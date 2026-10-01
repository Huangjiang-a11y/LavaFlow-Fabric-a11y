package dev.lavaflow.minecraft.vulkan;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins what {@link LavaFlowTransientMemory.CpuSlabs} hands out.
 *
 * <p>These buffers replaced a {@code MemoryUtil.memAlloc} per request, so they have to look the same to
 * the caller — capacity exactly the request, native byte order, and an address that honours the requested
 * alignment — while never overlapping each other: uploads are written into them, and an overlap would
 * corrupt an upload silently. The allocator is exercised directly because the submit-batch lifetime it
 * feeds ({@link LavaFlowTransientMemory.Retired}) is the part that needs a device.
 */
class CpuSlabsTest {
    private static LavaFlowTransientMemory.CpuSlabs newSlabs() {
        return new LavaFlowTransientMemory.CpuSlabs();
    }

    @Test
    void allocationsHaveExactlyTheRequestedCapacity() {
        LavaFlowTransientMemory.CpuSlabs slabs = newSlabs();
        for (int size : new int[]{1, 7, 1024, 4096}) {
            assertEquals(size, slabs.allocate(size, 8).capacity(),
                    "callers use the capacity as the upload size, so it must not be rounded up");
        }
    }

    @Test
    void allocationsHonourTheRequestedAlignment() {
        LavaFlowTransientMemory.CpuSlabs slabs = newSlabs();
        slabs.allocate(1, 8); // make the next one start off a fresh slab boundary
        for (long alignment : new long[]{8, 16, 64, 256}) {
            ByteBuffer allocation = slabs.allocate(64, alignment);
            assertEquals(0L, MemoryUtil.memAddress(allocation) % alignment,
                    "allocation must be " + alignment + "-byte aligned");
        }
    }

    @Test
    void allocationsDoNotOverlap() {
        LavaFlowTransientMemory.CpuSlabs slabs = newSlabs();
        ByteBuffer first = slabs.allocate(256, 8);
        ByteBuffer second = slabs.allocate(256, 8);

        first.put(0, (byte) 0x5A);
        second.put(0, (byte) 0x3C);

        assertEquals((byte) 0x5A, first.get(0), "the second allocation overwrote the first");
        assertEquals((byte) 0x3C, second.get(0));
    }

    @Test
    void aRequestLargerThanASlabGetsItsOwnSlab() {
        LavaFlowTransientMemory.CpuSlabs slabs = newSlabs();
        int huge = 4 * 1024 * 1024;
        ByteBuffer allocation = slabs.allocate(huge, 64);

        assertEquals(huge, allocation.capacity());
        allocation.put(huge - 1, (byte) 0x11);
        assertEquals((byte) 0x11, allocation.get(huge - 1));
    }

    @Test
    void detachHandsTheSlabsOver() {
        LavaFlowTransientMemory.CpuSlabs slabs = newSlabs();
        ByteBuffer before = slabs.allocate(128, 8);
        List<ByteBuffer> handed = slabs.detach();

        assertFalse(handed.isEmpty(), "the slab holding that allocation must be handed over");
        assertNotEquals(MemoryUtil.memAddress(before), MemoryUtil.memAddress(slabs.allocate(128, 8)),
                "after handing the slabs over, allocations must come from a new one");
    }

    @Test
    void aNegativeSizeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> newSlabs().allocate(-1, 8));
    }
}
