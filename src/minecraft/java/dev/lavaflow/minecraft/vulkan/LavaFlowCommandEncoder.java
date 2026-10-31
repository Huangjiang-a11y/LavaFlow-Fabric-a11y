package dev.lavaflow.minecraft.vulkan;

import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
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
    /** 有缓冲被拷贝写过、但还没发屏障。 */
    private boolean bufferWriteBarrierPending;
    /** 自上次屏障以来被拷贝写过的缓冲句柄（整缓冲粒度，保守）。 */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet recentlyWrittenBuffers =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
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
            awaitFence(slot.fence, "vkWaitForFences(submit slot)");
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
    private final long[] boundVertexHandles = new long[RenderPass.MAX_VERTEX_BUFFERS];
    private final long[] boundVertexOffsets = new long[RenderPass.MAX_VERTEX_BUFFERS];
    private final boolean[] vertexSlotBound = new boolean[RenderPass.MAX_VERTEX_BUFFERS];

    /** 记录里当前绑在 {@code slot} 上的顶点缓冲句柄；0 表示这个 command buffer 里还没录过。 */
    long boundVertexHandle(int slot) {
        return vertexSlotBound[slot] ? boundVertexHandles[slot] : 0;
    }

    /** 记录里当前绑定的索引缓冲句柄；0 表示这个 command buffer 里还没录过。 */
    long boundIndexHandle() {
        return boundIndexHandle;
    }

    /** 记录里 slot 上顶点绑定的字节偏移；未绑定时为 0。 */
    long boundVertexOffset(int slot) {
        return vertexSlotBound[slot] ? boundVertexOffsets[slot] : 0;
    }

    /** 记录里索引绑定的 VkIndexType；-1 表示还没录过。 */
    int boundIndexType() {
        return boundIndexType;
    }

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
        int submittedSlot = slotIndex;
        prepareSlot((slotIndex + 1) % SUBMIT_SLOTS);
        // 官方在提交后立刻 awaitSubmitCompletion(currentSubmitIndex - 2) 把在飞提交压住；这里是更
        // 强的一档：等这一次跑完再返回，于是"上一次提交里写的、下一次提交要读的"绝无重叠可能。
        if (LavaFlowSync.SERIALIZE_SUBMIT) awaitSlotCompletion(submittedSlot);
    }

    /** 等某个提交槽真正跑完，并把它的资源交还给设备（与 prepareSlot 里复用槽时做的事一致）。 */
    private void awaitSlotCompletion(int index) {
        SubmitSlot target = slots[index];
        if (!target.inFlight) return;
        awaitFence(target.fence, "vkWaitForFences(serialize submit)");
        check(vkResetFences(context.device(), target.fence), "vkResetFences(serialize submit)");
        completedSerial = Math.max(completedSerial, target.serial);
        target.inFlight = false;
        device.completeSubmit(target.batch);
        target.batch = null;
        readPassGpuTimes(index);
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

    /**
     * Waits for a submit fence, logging rather than throwing when it takes a long while. A fence can
     * genuinely need seconds on a thermally throttled phone, and turning a slow frame into an exception
     * kills the game over it. The wait keeps going instead, so a real hang still shows up - as a hang
     * with a log line, which is what it is.
     */
    private void awaitFence(long fence, String what) {
        while (true) {
            int result = vkWaitForFences(context.device(), fence, true, 5_000_000_000L);
            if (result == VK_SUCCESS) return;
            if (result != VK_TIMEOUT) {
                if (result == VK_ERROR_DEVICE_LOST) logDeviceLost(what, -1);
                check(result, what);
                return;
            }
            LOGGER.log(System.Logger.Level.WARNING, what + " 已等超过 5 秒（GPU 可能被降频或卡住），继续等");
        }
    }

    /**
     * Reports a lost device together with what was in flight when it was noticed. The loss itself always
     * happened earlier - a wait is only where the backend finds out - so the only usable record is this:
     * which wait noticed, and which submissions were still running at that moment.
     */
    private void logDeviceLost(String what, long serial) {
        StringBuilder inFlight = new StringBuilder();
        for (SubmitSlot other : slots) {
            if (other != null && other.inFlight) {
                if (inFlight.length() > 0) inFlight.append(", ");
                inFlight.append(other.serial);
            }
        }
        LOGGER.log(System.Logger.Level.ERROR, "VK_ERROR_DEVICE_LOST at " + what
                + (serial >= 0 ? " (serial=" + serial + ")" : "")
                + ": completed=" + completedSerial + ", recording=" + recordingSerial
                + ", in flight=[" + inFlight + "]. The device was already lost before this call; those are"
                + " the submissions that were running when it happened.");
    }

/^                check(vkWaitForFences(context.device(), submitSlot.fence, true, 5_000_000_000L),$/,/^                submitSlot.inFlight = false;$/c
                awaitFence(submitSlot.fence, "vkWaitForFences(destroy)");
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
        // 开启新通道前先把欠的缓冲写屏障发掉：通道外那条是官方式的全局屏障。
        flushBufferWriteBarrier();
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
    @Override public void clearColorAndDepthTextures(GpuTexture color, Vector4fc value, GpuTexture depth, double depthValue, int x, int y, int width, int height) {
        LavaFlowGpuTexture colorTexture = texture(color);
        LavaFlowGpuTexture depthTexture = texture(depth);
        if (width <= 0 || height <= 0) return;
        if (x < 0 || y < 0 || x + width > color.getWidth(0) || y + height > color.getHeight(0)) {
            throw new IllegalArgumentException("Clear rectangle exceeds texture bounds");
        }
        // LavaFlowFrameStats.partialClear(); // dead code: partialClear never called in production
        // The two attachment views are reused, not created here and destroyed on the way out: this was the
        // only view churn LavaFlow manufactured itself, and a device run showed it was exactly twice the
        // partial clears, all of it labeled "UI items atlas" and "UI items atlas depth". See
        // LavaFlowGpuTexture.cachedClearView for the lifetime rule. (26.3 fix, backported.)
        GpuTextureView colorView = colorTexture.cachedClearView();
        GpuTextureView depthView = depthTexture.cachedClearView();
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "LavaFlow partial color/depth clear")
                .withColorAttachment(colorView)
                .withDepthAttachment(depthView)
                .withRenderArea(new RenderPass.RenderArea(0, 0, color.getWidth(0), color.getHeight(0)));
        createRenderPass(descriptor);
        // vkCmdClearAttachments is recorded directly rather than through a draw, so the pass has
        // to be begun explicitly.
        activeRenderPass.ensureBegun();
        try (MemoryStack stack = stackPush()) {
            VkClearRect.Buffer rect = VkClearRect.calloc(1, stack).baseArrayLayer(0).layerCount(1);
            rect.rect().offset().set(x, y);
            rect.rect().extent().set(width, height);

            VkClearAttachment.Buffer attachments = VkClearAttachment.calloc(2, stack);
            attachments.get(0).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).colorAttachment(0);
            attachments.get(0).clearValue().color()
                    .float32(stack.floats(value.x(), value.y(), value.z(), value.w()));
            attachments.get(1).aspectMask(LavaFlowVk.aspect(depthTexture.getFormat()));
            attachments.get(1).clearValue().depthStencil().depth((float) depthValue).stencil(0);
            vkCmdClearAttachments(commandBuffer, attachments, rect);
        } finally {
            submitRenderPass();
        }
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
        copyToBuffer(staging, destination.slice(0, size), "writeToBuffer");
    }
    @Override public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice destination) {
        copyToBuffer(source, destination, "copyToBuffer");
    }

    /**
     * @param origin 记进诊断日志的来源名：{@code writeToBuffer} 会走到这里做实际拷贝，日志里要能分清
     *               是主机写入还是纯拷贝。
     */
    private void copyToBuffer(GpuBufferSlice source, GpuBufferSlice destination, String origin) {
        if (source.length() > destination.length()) throw new IllegalArgumentException("Destination buffer slice is too small");
        if (!LavaFlowSync.NARROW_BARRIER) {
            long sourceHandle = buffer(source).handle();
            long destinationHandle = buffer(destination).handle();
            // 连续拷贝整段只需要一条屏障：只有这笔真的会读到刚写过的东西（同缓冲、或源/目标在
            // 本段里已经写过）时，才先把账落成屏障。
            if (sourceHandle == destinationHandle || recentlyWrittenBuffers.contains(sourceHandle)
                    || recentlyWrittenBuffers.contains(destinationHandle)) {
                flushBufferWriteBarrier();
            }
        }
        try (MemoryStack stack = stackPush()) {
            VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack).srcOffset(source.offset())
                    .dstOffset(destination.offset()).size(source.length());
            vkCmdCopyBuffer(commandBuffer, buffer(source).handle(), buffer(destination).handle(), copy);
            // copy 只是记录，录制时目标的主机内存还是旧内容：不能再用主机捷径读它。
            if (destination.buffer() instanceof LavaFlowGpuBuffer written) written.markGpuWritten();
            // 诊断：源若是已映射的（transient staging 就是），直接读它即将被拷走的那段字节查一次。
            if (source.buffer() instanceof LavaFlowGpuBuffer readback) {
                ByteBuffer bytes = readback.mappedView(source.offset(), source.length());
                if (bytes != null) {
                    LavaFlowDataWatch.onUpload(origin, destination.buffer().usage(),
                            destination.buffer(), destination.offset(), bytes);
                }
            }
            if (LavaFlowSync.NARROW_BARRIER) {
                // 旧写法，留作对照：按目标缓冲的用途位推出 dst stage/access，并限定在那一段字节上。
                int usage = destination.buffer().usage();
                int destinationStages = bufferDestinationStages(usage);
                int destinationAccess = bufferDestinationAccess(usage);
                VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                        .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(destinationAccess)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .buffer(buffer(destination).handle()).offset(destination.offset()).size(source.length());
                vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                        destinationStages, 0, null, barrier, null);
            } else {
                // 默认：不再当场发全局屏障，只记下这笔写过的缓冲；下一个落账点（开通道、各类拷贝、
                // 或通道内立刻补）再发。真机 A/B 证明窄屏障会漏同步，画面表现为半透明表面的几何
                // 扭曲，见 LavaFlowSync 的说明。
                noteBufferWritten(destination);
            }
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
    @Override public void copyTextureToBuffer(GpuTexture source, GpuBuffer target, long offset, Runnable callback, int mip) {
        copyTextureToBuffer(source, target, offset, callback, mip, 0, 0, source.getWidth(mip), source.getHeight(mip));
    }
    @Override public void copyTextureToBuffer(GpuTexture source, GpuBuffer target, long offset, Runnable callback, int mip, int x, int y, int width, int height) {
        flushBufferWriteBarrier();
        LavaFlowGpuTexture texture = texture(source); transition(texture, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        try (MemoryStack stack = stackPush()) {
            VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack).bufferOffset(offset);
            copy.imageSubresource().aspectMask(LavaFlowVk.aspect(source.getFormat())).mipLevel(mip).baseArrayLayer(0).layerCount(1);
            copy.imageOffset().set(x, y, 0); copy.imageExtent().set(width, height, 1);
            vkCmdCopyImageToBuffer(commandBuffer, texture.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    ((LavaFlowGpuBuffer)target).handle(), copy);
        }
        restoreSampledLayout(texture);
        if (callback != null) device.afterSubmit(callback);
    }
    @Override public void copyTextureToTexture(GpuTexture source, GpuTexture target, int mip, int targetX, int targetY, int sourceX, int sourceY, int width, int height) {
        flushBufferWriteBarrier();
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

    /**
     * Records the barrier that makes the pending buffer writes visible to the next draw.
     *
     * <p>Outside a render pass this is the global barrier the official backend uses (ALL_COMMANDS with
     * MEMORY_READ|MEMORY_WRITE on both sides). Inside one it may only name graphics stages
     * (VUID-vkCmdPipelineBarrier-None-07892) and the pass has to have been created with a subpass self-dependency
     * whose scopes are a superset (VUID-vkCmdPipelineBarrier-None-07889) - LavaFlowVulkanContext creates that
     * one, so this form is legal here. BOTTOM_OF_PIPE as the source waits for every prior command including the
     * transfer stage the copy ran as, which TRANSFER as a source stage is not allowed to name inside a pass.
     */
    void flushBufferWriteBarrier() {
        if (!bufferWriteBarrierPending) return;
        try (MemoryStack stack = stackPush()) {
            if (renderPassOpen) {
                VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
                        .srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
                vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                        VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT, 0, barrier, null, null);
            } else {
                memoryBarrier(stack);
            }
        }
        bufferWriteBarrierPending = false;
        recentlyWrittenBuffers.clear();
    }

    boolean bufferWriteBarrierPending() { return bufferWriteBarrierPending; }

    /** 记下这笔拷贝写过的缓冲：之后若有拷贝要读它、或者要开始绘制，先补屏障。 */
    private void noteBufferWritten(GpuBufferSlice destination) {
        recentlyWrittenBuffers.add(buffer(destination).handle());
        bufferWriteBarrierPending = true;
        // A write inside a pass cannot wait for the next flush point: the barrier would land in a later
        // command buffer (or outside the pass altogether) while the draw in this one already reads it -
        // for indirect draws that means the command processor reading parameters the copy has not written
        // yet, which is a garbage index count in the hands of the GPU. The in-pass barrier is legal now that
        // the render pass carries its self-dependency, so flush here.
        if (renderPassOpen) flushBufferWriteBarrier();
    }

/^    @Override public void copyBufferToTexture(GpuBufferSlice source, int rowOffset, int imageOffset, int rowLength, int imageHeight, GpuTexture target, int x, int y, int width, int height, int mip, int layer) {$/a
        flushBufferWriteBarrier();
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
