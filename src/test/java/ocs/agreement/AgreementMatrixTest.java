package ocs.agreement;

import ocs.engine.ColocEngine;
import ocs.engine.ColumnSpec;
import ocs.engine.CurveSeries;
import ocs.engine.DirectionKey;
import ocs.engine.EngineFamily;
import ocs.engine.EngineInputs;
import ocs.engine.EngineProgress;
import ocs.engine.EngineResult;
import ocs.engine.InputRequirement;
import ocs.engine.ObjectScore;
import ocs.engine.ScaleKind;
import ocs.nullmodel.NullModelResult;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The matrix: which tiers fire, which are refused, and what each cell says. */
public class AgreementMatrixTest {

    private static final DirectionKey A_TO_B = new DirectionKey(0, "A", 1, "B");

    // ---------- tier gating ----------

    @Test
    public void twoEnginesOnTheSameScaleGetAllThreeTiers() {
        List<AgreementCell> cells = AgreementMatrix.withMinimumN(2).overObjects(
                Arrays.asList(result("jaccard-dice", 0.2, 0.8, 0.9),
                        result("volume-overlap", 0.3, 0.7, 0.95)),
                engines(ScaleKind.FRACTION, ScaleKind.FRACTION));

        assertEquals(3, cells.size());
        assertEquals(Tier.OBJECT_FLAG, cells.get(0).tier());
        assertEquals(Tier.OBJECT_RANK, cells.get(1).tier());
        assertEquals(Tier.OBJECT_VALUE, cells.get(2).tier());
        for (int i = 0; i < cells.size(); i++) {
            assertTrue(cells.get(i).toString(), cells.get(i).wasComputed());
        }
    }

    @Test
    public void incommensurableEnginesGetTiersOneAndTwoAndAnExplicitRefusal() {
        // A percentage against a correlation. Tiers 1 and 2 are still valid — a
        // boolean is always commensurable and rank order is scale-free — but
        // running Lin's CCC across them would produce a confident number
        // measuring nothing, which is the failure this gate exists to stop.
        List<AgreementCell> cells = AgreementMatrix.withMinimumN(2).overObjects(
                Arrays.asList(result("volume-overlap", 10.0, 40.0, 90.0),
                        result("per-object-intensity", 0.1, 0.4, 0.9)),
                engines(ScaleKind.PERCENT, ScaleKind.CORRELATION));

        assertEquals(3, cells.size());
        assertTrue(cells.get(0).wasComputed());
        assertTrue(cells.get(1).wasComputed());

        AgreementCell refused = cells.get(2);
        assertEquals(Tier.OBJECT_VALUE, refused.tier());
        assertFalse(refused.wasComputed());
        // The reason names both scales, so a reader can see why rather than
        // being told a cell is empty.
        assertTrue(refused.notComputedReason(), refused.notComputedReason()
                .contains(ScaleKind.PERCENT.displayName()));
        assertTrue(refused.notComputedReason(), refused.notComputedReason()
                .contains(ScaleKind.CORRELATION.displayName()));
    }

    @Test
    public void twoCurveEnginesAreRefusedOutrightRatherThanComparedAtZero() {
        // Both declare CURVE, which is never tier-3 eligible even against
        // itself. And neither produces object scores, so the object tiers have
        // nothing to pair at all.
        List<AgreementCell> cells = AgreementMatrix.withDefaults().overObjects(
                Arrays.asList(curveResult("cross-k"), curveResult("cross-l")),
                engines(ScaleKind.CURVE, ScaleKind.CURVE));

        assertEquals(1, cells.size());
        assertFalse(cells.get(0).wasComputed());
        assertTrue(cells.get(0).notComputedReason(),
                cells.get(0).notComputedReason().contains("tier V"));
    }

    @Test
    public void aRefusedPairStillProducesACellRatherThanVanishing() {
        // A matrix rendered from present cells only makes "excluded" and
        // "compared and found nothing" look identical, and the first is
        // information the reader needs.
        List<AgreementCell> cells = AgreementMatrix.withDefaults().overObjects(
                Arrays.asList(curveResult("cross-k"), result("cpc", 1.0, 0.0)),
                engines(ScaleKind.CURVE, ScaleKind.BINARY));
        assertEquals(1, cells.size());
        assertNotNull(cells.get(0).notComputedReason());
    }

    // ---------- pairing ----------

