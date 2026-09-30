/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ocs;

import ocs.nullmodel.NullModelRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One macro line, taken apart.
 *
 * <p>Holds what the line said and nothing about images: resolving a title to an
 * open window, or a path to a file on disk, is the entry point's job and needs
 * an ImageJ that this class deliberately does not require. That split is what
 * lets the grammar be tested without a running Fiji.
 *
 * <p><b>Option names and method ids are public API.</b> Renaming either breaks
 * every saved macro, silently, on somebody else's machine.
 */
public final class OCSMacroOptions {

    /** What the command records and what it accepts. Keep the two identical. */
    public static final String CHANNELS = "channels";
    public static final String INTENSITY = "intensity";
    public static final String REGION_ROI = "region_roi";
    public static final String METHODS = "methods";
    public static final String THRESHOLD_PREFIX = "threshold_";
    public static final String BIDIRECTIONAL = "bidirectional";
    public static final String ONE_DIRECTION = "one_direction";
    public static final String NULL_MODEL = "null_model";
    public static final String PERMUTATIONS = "permutations";
    public static final String SEED = "seed";
    public static final String AGREEMENT = "agreement";
    public static final String THRESHOLD_SWEEP = "threshold_sweep";
    public static final String DISCOVERY = "discovery";
    public static final String ALPHA = "alpha";
    public static final String FLIP_THRESHOLD = "flip_threshold";

    /**
     * How far either side of each method's chosen threshold the sweep reaches,
     * as a fraction of what that measure can be.
     *
     * <p>A fraction rather than an absolute width because the threshold-bearing
     * methods are on four different scales, so no single absolute number could
     * be right on more than one of them.
     */
    public static final String SWEEP_WIDTH = "sweep_width";
    public static final String MIN_COMPARE_N = "min_compare_n";
    public static final String OUTPUT = "output";
    public static final String HIDE_DISPLAY = "hide_display";

    /** Value prefix that names a shipped preset instead of listing methods. */
    public static final String PRESET_PREFIX = "preset:";

    /** Value that means every method the registry knows. */
    public static final String ALL = "all";

    /**
     * {@code region_roi=} values that read the region from the screen at replay
     * time instead of from a file: whatever the ROI Manager holds, or the active
     * image's selection. Anything else is a path to a {@code .zip} or
     * {@code .roi}.
     */
    public static final String REGION_FROM_ROI_MANAGER = "ROI Manager";
    public static final String REGION_FROM_SELECTION = "selection";

    private final List<String> channels = new ArrayList<String>();
    private final List<String> intensity = new ArrayList<String>();
    private final Map<String, Double> thresholds = new LinkedHashMap<String, Double>();
    private String regionRoi;
    private String methods;
    private boolean bidirectional = true;
    private boolean nullModel;
    private boolean agreement;
    private boolean thresholdSweep;
    private boolean discovery;
    private int permutations = NullModelRunner.DEFAULT_PERMUTATIONS;
    private long seed = NullModelRunner.DEFAULT_SEED;
    private double alpha = ocs.agreement.Verdict.DEFAULT_ALPHA;
    private double flipThreshold = ocs.sweep.FlipFraction.DEFAULT_FRAGILE_ABOVE;
    private double sweepWidth = ocs.sweep.ThresholdSweep.DEFAULT_SWEEP_WIDTH;
    private int minCompareN = ocs.agreement.AgreementCell.DEFAULT_MINIMUM_N;
    private String output;
    private boolean hideDisplay;

    OCSMacroOptions() {
    }

