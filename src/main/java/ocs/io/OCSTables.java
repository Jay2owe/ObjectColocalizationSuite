/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ocs.io;

import ij.measure.ResultsTable;
import ocs.OCSResult;
import ocs.SkippedMethod;
import ocs.agreement.AgreementCell;
import ocs.agreement.AgreementMatrix;
import ocs.discovery.DiscoveryResult;
import ocs.engine.ColocEngine;
import ocs.engine.CurveSeries;
import ocs.engine.ColumnSpec;
import ocs.engine.DirectionKey;
import ocs.engine.EngineResult;
import ocs.engine.ObjectScore;
import ocs.nullmodel.NullModelResult;
import ocs.sweep.FlipFraction;
import ocs.sweep.ThresholdSweep;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * One run, as the tables somebody actually reads.
 *
 * <p>Separate from the analysis on purpose. {@link OCSResult} holds objects;
 * batch wants them aggregated, a script wants them as values, and only the
 * person looking at a screen wants a table. Putting the table shape inside the
 * analysis would make every other consumer take it apart again.
 *
 * <p><b>Columns are keyed by engine id</b>, not by display name. Ids are public
 * API and unique; display names are neither — two methods may reasonably both
 * call their primary column "Overlap", and a column that silently overwrote
 * another would be the worst possible failure in a results table.
 */
public final class OCSTables {

    public static final String IMAGE = "Image";
    public static final String SOURCE_CHANNEL = "Source Channel";
    public static final String TARGET_CHANNEL = "Target Channel";
    public static final String LABEL = "Label";
    public static final String VOLUME = "Volume";
    public static final String VOXELS = "Voxels";
    public static final String METHOD = "Method";

    /** Suffixes on a method's per-object columns. */
    public static final String PARTNER = " Partner";
    public static final String COINCIDENT = " Coincident";

    private OCSTables() {
    }

    // ---------- per object ----------

    /**
     * One row per object per direction, with every column each method produces.
     *
     * <p>Wide rather than long: a reader comparing methods wants them side by
     * side on the same object, which is the whole question this plugin exists to
     * answer. Methods that produce no per-object value — the spatial curves, the
     * whole-image intensity measures — contribute no column here and have their
     * own tables instead.
     *
     * <h2>What each method contributes</h2>
     *
     * <p>Three columns keyed by engine id — {@code <id>}, {@code <id> Partner},
     * {@code <id> Coincident} — then one further column per supporting value the
     * method declares, named {@code <id> <column>} and written in the order the
     * engine declares them.
     *
     * <p><b>Widened 2026-08-14.</b> It used to stop at the three. The supporting
     * values were computed and then reached no table at all, which meant a user
     * running per-object Manders got a table with a Pearson column and no
     * Manders in it, and a user running containment got a yes/no verdict without
     * either of the two percentages that verdict is a summary of. Those numbers
     * cost nothing extra to report — every one of them was already calculated —
     * and a measurement the plugin performs but never shows is a measurement the
     * user has to take on trust.
     *
     * <p>The cost is width: with every method enabled a row runs to roughly fifty
     * columns. That is the honest shape of "compute every method over the same
     * objects and put them side by side", and the lever for a narrower table is
     * the method chooser, where a user has already decided what they care about.
     */
    public static ResultsTable perObject(OCSResult result) {
        ResultsTable table = new ResultsTable();
        List<EngineResult> perObjectResults = withObjectScores(result);
        if (perObjectResults.isEmpty()) {
            return table;
        }
        List<DirectionKey> directions = directionsOf(result, perObjectResults);
        Map<Integer, ObjectVolumes> volumes = new LinkedHashMap<Integer, ObjectVolumes>();
        Map<String, ColocEngine> engines = result.enginesById();

        for (int d = 0; d < directions.size(); d++) {
            DirectionKey direction = directions.get(d);
            ObjectVolumes sizes = volumesFor(result, direction.sourceIndex(), volumes);

            for (Integer label : labelsIn(perObjectResults, direction)) {
                table.incrementCounter();
                table.addValue(IMAGE, result.parameters().sourceName());
                table.addValue(SOURCE_CHANNEL, direction.sourceName());
                table.addValue(TARGET_CHANNEL, direction.targetName());
                table.addValue(LABEL, label.intValue());
                table.addValue(VOXELS, sizes.voxelCount(label.intValue()));
                table.addValue(VOLUME, sizes.calibratedVolume(label.intValue()));

                for (int e = 0; e < perObjectResults.size(); e++) {
                    EngineResult engineResult = perObjectResults.get(e);
                    String id = engineResult.engineId();
                    ObjectScore score = scoreFor(engineResult, direction, label.intValue());
                    if (score == null) {
                        // The method ran but had nothing to say about this
                        // object. NaN, not 0 — 0 is a real overlap. The
                        // supporting columns still have to be written, or this
                        // row would be narrower than its neighbours and every
                        // value after it would sit under the wrong header.
                        table.addValue(id, Double.NaN);
                        table.addValue(id + PARTNER, Double.NaN);
                        table.addValue(id + COINCIDENT, Double.NaN);
                        addSupporting(table, engineResult, engines.get(id), direction, -1);
                        continue;
                    }
                    table.addValue(id, score.value());
                    table.addValue(id + PARTNER, score.hasPartner()
                            ? score.partnerLabel() : Double.NaN);
                    table.addValue(id + COINCIDENT, score.isCoincident() ? 1 : 0);
                    addSupporting(table, engineResult, engines.get(id), direction,
                            rowOf(engineResult, direction, label.intValue()));
                }
            }
        }
        return table;
    }

