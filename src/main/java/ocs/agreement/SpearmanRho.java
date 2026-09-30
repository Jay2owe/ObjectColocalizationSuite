package ocs.agreement;

import java.util.Arrays;

/**
 * Rank correlation — do two methods put the objects in the same order?
 *
 * <p>Tier 2 exists because tier 3 is almost never allowed. Volume overlap is a
 * percentage and per-object Pearson is a correlation; their numbers cannot be
 * compared directly. But "which objects does each method think are the most
 * colocalized" is a question both can answer on the same footing, because rank
 * order does not care what units you are in.
 *
 * <p><b>Ties get midranks.</b> Several engines emit values that tie constantly —
 * a binary coincidence flag is all ties, containment percentages saturate at
 * 100. Assigning tied values their average rank is what keeps rho bounded by
 * ±1 and unbiased; ranking ties by array position instead would manufacture an
 * ordering out of the input's storage order, and two methods would then appear
 * to agree because their objects happened to be enumerated the same way.
 */
public final class SpearmanRho {

    private SpearmanRho() {
    }

    /**
     * @return rho on [-1, 1], or NaN when it is undefined: fewer than two pairs,
     *         or one of the two variables constant. A constant variable has no
     *         order to correlate, and returning 0 there would read as "these
     *         methods are unrelated" when the truth is "one of them said the
     *         same thing about every object".
     */
    public static double of(double[] x, double[] y) {
        if (x == null || y == null) {
            throw new IllegalArgumentException("both value arrays are required");
        }
        if (x.length != y.length) {
            throw new IllegalArgumentException("values must be paired object for "
                    + "object; got " + x.length + " and " + y.length);
        }
        int n = x.length;
        if (n < 2) {
            return Double.NaN;
        }
        double[] rankX = midranks(x);
        double[] rankY = midranks(y);
        return pearson(rankX, rankY);
    }

    /** Average rank within each group of equal values; 1-based. */
    static double[] midranks(double[] values) {
        int n = values.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = Integer.valueOf(i);
        }
        final double[] source = values;
        Arrays.sort(order, new java.util.Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Double.compare(source[a.intValue()], source[b.intValue()]);
            }
        });

        double[] ranks = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n
                    && source[order[j + 1].intValue()] == source[order[i].intValue()]) {
                j++;
            }
            // Positions i..j all hold the same value, so they share the average
            // of the ranks they collectively occupy.
            double shared = ((i + 1) + (j + 1)) / 2.0;
            for (int k = i; k <= j; k++) {
                ranks[order[k].intValue()] = shared;
            }
            i = j + 1;
        }
        return ranks;
    }

    private static double pearson(double[] x, double[] y) {
        int n = x.length;
        double meanX = 0.0;
        double meanY = 0.0;
        for (int i = 0; i < n; i++) {
            meanX += x[i];
            meanY += y[i];
        }
        meanX /= n;
        meanY /= n;

        double covariance = 0.0;
        double varianceX = 0.0;
        double varianceY = 0.0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - meanX;
            double dy = y[i] - meanY;
            covariance += dx * dy;
            varianceX += dx * dx;
            varianceY += dy * dy;
        }
        if (varianceX == 0.0 || varianceY == 0.0) {
            return Double.NaN;
        }
        return covariance / Math.sqrt(varianceX * varianceY);
    }
}
