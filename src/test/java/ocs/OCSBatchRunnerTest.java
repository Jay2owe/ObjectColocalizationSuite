package ocs;

import ij.IJ;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ShortProcessor;
import ocs.engine.EngineProgress;
import ocs.io.OCSOutputWriter;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A folder of images, run end to end.
 *
 * <p>The batch layer is where a mistake is most expensive, because it is the
 * layer that gets started at six in the evening. What is worth pinning is
 * therefore not the arithmetic — every layer below has its own tests — but the
 * behaviours that decide whether somebody still has their results in the
 * morning: one bad file must not lose the rest, files must be paired by name and
 * not by luck, and a second run must not analyse the first run's output.
 */
public class OCSBatchRunnerTest {

    private static final int SIZE = 24;

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    private File labels;
    private File output;

    @BeforeClass
    public static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Before
    public void folders() throws Exception {
        labels = temporary.newFolder("labels");
        output = temporary.newFolder("output");
    }

    // ---------- grouping ----------

    @Test
    public void filesAgreeingOnEverythingButTheChannelAreOneField() throws Exception {
        writeLabels("sample1_C1.tif");
        writeLabels("sample1_C2.tif");
        writeLabels("sample2_C1.tif");
        writeLabels("sample2_C2.tif");

        OCSBatchResult result = OCSBatchRunner.run(parameters().build());

        assertEquals(2, result.fieldsRun());
        assertTrue(result.failures().toString(), result.failures().isEmpty());
    }

    @Test
    public void aStrayFileWithNoPartnerIsSkippedRatherThanRunAlone() {
        // One channel is not a colocalization question. Running it would produce
        // a row of NaNs that reads like a measurement.
        writeLabels("sample1_C1.tif");
        writeLabels("sample1_C2.tif");
        writeLabels("orphan_C1.tif");

        OCSBatchResult result = OCSBatchRunner.run(parameters().build());
        assertEquals(1, result.fieldsRun());
    }

