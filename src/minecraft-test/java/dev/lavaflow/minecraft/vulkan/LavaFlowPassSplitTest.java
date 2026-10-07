package dev.lavaflow.minecraft.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout.UniformDescription;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.util.ShaderCompileException;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.displayAvailable;
import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.exposeVulkanLoaderToLwjgl;
import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.restore;
import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.setSwitches;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.sdl.SDLInit.SDL_INIT_VIDEO;
import static org.lwjgl.sdl.SDLInit.SDL_Init;
import static org.lwjgl.sdl.SDLInit.SDL_Quit;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_fragment_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_vertex_shader;

/**
 * A render pass is split — ended, its sampled textures transitioned, begun again — only when it has to be.
 *
 * <p>A barrier cannot be recorded inside a render pass, so a texture that is still in another layout when a
 * draw samples it forces the pass to be torn down and rebuilt around the transition. Blaze3D usually knows
 * which textures a pass will sample before the pass starts drawing, so the first draw of a pass never needs
 * that: the transition can be recorded before the pass begins. The split is then left to the case that
 * genuinely cannot avoid it — a pass that is already open and only then binds a texture it has not sampled
 * before.
 *
 * <p>That distinction is worth the test because the two cases look identical from the outside (a draw that
 * samples a texture that is not in the sampled layout) and differ by an end/begin pair per pass. On a tiled
 * GPU, where an end/begin pair is a tile flush and reload, paying it on the first draw of every sampling
 * pass is the most expensive per-frame command this backend can record for a handful of draws.
 *
 * <p>The second assertion is the negative control for the first: if splitting stopped happening altogether,
 * "did not split" would be satisfied by a backend that never transitioned anything — and the validation
 * layer, which is on here, would have caught the resulting layout mismatch long before this ran.
 */
class LavaFlowPassSplitTest {

    private static final int SIZE = 64;
    private static final int ATTACHMENT_USAGE =
            GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
    private static final String VERTEX_SOURCE =
            "#version 450\nvoid main() { gl_Position = vec4(0.0, 0.0, 0.0, 1.0); }";
    // The sampler has to be used, or shaderc drops the binding and the pipeline's layout no longer matches
    // the shader the driver was handed.
    private static final String FRAGMENT_SOURCE =
            "#version 450\n"
                    + "layout(set = 0, binding = 0) uniform sampler2D tex;\n"
                    + "layout(location = 0) out vec4 outColor;\n"
                    + "void main() { outColor = texture(tex, vec2(0.5, 0.5)); }";

