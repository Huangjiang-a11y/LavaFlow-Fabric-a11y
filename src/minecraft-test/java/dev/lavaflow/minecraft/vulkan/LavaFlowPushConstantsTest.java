package dev.lavaflow.minecraft.vulkan;

import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests LavaFlowPushConstants in isolation.
 *
 * LavaFlowPushConstants holds a static volatile provider field, so each test
 * resets it in @AfterEach to prevent cross-test state pollution.
 */
class LavaFlowPushConstantsTest {
    /**
     * A minimal pipeline. Every provider in this class ignores its argument, so the tests only need a
     * non-null instance, and the public builder produces one without spelling out the twelve positional
     * constructor arguments.
     *
     * <p>The old positional call cannot be ported as-is: 26.3's constructor tolerates neither the nulls
     * nor the array shapes it used. The shader map is wrapped in {@code new EnumMap(shaders)} (null throws,
     * and an empty non-EnumMap throws "Specified map is empty"), the bind-group layouts go through
     * {@code List.copyOf} (null throws) and the two arrays through fastutil's {@code ReferenceArrayList}
     * (null throws). The builder starts from an empty {@code EnumMap}, so all of that is handled, and the
     * fixed {@code MAX_VERTEX_ELEMENTS == 16} array is gone entirely -- 26.3 takes whatever length the
     * vertex-format array has.
     */
    private static final RenderPipeline DUMMY = RenderPipeline.builder()
            // These take a bare path and resolve it in the "minecraft" namespace, so they must not carry a
            // "namespace:" prefix -- that colon would end up inside the path and fail validation.
            .withLocation("lavaflow_push_constants_test")
            .withVertexShader("lavaflow_push_constants_test_vertex")
            .withFragmentShader("lavaflow_push_constants_test_fragment")
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();

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
