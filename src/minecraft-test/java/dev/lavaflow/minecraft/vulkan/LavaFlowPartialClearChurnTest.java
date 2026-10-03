package dev.lavaflow.minecraft.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.displayAvailable;
import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.exposeVulkanLoaderToLwjgl;
import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.restore;
import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.setSwitches;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.sdl.SDLInit.SDL_INIT_VIDEO;
import static org.lwjgl.sdl.SDLInit.SDL_Init;
import static org.lwjgl.sdl.SDLInit.SDL_Quit;

/**
 * A partial clear must not manufacture a pair of texture views per call.
 *
 * <p>That is what it did. A device run measured 13.3 partial clears per frame against 26.6 retired
 * views per frame — exactly double, every one of them labeled "UI items atlas" or "UI items atlas
 * depth", the pair {@code clearColorAndDepthTextures} clears. Two vkCreateImageView/vkDestroyImageView
 * pairs per rectangle fill, and nothing else in the backend churning views at all.
 *
 * <p>What is asserted is the count, not the mechanism, so the test survives a rewrite of how views get
 * reused. The second case covers the half of this change that can actually break something: a cached
 * view holds a view reference, so a texture that fails to release the views it caches never reaches
 * zero references and is never destroyed.
 *
 * <p>The validation layer is on: reusing one view across render passes turns a use-after-free into
 * something the layer can see rather than something only the device would.
 */
class LavaFlowPartialClearChurnTest {

    private static final int SIZE = 64;
    private static final int CLEARS = 64;
    private static final int ATTACHMENT_USAGE =
            GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
    private static final Vector4f CLEAR_COLOR = new Vector4f(0.1f, 0.2f, 0.3f, 1.0f);

    @Test
    void repeatedPartialClearsRetireNoViews() throws Exception {
        assumeTrue(displayAvailable(), "需要显示服务器才能初始化 SDL 视频子系统（本地可用 xvfb-run）");
        assertTrue(LavaFlowFrameStats.enabled(),
                "退役计数由 -Dlavaflow.frameStats=true 门控，关着时它恒为 0，这条断言会空过；"
                        + "属性只能设在 minecraftTest 任务上（见 build.gradle.kts）");
        Map<String, String> saved = setSwitches(Map.of("lavaflow.validation", "true"));
        LavaFlowDevice device = null;
        LavaFlowGpuTexture color = null;
        LavaFlowGpuTexture depth = null;
        try {
            assumeTrue(SDL_Init(SDL_INIT_VIDEO), "SDL 视频子系统初始化失败");
            exposeVulkanLoaderToLwjgl();
            device = new LavaFlowDevice();
            CommandEncoderBackend encoder = device.createCommandEncoder();
            color = new LavaFlowGpuTexture(device, ATTACHMENT_USAGE, "churn test color",
                    GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
            depth = new LavaFlowGpuTexture(device, ATTACHMENT_USAGE, "churn test depth",
                    GpuFormat.D32_FLOAT, SIZE, SIZE, 1, 1);

            long handle = color.cachedClearView(0).handle();
            long retiredBefore = LavaFlowFrameStats.retiredViewTotal();
            for (int i = 0; i < CLEARS; i++) {
                encoder.clearColorAndDepthTextures(color, CLEAR_COLOR, depth, 1.0,
                        i % 16, i % 16, 16, 16, 0);
            }

            assertEquals(0L, LavaFlowFrameStats.retiredViewTotal() - retiredBefore,
                    CLEARS + " 次局部清屏不该退役任何 view：退役数曾恰好是清屏次数的两倍");
            assertEquals(handle, color.cachedClearView(0).handle(),
                    "清屏要复用同一个 view 句柄，而不是每次现造一个");
        } finally {
            if (color != null) color.close();
            if (depth != null) depth.close();
            if (device != null) {
                device.completePending();
                device.close();
            }
            restore(saved);
            SDL_Quit();
        }
    }

    @Test
    void cachedClearViewsAreReleasedWithTheirTexture() throws Exception {
        assumeTrue(displayAvailable(), "需要显示服务器才能初始化 SDL 视频子系统（本地可用 xvfb-run）");
        Map<String, String> saved = setSwitches(Map.of("lavaflow.validation", "true"));
        LavaFlowDevice device = null;
        try {
            assumeTrue(SDL_Init(SDL_INIT_VIDEO), "SDL 视频子系统初始化失败");
            exposeVulkanLoaderToLwjgl();
            device = new LavaFlowDevice();
            LavaFlowGpuTexture texture = new LavaFlowGpuTexture(device, ATTACHMENT_USAGE, "lifecycle test",
                    GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
            LavaFlowGpuTextureView view = texture.cachedClearView(0);
            assertSame(view, texture.cachedClearView(0), "同一个 mip 上应复用同一个 view");
            assertFalse(view.isClosed());

            texture.close();
            assertTrue(view.isClosed(),
                    "纹理必须先解开自己缓存的 view：留着会让 views 永远不为 0，图像永远不会被销毁");
            device.completePending();
        } finally {
            if (device != null) device.close();
            restore(saved);
            SDL_Quit();
        }
    }
}
