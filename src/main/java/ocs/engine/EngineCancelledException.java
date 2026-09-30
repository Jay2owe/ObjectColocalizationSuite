package ocs.engine;

/**
 * Thrown when a user cancels a run.
 *
 * <p>Unchecked so it can unwind cleanly from deep inside a voxel loop without
 * every engine declaring it. Cancellation must never surface as a partial
 * result: a half-finished null model looks exactly like a finished one in a
 * table, and would be indistinguishable in a figure six months later.
 */
public class EngineCancelledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EngineCancelledException(String engineId) {
        super("cancelled during " + engineId);
    }
}
