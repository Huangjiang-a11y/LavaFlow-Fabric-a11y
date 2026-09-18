package dev.lavaflow.minecraft.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.*;
import com.mojang.renderpearl.api.commands.*;
import com.mojang.renderpearl.api.buffers.*;
import com.mojang.renderpearl.backend.api.*;
import com.mojang.renderpearl.api.textures.*;
import org.lwjgl.vulkan.VkPhysicalDeviceLimits;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1;

/** Minecraft-facing device backed only by LavaFlow-owned Vulkan objects. */
public final class LavaFlowDevice implements GpuDeviceBackend {
    private static final System.Logger LOGGER = System.getLogger(LavaFlowDevice.class.getName());
    private final LavaFlowVulkanContext context;
    private final DeviceInfo deviceInfo;
    private final LavaFlowCommandEncoder commandEncoder;
    private List<AutoCloseable> deferred = new ArrayList<>();
    private List<Runnable> callbacks = new ArrayList<>();
    private SubmitBatch completingBatch;
    private LavaFlowDescriptorCache descriptorCache;
    private boolean closed;

    static final class SubmitBatch {
        final List<AutoCloseable> resources;
        final List<Runnable> callbacks;

        SubmitBatch(List<AutoCloseable> resources, List<Runnable> callbacks) {
            this.resources = resources;
            this.callbacks = callbacks;
        }
    }

    public LavaFlowDevice() {
        this.context = new LavaFlowVulkanContext();
        this.descriptorCache = new LavaFlowDescriptorCache(context, this);
        VkPhysicalDeviceLimits limits = context.properties().limits();
        int maxAnisotropy = Math.max(1, (int)limits.maxSamplerAnisotropy());
        long maxMemoryAllocationSize = context.maxMemoryAllocationSize();
        // Interleaved multi-draw is emulated as a loop of single indexed draws, so no device limit
        // constrains how many draws one call may carry.
        int maxInterleavedDraws = Integer.MAX_VALUE;
        int physMaxTex = limits.maxImageDimension2D();
        // Trust the hardware-reported maxImageDimension2D when it is at least 8192. On modern
        // desktop GPUs (GTX 1080 Ti / RTX 4090 etc.) that value is 16384; clamping to 8192 here
        // would waste VRAM headroom. Fall back to 8192 only when the driver reports something
        // smaller (the Mali-G76 bug returns 0 here).
        int maxTex = physMaxTex >= 8192 ? physMaxTex : 8192;
        LOGGER.log(System.Logger.Level.INFO, "LavaFlow maxTextureSize=" + maxTex + "; device raw limits.maxImageDimension2D=" + physMaxTex);
        // Native multi-draw indirect is bounded by the device limit. When the device lacks the
        // feature the batch is split into single indirect draws (see LavaFlowRenderPass), so nothing
        // bounds the count in that case -- the same reasoning as maxInterleavedDraws above.
        int maxIndirectDraws = context.multiDrawIndirect()
                ? Math.max(1, limits.maxDrawIndirectCount())
                : Integer.MAX_VALUE;
        DeviceLimits blazeLimits = new DeviceLimits(maxAnisotropy, (int)limits.minUniformBufferOffsetAlignment(),
                maxTex, maxMemoryAllocationSize, maxInterleavedDraws,
                limits.maxColorAttachments(), maxIndirectDraws);
        // The multi-draw capabilities describe what LavaFlow's render pass accepts, not what the Vulkan
        // device exposes natively. Each is emulated with a loop of core Vulkan commands when the device
        // lacks the corresponding feature, so the Blaze3D-level capability holds on every supported
        // device. Advertising multiDrawDirectInterleaved matters for throughput: it lets callers pack
        // draws into a plain CPU array instead of an indirect-parameter buffer, which on tile-based GPUs
        // is host-visible memory the GPU has to read back once per draw.
        boolean multiDrawDirectInterleaved = !Boolean.getBoolean("lavaflow.forceNoMultiDrawDirect");
        // 26.3 prepended wireframeFillMode to DeviceFeatures; the remaining seven keep their 26.2
        // meaning and values. wireframeFillMode reports the Vulkan fillModeNonSolid capability, which
        // is what actually gates PolygonMode.LINE pipelines in LavaFlowRenderPipeline.
        DeviceFeatures features = new DeviceFeatures(context.fillModeNonSolid(), false, multiDrawDirectInterleaved,
                false, true, true, false, true);
        DeviceType type = switch (context.properties().deviceType()) {
            case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> DeviceType.INTEGRATED;
            case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> DeviceType.DISCRETE;
            case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> DeviceType.VIRTUAL;
            case VK_PHYSICAL_DEVICE_TYPE_CPU -> DeviceType.CPU;
            default -> DeviceType.OTHER;
        };
        Set<String> backendExtensions = new HashSet<>();
        backendExtensions.add("VK_KHR_swapchain");
        if (context.pushDescriptors()) backendExtensions.add("VK_KHR_push_descriptor");
        if (context.dynamicRendering()) backendExtensions.add("VK_KHR_dynamic_rendering");
        if (context.vertexAttributeDivisor()) backendExtensions.add("VK_EXT_vertex_attribute_divisor");
        LOGGER.log(System.Logger.Level.INFO,
                "Vulkan capabilities: dynamicRendering={0}, pushDescriptors={1}, multiDrawIndirect={2}, "
                        + "fillModeNonSolid={3}, vertexAttributeDivisor={4}",
                context.dynamicRendering(), context.pushDescriptors(), context.multiDrawIndirect(),
                context.fillModeNonSolid(), context.vertexAttributeDivisor());
        deviceInfo = new DeviceInfo(context.deviceName(), vendorName(context.properties().vendorID()),
                driverInfo(context), true,
                // Exactly "Vulkan", matching the name Minecraft's own backend reports. Mods select
                // their Vulkan code paths by comparing this string with equals — Distant Horizons
                // picks its OpenGL renderer for anything else — so a distinctive name here would
                // route them onto paths that cannot run. LavaFlow identifies itself in driverInfo.
                "Vulkan", limits.timestampPeriod(), blazeLimits, features,
                Set.copyOf(backendExtensions),
                // The two new 26.3 fields stay false: LavaFlow's legacy render-pass path accepts a
                // pass with no depth attachment, and its indirect-draw emulation is exercised by the
                // baselineDevice switch rather than being a known-bad path.
                new HintsAndWorkarounds(false, false, false, false), type);
        commandEncoder = new LavaFlowCommandEncoder(this);
    }

