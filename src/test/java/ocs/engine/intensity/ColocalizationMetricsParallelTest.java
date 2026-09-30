package ocs.engine.intensity;

import ocs.engine.EngineCancelledException;
import ocs.engine.EngineProgress;
import org.junit.Test;

import java.util.List;
import java.util.Random;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The parallel verification the performance contract requires for the Costes
 * randomization.
 *
 * <p>The fixture is a pair of independent channels, chosen so the permutation
 * <i>p</i> lands mid-range rather than on its floor. A p pinned at the floor
 * would agree across worker counts no matter how badly the merge was ordered; a
 * p near 0.5 moves if even one permutation is lost, duplicated or misplaced.
 *
 * <p>Nothing here asserts a speedup. The benchmark reports measured wall-clock
 * times and gates on none of them, because a CI box under load would make a
 * timing assertion a source of red builds rather than a source of information.
 */
public class ColocalizationMetricsParallelTest {

    private static final String WORKER_PREFIX = "ocs-coloc-perm-";
    private static final int SIDE = 96;

    // ------------------------------------------------------------------
    // Scientific equivalence across worker counts
    // ------------------------------------------------------------------

    @Test
    public void aFixedSeedGivesIdenticalResultsAtOneTwoAndMaxWorkers() {
        double[][] pair = independentPair(SIDE * SIDE, 101L);
        int max = Math.max(2, Runtime.getRuntime().availableProcessors());

        ColocalizationMetrics.Result serial = run(pair, 1, 64, 20260422L);
        ColocalizationMetrics.Result two = run(pair, 2, 64, 20260422L);
        ColocalizationMetrics.Result many = run(pair, max, 64, 20260422L);

        assertFalse("this fixture must actually run the null",
                serial.randomizationSkipped());
        assertTrue("and must land away from the p floor, or the comparison below is"
                        + " insensitive; got p = " + serial.costesP(),
                serial.costesP() > serial.pFloor() && serial.costesP() < 1.0);

        assertEquals("serial and two workers must agree to the bit",
                serial.costesP(), two.costesP(), 0.0);
        assertEquals("serial and " + max + " workers must agree to the bit",
                serial.costesP(), many.costesP(), 0.0);
        assertEquals(serial.pearson(), many.pearson(), 0.0);
        assertEquals(serial.costesTa(), many.costesTa(), 0.0);
        assertEquals(ColocalizationMetrics.SEED_CONTRACT_VERSION,
                serial.seedContractVersion());

        // Agreeing on p is necessary and nowhere near sufficient. p counts the
        // permutations that beat the observation, and a count is blind to which
        // permutation produced which number — a merge that scrambled the results
        // across slots entirely would still give the identical p. Pinning the
        // indexed merge needs the per-permutation array, element by element.
        assertArrayEquals("permutation i must be permutation i at every worker count",
                serial.nullDistribution(), two.nullDistribution(), 0.0);
        assertArrayEquals("permutation i must be permutation i at every worker count",
                serial.nullDistribution(), many.nullDistribution(), 0.0);
        assertEquals(64, serial.nullDistribution().length);
        assertFalse("a null distribution of one repeated value would make the check"
                        + " above vacuous",
                serial.nullDistribution()[0] == serial.nullDistribution()[1]);
    }

    @Test
    public void theSeedIsWhatDeterminesTheAnswer() {
        double[][] pair = independentPair(SIDE * SIDE, 102L);
        ColocalizationMetrics.Result first = run(pair, 4, 200, 1L);
        ColocalizationMetrics.Result repeat = run(pair, 4, 200, 1L);
        ColocalizationMetrics.Result other = run(pair, 4, 200, 2L);

        assertEquals("the same seed must reproduce exactly",
                first.costesP(), repeat.costesP(), 0.0);
        assertNotEquals("a different seed must draw a different null",
                first.costesP(), other.costesP(), 1.0e-15);
        assertEquals(1L, first.seed());
    }

