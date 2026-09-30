package ocs.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every method the plugin knows about.
 *
 * <p>The dialog, the macro parser and the concordance matrix are all generated
 * from this, so adding a method means writing one engine class and adding one
 * line to {@link #createDefault()}.
 *
 * <p>Registration is explicit rather than reflective on purpose. Two reasons:
 * the shade plugin runs with {@code minimizeJar}, which keeps only classes it
 * can see referenced — a reflectively-registered engine would be silently
 * dropped from the shipped jar and only fail for users. And relocation rewrites
 * bytecode references but not strings, so a class name held as a string would
 * survive the build and break at runtime.
 */
public final class EngineRegistry {

    private final Map<String, ColocEngine> engines = new LinkedHashMap<String, ColocEngine>();

    /**
     * The registry as shipped.
     *
     * <p>Grows one line at a time as 03_BUILD_PLAN.md progresses. Currently the
     * complete object family (two engines wrapping cores that already exist and
     * four derived from the same {@code volcoloc-core} scan), both intensity
     * engines, and the spatial family.
     *
     * <p>Registration order is the order the dialog lists methods in and the
     * order columns appear in the consolidated table, so it is grouped by family
     * and cheapest first within each family, rather than alphabetical.
     */
    public static EngineRegistry createDefault() {
        EngineRegistry registry = new EngineRegistry();
        registry.register(new ocs.engine.object.CentroidCoincidenceEngine());
        registry.register(new ocs.engine.object.DistanceToleranceEngine());
        registry.register(new ocs.engine.object.VolumeOverlapEngine());
        registry.register(new ocs.engine.object.JaccardDiceEngine());
        registry.register(new ocs.engine.object.ContainmentEngine());
        registry.register(new ocs.engine.object.BoundingBoxEngine());
        registry.register(new ocs.engine.intensity.PerObjectIntensityEngine());
        registry.register(new ocs.engine.intensity.WholeImageIntensityEngine());
        registry.register(new ocs.engine.spatial.CrossGEngine());
        registry.register(new ocs.engine.spatial.CrossKEngine());
        registry.register(new ocs.engine.spatial.CrossLEngine());
        registry.register(new ocs.engine.spatial.CrossPairCorrelationEngine());
        registry.register(new ocs.engine.territory.TerritoryColocEngine());
        return registry;
    }

    /** Empty registry, for tests that want to control the engine set exactly. */
    public static EngineRegistry empty() {
        return new EngineRegistry();
    }

    public EngineRegistry register(ColocEngine engine) {
        validate(engine);
        ColocEngine existing = engines.put(engine.id(), engine);
        if (existing != null) {
            throw new IllegalStateException("duplicate engine id '" + engine.id()
                    + "': " + existing.getClass().getName()
                    + " and " + engine.getClass().getName());
        }
        return this;
    }

    public List<ColocEngine> all() {
        return Collections.unmodifiableList(new ArrayList<ColocEngine>(engines.values()));
    }

    public List<ColocEngine> family(EngineFamily family) {
        List<ColocEngine> matching = new ArrayList<ColocEngine>();
        for (ColocEngine engine : engines.values()) {
            if (engine.family() == family) {
                matching.add(engine);
            }
        }
        return matching;
    }

    /** @throws IllegalArgumentException if no engine has that id */
    public ColocEngine byId(String id) {
        ColocEngine engine = engines.get(id);
        if (engine == null) {
            throw new IllegalArgumentException("no engine with id '" + id
                    + "'; known ids are " + engines.keySet());
        }
        return engine;
    }

    public boolean has(String id) {
        return engines.containsKey(id);
    }

    public int size() {
        return engines.size();
    }

    /**
     * Engines the given data can actually run, so the dialog can grey out the
     * rest with a reason rather than failing after the user presses Run.
     */
    public List<ColocEngine> runnableWith(EngineInputs inputs) {
        List<ColocEngine> runnable = new ArrayList<ColocEngine>();
        for (ColocEngine engine : engines.values()) {
            if (inputs.missing(engine.requires()).isEmpty()) {
                runnable.add(engine);
            }
        }
        return runnable;
    }

    /**
     * Unitless cost estimate for running the given engines over the given data.
     *
     * <p>Feeds the pre-run estimate. Every engine is charged for every ordered
     * direction, symmetric or not: the two symmetric engines (Jaccard/Dice and
     * whole-image intensity) still compute both directions in full, because
     * each direction has its own rows and supporting columns. Charging them for
     * half, as this once did, under-reported them by half in the dialog.
     */
    public static double estimateCost(List<ColocEngine> selected, EngineInputs inputs) {
        int channels = inputs.channelCount();
        int orderedPairs = channels * (channels - 1);
        double total = 0.0;
        for (ColocEngine engine : selected) {
            total += engine.relativeCost() * orderedPairs;
        }
        return total;
    }

    /**
     * Registration-time checks. These are contract violations by an engine
     * author, not user errors, so they fail loudly at startup rather than
     * producing a subtly wrong table much later.
     */
    private void validate(ColocEngine engine) {
        if (engine == null) {
            throw new IllegalArgumentException("engine must not be null");
        }
        String id = engine.id();
        if (id == null || !id.matches("[a-z0-9]+(-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("engine id must be lower-case and "
                    + "hyphenated, was '" + id + "'");
        }
        if (engine.requires() == null
                || !engine.requires().contains(InputRequirement.LABEL_IMAGES)) {
            throw new IllegalArgumentException(
                    "engine '" + id + "' must require LABEL_IMAGES");
        }
        if (engine.relativeCost() <= 0.0) {
            throw new IllegalArgumentException(
                    "engine '" + id + "' must report a positive relative cost");
        }
        requireExactlyOnePrimaryColumn(engine, id);
    }

    /**
     * The primary column is what {@link ObjectScore#value()} carries, so a
     * missing or duplicated one would leave the null-model and concordance
     * layers reading an undocumented number.
     */
    private void requireExactlyOnePrimaryColumn(ColocEngine engine, String id) {
        List<ColumnSpec> columns = engine.columns();
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("engine '" + id + "' declares no columns");
        }
        int primaries = 0;
        for (ColumnSpec column : columns) {
            if (column.isPrimary()) {
                primaries++;
            }
        }
        if (primaries != 1) {
            throw new IllegalArgumentException("engine '" + id + "' must declare exactly "
                    + "one primary column, declared " + primaries);
        }
    }
}
