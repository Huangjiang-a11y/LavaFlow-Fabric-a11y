package dev.lavaflow.minecraft.vulkan;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import net.minecraft.resources.Identifier;
import org.lwjgl.vulkan.VkPhysicalDeviceLimits;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Supplier;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1;

/** Minecraft-facing device backed only by LavaFlow-owned Vulkan objects. */
public final class LavaFlowDevice implements GpuDeviceBackend {
    private static final System.Logger LOGGER = System.getLogger(LavaFlowDevice.class.getName());
    private final LavaFlowVulkanContext context;
    private final ShaderSource shaderSource;
    private final DeviceInfo deviceInfo;
    private final LavaFlowCommandEncoder commandEncoder;
    private List<AutoCloseable> deferred = new ArrayList<>();
    private List<Runnable> callbacks = new ArrayList<>();
    private SubmitBatch completingBatch;
    private final Map<RenderPipeline, LavaFlowRenderPipeline> pipelines = new IdentityHashMap<>();
    private final Map<ShaderKey, String> shaderSources = new HashMap<>();
    private RenderPipeline lastPipelineInfo;
    private LavaFlowRenderPipeline lastPipeline;
    private LavaFlowDescriptorCache descriptorCache;
    private boolean closed;

    private record ShaderKey(Identifier id, ShaderType type) {}
    static final class SubmitBatch {
        final List<AutoCloseable> resources;
        final List<Runnable> callbacks;

        SubmitBatch(List<AutoCloseable> resources, List<Runnable> callbacks) {
            this.resources = resources;
            this.callbacks = callbacks;
        }
    }

    public LavaFlowDevice(long window, ShaderSource shaderSource) {
        this.context = new LavaFlowVulkanContext(window);
        this.shaderSource = shaderSource;
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
        DeviceLimits blazeLimits = new DeviceLimits(maxAnisotropy, (int)limits.minUniformBufferOffsetAlignment(),
                maxTex, maxMemoryAllocationSize, maxInterleavedDraws,
                limits.maxColorAttachments());
        // The multi-draw capabilities describe what LavaFlow's render pass accepts, not what the Vulkan
        // device exposes natively. Each is emulated with a loop of core Vulkan commands when the device
        // lacks the corresponding feature, so the Blaze3D-level capability holds on every supported
        // device. Advertising multiDrawDirectInterleaved matters for throughput: it lets callers pack
        // draws into a plain CPU array instead of an indirect-parameter buffer, which on tile-based GPUs
        // is host-visible memory the GPU has to read back once per draw.
        boolean multiDrawDirectInterleaved = !Boolean.getBoolean("lavaflow.forceNoMultiDrawDirect");
        DeviceFeatures features = new DeviceFeatures(false, multiDrawDirectInterleaved, false, true, true, false, true);
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
                new HintsAndWorkarounds(false, false), type);
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
        for (AutoCloseable resource : batch.resources) {
            try {
                resource.close();
            } catch (Exception e) {
                failure = new RuntimeException(e);
            }
        }
        for (Runnable callback : batch.callbacks) callback.run();
        completingBatch = null;
        if (failure != null) throw failure;
    }
    synchronized void completePending() { completeSubmit(detachSubmitBatch(null)); }

    @Override public GpuSurfaceBackend createSurface(long window) {
        if (window == 0) throw new IllegalArgumentException("window must be valid");
        return new LavaFlowGpuSurface(this, window);
    }
    @Override public CommandEncoderBackend createCommandEncoder() { ensureOpen(); return commandEncoder; }
    @Override public GpuSampler createSampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int anisotropy, OptionalDouble maxLod) {
        ensureOpen(); return new LavaFlowGpuSampler(this, u, v, min, mag, anisotropy, maxLod);
    }
    @Override public GpuTexture createTexture(Supplier<String> label, int usage, GpuFormat format, int width, int height, int layers, int mips) {
        return createTexture(label == null ? "" : label.get(), usage, format, width, height, layers, mips);
    }
    @Override public GpuTexture createTexture(String label, int usage, GpuFormat format, int width, int height, int layers, int mips) {
        ensureOpen(); return new LavaFlowGpuTexture(this, usage, label, format, width, height, layers, mips);
    }
    @Override public GpuTextureView createTextureView(GpuTexture texture) { return createTextureView(texture, 0, texture.getMipLevels()); }
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
    @Override public CompiledRenderPipeline precompilePipeline(RenderPipeline pipeline, ShaderSource source) {
        ensureOpen();
        return pipelines.computeIfAbsent(pipeline,
                ignored -> new LavaFlowRenderPipeline(this, pipeline, source == null ? shaderSource : source));
    }

    synchronized String shaderText(Identifier id, ShaderType type, ShaderSource preferredSource) {
        ShaderKey key = new ShaderKey(id, type);
        String text = preferredSource == null ? null : preferredSource.get(id, type);
        if (text == null && preferredSource != shaderSource && shaderSource != null) {
            text = shaderSource.get(id, type);
        }
        if (text != null) {
            shaderSources.put(key, text);
            return text;
        }
        return shaderSources.get(key);
    }
    LavaFlowRenderPipeline pipeline(RenderPipeline pipeline) {
        if (pipeline == lastPipelineInfo) return lastPipeline;
        return findPipeline(pipeline);
    }
    private synchronized LavaFlowRenderPipeline findPipeline(RenderPipeline pipeline) {
        ensureOpen();
        LavaFlowRenderPipeline result = pipelines.computeIfAbsent(pipeline,
                ignored -> new LavaFlowRenderPipeline(this, pipeline, shaderSource));
        lastPipelineInfo = pipeline;
        lastPipeline = result;
        return result;
    }
    @Override public synchronized void clearPipelineCache() {
        lastPipelineInfo = null;
        lastPipeline = null;
        for (LavaFlowRenderPipeline pipeline : pipelines.values()) pipeline.close();
        pipelines.clear();
        // Set layout handles may be reused by the replacement pipelines, so cached sets keyed on the
        // old handles must not survive.
        descriptorCache.invalidateAll();
    }
    @Override public GpuQueryPool createTimestampQueryPool(int size) { return new LavaFlowQueryPool(context, size); }
    @Override public long getTimestampNow() { return System.nanoTime(); }
    @Override public DeviceInfo getDeviceInfo() { return deviceInfo; }

    private void ensureOpen() { if (closed) throw new IllegalStateException("LavaFlow device is closed"); }
    @Override public synchronized void close() {
        if (closed) return; closed = true;
        commandEncoder.destroy();
        clearPipelineCache();
        completePending();
        descriptorCache.destroy();
        context.close();
    }
}