    /**
     * The options that repeat a run built elsewhere — the menu dialog — for the
     * macro recorder.
     *
     * <p>Methods are written as the explicit id list, never as a preset, so the
     * line keeps meaning the same run if a preset's contents change later.
     *
     * @param channels  label image titles, in channel order
     * @param intensity intensity image titles, one per channel, or empty
     * @param regionRoi the {@code region_roi=} value, or null for none
     * @param output    auto-save folder with forward slashes, or null
     */
    public static OCSMacroOptions forRecording(OCSParameters parameters,
            List<String> channels, List<String> intensity, String regionRoi,
            String output, boolean hideDisplay) {
        OCSMacroOptions options = new OCSMacroOptions();
        options.channels.addAll(channels);
        if (intensity != null) {
            options.intensity.addAll(intensity);
        }
        options.regionRoi = regionRoi;
        StringBuilder ids = new StringBuilder();
        List<String> methodIds = parameters.methodIds();
        for (int i = 0; i < methodIds.size(); i++) {
            if (i > 0) {
                ids.append(',');
            }
            ids.append(methodIds.get(i));
        }
        options.methods = ids.toString();
        options.thresholds.putAll(parameters.thresholds());
        options.bidirectional = parameters.isBidirectional();
        options.nullModel = parameters.runsNullModel();
        options.agreement = parameters.runsAgreement();
        options.thresholdSweep = parameters.runsThresholdSweep();
        options.discovery = parameters.runsDiscovery();
        options.permutations = parameters.permutations();
        options.seed = parameters.seed();
        options.alpha = parameters.alpha();
        options.flipThreshold = parameters.flipThreshold();
        options.sweepWidth = parameters.sweepWidth();
        options.minCompareN = parameters.minCompareN();
        options.output = output;
        options.hideDisplay = hideDisplay;
        return options;
    }

    /** Titles or paths, in channel order. */
    public List<String> channels() {
        return Collections.unmodifiableList(channels);
    }

    /** Titles or paths of the paired intensity images; empty when none. */
    public List<String> intensityImages() {
        return Collections.unmodifiableList(intensity);
    }

    public String regionRoi() {
        return regionRoi;
    }

    /**
     * The raw {@code methods=} value: a comma-separated id list,
     * {@code preset:<name>}, or {@code all}. Left raw because resolving
     * {@code all} needs the registry, and this class does not reach for one.
     */
    public String methods() {
        return methods;
    }

    public Map<String, Double> thresholds() {
        return Collections.unmodifiableMap(thresholds);
    }

    public boolean isBidirectional() {
        return bidirectional;
    }

    public boolean runsNullModel() {
        return nullModel;
    }

    public boolean runsAgreement() {
        return agreement;
    }

    public boolean runsThresholdSweep() {
        return thresholdSweep;
    }

    public boolean runsDiscovery() {
        return discovery;
    }

    public int permutations() {
        return permutations;
    }

    public long seed() {
        return seed;
    }

    public double alpha() {
        return alpha;
    }

    public double flipThreshold() {
        return flipThreshold;
    }

    public double sweepWidth() {
        return sweepWidth;
    }

    public int minCompareN() {
        return minCompareN;
    }

    public String output() {
        return output;
    }

    public boolean hideDisplay() {
        return hideDisplay;
    }

    // ---------- filled in by the parser ----------

    void addChannel(String value) {
        channels.add(value);
    }

    void addIntensity(String value) {
        intensity.add(value);
    }

    void setRegionRoi(String value) {
        this.regionRoi = value;
    }

    void setMethods(String value) {
        this.methods = value;
    }

    void setThreshold(String engineId, double value) {
        thresholds.put(engineId, Double.valueOf(value));
    }

    void setBidirectional(boolean value) {
        this.bidirectional = value;
    }

    void setRunsNullModel(boolean value) {
        this.nullModel = value;
    }

    void setRunsAgreement(boolean value) {
        this.agreement = value;
    }

    void setRunsThresholdSweep(boolean value) {
        this.thresholdSweep = value;
    }

    void setRunsDiscovery(boolean value) {
        this.discovery = value;
    }

    void setPermutations(int value) {
        this.permutations = value;
    }

    void setSeed(long value) {
        this.seed = value;
    }

    void setAlpha(double value) {
        this.alpha = value;
    }

    void setFlipThreshold(double value) {
        this.flipThreshold = value;
    }

    void setSweepWidth(double value) {
        this.sweepWidth = value;
    }

    void setMinCompareN(int value) {
        this.minCompareN = value;
    }

    void setOutput(String value) {
        this.output = value;
    }

    void setHideDisplay(boolean value) {
        this.hideDisplay = value;
    }

