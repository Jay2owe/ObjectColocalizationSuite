package ocs.engine.object;

import ij.ImagePlus;
import ij.measure.Calibration;
import ij.process.ShortProcessor;
import ocs.engine.ColocEngine;
import ocs.engine.ColumnSpec;
import ocs.engine.DirectionKey;
import ocs.engine.EngineCancelledException;
import ocs.engine.EngineInputs;
import ocs.engine.EngineProgress;
import ocs.engine.EngineRegistry;
import ocs.engine.EngineResult;
import ocs.engine.ObjectScore;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The four object engines added in stage 4, on fixtures whose answers are
 * computed by hand rather than captured from a previous run.
 *
 * <p>One class rather than four, because the fixtures are the expensive part and
 * the hand-computed numbers for each one belong in exactly one place. A fixture
 * whose expected values are stated twice is a fixture that will eventually be
 * stated two different ways.
 *
 * <p>The fixtures, all 10x10 single-slice label images with one object per
 * channel:
 *
 * <pre>
 *   contained  A = 10x10 (100 voxels), B = 2x2 inside it (4), share 4
 *                A -> B  overlap   4%   Jaccard 4/100 = 0.04   partner inside source
 *                B -> A  overlap 100%   Jaccard 4/100 = 0.04   source inside partner
 *   identical  two 10x10 squares, share all 100
 *                Jaccard 1.0, Dice 1.0, contained both ways
 *   disjoint   A = 2x2 at [0,2), B = 2x2 at [6,8), share none
 *                Jaccard 0.0, centroids 8.485 apart
 *   partial    A = 4x4 at [0,4), B = 4x4 at [2,6), share 2x2 = 4
 *                Jaccard 4/28 = 0.142857, Dice 0.25, neither contained
 *   diagonal   A on one diagonal, B on the other, share no voxel at all
 *                but identical 10x10 bounding boxes
 * </pre>
 */
public class ObjectEngineTest {

    private static final int SIZE = 10;
    private static final double EPSILON = 1e-9;

    // ------------------------------------------------------------------
    // Contract, checked without going through the registry.
    // ------------------------------------------------------------------

    @Test
    public void everyStageFourEngineDeclaresOnePrimaryColumnWithAScale() {
        for (ColocEngine engine : stageFourEngines()) {
            int primaries = 0;
            for (ColumnSpec column : engine.columns()) {
                assertTrue("column " + column.name() + " of " + engine.id()
                        + " must declare a scale", column.scale() != null);
                if (column.isPrimary()) {
                    primaries++;
                }
            }
            assertEquals(engine.id() + " must have exactly one primary column",
                    1, primaries);
            assertTrue(engine.id() + " must report a positive relative cost",
                    engine.relativeCost() > 0.0);
        }
    }

    @Test
    public void allFourEnginesPassRegistrationValidation() {
        // The registry's checks are contract violations by an engine author, not
        // user errors, so they throw at startup. Proving they pass here means
        // adding these four to createDefault() cannot break the plugin's launch.
        EngineRegistry registry = EngineRegistry.empty();
        for (ColocEngine engine : stageFourEngines()) {
            registry.register(engine);
        }
        assertEquals(4, registry.size());
        assertTrue(registry.has("bounding-box"));
        assertTrue(registry.has("jaccard-dice"));
        assertTrue(registry.has("containment"));
        assertTrue(registry.has("distance-tolerance"));
    }

    @Test
    public void everyStageFourEngineHonoursCancellation() {
        EngineInputs inputs = containedPair();
        for (ColocEngine engine : stageFourEngines()) {
            try {
                engine.compute(inputs, CANCELLED);
                fail(engine.id() + " ignored a cancelled progress reporter");
            } catch (EngineCancelledException expected) {
                assertTrue(expected.getMessage().contains(engine.id()));
            }
        }
    }

