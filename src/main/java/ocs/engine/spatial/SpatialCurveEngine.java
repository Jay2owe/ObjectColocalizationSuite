package ocs.engine.spatial;

import ocs.engine.ColocEngine;
import ocs.engine.ColumnSpec;
import ocs.engine.CurveSeries;
import ocs.engine.DirectionKey;
import ocs.engine.EngineCancelledException;
import ocs.engine.EngineFamily;
import ocs.engine.EngineInputs;
import ocs.engine.EngineProgress;
import ocs.engine.EngineResult;
import ocs.engine.InputRequirement;
import ocs.engine.ObjectScore;
import ocs.engine.ScaleKind;
import sc.fiji.opa.core.AnalysisCancelledException;
import sc.fiji.opa.core.EngineLimits;
import sc.fiji.opa.core.ProgressListener;
import sc.fiji.opa.core.spatial.EdgeCorrection;
import sc.fiji.opa.core.spatial.MonteCarloAnalyzer;
import sc.fiji.opa.core.spatial.MonteCarloResult;
import sc.fiji.opa.core.spatial.PatternFunction;
import sc.fiji.opa.core.spatial.PatternStatus;
import sc.fiji.opa.core.spatial.RectangularWindow;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Shared body of the four cross point-pattern engines.
 *
 * <p>All four ask the same question of two channels — "are the objects of B
 * arranged around the objects of A more, or less, tightly than chance would put
 * them" — and differ only in which statistic answers it. They all go through one
 * {@code opa-core} call, {@code MonteCarloAnalyzer.analyzeBivariate}, so the
 * envelope, the seed and the global test are literally the same code for each,
 * and a change to how a seed is recorded cannot drift between them.
 *
 * <h2>These engines have no per-object value, and that is not a gap</h2>
 *
 * <p>Cross-K at a radius is a property of the two patterns as a whole. There is
 * no meaningful way to say what share of it belongs to object 7. Every direction
 * is therefore reported with an <b>empty score list</b> and a {@link CurveSeries},
 * which is exactly the shape {@code EngineResult} grew a curve channel for.
 *
 * <p>The consequence is worth stating plainly, because it is invisible in the
 * output: with no {@link ObjectScore}s, all three tiers of the agreement layer see
 * <i>n</i> = 0 against every other method, and Discovery classes these engines
 * "Not applicable" rather than comparing them to anything. Their evidence is the
 * curve's own global <i>p</i>, not agreement with the object family.
 *
 * <h2>What the seed means</h2>
 *
 * <p>{@code Seed} is recorded on every curve and it is a complete description of
 * the randomness: given the same points, window, radii, correction and simulation
 * count, that seed reproduces the envelope bit for bit, at any worker count.
 * {@code opa-core} draws all simulated patterns from one {@code new Random(seed)}
 * on the coordinating thread and merges results by index, so parallelism changes
 * the speed and nothing else. The engines add no randomness of their own and
 * start no threads of their own — the simulation count is the only knob, and the
 * {@code opa.parallelism} system property (that exact name) is the only control
 * over how many workers {@code opa-core} uses.
 *
 * <p>Seeds are constrained to those a {@code double} carries exactly, because a
 * {@link CurveSeries} scalar is a {@code double} and a recorded seed that does not
 * replay the run is worse than no record at all.
 *
 * <h2>Not-computable cases arrive as a status</h2>
 *
 * <p>A run covers every ordered channel pair. One pair with too few objects must
 * not take the other nine with it, so every refusal is carried on that pair's
 * curve through {@link CurveSeries#status()} and the rest of the run continues.
 * Only genuine author errors — an unusable edge correction, a seed that cannot be
 * recorded, radii that do not increase — throw, and they throw at construction,
 * before a batch has run for an hour.
 */
public abstract class SpatialCurveEngine implements ColocEngine {

    // ---------- statuses ----------

    /** Fewer than {@link #MINIMUM_POINTS} objects in one of the two channels. */
    public static final String STATUS_INSUFFICIENT_POINTS = "INSUFFICIENT_POINTS";

    /** Every object of a channel shares one centre, so the pattern has no extent. */
    public static final String STATUS_DEGENERATE_PATTERN = "DEGENERATE_PATTERN";

    /** The largest radius reaches at least halfway across the shorter window side. */
    public static final String STATUS_RADIUS_EXCEEDS_WINDOW = "RADIUS_EXCEEDS_WINDOW";

    /** The window is the domain's bounding box rather than the domain itself. */
    public static final String STATUS_NON_RECTANGULAR_DOMAIN = "NON_RECTANGULAR_DOMAIN";

    // ---------- curve vocabulary ----------

    public static final String X_NAME = "Radius";
    public static final String OBSERVED = "Observed";
    public static final String EXPECTED = "Expected";
    public static final String LOWER = "Lower";
    public static final String UPPER = "Upper";
    public static final String ENVELOPE_SAMPLES = "Envelope Samples";

    public static final String GLOBAL_P = "Global p";
    public static final String MINIMUM_P = "Minimum Achievable p";
    public static final String MAX_DEVIATION = "Max Deviation";
    public static final String MAX_DEVIATION_RADIUS = "Max Deviation Radius";
    public static final String SIMULATIONS = "Simulations";
    public static final String SEED = "Seed";
    public static final String RANK_SAMPLE_COUNT = "Rank Sample Count";
    public static final String ENVELOPE_COMPLETE = "Envelope Complete";
    public static final String SOURCE_POINTS = "Source Points";
    public static final String TARGET_POINTS = "Target Points";

    /**
     * Pointwise escape probability the envelope actually delivers,
     * {@code 2k / (simulations + 1)} for a rank-k envelope (opa-core 0.3.0 and
     * later): 0.04 at the default 99 simulations, a 96% band rather than 95%.
     */
    public static final String ENVELOPE_LEVEL = "Envelope Level";

    /**
     * Radius past which cross-G has climbed to 1 under randomness and the band
     * collapses, so those radii can neither be escaped nor inform the global
     * test. NaN for the K-derived curves, which never saturate.
     */
    public static final String SATURATION_RADIUS = "Saturation Radius";

    /** How many of the requested radii lie past {@link #SATURATION_RADIUS}. */
    public static final String SATURATED_RADII = "Saturated Radii";

    // ---------- defaults ----------

    /** 99 puts the smallest expressible <i>p</i> at 0.01, which clears α = 0.05. */
    public static final int DEFAULT_SIMULATIONS = 99;

    /** Arbitrary but pinned: changing it changes every default run's envelope. */
    public static final long DEFAULT_SEED = 20260811L;

    /** 2^53: above this, consecutive integers stop being distinct as doubles. */
    public static final long MAX_EXACT_SEED = 1L << 53;

    /** Twenty radii out to a quarter of the shorter window side. */
    public static final int DEFAULT_RADIUS_BINS = 20;

    /**
     * Below this, a channel cannot support an envelope.
     *
     * <p>{@code opa-core} refuses only an <i>empty</i> channel in the bivariate
     * form. One point is worse than useless rather than merely weak: every
     * simulated curve is then a single indicator step, the envelope collapses onto
     * whichever radius that one distance falls in, and the global test reports a
     * confident number computed from one object.
     */
    public static final int MINIMUM_POINTS = 2;

    private final PatternFunction function;
    private final EdgeCorrection correction;
    private final int simulations;
    private final long seed;
    private final int radiusBins;
    private final double[] radii;

    /**
     * @param function   the bivariate statistic; must be one {@code opa-core}
     *                   accepts through {@code analyzeBivariate}
     * @param correction edge treatment for the K-derived forms. Ignored by
     *                   cross-G, which has no edge correction at all
     * @param radii      explicit radii, or null to derive them from the window
     */
    protected SpatialCurveEngine(PatternFunction function, EdgeCorrection correction,
                                 int simulations, long seed, int radiusBins,
                                 double[] radii) {
        if (function == null || !function.isBivariate()) {
            throw new IllegalArgumentException("a spatial engine needs a bivariate "
                    + "pattern function, got " + function);
        }
        if (correction == null) {
            throw new IllegalArgumentException("edge correction must not be null");
        }
        if (simulations < 1 || simulations > EngineLimits.MAX_SIMULATIONS) {
            throw new IllegalArgumentException("simulations must be between 1 and "
                    + EngineLimits.MAX_SIMULATIONS + ", was " + simulations);
        }
        if (Math.abs(seed) > MAX_EXACT_SEED) {
            // The seed is recorded as a CurveSeries scalar, which is a double.
            // Beyond 2^53 the recorded value is a different seed from the one that
            // ran, so the run record would replay a different envelope while
            // looking exactly like a faithful record.
            //
            // Checked by magnitude rather than by casting to double and back:
            // that round trip looks exact for Long.MAX_VALUE, because the cast up
            // rounds and the cast back saturates to the same number — so the
            // obvious test passes for the value most likely to be wrong.
            throw new IllegalArgumentException("seed " + seed + " cannot be recorded "
                    + "exactly in a curve scalar, which is a double; use a seed of "
                    + "magnitude at most 2^53 so the recorded seed replays the run");
        }
        this.function = function;
        this.correction = correction;
        this.simulations = simulations;
        this.seed = seed;
        this.radii = radii == null ? null : validRadii(radii);
        if (radii == null && radiusBins < 2) {
            throw new IllegalArgumentException("a curve needs at least two radii, "
                    + "was asked for " + radiusBins);
        }
        this.radiusBins = radiusBins;
    }

    // ---------- what each statistic supplies ----------

    /** Column header for the curve's own quantity — "Cross-K", "Cross-G". */
    protected abstract String curveColumnName();

    /** One sentence for the column's description and the dialog tooltip. */
    protected abstract String curveDescription();

    /**
     * Cost of evaluating this statistic once, in units of one pass over the
     * voxels. Multiplied by the simulation count in {@link #relativeCost()}.
     */
    protected abstract double perCurveCost();

    // ---------- accessors, so a run record can state what actually ran ----------

    public final PatternFunction function() {
        return function;
    }

    public final EdgeCorrection correction() {
        return correction;
    }

    public final int simulations() {
        return simulations;
    }

    public final long seed() {
        return seed;
    }

    // ---------- ColocEngine ----------

    @Override
    public final EngineFamily family() {
        return EngineFamily.SPATIAL;
    }

    @Override
    public final Set<InputRequirement> requires() {
        // ROI_DOMAIN because the envelope is a null model, and 02_CONTRACT.md is
        // unambiguous that a null model without a domain must be refused rather
        // than defaulted: simulating uniform points across a field that is mostly
        // empty slide understates the expected density and turns ordinary
        // patterns significant.
        //
        // CALIBRATION deliberately absent, for the reason DistanceToleranceEngine
        // gives: a radius in pixels is a perfectly good radius, and greying the
        // spatial family out on every uncalibrated mask would cost far more than
        // it saves. The unit travels on the curve's x axis either way.
        return Collections.unmodifiableSet(EnumSet.of(
                InputRequirement.LABEL_IMAGES, InputRequirement.ROI_DOMAIN));
    }

    /**
     * The primary column names the curve itself; the rest name the curve's
     * scalars.
     *
     * <p>Declaring a primary column at all is a concession to
     * {@code EngineRegistry}, which requires exactly one and assumes it is what
     * {@link ObjectScore#value()} carries. Nothing here ever produces an
     * {@code ObjectScore}, so this column never appears in the per-object table.
     * It is the honest name of what the engine measures, and the stage report
     * records the mismatch rather than papering over it.
     */
    @Override
    public final List<ColumnSpec> columns() {
        return Arrays.asList(
                ColumnSpec.primary(curveColumnName(), "", curveDescription(),
                        primaryScale()),
                ColumnSpec.of(GLOBAL_P, "",
                        "Maximum-deviation p over the whole curve, not per radius",
                        ScaleKind.FRACTION),
                ColumnSpec.of(MINIMUM_P, "",
                        "Smallest p this simulation count can express, 1/(simulations+1)",
                        ScaleKind.FRACTION),
                // CURVE, not UNBOUNDED: it is an ordinate of this curve — the
                // largest one — so it is incommensurable with another engine's
                // for exactly the reason the ordinates are.
                ColumnSpec.of(MAX_DEVIATION, "",
                        "Largest departure of the observed curve from the theoretical "
                                + "expectation, in the curve's own units",
                        ScaleKind.CURVE),
                ColumnSpec.of(MAX_DEVIATION_RADIUS, "",
                        "Radius at which the standardized departure is largest",
                        ScaleKind.DISTANCE),
                ColumnSpec.of(SIMULATIONS, "",
                        "Complete-spatial-randomness patterns behind the envelope",
                        ScaleKind.COUNT),
                ColumnSpec.of(SEED, "",
                        "Seed of the single random stream every simulated pattern "
                                + "was drawn from",
                        ScaleKind.UNBOUNDED),
                ColumnSpec.of(RANK_SAMPLE_COUNT, "",
                        "Curves that took part in the global rank, observed included",
                        ScaleKind.COUNT),
                ColumnSpec.of(ENVELOPE_COMPLETE, "",
                        "1 where every radius drew on all simulations, 0 where some "
                                + "simulated curves were not estimable",
                        ScaleKind.BINARY),
                ColumnSpec.of(SOURCE_POINTS, "",
                        "Source objects whose centre fell inside the domain window",
                        ScaleKind.COUNT),
                ColumnSpec.of(TARGET_POINTS, "",
                        "Target objects whose centre fell inside the domain window",
                        ScaleKind.COUNT),
                ColumnSpec.of(ENVELOPE_LEVEL, "",
                        "Pointwise escape probability the envelope delivers, "
                                + "2k/(simulations+1); 0.04 at 99 simulations",
                        ScaleKind.FRACTION),
                ColumnSpec.of(SATURATION_RADIUS, "",
                        "Radius past which cross-G reaches 1 under randomness and the "
                                + "band collapses; NaN for curves that never saturate",
                        ScaleKind.DISTANCE),
                ColumnSpec.of(SATURATED_RADII, "",
                        "Requested radii past the saturation radius, which carry no "
                                + "information about the pattern",
                        ScaleKind.COUNT));
    }

    /**
     * {@link ScaleKind#CURVE} for all four.
     *
     * <p>This was {@code UNBOUNDED}, which gated tier-3 agreement correctly and
     * was <i>false</i> about cross-G — an empirical distribution function lives
     * in [0, 1]. {@code CURVE} was added to say the true thing and gate the same
     * way: never eligible for direct-value agreement, including against itself,
     * because there is no single value to compare, only a vector whose meaning
     * depends on where on the axis you stand. Declaring cross-G {@code FRACTION}
     * to be truthful about its interval would have been worse than either — it
     * would claim a cross-G ordinate is directly comparable with a Jaccard index,
     * when one is a share of an object's voxels and the other the share of
     * objects with a neighbour within <i>r</i>.
     */
    protected ScaleKind primaryScale() {
        return ScaleKind.CURVE;
    }

    /**
     * False, for all four, and the interesting case is cross-K.
     *
     * <p>Cross-K's observed curve <i>is</i> symmetric under no correction and
     * under translation correction: the weighted pair sum depends on |dx| and |dy|
     * only and the denominator is n<sub>A</sub>n<sub>B</sub> either way. Its
     * <b>envelope is not</b>. {@code opa-core} draws n<sub>A</sub> source points
     * and then n<sub>B</sub> target points from one seeded stream, so swapping the
     * roles draws a different set of simulated patterns and returns a different
     * band and a different global <i>p</i> — and under border correction even the
     * observed curve differs, because the eligible-source set is the source
     * channel's. Cross-G is not symmetric in any sense: "how many A have a B
     * nearby" and "how many B have an A nearby" are different questions with
     * different answers.
     *
     * <p>Declaring true would let {@code EngineRegistry.estimateCost} charge for
     * half the directions and license a caller to compute one direction and copy
     * it. The point estimate would survive that; the envelope, which is the part a
     * reader draws a conclusion from, would not.
     */
    @Override
    public final boolean isSymmetric() {
        return false;
    }

    /**
     * Scales with the simulation count, because the run does.
     *
     * <p>The observed curve is computed once and then again for every simulated
     * pattern, so a hundredfold change in the simulation count is a hundredfold
     * change in the wall clock. A fixed weight would make the dialog's estimate
     * wrong by exactly the factor a user is most likely to change. At the default
     * 99 simulations the K-derived engines land near 100 — the same order as
     * whole-image intensity's 130, which is the honest comparison: both are
     * dominated by a randomization loop.
     *
     * <p>What the weight cannot know is the object count. Cost per curve is
     * O(n<sub>A</sub>·n<sub>B</sub>·radii), so on a field with thousands of objects
     * per channel these are far more expensive than this number says. The nominal
     * 1.0 per curve corresponds to a few hundred objects a channel, where the pair
     * loop is comparable to one pass over a megapixel image.
     */
    @Override
    public final double relativeCost() {
        return perCurveCost() * (simulations + 1);
    }

    @Override
    public final EngineResult compute(EngineInputs inputs, EngineProgress progress) {
        EngineProgress reporter = progress == null ? EngineProgress.SILENT : progress;
        if (reporter.isCancelled()) {
            throw new EngineCancelledException(id());
        }
        reporter.report(displayName(), 0.0);

        PointPattern pattern = PointPattern.from(inputs);
        RectangularWindow window = pattern.window();
        double[] axis = radii == null ? defaultRadii(window, radiusBins) : radii.clone();

        EngineResult.Builder result = EngineResult.forEngine(id());
        List<DirectionKey> directions = inputs.allDirections();
        for (int d = 0; d < directions.size(); d++) {
            if (reporter.isCancelled()) {
                throw new EngineCancelledException(id());
            }
            DirectionKey direction = directions.get(d);
            result.direction(direction, Collections.<ObjectScore>emptyList());
            result.curve(direction, curveFor(
                    pattern.points(direction.sourceIndex()),
                    pattern.points(direction.targetIndex()),
                    window, axis, pattern.unit(), pattern.isRectangularDomain(),
                    new Reporter(reporter, d, directions.size())));
            reporter.report(displayName(), (d + 1.0) / directions.size());
        }
        return result.build();
    }

    /**
     * Radii for a window nobody chose them for: twenty steps out to a quarter of
     * the shorter side.
     *
     * <p>The quarter is the standard rule of thumb for Ripley's K, and it is a
     * rule about honesty rather than about speed — beyond it, few source points
     * have a complete neighbourhood inside the window and the edge correction is
     * doing most of the work.
     */
    static double[] defaultRadii(RectangularWindow window, int bins) {
        double maximum = Math.min(window.width(), window.height()) / 4.0;
        double[] axis = new double[bins];
        for (int i = 0; i < bins; i++) {
            axis[i] = maximum * (i + 1) / bins;
        }
        return validRadii(axis);
    }

    private CurveSeries curveFor(double[][] source, double[][] target,
                                 RectangularWindow window, double[] axis,
                                 String unit, boolean rectangularDomain,
                                 Reporter reporter) {
        String blocked = preflight(source, target, window, axis);
        if (blocked != null) {
            return notComputable(axis, unit, source.length, target.length, blocked);
        }

        MonteCarloResult analysis;
        try {
            analysis = MonteCarloAnalyzer.analyzeBivariate(function, source, target,
                    window, axis, correction, simulations, seed, reporter);
        } catch (AnalysisCancelledException cancelled) {
            // opa-core watches ImageJ's Escape key on its own. Translated rather
            // than propagated, because every layer above this speaks
            // EngineCancelledException and a cancelled run must never be mistaken
            // for a complete one whichever key stopped it.
            throw new EngineCancelledException(id());
        }

        String status = statusOf(analysis.getStatus());
        if (CurveSeries.STATUS_OK.equals(status) && !rectangularDomain) {
            status = STATUS_NON_RECTANGULAR_DOMAIN;
        }

        return CurveSeries.over(X_NAME, unit, analysis.getRadii())
                .series(OBSERVED, analysis.getObserved())
                .series(EXPECTED, analysis.getExpected())
                .series(LOWER, analysis.getLower())
                .series(UPPER, analysis.getUpper())
                .series(ENVELOPE_SAMPLES, asDoubles(analysis.getEnvelopeSampleCounts()))
                .scalar(GLOBAL_P, analysis.getGlobalPValue())
                .scalar(MINIMUM_P, analysis.getMinimumAchievablePValue())
                .scalar(MAX_DEVIATION, analysis.getMaximumDeviation())
                .scalar(MAX_DEVIATION_RADIUS, analysis.getMaximumDeviationRadius())
                .scalar(SIMULATIONS, analysis.getSimulations())
                .scalar(SEED, analysis.getSeed())
                .scalar(RANK_SAMPLE_COUNT, analysis.getRankSampleCount())
                .scalar(ENVELOPE_COMPLETE, analysis.hasCompletePointwiseEnvelope() ? 1.0 : 0.0)
                .scalar(SOURCE_POINTS, source.length)
                .scalar(TARGET_POINTS, target.length)
                .scalar(ENVELOPE_LEVEL, analysis.getEnvelopeLevel())
                .scalar(SATURATION_RADIUS, analysis.getSaturationRadius())
                .scalar(SATURATED_RADII, analysis.getSaturatedRadiusCount())
                .status(status)
                .build();
    }

    /**
     * The three refusals this engine makes before {@code opa-core} is called.
     *
     * @return the status to carry, or null when the pair is computable
     */
    private static String preflight(double[][] source, double[][] target,
                                    RectangularWindow window, double[] axis) {
        if (source.length < MINIMUM_POINTS || target.length < MINIMUM_POINTS) {
            return STATUS_INSUFFICIENT_POINTS;
        }
        if (hasNoExtent(source) || hasNoExtent(target)) {
            return STATUS_DEGENERATE_PATTERN;
        }
        if (axis[axis.length - 1] >= Math.min(window.width(), window.height()) / 2.0) {
            // Past half the shorter side, translation correction has no
            // displacement left to weight with and the observed curve is
            // dominated by the window rather than by the pattern.
            return STATUS_RADIUS_EXCEEDS_WINDOW;
        }
        return null;
    }

    /**
     * A curve of the right shape carrying nothing but the reason it is empty.
     *
     * <p>Same axis, same series names, same scalar names as a successful run, so a
     * table written over a batch keeps one schema whether or not a given channel
     * pair could be measured.
     */
    private CurveSeries notComputable(double[] axis, String unit,
                                      int sourcePoints, int targetPoints,
                                      String status) {
        double[] undefined = new double[axis.length];
        Arrays.fill(undefined, Double.NaN);
        double[] none = new double[axis.length];
        return CurveSeries.over(X_NAME, unit, axis)
                .series(OBSERVED, undefined)
                .series(EXPECTED, undefined)
                .series(LOWER, undefined)
                .series(UPPER, undefined)
                .series(ENVELOPE_SAMPLES, none)
                .scalar(GLOBAL_P, Double.NaN)
                .scalar(MINIMUM_P, Double.NaN)
                .scalar(MAX_DEVIATION, Double.NaN)
                .scalar(MAX_DEVIATION_RADIUS, Double.NaN)
                .scalar(SIMULATIONS, simulations)
                .scalar(SEED, seed)
                .scalar(RANK_SAMPLE_COUNT, 0.0)
                .scalar(ENVELOPE_COMPLETE, 0.0)
                .scalar(SOURCE_POINTS, sourcePoints)
                .scalar(TARGET_POINTS, targetPoints)
                .scalar(ENVELOPE_LEVEL, Double.NaN)
                .scalar(SATURATION_RADIUS, Double.NaN)
                .scalar(SATURATED_RADII, 0.0)
                .status(status)
                .build();
    }

    /** {@code opa-core}'s own vocabulary, carried across unchanged. */
    private static String statusOf(PatternStatus status) {
        return status == PatternStatus.OK ? CurveSeries.STATUS_OK : status.name();
    }

    private static boolean hasNoExtent(double[][] points) {
        for (int i = 1; i < points.length; i++) {
            if (points[i][0] != points[0][0] || points[i][1] != points[0][1]) {
                return false;
            }
        }
        return true;
    }

    private static double[] asDoubles(int[] counts) {
        double[] values = new double[counts.length];
        for (int i = 0; i < counts.length; i++) {
            values[i] = counts[i];
        }
        return values;
    }

    /**
     * Radii both {@code opa-core} and {@link CurveSeries} will accept, checked
     * once here so a caller's mistake surfaces at construction rather than as an
     * exception thrown from inside a batch that has already run for an hour.
     */
    private static double[] validRadii(double[] axis) {
        if (axis == null || axis.length == 0) {
            throw new IllegalArgumentException("at least one radius is required");
        }
        if (axis.length > EngineLimits.MAX_RADIUS_BINS) {
            throw new IllegalArgumentException("at most " + EngineLimits.MAX_RADIUS_BINS
                    + " radii, was given " + axis.length);
        }
        for (int i = 0; i < axis.length; i++) {
            if (Double.isNaN(axis[i]) || Double.isInfinite(axis[i]) || axis[i] <= 0.0) {
                throw new IllegalArgumentException("radius[" + i + "] is " + axis[i]
                        + "; radii must be finite and strictly positive. A zero radius"
                        + " carries no information and makes the first pair-correlation"
                        + " annulus a division by zero");
            }
            if (i > 0 && axis[i] <= axis[i - 1]) {
                throw new IllegalArgumentException("radii must increase strictly, but ["
                        + (i - 1) + "] = " + axis[i - 1] + " and [" + i + "] = " + axis[i]
                        + "; an unsorted axis plots as a curve that doubles back, which"
                        + " reads as a finding rather than as a bug");
            }
        }
        double[] copy = new double[axis.length];
        System.arraycopy(axis, 0, copy, 0, axis.length);
        return copy;
    }

    /**
     * Bridges {@code opa-core}'s progress callback onto this plugin's, and turns
     * a cancellation into the exception the rest of the plugin understands.
     *
     * <p>Called from the coordinating thread only — {@code opa-core} reports as it
     * merges finished simulations, never from inside a worker — so this needs no
     * synchronization.
     */
    private final class Reporter implements ProgressListener {

        private final EngineProgress progress;
        private final int index;
        private final int total;

        Reporter(EngineProgress progress, int index, int total) {
            this.progress = progress;
            this.index = index;
            this.total = total;
        }

        @Override
        public void onProgress(double fraction, String message) {
            if (progress.isCancelled()) {
                throw new EngineCancelledException(id());
            }
            progress.report(displayName(), (index + fraction) / total);
        }
    }
}
