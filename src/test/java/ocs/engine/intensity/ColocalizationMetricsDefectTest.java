package ocs.engine.intensity;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.process.ColorProcessor;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * One test per defect-ledger entry, each failing against the unfixed code.
 *
 * <p>Defects 4 and 10 live in {@link WelfordAccumulatorTest}, where the two
 * accumulators can be compared directly. The other eight are here.
 */
public class ColocalizationMetricsDefectTest {

    private static final int SIDE = 64;

    // ------------------------------------------------------------------
    // Defect 1 — a fallback Costes threshold must be distinguishable
    // ------------------------------------------------------------------

    @Test
    public void aFittedCostesThresholdSaysSo() {
        double[][] pair = correlatedPair(SIDE * SIDE, 11L);
        ColocalizationMetrics.Result result = ColocalizationMetrics.compute(
                pair[0], pair[1], SIDE, SIDE, 1);

        assertTrue("this fixture is strongly correlated, so the bisection must find a"
                + " threshold; it reported r = " + result.pearson(), result.pearson() > 0.3);
        assertTrue("a successful bisection must be flagged as fitted",
                result.costesThresholdFitted());
        assertEquals("a fitted threshold carries no explanation", "", result.thresholdNote());
    }

    @Test
    public void aFallbackCostesThresholdIsFlaggedAndExplained() {
        // Anti-correlated, so the bisection is guaranteed to take its very first
        // bail-out rather than merely being likely to.
        double[] a = uniform(SIDE * SIDE, 21L, 0.0, 100.0);
        double[] b = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            b[i] = 200.0 - a[i];
        }

        ColocalizationMetrics.Result result = ColocalizationMetrics.compute(
                a, b, SIDE, SIDE, 1,
                ColocalizationMetrics.Options.builder().permutations(8).build(), null);
        assertTrue("the fixture must be anti-correlated", result.pearson() < -0.9);

        // This is the whole defect. The unfixed code returned exactly this number
        // and nothing else, so a reader could not tell it from a fitted one.
        assertEquals("the fallback is the domain minimum, as before",
                min(a), result.costesTa(), 1.0e-12);
        assertFalse("an unfitted threshold must not read as fitted",
                result.costesThresholdFitted());
        assertTrue("and it must say which of the five bail-outs it took, got '"
                        + result.thresholdNote() + "'",
                result.thresholdNote().contains("not fitted"));

