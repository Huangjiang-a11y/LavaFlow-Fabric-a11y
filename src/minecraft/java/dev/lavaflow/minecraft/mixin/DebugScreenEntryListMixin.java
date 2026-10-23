package dev.lavaflow.minecraft.mixin;

import dev.lavaflow.minecraft.LavaFlowBufferDebugEntry;
import dev.lavaflow.minecraft.LavaFlowGpuDebugEntry;
import dev.lavaflow.minecraft.vulkan.LavaFlowDevice;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
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
 *
 * <p>The buffer-events entry is only registered and enabled when {@code -Dlavaflow.debugBuffers} is set;
 * otherwise it is not registered at all, so it cannot appear as an empty column in the debug options.
 */
@Mixin(DebugScreenEntryList.class)
abstract class DebugScreenEntryListMixin {
    private static final System.Logger LOGGER =
            System.getLogger(DebugScreenEntryListMixin.class.getName());

    @Shadow @Final private Map<Identifier, DebugScreenEntryStatus> allStatuses;

    @Inject(method = "resetStatuses", at = @At("TAIL"))
    private void lavaflow$enableGpuEntryByDefault(
            Map<Identifier, DebugScreenEntryStatus> statuses, CallbackInfo callback) {
        if (register(new LavaFlowGpuDebugEntry(), LavaFlowGpuDebugEntry.ID, "GPU time")) {
            allStatuses.putIfAbsent(LavaFlowGpuDebugEntry.ID, DebugScreenEntryStatus.IN_OVERLAY);
        }
        if (LavaFlowDevice.DEBUG_BUFFERS
                && register(new LavaFlowBufferDebugEntry(), LavaFlowBufferDebugEntry.ID, "buffer events")) {
            allStatuses.putIfAbsent(LavaFlowBufferDebugEntry.ID, DebugScreenEntryStatus.IN_OVERLAY);
        }
    }

    /**
     * Registers an entry through Minecraft's own method. Returns whether the id is registered afterwards,
     * so the caller only adds a status when the entry actually exists — a status without an entry is a
     * null dereference inside Minecraft's enabled-list rebuild.
     */
    private static boolean register(DebugScreenEntry entry, Identifier id, String what) {
        if (DebugScreenEntries.getEntry(id) != null) return true;
        try {
            DebugScreenEntriesInvoker.lavaflow$register(id, entry);
            return true;
        } catch (AssertionError | AbstractMethodError failure) {
            // The registration helper did not land, which means this config was not applied. Said out
            // loud and then given up on rather than thrown: a missing debug line is a nuisance, while an
            // exception here would take the game down over a diagnostic.
            LOGGER.log(System.Logger.Level.WARNING,
                    "Could not register the LavaFlow debug entry, so F3 will not show its " + what
                            + ": " + failure);
            return false;
        }
    }
}
