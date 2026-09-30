package ocs.engine.intensity;

import ij.ImagePlus;
import ij.process.ShortProcessor;
import ocs.engine.ColumnSpec;
import ocs.engine.DirectionKey;
import ocs.engine.EngineCancelledException;
import ocs.engine.EngineFamily;
import ocs.engine.EngineInputs;
import ocs.engine.EngineProgress;
import ocs.engine.EngineRegistry;
import ocs.engine.EngineResult;
import ocs.engine.InputRequirement;
import ocs.engine.ObjectScore;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Per-object masked Pearson and Manders.
 *
 * <p>The main fixture has an answer that can be worked out on paper. Object 1's
 * two channels are an exactly increasing linear function of one another, so its
 * <i>r</i> is +1; object 2's are exactly decreasing, so its <i>r</i> is −1; object
 * 3's source channel is invariant, so its <i>r</i> does not exist. Objects 1 and 2
 * are interleaved column by column and share the identical bounding box, which
 * makes the two most plausible masking bugs — reading the box, or reading the
 * neighbouring object — impossible to commit without turning both ±1 into
 * something else.
 *
 * <p>Every assertion names an object. The mean of the five objects' correlations
 * is unchanged by attributing each value to the wrong object, so a test on the
 * mean would pass on a table where every row is wrong.
 */
public class PerObjectIntensityEngineTest {

    private static final String WORKER_PREFIX = "ocs-per-object-intensity-";
    private static final int SIDE = 8;

    // ------------------------------------------------------------------
    // Declarations
    // ------------------------------------------------------------------

    @Test
    public void theRegistryAcceptsTheEngine() {
        PerObjectIntensityEngine engine = new PerObjectIntensityEngine();
        EngineRegistry registry = EngineRegistry.empty().register(engine);

        assertEquals(1, registry.size());
        assertTrue(registry.has("per-object-intensity"));
        assertEquals(engine, registry.byId("per-object-intensity"));
        assertEquals(1, registry.family(EngineFamily.INTENSITY).size());
    }

    @Test
    public void theEngineDeclaresWhatTheContractAsksFor() {
        PerObjectIntensityEngine engine = new PerObjectIntensityEngine();

        assertEquals("per-object-intensity", engine.id());
        assertEquals(EngineFamily.INTENSITY, engine.family());
        assertTrue(engine.requires().contains(InputRequirement.LABEL_IMAGES));
        assertTrue(engine.requires().contains(InputRequirement.INTENSITY_IMAGES));

        int primaries = 0;
        for (ColumnSpec column : engine.columns()) {
            if (column.isPrimary()) {
                primaries++;
                assertEquals("Pearson r", column.name());
                assertEquals(ocs.engine.ScaleKind.CORRELATION, column.scale());
            }
        }
        assertEquals(1, primaries);

        List<String> names = new ArrayList<String>();
        for (ColumnSpec column : engine.columns()) {
            names.add(column.name());
        }
        assertTrue(names.toString(), names.contains("Manders M1"));
        assertTrue(names.toString(), names.contains("Manders M2"));
        assertTrue(names.toString(), names.contains("Object Voxels"));
        assertTrue(names.toString(), names.contains("Correlation Defined?"));
        assertFalse("no Costes p is declared, because the block shuffle needs voxels"
                        + " still arranged in space and per-object samples are not",
                names.contains("Costes p"));

        assertTrue("more than the object family's single scan, because of the Costes"
                        + " bisection; far less than whole-image intensity, because there"
                        + " is no randomization. Got " + engine.relativeCost(),
                engine.relativeCost() > 2.5 && engine.relativeCost() < 130.0);
    }

    /**
     * The direction flag, made executable.
     *
     * <p>Pearson's <i>r</i> is symmetric in its arguments, so it is tempting to
     * declare this engine symmetric and let the null-model layer compute one
     * direction. The two directions here carry <b>different objects</b> — five from
     * channel A, two from channel B — so the direction that was skipped could not
     * be recovered from the one that was kept.
     */
    @Test
    public void theTwoDirectionsCarryDifferentObjectsSoTheEngineIsNotSymmetric() {
        PerObjectIntensityEngine engine = new PerObjectIntensityEngine();
        assertFalse(engine.isSymmetric());

        EngineResult result = engine.compute(analyticInputs(), EngineProgress.SILENT);
        assertEquals(2, result.directions().size());

        assertEquals("channel A's five objects", Arrays.asList(1, 2, 3, 4, 5),
                labelsOf(result, forward(result)));
        assertEquals("channel B's two objects", Arrays.asList(7, 8),
                labelsOf(result, reverse(result)));
    }

