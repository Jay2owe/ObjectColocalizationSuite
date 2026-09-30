package ocs.engine;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The three result shapes, and the checks that make the two new ones safe.
 *
 * <p>Each shape is here because a real engine needed it and could not be written
 * honestly without it: supporting columns because a containment engine's two
 * percentages had nowhere to go, whole-direction scalars because whole-image
 * Pearson is one number per channel pair, curves because cross-K is a vector
 * over radii with no per-object value at all.
 */
public class EngineResultShapeTest {

    private static final DirectionKey A_TO_B = new DirectionKey(0, "A", 1, "B");
    private static final DirectionKey B_TO_A = new DirectionKey(1, "B", 0, "A");

    private static final List<ObjectScore> TWO_OBJECTS = Arrays.asList(
            new ObjectScore(1, 7, 0.25, false),
            new ObjectScore(2, 9, 0.80, true));

    // ---------- supporting columns ----------

    @Test
    public void aSupportingColumnRoundTripsByName() {
        EngineResult result = EngineResult.forEngine("containment")
                .direction(A_TO_B, TWO_OBJECTS)
                .supporting(A_TO_B, "Source Inside Partner %", new double[] {4.0, 100.0})
                .supporting(A_TO_B, "Partner Inside Source %", new double[] {100.0, 4.0})
                .build();

        // The whole point of naming rather than numbering: these two columns are
        // the same type, the same length and the reverse of each other, which is
        // exactly the pair a positional contract would let an engine transpose
        // with every assertion still passing.
        assertArrayEquals(new double[] {4.0, 100.0},
                result.supporting(A_TO_B, "Source Inside Partner %"), 1e-12);
        assertArrayEquals(new double[] {100.0, 4.0},
                result.supporting(A_TO_B, "Partner Inside Source %"), 1e-12);
        assertEquals(Arrays.asList("Source Inside Partner %", "Partner Inside Source %"),
                result.supportingNames(A_TO_B));
    }

    @Test(expected = IllegalArgumentException.class)
    public void aSupportingColumnShorterThanItsScoresIsRejected() {
        // Would not throw on its own. It would silently shift every object after
        // the gap onto the wrong row for the rest of the table.
        EngineResult.forEngine("containment")
                .direction(A_TO_B, TWO_OBJECTS)
                .supporting(A_TO_B, "Partner", new double[] {7.0});
    }

    @Test(expected = IllegalStateException.class)
    public void aColumnReportedBeforeItsDirectionIsRejected() {
        EngineResult.forEngine("containment")
                .supporting(A_TO_B, "Partner", new double[] {7.0, 9.0});
    }

    @Test(expected = IllegalStateException.class)
    public void theSameColumnTwiceIsRejected() {
        EngineResult.forEngine("containment")
                .direction(A_TO_B, TWO_OBJECTS)
                .supporting(A_TO_B, "Partner", new double[] {7.0, 9.0})
                .supporting(A_TO_B, "Partner", new double[] {1.0, 2.0});
    }

    @Test(expected = IllegalArgumentException.class)
    public void askingForAColumnTheEngineNeverReportedThrows() {
        EngineResult.forEngine("containment")
                .direction(A_TO_B, TWO_OBJECTS)
                .supporting(A_TO_B, "Partner", new double[] {7.0, 9.0})
                .build()
                .supporting(A_TO_B, "Partner Count");
    }

    @Test
    public void aDirectionWithNoSupportingColumnsReturnsEmptyRatherThanThrowing() {
        EngineResult result = EngineResult.forEngine("cpc")
                .direction(A_TO_B, TWO_OBJECTS)
                .build();
        assertEquals(0, result.supporting(A_TO_B, "anything at all").length);
        assertTrue(result.supportingNames(A_TO_B).isEmpty());
    }

    @Test
    public void supportingValuesAreCopiedNotShared() {
        double[] mutable = {4.0, 100.0};
        EngineResult result = EngineResult.forEngine("containment")
                .direction(A_TO_B, TWO_OBJECTS)
                .supporting(A_TO_B, "Inside %", mutable)
                .build();
        mutable[0] = 999.0;
        result.supporting(A_TO_B, "Inside %")[1] = 999.0;

        // The null-model layer reuses one result across hundreds of permutations;
        // a shared array would let one permutation corrupt every later one.
        assertArrayEquals(new double[] {4.0, 100.0},
                result.supporting(A_TO_B, "Inside %"), 1e-12);
    }

    // ---------- whole-direction scalars ----------

