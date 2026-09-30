package ocs.engine;

import java.util.List;
import java.util.Set;

/**
 * One colocalization method.
 *
 * <p>The plugin hardcodes none of its twenty-five-odd methods. Every one
 * implements this interface, the dialog is generated from the registry, and the
 * null-model, concordance and clustering layers wrap any engine without knowing
 * what it does. That is what keeps "add method 24" a one-file change.
 *
 * <p>Implementations must be stateless and safe to call repeatedly. The
 * null-model layer runs an engine hundreds of times over permuted inputs; an
 * engine that caches anything derived from {@link EngineInputs} between calls
 * will report the unpermuted answer every time and produce a null distribution
 * with no variance — which looks like a stunningly significant result rather
 * than like a bug.
 *
 * @see BUILD_ORDER.md for the staged plan that fills the registry
 */
public interface ColocEngine {

    /**
     * Stable identifier used in macro options, column prefixes and run records.
     * Lower-case, hyphenated, never translated: {@code "cpc"},
     * {@code "volume-overlap"}, {@code "ripley-k"}. Changing one breaks every
     * saved macro, so treat it as public API.
     */
    String id();

    /** Human-readable name for the dialog and table headers. */
    String displayName();

    EngineFamily family();

    /** Checked against the loaded data before the run starts. */
    Set<InputRequirement> requires();

    /**
     * Columns this engine will produce, declared before it runs. Exactly one
     * must be {@link ColumnSpec#isPrimary() primary}.
     */
    List<ColumnSpec> columns();

    /**
     * Whether this engine's measure is direction-independent.
     *
     * <p>Symmetric engines — Pearson's r, Jaccard — still report both
     * directions, but the null-model layer can halve its work by permuting only
     * one, and the concordance matrix can avoid double-counting them.
     */
    boolean isSymmetric();

    /**
     * Rough relative cost per direction, used for the honest pre-run estimate
     * the plan requires.
     *
     * <p>Not seconds — a unitless weight where 1 is a single pass over the
     * voxels. Costes randomization and cross-Ripley are two to three orders of
     * magnitude above that, and a user who selects "everything" across a batch
     * deserves to know before rather than after.
     */
    double relativeCost();

    /**
     * Runs the method.
     *
     * @throws EngineCancelledException if {@code progress} reports cancellation;
     *         never returns a partial result
     */
    EngineResult compute(EngineInputs inputs, EngineProgress progress);
}
