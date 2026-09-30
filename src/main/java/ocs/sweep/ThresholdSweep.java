package ocs.sweep;

import ocs.engine.ColocEngine;
import ocs.engine.EngineCancelledException;
import ocs.engine.EngineInputs;
import ocs.engine.EngineProgress;
import ocs.engine.EngineResult;
import ocs.engine.ThresholdBearing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Re-runs a threshold-bearing engine across its ladder and reports how much the
 * answer moved.
 *
 * <p>The output is a {@link FlipFraction} per direction — the proportion of
 * objects whose colocalized/not classification is not the same at every step.
 * That number is what separates Discovery's <i>Usable</i> from its
 * <i>Fragile</i>: both cleared the null model, but only one of them would still
 * say the same thing if the cut-off had been chosen slightly differently.
 *
 * <p>Engines with no threshold report nothing rather than zero. Centroid
 * coincidence is purely geometric, and a flip fraction of 0.0 there would read
 * as "measured, and perfectly stable" when the truth is "there was no choice to
 * be sensitive to" — which is a point in the method's favour and deserves to be
 * said rather than disguised as a measurement.
 */
public final class ThresholdSweep {

    /** One engine's sweep: the ladder it walked and what moved. */
    public static final class Result {
        private final String engineId;
        private final String thresholdName;
        private final String thresholdUnit;
        private final double[] ladder;
        private final List<FlipFraction> flipFractions;
        private final String notSwept;

        private Result(String engineId, String thresholdName, String thresholdUnit,
                double[] ladder, List<FlipFraction> flipFractions, String notSwept) {
            this.engineId = engineId;
            this.thresholdName = thresholdName;
            this.thresholdUnit = thresholdUnit;
            this.ladder = ladder;
            this.flipFractions = Collections.unmodifiableList(flipFractions);
            this.notSwept = notSwept;
        }

        public String engineId() {
            return engineId;
        }

        public boolean wasSwept() {
            return notSwept == null;
        }

        /** Why no sweep ran, or null if one did. Never silently absent. */
        public String notSweptReason() {
            return notSwept;
        }

        public String thresholdName() {
            return thresholdName;
        }

        public String thresholdUnit() {
            return thresholdUnit;
        }

        public double[] ladder() {
            return ladder.clone();
        }

        public List<FlipFraction> flipFractions() {
            return flipFractions;
        }

        /**
         * The worst direction's flip fraction — the honest single number, since
         * a method that is stable one way and unstable the other is unstable.
         * NaN when nothing was swept or no object survived the ladder.
         */
        public double worstFlipFraction() {
            double worst = Double.NaN;
            for (int i = 0; i < flipFractions.size(); i++) {
                double value = flipFractions.get(i).value();
                if (Double.isNaN(value)) {
                    continue;
                }
                if (Double.isNaN(worst) || value > worst) {
                    worst = value;
                }
            }
            return worst;
        }
    }

    private ThresholdSweep() {
    }

    /**
     * Sweeps {@code engine} over {@code ladder}, or reports why it did not.
     *
     * <p>Serial. The sweep is at most twenty-one engine runs and the batch layer
     * above it already parallelizes over images; a pool here would nest inside
     * that one, which the performance contract forbids.
     *
     * @throws EngineCancelledException if cancelled — never a partial ladder,
     *         because a flip fraction computed over half the range understates
     *         instability and there is no way to see that from the number
     */
    public static Result of(ColocEngine engine, EngineInputs inputs,
            double[] ladder, EngineProgress progress) {
        if (engine == null || inputs == null) {
            throw new IllegalArgumentException("engine and inputs are required");
        }
        EngineProgress reporter = progress == null ? EngineProgress.SILENT : progress;

        if (!(engine instanceof ThresholdBearing)) {
            return new Result(engine.id(), "", "", new double[0],
                    new ArrayList<FlipFraction>(),
                    "no threshold to sweep — the measure is not a cut-off, so its "
                            + "answer cannot depend on where one was drawn");
        }
        ThresholdBearing bearing = (ThresholdBearing) engine;
        double[] steps = ladder == null ? bearing.defaultLadder() : ladder.clone();
        if (steps.length < 2) {
            throw new IllegalArgumentException(
                    "a sweep needs at least two threshold steps, got " + steps.length);
        }
        for (int i = 1; i < steps.length; i++) {
            if (!(steps[i] > steps[i - 1])) {
                throw new IllegalArgumentException("the ladder must increase; step "
                        + i + " is " + steps[i] + " after " + steps[i - 1]);
            }
        }

        List<EngineResult> results = new ArrayList<EngineResult>(steps.length);
        for (int i = 0; i < steps.length; i++) {
            if (reporter.isCancelled()) {
                throw new EngineCancelledException("threshold sweep cancelled");
            }
            results.add(bearing.withThreshold(steps[i])
                    .compute(inputs, EngineProgress.SILENT));
            reporter.report("threshold sweep", (i + 1.0) / steps.length);
        }

        return new Result(engine.id(), bearing.thresholdName(),
                bearing.thresholdUnit(), steps, FlipFraction.across(results), null);
    }

