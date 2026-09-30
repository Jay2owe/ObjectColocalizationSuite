package ocs.nullmodel;

import ij.ImagePlus;
import ocs.engine.ColocEngine;
import ocs.engine.DirectionKey;
import ocs.engine.EngineCancelledException;
import ocs.engine.EngineInputs;
import ocs.engine.EngineProgress;
import ocs.engine.EngineResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs every enabled engine against chance.
 *
 * <p>An engine says "sixty per cent of these objects touch a partner". This says
 * whether sixty is a lot. It shuffles the objects to random positions inside the
 * user's region — keeping their shapes, sizes and counts — re-runs the same
 * engines on the shuffled field, and repeats. If the real sixty sits inside the
 * spread of shuffled answers, the method has found nothing on this data, however
 * confident its number looked.
 *
 * <h2>One shuffled field, every engine</h2>
 *
 * <p>The permuted label images are built once per permutation and handed to all
 * engines together. At five channels (twenty ordered directions), ten methods
 * and a thousand permutations the naive alternative is around 120,000 engine
 * invocations per image with the shuffle repeated inside each; sharing the field
 * is what makes the layer affordable at all. It also makes the comparison
 * fairer: two methods disagreeing on the same shuffled field disagree about the
 * method, not about which shuffle they happened to get.
 *
 * <h2>The seed, and why it is not one stream</h2>
 *
 * <p>Permutation <i>i</i> draws from {@code new Random(seed + i)}. The obvious
 * alternative — one {@code Random(seed)} feeding every permutation in turn —
 * makes permutation <i>i</i> depend on every draw before it, so running them in
 * parallel changes the numbers. Per-permutation seeding costs nothing, makes
 * row <i>i</i> reproducible on its own, and is what lets the worker count be a
 * performance setting rather than part of the result.
 *
 * <p><b>This is version 1 of that contract.</b> Changing how a permutation is
 * seeded changes every number this class produces, so it is recorded with the
 * results and versioned rather than adjusted quietly.
 */
public final class NullModelRunner {

    public static final int DEFAULT_PERMUTATIONS = 1000;

    /** Fixed, stated, and not a timestamp — an unrecorded seed is not a seed. */
    public static final long DEFAULT_SEED = 20260812L;

    /** Bumped whenever the meaning of a seed changes. Travels with the results. */
    public static final int SEED_CONTRACT_VERSION = 1;

    /**
     * How a channel is shuffled by default.
     *
     * <p>Whole-channel displacement runs on crowded and confluent segmentations
     * that the stronger per-object null cannot re-pack. The default changed in
     * Stage 06 after per-object refused all 27 surveyed four-channel fields;
     * callers needing the stronger hypothesis can still select it explicitly.
     */
    public static final NullModelKind DEFAULT_KIND = NullModelKind.WHOLE_CHANNEL;

    private final int permutations;
    private final long seed;
    private final int workers;
    private final NullModelKind kind;
    private final ExecutorService executor;

    private NullModelRunner(int permutations, long seed, int workers,
            NullModelKind kind, ExecutorService executor) {
        this.permutations = permutations;
        this.seed = seed;
        this.workers = workers;
        this.kind = kind;
        this.executor = executor;
    }

    public static Builder builder() {
        return new Builder();
    }

    public int permutations() {
        return permutations;
    }

    public long seed() {
        return seed;
    }

    /** Which way channels are shuffled. Part of the run record, not a knob. */
    public NullModelKind kind() {
        return kind;
    }

