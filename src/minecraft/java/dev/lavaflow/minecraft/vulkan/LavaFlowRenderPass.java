package dev.lavaflow.minecraft.vulkan;

import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPassLayout;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import org.joml.Vector4fc;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Objects;
import java.util.function.Supplier;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdBeginRenderingKHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdEndRenderingKHR;
import static org.lwjgl.vulkan.KHRPushDescriptor.vkCmdPushDescriptorSetKHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan 1.1 render-pass attachment and draw state.
 *
 * <p>Beginning the Vulkan render pass is deferred until the first draw. Recording nothing up front
 * is what allows attachment layouts to be handled lazily: a texture stays in its attachment layout
 * across passes that only render to it, and is transitioned to the sampled layout only when a later
 * pass actually binds it as a texture — which is known once that pass starts drawing. Blaze3D
 * issues many small render passes per frame over the same attachments, so the eager alternative
 * costs a round trip of image barriers per pass, each of which drains the pipeline between
 * attachment writes and shader reads. State set before the first draw is buffered and replayed when
 * the pass begins; a pass that never draws records nothing at all unless it carries clears.
 */
final class LavaFlowRenderPass implements RenderPassBackend {

    private static final String LAYOUT_DEBUG = System.getProperty("lavaflow.debugLayoutChecks", "");
    // Layout of the VkMultiDrawIndexedInfoEXT records that multiDrawIndexed receives, as int indices.
    private static final int INDEXED_INFO_INTS = VkMultiDrawIndexedInfoEXT.SIZEOF / Integer.BYTES;
    private static final int INDEXED_INFO_FIRST_INDEX = VkMultiDrawIndexedInfoEXT.FIRSTINDEX / Integer.BYTES;
    private static final int INDEXED_INFO_INDEX_COUNT = VkMultiDrawIndexedInfoEXT.INDEXCOUNT / Integer.BYTES;
    private static final int INDEXED_INFO_VERTEX_OFFSET = VkMultiDrawIndexedInfoEXT.VERTEXOFFSET / Integer.BYTES;

    private final LavaFlowCommandEncoder encoder;
    private final LavaFlowVulkanContext context;
    private final RenderPassDescriptor descriptor;
    private final int outputWidth;
    private final int outputHeight;
    private final long renderPass;
    private final LavaFlowGpuTextureView[] colorViews;
    private final LavaFlowGpuTextureView depthView;
    private final boolean hasClears;
    // One slot per uniform the bound pipeline was compiled with, in the same order as the frontend's
    // uniform indices: 26.3 resolves a uniform name to its slot in the frontend and hands the backend
    // (index, value) pairs. Sized in setPipeline, then filled by setUniform.
    private final ArrayList<Object> uniforms = new ArrayList<>();
    private LavaFlowRenderPipeline pipeline;

    // 诊断开关：-Dlavaflow.debugDraws=true 时，开头若干帧里每次间接绘制记一行——当时绑定的管线名、
    // 命令条数、参数缓冲偏移。配合 -Dlavaflow.debugPipelines 打出的声明行，就能把"水面那批用的是
    // 哪条管线、参数从哪个偏移开始"对上号。只在开头若干帧记录，避免刷屏。
    private static final boolean DEBUG_DRAWS = Boolean.getBoolean("lavaflow.debugDraws");

    // 每种绘制入口第一次被调用时打一行，用来确定地形究竟走哪个方法——上一轮把记录器挂在
    // drawIndexedIndirect 和 drawIndexed 上都没出东西，说明猜入口不如让入口自己报名。
    private static final java.util.Set<String> SEEN_DRAW_KINDS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // 每条被设进 pass 的管线第一次出现时报一次到。报名行只证明了 gui 与一个 blit 走过绘制入口，
    // 地形一条都没有；这一步用来确认地形管线到底有没有进过 LavaFlow 的 pass。
    private static final java.util.Set<String> SEEN_PIPELINES = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void notePipeline(String name) {
        if (!DEBUG_DRAWS) return;
        if (!SEEN_PIPELINES.add(name)) return;
        System.getLogger("LavaFlow").log(System.Logger.Level.INFO, "pass-pipeline " + name);
    }

    private void noteDrawKind(String kind) {
        if (!DEBUG_DRAWS) return;
        if (!SEEN_DRAW_KINDS.add(kind)) return;
        System.getLogger("LavaFlow").log(System.Logger.Level.INFO,
                "draw-kind " + kind + " pipeline=" + (pipeline == null ? "(none)" : pipeline.name()));
    }
    private static long drawLogFrame = -1;
    private static int drawLogThisFrame = 0;

    // 逐条索引绘制的摘要。只记名字里含 terrain 或 water 的管线——地形与水面都在这两类里，
    // 而 GUI、实体、粒子会把这行淹掉。要看的正是这批 drawIndexed 的实际参数：indexCount、
    // firstIndex、baseVertex。"顶点读偏"最终都落在这三个数的组合上。
    /** 地形绘制摘要的条数上限，从第一笔地形绘制开始数。 */
    private static final int TERRAIN_DRAW_LOG_CAP = 400;
    private static int terrainDrawsLogged;

    /**
     * 一笔地形绘制的全部读数：管线、索引参数、顶点/索引缓冲句柄，以及本次绘制时各 uniform 槽的
     * 缓冲与偏移。上一版把窗口挂在全局帧号上，而进世界发生在第 3000 帧之后，窗口早就关了；这里
     * 改成从第一笔地形绘制开始数，进世界多久都能记上。uniform 偏移是每段区块的位置数据，哪几笔
     * 绘制共用了同一段，一眼就能看出来。
     */
    private void logIndexedDraw(int indexCount, int firstIndex, int baseVertex) {
        String name = pipeline == null ? "(none)" : pipeline.name();
        if (!name.contains("terrain") && !name.contains("water")) return;
        // 前 TERRAIN_DRAW_LOG_CAP 笔照旧记；此后只在"出现过索引写入重叠"的帧附近再记，
        // 这样重叠那几帧的参数一定在日志里，而不会被前面的几千笔挤掉。
        if (terrainDrawsLogged >= TERRAIN_DRAW_LOG_CAP
                && !LavaFlowDataWatch.overlapping(LavaFlowFrameStats.totalFrames())) return;
        terrainDrawsLogged++;
        StringBuilder line = new StringBuilder(192);
        line.append("terrain-draw #").append(terrainDrawsLogged)
                .append(" frame=").append(LavaFlowFrameStats.totalFrames()).append(' ').append(name)
                .append(" indexCount=").append(indexCount).append(" firstIndex=").append(firstIndex)
                .append(" baseVertex=").append(baseVertex)
                .append(" vbuf0=").append(Long.toHexString(encoder.boundVertexHandle(0)))
                .append("+").append(encoder.boundVertexOffset(0))
                .append(" ibuf=").append(Long.toHexString(encoder.boundIndexHandle()))
                .append(" itype=").append(encoder.boundIndexType());
        for (int i = 0; i < uniforms.size(); i++) {
            if (uniforms.get(i) instanceof GpuBufferSlice slice) {
                line.append(" u").append(i).append('=')
                        .append(Long.toHexString(((LavaFlowGpuBuffer) slice.buffer()).handle()))
                        .append('@').append(slice.offset());
            }
        }
        System.getLogger("LavaFlow").log(System.Logger.Level.INFO, line.toString());
    }

