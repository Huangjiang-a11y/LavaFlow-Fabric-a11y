package dev.lavaflow.minecraft.mixin;

import dev.lavaflow.minecraft.vulkan.LavaFlowFrameStats;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Samples frame pacing for LavaFlow development, enabled with {@code -Dlavaflow.frameStats=true}.
 *
 * <p>Hooked here rather than on LavaFlow's presentation path so that the same measurement point
 * applies whichever graphics backend is active, which is what makes a comparison between backends
 * meaningful.
 */
@Mixin(Minecraft.class)
abstract class FrameStatsMixin {
    @Inject(method = "renderFrame", at = @At("RETURN"))
    private void lavaflow$sampleFrame(CallbackInfo callback) {
        LavaFlowFrameStats.framePresented();
    }
}
