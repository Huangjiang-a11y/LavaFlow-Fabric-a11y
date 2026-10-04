package dev.lavaflow.minecraft.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout.UniformDescription;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.util.ShaderCompileException;
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
 * Re-binding the pipeline that is already bound, and re-setting a uniform to the value it
 * already holds, must not re-record the bind or re-push the descriptors.
 *
 * <p>A device run measured 122 pipeline binds against 49 draws per frame — the frontend
 * re-binds the same pipeline roughly once per draw, and every one of those rebinds also
 * re-marks the descriptors dirty, so the descriptor push that follows each draw repeats
 * work the previous draw already did. The elision lives in {@code setPipeline} and
 * {@code setUniform}; this test drives a real render pass through both and asserts the
 * counters the elision is allowed to move, so a regression that drops the shortcut shows
 * up as a number going back up rather than as a performance complaint.
 *
 * <p>What is counted, deliberately, is both sides of the bargain: the frontend's requests
 * ({@code pipelineBinds}) keep counting every call, because those counters describe what
 * the frontend does and hiding repeat calls would paint rebind_ratio as zero. Only the
 * commands actually recorded into the command buffer ({@code pipelineBindsRecorded}) and
 * the descriptor pushes ({@code descriptorPushes}) are expected to drop.
 *
 * <p>The validation layer is on: an elided bind that left a stale pipeline bound, or an
 * elided descriptor push that left stale descriptors, would be a validation error rather
 * than a wrong pixel.
 */
class LavaFlowRebindElisionTest {

    private static final int SIZE = 64;
    private static final int ATTACHMENT_USAGE =
            GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
    // Desktop GLSL for Vulkan SPIR-V needs #version 450 or higher.
    private static final String VERTEX_SOURCE =
            "#version 450\nvoid main() { gl_Position = vec4(0.0, 0.0, 0.0, 1.0); }";
    private static final String FRAGMENT_SOURCE =
            "#version 450\n"
                    + "layout(set = 0, binding = 0) uniform U { vec4 color; };\n"
                    + "layout(location = 0) out vec4 outColor;\n"
                    + "void main() { outColor = color; }";