    private void logIndirectDraw(GpuBufferSlice buffer, int count) {
        long frame = LavaFlowFrameStats.totalFrames();
        if (frame > 240) return;
        if (frame != drawLogFrame) {
            drawLogFrame = frame;
            drawLogThisFrame = 0;
        }
        if (drawLogThisFrame >= 3) return;
        drawLogThisFrame++;
        System.getLogger("LavaFlow").log(System.Logger.Level.INFO,
                "indirect pipeline=" + (pipeline == null ? "(none)" : pipeline.name())
                        + " count=" + count + " offset=" + buffer.offset() + " frame=" + frame);
    }

    // 通道的颜色附件数与管线声明的数量不一致这件事只说一次：真出问题时驱动可能把进程带走，
    // 每个 draw 一行也没机会落盘。
    private boolean colorTargetMismatchReported;
    // 诊断用：当前绑在 slot 0 的顶点缓冲、以及当前的索引缓冲的"对象本身"——哨兵要拿它们的大小
    // 和主机映射来核对这一笔绘制到底在读什么。句柄记录里没有这些。
    private LavaFlowGpuBuffer watchedVertexBuffer;
    private LavaFlowGpuBuffer watchedIndexBuffer;
    /** slot 0 的绑定偏移：顶点字节的地址要在缓冲基址上加它。 */
    private long watchedVertexOffset;
    private boolean descriptorsDirty = true;
    private boolean begun;
    private boolean ended;

    // Fixed-function state buffered until the pass begins; recorded directly afterwards.
    private int scissorX;
    private int scissorY;
    private int scissorWidth;
    private int scissorHeight;
    private final long[] pendingVertexBuffers = new long[CompiledRenderPipeline.CreateInfo.MAX_VERTEX_BUFFERS];
    private final long[] pendingVertexOffsets = new long[CompiledRenderPipeline.CreateInfo.MAX_VERTEX_BUFFERS];
    // Scratch arrays for the descriptor-set path. That path runs on every descriptor change rather than
    // once per set — a set is only allocated when a binding combination has not been seen before — so the
    // garbage it used to produce was worth removing. They are sized exactly to the binding count because
    // the cache key hashes the whole array: a longer one would put its tail into the key. Safe to
    // overwrite on return — the cache copies the key it retains (see allocateAndStore).
    private long[] keyScratch = new long[0];
    private long[] resourceScratch = new long[0];
    // The legacy path rebuilds its attachment list on every pass begin. That array is read once on the
    // way into legacyFramebuffer, which copies the key it retains, so one array can serve every call and
    // is grown only when the attachment count changes — the same shape as the two above.
    private long[] attachmentScratch = new long[0];
    // Descriptor lookups reuse one key: the array it points at is the scratch above, and allocateAndStore
    // copies whatever it retains, so the probe never lands in a map. That removes the per-push Key object
    // (~15 a frame) without touching the cache's identity rules.
    private final LavaFlowDescriptorCache.Key keyProbe = new LavaFlowDescriptorCache.Key();
    // sampledTextures() runs on every descriptor push and on every pass begin, so its list is filled in
    // place rather than allocated per call. Callers that may re-enter the fill (pushDescriptors ->
    // splitForSampledTransitions) hand the filled list down instead of calling back in.
    private final List<LavaFlowGpuTexture> sampledScratch = new ArrayList<>();
    // The legacy path builds its format/load-op arrays once, at construction; the all-load variant used
    // when the pass is split is built once too. Both are keyed by the render pass cache, which copies
    // what it retains, so reusing one pair of arrays is safe.
    private int[] colorFormats;
    private int[] colorLoadOps;
    private long resumeRenderPass;
    private int pendingVertexMask;
    // The slot-local index of this pass's start timestamp, or -1 when per-pass timing is off.
    private int timedPass = -1;
    private long pendingIndexBuffer;
    private int pendingIndexType = -1;

    LavaFlowRenderPass(LavaFlowCommandEncoder encoder, RenderPassDescriptor descriptor) {
        this.encoder = encoder;
        this.context = encoder.device().context();
        this.descriptor = descriptor;

        int colorSlots = colorSlotCount();
        RenderPassDescriptor.Attachment depth = depthAttachment();
        GpuTextureView extentView = null;
        for (int slot = 0; slot < colorSlots; slot++) {
            RenderPassDescriptor.Attachment attachment = colorAttachment(slot);
            if (attachment != null) extentView = attachment.textureView();
        }
        if (extentView == null && depth != null) extentView = depth.textureView();
        if (extentView == null) throw new IllegalArgumentException("Render pass has no attachments");
        outputWidth = extentView.getWidth(0);
        outputHeight = extentView.getHeight(0);

        colorViews = new LavaFlowGpuTextureView[colorSlots];
        boolean clearAny = clears(depth, false);
        for (int slot = 0; slot < colorSlots; slot++) {
            RenderPassDescriptor.Attachment attachment = colorAttachment(slot);
            if (attachment == null) continue;
            colorViews[slot] = view(attachment.textureView());
            clearAny |= clears(attachment, false);
        }
        depthView = depth == null ? null : view(depth.textureView());
        hasClears = clearAny;

        if (context.dynamicRendering()) {
            renderPass = 0;
        } else {
            colorFormats = new int[colorSlots];
            colorLoadOps = new int[colorSlots];
            for (int slot = 0; slot < colorSlots; slot++) {
                RenderPassDescriptor.Attachment attachment = colorAttachment(slot);
                if (colorViews[slot] == null) {
                    colorFormats[slot] = VK_FORMAT_UNDEFINED;
                    colorLoadOps[slot] = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
                } else {
                    colorFormats[slot] = LavaFlowVk.format(colorViews[slot].texture().getFormat());
                    colorLoadOps[slot] = loadOp(attachment, false);
                }
            }
            int depthFormat = depthView == null ? VK_FORMAT_UNDEFINED
                    : LavaFlowVk.format(depthView.texture().getFormat());
            int depthLoadOp = depth == null ? VK_ATTACHMENT_LOAD_OP_DONT_CARE : loadOp(depth, false);
            renderPass = context.legacyRenderPass(colorFormats, colorLoadOps, depthFormat, depthLoadOp);
        }

        RenderPass.RenderArea area = descriptor.renderArea();
        scissorX = area.x();
        scissorY = area.y();
        scissorWidth = area.width();
        scissorHeight = area.height();
    }