    @Test
    public void symmetricEnginesAreChargedForBothDirectionsTheyCompute() {
        // Jaccard is symmetric per pair but reports both directions, one row per
        // source object each, so it pays for both. It used to be charged half.
        EngineInputs inputs = containedPair();
        JaccardDiceEngine jaccard = new JaccardDiceEngine();
        ContainmentEngine containment = new ContainmentEngine();

        assertTrue("Jaccard is symmetric", jaccard.isSymmetric());
        assertFalse("containment carries a directional class", containment.isSymmetric());

        assertEquals(2 * jaccard.relativeCost(),
                EngineRegistry.estimateCost(
                        Arrays.<ColocEngine>asList(jaccard), inputs), EPSILON);
        assertEquals(2 * containment.relativeCost(),
                EngineRegistry.estimateCost(
                        Arrays.<ColocEngine>asList(containment), inputs), EPSILON);
    }

    // ------------------------------------------------------------------
    // Bounding box.
    // ------------------------------------------------------------------

    @Test
    public void boundingBoxReportsTheSameAsymmetryAsTheVoxelExactMeasure() {
        EngineResult result = new BoundingBoxEngine()
                .compute(containedPair(), EngineProgress.SILENT);

        assertEquals(2, result.directions().size());
        // A's box is 10x10, B's is 2x2, and they intersect in B's whole box.
        assertEquals(4.0, only(result, "A", "B").value(), EPSILON);
        assertEquals(100.0, only(result, "B", "A").value(), EPSILON);
        assertEquals(1, only(result, "A", "B").partnerLabel());
    }

    @Test
    public void boundingBoxCallsFullOverlapWhereTheVoxelExactMeasureFindsNone() {
        // The over-calling this engine's javadoc warns about, made numeric. Two
        // diagonals cross without sharing a single voxel, and their bounding
        // boxes are the same 10x10 square.
        EngineInputs inputs = crossedDiagonals();

        EngineResult boxes = new BoundingBoxEngine().compute(inputs, EngineProgress.SILENT);
        EngineResult voxels = new VolumeOverlapEngine().compute(inputs, EngineProgress.SILENT);

        assertEquals("boxes coincide completely",
                100.0, only(boxes, "A", "B").value(), EPSILON);
        assertTrue("and are therefore called colocalized",
                only(boxes, "A", "B").isCoincident());

        assertEquals("not one voxel is shared",
                0.0, only(voxels, "A", "B").value(), EPSILON);
        assertFalse(only(voxels, "A", "B").isCoincident());
    }

    // ------------------------------------------------------------------
    // Jaccard and Dice.
    // ------------------------------------------------------------------

    @Test
    public void jaccardIsIdenticalInBothDirectionsWhereOverlapIsNot() {
        EngineInputs inputs = containedPair();
        EngineResult jaccard = new JaccardDiceEngine().compute(inputs, EngineProgress.SILENT);
        EngineResult overlap = new VolumeOverlapEngine().compute(inputs, EngineProgress.SILENT);

        assertEquals(2, jaccard.directions().size());

        // 4 shared voxels over a union of 100 + 4 - 4 = 100.
        assertEquals(0.04, only(jaccard, "A", "B").value(), EPSILON);
        assertEquals(0.04, only(jaccard, "B", "A").value(), EPSILON);

        // The same pair, the same shared voxels, read 4% and 100% by volumetric
        // overlap. Neither pair of numbers is wrong; they answer different
        // questions, which is the reason both engines exist.
        assertEquals(4.0, only(overlap, "A", "B").value(), EPSILON);
        assertEquals(100.0, only(overlap, "B", "A").value(), EPSILON);

        assertEquals(8.0 / 104.0,
                JaccardDiceEngine.diceFromJaccard(only(jaccard, "A", "B").value()), EPSILON);
        assertFalse("0.04 is below the 0.5 default cutoff",
                only(jaccard, "A", "B").isCoincident());
    }

