package ocs.engine.territory;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.process.ShortProcessor;
import ocs.engine.ColumnSpec;
import ocs.engine.DirectionKey;
import ocs.engine.EngineCancelledException;
import ocs.engine.EngineInputs;
import ocs.engine.EngineProgress;
import ocs.engine.EngineRegistry;
import ocs.engine.EngineResult;
import ocs.engine.ObjectScore;
import ocs.engine.ScaleKind;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The territory engine on fixtures whose answers are worked out on paper before
 * the code runs, never captured from it.
 *
 * <h2>The 2D fixture</h2>
 *
 * <pre>
 *   40x40 single plane, uncalibrated.
 *
 *   A: label 1  2x2 block centred on (6, 20)     index 0
 *      label 2  2x2 block centred on (34, 20)    index 1
 *      one wall between them, the vertical line x = 20, so the two
 *      territories are the two halves of the field: 800 each.
 *
 *   B: label 1  pixel  (10, 10) -> centroid (10.5, 10.5)   x < 20, A1
 *      label 2  pixel  (12, 30) -> centroid (12.5, 30.5)   x < 20, A1
 *      label 3  pixel  (30, 10) -> centroid (30.5, 10.5)   x > 20, A2
 *      label 4  pixels (19, 35) and (20, 35) -> centroid (20.0, 35.5)
 *                                                          exactly on the wall
 *
 *   A -> B, per A object, hand-computed:
 *      A1  3 targets, expected 4 x 800/1600 = 2, occupancy 1.5, share 0.75
 *      A2  1 target,  expected 2,               occupancy 0.5, share 0.25
 *
 *   B objects are scanned in raster order, so their indices do not follow
 *   their labels: label 1 -> 0, label 3 -> 1, label 2 -> 2, label 4 -> 3.
 *   That is the id mapping the engine must not get wrong, and it is why the
 *   Territory Id column of B -> A reads 0, 2, 1, 3 down the label order.
 * </pre>
 *
 * <h2>The 3D fixture</h2>
 *
 * <pre>
 *   20x20x6 stack, region ROI (0,0)-(18,18), uncalibrated.
 *
 *   A: label 1  voxel (4, 4, 2)     index 0
 *      label 2  voxel (15, 15, 3)   index 1
 *   A voxel belongs to A1 when 22x + 22y + 2z < 423, which never ties.
 *   Counting the 18x18x6 = 1944 region voxels that satisfy it:
 *      x+y <= 18   188 columns x 6 = 1128    all A1
 *      x+y == 19    16 columns, z <= 2 = 48  A1;  z >= 3 = 48  A2
 *      x+y >= 20   120 columns x 6 = 720     all A2
 *      A1 = 1176 voxels, A2 = 768, sum 1944.
 *
 *   B: label 1  voxel (5, 5, 2)     A1
 *      label 2  voxel (3, 6, 1)     A1
 *      label 3  voxel (14, 14, 3)   A2
 *      label 4  voxel (19, 19, 2)   outside the region: raster value 0
 *
 *   3 of the 4 targets are placed, so
 *      A1  2 targets, expected 3 x 1176/1944 = 49/27, occupancy 54/49
 *      A2  1 target,  expected 3 x  768/1944 = 32/27, occupancy 27/32
 * </pre>
 */
public class TerritoryColocEngineTest {

    private static final int FIELD = 40;
    private static final double EPSILON = 1e-9;

    private static final DirectionKey A_TO_B = new DirectionKey(0, "A", 1, "B");
    private static final DirectionKey B_TO_A = new DirectionKey(1, "B", 0, "A");

    private static final EngineProgress CANCELLED = new EngineProgress() {
        @Override
        public void report(String stage, double fraction) {
            // deliberately empty
        }

        @Override
        public boolean isCancelled() {
            return true;
        }
    };

    // ------------------------------------------------------------------
    // Contract.
    // ------------------------------------------------------------------

