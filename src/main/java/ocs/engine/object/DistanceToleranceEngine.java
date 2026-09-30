package ocs.engine.object;

import ij.measure.Calibration;
import ocs.engine.ColocEngine;
import ocs.engine.ColumnSpec;
import ocs.engine.DirectionKey;
import ocs.engine.EngineCancelledException;
import ocs.engine.EngineFamily;
import ocs.engine.EngineInputs;
import ocs.engine.EngineProgress;
import ocs.engine.EngineResult;
import ocs.engine.InputRequirement;
import ocs.engine.ObjectScore;
import ocs.engine.ScaleKind;
import ocs.engine.ThresholdBearing;
import sc.fiji.oc3d.core.measure.CentroidScan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Distance-tolerance colocalization — how far this object's centroid is from the
 * nearest centroid in the target channel, and whether that is within a tolerance.
 *
 * <p>The one method in the object family that reports something for objects
 * which do not touch. Every overlap-based measure collapses to zero the moment
 * two objects stop sharing voxels, and then reports zero identically for a pair
 * 100 nm apart and a pair on opposite sides of the field. In diffraction-limited
 * imaging of small puncta, "near" is often the only claim the optics support,
 * and a suite that only offered overlap measures would systematically report
 * "no colocalization" for data where proximity was the finding.
 *
 * <p>This is the <b>centroid</b> variant. Centroid distance is a poor proxy for
 * proximity once objects stop being compact: two long processes running
 * alongside each other are in contact along their whole length and have distant
 * centroids, and a large object's centroid can be far from a small object lying
 * just inside its boundary. The surface-to-surface variant is the right measure
 * for those and waits on {@code opa-core}; it is a separate engine, not a mode
 * of this one, because the two answer different questions and Discovery should
 * be free to classify them differently.
 *
 * <p><strong>Units.</strong> Distances are calibrated when the image carries a
 * calibration and are in pixels when it does not, and
 * {@link InputRequirement#CALIBRATION} is deliberately <i>not</i> required.
 * Requiring it would grey this engine out for most label images — a threshold or
 * a StarDist mask usually arrives uncalibrated — to no benefit, because a
 * distance in pixels is a perfectly good distance. What matters is that the
 * tolerance is read in the same units the distances are: a tolerance of 5 means
 * 5 µm on a micron-calibrated stack and 5 pixels on an uncalibrated one, and the
 * run record must carry the calibration so the two can never be confused after
 * the fact.
 *
 * <p><strong>Anisotropy caveat.</strong> Calibrated axes are scaled
 * independently, so an anisotropic confocal stack gives a true physical
 * distance. An <i>uncalibrated</i> stack does not: its z step is counted as one
 * unit per slice, so a distance across slices mixes pixels with slices whenever
 * the voxel is not a cube. The number is still comparable within one image and
 * is not comparable across acquisitions.
 */
public final class DistanceToleranceEngine implements ColocEngine, ThresholdBearing {

    /**
     * Five image units — five pixels uncalibrated, five microns on a
     * micron-calibrated stack.
     *
     * <p>Arbitrary in exactly the way {@code volcoloc-core}'s 30% overlap
     * threshold is arbitrary, and exposed for the same reason. There is no
     * distance at which two objects become colocalized; there is only a distance
     * a particular experiment is willing to call near. The threshold sweep
     * reports what fraction of objects change verdict across the range, which is
     * the honest answer to "does my tolerance matter here".
     */
    public static final double DEFAULT_TOLERANCE = 5.0;

    private static final ColumnSpec DISTANCE = ColumnSpec.primary(
            "Centroid Distance", "image units",
            "Distance from this object's centroid to the nearest centroid in the "
                    + "target channel; calibrated units where the image is "
                    + "calibrated, pixels where it is not",
            ScaleKind.DISTANCE);

    private static final ColumnSpec PARTNER = ColumnSpec.partner(
            "Distance Partner",
            "Label of the target object whose centroid is nearest, or 0 when the "
                    + "target channel holds no objects");

    private final double tolerance;

    /** Uses {@link #DEFAULT_TOLERANCE}. */
    public DistanceToleranceEngine() {
        this(DEFAULT_TOLERANCE);
    }

    /**
     * @param tolerance distance at or below which an object is called
     *                  colocalized, in the same units the distances are reported
     *                  in — see the class note on units
     */
    /**
     * @throws IllegalArgumentException if the tolerance is negative or not finite.
     *         There is no upper bound to check against — a tolerance is in the
     *         image's own calibrated units, so what counts as absurdly large
     *         depends on the image and not on this class.
     */
    public DistanceToleranceEngine(double tolerance) {
        if (!(tolerance >= 0.0) || Double.isInfinite(tolerance)) {
            throw new IllegalArgumentException("a centroid distance tolerance"
                    + " cannot be " + tolerance);
        }
        this.tolerance = tolerance;
    }

    @Override
    public String id() {
        return "distance-tolerance";
    }

    @Override
    public String displayName() {
        return "Distance tolerance (centroid)";
    }

    @Override
    public EngineFamily family() {
        return EngineFamily.OBJECT;
    }

    @Override
    public Set<InputRequirement> requires() {
        // CALIBRATION is not listed on purpose. See the class javadoc: the
        // measure degrades to pixels rather than becoming undefined, and greying
        // the engine out on uncalibrated data would cost far more than it saves.
        return Collections.singleton(InputRequirement.LABEL_IMAGES);
    }

    @Override
    public List<ColumnSpec> columns() {
        return Arrays.asList(DISTANCE, PARTNER);
    }

    @Override
    public boolean isSymmetric() {
        // The distance between two centroids is symmetric, but nearest-neighbour
        // assignment is not: A's nearest B need not have A as its nearest. A
        // dense channel against a sparse one is the ordinary case where the two
        // directions give different numbers for the same objects, which is
        // exactly the asymmetry a distance measure is usually assumed to be free
        // of and is not.
        return false;
    }

    @Override
    public double relativeCost() {
        // One centroid scan per channel, as centroid coincidence pays at 1.0,
        // plus a brute-force nearest search that is objects-squared per
        // direction rather than a hash lookup. Small in absolute terms next to a
        // voxel pass until object counts reach the thousands.
        return 1.2;
    }

    public double tolerance() {
        return tolerance;
    }

    @Override
    public EngineResult compute(EngineInputs inputs, EngineProgress progress) {
        if (progress.isCancelled()) {
            throw new EngineCancelledException(id());
        }
        progress.report("Distance tolerance", 0.0);

        // Scanned once per channel and reused across every direction. Rescanning
        // per direction would multiply the only real cost here by the number of
        // ordered pairs, which is twelve at five channels.
        List<CentroidScan.Result> centroids = new ArrayList<CentroidScan.Result>();
        for (int channel = 0; channel < inputs.channelCount(); channel++) {
            if (progress.isCancelled()) {
                throw new EngineCancelledException(id());
            }
            centroids.add(CentroidScan.scan(inputs.labelImages().get(channel)));
        }

        Calibration calibration = inputs.calibration();
        double scaleX = positiveOrOne(calibration.pixelWidth);
        double scaleY = positiveOrOne(calibration.pixelHeight);
        double scaleZ = positiveOrOne(calibration.pixelDepth);

        EngineResult.Builder result = EngineResult.forEngine(id());
        List<DirectionKey> directions = inputs.allDirections();
        for (int d = 0; d < directions.size(); d++) {
            if (progress.isCancelled()) {
                throw new EngineCancelledException(id());
            }
            DirectionKey direction = directions.get(d);
            List<CentroidScan.Centroid> sources =
                    centroids.get(direction.sourceIndex()).centroids();
            List<CentroidScan.Centroid> targets =
                    centroids.get(direction.targetIndex()).centroids();

            List<ObjectScore> scores = new ArrayList<ObjectScore>();
            for (CentroidScan.Centroid source : sources) {
                scores.add(nearest(source, targets, scaleX, scaleY, scaleZ));
            }
            result.direction(direction, scores);

            progress.report("Distance tolerance", (d + 1.0) / directions.size());
        }
        return result.build();
    }

    private ObjectScore nearest(CentroidScan.Centroid source,
                                List<CentroidScan.Centroid> targets,
                                double scaleX, double scaleY, double scaleZ) {
        int nearestLabel = ObjectScore.NO_PARTNER;
        double nearestDistance = Double.NaN;
        for (CentroidScan.Centroid target : targets) {
            double dx = (source.x() - target.x()) * scaleX;
            double dy = (source.y() - target.y()) * scaleY;
            double dz = (source.z() - target.z()) * scaleZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            // Ties go to the lower label, which is the ascending scan order, so
            // the partner column does not depend on iteration order.
            if (Double.isNaN(nearestDistance) || distance < nearestDistance) {
                nearestDistance = distance;
                nearestLabel = target.label();
            }
        }
        // An empty target channel leaves the distance NaN rather than infinite.
        // The generic layers skip NaN; an infinity would survive into a mean and
        // turn a whole summary row into a number no reader could interpret.
        return new ObjectScore(source.label(), nearestLabel, nearestDistance,
                nearestDistance <= tolerance);
    }

    /** An unset or nonsensical calibration means pixels, not a zero-scaled axis. */
    private static double positiveOrOne(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && value > 0.0 ? value : 1.0;
    }

    // ---------- ThresholdBearing ----------

    @Override
    public String thresholdName() {
        return "Centroid distance";
    }

    @Override
    public String thresholdUnit() {
        return "calibrated units";
    }

    @Override
    public double threshold() {
        return tolerance;
    }

    @Override
    public ColocEngine withThreshold(double threshold) {
        return new DistanceToleranceEngine(threshold);
    }

    /**
     * 0 to twice the current tolerance, in twenty-one steps.
     *
     * <p>Anchored on the user's own setting rather than on a fixed span,
     * because a distance has no absolute scale: 0-20 is a wide sweep for puncta
     * a micrometre apart and a rounding error for cells fifty apart. Sweeping a
     * fixed range would report near-zero flip fraction on one dataset and
     * near-total on another for reasons that have nothing to do with the method.
     */
    @Override
    public double[] thresholdRange() {
        // A distance has no upper bound, so the top is the widest tolerance
        // worth reporting rather than infinity: twice the chosen one, which
        // is what defaultLadder already sweeps to. The point is to size a
        // neighbourhood, not to state a mathematical domain.
        double top = tolerance > 0.0 ? tolerance * 2.0 : DEFAULT_TOLERANCE * 2.0;
        return new double[] {0.0, top};
    }

    @Override
    public double[] defaultLadder() {
        double[] ladder = new double[21];
        double top = tolerance > 0.0 ? tolerance * 2.0 : DEFAULT_TOLERANCE * 2.0;
        for (int i = 0; i < ladder.length; i++) {
            ladder[i] = top * i / (ladder.length - 1.0);
        }
        return ladder;
    }

}
