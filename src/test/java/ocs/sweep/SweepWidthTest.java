package ocs.sweep;

import ocs.engine.ThresholdBearing;
import ocs.engine.object.BoundingBoxEngine;
import ocs.engine.object.ContainmentEngine;
import ocs.engine.object.DistanceToleranceEngine;
import ocs.engine.object.JaccardDiceEngine;
import ocs.engine.object.VolumeOverlapEngine;
import ocs.engine.intensity.PerObjectIntensityEngine;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The sweep asks about a neighbourhood of the threshold the user chose, not
 * about the measure's whole range.
 *
 * <p>Sweeping the whole range asks "would the verdict change at <i>any</i>
 * cut-off", and on real segmentations the answer is almost always yes — median
 * flip fraction 0.73 on confocal data, 1.000 for volumetric overlap, so 83% of
 * directions came back Fragile. That is not a measurement, it is a restatement
 * of the fact that overlap percentages are spread out. The useful question is
 * whether a slightly different choice would have changed the answer.
 *
 * <p>The width is a fraction of each measure's own range rather than an absolute
 * number, because the six threshold-bearing engines are on four different
 * scales. That is what these tests mostly pin: one number, the same meaning
 * everywhere.
 */
public class SweepWidthTest {

    private static final double EXACT = 1.0e-12;

    /** Every engine that carries a threshold, at its own default setting. */
    private static List<ThresholdBearing> bearing() {
        return Arrays.<ThresholdBearing>asList(
                new VolumeOverlapEngine(), new BoundingBoxEngine(),
                new ContainmentEngine(), new JaccardDiceEngine(),
                new DistanceToleranceEngine(), new PerObjectIntensityEngine());
    }

    @Test
    public void everyThresholdBearingEngineDeclaresAUsableRange() {
        List<ThresholdBearing> engines = bearing();
        for (int i = 0; i < engines.size(); i++) {
            double[] range = engines.get(i).thresholdRange();
            assertEquals(engines.get(i) + " must give a low and a high", 2, range.length);
            assertTrue(engines.get(i) + " has an empty range " + Arrays.toString(range),
                    range[1] > range[0]);
            assertTrue(engines.get(i) + " sits outside its own range",
                    engines.get(i).threshold() >= range[0]
                            && engines.get(i).threshold() <= range[1]);
        }
    }

    /**
     * The same fraction produces a different absolute width on each measure —
     * which is the entire reason it is a fraction.
     */
    @Test
    public void oneFractionMeansTheRightWidthOnEveryScale() {
        // Overlap runs 0-100, so a fifth of it is 20 percentage points.
        assertSpan(new VolumeOverlapEngine(50.0), 0.2, 30.0, 70.0);
        // Jaccard runs 0-1, so a fifth of it is 0.2.
        assertSpan(new JaccardDiceEngine(0.5), 0.2, 0.3, 0.7);
        // Pearson runs -1 to 1, a span of 2, so a fifth of it is 0.4.
        assertSpan(new PerObjectIntensityEngine(0.0), 0.2, -0.4, 0.4);

        // A fixed absolute width could not do this: 20 is a fifth of the overlap
        // range, twenty times the whole Jaccard range, and ten times Pearson's.
    }

    @Test
    public void theLadderIsCentredOnTheThresholdTheUserChose() {
        double[] ladder = ThresholdSweep.neighbourhood(new VolumeOverlapEngine(40.0), 0.1);
        assertEquals("centred low", 30.0, ladder[0], EXACT);
        assertEquals("centred high", 50.0, ladder[ladder.length - 1], EXACT);
        assertEquals("the chosen threshold is itself a step",
                40.0, ladder[ladder.length / 2], EXACT);
    }

    /**
     * A threshold near the end of its range gets a shorter sweep rather than one
     * containing settings the engine would refuse. A sweep is not the place to
     * find out that −5% is not a percentage.
     */
    @Test
    public void aThresholdNearTheEdgeIsClampedRatherThanPushedOutOfRange() {
        double[] low = ThresholdSweep.neighbourhood(new VolumeOverlapEngine(5.0), 0.2);
        assertEquals(0.0, low[0], EXACT);
        assertEquals(25.0, low[low.length - 1], EXACT);

        double[] high = ThresholdSweep.neighbourhood(new JaccardDiceEngine(0.95), 0.2);
        assertEquals(0.75, high[0], EXACT);
        assertEquals(1.0, high[high.length - 1], EXACT);

        // Every step must be a setting the engine will actually accept.
        for (int i = 0; i < low.length; i++) {
            new VolumeOverlapEngine().withThreshold(low[i]);
        }
        for (int i = 0; i < high.length; i++) {
            new JaccardDiceEngine().withThreshold(high[i]);
        }
    }

    @Test
    public void aWidthOfOneOrMoreSweepsTheWholeRangeAsItUsedTo() {
        VolumeOverlapEngine engine = new VolumeOverlapEngine(50.0);
        assertTrue("width 1.0 must reproduce the old full-range ladder",
                Arrays.equals(engine.defaultLadder(),
                        ThresholdSweep.neighbourhood(engine, 1.0)));
        assertTrue("and anything wider is still the whole range",
                Arrays.equals(engine.defaultLadder(),
                        ThresholdSweep.neighbourhood(engine, 4.0)));
    }

    /**
     * Zero width is refused rather than quietly reporting every method perfectly
     * stable — the most flattering answer available, and one nothing downstream
     * would mark as vacuous.
     */
    @Test
    public void aZeroWidthSweepIsRefused() {
        try {
            ThresholdSweep.neighbourhood(new VolumeOverlapEngine(50.0), 0.0);
            fail("a sweep with no width must be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("width"));
        }
        try {
            ocs.OCSParameters.builder(null).sweepWidth(-0.1);
            fail("a negative width must be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("width"));
        }
    }

    /** Every ladder still increases, which {@code ThresholdSweep.of} requires. */
    @Test
    public void everyGeneratedLadderIsStrictlyAscending() {
        List<ThresholdBearing> engines = bearing();
        double[] widths = {0.05, 0.1, 0.2, 0.5, 0.99};
        for (int e = 0; e < engines.size(); e++) {
            for (int w = 0; w < widths.length; w++) {
                double[] ladder = ThresholdSweep.neighbourhood(engines.get(e), widths[w]);
                assertTrue(engines.get(e) + " at width " + widths[w]
                                + " gave a ladder of " + ladder.length, ladder.length >= 2);
                for (int i = 1; i < ladder.length; i++) {
                    assertTrue(engines.get(e) + " at width " + widths[w]
                                    + ": step " + i + " is " + ladder[i]
                                    + " after " + ladder[i - 1],
                            ladder[i] > ladder[i - 1]);
                }
            }
        }
    }

    private static void assertSpan(ThresholdBearing engine, double width,
            double low, double high) {
        double[] ladder = ThresholdSweep.neighbourhood(engine, width);
        assertEquals(engine + " low end", low, ladder[0], EXACT);
        assertEquals(engine + " high end", high, ladder[ladder.length - 1], EXACT);
    }
}
