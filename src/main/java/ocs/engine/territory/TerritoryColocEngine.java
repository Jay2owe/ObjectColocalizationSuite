package ocs.engine.territory;

import ocs.engine.ColocEngine;
import ocs.engine.ColumnSpec;
import ocs.engine.DirectionKey;
import ocs.engine.EngineCancelledException;
import sc.fiji.territories.core.ComputationCancelledException;
import ocs.engine.EngineFamily;
import ocs.engine.EngineInputs;
import ocs.engine.EngineProgress;
import ocs.engine.EngineResult;
import ocs.engine.InputRequirement;
import ocs.engine.ObjectScore;
import ocs.engine.ScaleKind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.Set;

/**
 * Territory colocalization — how many objects of the target channel live inside
 * each source object's share of the field.
 *
 * <p>Think of it as drawing borders. Every source object claims the ground that
 * is closer to it than to any other source object, the way a set of shops
 * divides a town into catchment areas. That partition — a Voronoi tessellation —
 * covers the whole field with one territory per source object and no gaps. The
 * question this engine answers is then simply: whose catchment did each target
 * object turn up in, and was that more than its fair share?
 *
 * <p>It is the one method in the suite that reports something for objects which
 * neither touch nor lie near each other. Overlap, containment and Jaccard all
 * collapse to zero once two objects stop sharing voxels; distance tolerance
 * survives that but only sees the single nearest partner. A microglial cell
 * surveying a domain that happens to contain six plaques is a finding that every
 * other method in the suite reports as "no colocalization".
 *
 * <h2>The measure, and why it is a ratio</h2>
 *
 * <p>The obvious number — what fraction of the target objects landed in this
 * territory — is unusable on its own, for two reasons that pull the same way.
 * It shrinks as the source channel gets denser, so it cannot be compared between
 * a field of ten source objects and a field of a hundred. And it rewards a
 * source object for having a large territory, which usually means it was
 * isolated rather than that it attracted anything.
 *
 * <p>So the primary column divides the observed count by the count the territory
 * would hold if the target objects were spread evenly over the tessellated
 * field. One is chance. Two is twice chance. Both nuisances cancel: territory
 * size is in the denominator, and the counts sum to the same total either way.
 *
 * <p>The ingredients ride alongside as their own columns, because a ratio with
 * an unstated denominator is not reportable — 3.0 built from three objects
 * against an expectation of one is a different claim from 300 against 100, and a
 * reader must be able to see which they have.
 *
 * <h2>Direction</h2>
 *
 * <p>Directional, and more strongly so than the overlap measures. Those at least
 * measure the same intersection from two ends. Here the two directions do not
 * share a tessellation at all: A→B partitions the field on A's objects, B→A
 * partitions it on B's, and the two partitions have different cell counts,
 * different shapes and different sizes. Reading one for the other is not an
 * inversion, it is a different experiment.
 *
 * @see TerritoryField for everything that touches {@code territories-core}
 */
public final class TerritoryColocEngine implements ColocEngine {

    private static final ColumnSpec OCCUPANCY = ColumnSpec.primary(
            "Territory Occupancy", "",
            "Target objects found in this object's territory divided by the number "
                    + "expected if the target objects were spread evenly over the "
                    + "tessellated field; 1.0 is chance, 2.0 is twice chance",
            ScaleKind.UNBOUNDED);

    private static final ColumnSpec PARTNER = ColumnSpec.partner(
            "Territory Partner",
            "Label of the target object nearest this object's centroid among those "
                    + "inside its territory, or 0 when the territory holds none");

    private static final ColumnSpec TARGETS = ColumnSpec.of(
            "Targets In Territory", "",
            "How many target objects have their centroid inside this object's territory",
            ScaleKind.COUNT);

    private static final ColumnSpec EXPECTED = ColumnSpec.of(
            "Expected Targets In Territory", "",
            "Target objects this territory would hold at even density: the placed "
                    + "target objects times this territory's share of the field",
            ScaleKind.COUNT);

    private static final ColumnSpec SHARE = ColumnSpec.of(
            "Target Share", "",
            "Fraction of all placed target objects that fell in this object's territory",
            ScaleKind.FRACTION);

    private static final ColumnSpec SIZE = ColumnSpec.of(
            "Territory Size", "image units",
            "Area in 2D or volume in 3D of this object's territory; calibrated units "
                    + "where the image is calibrated, pixels or voxels where it is not",
            ScaleKind.VOLUME);

    private static final ColumnSpec TERRITORY_ID = ColumnSpec.of(
            "Territory Id", "",
            "Identifier of this object's territory, matching the territory tables "
                    + "the Object Territories plugin writes for the same image",
            ScaleKind.COUNT);

    private static final ColumnSpec EDGE = ColumnSpec.of(
            "Territory Touches Field Edge", "",
            "1 when this territory runs into the edge of the analysed field, so its "
                    + "size was decided by where the image stops rather than by "
                    + "neighbouring objects, and its occupancy is correspondingly soft",
            ScaleKind.BINARY);

    @Override
    public String id() {
        return "territory-occupancy";
    }

    @Override
    public String displayName() {
        return "Territory occupancy (Voronoi)";
    }

    @Override
    public EngineFamily family() {
        // Its own family rather than SPATIAL, settled 2026-08-12. A Voronoi
        // tessellation is a spatial construction, but this engine emits one row
        // per object and no curve, and the spatial family emits a curve and no
        // per-object row. Sharing a dialog section and a preset with engines
        // that produce a different shape of answer helps nobody.
        return EngineFamily.TERRITORY;
    }

