package ocs.golden;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.OvalRoi;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.process.ShortProcessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Seeded synthetic inputs for the golden output gate.
 *
 * <p>Built in code from a fixed {@link Random} seed, so the same pixels come out
 * on every machine and every JVM: {@code java.util.Random} is specified to the
 * bit. Nothing here is hand-computed and nothing needs to be: these fixtures
 * exist to pin whatever the suite currently outputs, so that a later change to a
 * supporting column, a curve, a null-model p or a Discovery class shows up as a
 * diff instead of passing silently. The hand-computed answers live in
 * {@code ReferenceAnswersTest}.
 *
 * <p>Deliberately small. The whole gate must stay well under a minute or it will
 * be skipped in practice.
 */
final class GoldenFixtures {

    private GoldenFixtures() {
    }

    /** One case's inputs. */
    static final class Inputs {
        final List<ImagePlus> labels;
        final List<ImagePlus> intensities;
        final List<String> names;
        final List<Roi> domain;
        final Calibration calibration;

        Inputs(List<ImagePlus> labels, List<ImagePlus> intensities, List<String> names,
                List<Roi> domain, Calibration calibration) {
            this.labels = labels;
            this.intensities = intensities;
            this.names = names;
            this.domain = domain;
            this.calibration = calibration;
        }
    }

    /**
     * (a) Three channels in 3D with paired intensity images and a
     * non-rectangular region: an oval inscribed in the frame, so a scatter into
     * the bounding box instead of the region would show.
     *
     * <p>Channel B is channel A's objects shifted by one voxel plus a few of its
     * own, so the object methods have real partners, real misses and a spread of
     * overlaps. Channel C is independent. Intensity B follows intensity A inside
     * A's objects, so the intensity family has correlation to find.
     */
    static Inputs threeChannel3D() {
        int width = 40;
        int height = 40;
        int depth = 5;
        Random random = new Random(20260930L);
        ImageStack a = blank(width, height, depth);
        ImageStack b = blank(width, height, depth);
        ImageStack c = blank(width, height, depth);
        int labelA = 0;
        int labelB = 0;
        for (int i = 0; i < 10; i++) {
            int w = 2 + random.nextInt(3);
            int h = 2 + random.nextInt(3);
            int d = 1 + random.nextInt(3);
            int x = 6 + random.nextInt(width - 12 - w);
            int y = 6 + random.nextInt(height - 12 - h);
            int z = random.nextInt(depth - d + 1);
            cuboid(a, x, y, z, w, h, d, ++labelA);
            if (i % 3 != 2) {
                cuboid(b, x + 1, y, z, w, h, d, ++labelB);
            }
        }
        for (int i = 0; i < 3; i++) {
            cuboid(b, 6 + random.nextInt(26), 6 + random.nextInt(26),
                    random.nextInt(depth - 1), 2, 2, 2, ++labelB);
        }
        for (int i = 0; i < 9; i++) {
            cuboid(c, 6 + random.nextInt(26), 6 + random.nextInt(26),
                    random.nextInt(depth - 1), 2 + random.nextInt(2), 2, 2, i + 1);
        }
        ImagePlus labelsA = new ImagePlus("golden A", a);
        ImagePlus labelsB = new ImagePlus("golden B", b);
        ImagePlus labelsC = new ImagePlus("golden C", c);

        ImagePlus intensityA = intensity("golden A raw", a, null, random, 0);
        ImagePlus intensityB = intensity("golden B raw", b, intensityA.getStack(), random, 1);
        ImagePlus intensityC = intensity("golden C raw", c, null, random, 2);

        return new Inputs(Arrays.asList(labelsA, labelsB, labelsC),
                Arrays.asList(intensityA, intensityB, intensityC),
                Arrays.asList("A", "B", "C"),
                Collections.<Roi>singletonList(new OvalRoi(2, 2, width - 4, height - 4)),
                null);
    }

    /** (b) A single-slice pair: the 2D paths of every family. */
    static Inputs pair2D() {
        int width = 48;
        int height = 48;
        Random random = new Random(20260931L);
        ImageStack a = blank(width, height, 1);
        ImageStack b = blank(width, height, 1);
        int labelB = 0;
        for (int i = 0; i < 12; i++) {
            int w = 2 + random.nextInt(3);
            int h = 2 + random.nextInt(3);
            int x = 4 + random.nextInt(width - 8 - w);
            int y = 4 + random.nextInt(height - 8 - h);
            cuboid(a, x, y, 0, w, h, 1, i + 1);
            if (i % 2 == 0) {
                cuboid(b, x + random.nextInt(3) - 1, y + 1, 0, w, h, 1, ++labelB);
            }
        }
        for (int i = 0; i < 4; i++) {
            cuboid(b, 4 + random.nextInt(38), 4 + random.nextInt(38), 0, 2, 3, 1, ++labelB);
        }
        ImagePlus labelsA = new ImagePlus("pair A", a);
        ImagePlus labelsB = new ImagePlus("pair B", b);
        ImagePlus intensityA = intensity("pair A raw", a, null, random, 0);
        ImagePlus intensityB = intensity("pair B raw", b, intensityA.getStack(), random, 1);
        List<Roi> domain = new ArrayList<Roi>();
        domain.add(new OvalRoi(1, 1, width - 2, height - 2));
        return new Inputs(Arrays.asList(labelsA, labelsB),
                Arrays.asList(intensityA, intensityB),
                Arrays.asList("A", "B"), domain, null);
    }

