/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ocs.nullmodel;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;

import java.util.Random;

/**
 * Shuffles a channel by sliding the whole of it, rather than by re-placing each
 * object.
 *
 * <h2>Why this exists beside {@link ObjectPermuter}</h2>
 *
 * The per-object permuter picks every object up and drops it somewhere else,
 * refusing any placement that leaves the region or touches an object already
 * placed. That is the better null where it can run, because each object is free
 * of every other. On crowded or confluent segmentations it cannot run at all:
 *
 * <ul>
 *   <li>an object whose bounding box is the whole frame — a confluent astrocyte
 *       network is one — has exactly one possible position, and that position is
 *       not inside a region smaller than the frame;</li>
 *   <li>two or three thousand nuclei filling a fifth to a half of the region
 *       cannot be re-packed without any of them touching, whatever the retry
 *       budget.</li>
 * </ul>
 *
 * <p>One failed placement abandons the whole field. Measured on the real
 * four-channel tissue fields of the validation set, that was every field: 27 of
 * 27 surveyed, every method, every channel pair.
 *
 * <p>This class slides the entire channel by one random offset instead. Nothing
 * is re-packed, so crowding stops mattering and a single frame-sized object
 * stops mattering. Rather than reseating every guest in a full theatre one at a
 * time, it slides the whole seating plan sideways and asks how the two audiences
 * line up then.
 *
 * <h2>What the shift preserves, and what it does not</h2>
 *
 * <p>The shift is a rigid translation with wrap-around at the frame edges — a
 * torus. Every voxel moves, none is created or destroyed, so <b>object count,
 * object volumes and the label histogram are exactly invariant</b>. Each
 * channel's own internal spatial structure travels with it, which is the point:
 * the null being tested is that the two channels are unrelated to <i>each
 * other</i>, not that either one is unstructured.
 *
 * <p>Wrap rather than clip, because clipping deletes whatever leaves the frame.
 * A null model that quietly removes signal reports enrichment that is not there.
 *
 * <p><b>The seam.</b> An object straddling the wrap arrives in two pieces at
 * opposite edges. It keeps one label, so every count and volume stays exact, but
 * its <i>bounding box</i> becomes the width of the frame. Measures read off
 * bounding boxes are therefore inflated under this null in a way they are not
 * for the observed field, which never wraps — an effect that makes results look
 * <i>less</i> significant, not more. {@link #seamSplitCount(int, int, int)}
 * reports how many objects a given offset splits, so the size of it can be
 * measured rather than assumed.
 *
 * <h2>The offset</h2>
 *
 * Drawn uniformly over every in-plane position on the torus except the identity.
 * The identity would reproduce the observed field exactly and count as a
 * permutation that matched the observation.
 *
 * <p>Only the identity is excluded. Excluding <i>near</i>-identity offsets as
 * well is tempting and wrong: they are the offsets that produce the highest
 * overlap, so dropping them lowers the permuted distribution and makes the
 * observation look more extreme than it is. A null model must not be trimmed in
 * the direction of its own conclusion.
 *
 * <h2>Why the shift is in-plane</h2>
 *
 * {@link #drawOffset(Random)} never shifts in z. Two reasons, and the second is
 * the one that decides it.
 *
 * <p><b>The channels really are z-registered.</b> They were acquired in the same
 * optical stack, so which slice a structure sits in is a physical fact shared by
 * every channel, not an accident of arrangement. Randomizing it would break a
 * registration the microscope established and test a null nobody is interested
 * in. Holding z fixed asks the narrower and better-posed question: given where
 * each channel sits in depth, are the two associated <i>laterally</i>? That is
 * the more conservative of the two, because any genuine agreement in depth is
 * left in the null rather than being credited to the finding.
 *
 * <p><b>And it costs far less at the seam.</b> Measured over 8 fields of the
 * validation set, 20 offsets each, on stacks 13 slices deep: shifting
 * in all three axes cuts 14–15% of objects at the wrap, against 3–5% shifting in
 * plane. Objects span a large fraction of a 13-slice stack, so almost any
 * z-shift splits them; the same object is small against 1024 pixels laterally.
 *
 * <p>{@link #displaceBy(int, int, int)} still shifts in z when asked, so an
 * isotropic dataset could use it, and so a test can assert the wrap on every
 * axis.
 *
 * <h2>Determinism</h2>
 *
 * The same seeded {@link Random} gives the identical image, down to which label
 * sits on which voxel — the property {@code 02_CONTRACT.md} § Determinism
 * requires, and what lets a run be replayed from its recorded seed regardless of
 * how many workers ran it.
 */
