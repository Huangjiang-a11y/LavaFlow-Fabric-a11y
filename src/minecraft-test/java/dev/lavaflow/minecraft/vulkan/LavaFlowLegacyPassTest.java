package dev.lavaflow.minecraft.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.displayAvailable;
import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.exposeVulkanLoaderToLwjgl;
import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.restore;
import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.setSwitches;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.sdl.SDLInit.SDL_INIT_VIDEO;
import static org.lwjgl.sdl.SDLInit.SDL_Init;
import static org.lwjgl.sdl.SDLInit.SDL_Quit;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_fragment_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_vertex_shader;

/**
 * 真机走的那条 fallback（非动态渲染）通道，能画、且不惊动验证层。
 *
 * <p>这台设备的画像决定它走哪条路：Mali-G76 报 {@code dynamicRendering=false}，所以
 * {@code LavaFlowRenderPass} 走的是建 render pass + framebuffer 的那一支。而 CI 里原先没有任何一条
 * 用例覆盖它——两条冒烟跑的是 {@code src/main} 那个独立 renderer（它自己 {@code vkCreateRenderPass}，
 * 跟 MC 后端无关），而驱动 {@code LavaFlowRenderPass} 的四个用例都没开这个开关，走的全是动态渲染。
 * 那条 VUID-VkRenderPassCreateInfo-pDependencies-00838 能潜伏下来正是因为这个：它每次建 render pass
 * 都会被报出来，只是从来没人建过。
 *
 * <p>路径证明写在最前面：{@code context.dynamicRendering()} 必须为 false。开关被改名、被写错、
 * 或者 context 没读到它时，这里会红，而不是让"0 条验证层告警"变成对另一条路的空过。
 * 反方向的对照由那四个不开开关的用例提供：它们仍在动态渲染上跑（lavapipe 支持），所以一个
 * "把动态渲染全关掉"的实现会在那边现形。
 *
 * <p>两次 begin 进同一个目标：legacy 支里第一次是 DONT_CARE、第二次走 "resume" 的 LOAD，
 * 建 render pass / 建 framebuffer / 附件数量与 clear 值个数对不上的问题都会在验证层现形。
 */
class LavaFlowLegacyPassTest {

    private static final int SIZE = 64;
    private static final int ATTACHMENT_USAGE =
            GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
    private static final String VERTEX_SOURCE =
            "#version 450\nvoid main() { gl_Position = vec4(0.0, 0.0, 0.0, 1.0); }";
    private static final String FRAGMENT_SOURCE =
            "#version 450\n"
                    + "layout(location = 0) out vec4 outColor;\n"
                    + "void main() { outColor = vec4(1.0); }";

