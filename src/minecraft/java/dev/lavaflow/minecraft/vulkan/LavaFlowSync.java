package dev.lavaflow.minecraft.vulkan;

/**
 * 同步策略。
 *
 * <p>默认：拷贝之后用<b>官方式全局屏障</b>。开关：
 *
 * <pre>
 *   -Dlavaflow.narrowBarrier=true    回到旧写法：按目标用途位推出来的窄屏障（仅作对照/测性能）
 *   -Dlavaflow.serializeSubmit=true  提交后立刻等这一次跑完（在飞提交压到 1）
 * </pre>
 *
 * <p>结论（真机 A/B，2026-10-28）：同一份包上只改这一处——之前几局用窄屏障，半透明表面存在稳定的
 * 几何扭曲（个别三角形被拉得长到离谱）；这一局只把拷贝后的屏障换成官方式的全局屏障，画面就干净了。
 * 其余全部保持默认（绑定去重全开、UBO 走动态偏移、在飞提交照旧）。所以问题就出在这个屏障上。
 *
 * <p>官方写法（同一个 jar 里 {@code com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder}，
 * 与 LavaFlow 实现同一套 renderpearl 接口）是：{@code writeToBuffer} 与 {@code copyToBuffer} 结尾
 * 都调 {@code memoryBarrier()}，发一条<em>全局</em> {@code VkMemoryBarrier2}，两侧同为
 * {@code VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT}（65536）+ {@code MEMORY_READ|MEMORY_WRITE}（98304，
 * 数值取自其字节码常量池），走 {@code vkCmdPipelineBarrier2KHR}。LavaFlow 原来的窄屏障
 * （{@code TRANSFER/TRANSFER_WRITE} → 按用途位推出的 {@code VERTEX_INPUT}/顶点·索引读，且限定在
 * 那一段字节）从语义上看是对的，但那是"推出来的正确"：只要有一条用法没被推全，那一处就会漏同步，
 * 而漏掉的后果不是崩溃、是读到还没写完的缓冲——画面上就是乱连的三角形，任何读自己记账的诊断都看不见。
 * 官方的写法不依赖任何推断，这也是这一版把它定为默认的原因。
 *
 * <p>{@code serializeSubmit} 保留但默认关闭：它压的是"跨提交"的在飞数（官方那边提交后会
 * {@code awaitSubmitCompletion(currentSubmitIndex - 2)}，LavaFlow 是 3 个槽各一条 fence）。屏障定为
 * 全局之后，这一条在本机上已不是必需；留着重在需要时把排序压到最强。
 */
final class LavaFlowSync {
    /** 走旧写法：按用途位推出的窄屏障。默认 false，即使用官方那种全局屏障。 */
    static final boolean NARROW_BARRIER = Boolean.getBoolean("lavaflow.narrowBarrier");
    /**
     * 每笔拷贝之后立刻发那条官方全局屏障（ALL_COMMANDS → ALL_COMMANDS，MEMORY_READ|MEMORY_WRITE）。
     *
     * <p>这就是 4c0a957 那一版的写法，也是 2026-10-28 真机 A/B 里把"半透明表面几何扭曲、个别三角形
     * 被拉得长到离谱"修好的那一版。19a3b8d 之后为省开销改成"记账 + 延迟发"，前提是"拷贝和读它的
     * 绘制之间一定隔着一次 createRenderPass"——而后来通道内缓冲写屏障的出现证明这个前提不成立。
     *
     * <p>延迟发属于"推出来的正确"：只要有一条用法没被推全，那一处就漏同步，画面就是乱连的三角形，
     * 而任何靠读自己记账的诊断都看不见它。默认回到无条件发。
     *
     * <p>{@code -Dlavaflow.eagerCopyBarrier=false} 回到延迟发（测开销用）。
     */
    static final boolean EAGER_COPY_BARRIER =
            !"false".equals(System.getProperty("lavaflow.eagerCopyBarrier", "true"));

    /** 提交后立刻等这一次完成，把在飞提交压到 1（最强排序，代价是不再并行）。 */
    static final boolean SERIALIZE_SUBMIT = Boolean.getBoolean("lavaflow.serializeSubmit");

    private LavaFlowSync() {}

    static void logConfiguration() {
        System.getLogger("LavaFlow").log(System.Logger.Level.INFO,
                "sync policy: copyBarrier=" + (NARROW_BARRIER ? "narrow(legacy)"
                        : EAGER_COPY_BARRIER ? "global(eager, per copy)" : "global(deferred)")
                        + " serializeSubmit=" + SERIALIZE_SUBMIT);
    }
}
