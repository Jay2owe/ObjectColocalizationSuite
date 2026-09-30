package ocs.engine.territory;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.process.ShortProcessor;
import ocs.engine.DirectionKey;
import ocs.engine.EngineInputs;
import ocs.engine.EngineProgress;
import ocs.engine.EngineResult;
import org.junit.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import sc.fiji.territories.core.EdgeCellPolicy;
import sc.fiji.territories.core.LabelObjectExtractor;
import sc.fiji.territories.core.RegionFactory;
import sc.fiji.territories.core.RegionMode;
import sc.fiji.territories.core.SpatialObject2D;
import sc.fiji.territories.core.TerritoryCell;
import sc.fiji.territories.core.TerritoryEngine;
import sc.fiji.territories.core.TerritoryResult;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A direct probe of {@code territories-core}'s {@code Polygons}, which was
 * flagged during extraction as never having been reviewed — it exists in the
 * shipped jar with no history anywhere in the repository it came from.
 *
 * <p>The 2D territory path depends on it twice over: the region clip and the
 * per-cell territory clip both run their overlay output through it. If it is
 * wrong, this engine reports confidently wrong territory sizes, so it is tested
 * here rather than taken on trust.
 *
 * <p>It is package-private, so the checks go in through reflection. That names
 * the class as a string, and the shade plugin rewrites bytecode rather than
 * strings — harmless, because tests never run against the shaded jar, but the
 * reason this pattern must not appear in {@code src/main}.
 */
public class TerritoryCorePolygonsTest {

    private static final GeometryFactory GEOMETRY = new GeometryFactory();
    private static final double EPSILON = 1e-9;

    // ------------------------------------------------------------------
    // Why the class exists.
    // ------------------------------------------------------------------

