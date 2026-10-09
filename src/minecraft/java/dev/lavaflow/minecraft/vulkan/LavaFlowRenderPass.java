package dev.lavaflow.minecraft.vulkan;

import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.api.pipeline.IndexType;
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
import java.util.Collections;
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
    private static final int DRAW_INFO_INTS = VkMultiDrawInfoEXT.SIZEOF / Integer.BYTES;
    private static final int DRAW_INFO_FIRST_VERTEX = VkMultiDrawInfoEXT.FIRSTVERTEX / Integer.BYTES;
    private static final int DRAW_INFO_VERTEX_COUNT = VkMultiDrawInfoEXT.VERTEXCOUNT / Integer.BYTES;

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
    private final List<Object> uniforms = new ArrayList<>();
    private LavaFlowRenderPipeline pipeline;
    private boolean descriptorsDirty = true;
    private boolean begun;
    private boolean ended;

    // Fixed-function state buffered until the pass begins; recorded directly afterwards.
    private int scissorX;
    private int scissorY;
    private int scissorWidth;
    private int scissorHeight;
    private final long[] pendingVertexBuffers = new long[RenderPass.MAX_VERTEX_BUFFERS];
    private final long[] pendingVertexOffsets = new long[RenderPass.MAX_VERTEX_BUFFERS];
    // Scratch arrays for the descriptor-set path. That path runs on every descriptor change rather than
    // once per set — a set is only allocated when a binding combination has not been seen before — so the
    // garbage it used to produce was worth removing. They are sized exactly to the binding count because
    // the cache key hashes the whole array: a longer one would put its tail into the key. Safe to
    // overwrite on return — the cache copies the key it retains (see allocateAndStore).
    private long[] keyScratch = new long[0];
    private long[] resourceScratch = new long[0];
    private int pendingVertexMask;
    private long pendingIndexBuffer;
    private int pendingIndexType = -1;

    LavaFlowRenderPass(LavaFlowCommandEncoder encoder, RenderPassDescriptor descriptor) {
        this.encoder = encoder;
        this.context = encoder.device().context();
        this.descriptor = descriptor;

        List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colors = descriptor.colorAttachments();
        RenderPassDescriptor.Attachment<OptionalDouble> depth = descriptor.depthAttachment();
        GpuTextureView extentView = null;
        for (RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment : colors) {
            if (attachment != null) extentView = attachment.textureView();
        }
        if (extentView == null && depth != null) extentView = depth.textureView();
        if (extentView == null) throw new IllegalArgumentException("Render pass has no attachments");
        outputWidth = extentView.getWidth(0);
        outputHeight = extentView.getHeight(0);

        colorViews = new LavaFlowGpuTextureView[colors.size()];
        boolean clears = depth != null && depth.clearValue().isPresent();
        for (int i = 0; i < colors.size(); i++) {
            RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = colors.get(i);
            if (attachment == null) continue;
            colorViews[i] = view(attachment.textureView());
            clears |= attachment.clearValue().isPresent();
        }
        depthView = depth == null ? null : view(depth.textureView());
        hasClears = clears;

        if (context.dynamicRendering()) {
            renderPass = 0;
        } else {
            int[] colorFormats = new int[colors.size()];
            int[] colorLoadOps = new int[colors.size()];
            for (int i = 0; i < colors.size(); i++) {
                if (colorViews[i] == null) {
                    colorFormats[i] = VK_FORMAT_UNDEFINED;
                    colorLoadOps[i] = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
                } else {
                    colorFormats[i] = LavaFlowVk.format(colorViews[i].texture().getFormat());
                    colorLoadOps[i] = colors.get(i).clearValue().isPresent()
                            ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD;
                }
            }
            int depthFormat = depthView == null ? VK_FORMAT_UNDEFINED
                    : LavaFlowVk.format(depthView.texture().getFormat());
            int depthLoadOp = depth == null || depth.clearValue().isEmpty()
                    ? VK_ATTACHMENT_LOAD_OP_LOAD : VK_ATTACHMENT_LOAD_OP_CLEAR;
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
            vkCmdBindIndexBuffer(encoder.commandBuffer(), pendingIndexBuffer, 0, pendingIndexType);
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
    }

    private void beginDynamic(MemoryStack stack, boolean resume) {
        List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colors = descriptor.colorAttachments();
        RenderPassDescriptor.Attachment<OptionalDouble> depth = descriptor.depthAttachment();
        VkRenderingAttachmentInfo.Buffer colorAttachments = VkRenderingAttachmentInfo.calloc(colors.size(), stack);
        for (int i = 0; i < colors.size(); i++) {
            RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = colors.get(i);
            VkRenderingAttachmentInfo renderingAttachment = colorAttachments.get(i).sType$Default();
            if (attachment == null) {
                renderingAttachment.imageView(0).imageLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                        .loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE);
                continue;
            }
            boolean clear = !resume && attachment.clearValue().isPresent();
            renderingAttachment.imageView(colorViews[i].handle())
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(clear ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            if (clear) {
                Vector4fc value = attachment.clearValue().get();
                renderingAttachment.clearValue().color()
                        .float32(stack.floats(value.x(), value.y(), value.z(), value.w()));
            }
        }
        VkRenderingAttachmentInfo depthAttachment = null;
        if (depthView != null) {
            boolean clear = !resume && depth.clearValue().isPresent();
            depthAttachment = VkRenderingAttachmentInfo.calloc(stack).sType$Default()
                    .imageView(depthView.handle()).imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    .loadOp(clear ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            if (clear) depthAttachment.clearValue().depthStencil()
                    .depth((float) depth.clearValue().getAsDouble()).stencil(0);
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
        List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colors = descriptor.colorAttachments();
        RenderPassDescriptor.Attachment<OptionalDouble> depth = descriptor.depthAttachment();
        int attachmentCount = depthView == null ? 0 : 1;
        for (LavaFlowGpuTextureView view : colorViews) if (view != null) attachmentCount++;
        long[] views = new long[attachmentCount];
        VkClearValue.Buffer clearValues = VkClearValue.calloc(attachmentCount, stack);
        int attachmentIndex = 0;
        for (int i = 0; i < colorViews.length; i++) {
            LavaFlowGpuTextureView view = colorViews[i];
            if (view == null) continue;
            views[attachmentIndex] = view.handle();
            Optional<Vector4fc> clear = resume ? Optional.empty() : colors.get(i).clearValue();
            if (clear.isPresent()) {
                Vector4fc value = clear.get();
                clearValues.get(attachmentIndex).color()
                        .float32(stack.floats(value.x(), value.y(), value.z(), value.w()));
            }
            attachmentIndex++;
        }
        if (depthView != null) {
            views[attachmentIndex] = depthView.handle();
            if (!resume && depth.clearValue().isPresent()) clearValues.get(attachmentIndex).depthStencil()
                    .depth((float) depth.clearValue().getAsDouble()).stencil(0);
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
        List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colors = descriptor.colorAttachments();
        int[] colorFormats = new int[colorViews.length];
        int[] colorLoadOps = new int[colorViews.length];
        for (int i = 0; i < colorViews.length; i++) {
            if (colorViews[i] == null) {
                colorFormats[i] = VK_FORMAT_UNDEFINED;
                colorLoadOps[i] = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
            } else {
                colorFormats[i] = LavaFlowVk.format(colorViews[i].texture().getFormat());
                colorLoadOps[i] = VK_ATTACHMENT_LOAD_OP_LOAD;
            }
        }
        int depthFormat = depthView == null ? VK_FORMAT_UNDEFINED
                : LavaFlowVk.format(depthView.texture().getFormat());
        return context.legacyRenderPass(colorFormats, colorLoadOps, depthFormat, VK_ATTACHMENT_LOAD_OP_LOAD);
    }

    /**
     * Ends the running pass, transitions every bound texture to the sampled layout, and resumes the
     * pass with load ops so no contents are lost.
     *
     * <p>Needed when a texture is bound for sampling after the pass has begun while still in an
     * attachment layout: barriers cannot be recorded inside a render pass. Dynamic state, the bound
     * pipeline, and descriptor bindings are command-buffer state and survive the boundary untouched.
     */
    private void splitForSampledTransitions() {
        LavaFlowFrameStats.passSplit();
        if (context.dynamicRendering()) vkCmdEndRenderingKHR(encoder.commandBuffer());
        else vkCmdEndRenderPass(encoder.commandBuffer());
        for (LavaFlowGpuTexture sampled : sampledTextures()) {
            int layout = LavaFlowVk.sampledLayout();
            if (sampled.layout() != layout) {
                encoder.transition(sampled, layout);
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
        vkCmdBindPipeline(encoder.commandBuffer(), VK_PIPELINE_BIND_POINT_GRAPHICS,
                pipeline.pipelineFor(depthVkFormat, renderPass));
    }

    boolean hasDepth() { return descriptor.depthAttachment() != null; }


    @Override public void pushDebugGroup(Supplier<String> label) {}
    @Override public void popDebugGroup() {}
    @Override public void setPipeline(BackendRenderPipeline pipeline) {
        if (!(pipeline instanceof LavaFlowRenderPipeline lavaPipeline)) {
            throw new IllegalArgumentException("Pipeline must be a LavaFlowRenderPipeline");
        }
        if (this.pipeline == lavaPipeline) {
            // Elided here is only the vkCmdBindPipeline recording, not the accounting: the counters
            // describe what the frontend does, and hiding repeat calls would paint rebind_ratio as 0.
            LavaFlowFrameStats.pipelineBind(true);
            return;
        }
        LavaFlowFrameStats.pipelineBind(false);
        this.pipeline = lavaPipeline;
        // One slot per compiled uniform; the frontend replays the uniforms it already holds right after
        // this call, and 26.3 resolves names to slots there rather than here.
        uniforms.clear();
        uniforms.addAll(Collections.nCopies(lavaPipeline.entries().size(), null));
        if (begun) recordPipelineBind();
        descriptorsDirty = true;
    }
    @Override public void setUniform(int index, Object value) {
        if (index < 0 || index >= uniforms.size()) {
            throw new IllegalStateException("Uniform index " + index + " is outside the bound pipeline's "
                    + uniforms.size() + " uniforms");
        }
        if (Objects.equals(uniforms.get(index), value)) {
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
        // A device run measured 92.8 recorded vertex binds per frame against 92.8 draws: the frontend asks for
        // the same slot over and over, and each request that matches what is already bound would change
        // nothing. The same bargain as setPipeline and setUniform — the request is counted either way, only the
        // command is dropped — but the question has to be asked against the encoder's record rather than the
        // pass's, because a binding outlives the pass it was recorded in.
        boolean repeated = encoder.vertexBound(slot, handle, offset);
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
        int vkType = type == IndexType.SHORT ? VK_INDEX_TYPE_UINT16 : VK_INDEX_TYPE_UINT32;
        if (begun) {
            vkCmdBindIndexBuffer(encoder.commandBuffer(), handle, 0, vkType);
        } else {
            pendingIndexBuffer = handle;
            pendingIndexType = vkType;
        }
    }
    /**
     * Whether a draw command asks for nothing at all.
     *
     * <p>{@code vkCmdDraw*} with no vertices or no instances is a legal no-op, and every CPU-readable batch
     * (an indirect parameter buffer that is currently mapped, or a plain multi-draw array) has its parameters
     * read here anyway — so dropping these costs nothing and shortens the command stream. Sodium culls by
     * writing zeroes into its draw lists, and so does Minecraft's own terrain batching.
     */
    static boolean drawsNothing(int count, int instanceCount) {
        return count <= 0 || instanceCount <= 0;
    }

    @Override public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance) {
        pushDescriptors();
        vkCmdDrawIndexed(encoder.commandBuffer(), indexCount, instanceCount, firstIndex, baseVertex, firstInstance);
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
        pushDescriptors();
        VkCommandBuffer commandBuffer = encoder.commandBuffer();
        if (indexInfo.isDirect()) {
            int requiredInts = drawCount * (VkMultiDrawIndexedInfoEXT.SIZEOF / Integer.BYTES);
            if (requiredInts > indexInfo.remaining()) {
                throw new IllegalArgumentException("drawCount " + drawCount + " requires " + requiredInts
                        + " ints but indexInfo has only " + indexInfo.remaining() + " remaining");
            }
            long record = MemoryUtil.memAddress(indexInfo);
            for (int draw = 0; draw < drawCount; draw++) {
                int indexCount = MemoryUtil.memGetInt(record + VkMultiDrawIndexedInfoEXT.INDEXCOUNT);
                if (drawsNothing(indexCount, instanceCount)) {
                    LavaFlowFrameStats.indirectSkipped(1);
                    record += VkMultiDrawIndexedInfoEXT.SIZEOF;
                    continue;
                }
                vkCmdDrawIndexed(commandBuffer, indexCount, instanceCount,
                        MemoryUtil.memGetInt(record + VkMultiDrawIndexedInfoEXT.FIRSTINDEX),
                        MemoryUtil.memGetInt(record + VkMultiDrawIndexedInfoEXT.VERTEXOFFSET),
                        firstInstance);
                LavaFlowFrameStats.indirectExpanded(1);
                record += VkMultiDrawIndexedInfoEXT.SIZEOF;
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
    // Reached only if a caller batches with separate arrays, which DeviceFeatures.multiDrawDirectSeparate
    // advertises as unavailable: without that promise Minecraft would need one buffer binding per draw here.
    @Override public void multiDrawIndexed(PointerBuffer buffers, IntBuffer counts, IntBuffer baseVertices, int instanceCount) {
        unsupported("multiDrawIndexed with separate vertex buffers");
    }
    @Override public void drawIndexedIndirect(GpuBufferSlice buffer, int count) {
        pushDescriptors();
        LavaFlowGpuBuffer parameters = (LavaFlowGpuBuffer) buffer.buffer();
        if (count <= 1 || context.multiDrawIndirect()) {
            vkCmdDrawIndexedIndirect(encoder.commandBuffer(), parameters.handle(), buffer.offset(), count,
                    VkDrawIndexedIndirectCommand.SIZEOF);
            LavaFlowFrameStats.indirectNative(count);
            return;
        }
        // Without multiDrawIndirect the batch has to be split. Issuing one indirect command per draw
        // makes the driver re-validate the parameter buffer every time, which costs far more than the
        // draw itself. When the parameters are in host memory that is currently mapped, read them here
        // and record plain indexed draws instead, which is the same command count on a cheaper path.
        long hostBase = parameters.mappedPhysicalBase;
        if (hostBase == 0) {
            for (int draw = 0; draw < count; draw++) {
                vkCmdDrawIndexedIndirect(encoder.commandBuffer(), parameters.handle(),
                        buffer.offset() + (long) draw * VkDrawIndexedIndirectCommand.SIZEOF,
                        1, VkDrawIndexedIndirectCommand.SIZEOF);
            }
            LavaFlowFrameStats.indirectSplit(count);
            return;
        }
        VkCommandBuffer commandBuffer = encoder.commandBuffer();
        // Copy the range out in one sequential pass before reading fields from it. The allocation asks
        // for host-cached memory, but that is a preference: where the device offers only uncached host
        // memory this keeps the cost of reading it to one streaming copy instead of many narrow reads.
        int bytes = count * VkDrawIndexedIndirectCommand.SIZEOF;
        long base = encoder.readbackScratch(bytes);
        MemoryUtil.memCopy(hostBase + buffer.offset(), base, bytes);
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
        pushDescriptors();
        vkCmdDraw(encoder.commandBuffer(), vertexCount, instanceCount, firstVertex, firstInstance);
    }
    /**
     * Records interleaved draws packed as {@code VkMultiDrawInfoEXT} records, one {@code vkCmdDraw} per record.
     *
     * <p>This used to be an {@code unsupported()} stub while {@code DeviceFeatures.multiDrawDirectInterleaved}
     * was advertised as available — an inconsistency that only held because nothing had called it yet: the
     * frontend rejects a non-zero {@code firstInstance} itself unless {@code nonZeroFirstInstance} is reported,
     * so every caller it allowed was passing zero. The stub would have thrown the moment that changed.
     *
     * <p>The parameter order is the one Minecraft's frontend and its own Vulkan backend agree on —
     * {@code (records, instanceCount, firstInstance, drawCount)} — and not the order the names in the old stub
     * suggested: the frontend tests its third int for {@code firstInstance}, and the stock backend passes the
     * second as {@code instanceCount} and the fourth as the draw count. Minecraft's own backend records this
     * with {@code vkCmdDrawMultiEXT}, which needs {@code VK_EXT_multi_draw}; Vulkan 1.1 devices do not have it,
     * hence the loop.
     */
    @Override public void multiDraw(IntBuffer counts, int instanceCount, int firstInstance, int drawCount) {
        if (drawCount <= 0) return;
        pushDescriptors();
        VkCommandBuffer commandBuffer = encoder.commandBuffer();
        if (counts.isDirect()) {
            int requiredInts = drawCount * DRAW_INFO_INTS;
            if (requiredInts > counts.remaining()) {
                throw new IllegalArgumentException("drawCount " + drawCount + " requires " + requiredInts
                        + " ints but counts has only " + counts.remaining() + " remaining");
            }
            long record = MemoryUtil.memAddress(counts);
            for (int draw = 0; draw < drawCount; draw++) {
                int vertexCount = MemoryUtil.memGetInt(record + VkMultiDrawInfoEXT.VERTEXCOUNT);
                int firstVertex = MemoryUtil.memGetInt(record + VkMultiDrawInfoEXT.FIRSTVERTEX);
                record += VkMultiDrawInfoEXT.SIZEOF;
                if (drawsNothing(vertexCount, instanceCount)) {
                    LavaFlowFrameStats.indirectSkipped(1);
                    continue;
                }
                vkCmdDraw(commandBuffer, vertexCount, instanceCount, firstVertex, firstInstance);
                LavaFlowFrameStats.indirectExpanded(1);
            }
            return;
        }
        int base = counts.position();
        for (int draw = 0; draw < drawCount; draw++) {
            int record = base + draw * DRAW_INFO_INTS;
            int vertexCount = counts.get(record + DRAW_INFO_VERTEX_COUNT);
            int firstVertex = counts.get(record + DRAW_INFO_FIRST_VERTEX);
            if (drawsNothing(vertexCount, instanceCount)) {
                LavaFlowFrameStats.indirectSkipped(1);
                continue;
            }
            vkCmdDraw(commandBuffer, vertexCount, instanceCount, firstVertex, firstInstance);
            LavaFlowFrameStats.indirectExpanded(1);
        }
    }

    // The separate-first-vertices variant belongs to DeviceFeatures.multiDrawDirectSeparate, which is
    // advertised as unavailable, so a caller that reaches this is asking for something it was told it could
    // not have; saying which call it was beats the generic message.
    @Override public void multiDraw(IntBuffer counts, IntBuffer firstVertices, int instanceCount) {
        unsupported("multiDraw with separate first vertices");
    }

    @Override public void drawIndirect(GpuBufferSlice buffer, int count) {
        pushDescriptors();
        LavaFlowGpuBuffer parameters = (LavaFlowGpuBuffer) buffer.buffer();
        if (count <= 1 || context.multiDrawIndirect()) {
            vkCmdDrawIndirect(encoder.commandBuffer(), parameters.handle(), buffer.offset(), count,
                    VkDrawIndirectCommand.SIZEOF);
            LavaFlowFrameStats.indirectNative(count);
            return;
        }
        // The same three-way choice as the indexed form, for the same reason. This variant used to only split,
        // which made it the one path that paid the driver's per-call parameter re-validation even when the
        // parameters were sitting in host memory.
        long hostBase = parameters.mappedPhysicalBase;
        if (hostBase == 0) {
            for (int draw = 0; draw < count; draw++) {
                vkCmdDrawIndirect(encoder.commandBuffer(), parameters.handle(),
                        buffer.offset() + (long) draw * VkDrawIndirectCommand.SIZEOF,
                        1, VkDrawIndirectCommand.SIZEOF);
            }
            LavaFlowFrameStats.indirectSplit(count);
            return;
        }
        VkCommandBuffer commandBuffer = encoder.commandBuffer();
        int bytes = count * VkDrawIndirectCommand.SIZEOF;
        long base = encoder.readbackScratch(bytes);
        MemoryUtil.memCopy(hostBase + buffer.offset(), base, bytes);
        for (int draw = 0; draw < count; draw++) {
            long command = base + (long) draw * VkDrawIndirectCommand.SIZEOF;
            int vertexCount = MemoryUtil.memGetInt(command + VkDrawIndirectCommand.VERTEXCOUNT);
            int instances = MemoryUtil.memGetInt(command + VkDrawIndirectCommand.INSTANCECOUNT);
            if (drawsNothing(vertexCount, instances)) {
                LavaFlowFrameStats.indirectSkipped(1);
                continue;
            }
            vkCmdDraw(commandBuffer, vertexCount, instances,
                    MemoryUtil.memGetInt(command + VkDrawIndirectCommand.FIRSTVERTEX),
                    MemoryUtil.memGetInt(command + VkDrawIndirectCommand.FIRSTINSTANCE));
            LavaFlowFrameStats.indirectExpanded(1);
        }
    }
    @Override public void writeTimestamp(GpuQueryPool pool, int index) { encoder.writeTimestamp(pool, index); }

    private static LavaFlowGpuTextureView view(GpuTextureView view) { return (LavaFlowGpuTextureView) view; }

    private void pushDescriptors() {
        if (pipeline == null) throw new IllegalStateException("No graphics pipeline is bound");
        ensureBegun();
        if (!descriptorsDirty) return;
        List<LavaFlowRenderPipeline.Entry> entries = pipeline.entries();
        if (entries.isEmpty()) {
            descriptorsDirty = false;
            return;
        }
        for (LavaFlowGpuTexture sampled : sampledTextures()) {
            if (sampled.layout() != LavaFlowVk.sampledLayout()) {
                splitForSampledTransitions();
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
        boolean dynamic = pipeline.dynamicUniforms();
        int entryCount = entries.size();
        if (keyScratch.length != entryCount * 3) keyScratch = new long[entryCount * 3];
        if (resourceScratch.length != entryCount * 2) resourceScratch = new long[entryCount * 2];
        long[] keyValues = keyScratch;
        long[] resourceHandles = resourceScratch;
        // Every slot must be written on every push: a sampled image fills only two of its three slots, so
        // without this a value left by an earlier push would become part of this key, and two identical
        // binding sets would stop producing identical keys.
        java.util.Arrays.fill(keyValues, 0L);
        int handleCount = 0;
        int dynamicCount = 0;
        for (int i = 0; i < entryCount; i++) {
            LavaFlowRenderPipeline.Entry entry = entries.get(i);
            int at = i * 3;
            switch (entry.type()) {
                case UNIFORM_BUFFER -> {
                    GpuBufferSlice slice = requireAt(i, GpuBufferSlice.class);
                    keyValues[at] = ((LavaFlowGpuBuffer) slice.buffer()).handle();
                    keyValues[at + 1] = dynamic ? 0 : slice.offset();
                    keyValues[at + 2] = slice.length();
                    resourceHandles[handleCount++] = keyValues[at];
                    if (dynamic) dynamicCount++;
                }
                case TEXEL_BUFFER -> {
                    GpuBufferSlice slice = requireAt(i, GpuBufferSlice.class);
                    keyValues[at] = ((LavaFlowGpuBuffer) slice.buffer()).handle();
                    keyValues[at + 1] = slice.offset();
                    keyValues[at + 2] = slice.length();
                    resourceHandles[handleCount++] = keyValues[at];
                }
                case SAMPLED_IMAGE -> {
                    TextureViewAndSampler binding = requireAt(i, TextureViewAndSampler.class);
                    keyValues[at] = ((LavaFlowGpuTextureView) binding.view()).handle();
                    keyValues[at + 1] = ((LavaFlowGpuSampler) binding.sampler()).handle();
                    resourceHandles[handleCount++] = keyValues[at];
                    resourceHandles[handleCount++] = keyValues[at + 1];
                }
            }
        }
        LavaFlowDescriptorCache cache = encoder.device().descriptorCache();
        LavaFlowDescriptorCache.Key key = new LavaFlowDescriptorCache.Key(pipeline.descriptorSetLayout(), keyValues);
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
                    if (entries.get(i).type() != LavaFlowRenderPipeline.EntryType.UNIFORM_BUFFER) continue;
                    dynamicOffsets.put((int) requireAt(i, GpuBufferSlice.class).offset());
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
                    .descriptorType(pipeline.vkDescriptorType(entry.type()))
                    .pBufferInfo(null).pImageInfo(null).pTexelBufferView(null);
            switch (entry.type()) {
                case UNIFORM_BUFFER -> {
                    GpuBufferSlice slice = requireAt(i, GpuBufferSlice.class);
                    write.pBufferInfo(scratch.bufferInfo(i)
                            .buffer(((LavaFlowGpuBuffer) slice.buffer()).handle())
                            .offset(dynamicUniforms ? 0 : slice.offset()).range(slice.length()));
                }
                case SAMPLED_IMAGE -> {
                    TextureViewAndSampler binding = requireAt(i, TextureViewAndSampler.class);
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
                    GpuBufferSlice slice = requireAt(i, GpuBufferSlice.class);
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
        LavaFlowRenderPipeline.Entry entry = pipeline.entries().get(index);
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
        if (pipeline == null) return List.of();
        List<LavaFlowRenderPipeline.Entry> entries = pipeline.entries();
        List<LavaFlowGpuTexture> result = new ArrayList<>();
        for (int i = 0; i < entries.size() && i < uniforms.size(); i++) {
            if (entries.get(i).type() != LavaFlowRenderPipeline.EntryType.SAMPLED_IMAGE) continue;
            if (uniforms.get(i) instanceof TextureViewAndSampler binding) {
                result.add(((LavaFlowGpuTextureView) binding.view()).texture());
            }
        }
        return result;
    }

    private static void unsupported(String what) {
        throw new UnsupportedOperationException("LavaFlow's render pass does not implement " + what
                + "; the advertised DeviceFeatures must not make Minecraft call it");
    }
}