public final class ChannelDisplacer {

    private final int width;
    private final int height;
    private final int depth;
    /** Index of each occupied voxel, {@code (z * height + y) * width + x}. */
    private final int[] occupied;
    /** Label at the matching entry of {@link #occupied}. */
    private final int[] labels;
    private final int objectCount;
    private final ImageProcessor prototype;
    private final String title;

    private ChannelDisplacer(int width, int height, int depth, int[] occupied,
            int[] labels, int objectCount, ImageProcessor prototype, String title) {
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.occupied = occupied;
        this.labels = labels;
        this.objectCount = objectCount;
        this.prototype = prototype;
        this.title = title;
    }

    /**
     * Reads the occupied voxels out of {@code labelImage} once.
     *
     * <p>Once rather than per shift, for the reason the performance contract
     * gives: at 1,000 permutations the difference is 1,000 full stack scans
     * against one. Only the occupied voxels are kept, because the background is
     * the same everywhere and re-deriving it costs nothing.
     */
    public static ChannelDisplacer of(ImagePlus labelImage) {
        if (labelImage == null) {
            throw new IllegalArgumentException("label image must not be null");
        }
        int width = labelImage.getWidth();
        int height = labelImage.getHeight();
        int depth = labelImage.getStackSize();
        ImageStack stack = labelImage.getStack();

        int count = 0;
        int maxLabel = 0;
        for (int z = 0; z < depth; z++) {
            ImageProcessor processor = stack.getProcessor(z + 1);
            for (int p = 0; p < width * height; p++) {
                int label = (int) processor.getf(p);
                if (label > 0) {
                    count++;
                    if (label > maxLabel) {
                        maxLabel = label;
                    }
                }
            }
        }

        int[] occupied = new int[count];
        int[] labels = new int[count];
        int at = 0;
        for (int z = 0; z < depth; z++) {
            ImageProcessor processor = stack.getProcessor(z + 1);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int label = (int) processor.getf(x, y);
                    if (label <= 0) {
                        continue;
                    }
                    occupied[at] = (z * height + y) * width + x;
                    labels[at] = label;
                    at++;
                }
            }
        }
        return new ChannelDisplacer(width, height, depth, occupied, labels,
                distinct(labels, maxLabel), stack.getProcessor(1),
                labelImage.getTitle());
    }

    /**
     * Largest label counted with a lookup table; above it, by sorting.
     *
     * <p>A 32-bit image can hold label values in the billions — an intensity
     * image picked as labels by mistake — and a table sized by the largest
     * label then fails outright ({@code maxLabel + 1} overflows at
     * {@code Integer.MAX_VALUE}) or takes gigabytes.
     */
    private static final int LOOKUP_LIMIT = 1 << 24;

    private static int distinct(int[] labels, int maxLabel) {
        if (maxLabel <= LOOKUP_LIMIT) {
            boolean[] seen = new boolean[maxLabel + 1];
            int objects = 0;
            for (int i = 0; i < labels.length; i++) {
                if (!seen[labels[i]]) {
                    seen[labels[i]] = true;
                    objects++;
                }
            }
            return objects;
        }
        int[] sorted = labels.clone();
        java.util.Arrays.sort(sorted);
        int objects = 0;
        for (int i = 0; i < sorted.length; i++) {
            if (i == 0 || sorted[i] != sorted[i - 1]) {
                objects++;
            }
        }
        return objects;
    }

    /** How many distinct objects this channel holds. */
    public int objectCount() {
        return objectCount;
    }

    /** How many voxels are object rather than background. */
    public int occupiedVoxels() {
        return occupied.length;
    }

    /**
     * One shifted copy of the label image, at a randomly drawn offset.
     *
     * <p>Never null. Unlike {@link ObjectPermuter#permute(Random)} there is no
     * way for this to fail: nothing has to fit anywhere.
     */
    public ImagePlus displace(Random random) {
        int[] offset = drawOffset(random);
        return displaceBy(offset[0], offset[1], offset[2]);
    }

    /**
     * A uniform in-plane offset on the torus, never the identity.
     *
     * <p>The z component is always zero; see <i>Why the shift is in-plane</i>
     * above. Returned as a three-element offset anyway, so callers and the run
     * record see the whole shift rather than having to know that one axis is
     * implied.
     *
     * <p>Rejection rather than arithmetic, so the remaining offsets stay exactly
     * uniform. The identity is one position out of {@code width × height}, so on
     * any real frame this redraws essentially never; it matters on the small
     * images a test builds, which is where a bias would otherwise hide.
     *
     * @throws IllegalStateException if the frame is a single column, where every
     *         in-plane offset is the identity and there is nothing to shuffle
     */
    public int[] drawOffset(Random random) {
        if (random == null) {
            throw new IllegalArgumentException("random must not be null");
        }
        if (width * height <= 1) {
            throw new IllegalStateException("a " + width + "x" + height
                    + " frame has only the identity shift in plane, so there is"
                    + " no way to shuffle it");
        }
        while (true) {
            int dx = random.nextInt(width);
            int dy = random.nextInt(height);
            if (dx != 0 || dy != 0) {
                return new int[] {dx, dy, 0};
            }
        }
    }

    /**
     * The channel slid by a stated offset, wrapping at every edge.
     *
     * <p>Separate from {@link #displace(Random)} so a test can assert where a
     * known label lands rather than asserting a summary of where everything
     * landed. An aggregate is order-free by construction and cannot see a
     * transposed axis.
     */
    public ImagePlus displaceBy(int dx, int dy, int dz) {
        int shiftX = Math.floorMod(dx, width);
        int shiftY = Math.floorMod(dy, height);
        int shiftZ = Math.floorMod(dz, depth);

        ImageStack output = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            output.addSlice(prototype.createProcessor(width, height));
        }
        int plane = width * height;
        for (int i = 0; i < occupied.length; i++) {
            int index = occupied[i];
            int x = index % width;
            int y = (index / width) % height;
            int z = index / plane;
            int toX = x + shiftX;
            if (toX >= width) {
                toX -= width;
            }
            int toY = y + shiftY;
            if (toY >= height) {
                toY -= height;
            }
            int toZ = z + shiftZ;
            if (toZ >= depth) {
                toZ -= depth;
            }
            output.getProcessor(toZ + 1).setf(toX, toY, labels[i]);
        }
        return new ImagePlus(title + " (displaced)", output);
    }

    /**
     * How many objects a given offset cuts across the wrap.
     *
     * <p>Counts objects that end up occupying voxels at both the low and the
     * high end of an axis. Those keep every count and volume they had, but their
     * bounding box becomes the whole frame, which inflates any measure read off
     * a bounding box. Exposed so that inflation can be measured on real data
     * instead of argued about.
     */
    public int seamSplitCount(int dx, int dy, int dz) {
        int shiftX = Math.floorMod(dx, width);
        int shiftY = Math.floorMod(dy, height);
        int shiftZ = Math.floorMod(dz, depth);
        int maxLabel = 0;
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] > maxLabel) {
                maxLabel = labels[i];
            }
        }
        // Keyed by label through a map above the lookup limit, for the reason
        // distinct() gives.
        boolean small = maxLabel <= LOOKUP_LIMIT;
        boolean[] low = small ? new boolean[maxLabel + 1] : null;
        boolean[] high = small ? new boolean[maxLabel + 1] : null;
        java.util.Map<Integer, int[]> sides = small
                ? null : new java.util.HashMap<Integer, int[]>();
        int plane = width * height;
        for (int i = 0; i < occupied.length; i++) {
            int index = occupied[i];
            int x = index % width;
            int y = (index / width) % height;
            int z = index / plane;
            int label = labels[i];
            boolean wrappedX = x + shiftX >= width;
            boolean wrappedY = y + shiftY >= height;
            boolean wrappedZ = z + shiftZ >= depth;
            boolean wrapped = wrappedX || wrappedY || wrappedZ;
            if (!small) {
                int[] side = sides.get(Integer.valueOf(label));
                if (side == null) {
                    side = new int[2];
                    sides.put(Integer.valueOf(label), side);
                }
                side[wrapped ? 1 : 0] = 1;
                continue;
            }
            if (wrapped) {
                high[label] = true;
            } else {
                low[label] = true;
            }
        }
        int count = 0;
        if (!small) {
            for (int[] side : sides.values()) {
                if (side[0] == 1 && side[1] == 1) {
                    count++;
                }
            }
            return count;
        }
        for (int label = 1; label <= maxLabel; label++) {
            if (low[label] && high[label]) {
                count++;
            }
        }
        return count;
    }

    @Override
    public String toString() {
        return "ChannelDisplacer(" + width + "x" + height + "x" + depth + ", "
                + objectCount + " objects, " + occupied.length + " voxels)";
    }
}
