package ocs.bench;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.process.ShortProcessor;
import ocs.engine.ColocEngine;
import ocs.engine.EngineInputs;
import ocs.engine.EngineProgress;
import ocs.engine.EngineRegistry;
import ocs.nullmodel.NullModelRunner;
import org.junit.Assume;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Whether territories-core's own 3D parallelism, running inside the chance
 * test's workers, costs time or memory.
 *
 * <p>Not part of the build: run with {@code -Docs.bench=nested}. It times the
 * territory-occupancy chance test at 4 null-model workers with the core's
 * default parallelism (nested pools) against {@code territories.parallelism=1}
 * (one core thread per null-model worker, the 0.1.0 behaviour), alternating
 * the order, and samples the heap every 20 ms for the peak.
 */
public class NestedPoolBenchmark {

    private static final int WIDTH = 256;
    private static final int HEIGHT = 256;
    private static final int DEPTH = 16;
    private static final int OBJECTS = 150;
    private static final int PERMUTATIONS = 24;
    private static final int WORKERS = 4;

    @Test
    public void nestedPoolsAgainstSerialCore() throws Exception {
        Assume.assumeTrue("nested".equals(System.getProperty("ocs.bench")));
        EngineInputs inputs = inputs();
        ColocEngine territory = EngineRegistry.createDefault().byId("territory-occupancy");
        String[] settings = {null, "1"};
        System.out.println("[nested-pools] " + WIDTH + "x" + HEIGHT + "x" + DEPTH + ", "
                + OBJECTS + " objects per channel, " + PERMUTATIONS + " shuffles, "
                + WORKERS + " null-model workers, "
                + Runtime.getRuntime().availableProcessors() + " processors");
        for (int i = 0; i < settings.length; i++) {
            if (settings[i] == null) {
                System.clearProperty("territories.parallelism");
            } else {
                System.setProperty("territories.parallelism", settings[i]);
            }
            System.gc();
            PeakHeap peak = new PeakHeap();
            peak.start();
            long start = System.nanoTime();
            NullModelRunner.builder().permutations(PERMUTATIONS).seed(20260930L)
                    .workers(WORKERS).build()
                    .run(Collections.singletonList(territory), inputs, EngineProgress.SILENT);
            long ms = (System.nanoTime() - start) / 1000000L;
            peak.stop = true;
            peak.join();
            System.out.println(String.format("[nested-pools] territories.parallelism=%-8s %7d ms  peak heap %5d MB",
                    settings[i] == null ? "default" : settings[i], ms, peak.peak / (1024 * 1024)));
        }
        System.clearProperty("territories.parallelism");
    }

    private static final class PeakHeap extends Thread {
        volatile boolean stop;
        volatile long peak;

        PeakHeap() {
            setDaemon(true);
        }

        @Override
        public void run() {
            Runtime runtime = Runtime.getRuntime();
            while (!stop) {
                long used = runtime.totalMemory() - runtime.freeMemory();
                if (used > peak) {
                    peak = used;
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }
    }

    private static EngineInputs inputs() {
        Random random = new Random(20260930L);
        ImagePlus a = new ImagePlus("A", cuboids(random));
        ImagePlus b = new ImagePlus("B", cuboids(random));
        return EngineInputs.builder(Arrays.asList(a, b))
                .channelNames(Arrays.asList("A", "B"))
                .domain(Collections.<Roi>singletonList(new Roi(0, 0, WIDTH, HEIGHT)))
                .build();
    }

    private static ImageStack cuboids(Random random) {
        ImageStack stack = new ImageStack(WIDTH, HEIGHT);
        short[][] planes = new short[DEPTH][WIDTH * HEIGHT];
        for (int label = 1; label <= OBJECTS; label++) {
            int x0 = random.nextInt(WIDTH - 6);
            int y0 = random.nextInt(HEIGHT - 6);
            int z0 = random.nextInt(DEPTH - 3);
            for (int z = z0; z < z0 + 3; z++) {
                for (int y = y0; y < y0 + 5; y++) {
                    for (int x = x0; x < x0 + 5; x++) {
                        if (planes[z][y * WIDTH + x] == 0) {
                            planes[z][y * WIDTH + x] = (short) label;
                        }
                    }
                }
            }
        }
        for (int z = 0; z < DEPTH; z++) {
            stack.addSlice(new ShortProcessor(WIDTH, HEIGHT, planes[z], null));
        }
        return stack;
    }
}
