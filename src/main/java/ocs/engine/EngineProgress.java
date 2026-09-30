package ocs.engine;

/**
 * Progress reporting and cancellation for a running engine.
 *
 * <p>Deliberately defined here rather than reusing {@code oc3d-core}'s
 * equivalent. That type gets relocated into {@code ocs.internal.core} by
 * the shade plugin, so exposing it through this package's public API would mean
 * anyone writing an engine had to code against a relocated internal name. The
 * pattern rule is to never relocate a documented public API; keeping our own
 * two-method interface is cheaper than the alternative.
 *
 * <p>Engines must call {@link #isCancelled()} inside any loop that could run for
 * more than a second or so. Running "everything" over a batch can take hours,
 * and an uncancellable run is the difference between a slow tool and an
 * unusable one.
 */
public interface EngineProgress {

    /** Progress reporter that accepts everything and is never cancelled. */
    EngineProgress SILENT = new EngineProgress() {
        @Override
        public void report(String stage, double fraction) {
            // deliberately empty
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    };

    /**
     * @param stage    short human-readable description of what is happening now
     * @param fraction completed fraction in [0, 1], or a negative value when the
     *                 engine genuinely cannot estimate one
     */
    void report(String stage, double fraction);

    /**
     * Whether the user has asked to stop. Engines should return promptly and
     * throw {@link EngineCancelledException} rather than returning a partial
     * result, so a cancelled run can never be mistaken for a complete one.
     */
    boolean isCancelled();
}
