package ocs.agreement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tier V — agreement on conclusions rather than on values.
 *
 * <p>The route that lets the four spatial engines into the comparison at all.
 * They answer with a curve over radii and never produce a per-object number, so
 * the three object tiers see <i>n</i> = 0 for them against every other method
 * and would class the whole family "not applicable" on every dataset. What they
 * do produce is a conclusion — this pair of channels is more associated than
 * chance, or less, or not detectably either — and every other method produces
 * one too.
 *
 * <p><b>The unit is the run, not the object.</b> One verdict per engine per
 * direction per image, and kappa over those. So this tier needs a batch: with a
 * single image every pair of methods has <i>n</i> = 1 and kappa is undefined,
 * which is reported as such rather than as agreement. The rejected alternative
 * was inventing a per-object score for curve engines so the existing tiers would
 * populate — a number with no referent, added so a matrix would fill in.
 */
public final class VerdictAgreement {

    public static final String RAW_AGREEMENT = "Raw Agreement";
    public static final String KAPPA = "Cohen's Kappa";
    public static final String RUNS_COMPARED = "Runs Compared";

    /** Runs below which a verdict kappa is real but not to be leant on. */
    public static final int DEFAULT_MINIMUM_RUNS = 10;

    /** One image's verdicts, keyed by engine id. */
    public static final class Run {
        private final String name;
        private final Map<String, Verdict> verdicts;

        public Run(String name, Map<String, Verdict> verdicts) {
            if (verdicts == null) {
                throw new IllegalArgumentException("verdicts are required");
            }
            this.name = name;
            this.verdicts = Collections.unmodifiableMap(
                    new LinkedHashMap<String, Verdict>(verdicts));
        }

        public String name() {
            return name;
        }

        public Map<String, Verdict> verdicts() {
            return verdicts;
        }
    }

    private final int minimumRuns;

    private VerdictAgreement(int minimumRuns) {
        this.minimumRuns = minimumRuns;
    }

    public static VerdictAgreement withDefaults() {
        return new VerdictAgreement(DEFAULT_MINIMUM_RUNS);
    }

    public static VerdictAgreement withMinimumRuns(int minimumRuns) {
        if (minimumRuns < 0) {
            throw new IllegalArgumentException("minimum runs cannot be negative");
        }
        return new VerdictAgreement(minimumRuns);
    }

    /**
     * Every unordered pair of engines, over the runs where both concluded
     * something.
     *
     * <p>Complete-case like the object tiers: a run where one method was not
     * applicable is dropped from that pair's comparison and from no other.
     * Counting "not applicable" as a category instead would let two methods that
     * both failed on the same images score as agreeing.
     */
    public List<AgreementCell> across(List<Run> runs) {
        if (runs == null) {
            throw new IllegalArgumentException("runs are required");
        }
        Set<String> engineIds = new LinkedHashSet<String>();
        for (int i = 0; i < runs.size(); i++) {
            engineIds.addAll(runs.get(i).verdicts().keySet());
        }
        List<String> ids = new ArrayList<String>(engineIds);

        List<AgreementCell> cells = new ArrayList<AgreementCell>();
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                cells.add(compare(ids.get(i), ids.get(j), runs));
            }
        }
        return Collections.unmodifiableList(cells);
    }

    private AgreementCell compare(String engineA, String engineB, List<Run> runs) {
        List<Integer> categoriesA = new ArrayList<Integer>();
        List<Integer> categoriesB = new ArrayList<Integer>();
        for (int i = 0; i < runs.size(); i++) {
            Verdict a = runs.get(i).verdicts().get(engineA);
            Verdict b = runs.get(i).verdicts().get(engineB);
            if (a == null || b == null
                    || a == Verdict.NOT_APPLICABLE || b == Verdict.NOT_APPLICABLE) {
                continue;
            }
            categoriesA.add(Integer.valueOf(a.ordinal()));
            categoriesB.add(Integer.valueOf(b.ordinal()));
        }

        int n = categoriesA.size();
        if (n == 0) {
            return new AgreementCell(Tier.VERDICT, engineA, engineB, null, 0,
                    new LinkedHashMap<String, Double>(), true, false,
                    "no run produced a usable verdict from both methods");
        }
        if (n < 2) {
            return new AgreementCell(Tier.VERDICT, engineA, engineB, null, n,
                    new LinkedHashMap<String, Double>(), true, false,
                    "verdict agreement needs a batch; " + n
                            + " run gives no distribution to correct against chance");
        }

        int[] a = new int[n];
        int[] b = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = categoriesA.get(i).intValue();
            b[i] = categoriesB.get(i).intValue();
        }
        CohensKappa.CategoricalResult result = CohensKappa.ofCategories(a, b);

        Map<String, Double> statistics = new LinkedHashMap<String, Double>();
        statistics.put(RAW_AGREEMENT, Double.valueOf(result.rawAgreement()));
        statistics.put(KAPPA, Double.valueOf(result.kappa()));
        statistics.put(RUNS_COMPARED, Double.valueOf(n));
        return new AgreementCell(Tier.VERDICT, engineA, engineB, null, n,
                statistics, n < minimumRuns, false, null);
    }
}
