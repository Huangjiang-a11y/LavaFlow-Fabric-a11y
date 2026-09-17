package dev.lavaflow.minecraft.vulkan;

import com.mojang.blaze3d.textures.GpuTextureView;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

final class LavaFlowGpuTextureView extends GpuTextureView {
    private final LavaFlowDevice device;
    private final LavaFlowVulkanContext context;
    private final LavaFlowGpuTexture texture;
    private final long view;
    private boolean closed;

    LavaFlowGpuTextureView(LavaFlowDevice device, LavaFlowGpuTexture texture, int baseMipLevel, int mipLevels) {
        super(texture, baseMipLevel, mipLevels);
        if (baseMipLevel < 0 || mipLevels <= 0 || baseMipLevel + mipLevels > texture.getMipLevels())
            throw new IllegalArgumentException("Invalid texture view mip range");
        this.device = device;
        this.context = device.context();
        this.texture = texture;
        boolean cube = (texture.usage() & LavaFlowGpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;
        int layers = cube ? 6 : texture.getDepthOrLayers();
        try (MemoryStack stack = stackPush()) {
            VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(texture.handle())
                    .viewType(cube ? VK_IMAGE_VIEW_TYPE_CUBE : layers > 1 ? VK_IMAGE_VIEW_TYPE_2D_ARRAY : VK_IMAGE_VIEW_TYPE_2D)
                    .format(LavaFlowVk.format(texture.getFormat()));
            info.subresourceRange().aspectMask(LavaFlowVk.aspect(texture.getFormat()))
                    .baseMipLevel(baseMipLevel).levelCount(mipLevels).baseArrayLayer(0).layerCount(layers);
            LongBuffer out = stack.mallocLong(1);
            int result = vkCreateImageView(context.device(), info, null, out);
            if (result != VK_SUCCESS) throw new IllegalStateException("vkCreateImageView failed with VkResult " + result);
            view = out.get(0);
        }
        texture.retainView();
    }

    long handle() { return view; }
    @Override public LavaFlowGpuTexture texture() { return texture; }
    @Override public synchronized boolean isClosed() { return closed; }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        device.invalidateDescriptorCache(view);
        device.defer(() -> {
            context.releaseLegacyFramebuffers(view);
            vkDestroyImageView(context.device(), view, null);
        });
        texture.releaseView();
    }
}
