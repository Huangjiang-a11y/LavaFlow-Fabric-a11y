package net.caffeinemc.mods.sodium.client.gpu.device.context;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;

/**
 * Signature-only stub of Sodium's draw context. Sodium supplies the real class at runtime.
 *
 * <p>{@code PUSH_CONSTANT_RANGE} is deliberately declared non-final so that javac emits a field read
 * instead of inlining a constant. LavaFlow therefore always observes Sodium's actual range size.
 */
public abstract class DrawContext {
    protected RenderPass pass;

    public static int PUSH_CONSTANT_RANGE;

    public abstract void setContext(RenderPass pass, RenderPipeline pipeline);
}
