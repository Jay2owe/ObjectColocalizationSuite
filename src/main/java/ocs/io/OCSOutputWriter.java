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
import ocs.OCSParameters;
import ocs.OCSResult;
import ocs.SkippedMethod;
import ocs.engine.ColocEngine;
import ocs.engine.EngineFamily;
import ocs.nullmodel.NullModelKind;
import sc.fiji.oc3d.core.io.CsvWriter;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes a run to disk in a shape somebody can find things in a year later.
 *
 * <p>One folder per kind of table rather than one folder per image. A study with
 * four hundred fields then has eight directories of four hundred files, which a
 * script can glob; the other way round it has four hundred directories of eight
 * files, which nothing can.
 *
 * <pre>
 * &lt;root&gt;/Object Colocalization Suite/
 *   README.txt          what produced this, when, and what each folder holds
 *   run-record.json     every parameter, so the run can be reproduced
 *   per-object/&lt;name&gt;.csv
 *   summary/&lt;name&gt;.csv
 *   ...
 * </pre>
 */
public final class OCSOutputWriter {

    /** The one folder this plugin ever creates inside somebody's chosen root. */
    public static final String FOLDER = "Object Colocalization Suite";

    public static final String README = "README.txt";
    public static final String RUN_RECORD = "run-record.json";

    private OCSOutputWriter() {
    }

    /**
     * Writes every non-empty table, the run record and the README.
     *
     * @param root  where the user asked output to go; the suite's own folder is
     *              created inside it rather than writing loose files into it
     * @return the folder actually written to
     * @throws IOException if anything could not be written. Not swallowed: a
     *         batch that reports success while silently failing to save is the
     *         one failure that costs somebody a whole day of imaging.
     */
    public static File write(OCSResult result, File root) throws IOException {
        if (result == null || root == null) {
            throw new IllegalArgumentException("a result and a root are required");
        }
        File folder = new File(root, FOLDER);
        makeFolder(folder);

        Map<String, ResultsTable> tables = OCSTables.all(result);
        for (Map.Entry<String, ResultsTable> entry : tables.entrySet()) {
            File subfolder = new File(folder, entry.getKey());
            makeFolder(subfolder);
            CsvWriter.write(new File(subfolder, safeName(
                    result.parameters().sourceName()) + ".csv"), entry.getValue());
        }

        writeRunRecord(result, new File(folder, RUN_RECORD));
        writeReadme(result, tables, new File(folder, README));
        return folder;
    }

    /** Beside the per-kind folders: one run record per batch field. */
    public static final String FIELD_RECORDS = "run-records";

    /**
     * Writes this field's run record to {@code run-records/<name>.json}.
     *
     * <p>A batch writes every field into one folder, so the top-level
     * {@link #RUN_RECORD} is rewritten by each field and ends up describing
     * the last. The settings are the same for every field, but the images,
     * the methods a field could not run and its caveats are not.
     */
    public static File writeFieldRecord(OCSResult result, File root)
            throws IOException {
        File folder = new File(new File(root, FOLDER), FIELD_RECORDS);
        makeFolder(folder);
        File file = new File(folder,
                safeName(result.parameters().sourceName()) + ".json");
        writeRunRecord(result, file);
        return file;
    }

    /**
     * Checks, before any analysis runs, that output could be saved under
     * {@code root}.
     *
     * <p>Asked up front because the alternative is finding out after the run:
     * a save folder on an unplugged drive, or inside a file, or somewhere the
     * account may not write, would otherwise cost the whole analysis and then
     * fail. Creates nothing that stays: a probe file is written into the
     * deepest folder that already exists and deleted at once.
     *
     * @throws IllegalArgumentException with a sentence saying why not
     */
    public static void checkWritable(File root) {
        if (root == null) {
            throw new IllegalArgumentException("cannot save: no folder was given");
        }
        File absolute = root.getAbsoluteFile();
        File existing = absolute;
        while (existing != null && !existing.exists()) {
            existing = existing.getParentFile();
        }
        if (existing == null) {
            throw new IllegalArgumentException("cannot save to " + absolute
                    + ": neither it nor any folder above it exists. Is the drive"
                    + " connected?");
        }
        if (!existing.isDirectory()) {
            throw new IllegalArgumentException("cannot save to " + absolute
                    + ": " + existing + " is a file, not a folder");
        }
        try {
            File probe = File.createTempFile(".ocs-write-check", ".tmp", existing);
            if (!probe.delete()) {
                probe.deleteOnExit();
            }
        } catch (IOException denied) {
            throw new IllegalArgumentException("cannot save to " + absolute
                    + ": " + existing + " is not writable ("
                    + denied.getMessage() + ")");
        } catch (SecurityException denied) {
            throw new IllegalArgumentException("cannot save to " + absolute
                    + ": " + existing + " is not writable");
        }
    }

