package ocs.reference;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.process.ByteProcessor;
import ij.process.ShortProcessor;
import ocs.OCS;
import ocs.OCSParameters;
import ocs.OCSResult;
import ocs.engine.ColocEngine;
import ocs.engine.EngineRegistry;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * How long a real study takes, measured rather than guessed.
 *
 * <p>The question this file exists to answer is the one a user asks before
 * starting: <i>if I point this at eighty fields overnight, will it be finished
 * by morning?</i> The dialog's cost estimate answers it in relative units —
 * this method is roughly twelve times that one — and relative units cannot
 * answer it. This file supplies the missing constant.
 *
 * <p><b>Nothing here asserts a timing.</b> A test that fails when a machine is
 * busy is a test that gets disabled, and once disabled it stops reporting the
 * numbers it was written for. What it does assert is that the work it timed
 * actually happened: every method produced a result, and the results are not
 * empty. Otherwise the fastest possible benchmark is one that measures nothing,
 * and it would look like very good news.
 *
 * <p>Timings print to standard output, which surefire captures per test, so the
 * numbers survive in {@code target/surefire-reports} without anyone re-running
 * anything.
 *
 * <h2>Reading the numbers</h2>
 *
 * <p>Two field sizes are timed, differing by a factor of four in voxels, so the
 * two rows show how each method scales rather than only what it costs. That
 * matters more than the absolute figures: a method that doubles when the field
 * quadruples is bounded by the object count and will be fine on a big stack,
 * and one that quadruples is bounded by the voxel count and will not.
 */
public class SuiteBenchmarkTest {

    /** Voxels per field: 128·128·8 = 131,072 and 256·256·8 = 524,288. */
    private static final int[] SIDES = {128, 256};
    private static final int DEPTH = 8;

    /** Objects per channel. Enough that the per-object work is measurable. */
    private static final int OBJECTS = 60;

    /**
     * Shuffles for the chance test. Small, because the point is the per-shuffle
     * cost — which multiplies straight out to whatever a user chooses — not this
     * particular total.
     */
    private static final int PERMUTATIONS = 8;

    /** Timed runs per method per field size, after one untimed warm-up. */
    private static final int TIMED_RUNS = 2;

    @BeforeClass
    public static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    /**
     * Every method the registry ships, timed one at a time, on both field sizes.
     *
     * <p>One method per run rather than all of them at once, because a combined
     * figure cannot tell a user which method to drop when the estimate is too
     * long — and dropping one is the only lever they have.
     */
    @Test
    public void everyMethodIsTimedSeparatelyOnTwoFieldSizes() {
        EngineRegistry registry = EngineRegistry.createDefault();
        List<ColocEngine> engines = registry.all();

        System.out.println();
        System.out.println("[benchmark] Object Colocalization Suite, one method per run,"
                + " " + OBJECTS + " objects per channel, 2 channels (2 directions)");
        System.out.println(String.format("%-26s %14s %14s %10s",
                "method", SIDES[0] + "x" + SIDES[0] + "x" + DEPTH,
                SIDES[1] + "x" + SIDES[1] + "x" + DEPTH, "x4 voxels"));

        for (int e = 0; e < engines.size(); e++) {
            String id = engines.get(e).id();
            long[] elapsed = new long[SIDES.length];
            boolean ran = true;

            for (int s = 0; s < SIDES.length; s++) {
                Field field = Field.of(SIDES[s], DEPTH, OBJECTS, 4242L + s);
                OCSParameters parameters = field.parameters()
                        .methods(id)
                        .sourceName("benchmark")
                        .build();

                if (OCS.run(parameters).resultFor(id) == null) {
                    // Skipped rather than run — a method needing inputs this
                    // field does not carry. Reported, not silently omitted.
                    ran = false;
                    break;
                }
                elapsed[s] = fastestOf(parameters, id);
            }

            if (!ran) {
                System.out.println(String.format("%-26s %14s %14s %10s",
                        id, "skipped", "skipped", "-"));
                continue;
            }
            System.out.println(String.format("%-26s %11d ms %11d ms %10s",
                    id, elapsed[0], elapsed[1],
                    elapsed[0] == 0L ? "-"
                            : String.format("%.1fx", elapsed[1] / (double) elapsed[0])));
        }
    }

