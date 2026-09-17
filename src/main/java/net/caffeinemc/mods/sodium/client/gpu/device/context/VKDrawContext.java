package net.caffeinemc.mods.sodium.client.gpu.device.context;

import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Signature-only stub of Sodium's Vulkan draw context. Sodium supplies the real class at runtime.
 */
public abstract class VKDrawContext extends DrawContext {
    protected VkCommandBuffer cmdBuf;
    protected long layout;
}
