package dev.lavaflow.minecraft.vulkan;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;

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
}
