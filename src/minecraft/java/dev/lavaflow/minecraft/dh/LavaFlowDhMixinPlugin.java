package dev.lavaflow.minecraft.dh;

import dev.lavaflow.minecraft.MixinPluginUtil;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Disables LavaFlow's Distant Horizons compatibility mixins when Distant Horizons is not installed. */
public final class LavaFlowDhMixinPlugin implements IMixinConfigPlugin {
    private static final String DH_MARKER =
            "com.seibel.distanthorizons.neoforge.wrappers.NeoforgeTextureUnwrapper";

    private boolean dhPresent;

    @Override
    public void onLoad(String mixinPackage) {
        dhPresent = MixinPluginUtil.isClassPresent(DH_MARKER);
        System.Logger logger = System.getLogger(LavaFlowDhMixinPlugin.class.getName());
        logger.log(dhPresent ? System.Logger.Level.INFO : System.Logger.Level.DEBUG,
                dhPresent
                        ? "Distant Horizons detected; applying LavaFlow compatibility mixins"
                        : "Distant Horizons not detected; skipping LavaFlow compatibility mixins");
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return dhPresent;
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return Collections.emptyList(); }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
