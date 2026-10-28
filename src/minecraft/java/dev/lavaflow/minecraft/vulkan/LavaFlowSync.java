package dev.lavaflow.minecraft.vulkan;

/**
 * 同步策略开关，用来照官方 Vulkan 后端逐条对齐：
 *
 * <pre>
 *   -Dlavaflow.broadBarrier=true     每次写缓冲/拷贝之后改用官方式的全局内存屏障
 *   -Dlavaflow.serializeSubmit=true  提交后立刻等这一次跑完（在飞提交压到 1）
 * </pre>
 *
 * <p>背景：数据这条路已经被真机日志整条验清——索引内容、索引槽位、顶点内容、顶点覆盖、越界、区块
 * 信息槽位全部自洽，CPU 写下去的东西和 LavaFlow 记的账都对。于是只剩"GPU 实际读到什么"，而这一层
 * 任何读自己记账的诊断都看不见。把官方 Vulkan 后端（同一套 renderpearl API，就在同一个 jar 里）
 * 反汇编出来对照，两处结构差异是清楚可查的：
 *
 * <ol>
 *   <li><b>拷贝后的屏障。</b>官方 {@code VulkanCommandEncoder.writeToBuffer/copyToBuffer} 结尾都是
 *       {@code memoryBarrier()}：一条 <em>全局</em> 屏障，两侧同为
 *       {@code VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT} + {@code MEMORY_READ|MEMORY_WRITE}
 *       （65536 / 98304，见字节码常量池），走 {@code vkCmdPipelineBarrier2KHR}。LavaFlow 则是按
 *       目标缓冲的用途位推出来的<em>窄</em>屏障（TRANSFER/TRANSFER_WRITE → VERTEX_INPUT/顶点·索引读，
 *       并且限定在那一段字节上）。窄屏障从语义看是对的，但它是"我推出来的正确"，官方的写法不依赖
 *       任何推断——广播式屏障对什么用途都不会漏。
 *   <li><b>在飞提交数。</b>官方每个提交按 {@code currentSubmitIndex} 给一条 timeline semaphore 打值，
 *       提交完立刻 {@code awaitSubmitCompletion(currentSubmitIndex - 2)}，也就是 <em>CPU 会阻塞</em>，
 *       保证上一批已经跑完；另有 {@code MAX_SUBMITS_IN_FLIGHT} 与每批一份命令池。LavaFlow 是 3 个
 *       提交槽、各自一条 fence，只在复用某个槽时（三帧之后）才等它——在飞提交可以到 3。
 * </ol>
 *
 * <p>为什么盯着"跨提交"这件事：半透明的排序索引是<em>每帧重写</em>的，而它按记录顺序落在同一批
 * 绘制的<em>后面</em>，于是"写入在提交 N、读取在提交 N+1"。这类跨提交的写后读对一次性资源（贴图、
 * 区块顶点——写一次读一辈子）最多错一帧、看不出来，但对每帧重写的索引就是每帧都错：读到的可能是
 * 上一帧那份、或拷贝到一半的那份索引。索引都是从 0 起的合法值，所以查内容、查覆盖、查最大值全过，
 * 画出来却是乱连的三角形。这正是"个别三角形被拉得长到离谱、只在半透明上、而且稳定"的形状。
 *
 * <p>两个开关都是运行期的，可以单独开一个来判断是哪条在起作用，不用重新构建。默认都不开，行为与
 * 之前版本一致。
 */
final class LavaFlowSync {
    /** 拷贝之后改用官方式的全局屏障。 */
    static final boolean BROAD_BARRIER = Boolean.getBoolean("lavaflow.broadBarrier");
    /** 提交后立刻等这一次完成，把在飞提交压到 1（最强排序，代价是不再并行）。 */
    static final boolean SERIALIZE_SUBMIT = Boolean.getBoolean("lavaflow.serializeSubmit");

    private LavaFlowSync() {}

    static void logConfiguration() {
        System.getLogger("LavaFlow").log(System.Logger.Level.INFO,
                "sync policy: broadBarrier=" + BROAD_BARRIER + " serializeSubmit=" + SERIALIZE_SUBMIT);
    }
}
