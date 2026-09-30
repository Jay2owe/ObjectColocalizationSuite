package ocs.engine.spatial;

import ij.ImagePlus;
import ij.gui.OvalRoi;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.process.ShortProcessor;
import ocs.engine.EngineInputs;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The bridge from label images to calibrated points, tested where it can be
 * wrong without looking wrong.
 *
 * <p>Three of the four assertions here would pass a plausible bug: a curve
 * computed on pixel indices, on points outside the window, or on points ordered
 * by hash rather than by label all plot perfectly well.
 */
public class PointPatternTest {

    @Test
    public void centroidsAreInCalibratedUnitsNotPixelIndices() {
        // One object over pixels x = {2,3}, y = {4,5}. Its centre is at pixel-edge
        // coordinate (3.0, 5.0), which on a 0.5 x 0.25 pixel is (1.5, 1.25).
        ShortProcessor processor = new ShortProcessor(10, 10);
        processor.set(2, 4, 1);
        processor.set(3, 4, 1);
        processor.set(2, 5, 1);
        processor.set(3, 5, 1);
        ImagePlus object = new ImagePlus("A", processor);

        Calibration calibration = new Calibration();
        calibration.pixelWidth = 0.5;
        calibration.pixelHeight = 0.25;
        calibration.setUnit("micron");

        PointPattern pattern = PointPattern.from(SpatialFixtures.calibrated(
                calibration, object, SpatialFixtures.labels("B", 10, 10,
                        new int[][] {{7, 7}})));

        // Exactly, not nearly. Dropping the calibration is the single most likely
        // bug in this class and it leaves every radius in the wrong unit while the
        // curve still plots and the envelope still closes.
        assertArrayEquals(new double[] {1.5, 1.25}, pattern.points(0)[0], 0.0);
        assertEquals("micron", pattern.unit());

        // And the window follows the same calibration, or opa-core would reject
        // every point as outside it.
        assertEquals(0.0, pattern.window().getMinX(), 0.0);
        assertEquals(5.0, pattern.window().getMaxX(), 0.0);
        assertEquals(2.5, pattern.window().getMaxY(), 0.0);
    }

    @Test
    public void calibrationOriginMovesThePointsAndTheWindowTogether() {
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 2.0;
        calibration.pixelHeight = 2.0;
        calibration.xOrigin = 3.0;
        calibration.yOrigin = 1.0;

        PointPattern pattern = PointPattern.from(SpatialFixtures.calibrated(calibration,
                SpatialFixtures.labels("A", 8, 8, new int[][] {{4, 4}}),
                SpatialFixtures.labels("B", 8, 8, new int[][] {{6, 6}})));

        // (4 + 0.5 - 3) * 2 = 3.0 in x, (4 + 0.5 - 1) * 2 = 7.0 in y.
        assertArrayEquals(new double[] {3.0, 7.0}, pattern.points(0)[0], 0.0);
        assertEquals(-6.0, pattern.window().getMinX(), 0.0);
        assertEquals(-2.0, pattern.window().getMinY(), 0.0);
        assertTrue(pattern.window().contains(3.0, 7.0));
    }

    @Test
    public void everyPointLiesInsideTheWindow() {
        // opa-core throws on the first point outside the window, so a whole run
        // would be lost rather than one channel pair. Checked here instead.
        PointPattern pattern = PointPattern.from(SpatialFixtures.inputs(
                SpatialFixtures.labels("A", 30, 30,
                        SpatialFixtures.grid(5, 5, 6, 0, 0)),
                SpatialFixtures.labels("B", 30, 30,
                        SpatialFixtures.grid(5, 5, 6, 2, 2))));

        for (int channel = 0; channel < pattern.channelCount(); channel++) {
            for (double[] point : pattern.points(channel)) {
                assertTrue("point " + Arrays.toString(point) + " left the window",
                        pattern.window().contains(point[0], point[1]));
            }
        }
    }

    @Test
    public void pointsComeBackAscendingByLabelWhateverOrderTheyWereWritten() {
        // Cross-K sums a term per pair and floating-point addition is not
        // associative, so point order decides the last bits of every curve. A
        // hash-ordered extraction would still produce a correct-looking curve and
        // break bit-identity between runs.
        //
        // Labels 20, 3 and 11 rather than 1, 2 and 3, and that is the whole point
        // of the fixture. A small dense label set hashes into ascending order by
        // accident — Integer's hash is its own value — so 1, 2, 3 would come back
        // ascending from a hash map too and this test would pass a broken
        // extraction. These three land in buckets 4, 3 and 11 of a sixteen-bucket
        // table, so hash order is 3, 20, 11 and label order is 3, 11, 20.
        ShortProcessor processor = new ShortProcessor(20, 20);
        processor.set(15, 15, 20);
        processor.set(3, 3, 3);
        processor.set(9, 1, 11);
        ImagePlus scrambled = new ImagePlus("A", processor);

        PointPattern pattern = PointPattern.from(SpatialFixtures.inputs(scrambled,
                SpatialFixtures.labels("B", 20, 20, new int[][] {{5, 5}})));

        double[][] points = pattern.points(0);
        assertEquals(3, points.length);
        assertArrayEquals("label 3", new double[] {3.5, 3.5}, points[0], 0.0);
        assertArrayEquals("label 11", new double[] {9.5, 1.5}, points[1], 0.0);
        assertArrayEquals("label 20", new double[] {15.5, 15.5}, points[2], 0.0);
    }

