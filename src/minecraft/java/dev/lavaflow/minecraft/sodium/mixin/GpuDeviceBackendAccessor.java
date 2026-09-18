package dev.lavaflow.minecraft.sodium.mixin;

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
 */
@Mixin(FrontendGpuDevice.class)
public interface GpuDeviceBackendAccessor {
    @Accessor("backend")
    GpuDeviceBackend lavaflow$backend();
}
