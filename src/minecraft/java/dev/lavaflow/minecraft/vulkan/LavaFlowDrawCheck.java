package dev.lavaflow.minecraft.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 一笔地形绘制到底在读什么。开关：-Dlavaflow.watchUploads=true。
 *
 * <p>前几轮的结论是：绘制的三个参数、缓冲句柄、区块槽位、索引槽宽、管线状态，全都自洽。剩下的
 * 可能只有"读出去"这件事，所以这里每一帧挑一笔地形绘制，把三件事一次问清：
 *
 * <ul>
 *   <li>索引区间是否落在索引缓冲之内（越界读到的就是缓冲外的字节，顶点会被拉到莫名其妙的地方）。
 *   <li>顶点区间是否落在顶点缓冲之内。上界按每个四边形 4 个顶点、6 个索引折算。
 *   <li>索引里最大的那个值是多少，也就是这一段索引到底指向本区块自己的顶点，还是指到了别人的
 *       顶点区间。几万条索引读不出别人的区间，这一条几乎就是"顶点被拉成尖刺"的判定。
 * </ul>
 *
 * <p>索引缓冲是主机可见的（MC 用映射写它），所以这段索引可以在 CPU 侧直接读，不必等 GPU 回读；
 * 读不到（不可映射）也会记一行，那本身就是线索。每帧只查一笔，全部行数封顶。
 */
final class LavaFlowDrawCheck {
    static final boolean ENABLED = Boolean.getBoolean("lavaflow.watchUploads");
    private static final int LINE_CAP = 80;
    private static final int VERTEX_STRIDE = 28;
    /** 索引上界折算：6 个索引对 4 个顶点。 */
    private static final double VERTICES_PER_INDEX = 4.0 / 6.0;

    private static long lastFrame = -1;
    private static int lines;

    private LavaFlowDrawCheck() {}

    static void check(long frame, String pipelineName, LavaFlowGpuBuffer vertexBuffer, int baseVertex,
                      LavaFlowGpuBuffer indexBuffer, int indexElementBytes, int firstIndex, int indexCount) {
        if (!ENABLED || frame == lastFrame) return;
        if (!pipelineName.contains("terrain") && !pipelineName.contains("water")) return;
        lastFrame = frame;
        if (lines >= LINE_CAP) return;
        lines++;
        long vertexBytesNeeded = ((long) baseVertex + (long) Math.ceil(indexCount * VERTICES_PER_INDEX))
                * VERTEX_STRIDE;
        long indexBytesNeeded = ((long) firstIndex + indexCount) * indexElementBytes;
        String vertexFlag = vertexBuffer != null && vertexBytesNeeded > vertexBuffer.size() ? " OVERRUN" : "";
        String indexFlag = indexBuffer != null && indexBytesNeeded > indexBuffer.size() ? " OVERRUN" : "";
        StringBuilder line = new StringBuilder(160);
        line.append("drawcheck frame=").append(frame).append(' ').append(pipelineName)
                .append(" indexCount=").append(indexCount).append(" firstIndex=").append(firstIndex)
                .append(" baseVertex=").append(baseVertex)
                .append(" indexBytes=").append(indexBytesNeeded).append('/')
                .append(indexBuffer == null ? -1 : indexBuffer.size()).append(indexFlag)
                .append(" vertexBytes=").append(vertexBytesNeeded).append('/')
                .append(vertexBuffer == null ? -1 : vertexBuffer.size()).append(vertexFlag);
        if (indexBuffer != null) {
            ByteBuffer bytes = indexBuffer.mappedView((long) firstIndex * indexElementBytes,
                    (long) indexCount * indexElementBytes);
            if (bytes == null) {
                line.append(" indexData=<not host visible>");
            } else {
                ByteOrder order = ByteOrder.LITTLE_ENDIAN;
                ByteBuffer view = bytes.duplicate().order(order);
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
                if (maxIndex > bound) line.append(" !!index-beyond-section");
            }
        }
        System.getLogger("LavaFlow").log(System.Logger.Level.INFO, line.toString());
    }
}
