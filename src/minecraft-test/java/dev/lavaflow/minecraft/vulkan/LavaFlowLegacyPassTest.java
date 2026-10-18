package dev.lavaflow.minecraft.vulkan;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.displayAvailable;
import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.exposeVulkanLoaderToLwjgl;
import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.restore;
import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.setSwitches;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 *
 * <p>26.2 的差别只在搭建设式：事件窗口由 GLFW 出（不是 SDL），管线要经设备的 ShaderSource 解析、
 * uniform 按名字寻址，包名是 {@code com.mojang.blaze3d.*}。被覆盖的那条分支三版本一致。
 */
class LavaFlowLegacyPassTest {

    private static final int SIZE = 64;
    private static final int ATTACHMENT_USAGE =
            GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
    private static final Identifier VERTEX_ID = Identifier.parse("lavaflow_legacy_pass_test:vertex");
    private static final Identifier FRAGMENT_ID = Identifier.parse("lavaflow_legacy_pass_test:fragment");

    private static final String VERTEX_SOURCE =
            "#version 450\nvoid main() { gl_Position = vec4(0.0, 0.0, 0.0, 1.0); }";
    private static final String FRAGMENT_SOURCE =
            "#version 450\n"
                    + "layout(binding = 0, std140) uniform u { vec4 color; };\n"
                    + "layout(location = 0) out vec4 outColor;\n"
                    + "void main() { outColor = color; }";

    @BeforeAll
    static void initGlfw() {
        assumeTrue(displayAvailable(), "需要显示服务器才能创建 GLFW 窗口（本地可用 xvfb-run）");
        LavaFlowTestSupport.ensureGlfw();
    }

    @Test
    void aFallbackRenderPassDrawsWithoutValidationFindings() throws Exception {
        assumeTrue(displayAvailable(), "需要显示服务器才能创建 GLFW 窗口（本地可用 xvfb-run）");
        Map<String, String> saved = setSwitches(Map.of(
                "lavaflow.forceLegacyRenderPass", "true",
                "lavaflow.validation", "true"));
        LavaFlowDevice device = null;
        LavaFlowGpuTexture color = null;
        GpuBuffer uniform = null;
        RenderPipeline pipeline = null;
        GpuTextureView colorView = null;
        long window = NULL;
        try {
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            window = glfwCreateWindow(320, 240, "LavaFlow legacy pass test", NULL, NULL);
            assertNotEquals(NULL, window, "无法创建 GLFW 窗口");
            exposeVulkanLoaderToLwjgl();
            device = new LavaFlowDevice(window, (id, type) ->
                    type == ShaderType.FRAGMENT ? FRAGMENT_SOURCE : VERTEX_SOURCE);
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
            uniform = device.createBuffer(() -> "legacy pass uniform", GpuBuffer.USAGE_UNIFORM, 16);
            pipeline = pipeline();
            colorView = device.createTextureView(color, 0, 1);

            RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "legacy pass test")
                    .withColorAttachment(colorView, Optional.of(new Vector4f(1, 1, 1, 1)))
                    .withRenderArea(new RenderPass.RenderArea(0, 0, SIZE, SIZE));
            GpuBufferSlice slice = new GpuBufferSlice(uniform, 0, 16);

            RenderPassBackend first = encoder.createRenderPass(descriptor);
            first.setPipeline(pipeline);
            first.setUniform("u", slice);
            first.draw(3, 1, 0, 0);
            encoder.submitRenderPass();
            // 第二遍进同一个目标：legacy 支的 LOAD/resume 是另一条分支，不能只走 DONT_CARE 那条。
            RenderPassBackend second = encoder.createRenderPass(descriptor);
            second.setPipeline(pipeline);
            second.setUniform("u", slice);
            second.draw(3, 1, 0, 0);
            encoder.submitRenderPass();
            device.completePending();

            // 断言放在设备销毁之后：Object Tracking 的泄漏消息只在 vkDestroyDevice 那一刻冒出来，
            // 断言若写在 finally 之前的用例体里，对整个回收阶段都是瞎的。这里先把借走的还干净，
            // 再交设备，断言覆盖的是"两条 legacy 通道 + 全部回收"的完整生命周期。
            colorView.close(); colorView = null;
            uniform.close(); uniform = null;
            color.close(); color = null;
            device.completePending();
            device.close(); device = null;

            assertTrue(context.validationMessages().isEmpty(),
                    () -> "真机那条 fallback 通道（含回收全程）产生了验证层告警： "
                            + context.validationMessages());
        } finally {
            if (colorView != null) colorView.close();
            if (uniform != null) uniform.close();
            if (color != null) color.close();
            if (device != null) {
                device.completePending();
                device.close();
            }
            if (window != NULL) glfwDestroyWindow(window);
            restore(saved);
        }
    }

    /** 一条不带顶点缓冲的管线：着色器不读属性，uniform 由 bind group 声明、按名字绑。 */
    private static RenderPipeline pipeline() {
        BindGroupLayout bindGroup = BindGroupLayout.builder()
                .withUniform("u", UniformType.UNIFORM_BUFFER)
                .build();
        return new RenderPipeline(
                Identifier.parse("lavaflow_legacy_pass_test:pipeline"),
                VERTEX_ID,
                FRAGMENT_ID,
                ShaderDefines.EMPTY,
                List.of(bindGroup),
                new ColorTargetState[]{new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM,
                        ColorTargetState.WRITE_ALL)},
                DepthStencilState.DEFAULT,
                PolygonMode.FILL,
                false,                 // cull
                new VertexFormat[16],  // the constructor array-copies exactly MAX_VERTEX_ELEMENTS
                PrimitiveTopology.TRIANGLES,
                0) {};
    }
}
