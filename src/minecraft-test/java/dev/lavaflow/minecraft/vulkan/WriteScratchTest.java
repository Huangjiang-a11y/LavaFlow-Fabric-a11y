package dev.lavaflow.minecraft.vulkan;

import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins what {@link LavaFlowDescriptorCache.WriteScratch} hands back.
 *
 * <p>These structures are reused between descriptor pushes, so what matters is that a caller always gets
 * room for every entry, that each entry really is its own slot in that room, and that a later call with
 * more entries does not hand back structures that are too small. Reuse is exactly what makes those
 * properties easy to break, so they are asserted here rather than only through a device.
 *
 * <p>That the driver accepts the reused structures is covered where they are filled: see
 * {@code LavaFlowRenderPass.buildWrites}, which writes every field it relies on and clears the union
 * members it does not, because a reused structure keeps whatever an earlier push left in it.
 */
class WriteScratchTest {
    @Test
    void writesCoverEveryEntryInItsOwnSlot() {
        LavaFlowDescriptorCache.WriteScratch scratch = new LavaFlowDescriptorCache.WriteScratch();
        try {
            for (int count : new int[]{1, 2, 5}) {
                VkWriteDescriptorSet.Buffer writes = scratch.writes(count);
                assertEquals(count, writes.capacity());

                for (int i = 0; i < count; i++) scratch.write(i).sType$Default().dstBinding(i);
                // Read back only after every slot was written: if the views aliased one slot, only the
                // last write would survive and this would see the wrong binding.
                for (int i = 0; i < count; i++) {
                    assertEquals(i, scratch.write(i).dstBinding(), "entry " + i + " lost its own slot");
                }
            }
        } finally {
            scratch.close();
        }
    }

    @Test
    void theSameSizeIsNotAllocatedAgain() {
        LavaFlowDescriptorCache.WriteScratch scratch = new LavaFlowDescriptorCache.WriteScratch();
        try {
            VkWriteDescriptorSet.Buffer first = scratch.writes(3);
            VkWriteDescriptorSet view = scratch.write(0);
            VkDescriptorBufferInfo.Buffer info = scratch.bufferInfo(0);

            VkWriteDescriptorSet.Buffer second = scratch.writes(3);

            assertSame(first, second, "the same size must not allocate again");
            assertSame(view, scratch.write(0), "the views must be the ones built for this buffer");
            assertSame(info, scratch.bufferInfo(0));
            assertEquals(first.address(), scratch.write(0).address(),
                    "a view must keep pointing at its own slot in the reused buffer");
        } finally {
            scratch.close();
        }
    }

    @Test
    void aLargerCallGrowsTheStructures() {
        LavaFlowDescriptorCache.WriteScratch scratch = new LavaFlowDescriptorCache.WriteScratch();
        try {
            scratch.writes(2);
            VkWriteDescriptorSet smallView = scratch.write(0);

            VkWriteDescriptorSet.Buffer grown = scratch.writes(8);

            assertEquals(8, grown.capacity(), "a later call must get room for all of its entries");
            assertNotSame(smallView, scratch.write(0), "the views must be rebuilt against the grown buffer");
            grown.get(7).sType$Default().dstBinding(7);
            assertEquals(7, scratch.write(7).dstBinding(), "the last entry must be addressable");
        } finally {
            scratch.close();
        }
    }

    /**
     * The count the driver is told to read is {@code remaining()} of the buffer handed to
     * {@code vkUpdateDescriptorSets} -- LWJGL passes {@code Checks.remainingSafe(buffer)} as
     * {@code descriptorWriteCount} -- not its capacity. A reused buffer that grew for an earlier push is
     * therefore a trap: a later push with fewer entries must not leave the driver reading slots that
     * nobody filled this time.
     *
     * <p>This is what the Mali-G76 run crashed on: SIGSEGV inside the driver's
     * {@code vkUpdateDescriptorSets}, right after entering a world, intermittently. The test above checked
     * {@code capacity()} -- "there is room" -- which is true whether or not the count is right, so it could
     * not have caught this.
     */
    @Test
    void theDriverIsToldTheCurrentEntryCount() {
        LavaFlowDescriptorCache.WriteScratch scratch = new LavaFlowDescriptorCache.WriteScratch();
        try {
            for (int count : new int[]{1, 2, 5, 8, 3, 5, 1}) {
                VkWriteDescriptorSet.Buffer writes = scratch.writes(count);
                assertEquals(count, writes.remaining(),
                        "a push of " + count + " entries must tell the driver to read " + count);
            }
        } finally {
            scratch.close();
        }
    }

    /**
     * Why the count has to be exact: a slot the current push does not use still holds the previous push's
     * descriptor set handle, and nothing clears it. Sending the driver that slot means pointing it at an
     * object that may already be destroyed -- the difference between silent corruption and a native crash.
     */
    @Test
    void aShrunkCallLeavesEarlierHandlesInTheSlotsItDoesNotUse() {
        LavaFlowDescriptorCache.WriteScratch scratch = new LavaFlowDescriptorCache.WriteScratch();
        try {
            VkWriteDescriptorSet.Buffer wide = scratch.writes(8);
            wide.get(7).sType$Default().dstSet(0xDEADBEEFL).dstBinding(7);

            VkWriteDescriptorSet.Buffer narrow = scratch.writes(3);

            assertEquals(3, narrow.remaining(), "the driver must be told to read three entries, not eight");
            assertEquals(8, narrow.capacity(), "the room is kept; only the count shrinks");
            assertEquals(0xDEADBEEFL, scratch.write(7).dstSet(),
                    "the stale handle is still in slot 7 -- only the count keeps it away from the driver");
        } finally {
            scratch.close();
        }
    }
}
