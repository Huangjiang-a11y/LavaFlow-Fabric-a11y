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
 * A null vertex buffer slice from the frontend means "leave this binding alone", and the backend
 * must not turn it into {@code VK_NULL_HANDLE}.
 *
 * <p>Minecraft's own Vulkan backend returns on the spot for a null slice, and so does its GL one.
 * The vanilla cloud renderer depends on that: {@code CloudRenderer.render} calls
 * {@code setVertexBuffer(0, null)} and reads its geometry out of the {@code CloudFaces} /
 * {@code CloudInfo} storage buffers, so slot 0 is never meant to be bound at all. Recording a null
 * handle there instead is legal by the letter of the spec — {@code VUID-vkCmdBindVertexBuffers-pBuffers-parameter}
 * allows null elements, and an attribute bound to a null binding reads as zero — but a Vivo PD2284
 * (Mali-G610 MC6, driver 32.1.0) took SIGSEGV inside {@code libGLES_mali.so+0x1664004} while recording
 * exactly that call, with {@code si_addr=0x20} and {@code R1=0}: the driver dereferenced the handle it
 * had been handed. That killed the process on the first clouds draw of a world. Sodium replaces the
 * cloud path, so only the Sodium-free device hit it.
 *
 * <p>The counter these assertions read is the only way to tell the two behaviours apart: lavapipe
 * accepts a null handle without a murmur, so a test that merely drew cloud-like geometry would pass
 * either way. The second assertion is the negative control — a real slice still records its bind, so
 * "records nothing for the null" cannot be satisfied by a counter that never counts.
 */
class LavaFlowNullVertexBufferTest {

    private static final int SIZE = 64;
    private static final int ATTACHMENT_USAGE =
            GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
    private static final String VERTEX_SOURCE =
            "#version 450\nvoid main() { gl_Position = vec4(0.0, 0.0, 0.0, 1.0); }";
    // No uniform and no vertex attribute: this test is about which commands reach the driver, and
    // LavaFlow (rightly) refuses to draw while a declared uniform has no value.
    private static final String FRAGMENT_SOURCE =
            "#version 450\n"
                    + "layout(location = 0) out vec4 outColor;\n"
                    + "void main() { outColor = vec4(1.0); }";

    @Test
    void nullVertexBufferSliceRecordsNoBind() throws Exception {
        assumeTrue(displayAvailable(), "需要显示服务器才能初始化 SDL 视频子系统（本地可用 xvfb-run）");
        assertTrue(LavaFlowFrameStats.enabled(),
                "计数由 -Dlavaflow.frameStats=true 门控，关着时恒为 0，这两条断言就没有意义了；"
                        + "属性只能设在 minecraftTest 任务上（见 build.gradle.kts）");
        Map<String, String> saved = setSwitches(Map.of("lavaflow.validation", "true"));
        LavaFlowDevice device = null;
        LavaFlowGpuTexture color = null;
        LavaFlowGpuTexture depth = null;
        GpuBuffer vertex = null;
        LavaFlowRenderPipeline pipeline = null;
        try {
            assumeTrue(SDL_Init(SDL_INIT_VIDEO), "SDL 视频子系统初始化失败");
            exposeVulkanLoaderToLwjgl();
            device = new LavaFlowDevice();
            CommandEncoderBackend encoder = device.createCommandEncoder();
            color = new LavaFlowGpuTexture(device, ATTACHMENT_USAGE, "null vertex test color",
                    GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
            // The backend turns depth testing on unconditionally, so the pass needs a depth
            // attachment for the validation layer to accept the draw.
            depth = new LavaFlowGpuTexture(device, ATTACHMENT_USAGE, "null vertex test depth",
                    GpuFormat.D32_FLOAT, SIZE, SIZE, 1, 1);
            vertex = device.createBuffer(() -> "null vertex test buffer",
                    GpuBuffer.USAGE_VERTEX, 64);
            pipeline = (LavaFlowRenderPipeline) device.compilePipeline(createInfo()).finishCompile();

            RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "null vertex test pass")
                    .withColorAttachment(device.createTextureView(color, 0, 1), Optional.of(new Vector4f(0, 0, 0, 1)))
                    .withDepthAttachment(device.createTextureView(depth, 0, 1), OptionalDouble.of(1.0))
                    .withRenderArea(new RenderPass.RenderArea(0, 0, SIZE, SIZE))
                    .build();
            RenderPassBackend pass = encoder.createRenderPass(descriptor);

            long vertexBindsBefore = LavaFlowFrameStats.vertexBufferBindsTotal();

            GpuBufferSlice slice = new GpuBufferSlice(vertex, 0, 64);
            // The cloud case, and the crash case: the null arrives before the pass begins, so the
            // pending path is the one that has to decline it.
            pass.setVertexBuffer(0, null);
            pass.setPipeline(pipeline);
            pass.draw(3, 1, 0, 0); // the pass begins here: slot 0 must not have been recorded
            assertEquals(0, LavaFlowFrameStats.vertexBufferBindsTotal() - vertexBindsBefore,
                    "null slice 的语义是别碰这个 binding，不是绑一个空句柄：MC 自己的 Vulkan/GL 后端都直接返回，"
                            + "录一条 VK_NULL_HANDLE 会让 Mali 驱动在 record 阶段就吃 SIGSEGV");
            // A real slice has to keep recording, or the assertion above would hold just as well for a
            // backend that quietly stopped binding vertex buffers altogether.
            pass.setVertexBuffer(0, slice);
            pass.draw(3, 1, 0, 0);
            assertEquals(1, LavaFlowFrameStats.vertexBufferBindsTotal() - vertexBindsBefore,
                    "真 buffer 仍然要录一条 vkCmdBindVertexBuffers；这条是上面那条的负面对照");
            encoder.submitRenderPass();
        } finally {
            if (pipeline != null) pipeline.close();
            if (vertex != null) vertex.close();
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
                "null vertex buffer test",
                List.of(
                        new BackendRenderPipeline.CreateInfo.Shader("vertex", "main",
                                new CompiledShader(
                                        LavaFlowShaderc.compile(VERTEX_SOURCE,
                                                shaderc_glsl_vertex_shader, "null-vertex-v"),
                                        ShaderType.VERTEX)),
                        new BackendRenderPipeline.CreateInfo.Shader("fragment", "main",
                                new CompiledShader(
                                        LavaFlowShaderc.compile(FRAGMENT_SOURCE,
                                                shaderc_glsl_fragment_shader, "null-vertex-f"),
                                        ShaderType.FRAGMENT))),
                List.of(),   // no vertex buffers: the shaders read no attributes
                List.of(),   // no attribute bindings
                List.of(),   // no uniforms: nothing to set before the draw
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
