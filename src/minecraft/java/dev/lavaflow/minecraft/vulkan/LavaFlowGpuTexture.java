package dev.lavaflow.minecraft.vulkan;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
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
final class LavaFlowGpuTexture extends GpuTexture {
    private final LavaFlowDevice device;
    private final LavaFlowVulkanContext context;
    private final long image;
    private final long memory;
    private int views;
    private int layout = VK_IMAGE_LAYOUT_UNDEFINED;
    private boolean closed;
    private boolean destroyed;
    // The one view the partial-clear path reuses. See cachedClearView.
    private LavaFlowGpuTextureView clearView;

    LavaFlowGpuTexture(LavaFlowDevice device, int usage, String label, GpuFormat format,
                       int width, int height, int depthOrLayers, int mipLevels) {
        super(usage, label, format, width, height, depthOrLayers, mipLevels);
        if (width <= 0 || height <= 0 || depthOrLayers <= 0 || mipLevels <= 0)
            throw new IllegalArgumentException("Texture dimensions and mip count must be positive");
        this.device = device;
        this.context = device.context();
        long createdImage = NULL;
        long allocatedMemory = NULL;
        try (MemoryStack stack = stackPush()) {
            VkExtent3D extent = VkExtent3D.calloc(stack).set(width, height, 1);
            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack).sType$Default()
                    .flags((usage & USAGE_CUBEMAP_COMPATIBLE) != 0 ? VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT : 0)
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

    long handle() { return image; }
    int layout() { return layout; }
    void layout(int value) { layout = value; }

    /**
     * The view the partial-clear path uses, created once instead of once per clear.
     *
     * <p>{@code clearColorAndDepthTextures} used to build two views per call and destroy them on the way
     * out. A 26.3 device run measured what that cost — 13.3 partial clears per frame against 26.6 retired
     * views per frame, exactly double, every one labeled "UI items atlas" or "UI items atlas depth" — and
     * this path is the same code on both branches. A view is an immutable handle; only the clear rectangle
     * differs between calls.
     *
     * <p>26.2 clears mip 0 only, so one view is enough here; 26.3 takes a mip per call and caches a map.
     *
     * <p>The cached view holds a view reference, which is why {@link #close()} releases it first: left
     * alone it would keep {@code views} above zero forever and the texture would never be destroyed.
     */
    synchronized LavaFlowGpuTextureView cachedClearView() {
        if (closed) throw new IllegalStateException("Texture is closed");
        if (clearView == null) clearView = new LavaFlowGpuTextureView(device, this, 0, getMipLevels());
        return clearView;
    }

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
        // Release the cached clear view first: it carries a view reference, and while one lives,
        // destroyIfUnreferenced can never see views == 0. View and image go out through the same
        // deferred batch in insertion order, so the view goes before the image it viewed.
        if (clearView != null) { clearView.close(); clearView = null; }
        destroyIfUnreferenced();
    }

    private void destroyIfUnreferenced() {
        if (!closed || views != 0 || destroyed) return;
        destroyed = true;
        LavaFlowFrameStats.textureRetired();
        device.defer(this::destroyNow);
    }

    /**
     * How this texture names itself in the churn counters: its label, or a description of it when it
     * has none. Never null, and that is the point — a texture without a label used to make the view
     * tally silently record nothing, which is the opposite of what a diagnostic is for.
     */
    String identity() {
        // 26.2 的 blaze3d 把 GpuTexture 的这些字段设为 private，只能走访问器（注意是 usage()）。
        if (getLabel() != null) return getLabel();
        return "[unlabeled " + getWidth(0) + "x" + getHeight(0) + " " + getFormat() + " mips=" + getMipLevels()
                + " layers=" + getDepthOrLayers() + " usage=0x" + Integer.toHexString(usage()) + "]";
    }

    private void destroyNow() {
        vkDestroyImage(context.device(), image, null);
        vkFreeMemory(context.device(), memory, null);
    }

    @Override public synchronized boolean isClosed() { return closed; }
}
