package dev.lavaflow.minecraft.mixin;

import dev.lavaflow.minecraft.LavaFlowBufferDebugEntry;
import dev.lavaflow.minecraft.LavaFlowGpuDebugEntry;
import net.minecraft.client.core.registries.ClientRegistries;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Registers LavaFlow's F3 entry.
 *
 * <p>26.4 made debug entries registry-driven: {@code DebugScreenEntries} no longer has a private
 * {@code register} to call through, and an entry does not exist anywhere until the registry bootstrap
 * has run. {@code bootstrap} is therefore where it goes -- the same path Minecraft's own entries take,
 * so whatever invariants the registry keeps stay kept, and an entry added there is indistinguishable
 * from a built-in one everywhere else.
 *
 * <p>Registering is not showing: the status in {@code DebugScreenEntryList} is what turns the line on,
 * and that is {@link DebugScreenEntryListMixin}'s job.
 */
@Mixin(DebugScreenEntries.class)
abstract class DebugScreenEntriesMixin {
    @Inject(method = "bootstrap", at = @At("TAIL"))
    private static void lavaflow$registerEntry(Registry<DebugScreenEntry> registry,
                                               CallbackInfoReturnable<Object> callback) {
        ResourceKey<DebugScreenEntry> bufferKey = ResourceKey.create(ClientRegistries.DEBUG_SCREEN_ENTRY,
                LavaFlowBufferDebugEntry.ID);
        if (!registry.containsKey(bufferKey)) {
            Registry.register(registry, bufferKey, new LavaFlowBufferDebugEntry());
        }
        ResourceKey<DebugScreenEntry> key = ResourceKey.create(ClientRegistries.DEBUG_SCREEN_ENTRY,
                LavaFlowGpuDebugEntry.ID);
        if (!registry.containsKey(key)) {
            // 26.4's Registry exposes registration as static entry points only; the instance method that
            // 26.3 had is gone. This is the same path vanilla's own bootstrap uses.
            Registry.register(registry, key, new LavaFlowGpuDebugEntry());
        }
    }
}
