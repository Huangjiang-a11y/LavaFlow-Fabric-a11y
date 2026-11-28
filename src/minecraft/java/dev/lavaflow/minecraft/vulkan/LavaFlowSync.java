package dev.lavaflow.minecraft.vulkan;

/**
 * 同步策略。
 *
 * <p>默认：拷贝屏障<b>延迟合并发</b>（deferred）——每笔拷贝只记一笔账，攒到下一个落账点
 * （{@code beginRenderPass} 前或提交前）发一条<b>官方式全局屏障</b>。开关：
 *
 * <pre>
 *   -Dlavaflow.copyBarrierEager=true 每笔拷贝后立刻发一条全局屏障（4c0a957 的写法）
 *   -Dlavaflow.narrowBarrier=true    回到更旧的窄屏障写法（仅作对照/测性能）
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
 * 官方的写法不依赖任何推断，这也是屏障一直沿用它的掩码（ALL_COMMANDS → ALL_COMMANDS）的原因；
 * 发得早还是晚是另一件事，推迟到落账点不会改变掩码的含义。
 *
 * <p>{@code serializeSubmit} 保留但默认关闭：它压的是"跨提交"的在飞数（官方那边提交后会
 * {@code awaitSubmitCompletion(currentSubmitIndex - 2)}，LavaFlow 是 3 个槽各一条 fence）。屏障定为
 * 全局之后，这一条在本机上已不是必需；留着重在需要时把排序压到最强。
 */
final class LavaFlowSync {
    /** 走旧写法：按用途位推出的窄屏障。默认 false，即使用官方那种全局屏障。 */
    static final boolean NARROW_BARRIER = Boolean.getBoolean("lavaflow.narrowBarrier");
    /**
     * 是否在每笔拷贝后立即发一条全局拷贝屏障（eager）。
     * 默认关闭：拷贝屏障会被攒到下一个落账点合并成一条发（deferred），
     * 与 0.1.6 的延迟发行为一致，减少全屏屏障数量。
     *
     * <p>如需回到每笔拷贝后立即发屏障，传 {@code -Dlavaflow.copyBarrierEager=true}。
     */
    static final boolean COPY_BARRIER_EAGER =
            "true".equals(System.getProperty("lavaflow.copyBarrierEager", "false"));

    /**
     * 拷贝<b>之前</b>也发一条全局屏障（写后读 / WAR）。
     *
     * <p>已有的屏障都在拷贝之后，管的是"这次写完之后谁能读"（RAW）。缺的是另一半：这块缓冲
     * 可能刚被上一帧还在跑的绘制读过，而区块重建又会把新数据写进同一片区域 —— 写必须等那些读
     * 结束。同一队列上提交是按序提交的，但执行可以重叠，没有屏障时拷贝就能踩进正在进行的读取。
     * 读到的旧 draw 拿着旧的顶点数去解释新数据，就是"个别三角形被拉得极远"。
     *
     * <p>真机症状只在移动时出现，正好对应区块重建（静止时没有新分配、没有重用）。
     *
     * <p>官方后端同样只在拷贝后发屏障 —— 但官方后端不支持 Android，从未在 Mali 上运行过，
     * 它的形状不构成"这样写是安全的"的证据。真机对照以这个开关为准：
     * {@code -Dlavaflow.copyWarBarrier=false} 关掉。
     */
    static final boolean COPY_WAR_BARRIER =
            !"false".equals(System.getProperty("lavaflow.copyWarBarrier", "true"));

    /** 提交后立刻等这一次完成，把在飞提交压到 1（最强排序，代价是不再并行）。 */
    static final boolean SERIALIZE_SUBMIT = Boolean.getBoolean("lavaflow.serializeSubmit");

    private LavaFlowSync() {}

    static void logConfiguration() {
        LavaFlowLog.info("config", "sync copyBarrier=" + (NARROW_BARRIER ? "narrow(legacy)"
        : "global(" + (COPY_BARRIER_EAGER ? "eager" : "deferred") + ", per copy)")
                        + " warBarrier=" + COPY_WAR_BARRIER
                        + " serializeSubmit=" + SERIALIZE_SUBMIT);
    }
}
