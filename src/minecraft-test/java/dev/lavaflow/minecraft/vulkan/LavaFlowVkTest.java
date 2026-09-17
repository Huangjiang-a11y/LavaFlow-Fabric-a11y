package dev.lavaflow.minecraft.vulkan;

import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.vulkan.VK10.*;

class LavaFlowVkTest {

    // bufferUsage: bit-flag remapping, no GPU dependency

    @Test void bufferUsage_zeroBitsYieldsTransferSrc() {
        assertEquals(VK_BUFFER_USAGE_TRANSFER_SRC_BIT, LavaFlowVk.bufferUsage(0));
    }

    @Test void bufferUsage_copyDst() {
        assertEquals(VK_BUFFER_USAGE_TRANSFER_DST_BIT, LavaFlowVk.bufferUsage(8));
    }

    @Test void bufferUsage_vertexAndIndex() {
        int expected = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
        assertEquals(expected, LavaFlowVk.bufferUsage(32 | 64));
    }

    @Test void bufferUsage_uniformAndIndirect() {
        int expected = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
        assertEquals(expected, LavaFlowVk.bufferUsage(128 | 512));
    }

    // topology: QUADS and TRIANGLES both collapse to TRIANGLE_LIST (non-obvious)

    @Test void topology_linesYieldsLineList() {
        assertEquals(VK_PRIMITIVE_TOPOLOGY_LINE_LIST, LavaFlowVk.topology(PrimitiveTopology.LINES));
    }

    @Test void topology_trianglesYieldsTriangleList() {
        assertEquals(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, LavaFlowVk.topology(PrimitiveTopology.TRIANGLES));
    }

    @Test void topology_quadsCollapseToTriangleList() {
        // QUADS is emulated as indexed triangles; Sodium relies on this matching TRIANGLES.
        assertEquals(LavaFlowVk.topology(PrimitiveTopology.TRIANGLES),
                     LavaFlowVk.topology(PrimitiveTopology.QUADS));
    }

    @Test void topology_debugLineStripYieldsLineStrip() {
        assertEquals(VK_PRIMITIVE_TOPOLOGY_LINE_STRIP, LavaFlowVk.topology(PrimitiveTopology.DEBUG_LINE_STRIP));
    }

    @Test void topology_triangleFan() {
        assertEquals(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN, LavaFlowVk.topology(PrimitiveTopology.TRIANGLE_FAN));
    }

    // blendOp: exhaustive enum coverage

    @Test void blendOp_add() { assertEquals(VK_BLEND_OP_ADD, LavaFlowVk.blendOp(BlendOp.ADD)); }
    @Test void blendOp_subtract() { assertEquals(VK_BLEND_OP_SUBTRACT, LavaFlowVk.blendOp(BlendOp.SUBTRACT)); }
    @Test void blendOp_reverseSubtract() { assertEquals(VK_BLEND_OP_REVERSE_SUBTRACT, LavaFlowVk.blendOp(BlendOp.REVERSE_SUBTRACT)); }
    @Test void blendOp_min() { assertEquals(VK_BLEND_OP_MIN, LavaFlowVk.blendOp(BlendOp.MIN)); }
    @Test void blendOp_max() { assertEquals(VK_BLEND_OP_MAX, LavaFlowVk.blendOp(BlendOp.MAX)); }

    // blendFactor: spot-check a few cases, including the non-trivially named ones

    @Test void blendFactor_srcAlpha() { assertEquals(VK_BLEND_FACTOR_SRC_ALPHA, LavaFlowVk.blendFactor(BlendFactor.SRC_ALPHA)); }
    @Test void blendFactor_one() { assertEquals(VK_BLEND_FACTOR_ONE, LavaFlowVk.blendFactor(BlendFactor.ONE)); }
    @Test void blendFactor_zero() { assertEquals(VK_BLEND_FACTOR_ZERO, LavaFlowVk.blendFactor(BlendFactor.ZERO)); }
    @Test void blendFactor_oneMinusSrcAlpha() { assertEquals(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, LavaFlowVk.blendFactor(BlendFactor.ONE_MINUS_SRC_ALPHA)); }

    // compareOp: non-trivial naming (ALWAYS_PASS, NEVER_PASS, LESS_THAN)

    @Test void compareOp_alwaysPass() { assertEquals(VK_COMPARE_OP_ALWAYS, LavaFlowVk.compareOp(CompareOp.ALWAYS_PASS)); }
    @Test void compareOp_neverPass() { assertEquals(VK_COMPARE_OP_NEVER, LavaFlowVk.compareOp(CompareOp.NEVER_PASS)); }
    @Test void compareOp_lessThan() { assertEquals(VK_COMPARE_OP_LESS, LavaFlowVk.compareOp(CompareOp.LESS_THAN)); }
    @Test void compareOp_greaterThanOrEqual() { assertEquals(VK_COMPARE_OP_GREATER_OR_EQUAL, LavaFlowVk.compareOp(CompareOp.GREATER_THAN_OR_EQUAL)); }

    // addressMode and filter: ternary branches

    @Test void addressMode_repeat() { assertEquals(VK_SAMPLER_ADDRESS_MODE_REPEAT, LavaFlowVk.addressMode(AddressMode.REPEAT)); }
    @Test void addressMode_clampToEdge() { assertEquals(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, LavaFlowVk.addressMode(AddressMode.CLAMP_TO_EDGE)); }
    @Test void filter_nearest() { assertEquals(VK_FILTER_NEAREST, LavaFlowVk.filter(FilterMode.NEAREST)); }
    @Test void filter_linear() { assertEquals(VK_FILTER_LINEAR, LavaFlowVk.filter(FilterMode.LINEAR)); }

    // polygonMode

    @Test void polygonMode_fill() { assertEquals(VK_POLYGON_MODE_FILL, LavaFlowVk.polygonMode(PolygonMode.FILL)); }
    @Test void polygonMode_wireframe() { assertEquals(VK_POLYGON_MODE_LINE, LavaFlowVk.polygonMode(PolygonMode.WIREFRAME)); }
}