    @Test
    public void objectsAreMatchedByLabelNotByListPosition() {
        // Engine B has dropped object 2 as unmeasurable. Position matching would
        // slide B's object 3 up against A's object 2 — quietly, with every
        // statistic still looking plausible.
        //
        // The fixture is built so the two pairings give different answers.
        // Rewritten after a mutation check: the first version had objects 2 and
        // 3 carrying the same flag and similar values, so position-matching and
        // label-matching produced the identical n AND the identical raw
        // agreement, and the test passed against a deliberately broken pairing.
        EngineResult a = EngineResult.forEngine("a")
                .direction(A_TO_B, Arrays.asList(
                        new ObjectScore(1, 1, 0.10, false),
                        new ObjectScore(2, 2, 0.90, true),
                        new ObjectScore(3, 3, 0.20, false)))
                .build();
        EngineResult b = EngineResult.forEngine("b")
                .direction(A_TO_B, Arrays.asList(
                        new ObjectScore(1, 1, 0.11, false),
                        new ObjectScore(3, 3, 0.21, false)))
                .build();

        List<AgreementCell> cells = AgreementMatrix.withMinimumN(2)
                .overObjects(Arrays.asList(a, b),
                        engines(ScaleKind.FRACTION, ScaleKind.FRACTION));

        assertEquals("only objects 1 and 3 are shared", 2, cells.get(0).n());
        // Label-matched: (0.10,0.11) and (0.20,0.21), both false-false, so the
        // methods agree twice. Position-matched it would pair A's object 2
        // (true) against B's object 3 (false) and report 0.5.
        assertEquals(1.0, cells.get(0).statistic(AgreementMatrix.RAW_AGREEMENT), 1e-12);

        // Stronger, because it reads the values rather than the flags: with the
        // right pairing every difference is -0.01. Position-matching would pair
        // 0.90 against 0.21 and put the bias near +0.34.
        AgreementCell direct = cells.get(2);
        assertEquals(Tier.OBJECT_VALUE, direct.tier());
        assertEquals(-0.01, direct.statistic(AgreementMatrix.BIAS), 1e-12);
    }

    @Test
    public void theTwoPrevalencesAreNotInterchangeable() {
        // Prevalence A is how often the *first* method said yes. Transposing the
        // two is undetectable on any fixture where both methods have the same
        // rate — which is most of them, and was all of them here until a
        // mutation check swapped the pair and left the class green.
        //
        // Same shape of bug as the containment engine's two percentages in
        // stage 4: two same-typed numbers, adjacent, in the reverse of each
        // other's role.
        EngineResult a = EngineResult.forEngine("a")
                .direction(A_TO_B, Arrays.asList(
                        new ObjectScore(1, 1, 0.9, true),
                        new ObjectScore(2, 2, 0.9, true),
                        new ObjectScore(3, 3, 0.9, true),
                        new ObjectScore(4, 4, 0.1, false)))
                .build();
        EngineResult b = EngineResult.forEngine("b")
                .direction(A_TO_B, Arrays.asList(
                        new ObjectScore(1, 1, 0.9, true),
                        new ObjectScore(2, 2, 0.1, false),
                        new ObjectScore(3, 3, 0.1, false),
                        new ObjectScore(4, 4, 0.1, false)))
                .build();

        List<AgreementCell> cells = AgreementMatrix.withMinimumN(2)
                .overObjects(Arrays.asList(a, b),
                        engines(ScaleKind.FRACTION, ScaleKind.FRACTION));

        AgreementCell flags = cells.get(0);
        assertEquals("a called 3 of 4 coincident",
                0.75, flags.statistic(AgreementMatrix.PREVALENCE_A), 1e-12);
        assertEquals("b called 1 of 4 coincident",
                0.25, flags.statistic(AgreementMatrix.PREVALENCE_B), 1e-12);
    }

    @Test
    public void anObjectOneMethodCouldNotMeasureIsDroppedFromThatPairOnly() {
        EngineResult a = EngineResult.forEngine("a")
                .direction(A_TO_B, Arrays.asList(
                        new ObjectScore(1, 1, 0.10, false),
                        new ObjectScore(2, 2, Double.NaN, false),
                        new ObjectScore(3, 3, 0.90, true)))
                .build();
        EngineResult b = EngineResult.forEngine("b")
                .direction(A_TO_B, Arrays.asList(
                        new ObjectScore(1, 1, 0.11, false),
                        new ObjectScore(2, 2, 0.55, true),
                        new ObjectScore(3, 3, 0.91, true)))
                .build();

        List<AgreementCell> cells = AgreementMatrix.withMinimumN(2)
                .overObjects(Arrays.asList(a, b),
                        engines(ScaleKind.FRACTION, ScaleKind.FRACTION));
        assertEquals("the unmeasurable object leaves this comparison", 2,
                cells.get(0).n());
    }

