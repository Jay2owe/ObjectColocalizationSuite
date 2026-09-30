/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ocs.nullmodel;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The whole-channel shuffle, checked voxel by voxel.
 *
 * <p>Every assertion here names a voxel. {@code 02_CONTRACT.md} § Determinism
 * records three modules in this family that shipped a parallel bug precisely
 * because only aggregates were ever asserted — and an aggregate is order-free
 * and axis-blind by construction. A displacement that swapped x for y, or that
 * dropped a slice at the wrap, would leave the object count, the total volume
 * and the label histogram all perfectly correct.
 *
 * <h2>Why the fixtures are asymmetric</h2>
 *
 * Every image here has different extents on all three axes and objects that are
 * not centred, so a transposed axis cannot land back on itself. A cube in the
 * middle of a cube is the classic fixture that passes whatever you do to it.
 */
public class ChannelDisplacerTest {

    // ---------- where a voxel lands ----------

    @Test
    public void aKnownOffsetPutsAKnownLabelOnAKnownVoxel() {
        // 5 wide, 4 high, 3 deep — all different, so an axis swap shows up.
        ImagePlus labels = blank(5, 4, 3);
        set(labels, 1, 2, 0, 7);

        ImagePlus moved = ChannelDisplacer.of(labels).displaceBy(2, 1, 1);

        assertEquals("x + 2", 7, at(moved, 3, 3, 1));
        assertEquals("and nowhere else", 0, at(moved, 1, 2, 0));
        assertEquals("one voxel in, one voxel out", 1, occupiedCount(moved));
    }

    @Test
    public void theShiftWrapsOnX() {
        ImagePlus labels = blank(5, 4, 3);
        set(labels, 4, 1, 1, 9);

        ImagePlus moved = ChannelDisplacer.of(labels).displaceBy(2, 0, 0);

        assertEquals("4 + 2 = 6, which wraps to 1 in a frame 5 wide",
                9, at(moved, 1, 1, 1));
    }

    @Test
    public void theShiftWrapsOnY() {
        ImagePlus labels = blank(5, 4, 3);
        set(labels, 1, 3, 1, 9);

        ImagePlus moved = ChannelDisplacer.of(labels).displaceBy(0, 2, 0);

        assertEquals("3 + 2 = 5, which wraps to 1 in a frame 4 high",
                9, at(moved, 1, 1, 1));
    }

    @Test
    public void theShiftWrapsOnZ() {
        ImagePlus labels = blank(5, 4, 3);
        set(labels, 1, 1, 2, 9);

        ImagePlus moved = ChannelDisplacer.of(labels).displaceBy(0, 0, 2);

        assertEquals("2 + 2 = 4, which wraps to 1 in a stack 3 deep",
                9, at(moved, 1, 1, 1));
    }

    @Test
    public void everyVoxelOfEveryObjectMovesTogether() {
        ImagePlus labels = blank(6, 5, 4);
        // An L, so a rotation or a reflection cannot reproduce it.
        set(labels, 0, 0, 0, 3);
        set(labels, 1, 0, 0, 3);
        set(labels, 0, 1, 0, 3);
        set(labels, 0, 0, 1, 3);

        ImagePlus moved = ChannelDisplacer.of(labels).displaceBy(4, 3, 2);

        assertEquals(3, at(moved, 4, 3, 2));
        assertEquals(3, at(moved, 5, 3, 2));
        assertEquals(3, at(moved, 4, 4, 2));
        assertEquals(3, at(moved, 4, 3, 3));
        assertEquals("nothing else was written", 4, occupiedCount(moved));
    }

