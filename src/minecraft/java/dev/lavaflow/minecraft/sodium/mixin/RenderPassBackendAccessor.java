package dev.lavaflow.minecraft.sodium.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the backend behind the Blaze3D render pass facade. */
@Mixin(RenderPass.class)
public interface RenderPassBackendAccessor {
    @Accessor("backend")
    RenderPassBackend lavaflow$backend();
}
