package ocs.engine.spatial;

import ocs.engine.CurveSeries;
import ocs.engine.DirectionKey;
import ocs.engine.EngineInputs;
import ocs.engine.EngineProgress;
import ocs.engine.EngineResult;
import org.junit.Test;
import sc.fiji.opa.core.spatial.EdgeCorrection;
import sc.fiji.opa.core.spatial.SpatialStatistics;

import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What the seed means, and what the envelope does and does not say.
 *
 * <p>Written against the Determinism section of {@code 02_CONTRACT.md}, which was
 * paid for three times in wave 1: three modules asserted only aggregates, and an
 * aggregate is order-free by construction, so a scrambled parallel merge passes
 * it. Everything here asserts per-radius values, and every reproducibility check
 * compares raw IEEE-754 bits rather than a tolerance — plugin 04's mutation was
 * caught only at tolerance exactly zero and passed every time at 1e-12.
 */
public class SpatialDeterminismTest {

    private static final int SIZE = 240;
    private static final double[] RADII =
            {5.0, 10.0, 15.0, 20.0, 25.0, 30.0, 35.0, 40.0, 45.0, 50.0};
    private static final int SIMULATIONS = 99;
    private static final long SEED = 20260811L;
    private static final DirectionKey A_TO_B = new DirectionKey(0, "A", 1, "B");
    private static final DirectionKey B_TO_A = new DirectionKey(1, "B", 0, "A");

    // ------------------------------------------------------------------
    // Per-radius values, computed by hand
    // ------------------------------------------------------------------

    @Test
    public void crossGMatchesValuesWorkedOutByHandAndKnowsItsDirection() {
        // Four source objects in a row; two target objects. Nearest-target
        // distances are 3, 7, 12.207 and 21.190, so cross-G at these radii is the
        // running share of four. Read the other way it is a share of two, over a
        // different pair of distances — which is exactly what makes swapping the
        // two point sets a bug this fixture can see.
        EngineInputs inputs = SpatialFixtures.inputs(
                SpatialFixtures.labels("A", 60, 60,
                        new int[][] {{10, 10}, {20, 10}, {30, 10}, {40, 10}}),
                SpatialFixtures.labels("B", 60, 60,
                        new int[][] {{10, 13}, {20, 17}}));
        double[] radii = {2.0, 5.0, 10.0, 15.0, 25.0};

        EngineResult result = new CrossGEngine(19, SEED, radii)
                .compute(inputs, EngineProgress.SILENT);

        assertArrayEquals(new double[] {0.0, 0.25, 0.5, 0.75, 1.0},
                result.curve(A_TO_B).series("Observed"), 0.0);
        assertArrayEquals(new double[] {0.0, 0.5, 1.0, 1.0, 1.0},
                result.curve(B_TO_A).series("Observed"), 0.0);
    }

    @Test
    public void everyEnginesObservedCurveMatchesTheStatisticItClaimsToReport() {
        // The adapter's whole job is to hand opa-core the right points, window,
        // radii and correction. Recomputing the statistic straight from the point
        // pattern is the only check that sees a swapped pair of channels, an
        // unsorted axis or a dropped calibration, none of which change the shape
        // of the answer.
        EngineInputs inputs = clustered();
        PointPattern pattern = PointPattern.from(inputs);
        double[][] source = pattern.points(0);
        double[][] target = pattern.points(1);

        assertArrayEquals(
                SpatialStatistics.computeCrossK(source, target, pattern.window(),
                        RADII, EdgeCorrection.TRANSLATION),
                observed(new CrossKEngine(EdgeCorrection.TRANSLATION, 9, SEED, RADII),
                        inputs), 0.0);
        assertArrayEquals(
                SpatialStatistics.computeL(SpatialStatistics.computeCrossK(
                        source, target, pattern.window(), RADII,
                        EdgeCorrection.TRANSLATION)),
                observed(new CrossLEngine(EdgeCorrection.TRANSLATION, 9, SEED, RADII),
                        inputs), 0.0);
        assertArrayEquals(
                SpatialStatistics.computeCrossG(source, target, RADII),
                observed(new CrossGEngine(9, SEED, RADII), inputs), 0.0);
        assertArrayEquals(
                SpatialStatistics.computeCrossPairCorrelation(source, target,
                        pattern.window(), RADII, EdgeCorrection.TRANSLATION),
                observed(new CrossPairCorrelationEngine(EdgeCorrection.TRANSLATION,
                        9, SEED, RADII), inputs), 0.0);
    }

