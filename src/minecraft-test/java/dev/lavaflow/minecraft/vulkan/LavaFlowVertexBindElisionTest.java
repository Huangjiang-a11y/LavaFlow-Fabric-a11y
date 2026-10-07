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
 * 把已经绑在那个槽位上的顶点缓冲再绑一次，不得再录一条 {@code vkCmdBindVertexBuffers} ——
 * 同一趟里不行，下一趟开始时也不行。
 *
 * <p>0.1.6 之前的设备实测是每帧 92.8 条顶点绑定对 92.8 次 draw，也就是前端每画一次就把同样的槽位重绑一次。
 * 这里问的是 {@code setPipeline}/{@code setUniform} 早就在问的同一个问题，只有一处不同——**顶点绑定是命令
 * 缓冲的状态**，它活过录制它的那一趟通道。去重状态如果挂在通道上而不是编码器上，第二趟就会把还在生效的绑定
 * 再录一遍；本用例第二趟绑的就是第一趟结束时的那一段，并且断言一条都不该录，正是为这件事。
 *
 * <p>断言故意分两侧：描述前端的计数器（请求数、重复数）必须一次不漏地记——漏记会让比例看起来比代码更漂亮
 * ——而真正录进命令缓冲的条数必须正好少掉重复的那些。哪一侧坏了这里都会红，而不是变成一句没人解释得清的帧时。
 *
 * <p>验证层开着：省错了一条绑定，应该是"状态不对"被抓住，而不是画出一个错误的像素。
 */
class LavaFlowVertexBindElisionTest {

    private static final int SIZE = 64;
    private static final int ATTACHMENT_USAGE =
            GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
    private static final Identifier VERTEX_ID = Identifier.parse("lavaflow_vertex_bind_test:vertex");
    private static final Identifier FRAGMENT_ID = Identifier.parse("lavaflow_vertex_bind_test:fragment");
    // Desktop GLSL for Vulkan SPIR-V needs #version 450 or higher.
    private static final String VERTEX_SOURCE =
            "#version 450\nvoid main() { gl_Position = vec4(0.0, 0.0, 0.0, 1.0); }";
    // 无 uniform、无顶点属性：本用例只关心哪些命令到了驱动，而声明了 uniform 却没给值 LavaFlow 会（正确地）拒绝画。
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
    void repeatVertexBindsAreElidedWithinAndAcrossPasses() throws Exception {
        assumeTrue(displayAvailable(), "需要显示服务器才能创建 GLFW 窗口（本地可用 xvfb-run）");
        assertTrue(LavaFlowFrameStats.enabled(),
                "计数由 -Dlavaflow.frameStats=true 门控，关着时恒为 0，这条断言会空过；"
                        + "属性只能设在 minecraftTest 任务上（见 build.gradle.kts）");
        Map<String, String> saved = setSwitches(Map.of("lavaflow.validation", "true"));
        LavaFlowDevice device = null;
        LavaFlowGpuTexture color = null;
        GpuBuffer vertices = null;
        long window = NULL;
        try {
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            window = glfwCreateWindow(320, 240, "LavaFlow vertex bind elision test", NULL, NULL);
            assertNotEquals(NULL, window, "无法创建 GLFW 窗口");
            exposeVulkanLoaderToLwjgl();
            device = new LavaFlowDevice(window, (id, type) ->
                    type == ShaderType.FRAGMENT ? FRAGMENT_SOURCE : VERTEX_SOURCE);
            CommandEncoderBackend encoder = device.createCommandEncoder();
            color = new LavaFlowGpuTexture(device, ATTACHMENT_USAGE, "vertex bind test color",
                    GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
            vertices = device.createBuffer(() -> "vertex bind test buffer", GpuBuffer.USAGE_VERTEX, 256);

            RenderPipeline pipeline = pipeline();
            RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "vertex bind test pass")
                    .withColorAttachment(device.createTextureView(color, 0, 1),
                            Optional.of(new Vector4f(0, 0, 0, 1)))
                    .withRenderArea(new RenderPass.RenderArea(0, 0, SIZE, SIZE));

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
            // 负面对照：把去重状态挂在通道上（而不是编码器上），这条断言会红。
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
            if (vertices != null) vertices.close();
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
                Identifier.parse("lavaflow_vertex_bind_test:pipeline"),
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
