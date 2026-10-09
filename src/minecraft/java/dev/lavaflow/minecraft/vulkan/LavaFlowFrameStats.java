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
    // A short rolling window for the F3 line, kept whether or not the periodic report is enabled: the overlay
    // asks for the GPU time every frame it is shown, and a readout that only exists when a JVM flag was passed
    // is exactly the kind of number that is missing when it is wanted.
    private static final int DEBUG_WINDOW = 120;
    private static final double[] debugGpuMillis = new double[DEBUG_WINDOW];
    private static int debugGpuSamples;
    private static int debugGpuHead;
    private static final long[] debugFrameNanos = new long[DEBUG_WINDOW];
    private static int debugFrameSamples;
    private static int debugFrameHead;
    private static long debugPreviousFrameNanos;
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
    private static long indirectNative;
    private static long indirectExpanded;
    private static long indirectSplit;
    private static long indirectSkipped;
    private static long indirectNativeAtReport;
    private static long indirectExpandedAtReport;
    private static long indirectSplitAtReport;
    private static long indirectSkippedAtReport;
    private static long retiredBuffersAtReport;
    private static long retiredViewsAtReport;
    private static long retiredSamplersAtReport;
    private static final Map<String, Integer> retiredViewLabels = new HashMap<>();
    private static long pipelineBinds;
    private static long pipelineBindsAtReport;
    private static long pipelineRebinds;
    private static long pipelineRebindsAtReport;
    private static long pipelineBindsRecorded;
    private static long pipelineBindsRecordedAtReport;
    private static long uniformChanges;
    private static long uniformChangesAtReport;
    private static long vertexBufferBinds;
    private static long vertexBufferBindsAtReport;
    private static long vertexBufferBindCalls;
    private static long vertexBufferBindCallsAtReport;
    private static long vertexBufferBindRepeats;
    private static long vertexBufferBindRepeatsAtReport;
    private static long passSplits;
    private static long passSplitsAtReport;
    // GPU execution time, sampled by the encoder's timestamp queries. Reported as an average over the same
    // window as everything else, and only when the device can actually time work — gpuFramesAtReport is what
    // tells a window with no samples from a frame whose GPU time was zero.
    private static long gpuFrames;
    private static double gpuMillis;
    private static long gpuFramesAtReport;
    private static double gpuMillisAtReport;
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

    /** Counts one {@code vkCmdBindPipeline} actually recorded into a command buffer. */
    public static void pipelineBindRecorded() {
        if (!ENABLED) return;
        pipelineBindsRecorded++;
    }

    /**
     * Counts one {@code setUniform} call that really changed a slot's value, i.e. one that marked the
     * descriptors dirty.
     *
     * <p>Together with {@link #pipelineBindRecorded()} this says where the descriptor pushes come from.
     * A device run had pushes_per_frame equal to binds_per_frame line for line: the pipeline repeats
     * were being elided, so either the uniform values genuinely change per draw (a per-draw buffer
     * offset, which has to be re-pushed) or something else is doing the dirtying. Which one it is is
     * not guessable from the outside, so the report carries both.
     */
    public static void uniformChanged() {
        if (!ENABLED) return;
        uniformChanges++;
    }

    /**
     * Counts one {@code vkCmdBindVertexBuffers} actually recorded into a command buffer.
     *
     * <p>The sibling of {@link #pipelineBindRecorded()}, and the answer to the same kind of question: the
     * frontend asks for vertex buffers through {@code setVertexBuffer}, and a request that the backend
     * correctly declines to record (the frontend's null slice means "leave this binding alone") must not
     * show up here. A device log where this tracks the request rate one to one would mean the decline is
     * gone — which is how the Mali crash below got in.
     */
    /**
     * One request from the frontend to put a buffer in a vertex slot, whether or not it is recorded.
     *
     * <p>Counted separately from {@link #vertexBufferBindRecorded()} for the same reason pipeline binds are:
     * the requests describe what the frontend does, and a request for the binding that is already in place is
     * a repeat worth seeing. Only the recorded count is allowed to fall.
     */
    public static void vertexBufferBindCalled(boolean repeated) {
        if (!ENABLED) return;
        vertexBufferBindCalls++;
        if (repeated) vertexBufferBindRepeats++;
    }

    public static void vertexBufferBindRecorded() {
        if (!ENABLED) return;
        vertexBufferBinds++;
    }

    /**
     * Counts one batch handed to the driver unchanged: either the device batches indirect draws itself, or the
     * batch was a single command to begin with. Measured in draw commands, like the three below, so the four
     * add up to what the frontend asked for.
     */
    public static void indirectNative(int commands) {
        if (!ENABLED) return;
        indirectNative += commands;
    }

    /**
     * Counts commands the backend re-recorded as plain draws after reading their parameters on the CPU.
     *
     * <p>Covers both shapes of a CPU-readable batch: an indirect parameter buffer that is currently mapped, and
     * a plain multi-draw array. This is the counter that says whether the emulation is doing anything at all —
     * with no reading of it, "LavaFlow expands indirect draws on the CPU" was a claim about code that might
     * never have run.
     */
    public static void indirectExpanded(int commands) {
        if (!ENABLED) return;
        indirectExpanded += commands;
    }

    /**
     * Counts commands issued as one single-command indirect call each, the fallback when the parameters are
     * not in host memory and the device cannot batch: the driver re-validates the parameter buffer per call,
     * which is the cost the CPU expansion avoids. A high number here is the reason to go looking for a mapping.
     */
    public static void indirectSplit(int commands) {
        if (!ENABLED) return;
        indirectSplit += commands;
    }

    /**
     * Counts commands dropped because they draw nothing ({@code count <= 0} or {@code instanceCount <= 0}).
     * Legal no-ops either way, but a tiler still pays to walk them, so this is free win or free information.
     */
    public static void indirectSkipped(int commands) {
        if (!ENABLED) return;
        indirectSkipped += commands;
    }

    /**
     * Counts one render-pass split forced by a sampled-texture transition: the pass is ended, the textures
     * are transitioned, and the pass is begun again. On a tiled GPU an end/begin pair is a tile flush and
     * reload on top of the barrier, which makes it the most expensive per-frame command the backend can
     * record for a handful of draws — and it was invisible until it had a counter.
     */
    public static void passSplit() {
        if (!ENABLED) return;
        passSplits++;
    }

    /**
     * Records one finished frame's GPU execution time in milliseconds, as measured by the encoder's
     * timestamp queries.
     *
     * <p>This is the number that says whether any of the per-frame counters around it are worth chasing. The
     * others count commands the CPU records, and a frame can be limited by either side — so without it,
     * "this change removed N commands per frame" is a claim about the CPU half of a frame whose length the
     * GPU sets, and a report can be all green while nothing gets faster.
     */
    public static void gpuFrameCompleted(double millis) {
        synchronized (LavaFlowFrameStats.class) {
            debugGpuMillis[debugGpuHead] = millis;
            debugGpuHead = (debugGpuHead + 1) % DEBUG_WINDOW;
            if (debugGpuSamples < DEBUG_WINDOW) debugGpuSamples++;
        }
        if (!ENABLED) return;
        gpuFrames++;
        gpuMillis += millis;
    }

    /**
     * The line LavaFlow contributes to the F3 overlay: the GPU time it measured for a finished frame, and what
     * share of the frame that is. Null until a frame has been timed, so the entry adds nothing before then.
     *
     * <p>Minecraft's own GPU readout answers the same question from a timer query the frontend polls itself,
     * and on Mali that number has been arriving negative — which makes it useless for the one decision it
     * exists to inform. This is the same question answered from the timestamps LavaFlow writes around its own
     * submissions and reads back once the slot's fence has signalled. The share is the number to read: near
     * 100% means the GPU is what is holding the frame up, and no amount of command recording saved on the CPU
     * side will show up in the frame rate.
     */
    public static String debugLine() {
        double gpuMillis;
        double frameMillis;
        synchronized (LavaFlowFrameStats.class) {
            if (debugGpuSamples == 0) return null;
            double gpuSum = 0;
            for (int i = 0; i < debugGpuSamples; i++) gpuSum += debugGpuMillis[i];
            double frameSum = 0;
            for (int i = 0; i < debugFrameSamples; i++) frameSum += debugFrameNanos[i];
            gpuMillis = gpuSum / debugGpuSamples;
            frameMillis = debugFrameSamples == 0 ? 0 : frameSum / debugFrameSamples / 1_000_000.0;
        }
        if (frameMillis <= 0) return String.format("GPU (LavaFlow): %.2f ms", gpuMillis);
        return String.format("GPU (LavaFlow): %.2f ms, %.0f%% of a %.2f ms frame",
                gpuMillis, 100.0 * gpuMillis / frameMillis, frameMillis);
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

    /**
     * Retired views so far. The report diffs this same field; a test reads it to assert that a path
     * expected to reuse its views retires none. Only meaningful while {@link #enabled()}.
     */
    static long retiredViewTotal() { return retiredViews; }
    static long pipelineBindsTotal() { return pipelineBinds; }
    static long pipelineRebindsTotal() { return pipelineRebinds; }
    static long pipelineBindsRecordedTotal() { return pipelineBindsRecorded; }
    static long uniformChangesTotal() { return uniformChanges; }
    static long vertexBufferBindsTotal() { return vertexBufferBinds; }

    static long vertexBufferBindCallsTotal() { return vertexBufferBindCalls; }

    static long vertexBufferBindRepeatsTotal() { return vertexBufferBindRepeats; }
    static long passSplitsTotal() { return passSplits; }
    static long gpuFramesTotal() { return gpuFrames; }
    static double gpuMillisTotal() { return gpuMillis; }
    static long descriptorPushesTotal() { return descriptorPushes; }

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
        long now = System.nanoTime();
        synchronized (LavaFlowFrameStats.class) {
            if (debugPreviousFrameNanos != 0) {
                debugFrameNanos[debugFrameHead] = now - debugPreviousFrameNanos;
                debugFrameHead = (debugFrameHead + 1) % DEBUG_WINDOW;
                if (debugFrameSamples < DEBUG_WINDOW) debugFrameSamples++;
            }
            debugPreviousFrameNanos = now;
        }
        if (ENABLED) record();
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
        double bindRecordsPerFrame = framesSince == 0 ? 0
                : (pipelineBindsRecorded - pipelineBindsRecordedAtReport) / (double) framesSince;
        double uniformChangesPerFrame = framesSince == 0 ? 0
                : (uniformChanges - uniformChangesAtReport) / (double) framesSince;
        double vertexBindsPerFrame = framesSince == 0 ? 0
                : (vertexBufferBinds - vertexBufferBindsAtReport) / (double) framesSince;
        double vertexBindCallsPerFrame = framesSince == 0 ? 0
                : (vertexBufferBindCalls - vertexBufferBindCallsAtReport) / (double) framesSince;
        double vertexRebindRatio = vertexBufferBindCalls == vertexBufferBindCallsAtReport ? 0
                : (vertexBufferBindRepeats - vertexBufferBindRepeatsAtReport)
                        / (double) (vertexBufferBindCalls - vertexBufferBindCallsAtReport);
        double passSplitsPerFrame = framesSince == 0 ? 0
                : (passSplits - passSplitsAtReport) / (double) framesSince;
        double indirectNativePerFrame = framesSince == 0 ? 0
                : (indirectNative - indirectNativeAtReport) / (double) framesSince;
        double indirectExpandedPerFrame = framesSince == 0 ? 0
                : (indirectExpanded - indirectExpandedAtReport) / (double) framesSince;
        double indirectSplitPerFrame = framesSince == 0 ? 0
                : (indirectSplit - indirectSplitAtReport) / (double) framesSince;
        double indirectSkippedPerFrame = framesSince == 0 ? 0
                : (indirectSkipped - indirectSkippedAtReport) / (double) framesSince;
        long gpuSamplesSince = gpuFrames - gpuFramesAtReport;
        double gpuMillisPerFrame = gpuSamplesSince == 0 ? 0
                : (gpuMillis - gpuMillisAtReport) / gpuSamplesSince;
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
        indirectNativeAtReport = indirectNative;
        indirectExpandedAtReport = indirectExpanded;
        indirectSplitAtReport = indirectSplit;
        indirectSkippedAtReport = indirectSkipped;
        pipelineBindsAtReport = pipelineBinds;
        pipelineRebindsAtReport = pipelineRebinds;
        pipelineBindsRecordedAtReport = pipelineBindsRecorded;
        uniformChangesAtReport = uniformChanges;
        vertexBufferBindsAtReport = vertexBufferBinds;
        vertexBufferBindCallsAtReport = vertexBufferBindCalls;
        vertexBufferBindRepeatsAtReport = vertexBufferBindRepeats;
        passSplitsAtReport = passSplits;
        gpuFramesAtReport = gpuFrames;
        gpuMillisAtReport = gpuMillis;
        descriptorPushesAtReport = descriptorPushes;
        LOGGER.log(System.Logger.Level.INFO,
                "frames={0} fps_median={1} frame_ms median={2} mean={3} p99={4} min={5} gpu_ms={6}"
                        + " sets_per_frame={7}"
                        + " hit_rate={8} invalidations_per_frame={9} barriers_per_frame={10} submits_per_frame={11}"
                        + " binds_per_frame={12} rebind_ratio={13} bind_records_per_frame={14} vertex_binds_per_frame={15}"
                        + " pushes_per_frame={16} uniform_changes_per_frame={17}"
                        + " retired_buffer={18} retired_view={19} retired_sampler={20} retired_texture={21}"
                        + " partial_clears={22} pass_splits_per_frame={23} top_retired_view={24}"
                        + " vertex_bind_calls_per_frame={25} vertex_rebind_ratio={26}"
                        + " indirect_native_per_frame={27} indirect_expanded_per_frame={28}"
                        + " indirect_split_per_frame={29} indirect_skipped_per_frame={30}",
                Long.toString(totalFrames),
                String.format("%.1f", 1000.0 / medianMillis),
                String.format("%.3f", medianMillis),
                String.format("%.3f", meanMillis),
                String.format("%.3f", p99Millis),
                String.format("%.3f", minMillis),
                gpuSamplesSince == 0 ? "-" : String.format("%.2f", gpuMillisPerFrame),
                String.format("%.1f", setsPerFrame),
                String.format("%.3f", hitRate),
                String.format("%.2f", invalidationsPerFrame),
                String.format("%.1f", barriersPerFrame),
                String.format("%.1f", submitsPerFrame),
                String.format("%.1f", bindsPerFrame),
                String.format("%.3f", rebindRatio),
                String.format("%.1f", bindRecordsPerFrame),
                String.format("%.1f", vertexBindsPerFrame),
                String.format("%.1f", pushesPerFrame),
                String.format("%.1f", uniformChangesPerFrame),
                String.format("%.2f", buffersPerFrame),
                String.format("%.2f", viewsPerFrame),
                String.format("%.2f", samplersPerFrame),
                String.format("%.2f", texturesPerFrame),
                String.format("%.1f", clearsPerFrame),
                String.format("%.2f", passSplitsPerFrame),
                topViewLabels,
                String.format("%.1f", vertexBindCallsPerFrame),
                String.format("%.3f", vertexRebindRatio),
                String.format("%.1f", indirectNativePerFrame),
                String.format("%.1f", indirectExpandedPerFrame),
                String.format("%.1f", indirectSplitPerFrame),
                String.format("%.1f", indirectSkippedPerFrame));
    }
}
