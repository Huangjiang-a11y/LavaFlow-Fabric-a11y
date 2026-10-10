package dev.lavaflow.minecraft.vulkan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 空命令的判据是纯函数，钉住它。
 *
 * <p>这条判据一翻错就是"画面缺东西"而不是崩溃：CPU 展开时参数本来就在手里，判成空的命令会被直接丢掉。
 * 所以正反两边都要断言——零顶点/零实例是空命令，正常命令绝不是。
 */
class LavaFlowIndirectPathTest {

    @Test void parametersAreReadableWhenMappedOrWhenTheBufferIsReadable() {
        assertTrue(LavaFlowGpuBuffer.canReadParameters(0x1000L, false), "已经映射着 → 直接读");
        assertTrue(LavaFlowGpuBuffer.canReadParameters(0L, true), "缓冲可读 → 我们临时映射一下再读");
        assertFalse(LavaFlowGpuBuffer.canReadParameters(0L, false), "既没映射又不可读 → 只能退回逐条间接");
    }

    @Test void aCommandThatDrawsNothingIsRecognised() {
        assertTrue(LavaFlowRenderPass.drawsNothing(0, 1), "indexCount=0 是空命令");
        assertTrue(LavaFlowRenderPass.drawsNothing(1, 0), "instanceCount=0 是空命令");
        assertTrue(LavaFlowRenderPass.drawsNothing(-1, 4));
        // 负面对照：正常命令被当成空命令吞掉 = 画面缺东西，且不会崩，最难查
        assertFalse(LavaFlowRenderPass.drawsNothing(1, 1));
        assertFalse(LavaFlowRenderPass.drawsNothing(36, 12));
        assertFalse(LavaFlowRenderPass.drawsNothing(6, 1));
    }
}
