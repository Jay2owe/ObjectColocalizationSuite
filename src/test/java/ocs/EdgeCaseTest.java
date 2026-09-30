package ocs;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import ocs.engine.EngineCancelledException;
import ocs.engine.EngineProgress;
import ocs.io.OCSOutputWriter;
import ocs.io.OCSTables;
import ocs.ui.InputSelection;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The edge-case sweep (stage 06): every method on the inputs that break
 * plugins in practice.
 *
 * <p>Each case runs every method the registry knows, with intensity images, so
 * a case that trips any one engine fails here rather than in somebody's batch.
 * "Runs" means: returns, every table it produces can be written, and whatever
 * could not run says why instead of throwing.
 */
public class EdgeCaseTest {

    private static final int SIZE = 32;

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @BeforeClass
    public static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    // ---------- object counts ----------

    @Test
    public void aChannelWithNoObjectsRunsEveryMethod() throws Exception {
        runsEverything(labels16(SIZE, SIZE, 1, new int[][] {{3, 3, 8, 1}, {18, 18, 8, 2}}),
                labels16(SIZE, SIZE, 1, new int[0][]));
    }

    @Test
    public void bothChannelsEmptyRunsEveryMethod() throws Exception {
        runsEverything(labels16(SIZE, SIZE, 1, new int[0][]),
                labels16(SIZE, SIZE, 1, new int[0][]));
    }

    @Test
    public void oneObjectPerChannelRunsEveryMethod() throws Exception {
        runsEverything(labels16(SIZE, SIZE, 1, new int[][] {{3, 3, 8, 1}}),
                labels16(SIZE, SIZE, 1, new int[][] {{6, 6, 8, 1}}));
    }

    @Test
    public void objectsOnTheFrameEdgeRunEveryMethod() throws Exception {
        runsEverything(labels16(SIZE, SIZE, 1, new int[][] {{0, 0, 6, 1}, {26, 26, 6, 2}}),
                labels16(SIZE, SIZE, 1, new int[][] {{0, 2, 6, 1}, {26, 0, 6, 2}}));
    }

    // ---------- geometry and calibration ----------

    @Test
    public void aThreeDimensionalStackRunsEveryMethod() throws Exception {
        runsEverything(labels16(SIZE, SIZE, 4, new int[][] {{3, 3, 8, 1}, {18, 18, 8, 2}}),
                labels16(SIZE, SIZE, 4, new int[][] {{6, 6, 8, 1}, {16, 20, 8, 2}}));
    }

    @Test
    public void anisotropicCalibrationRunsEveryMethod() throws Exception {
        ImagePlus a = labels16(SIZE, SIZE, 4, new int[][] {{3, 3, 8, 1}, {18, 18, 8, 2}});
        ImagePlus b = labels16(SIZE, SIZE, 4, new int[][] {{6, 6, 8, 1}, {16, 20, 8, 2}});
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 0.1;
        calibration.pixelHeight = 0.1;
        calibration.pixelDepth = 0.5;
        calibration.setUnit("micron");
        a.setCalibration(calibration);
        b.setCalibration(calibration.copy());
        runsEverything(a, b);
    }

    @Test
    public void anUncalibratedImageRunsEveryMethodInPixels() throws Exception {
        ImagePlus a = labels16(SIZE, SIZE, 1, new int[][] {{3, 3, 8, 1}});
        ImagePlus b = labels16(SIZE, SIZE, 1, new int[][] {{6, 6, 8, 1}});
        assertFalse(a.getCalibration().scaled());
        runsEverything(a, b);
    }

    @Test
    public void imagesOfDifferentSizesAreRefusedNamingBoth() {
        refused(labels16(SIZE, SIZE, 1, new int[][] {{3, 3, 8, 1}}),
                labels16(SIZE + 1, SIZE, 1, new int[][] {{3, 3, 8, 1}}), "dimensions");
        refused(labels16(SIZE, SIZE, 2, new int[][] {{3, 3, 8, 1}}),
                labels16(SIZE, SIZE, 3, new int[][] {{3, 3, 8, 1}}), "dimensions");
    }

