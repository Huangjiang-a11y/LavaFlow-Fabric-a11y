package dev.lavaflow.minecraft.vulkan;

import com.mojang.renderpearl.api.buffers.GpuBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 地形上传数据的哨兵。开关：-Dlavaflow.watchUploads=true。
 *
 * <p>索引那一侧已经被真机日志证明是干净的：每一次写入的最大索引都恰好等于该区块顶点数的上界减一
 * （6 个索引对 4 个顶点），写入落在的偏移与绘制读的 firstIndex 一一对应。所以这一版把注意力全放到
 * 顶点侧：地形顶点是区块内的相对坐标，落在 0..16 这一格附近，任何"被拉成尖刺"的顶点都会远远超出。
 *
 * <p>做法：
 *
 * <ul>
 *   <li>登记过句柄的顶点缓冲，每一次上传都报一行摘要（字节数、行数、三个轴的最小最大值），不管好
 *       坏——这样至少能知道顶点区到底有没有被重写、坐标的取值范围是多少。
 *   <li>还没登记、但单次上传 ≥ 4096 字节的顶点数据也报（前若干行）。世界加载时的顶点上传发生在
 *       第一笔地形绘制之前，按"认句柄"是永远看不见的，这一段专门留给它。
 *   <li>绝对坐标超过 64 或者非有限的，标 WILD 并单列出来。阈值取 64 而不是一百万：区块相对坐标
 *       离 0..16 再远也不会到 64，而上一版的宽阈值等于什么都不查。
 *   <li>索引侧只留区间重叠（同一次写入经过 writeToBuffer 与内部 copyToBuffer 会被报两遍，完全同
 *       区间的那一遍不再算重叠）。
 * </ul>
 *
 * <p>全是诊断，不改行为。
 */
final class LavaFlowDataWatch {
    static final boolean ENABLED = Boolean.getBoolean("lavaflow.watchUploads");
    /** 顶点摘要最多打多少行。 */
    private static final int VERTEX_LINE_CAP = 90;
    /** 索引写入的普通行最多打多少行。 */
    private static final int INDEX_LINE_CAP = 60;
    /** 未登记但足够大的顶点上传，最多打多少行。 */
    private static final int UNWATCHED_MIN_BYTES = 4096;
    private static final int VERTEX_STRIDE = 28;
    /** 区块相对坐标的宽松上界；超过这个值几乎一定是坏顶点。 */
    private static final float POSITION_LIMIT = 64f;

    private static final Set<Long> VERTEX_HANDLES = ConcurrentHashMap.newKeySet();
    private static final Set<Long> INDEX_HANDLES = ConcurrentHashMap.newKeySet();
    private static volatile boolean worldSeen;
    private static final AtomicInteger VERTEX_LINES = new AtomicInteger();
    private static final AtomicInteger INDEX_LINES = new AtomicInteger();

    private static final List<long[]> RANGES = new ArrayList<>();
    private static long rangeFrame = -1;
    private static int frameWrites;

    private LavaFlowDataWatch() {}

    /** 由绘制路径登记：这一笔地形绘制用的顶点/索引缓冲。第一次登记即认为世界已经开画。 */
    static void watchDraw(long vertexHandle, long indexHandle) {
        if (!ENABLED) return;
        worldSeen = true;
        if (vertexHandle != 0) VERTEX_HANDLES.add(vertexHandle);
        if (indexHandle != 0) INDEX_HANDLES.add(indexHandle);
    }

    /**
     * 一份即将落到某个缓冲上的字节。
     *
     * @param what              日志里怎么称呼这次上传
     * @param destinationUsage  目标缓冲的用法位
     * @param destinationHandle 目标缓冲句柄
     * @param destinationOffset 这段字节在目标缓冲里的起始偏移
     * @param data              字节本身（只读，不改 position）
     */
    static void onUpload(String what, int destinationUsage, long destinationHandle, long destinationOffset,
                         ByteBuffer data) {
        if (!ENABLED) return;
        if ((destinationUsage & GpuBuffer.USAGE_VERTEX) != 0) {
            boolean watched = worldSeen && VERTEX_HANDLES.contains(destinationHandle);
            if (watched || data.remaining() >= UNWATCHED_MIN_BYTES) {
                checkVertices(what, destinationHandle, destinationOffset, data, watched);
            }
        } else if ((destinationUsage & GpuBuffer.USAGE_INDEX) != 0) {
            if (worldSeen && INDEX_HANDLES.contains(destinationHandle)) {
                noteIndexWrite(what, destinationHandle, destinationOffset, data);
            }
        }
    }

