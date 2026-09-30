package ocs.engine.intensity;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import ocs.engine.EngineCancelledException;
import ocs.engine.EngineProgress;

import java.util.List;

/**
 * Collects, for every labelled object in one channel, the intensity samples that
 * sit inside that object's mask — in one fused pass over the stacks.
 *
 * <p>Think of it as emptying a tray of numbered compartments in a single sweep
 * rather than walking the whole tray once per compartment. The naive per-object
 * implementation rescans the entire volume for each object and is
 * O(objects × voxels): on a 2048×2048×40 stack with 5,000 objects that is 840
 * billion voxel reads for a measurement whose honest cost is 168 million.
 *
 * <p>Two passes are made, both O(voxels):
 *
 * <ol>
 *   <li>a <b>counting pass</b> over the label stack alone, which sizes every
 *       object's slice of the output. The intensity stacks are not touched, so
 *       this costs one integer read per voxel;
 *   <li>the <b>fused pass</b>, which reads the label and <i>every</i> intensity
 *       channel at the same voxel and writes each channel's value into that
 *       object's slice.
 * </ol>
 *
 * <p>The alternative — growable per-object buffers in a single pass — was
 * rejected because a doubling buffer holds up to twice the final samples at the
 * moment it grows, and the peak allocation is what decides whether a real stack
 * fits in Fiji's heap.
 *
 * <h2>Layout</h2>
 *
 * <p>All objects share one flat array per channel, concatenated in <b>ascending
 * label order</b>, with per-object offsets alongside. One allocation per channel
 * rather than one per object per channel, and the ordering is a property of the
 * labels rather than of the traversal — which is what lets the per-object engine
 * merge parallel work by index and get the same table every run.
 *
 * <p>Samples of different channels at the same slot belong to the same voxel.
 * That pairing is the whole point: a per-object Pearson computed over two
 * channels whose samples came from different voxel orders is a confident number
 * measuring nothing.
 *
 * <p>The images are read and never written. The same {@code EngineInputs} is
 * handed to every engine and reused across hundreds of null-model permutations.
 */
public final class ObjectVoxelGatherer {

    /**
     * Labels above this are refused rather than used to size an index table.
     *
     * <p>16.7 million is far beyond any real segmentation and well below what a
     * 32-bit intensity image reaches. Passing an intensity image where a label
     * image was meant is the mistake this catches: without the guard it would
     * allocate a multi-gigabyte index table and report one "object" per grey
     * level.
     */
    public static final int MAX_LABEL = 1 << 24;

    /** One cancellation poll per this many voxels — often enough to feel immediate. */
    private static final int CANCEL_POLL_VOXELS = 1 << 20;

    private ObjectVoxelGatherer() {}

