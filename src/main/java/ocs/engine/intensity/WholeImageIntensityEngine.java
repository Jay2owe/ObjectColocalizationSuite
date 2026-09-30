package ocs.engine.intensity;

import ij.ImagePlus;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whole-image Pearson, Manders and Costes over a paired channel pair.
 *
 * <p><b>This engine produces no per-object scores, and that is deliberate.</b>
 * Every other engine answers "what happened to <i>this</i> object"; whole-image
 * intensity colocalization answers "what happened across the whole field", which
 * is one number per direction and not a number per object. {@code compute}
 * therefore reports each direction with an <i>empty</i> score list, and the
 * numbers are collected through {@link #computeMetrics} into the whole-image
 * table the output contract already specifies.
 *
 * <p>The two alternatives were considered and rejected:
 *
 * <ul>
 *   <li><b>Repeat the whole-image value on every object.</b> The column would look
 *       exactly like a per-object measure and be read as one. Worse, it would
 *       break the three generic layers quietly rather than loudly: Spearman's rho
 *       on a constant column is 0/0, Cohen's kappa on a constant flag is
 *       degenerate, and a null model permuting a constant returns enrichment 1.0
 *       and p 1.0 every time — which reads as the legitimate verdict
 *       "uninformative here" when it is an artefact of the repetition.
 *   <li><b>One score under a sentinel label.</b> {@link ObjectScore} requires a
 *       positive source label, so the sentinel would have to be some large
 *       integer that could in principle collide with a real one, and it would put
 *       a row in the per-object table describing an object that does not exist.
 * </ul>
 *
 * <p>Empty is the honest shape: the agreement matrix finds nothing to compare,
 * the null model finds nothing to permute, and Discovery classes the method
 * "Not applicable" on <i>n</i> = 0 — all three of which are true statements about
 * a whole-image metric. The cost is that an empty result is indistinguishable
 * from "ran and found no objects"; {@code 02_CONTRACT.md} would need an additive
 * whole-image channel on {@code EngineResult} to remove that ambiguity, and the
 * proposal is recorded in this stage's report rather than made here.
 *
 * <p>Per-object mask-restricted Pearson and Manders — the ones that <i>do</i> have
 * a per-object value — are stage 6's {@code PerObjectIntensityEngine}, and they
 * run over this same code with a one-object domain mask.
 */
public final class WholeImageIntensityEngine implements ColocEngine {

    private static final ColumnSpec PEARSON = ColumnSpec.primary(
            "Pearson r", "",
            "Pearson correlation between the two intensity channels over the domain",
            ScaleKind.CORRELATION);

    private static final ColumnSpec PEARSON_THRESHOLDED = ColumnSpec.of(
            "Pearson r Thresholded", "",
            "Pearson correlation over voxels above both Costes thresholds",
            ScaleKind.CORRELATION);

    private static final ColumnSpec MANDERS_M1 = ColumnSpec.of(
            "Manders M1", "",
            "Fraction of source-channel signal in voxels above the target's Costes threshold",
            ScaleKind.FRACTION);

    private static final ColumnSpec MANDERS_M2 = ColumnSpec.of(
            "Manders M2", "",
            "Fraction of target-channel signal in voxels above the source's Costes threshold",
            ScaleKind.FRACTION);

    private static final ColumnSpec MANDERS_VALID = ColumnSpec.of(
            "Manders Valid?", "",
            "0 where Manders was refused because a channel carries negative intensities",
            ScaleKind.BINARY);

    private static final ColumnSpec COSTES_TA = ColumnSpec.of(
            "Costes Ta", "intensity",
            "Costes automatic threshold on the source channel",
            ScaleKind.UNBOUNDED);

    private static final ColumnSpec COSTES_TB = ColumnSpec.of(
            "Costes Tb", "intensity",
            "Costes automatic threshold on the target channel",
            ScaleKind.UNBOUNDED);

    private static final ColumnSpec COSTES_FITTED = ColumnSpec.of(
            "Costes Threshold Fitted?", "",
            "1 where the thresholds came from the bisection, 0 where the search bailed"
                    + " out to the domain minimum",
            ScaleKind.BINARY);

    private static final ColumnSpec COSTES_P = ColumnSpec.of(
            "Costes p", "",
            "Permutation p from the block-shuffled null",
            ScaleKind.FRACTION);

    private static final ColumnSpec COSTES_P_FLOOR = ColumnSpec.of(
            "Costes p Floor", "",
            "Smallest p this permutation count can express, 1/(permutations+1)",
            ScaleKind.FRACTION);

    private static final ColumnSpec COSTES_P_AT_FLOOR = ColumnSpec.of(
            "Costes p At Floor?", "",
            "1 where p sits at its floor, meaning the permutation count and not the"
                    + " data is what bounds it",
            ScaleKind.BINARY);

    private static final ColumnSpec RANDOMIZATION_SKIPPED = ColumnSpec.of(
            "Randomization Skipped?", "",
            "1 where the null was not run, so this row's p is absent rather than large",
            ScaleKind.BINARY);

    private static final ColumnSpec BLOCK_SIZE = ColumnSpec.of(
            "Costes Block", "voxels",
            "Block edges used by the randomization, as width x height x depth",
            ScaleKind.COUNT);

    private static final ColumnSpec VOXELS_ANALYZED = ColumnSpec.of(
            "Voxels Analyzed", "voxels",
            "Voxels inside the domain, which is the whole image when no ROI was given",
            ScaleKind.COUNT);

    private final ColocalizationMetrics.Options template;

    /** Defaults: 100 permutations, FLASH's seed, PSF-derived blocks where calibrated. */
    public WholeImageIntensityEngine() {
        this(ColocalizationMetrics.Options.defaults());
    }

    /**
     * @param template permutation count, seed, PSF, block size and worker budget.
     *                 Voxel size and domain mask are filled in per run from
     *                 {@link EngineInputs}, so anything set for those here is
     *                 overwritten
     */
    public WholeImageIntensityEngine(ColocalizationMetrics.Options template) {
        if (template == null) {
            throw new IllegalArgumentException("options template must not be null");
        }
        this.template = template;
    }

    @Override
    public String id() {
        return "whole-image-intensity";
    }

    @Override
    public String displayName() {
        return "Whole-image intensity (Pearson / Manders / Costes)";
    }

    @Override
    public EngineFamily family() {
        return EngineFamily.INTENSITY;
    }

    @Override
    public Set<InputRequirement> requires() {
        return Collections.unmodifiableSet(EnumSet.of(
                InputRequirement.LABEL_IMAGES, InputRequirement.INTENSITY_IMAGES));
    }

    @Override
    public List<ColumnSpec> columns() {
        return Arrays.asList(PEARSON, PEARSON_THRESHOLDED, MANDERS_M1, MANDERS_M2,
                MANDERS_VALID, COSTES_TA, COSTES_TB, COSTES_FITTED, COSTES_P,
                COSTES_P_FLOOR, COSTES_P_AT_FLOOR, RANDOMIZATION_SKIPPED,
                BLOCK_SIZE, VOXELS_ANALYZED);
    }

    @Override
    public boolean isSymmetric() {
        // The primary column is Pearson's r, which is exactly equal both ways —
        // and the primary column is what this flag governs.
        //
        // The supporting columns are not symmetric, and both directions are
        // computed in full for that reason. M1 and M2 swap. So do the Costes
        // thresholds, because the search walks down an ordinary least-squares
        // line of B on A, and regressing A on B is a different line. Costes'
        // original method uses a major-axis fit, which would be direction-free;
        // the ancestor used OLS and this port did not change it, since that is
        // not one of the ten ledger defects. It is worth knowing before anyone
        // compares Ta from one direction with Tb from the other.
        return true;
    }

    @Override
    public double relativeCost() {
        // Up to 32 passes for the threshold bisection plus one per permutation:
        // ~132 passes at the default 100, against 1 for a single voxel scan.
        // The dialog's pre-run estimate is the only place a user finds out that
        // "everything" over a batch is three orders of magnitude off "the object
        // family" before rather than after.
        return 130.0;
    }

    /**
     * Every direction's metrics, in the order {@link EngineInputs#allDirections()}
     * gives them.
     *
     * <p>This is the engine's real output and what fills the whole-image intensity
     * table. {@link #compute} exists to satisfy {@link ColocEngine}; it delegates
     * here and throws the numbers away, so a caller wanting both should call this
     * one and build the empty {@link EngineResult} itself rather than pay twice.
     */
    public Map<DirectionKey, ColocalizationMetrics.Result> computeMetrics(
            EngineInputs inputs, EngineProgress progress) {
        EngineProgress reporter = progress == null ? EngineProgress.SILENT : progress;
        if (reporter.isCancelled()) {
            throw new EngineCancelledException(id());
        }
        List<ImagePlus> intensity = inputs.intensityImages();
        if (intensity.size() != inputs.channelCount()) {
            throw new IllegalStateException("engine '" + id() + "' needs one intensity"
                    + " image per label image; got " + intensity.size() + " for "
                    + inputs.channelCount() + " channels");
        }

        ImagePlus first = intensity.get(0);
        int width = first.getWidth();
        int height = first.getHeight();
        int depth = Math.max(1, first.getStackSize());

        // Built once and shared read-only across every direction. A domain mask
        // for a 168M-voxel stack is 168 MB; building it per direction would be
        // the largest avoidable allocation in the run.
        boolean[] domainMask = ColocalizationMetrics.maskFrom(
                inputs.domain(), width, height, depth);
        ColocalizationMetrics.Options options = withRunSettings(inputs, domainMask);

        Map<DirectionKey, ColocalizationMetrics.Result> results =
                new LinkedHashMap<DirectionKey, ColocalizationMetrics.Result>();
        List<DirectionKey> directions = inputs.allDirections();
        for (int d = 0; d < directions.size(); d++) {
            if (reporter.isCancelled()) {
                throw new EngineCancelledException(id());
            }
            DirectionKey direction = directions.get(d);
            reporter.report("Whole-image intensity " + direction.label(),
                    (double) d / directions.size());
            results.put(direction, ColocalizationMetrics.compute(
                    intensity.get(direction.sourceIndex()),
                    intensity.get(direction.targetIndex()),
                    options, reporter));
        }
        reporter.report("Whole-image intensity", 1.0);
        return results;
    }

    /**
     * The {@link ColocEngine} face: every direction, no per-object scores, and
     * the numbers on the whole-direction channel.
     *
     * <p>See the class comment for why an empty score list is the honest answer.
     * The proposal recorded there — an additive whole-image channel on
     * {@link EngineResult}, so that "measures the field" is distinguishable from
     * "ran and found no objects" — was accepted when the output layer was built
     * on 2026-08-13, because without it these numbers reached no table at all:
     * the per-object table has no row to put them on and nothing else was
     * looking. {@link EngineResult#isWholeDirection()} now carries exactly the
     * meaning the class comment argued for, including that Discovery must class
     * this method <i>not applicable</i> rather than uninformative.
     *
     * <p>{@link #computeMetrics} remains for callers wanting the full result
     * object rather than the doubles.
     */
    @Override
    public EngineResult compute(EngineInputs inputs, EngineProgress progress) {
        Map<DirectionKey, ColocalizationMetrics.Result> metrics =
                computeMetrics(inputs, progress);
        EngineResult.Builder result = EngineResult.forEngine(id());
        for (Map.Entry<DirectionKey, ColocalizationMetrics.Result> entry
                : metrics.entrySet()) {
            DirectionKey direction = entry.getKey();
            ColocalizationMetrics.Result value = entry.getValue();
            result.direction(direction, new ArrayList<ObjectScore>());
            result.wholeDirection(direction, PEARSON.name(), value.pearson());
            result.wholeDirection(direction, PEARSON_THRESHOLDED.name(),
                    value.pearsonThresholded());
            result.wholeDirection(direction, MANDERS_M1.name(), value.mandersM1());
            result.wholeDirection(direction, MANDERS_M2.name(), value.mandersM2());
            result.wholeDirection(direction, MANDERS_VALID.name(),
                    value.mandersRefusedNegative() ? 0.0 : 1.0);
            result.wholeDirection(direction, COSTES_TA.name(), value.costesTa());
            result.wholeDirection(direction, COSTES_TB.name(), value.costesTb());
            result.wholeDirection(direction, COSTES_FITTED.name(),
                    value.costesThresholdFitted() ? 1.0 : 0.0);
            result.wholeDirection(direction, COSTES_P.name(), value.costesP());
            result.wholeDirection(direction, COSTES_P_FLOOR.name(), value.pFloor());
            result.wholeDirection(direction, COSTES_P_AT_FLOOR.name(),
                    value.pAtFloor() ? 1.0 : 0.0);
            result.wholeDirection(direction, RANDOMIZATION_SKIPPED.name(),
                    value.randomizationSkipped() ? 1.0 : 0.0);
            result.wholeDirection(direction, BLOCK_SIZE.name(), value.blockWidth());
            result.wholeDirection(direction, VOXELS_ANALYZED.name(),
                    value.voxelsAnalyzed());
        }
        return result.build();
    }

    /**
     * Fills the two settings that belong to the data rather than to the user's
     * choices: the voxel size the block sizing needs, and the domain the
     * randomization must stay inside.
     */
    private ColocalizationMetrics.Options withRunSettings(EngineInputs inputs,
                                                          boolean[] domainMask) {
        ColocalizationMetrics.Options.Builder builder = template.toBuilder()
                .domainMask(domainMask);
        Calibration calibration = inputs.calibration();
        if (calibration != null && calibration.scaled()) {
            builder.voxelSize(calibration.pixelWidth,
                    calibration.pixelHeight, calibration.pixelDepth);
        }
        return builder.build();
    }
}
