package dev.lavaflow.minecraft.mixin;

import net.minecraft.client.renderer.texture.TextureAtlas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * WORKAROUND mixin: substitutes a usable size when the atlas is told it may not allocate one.
 *
 * <p>On the Mali-G76 this port was developed against, {@code maxSupportedTextureSize} could report a
 * non-positive value, leaving the atlas with nothing to size itself against. A fallback kept the game
 * running, and it is kept here as a net against that returning.
 *
 * <p>It no longer fires: 26.3 reports the device's real limit (8192 on that GPU), so all this does now
 * is log a warning if the value ever goes bad again -- quietly substituting a number would hide the
 * regression instead of reporting it. When it does fire the warning says what was reported and what was
 * used, which is what a bug report needs.
 *
 * <p>The reflection probe that used to live here is deliberately not coming back. It existed to find out
 * what the 26.3 frontend exposes, and that question is answered and written down: {@code getDeviceInfo()}
 * resolves, {@code getMaxTextureSize()}, {@code getRenderer()} and {@code getVendor()} do not. Re-adding
 * it would only buy five log lines per atlas rebuild.
 */
@Mixin(TextureAtlas.class)
public class TextureAtlasMaxSizeFallbackMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("LavaFlow/TextureAtlas");

    /** 8192 is the limit of the Mali device this guards, and is well within any modern GPU. */
    private static final int FALLBACK_SIZE = 8192;

    @Inject(method = "maxSupportedTextureSize", at = @At("RETURN"), require = 0, cancellable = true)
    private void lavaflow$fallbackMaxSize(CallbackInfoReturnable<Integer> cir) {
        int reported = cir.getReturnValue();
        if (reported > 0) return;
        LOGGER.warn("TextureAtlas.maxSupportedTextureSize() reported {}, which is not usable; using {}",
                reported, FALLBACK_SIZE);
        cir.setReturnValue(FALLBACK_SIZE);
    }
}
