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
import ij.gui.Roi;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import ocs.engine.ColocEngine;
import ocs.engine.EngineFamily;
import ocs.engine.EngineInputs;
import ocs.engine.EngineProgress;
import ocs.engine.EngineRegistry;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The seam: what a wrapping shuffle does to each kind of statistic.
 *
 * <h2>The mechanism, not the symptom</h2>
 *
 * Whole-channel displacement slides the image and wraps at the frame edge, so an
 * object crossing that edge arrives in two pieces at opposite sides. Nothing is
 * lost — every voxel is still there and still carries its own label — but the
 * object's <i>axis-aligned bounding box</i> now spans the frame, and a
 * frame-wide box overlaps every object there is.
 *
 * <p>The everyday version: a tenant whose flat straddles two ends of a corridor
 * still owns the same two rooms, but "the space between their front doors" is
 * now the whole corridor, and by that measure they share a wall with everybody.
 *
 * <p>These tests assert that on geometry planted by hand, not on the validation
 * set's numbers. A rule drawn from a measurement is right about that dataset;
 * this one has to be right about the next one.
 */
public class WrapDistortionTest {

    /**
     * One object, its voxels intact and its box the whole frame. Both halves are
     * asserted: the voxel count is what makes membership-based statistics safe,
     * and the box is what makes extent-based ones unusable.
     */
    @Test
    public void anObjectAcrossTheWrapKeepsItsVoxelsAndLosesItsBox() {
        // A bar of six voxels from x = 8 to x = 13 on a frame 16 wide.
        ImagePlus image = blank(16, 6, 3);
        for (int x = 8; x <= 13; x++) {
            image.getStack().getProcessor(1).setf(x, 2, 5);
        }
        ChannelDisplacer displacer = ChannelDisplacer.of(image);

        // Shift right by 5: the bar would run x = 13..18, so three voxels wrap.
        ImagePlus wrapped = displacer.displaceBy(5, 0, 0);

        int[] box = boxOf(wrapped, 5);
        assertEquals("every voxel must survive the wrap", 6, countOf(wrapped, 5));
        assertEquals("the box now starts at the left edge", 0, box[0]);
        assertEquals("and ends at the right edge", 15, box[1]);
        assertEquals("so it spans the whole frame", 16, box[1] - box[0] + 1);

        // The same object shifted somewhere it does not wrap keeps its true box.
        ImagePlus clear = displacer.displaceBy(2, 0, 0);
        int[] clearBox = boxOf(clear, 5);
        assertEquals(6, countOf(clear, 5));
        assertEquals(10, clearBox[0]);
        assertEquals(15, clearBox[1]);
        assertEquals(6, clearBox[1] - clearBox[0] + 1);
    }

    /** The count is the same at every offset; the box is not. */
    @Test
    public void acrossEveryOffsetTheVoxelCountHoldsAndTheBoxDoesNot() {
        ImagePlus image = blank(16, 6, 3);
        for (int x = 8; x <= 13; x++) {
            image.getStack().getProcessor(1).setf(x, 2, 5);
        }
        ChannelDisplacer displacer = ChannelDisplacer.of(image);
        int framesSpanned = 0;
        for (int dx = 0; dx < 16; dx++) {
            ImagePlus moved = displacer.displaceBy(dx, 0, 0);
            assertEquals("offset " + dx, 6, countOf(moved, 5));
            int[] box = boxOf(moved, 5);
            int width = box[1] - box[0] + 1;
            assertTrue("offset " + dx + " gave a box of " + width,
                    width == 6 || width == 16);
            if (width == 16) {
                framesSpanned++;
            }
        }
        // Offsets 3..7 put the bar across the edge: five of the sixteen.
        assertEquals(5, framesSpanned);
    }

    // ---------- the classification ----------

    @Test
    public void onlyTheExtentDerivedEngineIsRefusedAndOnlyByTheWrappingKind() {
        assertTrue(NullModelKind.WHOLE_CHANNEL.distorts("bounding-box"));
        assertFalse(NullModelKind.PER_OBJECT.distorts("bounding-box"));

        String[] safe = {"cpc", "distance-tolerance", "volume-overlap",
                "jaccard-dice", "containment"};
        for (int i = 0; i < safe.length; i++) {
            assertFalse(safe[i] + " must not be refused: it is not read from an"
                            + " object's extent",
                    NullModelKind.WHOLE_CHANNEL.distorts(safe[i]));
            assertFalse(NullModelKind.PER_OBJECT.distorts(safe[i]));
        }
    }

