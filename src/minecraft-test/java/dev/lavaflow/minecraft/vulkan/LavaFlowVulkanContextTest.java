package dev.lavaflow.minecraft.vulkan;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * 真的把 Minecraft 侧的上下文起一遍。
 *
 * <p>其余 minecraft-test 用例都只覆盖纯逻辑（位标志映射、SPIR-V 版本字、descriptor cache），
 * 没有一个会走 {@code createInstance → createSurface → selectDevice → createDevice}。而这条路上
 * 出过两次只有真机才能发现的问题：SDL 库未加载导致呈现查询对每个队列族都返回 false（于是每个
 * 设备都被拒，报错却指向"没有图形+呈现队列族"，与真因无关），以及能力标志选择得不对。
 *
 * <p>测试因此刻意用 {@code lavaflow.baselineDevice} 起上下文——它把五项可选能力一并置为不可用，
 * 正是 ARM64 Android 设备上报的画像，也就是移动端回退路径的入口。
 *
 * <p>需要显示服务器与可用 Vulkan 设备：无 {@code DISPLAY} 时跳过（本地可用 {@code xvfb-run}）。
 * CI 会断言这个用例确实执行了、而不是被跳过。
 */
class LavaFlowVulkanContextTest {

    @Test
    void startsOnADeviceWithNothingButSwapchain() {
        assumeTrue(displayAvailable(), "需要显示服务器才能创建窗口（本地可用 xvfb-run）");

        GLFWErrorCallback errors = GLFWErrorCallback.createPrint(System.err).set();
        long window = NULL;
        Map<String, String> saved = setSwitches(Map.of(
                "lavaflow.baselineDevice", "true",
                // 与 baselineDevice 的默认行为一致的显式声明，便于失败时定位是哪一项。
                "java.awt.headless", "false"));
        try {
            assumeTrue(glfwInit(), "GLFW 初始化失败");
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            window = glfwCreateWindow(320, 240, "LavaFlow context test", NULL, NULL);
            assertNotEquals(NULL, window, "无法创建 GLFW 窗口");

            try (LavaFlowVulkanContext context = new LavaFlowVulkanContext(window)) {
                assertNotNull(context.deviceName(), "上下文起来了，但没记录设备名");

                // baselineDevice 的语义：给出五项能力俱不可用的设备画像。
                assertFalse(context.pushDescriptors(), "baselineDevice 应关闭 push descriptors");
                assertFalse(context.dynamicRendering(), "baselineDevice 应关闭 dynamic rendering");
                assertFalse(context.multiDrawIndirect(), "baselineDevice 应关闭 multi-draw indirect");
                assertFalse(context.fillModeNonSolid(), "baselineDevice 应关闭非 solid 填充模式");
                assertFalse(context.vertexAttributeDivisor(), "baselineDevice 应关闭顶点属性除数");

                VkPhysicalDeviceProperties properties = context.properties();
                assertNotNull(properties, "上下文未暴露物理设备属性");
                assertEquals(properties.deviceNameString(), context.deviceName(),
                        "deviceName 应与物理设备属性一致");
            }
        } finally {
            if (window != NULL) {
                glfwDestroyWindow(window);
            }
            glfwTerminate();
            errors.free();
            restore(saved);
        }
    }

    private static boolean displayAvailable() {
        String display = System.getenv("DISPLAY");
        return display != null && !display.isBlank();
    }

    private static Map<String, String> setSwitches(Map<String, String> values) {
        Map<String, String> previous = new HashMap<>();
        values.forEach((key, value) -> {
            String old = System.getProperty(key);
            if (old != null) {
                previous.put(key, old);
            }
            System.setProperty(key, value);
        });
        return previous;
    }

    private static void restore(Map<String, String> previous) {
        previous.forEach(System::setProperty);
    }
}
