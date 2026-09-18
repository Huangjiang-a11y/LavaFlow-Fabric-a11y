package net.caffeinemc.mods.sodium.client.gpu.device.backend;

/**
 * Signature-only stub of Sodium's draw backend selector, copied from the real class in Sodium
 * {@code 0.9.2+mc26.3}. Sodium supplies the real enum at runtime; this exists only so LavaFlow's
 * compatibility mixins compile without a Sodium artifact.
 *
 * <p>It must mirror the real shape exactly. A stub that declares members the real class does not have
 * compiles cleanly and then fails at runtime inside a mixin, which is how a {@code VkCommandBuffer} field
 * that Sodium had already removed went unnoticed until the game was launched with Sodium installed.
 */
public enum DrawBackend {
    OPENGL,
    VK_MULTIDRAW,
    VK_INDIRECT,
    BACKEND;

    public String getName() {
        throw new AssertionError("Sodium stub");
    }

    private static DrawBackend chooseBackend() {
        throw new AssertionError("Sodium stub");
    }
}
