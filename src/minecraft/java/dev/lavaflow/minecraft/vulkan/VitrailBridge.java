package dev.lavaflow.minecraft.vulkan;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * The native handles Vitrail Shaders reads out of the backend it is standing on.
 *
 * <p>Vitrail is written against Mojang's own Vulkan backend and reaches past the render API for
 * what that backend alone can give it: the {@code VkDevice}, the command buffer being recorded
 * into, and the raw handles of buffers, samplers and image views. On LavaFlow none of the classes
 * those handles are read from are the ones it asks for -- the backend is {@link LavaFlowDevice},
 * not {@code VulkanDevice} -- so every one of its {@code instanceof} checks answers no and its
 * native paths retire locally while the engine as a whole is already on.
 *
 * <p>This class answers those questions with LavaFlow's handles instead, from the same package as
 * the classes that keep them, and is reached by reflection from Vitrail rather than compiled
 * against. The names here are therefore a small external contract: rename one and Vitrail retires
 * the path again instead of failing, which is the direction to be wrong in.
 *
 * <p>Nothing here allocates, submits or records; it reads fields the backend already keeps.
 */
public final class VitrailBridge {
    private VitrailBridge() {}

    /** Whether this backend is LavaFlow's, which is what decides the answer of every method here. */
    public static boolean isLavaFlow(GpuDeviceBackend backend) {
        return backend instanceof LavaFlowDevice;
    }

    /** The {@code VkDevice}, or 0 when the backend is not LavaFlow's. */
    public static VkDevice vkDevice(GpuDeviceBackend backend) {
        return backend instanceof LavaFlowDevice device ? device.context().device() : null;
    }

    /** The {@code VkInstance}, or null when the backend is not LavaFlow's. */
    public static VkInstance vkInstance(GpuDeviceBackend backend) {
        return backend instanceof LavaFlowDevice device ? device.context().instance() : null;
    }

    /** The {@code VkPhysicalDevice}, or null when the backend is not LavaFlow's. */
    public static VkPhysicalDevice vkPhysicalDevice(GpuDeviceBackend backend) {
        return backend instanceof LavaFlowDevice device ? device.context().physicalDevice() : null;
    }

    /** The command buffer the encoder is recording into, or null when the encoder is not LavaFlow's. */
    public static VkCommandBuffer commandBuffer(CommandEncoderBackend encoder) {
        return encoder instanceof LavaFlowCommandEncoder lavaflow ? lavaflow.commandBuffer() : null;
    }

    /** The {@code VkBuffer} behind a buffer LavaFlow made, or 0 for any other buffer. */
    public static long bufferHandle(GpuBuffer buffer) {
        return buffer instanceof LavaFlowGpuBuffer lavaflow ? lavaflow.handle() : 0L;
    }

    /** The {@code VkImage} behind a texture LavaFlow made, or 0 for any other texture. */
    public static long imageHandle(GpuTexture texture) {
        return texture instanceof LavaFlowGpuTexture lavaflow ? lavaflow.handle() : 0L;
    }

    /** The {@code VkImageView} behind a view LavaFlow made, or 0 for any other view. */
    public static long imageViewHandle(GpuTextureView view) {
        return view instanceof LavaFlowGpuTextureView lavaflow ? lavaflow.handle() : 0L;
    }