    @Test
    public void aThreeDimensionalObjectContributesItsPlanarCentroid() {
        // The spatial family is planar. An object spanning two slices contributes
        // one xy centroid over all of them, and two objects at the same xy on
        // different slices are indistinguishable to every spatial engine. That is
        // a real limitation of the family and this is where it happens.
        ImagePlus twoSlices = SpatialFixtures.stack("A", 20, 20,
                new int[][] {{2, 6, 1}, {8, 6, 1}});
        ImagePlus flat = SpatialFixtures.stack("B", 20, 20,
                new int[][] {{4, 4, 1}, {12, 12, 2}});

        PointPattern pattern = PointPattern.from(SpatialFixtures.inputs(twoSlices, flat));

        assertEquals(1, pattern.points(0).length);
        assertArrayEquals(new double[] {5.5, 6.5}, pattern.points(0)[0], 0.0);
    }

    @Test
    public void aDomainRoiNarrowsTheWindowAndDropsCentresOutsideIt() {
        List<Roi> domain = new ArrayList<Roi>();
        domain.add(new Roi(5, 5, 10, 10));

        PointPattern pattern = PointPattern.from(SpatialFixtures.inputs(domain,
                SpatialFixtures.labels("A", 20, 20,
                        new int[][] {{1, 1}, {8, 8}, {12, 12}, {18, 18}}),
                SpatialFixtures.labels("B", 20, 20, new int[][] {{9, 9}, {10, 10}})));

        assertEquals(5.0, pattern.window().getMinX(), 0.0);
        assertEquals(15.0, pattern.window().getMaxX(), 0.0);

        double[][] inside = pattern.points(0);
        assertEquals(2, inside.length);
        assertArrayEquals(new double[] {8.5, 8.5}, inside[0], 0.0);
        assertArrayEquals(new double[] {12.5, 12.5}, inside[1], 0.0);
        assertEquals("both outside centres are counted, not silently gone",
                2, pattern.droppedOutsideWindow());
        assertTrue(pattern.isRectangularDomain());
    }

    @Test
    public void aDomainThatIsNotOneRectangleIsFlagged() {
        // The CSR null scatters simulated points across the whole window. Where
        // the window is a bounding box round a polygon, the simulated density is
        // too low, the envelope too narrow, and ordinary patterns come out
        // significant. Reported rather than corrected: opa-core has no
        // non-rectangular window to correct it with.
        List<Roi> oval = new ArrayList<Roi>();
        oval.add(new OvalRoi(2, 2, 16, 16));
        assertFalse(PointPattern.from(SpatialFixtures.inputs(oval,
                SpatialFixtures.labels("A", 20, 20, new int[][] {{9, 9}}),
                SpatialFixtures.labels("B", 20, 20, new int[][] {{11, 11}})))
                .isRectangularDomain());

        List<Roi> twoFields = new ArrayList<Roi>();
        twoFields.add(new Roi(0, 0, 5, 5));
        twoFields.add(new Roi(14, 14, 5, 5));
        PointPattern split = PointPattern.from(SpatialFixtures.inputs(twoFields,
                SpatialFixtures.labels("A", 20, 20, new int[][] {{2, 2}}),
                SpatialFixtures.labels("B", 20, 20, new int[][] {{16, 16}})));
        assertFalse(split.isRectangularDomain());
        // ...and the window is their bounding box, which includes the gap.
        assertEquals(0.0, split.window().getMinX(), 0.0);
        assertEquals(19.0, split.window().getMaxX(), 0.0);
    }

    @Test
    public void aDomainThatMissesTheImagesIsALoadingErrorNotADataCase() {
        List<Roi> elsewhere = new ArrayList<Roi>();
        elsewhere.add(new Roi(200, 200, 10, 10));
        try {
            PointPattern.from(SpatialFixtures.inputs(elsewhere,
                    SpatialFixtures.labels("A", 20, 20, new int[][] {{9, 9}}),
                    SpatialFixtures.labels("B", 20, 20, new int[][] {{11, 11}})));
            fail("a domain sharing no area with the images must be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("do not overlap"));
        }
    }

    @Test
    public void pointsAreCopiedSoTwoEnginesCannotCorruptEachOther() {
        EngineInputs inputs = SpatialFixtures.inputs(
                SpatialFixtures.labels("A", 20, 20, new int[][] {{4, 4}}),
                SpatialFixtures.labels("B", 20, 20, new int[][] {{9, 9}}));
        PointPattern pattern = PointPattern.from(inputs);

        pattern.points(0)[0][0] = 999.0;

        assertArrayEquals(new double[] {4.5, 4.5}, pattern.points(0)[0], 0.0);
    }
}
