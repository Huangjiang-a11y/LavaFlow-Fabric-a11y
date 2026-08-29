package dev.lavaflow.minecraft.vulkan;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.TransientMemory;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

final class LavaFlowTransientMemory implements TransientMemory {
    private static final long BLOCK_SIZE = 16L * 1024L * 1024L;
    private static final int ALL_GPU_USAGES = GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC
            | GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_UNIFORM
            | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_INDIRECT_PARAMETERS;
    private static final Runnable NOOP = () -> {};

    private final LavaFlowDevice device;
    private final LavaFlowCommandEncoder encoder;
    private List<ByteBuffer> cpuAllocations = new ArrayList<>();
    private final List<MappedBlock> mappedBlocks = new ArrayList<>();
    private final List<GpuBlock> gpuBlocks = new ArrayList<>();
    private int mappedBlockIndex = -1;
    private int gpuBlockIndex = -1;

    private static final class MappedBlock {
        final LavaFlowGpuBuffer buffer;
        final GpuBufferSlice.MappedView mapping;
        long offset;
        boolean touched;
        long dirtyFrom = Long.MAX_VALUE;
        long dirtyTo = 0;

        MappedBlock(LavaFlowGpuBuffer buffer, GpuBufferSlice.MappedView mapping) {
            this.buffer = buffer;
            this.mapping = mapping;
        }
    }

    private static final class GpuBlock {
        final LavaFlowGpuBuffer buffer;
        long offset;

        GpuBlock(LavaFlowGpuBuffer buffer) {
            this.buffer = buffer;
        }
    }

    static final class Retired implements AutoCloseable {
        private final List<ByteBuffer> cpuAllocations;

        Retired(List<ByteBuffer> cpuAllocations) {
            this.cpuAllocations = cpuAllocations;
        }

        @Override public void close() {
            for (ByteBuffer allocation : cpuAllocations) MemoryUtil.memFree(allocation);
        }
    }

    LavaFlowTransientMemory(LavaFlowDevice device, LavaFlowCommandEncoder encoder) {
        this.device = device;
        this.encoder = encoder;
    }

    @Override public ByteBuffer allocateCpu(long size, long alignment, long submitIndex, long lifetime) {
        if (size > Integer.MAX_VALUE) throw new IllegalArgumentException("CPU allocation exceeds 2 GiB");
        ByteBuffer result = MemoryUtil.memAlloc((int)size);
        cpuAllocations.add(result);
        return result;
    }

    @Override public GpuBufferSlice.MappedView allocateStaging(long size, long alignment, int usage,
                                                               long submitIndex, long lifetime) {
        return allocateMapped(size, alignment);
    }

    @Override public GpuBufferSlice allocateGpu(long size, long alignment, int usage,
                                                long submitIndex, long lifetime) {
        return allocateDeviceLocal(size, alignment);
    }

    @Override public GpuBufferSlice.MappedView allocateGpuMapped(long size, long alignment, int usage,
                                                                 long submitIndex, long lifetime) {
        return allocateMapped(size, alignment);
    }

    @Override public GpuBufferSlice uploadStaging(List<ByteBuffer> sources, long alignment, int usage,
                                                  long submitIndex, long lifetime) {
        return uploadMapped(sources, alignment);
    }

    GpuBufferSlice uploadStaging(ByteBuffer source, long alignment) {
        GpuBufferSlice.MappedView allocation = allocateMapped(source.remaining(), alignment);
        MemoryUtil.memCopy(MemoryUtil.memAddress(source) + source.position(),
                MemoryUtil.memAddress(allocation.data()), source.remaining());
        return allocation.slice();
    }

    @Override public GpuBufferSlice uploadGpu(List<ByteBuffer> sources, long alignment, int usage,
                                              long submitIndex, long lifetime) {
        GpuBufferSlice staging = uploadMapped(sources, alignment);
        GpuBufferSlice destination = allocateDeviceLocal(staging.length(), alignment);
        encoder.copyToBuffer(staging, destination);
        return destination;
    }

