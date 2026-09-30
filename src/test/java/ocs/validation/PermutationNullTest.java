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
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Proves the tie-aware reference distribution against the shipped suite.
 *
 * <p>The claim being checked is a strong one: that scoring every value in the
 * pot by the rule that scored the real answer reproduces exactly what
 * {@code NullModelResult} would report had that value been the real answer. If
 * it does not, every expected false-positive rate in {@code 02_FINDINGS.md} is
 * about a distribution the plugin cannot produce. So the reference is checked
 * against {@code NullModelResult} itself rather than against this file's own
 * arithmetic, on pots with ties deliberately planted in them.
 */
public class PermutationNullTest {

    private static final double TIGHT = 1e-12;
    private static final DirectionKey DIRECTION = new DirectionKey(0, "A", 1, "B");

    /**
     * Slide the real answer to every value in the pot in turn and ask the suite
     * what p it reports; the answers must be exactly the reference distribution.
     * Ties are planted at 5 and at 2 so the tie handling is exercised on both
     * sides of the middle.
     */
    @Test
    public void theReferenceIsWhatTheSuiteWouldReportForEveryValueInThePot() {
        double[] pot = {2, 2, 3, 5, 5, 5, 7, 8, 9, 12};
        double[] fromSuite = new double[pot.length];
        for (int i = 0; i < pot.length; i++) {
            double observed = pot[i];
            double[] others = withoutIndex(pot, i);
            fromSuite[i] = NullModelResult.of("test", DIRECTION, observed, others, 1L)
                    .pTwoSided();
        }
        Arrays.sort(fromSuite);
        double[] reference = PermutationNull.conditionalSupport(
                pot[0], withoutIndex(pot, 0));
        assertArrayEquals(fromSuite, reference, TIGHT);
    }

    /** With no ties at all it collapses to the plain ladder for that count. */
    @Test
    public void withNoTiesItIsTheOrdinaryLadder() {
        double[] permuted = {1, 2, 3, 4, 5, 6, 7, 8, 9};
        double[] reference = PermutationNull.conditionalSupport(0.5, permuted);
        assertArrayEquals(Uniformity.discreteSupport(9), reference, TIGHT);
    }

    /**
     * Every shuffle scoring what the real one did: no evidence of anything, and
     * the only p the test can report is 1. Worked out by hand — all ten values
     * are 3, so both tails count all ten, each p is 1, and the doubling is
     * capped.
     */
    @Test
    public void aPotOfIdenticalValuesCanOnlyEverReportOne() {
        double[] permuted = {3, 3, 3, 3, 3, 3, 3, 3, 3};
        double[] reference = PermutationNull.conditionalSupport(3, permuted);
        assertEquals(10, reference.length);
        for (int i = 0; i < reference.length; i++) {
            assertEquals(1.0, reference[i], TIGHT);
        }
        assertEquals(0.0, PermutationNull.tailProbability(reference, 0.05), TIGHT);
    }

    /**
     * The number that replaces a flat 0.05. A pot of ten distinct values can
     * report 0.2 at best, so its exact chance of clearing 0.05 is zero — and a
     * gate comparing its false-positive count against 0.05 × n would be asking
     * for positives the test cannot produce.
     */
    @Test
    public void theExactFalsePositiveRateIsZeroWhenThePotIsTooSmallToReachAlpha() {
        double[] permuted = {1, 2, 3, 4, 5, 6, 7, 8, 9};
        double[] reference = PermutationNull.conditionalSupport(0.5, permuted);
        assertEquals(0.0, PermutationNull.tailProbability(reference, 0.05), TIGHT);
        assertEquals(0.2, PermutationNull.tailProbability(reference, 0.3), TIGHT);
    }

    /** Strictly below, matching Verdict.from, which tests {@code p >= alpha}. */
    @Test
    public void theExactRateExcludesAPSittingExactlyOnAlpha() {
        double[] support = {0.2, 0.2, 0.4, 0.4, 1.0};
        assertEquals(0.0, PermutationNull.tailProbability(support, 0.2), TIGHT);
        assertEquals(0.4, PermutationNull.tailProbability(support, 0.21), TIGHT);
    }

    /**
     * Ties turn the position of a p into a stretch. Two of the five values are
     * 0.4, so a reported 0.4 runs from 2/5 to 4/5.
     */
    @Test
    public void aTiedPOccupiesAStretchRatherThanAPoint() {
        double[] support = {0.2, 0.2, 0.4, 0.4, 1.0};
        double[] bracket = PermutationNull.cdfBracket(support, 0.4);
        assertEquals(0.4, bracket[0], TIGHT);
        assertEquals(0.8, bracket[1], TIGHT);
    }

