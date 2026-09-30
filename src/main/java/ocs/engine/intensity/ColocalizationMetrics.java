package ocs.engine.intensity;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.process.ImageProcessor;
import ocs.engine.EngineCancelledException;
import ocs.engine.EngineProgress;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pearson, Manders and Costes colocalization metrics for a paired pair of
 * single-channel volumes.
 *
 * <p>Ported from FLASH's {@code flash.pipeline.intelligence.ColocalizationMetrics}
 * with the ten defects listed in {@code 02_CONTRACT.md} § Defect ledger fixed.
 * The originals were tolerable in FLASH — one image at a time, under a human's
 * eye — and are not tolerable in an unattended batch, where a fallback threshold
 * and a fitted one look identical in a spreadsheet six months later.
 *
 * <p>What changed, and why each mattered:
 *
 * <ol>
 *   <li>{@link Result#costesThresholdFitted} distinguishes a fitted Costes
 *       threshold from the image-minimum fallback that five separate early
 *       returns produce. Every {@code mandersM1}, {@code mandersM2} and
 *       {@code pearsonThresholded} derived from a fallback is a different
 *       quantity from one derived from a fit, and the inherited {@code Result}
 *       gave a reader no way to tell.
 *   <li>Block depth is at least 2 on a stack. Depth-1 blocks preserve no axial
 *       autocorrelation, so the randomized images come out less correlated than
 *       a correct 3D shuffle would give, the observed r beats them too often,
 *       and significance is over-called.
 *   <li>Block size derives from voxel size and a PSF estimate rather than being
 *       fixed at 5x5. Costes specifies a block on the order of the PSF; a fixed
 *       block makes the null wrong by a dataset-dependent amount, so p values
 *       stop being comparable between differently-acquired datasets — which is
 *       exactly what a batch tool invites.
 *   <li>{@link WelfordAccumulator} replaces the naive sums of squares.
 *   <li>Manders is refused rather than returned on negative intensities.
 *   <li>{@link Result#pFloor} is reported beside p, so "at the resolution limit"
 *       cannot be misread as "significant".
 *   <li>{@link Result#randomizationSkipped} is a first-class field, surfaced by
 *       the engine as a column rather than buried in a note string.
 *   <li>RGB, 8-bit-indexed and composite images are refused at input rather than
 *       having their packed pixel values read as intensities.
 *   <li>An optional domain mask restricts every metric, and the randomization
 *       shuffles <i>within the domain</i>, never within its bounding box. Coloc 2
 *       is criticised for the bounding-box version and repeating it here would be
 *       indefensible given what this plugin claims to be for.
 *   <li>The accumulator's undivided sums are named as sums.
 * </ol>
 *
 * <p><b>Seed contract, version {@value #SEED_CONTRACT_VERSION}.</b> FLASH drew
 * all 100 permutations from one sequential {@code Random(20260422L)}, so
 * permutation <i>i</i> depended on every draw before it and the work could not be
 * split. Each permutation here derives its own seed from
 * {@code (baseSeed, permutationIndex)}, so the permutation stream is a function
 * of the index alone and is reproducible independent of how the work was
 * scheduled. <b>Numbers will not match FLASH's</b>, and cannot: the draw order is
 * different by construction. The equivalence harness gates against this contract,
 * not against FLASH output, and the run record stores both the seed and this
 * version number so a result is reproducible from what is written down.
 */
public final class ColocalizationMetrics {

    /**
     * Bumped whenever the mapping from (seed, permutation index) to permutation
     * changes. Stored in the run record; a stored value that does not match this
     * one means the numbers cannot be reproduced by the current build.
     */
    public static final int SEED_CONTRACT_VERSION = 1;

    /** FLASH's seed, kept as the default so runs stay comparable within this plugin. */
    public static final long DEFAULT_SEED = 20260422L;

    public static final int DEFAULT_PERMUTATIONS = 100;
    public static final long DEFAULT_VOXEL_LIMIT = 50_000_000L;

    /**
     * Block edge used only when neither an explicit size nor a PSF-and-voxel-size
     * pair is available. FLASH's fixed value, kept as the last resort so an
     * uncalibrated image still produces something, with the fallback recorded.
     */
    public static final int DEFAULT_BLOCK_SIZE = 5;

    /**
     * The smallest block depth that preserves any axial structure at all. Defect
     * 2: FLASH used 1, which preserves none.
     */
    public static final int MIN_STACK_BLOCK_DEPTH = 2;

    private static final double COSTES_R_TOLERANCE = 0.01;
    private static final int COSTES_MIN_SEARCH_ITERATIONS = 3;
    private static final int COSTES_MAX_SEARCH_ITERATIONS = 32;

    /**
     * Cancellation is polled once per this many blocks inside a permutation.
     * About one poll per 25,000 voxels at the default block size — often enough
     * that Cancel feels immediate, rarely enough that the poll costs nothing.
     */
    private static final int CANCEL_POLL_BLOCKS = 1024;

    private ColocalizationMetrics() {}

    // ------------------------------------------------------------------
    // Options
    // ------------------------------------------------------------------

    /**
     * Everything the ledger made configurable, in one immutable object.
     *
     * <p>Five of the ten defects were constants that should never have been
     * constants — block size, block depth, permutation count, the voxel limit and
     * the absent domain. Each is a judgement call, and burying a judgement call in
     * a {@code private static final} is how a tool acquires an opinion nobody
     * agreed to.
     */
    public static final class Options {

        private final int permutations;
        private final long seed;
        private final long voxelLimit;
        private final int blockWidth;
        private final int blockHeight;
        private final int blockDepth;
        private final double psfXY;
        private final double psfZ;
        private final double pixelWidth;
        private final double pixelHeight;
        private final double pixelDepth;
        private final boolean[] domainMask;
        private final int workers;
        private final ExecutorService executor;

        private Options(Builder builder) {
            this.permutations = builder.permutations;
            this.seed = builder.seed;
            this.voxelLimit = builder.voxelLimit;
            this.blockWidth = builder.blockWidth;
            this.blockHeight = builder.blockHeight;
            this.blockDepth = builder.blockDepth;
            this.psfXY = builder.psfXY;
            this.psfZ = builder.psfZ;
            this.pixelWidth = builder.pixelWidth;
            this.pixelHeight = builder.pixelHeight;
            this.pixelDepth = builder.pixelDepth;
            this.domainMask = builder.domainMask;
            this.workers = builder.workers;
            this.executor = builder.executor;
        }

        public static Builder builder() {
            return new Builder();
        }

        /** FLASH's settings, minus the defects: block depth still lifts to 2 on a stack. */
        public static Options defaults() {
            return builder().build();
        }

        public int permutations() {
            return permutations;
        }

        public long seed() {
            return seed;
        }

        public long voxelLimit() {
            return voxelLimit;
        }

        /**
         * The domain to restrict every metric to, one entry per voxel in
         * {@code (z * height + y) * width + x} order, or null for the whole image.
         *
         * <p>Held by reference rather than copied: a domain mask for a 168M-voxel
         * stack is 168 MB and copying it per engine would be the largest single
         * allocation in the run. Callers must treat it as read-only, which is the
         * same rule {@code EngineInputs} already imposes on pixel arrays.
         */
        public boolean[] domainMask() {
            return domainMask;
        }

        public int workers() {
            return workers;
        }

        public ExecutorService executor() {
            return executor;
        }

        public Builder toBuilder() {
            Builder builder = new Builder();
            builder.permutations = permutations;
            builder.seed = seed;
            builder.voxelLimit = voxelLimit;
            builder.blockWidth = blockWidth;
            builder.blockHeight = blockHeight;
            builder.blockDepth = blockDepth;
            builder.psfXY = psfXY;
            builder.psfZ = psfZ;
            builder.pixelWidth = pixelWidth;
            builder.pixelHeight = pixelHeight;
            builder.pixelDepth = pixelDepth;
            builder.domainMask = domainMask;
            builder.workers = workers;
            builder.executor = executor;
            return builder;
        }

        /**
         * How many workers to actually use for {@code taskCount} permutations.
         *
         * <p>Sized {@code min(availableProcessors - 1, tasks)} when the caller did
         * not say, leaving a core for the coordinator and never spawning threads
         * with nothing to do. One means the permutations run on the calling
         * thread and no pool is created at all — the serial override the
         * performance contract requires, and the only setting under which a
         * thread cannot leak because none is started.
         */
        int resolveWorkers(int taskCount) {
            int requested = workers > 0
                    ? workers
                    : Runtime.getRuntime().availableProcessors() - 1;
            if (requested < 1) {
                requested = 1;
            }
            return Math.min(requested, Math.max(1, taskCount));
        }

        /**
         * Block edge along one lateral axis.
         *
         * <p>Costes specifies a block on the order of the PSF, so the number that
         * matters is how many voxels one resolvable spot spans. Rounding up rather
         * than to nearest, because a block smaller than the PSF breaks up exactly
         * the correlation the block shuffle exists to preserve.
         */
        int resolveBlockWidth(int imageWidth) {
            int resolved = blockWidth > 0
                    ? blockWidth
                    : derivedBlock(psfXY, pixelWidth);
            return clampBlock(resolved, imageWidth);
        }

        int resolveBlockHeight(int imageHeight) {
            int resolved = blockHeight > 0
                    ? blockHeight
                    : derivedBlock(psfXY, pixelHeight > 0.0 ? pixelHeight : pixelWidth);
            return clampBlock(resolved, imageHeight);
        }

        /**
         * Block edge along the axial axis. Defect 2 lives here.
         *
         * @throws IllegalArgumentException if the caller explicitly asked for
         *         depth 1 on a stack, which is the defect rather than a setting
         */
        int resolveBlockDepth(int imageDepth) {
            if (imageDepth <= 1) {
                return 1;
            }
            if (blockDepth > 0) {
                if (blockDepth < MIN_STACK_BLOCK_DEPTH) {
                    throw new IllegalArgumentException("block depth must be at least "
                            + MIN_STACK_BLOCK_DEPTH + " on a " + imageDepth
                            + "-slice stack, was " + blockDepth
                            + "; depth-1 blocks preserve no axial correlation, so the"
                            + " randomized volumes come out less correlated than they"
                            + " should and significance is over-called");
                }
                return clampBlock(blockDepth, imageDepth);
            }
            int derived = psfZ > 0.0 && pixelDepth > 0.0
                    ? derivedBlock(psfZ, pixelDepth)
                    : MIN_STACK_BLOCK_DEPTH;
            return clampBlock(Math.max(MIN_STACK_BLOCK_DEPTH, derived), imageDepth);
        }

        /** True when the block edges came from a PSF and a voxel size rather than the fallback. */
        boolean blockSizeDerived() {
            if (blockWidth > 0 || blockHeight > 0 || blockDepth > 0) {
                return true;
            }
            return psfXY > 0.0 && pixelWidth > 0.0;
        }

        private static int derivedBlock(double psf, double voxel) {
            if (psf <= 0.0 || voxel <= 0.0 || Double.isNaN(psf) || Double.isNaN(voxel)) {
                return DEFAULT_BLOCK_SIZE;
            }
            // The tolerance stops a ratio that is an integer in arithmetic but a
            // few ulps above it in binary — 0.25 um over 0.05 um voxels is the
            // common case — from costing a whole extra voxel of block edge.
            return Math.max(2, (int) Math.ceil(psf / voxel - 1.0e-9));
        }

        /**
         * A block wider than the image leaves one block in its group, and a group
         * of one shuffles to itself — the randomized image would equal the
         * observed one and p would be exactly 1 for a reason that has nothing to
         * do with the data.
         */
        private static int clampBlock(int block, int extent) {
            int limited = Math.min(block, Math.max(1, extent));
            return Math.max(1, limited);
        }

        public static final class Builder {

            private int permutations = DEFAULT_PERMUTATIONS;
            private long seed = DEFAULT_SEED;
            private long voxelLimit = DEFAULT_VOXEL_LIMIT;
            private int blockWidth;
            private int blockHeight;
            private int blockDepth;
            private double psfXY;
            private double psfZ;
            private double pixelWidth;
            private double pixelHeight;
            private double pixelDepth;
            private boolean[] domainMask;
            private int workers;
            private ExecutorService executor;

            private Builder() {}

            /**
             * Permutations behind the Costes p. The floor on p is
             * {@code 1 / (permutations + 1)}, so 100 can never report below 0.0099
             * and a paper quoting "p = 0.0099" from the default is quoting a tool
             * artefact. 1000 or more is the honest setting for a published number.
             */
            public Builder permutations(int permutations) {
                if (permutations < 1) {
                    throw new IllegalArgumentException(
                            "permutations must be positive, was " + permutations);
                }
                this.permutations = permutations;
                return this;
            }

            public Builder seed(long seed) {
                this.seed = seed;
                return this;
            }

            /** Above this many domain voxels the randomization is skipped and said so. */
            public Builder voxelLimit(long voxelLimit) {
                this.voxelLimit = voxelLimit;
                return this;
            }

            /** Explicit lateral block edge in voxels; 0 derives it from the PSF. */
            public Builder blockSize(int edge) {
                this.blockWidth = edge;
                this.blockHeight = edge;
                return this;
            }

            public Builder blockWidth(int blockWidth) {
                this.blockWidth = blockWidth;
                return this;
            }

            public Builder blockHeight(int blockHeight) {
                this.blockHeight = blockHeight;
                return this;
            }

            public Builder blockDepth(int blockDepth) {
                this.blockDepth = blockDepth;
                return this;
            }

            /** Lateral and axial resolution estimates, in the same units as the voxel size. */
            public Builder psf(double psfXY, double psfZ) {
                this.psfXY = psfXY;
                this.psfZ = psfZ;
                return this;
            }

            public Builder voxelSize(double pixelWidth, double pixelHeight, double pixelDepth) {
                this.pixelWidth = pixelWidth;
                this.pixelHeight = pixelHeight;
                this.pixelDepth = pixelDepth;
                return this;
            }

            /** @see Options#domainMask() — held by reference, must not be mutated afterwards */
            public Builder domainMask(boolean[] domainMask) {
                this.domainMask = domainMask;
                return this;
            }

            /** 1 forces the serial path; 0 sizes the pool from the machine. */
            public Builder workers(int workers) {
                this.workers = workers;
                return this;
            }

            /**
             * A caller-owned pool to run permutations on, instead of the bounded
             * one this class would otherwise create and shut down itself.
             *
             * <p>Exists so a host that already owns an executor does not end up
             * with a pool inside a pool — the performance contract's "never nest
             * pools" rule — and so the tests can force a completion order that
             * would expose any dependence on it. An injected executor is never
             * shut down here.
             */
            public Builder executor(ExecutorService executor) {
                this.executor = executor;
                return this;
            }

            public Options build() {
                return new Options(this);
            }
        }
    }

    // ------------------------------------------------------------------
    // Result
    // ------------------------------------------------------------------

    /** Every number the metrics produce, plus everything needed to audit them. */
    public static final class Result {

        private final double pearson;
        private final double mandersM1;
        private final double mandersM2;
        private final double costesTa;
        private final double costesTb;
        private final boolean costesThresholdFitted;
        private final String thresholdNote;
        private final double pearsonThresholded;
        private final boolean mandersRefusedNegative;
        private final String mandersNote;
        private final double costesP;
        private final double pFloor;
        private final boolean pAtFloor;
        private final int permutations;
        private final long seed;
        private final int seedContractVersion;
        private final boolean randomizationSkipped;
        private final long voxelLimit;
        private final int blockWidth;
        private final int blockHeight;
        private final int blockDepth;
        private final boolean blockSizeDerived;
        private final long voxelsAnalyzed;
        private final double[] nullDistribution;
        private final String note;

        private Result(Builder builder) {
            this.pearson = builder.pearson;
            this.mandersM1 = builder.mandersM1;
            this.mandersM2 = builder.mandersM2;
            this.costesTa = builder.costesTa;
            this.costesTb = builder.costesTb;
            this.costesThresholdFitted = builder.costesThresholdFitted;
            this.thresholdNote = builder.thresholdNote;
            this.pearsonThresholded = builder.pearsonThresholded;
            this.mandersRefusedNegative = builder.mandersRefusedNegative;
            this.mandersNote = builder.mandersNote;
            this.costesP = builder.costesP;
            this.pFloor = builder.pFloor;
            this.pAtFloor = builder.pAtFloor;
            this.permutations = builder.permutations;
            this.seed = builder.seed;
            this.seedContractVersion = SEED_CONTRACT_VERSION;
            this.randomizationSkipped = builder.randomizationSkipped;
            this.voxelLimit = builder.voxelLimit;
            this.blockWidth = builder.blockWidth;
            this.blockHeight = builder.blockHeight;
            this.blockDepth = builder.blockDepth;
            this.blockSizeDerived = builder.blockSizeDerived;
            this.voxelsAnalyzed = builder.voxelsAnalyzed;
            this.nullDistribution = builder.nullDistribution;
            this.note = builder.note;
        }

        public double pearson() {
            return pearson;
        }

        /** Fraction of channel A's signal in voxels where B is above its Costes threshold. */
        public double mandersM1() {
            return mandersM1;
        }

        public double mandersM2() {
            return mandersM2;
        }

        public double costesTa() {
            return costesTa;
        }

        public double costesTb() {
            return costesTb;
        }

        /**
         * Whether the Costes thresholds came from the bisection or from the
         * fallback. Defect 1: without this, a reader cannot tell a fitted
         * threshold from the image minimum, and every Manders and thresholded
         * Pearson downstream of a fallback is a different quantity.
         */
        public boolean costesThresholdFitted() {
            return costesThresholdFitted;
        }

        /** Which of the fallback paths was taken, or empty when the fit succeeded. */
        public String thresholdNote() {
            return thresholdNote;
        }

        public double pearsonThresholded() {
            return pearsonThresholded;
        }

        /** True when Manders was refused because an input channel went negative. */
        public boolean mandersRefusedNegative() {
            return mandersRefusedNegative;
        }

        public String mandersNote() {
            return mandersNote;
        }

        public double costesP() {
            return costesP;
        }

        /**
         * The smallest p this many permutations can express,
         * {@code 1 / (permutations + 1)}. Defect 6: a p equal to its floor means
         * "no permutation beat the observation", which is a statement about the
         * permutation count as much as about the data.
         */
        public double pFloor() {
            return pFloor;
        }

        public boolean pAtFloor() {
            return pAtFloor;
        }

        public int permutations() {
            return permutations;
        }

        public long seed() {
            return seed;
        }

        /** @see ColocalizationMetrics#SEED_CONTRACT_VERSION */
        public int seedContractVersion() {
            return seedContractVersion;
        }

        public boolean randomizationSkipped() {
            return randomizationSkipped;
        }

        public long voxelLimit() {
            return voxelLimit;
        }

        public int blockWidth() {
            return blockWidth;
        }

        public int blockHeight() {
            return blockHeight;
        }

        public int blockDepth() {
            return blockDepth;
        }

        /** False when the block edges fell back to {@link #DEFAULT_BLOCK_SIZE}. */
        public boolean blockSizeDerived() {
            return blockSizeDerived;
        }

        /** Voxels inside the domain, which is every voxel when no domain was given. */
        public long voxelsAnalyzed() {
            return voxelsAnalyzed;
        }

        /**
         * The randomized Pearson from every permutation, <b>indexed by permutation
         * index</b>, or empty where the randomization was skipped.
         *
         * <p>Carried for two reasons. The null-model table wants an
         * <i>Expected</i> and an <i>Enrichment</i> alongside p, and the expected
         * value of this null is the mean of exactly this array — computing it any
         * other way would be computing a different null.
         *
         * <p>And it is the only thing that pins the indexed merge. The p value is
         * a count of permutations beating the observation, and a count cannot tell
         * an indexed merge from any reshuffling of it, so a test asserting only
         * that p agrees across worker counts would pass on a merge that scrambled
         * the permutations completely.
         */
        public double[] nullDistribution() {
            return nullDistribution.clone();
        }

        /** Mean of the null distribution — the <i>Expected</i> column. */
        public double nullMean() {
            if (nullDistribution.length == 0) {
                return Double.NaN;
            }
            WelfordAccumulator acc = new WelfordAccumulator();
            for (int i = 0; i < nullDistribution.length; i++) {
                if (!Double.isNaN(nullDistribution[i])) {
                    acc.add(nullDistribution[i], nullDistribution[i]);
                }
            }
            return acc.meanA();
        }

        public String note() {
            return note;
        }

        static Builder builder() {
            return new Builder();
        }

        static final class Builder {
            double pearson = Double.NaN;
            double mandersM1 = Double.NaN;
            double mandersM2 = Double.NaN;
            double costesTa = Double.NaN;
            double costesTb = Double.NaN;
            boolean costesThresholdFitted;
            String thresholdNote = "";
            double pearsonThresholded = Double.NaN;
            boolean mandersRefusedNegative;
            String mandersNote = "";
            double costesP = Double.NaN;
            double pFloor = Double.NaN;
            boolean pAtFloor;
            int permutations;
            long seed;
            boolean randomizationSkipped;
            long voxelLimit;
            int blockWidth;
            int blockHeight;
            int blockDepth;
            boolean blockSizeDerived;
            long voxelsAnalyzed;
            double[] nullDistribution = new double[0];
            String note = "";

            Result build() {
                return new Result(this);
            }
        }
    }

    // ------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------

    public static Result compute(ImagePlus channelA, ImagePlus channelB) {
        return compute(channelA, channelB, Options.defaults(), EngineProgress.SILENT);
    }

    /**
     * Computes every metric for a pair of open images.
     *
     * @throws IllegalArgumentException if either image is RGB, 8-bit indexed or a
     *         composite. Defect 8: {@code ImageProcessor.getf} on those returns a
     *         packed colour word or a palette index, which reads as a plausible
     *         intensity and produces a confident wrong number rather than an error
     */
    public static Result compute(ImagePlus channelA, ImagePlus channelB,
                                 Options options, EngineProgress progress) {
        if (channelA == null || channelB == null) {
            return failed("missing channel image");
        }
        requireIntensityImage(channelA, "channel A");
        requireIntensityImage(channelB, "channel B");
        if (channelA.getWidth() != channelB.getWidth()
                || channelA.getHeight() != channelB.getHeight()
                || channelA.getStackSize() != channelB.getStackSize()) {
            return failed("channel dimensions differ");
        }

        int width = channelA.getWidth();
        int height = channelA.getHeight();
        int depth = Math.max(1, channelA.getStackSize());
        long count = (long) width * (long) height * (long) depth;
        if (count > Integer.MAX_VALUE) {
            return failed("volume too large for in-memory coloc metrics");
        }

        Options resolved = withVoxelSizeFrom(options, channelA);
        double[] a = extract(channelA, (int) count);
        double[] b = extract(channelB, (int) count);
        return compute(a, b, width, height, depth, resolved, progress);
    }

    public static Result compute(double[] channelA, double[] channelB,
                                 int width, int height, int depth) {
        return compute(channelA, channelB, width, height, depth,
                Options.defaults(), EngineProgress.SILENT);
    }

    /**
     * The raw-array entry point every other form funnels into.
     *
     * <p>Taking arrays rather than images is what makes the per-object variants
     * nearly free: a per-object run is this same code over a domain mask covering
     * one object's voxels.
     */
    public static Result compute(double[] channelA, double[] channelB,
                                 int width, int height, int depth,
                                 Options options, EngineProgress progress) {
        if (channelA == null || channelB == null || channelA.length != channelB.length) {
            throw new IllegalArgumentException("Channel arrays must be non-null and equal length.");
        }
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw new IllegalArgumentException("Image dimensions must be positive.");
        }
        long expected = (long) width * (long) height * (long) depth;
        if (expected != channelA.length) {
            throw new IllegalArgumentException("Image dimensions do not match channel array length.");
        }
        Options settings = options == null ? Options.defaults() : options;
        EngineProgress reporter = progress == null ? EngineProgress.SILENT : progress;
        boolean[] mask = settings.domainMask();
        if (mask != null && mask.length != channelA.length) {
            throw new IllegalArgumentException("Domain mask length " + mask.length
                    + " does not match the " + channelA.length + "-voxel volume.");
        }

        // Block geometry is resolved before anything is measured so it can be
        // recorded even on the paths that bail out early.
        int blockWidth = settings.resolveBlockWidth(width);
        int blockHeight = settings.resolveBlockHeight(height);
        int blockDepth = settings.resolveBlockDepth(depth);

        Result.Builder out = Result.builder();
        out.permutations = settings.permutations();
        out.seed = settings.seed();
        out.voxelLimit = settings.voxelLimit();
        out.blockWidth = blockWidth;
        out.blockHeight = blockHeight;
        out.blockDepth = blockDepth;
        out.blockSizeDerived = settings.blockSizeDerived();

        if (channelA.length == 0) {
            out.note = "empty channel arrays";
            return out.build();
        }

        BasicStats stats = scan(channelA, channelB, mask);
        out.voxelsAnalyzed = stats.count;
        if (stats.count == 0L) {
            out.note = "domain contains no voxels";
            return out.build();
        }
        out.pearson = stats.pearson;

        Thresholds thresholds = costesThresholds(channelA, channelB, stats, mask);
        out.costesTa = thresholds.ta;
        out.costesTb = thresholds.tb;
        out.costesThresholdFitted = thresholds.fitted;
        out.thresholdNote = thresholds.note;

        applyManders(out, channelA, channelB, mask, stats, thresholds);
        out.pearsonThresholded =
                pearsonAbove(channelA, channelB, thresholds.ta, thresholds.tb, mask);

        Randomization randomization = costesRandomizationPValue(
                channelA, channelB, width, height, depth,
                blockWidth, blockHeight, blockDepth,
                mask, stats, settings, reporter);
        out.costesP = randomization.pValue;
        out.pFloor = randomization.pFloor;
        out.pAtFloor = randomization.atFloor;
        out.randomizationSkipped = randomization.skipped;
        out.nullDistribution = randomization.distribution;
        out.note = randomization.note;
        return out.build();
    }

    /**
     * Builds a voxel domain mask from an ROI set.
     *
     * <p>An ROI with no explicit slice position applies to every slice, which is
     * how a user drawing one outline on a stack expects it to behave. ROIs
     * combine by union.
     */
    public static boolean[] maskFrom(List<Roi> rois, int width, int height, int depth) {
        if (rois == null || rois.isEmpty()) {
            return null;
        }
        boolean[] mask = new boolean[width * height * Math.max(1, depth)];
        int planeSize = width * height;
        for (int r = 0; r < rois.size(); r++) {
            Roi roi = rois.get(r);
            if (roi == null) {
                continue;
            }
            ImageProcessor roiMask = roi.getMask();
            java.awt.Rectangle bounds = roi.getBounds();
            int position = roi.getPosition();
            int firstSlice = position > 0 ? position : 1;
            int lastSlice = position > 0 ? position : Math.max(1, depth);
            for (int z = firstSlice; z <= lastSlice && z <= Math.max(1, depth); z++) {
                int offset = (z - 1) * planeSize;
                for (int y = bounds.y; y < bounds.y + bounds.height; y++) {
                    if (y < 0 || y >= height) {
                        continue;
                    }
                    for (int x = bounds.x; x < bounds.x + bounds.width; x++) {
                        if (x < 0 || x >= width) {
                            continue;
                        }
                        // A null mask means the ROI is its own bounding rectangle;
                        // anything else is the non-rectangular case that defect 9
                        // turns on.
                        if (roiMask != null && roiMask.get(x - bounds.x, y - bounds.y) == 0) {
                            continue;
                        }
                        mask[offset + y * width + x] = true;
                    }
                }
            }
        }
        return mask;
    }

    /** The seed permutation {@code index} draws from, under the versioned contract. */
    public static long permutationSeed(long baseSeed, int index) {
        // SplitMix64's finalizer. Consecutive base seeds and consecutive indices
        // both need to give unrelated streams: `new Random(seed + index)` would
        // hand neighbouring permutations near-identical initial draws, which is a
        // correlated null dressed up as an independent one.
        long z = baseSeed + 0x9E3779B97F4A7C15L * (index + 1L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // ------------------------------------------------------------------
    // Input validation
    // ------------------------------------------------------------------

    private static void requireIntensityImage(ImagePlus image, String role) {
        int type = image.getType();
        if (type == ImagePlus.COLOR_RGB || type == ImagePlus.COLOR_256) {
            throw new IllegalArgumentException(role + " '" + image.getTitle()
                    + "' is a colour image. Intensity colocalization needs a single"
                    + " intensity per voxel; a packed RGB word or a palette index"
                    + " read as an intensity produces a confident wrong number."
                    + " Split the channels first.");
        }
        if (image.isComposite() || image.getNChannels() > 1) {
            throw new IllegalArgumentException(role + " '" + image.getTitle()
                    + "' is a composite with " + image.getNChannels() + " channels."
                    + " Its stack interleaves channels, so reading it as one volume"
                    + " would pair each voxel with the wrong partner. Split it first.");
        }
    }

    private static Options withVoxelSizeFrom(Options options, ImagePlus image) {
        Options settings = options == null ? Options.defaults() : options;
        if (settings.pixelWidth > 0.0) {
            return settings;
        }
        ij.measure.Calibration calibration = image.getCalibration();
        if (calibration == null || !calibration.scaled()) {
            return settings;
        }
        return settings.toBuilder()
                .voxelSize(calibration.pixelWidth, calibration.pixelHeight, calibration.pixelDepth)
                .build();
    }

    private static Result failed(String note) {
        Result.Builder out = Result.builder();
        out.note = note;
        return out.build();
    }

    private static double[] extract(ImagePlus image, int count) {
        double[] values = new double[count];
        ImageStack stack = image.getStack();
        int width = image.getWidth();
        int height = image.getHeight();
        int planeSize = width * height;
        int depth = Math.max(1, image.getStackSize());
        int offset = 0;
        for (int z = 1; z <= depth; z++) {
            ImageProcessor ip = stack.getProcessor(z);
            for (int p = 0; p < planeSize; p++) {
                values[offset + p] = ip.getf(p);
            }
            offset += planeSize;
        }
        return values;
    }

    // ------------------------------------------------------------------
    // Statistics
    // ------------------------------------------------------------------

    private static BasicStats scan(double[] a, double[] b, boolean[] mask) {
        WelfordAccumulator acc = new WelfordAccumulator();
        double minA = Double.POSITIVE_INFINITY;
        double maxA = Double.NEGATIVE_INFINITY;
        double minB = Double.POSITIVE_INFINITY;
        double maxB = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < a.length; i++) {
            if (mask != null && !mask[i]) {
                continue;
            }
            double av = a[i];
            double bv = b[i];
            acc.add(av, bv);
            if (av < minA) minA = av;
            if (av > maxA) maxA = av;
            if (bv < minB) minB = bv;
            if (bv > maxB) maxB = bv;
        }
        return new BasicStats(minA, maxA, minB, maxB, acc);
    }

    /**
     * Costes' automatic thresholds by bisection down the regression line.
     *
     * <p>Five paths bail out to the image minimum. Each now says which one it was
     * and leaves {@code fitted} false, because the difference between a fitted
     * threshold and the image minimum is the difference between "Manders above
     * the correlated background" and "Manders above nothing" — the same column
     * name for two different quantities.
     */
    private static Thresholds costesThresholds(double[] a, double[] b,
                                               BasicStats stats, boolean[] mask) {
        if (!aboveTolerance(stats.pearson) || stats.sumSqDevA <= 0.0 || stats.count < 3) {
            return Thresholds.fallback(stats,
                    "no fit attempted: channels are uncorrelated, invariant or nearly empty");
        }

        double slope = stats.sumCoDev / stats.sumSqDevA;
        double intercept = stats.meanB - slope * stats.meanA;
        if (Double.isNaN(slope) || Double.isInfinite(slope)
                || Double.isNaN(intercept) || Double.isInfinite(intercept)) {
            return Thresholds.fallback(stats,
                    "no fit attempted: the regression line is not finite");
        }

        double low = stats.minA;
        double high = stats.maxA;
        if (high <= low) {
            return Thresholds.fallback(stats,
                    "no fit attempted: channel A has no intensity range");
        }

        double lowTb = clamp(slope * low + intercept, stats.minB, stats.maxB);
        double highTb = clamp(slope * high + intercept, stats.minB, stats.maxB);
        double lowR = pearsonBelow(a, b, low, lowTb, mask);
        double highR = pearsonBelow(a, b, high, highTb, mask);
        if (aboveTolerance(lowR)) {
            return Thresholds.fallback(stats,
                    "search not bracketed: correlation is already above tolerance at the"
                            + " lowest threshold, so no threshold separates signal from"
                            + " correlated background");
        }
        if (!aboveTolerance(highR)) {
            return Thresholds.fallback(stats,
                    "search not bracketed: correlation stays below tolerance at the"
                            + " highest threshold");
        }

        double below = low;
        double above = high;
        boolean foundBelowTolerance = !aboveTolerance(lowR);
        for (int i = 0; i < COSTES_MAX_SEARCH_ITERATIONS; i++) {
            double mid = (below + above) / 2.0;
            double midTb = clamp(slope * mid + intercept, stats.minB, stats.maxB);
            double midR = pearsonBelow(a, b, mid, midTb, mask);
            if (aboveTolerance(midR)) {
                above = mid;
            } else {
                below = mid;
                foundBelowTolerance = true;
            }
            if (i + 1 >= COSTES_MIN_SEARCH_ITERATIONS
                    && Math.abs(above - below) <= 1.0e-6) {
                break;
            }
        }

        if (!foundBelowTolerance) {
            return Thresholds.fallback(stats,
                    "search did not converge: no candidate threshold brought the"
                            + " below-threshold correlation under tolerance");
        }
        double ta = above;
        double tb = clamp(slope * ta + intercept, stats.minB, stats.maxB);
        return Thresholds.fitted(ta, tb);
    }

    private static boolean aboveTolerance(double r) {
        return !Double.isNaN(r) && !Double.isInfinite(r) && r > COSTES_R_TOLERANCE;
    }

    /**
     * Manders M1 and M2, or a refusal.
     *
     * <p>Defect 5. Manders is a fraction of total signal and is defined only for
     * non-negative intensities. On a float image after background subtraction the
     * denominator can approach zero or cross it, at which point M1 exceeds 1 or
     * changes sign — a number that looks like a colocalization coefficient and is
     * not one. Refusing costs a cell in a table; returning it costs a retraction.
     */
    private static void applyManders(Result.Builder out, double[] a, double[] b,
                                     boolean[] mask, BasicStats stats,
                                     Thresholds thresholds) {
        boolean negativeA = stats.minA < 0.0;
        boolean negativeB = stats.minB < 0.0;
        if (negativeA) {
            out.mandersM1 = Double.NaN;
        } else {
            out.mandersM1 = mandersFraction(a, b, thresholds.tb, mask);
        }
        if (negativeB) {
            out.mandersM2 = Double.NaN;
        } else {
            out.mandersM2 = mandersFraction(b, a, thresholds.ta, mask);
        }
        if (negativeA || negativeB) {
            out.mandersRefusedNegative = true;
            StringBuilder message = new StringBuilder(
                    "Manders refused: it is defined only for non-negative intensities, and ");
            if (negativeA && negativeB) {
                message.append("both channels go negative (min A ")
                        .append(stats.minA).append(", min B ").append(stats.minB).append(")");
            } else if (negativeA) {
                message.append("channel A goes negative (min ").append(stats.minA).append(")");
            } else {
                message.append("channel B goes negative (min ").append(stats.minB).append(")");
            }
            out.mandersNote = message.toString();
        }
    }

    /**
     * The fraction of {@code numerator}'s total signal that sits where
     * {@code gate} is above {@code gateThreshold}.
     */
    private static double mandersFraction(double[] numerator, double[] gate,
                                          double gateThreshold, boolean[] mask) {
        double total = 0.0;
        double coloc = 0.0;
        for (int i = 0; i < numerator.length; i++) {
            if (mask != null && !mask[i]) {
                continue;
            }
            total += numerator[i];
            if (gate[i] > gateThreshold) {
                coloc += numerator[i];
            }
        }
        return total <= 0.0 ? Double.NaN : coloc / total;
    }

    private static double pearsonBelow(double[] a, double[] b, double ta, double tb,
                                       boolean[] mask) {
        WelfordAccumulator acc = new WelfordAccumulator();
        for (int i = 0; i < a.length; i++) {
            if (mask != null && !mask[i]) {
                continue;
            }
            if (a[i] <= ta && b[i] <= tb) {
                acc.add(a[i], b[i]);
            }
        }
        return acc.pearson();
    }

    private static double pearsonAbove(double[] a, double[] b, double ta, double tb,
                                       boolean[] mask) {
        WelfordAccumulator acc = new WelfordAccumulator();
        for (int i = 0; i < a.length; i++) {
            if (mask != null && !mask[i]) {
                continue;
            }
            if (a[i] > ta && b[i] > tb) {
                acc.add(a[i], b[i]);
            }
        }
        return acc.pearson();
    }

    // ------------------------------------------------------------------
    // Costes randomization
    // ------------------------------------------------------------------

    private static Randomization costesRandomizationPValue(
            final double[] a, final double[] b,
            int width, int height, int depth,
            int blockWidth, int blockHeight, int blockDepth,
            final boolean[] mask, BasicStats stats,
            Options options, final EngineProgress progress) {

        final double observedPearson = stats.pearson;
        if (stats.count > options.voxelLimit()) {
            return Randomization.skipped("coloc-randomization-skipped: "
                    + stats.count + " domain voxels exceeds the " + options.voxelLimit()
                    + "-voxel limit");
        }
        if (Double.isNaN(observedPearson) || Double.isInfinite(observedPearson)) {
            return Randomization.skipped(
                    "coloc-randomization-skipped: the observed correlation is not finite");
        }

        final List<BlockGroup> blockGroups = buildBlockGroups(
                width, height, depth, blockWidth, blockHeight, blockDepth, mask);
        String degenerate = degeneracyNote(blockGroups);

        final int permutations = options.permutations();
        final long seed = options.seed();
        final int widthRef = width;
        final int heightRef = height;
        final double[] randomized = new double[permutations];
        int workers = options.resolveWorkers(permutations);

        if (workers <= 1) {
            for (int i = 0; i < permutations; i++) {
                if (progress.isCancelled()) {
                    throw new EngineCancelledException("costes-randomization");
                }
                randomized[i] = shuffledBlockPearson(a, b, widthRef, heightRef,
                        blockGroups, new Random(permutationSeed(seed, i)), progress);
                progress.report("Costes randomization", (i + 1.0) / permutations);
            }
        } else {
            runPermutationsInParallel(a, b, widthRef, heightRef, blockGroups,
                    randomized, seed, options, workers, progress);
        }

        int greaterOrEqual = 0;
        for (int i = 0; i < permutations; i++) {
            if (!Double.isNaN(randomized[i]) && randomized[i] >= observedPearson) {
                greaterOrEqual++;
            }
        }
        double p = (greaterOrEqual + 1.0) / (permutations + 1.0);
        double floor = 1.0 / (permutations + 1.0);
        return new Randomization(p, floor, greaterOrEqual == 0, false, degenerate, randomized);
    }

    /**
     * Runs the permutations on a bounded pool and merges by index.
     *
     * <p>Every task writes only {@code randomized[itsOwnIndex]}, so the merge is
     * positional and completion order cannot reach the answer. That is the whole
     * reason each permutation derives its own seed: with FLASH's single shared
     * {@code Random}, permutation <i>i</i> depended on every draw before it and
     * the result would have been whatever order the threads happened to run in.
     */
    private static void runPermutationsInParallel(
            final double[] a, final double[] b,
            final int width, final int height,
            final List<BlockGroup> blockGroups,
            final double[] randomized,
            final long seed,
            Options options, int workers,
            final EngineProgress progress) {

        ExecutorService injected = options.executor();
        ExecutorService pool = injected != null ? injected : newPool(workers);
        boolean owned = injected == null;
        List<Future<?>> futures = new ArrayList<Future<?>>(randomized.length);
        boolean cancelled = false;
        RuntimeException failure = null;
        try {
            for (int i = 0; i < randomized.length; i++) {
                // A cancellation arriving before the queue is full should stop
                // filling it, not queue another two hundred tasks that will each
                // start and immediately refuse.
                if (progress.isCancelled()) {
                    cancelled = true;
                    break;
                }
                final int index = i;
                futures.add(pool.submit(new Callable<Void>() {
                    @Override
                    public Void call() {
                        if (progress.isCancelled()) {
                            throw new EngineCancelledException("costes-randomization");
                        }
                        randomized[index] = shuffledBlockPearson(a, b, width, height,
                                blockGroups, new Random(permutationSeed(seed, index)),
                                progress);
                        return null;
                    }
                }));
            }
            for (int i = 0; !cancelled && i < futures.size(); i++) {
                // Checked here as well as inside the tasks so a cancellation that
                // arrives while the queue is still draining stops the queue rather
                // than waiting for every queued task to start and refuse.
                if (progress.isCancelled()) {
                    cancelled = true;
                    break;
                }
                try {
                    futures.get(i).get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancelled = true;
                    break;
                } catch (ExecutionException e) {
                    failure = unwrap(e);
                    break;
                }
                progress.report("Costes randomization", (i + 1.0) / futures.size());
            }
        } finally {
            // First failure or cancellation drains the rest rather than letting
            // queued permutations keep burning cores for a result nobody will read.
            if (cancelled || failure != null) {
                for (int i = 0; i < futures.size(); i++) {
                    futures.get(i).cancel(true);
                }
            }
            if (owned) {
                shutdownAndWait(pool, cancelled || failure != null);
            }
        }
        if (failure != null) {
            throw failure;
        }
        if (cancelled) {
            throw new EngineCancelledException("costes-randomization");
        }
    }

    private static RuntimeException unwrap(ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException) {
            return (RuntimeException) cause;
        }
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        return new IllegalStateException("Costes randomization failed", cause);
    }

    private static ExecutorService newPool(int workers) {
        return Executors.newFixedThreadPool(workers, new ThreadFactory() {
            private final AtomicInteger next = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable,
                        "ocs-coloc-perm-" + next.incrementAndGet());
                // Daemon so a bug here can never be the reason Fiji will not quit.
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    /**
     * Returns only once the pool's threads are actually gone, so a cancelled run
     * leaves nothing behind. Bounded, because hanging on shutdown would be a worse
     * failure than leaking.
     */
    private static void shutdownAndWait(ExecutorService pool, boolean abrupt) {
        if (abrupt) {
            pool.shutdownNow();
        } else {
            pool.shutdown();
        }
        try {
            if (!pool.awaitTermination(30L, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A group whose largest member has fewer than two blocks cannot shuffle, so
     * every "randomized" image equals the observed one and p comes back at 1 for a
     * reason that has nothing to do with the data.
     */
    private static String degeneracyNote(List<BlockGroup> blockGroups) {
        int largest = 0;
        for (int i = 0; i < blockGroups.size(); i++) {
            largest = Math.max(largest, blockGroups.get(i).size);
        }
        if (largest < 2) {
            return "coloc-randomization-degenerate: the domain holds no two"
                    + " interchangeable blocks, so no shuffle is possible and p is not"
                    + " informative";
        }
        return "";
    }

    /**
     * Groups block positions so that shuffling within a group can only ever pair a
     * domain voxel with a domain voxel.
     *
     * <p>Defect 9's teeth. Blocks are keyed by their shape <i>and</i> by which of
     * their voxels are inside the domain, so two blocks land in the same group
     * only when they are genuinely interchangeable. Blocks entirely inside the
     * domain — nearly all of them — share one key per shape; blocks straddling the
     * boundary only mix with blocks straddling it identically; blocks entirely
     * outside are dropped.
     *
     * <p>Tiling the bounding box and shuffling whole blocks, which is the obvious
     * implementation and the one Coloc 2 is criticised for, would drag voxels from
     * outside a non-rectangular domain into the null distribution. With no domain
     * every block is full and this reduces exactly to grouping by shape.
     */
    private static List<BlockGroup> buildBlockGroups(int width, int height, int depth,
                                                     int blockWidth, int blockHeight,
                                                     int blockDepth, boolean[] mask) {
        Map<String, BlockGroup> byKey = new LinkedHashMap<String, BlockGroup>();
        // Two passes so the origins land in exact-sized int arrays. A 168M-voxel
        // stack tiles into millions of blocks and one small object per block would
        // be the largest allocation in the run.
        for (int pass = 0; pass < 2; pass++) {
            for (int z = 0; z < depth; z += blockDepth) {
                int currentDepth = Math.min(blockDepth, depth - z);
                for (int y = 0; y < height; y += blockHeight) {
                    int currentHeight = Math.min(blockHeight, height - y);
                    for (int x = 0; x < width; x += blockWidth) {
                        int currentWidth = Math.min(blockWidth, width - x);
                        boolean[] pattern = mask == null ? null : pattern(
                                mask, width, height, x, y, z,
                                currentWidth, currentHeight, currentDepth);
                        if (pattern != null && !anyTrue(pattern)) {
                            continue;
                        }
                        String key = key(currentWidth, currentHeight, currentDepth, pattern);
                        if (pass == 0) {
                            BlockGroup group = byKey.get(key);
                            if (group == null) {
                                group = new BlockGroup(currentWidth, currentHeight,
                                        currentDepth, pattern);
                                byKey.put(key, group);
                            }
                            group.size++;
                        } else {
                            byKey.get(key).add(x, y, z);
                        }
                    }
                }
            }
            if (pass == 0) {
                for (BlockGroup group : byKey.values()) {
                    group.allocate();
                }
            }
        }
        return new ArrayList<BlockGroup>(byKey.values());
    }

    private static boolean[] pattern(boolean[] mask, int width, int height,
                                     int x, int y, int z,
                                     int blockWidth, int blockHeight, int blockDepth) {
        boolean[] pattern = new boolean[blockWidth * blockHeight * blockDepth];
        int at = 0;
        for (int dz = 0; dz < blockDepth; dz++) {
            for (int dy = 0; dy < blockHeight; dy++) {
                int rowStart = index(width, height, x, y + dy, z + dz);
                for (int dx = 0; dx < blockWidth; dx++) {
                    pattern[at++] = mask[rowStart + dx];
                }
            }
        }
        return pattern;
    }

    private static boolean anyTrue(boolean[] pattern) {
        for (int i = 0; i < pattern.length; i++) {
            if (pattern[i]) {
                return true;
            }
        }
        return false;
    }

    private static boolean allTrue(boolean[] pattern) {
        for (int i = 0; i < pattern.length; i++) {
            if (!pattern[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Full blocks get a short shared key so the common case does not build a
     * per-block string; only boundary blocks pay for their pattern.
     */
    private static String key(int blockWidth, int blockHeight, int blockDepth,
                              boolean[] pattern) {
        String shape = blockWidth + "x" + blockHeight + "x" + blockDepth;
        if (pattern == null || allTrue(pattern)) {
            return "F" + shape;
        }
        StringBuilder builder = new StringBuilder(pattern.length + shape.length() + 2);
        builder.append('P').append(shape).append(':');
        for (int i = 0; i < pattern.length; i++) {
            builder.append(pattern[i] ? '1' : '0');
        }
        return builder.toString();
    }

    private static double shuffledBlockPearson(double[] a, double[] b,
                                               int width, int height,
                                               List<BlockGroup> blockGroups,
                                               Random random,
                                               EngineProgress progress) {
        WelfordAccumulator acc = new WelfordAccumulator();
        int sinceCancelCheck = 0;
        for (int g = 0; g < blockGroups.size(); g++) {
            BlockGroup group = blockGroups.get(g);
            int[] order = shuffledOrder(group.size, random);
            for (int destIndex = 0; destIndex < group.size; destIndex++) {
                if (++sinceCancelCheck >= CANCEL_POLL_BLOCKS) {
                    sinceCancelCheck = 0;
                    if (progress.isCancelled()) {
                        throw new EngineCancelledException("costes-randomization");
                    }
                }
                addShuffledBlock(acc, a, b, width, height, group, order[destIndex], destIndex);
            }
        }
        return acc.pearson();
    }

    private static int[] shuffledOrder(int size, Random random) {
        int[] order = new int[size];
        for (int i = 0; i < size; i++) {
            order[i] = i;
        }
        for (int i = size - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int tmp = order[i];
            order[i] = order[j];
            order[j] = tmp;
        }
        return order;
    }

    /**
     * Pairs channel A's voxels from the source block with channel B's voxels at
     * the destination block — A is shuffled, B stays put, as in the ancestor.
     *
     * <p>Only in-domain offsets are read. Both blocks share the group's pattern by
     * construction, so a destination voxel being inside the domain guarantees its
     * source counterpart is too; testing one is testing both.
     */
    private static void addShuffledBlock(WelfordAccumulator acc,
                                         double[] a, double[] b,
                                         int width, int height,
                                         BlockGroup group, int source, int destination) {
        int srcX = group.originX[source];
        int srcY = group.originY[source];
        int srcZ = group.originZ[source];
        int destX = group.originX[destination];
        int destY = group.originY[destination];
        int destZ = group.originZ[destination];
        boolean[] pattern = group.pattern;
        int at = 0;
        for (int dz = 0; dz < group.depth; dz++) {
            for (int dy = 0; dy < group.height; dy++) {
                int srcRow = index(width, height, srcX, srcY + dy, srcZ + dz);
                int destRow = index(width, height, destX, destY + dy, destZ + dz);
                for (int dx = 0; dx < group.width; dx++) {
                    if (pattern == null || pattern[at]) {
                        acc.add(a[srcRow + dx], b[destRow + dx]);
                    }
                    at++;
                }
            }
        }
    }

    private static int index(int width, int height, int x, int y, int z) {
        return (z * height + y) * width + x;
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    // ------------------------------------------------------------------
    // Internal value types
    // ------------------------------------------------------------------

    private static final class BasicStats {
        final long count;
        final double minA;
        final double maxA;
        final double minB;
        final double maxB;
        final double meanA;
        final double meanB;
        final double sumSqDevA;
        final double sumCoDev;
        final double pearson;

        BasicStats(double minA, double maxA, double minB, double maxB,
                   WelfordAccumulator acc) {
            this.count = acc.count();
            this.minA = minA;
            this.maxA = maxA;
            this.minB = minB;
            this.maxB = maxB;
            this.meanA = acc.meanA();
            this.meanB = acc.meanB();
            this.sumSqDevA = acc.sumSqDevA();
            this.sumCoDev = acc.sumCoDev();
            this.pearson = acc.pearson();
        }
    }

    private static final class Thresholds {
        final double ta;
        final double tb;
        final boolean fitted;
        final String note;

        private Thresholds(double ta, double tb, boolean fitted, String note) {
            this.ta = ta;
            this.tb = tb;
            this.fitted = fitted;
            this.note = note;
        }

        static Thresholds fitted(double ta, double tb) {
            return new Thresholds(ta, tb, true, "");
        }

        /**
         * The image minimum, which is what every bail-out path in the ancestor
         * returned without saying so.
         */
        static Thresholds fallback(BasicStats stats, String why) {
            return new Thresholds(stats.minA, stats.minB, false,
                    "Costes threshold not fitted, fell back to the domain minimum — " + why);
        }
    }

    private static final class Randomization {
        final double pValue;
        final double pFloor;
        final boolean atFloor;
        final boolean skipped;
        final String note;
        final double[] distribution;

        Randomization(double pValue, double pFloor, boolean atFloor,
                      boolean skipped, String note, double[] distribution) {
            this.pValue = pValue;
            this.pFloor = pFloor;
            this.atFloor = atFloor;
            this.skipped = skipped;
            this.note = note == null ? "" : note;
            this.distribution = distribution;
        }

        static Randomization skipped(String note) {
            return new Randomization(Double.NaN, Double.NaN, false, true, note,
                    new double[0]);
        }
    }

    /** Interchangeable block positions: same shape, same domain membership. */
    private static final class BlockGroup {
        final int width;
        final int height;
        final int depth;
        /** Null when every voxel of the block is in the domain. */
        final boolean[] pattern;
        int size;
        int[] originX;
        int[] originY;
        int[] originZ;
        private int filled;

        BlockGroup(int width, int height, int depth, boolean[] pattern) {
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.pattern = pattern != null && allTrue(pattern) ? null : pattern;
        }

        void allocate() {
            originX = new int[size];
            originY = new int[size];
            originZ = new int[size];
            filled = 0;
        }

        void add(int x, int y, int z) {
            originX[filled] = x;
            originY[filled] = y;
            originZ[filled] = z;
            filled++;
        }
    }
}