    // ---------- per direction ----------

    /** One row per channel pair: how many objects, and what each method made of them. */
    public static ResultsTable summary(OCSResult result) {
        ResultsTable table = new ResultsTable();
        List<EngineResult> perObjectResults = withObjectScores(result);
        if (perObjectResults.isEmpty()) {
            return table;
        }
        List<DirectionKey> directions = directionsOf(result, perObjectResults);

        for (int d = 0; d < directions.size(); d++) {
            DirectionKey direction = directions.get(d);
            table.incrementCounter();
            table.addValue(IMAGE, result.parameters().sourceName());
            table.addValue(SOURCE_CHANNEL, direction.sourceName());
            table.addValue(TARGET_CHANNEL, direction.targetName());
            table.addValue("n Objects", labelsIn(perObjectResults, direction).size());

            for (int e = 0; e < perObjectResults.size(); e++) {
                EngineResult engineResult = perObjectResults.get(e);
                String id = engineResult.engineId();
                if (!engineResult.directions().contains(direction)) {
                    continue;
                }
                table.addValue(id + " % Coincident",
                        engineResult.percentCoincident(direction));
                double[] values = engineResult.values(direction);
                table.addValue(id + " Mean", mean(values));
                table.addValue(id + " SD", standardDeviation(values));
            }
        }
        return table;
    }

    // ---------- the extra checks ----------

    /** One row per method per direction, from the chance test. */
    public static ResultsTable nullModel(OCSResult result) {
        ResultsTable table = new ResultsTable();
        List<NullModelResult> models = result.nullModels();
        for (int i = 0; i < models.size(); i++) {
            NullModelResult model = models.get(i);
            table.incrementCounter();
            table.addValue(IMAGE, result.parameters().sourceName());
            table.addValue(METHOD, model.engineId());
            addDirection(table, model.direction());
            table.addValue("Observed", model.observed());
            table.addValue("Expected", model.expected());
            table.addValue("Enrichment", model.enrichment());
            table.addValue("p", model.p());
            table.addValue("p Depletion", model.pDepletion());
            table.addValue("p Two-sided", model.pTwoSided());
            table.addValue("Permutations", model.permutations());
            table.addValue("Seed", model.seed());
            table.addValue("Randomization Skipped",
                    model.ran() ? "" : model.skip().toString());
        }
        return table;
    }

