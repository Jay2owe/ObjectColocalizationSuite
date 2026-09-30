package ocs.engine.intensity;

import org.junit.Test;

import java.math.BigInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Defect 4 and defect 10.
 *
 * <p>Defect 4 is the one that would be hardest to notice in the field: the naive
 * accumulator returns a number, the number looks like a correlation, and nothing
 * anywhere says it is noise. These tests put the two accumulators side by side on
 * data whose answer is known exactly and measure how many digits each keeps.
 */
public class WelfordAccumulatorTest {

    /**
     * The accumulator this replaced, transcribed from FLASH's
     * {@code ColocalizationMetrics.PearsonAccumulator} at {@code :427-479}.
     *
     * <p>Kept here and nowhere else. It exists so the defect can be measured
     * rather than asserted from memory, and so that if anyone ever proposes going
     * back to a one-pass sum of squares the cost is one test run away.
     */
    private static final class NaiveAccumulator {
        long count;
        double sumA;
        double sumB;
        double sumA2;
        double sumB2;
        double sumAB;

        void add(double a, double b) {
            count++;
            sumA += a;
            sumB += b;
            sumA2 += a * a;
            sumB2 += b * b;
            sumAB += a * b;
        }

        double varA() {
            return count < 2L ? Double.NaN : sumA2 - (sumA * sumA) / (double) count;
        }

        double varB() {
            return count < 2L ? Double.NaN : sumB2 - (sumB * sumB) / (double) count;
        }

        double covariance() {
            return count < 2L ? Double.NaN : sumAB - (sumA * sumB) / (double) count;
        }

        double pearson() {
            if (count < 2L) {
                return Double.NaN;
            }
            double varA = varA();
            double varB = varB();
            if (varA <= 0.0 || varB <= 0.0 || Double.isNaN(varA) || Double.isNaN(varB)) {
                return Double.NaN;
            }
            return covariance() / Math.sqrt(varA * varB);
        }
    }

    /**
     * A 32-bit float channel sitting on a large offset — a deconvolved or
     * flat-field-corrected stack, or any acquisition with the black level well
     * above zero. Values alternate between {@code OFFSET} and {@code OFFSET + 1},
     * so the sum of squared deviations is exactly {@code n / 4} and there is
     * nothing to argue about.
     */
    @Test
    public void welfordKeepsTheDigitsTheNaiveFormThrowsAway() {
        final int n = 1 << 20;
        final double offset = 1.0e9;
        final double exactSumSqDev = n / 4.0;

        WelfordAccumulator welford = new WelfordAccumulator();
        NaiveAccumulator naive = new NaiveAccumulator();
        for (int i = 0; i < n; i++) {
            double value = offset + (i & 1);
            welford.add(value, value);
            naive.add(value, value);
        }

        double welfordError = relativeError(welford.sumSqDevA(), exactSumSqDev);
        double naiveError = relativeError(naive.varA(), exactSumSqDev);

        System.out.println("[defect 4] high-offset float, n=" + n + ", offset=" + offset
                + ": exact=" + exactSumSqDev
                + " welford=" + welford.sumSqDevA() + " (rel err " + welfordError + ")"
                + " naive=" + naive.varA() + " (rel err " + naiveError + ")");

        assertTrue("Welford must keep at least 12 significant digits, relative error was "
                + welfordError, welfordError <= 1.0e-12);
        assertTrue("the naive form must be shown losing at least 6 significant digits,"
                + " relative error was " + naiveError, naiveError >= 1.0e-6);

        // Both channels are identical here, so all three of the naive form's
        // ruined quantities are ruined by the same factor and the ratio still
        // comes out 1.0. That is worth knowing: a diagnostic that pairs a channel
        // with itself would find nothing wrong.
        assertEquals(1.0, welford.pearson(), 1.0e-12);
        assertEquals("the error cancels in the ratio only because a and b are the"
                + " same array", 1.0, naive.pearson(), 1.0e-9);
    }

