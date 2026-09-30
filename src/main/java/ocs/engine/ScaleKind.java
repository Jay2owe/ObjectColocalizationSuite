package ocs.engine;

/**
 * What kind of quantity a column holds, and therefore which agreement statistics
 * may legitimately be computed against another column.
 *
 * <p>This exists because the engines emit values on incommensurable scales.
 * Centroid coincidence is a boolean, volume overlap is a percentage, per-object
 * Pearson is a correlation on [-1, 1], distance tolerance is micrometres. Lin's
 * concordance correlation coefficient and Bland-Altman limits of agreement both
 * measure agreement between two measurements <i>of the same quantity on the same
 * scale</i> — they treat a location or scale shift as disagreement. Running
 * either across "volume overlap % versus Pearson r" produces a confident number
 * that measures nothing.
 *
 * <p>So the agreement layer works in three tiers:
 *
 * <ol>
 *   <li><b>Cohen's kappa and raw agreement</b> on the {@link ObjectScore#isCoincident()}
 *       flag — valid between <i>any</i> two engines, because a boolean is always
 *       commensurable. This is the primary statistic: it is what users act on.
 *   <li><b>Spearman's rho</b> on {@link ObjectScore#value()} — valid between any
 *       two engines, because rank order is scale-free.
 *   <li><b>Lin's CCC and Bland-Altman</b> on the value directly — computed
 *       <i>only</i> where both columns declare the same {@code ScaleKind}.
 * </ol>
 *
 * <p>Two engines sharing a scale kind is a claim that their values are directly
 * comparable numbers, not merely that they happen to occupy the same interval.
 * Jaccard and Dice are both {@link #FRACTION} and comparing them directly is
 * meaningful. A per-object Pearson and a Jaccard index would both fit in [0, 1]
 * for non-negative data and are not comparable at all.
 */
public enum ScaleKind {

    /** Boolean-valued, 0 or 1. */
    BINARY("binary"),

    /** A proportion on [0, 1] — Jaccard, Dice, Manders, overlap fraction. */
    FRACTION("fraction 0–1"),

    /** A percentage on [0, 100]. Kept distinct from {@link #FRACTION} so a
     *  mismatched pair is caught rather than silently compared 100× apart. */
    PERCENT("percent 0–100"),

    /** A correlation coefficient on [-1, 1] — Pearson, Spearman, Kendall. */
    CORRELATION("correlation −1–+1"),

    /** A physical distance in calibrated units. */
    DISTANCE("distance"),

    /** A physical volume or area in calibrated units. */
    VOLUME("volume"),

    /** A count. */
    COUNT("count"),

    /**
     * Anything with no bounded, shared interpretation — an enrichment ratio, an
     * intensity threshold in raw units. Never eligible for tier 3, even against
     * another {@code UNBOUNDED} column, because two unbounded quantities being
     * unbounded is not evidence that they are the same quantity.
     */
    UNBOUNDED("unbounded"),

    /**
     * The ordinate of a curve swept over an axis — Ripley's K at a radius, a
     * cross-G value, a pair-correlation value. Never eligible for tier 3, for
     * the same reason as {@link #UNBOUNDED} and one stronger: there is no single
     * value to compare at all, only a vector whose meaning depends on where on
     * the axis you stand.
     *
     * <p>Added because {@code UNBOUNDED} was doing this job and was <i>false</i>
     * about cross-G, whose values are an empirical distribution function on
     * [0, 1]. Declaring it {@link #FRACTION} to be truthful about the interval
     * would have been the worse error — it would claim a cross-G ordinate is
     * directly comparable with a Jaccard index. {@code CURVE} is inert like
     * {@code UNBOUNDED} and true as well.
     *
     * <p>A curve-valued engine is compared through the verdict route instead;
     * see {@code 02_CONTRACT.md} § Agreement and Discovery.
     */
    CURVE("curve ordinate");

    private final String displayName;

    ScaleKind(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * Whether direct-value agreement (Lin's CCC, Bland-Altman) may be computed
     * between columns of this kind and {@code other}.
     *
     * <p>{@link #UNBOUNDED} and {@link #CURVE} are never eligible, including
     * against themselves.
     */
    public boolean isComparableWith(ScaleKind other) {
        return this == other && this != UNBOUNDED && this != CURVE;
    }
}
