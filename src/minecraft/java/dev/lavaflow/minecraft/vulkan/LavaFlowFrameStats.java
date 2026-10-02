package dev.lavaflow.minecraft.vulkan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Frame pacing statistics for LavaFlow development, enabled with {@code -Dlavaflow.frameStats=true}.
 *
 * <p>Reports the median and 99th percentile frame interval rather than an average, because backend
 * cost shows up as a heavy tail that an average hides. Disabled by default and free when disabled:
 * the only cost on the presentation path is one field read.
 */
public final class LavaFlowFrameStats {
    private static final boolean ENABLED = Boolean.getBoolean("lavaflow.frameStats");
    private static final System.Logger LOGGER = System.getLogger(LavaFlowFrameStats.class.getName());
    private static final int WINDOW = 600;
    private static final long REPORT_INTERVAL_NANOS = 5_000_000_000L;

    private static final long[] intervals = new long[WINDOW];
    private static int count;
    private static long previousFrameNanos;
    private static long lastReportNanos;
    private static long totalFrames;
    private static long descriptorSets;
    private static long descriptorSetsAtReport;
    private static long descriptorHits;
    private static long descriptorHitsAtReport;
    private static long descriptorInvalidations;
    private static long descriptorInvalidationsAtReport;
    private static long barriers;
    private static long barriersAtReport;
    private static long submits;
    private static long submitsAtReport;
    private static long framesAtReport;
    // Who is churning: the descriptor cache invalidation counter says how often a retirement hit the cache,
    // these say what was retired. They exist because a run showed ~21 retirements per frame at the main
    // menu, which is far too many to guess at.
    private static long retiredBuffers;
    private static long retiredViews;
    private static long retiredSamplers;
    private static long retiredTextures;
    private static long partialClears;
    private static long retiredTexturesAtReport;
    private static long partialClearsAtReport;
    private static long retiredBuffersAtReport;
    private static long retiredViewsAtReport;
    private static long retiredSamplersAtReport;
    private static final Map<String, Integer> retiredViewLabels = new HashMap<>();
    private static long pipelineBinds;
    private static long pipelineBindsAtReport;
    private static long pipelineRebinds;
    private static long pipelineRebindsAtReport;
    private static long descriptorPushes;
    private static long descriptorPushesAtReport;

    /** Counts one descriptor set allocated because no cached set matched. */
    public static void descriptorSetAllocated() {
        if (!ENABLED) return;
        descriptorSets++;
    }

    /** Counts one descriptor set reused from the cache. */
    public static void descriptorSetReused() {
        if (!ENABLED) return;
        descriptorHits++;
    }

    /**
     * Counts one resource retirement: the cached sets and buffer views that referenced a destroyed
     * resource. It is per resource, not per cache wipe — the cache retires only the entries that
     * reference the handle, so a frame that destroys twenty short-lived resources shows twenty here.
     */
    public static void descriptorCacheInvalidated() {
        if (!ENABLED) return;
        descriptorInvalidations++;
    }

    /** Counts one image layout barrier recorded into the command stream. */
    public static void barrierRecorded() {
        if (!ENABLED) return;
        barriers++;
    }

    /** Counts one queue submission. */
    public static void workSubmitted() {
        if (!ENABLED) return;
        submits++;
    }

    /**
     * Counts one pipeline bind, and whether it repeated the pipeline that was already bound.
     *
     * <p>Whether Minecraft re-binds a pipeline per draw decides whether eliding a repeat is worth the
     * trouble: re-binding the same pipeline re-records vkCmdBindPipeline and re-marks the descriptors
     * dirty, but a bind that is not repeated makes eliding it pointless. Reported as a ratio so the answer
     * comes from the game rather than from a guess.
     */
    public static void pipelineBind(boolean repeated) {
        if (!ENABLED) return;
        pipelineBinds++;
        if (repeated) pipelineRebinds++;
    }

    /** Counts one descriptor write push, whether through push descriptors or a cached set. */
    public static void descriptorPushed() {
        if (!ENABLED) return;
        descriptorPushes++;
    }

