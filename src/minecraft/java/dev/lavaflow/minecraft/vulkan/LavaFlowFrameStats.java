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

    /** Counts one cache-wide invalidation caused by a resource being destroyed. */
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

    /** Counts one retired texture view, and remembers the label of the texture it viewed. */
    public static void viewRetired(String label) {
        if (!ENABLED) return;
        retiredViews++;
        if (label != null) retiredViewLabels.merge(label, 1, Integer::sum);
    }

    /** Counts one retired sampler. */
    public static void samplerRetired() {
        if (!ENABLED) return;
        retiredSamplers++;
    }

    /** The busiest texture labels among the view retirements since the last report, most retired first. */
    private static String topRetiredViewLabels() {
        if (retiredViewLabels.isEmpty()) return "-";
        List<Map.Entry<String, Integer>> top = new ArrayList<>(retiredViewLabels.entrySet());
        top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(3, top.size()); i++) {
            if (i > 0) out.append(", ");
            out.append(top.get(i).getKey()).append('=').append(top.get(i).getValue());
        }
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
        pipelineBindsAtReport = pipelineBinds;
        pipelineRebindsAtReport = pipelineRebinds;
        descriptorPushesAtReport = descriptorPushes;
        LOGGER.log(System.Logger.Level.INFO,
                "frames={0} fps_median={1} frame_ms median={2} mean={3} p99={4} min={5} sets_per_frame={6}"
                        + " hit_rate={7} invalidations_per_frame={8} barriers_per_frame={9} submits_per_frame={10}"
                        + " binds_per_frame={11} rebind_ratio={12} pushes_per_frame={13}"
                        + " retired_buffer={14} retired_view={15} retired_sampler={16} top_retired_view={17}",
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
                topViewLabels);
    }
}
