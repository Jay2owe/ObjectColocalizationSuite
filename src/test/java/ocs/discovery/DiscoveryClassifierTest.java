package ocs.discovery;

import ocs.agreement.AgreementCell;
import ocs.agreement.AgreementMatrix;
import ocs.agreement.Tier;
import ocs.agreement.Verdict;
import ocs.agreement.VerdictAgreement;
import ocs.engine.ColocEngine;
import ocs.engine.ColumnSpec;
import ocs.engine.DirectionKey;
import ocs.engine.EngineFamily;
import ocs.engine.EngineInputs;
import ocs.engine.EngineProgress;
import ocs.engine.EngineResult;
import ocs.engine.InputRequirement;
import ocs.engine.ObjectScore;
import ocs.engine.ScaleKind;
import ocs.nullmodel.NullModelResult;
import ocs.sweep.ThresholdSweep;
import org.junit.Test;

import ij.ImagePlus;
import ij.process.ShortProcessor;

import java.lang.reflect.Field;
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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The five classes, the order the rules fire in, and the two things Discovery
 * must refuse to do: rank, and assert without evidence.
 */
public class DiscoveryClassifierTest {

    private static final DirectionKey A_TO_B = new DirectionKey(0, "A", 1, "B");
    private static final DirectionKey B_TO_A = new DirectionKey(1, "B", 0, "A");

    // ---------- the five classes ----------

    @Test
    public void aClearStableWellAgreeingMethodIsUsable() {
        List<DiscoveryResult> results = classify(
                MethodEvidence.of("volume-overlap", A_TO_B, Verdict.ENRICHED, 120, 0.02),
                agreeingWith("volume-overlap", "per-object-intensity", 0.8));
        assertEquals(DiscoveryClass.USABLE, results.get(0).discoveryClass());
    }

    @Test
    public void aMethodThatDidNotClearTheNullIsUninformativeNotBroken() {
        List<DiscoveryResult> results = classify(
                MethodEvidence.of("cpc", A_TO_B, Verdict.NOT_SIGNIFICANT, 120, 0.0),
                noAgreement());
        DiscoveryResult result = results.get(0);
        assertEquals(DiscoveryClass.UNINFORMATIVE_HERE, result.discoveryClass());
        // The wording matters. A user reading "failed" next to a method will
        // stop using it; the method has worked correctly and found nothing.
        assertTrue(result.evidence().toString(),
                joined(result).contains("working, not failing"));
        assertFalse(joined(result).toLowerCase().contains("failed"));
    }

    @Test
    public void aMethodWhoseAnswerMovesWithItsThresholdIsFragile() {
        List<DiscoveryResult> results = classify(
                MethodEvidence.of("containment", A_TO_B, Verdict.ENRICHED, 120, 0.35),
                noAgreement());
        DiscoveryResult result = results.get(0);
        assertEquals(DiscoveryClass.FRAGILE, result.discoveryClass());
        assertTrue(joined(result), joined(result).contains("35%"));
    }

    @Test
    public void aMethodAgreeingWithNothingInAnotherFamilyIsDivergent() {
        // distance-tolerance is OBJECT; per-object-intensity is INTENSITY.
        // Their kappa is below the limit, and it is the only cross-family
        // comparison available.
        List<DiscoveryResult> results = classify(
                MethodEvidence.of("distance-tolerance", A_TO_B, Verdict.ENRICHED, 120, 0.01),
                agreeingWith("distance-tolerance", "per-object-intensity", 0.05));
        DiscoveryResult result = results.get(0);

        assertEquals(DiscoveryClass.DIVERGENT, result.discoveryClass());
        assertEquals(Arrays.asList(EngineFamily.INTENSITY.displayName()),
                result.divergentFrom());
        // Flagged, never demoted: the report must say outright that being the
        // only method seeing the effect looks exactly like this.
        assertTrue(joined(result), joined(result).contains("only one here capturing"));
    }

    @Test
    public void aMethodWithTooFewObjectsIsNotApplicableRatherThanUsable() {
        List<DiscoveryResult> results = classify(
                MethodEvidence.of("jaccard-dice", A_TO_B, Verdict.ENRICHED, 7, 0.0),
                noAgreement());
        DiscoveryResult result = results.get(0);
        assertEquals(DiscoveryClass.NOT_APPLICABLE, result.discoveryClass());
        assertTrue(joined(result), joined(result).contains("7 objects"));
    }

