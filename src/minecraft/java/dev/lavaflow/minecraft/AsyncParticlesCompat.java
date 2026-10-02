package dev.lavaflow.minecraft;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.vulkan.VulkanDevice;

import java.lang.reflect.Constructor;

/**
 * {@code AsyncParticlesVulkanBackendMixin} 的支持代码。
 *
 * <p>刻意不放进 mixin 类里：{@code @Mixin} 类的普通成员会被合并进目标类，而这里的目标是第三方模组的
 * {@code Backends}。只有注入器本身属于那里，其余放这儿——放这儿还顺带能被测试够到。
 *
 * <p>AsyncParticles 是可选依赖、不在编译类路径上，所以运行时要用的那一个 AsyncParticles 类型
 * （{@code VkCommands.Unsupported}）按名字定位。
 *
 * <p>这些名字曾经是猜的，后来核对于 AsyncParticles <b>26.2.2.9+26.2</b>（本分支的 jar，取自 Modrinth）：
 * {@code Backends.getVkCaps(GpuDevice)} 是私有静态方法，它的第一步就是
 * {@code ((VulkanDevice) device.backend).vkDevice()}；{@code VkCommands} 是 public abstract class，
 * {@code Unsupported} 是它的 public 嵌套类、带 public 无参构造。下面的查找与这个形状一一对应。
 *
 * <p>它们仍然是第三方模组的内部结构，随时可能变。真变的时候，这个类里的 ERROR 日志会指出坏在哪，而
 * {@link #unsupportedVkCaps} 返回 {@code null}，不会假装成功。
 *
 * <p>取后端这一步走 {@link LavaFlowDevices}（两条守卫共用一处）。早先的版本直接
 * {@code GpuDevice.class.getDeclaredField("backend")}：26.3 的 {@code GpuDevice} 是无字段的接口，于是
 * 每次都抛，而 catch 里的 {@code return} 让原方法照跑——守卫成了空操作，恰好放过它本来要拦的那个崩溃。
 * 现在读不到会记一条 WARNING，而不是静默站到一边。
 */
public final class AsyncParticlesCompat {
    private static final System.Logger LOGGER = System.getLogger("LavaFlow/AsyncParticles");

    private static final String VK_COMMANDS =
            "fun.qu_an.minecraft.asyncparticles.client.core.backend.VkCommands";

    private AsyncParticlesCompat() {}

    /**
     * 返回 {@code device} 是不是 Mojang 自己的 Vulkan 设备；是的话，AsyncParticles 原本的判定就是对的，
     * 不要动它。
     *
     * <p>不知道的后端答 {@code false}：AsyncParticles 拿这个结果只会做一件事——把它强转成
     * {@link VulkanDevice}——而那是 LavaFlow 注册的任何东西都不可能通过的。
     */
    public static boolean isMojangVulkanDevice(GpuDevice device) {
        return LavaFlowDevices.backendOf(device) instanceof VulkanDevice;
    }

    /**
     * 记录守卫即将生效。
     *
     * <p>没有这一行，守卫**正常工作时反而是隐形的**：这个类里其它消息报的都是失败，于是"守卫生效了"和
     * "AsyncParticles 根本没问过"在设备日志里长得一模一样，而这两种情况该查的方向完全不同。
     *
     * <p>它同时把代价写在读日志的人会看到的地方：粒子走 CPU。那是有意的取舍，不是故障，不该由下一个读
     * 代码的人自己从源码里推。
     */
    public static void reportGuardFired(GpuDevice device) {
        LOGGER.log(System.Logger.Level.INFO, guardFiredMessage(device));
    }

    /**
     * {@link #reportGuardFired} 记的那一行。用拼接而不是 {@code {0}} 参数，这不是风格偏好。
     *
     * <p>{@code java.text.MessageFormat} 把撇号当成引用段的开始。一句含 "Mojang's" 的话只要再带一个占位符，
     * 那个引用段就一直不闭合，占位符会被原样打出来：设备名从此不再出现，而整行照常打印、读着像回事。
     * 这条消息最初就是这么上线的。保持无参数，选中的重载不做任何格式化，撇号就只是个字符。
     */
    static String guardFiredMessage(GpuDevice device) {
        return "AsyncParticles asked for Vulkan caps on a backend that is not Mojang's Vulkan device ("
                + (device == null ? "null device" : device.getClass().getSimpleName())
                + "); answering unsupported, so particles stay on the CPU path";
    }

    /**
     * 造一个 {@code VkCommands.Unsupported} 给 AsyncParticles，让它报告"没有 Vulkan 加速"；造不出来返回
     * {@code null}。
     *
     * <p>通过设备的类加载器按名字定位，而不是直接引用：AsyncParticles 可能压根没装。它的嵌套类型与构造
     * 都不属于任何有文档的 API，所以构造是**查**出来的而不是假定的，并且每一次失败都被报告：这里返回
     * {@code null} 意味着 AsyncParticles 将继续执行那句会拖垮游戏的强转，调用方不能悄悄吞掉。
     */
    public static Object unsupportedVkCaps(GpuDevice device) {
        Class<?> vkCommands;
        try {
            vkCommands = Class.forName(VK_COMMANDS, false,
                    device == null ? null : device.getClass().getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            LOGGER.log(System.Logger.Level.ERROR, "AsyncParticles 的 VkCommands 类找不到", e);
            return null;
        }
        Class<?> nested = null;
        try {
            for (Class<?> candidate : vkCommands.getDeclaredClasses()) {
                if ("Unsupported".equals(candidate.getSimpleName())) {
                    nested = candidate;
                    break;
                }
            }
        } catch (LinkageError e) {
            LOGGER.log(System.Logger.Level.ERROR, "无法枚举 VkCommands 的嵌套类型", e);
            return null;
        }
        if (nested == null) {
            LOGGER.log(System.Logger.Level.ERROR, "VkCommands.Unsupported 不在了");
            return null;
        }
        for (Constructor<?> constructor : nested.getDeclaredConstructors()) {
            if (constructor.getParameterCount() != 0) continue;
            try {
                if (!constructor.trySetAccessible()) continue;
                return constructor.newInstance();
            } catch (ReflectiveOperationException e) {
                LOGGER.log(System.Logger.Level.ERROR, "VkCommands.Unsupported 构造失败", e);
                return null;
            }
        }
        LOGGER.log(System.Logger.Level.ERROR, "VkCommands.Unsupported 没有可访问的无参构造");
        return null;
    }
}