    // ------------------------------------------------------------------
    // Reproducibility, on raw bits
    // ------------------------------------------------------------------

    @Test
    public void oneSeedRunFiveTimesIsBitIdentical() {
        EngineInputs inputs = clustered();
        CurveSeries reference = null;
        for (int run = 0; run < 5; run++) {
            CurveSeries curve = new CrossKEngine(EdgeCorrection.TRANSLATION,
                    SIMULATIONS, SEED, RADII)
                    .compute(inputs, EngineProgress.SILENT).curve(A_TO_B);
            if (reference == null) {
                reference = curve;
                continue;
            }
            assertBitIdentical("run " + run, reference, curve);
        }
    }

    @Test
    public void theSeedRecordedOnTheCurveIsTheSeedThatReproducesIt() {
        // Without this the seed on the curve is decorative: it would be written
        // into the run record, read back six months later, and replay a different
        // envelope.
        EngineInputs inputs = clustered();
        CurveSeries first = new CrossPairCorrelationEngine(EdgeCorrection.TRANSLATION,
                SIMULATIONS, 762309L, RADII)
                .compute(inputs, EngineProgress.SILENT).curve(A_TO_B);

        long recorded = (long) first.scalars().get("Seed").doubleValue();
        assertEquals(762309L, recorded);

        CurveSeries replayed = new CrossPairCorrelationEngine(EdgeCorrection.TRANSLATION,
                SIMULATIONS, recorded, RADII)
                .compute(inputs, EngineProgress.SILENT).curve(A_TO_B);
        assertBitIdentical("replayed from the recorded seed", first, replayed);
    }

    @Test
    public void changingOnlyTheSeedMovesTheEnvelopeAndLeavesTheObservationAlone() {
        // The negative control for the two tests above. A comparison that cannot
        // fail is not evidence, so something has to demonstrate that the seed
        // reaches the envelope at all.
        EngineInputs inputs = clustered();
        CurveSeries first = new CrossKEngine(EdgeCorrection.TRANSLATION, SIMULATIONS,
                11L, RADII).compute(inputs, EngineProgress.SILENT).curve(A_TO_B);
        CurveSeries second = new CrossKEngine(EdgeCorrection.TRANSLATION, SIMULATIONS,
                12L, RADII).compute(inputs, EngineProgress.SILENT).curve(A_TO_B);

        assertArrayEquals("the data did not change, so the observation must not",
                first.series("Observed"), second.series("Observed"), 0.0);

        boolean moved = false;
        for (int i = 0; i < RADII.length && !moved; i++) {
            moved = first.series("Upper")[i] != second.series("Upper")[i]
                    || first.series("Lower")[i] != second.series("Lower")[i];
        }
        assertTrue("a different seed must draw a different envelope", moved);
    }

    @Test
    public void everyWorkerCountGivesTheSameCurve() {
        // opa-core draws every simulated pattern from one stream on the
        // coordinating thread and merges by index, so worker count changes the
        // speed and nothing else. Asserted on bits: completion-order scrambling
        // breaks the last bits rather than the answer, and a single
        // serial-versus-parallel comparison at a loose tolerance cannot see that.
        //
        // The property is opa-core's own and keeps its name. Nothing in this
        // module wraps, renames or shadows it, and no engine here starts a thread.
        EngineInputs inputs = clustered();
        String previous = System.getProperty("opa.parallelism");
        try {
            CurveSeries reference = null;
            for (int workers : new int[] {1, 2, 4, 8}) {
                System.setProperty("opa.parallelism", Integer.toString(workers));
                CurveSeries curve = new CrossKEngine(EdgeCorrection.TRANSLATION,
                        SIMULATIONS, SEED, RADII)
                        .compute(inputs, EngineProgress.SILENT).curve(A_TO_B);
                if (reference == null) {
                    reference = curve;
                    continue;
                }
                assertBitIdentical("workers=" + workers, reference, curve);
            }
        } finally {
            if (previous == null) {
                System.clearProperty("opa.parallelism");
            } else {
                System.setProperty("opa.parallelism", previous);
            }
        }
    }