    /** The largest p reaches the top of the range; there is nothing above it. */
    @Test
    public void theLargestPReachesTheTopOfTheRange() {
        double[] support = {0.2, 0.2, 0.4, 0.4, 1.0};
        double[] bracket = PermutationNull.cdfBracket(support, 1.0);
        assertEquals(0.8, bracket[0], TIGHT);
        assertEquals(1.0, bracket[1], TIGHT);
    }

    @Test
    public void aPointInTheStretchLandsWhereItIsAskedTo() {
        assertEquals(0.4, PermutationNull.randomized(0.4, 0.8, 0.0), TIGHT);
        assertEquals(0.6, PermutationNull.randomized(0.4, 0.8, 0.5), TIGHT);
        assertEquals(0.4, PermutationNull.randomized(0.4, 0.4, 0.9), TIGHT);
    }

    @Test
    public void aFractionOutsideItsRangeIsRefused() {
        try {
            PermutationNull.randomized(0.4, 0.8, 1.0);
            fail("expected a refusal");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("[0, 1)"));
        }
    }

    /**
     * The whole point, end to end: put a heavily tied test through the
     * transform many times and the results must fill [0, 1] evenly, even though
     * the p values themselves take only a handful of distinct values.
     *
     * <p>The pot here is a sparse count — nine shuffles scoring 0, 1 or 2 — which
     * is what a sparse marker looks like on real tissue. Its p values take three values;
     * its transformed positions are spread flat.
     */
    @Test
    public void theTransformFlattensEvenAHeavilyTiedTest() {
        double[] pot = {0, 0, 0, 0, 1, 1, 1, 2, 2, 2};
        Random random = new Random(11L);
        double[] flattened = new double[pot.length * 400];
        int at = 0;
        for (int repeat = 0; repeat < 400; repeat++) {
            for (int i = 0; i < pot.length; i++) {
                double[] others = withoutIndex(pot, i);
                double p = NullModelResult.of("test", DIRECTION, pot[i], others, 1L)
                        .pTwoSided();
                double[] support = PermutationNull.conditionalSupport(pot[i], others);
                double[] bracket = PermutationNull.cdfBracket(support, p);
                flattened[at++] = PermutationNull.randomized(
                        bracket[0], bracket[1], random.nextDouble());
            }
        }
        // 4000 draws from a genuinely uniform source clear 1.36/sqrt(n) = 0.0215
        // comfortably; a distribution stuck on three lumps would not.
        double gap = Uniformity.ks(flattened);
        assertTrue("KS " + gap + " against a flat 0.0215 bar",
                gap < Uniformity.ksCritical5(flattened.length));
        assertTrue("KS " + gap + " is suspiciously perfect", gap > 0.0);
    }

    /**
     * Untransformed, the same test is nowhere near flat. Without this the test
     * above would prove only that the arithmetic runs.
     *
     * <p>Worked out by hand. A field scoring 0 beats none of the ten and ties
     * four, giving p = 0.8; scoring 1 gives p = 1; scoring 2 gives p = 0.6. So
     * the ten p values are three 0.6s, four 0.8s and three 1.0s, nothing below
     * 0.6 at all, and the running count is 0.6 clear of where an even spread
     * would be by the time the first value arrives.
     */
    @Test
    public void theSameTestUntransformedIsClearlyNotFlat() {
        double[] pot = {0, 0, 0, 0, 1, 1, 1, 2, 2, 2};
        double[] raw = new double[pot.length];
        for (int i = 0; i < pot.length; i++) {
            raw[i] = NullModelResult.of("test", DIRECTION, pot[i],
                    withoutIndex(pot, i), 1L).pTwoSided();
        }
        Arrays.sort(raw);
        assertArrayEquals(new double[] {0.6, 0.6, 0.6, 0.8, 0.8, 0.8, 0.8,
                1.0, 1.0, 1.0}, raw, TIGHT);
        assertEquals(0.6, Uniformity.ks(raw), TIGHT);
    }

    @Test
    public void aReferenceWithNoShufflesAtAllIsRefused() {
        try {
            PermutationNull.conditionalSupport(1.0, new double[0]);
            fail("expected a refusal");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("at least one shuffle"));
        }
    }

    private static double[] withoutIndex(double[] values, int index) {
        double[] rest = new double[values.length - 1];
        int at = 0;
        for (int i = 0; i < values.length; i++) {
            if (i != index) {
                rest[at++] = values[i];
            }
        }
        return rest;
    }
}
