package dev.lavaflow.minecraft.sodium.mixin;

import dev.lavaflow.minecraft.sodium.LavaFlowSodium;
import net.caffeinemc.mods.sodium.client.gpu.device.backend.DrawBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Routes Sodium's terrain rendering onto its Vulkan path when LavaFlow is the active backend.
 *
 * <p>Sodium selects its backend by testing for Minecraft's own {@code VulkanDevice}, so a foreign
 * Vulkan backend would be treated as OpenGL and Sodium would issue OpenGL calls. LavaFlow selects
 * the indirect Vulkan path, which is built on core {@code vkCmdDrawIndexedIndirect} and needs no
 * device extension.
 */
@Mixin(DrawBackend.class)
abstract class DrawBackendMixin {
    @Inject(method = "chooseBackend", at = @At("HEAD"), cancellable = true)
    private static void lavaflow$chooseVulkanBackend(CallbackInfoReturnable<DrawBackend> callback) {
        if (!LavaFlowSodium.isLavaFlowDevice()) return;
        LavaFlowSodium.install();
        callback.setReturnValue(LavaFlowSodium.drawBackend());
    }
}
