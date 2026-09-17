package dev.lavaflow.minecraft.sodium;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.lavaflow.minecraft.sodium.mixin.GpuDeviceBackendAccessor;
import dev.lavaflow.minecraft.sodium.mixin.RenderPassBackendAccessor;
import dev.lavaflow.minecraft.vulkan.LavaFlowDevice;
import dev.lavaflow.minecraft.vulkan.LavaFlowPushConstants;
import dev.lavaflow.minecraft.vulkan.LavaFlowVulkanPass;
import net.caffeinemc.mods.sodium.client.gpu.device.backend.DrawBackend;
import net.caffeinemc.mods.sodium.client.gpu.device.context.DrawContext;

/**
 * Sodium compatibility support.
 *
 * <p>Sodium already has a Vulkan terrain path, but selects it by testing the Blaze3D device backend
 * against Minecraft's own {@code VulkanDevice}. LavaFlow is a different backend, so Sodium would
 * otherwise fall back to its OpenGL path. This class supplies the two facts Sodium's Vulkan path
 * needs from a foreign backend: whether the current device is LavaFlow, and the Vulkan handles of a
 * LavaFlow render pass.
 */
public final class LavaFlowSodium {
    private static final System.Logger LOGGER = System.getLogger(LavaFlowSodium.class.getName());
    private static volatile boolean installed;

    private LavaFlowSodium() {}

    /** Returns whether Blaze3D is currently driving the LavaFlow backend. */
    public static boolean isLavaFlowDevice() {
        GpuDevice device = RenderSystem.getDevice();
        if (device == null) return false;
        return ((GpuDeviceBackendAccessor) device).lavaflow$backend() instanceof LavaFlowDevice;
    }

    /**
     * Reserves the push-constant range Sodium's terrain shaders declare.
     *
     * <p>Sodium's chunk shaders take the region offset, section time, and region id through a
     * {@code push_constant} block when compiled for Vulkan. Minecraft's own backend reserves that
     * range with a mixin on its pipeline compiler; LavaFlow reserves it through its push-constant
     * provider instead. Called during Sodium's backend selection, which happens when the chunk
     * renderer is constructed and therefore before any Sodium pipeline is compiled.
     */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        int range = DrawContext.PUSH_CONSTANT_RANGE;
        LavaFlowPushConstants.setProvider(pipeline ->
                pipeline.getLocation().getNamespace().contains("sodium") ? range : 0);
        LOGGER.log(System.Logger.Level.INFO,
                "Sodium compatibility active: reserving {0} push-constant bytes for Sodium pipelines", range);
    }

    /**
     * Picks the Sodium draw path for LavaFlow.
     *
     * <p>Prefers the interleaved multi-draw path, which packs draws into a plain CPU array. The
     * indirect path would instead route them through a mapped indirect-parameter buffer, costing a
     * per-region copy into host-visible memory and a GPU parameter fetch per draw. Falls back to the
     * indirect path if LavaFlow reports the interleaved capability as unavailable.
     */
    public static DrawBackend drawBackend() {
        GpuDevice device = RenderSystem.getDevice();
        if (device == null) return DrawBackend.VK_INDIRECT;
        boolean interleaved = device.getDeviceInfo().features().multiDrawDirectInterleaved();
        DrawBackend backend = interleaved ? DrawBackend.VK_MULTIDRAW : DrawBackend.VK_INDIRECT;
        LOGGER.log(System.Logger.Level.INFO, "Sodium draw path: {0}", backend);
        return backend;
    }

    /**
     * Returns the Vulkan handles of {@code pass}, or {@code null} when it is not a LavaFlow pass.
     */
    public static LavaFlowVulkanPass asLavaFlowPass(RenderPass pass) {
        if (pass == null) return null;
        RenderPassBackend backend = ((RenderPassBackendAccessor) pass).lavaflow$backend();
        return backend instanceof LavaFlowVulkanPass vulkanPass ? vulkanPass : null;
    }
}