    @Test
    public void declaresOnePrimaryColumnAndPassesRegistration() {
        TerritoryColocEngine engine = new TerritoryColocEngine();
        int primaries = 0;
        for (ColumnSpec column : engine.columns()) {
            assertNotNull("column " + column.name() + " must declare a scale", column.scale());
            if (column.isPrimary()) {
                primaries++;
                assertEquals("the primary is a ratio with no bounded meaning, so it must "
                                + "never reach tier-3 agreement",
                        ScaleKind.UNBOUNDED, column.scale());
            }
        }
        assertEquals(1, primaries);

        EngineRegistry registry = EngineRegistry.empty();
        registry.register(engine);
        assertEquals(1, registry.size());
        assertTrue(registry.has("territory-occupancy"));
        assertFalse("the two directions do not share a tessellation", engine.isSymmetric());
    }

    @Test
    public void honoursCancellation() {
        try {
            new TerritoryColocEngine().compute(pair2D(), CANCELLED);
            fail("ignored a cancelled progress reporter");
        } catch (EngineCancelledException expected) {
            assertTrue(expected.getMessage().contains("territory-occupancy"));
        }
    }

    @Test
    public void escapeReachesInsideTheThreeDimensionalAssignmentAtOneWorker() {
        cancelsInsideTheAssignment(1);
    }

    @Test
    public void escapeReachesInsideTheThreeDimensionalAssignmentAtFourWorkers() {
        cancelsInsideTheAssignment(4);
    }

    /**
     * Lets the engine's own two checks (start, first direction) pass and cancels
     * from then on, so only a poll inside territories-core's assignment can see
     * it. Before 0.2.1 the first direction finished, reported half done, and the
     * cancel was noticed only between directions.
     */
    private static void cancelsInsideTheAssignment(int workers) {
        String previous = System.getProperty("territories.parallelism");
        System.setProperty("territories.parallelism", String.valueOf(workers));
        final java.util.concurrent.atomic.AtomicInteger polls =
                new java.util.concurrent.atomic.AtomicInteger();
        final List<Double> reported = java.util.Collections.synchronizedList(
                new ArrayList<Double>());
        EngineProgress cancelLater = new EngineProgress() {
            @Override
            public void report(String stage, double fraction) {
                reported.add(Double.valueOf(fraction));
            }

            @Override
            public boolean isCancelled() {
                return polls.incrementAndGet() > 2;
            }
        };
        try {
            new TerritoryColocEngine().compute(pair3D(), cancelLater);
            fail("ran to completion under a cancelled reporter");
        } catch (EngineCancelledException expected) {
            assertTrue(expected.getMessage().contains("territory-occupancy"));
            assertTrue("the core polled the flag, " + polls.get() + " polls",
                    polls.get() > 2);
            for (int i = 0; i < reported.size(); i++) {
                assertEquals("no direction may finish once cancelled: " + reported,
                        0.0, reported.get(i).doubleValue(), 0.0);
            }
        } finally {
            if (previous == null) {
                System.clearProperty("territories.parallelism");
            } else {
                System.setProperty("territories.parallelism", previous);
            }
        }
    }

    @Test
    public void everyDeclaredSupportingColumnIsActuallyReported() {
        // Except the partner, which rides in ObjectScore rather than in a column
        // array — the same split VolumeOverlapEngine uses.
        EngineResult result = run(pair2D());
        List<String> reported = result.supportingNames(A_TO_B);
        for (ColumnSpec column : new TerritoryColocEngine().columns()) {
            if (column.isPrimary() || "Territory Partner".equals(column.name())) {
                continue;
            }
            assertTrue("column '" + column.name() + "' is declared but not reported",
                    reported.contains(column.name()));
        }
    }

    // ------------------------------------------------------------------
    // Two dimensions, per object, against the hand-computed fixture.
    // ------------------------------------------------------------------

    @Test
    public void twoDimensionalOccupancyIsPerObjectAndHandComputed() {
        EngineResult result = run(pair2D());

        assertEquals("one row per source object", 2, result.scores(A_TO_B).size());
        assertColumn("source labels", new double[] {1, 2}, sourceLabels(result, A_TO_B));
        assertColumn("targets in territory", new double[] {3, 1},
                result.supporting(A_TO_B, "Targets In Territory"));
        assertColumn("the wall at x = 20 halves a 40x40 field",
                new double[] {800, 800}, result.supporting(A_TO_B, "Territory Size"));
        assertColumn("expected at even density", new double[] {2, 2},
                result.supporting(A_TO_B, "Expected Targets In Territory"));
        assertColumn("share of the four placed targets", new double[] {0.75, 0.25},
                result.supporting(A_TO_B, "Target Share"));
        assertColumn("occupancy", new double[] {1.5, 0.5}, result.values(A_TO_B));
        assertColumn("both cells run into the field edge", new double[] {1, 1},
                result.supporting(A_TO_B, "Territory Touches Field Edge"));

        List<ObjectScore> scores = result.scores(A_TO_B);
        assertTrue("A1 holds more targets than chance", scores.get(0).isCoincident());
        assertFalse("A2 holds fewer", scores.get(1).isCoincident());
        assertEquals("nearest target inside A1's territory", 1, scores.get(0).partnerLabel());
        assertEquals("the only target inside A2's territory", 3, scores.get(1).partnerLabel());
    }