    @Test
    public void aWholeImageMeasureIsDistinguishableFromAnEmptyResult() {
        List<ObjectScore> none = Arrays.asList();

        EngineResult wholeImage = EngineResult.forEngine("whole-image-intensity")
                .direction(A_TO_B, none)
                .wholeDirection(A_TO_B, "Pearson r", 0.82)
                .build();
        EngineResult foundNothing = EngineResult.forEngine("cpc")
                .direction(A_TO_B, none)
                .build();

        // Both have an empty score list. Only one of them means "not applicable";
        // the other means "ran, found no objects", and Discovery must not treat
        // those alike.
        assertTrue(wholeImage.isWholeDirection());
        assertFalse(wholeImage.hasObjectScores());
        assertEquals(0.82, wholeImage.wholeDirection(A_TO_B).get("Pearson r").doubleValue(), 1e-12);

        assertFalse(foundNothing.isWholeDirection());
        assertFalse(foundNothing.hasObjectScores());
        assertTrue(foundNothing.wholeDirection(A_TO_B).isEmpty());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void wholeDirectionValuesAreNotModifiableAfterBuild() {
        EngineResult.forEngine("whole-image-intensity")
                .direction(A_TO_B, Arrays.<ObjectScore>asList())
                .wholeDirection(A_TO_B, "Pearson r", 0.82)
                .build()
                .wholeDirection(A_TO_B)
                .put("Pearson r", Double.valueOf(0.0));
    }

    // ---------- curves ----------

    @Test
    public void aCurveCarriesParallelVectorsAndItsScalars() {
        double[] radii = {1.0, 2.0, 4.0};
        CurveSeries crossK = CurveSeries.over("Radius", "µm", radii)
                .series("Observed", new double[] {3.1, 12.4, 49.0})
                .series("Expected", new double[] {3.0, 12.0, 48.0})
                .series("Lower", new double[] {2.4, 10.1, 41.0})
                .series("Upper", new double[] {3.7, 14.2, 55.0})
                .scalar("Global p", 0.02)
                .scalar("Seed", 20260422.0)
                .build();

        EngineResult result = EngineResult.forEngine("cross-k")
                .direction(A_TO_B, Arrays.<ObjectScore>asList())
                .curve(A_TO_B, crossK)
                .build();

        assertTrue(result.hasCurves());
        assertFalse(result.hasObjectScores());
        CurveSeries back = result.curve(A_TO_B);
        assertEquals(3, back.length());
        assertArrayEquals(radii, back.x(), 1e-12);
        assertArrayEquals(new double[] {3.1, 12.4, 49.0}, back.series("Observed"), 1e-12);
        assertEquals(0.02, back.scalars().get("Global p").doubleValue(), 1e-12);
        assertTrue(back.isOk());
        assertNull("a direction with no curve reports none", result.curve(B_TO_A));
    }

    @Test(expected = IllegalArgumentException.class)
    public void aSeriesOfTheWrongLengthIsRejected() {
        CurveSeries.over("Radius", "µm", new double[] {1.0, 2.0, 4.0})
                .series("Observed", new double[] {3.1, 12.4});
    }

    @Test(expected = IllegalArgumentException.class)
    public void anAxisThatDoesNotIncreaseIsRejected() {
        // Unsorted radii plot as a curve that doubles back, which reads as a
        // finding rather than as a bug.
        CurveSeries.over("Radius", "µm", new double[] {1.0, 4.0, 2.0});
    }

    @Test(expected = IllegalArgumentException.class)
    public void askingForASeriesTheCurveDoesNotCarryThrows() {
        CurveSeries.over("Radius", "µm", new double[] {1.0, 2.0})
                .series("Observed", new double[] {3.1, 12.4})
                .build()
                .series("Expected");
    }

    @Test
    public void aCurveCanReportWhyItIsNotTrustworthy() {
        CurveSeries curve = CurveSeries.over("Radius", "µm", new double[] {1.0, 2.0})
                .series("Observed", new double[] {Double.NaN, Double.NaN})
                .status("INSUFFICIENT_POINTS")
                .build();

        // Carried rather than thrown: a run over five channel pairs must report
        // the one pair that could not be computed, not lose the four that could.
        assertFalse(curve.isOk());
        assertEquals("INSUFFICIENT_POINTS", curve.status());
    }

    // ---------- the shapes stay independent ----------

    @Test
    public void aPlainPerObjectEngineDeclaresNeitherNewShape() {
        EngineResult result = EngineResult.forEngine("volume-overlap")
                .direction(A_TO_B, TWO_OBJECTS)
                .direction(B_TO_A, TWO_OBJECTS)
                .build();

        assertTrue(result.hasObjectScores());
        assertFalse(result.isWholeDirection());
        assertFalse(result.hasCurves());
        assertEquals(2, result.directions().size());
        assertArrayEquals(new double[] {0.25, 0.80}, result.values(A_TO_B), 1e-12);
        assertEquals(1, result.coincidentCount(A_TO_B));
    }
}
