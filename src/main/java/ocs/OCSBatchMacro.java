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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The batch command's macro line, both ways.
 *
 * <p>The folder options are the batch's own; every other option is the
 * single-run grammar, passed through to build the template every field runs
 * with. One grammar for "which methods, at what settings, with which checks",
 * so {@code sweep_width=} or {@code null_model} means the same in a batch line
 * as in a single run and cannot be silently dropped.
 *
 * <p>Needs no ImageJ, so the grammar is tested without a running Fiji.
 */
public final class OCSBatchMacro {

    public static final String FOLDER = "folder";
    public static final String PATTERN = "pattern";
    public static final String CHANNEL_GROUP = "channel_group";
    public static final String SUBFOLDERS = "subfolders";
    public static final String INTENSITY_FOLDER = "intensity_folder";
    public static final String INTENSITY_PATTERN = "intensity_pattern";

    /** The pattern the dialog offers first: {@code name_C1.tif}, {@code name_C2.tif}. */
    public static final String DEFAULT_PATTERN = "(.*)_C(\\d)\\..*";
    public static final int DEFAULT_CHANNEL_GROUP = 2;
    public static final String DEFAULT_METHODS = "preset:Quick look";

    /** Single-run options that name images, which a batch takes from file names. */
    private static final String[] NOT_IN_A_BATCH = {
        OCSMacroOptions.CHANNELS, OCSMacroOptions.INTENSITY, OCSMacroOptions.HIDE_DISPLAY
    };

    private OCSBatchMacro() {
    }