    @Override public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> sources, long alignment, int usage) {
        List<GpuBufferSlice> result = new ArrayList<>(sources.size());
        for (ByteBuffer source : sources) result.add(uploadStaging(source, alignment));
        return result;
    }

    @Override public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> sources, long alignment, int usage) {
        if (sources.isEmpty()) return List.of();
        // 合并为单次大 staging 上传再分批 copy：把映射分配与帧末 flush 从 O(n) 降到 O(1)，
        // 与 flushMappedRanges 的 per-block 收窄协同（只产生一个 touched 的 mapped block）。
        // 每个子区间在 mapped 缓冲内按 reqAlign 对齐，等价于原各 source 独立 allocateMapped 的对齐行为，
        // 因而 copyToBuffer 的 src/dst 偏移仍满足设备对齐要求。
        long reqAlign = Math.max(1, alignment);
        long total = 0;
        for (ByteBuffer source : sources) total = Math.addExact(total, source.remaining());
        GpuBufferSlice.MappedView staging = allocateMapped(total, alignment);
        long base = MemoryUtil.memAddress(staging.data());
        long off = 0;
        List<GpuBufferSlice> result = new ArrayList<>(sources.size());
        for (ByteBuffer source : sources) {
            off = alignUp(off, reqAlign);
            int len = source.remaining();
            MemoryUtil.memCopy(MemoryUtil.memAddress(source) + source.position(), base + off, len);
            GpuBufferSlice deviceLocal = allocateDeviceLocal(len, alignment);
            GpuBufferSlice srcSlice = staging.slice().slice(off, len);
            encoder.copyToBuffer(srcSlice, deviceLocal);
            result.add(deviceLocal);
            off += len;
        }
        return result;
    }

    private GpuBufferSlice.MappedView allocateMapped(long size, long alignment) {
        if (size <= 0 || size > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid mapped allocation size");
        long requiredAlignment = Math.max(1, alignment);
        MappedBlock block = mappedBlockIndex < 0 ? null : mappedBlocks.get(mappedBlockIndex);
        long offset = block == null ? 0 : alignUp(block.offset, requiredAlignment);
        if (block == null || offset + size > block.buffer.size()) {
            int nextIndex = mappedBlockIndex + 1;
            if (nextIndex < mappedBlocks.size() && mappedBlocks.get(nextIndex).buffer.size() >= size) {
                block = mappedBlocks.get(nextIndex);
            } else {
                long capacity = Math.max(BLOCK_SIZE, alignUp(size, requiredAlignment));
                LavaFlowGpuBuffer buffer = (LavaFlowGpuBuffer)device.createBuffer(
                        () -> "LavaFlow transient mapped block",
                        ALL_GPU_USAGES | GpuBuffer.USAGE_MAP_WRITE, capacity);
                block = new MappedBlock(buffer, buffer.map(false, true));
                mappedBlocks.add(block);
                nextIndex = mappedBlocks.size() - 1;
            }
            mappedBlockIndex = nextIndex;
            offset = 0;
        }
        block.offset = offset + size;
        block.touched = true;
        if (offset < block.dirtyFrom) block.dirtyFrom = offset;
        if (offset + size > block.dirtyTo) block.dirtyTo = offset + size;
        ByteBuffer data = MemoryUtil.memByteBuffer(
                MemoryUtil.memAddress(block.mapping.data()) + offset, (int)size);
        return new GpuBufferSlice.MappedView(block.buffer.slice(offset, size), data, NOOP);
    }

    private GpuBufferSlice allocateDeviceLocal(long size, long alignment) {
        if (size <= 0) throw new IllegalArgumentException("Invalid GPU allocation size");
        long requiredAlignment = Math.max(1, alignment);
        GpuBlock block = gpuBlockIndex < 0 ? null : gpuBlocks.get(gpuBlockIndex);
        long offset = block == null ? 0 : alignUp(block.offset, requiredAlignment);
        if (block == null || offset + size > block.buffer.size()) {
            int nextIndex = gpuBlockIndex + 1;
            if (nextIndex < gpuBlocks.size() && gpuBlocks.get(nextIndex).buffer.size() >= size) {
                block = gpuBlocks.get(nextIndex);
            } else {
                long capacity = Math.max(BLOCK_SIZE, alignUp(size, requiredAlignment));
                LavaFlowGpuBuffer buffer = (LavaFlowGpuBuffer)device.createBuffer(
                        () -> "LavaFlow transient GPU block", ALL_GPU_USAGES, capacity);
                block = new GpuBlock(buffer);
                gpuBlocks.add(block);
                nextIndex = gpuBlocks.size() - 1;
            }
            gpuBlockIndex = nextIndex;
            offset = 0;
        }
        block.offset = offset + size;
        return block.buffer.slice(offset, size);
    }

    private GpuBufferSlice uploadMapped(List<ByteBuffer> sources, long alignment) {
        long size = 0;
        for (ByteBuffer source : sources) size = Math.addExact(size, source.remaining());
        GpuBufferSlice.MappedView allocation = allocateMapped(size, alignment);
        long destination = MemoryUtil.memAddress(allocation.data());
        long offset = 0;
        for (ByteBuffer source : sources) {
            int length = source.remaining();
            MemoryUtil.memCopy(MemoryUtil.memAddress(source) + source.position(), destination + offset, length);
            offset += length;
        }
        return allocation.slice();
    }

    void flushMappedRanges() {
        for (MappedBlock block : mappedBlocks) {
            if (block.touched && block.dirtyFrom < Long.MAX_VALUE) {
                block.buffer.flushMapped(block.dirtyFrom, block.dirtyTo - block.dirtyFrom);
            }
        }
    }

    Retired retire() {
        if (cpuAllocations.isEmpty()) return null;
        Retired retired = new Retired(cpuAllocations);
        cpuAllocations = new ArrayList<>();
        return retired;
    }

    void recycle() {
        for (MappedBlock block : mappedBlocks) { block.offset = 0; block.touched = false; block.dirtyFrom = Long.MAX_VALUE; block.dirtyTo = 0; }
        for (GpuBlock block : gpuBlocks) block.offset = 0;
        mappedBlockIndex = mappedBlocks.isEmpty() ? -1 : 0;
        gpuBlockIndex = gpuBlocks.isEmpty() ? -1 : 0;
    }

    void destroy() {
        Retired retired = retire();
        if (retired != null) retired.close();
        for (MappedBlock block : mappedBlocks) block.mapping.close();
        for (MappedBlock block : mappedBlocks) block.buffer.close();
        for (GpuBlock block : gpuBlocks) block.buffer.close();
        mappedBlocks.clear();
        gpuBlocks.clear();
        mappedBlockIndex = gpuBlockIndex = -1;
    }

    private static long alignUp(long value, long alignment) {
        long remainder = value % alignment;
        return remainder == 0 ? value : Math.addExact(value, alignment - remainder);
    }
}