    @Test
    public void permutationSeedsAreAFunctionOfTheIndexAlone() {
        // The whole point of the new contract: permutation 7 is permutation 7
        // whatever order the scheduler ran the others in. FLASH's single shared
        // Random made that impossible, which is why the numbers cannot match.
        assertEquals(ColocalizationMetrics.permutationSeed(20260422L, 7),
                ColocalizationMetrics.permutationSeed(20260422L, 7));
        assertNotEquals(ColocalizationMetrics.permutationSeed(20260422L, 7),
                ColocalizationMetrics.permutationSeed(20260422L, 8));
        assertNotEquals(ColocalizationMetrics.permutationSeed(1L, 0),
                ColocalizationMetrics.permutationSeed(2L, 0));

        // Neighbouring seeds must not give overlapping streams. A plain
        // `seed + index` would hand permutation 1 of seed 1 and permutation 0 of
        // seed 2 the identical draws — a correlated null presented as an
        // independent one, and invisible unless someone looks for it.
        assertNotEquals(ColocalizationMetrics.permutationSeed(1L, 1),
                ColocalizationMetrics.permutationSeed(2L, 0));
    }

    /**
     * Completion order must never reach the answer. The executor here holds each
     * task until all are queued, then runs them on one thread
     * last first, so the permutations complete in exactly the reverse of the order
     * they were queued in.
     */
    @Test(timeout = 120000L)
    public void completionOrderDoesNotChangeTheResult() throws InterruptedException {
        double[][] pair = independentPair(SIDE * SIDE, 103L);
        int permutations = 24;

        ColocalizationMetrics.Result inOrder = run(pair, 4, permutations, 9L);

        ExecutorService backing = Executors.newFixedThreadPool(permutations);
        ReversingExecutor reversing = new ReversingExecutor(backing, permutations);
        try {
            ColocalizationMetrics.Result reversed = ColocalizationMetrics.compute(
                    pair[0], pair[1], SIDE, SIDE, 1,
                    ColocalizationMetrics.Options.builder()
                            .permutations(permutations).seed(9L)
                            .workers(permutations).executor(reversing).build(),
                    null);
            assertEquals("the merge is by index, so a reversed completion order must"
                    + " give the identical p", inOrder.costesP(), reversed.costesP(), 0.0);
            assertArrayEquals("and the identical permutation-by-permutation null",
                    inOrder.nullDistribution(), reversed.nullDistribution(), 0.0);
        } finally {
            backing.shutdown();
            backing.awaitTermination(30L, TimeUnit.SECONDS);
        }

        assertEquals(permutations, reversing.finishRank.get());
        assertTrue("the fixture must genuinely have reversed the completion order:"
                        + " first submitted finished at rank " + reversing.rankOfFirst
                        + ", last submitted at rank " + reversing.rankOfLast,
                reversing.rankOfFirst > reversing.rankOfLast);
    }

    @Test
    public void anInjectedExecutorIsNotShutDownByTheRun() {
        double[][] pair = independentPair(48 * 48, 104L);
        ExecutorService caller = Executors.newFixedThreadPool(2);
        try {
            ColocalizationMetrics.compute(pair[0], pair[1], 48, 48, 1,
                    ColocalizationMetrics.Options.builder()
                            .permutations(8).workers(2).executor(caller).build(),
                    null);
            assertFalse("a caller-owned pool must survive the run", caller.isShutdown());
        } finally {
            caller.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // Cancellation
    // ------------------------------------------------------------------

    @Test
    public void cancellationWhileWorkIsQueuedThrowsAndLeavesNothingBehind() {
        double[][] pair = independentPair(SIDE * SIDE, 111L);
        CancellableProgress progress = new CancellableProgress();
        progress.cancel();

        try {
            ColocalizationMetrics.compute(pair[0], pair[1], SIDE, SIDE, 1,
                    ColocalizationMetrics.Options.builder()
                            .permutations(400).workers(2).build(),
                    progress);
            fail("a cancelled run must never return a result — a half-finished null"
                    + " looks identical to a finished one in a table");
        } catch (EngineCancelledException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("costes-randomization"));
        }
        assertNoWorkerThreadsRemain();
    }

    @Test
    public void cancellationWhileWorkIsRunningThrowsAndLeavesNothingBehind() {
        int side = 512;
        double[][] pair = independentPair(side * side, 112L);
        // Counts only polls made from worker threads, so the cancellation lands
        // inside a running block loop rather than while the queue is filling.
        CancellableProgress progress = new CancellableProgress();
        progress.cancelAfterWorkerPolls(40);

        try {
            ColocalizationMetrics.compute(pair[0], pair[1], side, side, 1,
                    ColocalizationMetrics.Options.builder()
                            .permutations(200).workers(2).build(),
                    progress);
            fail("cancellation mid-permutation must still refuse to return a result");
        } catch (EngineCancelledException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("costes-randomization"));
        }
        assertTrue("the cancellation must have come from inside a permutation",
                progress.workerPolls.get() >= 40);
        assertNoWorkerThreadsRemain();
    }

