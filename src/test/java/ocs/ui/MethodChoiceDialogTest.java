package ocs.ui;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.process.ByteProcessor;
import ij.process.ShortProcessor;
import ocs.engine.ColocEngine;
import ocs.engine.EngineInputs;
import ocs.engine.EngineRegistry;
import ocs.engine.ThresholdBearing;
import ocs.nullmodel.NullModelRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.swing.JCheckBox;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The frame around the chooser: Run, Cancel, and what survives the dialog.
 *
 * <p>The window itself needs a screen, so the dialog builds its window only when
 * asked to show one. Everything up to that point — the buttons, their wiring, the
 * checks, what is written to preferences — is built without one and is tested
 * here. If these behaviours lived inside the window they would have no tests at
 * all, and "Run was enabled on a setting the dialog had already called invalid"
 * is not something to find out in Fiji.
 */
public class MethodChoiceDialogTest {

    private static final int SIZE = 24;

    @BeforeClass
    public static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Before
    @After
    public void noStoredSelection() {
        // ij.Prefs is process-wide static state. Clearing on both sides means a
        // test never inherits another's selection, and never leaves one behind
        // for whatever runs next.
        RememberedMethods.forget();
    }

    // ---------- Run ----------

    @Test
    public void runIsEnabledWhenThereIsSomethingToRun() {
        MethodChoiceDialog dialog = dialog();
        assertTrue(dialog.runButton().isEnabled());
        assertNull(dialog.methods().whyRunIsDisabled());
    }

    @Test
    public void emptyingTheSelectionDisablesRunWithoutAnyoneAskingItTo() {
        // The frame subscribes to the chooser rather than polling it, so this
        // fails if the notification is ever dropped.
        MethodChoiceDialog dialog = dialog();
        dialog.methods().model().setSelected("cpc", false);
        dialog.methods().model().setSelected("volume-overlap", false);
        dialog.methods().refresh();

        assertFalse(dialog.runButton().isEnabled());
    }

    @Test
    public void aSettingThatIsNotANumberDisablesRun() {
        MethodChoiceDialog dialog = dialog();
        fieldFor(dialog, "volume-overlap").setText("thirty");
        assertFalse("typing must reach Run, not just the cost line",
                dialog.runButton().isEnabled());
    }

    @Test
    public void pressingRunOnAnInvalidSelectionDoesNothing() {
        // The button is disabled, but the Enter key is a separate path to the
        // default button, and a run started from an invalid state is worse than
        // a key press that appears to do nothing.
        MethodChoiceDialog dialog = dialog();
        fieldFor(dialog, "volume-overlap").setText("thirty");
        dialog.runPressed();

        assertNull(dialog.choice());
        assertNull("and nothing may be remembered either", RememberedMethods.read());
    }

    // ---------- what Run produces ----------

    @Test
    public void runProducesTheSelectionAndItsSettings() {
        MethodChoiceDialog dialog = dialog();
        fieldFor(dialog, "volume-overlap").setText("45");
        dialog.runPressed();

        MethodChoice choice = dialog.choice();
        assertNotNull(choice);
        assertEquals(Arrays.asList("cpc", "volume-overlap"), choice.engineIds());
        assertEquals(45.0, choice.thresholdFor("volume-overlap"), 1e-9);
        assertTrue("a method with no setting reports none",
                Double.isNaN(choice.thresholdFor("cpc")));
    }

    @Test
    public void everyPresetDescriptionFitsTheChooserWidth() {
        // The chooser is 560 wide; a description that ran past it put a
        // sideways scroll bar under the whole list (GUI checks, stage 08).
        MethodChoiceDialog dialog = new MethodChoiceDialog(
                EngineRegistry.createDefault(), everythingAvailable(), null);
        javax.swing.JComboBox<?> presets = null;
        for (Component c : allComponents(dialog.methods())) {
            if (c instanceof javax.swing.JComboBox) {
                presets = (javax.swing.JComboBox<?>) c;
            }
        }
        for (Preset preset : Preset.shipped()) {
            presets.setSelectedItem(preset.name());
            assertEquals(preset.name(), presets.getSelectedItem());
            boolean wrapped = false;
            for (Component c : allComponents(dialog.methods())) {
                if (c instanceof javax.swing.JLabel) {
                    String text = ((javax.swing.JLabel) c).getText();
                    wrapped |= text != null && text.startsWith("<html>")
                            && text.contains(preset.description().substring(0, 20));
                }
            }
            // Headless font metrics are narrower than a real screen's, so the
            // width alone cannot catch an unwrapped line; the wrap must be there.
            assertTrue(preset.name() + ": description is wrapped", wrapped);
            int width = dialog.methods().getPreferredSize().width;
            assertTrue(preset.name() + ": the chooser needs " + width + " px",
                    width <= 540);
        }
    }

