package dev.lavaflow.minecraft.vulkan;

import static org.lwjgl.vulkan.VK10.*;
import org.junit.jupiter.api.Test;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.sdl.SDLInit.*;
import static org.lwjgl.sdl.SDLVideo.*;

/**
 * 真的把 Minecraft 侧的上下文起一遍。
 *
 * <p>其余 minecraft-test 用例都只覆盖纯逻辑（位标志映射、SPIR-V 版本字、descriptor cache），
 * 没有一个会走 {@code createInstance → selectDevice → createDevice → createSurface}。而这条路上出过
 * 只有真机／桌面才能发现的问题：本分支的 {@code createInstance} 若不显式调用
 * {@code SDL_Vulkan_LoadLibrary}，呈现查询会对每个队列族都返回 false，于是每个设备都被拒，报错却是
 * "No Vulkan 1.1 device with a combined graphics and presentation queue family found"——真因与呈现
 * 队列族无关，因而极具误导性。
 *
 * <p>测试因此刻意用 {@code lavaflow.baselineDevice} 起上下文——它把五项可选能力一并置为不可用，
 * 正是 ARM64 Android 设备上报的画像，也就是移动端回退路径的入口。
 *
 * <p>**必须用 X11 等真实窗口系统驱动**：offscreen 驱动没有实现呈现查询钩子，SDL 会直接返回 true，
 * 上面那条路径就覆盖不到了。
 *
 * <p>需要显示服务器与可用 Vulkan 设备：无 {@code DISPLAY} 时跳过（本地可用 {@code xvfb-run}）。
 * CI 会断言这个用例确实执行了、而不是被跳过。
 */
class LavaFlowVulkanContextTest {

    @Test
    void startsOnADeviceWithNothingButSwapchain() throws IOException {
        assumeTrue(displayAvailable(), "需要显示服务器才能初始化 SDL 视频子系统（本地可用 xvfb-run）");

        Map<String, String> saved = setSwitches(Map.of("lavaflow.baselineDevice", "true"));
        long window = 0L;
        try {
            assumeTrue(SDL_Init(SDL_INIT_VIDEO), "SDL 视频子系统初始化失败");
            exposeVulkanLoaderToLwjgl();

            // 窗口必须在**上下文之后**创建，顺序不能颠倒：带 SDL_WINDOW_VULKAN 的窗口会隐式加载
            // Vulkan 库，先建窗口就把"库尚未加载"这个前提抹掉了——而那正是本用例要覆盖的东西。
            // Minecraft 的真实顺序同样是先建设备、后建窗口，这里刻意照抄。
            try (LavaFlowVulkanContext context = new LavaFlowVulkanContext()) {
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

                // 设备起来之后才建窗口，然后才有 surface。
                window = SDL_CreateWindow("LavaFlow context test", 320, 240,
                        SDL_WINDOW_VULKAN | SDL_WINDOW_HIDDEN);
                assertNotEquals(0L, window, "无法创建 SDL Vulkan 窗口");
                assertNotEquals(0L, context.createSurface(window), "createSurface 未返回表面");
            }
        } finally {
            if (window != 0L) {
                SDL_DestroyWindow(window);
            }
            SDL_Quit();
            restore(saved);
        }
    }

    /**
     * 桌面发行版把 Vulkan loader（libvulkan.so.1）放在多架构目录（如 /usr/lib/x86_64-linux-gnu），
     * 那个目录不在 JVM 默认的 java.library.path 里；而 LWJGL 解压原生库时会把
     * org.lwjgl.librarypath 设成自己的解压目录，此后只在那里按文件名找库。SDL3 在镜像里没有，
     * 必须解压，于是搜索被收缩、loader 找不到，VK 初始化报
     * {@code Failed to locate library: libvulkan.so.1}——报错点却完全看不出与原生库有关。
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
