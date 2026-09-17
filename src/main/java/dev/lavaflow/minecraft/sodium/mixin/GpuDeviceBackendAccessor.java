package dev.lavaflow.minecraft.sodium.mixin;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the backend behind the Blaze3D device facade so LavaFlow can recognize its own device. */
@Mixin(GpuDevice.class)
public interface GpuDeviceBackendAccessor {
    @Accessor("backend")
    GpuDeviceBackend lavaflow$backend();
}
