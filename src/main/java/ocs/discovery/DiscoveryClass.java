package ocs.discovery;

/**
 * What Discovery concluded about one method on one dataset.
 *
 * <p>Five classes, and deliberately <b>no score and no ordering</b>. A weighted
 * combination of null-clearance, agreement and stability was considered and
 * rejected for three reasons, all of which still hold:
 *
 * <ul>
 *   <li><b>Agreeing with the majority is not being right.</b> If four
 *       overlap-based methods share a bias and one distance-based method is
 *       correct, a consensus score punishes the correct one for being
 *       outnumbered.
 *   <li><b>The methods are not independent.</b> Jaccard, Dice and volume-overlap
 *       fraction are near-restatements of one another, so "agrees with three
 *       others" can mean "agrees with one idea counted three times".
 *   <li><b>Any weighting would be arbitrary.</b> There is no empirical basis for
 *       the weights, and the resulting number would be cited as though there
 *       were.
 * </ul>
 *
 * <p>So each method lands in exactly one class with the evidence that put it
 * there, and the user does the ranking — which is the part that needs to know
 * what the experiment was.
 */
public enum DiscoveryClass {

    /**
     * Cleared the null model, stable across its threshold range, and computed
     * on enough objects. Safe to report.
     */
    USABLE("Usable",
            "cleared the null model, stable across its threshold range, enough objects"),

    /**
     * Indistinguishable from chance <i>on this data</i>.
     *
     * <p>Not a broken method and not a defect. A method that finds nothing on a
     * dataset where there is nothing to find has worked correctly, and the
     * wording says so — "uninformative here", never "failed".
     */
    UNINFORMATIVE_HERE("Uninformative here",
            "did not clear the null model on this dataset"),

    /**
     * Cleared the null, but its answer moves when the threshold does.
     *
     * <p>The finding is partly a statement about where the cut-off was drawn.
     * Worth reporting with the flip fraction beside it, not worth reporting
     * alone.
     */
    FRAGILE("Fragile",
            "cleared the null model but its classification depends on the threshold"),

    /**
     * Cleared the null, stable, and agrees with nothing in any other family.
     *
     * <p><b>Flagged, never demoted.</b> Divergence has two possible causes and
     * this class does not distinguish them: the method may be wrong, or it may
     * be the only one here capturing the effect. A distance-based method
     * <i>should</i> disagree with overlap-based methods on puncta that are close
     * but not touching — that disagreement is the finding, not a fault.
     */
    DIVERGENT("Divergent",
            "cleared the null model and is stable, but agrees with no method in "
                    + "another family — which may mean it is the only one seeing the effect"),

    /**
     * Could not be assessed: required inputs absent, or too few objects.
     * Greyed out with the reason, never silently omitted.
     */
    NOT_APPLICABLE("Not applicable",
            "required inputs absent, or too few objects to judge");

    private final String displayName;
    private final String meaning;

    DiscoveryClass(String displayName, String meaning) {
        this.displayName = displayName;
        this.meaning = meaning;
    }

    public String displayName() {
        return displayName;
    }

    /** One sentence a user can read without the manual. */
    public String meaning() {
        return meaning;
    }
}
