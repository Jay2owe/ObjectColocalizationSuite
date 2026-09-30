/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ocs.validation;

import ocs.engine.DirectionKey;
import ocs.nullmodel.NullModelResult;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Proves the uniformity statistics on inputs whose answers are known by hand.
 *
 * <p>Exit gate 4 of {@code 02_negative-control.md}. The point is not that the
 * code runs: it is that a control which reports "calibrated" has to be trusted,
 * and a statistic nobody has checked against a worked example is not evidence of
 * anything. Every expected number below is either arithmetic written out in the
 * comment or is taken from the shipped {@code NullModelResult} rather than from
 * this file's own idea of what a <i>p</i> value is.
 */
public class UniformityTest {

    private static final double EXACT = 0.0;
    private static final double TIGHT = 1e-12;

    // ---------- the ladder ----------

    /**
     * The ladder is derived from a formula in {@link Uniformity}; the suite
     * computes its <i>p</i> values in {@code NullModelResult}. If those two ever
     * disagree the control is measuring a distribution the plugin cannot
     * produce, and every conclusion drawn from it is about nothing.
     *
     * <p>Nine shuffles with distinct values 1..9, and an observation slid to
     * each of the ten possible ranks in turn.
     */
    @Test
    public void ladderMatchesWhatTheSuiteActuallyComputes() {
        double[] permuted = new double[9];
        for (int i = 0; i < 9; i++) {
            permuted[i] = i + 1;
        }
        DirectionKey direction = new DirectionKey(0, "A", 1, "B");
        double[] fromSuite = new double[10];
        for (int rank = 0; rank < 10; rank++) {
            NullModelResult result = NullModelResult.of("test", direction,
                    rank + 0.5, permuted, 1L);
            fromSuite[rank] = result.pTwoSided();
        }
        Arrays.sort(fromSuite);
        assertArrayEquals(fromSuite, Uniformity.discreteSupport(9), TIGHT);
    }

    /** Nine shuffles: two-sided p can only be 0.2, 0.4, 0.6, 0.8 or 1, twice each. */
    @Test
    public void ladderForNineShufflesIsTheTenValuesWrittenOut() {
        double[] expected = {0.2, 0.2, 0.4, 0.4, 0.6, 0.6, 0.8, 0.8, 1.0, 1.0};
        assertArrayEquals(expected, Uniformity.discreteSupport(9), TIGHT);
    }

    /** One shuffle can only ever say "1" — two outcomes, both capped. */
    @Test
    public void ladderForOneShuffleIsBothOutcomesAtOne() {
        assertArrayEquals(new double[] {1.0, 1.0},
                Uniformity.discreteSupport(1), TIGHT);
    }

    @Test
    public void ladderHasOneRungPerPossibleOutcome() {
        assertEquals(402, Uniformity.discreteSupport(401).length);
        assertEquals(101, Uniformity.discreteSupport(100).length);
    }

