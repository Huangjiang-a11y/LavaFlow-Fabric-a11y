package dev.lavaflow.minecraft.vulkan;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Map;

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
import static org.lwjgl.vulkan.VK10.*;

/**
 * Pins the invariant behind LavaFlow not flushing mapped memory by hand.
 *
 * <p>{@code LavaFlowGpuBuffer.write} and the transient upload path used to call
 * {@code vkFlushMappedMemoryRanges}. That call was both unnecessary and invalid here: the
 * constructor's {@code requiredFlags} make every mappable buffer HOST_COHERENT, where a flush is a
 * no-op, while {@code VUID-VkMappedMemoryRange-size-01390} still demands that {@code size} be a
 * multiple of {@code nonCoherentAtomSize} or end exactly at the end of the {@link
 * org.lwjgl.vulkan.VkDeviceMemory} allocation — which an arbitrary upload length does not.
 *
 * <p>The flush is gone, so what keeps the write visible is the memory type alone. This test states
 * that out loud: relax {@code requiredFlags} to drop HOST_COHERENT and the first assertion fails
 * instead of the corruption showing up as stale vertices on a device.
 */
class MappedMemoryCoherenceTest {

    // Deliberately not a power of two and not a multiple of the usual 64-byte nonCoherentAtomSize:
    // texture uploads and transient copies have this shape, and it is the shape the old flush got
    // wrong.
    private static final int ODD_LENGTH = 1234;

    @Test
    void mappedBuffersComeFromCoherentMemoryAndSurviveAWrite() throws Exception {
        assumeTrue(displayAvailable(), "需要显示服务器才能初始化 SDL 视频子系统（本地可用 xvfb-run）");
        Map<String, String> saved = setSwitches(Map.of("lavaflow.validation", "true"));
        LavaFlowDevice device = null;
        try {
            assumeTrue(SDL_Init(SDL_INIT_VIDEO), "SDL 视频子系统初始化失败");
            exposeVulkanLoaderToLwjgl();
            device = new LavaFlowDevice();
            LavaFlowVulkanContext context = device.context();

            LavaFlowGpuBuffer buffer = new LavaFlowGpuBuffer(device,
                    GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_SRC,
                    ODD_LENGTH);

            int flags = context.memoryTypeFlags(buffer.memoryTypeIndex());
            assertTrue((flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0,
                    "映射型 buffer 必须落在 HOST_VISIBLE 内存上，实际 flags=0x" + Integer.toHexString(flags));
            assertTrue((flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0,
                    "映射型 buffer 必须落在 HOST_COHERENT 内存上，实际 flags=0x" + Integer.toHexString(flags)
                            + "。write() 与 transient 上传都不再做手工 flush，非一致内存会让 GPU 读到旧数据");

            ByteBuffer source = ByteBuffer.allocateDirect(ODD_LENGTH);
            for (int i = 0; i < ODD_LENGTH; i++) source.put(i, (byte) (i * 7));

            buffer.write(0, source);

            try (GpuBufferSlice.MappedView view = buffer.map(0, ODD_LENGTH, true, false)) {
                ByteBuffer read = view.data();
                for (int i = 0; i < ODD_LENGTH; i++) {
                    assertEquals((byte) (i * 7), read.get(i), "第 " + i + " 字节读回不一致");
                }
            }

            buffer.close();
            device.completePending();
            device.close();
            device = null;

            if (context.validationEnabled()) {
                assertEquals(0, context.validationMessages().size(),
                        () -> "产生了 validation 消息: " + context.validationMessages());
            }
        } finally {
            if (device != null) device.close();
            SDL_Quit();
            restore(saved);
        }
    }
}
