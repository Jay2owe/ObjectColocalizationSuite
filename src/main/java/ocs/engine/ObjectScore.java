package ocs.engine;

/**
 * One object's result from one engine, in the one shape every engine shares.
 *
 * <p>This is the load-bearing type of the whole plugin. Every engine, whatever
 * else it produces, must produce a list of these per direction. Three generic
 * layers read them and nothing else:
 *
 * <ul>
 *   <li>the null-model layer permutes {@link #value()} against a randomized
 *       object arrangement to get an expected value, an enrichment ratio and a
 *       permutation <i>p</i>;
 *   <li>the concordance matrix correlates {@link #value()} across methods to
 *       report where methods agree;
 *   <li>the clustering layer treats {@link #value()} as one feature column of an
 *       object's colocalization profile.
 * </ul>
 *
 * <p>None of those three layers knows what any engine does, which is what makes
 * "add method 24" a one-file change. Engine-specific outputs — occupied voxel
 * counts, partner lists, containment taxonomy — ride alongside in the engine's
 * own columns and are never read generically.
 */
public final class ObjectScore {

    /** Sentinel for {@link #partnerLabel()} when an engine found no partner. */
    public static final int NO_PARTNER = 0;

    private final int sourceLabel;
    private final int partnerLabel;
    private final double value;
    private final boolean coincident;

    /**
     * @param sourceLabel  label of the object in the source channel; must be positive
     * @param partnerLabel label of its strongest partner in the target channel, or
     *                     {@link #NO_PARTNER}
     * @param value        the engine's primary scalar for this object. May be
     *                     {@link Double#NaN} where the measure is undefined for
     *                     this object — an empty object, a zero denominator —
     *                     and every generic layer must skip NaN rather than
     *                     propagate it
     * @param coincident   the engine's yes/no verdict for this object, after its
     *                     own threshold has been applied
     */
    public ObjectScore(int sourceLabel, int partnerLabel, double value, boolean coincident) {
        if (sourceLabel <= 0) {
            throw new IllegalArgumentException(
                    "source label must be positive, was " + sourceLabel);
        }
        if (partnerLabel < 0) {
            throw new IllegalArgumentException(
                    "partner label must not be negative, was " + partnerLabel);
        }
        this.sourceLabel = sourceLabel;
        this.partnerLabel = partnerLabel;
        this.value = value;
        this.coincident = coincident;
    }

    public int sourceLabel() {
        return sourceLabel;
    }

    public int partnerLabel() {
        return partnerLabel;
    }

    public boolean hasPartner() {
        return partnerLabel != NO_PARTNER;
    }

    /**
     * The engine's primary scalar. Units and meaning are declared by whichever
     * {@link ColumnSpec} the engine marked primary.
     */
    public double value() {
        return value;
    }

    public boolean isCoincident() {
        return coincident;
    }

    @Override
    public String toString() {
        return "ObjectScore[" + sourceLabel + " -> " + partnerLabel
                + ", value=" + value + ", coincident=" + coincident + "]";
    }
}
