package ocs;

import ij.ImagePlus;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The macro grammar.
 *
 * <p>Option names are public API in the strongest sense: a rename breaks every
 * saved macro, silently, on somebody else's machine months later. What is worth
 * pinning is therefore less the happy path than the refusals — a misspelled
 * option that parsed as nothing would run a different analysis from the one the
 * line describes, and the line is all anybody has afterwards.
 */
public class OCSMacroOptionsTest {

    @Test
    public void theFourDeferredOptionsAreRefusedNamingTheReleaseTheyArriveIn() {
        String[] lines = {
            "channels=A,B block_size=8",
            "channels=A,B psf_xy=0.25",
            "channels=A,B psf_z=0.8",
            "channels=A,B min_object_voxels=20",
            "channels=A,B block_size",
        };
        for (int i = 0; i < lines.length; i++) {
            try {
                OCSMacroOptionsParser.parse(lines[i]);
                fail("accepted a deferred option: " + lines[i]);
            } catch (IllegalArgumentException refused) {
                String option = lines[i].substring(lines[i].lastIndexOf(' ') + 1)
                        .split("=")[0];
                assertTrue(refused.getMessage(),
                        refused.getMessage().startsWith(option + "= is not available"));
                assertTrue(refused.getMessage(), refused.getMessage().contains("0.2.0"));
                assertTrue(refused.getMessage(),
                        refused.getMessage().contains("Deferred to 0.2.0"));
            }
        }
    }

    // ---------- what a line says ----------

    @Test
    public void aMinimalLineNamesItsChannels() {
        OCSMacroOptions options = OCSMacroOptionsParser.parse(
                "channels=labels-A channels=labels-B");
        assertEquals(Arrays.asList("labels-A", "labels-B"), options.channels());
    }

    @Test
    public void channelsMayAlsoArriveAsOneCommaSeparatedValue() {
        // The recorder writes one key per channel; a person writing the line by
        // hand writes the list. Both have to work or one of them is a trap.
        OCSMacroOptions options = OCSMacroOptionsParser.parse(
                "channels=[labels-A,labels-B]");
        assertEquals(Arrays.asList("labels-A", "labels-B"), options.channels());
    }

    @Test
    public void bracketsCarryValuesWithSpacesInThem() {
        OCSMacroOptions options = OCSMacroOptionsParser.parse(
                "channels=[C1 nuclei.tif] channels=[C2 puncta.tif]");
        assertEquals(Arrays.asList("C1 nuclei.tif", "C2 puncta.tif"),
                options.channels());
    }

    @Test
    public void everySettingSurvivesTheLine() {
        OCSMacroOptions options = OCSMacroOptionsParser.parse(
                "channels=a channels=b region_roi=region.zip"
                        + " methods=[volume-overlap,containment]"
                        + " threshold_volume-overlap=45"
                        + " null_model permutations=250 seed=77"
                        + " agreement threshold_sweep alpha=0.01"
                        + " flip_threshold=0.2 sweep_width=0.35 min_compare_n=10"
                        + " output=[/tmp/out] hide_display");

        assertEquals("volume-overlap,containment", options.methods());
        assertEquals(45.0, options.thresholds().get("volume-overlap").doubleValue(), 1e-9);
        assertTrue(options.runsNullModel());
        assertEquals(250, options.permutations());
        assertEquals(77L, options.seed());
        assertTrue(options.runsAgreement());
        assertTrue(options.runsThresholdSweep());
        assertEquals(0.01, options.alpha(), 1e-12);
        assertEquals(0.2, options.flipThreshold(), 1e-12);
        assertEquals(0.35, options.sweepWidth(), 1e-12);
        assertEquals(10, options.minCompareN());
        assertEquals("/tmp/out", options.output());
        assertTrue(options.hideDisplay());
    }

    /**
     * The sweep width round-trips, and is omitted from a recorded line when it
     * is the default — so a recorded macro says what differs from the defaults
     * rather than restating all of them.
     */
    @Test
    public void theSweepWidthSurvivesTheLineAndIsOmittedWhenDefault() {
        OCSMacroOptions wider = OCSMacroOptionsParser.parse(
                "channels=a channels=b threshold_sweep sweep_width=0.5");
        assertEquals(0.5, wider.sweepWidth(), 1e-12);
        assertTrue(wider.toMacroOptions(), wider.toMacroOptions().contains("sweep_width=0.5"));

        OCSMacroOptions plain = OCSMacroOptionsParser.parse(
                "channels=a channels=b threshold_sweep");
        assertEquals(ocs.sweep.ThresholdSweep.DEFAULT_SWEEP_WIDTH,
                plain.sweepWidth(), 1e-12);
        assertFalse(plain.toMacroOptions(),
                plain.toMacroOptions().contains("sweep_width"));
    }