    private static List<Component> allComponents(Container root) {
        List<Component> all = new ArrayList<Component>();
        for (Component c : root.getComponents()) {
            all.add(c);
            if (c instanceof Container) {
                all.addAll(allComponents((Container) c));
            }
        }
        return all;
    }

    @Test
    public void theDiscoveryPresetClassifiesTheMethodsAndTouchingItStops() {
        MethodChoiceDialog dialog = new MethodChoiceDialog(
                EngineRegistry.createDefault(), everythingAvailable(), null);
        MethodSelectionModel model = dialog.methods().model();
        model.apply(Preset.byName("Discovery"));
        dialog.syncChecksFromModel();
        assertTrue("the Discovery preset must produce a Discovery table",
                model.runsDiscovery());
        dialog.runPressed();
        assertTrue(dialog.choice().runsDiscovery());
        ocs.OCSParameters built = ocs.OCSParameters.builder(
                Arrays.asList(labels("A"), labels("B"))).from(dialog.choice()).build();
        assertTrue(built.runsDiscovery());

        model.setRunsThresholdSweep(false);
        assertFalse("Discovery reads the sweep, so without it there is none",
                model.runsDiscovery());
        model.setRunsThresholdSweep(true);
        model.setSelected("cpc", false);
        assertFalse("a hand-picked selection is not the Discovery preset",
                model.runsDiscovery());
        model.apply(Preset.byName("Object colocalization"));
        assertFalse(model.runsDiscovery());
    }

    @Test
    public void runRemembersTheSelectionForNextTime() {
        MethodChoiceDialog dialog = dialog();
        dialog.methods().model().setSelected("containment", true);
        dialog.runPressed();

        String remembered = RememberedMethods.read();
        assertNotNull(remembered);
        assertTrue(remembered, remembered.contains("containment"));
    }

    @Test
    public void cancelRemembersNothingAndProducesNothing() {
        // Otherwise Cancel becomes a way of changing settings.
        MethodChoiceDialog dialog = dialog();
        dialog.methods().model().setSelected("containment", true);
        dialog.cancelPressed();

        assertNull(dialog.choice());
        assertNull(RememberedMethods.read());
    }

    @Test
    public void aCancelAfterARunLeavesTheRunsSelectionRemembered() {
        // Two dialogs in one session: what was confirmed must not be undone by
        // opening the dialog again and backing out.
        MethodChoiceDialog first = dialog();
        first.methods().model().setSelected("containment", true);
        first.runPressed();
        String afterRun = RememberedMethods.read();

        MethodChoiceDialog second = dialog();
        second.cancelPressed();

        assertEquals(afterRun, RememberedMethods.read());
    }

    // ---------- the extra checks ----------

    @Test
    public void theChecksStartWhereThePresetPutThem() {
        // Quick look runs none of them, which is why it is quick.
        MethodChoiceDialog dialog = dialog();
        assertFalse(dialog.methods().model().runsNullModel());
        assertFalse(dialog.methods().model().runsAgreement());
        assertFalse(dialog.methods().model().runsThresholdSweep());
    }

    @Test
    public void theChecksAreCarriedIntoWhatTheRunAsksFor() {
        MethodChoiceDialog dialog = dialog();
        dialog.methods().model().setRunsNullModel(true);
        dialog.runPressed();

        assertTrue(dialog.choice().runsNullModel());
        assertFalse(dialog.choice().runsThresholdSweep());
    }

