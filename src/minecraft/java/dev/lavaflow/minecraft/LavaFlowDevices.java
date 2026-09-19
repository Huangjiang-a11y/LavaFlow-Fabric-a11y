package dev.lavaflow.minecraft;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import dev.lavaflow.minecraft.mixin.GpuDeviceBackendAccessor;
import dev.lavaflow.minecraft.vulkan.LavaFlowDevice;

/**
 * Answers "is the active Blaze3D device LavaFlow's?" for mods that inspect the backend behind it.
 *
 * <p>This has to go through the {@link GpuDeviceBackendAccessor} mixin rather than reflection: the
 * {@code backend} field is declared on the concrete {@code FrontendGpuDevice}, and {@code GpuDevice} is
 * a pure interface with no fields. A reflective {@code GpuDevice.class.getDeclaredField("backend")}
 * therefore throws {@code NoSuchFieldException} on every call, and any caller that swallows that
 * exception silently concludes the wrong thing — which is exactly how the AsyncParticles guard came to
 * be a no-op that let the very crash it was written to prevent happen anyway.
 *
 * <p>Both callers (the Sodium draw-backend selection and the AsyncParticles guard) need this answer, so
 * it lives here once instead of being re-derived per mod.
 */
public final class LavaFlowDevices {
    private static final System.Logger LOGGER = System.getLogger(LavaFlowDevices.class.getName());

    private LavaFlowDevices() {}

    /**
     * Returns the backend behind {@code device}, or {@code null} when it cannot be read.
     *
     * <p>A {@code null} result means the accessor mixin did not apply, which is a LavaFlow defect rather
     * than an expected state, so it is logged. Callers must treat {@code null} as "unknown", never as
     * "not LavaFlow", because the two call for different fallbacks.
     */
    public static GpuDeviceBackend backendOf(GpuDevice device) {
        if (device == null) return null;
        if (!(device instanceof GpuDeviceBackendAccessor accessor)) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "GpuDeviceBackendAccessor did not apply to {0}; backend lookups will report unknown",
                    device.getClass().getName());
            return null;
        }
        return accessor.lavaflow$backend();
    }

    /**
     * Returns whether {@code device} is driven by LavaFlow's own Vulkan backend.
     *
     * <p>False both for Mojang's real Vulkan device and when the backend cannot be read; use
     * {@link #backendOf} when the distinction matters.
     */
    public static boolean isLavaFlow(GpuDevice device) {
        return backendOf(device) instanceof LavaFlowDevice;
    }

    /** Convenience for the common case: the device Minecraft is currently rendering with. */
    public static boolean isCurrentDeviceLavaFlow() {
        return isLavaFlow(RenderSystem.getDevice());
    }
}