    @Test
    public void aMethodWhoseInputsWereAbsentSaysSo() {
        List<DiscoveryResult> results = classify(
                MethodEvidence.unavailable("per-object-intensity", A_TO_B,
                        "no raw intensity images were loaded"),
                noAgreement());
        assertEquals(DiscoveryClass.NOT_APPLICABLE, results.get(0).discoveryClass());
        assertTrue(joined(results.get(0)).contains("no raw intensity images"));
    }

    // ---------- rule order ----------

    @Test
    public void anUninformativeResultIsNotAlsoCalledFragile() {
        // Both conditions hold: it did not clear the null AND its threshold
        // moves the answer. Reporting Fragile would describe how an
        // uninformative answer wobbles, which is not a finding about anything.
        List<DiscoveryResult> results = classify(
                MethodEvidence.of("containment", A_TO_B, Verdict.NOT_SIGNIFICANT, 120, 0.9),
                noAgreement());
        assertEquals(DiscoveryClass.UNINFORMATIVE_HERE, results.get(0).discoveryClass());
    }

    @Test
    public void aFragileMethodIsNotAlsoCalledDivergent() {
        // A method whose own answer is unstable cannot support the stronger
        // claim that every other family is wrong.
        List<DiscoveryResult> results = classify(
                MethodEvidence.of("distance-tolerance", A_TO_B, Verdict.ENRICHED, 120, 0.6),
                agreeingWith("distance-tolerance", "per-object-intensity", 0.01));
        assertEquals(DiscoveryClass.FRAGILE, results.get(0).discoveryClass());
        assertTrue("a fragile method must not carry a divergence claim",
                results.get(0).divergentFrom().isEmpty());
    }

    @Test
    public void tooFewObjectsBeatsEveryOtherRule() {
        List<DiscoveryResult> results = classify(
                MethodEvidence.of("containment", A_TO_B, Verdict.NOT_SIGNIFICANT, 2, 0.9),
                noAgreement());
        assertEquals(DiscoveryClass.NOT_APPLICABLE, results.get(0).discoveryClass());
    }

    // ---------- divergence is against other families only ----------

    @Test
    public void disagreeingWithItsOwnFamilyIsNotDivergence() {
        // volume-overlap and jaccard-dice are both OBJECT and near-restatements
        // of each other. If they disagree that is worth knowing, but it is not
        // the claim "this method sees something no other kind of method does".
        Map<String, ColocEngine> engines = engines();
        List<AgreementCell> cells = new ArrayList<AgreementCell>();
        cells.add(cell("volume-overlap", "jaccard-dice", A_TO_B, 0.01));
        cells.add(cell("volume-overlap", "per-object-intensity", A_TO_B, 0.9));

        List<DiscoveryResult> results = DiscoveryClassifier.withDefaults().classify(
                Arrays.asList(MethodEvidence.of("volume-overlap", A_TO_B,
                        Verdict.ENRICHED, 120, 0.0)), engines, cells);
        assertEquals(DiscoveryClass.USABLE, results.get(0).discoveryClass());
    }

    @Test
    public void agreeingWithOneMethodInAFamilyIsEnoughToNotDiverge() {
        Map<String, ColocEngine> engines = engines();
        List<AgreementCell> cells = new ArrayList<AgreementCell>();
        cells.add(cell("distance-tolerance", "per-object-intensity", A_TO_B, 0.02));
        cells.add(cell("distance-tolerance", "whole-image-intensity", A_TO_B, 0.85));

        List<DiscoveryResult> results = DiscoveryClassifier.withDefaults().classify(
                Arrays.asList(MethodEvidence.of("distance-tolerance", A_TO_B,
                        Verdict.ENRICHED, 120, 0.0)), engines, cells);
        assertEquals(DiscoveryClass.USABLE, results.get(0).discoveryClass());
    }

    @Test
    public void aFamilyThatCouldNotBeComparedIsNotCountedAsDisagreement() {
        // "We could not compare" and "we compared and they disagreed" are
        // different findings, and only the second is evidence of divergence.
        Map<String, ColocEngine> engines = engines();
        List<AgreementCell> cells = new ArrayList<AgreementCell>();
        cells.add(AgreementCell.notComputed(Tier.OBJECT_FLAG, "distance-tolerance",
                "per-object-intensity", A_TO_B,
                "no object was scored by both methods"));

        List<DiscoveryResult> results = DiscoveryClassifier.withDefaults().classify(
                Arrays.asList(MethodEvidence.of("distance-tolerance", A_TO_B,
                        Verdict.ENRICHED, 120, 0.0)), engines, cells);
        assertEquals(DiscoveryClass.USABLE, results.get(0).discoveryClass());
        assertTrue(results.get(0).divergentFrom().isEmpty());
    }