    @Test
    public void aTargetOnTheWallGoesToTheLowerTerritoryId() {
        // B label 4 sits exactly on the wall between the two territories and is
        // covered by both. The rule is the lower territory id, which is A1, and
        // it is why A1 reads 3 targets rather than 2. Flip the rule and both
        // counts change.
        EngineResult result = run(pair2D());
        assertColumn("tie awarded to the lower territory id", new double[] {3, 1},
                result.supporting(A_TO_B, "Targets In Territory"));
    }

    @Test
    public void territoryIdIsTheCoreObjectIndexNotTheLabelOrder() {
        EngineResult result = run(pair2D());
        assertColumn("A objects appear in the raster in label order",
                new double[] {0, 1}, result.supporting(A_TO_B, "Territory Id"));
        // B objects do not: raster order is label 1, 3, 2, 4.
        assertColumn("B labels 1,2,3,4 carry territory ids 0,2,1,3",
                new double[] {0, 2, 1, 3}, result.supporting(B_TO_A, "Territory Id"));
    }

    @Test
    public void directionIsNotAnInversion() {
        // The test the containment engine did not have. A -> B tessellates the
        // two A objects; B -> A tessellates the four B objects. Swap the two
        // channels anywhere in the pipeline and every one of these assertions
        // moves, because the two directions do not even agree on how many rows
        // there are.
        EngineResult result = run(pair2D());

        assertEquals("A -> B has one row per A object", 2, result.scores(A_TO_B).size());
        assertEquals("B -> A has one row per B object", 4, result.scores(B_TO_A).size());
        assertColumn("A -> B source labels", new double[] {1, 2}, sourceLabels(result, A_TO_B));
        assertColumn("B -> A source labels", new double[] {1, 2, 3, 4},
                sourceLabels(result, B_TO_A));
        assertColumn("A -> B counts", new double[] {3, 1},
                result.supporting(A_TO_B, "Targets In Territory"));
        assertColumn("B -> A counts: A1 falls in B1's territory, A2 in B3's",
                new double[] {1, 0, 1, 0}, result.supporting(B_TO_A, "Targets In Territory"));

        double[] forward = result.supporting(A_TO_B, "Territory Size");
        double[] backward = result.supporting(B_TO_A, "Territory Size");
        assertEquals("both partitions cover the same field", sum(forward), sum(backward), EPSILON);
        assertEquals("but not with the same number of pieces", 2, forward.length);
        assertEquals(4, backward.length);
    }

    @Test
    public void targetIsPlacedByThePolygonNotByItsBoundingBox() {
        // Two objects on a diagonal make two triangular territories whose
        // bounding boxes are both the whole field. The target sits well inside
        // A2's triangle and inside A1's bounding box, so a bounding-box test
        // would hand it to A1 — the first territory in id order.
        ImagePlus a = plane("A", concat(block(1, 5, 5), block(2, 33, 33)));
        ImagePlus b = plane("B", new int[][] {{1, 30, 30}});
        EngineResult result = run(inputs(a, b));

        assertColumn("the target belongs to the second territory",
                new double[] {0, 1}, result.supporting(A_TO_B, "Targets In Territory"));
    }