        // And the consequence the ledger is really about: everything derived from
        // that threshold is a different quantity, and now carries the same flag.
        assertFalse(Double.isNaN(result.mandersM1()));
        assertFalse(result.costesThresholdFitted());
    }

    // ------------------------------------------------------------------
    // Defect 2 — block depth must be at least 2 on a stack
    // ------------------------------------------------------------------

    @Test
    public void aStackGetsBlocksAtLeastTwoSlicesDeep() {
        int side = 24;
        int depth = 6;
        double[][] pair = correlatedPair(side * side * depth, 31L);

        ColocalizationMetrics.Result result = ColocalizationMetrics.compute(
                pair[0], pair[1], side, side, depth,
                ColocalizationMetrics.Options.builder().permutations(8).build(),
                null);

        assertTrue("depth-1 blocks preserve no axial correlation; the fix is a floor of "
                        + ColocalizationMetrics.MIN_STACK_BLOCK_DEPTH + ", got "
                        + result.blockDepth(),
                result.blockDepth() >= ColocalizationMetrics.MIN_STACK_BLOCK_DEPTH);
    }

    @Test
    public void askingForDepthOneOnAStackIsRefused() {
        int side = 16;
        int depth = 4;
        double[][] pair = correlatedPair(side * side * depth, 32L);
        try {
            ColocalizationMetrics.compute(pair[0], pair[1], side, side, depth,
                    ColocalizationMetrics.Options.builder()
                            .blockDepth(1).permutations(4).build(),
                    null);
            fail("depth-1 blocks on a stack are the defect, not a setting");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("axial"));
        }
    }

    @Test
    public void aSingleSliceStillUsesDepthOne() {
        double[][] pair = correlatedPair(SIDE * SIDE, 33L);
        ColocalizationMetrics.Result result = ColocalizationMetrics.compute(
                pair[0], pair[1], SIDE, SIDE, 1,
                ColocalizationMetrics.Options.builder().permutations(4).build(), null);
        assertEquals("a 2D image has no axial structure to preserve",
                1, result.blockDepth());
    }

    // ------------------------------------------------------------------
    // Defect 3 — block size must follow the calibration, not a constant
    // ------------------------------------------------------------------

    @Test
    public void blockSizeFollowsTheVoxelSizeAndThePsf() {
        double[][] pair = correlatedPair(SIDE * SIDE, 41L);

        ColocalizationMetrics.Result fine = ColocalizationMetrics.compute(
                pair[0], pair[1], SIDE, SIDE, 1,
                ColocalizationMetrics.Options.builder()
                        .psf(0.25, 0.7).voxelSize(0.05, 0.05, 0.3).permutations(4).build(),
                null);
        ColocalizationMetrics.Result coarse = ColocalizationMetrics.compute(
                pair[0], pair[1], SIDE, SIDE, 1,
                ColocalizationMetrics.Options.builder()
                        .psf(0.25, 0.7).voxelSize(0.125, 0.125, 0.3).permutations(4).build(),
                null);

        assertEquals("0.25 um of PSF spans five 0.05 um voxels", 5, fine.blockWidth());
        assertEquals("and two 0.125 um voxels", 2, coarse.blockWidth());
        assertNotEquals("the same PSF at a different sampling is a different block, which"
                        + " is what makes p values comparable between acquisitions",
                fine.blockWidth(), coarse.blockWidth());
        assertTrue(fine.blockSizeDerived());
        assertTrue(coarse.blockSizeDerived());
    }

    @Test
    public void anUncalibratedImageFallsBackAndSaysItFellBack() {
        double[][] pair = correlatedPair(SIDE * SIDE, 42L);
        ColocalizationMetrics.Result result = ColocalizationMetrics.compute(
                pair[0], pair[1], SIDE, SIDE, 1,
                ColocalizationMetrics.Options.builder().permutations(4).build(), null);
        assertEquals(ColocalizationMetrics.DEFAULT_BLOCK_SIZE, result.blockWidth());
        assertFalse("the fallback must be recorded as a fallback",
                result.blockSizeDerived());
    }

    // ------------------------------------------------------------------
    // Defect 5 — Manders must be refused on negative intensities
    // ------------------------------------------------------------------

    @Test
    public void mandersIsRefusedRatherThanReturnedOnNegativeIntensities() {
        double[][] pair = correlatedPair(SIDE * SIDE, 51L);
        // What a background subtraction does to a float image.
        for (int i = 0; i < pair[0].length; i++) {
            pair[0][i] -= 60.0;
        }

        ColocalizationMetrics.Result result = ColocalizationMetrics.compute(
                pair[0], pair[1], SIDE, SIDE, 1,
                ColocalizationMetrics.Options.builder().permutations(4).build(), null);

        assertTrue("channel A goes negative, so M1's denominator is not a total signal",
                result.mandersRefusedNegative());
        assertTrue("no number may be returned for a refused Manders",
                Double.isNaN(result.mandersM1()));
        assertTrue(result.mandersNote(), result.mandersNote().contains("non-negative"));

        // The refusal is confined to the metric that is undefined. Pearson is
        // shift-invariant and is still a correct answer here, and throwing it away
        // would be its own kind of dishonesty.
        assertFalse("Pearson is unaffected by a constant offset",
                Double.isNaN(result.pearson()));
        assertFalse("channel B is still non-negative, so M2 stands",
                Double.isNaN(result.mandersM2()));
    }

    @Test
    public void nonNegativeIntensitiesStillGiveManders() {
        double[][] pair = correlatedPair(SIDE * SIDE, 52L);
        ColocalizationMetrics.Result result = ColocalizationMetrics.compute(
                pair[0], pair[1], SIDE, SIDE, 1,
                ColocalizationMetrics.Options.builder().permutations(4).build(), null);
        assertFalse(result.mandersRefusedNegative());
        assertTrue(result.mandersM1() >= 0.0 && result.mandersM1() <= 1.0);
        assertTrue(result.mandersM2() >= 0.0 && result.mandersM2() <= 1.0);
    }

    // ------------------------------------------------------------------
    // Defect 6 — the p floor must be reported beside p
    // ------------------------------------------------------------------

    @Test
    public void theFloorOnPIsReportedAlongsideIt() {
        double[][] pair = correlatedPair(SIDE * SIDE, 61L);

        ColocalizationMetrics.Result hundred = ColocalizationMetrics.compute(
                pair[0], pair[1], SIDE, SIDE, 1,
                ColocalizationMetrics.Options.builder().permutations(100).workers(1).build(),
                null);
        ColocalizationMetrics.Result thousand = ColocalizationMetrics.compute(
                pair[0], pair[1], SIDE, SIDE, 1,
                ColocalizationMetrics.Options.builder().permutations(1000).workers(1).build(),
                null);

        assertEquals("100 permutations can never express a p below 1/101",
                1.0 / 101.0, hundred.pFloor(), 1.0e-15);
        assertEquals(1.0 / 1001.0, thousand.pFloor(), 1.0e-15);

        // A strongly colocalized fixture: no block shuffle beats it, so p lands on
        // its floor. Without pAtFloor a reader sees "p = 0.0099" and reads it as a
        // measured significance rather than as the smallest number available.
        assertTrue("this fixture should saturate the null", hundred.pAtFloor());
        assertEquals(hundred.pFloor(), hundred.costesP(), 1.0e-15);
        assertEquals(100, hundred.permutations());

        assertTrue("raising the permutation count is what actually lowers the floor",
                thousand.costesP() < hundred.costesP());
    }

    // ------------------------------------------------------------------
    // Defect 7 — a skipped randomization must be a column, not a note
    // ------------------------------------------------------------------

    @Test
    public void aSkippedRandomizationIsFlagged() {
        double[][] pair = correlatedPair(SIDE * SIDE, 71L);
        ColocalizationMetrics.Result result = ColocalizationMetrics.compute(
                pair[0], pair[1], SIDE, SIDE, 1,
                ColocalizationMetrics.Options.builder().voxelLimit(100L).build(), null);

        assertTrue("above the voxel limit the null is not run", result.randomizationSkipped());
        assertTrue("and p must be absent rather than large", Double.isNaN(result.costesP()));
        assertTrue(result.note(), result.note().contains("skipped"));

        // The rest of the metrics are unaffected — the skip is about the null only,
        // which is exactly why a batch table can otherwise look uniform.
        assertFalse(Double.isNaN(result.pearson()));
        assertEquals(SIDE * SIDE, result.voxelsAnalyzed());
    }

    @Test
    public void aRandomizationWithinTheLimitIsNotFlagged() {
        double[][] pair = correlatedPair(SIDE * SIDE, 72L);
        ColocalizationMetrics.Result result = ColocalizationMetrics.compute(
                pair[0], pair[1], SIDE, SIDE, 1,
                ColocalizationMetrics.Options.builder().permutations(8).build(), null);
        assertFalse(result.randomizationSkipped());
        assertFalse(Double.isNaN(result.costesP()));
    }

    // ------------------------------------------------------------------
    // Defect 8 — colour and composite images must be refused
    // ------------------------------------------------------------------

    @Test
    public void anRgbImageIsRefusedRatherThanRead() {
        ImagePlus rgb = new ImagePlus("rgb", new ColorProcessor(16, 16));
        ImagePlus grey = grayImage("grey", 16, 16);
        try {
            ColocalizationMetrics.compute(rgb, grey);
            fail("getf() on an RGB processor returns a packed colour word, which reads"
                    + " as a plausible intensity");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("colour"));
        }
    }

    @Test
    public void aCompositeImageIsRefusedRatherThanRead() {
        ImageStack stack = new ImageStack(8, 8);
        for (int i = 0; i < 4; i++) {
            stack.addSlice(new ShortProcessor(8, 8));
        }
        ImagePlus composite = new ImagePlus("composite", stack);
        composite.setDimensions(2, 2, 1);
        try {
            ColocalizationMetrics.compute(composite, composite);
            fail("a composite's stack interleaves channels, so reading it as one volume"
                    + " pairs each voxel with the wrong partner");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("composite"));
        }
    }

    @Test
    public void aPlainGrayscalePairIsAccepted() {
        ImagePlus a = grayImage("a", 16, 16);
        ImagePlus b = grayImage("b", 16, 16);
        ColocalizationMetrics.Result result = ColocalizationMetrics.compute(a, b);
        assertEquals(256, result.voxelsAnalyzed());
    }

    // ------------------------------------------------------------------
    // Defect 9 — the randomization must shuffle within the ROI, not its box
    // ------------------------------------------------------------------

    /**
     * The fixture is a right triangle filling half of a 48x48 square, so its
     * bounding box holds roughly twice the voxels the ROI does and the two answers
     * genuinely differ. Everything outside the triangle is set to NaN, which makes
     * any contamination arithmetically loud: one NaN reaching an accumulator turns
     * that permutation's correlation into NaN and moves the p value.
     */
    @Test
    public void randomizationShufflesWithinTheRoiRatherThanItsBoundingBox() {
        int side = 48;
        int count = side * side;
        boolean[] triangle = triangleMask(side);
        long insideCount = countTrue(triangle);

        // Independent channels, so the observed correlation sits near zero and
        // roughly half the permutations beat it. That is what makes the p value
        // sensitive enough for a single lost permutation to show up.
        double[] cleanA = uniform(count, 91L, 0.0, 100.0);
        double[] cleanB = uniform(count, 92L, 0.0, 100.0);
        double[] poisonedA = poisonOutside(cleanA, triangle);
        double[] poisonedB = poisonOutside(cleanB, triangle);

        ColocalizationMetrics.Options roiOptions = ColocalizationMetrics.Options.builder()
                .domainMask(triangle).permutations(50).seed(7L).workers(1).build();

        ColocalizationMetrics.Result clean = ColocalizationMetrics.compute(
                cleanA, cleanB, side, side, 1, roiOptions, null);
        ColocalizationMetrics.Result poisoned = ColocalizationMetrics.compute(
                poisonedA, poisonedB, side, side, 1, roiOptions, null);

        assertEquals("only ROI voxels may be measured",
                insideCount, clean.voxelsAnalyzed());
        assertTrue("the fixture must produce a p away from both ends, or a lost"
                        + " permutation could not be detected; got " + clean.costesP(),
                clean.costesP() > clean.pFloor() && clean.costesP() < 1.0);

        // The proof. Every voxel outside the triangle is NaN, and the two runs agree
        // to the bit — so the block shuffle read none of them.
        assertEquals("the shuffle must not read a voxel outside the ROI",
                clean.pearson(), poisoned.pearson(), 0.0);
        assertEquals("the shuffle must not read a voxel outside the ROI",
                clean.costesP(), poisoned.costesP(), 0.0);
        assertFalse(Double.isNaN(poisoned.costesP()));

        // And the bounding box really is a different domain on this fixture, so the
        // test above is not vacuous. Shuffling within it reaches the poisoned
        // voxels immediately — which is the Coloc 2 behaviour this avoids.
        boolean[] box = boundingBoxMask(triangle, side);
        ColocalizationMetrics.Result viaBox = ColocalizationMetrics.compute(
                poisonedA, poisonedB, side, side, 1,
                roiOptions.toBuilder().domainMask(box).build(), null);
        assertTrue("the bounding box must hold strictly more voxels than the ROI",
                countTrue(box) > insideCount);
        assertTrue("shuffling within the bounding box reaches voxels the ROI excludes",
                Double.isNaN(viaBox.pearson()));
    }

    @Test
    public void noRoiMeansTheWholeImage() {
        double[][] pair = correlatedPair(SIDE * SIDE, 93L);
        ColocalizationMetrics.Result result = ColocalizationMetrics.compute(
                pair[0], pair[1], SIDE, SIDE, 1,
                ColocalizationMetrics.Options.builder().permutations(8).build(), null);
        assertEquals(SIDE * SIDE, result.voxelsAnalyzed());
    }

    @Test
    public void aRoiSetBecomesAVoxelMask() {
        Roi rectangle = new Roi(2, 3, 4, 5);
        boolean[] mask = ColocalizationMetrics.maskFrom(
                Collections.singletonList(rectangle), 16, 16, 3);
        assertEquals("an ROI with no slice position applies to every slice",
                4 * 5 * 3, countTrue(mask));

        PolygonRoi triangle = new PolygonRoi(
                new int[] {0, 10, 0}, new int[] {0, 0, 10}, 3, Roi.POLYGON);
        boolean[] triangleMask = ColocalizationMetrics.maskFrom(
                Collections.singletonList((Roi) triangle), 16, 16, 1);
        long inside = countTrue(triangleMask);
        assertTrue("a polygon must not fill its bounding box, filled " + inside,
                inside > 0 && inside < 100);

        assertNull("an empty ROI set means no restriction at all",
                ColocalizationMetrics.maskFrom(Collections.<Roi>emptyList(), 8, 8, 1));
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * A dim uncorrelated background with bright correlated puncta on top — the
     * shape of data Costes' method was designed for, and the only shape on which
     * the threshold bisection has something to find.
     */
    private static double[][] correlatedPair(int count, long seed) {
        Random random = new Random(seed);
        double[] a = new double[count];
        double[] b = new double[count];
        for (int i = 0; i < count; i++) {
            a[i] = 5.0 + 15.0 * random.nextDouble();
            b[i] = 5.0 + 15.0 * random.nextDouble();
            if (i % 11 == 0) {
                double shared = random.nextDouble();
                a[i] = 120.0 + 130.0 * shared;
                b[i] = 100.0 + 140.0 * shared;
            }
        }
        return new double[][] {a, b};
    }

    private static double[] uniform(int count, long seed, double low, double high) {
        Random random = new Random(seed);
        double[] values = new double[count];
        for (int i = 0; i < count; i++) {
            values[i] = low + (high - low) * random.nextDouble();
        }
        return values;
    }

    /** Lower-left right triangle: {@code x + y < side}. */
    private static boolean[] triangleMask(int side) {
        boolean[] mask = new boolean[side * side];
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                mask[y * side + x] = x + y < side;
            }
        }
        return mask;
    }

    private static boolean[] boundingBoxMask(boolean[] mask, int side) {
        int minX = side;
        int maxX = -1;
        int minY = side;
        int maxY = -1;
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                if (mask[y * side + x]) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        boolean[] box = new boolean[mask.length];
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                box[y * side + x] = true;
            }
        }
        return box;
    }

    private static double[] poisonOutside(double[] values, boolean[] mask) {
        double[] copy = Arrays.copyOf(values, values.length);
        for (int i = 0; i < copy.length; i++) {
            if (!mask[i]) {
                copy[i] = Double.NaN;
            }
        }
        return copy;
    }

    private static long countTrue(boolean[] mask) {
        long count = 0L;
        for (int i = 0; i < mask.length; i++) {
            if (mask[i]) {
                count++;
            }
        }
        return count;
    }

    private static double min(double[] values) {
        double smallest = Double.POSITIVE_INFINITY;
        for (int i = 0; i < values.length; i++) {
            smallest = Math.min(smallest, values[i]);
        }
        return smallest;
    }

    private static ImagePlus grayImage(String title, int width, int height) {
        ShortProcessor processor = new ShortProcessor(width, height);
        Random random = new Random(title.hashCode());
        for (int i = 0; i < width * height; i++) {
            processor.set(i, random.nextInt(4096));
        }
        return new ImagePlus(title, processor);
    }
}
