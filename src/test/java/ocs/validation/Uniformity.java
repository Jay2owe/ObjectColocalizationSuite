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
import java.util.Random;

/**
 * Is a set of <i>p</i> values shaped the way a working test would make them?
 *
 * <h2>The idea in one sentence</h2>
 *
 * If you run a fair test on data where there is genuinely nothing to find, the
 * <i>p</i> values it hands back should be spread evenly between 0 and 1 — a fair
 * die, not a loaded one. Five per cent of them should fall below 0.05, one per
 * cent below 0.01, and so on all the way along. If they pile up near zero the
 * test is calling findings that are not there; if they pile up near one it is
 * missing findings that are.
 *
 * <h2>Why "evenly" is not quite the right word here</h2>
 *
 * A permutation test cannot produce just any number. With <i>P</i> shuffles it
 * counts how many of them beat the real answer, so its one-sided <i>p</i> is
 * always one of the fractions {@code 1/(P+1), 2/(P+1), ... , 1}, and the
 * two-sided <i>p</i> the suite reports is twice the smaller tail, capped at 1.
 * At 401 shuffles that is a ladder with 402 rungs and nothing in between them.
 *
 * <p>So the honest comparison is not against a smooth uniform distribution but
 * against <b>the exact ladder the test can actually produce</b>, which
 * {@link #discreteSupport(int)} builds. Comparing against the smooth version
 * instead makes a perfectly calibrated test look slightly wrong, and the size of
 * that illusion grows as the shuffle count falls — which is exactly the regime
 * this project cares about, including the measured 100/1,000/10,000 counts.
 *
 * <h2>What is deliberately not here</h2>
 *
 * No significance decision. This class returns statistics and critical values;
 * whether a number clears its critical value is a sentence in
 * {@code 02_FINDINGS.md}, written by a person who can also see how many of the
 * inputs were independent. Nothing here knows that six methods run on one field
 * are six views of the same field rather than six separate experiments, and a
 * class that returned "calibrated: yes" would be hiding that.
 */
public final class Uniformity {

    /**
     * The multiplier in the classical one-sample Kolmogorov–Smirnov 5% critical
     * value {@code 1.36 / sqrt(n)}.
     *
     * <p>Asymptotic, and derived for a <i>continuous</i> reference distribution.
     * Against the discrete ladder above it is conservative: the statistic cannot
     * get as large, so a value that exceeds this really is too large, while a
     * value that does not has cleared a bar set slightly too high. Use
     * {@link #ksCritical5(double[], int, int, long)} when the difference
     * matters, which is whenever the shuffle count is small.
     */
    public static final double KS_ASYMPTOTIC_5_PERCENT = 1.36;

    private Uniformity() {
    }

    // ---------- the ladder a permutation test can produce ----------

    /**
     * Every two-sided <i>p</i> a permutation test with this many shuffles can
     * return, each appearing as often as it should when there is nothing to
     * find. Sorted ascending, one entry per possible outcome.
     *
     * <p>Derivation, so the array is checkable rather than magic. Under the null
     * the real answer is just one more draw from the same pot as the <i>P</i>
     * shuffled answers, so its rank <i>R</i> among all {@code P + 1} of them is
     * equally likely to be any of {@code 1 .. P+1}. At rank <i>R</i> the suite's
     * {@code NullModelResult} computes
     *
     * <pre>
     * depletion p = R / (P + 1)              (shuffles at or below it, plus itself)
     * enrichment p = (P + 2 - R) / (P + 1)   (shuffles at or above it, plus itself)
     * two-sided p = min(1, 2 * min(the two))
     * </pre>
     *
     * <p>so this returns those {@code P + 1} values. Ties between shuffled
     * answers push both tails up and therefore make the reported <i>p</i>
     * larger, never smaller — the suite's statistic is a whole number of
     * coincident objects, so ties are common and the test is conservative on
     * sparse channels rather than optimistic. That is worth measuring separately
     * and is not modelled here.
     *
     * @param permutations how many shuffles, at least one
     */
    public static double[] discreteSupport(int permutations) {
        if (permutations < 1) {
            throw new IllegalArgumentException(
                    "at least one permutation, got " + permutations);
        }
        int outcomes = permutations + 1;
        double[] support = new double[outcomes];
        for (int rank = 1; rank <= outcomes; rank++) {
            double depletion = rank / (double) outcomes;
            double enrichment = (outcomes + 1 - rank) / (double) outcomes;
            double smaller = Math.min(depletion, enrichment);
            support[rank - 1] = Math.min(1.0, 2.0 * smaller);
        }
        Arrays.sort(support);
        return support;
    }

