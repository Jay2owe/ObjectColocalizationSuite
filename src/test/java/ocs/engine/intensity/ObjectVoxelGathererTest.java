package ocs.engine.intensity;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import ocs.engine.EngineCancelledException;
import ocs.engine.EngineProgress;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The fused gather, checked voxel by voxel rather than by totals.
 *
 * <p>A total is the wrong thing to assert here for the same reason an aggregate
 * is the wrong thing to assert about a parallel merge: the sum of an object's
 * samples is unchanged by gathering them in the wrong order, and unchanged by
 * gathering the right count of the wrong voxels. Every assertion below names the
 * object and compares the sample vector element by element.
 */
public class ObjectVoxelGathererTest {

    /**
     * An L-shaped object. Its bounding box is the whole 4×4 field and includes
     * five background voxels carrying an intensity nothing in the object carries,
     * so a box-instead-of-mask implementation is visible in the values themselves
     * rather than only in the count.
     */
    private static final int[] L_LABELS = {
        1, 0, 0, 2,
        1, 0, 0, 2,
        1, 0, 0, 0,
        1, 1, 1, 0,
    };

    /** 900 marks every background voxel, and nothing else is 900. */
    private static final int[] L_CHANNEL_A = {
        10, 900, 900,  70,
        20, 900, 900,  80,
        30, 900, 900, 900,
        40,  50,  60, 900,
    };

    /** Offset by exactly 1000, so a mispaired channel is visible per element. */
    private static final int[] L_CHANNEL_B = {
        1010, 1900, 1900, 1070,
        1020, 1900, 1900, 1080,
        1030, 1900, 1900, 1900,
        1040, 1050, 1060, 1900,
    };

    @Test
    public void gathersEachObjectsOwnVoxelsAndNothingElse() {
        ObjectVoxelGatherer.Gathered gathered = gatherL();

        assertEquals("two labels, two objects", 2, gathered.objectCount());
        assertEquals(1, gathered.labelAt(0));
        assertEquals(2, gathered.labelAt(1));
        assertEquals(6, gathered.voxelCountAt(0));
        assertEquals(2, gathered.voxelCountAt(1));
        assertEquals("background is not an object", 8, gathered.totalVoxels());

        // Raster order within the mask: down the left column, then along the
        // bottom row.
        assertArrayEquals(new double[] {10.0, 20.0, 30.0, 40.0, 50.0, 60.0},
                gathered.samplesFor(0, 0), 0.0);
        assertArrayEquals(new double[] {70.0, 80.0}, gathered.samplesFor(1, 0), 0.0);
    }

    /**
     * The negative control for the mask. Object 1's bounding box is the whole
     * field; gathering the box instead of the mask would pull in the background's
     * 900s and the whole of object 2.
     */
    @Test
    public void theBoundingBoxIsNotTheMask() {
        ObjectVoxelGatherer.Gathered gathered = gatherL();
        double[] samples = gathered.samplesFor(0, 0);

        assertEquals("object 1 owns six voxels; its bounding box holds sixteen",
                6, samples.length);
        for (int i = 0; i < samples.length; i++) {
            assertFalse("900 marks background and must never be gathered, found it at "
                    + i, samples[i] == 900.0);
            assertFalse("70 and 80 belong to object 2", samples[i] == 70.0);
            assertFalse("70 and 80 belong to object 2", samples[i] == 80.0);
        }
    }

    /**
     * Two channels' samples at the same slot must come from the same voxel.
     *
     * <p>A per-object Pearson over two vectors gathered in different voxel orders
     * is a confident number measuring nothing, and would look entirely normal in
     * a table.
     */
    @Test
    public void channelsArePairedVoxelByVoxel() {
        ObjectVoxelGatherer.Gathered gathered = gatherL();
        assertEquals(2, gathered.channelCount());

        for (int object = 0; object < gathered.objectCount(); object++) {
            double[] a = gathered.samplesFor(object, 0);
            double[] b = gathered.samplesFor(object, 1);
            assertEquals(a.length, b.length);
            for (int i = 0; i < a.length; i++) {
                assertEquals("channel B is channel A plus 1000 at every voxel of the"
                        + " fixture, so any mispairing shows up here",
                        a[i] + 1000.0, b[i], 0.0);
            }
        }
    }

