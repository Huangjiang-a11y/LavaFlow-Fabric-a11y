package dev.lavaflow.minecraft;

import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;

import java.lang.reflect.Constructor;

/**
 * Support code for {@code AsyncParticlesVulkanBackendMixin}.
 *
 * <p>Kept out of the mixin class on purpose: ordinary members of a {@code @Mixin} class are merged into
 * the target class, and the target here is a third-party mod's {@code Backends}. Only the injector itself
 * belongs there; everything else lives here, where it is also reachable from tests.
 *
 * <p>AsyncParticles is an optional dependency that is not on the compile classpath, so the one
 * AsyncParticles type needed at runtime ({@code VkCommands.Unsupported}) is located by name.
 */
public final class AsyncParticlesCompat {
    private static final System.Logger LOGGER = System.getLogger("LavaFlow/AsyncParticles");

    private static final String VK_COMMANDS =
            "fun.qu_an.minecraft.asyncparticles.client.core.backend.VkCommands";

    private AsyncParticlesCompat() {}

    /**
     * Returns whether {@code device} is Mojang's own Vulkan device, in which case AsyncParticles' original
     * detection logic is correct and must be left alone.
     *
     * <p>Unknown backends answer {@code false}: the one thing AsyncParticles would do with the result is
     * cast it to {@link VulkanDevice}, which cannot succeed for anything LavaFlow registered.
     */
    public static boolean isMojangVulkanDevice(GpuDevice device) {
        return LavaFlowDevices.backendOf(device) instanceof VulkanDevice;
    }

    /**
     * Builds an {@code VkCommands.Unsupported} instance for AsyncParticles to report "no Vulkan
     * acceleration" with, or {@code null} if it cannot be built.
     *
     * <p>Located through the device's class loader rather than by direct reference, because AsyncParticles
     * may not be installed at all. Its nested type and constructor are not part of any documented API, so
     * the constructor is looked up rather than assumed, and every failure is reported: returning
     * {@code null} here leaves AsyncParticles to perform the cast that crashes the game, which the caller
     * must not do quietly.
     */
    public static Object unsupportedVkCaps(GpuDevice device) {
        Class<?> vkCommands;
        try {
            vkCommands = Class.forName(VK_COMMANDS, false,
                    device == null ? null : device.getClass().getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            LOGGER.log(System.Logger.Level.ERROR, "AsyncParticles' VkCommands class was not found", e);
            return null;
        }
        Class<?> nested = null;
        try {
            for (Class<?> candidate : vkCommands.getDeclaredClasses()) {
                if ("Unsupported".equals(candidate.getSimpleName())) {
                    nested = candidate;
                    break;
                }
            }
        } catch (LinkageError e) {
            LOGGER.log(System.Logger.Level.ERROR, "Could not enumerate VkCommands' nested types", e);
            return null;
        }
        if (nested == null) {
            LOGGER.log(System.Logger.Level.ERROR, "VkCommands.Unsupported is missing");
            return null;
        }
        for (Constructor<?> constructor : nested.getDeclaredConstructors()) {
            if (constructor.getParameterCount() != 0) continue;
            try {
                if (!constructor.trySetAccessible()) continue;
                return constructor.newInstance();
            } catch (ReflectiveOperationException e) {
                LOGGER.log(System.Logger.Level.ERROR, "Could not instantiate VkCommands.Unsupported", e);
                return null;
            }
        }
        LOGGER.log(System.Logger.Level.ERROR, "VkCommands.Unsupported has no accessible no-arg constructor");
        return null;
    }
}