    @Test
    public void aMixedCollectionIsUnusableAsAnAnalysisDomain() {
        // The premise of the whole class: a GeometryCollection holding a polygon
        // and a stray line still reports an area, so it passes the obvious
        // checks, and then refuses every operation the tessellation needs.
        Geometry mixed = mixed();
        assertEquals("it looks fine on area alone", 100.0, mixed.getArea(), EPSILON);

        Point inside = GEOMETRY.createPoint(new Coordinate(5.0, 5.0));
        try {
            mixed.covers(inside);
            fail("a mixed collection was expected to refuse covers()");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().toLowerCase().contains("geometrycollection"));
        }
    }

    // ------------------------------------------------------------------
    // What it promises.
    // ------------------------------------------------------------------

    @Test
    public void aStrayLineBesideAPolygonIsDropped() {
        Geometry result = polygonalOnly(mixed());
        assertTrue("the result must be usable as a domain", result instanceof Polygon);
        assertEquals("and must keep the whole polygon", 100.0, result.getArea(), EPSILON);
        assertTrue(result.covers(GEOMETRY.createPoint(new Coordinate(5.0, 5.0))));
    }

    @Test
    public void aPlainPolygonIsHandedBackUntouched() {
        Geometry square = square(0, 0, 10, 10);
        assertSame("no copy, no rebuild", square, polygonalOnly(square));
    }

    @Test
    public void severalPolygonsBecomeOneMultiPolygon() {
        Geometry collection = GEOMETRY.createGeometryCollection(new Geometry[] {
                square(0, 0, 10, 10), square(20, 0, 30, 10), line(0, 20, 10, 20)});
        Geometry result = polygonalOnly(collection);

        assertEquals("both islands survive", 2, result.getNumGeometries());
        assertEquals(200.0, result.getArea(), EPSILON);
        assertTrue(result.covers(GEOMETRY.createPoint(new Coordinate(25.0, 5.0))));
    }

    @Test
    public void aGeometryWithNoPolygonAtAllIsReturnedUnchanged() {
        // Documented behaviour: the caller reports emptiness itself, with the
        // context to explain it. Worth pinning because a caller that expected an
        // empty polygon back would take the wrong branch.
        Geometry lineOnly = line(0, 0, 10, 0);
        assertSame(lineOnly, polygonalOnly(lineOnly));
        assertFalse("still not empty, still not an area", lineOnly.isEmpty());
        assertEquals(0.0, lineOnly.getArea(), EPSILON);
    }

    @Test
    public void emptyAndNullPassStraightThrough() {
        Geometry empty = GEOMETRY.createPolygon();
        assertSame(empty, polygonalOnly(empty));
        assertNull(polygonalOnly(null));
    }

    @Test
    public void theOverlayThatMotivatesItReallyProducesAMixedResult() {
        // An L-shaped domain that shares part of one edge with the square being
        // clipped: the shared stretch comes back as a dangling line beside the
        // clipped area. This is the shape the territory clip hits when a cell
        // wall runs along the region boundary.
        Geometry square = square(0, 0, 10, 10);
        Geometry lShape = square(5, 0, 20, 10).union(square(0, 10, 10, 20));
        Geometry overlay = square.intersection(lShape);

        assertTrue("JTS returns polygon plus line, not a polygon",
                overlay.getNumGeometries() > 1);
        assertEquals("the area is only the overlapping strip", 50.0, overlay.getArea(), EPSILON);

        Geometry cleaned = polygonalOnly(overlay);
        assertEquals("cleaning changes no area", 50.0, cleaned.getArea(), EPSILON);
        assertTrue("and makes it usable again",
                cleaned.covers(GEOMETRY.createPoint(new Coordinate(7.0, 5.0))));
    }

    // ------------------------------------------------------------------
    // The same situation reached through the engine.
    // ------------------------------------------------------------------

    @Test
    public void aCellWallLyingOnTheRegionBoundaryStillMeasures() {
        // Two region ROIs meeting at x = 20, and two objects whose territory
        // wall falls on exactly that line. Both clips that Polygons guards run
        // here. The answer is the same halves as the single-ROI fixture: if the
        // dangling line survived either clip, the areas would not come back.
        ImagePlus a = plane("A", new int[][] {
                {1, 5, 19}, {1, 6, 19}, {1, 5, 20}, {1, 6, 20},
                {2, 33, 19}, {2, 34, 19}, {2, 33, 20}, {2, 34, 20}});
        ImagePlus b = plane("B", new int[][] {{1, 10, 10}, {2, 30, 10}});
        EngineInputs inputs = EngineInputs.builder(Arrays.asList(a, b))
                .domain(Arrays.asList(new Roi(0, 0, 20, 40), new Roi(20, 0, 20, 40)))
                .build();

        EngineResult result = new TerritoryColocEngine().compute(inputs, EngineProgress.SILENT);
        double[] sizes = result.supporting(new DirectionKey(0, "A", 1, "B"), "Territory Size");
        assertEquals(800.0, sizes[0], EPSILON);
        assertEquals(800.0, sizes[1], EPSILON);
    }

    @Test
    public void theTieBreakFixtureReallyIsATie() {
        // Pins the premise of TerritoryColocEngineTest's tie-break case: the
        // target at (20.0, 35.5) has to be covered by both territories, or that
        // test passes without ever exercising a tie.
        List<SpatialObject2D> sources = LabelObjectExtractor.extract(
                plane("A", new int[][] {
                        {1, 5, 19}, {1, 6, 19}, {1, 5, 20}, {1, 6, 20},
                        {2, 33, 19}, {2, 34, 19}, {2, 33, 20}, {2, 34, 20}}),
                0, 0);
        TerritoryResult tessellation = TerritoryEngine.analyze(
                sources,
                RegionFactory.create(
                        Collections.<Roi>singletonList(new Roi(0, 0, 40, 40)),
                        RegionMode.UNION, 1.0, 1.0, 40, 40).get(0),
                EdgeCellPolicy.INCLUDE_FLAGGED);

        Point onTheWall = GEOMETRY.createPoint(new Coordinate(20.0, 35.5));
        assertEquals(2, tessellation.getCells().size());
        for (TerritoryCell cell : tessellation.getCells()) {
            assertTrue("territory " + cell.getObject().getIndex()
                            + " must cover the point on the wall",
                    cell.getGeometry().covers(onTheWall));
        }
    }

    // ------------------------------------------------------------------
    // Plumbing.
    // ------------------------------------------------------------------

    private static Geometry polygonalOnly(Geometry geometry) {
        try {
            Class<?> type = Class.forName("sc.fiji.territories.core.Polygons");
            Method method = type.getDeclaredMethod("polygonalOnly", Geometry.class);
            method.setAccessible(true);
            return (Geometry) method.invoke(null, geometry);
        } catch (ClassNotFoundException failure) {
            throw new AssertionError(failure);
        } catch (NoSuchMethodException failure) {
            throw new AssertionError(failure);
        } catch (IllegalAccessException failure) {
            throw new AssertionError(failure);
        } catch (InvocationTargetException failure) {
            throw new AssertionError(failure.getCause());
        }
    }

    /** A polygon with a stray line beside it, the shape JTS hands back from an overlay. */
    private static Geometry mixed() {
        return GEOMETRY.createGeometryCollection(new Geometry[] {
                square(0, 0, 10, 10), line(0, 10, 10, 10)});
    }

    private static Geometry square(double x1, double y1, double x2, double y2) {
        return GEOMETRY.createPolygon(new Coordinate[] {
                new Coordinate(x1, y1), new Coordinate(x2, y1),
                new Coordinate(x2, y2), new Coordinate(x1, y2),
                new Coordinate(x1, y1)});
    }

    private static LineString line(double x1, double y1, double x2, double y2) {
        return GEOMETRY.createLineString(new Coordinate[] {
                new Coordinate(x1, y1), new Coordinate(x2, y2)});
    }

    private static ImagePlus plane(String title, int[][] pixels) {
        short[] data = new short[40 * 40];
        for (int[] pixel : pixels) {
            data[pixel[2] * 40 + pixel[1]] = (short) pixel[0];
        }
        return new ImagePlus(title, new ShortProcessor(40, 40, data, null));
    }
}
