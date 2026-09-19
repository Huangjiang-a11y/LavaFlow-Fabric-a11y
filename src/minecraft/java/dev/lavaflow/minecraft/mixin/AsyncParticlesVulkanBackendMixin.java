package dev.lavaflow.minecraft.mixin;

import com.mojang.renderpearl.api.device.GpuDevice;
import dev.lavaflow.minecraft.AsyncParticlesCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps AsyncParticles from crashing on LavaFlow's Vulkan backend.
 *
 * <p>AsyncParticles decides its GPU path in the static initializer of
 * {@code fun.qu_an.minecraft.asyncparticles.client.core.backend.Backends}. For a backend whose name
 * contains "vulkan" it calls {@code getVkCaps(device)}, whose first statement is
 * {@code ((VulkanDevice) device.backend).vkDevice()}. LavaFlow reports its backend name as "Vulkan" (so
 * mods that branch on that name keep working), but the backend object it registers is its own
 * {@code GpuDeviceBackend} implementation, not Mojang's {@code VulkanDevice}. The unchecked cast throws
 * {@link ClassCastException}, and because it happens in a class initializer the whole game fails rather
 * than one feature.
 *
 * <p>This mixin intercepts {@code getVkCaps} and, unless the backend really is Mojang's Vulkan device,
 * returns an AsyncParticles {@code VkCommands.Unsupported}. AsyncParticles then reports no Vulkan GPU
 * acceleration and takes its CPU particle path, never reaching the cast.
 *
 * <p>An earlier version of this guard read the backend with
 * {@code GpuDevice.class.getDeclaredField("backend")}. {@code GpuDevice} is a pure interface with no
 * fields, so that threw {@code NoSuchFieldException} on every call and the catch block's {@code return}
 * let the original method run: the guard was a no-op that produced the very crash it existed to prevent.
 * The lookup now goes through {@link AsyncParticlesCompat} and the shared accessor, so it cannot fail
 * that way, and the remaining failure path reports itself instead of falling through silently.
 *
 * <p>Targeted by fully-qualified string with {@code remap = false} because AsyncParticles is an optional
 * dependency; marked {@link Pseudo} so the mixin is inert when it is absent. No AsyncParticles source is
 * copied or extended, and nothing but the injector is declared here — ordinary mixin members get merged
 * into the target, which here is a third-party mod's class.
 */
@Pseudo
@Mixin(targets = "fun.qu_an.minecraft.asyncparticles.client.core.backend.Backends", remap = false)
public abstract class AsyncParticlesVulkanBackendMixin {

    @Inject(method = "getVkCaps", at = @At("HEAD"), cancellable = true)
    private static void lavaflow$guardGetVkCaps(GpuDevice device, CallbackInfoReturnable cir) {
        if (AsyncParticlesCompat.isMojangVulkanDevice(device)) {
            return; // real Mojang Vulkan device: the original detection logic is correct
        }
        Object unsupported = AsyncParticlesCompat.unsupportedVkCaps(device);
        if (unsupported == null) {
            // Reported by the helper; falling through here means AsyncParticles will cast and crash, which
            // is better than cancelling with a value its code cannot handle.
            return;
        }
        cir.setReturnValue(unsupported);
    }
}