    /**
     * Every object-family engine must be named in one list or the other, so a
     * seventh engine is a decision somebody makes rather than a default they
     * inherit. A new engine arriving on the safe side by silence is exactly how
     * the bounding-box problem would recur.
     */
    @Test
    public void everyObjectFamilyEngineHasBeenClassified() {
        Set<String> known = new LinkedHashSet<String>(Arrays.asList(
                "cpc", "distance-tolerance", "volume-overlap",
                "jaccard-dice", "containment", "bounding-box"));
        List<ColocEngine> objectEngines =
                EngineRegistry.createDefault().family(EngineFamily.OBJECT);
        List<String> unclassified = new ArrayList<String>();
        for (int i = 0; i < objectEngines.size(); i++) {
            String id = objectEngines.get(i).id();
            if (!known.contains(id)) {
                unclassified.add(id);
            }
        }
        assertEquals("engine(s) " + unclassified + " are in the object family but"
                + " this test does not say whether a wrapping shuffle distorts"
                + " them. Decide: is the statistic read from voxel membership"
                + " (exact under a wrap), from a centroid (bounded), or from an"
                + " axis-aligned extent (unbounded — add it to"
                + " NullModelKind.EXTENT_DERIVED_ENGINE_IDS)?",
                0, unclassified.size());
        assertEquals("the object family gained or lost an engine",
                known.size(), objectEngines.size());

        // And the refusal list must not name an engine that does not exist.
        Set<String> refused = NullModelKind.extentDerivedEngineIds();
        for (String id : refused) {
            assertTrue("the refusal list names " + id + ", which is not a"
                    + " registered object engine", known.contains(id));
        }
        assertEquals(1, refused.size());
    }

    // ---------- end to end through the runner ----------

    /**
     * Two channels the shuffle can move, run under both null models. The
     * wrapping one must refuse the box engine by name and keep its observed
     * value; the non-wrapping one must report a real <i>p</i> for the same
     * engine on the same field.
     */
    @Test
    public void theWrappingKindRefusesTheBoxEngineAndTheOtherKindDoesNot() {
        EngineInputs inputs = twoSparseChannels();
        List<ColocEngine> engines = enginesFor("bounding-box", "volume-overlap");

        List<NullModelResult> wrapped = NullModelRunner.builder()
                .kind(NullModelKind.WHOLE_CHANNEL)
                .permutations(16)
                .workers(1)
                .build()
                .run(engines, inputs, EngineProgress.SILENT);

        int boxDirections = 0;
        int overlapDirections = 0;
        for (int i = 0; i < wrapped.size(); i++) {
            NullModelResult result = wrapped.get(i);
            if ("bounding-box".equals(result.engineId())) {
                boxDirections++;
                assertFalse("the box engine must not report a p under a"
                        + " wrapping shuffle", result.ran());
                assertEquals(NullModelResult.Skip.DISTORTED_BY_WRAP, result.skip());
                assertTrue("p must be NaN, not a number nobody can act on",
                        Double.isNaN(result.pTwoSided()));
                assertFalse("the observed value is still the user's data and"
                        + " must survive the refusal",
                        Double.isNaN(result.observed()));
            } else {
                overlapDirections++;
                assertTrue(result.engineId() + " was refused and should not have"
                        + " been: " + result.skip(), result.ran());
            }
        }
        assertEquals(2, boxDirections);
        assertEquals(2, overlapDirections);

        List<NullModelResult> relocated = NullModelRunner.builder()
                .kind(NullModelKind.PER_OBJECT)
                .permutations(16)
                .workers(1)
                .build()
                .run(engines, inputs, EngineProgress.SILENT);
        int boxRan = 0;
        for (int i = 0; i < relocated.size(); i++) {
            NullModelResult result = relocated.get(i);
            if ("bounding-box".equals(result.engineId()) && result.ran()) {
                boxRan++;
                assertFalse(Double.isNaN(result.pTwoSided()));
            }
        }
        assertEquals("per-object does not wrap, so it must still permute the"
                + " box engine", 2, boxRan);
    }

