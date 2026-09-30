package ocs.sweep;

import ij.ImagePlus;
import ij.process.ShortProcessor;
import ocs.engine.ColocEngine;
import ocs.engine.EngineCancelledException;
import ocs.engine.EngineInputs;
import ocs.engine.EngineProgress;
import ocs.engine.EngineRegistry;
import ocs.engine.ThresholdBearing;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The sweep answers "how much of this result is the threshold?"
 *
 * <p>The fixture is built so the answer is known before the code runs: one pair
 * of objects overlapping heavily, one pair overlapping slightly. As the cut-off
 * rises past the slight pair the second flips and the first does not, so exactly
 * one object of two changes classification.
 */
public class ThresholdSweepTest {

    private static final int SIZE = 30;

    @Test
    public void anEngineWithNoThresholdReportsWhyRatherThanZero() {
        // A flip fraction of 0.0 would read as "measured, perfectly stable".
        // The truth is that a centroid is inside a partner or it is not, so
        // there was no choice to be sensitive to — a point in the method's
        // favour that deserves saying rather than disguising as a measurement.
        ColocEngine cpc = EngineRegistry.createDefault().byId("cpc");
        ThresholdSweep.Result result =
                ThresholdSweep.of(cpc, inputs(), null, EngineProgress.SILENT);

        assertFalse(result.wasSwept());
        assertTrue(result.notSweptReason(),
                result.notSweptReason().contains("no threshold"));
        assertTrue(result.flipFractions().isEmpty());
        assertTrue("no sweep means no number, not a zero",
                Double.isNaN(result.worstFlipFraction()));
    }

    @Test
    public void aThresholdBearingEngineIsSweptAndReportsItsAxis() {
        ColocEngine overlap = EngineRegistry.createDefault().byId("volume-overlap");
        ThresholdSweep.Result result = ThresholdSweep.of(
                overlap, inputs(), new double[] {5.0, 25.0, 50.0, 75.0},
                EngineProgress.SILENT);

        assertTrue(result.wasSwept());
        assertEquals("volume-overlap", result.engineId());
        assertEquals("Overlap", result.thresholdName());
        assertEquals("%", result.thresholdUnit());
        assertEquals(4, result.ladder().length);
        assertFalse(result.flipFractions().isEmpty());
    }

    @Test
    public void exactlyTheObjectThatCrossesTheLadderIsCountedAsFlipped() {
        // A1 overlaps its partner completely; A2 overlaps by a quarter. Sweeping
        // 5% to 75% moves the cut-off past A2 and never past A1.
        ColocEngine overlap = EngineRegistry.createDefault().byId("volume-overlap");
        ThresholdSweep.Result result = ThresholdSweep.of(
                overlap, inputs(), new double[] {5.0, 25.0, 50.0, 75.0},
                EngineProgress.SILENT);

        FlipFraction aToB = null;
        for (int i = 0; i < result.flipFractions().size(); i++) {
            if ("A".equals(result.flipFractions().get(i).direction().sourceName())) {
                aToB = result.flipFractions().get(i);
            }
        }
        assertNotNull(aToB);
        assertEquals("both A objects are present at every step", 2, aToB.objects());
        assertEquals("only the partly-overlapping object changes", 1, aToB.flipped());
        assertEquals(0.5, aToB.value(), 1e-12);
    }

    @Test
    public void anObjectStableAcrossTheWholeLadderIsNotCountedAsFlipped() {
        // Ladder entirely below the weaker overlap, so nothing crosses.
        ColocEngine overlap = EngineRegistry.createDefault().byId("volume-overlap");
        ThresholdSweep.Result result = ThresholdSweep.of(
                overlap, inputs(), new double[] {1.0, 2.0, 3.0},
                EngineProgress.SILENT);
        assertEquals(0.0, result.worstFlipFraction(), 1e-12);
    }

    @Test
    public void aSingleCleanSwitchStillCountsAsAFlip() {
        // The definition is "not the same at every step", not "changes more than
        // once". An object that switches once has still had its classification
        // decided by where the line was drawn.
        FlipFraction one = FlipFraction.across(Arrays.asList(
                ocs.engine.EngineResult.forEngine("e")
                        .direction(direction(), Arrays.asList(
                                new ocs.engine.ObjectScore(1, 1, 0.4, true)))
                        .build(),
                ocs.engine.EngineResult.forEngine("e")
                        .direction(direction(), Arrays.asList(
                                new ocs.engine.ObjectScore(1, 1, 0.4, false)))
                        .build())).get(0);
        assertEquals(1, one.flipped());
        assertEquals(1.0, one.value(), 1e-12);
    }

    @Test
    public void anObjectThatFlipsBackCountsEvenThoughItEndsWhereItStarted() {
        // true -> false -> true. Comparing only the ends misses it entirely, and
        // an object that will not settle is exactly what makes a finding
        // fragile — more so than one that switches once and stays.
        //
        // Added after a mutation check: restricting the comparison to the last
        // step left every other sweep test green, because every other fixture
        // is monotone and for those "differs at the end" and "differs anywhere"
        // are the same question.
        FlipFraction fraction = FlipFraction.across(Arrays.asList(
                step(true), step(false), step(true))).get(0);
        assertEquals(1, fraction.objects());
        assertEquals("an object that flips and flips back has still flipped",
                1, fraction.flipped());
        assertEquals(1.0, fraction.value(), 1e-12);
    }

