package dev.lavaflow.minecraft.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Removes Minecraft's framerate throttles, enabled with {@code -Dlavaflow.unlockFramerate=true}.
 *
 * <p>Minecraft clamps the framerate to 30 after a minute without input, and to 10 or 60 when the
 * window is iconified or no level is loaded. A benchmark run receives no input, so it would
 * otherwise measure the throttle rather than the backend. Development-only, and inert unless the
 * property is set.
 */
@Mixin(FramerateLimitTracker.class)
abstract class FramerateLimitMixin {
    private static final boolean UNLOCKED = Boolean.getBoolean("lavaflow.unlockFramerate");

    @Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
    private void lavaflow$unlockFramerate(CallbackInfoReturnable<Integer> callback) {
        if (!UNLOCKED) return;
        callback.setReturnValue(Integer.MAX_VALUE);
    }
}
