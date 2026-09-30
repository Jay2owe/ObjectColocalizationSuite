package ocs.reference;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.process.ByteProcessor;
import ij.process.ShortProcessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The reference dataset: five cases built from axis-aligned cuboids, chosen so
 * that every number the suite reports on them can be worked out from the shapes
 * alone, on paper, before any code runs.
 *
 * <p>This is the difference between this fixture and the per-engine ones. Those
 * check an engine against its own private fixture, which proves each engine
 * self-consistent but says nothing about whether thirteen engines describe the
 * <i>same</i> geometry the same way. Here one dataset goes through the whole
 * suite at once, so a direction swapped in one adapter, a calibration dropped in
 * another, or a channel pair transposed in the run layer shows up as a
 * disagreement between engines that must agree.
 *
 * <p><b>Every expected value in {@link ReferenceAnswersTest} is a literal with
 * its arithmetic written beside it.</b> None was captured from a run. A number
 * copied out of the output it is meant to gate agrees with that output by
 * construction and proves nothing, and it is indistinguishable in a test report
 * from the real thing — the rule is stated at greater length in
 * {@code oc3d-core/EQUIVALENCE_HARNESS.md} §7.
 *
 * <h2>Why cuboids</h2>
 *
 * A cuboid's volume, bounding box and centroid are all products and midpoints of
 * integers, so they can be checked mentally. Every side length here is
 * <b>odd</b>, which puts every centroid on an exact voxel rather than half way
 * between two — otherwise the centroid-based engines would be asserting a
 * rounding convention rather than a geometric fact, and a change to that
 * convention would look like a correctness regression.
 *
 * <h2>The five cases</h2>
 *
 * <table>
 *   <caption>Geometry and what each case is for</caption>
 *   <tr><th>Case</th><th>Geometry</th><th>What it pins</th></tr>
 *   <tr><td>{@link #identical()}</td>
 *       <td>B is a voxel-for-voxel copy of A</td>
 *       <td>Every method must report its maximum. Anything less than perfect
 *           agreement with itself is a bug, whatever the method</td></tr>
 *   <tr><td>{@link #disjoint()}</td>
 *       <td>A's objects at one side, B's at the other, nothing shared</td>
 *       <td>Every method must report its floor. Catches an engine that reports
 *           a spurious partner, and the {@code 0} that means "no partner"</td></tr>
 *   <tr><td>{@link #nested()}</td>
 *       <td>A 3×3×3 cube concentric inside a 7×7×7 one</td>
 *       <td><b>The direction test.</b> 100% one way, 7.87% the other. An adapter
 *           that swaps source for target passes every symmetric case and fails
 *           only this one</td></tr>
 *   <tr><td>{@link #partial()}</td>
 *       <td>Two 5×5×3 cuboids sharing two of five columns</td>
 *       <td><b>The disagreement test.</b> Overlap says 40%; centroid coincidence
 *           says no, because neither centroid is inside the other object. Both
 *           are right, and the suite exists to show that</td></tr>
 *   <tr><td>{@link #correlated()}</td>
 *       <td>Two objects, one with B = 2A + 6 inside it and one with
 *           B = 250 − 2A</td>
 *       <td>The intensity family through the run layer: r = +1 and r = −1
 *           exactly, so a mis-paired channel cannot hide in a plausible
 *           mid-range number</td></tr>
 * </table>
 *
 * <p>The cases carry no calibration. Calibrated volume is one multiplication on
 * top of the voxel count, tested where that multiplication lives; putting it
 * here would only make every expected number harder to check by eye.
 */
final class ReferenceDataset {

    /** Wide enough to hold every case with clear space around it. */
    static final int WIDTH = 24;
    static final int HEIGHT = 24;
    static final int DEPTH = 8;

    private ReferenceDataset() {
    }

    // ------------------------------------------------------------------
    // The cases
    // ------------------------------------------------------------------

    /**
     * B is a voxel-for-voxel copy of A: two cuboids, 27 and 75 voxels.
     *
     * <p>Object 1 spans x,y 4–6 and z 1–3, so 3·3·3 = 27 voxels with its centroid
     * at (5, 5, 2). Object 2 spans x,y 14–18 and z 2–4, so 5·5·3 = 75 voxels
     * centred at (16, 16, 3).
     */
    static Case identical() {
        ImagePlus a = labels("Reference A");
        ImagePlus b = labels("Reference B");
        return new Case("identical", a, b);
    }

    /**
     * Nothing shared and nothing near: A's two cuboids sit at x 2–4, B's at
     * x 16–18, at the same two heights.
     *
     * <p>Both channels hold 27-voxel cubes. A's object 1 is centred at (3, 3, 2)
     * and B's at (17, 3, 2), so the nearest-centroid distance is exactly 14
     * voxels — the one non-zero number in this case, and the reason the distance
     * engine has something to report where the overlap engines report zero.
     */
    static Case disjoint() {
        ImageStack a = empty();
        cuboid(a, 2, 2, 1, 3, 3, 3, 1);
        cuboid(a, 2, 14, 1, 3, 3, 3, 2);

        ImageStack b = empty();
        cuboid(b, 16, 2, 1, 3, 3, 3, 1);
        cuboid(b, 16, 14, 1, 3, 3, 3, 2);

        return new Case("disjoint",
                new ImagePlus("Reference A", a), new ImagePlus("Reference B", b));
    }

    /**
     * A 3×3×3 cube sitting concentrically inside a 7×7×7 one — 27 voxels inside
     * 343, sharing a centroid at (10, 10, 3).
     *
     * <p>The small cube spans 9–11 in x, y and 2–4 in z; the large one spans 7–13
     * in x, y and 0–6 in z. Every voxel of the small cube is a voxel of the large
     * one, so the shared count is 27 and the two directions differ by a factor of
     * 343/27 — the largest asymmetry any case here produces.
     */
    static Case nested() {
        ImageStack a = empty();
        cuboid(a, 9, 9, 2, 3, 3, 3, 1);

        ImageStack b = empty();
        cuboid(b, 7, 7, 0, 7, 7, 7, 1);

        return new Case("nested",
                new ImagePlus("Reference A", a), new ImagePlus("Reference B", b));
    }

    /**
     * Two 5×5×3 cuboids offset by three columns, sharing two of their five.
     *
     * <p>A spans x 4–8, B spans x 7–11; both span y 4–8 and z 1–3. The shared
     * region is x 7–8, so 2·5·3 = 30 of each object's 75 voxels. The centroids —
     * (6, 6, 2) and (9, 6, 2) — are three voxels apart and each lies outside the
     * other object, which is what makes the methods disagree here.
     */
    static Case partial() {
        ImageStack a = empty();
        cuboid(a, 4, 4, 1, 5, 5, 3, 1);

        ImageStack b = empty();
        cuboid(b, 7, 4, 1, 5, 5, 3, 1);

        return new Case("partial",
                new ImagePlus("Reference A", a), new ImagePlus("Reference B", b));
    }

    /**
     * The intensity case: the geometry of {@link #identical()} with intensity
     * images whose relationship inside each object is exactly linear.
     *
     * <p>Intensity A holds a ramp, {@code (7x + 3y + 11z) mod 61}, capped well
     * below 8-bit so the derived channel cannot overflow and wrap — a wrap would
     * break the linearity silently and turn r = ±1 into an arbitrary number that
     * still looks like a measurement.
     *
     * <p>Inside object 1, intensity B is {@code 2A + 6}: a positive affine map, so
     * Pearson's r over those voxels is exactly +1. Inside object 2 it is
     * {@code 250 − 2A}: negative slope, so exactly −1. Everywhere else B is
     * {@code 2A + 6}.
     *
     * <p>The two signs matter more than the two magnitudes. A channel pair fed in
     * the wrong order, or an accumulator that squares away a sign, still produces
     * a number near 1 on any fixture where both objects correlate the same way.
     *
     * <p><b>The whole-image correlation on this case is deliberately not a round
     * number and is not asserted as one.</b> Object 2's 75 inverted voxels sit in
     * a field of {@value #WIDTH}·{@value #HEIGHT}·{@value #DEPTH} that is
     * otherwise exactly linear, so the field-wide r is a mixture — high, positive,
     * short of 1, and with no closed form worth writing down. What the test
     * asserts about it is the part that <i>is</i> forced: it lies strictly between
     * the two per-object values. Asserting a mixture to twelve decimal places
     * would mean pinning a number nobody derived.
     */
    static Case correlated() {
        ImagePlus labelsA = labels("Reference A");
        ImagePlus labelsB = labels("Reference B");

        ImageStack intensityA = empty8();
        ImageStack intensityB = empty8();
        for (int z = 0; z < DEPTH; z++) {
            ByteProcessor sourceSlice = (ByteProcessor) intensityA.getProcessor(z + 1);
            ByteProcessor derivedSlice = (ByteProcessor) intensityB.getProcessor(z + 1);
            ShortProcessor labelSlice =
                    (ShortProcessor) labelsA.getStack().getProcessor(z + 1);
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    int source = (7 * x + 3 * y + 11 * z) % 61;
                    sourceSlice.set(x, y, source);
                    derivedSlice.set(x, y, labelSlice.get(x, y) == 2
                            ? 250 - 2 * source
                            : 2 * source + 6);
                }
            }
        }

        Case reference = new Case("correlated", labelsA, labelsB);
        reference.intensityImages = Arrays.asList(
                new ImagePlus("Intensity A", intensityA),
                new ImagePlus("Intensity B", intensityB));
        return reference;
    }

    /** Every case, in the order the table above lists them. */
    static List<Case> all() {
        return Arrays.asList(identical(), disjoint(), nested(), partial(),
                correlated());
    }

    // ------------------------------------------------------------------
    // Building blocks
    // ------------------------------------------------------------------

    /** The shared two-object geometry of {@link #identical()}. */
    private static ImagePlus labels(String title) {
        ImageStack stack = empty();
        cuboid(stack, 4, 4, 1, 3, 3, 3, 1);
        cuboid(stack, 14, 14, 2, 5, 5, 3, 2);
        return new ImagePlus(title, stack);
    }

    private static ImageStack empty() {
        ImageStack stack = new ImageStack(WIDTH, HEIGHT);
        for (int z = 0; z < DEPTH; z++) {
            stack.addSlice(new ShortProcessor(WIDTH, HEIGHT));
        }
        return stack;
    }

    private static ImageStack empty8() {
        ImageStack stack = new ImageStack(WIDTH, HEIGHT);
        for (int z = 0; z < DEPTH; z++) {
            stack.addSlice(new ByteProcessor(WIDTH, HEIGHT));
        }
        return stack;
    }

    /** Writes {@code label} into the cuboid whose lowest corner is (x0, y0, z0). */
    private static void cuboid(ImageStack stack, int x0, int y0, int z0,
            int width, int height, int depth, int label) {
        for (int z = z0; z < z0 + depth; z++) {
            ShortProcessor slice = (ShortProcessor) stack.getProcessor(z + 1);
            for (int y = y0; y < y0 + height; y++) {
                for (int x = x0; x < x0 + width; x++) {
                    slice.set(x, y, label);
                }
            }
        }
    }

    // ------------------------------------------------------------------

    /**
     * One case: its name, its two label channels, and its intensity channels
     * where it has them.
     */
    static final class Case {

        private final String name;
        private final List<ImagePlus> labelImages;
        private List<ImagePlus> intensityImages = Collections.emptyList();

        Case(String name, ImagePlus a, ImagePlus b) {
            this.name = name;
            List<ImagePlus> images = new ArrayList<ImagePlus>();
            images.add(a);
            images.add(b);
            this.labelImages = Collections.unmodifiableList(images);
        }

        String name() {
            return name;
        }

        List<ImagePlus> labelImages() {
            return labelImages;
        }

        List<ImagePlus> intensityImages() {
            return intensityImages;
        }

        /**
         * The whole field as a region. The spatial family needs an observation
         * window before it will run at all, and "the image" is the honest one for
         * a synthetic case with no tissue boundary in it.
         */
        List<Roi> wholeField() {
            return Collections.singletonList(
                    new Roi(0, 0, WIDTH, HEIGHT));
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
