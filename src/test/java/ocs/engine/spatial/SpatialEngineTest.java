package ocs.engine.spatial;

import ij.gui.OvalRoi;
import ij.gui.Roi;
import ocs.engine.ColocEngine;
import ocs.engine.ColumnSpec;
import ocs.engine.CurveSeries;
import ocs.engine.DirectionKey;
import ocs.engine.EngineCancelledException;
import ocs.engine.EngineInputs;
import ocs.engine.EngineProgress;
import ocs.engine.EngineRegistry;
import ocs.engine.EngineResult;
import ocs.engine.ScaleKind;
import org.junit.Test;
import sc.fiji.opa.core.spatial.EdgeCorrection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The four spatial engines: their contract, their refusals, and the one design
 * decision that needed evidence rather than an argument.
 *
 * <p>Determinism, reproducibility and the statistical controls live next door in
 * {@link SpatialDeterminismTest}, because those need a larger fixture and this
 * file is about shape.
 */
public class SpatialEngineTest {

    private static final int SIZE = 60;
    private static final double[] RADII = {2.0, 4.0, 6.0, 8.0, 10.0};
    private static final int SIMULATIONS = 19;
    private static final long SEED = 4242L;

    private static final EngineProgress CANCELLED = new EngineProgress() {
        @Override
        public void report(String stage, double fraction) {
        }

        @Override
        public boolean isCancelled() {
            return true;
        }
    };

    // ------------------------------------------------------------------
    // Contract
    // ------------------------------------------------------------------

    @Test
    public void everySpatialEngineDeclaresOnePrimaryColumnWithAScale() {
        for (ColocEngine engine : defaults()) {
            int primaries = 0;
            for (ColumnSpec column : engine.columns()) {
                assertNotNull("column " + column.name() + " of " + engine.id()
                        + " must declare a scale", column.scale());
                if (column.isPrimary()) {
                    primaries++;
                }
            }
            assertEquals(engine.id() + " must have exactly one primary column",
                    1, primaries);
            assertTrue(engine.id() + " must report a positive relative cost",
                    engine.relativeCost() > 0.0);
        }
    }

    @Test
    public void allFourEnginesPassRegistrationValidation() {
        EngineRegistry registry = EngineRegistry.empty();
        for (ColocEngine engine : defaults()) {
            registry.register(engine);
        }
        assertEquals(4, registry.size());
        assertTrue(registry.has("cross-g"));
        assertTrue(registry.has("cross-k"));
        assertTrue(registry.has("cross-l"));
        assertTrue(registry.has("cross-pair-correlation"));
    }

    @Test
    public void theSpatialFamilyIsNotEligibleForDirectValueAgreement() {
        // Stated as the property, not as one enum constant. What must hold is
        // that a curve ordinate is comparable with nothing — including another
        // curve ordinate, since cross-G living in [0,1] does not make it a
        // Jaccard index. Naming the constant instead made this test fail when
        // CURVE replaced UNBOUNDED, which was the declaration getting *more*
        // honest, not a regression.
        for (ColocEngine engine : defaults()) {
            for (ColumnSpec column : engine.columns()) {
                if (column.isPrimary()) {
                    for (ScaleKind other : ScaleKind.values()) {
                        assertFalse(engine.id() + " must not be tier-3 eligible "
                                        + "against " + other,
                                column.scale().isComparableWith(other));
                    }
                }
            }
        }
    }

    /**
     * The curve family declares {@link ScaleKind#CURVE}, and that is a claim
     * about what the column holds rather than only about what it is eligible for.
     *
     * <p>Kept separate from the test above so the two failure modes read
     * differently: this one breaking means an engine mislabelled its quantity;
     * that one breaking means the agreement gate leaks.
     */
    @Test
    public void everyCurveEnginesPrimaryColumnDeclaresItselfACurve() {
        for (ColocEngine engine : defaults()) {
            for (ColumnSpec column : engine.columns()) {
                if (column.isPrimary()) {
                    assertEquals(engine.id(), ScaleKind.CURVE, column.scale());
                }
            }
        }
    }

