package dev.lavaflow.minecraft.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.ShaderType;
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
 * Binding the vertex buffer that is already bound in that slot must not record another
 * {@code vkCmdBindVertexBuffers} — not twice in one pass, and not once the next pass starts.
 *
 * <p>The device run behind 0.1.6 recorded 92.8 vertex binds per frame against 92.8 draws, which is the frontend
 * re-binding the same slots per draw. The elision answers the question {@code setPipeline} and {@code setUniform}
 * already ask, with one difference that this test exists for: a vertex binding is command buffer state, so it
 * survives the end of the render pass it was recorded in. Asking the pass rather than the encoder would leave
 * the second pass re-recording a binding that is still in place, which is why the second pass here binds what
 * the first one ended on and expects nothing to be recorded.
 *
 * <p>Two-sided on purpose: the requests and the repeats are asserted separately, because the counters that
 * describe the frontend have to keep counting every call (a dropped request would make the ratio look better
 * than the code is), while the recorded count has to fall by exactly the repeats. A regression in either
 * direction fails here rather than showing up as a frame time nobody can explain.
 *
 * <p>The validation layer is on, so a bind wrongly elided would have to be caught as stale state rather than
 * as a wrong pixel.
 */
class LavaFlowVertexBindElisionTest {

    private static final int SIZE = 64;
    private static final int ATTACHMENT_USAGE =
            GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
    // Desktop GLSL for Vulkan SPIR-V needs #version 450 or higher.
    private static final String VERTEX_SOURCE =
            "#version 450\nvoid main() { gl_Position = vec4(0.0, 0.0, 0.0, 1.0); }";
    // No uniform: the rebind test already covers the uniform path, and a pipeline that declares one has to be
    // fed one before the first draw, which would put a second concern in the middle of this test.
    private static final String FRAGMENT_SOURCE =
            "#version 450\n"
                    + "layout(location = 0) out vec4 outColor;\n"
                    + "void main() { outColor = vec4(1.0); }";

