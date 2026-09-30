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
 * Containment taxonomy — which of an object and its strongest partner, if either,
 * sits inside the other.
 *
 * <p>This is the relationship a single overlap percentage hides. Take a
 * 4-voxel object entirely inside a 100-voxel one. Volumetric overlap from the
 * large object's end reports 4%, which sits below any usual threshold and lands
 * in a table as "not colocalized". Nothing in that row says the partner is
 * wholly enclosed. A synaptic marker inside a bouton, a nucleus inside a soma, a
 * plaque core inside its halo — every one of those is a containment relationship
 * that reads as a small percentage from the containing object's side, and reads
 * as a small percentage precisely <i>because</i> the containment is complete.
 *
 * <p>So the primary value is 1 when a containment relationship exists <b>in
 * either direction</b>, and the four-way class says which. That makes the value
 * a genuine proportion once the null model averages it — "the fraction of
 * objects in a nesting relationship with their partner" — rather than an
 * arbitrary numeric code whose mean means nothing.
 *
 * <p>Containment needs a tolerance because segmentation boundaries are not
 * exact: an object 98% inside another is inside it for every practical purpose,
 * and demanding 100% would report DISJOINT-adjacent nonsense for a single stray
 * boundary voxel. The tolerance is a constructor parameter with a documented
 * default, exactly as {@link VolumeOverlapEngine} exposes its threshold, and
 * exactly as arbitrary — the threshold sweep exists to show how much of a result
 * depends on it.
 */
public final class ContainmentEngine implements ColocEngine, ThresholdBearing {

    /**
     * 98% of an object's voxels inside its partner counts as inside.
     *
     * <p>Chosen so a two-voxel boundary discrepancy on a hundred-voxel object
     * does not flip the class, and no more principled than that. A reporting
     * convention, not a truth claim.
     */
    public static final double DEFAULT_CONTAINMENT_PERCENT = 98.0;

    /**
     * Which object, if either, is inside the other.
     *
     * <p>Public because the class is a per-object output with no transport in
     * {@link ObjectScore}, which carries one scalar. Until the table layer has a
     * richer per-object channel, {@link #classify} is how a consumer recovers the
     * category from the two percentages this engine also reports.
     */
    public enum ContainmentClass {

        /** No shared voxels at all. */
        DISJOINT("Disjoint"),

        /**
         * The source object lies inside its partner. Also reported when the
         * containment is mutual — two near-identical objects — because the row is
         * written from the source object's end, and the reverse direction's row
         * makes the same statement from the other end, so nothing is lost.
         */
        SOURCE_INSIDE_TARGET("Source inside target"),

        /** The partner lies inside the source object. */
        TARGET_INSIDE_SOURCE("Target inside source"),

        /** They share voxels, but neither is enclosed by the other. */
        PARTIAL("Partial");

        private final String displayName;

        ContainmentClass(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        /** Whether this class is a containment relationship either way. */
        public boolean isContained() {
            return this == SOURCE_INSIDE_TARGET || this == TARGET_INSIDE_SOURCE;
        }
    }

    private static final ColumnSpec CONTAINED = ColumnSpec.primary(
            "Contained", "",
            "1 when this object and its strongest partner are in a containment "
                    + "relationship in either direction, else 0",
            ScaleKind.BINARY);

    /**
     * Built from {@link ContainmentClass#values()} rather than from a written-out
     * list, so the codes the engine emits and the labels the table prints are the
     * same enum in the same order by construction. A hand-kept list would be one
     * reordering away from labelling every row with its neighbour's category —
     * a table that is wrong everywhere and looks entirely normal.
     *
     * <p>Categorical implies {@code UNBOUNDED}: a class code must never reach the
     * agreement layer's ordinal or interval statistics, because differencing two
     * class codes gives a Bland–Altman bias in units of nothing.
     */
    private static final ColumnSpec CONTAINMENT_CLASS = ColumnSpec.categorical(
            "Containment Class",
            "Disjoint, source inside target, target inside source, or partial",
            displayNames());

