/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ocs.validation;

import java.util.Arrays;

/**
 * What <i>p</i> this test could have reported, given the shuffles it actually
 * drew.
 *
 * <h2>The problem this exists to solve</h2>
 *
 * The chance test's statistic is a whole number: how many objects were called
 * coincident. On a sparse channel a real field might score 3, and so might forty
 * of the four hundred shuffled fields. The suite counts a tie as "at least as
 * extreme" in <i>both</i> directions — the honest choice, since a tie is no
 * evidence either way — and the effect is that <i>p</i> comes out larger than it
 * would with a continuous statistic. The test under-calls rather than over-calls,
 * which is the safe direction, but it means the <i>p</i> values from a perfectly
 * working test are <b>not</b> spread evenly, and judging them as though they
 * should be would condemn a test for being careful.
 *
 * <p>The everyday version: a race where several runners cross the line together.
 * Asking "what fraction of runners did I beat" has no clean answer, and any rule
 * you pick makes the ties count for or against you. Whatever rule you pick, you
 * have to score the whole field by that same rule before you can say whether
 * your position was unusual.
 *
 * <h2>What this computes</h2>
 *
 * Given one real answer and the shuffled answers it was measured against, the
 * exact set of <i>p</i> values the test could have reported if any one of those
 * numbers had been the real one. Under the null the real answer is just one more
 * draw from the same pot, equally likely to have been any of them, so that set
 * <b>is</b> the distribution <i>p</i> should follow — ties and all. It needs no
 * assumption and no simulation; it is arithmetic on the numbers the run already
 * produced.
 *
 * <p>Two things come out of it. The exact chance that this particular test
 * reports <i>p</i> below a cut-off, which is what the false-positive rate should
 * be compared against instead of a flat 0.05. And the position of the reported
 * <i>p</i> within that set, which — spread across the width of its own tie group
 * — is exactly evenly distributed when the test is working, and so can be
 * checked with the ordinary textbook statistic.
 *
 * <h2>Where it is exactly right and where it is not</h2>
 *
 * The argument needs the real answer to be exchangeable with the shuffled ones:
 * one more draw from the same pot. That holds outright when both channels were
 * themselves produced by the shuffle being tested, which is the
 * scrambled-versus-scrambled pair in the negative-control run. With one channel
 * left as the microscope found it, it is a good approximation rather than an
 * identity, and the gap between the two is one of the things stage 02 sets out
 * to measure rather than assume.
 */
public final class PermutationNull {

    private PermutationNull() {
    }

    /**
     * Every <i>p</i> this test could have reported, one entry per possible real
     * answer, sorted.
     *
     * <p>The suite's own formulas reduce to something that depends only on the
     * value, not on which slot it sat in:
     *
     * <pre>
     * enrichment p = (how many of all P+1 values are &gt;= v) / (P + 1)
     * depletion  p = (how many of all P+1 values are &lt;= v) / (P + 1)
     * </pre>
     *
     * because {@code NullModelResult} adds one to each count for the observation
     * itself, and the observation is one of the {@code P + 1}. So the whole
     * distribution follows from scoring every value in the pot by the same rule
     * that scored the real one.
     */
    public static double[] conditionalSupport(double observed, double[] permuted) {
        if (permuted == null || permuted.length == 0) {
            throw new IllegalArgumentException(
                    "a permutation null needs at least one shuffle");
        }
        int total = permuted.length + 1;
        double[] all = new double[total];
        all[0] = observed;
        System.arraycopy(permuted, 0, all, 1, permuted.length);
        double[] sorted = all.clone();
        Arrays.sort(sorted);

        double[] support = new double[total];
        for (int i = 0; i < total; i++) {
            double value = all[i];
            int atOrAbove = total - countBelow(sorted, value);
            int atOrBelow = countAtOrBelow(sorted, value);
            double enrichment = atOrAbove / (double) total;
            double depletion = atOrBelow / (double) total;
            support[i] = Math.min(1.0, 2.0 * Math.min(enrichment, depletion));
        }
        Arrays.sort(support);
        return support;
    }

    /**
     * The exact chance this test reports <i>p</i> below a cut-off when there is
     * nothing to find.
     *
     * <p>Strictly below, matching {@code Verdict.from}, which calls a direction
     * not significant when {@code p >= alpha}. Summed over a set of tests this
     * gives the number of false positives to expect from them — the number the
     * observed count has to be judged against, in place of {@code 0.05 × n}.
     */
    public static double tailProbability(double[] support, double alpha) {
        int below = 0;
        for (int i = 0; i < support.length; i++) {
            if (support[i] < alpha) {
                below++;
            }
        }
        return below / (double) support.length;
    }

    /**
     * Where the reported <i>p</i> sits in that set: the share of it strictly
     * below, and the share at or below.
     *
     * <p>Two numbers rather than one because ties make the answer a range. If
     * eleven of four hundred and two possible answers give exactly this <i>p</i>,
     * the reported value occupies the whole stretch between those two shares, and
     * collapsing that to a point is what makes a careful test look skewed.
     *
     * @return {@code {below, atOrBelow}}, each in [0, 1] with below &lt;= atOrBelow
     */
    public static double[] cdfBracket(double[] support, double p) {
        int below = 0;
        int atOrBelow = 0;
        for (int i = 0; i < support.length; i++) {
            if (support[i] < p) {
                below++;
            }
            if (support[i] <= p) {
                atOrBelow++;
            }
        }
        return new double[] {below / (double) support.length,
                atOrBelow / (double) support.length};
    }

    /**
     * The reported <i>p</i> turned into a number that really is evenly spread
     * when the test is working.
     *
     * <p>Take the stretch the <i>p</i> occupies and pick a point in it at random.
     * Across many tests those points fill [0, 1] exactly evenly under the null,
     * however lumpy the underlying ladder is and however many ties there were —
     * so the ordinary textbook check applies to them, and a departure means
     * something about the null model rather than about arithmetic on whole
     * numbers.
     *
     * <p>The randomness is not a fudge and it is not applied to any reported
     * result: it is the standard way to read a discrete test, it moves nothing by
     * more than the width of one tie group, and the draw is seeded so the number
     * in the findings file can be reproduced.
     *
     * @param fraction where in the stretch to land, from a seeded generator
     */
    public static double randomized(double below, double atOrBelow, double fraction) {
        if (fraction < 0.0 || fraction >= 1.0) {
            throw new IllegalArgumentException(
                    "fraction must lie in [0, 1), got " + fraction);
        }
        if (atOrBelow < below) {
            throw new IllegalArgumentException("the stretch runs backwards: "
                    + below + " to " + atOrBelow);
        }
        return below + fraction * (atOrBelow - below);
    }

    private static int countAtOrBelow(double[] sorted, double x) {
        int low = 0;
        int high = sorted.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (sorted[middle] <= x) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private static int countBelow(double[] sorted, double x) {
        int low = 0;
        int high = sorted.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (sorted[middle] < x) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }
}
