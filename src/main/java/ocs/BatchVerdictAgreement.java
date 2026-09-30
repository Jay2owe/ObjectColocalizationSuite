/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ocs;

import ij.measure.ResultsTable;
import ocs.agreement.AgreementCell;
import ocs.agreement.Verdict;
import ocs.agreement.VerdictAgreement;
import ocs.engine.CurveSeries;
import ocs.engine.DirectionKey;
import ocs.engine.EngineResult;
import ocs.engine.spatial.SpatialCurveEngine;
import ocs.nullmodel.NullModelResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tier-V verdict agreement across a batch: did two methods reach the same
 * conclusion about the same field, counted over every field.
 *
 * <p>The contract's route for the four spatial methods into the comparison.
 * They produce a curve rather than a number per object, so the per-object
 * tiers never see them; what they do produce is a verdict — enriched, depleted
 * or not significant — and so does every object method's chance test. One
 * verdict per method per direction per field, and Cohen's kappa over the
 * fields.
 *
 * <p>Directions are matched by channel <i>position</i>, not name: in a batch the
 * channel names are the file names and differ in every field, while "channel 1
 * against channel 2" means the same thing throughout.
 *
 * <p>Keeps only the verdicts — a few bytes per field — so it does not depend on
 * holding the fields' full results in memory.
 */
public final class BatchVerdictAgreement {

    private BatchVerdictAgreement() {
    }

    /** The direction's position, e.g. {@code C1 -> C2}. */
    public static String position(DirectionKey direction) {
        return "C" + (direction.sourceIndex() + 1) + " -> C"
                + (direction.targetIndex() + 1);
    }

    /**
     * One field's verdicts: direction position to method id to verdict.
     *
     * <p>Object methods' verdicts come from their chance test, the spatial
     * methods' from their own simulation envelope. A method with neither has no
     * verdict and is left out rather than entered as not applicable.
     */
    public static Map<String, Map<String, Verdict>> verdictsOf(OCSResult result) {
        double alpha = result.parameters().alpha();
        Map<String, Map<String, Verdict>> verdicts =
                new LinkedHashMap<String, Map<String, Verdict>>();
        List<NullModelResult> nullModels = result.nullModels();
        for (int i = 0; i < nullModels.size(); i++) {
            NullModelResult nullModel = nullModels.get(i);
            put(verdicts, position(nullModel.direction()), nullModel.engineId(),
                    Verdict.from(nullModel, alpha));
        }
        List<EngineResult> engineResults = result.engineResults();
        for (int i = 0; i < engineResults.size(); i++) {
            EngineResult engineResult = engineResults.get(i);
            if (!engineResult.hasCurves()) {
                continue;
            }
            List<DirectionKey> directions = engineResult.directions();
            for (int d = 0; d < directions.size(); d++) {
                CurveSeries curve = engineResult.curve(directions.get(d));
                if (curve != null) {
                    put(verdicts, position(directions.get(d)), engineResult.engineId(),
                            Verdict.from(curve, alpha, SpatialCurveEngine.GLOBAL_P,
                                    SpatialCurveEngine.MAX_DEVIATION));
                }
            }
        }
        return verdicts;
    }

    /**
     * Kappa for every pair of methods, per direction position, over the fields.
     *
     * @param fieldNames parallel to {@code perField}
     * @return direction position to cells, in the order positions first appear
     */
    public static Map<String, List<AgreementCell>> across(List<String> fieldNames,
            List<Map<String, Map<String, Verdict>>> perField, int minimumRuns) {
        List<String> positions = new ArrayList<String>();
        for (int f = 0; f < perField.size(); f++) {
            for (String position : perField.get(f).keySet()) {
                if (!positions.contains(position)) {
                    positions.add(position);
                }
            }
        }
        VerdictAgreement agreement = VerdictAgreement.withMinimumRuns(minimumRuns);
        Map<String, List<AgreementCell>> cells =
                new LinkedHashMap<String, List<AgreementCell>>();
        for (int p = 0; p < positions.size(); p++) {
            List<VerdictAgreement.Run> runs = new ArrayList<VerdictAgreement.Run>();
            for (int f = 0; f < perField.size(); f++) {
                Map<String, Verdict> verdicts = perField.get(f).get(positions.get(p));
                if (verdicts != null) {
                    runs.add(new VerdictAgreement.Run(fieldNames.get(f), verdicts));
                }
            }
            List<AgreementCell> positionCells = agreement.across(runs);
            if (!positionCells.isEmpty()) {
                cells.put(positions.get(p), positionCells);
            }
        }
        return Collections.unmodifiableMap(cells);
    }

    /** One row per method pair per direction position. */
    public static ResultsTable table(Map<String, List<AgreementCell>> cells) {
        ResultsTable table = new ResultsTable();
        for (Map.Entry<String, List<AgreementCell>> entry : cells.entrySet()) {
            for (int i = 0; i < entry.getValue().size(); i++) {
                AgreementCell cell = entry.getValue().get(i);
                table.incrementCounter();
                table.addValue("Direction", entry.getKey());
                table.addValue("Tier", cell.tier().label());
                table.addValue("Method A", cell.engineA());
                table.addValue("Method B", cell.engineB());
                table.addValue(VerdictAgreement.RUNS_COMPARED, cell.n());
                table.addValue(VerdictAgreement.RAW_AGREEMENT, cell.wasComputed()
                        ? cell.statistic(VerdictAgreement.RAW_AGREEMENT) : Double.NaN);
                table.addValue(VerdictAgreement.KAPPA, cell.wasComputed()
                        ? cell.statistic(VerdictAgreement.KAPPA) : Double.NaN);
                table.addValue("Underpowered", cell.isUnderpowered() ? 1 : 0);
                table.addValue("Note", cell.wasComputed()
                        ? "" : cell.notComputedReason());
            }
        }
        return table;
    }

    private static void put(Map<String, Map<String, Verdict>> verdicts,
            String position, String engineId, Verdict verdict) {
        Map<String, Verdict> atPosition = verdicts.get(position);
        if (atPosition == null) {
            atPosition = new LinkedHashMap<String, Verdict>();
            verdicts.put(position, atPosition);
        }
        atPosition.put(engineId, verdict);
    }
}
