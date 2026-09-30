package ocs.nullmodel;

import ij.ImagePlus;
import ij.gui.Roi;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The shuffle has to change where objects are and nothing else.
 *
 * <p>Every test here asserts a per-object or per-voxel fact. A test that checked
 * only "the total number of labelled voxels is unchanged" would pass while the
 * permuter swapped two objects' shapes, merged two objects into one, or dropped
 * an object and duplicated another — all of which change the null distribution.
 */
public class ObjectPermuterTest {

    private static final int SIZE = NullFixtures.SIZE;

    @Test
    public void everyObjectKeepsItsExactShapeAndSize() {
        ImagePlus labels = NullFixtures.blocks("A", NullFixtures.fourApart());
        ObjectPermuter permuter = ObjectPermuter.of(labels, wholeImage());

        ImagePlus permuted = permuter.permute(new Random(1L));
        assertNotNull(permuted);

        Map<Integer, List<int[]>> before = shapesOf(labels);
        Map<Integer, List<int[]>> after = shapesOf(permuted);

        assertEquals("object count must not change", before.size(), after.size());
        for (Integer label : before.keySet()) {
            assertTrue("label " + label + " disappeared", after.containsKey(label));
            // Normalized to each object's own bounding box, so this compares
            // shape rather than position — which is exactly the thing that must
            // survive a shuffle.
            assertEquals("label " + label + " changed shape",
                    normalize(before.get(label)), normalize(after.get(label)));
        }
    }

