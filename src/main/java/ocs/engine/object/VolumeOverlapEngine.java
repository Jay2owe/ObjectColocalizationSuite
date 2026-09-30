package ocs.engine.object;

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
import sc.fiji.volcoloc.core.DirectionalPairRunner;
import sc.fiji.volcoloc.core.OverlapParameters;
import sc.fiji.volcoloc.core.OverlapResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Volumetric overlap — what percentage of each object's voxels are shared with
 * an object in the target channel.
 *
 * <p>Wraps {@code volcoloc-core}, the engine plugin 01 ships, so results match
 * Volumetric Colocalization by construction.
 *
 * <p>Carries a threshold, so unlike {@link CentroidCoincidenceEngine} it does
 * produce a flip fraction under the threshold sweep. That threshold is the
 * arbitrary choice Discovery exists to expose: an object at 29% overlap and one
 * at 31% are nearly identical, and a 30% cutoff calls one colocalized and the
 * other not.
 */
public final class VolumeOverlapEngine implements ColocEngine, ThresholdBearing {

    private static final ColumnSpec OVERLAP_PERCENT = ColumnSpec.primary(
            "Overlap", "%",
            "Percentage of this object's voxels shared with any target object",
            ScaleKind.PERCENT);

    private static final ColumnSpec PARTNER = ColumnSpec.partner(
            "Overlap Partner",
            "Label of the target object sharing the most voxels with this one");

    private static final ColumnSpec PARTNER_COUNT = ColumnSpec.of(
            "Overlap Partner Count", "",
            "How many target objects this one overlaps at all",
            ScaleKind.COUNT);

    private static final ColumnSpec OVERLAP_VOXELS = ColumnSpec.of(
            "Overlap Voxels", "voxels",
            "Voxels of this object shared with the strongest partner",
            ScaleKind.COUNT);

    private final double thresholdPercent;

    /** Uses {@code volcoloc-core}'s default threshold. */
    public VolumeOverlapEngine() {
        this(OverlapParameters.DEFAULT_THRESHOLD_PERCENT);
    }

    /**
     * @throws IllegalArgumentException unless the setting is a percentage. Phrased
     *         as {@code !(inRange)} rather than {@code (outOfRange)} so that NaN,
     *         which compares false against everything, is rejected rather than
     *         quietly stored and turned into "nothing is ever coincident".
     */
    public VolumeOverlapEngine(double thresholdPercent) {
        if (!(thresholdPercent >= 0.0 && thresholdPercent <= 100.0)) {
            throw new IllegalArgumentException("overlap threshold is a percentage,"
                    + " so it must be between 0 and 100, not " + thresholdPercent);
        }
        this.thresholdPercent = thresholdPercent;
    }

    @Override
    public String id() {
        return "volume-overlap";
    }

    @Override
    public String displayName() {
        return "Volumetric overlap";
    }

    @Override
    public EngineFamily family() {
        return EngineFamily.OBJECT;
    }

    @Override
    public Set<InputRequirement> requires() {
        return Collections.singleton(InputRequirement.LABEL_IMAGES);
    }

    @Override
    public List<ColumnSpec> columns() {
        return Arrays.asList(OVERLAP_PERCENT, PARTNER, PARTNER_COUNT, OVERLAP_VOXELS);
    }

    @Override
    public boolean isSymmetric() {
        // The shared voxel count is symmetric; the percentage is not, because
        // the denominator is the source object's own volume. A small object
        // fully inside a large one is 100% one way and 2% the other, and that
        // asymmetry is the whole point of reporting a direction.
        return false;
    }

    @Override
    public double relativeCost() {
        // One scan of every channel building the overlap maps, then pair
        // bookkeeping. Comparable to centroid coincidence but with more
        // per-pair work.
        return 1.5;
    }

    @Override
    public EngineResult compute(EngineInputs inputs, EngineProgress progress) {
        if (progress.isCancelled()) {
            throw new EngineCancelledException(id());
        }
        progress.report("Volumetric overlap", 0.0);

        OverlapParameters parameters = OverlapParameters.builder(inputs.labelImages())
                .channelNames(inputs.channelNames())
                .thresholdPercent(thresholdPercent)
                .bidirectional(true)
                .includePartnerDetails(false)
                .includeMultiColocalization(false)
                .includeBoundingBoxOverlap(false)
                .build();

        OverlapResult overlap = DirectionalPairRunner.run(parameters);

        EngineResult.Builder result = EngineResult.forEngine(id());
        List<OverlapResult.DirectionResult> directions = overlap.getDirectionResults();
        for (int d = 0; d < directions.size(); d++) {
            if (progress.isCancelled()) {
                throw new EngineCancelledException(id());
            }
            OverlapResult.DirectionResult direction = directions.get(d);

            List<OverlapResult.ObjectResult> objects = direction.getObjects();
            List<ObjectScore> scores = new ArrayList<ObjectScore>();
            double[] partnerCounts = new double[objects.size()];
            double[] overlapVoxels = new double[objects.size()];

            for (int o = 0; o < objects.size(); o++) {
                OverlapResult.ObjectResult object = objects.get(o);
                scores.add(new ObjectScore(
                        object.getSourceLabel(),
                        object.getBestPartnerLabel(),
                        object.getOverlapPercent(),
                        object.isColocalized()));
                partnerCounts[o] = object.getPartnerCount();
                // The strongest partner's shared voxels, as the column says —
                // not the numerator of the percentage beside it. The percentage
                // counts voxels inside *any* target object, so for an object
                // straddling two targets this column is the smaller number.
                // Dividing it by the object's volume does not reproduce the
                // percentage, and a reader who tries it should find the two
                // columns described precisely enough to see why.
                overlapVoxels[o] = object.getBestPartnerOverlapVoxels();
            }

            DirectionKey key = new DirectionKey(
                    direction.getSourceIndex(), direction.getSourceChannel(),
                    direction.getTargetIndex(), direction.getTargetChannel());
            result.direction(key, scores);
            result.supporting(key, PARTNER_COUNT.name(), partnerCounts);
            result.supporting(key, OVERLAP_VOXELS.name(), overlapVoxels);

            progress.report("Volumetric overlap", (d + 1.0) / directions.size());
        }
        return result.build();
    }

    // ---------- ThresholdBearing ----------

    @Override
    public String thresholdName() {
        return "Overlap";
    }

    @Override
    public String thresholdUnit() {
        return "%";
    }

    @Override
    public double threshold() {
        return thresholdPercent;
    }

    @Override
    public ColocEngine withThreshold(double threshold) {
        return new VolumeOverlapEngine(threshold);
    }

    /** 0 to 100 in twenty-one steps of 5. */
    @Override
    public double[] thresholdRange() {
        // A percentage of the source object's own voxels.
        return new double[] {0.0, 100.0};
    }

    @Override
    public double[] defaultLadder() {
        double[] ladder = new double[21];
        for (int i = 0; i < ladder.length; i++) {
            ladder[i] = i * 5.0;
        }
        return ladder;
    }

}
