package dev.lavaflow.minecraft.mixin;

import dev.lavaflow.minecraft.LavaFlowGpuDebugEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.resources.Identifier;
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
 * <p>An entry that is merely registered is invisible: the status of an id that no profile mentions defaults
 * to {@code NEVER}. The statuses are rebuilt by {@code resetStatuses} from whichever profile or saved list
 * applies, so that is where the default is added — and only when the map does not mention the entry at all. A
 * map that does mention it is either the profile's or the player's, and a player who switched the line off
 * has that choice saved, so it is left alone.
 *
 * <p>Registration happens in the same place, immediately before the status, because the two must agree:
 * the enabled list is rebuilt by looking every enabled id up in the entry map, so an id with a status but no
 * entry would be a null dereference inside Minecraft's own code. Both are therefore done together, through
 * Minecraft's own registration method.
 */
@Mixin(DebugScreenEntryList.class)
abstract class DebugScreenEntryListMixin {
    private static final System.Logger LOGGER =
            System.getLogger(DebugScreenEntryListMixin.class.getName());

    @Shadow @Final private Map<Identifier, DebugScreenEntryStatus> allStatuses;

    @Inject(method = "resetStatuses", at = @At("TAIL"))
    private void lavaflow$enableGpuEntryByDefault(
            Map<Identifier, DebugScreenEntryStatus> statuses, CallbackInfo callback) {
        if (DebugScreenEntries.getEntry(LavaFlowGpuDebugEntry.ID) == null) {
            try {
                DebugScreenEntriesInvoker.lavaflow$register(LavaFlowGpuDebugEntry.ID,
                        new LavaFlowGpuDebugEntry());
            } catch (AssertionError | AbstractMethodError failure) {
                // The registration helper did not land, which means this config was not applied. Said out
                // loud and then given up on rather than thrown: a missing debug line is a nuisance, while an
                // exception here would take the game down over a diagnostic.
                //
                // The status is deliberately not added either. The enabled list is rebuilt by looking every
                // enabled id up in the entry map, so an id with a status but no entry is a null dereference
                // inside Minecraft's own code — this feature must not be able to do that to itself.
                LOGGER.log(System.Logger.Level.WARNING,
                        "Could not register the LavaFlow debug entry, so F3 will not show its GPU time: "
                                + failure);
                return;
            }
        }
        allStatuses.putIfAbsent(LavaFlowGpuDebugEntry.ID, DebugScreenEntryStatus.IN_OVERLAY);
    }
}
