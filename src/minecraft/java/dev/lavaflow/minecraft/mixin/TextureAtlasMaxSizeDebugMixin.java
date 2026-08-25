package dev.lavaflow.minecraft.mixin;

import net.minecraft.client.renderer.texture.TextureAtlas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DEBUG-ONLY mixin (no behaviour change). Logs the value TextureAtlas reports as its
 * maximum supported texture size, which SpriteLoader uses to cap atlas dimensions before
 * stitching. This pins down whether the atlas width/height cap is the distorted sub-32
 * value or the intended 8192 override. Safe to remove once diagnosis is complete.
 */
@Mixin(TextureAtlas.class)
public class TextureAtlasMaxSizeDebugMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("LavaFlow/AtlasDebug");

    @Inject(method = "maxSupportedTextureSize", at = @At("RETURN"), require = 0)
    private void lavaflow$logMaxSize(CallbackInfoReturnable<Integer> cir) {
        LOGGER.info("LavaFlow atlas debug: TextureAtlas.maxSupportedTextureSize() = {}", cir.getReturnValue());
    }
}