    @Test
    public void aFullFrameObjectMovesRatherThanBeingRefused() {
        // The case that defeats the per-object permuter entirely.
        ImagePlus labels = blank(4, 3, 2);
        for (int z = 0; z < 2; z++) {
            for (int y = 0; y < 3; y++) {
                for (int x = 0; x < 4; x++) {
                    set(labels, x, y, z, 1);
                }
            }
        }

        ImagePlus moved = ChannelDisplacer.of(labels).displaceBy(1, 1, 1);

        assertNotNull("a frame-filling object has nowhere to go and goes there"
                + " anyway, because the whole frame moves with it", moved);
        assertEquals(4 * 3 * 2, occupiedCount(moved));
    }

    // ---------- what must not change ----------

    @Test
    public void everyLabelKeepsExactlyItsOwnVoxelCount() {
        ImagePlus labels = blank(7, 5, 3);
        set(labels, 0, 0, 0, 1);
        set(labels, 6, 4, 2, 1);
        set(labels, 3, 2, 1, 2);
        set(labels, 3, 3, 1, 2);
        set(labels, 4, 2, 1, 2);

        int[] before = histogram(labels, 2);
        for (int dz = 0; dz < 3; dz++) {
            for (int dy = 0; dy < 5; dy++) {
                for (int dx = 0; dx < 7; dx++) {
                    ImagePlus moved =
                            ChannelDisplacer.of(labels).displaceBy(dx, dy, dz);
                    assertArrayEquals("offset " + dx + "," + dy + "," + dz
                            + " changed the label histogram; a shuffle that"
                            + " loses or duplicates a voxel is a bug no total"
                            + " would show", before, histogram(moved, 2));
                }
            }
        }
    }

    @Test
    public void objectCountAndVolumeSurviveAWrapThroughEveryCorner() {
        ImagePlus labels = blank(5, 4, 3);
        set(labels, 4, 3, 2, 5);
        set(labels, 0, 0, 0, 6);

        ImagePlus moved = ChannelDisplacer.of(labels).displaceBy(1, 1, 1);

        assertEquals("the far corner wraps to the origin", 5, at(moved, 0, 0, 0));
        assertEquals("and the origin moves one along", 6, at(moved, 1, 1, 1));
        assertEquals(2, occupiedCount(moved));
    }