    // ------------------------------------------------------------------
    // The known answer
    // ------------------------------------------------------------------

    @Test
    public void eachObjectGetsTheCorrelationItsOwnVoxelsImply() {
        EngineResult result = new PerObjectIntensityEngine()
                .compute(analyticInputs(), EngineProgress.SILENT);
        DirectionKey forward = forward(result);
        List<ObjectScore> scores = result.scores(forward);
        double[] voxels = result.supporting(forward, "Object Voxels");
        double[] defined = result.supporting(forward, "Correlation Defined?");

        // Object 1: channel B is 2A + 6 inside its mask, so r is exactly +1.
        assertEquals(1, scores.get(0).sourceLabel());
        assertEquals(1.0, scores.get(0).value(), 1.0e-12);
        assertTrue("r = 1 is above the 0.5 default", scores.get(0).isCoincident());
        assertEquals(16.0, voxels[0], 0.0);
        assertEquals(1.0, defined[0], 0.0);

        // Object 2: channel B is 900 − 2A inside its mask, so r is exactly −1.
        assertEquals(2, scores.get(1).sourceLabel());
        assertEquals(-1.0, scores.get(1).value(), 1.0e-12);
        assertFalse(scores.get(1).isCoincident());
        assertEquals(16.0, voxels[1], 0.0);
        assertEquals(1.0, defined[1], 0.0);

        // Object 3: the source channel is invariant inside it. "How strongly do
        // these covary" then has no answer, which is not the answer zero.
        assertEquals(3, scores.get(2).sourceLabel());
        assertTrue("a channel with no variation has no correlation, not r = 0",
                Double.isNaN(scores.get(2).value()));
        assertEquals(0.0, defined[2], 0.0);
        assertFalse(scores.get(2).isCoincident());
        assertEquals(8.0, voxels[2], 0.0);

        // Every object is still a row. Dropping the undefined ones would misalign
        // this engine's objects against every other engine's in the agreement
        // matrix, which compares object by object.
        assertEquals(5, scores.size());
        assertEquals(5, voxels.length);
    }

    /**
     * The masking negative control: the same objects measured over their bounding
     * boxes give visibly different numbers, so the ±1 above cannot have come from
     * a box.
     */
    @Test
    public void measuringTheBoundingBoxInsteadOfTheMaskWouldChangeEveryNumber() {
        int[] labels = labelsA();
        int[][] intensity = intensities();

        // Objects 1 and 2 interleave column by column across rows 0–3, so they
        // share one bounding box: the whole of those four rows.
        double[] boxA = new double[4 * SIDE];
        double[] boxB = new double[4 * SIDE];
        for (int i = 0; i < 4 * SIDE; i++) {
            boxA[i] = intensity[0][i];
            boxB[i] = intensity[1][i];
        }
        double boxPearson = twoPassPearson(boxA, boxB);

        EngineResult result = new PerObjectIntensityEngine()
                .compute(analyticInputs(), EngineProgress.SILENT);
        List<ObjectScore> scores = result.scores(forward(result));

        assertTrue("the shared bounding box must not itself correlate at ±1, or this"
                        + " control proves nothing; got " + boxPearson,
                Math.abs(boxPearson) < 0.9);
        assertTrue("object 1 reported " + scores.get(0).value() + ", its box gives "
                        + boxPearson,
                Math.abs(scores.get(0).value() - boxPearson) > 0.1);
        assertTrue("object 2 reported " + scores.get(1).value() + ", its box gives "
                        + boxPearson,
                Math.abs(scores.get(1).value() - boxPearson) > 0.1);

        // And the mask is not the neighbouring object either: label 1 and label 2
        // land on opposite ends of the scale.
        assertEquals(2.0, scores.get(0).value() - scores.get(1).value(), 1.0e-12);
        assertEquals("label 1 is the first row", 1, scores.get(0).sourceLabel());
    }

