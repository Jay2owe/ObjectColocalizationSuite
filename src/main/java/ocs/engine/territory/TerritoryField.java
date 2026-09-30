package ocs.engine.territory;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import ocs.engine.ObjectScore;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import sc.fiji.territories.core.EdgeCellPolicy;
import sc.fiji.territories.core.LabelObjectExtractor;
import sc.fiji.territories.core.LabelObjectExtractor3D;
import sc.fiji.territories.core.RegionFactory;
import sc.fiji.territories.core.RegionMask3D;
import sc.fiji.territories.core.RegionMaskFactory3D;
import sc.fiji.territories.core.RegionMode;
import sc.fiji.territories.core.SpatialObject2D;
import sc.fiji.territories.core.SpatialObject3D;
import sc.fiji.territories.core.SpatialRegion2D;
import sc.fiji.territories.core.TerritoryCell;
import sc.fiji.territories.core.TerritoryCell3D;
import sc.fiji.territories.core.TerritoryEngine;
import sc.fiji.territories.core.TerritoryEngine3D;
import sc.fiji.territories.core.TerritoryResult;
import sc.fiji.territories.core.TerritoryResult3D;
import sc.fiji.territories.core.TypedSpatialObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.Map;

/**
 * One channel tessellated into territories, with the other channel's objects
 * located in them.
 *
 * <p>Everything that touches {@code territories-core} lives here, so
 * {@link TerritoryColocEngine} is left declaring columns and arithmetic. The
 * split matters because the two halves fail differently: the arithmetic is
 * hand-checkable and the tessellation is a dependency with three separate traps
 * in its API — a deep-copying geometry accessor, a caller-assigned object index
 * that the 3D raster stores offset by one, and an {@code ImagePlus} the caller
 * must release.
 *
 * <h2>Direction</h2>
 *
 * <p>The <i>source</i> channel is tessellated and the <i>target</i> channel is
 * located in the result. Reversing the two is a different analysis, not the same
 * analysis read backwards, because the tessellation itself changes: territory
 * count, territory shapes and territory sizes all come from the source channel
 * alone. A field with two source objects produces two rows whichever way round
 * the targets are arranged.
 *
 * <h2>Edge territories</h2>
 *
 * <p>A Voronoi cell with no neighbour on one side is unbounded in principle and
 * is clipped to the analysed field in practice, so its size is set by where the
 * image stops rather than by biology. {@link EdgeCellPolicy#INCLUDE_FLAGGED} is
 * used deliberately: every source object keeps its row, and the flag rides out
 * to the table as a column so a reader can exclude those rows knowingly. The
 * alternative silently deletes rows from a per-object table.
 *
 * <h2>Two dimensions or three</h2>
 *
 * <p>Chosen from the stack size of the source image, which
 * {@code EngineInputs} has already checked is the same for every channel. A
 * single-slice stack is a 2D image and takes the exact polygon path; two slices
 * or more take the voxel path. There is no setting, because there is no case
 * where a user wants the other answer for the same data.
 */
final class TerritoryField {

    /** Territory id of a source object that fell outside the analysed field. */
    static final int NO_TERRITORY = -1;

    private static final EdgeCellPolicy EDGE_POLICY = EdgeCellPolicy.INCLUDE_FLAGGED;

    private static final GeometryFactory GEOMETRY = new GeometryFactory();

    /** Type indices exist to keep the core's error messages readable; nothing reads them back. */
    private static final int SOURCE_TYPE = 0;
    private static final int TARGET_TYPE = 1;

    private final List<Cell> cells;
    private final int placedTargets;
    private final double totalSize;
    private final ImageStack releasedLabels;

    private TerritoryField(List<Cell> cells, int placedTargets, double totalSize,
                           ImageStack releasedLabels) {
        this.cells = Collections.unmodifiableList(cells);
        this.placedTargets = placedTargets;
        this.totalSize = totalSize;
        this.releasedLabels = releasedLabels;
    }

