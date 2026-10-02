package dev.lavaflow.minecraft;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 AsyncParticles 守卫的两件事：生效时记的那一行，以及"什么是 Mojang 的 Vulkan 设备"。
 *
 * <p>那一行值得单独钉，因为它曾经以一种别处都不会察觉的方式坏掉：它用 {@code {0}} 参数写设备名，而
 * {@code java.text.MessageFormat} 把撇号当成引用段的开始，"Mojang's ... ({0})" 让引用段一直不闭合，
 * 占位符被原样打出来，设备名从此不再出现——而整行照常打印、读着像回事，所以读设备日志根本看不出来。
 *
 * <p>断言消息本身，而不是抓日志输出：{@code System.Logger} 走 {@code java.util.logging}，它的
 * {@code ConsoleHandler} 在**构造时**就绑定了 {@code System.err}，跑到这个类时别的测试早已记过日志，
 * 于是重定向 {@code System.err} 什么也抓不到。这里钉住的是交给 logger 的那个字符串，以及它里面没有
 * 占位符——把格式化挡在路径外是调用点的事，所以那里只传一个参数。
 *
 * <p>26.2 这边替身可以比 26.3 真一点：{@code GpuDevice} 是类，所以下面用一个**有名字的子类**，
 * "这句话点名了是哪个设备"才是一条真断言（匿名类与 Proxy 的 {@code getSimpleName()} 都是空的）。
 */
class AsyncParticlesCompatTest {

    /** 有名字的替身设备：那行日志里的设备名就是它，断言因此有话可说。 */
    private static final class ProbeDevice extends GpuDevice {
        private ProbeDevice() {
            super(null, null);
        }
    }

    private static GpuDeviceBackend backendDouble() {
        return (GpuDeviceBackend) Proxy.newProxyInstance(
                AsyncParticlesCompatTest.class.getClassLoader(),
                new Class<?>[]{GpuDeviceBackend.class},
                (proxy, method, args) -> null);
    }

    @Test
    void namesTheDeviceThatAsked() {
        GpuDevice device = new ProbeDevice();
        String message = AsyncParticlesCompat.guardFiredMessage(device);

        assertTrue(message.contains("ProbeDevice"),
                "这句话没点名是哪个设备问的，读日志的人无从查起：" + message);
    }

    @Test
    void noFormatPlaceholderLeaksIntoTheLine() {
        String message = AsyncParticlesCompat.guardFiredMessage(new ProbeDevice());

        assertFalse(message.contains("{"),
                "没被替换掉的占位符意味着 MessageFormat 把它吃掉了——看撇号：" + message);
    }

    @Test
    void nullDeviceStillProducesAReadableLine() {
        assertTrue(AsyncParticlesCompat.guardFiredMessage(null).contains("null device"));
    }

    @Test
    void statesThatParticlesStayOnTheCpu() {
        // 这一行是这个守卫的代价被记下来的地方：在设备日志里看到它的人，不该自己从源码里推"GPU 粒子关了"。
        String message = AsyncParticlesCompat.guardFiredMessage(new ProbeDevice());

        assertTrue(message.contains("CPU"),
                "这句话不再说明守卫生效的后果：" + message);
    }

    @Test
    void anotherModsBackendIsNotMojangsVulkanDevice() {
        // 这一条是守卫能不能开火的前提：判错了就会把 Mojang 的设备也拦下来，或者把我们的放过去。
        assertFalse(AsyncParticlesCompat.isMojangVulkanDevice(new GpuDevice(backendDouble(), null)),
                "别的模组的后端被当成了 Mojang 的 Vulkan 设备");
    }

    @Test
    void noDeviceIsNotMojangsVulkanDevice() {
        assertFalse(AsyncParticlesCompat.isMojangVulkanDevice(null),
                "还没设备的会话被当成了 Mojang 的 Vulkan 设备");
    }

    @Test
    void unsupportedVkCapsIsNullWhenAsyncParticlesIsNotInstalled() {
        // 造不出那个类型时返回 null，而不是假装成功——那种情况下放行原方法、让它自己报错，
        // 比用一个它的代码处理不了的值取消掉好。测试类路径上没有 AsyncParticles，所以走的正是这条路。
        // 这个类会为此记一条 ERROR（"VkCommands 类找不到"），那是这条路径的**预期**行为，不是测试噪音。
        assertNull(AsyncParticlesCompat.unsupportedVkCaps(new GpuDevice(backendDouble(), null)),
                "AsyncParticles 不在类路径上，却造出了一个它的类型");
    }
}
