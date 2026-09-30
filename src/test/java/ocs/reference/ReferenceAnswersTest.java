package ocs.reference;

import ocs.OCS;
import ocs.OCSParameters;
import ocs.OCSResult;
import ocs.engine.DirectionKey;
import ocs.engine.EngineResult;
import ocs.engine.ObjectScore;
import ocs.engine.object.ContainmentEngine;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The reference dataset run through the whole suite, checked against answers
 * worked out on paper from {@link ReferenceDataset}'s geometry.
 *
 * <p>Everything goes through {@link OCS#run}, not through the engines directly.
 * That is the point of this file: the engines already have their own tests, and
 * what those cannot see is the layer above them — which channel became the
 * source, which the target, whether an adapter's idea of a direction survived
 * being handed to the run layer and back. A transposed pair produces a table
 * that is entirely plausible unless something knows the right answer.
 *
 * <p>Tolerance is {@value #EXACT} throughout, because every expected value here
 * is a ratio of small integers and the arithmetic that produces it is a handful
 * of operations on exactly-representable doubles. A tolerance loose enough to
 * hide a real difference would defeat the file.
 */
public class ReferenceAnswersTest {

    /**
     * These are integer ratios computed in a few operations. Anything needing
     * more slack than this has a difference, not a rounding.
     */
    private static final double EXACT = 1.0e-12;

    /** Every geometric method, named rather than taken from a preset. */
    private static final String[] GEOMETRY = {
            "volume-overlap", "jaccard-dice", "containment", "bounding-box",
            "cpc", "distance-tolerance"};

    @BeforeClass
    public static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    // ==================================================================
    // Case: identical — every method must report its maximum
    // ==================================================================

    /**
     * When channel B is a copy of channel A, agreement is not a statistical
     * question: every method must report perfect coincidence for every object in
     * both directions. A method that cannot recognise a copy of the input has
     * nothing useful to say about anything harder.
     */
    @Test
    public void aCopyOfTheInputIsPerfectByEveryMethodInBothDirections() {
        OCSResult result = run(ReferenceDataset.identical(), GEOMETRY);

        for (int source = 0; source <= 1; source++) {
            int target = 1 - source;
            String where = "direction " + source + "->" + target;

            // Object 1 is 27 voxels (3·3·3), object 2 is 75 (5·5·3). Copied, so
            // each object's partner is the same label in the other channel.
            EngineResult overlap = engine(result, "volume-overlap");
            assertEquals(where, 100.0, valueOf(overlap, source, target, 1), EXACT);
            assertEquals(where, 100.0, valueOf(overlap, source, target, 2), EXACT);
            assertEquals(where, 1, partnerOf(overlap, source, target, 1));
            assertEquals(where, 2, partnerOf(overlap, source, target, 2));

            // Jaccard = 27/27 and 75/75; Dice is the same at the top of the range.
            EngineResult jaccard = engine(result, "jaccard-dice");
            assertEquals(where, 1.0, valueOf(jaccard, source, target, 1), EXACT);
            assertEquals(where, 1.0, valueOf(jaccard, source, target, 2), EXACT);
            assertEquals(where, 1.0,
                    supportingOf(jaccard, source, target, "Dice", 1), EXACT);
            assertEquals(where, 27.0,
                    supportingOf(jaccard, source, target, "Intersection Voxels", 1), EXACT);
            assertEquals(where, 27.0,
                    supportingOf(jaccard, source, target, "Union Voxels", 1), EXACT);
            assertEquals(where, 75.0,
                    supportingOf(jaccard, source, target, "Intersection Voxels", 2), EXACT);

            // Each object overlaps exactly one target object — its own copy —
            // and every one of its voxels is shared with it.
            assertEquals(where, 1.0,
                    supportingOf(overlap, source, target, "Overlap Partner Count", 1), EXACT);
            assertEquals(where, 27.0,
                    supportingOf(overlap, source, target, "Overlap Voxels", 1), EXACT);
            assertEquals(where, 75.0,
                    supportingOf(overlap, source, target, "Overlap Voxels", 2), EXACT);

            // Each object is 100% inside its partner and its partner 100% inside
            // it, which is the containment class, not merely a high percentage.
            EngineResult containment = engine(result, "containment");
            assertEquals(where, 1.0, valueOf(containment, source, target, 1), EXACT);
            assertEquals(where, 100.0,
                    supportingOf(containment, source, target, "Source Inside Partner", 1), EXACT);
            assertEquals(where, 100.0,
                    supportingOf(containment, source, target, "Partner Inside Source", 1), EXACT);
            assertEquals(where + ": mutual containment is reported from the source's"
                            + " end, so it reads as 'source inside target'",
                    ContainmentEngine.ContainmentClass.SOURCE_INSIDE_TARGET.ordinal(),
                    supportingOf(containment, source, target, "Containment Class", 1),
                    EXACT);

            // Identical objects have identical boxes, and the box is the cuboid.
            EngineResult box = engine(result, "bounding-box");
            assertEquals(where, 100.0, valueOf(box, source, target, 1), EXACT);
            assertEquals(where, 100.0, valueOf(box, source, target, 2), EXACT);
            assertEquals(where, 27.0,
                    supportingOf(box, source, target, "BBox Volume", 1), EXACT);
            assertEquals(where, 75.0,
                    supportingOf(box, source, target, "BBox Volume", 2), EXACT);

            // A centroid necessarily lands inside its own copy.
            EngineResult cpc = engine(result, "cpc");
            assertEquals(where, 1.0, valueOf(cpc, source, target, 1), EXACT);
            assertEquals(where, 1, partnerOf(cpc, source, target, 1));
            assertEquals(where, 2, partnerOf(cpc, source, target, 2));

            // Same centroid, so zero distance — not merely within tolerance.
            EngineResult distance = engine(result, "distance-tolerance");
            assertEquals(where, 0.0, valueOf(distance, source, target, 1), EXACT);
            assertEquals(where, 0.0, valueOf(distance, source, target, 2), EXACT);
        }
    }

    // ==================================================================
    // Case: disjoint — every method must report its floor
    // ==================================================================

    /**
     * The mirror of the case above. Nothing is shared, so every overlap-derived
     * method reports zero <i>and no partner</i>; the second half matters more,
     * because an engine that reports the nearest object as a partner while
     * scoring it zero produces a table where a reader following the partner
     * column sees a relationship that does not exist.
     */
    @Test
    public void nothingSharedMeansZeroAndNoPartner() {
        OCSResult result = run(ReferenceDataset.disjoint(), GEOMETRY);

        for (int source = 0; source <= 1; source++) {
            int target = 1 - source;
            String where = "direction " + source + "->" + target;

            for (int label = 1; label <= 2; label++) {
                assertEquals(where, 0.0,
                        valueOf(engine(result, "volume-overlap"), source, target, label), EXACT);
                assertEquals(where, 0.0,
                        valueOf(engine(result, "jaccard-dice"), source, target, label), EXACT);
                assertEquals(where, 0.0,
                        valueOf(engine(result, "containment"), source, target, label), EXACT);
                assertEquals(where, 0.0,
                        valueOf(engine(result, "bounding-box"), source, target, label), EXACT);
                assertEquals(where, 0.0,
                        valueOf(engine(result, "cpc"), source, target, label), EXACT);

                assertEquals("no partner, " + where, ObjectScore.NO_PARTNER,
                        partnerOf(engine(result, "volume-overlap"), source, target, label));
                assertEquals("no partner, " + where, ObjectScore.NO_PARTNER,
                        partnerOf(engine(result, "cpc"), source, target, label));
                assertEquals(where + ": no target object is overlapped at all",
                        0.0, supportingOf(engine(result, "volume-overlap"),
                                source, target, "Overlap Partner Count", label), EXACT);
                assertEquals(where,
                        ContainmentEngine.ContainmentClass.DISJOINT.ordinal(),
                        supportingOf(engine(result, "containment"), source, target,
                                "Containment Class", label), EXACT);

                assertFalse(where,
                        scoreOf(engine(result, "volume-overlap"), source, target, label)
                                .isCoincident());
            }

            // The exception, and the reason this engine exists: a distance is
            // defined between objects that share nothing. A's object 1 sits at
            // (3, 3, 2) and B's at (17, 3, 2) — fourteen voxels apart along x,
            // and the other object in the channel is further, so this is the
            // nearest. Far outside the 5-voxel default tolerance, so the answer
            // is "14, and that is a no".
            EngineResult distance = engine(result, "distance-tolerance");
            assertEquals(where, 14.0, valueOf(distance, source, target, 1), EXACT);
            assertEquals(where, 14.0, valueOf(distance, source, target, 2), EXACT);
            assertFalse(where,
                    scoreOf(distance, source, target, 1).isCoincident());
            assertEquals(where, 1, partnerOf(distance, source, target, 1));
            assertEquals(where, 2, partnerOf(distance, source, target, 2));
        }
    }

    // ==================================================================
    // Case: nested — the direction test
    // ==================================================================

    /**
     * A 27-voxel cube inside a 343-voxel one. Read one way the answer is 100%,
     * read the other it is 7.87%, and the two are not close enough to confuse.
     *
     * <p>This is the case that catches a source/target transposition. Every
     * symmetric fixture passes with the directions swapped — that is what
     * symmetric means — so a swap can only be caught by a case that is
     * deliberately lopsided, and the more lopsided the better.
     */
    @Test
    public void aSmallObjectInsideALargeOneReadsDifferentlyEachWay() {
        OCSResult result = run(ReferenceDataset.nested(), GEOMETRY);

        // 27/343 = 0.078717201166180758…, as a percentage 7.8717201166180758.
        double smallInLarge = 100.0 * 27.0 / 343.0;

        EngineResult overlap = engine(result, "volume-overlap");
        assertEquals("all 27 of the small cube's voxels are in the large one",
                100.0, valueOf(overlap, 0, 1, 1), EXACT);
        assertEquals("but they are only 27 of the large cube's 343",
                smallInLarge, valueOf(overlap, 1, 0, 1), EXACT);
        assertEquals(27.0, supportingOf(overlap, 0, 1, "Overlap Voxels", 1), EXACT);
        assertEquals(27.0, supportingOf(overlap, 1, 0, "Overlap Voxels", 1), EXACT);

        // The bounding boxes are the cubes themselves, so the box measure lands
        // on the same two numbers. Different code path, same geometry — a
        // disagreement here would mean one of them mis-handles the z extent.
        EngineResult box = engine(result, "bounding-box");
        assertEquals(100.0, valueOf(box, 0, 1, 1), EXACT);
        assertEquals(smallInLarge, valueOf(box, 1, 0, 1), EXACT);

        // Jaccard is symmetric by definition: the union is the large cube either
        // way, so both directions give 27/343. If this ever differs between the
        // directions, the union is being computed from the wrong denominator.
        EngineResult jaccard = engine(result, "jaccard-dice");
        assertEquals(27.0 / 343.0, valueOf(jaccard, 0, 1, 1), EXACT);
        assertEquals(27.0 / 343.0, valueOf(jaccard, 1, 0, 1), EXACT);
        // Dice = 2·27 / (27 + 343) = 54/370.
        assertEquals(54.0 / 370.0, supportingOf(jaccard, 0, 1, "Dice", 1), EXACT);
        assertEquals(343.0, supportingOf(jaccard, 0, 1, "Union Voxels", 1), EXACT);

        // Containment names which way round it is, and the two supporting
        // percentages swap with the direction while the verdict does not.
        EngineResult containment = engine(result, "containment");
        assertEquals("contained either way round", 1.0, valueOf(containment, 0, 1, 1), EXACT);
        assertEquals("contained either way round", 1.0, valueOf(containment, 1, 0, 1), EXACT);
        assertEquals(100.0,
                supportingOf(containment, 0, 1, "Source Inside Partner", 1), EXACT);
        assertEquals(smallInLarge,
                supportingOf(containment, 0, 1, "Partner Inside Source", 1), EXACT);
        assertEquals(smallInLarge,
                supportingOf(containment, 1, 0, "Source Inside Partner", 1), EXACT);
        assertEquals(100.0,
                supportingOf(containment, 1, 0, "Partner Inside Source", 1), EXACT);

        // The verdict is the same either way; the class is not, and the class is
        // where the direction survives. An adapter that transposed the direction
        // would leave the 1.0 above intact and flip only these two.
        assertEquals(ContainmentEngine.ContainmentClass.SOURCE_INSIDE_TARGET.ordinal(),
                supportingOf(containment, 0, 1, "Containment Class", 1), EXACT);
        assertEquals(ContainmentEngine.ContainmentClass.TARGET_INSIDE_SOURCE.ordinal(),
                supportingOf(containment, 1, 0, "Containment Class", 1), EXACT);

        // The boxes are the cubes, so their volumes are the cubes' volumes.
        assertEquals(27.0, supportingOf(box, 0, 1, "BBox Volume", 1), EXACT);
        assertEquals(343.0, supportingOf(box, 1, 0, "BBox Volume", 1), EXACT);

        // Concentric, so both centroid measures say yes from both sides. This is
        // the case where centroid coincidence and overlap agree despite the
        // 12-fold size difference, and it is worth pinning that they do.
        assertEquals(1.0, valueOf(engine(result, "cpc"), 0, 1, 1), EXACT);
        assertEquals(1.0, valueOf(engine(result, "cpc"), 1, 0, 1), EXACT);
        assertEquals(0.0, valueOf(engine(result, "distance-tolerance"), 0, 1, 1), EXACT);
    }

    /**
     * The same asymmetry read as a ratio: three engines derive their percentage
     * from the source object's own size, so all three must be off by exactly the
     * volume ratio 343/27 and not merely in the same direction.
     */
    @Test
    public void theThreeSizeNormalisedMethodsShareOneAsymmetryRatio() {
        OCSResult result = run(ReferenceDataset.nested(), GEOMETRY);
        double expected = 343.0 / 27.0;

        String[] sizeNormalised = {"volume-overlap", "bounding-box"};
        for (int i = 0; i < sizeNormalised.length; i++) {
            EngineResult engine = engine(result, sizeNormalised[i]);
            assertEquals(sizeNormalised[i],
                    expected,
                    valueOf(engine, 0, 1, 1) / valueOf(engine, 1, 0, 1), EXACT);
        }

        EngineResult containment = engine(result, "containment");
        assertEquals("containment's own percentage carries the same ratio",
                expected,
                supportingOf(containment, 0, 1, "Source Inside Partner", 1)
                        / supportingOf(containment, 1, 0, "Source Inside Partner", 1),
                EXACT);
    }

    // ==================================================================
    // Case: partial — the disagreement test
    // ==================================================================

    /**
     * Two cuboids sharing two of their five columns. Overlap says 40%, centroid
     * coincidence says no, and centroid distance says yes — all three correct,
     * about the same pair of objects.
     *
     * <p>This is the suite's reason for existing, reduced to a fixture. A user
     * running one method here gets one of three defensible answers and no way to
     * know the other two exist.
     */
    @Test
    public void thePartialCaseIsWhereTheMethodsLegitimatelyDisagree() {
        OCSResult result = run(ReferenceDataset.partial(), GEOMETRY);

        for (int source = 0; source <= 1; source++) {
            int target = 1 - source;
            String where = "direction " + source + "->" + target;

            // 30 shared voxels of 75: symmetric here because the objects are the
            // same size, which is why this case cannot substitute for `nested`.
            assertEquals(where, 40.0,
                    valueOf(engine(result, "volume-overlap"), source, target, 1), EXACT);
            assertEquals(where, 30.0,
                    supportingOf(engine(result, "volume-overlap"), source, target,
                            "Overlap Voxels", 1), EXACT);

            // Jaccard = 30 / (75 + 75 − 30) = 30/120; Dice = 60/150.
            assertEquals(where, 0.25,
                    valueOf(engine(result, "jaccard-dice"), source, target, 1), EXACT);
            assertEquals(where, 0.4,
                    supportingOf(engine(result, "jaccard-dice"), source, target, "Dice", 1),
                    EXACT);

            // 40% is nowhere near the 98% containment default, so this is
            // "partial", which is not a containment relationship.
            assertEquals(where, 0.0,
                    valueOf(engine(result, "containment"), source, target, 1), EXACT);
            assertEquals(where, 40.0,
                    supportingOf(engine(result, "containment"), source, target,
                            "Source Inside Partner", 1), EXACT);
            assertEquals(where + ": sharing voxels without enclosure is 'partial',"
                            + " which is a different answer from 'not contained'",
                    ContainmentEngine.ContainmentClass.PARTIAL.ordinal(),
                    supportingOf(engine(result, "containment"), source, target,
                            "Containment Class", 1), EXACT);

            // The boxes are the objects, so the box measure matches the voxel one.
            assertEquals(where, 40.0,
                    valueOf(engine(result, "bounding-box"), source, target, 1), EXACT);
            assertEquals(where, 75.0,
                    supportingOf(engine(result, "bounding-box"), source, target,
                            "BBox Volume", 1), EXACT);

            // Centroids at (6, 6, 2) and (9, 6, 2): each is three voxels outside
            // the other object, whose x range stops at 8 and starts at 7.
            assertEquals(where, 0.0,
                    valueOf(engine(result, "cpc"), source, target, 1), EXACT);
            assertEquals(where, ObjectScore.NO_PARTNER,
                    partnerOf(engine(result, "cpc"), source, target, 1));

            // Three voxels apart, inside the 5-voxel default tolerance — so this
            // engine says yes where centroid coincidence says no, on the same
            // centroids. Both are reporting exactly what they measure.
            EngineResult distance = engine(result, "distance-tolerance");
            assertEquals(where, 3.0, valueOf(distance, source, target, 1), EXACT);
            assertTrue(where, scoreOf(distance, source, target, 1).isCoincident());
        }
    }

    // ==================================================================
    // Case: correlated — the intensity family through the run layer
    // ==================================================================

    /**
     * Inside object 1 the second channel is {@code 2A + 6} and inside object 2 it
     * is {@code 250 − 2A}. Both are exactly linear, so Pearson's r is exactly +1
     * and exactly −1 — no tolerance argument to have, and no plausible middle
     * value for a mis-paired channel to hide in.
     */
    @Test
    public void perObjectCorrelationIsExactlyPlusAndMinusOne() {
        OCSResult result = run(ReferenceDataset.correlated(), "per-object-intensity");

        for (int source = 0; source <= 1; source++) {
            int target = 1 - source;
            String where = "direction " + source + "->" + target;
            EngineResult intensity = engine(result, "per-object-intensity");

            assertEquals(where, 1.0, valueOf(intensity, source, target, 1), EXACT);
            assertEquals(where, -1.0, valueOf(intensity, source, target, 2), EXACT);

            // Both objects are far above the three-voxel floor, so neither sign
            // can be an artefact of too little data to correlate.
            assertEquals(where, 27.0,
                    supportingOf(intensity, source, target, "Object Voxels", 1), EXACT);
            assertEquals(where, 75.0,
                    supportingOf(intensity, source, target, "Object Voxels", 2), EXACT);
        }
    }

    /**
     * The field-wide correlation on the same case is a mixture of the two
     * relationships, so what is asserted is what the geometry forces rather than
     * a number nobody derived: it sits strictly inside the two per-object values,
     * and on the positive side, because 75 inverted voxels cannot outvote 4,533
     * linear ones.
     */
    @Test
    public void theWholeFieldCorrelationSitsBetweenTheTwoObjectValues() {
        OCSResult result = run(ReferenceDataset.correlated(),
                "whole-image-intensity");
        EngineResult intensity = engine(result, "whole-image-intensity");

        Double pearson = intensity.wholeDirection(direction(intensity, 0, 1))
                .get("Pearson r");
        if (pearson == null) {
            fail("the whole-image engine reported no Pearson column at all");
        }
        assertTrue("field-wide r = " + pearson + " should be short of the +1 that "
                        + "object 1 alone reaches",
                pearson.doubleValue() < 1.0);
        assertTrue("field-wide r = " + pearson + " should be positive: the linear "
                        + "majority outweighs object 2's 75 inverted voxels",
                pearson.doubleValue() > 0.0);
    }

    // ==================================================================
    // The whole suite over the whole dataset
    // ==================================================================

    /**
     * Every method the registry knows, over every case, with the chance test and
     * the agreement matrix on. Nothing here asserts a value — the assertions are
     * that the run completes, that every method either produced a result or was
     * skipped <i>with a reason</i>, and that no method silently vanished.
     *
     * <p>A method dropping out of the table is the failure this catches, and it
     * is invisible to per-case assertions: they only look at the engines they
     * name.
     */
    @Test
    public void everyMethodEitherRunsOrSaysWhyNotOnEveryCase() {
        List<ReferenceDataset.Case> cases = ReferenceDataset.all();
        for (int i = 0; i < cases.size(); i++) {
            ReferenceDataset.Case reference = cases.get(i);

            OCSParameters.Builder parameters =
                    OCSParameters.builder(reference.labelImages())
                            .allMethods()
                            .domain(reference.wholeField())
                            .agreement(true)
                            .nullModel(true)
                            .permutations(8)
                            .sourceName(reference.name());
            if (!reference.intensityImages().isEmpty()) {
                parameters.intensityImages(reference.intensityImages());
            }

            OCSResult result = OCS.run(parameters.build());

            int accounted = result.engineResults().size() + result.skipped().size();
            assertEquals(reference.name() + ": every method asked for is either a "
                            + "result or a skip with a reason",
                    result.parameters().methodIds().size(), accounted);

            for (int s = 0; s < result.skipped().size(); s++) {
                assertTrue(reference.name() + ": a skip must say why",
                        result.skipped().get(s).reason().length() > 0);
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static OCSResult run(ReferenceDataset.Case reference, String... methods) {
        OCSParameters.Builder parameters =
                OCSParameters.builder(reference.labelImages())
                        .methods(methods)
                        .domain(reference.wholeField())
                        .sourceName(reference.name());
        if (!reference.intensityImages().isEmpty()) {
            parameters.intensityImages(reference.intensityImages());
        }
        return OCS.run(parameters.build());
    }

    private static EngineResult engine(OCSResult result, String engineId) {
        EngineResult engine = result.resultFor(engineId);
        if (engine == null) {
            fail("no result for " + engineId + "; skipped as " + result.skipped());
        }
        return engine;
    }

    private static DirectionKey direction(EngineResult result, int source, int target) {
        List<DirectionKey> directions = result.directions();
        for (int i = 0; i < directions.size(); i++) {
            DirectionKey key = directions.get(i);
            if (key.sourceIndex() == source && key.targetIndex() == target) {
                return key;
            }
        }
        fail(result.engineId() + " has no direction " + source + "->" + target
                + ", only " + directions);
        return null;
    }

    private static ObjectScore scoreOf(EngineResult result, int source, int target,
            int label) {
        List<ObjectScore> scores = result.scores(direction(result, source, target));
        for (int i = 0; i < scores.size(); i++) {
            if (scores.get(i).sourceLabel() == label) {
                return scores.get(i);
            }
        }
        fail(result.engineId() + " has no object " + label + " in direction "
                + source + "->" + target);
        return null;
    }

    private static double valueOf(EngineResult result, int source, int target,
            int label) {
        return scoreOf(result, source, target, label).value();
    }

    private static int partnerOf(EngineResult result, int source, int target,
            int label) {
        return scoreOf(result, source, target, label).partnerLabel();
    }

    /** Reads a supporting column at the row belonging to {@code label}. */
    private static double supportingOf(EngineResult result, int source, int target,
            String column, int label) {
        DirectionKey key = direction(result, source, target);
        List<ObjectScore> scores = result.scores(key);
        double[] values = result.supporting(key, column);
        if (values == null) {
            fail(result.engineId() + " has no supporting column '" + column
                    + "', only " + result.supportingNames(key));
        }
        for (int i = 0; i < scores.size(); i++) {
            if (scores.get(i).sourceLabel() == label) {
                return values[i];
            }
        }
        fail(result.engineId() + " has no object " + label);
        return Double.NaN;
    }
}
