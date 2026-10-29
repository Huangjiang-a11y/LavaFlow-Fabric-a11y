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
    private int liveViews;
    // Physical base of the buffer's single persistent mapping, such that the address of buffer position x
    // is mappedPhysicalBase + x. Zero until the buffer is first mapped and only cleared by close(). Volatile
    // so the render-thread draw path can read it without a lock: write()/map() are synchronized but
    // drawIndexedIndirect runs on the same thread.
    volatile long mappedPhysicalBase;
    // 主机内存是否是这块 buffer 当前内容的权威来源。drawIndexedIndirect 的“直接读主机内存”
    // 捷径依赖它：只有最后一次写入来自 write()/map(write=true) 时才成立。任何 vkCmdCopyBuffer
    // 写进这块 buffer 都把它清掉——那条 copy 只是被“记录”，录制时主机内存还是旧内容。
    private volatile boolean hostAuthoritative;
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
    boolean hostAuthoritative() { return hostAuthoritative; }
    void markGpuWritten() { hostAuthoritative = false; }

    /**
     * 已映射时返回这段区间的只读视图，未映射（或区间不合法）时返回 null。
     *
     * <p>只给诊断用：常驻映射是同一块 VkDeviceMemory 的地址，主机能读回自己刚写进去的字节，于是
     * 拷贝进 GPU 缓冲之前就能把源字节查一遍，不必等一次 GPU 回读。
     */
    ByteBuffer mappedView(long offset, long length) {
        long base = mappedPhysicalBase;
        if (base == 0 || offset < 0 || length < 0 || offset + length > size) return null;
        return MemoryUtil.memByteBuffer(base + offset, (int) length);
    }

    synchronized void write(long offset, ByteBuffer source) {
        if (closed) throw new IllegalStateException("Buffer is closed");
        if ((usage() & GpuBuffer.USAGE_MAP_WRITE) == 0) throw new IllegalStateException("Buffer is not host writable");
        if (offset < 0 || offset + source.remaining() > size()) throw new IllegalArgumentException("Write exceeds buffer");
        // 写进常驻映射，不再每次 vkMapMemory/vkUnmapMemory 一进一出。
        // 不做手工 flush：映射型 buffer 的 memory type 被 findMemoryType 强制要求 HOST_COHERENT
        // （见构造函数里的 requiredFlags），一致内存上的 vkFlushMappedMemoryRanges 按规范是 no-op，
        // 却仍要满足 VUID-VkMappedMemoryRange 的区间约束：size 必须是 nonCoherentAtomSize 的倍数，
        // 或 offset+size 恰好等于 VkDeviceMemory 的大小。写侧上传的字节数很少是 atom 的倍数，驱动又
        // 通常把 allocation 补齐到更大，于是这条 flush 会被 validation 报
        // VUID-VkMappedMemoryRange-size-01390。Minecraft 自带的 Vulkan 后端
        // （VMA + vmaMapMemory/vmaUnmapMemory）同样从不 flush，只靠一致内存。
        long base = ensureMapped();
        // memAddress 给的就是 buffer 在 position() 处的地址（原始基址要 memAddress0），补第二次
        // position 就把偏移算了两遍：position 非 0 时拷走的是越过预期位置的数据。长度用的是
        // remaining()，即 [position, limit)，地址这边同样只需要 memAddress 本身。
        MemoryUtil.memCopy(MemoryUtil.memAddress(source), base + offset, source.remaining());
        hostAuthoritative = true;
    }

    @Override public synchronized GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
        if (closed) throw new IllegalStateException("Buffer is closed");
        if (!read && !write) throw new IllegalArgumentException("At least read or write must be requested");
        if (read && (usage() & GpuBuffer.USAGE_MAP_READ) == 0) throw new IllegalStateException("Buffer is not readable");
        if (write && (usage() & GpuBuffer.USAGE_MAP_WRITE) == 0) throw new IllegalStateException("Buffer is not writable");
        if (offset < 0 || length < 0 || offset + length > size()) throw new IllegalArgumentException("Invalid mapped range");
        if (length > Integer.MAX_VALUE) throw new IllegalArgumentException("Mappings larger than 2 GiB are unsupported");
        // 与 write() 共用同一个常驻映射：一块 VkDeviceMemory 同时只能有一个映射，所以这里不再自己
        // vkMapMemory，只按 offset 取视图。视图可以同时开多个，各自 close 时只减计数。
        long base = ensureMapped();
        ByteBuffer data = MemoryUtil.memByteBuffer(base + offset, (int) length);
        if (write) hostAuthoritative = true;
        liveViews++;
        AtomicBoolean released = new AtomicBoolean();
        int watchedUsage = usage();
        long watchedHandle = buffer;
        return new GpuBufferSlice.MappedView(new GpuBufferSlice(this, offset, length), data, () -> {
            // 诊断：主机写完这一段后再扫一遍，查非有限值与量级离谱的坐标。
            if (write) LavaFlowDataWatch.onUpload("mapped write", watchedUsage, LavaFlowGpuBuffer.this, offset,
                    data.duplicate().position(0).limit(data.capacity()));
            if (released.compareAndSet(false, true)) {
                synchronized (LavaFlowGpuBuffer.this) { liveViews--; }
            }
        });
    }

    /**
     * Establishes the buffer's single persistent mapping and returns its base.
     *
     * <p>Vulkan allows one mapping per VkDeviceMemory, so {@code write} and {@code map} share this one
     * rather than each mapping a range of its own. It covers the whole buffer, is created on first use,
     * and is released by {@link #close()}. It also makes {@link #mappedPhysicalBase} meaningful for the
     * indirect-draw fallback: once the buffer has been written from the host, that view holds the same
     * bytes the GPU reads, because every mappable buffer is HOST_COHERENT.
     */
    private long ensureMapped() {
        long base = mappedPhysicalBase;
        if (base != 0) return base;
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pointer = stack.mallocPointer(1);
            check(vkMapMemory(context.device(), memory, 0, size, 0, pointer), "vkMapMemory");
            base = pointer.get(0);
        }
        mappedPhysicalBase = base;
        return base;
    }

    @Override public synchronized boolean isClosed() { return closed; }

    @Override public synchronized void close() {
        if (closed) return;
        if (liveViews != 0) throw new IllegalStateException("Cannot close a buffer with an open mapping");
        closed = true;
        if (LavaFlowDevice.DEBUG_BUFFERS) {
            LavaFlowDevice.recordDebug("buf close h=" + Long.toHexString(buffer) + " u=0x" + Integer.toHexString(usage)
                    + " sz=" + size + " f=" + LavaFlowFrameStats.totalFrames());
        }
        if (mappedPhysicalBase != 0) {
            vkUnmapMemory(context.device(), memory);
            mappedPhysicalBase = 0;
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
