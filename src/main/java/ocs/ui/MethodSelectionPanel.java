package ocs.ui;

import ocs.engine.ColocEngine;
import ocs.engine.EngineInputs;
import ocs.engine.EngineRegistry;
import ocs.engine.ThresholdBearing;
import sc.fiji.oc3d.core.ui.CollapsiblePane;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The method chooser: one preset dropdown, then families as sections that fold.
 *
 * <p>Thirteen methods is the number at which a dialog stops being a form and
 * starts being a document. Three things keep it a form. The <b>preset dropdown</b>
 * means the first decision is one choice, not thirteen. <b>Sections fold</b>, so
 * the specialised families are present but not in the way. And methods that ask
 * for the same thing sit in <b>one table under one column header</b> — three
 * overlap variants read as three rows of one idea rather than three separate
 * questions.
 *
 * <p>All the behaviour lives in {@link MethodSelectionModel}, which has no Swing
 * in it and is tested without a screen. This class draws that and writes back to
 * it; if a rule looks interesting it belongs in the model, not here.
 */
public final class MethodSelectionPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /** Client property on each row's checkbox, holding its engine id. */
    public static final String ENGINE_ID_PROPERTY = "ocs.engineId";

    private static final String CUSTOM = "Custom";
    /** Wide enough for the longest shipped name without clipping it. */
    private static final int NAME_WIDTH = 300;
    private static final int FIELD_WIDTH = 70;
    /** Narrower than the chooser, so the description wraps instead of scrolling. */
    private static final int DESCRIPTION_WIDTH = 380;

    private final EngineRegistry registry;
    private final MethodSelectionModel model;
    private final Map<String, JCheckBox> boxes = new LinkedHashMap<String, JCheckBox>();
    private final Map<String, JTextField> fields = new LinkedHashMap<String, JTextField>();
    private final List<CollapsiblePane> sections = new ArrayList<CollapsiblePane>();
    private final List<ChangeListener> listeners = new ArrayList<ChangeListener>();
    private final JComboBox<String> presets = new JComboBox<String>();
    private final JLabel presetDescription = new JLabel();
    private final JLabel cost = new JLabel();

    /** True while a preset is being applied, so listeners do not fight it. */
    private boolean applying;

    public MethodSelectionPanel(EngineRegistry registry, EngineInputs inputs,
            String remembered) {
        this.registry = registry;
        this.model = MethodSelectionModel.restoredFrom(registry, inputs, remembered);

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setAlignmentX(Component.LEFT_ALIGNMENT);
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        List<MethodGroup> layout = MethodGroup.defaultLayout();
        MethodGroup.requireCompleteCover(layout, registry);

        addPresetChooser();
        add(Box.createVerticalStrut(10));
        for (int i = 0; i < layout.size(); i++) {
            addSection(layout.get(i));
        }
        add(Box.createVerticalStrut(8));
        add(leftAligned(cost));

        syncFromModel();
    }

    /** The selection, the diagnostics and the estimate. No Swing inside. */
    public MethodSelectionModel model() {
        return model;
    }

    /**
     * Called whenever anything on this panel changes: a tick, a preset, a keystroke
     * in a setting.
     *
     * <p>Exists so the frame around this panel can keep its Run button honest
     * without knowing which widget moved. Without it the frame would have to
     * attach listeners to each of this panel's internals, and every new row type
     * would silently stop updating Run.
     */
    public void addChangeListener(ChangeListener listener) {
        listeners.add(listener);
    }

    /**
     * Re-reads the model and updates the cost line.
     *
     * <p>For changes made <i>through the model</i> rather than through this panel
     * — the frame's diagnostics switches, which multiply the estimate by about a
     * hundred and so must show up here immediately.
     */
    public void refresh() {
        syncCost();
    }

    /**
     * The setting each threshold-bearing method is currently showing.
     *
     * <p>Only for selected methods: an unticked row's field is still on screen
     * and still editable, and carrying its value forward would put a setting
     * nobody chose into the run record.
     */
    public Map<String, Double> thresholds() {
        Map<String, Double> values = new LinkedHashMap<String, Double>();
        List<String> ids = model.selectedIds();
        for (int i = 0; i < ids.size(); i++) {
            JTextField field = fields.get(ids.get(i));
            if (field == null) {
                continue;
            }
            try {
                values.put(ids.get(i), Double.valueOf(field.getText().trim()));
            } catch (NumberFormatException notANumber) {
                // Left out rather than defaulted. whyRunIsDisabled() is what
                // tells the user; silently substituting the default here
                // would run a different analysis from the one on screen.
                continue;
            }
        }
        return Collections.unmodifiableMap(values);
    }

    /**
     * Why Run should stay disabled, or null when it should not.
     *
     * <p>One message at a time and the most basic first, so a user fixing them
     * in order is never told about a bad number in a row they are about to untick.
     */
    public String whyRunIsDisabled() {
        if (model.isEmpty()) {
            return "Select at least one method.";
        }
        List<String> ids = model.selectedIds();
        for (int i = 0; i < ids.size(); i++) {
            JTextField field = fields.get(ids.get(i));
            if (field == null) {
                continue;
            }
            String text = field.getText().trim();
            ColocEngine engine = registry.byId(ids.get(i));
            try {
                double value = Double.parseDouble(text);
                if (Double.isNaN(value) || Double.isInfinite(value)) {
                    return engine.displayName()
                            + ": '" + text + "' is not a usable setting.";
                }
                if (engine instanceof ThresholdBearing) {
                    // The engine owns what its own scale admits, so ask it rather
                    // than keeping a second copy of the ranges here that could
                    // drift. 30 is a sensible overlap percentage and a nonsense
                    // Jaccard index, and only the engine knows which it is.
                    ((ThresholdBearing) engine).withThreshold(value);
                }
            } catch (NumberFormatException notANumber) {
                return engine.displayName()
                        + ": '" + text + "' is not a number.";
            } catch (IllegalArgumentException outOfRange) {
                return engine.displayName() + ": " + outOfRange.getMessage();
            }
        }
        return null;
    }

    /** Live text beside Run. Public so the dialog can mirror it in its footer. */
    public String costText() {
        return cost.getText();
    }

    /** The collapsible sections, in draw order. */
    public List<CollapsiblePane> sections() {
        return Collections.unmodifiableList(sections);
    }

    // ---------- building ----------

    private void addPresetChooser() {
        List<Preset> shipped = Preset.shipped();
        for (int i = 0; i < shipped.size(); i++) {
            presets.addItem(shipped.get(i).name());
        }
        presets.addItem(CUSTOM);
        presets.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                if (applying || CUSTOM.equals(presets.getSelectedItem())) {
                    return;
                }
                model.apply(Preset.byName((String) presets.getSelectedItem()));
                syncFromModel();
            }
        });

        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel label = new JLabel("Preset");
        fixWidth(label, 60);
        row.add(label);
        row.add(presets);
        row.add(Box.createHorizontalGlue());
        add(row);

        presetDescription.setFont(presetDescription.getFont()
                .deriveFont(Font.PLAIN, 11f));
        add(leftAligned(presetDescription));
    }

    private void addSection(MethodGroup group) {
        CollapsiblePane pane = new CollapsiblePane(
                group.title() + "  (" + group.engineIds().size() + ")",
                !group.isAdvanced());
        sections.add(pane);

        JLabel subtitle = new JLabel(group.subtitle());
        subtitle.setFont(subtitle.getFont().deriveFont(Font.ITALIC, 11f));
        pane.body().add(leftAligned(subtitle));

        if (group.hasSharedColumn()) {
            // One header for the whole group. This is the space saving: three
            // methods, one column title, instead of three labelled fields.
            String header = group.sharedColumn()
                    + (group.sharedColumnUnit().isEmpty()
                            ? "" : " (" + group.sharedColumnUnit() + ")");
            JPanel headerRow = new JPanel();
            headerRow.setOpaque(false);
            headerRow.setLayout(new BoxLayout(headerRow, BoxLayout.X_AXIS));
            headerRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            headerRow.add(Box.createHorizontalStrut(NAME_WIDTH));
            JLabel columnLabel = new JLabel(header);
            columnLabel.setFont(columnLabel.getFont().deriveFont(Font.PLAIN, 11f));
            headerRow.add(columnLabel);
            headerRow.add(Box.createHorizontalGlue());
            pane.body().add(headerRow);
        }

        List<String> ids = group.engineIds();
        for (int i = 0; i < ids.size(); i++) {
            pane.body().add(methodRow(ids.get(i), group));
        }
        add(pane);
    }

    private JComponent methodRow(final String engineId, MethodGroup group) {
        ColocEngine engine = registry.byId(engineId);
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        final JCheckBox box = new JCheckBox(engine.displayName());
        // The row carries its engine id, so anything looking a row up — a test,
        // a macro recorder, a future help overlay — matches on the stable id
        // rather than on display wording that is free to change.
        box.putClientProperty(ENGINE_ID_PROPERTY, engineId);
        fixWidth(box, NAME_WIDTH);
        boolean runnable = model.isRunnable(engineId);
        box.setEnabled(runnable);
        box.setToolTipText(runnable
                ? engine.displayName() : model.whyNotRunnable(engineId));
        box.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                if (applying) {
                    return;
                }
                model.setSelected(engineId, box.isSelected());
                syncPresetName();
                syncCost();
            }
        });
        boxes.put(engineId, box);
        row.add(box);

        if (group.hasSharedColumn()) {
            double initial = MethodGroup.defaultSettingOf(registry, engineId);
            JTextField field = new JTextField(format(initial), 6);
            field.putClientProperty(ENGINE_ID_PROPERTY, engineId);
            fixWidth(field, FIELD_WIDTH);
            field.setEnabled(runnable);
            field.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent event) {
                    syncCost();
                }

                @Override
                public void removeUpdate(DocumentEvent event) {
                    syncCost();
                }

                @Override
                public void changedUpdate(DocumentEvent event) {
                    syncCost();
                }
            });
            fields.put(engineId, field);
            row.add(field);
        }

        if (!runnable) {
            JLabel why = new JLabel("  " + model.whyNotRunnable(engineId));
            why.setFont(why.getFont().deriveFont(Font.ITALIC, 11f));
            row.add(why);
        }
        row.add(Box.createHorizontalGlue());
        return row;
    }

    // ---------- keeping the screen and the model agreed ----------

    private void syncFromModel() {
        applying = true;
        try {
            for (Map.Entry<String, JCheckBox> entry : boxes.entrySet()) {
                entry.getValue().setSelected(model.isSelected(entry.getKey()));
            }
            syncPresetName();
        } finally {
            applying = false;
        }
        syncCost();
    }

    private void syncPresetName() {
        applying = true;
        try {
            Preset matching = model.matchingPreset();
            presets.setSelectedItem(matching == null ? CUSTOM : matching.name());
            presetDescription.setText(wrapped(matching == null
                    ? "A selection of your own." : matching.description()));
        } finally {
            applying = false;
        }
    }

    private void syncCost() {
        double estimate = model.estimatedCost();
        String problem = whyRunIsDisabled();
        cost.setText(model.selectedCount() + " method"
                + (model.selectedCount() == 1 ? "" : "s")
                + "  ·  estimated cost " + format(estimate)
                + (problem == null ? "" : "   —   " + problem));
        fireChanged();
    }

    /** Every path that can change anything funnels through {@link #syncCost}. */
    private void fireChanged() {
        ChangeEvent event = new ChangeEvent(this);
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).stateChanged(event);
        }
    }

    // ---------- small helpers ----------

    private static JComponent leftAligned(JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(component);
        row.add(Box.createHorizontalGlue());
        return row;
    }

    private static void fixWidth(JComponent component, int width) {
        Dimension size = new Dimension(width, component.getPreferredSize().height);
        component.setPreferredSize(size);
        component.setMinimumSize(size);
        component.setMaximumSize(new Dimension(width, Integer.MAX_VALUE));
    }

    /**
     * The description wrapped to the dialog's width. A plain label ran past the
     * right edge and put a horizontal scroll bar under the whole chooser (seen
     * in the GUI checks' screenshots of the longer presets).
     */
    static String wrapped(String text) {
        String escaped = text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;");
        return "<html><body style='width: " + DESCRIPTION_WIDTH + "px'>" + escaped
                + "</body></html>";
    }

    /** Whole numbers without a trailing {@code .0}, which reads as spurious precision. */
    private static String format(double value) {
        if (Double.isNaN(value)) {
            return "";
        }
        if (!Double.isInfinite(value) && value == Math.rint(value)
                && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return String.valueOf(Math.round(value * 100.0) / 100.0);
    }
}