    /**
     * Gathers every object of {@code labelImage}, with one sample vector per
     * intensity channel.
     *
     * <p>Every intensity channel is gathered, not just the two a single direction
     * needs, because the label pass is the expensive part and a four-channel run
     * would otherwise repeat it twelve times instead of four.
     *
     * @param labelImage      the channel whose objects define the masks
     * @param intensityImages one per channel, in channel order, all matching the
     *                        label image's dimensions
     * @throws EngineCancelledException if {@code progress} reports cancellation
     */
    public static Gathered gather(ImagePlus labelImage, List<ImagePlus> intensityImages,
                                  EngineProgress progress) {
        if (labelImage == null) {
            throw new IllegalArgumentException("label image must not be null");
        }
        if (intensityImages == null || intensityImages.isEmpty()) {
            throw new IllegalArgumentException(
                    "per-object intensity metrics need at least one intensity image");
        }
        EngineProgress reporter = progress == null ? EngineProgress.SILENT : progress;
        requireSingleValuePerVoxel(labelImage, "label image");

        int width = labelImage.getWidth();
        int height = labelImage.getHeight();
        int depth = Math.max(1, labelImage.getStackSize());
        int channels = intensityImages.size();
        for (int c = 0; c < channels; c++) {
            ImagePlus image = intensityImages.get(c);
            if (image == null) {
                throw new IllegalArgumentException("intensity image " + (c + 1) + " is null");
            }
            requireSingleValuePerVoxel(image, "intensity image '" + image.getTitle() + "'");
            if (image.getWidth() != width || image.getHeight() != height
                    || Math.max(1, image.getStackSize()) != depth) {
                throw new IllegalArgumentException("intensity image '" + image.getTitle()
                        + "' is " + image.getWidth() + "×" + image.getHeight() + "×"
                        + Math.max(1, image.getStackSize()) + " but the label image '"
                        + labelImage.getTitle() + "' is " + width + "×" + height + "×" + depth
                        + "; a mismatch would pair each voxel with the wrong partner"
                        + " rather than fail");
            }
        }

        int planeSize = width * height;
        ImageStack labelStack = labelImage.getStack();

        // ------------------------------------------------------------------
        // Pass one: how many voxels each label owns.
        // ------------------------------------------------------------------
        int[] counts = new int[256];
        int highest = 0;
        int sinceCancelCheck = 0;
        for (int z = 1; z <= depth; z++) {
            ImageProcessor labels = labelStack.getProcessor(z);
            for (int p = 0; p < planeSize; p++) {
                if (++sinceCancelCheck >= CANCEL_POLL_VOXELS) {
                    sinceCancelCheck = 0;
                    if (reporter.isCancelled()) {
                        throw new EngineCancelledException("object-voxel-gather");
                    }
                }
                int label = labelAt(labels, p, labelImage);
                if (label <= 0) {
                    continue;
                }
                if (label >= counts.length) {
                    counts = grow(counts, label);
                }
                counts[label]++;
                if (label > highest) {
                    highest = label;
                }
            }
        }

        // ------------------------------------------------------------------
        // Slice the output: ascending label order, so the row order of the
        // finished table is a property of the segmentation and not of the
        // traversal or of which worker finished first.
        // ------------------------------------------------------------------
        int objectCount = 0;
        for (int label = 1; label <= highest; label++) {
            if (counts[label] > 0) {
                objectCount++;
            }
        }
        int[] objectLabels = new int[objectCount];
        int[] offsets = new int[objectCount];
        int[] sizes = new int[objectCount];
        int[] labelToIndex = new int[highest + 1];
        long total = 0L;
        int at = 0;
        for (int label = 1; label <= highest; label++) {
            if (counts[label] == 0) {
                continue;
            }
            objectLabels[at] = label;
            sizes[at] = counts[label];
            offsets[at] = (int) total;
            total += counts[label];
            // Stored one-based so a plain zero means "not an object".
            labelToIndex[label] = at + 1;
            at++;
        }
        if (total > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("the objects of '" + labelImage.getTitle()
                    + "' cover " + total + " voxels, more than one array can hold");
        }

        // ------------------------------------------------------------------
        // Pass two, fused: label and every intensity channel at the same voxel.
        // ------------------------------------------------------------------
        double[][] samples = new double[channels][(int) total];
        ImageStack[] intensityStacks = new ImageStack[channels];
        for (int c = 0; c < channels; c++) {
            intensityStacks[c] = intensityImages.get(c).getStack();
        }
        ImageProcessor[] planes = new ImageProcessor[channels];
        int[] filled = new int[objectCount];
        sinceCancelCheck = 0;
        for (int z = 1; z <= depth; z++) {
            ImageProcessor labels = labelStack.getProcessor(z);
            for (int c = 0; c < channels; c++) {
                planes[c] = intensityStacks[c].getProcessor(z);
            }
            for (int p = 0; p < planeSize; p++) {
                if (++sinceCancelCheck >= CANCEL_POLL_VOXELS) {
                    sinceCancelCheck = 0;
                    if (reporter.isCancelled()) {
                        throw new EngineCancelledException("object-voxel-gather");
                    }
                }
                int label = labelAt(labels, p, labelImage);
                if (label <= 0) {
                    continue;
                }
                int index = labelToIndex[label] - 1;
                int slot = offsets[index] + filled[index];
                filled[index]++;
                for (int c = 0; c < channels; c++) {
                    samples[c][slot] = planes[c].getf(p);
                }
            }
        }
        return new Gathered(objectLabels, offsets, sizes, samples);
    }