    /**
     * Manders is directional, so it is where a swapped channel pair shows up —
     * Pearson would not move at all, being symmetric in its two arguments.
     */
    @Test
    public void mandersIsMeasuredOverTheObjectsVoxelsAndKeepsItsTwoChannelsStraight() {
        EngineResult result = new PerObjectIntensityEngine()
                .compute(analyticInputs(), EngineProgress.SILENT);
        DirectionKey forward = forward(result);

        double reportedTa = result.supporting(forward, "Costes Ta")[0];
        double reportedTb = result.supporting(forward, "Costes Tb")[0];
        double reportedM1 = result.supporting(forward, "Manders M1")[0];
        double reportedM2 = result.supporting(forward, "Manders M2")[0];

        double[] objectA = samplesOf(labelsA(), intensities()[0], 1);
        double[] objectB = samplesOf(labelsA(), intensities()[1], 1);

        assertEquals("M1 is the fraction of the source channel's signal inside this"
                        + " object that sits above the target's threshold",
                mandersFraction(objectA, objectB, reportedTb), reportedM1, 1.0e-12);
        assertEquals("M2 is the same statement with the channels the other way round",
                mandersFraction(objectB, objectA, reportedTa), reportedM2, 1.0e-12);
        assertNotEquals("the fixture must separate M1 from M2, or a swap would be"
                + " invisible here", reportedM1, reportedM2, 1.0e-6);
        assertEquals("Manders is valid: neither channel goes negative", 1.0,
                result.supporting(forward, "Manders Valid?")[0], 0.0);

        // The threshold search succeeds on the perfectly correlated object and
        // refuses on the anticorrelated one, where no threshold separates signal
        // from correlated background. Both are reported as such rather than one
        // being passed off as the other.
        assertEquals(1.0, result.supporting(forward, "Costes Threshold Fitted?")[0], 0.0);
        assertEquals(0.0, result.supporting(forward, "Costes Threshold Fitted?")[1], 0.0);
    }

    /**
     * The second threshold and the thresholded correlation, pinned to values the
     * fixture fixes.
     *
     * <p>Added because a mutation that quietly filled {@code Pearson r Thresholded}
     * with the wrong quantity survived every other test here — nothing asserted
     * what was in it. A column no test reads is a column any refactor can
     * transpose, which is the failure the named-column contract exists to prevent
     * and does not prevent on its own.
     */
    @Test
    public void theSecondThresholdAndTheThresholdedCorrelationAreTheObjectsOwn() {
        EngineResult result = new PerObjectIntensityEngine()
                .compute(analyticInputs(), EngineProgress.SILENT);
        DirectionKey forward = forward(result);

        double ta = result.supporting(forward, "Costes Ta")[0];
        double tb = result.supporting(forward, "Costes Tb")[0];
        double thresholded = result.supporting(forward, "Pearson r Thresholded")[0];

        double[] objectA = samplesOf(labelsA(), intensities()[0], 1);
        double[] objectB = samplesOf(labelsA(), intensities()[1], 1);

        // Inside object 1 the target channel is exactly 2A + 6, so that is the
        // regression line the Costes search walks down and the second threshold
        // is fixed by the first.
        assertEquals("Tb is Ta carried across object 1's own regression line",
                2.0 * ta + 6.0, tb, 1.0e-9);

        int above = 0;
        for (int i = 0; i < objectA.length; i++) {
            if (objectA[i] > ta && objectB[i] > tb) {
                above++;
            }
        }
        assertTrue("the thresholds must exclude something, or the thresholded number"
                + " is the unthresholded one under a different name; " + above
                + " of " + objectA.length + " voxels survived",
                above > 2 && above < objectA.length);

        double[] survivingA = new double[above];
        double[] survivingB = new double[above];
        int at = 0;
        for (int i = 0; i < objectA.length; i++) {
            if (objectA[i] > ta && objectB[i] > tb) {
                survivingA[at] = objectA[i];
                survivingB[at] = objectB[i];
                at++;
            }
        }
        assertEquals("what survives the thresholds still lies on the same line",
                twoPassPearson(survivingA, survivingB), thresholded, 1.0e-12);
        assertEquals(1.0, thresholded, 1.0e-12);
    }

    /**
     * Two points lie on a line by construction. The fixture's two-voxel object
     * <i>would</i> report exactly −1, which is why it must not report anything.
     */
    @Test
    public void objectsTooSmallToCorrelateAreReportedAsUndefinedRatherThanConfident() {
        EngineResult result = new PerObjectIntensityEngine()
                .compute(analyticInputs(), EngineProgress.SILENT);
        DirectionKey forward = forward(result);
        List<ObjectScore> scores = result.scores(forward);
        double[] voxels = result.supporting(forward, "Object Voxels");
        double[] defined = result.supporting(forward, "Correlation Defined?");

        assertEquals(4, scores.get(3).sourceLabel());
        assertEquals(1.0, voxels[3], 0.0);
        assertTrue("a single voxel has no correlation", Double.isNaN(scores.get(3).value()));

        assertEquals(5, scores.get(4).sourceLabel());
        assertEquals(2.0, voxels[4], 0.0);
        assertTrue("two voxels have no informative correlation",
                Double.isNaN(scores.get(4).value()));

        // The negative control for the minimum: run the same two samples through
        // an independent Pearson and watch it produce a confident −1.
        double[] twoVoxelA = samplesOf(labelsA(), intensities()[0], 5);
        double[] twoVoxelB = samplesOf(labelsA(), intensities()[1], 5);
        assertEquals("a two-point Pearson is ±1 with certainty and carries no"
                        + " information, which is exactly why it is withheld",
                -1.0, twoPassPearson(twoVoxelA, twoVoxelB), 1.0e-12);

        for (int i = 3; i <= 4; i++) {
            assertEquals(0.0, defined[i], 0.0);
            assertFalse(scores.get(i).isCoincident());
            assertTrue(Double.isNaN(result.supporting(forward, "Manders M1")[i]));
            assertTrue(Double.isNaN(result.supporting(forward, "Manders M2")[i]));
            assertTrue(Double.isNaN(result.supporting(forward, "Costes Ta")[i]));
            assertEquals("nothing was measured, so nothing is claimed valid", 0.0,
                    result.supporting(forward, "Manders Valid?")[i], 0.0);
        }
    }

