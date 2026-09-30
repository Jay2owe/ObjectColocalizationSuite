package ocs.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one engine produced, for every direction it was asked about.
 *
 * <p>Built through {@link Builder} so an engine adds directions as it computes
 * them and the result is immutable once handed back — the null-model layer runs
 * an engine hundreds of times and must not be able to see a half-built result.
 *
 * <h2>Three shapes, because measures come in three shapes</h2>
 *
 * <p>The first version of this class carried only per-object primaries, on the
 * assumption that every colocalization measure answers "what happened to
 * <i>this</i> object". Building the first three families disproved it from three
 * independent directions, so a result now has three channels. An engine fills
 * whichever apply and leaves the rest empty.
 *
 * <ul>
 *   <li><b>Object scores</b> — one {@link ObjectScore} per object, carrying the
 *       primary scalar. Object and per-object intensity engines.
 *   <li><b>Supporting columns</b> — named vectors positionally aligned with the
 *       object scores, for everything an engine declares in
 *       {@link ColumnSpec} beyond its primary: partner counts, shared voxels, a
 *       containment class. Before this existed those columns had no route from
 *       the engine to the table at all, and a containment engine reading its two
 *       percentages the wrong way round could not be caught by any test.
 *   <li><b>Whole-direction scalars</b> — one number per channel pair, for
 *       measures of the field rather than of the objects in it. Whole-image
 *       Pearson is the case. Reporting these as a constant repeated across every
 *       object would break the agreement and null layers <i>quietly</i>: rank
 *       correlation on a constant column is undefined, and permuting a constant
 *       returns enrichment 1.0 and <i>p</i> 1.0 every time, which reads exactly
 *       like the honest verdict "uninformative here".
 *   <li><b>Curves</b> — a {@link CurveSeries} per direction, for measures swept
 *       over an axis. Cross-K at twenty radii with an envelope is six parallel
 *       vectors and eight scalars attached to a channel pair, with objects
 *       absent entirely.
 * </ul>
 *
 * <p>Keeping these distinct is what lets the generic layers ask <i>whether</i> a
 * measure has a per-object value rather than discovering it produced none. An
 * empty score list alone cannot tell "this measure is whole-image" from "this
 * ran and found no objects", and those need opposite treatment: the first is
 * <i>not applicable</i>, the second is <i>no data</i>.
 */
public final class EngineResult {

    private final String engineId;
    private final Map<DirectionKey, List<ObjectScore>> scores;
    private final Map<DirectionKey, Map<String, double[]>> supporting;
    private final Map<DirectionKey, Map<String, Double>> wholeDirection;
    private final Map<DirectionKey, CurveSeries> curves;

    private EngineResult(Builder builder) {
        this.engineId = builder.engineId;
        this.scores = Collections.unmodifiableMap(builder.scores);
        this.supporting = Collections.unmodifiableMap(builder.supporting);
        this.wholeDirection = Collections.unmodifiableMap(builder.wholeDirection);
        this.curves = Collections.unmodifiableMap(builder.curves);
    }

    public static Builder forEngine(String engineId) {
        return new Builder(engineId);
    }

    public String engineId() {
        return engineId;
    }

    /** In the order the engine produced them. */
    public List<DirectionKey> directions() {
        return Collections.unmodifiableList(
                new ArrayList<DirectionKey>(scores.keySet()));
    }

    /**
     * @return the per-object scores for one direction, or an empty list if the
     *         engine did not report that direction
     */
    public List<ObjectScore> scores(DirectionKey direction) {
        List<ObjectScore> found = scores.get(direction);
        return found == null ? Collections.<ObjectScore>emptyList() : found;
    }

    /**
     * The primary scalar for one direction as a plain array, which is the form
     * the null-model and concordance layers want.
     *
     * <p>May contain {@link Double#NaN} where the measure was undefined for an
     * object. Callers must skip those rather than propagate them — one NaN in a
     * mean makes the whole mean NaN, and a NaN enrichment ratio in a results
     * table reads as a bug rather than as "not applicable".
     */
    public double[] values(DirectionKey direction) {
        List<ObjectScore> list = scores(direction);
        double[] values = new double[list.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = list.get(i).value();
        }
        return values;
    }

