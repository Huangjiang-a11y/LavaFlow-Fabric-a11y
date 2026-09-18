package dev.lavaflow.minecraft.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkExtent3D;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;

/** LavaFlow-owned 2D, array, or cube-compatible Vulkan image. */
final class LavaFlowGpuTexture implements GpuTexture {
    private final LavaFlowDevice device;
    private final LavaFlowVulkanContext context;
    // 26.3 turned GpuTexture into an interface, so the fields its abstract base class used to hold
    // for us have to live here. The constructor parameter list is exactly that former super(...) call.
    private final int usage;
    private final String label;
    private final GpuFormat format;
    private final int width;
    private final int height;
    private final int depthOrLayers;
    private final int mipLevels;
    private final long image;
    private final long memory;
    private int views;
    private int layout = VK_IMAGE_LAYOUT_UNDEFINED;
    private boolean closed;
    private boolean destroyed;

    LavaFlowGpuTexture(LavaFlowDevice device, int usage, String label, GpuFormat format,
                       int width, int height, int depthOrLayers, int mipLevels) {
        if (width <= 0 || height <= 0 || depthOrLayers <= 0 || mipLevels <= 0)
            throw new IllegalArgumentException("Texture dimensions and mip count must be positive");
        this.usage = usage;
        this.label = label;
        this.format = format;
        this.width = width;
        this.height = height;
        this.depthOrLayers = depthOrLayers;
        this.mipLevels = mipLevels;
        this.device = device;
        this.context = device.context();
        long createdImage = NULL;
        long allocatedMemory = NULL;
        try (MemoryStack stack = stackPush()) {
            VkExtent3D extent = VkExtent3D.calloc(stack).set(width, height, 1);
            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack).sType$Default()
                    .flags((usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0 ? VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT : 0)
                    .imageType(VK_IMAGE_TYPE_2D).format(LavaFlowVk.format(format)).extent(extent)
                    .mipLevels(mipLevels).arrayLayers(depthOrLayers).samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL).usage(LavaFlowVk.textureUsage(usage, format))
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            LongBuffer out = stack.mallocLong(1);
            check(vkCreateImage(context.device(), info, null, out), "vkCreateImage");
            createdImage = out.get(0);
            VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(context.device(), createdImage, requirements);
            VkMemoryAllocateInfo allocation = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .allocationSize(requirements.size()).memoryTypeIndex(context.findMemoryType(
                            requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            check(vkAllocateMemory(context.device(), allocation, null, out), "vkAllocateMemory(image)");
            allocatedMemory = out.get(0);
            check(vkBindImageMemory(context.device(), createdImage, allocatedMemory, 0), "vkBindImageMemory");
        } catch (Throwable failure) {
            if (allocatedMemory != NULL) vkFreeMemory(context.device(), allocatedMemory, null);
            if (createdImage != NULL) vkDestroyImage(context.device(), createdImage, null);
            throw failure;
        }
        image = createdImage;
        memory = allocatedMemory;
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) throw new IllegalStateException(operation + " failed with VkResult " + result);
    }

    // The getters below mirror Minecraft's own GpuTexture implementation, which LavaFlow used to
    // inherit: getWidth/getHeight shift the base dimension down by the mip level.
    @Override public int getWidth(int mipLevel) { return width >> mipLevel; }
    @Override public int getHeight(int mipLevel) { return height >> mipLevel; }
    @Override public int getDepthOrLayers() { return depthOrLayers; }
    @Override public int getMipLevels() { return mipLevels; }
    @Override public GpuFormat getFormat() { return format; }
    @Override public int usage() { return usage; }
    @Override public String getLabel() { return label; }

    long handle() { return image; }
    int layout() { return layout; }
    void layout(int value) { layout = value; }

    synchronized void retainView() {
        if (destroyed) throw new IllegalStateException("Texture is destroyed");
        views++;
    }

    synchronized void releaseView() {
        if (--views < 0) throw new IllegalStateException("Texture view reference underflow");
        destroyIfUnreferenced();
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        destroyIfUnreferenced();
    }

    private void destroyIfUnreferenced() {
        if (!closed || views != 0 || destroyed) return;
        destroyed = true;
        device.defer(this::destroyNow);
    }

    private void destroyNow() {
        vkDestroyImage(context.device(), image, null);
        vkFreeMemory(context.device(), memory, null);
    }

    @Override public synchronized boolean isClosed() { return closed; }
}
