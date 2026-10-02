package dev.lavaflow.minecraft;

import com.mojang.renderpearl.api.device.GpuDevice;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what the Vitrail guard refuses and what it deliberately does not.
 *
 * <p>The guard answers Vitrail's "may I draw here?" as another backend, and the whole cost of that answer
 * is a pack that is not drawn. Getting it wrong in the other direction is the expensive one: a device the
 * guard cannot read is not a device to take the picture away from, which is the rule Vitrail applies to
 * itself. Two of these tests hold that line, and the message tests hold the line logged when it fires --
 * the AsyncParticles guard once lost its device name to a {@code MessageFormat} quote and printed a
 * plausible line that said nothing.
 *
 * <p>A {@code Proxy} device is enough for both directions: it is not a
 * {@code GpuDeviceBackendAccessor}, so the backend behind it cannot be read, which is exactly the
 * "unknown" case. There is no cheap way to build a device that really is LavaFlow's here, so the firing
 * direction is covered by its message alone.
 */
class VitrailCompatTest {

    private static GpuDevice deviceDouble() {
        return (GpuDevice) Proxy.newProxyInstance(
                VitrailCompatTest.class.getClassLoader(),
                new Class<?>[]{GpuDevice.class},
                (proxy, method, args) -> null);
    }

    @Test
    void noDeviceYetIsNotTreatedAsLavaFlow() {
        // Startup order: Vitrail asks before the game has picked a backend, and a yes there would refuse
        // every pack on every device, including Mojang's own.
        assertFalse(VitrailCompat.runsOnLavaFlow(null),
                "a session with no device yet was taken for LavaFlow's");
    }

    @Test
    void anUnreadableBackendIsNotTreatedAsLavaFlow() {
        assertFalse(VitrailCompat.runsOnLavaFlow(deviceDouble()),
                "a device whose backend cannot be read was taken for LavaFlow's");
    }

    @Test
    void theLineSaysWhichModIsStandingAside() {
        String message = VitrailCompat.guardFiredMessage(deviceDouble());

        assertTrue(message.contains("Vitrail"),
                "the line does not name the mod that stood aside: " + message);
    }

    @Test
    void theLineStatesTheConsequence() {
        // The cost of this guard is a pack that is not drawn; a reader of a device log should not have to
        // re-derive that from the source.
        String message = VitrailCompat.guardFiredMessage(deviceDouble());

        assertTrue(message.contains("keeps its own image"),
                "the line no longer states what the player gets instead: " + message);
    }

    @Test
    void noFormatPlaceholderLeaksIntoTheLine() {
        String message = VitrailCompat.guardFiredMessage(deviceDouble());

        assertFalse(message.contains("{"),
                "an unsubstituted placeholder means MessageFormat consumed it: " + message);
    }
}
