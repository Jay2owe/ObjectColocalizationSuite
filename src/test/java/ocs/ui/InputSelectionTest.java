package ocs.ui;

import ij.ImagePlus;
import ij.gui.OvalRoi;
import ij.gui.Roi;
import ij.io.RoiEncoder;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import ij.process.ShortProcessor;
import ocs.OCSMacroOptions;
import ocs.OCSMacroOptionsParser;
import ocs.OCSParameters;
import ocs.engine.EngineInputs;
import ocs.engine.EngineRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.JCheckBox;
import java.awt.Component;
import java.awt.Container;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The menu dialog's input step, without a screen.
 *
 * <p>The rules that used to be missing: more than five open images must be a
 * choice rather than a silent cut, intensity images and a region must be
 * choosable, and the line the Recorder writes must replay the run the dialog
 * built.
 */
public class InputSelectionTest {

    private static final int SIZE = 24;

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @BeforeClass
    public static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Before
    @After
    public void noStoredSelection() {
        RememberedMethods.forget();
    }

    // ---------- channels ----------

    @Test
    public void moreThanFiveOpenImagesAreAChoiceNotASilentCut() {
        List<ImagePlus> open = new ArrayList<ImagePlus>();
        for (int i = 0; i < 6; i++) {
            open.add(labels("C" + (i + 1)));
        }
        List<ImagePlus> defaults = InputSelection.defaultChannels(open, null);
        assertEquals("with six open the dialog opens on two and lets the user pick",
                2, defaults.size());

        InputSelection tooMany = InputSelection.from(open).channels(open);
        assertTrue(tooMany.problems().toString(),
                tooMany.problems().toString().contains("At most 5"));

        InputSelection lastFive = InputSelection.from(open).channels(open.subList(1, 6));
        assertTrue("any five of the six can be chosen, not only the first five",
                lastFive.problems().isEmpty());
        assertEquals("C6", lastFive.toBuilder().build().labelImages().get(4).getTitle());
    }

    @Test
    public void withNothingRememberedTheOptionalChannelsStartAtNone() {
        // Two label images and their two intensity images: the usual setup for
        // the intensity methods. Pre-filling channels 3 and 4 with the intensity
        // images made the first OK fail.
        List<ImagePlus> open = Arrays.asList(labels("A"), labels("B"),
                labels("A raw"), labels("B raw"));
        List<ImagePlus> defaults = InputSelection.defaultChannels(open, null);
        assertEquals(2, defaults.size());
        assertEquals("A", defaults.get(0).getTitle());
        assertEquals("B", defaults.get(1).getTitle());
    }

    @Test
    public void theRememberedChannelsWinWhenTheyAreAllOpen() {
        List<ImagePlus> open = Arrays.asList(labels("A"), labels("B"), labels("C"));
        List<ImagePlus> chosen = InputSelection.defaultChannels(open, Arrays.asList("C", "A"));
        assertEquals("C", chosen.get(0).getTitle());
        assertEquals("A", chosen.get(1).getTitle());

        List<ImagePlus> fallback = InputSelection.defaultChannels(open,
                Arrays.asList("C", "gone"));
        assertEquals("a remembered image that is closed falls back to the first two",
                2, fallback.size());
    }

    @Test
    public void oneChannelIsNotEnough() {
        InputSelection one = InputSelection.from(null).channels(Arrays.asList(labels("A")));
        assertTrue(one.problems().toString(), one.problems().toString().contains("at least 2"));
    }

    @Test
    public void theSameImageTwiceIsRefused() {
        ImagePlus a = labels("A");
        InputSelection twice = InputSelection.from(null).channels(Arrays.asList(a, a));
        assertTrue(twice.problems().toString(), twice.problems().toString().contains("both 'A'"));
    }

