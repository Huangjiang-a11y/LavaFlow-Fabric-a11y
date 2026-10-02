package dev.lavaflow.minecraft.mixin;

import dev.lavaflow.minecraft.VitrailCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 别让 Vitrail Shaders 在 LavaFlow 的 Vulkan 后端上画一半。
 *
 * <p>Vitrail 靠把设备上报的后端名与 {@code "Vulkan"} 比较来决定引擎能不能画：
 * {@code dev.vitrail.HostReport.otherBackend()} 是
 * {@code !UNKNOWN.equals(backend) && !VULKAN.equals(backend)}。LavaFlow 上报的正是 {@code "Vulkan"}，
 * 这个按名字的问句于是答"能"——而它引擎真正需要的每一样东西都是按**类**取的，在 Mojang 自己的
 * {@code VulkanBackend} / {@code VulkanRenderPass} / {@code VulkanDevice} / {@code VulkanCommandEncoder}
 * 上，那些 LavaFlow 从不实例化。于是引擎被打开、却填不上自己声明过的东西：翻译后的着色器带着 Vitrail 的
 * {@code OfGlobals} uniform 块，而没有任何代码为它调过 {@code setUniform}，第一次绘制就在空槽上死掉
 * （{@code Missing uniform OfGlobals}，从 {@code pushDescriptors} 抛出，经 Sodium 的间接批处理抵达）。
 *
 * <p>这条混入替那**一个**问句回答"是的，另一个后端"——前提是设备确实是 LavaFlow 的。这个答案也是 Vitrail
 * 其余部分按类早就得出的结论，它自己那条"另一个后端"的路便会接手：包既不读也不画，游戏保持自己的画面，
 * 并在日志与聊天里说明原因。这里不复制也不扩展 Vitrail 的任何源码；除了注入器之外什么也不声明——普通
 * mixin 成员会被合并进目标类，而这里的目标是第三方模组。
 *
 * <p>用全限定字符串 + {@code remap = false} 定位，因为 Vitrail 是可选依赖；标 {@link Pseudo}，让它缺席时
 * 完全惰性。类名与方法名核对于 Vitrail Shaders v0.12.0-beta 的 **26.2 jar**：{@code otherBackend} 是无参
 * 静态方法，与 26.3 那个 jar 相同。详见 {@link VitrailCompat}。
 */
@Pseudo
@Mixin(targets = "dev.vitrail.HostReport", remap = false)
public abstract class VitrailBackendMixin {

    @Inject(method = "otherBackend", at = @At("HEAD"), cancellable = true)
    private static void lavaflow$standAside(CallbackInfoReturnable<Boolean> cir) {
        if (VitrailCompat.runsOnLavaFlow()) {
            cir.setReturnValue(true);
        }
    }
}
