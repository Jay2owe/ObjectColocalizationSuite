package ocs.ui;

import ij.ImagePlus;
import ij.Prefs;
import ocs.engine.EngineInputs;
import ocs.nullmodel.NullModelRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * The selection the dialog opens on next time.
 *
 * <p>One class, so there is exactly one place that knows the preference keys and
 * exactly one place to look when somebody asks why the dialog opened on the wrong
 * thing.
 *
 * <p>Written only when Run is pressed, never when Cancel is. Remembering a
 * selection somebody explicitly backed out of would make Cancel a way of changing
 * settings, which is the opposite of what the button says.
 *
 * <p>The extra checks are remembered alongside the methods rather than left to
 * default off. They are the expensive part of a run — the chance test alone
 * multiplies it by about a hundred — so a user who turned them off and found the
 * plugin usable would otherwise find it slow again every session, with nothing on
 * screen explaining what changed.
 */
public final class RememberedMethods {

    static final String CHANCE_KEY = "ocs.checks.chance";
    static final String AGREEMENT_KEY = "ocs.checks.agreement";
    static final String SWEEP_KEY = "ocs.checks.sweep";
    static final String PERMUTATIONS_KEY = "ocs.checks.shuffles";
    static final String SEED_KEY = "ocs.checks.seed";

    private RememberedMethods() {
    }

    /** The stored selection, or null when there is none. */
    public static String read() {
        String stored = Prefs.get(MethodSelectionModel.PREFERENCE_KEY, "");
        return stored == null || stored.trim().isEmpty() ? null : stored;
    }

    /** Stores the methods and the checks, or clears both if nothing is ticked. */
    public static void remember(MethodSelectionModel model) {
        String value = model.toPreferenceValue();
        if (value.trim().isEmpty()) {
            // Blank clears rather than storing an empty string, so a stored blank
            // can never be read back as "the user chose no methods".
            forgetMethods();
            return;
        }
        Prefs.set(MethodSelectionModel.PREFERENCE_KEY, value);
        Prefs.set(CHANCE_KEY, model.runsNullModel());
        Prefs.set(AGREEMENT_KEY, model.runsAgreement());
        Prefs.set(SWEEP_KEY, model.runsThresholdSweep());
        Prefs.set(PERMUTATIONS_KEY, model.permutations());
        // As text, not as a number: ij.Prefs stores numbers as doubles, and a
        // seed that survives the round trip only approximately is worse than one
        // that is not stored at all — it would replay a different run.
        Prefs.set(SEED_KEY, String.valueOf(model.seed()));
    }

    /**
     * Applies the remembered checks to a model, if there is a remembered
     * selection at all.
     *
     * <p>Guarded on the selection because a model with no remembered methods has
     * just opened on a preset, and the preset's own choice of checks is the right
     * answer — overwriting it with stored booleans would give the user a preset
     * that does not do what the preset says.
     */
    public static void restoreChecks(MethodSelectionModel model) {
        if (read() == null) {
            return;
        }
        model.setRunsNullModel(Prefs.get(CHANCE_KEY, false));
        model.setRunsAgreement(Prefs.get(AGREEMENT_KEY, false));
        model.setRunsThresholdSweep(Prefs.get(SWEEP_KEY, false));

        int shuffles = (int) Math.round(Prefs.get(PERMUTATIONS_KEY,
                NullModelRunner.DEFAULT_PERMUTATIONS));
        if (shuffles >= 1) {
            model.setPermutations(shuffles);
        }
        try {
            model.setSeed(Long.parseLong(Prefs.get(SEED_KEY,
                    String.valueOf(NullModelRunner.DEFAULT_SEED)).trim()));
        } catch (NumberFormatException corrupted) {
            // A hand-edited or truncated IJ_Prefs.txt. Falling back to the
            // default beats refusing to open the dialog over a stored seed.
            model.setSeed(NullModelRunner.DEFAULT_SEED);
        } catch (IllegalArgumentException tooLarge) {
            model.setSeed(NullModelRunner.DEFAULT_SEED);
        }
    }