    @Test
    public void ladderRefusesZeroShuffles() {
        try {
            Uniformity.discreteSupport(0);
            fail("expected a refusal");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("at least one permutation"));
        }
    }

    // ---------- what a working test should produce ----------

    /**
     * At 401 shuffles the rungs either side of 0.05 are 20/402 and 22/402, so a
     * flawless test lands below 0.05 exactly 20/402 = 0.049751 of the time — not
     * 0.05. The gate has to be read against this number, not the nominal one.
     */
    @Test
    public void expectedRateAtFourHundredAndOneShufflesIsNotQuiteNominal() {
        assertEquals(20.0 / 402.0, Uniformity.expectedTail(401, 0.05), TIGHT);
        assertEquals(4.0 / 402.0, Uniformity.expectedTail(401, 0.01), TIGHT);
    }

    /**
     * The default shipped shuffle count. Its smallest two-sided p is 2/101 =
     * 0.0198, so only the rungs 0.0198 and 0.0396 sit below 0.05 and a flawless
     * test lands on one of them 4/101 = 0.0396 of the time — a false-positive
     * rate 21% below nominal. Below 0.01 nothing is reachable at all, so α =
     * 0.01 at this shuffle count is a cut-off no result can ever clear.
     */
    @Test
    public void expectedRateAtTheShippedDefaultIsWellBelowNominal() {
        assertEquals(4.0 / 101.0, Uniformity.expectedTail(100, 0.05), TIGHT);
        assertEquals(0.0, Uniformity.expectedTail(100, 0.01), TIGHT);
    }

    // ---------- tail ----------

    /**
     * {@code Verdict.from} calls a direction not significant when
     * {@code p >= alpha}, so a p of exactly alpha is not a finding.
     */
    @Test
    public void tailExcludesAPValueSittingExactlyOnAlpha() {
        double[] p = {0.049, 0.05, 0.051};
        assertEquals(1.0 / 3.0, Uniformity.tail(p, 0.05), TIGHT);
    }

    @Test
    public void tailCountsEveryValueWhenAlphaIsAboveThemAll() {
        double[] p = {0.1, 0.2, 0.9};
        assertEquals(1.0, Uniformity.tail(p, 1.01), EXACT);
    }

    @Test
    public void tailRefusesAnEmptySample() {
        try {
            Uniformity.tail(new double[0], 0.05);
            fail("expected a refusal");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("undefined"));
        }
    }

    // ---------- ks against the smooth uniform ----------

    /**
     * Values at the midpoint of each of n equal slices are as evenly spread as n
     * values can be, and the worst gap is then exactly half a slice.
     */
    @Test
    public void perfectlySpreadValuesGiveExactlyHalfASlice() {
        for (int n = 1; n <= 50; n++) {
            double[] p = new double[n];
            for (int i = 0; i < n; i++) {
                p[i] = (i + 0.5) / n;
            }
            assertEquals("n = " + n, 0.5 / n, Uniformity.ks(p), TIGHT);
        }
    }

    /**
     * Four values at 0.1, 0.2, 0.3, 0.4. Running count after the fourth is 4/4,
     * where a smooth uniform would be at 0.4, so the worst gap is 0.6.
     */
    @Test
    public void aSampleCrowdedIntoTheLowEndHasAKnownWorstGap() {
        double[] p = {0.1, 0.2, 0.3, 0.4};
        assertEquals(0.6, Uniformity.ks(p), TIGHT);
    }

    /** Everything piled at zero is the worst a sample can be: the gap is 1. */
    @Test
    public void everythingAtZeroGivesAGapOfOne() {
        double[] p = {0.0, 0.0, 0.0, 0.0};
        assertEquals(1.0, Uniformity.ks(p), TIGHT);
    }

    /** Order in, order out: the caller's array is not sorted underneath them. */
    @Test
    public void ksLeavesTheCallersArrayAlone() {
        double[] p = {0.9, 0.1, 0.5};
        Uniformity.ks(p);
        assertArrayEquals(new double[] {0.9, 0.1, 0.5}, p, EXACT);
    }

    // ---------- ks against the ladder ----------

    /** A sample that is the ladder, once each, sits on it exactly. */
    @Test
    public void aSampleThatIsTheLadderHasNoGapAtAll() {
        double[] support = Uniformity.discreteSupport(401);
        assertEquals(0.0, Uniformity.ksAgainst(support, support), EXACT);
    }

    /**
     * Ladder for nine shuffles: 0.2 0.2 0.4 0.4 0.6 0.6 0.8 0.8 1.0 1.0.
     * Sample: two values, both 0.2. At 0.2 the sample is already complete (1.0)
     * while the ladder has reached only 2/10, so the gap is 0.8; nothing later
     * beats it. Worked by hand because a discrete-versus-discrete comparison is
     * exactly where an off-by-one in the step handling would hide.
     */
    @Test
    public void aSampleStuckOnTheBottomRungHasAKnownGap() {
        double[] support = Uniformity.discreteSupport(9);
        double[] p = {0.2, 0.2};
        assertEquals(0.8, Uniformity.ksAgainst(p, support), TIGHT);
    }

    /**
     * Two values at the top rung. Against the ladder the gap is 0.8, because the
     * ladder has itself reached 0.8 just below 1. Against the smooth uniform it
     * is 1.0, because a smooth uniform is still at 0 there. Charging a
     * permutation test for the space between rungs it can never occupy is what
     * makes a correct test look broken, and it is the whole reason this stage
     * measures against the ladder.
     */
    @Test
    public void theSmoothComparisonOverchargesASampleOnTheTopRung() {
        double[] p = {1.0, 1.0};
        assertEquals(1.0, Uniformity.ks(p), TIGHT);
        assertEquals(0.8, Uniformity.ksAgainst(p, Uniformity.discreteSupport(9)), TIGHT);
    }

    /**
     * A sample avoiding the top rung shows up even though no data point sits
     * there. Ladder for nine shuffles again; sample is one value at 0.2. At 0.2
     * the sample is complete and the ladder is at 2/10, so the gap is 0.8; the
     * check has to look at rungs the data never landed on to see the rest of it.
     */
    @Test
    public void aRungTheSampleNeverLandsOnIsStillChecked() {
        double[] support = Uniformity.discreteSupport(9);
        assertEquals(0.8, Uniformity.ksAgainst(new double[] {0.2}, support), TIGHT);
        // And a sample only on the top rung: just below 1.0 the ladder has
        // reached 8/10 while the sample is still at 0, so the gap is 0.8 there
        // and 0 at 1.0 itself. Only the just-below check can see it.
        assertEquals(0.8, Uniformity.ksAgainst(new double[] {1.0}, support), TIGHT);
    }

    @Test
    public void ksAgainstLeavesBothCallerArraysAlone() {
        double[] p = {0.6, 0.2};
        double[] support = {1.0, 0.2, 0.6};
        Uniformity.ksAgainst(p, support);
        assertArrayEquals(new double[] {0.6, 0.2}, p, EXACT);
        assertArrayEquals(new double[] {1.0, 0.2, 0.6}, support, EXACT);
    }

    // ---------- critical values ----------

    @Test
    public void asymptoticCriticalValueIsTheTextbookFormula() {
        assertEquals(1.36 / Math.sqrt(500), Uniformity.ksCritical5(500), TIGHT);
    }

    /**
     * On a ladder fine enough to look smooth, the drawn critical value has to
     * land near the textbook one — otherwise the sampler and the formula
     * disagree about what they are measuring and neither can be trusted.
     *
     * <p>999 shuffles is a rung every 0.002, against a sample of 200. The exact
     * finite-sample value at n = 200 is about 1% under the asymptotic one, so
     * 10% is a real bound rather than a shrug.
     */
    @Test
    public void aFineLadderReproducesTheTextbookCriticalValue() {
        double[] support = Uniformity.discreteSupport(999);
        double drawn = Uniformity.ksCritical5(support, 200, 1000, 4242L);
        double textbook = Uniformity.ksCritical5(200);
        assertTrue("drawn " + drawn + " vs textbook " + textbook,
                Math.abs(drawn - textbook) < 0.10 * textbook);
    }

    /**
     * On a coarse ladder it must land clearly below. A statistic that cannot get
     * as large needs a lower bar, and using the textbook bar there is how a
     * miscalibrated null passes a control.
     */
    @Test
    public void aCoarseLadderNeedsALowerBarThanTheTextbookOne() {
        double[] support = Uniformity.discreteSupport(9);
        double drawn = Uniformity.ksCritical5(support, 200, 1000, 4242L);
        assertTrue("drawn " + drawn, drawn < Uniformity.ksCritical5(200));
        assertTrue("drawn " + drawn, drawn > 0.0);
    }

    /** Same seed, same number — the findings file quotes it. */
    @Test
    public void drawnCriticalValueIsReproducibleFromItsSeed() {
        double[] support = Uniformity.discreteSupport(99);
        double first = Uniformity.ksCritical5(support, 120, 400, 7L);
        double second = Uniformity.ksCritical5(support, 120, 400, 7L);
        assertEquals(first, second, EXACT);
        double other = Uniformity.ksCritical5(support, 120, 400, 8L);
        assertTrue("two seeds gave the identical value, so the seed is ignored",
                first != other);
    }

    // ---------- histogram ----------

    /** One value in the middle of each of twenty bins puts one in each. */
    @Test
    public void everyBinGetsItsOwnValue() {
        double[] p = new double[20];
        for (int i = 0; i < 20; i++) {
            p[i] = (2 * i + 1) / 40.0;
        }
        int[] counts = Uniformity.histogram(p, 20);
        int[] expected = new int[20];
        Arrays.fill(expected, 1);
        assertArrayEquals(expected, counts);
    }

    /** Exactly 1 belongs in the top bin, not in a nineteenth of its own. */
    @Test
    public void aPOfExactlyOneLandsInTheTopBin() {
        int[] counts = Uniformity.histogram(new double[] {1.0, 0.0}, 4);
        assertArrayEquals(new int[] {1, 0, 0, 1}, counts);
    }

    @Test
    public void histogramRefusesAValueOutsideZeroToOne() {
        try {
            Uniformity.histogram(new double[] {0.5, 1.5}, 4);
            fail("expected a refusal");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("1.5"));
        }
    }

    @Test
    public void histogramRefusesNaN() {
        try {
            Uniformity.histogram(new double[] {Double.NaN}, 4);
            fail("expected a refusal");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("NaN"));
        }
    }
}
