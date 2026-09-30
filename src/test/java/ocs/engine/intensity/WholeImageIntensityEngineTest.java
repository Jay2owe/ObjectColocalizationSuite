package ocs.engine.intensity;

import ij.ImagePlus;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.process.ShortProcessor;
import ocs.engine.ColumnSpec;
import ocs.engine.DirectionKey;
import ocs.engine.EngineFamily;
import ocs.engine.EngineInputs;
import ocs.engine.EngineProgress;
import ocs.engine.EngineRegistry;
import ocs.engine.EngineResult;
import ocs.engine.InputRequirement;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The {@link ocs.engine.ColocEngine} face of the intensity family.
 *
 * <p>Instantiated directly rather than fetched from
 * {@link EngineRegistry#createDefault()}, because wiring it into the default set
 * is the orchestrator's line to add, not this stage's. What is checked here is
 * that the registry's registration rules accept it, so that line cannot fail.
 */
public class WholeImageIntensityEngineTest {

    private static final int SIDE = 40;

    @Test
    public void theRegistryAcceptsTheEngine() {
        WholeImageIntensityEngine engine = new WholeImageIntensityEngine();
        EngineRegistry registry = EngineRegistry.empty().register(engine);

        assertEquals(1, registry.size());
        assertTrue(registry.has("whole-image-intensity"));
        assertEquals(engine, registry.byId("whole-image-intensity"));
        assertEquals(1, registry.family(EngineFamily.INTENSITY).size());
    }

    @Test
    public void theEngineDeclaresWhatTheContractAsksFor() {
        WholeImageIntensityEngine engine = new WholeImageIntensityEngine();

        assertEquals("whole-image-intensity", engine.id());
        assertEquals(EngineFamily.INTENSITY, engine.family());
        assertTrue(engine.requires().contains(InputRequirement.INTENSITY_IMAGES));
        assertTrue(engine.requires().contains(InputRequirement.LABEL_IMAGES));
        assertTrue("the null and the threshold search cost two orders of magnitude"
                + " more than an object scan, and the pre-run estimate is where a"
                + " user finds that out", engine.relativeCost() > 100.0);

        int primaries = 0;
        for (ColumnSpec column : engine.columns()) {
            if (column.isPrimary()) {
                primaries++;
                assertEquals("Pearson r", column.name());
            }
        }
        assertEquals(1, primaries);

        List<String> names = new ArrayList<String>();
        for (ColumnSpec column : engine.columns()) {
            names.add(column.name());
        }
        // Every column the whole-image intensity table in 02_CONTRACT.md names,
        // plus the four the defect ledger added.
        assertTrue(names.toString(), names.contains("Pearson r Thresholded"));
        assertTrue(names.toString(), names.contains("Manders M1"));
        assertTrue(names.toString(), names.contains("Manders M2"));
        assertTrue(names.toString(), names.contains("Costes Ta"));
        assertTrue(names.toString(), names.contains("Costes Tb"));
        assertTrue(names.toString(), names.contains("Costes Threshold Fitted?"));
        assertTrue(names.toString(), names.contains("Costes p"));
        assertTrue(names.toString(), names.contains("Costes p Floor"));
        assertTrue(names.toString(), names.contains("Randomization Skipped?"));
        assertTrue(names.toString(), names.contains("Manders Valid?"));
    }

    @Test
    public void engineWithoutIntensityImagesIsReportedAsUnrunnable() {
        EngineInputs labelsOnly = EngineInputs.builder(Arrays.asList(
                        labelImage("A"), labelImage("B")))
                .channelNames(Arrays.asList("A", "B"))
                .build();

        List<InputRequirement> missing = labelsOnly.missing(
                new WholeImageIntensityEngine().requires());
        assertEquals(Collections.singletonList(InputRequirement.INTENSITY_IMAGES), missing);
        assertTrue(EngineRegistry.empty()
                .register(new WholeImageIntensityEngine())
                .runnableWith(labelsOnly).isEmpty());
    }

    /**
     * The primary column is Pearson's r, which is exactly symmetric, so both
     * ordered directions must carry the identical number — and it must be the
     * number an independent two-pass calculation gives.
     */
    @Test
    public void pearsonMatchesAnIndependentCalculationInBothDirections() {
        double[][] values = correlatedValues(SIDE * SIDE, 5L);
        EngineInputs inputs = inputsFrom(values, null, null);

        Map<DirectionKey, ColocalizationMetrics.Result> metrics =
                new WholeImageIntensityEngine(fastOptions())
                        .computeMetrics(inputs, EngineProgress.SILENT);

        assertEquals("two channels give two ordered directions", 2, metrics.size());
        double expected = twoPassPearson(values[0], values[1]);
        for (Map.Entry<DirectionKey, ColocalizationMetrics.Result> entry : metrics.entrySet()) {
            assertEquals(entry.getKey().label(), expected,
                    entry.getValue().pearson(), 1.0e-9);
        }

        ColocalizationMetrics.Result forward = metrics.get(direction(metrics, "A", "B"));
        ColocalizationMetrics.Result reverse = metrics.get(direction(metrics, "B", "A"));
        assertEquals("the primary column is direction-independent, which is what"
                        + " isSymmetric() reports",
                forward.pearson(), reverse.pearson(), 0.0);
        assertTrue(new WholeImageIntensityEngine().isSymmetric());

        // The supporting columns are not, which is why both directions are run in
        // full rather than one being mirrored onto the other. M1 forward would
        // equal M2 reverse only if the Costes thresholds mirrored, and under an
        // ordinary least-squares fit they do not: B-on-A and A-on-B are different
        // lines. Asserting the mirror is precisely the assumption that would make
        // halving the work wrong.
        assertFalse(Double.isNaN(forward.mandersM1()));
        assertFalse(Double.isNaN(reverse.mandersM1()));
        assertTrue(forward.mandersM1() >= 0.0 && forward.mandersM1() <= 1.0);
        assertTrue(reverse.mandersM2() >= 0.0 && reverse.mandersM2() <= 1.0);
    }

    /**
     * The whole-image-versus-per-object decision, made executable.
     *
     * <p>A whole-image metric has no per-object value, so the engine reports every
     * direction with an empty score list. That is what makes the agreement matrix,
     * the null model and Discovery each do the right thing without knowing
     * anything about intensity colocalization: there is nothing to correlate,
     * nothing to permute, and <i>n</i> = 0 puts the method in "Not applicable".
     */
    @Test
    public void computeReportsEveryDirectionWithNoPerObjectScores() {
        double[][] values = correlatedValues(SIDE * SIDE, 6L);
        EngineInputs inputs = inputsFrom(values, null, null);

        EngineResult result = new WholeImageIntensityEngine(fastOptions())
                .compute(inputs, EngineProgress.SILENT);

        assertEquals("both ordered directions are still reported",
                2, result.directions().size());
        for (DirectionKey key : result.directions()) {
            assertTrue("a whole-image number is not a per-object number, and pretending"
                            + " otherwise would give the generic layers a constant column"
                            + " to mistake for data",
                    result.scores(key).isEmpty());
            assertEquals(0, result.values(key).length);
            assertEquals(0, result.coincidentCount(key));
            assertTrue("percent coincident over no objects is undefined, not zero",
                    Double.isNaN(result.percentCoincident(key)));
        }
    }

    @Test
    public void anRoiDomainRestrictsTheMeasurement() {
        double[][] values = correlatedValues(SIDE * SIDE, 7L);
        PolygonRoi triangle = new PolygonRoi(
                new int[] {0, SIDE - 1, 0}, new int[] {0, 0, SIDE - 1}, 3, Roi.POLYGON);

        EngineInputs whole = inputsFrom(values, null, null);
        EngineInputs restricted = inputsFrom(values, Collections.singletonList((Roi) triangle), null);

        WholeImageIntensityEngine engine = new WholeImageIntensityEngine(fastOptions());
        ColocalizationMetrics.Result full = first(engine.computeMetrics(whole, EngineProgress.SILENT));
        ColocalizationMetrics.Result inside =
                first(engine.computeMetrics(restricted, EngineProgress.SILENT));

        assertEquals(SIDE * SIDE, full.voxelsAnalyzed());
        assertTrue("the triangle covers about half the field, measured "
                        + inside.voxelsAnalyzed() + " of " + full.voxelsAnalyzed(),
                inside.voxelsAnalyzed() < full.voxelsAnalyzed()
                        && inside.voxelsAnalyzed() > full.voxelsAnalyzed() / 4);
        assertFalse(Double.isNaN(inside.pearson()));
    }

    @Test
    public void calibrationReachesTheBlockSizing() {
        double[][] values = correlatedValues(SIDE * SIDE, 8L);
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 0.05;
        calibration.pixelHeight = 0.05;
        calibration.pixelDepth = 0.3;
        calibration.setUnit("micron");

        EngineInputs inputs = inputsFrom(values, null, calibration);
        ColocalizationMetrics.Options options = ColocalizationMetrics.Options.builder()
                .psf(0.25, 0.7).permutations(4).workers(1).build();

        ColocalizationMetrics.Result result = first(new WholeImageIntensityEngine(options)
                .computeMetrics(inputs, EngineProgress.SILENT));

        assertEquals("0.25 um of PSF over 0.05 um voxels is a five-voxel block",
                5, result.blockWidth());
        assertTrue(result.blockSizeDerived());
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static ColocalizationMetrics.Options fastOptions() {
        return ColocalizationMetrics.Options.builder()
                .permutations(8).workers(1).build();
    }

    private static ColocalizationMetrics.Result first(
            Map<DirectionKey, ColocalizationMetrics.Result> metrics) {
        return metrics.values().iterator().next();
    }

    private static DirectionKey direction(Map<DirectionKey, ColocalizationMetrics.Result> metrics,
                                          String source, String target) {
        for (DirectionKey key : metrics.keySet()) {
            if (key.sourceName().equals(source) && key.targetName().equals(target)) {
                return key;
            }
        }
        throw new AssertionError("no direction " + source + " -> " + target);
    }

    private static EngineInputs inputsFrom(double[][] values, List<Roi> domain,
                                           Calibration calibration) {
        EngineInputs.Builder builder = EngineInputs.builder(Arrays.asList(
                        labelImage("A"), labelImage("B")))
                .intensityImages(Arrays.asList(
                        intensityImage("iA", values[0]), intensityImage("iB", values[1])))
                .channelNames(Arrays.asList("A", "B"));
        if (domain != null) {
            builder.domain(domain);
        }
        if (calibration != null) {
            builder.calibration(calibration);
        }
        return builder.build();
    }

    private static ImagePlus labelImage(String title) {
        ShortProcessor processor = new ShortProcessor(SIDE, SIDE);
        for (int i = 0; i < SIDE * SIDE; i++) {
            processor.set(i, 1 + (i % 3));
        }
        return new ImagePlus(title, processor);
    }

    private static ImagePlus intensityImage(String title, double[] values) {
        ShortProcessor processor = new ShortProcessor(SIDE, SIDE);
        for (int i = 0; i < values.length; i++) {
            processor.set(i, (int) Math.round(values[i]));
        }
        return new ImagePlus(title, processor);
    }

    /** Values are rounded into a 16-bit processor, so the fixture is read back exactly. */
    private static double[][] correlatedValues(int count, long seed) {
        Random random = new Random(seed);
        double[] a = new double[count];
        double[] b = new double[count];
        for (int i = 0; i < count; i++) {
            double shared = random.nextDouble();
            a[i] = Math.round(200.0 + 600.0 * shared + 120.0 * random.nextDouble());
            b[i] = Math.round(150.0 + 700.0 * shared + 140.0 * random.nextDouble());
        }
        return new double[][] {a, b};
    }

    /** Deliberately not the accumulator under test. */
    private static double twoPassPearson(double[] a, double[] b) {
        double meanA = 0.0;
        double meanB = 0.0;
        for (int i = 0; i < a.length; i++) {
            meanA += a[i];
            meanB += b[i];
        }
        meanA /= a.length;
        meanB /= b.length;

        double sumSqA = 0.0;
        double sumSqB = 0.0;
        double sumCo = 0.0;
        for (int i = 0; i < a.length; i++) {
            double da = a[i] - meanA;
            double db = b[i] - meanB;
            sumSqA += da * da;
            sumSqB += db * db;
            sumCo += da * db;
        }
        return sumCo / Math.sqrt(sumSqA * sumSqB);
    }
}