    // ---------- label encodings ----------

    @Test
    public void eightSixteenAndThirtyTwoBitLabelsGiveTheSameAnswer() throws Exception {
        int[][] objectsA = {{3, 3, 8, 1}, {18, 18, 8, 2}};
        int[][] objectsB = {{6, 6, 8, 1}, {16, 20, 8, 2}};
        String sixteen = summary(labels16(SIZE, SIZE, 1, objectsA),
                labels16(SIZE, SIZE, 1, objectsB));
        String eight = summary(convert(labels16(SIZE, SIZE, 1, objectsA), 8),
                convert(labels16(SIZE, SIZE, 1, objectsB), 8));
        String thirtyTwo = summary(convert(labels16(SIZE, SIZE, 1, objectsA), 32),
                convert(labels16(SIZE, SIZE, 1, objectsB), 32));
        assertEquals(sixteen, eight);
        assertEquals(sixteen, thirtyTwo);
    }

    @Test
    public void labelsAbove65535AndNonContiguousLabelsRunEveryMethod() throws Exception {
        ImagePlus a = floatLabels(new int[][] {{3, 3, 8, 70000}, {18, 18, 8, 5}});
        ImagePlus b = floatLabels(new int[][] {{6, 6, 8, 900}, {16, 20, 8, 100001}});
        OCSResult result = runsEverything(a, b);
        String table = tableText(OCSTables.all(result).get("per-object"));
        assertTrue("label 70000 kept its identity: " + table, table.contains("70000"));
        assertTrue("label 100001 kept its identity", table.contains("100001"));
    }

    @Test
    public void theChanceTestShufflesLabelsAbove65535() {
        ImagePlus a = floatLabels(new int[][] {{3, 3, 8, 70000}, {18, 18, 8, 5}});
        ImagePlus b = floatLabels(new int[][] {{6, 6, 8, 900}, {16, 20, 8, 100001}});
        OCSResult result = OCS.run(OCSParameters.builder(a, b)
                .domain(Arrays.asList(new Roi(0, 0, SIZE, SIZE)))
                .methods("volume-overlap", "cpc").nullModel(true).permutations(5)
                .build());
        assertFalse(result.nullModels().isEmpty());
    }

    @Test
    public void aHugeLabelValueDoesNotSizeAnArrayByIt() {
        // A 32-bit image read as labels can hold values in the billions (an
        // intensity image picked by mistake). Sizing a lookup by the largest
        // label then fails with NegativeArraySizeException or runs out of memory.
        FloatProcessor processor = new FloatProcessor(SIZE, SIZE);
        processor.setf(4, 4, 3.0e9f);
        processor.setf(9, 9, 2.0e9f);
        ocs.nullmodel.ChannelDisplacer displacer =
                ocs.nullmodel.ChannelDisplacer.of(new ImagePlus("huge", processor));
        assertEquals(2, displacer.objectCount());
        assertEquals(0, displacer.seamSplitCount(1, 1, 0));
    }

    @Test
    public void anRgbLabelImageIsRefused() {
        ImagePlus rgb = new ImagePlus("rgb", new ColorProcessor(SIZE, SIZE));
        refused(rgb, labels16(SIZE, SIZE, 1, new int[][] {{3, 3, 8, 1}}), "rgb colour");
    }

    @Test
    public void aCompositeLabelImageIsRefused() {
        ImageStack stack = new ImageStack(SIZE, SIZE);
        stack.addSlice(new ShortProcessor(SIZE, SIZE));
        stack.addSlice(new ShortProcessor(SIZE, SIZE));
        ImagePlus composite = new ImagePlus("two-channel", stack);
        composite.setDimensions(2, 1, 1);
        composite = new ij.CompositeImage(composite, ij.CompositeImage.COMPOSITE);
        ImagePlus plain = labels16(SIZE, SIZE, 2, new int[][] {{3, 3, 8, 1}});
        refused(composite, plain, "2 channels");
    }