    /**
     * Appends one run's tables to per-kind files shared by a whole batch.
     *
     * <p>Separate files per run and one file per kind are both wanted, by
     * different people: somebody chasing one bad field wants that field's file,
     * and somebody fitting a model wants all four hundred rows in one place.
     * This writes the second; {@link #write} writes the first.
     */
    public static File append(OCSResult result, File root, boolean writeHeader)
            throws IOException {
        File folder = new File(new File(root, FOLDER), "batch");
        makeFolder(folder);

        Map<String, ResultsTable> tables = OCSTables.all(result);
        for (Map.Entry<String, ResultsTable> entry : tables.entrySet()) {
            appendTable(new File(folder, entry.getKey() + ".csv"),
                    entry.getValue(), writeHeader);
        }
        return folder;
    }

    // ---------- the record ----------

    /**
     * Every setting that could change the numbers, as JSON.
     *
     * <p>Hand-written rather than through a library: adding a JSON dependency to
     * a Fiji plugin means shading another jar into the flat classloader, and
     * this is the only place in the plugin that needs one.
     */
    static void writeRunRecord(OCSResult result, File file) throws IOException {
        OCSParameters parameters = result.parameters();
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        field(json, "plugin", FOLDER, true);
        field(json, "image", parameters.sourceName(), true);
        json.append("  \"channels\": [");
        List<String> names = parameters.channelNames();
        for (int i = 0; i < parameters.labelImages().size(); i++) {
            if (i > 0) {
                json.append(", ");
            }
            json.append('"').append(escape(names != null && i < names.size()
                    ? names.get(i) : parameters.labelImages().get(i).getTitle()))
                    .append('"');
        }
        json.append("],\n");

        json.append("  \"methods\": [");
        for (int i = 0; i < parameters.methodIds().size(); i++) {
            if (i > 0) {
                json.append(", ");
            }
            json.append('"').append(escape(parameters.methodIds().get(i))).append('"');
        }
        json.append("],\n");

        json.append("  \"thresholds\": {");
        boolean first = true;
        for (Map.Entry<String, Double> entry : parameters.thresholds().entrySet()) {
            if (!first) {
                json.append(", ");
            }
            first = false;
            json.append('"').append(escape(entry.getKey())).append("\": ")
                    .append(entry.getValue());
        }
        json.append("},\n");

        // The methods that did not run, and why. A record listing thirteen
        // methods beside a table holding eleven is a record that misleads.
        json.append("  \"skipped\": [");
        List<SkippedMethod> skipped = result.skipped();
        for (int i = 0; i < skipped.size(); i++) {
            if (i > 0) {
                json.append(", ");
            }
            json.append("{\"method\": \"").append(escape(skipped.get(i).engineId()))
                    .append("\", \"reason\": \"")
                    .append(escape(skipped.get(i).reason())).append("\"}");
        }
        json.append("],\n");

        field(json, "bidirectional", String.valueOf(parameters.isBidirectional()), false);
        field(json, "nullModel", String.valueOf(parameters.runsNullModel()), false);
        field(json, "permutations", String.valueOf(parameters.permutations()), false);
        field(json, "seed", String.valueOf(parameters.seed()), false);
        field(json, "seedContractVersion",
                String.valueOf(ocs.nullmodel.NullModelRunner.SEED_CONTRACT_VERSION), false);
        field(json, "agreement", String.valueOf(parameters.runsAgreement()), false);
        field(json, "thresholdSweep",
                String.valueOf(parameters.runsThresholdSweep()), false);
        field(json, "discovery", String.valueOf(parameters.runsDiscovery()), false);
        field(json, "alpha", String.valueOf(parameters.alpha()), false);
        field(json, "flipThreshold", String.valueOf(parameters.flipThreshold()), false);
        field(json, "minCompareN", String.valueOf(parameters.minCompareN()), false);
        field(json, "kappaLimit", String.valueOf(parameters.kappaLimit()), false);
        field(json, "regionRoi", parameters.domain().isEmpty()
                ? "none" : parameters.domain().size() + " ROI(s)", true);
        field(json, "nullModelKind", parameters.nullModelKind().id(), true);
        field(json, "sweepWidth", String.valueOf(parameters.sweepWidth()), false);
        appendChanceTest(json, result);
        json.append("  \"caveats\": [");
        List<String> caveats = caveats(result);
        for (int i = 0; i < caveats.size(); i++) {
            json.append(i == 0 ? "\n    \"" : ",\n    \"")
                    .append(escape(caveats.get(i))).append('"');
        }
        json.append(caveats.isEmpty() ? "],\n" : "\n  ],\n");

        // Last, and without a trailing comma.
        json.append("  \"version\": \"").append(escape(version())).append("\"\n");
        json.append("}\n");
        writeText(file, json.toString());
    }