    @Test
    public void aPatternMatchingNothingFailsBeforeAnyFileIsOpened() {
        writeLabels("sample1_C1.tif");
        writeLabels("sample1_C2.tif");
        try {
            OCSBatchRunner.run(OCSBatchParameters
                    .builder(labels, "(.*)_Z(\\d)\\.tif", 2).build());
            fail("a pattern that matches nothing must say so");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("matched"));
        }
    }

    @Test
    public void aVaryingGroupThePatternDoesNotHaveIsRefused() {
        // The commonest batch mistake after a wrong pattern, and silently
        // collapsing every file into one group would look like it worked.
        try {
            OCSBatchRunner.run(OCSBatchParameters
                    .builder(labels, "(.*)_C(\\d)\\.tif", 5).build());
            fail("group 5 does not exist in that pattern");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("capture group"));
        }
    }

    @Test
    public void thePreviewSaysWhatWillRunBeforeAnythingDoes() {
        writeLabels("sample1_C1.tif");
        writeLabels("sample1_C2.tif");

        String preview = OCSBatchRunner.preview(parameters().build());
        assertTrue(preview, preview.contains("runnable"));
    }

    // ---------- surviving bad data ----------

    @Test
    public void oneUnreadableFileDoesNotLoseTheRest() throws Exception {
        // A batch that died at the first bad field would be started overnight
        // and found dead in the morning.
        writeLabels("good1_C1.tif");
        writeLabels("good1_C2.tif");
        writeLabels("good2_C1.tif");
        writeLabels("good2_C2.tif");
        writeGarbage("bad1_C1.tif");
        writeGarbage("bad1_C2.tif");

        OCSBatchResult result = OCSBatchRunner.run(parameters().build());

        assertEquals(2, result.fieldsRun());
        assertEquals(1, result.failures().size());
        assertTrue(result.failures().get(0).name(),
                result.failures().get(0).name().startsWith("bad1"));
        assertNotNull("a failure must say what went wrong",
                result.failures().get(0).reason());
    }

    @Test
    public void cancellingStopsTheBatchAndSaysSo() {
        // A cancelled batch and a batch where most fields failed leave the same
        // short list, and only one of them means the data is wrong.
        writeLabels("a_C1.tif");
        writeLabels("a_C2.tif");
        writeLabels("b_C1.tif");
        writeLabels("b_C2.tif");

        OCSBatchResult result = OCSBatchRunner.run(parameters().build(),
                new EngineProgress() {
                    @Override
                    public void report(String stage, double fraction) {
                    }

                    @Override
                    public boolean isCancelled() {
                        return true;
                    }
                });

        assertTrue(result.wasCancelled());
        assertEquals(0, result.fieldsRun());
    }

    // ---------- pairing intensity images ----------

    @Test
    public void intensityImagesArePairedByNameAndNotByPosition() throws Exception {
        // Pairing by position would weight one sample's objects by another
        // sample's intensities, and every number after that is confidently wrong.
        File intensity = temporary.newFolder("intensity");
        writeLabels("sample1_C1.tif");
        writeLabels("sample1_C2.tif");
        writeIntensity(intensity, "sample1_C1_raw.tif");
        writeIntensity(intensity, "sample1_C2_raw.tif");

        OCSBatchResult result = OCSBatchRunner.run(parameters()
                .intensity(intensity, "(.*)_C(\\d)_raw\\.tif")
                .template(OCSParameters.builder(blank(), blank())
                        .methods("per-object-intensity").build())
                .build());

        assertEquals(1, result.fieldsRun());
        assertTrue("the intensity method must have run, not been skipped",
                result.results().get(0).skipped().isEmpty());
    }

    @Test
    public void aMissingIntensityPartnerFailsThatFieldRatherThanRunningWithout() {
        // Running the field without its intensity image would produce a table
        // that looks complete and is not.
        File intensity;
        try {
            intensity = temporary.newFolder("intensity");
        } catch (Exception cannotCreate) {
            throw new IllegalStateException(cannotCreate);
        }
        writeLabels("sample1_C1.tif");
        writeLabels("sample1_C2.tif");
        writeIntensity(intensity, "sample1_C1_raw.tif");

        OCSBatchResult result = OCSBatchRunner.run(parameters()
                .intensity(intensity, "(.*)_C(\\d)_raw\\.tif")
                .build());

        assertEquals(0, result.fieldsRun());
        assertEquals(1, result.failures().size());
        assertTrue(result.failures().get(0).reason(),
                result.failures().get(0).reason().contains("no intensity image"));
    }

    // ---------- what lands on disk ----------

    @Test
    public void autoSaveWritesOneFolderPerKindOfTable() throws Exception {
        // Not one folder per image: four hundred fields would be four hundred
        // directories of eight files, which nothing can glob.
        writeLabels("sample1_C1.tif");
        writeLabels("sample1_C2.tif");

        OCSBatchResult result = OCSBatchRunner.run(
                parameters().saveDir(output).autoSave(true).build());

        File folder = new File(output, OCSOutputWriter.FOLDER);
        assertTrue(folder.isDirectory());
        assertTrue(new File(folder, "per-object").isDirectory());
        assertTrue(new File(folder, "summary").isDirectory());
        assertTrue(new File(folder, OCSOutputWriter.README).isFile());
        assertTrue(new File(folder, OCSOutputWriter.RUN_RECORD).isFile());
        assertEquals(folder.getParentFile(), result.outputFolder());
    }

    @Test
    public void everyFieldAlsoLandsInOneFilePerKind() throws Exception {
        // Both are wanted, by different people: somebody chasing one bad field
        // wants that field's file, somebody fitting a model wants all the rows.
        writeLabels("a_C1.tif");
        writeLabels("a_C2.tif");
        writeLabels("b_C1.tif");
        writeLabels("b_C2.tif");

        OCSBatchRunner.run(parameters().saveDir(output).autoSave(true).build());

        File combined = new File(new File(new File(output, OCSOutputWriter.FOLDER),
                "batch"), "summary.csv");
        assertTrue(combined.isFile());

        int lines = countLines(combined);
        // One header, then two directions for each of two fields.
        assertEquals(5, lines);
    }

    @Test
    public void aSecondRunDoesNotAnalyseTheFirstRunsOutput() throws Exception {
        // The save folder sits inside the label folder often enough that this
        // would otherwise happen on somebody's second run, and the CSVs would
        // fail to open as images one by one.
        writeLabels("sample1_C1.tif");
        writeLabels("sample1_C2.tif");

        OCSBatchParameters parameters = parameters()
                .saveDir(labels).autoSave(true).recursive(true).build();
        OCSBatchRunner.run(parameters);
        OCSBatchResult second = OCSBatchRunner.run(parameters);

        assertEquals(1, second.fieldsRun());
        assertTrue(second.failures().toString(), second.failures().isEmpty());
    }

    @Test
    public void withoutAutoSaveNothingIsWritten() throws Exception {
        writeLabels("sample1_C1.tif");
        writeLabels("sample1_C2.tif");

        OCSBatchResult result = OCSBatchRunner.run(
                parameters().saveDir(output).autoSave(false).build());

        assertEquals(1, result.fieldsRun());
        assertFalse(new File(output, OCSOutputWriter.FOLDER).exists());
        assertNull(result.outputFolder());
    }

    // ---------- settings reach every field ----------

    @Test
    public void theTemplateSettingsApplyToEveryField() throws Exception {
        writeLabels("a_C1.tif");
        writeLabels("a_C2.tif");
        writeLabels("b_C1.tif");
        writeLabels("b_C2.tif");

        OCSBatchResult result = OCSBatchRunner.run(parameters()
                .template(OCSParameters.builder(blank(), blank())
                        .methods("volume-overlap")
                        .threshold("volume-overlap", 45.0)
                        .build())
                .build());

        assertEquals(2, result.fieldsRun());
        for (int i = 0; i < result.results().size(); i++) {
            assertEquals(45.0, ((ocs.engine.ThresholdBearing)
                    result.results().get(i).engines().get(0)).threshold(), 1e-9);
        }
    }

    @Test
    public void everyFieldIsNamedAfterItselfAndNotAfterTheFirstImage() {
        // Otherwise every row in the combined file carries the same name and the
        // batch cannot be taken apart again.
        writeLabels("alpha_C1.tif");
        writeLabels("alpha_C2.tif");
        writeLabels("beta_C1.tif");
        writeLabels("beta_C2.tif");

        OCSBatchResult result = OCSBatchRunner.run(parameters().build());

        String first = result.results().get(0).parameters().sourceName();
        String second = result.results().get(1).parameters().sourceName();
        assertFalse(first + " vs " + second, first.equals(second));
    }

    // ---------- tier-V verdict agreement ----------

    @Test
    public void verdictAgreementIsAssembledAcrossTheFieldsOfABatch() throws Exception {
        String[] fields = {"a", "b", "c"};
        for (int i = 0; i < fields.length; i++) {
            writeLabels(fields[i] + "_C1.tif");
            writeLabels(fields[i] + "_C2.tif");
        }
        File region = new File(temporary.getRoot(), "region.roi");
        ij.io.RoiEncoder.save(new ij.gui.Roi(0, 0, SIZE, SIZE), region.getAbsolutePath());

        OCSBatchResult result = OCSBatchRunner.run(parameters()
                .sharedRegionRoi(region)
                .autoSave(true)
                .saveDir(output)
                .template(OCSParameters.builder(blank(), blank())
                        .methods("cpc", "volume-overlap")
                        .nullModel(true)
                        .permutations(19)
                        .build())
                .build());

        assertEquals(result.failures().toString(), 3, result.fieldsRun());
        java.util.List<ocs.agreement.AgreementCell> cells =
                result.verdictAgreement().get("C1 -> C2");
        assertNotNull(result.verdictAgreement().toString(), cells);
        assertEquals(1, cells.size());
        ocs.agreement.AgreementCell cell = cells.get(0);
        assertEquals(ocs.agreement.Tier.VERDICT, cell.tier());
        assertEquals("cpc", cell.engineA());
        assertEquals("volume-overlap", cell.engineB());
        assertEquals("one verdict per field", 3, cell.n());
        assertTrue("three fields is fewer than the ten a verdict kappa needs",
                cell.isUnderpowered());
        assertNotNull(result.verdictAgreement().get("C2 -> C1"));

        File csv = new File(new File(new File(output, OCSOutputWriter.FOLDER), "batch"),
                OCSBatchRunner.VERDICT_AGREEMENT_FILE);
        assertTrue(csv.getPath(), csv.isFile());
        assertEquals("header and one row per direction", 3, countLines(csv));
    }

    @Test
    public void withoutAnyVerdictThereIsNoVerdictAgreement() throws Exception {
        writeLabels("a_C1.tif");
        writeLabels("a_C2.tif");
        writeLabels("b_C1.tif");
        writeLabels("b_C2.tif");
        OCSBatchResult result = OCSBatchRunner.run(parameters()
                .autoSave(true)
                .saveDir(output)
                .build());
        assertTrue(result.verdictAgreement().toString(),
                result.verdictAgreement().isEmpty());
        assertFalse(new File(new File(new File(output, OCSOutputWriter.FOLDER), "batch"),
                OCSBatchRunner.VERDICT_AGREEMENT_FILE).exists());
    }

    // ---------- every setting reaches every field (stage 06) ----------

    /** Builder methods that describe the analysis and must reach every field. */
    private static final String[] CARRIED = {"methods", "thresholds",
        "bidirectional", "nullModel", "agreement", "thresholdSweep", "permutations",
        "seed", "alpha", "flipThreshold", "minCompareN", "kappaLimit",
        "calibration", "discovery", "sweepWidth", "nullModelKind"};

    /**
     * Builder methods that are not a per-field copy: the field's own images,
     * names and region, and shorthands that end in the carried properties.
     */
    private static final String[] NOT_PER_FIELD = {"intensityImages",
        "channelNames", "domain", "sourceName", "threshold", "preset",
        "allMethods", "from", "build"};

    /** Getters that belong to the field, not to the template. */
    private static final String[] FIELD_OWN = {"labelImages", "intensityImages",
        "channelNames", "domain", "sourceName", "toEngineInputs"};

    @Test
    public void theBuilderGainsNoSettingTheBatchDoesNotKnowAbout() {
        // A new setter is a new setting. If the batch template copy is not
        // taught about it, every batch silently runs at its default — which is
        // exactly how sweep_width= and the null-model kind went missing.
        java.util.Set<String> known = new java.util.HashSet<String>(
                java.util.Arrays.asList(CARRIED));
        known.addAll(java.util.Arrays.asList(NOT_PER_FIELD));
        java.lang.reflect.Method[] methods = OCSParameters.Builder.class.getDeclaredMethods();
        for (int i = 0; i < methods.length; i++) {
            if (java.lang.reflect.Modifier.isPublic(methods[i].getModifiers())) {
                assertTrue("OCSParameters.Builder." + methods[i].getName()
                        + " is new: carry it in OCSBatchRunner.runOne and list it"
                        + " in CARRIED, or list it in NOT_PER_FIELD",
                        known.contains(methods[i].getName()));
            }
        }
    }

    @Test
    public void everyTemplateSettingReachesEveryField() throws Exception {
        writeLabels("a_C1.tif");
        writeLabels("a_C2.tif");
        writeLabels("b_C1.tif");
        writeLabels("b_C2.tif");
        File region = new File(temporary.getRoot(), "region.roi");
        ij.io.RoiEncoder.save(new ij.gui.Roi(0, 0, SIZE, SIZE), region.getAbsolutePath());
        ij.measure.Calibration calibration = new ij.measure.Calibration();
        calibration.pixelWidth = 0.5;
        calibration.pixelHeight = 0.5;
        calibration.setUnit("um");
        // Every value away from its default, so a dropped copy shows.
        OCSParameters template = OCSParameters.builder(blank(), blank())
                .methods("volume-overlap", "containment")
                .threshold("volume-overlap", 45.0)
                .bidirectional(false)
                .discovery(true)
                .permutations(7)
                .nullModelKind(ocs.nullmodel.NullModelKind.PER_OBJECT)
                .seed(12345L)
                .alpha(0.1)
                .flipThreshold(0.3)
                .sweepWidth(0.35)
                .minCompareN(5)
                .kappaLimit(0.5)
                .calibration(calibration)
                .build();

        OCSBatchResult result = OCSBatchRunner.run(parameters()
                .sharedRegionRoi(region).template(template).build());

        assertEquals(result.failures().toString(), 2, result.fieldsRun());
        java.util.Set<String> own = new java.util.HashSet<String>(
                java.util.Arrays.asList(FIELD_OWN));
        java.lang.reflect.Method[] getters = OCSParameters.class.getDeclaredMethods();
        int compared = 0;
        for (int f = 0; f < result.results().size(); f++) {
            OCSParameters field = result.results().get(f).parameters();
            for (int i = 0; i < getters.length; i++) {
                java.lang.reflect.Method getter = getters[i];
                if (!java.lang.reflect.Modifier.isPublic(getter.getModifiers())
                        || java.lang.reflect.Modifier.isStatic(getter.getModifiers())
                        || getter.getParameterTypes().length != 0
                        || own.contains(getter.getName())) {
                    continue;
                }
                assertEquals(getter.getName() + " did not reach field " + f,
                        getter.invoke(template), getter.invoke(field));
                compared++;
            }
        }
        assertTrue("compared " + compared, compared >= 2 * CARRIED.length - 2);
    }

    // ---------- memory: summaries, not tables (stage 06) ----------

    @Test
    public void withAutoSaveTheBatchKeepsTheSummaryAndNotEveryFieldsTables()
            throws Exception {
        // Four hundred fields' per-object tables held until the end is the whole
        // heap. Once a field is on disk, only its name and verdicts are needed.
        int fields = 12;
        for (int i = 0; i < fields; i++) {
            writeLabels("f" + i + "_C1.tif");
            writeLabels("f" + i + "_C2.tif");
        }

        OCSBatchResult result = OCSBatchRunner.run(
                parameters().saveDir(output).autoSave(true).build());

        assertEquals(fields, result.fieldsRun());
        assertEquals(fields, result.fieldNames().size());
        assertEquals("f0_C", result.fieldNames().get(0));
        assertTrue("saved fields must not be held in memory",
                result.results().isEmpty());
        File perObject = new File(new File(output, OCSOutputWriter.FOLDER), "per-object");
        assertEquals(fields, perObject.list().length);
        File records = new File(new File(output, OCSOutputWriter.FOLDER),
                OCSOutputWriter.FIELD_RECORDS);
        assertEquals("one run record per field, not only the last field's",
                fields, records.list().length);
    }

    @Test
    public void withoutAutoSaveTheResultsAreKeptBecauseTheyAreNowhereElse() {
        writeLabels("a_C1.tif");
        writeLabels("a_C2.tif");

        OCSBatchResult result = OCSBatchRunner.run(parameters().build());

        assertEquals(1, result.results().size());
        assertEquals(1, result.fieldNames().size());
    }

    // ---------- saving (stage 06) ----------

    @Test
    public void aSaveFolderThatCannotBeCreatedIsRefusedBeforeAnyFieldRuns()
            throws Exception {
        writeLabels("a_C1.tif");
        writeLabels("a_C2.tif");
        File notAFolder = temporary.newFile("plain-file.txt");
        try {
            OCSBatchRunner.run(parameters()
                    .saveDir(new File(notAFolder, "results")).autoSave(true).build());
            fail("a save folder inside a file cannot exist");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("cannot save"));
        }
    }

    @Test
    public void aSaveFolderOnADriveThatIsNotThereIsRefused() {
        File missingDrive = missingDriveRoot();
        org.junit.Assume.assumeNotNull(missingDrive);
        writeLabels("a_C1.tif");
        writeLabels("a_C2.tif");
        try {
            OCSBatchRunner.run(parameters()
                    .saveDir(new File(missingDrive, "results")).autoSave(true).build());
            fail("a folder on a drive that is not there cannot be saved to");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("cannot save"));
        }
    }

    @Test
    public void aFieldThatCouldNotBeSavedIsAFailureAndNotARun() throws Exception {
        // Counted as run, a field whose tables never reached the disk would be
        // reported as analysed and then be missing from every combined file.
        writeLabels("a_C1.tif");
        writeLabels("a_C2.tif");
        writeLabels("b_C1.tif");
        writeLabels("b_C2.tif");
        File blocked = new File(new File(new File(output, OCSOutputWriter.FOLDER),
                "per-object"), "a_C.csv");
        assertTrue(blocked.mkdirs());

        OCSBatchResult result = OCSBatchRunner.run(
                parameters().saveDir(output).autoSave(true).build());

        assertEquals(1, result.fieldsRun());
        assertEquals(java.util.Arrays.asList("b_C"), result.fieldNames());
        assertEquals(1, result.failures().size());
        assertEquals("a_C", result.failures().get(0).name());
        File combined = new File(new File(new File(output, OCSOutputWriter.FOLDER),
                "batch"), "summary.csv");
        assertEquals("header and field b's two directions only", 3,
                countLines(combined));
    }

    @Test
    public void everyFieldsTablesShareTheCombinedFilesColumns() throws Exception {
        // Rows are appended under the first field's header, so a field whose
        // table had different columns would put its numbers under the wrong
        // names. Mixed here: a 3D field, a 2D field, and one with an empty channel.
        writeLabels("flat_C1.tif");
        writeLabels("flat_C2.tif");
        writeStack("deep_C1.tif", 3, true);
        writeStack("deep_C2.tif", 3, true);
        writeStack("empty_C1.tif", 1, true);
        writeStack("empty_C2.tif", 1, false);

        OCSBatchResult result = OCSBatchRunner.run(parameters()
                .saveDir(output).autoSave(true)
                .template(OCSParameters.builder(blank(), blank()).allMethods().build())
                .build());

        assertEquals(result.failures().toString(), 3, result.fieldsRun());
        File folder = new File(output, OCSOutputWriter.FOLDER);
        File[] combined = new File(folder, "batch").listFiles();
        int checked = 0;
        for (int i = 0; i < combined.length; i++) {
            String kind = combined[i].getName().replace(".csv", "");
            File perField = new File(folder, kind);
            if (!perField.isDirectory()) {
                continue;
            }
            String header = firstLine(combined[i]);
            File[] fieldFiles = perField.listFiles();
            for (int f = 0; f < fieldFiles.length; f++) {
                assertEquals(kind + ": " + fieldFiles[f].getName(), header,
                        firstLine(fieldFiles[f]));
                checked++;
            }
        }
        assertTrue("checked " + checked, checked >= 6);
    }

    // ---------- cancelling between fields (stage 06) ----------

    @Test
    public void cancellingBetweenFieldsKeepsWhatFinishedAndWritesNothingMore()
            throws Exception {
        writeLabels("a_C1.tif");
        writeLabels("a_C2.tif");
        writeLabels("b_C1.tif");
        writeLabels("b_C2.tif");
        final int[] fieldsStarted = {0};

        OCSBatchResult result = OCSBatchRunner.run(
                parameters().saveDir(output).autoSave(true).build(),
                new EngineProgress() {
                    @Override
                    public void report(String stage, double fraction) {
                        if (stage.equals("a_C") || stage.equals("b_C")) {
                            fieldsStarted[0]++;
                        }
                    }

                    @Override
                    public boolean isCancelled() {
                        return fieldsStarted[0] >= 2;
                    }
                });

        assertTrue(result.wasCancelled());
        assertEquals(java.util.Arrays.asList("a_C"), result.fieldNames());
        File perObject = new File(new File(output, OCSOutputWriter.FOLDER), "per-object");
        assertEquals(java.util.Arrays.asList("a_C.csv"),
                java.util.Arrays.asList(perObject.list()));
        assertEquals("no image left open", 0, ij.WindowManager.getImageCount());
    }

    // ---------- fixtures ----------

    /** A drive letter with nothing behind it, or null off Windows. */
    private static File missingDriveRoot() {
        if (File.separatorChar != '\\') {
            return null;
        }
        for (char letter = 'Q'; letter <= 'Z'; letter++) {
            File root = new File(letter + ":\\");
            if (!root.exists()) {
                return root;
            }
        }
        return null;
    }

    private static void assertNull(Object value) {
        org.junit.Assert.assertNull(value);
    }

    private OCSBatchParameters.Builder parameters() {
        return OCSBatchParameters.builder(labels, "(.*)_C(\\d)\\.tif", 2)
                .autoSave(false);
    }

    private void writeLabels(String name) {
        ShortProcessor processor = new ShortProcessor(SIZE, SIZE);
        for (int y = 3; y < 11; y++) {
            for (int x = 3; x < 11; x++) {
                processor.set(x, y, 1);
            }
        }
        IJ.saveAsTiff(new ImagePlus(name, processor),
                new File(labels, name).getAbsolutePath());
    }

    private void writeStack(String name, int slices, boolean withObject) {
        ij.ImageStack stack = new ij.ImageStack(SIZE, SIZE);
        for (int z = 0; z < slices; z++) {
            ShortProcessor processor = new ShortProcessor(SIZE, SIZE);
            if (withObject) {
                for (int y = 5; y < 12; y++) {
                    for (int x = 5; x < 12; x++) {
                        processor.set(x, y, 3);
                    }
                }
            }
            stack.addSlice(processor);
        }
        IJ.saveAsTiff(new ImagePlus(name, stack), new File(labels, name).getAbsolutePath());
    }

    private static String firstLine(File file) {
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(
                            new java.io.FileInputStream(file), "UTF-8"));
            try {
                return reader.readLine();
            } finally {
                reader.close();
            }
        } catch (Exception unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }

    private void writeIntensity(File folder, String name) {
        ByteProcessor processor = new ByteProcessor(SIZE, SIZE);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                processor.set(x, y, (x * 7 + y * 3) % 256);
            }
        }
        IJ.saveAsTiff(new ImagePlus(name, processor),
                new File(folder, name).getAbsolutePath());
    }

    /** A file that is not an image, to stand in for a corrupt acquisition. */
    private void writeGarbage(String name) {
        try {
            java.io.Writer writer = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(new File(labels, name)), "UTF-8");
            writer.write("this is not a tiff");
            writer.close();
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }

    /** A placeholder for the template's image slots, which are never read. */
    private static ImagePlus blank() {
        return new ImagePlus("blank", new ShortProcessor(4, 4));
    }

    private static int countLines(File file) {
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(
                            new java.io.FileInputStream(file), "UTF-8"));
            try {
                int lines = 0;
                while (reader.readLine() != null) {
                    lines++;
                }
                return lines;
            } finally {
                reader.close();
            }
        } catch (Exception unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }
}