    @Test
    public void theChecksAreRememberedSeparatelyFromTheMethods() {
        // They are the expensive part. A user who turned the chance test off
        // must not find it back on next session with nothing saying why.
        MethodChoiceDialog dialog = dialog();
        dialog.methods().model().setRunsNullModel(true);
        dialog.methods().model().setRunsThresholdSweep(false);
        dialog.runPressed();

        MethodSelectionModel reopened = MethodSelectionModel.restoredFrom(
                EngineRegistry.createDefault(), labelsOnly(), RememberedMethods.read());
        RememberedMethods.restoreChecks(reopened);

        assertTrue(reopened.runsNullModel());
        assertFalse(reopened.runsThresholdSweep());
    }

    @Test
    public void aPresetRedrawsTheCheckSwitchesAndDoesNotJustChangeTheModel() {
        // The switches are on the frame and the preset dropdown is on the chooser,
        // so nothing connects them except the notification. If that is dropped,
        // the model runs the chance test while the switch beside Run says it will
        // not — the user is told one thing and gets another.
        MethodChoiceDialog dialog = dialog();
        assertFalse(checkSwitch(dialog, MethodChoiceDialog.CHANCE).isSelected());

        dialog.methods().model().apply(Preset.byName("Object colocalization"));
        dialog.methods().refresh();

        assertTrue("that preset tests against chance",
                checkSwitch(dialog, MethodChoiceDialog.CHANCE).isSelected());
    }

    @Test
    public void tickingACheckSwitchReachesTheModelAndTheEstimate() {
        MethodChoiceDialog dialog = dialog();
        double before = dialog.methods().model().estimatedCost();

        checkSwitch(dialog, MethodChoiceDialog.CHANCE).doClick();

        assertTrue(dialog.methods().model().runsNullModel());
        assertFalse("and only that switch — each writes back its own check",
                dialog.methods().model().runsThresholdSweep());
        assertFalse(dialog.methods().model().runsAgreement());
        assertTrue("the chance test multiplies the run, so the estimate must move",
                dialog.methods().model().estimatedCost() > before * 50);
        assertTrue(dialog.methods().costText(),
                dialog.methods().costText().contains("estimated cost"));
    }

    @Test
    public void unpickingEverythingClearsTheMemoryRatherThanStoringABlank() {
        // A stored blank would read back as "the user chose no methods", which is
        // not a thing anyone can choose — Run refuses it.
        MethodChoiceDialog dialog = dialog();
        dialog.methods().model().setSelected("containment", true);
        dialog.runPressed();
        assertNotNull(RememberedMethods.read());

        MethodSelectionModel empty = MethodSelectionModel.restoredFrom(
                EngineRegistry.createDefault(), labelsOnly(), null);
        empty.setSelected("cpc", false);
        empty.setSelected("volume-overlap", false);
        RememberedMethods.remember(empty);

        assertNull("blank clears rather than storing nothing-selected",
                RememberedMethods.read());
    }

    @Test
    public void withNothingRememberedThePresetsOwnChecksStand() {
        // A stored 'false' from a previous session must not quietly override a
        // preset that says it runs the chance test.
        MethodSelectionModel model = MethodSelectionModel.openedOn(
                EngineRegistry.createDefault(), everythingAvailable());
        model.apply(Preset.byName("Object colocalization"));
        RememberedMethods.restoreChecks(model);

        assertTrue("the preset says it tests against chance", model.runsNullModel());
    }

    // ---------- how many shuffles, and which seed ----------

    @Test
    public void theShuffleCountAndSeedStartAtTheDocumentedDefaults() {
        MethodChoiceDialog dialog = dialog();
        assertEquals(NullModelRunner.DEFAULT_PERMUTATIONS,
                dialog.methods().model().permutations());
        assertEquals(NullModelRunner.DEFAULT_SEED, dialog.methods().model().seed());
        assertEquals("1000", settingField(dialog, MethodChoiceDialog.SHUFFLES).getText());
    }

