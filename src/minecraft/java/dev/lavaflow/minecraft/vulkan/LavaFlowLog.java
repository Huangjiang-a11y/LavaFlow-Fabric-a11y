package dev.lavaflow.minecraft.vulkan;

import java.util.HashMap;
import java.util.Map;

/**
 * 日志的唯一出口。
 *
 * <p>之前每个类各自 {@code System.getLogger("LavaFlow")} 加自己发明的格式，同一条链路上的信息
 * 一半带 {@code [LavaFlow pass-seq]}、一半什么都没有，还都按帧刷：一次启动三十秒就写出十万行，
 * 真正有用的那几行（启动配置、每五秒的汇总、警告）被埋在中间。
 *
 * <p>这里定死三件事：
 *
 * <ul>
 *   <li><b>格式</b>：{@code [lavaflow:<area>] 正文}。area 是固定的几个：{@code config / device /
 *       frame / pass / gpu / draw / sync / mem / depth / upload / error}。
 *   <li><b>节奏</b>：{@link #info} 只用于每五秒一次的汇总；逐帧的东西一律走
 *       {@link #throttled}（同一 key 最快多少毫秒一条）或聚合后进汇总；
 *       {@link #once} 一次启动只出一条。
 *   <li><b>开关</b>：{@code -Dlavaflow.logVerbose=true} 才放 {@link #debug}；
 *       {@code -Dlavaflow.logPassRaw=true} 才放逐通道的原始行（默认只有聚合）。
 * </ul>
 */
final class LavaFlowLog {
    /** 放行 {@link #debug}：开发时看细节用，正常启动不开。 */
    static final boolean VERBOSE = Boolean.getBoolean("lavaflow.logVerbose");
    /** 放行逐通道原始行（默认只在五秒汇总里给聚合结果）。 */
    static final boolean PASS_RAW = Boolean.getBoolean("lavaflow.logPassRaw");

    private static final System.Logger LOGGER = System.getLogger("LavaFlow");
    private static final Map<String, Long> LAST_AT = new HashMap<>();
    private static final Map<String, Boolean> SEEN = new HashMap<>();

    private LavaFlowLog() {}

    /** 汇总类信息：只在每五秒的块里用。 */
    static void info(String area, String message) {
        LOGGER.log(System.Logger.Level.INFO, tag(area) + message);
    }

    /** 真出问题才用：布局不符、校验层报错、退路被触发。 */
    static void warn(String area, String message) {
        LOGGER.log(System.Logger.Level.WARNING, tag(area) + message);
    }

    /** 细节诊断：{@code -Dlavaflow.logVerbose=true} 才出。 */
    static void debug(String area, String message) {
        if (!VERBOSE) return;
        LOGGER.log(System.Logger.Level.INFO, tag(area) + message);
    }

    /** 一次启动只出一条（版本、能力、配置）。 */
    static void once(String area, String message) {
        if (SEEN.putIfAbsent(area + '\u0000' + message, Boolean.TRUE) != null) return;
        LOGGER.log(System.Logger.Level.INFO, tag(area) + message);
    }

    /**
     * 逐帧类的东西走这里：同一个 key 距上一条不足 {@code minMillis} 就丢掉。
     *
     * <p>热路径上的异常（帧时间超阈值、通道被拆）用它，既能第一时间看到，又不会一秒钟刷几百行。
     */
    static void throttled(String area, String key, long minMillis, String message) {
        long now = System.nanoTime();
        long last = LAST_AT.getOrDefault(key, 0L);
        if (last != 0 && now - last < minMillis * 1_000_000L) return;
        LAST_AT.put(key, now);
        LOGGER.log(System.Logger.Level.INFO, tag(area) + message);
    }

    /** 一个汇总块：每行自带 [lavaflow:*] 标签，整体写成一条记录，省掉行行重复的日志前缀。 */
    static void block(java.util.List<String> lines) {
        if (lines.isEmpty()) return;
        LOGGER.log(System.Logger.Level.INFO, String.join("\n", lines));
    }

    /**
     * 启动时一行说清哪些开关开着。以前是各模块自己打一行 {@code ... policy: ...}，散在不同位置，
     * 还都不说自己默认值是什么。现在只列非默认的，全默认就明说"全默认"。
     */
    static void configSummary() {
        String[][] flags = {
            {"frameStats", "lavaflow.frameStats", "帧统计(5秒汇总)"},
            {"passGpuTiming", "lavaflow.passGpuTiming", "通道GPU计时"},
            {"logPassRaw", "lavaflow.logPassRaw", "逐通道原始行"},
            {"logVerbose", "lavaflow.logVerbose", "细节诊断"},
            {"depthDebug", "lavaflow.depthDebug", "深度清屏诊断"},
            {"validation", "lavaflow.validation", "校验层"},
            {"watchUploads", "lavaflow.watchUploads", "上传自检"},
            {"narrowBarrier", "lavaflow.narrowBarrier", "旧式窄屏障"},
            {"serializeSubmit", "lavaflow.serializeSubmit", "串行提交"},
            {"skipRepeatDepthClear", "lavaflow.skipRepeatDepthClear", "跳过重复深度清屏"},
            {"noTransitionBatch", "lavaflow.noTransitionBatch", "关闭转换合批"},
        };
        StringBuilder line = new StringBuilder("[lavaflow:config] ");
        int on = 0;
        for (String[] flag : flags) {
            if (!Boolean.getBoolean(flag[1])) continue;
            if (on++ == 0) line.append("开着的开关: ");
            else line.append(", ");
            line.append(flag[2]).append('(').append(flag[0]).append(')');
        }
        if (on == 0) line.append("全部默认（诊断关闭，只打五秒汇总与警告）");
        LOGGER.log(System.Logger.Level.INFO, line.toString());
    }

    private static String tag(String area) { return "[lavaflow:" + area + "] "; }
}