    private static String vendorName(int id) {
        return switch (id) {
            case 0x1002 -> "AMD"; case 0x10DE -> "NVIDIA"; case 0x8086 -> "Intel";
            case 0x13B5 -> "ARM"; case 0x5143 -> "Qualcomm"; default -> "0x" + Integer.toHexString(id);
        };
    }

    /**
     * The debug-screen driver line. Minecraft's debug overlay prints {@code backendName + " " +
     * driverInfo}, and the backend name is fixed to exactly "Vulkan" (see the constructor), so this
     * method supplies everything after that: the instance version LavaFlow requested, the highest
     * version the physical device actually supports, the driver version, and LavaFlow's own identity,
     * e.g. "1.1 (device 1.4.341) driver 610.43.3 LavaFlow 0.1.0-alpha".
     */
    private static String driverInfo(LavaFlowVulkanContext context) {
        int deviceApi = context.properties().apiVersion();
        int driver = context.properties().driverVersion();
        // NVIDIA packs its driver version 10.8.8.6 instead of Vulkan's standard 10.10.12 split.
        String driverVersion = context.properties().vendorID() == 0x10DE
                ? (driver >>> 22) + "." + ((driver >>> 14) & 0xFF) + "." + ((driver >>> 6) & 0xFF)
                : VK_VERSION_MAJOR(driver) + "." + VK_VERSION_MINOR(driver) + "." + VK_VERSION_PATCH(driver);
        return VK_VERSION_MAJOR(VK_API_VERSION_1_1) + "." + VK_VERSION_MINOR(VK_API_VERSION_1_1)
                + " (device " + VK_VERSION_MAJOR(deviceApi) + "." + VK_VERSION_MINOR(deviceApi) + "."
                + VK_VERSION_PATCH(deviceApi) + ") driver " + driverVersion
                + " LavaFlow " + LavaFlowVersion.get();
    }

    LavaFlowVulkanContext context() { return context; }
    LavaFlowDescriptorCache descriptorCache() { return descriptorCache; }

    /**
     * Retires the cached descriptor sets and buffer views that reference {@code handle}, because
     * that resource is going away. Called from resource destruction.
     */
    void invalidateDescriptorCache(long handle) { descriptorCache.invalidate(handle); }

    synchronized void defer(AutoCloseable resource) {
        (completingBatch == null ? deferred : completingBatch.resources).add(resource);
    }
    synchronized void afterSubmit(Runnable callback) { callbacks.add(callback); }
    synchronized SubmitBatch detachSubmitBatch(AutoCloseable transientResources) {
        if (transientResources != null) deferred.add(transientResources);
        if (deferred.isEmpty() && callbacks.isEmpty()) return null;
        SubmitBatch batch = new SubmitBatch(deferred, callbacks);
        deferred = new ArrayList<>();
        callbacks = new ArrayList<>();
        return batch;
    }
    synchronized void completeSubmit(SubmitBatch batch) {
        if (batch == null) return;
        RuntimeException failure = null;
        completingBatch = batch;
        // 索引遍历而非 for-each：close() 可能在同一线程内重入 defer()，
        // 而 defer() 在 completingBatch!=null 时会向本批 resources 追加，
        // 改变 ArrayList 结构。索引遍历不触发 Iterator 的 fail-fast，
        // 且能照原意把遍历期追加的资源一并回收。
        for (int i = 0; i < batch.resources.size(); i++) {
            try {
                batch.resources.get(i).close();
            } catch (Exception e) {
                failure = new RuntimeException(e);
            }
        }
        for (Runnable callback : batch.callbacks) callback.run();
        completingBatch = null;
        if (failure != null) throw failure;
    }
    synchronized void completePending() { completeSubmit(detachSubmitBatch(null)); }