    /**
     * Row order must be a property of the labels, not of which object the
     * traversal met first — otherwise the table's row order would change with the
     * segmentation's numbering and two runs of the same data would not diff.
     */
    @Test
    public void objectsComeBackInAscendingLabelOrder() {
        int[] labels = {
            9, 9, 0, 0,
            0, 0, 4, 4,
            0, 0, 0, 0,
            7, 0, 0, 0,
        };
        int[] intensity = {
            91, 92, 0, 0,
             0,  0, 41, 42,
             0,  0,  0,  0,
            71,  0,  0,  0,
        };
        ObjectVoxelGatherer.Gathered gathered = ObjectVoxelGatherer.gather(
                image("labels", 4, 4, labels),
                Collections.singletonList(image("i", 4, 4, intensity)),
                EngineProgress.SILENT);

        assertEquals(3, gathered.objectCount());
        assertEquals("label 9 is met first and must still be reported last",
                4, gathered.labelAt(0));
        assertEquals(7, gathered.labelAt(1));
        assertEquals(9, gathered.labelAt(2));
        assertArrayEquals(new double[] {41.0, 42.0}, gathered.samplesFor(0, 0), 0.0);
        assertArrayEquals(new double[] {71.0}, gathered.samplesFor(1, 0), 0.0);
        assertArrayEquals(new double[] {91.0, 92.0}, gathered.samplesFor(2, 0), 0.0);

        assertEquals(0, gathered.indexOfLabel(4));
        assertEquals(2, gathered.indexOfLabel(9));
        assertEquals("a label with no object has no index", -1, gathered.indexOfLabel(5));
    }

    @Test
    public void objectsSpanningSlicesAreGatheredAcrossTheStack() {
        int[][] labels = {
            {1, 1, 0, 0},
            {0, 1, 0, 2},
        };
        int[][] intensity = {
            {11, 12, 99, 99},
            {99, 13, 99, 21},
        };
        ObjectVoxelGatherer.Gathered gathered = ObjectVoxelGatherer.gather(
                stack("labels", 2, 2, labels),
                Collections.singletonList(stack("i", 2, 2, intensity)),
                EngineProgress.SILENT);

        assertEquals(2, gathered.objectCount());
        assertEquals("object 1 has two voxels on slice 1 and one on slice 2",
                3, gathered.voxelCountAt(0));
        assertArrayEquals("slice order, then raster order within the slice",
                new double[] {11.0, 12.0, 13.0}, gathered.samplesFor(0, 0), 0.0);
        assertArrayEquals(new double[] {21.0}, gathered.samplesFor(1, 0), 0.0);
    }

    @Test
    public void aColourLabelImageIsRefusedRatherThanUnpacked() {
        ImagePlus colour = new ImagePlus("rgb", new ColorProcessor(4, 4));
        try {
            ObjectVoxelGatherer.gather(colour,
                    Collections.singletonList(image("i", 4, 4, new int[16])),
                    EngineProgress.SILENT);
            fail("a packed RGB word read as a label invents one object per colour");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("colour image"));
        }
    }

    @Test
    public void anIntensityImageHandedInAsLabelsIsRefused() {
        FloatProcessor processor = new FloatProcessor(4, 4);
        processor.setf(0, 5.0e7f);
        try {
            ObjectVoxelGatherer.gather(new ImagePlus("not labels", processor),
                    Collections.singletonList(image("i", 4, 4, new int[16])),
                    EngineProgress.SILENT);
            fail("without the ceiling this allocates a 50-million-entry index table"
                    + " and reports one object per grey level");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("ceiling"));
        }
    }

    @Test
    public void mismatchedDimensionsAreRefused() {
        try {
            ObjectVoxelGatherer.gather(image("labels", 4, 4, new int[16]),
                    Collections.singletonList(image("i", 2, 2, new int[4])),
                    EngineProgress.SILENT);
            fail("a mismatch would pair each voxel with the wrong partner rather"
                    + " than fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("but the label image"));
        }
    }

    /** Large enough to reach the first cancellation poll, and no larger. */
    @Test
    public void cancellationDuringTheGatherThrowsRatherThanReturningPartialSamples() {
        int side = 1100;
        int[] labels = new int[side * side];
        Arrays.fill(labels, 1);
        List<ImagePlus> intensities = new ArrayList<ImagePlus>();
        intensities.add(image("i", side, side, labels));

        try {
            ObjectVoxelGatherer.gather(image("labels", side, side, labels), intensities,
                    new AlwaysCancelled());
            fail("a partial gather would give every object downstream a truncated"
                    + " sample vector and a plausible wrong correlation");
        } catch (EngineCancelledException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("object-voxel-gather"));
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static ObjectVoxelGatherer.Gathered gatherL() {
        return ObjectVoxelGatherer.gather(
                image("labels", 4, 4, L_LABELS),
                Arrays.asList(image("a", 4, 4, L_CHANNEL_A), image("b", 4, 4, L_CHANNEL_B)),
                EngineProgress.SILENT);
    }

    private static ImagePlus image(String title, int width, int height, int[] values) {
        ShortProcessor processor = new ShortProcessor(width, height);
        for (int i = 0; i < values.length; i++) {
            processor.set(i, values[i]);
        }
        return new ImagePlus(title, processor);
    }

    private static ImagePlus stack(String title, int width, int height, int[][] slices) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < slices.length; z++) {
            ShortProcessor processor = new ShortProcessor(width, height);
            for (int i = 0; i < slices[z].length; i++) {
                processor.set(i, slices[z][i]);
            }
            stack.addSlice(processor);
        }
        return new ImagePlus(title, stack);
    }

    private static final class AlwaysCancelled implements EngineProgress {
        @Override
        public void report(String stage, double fraction) {
            // deliberately empty
        }

        @Override
        public boolean isCancelled() {
            return true;
        }
    }
}