    /**
     * (c) Three channels: one with no objects at all, one with a single object,
     * one ordinary. Every "nothing to compare" path in one run.
     */
    static Inputs emptyAndSingle() {
        int width = 32;
        int height = 32;
        int depth = 3;
        ImageStack none = blank(width, height, depth);
        ImageStack one = blank(width, height, depth);
        cuboid(one, 12, 12, 0, 4, 4, 2, 1);
        ImageStack some = blank(width, height, depth);
        cuboid(some, 13, 13, 0, 3, 3, 2, 1);
        cuboid(some, 4, 22, 1, 3, 3, 2, 2);
        cuboid(some, 22, 5, 0, 2, 2, 2, 3);
        return new Inputs(Arrays.asList(new ImagePlus("empty", none),
                        new ImagePlus("single", one), new ImagePlus("some", some)),
                null, Arrays.asList("Empty", "Single", "Some"),
                Collections.<Roi>singletonList(new OvalRoi(1, 1, width - 2, height - 2)),
                null);
    }

    /**
     * (d) Anisotropic calibration, 0.284 x 0.284 x 1.0 um: every calibrated
     * volume, distance and radius goes through the voxel size.
     */
    static Inputs anisotropic() {
        int width = 36;
        int height = 36;
        int depth = 6;
        Random random = new Random(20261001L);
        ImageStack a = blank(width, height, depth);
        ImageStack b = blank(width, height, depth);
        for (int i = 0; i < 8; i++) {
            int x = 5 + random.nextInt(24);
            int y = 5 + random.nextInt(24);
            int z = random.nextInt(depth - 2);
            cuboid(a, x, y, z, 3, 3, 2, i + 1);
            cuboid(b, x + random.nextInt(3), y + random.nextInt(2), z + random.nextInt(2),
                    2, 3, 2, i + 1);
        }
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 0.284;
        calibration.pixelHeight = 0.284;
        calibration.pixelDepth = 1.0;
        calibration.setUnit("um");
        ImagePlus labelsA = new ImagePlus("aniso A", a);
        ImagePlus labelsB = new ImagePlus("aniso B", b);
        labelsA.setCalibration(calibration);
        labelsB.setCalibration(calibration);
        List<Roi> domain = new ArrayList<Roi>();
        domain.add(new OvalRoi(2, 2, width - 4, height - 4));
        return new Inputs(Arrays.asList(labelsA, labelsB), null,
                Arrays.asList("A", "B"), domain, calibration);
    }

    // ---------- building blocks ----------

    private static ImageStack blank(int width, int height, int depth) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            stack.addSlice(new ShortProcessor(width, height));
        }
        return stack;
    }

    private static void cuboid(ImageStack stack, int x0, int y0, int z0,
            int w, int h, int d, int label) {
        for (int z = z0; z < z0 + d && z < stack.getSize(); z++) {
            ShortProcessor slice = (ShortProcessor) stack.getProcessor(z + 1);
            for (int y = y0; y < y0 + h && y < stack.getHeight(); y++) {
                for (int x = x0; x < x0 + w && x < stack.getWidth(); x++) {
                    if (x >= 0 && y >= 0) {
                        slice.set(x, y, label);
                    }
                }
            }
        }
    }

    /**
     * Background noise everywhere, brighter inside objects. With a partner
     * stack, follows the partner linearly inside this channel's objects.
     */
    private static ImagePlus intensity(String title, ImageStack labels,
            ImageStack partner, Random random, int offset) {
        int width = labels.getWidth();
        int height = labels.getHeight();
        ImageStack stack = blank(width, height, labels.getSize());
        for (int z = 1; z <= labels.getSize(); z++) {
            ShortProcessor out = (ShortProcessor) stack.getProcessor(z);
            ShortProcessor in = (ShortProcessor) labels.getProcessor(z);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int value = 100 + 10 * offset + random.nextInt(40);
                    if (in.get(x, y) > 0) {
                        value = partner != null
                                ? 50 + 2 * partner.getProcessor(z).get(x, y) / 3
                                        + random.nextInt(20)
                                : 400 + random.nextInt(200);
                    }
                    out.set(x, y, value);
                }
            }
        }
        return new ImagePlus(title, stack);
    }
}
