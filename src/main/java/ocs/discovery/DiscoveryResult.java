package ocs.discovery;

import ocs.engine.DirectionKey;

import java.util.Collections;
import java.util.List;

/**
 * One method's class, and the evidence that put it there.
 *
 * <p>There is no score field and no rank field, and that is structural rather
 * than an omission. Adding one later would be a decision to make, not a
 * convenience to reach for: the moment a number exists it will be sorted on,
 * cited, and read as an authority nobody agreed to give it.
 *
 * <p>The evidence is a list of plain sentences rather than the raw numbers alone
 * because the class on its own is an assertion. "Uninformative here" invites
 * "says who"; "did not clear the null model: p = 0.34 against 99 permutations"
 * answers it, and lets a reader disagree with the threshold rather than with
 * the tool.
 */
public final class DiscoveryResult {

    private final String engineId;
    private final DirectionKey direction;
    private final DiscoveryClass discoveryClass;
    private final List<String> evidence;
    private final List<String> divergentFrom;

    DiscoveryResult(String engineId, DirectionKey direction,
            DiscoveryClass discoveryClass, List<String> evidence,
            List<String> divergentFrom) {
        this.engineId = engineId;
        this.direction = direction;
        this.discoveryClass = discoveryClass;
        this.evidence = Collections.unmodifiableList(evidence);
        this.divergentFrom = Collections.unmodifiableList(divergentFrom);
    }

    public String engineId() {
        return engineId;
    }

    public DirectionKey direction() {
        return direction;
    }

    public DiscoveryClass discoveryClass() {
        return discoveryClass;
    }

    /** Why, in sentences. Never empty — a class with no evidence is an opinion. */
    public List<String> evidence() {
        return evidence;
    }

    /**
     * The families this method agrees with nothing in, populated only for
     * {@link DiscoveryClass#DIVERGENT}.
     *
     * <p>Named rather than counted, because "diverges from the intensity family"
     * and "diverges from the object family" point a reader at completely
     * different follow-ups.
     */
    public List<String> divergentFrom() {
        return divergentFrom;
    }

    @Override
    public String toString() {
        return engineId + " " + (direction == null ? "-" : direction.label())
                + ": " + discoveryClass.displayName() + " " + evidence;
    }
}