    /**
     * Records everything the first draw depends on: layout transitions, the render pass begin, and
     * the state calls buffered so far. Layout transitions must precede the begin, which is why the
     * begin waits for the first draw — only then is it known which bound textures still need to
     * leave their attachment layout.
     */
    void ensureBegun() {
        if (begun) return;
        begun = true;
        for (LavaFlowGpuTexture sampled : sampledTextures()) {
            int layout = LavaFlowVk.sampledLayout();
            if (sampled.layout() != layout) {
                encoder.transition(sampled, layout);
            }
        }
        for (LavaFlowGpuTextureView view : colorViews) {
            if (view != null) encoder.transition(view.texture(), VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        }
        if (depthView != null) {
            encoder.transition(depthView.texture(), VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        }
        if (!LAYOUT_DEBUG.isEmpty()) debugValidateLayouts();
        // Outside the render pass on purpose: the timestamps bracket begin..end, and recording them inside
        // would put pipeline work between the tile load and the tile store this is meant to measure.
        timedPass = encoder.beginTimedPass(this::passLabel);
        try (MemoryStack stack = stackPush()) {
            if (context.dynamicRendering()) beginDynamic(stack, false);
            else beginLegacy(stack, false);
            setViewport(stack);
            setScissor(stack, scissorX, scissorY, scissorWidth, scissorHeight);
        }
        if (pipeline != null) recordPipelineBind();
        if (pendingVertexMask != 0) {
            try (MemoryStack stack = stackPush()) {
                for (int slot = 0; slot < pendingVertexBuffers.length; slot++) {
                    if ((pendingVertexMask & (1 << slot)) == 0) continue;
                    LavaFlowFrameStats.vertexBufferBindRecorded();
                    vkCmdBindVertexBuffers(encoder.commandBuffer(), slot,
                            stack.longs(pendingVertexBuffers[slot]), stack.longs(pendingVertexOffsets[slot]));
                    encoder.recordVertexBound(slot, pendingVertexBuffers[slot], pendingVertexOffsets[slot]);
                }
            }
            pendingVertexMask = 0;
        }
        if (pendingIndexType >= 0) {
            LavaFlowFrameStats.indexBufferBindRecorded();
            vkCmdBindIndexBuffer(encoder.commandBuffer(), pendingIndexBuffer, 0, pendingIndexType);
            encoder.recordIndexBound(pendingIndexBuffer, pendingIndexType);
            pendingIndexType = -1;
        }
    }

    /**
     * Ends the pass. A pass that never began but carries clears is begun and ended empty so its
     * load-op clears still happen; one with neither draws nor clears records nothing.
     */
    void end() {
        if (ended) return;
        ended = true;
        if (!begun) {
            if (!hasClears) return;
            ensureBegun();
        }
        if (context.dynamicRendering()) vkCmdEndRenderingKHR(encoder.commandBuffer());
        else vkCmdEndRenderPass(encoder.commandBuffer());
        encoder.endTimedPass(timedPass);
        timedPass = -1;
    }

    /** The frontend's name for this pass, used as the key of the per-pass GPU breakdown. */
    private String passLabel() {
        var label = descriptor.label();
        String value = label == null ? null : label.get();
        return value == null || value.isEmpty() ? "unnamed" : value;
    }

    private void beginDynamic(MemoryStack stack, boolean resume) {
        int colorSlots = colorSlotCount();
        RenderPassDescriptor.Attachment depth = depthAttachment();
        VkRenderingAttachmentInfo.Buffer colorAttachments = VkRenderingAttachmentInfo.calloc(colorSlots, stack);
        for (int slot = 0; slot < colorSlots; slot++) {
            RenderPassDescriptor.Attachment attachment = colorAttachment(slot);
            VkRenderingAttachmentInfo renderingAttachment = colorAttachments.get(slot).sType$Default();
            if (attachment == null) {
                renderingAttachment.imageView(0).imageLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                        .loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE);
                continue;
            }
            boolean clear = clears(attachment, resume);
            renderingAttachment.imageView(colorViews[slot].handle())
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(loadOp(attachment, resume))
                    .storeOp(attachment.store() ? VK_ATTACHMENT_STORE_OP_STORE : VK_ATTACHMENT_STORE_OP_DONT_CARE);
            if (clear && attachment.clearValue().color() != null) {
                Vector4fc value = attachment.clearValue().color();
                renderingAttachment.clearValue().color()
                        .float32(stack.floats(value.x(), value.y(), value.z(), value.w()));
            }
        }
        VkRenderingAttachmentInfo depthAttachment = null;
        if (depthView != null) {
            depthAttachment = VkRenderingAttachmentInfo.calloc(stack).sType$Default()
                    .imageView(depthView.handle()).imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    .loadOp(loadOp(depth, resume))
                    .storeOp(depth.store() ? VK_ATTACHMENT_STORE_OP_STORE : VK_ATTACHMENT_STORE_OP_DONT_CARE);
            if (clears(depth, resume)) depthAttachment.clearValue().depthStencil()
                    .depth(depth.clearValue().depth()).stencil(depth.clearValue().stencil());
        }
        RenderPass.RenderArea area = descriptor.renderArea();
        VkRenderingInfo rendering = VkRenderingInfo.calloc(stack).sType$Default()
                .layerCount(1).viewMask(0).pColorAttachments(colorAttachments);
        rendering.renderArea().offset().set(area.x(), area.y());
        rendering.renderArea().extent().set(area.width(), area.height());
        if (depthAttachment != null) rendering.pDepthAttachment(depthAttachment);
        vkCmdBeginRenderingKHR(encoder.commandBuffer(), rendering);
    }

    private void beginLegacy(MemoryStack stack, boolean resume) {
        int colorSlots = colorSlotCount();
        RenderPassDescriptor.Attachment depth = depthAttachment();
        int attachmentCount = depthView == null ? 0 : 1;
        for (LavaFlowGpuTextureView view : colorViews) if (view != null) attachmentCount++;
        if (attachmentScratch.length != attachmentCount) attachmentScratch = new long[attachmentCount];
        long[] views = attachmentScratch;
        VkClearValue.Buffer clearValues = VkClearValue.calloc(attachmentCount, stack);
        int attachmentIndex = 0;
        for (int slot = 0; slot < colorViews.length; slot++) {
            LavaFlowGpuTextureView view = colorViews[slot];
            if (view == null) continue;
            views[attachmentIndex] = view.handle();
            RenderPassDescriptor.Attachment attachment = colorAttachment(slot);
            if (clears(attachment, resume) && attachment.clearValue().color() != null) {
                Vector4fc value = attachment.clearValue().color();
                clearValues.get(attachmentIndex).color()
                        .float32(stack.floats(value.x(), value.y(), value.z(), value.w()));
            }
            attachmentIndex++;
        }
        if (depthView != null) {
            views[attachmentIndex] = depthView.handle();
            if (clears(depth, resume)) clearValues.get(attachmentIndex).depthStencil()
                    .depth(depth.clearValue().depth()).stencil(depth.clearValue().stencil());
        }
        long beginRenderPass = resume ? resumeRenderPass() : renderPass;
        long framebuffer = context.legacyFramebuffer(beginRenderPass, views, outputWidth, outputHeight);
        RenderPass.RenderArea area = descriptor.renderArea();
        VkRenderPassBeginInfo begin = VkRenderPassBeginInfo.calloc(stack).sType$Default()
                .renderPass(beginRenderPass).framebuffer(framebuffer).pClearValues(clearValues);
        begin.renderArea().offset().set(area.x(), area.y());
        begin.renderArea().extent().set(area.width(), area.height());
        vkCmdBeginRenderPass(encoder.commandBuffer(), begin, VK_SUBPASS_CONTENTS_INLINE);
    }

    /**
     * The all-load variant of this pass's render pass, used when the pass is split mid-way. Load ops
     * do not affect render-pass compatibility, so pipelines built against the original stay valid.
     */
    private long resumeRenderPass() {
        if (resumeRenderPass != 0) return resumeRenderPass;
        // Formats are the ones built at construction; only the load ops differ (all LOAD so the split
        // does not lose the first half of the pass).
        for (int i = 0; i < colorLoadOps.length; i++) {
            if (colorViews[i] != null) colorLoadOps[i] = VK_ATTACHMENT_LOAD_OP_LOAD;
        }
        int depthFormat = depthView == null ? VK_FORMAT_UNDEFINED
                : LavaFlowVk.format(depthView.texture().getFormat());
        resumeRenderPass = context.legacyRenderPass(colorFormats, colorLoadOps, depthFormat,
                VK_ATTACHMENT_LOAD_OP_LOAD);
        return resumeRenderPass;
    }

    /**
     * Ends the running pass, transitions every bound texture to the sampled layout, and resumes the
     * pass with load ops so no contents are lost.
     *
     * <p>Needed when a texture is bound for sampling after the pass has begun while still in an
     * attachment layout: barriers cannot be recorded inside a render pass. Dynamic state, the bound
     * pipeline, and descriptor bindings are command-buffer state and survive the boundary untouched.
     */
    /**
     * Flushes a pending buffer-write dependency before a draw, keeping the barrier outside the pass.
     *
     * <p>Inside a render pass a barrier may only use graphics stages
     * (VUID-vkCmdPipelineBarrier-None-07892) and, on the legacy path, needs a subpass self-dependency
     * the render pass does not have (VUID-vkCmdPipelineBarrier-None-07889). A device copy is a transfer,
     * which is not a graphics stage, so there is no legal in-pass form for "the copy finished, now read
     * those vertices" — the pass has to be split instead, the same shape as splitForSampledTransitions.
     */
    private void flushPendingBufferBarrier() {
        if (!encoder.bufferWriteBarrierPending()) return;
        if (!begun) {
            encoder.flushBufferWriteBarrier();
            return;
        }
        splitForBufferBarrier();
    }

    /** Ends the pass, flushes the buffer-write barrier outside it, and resumes with load ops. */
    private void splitForBufferBarrier() {
        LavaFlowFrameStats.passSplit();
        if (context.dynamicRendering()) vkCmdEndRenderingKHR(encoder.commandBuffer());
        else vkCmdEndRenderPass(encoder.commandBuffer());
        encoder.flushBufferWriteBarrier();
        try (MemoryStack stack = stackPush()) {
            if (context.dynamicRendering()) beginDynamic(stack, true);
            else beginLegacy(stack, true);
        }
    }

    private void splitForSampledTransitions(List<LavaFlowGpuTexture> sampled) {
        LavaFlowFrameStats.passSplit();
        if (context.dynamicRendering()) vkCmdEndRenderingKHR(encoder.commandBuffer());
        else vkCmdEndRenderPass(encoder.commandBuffer());
        for (LavaFlowGpuTexture texture : sampled) {
            int layout = LavaFlowVk.sampledLayout();
            if (texture.layout() != layout) {
                encoder.transition(texture, layout);
            }
        }
        try (MemoryStack stack = stackPush()) {
            if (context.dynamicRendering()) beginDynamic(stack, true);
            else beginLegacy(stack, true);
        }
    }

    /**
     * Layout precondition checks for render-pass begin. Mali enforces the Vulkan spec far more
     * strictly than desktop drivers: an attachment or sampled texture left in an incompatible layout
     * surfaces as texture corruption/tearing rather than a validation error. With
     * -Dlavaflow.debugLayoutChecks=throw this throws (with the offending texture identity and its
     * raw layout value) so the exact violation can be pinpointed on-device; with the flag set to any
     * other non-empty value it only warns.
     */
    private void debugValidateLayouts() {
        for (LavaFlowGpuTexture t : sampledTextures()) {
            int l = t.layout();
            if (l != VK_IMAGE_LAYOUT_GENERAL && l != VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
                report("Sampled texture " + System.identityHashCode(t) + " has pre-begin layout " + l
                        + " (expected GENERAL or SHADER_READ_ONLY_OPTIMAL)");
            }
        }
        for (LavaFlowGpuTextureView view : colorViews) {
            if (view == null) continue;
            int l = view.texture().layout();
            if (l != VK_IMAGE_LAYOUT_GENERAL && l != VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL) {
                report("Color attachment " + System.identityHashCode(view.texture()) + " has pre-begin layout " + l
                        + " (expected GENERAL or COLOR_ATTACHMENT_OPTIMAL)");
            }
        }
        if (depthView != null) {
            int l = depthView.texture().layout();
            if (l != VK_IMAGE_LAYOUT_GENERAL && l != VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL) {
                report("Depth attachment " + System.identityHashCode(depthView.texture()) + " has pre-begin layout " + l
                        + " (expected GENERAL or DEPTH_STENCIL_ATTACHMENT_OPTIMAL)");
            }
        }
    }

    private static void report(String message) {
        String full = "[LavaFlow layout-debug] " + message;
        if (LAYOUT_DEBUG.equals("throw")) throw new IllegalStateException(full);
        System.getLogger("LavaFlow").log(System.Logger.Level.WARNING, full);
    }

    private void setViewport(MemoryStack stack) {
        VkViewport.Buffer viewport = VkViewport.calloc(1, stack).x(0).y(0)
                .width(outputWidth).height(outputHeight).minDepth(0).maxDepth(1);
        vkCmdSetViewport(encoder.commandBuffer(), 0, viewport);
    }

    private void setScissor(MemoryStack stack, int x, int y, int width, int height) {
        VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
        scissor.offset().set(x, y);
        scissor.extent().set(width, height);
        vkCmdSetScissor(encoder.commandBuffer(), 0, scissor);
    }

    private void recordPipelineBind() {
        LavaFlowFrameStats.pipelineBindRecorded();
        int depthVkFormat = depthView == null ? VK_FORMAT_UNDEFINED
                : LavaFlowVk.format(depthView.texture().getFormat());
        // 旧版渲染通道的子通道颜色附件数是写死的，VkPipelineColorBlendStateCreateInfo 必须正好
        // 报这么多。数量能对上就不必多说；对不上时驱动可能读越界（Mali 上是空指针），这一行是
        // 崩溃前唯一能落盘的证词，所以只说一次、直接 WARNING。
        int passColorTargets = colorSlotCount();
        if (passColorTargets != pipeline.colorTargetCount() && !colorTargetMismatchReported) {
            colorTargetMismatchReported = true;
            System.getLogger("LavaFlow").log(System.Logger.Level.WARNING,
                    "render pass colour attachments " + passColorTargets + " != pipeline "
                            + pipeline.name() + " declares " + pipeline.colorTargetCount()
                            + "; padding the blend state to the larger count");
        }
        vkCmdBindPipeline(encoder.commandBuffer(), VK_PIPELINE_BIND_POINT_GRAPHICS,
                pipeline.pipelineFor(depthVkFormat, renderPass, passColorTargets));
    }

    // ---- snapshot-6 的附件模型 ------------------------------------------------------------
    // 6 里渲染通道不再分"颜色列表 / 深度附件"，而是由 RenderPassLayout 的 subpass 声明附件下标：
    // colorAttachments() 是每个颜色槽对应的附件下标（ByteList，槽位顺序即绘制顺序），
    // depthStencilAttachment() 是深度槽的附件下标，ATTACHMENT_UNUSED 表示该槽空着。
    // 附件本身是 RenderPassDescriptor.Attachment(textureView, load, store, clearValue)。
    private RenderPassLayout.Subpass subpass0() { return descriptor.layout().subpass(0); }

    private RenderPassDescriptor.Attachment attachmentAt(int index) {
        return index == RenderPassLayout.ATTACHMENT_UNUSED || index >= descriptor.attachments().size()
                ? null : descriptor.attachments().get(index);
    }

    private RenderPassDescriptor.Attachment colorAttachment(int slot) {
        return slot >= subpass0().colorAttachments().size()
                ? null : attachmentAt(subpass0().colorAttachments().getByte(slot));
    }

    private RenderPassDescriptor.Attachment depthAttachment() {
        return attachmentAt(subpass0().depthStencilAttachment());
    }

    private int colorSlotCount() { return subpass0().colorAttachments().size(); }

    /** 该附件这一帧要不要清：有 clear 值且不是续录。 */
    private static boolean clears(RenderPassDescriptor.Attachment attachment, boolean resume) {
        return !resume && attachment != null && attachment.clearValue() != null;
    }

    /** 该附件的 load op：能清就清，否则按 load 标志决定是保留还是不用。 */
    private static int loadOp(RenderPassDescriptor.Attachment attachment, boolean resume) {
        if (clears(attachment, resume)) return VK_ATTACHMENT_LOAD_OP_CLEAR;
        return attachment != null && attachment.load() ? VK_ATTACHMENT_LOAD_OP_LOAD : VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    }

    @Override public void nextSubpass() {
        // LavaFlow 按单子通道录制：旧的 VkRenderPass 只声明一个 subpass，动态渲染本来也没有 subpass。
        // 单子通道布局下这一步是无操作；真出现多子通道布局，在旧路径上如实推进一步，动态渲染明确拒绝。
        if (descriptor.layout().subpasses().size() <= 1 || !begun) return;
        if (context.dynamicRendering()) {
            throw new IllegalStateException("nextSubpass is not supported with dynamic rendering");
        }
        vkCmdNextSubpass(encoder.commandBuffer(), VK_SUBPASS_CONTENTS_INLINE);
    }

    boolean hasDepth() { return depthAttachment() != null; }


    @Override public void pushDebugGroup(Supplier<String> label) {}
    @Override public void popDebugGroup() {}
    @Override public void setPipeline(BackendRenderPipeline pipeline) {
        if (!(pipeline instanceof LavaFlowRenderPipeline lavaPipeline)) {
            throw new IllegalArgumentException("Pipeline must be a LavaFlowRenderPipeline");
        }
        if (LavaFlowElision.PIPELINE && this.pipeline == lavaPipeline) {
            // Elided here is only the vkCmdBindPipeline recording, not the accounting: the counters
            // describe what the frontend does, and hiding repeat calls would paint rebind_ratio as 0.
            LavaFlowFrameStats.pipelineBind(true);
            return;
        }
        LavaFlowFrameStats.pipelineBind(false);
        this.pipeline = lavaPipeline;
        notePipeline(lavaPipeline.name());
        // One slot per compiled uniform; the frontend replays the uniforms it already holds right after
        // this call, and 26.3 resolves names to slots there rather than here.
        int uniformCount = lavaPipeline.entries().size();
        uniforms.clear();
        // Capacity is kept between passes, so only the first pass allocates; the fill avoids the
        // nCopies wrapper and its iterator that the clear-and-addAll form produced on every pass.
        uniforms.ensureCapacity(uniformCount);
        for (int i = 0; i < uniformCount; i++) uniforms.add(null);
        if (begun) recordPipelineBind();
        descriptorsDirty = true;
    }
    @Override public void setUniform(int index, Object value) {
        if (index < 0 || index >= uniforms.size()) {
            throw new IllegalStateException("Uniform index " + index + " is outside the bound pipeline's "
                    + uniforms.size() + " uniforms");
        }
        if (LavaFlowElision.UNIFORM && Objects.equals(uniforms.get(index), value)) {
            return;
        }
        uniforms.set(index, value);
        LavaFlowFrameStats.uniformChanged();
        descriptorsDirty = true;
    }
    @Override public void pushConstants(ByteBuffer value) {
        if (pipeline == null) throw new IllegalStateException("Must bind a pipeline before pushing constants");
        vkCmdPushConstants(encoder.commandBuffer(), pipeline.pipelineLayout(), Integer.MAX_VALUE, 0, value);
    }
    @Override public void enableScissor(int x, int y, int width, int height) {
        if (begun) {
            try (MemoryStack stack = stackPush()) { setScissor(stack, x, y, width, height); }
        } else {
            scissorX = x;
            scissorY = y;
            scissorWidth = width;
            scissorHeight = height;
        }
    }
    @Override public void disableScissor() {
        RenderPass.RenderArea area = descriptor.renderArea();
        enableScissor(area.x(), area.y(), area.width(), area.height());
    }
    @Override public void setVertexBuffer(int slot, GpuBufferSlice buffer) {
        if (buffer == null) {
            // The frontend means "leave this binding alone", not "bind nothing": Minecraft's own Vulkan and GL
            // backends both return here without recording a thing. Passing VK_NULL_HANDLE through is legal by
            // the letter of the spec (VUID-vkCmdBindVertexBuffers-pBuffers-parameter allows null elements, and
            // an attribute bound to a null binding reads as zero), but a Vivo PD2284 (Mali-G610 MC6, driver
            // 32.1.0) took SIGSEGV inside libGLES_mali.so+0x1664004 while recording exactly this call —
            // si_addr=0x20 with R1=0, the driver dereferencing the null handle it was handed. The vanilla cloud
            // renderer asks for it once per frame: CloudRenderer.render calls setVertexBuffer(0, null) and reads
            // its geometry out of the CloudFaces/CloudInfo storage buffers, so without this the first clouds
            // draw of a world kills the process on that GPU. Sodium replaces that path, which is why only the
            // Sodium-free device hit it.
            return;
        }
        long handle = ((LavaFlowGpuBuffer) buffer.buffer()).handle();
        long offset = buffer.offset();
        if (slot == 0) {
            watchedVertexBuffer = (LavaFlowGpuBuffer) buffer.buffer();
            watchedVertexOffset = offset;
        }
        // A device run measured 92.8 recorded vertex binds per frame against 92.8 draws: the frontend asks for
        // the same slot over and over, and each request that matches what is already bound would change
        // nothing. The same bargain as setPipeline and setUniform — the request is counted either way, only the
        // command is dropped — but the question has to be asked against the encoder's record rather than the
        // pass's, because a binding outlives the pass it was recorded in.
        boolean repeated = LavaFlowElision.VERTEX && encoder.vertexBound(slot, handle, offset);
        LavaFlowFrameStats.vertexBufferBindCalled(repeated);
        if (repeated) return;
        if (begun) {
            try (MemoryStack stack = stackPush()) {
                LavaFlowFrameStats.vertexBufferBindRecorded();
                vkCmdBindVertexBuffers(encoder.commandBuffer(), slot, stack.longs(handle), stack.longs(offset));
            }
            encoder.recordVertexBound(slot, handle, offset);
        } else {
            pendingVertexBuffers[slot] = handle;
            pendingVertexOffsets[slot] = offset;
            pendingVertexMask |= 1 << slot;
        }
    }
    @Override public void setIndexBuffer(GpuBuffer buffer, IndexType type) {
        long handle = ((LavaFlowGpuBuffer) buffer).handle();
        watchedIndexBuffer = (LavaFlowGpuBuffer) buffer;
        int vkType = type == IndexType.SHORT ? VK_INDEX_TYPE_UINT16 : VK_INDEX_TYPE_UINT32;
        // Same bargain as setVertexBuffer: the request is not hidden from the frontend's counters, only
        // the redundant vkCmdBindIndexBuffer is dropped. The binding outlives the pass it was recorded
        // in, so the question goes to the encoder.
        boolean repeated = LavaFlowElision.INDEX && encoder.indexBound(handle, vkType);
        LavaFlowFrameStats.indexBufferBindCalled(repeated);
        if (repeated) return;
        if (begun) {
            LavaFlowFrameStats.indexBufferBindRecorded();
            vkCmdBindIndexBuffer(encoder.commandBuffer(), handle, 0, vkType);
            encoder.recordIndexBound(handle, vkType);
        } else {
            pendingIndexBuffer = handle;
            pendingIndexType = vkType;
        }
    }
    @Override public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance) {
        flushPendingBufferBarrier();
        pushDescriptors();
        noteDrawKind("drawIndexed");
        if (LavaFlowDrawCheck.ENABLED) {
            LavaFlowDrawCheck.check(LavaFlowFrameStats.totalFrames(), pipeline == null ? "" : pipeline.name(),
                    watchedVertexBuffer, watchedVertexOffset, baseVertex, watchedIndexBuffer,
                    encoder.boundIndexType() == VK_INDEX_TYPE_UINT16 ? 2 : 4, firstIndex, indexCount);
        }
        if (DEBUG_DRAWS) logIndexedDraw(indexCount, firstIndex, baseVertex);
        if (LavaFlowDataWatch.ENABLED) noteTerrainHandles();
        vkCmdDrawIndexed(encoder.commandBuffer(), indexCount, instanceCount, firstIndex, baseVertex, firstInstance);
    }
    /**
     * 把这一笔绘制正在用的顶点/索引缓冲登记给上传哨兵。
     *
     * <p>哨兵不认识地形用的缓冲，只能跟着绘制学：登记过之后，它盯的就只是这几个句柄的上传，
     * 于是菜单、GUI、贴图的上传既不会占用它的行数预算，也不会被它按 28 字节一行误读。
     */
    private void noteTerrainHandles() {
        String name = pipeline == null ? "" : pipeline.name();
        if (!name.contains("terrain") && !name.contains("water")) return;
        LavaFlowDataWatch.watchDraw(encoder.boundVertexHandle(0), encoder.boundIndexHandle());
    }
    /**
     * Records interleaved indexed draws packed as {@code VkMultiDrawIndexedInfoEXT} records.
     *
     * <p>Emulated with one {@code vkCmdDrawIndexed} per record. LavaFlow targets Vulkan 1.1 devices
     * that do not expose {@code VK_EXT_multi_draw}, so there is no single-command form to use. The
     * emulation still avoids the indirect-parameter buffer a caller would otherwise need, which is
     * host-visible memory the GPU has to read back per draw.
     */
    @Override public void multiDrawIndexed(IntBuffer indexInfo, int instanceCount, int firstInstance, int drawCount) {
        if (drawCount <= 0) return;
        flushPendingBufferBarrier();
        pushDescriptors();
        noteDrawKind("multiDrawIndexed");
        if (LavaFlowDataWatch.ENABLED) noteTerrainHandles();
        VkCommandBuffer commandBuffer = encoder.commandBuffer();
        if (indexInfo.isDirect()) {
            int requiredInts = drawCount * (VkMultiDrawIndexedInfoEXT.SIZEOF / Integer.BYTES);
            if (requiredInts > indexInfo.remaining()) {
                throw new IllegalArgumentException("drawCount " + drawCount + " requires " + requiredInts
                        + " ints but indexInfo has only " + indexInfo.remaining() + " remaining");
            }
            long record = MemoryUtil.memAddress(indexInfo);
            for (int draw = 0; draw < drawCount; draw++) {
                int arrayIndexCount = MemoryUtil.memGetInt(record + VkMultiDrawIndexedInfoEXT.INDEXCOUNT);
                if (drawsNothing(arrayIndexCount, instanceCount)) {
                    LavaFlowFrameStats.indirectSkipped(1);
                    record += VkMultiDrawIndexedInfoEXT.SIZEOF;
                    continue;
                }
                vkCmdDrawIndexed(commandBuffer,
                        arrayIndexCount,
                        instanceCount,
                        MemoryUtil.memGetInt(record + VkMultiDrawIndexedInfoEXT.FIRSTINDEX),
                        MemoryUtil.memGetInt(record + VkMultiDrawIndexedInfoEXT.VERTEXOFFSET),
                        firstInstance);
                record += VkMultiDrawIndexedInfoEXT.SIZEOF;
                LavaFlowFrameStats.indirectExpanded(1);
            }
            return;
        }
        int base = indexInfo.position();
        for (int draw = 0; draw < drawCount; draw++) {
            int record = base + draw * INDEXED_INFO_INTS;
            int firstIndex = indexInfo.get(record + INDEXED_INFO_FIRST_INDEX);
            int indexCount = indexInfo.get(record + INDEXED_INFO_INDEX_COUNT);
            int vertexOffset = indexInfo.get(record + INDEXED_INFO_VERTEX_OFFSET);
            if (drawsNothing(indexCount, instanceCount)) {
                LavaFlowFrameStats.indirectSkipped(1);
                continue;
            }
            vkCmdDrawIndexed(commandBuffer, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
            LavaFlowFrameStats.indirectExpanded(1);
        }
    }
    @Override public void multiDrawIndexed(PointerBuffer buffers, IntBuffer counts, IntBuffer baseVertices, int instanceCount) { unsupported(); }
    @Override public void drawIndexedIndirect(GpuBufferSlice buffer, int count) {
        flushPendingBufferBarrier();
        pushDescriptors();
        noteDrawKind("drawIndexedIndirect");
        if (DEBUG_DRAWS) logIndirectDraw(buffer, count);
        LavaFlowGpuBuffer parameters = (LavaFlowGpuBuffer) buffer.buffer();
        if (count <= 1 || context.multiDrawIndirect()) {
            vkCmdDrawIndexedIndirect(encoder.commandBuffer(), parameters.handle(), buffer.offset(), count,
                    VkDrawIndexedIndirectCommand.SIZEOF);
            LavaFlowFrameStats.indirectNative(count);
            return;
        }
        // Without multiDrawIndirect the batch has to be split. Issuing one indirect command per draw makes the
        // driver re-validate the parameter buffer every time, which costs far more than the draw itself. When
        // the parameters can be read from host memory, read them here and record plain indexed draws instead:
        // the same command count on a cheaper path. The read needs the host copy to be authoritative as well
        // (no device-side copy since the last CPU write), or it would expand commands the GPU has not written
        // yet — that is the bug fixed in 4b877f6, and it stays a precondition here.
        long hostBase = parameters.mappedPhysicalBase;
        boolean readable = parameters.hostAuthoritative()
                && LavaFlowGpuBuffer.canReadParameters(hostBase, parameters.hostVisible());
        if (forceIndirectSplit() || !readable) {
            if (!forceIndirectSplit()) {
                parameters.reportUnreadableOnce(buffer.offset(), count * VkDrawIndexedIndirectCommand.SIZEOF);
            }
            for (int draw = 0; draw < count; draw++) {
                vkCmdDrawIndexedIndirect(encoder.commandBuffer(), parameters.handle(),
                        buffer.offset() + (long) draw * VkDrawIndexedIndirectCommand.SIZEOF,
                        1, VkDrawIndexedIndirectCommand.SIZEOF);
            }
            LavaFlowFrameStats.indirectSplit(count);
            return;
        }
        VkCommandBuffer commandBuffer = encoder.commandBuffer();
        // Copy the range out in one sequential pass before reading fields from it. The allocation asks for
        // host-cached memory, but that is a preference: where the device offers only uncached host memory this
        // keeps the cost of reading it to one streaming copy instead of many narrow reads.
        int bytes = count * VkDrawIndexedIndirectCommand.SIZEOF;
        long base = encoder.readbackScratch(bytes);
        if (!parameters.tryReadBack(buffer.offset(), bytes, base)) {
            for (int draw = 0; draw < count; draw++) {
                vkCmdDrawIndexedIndirect(encoder.commandBuffer(), parameters.handle(),
                        buffer.offset() + (long) draw * VkDrawIndexedIndirectCommand.SIZEOF,
                        1, VkDrawIndexedIndirectCommand.SIZEOF);
            }
            LavaFlowFrameStats.indirectSplit(count);
            return;
        }
        for (int draw = 0; draw < count; draw++) {
            long command = base + (long) draw * VkDrawIndexedIndirectCommand.SIZEOF;
            int indexCount = MemoryUtil.memGetInt(command + VkDrawIndexedIndirectCommand.INDEXCOUNT);
            int instances = MemoryUtil.memGetInt(command + VkDrawIndexedIndirectCommand.INSTANCECOUNT);
            if (drawsNothing(indexCount, instances)) {
                LavaFlowFrameStats.indirectSkipped(1);
                continue;
            }
            vkCmdDrawIndexed(commandBuffer, indexCount, instances,
                    MemoryUtil.memGetInt(command + VkDrawIndexedIndirectCommand.FIRSTINDEX),
                    MemoryUtil.memGetInt(command + VkDrawIndexedIndirectCommand.VERTEXOFFSET),
                    MemoryUtil.memGetInt(command + VkDrawIndexedIndirectCommand.FIRSTINSTANCE));
            LavaFlowFrameStats.indirectExpanded(1);
        }
    }
    // drawMultipleIndexed is frontend work in 26.3 -- FrontendRenderPass walks the draws itself and
    // drives setVertexBuffer / setIndexBuffer / drawIndexed, so RenderPassBackend no longer has it.
    @Override public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        flushPendingBufferBarrier();
        pushDescriptors();
        noteDrawKind("draw");
        vkCmdDraw(encoder.commandBuffer(), vertexCount, instanceCount, firstVertex, firstInstance);
    }
    @Override public void multiDraw(IntBuffer counts, int firstInstance, int instanceCount, int firstVertex) { unsupported(); }
    @Override public void multiDraw(IntBuffer counts, IntBuffer firstVertices, int instanceCount) { unsupported(); }
    @Override public void drawIndirect(GpuBufferSlice buffer, int count) {
        flushPendingBufferBarrier();
        pushDescriptors();
        noteDrawKind("drawIndirect");
        LavaFlowGpuBuffer parameters = (LavaFlowGpuBuffer) buffer.buffer();
        if (count <= 1 || context.multiDrawIndirect()) {
            vkCmdDrawIndirect(encoder.commandBuffer(), parameters.handle(), buffer.offset(), count,
                    VkDrawIndirectCommand.SIZEOF);
            LavaFlowFrameStats.indirectNative(count);
            return;
        }
        // Same split-versus-expand choice as drawIndexedIndirect; see the comment there for why the host copy
        // has to be authoritative before it can be read.
        long hostBase = parameters.mappedPhysicalBase;
        boolean readable = parameters.hostAuthoritative()
                && LavaFlowGpuBuffer.canReadParameters(hostBase, parameters.hostVisible());
        if (forceIndirectSplit() || !readable) {
            if (!forceIndirectSplit()) {
                parameters.reportUnreadableOnce(buffer.offset(), count * VkDrawIndirectCommand.SIZEOF);
            }
            for (int draw = 0; draw < count; draw++) {
                vkCmdDrawIndirect(encoder.commandBuffer(), parameters.handle(),
                        buffer.offset() + (long) draw * VkDrawIndirectCommand.SIZEOF,
                        1, VkDrawIndirectCommand.SIZEOF);
            }
            LavaFlowFrameStats.indirectSplit(count);
            return;
        }
        int bytes = count * VkDrawIndirectCommand.SIZEOF;
        long base = encoder.readbackScratch(bytes);
        if (!parameters.tryReadBack(buffer.offset(), bytes, base)) {
            for (int draw = 0; draw < count; draw++) {
                vkCmdDrawIndirect(encoder.commandBuffer(), parameters.handle(),
                        buffer.offset() + (long) draw * VkDrawIndirectCommand.SIZEOF,
                        1, VkDrawIndirectCommand.SIZEOF);
            }
            LavaFlowFrameStats.indirectSplit(count);
            return;
        }
        for (int draw = 0; draw < count; draw++) {
            long command = base + (long) draw * VkDrawIndirectCommand.SIZEOF;
            int vertexCount = MemoryUtil.memGetInt(command + VkDrawIndirectCommand.VERTEXCOUNT);
            int instances = MemoryUtil.memGetInt(command + VkDrawIndirectCommand.INSTANCECOUNT);
            if (drawsNothing(vertexCount, instances)) {
                LavaFlowFrameStats.indirectSkipped(1);
                continue;
            }
            vkCmdDraw(encoder.commandBuffer(), vertexCount, instances,
                    MemoryUtil.memGetInt(command + VkDrawIndirectCommand.FIRSTVERTEX),
                    MemoryUtil.memGetInt(command + VkDrawIndirectCommand.FIRSTINSTANCE));
            LavaFlowFrameStats.indirectExpanded(1);
        }
    }

