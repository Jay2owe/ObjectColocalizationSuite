package ocs.engine;

/**
 * The four kinds of colocalization measurement this plugin runs.
 *
 * <p>Used to group the dialog and to build the presets. It is deliberately not
 * used to change how an engine is called: the null-model layer and the
 * concordance matrix treat every family identically.
 */
public enum EngineFamily {

    /** Measures relationships between segmented objects. */
    OBJECT("Object-based"),

    /** Measures correlation between raw intensity channels. */
    INTENSITY("Intensity-based"),

    /** Measures spatial point-pattern relationships. */
    SPATIAL("Spatial statistics"),

    /**
     * Partitions space into one region per object and asks where the other
     * channel falls.
     *
     * <p>Separate from {@link #SPATIAL} despite a Voronoi tessellation being a
     * spatial construction, because the two produce different <i>shapes</i> of
     * answer: the spatial family emits a curve over radii and no per-object
     * value, this one emits one row per object and no curve. Grouping them
     * would put engines with no common output in one dialog section and one
     * preset.
     */
    TERRITORY("Territories");

    private final String displayName;

    EngineFamily(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