    /** One source object's territory and what the target channel put in it. */
    static final class Cell {

        private final int sourceLabel;
        private final int territoryId;
        private final double size;
        private final boolean edgeCell;
        private int targetCount;
        private int partnerLabel = ObjectScore.NO_PARTNER;
        private double partnerDistanceSquared = Double.POSITIVE_INFINITY;

        private Cell(int sourceLabel, int territoryId, double size, boolean edgeCell) {
            this.sourceLabel = sourceLabel;
            this.territoryId = territoryId;
            this.size = size;
            this.edgeCell = edgeCell;
        }

        int sourceLabel() {
            return sourceLabel;
        }

        /** The core's object index, or {@link #NO_TERRITORY}. */
        int territoryId() {
            return territoryId;
        }

        /** Calibrated area in 2D, calibrated volume in 3D; NaN without a territory. */
        double size() {
            return size;
        }

        boolean isEdgeCell() {
            return edgeCell;
        }

        int targetCount() {
            return targetCount;
        }

        int partnerLabel() {
            return partnerLabel;
        }

        boolean hasTerritory() {
            return territoryId != NO_TERRITORY;
        }

        private void place(int targetLabel, double distanceSquared) {
            targetCount++;
            // Ties go to the lower target label so the partner column cannot
            // depend on the order objects happened to be scanned in.
            if (distanceSquared < partnerDistanceSquared
                    || (distanceSquared == partnerDistanceSquared
                        && targetLabel < partnerLabel)) {
                partnerDistanceSquared = distanceSquared;
                partnerLabel = targetLabel;
            }
        }
    }

    /** One cell per source object, ordered by ascending source label. */
    List<Cell> cells() {
        return cells;
    }

    /** Target objects that landed in some territory; the denominator of the share. */
    int placedTargets() {
        return placedTargets;
    }

    /** Total territory size, which is the size of the tessellated field. */
    double totalSize() {
        return totalSize;
    }

    /**
     * The 3D territory raster after this class released it, or null on the 2D
     * path. Held for one reason only: a test can prove the release happened.
     * After the release it carries no voxels.
     */
    ImageStack releasedLabels() {
        return releasedLabels;
    }

    static TerritoryField measure(ImagePlus sourceLabels, ImagePlus targetLabels,
                                  List<Roi> domain, Calibration calibration) {
        return measure(sourceLabels, targetLabels, domain, calibration, 0, null);
    }

    /**
     * @param cancelled polled inside the 3D territory assignment, per slice and
     *                  every 4,096 voxels, from the core's own worker threads;
     *                  when it returns true the core throws
     *                  {@code ComputationCancelledException}. Null never
     *                  cancels. The 2D path is a polygon tessellation with no
     *                  long voxel loop and no cancellable overload.
     */
    static TerritoryField measure(ImagePlus sourceLabels, ImagePlus targetLabels,
                                  List<Roi> domain, Calibration calibration,
                                  BooleanSupplier cancelled) {
        return measure(sourceLabels, targetLabels, domain, calibration, 0, cancelled);
    }

    /**
     * @param firstTerritoryId index the core assigns to the first source object.
     *                         Caller-assigned, not derived from the label image,
     *                         and the 3D raster stores it as {@code index + 1}.
     *                         Territory ids are therefore whatever the caller
     *                         says they are and must be looked up, never assumed
     *                         to run from zero or to follow label order.
     */
    static TerritoryField measure(ImagePlus sourceLabels, ImagePlus targetLabels,
                                  List<Roi> domain, Calibration calibration,
                                  int firstTerritoryId) {
        return measure(sourceLabels, targetLabels, domain, calibration,
                firstTerritoryId, null);
    }

    static TerritoryField measure(ImagePlus sourceLabels, ImagePlus targetLabels,
                                  List<Roi> domain, Calibration calibration,
                                  int firstTerritoryId, BooleanSupplier cancelled) {
        return sourceLabels.getStackSize() > 1
                ? measure3D(sourceLabels, targetLabels, domain, calibration,
                        firstTerritoryId, cancelled)
                : measure2D(sourceLabels, targetLabels, domain, calibration, firstTerritoryId);
    }