    @Test
    public void nothingIsPlacedOutsideTheRegion() {
        // The region is a circle. Its bounding box includes four corners that are
        // not in it, so an implementation that scatters into the box lands there.
        // This is defect 9 in the ledger and the bug Coloc 2 is reported to have:
        // it puts objects where there is no tissue, which lowers the by-chance
        // colocalization and inflates every significance call built on it.
        Roi circle = NullFixtures.circle();
        boolean[] domain = ObjectPermuter.domainMask(
                Arrays.<Roi>asList(circle), SIZE, SIZE, 1);
        ObjectPermuter permuter = ObjectPermuter.of(
                NullFixtures.blocks("A", NullFixtures.fourApart()), domain);

        for (int trial = 0; trial < 30; trial++) {
            ImagePlus permuted = permuter.permute(new Random(trial));
            assertNotNull(permuted);
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    if (permuted.getProcessor().get(x, y) > 0) {
                        assertTrue("voxel (" + x + "," + y + ") is outside the region",
                                circle.contains(x, y));
                    }
                }
            }
        }
    }

    @Test
    public void theCornersOfTheRegionsBoundingBoxAreReachableButNotInside() {
        // Guards the test above from becoming vacuous. If the circle happened to
        // cover its own bounding box, "nothing landed outside" would prove
        // nothing at all.
        Roi circle = NullFixtures.circle();
        assertFalse("a corner of the bounding box must be outside the region",
                circle.contains(circle.getBounds().x, circle.getBounds().y));
    }

    @Test
    public void objectsOfOneChannelNeverOverlap() {
        // Not a modelling preference: a label image holds one label per voxel, so
        // an overlapping placement would silently shrink whichever object was
        // written first and destroy the size distribution being preserved.
        ImagePlus labels = NullFixtures.blocks("A", NullFixtures.fourApart());
        ObjectPermuter permuter = ObjectPermuter.of(labels, wholeImage());

        for (int trial = 0; trial < 30; trial++) {
            ImagePlus permuted = permuter.permute(new Random(trial));
            assertNotNull(permuted);
            int labelled = 0;
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    if (permuted.getProcessor().get(x, y) > 0) {
                        labelled++;
                    }
                }
            }
            // 4 objects x 4 voxels. Any overlap shows up as a shortfall, because
            // the overwritten voxels stop being counted for their first owner.
            assertEquals("trial " + trial + " lost voxels to an overlap", 16, labelled);
        }
    }

    @Test
    public void theSameSeedGivesTheIdenticalImage() {
        ObjectPermuter permuter = ObjectPermuter.of(
                NullFixtures.blocks("A", NullFixtures.fourApart()), wholeImage());

        ImagePlus first = permuter.permute(new Random(4242L));
        ImagePlus second = permuter.permute(new Random(4242L));

        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                assertEquals("(" + x + "," + y + ")",
                        first.getProcessor().get(x, y), second.getProcessor().get(x, y));
            }
        }
    }

    @Test
    public void adifferentSeedMovesTheObjects() {
        // The negative control for the test above. Without it, a permuter that
        // ignored its Random entirely — returning the input unshuffled — would
        // pass "the same seed gives the same image" perfectly.
        ObjectPermuter permuter = ObjectPermuter.of(
                NullFixtures.blocks("A", NullFixtures.fourApart()), wholeImage());

        ImagePlus first = permuter.permute(new Random(1L));
        ImagePlus second = permuter.permute(new Random(2L));

        boolean differs = false;
        for (int y = 0; y < SIZE && !differs; y++) {
            for (int x = 0; x < SIZE && !differs; x++) {
                differs = first.getProcessor().get(x, y) != second.getProcessor().get(x, y);
            }
        }
        assertTrue("two seeds produced the identical placement", differs);
    }

    @Test
    public void theShuffleActuallyMovesThingsOffTheirOriginalPositions() {
        // Stronger than "two seeds differ": pins that the permuted field differs
        // from the *observed* one. A permuter that returned its input would give
        // a null distribution with no variance, which reads as an overwhelmingly
        // significant result rather than as a bug.
        ImagePlus labels = NullFixtures.blocks("A", NullFixtures.fourApart());
        ObjectPermuter permuter = ObjectPermuter.of(labels, wholeImage());
        ImagePlus permuted = permuter.permute(new Random(7L));

        boolean differs = false;
        for (int y = 0; y < SIZE && !differs; y++) {
            for (int x = 0; x < SIZE && !differs; x++) {
                differs = labels.getProcessor().get(x, y) != permuted.getProcessor().get(x, y);
            }
        }
        assertTrue("the permuted field is identical to the observed one", differs);
    }

    @Test
    public void aRegionWithNoRoomReportsFailureRatherThanPlacingAnyway() {
        // Six 2x2 objects into a region that holds at most four without
        // overlapping. Returning null lets the caller report
        // NO_ROOM_IN_DOMAIN; the alternative — placing them anyway — would
        // produce a null model quietly built on the wrong object sizes.
        ImagePlus labels = NullFixtures.blocks("A", new int[][] {
                {0, 0}, {4, 0}, {8, 0}, {12, 0}, {16, 0}, {20, 0}});
        boolean[] tiny = new boolean[SIZE * SIZE];
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 5; x++) {
                tiny[y * SIZE + x] = true;
            }
        }
        ObjectPermuter permuter = ObjectPermuter.of(labels, tiny);
        assertNull("must refuse rather than overlap or spill",
                permuter.permute(new Random(1L)));
    }

    @Test
    public void anEmptyRoiListYieldsNoDomainRatherThanTheWholeImage() {
        // The caller must refuse the run. Substituting the whole image would
        // answer a different question without saying so.
        assertNull(ObjectPermuter.domainMask(
                new ArrayList<Roi>(), SIZE, SIZE, 1));
        assertNull(ObjectPermuter.domainMask(null, SIZE, SIZE, 1));
    }

    @Test
    public void aStackPermutesInThreeDimensions() {
        ImagePlus labels = NullFixtures.blocks("A", NullFixtures.fourApart(), 5);
        ObjectPermuter permuter = ObjectPermuter.of(labels, wholeImageStack(5));
        ImagePlus permuted = permuter.permute(new Random(3L));

        assertNotNull(permuted);
        assertEquals(5, permuted.getStackSize());
        Map<Integer, List<int[]>> before = shapesOf(labels);
        Map<Integer, List<int[]>> after = shapesOf(permuted);
        assertEquals(before.size(), after.size());
        for (Integer label : before.keySet()) {
            assertEquals("label " + label + " changed shape in 3D",
                    normalize(before.get(label)), normalize(after.get(label)));
        }
    }

    @Test
    public void objectCountIsReportedBeforeAnyShuffle() {
        ObjectPermuter permuter = ObjectPermuter.of(
                NullFixtures.blocks("A", NullFixtures.fourApart()), wholeImage());
        assertEquals(4, permuter.objectCount());
    }

    // ---------- helpers ----------

    private static boolean[] wholeImage() {
        return ObjectPermuter.wholeImageMask(SIZE, SIZE, 1);
    }

    private static boolean[] wholeImageStack(int slices) {
        return ObjectPermuter.wholeImageMask(SIZE, SIZE, slices);
    }

    /** label to its voxel coordinates, as {x, y, z}. */
    private static Map<Integer, List<int[]>> shapesOf(ImagePlus image) {
        Map<Integer, List<int[]>> shapes = new HashMap<Integer, List<int[]>>();
        for (int z = 0; z < image.getStackSize(); z++) {
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int label = (int) image.getStack().getProcessor(z + 1).getf(x, y);
                    if (label <= 0) {
                        continue;
                    }
                    Integer key = Integer.valueOf(label);
                    if (!shapes.containsKey(key)) {
                        shapes.put(key, new ArrayList<int[]>());
                    }
                    shapes.get(key).add(new int[] {x, y, z});
                }
            }
        }
        return shapes;
    }

    /** Voxels relative to the object's own minimum corner, sorted, as a string. */
    private static String normalize(List<int[]> voxels) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        for (int i = 0; i < voxels.size(); i++) {
            minX = Math.min(minX, voxels.get(i)[0]);
            minY = Math.min(minY, voxels.get(i)[1]);
            minZ = Math.min(minZ, voxels.get(i)[2]);
        }
        List<String> relative = new ArrayList<String>();
        for (int i = 0; i < voxels.size(); i++) {
            relative.add((voxels.get(i)[0] - minX) + ","
                    + (voxels.get(i)[1] - minY) + ","
                    + (voxels.get(i)[2] - minZ));
        }
        java.util.Collections.sort(relative);
        return relative.toString();
    }
}
