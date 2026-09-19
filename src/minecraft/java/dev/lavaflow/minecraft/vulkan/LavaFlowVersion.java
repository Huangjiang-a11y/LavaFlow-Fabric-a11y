package dev.lavaflow.minecraft.vulkan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * LavaFlow's build identity: the mod version and the commit the running jar was built from.
 *
 * <p>The pair is written at build time into {@code lavaflow-version.txt} by {@code build.gradle.kts}
 * and read here as a classpath resource. A resource rather than a jar manifest attribute because
 * {@code Package.getImplementationVersion()} depends on the classloader populating package version
 * info from the manifest, which FML's transforming classloader never does for a mod's own classes,
 * so it always returns null.
 *
 * <p>The commit travels next to the version because the version on its own does not identify a build:
 * it changes only when someone bumps it, so jars from different commits report the same string. A log
 * or crash report from such a jar then cannot be attributed to a revision, and file timestamps are all
 * that is left to tell the builds apart.
 *
 * <p>Both halves degrade to {@code dev} / {@code unknown} instead of failing. This is diagnostics, and
 * it must never be the reason a launch dies.
 */
public final class LavaFlowVersion {
    private static final String VERSION;
    private static final String COMMIT;

    static {
        Identity identity = parse(read());
        VERSION = identity.version();
        COMMIT = identity.commit();
    }

    private LavaFlowVersion() {}

    /** The mod version, for example {@code 0.1.0-alpha}. */
    public static String version() { return VERSION; }

    /** The commit the running build came from; suffixed {@code -dirty} when built from a modified tree. */
    public static String commit() { return COMMIT; }

    /** A build's identity as written by the build script. */
    record Identity(String version, String commit) {}

    /**
     * Splits the two-line resource, version first. Blank lines are dropped so that a trailing newline,
     * or a file that never got its second line, cannot shift the commit into the version slot.
     */
    static Identity parse(String content) {
        List<String> lines = content.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        return new Identity(
                lines.isEmpty() ? "dev" : lines.get(0),
                lines.size() < 2 ? "unknown" : lines.get(1));
    }

    private static String read() {
        try (InputStream in = LavaFlowVersion.class.getResourceAsStream("/lavaflow-version.txt")) {
            if (in == null) return "";
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
