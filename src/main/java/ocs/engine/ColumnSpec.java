package ocs.engine;

/**
 * One output column, declared by an engine before it runs.
 *
 * <p>Declared rather than discovered, so the consolidated table's schema is
 * known before a single voxel is read. That is what lets the dialog show what a
 * run will produce, lets the run-record file be written up front, and lets a
 * batch guarantee every image contributes the same columns.
 */
public final class ColumnSpec {

    private final String name;
    private final String unit;
    private final String description;
    private final boolean primary;
    private final ScaleKind scale;
    private final String[] categories;
    private final boolean partner;

    private ColumnSpec(String name, String unit, String description,
                       boolean primary, ScaleKind scale) {
        this(name, unit, description, primary, scale, null, false);
    }

    private ColumnSpec(String name, String unit, String description,
                       boolean primary, ScaleKind scale, String[] categories) {
        this(name, unit, description, primary, scale, categories, false);
    }

    private ColumnSpec(String name, String unit, String description,
                       boolean primary, ScaleKind scale, String[] categories,
                       boolean partner) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("column name must not be blank");
        }
        if (scale == null) {
            throw new IllegalArgumentException(
                    "column '" + name + "' must declare a ScaleKind");
        }
        this.name = name;
        this.unit = unit == null ? "" : unit;
        this.description = description == null ? "" : description;
        this.primary = primary;
        this.scale = scale;
        this.categories = categories;
        this.partner = partner;
    }

    /**
     * The engine's primary scalar column — the one carried in
     * {@link ObjectScore#value()}.
     *
     * <p>Exactly one column per engine is primary. {@link EngineRegistry}
     * enforces that at registration, because a missing or duplicated primary
     * would silently give the null-model and agreement layers the wrong number
     * to work with.
     *
     * @param scale what kind of quantity this is. Decides which agreement
     *              statistics may legitimately be computed against another
     *              engine — see {@link ScaleKind}
     */
    public static ColumnSpec primary(String name, String unit, String description,
                                     ScaleKind scale) {
        return new ColumnSpec(name, unit, description, true, scale);
    }

    /** An additional engine-specific column. Never read by the generic layers. */
    public static ColumnSpec of(String name, String unit, String description,
                                ScaleKind scale) {
        return new ColumnSpec(name, unit, description, false, scale);
    }

    /**
     * The column carrying {@link ObjectScore#partnerLabel()} — which target
     * object this row's object was matched against.
     *
     * <p>Declared rather than recognised by name. The table layer needs to know
     * which column the partner label already occupies so it does not write it
     * twice, and the obvious test — does the name end in "Partner" — is wrong:
     * containment's <b>Source Inside Partner</b> is a percentage, and matching on
     * the name silently dropped it from the table the first time this was
     * written. A column that disappears is not a visible failure, so the rule
     * that decides it must not be a guess.
     */
    public static ColumnSpec partner(String name, String description) {
        return new ColumnSpec(name, "", description, false, ScaleKind.COUNT,
                null, true);
    }

    /**
     * A column whose numbers are codes for named categories.
     *
     * <p>Engines transport per-object values as {@code double[]}, so a category
     * has to travel as an index into {@code categories}. That is fine inside the
     * engine layer and useless in a table: a reader who opens a CSV and finds
     * {@code 2} in a column called "Containment Class" has no way to learn what
     * 2 means, and the obvious guess — that bigger is more — is wrong for every
     * category column there will ever be.
     *
     * <p>Declaring the labels here rather than special-casing particular engines
     * in the table layer keeps the table generic: it renders words wherever an
     * engine says its numbers are codes, and nothing in the table layer needs to
     * know which engines those are.
     *
     * @param categories the label for code 0, 1, 2 … in order. Copied, so a
     *                   caller's array cannot be edited out from under the spec
     */
    public static ColumnSpec categorical(String name, String description,
                                         String[] categories) {
        if (categories == null || categories.length == 0) {
            throw new IllegalArgumentException("column '" + name
                    + "' is categorical, so it must name its categories");
        }
        // UNBOUNDED, always. A category code must never reach the agreement
        // layer's ordinal or interval statistics: the mean of "Disjoint" and
        // "Partial" is not a quantity, and Spearman's rho over codes would
        // report a correlation between two arbitrary numberings.
        return new ColumnSpec(name, "", description, false, ScaleKind.UNBOUNDED,
                categories.clone());
    }

    public String name() {
        return name;
    }

    /** Unit string, or empty for dimensionless measures such as a ratio. */
    public String unit() {
        return unit;
    }

    public String description() {
        return description;
    }

    public boolean isPrimary() {
        return primary;
    }

    /**
     * What kind of quantity this column holds. Gates which agreement statistics
     * the agreement layer may compute against another engine.
     */
    public ScaleKind scale() {
        return scale;
    }

    /** Whether this column's numbers are codes for named categories. */
    public boolean isCategorical() {
        return categories != null;
    }

    /** Whether this column carries {@link ObjectScore#partnerLabel()}. */
    public boolean isPartner() {
        return partner;
    }

    /**
     * The label for a category code, or {@code null} when this column is not
     * categorical or the code names no category.
     *
     * <p>An out-of-range or non-integral code returns {@code null} rather than
     * throwing or guessing. NaN is the ordinary case — it is how an engine says
     * "no category applies here", which is a different statement from any of the
     * categories it declares — and a code past the end means the engine and the
     * spec disagree, which the table should show as a blank cell rather than
     * relabel as whatever happens to sit at that index.
     */
    public String categoryFor(double code) {
        if (categories == null || Double.isNaN(code) || Double.isInfinite(code)) {
            return null;
        }
        long index = Math.round(code);
        if (index != (long) code || index < 0L || index >= categories.length) {
            return null;
        }
        return categories[(int) index];
    }

    /** Column name with its unit appended, for table headers. */
    public String header() {
        return unit.isEmpty() ? name : name + " (" + unit + ")";
    }

    @Override
    public String toString() {
        return "ColumnSpec[" + header() + (primary ? ", primary]" : "]");
    }
}