    /**
     * Cancelling must drain the queue, not merely stop waiting on it.
     *
     * <p>On a pool this class owns, {@code shutdownNow} would hide a missing
     * drain: it empties the queue itself. The drain is only observable on a
     * caller-owned executor, which must not be shut down — so that is what this
     * uses.
     *
     * <p>The executor is gated so the count cannot depend on machine load. Its
     * single worker starts only once all four hundred permutations are queued,
     * and stops taking tasks the moment the cancellation trips. The queue is
     * released only after {@code compute} has returned — by which time the
     * coordinator has either cancelled every queued future or it has not. A
     * cancelled future runs as a no-op and never polls; an uncancelled one polls
     * once and bails. So the count is exact: the three polls that tripped the
     * cancellation with a drain, and all four hundred permutations without one.
     * Earlier versions timed a race between the drain loop and a free-running
     * worker, which counted 88 of 400 on a loaded machine.
     */
    @Test
    public void cancellationCancelsQueuedWorkRatherThanLettingItRun()
            throws InterruptedException {
        int side = 256;
        double[][] pair = independentPair(side * side, 116L);
        int permutations = 400;
        int tripAt = 3;
        GatedExecutor caller = new GatedExecutor(permutations);
        CountingProgress progress =
                new CountingProgress(Thread.currentThread(), tripAt, caller);

        try {
            ColocalizationMetrics.compute(pair[0], pair[1], side, side, 1,
                    ColocalizationMetrics.Options.builder()
                            .permutations(permutations).workers(2).executor(caller).build(),
                    progress);
            fail("a cancelled run must never return a result");
        } catch (EngineCancelledException expected) {
            assertTrue(expected.getMessage().contains("costes-randomization"));
        } finally {
            // Only now may the remaining queue run. Whatever was not cancelled
            // polls once as it starts, and is counted.
            caller.release();
            caller.shutdown();
            assertTrue("the gated worker must finish the queue",
                    caller.awaitTermination(60L, TimeUnit.SECONDS));
        }

        assertEquals("every permutation must have been queued before any ran",
                permutations, caller.submitted());
        int entered = progress.offThreadPolls.get();
        System.out.println("[cancellation] " + entered + " polls from " + permutations
                + " queued permutations after the queue was drained");
        assertEquals("queued permutations must be cancelled, not merely unwaited-for;"
                        + " only the polls that tripped the cancellation may be counted",
                tripAt, entered);
    }

    @Test
    public void cancellationOnTheSerialPathThrowsToo() {
        double[][] pair = independentPair(48 * 48, 113L);
        CancellableProgress progress = new CancellableProgress();
        progress.cancel();
        try {
            ColocalizationMetrics.compute(pair[0], pair[1], 48, 48, 1,
                    ColocalizationMetrics.Options.builder()
                            .permutations(10).workers(1).build(),
                    progress);
            fail("the serial override must honour cancellation as well");
        } catch (EngineCancelledException expected) {
            assertTrue(expected.getMessage().contains("costes-randomization"));
        }
    }

