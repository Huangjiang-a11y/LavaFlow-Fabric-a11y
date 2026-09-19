package dev.lavaflow.minecraft;

import com.mojang.blaze3d.platform.NativeLibrariesBootstrap;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.lavaflow.minecraft.vulkan.LavaFlowDevice;
import dev.lavaflow.minecraft.vulkan.LavaFlowShaderc;
import dev.lavaflow.minecraft.vulkan.LavaFlowVersion;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDLVulkan;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.vulkan.VK;

import java.util.Objects;

/**
 * Minecraft-facing boundary for the LavaFlow backend.
 *
 * <p>Minecraft drives a backend in this order (see {@code Minecraft} and {@code Window}):
 * {@code loadLibrary} then {@code createDevice}, both before any window exists, and only then
 * {@code createWindow}. A surface may be requested afterwards through
 * {@code GpuDevice.createSurface(windowHandle, isIconified)}, which the frontend calls with the
 * handle it got back from {@link #createWindow}.
 */
public final class LavaFlowBackend implements GpuBackend {
    private static final System.Logger LOGGER = System.getLogger(LavaFlowBackend.class.getName());

    private boolean libraryLoaded;
    private BackendCreationException libraryLoadFailure;

    @Override
    public String getName() {
        return "LavaFlow Vulkan 1.1";
    }

    /**
     * Hands the Vulkan loader to SDL.
     *
     * <p>SDL has to own the loader rather than LavaFlow calling into LWJGL's copy directly, because
     * {@code SDL_Vulkan_CreateSurface} resolves its entry points through the loader SDL knows about;
     * both libraries have to end up on the same instance.
     *
     * <p>The {@code vkGetInstanceProcAddr} comparison after the load is what verifies that. Skipping
     * it does not fail here -- it fails much later, as a surface that cannot be created against an
     * instance that looks fine, which is very hard to trace back to this point.
     *
     * <p>Failures are cached so that repeated calls (Minecraft walks a list of candidate backends)
     * do not retry a load the process already knows cannot succeed.
     */
    @Override
    public void loadLibrary() throws BackendCreationException {
        if (libraryLoaded) return;
        if (libraryLoadFailure != null) throw libraryLoadFailure;
        if (!NativeLibrariesBootstrap.isVulkanLoaderAvailable()) {
            libraryLoadFailure = new BackendCreationException("Vulkan loader library is missing",
                    BackendCreationException.Reason.VULKAN_LOADER_MISSING);
            throw libraryLoadFailure;
        }
        if (!SDLVulkan.SDL_Vulkan_LoadLibrary(((SharedLibrary)VK.getFunctionProvider()).getPath())) {
            libraryLoadFailure = new BackendCreationException(
                    "Vulkan is not supported: " + Objects.requireNonNullElse(SDLError.SDL_GetError(), "<no error>"),
                    BackendCreationException.Reason.PLATFORM_ERROR);
            throw libraryLoadFailure;
        }
        if (VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr")
                != SDLVulkan.SDL_Vulkan_GetVkGetInstanceProcAddr()) {
            libraryLoadFailure = new BackendCreationException("vkGetInstanceProcAddr mismatch",
                    BackendCreationException.Reason.PLATFORM_ERROR);
            SDLVulkan.SDL_Vulkan_UnloadLibrary();
            throw libraryLoadFailure;
        }
        libraryLoaded = true;
    }

    @Override
    public void unloadLibrary() {
        if (!libraryLoaded) return;
        SDLVulkan.SDL_Vulkan_UnloadLibrary();
        libraryLoaded = false;
    }

    /**
     * Creates the window LavaFlow renders into.
     *
     * <p>{@code SDL_WINDOW_VULKAN} has to be OR-ed in: without it SDL creates a window with no
     * Vulkan-capable surface, and the later surface creation fails. The handle is not kept here
     * because the frontend passes it back to {@code GpuDevice.createSurface} itself.
     */
    @Override
    public long createWindow(String title, int width, int height, long flags) {
        return SDLVideo.SDL_CreateWindow(title, width, height, SDLVideo.SDL_WINDOW_VULKAN | flags);
    }

    @Override
    public GpuDevice createDevice(GpuDebugOptions debugOptions) throws BackendCreationException {
        // Logged before anything can fail: the point of a build identity is to be in the log that
        // reports the failure, and the first line of device setup is the earliest LavaFlow can be.
        LOGGER.log(System.Logger.Level.INFO, "LavaFlow build {0} ({1})",
                LavaFlowVersion.version(), LavaFlowVersion.commit());
        LOGGER.log(System.Logger.Level.INFO, "Creating LavaFlow-owned Vulkan 1.1 graphics device");
        try {
            LOGGER.log(System.Logger.Level.INFO, "Using LavaFlow shaderc at {0}", LavaFlowShaderc.load());
            LavaFlowDevice device = new LavaFlowDevice();
            LOGGER.log(System.Logger.Level.INFO, "Using LavaFlow Vulkan 1.1 backend on {0}",
                    device.getDeviceInfo().name());
            // 26.3 splits the device in two: LavaFlowDevice is the backend, and callers only ever see
            // this frontend facade. Returning the backend directly would not type-check.
            return new FrontendGpuDevice(device);
        } catch (RuntimeException failure) {
            throw new BackendCreationException(
                    "Failed to create LavaFlow Vulkan device: " + failure.getMessage(),
                    BackendCreationException.Reason.OTHER
            );
        }
    }
}