    @Test
    public void theCoincidenceThresholdIsTheCallersToMake() {
        EngineResult strict = new PerObjectIntensityEngine(0.99)
                .compute(analyticInputs(), EngineProgress.SILENT);
        EngineResult loose = new PerObjectIntensityEngine(-1.0)
                .compute(analyticInputs(), EngineProgress.SILENT);

        assertEquals("only the perfectly correlated object clears 0.99",
                1, strict.coincidentCount(forward(strict)));
        assertEquals("at −1.0 both objects with a correlation clear it, and the three"
                        + " without one still do not — an undefined value is not a low"
                        + " value and must never be swept into a yes",
                2, loose.coincidentCount(forward(loose)));
    }

    // ------------------------------------------------------------------
    // Parallel verification
    // ------------------------------------------------------------------

    @Test
    public void serialTwoAndMaxWorkersProduceBitIdenticalTables() {
        EngineInputs inputs = manyObjects(48, 4, 8, 21L);
        int max = Math.max(2, Runtime.getRuntime().availableProcessors());

        EngineResult serial = new PerObjectIntensityEngine(0.5, 1)
                .compute(inputs, EngineProgress.SILENT);
        EngineResult two = new PerObjectIntensityEngine(0.5, 2)
                .compute(inputs, EngineProgress.SILENT);
        EngineResult many = new PerObjectIntensityEngine(0.5, max)
                .compute(inputs, EngineProgress.SILENT);

        assertEquals("the fixture must have enough objects for the split to matter",
                144, serial.scores(forward(serial)).size());
        assertBitIdentical("serial versus two workers", serial, two);
        assertBitIdentical("serial versus " + max + " workers", serial, many);
    }

    /**
     * A comparison that cannot fail is not evidence. Rotating the table by one row
     * leaves every column's contents, mean and sum untouched and moves every value
     * to the wrong object — which is exactly what a merge by completion order
     * produces — so the comparison above must reject it.
     */
    @Test
    public void theBitComparisonRejectsATableRotatedByOneRow() {
        EngineInputs inputs = manyObjects(48, 4, 8, 22L);
        EngineResult serial = new PerObjectIntensityEngine(0.5, 1)
                .compute(inputs, EngineProgress.SILENT);

        try {
            assertBitIdentical("rotated", serial, rotatedByOneRow(serial));
            fail("the comparison used by the worker-count tests cannot distinguish a"
                    + " correct table from a rotated one, so those tests prove nothing");
        } catch (AssertionError expected) {
            assertTrue(String.valueOf(expected.getMessage()),
                    String.valueOf(expected.getMessage()).contains("rotated"));
        }
    }

    /**
     * Completion order must never reach the table. Every task is held until
     * all are queued and then run last submitted first, so the objects finish backwards.
     */
    @Test(timeout = 120000L)
    public void reversedCompletionOrderGivesTheIdenticalTable() throws InterruptedException {
        EngineInputs inputs = manyObjects(24, 4, 8, 23L);
        int objects = 36;

        EngineResult inOrder = new PerObjectIntensityEngine(0.5, 1)
                .compute(inputs, EngineProgress.SILENT);
        assertEquals(objects, inOrder.scores(forward(inOrder)).size());

        ExecutorService backing = Executors.newFixedThreadPool(objects);
        ReversingExecutor reversing = new ReversingExecutor(backing, objects);
        try {
            EngineResult reversed = new PerObjectIntensityEngine(0.5, objects, reversing)
                    .compute(inputs, EngineProgress.SILENT);
            assertBitIdentical("reversed completion order", inOrder, reversed);
        } finally {
            backing.shutdown();
            backing.awaitTermination(30L, TimeUnit.SECONDS);
        }

        assertTrue("the fixture must genuinely have reversed the completion order:"
                        + " first submitted finished at rank " + reversing.rankOfFirst
                        + ", last submitted at rank " + reversing.rankOfLast,
                reversing.rankOfFirst > reversing.rankOfLast);
    }

