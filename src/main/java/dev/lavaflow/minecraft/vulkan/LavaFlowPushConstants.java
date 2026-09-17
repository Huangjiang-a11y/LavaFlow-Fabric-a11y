package dev.lavaflow.minecraft.vulkan;

import com.mojang.blaze3d.pipeline.RenderPipeline;

import java.util.function.ToIntFunction;

/**
 * Push-constant range sizing for pipelines that declare a {@code push_constant} block.
 *
 * <p>Blaze3D has no push-constant concept, so the range cannot be derived from the pipeline
 * description. A compatibility layer registers a provider that reports the byte size a given
 * pipeline needs; LavaFlow reserves that range in the pipeline layout. Pipelines with no provider
 * entry get no range, which keeps vanilla layouts unchanged.
 */
public final class LavaFlowPushConstants {
    private static volatile ToIntFunction<RenderPipeline> provider;

    private LavaFlowPushConstants() {}

    /** Installs the provider consulted for every pipeline layout LavaFlow creates. */
    public static void setProvider(ToIntFunction<RenderPipeline> newProvider) {
        provider = newProvider;
    }

    /** Returns the push-constant byte size {@code pipeline} needs, or {@code 0} for none. */
    public static int sizeFor(RenderPipeline pipeline) {
        ToIntFunction<RenderPipeline> current = provider;
        if (current == null || pipeline == null) return 0;
        int size = current.applyAsInt(pipeline);
        return Math.max(size, 0);
    }
}