    @Override
    public Set<InputRequirement> requires() {
        // Label images only. A region and a calibration are both used when
        // present and both degrade honestly when absent: no region means the
        // whole field, no calibration means pixels. Requiring either would grey
        // the method out for most label images to no benefit.
        return Collections.singleton(InputRequirement.LABEL_IMAGES);
    }

    @Override
    public List<ColumnSpec> columns() {
        return Arrays.asList(OCCUPANCY, PARTNER, TARGETS, EXPECTED, SHARE, SIZE,
                TERRITORY_ID, EDGE);
    }

    @Override
    public boolean isSymmetric() {
        // Not even close. The two directions tessellate different channels, so
        // they do not agree on how many territories exist, let alone on what is
        // in them. See the class javadoc.
        return false;
    }

    @Override
    public double relativeCost() {
        // Priced on the 3D path, which is the one that matters: it walks every
        // voxel to grow the territories, runs a nearest-centroid query at each
        // one, walks them again to measure, and materialises a 32-bit label
        // stack. That is tens of passes over the voxels, not one. The 2D path is
        // far cheaper — extraction and then geometry over object counts — but a
        // single number has to cover both, and understating the expensive path
        // is what makes a pre-run estimate worthless. An order of magnitude
        // above the object engines, an order below Costes randomization.
        return 25.0;
    }

    @Override
    public EngineResult compute(EngineInputs inputs, EngineProgress progress) {
        if (progress.isCancelled()) {
            throw new EngineCancelledException(id());
        }
        progress.report("Territory occupancy", 0.0);

        EngineResult.Builder result = EngineResult.forEngine(id());
        List<DirectionKey> directions = inputs.allDirections();
        for (int d = 0; d < directions.size(); d++) {
            if (progress.isCancelled()) {
                throw new EngineCancelledException(id());
            }
            DirectionKey direction = directions.get(d);
            // Tessellated per direction rather than cached per source channel.
            // The cache would save real time at five channels and would hold up
            // to five 32-bit label stacks of the whole volume at once, which on
            // a routine confocal stack is a quarter of a gigabyte. Time is the
            // cheaper thing to spend.
            TerritoryField field;
            try {
                // Escape reaches inside the 3D assignment, which on a large
                // stack is most of the run; before territories-core 0.2.1 it
                // was only seen between directions.
                field = TerritoryField.measure(
                        inputs.labelImages().get(direction.sourceIndex()),
                        inputs.labelImages().get(direction.targetIndex()),
                        inputs.domain(),
                        inputs.calibration(),
                        cancellation(progress));
            } catch (ComputationCancelledException cancelled) {
                throw new EngineCancelledException(id());
            }
            report(result, direction, field);
            progress.report("Territory occupancy", (d + 1.0) / directions.size());
        }
        return result.build();
    }

    /** The progress reporter's cancel flag, in the form the core polls. */
    private static BooleanSupplier cancellation(final EngineProgress progress) {
        return new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return progress.isCancelled();
            }
        };
    }

    private void report(EngineResult.Builder result, DirectionKey direction,
                        TerritoryField field) {
        List<TerritoryField.Cell> cells = field.cells();
        int count = cells.size();
        List<ObjectScore> scores = new ArrayList<ObjectScore>(count);
        double[] targets = new double[count];
        double[] expected = new double[count];
        double[] share = new double[count];
        double[] size = new double[count];
        double[] territoryId = new double[count];
        double[] edge = new double[count];

        for (int i = 0; i < count; i++) {
            TerritoryField.Cell cell = cells.get(i);
            double occupancy = Double.NaN;
            if (cell.hasTerritory()) {
                targets[i] = cell.targetCount();
                expected[i] = field.totalSize() > 0.0
                        ? field.placedTargets() * cell.size() / field.totalSize()
                        : Double.NaN;
                share[i] = field.placedTargets() > 0
                        ? (double) cell.targetCount() / field.placedTargets()
                        : Double.NaN;
                size[i] = cell.size();
                territoryId[i] = cell.territoryId();
                edge[i] = cell.isEdgeCell() ? 1.0 : 0.0;
                // An empty target channel, or a field with no measurable size,
                // leaves this undefined rather than infinite or zero. The
                // generic layers skip NaN; a zero here would read as "no
                // enrichment", which is a claim about data that does not exist.
                occupancy = expected[i] > 0.0 ? cell.targetCount() / expected[i] : Double.NaN;
            } else {
                // A source object whose centroid fell outside the analysed
                // region. It keeps its row, and every measured column says so.
                targets[i] = Double.NaN;
                expected[i] = Double.NaN;
                share[i] = Double.NaN;
                size[i] = Double.NaN;
                territoryId[i] = Double.NaN;
                edge[i] = Double.NaN;
            }
            // Coincident means the territory holds more target objects than
            // chance would put there. "Holds at least one" was the alternative
            // and is useless: in any field dense enough to be interesting it is
            // true for nearly every object, and a flag that is nearly always
            // true carries no information into the agreement layer. NaN
            // compares false, which is the intended answer for an undefined row.
            scores.add(new ObjectScore(cell.sourceLabel(), cell.partnerLabel(),
                    occupancy, occupancy > 1.0));
        }

        result.direction(direction, scores);
        result.supporting(direction, TARGETS.name(), targets);
        result.supporting(direction, EXPECTED.name(), expected);
        result.supporting(direction, SHARE.name(), share);
        result.supporting(direction, SIZE.name(), size);
        result.supporting(direction, TERRITORY_ID.name(), territoryId);
        result.supporting(direction, EDGE.name(), edge);
    }
}
