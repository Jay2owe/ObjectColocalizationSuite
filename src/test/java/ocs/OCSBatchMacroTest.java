package ocs;

import ij.IJ;
import ij.ImagePlus;
import ij.process.ShortProcessor;
import ocs.nullmodel.NullModelKind;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The batch command's macro line (stage 06).
 *
 * <p>Before this, the batch read only its folder options and {@code methods=};
 * {@code sweep_width=}, {@code null_model}, {@code permutations=} and the rest
 * of the single-run grammar were not read at all, {@code subfolders} was found
 * anywhere in the text (including inside a path), {@code folder=} could be read
 * out of {@code intensity_folder=}, and a batch recorded from the dialog could
 * not be replayed.
 */
public class OCSBatchMacroTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @BeforeClass
    public static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    public void analysisOptionsReachTheTemplate() {
        OCSBatchParameters parameters = OCSBatchMacro.parse("folder=/data/labels"
                + " region_roi=/data/region.zip methods=[volume-overlap,cpc]"
                + " null_model permutations=99 seed=7 sweep_width=0.35"
                + " threshold_volume-overlap=45 alpha=0.01");
        OCSParameters template = parameters.template();
        assertEquals(0.35, template.sweepWidth(), 0.0);
        assertTrue(template.runsNullModel());
        assertEquals(99, template.permutations());
        assertEquals(7L, template.seed());
        assertEquals(0.01, template.alpha(), 0.0);
        assertEquals(45.0, template.thresholds().get("volume-overlap"), 0.0);
        assertEquals(java.util.Arrays.asList("volume-overlap", "cpc"),
                template.methodIds());
        assertEquals(new File("/data/region.zip"), parameters.sharedRegionRoi());
    }

    @Test
    public void anIntensityFolderWithoutAPatternUsesTheLabelPattern() {
        OCSBatchParameters parameters = OCSBatchMacro.parse("folder=/data/labels"
                + " pattern=(.*)_C(\\d)\\.tif intensity_folder=/data/raw");
        assertTrue("an intensity folder on its own must not be ignored",
                parameters.usesIntensityImages());
        assertEquals("(.*)_C(\\d)\\.tif", parameters.intensityRegex());
    }

    @Test
    public void sweepWidthFromTheLineReachesEveryFieldOfARealBatch() throws Exception {
        File labels = temporary.newFolder("labels");
        String[] fields = {"a", "b", "c"};
        for (int i = 0; i < fields.length; i++) {
            writeLabels(labels, fields[i] + "_C1.tif");
            writeLabels(labels, fields[i] + "_C2.tif");
        }
        OCSBatchParameters parameters = OCSBatchMacro.parse("folder=["
                + labels.getAbsolutePath() + "] pattern=(.*)_C(\\d)\\.tif"
                + " methods=[volume-overlap] threshold_sweep sweep_width=0.35");
        OCSBatchResult result = OCSBatchRunner.run(OCSBatchParameters
                .builder(parameters.labelFolder(), parameters.labelRegex(),
                        parameters.varyingGroup())
                .template(parameters.template()).autoSave(false).build());

        assertEquals(result.failures().toString(), 3, result.fieldsRun());
        for (int i = 0; i < result.results().size(); i++) {
            OCSResult field = result.results().get(i);
            assertEquals(0.35, field.parameters().sweepWidth(), 0.0);
            assertEquals(NullModelKind.WHOLE_CHANNEL, field.parameters().nullModelKind());
            assertFalse(field.sweeps().isEmpty());
        }
    }

    @Test
    public void windowsPathsAndARegexWithBackslashesAreRead() {
        OCSBatchParameters parameters = OCSBatchMacro.parse(
                "folder=[C:\\my data\\labels] pattern=(.*)_C(\\d)\\.tif"
                        + " output=D:\\results intensity_folder=C:\\raw");
        assertEquals(new File("C:\\my data\\labels"), parameters.labelFolder());
        assertEquals("(.*)_C(\\d)\\.tif", parameters.labelRegex());
        assertEquals(new File("D:\\results"), parameters.saveDir());
        assertEquals(new File("C:\\raw"), parameters.intensityFolder());
    }

    @Test
    public void folderIsNotReadOutOfIntensityFolder() {
        // ImageJ's Macro.getValue accepts a key after an underscore, so
        // "intensity_folder=A folder=B" read A as the label folder.
        OCSBatchParameters parameters = OCSBatchMacro.parse(
                "intensity_folder=/raw folder=/labels");
        assertEquals(new File("/labels"), parameters.labelFolder());
    }

    @Test
    public void subfoldersIsAFlagNotAWordAnywhereInTheLine() {
        assertFalse(OCSBatchMacro.parse("folder=/data/subfolders-of-mine")
                .isRecursive());
        assertTrue(OCSBatchMacro.parse("folder=/data subfolders").isRecursive());
    }

    @Test
    public void theDefaultsAreTheDialogsDefaults() {
        OCSBatchParameters parameters = OCSBatchMacro.parse("folder=/data");
        assertEquals(OCSBatchMacro.DEFAULT_PATTERN, parameters.labelRegex());
        assertEquals(OCSBatchMacro.DEFAULT_CHANNEL_GROUP, parameters.varyingGroup());
        assertEquals(ocs.ui.Preset.byName("Quick look").engineIds(),
                parameters.template().methodIds());
    }

    @Test
    public void mistakesAreRefusedByName() {
        String[][] cases = {
            {"pattern=x", "folder="},
            {"folder=/a sweep_widht=0.3", "sweep_widht"},
            {"folder=/a channels=x", "not a batch option"},
            {"folder=/a folder=/b", "duplicate"},
            {"folder=/a channel_group=two", "channel_group="},
            {"folder=/a null_model", "region_roi"},
            {"folder=/a seed=9007199254740993", "2^53"},
            {"folder=/a permutations=0", "permutations="},
        };
        for (int i = 0; i < cases.length; i++) {
            try {
                OCSBatchMacro.parse(cases[i][0]);
                fail("accepted: " + cases[i][0]);
            } catch (IllegalArgumentException refused) {
                assertTrue(cases[i][0] + " -> " + refused.getMessage(),
                        refused.getMessage().contains(cases[i][1]));
            }
        }
    }

    @Test
    public void aRecordedBatchReplaysAsTheSameBatch() {
        OCSBatchParameters original = OCSBatchParameters
                .builder(new File("C:\\my data\\labels"), "(.*)_C(\\d)\\.tif", 2)
                .recursive(true)
                .intensity(new File("C:\\raw"), "(.*)_C(\\d)_raw\\.tif")
                .sharedRegionRoi(new File("C:\\roi\\region.zip"))
                .saveDir(new File("C:\\out put"))
                .template(OCSBatchMacro.template("region_roi=x methods=[cpc]"
                        + " null_model sweep_width=0.3"))
                .build();
        String line = OCSBatchMacro.toMacroOptions(original,
                "methods=[cpc] null_model sweep_width=0.3");

        OCSBatchParameters replayed = OCSBatchMacro.parse(line);

        assertEquals(new File("C:/my data/labels"), replayed.labelFolder());
        assertEquals(original.labelRegex(), replayed.labelRegex());
        assertEquals(original.varyingGroup(), replayed.varyingGroup());
        assertTrue(replayed.isRecursive());
        assertEquals(new File("C:/raw"), replayed.intensityFolder());
        assertEquals(original.intensityRegex(), replayed.intensityRegex());
        assertEquals(new File("C:/roi/region.zip"), replayed.sharedRegionRoi());
        assertEquals(new File("C:/out put"), replayed.saveDir());
        assertEquals(0.3, replayed.template().sweepWidth(), 0.0);
        assertTrue(replayed.template().runsNullModel());
    }

    @Test
    public void theRecorderDoublesBackslashesSoTheMacroStringReplaysThem() {
        assertEquals("pattern=(.*)_C(\\\\d)", OCSMacroOptions.forRecorder(
                "pattern=(.*)_C(\\d)"));
    }

    private static void writeLabels(File folder, String name) {
        ShortProcessor processor = new ShortProcessor(24, 24);
        for (int y = 3; y < 11; y++) {
            for (int x = 3; x < 11; x++) {
                processor.set(x, y, 1);
            }
        }
        IJ.saveAsTiff(new ImagePlus(name, processor),
                new File(folder, name).getAbsolutePath());
    }
}
