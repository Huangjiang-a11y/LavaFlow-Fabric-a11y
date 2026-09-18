package dev.lavaflow.minecraft.vulkan;

import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * SPIR-V module compatibility for devices older than the frontend's compilation target.
 *
 * <p>Minecraft 26.3 compiles every shader on the frontend with a hard-coded
 * {@code shaderc_env_version_vulkan_1_2} target and never overrides the SPIR-V version, so shaderc emits
 * SPIR-V 1.5 modules. A Vulkan 1.1 device without {@code VK_KHR_spirv_1_4} accepts at most SPIR-V 1.3:
 * {@code vkCreateShaderModule} takes the module without complaint, but the first
 * {@code vkCreateGraphicsPipelines} that consumes it fails with {@code VK_ERROR_INITIALIZATION_FAILED}.
 *
 * <p>26.2 did not hit this because LavaFlow compiled its own shaders and pinned the target to
 * {@code vulkan_1_1} / SPIR-V 1.3. The backend cannot set shaderc options, so the only place left to
 * correct the mismatch is the module header: lower the 4-byte version word to what the device accepts.
 * That is sound as long as the module uses no instruction introduced after the target version, which
 * holds for the GLSL the frontend feeds us — the version word reflects the target environment, not the
 * instructions the shader actually needs. On devices that already accept the module the bytes are passed
 * through untouched, so capable devices are unaffected.
 *
 * <p>The downgrade can be disabled with {@code -Dlavaflow.noSpirvDowngrade=true}, which is only useful
 * for bisecting a suspected downgrade problem.
 */
final class LavaFlowSpirv {
    /** SPIR-V version words: header word 1 encodes {@code 0x0001_MMmm_00}. */
    static final int VERSION_1_0 = 0x00010000;
    static final int VERSION_1_1 = 0x00010100;
    static final int VERSION_1_2 = 0x00010200;
    static final int VERSION_1_3 = 0x00010300;
    static final int VERSION_1_4 = 0x00010400;
    static final int VERSION_1_5 = 0x00010500;
    static final int VERSION_1_6 = 0x00010600;

    private static final int MAGIC = 0x07230203;
    /** Five 32-bit header words: magic, version, generator, bound, schema. */
    private static final int HEADER_BYTES = 20;
    private static final int VERSION_OFFSET = 4;

    private LavaFlowSpirv() {}

    /**
     * The SPIR-V version word of {@code module}, or {@code 0} when the buffer is not a little-endian
     * SPIR-V module (too short, or the magic does not match). Callers treat {@code 0} as "not recognised"
     * and hand the module through unchanged rather than guessing at its layout.
     */
    static int versionOf(ByteBuffer module) {
        if (module == null || module.remaining() < HEADER_BYTES) return 0;
        ByteBuffer words = module.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int start = words.position();
        if (words.getInt(start) != MAGIC) return 0;
        return words.getInt(start + VERSION_OFFSET);
    }

    /**
     * Returns a module {@code maxVersion} can consume: {@code module} itself when its version is already
     * supported (or unrecognised), otherwise a copy of it with the header version word lowered to
     * {@code maxVersion}.
     *
     * <p>Every other byte is copied through unchanged, so the instructions, debug info and decorations the
     * frontend generated — including the binding and location decorations the descriptor set layout and
     * pipeline state are built from — are preserved exactly. When a copy is needed it is allocated from
     * {@code stack} and therefore only has to stay alive for the {@code vkCreateShaderModule} call.
     */
    static ByteBuffer downlevel(MemoryStack stack, ByteBuffer module, int maxVersion) {
        int version = versionOf(module);
        if (version == 0 || version <= maxVersion || Boolean.getBoolean("lavaflow.noSpirvDowngrade")) {
            return module;
        }
        ByteBuffer source = module.duplicate();
        int length = source.remaining();
        ByteBuffer copy = stack.malloc(length);
        copy.put(source);
        copy.flip();
        copy.order(ByteOrder.LITTLE_ENDIAN).putInt(VERSION_OFFSET, maxVersion);
        return copy;
    }

    /** Renders a version word for diagnostics, e.g. {@code 0x00010500} as {@code 1.5}. */
    static String describe(int version) {
        if (version == 0) return "unknown";
        return ((version >> 16) & 0xFF) + "." + ((version >> 8) & 0xF);
    }
}
