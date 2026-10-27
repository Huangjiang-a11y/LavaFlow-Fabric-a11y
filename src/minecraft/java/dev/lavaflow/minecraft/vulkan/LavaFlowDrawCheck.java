package dev.lavaflow.minecraft.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * 一笔地形绘制到底在读什么。开关：-Dlavaflow.watchUploads=true。
 *
 * <p>每一帧给每个层各抽查一笔（solid / cutout / translucent 各一行），问三件事：索引区间是否落在索引
 * 缓冲之内、顶点区间是否落在顶点缓冲之内、以及这一段索引能不能在主机侧读到。半透明层的 firstIndex
 * 已经到六万多，索引缓冲只要比它小，那一笔读的就是缓冲外的字节——顶点会被拉到莫名其妙的地方，这
 * 正是"被拉成尖刺"的形状，所以越界必须单独盯住。
 *
 * <p>索引缓冲在真机上是不可映射的（MC 用 writeToBuffer 分段写它），所以"读索引内容"这一路对地形
 * 不通；那一段已经由上传哨兵从写入侧验过：每次写入的最大索引恰好等于上界减一。
 */
final class LavaFlowDrawCheck {
    static final boolean ENABLED = Boolean.getBoolean("lavaflow.watchUploads");
    private static final int LINE_CAP = 150;
    private static final int VERTEX_STRIDE = 28;
    /** 索引上界折算：6 个索引对 4 个顶点。 */
    private static final double VERTICES_PER_INDEX = 4.0 / 6.0;

    private static final Map<String, Long> lastCheckedFrame = new HashMap<>();
    private static int lines;

    private LavaFlowDrawCheck() {}

    static void check(long frame, String pipelineName, LavaFlowGpuBuffer vertexBuffer, int baseVertex,
                      LavaFlowGpuBuffer indexBuffer, int indexElementBytes, int firstIndex, int indexCount) {
        if (!ENABLED) return;
        if (!pipelineName.contains("terrain") && !pipelineName.contains("water")) return;
        if (lastCheckedFrame.getOrDefault(pipelineName, -1L) == frame) return;
        lastCheckedFrame.put(pipelineName, frame);
        if (lines >= LINE_CAP) return;
        lines++;
        long vertexBytesNeeded = ((long) baseVertex + (long) Math.ceil(indexCount * VERTICES_PER_INDEX))
                * VERTEX_STRIDE;
        long indexBytesNeeded = ((long) firstIndex + indexCount) * indexElementBytes;
        boolean vertexOverrun = vertexBuffer != null && vertexBytesNeeded > vertexBuffer.size();
        boolean indexOverrun = indexBuffer != null && indexBytesNeeded > indexBuffer.size();
        StringBuilder line = new StringBuilder(176);
        line.append("drawcheck frame=").append(frame).append(' ').append(pipelineName)
                .append(" indexCount=").append(indexCount).append(" firstIndex=").append(firstIndex)
                .append(" baseVertex=").append(baseVertex)
                .append(" indexBytes=").append(indexBytesNeeded).append('/')
                .append(indexBuffer == null ? -1 : indexBuffer.size()).append(indexOverrun ? " INDEX-OVERRUN" : "")
                .append(" vertexBytes=").append(vertexBytesNeeded).append('/')
                .append(vertexBuffer == null ? -1 : vertexBuffer.size()).append(vertexOverrun ? " VERTEX-OVERRUN" : "");
        if (indexBuffer != null) {
            ByteBuffer bytes = indexBuffer.mappedView((long) firstIndex * indexElementBytes,
                    (long) indexCount * indexElementBytes);
            if (bytes == null) {
                line.append(" indexData=<not host visible>");
            } else {
                ByteBuffer view = bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                int start = view.position();
                int maxIndex = 0;
                for (int i = 0; i < indexCount; i++) {
                    int at = start + i * indexElementBytes;
                    if (at + indexElementBytes > view.limit()) break;
                    int value = indexElementBytes == 2 ? (view.getShort(at) & 0xFFFF) : view.getInt(at);
                    if (value > maxIndex) maxIndex = value;
                }
                long bound = (long) Math.ceil(indexCount * VERTICES_PER_INDEX);
                line.append(" maxIndex=").append(maxIndex).append(" sectionBound=").append(bound);
                if (maxIndex > bound) line.append(" INDEX-BEYOND-SECTION");
            }
        }
        System.getLogger("LavaFlow").log(
                indexOverrun || vertexOverrun ? System.Logger.Level.WARNING : System.Logger.Level.INFO,
                line.toString());
    }
}
