/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ocs;

import java.io.IOException;

/**
 * Which failures are the user's to fix, and what to tell them.
 *
 * <p>The entry commands show these as a sentence and keep ImageJ's stack-trace
 * window for genuine bugs. A stack trace for "the save folder is on a drive
 * that is not plugged in" reads as a crash, and gets reported as one.
 */
public final class OCSErrors {

    private OCSErrors() {
    }

    /**
     * @return the sentence to show, or null when the failure is a bug and the
     *         stack trace is the useful thing to report
     */
    public static String messageFor(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof OutOfMemoryError) {
                return memoryMessage();
            }
        }
        if (failure instanceof IllegalArgumentException) {
            return failure.getMessage();
        }
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof IOException) {
                return "Could not read or save a file: " + t.getMessage()
                        + "\nCheck that the folder exists, the drive is connected"
                        + " and there is space on it.";
            }
        }
        return null;
    }

    /** Said the same way wherever memory runs out. */
    public static String memoryMessage() {
        return "Fiji ran out of memory. Give it more under Edit > Options >"
                + " Memory & Threads, or run fewer methods or shuffles at once.";
    }
}
