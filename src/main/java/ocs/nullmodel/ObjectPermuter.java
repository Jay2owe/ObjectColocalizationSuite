package ocs.nullmodel;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * The shape-preserving shuffle behind the null model.
 *
 * <p>Think of each object as a jigsaw piece cut from the image. The permuter
 * lifts every piece out, keeps its exact shape and size, and drops it back at a
 * random position inside the region the user drew — never overlapping another
 * piece from the same channel, never hanging over the region's edge. What
 * changes is <i>where</i> things are; what does not change is what they are, how
 * big they are, or how many of them there are.
 *
 * <p>That is the whole point. If the shuffle also changed object sizes, a null
 * model built on it would be answering "would this happen with different
 * objects", when the question is "would this happen with the <i>same</i> objects
 * somewhere else".
 *
 * <h2>Three decisions worth stating</h2>
 *
 * <p><b>Placement is within the region, not its bounding box.</b> This is defect
 * 9 in {@code 02_CONTRACT.md} and it is the exact bug Coloc 2 is reported to
 * have. Scattering into the bounding box of an L-shaped or annular region puts
 * objects where there is no tissue, which lowers the by-chance colocalization
 * and therefore inflates every significance call built on it.
 *
 * <p><b>Objects of one channel may not overlap each other.</b> Not a modelling
 * preference — a label image holds one label per voxel, so an overlapping
 * placement would silently shrink whichever object was written first and destroy
 * the size distribution this class exists to preserve. It also matches the data:
 * the observed objects are segmented labels and cannot overlap either, so the
 * null carries the same constraint the observation does.
 *
 * <p><b>Objects of different channels may overlap freely.</b> They live in
 * separate images, and their overlap is the quantity being measured. Forbidding
 * it would build the answer into the null.
 */
public final class ObjectPermuter {

    /**
     * Placement attempts per object before the permuter gives up.
     *
     * <p>Bounded because a region packed close to its capacity can make the last
     * object nearly unplaceable, and an unbounded search would hang the run
     * rather than report the problem. On failure the caller reports
     * {@link NullModelResult.Skip#NO_ROOM_IN_DOMAIN}; it never places an object
     * outside the region or on top of another to make progress.
     */
    public static final int MAX_PLACEMENT_ATTEMPTS = 2000;

    /** One object, lifted out of the label image with its shape intact. */
    private static final class Piece {
        private final int label;
        private final int boxWidth;
        private final int boxHeight;
        private final int boxDepth;
        /** Voxels as {@code (dz * boxHeight + dy) * boxWidth + dx}. */
        private final int[] offsets;

        private Piece(int label, int boxWidth, int boxHeight, int boxDepth, int[] offsets) {
            this.label = label;
            this.boxWidth = boxWidth;
            this.boxHeight = boxHeight;
            this.boxDepth = boxDepth;
            this.offsets = offsets;
        }

        private int voxelCount() {
            return offsets.length;
        }
    }

    private final int width;
    private final int height;
    private final int depth;
    private final boolean[] domain;
    private final List<Piece> pieces;
    private final ImageProcessor prototype;
    private final String title;

    private ObjectPermuter(int width, int height, int depth, boolean[] domain,
            List<Piece> pieces, ImageProcessor prototype, String title) {
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.domain = domain;
        this.pieces = pieces;
        this.prototype = prototype;
        this.title = title;
    }