    @Test
    void repeatBindsAndRepeatUniformValuesAreElided() throws Exception {
        assumeTrue(displayAvailable(), "需要显示服务器才能初始化 SDL 视频子系统（本地可用 xvfb-run）");
        assertTrue(LavaFlowFrameStats.enabled(),
                "计数由 -Dlavaflow.frameStats=true 门控，关着时恒为 0，这条断言会空过；"
                        + "属性只能设在 minecraftTest 任务上（见 build.gradle.kts）");
        Map<String, String> saved = setSwitches(Map.of("lavaflow.validation", "true"));
        LavaFlowDevice device = null;
        LavaFlowGpuTexture color = null;
        LavaFlowGpuTexture depth = null;
        GpuBuffer uniform = null;
        LavaFlowRenderPipeline pipeline = null;
        try {
            assumeTrue(SDL_Init(SDL_INIT_VIDEO), "SDL 视频子系统初始化失败");
            exposeVulkanLoaderToLwjgl();
            device = new LavaFlowDevice();
            CommandEncoderBackend encoder = device.createCommandEncoder();
            color = new LavaFlowGpuTexture(device, ATTACHMENT_USAGE, "rebind test color",
                    GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
            // The backend turns depth testing on unconditionally, so the pass needs a depth
            // attachment for the validation layer to accept the draw.
            depth = new LavaFlowGpuTexture(device, ATTACHMENT_USAGE, "rebind test depth",
                    GpuFormat.D32_FLOAT, SIZE, SIZE, 1, 1);
            uniform = device.createBuffer(() -> "rebind test uniform",
                    GpuBuffer.USAGE_UNIFORM, 128);
            pipeline = (LavaFlowRenderPipeline) device.compilePipeline(createInfo()).finishCompile();

            RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "rebind test pass")
                    .withColorAttachment(device.createTextureView(color, 0, 1), Optional.of(new Vector4f(0, 0, 0, 1)))
                    .withDepthAttachment(device.createTextureView(depth, 0, 1), OptionalDouble.of(1.0))
                    .withRenderArea(new RenderPass.RenderArea(0, 0, SIZE, SIZE))
                    .build();
            RenderPassBackend pass = encoder.createRenderPass(descriptor);

            long bindsBefore = LavaFlowFrameStats.pipelineBindsTotal();
            long rebindsBefore = LavaFlowFrameStats.pipelineRebindsTotal();
            long recordedBefore = LavaFlowFrameStats.pipelineBindsRecordedTotal();
            long pushesBefore = LavaFlowFrameStats.descriptorPushesTotal();
            long uniformChangesBefore = LavaFlowFrameStats.uniformChangesTotal();

            GpuBufferSlice slice = new GpuBufferSlice(uniform, 0, 16);
            // Offset 64 rather than 16: minUniformBufferOffsetAlignment is device-defined, and 64
            // is a multiple of every value the spec allows, so this cannot trip an alignment VUID.
            GpuBufferSlice otherSlice = new GpuBufferSlice(uniform, 64, 16);
            pass.setPipeline(pipeline); // first bind: recorded when the pass begins
            pass.setUniform(0, slice);  // first value: descriptors marked dirty
            pass.draw(3, 1, 0, 0);      // begin + record the bind + one descriptor push
            // Both repeats have to come after the pass began: before that there is nothing recorded to
            // elide, and an assertion about the recording would hold whether or not the elision exists.
            pass.setPipeline(pipeline); // same pipeline: request counted, vkCmdBindPipeline elided
            pass.setUniform(0, slice);  // same value: elided, descriptors stay clean
            pass.draw(3, 1, 0, 0);      // second draw: nothing re-recorded, no descriptor push
            // A genuinely different value has to take the other path, or the assertions above would hold
            // just as well for a counter that never counts and a dirty flag that is never set.
            pass.setUniform(0, otherSlice); // different value: this one really dirties
            pass.draw(3, 1, 0, 0);  // third draw: still no re-record, but the push is owed
            encoder.submitRenderPass();

            assertEquals(2, LavaFlowFrameStats.pipelineBindsTotal() - bindsBefore,
                    "两次 setPipeline 都要计数：这个计数器描述前端行为，省掉它等于把温度计砸了");
            assertEquals(1, LavaFlowFrameStats.pipelineRebindsTotal() - rebindsBefore,
                    "第二次绑定同一 pipeline 是重复绑定，rebind_ratio 的分子不能少算");
            assertEquals(1, LavaFlowFrameStats.pipelineBindsRecordedTotal() - recordedBefore,
                    "两次绑定只该录制一次 vkCmdBindPipeline：重复绑定的省的就是这条命令");
            assertEquals(2, LavaFlowFrameStats.uniformChangesTotal() - uniformChangesBefore,
                    "三次 setUniform 里只有两次真的改了值（首次与换值），重复值那次不算；"
                            + "计入是必须的——它是除去切换 pipeline 之外唯一的置脏来源，漏计就解释不了 pushes");
            assertEquals(2, LavaFlowFrameStats.descriptorPushesTotal() - pushesBefore,
                    "首次脏了推一次、重复值那次不推、换值后又脏了再推一次；"
                            + "负面对照：把换值那步去掉，这条会红在 1");
        } finally {
            if (pipeline != null) pipeline.close();
            if (uniform != null) uniform.close();
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
                "rebind elision test",
                List.of(
                        new BackendRenderPipeline.CreateInfo.Shader("vertex", "main",
                                new CompiledShader(
                                        LavaFlowShaderc.compile(VERTEX_SOURCE,
                                                shaderc_glsl_vertex_shader, "rebind-vertex"),
                                        ShaderType.VERTEX)),
                        new BackendRenderPipeline.CreateInfo.Shader("fragment", "main",
                                new CompiledShader(
                                        LavaFlowShaderc.compile(FRAGMENT_SOURCE,
                                                shaderc_glsl_fragment_shader, "rebind-fragment"),
                                        ShaderType.FRAGMENT))),
                List.of(),   // no vertex buffers: the shaders read no attributes
                List.of(),   // no attribute bindings
                List.of(new UniformDescription("u", UniformType.UNIFORM_BUFFER)),
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