    @Test
    public void eachDirectionIsComparedSeparatelyAndNeverPooled() {
        // A→B and B→A are different questions, and pooling averages away the
        // asymmetry that object colocalization mostly consists of.
        DirectionKey bToA = new DirectionKey(1, "B", 0, "A");
        EngineResult a = EngineResult.forEngine("a")
                .direction(A_TO_B, Arrays.asList(new ObjectScore(1, 1, 0.1, false)))
                .direction(bToA, Arrays.asList(new ObjectScore(1, 1, 0.9, true)))
                .build();
        EngineResult b = EngineResult.forEngine("b")
                .direction(A_TO_B, Arrays.asList(new ObjectScore(1, 1, 0.2, false)))
                .direction(bToA, Arrays.asList(new ObjectScore(1, 1, 0.8, true)))
                .build();

        List<AgreementCell> cells = AgreementMatrix.withMinimumN(1)
                .overObjects(Arrays.asList(a, b),
                        engines(ScaleKind.FRACTION, ScaleKind.FRACTION));

        int aToBCells = 0;
        int bToACells = 0;
        for (int i = 0; i < cells.size(); i++) {
            if (A_TO_B.equals(cells.get(i).direction())) {
                aToBCells++;
            } else if (bToA.equals(cells.get(i).direction())) {
                bToACells++;
            }
        }
        assertEquals(3, aToBCells);
        assertEquals(3, bToACells);
    }

    // ---------- reporting ----------

    @Test
    public void aThinCellIsMarkedUnderpoweredRatherThanDropped() {
        // A kappa on 3 objects and a kappa on 500 are both kappas and must not
        // read as equal in authority — but dropping the thin one hides that the
        // comparison was attempted at all.
        List<AgreementCell> cells = AgreementMatrix.withMinimumN(30).overObjects(
                Arrays.asList(result("a", 0.1, 0.5, 0.9), result("b", 0.2, 0.6, 0.8)),
                engines(ScaleKind.FRACTION, ScaleKind.FRACTION));
        assertEquals(3, cells.get(0).n());
        assertTrue(cells.get(0).isUnderpowered());
        assertTrue(cells.get(0).wasComputed());
    }

    @Test
    public void aSymmetricPairIsFlaggedSoDuplicationIsNotReadAsCorroboration() {
        List<AgreementCell> symmetric = AgreementMatrix.withMinimumN(1).overObjects(
                Arrays.asList(result("a", 0.1, 0.9), result("b", 0.2, 0.8)),
                engines(ScaleKind.FRACTION, ScaleKind.FRACTION, true, true));
        assertTrue(symmetric.get(0).isDuplicatedBySymmetry());

        List<AgreementCell> mixed = AgreementMatrix.withMinimumN(1).overObjects(
                Arrays.asList(result("a", 0.1, 0.9), result("b", 0.2, 0.8)),
                engines(ScaleKind.FRACTION, ScaleKind.FRACTION, true, false));
        assertFalse(mixed.get(0).isDuplicatedBySymmetry());
    }

    @Test
    public void everyCellNamesTheTierThatProducedIt() {
        // The point of one cell per tier: a tier-1 kappa and a tier-V kappa are
        // both kappas on the same range, and there is no way to read either
        // without having read which is which.
        List<AgreementCell> cells = AgreementMatrix.withMinimumN(1).overObjects(
                Arrays.asList(result("a", 0.1, 0.9), result("b", 0.2, 0.8)),
                engines(ScaleKind.FRACTION, ScaleKind.FRACTION));
        for (int i = 0; i < cells.size(); i++) {
            assertNotNull(cells.get(i).tier());
            assertFalse(cells.get(i).tier().label().isEmpty());
        }
    }

