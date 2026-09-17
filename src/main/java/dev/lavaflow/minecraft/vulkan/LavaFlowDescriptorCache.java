package dev.lavaflow.minecraft.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferViewCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_ERROR_OUT_OF_POOL_MEMORY;

/**
 * Reuses descriptor sets and buffer views across frames on devices without
 * {@code VK_KHR_push_descriptor}.
 *
 * <p>Allocating and writing a descriptor set is not cheap, and the bindings a frame needs are
 * largely the ones the previous frame needed. Sets are therefore keyed by the resources they point
 * at and kept until one of those resources is destroyed: a descriptor refers to memory rather than
 * to the values in it, so a set stays valid however often the contents are rewritten.
 *
 * <p>Destruction of a resource retires only the entries that reference it, through a reverse index
 * from resource handle to cache keys. Sets are never rewritten once cached, which is what makes
 * them safe to leave bound in command buffers that are still executing; retirement goes through the
 * device's deferred-release path so nothing is freed before the submission that used it completes.
 */
final class LavaFlowDescriptorCache {
    private static final int SETS_PER_POOL = 256;

    private final LavaFlowVulkanContext context;
    private final LavaFlowDevice device;
    private final List<Long> pools = new ArrayList<>();
    private final Map<Key, CachedSet> sets = new HashMap<>();
    private final Map<Key, Long> bufferViews = new HashMap<>();
    private final Map<Long, List<Key>> byResource = new HashMap<>();
    private int poolIndex;

    private record CachedSet(long set, long pool) {}

    LavaFlowDescriptorCache(LavaFlowVulkanContext context, LavaFlowDevice device) {
        this.context = context;
        this.device = device;
    }

    /** Identity of a cached object: the layout or format it was built for, plus the resources in it. */
    static final class Key {
        private final long owner;
        private final long[] values;
        private final int hash;