    /** One row per method pair per direction. */
    public static ResultsTable agreement(OCSResult result) {
        ResultsTable table = new ResultsTable();
        List<AgreementCell> cells = result.agreement();
        for (int i = 0; i < cells.size(); i++) {
            AgreementCell cell = cells.get(i);
            table.incrementCounter();
            table.addValue(IMAGE, result.parameters().sourceName());
            table.addValue("Tier", cell.tier().label());
            table.addValue("Method A", cell.engineA());
            table.addValue("Method B", cell.engineB());
            addDirection(table, cell.direction());
            table.addValue("n Compared", cell.n());
            addIfPresent(table, cell, "Raw Agreement %", AgreementMatrix.RAW_AGREEMENT);
            addIfPresent(table, cell, "Cohen's Kappa", AgreementMatrix.KAPPA);
            addIfPresent(table, cell, "Prevalence A", AgreementMatrix.PREVALENCE_A);
            addIfPresent(table, cell, "Prevalence B", AgreementMatrix.PREVALENCE_B);
            addIfPresent(table, cell, "Spearman rho", AgreementMatrix.SPEARMAN);
            addIfPresent(table, cell, "Lin's CCC", AgreementMatrix.CONCORDANCE);
            addIfPresent(table, cell, "BA Bias", AgreementMatrix.BIAS);
            addIfPresent(table, cell, "BA LoA Lower", AgreementMatrix.LOWER_LIMIT);
            addIfPresent(table, cell, "BA LoA Upper", AgreementMatrix.UPPER_LIMIT);
            table.addValue("Computed", cell.wasComputed() ? 1 : 0);
        }
        return table;
    }

    /** One row per method per threshold step. */
    public static ResultsTable thresholdSensitivity(OCSResult result) {
        ResultsTable table = new ResultsTable();
        List<ThresholdSweep.Result> sweeps = result.sweeps();
        for (int i = 0; i < sweeps.size(); i++) {
            ThresholdSweep.Result sweep = sweeps.get(i);
            if (!sweep.wasSwept()) {
                // Kept as a row rather than dropped. "This method has no cut-off
                // to be sensitive to" is a point in its favour and belongs in
                // the table, not in the gap where a row would have been.
                table.incrementCounter();
                table.addValue(IMAGE, result.parameters().sourceName());
                table.addValue(METHOD, sweep.engineId());
                table.addValue("Not Swept", sweep.notSweptReason());
                continue;
            }
            List<FlipFraction> fractions = sweep.flipFractions();
            for (int f = 0; f < fractions.size(); f++) {
                FlipFraction fraction = fractions.get(f);
                table.incrementCounter();
                table.addValue(IMAGE, result.parameters().sourceName());
                table.addValue(METHOD, sweep.engineId());
                addDirection(table, fraction.direction());
                table.addValue("Threshold Name", sweep.thresholdName());
                table.addValue("Threshold Unit", sweep.thresholdUnit());
                table.addValue("Steps", sweep.ladder().length);
                table.addValue("Ladder From", sweep.ladder()[0]);
                table.addValue("Ladder To", sweep.ladder()[sweep.ladder().length - 1]);
                table.addValue("Flip Fraction", fraction.value());
            }
        }
        return table;
    }

    /** One row per method: what it was classified as, and on what evidence. */
    public static ResultsTable discovery(OCSResult result) {
        ResultsTable table = new ResultsTable();
        List<DiscoveryResult> classified = result.discovery();
        for (int i = 0; i < classified.size(); i++) {
            DiscoveryResult row = classified.get(i);
            table.incrementCounter();
            table.addValue(IMAGE, result.parameters().sourceName());
            table.addValue(METHOD, row.engineId());
            addDirection(table, row.direction());
            table.addValue("Class", row.discoveryClass().displayName());
            table.addValue("Meaning", row.discoveryClass().meaning());
            table.addValue("Evidence", join(row.evidence()));
            table.addValue("Disagrees With", join(row.divergentFrom()));
        }
        return table;
    }