    @Test
    public void askingACellForAStatisticItDoesNotCarryThrows() {
        // Rather than returning zero, which enters a table as a real measurement
        // of perfect disagreement.
        List<AgreementCell> cells = AgreementMatrix.withMinimumN(1).overObjects(
                Arrays.asList(result("a", 0.1, 0.9), result("b", 0.2, 0.8)),
                engines(ScaleKind.FRACTION, ScaleKind.FRACTION));
        try {
            cells.get(0).statistic(AgreementMatrix.CONCORDANCE);
            fail("a tier-1 cell has no CCC");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    // ---------- tier V ----------

    @Test
    public void verdictAgreementComparesConclusionsAcrossABatch() {
        List<VerdictAgreement.Run> runs = new ArrayList<VerdictAgreement.Run>();
        // Four images. cross-k and volume-overlap concur on three, differ on one.
        Verdict[] crossK = {Verdict.ENRICHED, Verdict.ENRICHED,
                Verdict.NOT_SIGNIFICANT, Verdict.NOT_SIGNIFICANT};
        Verdict[] overlap = {Verdict.ENRICHED, Verdict.ENRICHED,
                Verdict.NOT_SIGNIFICANT, Verdict.ENRICHED};
        for (int i = 0; i < 4; i++) {
            Map<String, Verdict> verdicts = new LinkedHashMap<String, Verdict>();
            verdicts.put("cross-k", crossK[i]);
            verdicts.put("volume-overlap", overlap[i]);
            runs.add(new VerdictAgreement.Run("image" + i, verdicts));
        }

        List<AgreementCell> cells = VerdictAgreement.withMinimumRuns(2).across(runs);
        assertEquals(1, cells.size());
        AgreementCell cell = cells.get(0);
        assertEquals(Tier.VERDICT, cell.tier());
        assertEquals(4, cell.n());
        assertEquals(0.75, cell.statistic(VerdictAgreement.RAW_AGREEMENT), 1e-12);
        assertNull("tier V is a batch statistic, not a per-direction one",
                cell.direction());
    }

    @Test
    public void aSingleImageCannotSupportVerdictAgreement() {
        // With n = 1 every pair agrees or disagrees completely and kappa is
        // undefined. Reported as such rather than as perfect agreement.
        Map<String, Verdict> verdicts = new LinkedHashMap<String, Verdict>();
        verdicts.put("cross-k", Verdict.ENRICHED);
        verdicts.put("volume-overlap", Verdict.ENRICHED);
        List<AgreementCell> cells = VerdictAgreement.withDefaults()
                .across(Arrays.asList(new VerdictAgreement.Run("one", verdicts)));

        assertEquals(1, cells.size());
        assertFalse(cells.get(0).wasComputed());
        assertTrue(cells.get(0).notComputedReason(),
                cells.get(0).notComputedReason().contains("batch"));
    }

    @Test
    public void runsWhereEitherMethodWasNotApplicableAreDropped() {
        // Counting "not applicable" as a category would let two methods that
        // both failed on the same images score as agreeing.
        List<VerdictAgreement.Run> runs = new ArrayList<VerdictAgreement.Run>();
        Verdict[] a = {Verdict.NOT_APPLICABLE, Verdict.ENRICHED, Verdict.ENRICHED};
        Verdict[] b = {Verdict.NOT_APPLICABLE, Verdict.ENRICHED, Verdict.DEPLETED};
        for (int i = 0; i < 3; i++) {
            Map<String, Verdict> verdicts = new LinkedHashMap<String, Verdict>();
            verdicts.put("a", a[i]);
            verdicts.put("b", b[i]);
            runs.add(new VerdictAgreement.Run("image" + i, verdicts));
        }
        List<AgreementCell> cells = VerdictAgreement.withMinimumRuns(2).across(runs);
        assertEquals("the doubly-inapplicable run must not count as agreement",
                2, cells.get(0).n());
        assertEquals(0.5, cells.get(0).statistic(VerdictAgreement.RAW_AGREEMENT), 1e-12);
    }

    // ---------- verdicts ----------

    @Test
    public void aNullModelVerdictIsTwoSidedAndKnowsItsDirection() {
        // Enrichment: observed well above everything the shuffles produced.
        double[] low = new double[99];
        NullModelResult enriched = NullModelResult.of("e", A_TO_B, 40.0, low, 1L);
        assertEquals(Verdict.ENRICHED, Verdict.from(enriched, 0.05));

        // Depletion: observed below everything. A one-sided upper-tail test
        // would call this "not significant" and lose a real finding — two
        // structures that avoid each other.
        double[] high = new double[99];
        Arrays.fill(high, 20.0);
        NullModelResult depleted = NullModelResult.of("e", A_TO_B, 1.0, high, 1L);
        assertEquals(Verdict.DEPLETED, Verdict.from(depleted, 0.05));
    }

    @Test
    public void anOrdinaryObservationIsNotSignificantInEitherDirection() {
        double[] spread = new double[99];
        for (int i = 0; i < spread.length; i++) {
            spread[i] = i;
        }
        NullModelResult middling = NullModelResult.of("e", A_TO_B, 49.0, spread, 1L);
        assertEquals(Verdict.NOT_SIGNIFICANT, Verdict.from(middling, 0.05));
    }

    @Test
    public void aSkippedNullModelYieldsNotApplicableRatherThanNotSignificant() {
        // "We could not test this" and "we tested this and found nothing" are
        // different statements and must not collapse into one.
        NullModelResult skipped = NullModelResult.skipped("e", A_TO_B, 4.0,
                NullModelResult.Skip.CARRIES_ITS_OWN_ENVELOPE);
        assertEquals(Verdict.NOT_APPLICABLE, Verdict.from(skipped, 0.05));
    }

    @Test
    public void aCurveVerdictReadsItsGlobalPAndTheSignOfItsDeviation() {
        CurveSeries clustered = CurveSeries.over("Radius", "um", new double[] {1, 2})
                .series("Observed", new double[] {5.0, 9.0})
                .scalar("Global p", 0.01)
                .scalar("Max Deviation", 3.2)
                .build();
        assertEquals(Verdict.ENRICHED,
                Verdict.from(clustered, 0.05, "Global p", "Max Deviation"));

        CurveSeries segregated = CurveSeries.over("Radius", "um", new double[] {1, 2})
                .series("Observed", new double[] {0.1, 0.2})
                .scalar("Global p", 0.01)
                .scalar("Max Deviation", -2.8)
                .build();
        assertEquals(Verdict.DEPLETED,
                Verdict.from(segregated, 0.05, "Global p", "Max Deviation"));
    }

    @Test
    public void aCurveThatCouldNotBeComputedIsNotApplicable() {
        CurveSeries broken = CurveSeries.over("Radius", "um", new double[] {1, 2})
                .series("Observed", new double[] {Double.NaN, Double.NaN})
                .status("INSUFFICIENT_POINTS")
                .build();
        assertEquals(Verdict.NOT_APPLICABLE,
                Verdict.from(broken, 0.05, "Global p", "Max Deviation"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void anAlphaOutsideZeroToOneIsRejected() {
        Verdict.from((CurveSeries) null, 1.5, "Global p", "Max Deviation");
    }

    // ---------- helpers ----------

    private static EngineResult result(String id, double... values) {
        List<ObjectScore> scores = new ArrayList<ObjectScore>();
        for (int i = 0; i < values.length; i++) {
            scores.add(new ObjectScore(i + 1, i + 1, values[i], values[i] >= 0.5));
        }
        return EngineResult.forEngine(id).direction(A_TO_B, scores).build();
    }

    private static EngineResult curveResult(String id) {
        return EngineResult.forEngine(id)
                .direction(A_TO_B, new ArrayList<ObjectScore>())
                .curve(A_TO_B, CurveSeries.over("Radius", "um", new double[] {1, 2})
                        .series("Observed", new double[] {1.0, 2.0})
                        .build())
                .build();
    }

    private static Map<String, ColocEngine> engines(ScaleKind first, ScaleKind second) {
        return engines(first, second, false, false);
    }

    private static Map<String, ColocEngine> engines(ScaleKind first, ScaleKind second,
            boolean firstSymmetric, boolean secondSymmetric) {
        Map<String, ColocEngine> engines = new LinkedHashMap<String, ColocEngine>();
        String[] ids = {"jaccard-dice", "volume-overlap", "per-object-intensity",
                "cross-k", "cross-l", "cpc", "a", "b"};
        for (int i = 0; i < ids.length; i++) {
            // Two scales in play; every id resolves to one of them so a test can
            // name whichever pair it needs.
            boolean isSecond = i % 2 == 1;
            engines.put(ids[i], new StubEngine(ids[i],
                    isSecond ? second : first,
                    isSecond ? secondSymmetric : firstSymmetric));
        }
        return engines;
    }

    /** Declares a scale and a symmetry, and is never actually run. */
    private static final class StubEngine implements ColocEngine {
        private final String id;
        private final ScaleKind scale;
        private final boolean symmetric;

        private StubEngine(String id, ScaleKind scale, boolean symmetric) {
            this.id = id;
            this.scale = scale;
            this.symmetric = symmetric;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String displayName() {
            return id;
        }

        @Override
        public EngineFamily family() {
            return EngineFamily.OBJECT;
        }

        @Override
        public Set<InputRequirement> requires() {
            return EnumSet.of(InputRequirement.LABEL_IMAGES);
        }

        @Override
        public List<ColumnSpec> columns() {
            return Arrays.asList(ColumnSpec.primary("Value", "", "stub", scale));
        }

        @Override
        public boolean isSymmetric() {
            return symmetric;
        }

        @Override
        public double relativeCost() {
            return 1.0;
        }

        @Override
        public EngineResult compute(EngineInputs inputs, EngineProgress progress) {
            throw new UnsupportedOperationException("stub");
        }
    }
}
