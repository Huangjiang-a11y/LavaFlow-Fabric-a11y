package dev.lavaflow.minecraft.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 上传数据的哨兵。开关：-Dlavaflow.watchUploads=true。
 *
 * <p>灵气逼人的猜测已经够多了，这一支只问一个问题：送到 GPU 的顶点数据本身是否是垃圾。判据是两条
 * 不依赖顶点格式的不变量——
 *
 * <ul>
 *   <li>整段里不该出现 NaN/Inf。合法顶点数据里（位置、UV、颜色、光照）没有一个是非有限值，所以
 *       任何一个非有限字就是一个坏字节的实证。
 *   <li>按 28 字节一行（26.4 方块顶点的 stride）取前三个 float 当位置时，不应该出现量级大得离谱的
 *       坐标。地形顶点是区块内的相对坐标，世界坐标也在几百万以内，所以绝对值超过 100 万的都记下来
 *       ——"顶点被拉成尖刺"正是这种值的形状。
 * </ul>
 *
 * <p>行数封顶，每份上传只报前几个坏字，避免把日志淹掉。这是诊断，不改任何行为。
 */
final class LavaFlowDataWatch {
    static final boolean ENABLED = Boolean.getBoolean("lavaflow.watchUploads");
    /** 整份日志最多报多少行。 */
    private static final int LINE_CAP = 60;
    /** 每次上传最多报几个坏字。 */
    private static final int PER_UPLOAD = 4;
    /** 地形顶点的 stride，前 12 字节是位置。 */
    private static final int VERTEX_STRIDE = 28;
    private static final float SUSPICIOUS_POSITION = 1.0e6f;

    private static int lines;

    private LavaFlowDataWatch() {}

    /** 按缓冲的用法分派：顶点段查位置，索引段只查非有限字。 */
    static void scanForUsage(String what, int usage, long handle, long byteOffset, ByteBuffer data) {
        if (!ENABLED) return;
        if ((usage & com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_VERTEX) != 0) {
            scan(what, handle, byteOffset, data);
        } else if ((usage & com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_INDEX) != 0) {
            scanIndex(what, handle, byteOffset, data);
        }
    }

    /** 扫一段即将送往 GPU 的顶点字节。 */
    static void scan(String what, long handle, long byteOffset, ByteBuffer data) {
        if (!ENABLED || lines >= LINE_CAP) return;
        int remaining = data.remaining();
        if (remaining < 4) return;
        ByteBuffer view = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int start = view.position();
        int end = start + remaining;
        int nonFinite = 0;
        int wildPosition = 0;
        StringBuilder detail = new StringBuilder(96);
        for (int at = start; at + 4 <= end; at += 4) {
            float value = view.getFloat(at);
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                nonFinite++;
                if (nonFinite <= PER_UPLOAD) detail.append(" nan@").append(at - start);
            } else if ((at - start) % VERTEX_STRIDE == 0 && Math.abs(value) > SUSPICIOUS_POSITION) {
                wildPosition++;
                if (wildPosition <= PER_UPLOAD) detail.append(" pos@").append(at - start).append('=').append(value);
            }
        }
        if (nonFinite == 0 && wildPosition == 0) return;
        lines++;
        System.getLogger("LavaFlow").log(System.Logger.Level.WARNING,
                what + " h=" + Long.toHexString(handle) + "@" + byteOffset + " bytes=" + remaining
                        + " nonFinite=" + nonFinite + " wildPosition=" + wildPosition + detail);
    }

    /** 扫一段索引字节：索引是整数，出现非有限字就是坏字节；顺带记最大索引值。 */
    static void scanIndex(String what, long handle, long byteOffset, ByteBuffer data) {
        if (!ENABLED || lines >= LINE_CAP) return;
        int remaining = data.remaining();
        if (remaining < 4) return;
        ByteBuffer view = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int start = view.position();
        int end = start + remaining;
        int nonFinite = 0;
        int maxShort = 0;
        int maxInt = 0;
        StringBuilder detail = new StringBuilder(64);
        for (int at = start; at + 4 <= end; at += 4) {
            float asFloat = view.getFloat(at);
            if (Float.isNaN(asFloat) || Float.isInfinite(asFloat)) {
                nonFinite++;
                if (nonFinite <= PER_UPLOAD) detail.append(" nan@").append(at - start);
            }
            maxInt = Math.max(maxInt, view.getInt(at));
            maxShort = Math.max(maxShort, view.getShort(at) & 0xFFFF);
            maxShort = Math.max(maxShort, view.getShort(at + 2) & 0xFFFF);
        }
        if (nonFinite == 0) return;
        lines++;
        System.getLogger("LavaFlow").log(System.Logger.Level.WARNING,
                what + " h=" + Long.toHexString(handle) + "@" + byteOffset + " bytes=" + remaining
                        + " nonFinite=" + nonFinite + " maxShort=" + maxShort + " maxInt=" + maxInt + detail);
    }
}
