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
 * Bounding-box overlap — what percentage of this object's axis-aligned bounding
 * box is shared with the box of a target object.
 *
 * <p>Wraps the bounding-box family {@code volcoloc-core} already ships, so the
 * numbers match Volumetric Colocalization's BBColoc column by construction.
 *
 * <p><strong>This engine over-calls, and that is why it is here.</strong> A box
 * is a coarse stand-in for a shape. Two objects that share no voxel at all can
 * have identical bounding boxes: an elongated object running one diagonal of a
 * field and another running the opposite diagonal touch nowhere and yet report
 * 100% box overlap. Anything long, thin, curved, branched or diagonal — which is
 * most of neurobiology, from processes to vessels to dendritic spines on a
 * shaft — inflates the same way. Compact, roughly isotropic puncta are the one
 * case where the box is a fair proxy.
 *
 * <p>Discovery will classify this engine against the voxel-exact engines
 * ({@link VolumeOverlapEngine}, {@link JaccardDiceEngine}) and will frequently
 * find it Divergent. A reader needs to know that the divergence has a known
 * geometric cause and is not evidence that the voxel-exact engines are wrong —
 * which is precisely the reverse of how Divergent is read for a distance-based
 * method. Reported so the inflation can be measured on the user's own data
 * rather than assumed away.
 *
 * <p><strong>Cost note.</strong> The bounding-box pass is reached through
 * {@code DirectionalPairRunner}, which runs the voxel-exact
 * {@code VolumeOverlap.scan()} in its constructor whether or not the caller
 * wants it, then walks every voxel again to accumulate per-label boxes. Through
 * this API the "cheap screen" therefore costs strictly more than the exact
 * measure it approximates. It remains worth running for the comparison; it is
 * not worth running as a speed optimisation.
 */
public final class BoundingBoxEngine implements ColocEngine, ThresholdBearing {

    private static final ColumnSpec BOX_OVERLAP_PERCENT = ColumnSpec.primary(
            "BBox Overlap", "%",
            "Percentage of this object's bounding box shared with the box of a target object",
            // PERCENT, not FRACTION, and the 0-100 number from volcoloc-core is
            // passed through unconverted. The comparison this engine exists for
            // is against VolumeOverlapEngine, which is also PERCENT; declaring
            // FRACTION here would make ScaleKind.isComparableWith() refuse tier 3
            // between exactly the two engines whose disagreement is the finding.
            ScaleKind.PERCENT);

    private static final ColumnSpec PARTNER = ColumnSpec.partner(
            "BBox Overlap Partner",
            "Label of the target object whose box shares the most volume with this one");

    private static final ColumnSpec BOX_VOLUME = ColumnSpec.of(
            "BBox Volume", "voxels",
            "Volume of this object's own bounding box, which is the denominator above",
            ScaleKind.COUNT);

    private final double thresholdPercent;

    /** Uses {@code volcoloc-core}'s default bounding-box threshold, 30%. */
    public BoundingBoxEngine() {
        this(OverlapParameters.DEFAULT_BOUNDING_BOX_THRESHOLD_PERCENT);
    }

    /** @throws IllegalArgumentException unless the setting is a percentage */
    public BoundingBoxEngine(double thresholdPercent) {
        if (!(thresholdPercent >= 0.0 && thresholdPercent <= 100.0)) {
            throw new IllegalArgumentException("bounding-box overlap threshold is a"
                    + " percentage, so it must be between 0 and 100, not "
                    + thresholdPercent);
        }
        this.thresholdPercent = thresholdPercent;
    }

    @Override
    public String id() {
        return "bounding-box";
    }

    @Override
    public String displayName() {
        return "Bounding-box overlap";
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
        return Arrays.asList(BOX_OVERLAP_PERCENT, PARTNER, BOX_VOLUME);
    }