    @Test
    public void anInteriorTerritoryIsNotFlaggedAsAnEdgeCell() {
        // Five objects in a plus. The middle one's territory is bounded on all
        // four sides by its neighbours — the square from 13 to 27 — so it is the
        // one cell in the fixture whose size is a measurement rather than an
        // artefact of where the image stops. Labels are deliberately not in
        // raster order, so the Territory Id column has to be a lookup.
        ImagePlus a = plane("A", concat(
                block(10, 19, 19), block(20, 5, 19), block(30, 33, 19),
                block(40, 19, 5), block(50, 19, 33)));
        ImagePlus b = plane("B", new int[][] {{7, 22, 22}});
        EngineResult result = run(inputs(a, b));

        assertColumn("rows run in label order", new double[] {10, 20, 30, 40, 50},
                sourceLabels(result, A_TO_B));
        assertColumn("raster order was top, left, centre, right, bottom",
                new double[] {2, 1, 3, 0, 4}, result.supporting(A_TO_B, "Territory Id"));
        assertColumn("only the centre territory is interior",
                new double[] {0, 1, 1, 1, 1},
                result.supporting(A_TO_B, "Territory Touches Field Edge"));
        assertEquals("the centre cell is the 14x14 square between the four walls",
                196.0, result.supporting(A_TO_B, "Territory Size")[0], EPSILON);
        assertColumn("the single target is in the centre territory",
                new double[] {1, 0, 0, 0, 0},
                result.supporting(A_TO_B, "Targets In Territory"));
        assertEquals("1 target against an expectation of 196/1600",
                1600.0 / 196.0, result.values(A_TO_B)[0], 1e-9);
    }

    @Test
    public void calibrationScalesTheTerritoryAndIsTakenFromTheInputsNotTheImage() {
        // The images are uncalibrated; the run's calibration says two microns a
        // pixel. Territory size must be in the run's units, and the tessellation
        // must happen in the same coordinate system the region was built in —
        // mixing the two puts every object outside the field.
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 2.0;
        calibration.pixelHeight = 2.0;
        calibration.setUnit("micron");
        EngineInputs inputs = EngineInputs.builder(pairImages2D())
                .calibration(calibration).build();
        EngineResult result = run(inputs);

        assertColumn("800 square pixels is 3200 square microns",
                new double[] {3200, 3200}, result.supporting(A_TO_B, "Territory Size"));
        assertColumn("a uniform scaling cannot move an object between territories",
                new double[] {3, 1}, result.supporting(A_TO_B, "Targets In Territory"));
        assertColumn("and the ratio is scale free", new double[] {1.5, 0.5},
                result.values(A_TO_B));
    }

    @Test
    public void aNonZeroFirstTerritoryIdStillResolves() {
        // The core's object index is assigned by whoever calls it. Nothing makes
        // it start at zero, and the 3D raster stores it plus one, so an engine
        // that assumes either resolves every target to the wrong object.
        TerritoryField field = TerritoryField.measure(
                plane("A", aPixels2D()), plane("B", bPixels2D()),
                Collections.<Roi>emptyList(), new Calibration(), 7);

        assertEquals(2, field.cells().size());
        assertEquals("first source object keeps the index it was given",
                7, field.cells().get(0).territoryId());
        assertEquals(8, field.cells().get(1).territoryId());
        assertEquals("and the targets still land in the right one",
                3, field.cells().get(0).targetCount());
        assertEquals(1, field.cells().get(1).targetCount());
    }

    // ------------------------------------------------------------------
    // Three dimensions.
    // ------------------------------------------------------------------

    @Test
    public void threeDimensionalOccupancyIsPerObjectAndHandComputed() {
        EngineResult result = run(pair3D());

        assertColumn("source labels", new double[] {1, 2}, sourceLabels(result, A_TO_B));
        assertColumn("territory ids", new double[] {0, 1},
                result.supporting(A_TO_B, "Territory Id"));
        assertColumn("targets in territory", new double[] {2, 1},
                result.supporting(A_TO_B, "Targets In Territory"));
        assertColumn("voxels either side of 22x + 22y + 2z = 423",
                new double[] {1176, 768}, result.supporting(A_TO_B, "Territory Size"));
        assertColumn("three placed targets split by territory volume",
                new double[] {49.0 / 27.0, 32.0 / 27.0},
                result.supporting(A_TO_B, "Expected Targets In Territory"));
        assertColumn("share", new double[] {2.0 / 3.0, 1.0 / 3.0},
                result.supporting(A_TO_B, "Target Share"));
        assertColumn("occupancy", new double[] {54.0 / 49.0, 27.0 / 32.0},
                result.values(A_TO_B));

        List<ObjectScore> scores = result.scores(A_TO_B);
        assertEquals("nearest target inside A1's territory", 1, scores.get(0).partnerLabel());
        assertEquals(3, scores.get(1).partnerLabel());
        assertTrue(scores.get(0).isCoincident());
        assertFalse(scores.get(1).isCoincident());
    }

