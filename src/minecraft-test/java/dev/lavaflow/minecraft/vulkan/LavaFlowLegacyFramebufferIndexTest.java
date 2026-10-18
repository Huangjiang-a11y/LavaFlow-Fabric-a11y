package dev.lavaflow.minecraft.vulkan;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.displayAvailable;
import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.exposeVulkanLoaderToLwjgl;
import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.restore;
import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.setSwitches;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import static org.lwjgl.vulkan.VK10.*;

/**
 * legacy framebuffer 缓存的退役只碰点名了该 view 的条目。
 *
 * <p>这条路径原先靠整表扫描：{@code releaseLegacyFramebuffers} 遍历 {@code legacyFramebuffers}
 * 找 {@code key.contains(view)}。改为反向索引后行为必须一分不差——所以这里断言的是两件事：
 * 点名了该 view 的条目（含多附件的那条）必须退役，没点名它的那条必须原地不动。只断言前者的
 * 话，一个"退役时清空整张表"的实现照样全绿，而那正是缓存存在的意义被丢掉的样子。
 *
 * <p>观察点是句柄身份：条目还在时重复请求命中缓存、拿回同一个 VkFramebuffer；条目退役后再请求
 * 只能新建一个。这样不必把私有表开出来给测试看。
 *
 * <p>26.2 的差别只在搭建设式：事件窗口由 GLFW 出（不是 SDL），设备构造要窗口与 ShaderSource，
 * 包名是 {@code com.mojang.blaze3d.*}。被断言的退役行为三版本一致。
 */
class LavaFlowLegacyFramebufferIndexTest {

    private static final int SIZE = 64;
    private static final int ATTACHMENT_USAGE =
            GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;

    @BeforeAll
    static void initGlfw() {
        assumeTrue(displayAvailable(), "需要显示服务器才能创建 GLFW 窗口（本地可用 xvfb-run）");
        LavaFlowTestSupport.ensureGlfw();
    }

    @Test
    void releasingOneViewRetiresOnlyTheFramebuffersThatNameIt() throws Exception {
        assumeTrue(displayAvailable(), "需要显示服务器才能创建 GLFW 窗口（本地可用 xvfb-run）");
        Map<String, String> saved = setSwitches(Map.of("lavaflow.validation", "true"));
        LavaFlowDevice device = null;
        LavaFlowGpuTexture a = null;
        LavaFlowGpuTexture b = null;
        GpuTextureView viewA = null;
        GpuTextureView viewB = null;
        long window = NULL;
        try {
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            window = glfwCreateWindow(320, 240, "LavaFlow legacy framebuffer index test", NULL, NULL);
            assertNotEquals(NULL, window, "无法创建 GLFW 窗口");
            exposeVulkanLoaderToLwjgl();
            // 本用例不建管线，着色器源供应者不会被问到。
            device = new LavaFlowDevice(window, (id, type) -> "");
            LavaFlowVulkanContext context = device.context();
            a = new LavaFlowGpuTexture(device, ATTACHMENT_USAGE, "legacy index A",
                    GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
            b = new LavaFlowGpuTexture(device, ATTACHMENT_USAGE, "legacy index B",
                    GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
            viewA = device.createTextureView(a, 0, 1);
            viewB = device.createTextureView(b, 0, 1);
            long handleA = ((LavaFlowGpuTextureView) viewA).handle();
            long handleB = ((LavaFlowGpuTextureView) viewB).handle();

            int format = LavaFlowVk.format(GpuFormat.RGBA8_UNORM);
            long singleAttachment = context.legacyRenderPass(new int[]{format},
                    new int[]{VK_ATTACHMENT_LOAD_OP_DONT_CARE}, VK_FORMAT_UNDEFINED, 0);
            long twoAttachments = context.legacyRenderPass(new int[]{format, format},
                    new int[]{VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_LOAD_OP_DONT_CARE},
                    VK_FORMAT_UNDEFINED, 0);

            long framebufferA = context.legacyFramebuffer(singleAttachment, new long[]{handleA}, SIZE, SIZE);
            assertEquals(framebufferA, context.legacyFramebuffer(singleAttachment, new long[]{handleA}, SIZE, SIZE),
                    "同一组附件应命中缓存：句柄都变了，说明缓存根本没建立，后面的断言也就无从谈起");
            long framebufferB = context.legacyFramebuffer(singleAttachment, new long[]{handleB}, SIZE, SIZE);
            long framebufferBoth = context.legacyFramebuffer(twoAttachments, new long[]{handleA, handleB}, SIZE, SIZE);
            assertNotEquals(framebufferA, framebufferB, "附件不同不应共用条目");
            assertEquals(framebufferBoth,
                    context.legacyFramebuffer(twoAttachments, new long[]{handleA, handleB}, SIZE, SIZE),
                    "多附件条目同样应命中缓存");

            context.releaseLegacyFramebuffers(handleA);

            assertNotEquals(framebufferA,
                    context.legacyFramebuffer(singleAttachment, new long[]{handleA}, SIZE, SIZE),
                    "点名了 A 的条目必须退役：命中并摘链是这次改动的正路");
            assertNotEquals(framebufferBoth,
                    context.legacyFramebuffer(twoAttachments, new long[]{handleA, handleB}, SIZE, SIZE),
                    "多附件条目也点名了 A，必须一并退役——它是反向索引里比单附件更容易漏的一类");
            // 负面对照：只点名 B 的那条必须留下。少了它，一个"退役即清空"的实现也能全绿。
            assertEquals(framebufferB,
                    context.legacyFramebuffer(singleAttachment, new long[]{handleB}, SIZE, SIZE),
                    "与 A 无关的条目必须原地不动，否则退役退化成了清空");
        } finally {
            if (viewA != null) viewA.close();
            if (viewB != null) viewB.close();
            if (a != null) a.close();
            if (b != null) b.close();
            if (device != null) {
                device.completePending();
                LavaFlowVulkanContext context = device.context();
                if (context.validationEnabled()) {
                    assertEquals(0, context.validationMessages().size(),
                            () -> "本用例产生了验证层告警（多为句柄被重复销毁或引用已亡视图）："
                                    + context.validationMessages());
                }
                device.close();
            }
            if (window != NULL) glfwDestroyWindow(window);
            restore(saved);
        }
    }
}
