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
 *
 * <p>26.2 differs from 26.3 in how a pipeline reaches the backend: the frontend's
 * {@code RenderPipeline} is resolved through the device, uniforms are addressed by name
 * rather than by index, and the render pass descriptor is Blaze3D's own. The elision and
 * what it is allowed to move are the same.
 */
class LavaFlowRebindElisionTest {

    private static final int SIZE = 64;
    private static final int ATTACHMENT_USAGE =
            GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
    private static final Identifier VERTEX_ID = Identifier.parse("lavaflow_rebind_test:vertex");
    private static final Identifier FRAGMENT_ID = Identifier.parse("lavaflow_rebind_test:fragment");

    // Desktop GLSL for Vulkan SPIR-V needs #version 140 or higher, and the explicit layout
    // qualifiers below need 420 or higher. The uniform block is named "u" because the
    // backend rebinds uniforms by the name the bind group layout declares.
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
    void repeatBindsAndRepeatUniformValuesAreElided() throws Exception {
        assumeTrue(displayAvailable(), "需要显示服务器才能创建 GLFW 窗口（本地可用 xvfb-run）");
        assertTrue(LavaFlowFrameStats.enabled(),
                "计数由 -Dlavaflow.frameStats=true 门控，关着时恒为 0，这条断言会空过；"
                        + "属性只能设在 minecraftTest 任务上（见 build.gradle.kts）");
        Map<String, String> saved = setSwitches(Map.of("lavaflow.validation", "true"));
        LavaFlowDevice device = null;
        LavaFlowGpuTexture color = null;
        GpuBuffer uniform = null;
        long window = NULL;
        try {
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            window = glfwCreateWindow(320, 240, "LavaFlow rebind elision test", NULL, NULL);
            assertNotEquals(NULL, window, "无法创建 GLFW 窗口");
            exposeVulkanLoaderToLwjgl();
            device = new LavaFlowDevice(window, (id, type) ->
                    type == ShaderType.FRAGMENT ? FRAGMENT_SOURCE : VERTEX_SOURCE);
            CommandEncoderBackend encoder = device.createCommandEncoder();
            color = new LavaFlowGpuTexture(device, ATTACHMENT_USAGE, "rebind test color",
                    GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
            uniform = device.createBuffer(() -> "rebind test uniform", GpuBuffer.USAGE_UNIFORM, 128);

            RenderPipeline pipeline = pipeline();
            RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "rebind test pass")
                    .withColorAttachment(device.createTextureView(color, 0, 1),
                            Optional.of(new Vector4f(0, 0, 0, 1)))
                    .withRenderArea(new RenderPass.RenderArea(0, 0, SIZE, SIZE));
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
            pass.setPipeline(pipeline);  // first bind: recorded when the pass begins
            pass.setUniform("u", slice); // first value: descriptors marked dirty
            pass.draw(3, 1, 0, 0);       // begin + record the bind + one descriptor push
            // Both repeats have to come after the pass began: before that there is nothing recorded to
            // elide, and an assertion about the recording would hold whether or not the elision exists.
            pass.setPipeline(pipeline);  // same pipeline: request counted, vkCmdBindPipeline elided
            pass.setUniform("u", slice); // same value: elided, descriptors stay clean
            pass.draw(3, 1, 0, 0);       // second draw: nothing re-recorded, no descriptor push
            // A genuinely different value has to take the other path, or the assertions above would hold
            // just as well for a counter that never counts and a dirty flag that is never set.
            pass.setUniform("u", otherSlice); // different value: this one really dirties
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

    /** No vertex buffers: {@code vertexNames} skips null entries, so the shaders read no attributes. */
    private static RenderPipeline pipeline() {
        BindGroupLayout bindGroup = BindGroupLayout.builder()
                .withUniform("u", UniformType.UNIFORM_BUFFER)
                .build();
        return new RenderPipeline(
                Identifier.parse("lavaflow_rebind_test:pipeline"),
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
