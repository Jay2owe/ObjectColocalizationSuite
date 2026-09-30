package ocs.engine.spatial;

import sc.fiji.opa.core.spatial.EdgeCorrection;
import sc.fiji.opa.core.spatial.PatternFunction;

/**
 * Cross pair correlation g(r) — how much more often a target object sits at
 * distance <i>r</i> from a source object than independence predicts.
 *
 * <p>Where cross-K counts everything inside a circle, this counts only what lies
 * in the thin ring at <i>r</i>. That difference matters: cross-K at 30 µm still
 * carries every pair at 5 µm, so a strong short-range association keeps the curve
 * high long after the association has stopped. g(r) does not accumulate, so it
 * returns to 1 as soon as the effect ends and the curve reads directly as "at
 * this separation, this much more than chance".
 *
 * <p>Its expectation is 1 at every radius, whatever the densities — the flattest
 * possible reference line, and the reason this is the curve to show someone who
 * has not seen Ripley's K before.
 *
 * <p>The price is variance. Each value comes from one annulus rather than a whole
 * disc, so with few objects the curve is noisy and the envelope is wide. Sparse
 * data is where cross-K is the safer read.
 *
 * <h2>Border correction is refused, not substituted</h2>
 *
 * <p>g(r) is built from differences of cross-K between consecutive radii. Border
 * correction changes which source objects are eligible as the radius grows, so
 * K's risk set shrinks with <i>r</i> and a difference between two radii can come
 * out negative — a negative pair correlation, which has no meaning and would plot
 * as a real feature. {@code opa-core} refuses the combination and so does this
 * constructor, at construction time rather than mid-run: a batch must not fail an
 * hour in on a setting that was unusable before it started. Nothing here quietly
 * swaps in translation correction on the user's behalf; a run must not report a
 * correction the user did not choose.
 */
public final class CrossPairCorrelationEngine extends SpatialCurveEngine {

    /** Translation correction, 99 simulations, 20 radii, {@link #DEFAULT_SEED}. */
    public CrossPairCorrelationEngine() {
        this(EdgeCorrection.TRANSLATION, DEFAULT_SIMULATIONS, DEFAULT_SEED);
    }

    public CrossPairCorrelationEngine(EdgeCorrection correction, int simulations,
                                      long seed) {
        super(PatternFunction.CROSS_PAIR_CORRELATION, requireUsable(correction),
                simulations, seed, DEFAULT_RADIUS_BINS, null);
    }

    /** @param radii explicit radii in calibrated units; strictly increasing */
    public CrossPairCorrelationEngine(EdgeCorrection correction, int simulations,
                                      long seed, double[] radii) {
        super(PatternFunction.CROSS_PAIR_CORRELATION, requireUsable(correction),
                simulations, seed, DEFAULT_RADIUS_BINS, radii);
    }

    @Override
    public String id() {
        return "cross-pair-correlation";
    }

    @Override
    public String displayName() {
        return "Cross pair correlation g(r)";
    }

    @Override
    protected String curveColumnName() {
        return "Cross-g";
    }

    @Override
    protected String curveDescription() {
        return "Target objects in the ring at this radius from a source object,"
                + " relative to independence; expected to equal 1 at every radius";
    }

    @Override
    protected double perCurveCost() {
        // Cross-K, then one subtraction per radius.
        return 1.0;
    }

    /**
     * Rejects border correction with the reason, and does not choose another.
     *
     * <p>The wording deliberately echoes {@code opa-core}'s own rejection so a
     * user who meets it through either route reads the same explanation and the
     * same remedy.
     */
    private static EdgeCorrection requireUsable(EdgeCorrection correction) {
        if (correction == EdgeCorrection.BORDER) {
            throw new IllegalArgumentException("Cross pair correlation cannot use "
                    + "border correction because the radius-dependent risk set can "
                    + "make K increments negative; use translation or no edge "
                    + "correction.");
        }
        return correction;
    }
}