    @Test
    public void everySpatialEngineNeedsARandomizationDomain() {
        // 02_CONTRACT.md: a null model without a domain is meaningless and must be
        // refused rather than defaulted. The envelope is a null model.
        EngineInputs noDomain = EngineInputs.builder(Arrays.asList(
                SpatialFixtures.labels("A", SIZE, SIZE, SpatialFixtures.grid(3, 3, 8, 5, 5)),
                SpatialFixtures.labels("B", SIZE, SIZE, SpatialFixtures.grid(3, 3, 8, 7, 7))))
                .build();
        for (ColocEngine engine : defaults()) {
            assertFalse(engine.id() + " must not be runnable without a domain",
                    noDomain.missing(engine.requires()).isEmpty());
        }
    }

    @Test
    public void everySpatialEngineHonoursCancellation() {
        EngineInputs inputs = pair();
        for (ColocEngine engine : fast()) {
            try {
                engine.compute(inputs, CANCELLED);
                fail(engine.id() + " ignored a cancelled progress reporter");
            } catch (EngineCancelledException expected) {
                assertTrue(expected.getMessage().contains(engine.id()));
            }
        }
    }

    @Test
    public void noSpatialEngineProducesAPerObjectScore() {
        for (ColocEngine engine : fast()) {
            EngineResult result = engine.compute(pair(), EngineProgress.SILENT);
            assertTrue(engine.id() + " must report curves", result.hasCurves());
            assertFalse(engine.id() + " has no per-object value to report",
                    result.hasObjectScores());
            assertFalse(engine.id() + " is not a whole-direction scalar engine",
                    result.isWholeDirection());
            assertEquals(2, result.directions().size());
        }
    }

    @Test
    public void aCurveCarriesEveryVectorAndScalarTheAnalyzerProduced() {
        EngineResult result = new CrossKEngine(EdgeCorrection.TRANSLATION,
                SIMULATIONS, SEED, RADII).compute(pair(), EngineProgress.SILENT);
        CurveSeries curve = result.curve(new DirectionKey(0, "A", 1, "B"));

        assertEquals(Arrays.asList("Observed", "Expected", "Lower", "Upper",
                "Envelope Samples"), curve.seriesNames());
        assertEquals(RADII.length, curve.length());
        assertArrayEquals(RADII, curve.x(), 0.0);
        assertEquals("pixel", curve.xUnit());
        assertTrue(curve.isOk());

        for (String scalar : new String[] {"Global p", "Minimum Achievable p",
                "Max Deviation", "Max Deviation Radius", "Simulations", "Seed",
                "Rank Sample Count", "Envelope Complete", "Source Points",
                "Target Points", "Envelope Level", "Saturation Radius",
                "Saturated Radii"}) {
            assertTrue("missing scalar " + scalar, curve.scalars().containsKey(scalar));
        }
        assertEquals(SIMULATIONS, curve.scalars().get("Simulations").doubleValue(), 0.0);
        assertEquals(SEED, curve.scalars().get("Seed").doubleValue(), 0.0);
        assertEquals(1.0 / (SIMULATIONS + 1),
                curve.scalars().get("Minimum Achievable p").doubleValue(), 0.0);
        assertEquals(9.0, curve.scalars().get("Source Points").doubleValue(), 0.0);

        // Every column the engine declares beyond the primary names a scalar the
        // curve actually carries, so the summary table cannot declare a column
        // nothing fills.
        for (ColumnSpec column : new CrossKEngine().columns()) {
            if (!column.isPrimary()) {
                assertTrue("declared column '" + column.name() + "' names no scalar",
                        curve.scalars().containsKey(column.name()));
            }
        }
    }