    @Test
    public void theSettingsAreLiveOnlyWhileTheChanceTestIs() {
        // An editable box that feeds nothing is how somebody comes to believe
        // they set something they did not.
        MethodChoiceDialog dialog = dialog();
        JTextField shuffles = settingField(dialog, MethodChoiceDialog.SHUFFLES);
        assertFalse(shuffles.isEnabled());

        checkSwitch(dialog, MethodChoiceDialog.CHANCE).doClick();
        assertTrue(shuffles.isEnabled());
    }

    @Test
    public void raisingTheShuffleCountRaisesTheEstimateInProportion() {
        // Ten times the shuffles is ten times the run. An estimate pinned to the
        // default would understate it by exactly that factor.
        MethodChoiceDialog dialog = dialog();
        checkSwitch(dialog, MethodChoiceDialog.CHANCE).doClick();
        double atDefault = dialog.methods().model().estimatedCost();

        settingField(dialog, MethodChoiceDialog.SHUFFLES).setText("10000");

        assertEquals(10000, dialog.methods().model().permutations());
        assertTrue("the estimate must follow the field",
                dialog.methods().model().estimatedCost() > atDefault * 9);
    }

    @Test
    public void theSmallestReachablePIsStatedRatherThanLeftToBeDiscovered() {
        // The suite reports a two-sided p, so with a hundred shuffles it can
        // never go below 2/101 before ties raise the floor further. Showing the
        // one-sided floor would promise resolution the test does not have.
        MethodChoiceDialog dialog = dialog();
        dialog.methods().model().setPermutations(100);
        assertEquals(2.0 / 101.0,
                dialog.methods().model().smallestReachableP(), 1e-12);

        dialog.methods().model().setPermutations(999);
        assertEquals(0.002,
                dialog.methods().model().smallestReachableP(), 1e-12);
    }

    @Test
    public void aShuffleCountBelowOneBlocksRun() {
        MethodChoiceDialog dialog = dialog();
        checkSwitch(dialog, MethodChoiceDialog.CHANCE).doClick();
        settingField(dialog, MethodChoiceDialog.SHUFFLES).setText("0");

        assertFalse(dialog.runButton().isEnabled());
        assertTrue(dialog.whyRunIsDisabled(),
                dialog.whyRunIsDisabled().contains("at least one"));
    }

    @Test
    public void aBadShuffleCountIsIgnoredWhileTheChanceTestIsOff() {
        // Nothing reads it, so it cannot make the run wrong, and blocking Run
        // over a field that feeds nothing would be a dead end with no way out
        // except editing a box the dialog has greyed.
        MethodChoiceDialog dialog = dialog();
        settingField(dialog, MethodChoiceDialog.SHUFFLES).setText("0");
        assertNull(dialog.whyRunIsDisabled());
    }

    @Test
    public void aSeedTooLargeToRecordExactlyBlocksRun() {
        // Past 2^53 the seed written into the run record is a different number
        // from the one that ran, so the record would replay a different result
        // while looking like a faithful one.
        MethodChoiceDialog dialog = dialog();
        checkSwitch(dialog, MethodChoiceDialog.CHANCE).doClick();
        settingField(dialog, MethodChoiceDialog.SEED).setText(String.valueOf(Long.MAX_VALUE));

        assertFalse(dialog.runButton().isEnabled());
        assertTrue(dialog.whyRunIsDisabled(),
                dialog.whyRunIsDisabled().contains("replay"));
    }

    @Test
    public void theShufflesAndSeedTravelOnTheSlip() {
        MethodChoiceDialog dialog = dialog();
        checkSwitch(dialog, MethodChoiceDialog.CHANCE).doClick();
        settingField(dialog, MethodChoiceDialog.SHUFFLES).setText("250");
        settingField(dialog, MethodChoiceDialog.SEED).setText("77");
        dialog.runPressed();

        assertEquals(250, dialog.choice().permutations());
        assertEquals(77L, dialog.choice().seed());
    }

    @Test
    public void theSeedTravelsEvenWithTheChanceTestOff() {
        // It goes into the run record either way, and a record whose seed field
        // appears only sometimes is a record nobody trusts to replay.
        MethodChoiceDialog dialog = dialog();
        dialog.runPressed();
        assertEquals(NullModelRunner.DEFAULT_SEED, dialog.choice().seed());
    }

