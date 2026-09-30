package ocs.engine.spatial;

import sc.fiji.opa.core.spatial.EdgeCorrection;
import sc.fiji.opa.core.spatial.PatternFunction;

/**
 * Cross-Ripley L — cross-K with the radius scaled out of it.
 *
 * <p>L(r) = sqrt(K(r)/π), so under independence it is simply <i>r</i>: a straight
 * diagonal instead of cross-K's parabola. That is the entire reason it exists.
 * Cross-K's values grow with r², so a plot of it is dominated by the largest radii
 * and a real departure at 5 µm is invisible beside the scale at 50 µm. L puts
 * every radius on the same footing, in the same units as the radius itself.
 *
 * <p>It is a transform of the same numbers, not a second opinion. Where cross-K
 * clears the null, cross-L will too — running both buys a readable plot, not
 * independent evidence, and the agreement layer would be wrong to treat them as
 * two methods agreeing.
 */
public final class CrossLEngine extends SpatialCurveEngine {

    /** Translation correction, 99 simulations, 20 radii, {@link #DEFAULT_SEED}. */
    public CrossLEngine() {
        this(EdgeCorrection.TRANSLATION, DEFAULT_SIMULATIONS, DEFAULT_SEED);
    }

    public CrossLEngine(EdgeCorrection correction, int simulations, long seed) {
        super(PatternFunction.CROSS_L, correction, simulations, seed,
                DEFAULT_RADIUS_BINS, null);
    }

    /** @param radii explicit radii in calibrated units; strictly increasing */
    public CrossLEngine(EdgeCorrection correction, int simulations, long seed,
                        double[] radii) {
        super(PatternFunction.CROSS_L, correction, simulations, seed,
                DEFAULT_RADIUS_BINS, radii);
    }

    @Override
    public String id() {
        return "cross-l";
    }

    @Override
    public String displayName() {
        return "Cross-Ripley L";
    }

    @Override
    protected String curveColumnName() {
        return "Cross-L";
    }

    @Override
    protected String curveDescription() {
        return "Variance-stabilised cross-K, sqrt(K/pi); a distance, expected to equal"
                + " the radius itself under independence";
    }

    @Override
    protected double perCurveCost() {
        // Cross-K plus one square root per radius, which is nothing beside the
        // pair loop.
        return 1.0;
    }
}
