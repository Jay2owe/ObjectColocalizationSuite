/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ocs.nullmodel;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which way a channel is shuffled.
 *
 * <p>Two different null hypotheses, not two implementations of one. A <i>p</i>
 * from one is not comparable with a <i>p</i> from the other, which is why the
 * choice travels in the run record rather than being a tuning knob.
 */
public enum NullModelKind {

    /**
     * Every object picked up and dropped somewhere else inside the region,
     * shape and size preserved, none allowed to touch another.
     *
     * <p>The stronger null where it can run, because each object is placed
     * independently of every other, so nothing of the channel's own arrangement
     * survives. Refuses the field outright when the objects cannot be re-packed
     * — a confluent network that fills the frame, or nuclei filling a third of
     * the region.
     */
    PER_OBJECT("per-object",
            "each object relocated independently inside the region"),

    /**
     * The whole channel slid by one random offset, wrapping at the frame edges.
     *
     * <p>Runs on any segmentation, however crowded or confluent, because nothing
     * is re-packed. Tests a weaker and different null: each channel keeps its own
     * internal arrangement, and only its position relative to the other channel
     * is randomized. That is the right question when the channels were imaged
     * separately and the claim under test is that they are associated with each
     * other rather than that either is unstructured.
     */
    WHOLE_CHANNEL("whole-channel",
            "the whole channel slid by one offset, wrapping at the frame edges");

    /**
     * Engines whose statistic a wrapping shuffle distorts without bound.
     *
     * <p>The rule, and it is a rule rather than a list. A wrapping shuffle moves
     * voxels and creates or destroys none, so:
     *
     * <ul>
     * <li>a statistic read from an object's <b>voxel membership</b> is exact —
     *     {@code volume-overlap}, {@code jaccard-dice}, {@code containment};</li>
     * <li>a statistic read from an object's <b>centroid</b> is displaced but
     *     bounded, and displaced toward and away from partners equally —
     *     {@code cpc}, {@code distance-tolerance};</li>
     * <li>a statistic read from an object's <b>axis-aligned extent</b> is
     *     unbounded, because an object crossing the frame edge arrives in two
     *     pieces at opposite sides and its box becomes the whole frame — and a
     *     frame-wide box overlaps everything.</li>
     * </ul>
     *
     * <p>Only the third class is refused, and {@code bounding-box} is the only
     * member of it. Ids rather than types because engine ids are the public API
     * and because the null-model layer must not depend on the engine
     * implementations it is shuffling for.
     */
    private static final Set<String> EXTENT_DERIVED_ENGINE_IDS =
            Collections.unmodifiableSet(new LinkedHashSet<String>(
                    Arrays.asList("bounding-box")));

    private final String id;
    private final String explanation;

    NullModelKind(String id, String explanation) {
        this.id = id;
        this.explanation = explanation;
    }

    /**
     * Whether this null model would distort what an engine measures.
     *
     * <p>A property of the null hypothesis rather than of the run: only a
     * shuffle that wraps can turn a bounding box into the whole frame, and
     * {@link #PER_OBJECT} never wraps — it places every object wholly inside the
     * region or refuses the field.
     *
     * <p>Callers should skip a distorted engine rather than report its
     * <i>p</i>. On the validation set, reporting it produced a confident finding
     * of depletion on 51% of channel pairs that had nothing in them.
     *
     * @param engineId the engine's public id, e.g. {@code bounding-box}
     */
    public boolean distorts(String engineId) {
        if (this != WHOLE_CHANNEL) {
            return false;
        }
        return EXTENT_DERIVED_ENGINE_IDS.contains(engineId);
    }

    /**
     * The ids any wrapping null model distorts, for a test that wants to check
     * every engine has been considered rather than defaulted.
     */
    public static Set<String> extentDerivedEngineIds() {
        return EXTENT_DERIVED_ENGINE_IDS;
    }

    /** Stable identifier for macros and the run record. */
    public String id() {
        return id;
    }

    public String explanation() {
        return explanation;
    }

    /**
     * @throws IllegalArgumentException on an unknown id, naming the ones that
     *         exist. A macro asking for a null model that does not exist must
     *         fail rather than quietly run a different one
     */
    public static NullModelKind byId(String id) {
        NullModelKind[] all = values();
        for (int i = 0; i < all.length; i++) {
            if (all[i].id.equalsIgnoreCase(id)) {
                return all[i];
            }
        }
        StringBuilder known = new StringBuilder();
        for (int i = 0; i < all.length; i++) {
            known.append(i > 0 ? ", " : "").append(all[i].id);
        }
        throw new IllegalArgumentException("no null model with id '" + id
                + "'; the ones that exist are " + known);
    }

    @Override
    public String toString() {
        return id;
    }
}
