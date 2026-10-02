package dev.lavaflow.minecraft;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 钉住 {@link LavaFlowDevices#backendOf} 的约定 —— 设备判定里已经坏过一次的那一段。
 *
 * <p>它防的是一种失败：早先的 AsyncParticles 守卫用
 * {@code GpuDevice.class.getDeclaredField("backend")} 取后端，取不到就把"读不出来"当成"不是 LavaFlow"
 * 然后站到一边，于是守卫成了空操作，它本来要拦的那个崩溃照旧发生。所以这里钉的是那个区分：
 * {@code null} 意味着**不知道**，非 {@code null} 才是一次真实的读取。
 *
 * <p>本分支（26.2）比 26.3 幸运：{@code GpuDevice} 是**类**，{@code backend} 字段就在它自己身上，
 * 所以"字段读得到"这件事本身可以用真 {@code GpuDevice} 钉住（26.3 那边只能用 Proxy 模拟 accessor 是否
 * 生效）。替身只用在**后端**上——{@code GpuDeviceBackend} 是接口，Proxy 造一个就够了，不需要真设备。
 *
 * <p>盖不到的是正例（真的 {@code LavaFlowDevice}）：构造它会建一个 Vulkan 上下文，需要真驱动。
 */
class LavaFlowDevicesTest {

    /** 替身后端；只断言身份，所以随便一个 Proxy 都行。 */
    private static GpuDeviceBackend backendDouble() {
        return (GpuDeviceBackend) Proxy.newProxyInstance(
                LavaFlowDevicesTest.class.getClassLoader(),
                new Class<?>[]{GpuDeviceBackend.class},
                (proxy, method, args) -> null);
    }

    @Test
    void backendOf_returnsTheFieldTheDeviceCarries() {
        GpuDeviceBackend backend = backendDouble();

        assertSame(backend, LavaFlowDevices.backendOf(new GpuDevice(backend, null)),
                "GpuDevice.backend 就声明在 GpuDevice 自己身上，读不到说明这条判定已经悄悄失效了");
    }

    @Test
    void backendOf_hasNoAnswerForNoDevice() {
        assertNull(LavaFlowDevices.backendOf(null), "还没设备的会话被当成了有后端");
    }

    @Test
    void aDeviceCarryingNoBackendIsUnknownNotLavaFlow() {
        assertNull(LavaFlowDevices.backendOf(new GpuDevice(null, null)),
                "没有后端的设备被当成了读到了后端");
    }

    @Test
    void anotherModsBackendIsNotLavaFlow() {
        assertFalse(LavaFlowDevices.isLavaFlow(new GpuDevice(backendDouble(), null)),
                "别的模组的后端被当成了 LavaFlow 的");
    }
}
