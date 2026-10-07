package dev.lavaflow.minecraft.mixin;

import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Reaches Minecraft's own debug-entry registration, which is private.
 *
 * <p>Called through rather than writing the entry map directly: it is the same path Minecraft's own entries
 * take, so the map keeps whatever invariants its owner keeps in it.
 */
@Mixin(DebugScreenEntries.class)
public interface DebugScreenEntriesInvoker {
    @Invoker("register")
    static Identifier lavaflow$register(Identifier id, DebugScreenEntry entry) {
        throw new AssertionError("replaced by the mixin");
    }
}