    @Test
    public void anInjectedExecutorIsNotShutDownByTheRun() {
        EngineInputs inputs = manyObjects(24, 4, 8, 24L);
        ExecutorService caller = Executors.newFixedThreadPool(2);
        try {
            new PerObjectIntensityEngine(0.5, 2, caller)
                    .compute(inputs, EngineProgress.SILENT);
            assertFalse("a caller-owned pool must survive the run", caller.isShutdown());
        } finally {
            caller.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // Cancellation
    // ------------------------------------------------------------------

    /**
     * Cancelling must drain the queue, not merely stop waiting on it.
     *
     * <p>The executor here never runs anything, so what is left in it after the
     * run is exactly the queued work — and every piece of it must come back
     * cancelled. On a pool the engine owns, {@code shutdownNow} empties the queue
     * itself and would hide a missing drain.
     */
    @Test
    public void cancellationWhileWorkIsQueuedCancelsTheQueuedWork() {
        EngineInputs inputs = manyObjects(48, 4, 8, 25L);
        QueueingExecutor queue = new QueueingExecutor();
        CancelAfterCoordinatorPolls progress = new CancelAfterCoordinatorPolls(12);

        try {
            new PerObjectIntensityEngine(0.5, 2, queue).compute(inputs, progress);
            fail("a cancelled run must never return a result — a table missing half"
                    + " its objects looks identical to a complete one");
        } catch (EngineCancelledException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("per-object-intensity"));
        }

        assertTrue("submission must stop at the cancellation rather than queueing all"
                        + " 144 objects; queued " + queue.queued.size(),
                queue.queued.size() > 0 && queue.queued.size() < 144);
        for (int i = 0; i < queue.queued.size(); i++) {
            assertTrue("queued object " + i + " was left to run rather than drained",
                    ((Future<?>) queue.queued.get(i)).isCancelled());
        }
        assertFalse("a caller-owned pool must survive a cancelled run too",
                queue.isShutdown());
    }

    @Test
    public void cancellationWhileWorkIsRunningThrowsAndLeavesNoThreads() {
        EngineInputs inputs = manyObjects(48, 4, 8, 26L);
        CancelAfterWorkerPolls progress = new CancelAfterWorkerPolls(3);

        try {
            new PerObjectIntensityEngine(0.5, 2).compute(inputs, progress);
            fail("cancellation from inside a running object must still refuse to"
                    + " return a result");
        } catch (EngineCancelledException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("per-object-intensity"));
        }
        assertTrue("the cancellation must have come from a worker",
                progress.workerPolls.get() >= 3);
        assertNoWorkerThreadsRemain();
    }