    /**
     * A task that fails for a reason other than cancellation must surface that
     * reason, drain the rest and leave no threads. Injected through the progress
     * reporter, because that is the only call a running permutation makes into
     * caller-supplied code.
     */
    @Test
    public void theFirstFailureDrainsTheRestAndSurfacesItself() {
        int side = 256;
        double[][] pair = independentPair(side * side, 114L);
        try {
            ColocalizationMetrics.compute(pair[0], pair[1], side, side, 1,
                    ColocalizationMetrics.Options.builder()
                            .permutations(200).workers(2).build(),
                    new ExplodingProgress(5));
            fail("a worker failure must not be swallowed into a plausible p value");
        } catch (IllegalStateException expected) {
            assertEquals("injected worker failure", expected.getMessage());
        }
        assertNoWorkerThreadsRemain();
    }

    @Test
    public void theSerialOverrideLeavesNoThreadsBehindEither() {
        double[][] pair = independentPair(48 * 48, 115L);
        ColocalizationMetrics.compute(pair[0], pair[1], 48, 48, 1,
                ColocalizationMetrics.Options.builder()
                        .permutations(16).workers(1).build(),
                null);
        assertNoWorkerThreadsRemain();
    }

    // ------------------------------------------------------------------
    // Benchmark — reported, never asserted
    // ------------------------------------------------------------------

