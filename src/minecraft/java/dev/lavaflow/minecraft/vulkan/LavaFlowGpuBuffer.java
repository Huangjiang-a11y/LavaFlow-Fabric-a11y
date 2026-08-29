package dev.lavaflow.minecraft.vulkan;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMappedMemoryRange;
import org.lwjgl.vulkan.VkMemoryRequirements;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;

/** A directly mappable LavaFlow buffer with explicit Vulkan memory ownership. */
final class LavaFlowGpuBuffer extends GpuBuffer {
    private final LavaFlowDevice device;
    private final LavaFlowVulkanContext context;
    private final long buffer;
    private final long memory;
    private int mappingCount;
    // Physical base of the mapping such that address of buffer position x = mappedPhysicalBase + x.
    // Zero when the buffer is not mapped. Volatile so the render-thread draw path can read it without
    // a lock: map() and unmap() are synchronized but drawIndexedIndirect runs on the same thread.
    volatile long mappedPhysicalBase;
    private boolean closed;

    LavaFlowGpuBuffer(LavaFlowDevice device, int usage, long size) {
        super(usage, size);
        if (size <= 0) throw new IllegalArgumentException("Buffer size must be positive");
        this.device = device;
        this.context = device.context();
        long createdBuffer = NULL;
        long allocatedMemory = NULL;
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size(size).usage(LavaFlowVk.bufferUsage(usage)).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer out = stack.mallocLong(1);
            check(vkCreateBuffer(context.device(), info, null, out), "vkCreateBuffer");
            createdBuffer = out.get(0);
            VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(context.device(), createdBuffer, requirements);
            boolean mapped = (usage & (USAGE_MAP_READ | USAGE_MAP_WRITE)) != 0;
            int requiredMemory = mapped
                    ? VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
                    : VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
            // Indirect parameters may be read back by the backend when the device cannot batch indirect
            // draws, and mapped buffers requesting reads obviously are. Uncached host memory makes those
            // reads cost orders of magnitude more than cached memory, so ask for cached in both cases.
            int preferredMemory = mapped && (usage & (USAGE_MAP_READ | USAGE_INDIRECT_PARAMETERS)) != 0
                    ? VK_MEMORY_PROPERTY_HOST_CACHED_BIT : 0;
            VkMemoryAllocateInfo allocation = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .allocationSize(requirements.size())
                    .memoryTypeIndex(context.findMemoryType(requirements.memoryTypeBits(), requiredMemory,
                            preferredMemory));
            check(vkAllocateMemory(context.device(), allocation, null, out), "vkAllocateMemory(buffer)");
            allocatedMemory = out.get(0);
            check(vkBindBufferMemory(context.device(), createdBuffer, allocatedMemory, 0), "vkBindBufferMemory");
        } catch (Throwable failure) {
            if (allocatedMemory != NULL) vkFreeMemory(context.device(), allocatedMemory, null);
            if (createdBuffer != NULL) vkDestroyBuffer(context.device(), createdBuffer, null);
            throw failure;
        }
        buffer = createdBuffer;
        memory = allocatedMemory;
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) throw new IllegalStateException(operation + " failed with VkResult " + result);
    }

    long handle() { return buffer; }
    long memory() { return memory; }

    synchronized void write(long offset, ByteBuffer source) {
        if (closed) throw new IllegalStateException("Buffer is closed");
        if ((usage() & USAGE_MAP_WRITE) == 0) throw new IllegalStateException("Buffer is not host writable");
        if (offset < 0 || offset + source.remaining() > size()) throw new IllegalArgumentException("Write exceeds buffer");
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pointer = stack.mallocPointer(1);
            check(vkMapMemory(context.device(), memory, offset, source.remaining(), 0, pointer), "vkMapMemory(write)");
            MemoryUtil.memCopy(MemoryUtil.memAddress(source) + source.position(), pointer.get(0), source.remaining());
            VkMappedMemoryRange flushRange = VkMappedMemoryRange.calloc(stack).sType$Default()
                    .memory(memory).offset(0).size(VK_WHOLE_SIZE);
            vkFlushMappedMemoryRanges(context.device(), flushRange);
            vkUnmapMemory(context.device(), memory);
        }
    }

    @Override public synchronized GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
        if (closed) throw new IllegalStateException("Buffer is closed");
        if (!read && !write) throw new IllegalArgumentException("At least read or write must be requested");
        if (read && (usage() & USAGE_MAP_READ) == 0) throw new IllegalStateException("Buffer is not readable");
        if (write && (usage() & USAGE_MAP_WRITE) == 0) throw new IllegalStateException("Buffer is not writable");
        if (offset < 0 || length < 0 || offset + length > size()) throw new IllegalArgumentException("Invalid mapped range");
        if (length > Integer.MAX_VALUE) throw new IllegalArgumentException("Mappings larger than 2 GiB are unsupported");
        if (mappingCount != 0) throw new IllegalStateException("Concurrent mappings of one buffer are unsupported");
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pointer = stack.mallocPointer(1);
            check(vkMapMemory(context.device(), memory, offset, length, 0, pointer), "vkMapMemory");
            mappingCount = 1;
            mappedPhysicalBase = pointer.get(0) - offset;
            ByteBuffer data = MemoryUtil.memByteBuffer(pointer.get(0), (int)length);
            AtomicBoolean released = new AtomicBoolean();
            return new GpuBufferSlice.MappedView(new GpuBufferSlice(this, offset, length), data, () -> {
                if (released.compareAndSet(false, true)) unmap();
            });
        }
    }

    private synchronized void unmap() {
        if (mappingCount == 0) return;
        mappedPhysicalBase = 0;
        vkUnmapMemory(context.device(), memory);
        mappingCount = 0;
    }

    // Ensures CPU writes to this mapped buffer are visible to the GPU. Required on some mobile
    // drivers (Mali) where HOST_COHERENT writes are not reliably visible without an explicit flush.
    void flushMapped() {
        flushMapped(0, VK_WHOLE_SIZE);
    }

    // 按脏区间 flush：Vulkan 要求 offset/size 对齐到 nonCoherentAtomSize，否则部分移动驱动
    // 会拒绝或漏刷缓存行。这里把调用方给的区间按 atom 向下/向上对齐，并 clamp 到映射范围。
    void flushMapped(long offset, long length) {
        if (mappingCount == 0) return;
        if (length <= 0 || length >= VK_WHOLE_SIZE) {
            flushMapped(0, VK_WHOLE_SIZE);
            return;
        }
        long atom = context.properties().limits().nonCoherentAtomSize();
        if (atom <= 0) atom = 1;
        long alignedOffset = (offset / atom) * atom;
        long end = ((offset + length + atom - 1) / atom) * atom;
        long mappedBytes = size();
        if (end > mappedBytes) end = mappedBytes;
        long alignedLength = end - alignedOffset;
        if (alignedLength <= 0) return;
        try (MemoryStack stack = stackPush()) {
            VkMappedMemoryRange range = VkMappedMemoryRange.calloc(stack).sType$Default()
                    .memory(memory).offset(alignedOffset).size(alignedLength);
            vkFlushMappedMemoryRanges(context.device(), range);
        }
    }

    @Override public synchronized boolean isClosed() { return closed; }

    @Override public synchronized void close() {
        if (closed) return;
        if (mappingCount != 0) throw new IllegalStateException("Cannot close a mapped buffer");
        closed = true;
        device.invalidateDescriptorCache(buffer);
        device.defer(this::destroyNow);
    }

    private void destroyNow() {
        vkDestroyBuffer(context.device(), buffer, null);
        vkFreeMemory(context.device(), memory, null);
    }
}
