package dev.lavaflow.minecraft.vulkan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The F3 line reports the average GPU time and its share of the frame.
 *
 * <p>Worth a test without a device because everything here is arithmetic on a rolling window, and because
 * the two ways it can be wrong are both silent: a share computed the wrong way round reports a GPU-bound
 * frame as idle, which is the opposite of the decision the line exists to inform.
 *
 * <p>The window is deliberately over-filled: the ring buffer is shared with whatever else ran first in this
 * JVM, so a test that fed fewer samples than the window holds would be asserting on an average that includes
 * state it does not control.
 */
class LavaFlowGpuDebugLineTest {

    @Test
    void theLineCarriesTheAverageGpuTimeAndItsShareOfTheFrame() {
        for (int i = 0; i < 300; i++) {
            LavaFlowFrameStats.gpuFrameCompleted(10.0);
        }
        // Two calls: the first only records the instant the next interval is measured from.
        LavaFlowFrameStats.framePresented();
        LavaFlowFrameStats.framePresented();

        String line = LavaFlowFrameStats.debugLine();
        assertNotNull(line, "有样本时这一行不该是 null，否则 F3 上这一栏永远不出现");
        assertTrue(line.contains("10.00 ms"),
                "填满窗口的样本都是 10.0 ms，均值就该是 10.00 ms，实际: " + line);
        assertTrue(line.contains("GPU (LavaFlow)"), "这行必须标明来源，好与 MC 自己那栏区分: " + line);
        assertTrue(line.contains("%") && line.contains("frame"),
                "有帧窗口样本时要给出占帧比例（GPU 受限度），实际: " + line);
    }
}
