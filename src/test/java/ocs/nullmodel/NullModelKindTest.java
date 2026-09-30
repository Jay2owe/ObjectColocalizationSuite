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
import ij.gui.OvalRoi;
import ij.gui.Roi;
import ij.process.ShortProcessor;
import ocs.engine.ColocEngine;
import ocs.engine.DirectionKey;
import ocs.engine.EngineInputs;
import ocs.engine.EngineProgress;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The two null models, and the case that forced the second one to exist.
 *
 * <p>Stage 01 of the α validation found that the per-object permuter refuses
 * every real tissue field of the validation set: 27 of 27 surveyed, every
 * method, every channel pair. Two causes, and this reproduces the harder one in
 * miniature — a channel holding a single object too large to be re-placed
 * anywhere inside the region.
 *
 * <p>The test that matters is the pair: per-object <b>must</b> refuse this
 * field, and whole-channel <b>must</b> get through it. Asserting only the second
 * half would pass just as well if the fixture were easy, and would then be
 * evidence of nothing.
 */
public class NullModelKindTest {

    private static final int SIZE = 40;
    private static final DirectionKey A_TO_B = new DirectionKey(0, "A", 1, "B");

    // ---------- the case that forced this ----------

    @Test
    public void perObjectRefusesAChannelItCannotRepack() {
        List<NullModelResult> results = run(NullModelKind.PER_OBJECT, confluent());

        NullModelResult result = only(results);
        assertFalse("a frame-filling object has one possible position and it is"
                + " outside the region, so this must refuse rather than invent"
                + " a placement", result.ran());
        assertEquals(NullModelResult.Skip.NO_ROOM_IN_DOMAIN, result.skip());
        assertTrue("and p must be absent, not a number",
                Double.isNaN(result.pTwoSided()));
    }

    @Test
    public void wholeChannelGetsThroughTheSameField() {
        List<NullModelResult> results =
                run(NullModelKind.WHOLE_CHANNEL, confluent());

        NullModelResult result = only(results);
        assertTrue("nothing has to be re-packed, so there is nothing to refuse",
                result.ran());
        assertEquals(NullModelResult.Skip.NONE, result.skip());
        assertEquals(64, result.permutations());
        assertFalse("p must be a number, which is the whole point",
                Double.isNaN(result.pTwoSided()));
        assertTrue(String.valueOf(result.pTwoSided()),
                result.pTwoSided() > 0.0 && result.pTwoSided() <= 1.0);
    }

    // ---------- both are still null models ----------

    @Test
    public void wholeChannelActuallyMovesTheStatistic() {
        List<NullModelResult> results =
                run(NullModelKind.WHOLE_CHANNEL, overlapping());

        NullModelResult result = only(results);
        double[] permuted = result.permutedValues();
        boolean moved = false;
        for (int i = 1; i < permuted.length; i++) {
            if (permuted[i] != permuted[0]) {
                moved = true;
                break;
            }
        }
        assertTrue("a null model whose statistic never changes reports p = 1 on"
                + " every dataset and looks like a legitimate verdict", moved);
    }

    @Test
    public void wholeChannelIsDeterministicPerPermutation() {
        double[] first = only(run(NullModelKind.WHOLE_CHANNEL, overlapping()))
                .permutedValues();
        double[] second = only(run(NullModelKind.WHOLE_CHANNEL, overlapping()))
                .permutedValues();

        assertEquals(first.length, second.length);
        for (int i = 0; i < first.length; i++) {
            assertEquals("permutation " + i + " must be the same on every run,"
                    + " bit for bit, or the recorded seed does not replay it",
                    Double.doubleToRawLongBits(first[i]),
                    Double.doubleToRawLongBits(second[i]));
        }
    }

    @Test
    public void theTwoKindsDisagreeOnAFieldBothCanRun() {
        double[] perObject =
                only(run(NullModelKind.PER_OBJECT, overlapping())).permutedValues();
        double[] wholeChannel =
                only(run(NullModelKind.WHOLE_CHANNEL, overlapping())).permutedValues();

        assertTrue("two different null hypotheses must not produce the identical"
                + " permuted distribution, or one of them is not being applied",
                !Arrays.equals(perObject, wholeChannel));
    }

    // ---------- the choice is not silently defaulted ----------

    @Test
    public void theDefaultRunsOnCrowdedFourChannelData() {
        assertEquals("the per-object null refused every surveyed four-channel field",
                NullModelKind.WHOLE_CHANNEL, NullModelRunner.DEFAULT_KIND);
        assertEquals(NullModelKind.WHOLE_CHANNEL,
                NullModelRunner.builder().build().kind());
    }

    @Test
    public void anUnknownKindIsRefusedRatherThanDefaulted() {
        try {
            NullModelKind.byId("toroidal");
            fail("a macro naming a null model that does not exist must fail"
                    + " loudly, not quietly run a different one");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("per-object"));
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("whole-channel"));
        }
    }

    @Test
    public void everyKindRoundTripsThroughItsId() {
        NullModelKind[] all = NullModelKind.values();
        for (int i = 0; i < all.length; i++) {
            assertEquals(all[i], NullModelKind.byId(all[i].id()));
        }
    }

    @Test
    public void aNullKindIsRefused() {
        try {
            NullModelRunner.builder().kind(null);
            fail("defaulting a null here would pick a null hypothesis on the"
                    + " caller's behalf");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("null model kind"));
        }
    }

    // ---------- fixtures ----------

    private static List<NullModelResult> run(NullModelKind kind, EngineInputs inputs) {
        List<ColocEngine> engines =
                Arrays.<ColocEngine>asList(new NullFixtures.TouchCountEngine("touch"));
        return NullModelRunner.builder()
                .permutations(64)
                .seed(20260812L)
                .workers(1)
                .kind(kind)
                .build()
                .run(engines, inputs, EngineProgress.SILENT);
    }

    /**
     * Channel A fills the frame as one object; channel B holds four small ones.
     *
     * <p>The region is a circle inscribed in the frame, so the frame-filling
     * object cannot sit anywhere inside it — the miniature of a confluent GFAP
     * network in an SCN outline.
     */
    private static EngineInputs confluent() {
        return inputs(filled("A"),
                NullFixtures.blocks("B", NullFixtures.fourApart()));
    }

    /** Two ordinary channels both null models can handle. */
    private static EngineInputs overlapping() {
        return inputs(NullFixtures.blocks("A", NullFixtures.fourApart()),
                NullFixtures.blocks("B", new int[][] {{6, 7}, {7, 26},
                        {26, 7}, {27, 26}}));
    }

    private static EngineInputs inputs(ImagePlus a, ImagePlus b) {
        return EngineInputs.builder(Arrays.asList(a, b))
                .channelNames(Arrays.asList("A", "B"))
                .domain(Arrays.<Roi>asList(
                        (Roi) new OvalRoi(2, 2, SIZE - 4, SIZE - 4)))
                .build();
    }

    /** One object covering every voxel of the frame. */
    private static ImagePlus filled(String title) {
        ImageStack stack = new ImageStack(SIZE, SIZE);
        ShortProcessor slice = new ShortProcessor(SIZE, SIZE);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                slice.set(x, y, 1);
            }
        }
        stack.addSlice(slice);
        return new ImagePlus(title, stack);
    }

    private static NullModelResult only(List<NullModelResult> results) {
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).direction().equals(A_TO_B)) {
                return results.get(i);
            }
        }
        throw new AssertionError("no A->B result in " + results);
    }
}