    private static String[] displayNames() {
        ContainmentClass[] classes = ContainmentClass.values();
        String[] names = new String[classes.length];
        for (int i = 0; i < classes.length; i++) {
            names[i] = classes[i].displayName();
        }
        return names;
    }

    private static final ColumnSpec SOURCE_INSIDE = ColumnSpec.of(
            "Source Inside Partner", "%",
            "Percentage of this object's voxels inside its strongest partner",
            ScaleKind.PERCENT);

    private static final ColumnSpec TARGET_INSIDE = ColumnSpec.of(
            "Partner Inside Source", "%",
            "Percentage of the partner's voxels inside this object — the number "
                    + "a single overlap percentage never shows",
            ScaleKind.PERCENT);

    private static final ColumnSpec PARTNER = ColumnSpec.partner(
            "Containment Partner",
            "Label of the target object sharing the most voxels with this one");

    private final double containmentPercent;

    /** Uses {@link #DEFAULT_CONTAINMENT_PERCENT}. */
    public ContainmentEngine() {
        this(DEFAULT_CONTAINMENT_PERCENT);
    }

    /** @throws IllegalArgumentException unless the setting is a percentage */
    public ContainmentEngine(double containmentPercent) {
        if (!(containmentPercent >= 0.0 && containmentPercent <= 100.0)) {
            throw new IllegalArgumentException("containment is a percentage of one"
                    + " object inside the other, so it must be between 0 and 100,"
                    + " not " + containmentPercent);
        }
        this.containmentPercent = containmentPercent;
    }

    @Override
    public String id() {
        return "containment";
    }

    @Override
    public String displayName() {
        return "Containment taxonomy";
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
        return Arrays.asList(CONTAINED, CONTAINMENT_CLASS, SOURCE_INSIDE,
                TARGET_INSIDE, PARTNER);
    }

    @Override
    public boolean isSymmetric() {
        // The 0/1 value alone would justify true — a nesting relationship exists
        // or it does not, whichever end you stand at. False anyway, because the
        // class column is the engine's actual output and SOURCE_INSIDE_TARGET is
        // not TARGET_INSIDE_SOURCE. Declaring symmetry would licence the
        // null-model layer to permute one direction and reuse it for the other,
        // which would leave the direction carrying the finding — the large
        // object being told its partner is wholly inside it — without a null.
        return false;
    }

    @Override
    public double relativeCost() {
        // The same single scan VolumeOverlapEngine pays for at 1.5, plus one
        // size lookup and two divisions per object.
        return 1.6;
    }

    /**
     * The taxonomy rule, in one place so a consumer rebuilding the class column
     * cannot drift from what the engine decided.
     *
     * @param sourceInsidePercent percentage of the source object inside the partner
     * @param targetInsidePercent percentage of the partner inside the source object
     * @param tolerancePercent    at or above which "inside" is called
     */
    public static ContainmentClass classify(double sourceInsidePercent,
                                            double targetInsidePercent,
                                            double tolerancePercent) {
        if (!(sourceInsidePercent > 0.0) && !(targetInsidePercent > 0.0)) {
            return ContainmentClass.DISJOINT;
        }
        if (sourceInsidePercent >= tolerancePercent) {
            return ContainmentClass.SOURCE_INSIDE_TARGET;
        }
        if (targetInsidePercent >= tolerancePercent) {
            return ContainmentClass.TARGET_INSIDE_SOURCE;
        }
        return ContainmentClass.PARTIAL;
    }

    @Override
    public EngineResult compute(EngineInputs inputs, EngineProgress progress) {
        if (progress.isCancelled()) {
            throw new EngineCancelledException(id());
        }
        progress.report("Containment taxonomy", 0.0);

        OverlapParameters parameters = OverlapParameters.builder(inputs.labelImages())
                .channelNames(inputs.channelNames())
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
            double[] classes = new double[objects.size()];
            double[] sourceInside = new double[objects.size()];
            double[] targetInside = new double[objects.size()];

            for (int o = 0; o < objects.size(); o++) {
                Measured measured = measure(objects.get(o),
                        direction.getTargetIndex(), voxelCounts);
                scores.add(measured.score);
                classes[o] = measured.containmentClass == null
                        ? Double.NaN : measured.containmentClass.ordinal();
                sourceInside[o] = measured.sourceInside;
                targetInside[o] = measured.targetInside;
            }

            DirectionKey key = new DirectionKey(
                    direction.getSourceIndex(), direction.getSourceChannel(),
                    direction.getTargetIndex(), direction.getTargetChannel());
            result.direction(key, scores);
            result.supporting(key, CONTAINMENT_CLASS.name(), classes);
            result.supporting(key, SOURCE_INSIDE.name(), sourceInside);
            result.supporting(key, TARGET_INSIDE.name(), targetInside);

            progress.report("Containment taxonomy", (d + 1.0) / directions.size());
        }
        return result.build();
    }

