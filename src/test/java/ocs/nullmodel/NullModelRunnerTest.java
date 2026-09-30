package ocs.nullmodel;

import ij.ImagePlus;
import ij.gui.Roi;
import ocs.engine.ColocEngine;
import ocs.engine.DirectionKey;
import ocs.engine.EngineCancelledException;
import ocs.engine.EngineInputs;
import ocs.engine.EngineProgress;
import ocs.engine.EngineRegistry;
import ocs.engine.EngineResult;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The null-model layer, held to the determinism rules in {@code 02_CONTRACT.md}.
 *
 * <p>Those rules exist because three modules in this family shipped a parallel
 * bug that their own tests could not see, each having asserted only an aggregate
 * — a count, a mean, a sorted percentile. All three are order-free by
 * construction, so a merge that assigned results by completion order passed them
 * every time. Everything here asserts per-permutation values, and the comparison
 * itself carries a negative control proving it can fail.
 */
public class NullModelRunnerTest {

    private static final DirectionKey A_TO_B = new DirectionKey(0, "A", 1, "B");

    // ---------- the domain is not optional ----------

    @Test
    public void aRunWithNoRegionIsRefusedRatherThanDefaulted() {
        EngineInputs noDomain = NullFixtures.inputs(
                NullFixtures.blocks("A", NullFixtures.fourApart()),
                NullFixtures.blocks("B", NullFixtures.fourApart()),
                new ArrayList<Roi>());
        try {
            runner(10, 1).run(engines(), noDomain, EngineProgress.SILENT);
            fail("a null model with no domain must be refused");
        } catch (IllegalArgumentException expected) {
            // Scattering into the whole frame instead puts objects where there is
            // no tissue. They collide less by chance, so every observation looks
            // more significant than it is — a silent inflation, not a crash.
            assertTrue(expected.getMessage().contains("region"));
        }
    }

    // ---------- the seed means something specific ----------

    @Test
    public void permutationIIsTheDrawFromSeedPlusI() {
        // Rule 2 of the determinism contract: pin what the seed means, by
        // replaying it inside the test. Without this the seed is decorative — it
        // would be recorded, reported, and reproduce nothing.
        EngineInputs inputs = circleInputs();
        NullModelResult result = only(runner(8, 1)
                .run(engines(), inputs, EngineProgress.SILENT));

        boolean[] domain = ObjectPermuter.domainMask(inputs.domain(),
                NullFixtures.SIZE, NullFixtures.SIZE, 1);
        List<ObjectPermuter> permuters = Arrays.asList(
                ObjectPermuter.of(inputs.labelImages().get(0), domain),
                ObjectPermuter.of(inputs.labelImages().get(1), domain));

        double[] permuted = result.permutedValues();
        for (int i = 0; i < permuted.length; i++) {
            Random replay = new Random(NullModelRunner.DEFAULT_SEED + i);
            List<ImagePlus> labels = new ArrayList<ImagePlus>();
            for (int c = 0; c < permuters.size(); c++) {
                labels.add(permuters.get(c).permute(replay));
            }
            EngineInputs replayed = EngineInputs.builder(labels)
                    .channelNames(inputs.channelNames())
                    .domain(inputs.domain())
                    .build();
            EngineResult expected = engines().get(0)
                    .compute(replayed, EngineProgress.SILENT);
            assertEquals("permutation " + i + " is not the draw from seed+" + i,
                    expected.coincidentCount(A_TO_B), permuted[i], 0.0);
        }
    }

    @Test
    public void changingOnlyTheSeedChangesTheDistribution() {
        // The partner to the test above, and the reason it is evidence. A runner
        // that ignored its seed entirely would satisfy the replay test perfectly,
        // because the replay would use the same hard-coded stream the runner did.
        // Only this can tell the two apart.
        EngineInputs inputs = circleInputs();
        double[] first = only(runner(40, 1).seedOf(11L)
                .run(engines(), inputs, EngineProgress.SILENT)).permutedValues();
        double[] second = only(runner(40, 1).seedOf(12L)
                .run(engines(), inputs, EngineProgress.SILENT)).permutedValues();

        assertFalse("two seeds produced the identical permuted distribution",
                Arrays.equals(first, second));
    }

