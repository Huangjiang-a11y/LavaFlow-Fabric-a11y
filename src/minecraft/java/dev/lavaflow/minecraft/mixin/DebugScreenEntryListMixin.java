package dev.lavaflow.minecraft.mixin;

import dev.lavaflow.minecraft.LavaFlowBufferDebugEntry;
import dev.lavaflow.minecraft.LavaFlowGpuDebugEntry;
import net.minecraft.client.core.registries.ClientRegistries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Turns LavaFlow's F3 entry on by default, without overriding a choice the player already made.
 *
 * <p>An entry that is merely registered is invisible: the status of an id that no profile mentions
 * defaults to {@code NEVER}. The statuses are rebuilt by {@code resetStatuses} from whichever profile
 * or saved list applies, so that is where the default is added -- and only when the map does not
 * mention the entry at all. A map that does mention it is either the profile's or the player's, and a
 * player who switched the line off has that choice saved, so it is left alone.
 *
 * <p>Registration itself is no longer done here: 26.4 replaced the private {@code register} method
 * with a registry bootstrap, so the entry is added by {@link DebugScreenEntriesMixin} and this mixin
 * only sets its status. The two still have to agree -- the enabled list is rebuilt by looking every
 * enabled key up in the registry, so a status without an entry would be a null dereference inside
 * Minecraft's own code.
 */
@Mixin(DebugScreenEntryList.class)
abstract class DebugScreenEntryListMixin {
    @Shadow @Final private Map<ResourceKey<DebugScreenEntry>, DebugScreenEntryStatus> allStatuses;

    @Inject(method = "resetStatuses", at = @At("TAIL"))
    private void lavaflow$enableGpuEntryByDefault(
            Map<ResourceKey<DebugScreenEntry>, DebugScreenEntryStatus> statuses, CallbackInfo callback) {
        ResourceKey<DebugScreenEntry> bufferKey = ResourceKey.create(ClientRegistries.DEBUG_SCREEN_ENTRY,
                LavaFlowBufferDebugEntry.ID);
        allStatuses.putIfAbsent(bufferKey, DebugScreenEntryStatus.IN_OVERLAY);
        ResourceKey<DebugScreenEntry> key = ResourceKey.create(ClientRegistries.DEBUG_SCREEN_ENTRY,
                LavaFlowGpuDebugEntry.ID);
        allStatuses.putIfAbsent(key, DebugScreenEntryStatus.IN_OVERLAY);
    }
}