    @Test
    public void aTimeSeriesLabelImageIsRefused() {
        ImagePlus movie = labels16(SIZE, SIZE, 2, new int[][] {{3, 3, 8, 1}});
        movie.setDimensions(1, 1, 2);
        refused(movie, labels16(SIZE, SIZE, 2, new int[][] {{3, 3, 8, 1}}), "time series");
    }

    // ---------- intensity ----------

    @Test
    public void negativeAndNotANumberIntensitiesDoNotStopTheRun() throws Exception {
        ImagePlus a = labels16(SIZE, SIZE, 1, new int[][] {{3, 3, 8, 1}, {18, 18, 8, 2}});
        ImagePlus b = labels16(SIZE, SIZE, 1, new int[][] {{6, 6, 8, 1}, {16, 20, 8, 2}});
        FloatProcessor ia = ramp();
        FloatProcessor ib = ramp();
        for (int i = 0; i < SIZE * SIZE; i += 3) {
            ia.setf(i, -ia.getf(i));
        }
        ib.setf(5, 5, Float.NaN);
        ib.setf(7, 7, Float.NaN);
        OCSResult result = OCS.run(OCSParameters.builder(a, b)
                .intensityImages(Arrays.asList(new ImagePlus("ia", ia), new ImagePlus("ib", ib)))
                .allMethods().build());
        writable(result);
    }

    @Test
    public void anRgbIntensityImageIsRefused() {
        ImagePlus a = labels16(SIZE, SIZE, 1, new int[][] {{3, 3, 8, 1}});
        ImagePlus b = labels16(SIZE, SIZE, 1, new int[][] {{6, 6, 8, 1}});
        try {
            OCS.run(OCSParameters.builder(a, b)
                    .intensityImages(Arrays.asList(
                            new ImagePlus("rgb", new ColorProcessor(SIZE, SIZE)),
                            new ImagePlus("ib", ramp())))
                    .methods("per-object-intensity").build());
            fail("an RGB intensity image must be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("colour"));
        }
    }

    // ---------- region ----------

    @Test
    public void aRegionEntirelyOutsideTheImageIsRefusedForTheChanceTest() {
        ImagePlus a = labels16(SIZE, SIZE, 1, new int[][] {{3, 3, 8, 1}});
        ImagePlus b = labels16(SIZE, SIZE, 1, new int[][] {{6, 6, 8, 1}});
        try {
            OCS.run(OCSParameters.builder(a, b)
                    .domain(Arrays.asList(new Roi(100, 100, 10, 10)))
                    .methods("volume-overlap").nullModel(true).permutations(5).build());
            fail("a region outside the frame has nowhere to scatter objects");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("covers no pixel"));
        }
    }

    @Test
    public void severalRegionRoisRunTheChanceTest() {
        ImagePlus a = labels16(SIZE, SIZE, 1, new int[][] {{3, 3, 8, 1}, {18, 18, 8, 2}});
        ImagePlus b = labels16(SIZE, SIZE, 1, new int[][] {{6, 6, 8, 1}, {16, 20, 8, 2}});
        OCSResult result = OCS.run(OCSParameters.builder(a, b)
                .domain(Arrays.asList(new Roi(0, 0, 16, SIZE), new Roi(16, 0, 16, SIZE)))
                .methods("volume-overlap", "cpc").nullModel(true).permutations(5)
                .build());
        assertFalse(result.nullModels().isEmpty());
    }

    @Test
    public void anEmptyRoiSetIsRefusedWithASentence() throws Exception {
        File empty = temporary.newFile("empty.zip");
        java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(
                new java.io.FileOutputStream(empty));
        zip.close();
        InputSelection region = InputSelection.from(null).region(
                InputSelection.RegionSource.ZIP_FILE, empty.getAbsolutePath(), null);
        assertNotNull("an empty .zip must be a problem, not an empty region",
                region.regionProblem());
    }