    @Test
    public void theEnvelopeLevelIsTheOneDeliveredNotANominalFivePercent() {
        // opa-core 0.3.0's rank envelope delivers 2k/(S+1): at the default 99
        // simulations k = 2, a 4% escape probability, a 96% band.
        CurveSeries atDefault = new CrossKEngine().compute(pair(), EngineProgress.SILENT)
                .curve(new DirectionKey(0, "A", 1, "B"));
        assertEquals(0.04, atDefault.scalars().get(SpatialCurveEngine.ENVELOPE_LEVEL)
                .doubleValue(), 1e-12);

        CurveSeries at39 = new CrossKEngine(EdgeCorrection.TRANSLATION, 39, SEED, RADII)
                .compute(pair(), EngineProgress.SILENT)
                .curve(new DirectionKey(0, "A", 1, "B"));
        assertEquals("39 simulations is where the rank lands on 5% exactly", 0.05,
                at39.scalars().get(SpatialCurveEngine.ENVELOPE_LEVEL).doubleValue(), 1e-12);
    }

    @Test
    public void crossGReportsWhereItSaturatesAndTheKCurvesDoNot() {
        // Nine objects in a 60 x 60 window: cross-G reaches 0.99 under randomness
        // near r = sqrt(ln 100 / (lambda pi)), about 24 pixels, so radii out to 28
        // include some that carry no information.
        double[] wide = {4.0, 8.0, 12.0, 16.0, 20.0, 24.0, 26.0, 28.0};
        CurveSeries g = new CrossGEngine(SIMULATIONS, SEED, wide)
                .compute(pair(), EngineProgress.SILENT)
                .curve(new DirectionKey(0, "A", 1, "B"));
        double saturation = g.scalars().get(SpatialCurveEngine.SATURATION_RADIUS).doubleValue();
        assertFalse("cross-G saturates", Double.isNaN(saturation));
        int past = 0;
        for (int i = 0; i < wide.length; i++) {
            if (wide[i] > saturation) {
                past++;
            }
        }
        assertTrue("some requested radii lie past saturation at " + saturation, past > 0);
        assertEquals(past, g.scalars().get(SpatialCurveEngine.SATURATED_RADII)
                .doubleValue(), 0.0);
        assertEquals("every requested radius still comes back", wide.length, g.length());

        CurveSeries k = new CrossKEngine(EdgeCorrection.TRANSLATION, SIMULATIONS, SEED,
                RADII).compute(pair(), EngineProgress.SILENT)
                .curve(new DirectionKey(0, "A", 1, "B"));
        assertTrue(Double.isNaN(k.scalars().get(SpatialCurveEngine.SATURATION_RADIUS)
                .doubleValue()));
        assertEquals(0.0, k.scalars().get(SpatialCurveEngine.SATURATED_RADII)
                .doubleValue(), 0.0);
    }

    @Test
    public void theDefaultsArePinned() {
        // 02_CONTRACT.md is explicit that defaults are stated, not implied: a
        // default that changes silently changes every run's numbers and its
        // recorded provenance at the same time, so nothing in the output looks
        // different afterwards. Every one of these five is a judgement call, and
        // this is where the judgement is written down.
        assertEquals(EdgeCorrection.TRANSLATION, new CrossKEngine().correction());
        assertEquals(EdgeCorrection.TRANSLATION, new CrossLEngine().correction());
        assertEquals(EdgeCorrection.TRANSLATION,
                new CrossPairCorrelationEngine().correction());
        assertEquals("cross-G has no edge correction to choose",
                EdgeCorrection.NONE, new CrossGEngine().correction());

        for (ColocEngine engine : defaults()) {
            SpatialCurveEngine spatial = (SpatialCurveEngine) engine;
            assertEquals(engine.id(), 99, spatial.simulations());
            assertEquals(engine.id(), 20260811L, spatial.seed());
        }

        // And the default radius axis: twenty steps out to a quarter of the
        // shorter window side, which is the standard rule of thumb for K.
        double[] axis = SpatialCurveEngine.defaultRadii(
                new sc.fiji.opa.core.spatial.RectangularWindow(0.0, 0.0, 200.0, 80.0), 20);
        assertEquals(20, axis.length);
        assertEquals(1.0, axis[0], 1e-12);
        assertEquals(20.0, axis[19], 1e-12);
    }

