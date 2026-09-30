package ocs.agreement;

/**
 * Which comparison produced a number, carried with the number itself.
 *
 * <p>Not decoration. A tier-1 kappa and a tier-V kappa are both kappas, both on
 * [-1, 1], and both read as "how much do these two methods agree" — but one is
 * computed over objects within a single image and the other over whole-run
 * verdicts across a batch. Reporting them in the same column without saying
 * which is which invites a reader to compare them, and they are not comparable.
 *
 * <p>So the tier is part of a cell's identity rather than a property of it:
 * there is one cell per tier, and no way to read a statistic without having read
 * the tier that produced it.
 */
public enum Tier {

    /**
     * Raw agreement, Cohen's kappa and both prevalences on the coincident flag.
     * Valid between any two engines, because a boolean is always commensurable,
     * and primary because the flag is what users act on.
     */
    OBJECT_FLAG(1, "Coincidence agreement"),

    /**
     * Spearman's rho on the ranks of the per-object value. Valid between any two
     * engines, because rank order is scale-free.
     */
    OBJECT_RANK(2, "Rank correlation"),

    /**
     * Lin's concordance correlation coefficient and Bland–Altman limits, on the
     * per-object value directly. Computed only where both columns declare the
     * same {@code ScaleKind} and that kind permits it.
     */
    OBJECT_VALUE(3, "Direct-value agreement"),

    /**
     * Cohen's kappa over one verdict per engine per direction, gathered across a
     * batch. The only route open to an engine that produces no per-object score
     * at all — the four spatial engines answer with a curve over radii, so the
     * three object tiers see <i>n</i> = 0 for them against every method.
     */
    VERDICT(5, "Verdict agreement");

    private final int number;
    private final String displayName;

    Tier(int number, String displayName) {
        this.number = number;
        this.displayName = displayName;
    }

    /** 1, 2, 3 for the object tiers; 5 for verdict, which the docs call "V". */
    public int number() {
        return number;
    }

    public String displayName() {
        return displayName;
    }

    /** {@code "1"}, {@code "2"}, {@code "3"}, {@code "V"}. */
    public String label() {
        return this == VERDICT ? "V" : Integer.toString(number);
    }
}