    /** A sweep of no width would report every method perfectly stable. */
    @Test
    public void aSweepWidthOfZeroIsRefused() {
        try {
            OCSMacroOptionsParser.parse(
                    "channels=a channels=b threshold_sweep sweep_width=0");
            fail("a zero-width sweep must be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("sweep_width"));
        }
    }

    @Test
    public void discoveryTurnsOnTheEvidenceItReads() {
        // Otherwise every method classifies "not applicable" and the feature
        // looks broken rather than under-supplied.
        OCSMacroOptions options = OCSMacroOptionsParser.parse(
                "channels=a channels=b region_roi=r.zip discovery");

        assertTrue(options.runsDiscovery());
        assertTrue(options.runsNullModel());
        assertTrue(options.runsAgreement());
        assertTrue(options.runsThresholdSweep());
    }

    @Test
    public void bothDirectionsAreTheDefaultAndTurningThemOffTakesASayingSo() {
        assertTrue(OCSMacroOptionsParser.parse("channels=a channels=b")
                .isBidirectional());
        assertFalse(OCSMacroOptionsParser.parse("channels=a channels=b one_direction")
                .isBidirectional());
        assertFalse(OCSMacroOptionsParser
                .parse("channels=a channels=b bidirectional=false").isBidirectional());
    }

    // ---------- what it refuses ----------

    @Test
    public void anUnknownOptionIsRefusedRatherThanIgnored() {
        // A typo that parsed as nothing would run a different analysis from the
        // one the line describes.
        try {
            OCSMacroOptionsParser.parse("channels=a channels=b permutation=100");
            fail("a misspelled option must not be silently dropped");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("permutation"));
        }
    }

