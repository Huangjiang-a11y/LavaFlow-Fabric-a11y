package dev.lavaflow.minecraft.vulkan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Guards the build identity {@link LavaFlowVersion} reports, and the parsing that turns it into two
 * strings.
 *
 * <p>Two separate things are protected. The parse rules matter because a truncated or malformed resource
 * has to degrade rather than throw: the identity is diagnostics, and a launch must not die for want of it.
 * The resource itself matters because it is what makes a log attributable. If the build task stops writing
 * it, this class falls back to {@code dev} and announces that only in a log nobody can place.
 */
class LavaFlowVersionTest {

    @Test
    void parsesVersionThenCommit() {
        LavaFlowVersion.Identity identity = LavaFlowVersion.parse("0.1.0-alpha\n2e3affa\n");

        assertEquals("0.1.0-alpha", identity.version());
        assertEquals("2e3affa", identity.commit());
    }

    @Test
    void keepsADirtyMarkerAttachedToTheCommit() {
        assertEquals("2e3affa-dirty", LavaFlowVersion.parse("0.1.0-alpha\n2e3affa-dirty").commit());
    }

    @Test
    void missingCommitDoesNotEmptyTheVersion() {
        LavaFlowVersion.Identity identity = LavaFlowVersion.parse("0.1.0-alpha");

        assertEquals("0.1.0-alpha", identity.version());
        assertEquals("unknown", identity.commit());
    }

    @Test
    void blankLinesCannotShiftTheCommitIntoTheVersionSlot() {
        LavaFlowVersion.Identity identity = LavaFlowVersion.parse("\n\n0.1.0-alpha\n\nabc123\n\n");

        assertEquals("0.1.0-alpha", identity.version());
        assertEquals("abc123", identity.commit());
    }

    @Test
    void emptyContentDegradesToTheDocumentedPlaceholders() {
        assertEquals(new LavaFlowVersion.Identity("dev", "unknown"), LavaFlowVersion.parse(""));
    }

    @Test
    void theJarCarriesABuildIdentity() {
        // Not a tautology: a resource that was never written leaves version() at "dev", which is exactly
        // the state that makes every log from this jar impossible to place.
        assertNotEquals("dev", LavaFlowVersion.version(),
                "lavaflow-version.txt is not on the classpath; generateLavaFlowVersion did not run");
    }
}
