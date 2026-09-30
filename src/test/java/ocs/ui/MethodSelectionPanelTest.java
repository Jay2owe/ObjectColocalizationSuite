package ocs.ui;

import ij.ImagePlus;
import ij.process.ShortProcessor;
import ocs.engine.EngineInputs;
import ocs.engine.EngineRegistry;
import org.junit.BeforeClass;
import org.junit.Test;
import sc.fiji.oc3d.core.ui.CollapsiblePane;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What the panel actually puts on screen.
 *
 * <p>The rules are all in {@link MethodSelectionModel} and tested there. What is
 * left here is the wiring — that a checkbox writes back, that a preset redraws
 * the ticks, that an unavailable method is disabled with its reason attached,
 * and that the space-saving table really has one header rather than one per row.
 * Wiring is exactly the part that compiles fine and is silently wrong.
 */
public class MethodSelectionPanelTest {

    private static final int SIZE = 20;

    @BeforeClass
    public static void headless() {
        // Swing components can be built without a display; only showing them
        // needs one. Stated explicitly so this runs the same way on a CI box.
        System.setProperty("java.awt.headless", "true");
    }

    // ---------- the table ----------

    @Test
    public void methodsSharingASettingGetOneColumnHeaderNotOnePerRow() {
        // The whole point of grouping. Three overlap methods, one "Threshold (%)"
        // header, three rows — instead of three separately labelled fields.
        MethodSelectionPanel panel = panel();
        CollapsiblePane overlap = sectionTitled(panel, "Object overlap");

        List<JLabel> labels = descendants(overlap, JLabel.class);
        int headers = 0;
        for (int i = 0; i < labels.size(); i++) {
            if ("Threshold (%)".equals(labels.get(i).getText())) {
                headers++;
            }
        }
        assertEquals("exactly one shared column header", 1, headers);
        assertEquals("three rows under it", 3,
                descendants(overlap, JCheckBox.class).size());
        assertEquals("three settings, one per row", 3,
                descendants(overlap, JTextField.class).size());
    }

    @Test
    public void aGroupWithNoSettingHasNoFieldsAtAll() {
        MethodSelectionPanel panel = panel();
        CollapsiblePane geometric = sectionTitled(panel, "Object position");
        assertEquals(1, descendants(geometric, JCheckBox.class).size());
        assertTrue("a method with no setting must show no empty box",
                descendants(geometric, JTextField.class).isEmpty());
    }

    @Test
    public void aRowShowsTheEnginesOwnDefaultSetting() {
        MethodSelectionPanel panel = panel();
        CollapsiblePane overlap = sectionTitled(panel, "Object overlap");
        List<JTextField> fields = descendants(overlap, JTextField.class);
        assertEquals("30", fields.get(0).getText());
    }

    // ---------- folding ----------

    @Test
    public void everyGroupIsASectionAndTheSpecialisedOnesStartFolded() {
        MethodSelectionPanel panel = panel();
        assertEquals(MethodGroup.defaultLayout().size(), panel.sections().size());

        assertTrue("the cheapest object group must open expanded",
                sectionTitled(panel, "Object overlap").isExpanded());
        assertFalse("the spatial family must start folded",
                sectionTitled(panel, "Spatial statistics").isExpanded());
    }

    @Test
    public void aFoldedSectionSaysHowManyMethodsAreInside() {
        // A method a user cannot find is a method that does not exist to them.
        // The count is what tells them the fold is worth opening.
        MethodSelectionPanel panel = panel();
        assertNotNull(sectionTitled(panel, "Spatial statistics"));
    }

    // ---------- wiring ----------

    @Test
    public void tickingABoxWritesBackToTheModel() {
        MethodSelectionPanel panel = panel();
        assertFalse(panel.model().isSelected("containment"));

        JCheckBox box = boxFor(panel, "containment");
        assertNotNull(box);
        box.doClick();
        assertTrue("clicking must reach the model", panel.model().isSelected("containment"));
        box.doClick();
        assertFalse("and unticking must too", panel.model().isSelected("containment"));
    }