    @Test
    public void aTargetOnRasterValueZeroIsOutsideEveryTerritory() {
        // B label 4 sits at (19, 19, 2), outside the 18x18 region, where the
        // territory raster reads 0. Treating 0 as a territory id would add it to
        // some object's count and to the placed total; both are visible here.
        EngineResult result = run(pair3D());

        double placed = sum(result.supporting(A_TO_B, "Targets In Territory"));
        assertEquals("three of the four B objects were placed", 3.0, placed, EPSILON);
        assertEquals("and the expectations sum to the same three",
                3.0, sum(result.supporting(A_TO_B, "Expected Targets In Territory")), 1e-12);
        assertEquals("no territory claims the outside object as its nearest",
                1, result.scores(A_TO_B).get(0).partnerLabel());
    }

    @Test
    public void aSourceObjectOutsideTheRegionKeepsItsRowAndReportsNothing() {
        ImagePlus a = stack("A", aVoxels3D());
        ImagePlus b = stack("B", bVoxels3D());
        EngineInputs inputs = EngineInputs.builder(Arrays.asList(a, b))
                .domain(Collections.<Roi>singletonList(new Roi(0, 0, 12, 12)))
                .build();
        EngineResult result = run(inputs);

        assertColumn("both A objects keep a row", new double[] {1, 2},
                sourceLabels(result, A_TO_B));
        double[] sizes = result.supporting(A_TO_B, "Territory Size");
        assertEquals("A1 owns the whole 12x12x6 region", 864.0, sizes[0], EPSILON);
        assertTrue("A2's centroid is outside it, so A2 has no territory",
                Double.isNaN(sizes[1]));
        assertTrue(Double.isNaN(result.supporting(A_TO_B, "Territory Id")[1]));
        assertTrue(Double.isNaN(result.supporting(A_TO_B, "Targets In Territory")[1]));
        assertTrue(Double.isNaN(result.values(A_TO_B)[1]));
        assertFalse("an undefined row is not a coincident row",
                result.scores(A_TO_B).get(1).isCoincident());
        assertEquals(ObjectScore.NO_PARTNER, result.scores(A_TO_B).get(1).partnerLabel());
        assertColumn("only the two targets inside the region were placed",
                new double[] {2, Double.NaN}, result.supporting(A_TO_B, "Targets In Territory"));
    }

    @Test
    public void threeDimensionalCalibrationChangesTheTessellation() {
        // Three microns a slice against one across, so distance in z counts nine
        // times as much and the wall between the two objects moves. Volumes are
        // recomputed here from the definition of a Voronoi partition — nearest
        // calibrated centroid wins — rather than taken from the engine.
        Calibration calibration = new Calibration();
        calibration.pixelDepth = 3.0;
        calibration.setUnit("micron");
        EngineInputs inputs = EngineInputs.builder(
                        Arrays.asList(stack("A", aVoxels3D()), stack("B", bVoxels3D())))
                .domain(Collections.<Roi>singletonList(new Roi(0, 0, 18, 18)))
                .calibration(calibration)
                .build();
        EngineResult result = run(inputs);

        double[] expectedVolumes = voronoiVolumesByDefinition(1.0, 1.0, 3.0);
        assertColumn("calibrated partition", expectedVolumes,
                result.supporting(A_TO_B, "Territory Size"));
        double[] uncalibrated = voronoiVolumesByDefinition(1.0, 1.0, 1.0);
        assertTrue("if it matched the uncalibrated split the calibration was dropped",
                Math.abs(expectedVolumes[0] - uncalibrated[0]) > EPSILON);
    }

