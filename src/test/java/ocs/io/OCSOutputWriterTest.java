package ocs.io;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.process.ShortProcessor;
import ocs.OCS;
import ocs.OCSParameters;
import ocs.OCSResult;
import ocs.nullmodel.NullModelKind;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What lands on disk.
 *
 * <p>Everything here outlives the session that made it, which changes what is
 * worth testing: not that a number is right — that is settled upstream — but
 * that somebody opening the folder in a year can tell what produced it, repeat
 * it, and see what did not run.
 */
public class OCSOutputWriterTest {

    private static final int SIZE = 24;

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @BeforeClass
    public static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    public void theRunRecordCarriesTheSeedAndTheMethods() throws Exception {
        // A result nobody can reproduce is not evidence, and the seed is the
        // one setting that cannot be inferred from anything else in the folder.
        File root = temporary.newFolder("out");
        OCSOutputWriter.write(run(45.0), root);

        String record = read(new File(new File(root, OCSOutputWriter.FOLDER),
                OCSOutputWriter.RUN_RECORD));
        assertTrue(record, record.contains("\"seed\""));
        assertTrue(record, record.contains("volume-overlap"));
        assertTrue("the setting that was used, not the shipped default",
                record.contains("45"));
    }

    @Test
    public void theRunRecordListsWhatDidNotRunAlongsideWhatDid() throws Exception {
        // A record naming thirteen methods beside a table holding eleven is a
        // record that misleads.
        File root = temporary.newFolder("out");
        OCSResult result = OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                .methods("volume-overlap", "per-object-intensity")
                .build());
        OCSOutputWriter.write(result, root);