    @Test
    public void theShufflesAndSeedAreRememberedExactly() {
        // The seed is stored as text, not as a number: ij.Prefs keeps numbers as
        // doubles, and a seed that survives only approximately replays a
        // different run than the one recorded.
        MethodChoiceDialog dialog = dialog();
        checkSwitch(dialog, MethodChoiceDialog.CHANCE).doClick();
        settingField(dialog, MethodChoiceDialog.SHUFFLES).setText("250");
        settingField(dialog, MethodChoiceDialog.SEED).setText("9007199254740991");
        dialog.runPressed();

        MethodSelectionModel reopened = MethodSelectionModel.restoredFrom(
                EngineRegistry.createDefault(), labelsOnly(), RememberedMethods.read());
        RememberedMethods.restoreChecks(reopened);

        assertEquals(250, reopened.permutations());
        assertEquals(9007199254740991L, reopened.seed());
    }

    @Test
    public void reopeningShowsTheRememberedSettingsAndNotTheDefaults() {
        // The failure this rules out: the fields show 100 shuffles while the run
        // quietly uses the remembered 250. Both numbers are defensible on their
        // own; showing one and running the other is not.
        MethodChoiceDialog first = dialog();
        checkSwitch(first, MethodChoiceDialog.CHANCE).doClick();
        settingField(first, MethodChoiceDialog.SHUFFLES).setText("250");
        settingField(first, MethodChoiceDialog.SEED).setText("77");
        first.runPressed();

        MethodChoiceDialog second = MethodChoiceDialog.reopened(
                EngineRegistry.createDefault(), labelsOnly());

        assertEquals("250",
                settingField(second, MethodChoiceDialog.SHUFFLES).getText());
        assertEquals("77", settingField(second, MethodChoiceDialog.SEED).getText());
        assertEquals(250, second.methods().model().permutations());
        assertTrue("and the switch that owns them is on too",
                checkSwitch(second, MethodChoiceDialog.CHANCE).isSelected());
    }

    // ---------- the slip ----------

    @Test
    public void theSlipBuildsEnginesAtTheChosenSettingNotTheShippedDefault() {
        // The whole reason the settings are collected. An engine taken straight
        // from the registry keeps its default forever, so a report describing
        // the user's number while running the default is the failure to avoid.
        MethodChoiceDialog dialog = dialog();
        fieldFor(dialog, "volume-overlap").setText("45");
        dialog.runPressed();

        EngineRegistry registry = EngineRegistry.createDefault();
        double shipped = ((ThresholdBearing) registry.byId("volume-overlap"))
                .threshold();
        assertEquals("fixture assumes the default is not 45", 30.0, shipped, 1e-9);

        List<ColocEngine> engines = dialog.choice().configuredEngines(registry);
        ColocEngine overlap = null;
        for (int i = 0; i < engines.size(); i++) {
            if ("volume-overlap".equals(engines.get(i).id())) {
                overlap = engines.get(i);
            }
        }
        assertNotNull(overlap);
        assertEquals(45.0, ((ThresholdBearing) overlap).threshold(), 1e-9);
        assertEquals("and the registry's own copy is untouched",
                30.0, ((ThresholdBearing) registry.byId("volume-overlap")).threshold(),
                1e-9);
    }

    @Test
    public void theSlipKeepsTheMethodsInRegistrationOrder() {
        // Tick order is whatever the user happened to do; every table downstream
        // is in registration order, and two orders would eventually disagree.
        MethodChoiceDialog dialog = dialog();
        dialog.methods().model().setSelected("containment", true);
        dialog.methods().model().setSelected("bounding-box", true);
        dialog.runPressed();

        // Registration order, not the order they were ticked in: containment is
        // registered before bounding-box, and both after volume-overlap.
        assertEquals(Arrays.asList("cpc", "volume-overlap", "containment",
                "bounding-box"), dialog.choice().engineIds());
    }

    // ---------- settings the engine refuses ----------