    /** Counts one retired GPU buffer. */
    public static void bufferRetired() {
        if (!ENABLED) return;
        retiredBuffers++;
    }

    /**
     * Counts one retired texture view under {@code identity}, the name of the texture it viewed.
     *
     * <p>The identity is required and must say something even when the texture has no label — see
     * {@code LavaFlowGpuTexture.identity()}. It used to be dropped when null, which quietly turned the
     * whole tally empty in the one situation it exists for: a device run retired ~21 views per frame
     * while this reported nothing, because every one of those textures had no label.
     */
    public static void viewRetired(String identity) {
        if (!ENABLED) return;
        retiredViews++;
        retiredViewLabels.merge(identity, 1, Integer::sum);
    }

    /** Counts one retired texture, so texture churn can be told apart from view-only churn. */
    public static void textureRetired() {
        if (!ENABLED) return;
        retiredTextures++;
    }

    /**
     * Whether the churn counters are collecting. Also what makes object names worth resolving: the
     * frontend asks the backend this before unwrapping a texture's label supplier (see
     * {@code LavaFlowDevice.isDebuggingEnabled()}), and those names are read only by this report.
     */
    public static boolean enabled() { return ENABLED; }

    /**
     * Counts one {@code clearColorAndDepthTextures} call. Those create two texture views that are
     * destroyed again immediately, so this is the one place LavaFlow itself manufactures view churn
     * and it is worth being able to rule in or out by number rather than by reading the call graph.
     */
    public static void partialClear() {
        if (!ENABLED) return;
        partialClears++;
    }

    /** Counts one retired sampler. */
    public static void samplerRetired() {
        if (!ENABLED) return;
        retiredSamplers++;
    }

