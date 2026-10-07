package dev.lavaflow.minecraft.vulkan;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

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
 * A render pass is split — ended, its sampled textures transitioned, begun again — only when it has to be.
 *
 * <p>A barrier cannot be recorded inside a render pass, so a texture that is still in another layout when a
 * draw samples it forces the pass to be torn down and rebuilt around the transition. The pass begins lazily,
 * at the first draw, precisely so that the textures that draw needs are known by then: those are transitioned
 * before the begin, and no split is needed. The split is left to the case that genuinely cannot avoid it — a
 * pass that is already open and only then binds a texture it has not sampled before.
 *
 * <p>That distinction is worth the test because the two cases look identical from the outside (a draw that
 * samples a texture that is not in the sampled layout) and differ by an end/begin pair per pass. On a tiled
 * GPU, where an end/begin pair is a tile flush and reload, paying it on the first draw of every sampling
 * pass would be the most expensive per-frame command this backend can record for a handful of draws.
 *
 * <p>The second assertion is the negative control for the first: if splitting stopped happening altogether,
 * "did not split" would hold for a backend that never transitioned anything — and the validation layer, which
 * is on here, would have caught the resulting layout mismatch long before this ran.
 *
 * <p>26.2 differs in how a pipeline and its texture reach the backend: the frontend's {@code RenderPipeline}
 * record is handed to the device, samplers are declared by name on the bind group, and textures arrive through
 * {@code bindTexture} rather than as a uniform value. The behaviour under test is the same.
 */
class LavaFlowPassSplitTest {

    private static final int SIZE = 64;
    private static final int ATTACHMENT_USAGE =
            GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
    private static final Identifier VERTEX_ID = Identifier.parse("lavaflow_pass_split_test:vertex");
    private static final Identifier FRAGMENT_ID = Identifier.parse("lavaflow_pass_split_test:fragment");
    private static final String VERTEX_SOURCE =
            "#version 450\nvoid main() { gl_Position = vec4(0.0, 0.0, 0.0, 1.0); }";
    // The sampler has to be used, or shaderc drops the binding and the pipeline's layout no longer matches
    // the shader the driver was handed.
    private static final String FRAGMENT_SOURCE =
            "#version 450\n"
                    + "layout(binding = 0) uniform sampler2D tex;\n"
                    + "layout(location = 0) out vec4 outColor;\n"
                    + "void main() { outColor = texture(tex, vec2(0.5, 0.5)); }";

    @BeforeAll
    static void initGlfw() {
        assumeTrue(displayAvailable(), "需要显示服务器才能创建 GLFW 窗口（本地可用 xvfb-run）");
        LavaFlowTestSupport.ensureGlfw();
    }

    @Test
    void onlyAFirstDrawThatCannotAvoidItSplitsThePass() throws Exception {
        assumeTrue(displayAvailable(), "需要显示服务器才能创建 GLFW 窗口（本地可用 xvfb-run）");
        assertTrue(LavaFlowFrameStats.enabled(),
                "计数由 -Dlavaflow.frameStats=true 门控，关着时恒为 0，这条断言会空过；"
                        + "属性只能设在 minecraftTest 任务上（见 build.gradle.kts）");
        Map<String, String> saved = setSwitches(Map.of("lavaflow.validation", "true"));
        LavaFlowDevice device = null;
        LavaFlowGpuTexture color = null;
        LavaFlowGpuTexture firstSampled = null;
        LavaFlowGpuTexture secondSampled = null;
        LavaFlowGpuSampler sampler = null;
        long window = NULL;
        try {
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            window = glfwCreateWindow(320, 240, "LavaFlow pass split test", NULL, NULL);
            assertNotEquals(NULL, window, "无法创建 GLFW 窗口");
            exposeVulkanLoaderToLwjgl();
            device = new LavaFlowDevice(window, (id, type) ->
                    type == ShaderType.FRAGMENT ? FRAGMENT_SOURCE : VERTEX_SOURCE);
            CommandEncoderBackend encoder = device.createCommandEncoder();
            color = new LavaFlowGpuTexture(device, ATTACHMENT_USAGE, "pass split test color",
                    GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
            // Both are created fresh, which leaves them in VK_IMAGE_LAYOUT_UNDEFINED: exactly the
            // "sampled texture that still sits in another layout" the transition — and the split — exists for.
            firstSampled = new LavaFlowGpuTexture(device, GpuTexture.USAGE_TEXTURE_BINDING,
                    "pass split test sampled one", GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
            secondSampled = new LavaFlowGpuTexture(device, GpuTexture.USAGE_TEXTURE_BINDING,
                    "pass split test sampled two", GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
            sampler = (LavaFlowGpuSampler) device.createSampler(
                    AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());

            RenderPipeline pipeline = pipeline();
            RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "pass split test pass")
                    .withColorAttachment(device.createTextureView(color, 0, 1),
                            Optional.of(new Vector4f(0, 0, 0, 1)))
                    .withRenderArea(new RenderPass.RenderArea(0, 0, SIZE, SIZE));
            RenderPassBackend pass = encoder.createRenderPass(descriptor);

            long splitsBefore = LavaFlowFrameStats.passSplitsTotal();

            // First draw of the pass, sampling a texture that is not in the sampled layout yet. The pass has
            // not begun, so the transition goes in before it starts and the pass never has to be split.
            pass.setPipeline(pipeline);
            pass.bindTexture("tex", device.createTextureView(firstSampled, 0, 1), sampler);
            pass.draw(3, 1, 0, 0);
            assertEquals(0, LavaFlowFrameStats.passSplitsTotal() - splitsBefore,
                    "本趟的第一个 draw 不必拆通道：通道还没开，采样纹理的布局转换录在 begin 之前就行");

            // Second draw, now sampling a texture the pass has not seen. The pass is already open, so this one
            // really does have to be split — and that keeps the assertion above from passing vacuously.
            pass.bindTexture("tex", device.createTextureView(secondSampled, 0, 1), sampler);
            pass.draw(3, 1, 0, 0);
            assertEquals(1, LavaFlowFrameStats.passSplitsTotal() - splitsBefore,
                    "通道已开、又绑了一张没采样过的纹理，这一趟只能拆：负面对照，说明上面那条不是恒真");
            encoder.submitRenderPass();
        } finally {
            if (sampler != null) sampler.close();
            if (firstSampled != null) firstSampled.close();
            if (secondSampled != null) secondSampled.close();
            if (color != null) color.close();
            if (device != null) {
                device.completePending();
                device.close();
            }
            if (window != NULL) glfwDestroyWindow(window);
            restore(saved);
        }
    }

    /** The device compiles the frontend's pipeline record itself on first use, so there is nothing to close. */
    private static RenderPipeline pipeline() {
        BindGroupLayout bindGroup = BindGroupLayout.builder().withSampler("tex").build();
        RenderPipeline pipeline = new RenderPipeline(
                Identifier.parse("lavaflow_pass_split_test:pipeline"),
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
        return pipeline;
    }
}