    @Test
    public void aMethodThatCannotMeasureAShuffledCopyLosesOnlyItsOwnChanceTest() {
        // Objects on a perfectly regular grid: some shuffled copies defeat the
        // territory tessellation. Before, that one failure stopped the whole run.
        Roi region = new Roi(2, 2, 124, 124);
        OCSResult result = OCS.run(OCSParameters.builder(grid(0), grid(4))
                .methods("territory-occupancy", "volume-overlap")
                .domain(Arrays.asList(region)).nullModel(true).permutations(19)
                .build());
        boolean territorySkipped = false;
        boolean overlapTested = false;
        for (int i = 0; i < result.nullModels().size(); i++) {
            ocs.nullmodel.NullModelResult model = result.nullModels().get(i);
            if (model.engineId().equals("territory-occupancy")) {
                territorySkipped = model.skip()
                        == ocs.nullmodel.NullModelResult.Skip.FAILED_ON_A_SHUFFLE;
            }
            if (model.engineId().equals("volume-overlap") && model.ran()) {
                overlapTested = true;
            }
        }
        assertTrue("territory chance test reported as failed on a shuffle", territorySkipped);
        assertTrue("the other method's chance test still ran", overlapTested);
        assertEquals("observed territory values stand", 2, result.engineResults().size());
    }

    // ---------- cancellation at each phase ----------

