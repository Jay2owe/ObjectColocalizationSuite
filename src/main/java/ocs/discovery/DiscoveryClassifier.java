package ocs.discovery;

import ocs.agreement.AgreementCell;
import ocs.agreement.AgreementMatrix;
import ocs.agreement.Tier;
import ocs.agreement.Verdict;
import ocs.agreement.VerdictAgreement;
import ocs.engine.ColocEngine;
import ocs.engine.EngineFamily;
import ocs.sweep.FlipFraction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which of these methods actually told you something?
 *
 * <p>This is the plugin's headline question, answered. Eleven methods produce
 * eleven numbers; on any given dataset some of those numbers are findings, some
 * are noise dressed as findings, and some are artefacts of a threshold. The
 * classifier puts each method in one of five classes with the evidence attached,
 * and stops there.
 *
 * <p><b>It emits no score and no ranking</b>, structurally — there is no field
 * to sort on. See {@link DiscoveryClass} for why: agreeing with the majority is
 * not being right, the methods are not independent enough for a consensus to
 * mean much, and any weighting would be arbitrary while looking authoritative.
 *
 * <h2>The order the rules fire in, and why</h2>
 *
 * <ol>
 *   <li><b>Not applicable</b> — inputs absent, or too few objects. Asked first
 *       because everything below it is a claim about a measurement, and there
 *       isn't one.
 *   <li><b>Uninformative here</b> — did not clear the null. Before stability,
 *       because the flip fraction of a result that is indistinguishable from
 *       chance is not interesting: it describes how an uninformative answer
 *       moves.
 *   <li><b>Fragile</b> — cleared the null, but the classification moves with the
 *       threshold. Before divergence, because a method whose own answer is
 *       unstable cannot support the stronger claim that everyone else is wrong.
 *   <li><b>Divergent</b> — cleared the null, stable, and agrees with nothing in
 *       any other family.
 *   <li><b>Usable</b> — everything else.
 * </ol>
 *
 * <p>Every threshold here is a constructor parameter with a documented default,
 * never a buried constant.
 */
public final class DiscoveryClassifier {

    /** Below this kappa, two methods are treated as not agreeing. */
    public static final double DEFAULT_KAPPA_LIMIT = 0.40;

    private final double flipLimit;
    private final int minimumObjects;
    private final double kappaLimit;

    private DiscoveryClassifier(double flipLimit, int minimumObjects, double kappaLimit) {
        this.flipLimit = flipLimit;
        this.minimumObjects = minimumObjects;
        this.kappaLimit = kappaLimit;
    }

    /**
     * Flip fraction 0.10, minimum 30 objects, kappa 0.40.
     *
     * <p>All three are judgement calls with no empirical basis in this data, and
     * all three are exposed so a user can disagree with them explicitly rather
     * than discovering later that the tool held an opinion.
     */
    public static DiscoveryClassifier withDefaults() {
        return new DiscoveryClassifier(FlipFraction.DEFAULT_FRAGILE_ABOVE,
                AgreementCell.DEFAULT_MINIMUM_N, DEFAULT_KAPPA_LIMIT);
    }

    public static DiscoveryClassifier with(double flipLimit, int minimumObjects,
            double kappaLimit) {
        if (!(flipLimit >= 0.0) || !(flipLimit <= 1.0)) {
            throw new IllegalArgumentException(
                    "the flip limit is a proportion of objects, got " + flipLimit);
        }
        if (minimumObjects < 0) {
            throw new IllegalArgumentException("minimum objects cannot be negative");
        }
        if (!(kappaLimit >= -1.0) || !(kappaLimit <= 1.0)) {
            throw new IllegalArgumentException(
                    "kappa lies on [-1, 1], got " + kappaLimit);
        }
        return new DiscoveryClassifier(flipLimit, minimumObjects, kappaLimit);
    }

    /**
     * Classifies each piece of evidence, in the order given.
     *
     * <p>Input order is preserved rather than grouped or sorted by class. Any
     * ordering this returned would be read as a ranking, and there isn't one.
     *
     * @param engines keyed by id, for the family each belongs to — divergence is
     *        computed against <i>other families</i>, never against everything
     * @param agreement cells from {@link AgreementMatrix} and
     *        {@link VerdictAgreement}; may be empty, in which case no method can
     *        be called divergent because there is nothing to diverge from
     */
    public List<DiscoveryResult> classify(List<MethodEvidence> evidence,
            Map<String, ColocEngine> engines, List<AgreementCell> agreement) {
        if (evidence == null || engines == null) {
            throw new IllegalArgumentException("evidence and engines are required");
        }
        List<AgreementCell> cells = agreement == null
                ? new ArrayList<AgreementCell>() : agreement;

        List<DiscoveryResult> results = new ArrayList<DiscoveryResult>();
        for (int i = 0; i < evidence.size(); i++) {
            results.add(classifyOne(evidence.get(i), engines, cells));
        }
        return Collections.unmodifiableList(results);
    }

