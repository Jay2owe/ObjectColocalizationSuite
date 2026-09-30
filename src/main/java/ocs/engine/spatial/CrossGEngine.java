package ocs.engine.spatial;

import sc.fiji.opa.core.spatial.EdgeCorrection;
import sc.fiji.opa.core.spatial.PatternFunction;

/**
 * Cross nearest-neighbour G — what share of source objects have a target object
 * within <i>r</i>.
 *
 * <p>The most directly readable curve of the four: G(10) = 0.8 means eight in ten
 * microglia have a plaque within ten micrometres. It rises from 0 to 1 and never
 * falls, and its expectation under independence is a known curve rather than a
 * flat line, so "above the expectation" is what association looks like.
 *
 * <p>Unlike the three K-derived engines it counts each source object once, at its
 * nearest target only. That makes it insensitive to how <i>many</i> targets are
 * nearby — one plaque or nine reads the same — which is exactly why it is worth
 * having beside cross-K rather than instead of it.
 *
 * <p><b>Direction matters more here than anywhere else in the plugin.</b> A to B
 * and B to A are different questions with different answers, and the classic
 * misreading of an object-colocalization result — "80% of microglia touch a
 * plaque" quoted as though it said something about plaques — is precisely this
 * statistic read from the wrong end.
 *
 * <h2>No edge correction, and no parameter offering one</h2>
 *
 * <p>{@code opa-core} computes cross-G with no edge correction: a source object
 * near the border may have its true nearest target outside the window, so G is
 * biased low there. {@code MonteCarloAnalyzer.analyzeBivariate} still demands an
 * {@link EdgeCorrection} argument and then ignores it for this function. This
 * engine therefore takes no correction parameter at all rather than accepting one
 * and quietly discarding it — an engine constructed with border correction that
 * silently did nothing would be worse than one that cannot be constructed that
 * way. The bias is real and is the reason to keep the largest radius well inside
 * the window, which the default radii do.
 */
public final class CrossGEngine extends SpatialCurveEngine {

    /** 99 simulations, 20 radii, {@link #DEFAULT_SEED}. */
    public CrossGEngine() {
        this(DEFAULT_SIMULATIONS, DEFAULT_SEED);
    }

    public CrossGEngine(int simulations, long seed) {
        super(PatternFunction.CROSS_G, EdgeCorrection.NONE, simulations, seed,
                DEFAULT_RADIUS_BINS, null);
    }

    /** @param radii explicit radii in calibrated units; strictly increasing */
    public CrossGEngine(int simulations, long seed, double[] radii) {
        super(PatternFunction.CROSS_G, EdgeCorrection.NONE, simulations, seed,
                DEFAULT_RADIUS_BINS, radii);
    }

    @Override
    public String id() {
        return "cross-g";
    }

    @Override
    public String displayName() {
        return "Cross nearest-neighbour G";
    }

    @Override
    protected String curveColumnName() {
        return "Cross-G";
    }

    @Override
    protected String curveDescription() {
        return "Share of source objects with at least one target object within the"
                + " radius; a cumulative distribution rising from 0 to 1";
    }

    @Override
    protected double perCurveCost() {
        // One nearest-neighbour sweep, O(nA*nB), then a count per radius over nA
        // distances. The radii do not multiply the pair loop as they do for
        // cross-K, so this is roughly a twentieth of it at the default radii.
        return 0.1;
    }
}