    @Test
    public void anUnavailableMethodIsDisabledAndCarriesItsReason() {
        // Label images only, so the per-object intensity method cannot run.
        MethodSelectionPanel panel = panel();
        JCheckBox box = boxFor(panel, "per-object-intensity");
        assertNotNull("something must be unavailable on labels alone", box);
        assertFalse(box.isEnabled());
        assertNotNull("a greyed row must say why", box.getToolTipText());
        assertTrue(box.getToolTipText(),
                box.getToolTipText().toLowerCase().contains("needs"));
    }

    @Test
    public void theSettingBesideAnUnavailableMethodIsDisabledToo() {
        // Otherwise a user edits a number in a row that can never run, and
        // reasonably expects that to have meant something.
        MethodSelectionPanel panel = panel();
        CollapsiblePane intensity = sectionTitled(panel, "Intensity correlation");
        List<JTextField> fields = descendants(intensity, JTextField.class);
        assertFalse(fields.isEmpty());
        assertFalse(fields.get(0).isEnabled());
    }

    // ---------- readback ----------

    @Test
    public void onlySelectedMethodsContributeASetting() {
        // An unticked row's field is still on screen and still editable; carrying
        // its value into the run record would record a setting nobody chose.
        MethodSelectionPanel panel = panel();
        Map<String, Double> thresholds = panel.thresholds();
        assertTrue(thresholds.containsKey("volume-overlap"));
        assertFalse("containment is not ticked", thresholds.containsKey("containment"));
    }

    @Test
    public void aSettingThatIsNotANumberBlocksRunRatherThanDefaultingQuietly() {
        MethodSelectionPanel panel = panel();
        CollapsiblePane overlap = sectionTitled(panel, "Object overlap");
        descendants(overlap, JTextField.class).get(0).setText("thirty");

        assertNotNull(panel.whyRunIsDisabled());
        assertTrue(panel.whyRunIsDisabled(),
                panel.whyRunIsDisabled().contains("not a number"));
        assertFalse("a bad value must not silently become the default",
                panel.thresholds().containsKey("volume-overlap"));
    }

    @Test
    public void anEmptySelectionBlocksRunWithAPlainMessage() {
        MethodSelectionPanel panel = panel();
        panel.model().setSelected("cpc", false);
        panel.model().setSelected("volume-overlap", false);
        assertEquals("Select at least one method.", panel.whyRunIsDisabled());
    }

    @Test
    public void aValidSelectionBlocksNothing() {
        assertNull(panel().whyRunIsDisabled());
    }

    // ---------- the estimate ----------

    @Test
    public void theCostLineNamesTheMethodCountAndTheEstimate() {
        // Shown before Run, not discovered after it.
        String text = panel().costText();
        assertTrue(text, text.contains("2 methods"));
        assertTrue(text, text.contains("estimated cost"));
    }

    // ---------- fixtures ----------

    private static MethodSelectionPanel panel() {
        return new MethodSelectionPanel(
                EngineRegistry.createDefault(), labelsOnly(), null);
    }

    private static EngineInputs labelsOnly() {
        return EngineInputs.builder(Arrays.asList(labels("A"), labels("B")))
                .channelNames(Arrays.asList("A", "B"))
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

    private static CollapsiblePane sectionTitled(MethodSelectionPanel panel,
            String title) {
        List<CollapsiblePane> sections = panel.sections();
        List<MethodGroup> layout = MethodGroup.defaultLayout();
        for (int i = 0; i < layout.size(); i++) {
            if (layout.get(i).title().equals(title)) {
                return sections.get(i);
            }
        }
        throw new AssertionError("no section titled '" + title + "'");
    }

    /** By engine id, not by display wording, which is free to change. */
    private static JCheckBox boxFor(MethodSelectionPanel panel, String engineId) {
        List<JCheckBox> boxes = descendants(panel, JCheckBox.class);
        for (int i = 0; i < boxes.size(); i++) {
            if (engineId.equals(boxes.get(i).getClientProperty(
                    MethodSelectionPanel.ENGINE_ID_PROPERTY))) {
                return boxes.get(i);
            }
        }
        return null;
    }

    private static <T extends JComponent> List<T> descendants(Container root,
            Class<T> type) {
        List<T> found = new ArrayList<T>();
        collect(root, type, found);
        return found;
    }

    private static <T extends JComponent> void collect(Container root, Class<T> type,
            List<T> found) {
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
}
