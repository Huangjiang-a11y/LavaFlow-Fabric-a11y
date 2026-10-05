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
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
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
 * A null vertex buffer slice from the frontend means "leave this binding alone", and the backend
 * must not turn it into {@code VK_NULL_HANDLE}.
 *
 * <p>Minecraft's own Vulkan backend returns on the spot for a null slice, and so does its GL one.
 * The vanilla cloud renderer depends on that: {@code CloudRenderer.render} calls
 * {@code setVertexBuffer(0, null)} and reads its geometry out of the cloud storage buffers, so slot 0
 * is never meant to be bound at all. Recording a null handle there instead is legal by the letter of
 * the spec — {@code VUID-vkCmdBindVertexBuffers-pBuffers-parameter} allows null elements, and an
 * attribute bound to a null binding reads as zero — but a Vivo PD2284 (Mali-G610 MC6, driver 32.1.0)
 * took SIGSEGV inside {@code libGLES_mali.so+0x1664004} while recording exactly that call, with
 * {@code si_addr=0x20} and {@code R1=0}: the driver dereferenced the handle it had been handed. That
 * killed the process on the first clouds draw of a world. Sodium replaces the cloud path, so only the
 * Sodium-free device hit it.
 *
 * <p>The counter these assertions read is the only way to tell the two behaviours apart: lavapipe
 * accepts a null handle without a murmur, so a test that merely drew cloud-like geometry would pass
 * either way. The second assertion is the negative control — a real slice still records its bind, so
 * "records nothing for the null" cannot be satisfied by a counter that never counts.
 *
 * <p>26.2 reaches the same code by a different route: the frontend's {@code RenderPipeline} is a
 * Blaze3D record handed to the device, the pass descriptor is Blaze3D's own, and the window comes
 * from GLFW rather than SDL. The behaviour under test is the same.
 */
class LavaFlowNullVertexBufferTest {

    private static final int SIZE = 64;
    private static final int ATTACHMENT_USAGE =
            GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
    private static final Identifier VERTEX_ID = Identifier.parse("lavaflow_null_vertex_test:vertex");
    private static final Identifier FRAGMENT_ID = Identifier.parse("lavaflow_null_vertex_test:fragment");
    private static final String VERTEX_SOURCE =
            "#version 450\nvoid main() { gl_Position = vec4(0.0, 0.0, 0.0, 1.0); }";
    // No uniform and no vertex attribute: this test is about which commands reach the driver, and
    // LavaFlow (rightly) refuses to draw while a declared uniform has no value.
    private static final String FRAGMENT_SOURCE =
            "#version 450\n"
                    + "layout(location = 0) out vec4 outColor;\n"
                    + "void main() { outColor = vec4(1.0); }";

    @BeforeAll
    static void initGlfw() {
        assumeTrue(displayAvailable(), "需要显示服务器才能创建 GLFW 窗口（本地可用 xvfb-run）");
        LavaFlowTestSupport.ensureGlfw();
    }

    @Test
    void nullVertexBufferSliceRecordsNoBind() throws Exception {
        assumeTrue(displayAvailable(), "需要显示服务器才能创建 GLFW 窗口（本地可用 xvfb-run）");
        assertTrue(LavaFlowFrameStats.enabled(),
                "计数由 -Dlavaflow.frameStats=true 门控，关着时恒为 0，这两条断言就没有意义了；"
                        + "属性只能设在 minecraftTest 任务上（见 build.gradle.kts）");
        Map<String, String> saved = setSwitches(Map.of("lavaflow.validation", "true"));
        LavaFlowDevice device = null;
        LavaFlowGpuTexture color = null;
        GpuBuffer vertex = null;
        long window = NULL;
        try {
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            window = glfwCreateWindow(320, 240, "LavaFlow null vertex buffer test", NULL, NULL);
            assertNotEquals(NULL, window, "无法创建 GLFW 窗口");
            exposeVulkanLoaderToLwjgl();
            device = new LavaFlowDevice(window, (id, type) ->
                    type == ShaderType.FRAGMENT ? FRAGMENT_SOURCE : VERTEX_SOURCE);
            CommandEncoderBackend encoder = device.createCommandEncoder();
            color = new LavaFlowGpuTexture(device, ATTACHMENT_USAGE, "null vertex test color",
                    GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
            vertex = device.createBuffer(() -> "null vertex test buffer",
                    GpuBuffer.USAGE_VERTEX, 64);

            RenderPipeline pipeline = pipeline();
            RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "null vertex test pass")
                    .withColorAttachment(device.createTextureView(color, 0, 1),
                            Optional.of(new Vector4f(0, 0, 0, 1)))
                    .withRenderArea(new RenderPass.RenderArea(0, 0, SIZE, SIZE));
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
            if (vertex != null) vertex.close();
            if (color != null) color.close();
            if (device != null) {
                device.completePending();
                device.close();
            }
            if (window != NULL) glfwDestroyWindow(window);
            restore(saved);
        }
    }

    /** No uniforms and no vertex attributes: nothing has to be set before the draw. */
    private static RenderPipeline pipeline() {
        return new RenderPipeline(
                Identifier.parse("lavaflow_null_vertex_test:pipeline"),
                VERTEX_ID,
                FRAGMENT_ID,
                ShaderDefines.EMPTY,
                List.<BindGroupLayout>of(),
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
