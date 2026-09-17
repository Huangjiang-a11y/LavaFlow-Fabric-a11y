package dev.lavaflow.minecraft.sodium.mixin;

import com.mojang.blaze3d.systems.RenderPass;
import net.caffeinemc.mods.sodium.client.gpu.device.context.DrawContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Writes the render pass field Sodium's draw contexts inherit from {@code DrawContext}.
 *
 * <p>The field is declared on the superclass, which {@code @Shadow} does not resolve from a mixin
 * targeting the subclass, so it is reached through an accessor instead.
 */
@Mixin(DrawContext.class)
public interface DrawContextPassAccessor {
    @Accessor("pass")
    void lavaflow$setPass(RenderPass pass);
}
