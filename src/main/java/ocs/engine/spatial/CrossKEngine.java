package ocs.engine.spatial;

import sc.fiji.opa.core.spatial.EdgeCorrection;
import sc.fiji.opa.core.spatial.PatternFunction;

/**
 * Cross-Ripley K — how many target objects sit within <i>r</i> of a source
 * object, against how many chance would put there.
 *
 * <p>Read it as: draw a circle of radius <i>r</i> round every object of channel A,
 * count the channel-B objects inside, and divide by the density of B. Under
 * independence that comes to the circle's own area, πr², whatever the two
 * densities are. Above it means B clusters around A at that distance; below means
 * B avoids A.
 *
 * <p>The whole curve is what carries the finding, not any single radius. Two
 * channels can be indistinguishable at 5 µm and strongly associated at 30 µm, and
 * that shape — the radius at which association appears — is the thing an overlap
 * percentage cannot express at all.
 */
public final class CrossKEngine extends SpatialCurveEngine {

    /** Translation correction, 99 simulations, 20 radii, {@link #DEFAULT_SEED}. */
    public CrossKEngine() {
        this(EdgeCorrection.TRANSLATION, DEFAULT_SIMULATIONS, DEFAULT_SEED);
    }

    public CrossKEngine(EdgeCorrection correction, int simulations, long seed) {
        super(PatternFunction.CROSS_K, correction, simulations, seed,
                DEFAULT_RADIUS_BINS, null);
    }

    /** @param radii explicit radii in calibrated units; strictly increasing */
    public CrossKEngine(EdgeCorrection correction, int simulations, long seed,
                        double[] radii) {
        super(PatternFunction.CROSS_K, correction, simulations, seed,
                DEFAULT_RADIUS_BINS, radii);
    }

    @Override
    public String id() {
        return "cross-k";
    }

    @Override
    public String displayName() {
        return "Cross-Ripley K";
    }

    @Override
    protected String curveColumnName() {
        return "Cross-K";
    }

    @Override
    protected String curveDescription() {
        return "Target objects within a radius of a source object, divided by target"
                + " density; an area, expected to equal pi*r*r under independence";
    }

    @Override
    protected double perCurveCost() {
        // Every source-target pair, at every radius: O(nA*nB*radii). Nominal 1.0
        // is a few hundred objects a channel at twenty radii, comparable to one
        // pass over a megapixel image.
        return 1.0;
    }
}