    @Test
    public void benchmarkFixtureReportsMeasuredTimings() {
        int side = 512;
        int depth = 4;
        double[][] pair = independentPair(side * side * depth, 121L);
        int max = Math.max(2, Runtime.getRuntime().availableProcessors());
        int permutations = 100;

        double reference = Double.NaN;
        int[] workerCounts = new int[] {1, 2, max};
        for (int w = 0; w < workerCounts.length; w++) {
            long start = System.nanoTime();
            ColocalizationMetrics.Result result = ColocalizationMetrics.compute(
                    pair[0], pair[1], side, side, depth,
                    ColocalizationMetrics.Options.builder()
                            .permutations(permutations).seed(20260422L)
                            .workers(workerCounts[w]).build(),
                    null);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            System.out.println("[benchmark] " + side + "x" + side + "x" + depth
                    + " = " + (side * side * depth) + " voxels, " + permutations
                    + " permutations, block " + result.blockWidth() + "x"
                    + result.blockHeight() + "x" + result.blockDepth() + ", "
                    + workerCounts[w] + " worker(s): " + elapsedMs + " ms, p = "
                    + result.costesP());
            if (w == 0) {
                reference = result.costesP();
            } else {
                assertEquals("equivalence, not speed, is what is gated here",
                        reference, result.costesP(), 0.0);
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static ColocalizationMetrics.Result run(double[][] pair, int workers,
                                                    int permutations, long seed) {
        return ColocalizationMetrics.compute(pair[0], pair[1], SIDE, SIDE, 1,
                ColocalizationMetrics.Options.builder()
                        .permutations(permutations).seed(seed).workers(workers).build(),
                null);
    }

    private static double[][] independentPair(int count, long seed) {
        Random random = new Random(seed);
        double[] a = new double[count];
        double[] b = new double[count];
        for (int i = 0; i < count; i++) {
            a[i] = 10.0 + 90.0 * random.nextDouble();
            b[i] = 12.0 + 85.0 * random.nextDouble();
        }
        return new double[][] {a, b};
    }

    private static int liveWorkerThreads() {
        Thread[] threads = new Thread[Thread.activeCount() * 2 + 64];
        int found = Thread.enumerate(threads);
        int count = 0;
        for (int i = 0; i < found; i++) {
            if (threads[i] != null && threads[i].isAlive()
                    && threads[i].getName().startsWith(WORKER_PREFIX)) {
                count++;
            }
        }
        return count;
    }

    /** Bounded, because a worker that has returned may take a moment to die. */
    private static void assertNoWorkerThreadsRemain() {
        long deadline = System.currentTimeMillis() + 10_000L;
        int live = liveWorkerThreads();
        while (live > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            live = liveWorkerThreads();
        }
        assertEquals("a cancelled or failed run must leave no worker threads", 0, live);
    }

    /** Cancels immediately, or after a given number of polls from a worker thread. */
    private static final class CancellableProgress implements EngineProgress {
        final AtomicInteger workerPolls = new AtomicInteger();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile int cancelAfter = Integer.MAX_VALUE;

        void cancel() {
            cancelled.set(true);
        }

        void cancelAfterWorkerPolls(int count) {
            cancelAfter = count;
        }

        @Override
        public void report(String stage, double fraction) {
            // deliberately empty
        }

        @Override
        public boolean isCancelled() {
            if (!cancelled.get()
                    && Thread.currentThread().getName().startsWith(WORKER_PREFIX)
                    && workerPolls.incrementAndGet() >= cancelAfter) {
                cancelled.set(true);
            }
            return cancelled.get();
        }
    }

    /**
     * Cancels on the given poll from a worker, closes the executor's gate at that
     * moment, and counts every worker poll. Counts by "not the thread that started
     * the run" rather than by thread name, because the executor under test here is
     * caller-owned and its thread carries its own name.
     */
    private static final class CountingProgress implements EngineProgress {
        final AtomicInteger offThreadPolls = new AtomicInteger();
        private final Thread coordinator;
        private final int cancelAfter;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final GatedExecutor gate;

        CountingProgress(Thread coordinator, int cancelAfter, GatedExecutor gate) {
            this.coordinator = coordinator;
            this.cancelAfter = cancelAfter;
            this.gate = gate;
        }

        @Override
        public void report(String stage, double fraction) {
            // deliberately empty
        }

        @Override
        public boolean isCancelled() {
            if (Thread.currentThread() != coordinator
                    && offThreadPolls.incrementAndGet() >= cancelAfter
                    && cancelled.compareAndSet(false, true)) {
                gate.close();
            }
            return cancelled.get();
        }
    }

    /**
     * One worker thread that starts only once {@code expected} tasks are queued,
     * and takes no further task after {@link #close} until {@link #release}. That
     * pins the two things a load-dependent test cannot: the whole queue exists
     * before anything runs, and nothing runs between the cancellation and the
     * drain.
     */
    private static final class GatedExecutor extends AbstractExecutorService {

        private final int expected;
        private final java.util.concurrent.LinkedBlockingQueue<Runnable> queue =
                new java.util.concurrent.LinkedBlockingQueue<Runnable>();
        private final java.util.concurrent.CountDownLatch allQueued;
        private final java.util.concurrent.CountDownLatch released =
                new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch terminated =
                new java.util.concurrent.CountDownLatch(1);
        private final AtomicInteger submitted = new AtomicInteger();
        private volatile boolean closed;
        private volatile boolean shutdown;

        GatedExecutor(int expected) {
            this.expected = expected;
            this.allQueued = new java.util.concurrent.CountDownLatch(expected);
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        work();
                    } finally {
                        terminated.countDown();
                    }
                }
            }, "gated-test-worker");
            worker.setDaemon(true);
            worker.start();
        }

