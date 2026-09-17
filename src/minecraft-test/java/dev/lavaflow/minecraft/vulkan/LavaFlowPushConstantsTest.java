package dev.lavaflow.minecraft.vulkan;

import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests LavaFlowPushConstants in isolation.
 *
 * LavaFlowPushConstants holds a static volatile provider field, so each test
 * resets it in @AfterEach to prevent cross-test state pollution.
 */
class LavaFlowPushConstantsTest {
    // RenderPipeline.MAX_VERTEX_ELEMENTS is 16; the constructor arraycopy requires exactly that count.
    private static final RenderPipeline DUMMY = new RenderPipeline(
            null, null, null, null, List.of(),
            new ColorTargetState[0], null, PolygonMode.FILL, false,
            new VertexFormat[16], PrimitiveTopology.TRIANGLES, 0
    ) {};

    @AfterEach
    void resetProvider() {
        LavaFlowPushConstants.setProvider(null);
    }

    @Test void sizeFor_nullPipeline_returnsZero() {
        LavaFlowPushConstants.setProvider(_ -> 42);
        assertEquals(0, LavaFlowPushConstants.sizeFor(null));
    }

    @Test void sizeFor_nullProvider_returnsZero() {
        // No setProvider call — provider is null from @AfterEach reset.
        assertEquals(0, LavaFlowPushConstants.sizeFor(DUMMY));
    }

    @Test void sizeFor_providerReturnPositive_delegatesValue() {
        LavaFlowPushConstants.setProvider(_ -> 20);
        assertEquals(20, LavaFlowPushConstants.sizeFor(DUMMY));
    }

    @Test void sizeFor_providerReturnNegative_clampedToZero() {
        LavaFlowPushConstants.setProvider(_ -> -8);
        assertEquals(0, LavaFlowPushConstants.sizeFor(DUMMY));
    }

    @Test void sizeFor_providerReturnZero_returnsZero() {
        LavaFlowPushConstants.setProvider(_ -> 0);
        assertEquals(0, LavaFlowPushConstants.sizeFor(DUMMY));
    }
}
