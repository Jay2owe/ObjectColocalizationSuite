package ocs.reference;

import ij.ImagePlus;
import ij.ImageStack;
import ocs.OCS;
import ocs.OCSParameters;
import ocs.OCSResult;
import ocs.engine.DirectionKey;
import ocs.engine.EngineResult;
import ocs.engine.ObjectScore;
import org.junit.BeforeClass;
import org.junit.Test;
import sc.fiji.cpc.core.CentroidCoincidence;
import sc.fiji.oc3d.core.measure.CentroidScan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * The same label images, measured two ways: by this suite, and by an oracle
 * written here from the geometry.
 *
 * <p>The build plan calls this the cross-plugin agreement test, and its
 * reasoning is that a suite disagreeing with its own siblings is unshippable —
 * a user who runs CPC and then runs this expects the same objects called
 * colocalized, and if the numbers differ one of the two is wrong. Both
 * centroid coincidence and volumetric overlap in this suite are adapters over
 * the cores those two plugins ship ({@code cpc-core} and
 * {@code volcoloc-core}), so the arithmetic is shared by construction and the
 * only place the two can diverge is the adapter: which channel became the
 * source, whether a direction was transposed on the way back, whether a label
 * was matched by position instead of by number.
 *
 * <h2>Why the oracle is written here rather than borrowed</h2>
 *
 * <p>Comparing the suite's answer against the core's answer would check the
 * adapter but not the arithmetic, because both sides would be the same code. So
 * the oracle below counts voxels and averages coordinates directly, in a plain
 * nested loop over the two label stacks — the slowest possible implementation,
 * chosen because it shares nothing with what it checks. On a fixture this size
 * its cost is irrelevant.
 *
 * <p>{@link #centroidCoincidenceMatchesTheCoreCalledTheWayCpcCallsIt()} then adds
 * the other half: the suite's answer against {@code cpc-core} driven the way
 * {@code CPCAnalysis.testCoincidence} drives it, object by object. That one is
 * the literal question — would CPC print this — and the oracle is the question
 * behind it, which is whether either of them is right.
 */
public class CrossPluginAgreementTest {

    private static final double EXACT = 1.0e-12;

    @BeforeClass
    public static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    // ==================================================================
    // Volumetric overlap — against Volumetric Colocalization's measure
    // ==================================================================

    /**
     * Every object's overlap percentage, recomputed by counting voxels here, on
     * every case in the reference dataset and in both directions.
     *
     * <p>Both directions matters more than it looks. The percentage's denominator
     * is the source object's own volume, so a transposed direction changes only
     * the denominator — the shared voxel count is the same either way, and a
     * table built from a swapped pair is entirely plausible until something
     * counts the voxels independently.
     */
    @Test
    public void volumetricOverlapMatchesAVoxelCountDoneHere() {
        List<ReferenceDataset.Case> cases = ReferenceDataset.all();
        for (int c = 0; c < cases.size(); c++) {
            ReferenceDataset.Case reference = cases.get(c);
            ImagePlus a = reference.labelImages().get(0);
            ImagePlus b = reference.labelImages().get(1);

            OCSResult result = OCS.run(OCSParameters.builder(reference.labelImages())
                    .methods("volume-overlap")
                    .sourceName(reference.name())
                    .build());
            EngineResult overlap = result.resultFor("volume-overlap");

            assertOverlapMatches(reference.name(), overlap, 0, 1, a, b);
            assertOverlapMatches(reference.name(), overlap, 1, 0, b, a);
        }
    }

    private static void assertOverlapMatches(String caseName, EngineResult overlap,
            int source, int target, ImagePlus sourceLabels, ImagePlus targetLabels) {
        Oracle oracle = Oracle.over(sourceLabels, targetLabels);
        List<ObjectScore> scores = overlap.scores(direction(overlap, source, target));

        assertEquals(caseName + ": the suite reports a different number of objects "
                        + "than the label image holds, direction " + source + "->" + target,
                oracle.sourceLabels().size(), scores.size());

        for (int i = 0; i < scores.size(); i++) {
            ObjectScore score = scores.get(i);
            int label = score.sourceLabel();
            String where = caseName + " object " + label + ", direction "
                    + source + "->" + target;

            // The percentage counts this object's voxels lying inside *any*
            // target object, over its own volume.
            double expected = 100.0 * oracle.sharedWithAnyTarget(label)
                    / oracle.voxelCount(label);
            assertEquals(where, expected, score.value(), EXACT);

            // The partner is the target object sharing the most voxels, and 0
            // where nothing is shared at all.
            assertEquals(where + ": partner", oracle.strongestPartner(label),
                    score.partnerLabel());

            double sharedVoxels = overlap.supporting(
                    direction(overlap, source, target), "Overlap Voxels")[i];
            assertEquals(where + ": shared voxels with the strongest partner",
                    oracle.sharedWithStrongestPartner(label), sharedVoxels, EXACT);
        }
    }

    // ==================================================================
    // Centroid coincidence — against CPC's measure
    // ==================================================================

    /**
     * Every object's centroid computed here as the mean of its voxel
     * coordinates, rounded to the nearest voxel, and looked up in the target
     * label image.
     *
     * <p>{@link ReferenceDataset} gives every cuboid an odd side length so that
     * every centroid lands exactly on a voxel. That is deliberate: at a centroid
     * of 9.5 this test would be asserting a rounding convention rather than a
     * geometric fact, and a change to that convention would read as a
     * correctness regression when it is nothing of the kind.
     */
    @Test
    public void centroidCoincidenceMatchesACentroidComputedHere() {
        List<ReferenceDataset.Case> cases = ReferenceDataset.all();
        for (int c = 0; c < cases.size(); c++) {
            ReferenceDataset.Case reference = cases.get(c);
            ImagePlus a = reference.labelImages().get(0);
            ImagePlus b = reference.labelImages().get(1);

            OCSResult result = OCS.run(OCSParameters.builder(reference.labelImages())
                    .methods("cpc")
                    .sourceName(reference.name())
                    .build());
            EngineResult cpc = result.resultFor("cpc");

            assertCoincidenceMatches(reference.name(), cpc, 0, 1, a, b);
            assertCoincidenceMatches(reference.name(), cpc, 1, 0, b, a);
        }
    }

    private static void assertCoincidenceMatches(String caseName, EngineResult cpc,
            int source, int target, ImagePlus sourceLabels, ImagePlus targetLabels) {
        Oracle oracle = Oracle.over(sourceLabels, targetLabels);
        List<ObjectScore> scores = cpc.scores(direction(cpc, source, target));

        for (int i = 0; i < scores.size(); i++) {
            ObjectScore score = scores.get(i);
            int label = score.sourceLabel();
            String where = caseName + " object " + label + ", direction "
                    + source + "->" + target;

            int expected = oracle.labelUnderCentroid(label);
            assertEquals(where + ": partner", expected, score.partnerLabel());
            assertEquals(where + ": coincident", expected == 0 ? 0.0 : 1.0,
                    score.value(), EXACT);
        }
    }

    /**
     * The same objects again, this time against {@code cpc-core} driven exactly
     * as {@code CPCAnalysis.testCoincidence} drives it: scan the source channel
     * for centroids, then ask {@code CentroidCoincidence.labelAt} what sits at
     * each one.
     *
     * <p>This is the literal cross-plugin question. The suite's engine goes
     * through {@code PairwiseCoincidenceRunner}, which is a different entry point
     * into the same core, so this pins that the two entry points agree — and with
     * the oracle above it pins that they agree on the right answer rather than
     * merely with each other.
     *
     * <p>One thing it deliberately does not check: CPC pins {@code cpc-core}
     * 0.1.0 while this suite pins 0.2.0. A test cannot hold two versions of one
     * class on a flat classpath, so what runs here is 0.2.0 on both sides. If the
     * coincidence rule ever changes between core versions, this test will not
     * see it, and the sibling plugins will disagree in the field until CPC
     * upgrades. That is a release-coordination fact, recorded here because the
     * test would otherwise imply it had been checked.
     */
    @Test
    public void centroidCoincidenceMatchesTheCoreCalledTheWayCpcCallsIt() {
        List<ReferenceDataset.Case> cases = ReferenceDataset.all();
        for (int c = 0; c < cases.size(); c++) {
            ReferenceDataset.Case reference = cases.get(c);
            ImagePlus a = reference.labelImages().get(0);
            ImagePlus b = reference.labelImages().get(1);

            OCSResult result = OCS.run(OCSParameters.builder(reference.labelImages())
                    .methods("cpc")
                    .sourceName(reference.name())
                    .build());
            EngineResult cpc = result.resultFor("cpc");

            assertMatchesCpcCallSequence(reference.name(), cpc, 0, 1, a, b);
            assertMatchesCpcCallSequence(reference.name(), cpc, 1, 0, b, a);
        }
    }

    private static void assertMatchesCpcCallSequence(String caseName,
            EngineResult cpc, int source, int target, ImagePlus sourceLabels,
            ImagePlus targetLabels) {
        // CPCAnalysis.extractObjects, then CPCAnalysis.testCoincidence.
        CentroidScan.Result centroids = CentroidScan.scan(sourceLabels, null);
        Map<Integer, Integer> partnerByLabel = new HashMap<Integer, Integer>();
        List<CentroidScan.Centroid> objects = centroids.centroids();
        for (int i = 0; i < objects.size(); i++) {
            CentroidScan.Centroid centroid = objects.get(i);
            partnerByLabel.put(Integer.valueOf(centroid.label()),
                    Integer.valueOf(CentroidCoincidence.labelAt(targetLabels,
                            centroid.x(), centroid.y(), centroid.z())));
        }

        List<ObjectScore> scores = cpc.scores(direction(cpc, source, target));
        assertEquals(caseName + ": the two routes see different object counts, "
                        + "direction " + source + "->" + target,
                partnerByLabel.size(), scores.size());

        for (int i = 0; i < scores.size(); i++) {
            ObjectScore score = scores.get(i);
            Integer expected = partnerByLabel.get(Integer.valueOf(score.sourceLabel()));
            if (expected == null) {
                fail(caseName + ": the suite reports object " + score.sourceLabel()
                        + " which the centroid scan never saw");
            }
            assertEquals(caseName + " object " + score.sourceLabel()
                            + ", direction " + source + "->" + target,
                    expected.intValue(), score.partnerLabel());
        }
    }

    // ==================================================================
    // The negative control
    // ==================================================================

    /**
     * The oracle must be able to fail. Fed the wrong direction on the one case
     * built to read differently each way, it has to disagree — otherwise every
     * assertion above passes because the oracle agrees with anything.
     */
    @Test
    public void theOracleDisagreesWhenTheDirectionIsTransposed() {
        ReferenceDataset.Case nested = ReferenceDataset.nested();
        ImagePlus a = nested.labelImages().get(0);
        ImagePlus b = nested.labelImages().get(1);

        OCSResult result = OCS.run(OCSParameters.builder(nested.labelImages())
                .methods("volume-overlap")
                .sourceName(nested.name())
                .build());
        EngineResult overlap = result.resultFor("volume-overlap");

        // The forward direction's rows against the reversed oracle: 100% read
        // against an oracle that says 7.87%.
        Oracle transposed = Oracle.over(b, a);
        double reported = overlap.scores(direction(overlap, 0, 1)).get(0).value();
        double wrongWayRound = 100.0 * transposed.sharedWithAnyTarget(1)
                / transposed.voxelCount(1);

        if (Math.abs(reported - wrongWayRound) < 1.0) {
            fail("the oracle gives the same answer with the channels swapped, so "
                    + "it cannot detect a transposed direction");
        }
    }

    // ------------------------------------------------------------------

    private static DirectionKey direction(EngineResult result, int source, int target) {
        List<DirectionKey> directions = result.directions();
        for (int i = 0; i < directions.size(); i++) {
            DirectionKey key = directions.get(i);
            if (key.sourceIndex() == source && key.targetIndex() == target) {
                return key;
            }
        }
        fail(result.engineId() + " has no direction " + source + "->" + target);
        return null;
    }

    /**
     * The independent measure: voxel counts, per-pair shared counts and
     * centroids, read straight off the two stacks with nothing shared with the
     * code under test.
     */
    private static final class Oracle {

        private final Map<Integer, Integer> voxelCounts =
                new HashMap<Integer, Integer>();
        private final Map<Integer, Integer> sharedWithAny =
                new HashMap<Integer, Integer>();
        private final Map<Integer, Map<Integer, Integer>> sharedByPartner =
                new HashMap<Integer, Map<Integer, Integer>>();
        private final Map<Integer, double[]> centroidSums =
                new HashMap<Integer, double[]>();
        private final ImagePlus targetLabels;

        private Oracle(ImagePlus targetLabels) {
            this.targetLabels = targetLabels;
        }

        static Oracle over(ImagePlus sourceLabels, ImagePlus targetLabels) {
            Oracle oracle = new Oracle(targetLabels);
            ImageStack source = sourceLabels.getStack();
            ImageStack target = targetLabels.getStack();

            for (int z = 0; z < source.getSize(); z++) {
                for (int y = 0; y < sourceLabels.getHeight(); y++) {
                    for (int x = 0; x < sourceLabels.getWidth(); x++) {
                        int label = (int) source.getProcessor(z + 1).getf(x, y);
                        if (label == 0) {
                            continue;
                        }
                        Integer key = Integer.valueOf(label);
                        increment(oracle.voxelCounts, key);

                        double[] sums = oracle.centroidSums.get(key);
                        if (sums == null) {
                            sums = new double[3];
                            oracle.centroidSums.put(key, sums);
                        }
                        sums[0] += x;
                        sums[1] += y;
                        sums[2] += z;

                        int partner = (int) target.getProcessor(z + 1).getf(x, y);
                        if (partner == 0) {
                            continue;
                        }
                        increment(oracle.sharedWithAny, key);
                        Map<Integer, Integer> perPartner = oracle.sharedByPartner.get(key);
                        if (perPartner == null) {
                            perPartner = new HashMap<Integer, Integer>();
                            oracle.sharedByPartner.put(key, perPartner);
                        }
                        increment(perPartner, Integer.valueOf(partner));
                    }
                }
            }
            return oracle;
        }

        List<Integer> sourceLabels() {
            return new ArrayList<Integer>(voxelCounts.keySet());
        }

        int voxelCount(int label) {
            Integer count = voxelCounts.get(Integer.valueOf(label));
            return count == null ? 0 : count.intValue();
        }

        int sharedWithAnyTarget(int label) {
            Integer count = sharedWithAny.get(Integer.valueOf(label));
            return count == null ? 0 : count.intValue();
        }

        /**
         * The target label sharing the most voxels, ties broken by the smaller
         * label so the answer does not depend on map iteration order.
         */
        int strongestPartner(int label) {
            Map<Integer, Integer> perPartner = sharedByPartner.get(Integer.valueOf(label));
            if (perPartner == null) {
                return 0;
            }
            int best = 0;
            int bestCount = 0;
            for (Map.Entry<Integer, Integer> entry : perPartner.entrySet()) {
                int candidate = entry.getKey().intValue();
                int count = entry.getValue().intValue();
                if (count > bestCount || (count == bestCount && candidate < best)) {
                    best = candidate;
                    bestCount = count;
                }
            }
            return best;
        }

        int sharedWithStrongestPartner(int label) {
            int partner = strongestPartner(label);
            if (partner == 0) {
                return 0;
            }
            return sharedByPartner.get(Integer.valueOf(label))
                    .get(Integer.valueOf(partner)).intValue();
        }

        /** The label of the target object under this object's centroid, or 0. */
        int labelUnderCentroid(int label) {
            double[] sums = centroidSums.get(Integer.valueOf(label));
            int count = voxelCount(label);
            if (sums == null || count == 0) {
                return 0;
            }
            long x = Math.round(sums[0] / count);
            long y = Math.round(sums[1] / count);
            long z = Math.round(sums[2] / count);
            if (x < 0 || x >= targetLabels.getWidth()) {
                return 0;
            }
            if (y < 0 || y >= targetLabels.getHeight()) {
                return 0;
            }
            ImageStack stack = targetLabels.getStack();
            if (z < 0 || z >= stack.getSize()) {
                return 0;
            }
            return (int) stack.getProcessor((int) z + 1).getf((int) x, (int) y);
        }

        private static void increment(Map<Integer, Integer> counts, Integer key) {
            Integer current = counts.get(key);
            counts.put(key, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
        }
    }
}