    // ---------- workers are a performance setting, not part of the answer ----------

    @Test
    public void serialTwoAndMaxWorkersAgreeBitForBit() {
        // Rule 1 and rule 5: per-permutation values, and no tolerance at all.
        // Plugin 04's equivalent mutation was caught only at exactly 0.0; at
        // 1e-12 it passed every single time.
        EngineInputs inputs = circleInputs();
        double[] serial = only(runner(24, 1)
                .run(engines(), inputs, EngineProgress.SILENT)).permutedValues();
        double[] two = only(runner(24, 2)
                .run(engines(), inputs, EngineProgress.SILENT)).permutedValues();
        double[] many = only(runner(24, 8)
                .run(engines(), inputs, EngineProgress.SILENT)).permutedValues();

        assertBitIdentical("serial vs 2 workers", serial, two);
        assertBitIdentical("serial vs 8 workers", serial, many);
    }

    @Test
    public void theBitComparisonRejectsADistributionRotatedByOne() {
        // Rule 3: the negative control. Rotation leaves the count, the mean, the
        // sum and the sorted order untouched and puts every value on the wrong
        // permutation — precisely what a completion-order merge does. A
        // comparison that cannot fail is not evidence.
        double[] original = {1.0, 2.0, 3.0, 4.0};
        double[] rotated = {4.0, 1.0, 2.0, 3.0};
        assertEquals("rotation must not change the mean",
                mean(original), mean(rotated), 0.0);
        try {
            assertBitIdentical("rotated", original, rotated);
            fail("the bit comparison accepted a rotated distribution");
        } catch (AssertionError expected) {
            assertNotNull(expected);
        }
    }

    @Test
    public void repeatedRunsAreBitIdentical() {
        // Rule 4. Completion-order scrambling breaks reproducibility rather than
        // correctness, so one serial-vs-parallel comparison can miss it; running
        // the same configuration repeatedly is what surfaces it.
        EngineInputs inputs = circleInputs();
        double[] reference = only(runner(16, 4)
                .run(engines(), inputs, EngineProgress.SILENT)).permutedValues();
        for (int run = 0; run < 5; run++) {
            double[] again = only(runner(16, 4)
                    .run(engines(), inputs, EngineProgress.SILENT)).permutedValues();
            assertBitIdentical("run " + run, reference, again);
        }
    }

    // ---------- the statistics ----------

    @Test
    public void colocalizedObjectsBeatChanceAndScatteredOnesDoNot() {
        // Both controls, because only one of them is not evidence. The positive
        // case has all four B objects sitting exactly on the A objects; the
        // negative has them in genuinely unrelated places.
        ImagePlus a = NullFixtures.blocks("A", NullFixtures.fourApart());
        ImagePlus coincident = NullFixtures.blocks("B", NullFixtures.fourApart());
        ImagePlus elsewhere = NullFixtures.blocks("B", new int[][] {
                {13, 17}, {31, 12}, {9, 33}, {19, 4}});

        NullModelResult together = only(runner(199, 4).run(engines(),
                NullFixtures.withCircle(a, coincident), EngineProgress.SILENT));
        NullModelResult apart = only(runner(199, 4).run(engines(),
                NullFixtures.withCircle(a, elsewhere), EngineProgress.SILENT));

        assertEquals("all four A objects touch a B object", 4.0, together.observed(), 0.0);
        assertTrue("perfect colocalization must clear chance, got p=" + together.p(),
                together.p() <= 0.05);
        assertTrue("scattered objects must not clear chance, got p=" + apart.p(),
                apart.p() > 0.05);
    }

    @Test
    public void pIsTheExactPermutationFormulaAndNeverZero() {
        double[] permuted = {0.0, 0.0, 0.0, 0.0};
        NullModelResult beaten = NullModelResult.of("e", A_TO_B, 4.0, permuted, 1L);

        // k = 0 of 4, so (0+1)/(4+1). Not 0/4 — a p of exactly zero would claim
        // infinite evidence from four shuffles.
        assertEquals(0.2, beaten.p(), 1e-12);
        assertEquals("the floor must be reported beside it",
                0.2, beaten.minimumP(), 1e-12);
        assertEquals(0.0, beaten.expected(), 0.0);
        assertTrue("enrichment over a zero expectation is not a number",
                Double.isNaN(beaten.enrichment()));
    }

