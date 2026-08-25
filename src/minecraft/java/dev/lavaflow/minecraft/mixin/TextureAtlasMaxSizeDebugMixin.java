package dev.lavaflow.minecraft.mixin;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DIAGNOSTIC + WORKAROUND mixin.
 * Prints the values feeding TextureAtlas's max texture size, then forces a sane 8192
 * ceiling so stitching can succeed. Remove the setReturnValue once the underlying
 * DeviceInfo propagation is fixed.
 */
@Mixin(TextureAtlas.class)
public class TextureAtlasMaxSizeDebugMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("LavaFlow/AtlasDebug");

    @Inject(method = "maxSupportedTextureSize", at = @At("RETURN"), require = 0, cancellable = true)
    private void lavaflow$forceMaxSize(CallbackInfoReturnable<Integer> cir) {
        GpuDevice device = RenderSystem.getDevice();
        int deviceMax = device == null ? -1 : device.getMaxTextureSize();
        int infoMax = device == null ? -1 : device.getDeviceInfo().limits().maxTextureSize();
        LOGGER.info("LavaFlow atlas debug: atlas={} gpuDevice.getMaxTextureSize()={} deviceInfo.limits().maxTextureSize()={}",
                cir.getReturnValue(), deviceMax, infoMax);
        if (cir.getReturnValue() <= 0) {
            cir.setReturnValue(8192);
        }
    }
}