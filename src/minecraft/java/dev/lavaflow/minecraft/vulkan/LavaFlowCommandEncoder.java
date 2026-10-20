package dev.lavaflow.minecraft.vulkan;

import com.mojang.renderpearl.api.buffers.*;
import com.mojang.renderpearl.api.commands.*;
import com.mojang.renderpearl.backend.api.*;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import org.joml.Vector4fc;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import dev.lavaflow.vulkan.VulkanException;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdEndRenderingKHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_ERROR_OUT_OF_POOL_MEMORY;

final class LavaFlowCommandEncoder implements CommandEncoderBackend {
    private static final int SUBMIT_SLOTS = 3;
    private static final System.Logger LOGGER = System.getLogger(LavaFlowCommandEncoder.class.getName());

    private final LavaFlowVulkanContext context;
    private final LavaFlowDevice device;
    private final SubmitSlot[] slots = new SubmitSlot[SUBMIT_SLOTS];
    private int slotIndex;
    private SubmitSlot slot;
    private LavaFlowTransientMemory transientMemory;
    private VkCommandBuffer commandBuffer;
    private boolean renderPassOpen;
    private long waitSemaphore;
    private long signalSemaphore;
    private LavaFlowRenderPass activeRenderPass;
    private long recordingSerial = 1;
    private long completedSerial;
    private boolean destroyed;
    private long readbackScratch;
    private int readbackScratchBytes;
    // Two timestamp queries per submit slot: the start and the end of that slot's recording. They are read
    // when the slot's fence signals — two frames later, since slots rotate — so the reported GPU time is a
    // frame that has actually finished rather than the one being recorded. Zero when the device cannot time.
    private long timestampPool;
    // Per-pass timing: one timestamp per pass, at its end, written outside the render pass so the
    // measurement cannot perturb the tile load/store it is meant to see. Off unless asked for with
    // -Dlavaflow.passGpuTiming=true, and gated by the frame stats. A BOTTOM_OF_PIPE timestamp is a
    // completion point: one per pass broke the overlap between adjacent passes, and gpu_ms rose 2-4% on the
    // device while it was on (0.1.1 -> 0.1.3). A diagnostic that makes the number it reports a few percent
    // worse has to be opt-in; the breakdown is worth that only while it is being read.
    private static final int MAX_TIMED_PASSES = 64;
    private boolean passTimingEnabled;
    private long passTimestampPool;

    private static final class SubmitSlot {
        final VkCommandBuffer commandBuffer;
        final long fence;
        final LavaFlowTransientMemory transientMemory;
        LavaFlowDevice.SubmitBatch batch;
        long serial;
        boolean inFlight;
        // Passes timed in this slot's recording, in recording order; the labels are kept until the slot's
        // fence signals, because only then can the queries be read back.
        int timedPasses;
        final String[] passLabels = new String[MAX_TIMED_PASSES];
        // This frame's frame-start timestamp, kept so the pass readback can use it as the first pass's base.
        long frameStartTicks = -1;

        SubmitSlot(VkCommandBuffer commandBuffer, long fence, LavaFlowTransientMemory transientMemory) {
            this.commandBuffer = commandBuffer;
            this.fence = fence;
            this.transientMemory = transientMemory;
        }
    }

