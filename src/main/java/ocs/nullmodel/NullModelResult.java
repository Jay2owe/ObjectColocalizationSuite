package ocs.nullmodel;

import ocs.engine.DirectionKey;

/**
 * What one engine's answer looked like against chance, for one direction.
 *
 * <p>The question this exists to answer is the one a colocalization number
 * cannot answer by itself. "Sixty per cent of my mitochondria touch a lysosome"
 * is not a finding until you know what fraction would touch one if the
 * lysosomes were scattered at random — in a densely packed cell that figure can
 * be ninety per cent, and sixty is then evidence of <i>avoidance</i>.
 *
 * <p>Carries the whole permuted distribution, not just its summary. That is not
 * generosity: {@code 02_CONTRACT.md} § Determinism records three modules in this
 * family that shipped a parallel bug precisely because only aggregates were ever
 * asserted, and an aggregate is order-free by construction. A caller that can
 * see element <i>i</i> can prove element <i>i</i> came from draw <i>i</i>.
 */
public final class NullModelResult {

    /** Why a direction carries no null model. Never a silent empty result. */
    public enum Skip {
        /** Ran normally. */
        NONE("", ""),
        /**
         * The engine reports a curve, not per-object scores, and its own
         * simulation envelope already is a null model. Permuting it would run a
         * Monte Carlo inside a Monte Carlo — at the default 99 simulations and
         * 100 permutations, ten thousand pattern evaluations for a number the
         * engine already reported.
         */
        CARRIES_ITS_OWN_ENVELOPE("Carries its own envelope",
                "engine reports a simulation envelope; its global p is its null model"),
        /**
         * The engine's answer does not read the label images, so shuffling them
         * cannot change it. Whole-image Pearson is the case: every permutation
         * would return the observed value exactly, and the p computed from that
         * would be 1.0 on every dataset — a confident-looking number meaning
         * only that the wrong null was applied. Its real null is the Costes
         * block randomization the engine already runs internally.
         */
        INVARIANT_UNDER_PERMUTATION("Invariant under permutation",
                "engine does not read object labels; its Costes randomization is its null model"),
        /**
         * The engine produced no objects at all in this direction, so there is
         * nothing to shuffle and nothing to test.
         */
        TOO_FEW_OBJECTS("Too few objects",
                "a direction needs at least one object in each channel"),
        /**
         * No placement of an object fits inside the domain. Happens when the
         * region ROI is smaller than the objects in it.
         */
        NO_ROOM_IN_DOMAIN("No room in domain",
                "no position inside the region places this object wholly within it"),
        /**
         * The engine's statistic is read from an object's axis-aligned extent,
         * and the null model wraps at the frame edge.
         *
         * <p>A wrapping shuffle moves voxels without creating or destroying any,
         * so a statistic read from voxel membership is exact under it and a
         * statistic read from a centroid is displaced but bounded. An extent is
         * neither: an object that crosses the edge arrives in two pieces at
         * opposite sides of the frame, and its bounding box becomes the frame.
         * A frame-wide box overlaps every object there is, so every shuffled
         * field scores higher than it should and the real one — which never
         * wraps — looks depleted by comparison.
         *
         * <p>Measured rather than argued: on the validation set this produced a
         * confident finding of depletion on 51% of channel pairs that had
         * nothing in them, always in the same direction.
         */
        DISTORTED_BY_WRAP("Distorted by wrap",
                "this statistic is read from an object's bounding box, and a"
                        + " wrapping shuffle turns a box that crosses the frame"
                        + " edge into the whole frame"),
        /**
         * The method measured the real field but could not measure one of its
         * shuffled copies — territory tessellation on a layout the geometry
         * library cannot resolve, for example objects on a perfectly regular
         * grid. Its observed values stand; its chance test is not reported,
         * and the other methods' tests are unaffected.
         */
        FAILED_ON_A_SHUFFLE("Failed on a shuffle",
                "the method could not measure a shuffled copy of this field,"
                        + " so it has no chance test; its observed values stand");

        private final String label;
        private final String explanation;

        Skip(String label, String explanation) {
            this.label = label;
            this.explanation = explanation;
        }

        /**
         * Short form, for display. The {@code Randomization Skipped} column
         * carries the constant's name instead, which a script can match on.
         */
        public String label() {
            return label;
        }

        /** Long form, for a tooltip or the run record. */
        public String explanation() {
            return explanation;
        }
    }

    private final String engineId;
    private final DirectionKey direction;
    private final double observed;
    private final double[] permuted;
    private final long seed;
    private final Skip skip;

    private NullModelResult(String engineId, DirectionKey direction, double observed,
            double[] permuted, long seed, Skip skip) {
        this.engineId = engineId;
        this.direction = direction;
        this.observed = observed;
        this.permuted = permuted;
        this.seed = seed;
        this.skip = skip;
    }

