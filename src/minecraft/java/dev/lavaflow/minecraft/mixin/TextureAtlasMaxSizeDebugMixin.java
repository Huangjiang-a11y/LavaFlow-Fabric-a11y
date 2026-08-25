package dev.lavaflow.minecraft.mixin;

import net.minecraft.client.renderer.texture.TextureAtlas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;

/**
 * DIAGNOSTIC + WORKAROUND mixin for LavaFlow on Minecraft 26.2.
 * Forces TextureAtlas's max texture size to 8192 when it reports <= 0 (the Mali bug),
 * and uses runtime reflection to probe whatever the 26.2 GpuDevice actually exposes,
 * since the 1.21.x getMaxTextureSize() symbol no longer exists at compile time.
 * Remove the setReturnValue once the underlying limit propagation is fixed.
 */
@Mixin(TextureAtlas.class)
public class TextureAtlasMaxSizeDebugMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("LavaFlow/AtlasDebug");

    @Inject(method = "maxSupportedTextureSize", at = @At("RETURN"), require = 0, cancellable = true)
    private void lavaflow$forceMaxSize(CallbackInfoReturnable<Integer> cir) {
        int atlas = cir.getReturnValue();
        LOGGER.info("LavaFlow atlas debug: TextureAtlas.maxSupportedTextureSize() raw={}", atlas);
        try {
            Class<?> rs = Class.forName("com.mojang.blaze3d.systems.RenderSystem");
            Object dev = rs.getMethod("getDevice").invoke(null);
            if (dev != null) {
                Class<?> devClass = dev.getClass();
                for (String name : new String[]{"getMaxTextureSize", "getDeviceInfo", "getRenderer", "getVendor"}) {
                    try {
                        Method m = devClass.getMethod(name);
                        LOGGER.info("LavaFlow probe: {}.{}() -> {}", devClass.getSimpleName(), name, m.invoke(dev));
                    } catch (NoSuchMethodException e) {
                        LOGGER.info("LavaFlow probe: {}.{}() NOT PRESENT", devClass.getSimpleName(), name);
                    }
                }
            } else {
                LOGGER.info("LavaFlow probe: RenderSystem.getDevice() == null");
            }
        } catch (Throwable t) {
            LOGGER.info("LavaFlow probe error: {}", t.toString());
        }
        if (atlas <= 0) {
            cir.setReturnValue(8192);
        }
    }
}
