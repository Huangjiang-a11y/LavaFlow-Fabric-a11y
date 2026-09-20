package dev.lavaflow.minecraft.dh.mixin;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import com.seibel.distanthorizons.fabric.FabricTextureUnwrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Returns Distant Horizons' own no-texture sentinel instead of letting its GL unwrap throw on a
 * Vulkan texture.
 *
 * <p>Distant Horizons unwraps Blaze3D textures to raw OpenGL ids for its OpenGL renderer. Most of
 * its call sites catch the resulting {@code ClassCastException} on a Vulkan backend and fall back
 * to {@code -1}, but the lightmap capture hook does not and crashes the game. Under its Vulkan
 * rendering path the ids are never consumed, so answering {@code -1} — the same sentinel those
 * guarded call sites produce — restores the degradation the mod intends everywhere.
 */
@Mixin(FabricTextureUnwrapper.class)
abstract class TextureUnwrapperMixin {
    @Inject(method = "getGlTextureIdFromGpuTexture", at = @At("HEAD"), cancellable = true)
    private static void lavaflow$unwrapNonGlTexture(GpuTexture texture, CallbackInfoReturnable<Integer> callback) {
        if (!(texture instanceof GlTexture)) callback.setReturnValue(-1);
    }
}
