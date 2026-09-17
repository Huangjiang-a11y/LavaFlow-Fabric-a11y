package dev.lavaflow.minecraft.sodium.mixin;

import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the backend behind the Blaze3D render pass facade. */
@Mixin(RenderPass.class)
public interface RenderPassBackendAccessor {
    @Accessor("backend")
    RenderPassBackend lavaflow$backend();
}
