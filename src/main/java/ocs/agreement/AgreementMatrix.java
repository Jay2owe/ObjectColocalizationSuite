package ocs.agreement;

import ocs.engine.ColocEngine;
import ocs.engine.ColumnSpec;
import ocs.engine.DirectionKey;
import ocs.engine.EngineResult;
import ocs.engine.ObjectScore;
import ocs.engine.ScaleKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the methods concur and where they do not.
 *
 * <p>This is the plugin's central claim made checkable. Running eleven methods
 * and printing eleven numbers tells a user nothing about which to believe;
 * showing that eight of them classify the same objects the same way, and that
 * the ninth systematically disagrees, tells them where to look.
 *
 * <h2>Two entry points, and why</h2>
 *
 * <p>{@link #overObjects} compares methods object by object within one image —
 * three tiers, gated by how commensurable the two methods' numbers really are.
 * {@link VerdictAgreement} compares whole-run verdicts across a batch, and is the
 * only route open to an engine that produces no per-object score at all. The
 * four spatial engines answer with a curve over radii, so the object tiers see
 * <i>n</i> = 0 for them against every other method; without the second route a
 * quarter of the suite would be structurally excluded from the comparison the
 * suite exists to make.
 *
 * <h2>Complete-case, pairwise, per direction</h2>
 *
 * <p>Each cell compares only the objects <i>both</i> methods scored, so each
 * carries its own <i>n</i>. Dropping an object from one comparison does not
 * shrink any other. A→B and B→A are compared separately and never pooled —
 * pooling averages away the asymmetry that object colocalization mostly
 * consists of.
 */
public final class AgreementMatrix {

    /** Statistic names. By name, never by position — see {@link AgreementCell}. */
    public static final String RAW_AGREEMENT = "Raw Agreement";
    public static final String KAPPA = "Cohen's Kappa";
    public static final String PREVALENCE_A = "Prevalence A";
    public static final String PREVALENCE_B = "Prevalence B";
    public static final String SPEARMAN = "Spearman Rho";
    public static final String CONCORDANCE = "Lin's CCC";
    public static final String BIAS = "Bland-Altman Bias";
    public static final String LOWER_LIMIT = "Lower Limit Of Agreement";
    public static final String UPPER_LIMIT = "Upper Limit Of Agreement";

    private final int minimumN;

    private AgreementMatrix(int minimumN) {
        this.minimumN = minimumN;
    }

    public static AgreementMatrix withDefaults() {
        return new AgreementMatrix(AgreementCell.DEFAULT_MINIMUM_N);
    }

    /**
     * @param minimumN below which cells are marked underpowered. A parameter and
     *        not a constant, because it is a judgement call and burying a
     *        judgement call in a constant is how a tool acquires an opinion
     *        nobody agreed to.
     */
    public static AgreementMatrix withMinimumN(int minimumN) {
        if (minimumN < 0) {
            throw new IllegalArgumentException("minimum n cannot be negative");
        }
        return new AgreementMatrix(minimumN);
    }

    /**
     * Every unordered method pair, for every direction both reported.
     *
     * <p>Engines producing no object scores are not silently absent: they get a
     * cell saying so, because a matrix rendered from present cells only makes
     * "excluded" and "compared and found nothing" look identical.
     *
     * @param engines the engines that produced {@code results}, keyed by id —
     *        needed for the declared {@code ScaleKind} that gates tier 3, and
     *        for the symmetry flag
     */
    public List<AgreementCell> overObjects(List<EngineResult> results,
            Map<String, ColocEngine> engines) {
        if (results == null || engines == null) {
            throw new IllegalArgumentException("results and engines are required");
        }
        List<AgreementCell> cells = new ArrayList<AgreementCell>();
        for (int i = 0; i < results.size(); i++) {
            for (int j = i + 1; j < results.size(); j++) {
                cells.addAll(pair(results.get(i), results.get(j), engines));
            }
        }
        return Collections.unmodifiableList(cells);
    }

    private List<AgreementCell> pair(EngineResult a, EngineResult b,
            Map<String, ColocEngine> engines) {
        List<AgreementCell> cells = new ArrayList<AgreementCell>();
        ColocEngine engineA = engines.get(a.engineId());
        ColocEngine engineB = engines.get(b.engineId());
        if (engineA == null || engineB == null) {
            throw new IllegalArgumentException("no engine registered for '"
                    + (engineA == null ? a.engineId() : b.engineId()) + "'");
        }

        if (!a.hasObjectScores() || !b.hasObjectScores()) {
            String culprit = !a.hasObjectScores() ? a.engineId() : b.engineId();
            cells.add(notComputed(Tier.OBJECT_FLAG, a, b, null,
                    culprit + " reports no per-object score, so the object tiers "
                            + "have nothing to pair; compare it at tier V instead"));
            return cells;
        }

        boolean symmetricPair = engineA.isSymmetric() && engineB.isSymmetric();
        for (DirectionKey direction : a.directions()) {
            if (!b.directions().contains(direction)) {
                continue;
            }
            Paired paired = pairByLabel(a.scores(direction), b.scores(direction));
            if (paired.size() == 0) {
                cells.add(notComputed(Tier.OBJECT_FLAG, a, b, direction,
                        "no object was scored by both methods"));
                continue;
            }
            boolean weak = paired.size() < minimumN;

            CohensKappa.Result kappa = CohensKappa.of(paired.flagsA, paired.flagsB);
            Map<String, Double> tier1 = new LinkedHashMap<String, Double>();
            tier1.put(RAW_AGREEMENT, Double.valueOf(kappa.rawAgreement()));
            tier1.put(KAPPA, Double.valueOf(kappa.kappa()));
            tier1.put(PREVALENCE_A, Double.valueOf(kappa.prevalenceA()));
            tier1.put(PREVALENCE_B, Double.valueOf(kappa.prevalenceB()));
            cells.add(new AgreementCell(Tier.OBJECT_FLAG, a.engineId(), b.engineId(),
                    direction, paired.size(), tier1, weak, symmetricPair, null));

            Map<String, Double> tier2 = new LinkedHashMap<String, Double>();
            tier2.put(SPEARMAN, Double.valueOf(
                    SpearmanRho.of(paired.valuesA, paired.valuesB)));
            cells.add(new AgreementCell(Tier.OBJECT_RANK, a.engineId(), b.engineId(),
                    direction, paired.size(), tier2, weak, symmetricPair, null));

            ScaleKind scaleA = primaryScale(engineA);
            ScaleKind scaleB = primaryScale(engineB);
            if (!scaleA.isComparableWith(scaleB)) {
                cells.add(notComputed(Tier.OBJECT_VALUE, a, b, direction,
                        "not directly comparable: " + a.engineId() + " is "
                                + scaleA.displayName() + ", " + b.engineId() + " is "
                                + scaleB.displayName()));
                continue;
            }
            DirectValueAgreement.Result direct =
                    DirectValueAgreement.of(paired.valuesA, paired.valuesB);
            Map<String, Double> tier3 = new LinkedHashMap<String, Double>();
            tier3.put(CONCORDANCE, Double.valueOf(direct.concordance()));
            tier3.put(BIAS, Double.valueOf(direct.bias()));
            tier3.put(LOWER_LIMIT, Double.valueOf(direct.lowerLimit()));
            tier3.put(UPPER_LIMIT, Double.valueOf(direct.upperLimit()));
            cells.add(new AgreementCell(Tier.OBJECT_VALUE, a.engineId(), b.engineId(),
                    direction, paired.size(), tier3, weak, symmetricPair, null));
        }
        return cells;
    }

    private AgreementCell notComputed(Tier tier, EngineResult a, EngineResult b,
            DirectionKey direction, String reason) {
        return new AgreementCell(tier, a.engineId(), b.engineId(), direction, 0,
                new LinkedHashMap<String, Double>(), true, false, reason);
    }

    private static ScaleKind primaryScale(ColocEngine engine) {
        for (ColumnSpec column : engine.columns()) {
            if (column.isPrimary()) {
                return column.scale();
            }
        }
        throw new IllegalStateException(engine.id() + " declares no primary column");
    }

    /**
     * Objects scored by both methods, matched on the source label.
     *
     * <p>Matched by label rather than by list position. Two engines can report
     * different objects — one may drop an object as too small to measure — and
     * position-matching would then compare object 7's value against object 8's
     * for the rest of the direction, quietly, with every statistic still
     * looking plausible.
     */
    private static Paired pairByLabel(List<ObjectScore> a, List<ObjectScore> b) {
        Map<Integer, ObjectScore> byLabel = new LinkedHashMap<Integer, ObjectScore>();
        for (int i = 0; i < b.size(); i++) {
            byLabel.put(Integer.valueOf(b.get(i).sourceLabel()), b.get(i));
        }
        List<Double> valuesA = new ArrayList<Double>();
        List<Double> valuesB = new ArrayList<Double>();
        List<Boolean> flagsA = new ArrayList<Boolean>();
        List<Boolean> flagsB = new ArrayList<Boolean>();
        for (int i = 0; i < a.size(); i++) {
            ObjectScore left = a.get(i);
            ObjectScore right = byLabel.get(Integer.valueOf(left.sourceLabel()));
            if (right == null) {
                continue;
            }
            // Complete-case: an object one method could not measure is dropped
            // from this pair's comparison and from no other.
            if (Double.isNaN(left.value()) || Double.isNaN(right.value())) {
                continue;
            }
            valuesA.add(Double.valueOf(left.value()));
            valuesB.add(Double.valueOf(right.value()));
            flagsA.add(Boolean.valueOf(left.isCoincident()));
            flagsB.add(Boolean.valueOf(right.isCoincident()));
        }
        return new Paired(valuesA, valuesB, flagsA, flagsB);
    }

    private static final class Paired {
        private final double[] valuesA;
        private final double[] valuesB;
        private final boolean[] flagsA;
        private final boolean[] flagsB;

        private Paired(List<Double> valuesA, List<Double> valuesB,
                List<Boolean> flagsA, List<Boolean> flagsB) {
            this.valuesA = toDoubles(valuesA);
            this.valuesB = toDoubles(valuesB);
            this.flagsA = toBooleans(flagsA);
            this.flagsB = toBooleans(flagsB);
        }

        private int size() {
            return valuesA.length;
        }

        private static double[] toDoubles(List<Double> values) {
            double[] array = new double[values.size()];
            for (int i = 0; i < array.length; i++) {
                array[i] = values.get(i).doubleValue();
            }
            return array;
        }

        private static boolean[] toBooleans(List<Boolean> values) {
            boolean[] array = new boolean[values.size()];
            for (int i = 0; i < array.length; i++) {
                array[i] = values.get(i).booleanValue();
            }
            return array;
        }
    }
}