    /** -Dlavaflow.forceIndirectSplit=true: always one indirect command per draw, the comparison path. */
    private static boolean forceIndirectSplit() {
        return Boolean.getBoolean("lavaflow.forceIndirectSplit");
    }

    /** A command that draws nothing: the CPU expansion drops it instead of recording a no-op. */
    private static boolean drawsNothing(int count, int instances) {
        return count <= 0 || instances <= 0;
    }
    @Override public void writeTimestamp(GpuQueryPool pool, int index) { encoder.writeTimestamp(pool, index); }

    private static LavaFlowGpuTextureView view(GpuTextureView view) { return (LavaFlowGpuTextureView) view; }

    private void pushDescriptors() {
        if (pipeline == null) throw new IllegalStateException("No graphics pipeline is bound");
        ensureBegun();
        if (LavaFlowElision.DESCRIPTORS && !descriptorsDirty) return;
        List<LavaFlowRenderPipeline.Entry> entries = pipeline.entries();
        if (entries.isEmpty()) {
            descriptorsDirty = false;
            return;
        }
        List<LavaFlowGpuTexture> sampled = sampledTextures();
        for (int i = 0; i < sampled.size(); i++) {
            if (sampled.get(i).layout() != LavaFlowVk.sampledLayout()) {
                splitForSampledTransitions(sampled);
                break;
            }
        }
        LavaFlowFrameStats.descriptorPushed();
        if (context.pushDescriptors()) {
            try (MemoryStack stack = stackPush()) {
                vkCmdPushDescriptorSetKHR(encoder.commandBuffer(), VK_PIPELINE_BIND_POINT_GRAPHICS,
                        pipeline.pipelineLayout(), 0, buildWrites(stack, entries, 0, false));
            }
            descriptorsDirty = false;
            return;
        }
        // Descriptor-set path. Uniform buffer offsets are passed at bind time as dynamic offsets, so
        // the cached set's identity — and the set itself — is independent of where in a buffer each
        // draw reads. A set is allocated and written only when these bindings name a resource
        // combination that has not been seen before.
        boolean dynamic = pipeline.dynamicUniforms() && LavaFlowElision.DYNAMIC_UNIFORM_OFFSETS;
        int entryCount = entries.size();
        if (keyScratch.length != entryCount * 3) keyScratch = new long[entryCount * 3];
        if (resourceScratch.length != entryCount * 2) resourceScratch = new long[entryCount * 2];
        long[] keyValues = keyScratch;
        long[] resourceHandles = resourceScratch;
        // Every slot is written below; a sampled image fills only two of its three slots and clears the
        // third, so a value left by an earlier push cannot leak into this key. The array is reallocated
        // whenever the entry count changes, and a fresh array is zeroed, which is why no full fill is
        // needed here.
        int handleCount = 0;
        int dynamicCount = 0;
        for (int i = 0; i < entryCount; i++) {
            LavaFlowRenderPipeline.Entry entry = entries.get(i);
            int at = i * 3;
            switch (entry.type()) {
                case UNIFORM_BUFFER -> {
                    GpuBufferSlice slice = requireAt(entry, i, GpuBufferSlice.class);
                    keyValues[at] = ((LavaFlowGpuBuffer) slice.buffer()).handle();
                    keyValues[at + 1] = dynamic ? 0 : slice.offset();
                    keyValues[at + 2] = slice.length();
                    resourceHandles[handleCount++] = keyValues[at];
                    if (dynamic) dynamicCount++;
                }
                case TEXEL_BUFFER -> {
                    GpuBufferSlice slice = requireAt(entry, i, GpuBufferSlice.class);
                    keyValues[at] = ((LavaFlowGpuBuffer) slice.buffer()).handle();
                    keyValues[at + 1] = slice.offset();
                    keyValues[at + 2] = slice.length();
                    resourceHandles[handleCount++] = keyValues[at];
                }
                case SAMPLED_IMAGE -> {
                    TextureViewAndSampler binding = requireAt(entry, i, TextureViewAndSampler.class);
                    keyValues[at] = ((LavaFlowGpuTextureView) binding.view()).handle();
                    keyValues[at + 1] = ((LavaFlowGpuSampler) binding.sampler()).handle();
                    keyValues[at + 2] = 0;
                    resourceHandles[handleCount++] = keyValues[at];
                    resourceHandles[handleCount++] = keyValues[at + 1];
                }
            }
        }
        LavaFlowDescriptorCache cache = encoder.device().descriptorCache();
        LavaFlowDescriptorCache.Key key = keyProbe.reset(pipeline.descriptorSetLayout(), keyValues);
        long descriptorSet = cache.lookup(key);
        boolean reused = descriptorSet != 0;
        if (reused) LavaFlowFrameStats.descriptorSetReused();
        try (MemoryStack stack = stackPush()) {
            if (!reused) {
                descriptorSet = cache.allocateAndStore(key, pipeline.descriptorSetLayout(),
                        java.util.Arrays.copyOf(resourceHandles, handleCount));
                vkUpdateDescriptorSets(context.device(), buildWrites(stack, entries, descriptorSet, dynamic), null);
            }
            IntBuffer dynamicOffsets = null;
            if (dynamicCount > 0) {
                dynamicOffsets = stack.mallocInt(dynamicCount);
                for (int i = 0; i < entryCount; i++) {
                    LavaFlowRenderPipeline.Entry entry = entries.get(i);
                    if (entry.type() != LavaFlowRenderPipeline.EntryType.UNIFORM_BUFFER) continue;
                    dynamicOffsets.put((int) requireAt(entry, i, GpuBufferSlice.class).offset());
                }
                dynamicOffsets.flip();
            }
            vkCmdBindDescriptorSets(encoder.commandBuffer(), VK_PIPELINE_BIND_POINT_GRAPHICS,
                    pipeline.pipelineLayout(), 0, stack.longs(descriptorSet), dynamicOffsets);
        }
        descriptorsDirty = false;
    }

