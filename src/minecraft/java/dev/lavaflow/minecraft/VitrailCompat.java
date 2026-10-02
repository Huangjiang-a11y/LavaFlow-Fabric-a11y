package dev.lavaflow.minecraft;

import com.mojang.blaze3d.systems.GpuDevice;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code VitrailBackendMixin} 的支持代码。
 *
 * <p>刻意不放进 mixin 类里：{@code @Mixin} 类的普通成员会被合并进目标类，而这里的目标是第三方模组的
 * {@code HostReport}。只有注入器本身属于那里，其余都放这儿——放这儿还顺带能被测试够到。
 *
 * <p>Vitrail Shaders 是把 OptiFine 格式的包跑在**游戏自己那个 Vulkan 后端**上的光影引擎。它靠**名字**
 * 判断能不能绘制：{@code dev.vitrail.HostReport.otherBackend()} 是
 * {@code !UNKNOWN.equals(backend) && !VULKAN.equals(backend)}，那个名字来自
 * {@code RenderSystem.tryGetDevice().getDeviceInfo().backendName()}。LavaFlow 上报的正是 {@code "Vulkan"}
 * （见 {@code LavaFlowDevice}，为的是让按名字选 Vulkan 路径的模组继续工作），于是 Vitrail 的门答"是"，
 * 它的引擎被打开。
 *
 * <p>引擎打开后要的是**原生后端的类**，不是设备门面：{@code VulkanBackendMixin} 包住
 * {@code VulkanBackend.createDevice(...)} 来申请包要的设备特性（{@code vertexPipelineStoresAndAtomics}、
 * {@code shaderStorageImageExtendedFormats}、{@code independentBlend}、{@code geometryShader}、16/8 位
 * 算术），其余部分从 {@code VulkanRenderPass}、{@code VulkanDevice}、{@code VulkanCommandEncoder}、
 * {@code VulkanGpuTextureView} 取裸句柄。LavaFlow 注册的是自己的 {@code GpuDeviceBackend}，这些对象一个
 * 都不存在，特性也一个都没申请过。Vitrail 自己那些 {@code instanceof} 检测（{@code PackCompute}、
 * {@code ShadowCompare}）确实答"不是"并在本地退让，但引擎**整体**已经被名字打开了，而半开的引擎比不开
 * 更糟：翻译后的着色器带上了 Vitrail 自己的 {@code OfGlobals} uniform 块，填它的那段代码却在永不执行的
 * 原生路径上，第一次绘制就死在空槽上（{@code Missing uniform OfGlobals}，从 {@code pushDescriptors}
 * 抛出，经 Sodium 的间接批处理抵达）。
 *
 * <p>那个失败是崩溃，而且不是 LavaFlow 比原版严：Mojang 自己的 {@code VulkanRenderPass.pushDescriptors}
 * 遇到空 uniform-buffer 槽同样抛 {@code IllegalStateException}；两边都在 26.3 客户端上核对过。
 *
 * <p>所以这条守卫按 Vitrail 其余那些答案已经给出的答案回答它自己的问题——"这不是它能绘制的后端"——
 * Vitrail 便走它自己为"另一个后端"准备的那条路：包既不读也不画，游戏保持自己的画面，并在日志与聊天里
 * 自己说明。这里不复制也不扩展 Vitrail 的任何源码。
 *
 * <p>核对于 Vitrail Shaders v0.12.0-beta 的 26.2 jar：{@code dev.vitrail.HostReport} 是类，
 * {@code otherBackend} 是无参静态方法（与 26.3 那个 jar 一致）。两者都是第三方模组的内部结构，可能随时
 * 变动。
 */
public final class VitrailCompat {
    private static final System.Logger LOGGER = System.getLogger("LavaFlow/Vitrail");

    /** Vitrail 每个 tick、好几个界面都会问；这句话值得听一次，不值得听一万次。 */
    private static final AtomicBoolean REPORTED = new AtomicBoolean();

    private VitrailCompat() {}

    /**
     * 本次会话是否跑在 LavaFlow 自己的后端上；是的话，Vitrail 的引擎就不能被允许绘制。
     *
     * <p>还没有设备、或后端读不出来，答 {@code false}。这正是 Vitrail 给自己定的规矩：一个没人能叫出
     * 名字的后端，不是拒绝一个包的理由——在那儿拒绝，会从一个很可能就是 Mojang 的设备上把画面拿走。
     */
    public static boolean runsOnLavaFlow() {
        return runsOnLavaFlow(LavaFlowDevices.currentDeviceOrNull());
    }

    /** 收设备的那个形式，测试驱动的就是它。 */
    static boolean runsOnLavaFlow(GpuDevice device) {
        if (!LavaFlowDevices.isLavaFlow(device)) {
            return false;
        }
        if (REPORTED.compareAndSet(false, true)) {
            LOGGER.log(System.Logger.Level.INFO, guardFiredMessage(device));
        }
        return true;
    }

    /**
     * 守卫生效时记的那一行。
     *
     * <p>拼好之后作为**单个参数**交给 logger：AsyncParticles 那条守卫曾经把设备名丢给
     * {@code MessageFormat} 的引号，结果打出一句读着像回事、其实什么也没说的话。
     */
    static String guardFiredMessage(GpuDevice device) {
        return "Vitrail Shaders is standing aside: its programs are translated for Minecraft's own Vulkan"
                + " backend and every device feature they ask for is enabled on a device LavaFlow never"
                + " creates, so a pack it was asked for is neither read nor drawn and the game keeps its"
                + " own image. This is Vitrail's own behaviour on a backend it was not written for; the"
                + " device is " + device;
    }
}