    @Test
    public void theThreeDimensionalTerritoryRasterIsReleased() {
        // territories-core hands over an ImagePlus and says the caller owns it.
        // One of these is built per direction per image, so a batch that keeps
        // them dies on heap rather than on anything a user could diagnose.
        TerritoryField field = TerritoryField.measure(
                stack("A", aVoxels3D()), stack("B", bVoxels3D()),
                Collections.<Roi>singletonList(new Roi(0, 0, 18, 18)),
                new Calibration());

        ImageStack raster = field.releasedLabels();
        assertNotNull("the 3D path must report the raster it used", raster);
        for (int slice = 1; slice <= raster.getSize(); slice++) {
            assertNull("slice " + slice + " still holds its voxels", raster.getPixels(slice));
        }
        assertEquals("and the measurement happened before the release",
                2, field.cells().get(0).targetCount());
    }

    @Test
    public void aNonZeroFirstTerritoryIdStillResolvesInTheRaster() {
        // The 3D raster stores the index plus one. At base 7 the raster holds 8
        // and 9, so an engine that subtracts the wrong amount, or none, finds no
        // territory at all and reports every count as zero.
        TerritoryField field = TerritoryField.measure(
                stack("A", aVoxels3D()), stack("B", bVoxels3D()),
                Collections.<Roi>singletonList(new Roi(0, 0, 18, 18)),
                new Calibration(), 7);

        assertEquals(7, field.cells().get(0).territoryId());
        assertEquals(8, field.cells().get(1).territoryId());
        assertEquals(2, field.cells().get(0).targetCount());
        assertEquals(1, field.cells().get(1).targetCount());
        assertEquals("the object outside the region is still outside", 3, field.placedTargets());
    }

    // ------------------------------------------------------------------
    // Determinism.
    // ------------------------------------------------------------------

    @Test
    public void repeatedRunsAgreeBitForBitPerObject() {
        EngineInputs inputs = pair2D();
        EngineResult first = run(inputs);
        for (int repeat = 0; repeat < 3; repeat++) {
            EngineResult again = run(inputs);
            for (DirectionKey direction : new DirectionKey[] {A_TO_B, B_TO_A}) {
                assertExactColumn("primary of " + direction.label(),
                        first.values(direction), again.values(direction));
                for (String column : first.supportingNames(direction)) {
                    assertExactColumn(column + " of " + direction.label(),
                            first.supporting(direction, column),
                            again.supporting(direction, column));
                }
                List<ObjectScore> before = first.scores(direction);
                List<ObjectScore> after = again.scores(direction);
                for (int i = 0; i < before.size(); i++) {
                    assertEquals("source label " + i, before.get(i).sourceLabel(),
                            after.get(i).sourceLabel());
                    assertEquals("partner " + i, before.get(i).partnerLabel(),
                            after.get(i).partnerLabel());
                }
            }
        }
    }

