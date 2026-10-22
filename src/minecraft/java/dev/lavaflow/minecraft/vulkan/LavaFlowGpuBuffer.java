package dev.lavaflow.minecraft.vulkan;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
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
import dev.lavaflow.vulkan.VulkanException;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;

/** A directly mappable LavaFlow buffer with explicit Vulkan memory ownership. */
final class LavaFlowGpuBuffer implements GpuBuffer {
    private final LavaFlowDevice device;
    private final LavaFlowVulkanContext context;
    // 26.3 turned GpuBuffer into an interface; these two were the former abstract base class fields.
    private final int usage;
    private final long size;
    private int memoryTypeIndex;
    private final long buffer;
    private final long memory;
    private int mappingCount;
    // Physical base of the mapping such that address of buffer position x = mappedPhysicalBase + x.
    // Zero when the buffer is not mapped. Volatile so the render-thread draw path can read it without
    // a lock: map() and unmap() are synchronized but drawIndexedIndirect runs on the same thread.
    volatile long mappedPhysicalBase;
    private boolean closed;

    LavaFlowGpuBuffer(LavaFlowDevice device, int usage, long size) {
        if (size <= 0) throw new IllegalArgumentException("Buffer size must be positive");
        this.usage = usage;
        this.size = size;
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
            boolean mapped = (usage & (GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE)) != 0;
            int requiredMemory = mapped
                    ? VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
                    : VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
            // Indirect parameters may be read back by the backend when the device cannot batch indirect
            // draws, and mapped buffers requesting reads obviously are. Uncached host memory makes those
            // reads cost orders of magnitude more than cached memory, so ask for cached in both cases.
            int preferredMemory = mapped && (usage & (GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_INDIRECT_PARAMETERS)) != 0
                    ? VK_MEMORY_PROPERTY_HOST_CACHED_BIT : 0;
            int memoryType = context.findMemoryType(requirements.memoryTypeBits(), requiredMemory, preferredMemory);
            // HOST_COHERENT 是硬条件而不是偏好：LavaFlow 不做手工 flush（write() 与 transient 上传都不调
            // vkFlushMappedMemoryRanges），非一致内存会让 GPU 读到旧数据。findMemoryType 已经把它列进
            // requiredFlags，这里在分配点再核对一次 flags，免得以后有人把它挪成 preferredFlags 而静默
            // 选到非一致类型。
            int chosenFlags = context.memoryTypeFlags(memoryType);
            if (mapped && (chosenFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) == 0) {
                throw new IllegalStateException("Mapped buffers must live in HOST_COHERENT memory, but memory type "
                        + memoryType + " has flags 0x" + Integer.toHexString(chosenFlags)
                        + "; LavaFlow does not flush mapped ranges");
            }
            VkMemoryAllocateInfo allocation = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .allocationSize(requirements.size())
                    .memoryTypeIndex(memoryType);
            check(vkAllocateMemory(context.device(), allocation, null, out), "vkAllocateMemory(buffer)");
            allocatedMemory = out.get(0);
            memoryTypeIndex = memoryType;
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
        if (result != VK_SUCCESS) throw new IllegalStateException(VulkanException.describe(operation, result));
    }

    @Override public long size() { return size; }
    @Override public int usage() { return usage; }

    long handle() { return buffer; }
    long memory() { return memory; }
    int memoryTypeIndex() { return memoryTypeIndex; }

    synchronized void write(long offset, ByteBuffer source) {
        if (closed) throw new IllegalStateException("Buffer is closed");
        if ((usage() & GpuBuffer.USAGE_MAP_WRITE) == 0) throw new IllegalStateException("Buffer is not host writable");
        if (offset < 0 || offset + source.remaining() > size()) throw new IllegalArgumentException("Write exceeds buffer");
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pointer = stack.mallocPointer(1);
            check(vkMapMemory(context.device(), memory, offset, source.remaining(), 0, pointer), "vkMapMemory(write)");
            MemoryUtil.memCopy(MemoryUtil.memAddress(source) + source.position(), pointer.get(0), source.remaining());
            // 不做手工 flush。映射型 buffer 的 memory type 被 findMemoryType 强制要求 HOST_COHERENT
            // （见构造函数里的 requiredFlags），一致内存上的 vkFlushMappedMemoryRanges 按规范是 no-op，
            // 却仍要满足 VUID-VkMappedMemoryRange 的区间约束：size 必须是 nonCoherentAtomSize 的倍数，
            // 或 offset+size 恰好等于 VkDeviceMemory 的大小。writeToTexture 的字节数很少是 atom 的倍数，
            // 驱动又通常把 allocation 补齐到更大，于是这条 flush 会被 validation 报
            // VUID-VkMappedMemoryRange-size-01390。Minecraft 自带的 Vulkan 后端
            // （VMA + vmaMapMemory/vmaUnmapMemory）同样从不 flush，只靠一致内存。
            vkUnmapMemory(context.device(), memory);
        }
    }

    @Override public synchronized GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
        if (closed) throw new IllegalStateException("Buffer is closed");
        if (!read && !write) throw new IllegalArgumentException("At least read or write must be requested");
        if (read && (usage() & GpuBuffer.USAGE_MAP_READ) == 0) throw new IllegalStateException("Buffer is not readable");
        if (write && (usage() & GpuBuffer.USAGE_MAP_WRITE) == 0) throw new IllegalStateException("Buffer is not writable");
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

    @Override public synchronized boolean isClosed() { return closed; }

    @Override public synchronized void close() {
        if (closed) return;
        if (mappingCount != 0) throw new IllegalStateException("Cannot close a mapped buffer");
        closed = true;
        LavaFlowFrameStats.bufferRetired();
        device.invalidateDescriptorCache(buffer);
        device.defer(this::destroyNow);
    }

    private void destroyNow() {
        vkDestroyBuffer(context.device(), buffer, null);
        vkFreeMemory(context.device(), memory, null);
    }
}