    /**
     * The busiest texture labels among the view retirements since the last report, most retired first,
     * with the number of distinct labels when that exceeds the number shown.
     *
     * <p>The distinct count matters because the interesting distributions here are flat: a device run
     * retiring ~3200 views while reloading resources showed the top three as
     * {@code minecraft:missingno=12, minecraft:item/cave_spider_spawn_egg=1,
     * minecraft:block/sniffer_egg_very_cracked_bottom=1} -- 14 of 3200 named, because every texture
     * resource is retired exactly once. "2589 distinct" says one-per-resource at a glance; a top-three
     * of counts cannot.
     */
    private static String topRetiredViewLabels() {
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

    private LavaFlowFrameStats() {}

    /** Records one rendered frame. */
    public static void framePresented() {
        if (!ENABLED) return;
        record();
    }

    private static synchronized void record() {
        long now = System.nanoTime();
        if (previousFrameNanos != 0) {
            if (count < WINDOW) {
                intervals[count++] = now - previousFrameNanos;
            } else {
                intervals[(int) (totalFrames % WINDOW)] = now - previousFrameNanos;
            }
            totalFrames++;
        }
        previousFrameNanos = now;
        if (lastReportNanos == 0) lastReportNanos = now;
        if (now - lastReportNanos >= REPORT_INTERVAL_NANOS && count > 1) {
            report();
            lastReportNanos = now;
        }
    }

    private static void report() {
        long[] sorted = Arrays.copyOf(intervals, count);
        Arrays.sort(sorted);
        double medianMillis = sorted[sorted.length / 2] / 1_000_000.0;
        double p99Millis = sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.99))] / 1_000_000.0;
        double minMillis = sorted[0] / 1_000_000.0;
        long total = 0;
        for (long interval : sorted) total += interval;
        double meanMillis = total / (double) sorted.length / 1_000_000.0;
        // Every value is pre-formatted: the logger applies locale formatting to raw numbers, which
        // would put digit-group separators in the output.
        long framesSince = totalFrames - framesAtReport;
        long setsSince = descriptorSets - descriptorSetsAtReport;
        long hitsSince = descriptorHits - descriptorHitsAtReport;
        long invalidationsSince = descriptorInvalidations - descriptorInvalidationsAtReport;
        double setsPerFrame = framesSince == 0 ? 0 : setsSince / (double) framesSince;
        double hitRate = setsSince + hitsSince == 0 ? 0 : hitsSince / (double) (setsSince + hitsSince);
        double invalidationsPerFrame = framesSince == 0 ? 0 : invalidationsSince / (double) framesSince;
        double barriersPerFrame = framesSince == 0 ? 0 : (barriers - barriersAtReport) / (double) framesSince;
        double submitsPerFrame = framesSince == 0 ? 0 : (submits - submitsAtReport) / (double) framesSince;
        long bindsSince = pipelineBinds - pipelineBindsAtReport;
        long rebindsSince = pipelineRebinds - pipelineRebindsAtReport;
        double bindsPerFrame = framesSince == 0 ? 0 : bindsSince / (double) framesSince;
        double rebindRatio = bindsSince == 0 ? 0 : rebindsSince / (double) bindsSince;
        double pushesPerFrame = framesSince == 0 ? 0 : (descriptorPushes - descriptorPushesAtReport) / (double) framesSince;
        double buffersPerFrame = framesSince == 0 ? 0 : (retiredBuffers - retiredBuffersAtReport) / (double) framesSince;
        double viewsPerFrame = framesSince == 0 ? 0 : (retiredViews - retiredViewsAtReport) / (double) framesSince;
        double samplersPerFrame = framesSince == 0 ? 0 : (retiredSamplers - retiredSamplersAtReport) / (double) framesSince;
        double texturesPerFrame = framesSince == 0 ? 0 : (retiredTextures - retiredTexturesAtReport) / (double) framesSince;
        double clearsPerFrame = framesSince == 0 ? 0 : (partialClears - partialClearsAtReport) / (double) framesSince;
        String topViewLabels = topRetiredViewLabels();
        framesAtReport = totalFrames;
        descriptorSetsAtReport = descriptorSets;
        descriptorHitsAtReport = descriptorHits;
        descriptorInvalidationsAtReport = descriptorInvalidations;
        barriersAtReport = barriers;
        submitsAtReport = submits;
        retiredBuffersAtReport = retiredBuffers;
        retiredViewsAtReport = retiredViews;
        retiredSamplersAtReport = retiredSamplers;
        retiredTexturesAtReport = retiredTextures;
        partialClearsAtReport = partialClears;
        pipelineBindsAtReport = pipelineBinds;
        pipelineRebindsAtReport = pipelineRebinds;
        descriptorPushesAtReport = descriptorPushes;
        LOGGER.log(System.Logger.Level.INFO,
                "frames={0} fps_median={1} frame_ms median={2} mean={3} p99={4} min={5} sets_per_frame={6}"
                        + " hit_rate={7} invalidations_per_frame={8} barriers_per_frame={9} submits_per_frame={10}"
                        + " binds_per_frame={11} rebind_ratio={12} pushes_per_frame={13}"
                        + " retired_buffer={14} retired_view={15} retired_sampler={16} retired_texture={17}"
                        + " partial_clears={18} top_retired_view={19}",
                Long.toString(totalFrames),
                String.format("%.1f", 1000.0 / medianMillis),
                String.format("%.3f", medianMillis),
                String.format("%.3f", meanMillis),
                String.format("%.3f", p99Millis),
                String.format("%.3f", minMillis),
                String.format("%.1f", setsPerFrame),
                String.format("%.3f", hitRate),
                String.format("%.2f", invalidationsPerFrame),
                String.format("%.1f", barriersPerFrame),
                String.format("%.1f", submitsPerFrame),
                String.format("%.1f", bindsPerFrame),
                String.format("%.3f", rebindRatio),
                String.format("%.1f", pushesPerFrame),
                String.format("%.2f", buffersPerFrame),
                String.format("%.2f", viewsPerFrame),
                String.format("%.2f", samplersPerFrame),
                String.format("%.2f", texturesPerFrame),
                String.format("%.1f", clearsPerFrame),
                topViewLabels);
    }
}