    /**
     * Checks what can be checked without images.
     *
     * <p>Deliberately not everything: whether {@code channels=} names windows
     * that exist is a question about a running ImageJ, and answering it here
     * would make the grammar untestable without one.
     */
    void validate() {
        if (channels.size() < OCSParameters.MIN_IMAGES) {
            throw new IllegalArgumentException(CHANNELS + "= needs at least "
                    + OCSParameters.MIN_IMAGES + " entries, got " + channels.size());
        }
        if (channels.size() > ocs.engine.EngineInputs.MAX_CHANNELS) {
            throw new IllegalArgumentException(CHANNELS + "= takes at most "
                    + ocs.engine.EngineInputs.MAX_CHANNELS + " channels, got "
                    + channels.size());
        }
        if (!ocs.ui.MethodSelectionModel.isRecordableExactly(seed)) {
            // The run record is JSON, and a JSON reader turns a larger integer
            // into the nearest double: a different seed from the one that ran.
            throw new IllegalArgumentException(SEED + "=" + seed + " cannot be"
                    + " recorded exactly; use a seed of magnitude at most 2^53"
                    + " (9007199254740992)");
        }
        if (!intensity.isEmpty() && intensity.size() != channels.size()) {
            // Not padded to fit. A shorter list would silently pair the wrong
            // intensity image with the wrong labels, and every number after that
            // would be confidently wrong.
            throw new IllegalArgumentException(INTENSITY + "= must name one image"
                    + " per channel or none at all; got " + intensity.size()
                    + " for " + channels.size() + " channels");
        }
        if (permutations < 1) {
            throw new IllegalArgumentException(PERMUTATIONS
                    + "= must be at least 1, not " + permutations);
        }
        if (!(alpha > 0.0 && alpha < 1.0)) {
            throw new IllegalArgumentException(ALPHA
                    + "= must lie strictly between 0 and 1, not " + alpha);
        }
        if (!(sweepWidth > 0.0)) {
            throw new IllegalArgumentException(SWEEP_WIDTH
                    + "= is how far the sweep reaches either side, as a fraction"
                    + " of what the measure can be, so it must be above 0, not "
                    + sweepWidth + "; use 1 or more to sweep the whole range");
        }
        if (!(flipThreshold >= 0.0 && flipThreshold <= 1.0)) {
            throw new IllegalArgumentException(FLIP_THRESHOLD
                    + "= is a fraction between 0 and 1, not " + flipThreshold);
        }
        if (minCompareN < 1) {
            throw new IllegalArgumentException(MIN_COMPARE_N
                    + "= must be at least 1, not " + minCompareN);
        }
        if (nullModel && (regionRoi == null || regionRoi.trim().isEmpty())) {
            throw new IllegalArgumentException(NULL_MODEL + " needs " + REGION_ROI
                    + "= saying where objects may be scattered. It will not"
                    + " default to the whole frame: scattering objects over the"
                    + " parts that are not tissue makes every result look more"
                    + " significant than it is.");
        }
        if (discovery && (regionRoi == null || regionRoi.trim().isEmpty())) {
            throw new IllegalArgumentException(DISCOVERY + " reads the chance"
                    + " test, which needs " + REGION_ROI + "=.");
        }
    }

    /**
     * Applies everything except the images, which the caller supplies.
     *
     * <p>Resolving {@code methods=} needs the registry for {@code all}, so it
     * happens here rather than in the parser.
     */
    public OCSParameters.Builder applyTo(OCSParameters.Builder builder) {
        boolean usedPreset = false;
        if (methods != null && !methods.trim().isEmpty()) {
            String value = methods.trim();
            if (value.toLowerCase(java.util.Locale.ROOT).startsWith(PRESET_PREFIX)) {
                builder.preset(value.substring(PRESET_PREFIX.length()).trim());
                usedPreset = true;
            } else if (ALL.equalsIgnoreCase(value)) {
                builder.allMethods();
            } else {
                List<String> ids = new ArrayList<String>();
                String[] parts = value.split(",");
                for (int i = 0; i < parts.length; i++) {
                    String id = parts[i].trim();
                    if (!id.isEmpty()) {
                        ids.add(id);
                    }
                }
                builder.methods(ids);
            }
        }
        // A preset carries its own choice of checks, and a flag can only turn a
        // check on — there is no macro spelling for "off". So after a preset the
        // flags add to what it asked for rather than replacing it; writing the
        // whole set would silently switch off the chance test that the preset
        // named in the same line exists to run.
        if (usedPreset) {
            if (nullModel) {
                builder.nullModel(true);
            }
            if (agreement) {
                builder.agreement(true);
            }
            if (thresholdSweep) {
                builder.thresholdSweep(true);
            }
        } else {
            builder.nullModel(nullModel)
                    .agreement(agreement)
                    .thresholdSweep(thresholdSweep);
        }
        builder.thresholds(thresholds)
                .bidirectional(bidirectional)
                .permutations(permutations)
                .seed(seed)
                .alpha(alpha)
                .flipThreshold(flipThreshold)
                .sweepWidth(sweepWidth)
                .minCompareN(minCompareN);
        if (discovery) {
            builder.discovery(true);
        }
        return builder;
    }

