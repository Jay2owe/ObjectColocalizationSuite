package ocs.ui;

import ocs.engine.ColocEngine;
import ocs.engine.EngineInputs;
import ocs.engine.EngineRegistry;
import ocs.engine.InputRequirement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What is ticked, what it will cost, and what cannot be ticked at all.
 *
 * <p>Holds no Swing. The dialog draws this and writes back to it, which is what
 * lets the interesting behaviour — presets, greying out, the cost estimate, the
 * remembered selection — be tested without a screen, and lets the macro and
 * batch entry points reach the same state the dialog produces rather than a
 * parallel copy of it.
 */
public final class MethodSelectionModel {

    /** Where the remembered selection lives between sessions. */
    public static final String PREFERENCE_KEY = "ocs.methods";

    /** Separator in the stored string. Not a comma: ids never contain it either,
     *  but a semicolon survives a spreadsheet round-trip a comma does not. */
    private static final String SEPARATOR = ";";

    private final EngineRegistry registry;
    private final EngineInputs inputs;
    private final Set<String> selected = new LinkedHashSet<String>();

    private boolean nullModel;
    private boolean agreement;
    private boolean thresholdSweep;
    private int permutations = ocs.nullmodel.NullModelRunner.DEFAULT_PERMUTATIONS;
    private long seed = ocs.nullmodel.NullModelRunner.DEFAULT_SEED;

    private MethodSelectionModel(EngineRegistry registry, EngineInputs inputs) {
        this.registry = registry;
        this.inputs = inputs;
    }

    /**
     * A model opened on the default preset, narrowed to what this data supports.
     *
     * <p>Narrowed rather than left ticked-and-failing: a method whose inputs are
     * absent is offered nowhere, so the user cannot select a run that dies after
     * they commit to it.
     */
    public static MethodSelectionModel openedOn(EngineRegistry registry,
            EngineInputs inputs) {
        MethodSelectionModel model = new MethodSelectionModel(registry, inputs);
        model.apply(Preset.defaultPreset());
        return model;
    }

    /**
     * A model restored from a remembered selection.
     *
     * <p>Falls back to the default preset when the stored string names nothing
     * runnable — after an upgrade that renamed a method, or simply on data of a
     * different kind. Silently opening empty would look like a broken dialog.
     */
    public static MethodSelectionModel restoredFrom(EngineRegistry registry,
            EngineInputs inputs, String remembered) {
        MethodSelectionModel model = new MethodSelectionModel(registry, inputs);
        if (remembered != null && !remembered.trim().isEmpty()) {
            String[] ids = remembered.split(SEPARATOR);
            for (int i = 0; i < ids.length; i++) {
                String id = ids[i].trim();
                if (!id.isEmpty() && registry.has(id) && model.isRunnable(id)) {
                    model.selected.add(id);
                }
            }
        }
        if (model.selected.isEmpty()) {
            model.apply(Preset.defaultPreset());
        }
        return model;
    }

    // ---------- selection ----------

    public boolean isSelected(String engineId) {
        return selected.contains(engineId);
    }

    /**
     * @throws IllegalArgumentException if the engine's inputs are absent. The
     *         dialog greys those rows, so reaching this means something bypassed
     *         the interface, and failing loudly beats a run that dies later.
     */
    public void setSelected(String engineId, boolean on) {
        if (!registry.has(engineId)) {
            throw new IllegalArgumentException("no engine '" + engineId + "'");
        }
        if (on && !isRunnable(engineId)) {
            throw new IllegalArgumentException("'" + engineId
                    + "' cannot run on this data: " + whyNotRunnable(engineId));
        }
        if (on) {
            selected.add(engineId);
        } else {
            selected.remove(engineId);
        }
    }

    /** In registration order, which is the order the dialog and table use. */
    public List<String> selectedIds() {
        List<String> ordered = new ArrayList<String>();
        List<ColocEngine> all = registry.all();
        for (int i = 0; i < all.size(); i++) {
            if (selected.contains(all.get(i).id())) {
                ordered.add(all.get(i).id());
            }
        }
        return Collections.unmodifiableList(ordered);
    }

