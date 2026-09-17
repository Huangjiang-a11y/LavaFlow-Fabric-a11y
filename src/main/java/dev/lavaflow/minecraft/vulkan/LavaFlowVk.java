package dev.lavaflow.minecraft.vulkan;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.BlendOp;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;

import static org.lwjgl.vulkan.VK10.*;

final class LavaFlowVk {
    private static final int[] FORMATS = {
            9, 10, 16, 17, 23, 24, 37, 38, 70, 71, 77, 78, 84, 85, 91, 92,
            13, 14, 20, 21, 27, 28, 41, 42, 74, 75, 81, 82, 88, 89, 95, 96,
            98, 99, 101, 102, 104, 105, 107, 108, 76, 83, 90, 97, 100, 103,
            106, 109, 64, 68, 122, 126, 130, 129, 124, 127
    };

    private LavaFlowVk() {}

    static int format(GpuFormat format) {
        return FORMATS[format.ordinal()];
    }

    static int aspect(GpuFormat format) {
        int mask = 0;
        if (format.hasColorAspect()) mask |= VK_IMAGE_ASPECT_COLOR_BIT;
        if (format.hasDepthAspect()) mask |= VK_IMAGE_ASPECT_DEPTH_BIT;
        if (format.hasStencilAspect()) mask |= VK_IMAGE_ASPECT_STENCIL_BIT;
        return mask;
    }

    static int textureUsage(int usage, GpuFormat format) {
        int flags = 0;
        if ((usage & 8) != 0) {
            if (format.hasColorAspect()) flags |= VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
            if (format.hasDepthAspect() || format.hasStencilAspect()) flags |= VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
        }
        if ((usage & 4) != 0) flags |= VK_IMAGE_USAGE_SAMPLED_BIT;
        if ((usage & 1) != 0) flags |= VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        if ((usage & 2) != 0) flags |= VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        return flags;
    }

    static int bufferUsage(int usage) {
        int flags = 0;
        if ((usage & 8) != 0) flags |= VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        if ((usage & 16) != 0) flags |= VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        if ((usage & 32) != 0) flags |= VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
        if ((usage & 64) != 0) flags |= VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
        if ((usage & 128) != 0) flags |= VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
        if ((usage & 256) != 0) flags |= VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT;
        if ((usage & 512) != 0) flags |= VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
        return flags == 0 ? VK_BUFFER_USAGE_TRANSFER_SRC_BIT : flags;
    }

    static int addressMode(AddressMode mode) {
        return mode == AddressMode.REPEAT ? VK_SAMPLER_ADDRESS_MODE_REPEAT : VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    }

    static int filter(FilterMode mode) {
        return mode == FilterMode.NEAREST ? VK_FILTER_NEAREST : VK_FILTER_LINEAR;
    }

    static int blendFactor(BlendFactor factor) {
        return switch (factor) {
            case CONSTANT_ALPHA -> VK_BLEND_FACTOR_CONSTANT_ALPHA;
            case CONSTANT_COLOR -> VK_BLEND_FACTOR_CONSTANT_COLOR;
            case DST_ALPHA -> VK_BLEND_FACTOR_DST_ALPHA;
            case DST_COLOR -> VK_BLEND_FACTOR_DST_COLOR;
            case ONE -> VK_BLEND_FACTOR_ONE;
            case ONE_MINUS_CONSTANT_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA;
            case ONE_MINUS_CONSTANT_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR;
            case ONE_MINUS_DST_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
            case ONE_MINUS_DST_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
            case ONE_MINUS_SRC_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            case ONE_MINUS_SRC_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
            case SRC_ALPHA -> VK_BLEND_FACTOR_SRC_ALPHA;
            case SRC_ALPHA_SATURATE -> VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
            case SRC_COLOR -> VK_BLEND_FACTOR_SRC_COLOR;
            case ZERO -> VK_BLEND_FACTOR_ZERO;
        };
    }

    static int blendOp(BlendOp op) {
        return switch (op) {
            case ADD -> VK_BLEND_OP_ADD;
            case SUBTRACT -> VK_BLEND_OP_SUBTRACT;
            case REVERSE_SUBTRACT -> VK_BLEND_OP_REVERSE_SUBTRACT;
            case MIN -> VK_BLEND_OP_MIN;
            case MAX -> VK_BLEND_OP_MAX;
        };
    }

    static int compareOp(CompareOp op) {
        return switch (op) {
            case ALWAYS_PASS -> VK_COMPARE_OP_ALWAYS;
            case LESS_THAN -> VK_COMPARE_OP_LESS;
            case LESS_THAN_OR_EQUAL -> VK_COMPARE_OP_LESS_OR_EQUAL;
            case EQUAL -> VK_COMPARE_OP_EQUAL;
            case NOT_EQUAL -> VK_COMPARE_OP_NOT_EQUAL;
            case GREATER_THAN_OR_EQUAL -> VK_COMPARE_OP_GREATER_OR_EQUAL;
            case GREATER_THAN -> VK_COMPARE_OP_GREATER;
            case NEVER_PASS -> VK_COMPARE_OP_NEVER;
        };
    }

    static int polygonMode(PolygonMode mode) {
        return mode == PolygonMode.WIREFRAME ? VK_POLYGON_MODE_LINE : VK_POLYGON_MODE_FILL;
    }

    static int topology(PrimitiveTopology topology) {
        return switch (topology) {
            case LINES, TRIANGLES, QUADS -> topology == PrimitiveTopology.LINES
                    ? VK_PRIMITIVE_TOPOLOGY_LINE_LIST : VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            case DEBUG_LINES -> VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
            case DEBUG_LINE_STRIP -> VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;
            case POINTS -> VK_PRIMITIVE_TOPOLOGY_POINT_LIST;
            case TRIANGLE_STRIP -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
            case TRIANGLE_FAN -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN;
        };
    }

    static int colorWriteMask(ColorTargetState state) {
        int result = 0;
        if (state.writeRed()) result |= VK_COLOR_COMPONENT_R_BIT;
        if (state.writeGreen()) result |= VK_COLOR_COMPONENT_G_BIT;
        if (state.writeBlue()) result |= VK_COLOR_COMPONENT_B_BIT;
        if (state.writeAlpha()) result |= VK_COLOR_COMPONENT_A_BIT;
        return result;
    }
}
