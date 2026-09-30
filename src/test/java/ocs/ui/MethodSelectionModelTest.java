package ocs.ui;

import ij.ImagePlus;
import ij.process.ShortProcessor;
import ocs.engine.EngineFamily;
import ocs.engine.EngineInputs;
import ocs.engine.EngineRegistry;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The dialog's behaviour, without a screen.
 *
 * <p>Everything a user would notice — what is ticked when it opens, what is
 * greyed and why, what a preset does, what the estimate says — lives in the
 * model, so all of it is testable here and none of it depends on Swing being
 * available.
 */
public class MethodSelectionModelTest {

    private static final int SIZE = 20;

    // ---------- the layout ----------

    @Test
    public void everyRegisteredEngineAppearsInExactlyOneGroup() {
        // A method added to the registry and forgotten in the layout would be
        // reachable from a macro and invisible in the interface — which is
        // impossible to explain to somebody who has both open.
        MethodGroup.requireCompleteCover(MethodGroup.defaultLayout(),
                EngineRegistry.createDefault());
    }

    @Test
    public void aMissingGroupIsReportedByNameRatherThanSilently() {
        List<MethodGroup> incomplete = new ArrayList<MethodGroup>(
                MethodGroup.defaultLayout());
        incomplete.remove(0);
        try {
            MethodGroup.requireCompleteCover(incomplete, EngineRegistry.createDefault());
            fail("an uncovered engine must be reported");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("volume-overlap"));
        }
    }

    @Test
    public void methodsSharingASettingShareOneTableColumn() {
        // The point of grouping: three variants of "how much overlap" read as
        // one decision with three rows, not as three unrelated questions.
        MethodGroup overlap = groupTitled("Object overlap");
        assertEquals(Arrays.asList("volume-overlap", "bounding-box", "containment"),
                overlap.engineIds());
        assertTrue(overlap.hasSharedColumn());
        assertEquals("Threshold", overlap.sharedColumn());
        assertEquals("%", overlap.sharedColumnUnit());
    }

    @Test
    public void aGroupWithNothingToConfigureDeclaresNoColumn() {
        // Null rather than an empty header: a blank column reads as a setting
        // somebody forgot to fill in.
        MethodGroup geometric = groupTitled("Object position");
        assertFalse(geometric.hasSharedColumn());
        assertNull(geometric.sharedColumn());
    }

    @Test
    public void aRowStartsAtItsEnginesOwnDefaultRatherThanARestatedOne() {
        // Restating the default in the dialog is how a macro recorded from the
        // interface starts producing different numbers from the interface.
        EngineRegistry registry = EngineRegistry.createDefault();
        double dialogDefault = MethodGroup.defaultSettingOf(registry, "volume-overlap");
        double engineDefault = ((ocs.engine.ThresholdBearing)
                registry.byId("volume-overlap")).threshold();
        assertEquals(engineDefault, dialogDefault, 0.0);
    }

    @Test
    public void aMethodWithNoSettingReportsNoDefault() {
        assertTrue(Double.isNaN(MethodGroup.defaultSettingOf(
                EngineRegistry.createDefault(), "cpc")));
    }

    @Test
    public void familiesStayTogetherAndTheirBasicGroupsComeFirst() {
        // Two rules, and the first beats the second. A user looking for an
        // intensity method looks under intensity — so families are contiguous
        // blocks, and "everything expanded first, everything collapsed after"
        // would scatter them. Within a family, the group most people want is
        // open and the specialised ones are folded away.
        List<MethodGroup> layout = MethodGroup.defaultLayout();

        List<EngineFamily> seen = new ArrayList<EngineFamily>();
        EngineFamily current = null;
        for (int i = 0; i < layout.size(); i++) {
            EngineFamily family = layout.get(i).family();
            if (family != current) {
                assertFalse("the " + family.displayName()
                                + " family is split across the layout",
                        seen.contains(family));
                seen.add(family);
                current = family;
            }
        }

        current = null;
        boolean collapsedSeenInFamily = false;
        for (int i = 0; i < layout.size(); i++) {
            if (layout.get(i).family() != current) {
                current = layout.get(i).family();
                collapsedSeenInFamily = false;
            }
            if (layout.get(i).isAdvanced()) {
                collapsedSeenInFamily = true;
            } else {
                assertFalse("group '" + layout.get(i).title()
                                + "' is expanded but sits below a collapsed group "
                                + "in the same family",
                        collapsedSeenInFamily);
            }
        }

        assertTrue("the spatial family must start collapsed",
                groupTitled("Spatial statistics").isAdvanced());
        assertFalse("the cheapest object group must start open",
                groupTitled("Object overlap").isAdvanced());
    }

    // ---------- what it opens on ----------

    @Test
    public void itOpensOnASmallCheapSubset() {
        MethodSelectionModel model = MethodSelectionModel.openedOn(
                EngineRegistry.createDefault(), labelsOnly());
        assertEquals(2, model.selectedCount());
        assertEquals(Preset.defaultPreset().name(), model.matchingPreset().name());
        assertTrue(model.isSelected("cpc"));
        assertTrue(model.isSelected("volume-overlap"));
    }

    @Test
    public void openingCostsFarLessThanSelectingEverything() {
        // The reason presets exist rather than an all-on default. If the two
        // were comparable the first screen would not matter.
        EngineRegistry registry = EngineRegistry.createDefault();
        MethodSelectionModel quick = MethodSelectionModel.openedOn(registry, labelsOnly());
        double opening = quick.estimatedCost();

        MethodSelectionModel everything = MethodSelectionModel.openedOn(registry, labelsOnly());
        everything.apply(Preset.byName("Discovery — everything"));

        assertTrue("everything must cost at least 20x the opening screen, got "
                        + everything.estimatedCost() + " against " + opening,
                everything.estimatedCost() > opening * 20.0);
    }

    // ---------- greying out ----------

    @Test
    public void aMethodThatCannotRunOnThisDataIsNotOfferedAndSaysWhy() {
        MethodSelectionModel model = MethodSelectionModel.openedOn(
                EngineRegistry.createDefault(), labelsOnly());

        assertFalse(model.isRunnable("per-object-intensity"));
        // "Greyed out with no explanation" is the commonest way a dialog wastes
        // somebody's afternoon.
        assertTrue(model.whyNotRunnable("per-object-intensity"),
                model.whyNotRunnable("per-object-intensity").contains("intensity"));
        assertEquals("", model.whyNotRunnable("cpc"));
    }

    @Test
    public void tickingAnUnrunnableMethodFailsLoudlyRatherThanLater() {
        MethodSelectionModel model = MethodSelectionModel.openedOn(
                EngineRegistry.createDefault(), labelsOnly());
        try {
            model.setSelected("per-object-intensity", true);
            fail("must refuse a method whose inputs are absent");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("intensity"));
        }
    }

    @Test
    public void aPresetDropsWhatThisDataCannotRunRatherThanFailing() {
        // Label images only, intensity preset chosen. The user gets the part of
        // it that works instead of an error.
        MethodSelectionModel model = MethodSelectionModel.openedOn(
                EngineRegistry.createDefault(), labelsOnly());
        model.apply(Preset.byName("Intensity colocalization"));
        assertTrue("no intensity method can run on labels alone", model.isEmpty());

        MethodSelectionModel withIntensity = MethodSelectionModel.openedOn(
                EngineRegistry.createDefault(), labelsAndIntensity());
        withIntensity.apply(Preset.byName("Intensity colocalization"));
        assertEquals(2, withIntensity.selectedCount());
    }

    // ---------- presets ----------

    @Test
    public void applyingAPresetReplacesRatherThanAddsToTheSelection() {
        // Merging would make two presets in a row produce a set matching
        // neither, and the name at the top would then be wrong.
        MethodSelectionModel model = MethodSelectionModel.openedOn(
                EngineRegistry.createDefault(), labelsOnly());
        model.apply(Preset.byName("Object colocalization"));
        int afterFirst = model.selectedCount();
        model.apply(Preset.byName("Quick look"));

        assertEquals(2, model.selectedCount());
        assertTrue(afterFirst > 2);
    }

    @Test
    public void touchingACheckboxLeavesNoPresetMatching() {
        // Which is what makes the dropdown read "Custom" — the honest answer.
        MethodSelectionModel model = MethodSelectionModel.openedOn(
                EngineRegistry.createDefault(), labelsOnly());
        assertNotNull(model.matchingPreset());
        model.setSelected("containment", true);
        assertNull(model.matchingPreset());
    }

    @Test
    public void aPresetNarrowedByTheDataStillReadsAsThatPreset() {
        // Otherwise loading labels without intensity images would flip the
        // dropdown to Custom for a reason the user did not cause.
        MethodSelectionModel model = MethodSelectionModel.openedOn(
                EngineRegistry.createDefault(), labelsOnly());
        model.apply(Preset.byName("Object colocalization"));
        assertNotNull(model.matchingPreset());
        assertEquals("Object colocalization", model.matchingPreset().name());
    }

    @Test
    public void everyShippedPresetNamesOnlyRealEngines() {
        EngineRegistry registry = EngineRegistry.createDefault();
        List<Preset> presets = Preset.shipped();
        for (int i = 0; i < presets.size(); i++) {
            List<String> ids = presets.get(i).engineIds();
            assertFalse(presets.get(i).name() + " selects nothing", ids.isEmpty());
            for (int j = 0; j < ids.size(); j++) {
                assertTrue(presets.get(i).name() + " names unknown engine '"
                        + ids.get(j) + "'", registry.has(ids.get(j)));
            }
            assertFalse(presets.get(i).name() + " needs a description",
                    presets.get(i).description().trim().isEmpty());
        }
    }

    @Test
    public void theEverythingPresetReallyIsEveryMethod() {
        // Otherwise "Discovery — everything" quietly stops being everything the
        // first time a method is added.
        EngineRegistry registry = EngineRegistry.createDefault();
        assertEquals(registry.size(),
                Preset.byName("Discovery — everything").engineIds().size());
    }

    @Test
    public void presetsAreOrderedCheapestFirst() {
        // Must be judged on data where every method can actually run. On labels
        // alone the spatial family is dropped for want of a region, and the
        // preset that should be the second most expensive comes out cheap —
        // which would have let a genuinely mis-ordered list pass.
        EngineRegistry registry = EngineRegistry.createDefault();
        EngineInputs inputs = everythingAvailable();
        List<Preset> presets = Preset.shipped();
        double previous = -1.0;
        for (int i = 0; i < presets.size(); i++) {
            MethodSelectionModel model = MethodSelectionModel.openedOn(registry, inputs);
            model.apply(presets.get(i));
            double cost = model.estimatedCost();
            assertTrue(presets.get(i).name() + " costs " + cost
                            + ", less than the preset above it", cost >= previous);
            previous = cost;
        }
    }

    // ---------- the estimate ----------

    @Test
    public void theNullModelDominatesTheEstimate() {
        // It re-runs every selected engine once per permutation, so a user who
        // cannot see that before pressing Run will read the wait as slowness.
        MethodSelectionModel model = MethodSelectionModel.openedOn(
                EngineRegistry.createDefault(), labelsOnly());
        model.setRunsNullModel(false);
        model.setRunsThresholdSweep(false);
        double bare = model.estimatedCost();

        model.setRunsNullModel(true);
        assertTrue("the null model must dominate, got " + model.estimatedCost()
                        + " against " + bare,
                model.estimatedCost() > bare * 50.0);
    }

    @Test
    public void theSweepIsChargedForRatherThanTreatedAsFree() {
        MethodSelectionModel model = MethodSelectionModel.openedOn(
                EngineRegistry.createDefault(), labelsOnly());
        model.setRunsNullModel(false);
        model.setRunsThresholdSweep(false);
        double bare = model.estimatedCost();
        model.setRunsThresholdSweep(true);
        assertTrue("21 more runs of each engine is not free",
                model.estimatedCost() > bare);
    }

    @Test
    public void anEmptySelectionCostsNothingAndIsRecognisable() {
        MethodSelectionModel model = MethodSelectionModel.openedOn(
                EngineRegistry.createDefault(), labelsOnly());
        model.setSelected("cpc", false);
        model.setSelected("volume-overlap", false);
        assertTrue(model.isEmpty());
        assertEquals(0.0, model.estimatedCost(), 0.0);
    }

    // ---------- remembering ----------

    @Test
    public void theSelectionSurvivesARoundTripThroughPreferences() {
        EngineRegistry registry = EngineRegistry.createDefault();
        MethodSelectionModel saved = MethodSelectionModel.openedOn(registry, labelsOnly());
        saved.apply(Preset.byName("Object colocalization"));

        MethodSelectionModel restored = MethodSelectionModel.restoredFrom(
                registry, labelsOnly(), saved.toPreferenceValue());
        assertEquals(saved.selectedIds(), restored.selectedIds());
    }

    @Test
    public void aRememberedMethodThatNoLongerExistsIsDroppedNotFatal() {
        // After an upgrade that renamed a method, the dialog must still open.
        MethodSelectionModel restored = MethodSelectionModel.restoredFrom(
                EngineRegistry.createDefault(), labelsOnly(),
                "cpc;a-method-from-2019;volume-overlap");
        assertEquals(Arrays.asList("cpc", "volume-overlap"), restored.selectedIds());
    }

    @Test
    public void aRememberedSelectionThatCannotRunFallsBackToTheDefault() {
        // Silently opening with nothing ticked looks like a broken dialog.
        MethodSelectionModel restored = MethodSelectionModel.restoredFrom(
                EngineRegistry.createDefault(), labelsOnly(),
                "per-object-intensity;whole-image-intensity");
        assertFalse(restored.isEmpty());
        assertEquals(Preset.defaultPreset().name(), restored.matchingPreset().name());
    }

    @Test
    public void anEmptyOrAbsentPreferenceOpensOnTheDefault() {
        EngineRegistry registry = EngineRegistry.createDefault();
        assertEquals(2, MethodSelectionModel
                .restoredFrom(registry, labelsOnly(), null).selectedCount());
        assertEquals(2, MethodSelectionModel
                .restoredFrom(registry, labelsOnly(), "  ").selectedCount());
    }

    // ---------- ordering ----------

    @Test
    public void selectedIdsComeBackInRegistrationOrderNotTickOrder() {
        // The table, the columns and the run record all follow registration
        // order; if the selection did not, the same set of methods would produce
        // different column orders depending on which box was clicked first.
        MethodSelectionModel model = MethodSelectionModel.openedOn(
                EngineRegistry.createDefault(), labelsOnly());
        model.setSelected("cpc", false);
        model.setSelected("volume-overlap", false);
        model.setSelected("containment", true);
        model.setSelected("cpc", true);

        assertEquals(Arrays.asList("cpc", "containment"), model.selectedIds());
    }

    // ---------- fixtures ----------

    private static MethodGroup groupTitled(String title) {
        List<MethodGroup> layout = MethodGroup.defaultLayout();
        for (int i = 0; i < layout.size(); i++) {
            if (layout.get(i).title().equals(title)) {
                return layout.get(i);
            }
        }
        throw new AssertionError("no group titled '" + title + "'");
    }

    private static EngineInputs labelsOnly() {
        return EngineInputs.builder(Arrays.asList(labels("A"), labels("B")))
                .channelNames(Arrays.asList("A", "B"))
                .build();
    }

    private static EngineInputs labelsAndIntensity() {
        return EngineInputs.builder(Arrays.asList(labels("A"), labels("B")))
                .intensityImages(Arrays.asList(labels("rawA"), labels("rawB")))
                .channelNames(Arrays.asList("A", "B"))
                .build();
    }

    /** Labels, raw intensity and a region: everything every method needs. */
    private static EngineInputs everythingAvailable() {
        return EngineInputs.builder(Arrays.asList(labels("A"), labels("B")))
                .intensityImages(Arrays.asList(labels("rawA"), labels("rawB")))
                .channelNames(Arrays.asList("A", "B"))
                .domain(Arrays.<ij.gui.Roi>asList(
                        new ij.gui.Roi(0, 0, SIZE, SIZE)))
                .build();
    }

    private static ImagePlus labels(String title) {
        ShortProcessor processor = new ShortProcessor(SIZE, SIZE);
        for (int y = 2; y < 8; y++) {
            for (int x = 2; x < 8; x++) {
                processor.set(x, y, 1);
            }
        }
        return new ImagePlus(title, processor);
    }

    /** Guards the fixture: every family must be represented in the layout. */
    @Test
    public void theLayoutCoversEveryFamily() {
        List<MethodGroup> layout = MethodGroup.defaultLayout();
        for (EngineFamily family : EngineFamily.values()) {
            boolean present = false;
            for (int i = 0; i < layout.size() && !present; i++) {
                present = layout.get(i).family() == family;
            }
            assertTrue("no dialog group for the " + family.displayName() + " family",
                    present);
        }
    }
}
