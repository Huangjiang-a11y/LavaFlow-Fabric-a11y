package dev.lavaflow.minecraft.mixin;

import dev.lavaflow.minecraft.LavaFlowBufferDebugEntry;
import dev.lavaflow.minecraft.LavaFlowGpuDebugEntry;
import net.minecraft.client.core.registries.ClientRegistries;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.core.WritableRegistry;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Registers LavaFlow's F3 entry.
 *
 * <p>26.4 made debug entries registry-driven: {@code DebugScreenEntries} no longer has a private
 * {@code register} to call through, and an entry does not exist anywhere until the registry bootstrap
 * has run. 26.4-snapshot-8 changed that bootstrap's signature from {@code Registry} to
 * {@code WritableRegistry} and made it {@code void} -- with the {@code CallbackInfo} from before, the
 * injector failed to apply and neither F3 entry was ever registered. {@code bootstrap} is therefore where it goes -- the same path Minecraft's own entries take,
 * so whatever invariants the registry keeps stay kept, and an entry added there is indistinguishable
 * from a built-in one everywhere else.
 *
 * <p>Registering is not showing: the status in {@code DebugScreenEntryList} is what turns the line on,
 * and that is {@link DebugScreenEntryListMixin}'s job.
 */
@Mixin(DebugScreenEntries.class)
abstract class DebugScreenEntriesMixin {
    @Inject(method = "bootstrap", at = @At("TAIL"))
    private static void lavaflow$registerEntry(WritableRegistry<DebugScreenEntry> registry,
                                               CallbackInfo callback) {
        ResourceKey<DebugScreenEntry> bufferKey = ResourceKey.create(ClientRegistries.DEBUG_SCREEN_ENTRY,
                LavaFlowBufferDebugEntry.ID);
        if (!registry.containsKey(bufferKey)) {
            registry.register(bufferKey, new LavaFlowBufferDebugEntry());
        }
        ResourceKey<DebugScreenEntry> key = ResourceKey.create(ClientRegistries.DEBUG_SCREEN_ENTRY,
                LavaFlowGpuDebugEntry.ID);
        if (!registry.containsKey(key)) {
            // snapshot-8 把这个形参收窄成 WritableRegistry，注册也回到实例方法 register(key, value) 上；
            // vanilla 自己的 bootstrap 用的就是同一条路。
            registry.register(key, new LavaFlowGpuDebugEntry());
        }
    }
}
