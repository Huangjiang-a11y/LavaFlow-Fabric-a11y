package dev.lavaflow.minecraft.vulkan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * LavaFlow's own version string, generated at build time into {@code lavaflow-version.txt} by
 * {@code build.gradle.kts} (the {@code generateLavaFlowVersion} task). Read as a classpath resource
 * rather than a jar manifest attribute: {@code Package.getImplementationVersion()} depends on the
 * classloader populating package version info from the manifest, which FML's transforming
 * classloader does not do, so it always returns null for a mod's own classes.
 */
final class LavaFlowVersion {
    private static final String VALUE = load();

    private LavaFlowVersion() {}

    static String get() { return VALUE; }

    private static String load() {
        try (InputStream in = LavaFlowVersion.class.getResourceAsStream("/lavaflow-version.txt")) {
            if (in == null) return "dev";
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return "dev";
        }
    }
}
