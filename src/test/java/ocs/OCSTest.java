package ocs;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.process.ByteProcessor;
import ij.process.ShortProcessor;
import ocs.engine.EngineCancelledException;
import ocs.engine.EngineProgress;
import ocs.engine.EngineResult;
import ocs.engine.ThresholdBearing;
import ocs.ui.MethodChoice;
import ocs.ui.MethodSelectionPanel;
import ocs.engine.EngineRegistry;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The Java way in: {@link OCS#run}, with no dialog and no windows.
 *
 * <p>This is the layer the macro and the batch runner both go through, so a
 * mistake here is a mistake in every entry point at once. What it has to get
 * right is less the arithmetic — that is tested engine by engine — than the
 * decisions about <i>what runs at all</i>: which methods are refused, which are
 * skipped with a reason, and which failures are loud rather than quiet.
 */
public class OCSTest {

    private static final int SIZE = 32;

    @BeforeClass
    public static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    // ---------- the ordinary case ----------

    @Test
    public void aRunProducesOneResultPerMethodInTheOrderAsked() {
        OCSResult result = OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                .methods("volume-overlap", "cpc")
                .build());

        assertEquals(2, result.engineResults().size());
        assertEquals("volume-overlap", result.engineResults().get(0).engineId());
        assertEquals("cpc", result.engineResults().get(1).engineId());
        assertNotNull(result.resultFor("cpc"));
        assertNull(result.resultFor("containment"));
    }

    @Test
    public void everyOptionalLayerComesBackEmptyRatherThanNull() {
        // A caller that has to null-check before looping gets it wrong once.
        OCSResult result = OCS.run(labels("A"), labels("B"));

        assertTrue(result.nullModels().isEmpty());
        assertTrue(result.agreement().isEmpty());
        assertTrue(result.sweeps().isEmpty());
        assertTrue(result.discovery().isEmpty());
        assertTrue(result.skipped().isEmpty());
    }

    @Test
    public void theDefaultRunIsTheDefaultPreset() {
        // Whatever the dialog opens on is what a bare Java call does, so the two
        // cannot describe the same run differently.
        OCSResult result = OCS.run(labels("A"), labels("B"));
        assertEquals(ocs.ui.Preset.defaultPreset().engineIds(),
                result.parameters().methodIds());
    }

    @Test
    public void theSettledAlphaDefaultIsPointZeroFivePerTest() {
        OCSParameters parameters = OCSParameters.builder(
                labels("A"), labels("B")).build();

        assertEquals(0.05, ocs.agreement.Verdict.DEFAULT_ALPHA, 0.0);
        assertEquals(0.05, parameters.alpha(), 0.0);
    }

    // ---------- what is refused, and what is merely skipped ----------

    @Test
    public void anUnknownMethodIdIsRefusedRatherThanIgnored() {
        // A macro naming a method that no longer exists must fail, not quietly
        // run a smaller analysis while reporting the name it was given.
        try {
            OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                    .methods("volume-overlap", "manders-per-object")
                    .build());
            fail("an unknown method id must not be skipped");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("manders-per-object"));
        }
    }

    @Test
    public void aMethodThisDataCannotFeedIsSkippedWithItsReason() {
        // Naming every method is the normal case — the presets do it — and on
        // labels alone the intensity family genuinely cannot run. Not an error,
        // but not silence either.
        OCSResult result = OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                .methods("volume-overlap", "per-object-intensity")
                .build());

        assertEquals(1, result.engineResults().size());
        assertEquals(1, result.skipped().size());
        assertEquals("per-object-intensity", result.skipped().get(0).engineId());
        assertTrue(result.skipped().get(0).reason(),
                result.skipped().get(0).reason().contains("intensity"));
    }

    @Test
    public void withIntensityImagesTheIntensityFamilyIsNotSkipped() {
        OCSResult result = OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                .intensityImages(Arrays.asList(intensity("A raw"), intensity("B raw")))
                .methods("per-object-intensity")
                .build());

        assertTrue(result.skipped().toString(), result.skipped().isEmpty());
        assertEquals(1, result.engineResults().size());
    }

    @Test
    public void theSameImagePlusTwiceIsRefused() {
        // Not two copies of one picture — the same object. Every pair would be a
        // channel against itself, which reports perfect colocalization and looks
        // exactly like a result.
        ImagePlus shared = labels("A");
        try {
            OCS.run(shared, shared);
            fail("the same image in two channels must be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("same ImagePlus"));
        }
    }

    @Test
    public void anEmptyMethodListIsRefused() {
        try {
            OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                    .methods(new ArrayList<String>())
                    .build());
            fail("a run with no methods is not a run");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("no methods"));
        }
    }

    @Test
    public void anAlphaOutsideZeroToOneIsRefused() {
        try {
            OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                    .alpha(1.0).build());
            fail("alpha of 1 declares everything significant");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("alpha"));
        }
    }

    // ---------- settings reach the engines ----------

    @Test
    public void aThresholdInTheParametersIsWhatTheEngineRunsAt() {
        // The whole reason settings are carried. An engine taken straight from
        // the registry keeps its shipped default forever, so a report describing
        // the user's number while running the default is the failure to avoid.
        OCSResult result = OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                .methods("volume-overlap")
                .threshold("volume-overlap", 45.0)
                .build());

        assertEquals(45.0,
                ((ThresholdBearing) result.engines().get(0)).threshold(), 1e-9);
        assertEquals("and the registry's own copy is untouched", 30.0,
                ((ThresholdBearing) EngineRegistry.createDefault()
                        .byId("volume-overlap")).threshold(), 1e-9);
    }

    @Test
    public void movingTheThresholdChangesWhichObjectsCountAsCoincident() {
        // Not just stored — used. Two 8x8 blocks offset by four columns share
        // exactly half their voxels, so a threshold either side of 50% flips
        // the verdict, which pins the number the engine actually measured
        // rather than only that a number was passed along.
        List<ImagePlus> images = Arrays.asList(
                blockLabel("A", 4, 4, 8), blockLabel("B", 8, 4, 8));

        assertTrue(anyCoincident(runAt(images, 40.0)));
        assertFalse(anyCoincident(runAt(images, 60.0)));
    }

    @Test
    public void aChoiceFromTheDialogDescribesTheSameRun() {
        // The one-slip promise: what the dialog produced and what Java runs are
        // the same description, not two that happen to agree today.
        MethodSelectionPanel panel = new MethodSelectionPanel(
                EngineRegistry.createDefault(),
                OCSParameters.builder(labels("A"), labels("B")).build()
                        .toEngineInputs(),
                "volume-overlap");
        panel.model().setRunsAgreement(true);
        panel.model().setPermutations(37);
        panel.model().setSeed(11L);
        MethodChoice choice = MethodChoice.from(panel);

        OCSParameters parameters = OCSParameters.builder(labels("A"), labels("B"))
                .from(choice)
                .build();

        assertEquals(choice.engineIds(), parameters.methodIds());
        assertTrue(parameters.runsAgreement());
        assertEquals(37, parameters.permutations());
        assertEquals(11L, parameters.seed());
    }

    // ---------- the extra checks ----------

    @Test
    public void theChanceTestWithoutARegionIsRefusedAndSaysWhatToSupply() {
        // Refused rather than defaulted to the whole frame: objects scattered
        // over the parts that are not tissue collide less by chance, which makes
        // every observation look more significant than it is.
        try {
            OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                    .methods("volume-overlap")
                    .nullModel(true)
                    .build());
            fail("a chance test with nowhere to scatter must be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("region ROI"));
        }
    }

    @Test
    public void theChanceTestRunsWhenThereIsSomewhereToScatter() {
        OCSResult result = OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                .domain(Arrays.<Roi>asList(new Roi(0, 0, SIZE, SIZE)))
                .methods("volume-overlap")
                .nullModel(true)
                .permutations(9)
                .seed(4L)
                .build());

        assertFalse(result.nullModels().isEmpty());
        assertEquals(9, result.nullModels().get(0).permutations());
    }

    @Test
    public void agreementNeedsTwoMethodsToHaveAnythingToCompare() {
        OCSResult one = OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                .methods("volume-overlap").agreement(true).build());
        assertTrue("one method agrees with nothing", one.agreement().isEmpty());

        OCSResult two = OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                .methods("volume-overlap", "containment").agreement(true).build());
        assertFalse(two.agreement().isEmpty());
    }

    @Test
    public void askingForDiscoveryTurnsOnTheEvidenceItReads() {
        // Otherwise every method classifies as "not applicable" and the feature
        // looks broken rather than under-supplied.
        OCSParameters parameters = OCSParameters.builder(labels("A"), labels("B"))
                .discovery(true)
                .build();

        assertTrue(parameters.runsNullModel());
        assertTrue(parameters.runsAgreement());
        assertTrue(parameters.runsThresholdSweep());
    }

    @Test
    public void discoveryClassifiesEveryMethodThatRan() {
        OCSResult result = OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                .domain(Arrays.<Roi>asList(new Roi(0, 0, SIZE, SIZE)))
                .methods("volume-overlap", "containment")
                .discovery(true)
                .permutations(9)
                .seed(4L)
                .build());

        assertFalse(result.discovery().isEmpty());
        assertFalse(result.sweeps().isEmpty());
    }

    // ---------- cancellation ----------

    @Test
    public void cancellingStopsTheRunRatherThanReturningWhatItHadSoFar() {
        // A partial result is indistinguishable from a complete one in every
        // summary column, so there must be no way to obtain one.
        try {
            OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                    .methods("volume-overlap", "containment", "bounding-box")
                    .build(), cancelAfter(1));
            fail("a cancelled run must not return");
        } catch (EngineCancelledException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void progressIsWeightedByCostAndNotByMethodCount() {
        // Thirteen methods spanning two orders of magnitude in cost. A bar that
        // moved one thirteenth per method would sit near the end for most of the
        // run and read as frozen.
        final List<Double> fractions = new ArrayList<Double>();
        OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                .methods("cpc", "territory-occupancy")
                .build(), new EngineProgress() {
            @Override
            public void report(String stage, double fraction) {
                if (fraction >= 0.0) {
                    fractions.add(Double.valueOf(fraction));
                }
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        });

        assertFalse(fractions.isEmpty());
        // Centroid coincidence is far cheaper than the territory method, so the
        // bar must still be near the start when the second one begins.
        assertTrue(fractions.toString(), fractions.get(1).doubleValue() < 0.2);
    }

    // ---------- fixtures ----------

    private static OCSResult runAt(List<ImagePlus> images, double threshold) {
        return OCS.run(OCSParameters.builder(images)
                .methods("volume-overlap")
                .threshold("volume-overlap", threshold)
                .build());
    }

    private static boolean anyCoincident(OCSResult result) {
        EngineResult engineResult = result.engineResults().get(0);
        List<ocs.engine.DirectionKey> directions = engineResult.directions();
        for (int i = 0; i < directions.size(); i++) {
            if (engineResult.coincidentCount(directions.get(i)) > 0) {
                return true;
            }
        }
        return false;
    }

    private static EngineProgress cancelAfter(final int calls) {
        return new EngineProgress() {
            private int seen;

            @Override
            public void report(String stage, double fraction) {
                seen++;
            }

            @Override
            public boolean isCancelled() {
                return seen > calls;
            }
        };
    }

    /** Two blocks, so a direction has more than one object to distinguish. */
    private static ImagePlus labels(String title) {
        ShortProcessor processor = new ShortProcessor(SIZE, SIZE);
        fill(processor, 3, 3, 8, 1);
        fill(processor, 18, 18, 8, 2);
        return new ImagePlus(title, processor);
    }

    /** One block at a given position, for pinning an exact overlap fraction. */
    private static ImagePlus blockLabel(String title, int x, int y, int size) {
        ShortProcessor processor = new ShortProcessor(SIZE, SIZE);
        fill(processor, x, y, size, 1);
        return new ImagePlus(title, processor);
    }

    private static void fill(ShortProcessor processor, int x0, int y0, int size,
            int label) {
        for (int y = y0; y < y0 + size; y++) {
            for (int x = x0; x < x0 + size; x++) {
                processor.set(x, y, label);
            }
        }
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