    private DiscoveryResult classifyOne(MethodEvidence evidence,
            Map<String, ColocEngine> engines, List<AgreementCell> cells) {
        List<String> reasons = new ArrayList<String>();
        List<String> divergentFrom = new ArrayList<String>();

        if (!evidence.inputsSatisfied()) {
            reasons.add(evidence.unavailableReason());
            return new DiscoveryResult(evidence.engineId(), evidence.direction(),
                    DiscoveryClass.NOT_APPLICABLE, reasons, divergentFrom);
        }
        if (evidence.verdict() == Verdict.NOT_APPLICABLE) {
            reasons.add("the method produced no conclusion for this direction");
            return new DiscoveryResult(evidence.engineId(), evidence.direction(),
                    DiscoveryClass.NOT_APPLICABLE, reasons, divergentFrom);
        }
        if (evidence.objects() < minimumObjects) {
            reasons.add("only " + evidence.objects() + " objects, below the minimum of "
                    + minimumObjects + " needed to judge");
            return new DiscoveryResult(evidence.engineId(), evidence.direction(),
                    DiscoveryClass.NOT_APPLICABLE, reasons, divergentFrom);
        }

        if (!evidence.verdict().isSignificant()) {
            reasons.add("did not clear the null model on this dataset");
            reasons.add("this is the method working, not failing — there may be "
                    + "nothing here for it to find");
            return new DiscoveryResult(evidence.engineId(), evidence.direction(),
                    DiscoveryClass.UNINFORMATIVE_HERE, reasons, divergentFrom);
        }
        reasons.add("cleared the null model: " + evidence.verdict().displayName().toLowerCase()
                + " relative to chance, over " + evidence.objects() + " objects");

        if (evidence.hasThreshold() && evidence.flipFraction() > flipLimit) {
            reasons.add(percent(evidence.flipFraction()) + " of objects change "
                    + "classification across the threshold range, above the "
                    + percent(flipLimit) + " limit");
            return new DiscoveryResult(evidence.engineId(), evidence.direction(),
                    DiscoveryClass.FRAGILE, reasons, divergentFrom);
        }
        if (evidence.hasThreshold()) {
            reasons.add("stable: only " + percent(evidence.flipFraction())
                    + " of objects change classification across the threshold range");
        } else {
            reasons.add("no threshold to be sensitive to");
        }

        divergentFrom.addAll(familiesItAgreesWithNothingIn(evidence, engines, cells));
        if (!divergentFrom.isEmpty()) {
            reasons.add("agrees with no method in: " + join(divergentFrom));
            reasons.add("this has two possible causes and the classifier does not "
                    + "distinguish them: the method may be wrong, or it may be the "
                    + "only one here capturing the effect");
            return new DiscoveryResult(evidence.engineId(), evidence.direction(),
                    DiscoveryClass.DIVERGENT, reasons, divergentFrom);
        }

        return new DiscoveryResult(evidence.engineId(), evidence.direction(),
                DiscoveryClass.USABLE, reasons, divergentFrom);
    }

    /**
     * Families where every comparable method disagrees with this one.
     *
     * <p>Computed against other families only. A distance-based method
     * <i>should</i> disagree with overlap-based methods on puncta that are close
     * but not touching, and a method that disagreed with its own near-duplicates
     * would be flagged for a difference nobody would call divergence.
     *
     * <p>A family with no comparable cell is not counted. "We could not compare"
     * and "we compared and they disagreed" are different findings, and only the
     * second is evidence of divergence.
     */
    private Set<String> familiesItAgreesWithNothingIn(MethodEvidence evidence,
            Map<String, ColocEngine> engines, List<AgreementCell> cells) {
        Set<String> diverges = new LinkedHashSet<String>();
        ColocEngine self = engines.get(evidence.engineId());
        if (self == null) {
            return diverges;
        }

        for (EngineFamily family : EngineFamily.values()) {
            if (family == self.family()) {
                continue;
            }
            int compared = 0;
            int agreed = 0;
            for (int i = 0; i < cells.size(); i++) {
                AgreementCell cell = cells.get(i);
                if (!cell.wasComputed() || !involves(cell, evidence.engineId())) {
                    continue;
                }
                // Tier V carries no direction because it is a batch statistic, so
                // it matches any direction; the object tiers must match ours.
                if (cell.direction() != null && evidence.direction() != null
                        && !cell.direction().equals(evidence.direction())) {
                    continue;
                }
                if (cell.tier() != Tier.OBJECT_FLAG && cell.tier() != Tier.VERDICT) {
                    continue;
                }
                ColocEngine other = engines.get(otherIdIn(cell, evidence.engineId()));
                if (other == null || other.family() != family) {
                    continue;
                }
                Double kappa = cell.statistics().get(
                        cell.tier() == Tier.VERDICT
                                ? VerdictAgreement.KAPPA : AgreementMatrix.KAPPA);
                if (kappa == null || Double.isNaN(kappa.doubleValue())) {
                    continue;
                }
                compared++;
                if (kappa.doubleValue() >= kappaLimit) {
                    agreed++;
                }
            }
            if (compared > 0 && agreed == 0) {
                diverges.add(family.displayName());
            }
        }
        return diverges;
    }

    private static boolean involves(AgreementCell cell, String engineId) {
        return cell.engineA().equals(engineId) || cell.engineB().equals(engineId);
    }

    private static String otherIdIn(AgreementCell cell, String engineId) {
        return cell.engineA().equals(engineId) ? cell.engineB() : cell.engineA();
    }

    private static String percent(double fraction) {
        return Math.round(fraction * 100.0) + "%";
    }

    private static String join(List<String> values) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                joined.append(i == values.size() - 1 ? " and " : ", ");
            }
            joined.append(values.get(i));
        }
        return joined.toString();
    }
}