    // ------------------------------------------------------------------
    // Two dimensions: exact polygons, point in polygon.
    // ------------------------------------------------------------------

    private static TerritoryField measure2D(ImagePlus sourceLabels, ImagePlus targetLabels,
                                            List<Roi> domain, Calibration calibration,
                                            int firstTerritoryId) {
        double pixelWidth = positiveOrOne(calibration.pixelWidth);
        double pixelHeight = positiveOrOne(calibration.pixelHeight);
        List<SpatialObject2D> sources = LabelObjectExtractor.extract(
                calibratedPlane(sourceLabels, calibration), SOURCE_TYPE, firstTerritoryId);
        if (sources.isEmpty()) {
            return new TerritoryField(new ArrayList<Cell>(), 0, 0.0, null);
        }
        List<SpatialObject2D> targets = LabelObjectExtractor.extract(
                calibratedPlane(targetLabels, calibration), TARGET_TYPE,
                firstTerritoryId + sources.size());

        TerritoryResult tessellation = TerritoryEngine.analyze(
                sources,
                region2D(domain, sourceLabels.getWidth(), sourceLabels.getHeight(),
                        pixelWidth, pixelHeight),
                EDGE_POLICY);

        // Sorted here rather than taken on trust, because this order decides a
        // tie: a target centroid lying exactly on a cell wall is covered by both
        // cells, and the rule is that the lower territory id takes it.
        List<TerritoryCell> ordered =
                new ArrayList<TerritoryCell>(tessellation.getCells());
        Collections.sort(ordered, new Comparator<TerritoryCell>() {
            @Override
            public int compare(TerritoryCell first, TerritoryCell second) {
                return Integer.compare(
                        first.getObject().getIndex(), second.getObject().getIndex());
            }
        });

        int count = ordered.size();
        PreparedGeometry[] shapes = new PreparedGeometry[count];
        Cell[] owners = new Cell[count];
        double[] centreX = new double[count];
        double[] centreY = new double[count];
        Map<Integer, Cell> byTerritory = new LinkedHashMap<Integer, Cell>();
        double totalSize = 0.0;
        for (int i = 0; i < count; i++) {
            TerritoryCell cell = ordered.get(i);
            SpatialObject2D object = cell.getObject();
            // getGeometry() deep-copies the polygon on every call, so it is
            // called once per cell here and never inside the target loop below.
            // Preparing the copy builds the segment index that makes a few
            // thousand point-in-polygon tests against the same cell cheap.
            shapes[i] = PreparedGeometryFactory.prepare(cell.getGeometry());
            owners[i] = new Cell(label(object.getLabel()), object.getIndex(),
                    cell.getArea(), cell.isEdgeCell());
            centreX[i] = object.getCentroidX();
            centreY[i] = object.getCentroidY();
            byTerritory.put(Integer.valueOf(object.getIndex()), owners[i]);
            totalSize += cell.getArea();
        }

        int placed = 0;
        for (SpatialObject2D target : targets) {
            Point point = GEOMETRY.createPoint(
                    new Coordinate(target.getCentroidX(), target.getCentroidY()));
            for (int i = 0; i < count; i++) {
                // covers() rather than contains(): a point on the wall belongs
                // to a territory, and contains() calls the boundary outside.
                if (!shapes[i].covers(point)) {
                    continue;
                }
                owners[i].place(label(target.getLabel()), squared(
                        centreX[i] - target.getCentroidX(),
                        centreY[i] - target.getCentroidY(),
                        0.0));
                placed++;
                break;
            }
        }
        return new TerritoryField(order(sources, byTerritory), placed, totalSize, null);
    }

    // ------------------------------------------------------------------
    // Three dimensions: voxel raster, direct lookup.
    // ------------------------------------------------------------------