    @Test
    public void agreementInOneDirectionSaysNothingAboutTheOther() {
        // A→B and B→A are different questions. Reading a cell from one direction
        // as evidence about the other would let a method be called divergent —
        // or spared that label — on a comparison that was never made.
        //
        // Added after a mutation check: dropping the direction filter left every
        // other test green, because they all used a single direction throughout.
        Map<String, ColocEngine> engines = engines();
        List<AgreementCell> cells = new ArrayList<AgreementCell>();
        // Strong agreement, but only in the A→B direction.
        cells.add(cell("distance-tolerance", "per-object-intensity", A_TO_B, 0.95));

        List<DiscoveryResult> results = DiscoveryClassifier.withDefaults().classify(
                Arrays.asList(MethodEvidence.of("distance-tolerance", B_TO_A,
                        Verdict.ENRICHED, 120, 0.0)), engines, cells);

        // No comparison exists for B→A, so divergence cannot be assessed and
        // must not be claimed — but neither may the A→B agreement be borrowed.
        assertEquals(DiscoveryClass.USABLE, results.get(0).discoveryClass());
        assertTrue(results.get(0).divergentFrom().isEmpty());

        // And the mirror: a genuine B→A disagreement must be seen.
        List<AgreementCell> mirrored = new ArrayList<AgreementCell>();
        mirrored.add(cell("distance-tolerance", "per-object-intensity", A_TO_B, 0.95));
        mirrored.add(cell("distance-tolerance", "per-object-intensity", B_TO_A, 0.01));
        List<DiscoveryResult> divergent = DiscoveryClassifier.withDefaults().classify(
                Arrays.asList(MethodEvidence.of("distance-tolerance", B_TO_A,
                        Verdict.ENRICHED, 120, 0.0)), engines, mirrored);
        assertEquals("the A->B agreement must not rescue B->A",
                DiscoveryClass.DIVERGENT, divergent.get(0).discoveryClass());
    }

    @Test
    public void onlyTheFlagAndVerdictTiersCountTowardDivergence() {
        // Tiers 2 and 3 answer different questions — rank order, and closeness
        // of the values themselves — and neither is the yes/no agreement
        // divergence is defined on. The tier filter is what enforces that, and a
        // mutation check showed it was doing nothing detectable: tier-2 cells
        // happen not to carry a statistic named "Cohen's Kappa", so the
        // statistic lookup was quietly covering for it.
        Map<String, ColocEngine> engines = engines();
        Map<String, Double> statistics = new LinkedHashMap<String, Double>();
        statistics.put(AgreementMatrix.KAPPA, Double.valueOf(0.01));
        statistics.put(AgreementMatrix.SPEARMAN, Double.valueOf(0.01));

        List<AgreementCell> cells = new ArrayList<AgreementCell>();
        cells.add(AgreementCell.of(Tier.OBJECT_RANK, "distance-tolerance",
                "per-object-intensity", A_TO_B, 120, statistics, false, false));

        List<DiscoveryResult> results = DiscoveryClassifier.withDefaults().classify(
                Arrays.asList(MethodEvidence.of("distance-tolerance", A_TO_B,
                        Verdict.ENRICHED, 120, 0.0)), engines, cells);
        assertEquals("a rank-tier cell must not decide divergence",
                DiscoveryClass.USABLE, results.get(0).discoveryClass());
    }

    @Test
    public void anUncomputedCellCarriesNoStatisticsToBeMisread() {
        // The invariant Discovery leans on when it skips uncomputed cells. Pinned
        // here because a mutation check showed the skip was redundant with the
        // statistic lookup — if a future uncomputed cell ever carried a number,
        // "could not compare" would silently start counting as "disagreed".
        AgreementCell uncomputed = AgreementCell.notComputed(Tier.OBJECT_FLAG,
                "a", "b", A_TO_B, "no object was scored by both methods");
        assertFalse(uncomputed.wasComputed());
        assertTrue("an uncomputed cell must hold no statistics",
                uncomputed.statistics().isEmpty());
        assertEquals(0, uncomputed.n());
    }

