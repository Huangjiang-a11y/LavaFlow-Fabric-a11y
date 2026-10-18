package dev.lavaflow.minecraft.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.displayAvailable;
import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.exposeVulkanLoaderToLwjgl;
import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.restore;
import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.setSwitches;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.sdl.SDLInit.SDL_INIT_VIDEO;
import static org.lwjgl.sdl.SDLInit.SDL_Init;
import static org.lwjgl.sdl.SDLInit.SDL_Quit;
import static org.lwjgl.vulkan.VK10.*;

/**
 * legacy framebuffer 缓存的退役只碰点名了该 view 的条目。
 *
 * <p>这条路径原先靠整表扫描：{@code releaseLegacyFramebuffers} 遍历 {@code legacyFramebuffers} 找
 * {@code key.contains(view)}。改为反向索引后，行为必须一分不差——所以这里断言的是**两件事**：
 * 点名了该 view 的条目（含多附件的那条）必须退役，没点名它的那条必须原地不动。只断言前者的话，
 * 一个"退役时清空整张表"的实现照样全绿，而那正是缓存存在的意义被丢掉的样子。
 *
 * <p>观察点是句柄身份：条目还在时重复请求会命中缓存、拿回同一个 VkFramebuffer；条目退役后再请求
 * 只能新建一个，句柄必然不同。这样不必把私有表开出来给测试看。
 *
 * <p>本用例走真实设备与验证层，无 {@code DISPLAY} 时跳过（本地 {@code xvfb-run}）。
 */
class LavaFlowLegacyFramebufferIndexTest {

    private static final int SIZE = 64;
    private static final int ATTACHMENT_USAGE =
            GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;

    @Test
    void releasingOneViewRetiresOnlyTheFramebuffersThatNameIt() throws Exception {
        assumeTrue(displayAvailable(), "需要显示服务器才能初始化 SDL 视频子系统（本地可用 xvfb-run）");
        Map<String, String> saved = setSwitches(Map.of("lavaflow.validation", "true"));
        LavaFlowDevice device = null;
        LavaFlowGpuTexture a = null;
        LavaFlowGpuTexture b = null;
        GpuTextureView viewA = null;
        GpuTextureView viewB = null;
        try {
            assumeTrue(SDL_Init(SDL_INIT_VIDEO), "SDL 视频子系统初始化失败");
            exposeVulkanLoaderToLwjgl();
            device = new LavaFlowDevice();
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
            SDL_Quit();
            restore(saved);
        }
    }
}
