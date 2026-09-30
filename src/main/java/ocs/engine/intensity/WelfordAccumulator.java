package ocs.engine.intensity;

/**
 * Online means, sums of squared deviations and co-deviation for a voxel pair
 * stream, by Welford's algorithm.
 *
 * <p>Replaces the naive one-pass accumulator inherited from FLASH, which held
 * {@code sumA2} and recovered the sum of squared deviations as
 * {@code sumA2 - (sumA * sumA) / count}. Both terms of that subtraction grow as
 * the square of the intensity while their difference grows only as the variance,
 * so at high background the two operands agree to more digits than a double
 * carries and the result is rounding noise. Sixteen-bit intensities over 50M
 * voxels put {@code sumA2} near 4.5e16, at which point the subtraction has
 * already lost several digits; a 32-bit float channel with a large offset loses
 * all of them and can return a negative "sum of squares". Nothing in the naive
 * form reports that it happened — Pearson simply comes back wrong.
 *
 * <p>Welford never forms the large intermediate. It carries the mean and updates
 * the deviation sums against it, so every quantity stays on the scale of the
 * variance rather than the scale of the intensity squared.
 *
 * <p>The method names are the second half of the fix. The inherited class called
 * these quantities {@code varA()} and {@code covariance()} while returning
 * undivided sums of squared deviations. Pearson and the OLS slope were still
 * correct because the missing divisors cancel, so the misnaming was invisible
 * until someone wanted an actual variance — at which point they would be wrong
 * by a factor of the voxel count with no symptom. {@link #sumSqDevA()} says what
 * it returns; {@link #varianceA()} is available for anyone who wanted the other
 * thing.
 *
 * <p>Not thread-safe. Each permutation of the Costes randomization owns one.
 */
public final class WelfordAccumulator {

    private long count;
    private double meanA;
    private double meanB;
    private double sumSqDevA;
    private double sumSqDevB;
    private double sumCoDev;

    /** Adds one paired sample. Order of the six updates is load-bearing. */
    public void add(double a, double b) {
        count++;
        // Deltas are taken against the previous means...
        double deltaA = a - meanA;
        double deltaB = b - meanB;
        meanA += deltaA / count;
        meanB += deltaB / count;
        // ...and closed against the updated ones. Mixing the two generations is
        // what makes the update exact rather than merely stable.
        sumSqDevA += deltaA * (a - meanA);
        sumSqDevB += deltaB * (b - meanB);
        sumCoDev += deltaA * (b - meanB);
    }

    public long count() {
        return count;
    }

    public double meanA() {
        return count == 0L ? Double.NaN : meanA;
    }

    public double meanB() {
        return count == 0L ? Double.NaN : meanB;
    }

    /** Sum of squared deviations from the mean for channel A — <i>not</i> a variance. */
    public double sumSqDevA() {
        return count < 2L ? Double.NaN : sumSqDevA;
    }

    /** Sum of squared deviations from the mean for channel B — <i>not</i> a variance. */
    public double sumSqDevB() {
        return count < 2L ? Double.NaN : sumSqDevB;
    }

    /** Sum of the products of paired deviations — <i>not</i> a covariance. */
    public double sumCoDev() {
        return count < 2L ? Double.NaN : sumCoDev;
    }

    /** Sample variance of channel A, divided by {@code n - 1}. */
    public double varianceA() {
        return count < 2L ? Double.NaN : sumSqDevA / (count - 1L);
    }

    /** Sample variance of channel B, divided by {@code n - 1}. */
    public double varianceB() {
        return count < 2L ? Double.NaN : sumSqDevB / (count - 1L);
    }

    /** Sample covariance, divided by {@code n - 1}. */
    public double covariance() {
        return count < 2L ? Double.NaN : sumCoDev / (count - 1L);
    }

    /**
     * Ordinary-least-squares slope of B regressed on A, which is what the Costes
     * threshold search walks down.
     */
    public double olsSlope() {
        if (count < 2L || sumSqDevA <= 0.0) {
            return Double.NaN;
        }
        return sumCoDev / sumSqDevA;
    }

    /**
     * Pearson's r, or NaN where it is undefined — fewer than two samples, or a
     * channel with no variation, for which "how strongly do these covary" has no
     * answer rather than the answer zero.
     */
    public double pearson() {
        if (count < 2L) {
            return Double.NaN;
        }
        if (sumSqDevA <= 0.0 || sumSqDevB <= 0.0
                || Double.isNaN(sumSqDevA) || Double.isNaN(sumSqDevB)) {
            return Double.NaN;
        }
        double r = sumCoDev / Math.sqrt(sumSqDevA * sumSqDevB);
        // Rounding can put an exact +/-1 a few ulps outside the valid interval.
        // Clamping only within that margin keeps a genuinely broken r visible.
        if (r > 1.0 && r < 1.0 + 1.0e-12) {
            return 1.0;
        }
        if (r < -1.0 && r > -1.0 - 1.0e-12) {
            return -1.0;
        }
        return r;
    }
}
