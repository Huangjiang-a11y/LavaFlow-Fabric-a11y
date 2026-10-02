package dev.lavaflow.minecraft;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.lavaflow.minecraft.vulkan.LavaFlowDevice;

import java.lang.reflect.Field;

/**
 * 回答"当前 Blaze3D 设备是不是 LavaFlow 的"，给需要看设备背后后端的模组用。
 *
 * <p><b>本分支（26.2）与 26.3 的取法必须不同，不要互相搬运。</b>26.2 的 {@code GpuDevice} 是**类**，
 * {@code backend} 字段就声明在它自己身上（{@code private final GpuDeviceBackend backend}），所以反射
 * 读得到；26.3 把它改成了接口、字段落到具体类 {@code FrontendGpuDevice} 上，同样的反射必抛
 * {@code NoSuchFieldException}，那边因此用一个 accessor mixin。这儿不该照搬那个做法：本分支的
 * {@code GpuDeviceBackendAccessor} 挂在 Sodium 的 mixin 配置里（插件门控），拿它回答一个与 Sodium 无关
 * 的问题会把两个模组绑在一起。
 *
 * <p>两条守卫（AsyncParticles、Vitrail）都要这个答案，所以只放一处。更要紧的是读不到时的行为必须一致：
 * 记一条 WARNING。26.3 上的守卫正是因为把反射失败**静默**当成"不是 LavaFlow"，成了空操作，恰好放过它
 * 本来要拦的那个崩溃——静默的失败穿着成功的外衣。
 */
public final class LavaFlowDevices {
    private static final System.Logger LOGGER = System.getLogger(LavaFlowDevices.class.getName());

    /** 一次进程只吵一次：Vitrail 每个 tick 都会问，而字段名不会中途变回来。 */
    private static boolean warned;

    private LavaFlowDevices() {}

    /**
     * 返回 {@code device} 背后的后端，读不到时返回 {@code null}。
     *
     * <p>{@code null} 意味着读不到（字段改名或反射被挡），那是 LavaFlow 的缺陷而不是正常状态，因此记一条
     * WARNING。调用方必须把 {@code null} 当成"不知道"，不能当成"不是 LavaFlow"——两者该走的兜底不一样。
     */
    public static GpuDeviceBackend backendOf(GpuDevice device) {
        if (device == null) {
            return null;
        }
        try {
            Field field = GpuDevice.class.getDeclaredField("backend");
            field.setAccessible(true);
            return (GpuDeviceBackend) field.get(device);
        } catch (ReflectiveOperationException | ClassCastException e) {
            if (!warned) {
                warned = true;
                LOGGER.log(System.Logger.Level.WARNING,
                        "GpuDevice.backend 读不到（{0}）；后端判定将一律回答\"不知道\"，"
                                + "在那之前依赖它的守卫都不会生效", e.toString());
            }
            return null;
        }
    }

    /**
     * 返回 {@code device} 是否由 LavaFlow 自己的 Vulkan 后端驱动。
     *
     * <p>Mojang 真正的 Vulkan 设备与读不到的情况都返回 false；要区分这两者就用 {@link #backendOf}。
     */
    public static boolean isLavaFlow(GpuDevice device) {
        return backendOf(device) instanceof LavaFlowDevice;
    }

    /**
     * Minecraft 正在用来渲染的设备，还没有设备时返回 {@code null}。
     *
     * <p>{@code getDevice()} 在设备初始化之前抛 {@code IllegalStateException}，所以启动期（游戏还没挑
     * 后端时）问不出话来。Vitrail 的守卫正是在那么早的时候问的，而它的答案决定一个包会不会被读。
     */
    public static GpuDevice currentDeviceOrNull() {
        return RenderSystem.tryGetDevice();
    }
}
