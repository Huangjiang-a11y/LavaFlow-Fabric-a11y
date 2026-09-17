package dev.lavaflow.minecraft.vulkan;

import com.mojang.blaze3d.systems.GpuQueryPool;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

import java.nio.LongBuffer;
import java.util.OptionalLong;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

final class LavaFlowQueryPool implements GpuQueryPool {
    private final LavaFlowVulkanContext context;
    private final int size;
    private long pool;

    LavaFlowQueryPool(LavaFlowVulkanContext context, int size) {
        if (size <= 0) throw new IllegalArgumentException("Query pool size must be positive");
        this.context = context; this.size = size;
        try (MemoryStack stack = stackPush()) {
            VkQueryPoolCreateInfo info = VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                    .queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(size);
            LongBuffer out = stack.mallocLong(1);
            int result = vkCreateQueryPool(context.device(), info, null, out);
            if (result != VK_SUCCESS) throw new IllegalStateException("vkCreateQueryPool failed with VkResult " + result);
            pool = out.get(0);
        }
    }

    long handle() { return pool; }
    @Override public int size() { return size; }
    @Override public OptionalLong getValue(int index) {
        checkIndex(index);
        try (MemoryStack stack = stackPush()) {
            LongBuffer value = stack.mallocLong(1);
            int result = vkGetQueryPoolResults(context.device(), pool, index, 1, value, Long.BYTES,
                    VK_QUERY_RESULT_64_BIT);
            if (result == VK_NOT_READY) return OptionalLong.empty();
            if (result != VK_SUCCESS) throw new IllegalStateException("vkGetQueryPoolResults failed with VkResult " + result);
            return OptionalLong.of(value.get(0));
        }
    }
    @Override public OptionalLong[] getValues(int first, int count) {
        if (first < 0 || count < 0 || first + count > size) throw new IndexOutOfBoundsException();
        OptionalLong[] values = new OptionalLong[count];
        for (int i = 0; i < count; i++) values[i] = getValue(first + i);
        return values;
    }
    private void checkIndex(int index) { if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index); }
    @Override public void close() { if (pool != 0) { vkDestroyQueryPool(context.device(), pool, null); pool = 0; } }
}