        String record = read(new File(new File(root, OCSOutputWriter.FOLDER),
                OCSOutputWriter.RUN_RECORD));
        assertTrue(record, record.contains("per-object-intensity"));
        assertTrue(record, record.contains("intensity images"));
    }

    @Test
    public void theReadmeSaysWhatEachFolderHolds() throws Exception {
        File root = temporary.newFolder("out");
        OCSOutputWriter.write(run(30.0), root);

        String readme = read(new File(new File(root, OCSOutputWriter.FOLDER),
                OCSOutputWriter.README));
        assertTrue(readme, readme.contains("per-object"));
        assertTrue("blank must be explained, because it is not zero",
                readme.contains("never means zero"));
    }

    @Test
    public void aRunNameThatIsNotAFilenameStillWrites() throws Exception {
        // Window titles routinely contain colons and slashes, and a batch that
        // failed on the one field named "C1:C2" would be maddening to diagnose.
        File root = temporary.newFolder("out");
        OCSResult result = OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                .methods("volume-overlap")
                .sourceName("field 3: C1/C2 <raw>")
                .build());
        OCSOutputWriter.write(result, root);

        File perObject = new File(new File(root, OCSOutputWriter.FOLDER),
                "per-object");
        assertEquals(1, perObject.listFiles().length);
        assertFalse(perObject.listFiles()[0].getName(),
                perObject.listFiles()[0].getName().contains(":"));
    }

    @Test
    public void aRunWithNoUsableNameStillGetsAFile() {
        assertEquals("run", OCSOutputWriter.safeName(""));
        assertEquals("run", OCSOutputWriter.safeName(null));
        assertEquals("run", OCSOutputWriter.safeName("..."));
    }

    @Test
    public void theCombinedFileKeepsItsMethodNamesRatherThanBlankingThem()
            throws Exception {
        // ResultsTable returns NaN for a text column, so reading everything as a
        // number would silently empty exactly the columns that make the rest
        // interpretable.
        File root = temporary.newFolder("out");
        OCSResult result = OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                .methods("volume-overlap", "cpc")
                .thresholdSweep(true)
                .build());
        OCSOutputWriter.append(result, root, true);

        String csv = read(new File(new File(new File(root, OCSOutputWriter.FOLDER),
                "batch"), "threshold-sensitivity.csv"));
        assertTrue(csv, csv.contains("volume-overlap"));
        assertTrue(csv, csv.contains("cpc"));
    }

    @Test
    public void appendingASecondRunDoesNotRepeatTheHeader() throws Exception {
        File root = temporary.newFolder("out");
        OCSOutputWriter.append(run(30.0), root, true);
        OCSOutputWriter.append(run(30.0), root, false);

        File combined = new File(new File(new File(root, OCSOutputWriter.FOLDER),
                "batch"), "summary.csv");
        String[] lines = read(combined).split("\n");
        assertEquals("one header, then two directions twice", 5, lines.length);
        assertTrue(lines[0], lines[0].contains("Image"));
        assertFalse(lines[3], lines[3].contains("Source Channel"));
    }

    // ---------- caveats in the record and the README ----------

    @Test
    public void theChanceTestIsDescribedInFullInTheRunRecordAndTheReadme()
            throws Exception {
        File root = temporary.newFolder("out");
        OCSResult result = OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                .methods("volume-overlap", "bounding-box", "jaccard-dice")
                .domain(Arrays.<Roi>asList(new Roi(0, 0, SIZE, SIZE)))
                .nullModel(true)
                .permutations(19)
                .seed(77L)
                .build());
        OCSOutputWriter.write(result, root);
        String record = read(new File(new File(root, OCSOutputWriter.FOLDER),
                OCSOutputWriter.RUN_RECORD));
        String readme = read(new File(new File(root, OCSOutputWriter.FOLDER),
                OCSOutputWriter.README));

        assertTrue(record, record.contains("\"nullModelKind\": \"whole-channel\""));
        assertTrue(record, record.contains("\"chanceTest\": {"));
        assertTrue(record, record.contains("\"kind\": \"whole-channel\""));
        assertTrue(record, record.contains("\"hypothesis\": \"the two channels meet"));
        assertTrue(record, record.contains("\"shuffles\": 19"));
        assertTrue(record, record.contains("\"seed\": 77"));
        assertTrue(record, record.contains("\"seedContractVersion\": "
                + ocs.nullmodel.NullModelRunner.SEED_CONTRACT_VERSION));
        assertTrue("min(1, 2/(19+1)) = 0.1: " + record,
                record.contains("\"nominalPFloor\": 0.1,"));
        assertTrue(record, record.contains("\"crossFieldInflation\": 1.45"));
        assertTrue(record, record.contains("\"noPValue\": [\"bounding-box\"]"));
        assertTrue(record, record.contains("\"sweepWidth\": 0.2"));

        List<String> caveats = OCSOutputWriter.caveats(result);
        assertEquals(caveats.toString(), 5, caveats.size());
        assertTrue(caveats.get(0), caveats.get(0).startsWith("Chance test: whole-channel"));
        assertTrue(caveats.get(1), caveats.get(1).contains("min(1, 2/(19+1)) = 0.1"));
        assertTrue(caveats.get(2), caveats.get(2).contains("1.45 times"));
        assertTrue(caveats.get(3), caveats.get(3).startsWith("bounding-box has no p"));
        assertTrue(caveats.get(4), caveats.get(4).startsWith("jaccard-dice"));
        for (int i = 0; i < caveats.size(); i++) {
            assertTrue("README carries caveat " + i, readme.contains(caveats.get(i)));
            assertTrue("run record carries caveat " + i, record.contains(caveats.get(i)));
        }
        assertFalse("no spatial method ran, so no planar note: " + readme,
                readme.contains("planar"));
    }

    @Test
    public void thePerObjectNullStatesItsOwnHypothesisAndNoInflation() throws Exception {
        OCSResult result = OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                .methods("volume-overlap")
                .domain(Arrays.<Roi>asList(new Roi(0, 0, SIZE, SIZE)))
                .nullModel(true)
                .nullModelKind(NullModelKind.PER_OBJECT)
                .permutations(9)
                .build());
        String record = writeAndReadRecord(result);
        assertTrue(record, record.contains("\"kind\": \"per-object\""));
        assertTrue(record, record.contains("placed independently inside the region"));
        assertTrue(record, record.contains("\"crossFieldInflation\": null"));
        assertTrue(record, record.contains("\"noPValue\": []"));
        assertFalse(record, record.contains("1.45"));
    }

    @Test
    public void withoutAChanceTestTheRecordSaysSoAndCarriesNoChanceCaveats()
            throws Exception {
        OCSResult result = run(45.0);
        String record = writeAndReadRecord(result);
        assertTrue(record, record.contains("\"chanceTest\": null"));
        assertTrue(record, record.contains("\"caveats\": []"));
        assertTrue(OCSOutputWriter.caveats(result).isEmpty());
    }

    @Test
    public void thePlanarNoteAppearsOnlyWhenASpatialMethodRan() throws Exception {
        OCSResult spatial = OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                .methods("volume-overlap", "cross-g")
                .domain(Arrays.<Roi>asList(new Roi(0, 0, SIZE, SIZE)))
                .build());
        String record = writeAndReadRecord(spatial);
        assertTrue(record, record.contains("planar"));
        assertTrue(record, record.contains("xy centroid"));
        assertEquals(1, OCSOutputWriter.caveats(spatial).size());

        OCSResult asked = OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                .methods("volume-overlap", "cross-g")
                .build());
        assertFalse("cross-g was asked for but could not run without a region: "
                + asked.skipped(), writeAndReadRecord(asked).contains("planar"));
    }

    private String writeAndReadRecord(OCSResult result) throws Exception {
        File root = temporary.newFolder();
        OCSOutputWriter.write(result, root);
        return read(new File(new File(root, OCSOutputWriter.FOLDER),
                OCSOutputWriter.RUN_RECORD));
    }

    // ---------- fixtures ----------

    private static OCSResult run(double threshold) {
        return OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                .methods("volume-overlap")
                .threshold("volume-overlap", threshold)
                .build());
    }

    private static String read(File file) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), "UTF-8"));
        try {
            StringBuilder text = new StringBuilder();
            String line = reader.readLine();
            while (line != null) {
                text.append(line).append('\n');
                line = reader.readLine();
            }
            return text.toString();
        } finally {
            reader.close();
        }
    }

    private static ImagePlus labels(String title) {
        ShortProcessor processor = new ShortProcessor(SIZE, SIZE);
        for (int y = 3; y < 11; y++) {
            for (int x = 3; x < 11; x++) {
                processor.set(x, y, 1);
            }
        }
        for (int y = 14; y < 20; y++) {
            for (int x = 14; x < 20; x++) {
                processor.set(x, y, 2);
            }
        }
        return new ImagePlus(title, processor);
    }
}