    @Test
    public void withNoAgreementAtAllNothingCanBeCalledDivergent() {
        List<DiscoveryResult> results = classify(
                MethodEvidence.of("distance-tolerance", A_TO_B, Verdict.ENRICHED, 120, 0.0),
                noAgreement());
        assertEquals(DiscoveryClass.USABLE, results.get(0).discoveryClass());
    }

    @Test
    public void aCurveEngineDivergesViaItsVerdictCells() {
        // The whole reason tier V exists: cross-k produces no object scores, so
        // it has no tier-1 cell with anything. Without the verdict route it
        // could never be assessed for divergence at all.
        Map<String, ColocEngine> engines = engines();
        List<AgreementCell> cells = new ArrayList<AgreementCell>();
        Map<String, Double> statistics = new LinkedHashMap<String, Double>();
        statistics.put(VerdictAgreement.KAPPA, Double.valueOf(0.05));
        statistics.put(VerdictAgreement.RAW_AGREEMENT, Double.valueOf(0.3));
        cells.add(AgreementCell.of(Tier.VERDICT, "cross-k", "volume-overlap",
                null, 20, statistics, false, false));

        List<DiscoveryResult> results = DiscoveryClassifier.withDefaults().classify(
                Arrays.asList(MethodEvidence.of("cross-k", A_TO_B,
                        Verdict.ENRICHED, 120, Double.NaN)), engines, cells);
        assertEquals(DiscoveryClass.DIVERGENT, results.get(0).discoveryClass());
        assertEquals(Arrays.asList(EngineFamily.OBJECT.displayName()),
                results.get(0).divergentFrom());
    }

    // ---------- methods with no threshold ----------

    @Test
    public void aMethodWithNoThresholdIsNeverCalledFragile() {
        // Centroid coincidence is purely geometric. NaN must not compare as
        // "above the limit", and it must not be reported as a stable 0% either.
        List<DiscoveryResult> results = classify(
                MethodEvidence.of("cpc", A_TO_B, Verdict.ENRICHED, 120, Double.NaN),
                noAgreement());
        DiscoveryResult result = results.get(0);
        assertEquals(DiscoveryClass.USABLE, result.discoveryClass());
        assertTrue(joined(result), joined(result).contains("no threshold"));
        assertFalse("must not claim a measured stability it never measured",
                joined(result).contains("0%"));
    }

    // ---------- what Discovery must refuse to do ----------

    @Test
    public void thereIsNoScoreOrRankToSortOn() {
        // Structural, not stylistic. The moment a number exists it will be
        // sorted on, cited, and read as an authority nobody agreed to give it.
        for (Field field : DiscoveryResult.class.getDeclaredFields()) {
            String name = field.getName().toLowerCase();
            assertFalse("DiscoveryResult must carry no '" + field.getName() + "'",
                    name.contains("score") || name.contains("rank")
                            || name.contains("weight"));
        }
        for (java.lang.reflect.Method method : DiscoveryResult.class.getMethods()) {
            String name = method.getName().toLowerCase();
            assertFalse("DiscoveryResult must expose no '" + method.getName() + "'",
                    name.contains("score") || name.contains("rank"));
        }
    }

    @Test
    public void resultsComeBackInInputOrderNotGroupedByClass() {
        // Any ordering would be read as a ranking, and there is not one.
        List<MethodEvidence> evidence = Arrays.asList(
                MethodEvidence.of("a-usable", A_TO_B, Verdict.ENRICHED, 120, 0.0),
                MethodEvidence.of("b-uninformative", A_TO_B, Verdict.NOT_SIGNIFICANT, 120, 0.0),
                MethodEvidence.of("c-usable", A_TO_B, Verdict.ENRICHED, 120, 0.0));
        List<DiscoveryResult> results = DiscoveryClassifier.withDefaults()
                .classify(evidence, engines(), noAgreement());

        assertEquals("a-usable", results.get(0).engineId());
        assertEquals("b-uninformative", results.get(1).engineId());
        assertEquals("c-usable", results.get(2).engineId());
    }

