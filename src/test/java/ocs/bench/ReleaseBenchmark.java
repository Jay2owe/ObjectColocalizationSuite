package ocs.bench;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.process.ByteProcessor;
import ij.process.ShortProcessor;
import ocs.OCS;
import ocs.OCSParameters;
import ocs.OCSResult;
import ocs.io.OCSOutputWriter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The release benchmark: four fixed cases, timed, each with the SHA-256 of the
 * whole output tree it saves.
 *
 * <p>A {@code main}, not a test. Timings belong in a report, never in an
 * assertion that fails when the machine is busy. The digest is the other half:
 * a speed-up is only kept when every case still writes byte-for-byte the same
 * files, so the table compares like with like.
 *
 * <pre>
 * java -cp "target/test-classes;target/classes;$(cat target/cp.txt)" \
 *     ocs.bench.ReleaseBenchmark --case A [--runs 3] [--shuffles-b 50]
 * </pre>
 *
 * <p>Cases: (A) Quick look with the chance test, 200 shuffles, 256x256x8, 60
 * objects per channel, 2 channels; (B) Object colocalization with the chance
 * test, 4 channels 512x512x13, 500 objects each; (C) whole-image intensity on
 * 512x512x13; (D) territory occupancy on 512x512x13. One untimed warm-up, then
 * the median of the timed runs.
 */
public final class ReleaseBenchmark {

    private ReleaseBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        String which = "all";
        int runs = 3;
        int shufflesB = 50;
        for (int i = 0; i < args.length; i++) {
            if ("--case".equals(args[i])) {
                which = args[++i];
            } else if ("--runs".equals(args[i])) {
                runs = Integer.parseInt(args[++i]);
            } else if ("--shuffles-b".equals(args[i])) {
                shufflesB = Integer.parseInt(args[++i]);
            } else {
                throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        File out = new File("target/bench-out");
        for (String name : Arrays.asList("A", "B", "C", "D")) {
            if (!"all".equalsIgnoreCase(which) && !name.equalsIgnoreCase(which)) {
                continue;
            }
            OCSParameters parameters = caseParameters(name, shufflesB);
            report(name, parameters, runs, new File(out, name));
        }
    }

    /** The four cases. Fixed seeds, so every run measures the same work. */
    static OCSParameters caseParameters(String name, int shufflesB) {
        if ("A".equals(name)) {
            Field field = Field.of(256, 8, 60, 2, 4242L);
            return field.parameters().preset("Quick look").nullModel(true)
                    .permutations(200).sourceName("bench-A").build();
        }
        if ("B".equals(name)) {
            Field field = Field.of(512, 13, 500, 4, 5151L);
            return field.parameters().preset("Object colocalization").nullModel(true)
                    .permutations(shufflesB).sourceName("bench-B").build();
        }
        if ("C".equals(name)) {
            Field field = Field.of(512, 13, 500, 2, 6161L);
            return field.parameters().methods("whole-image-intensity")
                    .sourceName("bench-C").build();
        }
        if ("D".equals(name)) {
            Field field = Field.of(512, 13, 500, 2, 7171L);
            return field.parameters().methods("territory-occupancy")
                    .sourceName("bench-D").build();
        }
        throw new IllegalArgumentException("no case " + name);
    }

    private static void report(String name, OCSParameters parameters, int runs,
            File folder) throws Exception {
        long warmStart = System.nanoTime();
        OCSResult first = OCS.run(parameters);
        long warm = (System.nanoTime() - warmStart) / 1_000_000L;
        String digest = saveAndDigest(first, folder);
        long[] timed = new long[runs];
        long[] cpu = new long[runs];
        for (int r = 0; r < runs; r++) {
            long start = System.nanoTime();
            long cpuStart = processCpuNanos();
            OCSResult again = OCS.run(parameters);
            timed[r] = (System.nanoTime() - start) / 1_000_000L;
            cpu[r] = (processCpuNanos() - cpuStart) / 1_000_000L;
            String repeat = saveAndDigest(again, folder);
            if (!repeat.equals(digest)) {
                throw new IllegalStateException("case " + name + " is not deterministic: "
                        + digest + " then " + repeat);
            }
        }
        long[] sorted = timed.clone();
        Arrays.sort(sorted);
        long median = runs == 0 ? warm : sorted[runs / 2];
        long[] cpuSorted = cpu.clone();
        Arrays.sort(cpuSorted);
        long cpuMedian = runs == 0 ? 0L : cpuSorted[runs / 2];
        // CPU time is printed beside wall time because on a shared machine the
        // wall clock measures the other work as much as this one; the CPU the
        // process itself spent is far steadier under contention.
        System.out.println(String.format("[release-benchmark] case %s: median %d ms "
                        + "(warm-up %d ms, runs %s) cpu median %d ms (runs %s) digest %s",
                name, Long.valueOf(median), Long.valueOf(warm),
                Arrays.toString(timed), Long.valueOf(cpuMedian), Arrays.toString(cpu),
                digest));
    }

    /** CPU time used by this whole process, all threads, in nanoseconds. */
    private static long processCpuNanos() {
        java.lang.management.OperatingSystemMXBean os =
                java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean) {
            return ((com.sun.management.OperatingSystemMXBean) os).getProcessCpuTime();
        }
        return 0L;
    }

    /** SHA-256 over every saved file, in path order: its path, then its bytes. */
    static String saveAndDigest(OCSResult result, File folder) throws IOException,
            NoSuchAlgorithmException {
        deleteTree(folder);
        OCSOutputWriter.write(result, folder);
        List<File> files = new ArrayList<File>();
        collect(folder, files);
        final String root = folder.getCanonicalPath();
        Collections.sort(files);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        for (File file : files) {
            String relative = file.getCanonicalPath().substring(root.length())
                    .replace('\\', '/');
            sha.update(relative.getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update(Files.readAllBytes(file.toPath()));
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : sha.digest()) {
            hex.append(String.format("%02x", Integer.valueOf(b & 0xff)));
        }
        return hex.toString();
    }

    private static void collect(File folder, List<File> into) {
        File[] children = folder.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, into);
            } else {
                into.add(child);
            }
        }
    }

    private static void deleteTree(File folder) {
        File[] children = folder.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        folder.delete();
    }

    /**
     * Scattered 5 x 5 x 3 cubes per channel from one seeded generator, plus two
     * intensity ramps. Objects may touch and merge, as real segmentations do.
     */
    private static final class Field {

        private final int side;
        private final List<ImagePlus> labels;
        private final List<ImagePlus> intensities;

        private Field(int side, List<ImagePlus> labels, List<ImagePlus> intensities) {
            this.side = side;
            this.labels = labels;
            this.intensities = intensities;
        }

        static Field of(int side, int depth, int objects, int channels, long seed) {
            Random random = new Random(seed);
            List<ImagePlus> labels = new ArrayList<ImagePlus>();
            List<ImagePlus> intensities = new ArrayList<ImagePlus>();
            for (int c = 0; c < channels; c++) {
                labels.add(scatter("Bench " + (char) ('A' + c), side, depth, objects, random));
                intensities.add(ramp("Bench intensity " + (char) ('A' + c), side, depth,
                        3 + 4 * (c % 2), 7 - 4 * (c % 2) + c));
            }
            return new Field(side, labels, intensities);
        }

        OCSParameters.Builder parameters() {
            return OCSParameters.builder(labels)
                    .intensityImages(intensities)
                    .domain(Collections.singletonList(new Roi(0, 0, side, side)));
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
