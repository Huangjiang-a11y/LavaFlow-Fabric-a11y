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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * A partial clear must not manufacture a pair of texture views per call — the 26.3 fix, backported.
 *
 * <p>The measurement behind it: 13.3 partial clears per frame against 26.6 retired views per frame,
 * exactly double, every one of them labeled "UI items atlas" or "UI items atlas depth". This is the same
 * code on both branches (26.2 clears mip 0 only, so it caches one view where 26.3 caches a view per mip),
 * so the rule holds here for the same reason.
 *
 * <p>The second case covers the half that can break something: a cached view holds a view reference, so a
 * texture that fails to release the view it caches never reaches zero references and is never destroyed.
 */
class LavaFlowPartialClearChurnTest {

    private static final int SIZE = 64;
    private static final int CLEARS = 64;
    private static final int ATTACHMENT_USAGE =
            GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
    private static final Vector4f CLEAR_COLOR = new Vector4f(0.1f, 0.2f, 0.3f, 1.0f);

    @BeforeAll
    static void initGlfw() {
        assumeTrue(displayAvailable(), "需要显示服务器才能创建窗口（本地可用 xvfb-run）");
        LavaFlowTestSupport.ensureGlfw();
    }

    @Test
    void repeatedPartialClearsRetireNoViews() throws Exception {
        assumeTrue(displayAvailable(), "需要显示服务器才能创建 GLFW 窗口（本地可用 xvfb-run）");
        assertTrue(LavaFlowFrameStats.enabled(),
                "退役计数由 -Dlavaflow.frameStats=true 门控，关着时它恒为 0，这条断言会空过；"
                        + "属性只能设在 minecraftTest 任务上（见 build.gradle.kts）");
        Map<String, String> saved = setSwitches(Map.of("lavaflow.validation", "true"));
        LavaFlowDevice device = null;
        LavaFlowGpuTexture color = null;
        LavaFlowGpuTexture depth = null;
        long window = NULL;
        try {
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            window = glfwCreateWindow(320, 240, "LavaFlow partial clear churn test", NULL, NULL);
            assertNotEquals(NULL, window, "无法创建 GLFW 窗口");
            exposeVulkanLoaderToLwjgl();
            device = new LavaFlowDevice(window, (id, type) -> null);
            CommandEncoderBackend encoder = device.createCommandEncoder();
            color = new LavaFlowGpuTexture(device, ATTACHMENT_USAGE, "churn test color",
                    GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
            depth = new LavaFlowGpuTexture(device, ATTACHMENT_USAGE, "churn test depth",
                    GpuFormat.D32_FLOAT, SIZE, SIZE, 1, 1);

            long handle = color.cachedClearView().handle();
            long retiredBefore = LavaFlowFrameStats.retiredViewTotal();
            for (int i = 0; i < CLEARS; i++) {
                encoder.clearColorAndDepthTextures(color, CLEAR_COLOR, depth, 1.0, i % 16, i % 16, 16, 16);
            }

            assertEquals(0L, LavaFlowFrameStats.retiredViewTotal() - retiredBefore,
                    CLEARS + " 次局部清屏不该退役任何 view：退役数曾恰好是清屏次数的两倍");
            assertEquals(handle, color.cachedClearView().handle(),
                    "清屏要复用同一个 view 句柄，而不是每次现造一个");
        } finally {
            if (color != null) color.close();
            if (depth != null) depth.close();
            if (device != null) {
                device.completePending();
                device.close();
            }
            if (window != NULL) glfwDestroyWindow(window);
            restore(saved);
        }
    }

    @Test
    void cachedClearViewsAreReleasedWithTheirTexture() throws Exception {
        assumeTrue(displayAvailable(), "需要显示服务器才能创建 GLFW 窗口（本地可用 xvfb-run）");
        Map<String, String> saved = setSwitches(Map.of("lavaflow.validation", "true"));
        LavaFlowDevice device = null;
        long window = NULL;
        try {
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            window = glfwCreateWindow(320, 240, "LavaFlow partial clear lifecycle test", NULL, NULL);
            assertNotEquals(NULL, window, "无法创建 GLFW 窗口");
            exposeVulkanLoaderToLwjgl();
            device = new LavaFlowDevice(window, (id, type) -> null);
            LavaFlowGpuTexture texture = new LavaFlowGpuTexture(device, ATTACHMENT_USAGE, "lifecycle test",
                    GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
            LavaFlowGpuTextureView view = texture.cachedClearView();
            assertSame(view, texture.cachedClearView(), "同一纹理上应复用同一个 view");
            assertFalse(view.isClosed());

            texture.close();
            assertTrue(view.isClosed(),
                    "纹理必须先解开自己缓存的 view：留着会让 views 永远不为 0，图像永远不会被销毁");
            device.completePending();
        } finally {
            if (device != null) device.close();
            if (window != NULL) glfwDestroyWindow(window);
            restore(saved);
        }
    }
}