    private static void checkVertices(String what, long handle, long offset, ByteBuffer data, boolean watched) {
        int remaining = data.remaining();
        if (remaining < VERTEX_STRIDE) return;
        ByteBuffer view = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int start = view.position();
        int rows = remaining / VERTEX_STRIDE;
        int nonFinite = 0;
        int wild = 0;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int row = 0; row < rows; row++) {
            int at = start + row * VERTEX_STRIDE;
            for (int axis = 0; axis < 3; axis++) {
                float value = view.getFloat(at + axis * 4);
                if (Float.isNaN(value) || Float.isInfinite(value)) {
                    nonFinite++;
                    continue;
                }
                if (Math.abs(value) > POSITION_LIMIT) wild++;
                switch (axis) {
                    case 0 -> {
                        if (value < minX) minX = value;
                        if (value > maxX) maxX = value;
                    }
                    case 1 -> {
                        if (value < minY) minY = value;
                        if (value > maxY) maxY = value;
                    }
                    default -> {
                        if (value < minZ) minZ = value;
                        if (value > maxZ) maxZ = value;
                    }
                }
            }
        }
        boolean suspicious = nonFinite > 0 || wild > 0;
        if (VERTEX_LINES.getAndIncrement() >= VERTEX_LINE_CAP) return;
        boolean finite = rows > 0 && nonFinite < rows * 3;
        String bounds = finite
                ? " x=[" + minX + "," + maxX + "] y=[" + minY + "," + maxY + "] z=[" + minZ + "," + maxZ + "]"
                : " <no finite positions>";
        System.getLogger("LavaFlow").log(suspicious ? System.Logger.Level.WARNING : System.Logger.Level.INFO,
                "vertex upload " + what + (watched ? "" : " (unwatched)") + " h=" + Long.toHexString(handle)
                        + "@" + offset + " bytes=" + remaining + " rows=" + rows
                        + " nonFinite=" + nonFinite + " wild=" + wild + bounds);
    }

    private static void noteIndexWrite(String what, long handle, long offset, ByteBuffer data) {
        int length = data.remaining();
        long frame = LavaFlowFrameStats.totalFrames();
        if (frame != rangeFrame) {
            rangeFrame = frame;
            RANGES.clear();
            frameWrites = 0;
        }
        frameWrites++;
        long end = offset + length;
        long clashOffset = -1, clashEnd = -1;
        for (long[] range : RANGES) {
            // 同一次写入会经过 writeToBuffer 与它内部的 copyToBuffer 报两遍，区间完全一样的不算重叠。
            if (offset == range[0] && end == range[1]) continue;
            if (offset < range[1] && range[0] < end) {
                clashOffset = range[0];
                clashEnd = range[1];
            }
        }
        if (RANGES.size() < 256) RANGES.add(new long[]{offset, end});
        if (clashOffset >= 0) {
            System.getLogger("LavaFlow").log(System.Logger.Level.WARNING,
                    "index upload OVERLAP " + what + " h=" + Long.toHexString(handle) + " @" + offset
                            + "+" + length + " frame=" + frame + " clashes-with=[" + clashOffset + "," + clashEnd + ")");
        }
        if (INDEX_LINES.getAndIncrement() < INDEX_LINE_CAP) {
            System.getLogger("LavaFlow").log(System.Logger.Level.INFO,
                    "index upload " + what + " h=" + Long.toHexString(handle) + "@" + offset + " bytes=" + length
                            + " frame=" + frame);
        }
    }
}