    // ---------- methods that answer per direction rather than per object ----------

    /**
     * One row per whole-direction method per direction.
     *
     * <p>Whole-image intensity is the family this exists for: Pearson, Manders
     * and Costes describe a channel pair, not an object, so they have no row in
     * the per-object table and would otherwise appear nowhere.
     */
    public static ResultsTable wholeDirectionValues(OCSResult result) {
        ResultsTable table = new ResultsTable();
        List<EngineResult> results = result.engineResults();
        Map<String, ColocEngine> engines = result.enginesById();

        for (int i = 0; i < results.size(); i++) {
            EngineResult engineResult = results.get(i);
            if (!engineResult.isWholeDirection()) {
                continue;
            }
            List<DirectionKey> directions =
                    filtered(result, engineResult.directions());
            for (int d = 0; d < directions.size(); d++) {
                DirectionKey direction = directions.get(d);
                table.incrementCounter();
                table.addValue(IMAGE, result.parameters().sourceName());
                table.addValue(METHOD, engineResult.engineId());
                addDirection(table, direction);

                Map<String, Double> values = engineResult.wholeDirection(direction);
                // Driven by the engine's declared column order, so the table
                // reads the way the method describes itself rather than in
                // whatever order a map happened to iterate.
                ColocEngine engine = engines.get(engineResult.engineId());
                List<ColumnSpec> columns = engine == null
                        ? null : engine.columns();
                if (columns == null) {
                    for (Map.Entry<String, Double> entry : values.entrySet()) {
                        table.addValue(entry.getKey(), entry.getValue().doubleValue());
                    }
                    continue;
                }
                for (int c = 0; c < columns.size(); c++) {
                    String name = columns.get(c).name();
                    Double value = values.get(name);
                    table.addValue(name,
                            value == null ? Double.NaN : value.doubleValue());
                }
            }
        }
        return table;
    }

    /**
     * One row per curve-bearing method per direction, holding the curve's
     * scalars.
     *
     * <p>Not in the original output contract, and added because without it the
     * spatial family's answer appears in no table at all: the curve itself is a
     * plot, and its global <i>p</i> — the thing that says whether the two
     * channels are arranged around each other — lives in the scalars beside it.
     */
    public static ResultsTable curveSummary(OCSResult result) {
        ResultsTable table = new ResultsTable();
        List<EngineResult> results = result.engineResults();

        for (int i = 0; i < results.size(); i++) {
            EngineResult engineResult = results.get(i);
            if (!engineResult.hasCurves()) {
                continue;
            }
            List<DirectionKey> directions =
                    filtered(result, engineResult.directions());
            for (int d = 0; d < directions.size(); d++) {
                DirectionKey direction = directions.get(d);
                CurveSeries curve = engineResult.curve(direction);
                if (curve == null) {
                    continue;
                }
                table.incrementCounter();
                table.addValue(IMAGE, result.parameters().sourceName());
                table.addValue(METHOD, engineResult.engineId());
                addDirection(table, direction);
                table.addValue("Status", curve.status());
                table.addValue("Radii", curve.length());
                for (Map.Entry<String, Double> entry : curve.scalars().entrySet()) {
                    table.addValue(entry.getKey(), entry.getValue().doubleValue());
                }
            }
        }
        return table;
    }

    /** One row per method that was asked for and could not run. */
    public static ResultsTable skipped(OCSResult result) {
        ResultsTable table = new ResultsTable();
        List<SkippedMethod> skipped = result.skipped();
        for (int i = 0; i < skipped.size(); i++) {
            table.incrementCounter();
            table.addValue(IMAGE, result.parameters().sourceName());
            table.addValue(METHOD, skipped.get(i).engineId());
            table.addValue("Reason", skipped.get(i).reason());
        }
        return table;
    }

