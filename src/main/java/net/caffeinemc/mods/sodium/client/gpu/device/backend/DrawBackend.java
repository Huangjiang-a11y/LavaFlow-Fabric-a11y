package net.caffeinemc.mods.sodium.client.gpu.device.backend;

/**
 * Signature-only stub of Sodium's draw backend selector. Sodium supplies the real enum at runtime;
 * this exists so LavaFlow's compatibility mixins can be compiled without a Sodium artifact.
 */
public enum DrawBackend {
    OPENGL,
    VK_MULTIDRAW,
    VK_INDIRECT;

    public static DrawBackend BACKEND;

    private static DrawBackend chooseBackend() {
        throw new AssertionError("Sodium stub");
    }
}