    /**
     * The same regime with two channels on different scales, which is what a real
     * pair looks like. Now the three ruined quantities are ruined by different
     * factors, nothing cancels, and Pearson itself is wrong.
     */
    @Test
    public void theNaiveFormBreaksPearsonOnceTheChannelsDifferInScale() {
        final int n = 1 << 20;
        final double offset = 1.0e7;

        WelfordAccumulator welford = new WelfordAccumulator();
        NaiveAccumulator naive = new NaiveAccumulator();
        for (int i = 0; i < n; i++) {
            int step = i & 1;
            welford.add(offset + step, offset / 2.0 + 3.0 * step);
            naive.add(offset + step, offset / 2.0 + 3.0 * step);
        }

        // b is an exact affine function of a, so the true correlation is 1.
        assertEquals(1.0, welford.pearson(), 1.0e-11);

        double naiveR = naive.pearson();
        System.out.println("[defect 4] mismatched scales at offset " + offset
                + ": welford r = " + welford.pearson() + ", naive r = " + naiveR
                + ", naive sumSqDevA = " + naive.varA()
                + ", naive sumSqDevB = " + naive.varB());

        // The naive form does not merely lose digits here — the cancellation runs
        // far enough past the true answer that a sum of squared deviations comes
        // back negative, which is arithmetically impossible.
        assertTrue("a sum of squared deviations cannot be negative; the naive form"
                        + " produced A = " + naive.varA() + ", B = " + naive.varB(),
                naive.varA() <= 0.0 || naive.varB() <= 0.0);
        assertTrue("the naive Pearson must be visibly wrong here, was " + naiveR,
                Double.isNaN(naiveR) || Math.abs(naiveR - 1.0) > 1.0e-6);
    }

    /**
     * The ledger's literal case: 16-bit intensities over 50M voxels, which puts
     * the naive {@code sumA2} at the ~4.5e16 the ledger names.
     *
     * <p>Streamed rather than allocated — 50M doubles per channel would be 800 MB
     * of test fixture for an arithmetic property that needs none. The loss here is
     * smaller than in the high-offset case above, because a 16-bit channel's
     * variance is a larger fraction of its mean squared; it is reported rather
     * than gated so the size of the real-world effect is on the record.
     */
    @Test
    public void welfordSurvivesFiftyMillionSixteenBitVoxels() {
        final int n = 50_000_000;
        final int base = 40000;

        WelfordAccumulator welford = new WelfordAccumulator();
        NaiveAccumulator naive = new NaiveAccumulator();
        BigInteger sum = BigInteger.ZERO;
        BigInteger sumOfSquares = BigInteger.ZERO;
        for (int i = 0; i < n; i++) {
            int value = base + (i % 7);
            welford.add(value, value);
            naive.add(value, value);
            sum = sum.add(BigInteger.valueOf(value));
            sumOfSquares = sumOfSquares.add(BigInteger.valueOf((long) value * value));
        }

        // Exact, in integers: n * sumOfSquares - sum^2, over n.
        BigInteger scaled = sumOfSquares.multiply(BigInteger.valueOf(n)).subtract(sum.multiply(sum));
        double exactSumSqDev = new java.math.BigDecimal(scaled)
                .divide(java.math.BigDecimal.valueOf(n), java.math.MathContext.DECIMAL128)
                .doubleValue();

        double welfordError = relativeError(welford.sumSqDevA(), exactSumSqDev);
        double naiveError = relativeError(naive.varA(), exactSumSqDev);

        System.out.println("[defect 4] 16-bit, n=" + n + ", sumA2~" + naive.sumA2
                + ": exact=" + exactSumSqDev
                + " welford rel err " + welfordError
                + ", naive rel err " + naiveError
                + " (" + digitsLost(naiveError) + " significant digits lost)");

        assertTrue("Welford must stay accurate at 50M voxels, relative error was "
                + welfordError, welfordError <= 1.0e-9);
        assertTrue("the naive form must be measurably worse, welford " + welfordError
                + " vs naive " + naiveError, naiveError > welfordError * 100.0);
    }