    private static TerritoryField measure3D(ImagePlus sourceLabels, ImagePlus targetLabels,
                                            List<Roi> domain, Calibration calibration,
                                            int firstTerritoryId,
                                            BooleanSupplier cancelled) {
        double pixelWidth = positiveOrOne(calibration.pixelWidth);
        double pixelHeight = positiveOrOne(calibration.pixelHeight);
        double pixelDepth = positiveOrOne(calibration.pixelDepth);
        List<SpatialObject3D> sources = LabelObjectExtractor3D.extract(
                calibratedStack(sourceLabels, calibration), SOURCE_TYPE, firstTerritoryId);
        if (sources.isEmpty()) {
            return new TerritoryField(new ArrayList<Cell>(), 0, 0.0, null);
        }
        List<SpatialObject3D> targets = LabelObjectExtractor3D.extract(
                calibratedStack(targetLabels, calibration), TARGET_TYPE,
                firstTerritoryId + sources.size());

        int width = sourceLabels.getWidth();
        int height = sourceLabels.getHeight();
        int depth = sourceLabels.getStackSize();
        TerritoryResult3D tessellation = TerritoryEngine3D.analyze(
                sources, region3D(domain, width, height, depth, calibration), EDGE_POLICY,
                cancelled);

        Map<Integer, Cell> byTerritory = new LinkedHashMap<Integer, Cell>();
        Map<Integer, SpatialObject3D> objectByTerritory =
                new LinkedHashMap<Integer, SpatialObject3D>();
        double totalSize = 0.0;
        for (TerritoryCell3D cell : tessellation.getCells()) {
            SpatialObject3D object = cell.getObject();
            byTerritory.put(Integer.valueOf(object.getIndex()),
                    new Cell(label(object.getLabel()), object.getIndex(),
                            cell.getVolume(), cell.isEdgeCell()));
            objectByTerritory.put(Integer.valueOf(object.getIndex()), object);
            totalSize += cell.getVolume();
        }

        ImagePlus territoryLabels = tessellation.getTerritoryLabels();
        ImageStack raster = territoryLabels.getStack();
        int placed = 0;
        try {
            float[][] planes = new float[depth][];
            for (int z = 0; z < depth; z++) {
                planes[z] = (float[]) raster.getPixels(z + 1);
            }
            for (SpatialObject3D target : targets) {
                // The same floor of the calibrated centroid the core used to
                // decide which voxel a source object sits in. Any other rounding
                // would disagree with the raster it is about to read.
                int x = (int) Math.floor(target.getCentroidX() / pixelWidth);
                int y = (int) Math.floor(target.getCentroidY() / pixelHeight);
                int z = (int) Math.floor(target.getCentroidZ() / pixelDepth);
                if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= depth) {
                    continue;
                }
                float voxel = planes[z][y * width + x];
                // Voxel value is the object index plus one, and zero means no
                // territory covers this voxel — outside the analysed field, or a
                // disconnected pocket of it holding no source object. Reading
                // zero as an id would hand every one of those targets to the
                // object whose index happens to be minus one from it.
                if (voxel <= 0.0f) {
                    continue;
                }
                Integer territory = Integer.valueOf((int) voxel - 1);
                Cell cell = byTerritory.get(territory);
                if (cell == null) {
                    // Unreachable against a sane raster: every positive voxel
                    // names an object the tessellation also returned a cell for.
                    // It throws rather than skipping because the alternative is
                    // a run that quietly drops target objects, and a count that
                    // is quietly short is worse than a run that stops.
                    throw new IllegalStateException("the territory raster names object "
                            + territory + ", which the tessellation did not report");
                }
                SpatialObject3D owner = objectByTerritory.get(territory);
                cell.place(label(target.getLabel()), squared(
                        owner.getCentroidX() - target.getCentroidX(),
                        owner.getCentroidY() - target.getCentroidY(),
                        owner.getCentroidZ() - target.getCentroidZ()));
                placed++;
            }
        } finally {
            // The core hands this raster over and says the caller owns it. A
            // batch runs one of these per direction per image, so a leak here is
            // four bytes a voxel accumulating until the run dies. close() only
            // unregisters the image; flush() is what releases the voxels.
            territoryLabels.close();
            territoryLabels.flush();
        }
        return new TerritoryField(order(sources, byTerritory), placed, totalSize, raster);
    }

    // ------------------------------------------------------------------
    // Shared.
    // ------------------------------------------------------------------

    /**
     * One cell per source object in ascending label order, including the objects
     * the tessellation dropped.
     *
     * <p>A source object whose centroid lies outside the analysed field gets no
     * territory, and it keeps its row carrying NaN rather than vanishing. A
     * table that silently loses rows when a region is applied is a table nobody
     * can reconcile against the label image it came from.
     */
    private static List<Cell> order(List<? extends TypedSpatialObject> sources,
                                    Map<Integer, Cell> byTerritory) {
        List<Cell> ordered = new ArrayList<Cell>(sources.size());
        for (TypedSpatialObject object : sources) {
            Cell cell = byTerritory.get(Integer.valueOf(object.getIndex()));
            ordered.add(cell != null ? cell
                    : new Cell(label(object.getLabel()), NO_TERRITORY, Double.NaN, false));
        }
        Collections.sort(ordered, new Comparator<Cell>() {
            @Override
            public int compare(Cell first, Cell second) {
                return Integer.compare(first.sourceLabel(), second.sourceLabel());
            }
        });
        return ordered;
    }

    private static SpatialRegion2D region2D(List<Roi> domain, int width, int height,
                                            double pixelWidth, double pixelHeight) {
        List<Roi> rois = domain.isEmpty()
                ? Collections.singletonList(new Roi(0, 0, width, height))
                : domain;
        // UNION, so this always returns exactly one region. Independent regions
        // are a v0.2.0 question: they would multiply every result shape by a
        // region axis that EngineResult does not have.
        return RegionFactory.create(rois, RegionMode.UNION, pixelWidth, pixelHeight,
                width, height).get(0);
    }

    private static RegionMask3D region3D(List<Roi> domain, int width, int height, int depth,
                                         Calibration calibration) {
        ImageStack mask = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            ByteProcessor plane = new ByteProcessor(width, height);
            plane.setValue(1.0);
            if (domain.isEmpty()) {
                plane.fill();
            } else {
                for (Roi roi : domain) {
                    plane.fill(roi);
                }
            }
            mask.addSlice(plane);
        }
        ImagePlus image = new ImagePlus("Territory_Field", mask);
        image.setCalibration(calibration);
        return RegionMaskFactory3D.create(image, RegionMode.UNION).get(0);
    }

    /**
     * The label image seen through the run's calibration.
     *
     * <p>The core reads pixel size off the {@code ImagePlus} it is handed, while
     * the authoritative calibration is the one {@code EngineInputs} assembled and
     * validated. Wrapping shares the pixels and overrides nothing else, so the
     * caller's image is not touched — engines must treat their inputs as
     * read-only, and a run where one channel carried a stale calibration would
     * otherwise tessellate in one coordinate system and locate in another.
     */
    private static ImagePlus calibratedPlane(ImagePlus image, Calibration calibration) {
        ImagePlus view = new ImagePlus(image.getTitle(), image.getProcessor());
        view.setCalibration(calibration);
        return view;
    }

    private static ImagePlus calibratedStack(ImagePlus image, Calibration calibration) {
        ImagePlus view = new ImagePlus(image.getTitle(), image.getStack());
        view.setCalibration(calibration);
        return view;
    }

    private static double squared(double dx, double dy, double dz) {
        return dx * dx + dy * dy + dz * dz;
    }

    /** An unset or nonsensical calibration means pixels, not a zero-scaled axis. */
    private static double positiveOrOne(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) || value <= 0.0 ? 1.0 : value;
    }

    private static int label(long value) {
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("label " + value
                    + " is larger than an object score can carry");
        }
        return (int) value;
    }
}