    /**
     * Fills descriptor writes for these bindings. With {@code descriptorSet} zero the writes are for
     * a push-descriptor recording; with {@code dynamicUniforms} the uniform writes carry offset zero
     * because the actual offset arrives at bind time.
     */
    private VkWriteDescriptorSet.Buffer buildWrites(MemoryStack stack, List<LavaFlowRenderPipeline.Entry> entries,
                                                    long descriptorSet, boolean dynamicUniforms) {
        LavaFlowDescriptorCache cache = encoder.device().descriptorCache();
        LavaFlowDescriptorCache.WriteScratch scratch = cache.writes();
        VkWriteDescriptorSet.Buffer writes = scratch.writes(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            LavaFlowRenderPipeline.Entry entry = entries.get(i);
            // Reused structures, so every union member an earlier push could have left behind is cleared:
            // only the member matching this entry's type carries meaning.
            VkWriteDescriptorSet write = scratch.write(i).sType$Default().dstBinding(i)
                    .dstArrayElement(0).descriptorCount(1).dstSet(descriptorSet)
                    .descriptorType(pipeline.descriptorType(i))
                    .pBufferInfo(null).pImageInfo(null).pTexelBufferView(null);
            switch (entry.type()) {
                case UNIFORM_BUFFER -> {
                    GpuBufferSlice slice = requireAt(entry, i, GpuBufferSlice.class);
                    write.pBufferInfo(scratch.bufferInfo(i)
                            .buffer(((LavaFlowGpuBuffer) slice.buffer()).handle())
                            .offset(dynamicUniforms ? 0 : slice.offset()).range(slice.length()));
                }
                case SAMPLED_IMAGE -> {
                    TextureViewAndSampler binding = requireAt(entry, i, TextureViewAndSampler.class);
                    LavaFlowGpuTextureView view = (LavaFlowGpuTextureView) binding.view();
                    LavaFlowGpuTexture sampledTexture = view.texture();
                    if (sampledTexture.layout() != LavaFlowVk.sampledLayout()) {
                        throw new IllegalStateException("Sampled image " + entry.name() + " is not shader-readable (layout " + sampledTexture.layout() + ")");
                    }
                    write.pImageInfo(scratch.imageInfo(i)
                            .sampler(((LavaFlowGpuSampler) binding.sampler()).handle()).imageView(view.handle())
                            .imageLayout(LavaFlowVk.sampledLayout()));
                }
                case TEXEL_BUFFER -> {
                    GpuBufferSlice slice = requireAt(entry, i, GpuBufferSlice.class);
                    long bufferView = cache.bufferView(((LavaFlowGpuBuffer) slice.buffer()).handle(),
                            LavaFlowVk.format(entry.texelFormat()), slice.offset(), slice.length());
                    write.pTexelBufferView(stack.longs(bufferView));
                }
            }
        }
        return writes;
    }