    static void writeReadme(OCSResult result, Map<String, ResultsTable> tables,
            File file) throws IOException {
        StringBuilder text = new StringBuilder();
        text.append(FOLDER).append('\n');
        text.append("Version ").append(version()).append('\n');
        text.append("Image: ").append(result.parameters().sourceName()).append('\n');
        text.append('\n');
        text.append("Every setting that could change these numbers is in ")
                .append(RUN_RECORD).append(", including the seed.\n");
        text.append('\n');
        text.append("Folders here:\n");
        for (Map.Entry<String, ResultsTable> entry : tables.entrySet()) {
            text.append("  ").append(entry.getKey()).append('/')
                    .append(pad(entry.getKey()))
                    .append(describe(entry.getKey()))
                    .append("  (").append(entry.getValue().size())
                    .append(" rows)\n");
        }
        if (!result.skipped().isEmpty()) {
            text.append('\n');
            text.append("Methods asked for that did not run:\n");
            for (int i = 0; i < result.skipped().size(); i++) {
                text.append("  ").append(result.skipped().get(i)).append('\n');
            }
        }
        List<String> caveats = caveats(result);
        if (!caveats.isEmpty()) {
            text.append('\n');
            text.append("Read these numbers with:\n");
            for (int i = 0; i < caveats.size(); i++) {
                text.append("  - ").append(caveats.get(i)).append('\n');
            }
        }
        text.append('\n');
        text.append("Columns for a method are named by its method id, which is "
                + "stable across\nversions. Blank means the method produced no "
                + "value there; it never means zero.\n");
        writeText(file, text.toString());
    }

    // ---------- what to read the numbers with ----------

    /** The measured cross-field inflation of the whole-channel null. */
    static final double WHOLE_CHANNEL_INFLATION = 1.45;

    /** The one engine that reports no p under a wrapping null. */
    static final String BOUNDING_BOX = "bounding-box";

    /** The engine whose count statistic ties almost everywhere on real data. */
    static final String JACCARD_DICE = "jaccard-dice";

    /**
     * The smallest two-sided p a chance test with this many shuffles can
     * report, before ties: {@code min(1, 2 / (P + 1))}.
     */
    static double nominalPFloor(int shuffles) {
        return Math.min(1.0, 2.0 / (shuffles + 1.0));
    }

    /** What the chance test's null hypothesis says, in one sentence. */
    static String hypothesis(NullModelKind kind) {
        if (kind == NullModelKind.PER_OBJECT) {
            return "the objects meet no more and no less often than they would if"
                    + " each were placed independently inside the region, keeping"
                    + " its shape and size";
        }
        return "the two channels meet no more and no less often than they would if"
                + " each kept its own internal arrangement but its position relative"
                + " to the other were random";
    }