    @Test
    void aFallbackRenderPassDrawsWithoutValidationFindings() throws Exception {
        assumeTrue(displayAvailable(), "需要显示服务器才能初始化 SDL 视频子系统（本地可用 xvfb-run）");
        Map<String, String> saved = setSwitches(Map.of(
                "lavaflow.forceLegacyRenderPass", "true",
                "lavaflow.validation", "true"));
        LavaFlowDevice device = null;
        LavaFlowGpuTexture color = null;
        LavaFlowGpuTexture depth = null;
        LavaFlowRenderPipeline pipeline = null;
        GpuTextureView colorView = null;
        GpuTextureView depthView = null;
        try {
            assumeTrue(SDL_Init(SDL_INIT_VIDEO), "SDL 视频子系统初始化失败");
            exposeVulkanLoaderToLwjgl();
            device = new LavaFlowDevice();
            LavaFlowVulkanContext context = device.context();
            assertFalse(context.dynamicRendering(),
                    "forceLegacyRenderPass 应关掉动态渲染：本用例要覆盖的正是 Mali 设备那条路，"
                            + "开关失效时这条必须红，否则下面那条断言就是空过");
            // 验证层不在时本用例的断言没有意义，跳过而不是给一个绿。CI 里那一步指名断言它跑了，
            // 所以这里的"跳过"在 CI 上等于红。
            assumeTrue(context.validationEnabled(),
                    "lavaflow.validation 被请求了，但这一层没装上：本用例的意义在于层真的在看");

            CommandEncoderBackend encoder = device.createCommandEncoder();
            color = new LavaFlowGpuTexture(device, ATTACHMENT_USAGE, "legacy pass color",
                    GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
            // 后端无条件开着深度测试，所以没有深度附件时验证层不接受这次绘制。
            depth = new LavaFlowGpuTexture(device, ATTACHMENT_USAGE, "legacy pass depth",
                    GpuFormat.D32_FLOAT, SIZE, SIZE, 1, 1);
            pipeline = (LavaFlowRenderPipeline) device.compilePipeline(createInfo()).finishCompile();
            colorView = device.createTextureView(color, 0, 1);
            depthView = device.createTextureView(depth, 0, 1);

            RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "legacy pass test")
                    .withColorAttachment(colorView, Optional.of(new Vector4f(0, 0, 0, 1)))
                    .withDepthAttachment(depthView, OptionalDouble.of(1.0))
                    .withRenderArea(new RenderPass.RenderArea(0, 0, SIZE, SIZE))
                    .build();

            RenderPassBackend first = encoder.createRenderPass(descriptor);
            first.setPipeline(pipeline);
            first.draw(3, 1, 0, 0);
            encoder.submitRenderPass();
            // 第二遍进同一个目标：legacy 支的 LOAD/resume 是另一条分支，不能只走 DONT_CARE 那条。
            RenderPassBackend second = encoder.createRenderPass(descriptor);
            second.setPipeline(pipeline);
            second.draw(3, 1, 0, 0);
            encoder.submitRenderPass();
            device.completePending();

            // 断言放在设备销毁之后：Object Tracking 的泄漏消息只在 vkDestroyDevice 那一刻冒出来，
            // 断言若写在 finally 之前的用例体里，对整个回收阶段都是瞎的。这里先把借走的还干净，
            // 再交设备，断言覆盖的是"两条 legacy 通道 + 全部回收"的完整生命周期。
            colorView.close(); colorView = null;
            depthView.close(); depthView = null;
            pipeline.close(); pipeline = null;
            color.close(); color = null;
            depth.close(); depth = null;
            device.completePending();
            device.close(); device = null;

            assertTrue(context.validationMessages().isEmpty(),
                    () -> "真机那条 fallback 通道（含回收全程）产生了验证层告警： "
                            + context.validationMessages());
        } finally {
            if (colorView != null) colorView.close();
            if (depthView != null) depthView.close();
            if (pipeline != null) pipeline.close();
            if (color != null) color.close();
            if (depth != null) depth.close();
            if (device != null) {
                device.completePending();
                device.close();
            }
            SDL_Quit();
            restore(saved);
        }
    }

    private static BackendRenderPipeline.CreateInfo createInfo() {
        return new BackendRenderPipeline.CreateInfo(
                "legacy pass test",
                List.of(
                        new BackendRenderPipeline.CreateInfo.Shader("vertex", "main",
                                new CompiledShader(
                                        LavaFlowShaderc.compile(VERTEX_SOURCE,
                                                shaderc_glsl_vertex_shader, "legacy-pass-vertex"),
                                        ShaderType.VERTEX)),
                        new BackendRenderPipeline.CreateInfo.Shader("fragment", "main",
                                new CompiledShader(
                                        LavaFlowShaderc.compile(FRAGMENT_SOURCE,
                                                shaderc_glsl_fragment_shader, "legacy-pass-fragment"),
                                        ShaderType.FRAGMENT))),
                List.of(),   // no vertex buffers: the shaders read no attributes
                List.of(),   // no attribute bindings
                List.of(),   // no uniforms: the fragment shader writes a constant
                0,           // push constants size
                DepthStencilState.DEFAULT,
                PolygonMode.FILL,
                false,       // cull
                List.of(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM,
                        ColorTargetState.WRITE_ALL)),
                PrimitiveTopology.TRIANGLES);
    }
}
