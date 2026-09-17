package dev.lavaflow.minecraft.mixin;

import com.mojang.blaze3d.systems.GpuBackend;
import dev.lavaflow.minecraft.LavaFlowBackend;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PreferredGraphicsApi.class)
abstract class PreferredGraphicsApiMixin {
    // Development switch: leaves Minecraft's own backend selection in place so the two backends can be
    // compared under identical instrumentation. LavaFlow's other mixins stay loaded and inert.
    private static final boolean DISABLED = Boolean.getBoolean("lavaflow.disable");

    @Inject(method = "getBackendsToTry", at = @At("HEAD"), cancellable = true)
    private void lavaflow$selectBackend(CallbackInfoReturnable<GpuBackend[]> callback) {
        if (DISABLED) return;
        callback.setReturnValue(new GpuBackend[]{new LavaFlowBackend()});
    }
}