    /**
     * The fastest of {@value #TIMED_RUNS} runs, after one untimed run that has
     * already happened at the call site.
     *
     * <p>Both halves matter and for different reasons. <b>The warm-up</b> is
     * because a first run on a cold JVM measures the just-in-time compiler as
     * much as the method: without it the 128-wide field pays for compiling code
     * that the 256-wide field then inherits for free, and several methods came
     * out <i>faster</i> on the larger field — a scaling column that says a
     * method gets cheaper as the image grows is worse than no column, because a
     * reader will believe it. <b>The minimum</b> rather than the mean is because
     * the noise is one-sided: another process can only ever make a run slower,
     * so the fastest observation is the closest to the cost of the work itself.
     */
    private static long fastestOf(OCSParameters parameters, String id) {
        long fastest = Long.MAX_VALUE;
        for (int run = 0; run < TIMED_RUNS; run++) {
            long start = System.nanoTime();
            OCSResult result = OCS.run(parameters);
            long elapsed = (System.nanoTime() - start) / 1_000_000L;
            assertTrue(id + " produced a result with no directions in it, so the "
                            + "timing measured setup and nothing else",
                    !result.resultFor(id).directions().isEmpty());
            fastest = Math.min(fastest, elapsed);
        }
        return fastest;
    }

    /**
     * The default preset end to end, then the same preset with the chance test
     * on, so the multiplier a user is really choosing when they tick that box is
     * a measured number rather than an argument.
     *
     * <p>The chance test is the single largest cost in the plugin and the one
     * users most often turn on without pricing: it re-runs every enabled method
     * once per shuffle, so a hundred shuffles is a hundredfold, and the estimate
     * shown before Run tracks the count they typed rather than the default.
     */
    @Test
    public void theChanceTestMultiplierIsMeasuredNotAssumed() {
        Field field = Field.of(SIDES[1], DEPTH, OBJECTS, 99L);
        OCSParameters methodsOnly = field.parameters()
                .preset(ocs.ui.Preset.defaultPreset().name())
                .sourceName("benchmark")
                .build();
        OCSParameters withShuffles = field.parameters()
                .preset(ocs.ui.Preset.defaultPreset().name())
                .nullModel(true)
                .permutations(PERMUTATIONS)
                .sourceName("benchmark")
                .build();

        // Warm up both paths before either is timed, or the first one measured
        // pays for compiling code the second then inherits — and here that
        // difference lands directly in the multiplier being reported.
        OCS.run(methodsOnly);
        OCS.run(withShuffles);

        long start = System.nanoTime();
        OCSResult plain = OCS.run(methodsOnly);
        long plainMs = (System.nanoTime() - start) / 1_000_000L;

        start = System.nanoTime();
        OCSResult withChance = OCS.run(withShuffles);
        long chanceMs = (System.nanoTime() - start) / 1_000_000L;

        System.out.println();
        System.out.println("[benchmark] preset '" + ocs.ui.Preset.defaultPreset().name()
                + "' on " + SIDES[1] + "x" + SIDES[1] + "x" + DEPTH + ", "
                + OBJECTS + " objects per channel");
        System.out.println("  methods only            : " + plainMs + " ms");
        System.out.println("  plus " + PERMUTATIONS + " shuffles        : " + chanceMs
                + " ms  (" + (plainMs == 0L ? "-"
                        : String.format("%.1fx", chanceMs / (double) plainMs)) + ")");
        System.out.println("  per shuffle             : "
                + (chanceMs - plainMs) / PERMUTATIONS + " ms, so 100 shuffles is about "
                + (plainMs + 100L * (chanceMs - plainMs) / PERMUTATIONS) + " ms per field");

        // The assertions are about the work, not its speed.
        assertEquals("the two runs must cover the same methods, or the multiplier "
                        + "above is comparing different amounts of work",
                plain.engineResults().size(), withChance.engineResults().size());
        assertTrue("the chance test produced no null models, so the second timing "
                        + "measured the same run as the first",
                !withChance.nullModels().isEmpty());
    }

