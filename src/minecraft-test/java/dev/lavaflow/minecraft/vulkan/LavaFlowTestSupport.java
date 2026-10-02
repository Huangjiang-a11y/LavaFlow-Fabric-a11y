package dev.lavaflow.minecraft.vulkan;

import org.lwjgl.glfw.GLFWErrorCallback;

import static org.lwjgl.glfw.GLFW.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The setup two LavaFlow tests need before they can talk to a real driver.
 *
 * <p>Both {@code LavaFlowVulkanContextTest} and {@code LavaFlowDescriptorPathTest} bring up an actual
 * GLFW window system and Vulkan device, so both need the same two workarounds described below. They
 * live here rather than in each test because a second copy is exactly the kind of thing that drifts —
 * and because a workaround that quietly stops working makes a test pass while covering nothing, which
 * this repository has already been bitten by once (see {@link #exposeVulkanLoaderToLwjgl()}).
 */
final class LavaFlowTestSupport {

    private LavaFlowTestSupport() {}

    /**
     * Whether a display server is reachable. Both tests need one: an offscreen window system does not implement
     * the presentation query hook and answers true for every queue family, which is precisely the
     * path the context test exists to cover. Local runs use {@code xvfb-run}.
     */
    static boolean displayAvailable() {
        String display = System.getenv("DISPLAY");
        return display != null && !display.isBlank();
    }


    private static boolean glfwReady;

    /**
     * Brings GLFW up exactly once per JVM, and deliberately never tears it down again.
     *
     * <p>Two test classes need GLFW and run in the same JVM. Creating the error callback again after a
     * class has freed it and terminated GLFW raises {@code NullPointerException} inside LWJGL's upcall
     * table ({@code Upcalls.upcallGet}) -- which names neither the class at fault nor the reason. So the
     * callback is installed once and left installed; the process exits shortly after, which is the
     * cheapest correct lifetime here.
     */
    static synchronized void ensureGlfw() {
        if (glfwReady) return;
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("GLFW 初始化失败");
        glfwReady = true;
    }
    /**
     * Desktop distributions keep the Vulkan loader (libvulkan.so.1) in a multiarch directory such as
     * {@code /usr/lib/x86_64-linux-gnu}, which is not on the JVM's default search path; and LWJGL
     * points {@code org.lwjgl.librarypath} at its own extraction directory as it unpacks natives, so
     * from then on it looks for libraries by name only in there. GLFW has to
     * be extracted, so that search is what gets narrowed and the loader is not found — VK
     * initialisation then fails with {@code Failed to locate library: libvulkan.so.1}, an error that
     * gives no hint of being about a native library at all.
     *
     * <p>This copies the system loader into the directory LWJGL is looking in. When no system loader
     * is found it does nothing, leaving the failure intact rather than papering over it.
     */
    static void exposeVulkanLoaderToLwjgl() throws IOException {
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

    /**
     * Sets each switch, returning what {@link #restore} needs to undo it. A switch that was not set
     * before is recorded as {@code null} rather than dropped: the tests run in one JVM, so a switch
     * left behind by one case silently changes what the next one tests — and these are exactly the
     * switches that decide which code path a case covers.
     */
    static Map<String, String> setSwitches(Map<String, String> values) {
        Map<String, String> previous = new HashMap<>();
        values.forEach((key, value) -> {
            previous.put(key, System.getProperty(key));
            System.setProperty(key, value);
        });
        return previous;
    }

    /** Puts back what {@link #setSwitches} displaced, clearing the ones that were not set before. */
    static void restore(Map<String, String> previous) {
        previous.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
    }
}
