package dev.lavaflow.minecraft.vulkan;

/**
 * 同步策略。
 *
 * <p>默认：拷贝之后用<b>官方式全局屏障</b>。开关：
 *
 * <pre>
 *   -Dlavaflow.narrowBarrier=true    回到旧写法：按目标用途位推出来的窄屏障（仅作对照/测性能）
 * </pre>
 *
 * <p>结论（26.4 分支上的真机 A/B，2026-10-28）：同一份包只改这一处——旧写法（窄屏障）下，半透明
 * 表面存在稳定的几何扭曲，个别三角形被拉得长到离谱；只把拷贝后的屏障换成官方式的全局屏障，画面
 * 就干净，其余全部保持默认（绑定去重全开、UBO 走动态偏移、在飞提交照旧）。所以问题就出在这个屏障上，
 * 26.3 与 26.2 的 {@code copyToBuffer} 与 26.4 原本逐字相同，因此一并改掉。
 *
 * <p>官方写法（同一个 jar 里 {@code com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder}，
 * 与 LavaFlow 实现同一套 renderpearl 接口）：{@code writeToBuffer} 与 {@code copyToBuffer} 结尾都调
 * {@code memoryBarrier()}，发一条<em>全局</em> {@code VkMemoryBarrier2}，两侧同为
 * {@code VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT}（65536）+ {@code MEMORY_READ|MEMORY_WRITE}（98304，
 * 数值取自其字节码常量池），走 {@code vkCmdPipelineBarrier2KHR}。
 *
 * <p>为什么窄屏障会漏：它按目标缓冲的用途位推出 dst stage/access，并限定在那一段字节上——语义上
 * 说得通，但每一条用法都必须推全，漏掉任何一条，那一处就没有同步。漏同步的后果不是崩溃，而是读到
 * 还没写完的缓冲：顶点和索引都在本区块自己的合法范围内，于是查内容、查覆盖、查越界、查最大值全部
 * 通过，画面却是乱连的三角形。也只有半透明会中招——它的排序索引每帧重写，漏一次同步就每帧复现；
 * 贴图与区块顶点写一次读一辈子，错一帧根本看不出来。
 */
final class LavaFlowSync {
    /** 走旧写法：按用途位推出的窄屏障。默认 false，即使用官方那种全局屏障。 */
    static final boolean NARROW_BARRIER = Boolean.getBoolean("lavaflow.narrowBarrier");

    private LavaFlowSync() {}

    static void logConfiguration() {
        System.getLogger("LavaFlow").log(System.Logger.Level.INFO,
                "sync policy: copyBarrier=" + (NARROW_BARRIER ? "narrow(legacy)" : "global(official)"));
    }
}
