package ocs.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A named set of methods, so the first decision is one choice rather than
 * thirteen.
 *
 * <p>Like the wash cycles on a machine: nobody wants to set drum speed and
 * temperature from scratch, they want "wool" — and the dial still turns to
 * manual for the one person who does.
 *
 * <p>Each preset carries its own <b>rough cost</b>, shown in the dropdown beside
 * the name. That is the whole reason presets exist here rather than as a
 * convenience: the difference between the cheapest and the fullest selection is
 * about two orders of magnitude, and a user choosing blind will pick the
 * thorough-sounding one and conclude the plugin is slow.
 */
public final class Preset {

    private final String name;
    private final String description;
    private final List<String> engineIds;
    private final boolean nullModel;
    private final boolean agreement;
    private final boolean thresholdSweep;
    private final boolean discovery;

    Preset(String name, String description, List<String> engineIds,
            boolean nullModel, boolean agreement, boolean thresholdSweep) {
        this(name, description, engineIds, nullModel, agreement, thresholdSweep,
                false);
    }

    Preset(String name, String description, List<String> engineIds,
            boolean nullModel, boolean agreement, boolean thresholdSweep,
            boolean discovery) {
        this.name = name;
        this.description = description;
        this.engineIds = Collections.unmodifiableList(new ArrayList<String>(engineIds));
        this.nullModel = nullModel;
        this.agreement = agreement;
        this.thresholdSweep = thresholdSweep;
        this.discovery = discovery;
    }

    /** Shown in the dropdown. */
    public String name() {
        return name;
    }

    /** One line under the name saying who it is for. */
    public String description() {
        return description;
    }

    public List<String> engineIds() {
        return engineIds;
    }

    /** Whether to test every method against chance. */
    public boolean runsNullModel() {
        return nullModel;
    }

    /** Whether to build the cross-method agreement matrix. */
    public boolean runsAgreement() {
        return agreement;
    }

    /** Whether to sweep each method's threshold to see how stable it is. */
    public boolean runsThresholdSweep() {
        return thresholdSweep;
    }

    /**
     * Whether to classify the methods (Discovery) from the three checks.
     *
     * <p>Only the Discovery preset does. Before 0.1.0 it switched on the three
     * checks but not the classification, so the preset named for Discovery
     * never produced a Discovery table from the dialog (found by the GUI
     * checks).
     */
    public boolean runsDiscovery() {
        return discovery;
    }

    /**
     * The shipped presets, cheapest first.
     *
     * <p>"Custom" is deliberately absent as a stored preset — it is what the
     * dropdown shows once the user has touched a checkbox, and inventing a
     * preset for it would imply there is a saved configuration when there is not.
     */
    public static List<Preset> shipped() {
        List<Preset> presets = new ArrayList<Preset>();

        presets.add(new Preset("Quick look",
                "Two cheap object methods. Seconds, and enough to see whether "
                        + "there is anything here at all.",
                Arrays.asList("cpc", "volume-overlap"),
                false, false, false));

        presets.add(new Preset("Object colocalization",
                "Every object-based method, tested against chance. The usual "
                        + "starting point for punctate data.",
                Arrays.asList("cpc", "volume-overlap", "bounding-box", "containment",
                        "jaccard-dice", "distance-tolerance"),
                true, true, true));

        presets.add(new Preset("Intensity colocalization",
                "Per-object and whole-image Pearson and Manders. Needs raw "
                        + "intensity images, not just labels.",
                Arrays.asList("per-object-intensity", "whole-image-intensity"),
                true, true, true));

        presets.add(new Preset("Spatial arrangement",
                "Cross-function curves and territories — for asking whether one "
                        + "channel is arranged around the other, not whether it "
                        + "touches it. Slow: each curve runs its own simulations.",
                Arrays.asList("cross-g", "cross-k", "cross-l",
                        "cross-pair-correlation", "territory-occupancy"),
                true, true, false));

        presets.add(new Preset("Discovery — everything",
                "All thirteen methods, every diagnostic. Answers 'which method "
                        + "fits this dataset', and takes the longest by far.",
                Arrays.asList("cpc", "volume-overlap", "bounding-box", "containment",
                        "jaccard-dice", "distance-tolerance",
                        "per-object-intensity", "whole-image-intensity",
                        "cross-g", "cross-k", "cross-l", "cross-pair-correlation",
                        "territory-occupancy"),
                true, true, true, true));

        return Collections.unmodifiableList(presets);
    }

    /** The one the dialog opens on. */
    public static Preset defaultPreset() {
        return shipped().get(0);
    }

    /**
     * The shipped preset with this name, matched on its ASCII letters and digits
     * only, ignoring case; the part before a dash is enough.
     *
     * <p>Loose on purpose. A macro file saved as UTF-8 is read in the platform
     * encoding on Windows, which turns the dash in "Discovery \u2014 everything"
     * into three other characters; an exact match could then never be typed
     * into a macro at all. "discovery", "Discovery - everything" and the
     * mangled form all find it.
     */
    public static Preset byName(String name) {
        String wanted = key(name);
        List<Preset> presets = shipped();
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < presets.size(); i++) {
            String full = presets.get(i).name();
            int dash = full.indexOf('\u2014');
            if (!wanted.isEmpty() && (wanted.equals(key(full))
                    || (dash > 0 && wanted.equals(key(full.substring(0, dash)))))) {
                return presets.get(i);
            }
            names.append(i == 0 ? "" : ", ").append(full);
        }
        throw new IllegalArgumentException("no preset named '" + name + "'; the"
                + " presets are " + names);
    }

    private static String key(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder key = new StringBuilder();
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                key.append(c);
            }
        }
        return key.toString();
    }

    @Override
    public String toString() {
        return name + " (" + engineIds.size() + " methods)";
    }
}
