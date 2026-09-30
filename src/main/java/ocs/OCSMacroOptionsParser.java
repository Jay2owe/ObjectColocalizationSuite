/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ocs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns an ImageJ macro options string into {@link OCSMacroOptions}.
 *
 * <p>Refuses what it does not recognise. A misspelled option that parsed as
 * nothing would run a different analysis from the one the line describes, and
 * the person reading the results months later has only the line.
 */
public final class OCSMacroOptionsParser {

    private OCSMacroOptionsParser() {
    }

    public static OCSMacroOptions parse(String optionsText) {
        OCSMacroOptions options = new OCSMacroOptions();
        Set<String> seen = new HashSet<String>();
        List<String> tokens = tokenize(optionsText == null ? "" : optionsText);
        // A comma-separated list is only read as a list when the key appears
        // once. The recorder writes one key per channel, and a title may hold a
        // comma; splitting it there would replay a different set of channels.
        boolean splitChannels = count(tokens, OCSMacroOptions.CHANNELS) == 1;
        boolean splitIntensity = count(tokens, OCSMacroOptions.INTENSITY) == 1;

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            int equals = token.indexOf('=');
            if (equals < 0) {
                applyFlag(options, token.toLowerCase(Locale.ROOT));
                continue;
            }
            String key = token.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            String value = decode(token.substring(equals + 1).trim());
            // channels= and intensity= repeat, once per channel; everything else
            // appearing twice means the line contradicts itself, and guessing
            // which occurrence was meant is not something a parser should do.
            boolean repeatable = OCSMacroOptions.CHANNELS.equals(key)
                    || OCSMacroOptions.INTENSITY.equals(key);
            if (!repeatable && !seen.add(key)) {
                throw new IllegalArgumentException(
                        "duplicate macro option: " + key);
            }
            applyKeyValue(options, key, value, splitChannels, splitIntensity);
        }
        options.validate();
        return options;
    }

    private static int count(List<String> tokens, String key) {
        int n = 0;
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            int equals = token.indexOf('=');
            if (equals > 0 && key.equals(token.substring(0, equals).trim()
                    .toLowerCase(Locale.ROOT))) {
                n++;
            }
        }
        return n;
    }

    // ---------- tokenizing ----------

    /** Splits on whitespace, honouring ImageJ's {@code [bracketed values]}. */
    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<String>();
        StringBuilder token = new StringBuilder();
        boolean inBracket = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inBracket) {
                if (c == '[') {
                    throw new IllegalArgumentException(
                            "nested brackets are not allowed in macro options");
                }
                if (c == '\n' || c == '\r') {
                    throw new IllegalArgumentException(
                            "line breaks are not allowed in macro option values");
                }
                token.append(c);
                if (c == ']') {
                    inBracket = false;
                }
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (token.length() > 0) {
                    tokens.add(token.toString());
                    token.setLength(0);
                }
                continue;
            }
            if (c == '[') {
                inBracket = true;
            } else if (c == ']') {
                throw new IllegalArgumentException(
                        "unexpected closing bracket in macro options");
            }
            token.append(c);
        }
        if (inBracket) {
            throw new IllegalArgumentException(
                    "unclosed bracketed macro option value");
        }
        if (token.length() > 0) {
            tokens.add(token.toString());
        }
        return tokens;
    }

    /**
     * A value with its brackets removed.
     *
     * <p>Backslashes are kept: a Windows path typed into a macro as
     * {@code "C:\\data\\roi.zip"} arrives here with single backslashes, and
     * refusing them refused every such path.
     */
    static String decode(String raw) {
        if (raw.length() >= 2 && raw.charAt(0) == '['
                && raw.charAt(raw.length() - 1) == ']') {
            String inner = raw.substring(1, raw.length() - 1);
            if (inner.indexOf('[') >= 0 || inner.indexOf(']') >= 0
                    || inner.indexOf('"') >= 0) {
                throw new IllegalArgumentException("bracketed macro values must not"
                        + " contain brackets or quotes");
            }
            return inner;
        }
        if (raw.indexOf('"') >= 0) {
            throw new IllegalArgumentException(
                    "macro values must not contain quotes");
        }
        return raw;
    }

    // ---------- applying ----------

    private static void applyKeyValue(OCSMacroOptions options, String key,
            String value, boolean splitChannels, boolean splitIntensity) {
        if (OCSMacroOptions.CHANNELS.equals(key)) {
            // One key per channel, or one key holding a comma-separated list.
            // Both because the recorder writes one per channel and a person
            // writing the line by hand writes the list.
            addAll(options, value, true, splitChannels);
            return;
        }
        if (OCSMacroOptions.INTENSITY.equals(key)) {
            addAll(options, value, false, splitIntensity);
            return;
        }
        if (OCSMacroOptions.REGION_ROI.equals(key)) {
            options.setRegionRoi(value);
            return;
        }
        if (OCSMacroOptions.METHODS.equals(key)) {
            options.setMethods(value);
            return;
        }
        if (key.startsWith(OCSMacroOptions.THRESHOLD_PREFIX)
                && !OCSMacroOptions.THRESHOLD_SWEEP.equals(key)) {
            String engineId = key.substring(
                    OCSMacroOptions.THRESHOLD_PREFIX.length());
            if (engineId.isEmpty()) {
                throw new IllegalArgumentException(
                        OCSMacroOptions.THRESHOLD_PREFIX + " needs a method id");
            }
            options.setThreshold(engineId, asDouble(key, value));
            return;
        }
        if (OCSMacroOptions.BIDIRECTIONAL.equals(key)) {
            options.setBidirectional(asBoolean(key, value));
            return;
        }
        if (OCSMacroOptions.PERMUTATIONS.equals(key)) {
            options.setPermutations(asInt(key, value));
            return;
        }
        if (OCSMacroOptions.SEED.equals(key)) {
            options.setSeed(asLong(key, value));
            return;
        }
        if (OCSMacroOptions.ALPHA.equals(key)) {
            options.setAlpha(asDouble(key, value));
            return;
        }
        if (OCSMacroOptions.FLIP_THRESHOLD.equals(key)) {
            options.setFlipThreshold(asDouble(key, value));
            return;
        }
        if (OCSMacroOptions.SWEEP_WIDTH.equals(key)) {
            options.setSweepWidth(asDouble(key, value));
            return;
        }
        if (OCSMacroOptions.MIN_COMPARE_N.equals(key)) {
            options.setMinCompareN(asInt(key, value));
            return;
        }
        if (OCSMacroOptions.OUTPUT.equals(key)) {
            options.setOutput(value);
            return;
        }
        refuseDeferred(key);
        throw new IllegalArgumentException("unknown macro option: " + key);
    }

    /**
     * The four options the contract lists and 0.1.0 does not build.
     *
     * <p>Refused by name rather than as unknown, so the message can say when
     * they arrive and what 0.1.0 does instead. Accepting and ignoring them would
     * record a run that did not happen.
     */
    static final String[] DEFERRED_TO_0_2_0 = {
        "block_size", "psf_xy", "psf_z", "min_object_voxels"
    };

    private static void refuseDeferred(String key) {
        for (int i = 0; i < DEFERRED_TO_0_2_0.length; i++) {
            if (DEFERRED_TO_0_2_0[i].equals(key)) {
                throw new IllegalArgumentException(key + "= is not available in"
                        + " Object Colocalization Suite 0.1.0; it is planned for"
                        + " 0.2.0 (see CHANGELOG, Deferred to 0.2.0). 0.1.0 uses"
                        + " a 5 x 5 voxel Costes block, recorded in the Block Size"
                        + " column, and reports per-object Pearson for every object"
                        + " of 3 or more voxels. Remove the option to run.");
            }
        }
    }

    private static void applyFlag(OCSMacroOptions options, String flag) {
        refuseDeferred(flag);
        if (OCSMacroOptions.BIDIRECTIONAL.equals(flag)) {
            options.setBidirectional(true);
        } else if (OCSMacroOptions.ONE_DIRECTION.equals(flag)) {
            options.setBidirectional(false);
        } else if (OCSMacroOptions.NULL_MODEL.equals(flag)) {
            options.setRunsNullModel(true);
        } else if (OCSMacroOptions.AGREEMENT.equals(flag)) {
            options.setRunsAgreement(true);
        } else if (OCSMacroOptions.THRESHOLD_SWEEP.equals(flag)) {
            options.setRunsThresholdSweep(true);
        } else if (OCSMacroOptions.DISCOVERY.equals(flag)) {
            // Discovery reads all three, so asking for it asks for them.
            options.setRunsDiscovery(true);
            options.setRunsNullModel(true);
            options.setRunsAgreement(true);
            options.setRunsThresholdSweep(true);
        } else if (OCSMacroOptions.HIDE_DISPLAY.equals(flag)) {
            options.setHideDisplay(true);
        } else {
            throw new IllegalArgumentException("unknown macro flag: " + flag);
        }
    }

    private static void addAll(OCSMacroOptions options, String value,
            boolean channels, boolean split) {
        String[] parts = split ? value.split(",") : new String[] {value};
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.isEmpty()) {
                continue;
            }
            if (channels) {
                options.addChannel(part);
            } else {
                options.addIntensity(part);
            }
        }
    }

    private static double asDouble(String key, String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(
                    key + "= expects a number, got '" + value + "'");
        }
    }

    private static int asInt(String key, String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(
                    key + "= expects a whole number, got '" + value + "'");
        }
    }

    private static long asLong(String key, String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(
                    key + "= expects a whole number, got '" + value + "'");
        }
    }

    private static boolean asBoolean(String key, String value) {
        String text = value.trim().toLowerCase(Locale.ROOT);
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
