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
 * <p>只在 {@code -Dlavaflow.debugBuffers} 打开时显示一行汇总：本次会话累计的事件条数与
 * 日志文件路径。事件本体（buffer 退休、销毁、indirect 主机路径）写进
 * {@code logs/lavaflow-debug.log}，不再铺在 F3 上。作为独立条目注册，可以像普通条目一样
 * 在调试选项里开关。
 */
public final class LavaFlowBufferDebugEntry implements DebugScreenEntry {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("lavaflow", "buffer_events");

    @Override public void display(DebugScreenDisplayer displayer, Level level, LevelChunk chunk,
                                  LevelChunk adjacentChunk) {
        if (!LavaFlowDevice.DEBUG_BUFFERS) return;
        // 事件本体写进 logs/lavaflow-debug.log；F3 上只留一行，免得每帧被刷成一片看不清。
        displayer.addPriorityLine("LavaFlow 事件 " + LavaFlowDevice.debugEventCount()
                + " 条 -> " + LavaFlowDevice.debugLogPath());
    }

    @Override public DebugEntryCategory category() { return DebugEntryCategory.RENDERER; }
}
