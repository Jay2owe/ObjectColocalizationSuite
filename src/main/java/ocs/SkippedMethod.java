/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ocs;

/**
 * A method that was asked for and did not run, and why.
 *
 * <p>Exists so that "asked for thirteen methods, got eleven" is a fact the
 * result carries rather than something a user works out by counting rows. A
 * preset naming every method is the normal case, and on label images without a
 * region ROI two of them genuinely cannot run — that is not an error, but it is
 * not silence either.
 */
public final class SkippedMethod {

    private final String engineId;
    private final String reason;

    SkippedMethod(String engineId, String reason) {
        if (engineId == null || engineId.trim().isEmpty()) {
            throw new IllegalArgumentException("a skipped method needs its id");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "a skipped method must say why, or nobody can act on it");
        }
        this.engineId = engineId;
        this.reason = reason;
    }

    public String engineId() {
        return engineId;
    }

    /** Phrased for a user: {@code "needs raw intensity images"}. */
    public String reason() {
        return reason;
    }

    @Override
    public String toString() {
        return engineId + ": " + reason;
    }
}