    /**
     * The fraction of that ladder lying strictly below a cut-off — what
     * {@link #tail(double[], double)} should come out at when the test is
     * working.
     *
     * <p>Reported next to the observed fraction because the two are not the same
     * number. At 401 shuffles the rungs nearest 0.05 sit at 0.049751 and
     * 0.054726, so a flawless test produces 0.049751 of its results below 0.05,
     * not 0.05. Judging it against 0.05 charges it for the gap between rungs.
     */
    public static double expectedTail(int permutations, double alpha) {
        return tail(discreteSupport(permutations), alpha);
    }

    // ---------- the two statistics the exit gate names ----------

    /**
     * The fraction of <i>p</i> values below a cut-off, counting the same way the
     * suite's own verdict does.
     *
     * <p><b>Strictly below.</b> {@code Verdict.from(result, alpha)} calls a
     * direction not significant when {@code p >= alpha}, so a <i>p</i> of
     * exactly α is <i>not</i> a finding, and a control that counted it as one
     * would report a false-positive rate the plugin never actually produces.
     * The boundary is reachable: on the discrete ladder α = 0.05 is an exact rung
     * whenever {@code (P + 1)} divides by 40.
     */
    public static double tail(double[] p, double alpha) {
        requireValues(p);
        int below = 0;
        for (int i = 0; i < p.length; i++) {
            if (p[i] < alpha) {
                below++;
            }
        }
        return below / (double) p.length;
    }

    /**
     * The largest gap between where the <i>p</i> values actually sit and where a
     * smooth uniform distribution would put them.
     *
     * <p>The everyday picture: line the values up shortest to longest, and ask
     * at which point the running count is furthest from the count you would have
     * expected by then. That single worst gap is the statistic. It notices a
     * pile-up anywhere along the range, which a count of how many fell below
     * 0.05 cannot.
     *
     * <p>Against the <i>smooth</i> uniform. For permutation <i>p</i> values
     * prefer {@link #ksAgainst(double[], double[])} with
     * {@link #discreteSupport(int)}; this one is kept because it is the textbook
     * definition and because the discrete version has to agree with it when the
     * ladder is fine enough, which is a check worth being able to make.
     */
    public static double ks(double[] p) {
        requireValues(p);
        double[] sorted = p.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        double largest = 0.0;
        for (int i = 0; i < n; i++) {
            double above = (i + 1) / (double) n - sorted[i];
            double below = sorted[i] - i / (double) n;
            largest = Math.max(largest, Math.max(above, below));
        }
        return largest;
    }

    /**
     * The same worst gap, measured against a stated set of possible outcomes
     * rather than against a smooth line.
     *
     * <p>Both curves being compared are staircases here — the data steps up at
     * each value observed, the reference steps up at each rung of the ladder —
     * and between two consecutive steps neither moves. So the widest gap is
     * always found at a step, and every step of <i>either</i> staircase has to be
     * looked at. That is the difference from the textbook formula, which walks
     * the data points alone: a sample sitting only on the top rung has its
     * largest gap at the rung below, where it has no data at all, and walking the
     * data would report no gap whatever.
     *
     * @param support every outcome the reference distribution can take, each
     *                counting once; {@link #discreteSupport(int)} builds it for
     *                a permutation test
     */
    public static double ksAgainst(double[] p, double[] support) {
        requireValues(p);
        requireValues(support);
        double[] sample = p.clone();
        Arrays.sort(sample);
        double[] reference = support.clone();
        Arrays.sort(reference);

        // Every place either staircase can step. Checking only the data points
        // would miss a rung the data never landed on, which is precisely where a
        // sample that avoids one end of the ladder shows itself.
        double[] steps = new double[sample.length + reference.length];
        System.arraycopy(sample, 0, steps, 0, sample.length);
        System.arraycopy(reference, 0, steps, sample.length, reference.length);
        Arrays.sort(steps);

        double largest = 0.0;
        for (int i = 0; i < steps.length; i++) {
            if (i > 0 && steps[i] == steps[i - 1]) {
                continue;
            }
            double x = steps[i];
            double sampleSoFar = countAtOrBelow(sample, x) / (double) sample.length;
            double referenceSoFar =
                    countAtOrBelow(reference, x) / (double) reference.length;
            largest = Math.max(largest, Math.abs(sampleSoFar - referenceSoFar));
        }
        return largest;
    }