    /**
     * The value bound to one uniform slot. Fails loudly rather than asserting: a wrong type or a
     * missing value here means the frontend and this backend disagree about the pipeline's uniform
     * layout, which would otherwise show up as a wrong or unread descriptor.
     */
    private <T> T requireAt(int index, Class<T> type) {
        return requireAt(pipeline.entries().get(index), index, type);
    }

    private <T> T requireAt(LavaFlowRenderPipeline.Entry entry, int index, Class<T> type) {
        Object value = index < uniforms.size() ? uniforms.get(index) : null;
        if (value == null) throw new IllegalStateException("Missing uniform " + entry.name() + " (slot " + index + ")");
        if (!type.isInstance(value)) {
            throw new IllegalStateException("Uniform " + entry.name() + " (slot " + index + ") is a "
                    + value.getClass().getName() + ", expected " + type.getSimpleName());
        }
        if (value instanceof GpuBufferSlice slice && slice.buffer().isClosed()) {
            throw new IllegalStateException("Uniform buffer is closed: " + entry.name());
        }
        return type.cast(value);
    }

    /** Textures behind the sampled-image uniforms currently bound, in slot order. */
    private List<LavaFlowGpuTexture> sampledTextures() {
        sampledScratch.clear();
        if (pipeline == null) return sampledScratch;
        for (int index : pipeline.sampledIndices()) {
            if (index < uniforms.size() && uniforms.get(index) instanceof TextureViewAndSampler binding) {
                sampledScratch.add(((LavaFlowGpuTextureView) binding.view()).texture());
            }
        }
        return sampledScratch;
    }

    private static void unsupported() { throw new UnsupportedOperationException("LavaFlow graphics pipeline binding is not initialized"); }
}
