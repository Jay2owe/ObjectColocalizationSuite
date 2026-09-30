package ocs.discovery;

import ocs.agreement.Verdict;
import ocs.engine.DirectionKey;
import ocs.nullmodel.NullModelResult;
import ocs.sweep.FlipFraction;
import ocs.sweep.ThresholdSweep;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything Discovery knows about one method on one direction, gathered before
 * any judgement is made.
 *
 * <p>A deliberate seam. Discovery consumes this rather than reaching into the
 * null model and the sweep itself, so the classifier can be tested on evidence
 * constructed by hand — which is the only way to be sure a rule fires for the
 * reason it claims rather than because some upstream fixture happened to
 * produce the right shape.
 *
 * <p>It also lets an engine that carries its own null model — the four spatial
 * ones, whose simulation envelope <i>is</i> a null model — arrive here on the
 * same footing as one that was permuted. By this point both have become a
 * {@link Verdict}, and Discovery never learns which route it took.
 */
public final class MethodEvidence {

    private final String engineId;
    private final DirectionKey direction;
    private final Verdict verdict;
    private final int objects;
    private final double flipFraction;
    private final boolean inputsSatisfied;
    private final String unavailableReason;

    private MethodEvidence(String engineId, DirectionKey direction, Verdict verdict,
            int objects, double flipFraction, boolean inputsSatisfied,
            String unavailableReason) {
        this.engineId = engineId;
        this.direction = direction;
        this.verdict = verdict;
        this.objects = objects;
        this.flipFraction = flipFraction;
        this.inputsSatisfied = inputsSatisfied;
        this.unavailableReason = unavailableReason;
    }

    /**
     * @param flipFraction NaN where the method has no threshold to sweep.
     *        <b>NaN, not zero.</b> Zero would say "swept, and perfectly stable";
     *        NaN says "there was no cut-off to be sensitive to", which is a
     *        different statement and a point in the method's favour.
     */
    public static MethodEvidence of(String engineId, DirectionKey direction,
            Verdict verdict, int objects, double flipFraction) {
        if (engineId == null || verdict == null) {
            throw new IllegalArgumentException("engine id and verdict are required");
        }
        if (objects < 0) {
            throw new IllegalArgumentException("object count cannot be negative");
        }
        return new MethodEvidence(engineId, direction, verdict, objects,
                flipFraction, true, null);
    }

    /** A method whose inputs were not present at all. */
    public static MethodEvidence unavailable(String engineId, DirectionKey direction,
            String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "an unavailable method must say why; a blank cell is not a reason");
        }
        return new MethodEvidence(engineId, direction, Verdict.NOT_APPLICABLE, 0,
                Double.NaN, false, reason);
    }

    public String engineId() {
        return engineId;
    }

    public DirectionKey direction() {
        return direction;
    }

    public Verdict verdict() {
        return verdict;
    }

    /** Objects the method scored in this direction. */
    public int objects() {
        return objects;
    }

    /** NaN where there was no threshold to sweep. */
    public double flipFraction() {
        return flipFraction;
    }

    public boolean hasThreshold() {
        return !Double.isNaN(flipFraction);
    }

    public boolean inputsSatisfied() {
        return inputsSatisfied;
    }

    /** Why the method could not run, or null if it did. */
    public String unavailableReason() {
        return unavailableReason;
    }

    /**
     * Assembles evidence from a completed run.
     *
     * <p>Every null-model result becomes one row. A sweep is matched to it by
     * engine id and direction; a method with no sweep — or one that reported no
     * threshold — carries NaN rather than a fabricated zero.
     *
     * @param alpha the significance level. A parameter rather than a constant,
     *        because it is a judgement call, and burying a judgement call in a
     *        constant is how a tool acquires an opinion nobody agreed to.
     */
    public static List<MethodEvidence> gather(List<NullModelResult> nullModels,
            List<ThresholdSweep.Result> sweeps, double alpha) {
        if (nullModels == null) {
            throw new IllegalArgumentException("null-model results are required");
        }
        List<MethodEvidence> evidence = new ArrayList<MethodEvidence>();
        for (int i = 0; i < nullModels.size(); i++) {
            NullModelResult nullModel = nullModels.get(i);
            evidence.add(of(nullModel.engineId(), nullModel.direction(),
                    Verdict.from(nullModel, alpha),
                    (int) Math.round(nullModel.observed()),
                    flipFor(sweeps, nullModel.engineId(), nullModel.direction())));
        }
        return evidence;
    }

    private static double flipFor(List<ThresholdSweep.Result> sweeps,
            String engineId, DirectionKey direction) {
        if (sweeps == null) {
            return Double.NaN;
        }
        for (int i = 0; i < sweeps.size(); i++) {
            ThresholdSweep.Result sweep = sweeps.get(i);
            if (!sweep.engineId().equals(engineId) || !sweep.wasSwept()) {
                continue;
            }
            List<FlipFraction> fractions = sweep.flipFractions();
            for (int f = 0; f < fractions.size(); f++) {
                if (fractions.get(f).direction().equals(direction)) {
                    return fractions.get(f).value();
                }
            }
        }
        return Double.NaN;
    }

    @Override
    public String toString() {
        return engineId + " " + (direction == null ? "-" : direction.label())
                + " " + verdict + " n=" + objects + " flip=" + flipFraction;
    }
}