    // ---------- helpers ----------

    private static List<EngineResult> withObjectScores(OCSResult result) {
        List<EngineResult> kept = new ArrayList<EngineResult>();
        List<EngineResult> all = result.engineResults();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).hasObjectScores()) {
                kept.add(all.get(i));
            }
        }
        return kept;
    }

    /** Every direction any of these methods reported, in first-seen order. */
    private static List<DirectionKey> directionsOf(OCSResult result,
            List<EngineResult> results) {
        Set<DirectionKey> seen = new LinkedHashSet<DirectionKey>();
        for (int i = 0; i < results.size(); i++) {
            seen.addAll(results.get(i).directions());
        }
        return filtered(result, new ArrayList<DirectionKey>(seen));
    }

    /**
     * Drops the mirrored half when the run asked for one direction only.
     *
     * <p>A filter on reporting, not on work: both directions were computed
     * either way, because "how much of A is in B" and "how much of B is in A"
     * are different questions whenever the channels hold different numbers of
     * objects.
     */
    private static List<DirectionKey> filtered(OCSResult result,
            List<DirectionKey> directions) {
        if (result.parameters().isBidirectional()) {
            return directions;
        }
        List<DirectionKey> kept = new ArrayList<DirectionKey>();
        for (int i = 0; i < directions.size(); i++) {
            if (directions.get(i).sourceIndex() < directions.get(i).targetIndex()) {
                kept.add(directions.get(i));
            }
        }
        return kept;
    }

    /** Every label any method reported for this direction, ascending. */
    private static Set<Integer> labelsIn(List<EngineResult> results,
            DirectionKey direction) {
        Set<Integer> labels = new TreeSet<Integer>();
        for (int i = 0; i < results.size(); i++) {
            EngineResult engineResult = results.get(i);
            if (!engineResult.directions().contains(direction)) {
                continue;
            }
            List<ObjectScore> scores = engineResult.scores(direction);
            for (int s = 0; s < scores.size(); s++) {
                labels.add(Integer.valueOf(scores.get(s).sourceLabel()));
            }
        }
        return labels;
    }

    private static ObjectScore scoreFor(EngineResult result, DirectionKey direction,
            int label) {
        int row = rowOf(result, direction, label);
        return row < 0 ? null : result.scores(direction).get(row);
    }

    /** Position of {@code label} in a direction's score list, or −1. */
    private static int rowOf(EngineResult result, DirectionKey direction, int label) {
        if (!result.directions().contains(direction)) {
            return -1;
        }
        List<ObjectScore> scores = result.scores(direction);
        for (int i = 0; i < scores.size(); i++) {
            if (scores.get(i).sourceLabel() == label) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Writes one cell per supporting column the engine declares, in declared
     * order, for the object sitting at {@code row}. A {@code row} of −1 writes
     * the whole set as blanks.
     *
     * <p>Driven by {@code columns()} rather than by whatever supporting arrays
     * happen to be present, so the column set is the same for every row of every
     * image in a batch. Reading the arrays instead would let one image
     * contribute a column another did not, and a batch CSV whose columns depend
     * on the data is one that cannot be concatenated.
     *
     * <p>Skips the primary and the partner column: those are the two written by
     * the caller, and repeating them under a second name would put the same
     * number in the table twice with nothing to say which is which.
     */
    private static void addSupporting(ResultsTable table, EngineResult result,
            ColocEngine engine, DirectionKey direction, int row) {
        if (engine == null) {
            // No registry entry for this id, so there is no declared column list
            // to follow. The three columns above still went in, and inventing a
            // schema from the arrays would make this row's width depend on which
            // engine produced it.
            return;
        }
        List<ColumnSpec> columns = engine.columns();
        for (int c = 0; c < columns.size(); c++) {
            ColumnSpec column = columns.get(c);
            if (column.isPrimary() || column.isPartner()) {
                continue;
            }
            String header = result.engineId() + " " + column.name();
            double[] values = result.supporting(direction, column.name());
            double value = row >= 0 && values != null && row < values.length
                    ? values[row] : Double.NaN;

            if (column.isCategorical()) {
                String category = column.categoryFor(value);
                // Empty rather than the raw code when the code names nothing:
                // a blank reads as "not applicable", which is what NaN means
                // here, while a number in a category column reads as a category.
                table.addValue(header, category == null ? "" : category);
                continue;
            }
            table.addValue(header, value);
        }
    }

    private static ObjectVolumes volumesFor(OCSResult result, int channelIndex,
            Map<Integer, ObjectVolumes> cache) {
        ObjectVolumes cached = cache.get(Integer.valueOf(channelIndex));
        if (cached != null) {
            return cached;
        }
        ObjectVolumes volumes = ObjectVolumes.of(
                result.parameters().labelImages().get(channelIndex),
                result.parameters().calibration());
        cache.put(Integer.valueOf(channelIndex), volumes);
        return volumes;
    }

    private static void addDirection(ResultsTable table, DirectionKey direction) {
        if (direction == null) {
            table.addValue(SOURCE_CHANNEL, "");
            table.addValue(TARGET_CHANNEL, "");
            return;
        }
        table.addValue(SOURCE_CHANNEL, direction.sourceName());
        table.addValue(TARGET_CHANNEL, direction.targetName());
    }

    /**
     * Writes a statistic only where the cell actually holds one.
     *
     * <p>A tier-3 statistic that both methods' scales do not admit is absent,
     * not zero. Writing zero would read as perfect agreement.
     */
    private static void addIfPresent(ResultsTable table, AgreementCell cell,
            String columnName, String statisticName) {
        Double value = cell.statistics().get(statisticName);
        table.addValue(columnName, value == null ? Double.NaN : value.doubleValue());
    }

    /** Several short notes in one cell, so a row stays a row. */
    private static String join(List<String> parts) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                text.append("; ");
            }
            text.append(parts.get(i));
        }
        return text.toString();
    }

    private static double mean(double[] values) {
        int n = 0;
        double sum = 0.0;
        for (int i = 0; i < values.length; i++) {
            if (!Double.isNaN(values[i])) {
                sum += values[i];
                n++;
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    /** Sample standard deviation, NaN below two values rather than 0. */
    private static double standardDeviation(double[] values) {
        double average = mean(values);
        if (Double.isNaN(average)) {
            return Double.NaN;
        }
        int n = 0;
        double sum = 0.0;
        for (int i = 0; i < values.length; i++) {
            if (!Double.isNaN(values[i])) {
                sum += (values[i] - average) * (values[i] - average);
                n++;
            }
        }
        return n < 2 ? Double.NaN : Math.sqrt(sum / (n - 1));
    }

    /** Every table this run can produce, keyed by the name its file would take. */
    public static Map<String, ResultsTable> all(OCSResult result) {
        Map<String, ResultsTable> tables = new LinkedHashMap<String, ResultsTable>();
        put(tables, "per-object", perObject(result));
        put(tables, "summary", summary(result));
        put(tables, "whole-direction", wholeDirectionValues(result));
        put(tables, "curves", curveSummary(result));
        put(tables, "null-model", nullModel(result));
        put(tables, "agreement", agreement(result));
        put(tables, "threshold-sensitivity", thresholdSensitivity(result));
        put(tables, "discovery", discovery(result));
        put(tables, "skipped", skipped(result));
        return Collections.unmodifiableMap(tables);
    }

    /** Empty tables are left out: a file with only headers reads as a failure. */
    private static void put(Map<String, ResultsTable> tables, String name,
            ResultsTable table) {
        if (table.size() > 0) {
            tables.put(name, table);
        }
    }
}
