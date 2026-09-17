package dev.lavaflow.minecraft.sodium;

import dev.lavaflow.minecraft.MixinPluginUtil;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Disables LavaFlow's Sodium compatibility mixins when Sodium is not installed. */
public final class LavaFlowSodiumMixinPlugin implements IMixinConfigPlugin {
    private static final String SODIUM_MARKER =
            "net.caffeinemc.mods.sodium.client.gpu.device.backend.DrawBackend";

    private boolean sodiumPresent;

    @Override
    public void onLoad(String mixinPackage) {
        sodiumPresent = MixinPluginUtil.isClassPresent(SODIUM_MARKER);
        System.Logger logger = System.getLogger(LavaFlowSodiumMixinPlugin.class.getName());
        logger.log(sodiumPresent ? System.Logger.Level.INFO : System.Logger.Level.DEBUG,
                sodiumPresent
                        ? "Sodium detected; applying LavaFlow Sodium compatibility mixins"
                        : "Sodium not detected; skipping LavaFlow Sodium compatibility mixins");
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return sodiumPresent;
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return Collections.emptyList(); }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
