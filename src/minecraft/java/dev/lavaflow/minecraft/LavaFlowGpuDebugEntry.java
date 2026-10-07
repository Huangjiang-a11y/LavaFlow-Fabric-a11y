package dev.lavaflow.minecraft;

import dev.lavaflow.minecraft.vulkan.LavaFlowFrameStats;
import net.minecraft.client.gui.components.debug.DebugEntryCategory;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * LavaFlow's own GPU time, as an F3 entry of its own.
 *
 * <p>An entry rather than a line appended to Minecraft's GPU readout. Appending means writing into a
 * collection that belongs to someone else and that another mod may have replaced with one that refuses
 * writes — a fixed-size list surfacing as {@code UnsupportedOperationException} during rendering is a crash
 * that has happened in the wild — and it would also make the number impossible to switch off. As an entry it
 * appears in the debug options screen and can be toggled like any other, and nothing Minecraft owns is
 * written to.
 *
 * <p>What it reports is the GPU time LavaFlow measures for a finished frame, computed from the timestamps it
 * writes around its own submissions. Minecraft's own GPU readout answers the same question from a timer query
 * the frontend polls itself; the two are meant to be read side by side, which is why this does not replace it.
 */
public final class LavaFlowGpuDebugEntry implements DebugScreenEntry {
    /** The id the entry is registered and saved under, and what the default-on status is keyed on. */
    public static final Identifier ID = Identifier.fromNamespaceAndPath("lavaflow", "gpu_time");

    @Override public void display(DebugScreenDisplayer displayer, Level level, LevelChunk chunk,
                                  LevelChunk adjacentChunk) {
        String line = LavaFlowFrameStats.debugLine();
        if (line != null) displayer.addLine(line);
    }

    @Override public DebugEntryCategory category() { return DebugEntryCategory.RENDERER; }
}
