package dev.lavaflow.minecraft.vulkan;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LavaFlowTransientLayoutTest {
    @Test
    void paddingBetweenSourcesIsCounted() {
        // 10 字节按 4 对齐后仍是 10，第二段从 12 开始，末端 22 —— 而不是 remaining 之和的 20。
        List<ByteBuffer> sources = List.of(ByteBuffer.allocate(10), ByteBuffer.allocate(10));
        assertEquals(22, LavaFlowTransientMemory.layoutSize(sources, 4));
    }

    @Test
    void alreadyAlignedSourcesNeedNoPadding() {
        List<ByteBuffer> sources = List.of(ByteBuffer.allocate(12), ByteBuffer.allocate(8));
        assertEquals(20, LavaFlowTransientMemory.layoutSize(sources, 4));
    }

    @Test
    void layoutCoversEveryByteTheCopyWalkWrites() {
        long[] lengths = {1, 3, 7, 10, 15, 31, 64};
        long alignment = 16;
        List<ByteBuffer> sources = new ArrayList<>();
        long end = 0;
        for (long length : lengths) {
            sources.add(ByteBuffer.allocate((int) length));
            long remainder = end % alignment;
            if (remainder != 0) end += alignment - remainder;
            end += length;
        }
        long layout = LavaFlowTransientMemory.layoutSize(sources, alignment);
        // 长度和是 131，真正的末端是 176：修复前最后 45 字节写在分配区间之外。
        assertEquals(176, layout);
        assertTrue(layout >= end);
    }
}
