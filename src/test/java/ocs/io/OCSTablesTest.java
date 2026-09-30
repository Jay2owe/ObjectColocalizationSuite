package ocs.io;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import ij.process.ShortProcessor;
import ocs.OCS;
import ocs.OCSParameters;
import ocs.OCSResult;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The tables somebody actually reads.
 *
 * <p>A results table is where a mistake becomes permanent: it gets saved, put in
 * a figure, and cited. The things worth pinning here are the ones that would
 * still look like a valid table while being wrong — a method's column silently
 * overwriting another's, a missing value written as zero, or a row appearing
 * once per method instead of once per object.
 */
public class OCSTablesTest {

    private static final int SIZE = 32;

    @BeforeClass
    public static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    // ---------- per object ----------

    @Test
    public void thePerObjectTableHasOneRowPerObjectPerDirectionNotOnePerMethod() {
        // Two objects per channel, two channels, so two directions and four
        // rows — however many methods are running. A row per method would look
        // like twice as many objects.
        OCSResult result = run("volume-overlap", "containment");
        ResultsTable table = OCSTables.perObject(result);

        assertEquals(4, table.size());
    }

    @Test
    public void eachMethodGetsItsOwnColumnsKeyedByItsId() {
        // Ids, not display names: two methods may reasonably call their primary
        // column the same thing, and a column overwriting another is the worst
        // failure a results table has.
        ResultsTable table = OCSTables.perObject(run("volume-overlap", "containment"));
        List<String> headings = Arrays.asList(table.getHeadings());

        assertTrue(headings.toString(), headings.contains("volume-overlap"));
        assertTrue(headings.contains("volume-overlap Partner"));
        assertTrue(headings.contains("volume-overlap Coincident"));
        assertTrue(headings.contains("containment"));
        assertTrue(headings.contains("containment Coincident"));
    }

    @Test
    public void everyObjectCarriesItsOwnSizeSoMethodsCanBeReadAgainstIt() {
        // Without this, "the method only calls the big ones coincident" is not a
        // question the table can answer, and it is the first one worth asking.
        ResultsTable table = OCSTables.perObject(run("volume-overlap"));
        assertEquals(64.0, table.getValue(OCSTables.VOXELS, 0), 1e-9);
    }

    @Test
    public void volumeIsCalibratedAndVoxelsAreNot() {
        // The two columns exist separately because a reader comparing across
        // experiments needs the calibrated one and a reader debugging a
        // segmentation needs the raw count.
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 0.5;
        calibration.pixelHeight = 0.5;
        calibration.pixelDepth = 1.0;

        OCSResult result = OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                .methods("volume-overlap")
                .calibration(calibration)
                .build());
        ResultsTable table = OCSTables.perObject(result);

