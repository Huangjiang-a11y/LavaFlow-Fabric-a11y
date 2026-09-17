package dev.lavaflow.minecraft.vulkan;

import java.util.Arrays;

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
        framesAtReport = totalFrames;
        descriptorSetsAtReport = descriptorSets;
        descriptorHitsAtReport = descriptorHits;
        descriptorInvalidationsAtReport = descriptorInvalidations;
        barriersAtReport = barriers;
        submitsAtReport = submits;
        LOGGER.log(System.Logger.Level.INFO,
                "frames={0} fps_median={1} frame_ms median={2} mean={3} p99={4} min={5} sets_per_frame={6}"
                        + " hit_rate={7} invalidations_per_frame={8} barriers_per_frame={9} submits_per_frame={10}",
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
                String.format("%.1f", submitsPerFrame));
    }
}