    @Test
    public void theDeterminismCheckRejectsAScrambledResult() {
        // Negative control. Rotating the per-object values by one row must break
        // the comparison, or the comparison was never evidence of anything.
        double[] values = run(pair2D()).values(A_TO_B);
        double[] rotated = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            rotated[i] = values[(i + 1) % values.length];
        }
        try {
            assertExactColumn("rotated", values, rotated);
            fail("a rotation of the per-object values passed the comparison");
        } catch (AssertionError expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("rotated"));
        }
    }

    // ------------------------------------------------------------------
    // Fixtures.
    // ------------------------------------------------------------------

    private static EngineResult run(EngineInputs inputs) {
        return new TerritoryColocEngine().compute(inputs, EngineProgress.SILENT);
    }

    private static EngineInputs inputs(ImagePlus a, ImagePlus b) {
        return EngineInputs.builder(Arrays.asList(a, b)).build();
    }

    private static EngineInputs pair2D() {
        return EngineInputs.builder(pairImages2D()).build();
    }

    private static List<ImagePlus> pairImages2D() {
        return Arrays.asList(plane("A", aPixels2D()), plane("B", bPixels2D()));
    }

    private static int[][] aPixels2D() {
        return concat(block(1, 5, 19), block(2, 33, 19));
    }

    private static int[][] bPixels2D() {
        return new int[][] {
                {1, 10, 10},
                {2, 12, 30},
                {3, 30, 10},
                {4, 19, 35}, {4, 20, 35}};
    }

    private static EngineInputs pair3D() {
        return EngineInputs.builder(
                        Arrays.asList(stack("A", aVoxels3D()), stack("B", bVoxels3D())))
                .domain(Collections.<Roi>singletonList(new Roi(0, 0, 18, 18)))
                .build();
    }

    private static int[][] aVoxels3D() {
        return new int[][] {{1, 4, 4, 2}, {2, 15, 15, 3}};
    }

    private static int[][] bVoxels3D() {
        return new int[][] {{1, 5, 5, 2}, {2, 3, 6, 1}, {3, 14, 14, 3}, {4, 19, 19, 2}};
    }

    /**
     * The 3D territory volumes worked out from the definition — every region
     * voxel goes to the nearer calibrated centroid — rather than from the core's
     * flood fill and k-d tree. Two independent routes to the same partition.
     */
    private static double[] voronoiVolumesByDefinition(
            double pixelWidth, double pixelHeight, double pixelDepth) {
        double[] volumes = new double[2];
        double voxel = pixelWidth * pixelHeight * pixelDepth;
        for (int z = 0; z < 6; z++) {
            for (int y = 0; y < 18; y++) {
                for (int x = 0; x < 18; x++) {
                    double first = squared(
                            (x - 4) * pixelWidth, (y - 4) * pixelHeight, (z - 2) * pixelDepth);
                    double second = squared(
                            (x - 15) * pixelWidth, (y - 15) * pixelHeight, (z - 3) * pixelDepth);
                    volumes[first <= second ? 0 : 1] += voxel;
                }
            }
        }
        return volumes;
    }

    private static double squared(double dx, double dy, double dz) {
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * A 2x2 block with (x, y) as its top-left pixel, so its centroid lands on
     * the whole number (x + 1, y + 1). Single pixels centre on a half, which is
     * no use when a fixture needs an object exactly on a wall.
     */
    private static int[][] block(int label, int x, int y) {
        return new int[][] {
                {label, x, y}, {label, x + 1, y},
                {label, x, y + 1}, {label, x + 1, y + 1}};
    }

    private static int[][] concat(int[][]... parts) {
        List<int[]> all = new ArrayList<int[]>();
        for (int[][] part : parts) {
            all.addAll(Arrays.asList(part));
        }
        return all.toArray(new int[all.size()][]);
    }

    private static ImagePlus plane(String title, int[][] pixels) {
        short[] data = new short[FIELD * FIELD];
        for (int[] pixel : pixels) {
            data[pixel[2] * FIELD + pixel[1]] = (short) pixel[0];
        }
        return new ImagePlus(title, new ShortProcessor(FIELD, FIELD, data, null));
    }

    private static ImagePlus stack(String title, int[][] voxels) {
        int width = 20;
        int height = 20;
        int depth = 6;
        ImageStack stack = new ImageStack(width, height);
        short[][] planes = new short[depth][width * height];
        for (int[] voxel : voxels) {
            planes[voxel[3]][voxel[2] * width + voxel[1]] = (short) voxel[0];
        }
        for (int z = 0; z < depth; z++) {
            stack.addSlice(new ShortProcessor(width, height, planes[z], null));
        }
        return new ImagePlus(title, stack);
    }

    // ------------------------------------------------------------------
    // Assertions.
    // ------------------------------------------------------------------

    private static double[] sourceLabels(EngineResult result, DirectionKey direction) {
        List<ObjectScore> scores = result.scores(direction);
        double[] labels = new double[scores.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = scores.get(i).sourceLabel();
        }
        return labels;
    }

    private static double sum(double[] values) {
        double total = 0.0;
        for (double value : values) {
            total += value;
        }
        return total;
    }

    private static void assertColumn(String message, double[] expected, double[] actual) {
        assertEquals(message + ": wrong number of objects", expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            if (Double.isNaN(expected[i])) {
                assertTrue(message + " at object " + i + ": expected NaN, was " + actual[i],
                        Double.isNaN(actual[i]));
            } else {
                assertEquals(message + " at object " + i, expected[i], actual[i], EPSILON);
            }
        }
    }

    /** Tolerance exactly zero, per the determinism rule. */
    private static void assertExactColumn(String message, double[] expected, double[] actual) {
        assertEquals(message + ": wrong number of objects", expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            if (Double.isNaN(expected[i]) && Double.isNaN(actual[i])) {
                continue;
            }
            assertEquals(message + " at object " + i, expected[i], actual[i], 0.0);
        }
    }
}