    @Test
    public void everyResultCarriesEvidenceForItsClass() {
        // A class with no evidence is an opinion. Whatever the verdict, the user
        // must be able to see what produced it and disagree with the threshold
        // rather than with the tool.
        List<MethodEvidence> evidence = Arrays.asList(
                MethodEvidence.of("a", A_TO_B, Verdict.ENRICHED, 120, 0.0),
                MethodEvidence.of("b", A_TO_B, Verdict.NOT_SIGNIFICANT, 120, 0.0),
                MethodEvidence.of("c", A_TO_B, Verdict.ENRICHED, 120, 0.9),
                MethodEvidence.of("d", A_TO_B, Verdict.ENRICHED, 2, 0.0),
                MethodEvidence.unavailable("e", A_TO_B, "no intensity images"));
        List<DiscoveryResult> results = DiscoveryClassifier.withDefaults()
                .classify(evidence, engines(), noAgreement());

        for (int i = 0; i < results.size(); i++) {
            assertFalse(results.get(i).engineId() + " has no evidence",
                    results.get(i).evidence().isEmpty());
            assertNotNull(results.get(i).discoveryClass().meaning());
        }
    }

    @Test
    public void everyDirectionIsClassifiedSeparately() {
        // A→B and B→A are different questions and a method can be usable one way
        // and uninformative the other.
        List<MethodEvidence> evidence = Arrays.asList(
                MethodEvidence.of("volume-overlap", A_TO_B, Verdict.ENRICHED, 120, 0.0),
                MethodEvidence.of("volume-overlap", B_TO_A, Verdict.NOT_SIGNIFICANT, 120, 0.0));
        List<DiscoveryResult> results = DiscoveryClassifier.withDefaults()
                .classify(evidence, engines(), noAgreement());

        assertEquals(DiscoveryClass.USABLE, results.get(0).discoveryClass());
        assertEquals(DiscoveryClass.UNINFORMATIVE_HERE, results.get(1).discoveryClass());
    }

    // ---------- parameters, not constants ----------

    @Test
    public void theThresholdsAreParametersAUserCanDisagreeWith() {
        MethodEvidence borderline =
                MethodEvidence.of("containment", A_TO_B, Verdict.ENRICHED, 40, 0.15);

        assertEquals(DiscoveryClass.FRAGILE, DiscoveryClassifier.withDefaults()
                .classify(Arrays.asList(borderline), engines(), noAgreement())
                .get(0).discoveryClass());
        assertEquals(DiscoveryClass.USABLE, DiscoveryClassifier.with(0.20, 30, 0.40)
                .classify(Arrays.asList(borderline), engines(), noAgreement())
                .get(0).discoveryClass());
        assertEquals(DiscoveryClass.NOT_APPLICABLE, DiscoveryClassifier.with(0.20, 50, 0.40)
                .classify(Arrays.asList(borderline), engines(), noAgreement())
                .get(0).discoveryClass());
    }

