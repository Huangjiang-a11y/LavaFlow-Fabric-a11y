package dev.lavaflow.minecraft.sodium;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import dev.lavaflow.minecraft.sodium.mixin.GpuDeviceBackendAccessor;
import dev.lavaflow.minecraft.vulkan.LavaFlowDevice;
import net.caffeinemc.mods.sodium.client.gpu.device.backend.DrawBackend;

/**
 * Sodium compatibility support.
 *
 * <p>Sodium selects its terrain backend by testing the current Blaze3D device's backend for Minecraft's
 * own {@code VulkanDevice}. LavaFlow is a different backend, so Sodium would otherwise fall back to its
 * OpenGL path. This class supplies the one fact Sodium cannot determine for itself: whether the device
 * it is running on is LavaFlow's.
 *
 * <p>Nothing else is required. Sodium 0.9.2 issues its terrain draws through the Blaze3D render-pass API
 * ({@code multiDrawIndexed} / {@code drawIndexedIndirect}) and declares its own push-constant size on the
 * pipeline it builds, so it never needs a Vulkan command buffer, pipeline layout or descriptor set from
 * the backend. Earlier Sodium releases did, which is why the compatibility layer used to hand those over
 * and reserve a push-constant range; both mechanisms are gone in 26.3 and have been removed here.
 */
public final class LavaFlowSodium {
    private static final System.Logger LOGGER = System.getLogger(LavaFlowSodium.class.getName());

    private LavaFlowSodium() {}

    /**
     * Returns whether Blaze3D is currently driving the LavaFlow backend.
     *
     * <p>Resolved by reading the backend out of the frontend device rather than by identity against the
     * device LavaFlow created, because the object Sodium sees is the frontend facade, not LavaFlow's own.
     */
    public static boolean isLavaFlowDevice() {
        GpuDevice device = RenderSystem.getDevice();
        if (device == null) return false;
        if (!(device instanceof GpuDeviceBackendAccessor accessor)) {
            // The accessor mixin did not apply, so LavaFlow cannot be recognised. Reporting false hands
            // Sodium its OpenGL path, which cannot work on a Vulkan-only device -- so say why, loudly,
            // instead of letting it fail somewhere unrelated.
            LOGGER.log(System.Logger.Level.WARNING,
                    "GpuDeviceBackendAccessor did not apply; Sodium cannot recognise the LavaFlow device");
            return false;
        }
        return accessor.lavaflow$backend() instanceof LavaFlowDevice;
    }

    /**
     * Picks the Sodium draw path for LavaFlow.
     *
     * <p>Prefers the interleaved multi-draw path, which packs draws into a plain CPU array. The indirect
     * path would instead route them through a mapped indirect-parameter buffer, costing a per-region copy
     * into host-visible memory and a GPU parameter fetch per draw.
     */
    public static DrawBackend drawBackend() {
        GpuDevice device = RenderSystem.getDevice();
        if (device == null) return DrawBackend.VK_INDIRECT;
        boolean interleaved = device.getDeviceInfo().features().multiDrawDirectInterleaved();
        DrawBackend backend = interleaved ? DrawBackend.VK_MULTIDRAW : DrawBackend.VK_INDIRECT;
        LOGGER.log(System.Logger.Level.INFO, "Sodium draw path: {0}", backend);
        return backend;
    }
}
