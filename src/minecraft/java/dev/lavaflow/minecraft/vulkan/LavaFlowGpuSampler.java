package dev.lavaflow.minecraft.vulkan;

import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.LongBuffer;
import java.util.OptionalDouble;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

final class LavaFlowGpuSampler extends GpuSampler {
    private final LavaFlowDevice device;
    private final LavaFlowVulkanContext context;
    private final AddressMode addressModeU;
    private final AddressMode addressModeV;
    private final FilterMode minFilter;
    private final FilterMode magFilter;
    private final int maxAnisotropy;
    private final OptionalDouble maxLod;
    private final long sampler;
    private boolean closed;

    LavaFlowGpuSampler(LavaFlowDevice device, AddressMode addressModeU, AddressMode addressModeV,
                       FilterMode minFilter, FilterMode magFilter, int maxAnisotropy, OptionalDouble maxLod) {
        this.device = device; this.context = device.context(); this.addressModeU = addressModeU; this.addressModeV = addressModeV;
        this.minFilter = minFilter; this.magFilter = magFilter; this.maxAnisotropy = maxAnisotropy; this.maxLod = maxLod;
        try (MemoryStack stack = stackPush()) {
            // Some mobile drivers (Mali) return flickering/corrupt samples for scaled textures when
            // anisotropic filtering or high-LOD mip sampling is involved, while flat (mip 0) sampling
            // is fine -- which surfaces as intermittent corruption of icons, the sun/moon, the main
            // menu panorama and other small/scaled textures. -Dlavaflow.disableTextureLods=true pins
            // every sampler to mip 0 with anisotropy off to confirm the cause.
            double lod = maxLod.orElse(1000.0);
            int anisotropy = maxAnisotropy;
            if (Boolean.getBoolean("lavaflow.disableTextureLods")) {
                lod = 0.25;
                anisotropy = 1;
            }
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(LavaFlowVk.filter(magFilter)).minFilter(LavaFlowVk.filter(minFilter))
                    .mipmapMode(lod > 0.25 ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(LavaFlowVk.addressMode(addressModeU)).addressModeV(LavaFlowVk.addressMode(addressModeV))
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).mipLodBias(0)
                    .minLod(0).maxLod((float)Math.max(0.25, lod))
                    .anisotropyEnable(anisotropy > 1).maxAnisotropy(Math.max(1, anisotropy));
            LongBuffer out = stack.mallocLong(1);
            int result = vkCreateSampler(context.device(), info, null, out);
            if (result != VK_SUCCESS) throw new IllegalStateException("vkCreateSampler failed with VkResult " + result);
            sampler = out.get(0);
        }
    }

    long handle() { return sampler; }
    @Override public AddressMode getAddressModeU() { return addressModeU; }
    @Override public AddressMode getAddressModeV() { return addressModeV; }
    @Override public FilterMode getMinFilter() { return minFilter; }
    @Override public FilterMode getMagFilter() { return magFilter; }
    @Override public int getMaxAnisotropy() { return maxAnisotropy; }
    @Override public OptionalDouble getMaxLod() { return maxLod; }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        device.invalidateDescriptorCache(sampler);
        device.defer(() -> vkDestroySampler(context.device(), sampler, null));
    }
}