    /** Sweeps each engine over its own default ladder. */
    public static List<Result> of(List<ColocEngine> engines, EngineInputs inputs,
            EngineProgress progress) {
        return of(engines, inputs, DEFAULT_SWEEP_WIDTH, progress);
    }

    /**
     * Sweeps every engine over a neighbourhood of its own chosen threshold.
     *
     * @param sweepWidth how far to reach either side, as a fraction of what the
     *                   measure can be. {@code 1.0} or more sweeps the whole
     *                   range, which is the old behaviour
     */
    public static List<Result> of(List<ColocEngine> engines, EngineInputs inputs,
            double sweepWidth, EngineProgress progress) {
        List<Result> results = new ArrayList<Result>();
        for (int i = 0; i < engines.size(); i++) {
            ColocEngine engine = engines.get(i);
            double[] ladder = engine instanceof ThresholdBearing
                    ? neighbourhood((ThresholdBearing) engine, sweepWidth) : null;
            results.add(of(engine, inputs, ladder, progress));
        }
        return Collections.unmodifiableList(results);
    }

    /**
     * How far either side of the chosen threshold the sweep reaches, as a
     * fraction of the measure's own range.
     *
     * <p>0.2 means ±20% of what the measure can be: ±20 percentage points for an
     * overlap percentage, ±0.2 for a Jaccard index, ±0.4 for a correlation
     * running −1 to 1. One number, the same meaning on every method.
     *
     * <p><b>Chosen from real data, 2026-08-14.</b> Sweeping the whole range —
     * which is what this did before — asks "would the verdict change at
     * <i>any</i> cut-off", and on real segmentations the answer is almost always
     * yes: median flip fraction 0.73 across 12 directions of confocal data, and
     * 1.000 for volumetric overlap, so 83% of directions came back Fragile. A
     * classifier that calls five results in six fragile carries no information.
     * At ±20% the same fields put every direction under the 0.10 fragile cut-off
     * except the two that genuinely move. The user chose a threshold; the useful
     * question is whether a slightly different choice would have changed the
     * answer, not whether an absurd one would.
     */
    public static final double DEFAULT_SWEEP_WIDTH = 0.2;

    /**
     * A ladder of {@value #LADDER_STEPS} settings centred on the engine's chosen
     * threshold, reaching {@code sweepWidth} of the measure's range either side
     * and clamped to what the measure admits.
     *
     * <p>Clamping means a threshold near an end of the range gets a shorter but
     * still valid sweep rather than one containing settings the engine would
     * refuse. A sweep is not the place to discover that −5% is not a percentage.
     */
    public static double[] neighbourhood(ThresholdBearing engine, double sweepWidth) {
        if (engine == null) {
            throw new IllegalArgumentException("an engine is required");
        }
        if (!(sweepWidth > 0.0)) {
            throw new IllegalArgumentException("the sweep must have a width, not "
                    + sweepWidth + "; use 1.0 or more for the whole range");
        }
        double[] range = engine.thresholdRange();
        if (range == null || range.length != 2 || !(range[1] > range[0])) {
            throw new IllegalStateException(engine + " declares an unusable "
                    + "threshold range " + Arrays.toString(range));
        }
        if (sweepWidth >= 1.0) {
            return engine.defaultLadder();
        }

        double reach = (range[1] - range[0]) * sweepWidth;
        double low = Math.max(range[0], engine.threshold() - reach);
        double high = Math.min(range[1], engine.threshold() + reach);
        if (!(high > low)) {
            // A degenerate range leaves nothing to sweep. The full ladder is a
            // truthful fallback: it is what the engine itself says its settings
            // are, rather than a window invented here.
            return engine.defaultLadder();
        }

        double[] ladder = new double[LADDER_STEPS];
        for (int i = 0; i < LADDER_STEPS; i++) {
            ladder[i] = low + (high - low) * i / (LADDER_STEPS - 1.0);
        }
        // The endpoints are set exactly rather than accumulated, so the last step
        // is the clamp value and not a rounding error above it — which the
        // engine's own range check would then refuse.
        ladder[0] = low;
        ladder[LADDER_STEPS - 1] = high;
        return ladder;
    }

    /** Steps per sweep. Odd, so the chosen threshold is itself a step. */
    public static final int LADDER_STEPS = 21;
}