    /**
     * Runs the null model for every engine and every ordered channel pair.
     *
     * @throws IllegalArgumentException if {@code inputs} carries no region ROI.
     *         A null model without a domain is not a weaker null model, it is a
     *         different one — objects scattered over the whole frame including
     *         the parts that are not tissue collide less by chance, which makes
     *         every observation look more significant than it is. Refused rather
     *         than defaulted, per {@code 02_CONTRACT.md}.
     * @throws EngineCancelledException if cancelled; never returns a partial
     *         distribution, because a half-finished permutation set is
     *         indistinguishable from a complete one in every summary column
     */
    public List<NullModelResult> run(List<ColocEngine> engines, EngineInputs inputs,
            EngineProgress progress) {
        if (engines == null || engines.isEmpty()) {
            return Collections.unmodifiableList(new ArrayList<NullModelResult>());
        }
        if (inputs.domain().isEmpty()) {
            throw new IllegalArgumentException(
                    "a null model needs a region ROI defining where objects may be "
                            + "scattered; refusing rather than defaulting to the whole image");
        }
        requireRegionInsideTheImage(inputs);
        EngineProgress reporter = progress == null ? EngineProgress.SILENT : progress;

        // Observed first. It decides which engines can be permuted at all, and it
        // is what the permuted distribution is measured against.
        Map<String, EngineResult> observed = new LinkedHashMap<String, EngineResult>();
        for (int i = 0; i < engines.size(); i++) {
            requireNotCancelled(reporter);
            ColocEngine engine = engines.get(i);
            observed.put(engine.id(), engine.compute(inputs, cancellationOf(reporter)));
        }

        List<ColocEngine> permutable = new ArrayList<ColocEngine>();
        List<NullModelResult> results = new ArrayList<NullModelResult>();
        for (int i = 0; i < engines.size(); i++) {
            ColocEngine engine = engines.get(i);
            EngineResult result = observed.get(engine.id());
            NullModelResult.Skip skip = skipReasonFor(result, engine.id(), kind);
            if (skip == NullModelResult.Skip.NONE) {
                permutable.add(engine);
            } else {
                for (DirectionKey direction : result.directions()) {
                    results.add(NullModelResult.skipped(engine.id(), direction,
                            statistic(result, direction), skip));
                }
            }
        }
        if (permutable.isEmpty()) {
            return Collections.unmodifiableList(results);
        }

        List<Shuffler> shufflers = shufflersFor(inputs);

        Set<String> failed = Collections.newSetFromMap(
                new ConcurrentHashMap<String, Boolean>());
        double[][] permutedStatistics = runPermutations(permutable, inputs, shufflers,
                reporter, observed, failed);
        if (permutedStatistics == null) {
            for (int e = 0; e < permutable.size(); e++) {
                ColocEngine engine = permutable.get(e);
                EngineResult result = observed.get(engine.id());
                for (DirectionKey direction : result.directions()) {
                    results.add(NullModelResult.skipped(engine.id(), direction,
                            statistic(result, direction),
                            NullModelResult.Skip.NO_ROOM_IN_DOMAIN));
                }
            }
            return Collections.unmodifiableList(results);
        }

        List<Slot> slots = slotsFor(permutable, observed);
        for (int s = 0; s < slots.size(); s++) {
            Slot slot = slots.get(s);
            if (failed.contains(slot.engineId)) {
                results.add(NullModelResult.skipped(slot.engineId, slot.direction,
                        slot.observed, NullModelResult.Skip.FAILED_ON_A_SHUFFLE));
                continue;
            }
            double[] column = new double[permutations];
            for (int p = 0; p < permutations; p++) {
                column[p] = permutedStatistics[p][s];
            }
            results.add(NullModelResult.of(slot.engineId, slot.direction,
                    slot.observed, column, seed));
        }
        return Collections.unmodifiableList(results);
    }

    /**
     * Refuses a region that covers no pixel of the image.
     *
     * <p>Almost always a ROI set saved from a different image. Per-object
     * shuffling would then find no room for any object, and whole-channel
     * shuffling, which does not read the region, would run and report a test
     * the region never constrained.
     */
    private static void requireRegionInsideTheImage(EngineInputs inputs) {
        ImagePlus first = inputs.labelImages().get(0);
        boolean[] plane = ObjectPermuter.domainMask(inputs.domain(),
                first.getWidth(), first.getHeight(), 1);
        for (int i = 0; i < plane.length; i++) {
            if (plane[i]) {
                return;
            }
        }
        throw new IllegalArgumentException("the region ROI covers no pixel of the "
                + first.getWidth() + " x " + first.getHeight() + " image; was it"
                + " drawn on a different image?");
    }

    /**
     * One shuffled copy of a channel, however this run shuffles.
     *
     * <p>An adapter rather than a shared supertype on the two shufflers, because
     * they are not variants of one idea: {@link ObjectPermuter} can refuse and
     * {@link ChannelDisplacer} cannot, and their public shapes should say so.
     */
    private interface Shuffler {
        /** @return a fresh image, or null if this shuffle could not be made */
        ImagePlus shuffle(Random random);
    }