        Key(long owner, long[] values) {
            this.owner = owner;
            this.values = values;
            this.hash = 31 * Long.hashCode(owner) + Arrays.hashCode(values);
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key key)) return false;
            return owner == key.owner && Arrays.equals(values, key.values);
        }

        @Override public int hashCode() { return hash; }
    }

    /** Returns the set cached for {@code key}, or {@code 0} when there is none. */
    long lookup(Key key) {
        CachedSet cached = sets.get(key);
        return cached == null ? 0L : cached.set();
    }

    /**
     * Allocates a set for {@code layout} and caches it under {@code key}. Every resource handle the
     * set references is registered so destroying that resource retires exactly this entry.
     */
    long allocateAndStore(Key key, long layout, long[] resourceHandles) {
        LavaFlowFrameStats.descriptorSetAllocated();
        if (pools.isEmpty()) pools.add(createPool());
        try (MemoryStack stack = stackPush()) {
            while (true) {
                VkDescriptorSetAllocateInfo allocation = VkDescriptorSetAllocateInfo.calloc(stack)
                        .sType$Default().descriptorPool(pools.get(poolIndex))
                        .pSetLayouts(stack.longs(layout));
                LongBuffer out = stack.mallocLong(1);
                int result = vkAllocateDescriptorSets(context.device(), allocation, out);
                if (result == VK_SUCCESS) {
                    long set = out.get(0);
                    sets.put(key, new CachedSet(set, pools.get(poolIndex)));
                    for (long handle : resourceHandles) registerResource(handle, key);
                    return set;
                }
                if (result != VK_ERROR_OUT_OF_POOL_MEMORY && result != VK_ERROR_FRAGMENTED_POOL) {
                    throw new IllegalStateException("vkAllocateDescriptorSets failed with VkResult " + result);
                }
                poolIndex++;
                if (poolIndex == pools.size()) pools.add(createPool());
            }
        }
    }

    /** Returns a buffer view for the given range, creating and caching it on first use. */
    long bufferView(long buffer, int format, long offset, long length) {
        Key key = new Key(format, new long[]{buffer, offset, length});
        Long cached = bufferViews.get(key);
        if (cached != null) return cached;
        try (MemoryStack stack = stackPush()) {
            VkBufferViewCreateInfo info = VkBufferViewCreateInfo
                    .calloc(stack).sType$Default().buffer(buffer).format(format).offset(offset).range(length);
            LongBuffer out = stack.mallocLong(1);
            check(vkCreateBufferView(context.device(), info, null, out), "vkCreateBufferView");
            long view = out.get(0);
            bufferViews.put(key, view);
            registerResource(buffer, key);
            return view;
        }
    }

    private void registerResource(long handle, Key key) {
        byResource.computeIfAbsent(handle, ignored -> new ArrayList<>(4)).add(key);
    }

    /**
     * Retires every cached set and buffer view that references {@code handle}, because that resource
     * is being destroyed. Entries leave the maps at once so nothing stale is handed out again; the
     * Vulkan objects are released once the submissions that used them have completed.
     */
    void invalidate(long handle) {
        List<Key> keys = byResource.remove(handle);
        if (keys == null) return;
        LavaFlowFrameStats.descriptorCacheInvalidated();
        List<CachedSet> staleSets = new ArrayList<>(keys.size());
        List<Long> staleViews = new ArrayList<>(0);
        for (Key key : keys) {
            CachedSet set = sets.remove(key);
            if (set != null) staleSets.add(set);
            Long view = bufferViews.remove(key);
            if (view != null) staleViews.add(view);
        }
        if (staleSets.isEmpty() && staleViews.isEmpty()) return;
        device.defer(() -> {
            for (Long view : staleViews) vkDestroyBufferView(context.device(), view, null);
            try (MemoryStack stack = stackPush()) {
                for (CachedSet set : staleSets) {
                    check(vkFreeDescriptorSets(context.device(), set.pool(), stack.longs(set.set())),
                            "vkFreeDescriptorSets");
                }
            }
        });
    }

    /**
     * Drops the whole cache. Needed when the pipeline cache is cleared: descriptor-set layout
     * handles can be reused by new pipelines, which would let a stale entry alias a fresh layout.
     */
    void invalidateAll() {
        if (sets.isEmpty() && bufferViews.isEmpty()) return;
        LavaFlowFrameStats.descriptorCacheInvalidated();
        long[] staleViews = bufferViews.values().stream().mapToLong(Long::longValue).toArray();
        long[] stalePools = pools.stream().mapToLong(Long::longValue).toArray();
        sets.clear();
        bufferViews.clear();
        byResource.clear();
        pools.clear();
        poolIndex = 0;
        device.defer(() -> {
            for (long view : staleViews) vkDestroyBufferView(context.device(), view, null);
            // Destroying the pools releases every set allocated from them.
            for (long pool : stalePools) vkDestroyDescriptorPool(context.device(), pool, null);
        });
    }

    void destroy() {
        for (long view : bufferViews.values()) vkDestroyBufferView(context.device(), view, null);
        for (long pool : pools) vkDestroyDescriptorPool(context.device(), pool, null);
        bufferViews.clear();
        sets.clear();
        byResource.clear();
        pools.clear();
        poolIndex = 0;
    }

    private long createPool() {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(4, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(SETS_PER_POOL * 4);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(SETS_PER_POOL * 8);
            sizes.get(2).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(SETS_PER_POOL * 4);
            sizes.get(3).type(VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER).descriptorCount(SETS_PER_POOL * 2);
            VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT)
                    .maxSets(SETS_PER_POOL).pPoolSizes(sizes);
            LongBuffer out = stack.mallocLong(1);
            check(vkCreateDescriptorPool(context.device(), info, null, out), "vkCreateDescriptorPool(cache)");
            return out.get(0);
        }
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) throw new IllegalStateException(operation + " failed with VkResult " + result);
    }
}
