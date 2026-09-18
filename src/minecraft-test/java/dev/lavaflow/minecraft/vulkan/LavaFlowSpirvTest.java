package dev.lavaflow.minecraft.vulkan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests the SPIR-V version handling that lets a Vulkan 1.1 device consume the SPIR-V 1.5 modules the 26.3
 * frontend compiles.
 *
 * <p>Only the header is touched, so these tests build synthetic modules: a real one is not needed to prove
 * which bytes are rewritten, that everything else survives and that a module the device already accepts is
 * passed through by reference.
 */
class LavaFlowSpirvTest {
    private static final int MAGIC = 0x07230203;
    private static final int GENERATOR = 0x00080007;
    private static final int BOUND = 42;
    private static final int SCHEMA = 0;
    /** Magic, version, generator, bound, schema, then one instruction word. */
    private static final int WORDS = 6;

    @BeforeEach
    void clearOverride() {
        System.clearProperty("lavaflow.noSpirvDowngrade");
    }

    private static ByteBuffer module(int version) {
        ByteBuffer module = ByteBuffer.allocateDirect(WORDS * 4).order(ByteOrder.LITTLE_ENDIAN);
        module.putInt(MAGIC).putInt(version).putInt(GENERATOR).putInt(BOUND).putInt(SCHEMA).putInt(0x00010001);
        module.flip();
        return module;
    }

    @Test void versionOf_readsHeaderWord() {
        assertEquals(LavaFlowSpirv.VERSION_1_5, LavaFlowSpirv.versionOf(module(LavaFlowSpirv.VERSION_1_5)));
        assertEquals(LavaFlowSpirv.VERSION_1_3, LavaFlowSpirv.versionOf(module(LavaFlowSpirv.VERSION_1_3)));
    }

    @Test void versionOf_rejectsTruncatedAndForeignBuffers() {
        assertEquals(0, LavaFlowSpirv.versionOf(null));
        assertEquals(0, LavaFlowSpirv.versionOf(ByteBuffer.allocateDirect(8)));
        ByteBuffer foreign = ByteBuffer.allocateDirect(24).order(ByteOrder.LITTLE_ENDIAN);
        foreign.putInt(0xDEADBEEF).putInt(LavaFlowSpirv.VERSION_1_5).flip();
        assertEquals(0, LavaFlowSpirv.versionOf(foreign));
    }

    @Test void downlevel_lowersVersionAndPreservesEverythingElse() {
        ByteBuffer original = module(LavaFlowSpirv.VERSION_1_5);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer lowered = LavaFlowSpirv.downlevel(stack, original, LavaFlowSpirv.VERSION_1_3);
            assertNotSame(original, lowered, "a downgraded module must be a copy, not the caller's buffer");
            assertEquals(LavaFlowSpirv.VERSION_1_3, LavaFlowSpirv.versionOf(lowered));
            assertEquals(WORDS * 4, lowered.remaining(), "the module must keep its length");
            ByteBuffer before = original.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer after = lowered.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            for (int word = 2; word < WORDS; word++) {
                assertEquals(before.getInt(word * 4), after.getInt(word * 4), "word " + word + " must survive");
            }
            assertEquals(MAGIC, after.getInt(0), "the magic must survive");
            assertEquals(LavaFlowSpirv.VERSION_1_5, before.getInt(4), "the caller's buffer must not be modified");
        }
    }

    @Test void downlevel_passesThroughWhenAlreadySupported() {
        ByteBuffer supported = module(LavaFlowSpirv.VERSION_1_3);
        ByteBuffer newer = module(LavaFlowSpirv.VERSION_1_6);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            assertSame(supported, LavaFlowSpirv.downlevel(stack, supported, LavaFlowSpirv.VERSION_1_3));
            assertSame(newer, LavaFlowSpirv.downlevel(stack, newer, LavaFlowSpirv.VERSION_1_6));

            // A device below the module's version still gets a lowered copy, so the boundary is inclusive
            // on the supported side only.
            ByteBuffer lowered = LavaFlowSpirv.downlevel(stack, newer, LavaFlowSpirv.VERSION_1_0);
            assertNotSame(newer, lowered);
            assertEquals(LavaFlowSpirv.VERSION_1_0, LavaFlowSpirv.versionOf(lowered));
        }
    }

    @Test void downlevel_ignoresUnrecognisedModules() {
        ByteBuffer foreign = ByteBuffer.allocateDirect(24).order(ByteOrder.LITTLE_ENDIAN);
        foreign.putInt(0xDEADBEEF).putInt(LavaFlowSpirv.VERSION_1_5).flip();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            assertSame(foreign, LavaFlowSpirv.downlevel(stack, foreign, LavaFlowSpirv.VERSION_1_0));
        }
    }

    @Test void downlevel_honoursEscapeHatch() {
        ByteBuffer module = module(LavaFlowSpirv.VERSION_1_5);
        System.setProperty("lavaflow.noSpirvDowngrade", "true");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            assertSame(module, LavaFlowSpirv.downlevel(stack, module, LavaFlowSpirv.VERSION_1_3));
        } finally {
            System.clearProperty("lavaflow.noSpirvDowngrade");
        }
    }

    @Test void describe_rendersVersionsReadably() {
        assertEquals("1.5", LavaFlowSpirv.describe(LavaFlowSpirv.VERSION_1_5));
        assertEquals("1.3", LavaFlowSpirv.describe(LavaFlowSpirv.VERSION_1_3));
        assertEquals("unknown", LavaFlowSpirv.describe(0));
    }
}