    /** The class this engine assigns to one object, for consumers and tests. */
    public ContainmentClass classOf(double sourceInsidePercent, double targetInsidePercent) {
        return classify(sourceInsidePercent, targetInsidePercent, containmentPercent);
    }

    public double containmentPercent() {
        return containmentPercent;
    }

    /**
     * One object's verdict together with the two percentages behind it.
     *
     * <p>The category travels as its {@link ContainmentClass#ordinal()} in a
     * {@code double[]}, because supporting columns carry numbers and a category
     * is not one. A reader wanting the words looks the ordinal up in
     * {@link ContainmentClass#values()}; a reader recomputing the category from
     * the two percentages uses {@link #classify}. Both routes exist, and they
     * agree because the ordinal written here comes from that same call.
     */
    private static final class Measured {
        private final ObjectScore score;
        private final ContainmentClass containmentClass;
        private final double sourceInside;
        private final double targetInside;

        Measured(ObjectScore score, ContainmentClass containmentClass,
                 double sourceInside, double targetInside) {
            this.score = score;
            this.containmentClass = containmentClass;
            this.sourceInside = sourceInside;
            this.targetInside = targetInside;
        }
    }

    private Measured measure(OverlapResult.ObjectResult object, int targetChannel,
                             Map<Long, Integer> voxelCounts) {
        int partner = object.getBestPartnerLabel();
        if (partner == ObjectScore.NO_PARTNER) {
            return new Measured(new ObjectScore(object.getSourceLabel(),
                    ObjectScore.NO_PARTNER, 0.0, false),
                    ContainmentClass.DISJOINT, 0.0, 0.0);
        }

        int intersection = object.getBestPartnerOverlapVoxels();
        int sourceVoxels = object.getSourceVoxels();
        Integer partnerVoxels = voxelCounts.get(Long.valueOf(key(targetChannel, partner)));
        if (partnerVoxels == null || partnerVoxels.intValue() <= 0 || sourceVoxels <= 0) {
            // A size the scan never reported. NaN rather than a guessed
            // denominator — see JaccardDiceEngine for the same reasoning. The
            // class goes with it, as no class rather than as Disjoint: an
            // unknown denominator is not evidence of no overlap, and ordinal 0
            // would say it was.
            return new Measured(new ObjectScore(object.getSourceLabel(), partner,
                    Double.NaN, false), null, Double.NaN, Double.NaN);
        }

        double sourceInside = 100.0 * intersection / sourceVoxels;
        double targetInside = 100.0 * intersection / partnerVoxels.intValue();
        ContainmentClass containmentClass =
                classify(sourceInside, targetInside, containmentPercent);
        boolean contained = containmentClass.isContained();
        return new Measured(new ObjectScore(object.getSourceLabel(), partner,
                contained ? 1.0 : 0.0, contained),
                containmentClass, sourceInside, targetInside);
    }

    /** See {@code JaccardDiceEngine} for why the sizes are recovered this way. */
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
        return "Containment";
    }

    @Override
    public String thresholdUnit() {
        return "%";
    }

    @Override
    public double threshold() {
        return containmentPercent;
    }

    @Override
    public ColocEngine withThreshold(double threshold) {
        return new ContainmentEngine(threshold);
    }

    /** 0 to 100 in twenty-one steps of 5. */
    @Override
    public double[] thresholdRange() {
        // The percentage of one object inside the other at which "inside"
        // is called.
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