        private void work() {
            try {
                allQueued.await(60L, TimeUnit.SECONDS);
                while (true) {
                    if (closed) {
                        released.await(60L, TimeUnit.SECONDS);
                    }
                    Runnable next = queue.poll(50L, TimeUnit.MILLISECONDS);
                    if (next != null) {
                        next.run();
                    } else if (shutdown) {
                        return;
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        void close() {
            closed = true;
        }

        void release() {
            released.countDown();
        }

        int submitted() {
            return submitted.get();
        }

        @Override
        public void execute(Runnable command) {
            if (shutdown) {
                throw new java.util.concurrent.RejectedExecutionException("shut down");
            }
            queue.add(command);
            submitted.incrementAndGet();
            allQueued.countDown();
        }

        @Override
        public void shutdown() {
            shutdown = true;
            // A run that queued fewer than expected must not strand the worker.
            while (allQueued.getCount() > 0) {
                allQueued.countDown();
            }
            release();
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown();
            List<Runnable> left = new java.util.ArrayList<Runnable>();
            queue.drainTo(left);
            return left;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return terminated.getCount() == 0;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
            return terminated.await(timeout, unit);
        }
    }

    /** Throws from inside a worker, which is the only failure a permutation can raise. */
    private static final class ExplodingProgress implements EngineProgress {
        private final AtomicInteger polls = new AtomicInteger();
        private final int explodeAfter;

        ExplodingProgress(int explodeAfter) {
            this.explodeAfter = explodeAfter;
        }

        @Override
        public void report(String stage, double fraction) {
            // deliberately empty
        }

        @Override
        public boolean isCancelled() {
            if (Thread.currentThread().getName().startsWith(WORKER_PREFIX)
                    && polls.incrementAndGet() >= explodeAfter) {
                throw new IllegalStateException("injected worker failure");
            }
            return false;
        }
    }

    /**
     * Runs every task only once all are queued, last submitted first, so the
     * permutations complete backwards. Only {@code execute} needs overriding —
     * {@link AbstractExecutorService} builds {@code submit} on top of it.
     */
    private static final class ReversingExecutor extends AbstractExecutorService {

        final AtomicInteger finishRank = new AtomicInteger();
        volatile int rankOfFirst = -1;
        volatile int rankOfLast = -1;

        private final ExecutorService delegate;
        private final int total;
        private final AtomicInteger submitted = new AtomicInteger();

        ReversingExecutor(ExecutorService delegate, int total) {
            this.delegate = delegate;
            this.total = total;
        }

        private final List<Runnable> held = new java.util.ArrayList<Runnable>();
        private final List<Integer> heldIndex = new java.util.ArrayList<Integer>();

        /**
         * Holds each task instead of running it. The held batch is released the
         * first time the coordinator waits on any of its futures, which it does
         * only after it has queued the whole batch; the batch then runs on one
         * delegate thread from last to first. The completion order is therefore
         * exactly the reverse of the submission order on any machine under any
         * load. The sleep-scaled delays this replaces reversed it only when the
         * scheduler cooperated, and failed under load.
         */
        @Override
        public void execute(final Runnable command) {
            synchronized (held) {
                heldIndex.add(Integer.valueOf(submitted.getAndIncrement()));
                held.add(command);
            }
        }

        private void release() {
            final List<Runnable> batch;
            final List<Integer> indices;
            synchronized (held) {
                if (held.isEmpty()) {
                    return;
                }
                batch = new java.util.ArrayList<Runnable>(held);
                indices = new java.util.ArrayList<Integer>(heldIndex);
                held.clear();
                heldIndex.clear();
            }
            delegate.execute(new Runnable() {
                @Override
                public void run() {
                    for (int i = batch.size() - 1; i >= 0; i--) {
                        batch.get(i).run();
                        int rank = finishRank.incrementAndGet();
                        int index = indices.get(i).intValue();
                        if (index == 0) {
                            rankOfFirst = rank;
                        }
                        if (index == total - 1) {
                            rankOfLast = rank;
                        }
                    }
                }
            });
        }

        @Override
        protected <T> java.util.concurrent.RunnableFuture<T> newTaskFor(
                java.util.concurrent.Callable<T> callable) {
            return new ReleasingFuture<T>(callable);
        }

        @Override
        protected <T> java.util.concurrent.RunnableFuture<T> newTaskFor(
                Runnable runnable, T value) {
            return new ReleasingFuture<T>(java.util.concurrent.Executors.callable(runnable, value));
        }

        /** Releases the held batch before the coordinator blocks on it. */
        private final class ReleasingFuture<T> extends java.util.concurrent.FutureTask<T> {
            ReleasingFuture(java.util.concurrent.Callable<T> callable) {
                super(callable);
            }

            @Override
            public T get() throws InterruptedException,
                    java.util.concurrent.ExecutionException {
                release();
                return super.get();
            }

            @Override
            public T get(long timeout, TimeUnit unit) throws InterruptedException,
                    java.util.concurrent.ExecutionException,
                    java.util.concurrent.TimeoutException {
                release();
                return super.get(timeout, unit);
            }
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