    @Test
    public void jaccardIsOneForIdenticalObjectsAndZeroForDisjointOnes() {
        EngineResult identical = new JaccardDiceEngine()
                .compute(identicalPair(), EngineProgress.SILENT);
        assertEquals(1.0, only(identical, "A", "B").value(), EPSILON);
        assertEquals(1.0, JaccardDiceEngine.diceFromJaccard(
                only(identical, "A", "B").value()), EPSILON);
        assertTrue(only(identical, "A", "B").isCoincident());

        EngineResult disjoint = new JaccardDiceEngine()
                .compute(disjointPair(), EngineProgress.SILENT);
        ObjectScore score = only(disjoint, "A", "B");
        assertEquals("an empty intersection is zero, not undefined",
                0.0, score.value(), EPSILON);
        assertEquals(ObjectScore.NO_PARTNER, score.partnerLabel());
        assertFalse(score.isCoincident());
    }

    @Test
    public void jaccardAndDiceOnAPartialOverlap() {
        // Two 4x4 squares sharing a 2x2 corner: 4 / (16 + 16 - 4) = 1/7.
        EngineResult result = new JaccardDiceEngine()
                .compute(partialPair(), EngineProgress.SILENT);
        assertEquals(4.0 / 28.0, only(result, "A", "B").value(), EPSILON);
        assertEquals(8.0 / 32.0,
                JaccardDiceEngine.diceFromJaccard(only(result, "A", "B").value()), EPSILON);
    }

    // ------------------------------------------------------------------
    // Containment.
    // ------------------------------------------------------------------

    @Test
    public void containmentSeesTheNestingThatOverlapAloneHides() {
        EngineInputs inputs = containedPair();
        ContainmentEngine engine = new ContainmentEngine();
        EngineResult result = engine.compute(inputs, EngineProgress.SILENT);
        EngineResult overlap = new VolumeOverlapEngine().compute(inputs, EngineProgress.SILENT);

        // Volumetric overlap reads 4% from the large object's end and drops it
        // below any usual threshold. It is 4% because the containment is total.
        assertFalse(only(overlap, "A", "B").isCoincident());

        ObjectScore aToB = only(result, "A", "B");
        assertEquals(1.0, aToB.value(), EPSILON);
        assertTrue(aToB.isCoincident());
        assertEquals(ContainmentEngine.ContainmentClass.TARGET_INSIDE_SOURCE,
                engine.classOf(4.0, 100.0));

        ObjectScore bToA = only(result, "B", "A");
        assertEquals(1.0, bToA.value(), EPSILON);
        assertEquals(ContainmentEngine.ContainmentClass.SOURCE_INSIDE_TARGET,
                engine.classOf(100.0, 4.0));
    }

    @Test
    public void containmentIsZeroForDisjointAndForMerelyPartialOverlap() {
        ContainmentEngine engine = new ContainmentEngine();

        ObjectScore disjoint = only(
                engine.compute(disjointPair(), EngineProgress.SILENT), "A", "B");
        assertEquals(0.0, disjoint.value(), EPSILON);
        assertFalse(disjoint.isCoincident());
        assertEquals(ContainmentEngine.ContainmentClass.DISJOINT,
                engine.classOf(0.0, 0.0));

        // 25% each way: they overlap, neither is inside the other.
        ObjectScore partial = only(
                engine.compute(partialPair(), EngineProgress.SILENT), "A", "B");
        assertEquals(0.0, partial.value(), EPSILON);
        assertFalse(partial.isCoincident());
        assertEquals(ContainmentEngine.ContainmentClass.PARTIAL,
                engine.classOf(25.0, 25.0));
    }

    @Test
    public void identicalObjectsAreContainedFromBothEnds() {
        EngineResult result = new ContainmentEngine()
                .compute(identicalPair(), EngineProgress.SILENT);
        assertEquals(1.0, only(result, "A", "B").value(), EPSILON);
        assertEquals(1.0, only(result, "B", "A").value(), EPSILON);
        assertEquals("mutual containment is reported from the source's end",
                ContainmentEngine.ContainmentClass.SOURCE_INSIDE_TARGET,
                ContainmentEngine.classify(100.0, 100.0,
                        ContainmentEngine.DEFAULT_CONTAINMENT_PERCENT));
    }

