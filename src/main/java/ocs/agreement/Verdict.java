package ocs.agreement;

import ocs.engine.CurveSeries;
import ocs.nullmodel.NullModelResult;

/**
 * What one method concluded from one run: more than chance, less than chance,
 * or neither.
 *
 * <p>This is the common currency of tier V. A per-object engine and a
 * cross-Ripley curve share no value a reader could compare — one is a
 * percentage per object, the other a vector over radii — but both make exactly
 * this claim, and it means the same thing coming from either.
 *
 * <p><b>Depletion is kept separate from enrichment rather than folded into
 * "significant".</b> Two methods, one finding that structures seek each other
 * and the other that they avoid each other, have made the strongest
 * disagreement available to them; scoring that as agreement because both said
 * "significant" would invert the finding.
 */
public enum Verdict {

    /** More coincidence than chance produces. */
    ENRICHED("Enriched"),

    /** Less coincidence than chance produces — avoidance, and a real finding. */
    DEPLETED("Depleted"),

    /** Indistinguishable from chance on this data. Not a failure of the method. */
    NOT_SIGNIFICANT("Not significant"),

    /** The method could not run, or ran and could not conclude. */
    NOT_APPLICABLE("Not applicable");

    /** Conventional default. Exposed as a parameter everywhere it is used. */
    public static final double DEFAULT_ALPHA = 0.05;

    private final String displayName;

    Verdict(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** Whether this verdict claims an effect of any direction. */
    public boolean isSignificant() {
        return this == ENRICHED || this == DEPLETED;
    }

    /**
     * The verdict a permutation null model reached.
     *
     * <p>Two-sided, because the direction was not decided in advance; the sign
     * then comes from the enrichment ratio rather than from which tail was
     * smaller, so a result sitting exactly at the expectation cannot be labelled
     * enriched by rounding noise in the tails.
     */
    public static Verdict from(NullModelResult result, double alpha) {
        requireAlpha(alpha);
        if (result == null || !result.ran()) {
            return NOT_APPLICABLE;
        }
        double p = result.pTwoSided();
        if (Double.isNaN(p) || p >= alpha) {
            return NOT_SIGNIFICANT;
        }
        double enrichment = result.enrichment();
        if (Double.isNaN(enrichment)) {
            // Nothing was expected by chance and something was observed. That is
            // enrichment in the only sense the data supports, and the ratio is
            // undefined rather than absent.
            return result.observed() > 0.0 ? ENRICHED : NOT_SIGNIFICANT;
        }
        if (enrichment == 1.0) {
            return NOT_SIGNIFICANT;
        }
        return enrichment > 1.0 ? ENRICHED : DEPLETED;
    }

    /**
     * The verdict a simulation-envelope engine reached.
     *
     * <p>Reads the curve's own global p and the sign of its largest departure
     * from the theoretical expectation: above the expectation is clustering
     * between the two channels, below it is segregation.
     *
     * @param globalPName the scalar carrying the curve's global p
     * @param deviationName the scalar carrying its signed maximum deviation
     */
    public static Verdict from(CurveSeries curve, double alpha,
            String globalPName, String deviationName) {
        requireAlpha(alpha);
        if (curve == null || !curve.isOk()) {
            return NOT_APPLICABLE;
        }
        Double p = curve.scalars().get(globalPName);
        Double deviation = curve.scalars().get(deviationName);
        if (p == null || deviation == null
                || Double.isNaN(p.doubleValue()) || Double.isNaN(deviation.doubleValue())) {
            return NOT_APPLICABLE;
        }
        if (p.doubleValue() >= alpha) {
            return NOT_SIGNIFICANT;
        }
        if (deviation.doubleValue() == 0.0) {
            return NOT_SIGNIFICANT;
        }
        return deviation.doubleValue() > 0.0 ? ENRICHED : DEPLETED;
    }

    private static void requireAlpha(double alpha) {
        if (!(alpha > 0.0) || !(alpha < 1.0)) {
            throw new IllegalArgumentException(
                    "alpha must lie strictly between 0 and 1, got " + alpha);
        }
    }
}
