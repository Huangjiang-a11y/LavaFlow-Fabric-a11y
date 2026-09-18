package dev.lavaflow.minecraft.vulkan;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import java.util.function.ToIntFunction;

/**
 * Push-constant range sizing for pipelines that declare a {@code push_constant} block.
 *
 * <p>Blaze3D has no push-constant concept, so the range cannot be derived from the pipeline
 * description. A compatibility layer registers a provider that reports the byte size a given
 * pipeline needs; LavaFlow reserves that range in the pipeline layout. Pipelines with no provider
 * entry get no range, which keeps vanilla layouts unchanged.
 *
 * <p>Candidate for removal as of 26.3: the frontend now resolves the push-constant size into
 * {@code BackendRenderPipeline.CreateInfo.pushConstantsSize()} (from {@code RenderPipeline.pushConstantSize()})
 * and validates it against the shader's own reflection, so the backend no longer needs a provider of its
 * own. LavaFlowRenderPipeline reads the CreateInfo value. Left in place for now because the Sodium
 * compatibility layer still calls {@link #setProvider}, which is harmless but no longer consulted.
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
