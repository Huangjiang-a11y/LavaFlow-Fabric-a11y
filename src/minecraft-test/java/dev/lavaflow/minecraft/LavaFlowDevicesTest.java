package dev.lavaflow.minecraft;

import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import dev.lavaflow.minecraft.mixin.GpuDeviceBackendAccessor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins the contract of {@link LavaFlowDevices#backendOf}, which is the part of the device detection that
 * has already broken once.
 *
 * <p>The failure it guards against: an earlier version of the AsyncParticles guard read the backend with
 * {@code GpuDevice.class.getDeclaredField("backend")}. Because {@code GpuDevice} is an interface with no
 * fields that threw on every call, and the caller's catch block treated "cannot read" as "not LavaFlow"
 * and stepped aside — so the guard became a no-op and the crash it existed to prevent happened anyway.
 * The distinction these tests encode is therefore that {@code null} means *unknown*, while a non-null
 * backend is an actual reading of the field.
 *
 * <p>Proxies stand in for the device: the accessor is an interface, so a proxy can present both it and
 * {@code GpuDevice} the way the mixin makes {@code FrontendGpuDevice} do, and a proxy without it
 * reproduces the "mixin did not apply" case. {@code GpuDeviceBackend} is likewise an interface, so a
 * backend double needs no Vulkan device — which also means these tests cannot cover the positive
 * {@code isLavaFlow} case, as that needs a real {@code LavaFlowDevice} instance.
 */
class LavaFlowDevicesTest {

    /** A stand-in backend; any proxy will do, since only identity is asserted. */
    private static GpuDeviceBackend backendDouble() {
        return (GpuDeviceBackend) Proxy.newProxyInstance(
                LavaFlowDevicesTest.class.getClassLoader(),
                new Class<?>[]{GpuDeviceBackend.class},
                (proxy, method, args) -> null);
    }

    /** A device that exposes the accessor, as {@code FrontendGpuDevice} does once the mixin applies. */
    private static GpuDevice deviceWithAccessor(GpuDeviceBackend backend) {
        return (GpuDevice) Proxy.newProxyInstance(
                LavaFlowDevicesTest.class.getClassLoader(),
                new Class<?>[]{GpuDevice.class, GpuDeviceBackendAccessor.class},
                (proxy, method, args) -> method.getName().equals("lavaflow$backend") ? backend : null);
    }

    /** A device without the accessor: the mixin failed to apply. */
    private static GpuDevice deviceWithoutAccessor() {
        return (GpuDevice) Proxy.newProxyInstance(
                LavaFlowDevicesTest.class.getClassLoader(),
                new Class<?>[]{GpuDevice.class},
                (proxy, method, args) -> null);
    }

    @Test
    void backendOf_returnsTheBackendTheAccessorExposes() {
        GpuDeviceBackend backend = backendDouble();
        assertSame(backend, LavaFlowDevices.backendOf(deviceWithAccessor(backend)),
                "a readable backend must be reported, not discarded");
    }

    @Test
    void backendOf_returnsNullWhenTheAccessorIsMissing() {
        assertNull(LavaFlowDevices.backendOf(deviceWithoutAccessor()),
                "unknown must be reported as null rather than as a verdict");
    }

    @Test
    void backendOf_returnsNullForNoDevice() {
        assertNull(LavaFlowDevices.backendOf(null));
    }

    @Test
    void isLavaFlow_isFalseForAForeignBackend() {
        GpuDeviceBackend backend = backendDouble();
        assertFalse(LavaFlowDevices.isLavaFlow(deviceWithAccessor(backend)),
                "a non-LavaFlow backend must not be claimed as LavaFlow's");
        assertFalse(LavaFlowDevices.isLavaFlow(deviceWithoutAccessor()));
        assertFalse(LavaFlowDevices.isLavaFlow(null));
    }

    @Test
    void accessorTypeIsTheMixinInterfaceTheDeviceImplements() {
        // Guards against the accessor being moved or renamed without the device detection following it.
        GpuDeviceBackend backend = backendDouble();
        assertSame(backend, ((GpuDeviceBackendAccessor) deviceWithAccessor(backend)).lavaflow$backend());
    }
}