    /**
     * A packed RGB word or a palette index read as a label invents objects, and
     * read as an intensity invents a correlation. Both produce a confident wrong
     * number rather than an error, which is why this refuses rather than coerces.
     */
    private static void requireSingleValuePerVoxel(ImagePlus image, String role) {
        int type = image.getType();
        if (type == ImagePlus.COLOR_RGB || type == ImagePlus.COLOR_256) {
            throw new IllegalArgumentException(role + " '" + image.getTitle()
                    + "' is a colour image. Per-object intensity colocalization needs one"
                    + " value per voxel; a packed RGB word or a palette index read as one"
                    + " produces a confident wrong number. Split the channels first.");
        }
        if (image.isComposite() || image.getNChannels() > 1) {
            throw new IllegalArgumentException(role + " '" + image.getTitle()
                    + "' is a composite with " + image.getNChannels() + " channels."
                    + " Its stack interleaves channels, so reading it as one volume would"
                    + " pair each voxel with the wrong partner. Split it first.");
        }
    }

    private static int labelAt(ImageProcessor labels, int index, ImagePlus image) {
        float value = labels.getf(index);
        if (!(value > 0.0f)) {
            return 0;
        }
        if (value > MAX_LABEL) {
            throw new IllegalArgumentException("'" + image.getTitle() + "' carries the value "
                    + value + ", above the " + MAX_LABEL + " ceiling for a label."
                    + " This is almost always an intensity image passed where a label image"
                    + " was meant, which would report one object per grey level.");
        }
        return (int) (value + 0.5f);
    }

    private static int[] grow(int[] counts, int label) {
        int size = counts.length;
        while (size <= label) {
            size <<= 1;
        }
        int[] bigger = new int[size];
        System.arraycopy(counts, 0, bigger, 0, counts.length);
        return bigger;
    }

    /**
     * The gathered samples, addressed by object index.
     *
     * <p>Object index <i>i</i> is the <i>i</i>-th label in ascending order, not
     * the label itself: labels from a segmentation are sparse after objects are
     * filtered out, and a dense index is what a result array can be sized from.
     * {@link #labelAt(int)} converts back, and every reported row carries the
     * label rather than the index.
     */
    public static final class Gathered {

        private final int[] labels;
        private final int[] offsets;
        private final int[] sizes;
        private final double[][] samples;

        private Gathered(int[] labels, int[] offsets, int[] sizes, double[][] samples) {
            this.labels = labels;
            this.offsets = offsets;
            this.sizes = sizes;
            this.samples = samples;
        }

        public int objectCount() {
            return labels.length;
        }

        public int channelCount() {
            return samples.length;
        }

        /** The label of object {@code index}, in ascending label order. */
        public int labelAt(int index) {
            return labels[index];
        }

        /** How many voxels object {@code index} owns. */
        public int voxelCountAt(int index) {
            return sizes[index];
        }

        /** Object index for a label, or -1 where that label has no object. */
        public int indexOfLabel(int label) {
            int low = 0;
            int high = labels.length - 1;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                if (labels[mid] < label) {
                    low = mid + 1;
                } else if (labels[mid] > label) {
                    high = mid - 1;
                } else {
                    return mid;
                }
            }
            return -1;
        }

        /** Total voxels across every object — the length of one channel's array. */
        public int totalVoxels() {
            return samples.length == 0 ? 0 : samples[0].length;
        }

        /**
         * One object's samples from one channel, in the order the fused pass met
         * them.
         *
         * <p>Copied rather than exposed as a view. The copy is O(this object's
         * voxels), the same order as the measurement that follows it, and it is
         * what stops a metric implementation from being able to reorder the
         * gathered samples under a second direction that still has to read them.
         */
        public double[] samplesFor(int objectIndex, int channel) {
            int count = sizes[objectIndex];
            double[] slice = new double[count];
            System.arraycopy(samples[channel], offsets[objectIndex], slice, 0, count);
            return slice;
        }
    }
}
