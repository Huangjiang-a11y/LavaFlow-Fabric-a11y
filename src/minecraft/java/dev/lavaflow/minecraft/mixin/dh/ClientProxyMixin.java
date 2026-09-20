package dev.lavaflow.minecraft.dh.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.seibel.distanthorizons.fabric.FabricClientProxy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips Distant Horizons' end-of-level-render capture when no OpenGL context exists.
 *
 * <p>The handler reads the bound framebuffer with a raw OpenGL query to restore it around the mod's
 * own OpenGL rendering. On a Vulkan backend there is no context, so the query aborts the JVM inside
 * the driver — and the captured id feeds only the OpenGL renderer, which the mod never runs there.
 * The condition mirrors how Distant Horizons itself selects its Vulkan rendering path.
 */
@Mixin(FabricClientProxy.class)
abstract class ClientProxyMixin {
    @Inject(method = "afterLevelRenderEvent", at = @At("HEAD"), cancellable = true, remap = false)
    private void lavaflow$skipGlCaptureWithoutGl(CallbackInfo callback) {
        com.mojang.blaze3d.systems.GpuDevice device = RenderSystem.getDevice();
        if (device != null && "Vulkan".equalsIgnoreCase(device.getDeviceInfo().backendName())) {
            callback.cancel();
        }
    }
}