    public List<ColocEngine> selectedEngines() {
        List<ColocEngine> engines = new ArrayList<ColocEngine>();
        List<String> ids = selectedIds();
        for (int i = 0; i < ids.size(); i++) {
            engines.add(registry.byId(ids.get(i)));
        }
        return Collections.unmodifiableList(engines);
    }

    public int selectedCount() {
        return selected.size();
    }

    /** Nothing ticked is a legitimate state, and Run must refuse it. */
    public boolean isEmpty() {
        return selected.isEmpty();
    }

    // ---------- what this data supports ----------

    public boolean isRunnable(String engineId) {
        return registry.has(engineId)
                && inputs.missing(registry.byId(engineId).requires()).isEmpty();
    }

    /**
     * Why a row is greyed, phrased for the tooltip.
     *
     * <p>Present because "greyed out with no explanation" is the single most
     * common way a dialog wastes somebody's afternoon.
     */
    public String whyNotRunnable(String engineId) {
        List<InputRequirement> missing =
                inputs.missing(registry.byId(engineId).requires());
        if (missing.isEmpty()) {
            return "";
        }
        StringBuilder reason = new StringBuilder("needs ");
        for (int i = 0; i < missing.size(); i++) {
            if (i > 0) {
                reason.append(i == missing.size() - 1 ? " and " : ", ");
            }
            reason.append(missing.get(i).displayName());
        }
        return reason.toString();
    }

    // ---------- presets ----------

    /**
     * Replaces the selection with a preset, dropping anything this data cannot
     * run.
     *
     * <p>Replaces rather than adds. A preset that merged into the current
     * selection would make picking two presets in a row produce a set matching
     * neither, and the name at the top would then be a lie.
     */
    public void apply(Preset preset) {
        selected.clear();
        List<String> ids = preset.engineIds();
        for (int i = 0; i < ids.size(); i++) {
            if (registry.has(ids.get(i)) && isRunnable(ids.get(i))) {
                selected.add(ids.get(i));
            }
        }
        nullModel = preset.runsNullModel();
        agreement = preset.runsAgreement();
        thresholdSweep = preset.runsThresholdSweep();
    }

    /**
     * The preset the current selection exactly matches, or null for "Custom".
     *
     * <p>Compared against the preset <i>narrowed to what this data supports</i>,
     * so loading label images without intensity images and picking the intensity
     * preset still reads as that preset rather than flipping to Custom for a
     * reason the user did not cause.
     */
    public Preset matchingPreset() {
        List<Preset> presets = Preset.shipped();
        for (int i = 0; i < presets.size(); i++) {
            Set<String> runnable = new LinkedHashSet<String>();
            List<String> ids = presets.get(i).engineIds();
            for (int j = 0; j < ids.size(); j++) {
                if (registry.has(ids.get(j)) && isRunnable(ids.get(j))) {
                    runnable.add(ids.get(j));
                }
            }
            if (!runnable.isEmpty() && runnable.equals(selected)) {
                return presets.get(i);
            }
        }
        return null;
    }

    // ---------- diagnostics ----------

    /**
     * Whether these inputs can feed the chance test at all.
     *
     * <p>It scatters objects inside the region ROI and refuses to default to the
     * whole frame, so without a region it cannot run whatever methods are ticked.
     */
    public boolean canRunNullModel() {
        return inputs.has(InputRequirement.ROI_DOMAIN);
    }

    /** Why the chance test is greyed, for its tooltip; empty when it is not. */
    public String whyNoNullModel() {
        return canRunNullModel() ? ""
                : "Needs a region ROI: choose one in the Inputs dialog. The chance"
                        + " test scatters objects only inside the region.";
    }

    public boolean runsNullModel() {
        return nullModel;
    }

    public void setRunsNullModel(boolean on) {
        this.nullModel = on;
    }

    public boolean runsAgreement() {
        return agreement;
    }

    public void setRunsAgreement(boolean on) {
        this.agreement = on;
    }

    public boolean runsThresholdSweep() {
        return thresholdSweep;
    }

    public void setRunsThresholdSweep(boolean on) {
        this.thresholdSweep = on;
    }

