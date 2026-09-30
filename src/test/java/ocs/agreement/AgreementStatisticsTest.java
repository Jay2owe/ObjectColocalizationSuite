package ocs.agreement;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The four statistics, against values worked out by hand.
 *
 * <p>Every expectation here was computed on paper before the code ran. A test
 * that captured what the implementation happened to return would agree with it
 * by construction and prove nothing — the same reason a golden re-captured after
 * a change is not evidence.
 */
public class AgreementStatisticsTest {

    // ---------- Cohen's kappa ----------

    @Test
    public void kappaOnAWorkedExample() {
        // 20 items. a=8 both yes, b=2 A only, c=3 B only, d=7 both no.
        // po = 15/20 = 0.75
        // A yes rate = 10/20 = 0.5, B yes rate = 11/20 = 0.55
        // pe = 0.5*0.55 + 0.5*0.45 = 0.275 + 0.225 = 0.5
        // kappa = (0.75 - 0.5) / 0.5 = 0.5
        boolean[] a = new boolean[20];
        boolean[] b = new boolean[20];
        for (int i = 0; i < 8; i++) { a[i] = true; b[i] = true; }
        for (int i = 8; i < 10; i++) { a[i] = true; }
        for (int i = 10; i < 13; i++) { b[i] = true; }

        CohensKappa.Result result = CohensKappa.of(a, b);
        assertEquals(20, result.n());
        assertEquals(0.75, result.rawAgreement(), 1e-12);
        assertEquals(0.5, result.kappa(), 1e-12);
        assertEquals(0.5, result.prevalenceA(), 1e-12);
        assertEquals(0.55, result.prevalenceB(), 1e-12);
    }

    @Test
    public void highRawAgreementOnSparseDataStillGivesALowKappa() {
        // The case the contract insists prevalence be shown for: 100 objects,
        // both methods call 3 coincident and agree on 2 of them. Raw agreement
        // is 97%, which reads as near-perfect. Kappa is far lower, because two
        // methods calling almost everything "no" would agree 94% of the time by
        // doing nothing. Neither number alone tells the reader that.
        boolean[] a = new boolean[100];
        boolean[] b = new boolean[100];
        a[0] = true; a[1] = true; a[2] = true;
        b[0] = true; b[1] = true; b[3] = true;

        CohensKappa.Result result = CohensKappa.of(a, b);
        assertEquals(0.98, result.rawAgreement(), 1e-12);
        assertTrue("kappa must be well below raw agreement, got " + result.kappa(),
                result.kappa() < 0.7);
        assertEquals(0.03, result.prevalenceA(), 1e-12);
        assertEquals(0.03, result.prevalenceB(), 1e-12);
    }

    @Test
    public void kappaIsNotANumberWhenBothCalledEverythingTheSameWay() {
        // Perfect agreement and zero information. Reporting 1.0 would claim the
        // methods corroborate each other; they have simply both said "no".
        boolean[] none = new boolean[10];
        CohensKappa.Result result = CohensKappa.of(none, none.clone());
        assertEquals(1.0, result.rawAgreement(), 1e-12);
        assertTrue("kappa is undefined here", Double.isNaN(result.kappa()));
        assertEquals(0.0, result.prevalenceA(), 1e-12);
    }

    @Test
    public void perfectDisagreementIsNegative() {
        boolean[] a = {true, true, false, false};
        boolean[] b = {false, false, true, true};
        assertEquals(-1.0, CohensKappa.of(a, b).kappa(), 1e-12);
    }

    @Test(expected = IllegalArgumentException.class)
    public void unpairedClassificationsAreRejected() {
        // Comparing object 7's flag against object 8's is worse than not
        // comparing: every statistic still comes out looking plausible.
        CohensKappa.of(new boolean[3], new boolean[4]);
    }

    // ---------- categorical kappa, for tier V ----------

    @Test
    public void categoricalKappaSeparatesEnrichedFromDepleted() {
        // Two methods that both said "significant" on every run, one calling it
        // enrichment and the other avoidance. Collapsing to significant/not
        // would score this as perfect agreement; it is the strongest possible
        // disagreement.
        int[] enriched = {0, 0, 0, 0};
        int[] depleted = {1, 1, 1, 1};
        CohensKappa.CategoricalResult result =
                CohensKappa.ofCategories(enriched, depleted);
        assertEquals(0.0, result.rawAgreement(), 1e-12);
        assertEquals(4, result.n());
    }

    @Test
    public void categoricalKappaMatchesTheBinaryFormOnTwoCategories() {
        int[] a = {0, 0, 1, 1, 0, 1};
        int[] b = {0, 1, 1, 1, 0, 0};
        boolean[] ba = {false, false, true, true, false, true};
        boolean[] bb = {false, true, true, true, false, false};
        assertEquals(CohensKappa.of(ba, bb).kappa(),
                CohensKappa.ofCategories(a, b).kappa(), 1e-12);
    }

    // ---------- Spearman ----------