    /** How many objects this engine judged coincident in one direction. */
    public int coincidentCount(DirectionKey direction) {
        int count = 0;
        for (ObjectScore score : scores(direction)) {
            if (score.isCoincident()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Coincident objects as a percentage of objects measured in this direction.
     *
     * <p>This is the raw number the plan argues is meaningless on its own — the
     * "40% of objects colocalize" that may be below chance in dense tissue. It
     * is reported, but the null-model layer is what makes it interpretable.
     */
    public double percentCoincident(DirectionKey direction) {
        int total = scores(direction).size();
        return total == 0 ? Double.NaN : 100.0 * coincidentCount(direction) / total;
    }

    /**
     * A supporting column's values for one direction, positionally aligned with
     * {@link #scores(DirectionKey)} — index <i>i</i> belongs to score <i>i</i>.
     *
     * <p>Columns are addressed by {@link ColumnSpec#name()} rather than by
     * position on purpose. A positional contract over several same-typed columns
     * is precisely what allows two of them to be transposed with every test
     * still passing.
     *
     * @return an empty array where the engine declared the column but reported
     *         no values for this direction
     * @throws IllegalArgumentException if the column name is unknown to this
     *         result, which is an engine-author error rather than a data case
     */
    public double[] supporting(DirectionKey direction, String columnName) {
        Map<String, double[]> columns = supporting.get(direction);
        if (columns == null || columns.isEmpty()) {
            return new double[0];
        }
        double[] found = columns.get(columnName);
        if (found == null) {
            throw new IllegalArgumentException("engine '" + engineId
                    + "' reported no column '" + columnName + "' for "
                    + direction.label() + "; it reported " + columns.keySet());
        }
        double[] copy = new double[found.length];
        System.arraycopy(found, 0, copy, 0, found.length);
        return copy;
    }

    /** Supporting column names reported for one direction, in engine order. */
    public List<String> supportingNames(DirectionKey direction) {
        Map<String, double[]> columns = supporting.get(direction);
        return columns == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(columns.keySet()));
    }

    /**
     * Scalars produced for the direction as a whole rather than per object.
     *
     * <p>Empty for every per-object engine, which is how a consumer tells the
     * two kinds apart without knowing any engine by name.
     */
    public Map<String, Double> wholeDirection(DirectionKey direction) {
        Map<String, Double> found = wholeDirection.get(direction);
        return found == null ? Collections.<String, Double>emptyMap() : found;
    }

    /** @return the swept-axis result for one direction, or null if there is none */
    public CurveSeries curve(DirectionKey direction) {
        return curves.get(direction);
    }

    /**
     * True when this engine measures the field rather than the objects in it.
     *
     * <p>The disambiguation an empty score list cannot make on its own: with
     * this true, no per-object value exists and Discovery should class the
     * method <i>not applicable</i>; with it false, an empty score list means the
     * engine genuinely found nothing, which is data.
     */
    public boolean isWholeDirection() {
        return !wholeDirection.isEmpty();
    }

    /** True when this engine reported a swept curve for at least one direction. */
    public boolean hasCurves() {
        return !curves.isEmpty();
    }

    /** True when at least one direction carries per-object scores. */
    public boolean hasObjectScores() {
        for (List<ObjectScore> list : scores.values()) {
            if (!list.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public static final class Builder {

        private final String engineId;
        private final Map<DirectionKey, List<ObjectScore>> scores =
                new LinkedHashMap<DirectionKey, List<ObjectScore>>();
        private final Map<DirectionKey, Map<String, double[]>> supporting =
                new LinkedHashMap<DirectionKey, Map<String, double[]>>();
        private final Map<DirectionKey, Map<String, Double>> wholeDirection =
                new LinkedHashMap<DirectionKey, Map<String, Double>>();
        private final Map<DirectionKey, CurveSeries> curves =
                new LinkedHashMap<DirectionKey, CurveSeries>();

        private Builder(String engineId) {
            if (engineId == null || engineId.trim().isEmpty()) {
                throw new IllegalArgumentException("engine id must not be blank");
            }
            this.engineId = engineId;
        }

        public Builder direction(DirectionKey direction, List<ObjectScore> objectScores) {
            if (direction == null) {
                throw new IllegalArgumentException("direction must not be null");
            }
            if (scores.containsKey(direction)) {
                throw new IllegalStateException(
                        "direction " + direction.label() + " reported twice by " + engineId);
            }
            scores.put(direction, Collections.unmodifiableList(
                    new ArrayList<ObjectScore>(objectScores)));
            return this;
        }

        /**
         * One supporting column for a direction already reported through
         * {@link #direction}.
         *
         * <p>The length check is the point of this method. A supporting column
         * one element short of its score list would misalign every object after
         * the gap — a whole table of confidently wrong partner labels, with no
         * exception anywhere. Caught here, in the engine that wrote it.
         */
        public Builder supporting(DirectionKey direction, String columnName, double[] values) {
            List<ObjectScore> objectScores = scores.get(direction);
            if (objectScores == null) {
                throw new IllegalStateException("engine '" + engineId + "' reported column '"
                        + columnName + "' for " + direction.label()
                        + " before reporting that direction's scores");
            }
            if (columnName == null || columnName.trim().isEmpty()) {
                throw new IllegalArgumentException("a supporting column must be named");
            }
            if (values == null) {
                throw new IllegalArgumentException("column '" + columnName + "' is null");
            }
            if (values.length != objectScores.size()) {
                throw new IllegalArgumentException("column '" + columnName + "' of "
                        + direction.label() + " has " + values.length + " values but "
                        + objectScores.size() + " objects were scored; a supporting column "
                        + "must align with its scores position by position");
            }
            Map<String, double[]> columns = supporting.get(direction);
            if (columns == null) {
                columns = new LinkedHashMap<String, double[]>();
                supporting.put(direction, columns);
            }
            if (columns.containsKey(columnName)) {
                throw new IllegalStateException("column '" + columnName + "' of "
                        + direction.label() + " reported twice by " + engineId);
            }
            double[] copy = new double[values.length];
            System.arraycopy(values, 0, copy, 0, values.length);
            columns.put(columnName, copy);
            return this;
        }

        /** One scalar describing the direction as a whole rather than an object. */
        public Builder wholeDirection(DirectionKey direction, String name, double value) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("a whole-direction value must be named");
            }
            Map<String, Double> values = wholeDirection.get(direction);
            if (values == null) {
                values = new LinkedHashMap<String, Double>();
                wholeDirection.put(direction, values);
            }
            if (values.containsKey(name)) {
                throw new IllegalStateException("whole-direction value '" + name + "' of "
                        + direction.label() + " reported twice by " + engineId);
            }
            values.put(name, Double.valueOf(value));
            return this;
        }

        /** The swept-axis result for one direction. */
        public Builder curve(DirectionKey direction, CurveSeries series) {
            if (series == null) {
                throw new IllegalArgumentException("curve of "
                        + direction.label() + " is null");
            }
            if (curves.containsKey(direction)) {
                throw new IllegalStateException("curve of " + direction.label()
                        + " reported twice by " + engineId);
            }
            curves.put(direction, series);
            return this;
        }

        public EngineResult build() {
            Map<DirectionKey, Map<String, Double>> frozenWhole =
                    new LinkedHashMap<DirectionKey, Map<String, Double>>();
            for (Map.Entry<DirectionKey, Map<String, Double>> entry
                    : wholeDirection.entrySet()) {
                frozenWhole.put(entry.getKey(),
                        Collections.unmodifiableMap(entry.getValue()));
            }
            wholeDirection.clear();
            wholeDirection.putAll(frozenWhole);
            return new EngineResult(this);
        }
    }
}
