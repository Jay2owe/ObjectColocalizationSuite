package ocs.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A measure evaluated over a swept axis rather than over objects.
 *
 * <p>The spatial family produces these: cross-K at twenty radii, with a
 * randomized envelope around each one. There is no per-object number to report,
 * because the statistic is a property of the two point patterns as a whole. The
 * unit of result is (source channel, target channel, function) → a set of
 * parallel vectors sharing one x axis.
 *
 * <p>Everything here is named, never positional. Six vectors of equal length is
 * exactly the shape where a positional contract silently transposes two of them
 * and no test can tell — the same failure that let a containment engine read its
 * two percentages the wrong way round undetected.
 *
 * <p>Vectors all share the length of {@link #x()}, checked at build time so a
 * mismatched series fails in the engine that produced it rather than in a plot
 * that renders half a curve without complaint.
 */
public final class CurveSeries {

    /** Nothing was wrong. */
    public static final String STATUS_OK = "OK";

    private final String xName;
    private final String xUnit;
    private final double[] x;
    private final Map<String, double[]> series;
    private final Map<String, Double> scalars;
    private final String status;

    private CurveSeries(Builder builder) {
        this.xName = builder.xName;
        this.xUnit = builder.xUnit;
        this.x = copy(builder.x);
        this.series = Collections.unmodifiableMap(
                new LinkedHashMap<String, double[]>(builder.series));
        this.scalars = Collections.unmodifiableMap(
                new LinkedHashMap<String, Double>(builder.scalars));
        this.status = builder.status;
    }

    /**
     * @param xName the swept axis, as a column header — "Radius" for Ripley's K
     * @param xUnit its unit, or the empty string when dimensionless
     * @param x     the swept values; must be finite and strictly increasing,
     *              because every consumer assumes a curve can be plotted and
     *              interpolated without first being sorted
     */
    public static Builder over(String xName, String xUnit, double[] x) {
        return new Builder(xName, xUnit, x);
    }

    public String xName() {
        return xName;
    }

    public String xUnit() {
        return xUnit;
    }

    /** The swept axis. A copy — callers may not alter the curve in place. */
    public double[] x() {
        return copy(x);
    }

    public int length() {
        return x.length;
    }

    /** Series names in the order the engine declared them. */
    public List<String> seriesNames() {
        return Collections.unmodifiableList(new ArrayList<String>(series.keySet()));
    }

    /**
     * One named vector, of length {@link #length()}.
     *
     * @throws IllegalArgumentException if no series has that name, rather than
     *         returning null or an empty array — a misspelt series name is an
     *         author error, and silently reading zeros would put a flat line in
     *         a figure that no reviewer could distinguish from a real result
     */
    public double[] series(String name) {
        double[] found = series.get(name);
        if (found == null) {
            throw new IllegalArgumentException("no series '" + name
                    + "'; this curve carries " + series.keySet());
        }
        return copy(found);
    }

    public boolean hasSeries(String name) {
        return series.containsKey(name);
    }

    /** Whole-curve summaries — a global <i>p</i>, a maximum deviation, a seed. */
    public Map<String, Double> scalars() {
        return scalars;
    }

    /**
     * Whether the curve is trustworthy, in the engine's own vocabulary —
     * {@link #STATUS_OK}, or why not: too few points, no valid radii, an
     * incomplete envelope. Carried rather than thrown, because a run over five
     * channel pairs should report the one that could not be computed instead of
     * losing the four that could.
     */
    public String status() {
        return status;
    }

    public boolean isOk() {
        return STATUS_OK.equals(status);
    }

    private static double[] copy(double[] values) {
        double[] copied = new double[values.length];
        System.arraycopy(values, 0, copied, 0, values.length);
        return copied;
    }

    public static final class Builder {

        private final String xName;
        private final String xUnit;
        private final double[] x;
        private final Map<String, double[]> series = new LinkedHashMap<String, double[]>();
        private final Map<String, Double> scalars = new LinkedHashMap<String, Double>();
        private String status = STATUS_OK;

        private Builder(String xName, String xUnit, double[] x) {
            if (xName == null || xName.trim().isEmpty()) {
                throw new IllegalArgumentException("the swept axis must be named");
            }
            if (x == null || x.length == 0) {
                throw new IllegalArgumentException(
                        "a curve needs at least one point on its axis");
            }
            requireFiniteAndIncreasing(xName, x);
            this.xName = xName;
            this.xUnit = xUnit == null ? "" : xUnit;
            this.x = copy(x);
        }

        public Builder series(String name, double[] values) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("a series must be named");
            }
            if (values == null) {
                throw new IllegalArgumentException("series '" + name + "' is null");
            }
            if (values.length != x.length) {
                throw new IllegalArgumentException("series '" + name + "' has "
                        + values.length + " values but the " + xName + " axis has "
                        + x.length + "; parallel vectors must share a length");
            }
            if (series.containsKey(name)) {
                throw new IllegalStateException("series '" + name + "' declared twice");
            }
            series.put(name, copy(values));
            return this;
        }

        public Builder scalar(String name, double value) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("a scalar must be named");
            }
            if (scalars.containsKey(name)) {
                throw new IllegalStateException("scalar '" + name + "' declared twice");
            }
            scalars.put(name, Double.valueOf(value));
            return this;
        }

        public Builder status(String status) {
            this.status = status == null || status.trim().isEmpty() ? STATUS_OK : status;
            return this;
        }

        public CurveSeries build() {
            return new CurveSeries(this);
        }

        /**
         * A curve whose axis is unsorted or repeats a value cannot be plotted or
         * interpolated without a caller first discovering the problem, and by
         * then it is in a figure. Rejected here instead.
         */
        private static void requireFiniteAndIncreasing(String xName, double[] x) {
            for (int i = 0; i < x.length; i++) {
                if (Double.isNaN(x[i]) || Double.isInfinite(x[i])) {
                    throw new IllegalArgumentException(xName + "[" + i
                            + "] is " + x[i] + "; the axis must be finite");
                }
                if (i > 0 && x[i] <= x[i - 1]) {
                    throw new IllegalArgumentException(xName + " must increase strictly, but ["
                            + (i - 1) + "] = " + x[i - 1] + " and [" + i + "] = " + x[i]);
                }
            }
        }

        private static double[] copy(double[] values) {
            double[] copied = new double[values.length];
            System.arraycopy(values, 0, copied, 0, values.length);
            return copied;
        }
    }
}