    @Override public GpuSurfaceBackend createSurface(long window, BooleanSupplier isIconified) {
        if (window == 0) throw new IllegalArgumentException("window must be valid");
        // isIconified is unused: LavaFlow throttles through the swapchain present mode instead.
        return new LavaFlowGpuSurface(this, window);
    }
    @Override public CommandEncoderBackend createCommandEncoder() { ensureOpen(); return commandEncoder; }
    @Override public GpuSampler createSampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int anisotropy, OptionalDouble maxLod) {
        ensureOpen(); return new LavaFlowGpuSampler(this, u, v, min, mag, anisotropy, maxLod);
    }
    // 26.3 dropped createTexture(Supplier) from GpuDeviceBackend; the frontend now unwraps the
    // label and calls createTexture(String, ...) below. Removed rather than kept as a local
    // convenience because nothing inside LavaFlow calls it either.
    @Override public GpuTexture createTexture(String label, int usage, GpuFormat format, int width, int height, int layers, int mips) {
        ensureOpen(); return new LavaFlowGpuTexture(this, usage, label, format, width, height, layers, mips);
    }
    // No longer part of GpuDeviceBackend (the frontend owns the single-argument overload in 26.3),
    // but LavaFlowCommandEncoder still calls it, so it stays as an ordinary method.
    public GpuTextureView createTextureView(GpuTexture texture) { return createTextureView(texture, 0, texture.getMipLevels()); }
    @Override public GpuTextureView createTextureView(GpuTexture texture, int baseMip, int mips) {
        ensureOpen(); return new LavaFlowGpuTextureView(this, (LavaFlowGpuTexture)texture, baseMip, mips);
    }
    @Override public GpuBuffer createBuffer(Supplier<String> label, int usage, long size) {
        ensureOpen(); return new LavaFlowGpuBuffer(this, usage, size);
    }
    @Override public GpuBuffer createBuffer(Supplier<String> label, int usage, ByteBuffer initialData) {
        LavaFlowGpuBuffer buffer = new LavaFlowGpuBuffer(this, usage | GpuBuffer.USAGE_COPY_DST,
                initialData.remaining());
        commandEncoder.writeToBuffer(buffer.slice(), initialData);
        return buffer;
    }
    @Override public List<String> getLastDebugMessages() { return List.of(); }
    @Override public boolean isDebuggingEnabled() { return false; }
    /**
     * Compiles a backend render pipeline.
     *
     * <p>The work is deferred into the returned {@code Pending}: the frontend holds the compiled SPIR-V
     * until it calls {@code finishCompile()}, and releases the modules once that returns. The frontend
     * also owns pipeline caching (its {@code PipelineCache}, keyed by {@code RenderPipeline}), so no
     * cache lives here -- a backend pipeline exists exactly as long as the frontend keeps it.
     */
    @Override public BackendRenderPipeline.Pending compilePipeline(BackendRenderPipeline.CreateInfo pipelineCreateInfo) {
        // Compiled here rather than inside the returned lambda, matching the vanilla Vulkan backend: the
        // frontend calls this from its shader-compilation executor, and its "complete compile" step is then
        // only bookkeeping. Deferring the work to finishCompile() would instead do it on whichever thread
        // asks for the pipeline, which for the frontend means the render thread mid-frame.
        LavaFlowRenderPipeline pipeline = LavaFlowRenderPipeline.compile(this, pipelineCreateInfo);
        return () -> pipeline;
    }

    @Override public GpuQueryPool createTimestampQueryPool(int size) { return new LavaFlowQueryPool(context, size); }

    /**
     * Offset that converts a query-pool timestamp into {@link System#nanoTime()} space.
     *
     * <p>LavaFlow has no device-clock read and does not use {@code VK_EXT_calibrated_timestamps}, so
     * there is no honest offset to report; 0 is what the frontend gets. Timestamp queries still
     * produce usable <em>relative</em> durations, which is what LavaFlow's own frame stats use.
     */
    @Override public long getTimestampCalibrationOffset() { return 0L; }
    @Override public DeviceInfo getDeviceInfo() { return deviceInfo; }

    private void ensureOpen() { if (closed) throw new IllegalStateException("LavaFlow device is closed"); }
    @Override public synchronized void close() {
        if (closed) return; closed = true;
        commandEncoder.destroy();
        completePending();
        descriptorCache.destroy();
        context.close();
    }
}
