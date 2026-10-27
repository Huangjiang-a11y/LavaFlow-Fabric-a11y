package dev.lavaflow.minecraft.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * 一笔地形绘制到底在读什么。开关：-Dlavaflow.watchUploads=true。
 *
 * <p>每一帧给每个地形层各抽查一笔（solid / cutout / translucent 各一行），问三件事：
 *
 * <ul>
 *   <li>索引区间是否落在索引缓冲之内（越界读到的就是缓冲外的字节）。
 *   <li>顶点区间是否落在顶点缓冲之内，以及这一段字节<b>写入账上有没有人写过</b>。半透明顶点的写入
 *       只发生在区块建好那一刻，之后不再重写——所以只要缺口一直在，每一帧都会读到同一批坏顶点，
 *       于是每一处半透明表面都稳定地长出几个被拉得长到离谱的三角形。这就是这一行要抓的东西。
 *   <li>那一段索引能不能在主机侧读到（真机上地形索引区不可映射，读不到本身就是线索）。
 * </ul>
 */
final class LavaFlowDrawCheck {
    static final boolean ENABLED = Boolean.getBoolean("lavaflow.watchUploads");
    private static final int LINE_CAP = 200;
    private static final int VERTEX_STRIDE = 28;
    /** 索引上界折算：6 个索引对 4 个顶点。 */
    private static final double VERTICES_PER_INDEX = 4.0 / 6.0;

    private static final Map<String, Long> lastCheckedFrame = new HashMap<>();
    private static int lines;

    private LavaFlowDrawCheck() {}

    static void check(long frame, String pipelineName, LavaFlowGpuBuffer vertexBuffer, long vertexOffset,
                      int baseVertex, LavaFlowGpuBuffer indexBuffer, int indexElementBytes, int firstIndex,
                      int indexCount) {
        if (!ENABLED) return;
        if (!pipelineName.contains("terrain") && !pipelineName.contains("water")) return;
        if (lastCheckedFrame.getOrDefault(pipelineName, -1L) == frame) return;
        lastCheckedFrame.put(pipelineName, frame);
        if (lines >= LINE_CAP) return;
        lines++;
        long bound = (long) Math.ceil(indexCount * VERTICES_PER_INDEX);
        long vertexFrom = vertexOffset + (long) baseVertex * VERTEX_STRIDE;
        long vertexTo = vertexFrom + bound * VERTEX_STRIDE;
        long indexBytesNeeded = ((long) firstIndex + indexCount) * indexElementBytes;
        boolean vertexOverrun = vertexBuffer != null && vertexTo > vertexBuffer.size();
        boolean indexOverrun = indexBuffer != null && indexBytesNeeded > indexBuffer.size();
        StringBuilder line = new StringBuilder(220);
        line.append("drawcheck frame=").append(frame).append(' ').append(pipelineName)
                .append(" indexCount=").append(indexCount).append(" firstIndex=").append(firstIndex)
                .append(" baseVertex=").append(baseVertex)
                .append(" indexBytes=").append(indexBytesNeeded).append('/')
                .append(indexBuffer == null ? -1 : indexBuffer.size()).append(indexOverrun ? " INDEX-OVERRUN" : "")
                .append(" vertexBytes=").append(vertexTo).append('/')
                .append(vertexBuffer == null ? -1 : vertexBuffer.size()).append(vertexOverrun ? " VERTEX-OVERRUN" : "");
        if (vertexBuffer != null) {
            line.append(LavaFlowDataWatch.coverageReport(vertexBuffer.handle(), vertexFrom, vertexTo));
        }
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
                line.append(" maxIndex=").append(maxIndex).append(" sectionBound=").append(bound);
                if (maxIndex > bound) line.append(" INDEX-BEYOND-SECTION");
            }
        }
        System.getLogger("LavaFlow").log(
                indexOverrun || vertexOverrun ? System.Logger.Level.WARNING : System.Logger.Level.INFO,
                line.toString());
    }
}