    // ------------------------------------------------------------------
    // Controls: both directions, not just the flattering one
    // ------------------------------------------------------------------

    @Test
    public void aDeliberatelyClusteredPairIsCalledSignificant() {
        CurveSeries curve = new CrossPairCorrelationEngine(EdgeCorrection.TRANSLATION,
                SIMULATIONS, SEED, RADII)
                .compute(clustered(), EngineProgress.SILENT).curve(A_TO_B);

        assertTrue(curve.isOk());
        assertTrue("clustered pair: p = " + curve.scalars().get("Global p"),
                curve.scalars().get("Global p").doubleValue() <= 0.05);
        assertTrue("g(5) = " + curve.series("Observed")[0] + " should exceed 1",
                curve.series("Observed")[0] > 1.0);
        assertTrue("and should escape the pointwise band at the shortest radius",
                curve.series("Observed")[0] > curve.series("Upper")[0]);
    }

    @Test
    public void anIndependentPairIsNotCalledSignificant() {
        // The half that is easy to leave out. A test suite that only ever shows
        // the method finding what was planted in it has not shown it can decline.
        CurveSeries curve = new CrossPairCorrelationEngine(EdgeCorrection.TRANSLATION,
                SIMULATIONS, SEED, RADII)
                .compute(independent(), EngineProgress.SILENT).curve(A_TO_B);

        assertTrue(curve.isOk());
        assertTrue("independent pair: p = " + curve.scalars().get("Global p"),
                curve.scalars().get("Global p").doubleValue() > 0.05);

        // And the null is centred: the envelope is the 2.5-97.5 band of 99
        // independent simulated curves, so the expectation of 1 has to sit inside
        // it at every radius. A structural property of a correct null, not a coin
        // toss.
        for (int i = 0; i < RADII.length; i++) {
            assertTrue("r=" + RADII[i] + " envelope [" + curve.series("Lower")[i]
                            + ", " + curve.series("Upper")[i]
                            + "] excludes the expectation of 1",
                    curve.series("Lower")[i] <= 1.0 && curve.series("Upper")[i] >= 1.0);
        }
    }

    @Test
    public void pointwiseEnvelopesAreNotASimultaneousBand() {
        // Carried forward from opa-core's CrossPairCorrelationTest, because the
        // misreading it guards against survives the move into this plugin
        // unchanged and is the single most likely way a user draws a wrong
        // conclusion from a spatial result.
        //
        // A pointwise 2.5-97.5 band at ten radii is not a 95% band over the whole
        // curve. An independent pair is expected to poke outside it at some radius
        // in a good fraction of runs, and doing so is not significance — the
        // global maximum-deviation test is what answers that question.
        //
        // Stated over twelve analysis seeds rather than one fixture, so it asserts
        // the property rather than an accident of a single band.
        EngineInputs inputs = independent();
        int excursions = 0;
        int excursionsThatDoNotReject = 0;
        for (long seed = 1L; seed <= 12L; seed++) {
            CurveSeries curve = new CrossPairCorrelationEngine(
                    EdgeCorrection.TRANSLATION, SIMULATIONS, seed, RADII)
                    .compute(inputs, EngineProgress.SILENT).curve(A_TO_B);
            boolean outside = false;
            for (int i = 0; i < RADII.length; i++) {
                if (curve.series("Observed")[i] > curve.series("Upper")[i]
                        || curve.series("Observed")[i] < curve.series("Lower")[i]) {
                    outside = true;
                }
            }
            if (outside) {
                excursions++;
                if (curve.scalars().get("Global p").doubleValue() > 0.05) {
                    excursionsThatDoNotReject++;
                }
            }
        }
        assertTrue("an independent pair should leave the pointwise band in some of"
                        + " twelve runs; if it never does, the point still stands but"
                        + " the fixture is stale", excursions > 0);
        assertTrue("leaving the pointwise band is not significance, and at least one"
                        + " run must show both at once", excursionsThatDoNotReject > 0);
    }