    @Test
    public void theSerialOverrideHonoursCancellationToo() {
        EngineInputs inputs = manyObjects(48, 4, 8, 27L);
        CancelAfterCoordinatorPolls progress = new CancelAfterCoordinatorPolls(6);

        try {
            new PerObjectIntensityEngine(0.5, 1).compute(inputs, progress);
            fail("the serial path must check cancellation in its object loop");
        } catch (EngineCancelledException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("per-object-intensity"));
        }
        assertNoWorkerThreadsRemain();
    }

    @Test
    public void aCompletedParallelRunLeavesNoThreadsBehind() {
        EngineInputs inputs = manyObjects(24, 4, 8, 28L);
        new PerObjectIntensityEngine(0.5, 4).compute(inputs, EngineProgress.SILENT);
        assertNoWorkerThreadsRemain();
    }

    /**
     * One object failing must surface as that failure and drain the rest, not
     * disappear into a table that is short a row.
     *
     * <p>Cancellation and failure look the same from inside the merge loop and
     * are handled by the same drain, but they arrive differently: a cancellation
     * is a flag the coordinator polls, a failure is an exception coming back out
     * of a worker. Only the second one can be swallowed, and a swallowed failure
     * is the worst outcome available here — the run finishes, the table looks
     * complete, and one object silently holds whatever the array was initialised
     * with.
     *
     * <p>The executor runs the first few objects inline and queues the rest
     * without running them, so what happens is fixed rather than raced: the
     * failing object has definitely failed and the later ones have definitely
     * not started when the drain fires.
     *
     * <p><b>The timeout is load-bearing, not boilerplate.</b> An engine that
     * loses the failure does not fail this test by returning the wrong answer —
     * it hangs. Having swallowed the exception it carries on to the next
     * direction, submits that direction's objects into an executor whose
     * inline-run budget is already spent, and then blocks forever waiting on a
     * task nothing will ever run. Without a timeout the whole suite stops there,
     * which reads as an infrastructure problem rather than as the defect it is.
     * Found by the stage-14 mutation check, which deleted the
     * {@code failure = unwrap(e)} assignment and hung twice before this was
     * added.
     */
    @Test(timeout = 60000L)
    public void theFirstFailureSurfacesAndDrainsTheRest() {
        EngineInputs inputs = manyObjects(24, 4, 8, 29L);
        int failAt = 3;
        FailingExecutor executor = new FailingExecutor(failAt);

        try {
            new PerObjectIntensityEngine(0.5, 4, executor)
                    .compute(inputs, EngineProgress.SILENT);
            fail("an object that failed to measure must not leave a complete-looking"
                    + " table behind");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("object " + failAt + " failed"));
        }

        assertTrue("nothing was queued behind the failure, so no drain was tested;"
                        + " queued " + executor.queued.size(),
                executor.queued.size() > 0);
        for (int i = 0; i < executor.queued.size(); i++) {
            assertTrue("object queued behind the failure at position " + i
                            + " was left to run",
                    executor.queued.get(i).isCancelled());
        }
        assertFalse("a caller-owned pool must survive a failed run too",
                executor.isShutdown());
    }

    // ------------------------------------------------------------------
    // Comparison helpers
    // ------------------------------------------------------------------

    /**
     * Compares two results value by value on their raw IEEE-754 bit patterns.
     *
     * <p>Not a tolerance. Completion-order scrambling breaks reproducibility rather
     * than correctness — the last bits wander — and a comparison at 1e-12 passes on
     * a merge that is subtly wrong every single time.
     */
    private static void assertBitIdentical(String why, EngineResult expected,
                                           EngineResult actual) {
        assertEquals(why + ": directions", expected.directions(), actual.directions());
        for (DirectionKey key : expected.directions()) {
            List<ObjectScore> expectedScores = expected.scores(key);
            List<ObjectScore> actualScores = actual.scores(key);
            assertEquals(why + ": object count of " + key.label(),
                    expectedScores.size(), actualScores.size());
            for (int i = 0; i < expectedScores.size(); i++) {
                assertEquals(why + ": label at row " + i + " of " + key.label(),
                        expectedScores.get(i).sourceLabel(),
                        actualScores.get(i).sourceLabel());
                assertEquals(why + ": Pearson r at row " + i + " of " + key.label(),
                        Double.doubleToRawLongBits(expectedScores.get(i).value()),
                        Double.doubleToRawLongBits(actualScores.get(i).value()));
                assertEquals(why + ": coincident at row " + i + " of " + key.label(),
                        expectedScores.get(i).isCoincident(),
                        actualScores.get(i).isCoincident());
            }
            List<String> columns = expected.supportingNames(key);
            assertEquals(why + ": columns of " + key.label(),
                    columns, actual.supportingNames(key));
            for (int c = 0; c < columns.size(); c++) {
                double[] expectedColumn = expected.supporting(key, columns.get(c));
                double[] actualColumn = actual.supporting(key, columns.get(c));
                assertEquals(expectedColumn.length, actualColumn.length);
                for (int i = 0; i < expectedColumn.length; i++) {
                    assertEquals(why + ": column '" + columns.get(c) + "' row " + i
                                    + " of " + key.label(),
                            Double.doubleToRawLongBits(expectedColumn[i]),
                            Double.doubleToRawLongBits(actualColumn[i]));
                }
            }
        }
    }

    /** Every value moved to its neighbour's object, every column otherwise intact. */
    private static EngineResult rotatedByOneRow(EngineResult source) {
        EngineResult.Builder builder = EngineResult.forEngine(source.engineId());
        for (DirectionKey key : source.directions()) {
            List<ObjectScore> scores = source.scores(key);
            List<ObjectScore> rotated = new ArrayList<ObjectScore>(scores.size());
            for (int i = 0; i < scores.size(); i++) {
                ObjectScore stolen = scores.get((i + 1) % scores.size());
                rotated.add(new ObjectScore(scores.get(i).sourceLabel(),
                        stolen.partnerLabel(), stolen.value(), stolen.isCoincident()));
            }
            builder.direction(key, rotated);
            for (String column : source.supportingNames(key)) {
                double[] values = source.supporting(key, column);
                double[] shifted = new double[values.length];
                for (int i = 0; i < values.length; i++) {
                    shifted[i] = values[(i + 1) % values.length];
                }
                builder.supporting(key, column, shifted);
            }
        }
        return builder.build();
    }

    private static DirectionKey forward(EngineResult result) {
        return result.directions().get(0);
    }

    private static DirectionKey reverse(EngineResult result) {
        return result.directions().get(1);
    }

    private static List<Integer> labelsOf(EngineResult result, DirectionKey key) {
        List<Integer> labels = new ArrayList<Integer>();
        for (ObjectScore score : result.scores(key)) {
            labels.add(Integer.valueOf(score.sourceLabel()));
        }
        return labels;
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * Channel A's objects.
     *
     * <p>Rows 0–3 alternate between labels 1 and 2 column by column, so the two
     * share one bounding box. Row 4 is label 3, and row 5 holds a one-voxel
     * label 4 and a two-voxel label 5.
     */
    private static int[] labelsA() {
        int[] labels = new int[SIDE * SIDE];
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < SIDE; x++) {
                labels[y * SIDE + x] = x % 2 == 0 ? 1 : 2;
            }
        }
        for (int x = 0; x < SIDE; x++) {
            labels[4 * SIDE + x] = 3;
        }
        labels[5 * SIDE] = 4;
        labels[5 * SIDE + 2] = 5;
        labels[5 * SIDE + 3] = 5;
        return labels;
    }

    /** Channel B's objects: two halves, sharing no boundary with channel A's five. */
    private static int[] labelsB() {
        int[] labels = new int[SIDE * SIDE];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = i < 4 * SIDE ? 7 : 8;
        }
        return labels;
    }

    /**
     * Intensities with a per-object rule, so each object's answer is arithmetic.
     *
     * <p>Object 1: B = 2A + 6, exactly increasing, r = +1. Object 2: B = 900 − 2A,
     * exactly decreasing, r = −1. Object 3: A invariant, r undefined. Object 4: one
     * voxel. Object 5: two voxels that would give a confident −1.
     */
    private static int[][] intensities() {
        int[] labels = labelsA();
        int[] a = new int[SIDE * SIDE];
        int[] b = new int[SIDE * SIDE];
        int[] seen = new int[6];
        for (int i = 0; i < labels.length; i++) {
            int label = labels[i];
            if (label == 0) {
                a[i] = 50;
                b[i] = 60;
                continue;
            }
            int k = seen[label]++;
            if (label == 1) {
                a[i] = 100 + 10 * k;
                b[i] = 2 * a[i] + 6;
            } else if (label == 2) {
                a[i] = 100 + 10 * k;
                b[i] = 900 - 2 * a[i];
            } else if (label == 3) {
                a[i] = 500;
                b[i] = 100 + 40 * k;
            } else if (label == 4) {
                a[i] = 300;
                b[i] = 400;
            } else {
                a[i] = 100 + 100 * k;
                b[i] = 900 - 800 * k;
            }
        }
        return new int[][] {a, b};
    }

    private static EngineInputs analyticInputs() {
        int[][] intensity = intensities();
        return EngineInputs.builder(Arrays.asList(
                        image("labelsA", SIDE, SIDE, labelsA()),
                        image("labelsB", SIDE, SIDE, labelsB())))
                .intensityImages(Arrays.asList(
                        image("iA", SIDE, SIDE, intensity[0]),
                        image("iB", SIDE, SIDE, intensity[1])))
                .channelNames(Arrays.asList("A", "B"))
                .build();
    }

    /**
     * A tiled fixture with enough objects for the parallel split to be worth
     * testing: {@code (side / tileA)²} objects in channel A and fewer, larger ones
     * in channel B.
     */
    private static EngineInputs manyObjects(int side, int tileA, int tileB, long seed) {
        Random random = new Random(seed);
        int[] a = new int[side * side];
        int[] b = new int[side * side];
        for (int i = 0; i < a.length; i++) {
            double shared = random.nextDouble();
            a[i] = (int) Math.round(200.0 + 600.0 * shared + 120.0 * random.nextDouble());
            b[i] = (int) Math.round(150.0 + 700.0 * shared + 140.0 * random.nextDouble());
        }
        return EngineInputs.builder(Arrays.asList(
                        image("labelsA", side, side, tiles(side, tileA)),
                        image("labelsB", side, side, tiles(side, tileB))))
                .intensityImages(Arrays.asList(
                        image("iA", side, side, a), image("iB", side, side, b)))
                .channelNames(Arrays.asList("A", "B"))
                .build();
    }

    private static int[] tiles(int side, int tile) {
        int[] labels = new int[side * side];
        int perRow = side / tile;
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                labels[y * side + x] = 1 + (y / tile) * perRow + (x / tile);
            }
        }
        return labels;
    }

    private static ImagePlus image(String title, int width, int height, int[] values) {
        ShortProcessor processor = new ShortProcessor(width, height);
        for (int i = 0; i < values.length; i++) {
            processor.set(i, values[i]);
        }
        return new ImagePlus(title, processor);
    }

    /** One object's samples, gathered by the test rather than by the code under test. */
    private static double[] samplesOf(int[] labels, int[] intensity, int label) {
        int count = 0;
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] == label) {
                count++;
            }
        }
        double[] samples = new double[count];
        int at = 0;
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] == label) {
                samples[at++] = intensity[i];
            }
        }
        return samples;
    }

    private static double mandersFraction(double[] numerator, double[] gate,
                                          double gateThreshold) {
        double total = 0.0;
        double coloc = 0.0;
        for (int i = 0; i < numerator.length; i++) {
            total += numerator[i];
            if (gate[i] > gateThreshold) {
                coloc += numerator[i];
            }
        }
        return total <= 0.0 ? Double.NaN : coloc / total;
    }

    /** Deliberately not the accumulator under test. */
    private static double twoPassPearson(double[] a, double[] b) {
        double meanA = 0.0;
        double meanB = 0.0;
        for (int i = 0; i < a.length; i++) {
            meanA += a[i];
            meanB += b[i];
        }
        meanA /= a.length;
        meanB /= b.length;

        double sumSqA = 0.0;
        double sumSqB = 0.0;
        double sumCo = 0.0;
        for (int i = 0; i < a.length; i++) {
            double da = a[i] - meanA;
            double db = b[i] - meanB;
            sumSqA += da * da;
            sumSqB += db * db;
            sumCo += da * db;
        }
        return sumCo / Math.sqrt(sumSqA * sumSqB);
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
        assertEquals("a run must leave no worker threads", 0, live);
    }

    // ------------------------------------------------------------------
    // Test doubles
    // ------------------------------------------------------------------

    /** Cancels after a set number of polls made by the thread that started the run. */
    private static final class CancelAfterCoordinatorPolls implements EngineProgress {
        private final Thread coordinator = Thread.currentThread();
        private final int after;
        private final AtomicInteger polls = new AtomicInteger();
        private volatile boolean cancelled;

        CancelAfterCoordinatorPolls(int after) {
            this.after = after;
        }

        @Override
        public void report(String stage, double fraction) {
            // deliberately empty
        }

        @Override
        public boolean isCancelled() {
            if (!cancelled && Thread.currentThread() == coordinator
                    && polls.incrementAndGet() > after) {
                cancelled = true;
            }
            return cancelled;
        }
    }

    /** Cancels from inside a running object rather than while the queue fills. */
    private static final class CancelAfterWorkerPolls implements EngineProgress {
        final AtomicInteger workerPolls = new AtomicInteger();
        private final int after;
        private volatile boolean cancelled;

        CancelAfterWorkerPolls(int after) {
            this.after = after;
        }

        @Override
        public void report(String stage, double fraction) {
            // deliberately empty
        }

        @Override
        public boolean isCancelled() {
            if (!cancelled && Thread.currentThread().getName().startsWith(WORKER_PREFIX)
                    && workerPolls.incrementAndGet() >= after) {
                cancelled = true;
            }
            return cancelled;
        }
    }

    /** Accepts work and never runs it, so what remains queued can be inspected. */
    private static final class QueueingExecutor extends AbstractExecutorService {

        final List<Runnable> queued = new ArrayList<Runnable>();
        private volatile boolean shutdown;

        @Override
        public void execute(Runnable command) {
            queued.add(command);
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return new ArrayList<Runnable>(queued);
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }
    }

    /**
     * Delays each task in inverse proportion to its submission order, so the
     * objects complete backwards. Only {@code execute} needs overriding —
     * {@link AbstractExecutorService} builds {@code submit} on top of it.
     */
    /**
     * Runs the first {@code failAt + 1} objects inline on the submitting thread,
     * makes object {@code failAt} throw, and queues everything after it without
     * running it.
     */
    private static final class FailingExecutor extends AbstractExecutorService {

        final List<FutureTask<?>> queued = new ArrayList<FutureTask<?>>();

        private final int failAt;
        private final AtomicInteger submitted = new AtomicInteger();
        private int run;

        FailingExecutor(int failAt) {
            this.failAt = failAt;
        }

        @Override
        public <T> Future<T> submit(final Callable<T> task) {
            final int index = submitted.getAndIncrement();
            return super.submit(new Callable<T>() {
                @Override
                public T call() throws Exception {
                    if (index == failAt) {
                        throw new IllegalStateException("object " + index + " failed");
                    }
                    return task.call();
                }
            });
        }

        @Override
        public void execute(Runnable command) {
            if (run <= failAt) {
                run++;
                command.run();
                return;
            }
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
