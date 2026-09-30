/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ocs;

import ocs.agreement.AgreementCell;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a whole folder produced: the fields that ran, and the ones that did not.
 *
 * <p>Failures are collected rather than thrown. One unreadable file in four
 * hundred must not lose the other three hundred and ninety-nine, and a batch
 * that stopped at the first bad field would be run overnight and found dead in
 * the morning.
 */
public final class OCSBatchResult {

    /** One field that failed, kept with enough detail to go and look at it. */
    public static final class Failure {

        private final String name;
        private final String reason;

        Failure(String name, String reason) {
            this.name = name;
            this.reason = reason;
        }

        public String name() {
            return name;
        }

        public String reason() {
            return reason;
        }

        @Override
        public String toString() {
            return name + ": " + reason;
        }
    }

    private final List<OCSResult> results;
    private final List<String> fieldNames;
    private final List<Failure> failures;
    private final File outputFolder;
    private final boolean cancelled;

    private final Map<String, List<AgreementCell>> verdictAgreement;

    OCSBatchResult(List<OCSResult> results, List<String> fieldNames,
            List<Failure> failures, File outputFolder, boolean cancelled,
            Map<String, List<AgreementCell>> verdictAgreement) {
        this.verdictAgreement = Collections.unmodifiableMap(
                new LinkedHashMap<String, List<AgreementCell>>(verdictAgreement));
        this.results = Collections.unmodifiableList(
                new ArrayList<OCSResult>(results));
        this.fieldNames = Collections.unmodifiableList(
                new ArrayList<String>(fieldNames));
        this.failures = Collections.unmodifiableList(
                new ArrayList<Failure>(failures));
        this.outputFolder = outputFolder;
        this.cancelled = cancelled;
    }

    /**
     * Each field's full result, in running order — <b>only when auto-save was
     * off</b>. With auto-save on this is empty: each field's tables are on disk
     * under {@link #outputFolder()}, and keeping every field's per-object tables
     * in memory until the end is what exhausted the heap on large batches. Use
     * {@link #fieldNames()} and {@link #fieldsRun()} for what ran.
     */
    public List<OCSResult> results() {
        return results;
    }

    /**
     * Tier-V agreement: for each direction position ({@code C1 -> C2}), kappa
     * between every pair of methods' verdicts over the fields. Empty when no
     * method produced a verdict (no chance test and no spatial method).
     */
    public Map<String, List<AgreementCell>> verdictAgreement() {
        return verdictAgreement;
    }

    /** Every field that ran (and, with auto-save, was saved), in running order. */
    public List<String> fieldNames() {
        return fieldNames;
    }

    public List<Failure> failures() {
        return failures;
    }

    public int fieldsRun() {
        return fieldNames.size();
    }

    /** Null when auto-save was off. */
    public File outputFolder() {
        return outputFolder;
    }

    /**
     * Whether the user stopped it partway.
     *
     * <p>Carried explicitly because a cancelled batch and a batch where most
     * fields failed produce the same short list of results, and only one of them
     * means something is wrong with the data.
     */
    public boolean wasCancelled() {
        return cancelled;
    }

    @Override
    public String toString() {
        return "OCSBatchResult[" + fieldNames.size() + " run, "
                + failures.size() + " failed"
                + (cancelled ? ", cancelled" : "") + "]";
    }
}