    @Test
    public void mismatchedDimensionsNameBothImages() {
        ImagePlus small = labels("small");
        ImagePlus big = new ImagePlus("big", new ShortProcessor(SIZE + 8, SIZE));
        InputSelection selection = InputSelection.from(null)
                .channels(Arrays.asList(small, big));
        String problems = selection.problems().toString();
        assertTrue(problems, problems.contains("'small'"));
        assertTrue(problems, problems.contains("'big'"));
        try {
            selection.toBuilder();
            fail("a selection with problems must not build a run");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("'big'"));
        }
    }

    @Test
    public void anIntensityImageOfTheWrongSizeIsNamedToo() {
        InputSelection selection = InputSelection.from(null)
                .channels(Arrays.asList(labels("A"), labels("B")))
                .intensity(0, new ImagePlus("A raw", new ByteProcessor(SIZE, SIZE + 1)))
                .intensity(1, intensity("B raw"));
        assertTrue(selection.problems().toString(),
                selection.problems().toString().contains("'A raw'"));
    }

    // ---------- intensity ----------

    @Test
    public void aChannelWithoutIntensityIsNamedWhenOthersHaveOne() {
        InputSelection selection = InputSelection.from(null)
                .channels(Arrays.asList(labels("A"), labels("B")))
                .intensity(0, intensity("A raw"));
        String problems = selection.problems().toString();
        assertTrue(problems, problems.contains("Channel 2"));
        assertTrue(problems, problems.contains("'B'"));
        assertFalse(selection.hasIntensity());
    }

    @Test
    public void noIntensityAnywhereIsFineAndGreysTheIntensityMethods() {
        InputSelection selection = InputSelection.from(null)
                .channels(Arrays.asList(labels("A"), labels("B")));
        assertTrue(selection.problems().isEmpty());
        EngineInputs inputs = selection.toBuilder().build().toEngineInputs();
        MethodSelectionModel model = MethodSelectionModel.openedOn(
                EngineRegistry.createDefault(), inputs);
        assertFalse(model.isRunnable("per-object-intensity"));
        assertFalse(model.isRunnable("whole-image-intensity"));
        assertTrue(model.whyNotRunnable("per-object-intensity"),
                model.whyNotRunnable("per-object-intensity").contains("intensity"));
    }

    @Test
    public void intensityOnEveryChannelReachesTheRun() {
        InputSelection selection = InputSelection.from(null)
                .channels(Arrays.asList(labels("A"), labels("B")))
                .intensity(0, intensity("A raw"))
                .intensity(1, intensity("B raw"));
        OCSParameters parameters = selection.toBuilder().build();
        assertEquals("B raw", parameters.intensityImages().get(1).getTitle());
        MethodSelectionModel model = MethodSelectionModel.openedOn(
                EngineRegistry.createDefault(), parameters.toEngineInputs());
        assertTrue(model.isRunnable("per-object-intensity"));
    }

    // ---------- region ----------

    @Test
    public void anEmptyRoiManagerIsNamed() {
        InputSelection selection = twoChannels()
                .region(InputSelection.RegionSource.ROI_MANAGER, null, new Roi[0]);
        assertTrue(selection.problems().toString(),
                selection.problems().toString().contains("ROI Manager is empty"));
    }

    @Test
    public void aMissingSelectionIsNamed() {
        InputSelection selection = twoChannels()
                .region(InputSelection.RegionSource.SELECTION, null, new Roi[0]);
        assertTrue(selection.problems().toString(),
                selection.problems().toString().contains("no active selection"));
    }

    @Test
    public void aZipWithNoRoisIsNamed() throws IOException {
        File empty = temp.newFile("empty.zip");
        ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(empty));
        zip.close();
        InputSelection selection = twoChannels()
                .region(InputSelection.RegionSource.ZIP_FILE, empty.getPath(), null);
        String problems = selection.problems().toString();
        assertFalse("an empty ROI set must be a problem", selection.problems().isEmpty());
        assertTrue(problems, problems.contains("empty.zip"));
    }

    @Test
    public void aMissingZipIsNamed() {
        InputSelection selection = twoChannels()
                .region(InputSelection.RegionSource.ZIP_FILE, "C:/no/such/region.zip", null);
        assertTrue(selection.problems().toString(),
                selection.problems().toString().contains("region.zip"));
    }

    @Test
    public void aZipRegionIsReadAndReachesTheRun() throws IOException {
        File zip = regionZip();
        InputSelection selection = twoChannels()
                .region(InputSelection.RegionSource.ZIP_FILE, zip.getPath(), null);
        assertTrue(selection.problems().toString(), selection.problems().isEmpty());
        assertEquals(1, selection.toBuilder().build().domain().size());
    }

    @Test
    public void theOutputFolderMustNotBeAFile() throws IOException {
        File file = temp.newFile("not-a-folder.txt");
        InputSelection selection = twoChannels().output(file.getPath(), true);
        assertTrue(selection.problems().toString(),
                selection.problems().toString().contains("is a file"));
    }

    @Test
    public void calibrationIsReadFromTheFirstChannelAndShown() {
        ImagePlus a = labels("A");
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 0.284;
        calibration.pixelHeight = 0.284;
        calibration.pixelDepth = 1.0;
        calibration.setUnit("um");
        a.setCalibration(calibration);
        InputSelection selection = InputSelection.from(null)
                .channels(Arrays.asList(a, labels("B")));
        // ImageJ turns "um" into the micro sign, so the unit is read back from it.
        assertEquals("Calibration: 0.284 x 0.284 x 1 " + calibration.getUnit()
                + " per voxel", selection.calibrationText());
        assertTrue(InputSelection.from(null).channels(Arrays.asList(labels("A"), labels("B")))
                .calibrationText().contains("none"));
    }

    // ---------- the chooser greys what the inputs cannot feed ----------

    @Test
    public void theChooserGreysTheChanceTestWithoutARegion() {
        InputSelection selection = twoChannels();
        MethodChoiceDialog dialog = new MethodChoiceDialog(EngineRegistry.createDefault(),
                selection.toBuilder().build().toEngineInputs(), null);
        dialog.methods().model().apply(Preset.byName("Object colocalization"));
        dialog.methods().refresh();

        JCheckBox chance = chanceSwitch(dialog);
        assertFalse("the chance test cannot run without a region", chance.isEnabled());
        assertFalse("and a preset that asks for it cannot switch it on",
                dialog.methods().model().runsNullModel());
        assertNotNull("the reason is shown", chance.getToolTipText());
        assertTrue(chance.getToolTipText(), chance.getToolTipText().contains("region"));
        assertNull("Run stays available for the methods that can run",
                dialog.whyRunIsDisabled());
    }

    @Test
    public void theChooserOffersTheChanceTestWithARegion() {
        InputSelection selection = twoChannels().region(
                InputSelection.RegionSource.SELECTION, null, new Roi[] {region()});
        MethodChoiceDialog dialog = new MethodChoiceDialog(EngineRegistry.createDefault(),
                selection.toBuilder().build().toEngineInputs(), null);
        dialog.methods().model().apply(Preset.byName("Object colocalization"));
        dialog.methods().refresh();

        assertTrue(chanceSwitch(dialog).isEnabled());
        assertTrue(dialog.methods().model().runsNullModel());
    }

    // ---------- the recorded line replays the run ----------

    @Test
    public void recorderRoundTripWithNoRegion() {
        InputSelection selection = twoChannels().output("C:\\out\\ocs run", false);
        OCSParameters built = selection.toBuilder().preset("Quick look")
                .threshold("volume-overlap", 45.0).build();
        assertReplays(selection, built, null);
    }

    @Test
    public void recorderRoundTripWithTheRoiManager() {
        Roi[] manager = {region()};
        InputSelection selection = withIntensity().region(
                InputSelection.RegionSource.ROI_MANAGER, null, manager);
        OCSParameters built = selection.toBuilder().preset("Object colocalization")
                .permutations(250).seed(77L).build();
        String line = assertReplays(selection, built, manager);
        assertTrue(line, line.contains(OCSMacroOptions.REGION_ROI + "=["
                + OCSMacroOptions.REGION_FROM_ROI_MANAGER + "]"));
        assertNotNull("the Log is told the line reads the ROI Manager at replay",
                selection.replayNote());
    }

    @Test
    public void recorderRoundTripWithTheActiveSelection() {
        Roi[] active = {region()};
        InputSelection selection = twoChannels().region(
                InputSelection.RegionSource.SELECTION, null, active);
        OCSParameters built = selection.toBuilder().preset("Object colocalization")
                .discovery(true).sweepWidth(0.3).build();
        String line = assertReplays(selection, built, active);
        assertTrue(line, line.contains(OCSMacroOptions.REGION_ROI + "="
                + OCSMacroOptions.REGION_FROM_SELECTION));
    }

    @Test
    public void recorderRoundTripWithAZipFile() throws IOException {
        File zip = regionZip();
        InputSelection selection = withIntensity().region(
                InputSelection.RegionSource.ZIP_FILE, zip.getPath(), null)
                .output(temp.getRoot().getPath(), true);
        OCSParameters built = selection.toBuilder().preset("Discovery \u2014 everything")
                .discovery(true).permutations(99).build();
        String line = assertReplays(selection, built, null);
        assertFalse("paths are recorded with forward slashes: " + line, line.contains("\\"));
        assertNull(selection.replayNote());
    }

    /**
     * Records, parses, replays the way the macro entry does, and compares every
     * setting the dialog built.
     *
     * @param onScreen the ROIs the replay finds in the ROI Manager or selection
     */
    private String assertReplays(InputSelection selection, OCSParameters built,
            Roi[] onScreen) {
        String line = selection.recordedOptions(built);
        OCSMacroOptions options = OCSMacroOptionsParser.parse(line);

        List<ImagePlus> labels = byTitles(selection, options.channels());
        OCSParameters.Builder replay = OCSParameters.builder(labels)
                .channelNames(options.channels());
        if (!options.intensityImages().isEmpty()) {
            replay.intensityImages(byTitles(selection, options.intensityImages()));
        }
        InputSelection.RegionSource source =
                InputSelection.sourceForMacroValue(options.regionRoi());
        assertEquals(line, selection.regionSource(), source);
        if (source != InputSelection.RegionSource.NONE) {
            InputSelection region = InputSelection.from(null).region(source,
                    options.regionRoi(), source == InputSelection.RegionSource.ZIP_FILE
                            ? null : onScreen);
            assertNull(region.regionProblem());
            replay.domain(region.region());
        }
        options.applyTo(replay);
        OCSParameters replayed = replay.build();

        assertEquals(line, built.labelImages(), replayed.labelImages());
        assertEquals(line, built.channelNames(), replayed.channelNames());
        assertEquals(line, built.intensityImages(), replayed.intensityImages());
        assertEquals(line, built.domain().size(), replayed.domain().size());
        assertEquals(line, built.methodIds(), replayed.methodIds());
        assertEquals(line, built.thresholds(), replayed.thresholds());
        assertEquals(line, built.isBidirectional(), replayed.isBidirectional());
        assertEquals(line, built.runsNullModel(), replayed.runsNullModel());
        assertEquals(line, built.runsAgreement(), replayed.runsAgreement());
        assertEquals(line, built.runsThresholdSweep(), replayed.runsThresholdSweep());
        assertEquals(line, built.runsDiscovery(), replayed.runsDiscovery());
        assertEquals(line, built.permutations(), replayed.permutations());
        assertEquals(line, built.seed(), replayed.seed());
        assertEquals(line, built.alpha(), replayed.alpha(), 0.0);
        assertEquals(line, built.flipThreshold(), replayed.flipThreshold(), 0.0);
        assertEquals(line, built.sweepWidth(), replayed.sweepWidth(), 0.0);
        assertEquals(line, built.minCompareN(), replayed.minCompareN());
        assertEquals(line, built.nullModelKind(), replayed.nullModelKind());
        assertEquals(line, InputSelection.macroPath(selection.output()), options.output());
        assertEquals(line, !selection.showTables(), options.hideDisplay());
        return line;
    }

    // ---------- fixtures ----------

    private static List<ImagePlus> byTitles(InputSelection selection, List<String> titles) {
        List<ImagePlus> pool = new ArrayList<ImagePlus>(selection.labels());
        for (int i = 0; i < selection.intensities().size(); i++) {
            if (selection.intensities().get(i) != null) {
                pool.add(selection.intensities().get(i));
            }
        }
        List<ImagePlus> images = new ArrayList<ImagePlus>();
        for (int i = 0; i < titles.size(); i++) {
            ImagePlus image = InputSelection.byTitle(pool, titles.get(i));
            assertNotNull("recorded title '" + titles.get(i) + "' names no image", image);
            images.add(image);
        }
        return images;
    }

    private static InputSelection twoChannels() {
        return InputSelection.from(null)
                .channels(Arrays.asList(labels("A labels"), labels("B")));
    }

    private static InputSelection withIntensity() {
        return twoChannels()
                .intensity(0, intensity("A raw"))
                .intensity(1, intensity("B raw"));
    }

    private File regionZip() throws IOException {
        File file = temp.newFile("region.zip");
        ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(
                new FileOutputStream(file)));
        DataOutputStream out = new DataOutputStream(zip);
        zip.putNextEntry(new ZipEntry("region.roi"));
        new RoiEncoder(out).write(region());
        out.flush();
        zip.closeEntry();
        zip.close();
        return file;
    }

    private static Roi region() {
        return new OvalRoi(1, 1, SIZE - 2, SIZE - 2);
    }

    private static JCheckBox chanceSwitch(MethodChoiceDialog dialog) {
        List<JCheckBox> boxes = new ArrayList<JCheckBox>();
        collect(dialog.content(), boxes);
        for (int i = 0; i < boxes.size(); i++) {
            if (MethodChoiceDialog.CHANCE.equals(boxes.get(i).getClientProperty(
                    MethodChoiceDialog.CHECK_PROPERTY))) {
                return boxes.get(i);
            }
        }
        throw new AssertionError("no chance switch");
    }

    private static void collect(Container root, List<JCheckBox> found) {
        Component[] children = root.getComponents();
        for (int i = 0; i < children.length; i++) {
            if (children[i] instanceof JCheckBox) {
                found.add((JCheckBox) children[i]);
            }
            if (children[i] instanceof Container) {
                collect((Container) children[i], found);
            }
        }
    }

    private static ImagePlus labels(String title) {
        ShortProcessor processor = new ShortProcessor(SIZE, SIZE);
        for (int y = 2; y < 8; y++) {
            for (int x = 2; x < 8; x++) {
                processor.set(x, y, 1);
            }
        }
        for (int y = 12; y < 18; y++) {
            for (int x = 12; x < 18; x++) {
                processor.set(x, y, 2);
            }
        }
        return new ImagePlus(title, processor);
    }

    private static ImagePlus intensity(String title) {
        ByteProcessor processor = new ByteProcessor(SIZE, SIZE);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                processor.set(x, y, (x * 7 + y * 3) % 256);
            }
        }
        return new ImagePlus(title, processor);
    }
}