    /**
     * @throws IllegalArgumentException naming the option that is wrong
     */
    public static OCSBatchParameters parse(String optionsText) {
        List<String> tokens = OCSMacroOptionsParser.tokenize(
                optionsText == null ? "" : optionsText);
        String folder = null;
        String pattern = DEFAULT_PATTERN;
        int group = DEFAULT_CHANNEL_GROUP;
        boolean recursive = false;
        String intensityFolder = null;
        String intensityPattern = "";
        String regionRoi = null;
        String output = null;
        boolean methodsGiven = false;
        StringBuilder analysis = new StringBuilder();
        Set<String> seen = new HashSet<String>();

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            int equals = token.indexOf('=');
            String key = (equals < 0 ? token : token.substring(0, equals))
                    .trim().toLowerCase(Locale.ROOT);
            String raw = equals < 0 ? null : token.substring(equals + 1).trim();
            for (int n = 0; n < NOT_IN_A_BATCH.length; n++) {
                if (NOT_IN_A_BATCH[n].equals(key)) {
                    throw new IllegalArgumentException(key + (equals < 0 ? "" : "=")
                            + " is not a batch option: a batch finds each field's"
                            + " images from the file names in " + FOLDER + "=");
                }
            }
            if (!seen.add(key)) {
                throw new IllegalArgumentException("duplicate macro option: " + key);
            }
            if (SUBFOLDERS.equals(key)) {
                recursive = raw == null || asBoolean(key, raw);
            } else if (FOLDER.equals(key)) {
                folder = value(key, raw);
            } else if (PATTERN.equals(key)) {
                pattern = value(key, raw);
            } else if (CHANNEL_GROUP.equals(key)) {
                group = asInt(key, value(key, raw));
            } else if (INTENSITY_FOLDER.equals(key)) {
                intensityFolder = value(key, raw);
            } else if (INTENSITY_PATTERN.equals(key)) {
                intensityPattern = value(key, raw);
            } else if (OCSMacroOptions.OUTPUT.equals(key)) {
                output = value(key, raw);
            } else {
                if (OCSMacroOptions.REGION_ROI.equals(key)) {
                    regionRoi = value(key, raw);
                }
                if (OCSMacroOptions.METHODS.equals(key)) {
                    methodsGiven = true;
                }
                // Everything else is the single-run grammar, checked there.
                analysis.append(' ').append(token);
            }
        }
        if (folder == null || folder.trim().isEmpty()) {
            throw new IllegalArgumentException(FOLDER + "= is needed: the folder"
                    + " of label images to run over");
        }
        if (!methodsGiven) {
            analysis.append(' ').append(OCSMacroOptions.METHODS).append("=[")
                    .append(DEFAULT_METHODS).append(']');
        }
        OCSBatchParameters.Builder builder = OCSBatchParameters
                .builder(new File(folder.trim()), pattern, group)
                .recursive(recursive)
                .template(template(analysis.toString().trim()));
        if (intensityFolder != null && !intensityFolder.trim().isEmpty()) {
            builder.intensity(new File(intensityFolder.trim()), intensityPattern);
        }
        if (regionRoi != null && !regionRoi.trim().isEmpty()) {
            builder.sharedRegionRoi(new File(regionRoi.trim()));
        }
        if (output != null && !output.trim().isEmpty()) {
            builder.saveDir(new File(output.trim()));
        }
        return builder.build();
    }

    /**
     * The template every field runs with, from single-run options.
     *
     * <p>Two placeholder channels satisfy the single-run grammar's own check;
     * the images themselves come from each field.
     */
    public static OCSParameters template(String analysisOptions) {
        OCSMacroOptions options = OCSMacroOptionsParser.parse(
                OCSMacroOptions.CHANNELS + "=placeholder-a "
                        + OCSMacroOptions.CHANNELS + "=placeholder-b "
                        + (analysisOptions == null ? "" : analysisOptions));
        return options.applyTo(OCSParameters.builder(
                new ArrayList<ij.ImagePlus>())).build();
    }

    /**
     * The line that repeats a batch set up in the dialog.
     *
     * <p>Paths are written with forward slashes, which ImageJ reads on every
     * platform. The pattern is a regular expression and keeps its backslashes;
     * {@link OCSMacroOptions#forRecorder} doubles them for the macro string.
     *
     * @param analysisOptions single-run options, as typed in the dialog
     */
    public static String toMacroOptions(OCSBatchParameters parameters,
            String analysisOptions) {
        StringBuilder line = new StringBuilder();
        append(line, FOLDER, path(parameters.labelFolder()));
        append(line, PATTERN, parameters.labelRegex());
        append(line, CHANNEL_GROUP, String.valueOf(parameters.varyingGroup()));
        if (parameters.isRecursive()) {
            line.append(' ').append(SUBFOLDERS);
        }
        if (parameters.usesIntensityImages()) {
            append(line, INTENSITY_FOLDER, path(parameters.intensityFolder()));
            append(line, INTENSITY_PATTERN, parameters.intensityRegex());
        }
        if (parameters.sharedRegionRoi() != null) {
            append(line, OCSMacroOptions.REGION_ROI, path(parameters.sharedRegionRoi()));
        }
        if (parameters.saveDir() != null) {
            append(line, OCSMacroOptions.OUTPUT, path(parameters.saveDir()));
        }
        if (analysisOptions != null && !analysisOptions.trim().isEmpty()) {
            line.append(' ').append(analysisOptions.trim());
        }
        return line.toString().trim();
    }

    // ---------- plumbing ----------

    private static void append(StringBuilder line, String key, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        line.append(' ').append(key).append('=');
        if (value.indexOf(' ') >= 0) {
            line.append('[').append(value).append(']');
        } else {
            line.append(value);
        }
    }

    private static String path(File file) {
        return file == null ? null : file.getPath().replace('\\', '/');
    }

    private static String value(String key, String raw) {
        if (raw == null) {
            throw new IllegalArgumentException(key + "= needs a value");
        }
        return OCSMacroOptionsParser.decode(raw);
    }

    private static int asInt(String key, String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(
                    key + "= expects a whole number, got '" + value + "'");
        }
    }

    private static boolean asBoolean(String key, String value) {
        String text = OCSMacroOptionsParser.decode(value).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(text) || "1".equals(text) || "yes".equals(text)) {
            return true;
        }
        if ("false".equals(text) || "0".equals(text) || "no".equals(text)) {
            return false;
        }
        throw new IllegalArgumentException(
                key + "= expects true or false, got '" + value + "'");
    }
}
