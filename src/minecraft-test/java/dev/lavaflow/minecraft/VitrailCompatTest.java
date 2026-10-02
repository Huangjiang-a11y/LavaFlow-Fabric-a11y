package dev.lavaflow.minecraft;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住这条守卫拒绝什么、以及它**刻意不**拒绝什么。
 *
 * <p>守卫把 Vitrail 的"我能在这儿绘制吗"回答成"另一个后端"，代价是一个包不被绘制。反过来答错才是贵的：
 * 一个读不出来的设备不是可以把画面拿走的设备——这正是 Vitrail 给自己定的规矩。下面两条守着这条线，
 * 消息那几条守着守卫生效时记的那一行（AsyncParticles 那条守卫曾经把设备名丢给 {@code MessageFormat}
 * 的引号，打出一句读着像回事、其实什么也没说的话）。
 *
 * <p>26.2 这边替身可以比 26.3 真一点：{@code GpuDevice} 是类，{@code new GpuDevice(后端, null)} 就是一个
 * 真的设备对象，只是它的后端不是我们的。于是"别的模组的设备不被拒绝"这条能真钉住。
 *
 * <p>盖不到的是正例：要让守卫真的生效，需要一个真正的 {@code LavaFlowDevice}，那会建一个 Vulkan 上下文。
 * 所以生效方向只由它记的那一行覆盖。
 */
class VitrailCompatTest {

    /** 替身后端；只断言身份与类型，所以随便一个 Proxy 都行。 */
    private static GpuDeviceBackend backendDouble() {
        return (GpuDeviceBackend) Proxy.newProxyInstance(
                VitrailCompatTest.class.getClassLoader(),
                new Class<?>[]{GpuDeviceBackend.class},
                (proxy, method, args) -> null);
    }

    /** 一个设备，其后端不是 LavaFlow 的——也就是别的模组或 Mojang 自己的后端。 */
    private static GpuDevice deviceWithSomeoneElsesBackend() {
        return new GpuDevice(backendDouble(), null);
    }

    @Test
    void noDeviceYetIsNotTreatedAsLavaFlow() {
        // 启动顺序：Vitrail 在游戏挑定后端之前就问，而在那儿答"是"会把每个设备上的每个包都拒掉，
        // 包括 Mojang 自己的设备。
        assertFalse(VitrailCompat.runsOnLavaFlow(null),
                "还没设备的会话被当成了 LavaFlow");
    }

    @Test
    void someoneElsesBackendIsNotTreatedAsLavaFlow() {
        assertFalse(VitrailCompat.runsOnLavaFlow(deviceWithSomeoneElsesBackend()),
                "别的模组的设备被当成了 LavaFlow：这条守卫会把不该动的包也拒掉");
    }

    @Test
    void theLineSaysWhichModIsStandingAside() {
        String message = VitrailCompat.guardFiredMessage(deviceWithSomeoneElsesBackend());

        assertTrue(message.contains("Vitrail"),
                "这句话没说清是哪个模组站到了一边：" + message);
    }

    @Test
    void theLineStatesTheConsequence() {
        // 这条守卫的代价是一个包不被绘制；读设备日志的人不该自己去源码里推这件事。
        String message = VitrailCompat.guardFiredMessage(deviceWithSomeoneElsesBackend());

        assertTrue(message.contains("keeps its own image"),
                "这句话不再说明玩家换到的是什么：" + message);
    }

    @Test
    void noFormatPlaceholderLeaksIntoTheLine() {
        String message = VitrailCompat.guardFiredMessage(deviceWithSomeoneElsesBackend());

        assertFalse(message.contains("{"),
                "没被替换掉的占位符意味着 MessageFormat 把它吃掉了：" + message);
    }
}
