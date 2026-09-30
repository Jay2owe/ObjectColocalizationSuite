package ocs.engine;

import ij.ImagePlus;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Proves the registry contract carries a real engine.
 *
 * <p>The fixture is deliberately the asymmetric case, because direction is the
 * thing object colocalization is most often misread on. Channel A is a 10x10
 * square (100 voxels); channel B is a 2x2 square (4 voxels) sitting entirely
 * inside it. They share 4 voxels, so:
 *
 * <pre>
 *   A -> B :   4/100 =   4%   "4% of A overlaps B"
 *   B -> A :   4/4   = 100%   "all of B is inside A"
 * </pre>
 *
 * Same shared voxels, same objects, two answers an order of magnitude apart.
 * Any engine that reported one number for this pair would be hiding the finding.
 */
public class EngineRegistryTest {

    private static final int SIZE = 10;

    /** Every id the object family ships, in registration order. */
    private static final List<String> OBJECT_ENGINE_IDS = Arrays.asList(
            "cpc", "distance-tolerance", "volume-overlap",
            "jaccard-dice", "containment", "bounding-box");

    /** Every id the intensity family ships, in registration order. */
    private static final List<String> INTENSITY_ENGINE_IDS = Arrays.asList(
            "per-object-intensity", "whole-image-intensity");

    /** Every id the spatial family ships, in registration order. */
    private static final List<String> SPATIAL_ENGINE_IDS = Arrays.asList(
            "cross-g", "cross-k", "cross-l", "cross-pair-correlation");

    /** Every id the territory family ships, in registration order. */
    private static final List<String> TERRITORY_ENGINE_IDS = Arrays.asList(
            "territory-occupancy");

    @Test
    public void defaultRegistryHoldsEveryShippedEngine() {
        EngineRegistry registry = EngineRegistry.createDefault();
        for (String id : OBJECT_ENGINE_IDS) {
            assertTrue("missing engine " + id, registry.has(id));
        }
        for (String id : INTENSITY_ENGINE_IDS) {
            assertTrue("missing engine " + id, registry.has(id));
        }
        for (String id : SPATIAL_ENGINE_IDS) {
            assertTrue("missing engine " + id, registry.has(id));
        }
        for (String id : TERRITORY_ENGINE_IDS) {
            assertTrue("missing engine " + id, registry.has(id));
        }
        assertEquals(OBJECT_ENGINE_IDS.size(),
                registry.family(EngineFamily.OBJECT).size());
        assertEquals(INTENSITY_ENGINE_IDS.size(),
                registry.family(EngineFamily.INTENSITY).size());
        assertEquals(SPATIAL_ENGINE_IDS.size(),
                registry.family(EngineFamily.SPATIAL).size());
        assertEquals(TERRITORY_ENGINE_IDS.size(),
                registry.family(EngineFamily.TERRITORY).size());
        // Every family accounted for, so a fifth constant added without a list
        // above fails here rather than leaving its engines untested.
        assertEquals(EngineFamily.values().length, 4);
        // Total last, so a new engine added without a family assertion above
        // fails here rather than slipping in unnoticed.
        assertEquals(OBJECT_ENGINE_IDS.size() + INTENSITY_ENGINE_IDS.size()
                        + SPATIAL_ENGINE_IDS.size() + TERRITORY_ENGINE_IDS.size(),
                registry.size());
    }

    /**
     * The fixture carries label images only, so anything needing raw intensity
     * or a domain must be greyed out rather than offered and then failing after
     * the user presses Run.
     *
     * <p>Stated as the property rather than as a count. This used to assert
     * "runnable is exactly the object family", which was true only while the
     * object family happened to be the whole of what runs on labels alone; the
     * territory engine needs nothing else either, and a count assertion would
     * have read that correct behaviour as a regression. What actually matters is
     * that the offered set and the satisfiable set are the same set — offering
     * one engine too many is a run that dies after the user commits to it, and
     * one too few is a method silently missing from their options.
     */
    @Test
    public void onlyEnginesWhoseInputsAreSatisfiedAreOffered() {
        EngineRegistry registry = EngineRegistry.createDefault();
        EngineInputs labelsOnly = containedPairInputs();
        List<ColocEngine> runnable = registry.runnableWith(labelsOnly);

        for (ColocEngine engine : registry.all()) {
            boolean needsMoreThanLabels =
                    engine.requires().contains(InputRequirement.INTENSITY_IMAGES)
                            || engine.requires().contains(InputRequirement.ROI_DOMAIN);
            assertEquals(engine.id() + " offered=" + runnable.contains(engine)
                            + " but requires " + engine.requires(),
                    !needsMoreThanLabels, runnable.contains(engine));
        }

        // The original point of this test, kept explicit: no engine that reads
        // raw intensity may be offered against label images.
        for (ColocEngine engine : runnable) {
            assertFalse("the intensity family cannot run on labels alone",
                    engine.family() == EngineFamily.INTENSITY);
        }
        assertFalse("fixture must leave something runnable", runnable.isEmpty());
    }

