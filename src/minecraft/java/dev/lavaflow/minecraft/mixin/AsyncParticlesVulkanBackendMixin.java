package dev.lavaflow.minecraft.mixin;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;

/**
 * Keeps AsyncParticles from crashing on LavaFlow's Vulkan backend.
 *
 * <p>AsyncParticles decides its GPU path in the static initializer of
 * {@code fun.qu_an.minecraft.asyncparticles.client.core.backend.Backends}. For a backend whose name
 * contains "vulkan" it calls {@code getVkCaps(device)}, whose first statement is
 * {@code ((VulkanDevice) device.backend).vkDevice()}. LavaFlow reports its backend name as
 * "Vulkan" (so other mods that branch on that name keep working), but the {@code GpuDevice.backend}
 * object it registers is its own {@code GpuDeviceBackend} implementation, not Mojang's
 * {@link VulkanDevice}. The unchecked cast therefore throws {@link ClassCastException}.
 *
 * <p>This mixin intercepts {@code getVkCaps} and, when the backend is not Mojang's {@link VulkanDevice},
 * returns a real {@code VkCommands.Unsupported} up front (built reflectively from AsyncParticles' own
 * class loader so it is the exact type the method is expected to return). AsyncParticles then reports
 * no Vulkan GPU acceleration and falls back to its CPU particle path, never reaching the later cast.
 *
 * <p>The mixin targets the real AsyncParticles class by fully-qualified string (with {@code remap = false})
 * because AsyncParticles is an optional dependency that is not on the compile classpath. Marked
 * {@link Pseudo} so the mixin is inert when AsyncParticles is absent. No AsyncParticles source is copied
 * or extended; only its public {@code VkCommands.Unsupported} factory is referenced by name at runtime.
 */
@Pseudo
@Mixin(targets = "fun.qu_an.minecraft.asyncparticles.client.core.backend.Backends", remap = false)
public abstract class AsyncParticlesVulkanBackendMixin {

    @Inject(method = "getVkCaps", at = @At("HEAD"), cancellable = true)
    private static void lavaflow$guardGetVkCaps(GpuDevice device, CallbackInfoReturnable cir) {
        Object backendObj;
        try {
            Field f = GpuDevice.class.getDeclaredField("backend");
            f.setAccessible(true);
            backendObj = f.get(device);
        } catch (ReflectiveOperationException e) {
            return; // cannot inspect; let the original method run and report the failure itself
        }
        if (backendObj instanceof VulkanDevice) {
            return; // real Mojang Vulkan device: honour the original detection logic
        }
        try {
            Class<?> vkCommandsClass = Class.forName(
                    "fun.qu_an.minecraft.asyncparticles.client.core.backend.VkCommands",
                    false,
                    device.getClass().getClassLoader());
            for (Class<?> nested : vkCommandsClass.getDeclaredClasses()) {
                if ("Unsupported".equals(nested.getSimpleName())) {
                    Object unsupported = nested.getDeclaredConstructor().newInstance();
                    cir.setReturnValue(unsupported);
                    return;
                }
            }
        } catch (ReflectiveOperationException e) {
            // If we cannot build the fallback, fall through and let the original method throw.
        }
    }
}
