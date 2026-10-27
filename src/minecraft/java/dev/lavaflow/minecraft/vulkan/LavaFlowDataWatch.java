package dev.lavaflow.minecraft.vulkan;

import com.mojang.renderpearl.api.buffers.GpuBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 地形上传数据的哨兵。开关：-Dlavaflow.watchUploads=true。
 *
 * <p>索引那一侧已经由真机日志验清：每次写入的最大索引恰好等于该区块顶点数的上界减一，写入落在的偏移
 * 与绘制读的 firstIndex 一一对应。剩下唯一没量过的是<b>顶点</b>，而且它是"被拉成尖刺"最直接的来源：
 * 一个三角形被拉得长到离谱，就是它三个顶点里有一个读到了别处。
 *
 * <p>顶点数据只在地形缓冲第一次被填（区块建好）时写一次，之后不再重写；而那一刻发生在第一笔地形
 * 绘制之前，按"认句柄"永远看不见——前两版就是这么漏掉的。所以这里换一条识别途径，按<b>缓冲本身</b>
 * 认：用法带 VERTEX 且尺寸 ≥ 1 MiB 的就是地形那几块场地（solid 顶点区 128 MiB、半透明索引区 32 MiB，
 * 而 GUI、字体、粒子那些都是几十 KB）。同时对每一个这样的句柄记一本<b>写入账</b>，记下到底有哪些
 * 字节被人写过。
 *
 * <p>于是顶点这条路可以被完整地问两遍：
 *
 * <ul>
 *   <li>内容：这一次上传的字节按 28 字节一行读出来的三个坐标是不是正常（区块相对坐标应落在 0..16
 *       附近）。行数与三个轴的最小最大值都打出来，好坏都打。
 *   <li>落点：绘制要读的那段顶点字节，写入账上有没有人写过。有洞就说明这一段读到的是从未写过的
 *       内存——那就是尖刺的成因，并且洞的位置直接指出版权在谁手里。
 * </ul>
 */
final class LavaFlowDataWatch {
    static final boolean ENABLED = Boolean.getBoolean("lavaflow.watchUploads");
    private static final int VERTEX_LINE_CAP = 70;
    private static final int INDEX_LINE_CAP = 40;
    private static final int VERTEX_STRIDE = 28;
    /** 场地级缓冲的下限：地形顶点区与索引区都远大于它，GUI/字体/粒子都远小于它。 */
    private static final long ARENA_MIN_BYTES = 1L << 20;
    /** 区块相对坐标的宽松上界。 */
    private static final float POSITION_LIMIT = 64f;
    /** 每个句柄最多记多少个写入区间，防止无限增长。 */
    private static final int COVERAGE_LIMIT = 8192;

    private static final Set<Long> INDEX_HANDLES = ConcurrentHashMap.newKeySet();
    /** 句柄 → 已写过的字节区间（已合并）。 */
    private static final Map<Long, List<long[]>> COVERAGE = new ConcurrentHashMap<>();
    private static final AtomicInteger VERTEX_LINES = new AtomicInteger();
    private static final AtomicInteger INDEX_LINES = new AtomicInteger();

    private static final List<long[]> RANGES = new ArrayList<>();
    private static long rangeFrame = -1;

    private LavaFlowDataWatch() {}

    /** 由绘制路径登记地形用的索引缓冲（顶点区改用尺寸识别，不靠这一路）。 */
    static void watchDraw(long vertexHandle, long indexHandle) {
        if (!ENABLED) return;
        if (indexHandle != 0) INDEX_HANDLES.add(indexHandle);
    }

    static void onUpload(String what, int destinationUsage, GpuBuffer destination, long destinationOffset,
                         ByteBuffer data) {
        if (!ENABLED) return;
        long handle = ((LavaFlowGpuBuffer) destination).handle();
        boolean vertexUsage = (destinationUsage & GpuBuffer.USAGE_VERTEX) != 0;
        boolean indexUsage = (destinationUsage & GpuBuffer.USAGE_INDEX) != 0;
        long size = destination.size();
        if (vertexUsage && (size >= ARENA_MIN_BYTES || COVERAGE.containsKey(handle))) {
            noteCoverage(handle, destinationOffset, data.remaining());
            checkVertices(what, handle, destinationOffset, data);
        } else if (indexUsage && INDEX_HANDLES.contains(handle)) {
            noteIndexWrite(what, handle, destinationOffset, data);
        }
    }

    private static void noteCoverage(long handle, long offset, long length) {
        if (length <= 0) return;
        List<long[]> ranges = COVERAGE.computeIfAbsent(handle, key -> new ArrayList<>());
        long start = offset;
        long end = offset + length;
        synchronized (ranges) {
            int at = 0;
            while (at < ranges.size()) {
                long[] range = ranges.get(at);
                if (range[1] < start) {
                    at++;
                } else if (end < range[0]) {
                    break;
                } else {
                    start = Math.min(start, range[0]);
                    end = Math.max(end, range[1]);
                    ranges.remove(at);
                }
            }
            if (ranges.size() < COVERAGE_LIMIT) ranges.add(at, new long[]{start, end});
        }
    }

    /**
     * 这段顶点字节有没有人写过。返回一句可以贴进日志的话。
     *
     * <p>没记过账和"记过账但这段是空的"要分开说：前者是没看见写入，后者才是真的洞。
     */
    static String coverageReport(long handle, long from, long to) {
        if (!ENABLED || to <= from) return "";
        List<long[]> ranges = COVERAGE.get(handle);
        if (ranges == null) return " vertexCoverage=<no write ever seen>";
        long covered = 0;
        long holeAt = -1;
        long cursor = from;
        synchronized (ranges) {
            for (long[] range : ranges) {
                if (range[1] <= cursor) continue;
                if (range[0] > cursor) {
                    holeAt = cursor;
                    break;
                }
                cursor = Math.max(cursor, range[1]);
                if (cursor >= to) break;
            }
        }
        if (holeAt < 0 && cursor < to) holeAt = cursor;
        // 粗算覆盖率：区间是合并过的，直接累加落在 [from,to) 里的部分。
        synchronized (ranges) {
            for (long[] range : ranges) {
                long overlapStart = Math.max(range[0], from);
                long overlapEnd = Math.min(range[1], to);
                if (overlapEnd > overlapStart) covered += overlapEnd - overlapStart;
            }
        }
        double percent = 100.0 * covered / (to - from);
        String text = " vertexCoverage=" + String.format("%.1f%%", percent) + "/" + (to - from) + "B";
        return holeAt < 0 ? text : text + " HOLE@+" + (holeAt - from);
    }

    private static void checkVertices(String what, long handle, long offset, ByteBuffer data) {
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
        boolean finite = nonFinite < rows * 3;
        String bounds = finite
                ? " x=[" + minX + "," + maxX + "] y=[" + minY + "," + maxY + "] z=[" + minZ + "," + maxZ + "]"
                : " <no finite positions>";
        System.getLogger("LavaFlow").log(suspicious ? System.Logger.Level.WARNING : System.Logger.Level.INFO,
                "vertex upload " + what + " h=" + Long.toHexString(handle) + "@" + offset
                        + " bytes=" + remaining + " rows=" + rows
                        + " nonFinite=" + nonFinite + " wild=" + wild + bounds);
    }

    private static void noteIndexWrite(String what, long handle, long offset, ByteBuffer data) {
        int length = data.remaining();
        long frame = LavaFlowFrameStats.totalFrames();
        if (frame != rangeFrame) {
            rangeFrame = frame;
            RANGES.clear();
        }
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
