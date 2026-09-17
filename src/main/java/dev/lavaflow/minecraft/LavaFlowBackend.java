package dev.lavaflow.minecraft;

import com.mojang.blaze3d.GLFWErrorCapture;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.systems.GpuDevice;
import dev.lavaflow.minecraft.vulkan.LavaFlowDevice;
import dev.lavaflow.minecraft.vulkan.LavaFlowShaderc;

import java.util.Locale;

import static org.lwjgl.glfw.GLFW.GLFW_CLIENT_API;
import static org.lwjgl.glfw.GLFW.GLFW_NO_API;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;

/** Minecraft-facing boundary for the LavaFlow backend. */
public final class LavaFlowBackend implements GpuBackend {
    private static final System.Logger LOGGER = System.getLogger(LavaFlowBackend.class.getName());

    @Override
    public String getName() {
        return "LavaFlow Vulkan 1.1";
    }

    @Override
    public void setWindowHints() {
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
    }

    @Override
    public void handleWindowCreationErrors(GLFWErrorCapture.Error error) throws BackendCreationException {
        String message = error == null
                ? "Failed to create a GLFW window for LavaFlow Vulkan"
                : String.format(Locale.ROOT, "GLFW_ERROR: 0x%X", error.error());
        throw new BackendCreationException(message, BackendCreationException.Reason.GLFW_ERROR);
    }

    @Override
    public GpuDevice createDevice(
            long window,
            ShaderSource shaderSource,
            GpuDebugOptions debugOptions,
            Runnable onDeviceLost
    ) throws BackendCreationException {
        LOGGER.log(System.Logger.Level.INFO, "Creating LavaFlow-owned Vulkan 1.1 graphics device");
        try {
            LOGGER.log(System.Logger.Level.INFO, "Using LavaFlow shaderc at {0}", LavaFlowShaderc.load());
            LavaFlowDevice device = new LavaFlowDevice(window, shaderSource);
            LOGGER.log(System.Logger.Level.INFO, "Using LavaFlow Vulkan 1.1 backend on {0}",
                    device.getDeviceInfo().name());
            return new GpuDevice(device, onDeviceLost);
        } catch (RuntimeException failure) {
            throw new BackendCreationException(
                    "Failed to create LavaFlow Vulkan device: " + failure.getMessage(),
                    BackendCreationException.Reason.OTHER
            );
        }
    }
}