    /**
     * Options text as it must appear inside a recorded macro string.
     *
     * <p>ImageJ's recorder writes the text between double quotes as it is, and
     * the macro language reads a backslash there as an escape; doubling them
     * replays the same text.
     */
    public static String forRecorder(String optionsText) {
        return optionsText == null ? "" : optionsText.replace("\\", "\\\\");
    }

    /**
     * The line that would reproduce this, for the macro recorder.
     *
     * <p>Round-trips: parsing what this produces gives back the same options.
     * A recorder whose output cannot be replayed is worse than none, because it
     * looks like a record of what happened.
     */
    public String toMacroOptions() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < channels.size(); i++) {
            append(text, CHANNELS + "=" + bracket(channels.get(i)));
        }
        for (int i = 0; i < intensity.size(); i++) {
            append(text, INTENSITY + "=" + bracket(intensity.get(i)));
        }
        if (regionRoi != null && !regionRoi.trim().isEmpty()) {
            append(text, REGION_ROI + "=" + bracket(regionRoi));
        }
        if (methods != null && !methods.trim().isEmpty()) {
            append(text, METHODS + "=" + bracket(methods));
        }
        for (Map.Entry<String, Double> entry : thresholds.entrySet()) {
            append(text, THRESHOLD_PREFIX + entry.getKey() + "="
                    + trim(entry.getValue().doubleValue()));
        }
        if (!bidirectional) {
            append(text, ONE_DIRECTION);
        }
        if (nullModel) {
            append(text, NULL_MODEL);
            append(text, PERMUTATIONS + "=" + permutations);
            append(text, SEED + "=" + seed);
        }
        if (agreement) {
            append(text, AGREEMENT);
        }
        if (thresholdSweep) {
            append(text, THRESHOLD_SWEEP);
        }
        if (discovery) {
            append(text, DISCOVERY);
        }
        if (alpha != ocs.agreement.Verdict.DEFAULT_ALPHA) {
            append(text, ALPHA + "=" + trim(alpha));
        }
        if (sweepWidth != ocs.sweep.ThresholdSweep.DEFAULT_SWEEP_WIDTH) {
            append(text, SWEEP_WIDTH + "=" + trim(sweepWidth));
        }
        if (flipThreshold != ocs.sweep.FlipFraction.DEFAULT_FRAGILE_ABOVE) {
            append(text, FLIP_THRESHOLD + "=" + trim(flipThreshold));
        }
        if (minCompareN != ocs.agreement.AgreementCell.DEFAULT_MINIMUM_N) {
            append(text, MIN_COMPARE_N + "=" + minCompareN);
        }
        if (output != null && !output.trim().isEmpty()) {
            append(text, OUTPUT + "=" + bracket(output));
        }
        if (hideDisplay) {
            append(text, HIDE_DISPLAY);
        }
        return text.toString();
    }

    private static void append(StringBuilder text, String token) {
        if (text.length() > 0) {
            text.append(' ');
        }
        text.append(token);
    }

    /** Brackets anything with a space in it, which is how ImageJ quotes values. */
    private static String bracket(String value) {
        return value.indexOf(' ') >= 0 ? "[" + value + "]" : value;
    }

    /** Whole numbers without a trailing {@code .0}, which reads as false precision. */
    private static String trim(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)
                && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }
}
