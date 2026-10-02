package dev.lavaflow.minecraft.vulkan;

import static org.lwjgl.vulkan.VK10.*;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
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
    void startsOnADeviceWithNothingButSwapchain() throws IOException {
        assumeTrue(displayAvailable(), "需要显示服务器才能创建窗口（本地可用 xvfb-run）");

        long window = NULL;
        Map<String, String> saved = setSwitches(Map.of(
                "lavaflow.baselineDevice", "true",
                // 与 baselineDevice 的默认行为一致的显式声明，便于失败时定位是哪一项。
                "java.awt.headless", "false"));
        try {
            LavaFlowTestSupport.ensureGlfw();
            exposeVulkanLoaderToLwjgl();
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

                // 内存堆与类型只读一次并缓存。缓存没填上时，堆大小会是 0、类型查找会抛——所以这两条
                // 断言覆盖的是"表真的读到了"，而不只是"方法没崩"。
                assertNotEquals(0L, context.largestDeviceLocalHeapSize(),
                        "缓存里应有设备本地堆的大小");
                assertNotEquals(-1, context.findMemoryType(0xFFFF, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT),
                        "缓存里应能找到主机可见的内存类型");

                VkPhysicalDeviceProperties properties = context.properties();
                assertNotNull(properties, "上下文未暴露物理设备属性");
                assertEquals(properties.deviceNameString(), context.deviceName(),
                        "deviceName 应与物理设备属性一致");
            }
        } finally {
            if (window != NULL) {
                glfwDestroyWindow(window);
            }
            restore(saved);
        }
    }

    /**
     * 桌面发行版把 Vulkan loader（libvulkan.so.1）放在多架构目录（如 /usr/lib/x86_64-linux-gnu），
     * 那个目录不在 JVM 默认的 java.library.path 里；而 LWJGL 解压原生库时会把
     * org.lwjgl.librarypath 设成自己的解压目录，此后只在那里按文件名找库，于是 VK 初始化会以
     * {@code Failed to locate library: libvulkan.so.1} 失败——报错点完全看不出与原生库有关。
     *
     * <p>这里把系统 loader 拷进 LWJGL 正在查找的那个目录。找不到系统 loader 时什么都不做，
     * 让失败保持原样，而不是把它掩盖过去。
     */
    private static void exposeVulkanLoaderToLwjgl() throws IOException {
        Path loader = LOADER_DIRS.stream()
                .map(dir -> dir.resolve("libvulkan.so.1"))
                .filter(Files::exists)
                .findFirst()
                .orElse(null);
        String searchPath = System.getProperty("org.lwjgl.librarypath");
        if (loader == null || searchPath == null || searchPath.isBlank()) {
            return;
        }
        for (String dir : searchPath.split(File.pathSeparator)) {
            Path target = Path.of(dir).resolve("libvulkan.so.1");
            if (!Files.exists(target)) {
                Files.copy(loader, target);
            }
        }
    }

    private static final List<Path> LOADER_DIRS = List.of(
            Path.of("/usr/lib/x86_64-linux-gnu"),
            Path.of("/usr/lib/aarch64-linux-gnu"),
            Path.of("/usr/lib64"),
            Path.of("/usr/lib"),
            Path.of("/lib"),
            Path.of("/usr/local/lib"));

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
