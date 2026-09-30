package ocs.engine;

/**
 * An engine whose coincident/not call depends on a cut-off somebody chose.
 *
 * <p>"Objects overlapping by more than 30% are colocalized" is a decision, not a
 * measurement. Nothing in the data says 30. If moving it to 25 or 35 leaves the
 * answer alone, the finding is robust; if it reclassifies a fifth of the
 * objects, the finding is really a statement about the cut-off. The threshold
 * sweep exists to tell those apart, and it needs to be able to rebuild an engine
 * at a different setting without knowing what the setting means.
 *
 * <p>Optional on purpose. Centroid coincidence is purely geometric — a centroid
 * is inside a partner or it is not — so it implements none of this and reports
 * {@code n/a} for flip fraction. That is not a gap in the sweep; it is a point
 * in the method's favour, and the report says so.
 *
 * <p>Implementations must be immutable: {@link #withThreshold} returns a new
 * engine rather than mutating this one. The sweep holds several settings of the
 * same engine at once, and the null-model layer may be running the original on
 * another thread.
 */
public interface ThresholdBearing {

    /** What the threshold means, for the axis label: {@code "Overlap"}. */
    String thresholdName();

    /** Unit of the threshold: {@code "%"}, {@code "µm"}, or empty. */
    String thresholdUnit();

    /** The setting this instance is using. */
    double threshold();

    /**
     * The same engine at a different setting.
     *
     * @throws IllegalArgumentException if the value is outside what the measure
     *         admits — a negative overlap percentage is not a wide sweep, it is
     *         a bug in whatever built the ladder
     */
    ColocEngine withThreshold(double threshold);

    /**
     * The settings to sweep, ascending, across the measure's whole span.
     *
     * <p>Per-engine because the scales are unrelated: an overlap percentage
     * sweeps 0–100, a Jaccard index 0–1, a distance tolerance a span of
     * micrometres. A single shared ladder would be meaningless on most of them.
     *
     * <p><b>Not what the sweep uses by default.</b> Sweeping the whole span
     * answers "would the verdict change at <i>any</i> cut-off", which on real
     * data is trivially yes — see {@code ThresholdSweep}. It is kept because the
     * full curve is the right thing to plot, and because a caller who genuinely
     * wants the whole range should be able to ask for it.
     */
    double[] defaultLadder();

    /**
     * The smallest and largest settings this measure admits, as
     * {@code {low, high}}.
     *
     * <p>Exists so a sweep width can be expressed once, as a fraction, and mean
     * the same thing on every method. The six threshold-bearing engines are on
     * four different scales — percent, fraction, correlation and micrometres —
     * so "sweep ±10" cannot be written once and be right anywhere; "sweep ±20%
     * of what this measure can be" can.
     *
     * <p>For a measure with no natural upper bound the high end is the widest
     * value worth reporting rather than infinity, since the point is to size a
     * neighbourhood rather than to state a mathematical domain.
     */
    double[] thresholdRange();
}
