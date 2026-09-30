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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Jaccard index and Dice coefficient between an object and its strongest partner.
 *
 * <pre>
 *   Jaccard = overlap / (sizeA + sizeB - overlap)
 *   Dice    = 2 * overlap / (sizeA + sizeB)
 * </pre>
 *
 * <p>Both are arithmetic over numbers {@code volcoloc-core}'s single scan has
 * already produced — the shared voxel count and each object's own voxel count —
 * so this engine adds no second pass over the voxels.
 *
 * <p><strong>Symmetric, and that is the point.</strong> Volumetric overlap
 * divides by the source object's own volume, so a 4-voxel object inside a
 * 100-voxel one reads 100% one way and 4% the other. Jaccard divides by the
 * union, which both objects contribute to equally, so the same pair reads 0.04
 * from either end. Neither answer is wrong; they are answers to different
 * questions, and a suite whose whole premise is that colocalization numbers need
 * auditing has to show both rather than pick one.
 *
 * <p>Dice is reported alongside because the two are monotone transforms of each
 * other ({@code Dice = 2J/(1+J)}) and the literature is split on which to quote.
 * Reporting both costs one column and removes a conversion step that readers
 * otherwise do by hand and occasionally get wrong. It also means the agreement
 * matrix will find a near-perfect rank correlation between them, which is a
 * useful sanity check on the matrix itself and a live example of the
 * non-independence the Discovery classifier refuses to treat as corroboration.
 *
 * <p><strong>Partner choice.</strong> The pair measured is the one
 * {@code volcoloc-core} calls best — the target object sharing the most voxels —
 * not the partner that would maximise Jaccard. Those can differ: a smaller
 * partner with slightly less shared volume can score a higher index. Following
 * the shared-voxel argmax keeps this engine's partner column identical to
 * {@link VolumeOverlapEngine}'s, so the agreement matrix compares two measures of
 * the <i>same</i> object pair. An engine that silently picked a different pair
 * would make every agreement cell involving it a comparison of two different
 * things, reported as though it were a comparison of two methods.
 */
public final class JaccardDiceEngine implements ColocEngine, ThresholdBearing {

    /**
     * The intersection-over-union cutoff segmentation evaluation has used since
     * PASCAL VOC. Borrowed rather than invented, but still a reporting
     * convention: an object at 0.49 and one at 0.51 are near-identical and this
     * calls one colocalized and the other not. The threshold sweep exists to
     * show how much of a result rests on it.
     */
    public static final double DEFAULT_JACCARD_THRESHOLD = 0.5;

    private static final ColumnSpec JACCARD = ColumnSpec.primary(
            "Jaccard", "",
            "Shared voxels divided by the union of this object and its strongest partner",
            ScaleKind.FRACTION);

    private static final ColumnSpec DICE = ColumnSpec.of(
            "Dice", "",
            "Twice the shared voxels divided by the summed volumes; a monotone "
                    + "transform of Jaccard, reported for readers who quote it",
            ScaleKind.FRACTION);

    private static final ColumnSpec PARTNER = ColumnSpec.partner(
            "Jaccard Partner",
            "Label of the target object sharing the most voxels with this one");

    private static final ColumnSpec INTERSECTION = ColumnSpec.of(
            "Intersection Voxels", "voxels",
            "Voxels shared with that partner — the numerator",
            ScaleKind.COUNT);

    private static final ColumnSpec UNION = ColumnSpec.of(
            "Union Voxels", "voxels",
            "Voxels in either object — the Jaccard denominator",
            ScaleKind.COUNT);

    private final double thresholdJaccard;

    /** Uses {@link #DEFAULT_JACCARD_THRESHOLD}. */
    public JaccardDiceEngine() {
        this(DEFAULT_JACCARD_THRESHOLD);
    }

    /**
     * @throws IllegalArgumentException unless the setting is a Jaccard index. Note
     *         the scale: 0 to 1, not 0 to 100. Typing 30 here meaning "30%" is the
     *         obvious mistake, and it must fail rather than mean "never".
     */
    public JaccardDiceEngine(double thresholdJaccard) {
        if (!(thresholdJaccard >= 0.0 && thresholdJaccard <= 1.0)) {
            throw new IllegalArgumentException("the Jaccard index runs from 0 to 1,"
                    + " not 0 to 100, so the threshold cannot be "
                    + thresholdJaccard);
        }
        this.thresholdJaccard = thresholdJaccard;
    }