    // ---------- how large the gap has to be before it means anything ----------

    /** The classical asymptotic 5% critical value. Report <i>n</i> beside it. */
    public static double ksCritical5(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("n must be positive, got " + n);
        }
        return KS_ASYMPTOTIC_5_PERCENT / Math.sqrt(n);
    }

    /**
     * The 5% critical value for a stated ladder and sample size, obtained by
     * drawing samples from the ladder itself.
     *
     * <p>Needed because {@link #ksCritical5(int)} answers a different question.
     * That formula assumes the reference distribution is smooth, and a coarse
     * ladder makes the worst gap smaller than it would otherwise be, so the
     * textbook bar sits above where it should. Judging a coarse-ladder statistic
     * against it means a genuinely miscalibrated null can slip through.
     *
     * <p>Deterministic in {@code seed}, so the number in the findings file can be
     * reproduced exactly. Costs {@code trials} sorts of {@code n} values, which
     * at the sizes this stage uses is under a second.
     *
     * @param trials how many samples to draw; 10,000 puts the 95th percentile
     *               within about half a percent of itself run to run
     */
    public static double ksCritical5(double[] support, int n, int trials, long seed) {
        requireValues(support);
        if (n < 1) {
            throw new IllegalArgumentException("n must be positive, got " + n);
        }
        if (trials < 1) {
            throw new IllegalArgumentException("trials must be positive, got " + trials);
        }
        Random random = new Random(seed);
        double[] statistics = new double[trials];
        double[] draw = new double[n];
        for (int t = 0; t < trials; t++) {
            for (int i = 0; i < n; i++) {
                draw[i] = support[random.nextInt(support.length)];
            }
            statistics[t] = ksAgainst(draw, support);
        }
        Arrays.sort(statistics);
        // The smallest value that at most 5% of trials exceeded. Rounding up
        // rather than interpolating keeps the bar on the safe side of the
        // nominal rate, which is the direction a control should err in.
        int index = (int) Math.ceil(0.95 * trials) - 1;
        if (index < 0) {
            index = 0;
        }
        if (index >= trials) {
            index = trials - 1;
        }
        return statistics[index];
    }

    // ---------- shape, for a reader rather than a gate ----------

    /**
     * Counts in equal-width bins across [0, 1], so the shape can be looked at
     * rather than summarised.
     *
     * <p>A value of exactly 1 goes in the last bin. It is a common outcome for a
     * two-sided permutation <i>p</i> — the doubling is capped at 1 — and putting
     * it in a bin of its own would make the histogram look as though something
     * odd were happening at the top end.
     */
    public static int[] histogram(double[] p, int bins) {
        requireValues(p);
        if (bins < 1) {
            throw new IllegalArgumentException("at least one bin, got " + bins);
        }
        int[] counts = new int[bins];
        for (int i = 0; i < p.length; i++) {
            double value = p[i];
            if (value < 0.0 || value > 1.0 || Double.isNaN(value)) {
                throw new IllegalArgumentException(
                        "p values must lie in [0, 1], got " + value);
            }
            int bin = (int) (value * bins);
            if (bin >= bins) {
                bin = bins - 1;
            }
            counts[bin] = counts[bin] + 1;
        }
        return counts;
    }

    // ---------- helpers ----------

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

    private static void requireValues(double[] values) {
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException(
                    "no values; a uniformity statistic over nothing is not zero,"
                            + " it is undefined, and reporting zero would read as"
                            + " a perfect result");
        }
    }
}