    @Test
    public void aSettingOutsideTheMethodsOwnScaleDisablesRun() {
        // 30 is a sensible overlap percentage and a nonsense Jaccard index, so
        // the range cannot live in the dialog. It asks the engine.
        MethodChoiceDialog dialog = dialog();
        dialog.methods().model().setSelected("jaccard-dice", true);
        fieldFor(dialog, "jaccard-dice").setText("30");
        dialog.methods().refresh();

        assertFalse(dialog.runButton().isEnabled());
        assertTrue(dialog.methods().whyRunIsDisabled(),
                dialog.methods().whyRunIsDisabled().contains("0 to 1"));
    }

    @Test
    public void anImpossiblePercentageDisablesRun() {
        MethodChoiceDialog dialog = dialog();
        fieldFor(dialog, "volume-overlap").setText("-5");
        assertFalse(dialog.runButton().isEnabled());
    }

    // ---------- fixtures ----------

    private static MethodChoiceDialog dialog() {
        return new MethodChoiceDialog(
                EngineRegistry.createDefault(), labelsOnly(), null);
    }

    /** By engine id, which is stable, rather than by display wording, which is not. */
    private static JTextField fieldFor(MethodChoiceDialog dialog, String engineId) {
        List<JTextField> fields = new ArrayList<JTextField>();
        collect(dialog.methods(), fields);
        for (int i = 0; i < fields.size(); i++) {
            if (engineId.equals(fields.get(i).getClientProperty(
                    MethodSelectionPanel.ENGINE_ID_PROPERTY))) {
                return fields.get(i);
            }
        }
        throw new AssertionError("no setting field for '" + engineId + "'");
    }

    /** The shuffle-count or seed field, by its stable name. */
    private static JTextField settingField(MethodChoiceDialog dialog, String name) {
        List<JTextField> fields = new ArrayList<JTextField>();
        collect(dialog.content(), JTextField.class, fields);
        for (int i = 0; i < fields.size(); i++) {
            if (name.equals(fields.get(i).getClientProperty(
                    MethodChoiceDialog.CHECK_PROPERTY))) {
                return fields.get(i);
            }
        }
        throw new AssertionError("no setting field named '" + name + "'");
    }

    /** One of the three extra-check switches, by its stable name. */
    private static JCheckBox checkSwitch(MethodChoiceDialog dialog, String name) {
        List<JCheckBox> boxes = new ArrayList<JCheckBox>();
        collect(dialog.content(), JCheckBox.class, boxes);
        for (int i = 0; i < boxes.size(); i++) {
            if (name.equals(boxes.get(i).getClientProperty(
                    MethodChoiceDialog.CHECK_PROPERTY))) {
                return boxes.get(i);
            }
        }
        throw new AssertionError("no check switch named '" + name + "'");
    }

    private static void collect(Container root, List<JTextField> found) {
        collect(root, JTextField.class, found);
    }

    private static <T> void collect(Container root, Class<T> type, List<T> found) {
        Component[] children = root.getComponents();
        for (int i = 0; i < children.length; i++) {
            if (type.isInstance(children[i])) {
                found.add(type.cast(children[i]));
            }
            if (children[i] instanceof Container) {
                collect((Container) children[i], type, found);
            }
        }
    }

    private static EngineInputs labelsOnly() {
        // With a region, because the chance test these tests switch on greys
        // itself without one.
        return EngineInputs.builder(Arrays.asList(labels("A"), labels("B")))
                .channelNames(Arrays.asList("A", "B"))
                .domain(Arrays.asList(new Roi(0, 0, SIZE, SIZE)))
                .build();
    }

    private static EngineInputs everythingAvailable() {
        return EngineInputs.builder(Arrays.asList(labels("A"), labels("B")))
                .channelNames(Arrays.asList("A", "B"))
                .intensityImages(Arrays.asList(intensity("A raw"), intensity("B raw")))
                .domain(Arrays.asList(new Roi(0, 0, SIZE, SIZE)))
                .build();
    }

    private static ImagePlus labels(String title) {
        ShortProcessor processor = new ShortProcessor(SIZE, SIZE);
        for (int y = 2; y < 8; y++) {
            for (int x = 2; x < 8; x++) {
                processor.set(x, y, 1);
            }
        }
        for (int y = 12; y < 18; y++) {
            for (int x = 12; x < 18; x++) {
                processor.set(x, y, 2);
            }
        }
        return new ImagePlus(title, processor);
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