    @Test
    public void anUnknownFlagIsRefusedToo() {
        try {
            OCSMacroOptionsParser.parse("channels=a channels=b nullmodel");
            fail("a misspelled flag must not be silently dropped");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("nullmodel"));
        }
    }

    @Test
    public void anOptionGivenTwiceIsRefusedRatherThanResolved() {
        // The line contradicts itself and guessing which occurrence was meant is
        // not something a parser should do.
        try {
            OCSMacroOptionsParser.parse("channels=a channels=b seed=1 seed=2");
            fail("a contradictory line must not be silently resolved");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("duplicate"));
        }
    }

    @Test
    public void oneChannelIsNotAPair() {
        try {
            OCSMacroOptionsParser.parse("channels=a");
            fail("there is nothing to compare");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("channels"));
        }
    }

    @Test
    public void aShortIntensityListIsRefusedRatherThanPadded() {
        // Padding would pair the wrong intensity image with the wrong labels,
        // and every number after that would be confidently wrong.
        try {
            OCSMacroOptionsParser.parse(
                    "channels=a channels=b channels=c intensity=x intensity=y");
            fail("an intensity list that does not match the channels is a mistake");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("one image"));
        }
    }

    @Test
    public void theChanceTestWithoutARegionIsRefusedAtTheLine() {
        // Caught before any image is opened, so a batch of four hundred does not
        // fail on the first one after loading it.
        try {
            OCSMacroOptionsParser.parse("channels=a channels=b null_model");
            fail("a chance test with nowhere to scatter must be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("region_roi"));
        }
    }

    @Test
    public void aNonNumericSettingSaysWhichOptionWasWrong() {
        try {
            OCSMacroOptionsParser.parse("channels=a channels=b permutations=lots");
            fail("'lots' is not a number of permutations");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("permutations"));
        }
    }

    @Test
    public void anUnclosedBracketIsRefused() {
        try {
            OCSMacroOptionsParser.parse("channels=[a channels=b");
            fail("an unclosed bracket swallows the rest of the line");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("unclosed"));
        }
    }

    // ---------- round trip ----------

    @Test
    public void whatIsRecordedIsWhatReplays() {
        // A recorder whose output cannot be replayed is worse than none, because
        // it looks like a record of what happened.
        String original = "channels=a channels=b region_roi=r.zip"
                + " methods=[volume-overlap,containment]"
                + " threshold_volume-overlap=45"
                + " null_model permutations=250 seed=77 agreement discovery"
                + " alpha=0.01 min_compare_n=10 output=[out] hide_display";

        OCSMacroOptions once = OCSMacroOptionsParser.parse(original);
        OCSMacroOptions twice = OCSMacroOptionsParser.parse(once.toMacroOptions());

        assertEquals(once.channels(), twice.channels());
        assertEquals(once.methods(), twice.methods());
        assertEquals(once.thresholds(), twice.thresholds());
        assertEquals(once.permutations(), twice.permutations());
        assertEquals(once.seed(), twice.seed());
        assertEquals(once.alpha(), twice.alpha(), 1e-12);
        assertEquals(once.minCompareN(), twice.minCompareN());
        assertEquals(once.runsDiscovery(), twice.runsDiscovery());
        assertEquals(once.hideDisplay(), twice.hideDisplay());
    }

    @Test
    public void aRecordedLineLeavesOutSettingsNobodyChanged() {
        // A line restating every default is a line nobody reads, and the one
        // setting that was deliberate is then invisible among twenty that were not.
        String recorded = OCSMacroOptionsParser
                .parse("channels=a channels=b").toMacroOptions();

        assertFalse(recorded, recorded.contains("alpha="));
        assertFalse(recorded, recorded.contains("permutations="));
        assertFalse(recorded, recorded.contains("min_compare_n="));
        assertTrue(recorded, recorded.contains("channels=a"));
    }

    // ---------- into a run ----------

    @Test
    public void aLineBecomesTheSameParametersJavaWouldBuild() {
        OCSMacroOptions options = OCSMacroOptionsParser.parse(
                "channels=a channels=b methods=[volume-overlap]"
                        + " threshold_volume-overlap=45 agreement");

        OCSParameters parameters = options
                .applyTo(OCSParameters.builder(labels("a"), labels("b")))
                .build();

        assertEquals(Arrays.asList("volume-overlap"), parameters.methodIds());
        assertEquals(45.0,
                parameters.thresholds().get("volume-overlap").doubleValue(), 1e-9);
        assertTrue(parameters.runsAgreement());
    }

    @Test
    public void aPresetNamedInTheLineBringsItsOwnChecks() {
        OCSMacroOptions options = OCSMacroOptionsParser.parse(
                "channels=a channels=b methods=[preset:Object colocalization]"
                        + " region_roi=r.zip");

        OCSParameters parameters = options
                .applyTo(OCSParameters.builder(labels("a"), labels("b")))
                .build();

        assertTrue(parameters.methodIds().contains("jaccard-dice"));
        assertTrue("that preset tests against chance", parameters.runsNullModel());
    }

    @Test
    public void allMeansEveryMethodTheRegistryKnows() {
        OCSMacroOptions options = OCSMacroOptionsParser.parse(
                "channels=a channels=b methods=all");

        OCSParameters parameters = options
                .applyTo(OCSParameters.builder(labels("a"), labels("b")))
                .build();

        assertEquals(ocs.engine.EngineRegistry.createDefault().all().size(),
                parameters.methodIds().size());
    }

    // ---------- edge cases (stage 06) ----------

    @Test
    public void windowsPathsWithBackslashesAreAccepted() {
        // A macro string "C:\\data\\roi.zip" reaches the options as single
        // backslashes; refusing them refused every Windows path typed by hand.
        OCSMacroOptions options = OCSMacroOptionsParser.parse(
                "channels=a channels=b region_roi=C:\\data\\region.zip"
                        + " output=[C:\\my results\\run 1] null_model");
        assertEquals("C:\\data\\region.zip", options.regionRoi());
        assertEquals("C:\\my results\\run 1", options.output());
        assertEquals(options.toMacroOptions(), OCSMacroOptionsParser
                .parse(options.toMacroOptions()).toMacroOptions());
    }

    @Test
    public void aCommaInsideATitleSurvivesWhenEachChannelHasItsOwnKey() {
        // The recorder writes one channels= per channel, so a title with a
        // comma in it must not be split into two channels on replay.
        OCSMacroOptions options = OCSMacroOptionsParser.parse(
                "channels=[cells, left.tif] channels=nuclei.tif");
        assertEquals(Arrays.asList("cells, left.tif", "nuclei.tif"),
                options.channels());
        OCSMacroOptions noSpace = OCSMacroOptionsParser.parse(
                "channels=a,b.tif channels=c.tif intensity=x,y.tif intensity=z.tif");
        assertEquals(Arrays.asList("a,b.tif", "c.tif"), noSpace.channels());
        assertEquals(Arrays.asList("x,y.tif", "z.tif"), noSpace.intensityImages());
    }

    @Test
    public void aRecordedTitleWithACommaReplaysAsOneChannel() {
        OCSParameters parameters = OCSParameters.builder(labels("a,1"), labels("b"))
                .build();
        String line = OCSMacroOptions.forRecording(parameters,
                Arrays.asList("a,1", "b"), null, null, null, false).toMacroOptions();
        assertEquals(Arrays.asList("a,1", "b"),
                OCSMacroOptionsParser.parse(line).channels());
    }

    @Test
    public void titlesWithSpacesAndSingleBracketsParse() {
        OCSMacroOptions options = OCSMacroOptionsParser.parse(
                "channels=[C1 labels (1).tif] channels=img[2].tif");
        assertEquals(Arrays.asList("C1 labels (1).tif", "img[2].tif"),
                options.channels());
    }

    @Test
    public void aSeedTooLargeToRecordExactlyIsRefusedAtTheLine() {
        // The run record is JSON; beyond 2^53 a reader gets a different seed
        // back, and the record would replay a different run.
        assertEquals(1L << 53, OCSMacroOptionsParser.parse(
                "channels=a channels=b seed=9007199254740992").seed());
        assertEquals(-(1L << 53), OCSMacroOptionsParser.parse(
                "channels=a channels=b seed=-9007199254740992").seed());
        String[] tooBig = {"9007199254740993", "-9007199254740993",
            "9223372036854775807"};
        for (int i = 0; i < tooBig.length; i++) {
            try {
                OCSMacroOptionsParser.parse("channels=a channels=b seed=" + tooBig[i]);
                fail("accepted seed " + tooBig[i]);
            } catch (IllegalArgumentException refused) {
                assertTrue(refused.getMessage(), refused.getMessage().contains("2^53"));
            }
        }
    }

    @Test
    public void unusableShuffleCountsSayWhatWasWrong() {
        String[] values = {"abc", "NaN", "0", "-5", "2.5", "99999999999", ""};
        for (int i = 0; i < values.length; i++) {
            try {
                OCSMacroOptionsParser.parse(
                        "channels=a channels=b permutations=" + values[i]);
                fail("accepted permutations=" + values[i]);
            } catch (IllegalArgumentException refused) {
                assertTrue(refused.getMessage(),
                        refused.getMessage().startsWith("permutations="));
            }
        }
    }

    @Test
    public void moreThanFiveChannelsAreRefusedAtTheLine() {
        try {
            OCSMacroOptionsParser.parse("channels=a,b,c,d,e,f");
            fail("six channels accepted");
        } catch (IllegalArgumentException refused) {
            assertTrue(refused.getMessage(), refused.getMessage().contains("at most 5"));
        }
    }

    @Test
    public void optionNamesAreNotCaseSensitiveButStillCannotRepeat() {
        try {
            OCSMacroOptionsParser.parse("channels=a channels=b seed=1 SEED=2");
            fail("seed given twice");
        } catch (IllegalArgumentException refused) {
            assertTrue(refused.getMessage(), refused.getMessage().contains("duplicate"));
        }
    }

    @Test
    public void presetNamesSurviveAMacroFileReadInAnotherEncoding() {
        // A UTF-8 .ijm read as windows-1252 turns the em dash into three
        // characters, so the exact name could not be used from a macro file.
        String discovery = "Discovery \u2014 everything";
        String[] spellings = {discovery, "discovery", "Discovery - everything",
            "Discovery \u00e2\u20ac\u201d everything", "QUICK LOOK", "quick-look"};
        String[] expected = {discovery, discovery, discovery, discovery,
            "Quick look", "Quick look"};
        for (int i = 0; i < spellings.length; i++) {
            assertEquals(spellings[i], expected[i],
                    ocs.ui.Preset.byName(spellings[i]).name());
        }
        try {
            ocs.ui.Preset.byName("everything");
            fail("a later word alone is not a preset name");
        } catch (IllegalArgumentException refused) {
            assertTrue(refused.getMessage(), refused.getMessage().contains("Quick look"));
        }
    }

    private static ImagePlus labels(String title) {
        ShortProcessor processor = new ShortProcessor(16, 16);
        for (int y = 2; y < 6; y++) {
            for (int x = 2; x < 6; x++) {
                processor.set(x, y, 1);
            }
        }
        return new ImagePlus(title, processor);
    }
}