    @Test
    public void aFullTurnOnEveryAxisIsTheImageItStartedFrom() {
        ImagePlus labels = blank(5, 4, 3);
        set(labels, 1, 2, 0, 7);
        set(labels, 4, 0, 2, 8);

        ImagePlus moved = ChannelDisplacer.of(labels).displaceBy(5, 4, 3);

        for (int z = 0; z < 3; z++) {
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 5; x++) {
                    assertEquals("voxel " + x + "," + y + "," + z,
                            at(labels, x, y, z), at(moved, x, y, z));
                }
            }
        }
    }

    // ---------- the offset ----------

    @Test
    public void theIdentityOffsetIsNeverDrawn() {
        // A 2x1 frame: two in-plane offsets exist, and one is the identity.
        // Over many draws an unguarded implementation returns it about half the
        // time, so this fails immediately rather than flaking.
        ImagePlus labels = blank(2, 1, 1);
        set(labels, 0, 0, 0, 1);
        ChannelDisplacer displacer = ChannelDisplacer.of(labels);

        Random random = new Random(4242L);
        for (int i = 0; i < 200; i++) {
            int[] offset = displacer.drawOffset(random);
            if (offset[0] == 0 && offset[1] == 0 && offset[2] == 0) {
                fail("draw " + i + " returned the identity offset, which"
                        + " reproduces the observed field exactly and would"
                        + " count as a permutation matching the observation");
            }
        }
    }

    @Test
    public void everyNonIdentityInPlaneOffsetIsReachable() {
        ImagePlus labels = blank(3, 2, 2);
        set(labels, 0, 0, 0, 1);
        ChannelDisplacer displacer = ChannelDisplacer.of(labels);

        Set<String> seen = new HashSet<String>();
        Random random = new Random(11L);
        for (int i = 0; i < 5000; i++) {
            int[] offset = displacer.drawOffset(random);
            seen.add(offset[0] + "," + offset[1] + "," + offset[2]);
        }

        assertEquals("all 3x2 in-plane offsets bar the identity must be"
                + " reachable, or the null is drawn from a smaller space than"
                + " it claims", 3 * 2 - 1, seen.size());
        assertFalse(seen.contains("0,0,0"));
    }

    /**
     * The channels were acquired in one optical stack, so which slice a
     * structure sits in is shared physical fact rather than arrangement.
     * Randomizing it would break a registration the microscope established.
     */
    @Test
    public void theDrawnOffsetNeverShiftsInZ() {
        ImagePlus labels = blank(5, 4, 3);
        set(labels, 1, 1, 1, 1);
        ChannelDisplacer displacer = ChannelDisplacer.of(labels);

        Random random = new Random(7L);
        for (int i = 0; i < 500; i++) {
            int[] offset = displacer.drawOffset(random);
            assertEquals("draw " + i + " shifted in z; the two channels are"
                    + " z-registered by the microscope and that registration is"
                    + " not the thing under test", 0, offset[2]);
        }
    }

    @Test
    public void aSingleColumnFrameIsRefusedRatherThanLoopingForever() {
        ImagePlus labels = blank(1, 1, 4);
        set(labels, 0, 0, 0, 1);
        try {
            ChannelDisplacer.of(labels).drawOffset(new Random(1L));
            fail("every in-plane offset on a one-column frame is the identity;"
                    + " rejection sampling would spin forever");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("identity"));
        }
    }

    // ---------- determinism ----------

    @Test
    public void theSameSeedGivesTheIdenticalImageVoxelForVoxel() {
        ImagePlus labels = blank(9, 7, 4);
        set(labels, 0, 0, 0, 1);
        set(labels, 8, 6, 3, 2);
        set(labels, 4, 2, 1, 3);
        ChannelDisplacer displacer = ChannelDisplacer.of(labels);

        ImagePlus first = displacer.displace(new Random(20260812L));
        ImagePlus second = displacer.displace(new Random(20260812L));

        for (int z = 0; z < 4; z++) {
            for (int y = 0; y < 7; y++) {
                for (int x = 0; x < 9; x++) {
                    assertEquals("voxel " + x + "," + y + "," + z,
                            at(first, x, y, z), at(second, x, y, z));
                }
            }
        }
    }

    @Test
    public void permutationIIsTheSameWhicheverOrderPermutationsAreDrawnIn() {
        ImagePlus labels = blank(9, 7, 4);
        set(labels, 1, 1, 1, 1);
        set(labels, 8, 0, 3, 2);
        ChannelDisplacer displacer = ChannelDisplacer.of(labels);

        // Forwards, as a serial run would; then backwards, as workers finishing
        // out of order effectively do. Permutation i draws from Random(seed + i),
        // so neither order may change it.
        ImagePlus[] forwards = new ImagePlus[8];
        for (int i = 0; i < 8; i++) {
            forwards[i] = displacer.displace(new Random(20260812L + i));
        }
        for (int i = 7; i >= 0; i--) {
            ImagePlus again = displacer.displace(new Random(20260812L + i));
            for (int z = 0; z < 4; z++) {
                for (int y = 0; y < 7; y++) {
                    for (int x = 0; x < 9; x++) {
                        assertEquals("permutation " + i + " voxel "
                                + x + "," + y + "," + z,
                                at(forwards[i], x, y, z), at(again, x, y, z));
                    }
                }
            }
        }
    }

    @Test
    public void differentSeedsGiveDifferentImages() {
        ImagePlus labels = blank(9, 7, 4);
        set(labels, 2, 3, 1, 1);
        ChannelDisplacer displacer = ChannelDisplacer.of(labels);

        ImagePlus first = displacer.displace(new Random(1L));
        ImagePlus second = displacer.displace(new Random(2L));

        assertFalse("two seeds landing on the same offset would make the null"
                + " model a constant", same(first, second, 9, 7, 4));
    }

    // ---------- the seam ----------

    @Test
    public void theSeamCountNamesTheObjectsTheWrapCutsInTwo() {
        ImagePlus labels = blank(6, 4, 2);
        // Object 1 spans x = 4..5, so a shift of 1 puts half of it at x = 0.
        set(labels, 4, 1, 0, 1);
        set(labels, 5, 1, 0, 1);
        // Object 2 sits well inside and cannot be cut by a shift of 1.
        set(labels, 1, 1, 0, 2);
        set(labels, 2, 1, 0, 2);
        ChannelDisplacer displacer = ChannelDisplacer.of(labels);

        assertEquals("only the object straddling the edge is cut",
                1, displacer.seamSplitCount(1, 0, 0));
        assertEquals("a shift of zero cuts nothing",
                0, displacer.seamSplitCount(0, 0, 0));
    }

    @Test
    public void aSeamSplitObjectKeepsEveryVoxelItHad() {
        ImagePlus labels = blank(6, 4, 2);
        set(labels, 4, 1, 0, 1);
        set(labels, 5, 1, 0, 1);

        ImagePlus moved = ChannelDisplacer.of(labels).displaceBy(1, 0, 0);

        assertEquals("the half that wrapped", 1, at(moved, 0, 1, 0));
        assertEquals("the half that did not", 1, at(moved, 5, 1, 0));
        assertEquals("volume is exact even when the object is in two pieces",
                2, occupiedCount(moved));
    }

    // ---------- bookkeeping ----------

    @Test
    public void itCountsTheObjectsAndVoxelsItWasGiven() {
        ImagePlus labels = blank(5, 4, 3);
        set(labels, 0, 0, 0, 1);
        set(labels, 1, 0, 0, 1);
        set(labels, 3, 3, 2, 4);

        ChannelDisplacer displacer = ChannelDisplacer.of(labels);

        assertEquals("labels need not be contiguous; 1 and 4 are two objects",
                2, displacer.objectCount());
        assertEquals(3, displacer.occupiedVoxels());
    }

    // ---------- fixtures ----------

    private static ImagePlus blank(int width, int height, int depth) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            stack.addSlice(new ShortProcessor(width, height));
        }
        return new ImagePlus("fixture", stack);
    }

    private static void set(ImagePlus image, int x, int y, int z, int label) {
        image.getStack().getProcessor(z + 1).setf(x, y, label);
    }

    private static int at(ImagePlus image, int x, int y, int z) {
        return (int) image.getStack().getProcessor(z + 1).getf(x, y);
    }

    private static int occupiedCount(ImagePlus image) {
        int count = 0;
        for (int z = 1; z <= image.getStackSize(); z++) {
            ImageProcessor processor = image.getStack().getProcessor(z);
            for (int p = 0; p < image.getWidth() * image.getHeight(); p++) {
                if (processor.getf(p) > 0) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int[] histogram(ImagePlus image, int maxLabel) {
        int[] counts = new int[maxLabel + 1];
        for (int z = 1; z <= image.getStackSize(); z++) {
            ImageProcessor processor = image.getStack().getProcessor(z);
            for (int p = 0; p < image.getWidth() * image.getHeight(); p++) {
                int label = (int) processor.getf(p);
                if (label > 0) {
                    counts[label]++;
                }
            }
        }
        return counts;
    }

    private static boolean same(ImagePlus a, ImagePlus b,
            int width, int height, int depth) {
        for (int z = 0; z < depth; z++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (at(a, x, y, z) != at(b, x, y, z)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static void assertArrayEquals(String message, int[] expected,
            int[] actual) {
        if (!Arrays.equals(expected, actual)) {
            fail(message + "\n  expected " + Arrays.toString(expected)
                    + "\n  actual   " + Arrays.toString(actual));
        }
    }
}