    /**
     * A completed null model.
     *
     * @param permuted the statistic under each permutation, <b>indexed by
     *                 permutation number</b> — element <i>i</i> must be the
     *                 result of permutation <i>i</i>, not of whichever worker
     *                 finished <i>i</i>-th
     */
    public static NullModelResult of(String engineId, DirectionKey direction,
            double observed, double[] permuted, long seed) {
        if (permuted == null || permuted.length == 0) {
            throw new IllegalArgumentException(
                    "a completed null model has at least one permutation; "
                            + "use skipped(...) to report that it did not run");
        }
        return new NullModelResult(engineId, direction, observed,
                permuted.clone(), seed, Skip.NONE);
    }

    /**
     * A direction that carries no null model, and the reason.
     *
     * <p>Separate from a zero-permutation result on purpose. A run that did not
     * randomize and a run that randomized and found nothing look identical in
     * every summary column, and a reader who cannot tell them apart will read
     * the first as the second.
     */
    public static NullModelResult skipped(String engineId, DirectionKey direction,
            double observed, Skip skip) {
        if (skip == null || skip == Skip.NONE) {
            throw new IllegalArgumentException("a skipped result needs a reason");
        }
        return new NullModelResult(engineId, direction, observed,
                new double[0], 0L, skip);
    }

    public String engineId() {
        return engineId;
    }

    public DirectionKey direction() {
        return direction;
    }

    /** The statistic as actually measured, present whether or not the null ran. */
    public double observed() {
        return observed;
    }

    public boolean ran() {
        return skip == Skip.NONE;
    }

    public Skip skip() {
        return skip;
    }

    public int permutations() {
        return permuted.length;
    }

    public long seed() {
        return seed;
    }

    /** The statistic under each permutation, indexed by permutation number. */
    public double[] permutedValues() {
        return permuted.clone();
    }

    /** Mean of the permuted statistic. NaN when the null did not run. */
    public double expected() {
        if (!ran()) {
            return Double.NaN;
        }
        double total = 0.0;
        for (int i = 0; i < permuted.length; i++) {
            total += permuted[i];
        }
        return total / permuted.length;
    }

    /**
     * Observed divided by expected.
     *
     * <p>NaN rather than infinity when nothing was expected by chance. An
     * enrichment of ∞ formats as a number in a table and reads as the strongest
     * possible finding; it actually means the denominator was zero, which is a
     * statement about the null and not about the data.
     */
    public double enrichment() {
        double expected = expected();
        if (!ran() || expected == 0.0) {
            return Double.NaN;
        }
        return observed / expected;
    }

    /**
     * One-sided permutation p, {@code (k + 1) / (P + 1)} where <i>k</i> counts
     * permutations reaching or beating the observation.
     *
     * <p>The {@code +1} on both sides is not a fudge: it counts the observation
     * itself as one of the possible arrangements, which is what makes the test
     * exact and stops it ever reporting p = 0. A p of exactly zero would claim
     * infinite evidence from a finite number of shuffles.
     */
    public double p() {
        if (!ran()) {
            return Double.NaN;
        }
        int atLeastAsExtreme = 0;
        for (int i = 0; i < permuted.length; i++) {
            if (permuted[i] >= observed) {
                atLeastAsExtreme++;
            }
        }
        return (atLeastAsExtreme + 1.0) / (permuted.length + 1.0);
    }

    /**
     * The lower-tail counterpart of {@link #p()}: how ordinary it would be to
     * see this <i>few</i> coincidences by chance.
     *
     * <p>Needed because depletion is a finding. Two structures that avoid each
     * other colocalize less than randomly placed ones would, and a one-sided
     * upper-tail test cannot see that at all — it reports a large p and the
     * effect reads as "nothing here".
     */
    public double pDepletion() {
        if (!ran()) {
            return Double.NaN;
        }
        int atMostAsExtreme = 0;
        for (int i = 0; i < permuted.length; i++) {
            if (permuted[i] <= observed) {
                atMostAsExtreme++;
            }
        }
        return (atMostAsExtreme + 1.0) / (permuted.length + 1.0);
    }

    /**
     * Two-sided p, {@code min(1, 2 × min(enrichment, depletion))}.
     *
     * <p>The doubling is the price of not having decided the direction in
     * advance, and it is not optional: taking whichever tail happens to look
     * better and reporting it as a one-sided p halves every p in the table and
     * doubles the false-positive rate. Capped at 1 because a probability cannot
     * exceed it, which the doubling can otherwise produce near the middle.
     */
    public double pTwoSided() {
        if (!ran()) {
            return Double.NaN;
        }
        return Math.min(1.0, 2.0 * Math.min(p(), pDepletion()));
    }

    /**
     * The smallest p this permutation count can express, {@code 1 / (P + 1)}.
     *
     * <p>Reported alongside p because "p = 0.01" from 99 permutations means "no
     * shuffle beat it", not "one in a hundred". Without the floor beside it a
     * reader cannot tell a result that is at the limit of resolution from one
     * that is comfortably inside it.
     */
    public double minimumP() {
        return ran() ? 1.0 / (permuted.length + 1.0) : Double.NaN;
    }

    @Override
    public String toString() {
        if (!ran()) {
            return engineId + " " + direction + " skipped(" + skip.label() + ")";
        }
        return engineId + " " + direction + " observed=" + observed
                + " expected=" + expected() + " p=" + p()
                + " over " + permutations() + " permutations";
    }
}