    @Override
    public String id() {
        return "jaccard-dice";
    }

    @Override
    public String displayName() {
        return "Jaccard / Dice";
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
        return Arrays.asList(JACCARD, DICE, PARTNER, INTERSECTION, UNION);
    }

    @Override
    public boolean isSymmetric() {
        // Jaccard(A, B) == Jaccard(B, A) for any given pair: the union
        // denominator does not care which object is called the source.
        //
        // Both ordered directions are still reported, because the two directions
        // carry different row sets — A -> B has one row per A object and B -> A
        // one per B object — and because best-partner choice is made from the
        // source's end, so the pairs measured need not be mutual. What symmetry
        // buys is the null-model and cost-estimate halving, not one column
        // instead of two.
        return true;
    }

    @Override
    public double relativeCost() {
        // The same single scan VolumeOverlapEngine pays for, at 1.5, plus a
        // per-object size lookup and four arithmetic operations. The extra is
        // rounding error next to the scan.
        return 1.6;
    }

    public double thresholdJaccard() {
        return thresholdJaccard;
    }

    /**
     * The Dice coefficient for an object whose Jaccard index is {@code jaccard}.
     *
     * <p>{@code Dice = 2J / (1 + J)} — the two are the same measurement written
     * two ways, which is why Dice is a supporting column rather than a second
     * primary. {@link ObjectScore} carries one scalar, so this is how a consumer
     * fills the Dice column from what the engine transported. That it is a pure
     * function of the primary is the honest reading of what the second column
     * adds: convenience, not information.
     */
    public static double diceFromJaccard(double jaccard) {
        if (Double.isNaN(jaccard)) {
            return Double.NaN;
        }
        double denominator = 1.0 + jaccard;
        return denominator == 0.0 ? Double.NaN : 2.0 * jaccard / denominator;
    }

    @Override
    public EngineResult compute(EngineInputs inputs, EngineProgress progress) {
        if (progress.isCancelled()) {
            throw new EngineCancelledException(id());
        }
        progress.report("Jaccard / Dice", 0.0);

        OverlapParameters parameters = OverlapParameters.builder(inputs.labelImages())
                .channelNames(inputs.channelNames())
                // volcoloc-core's own percentage threshold is left at its default
                // and its colocalized flag ignored; the verdict below is the
                // Jaccard cutoff, which is a different quantity entirely.
                .bidirectional(true)
                .includePartnerDetails(false)
                .includeMultiColocalization(false)
                .includeBoundingBoxOverlap(false)
                .build();

        OverlapResult overlap = DirectionalPairRunner.run(parameters);
        List<OverlapResult.DirectionResult> directions = overlap.getDirectionResults();
        Map<Long, Integer> voxelCounts = voxelCountsByChannelAndLabel(directions);

        EngineResult.Builder result = EngineResult.forEngine(id());
        for (int d = 0; d < directions.size(); d++) {
            if (progress.isCancelled()) {
                throw new EngineCancelledException(id());
            }
            OverlapResult.DirectionResult direction = directions.get(d);

            List<OverlapResult.ObjectResult> objects = direction.getObjects();
            List<ObjectScore> scores = new ArrayList<ObjectScore>();
            double[] dice = new double[objects.size()];
            double[] intersections = new double[objects.size()];
            double[] unions = new double[objects.size()];

            for (int o = 0; o < objects.size(); o++) {
                // The counts come back from the same call that produced the
                // index, not from a second derivation, so the numerator column,
                // the denominator column and the index cannot disagree about the
                // same object.
                Measured measured = measure(objects.get(o),
                        direction.getTargetIndex(), voxelCounts);
                scores.add(measured.score);
                dice[o] = diceFromJaccard(measured.score.value());
                intersections[o] = measured.intersection;
                unions[o] = measured.union;
            }

            DirectionKey key = new DirectionKey(
                    direction.getSourceIndex(), direction.getSourceChannel(),
                    direction.getTargetIndex(), direction.getTargetChannel());
            result.direction(key, scores);
            result.supporting(key, DICE.name(), dice);
            result.supporting(key, INTERSECTION.name(), intersections);
            result.supporting(key, UNION.name(), unions);

            progress.report("Jaccard / Dice", (d + 1.0) / directions.size());
        }
        return result.build();
    }