    /**
     * Defect 10. The rename must be exactly a rename: same Pearson, to the bit.
     *
     * <p>The old names claimed a variance and a covariance and returned undivided
     * sums of squared deviations. Pearson and the OLS slope were still right
     * because the missing {@code n} cancels between numerator and denominator,
     * which is precisely why nothing ever caught it.
     */
    @Test
    public void renamingTheSumsLeavesPearsonBitIdentical() {
        WelfordAccumulator acc = fixture();

        // The claim defect 10 actually makes is that behaviour did not change.
        // Checked against the accumulator that still carries the OLD names, on a
        // well-conditioned fixture where the naive form is accurate — otherwise
        // this would be measuring defect 4 instead. Comparing the renamed
        // accessors against each other could not fail and would prove nothing.
        NaiveAccumulator underOldNames = new NaiveAccumulator();
        java.util.Random random = new java.util.Random(4242L);
        for (int i = 0; i < 5000; i++) {
            double a = 1000.0 + 250.0 * random.nextDouble();
            double b = 0.4 * a + 60.0 * random.nextDouble();
            underOldNames.add(a, b);
        }
        assertEquals("varA() renamed to sumSqDevA() must return the same quantity",
                underOldNames.varA(), acc.sumSqDevA(), Math.abs(acc.sumSqDevA()) * 1.0e-10);
        assertEquals("covariance() renamed to sumCoDev() must return the same quantity",
                underOldNames.covariance(), acc.sumCoDev(),
                Math.abs(acc.sumCoDev()) * 1.0e-10);
        assertEquals("and Pearson must be unchanged by the rename",
                underOldNames.pearson(), acc.pearson(), 1.0e-12);

        double throughAccessor = acc.pearson();
        double fromTheSums = acc.sumCoDev()
                / Math.sqrt(acc.sumSqDevA() * acc.sumSqDevB());
        assertEquals("Pearson from the renamed sums must be identical, not merely close",
                throughAccessor, fromTheSums, 0.0);

        // The same identity through the properly divided quantities, which is what
        // a reader of the old names would have believed they were using.
        long n = acc.count();
        double fromVariances = acc.covariance()
                / Math.sqrt(acc.varianceA() * acc.varianceB());
        assertEquals(throughAccessor, fromVariances, 1.0e-12);
        assertEquals("the divisors are what the old names hid",
                acc.sumSqDevA() / (n - 1), acc.varianceA(), 1.0e-12);
        assertTrue("the two quantities differ by a factor of n-1, which is exactly the"
                        + " error a contributor reading the old varA() would have made",
                acc.sumSqDevA() > acc.varianceA() * (n - 2));

        assertEquals("the OLS slope the Costes search walks is also unchanged",
                acc.sumCoDev() / acc.sumSqDevA(), acc.olsSlope(), 0.0);
    }

    @Test
    public void knownFixturesGiveTheKnownAnswers() {
        WelfordAccumulator perfect = new WelfordAccumulator();
        WelfordAccumulator inverse = new WelfordAccumulator();
        for (int i = 0; i < 100; i++) {
            perfect.add(i, 3.0 * i + 7.0);
            inverse.add(i, -2.0 * i + 500.0);
        }
        assertEquals(1.0, perfect.pearson(), 1.0e-12);
        assertEquals(-1.0, inverse.pearson(), 1.0e-12);
        assertEquals(3.0, perfect.olsSlope(), 1.0e-12);

        WelfordAccumulator flat = new WelfordAccumulator();
        for (int i = 0; i < 100; i++) {
            flat.add(5.0, i);
        }
        assertTrue("a channel with no variation has no correlation, not a zero one",
                Double.isNaN(flat.pearson()));

        WelfordAccumulator single = new WelfordAccumulator();
        single.add(1.0, 2.0);
        assertTrue(Double.isNaN(single.pearson()));
        assertEquals(1L, single.count());
    }

    private static WelfordAccumulator fixture() {
        WelfordAccumulator acc = new WelfordAccumulator();
        java.util.Random random = new java.util.Random(4242L);
        for (int i = 0; i < 5000; i++) {
            double a = 1000.0 + 250.0 * random.nextDouble();
            double b = 0.4 * a + 60.0 * random.nextDouble();
            acc.add(a, b);
        }
        return acc;
    }

    private static double relativeError(double actual, double exact) {
        if (Double.isNaN(actual)) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.abs(actual - exact) / Math.abs(exact);
    }

    /**
     * A double carries about 16 significant decimal digits, so a relative error of
     * 1e-16 has lost none and 1e-10 has lost six.
     */
    private static String digitsLost(double relativeError) {
        if (relativeError <= 0.0) {
            return "0.0";
        }
        if (Double.isInfinite(relativeError) || Double.isNaN(relativeError)) {
            return "all";
        }
        double lost = 16.0 + Math.log10(relativeError);
        if (lost < 0.0) {
            lost = 0.0;
        }
        if (lost > 16.0) {
            lost = 16.0;
        }
        return String.format("%.1f", lost);
    }
}