    @Test
    public void spearmanIsOneForAnyIncreasingRelationHoweverNonlinear() {
        // The reason tier 2 exists. These two would have a Pearson well below 1
        // and a Lin's CCC near zero, and they order the objects identically —
        // which is the only question two incommensurable methods can both answer.
        double[] x = {1, 2, 3, 4, 5};
        double[] y = {1, 8, 27, 64, 125};
        assertEquals(1.0, SpearmanRho.of(x, y), 1e-12);
    }

    @Test
    public void spearmanIsMinusOneForAReversedOrder() {
        double[] x = {1, 2, 3, 4};
        double[] y = {4, 3, 2, 1};
        assertEquals(-1.0, SpearmanRho.of(x, y), 1e-12);
    }

    @Test
    public void tiesTakeTheirMidrankRatherThanTheirArrayPosition() {
        // Ranking ties by position would invent an ordering out of storage
        // order, and two methods would then appear to agree because their
        // objects happened to be enumerated the same way.
        double[] ranks = SpearmanRho.midranks(new double[] {5.0, 5.0, 1.0, 9.0});
        // 1.0 is rank 1; the two 5s occupy ranks 2 and 3 so share 2.5; 9 is 4.
        assertEquals(2.5, ranks[0], 1e-12);
        assertEquals(2.5, ranks[1], 1e-12);
        assertEquals(1.0, ranks[2], 1e-12);
        assertEquals(4.0, ranks[3], 1e-12);
    }

    @Test
    public void spearmanOnAWorkedExampleWithNoTies() {
        // d = 0, 1, -1, 0 over n=4  ->  sum d^2 = 2
        // rho = 1 - 6*2 / (4*15) = 1 - 12/60 = 0.8
        double[] x = {1, 2, 3, 4};
        double[] y = {1, 3, 2, 4};
        assertEquals(0.8, SpearmanRho.of(x, y), 1e-12);
    }

    @Test
    public void aConstantVariableHasNoOrderToCorrelate() {
        // NaN rather than 0. Zero would read as "these methods are unrelated";
        // the truth is that one of them said the same thing about every object.
        double[] constant = {3.0, 3.0, 3.0, 3.0};
        double[] varying = {1.0, 2.0, 3.0, 4.0};
        assertTrue(Double.isNaN(SpearmanRho.of(constant, varying)));
    }

    // ---------- Lin's CCC and Bland-Altman ----------

    @Test
    public void identicalMeasurementsConcordPerfectlyWithNoBias() {
        double[] values = {1.0, 2.0, 3.0, 4.0, 5.0};
        DirectValueAgreement.Result result =
                DirectValueAgreement.of(values, values.clone());
        assertEquals(1.0, result.concordance(), 1e-12);
        assertEquals(0.0, result.bias(), 1e-12);
        assertEquals(0.0, result.lowerLimit(), 1e-12);
        assertEquals(0.0, result.upperLimit(), 1e-12);
    }

    @Test
    public void aConstantOffsetIsCaughtAsBiasNotAsPerfectAgreement() {
        // Perfectly correlated, systematically 10 apart. Pearson would report
        // 1.0 and hide it entirely; that is exactly why tier 3 is CCC and
        // Bland-Altman rather than a correlation.
        double[] a = {1.0, 2.0, 3.0, 4.0, 5.0};
        double[] b = {11.0, 12.0, 13.0, 14.0, 15.0};
        DirectValueAgreement.Result result = DirectValueAgreement.of(a, b);

        assertEquals(-10.0, result.bias(), 1e-12);
        assertTrue("a 10-unit offset must not concord, got " + result.concordance(),
                result.concordance() < 0.1);
        // Every difference is exactly -10, so the limits collapse onto the bias.
        assertEquals(-10.0, result.lowerLimit(), 1e-12);
        assertEquals(-10.0, result.upperLimit(), 1e-12);
    }

    @Test
    public void concordanceOnAWorkedExample() {
        // a = {1,2,3,4}: mean 2.5, population variance 1.25
        // b = {1,3,3,5}: mean 3.0, population variance 2.0
        // covariance = (1.5*2 + 0.5*0 + 0.5*0 + 1.5*2)/4 = 6/4 = 1.5
        // ccc = 2*1.5 / (1.25 + 2.0 + 0.25) = 3.0 / 3.5
        double[] a = {1.0, 2.0, 3.0, 4.0};
        double[] b = {1.0, 3.0, 3.0, 5.0};
        assertEquals(3.0 / 3.5, DirectValueAgreement.of(a, b).concordance(), 1e-12);
    }

    @Test
    public void limitsOfAgreementBracketTheDifferences() {
        double[] a = {10.0, 12.0, 14.0, 16.0};
        double[] b = {11.0, 11.0, 15.0, 15.0};
        // differences: -1, 1, -1, 1 -> bias 0, sample SD = sqrt(4/3)
        DirectValueAgreement.Result result = DirectValueAgreement.of(a, b);
        double sd = Math.sqrt(4.0 / 3.0);
        assertEquals(0.0, result.bias(), 1e-12);
        assertEquals(-DirectValueAgreement.LIMIT_MULTIPLIER * sd,
                result.lowerLimit(), 1e-12);
        assertEquals(DirectValueAgreement.LIMIT_MULTIPLIER * sd,
                result.upperLimit(), 1e-12);
    }
}
