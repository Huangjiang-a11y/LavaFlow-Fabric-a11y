package dev.lavaflow.minecraft.mixin;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.lavaflow.minecraft.mixin.stub.fun.qu_an.minecraft.asyncparticles.client.core.backend.Backends;
import dev.lavaflow.minecraft.mixin.stub.fun.qu_an.minecraft.asyncparticles.client.core.backend.VkCommands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;

/**
 * Keeps AsyncParticles from crashing on LavaFlow's Vulkan backend.
 *
 * <p>AsyncParticles decides its GPU acceleration path by reading {@code GpuDevice.backend} and
 * casting it to Mojang's {@link VulkanDevice}. LavaFlow reports its backend name as the exact
 * string "Vulkan" (so other mods that branch on that name keep working), but the backend object
 * it registers is its own {@code GpuDeviceBackend} implementation, not Mojang's {@link VulkanDevice}.
 * The unchecked cast throws {@link ClassCastException} from {@code Backends.<clinit>}.
 *
 * <p>This mixin intercepts {@code Backends.getVkCaps} and, when the backend is not Mojang's
 * {@link VulkanDevice}, returns {@link VkCommands.Unsupported()} up front. AsyncParticles then
 * reports no GPU acceleration and falls back to its CPU particle path, never reaching the later
 * {@code (VulkanDevice) ...} cast inside its Vulkan renderer.
 *
 * <p>{@link Backends} and {@link VkCommands} are referenced as class literals only so javac can
 * resolve them at compile time. The class literals point at LavaFlow-local stubs living in a
 * different package; Mixin injection is matched at runtime by method name against the real
 * AsyncParticles class. Marked {@link Pseudo} so the mixin is inert when AsyncParticles is absent.
 */
@Pseudo
@Mixin(Backends.class)
public abstract class AsyncParticlesVulkanBackendMixin {

    @Inject(method = "getVkCaps", at = @At("HEAD"), cancellable = true)
    private static void lavaflow$guardGetVkCaps(GpuDevice device, CallbackInfoReturnable<VkCommands> cir) {
        Object backendObj;
        try {
            Field f = GpuDevice.class.getDeclaredField("backend");
            f.setAccessible(true);
            backendObj = f.get(device);
        } catch (ReflectiveOperationException e) {
            backendObj = null;
        }
        if (!(backendObj instanceof VulkanDevice)) {
            cir.setReturnValue(new VkCommands.Unsupported());
        }
    }
}