    /** One object's index together with the two counts that produced it. */
    private static final class Measured {
        private final ObjectScore score;
        private final double intersection;
        private final double union;

        Measured(ObjectScore score, double intersection, double union) {
            this.score = score;
            this.intersection = intersection;
            this.union = union;
        }
    }

    private Measured measure(OverlapResult.ObjectResult object, int targetChannel,
                             Map<Long, Integer> voxelCounts) {
        int partner = object.getBestPartnerLabel();
        int sourceVoxels = object.getSourceVoxels();
        if (partner == ObjectScore.NO_PARTNER) {
            // No target object shares a voxel. The union is well defined — it is
            // this object — and the intersection is empty, so the index is zero
            // rather than undefined. Reporting NaN here would hide a real answer
            // from every mean the summary table computes.
            return new Measured(new ObjectScore(object.getSourceLabel(),
                    ObjectScore.NO_PARTNER, 0.0, false), 0.0, sourceVoxels);
        }

        int intersection = object.getBestPartnerOverlapVoxels();
        Integer partnerVoxels = voxelCounts.get(Long.valueOf(key(targetChannel, partner)));
        if (partnerVoxels == null) {
            // Only reachable if the runner reported a partner label it never
            // reported a size for, which would mean the two halves of the same
            // scan disagree. NaN rather than a guessed denominator: the generic
            // layers skip NaN, and a fabricated union would be a confident wrong
            // number in a table nobody would question.
            return new Measured(new ObjectScore(object.getSourceLabel(), partner,
                    Double.NaN, false), intersection, Double.NaN);
        }

        long union = (long) sourceVoxels + partnerVoxels.intValue() - intersection;
        if (union <= 0L) {
            return new Measured(new ObjectScore(object.getSourceLabel(), partner,
                    Double.NaN, false), intersection, Double.NaN);
        }
        double jaccard = intersection / (double) union;
        return new Measured(new ObjectScore(object.getSourceLabel(), partner,
                jaccard, jaccard >= thresholdJaccard), intersection, union);
    }

    /**
     * Every label's voxel count, keyed by channel and label.
     *
     * <p>{@code VolumeOverlap.scan()} knows every size, but exposes them only
     * through {@code voxelCount(channel, label)} on an instance the runner keeps
     * to itself. Each direction's rows do carry the source object's own size, and
     * a bidirectional run makes every channel the source of some direction, so
     * the same numbers are recoverable without a second scan.
     */
    private static Map<Long, Integer> voxelCountsByChannelAndLabel(
            List<OverlapResult.DirectionResult> directions) {
        Map<Long, Integer> counts = new HashMap<Long, Integer>();
        for (OverlapResult.DirectionResult direction : directions) {
            int channel = direction.getSourceIndex();
            for (OverlapResult.ObjectResult object : direction.getObjects()) {
                counts.put(Long.valueOf(key(channel, object.getSourceLabel())),
                        Integer.valueOf(object.getSourceVoxels()));
            }
        }
        return counts;
    }

    private static long key(int channel, int label) {
        return ((long) channel << 32) | (label & 0xffffffffL);
    }

    // ---------- ThresholdBearing ----------

    @Override
    public String thresholdName() {
        return "Jaccard index";
    }

    @Override
    public String thresholdUnit() {
        return "";
    }

    @Override
    public double threshold() {
        return thresholdJaccard;
    }

    @Override
    public ColocEngine withThreshold(double threshold) {
        return new JaccardDiceEngine(threshold);
    }

    /** 0 to 1 in twenty-one steps of 0.05. */
    @Override
    public double[] thresholdRange() {
        // The Jaccard index runs 0 to 1, not 0 to 100 — the scale mistake
        // the constructor refuses, stated once more where a sweep reads it.
        return new double[] {0.0, 1.0};
    }

    @Override
    public double[] defaultLadder() {
        double[] ladder = new double[21];
        for (int i = 0; i < ladder.length; i++) {
            ladder[i] = i * 0.05;
        }
        return ladder;
    }

}
