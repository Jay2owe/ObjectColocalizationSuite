package ocs.golden;

import ij.measure.ResultsTable;
import ocs.OCS;
import ocs.OCSParameters;
import ocs.OCSResult;
import ocs.engine.ColocEngine;
import ocs.engine.EngineInputs;
import ocs.engine.EngineProgress;
import ocs.engine.EngineRegistry;
import ocs.io.OCSOutputWriter;
import ocs.io.OCSTables;
import ocs.nullmodel.NullModelKind;
import ocs.nullmodel.NullModelResult;
import ocs.nullmodel.NullModelRunner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import sc.fiji.oc3d.core.io.CsvWriter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * The byte-identical output gate.
 *
 * <p>Every later change to this plugin promises its outputs are unchanged unless
 * it says otherwise. The hand-computed reference answers pin the headline
 * numbers of five simple geometries; they do not pin a supporting column, a
 * curve scalar, a null-model p, an agreement statistic or a Discovery class. This
 * does: each case runs {@link OCS#run} on a seeded synthetic fixture and compares
 * every table in {@link OCSTables#all} and every file {@link OCSOutputWriter}
 * writes against a committed copy under {@code src/test/resources/golden/<case>/}.
 *
 * <p><b>Exact, not approximate.</b> Numbers are written with
 * {@link Double#toString}, which round-trips every bit, and compared as text. A
 * tolerance here would hide exactly the drift this exists to catch.
 *
 * <p><b>Normalised fields</b>, the only ones that may legitimately differ between
 * two runs of the same build:
 * <ul>
 *   <li>line endings (CRLF becomes LF; the CSV writer uses the platform separator);</li>
 *   <li>the {@code Version} line of {@code README.txt} and the {@code "version"}
 *       field of {@code run-record.json}, which read the jar manifest and say
 *       "development build" under Maven;</li>
 *   <li>the output root, which is a temporary folder and never appears in the
 *       files themselves.</li>
 * </ul>
 * Timestamps are not normalised because the outputs hold none.
 *
 * <p><b>Updating.</b> {@code -Docs.golden.update=true} rewrites the files from
 * the current build. Only do this for an intended change, named in the CHANGELOG
 * and the commit message; see {@code src/test/resources/golden/README.md}. A
 * missing golden fails unless the flag is set.
 */
public class GoldenOutputTest {

    static final long SEED = 20260812L;
    static final int SHUFFLES = 49;

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    // ---------- the cases ----------

    /** (a) All thirteen methods, every diagnostic, three channels in 3D. */
    @Test
    public void discovery3D() throws IOException {
        GoldenFixtures.Inputs in = GoldenFixtures.threeChannel3D();
        runCase("discovery-3d", everything(in).nullModelKind(NullModelKind.WHOLE_CHANNEL));
    }

    /** (b) All thirteen methods on a single slice. */
    @Test
    public void discovery2D() throws IOException {
        GoldenFixtures.Inputs in = GoldenFixtures.pair2D();
        runCase("discovery-2d", everything(in).nullModelKind(NullModelKind.WHOLE_CHANNEL));
    }

    /** (c) An empty channel and a one-object channel beside an ordinary one. */
    @Test
    public void emptyAndSingleObjectChannels() throws IOException {
        GoldenFixtures.Inputs in = GoldenFixtures.emptyAndSingle();
        runCase("empty-and-single", everything(in).nullModelKind(NullModelKind.WHOLE_CHANNEL));
    }

    /** (d) Anisotropic calibration under the per-object null. */
    @Test
    public void anisotropicPerObject() throws IOException {
        GoldenFixtures.Inputs in = GoldenFixtures.anisotropic();
        runCase("anisotropic-per-object", base(in)
                .preset("Object colocalization")
                .discovery(true)
                .nullModelKind(NullModelKind.PER_OBJECT));
    }

    /** The whole-channel null of case (a) under the object methods, at 1 and 4 workers. */
    @Test
    public void wholeChannelNullIsIdenticalAtOneAndFourWorkers() throws IOException {
        GoldenFixtures.Inputs in = GoldenFixtures.threeChannel3D();
        String one = nullDump(in, NullModelKind.WHOLE_CHANNEL, 1);
        String four = nullDump(in, NullModelKind.WHOLE_CHANNEL, 4);
        assertEquals("the chance test must not depend on the worker count", one, four);
        Golden.assertMatches("workers-whole-channel",
                Collections.singletonMap("null-distribution", one));
    }

    /** The per-object null of case (d), at 1 and 4 workers. */
    @Test
    public void perObjectNullIsIdenticalAtOneAndFourWorkers() throws IOException {
        GoldenFixtures.Inputs in = GoldenFixtures.anisotropic();
        String one = nullDump(in, NullModelKind.PER_OBJECT, 1);
        String four = nullDump(in, NullModelKind.PER_OBJECT, 4);
        assertEquals("the chance test must not depend on the worker count", one, four);
        Golden.assertMatches("workers-per-object",
                Collections.singletonMap("null-distribution", one));
    }

    // ---------- running ----------

    private static OCSParameters.Builder base(GoldenFixtures.Inputs in) {
        OCSParameters.Builder builder = OCSParameters.builder(in.labels)
                .channelNames(in.names)
                .domain(in.domain)
                .permutations(SHUFFLES)
                .seed(SEED);
        if (in.intensities != null) {
            builder.intensityImages(in.intensities);
        }
        if (in.calibration != null) {
            builder.calibration(in.calibration);
        }
        return builder;
    }

    private static OCSParameters.Builder everything(GoldenFixtures.Inputs in) {
        return base(in).preset("Discovery — everything").discovery(true);
    }

    /**
     * Runs one case and compares its tables and its written tree. A run that
     * throws is itself an output: the exception class and message are the golden.
     */
    private void runCase(String name, OCSParameters.Builder parameters) throws IOException {
        Map<String, String> files = new java.util.LinkedHashMap<String, String>();
        OCSResult result;
        try {
            result = OCS.run(parameters.build());
        } catch (RuntimeException refused) {
            files.put("error", refused.getClass().getName() + ": " + refused.getMessage() + "\n");
            Golden.assertMatches(name, files);
            return;
        }
        for (Map.Entry<String, ResultsTable> table : OCSTables.all(result).entrySet()) {
            files.put("table-" + table.getKey(), exact(table.getValue()));
        }
        File root = temp.newFolder(name);
        OCSOutputWriter.write(result, root);
        List<String> written = new ArrayList<String>();
        collect(root, "", written);
        Collections.sort(written);
        for (int i = 0; i < written.size(); i++) {
            String relative = written.get(i);
            String text = new String(Files.readAllBytes(new File(root, relative).toPath()),
                    StandardCharsets.UTF_8);
            files.put("tree/" + relative, normaliseTree(relative, text));
        }
        Golden.assertMatches(name, files);
    }

    /** Every per-permutation statistic, bit for bit, plus the p values. */
    private static String nullDump(GoldenFixtures.Inputs in, NullModelKind kind,
            int workers) {
        OCSParameters parameters = base(in).preset("Object colocalization").build();
        EngineInputs inputs = parameters.toEngineInputs();
        EngineRegistry registry = EngineRegistry.createDefault();
        List<ColocEngine> engines = new ArrayList<ColocEngine>();
        for (String id : parameters.methodIds()) {
            engines.add(registry.byId(id));
        }
        List<NullModelResult> results = NullModelRunner.builder()
                .permutations(SHUFFLES).seed(SEED).kind(kind).workers(workers)
                .build().run(engines, inputs, EngineProgress.SILENT);
        StringBuilder text = new StringBuilder();
        text.append("method,source,target,ran,observed,p,pDepletion,pTwoSided,permuted\n");
        for (int i = 0; i < results.size(); i++) {
            NullModelResult r = results.get(i);
            text.append(r.engineId()).append(',')
                    .append(r.direction().sourceName()).append(',')
                    .append(r.direction().targetName()).append(',')
                    .append(r.ran()).append(',')
                    .append(Double.toString(r.observed())).append(',')
                    .append(Double.toString(r.p())).append(',')
                    .append(Double.toString(r.pDepletion())).append(',')
                    .append(Double.toString(r.pTwoSided())).append(',');
            double[] permuted = r.permutedValues();
            for (int p = 0; p < permuted.length; p++) {
                if (p > 0) {
                    text.append(' ');
                }
                text.append(Double.toString(permuted[p]));
            }
            text.append('\n');
        }
        return text.toString();
    }

    // ---------- canonical text ----------

    /**
     * A table as text with every number at full precision. A cell that holds a
     * number is written with {@link Double#toString}; a cell that holds text is
     * written as the text. Column order is the table's own.
     */
    static String exact(ResultsTable table) {
        StringBuilder text = new StringBuilder();
        String[] headings = table.getHeadings();
        for (int c = 0; c < headings.length; c++) {
            if (c > 0) {
                text.append(',');
            }
            text.append(CsvWriter.quote(headings[c]));
        }
        text.append('\n');
        for (int row = 0; row < table.size(); row++) {
            for (int c = 0; c < headings.length; c++) {
                if (c > 0) {
                    text.append(',');
                }
                text.append(CsvWriter.quote(cell(table, headings[c], row)));
            }
            text.append('\n');
        }
        return text.toString();
    }

    private static String cell(ResultsTable table, String heading, int row) {
        double value = table.getValue(heading, row);
        if (!Double.isNaN(value)) {
            return Double.toString(value);
        }
        String text = table.getStringValue(heading, row);
        if (text == null || "NaN".equals(text)) {
            return "NaN";
        }
        return text;
    }

    private static String normaliseTree(String relative, String text) {
        String lf = text.replace("\r\n", "\n");
        if (relative.endsWith(OCSOutputWriter.README)) {
            return lf.replaceAll("(?m)^Version .*$", "Version <normalised>");
        }
        if (relative.endsWith(OCSOutputWriter.RUN_RECORD)) {
            return lf.replaceAll("(?m)^  \"version\": \".*\"$", "  \"version\": \"<normalised>\"");
        }
        return lf;
    }

    private static void collect(File dir, String prefix, List<String> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (int i = 0; i < children.length; i++) {
            String relative = prefix + children[i].getName();
            if (children[i].isDirectory()) {
                collect(children[i], relative + "/", out);
            } else {
                out.add(relative);
            }
        }
    }

    // ---------- the comparison ----------

    /** Reads, compares and (with the update flag) writes golden files. */
    static final class Golden {

        static final String UPDATE_FLAG = "ocs.golden.update";

        private Golden() {
        }

        static File root() {
            return new File(System.getProperty("ocs.golden.dir", "src/test/resources/golden"));
        }

        /**
         * @param files file name (without {@code .csv}/{@code .txt}) to its
         *              canonical text
         */
        static void assertMatches(String caseName, Map<String, String> files)
                throws IOException {
            File dir = new File(root(), caseName);
            boolean update = Boolean.getBoolean(UPDATE_FLAG);
            if (update) {
                deleteRecursively(dir);
            }
            List<String> failures = new ArrayList<String>();
            List<String> expectedNames = new ArrayList<String>();
            for (Map.Entry<String, String> entry : files.entrySet()) {
                String fileName = fileNameFor(entry.getKey());
                expectedNames.add(fileName);
                File golden = new File(dir, fileName);
                if (update) {
                    golden.getParentFile().mkdirs();
                    Files.write(golden.toPath(), entry.getValue().getBytes(StandardCharsets.UTF_8));
                    continue;
                }
                if (!golden.isFile()) {
                    failures.add(caseName + "/" + fileName + ": no golden file; run with -D"
                            + UPDATE_FLAG + "=true only if this output is new and intended");
                    continue;
                }
                String expected = new String(Files.readAllBytes(golden.toPath()),
                        StandardCharsets.UTF_8).replace("\r\n", "\n");
                String difference = firstDifference(expected, entry.getValue());
                if (difference != null) {
                    failures.add(caseName + "/" + fileName + ": " + difference);
                }
            }
            if (!update && dir.isDirectory()) {
                List<String> present = new ArrayList<String>();
                collect(dir, "", present);
                for (int i = 0; i < present.size(); i++) {
                    if (!expectedNames.contains(present.get(i))) {
                        failures.add(caseName + "/" + present.get(i)
                                + ": golden file exists but this run no longer produces it");
                    }
                }
            }
            if (!failures.isEmpty()) {
                StringBuilder message = new StringBuilder("golden output changed in "
                        + failures.size() + " file(s):");
                for (int i = 0; i < failures.size(); i++) {
                    message.append("\n  ").append(failures.get(i));
                }
                fail(message.toString());
            }
        }

        static String fileNameFor(String key) {
            if (key.startsWith("tree/")) {
                return key;
            }
            return key + (key.equals("error") ? ".txt" : ".csv");
        }

        /** The first differing line, numbered from 1 with the header as line 1. */
        static String firstDifference(String expected, String actual) {
            if (expected.equals(actual)) {
                return null;
            }
            String[] want = expected.split("\n", -1);
            String[] got = actual.split("\n", -1);
            int lines = Math.max(want.length, got.length);
            for (int i = 0; i < lines; i++) {
                String w = i < want.length ? want[i] : "<missing>";
                String g = i < got.length ? got[i] : "<missing>";
                if (!w.equals(g)) {
                    String where = i == 0 ? "header" : "row " + i;
                    return "line " + (i + 1) + " (" + where + ") differs\n    expected: "
                            + w + "\n    actual:   " + g;
                }
            }
            return "differs";
        }

        private static void deleteRecursively(File file) {
            File[] children = file.listFiles();
            if (children != null) {
                for (int i = 0; i < children.length; i++) {
                    deleteRecursively(children[i]);
                }
            }
            file.delete();
        }
    }
}
