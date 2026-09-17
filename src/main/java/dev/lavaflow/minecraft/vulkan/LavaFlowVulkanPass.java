package dev.lavaflow.minecraft.vulkan;

import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Vulkan handles of an in-flight LavaFlow render pass.
 *
 * <p>Exposed so a compatibility layer can record its own commands into the same command buffer that
 * LavaFlow is encoding, using the layout of the currently bound pipeline. Both handles are only
 * valid between {@code setPipeline} and the end of the pass.
 */
public interface LavaFlowVulkanPass {
    /** The command buffer this pass records into. */
    VkCommandBuffer lavaflowCommandBuffer();

    /** The pipeline layout of the currently bound pipeline, or {@code 0} if none is bound. */
    long lavaflowPipelineLayout();
}