    /**
     * Whether the run classifies the methods (Discovery).
     *
     * <p>Derived, not stored: the chooser has no switch of its own for it. It is
     * on while the selection is the Discovery preset's and all three checks it
     * reads are on, so touching a method or a check turns it off together with
     * the preset name.
     */
    public boolean runsDiscovery() {
        Preset matching = matchingPreset();
        return matching != null && matching.runsDiscovery()
                && nullModel && agreement && thresholdSweep;
    }

    public int permutations() {
        return permutations;
    }

    /** @throws IllegalArgumentException if fewer than one */
    public void setPermutations(int count) {
        if (count < 1) {
            throw new IllegalArgumentException(
                    "a chance test needs at least one shuffle, not " + count);
        }
        this.permutations = count;
    }

    /**
     * The nominal smallest two-sided <i>p</i> the chance test could report,
     * {@code min(1, 2 / (shuffles + 1))}, before ties raise it further.
     *
     * <p>Shown beside the field because it is the one thing about this setting a
     * user cannot work out by looking at it. At a hundred shuffles the nominal
     * floor is about 0.0198, and a tied count statistic can floor higher still.
     * Reporting the one-sided 0.0099 here would promise resolution the
     * two-sided test cannot produce.
     */
    public double smallestReachableP() {
        return Math.min(1.0, 2.0 / (permutations + 1.0));
    }

    public long seed() {
        return seed;
    }

    /**
     * @throws IllegalArgumentException if the value cannot survive being recorded
     *         as a double. Beyond 2^53 the seed written into the run record is a
     *         different number from the one that ran, so the record would replay
     *         a different result while looking like a faithful one.
     */
    public void setSeed(long value) {
        if (!isRecordableExactly(value)) {
            throw new IllegalArgumentException("seed " + value + " cannot be "
                    + "recorded exactly; use one of magnitude at most 2^53");
        }
        this.seed = value;
    }

    /**
     * Whether a seed survives being written down as a double.
     *
     * <p>Checked by magnitude, not by casting to double and back. The round trip
     * looks exact for {@code Long.MAX_VALUE} — the cast to double rounds up and
     * the cast back saturates to the same number — so the obvious test passes for
     * the very value most likely to be wrong.
     */
    public static boolean isRecordableExactly(long seed) {
        return Math.abs(seed) <= MAX_EXACT_SEED;
    }

    /** 2^53: above this, consecutive integers are no longer distinct as doubles. */
    public static final long MAX_EXACT_SEED = 1L << 53;

    // ---------- cost ----------

    /**
     * Unitless weight where 1 is roughly one pass over the voxels.
     *
     * <p>Shown live beside the Run button rather than reported afterwards. The
     * spread across the shipped presets is about two orders of magnitude, and a
     * user who cannot see that before pressing Run will read a slow selection as
     * a slow plugin.
     */
    public double estimatedCost() {
        double base = EngineRegistry.estimateCost(selectedEngines(), inputs);
        return base * diagnosticMultiplier();
    }

    /**
     * How much the diagnostics multiply the engine cost.
     *
     * <p>The null model dominates everything: it re-runs every selected engine
     * once per permutation. Agreement and the sweep are counted too, at their
     * real weight rather than at zero — the sweep is another twenty-one runs of
     * each threshold-bearing engine, which is not free just because it is not
     * the headline.
     */
    public double diagnosticMultiplier() {
        double multiplier = 1.0;
        if (nullModel) {
            // The chosen number of shuffles, not the default one. Raising it from
            // a hundred to a thousand is a tenfold longer run, and an estimate
            // that ignored the field would understate it by that factor.
            multiplier += permutations;
        }
        if (thresholdSweep) {
            multiplier += 21.0;
        }
        // Agreement is arithmetic over results already computed; it adds no
        // engine runs and is deliberately not charged for.
        return multiplier;
    }

    /** The string to remember for next time. */
    public String toPreferenceValue() {
        StringBuilder value = new StringBuilder();
        List<String> ids = selectedIds();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                value.append(SEPARATOR);
            }
            value.append(ids.get(i));
        }
        return value.toString();
    }
}