    @Test
    void repeatVertexBindsAreElidedWithinAndAcrossPasses() throws Exception {
        assumeTrue(displayAvailable(), "需要显示服务器才能初始化 SDL 视频子系统（本地可用 xvfb-run）");
        assertTrue(LavaFlowFrameStats.enabled(),
                "计数由 -Dlavaflow.frameStats=true 门控，关着时恒为 0，这条断言会空过；"
                        + "属性只能设在 minecraftTest 任务上（见 build.gradle.kts）");
        Map<String, String> saved = setSwitches(Map.of("lavaflow.validation", "true"));
        LavaFlowDevice device = null;
        LavaFlowGpuTexture color = null;
        LavaFlowGpuTexture depth = null;
        GpuBuffer vertices = null;
        LavaFlowRenderPipeline pipeline = null;
        try {
            assumeTrue(SDL_Init(SDL_INIT_VIDEO), "SDL 视频子系统初始化失败");
            exposeVulkanLoaderToLwjgl();
            device = new LavaFlowDevice();
            CommandEncoderBackend encoder = device.createCommandEncoder();
            color = new LavaFlowGpuTexture(device, ATTACHMENT_USAGE, "vertex bind test color",
                    GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
            // The backend turns depth testing on unconditionally, so the pass needs a depth attachment for the
            // validation layer to accept the draw.
            depth = new LavaFlowGpuTexture(device, ATTACHMENT_USAGE, "vertex bind test depth",
                    GpuFormat.D32_FLOAT, SIZE, SIZE, 1, 1);
            vertices = device.createBuffer(() -> "vertex bind test buffer", GpuBuffer.USAGE_VERTEX, 256);
            pipeline = (LavaFlowRenderPipeline) device.compilePipeline(createInfo()).finishCompile();

            RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "vertex bind test pass")
                    .withColorAttachment(device.createTextureView(color, 0, 1), Optional.of(new Vector4f(0, 0, 0, 1)))
                    .withDepthAttachment(device.createTextureView(depth, 0, 1), OptionalDouble.of(1.0))
                    .withRenderArea(new RenderPass.RenderArea(0, 0, SIZE, SIZE))
                    .build();

            long callsBefore = LavaFlowFrameStats.vertexBufferBindCallsTotal();
            long repeatsBefore = LavaFlowFrameStats.vertexBufferBindRepeatsTotal();
            long recordedBefore = LavaFlowFrameStats.vertexBufferBindsTotal();

            GpuBufferSlice first = new GpuBufferSlice(vertices, 0, 16);
            GpuBufferSlice second = new GpuBufferSlice(vertices, 16, 16);

            RenderPassBackend pass = encoder.createRenderPass(descriptor);
            pass.setPipeline(pipeline);
            pass.setVertexBuffer(0, first);  // 请求，要等到 begin 才录
            pass.draw(3, 1, 0, 0);           // begin + 录第 1 条
            pass.setVertexBuffer(0, first);  // 已经是它：请求照记，命令省掉
            pass.draw(3, 1, 0, 0);
            pass.setVertexBuffer(0, second); // 换了 buffer 段：这条必须真的录
            pass.draw(3, 1, 0, 0);
            encoder.submitRenderPass();

            // 第二个通道：绑定是命令缓冲的状态，上一趟留下的还在，所以同一段这里也该省。
            // 负面对照：把去重状态挂在通道上（而不是编码器上），这条断言会红在 4 而不是 3。
            RenderPassBackend next = encoder.createRenderPass(descriptor);
            next.setPipeline(pipeline);
            next.setVertexBuffer(0, second); // 与命令缓冲里当前绑定相同：跨通道也省
            next.draw(3, 1, 0, 0);
            next.setVertexBuffer(0, first);  // 换回来：录第 3 条
            next.draw(3, 1, 0, 0);
            encoder.submitRenderPass();

            assertEquals(5, LavaFlowFrameStats.vertexBufferBindCallsTotal() - callsBefore,
                    "五次 setVertexBuffer 都要计数：这个计数器描述前端行为，省掉它等于砸温度计");
            assertEquals(2, LavaFlowFrameStats.vertexBufferBindRepeatsTotal() - repeatsBefore,
                    "重复请求有两次（同一通道内一次、跨通道一次），ratio 的分子不能少算");
            assertEquals(3, LavaFlowFrameStats.vertexBufferBindsTotal() - recordedBefore,
                    "五次请求只该录三条 vkCmdBindVertexBuffers：省下的正是重复的那些");
        } finally {
            if (pipeline != null) pipeline.close();
            if (vertices != null) vertices.close();
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
                "vertex bind elision test",
                List.of(
                        new BackendRenderPipeline.CreateInfo.Shader("vertex", "main",
                                new CompiledShader(
                                        LavaFlowShaderc.compile(VERTEX_SOURCE,
                                                shaderc_glsl_vertex_shader, "vertex-bind-vertex"),
                                        ShaderType.VERTEX)),
                        new BackendRenderPipeline.CreateInfo.Shader("fragment", "main",
                                new CompiledShader(
                                        LavaFlowShaderc.compile(FRAGMENT_SOURCE,
                                                shaderc_glsl_fragment_shader, "vertex-bind-fragment"),
                                        ShaderType.FRAGMENT))),
                List.of(),   // 管线不读顶点属性：绑定的槽位只是"别处绑过什么"的状态
                List.of(),   // no attribute bindings
                List.of(),   // no uniforms

                0,
                DepthStencilState.DEFAULT,
                PolygonMode.FILL,
                false,
                List.of(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM,
                        ColorTargetState.WRITE_ALL)),
                PrimitiveTopology.TRIANGLES);
    }

    /**
     * The backend reads only {@code spv()} and {@code type()} while compiling; the caller that owns the module
     * (here: this test, standing in for the frontend) releases it once {@code finishCompile()} returns, so
     * closing is a no-op.
     */
    private record CompiledShader(ByteBuffer spv, ShaderType type) implements SpvModule {
        @Override public void close() {}
        @Override public Reflection reflect() throws ShaderCompileException {
            throw new UnsupportedOperationException("reflection is the frontend's job");
        }
        @Override public Reflection getReflectionInfoIfAvailable() { return null; }
    }
}