    @Test
    public void everyEngineDeclaresExactlyOnePrimaryColumnWithAScale() {
        for (ColocEngine engine : EngineRegistry.createDefault().all()) {
            int primaries = 0;
            for (ColumnSpec column : engine.columns()) {
                if (column.isPrimary()) {
                    primaries++;
                }
                assertTrue("column " + column.name() + " of " + engine.id()
                        + " must declare a scale", column.scale() != null);
            }
            assertEquals(engine.id() + " must have exactly one primary column",
                    1, primaries);
        }
    }

    @Test
    public void volumeOverlapReportsTheAsymmetryInBothDirections() {
        EngineInputs inputs = containedPairInputs();
        EngineResult result = EngineRegistry.createDefault()
                .byId("volume-overlap")
                .compute(inputs, EngineProgress.SILENT);

        assertEquals("both ordered directions must be reported",
                2, result.directions().size());

        ObjectScore aToB = onlyScore(result, "A", "B");
        ObjectScore bToA = onlyScore(result, "B", "A");

        assertEquals("4 of A's 100 voxels are shared", 4.0, aToB.value(), 1e-9);
        assertEquals("all 4 of B's voxels are shared", 100.0, bToA.value(), 1e-9);

        assertEquals(1, aToB.partnerLabel());
        assertEquals(1, bToA.partnerLabel());

        // At the 30% default threshold the containment is called one way only.
        assertFalse("4% is below the 30% threshold", aToB.isCoincident());
        assertTrue("100% clears the 30% threshold", bToA.isCoincident());
    }

    @Test
    public void centroidCoincidenceProducesBinaryValuesInBothDirections() {
        EngineInputs inputs = containedPairInputs();
        EngineResult result = EngineRegistry.createDefault()
                .byId("cpc")
                .compute(inputs, EngineProgress.SILENT);

        assertEquals(2, result.directions().size());
        for (DirectionKey direction : result.directions()) {
            for (ObjectScore score : result.scores(direction)) {
                double value = score.value();
                assertTrue("centroid coincidence is binary, got " + value,
                        value == 0.0 || value == 1.0);
                assertEquals("the flag and the value must agree",
                        score.isCoincident(), value == 1.0);
            }
        }
    }

    @Test
    public void costEstimateChargesEveryEngineForEveryDirectionItComputes() {
        EngineInputs inputs = containedPairInputs();
        List<ColocEngine> all = EngineRegistry.createDefault().all();

        // Two channels => two ordered pairs, and every engine computes both,
        // symmetric or not. Asserting a symmetric engine is registered keeps this
        // from passing vacuously if the halving ever comes back for them only.
        int symmetric = 0;
        for (ColocEngine engine : all) {
            if (engine.isSymmetric()) {
                symmetric++;
            }
            assertEquals(engine.id(), engine.relativeCost() * 2,
                    EngineRegistry.estimateCost(Arrays.asList(engine), inputs), 1e-9);
        }
        assertTrue("no symmetric engine is registered", symmetric > 0);
    }

    @Test
    public void costEstimateOrdersJaccardAboveCentroidCoincidenceAsMeasured() {
        // SuiteBenchmarkTest, README "How long a real study takes": at 256 x 256 x 8
        // Jaccard/Dice took 16 ms and centroid coincidence 11 ms. Halving the
        // symmetric Jaccard/Dice put its estimate (1.6) below centroid
        // coincidence's (2.0), the opposite of what was measured.
        EngineInputs inputs = containedPairInputs();
        EngineRegistry registry = EngineRegistry.createDefault();
        double jaccard = EngineRegistry.estimateCost(
                Arrays.asList(registry.byId("jaccard-dice")), inputs);
        double centroid = EngineRegistry.estimateCost(
                Arrays.asList(registry.byId("cpc")), inputs);
        assertTrue(jaccard + " vs " + centroid, jaccard > centroid);
    }

    @Test(expected = IllegalArgumentException.class)
    public void mismatchedImageDimensionsAreRejectedAtInputAssembly() {
        // Would not throw on its own — it would read the wrong voxel and report
        // a confident wrong number. That is why it is validated once, up front.
        EngineInputs.builder(Arrays.asList(
                labelImage("A", 0, SIZE, 1),
                new ImagePlus("B", new ShortProcessor(SIZE + 4, SIZE)))).build();
    }

    /** Channel A: the full 10x10 square. Channel B: a 2x2 square inside it. */
    private static EngineInputs containedPairInputs() {
        return EngineInputs.builder(Arrays.asList(
                        labelImage("A", 0, SIZE, 1),
                        labelImage("B", 4, 6, 1)))
                .channelNames(Arrays.asList("A", "B"))
                .build();
    }

    /** Single-slice label image with label {@code value} filling [from, to). */
    private static ImagePlus labelImage(String title, int from, int to, int value) {
        ShortProcessor processor = new ShortProcessor(SIZE, SIZE);
        for (int y = from; y < to; y++) {
            for (int x = from; x < to; x++) {
                processor.set(x, y, value);
            }
        }
        return new ImagePlus(title, processor);
    }

    private static ObjectScore onlyScore(EngineResult result, String source, String target) {
        for (DirectionKey direction : result.directions()) {
            if (direction.sourceName().equals(source)
                    && direction.targetName().equals(target)) {
                List<ObjectScore> scores = result.scores(direction);
                assertEquals("fixture has exactly one object per channel",
                        1, scores.size());
                return scores.get(0);
            }
        }
        throw new AssertionError("no direction " + source + " -> " + target);
    }
}
