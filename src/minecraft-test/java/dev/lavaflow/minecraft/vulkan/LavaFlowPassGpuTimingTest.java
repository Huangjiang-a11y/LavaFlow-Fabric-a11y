package dev.lavaflow.minecraft.vulkan;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Vector4f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.displayAvailable;
import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.exposeVulkanLoaderToLwjgl;
import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.restore;
import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.setSwitches;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Per-pass GPU time is measured on the device and lands under the pass's own label.
 *
 * <p>{@code gpu_ms} says how much of the frame the GPU took; it cannot say which pass took it, so any
 * attempt to optimize a specific pass is a guess. This test pins the wiring of the per-pass queries: a
 * pass that begins and ends produces a sample under its label, the sample is a plausible duration, and the
 * switch actually turns the queries off — without the negative control, a build that silently stopped
 * writing timestamps would still look healthy as long as some other test kept the counters warm.
 */
class LavaFlowPassGpuTimingTest {

    private static final int SIZE = 16;
    private static final String LABEL = "pass timing test";

    @BeforeAll
    static void initGlfw() {
        assumeTrue(displayAvailable(), "需要显示服务器才能创建 GLFW 窗口（本地可用 xvfb-run）");
        LavaFlowTestSupport.ensureGlfw();
    }

    @Test
    void aFinishedPassIsTimedOnTheGpu() throws Exception {
        assumeTrue(displayAvailable(), "需要显示服务器才能创建 GLFW 窗口（本地可用 xvfb-run）");
        assertTrue(LavaFlowFrameStats.enabled(),
                "逐通道计时搭在帧统计上，-Dlavaflow.frameStats=true 关着时不会有样本；"
                        + "属性只能设在 minecraftTest 任务上（见 build.gradle.kts）");
        // Per-pass timing is opt-in: it costs a few percent of GPU time, so the test has to ask for it the
        // same way a device run does.
        Map<String, String> saved = setSwitches(Map.of("lavaflow.passGpuTiming", "true"));
        LavaFlowDevice device = null;
        LavaFlowGpuTexture target = null;
        GpuTextureView view = null;
        long window = NULL;
        try {
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            window = glfwCreateWindow(320, 240, "LavaFlow pass timing test", NULL, NULL);
            assertNotEquals(NULL, window, "无法创建 GLFW 窗口");
            exposeVulkanLoaderToLwjgl();
            device = new LavaFlowDevice(window, (id, type) -> "");
            assumeTrue(device.context().timestampsSupported(),
                    "该设备的图形队列不报告 timestamp 位数（设备日志里会有一条 INFO 说明），逐通道时间本来就不会有值");
            target = new LavaFlowGpuTexture(device,
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                    "pass timing target", GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
            view = device.createTextureView(target, 0, 1);
            RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> LABEL)
                    .withColorAttachment(view, Optional.of(new Vector4f(0.0f, 0.0f, 0.0f, 1.0f)))
                    .withRenderArea(new RenderPass.RenderArea(0, 0, SIZE, SIZE));

            long samplesBefore = LavaFlowFrameStats.timedPassSamples();
            CommandEncoderBackend encoder = device.createCommandEncoder();
            // Four submits: the pass is recorded each time, and the fourth reuses a slot, which is where
            // that slot's fence has signalled and its pass timestamps are finally read.
            for (int i = 0; i < 4; i++) {
                encoder.createRenderPass(descriptor);
                encoder.submitRenderPass();
                encoder.submit();
            }
            long samples = LavaFlowFrameStats.timedPassSamples() - samplesBefore;
            assertTrue(samples > 0,
                    "四个提交之后应当至少读回一个逐通道样本：建了查询池却没人读，"
                            + "pass_gpu_ms 会永远缺席，而报告其余部分看起来完全正常");
            double millis = LavaFlowFrameStats.passGpuMillis(LABEL);
            assertTrue(millis > 0 && millis < 1000,
                    "标签为 \"" + LABEL + "\" 的通道平均 GPU 时间应在 (0, 1000) ms 之间，实际 " + millis
                            + "：荒唐的大值通常意味着读回步长或标志位写错，读到的是别的内存");
            assertTrue(LavaFlowFrameStats.passGpuFrameTotal() > 0,
                    "每帧通道总时长也应当有值：它是 outside 的分母，缺失时 outside 会显示成整个 gpu_ms");
        } finally {
            if (view != null) view.close();
            if (target != null) target.close();
            if (device != null) {
                device.completePending();
                device.close();
            }
            if (window != NULL) glfwDestroyWindow(window);
            restore(saved);
        }
    }

    @Test
    void passTimingIsOffUnlessAsked() throws Exception {
        assumeTrue(displayAvailable(), "需要显示服务器才能创建 GLFW 窗口（本地可用 xvfb-run）");
        assertTrue(LavaFlowFrameStats.enabled(), "-Dlavaflow.frameStats=true 未启用时这条对照没有意义");
        LavaFlowDevice device = null;
        LavaFlowGpuTexture target = null;
        GpuTextureView view = null;
        long window = NULL;
        try {
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            window = glfwCreateWindow(320, 240, "LavaFlow pass timing off test", NULL, NULL);
            assertNotEquals(NULL, window, "无法创建 GLFW 窗口");
            exposeVulkanLoaderToLwjgl();
            device = new LavaFlowDevice(window, (id, type) -> "");
            assumeTrue(device.context().timestampsSupported(),
                    "该设备不能计时，有没有开关都不会有样本");
            target = new LavaFlowGpuTexture(device,
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                    "pass timing off target", GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
            view = device.createTextureView(target, 0, 1);
            RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> LABEL)
                    .withColorAttachment(view, Optional.of(new Vector4f(0.0f, 0.0f, 0.0f, 1.0f)))
                    .withRenderArea(new RenderPass.RenderArea(0, 0, SIZE, SIZE));

            long samplesBefore = LavaFlowFrameStats.timedPassSamples();
            CommandEncoderBackend encoder = device.createCommandEncoder();
            for (int i = 0; i < 4; i++) {
                encoder.createRenderPass(descriptor);
                encoder.submitRenderPass();
                encoder.submit();
            }
            assertEquals(samplesBefore, LavaFlowFrameStats.timedPassSamples(),
                    "默认不开时不应产生逐通道样本（需显式 -Dlavaflow.passGpuTiming=true）；"
                            + "样本照常增长说明默认值或开关读取被改坏，性能会被诊断本身拖下去");
        } finally {
            if (view != null) view.close();
            if (target != null) target.close();
            if (device != null) {
                device.completePending();
                device.close();
            }
            if (window != NULL) glfwDestroyWindow(window);
        }
    }
}