    @Test
    public void costScalesWithTheSimulationCount() {
        // The knob a user is most likely to change is the one a fixed weight
        // would make the pre-run estimate wrong by.
        double at99 = new CrossKEngine().relativeCost();
        double at999 = new CrossKEngine(EdgeCorrection.TRANSLATION, 999, SEED)
                .relativeCost();
        assertEquals(100.0, at99, 1e-12);
        assertEquals(1000.0, at999, 1e-12);

        // Cross-G does not multiply its pair loop by the radii, and says so.
        assertTrue(new CrossGEngine().relativeCost() < at99);
    }

    // ------------------------------------------------------------------
    // Symmetry: the decision that needed evidence
    // ------------------------------------------------------------------

    @Test
    public void crossKsPointEstimateIsSymmetricButItsEnvelopeIsNot() {
        // This is why all four declare isSymmetric() false. Declaring true would
        // let a caller compute one direction and copy it: the observed curve would
        // survive that, and the envelope — the part a reader draws a conclusion
        // from — would not.
        EngineInputs inputs = unevenPair();
        EngineResult result = new CrossKEngine(EdgeCorrection.TRANSLATION,
                SIMULATIONS, SEED, RADII).compute(inputs, EngineProgress.SILENT);
        CurveSeries forward = result.curve(new DirectionKey(0, "A", 1, "B"));
        CurveSeries backward = result.curve(new DirectionKey(1, "B", 0, "A"));

        // Equal in exact arithmetic: the pair weight depends on |dx| and |dy| and
        // the denominator is nA*nB either way. Compared with a tolerance only
        // because the two sums are accumulated in a different order.
        assertArrayEquals("cross-K's point estimate is direction-free",
                forward.series("Observed"), backward.series("Observed"), 1e-9);

        assertFalse("the envelope is drawn from a different set of simulated"
                        + " patterns and must not be assumed equal",
                Arrays.equals(forward.series("Upper"), backward.series("Upper")));

        for (ColocEngine engine : defaults()) {
            assertFalse(engine.id() + " must not claim symmetry", engine.isSymmetric());
        }
    }

    @Test
    public void crossGIsAsymmetricInItsObservedCurveToo() {
        // "How many A have a B nearby" and "how many B have an A nearby" are
        // different questions. This is the misreading DirectionKey exists for.
        EngineResult result = new CrossGEngine(SIMULATIONS, SEED, RADII)
                .compute(unevenPair(), EngineProgress.SILENT);
        assertFalse(Arrays.equals(
                result.curve(new DirectionKey(0, "A", 1, "B")).series("Observed"),
                result.curve(new DirectionKey(1, "B", 0, "A")).series("Observed")));
    }

    // ------------------------------------------------------------------
    // Refusals that throw, because they are author errors
    // ------------------------------------------------------------------