    @Override
    public boolean isSymmetric() {
        // The shared box volume is symmetric; the percentage is not, because the
        // denominator is the source object's own box. A small object inside a
        // large one's box is high one way and negligible the other, exactly as
        // for the voxel-exact measure.
        return false;
    }

    @Override
    public double relativeCost() {
        // 1.5 for the voxel-exact scan the runner performs regardless, plus a
        // second full voxel pass accumulating per-label boxes, plus an
        // objects-squared box-intersection search per direction.
        return 2.5;
    }

    @Override
    public EngineResult compute(EngineInputs inputs, EngineProgress progress) {
        if (progress.isCancelled()) {
            throw new EngineCancelledException(id());
        }
        progress.report("Bounding-box overlap", 0.0);

        OverlapParameters parameters = OverlapParameters.builder(inputs.labelImages())
                .channelNames(inputs.channelNames())
                .boundingBoxThresholdsPercent(perChannelThresholds(inputs.channelCount()))
                .bidirectional(true)
                .includePartnerDetails(false)
                .includeMultiColocalization(false)
                .includeBoundingBoxOverlap(true)
                .includeBoundingBoxCpc(false)
                // The volume-fill variant reads the target image inside every
                // source box — a third pass over much of the data — and answers
                // a different question. VolumeOverlapEngine already answers that
                // question exactly.
                .includeBoundingBoxVolumeFill(false)
                .build();

        OverlapResult overlap = DirectionalPairRunner.run(parameters);

        EngineResult.Builder result = EngineResult.forEngine(id());
        List<OverlapResult.BoundingBoxDirectionResult> directions =
                overlap.getBoundingBoxDirectionResults();
        for (int d = 0; d < directions.size(); d++) {
            if (progress.isCancelled()) {
                throw new EngineCancelledException(id());
            }
            OverlapResult.BoundingBoxDirectionResult direction = directions.get(d);

            List<OverlapResult.BoundingBoxObjectResult> objects = direction.getObjects();
            List<ObjectScore> scores = new ArrayList<ObjectScore>();
            double[] boxVolumes = new double[objects.size()];

            for (int o = 0; o < objects.size(); o++) {
                OverlapResult.BoundingBoxObjectResult object = objects.get(o);
                scores.add(new ObjectScore(
                        object.getSourceLabel(),
                        object.getBoundingBoxOverlapPartnerLabel(),
                        object.getBoundingBoxOverlapPercent(),
                        object.isBoundingBoxOverlapColocalized()));
                // The percentage's denominator, carried beside it. Without this
                // column a reader cannot tell 60% of a tight box around a
                // 27-voxel object from 60% of a box eight times the size, and
                // for this measure that difference is the whole story.
                boxVolumes[o] = object.getBoxVolume();
            }

            DirectionKey key = new DirectionKey(
                    direction.getSourceIndex(), direction.getSourceChannel(),
                    direction.getTargetIndex(), direction.getTargetChannel());
            result.direction(key, scores);
            result.supporting(key, BOX_VOLUME.name(), boxVolumes);

            progress.report("Bounding-box overlap", (d + 1.0) / directions.size());
        }
        return result.build();
    }

    /**
     * {@code volcoloc-core} exposes bounding-box thresholds only as a per-channel
     * list — there is no single-value convenience the way there is for the
     * voxel-exact threshold — so one value is broadcast here.
     */
    private List<Double> perChannelThresholds(int channelCount) {
        List<Double> thresholds = new ArrayList<Double>();
        for (int i = 0; i < channelCount; i++) {
            thresholds.add(Double.valueOf(thresholdPercent));
        }
        return thresholds;
    }

    // ---------- ThresholdBearing ----------

    @Override
    public String thresholdName() {
        return "Bounding-box overlap";
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
        return new BoundingBoxEngine(threshold);
    }

    /** 0 to 100 in twenty-one steps of 5. */
    @Override
    public double[] thresholdRange() {
        // A percentage of the source object's own bounding box.
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
