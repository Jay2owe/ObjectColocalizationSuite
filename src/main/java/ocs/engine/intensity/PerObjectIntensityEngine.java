package ocs.engine.intensity;

import ij.ImagePlus;
import ocs.engine.ColocEngine;
import ocs.engine.ColumnSpec;
import ocs.engine.DirectionKey;
import ocs.engine.EngineCancelledException;
import ocs.engine.EngineFamily;
import ocs.engine.EngineInputs;
import ocs.engine.EngineProgress;
import ocs.engine.EngineResult;
import ocs.engine.InputRequirement;
import ocs.engine.ObjectScore;
import ocs.engine.ScaleKind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pearson and Manders computed inside each object's own mask.
 *
 * <p>The complement of {@link WholeImageIntensityEngine}. That one answers "how
 * correlated are these two channels across the field", which is one number and no
 * per-object row. This one answers "how correlated are they <i>inside this
 * object</i>", which is a row per object and therefore the only intensity measure
 * the null-model, agreement and clustering layers can read — all three work
 * through {@link ObjectScore#value()}.
 *
 * <h2>Inside the mask, not inside the box</h2>
 *
 * <p>The mask is the object's actual voxels. A bounding box would be simpler and
 * would be wrong in a specific, invisible way: for a branched or curved object the
 * box is mostly background, so the reported correlation is dominated by the
 * background's own correlation and drifts toward the whole-image number. Nothing
 * in the output would say so. {@link ObjectVoxelGatherer} gathers mask voxels and
 * this engine never sees a box.
 *
 * <h2>What is not computed, and why</h2>
 *
 * <p><b>No Costes p value.</b> The Costes null shuffles blocks of voxels to
 * preserve local correlation while destroying the pairing between channels, which
 * requires the voxels to still be arranged in space. Per-object samples are packed
 * into a flat list, so a block shuffle over them would shuffle runs of a flattened
 * index rather than regions of the specimen — a p value from a null that is not
 * the Costes null, under the Costes name. Objects are also small: a 200-voxel
 * object holds a few dozen blocks, and a p from that is bounded by the
 * discretization rather than by the data. The randomization is therefore switched
 * off, and no {@code p} column is declared at all rather than a column of NaN
 * that a reader would try to interpret.
 *
 * <p><b>No partner object.</b> Every row's {@code partnerLabel} is
 * {@link ObjectScore#NO_PARTNER}. The measure compares this object against the
 * target channel's <i>intensity field</i>, not against a target object; naming an
 * arbitrary overlapping object as "the partner" would put a relationship in the
 * table that the number does not describe.
 *
 * <h2>Objects too small to correlate</h2>
 *
 * <p>Below {@link #MIN_VOXELS_FOR_CORRELATION} voxels the row is reported with
 * every metric NaN and {@code Correlation Defined?} 0, rather than dropped. Two
 * voxels always correlate perfectly — <i>r</i> is exactly ±1 for any two points,
 * carrying no information whatsoever — and a table full of ±1 from single-voxel
 * debris is the most confident-looking wrong answer this engine could produce.
 * Dropping the rows instead would misalign this engine's objects with every other
 * engine's in the agreement matrix, which compares object by object.
 */
public final class PerObjectIntensityEngine
        implements ColocEngine, ocs.engine.ThresholdBearing {

    /**
     * Fewest voxels an object may have before its correlation is reported.
     *
     * <p>Three, because two points lie on a line by construction: <i>r</i> is ±1
     * with certainty and describes nothing. Three is the smallest count at which
     * the value can vary, and is also what the Costes threshold search already
     * requires.
     */
    public static final int MIN_VOXELS_FOR_CORRELATION = 3;

    /**
     * Default <i>r</i> at or above which an object is called coincident.
     *
     * <p>A judgement call, exposed as a constructor argument rather than buried
     * here, and the arbitrary choice the threshold sweep exists to expose: an
     * object at <i>r</i> = 0.49 and one at 0.51 are indistinguishable, and this
     * cutoff calls one colocalized and the other not.
     */
    public static final double DEFAULT_COINCIDENT_R = 0.5;

    private static final ColumnSpec PEARSON = ColumnSpec.primary(
            "Pearson r", "",
            "Pearson correlation between the two channels over this object's voxels",
            ScaleKind.CORRELATION);

    private static final ColumnSpec MANDERS_M1 = ColumnSpec.of(
            "Manders M1", "",
            "Fraction of this object's source-channel signal in voxels above the"
                    + " target's Costes threshold",
            ScaleKind.FRACTION);

    private static final ColumnSpec MANDERS_M2 = ColumnSpec.of(
            "Manders M2", "",
            "Fraction of this object's target-channel signal in voxels above the"
                    + " source's Costes threshold",
            ScaleKind.FRACTION);

    private static final ColumnSpec MANDERS_VALID = ColumnSpec.of(
            "Manders Valid?", "",
            "0 where Manders was not reported for this object: too few voxels, or a"
                    + " channel carrying negative intensities",
            ScaleKind.BINARY);

    private static final ColumnSpec PEARSON_THRESHOLDED = ColumnSpec.of(
            "Pearson r Thresholded", "",
            "Pearson over this object's voxels above both Costes thresholds",
            ScaleKind.CORRELATION);

    private static final ColumnSpec COSTES_TA = ColumnSpec.of(
            "Costes Ta", "intensity",
            "Costes automatic threshold on the source channel within this object",
            ScaleKind.UNBOUNDED);

    private static final ColumnSpec COSTES_TB = ColumnSpec.of(
            "Costes Tb", "intensity",
            "Costes automatic threshold on the target channel within this object",
            ScaleKind.UNBOUNDED);

    private static final ColumnSpec COSTES_FITTED = ColumnSpec.of(
            "Costes Threshold Fitted?", "",
            "1 where this object's thresholds came from the bisection, 0 where the"
                    + " search bailed out to the object's minimum",
            ScaleKind.BINARY);

    private static final ColumnSpec OBJECT_VOXELS = ColumnSpec.of(
            "Object Voxels", "voxels",
            "Voxels in this object's mask, which is what every metric was measured over",
            ScaleKind.COUNT);

    private static final ColumnSpec CORRELATION_DEFINED = ColumnSpec.of(
            "Correlation Defined?", "",
            "0 where Pearson has no value for this object: fewer than "
                    + MIN_VOXELS_FOR_CORRELATION + " voxels, or a channel with no"
                    + " variation inside it",
            ScaleKind.BINARY);

    /**
     * Settings for the per-object metric call, fixed rather than exposed.
     *
     * <p>{@code workers(1)} because the parallel axis is objects, and a pool per
     * object inside a pool over objects is the nested-pool case the performance
     * contract forbids. {@code voxelLimit(0)} switches the randomization off for
     * the reason the class comment gives; it is the existing knob for exactly this
     * — "above this many domain voxels the randomization is skipped and said so".
     */
    private static final ColocalizationMetrics.Options METRIC_OPTIONS =
            ColocalizationMetrics.Options.builder().workers(1).voxelLimit(0L).build();

    private final double coincidentThreshold;
    private final int workers;
    private final ExecutorService executor;

    public PerObjectIntensityEngine() {
        this(DEFAULT_COINCIDENT_R);
    }

    public PerObjectIntensityEngine(double coincidentThreshold) {
        this(coincidentThreshold, 0);
    }

    /** @param workers 1 forces the serial path; 0 sizes the pool from the machine */
    public PerObjectIntensityEngine(double coincidentThreshold, int workers) {
        this(coincidentThreshold, workers, null);
    }

    /**
     * @param executor a caller-owned pool to run the objects on instead of the
     *                 bounded one this engine would create and shut down itself.
     *                 Never shut down here — the host that made it owns it
     */
    public PerObjectIntensityEngine(double coincidentThreshold, int workers,
                                    ExecutorService executor) {
        // A correlation coefficient, so -1 to 1. Rejecting anything else here
        // matters more than usual: a setting of 30, meaning "30%", would make
        // every object non-coincident and the result would look like a finding.
        if (!(coincidentThreshold >= -1.0 && coincidentThreshold <= 1.0)) {
            throw new IllegalArgumentException("the coincidence threshold is a"
                    + " Pearson r, so it runs from -1 to 1, not "
                    + coincidentThreshold);
        }
        this.coincidentThreshold = coincidentThreshold;
        this.workers = workers;
        this.executor = executor;
    }

    @Override
    public String id() {
        return "per-object-intensity";
    }

    @Override
    public String displayName() {
        return "Per-object intensity (masked Pearson / Manders)";
    }

    @Override
    public EngineFamily family() {
        return EngineFamily.INTENSITY;
    }

    @Override
    public Set<InputRequirement> requires() {
        return Collections.unmodifiableSet(EnumSet.of(
                InputRequirement.LABEL_IMAGES, InputRequirement.INTENSITY_IMAGES));
    }

    @Override
    public List<ColumnSpec> columns() {
        return Arrays.asList(PEARSON, MANDERS_M1, MANDERS_M2, MANDERS_VALID,
                PEARSON_THRESHOLDED, COSTES_TA, COSTES_TB, COSTES_FITTED,
                OBJECT_VOXELS, CORRELATION_DEFINED);
    }

    @Override
    public boolean isSymmetric() {
        // Pearson's r is symmetric in its two arguments, and reading only that far
        // is how this would be got wrong: r(A, B) does equal r(B, A) once the mask
        // is fixed. But the mask is not fixed — it is whichever channel's objects
        // the direction names. A→B is one row per object of A, masked by A; B→A is
        // one row per object of B, masked by B. Different objects, different
        // masks, different counts, different numbers. Declaring this symmetric
        // would let the null-model layer compute one direction and halve its work,
        // and the half it skipped is not a copy of the half it kept — it is the
        // rows for the other channel's objects, which nothing else would produce.
        return false;
    }

    @Override
    public double relativeCost() {
        // One fused gathering pass over the volume, then per object: one scan for
        // the statistics, up to 32 for the Costes bisection, and three more for
        // Manders and the thresholded Pearson. Those per-object passes run over
        // the object voxels only, so the ~36 passes are charged against the
        // fraction of the volume the objects cover rather than against all of it.
        // At a typical 20% coverage that is 1 + 0.2 × 36 ≈ 8.
        //
        // Sixteen times the object family's 1.5 and a sixteenth of whole-image
        // intensity's 130 — the difference from the latter being the 100
        // permutations, which this engine does not run. Sparse puncta come in
        // under this estimate and a dense cytoplasmic label goes over it.
        return 8.0;
    }

    @Override
    public EngineResult compute(EngineInputs inputs, EngineProgress progress) {
        EngineProgress reporter = progress == null ? EngineProgress.SILENT : progress;
        if (reporter.isCancelled()) {
            throw new EngineCancelledException(id());
        }
        List<ImagePlus> intensity = inputs.intensityImages();
        if (intensity.size() != inputs.channelCount()) {
            throw new IllegalStateException("engine '" + id() + "' needs one intensity"
                    + " image per label image; got " + intensity.size() + " for "
                    + inputs.channelCount() + " channels");
        }

        List<DirectionKey> directions = inputs.allDirections();
        EngineResult.Builder result = EngineResult.forEngine(id());
        ObjectVoxelGatherer.Gathered gathered = null;
        int gatheredSource = -1;
        for (int d = 0; d < directions.size(); d++) {
            if (reporter.isCancelled()) {
                throw new EngineCancelledException(id());
            }
            DirectionKey direction = directions.get(d);
            if (direction.sourceIndex() != gatheredSource) {
                // Released before the next is built rather than after, so peak
                // memory is one channel's gather and not two. Directions arrive
                // grouped by source, so each source is gathered exactly once; an
                // ungrouped order would still be correct, only slower.
                gathered = null;
                gatheredSource = -1;
                reporter.report("Gathering " + direction.sourceName() + " object voxels",
                        (double) d / directions.size());
                gathered = ObjectVoxelGatherer.gather(
                        inputs.labelImages().get(direction.sourceIndex()), intensity, reporter);
                gatheredSource = direction.sourceIndex();
            }
            reporter.report("Per-object intensity " + direction.label(),
                    (double) d / directions.size());
            addDirection(result, direction, gathered, reporter);
        }
        reporter.report("Per-object intensity", 1.0);
        return result.build();
    }

    /**
     * Measures every object of one direction and merges the results by index.
     *
     * <p>Each task writes only {@code perObject[itsOwnIndex]}, and the columns are
     * built afterwards on this thread by walking that array in order. Completion
     * order therefore cannot reach the table. Merging as results arrive would be
     * the natural way to write this and would produce a table whose rows are
     * correct values against the wrong objects — every column internally
     * consistent, every row attributed to the wrong label, and no symptom anywhere.
     */
    private void addDirection(EngineResult.Builder result, DirectionKey direction,
                              ObjectVoxelGatherer.Gathered gathered, EngineProgress reporter) {
        int objects = gathered.objectCount();
        ColocalizationMetrics.Result[] perObject = new ColocalizationMetrics.Result[objects];
        int resolved = resolveWorkers(objects);
        if (resolved <= 1) {
            for (int i = 0; i < objects; i++) {
                if (reporter.isCancelled()) {
                    throw new EngineCancelledException(id());
                }
                perObject[i] = measure(gathered, i,
                        direction.sourceIndex(), direction.targetIndex());
            }
        } else {
            runObjectsInParallel(gathered, direction, perObject, resolved, reporter);
        }

        List<ObjectScore> scores = new ArrayList<ObjectScore>(objects);
        double[] m1 = new double[objects];
        double[] m2 = new double[objects];
        double[] mandersValid = new double[objects];
        double[] thresholded = new double[objects];
        double[] ta = new double[objects];
        double[] tb = new double[objects];
        double[] fitted = new double[objects];
        double[] voxels = new double[objects];
        double[] defined = new double[objects];
        for (int i = 0; i < objects; i++) {
            ColocalizationMetrics.Result metrics = perObject[i];
            double r = metrics == null ? Double.NaN : metrics.pearson();
            boolean hasCorrelation = !Double.isNaN(r);
            scores.add(new ObjectScore(gathered.labelAt(i), ObjectScore.NO_PARTNER, r,
                    hasCorrelation && r >= coincidentThreshold));
            voxels[i] = gathered.voxelCountAt(i);
            defined[i] = hasCorrelation ? 1.0 : 0.0;
            if (metrics == null) {
                m1[i] = Double.NaN;
                m2[i] = Double.NaN;
                mandersValid[i] = 0.0;
                thresholded[i] = Double.NaN;
                ta[i] = Double.NaN;
                tb[i] = Double.NaN;
                fitted[i] = 0.0;
            } else {
                m1[i] = metrics.mandersM1();
                m2[i] = metrics.mandersM2();
                mandersValid[i] = metrics.mandersRefusedNegative() ? 0.0 : 1.0;
                thresholded[i] = metrics.pearsonThresholded();
                ta[i] = metrics.costesTa();
                tb[i] = metrics.costesTb();
                fitted[i] = metrics.costesThresholdFitted() ? 1.0 : 0.0;
            }
        }

        result.direction(direction, scores);
        result.supporting(direction, MANDERS_M1.name(), m1);
        result.supporting(direction, MANDERS_M2.name(), m2);
        result.supporting(direction, MANDERS_VALID.name(), mandersValid);
        result.supporting(direction, PEARSON_THRESHOLDED.name(), thresholded);
        result.supporting(direction, COSTES_TA.name(), ta);
        result.supporting(direction, COSTES_TB.name(), tb);
        result.supporting(direction, COSTES_FITTED.name(), fitted);
        result.supporting(direction, OBJECT_VOXELS.name(), voxels);
        result.supporting(direction, CORRELATION_DEFINED.name(), defined);
    }

    /**
     * One object's metrics, or null where it is too small to carry any.
     *
     * <p>The samples are already packed, so the volume handed to
     * {@link ColocalizationMetrics} is {@code n × 1 × 1}. Nothing downstream of
     * the statistics uses that geometry: the block shuffle it would matter for is
     * switched off in {@link #METRIC_OPTIONS}.
     */
    private ColocalizationMetrics.Result measure(ObjectVoxelGatherer.Gathered gathered,
                                                 int index, int sourceChannel,
                                                 int targetChannel) {
        int count = gathered.voxelCountAt(index);
        if (count < MIN_VOXELS_FOR_CORRELATION) {
            return null;
        }
        double[] source = gathered.samplesFor(index, sourceChannel);
        double[] target = gathered.samplesFor(index, targetChannel);
        return ColocalizationMetrics.compute(source, target, count, 1, 1,
                METRIC_OPTIONS, EngineProgress.SILENT);
    }

    /**
     * How many workers to use for {@code taskCount} objects.
     *
     * <p>Sized {@code min(availableProcessors - 1, objects)} when the caller did
     * not say, leaving a core for the coordinator and never starting a thread with
     * nothing to do. One runs the objects on the calling thread and creates no pool
     * at all — the serial override the performance contract requires, and the only
     * setting under which no thread can leak because none is started.
     */
    private int resolveWorkers(int taskCount) {
        int requested = workers > 0
                ? workers
                : Runtime.getRuntime().availableProcessors() - 1;
        if (requested < 1) {
            requested = 1;
        }
        return Math.min(requested, Math.max(1, taskCount));
    }

    private void runObjectsInParallel(final ObjectVoxelGatherer.Gathered gathered,
                                      final DirectionKey direction,
                                      final ColocalizationMetrics.Result[] perObject,
                                      int resolved, final EngineProgress reporter) {
        ExecutorService injected = executor;
        ExecutorService pool = injected != null ? injected : newPool(resolved);
        boolean owned = injected == null;
        List<Future<?>> futures = new ArrayList<Future<?>>(perObject.length);
        boolean cancelled = false;
        RuntimeException failure = null;
        try {
            for (int i = 0; i < perObject.length; i++) {
                // A cancellation arriving before the queue is full should stop
                // filling it, not queue five thousand more objects that will each
                // start and immediately refuse.
                if (reporter.isCancelled()) {
                    cancelled = true;
                    break;
                }
                final int index = i;
                futures.add(pool.submit(new Callable<Void>() {
                    @Override
                    public Void call() {
                        if (reporter.isCancelled()) {
                            throw new EngineCancelledException(id());
                        }
                        perObject[index] = measure(gathered, index,
                                direction.sourceIndex(), direction.targetIndex());
                        return null;
                    }
                }));
            }
            for (int i = 0; !cancelled && i < futures.size(); i++) {
                // Checked here as well as inside the tasks so a cancellation that
                // arrives while the queue is still draining stops the queue rather
                // than waiting for every queued object to start and refuse.
                if (reporter.isCancelled()) {
                    cancelled = true;
                    break;
                }
                try {
                    futures.get(i).get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancelled = true;
                    break;
                } catch (ExecutionException e) {
                    failure = unwrap(e);
                    break;
                }
                reporter.report("Per-object intensity " + direction.label(),
                        (i + 1.0) / futures.size());
            }
        } finally {
            // First failure or cancellation drains the rest rather than letting
            // queued objects keep burning cores for a result nobody will read.
            if (cancelled || failure != null) {
                for (int i = 0; i < futures.size(); i++) {
                    futures.get(i).cancel(true);
                }
            }
            if (owned) {
                shutdownAndWait(pool, cancelled || failure != null);
            }
        }
        if (failure != null) {
            throw failure;
        }
        if (cancelled) {
            throw new EngineCancelledException(id());
        }
    }

    private static RuntimeException unwrap(ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException) {
            return (RuntimeException) cause;
        }
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        return new IllegalStateException("per-object intensity measurement failed", cause);
    }

    private static ExecutorService newPool(int workers) {
        return Executors.newFixedThreadPool(workers, new ThreadFactory() {
            private final AtomicInteger next = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable,
                        "ocs-per-object-intensity-" + next.incrementAndGet());
                // Daemon so a bug here can never be the reason Fiji will not quit.
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    /**
     * Returns only once the pool's threads are actually gone, so a cancelled run
     * leaves nothing behind. Bounded, because hanging on shutdown would be a worse
     * failure than leaking.
     */
    private static void shutdownAndWait(ExecutorService pool, boolean abrupt) {
        if (abrupt) {
            pool.shutdownNow();
        } else {
            pool.shutdown();
        }
        try {
            if (!pool.awaitTermination(30L, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ---------- ThresholdBearing ----------

    @Override
    public String thresholdName() {
        return "Per-object Pearson r";
    }

    @Override
    public String thresholdUnit() {
        return "";
    }

    @Override
    public double threshold() {
        return coincidentThreshold;
    }

    /**
     * Carries the worker count and any caller-owned executor across, so a sweep
     * does not silently drop the host's pool and start making its own.
     */
    @Override
    public ColocEngine withThreshold(double threshold) {
        return new PerObjectIntensityEngine(threshold, workers, executor);
    }

    /** -1 to +1 in twenty-one steps of 0.1: a correlation spans both signs. */
    @Override
    public double[] thresholdRange() {
        // Pearson's r. Perfect anticorrelation to perfect correlation.
        return new double[] {-1.0, 1.0};
    }

    @Override
    public double[] defaultLadder() {
        double[] ladder = new double[21];
        for (int i = 0; i < ladder.length; i++) {
            ladder[i] = -1.0 + i * 0.1;
        }
        return ladder;
    }

}