    private static ocs.engine.EngineResult step(boolean coincident) {
        return ocs.engine.EngineResult.forEngine("e")
                .direction(direction(), Arrays.asList(
                        new ocs.engine.ObjectScore(1, 1, 0.4, coincident)))
                .build();
    }

    @Test
    public void anObjectMissingFromAnyStepIsExcludedRatherThanAssumedStable() {
        // Otherwise a method that stops reporting objects at high thresholds
        // looks more stable the more of them it drops.
        FlipFraction fraction = FlipFraction.across(Arrays.asList(
                ocs.engine.EngineResult.forEngine("e")
                        .direction(direction(), Arrays.asList(
                                new ocs.engine.ObjectScore(1, 1, 0.4, true),
                                new ocs.engine.ObjectScore(2, 2, 0.9, true)))
                        .build(),
                ocs.engine.EngineResult.forEngine("e")
                        .direction(direction(), Arrays.asList(
                                new ocs.engine.ObjectScore(2, 2, 0.9, true)))
                        .build())).get(0);
        assertEquals("object 1 vanished, so it cannot be judged", 1, fraction.objects());
        assertEquals(0, fraction.flipped());
    }

    @Test
    public void everyThresholdBearingEngineOffersAnIncreasingLadder() {
        // The sweep rejects a non-monotone ladder, so a default that is not
        // sorted would fail at run time on a user's data rather than here.
        List<ColocEngine> all = EngineRegistry.createDefault().all();
        int bearing = 0;
        for (int i = 0; i < all.size(); i++) {
            if (!(all.get(i) instanceof ThresholdBearing)) {
                continue;
            }
            bearing++;
            ThresholdBearing engine = (ThresholdBearing) all.get(i);
            double[] ladder = engine.defaultLadder();
            assertTrue(all.get(i).id() + " needs at least two steps", ladder.length >= 2);
            for (int s = 1; s < ladder.length; s++) {
                assertTrue(all.get(i).id() + " ladder must increase at " + s,
                        ladder[s] > ladder[s - 1]);
            }
            assertFalse(all.get(i).id() + " must name its threshold",
                    engine.thresholdName().isEmpty());
        }
        assertTrue("no threshold-bearing engine is registered", bearing >= 5);
    }

    @Test
    public void withThresholdReturnsANewEngineAndLeavesTheOriginalAlone() {
        // The sweep holds several settings at once and the null model may be
        // running the original on another thread.
        ColocEngine original = EngineRegistry.createDefault().byId("volume-overlap");
        ThresholdBearing bearing = (ThresholdBearing) original;
        double before = bearing.threshold();

        ColocEngine changed = bearing.withThreshold(before + 10.0);
        assertEquals(before + 10.0, ((ThresholdBearing) changed).threshold(), 1e-12);
        assertEquals("the original must not have moved", before, bearing.threshold(), 1e-12);
    }

    @Test
    public void aNonIncreasingLadderIsRejected() {
        ColocEngine overlap = EngineRegistry.createDefault().byId("volume-overlap");
        try {
            ThresholdSweep.of(overlap, inputs(), new double[] {50.0, 25.0},
                    EngineProgress.SILENT);
            fail("a ladder that doubles back must be refused");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void cancellationStopsTheSweepRatherThanReturningAPartialLadder() {
        // A flip fraction over half the range understates instability, and there
        // is no way to see that from the number afterwards.
        ColocEngine overlap = EngineRegistry.createDefault().byId("volume-overlap");
        EngineProgress cancelled = new EngineProgress() {
            @Override
            public void report(String stage, double fraction) {
            }

            @Override
            public boolean isCancelled() {
                return true;
            }
        };
        try {
            ThresholdSweep.of(overlap, inputs(), null, cancelled);
            fail("a cancelled sweep must not return");
        } catch (EngineCancelledException expected) {
            assertNotNull(expected);
        }
    }

    // ---------- fixture ----------

    private static ocs.engine.DirectionKey direction() {
        return new ocs.engine.DirectionKey(0, "A", 1, "B");
    }

    /**
     * Channel A: object 1 at (2,2)-(5,5), object 2 at (12,12)-(15,15).
     * Channel B: object 1 covering all of A's object 1; object 2 covering one
     * quarter of A's object 2.
     */
    private static EngineInputs inputs() {
        ShortProcessor a = new ShortProcessor(SIZE, SIZE);
        ShortProcessor b = new ShortProcessor(SIZE, SIZE);
        for (int y = 2; y <= 5; y++) {
            for (int x = 2; x <= 5; x++) {
                a.set(x, y, 1);
                b.set(x, y, 1);
            }
        }
        for (int y = 12; y <= 15; y++) {
            for (int x = 12; x <= 15; x++) {
                a.set(x, y, 2);
            }
        }
        for (int y = 12; y <= 13; y++) {
            for (int x = 12; x <= 13; x++) {
                b.set(x, y, 2);
            }
        }
        return EngineInputs.builder(Arrays.asList(
                        new ImagePlus("A", a), new ImagePlus("B", b)))
                .channelNames(Arrays.asList("A", "B"))
                .build();
    }
}