    // ---------- the Inputs dialog ----------

    /** Followed by the channel index, 0 to 4: one key per image title. */
    static final String CHANNEL_KEY = "ocs.inputs.channel.";
    static final String INTENSITY_KEY = "ocs.inputs.intensity.";
    static final String REGION_SOURCE_KEY = "ocs.inputs.region";
    static final String REGION_FILE_KEY = "ocs.inputs.region_file";
    static final String OUTPUT_KEY = "ocs.inputs.output";
    static final String SHOW_TABLES_KEY = "ocs.inputs.show_tables";

    /**
     * The titles of the label images last run on, in channel order; empty if
     * none. One key per title because a window title can hold any separator.
     */
    public static List<String> rememberedChannels() {
        List<String> titles = new ArrayList<String>();
        for (int c = 0; c < EngineInputs.MAX_CHANNELS; c++) {
            String title = Prefs.get(CHANNEL_KEY + c, "");
            if (title == null || title.isEmpty()) {
                break;
            }
            titles.add(title);
        }
        return titles;
    }

    /** The title of the intensity image last paired with this channel, or "none". */
    public static String rememberedIntensity(int channel) {
        String title = Prefs.get(INTENSITY_KEY + channel, "");
        return title == null || title.isEmpty() ? InputSelection.NONE : title;
    }

    public static InputSelection.RegionSource rememberedRegionSource() {
        return InputSelection.RegionSource.fromLabel(Prefs.get(REGION_SOURCE_KEY,
                InputSelection.RegionSource.NONE.name()));
    }

    public static String rememberedRegionFile() {
        return Prefs.get(REGION_FILE_KEY, "");
    }

    public static String rememberedOutput() {
        return Prefs.get(OUTPUT_KEY, "");
    }

    public static boolean rememberedShowTables() {
        return Prefs.get(SHOW_TABLES_KEY, true);
    }

    /** Stores what the Inputs dialog was confirmed with; never called on Cancel. */
    public static void rememberInputs(InputSelection chosen) {
        for (int c = 0; c < EngineInputs.MAX_CHANNELS; c++) {
            ImagePlus label = c < chosen.labels().size() ? chosen.labels().get(c) : null;
            ImagePlus raw = c < chosen.intensities().size()
                    ? chosen.intensities().get(c) : null;
            Prefs.set(CHANNEL_KEY + c, label == null ? "" : label.getTitle());
            Prefs.set(INTENSITY_KEY + c, raw == null ? "" : raw.getTitle());
        }
        Prefs.set(REGION_SOURCE_KEY, chosen.regionSource().name());
        Prefs.set(REGION_FILE_KEY, chosen.regionPath() == null ? "" : chosen.regionPath());
        Prefs.set(OUTPUT_KEY, chosen.output() == null ? "" : chosen.output());
        Prefs.set(SHOW_TABLES_KEY, chosen.showTables());
    }

    /** Clears everything remembered: the inputs, the methods and the checks. */
    public static void forget() {
        forgetMethods();
        for (int c = 0; c < EngineInputs.MAX_CHANNELS; c++) {
            Prefs.set(CHANNEL_KEY + c, "");
            Prefs.set(INTENSITY_KEY + c, "");
        }
        Prefs.set(REGION_SOURCE_KEY, InputSelection.RegionSource.NONE.name());
        Prefs.set(REGION_FILE_KEY, "");
        Prefs.set(OUTPUT_KEY, "");
        Prefs.set(SHOW_TABLES_KEY, true);
    }

    private static void forgetMethods() {
        Prefs.set(MethodSelectionModel.PREFERENCE_KEY, "");
        Prefs.set(CHANCE_KEY, false);
        Prefs.set(AGREEMENT_KEY, false);
        Prefs.set(SWEEP_KEY, false);
        Prefs.set(PERMUTATIONS_KEY, NullModelRunner.DEFAULT_PERMUTATIONS);
        Prefs.set(SEED_KEY, String.valueOf(NullModelRunner.DEFAULT_SEED));
    }
}
