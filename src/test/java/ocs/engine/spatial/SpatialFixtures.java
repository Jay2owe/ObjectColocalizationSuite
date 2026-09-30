package ocs.engine.spatial;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.process.ShortProcessor;
import ocs.engine.EngineInputs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Label images made of single-pixel objects, so a fixture's point pattern is
 * exactly the coordinates written into it.
 *
 * <p>One pixel per object is not a simplification of the statistics — the spatial
 * family reduces every object to one centroid anyway — but it does mean a
 * fixture's expected curve can be worked out from the coordinates alone, without
 * a centroid calculation standing between the test and the number it asserts.
 */
final class SpatialFixtures {

    private SpatialFixtures() {
    }

    /** One single-pixel object per position, labelled 1..n in the given order. */
    static ImagePlus labels(String title, int width, int height, int[][] positions) {
        ShortProcessor processor = new ShortProcessor(width, height);
        for (int i = 0; i < positions.length; i++) {
            processor.set(positions[i][0], positions[i][1], i + 1);
        }
        return new ImagePlus(title, processor);
    }

    /** A two-slice stack, so the z-collapse in {@link PointPattern} is reachable. */
    static ImagePlus stack(String title, int width, int height, int[][] perSlice) {
        ImageStack stack = new ImageStack(width, height);
        for (int slice = 0; slice < perSlice.length; slice++) {
            ShortProcessor processor = new ShortProcessor(width, height);
            processor.set(perSlice[slice][0], perSlice[slice][1], perSlice[slice][2]);
            stack.addSlice(processor);
        }
        return new ImagePlus(title, stack);
    }

    /** Inputs with a full-frame rectangular domain, which is what the engines need. */
    static EngineInputs inputs(ImagePlus... images) {
        return calibrated(null, images);
    }

    static EngineInputs calibrated(Calibration calibration, ImagePlus... images) {
        List<ImagePlus> list = Arrays.asList(images);
        ImagePlus first = list.get(0);
        EngineInputs.Builder builder = EngineInputs.builder(list)
                .domain(Collections.<Roi>singletonList(
                        new Roi(0, 0, first.getWidth(), first.getHeight())));
        if (calibration != null) {
            builder.calibration(calibration);
        }
        return builder.build();
    }

    static EngineInputs inputs(List<Roi> domain, ImagePlus... images) {
        return EngineInputs.builder(Arrays.asList(images)).domain(domain).build();
    }

    /** A regular lattice, offset so nothing sits on the window boundary. */
    static int[][] grid(int columns, int rows, int spacing, int offsetX, int offsetY) {
        int[][] positions = new int[columns * rows][2];
        int index = 0;
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < columns; x++) {
                positions[index][0] = offsetX + x * spacing;
                positions[index][1] = offsetY + y * spacing;
                index++;
            }
        }
        return positions;
    }

    /** Every position shifted, which is how the clustered control is built. */
    static int[][] shifted(int[][] positions, int dx, int dy) {
        int[][] moved = new int[positions.length][2];
        for (int i = 0; i < positions.length; i++) {
            moved[i][0] = positions[i][0] + dx;
            moved[i][1] = positions[i][1] + dy;
        }
        return moved;
    }

    /**
     * Distinct pixels drawn uniformly, from a caller-supplied seeded generator.
     *
     * <p>Distinct because two objects cannot share a pixel in a label image: the
     * second write would erase the first and the fixture would silently have fewer
     * objects than the test believes.
     */
    static int[][] uniform(int count, int width, int height, int margin, Random random) {
        Set<Long> occupied = new LinkedHashSet<Long>();
        List<int[]> positions = new ArrayList<int[]>();
        while (positions.size() < count) {
            int x = margin + random.nextInt(width - 2 * margin);
            int y = margin + random.nextInt(height - 2 * margin);
            if (occupied.add(Long.valueOf((long) y * width + x))) {
                positions.add(new int[] {x, y});
            }
        }
        return positions.toArray(new int[positions.size()][]);
    }
}