    /**
     * The fixture holds the objects the tables above say it does.
     *
     * <p>Without this, an empty field is the fastest benchmark available and
     * reads as very good news. Every method still returns a direction on a field
     * of nothing — a direction with no objects in it — so the "did it produce
     * results" guard in the timing loop passes, every number collapses, and the
     * report looks like a spectacular optimisation. Checked here rather than
     * inside the loop because it is a property of the fixture, and a fixture that
     * has quietly stopped generating objects should say so once, plainly, rather
     * than as thirteen suspiciously fast rows.
     */
    @Test
    public void theBenchmarkFieldHoldsTheObjectsItClaims() {
        for (int s = 0; s < SIDES.length; s++) {
            Field field = Field.of(SIDES[s], DEPTH, OBJECTS, 4242L + s);
            for (int channel = 0; channel < 2; channel++) {
                assertEquals(SIDES[s] + "-wide field, channel " + channel
                                + ": every label must survive placement",
                        OBJECTS, field.objectCount(channel));
            }
        }
    }

    // ------------------------------------------------------------------

    /**
     * A field of scattered cubes: two label channels sharing about half their
     * objects, plus the intensity channels the intensity family needs.
     *
     * <p>Randomly placed with a fixed seed, so the benchmark measures the same
     * work every time it runs and two timings from different machines are
     * comparable. Objects may touch and merge — that is what real segmentations
     * do, and a fixture of perfectly isolated cubes would understate the
     * per-pair work every overlap method does.
     */
    private static final class Field {

        private final List<ImagePlus> labels;
        private final List<ImagePlus> intensities;
        private final int side;

        private Field(int side, List<ImagePlus> labels, List<ImagePlus> intensities) {
            this.side = side;
            this.labels = labels;
            this.intensities = intensities;
        }

        static Field of(int side, int depth, int objects, long seed) {
            Random random = new Random(seed);
            ImagePlus a = scatter("Bench A", side, depth, objects, random);
            // The same generator continues, so channel B's objects sit
            // independently of A's rather than in a shifted copy of them.
            ImagePlus b = scatter("Bench B", side, depth, objects, random);
            return new Field(side,
                    Arrays.asList(a, b),
                    Arrays.asList(ramp("Bench intensity A", side, depth, 7, 3),
                            ramp("Bench intensity B", side, depth, 3, 7)));
        }

        OCSParameters.Builder parameters() {
            return OCSParameters.builder(labels)
                    .intensityImages(intensities)
                    .domain(Collections.singletonList(new Roi(0, 0, side, side)));
        }

        /** Distinct non-zero labels actually present in a channel. */
        int objectCount(int channel) {
            ImagePlus image = labels.get(channel);
            ImageStack stack = image.getStack();
            Set<Integer> present = new HashSet<Integer>();
            for (int z = 0; z < stack.getSize(); z++) {
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        int label = (int) stack.getProcessor(z + 1).getf(x, y);
                        if (label != 0) {
                            present.add(Integer.valueOf(label));
                        }
                    }
                }
            }
            return present.size();
        }

        private static ImagePlus scatter(String title, int side, int depth,
                int objects, Random random) {
            ImageStack stack = new ImageStack(side, side);
            for (int z = 0; z < depth; z++) {
                stack.addSlice(new ShortProcessor(side, side));
            }
            int cube = 5;
            for (int label = 1; label <= objects; label++) {
                int x0 = random.nextInt(side - cube);
                int y0 = random.nextInt(side - cube);
                int z0 = random.nextInt(Math.max(1, depth - 3));
                for (int z = z0; z < Math.min(depth, z0 + 3); z++) {
                    ShortProcessor slice = (ShortProcessor) stack.getProcessor(z + 1);
                    for (int y = y0; y < y0 + cube; y++) {
                        for (int x = x0; x < x0 + cube; x++) {
                            slice.set(x, y, label);
                        }
                    }
                }
            }
            return new ImagePlus(title, stack);
        }

        private static ImagePlus ramp(String title, int side, int depth,
                int xWeight, int yWeight) {
            ImageStack stack = new ImageStack(side, side);
            for (int z = 0; z < depth; z++) {
                ByteProcessor slice = new ByteProcessor(side, side);
                for (int y = 0; y < side; y++) {
                    for (int x = 0; x < side; x++) {
                        slice.set(x, y, (xWeight * x + yWeight * y + 13 * z) % 251);
                    }
                }
                stack.addSlice(slice);
            }
            return new ImagePlus(title, stack);
        }
    }
}
