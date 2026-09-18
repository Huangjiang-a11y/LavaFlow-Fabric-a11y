package dev.lavaflow.minecraft.vulkan;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

final class LavaFlowGpuTextureView implements GpuTextureView {
    private final LavaFlowDevice device;
    private final LavaFlowVulkanContext context;
    private final LavaFlowGpuTexture texture;
    // 26.3 turned GpuTextureView into an interface; these were the former abstract base class fields.
    private final int baseMipLevel;
    private final int mipLevels;
    private final long view;
    private boolean closed;

    LavaFlowGpuTextureView(LavaFlowDevice device, LavaFlowGpuTexture texture, int baseMipLevel, int mipLevels) {
        if (baseMipLevel < 0 || mipLevels <= 0 || baseMipLevel + mipLevels > texture.getMipLevels())
            throw new IllegalArgumentException("Invalid texture view mip range");
        this.baseMipLevel = baseMipLevel;
        this.mipLevels = mipLevels;
        this.device = device;
        this.context = device.context();
        this.texture = texture;
        boolean cube = (texture.usage() & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;
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
    @Override public int baseMipLevel() { return baseMipLevel; }
    @Override public int mipLevels() { return mipLevels; }
    // Mirrors Minecraft's own GpuTextureView, which LavaFlow used to inherit: the requested mip is
    // relative to the view, so the view's base mip offset is added before asking the texture.
    @Override public int getWidth(int mipLevel) { return texture.getWidth(mipLevel + baseMipLevel); }
    @Override public int getHeight(int mipLevel) { return texture.getHeight(mipLevel + baseMipLevel); }
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