    @Test
    public void movingTheToleranceAcrossTheMeasuredPercentageFlipsTheVerdict() {
        // The partial pair sits at exactly 25% each way. Walking the tolerance
        // across that value flips the verdict, which pins down the percentage the
        // engine actually computed from the voxels — the classify() checks above
        // only prove the rule, not the arithmetic feeding it.
        assertEquals(1.0, only(new ContainmentEngine(25.0)
                .compute(partialPair(), EngineProgress.SILENT), "A", "B").value(), EPSILON);
        assertEquals(0.0, only(new ContainmentEngine(25.000001)
                .compute(partialPair(), EngineProgress.SILENT), "A", "B").value(), EPSILON);

        // And the contained pair sits at exactly 4% one way, 100% the other, so
        // the strictest setting the measure admits still calls it contained.
        assertEquals(1.0, only(new ContainmentEngine(100.0)
                .compute(containedPair(), EngineProgress.SILENT), "A", "B").value(), EPSILON);
        try {
            new ContainmentEngine(100.000001);
            fail("nothing is more than 100% inside anything, so asking for it"
                    + " must fail rather than quietly report nothing coincident");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("between 0 and 100"));
        }
    }

    @Test
    public void theContainmentToleranceIsWhereTheClassChanges() {
        double tolerance = ContainmentEngine.DEFAULT_CONTAINMENT_PERCENT;
        assertEquals(ContainmentEngine.ContainmentClass.SOURCE_INSIDE_TARGET,
                ContainmentEngine.classify(98.0, 4.0, tolerance));
        assertEquals(ContainmentEngine.ContainmentClass.PARTIAL,
                ContainmentEngine.classify(97.9, 4.0, tolerance));
        // A stricter engine calls the same object partial.
        assertEquals(ContainmentEngine.ContainmentClass.PARTIAL,
                new ContainmentEngine(100.0).classOf(98.0, 4.0));
    }

    // ------------------------------------------------------------------
    // Distance tolerance.
    // ------------------------------------------------------------------

    @Test
    public void centroidDistanceIsMeasuredToTheNearestTargetObject() {
        // Centroids at (0.5, 0.5) and (6.5, 6.5): sqrt(36 + 36).
        double expected = Math.sqrt(72.0);
        EngineResult result = new DistanceToleranceEngine(10.0)
                .compute(disjointPair(), EngineProgress.SILENT);

        ObjectScore aToB = only(result, "A", "B");
        assertEquals(expected, aToB.value(), 1e-9);
        assertEquals(1, aToB.partnerLabel());
        assertTrue("8.49 is within a tolerance of 10", aToB.isCoincident());
        assertEquals("distance is the same measured either way",
                expected, only(result, "B", "A").value(), EPSILON);

        assertFalse("and outside the default tolerance of 5",
                only(new DistanceToleranceEngine().compute(
                        disjointPair(), EngineProgress.SILENT), "A", "B").isCoincident());
    }

    @Test
    public void distanceIsZeroForConcentricObjectsThatOverlapBarely() {
        // The contained pair share only 4% of the large object, yet their
        // centroids are the same point. Distance and overlap disagree about this
        // pair completely, which is the comparison the suite exists to show.
        EngineResult result = new DistanceToleranceEngine()
                .compute(containedPair(), EngineProgress.SILENT);
        assertEquals(0.0, only(result, "A", "B").value(), EPSILON);
        assertTrue(only(result, "A", "B").isCoincident());
    }

    @Test
    public void calibrationScalesTheDistanceAndTheToleranceDoesNotFollow() {
        // Same pixels, 2 units per pixel: every distance doubles while a
        // tolerance of 10 stays 10. An uncalibrated run would have called this
        // pair colocalized; a calibrated one does not.
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 2.0;
        calibration.pixelHeight = 2.0;
        calibration.pixelDepth = 2.0;
        calibration.setUnit("micron");

        EngineInputs inputs = EngineInputs.builder(Arrays.asList(
                        square("A", 0, 2, 1), square("B", 6, 8, 1)))
                .channelNames(Arrays.asList("A", "B"))
                .calibration(calibration)
                .build();

        EngineResult result = new DistanceToleranceEngine(10.0)
                .compute(inputs, EngineProgress.SILENT);
        assertEquals(2.0 * Math.sqrt(72.0), only(result, "A", "B").value(), 1e-9);
        assertFalse("16.97 microns is outside a 10 micron tolerance",
                only(result, "A", "B").isCoincident());
    }

    // ------------------------------------------------------------------
    // Fixtures.
    // ------------------------------------------------------------------

    private static List<ColocEngine> stageFourEngines() {
        return Arrays.<ColocEngine>asList(
                new BoundingBoxEngine(),
                new JaccardDiceEngine(),
                new ContainmentEngine(),
                new DistanceToleranceEngine());
    }

    /** A fills the whole field; B is a 2x2 square entirely inside it. */
    private static EngineInputs containedPair() {
        return pair(square("A", 0, SIZE, 1), square("B", 4, 6, 1));
    }

    private static EngineInputs identicalPair() {
        return pair(square("A", 0, SIZE, 1), square("B", 0, SIZE, 1));
    }

    private static EngineInputs disjointPair() {
        return pair(square("A", 0, 2, 1), square("B", 6, 8, 1));
    }

    private static EngineInputs partialPair() {
        return pair(square("A", 0, 4, 1), square("B", 2, 6, 1));
    }

    private static EngineInputs crossedDiagonals() {
        return pair(diagonal("A", false), diagonal("B", true));
    }

    private static EngineInputs pair(ImagePlus a, ImagePlus b) {
        return EngineInputs.builder(Arrays.asList(a, b))
                .channelNames(Arrays.asList("A", "B"))
                .build();
    }

    /** Single-slice label image with {@code value} filling [from, to) in x and y. */
    private static ImagePlus square(String title, int from, int to, int value) {
        ShortProcessor processor = new ShortProcessor(SIZE, SIZE);
        for (int y = from; y < to; y++) {
            for (int x = from; x < to; x++) {
                processor.set(x, y, value);
            }
        }
        return new ImagePlus(title, processor);
    }

    /**
     * One voxel per row, on the main diagonal or the anti-diagonal. The two never
     * meet — {@code x == y} and {@code x == 9 - y} have no integer solution on an
     * even-sided field — and both fill the same bounding box.
     */
    private static ImagePlus diagonal(String title, boolean anti) {
        ShortProcessor processor = new ShortProcessor(SIZE, SIZE);
        for (int i = 0; i < SIZE; i++) {
            processor.set(anti ? SIZE - 1 - i : i, i, 1);
        }
        return new ImagePlus(title, processor);
    }

    private static ObjectScore only(EngineResult result, String source, String target) {
        for (DirectionKey direction : result.directions()) {
            if (direction.sourceName().equals(source)
                    && direction.targetName().equals(target)) {
                List<ObjectScore> scores = result.scores(direction);
                assertEquals("every fixture has one object per channel",
                        1, scores.size());
                return scores.get(0);
            }
        }
        throw new AssertionError("no direction " + source + " -> " + target
                + " in " + result.engineId());
    }

    /** Cancelled before the first check, which every engine must notice. */
    private static final EngineProgress CANCELLED = new EngineProgress() {
        @Override
        public void report(String stage, double fraction) {
            // deliberately empty
        }

        @Override
        public boolean isCancelled() {
            return true;
        }
    };
}
