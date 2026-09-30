package ocs.agreement;

/**
 * Agreement between two yes/no classifications, corrected for chance.
 *
 * <p>Two methods that both call 95% of objects "not colocalized" agree 95% of
 * the time by doing nothing at all. Kappa removes that: it asks how much of the
 * agreement is more than you would get from two methods with those same overall
 * rates guessing independently.
 *
 * <p><b>Prevalence travels with it, and that is not optional.</b> Colocalization
 * data is usually unbalanced — at 5% coincident objects kappa collapses toward
 * zero even at 95% raw agreement, so kappa alone systematically makes
 * well-agreeing methods look like they disagree on exactly the sparse data most
 * experiments produce. A reader who can see raw agreement, kappa and both
 * prevalences together can tell a low kappa caused by prevalence from a low
 * kappa caused by disagreement. A reader given only kappa cannot.
 */
public final class CohensKappa {

    /** Raw agreement, kappa, and each rater's rate of calling "yes". */
    public static final class Result {
        private final int n;
        private final double rawAgreement;
        private final double kappa;
        private final double prevalenceA;
        private final double prevalenceB;

        private Result(int n, double rawAgreement, double kappa,
                double prevalenceA, double prevalenceB) {
            this.n = n;
            this.rawAgreement = rawAgreement;
            this.kappa = kappa;
            this.prevalenceA = prevalenceA;
            this.prevalenceB = prevalenceB;
        }

        public int n() {
            return n;
        }

        /** Proportion of items the two agreed on, chance included. */
        public double rawAgreement() {
            return rawAgreement;
        }

        /**
         * Chance-corrected agreement.
         *
         * <p>NaN where it is undefined rather than a number that looks like an
         * answer: when both raters called everything the same way, expected
         * agreement is 1 and the correction divides by zero. That case is
         * perfect agreement <i>and</i> no information, and the honest report of
         * it is the prevalences, both 0 or both 1, sitting beside a NaN.
         */
        public double kappa() {
            return kappa;
        }

        public double prevalenceA() {
            return prevalenceA;
        }

        public double prevalenceB() {
            return prevalenceB;
        }
    }

    /** Kappa over more than two categories, where prevalence is not one number. */
    public static final class CategoricalResult {
        private final int n;
        private final double rawAgreement;
        private final double kappa;

        private CategoricalResult(int n, double rawAgreement, double kappa) {
            this.n = n;
            this.rawAgreement = rawAgreement;
            this.kappa = kappa;
        }

        public int n() {
            return n;
        }

        public double rawAgreement() {
            return rawAgreement;
        }

        public double kappa() {
            return kappa;
        }
    }

    private CohensKappa() {
    }

    /**
     * Kappa over categorical labels — used by tier V, where a verdict is
     * enriched, depleted or neither rather than simply yes or no.
     *
     * <p>Collapsing those three to "significant / not" would score two methods
     * as agreeing when one found enrichment and the other found avoidance, which
     * is the strongest disagreement they can have.
     *
     * @param a category index per item; any non-negative integers
     */
    public static CategoricalResult ofCategories(int[] a, int[] b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("both classifications are required");
        }
        if (a.length != b.length) {
            throw new IllegalArgumentException("classifications must be paired item "
                    + "for item; got " + a.length + " and " + b.length);
        }
        int n = a.length;
        if (n == 0) {
            return new CategoricalResult(0, Double.NaN, Double.NaN);
        }
        int categories = 0;
        for (int i = 0; i < n; i++) {
            categories = Math.max(categories, Math.max(a[i], b[i]) + 1);
        }

        int agreed = 0;
        int[] countsA = new int[categories];
        int[] countsB = new int[categories];
        for (int i = 0; i < n; i++) {
            countsA[a[i]]++;
            countsB[b[i]]++;
            if (a[i] == b[i]) {
                agreed++;
            }
        }
        double observed = agreed / (double) n;
        double expected = 0.0;
        for (int c = 0; c < categories; c++) {
            expected += (countsA[c] / (double) n) * (countsB[c] / (double) n);
        }
        double kappa = expected >= 1.0
                ? Double.NaN : (observed - expected) / (1.0 - expected);
        return new CategoricalResult(n, observed, kappa);
    }

    /**
     * @throws IllegalArgumentException if the two arrays differ in length — that
     *         would mean the pairing broke upstream, and comparing object 7's
     *         flag against object 8's is worse than not comparing at all
     */
    public static Result of(boolean[] a, boolean[] b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("both classifications are required");
        }
        if (a.length != b.length) {
            throw new IllegalArgumentException("classifications must be paired object "
                    + "for object; got " + a.length + " and " + b.length);
        }
        int n = a.length;
        if (n == 0) {
            return new Result(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }

        int bothYes = 0;
        int aYesOnly = 0;
        int bYesOnly = 0;
        int bothNo = 0;
        for (int i = 0; i < n; i++) {
            if (a[i] && b[i]) {
                bothYes++;
            } else if (a[i]) {
                aYesOnly++;
            } else if (b[i]) {
                bYesOnly++;
            } else {
                bothNo++;
            }
        }

        double total = n;
        double observed = (bothYes + bothNo) / total;
        double aYesRate = (bothYes + aYesOnly) / total;
        double bYesRate = (bothYes + bYesOnly) / total;
        double expected = aYesRate * bYesRate + (1.0 - aYesRate) * (1.0 - bYesRate);

        double kappa;
        if (expected >= 1.0) {
            kappa = Double.NaN;
        } else {
            kappa = (observed - expected) / (1.0 - expected);
        }
        return new Result(n, observed, kappa, aYesRate, bYesRate);
    }
}
