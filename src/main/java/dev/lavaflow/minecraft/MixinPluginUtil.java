package dev.lavaflow.minecraft;

/**
 * Shared utilities for LavaFlow's conditional mixin configuration plugins.
 */
public final class MixinPluginUtil {
    private MixinPluginUtil() {}

    /**
     * Returns whether a class with the given binary name is available on the classpath without
     * initialising it. Uses a resource lookup rather than {@code Class.forName} so that no static
     * initialisers run and the method is safe to call from mixin {@code onLoad}.
     */
    public static boolean isClassPresent(String binaryName) {
        String resource = binaryName.replace('.', '/') + ".class";
        ClassLoader loader = MixinPluginUtil.class.getClassLoader();
        if (loader != null && loader.getResource(resource) != null) return true;
        return ClassLoader.getSystemClassLoader().getResource(resource) != null;
    }
}
