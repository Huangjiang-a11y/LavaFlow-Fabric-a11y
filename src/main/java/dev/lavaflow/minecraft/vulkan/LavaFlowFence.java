package dev.lavaflow.minecraft.vulkan;

import com.mojang.blaze3d.buffers.GpuFence;

final class LavaFlowFence implements GpuFence {
    private final LavaFlowCommandEncoder encoder;
    private final long submitSerial;
    private boolean completed;

    LavaFlowFence(LavaFlowCommandEncoder encoder, long submitSerial) {
        this.encoder = encoder;
        this.submitSerial = submitSerial;
    }

    @Override public boolean awaitCompletion(long timeout) {
        if (!completed) completed = encoder.awaitSubmitCompletion(submitSerial, timeout);
        return completed;
    }

    @Override public void close() { completed = true; }
}