    /** The {@code VkSampler} behind a sampler LavaFlow made, or 0 for any other sampler. */
    public static long samplerHandle(GpuSampler sampler) {
        return sampler instanceof LavaFlowGpuSampler lavaflow ? lavaflow.handle() : 0L;
    }
    /**
     * Creates a buffer for a pack's own storage, with memory bound to it.
     *
     * <p>Vitrail asks VMA for these on the game's own backend, which LavaFlow does not keep. The
     * resources a pack declares are therefore made here on the same terms as the backend's own
     * buffers: the usage the caller names, and device-local memory, which is what a storage buffer
     * written and read on the device wants.
     *
     * @return {@code {VkBuffer, VkDeviceMemory}}, or null when the backend is not LavaFlow's or the
     *         driver refused the allocation, which retires the caller's storage path for this load
     */
    public static long[] createBuffer(GpuDeviceBackend backend, long size, int usage) {
        if (!(backend instanceof LavaFlowDevice device)) return null;
        LavaFlowVulkanContext context = device.context();
        long buffer = 0L;
        long memory = 0L;
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size(size).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer out = stack.mallocLong(1);
            if (vkCreateBuffer(context.device(), info, null, out) != VK_SUCCESS) return null;
            buffer = out.get(0);
            VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(context.device(), buffer, requirements);
            int type;
            try {
                type = context.findMemoryType(requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            } catch (RuntimeException none) {
                vkDestroyBuffer(context.device(), buffer, null);
                return null;
            }
            VkMemoryAllocateInfo allocation = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .allocationSize(requirements.size()).memoryTypeIndex(type);
            if (vkAllocateMemory(context.device(), allocation, null, out) != VK_SUCCESS) {
                vkDestroyBuffer(context.device(), buffer, null);
                return null;
            }
            memory = out.get(0);
            if (vkBindBufferMemory(context.device(), buffer, memory, 0) != VK_SUCCESS) {
                vkFreeMemory(context.device(), memory, null);
                vkDestroyBuffer(context.device(), buffer, null);
                return null;
            }
        }
        return new long[]{buffer, memory};
    }

    /** Frees a buffer {@link #createBuffer} made. Both handles may be 0. */
    public static void destroyBuffer(GpuDeviceBackend backend, long buffer, long memory) {
        if (!(backend instanceof LavaFlowDevice device)) return;
        VkDevice vkDevice = device.context().device();
        if (buffer != 0L) vkDestroyBuffer(vkDevice, buffer, null);
        if (memory != 0L) vkFreeMemory(vkDevice, memory, null);
    }

    /**
     * Creates an image for a pack's own storage, with memory bound to it.
     *
     * @return {@code {VkImage, VkDeviceMemory}}, or null when the backend is not LavaFlow's or the
     *         driver refused the allocation
     */
    public static long[] createImage(GpuDeviceBackend backend, int format, int width, int height,
            int mipLevels, int arrayLayers, int samples, int tiling, int usage, int initialLayout) {
        if (!(backend instanceof LavaFlowDevice device)) return null;
        LavaFlowVulkanContext context = device.context();
        long image = 0L;
        long memory = 0L;
        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D).format(format)
                    .extent(e -> e.width(width).height(height).depth(1))
                    .mipLevels(mipLevels).arrayLayers(arrayLayers).samples(samples)
                    .tiling(tiling).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(initialLayout);
            LongBuffer out = stack.mallocLong(1);
            if (vkCreateImage(context.device(), info, null, out) != VK_SUCCESS) return null;
            image = out.get(0);
            VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(context.device(), image, requirements);
            int type;
            try {
                type = context.findMemoryType(requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            } catch (RuntimeException none) {
                vkDestroyImage(context.device(), image, null);
                return null;
            }
            VkMemoryAllocateInfo allocation = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .allocationSize(requirements.size()).memoryTypeIndex(type);
            if (vkAllocateMemory(context.device(), allocation, null, out) != VK_SUCCESS) {
                vkDestroyImage(context.device(), image, null);
                return null;
            }
            memory = out.get(0);
            if (vkBindImageMemory(context.device(), image, memory, 0) != VK_SUCCESS) {
                vkFreeMemory(context.device(), memory, null);
                vkDestroyImage(context.device(), image, null);
                return null;
            }
        }
        return new long[]{image, memory};
    }

    /** Frees an image {@link #createImage} made. Both handles may be 0. */
    public static void destroyImage(GpuDeviceBackend backend, long image, long memory) {
        if (!(backend instanceof LavaFlowDevice device)) return;
        VkDevice vkDevice = device.context().device();
        if (image != 0L) vkDestroyImage(vkDevice, image, null);
        if (memory != 0L) vkFreeMemory(vkDevice, memory, null);
    }
}
