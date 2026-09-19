package dev.lavaflow.minecraft.mixin;

import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the backend behind Blaze3D's device facade so LavaFlow can recognise its own device.
 *
 * <p>The field lives on the concrete {@code FrontendGpuDevice}; {@code GpuDevice} is the interface
 * callers see, and an accessor cannot resolve a field through it. Targeting the interface found nothing
 * to read and left the accessor method unimplemented, which surfaced only at runtime as an
 * {@code AbstractMethodError} the first time Sodium asked which device it was running on.
 *
 * <p>Declared in the core mixin config rather than the Sodium one because it is not Sodium-specific:
 * the AsyncParticles guard needs the same answer, and the Sodium config is skipped entirely when Sodium
 * is not installed.
 */
@Mixin(FrontendGpuDevice.class)
public interface GpuDeviceBackendAccessor {
    @Accessor("backend")
    GpuDeviceBackend lavaflow$backend();
}