    @Test
    public void nonsenseParametersAreRejected() {
        try {
            DiscoveryClassifier.with(1.5, 30, 0.4);
            fail("a flip limit above 1 is not a proportion of objects");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            DiscoveryClassifier.with(0.1, 30, 2.0);
            fail("kappa lies on [-1, 1]");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void anUnavailableMethodMustGiveAReason() {
        MethodEvidence.unavailable("e", A_TO_B, "  ");
    }

    // ---------- gathering evidence from a real run ----------

    @Test
    public void evidenceGatheredFromANullModelCarriesItsVerdict() {
        double[] low = new double[99];
        NullModelResult enriched = NullModelResult.of("volume-overlap", A_TO_B, 45.0, low, 1L);
        List<MethodEvidence> evidence = MethodEvidence.gather(
                Arrays.asList(enriched), null, 0.05);

        assertEquals(1, evidence.size());
        assertEquals(Verdict.ENRICHED, evidence.get(0).verdict());
        assertEquals(45, evidence.get(0).objects());
        assertFalse("no sweep was supplied, so there is no flip fraction",
                evidence.get(0).hasThreshold());
    }

    @Test
    public void aSkippedNullModelGathersAsNotApplicableNotAsNotSignificant() {
        NullModelResult skipped = NullModelResult.skipped("cross-k", A_TO_B, 4.0,
                NullModelResult.Skip.CARRIES_ITS_OWN_ENVELOPE);
        List<MethodEvidence> evidence = MethodEvidence.gather(
                Arrays.asList(skipped), null, 0.05);
        assertEquals(Verdict.NOT_APPLICABLE, evidence.get(0).verdict());
    }

    @Test
    public void aSweepListThatSaysNothingAboutThisMethodLeavesItWithoutAFlipFraction() {
        // The failure this rules out: a flip fraction of 0 where the truth is
        // "never swept". Zero reads as "swept, and perfectly stable", which is
        // exactly the evidence that lets a method be called Usable — so a method
        // could be certified stable on a sweep that was never run on it.
        ThresholdSweep.Result somebodyElse = ThresholdSweep.of(
                stub("cpc", EngineFamily.OBJECT), oneObjectEach(), null,
                EngineProgress.SILENT);
        assertEquals("fixture: the sweep must be about a different method",
                "cpc", somebodyElse.engineId());

        NullModelResult overlap = NullModelResult.of(
                "volume-overlap", A_TO_B, 45.0, new double[99], 1L);
        List<MethodEvidence> evidence = MethodEvidence.gather(
                Arrays.asList(overlap), Arrays.asList(somebodyElse), 0.05);

        assertFalse("a sweep of another method is not evidence about this one",
                evidence.get(0).hasThreshold());
        assertTrue(Double.isNaN(evidence.get(0).flipFraction()));
    }

    // ---------- helpers ----------

    /** The smallest thing a sweep will accept; its content is never measured. */
    private static EngineInputs oneObjectEach() {
        return EngineInputs.builder(Arrays.asList(
                oneObject("A"), oneObject("B"))).build();
    }

    private static ImagePlus oneObject(String title) {
        ShortProcessor processor = new ShortProcessor(8, 8);
        processor.set(2, 2, 1);
        return new ImagePlus(title, processor);
    }

    private List<DiscoveryResult> classify(MethodEvidence evidence,
            List<AgreementCell> cells) {
        return DiscoveryClassifier.withDefaults()
                .classify(Arrays.asList(evidence), engines(), cells);
    }

    private static String joined(DiscoveryResult result) {
        return result.evidence().toString();
    }

    private static List<AgreementCell> noAgreement() {
        return new ArrayList<AgreementCell>();
    }

    private static List<AgreementCell> agreeingWith(String a, String b, double kappa) {
        return Arrays.asList(cell(a, b, A_TO_B, kappa));
    }

    private static AgreementCell cell(String a, String b, DirectionKey direction,
            double kappa) {
        Map<String, Double> statistics = new LinkedHashMap<String, Double>();
        statistics.put(AgreementMatrix.KAPPA, Double.valueOf(kappa));
        statistics.put(AgreementMatrix.RAW_AGREEMENT, Double.valueOf(0.5));
        return AgreementCell.of(Tier.OBJECT_FLAG, a, b, direction, 120,
                statistics, false, false);
    }

    private static Map<String, ColocEngine> engines() {
        Map<String, ColocEngine> engines = new LinkedHashMap<String, ColocEngine>();
        engines.put("volume-overlap", stub("volume-overlap", EngineFamily.OBJECT));
        engines.put("jaccard-dice", stub("jaccard-dice", EngineFamily.OBJECT));
        engines.put("containment", stub("containment", EngineFamily.OBJECT));
        engines.put("distance-tolerance", stub("distance-tolerance", EngineFamily.OBJECT));
        engines.put("cpc", stub("cpc", EngineFamily.OBJECT));
        engines.put("per-object-intensity", stub("per-object-intensity", EngineFamily.INTENSITY));
        engines.put("whole-image-intensity", stub("whole-image-intensity", EngineFamily.INTENSITY));
        engines.put("cross-k", stub("cross-k", EngineFamily.SPATIAL));
        engines.put("territory-occupancy", stub("territory-occupancy", EngineFamily.TERRITORY));
        for (String id : new String[] {"a", "b", "c", "d", "e",
                "a-usable", "b-uninformative", "c-usable"}) {
            engines.put(id, stub(id, EngineFamily.OBJECT));
        }
        return engines;
    }

    private static ColocEngine stub(final String id, final EngineFamily family) {
        return new ColocEngine() {
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
                return family;
            }

            @Override
            public Set<InputRequirement> requires() {
                return EnumSet.of(InputRequirement.LABEL_IMAGES);
            }

            @Override
            public List<ColumnSpec> columns() {
                return Arrays.asList(ColumnSpec.primary("Value", "", "stub",
                        ScaleKind.FRACTION));
            }

            @Override
            public boolean isSymmetric() {
                return false;
            }

            @Override
            public double relativeCost() {
                return 1.0;
            }

            @Override
            public EngineResult compute(EngineInputs inputs, EngineProgress progress) {
                return EngineResult.forEngine(id)
                        .direction(A_TO_B, new ArrayList<ObjectScore>()).build();
            }
        };
    }
}