    @Test
    public void everyRadiusDrawsOnEverySimulationOnAWorkableFixture() {
        // The envelope's own honesty check. Where some simulated curves are not
        // estimable at a radius, opa-core reports NaN there rather than a band
        // over fewer samples, and the engine carries the count so a reader can see
        // it happened.
        CurveSeries curve = new CrossKEngine(EdgeCorrection.TRANSLATION, SIMULATIONS,
                SEED, RADII).compute(clustered(), EngineProgress.SILENT).curve(A_TO_B);
        assertEquals(1.0, curve.scalars().get("Envelope Complete").doubleValue(), 0.0);
        for (double count : curve.series("Envelope Samples")) {
            assertEquals(SIMULATIONS, count, 0.0);
        }
        assertEquals(SIMULATIONS + 1.0,
                curve.scalars().get("Rank Sample Count").doubleValue(), 0.0);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static double[] observed(ocs.engine.ColocEngine engine, EngineInputs inputs) {
        return engine.compute(inputs, EngineProgress.SILENT).curve(A_TO_B)
                .series("Observed");
    }

    /**
     * Every target three pixels east of a source, on a lattice far coarser than
     * that. Attraction at the shortest radius and nothing else.
     */
    private static EngineInputs clustered() {
        int[][] source = SpatialFixtures.grid(8, 8, 26, 15, 15);
        return SpatialFixtures.inputs(
                SpatialFixtures.labels("A", SIZE, SIZE, source),
                SpatialFixtures.labels("B", SIZE, SIZE,
                        SpatialFixtures.shifted(source, 3, 0)));
    }

    /** Two point sets drawn independently, from a seed that is not the analysis seed. */
    private static EngineInputs independent() {
        Random random = new Random(20260812L);
        return SpatialFixtures.inputs(
                SpatialFixtures.labels("A", SIZE, SIZE,
                        SpatialFixtures.uniform(64, SIZE, SIZE, 4, random)),
                SpatialFixtures.labels("B", SIZE, SIZE,
                        SpatialFixtures.uniform(64, SIZE, SIZE, 4, random)));
    }

    /**
     * Bit-for-bit, on every series value and every scalar.
     *
     * <p>{@code assertArrayEquals} at 0.0 would let two NaNs of different bit
     * patterns through and, more importantly, would say nothing about the scalars.
     */
    private static void assertBitIdentical(String at, CurveSeries expected,
                                           CurveSeries actual) {
        assertEquals(at, expected.status(), actual.status());
        assertEquals(at, expected.seriesNames(), actual.seriesNames());
        assertArrayEquals(at + " axis", expected.x(), actual.x(), 0.0);
        for (String series : expected.seriesNames()) {
            double[] left = expected.series(series);
            double[] right = actual.series(series);
            assertEquals(at + " " + series + " length", left.length, right.length);
            for (int i = 0; i < left.length; i++) {
                assertEquals(at + " " + series + "[" + i + "]",
                        Double.doubleToRawLongBits(left[i]),
                        Double.doubleToRawLongBits(right[i]));
            }
        }
        Map<String, Double> scalars = expected.scalars();
        assertEquals(at + " scalar names", scalars.keySet(), actual.scalars().keySet());
        for (Map.Entry<String, Double> entry : scalars.entrySet()) {
            assertEquals(at + " scalar " + entry.getKey(),
                    Double.doubleToRawLongBits(entry.getValue().doubleValue()),
                    Double.doubleToRawLongBits(
                            actual.scalars().get(entry.getKey()).doubleValue()));
        }
        assertFalse(at, expected == actual);
    }
}
