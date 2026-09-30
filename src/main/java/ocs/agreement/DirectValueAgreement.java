package ocs.agreement;

/**
 * Tier 3: do two methods produce the <i>same number</i>, not merely the same
 * ordering?
 *
 * <p>Lin's concordance correlation coefficient and Bland–Altman limits of
 * agreement answer that together, and they are computed together because
 * separately each is misleading. Lin's CCC gives one number for "how close to
 * the line of equality"; Bland–Altman says <i>how</i> they differ — a constant
 * offset, or a spread that grows with the measurement. A CCC of 0.9 with a
 * systematic bias of 20 percentage points is a very different finding from a CCC
 * of 0.9 with no bias and wide scatter, and only the pair distinguishes them.
 *
 * <p><b>This tier is almost always refused, and that is the design working.</b>
 * Both statistics treat a location or scale shift as disagreement, so they are
 * only meaningful between measurements of the same quantity on the same scale.
 * Running them across "volume overlap % versus per-object Pearson r" produces a
 * confident number that measures nothing — exactly the authoritative-looking
 * nonsense this plugin exists to prevent. {@code ScaleKind} is the gate, and it
 * refuses far more pairs than it admits.
 */
public final class DirectValueAgreement {

    public static final class Result {
        private final int n;
        private final double concordance;
        private final double bias;
        private final double lowerLimit;
        private final double upperLimit;

        private Result(int n, double concordance, double bias,
                double lowerLimit, double upperLimit) {
            this.n = n;
            this.concordance = concordance;
            this.bias = bias;
            this.lowerLimit = lowerLimit;
            this.upperLimit = upperLimit;
        }

        public int n() {
            return n;
        }

        /** Lin's CCC: 1 is the line of equality, 0 no concordance. */
        public double concordance() {
            return concordance;
        }

        /** Mean of (A − B). A systematic offset between the two methods. */
        public double bias() {
            return bias;
        }

        /** bias − 1.96 SD of the differences. */
        public double lowerLimit() {
            return lowerLimit;
        }

        /** bias + 1.96 SD of the differences. */
        public double upperLimit() {
            return upperLimit;
        }
    }

    /** The conventional 95% coverage multiplier for limits of agreement. */
    public static final double LIMIT_MULTIPLIER = 1.96;

    private DirectValueAgreement() {
    }

    /**
     * @return NaN throughout when fewer than two pairs, or when both methods
     *         reported a constant. Two constants have no concordance to measure;
     *         a CCC of 1 there would claim perfect agreement between two methods
     *         that each said the same thing about every object.
     */
    public static Result of(double[] a, double[] b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("both value arrays are required");
        }
        if (a.length != b.length) {
            throw new IllegalArgumentException("values must be paired object for "
                    + "object; got " + a.length + " and " + b.length);
        }
        int n = a.length;
        if (n < 2) {
            return new Result(n, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }

        double meanA = 0.0;
        double meanB = 0.0;
        for (int i = 0; i < n; i++) {
            meanA += a[i];
            meanB += b[i];
        }
        meanA /= n;
        meanB /= n;

        // Population moments: Lin's CCC is defined on them, and using the sample
        // form here would leave the coefficient slightly biased at small n —
        // which is precisely the n this tier most often runs at.
        double varianceA = 0.0;
        double varianceB = 0.0;
        double covariance = 0.0;
        for (int i = 0; i < n; i++) {
            double da = a[i] - meanA;
            double db = b[i] - meanB;
            varianceA += da * da;
            varianceB += db * db;
            covariance += da * db;
        }
        varianceA /= n;
        varianceB /= n;
        covariance /= n;

        double shift = meanA - meanB;
        double denominator = varianceA + varianceB + shift * shift;
        double concordance = denominator == 0.0
                ? Double.NaN : (2.0 * covariance) / denominator;

        // Bland-Altman uses the sample SD, because the limits are a prediction
        // interval for a future difference rather than a description of these.
        double meanDifference = shift;
        double sumSquares = 0.0;
        for (int i = 0; i < n; i++) {
            double d = (a[i] - b[i]) - meanDifference;
            sumSquares += d * d;
        }
        double sd = Math.sqrt(sumSquares / (n - 1));

        return new Result(n, concordance, meanDifference,
                meanDifference - LIMIT_MULTIPLIER * sd,
                meanDifference + LIMIT_MULTIPLIER * sd);
    }
}
