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
 * <p>怎么用：哨兵不认识地形用的缓冲。它先跟着绘制路径学——每一次地形绘制把当时绑在 slot 0 的顶点
 * 缓冲和索引缓冲登记进来——然后就只盯这几个句柄的上传。这样菜单、GUI、贴图的上传不会占用预算，
 * 也不会拿"28 字节一行"去误读别的顶点格式（上一版正是这么在开机阶段把 60 行额度用光的）。
 *
 * <p>盯两件事，各自对应一种能造出"顶点被拉成尖刺"的机制：
 *
 * <ul>
 *   <li>顶点：按 28 字节一行取前三个 float 当位置，非有限或者绝对值超过一百万就记下来。
 *   <li>索引：记下这一帧每一次写入的区间与最大索引值，并当场比对同一帧里已记下的区间有没有重叠。
 *       半透明的索引区是按区块分槽、每帧重排的；同帧内两次写入叠在一起，就是"某些区块读到了别人的
 *       索引"，而它只在半透明那一层发生——因为只有那一层每帧重写。
 * </ul>
 *
 * <p>全是诊断，不改行为。
 */
final class LavaFlowDataWatch {
    static final boolean ENABLED = Boolean.getBoolean("lavaflow.watchUploads");
    /** 每次写入都打一行的话，前多少行这么打。 */
    private static final int WRITE_LINE_CAP = 150;
    /** 帧摘要最多打多少行（帧号对得上就每次都打重叠警告，不受这个限制）。 */
    private static final int SUMMARY_LINE_CAP = 20;
    private static final int SUMMARY_EVERY = 50;
    /** 同帧内记录多少个区间用于重叠比对。 */
    private static final int RANGE_CAP = 256;
    private static final int VERTEX_STRIDE = 28;
    private static final float WILD_POSITION = 1.0e6f;

    private static final Set<Long> VERTEX_HANDLES = ConcurrentHashMap.newKeySet();
    private static final Set<Long> INDEX_HANDLES = ConcurrentHashMap.newKeySet();
    private static volatile boolean worldSeen;
    private static final AtomicInteger WRITE_LINES = new AtomicInteger();
    private static final AtomicInteger SUMMARY_LINES = new AtomicInteger();

    // 同帧索引区间的记账（只在渲染线程访问）。
    private static final List<long[]> RANGES = new ArrayList<>();
    private static long rangeFrame = -1;
    private static int frameWrites;
    private static int frameOverlaps;
    private static long lastOverlapFrame = -1;

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
        if (!ENABLED || !worldSeen) return;
        if ((destinationUsage & GpuBuffer.USAGE_VERTEX) != 0 && VERTEX_HANDLES.contains(destinationHandle)) {
            checkVertices(what, destinationHandle, destinationOffset, data);
        } else if ((destinationUsage & GpuBuffer.USAGE_INDEX) != 0 && INDEX_HANDLES.contains(destinationHandle)) {
            noteIndexWrite(what, destinationHandle, destinationOffset, data);
        }
    }

    private static void checkVertices(String what, long handle, long offset, ByteBuffer data) {
        int remaining = data.remaining();
        if (remaining < VERTEX_STRIDE) return;
        ByteBuffer view = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int start = view.position();
        int rows = remaining / VERTEX_STRIDE;
        int nonFinite = 0;
        int wild = 0;
        StringBuilder detail = new StringBuilder(96);
        for (int row = 0; row < rows; row++) {
            int at = start + row * VERTEX_STRIDE;
            for (int axis = 0; axis < 3; axis++) {
                float value = view.getFloat(at + axis * 4);
                if (Float.isNaN(value) || Float.isInfinite(value)) {
                    if (nonFinite++ < 4) detail.append(" nan@").append(row).append(':').append(axis);
                } else if (Math.abs(value) > WILD_POSITION) {
                    if (wild++ < 4) detail.append(" pos@").append(row).append(':').append(axis).append('=').append(value);
                }
            }
        }
        if (nonFinite == 0 && wild == 0) return;
        if (WRITE_LINES.getAndIncrement() >= WRITE_LINE_CAP) return;
        log("vertex upload " + what + " h=" + Long.toHexString(handle) + "@" + offset
                + " bytes=" + remaining + " rows=" + rows + " nonFinite=" + nonFinite + " wildPosition=" + wild + detail);
    }

    private static void noteIndexWrite(String what, long handle, long offset, ByteBuffer data) {
        int length = data.remaining();
        long frame = LavaFlowFrameStats.totalFrames();
        if (frame != rangeFrame) {
            reportFrame(frame);
            rangeFrame = frame;
            RANGES.clear();
            frameWrites = 0;
            frameOverlaps = 0;
        }
        frameWrites++;
        long end = offset + length;
        StringBuilder clashes = null;
        for (long[] range : RANGES) {
            if (offset < range[1] && range[0] < end) {
                frameOverlaps++;
                if (clashes == null) clashes = new StringBuilder(64);
                clashes.append(" [").append(range[0]).append(',').append(range[1]).append(')');
            }
        }
        if (RANGES.size() < RANGE_CAP) RANGES.add(new long[]{offset, end});
        int maxShort = 0;
        int maxInt = 0;
        ByteBuffer view = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int start = view.position();
        int stop = start + length;
        for (int at = start; at + 4 <= stop; at += 4) {
            maxInt = Math.max(maxInt, view.getInt(at));
            maxShort = Math.max(maxShort, view.getShort(at) & 0xFFFF);
            maxShort = Math.max(maxShort, view.getShort(at + 2) & 0xFFFF);
        }
        if (clashes != null) {
            // 同帧重叠：这一行是重点，不受行数上限约束。
            log("index upload OVERLAP " + what + " h=" + Long.toHexString(handle) + " @" + offset
                    + "+" + length + " frame=" + frame + " maxIndex16=" + maxShort + " maxIndex32=" + maxInt
                    + " clashes-with=" + clashes);
            lastOverlapFrame = frame;
        }
        if (WRITE_LINES.getAndIncrement() < WRITE_LINE_CAP) {
            log("index upload " + what + " h=" + Long.toHexString(handle) + "@" + offset + " bytes=" + length
                    + " frame=" + frame + " maxIndex16=" + maxShort + " maxIndex32=" + maxInt);
        }
    }

    private static void reportFrame(long frame) {
        if (rangeFrame < 0) return;
        boolean interesting = frameOverlaps > 0 || lastOverlapFrame == rangeFrame || frame % SUMMARY_EVERY == 0;
        if (!interesting) return;
        if (SUMMARY_LINES.getAndIncrement() >= SUMMARY_LINE_CAP) return;
        log("index frame " + rangeFrame + " writes=" + frameWrites + " distinct=" + RANGES.size()
                + " overlaps=" + frameOverlaps);
    }

    private static void log(String message) {
        System.getLogger("LavaFlow").log(System.Logger.Level.WARNING, message);
    }
}
