package ocs.agreement;

import ocs.engine.DirectionKey;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One method pair, one direction, one tier.
 *
 * <p>Statistics are held <b>by name</b> rather than as fixed fields, for the
 * reason the rest of this codebase addresses columns by name: a positional
 * contract is what let an engine transpose two percentages undetectably in stage
 * 4. Here the risk is the same shape — kappa, rho and CCC are all bounded
 * correlation-like numbers on similar ranges, and swapping two of them produces
 * a table that still looks entirely reasonable.
 *
 * <p>A cell always exists for a pair that was attempted, even when nothing could
 * be computed. An absent cell and a cell reading "not comparable" look identical
 * in a matrix rendered from present cells only, and the second is information.
 */
public final class AgreementCell {

    /** Objects compared below which a cell is real but not to be leant on. */
    public static final int DEFAULT_MINIMUM_N = 30;

    private final Tier tier;
    private final String engineA;
    private final String engineB;
    private final DirectionKey direction;
    private final int n;
    private final Map<String, Double> statistics;
    private final boolean underpowered;
    private final boolean duplicatedBySymmetry;
    private final String notComputed;

    /**
     * A computed cell.
     *
     * <p>Public because a cell is an immutable value and things outside this
     * package legitimately assemble them — a batch aggregator combining several
     * images, and the tests that check how Discovery reads a cell without having
     * to run an entire pipeline to produce one.
     */
    public static AgreementCell of(Tier tier, String engineA, String engineB,
            DirectionKey direction, int n, Map<String, Double> statistics,
            boolean underpowered, boolean duplicatedBySymmetry) {
        if (tier == null || engineA == null || engineB == null || statistics == null) {
            throw new IllegalArgumentException(
                    "tier, both engine ids and the statistics are required");
        }
        return new AgreementCell(tier, engineA, engineB, direction, n, statistics,
                underpowered, duplicatedBySymmetry, null);
    }

    /** A cell that exists to say why it holds nothing. */
    public static AgreementCell notComputed(Tier tier, String engineA, String engineB,
            DirectionKey direction, String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "an uncomputed cell must say why; a blank cell is not a reason");
        }
        return new AgreementCell(tier, engineA, engineB, direction, 0,
                new LinkedHashMap<String, Double>(), true, false, reason);
    }

    AgreementCell(Tier tier, String engineA, String engineB, DirectionKey direction,
            int n, Map<String, Double> statistics, boolean underpowered,
            boolean duplicatedBySymmetry, String notComputed) {
        this.tier = tier;
        this.engineA = engineA;
        this.engineB = engineB;
        this.direction = direction;
        this.n = n;
        this.statistics = Collections.unmodifiableMap(
                new LinkedHashMap<String, Double>(statistics));
        this.underpowered = underpowered;
        this.duplicatedBySymmetry = duplicatedBySymmetry;
        this.notComputed = notComputed;
    }

    public Tier tier() {
        return tier;
    }

    public String engineA() {
        return engineA;
    }

    public String engineB() {
        return engineB;
    }

    /** Null for {@link Tier#VERDICT}, which is gathered across a whole batch. */
    public DirectionKey direction() {
        return direction;
    }

    /** Objects — or, at tier V, runs — that both methods scored. */
    public int n() {
        return n;
    }

    public boolean wasComputed() {
        return notComputed == null;
    }

    /** Why this cell holds no statistics, or null if it does. */
    public String notComputedReason() {
        return notComputed;
    }

    /**
     * Whether <i>n</i> fell below the minimum.
     *
     * <p>Reported rather than dropped, and marked rather than trusted: a kappa
     * on 8 objects and a kappa on 500 are both kappas and must not read as equal
     * in authority.
     */
    public boolean isUnderpowered() {
        return underpowered;
    }

    /**
     * Whether both engines are symmetric, so this cell and its mirror carry
     * identical values by construction.
     *
     * <p>Flagged because two cells agreeing is otherwise read as corroboration,
     * and here it is arithmetic.
     */
    public boolean isDuplicatedBySymmetry() {
        return duplicatedBySymmetry;
    }

    public Map<String, Double> statistics() {
        return statistics;
    }

    /**
     * @throws IllegalArgumentException if this cell carries no such statistic —
     *         rather than returning zero, which would enter a table as a real
     *         measurement of perfect disagreement
     */
    public double statistic(String name) {
        Double value = statistics.get(name);
        if (value == null) {
            throw new IllegalArgumentException("cell " + engineA + " vs " + engineB
                    + " at tier " + tier.label() + " carries no '" + name
                    + "'; it has " + statistics.keySet());
        }
        return value.doubleValue();
    }

    @Override
    public String toString() {
        String where = direction == null ? "batch" : direction.label();
        if (!wasComputed()) {
            return "tier " + tier.label() + " " + engineA + " vs " + engineB
                    + " (" + where + "): " + notComputed;
        }
        return "tier " + tier.label() + " " + engineA + " vs " + engineB
                + " (" + where + ") n=" + n + " " + statistics;
    }
}
