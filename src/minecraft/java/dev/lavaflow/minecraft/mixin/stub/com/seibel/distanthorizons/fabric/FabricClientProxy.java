package com.seibel.distanthorizons.fabric;

/**
 * Signature-only stub of Distant Horizons' Fabric client proxy.
 *
 * <p>The real class is supplied by the DH Fabric jar at runtime; this stub exists solely so the
 * mixin config compiles without the mod artifact on the compile classpath. Keep it in sync with
 * the real jar: mismatches surface as {@link org.spongepowered.asm.mixin.MixinApplyError} at
 * runtime, not as a compile-time failure.</p>
 */
public class FabricClientProxy {
    public void afterLevelRenderEvent() {
        // stub
    }
}
