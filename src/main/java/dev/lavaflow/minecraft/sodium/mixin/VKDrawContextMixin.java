package dev.lavaflow.minecraft.sodium.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import dev.lavaflow.minecraft.sodium.LavaFlowSodium;
import dev.lavaflow.minecraft.vulkan.LavaFlowVulkanPass;
import net.caffeinemc.mods.sodium.client.gpu.device.context.VKDrawContext;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Binds Sodium's Vulkan draw context to LavaFlow's command buffer and pipeline layout.
 *
 * <p>Sodium reads both handles out of Minecraft's own Vulkan render pass. When the pass belongs to
 * LavaFlow it is a different type, so the handles are taken from LavaFlow instead. Sodium binds the
 * pipeline through Blaze3D immediately before calling this, so the layout is already available.
 */
@Mixin(VKDrawContext.class)
abstract class VKDrawContextMixin {
    @Shadow protected VkCommandBuffer cmdBuf;
    @Shadow protected long layout;

    @Inject(method = "setContext", at = @At("HEAD"), cancellable = true)
    private void lavaflow$bindPass(RenderPass pass, RenderPipeline pipeline, CallbackInfo callback) {
        LavaFlowVulkanPass vulkanPass = LavaFlowSodium.asLavaFlowPass(pass);
        if (vulkanPass == null) return;
        ((DrawContextPassAccessor) this).lavaflow$setPass(pass);
        this.layout = vulkanPass.lavaflowPipelineLayout();
        this.cmdBuf = vulkanPass.lavaflowCommandBuffer();
        callback.cancel();
    }
}
