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
import dev.lavaflow.vulkan.VulkanException;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;

/** A directly mappable LavaFlow buffer with explicit Vulkan memory ownership. */
final class LavaFlowGpuBuffer extends GpuBuffer {
    private final LavaFlowDevice device;
    private static final System.Logger LOGGER = System.getLogger(LavaFlowGpuBuffer.class.getName());
    private final LavaFlowVulkanContext context;
    private final long buffer;
    private final long memory;
    private int mappingCount;
    // Physical base of the mapping such that address of buffer position x = mappedPhysicalBase + x.
    // Zero when the buffer is not mapped. Volatile so the render-thread draw path can read it without
    // a lock: map() and unmap() are synchronized but drawIndexedIndirect runs on the same thread.
    volatile long mappedPhysicalBase;
    // 主机内存是否是这块 buffer 当前内容的权威来源。drawIndexedIndirect 的“直接读主机内存”
    // 捷径依赖它：只有最后一次写入来自 write()/map(write=true) 时才成立。任何 vkCmdCopyBuffer
    // 写进这块 buffer 都把它清掉——那条 copy 只是被“记录”，录制时主机内存还是旧内容。
    private volatile boolean hostAuthoritative;
    private boolean closed;
    private boolean unreadableReported;

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
        if (result != VK_SUCCESS) throw new IllegalStateException(VulkanException.describe(operation, result));
    }

    long handle() { return buffer; }
    long memory() { return memory; }
    boolean hostAuthoritative() { return hostAuthoritative; }

    /**
     * Whether indirect parameters in this buffer can be read on the CPU: either a mapping is already open, or
     * the allocation is host-visible, in which case a mapping is opened on demand.
     *
     * <p>Minecraft's dynamic buffers are {@code MappableRingBuffer}s: the render thread opens a mapped view,
     * writes the frame's commands, and closes it again - the release callback unmaps, so
     * {@code mappedPhysicalBase} is back to zero by the time the buffer reaches drawIndexedIndirect. Requiring
     * an open mapping therefore meant the CPU expansion could never run: the 2026-10-09 device log shows 1149
     * split commands per frame against 0 expanded ones. (Recovered from the archived 26.3 branch, tag 3b854e1.)
     */
    static boolean canReadParameters(long mappedPhysicalBase, boolean hostVisible) {
        return mappedPhysicalBase != 0 || hostVisible;
    }

    /**
     * Whether the allocation behind this buffer is host-visible, which is the only thing that decides whether
     * the CPU can read its contents.
     *
     * <p>Deliberately not {@code GpuBuffer.USAGE_MAP_READ}: Minecraft's usage bits are an abstraction we
     * implement, not a Vulkan rule - {@code vkMapMemory} has no per-buffer usage restriction at all, so a
     * buffer Minecraft only ever maps for writing ({@code USAGE_MAP_WRITE}, memory HOST_VISIBLE|HOST_COHERENT)
     * is perfectly readable here. Requiring the read bit is what kept the CPU expansion at zero expanded
     * commands in the 2026-10-09 device log even after the mapping-it-ourselves fix.
     */
    boolean hostVisible() {
        return (usage() & (GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE)) != 0;
    }

    /**
     * Explains once per buffer why a batch cannot be read on the CPU and is therefore issued as one indirect
     * command per draw. The alternative is a silent slowdown, and which buffer it is and why is not something
     * a counter can say.
     */
    synchronized void reportUnreadableOnce(long offset, long length) {
        if (unreadableReported) return;
        unreadableReported = true;
        LOGGER.log(System.Logger.Level.INFO,
                "Indirect parameters are not CPU-readable: buffer=" + Long.toHexString(buffer)
                        + " size=" + size() + " read=" + offset + "+" + length + " usage=" + usage()
                        + " hostAuthoritative=" + hostAuthoritative
                        + "; issuing one indirect command per draw");
    }

    /**
     * Copies {@code length} bytes at {@code offset} into host memory for the CPU expansion path.
     *
     * <p>Prefers the mapping the caller already opened, and otherwise opens one for the duration of the copy:
     * that is the case the expansion actually meets, because a ring buffer's mapping is closed long before its
     * draw arrives. Returns false rather than throwing when the buffer cannot be read from the CPU at all,
     * because the caller has a correct fallback (one indirect command per draw) and a device-local buffer that
     * holds indirect parameters is not an error.
     *
     * <p>No invalidation is needed after mapping: host-visible allocations here require HOST_COHERENT, so the
     * copy sees whatever the render thread wrote.
     */
    synchronized boolean tryReadBack(long offset, long length, long destination) {
        if (mappedPhysicalBase != 0) {
            MemoryUtil.memCopy(mappedPhysicalBase + offset, destination, length);
            return true;
        }
        if (!hostVisible()) return false;
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pointer = stack.mallocPointer(1);
            if (vkMapMemory(context.device(), memory, offset, length, 0, pointer) != VK_SUCCESS) return false;
            try {
                MemoryUtil.memCopy(pointer.get(0), destination, length);
            } finally {
                vkUnmapMemory(context.device(), memory);
            }
            return true;
        }
    }
    void markGpuWritten() { hostAuthoritative = false; }

    /**
     * 主机侧视图，只给诊断用：常驻映射是同一块 VkDeviceMemory 的地址，主机能读回自己刚写进去的字节，
     * 于是拷贝进 GPU 缓冲之前就能把源字节查一遍，不必等一次 GPU 回读。
     */
    ByteBuffer mappedView(long offset, long length) {
        long base = mappedPhysicalBase;
        if (base == 0 || offset < 0 || length < 0 || offset + length > size()) return null;
        return MemoryUtil.memByteBuffer(base + offset, (int) length);
    }

    synchronized void write(long offset, ByteBuffer source) {
        if (closed) throw new IllegalStateException("Buffer is closed");
        if ((usage() & USAGE_MAP_WRITE) == 0) throw new IllegalStateException("Buffer is not host writable");
        if (offset < 0 || offset + source.remaining() > size()) throw new IllegalArgumentException("Write exceeds buffer");
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pointer = stack.mallocPointer(1);
            check(vkMapMemory(context.device(), memory, offset, source.remaining(), 0, pointer), "vkMapMemory(write)");
            // memAddress 给的就是 buffer 在 position() 处的地址（原始基址要 memAddress0），再补一次
            // position 就把偏移算了两遍：position 非 0 时拷走的是越过预期位置的数据。长度用 remaining()，
            // 即 [position, limit)，地址这边同样只需要 memAddress 本身。
            MemoryUtil.memCopy(MemoryUtil.memAddress(source), pointer.get(0), source.remaining());
            hostAuthoritative = true;
            // 不做手工 flush：映射型 buffer 的 memory type 被 findMemoryType 强制要求
            // HOST_COHERENT，一致内存上的 vkFlushMappedMemoryRanges 按规范是 no-op，而它的
            // 区间约束（size 为 nonCoherentAtomSize 的倍数，或 offset+size 恰好等于
            // VkDeviceMemory 的大小）在这种小块上传上很难同时满足，只会换来 validation 噪音。
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
            if (write) hostAuthoritative = true;
            mappedPhysicalBase = pointer.get(0) - offset;
            ByteBuffer data = MemoryUtil.memByteBuffer(pointer.get(0), (int)length);
            AtomicBoolean released = new AtomicBoolean();
            int watchedUsage = usage();
            return new GpuBufferSlice.MappedView(new GpuBufferSlice(this, offset, length), data, () -> {
                // 诊断：主机写完这一段后再扫一遍，查非有限值与量级离谱的坐标。
                if (write) LavaFlowDataWatch.onUpload("mapped write", watchedUsage, LavaFlowGpuBuffer.this, offset,
                        data.duplicate().position(0).limit(data.capacity()));
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
        if (LavaFlowDevice.DEBUG_BUFFERS) {
            LavaFlowDevice.recordDebug("buf close h=" + Long.toHexString(buffer) + " u=0x" + Integer.toHexString(usage())
                    + " sz=" + size() + " f=" + LavaFlowFrameStats.totalFrames());
        }
        LavaFlowFrameStats.bufferRetired();
        device.invalidateDescriptorCache(buffer);
        device.defer(this::destroyNow);
    }

    private void destroyNow() {
        if (LavaFlowDevice.DEBUG_BUFFERS) {
            LavaFlowDevice.recordDebug("buf destroy h=" + Long.toHexString(buffer) + " f=" + LavaFlowFrameStats.totalFrames());
        }
        vkDestroyBuffer(context.device(), buffer, null);
        vkFreeMemory(context.device(), memory, null);
    }
}