    /**
     * Lifts every object out of {@code labelImage} once.
     *
     * <p>Extraction happens here rather than inside {@link #permute} because the
     * performance contract requires one pass over the voxels reused across all
     * permutations — at 1,000 permutations the difference is 1,000 full stack
     * scans against one.
     *
     * @param domain voxels the objects may occupy, indexed
     *               {@code (z * height + y) * width + x}; see
     *               {@link #domainMask(List, int, int, int)}
     */
    public static ObjectPermuter of(ImagePlus labelImage, boolean[] domain) {
        if (labelImage == null) {
            throw new IllegalArgumentException("label image must not be null");
        }
        int width = labelImage.getWidth();
        int height = labelImage.getHeight();
        int depth = labelImage.getStackSize();
        int voxels = width * height * depth;
        if (domain == null || domain.length != voxels) {
            throw new IllegalArgumentException("domain mask must cover " + voxels
                    + " voxels, got " + (domain == null ? "null" : domain.length));
        }

        // Bounds first, then voxels: two passes over the stack rather than a
        // growable buffer per object, so peak memory is the final size and not
        // twice it at the moment a buffer doubles.
        ImageStack stack = labelImage.getStack();
        int maxLabel = 0;
        for (int z = 0; z < depth; z++) {
            ImageProcessor processor = stack.getProcessor(z + 1);
            for (int p = 0; p < width * height; p++) {
                int label = (int) processor.getf(p);
                if (label > maxLabel) {
                    maxLabel = label;
                }
            }
        }

        int[] minX = new int[maxLabel + 1];
        int[] minY = new int[maxLabel + 1];
        int[] minZ = new int[maxLabel + 1];
        int[] maxX = new int[maxLabel + 1];
        int[] maxY = new int[maxLabel + 1];
        int[] maxZ = new int[maxLabel + 1];
        int[] counts = new int[maxLabel + 1];
        Arrays.fill(minX, Integer.MAX_VALUE);
        Arrays.fill(minY, Integer.MAX_VALUE);
        Arrays.fill(minZ, Integer.MAX_VALUE);
        Arrays.fill(maxX, Integer.MIN_VALUE);
        Arrays.fill(maxY, Integer.MIN_VALUE);
        Arrays.fill(maxZ, Integer.MIN_VALUE);

        for (int z = 0; z < depth; z++) {
            ImageProcessor processor = stack.getProcessor(z + 1);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int label = (int) processor.getf(x, y);
                    if (label <= 0) {
                        continue;
                    }
                    counts[label]++;
                    if (x < minX[label]) { minX[label] = x; }
                    if (y < minY[label]) { minY[label] = y; }
                    if (z < minZ[label]) { minZ[label] = z; }
                    if (x > maxX[label]) { maxX[label] = x; }
                    if (y > maxY[label]) { maxY[label] = y; }
                    if (z > maxZ[label]) { maxZ[label] = z; }
                }
            }
        }

        List<Piece> pieces = new ArrayList<Piece>();
        int[][] offsets = new int[maxLabel + 1][];
        int[] filled = new int[maxLabel + 1];
        for (int label = 1; label <= maxLabel; label++) {
            if (counts[label] > 0) {
                offsets[label] = new int[counts[label]];
            }
        }
        for (int z = 0; z < depth; z++) {
            ImageProcessor processor = stack.getProcessor(z + 1);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int label = (int) processor.getf(x, y);
                    if (label <= 0) {
                        continue;
                    }
                    int boxWidth = maxX[label] - minX[label] + 1;
                    int boxHeight = maxY[label] - minY[label] + 1;
                    int dx = x - minX[label];
                    int dy = y - minY[label];
                    int dz = z - minZ[label];
                    offsets[label][filled[label]++] =
                            (dz * boxHeight + dy) * boxWidth + dx;
                }
            }
        }
        for (int label = 1; label <= maxLabel; label++) {
            if (counts[label] == 0) {
                continue;
            }
            pieces.add(new Piece(label,
                    maxX[label] - minX[label] + 1,
                    maxY[label] - minY[label] + 1,
                    maxZ[label] - minZ[label] + 1,
                    offsets[label]));
        }

        // Largest first. Placement is rejection sampling, and a big piece left
        // until last has to find a gap that the small ones have already eaten;
        // placing it while the region is still empty is what keeps a dense field
        // placeable at all. Deterministic: size descending, label ascending on
        // ties, so the same seed always meets the same order.
        Collections.sort(pieces, new Comparator<Piece>() {
            @Override
            public int compare(Piece a, Piece b) {
                if (a.voxelCount() != b.voxelCount()) {
                    return b.voxelCount() - a.voxelCount();
                }
                return a.label - b.label;
            }
        });

        return new ObjectPermuter(width, height, depth, domain, pieces,
                stack.getProcessor(1), labelImage.getTitle());
    }

    /**
     * The voxels a permuted object may occupy, from the user's region ROIs.
     *
     * <p>ImageJ ROIs are planar, so each applies to every slice of a stack —
     * a region drawn on one slice means the same column of tissue throughout.
     *
     * <p>Returns {@code null} when {@code rois} is empty. The caller must refuse
     * the run rather than substituting the whole image: a null model with no
     * domain is not a weaker null model, it is a different one, and it silently
     * answers a question the user did not ask.
     */
    public static boolean[] domainMask(List<Roi> rois, int width, int height, int depth) {
        if (rois == null || rois.isEmpty()) {
            return null;
        }
        boolean[] mask = new boolean[width * height * depth];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean inside = false;
                for (int i = 0; i < rois.size() && !inside; i++) {
                    // Roi.contains, not the bounding rectangle. An annulus or a
                    // hand-drawn outline differs from its box by most of its area.
                    inside = rois.get(i).contains(x, y);
                }
                if (inside) {
                    for (int z = 0; z < depth; z++) {
                        mask[(z * height + y) * width + x] = true;
                    }
                }
            }
        }
        return mask;
    }

    /** Whole image, for tests and for callers that have already decided. */
    public static boolean[] wholeImageMask(int width, int height, int depth) {
        boolean[] mask = new boolean[width * height * depth];
        Arrays.fill(mask, true);
        return mask;
    }

    public int objectCount() {
        return pieces.size();
    }

    /**
     * One permuted copy of the label image.
     *
     * <p>Deterministic in {@code random}: the same seeded {@link Random}, the
     * same objects and the same domain give the identical image, down to which
     * label sits on which voxel. That is what lets the null model be replayed
     * from its recorded seed.
     *
     * @return a fresh image, or {@code null} if some object could not be placed
     *         within {@link #MAX_PLACEMENT_ATTEMPTS}
     */
    public ImagePlus permute(Random random) {
        if (random == null) {
            throw new IllegalArgumentException("random must not be null");
        }
        boolean[] taken = new boolean[width * height * depth];
        ImageStack output = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            output.addSlice(prototype.createProcessor(width, height));
        }

        for (int i = 0; i < pieces.size(); i++) {
            Piece piece = pieces.get(i);
            int spanX = width - piece.boxWidth;
            int spanY = height - piece.boxHeight;
            int spanZ = depth - piece.boxDepth;
            if (spanX < 0 || spanY < 0 || spanZ < 0) {
                return null;
            }

            boolean placed = false;
            for (int attempt = 0; attempt < MAX_PLACEMENT_ATTEMPTS && !placed; attempt++) {
                int originX = random.nextInt(spanX + 1);
                int originY = random.nextInt(spanY + 1);
                int originZ = spanZ == 0 ? 0 : random.nextInt(spanZ + 1);
                if (fits(piece, originX, originY, originZ, taken)) {
                    write(piece, originX, originY, originZ, taken, output);
                    placed = true;
                }
            }
            if (!placed) {
                return null;
            }
        }

        ImagePlus permuted = new ImagePlus(title + " (permuted)", output);
        return permuted;
    }

    private boolean fits(Piece piece, int originX, int originY, int originZ, boolean[] taken) {
        for (int i = 0; i < piece.offsets.length; i++) {
            int offset = piece.offsets[i];
            int dx = offset % piece.boxWidth;
            int rest = offset / piece.boxWidth;
            int dy = rest % piece.boxHeight;
            int dz = rest / piece.boxHeight;
            int index = ((originZ + dz) * height + (originY + dy)) * width + (originX + dx);
            if (!domain[index] || taken[index]) {
                return false;
            }
        }
        return true;
    }

    private void write(Piece piece, int originX, int originY, int originZ,
            boolean[] taken, ImageStack output) {
        for (int i = 0; i < piece.offsets.length; i++) {
            int offset = piece.offsets[i];
            int dx = offset % piece.boxWidth;
            int rest = offset / piece.boxWidth;
            int dy = rest % piece.boxHeight;
            int dz = rest / piece.boxHeight;
            int x = originX + dx;
            int y = originY + dy;
            int z = originZ + dz;
            taken[((z * height + y) * width + x)] = true;
            output.getProcessor(z + 1).setf(x, y, piece.label);
        }
    }
}