    @Test
    public void aPermutationThatTiesTheObservationCountsAgainstIt() {
        // The statistic is a count, so exact ties with the observation are
        // common rather than exotic — and the direction of the tie-break decides
        // whether the test is conservative or anti-conservative. Counting only
        // strictly-greater permutations shrinks p and over-calls significance,
        // which is the failure mode this whole layer exists to prevent.
        //
        // Added after a mutation check: swapping >= for > left every other test
        // in this class green, because none of their fixtures contained a tie.
        double[] permuted = {2.0, 3.0, 3.0, 4.0};
        NullModelResult result = NullModelResult.of("e", A_TO_B, 3.0, permuted, 1L);

        // Three permutations reach or beat 3.0, so (3+1)/(4+1). Counting only
        // strictly-greater would find one, and report 0.4 instead.
        assertEquals(0.8, result.p(), 1e-12);
    }

    @Test
    public void theTwoSidedPIsTwiceTheSmallerTail() {
        // The doubling is the price of not having decided the direction in
        // advance. Dropping it halves every p in the table and doubles the
        // false-positive rate — silently, since the numbers still look like
        // perfectly ordinary p-values.
        //
        // Added after a mutation check: removing the factor of 2 left every
        // verdict test green, because their fixtures are extreme enough that
        // halving p does not move anything across alpha.
        double[] permuted = {1.0, 2.0, 3.0, 4.0};
        NullModelResult result = NullModelResult.of("e", A_TO_B, 4.0, permuted, 1L);

        // Upper tail: one permutation reaches 4, so (1+1)/(4+1) = 0.4.
        assertEquals(0.4, result.p(), 1e-12);
        // Lower tail: all four are at most 4, so (4+1)/(4+1) = 1.0.
        assertEquals(1.0, result.pDepletion(), 1e-12);
        assertEquals("two-sided is 2 x the smaller tail",
                0.8, result.pTwoSided(), 1e-12);
    }

    @Test
    public void theTwoSidedPIsCappedAtOne() {
        // Doubling can exceed 1 near the middle of the distribution, and a
        // probability cannot.
        double[] permuted = {1.0, 2.0, 3.0, 4.0};
        NullModelResult middling = NullModelResult.of("e", A_TO_B, 2.5, permuted, 1L);
        assertTrue("2 x " + Math.min(middling.p(), middling.pDepletion())
                        + " would exceed 1",
                2.0 * Math.min(middling.p(), middling.pDepletion()) > 1.0);
        assertEquals(1.0, middling.pTwoSided(), 1e-12);
    }

    @Test
    public void depletionIsVisibleToTheLowerTailAndInvisibleToTheUpper() {
        // Two structures that avoid each other colocalize less than randomly
        // placed ones would. A one-sided upper-tail test reports a large p and
        // the effect reads as "nothing here".
        double[] permuted = new double[99];
        Arrays.fill(permuted, 20.0);
        NullModelResult avoided = NullModelResult.of("e", A_TO_B, 1.0, permuted, 1L);

        assertEquals("the upper tail sees nothing", 1.0, avoided.p(), 1e-12);
        assertEquals("the lower tail sees it", 0.01, avoided.pDepletion(), 1e-12);
        assertEquals(0.02, avoided.pTwoSided(), 1e-12);
    }

    @Test
    public void enrichmentAndExpectedAreTheHandComputedValues() {
        double[] permuted = {1.0, 2.0, 3.0, 4.0};
        NullModelResult result = NullModelResult.of("e", A_TO_B, 5.0, permuted, 1L);

        assertEquals(2.5, result.expected(), 1e-12);
        assertEquals(2.0, result.enrichment(), 1e-12);
        // No permutation reached 5, so k = 0.
        assertEquals(1.0 / 5.0, result.p(), 1e-12);
    }

