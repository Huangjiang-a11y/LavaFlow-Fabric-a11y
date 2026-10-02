package dev.lavaflow.minecraft.mixin;

import dev.lavaflow.minecraft.VitrailCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps Vitrail Shaders from half-drawing on LavaFlow's Vulkan backend.
 *
 * <p>Vitrail decides whether its engine may draw by comparing the backend name the game reports against
 * {@code "Vulkan"}: {@code dev.vitrail.HostReport.otherBackend()} is {@code !UNKNOWN.equals(backend) &&
 * !VULKAN.equals(backend)}. LavaFlow reports exactly {@code "Vulkan"} on purpose, so that name-based
 * question answers yes -- while everything Vitrail's engine actually needs is by class, on Mojang's own
 * {@code VulkanBackend} / {@code VulkanRenderPass} / {@code VulkanDevice} / {@code VulkanCommandEncoder},
 * which LavaFlow never instantiates. The engine is therefore switched on and then cannot fill what it
 * declared: the translated shaders carry Vitrail's {@code OfGlobals} uniform block, nothing ever calls
 * {@code setUniform} for it, and the first draw dies on the empty slot with
 * {@code Missing uniform OfGlobals} out of {@code pushDescriptors}, reached from Sodium's indirect batch.
 *
 * <p>This mixin answers that one question "yes, another backend" when the device is LavaFlow's, which is
 * also the answer the rest of Vitrail already reaches by class. Its own other-backend path then does the
 * rest: the pack is neither read nor drawn, the game keeps its own image, and Vitrail says why in its log
 * and in chat. No Vitrail source is copied or extended, and nothing but the injector is declared here --
 * ordinary mixin members get merged into the target, which here is a third-party mod's class.
 *
 * <p>Targeted by fully-qualified string with {@code remap = false} because Vitrail is an optional
 * dependency, and marked {@link Pseudo} so the mixin is inert when it is absent. The class and method
 * names were checked against Vitrail Shaders v0.12.0-beta for 26.3; see {@link VitrailCompat}.
 */
@Pseudo
@Mixin(targets = "dev.vitrail.HostReport", remap = false)
public abstract class VitrailBackendMixin {

    @Inject(method = "otherBackend", at = @At("HEAD"), cancellable = true)
    private static void lavaflow$standAside(CallbackInfoReturnable<Boolean> cir) {
        if (VitrailCompat.runsOnLavaFlow()) {
            cir.setReturnValue(true);
        }
    }
}
