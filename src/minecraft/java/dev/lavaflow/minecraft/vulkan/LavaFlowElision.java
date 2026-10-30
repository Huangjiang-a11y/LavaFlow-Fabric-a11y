package dev.lavaflow.minecraft.vulkan;

import java.util.HashSet;
import java.util.Set;

/**
 * 绑定去重的总开关：-Dlavaflow.noElide=vbuf,ibuf,pipeline,uniform,descriptors（逗号分隔，可只写其中几个）。
 *
 * <p>存在理由：前几轮的真机日志已经把数据这条路整条验清了——索引内容、索引槽位、顶点内容、顶点覆盖、
 * 越界、区块信息槽位，全部自洽。也就是说 CPU 写下去的东西是对的，而 LavaFlow 自己记的账也是自洽的。
 * 那么画面还歪，只剩一种可能：<b>GPU 实际绑着的东西和 LavaFlow 账上记的不一样</b>——而这一层恰恰是任何
 * "读自己的记账" 的诊断都看不见的。
 *
 * <p>会造成这种偏差的机制只有一个来源：为了省绘制命令而做的各种去重（顶点缓冲绑定、索引缓冲绑定、
 * 管线绑定、uniform 值、描述符集合）。把它们全部关掉，每一笔绘制都老老实实重新绑一遍，语义上永远
 * 正确，只是慢一点。于是：
 *
 * <ul>
 *   <li>关掉之后画面恢复正常 → 偏差就出在某一条去重上，接下来按项二分，不用再重新构建（这个开关是
 *       运行期的，改字符串就行）。
 *   <li>关掉之后画面照旧 → 去重是无辜的，还剩最后一环：显存里 GPU 真正读到的东西。那一步要回读比对。
 * </ul>
 *
 * <p>默认全部开着（与之前版本行为一致）。
 */
final class LavaFlowElision {
    private static final Set<String> DISABLED = parse(System.getProperty("lavaflow.noElide", ""));

    /** 允许省略重复的顶点缓冲绑定。 */
    static final boolean VERTEX = !DISABLED.contains("vbuf");
    /** 允许省略重复的索引缓冲绑定。 */
    static final boolean INDEX = !DISABLED.contains("ibuf");
    /** 允许省略重复的管线绑定。 */
    static final boolean PIPELINE = !DISABLED.contains("pipeline");
    /** 允许省略取值相同的 uniform。 */
    static final boolean UNIFORM = !DISABLED.contains("uniform");
    /** 允许在描述符没有变化时跳过重新绑定。 */
    /** 允许把 UBO 偏移当作动态偏移在绑定时给出。关掉它（-Dlavaflow.bakeUniform=true）就是官方那种做法：
     * 把偏移烘焙进描述符集合、按资源+偏移去缓存集合，绑定时不给动态偏移。 */
    static final boolean DYNAMIC_UNIFORM_OFFSETS = !Boolean.getBoolean("lavaflow.bakeUniform");
    /** 允许在描述符没有变化时跳过重新绑定。 */
    static final boolean DESCRIPTORS = !DISABLED.contains("descriptors");

    private LavaFlowElision() {}

    /** 启动时打一行，好让日志自己说明这一局跑的是哪种组合。 */
    static void logConfiguration() {
        System.getLogger("LavaFlow").log(System.Logger.Level.INFO,
                "bind elision: vbuf=" + VERTEX + " ibuf=" + INDEX + " pipeline=" + PIPELINE
                + " uniform=" + UNIFORM + " descriptors=" + DESCRIPTORS
                + " dynamicUniformOffsets=" + DYNAMIC_UNIFORM_OFFSETS
                        + (DISABLED.isEmpty() ? "" : " (disabled=" + DISABLED + ")"));
    }

    private static Set<String> parse(String property) {
        Set<String> result = new HashSet<>();
        for (String part : property.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }
}