    LavaFlowCommandEncoder(LavaFlowDevice device) {
        this.device = device;
        this.context = device.context();
        createSlots();
        if (context.timestampsSupported()) {
            try (MemoryStack stack = stackPush()) {
                VkQueryPoolCreateInfo info = VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                        .queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(SUBMIT_SLOTS * 2);
                LongBuffer out = stack.mallocLong(1);
                int result = vkCreateQueryPool(context.device(), info, null, out);
                if (result == VK_SUCCESS) timestampPool = out.get(0);
                else LOGGER.log(System.Logger.Level.WARNING, "vkCreateQueryPool(timestamps) failed with "
                        + "VkResult " + result + ", so the report will carry no gpu_ms");
            }
        } else {
            // Said out loud rather than left as a silently missing field: an absent gpu_ms has to read as
            // "this device cannot be timed", not as "the GPU finished instantly".
            LOGGER.log(System.Logger.Level.INFO,
                    "The graphics queue reports no timestamp bits, so the report will carry no gpu_ms");
        }
        passTimingEnabled = context.timestampsSupported() && LavaFlowFrameStats.enabled()
                && "true".equalsIgnoreCase(System.getProperty("lavaflow.passGpuTiming", "false"));
        if (passTimingEnabled) {
            LOGGER.log(System.Logger.Level.INFO, "Per-pass GPU timing is on: every pass now carries a "
                    + "completion timestamp, which costs a few percent of GPU time on a tiler");
        }
        if (passTimingEnabled) {
            try (MemoryStack stack = stackPush()) {
                VkQueryPoolCreateInfo info = VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                        .queryType(VK_QUERY_TYPE_TIMESTAMP)
                        .queryCount(SUBMIT_SLOTS * MAX_TIMED_PASSES);
                LongBuffer out = stack.mallocLong(1);
                int result = vkCreateQueryPool(context.device(), info, null, out);
                if (result == VK_SUCCESS) passTimestampPool = out.get(0);
                else {
                    passTimingEnabled = false;
                    LOGGER.log(System.Logger.Level.WARNING, "vkCreateQueryPool(pass timestamps) failed with "
                            + "VkResult " + result + ", so the report will carry no per-pass GPU time");
                }
            }
        }
        prepareSlot(0);
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) throw new IllegalStateException(VulkanException.describe(operation, result));
    }

    private void createSlots() {
        try (MemoryStack stack = stackPush()) {
            VkCommandBufferAllocateInfo allocation = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(context.commandPool()).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(SUBMIT_SLOTS);
            PointerBuffer out = stack.mallocPointer(SUBMIT_SLOTS);
            check(vkAllocateCommandBuffers(context.device(), allocation, out), "vkAllocateCommandBuffers");
            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack).sType$Default();
            LongBuffer fenceOut = stack.mallocLong(1);
            for (int i = 0; i < SUBMIT_SLOTS; i++) {
                check(vkCreateFence(context.device(), fenceInfo, null, fenceOut), "vkCreateFence(submit)");
                slots[i] = new SubmitSlot(new VkCommandBuffer(out.get(i), context.device()),
                        fenceOut.get(0), new LavaFlowTransientMemory(device, this));
            }
        }
    }

    private void prepareSlot(int index) {
        if (destroyed) throw new IllegalStateException("LavaFlow command encoder is destroyed");
        slotIndex = index;
        slot = slots[index];
        if (slot.inFlight) {
            check(vkWaitForFences(context.device(), slot.fence, true, 5_000_000_000L),
                    "vkWaitForFences(submit slot)");
            check(vkResetFences(context.device(), slot.fence), "vkResetFences(submit slot)");
            completedSerial = Math.max(completedSerial, slot.serial);
            slot.inFlight = false;
            device.completeSubmit(slot.batch);
            slot.batch = null;
            slot.frameStartTicks = -1;
            readGpuTime(index);
            readPassGpuTimes(index);
        }
        slot.transientMemory.recycle();
        check(vkResetCommandBuffer(slot.commandBuffer, 0), "vkResetCommandBuffer");
        try (MemoryStack stack = stackPush()) {
            VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(vkBeginCommandBuffer(slot.commandBuffer, begin), "vkBeginCommandBuffer");
        }
        commandBuffer = slot.commandBuffer;
        transientMemory = slot.transientMemory;
        for (int i = 0; i < vertexSlotBound.length; i++) vertexSlotBound[i] = false;
        if (timestampPool != 0) {
            vkCmdResetQueryPool(commandBuffer, timestampPool, index * 2, 2);
            vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, timestampPool, index * 2);
        }
        if (passTimestampPool != 0) {
            vkCmdResetQueryPool(commandBuffer, passTimestampPool, index * MAX_TIMED_PASSES,
                    MAX_TIMED_PASSES);
            slot.timedPasses = 0;
        }
    }

    /**
     * Reads the timestamps written around one slot's recording, once that slot's fence has signalled. A frame
     * whose queries are not ready contributes no sample rather than a wrong one, and a device that cannot time
     * never gets here at all.
     */
    /**
     * The pass-timing counterpart of {@link #readGpuTime}: reads back this slot's pairs of pass timestamps
     * once its fence has signalled and hands each pair to the frame stats under the pass's label.
     */
    private void readPassGpuTimes(int index) {
        if (passTimestampPool == 0) return;
        SubmitSlot current = slots[index];
        int passes = current.timedPasses;
        if (passes == 0) {
            // Counted anyway: the per-frame total is subtracted from gpu_ms, and a frame missing from its
            // denominator would make the share outside passes drift towards zero.
            LavaFlowFrameStats.passGpuFrameCompleted(0);
            return;
        }
        if (current.frameStartTicks < 0) return;
        try (MemoryStack stack = stackPush()) {
            LongBuffer values = stack.mallocLong(passes);
            // The fence has signalled, so WAIT returns immediately; it is passed anyway because "the fence
            // said it was done" and "the query results are readable" are two different promises, and a
            // result read half-written would show up as a nonsense pass time rather than as an error.
            int result = vkGetQueryPoolResults(context.device(), passTimestampPool,
                    index * MAX_TIMED_PASSES, passes, values, Long.BYTES,
                    VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT);
            if (result != VK_SUCCESS) return;
            double period = context.timestampPeriod();
            long previous = current.frameStartTicks;
            double frameMillis = 0;
            for (int p = 0; p < passes; p++) {
                long end = values.get(p);
                if (end <= previous) continue;
                double millis = (end - previous) * period / 1_000_000.0;
                previous = end;
                frameMillis += millis;
                LavaFlowFrameStats.passGpuCompleted(current.passLabels[p], millis);
            }
            LavaFlowFrameStats.passGpuFrameCompleted(frameMillis);
        }
    }

    /**
     * Records the start of a timed pass and returns its slot-local index, or -1 when pass timing is off (or
     * this recording already holds the maximum). The matching {@link #endTimedPass} closes the pair.
     */
    int beginTimedPass(String label) {
        if (passTimestampPool == 0 || slot.timedPasses >= MAX_TIMED_PASSES) return -1;
        int pass = slot.timedPasses;
        slot.passLabels[pass] = label;
        // No timestamp here on purpose. A start timestamp cheap enough to be free is TOP_OF_PIPE, which is
        // taken when the GPU front end processes the command and can therefore precede the end of the previous
        // pass — the intervals then overlap and their sum ran 8-9% above gpu_ms on the device. One timestamp
        // per pass, at its end, and durations from successive ends, gives intervals that tile the frame with
        // half the queries and no skew.
        return pass;
    }

    /** Records the end of the pass started by {@link #beginTimedPass}. */
    void endTimedPass(int pass) {
        if (pass < 0) return;
        vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, passTimestampPool,
                slotIndex * MAX_TIMED_PASSES + pass);
        slot.timedPasses++;
    }

    private void readGpuTime(int index) {
        if (timestampPool == 0) return;
        try (MemoryStack stack = stackPush()) {
            LongBuffer values = stack.mallocLong(2);
            // The stride is the byte step *between* results, not the size of the two together: passing the
            // total writes the second result past the end of a two-long buffer and leaves the value this reads
            // uninitialised.
            int result = vkGetQueryPoolResults(context.device(), timestampPool, index * 2, 2, values,
                    Long.BYTES, VK_QUERY_RESULT_64_BIT);
            if (result != VK_SUCCESS) return;
            // The per-pass durations are deltas from this frame's start; keep the tick so the pass readback
            // can use it without reading the frame pool a second time.
            slot.frameStartTicks = values.get(0);
            long ticks = values.get(1) - values.get(0);
            if (ticks <= 0) return;
            LavaFlowFrameStats.gpuFrameCompleted(ticks * (double) context.timestampPeriod() / 1_000_000.0);
        }
    }

    // Vertex bindings are command buffer state, not render pass state: one recorded by a pass stays in effect
    // for the next pass recorded into the same command buffer, and nothing here ever unbinds one. So the record
    // of what is currently bound belongs to the encoder, and starts empty with every new command buffer.
    private final long[] boundVertexHandles = new long[CompiledRenderPipeline.CreateInfo.MAX_VERTEX_BUFFERS];
    private final long[] boundVertexOffsets = new long[CompiledRenderPipeline.CreateInfo.MAX_VERTEX_BUFFERS];
    private final boolean[] vertexSlotBound = new boolean[CompiledRenderPipeline.CreateInfo.MAX_VERTEX_BUFFERS];

    /** Whether recording this binding would change what the command buffer already has in that slot. */
    boolean vertexBound(int slot, long handle, long offset) {
        return vertexSlotBound[slot] && boundVertexHandles[slot] == handle
                && boundVertexOffsets[slot] == offset;
    }

    void recordVertexBound(int slot, long handle, long offset) {
        vertexSlotBound[slot] = true;
        boundVertexHandles[slot] = handle;
        boundVertexOffsets[slot] = offset;
    }

    VkCommandBuffer commandBuffer() { return commandBuffer; }
    LavaFlowDevice device() { return device; }
    void waitFor(long semaphore) {
        if (waitSemaphore != NULL) throw new IllegalStateException("Only one surface acquire is supported per submit");
        waitSemaphore = semaphore;
    }

    void signal(long semaphore) {
        if (signalSemaphore != NULL) throw new IllegalStateException("Only one surface present is supported per submit");
        signalSemaphore = semaphore;
    }

    @Override public void submit() {
        if (destroyed) throw new IllegalStateException("LavaFlow command encoder is destroyed");
        if (renderPassOpen) submitRenderPass();
        try (MemoryStack stack = stackPush()) {
            memoryBarrier(stack);
        }
        if (timestampPool != 0) {
            vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, timestampPool,
                    slotIndex * 2 + 1);
        }
        check(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer");
        transientMemory.flushMappedRanges();
        try (MemoryStack stack = stackPush()) {
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(commandBuffer));
            if (waitSemaphore != NULL) {
                submit.waitSemaphoreCount(1).pWaitSemaphores(stack.longs(waitSemaphore));
                submit.pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_TRANSFER_BIT));
            }
            if (signalSemaphore != NULL) submit.pSignalSemaphores(stack.longs(signalSemaphore));
            check(vkQueueSubmit(context.graphicsQueue(), submit, slot.fence), "vkQueueSubmit");
        }
        LavaFlowFrameStats.workSubmitted();
        slot.batch = device.detachSubmitBatch(transientMemory.retire());
        slot.serial = recordingSerial++;
        slot.inFlight = true;
        waitSemaphore = signalSemaphore = NULL;
        prepareSlot((slotIndex + 1) % SUBMIT_SLOTS);
    }

    /**
     * Returns a scratch allocation of at least {@code bytes}, reused across calls.
     *
     * <p>Host-visible device memory is typically write-combined, where scattered narrow reads are far
     * more expensive than one sequential bulk copy. Callers that need to inspect such memory copy the
     * whole range here first and read from the result, which is ordinary cached memory. Grows
     * monotonically so the render loop performs no allocation once warm.
     */
    long readbackScratch(int bytes) {
        if (bytes > readbackScratchBytes) {
            int capacity = Math.max(bytes, Math.max(readbackScratchBytes * 2, 4096));
            long grown = MemoryUtil.nmemAlignedAlloc(64, capacity);
            if (grown == NULL) throw new IllegalStateException("Out of memory growing readback scratch");
            if (readbackScratch != NULL) MemoryUtil.nmemAlignedFree(readbackScratch);
            readbackScratch = grown;
            readbackScratchBytes = capacity;
        }
        return readbackScratch;
    }

    void destroy() {
        if (destroyed) return;
        if (renderPassOpen) submitRenderPass();
        destroyed = true;
        if (readbackScratch != NULL) {
            MemoryUtil.nmemAlignedFree(readbackScratch);
            readbackScratch = NULL;
            readbackScratchBytes = 0;
        }
        for (SubmitSlot submitSlot : slots) {
            if (submitSlot == null) continue;
            if (submitSlot.inFlight) {
                check(vkWaitForFences(context.device(), submitSlot.fence, true, 5_000_000_000L),
                        "vkWaitForFences(destroy)");
                completedSerial = Math.max(completedSerial, submitSlot.serial);
                submitSlot.inFlight = false;
                device.completeSubmit(submitSlot.batch);
                submitSlot.batch = null;
            }
            submitSlot.transientMemory.destroy();
            vkDestroyFence(context.device(), submitSlot.fence, null);
        }
        for (SubmitSlot submitSlot : slots) {
            if (submitSlot != null) {
                vkFreeCommandBuffers(context.device(), context.commandPool(), submitSlot.commandBuffer);
            }
        }
        if (timestampPool != 0) {
            vkDestroyQueryPool(context.device(), timestampPool, null);
            timestampPool = 0;
        }
        if (passTimestampPool != 0) {
            vkDestroyQueryPool(context.device(), passTimestampPool, null);
            passTimestampPool = 0;
        }
        commandBuffer = null;
    }

    @Override public TransientMemory transientMemory() { return transientMemory; }
    @Override public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
        if (renderPassOpen) throw new IllegalStateException("A render pass is already open");
        renderPassOpen = true;
        activeRenderPass = new LavaFlowRenderPass(this, descriptor);
        return activeRenderPass;
    }
    @Override public void submitRenderPass() {
        if (renderPassOpen) {
            renderPassOpen = false;
            LavaFlowRenderPass pass = activeRenderPass;
            activeRenderPass = null;
            pass.end();
        }
    }

    void markRenderPassClosed() { renderPassOpen = false; }

    void transition(LavaFlowGpuTexture texture, int targetLayout) {
        int sourceLayout = texture.layout();
        if (sourceLayout == targetLayout) return;
        LavaFlowFrameStats.barrierRecorded();
        try (MemoryStack stack = stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                    .oldLayout(sourceLayout).newLayout(targetLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(texture.handle());
            barrier.subresourceRange().aspectMask(LavaFlowVk.aspect(texture.getFormat())).baseMipLevel(0)
                    .levelCount(texture.getMipLevels()).baseArrayLayer(0).layerCount(texture.getDepthOrLayers());
            barrier.srcAccessMask(sourceAccessForLayout(sourceLayout));
            barrier.dstAccessMask(destinationAccessForLayout(targetLayout));
            vkCmdPipelineBarrier(commandBuffer, sourceStageForLayout(sourceLayout), destinationStageForLayout(targetLayout),
                    0, null, null, barrier);
            texture.layout(targetLayout);
        }
    }

    @Override public void clearColorTexture(GpuTexture gpuTexture, Vector4fc color) {
        LavaFlowGpuTexture texture = texture(gpuTexture); transition(texture, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        try (MemoryStack stack = stackPush()) {
            VkClearColorValue clear = VkClearColorValue.calloc(stack).float32(stack.floats(color.x(), color.y(), color.z(), color.w()));
            VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(texture.getMipLevels()).baseArrayLayer(0).layerCount(texture.getDepthOrLayers());
            vkCmdClearColorImage(commandBuffer, texture.handle(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clear, range);
        }
        restoreSampledLayout(texture);
    }
    @Override public void clearColorAndDepthTextures(GpuTexture color, Vector4fc value, GpuTexture depth, double depthValue) {
        clearColorTexture(color, value); clearDepthTexture(depth, depthValue);
    }
    @Override public void clearDepthTexture(GpuTexture gpuTexture, double value) {
        LavaFlowGpuTexture texture = texture(gpuTexture); transition(texture, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        try (MemoryStack stack = stackPush()) {
            VkClearDepthStencilValue clear = VkClearDepthStencilValue.calloc(stack).depth((float)value).stencil(0);
            VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack).aspectMask(LavaFlowVk.aspect(texture.getFormat()))
                    .baseMipLevel(0).levelCount(texture.getMipLevels()).baseArrayLayer(0).layerCount(texture.getDepthOrLayers());
            vkCmdClearDepthStencilImage(commandBuffer, texture.handle(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clear, range);
        }
        restoreSampledLayout(texture);
    }

    @Override public void writeToBuffer(GpuBufferSlice destination, ByteBuffer source) {
        long size = source.remaining();
        if (size == 0) return;
        if (size > destination.length()) throw new IllegalArgumentException("Destination buffer slice is too small");
        GpuBufferSlice staging = transientMemory.uploadStaging(source, 1);
        copyToBuffer(staging, destination.slice(0, size));
    }
    @Override public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice destination) {
        if (source.length() > destination.length()) throw new IllegalArgumentException("Destination buffer slice is too small");
        try (MemoryStack stack = stackPush()) {
            VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack).srcOffset(source.offset())
                    .dstOffset(destination.offset()).size(source.length());
            vkCmdCopyBuffer(commandBuffer, buffer(source).handle(), buffer(destination).handle(), copy);
            int usage = destination.buffer().usage();
            int destinationStages = bufferDestinationStages(usage);
            int destinationAccess = bufferDestinationAccess(usage);
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(destinationAccess)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(buffer(destination).handle()).offset(destination.offset()).size(source.length());
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    destinationStages, 0, null, barrier, null);
        }
    }
    @Override public void writeToTexture(GpuTexture target, ByteBuffer source, int mip, int layer, int x, int y, int width, int height) {
        LavaFlowGpuBuffer staging = new LavaFlowGpuBuffer(device, GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_SRC, source.remaining());
        staging.write(0, source);
        copyBufferToTexture(staging.slice(), 0, 0, width, height, target, x, y, width, height, mip, layer);
        device.defer(staging);
    }
    @Override public void copyBufferToTexture(GpuBufferSlice source, int rowOffset, int imageOffset, int rowLength, int imageHeight, GpuTexture target, int x, int y, int width, int height, int mip, int layer) {
        LavaFlowGpuTexture texture = texture(target); transition(texture, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        long offset = source.offset() + (long)(rowOffset + imageOffset * rowLength) * target.getFormat().blockSize();
        try (MemoryStack stack = stackPush()) {
            VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack).bufferOffset(offset)
                    .bufferRowLength(rowLength).bufferImageHeight(imageHeight);
            copy.imageSubresource().aspectMask(LavaFlowVk.aspect(target.getFormat())).mipLevel(mip).baseArrayLayer(layer).layerCount(1);
            copy.imageOffset().set(x, y, 0); copy.imageExtent().set(width, height, 1);
            vkCmdCopyBufferToImage(commandBuffer, buffer(source).handle(), texture.handle(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy);
        }
        if ((target.usage() & GpuTexture.USAGE_TEXTURE_BINDING) != 0) {
            transition(texture, LavaFlowVk.sampledLayout());
        }
    }
    // 26.4 dropped both the Runnable callback and the whole-texture overload from CommandEncoderBackend:
    // a readback that needs to be waited on now goes through GpuFence (createFence), so there is nothing
    // left for the backend to notify. Only the rectangle form is still abstract.
    @Override public void copyTextureToBuffer(GpuTexture source, GpuBuffer target, long offset, int mip, int x, int y, int width, int height) {
        LavaFlowGpuTexture texture = texture(source); transition(texture, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        try (MemoryStack stack = stackPush()) {
            VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack).bufferOffset(offset);
            copy.imageSubresource().aspectMask(LavaFlowVk.aspect(source.getFormat())).mipLevel(mip).baseArrayLayer(0).layerCount(1);
            copy.imageOffset().set(x, y, 0); copy.imageExtent().set(width, height, 1);
            vkCmdCopyImageToBuffer(commandBuffer, texture.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    ((LavaFlowGpuBuffer)target).handle(), copy);
        }
        restoreSampledLayout(texture);
    }
    @Override public void copyTextureToTexture(GpuTexture source, GpuTexture target, int mip, int targetX, int targetY, int sourceX, int sourceY, int width, int height) {
        LavaFlowGpuTexture src = texture(source), dst = texture(target);
        transition(src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL); transition(dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        try (MemoryStack stack = stackPush()) {
            VkImageCopy.Buffer copy = VkImageCopy.calloc(1, stack);
            copy.srcSubresource().aspectMask(LavaFlowVk.aspect(source.getFormat())).mipLevel(mip).baseArrayLayer(0).layerCount(1);
            copy.dstSubresource().aspectMask(LavaFlowVk.aspect(target.getFormat())).mipLevel(mip).baseArrayLayer(0).layerCount(1);
            copy.srcOffset().set(sourceX, sourceY, 0); copy.dstOffset().set(targetX, targetY, 0); copy.extent().set(width, height, 1);
            vkCmdCopyImage(commandBuffer, src.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst.handle(),
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy);
        }
        restoreSampledLayout(src);
        restoreSampledLayout(dst);
    }
    @Override public GpuFence createFence() { return new LavaFlowFence(this, recordingSerial); }

    boolean awaitSubmitCompletion(long serial, long timeout) {
        if (serial <= completedSerial) return true;
        if (serial >= recordingSerial) {
            if (timeout == 0) return false;
            throw new IllegalStateException("Cannot wait for work that has not been submitted");
        }
        for (SubmitSlot submitSlot : slots) {
            if (submitSlot.inFlight && submitSlot.serial == serial) {
                int result = vkWaitForFences(context.device(), submitSlot.fence, true, timeout);
                if (result == VK_TIMEOUT) return false;
                check(result, "vkWaitForFences(logical fence)");
                completedSerial = Math.max(completedSerial, serial);
                return true;
            }
        }
        return serial <= completedSerial;
    }
    @Override public void writeTimestamp(GpuQueryPool pool, int index) {
        // Reset the query slot in the same command buffer before writing the timestamp.
        // Without this, the slot keeps its previous GPU tick across frames; on TBDR drivers
        // (Mali) that can be a smaller value than the next write after counter wrap-around,
        // producing a negative end - start delta and a negative GPU frequency on F3.
        // The two commands are recorded back-to-back, so the GPU executes reset before write.
        long handle = ((LavaFlowQueryPool)pool).handle();
        vkCmdResetQueryPool(commandBuffer, handle, index, 1);
        vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, handle, index);
    }

    private void memoryBarrier(MemoryStack stack) {
        VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
        vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, barrier, null, null);
    }

    private static LavaFlowGpuBuffer buffer(GpuBufferSlice slice) { return (LavaFlowGpuBuffer)slice.buffer(); }
    private static LavaFlowGpuTexture texture(GpuTexture texture) { return (LavaFlowGpuTexture)texture; }

    private void restoreSampledLayout(LavaFlowGpuTexture texture) {
        if ((texture.usage() & GpuTexture.USAGE_TEXTURE_BINDING) != 0) {
            transition(texture, LavaFlowVk.sampledLayout());
        }
    }

    private static int sourceStageForLayout(int layout) {
        return switch (layout) {
            case VK_IMAGE_LAYOUT_UNDEFINED -> VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            case VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL ->
                    VK_PIPELINE_STAGE_TRANSFER_BIT;
            case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            case VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL ->
                    VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
            case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL ->
                    VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
            default -> VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
        };
    }

    private static int destinationStageForLayout(int layout) {
        return switch (layout) {
            case VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL ->
                    VK_PIPELINE_STAGE_TRANSFER_BIT;
            case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            case VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL ->
                    VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
            case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL ->
                    VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
            default -> VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
        };
    }

    private static int sourceAccessForLayout(int layout) {
        return switch (layout) {
            case VK_IMAGE_LAYOUT_UNDEFINED -> 0;
            case VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> VK_ACCESS_TRANSFER_READ_BIT;
            case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> VK_ACCESS_TRANSFER_WRITE_BIT;
            case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL ->
                    VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            case VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL ->
                    VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
            case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL ->
                    VK_ACCESS_SHADER_READ_BIT;
            default -> VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
        };
    }

    private static int destinationAccessForLayout(int layout) {
        return switch (layout) {
            case VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> VK_ACCESS_TRANSFER_READ_BIT;
            case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> VK_ACCESS_TRANSFER_WRITE_BIT;
            case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL ->
                    VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            case VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL ->
                    VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
            case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL ->
                    VK_ACCESS_SHADER_READ_BIT;
            default -> VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
        };
    }

    private static int bufferDestinationStages(int usage) {
        int stages = 0;
        if ((usage & (GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_INDEX)) != 0) {
            stages |= VK_PIPELINE_STAGE_VERTEX_INPUT_BIT;
        }
        if ((usage & (GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER)) != 0) {
            stages |= VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        }
        if ((usage & GpuBuffer.USAGE_INDIRECT_PARAMETERS) != 0) stages |= VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT;
        if ((usage & (GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST)) != 0) stages |= VK_PIPELINE_STAGE_TRANSFER_BIT;
        if ((usage & GpuBuffer.USAGE_MAP_READ) != 0) stages |= VK_PIPELINE_STAGE_HOST_BIT;
        return stages == 0 ? VK_PIPELINE_STAGE_ALL_COMMANDS_BIT : stages;
    }

    private static int bufferDestinationAccess(int usage) {
        int access = 0;
        if ((usage & GpuBuffer.USAGE_VERTEX) != 0) access |= VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT;
        if ((usage & GpuBuffer.USAGE_INDEX) != 0) access |= VK_ACCESS_INDEX_READ_BIT;
        if ((usage & GpuBuffer.USAGE_UNIFORM) != 0) access |= VK_ACCESS_UNIFORM_READ_BIT;
        if ((usage & GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER) != 0) access |= VK_ACCESS_SHADER_READ_BIT;
        if ((usage & GpuBuffer.USAGE_INDIRECT_PARAMETERS) != 0) access |= VK_ACCESS_INDIRECT_COMMAND_READ_BIT;
        if ((usage & GpuBuffer.USAGE_COPY_SRC) != 0) access |= VK_ACCESS_TRANSFER_READ_BIT;
        if ((usage & GpuBuffer.USAGE_COPY_DST) != 0) access |= VK_ACCESS_TRANSFER_WRITE_BIT;
        if ((usage & GpuBuffer.USAGE_MAP_READ) != 0) access |= VK_ACCESS_HOST_READ_BIT;
        return access == 0 ? VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT : access;
    }
}