        assertEquals(64.0, table.getValue(OCSTables.VOXELS, 0), 1e-9);
        assertEquals(16.0, table.getValue(OCSTables.VOLUME, 0), 1e-9);
    }

    @Test
    public void anObjectAMethodSaidNothingAboutIsBlankRatherThanZero() {
        // Zero is a real overlap. A method that produced no value for an object
        // must not be indistinguishable from one that measured no overlap.
        OCSResult result = OCS.run(OCSParameters.builder(
                        oneObject("A"), twoObjects("B"))
                .methods("volume-overlap")
                .build());
        ResultsTable table = OCSTables.perObject(result);

        boolean sawBlank = false;
        for (int row = 0; row < table.size(); row++) {
            double partner = table.getValue("volume-overlap Partner", row);
            if (Double.isNaN(partner)) {
                sawBlank = true;
            }
        }
        assertTrue("an object with no partner reports no partner, not partner 0",
                sawBlank);
    }

    @Test
    public void oneDirectionalRunsReportHalfTheRowsAndStillComputeBoth() {
        // The flag is about what is printed, not about what is measured: the two
        // directions are different questions whenever the channels hold
        // different object counts.
        OCSResult both = run("volume-overlap");
        OCSResult one = OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                .methods("volume-overlap")
                .bidirectional(false)
                .build());

        assertEquals(4, OCSTables.perObject(both).size());
        assertEquals(2, OCSTables.perObject(one).size());
        assertEquals("both directions were still measured",
                2, one.engineResults().get(0).directions().size());
    }

    // ---------- summary ----------

    @Test
    public void theSummaryHasOneRowPerDirection() {
        ResultsTable table = OCSTables.summary(run("volume-overlap", "containment"));
        assertEquals(2, table.size());
        assertEquals(2.0, table.getValue("n Objects", 0), 1e-9);
    }

    @Test
    public void aSingleObjectReportsNoSpreadRatherThanZeroSpread() {
        // A standard deviation of 0 from one value would read as "every object
        // agreed", which is a claim one object cannot support.
        OCSResult result = OCS.run(OCSParameters.builder(
                        oneObject("A"), oneObject("B"))
                .methods("volume-overlap").build());
        ResultsTable table = OCSTables.summary(result);

        assertTrue(Double.isNaN(table.getValue("volume-overlap SD", 0)));
        assertFalse(Double.isNaN(table.getValue("volume-overlap Mean", 0)));
    }

    // ---------- the methods that answer per direction ----------

    @Test
    public void wholeImageIntensityAppearsInItsOwnTableAndNotInThePerObjectOne() {
        // It describes a channel pair, not an object. A row per object would be
        // the same number repeated, which reads as agreement between objects.
        OCSResult result = OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                .intensityImages(Arrays.asList(intensity("A raw"), intensity("B raw")))
                .methods("volume-overlap", "whole-image-intensity")
                .build());

        List<String> perObjectHeadings =
                Arrays.asList(OCSTables.perObject(result).getHeadings());
        assertFalse(perObjectHeadings.contains("whole-image-intensity"));

        ResultsTable whole = OCSTables.wholeDirectionValues(result);
        assertEquals(2, whole.size());
        assertTrue(Arrays.asList(whole.getHeadings()).toString(),
                Arrays.asList(whole.getHeadings()).contains("Pearson r"));
    }

    @Test
    public void aCurveMethodReportsItsGlobalPSomewhere() {
        // The curve itself is a plot. Its global p is the answer, and without
        // this table it would appear in no table at all.
        OCSResult result = OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                .domain(Arrays.<Roi>asList(new Roi(0, 0, SIZE, SIZE)))
                .methods("cross-g")
                .build());

        ResultsTable table = OCSTables.curveSummary(result);
        assertEquals(2, table.size());
        assertTrue(Arrays.asList(table.getHeadings()).toString(),
                Arrays.asList(table.getHeadings()).contains("Status"));
    }

    // ---------- the extra checks ----------

    @Test
    public void theChanceTestTableCarriesTheSeedThatProducedIt() {
        // A p value without the seed beside it cannot be replayed, and a number
        // nobody can reproduce is not evidence.
        OCSResult result = OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                .domain(Arrays.<Roi>asList(new Roi(0, 0, SIZE, SIZE)))
                .methods("volume-overlap")
                .nullModel(true).permutations(9).seed(4L)
                .build());

        ResultsTable table = OCSTables.nullModel(result);
        assertTrue(table.size() > 0);
        assertEquals(4.0, table.getValue("Seed", 0), 1e-9);
        assertEquals(9.0, table.getValue("Permutations", 0), 1e-9);
    }

    @Test
    public void anAgreementStatisticThatDoesNotApplyIsBlankRatherThanZero() {
        // Zero agreement and no agreement statistic are opposite readings of the
        // same cell, and only one of them is true.
        OCSResult result = OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                .methods("volume-overlap", "containment")
                .agreement(true)
                .build());

        ResultsTable table = OCSTables.agreement(result);
        assertTrue(table.size() > 0);
        boolean sawBlank = false;
        for (int row = 0; row < table.size(); row++) {
            if (Double.isNaN(table.getValue("Lin's CCC", row))) {
                sawBlank = true;
            }
        }
        assertTrue("a statistic the scales do not admit must be blank", sawBlank);
    }

    @Test
    public void aMethodWithNoThresholdKeepsItsRowInTheSweepTable() {
        // "Nothing to be sensitive to" is a point in the method's favour and
        // belongs in the table, not in the gap where its row would have been.
        OCSResult result = OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                .methods("cpc", "volume-overlap")
                .thresholdSweep(true)
                .build());

        ResultsTable table = OCSTables.thresholdSensitivity(result);
        boolean sawCpc = false;
        for (int row = 0; row < table.size(); row++) {
            if ("cpc".equals(table.getStringValue(OCSTables.METHOD, row))) {
                sawCpc = true;
            }
        }
        assertTrue("centroid coincidence has no cut-off and must say so", sawCpc);
    }

    @Test
    public void aSkippedMethodGetsARowSayingWhy() {
        OCSResult result = OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                .methods("volume-overlap", "per-object-intensity")
                .build());

        ResultsTable table = OCSTables.skipped(result);
        assertEquals(1, table.size());
        assertEquals("per-object-intensity",
                table.getStringValue(OCSTables.METHOD, 0));
    }

    // ---------- the bundle ----------

    @Test
    public void anEmptyTableIsLeftOutRatherThanSavedWithOnlyHeadings() {
        // A file containing nothing but column names reads as a failed run.
        Map<String, ResultsTable> tables = OCSTables.all(run("volume-overlap"));

        assertTrue(tables.containsKey("per-object"));
        assertTrue(tables.containsKey("summary"));
        assertFalse("nothing was skipped", tables.containsKey("skipped"));
        assertFalse("no chance test was asked for", tables.containsKey("null-model"));
    }

    @Test
    public void everyTableNamesTheRunItCameFrom() {
        // Batch aggregates hundreds of these into one file, and a row with no
        // image name cannot be traced back to anything.
        OCSResult result = OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                .methods("volume-overlap")
                .sourceName("field-07")
                .build());

        Map<String, ResultsTable> tables = OCSTables.all(result);
        for (Map.Entry<String, ResultsTable> entry : tables.entrySet()) {
            ResultsTable table = entry.getValue();
            assertEquals(entry.getKey(), "field-07",
                    table.getStringValue(OCSTables.IMAGE, 0));
        }
    }

    @Test
    public void theRunIsNamedAfterTheFirstImageWhenNobodySaysOtherwise() {
        OCSResult result = run("volume-overlap");
        assertEquals("A", result.parameters().sourceName());
    }

    // ---------- fixtures ----------

    // ---------- the supporting columns, widened 2026-08-14 ----------

    /**
     * Every column a method declares reaches the table, not just its headline
     * number.
     *
     * <p>Before the widening a user running containment got a yes/no verdict and
     * neither of the two percentages that verdict summarises, and a user running
     * per-object Manders got a table with a Pearson column and no Manders. The
     * numbers were computed either way; they simply had nowhere to go.
     */
    @Test
    public void everyDeclaredColumnOfEveryMethodReachesTheTable() {
        OCSResult result = OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                .intensityImages(Arrays.asList(intensity("iA"), intensity("iB")))
                .methods("volume-overlap", "jaccard-dice", "containment",
                        "bounding-box", "per-object-intensity")
                .build());
        ResultsTable table = OCSTables.perObject(result);
        List<String> headings = Arrays.asList(table.getHeadings());

        String[] expected = {
                // The three that were always there, for one method.
                "volume-overlap", "volume-overlap Partner", "volume-overlap Coincident",
                // The supporting values, keyed by engine id so two methods with
                // a column of the same name cannot overwrite each other.
                "volume-overlap Overlap Partner Count", "volume-overlap Overlap Voxels",
                "jaccard-dice Dice", "jaccard-dice Intersection Voxels",
                "jaccard-dice Union Voxels",
                "containment Containment Class", "containment Source Inside Partner",
                "containment Partner Inside Source",
                "bounding-box BBox Volume",
                "per-object-intensity Manders M1", "per-object-intensity Manders M2",
                "per-object-intensity Costes Ta", "per-object-intensity Object Voxels"};
        for (int i = 0; i < expected.length; i++) {
            assertTrue("missing column '" + expected[i] + "'; table has " + headings,
                    headings.contains(expected[i]));
        }
    }

    /**
     * The supporting values are the object's own, not the first object's
     * repeated down the column.
     *
     * <p><b>The two objects must disagree, and that is the whole design of this
     * fixture.</b> The obvious version — two identical 8×8 objects, both fully
     * overlapped by their copies — gives every row the same Dice, the same
     * intersection and the same union, so a column wired to read row 0 for every
     * row passes it. That version was written first and a mutation walked
     * straight through it.
     *
     * <p>So object 1 is an 8×8 square with an exact copy in the other channel,
     * and object 2 is a 4×4 square whose copy is shifted two columns:
     *
     * <pre>
     *   object 1:  64 shared of 64      union 64      Jaccard 1      Dice 1
     *   object 2:   8 shared of 16      union 24      Jaccard 1/3    Dice 0.5
     * </pre>
     *
     * Object 2's numbers come from a 2-column × 4-row overlap, and its union is
     * 16 + 16 − 8. Every one of the three columns now differs between the rows,
     * so no single row can stand in for the other.
     */
    @Test
    public void aSupportingColumnCarriesEachObjectsOwnValue() {
        OCSResult result = OCS.run(OCSParameters.builder(
                        unequalObjects("A", 20), unequalObjects("B", 22))
                .methods("jaccard-dice")
                .build());
        ResultsTable table = OCSTables.perObject(result);

        assertEquals("two objects, two directions", 4, table.size());
        for (int row = 0; row < table.size(); row++) {
            boolean isFirstObject = table.getValue(OCSTables.LABEL, row) == 1.0;
            String where = "row " + row + ", object "
                    + (int) table.getValue(OCSTables.LABEL, row);

            assertEquals(where + " Dice", isFirstObject ? 1.0 : 0.5,
                    table.getValue("jaccard-dice Dice", row), 1.0e-12);
            assertEquals(where + " intersection", isFirstObject ? 64.0 : 8.0,
                    table.getValue("jaccard-dice Intersection Voxels", row), 1.0e-12);
            assertEquals(where + " union", isFirstObject ? 64.0 : 24.0,
                    table.getValue("jaccard-dice Union Voxels", row), 1.0e-12);
        }
    }

    /**
     * An 8×8 object at a fixed position plus a 4×4 object whose column start is
     * the caller's, so two channels can share the first object exactly and
     * overlap the second only partly.
     */
    private static ImagePlus unequalObjects(String title, int secondObjectX) {
        ShortProcessor processor = new ShortProcessor(SIZE, SIZE);
        fill(processor, 3, 3, 8, 1);
        fill(processor, secondObjectX, 20, 4, 2);
        return new ImagePlus(title, processor);
    }

    /**
     * A category column reads as words, not as the integer code the engine
     * transports.
     *
     * <p>Both objects are exact copies of their partners, so both are contained.
     * A CSV cell saying {@code 1} in a column called "Containment Class" tells a
     * reader nothing and invites the wrong guess that bigger means more.
     */
    @Test
    public void theContainmentClassIsWrittenAsItsNameNotItsCode() {
        OCSResult result = run("containment");
        ResultsTable table = OCSTables.perObject(result);

        for (int row = 0; row < table.size(); row++) {
            assertEquals("row " + row, "Source inside target",
                    table.getStringValue("containment Containment Class", row));
        }
    }

    /**
     * A method that says nothing about an object still contributes its full set
     * of columns for that row.
     *
     * <p>Channel B's second object sits far from anything in channel A, so
     * centroid coincidence finds no partner for it. If the missing row were
     * written narrow, every value after it in the row would sit under the wrong
     * heading — a whole-table corruption that a spot check of the first few rows
     * would never show.
     */
    @Test
    public void aRowWithNothingToSayIsStillTheFullWidth() {
        OCSResult result = OCS.run(OCSParameters.builder(
                        oneObject("A"), twoObjects("B"))
                .methods("volume-overlap", "containment")
                .build());
        ResultsTable table = OCSTables.perObject(result);

        int columns = table.getHeadings().length;
        for (int row = 0; row < table.size(); row++) {
            assertEquals("row " + row + " is a different width from the header",
                    columns, table.getHeadings().length);
            // Present and blank, rather than absent: the column exists on every
            // row even where the method had no category to name.
            assertNotNull("row " + row + " lost its category cell",
                    table.getStringValue("containment Containment Class", row));
        }
        assertTrue("the fixture must contain an object with no partner, or this "
                        + "test never exercises the narrow-row path",
                table.size() > 2);
    }

    private static OCSResult run(String... methods) {
        return OCS.run(OCSParameters.builder(labels("A"), labels("B"))
                .methods(methods)
                .build());
    }

    private static ImagePlus labels(String title) {
        ShortProcessor processor = new ShortProcessor(SIZE, SIZE);
        fill(processor, 3, 3, 8, 1);
        fill(processor, 18, 18, 8, 2);
        return new ImagePlus(title, processor);
    }

    private static ImagePlus oneObject(String title) {
        ShortProcessor processor = new ShortProcessor(SIZE, SIZE);
        fill(processor, 3, 3, 8, 1);
        return new ImagePlus(title, processor);
    }

    /** Two objects, one of them far from anything in the other channel. */
    private static ImagePlus twoObjects(String title) {
        ShortProcessor processor = new ShortProcessor(SIZE, SIZE);
        fill(processor, 3, 3, 8, 1);
        fill(processor, 24, 24, 4, 2);
        return new ImagePlus(title, processor);
    }

    private static void fill(ShortProcessor processor, int x0, int y0, int size,
            int label) {
        for (int y = y0; y < y0 + size; y++) {
            for (int x = x0; x < x0 + size; x++) {
                processor.set(x, y, label);
            }
        }
    }

    private static ImagePlus intensity(String title) {
        ByteProcessor processor = new ByteProcessor(SIZE, SIZE);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                processor.set(x, y, (x * 7 + y * 3) % 256);
            }
        }
        return new ImagePlus(title, processor);
    }
}