    /**
     * The caveats this run's numbers carry, as sentences for the README and the
     * run record.
     *
     * <p>Only the caveats that apply: a planar note on a run with no spatial
     * method, or a tie note on a run without Jaccard/Dice, is noise that teaches
     * people to skip the section.
     */
    static List<String> caveats(OCSResult result) {
        OCSParameters parameters = result.parameters();
        List<String> caveats = new ArrayList<String>();
        if (parameters.runsNullModel()) {
            NullModelKind kind = parameters.nullModelKind();
            caveats.add("Chance test: " + kind.id() + " null (" + kind.explanation()
                    + "). It tests whether " + hypothesis(kind) + ". "
                    + parameters.permutations() + " shuffles, seed "
                    + parameters.seed() + ", seed contract version "
                    + ocs.nullmodel.NullModelRunner.SEED_CONTRACT_VERSION + ".");
            caveats.add("The smallest two-sided p this run can report is min(1, 2/("
                    + parameters.permutations() + "+1)) = "
                    + nominalPFloor(parameters.permutations())
                    + "; ties can raise it. p values are raw and two-sided; alpha ("
                    + parameters.alpha() + ") is per test, with no correction for"
                    + " multiple comparisons.");
            if (kind == NullModelKind.WHOLE_CHANNEL) {
                caveats.add("Under the whole-channel null, independent real channels"
                        + " gave p < 0.05 about " + WHOLE_CHANNEL_INFLATION
                        + " times as often as expected (0.029 against 0.020,"
                        + " alpha validation 2026-08-30), so read p values near"
                        + " alpha with that inflation in mind.");
                if (ran(result, BOUNDING_BOX)) {
                    caveats.add("bounding-box has no p under the whole-channel null:"
                            + " wrapping at the frame edge can turn an object's box"
                            + " into the whole frame. Its measured values are still"
                            + " reported.");
                }
            }
            if (ran(result, JACCARD_DICE)) {
                caveats.add("jaccard-dice: its coincident count ties almost"
                        + " everywhere on real data, so its p rarely falls below"
                        + " 0.05 even when there is an effect (about 0.0007 by"
                        + " chance in validation). A non-significant jaccard-dice"
                        + " result is weak evidence of no effect.");
            }
        }
        if (spatialRan(result)) {
            caveats.add("The spatial methods (cross-G, cross-K, cross-L, pair"
                    + " correlation) are planar: each object contributes only its"
                    + " xy centroid, so two objects at the same xy on different"
                    + " slices count as coincident.");
        }
        return caveats;
    }

    private static boolean ran(OCSResult result, String engineId) {
        return result.enginesById().containsKey(engineId);
    }

    private static boolean spatialRan(OCSResult result) {
        List<ColocEngine> engines = result.engines();
        for (int i = 0; i < engines.size(); i++) {
            if (engines.get(i).family() == EngineFamily.SPATIAL) {
                return true;
            }
        }
        return false;
    }

    /** The chance test's settings as one JSON object, or null when it did not run. */
    private static void appendChanceTest(StringBuilder json, OCSResult result) {
        OCSParameters parameters = result.parameters();
        if (!parameters.runsNullModel()) {
            json.append("  \"chanceTest\": null,\n");
            return;
        }
        NullModelKind kind = parameters.nullModelKind();
        json.append("  \"chanceTest\": {\n");
        json.append("    \"kind\": \"").append(escape(kind.id())).append("\",\n");
        json.append("    \"hypothesis\": \"").append(escape(hypothesis(kind)))
                .append("\",\n");
        json.append("    \"shuffles\": ").append(parameters.permutations()).append(",\n");
        json.append("    \"seed\": ").append(parameters.seed()).append(",\n");
        json.append("    \"seedContractVersion\": ")
                .append(ocs.nullmodel.NullModelRunner.SEED_CONTRACT_VERSION).append(",\n");
        json.append("    \"nominalPFloor\": ")
                .append(nominalPFloor(parameters.permutations())).append(",\n");
        json.append("    \"pValues\": \"raw, two-sided; alpha is per test\",\n");
        json.append("    \"crossFieldInflation\": ")
                .append(kind == NullModelKind.WHOLE_CHANNEL
                        ? String.valueOf(WHOLE_CHANNEL_INFLATION) : "null")
                .append(",\n");
        json.append("    \"noPValue\": [");
        if (kind == NullModelKind.WHOLE_CHANNEL && ran(result, BOUNDING_BOX)) {
            json.append('"').append(BOUNDING_BOX).append('"');
        }
        json.append("]\n");
        json.append("  },\n");
    }

