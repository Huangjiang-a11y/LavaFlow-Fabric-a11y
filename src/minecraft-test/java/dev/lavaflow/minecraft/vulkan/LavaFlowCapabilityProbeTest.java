package dev.lavaflow.minecraft.vulkan;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * 探测行必须把"我们后面要照着改的那些 flag"打出来，且值必须真的来自设备。
 *
 * <p>这条线路的意义全在"读数"上：{@code DeviceFeatures.nonZeroFirstInstance} 与
 * {@code shaderDrawParameters} 现在是硬编码 false，而它们分别决定 {@code LevelRenderer} 走不走
 * multi-draw indirect 的地形路径、{@code GlslCompiler} 定不定义
 * {@code RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE}。所以探测行少打一个字段（或把值打成常量）
 * 会让下一次改动得出"设备不支持"的错误结论。
 *
 * <p>不需要设备：格式化是纯函数，结构体在栈上自己攒。
 */
class LavaFlowCapabilityProbeTest {

    @Test void theProbeNamesTheFlagsThatGateMinecraftsGpuDrawPaths() {
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceFeatures core = VkPhysicalDeviceFeatures.calloc(stack);
            core.multiDrawIndirect(true).drawIndirectFirstInstance(false).geometryShader(false);
            String line = LavaFlowVulkanContext.describeCoreFeatures(core, true);
            assertTrue(line.contains("multiDrawIndirect=true"), line);
            assertTrue(line.contains("drawIndirectFirstInstance=false"), line);
            assertTrue(line.contains("shaderDrawParameters=true"), line);
            assertTrue(line.contains("geometryShader=false"), line);
            // 负面对照：false 必须打成 false。若格式化把某个字段写成常量（或漏传），上面那些 contains
            // 仍然可能通过，这一条不会。
            assertFalse(line.contains("drawIndirectFirstInstance=true"), line + " —— false 被打成了 true");
            assertFalse(line.contains("geometryShader=true"), line + " —— false 被打成了 true");
        }
    }

    @Test void nonZeroFirstInstanceIsOptInAndNeverExceedsTheDevice() {
        // 默认关：它唯一打开的是 MC 自己的地形路，而 Sodium 把那条路整个换掉了（玩家都在用 Sodium）
        assertFalse(LavaFlowVulkanContext.advertisedNonZeroFirstInstance(true, false, false),
                "默认必须关，哪怕设备支持");
        assertTrue(LavaFlowVulkanContext.advertisedNonZeroFirstInstance(true, true, false),
                "显式打开且设备支持 → 可以报 true");
        assertFalse(LavaFlowVulkanContext.advertisedNonZeroFirstInstance(false, true, false),
                "设备没有 drawIndirectFirstInstance 时，开关也不能凭空造出能力");
        assertFalse(LavaFlowVulkanContext.advertisedNonZeroFirstInstance(true, true, true),
                "退回开关必须压过显式打开");
    }

    @Test void anUnlimitedUint32LimitIsNotPrintedAsMinusOne() {
        assertEquals("4294967295", LavaFlowVulkanContext.unsigned(-1),
                "0xFFFFFFFF 是\"无上限\"，打成 -1 意思正好相反");
        assertEquals("1", LavaFlowVulkanContext.unsigned(1));
    }

    // limits 那行没有单测：LWJGL 把 VkPhysicalDeviceLimits 声明成只读（没有 calloc，setter 也不收参），
    // 测试里造不出来。它由 LavaFlowVulkanContextTest 覆盖——那个用例真的起一遍上下文，必然走到探测、
    // 也必然构造这条字符串，字段名写错在那里是编译错误、格式化抛异常在那里是失败。
}