    private List<Shuffler> shufflersFor(EngineInputs inputs) {
        List<Shuffler> shufflers = new ArrayList<Shuffler>();
        List<ImagePlus> labels = inputs.labelImages();
        if (kind == NullModelKind.WHOLE_CHANNEL) {
            for (int c = 0; c < labels.size(); c++) {
                final ChannelDisplacer displacer =
                        ChannelDisplacer.of(labels.get(c));
                shufflers.add(new Shuffler() {
                    @Override
                    public ImagePlus shuffle(Random random) {
                        return displacer.displace(random);
                    }
                });
            }
            return shufflers;
        }
        ImagePlus first = labels.get(0);
        boolean[] domain = ObjectPermuter.domainMask(inputs.domain(),
                first.getWidth(), first.getHeight(), first.getStackSize());
        for (int c = 0; c < labels.size(); c++) {
            final ObjectPermuter permuter =
                    ObjectPermuter.of(labels.get(c), domain);
            shufflers.add(new Shuffler() {
                @Override
                public ImagePlus shuffle(Random random) {
                    return permuter.permute(random);
                }
            });
        }
        return shufflers;
    }

    /**
     * @return statistics indexed {@code [permutation][slot]}, or {@code null} if
     *         any permutation could not place its objects inside the domain
     */
    private double[][] runPermutations(final List<ColocEngine> engines,
            final EngineInputs inputs, final List<Shuffler> shufflers,
            final EngineProgress progress,
            final Map<String, EngineResult> observed, final Set<String> failed) {

        final double[][] statistics = new double[permutations][];
        // Workers see the user's Stop but not the progress bar: a permutation's
        // engines report their own fractions, which would make the bar jump
        // back and forth between shuffles.
        final EngineProgress watch = cancellationOf(progress);
        int threads = Math.max(1, Math.min(workers, permutations));
        // An injected pool always takes the parallel path, whatever the worker
        // count says. `workers` sizes a pool this class would create; a caller
        // who hands one in has already decided its size, and silently running
        // serially instead would mean an injected single-thread pool — the
        // ordinary way to force a deterministic completion order — exercised the
        // branch it was injected to avoid.
        if (threads == 1 && executor == null) {
            for (int p = 0; p < permutations; p++) {
                requireNotCancelled(progress);
                statistics[p] = onePermutation(p, engines, inputs, shufflers,
                        observed, failed, watch);
                if (statistics[p] == null) {
                    return null;
                }
                progress.report("null model", (p + 1.0) / permutations);
            }
            return statistics;
        }

        ExecutorService injected = executor;
        ExecutorService pool = injected != null ? injected
                : Executors.newFixedThreadPool(threads, new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable runnable) {
                        Thread thread = new Thread(runnable, "ocs-null-model");
                        thread.setDaemon(true);
                        return thread;
                    }
                });
        List<Future<double[]>> futures = new ArrayList<Future<double[]>>(permutations);
        try {
            for (int p = 0; p < permutations; p++) {
                final int index = p;
                futures.add(pool.submit(new Callable<double[]>() {
                    @Override
                    public double[] call() {
                        return onePermutation(index, engines, inputs, shufflers,
                                observed, failed, watch);
                    }
                }));
            }
            for (int p = 0; p < permutations; p++) {
                if (progress.isCancelled()) {
                    // Drain before throwing. Leaving queued permutations to run
                    // against a cancelled run wastes minutes of a user's time
                    // after they have already pressed Stop.
                    for (int q = p; q < futures.size(); q++) {
                        futures.get(q).cancel(true);
                    }
                    throw new EngineCancelledException("null model cancelled");
                }
                // Indexed, never by completion order. Assigning results in the
                // order workers happen to finish is the bug three modules in this
                // family shipped, and no aggregate assertion can see it.
                statistics[p] = await(futures, p, progress);
                if (statistics[p] == null) {
                    for (int q = p + 1; q < futures.size(); q++) {
                        futures.get(q).cancel(true);
                    }
                    return null;
                }
                progress.report("null model", (p + 1.0) / permutations);
            }
        } finally {
            // A caller-owned pool is left running. Shutting down a pool this
            // class did not create would kill work the caller is still using,
            // and the caller has no way to know a run did that to them.
            if (injected == null) {
                pool.shutdownNow();
                try {
                    pool.awaitTermination(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return statistics;
    }

    /** @return one statistic per slot, or {@code null} if the shuffle failed */
    private double[] onePermutation(int index, List<ColocEngine> engines,
            EngineInputs inputs, List<Shuffler> shufflers,
            Map<String, EngineResult> observed, Set<String> failed,
            EngineProgress watch) {
        // Version 1 of the seed contract: permutation i is Random(seed + i),
        // independent of every other permutation and therefore of worker count.
        Random random = new Random(seed + index);
        List<ImagePlus> permutedLabels = new ArrayList<ImagePlus>(shufflers.size());
        for (int c = 0; c < shufflers.size(); c++) {
            requireNotCancelled(watch);
            ImagePlus permuted = shufflers.get(c).shuffle(random);
            if (permuted == null) {
                return null;
            }
            permutedLabels.add(permuted);
        }

        EngineInputs permutedInputs = EngineInputs.builder(permutedLabels)
                .intensityImages(inputs.intensityImages())
                .channelNames(inputs.channelNames())
                .domain(inputs.domain())
                .calibration(inputs.calibration())
                .build();

        List<Double> values = new ArrayList<Double>();
        for (int e = 0; e < engines.size(); e++) {
            ColocEngine engine = engines.get(e);
            requireNotCancelled(watch);
            EngineResult result;
            try {
                result = failed.contains(engine.id()) ? null
                        : engine.compute(permutedInputs, watch);
            } catch (EngineCancelledException cancelled) {
                throw cancelled;
            } catch (IllegalArgumentException | IllegalStateException unmeasurable) {
                // One method that cannot measure a shuffled copy loses its own
                // chance test, not everyone's: the run keeps its other methods'
                // p values and says why this one has none. Which shuffles fail
                // is fixed by the seed, so the outcome is reproducible.
                failed.add(engine.id());
                result = null;
            }
            // Slots stay aligned with the observed directions either way.
            List<DirectionKey> directions = observed.get(engine.id()).directions();
            for (DirectionKey direction : directions) {
                values.add(Double.valueOf(result == null
                        ? Double.NaN : statistic(result, direction)));
            }
        }
        double[] statistics = new double[values.size()];
        for (int i = 0; i < statistics.length; i++) {
            statistics[i] = values.get(i).doubleValue();
        }
        return statistics;
    }

    /**
     * The number the null model tests: how many source objects were called
     * coincident.
     *
     * <p>The coincident flag rather than the mean value, because it is what a
     * user acts on — they threshold and then count objects — and because it is
     * the one quantity every engine produces on the same scale, which is what
     * lets one permuted field serve all of them.
     */
    private static double statistic(EngineResult result, DirectionKey direction) {
        return result.coincidentCount(direction);
    }

    /**
     * Whether an engine can be permuted at all.
     *
     * <p>Two separate questions, and they are asked in this order on purpose.
     * The first is what the engine actually produced — a curve, a whole-image
     * number, nothing at all — which is decided from the result rather than from
     * the family it declares. The second is whether this particular shuffle
     * would distort what the engine measures even though it produced exactly the
     * right shape of answer; that is the seam, and it depends on the null model
     * rather than on the engine's output.
     *
     * <p>Order matters only for which reason a reader is shown, and the
     * production reasons come first because they are about the data in front of
     * the user, while the wrap is about the choice of null.
     */
    private static NullModelResult.Skip skipReasonFor(EngineResult result,
            String engineId, NullModelKind kind) {
        if (result.hasObjectScores()) {
            return kind.distorts(engineId)
                    ? NullModelResult.Skip.DISTORTED_BY_WRAP
                    : NullModelResult.Skip.NONE;
        }
        if (result.hasCurves()) {
            return NullModelResult.Skip.CARRIES_ITS_OWN_ENVELOPE;
        }
        if (result.isWholeDirection()) {
            return NullModelResult.Skip.INVARIANT_UNDER_PERMUTATION;
        }
        return NullModelResult.Skip.TOO_FEW_OBJECTS;
    }

    private List<Slot> slotsFor(List<ColocEngine> engines, Map<String, EngineResult> observed) {
        List<Slot> slots = new ArrayList<Slot>();
        for (int e = 0; e < engines.size(); e++) {
            ColocEngine engine = engines.get(e);
            EngineResult result = observed.get(engine.id());
            for (DirectionKey direction : result.directions()) {
                slots.add(new Slot(engine.id(), direction, statistic(result, direction)));
            }
        }
        return slots;
    }

    /** How often the coordinator looks up from a running permutation for Stop. */
    private static final long POLL_MILLIS = 50L;

    /**
     * Waits for permutation {@code p} while watching for Stop.
     *
     * <p>A plain {@code get()} would wait out the whole permutation first. On a
     * large field one permutation re-runs every method over every channel, which
     * is seconds, and the GUI checks measured Escape taking eight of them to
     * stop a run. Draining the rest of the queue happens here too, so a
     * cancelled run leaves no permutation to start after it.
     */
    private static double[] await(List<Future<double[]>> futures, int p,
            EngineProgress progress) {
        Future<double[]> future = futures.get(p);
        while (true) {
            if (progress.isCancelled()) {
                for (int q = p; q < futures.size(); q++) {
                    futures.get(q).cancel(true);
                }
                throw new EngineCancelledException("null model cancelled");
            }
            try {
                return get(future, POLL_MILLIS);
            } catch (TimeoutException stillRunning) {
                // look for Stop again
            }
        }
    }

    private static double[] get(Future<double[]> future, long millis)
            throws TimeoutException {
        try {
            return future.get(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new EngineCancelledException("null model interrupted");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException("null model permutation failed", cause);
        }
    }

    /** Passes Stop through and nothing else. */
    private static EngineProgress cancellationOf(final EngineProgress progress) {
        return new EngineProgress() {
            @Override
            public void report(String stage, double fraction) {
                // deliberately empty
            }

            @Override
            public boolean isCancelled() {
                return progress.isCancelled();
            }
        };
    }

    private static void requireNotCancelled(EngineProgress progress) {
        if (progress.isCancelled()) {
            throw new EngineCancelledException("null model cancelled");
        }
    }

    /** One engine, one direction: the column a permuted statistic lands in. */
    private static final class Slot {
        private final String engineId;
        private final DirectionKey direction;
        private final double observed;

        private Slot(String engineId, DirectionKey direction, double observed) {
            this.engineId = engineId;
            this.direction = direction;
            this.observed = observed;
        }
    }

    public static final class Builder {

        private int permutations = DEFAULT_PERMUTATIONS;
        private long seed = DEFAULT_SEED;
        private int workers = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        private NullModelKind kind = DEFAULT_KIND;
        private ExecutorService executor;

        private Builder() {
        }

        /**
         * Which way to shuffle a channel.
         *
         * <p>Not a performance setting. The two kinds test different null
         * hypotheses and their <i>p</i> values are not comparable, so whichever
         * is chosen must be reported alongside the numbers it produced.
         *
         * @throws IllegalArgumentException if {@code kind} is null. Defaulting a
         *         null here would pick a null hypothesis on the caller's behalf
         */
        public Builder kind(NullModelKind kind) {
            if (kind == null) {
                throw new IllegalArgumentException("a null model kind is"
                        + " required; it decides what the p value means and"
                        + " cannot be left to a default at this level");
            }
            this.kind = kind;
            return this;
        }

        public Builder permutations(int permutations) {
            if (permutations < 1) {
                throw new IllegalArgumentException(
                        "at least one permutation, got " + permutations);
            }
            this.permutations = permutations;
            return this;
        }

        public Builder seed(long seed) {
            this.seed = seed;
            return this;
        }

        /** {@code 1} forces the serial path — the override the contract requires. */
        public Builder workers(int workers) {
            if (workers < 1) {
                throw new IllegalArgumentException("at least one worker, got " + workers);
            }
            this.workers = workers;
            return this;
        }

        /**
         * A caller-owned pool to run the permutations on instead of one this
         * class creates and disposes of.
         *
         * <p>Matches {@code PerObjectIntensityEngine}, which takes the same
         * seam for the same two reasons. A caller running many images already
         * has a pool and should not have one created per image beneath it. And
         * a test cannot otherwise force a completion order: with a pool this
         * class owns, workers finish in whatever order the machine gives, which
         * is exactly the ordering an index-versus-completion-order bug survives.
         *
         * <p>The run never shuts an injected pool down. {@code null} — the
         * default — restores the owned-pool behaviour.
         */
        public Builder executor(ExecutorService executor) {
            this.executor = executor;
            return this;
        }

        public NullModelRunner build() {
            return new NullModelRunner(permutations, seed, workers, kind, executor);
        }
    }
}