    /** One line each, in the words somebody reading the folder would use. */
    private static String describe(String tableName) {
        if ("per-object".equals(tableName)) {
            return "one row per object per channel pair, one column per method";
        }
        if ("summary".equals(tableName)) {
            return "one row per channel pair";
        }
        if ("whole-direction".equals(tableName)) {
            return "methods that measure the field rather than the objects in it";
        }
        if ("curves".equals(tableName)) {
            return "the spatial curves' status and global p";
        }
        if ("null-model".equals(tableName)) {
            return "each method against chance, with the seed that produced it";
        }
        if ("agreement".equals(tableName)) {
            return "how far the methods agree with each other, object by object";
        }
        if ("threshold-sensitivity".equals(tableName)) {
            return "how much each answer depends on where its cut-off was drawn";
        }
        if ("discovery".equals(tableName)) {
            return "each method classified, with the evidence behind it";
        }
        if ("skipped".equals(tableName)) {
            return "methods asked for that this data could not feed";
        }
        return "";
    }

    // ---------- plumbing ----------

    private static void appendTable(File file, ResultsTable table,
            boolean writeHeader) throws IOException {
        boolean header = writeHeader || !file.exists();
        Writer writer = new OutputStreamWriter(
                new java.io.FileOutputStream(file, !header), "UTF-8");
        try {
            String[] headings = table.getHeadings();
            if (header) {
                writer.write(joinQuoted(headings));
                writer.write("\n");
            }
            for (int row = 0; row < table.size(); row++) {
                StringBuilder line = new StringBuilder();
                for (int c = 0; c < headings.length; c++) {
                    if (c > 0) {
                        line.append(',');
                    }
                    line.append(CsvWriter.quote(cell(table, headings[c], row)));
                }
                writer.write(line.toString());
                writer.write("\n");
            }
        } finally {
            writer.close();
        }
    }

    /**
     * A cell as text, keeping string columns as strings.
     *
     * <p>{@code getValue} on a string column returns NaN, so reading everything
     * as a number would silently blank the method names — the columns a reader
     * needs most to make sense of the rest.
     */
    private static String cell(ResultsTable table, String heading, int row) {
        String text = table.getStringValue(heading, row);
        return text == null ? "" : text;
    }

    private static String joinQuoted(String[] values) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                line.append(',');
            }
            line.append(CsvWriter.quote(values[i]));
        }
        return line.toString();
    }

    private static void makeFolder(File folder) throws IOException {
        if (folder.isDirectory()) {
            return;
        }
        if (!folder.mkdirs() && !folder.isDirectory()) {
            throw new IOException("could not create " + folder.getAbsolutePath());
        }
    }

    private static void writeText(File file, String text) throws IOException {
        Writer writer = new OutputStreamWriter(
                new java.io.FileOutputStream(file), "UTF-8");
        try {
            writer.write(text);
        } finally {
            writer.close();
        }
    }

    private static void field(StringBuilder json, String name, String value,
            boolean quoted) {
        json.append("  \"").append(name).append("\": ");
        if (quoted) {
            json.append('"').append(escape(value)).append('"');
        } else {
            json.append(value);
        }
        json.append(",\n");
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                escaped.append('\\').append(c);
            } else if (c == '\n') {
                escaped.append("\\n");
            } else if (c == '\r') {
                escaped.append("\\r");
            } else if (c == '\t') {
                escaped.append("\\t");
            } else if (c < 0x20) {
                escaped.append(String.format("\\u%04x", Integer.valueOf(c)));
            } else {
                escaped.append(c);
            }
        }
        return escaped.toString();
    }

    /** Anything a filesystem might refuse becomes an underscore. */
    static String safeName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "run";
        }
        String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        // Trailing dots and spaces are legal in the string and illegal in a
        // Windows filename, which fails at write time rather than at name time.
        safe = safe.replaceAll("[. ]+$", "");
        return safe.isEmpty() ? "run" : safe;
    }

    private static String version() {
        String version = OCSOutputWriter.class.getPackage() == null
                ? null : OCSOutputWriter.class.getPackage().getImplementationVersion();
        return version == null ? "development build" : version;
    }

    private static String pad(String name) {
        StringBuilder padding = new StringBuilder(" ");
        for (int i = name.length(); i < 22; i++) {
            padding.append(' ');
        }
        return padding.toString();
    }
}
