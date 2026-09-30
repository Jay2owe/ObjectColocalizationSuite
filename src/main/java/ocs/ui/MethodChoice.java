package ocs.ui;

import ocs.engine.ColocEngine;
import ocs.engine.EngineRegistry;
import ocs.engine.ThresholdBearing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What somebody asked for: which methods, at which settings, with which checks.
 *
 * <p>Like an order slip. The dialog writes one and hands it over; a macro line or
 * a batch script writes the same slip without a dialog ever opening. Everything
 * downstream reads the slip and never asks where it came from, which is what
 * stops the macro path and the interactive path drifting into two different
 * analyses that happen to share a name.
 *
 * <p>Immutable, and holds no engines — only their identifiers and settings. An
 * engine is a live object with a thread pool behind it; a slip is something you
 * can print, store in preferences, or put in a results file.
 */
public final class MethodChoice {

    private final List<String> engineIds;
    private final Map<String, Double> thresholds;
    private final boolean nullModel;
    private final boolean agreement;
    private final boolean thresholdSweep;
    private final boolean discovery;
    private final int permutations;
    private final long seed;

    MethodChoice(List<String> engineIds, Map<String, Double> thresholds,
            boolean nullModel, boolean agreement, boolean thresholdSweep,
            boolean discovery, int permutations, long seed) {
        this.engineIds = Collections.unmodifiableList(
                new ArrayList<String>(engineIds));
        this.thresholds = Collections.unmodifiableMap(
                new LinkedHashMap<String, Double>(thresholds));
        this.nullModel = nullModel;
        this.agreement = agreement;
        this.thresholdSweep = thresholdSweep;
        this.discovery = discovery;
        this.permutations = permutations;
        this.seed = seed;
    }

    /** Everything showing on the chooser at the moment Run was pressed. */
    public static MethodChoice from(MethodSelectionPanel panel) {
        MethodSelectionModel model = panel.model();
        return new MethodChoice(model.selectedIds(), panel.thresholds(),
                model.runsNullModel(), model.runsAgreement(),
                model.runsThresholdSweep(), model.runsDiscovery(),
                model.permutations(), model.seed());
    }

    /** In registration order, matching the chooser and every output table. */
    public List<String> engineIds() {
        return engineIds;
    }

    /** Only for methods that have a setting; the others are simply absent. */
    public Map<String, Double> thresholds() {
        return thresholds;
    }

    /**
     * The chosen setting, or NaN where the method has none.
     *
     * <p>NaN rather than 0, because 0 is a perfectly valid overlap threshold and
     * would be indistinguishable from "this method has no threshold".
     */
    public double thresholdFor(String engineId) {
        Double value = thresholds.get(engineId);
        return value == null ? Double.NaN : value.doubleValue();
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

    /** Whether the methods are classified; see {@link MethodSelectionModel#runsDiscovery()}. */
    public boolean runsDiscovery() {
        return discovery;
    }

    /** How many shuffles the chance test uses. Meaningless unless it is running. */
    public int permutations() {
        return permutations;
    }

    /**
     * The seed, carried whether or not the chance test is running.
     *
     * <p>Carried unconditionally because it goes into the run record either way,
     * and a record whose seed field appears only sometimes is a record nobody
     * trusts to replay.
     */
    public long seed() {
        return seed;
    }

    public boolean isEmpty() {
        return engineIds.isEmpty();
    }

    /**
     * The engines to run, each already built at the setting that was chosen.
     *
     * <p>This is the only place a threshold is applied. The registry's own copy of
     * an engine keeps its shipped default forever, so anything that took engines
     * straight from the registry and ignored the slip would silently run the
     * defaults while the report described the user's numbers.
     *
     * @throws IllegalArgumentException if a setting is outside what the engine
     *         admits. The chooser blocks that before Run, so reaching it means a
     *         macro line asked for something impossible, and failing here beats
     *         quantifying it.
     */
    public List<ColocEngine> configuredEngines(EngineRegistry registry) {
        List<ColocEngine> engines = new ArrayList<ColocEngine>();
        for (int i = 0; i < engineIds.size(); i++) {
            String id = engineIds.get(i);
            ColocEngine engine = registry.byId(id);
            Double setting = thresholds.get(id);
            if (setting != null && engine instanceof ThresholdBearing) {
                engine = ((ThresholdBearing) engine)
                        .withThreshold(setting.doubleValue());
            }
            engines.add(engine);
        }
        return Collections.unmodifiableList(engines);
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < engineIds.size(); i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(engineIds.get(i));
            Double setting = thresholds.get(engineIds.get(i));
            if (setting != null) {
                text.append('=').append(setting);
            }
        }
        return "MethodChoice[" + text + "]";
    }
}