    @Test
    public void cancellingDuringTheMethodsTheChanceTestOrTheSweepReturnsNothing() {
        String[] phases = {"Volumetric", "testing against chance",
            "checking how much each setting matters"};
        for (int i = 0; i < phases.length; i++) {
            ImagePlus a = labels16(SIZE, SIZE, 1, new int[][] {{3, 3, 8, 1}, {18, 18, 8, 2}});
            ImagePlus b = labels16(SIZE, SIZE, 1, new int[][] {{6, 6, 8, 1}, {16, 20, 8, 2}});
            try {
                OCS.run(OCSParameters.builder(a, b)
                        .domain(Arrays.asList(new Roi(0, 0, SIZE, SIZE)))
                        .methods("volume-overlap", "cpc")
                        .nullModel(true).thresholdSweep(true).permutations(20)
                        .build(), cancelOnceSeen(phases[i]));
                fail("cancelled at '" + phases[i] + "' and still returned a result");
            } catch (EngineCancelledException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    // ---------- fixtures ----------

    /** Every method, with intensity images and a region, and every table written. */
    private OCSResult runsEverything(ImagePlus a, ImagePlus b) throws Exception {
        List<ImagePlus> intensity = new ArrayList<ImagePlus>();
        intensity.add(intensityLike(a, "ia"));
        intensity.add(intensityLike(b, "ib"));
        OCSResult result = OCS.run(OCSParameters.builder(a, b)
                .intensityImages(intensity)
                .allMethods()
                .build());
        assertEquals("every method ran or said why not: " + result.skipped(),
                ocs.engine.EngineRegistry.createDefault().all().size(),
                result.engineResults().size() + result.skipped().size());
        writable(result);
        return result;
    }

    private void writable(OCSResult result) throws Exception {
        File root = temporary.newFolder();
        File folder = OCSOutputWriter.write(result, root);
        assertTrue(new File(folder, OCSOutputWriter.RUN_RECORD).isFile());
    }

    private static String summary(ImagePlus a, ImagePlus b) {
        a.setTitle("A");
        b.setTitle("B");
        OCSResult result = OCS.run(OCSParameters.builder(a, b)
                .methods("volume-overlap", "jaccard-dice", "cpc", "containment")
                .build());
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, ResultsTable> entry : OCSTables.all(result).entrySet()) {
            ResultsTable table = entry.getValue();
            String[] headings = table.getHeadings();
            for (int row = 0; row < table.size(); row++) {
                for (int c = 0; c < headings.length; c++) {
                    if (!"Image".equals(headings[c]) && !headings[c].startsWith("Channel")) {
                        text.append(table.getStringValue(headings[c], row)).append(',');
                    }
                }
                text.append('\n');
            }
        }
        return text.toString();
    }

    private static String tableText(ResultsTable table) {
        StringBuilder text = new StringBuilder();
        String[] headings = table.getHeadings();
        for (int row = 0; row < table.size(); row++) {
            for (int c = 0; c < headings.length; c++) {
                text.append(table.getStringValue(headings[c], row)).append(',');
            }
            text.append('\n');
        }
        return text.toString();
    }

    /** Refused whichever method is asked for, not only by one that checks. */
    private static void refused(ImagePlus a, ImagePlus b, String says) {
        try {
            OCS.run(OCSParameters.builder(a, b).methods("cpc").build());
            fail("accepted " + a.getTitle() + " with " + b.getTitle());
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().toLowerCase(java.util.Locale.ROOT).contains(says));
        }
    }

    private static EngineProgress cancelOnceSeen(final String phase) {
        return new EngineProgress() {
            private boolean seen;

            @Override
            public void report(String stage, double fraction) {
                if (stage != null && stage.startsWith(phase)) {
                    seen = true;
                }
            }

            @Override
            public boolean isCancelled() {
                return seen;
            }
        };
    }

    /** A 4 x 4 grid of 9-pixel squares, 30 apart, in a 128 x 128 frame. */
    private static ImagePlus grid(int offset) {
        ShortProcessor processor = new ShortProcessor(128, 128);
        int label = 1;
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                fill(processor, new int[] {8 + column * 30 + offset,
                    8 + row * 30 + offset, 9, label});
                label++;
            }
        }
        return new ImagePlus("grid-" + offset, processor);
    }

    /** Square objects {x, y, size, label} on every slice. */
    private static ImagePlus labels16(int width, int height, int slices, int[][] objects) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < slices; z++) {
            ShortProcessor processor = new ShortProcessor(width, height);
            for (int o = 0; o < objects.length; o++) {
                fill(processor, objects[o]);
            }
            stack.addSlice(processor);
        }
        return new ImagePlus("labels-" + System.identityHashCode(stack), stack);
    }

    private static ImagePlus floatLabels(int[][] objects) {
        FloatProcessor processor = new FloatProcessor(SIZE, SIZE);
        for (int o = 0; o < objects.length; o++) {
            fill(processor, objects[o]);
        }
        return new ImagePlus("float-" + System.identityHashCode(processor), processor);
    }

    private static void fill(ImageProcessor processor, int[] object) {
        for (int y = object[1]; y < object[1] + object[2]; y++) {
            for (int x = object[0]; x < object[0] + object[2]; x++) {
                processor.putPixelValue(x, y, object[3]);
            }
        }
    }

    private static ImagePlus convert(ImagePlus image, int bits) {
        ImageStack source = image.getStack();
        ImageStack stack = new ImageStack(image.getWidth(), image.getHeight());
        for (int z = 1; z <= source.getSize(); z++) {
            ImageProcessor in = source.getProcessor(z);
            ImageProcessor out = bits == 8
                    ? new ByteProcessor(image.getWidth(), image.getHeight())
                    : new FloatProcessor(image.getWidth(), image.getHeight());
            for (int i = 0; i < image.getWidth() * image.getHeight(); i++) {
                out.setf(i, in.getf(i));
            }
            stack.addSlice(out);
        }
        return new ImagePlus(image.getTitle() + "-" + bits, stack);
    }

    private static ImagePlus intensityLike(ImagePlus labels, String title) {
        ImageStack stack = new ImageStack(labels.getWidth(), labels.getHeight());
        for (int z = 0; z < labels.getStackSize(); z++) {
            stack.addSlice(ramp());
        }
        ImagePlus image = new ImagePlus(title + "-" + System.identityHashCode(stack), stack);
        image.setCalibration(labels.getCalibration().copy());
        return image;
    }

    private static FloatProcessor ramp() {
        FloatProcessor processor = new FloatProcessor(SIZE, SIZE);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                processor.setf(x, y, (x * 7 + y * 3) % 256 + 1);
            }
        }
        return processor;
    }
}
