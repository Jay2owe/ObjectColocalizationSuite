/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ocs;

import java.io.File;

/**
 * A folder of images, and how to tell which files belong together.
 *
 * <p>Same idiom as the rest of the family: one regular expression over the file
 * names with a capture group marking the part that <i>varies between
 * channels</i>. Files agreeing on everything except that group are one field,
 * imaged twice. {@code (.*)_C(\d)\.tif} with varying group 2 puts
 * {@code sample3_C1.tif} and {@code sample3_C2.tif} together and keeps
 * {@code sample4_C1.tif} apart from both.
 *
 * <p>Holds the analysis settings as an {@link OCSParameters} <i>template</i>:
 * the images in it are ignored and replaced per field, everything else applies
 * to every field. One place to say "45% overlap, tested against chance", rather
 * than a second copy of every option with "batch" in front of it.
 */
public final class OCSBatchParameters {

    private final File labelFolder;
    private final String labelRegex;
    private final int varyingGroup;
    private final boolean recursive;
    private final File intensityFolder;
    private final String intensityRegex;
    private final File sharedRegionRoi;
    private final File saveDir;
    private final boolean autoSave;
    private final OCSParameters template;

    private OCSBatchParameters(Builder builder) {
        this.labelFolder = builder.labelFolder;
        this.labelRegex = builder.labelRegex;
        this.varyingGroup = builder.varyingGroup;
        this.recursive = builder.recursive;
        this.intensityFolder = builder.intensityFolder;
        this.intensityRegex = builder.intensityRegex;
        this.sharedRegionRoi = builder.sharedRegionRoi;
        this.saveDir = builder.saveDir;
        this.autoSave = builder.autoSave;
        this.template = builder.template;
    }

    public static Builder builder(File labelFolder, String labelRegex,
            int varyingGroup) {
        return new Builder(labelFolder, labelRegex, varyingGroup);
    }

    public File labelFolder() {
        return labelFolder;
    }

    public String labelRegex() {
        return labelRegex;
    }

    /** Which capture group varies between channels of the same field. */
    public int varyingGroup() {
        return varyingGroup;
    }

    public boolean isRecursive() {
        return recursive;
    }

    public File intensityFolder() {
        return intensityFolder;
    }

    public String intensityRegex() {
        return intensityRegex;
    }

    public boolean usesIntensityImages() {
        return intensityFolder != null && intensityRegex != null
                && !intensityRegex.trim().isEmpty();
    }

    /**
     * One region applied to every field, or null.
     *
     * <p>Named "shared" rather than "the region" because that is the assumption
     * it makes, and it is only sometimes true: a fixed imaging window, a chamber,
     * a well. Where each field has its own tissue outline, this is the wrong
     * tool and the chance test should be run per field instead.
     */
    public File sharedRegionRoi() {
        return sharedRegionRoi;
    }

    public File saveDir() {
        return saveDir;
    }

    public boolean isAutoSave() {
        return autoSave;
    }

    /** The analysis settings; its images are placeholders and are replaced. */
    public OCSParameters template() {
        return template;
    }

    public static final class Builder {

        private final File labelFolder;
        private final String labelRegex;
        private final int varyingGroup;
        private boolean recursive;
        private File intensityFolder;
        private String intensityRegex;
        private File sharedRegionRoi;
        private File saveDir;
        private boolean autoSave = true;
        private OCSParameters template;

        private Builder(File labelFolder, String labelRegex, int varyingGroup) {
            this.labelFolder = labelFolder;
            this.labelRegex = labelRegex;
            this.varyingGroup = varyingGroup;
        }

        public Builder recursive(boolean recursive) {
            this.recursive = recursive;
            return this;
        }

        /**
         * @param regex the intensity images' own pattern; null or blank means
         *              they are named like the label images. (Blank used to
         *              switch the intensity images off without a word, so a
         *              batch given only an intensity folder ran without them.)
         */
        public Builder intensity(File folder, String regex) {
            this.intensityFolder = folder;
            this.intensityRegex = regex == null || regex.trim().isEmpty()
                    ? labelRegex : regex;
            return this;
        }

        public Builder sharedRegionRoi(File file) {
            this.sharedRegionRoi = file;
            return this;
        }

        public Builder saveDir(File saveDir) {
            this.saveDir = saveDir;
            return this;
        }

        public Builder autoSave(boolean autoSave) {
            this.autoSave = autoSave;
            return this;
        }

        public Builder template(OCSParameters template) {
            this.template = template;
            return this;
        }

        public OCSBatchParameters build() {
            return new OCSBatchParameters(this);
        }
    }
}
