package dev.lavaflow.minecraft;

import com.mojang.renderpearl.api.device.GpuDevice;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the line the AsyncParticles guard logs when it takes effect.
 *
 * <p>These tests exist because that line was wrong in a way nothing else would notice. It named the device
 * through a {@code {0}} parameter, and {@code java.text.MessageFormat} treats an apostrophe as the start of
 * a quoted section: "Mojang's ... ({0})" left that quote open, so the placeholder was logged verbatim and
 * the device name never appeared. The line still printed and looked plausible, which is why reading the
 * device log did not give it away. Reverting the fix fails three of these four.
 *
 * <p>They assert the message rather than captured log output deliberately. {@code System.Logger} routes to
 * {@code java.util.logging}, whose {@code ConsoleHandler} binds {@code System.err} when it is constructed;
 * by the time this class runs, other tests have already logged, so redirecting {@code System.err} around the
 * call captures nothing. What is pinned here is the string handed to the logger plus the absence of a
 * placeholder in it -- keeping formatting out of the path is then the call site's job, which is why it
 * passes a single argument.
 */
class AsyncParticlesCompatTest {

    private static GpuDevice deviceDouble() {
        return (GpuDevice) Proxy.newProxyInstance(
                AsyncParticlesCompatTest.class.getClassLoader(),
                new Class<?>[]{GpuDevice.class},
                (proxy, method, args) -> null);
    }

    @Test
    void namesTheDeviceThatAsked() {
        GpuDevice device = deviceDouble();
        String message = AsyncParticlesCompat.guardFiredMessage(device);

        assertTrue(message.contains(device.getClass().getSimpleName()),
                "the device name is missing, so the line cannot say who triggered the guard: " + message);
    }

    @Test
    void noFormatPlaceholderLeaksIntoTheLine() {
        String message = AsyncParticlesCompat.guardFiredMessage(deviceDouble());

        assertFalse(message.contains("{"),
                "an unsubstituted placeholder means MessageFormat consumed it -- see the apostrophe: " + message);
    }

    @Test
    void nullDeviceStillProducesAReadableLine() {
        assertTrue(AsyncParticlesCompat.guardFiredMessage(null).contains("null device"));
    }

    @Test
    void statesThatParticlesStayOnTheCpu() {
        // The line is where the cost of this guard is recorded; a reader who sees it in a device log should
        // not have to re-derive from the source that GPU particles are off.
        String message = AsyncParticlesCompat.guardFiredMessage(deviceDouble());

        assertTrue(message.contains("CPU"),
                "the line no longer states the consequence of the guard firing: " + message);
    }
}