    @Test
    public void eachDirectionCarriesItsOwnStatisticAndNotTheFirstOnes() {
        // A→B and B→A are different questions and here they have different
        // answers: one B block straddles two adjacent A blocks, so two of the
        // four A objects have a partner while the single B object has one.
        //
        // Added after a mutation check: making every direction report the first
        // direction's count left the whole class green, because the fixture used
        // everywhere else is symmetric enough that both directions agree. That
        // is the same shape of bug as the containment engine's undetectable
        // direction flip in stage 4.
        ImagePlus a = NullFixtures.blocks("A", new int[][] {
                {6, 6}, {8, 6}, {6, 26}, {26, 26}});
        ImagePlus b = NullFixtures.blocks("B", new int[][] {{7, 6}});

        List<NullModelResult> results = runner(12, 1)
                .run(engines(), NullFixtures.withCircle(a, b), EngineProgress.SILENT);

        NullModelResult aToB = only(results);
        NullModelResult bToA = null;
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).direction().equals(new DirectionKey(1, "B", 0, "A"))) {
                bToA = results.get(i);
            }
        }
        assertNotNull("B->A must be reported too", bToA);

        assertEquals("two of four A objects touch the B block", 2.0, aToB.observed(), 0.0);
        assertEquals("the single B object touches an A block", 1.0, bToA.observed(), 0.0);
    }

    @Test
    public void aResultCarriesEveryPermutationNotJustItsSummary() {
        double[] permuted = {3.0, 1.0, 2.0};
        NullModelResult result = NullModelResult.of("e", A_TO_B, 9.0, permuted, 1L);
        assertEquals(3, result.permutations());
        // Order preserved, so a caller can prove element i came from draw i.
        assertEquals(3.0, result.permutedValues()[0], 0.0);
        assertEquals(1.0, result.permutedValues()[1], 0.0);
        assertEquals(2.0, result.permutedValues()[2], 0.0);
    }

    @Test
    public void permutedValuesAreCopiedNotShared() {
        double[] permuted = {1.0, 2.0};
        NullModelResult result = NullModelResult.of("e", A_TO_B, 1.0, permuted, 1L);
        permuted[0] = 99.0;
        result.permutedValues()[1] = 99.0;
        assertEquals(1.0, result.permutedValues()[0], 0.0);
        assertEquals(2.0, result.permutedValues()[1], 0.0);
    }

    // ---------- engines that must not be permuted ----------

    @Test
    public void aCurveEngineIsSkippedWithItsReasonRatherThanPermuted() {
        // Permuting a Monte Carlo engine would run a simulation inside a
        // simulation: at 99 simulations and 100 permutations, ten thousand
        // pattern evaluations for a p the engine already reported.
        EngineInputs inputs = circleInputs();
        ColocEngine crossG = EngineRegistry.createDefault().byId("cross-g");
        List<NullModelResult> results = runner(4, 1)
                .run(Arrays.asList(crossG), inputs, EngineProgress.SILENT);

        assertFalse(results.isEmpty());
        for (int i = 0; i < results.size(); i++) {
            assertFalse("a curve engine must not be permuted", results.get(i).ran());
            assertEquals(NullModelResult.Skip.CARRIES_ITS_OWN_ENVELOPE,
                    results.get(i).skip());
        }
    }

    @Test
    public void skippedAndRanAreDistinguishable() {
        // A run that did not randomize and a run that randomized and found
        // nothing look identical in every summary column. A reader who cannot
        // tell them apart will read the first as the second.
        NullModelResult skipped = NullModelResult.skipped("e", A_TO_B, 4.0,
                NullModelResult.Skip.NO_ROOM_IN_DOMAIN);
        assertFalse(skipped.ran());
        assertEquals(0, skipped.permutations());
        assertTrue(Double.isNaN(skipped.p()));
        assertTrue(Double.isNaN(skipped.expected()));
        assertTrue(Double.isNaN(skipped.enrichment()));
        // The observation survives, because it is still a real measurement.
        assertEquals(4.0, skipped.observed(), 0.0);
        assertFalse(skipped.skip().label().isEmpty());
        assertFalse(skipped.skip().explanation().isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void aCompletedResultCannotHaveZeroPermutations() {
        NullModelResult.of("e", A_TO_B, 1.0, new double[0], 1L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aSkippedResultNeedsAReason() {
        NullModelResult.skipped("e", A_TO_B, 1.0, NullModelResult.Skip.NONE);
    }

    // ---------- cancellation ----------

    @Test
    public void cancellationWhileRunningThrowsRatherThanReturningAPartialSet() {
        // A half-finished permutation set is indistinguishable from a complete
        // one in every summary column, so it must never be returned at all.
        try {
            runner(200, 4).run(engines(), circleInputs(), new NullFixtures.CancelAfter(2));
            fail("a cancelled null model must not return");
        } catch (EngineCancelledException expected) {
            assertNotNull(expected);
        }
    }

    @Test
    public void cancellationBeforeAnyWorkThrowsImmediately() {
        EngineProgress cancelled = new EngineProgress() {
            @Override
            public void report(String stage, double fraction) {
            }

            @Override
            public boolean isCancelled() {
                return true;
            }
        };
        try {
            runner(50, 4).run(engines(), circleInputs(), cancelled);
            fail("a cancelled null model must not return");
        } catch (EngineCancelledException expected) {
            assertNotNull(expected);
        }
    }

    @Test
    public void aCancelledRunLeavesNoLiveWorkerThreads() throws Exception {
        try {
            runner(400, 4).run(engines(), circleInputs(), new NullFixtures.CancelAfter(1));
            fail("expected cancellation");
        } catch (EngineCancelledException expected) {
            assertNotNull(expected);
        }
        // The pool is shut down in a finally block, so by the time run() has
        // returned its threads must be on their way out.
        long deadline = 5000L;
        long waited = 0L;
        while (namedThreadCount("ocs-null-model") > 0 && waited < deadline) {
            Thread.sleep(50L);
            waited += 50L;
        }
        assertEquals("null-model workers outlived the run",
                0, namedThreadCount("ocs-null-model"));
    }

    /**
     * Stop reaches a permutation that is already running.
     *
     * <p>Found by the GUI checks: on a large field one permutation re-runs every
     * method and takes seconds, and Escape took eight of them to stop a run
     * because the permuted engines were handed a progress that could never be
     * cancelled and the coordinator waited out the permutation in hand. The
     * engine here takes 20 s per permutation unless it is told to stop.
     */
    @Test(timeout = 60000L)
    public void stopReachesAPermutationThatIsAlreadyRunning() throws Exception {
        final java.util.concurrent.CountDownLatch started =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean stop =
                new java.util.concurrent.atomic.AtomicBoolean();
        final AtomicInteger calls = new AtomicInteger();
        final NullFixtures.TouchCountEngine touch = new NullFixtures.TouchCountEngine("touch");
        ColocEngine slow = new ColocEngine() {
            @Override public String id() { return touch.id(); }
            @Override public String displayName() { return touch.displayName(); }
            @Override public ocs.engine.EngineFamily family() { return touch.family(); }
            @Override public java.util.Set<ocs.engine.InputRequirement> requires() {
                return touch.requires();
            }
            @Override public List<ocs.engine.ColumnSpec> columns() { return touch.columns(); }
            @Override public boolean isSymmetric() { return touch.isSymmetric(); }
            @Override public double relativeCost() { return touch.relativeCost(); }

            @Override
            public EngineResult compute(EngineInputs inputs, EngineProgress progress) {
                if (calls.getAndIncrement() > 0) {
                    // a permutation, not the observed pass
                    started.countDown();
                    long end = System.currentTimeMillis() + 20000L;
                    while (System.currentTimeMillis() < end) {
                        if (progress.isCancelled()) {
                            throw new EngineCancelledException(id());
                        }
                        try {
                            Thread.sleep(5L);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new EngineCancelledException(id());
                        }
                    }
                }
                return touch.compute(inputs, progress);
            }
        };
        EngineProgress progress = new EngineProgress() {
            @Override
            public void report(String stage, double fraction) {
            }

            @Override
            public boolean isCancelled() {
                return stop.get();
            }
        };
        final long[] stoppedAt = new long[1];
        Thread presser = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    started.await();
                    Thread.sleep(200L);
                } catch (InterruptedException interrupted) {
                    return;
                }
                stoppedAt[0] = System.currentTimeMillis();
                stop.set(true);
            }
        });
        presser.start();
        try {
            runner(4, 2).run(Arrays.asList(slow), circleInputs(), progress);
            fail("a cancelled null model must not return");
        } catch (EngineCancelledException expected) {
            long took = System.currentTimeMillis() - stoppedAt[0];
            assertTrue("Stop took " + took + " ms to end the run", took < 2000L);
        } finally {
            presser.join(5000L);
        }
    }

    // ---------- completion order, forced rather than hoped for ----------

    /**
     * Permutation 0 finishes last and permutation <i>n</i>−1 finishes first, and
     * the distribution comes back unchanged.
     *
     * <p>The tests above run at 2, 4 and 8 workers and rely on the machine to
     * produce some interleaving. That is evidence, but it is evidence the
     * machine chooses: on a lightly loaded box the tasks can finish very nearly
     * in order, and a merge keyed on completion order would then agree with an
     * indexed one by luck. Here the order is inverted deliberately, so the two
     * merges cannot agree.
     *
     * <p>The last assertion is the one that keeps this test honest. Without it a
     * reversing executor that quietly stopped reversing — a delay rounded to
     * zero, a pool too small to overlap — would leave the test passing while
     * measuring nothing.
     */
    @Test(timeout = 120000L)
    public void reversedCompletionOrderGivesTheIdenticalDistribution()
            throws InterruptedException {
        EngineInputs inputs = circleInputs();
        int permutations = 12;

        double[] inOrder = only(runner(permutations, 1)
                .run(engines(), inputs, EngineProgress.SILENT)).permutedValues();

        ExecutorService backing = Executors.newFixedThreadPool(permutations);
        ReversingExecutor reversing = new ReversingExecutor(backing, permutations);
        try {
            double[] reversed = only(NullModelRunner.builder()
                    .permutations(permutations)
                    .seed(NullModelRunner.DEFAULT_SEED)
                    .kind(NullModelKind.PER_OBJECT)
                    .executor(reversing)
                    .build()
                    .run(engines(), inputs, EngineProgress.SILENT)).permutedValues();
            assertBitIdentical("reversed completion order", inOrder, reversed);
        } finally {
            backing.shutdown();
            backing.awaitTermination(30L, TimeUnit.SECONDS);
        }

        assertTrue("the fixture must genuinely have reversed the completion order:"
                        + " permutation 0 finished at rank " + reversing.rankOfFirst
                        + ", the last permutation at rank " + reversing.rankOfLast,
                reversing.rankOfFirst > reversing.rankOfLast);
    }

    @Test
    public void anInjectedExecutorIsNotShutDownByTheRun() {
        // A caller running a folder of images owns one pool for the whole batch.
        // A run that shuts it down takes the rest of the batch with it, and the
        // caller has no way to see which run did it.
        ExecutorService caller = Executors.newFixedThreadPool(2);
        try {
            NullModelRunner.builder()
                    .permutations(6)
                    .kind(NullModelKind.PER_OBJECT)
                    .executor(caller)
                    .build()
                    .run(engines(), circleInputs(), EngineProgress.SILENT);
            assertFalse("a caller-owned pool must survive the run", caller.isShutdown());
        } finally {
            caller.shutdownNow();
        }
    }

    /**
     * Cancelling drains the queue rather than merely stopping the wait on it.
     *
     * <p>The executor here never runs anything, so whatever is left in it after
     * the run is exactly the queued work — and all of it must come back
     * cancelled. On a pool the runner owns, {@code shutdownNow} empties the queue
     * itself and would hide a missing drain entirely.
     *
     * <p>What this protects is a user's afternoon. Every permutation re-runs
     * every enabled method, so a queue left to finish after Stop can run for
     * minutes producing a result nobody will look at.
     *
     * <p>The timeout is here for the same reason as the one on
     * {@code PerObjectIntensityEngineTest.theFirstFailureSurfacesAndDrainsTheRest}:
     * this executor runs nothing, so a coordinator that stopped checking for
     * cancellation would block on a future that never completes rather than
     * return a wrong answer. A hang reads as broken infrastructure; a failed
     * test reads as the defect it is.
     */
    @Test(timeout = 60000L)
    public void cancellationDrainsTheQueuedPermutationsRatherThanLettingThemRun() {
        NeverRunsExecutor queue = new NeverRunsExecutor();
        // Not cancelled from the outset: the observed pass runs before anything
        // is queued and would throw first, leaving an empty queue and a test
        // that proved nothing. One clear query lets the observed pass through,
        // and the run is cancelled by the time the coordinator starts
        // collecting — which is where the drain lives.
        final AtomicInteger queries = new AtomicInteger();
        EngineProgress cancelled = new EngineProgress() {
            @Override
            public void report(String stage, double fraction) {
            }

            @Override
            public boolean isCancelled() {
                return queries.getAndIncrement() >= 1;
            }
        };

        try {
            NullModelRunner.builder()
                    .permutations(20)
                    .kind(NullModelKind.PER_OBJECT)
                    .executor(queue)
                    .build()
                    .run(engines(), circleInputs(), cancelled);
            fail("a cancelled null model must not return");
        } catch (EngineCancelledException expected) {
            assertNotNull(expected);
        }

        assertTrue("nothing was ever queued, so this proves nothing",
                queue.queued.size() > 0);
        for (int i = 0; i < queue.queued.size(); i++) {
            assertTrue("queued permutation " + i + " was left to run after Stop",
                    queue.queued.get(i).isCancelled());
        }
    }

    // ---------- helpers ----------

    /** Accepts work, records the futures, and runs none of it. */
    private static final class NeverRunsExecutor extends AbstractExecutorService {

        final List<FutureTask<?>> queued = new ArrayList<FutureTask<?>>();

        @Override
        public void execute(Runnable command) {
            queued.add((FutureTask<?>) command);
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return new ArrayList<Runnable>();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    /** Runs every task only once all are queued, last submitted first. */
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

    private static Runner runner(int permutations, int workers) {
        return new Runner(permutations, workers, NullModelRunner.DEFAULT_SEED);
    }

    /** Small wrapper so a test can say {@code .seedOf(11L)} inline. */
    private static final class Runner {
        private final int permutations;
        private final int workers;
        private final long seed;

        private Runner(int permutations, int workers, long seed) {
            this.permutations = permutations;
            this.workers = workers;
            this.seed = seed;
        }

        private Runner seedOf(long newSeed) {
            return new Runner(permutations, workers, newSeed);
        }

        private List<NullModelResult> run(List<ColocEngine> engines,
                EngineInputs inputs, EngineProgress progress) {
            return NullModelRunner.builder()
                    .permutations(permutations)
                    .workers(workers)
                    .seed(seed)
                    .kind(NullModelKind.PER_OBJECT)
                    .build()
                    .run(engines, inputs, progress);
        }
    }

    private static List<ColocEngine> engines() {
        return Arrays.<ColocEngine>asList(new NullFixtures.TouchCountEngine("touch"));
    }

    private static EngineInputs circleInputs() {
        return NullFixtures.withCircle(
                NullFixtures.blocks("A", NullFixtures.fourApart()),
                NullFixtures.blocks("B", NullFixtures.fourApart()));
    }

    /** The A→B result, which is the one every assertion here is about. */
    private static NullModelResult only(List<NullModelResult> results) {
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).direction().equals(A_TO_B)) {
                return results.get(i);
            }
        }
        throw new AssertionError("no A->B result in " + results);
    }

    private static void assertBitIdentical(String what, double[] expected, double[] actual) {
        assertEquals(what + ": permutation count", expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(what + ": permutation " + i,
                    Double.doubleToRawLongBits(expected[i]),
                    Double.doubleToRawLongBits(actual[i]));
        }
    }

    private static double mean(double[] values) {
        double total = 0.0;
        for (int i = 0; i < values.length; i++) {
            total += values[i];
        }
        return total / values.length;
    }

    private static int namedThreadCount(String name) {
        Thread[] threads = new Thread[Thread.activeCount() * 2 + 32];
        int found = Thread.enumerate(threads);
        int count = 0;
        for (int i = 0; i < found; i++) {
            if (threads[i] != null && name.equals(threads[i].getName())
                    && threads[i].isAlive()) {
                count++;
            }
        }
        return count;
    }
}
