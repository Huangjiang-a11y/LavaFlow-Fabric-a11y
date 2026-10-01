package dev.lavaflow.minecraft.vulkan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Pins the identity rules of {@link LavaFlowDescriptorCache.Key}.
 *
 * <p>A key is the descriptor set layout handle a set was built for, plus the handles of the resources
 * bound in it. The layout has to take part in equality. Reloading shaders closes every pipeline, and
 * Vulkan may hand a pipeline compiled afterwards the layout handle just released, so an entry that
 * ignored which layout it belonged to would not miss the cache on such a reload — it would hit an entry
 * that only looks as if it matches. That is why the cache retires entries when a layout is destroyed,
 * and why this test exists: dropping {@code owner} from {@code equals} would read as a harmless
 * simplification while quietly restoring the aliasing.
 *
 * <p>Only the key arithmetic is covered here; exercising the cache itself needs a Vulkan device.
 */
class LavaFlowDescriptorCacheTest {
    private static final long LAYOUT = 0x0000_7F00_0000_1234L;
    private static final long OTHER_LAYOUT = 0x0000_7F00_0000_5678L;

    @Test
    void keysAgreeWhenLayoutAndResourcesAgree() {
        long[] resources = {0x1000L, 0x2000L};

        assertEquals(new LavaFlowDescriptorCache.Key(LAYOUT, resources),
                new LavaFlowDescriptorCache.Key(LAYOUT, resources.clone()));
    }

    @Test
    void keysDisagreeWhenOnlyTheLayoutDisagrees() {
        long[] resources = {0x1000L, 0x2000L};

        assertNotEquals(new LavaFlowDescriptorCache.Key(LAYOUT, resources),
                new LavaFlowDescriptorCache.Key(OTHER_LAYOUT, resources.clone()),
                "a recycled layout handle must not let a set built for another layout match");
    }

    @Test
    void keysDisagreeWhenOnlyAResourceDisagrees() {
        assertNotEquals(new LavaFlowDescriptorCache.Key(LAYOUT, new long[]{0x1000L, 0x2000L}),
                new LavaFlowDescriptorCache.Key(LAYOUT, new long[]{0x1000L, 0x3000L}),
                "a set bound to different resources is a different set");
    }

    @Test
    void keysDisagreeWhenOnlyResourceOrderDisagrees() {
        assertNotEquals(new LavaFlowDescriptorCache.Key(LAYOUT, new long[]{0x1000L, 0x2000L}),
                new LavaFlowDescriptorCache.Key(LAYOUT, new long[]{0x2000L, 0x1000L}),
                "resources are positional: the same two buffers at swapped bindings are not interchangeable");
    }

    @Test
    void equalKeysShareAHash() {
        long[] resources = {0x1000L, 0x2000L};

        assertEquals(new LavaFlowDescriptorCache.Key(LAYOUT, resources).hashCode(),
                new LavaFlowDescriptorCache.Key(LAYOUT, resources.clone()).hashCode());
    }

    /**
     * A key the cache keeps must not follow later writes to the array it was built from. Both callers
     * that build keys out of arrays they reuse — {@code pushDescriptors} and {@code bufferView} — rely on
     * this, and a key that kept the array would silently change what the cache had already accepted.
     */
    @Test
    void copiedKeysDoNotFollowTheCallersArray() {
        long[] reused = {0x1000L, 0x2000L};
        LavaFlowDescriptorCache.Key probe = new LavaFlowDescriptorCache.Key(LAYOUT, reused);
        LavaFlowDescriptorCache.Key stored = probe.copy();

        reused[0] = 0x9999L;

        assertNotEquals(probe, stored, "a stored key must keep the values it was built from");
        assertEquals(new LavaFlowDescriptorCache.Key(LAYOUT, new long[]{0x1000L, 0x2000L}), stored);
    }
}