    @Test
    public void borderCorrectionIsRefusedForCrossPairCorrelation() {
        // Refused at construction, so a batch cannot fail an hour in on a setting
        // that was unusable before it started. The wording is opa-core's own, so a
        // user meeting it by either route reads the same reason and the same
        // remedy.
        try {
            new CrossPairCorrelationEngine(EdgeCorrection.BORDER, SIMULATIONS, SEED);
            fail("border correction must be refused for pair correlation");
        } catch (IllegalArgumentException expected) {
            assertEquals("Cross pair correlation cannot use border correction because"
                    + " the radius-dependent risk set can make K increments negative;"
                    + " use translation or no edge correction.", expected.getMessage());
        }
        try {
            new CrossPairCorrelationEngine(EdgeCorrection.BORDER, SIMULATIONS, SEED, RADII);
            fail("border correction must be refused whichever constructor is used");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains(
                    "risk set can make K increments negative"));
        }
    }

    @Test
    public void borderCorrectionIsAcceptedWhereItIsValid() {
        // Not a blanket ban: K and L are perfectly well defined under it, and
        // banning it everywhere would be a different kind of dishonesty.
        assertEquals(EdgeCorrection.BORDER,
                new CrossKEngine(EdgeCorrection.BORDER, SIMULATIONS, SEED).correction());
        assertEquals(EdgeCorrection.BORDER,
                new CrossLEngine(EdgeCorrection.BORDER, SIMULATIONS, SEED).correction());
        // Cross-G offers no correction parameter at all, because opa-core ignores
        // one for that function; an accepted-then-discarded setting would be worse.
        assertEquals(EdgeCorrection.NONE, new CrossGEngine().correction());
    }

    @Test
    public void radiiThatDoNotIncreaseAreRefused() {
        try {
            new CrossKEngine(EdgeCorrection.TRANSLATION, SIMULATIONS, SEED,
                    new double[] {2.0, 8.0, 4.0});
            fail("unsorted radii must be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("increase strictly"));
        }
        try {
            new CrossKEngine(EdgeCorrection.TRANSLATION, SIMULATIONS, SEED,
                    new double[] {0.0, 4.0});
            fail("a zero radius must be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("strictly positive"));
        }
    }

    @Test
    public void aSeedTooLargeToRecordExactlyIsRefused() {
        // CurveSeries scalars are doubles. Past 2^53 the seed written into the run
        // record is a different seed from the one that ran, and the record would
        // replay a different envelope while looking faithful.
        long unrecordable = (1L << 53) + 1L;
        try {
            new CrossKEngine(EdgeCorrection.TRANSLATION, SIMULATIONS, unrecordable);
            fail("a seed that cannot round-trip through a double must be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("replays the run"));
        }
        // The boundary itself is fine.
        assertEquals(1L << 53, new CrossKEngine(EdgeCorrection.TRANSLATION,
                SIMULATIONS, 1L << 53).seed());
    }

    // ------------------------------------------------------------------
    // Refusals that arrive as a status, because they are data
    // ------------------------------------------------------------------

    @Test
    public void tooFewObjectsArriveAsAStatusNotAnException() {
        EngineInputs sparse = SpatialFixtures.inputs(
                SpatialFixtures.labels("A", SIZE, SIZE, new int[][] {{20, 20}}),
                SpatialFixtures.labels("B", SIZE, SIZE,
                        SpatialFixtures.grid(3, 3, 8, 5, 5)));
        for (ColocEngine engine : fast()) {
            EngineResult result = engine.compute(sparse, EngineProgress.SILENT);
            CurveSeries curve = result.curve(new DirectionKey(0, "A", 1, "B"));
            assertEquals(engine.id(), "INSUFFICIENT_POINTS", curve.status());
            assertFalse(curve.isOk());
            assertEquals(1.0, curve.scalars().get("Source Points").doubleValue(), 0.0);
            for (double value : curve.series("Observed")) {
                assertTrue("a refusal must not report zero", Double.isNaN(value));
            }
        }
    }

    @Test
    public void objectsStackedAtOnePlaceArriveAsAStatus() {
        // Two objects on different slices at the same xy collapse onto one point,
        // so the pattern has no extent and neither the curve nor the envelope
        // means anything.
        EngineInputs stacked = SpatialFixtures.inputs(
                SpatialFixtures.stack("A", SIZE, SIZE,
                        new int[][] {{20, 20, 1}, {20, 20, 2}}),
                SpatialFixtures.stack("B", SIZE, SIZE,
                        new int[][] {{10, 10, 1}, {30, 30, 2}}));

        CurveSeries curve = new CrossKEngine(EdgeCorrection.TRANSLATION, SIMULATIONS,
                SEED, RADII).compute(stacked, EngineProgress.SILENT)
                .curve(new DirectionKey(0, "A", 1, "B"));
        assertEquals("DEGENERATE_PATTERN", curve.status());
        assertEquals(2.0, curve.scalars().get("Source Points").doubleValue(), 0.0);
    }

    @Test
    public void aRadiusReachingHalfwayAcrossTheWindowArrivesAsAStatus() {
        // Past half the shorter side, translation correction has no displacement
        // left to weight with and the curve describes the window, not the pattern.
        CurveSeries curve = new CrossKEngine(EdgeCorrection.TRANSLATION, SIMULATIONS,
                SEED, new double[] {5.0, 15.0, 30.0})
                .compute(pair(), EngineProgress.SILENT)
                .curve(new DirectionKey(0, "A", 1, "B"));
        assertEquals("RADIUS_EXCEEDS_WINDOW", curve.status());
    }

    @Test
    public void aBoundingBoxDomainIsCarriedOnTheCurveRatherThanPassedOff() {
        List<Roi> oval = new ArrayList<Roi>();
        oval.add(new OvalRoi(2, 2, SIZE - 4, SIZE - 4));
        EngineInputs inputs = SpatialFixtures.inputs(oval,
                SpatialFixtures.labels("A", SIZE, SIZE, SpatialFixtures.grid(4, 4, 10, 8, 8)),
                SpatialFixtures.labels("B", SIZE, SIZE, SpatialFixtures.grid(4, 4, 10, 11, 8)));

        CurveSeries curve = new CrossKEngine(EdgeCorrection.TRANSLATION, SIMULATIONS,
                SEED, RADII).compute(inputs, EngineProgress.SILENT)
                .curve(new DirectionKey(0, "A", 1, "B"));

        // The numbers are still there — this is a caveat, not a refusal — but the
        // curve says the null was simulated over more area than the domain.
        assertEquals("NON_RECTANGULAR_DOMAIN", curve.status());
        assertFalse(curve.isOk());
        assertFalse(Double.isNaN(curve.scalars().get("Global p").doubleValue()));
    }

    @Test
    public void oneUncomputableDirectionDoesNotLoseTheOthers() {
        // The whole reason a refusal is carried rather than thrown. Three channels
        // give six ordered pairs; the two that cannot be measured must not take
        // the four that can with them.
        EngineInputs three = SpatialFixtures.inputs(
                SpatialFixtures.labels("A", SIZE, SIZE, SpatialFixtures.grid(4, 4, 10, 5, 5)),
                SpatialFixtures.labels("B", SIZE, SIZE, SpatialFixtures.grid(4, 4, 10, 8, 5)),
                SpatialFixtures.labels("C", SIZE, SIZE, new int[][] {{30, 30}}));

        EngineResult result = new CrossKEngine(EdgeCorrection.TRANSLATION, SIMULATIONS,
                SEED, RADII).compute(three, EngineProgress.SILENT);

        assertEquals(6, result.directions().size());
        int refused = 0;
        int measured = 0;
        for (DirectionKey direction : result.directions()) {
            CurveSeries curve = result.curve(direction);
            assertNotNull(direction.label(), curve);
            if (curve.isOk()) {
                measured++;
                assertFalse(Double.isNaN(curve.scalars().get("Global p").doubleValue()));
            } else {
                refused++;
                assertEquals("INSUFFICIENT_POINTS", curve.status());
            }
        }
        assertEquals(4, refused);
        assertEquals(2, measured);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static List<ColocEngine> defaults() {
        return Arrays.<ColocEngine>asList(
                new CrossGEngine(),
                new CrossKEngine(),
                new CrossLEngine(),
                new CrossPairCorrelationEngine());
    }

    /** The same four, sized so a whole test class runs in a second. */
    private static List<ColocEngine> fast() {
        return Arrays.<ColocEngine>asList(
                new CrossGEngine(SIMULATIONS, SEED, RADII),
                new CrossKEngine(EdgeCorrection.TRANSLATION, SIMULATIONS, SEED, RADII),
                new CrossLEngine(EdgeCorrection.TRANSLATION, SIMULATIONS, SEED, RADII),
                new CrossPairCorrelationEngine(EdgeCorrection.TRANSLATION, SIMULATIONS,
                        SEED, RADII));
    }

    private static EngineInputs pair() {
        return SpatialFixtures.inputs(
                SpatialFixtures.labels("A", SIZE, SIZE, SpatialFixtures.grid(3, 3, 12, 8, 8)),
                SpatialFixtures.labels("B", SIZE, SIZE, SpatialFixtures.grid(3, 3, 12, 11, 8)));
    }

    /** Unequal object counts, so a swapped direction cannot pass by coincidence. */
    private static EngineInputs unevenPair() {
        return SpatialFixtures.inputs(
                SpatialFixtures.labels("A", SIZE, SIZE, SpatialFixtures.grid(3, 3, 12, 8, 8)),
                SpatialFixtures.labels("B", SIZE, SIZE, SpatialFixtures.grid(4, 4, 9, 10, 10)));
    }
}
