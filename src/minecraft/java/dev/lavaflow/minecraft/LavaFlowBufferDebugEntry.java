package dev.lavaflow.minecraft;

import dev.lavaflow.minecraft.vulkan.LavaFlowDevice;
import net.minecraft.client.gui.components.debug.DebugEntryCategory;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * LavaFlow 自己的 buffer 诊断栏目，独立于 GPU time 栏目。
 *
 * <p>只显示 {@code -Dlavaflow.debugBuffers} 打开时的事件：buffer 退休、销毁、以及
 * indirect 主机路径。作为独立条目注册，不追加到 Minecraft 或 LavaFlow 已有的任何栏目，
 * 可以像普通条目一样在调试选项里开关。
 */
public final class LavaFlowBufferDebugEntry implements DebugScreenEntry {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("lavaflow", "buffer_events");

    @Override public void display(DebugScreenDisplayer displayer, Level level, LevelChunk chunk,
                                  LevelChunk adjacentChunk) {
        if (!LavaFlowDevice.DEBUG_BUFFERS) return;
        for (String line : LavaFlowDevice.debugMessages()) {
            displayer.addPriorityLine(line);
        }
    }

    @Override public DebugEntryCategory category() { return DebugEntryCategory.RENDERER; }
}
