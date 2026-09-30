package ocs.engine.spatial;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.process.ImageProcessor;
import ocs.engine.EngineInputs;
import sc.fiji.opa.core.spatial.RectangularWindow;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The label images of one run, reduced to the one thing point-pattern statistics
 * can consume: a calibrated 2D point per object, and the rectangle they live in.
 *
 * <p>This class exists because {@link EngineInputs} carries label <i>images</i>
 * and {@code opa-core}'s spatial package takes {@code double[n][2]} in real units
 * inside a {@link RectangularWindow}. Nothing in the shared scaffold bridged the
 * two, and the bridge is not a one-liner: it decides where a point is, which unit
 * it is in, what the window is, and what happens to an object whose centre falls
 * outside that window. Each of those is a place a wrong answer could look
 * perfectly reasonable.
 *
 * <h2>Three decisions worth knowing before reading a spatial result</h2>
 *
 * <p><b>Points are calibrated, not pixel indices.</b> An object whose voxels span
 * columns 2 and 3 has its centre at pixel-edge coordinate 3.0, which on a 0.5 µm
 * pixel is 1.5 µm. Feeding the pixel index instead would leave every radius
 * silently in the wrong unit — the curve would still plot, the envelope would
 * still close, and every distance in it would be wrong by the pixel size. The
 * conversion matches {@code opa-core}'s own {@code CalibrationInfo.x(int)} so the
 * two modules place a point in the same spot.
 *
 * <p><b>The third dimension is dropped, not averaged into the answer.</b>
 * {@code opa-core}'s spatial statistics are planar. A 3D stack contributes each
 * object's <i>xy</i> centroid over all its slices, which is the honest planar
 * summary of a 3D object, and the <i>z</i> separation of two objects at the same
 * <i>xy</i> is invisible to every spatial engine. Two puncta 40 slices apart read
 * as coincident. That is a property of the family, not of this class, but this is
 * where it happens and it belongs in the run record.
 *
 * <p><b>The window is a rectangle, because {@code RectangularWindow} is.</b> The
 * region ROI set defines the randomization domain, and a domain the Monte Carlo
 * cannot represent is a domain it will simulate points outside of. A single
 * rectangular ROI is represented exactly; anything else — a polygon round a
 * cortical layer, several disjoint fields — is reduced to its bounding box, and
 * {@link #isRectangularDomain()} goes false so the engines can carry that on the
 * curve rather than let it pass as an ordinary result.
 */
public final class PointPattern {

    private final RectangularWindow window;
    private final List<double[][]> points;
    private final boolean rectangularDomain;
    private final String unit;
    private final int droppedOutsideWindow;

    private PointPattern(RectangularWindow window, List<double[][]> points,
                         boolean rectangularDomain, String unit,
                         int droppedOutsideWindow) {
        this.window = window;
        this.points = points;
        this.rectangularDomain = rectangularDomain;
        this.unit = unit;
        this.droppedOutsideWindow = droppedOutsideWindow;
    }

    /**
     * Reduces every label image to its calibrated object centres.
     *
     * @throws IllegalArgumentException if the domain and the image share no area,
     *         which is a loading mistake rather than a data case — every engine
     *         in the run would produce nothing and none of them could say why
     */
    public static PointPattern from(EngineInputs inputs) {
        if (inputs == null) {
            throw new IllegalArgumentException("inputs must not be null");
        }
        Calibration calibration = inputs.calibration();
        double pixelWidth = positiveOrOne(calibration == null ? 1.0 : calibration.pixelWidth);
        double pixelHeight = positiveOrOne(calibration == null ? 1.0 : calibration.pixelHeight);
        double xOrigin = finiteOrZero(calibration == null ? 0.0 : calibration.xOrigin);
        double yOrigin = finiteOrZero(calibration == null ? 0.0 : calibration.yOrigin);

        ImagePlus first = inputs.labelImages().get(0);
        Rectangle bounds = domainBounds(inputs, first.getWidth(), first.getHeight());
        RectangularWindow window = new RectangularWindow(
                (bounds.x - xOrigin) * pixelWidth,
                (bounds.y - yOrigin) * pixelHeight,
                (bounds.x + bounds.width - xOrigin) * pixelWidth,
                (bounds.y + bounds.height - yOrigin) * pixelHeight);

        List<double[][]> perChannel = new ArrayList<double[][]>();
        int dropped = 0;
        for (ImagePlus image : inputs.labelImages()) {
            Map<Integer, double[]> sums = accumulate(image);
            List<double[]> inside = new ArrayList<double[]>();
            for (double[] accumulator : sums.values()) {
                double x = (accumulator[0] / accumulator[2] + 0.5 - xOrigin) * pixelWidth;
                double y = (accumulator[1] / accumulator[2] + 0.5 - yOrigin) * pixelHeight;
                if (window.contains(x, y)) {
                    inside.add(new double[] {x, y});
                } else {
                    dropped++;
                }
            }
            perChannel.add(inside.toArray(new double[inside.size()][]));
        }

        String unit = calibration == null ? "pixel" : calibration.getUnit();
        if (unit == null || unit.trim().isEmpty()) {
            unit = "pixel";
        }
        return new PointPattern(window, perChannel,
                isSingleRectangle(inputs), unit, dropped);
    }

    /** The rectangle every point sits inside and the null model simulates into. */
    public RectangularWindow window() {
        return window;
    }

    public int channelCount() {
        return points.size();
    }

    /**
     * One channel's object centres, ascending by label so a run is bit-repeatable.
     *
     * <p>Order looks like a detail and is not. Cross-K sums a term per pair, and
     * floating-point addition is not associative, so two orderings of the same
     * points differ in the last bits — enough to break the bit-identity the
     * determinism contract requires. A copy, because {@code opa-core} is handed
     * these arrays directly and a caller must not be able to reach back into a
     * pattern two engines are sharing.
     */
    public double[][] points(int channelIndex) {
        double[][] source = points.get(channelIndex);
        double[][] copy = new double[source.length][];
        for (int i = 0; i < source.length; i++) {
            copy[i] = new double[] {source[i][0], source[i][1]};
        }
        return copy;
    }

    /**
     * False when the window is a bounding box rather than the domain itself.
     *
     * <p>The complete-spatial-randomness null scatters simulated points uniformly
     * over the whole window. Where the window is larger than the real domain, the
     * simulated intensity is too low and the envelope too narrow, which makes an
     * ordinary pattern look significant. Reported rather than corrected, because
     * correcting it needs a non-rectangular window {@code opa-core} does not have.
     */
    public boolean isRectangularDomain() {
        return rectangularDomain;
    }

    /** Calibration unit for the radius axis — "pixel" on uncalibrated images. */
    public String unit() {
        return unit;
    }

    /** Objects whose centre fell outside the domain window, summed over channels. */
    public int droppedOutsideWindow() {
        return droppedOutsideWindow;
    }

    /**
     * Per-label pixel-index sums, in a {@link TreeMap} so labels come back
     * ascending whatever order the voxels were visited in.
     */
    private static Map<Integer, double[]> accumulate(ImagePlus image) {
        Map<Integer, double[]> sums = new TreeMap<Integer, double[]>();
        ImageStack stack = image.getStack();
        int slices = image.getStackSize();
        int width = image.getWidth();
        int height = image.getHeight();
        for (int slice = 1; slice <= slices; slice++) {
            ImageProcessor processor = stack.getProcessor(slice);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    float raw = processor.getf(x, y);
                    if (!(raw > 0.0f)) {
                        continue;
                    }
                    int label = Math.round(raw);
                    if (label <= 0) {
                        continue;
                    }
                    Integer key = Integer.valueOf(label);
                    double[] accumulator = sums.get(key);
                    if (accumulator == null) {
                        accumulator = new double[3];
                        sums.put(key, accumulator);
                    }
                    accumulator[0] += x;
                    accumulator[1] += y;
                    accumulator[2] += 1.0;
                }
            }
        }
        return sums;
    }

    private static Rectangle domainBounds(EngineInputs inputs, int width, int height) {
        Rectangle image = new Rectangle(0, 0, width, height);
        List<Roi> domain = inputs.domain();
        if (domain.isEmpty()) {
            return image;
        }
        Rectangle union = null;
        for (Roi roi : domain) {
            if (roi == null) {
                continue;
            }
            Rectangle roiBounds = roi.getBounds();
            union = union == null ? new Rectangle(roiBounds) : union.union(roiBounds);
        }
        if (union == null) {
            return image;
        }
        Rectangle clipped = image.intersection(union);
        if (clipped.width <= 0 || clipped.height <= 0) {
            throw new IllegalArgumentException("the region ROI set and the label images"
                    + " do not overlap; the domain is " + union + " and the images are "
                    + width + "x" + height);
        }
        return clipped;
    }

    private static boolean isSingleRectangle(EngineInputs inputs) {
        List<Roi> domain = inputs.domain();
        if (domain.isEmpty()) {
            // No domain means the window is the whole image, which is a rectangle
            // exactly. The engines require a domain anyway; this branch keeps the
            // class usable on its own terms.
            return true;
        }
        return domain.size() == 1
                && domain.get(0) != null
                && domain.get(0).getType() == Roi.RECTANGLE;
    }

    private static double positiveOrOne(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) || value <= 0.0 ? 1.0 : value;
    }

    private static double finiteOrZero(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? 0.0 : value;
    }
}
