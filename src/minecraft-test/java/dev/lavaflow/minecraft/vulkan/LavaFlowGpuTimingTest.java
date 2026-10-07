package dev.lavaflow.minecraft.vulkan;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.textures.GpuTexture;
import org.joml.Vector4f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.displayAvailable;
import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.exposeVulkanLoaderToLwjgl;
import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.restore;
import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.setSwitches;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.GLFW_CLIENT_API;
import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_NO_API;
import static org.lwjgl.glfw.GLFW.GLFW_VISIBLE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Frames that have finished are timed on the GPU, and the report has the number to prove it.
 *
 * <p>Every other counter in the frame report counts work the CPU records. Those numbers can all improve while
 * the frame time stands still, because the length of a frame is set by whichever side is slower — so the
 * report needs one number from the GPU side, or "we removed N commands per frame" is a claim about half of a
 * frame. That number comes from two timestamp queries written around each submit slot's recording and read
 * back once the slot's fence signals.
 *
 * <p>What this test pins is the wiring, not the value: that the pool is created, the timestamps are written,
 * and a finished slot is actually read. A sample that never arrives is exactly the failure that would look
 * like a healthy report — {@code gpu_ms} would read as "-" forever and nothing else would be wrong.
 */
class LavaFlowGpuTimingTest {

    private static final int SIZE = 16;

    @BeforeAll
    static void initGlfw() {
        assumeTrue(displayAvailable(), "需要显示服务器才能创建 GLFW 窗口（本地可用 xvfb-run）");
        LavaFlowTestSupport.ensureGlfw();
    }

    @Test
    void aFinishedFrameIsTimedOnTheGpu() throws Exception {
        assumeTrue(displayAvailable(), "需要显示服务器才能创建 GLFW 窗口（本地可用 xvfb-run）");
        assertTrue(LavaFlowFrameStats.enabled(),
                "计数由 -Dlavaflow.frameStats=true 门控，关着时恒为 0，这条断言就没有意义了；"
                        + "属性只能设在 minecraftTest 任务上（见 build.gradle.kts）");
        Map<String, String> saved = setSwitches(Map.of("lavaflow.validation", "true"));
        LavaFlowDevice device = null;
        LavaFlowGpuTexture target = null;
        long window = NULL;
        try {
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            window = glfwCreateWindow(320, 240, "LavaFlow gpu timing test", NULL, NULL);
            assertNotEquals(NULL, window, "无法创建 GLFW 窗口");
            exposeVulkanLoaderToLwjgl();
            // No pipeline is built in this test, so the shader source supplier is never asked for anything.
            device = new LavaFlowDevice(window, (id, type) -> "");
            assumeTrue(device.context().timestampsSupported(),
                    "该设备的图形队列不报告 timestamp 位数（设备日志里会有一条 INFO 说明），gpu_ms 本来就不会有值");
            target = new LavaFlowGpuTexture(device, GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                    "gpu timing test target", GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);

            long samplesBefore = LavaFlowFrameStats.gpuFramesTotal();
            double millisBefore = LavaFlowFrameStats.gpuMillisTotal();
            CommandEncoderBackend encoder = device.createCommandEncoder();
            // One submit per slot, plus one more: the extra submit prepares the first slot again, which is
            // where its fence is waited on and its pair of timestamps is finally read. Each frame clears a
            // texture so the GPU has something to time rather than an empty command buffer.
            for (int i = 0; i < 4; i++) {
                encoder.clearColorTexture(target, new Vector4f(0.0f, 0.0f, 0.0f, 1.0f));
                encoder.submit();
            }
            long samples = LavaFlowFrameStats.gpuFramesTotal() - samplesBefore;
            assertTrue(samples > 0,
                    "四个提交之后应当至少读回一个 GPU 时间样本：建了查询池却没人读，等于 gpu_ms 永远是 -");
            double averageMillis = (LavaFlowFrameStats.gpuMillisTotal() - millisBefore) / samples;
            assertTrue(averageMillis > 0 && averageMillis < 1000,
                    "样本的平均 GPU 时间应当在 (0, 1000) ms 之间，实际 " + averageMillis
                            + "：一个荒唐的大值通常意味着读回时步长或标志位写错，读到的是别的内存");
        } finally {
            if (target != null) target.close();
            if (device != null) {
                device.completePending();
                device.close();
            }
            if (window != NULL) glfwDestroyWindow(window);
            restore(saved);
        }
    }
}
