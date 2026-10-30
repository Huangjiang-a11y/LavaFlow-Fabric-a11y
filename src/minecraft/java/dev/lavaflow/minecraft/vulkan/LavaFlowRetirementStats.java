package dev.lavaflow.minecraft.vulkan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Retirement counters extracted from {@link LavaFlowFrameStats} to keep that class readable. */
final class LavaFlowRetirementStats {
    static long retiredBuffers;
    static long retiredViews;
    static long retiredSamplers;
    static long retiredTextures;
    static long retiredBuffersAtReport;
    static long retiredViewsAtReport;
    static long retiredSamplersAtReport;
    static long retiredTexturesAtReport;
    static final Map<String, Integer> retiredViewLabels = new HashMap<>();

    static void bufferRetired() { if (LavaFlowFrameStats.enabled()) retiredBuffers++; }
    static void viewRetired(String identity) {
        if (!LavaFlowFrameStats.enabled()) return;
        retiredViews++;
        retiredViewLabels.merge(identity, 1, Integer::sum);
    }
    static void textureRetired() { if (LavaFlowFrameStats.enabled()) retiredTextures++; }
    static void samplerRetired() { if (LavaFlowFrameStats.enabled()) retiredSamplers++; }

    static long retiredViewTotal() { return retiredViews; }

    static String topRetiredViewLabels() {
        int distinct = retiredViewLabels.size();
        if (retiredViewLabels.isEmpty()) return "-";
        List<Map.Entry<String, Integer>> top = new ArrayList<>(retiredViewLabels.entrySet());
        top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(3, top.size()); i++) {
            if (i > 0) out.append(", ");
            out.append(top.get(i).getKey()).append('=').append(top.get(i).getValue());
        }
        int shown = Math.min(3, top.size());
        if (distinct > shown) out.append(" (distinct=").append(distinct).append(')');
        retiredViewLabels.clear();
        return out.toString();
    }

    private LavaFlowRetirementStats() {}
}