    @Test
    void onlyAFirstDrawThatCannotAvoidItSplitsThePass() throws Exception {
        assumeTrue(displayAvailable(), "需要显示服务器才能初始化 SDL 视频子系统（本地可用 xvfb-run）");
        assertTrue(LavaFlowFrameStats.enabled(),
                "计数由 -Dlavaflow.frameStats=true 门控，关着时恒为 0，这条断言会空过；"
                        + "属性只能设在 minecraftTest 任务上（见 build.gradle.kts）");
        Map<String, String> saved = setSwitches(Map.of("lavaflow.validation", "true"));
        LavaFlowDevice device = null;
        LavaFlowGpuTexture color = null;
        LavaFlowGpuTexture depth = null;
        LavaFlowGpuTexture firstSampled = null;
        LavaFlowGpuTexture secondSampled = null;
        LavaFlowGpuSampler sampler = null;
        LavaFlowRenderPipeline pipeline = null;
        try {
            assumeTrue(SDL_Init(SDL_INIT_VIDEO), "SDL 视频子系统初始化失败");
            exposeVulkanLoaderToLwjgl();
            device = new LavaFlowDevice();
            CommandEncoderBackend encoder = device.createCommandEncoder();
            color = new LavaFlowGpuTexture(device, ATTACHMENT_USAGE, "pass split test color",
                    GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
            // The backend turns depth testing on unconditionally, so the pass needs a depth
            // attachment for the validation layer to accept the draw.
            depth = new LavaFlowGpuTexture(device, ATTACHMENT_USAGE, "pass split test depth",
                    GpuFormat.D32_FLOAT, SIZE, SIZE, 1, 1);
            // Both are created fresh, which leaves them in VK_IMAGE_LAYOUT_UNDEFINED: exactly the
            // "sampled texture that still sits in another layout" the transition — and the split — exists for.
            firstSampled = new LavaFlowGpuTexture(device, GpuTexture.USAGE_TEXTURE_BINDING,
                    "pass split test sampled one", GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
            secondSampled = new LavaFlowGpuTexture(device, GpuTexture.USAGE_TEXTURE_BINDING,
                    "pass split test sampled two", GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
            sampler = (LavaFlowGpuSampler) device.createSampler(
                    AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
            pipeline = (LavaFlowRenderPipeline) device.compilePipeline(createInfo()).finishCompile();

            RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "pass split test pass")
                    .withColorAttachment(device.createTextureView(color, 0, 1), Optional.of(new Vector4f(0, 0, 0, 1)))
                    .withDepthAttachment(device.createTextureView(depth, 0, 1), OptionalDouble.of(1.0))
                    .withRenderArea(new RenderPass.RenderArea(0, 0, SIZE, SIZE))
                    .build();
            RenderPassBackend pass = encoder.createRenderPass(descriptor);

            long splitsBefore = LavaFlowFrameStats.passSplitsTotal();

            // First draw of the pass, sampling a texture that is not in the sampled layout yet. The pass has
            // not begun, so the transition goes in before it starts and the pass never has to be split.
            pass.setPipeline(pipeline);
            pass.setUniform(0, new TextureViewAndSampler(
                    device.createTextureView(firstSampled, 0, 1), sampler));
            pass.draw(3, 1, 0, 0);
            assertEquals(0, LavaFlowFrameStats.passSplitsTotal() - splitsBefore,
                    "本趟的第一个 draw 不必拆通道：通道还没开，采样纹理的布局转换录在 begin 之前就行");

            // Second draw, now sampling a texture the pass has not seen. The pass is already open, so this one
            // really does have to be split — and that keeps the assertion above from passing vacuously.
            pass.setUniform(0, new TextureViewAndSampler(
                    device.createTextureView(secondSampled, 0, 1), sampler));
            pass.draw(3, 1, 0, 0);
            assertEquals(1, LavaFlowFrameStats.passSplitsTotal() - splitsBefore,
                    "通道已开、又绑了一张没采样过的纹理，这一趟只能拆：负面对照，说明上面那条不是恒真");
            encoder.submitRenderPass();
        } finally {
            if (pipeline != null) pipeline.close();
            if (sampler != null) sampler.close();
            if (firstSampled != null) firstSampled.close();
            if (secondSampled != null) secondSampled.close();
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

    private static BackendRenderPipeline.CreateInfo createInfo() {
        return new BackendRenderPipeline.CreateInfo(
                "pass split test",
                List.of(
                        new BackendRenderPipeline.CreateInfo.Shader("vertex", "main",
                                new CompiledShader(
                                        LavaFlowShaderc.compile(VERTEX_SOURCE,
                                                shaderc_glsl_vertex_shader, "pass-split-v"),
                                        ShaderType.VERTEX)),
                        new BackendRenderPipeline.CreateInfo.Shader("fragment", "main",
                                new CompiledShader(
                                        LavaFlowShaderc.compile(FRAGMENT_SOURCE,
                                                shaderc_glsl_fragment_shader, "pass-split-f"),
                                        ShaderType.FRAGMENT))),
                List.of(),   // no vertex buffers: the shaders read no attributes
                List.of(),   // no attribute bindings
                List.of(new UniformDescription("tex", UniformType.COMBINED_IMAGE_SAMPLER)),
                0,           // push constants size
                DepthStencilState.DEFAULT,
                PolygonMode.FILL,
                false,       // cull
                List.of(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM,
                        ColorTargetState.WRITE_ALL)),
                PrimitiveTopology.TRIANGLES);
    }

    /**
     * The backend reads only {@code spv()} and {@code type()} while compiling; the caller
     * that owns the module (here: this test, standing in for the frontend) releases it
     * once {@code finishCompile()} returns, so closing is a no-op.
     */
    private record CompiledShader(ByteBuffer spv, ShaderType type) implements SpvModule {
        @Override public void close() {}
        @Override public Reflection reflect() throws ShaderCompileException {
            throw new UnsupportedOperationException("reflection is the frontend's job");
        }
        @Override public Reflection getReflectionInfoIfAvailable() { return null; }
    }
}