    /**
     * The refusal must not change what the other engines report. Each
     * permutation's shuffled field is built before any engine runs and is shared
     * by all of them, so removing one engine cannot move another — asserted here
     * per permutation rather than on a summary.
     */
    @Test
    public void refusingOneEngineLeavesTheOthersPermutationsUntouched() {
        EngineInputs inputs = twoSparseChannels();
        List<NullModelResult> withBox = NullModelRunner.builder()
                .kind(NullModelKind.WHOLE_CHANNEL).permutations(16).workers(1).build()
                .run(enginesFor("bounding-box", "volume-overlap"), inputs,
                        EngineProgress.SILENT);
        List<NullModelResult> withoutBox = NullModelRunner.builder()
                .kind(NullModelKind.WHOLE_CHANNEL).permutations(16).workers(1).build()
                .run(enginesFor("volume-overlap"), inputs, EngineProgress.SILENT);

        List<NullModelResult> a = onlyEngine(withBox, "volume-overlap");
        List<NullModelResult> b = onlyEngine(withoutBox, "volume-overlap");
        assertEquals(b.size(), a.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).direction(), b.get(i).direction());
            assertEquals(a.get(i).observed(), b.get(i).observed(), 0.0);
            double[] first = a.get(i).permutedValues();
            double[] second = b.get(i).permutedValues();
            assertEquals(second.length, first.length);
            for (int p = 0; p < first.length; p++) {
                assertEquals("direction " + a.get(i).direction()
                        + " permutation " + p, second[p], first[p], 0.0);
            }
        }
    }

    // ---------- fixtures ----------

    private static List<NullModelResult> onlyEngine(
            List<NullModelResult> results, String engineId) {
        List<NullModelResult> kept = new ArrayList<NullModelResult>();
        for (int i = 0; i < results.size(); i++) {
            if (engineId.equals(results.get(i).engineId())) {
                kept.add(results.get(i));
            }
        }
        return kept;
    }

    private static List<ColocEngine> enginesFor(String... ids) {
        EngineRegistry registry = EngineRegistry.createDefault();
        List<ColocEngine> all = registry.all();
        List<ColocEngine> chosen = new ArrayList<ColocEngine>();
        for (int i = 0; i < ids.length; i++) {
            for (int e = 0; e < all.size(); e++) {
                if (all.get(e).id().equals(ids[i])) {
                    chosen.add(all.get(e));
                }
            }
        }
        assertEquals("an engine id in this fixture no longer exists",
                ids.length, chosen.size());
        return chosen;
    }

    /**
     * Two channels of small blocks, sparse enough that the per-object permuter
     * can re-pack them and that a shuffle changes the answer.
     */
    private static EngineInputs twoSparseChannels() {
        ImagePlus a = blank(40, 30, 3);
        ImagePlus b = blank(40, 30, 3);
        int label = 1;
        for (int x = 2; x < 34; x += 8) {
            for (int y = 2; y < 26; y += 8) {
                block(a, x, y, label);
                block(b, x + 1, y + 1, label);
                label++;
            }
        }
        List<ImagePlus> labels = new ArrayList<ImagePlus>();
        labels.add(a);
        labels.add(b);
        List<Roi> domain = new ArrayList<Roi>();
        domain.add(new Roi(0, 0, 40, 30));
        return EngineInputs.builder(labels)
                .channelNames(Arrays.asList("A", "B"))
                .domain(domain)
                .build();
    }

    private static void block(ImagePlus image, int x, int y, int label) {
        ImageProcessor slice = image.getStack().getProcessor(2);
        for (int dx = 0; dx < 2; dx++) {
            for (int dy = 0; dy < 2; dy++) {
                slice.setf(x + dx, y + dy, label);
            }
        }
    }

    private static ImagePlus blank(int width, int height, int depth) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            stack.addSlice(new ShortProcessor(width, height));
        }
        return new ImagePlus("fixture", stack);
    }

    private static int countOf(ImagePlus image, int label) {
        int count = 0;
        ImageStack stack = image.getStack();
        for (int z = 1; z <= stack.getSize(); z++) {
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    if ((int) stack.getProcessor(z).getf(x, y) == label) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /** @return {@code {minX, maxX}} of a label, the extent a box measure reads */
    private static int[] boxOf(ImagePlus image, int label) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        ImageStack stack = image.getStack();
        for (int z = 1; z <= stack.getSize(); z++) {
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    if ((int) stack.getProcessor(z).getf(x, y) == label) {
                        min = Math.min(min, x);
                        max = Math.max(max, x);
                    }
                }
            }
        }
        return new int[] {min, max};
    }
}
