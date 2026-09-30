package ocs.ui;

import ocs.engine.EngineInputs;
import ocs.engine.EngineRegistry;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;

/**
 * The window around the method chooser: the extra checks, Run and Cancel.
 *
 * <p>Deliberately thin. Everything with a rule in it is in
 * {@link MethodSelectionModel} or {@link MethodSelectionPanel}, both of which run
 * without a screen. What is left here is the frame — and the frame is built in
 * two halves so that most of it is still testable: the constructor assembles only
 * panels, buttons and labels, which need no display, and {@link #showAndWait}
 * makes the actual window, which does.
 *
 * <p>That split is the point. "Run stays disabled while a setting is nonsense" is
 * a rule worth a test, and it would not have one if it lived inside something that
 * throws the moment a test machine has no monitor.
 */
public final class MethodChoiceDialog {

    public static final String TITLE = "Object Colocalization Suite";

    /**
     * Client property on each of the three check switches, holding its name.
     *
     * <p>Same idea as the engine id on a method row: anything looking a switch up
     * matches on a name that will not change rather than on the wording, which
     * will.
     */
    public static final String CHECK_PROPERTY = "ocs.check";

    static final String CHANCE = "chance";
    static final String AGREEMENT = "agreement";
    static final String SWEEP = "sweep";
    static final String SHUFFLES = "shuffles";
    static final String SEED = "seed";

    /** Roughly a laptop screen's worth; taller than this and the list scrolls. */
    private static final int MAX_HEIGHT = 620;
    private static final int WIDTH = 560;

    private final MethodSelectionPanel methods;
    private final JButton run = new JButton("Run");
    private final JButton cancel = new JButton("Cancel");
    private final JLabel problem = new JLabel();
    private final JCheckBox chance = new JCheckBox("Test each method against chance");
    private final JCheckBox agreement = new JCheckBox("Compare the methods with each other");
    private final JCheckBox sweep = new JCheckBox("Check how much each setting matters");
    private final JTextField shuffles = new JTextField(6);
    private final JTextField seed = new JTextField(10);
    private final JLabel reachableP = new JLabel();
    private final JPanel content = new JPanel(new BorderLayout());

    private MethodChoice choice;
    private JDialog window;

    /**
     * @param remembered a previous selection to reopen on, or null for the
     *                   default preset
     */
    public MethodChoiceDialog(EngineRegistry registry, EngineInputs inputs,
            String remembered) {
        this.methods = new MethodSelectionPanel(registry, inputs, remembered);

        JScrollPane scroller = new JScrollPane(methods);
        scroller.setBorder(BorderFactory.createEmptyBorder());
        scroller.getVerticalScrollBar().setUnitIncrement(16);
        content.add(scroller, BorderLayout.CENTER);
        content.add(footer(), BorderLayout.SOUTH);

        methods.addChangeListener(new ChangeListener() {
            @Override
            public void stateChanged(ChangeEvent event) {
                syncRun();
            }
        });
        syncChecksFromModel();
        syncRun();
    }

    // ---------- what the frame exposes ----------

    /** Everything except the window itself, so this can be embedded or tested. */
    public JComponent content() {
        return content;
    }

    public MethodSelectionPanel methods() {
        return methods;
    }

    public JButton runButton() {
        return run;
    }

    public JButton cancelButton() {
        return cancel;
    }

    /** What Run was pressed on, or null while nothing has been confirmed. */
    public MethodChoice choice() {
        return choice;
    }

    // ---------- the two buttons ----------

    /**
     * Confirms the current selection: takes the slip, remembers it, closes.
     *
     * <p>Package-visible rather than private so the wiring can be tested without
     * a window to click in.
     */
    void runPressed() {
        if (whyRunIsDisabled() != null) {
            // Belt and braces. The button is already disabled, but a keyboard
            // default-button press is a separate path, and running a selection
            // the dialog has just called invalid is worse than doing nothing.
            return;
        }
        choice = MethodChoice.from(methods);
        RememberedMethods.remember(methods.model());
        close();
    }

    /** Backs out. Writes nothing: Cancel must not be a way to change settings. */
    void cancelPressed() {
        choice = null;
        close();
    }

    private void close() {
        if (window != null) {
            window.setVisible(false);
            window.dispose();
        }
    }

    // ---------- showing it ----------

    /**
     * Opens the window and blocks until it closes.
     *
     * @param parent the window to sit over, usually the ImageJ main window; null
     *               centres on screen
     * @return the confirmed slip, or null if Cancel or the close box was used
     */
    public MethodChoice showAndWait(Component parent) {
        window = new JDialog(ownerOf(parent), TITLE,
                JDialog.ModalityType.APPLICATION_MODAL);
        window.setContentPane(content);
        window.getRootPane().setDefaultButton(run);
        window.getRootPane().registerKeyboardAction(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                cancelPressed();
            }
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        window.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        window.pack();

        Dimension packed = window.getSize();
        window.setSize(Math.max(packed.width, WIDTH),
                Math.min(packed.height, MAX_HEIGHT));
        window.setLocationRelativeTo(parent);
        window.setVisible(true);
        return choice;
    }

    /**
     * Open the chooser on whatever was used last, and return what was asked for.
     *
     * @return null if the user cancelled, which callers must treat as "do
     *         nothing" rather than as an empty run
     */
    public static MethodChoice ask(EngineRegistry registry, EngineInputs inputs,
            Component parent) {
        return reopened(registry, inputs).showAndWait(parent);
    }

    /**
     * A dialog restored from the last run, but not yet shown.
     *
     * <p>Everything {@link #ask} does except open a window, so the restore path
     * has tests. Without this split the only way to reach it would be through
     * {@code showAndWait}, which needs a screen — and "the run used the
     * remembered settings while the fields showed the defaults" would be a bug
     * only a person sitting in front of Fiji could ever find.
     */
    static MethodChoiceDialog reopened(EngineRegistry registry, EngineInputs inputs) {
        MethodChoiceDialog dialog =
                new MethodChoiceDialog(registry, inputs, RememberedMethods.read());
        RememberedMethods.restoreChecks(dialog.methods.model());
        dialog.syncChanceSettingsFromModel();
        dialog.syncChecksFromModel();
        dialog.methods.refresh();
        return dialog;
    }

    /**
     * ImageJ's main window is itself a {@code Frame}, and asking for a component's
     * <i>ancestor</i> window would skip straight past it and leave this dialog
     * unowned — which on Windows means it can fall behind the main window with no
     * way back to it, while still being modal.
     */
    private static java.awt.Window ownerOf(Component parent) {
        if (parent instanceof java.awt.Window) {
            return (java.awt.Window) parent;
        }
        return parent == null
                ? null : javax.swing.SwingUtilities.getWindowAncestor(parent);
    }

    // ---------- building the footer ----------

    private JComponent footer() {
        JPanel footer = new JPanel();
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        footer.setBorder(BorderFactory.createEmptyBorder(6, 10, 10, 10));

        footer.add(checks());
        footer.add(Box.createVerticalStrut(6));

        problem.setFont(problem.getFont().deriveFont(Font.PLAIN, 11f));
        problem.setForeground(new Color(0xB0, 0x30, 0x20));
        problem.setAlignmentX(Component.LEFT_ALIGNMENT);
        footer.add(problem);
        footer.add(Box.createVerticalStrut(6));

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
        buttons.add(Box.createHorizontalGlue());
        cancel.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                cancelPressed();
            }
        });
        run.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                runPressed();
            }
        });
        buttons.add(cancel);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(run);
        footer.add(buttons);
        return footer;
    }

    /**
     * The three extra checks.
     *
     * <p>On the frame rather than in the chooser because they are questions about
     * the run, not about which methods are in it — and because the chance test
     * multiplies the whole run by about a hundred, which belongs next to the
     * button that starts it.
     */
    private JComponent checks() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setBorder(BorderFactory.createTitledBorder("Extra checks"));

        chance.setToolTipText("Shuffles the objects and re-runs, to see whether the "
                + "result is more than the two channels being crowded. Slow: about "
                + "a hundred times the run.");
        agreement.setToolTipText("Asks whether the methods agree object by object. "
                + "Free — it is arithmetic on results already computed.");
        sweep.setToolTipText("Re-runs each method across a range of its setting, to "
                + "show whether the answer is about the data or about the cut-off. "
                + "About twenty times the run, for methods that have a setting.");

        ActionListener writeBack = new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                methods.model().setRunsNullModel(chance.isSelected());
                methods.model().setRunsAgreement(agreement.isSelected());
                methods.model().setRunsThresholdSweep(sweep.isSelected());
                // The estimate lives on the chooser and these multiply it, so it
                // has to be told; it has no way of noticing on its own.
                methods.refresh();
            }
        };
        chance.addActionListener(writeBack);
        agreement.addActionListener(writeBack);
        sweep.addActionListener(writeBack);

        chance.putClientProperty(CHECK_PROPERTY, CHANCE);
        agreement.putClientProperty(CHECK_PROPERTY, AGREEMENT);
        sweep.putClientProperty(CHECK_PROPERTY, SWEEP);

        chance.setAlignmentX(Component.LEFT_ALIGNMENT);
        agreement.setAlignmentX(Component.LEFT_ALIGNMENT);
        sweep.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(chance);
        panel.add(chanceSettings());
        panel.add(agreement);
        panel.add(sweep);
        return panel;
    }

    /**
     * How many shuffles, and the seed — indented under the chance test they
     * belong to, and greyed until it is switched on.
     *
     * <p>The line beside them is the point of showing them at all. With the
     * default thousand shuffles the smallest two-sided <i>p</i> is nominally
     * about 0.002, so
     * "p &lt; 0.001" is unreachable however strong the effect — and that ceiling
     * is invisible in the number 1000. Stating it turns a silent limit into a
     * setting somebody can decide about. Ties can raise that floor further.
     */
    private JComponent chanceSettings() {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createEmptyBorder(0, 22, 0, 0));

        shuffles.setText(String.valueOf(methods.model().permutations()));
        seed.setText(String.valueOf(methods.model().seed()));
        shuffles.setToolTipText("How many times the objects are reshuffled. More "
                + "shuffles cost proportionally more time and buy a finer p.");
        seed.setToolTipText("Fixes the shuffles, so the same data and the same seed "
                + "give the same answer. Recorded with the results.");
        shuffles.putClientProperty(CHECK_PROPERTY, SHUFFLES);
        seed.putClientProperty(CHECK_PROPERTY, SEED);
        fixWidth(shuffles, 70);
        fixWidth(seed, 110);

        DocumentListener retype = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                writeChanceSettings();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                writeChanceSettings();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                writeChanceSettings();
            }
        };
        shuffles.getDocument().addDocumentListener(retype);
        seed.getDocument().addDocumentListener(retype);

        row.add(smallLabel("Shuffles"));
        row.add(shuffles);
        row.add(Box.createHorizontalStrut(10));
        row.add(smallLabel("Seed"));
        row.add(seed);
        row.add(Box.createHorizontalStrut(10));
        reachableP.setFont(reachableP.getFont().deriveFont(Font.ITALIC, 11f));
        row.add(reachableP);
        row.add(Box.createHorizontalGlue());
        return row;
    }

    private static JLabel smallLabel(String text) {
        JLabel label = new JLabel(text + "  ");
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
        return label;
    }

    private static void fixWidth(JComponent component, int width) {
        Dimension size = new Dimension(width, component.getPreferredSize().height);
        component.setPreferredSize(size);
        component.setMaximumSize(size);
    }

    /**
     * Pushes the two fields into the model, ignoring anything unusable.
     *
     * <p>Ignoring rather than substituting a default: {@link #whyRunIsDisabled} is
     * what tells the user, and quietly running a hundred shuffles while the field
     * says 1000 would produce a run record describing a run that did not happen.
     */
    private void writeChanceSettings() {
        try {
            methods.model().setPermutations(
                    Integer.parseInt(shuffles.getText().trim()));
        } catch (IllegalArgumentException unusable) {
            // Reported by whyRunIsDisabled, not fixed here.
        }
        try {
            methods.model().setSeed(Long.parseLong(seed.getText().trim()));
        } catch (IllegalArgumentException unusable) {
            // Same.
        }
        methods.refresh();
    }

    // ---------- keeping the frame and the model agreed ----------

    /**
     * Redraws the two fields from the model.
     *
     * <p>Separate from {@link #syncChecksFromModel} and called only when something
     * outside the dialog changed them — restoring a remembered run. Doing it on
     * every change would rewrite the field under the cursor while somebody was
     * still typing in it.
     */
    void syncChanceSettingsFromModel() {
        // Both values read before either field is written. Writing one field
        // fires its listener, which reads *both* fields back into the model — so
        // updating the shuffles first and only then asking the model for the seed
        // returns the seed that the first write had already overwritten with the
        // stale text still sitting in the second field.
        String count = String.valueOf(methods.model().permutations());
        String value = String.valueOf(methods.model().seed());
        if (!count.equals(shuffles.getText())) {
            shuffles.setText(count);
        }
        if (!value.equals(seed.getText())) {
            seed.setText(value);
        }
    }

    /** Presets carry their own choice of checks, so the boxes follow the model. */
    void syncChecksFromModel() {
        MethodSelectionModel model = methods.model();
        // Without a region the chance test cannot run, so a preset or a
        // remembered setting that asks for it is overruled here, where the
        // greyed switch and its tooltip say why.
        if (!model.canRunNullModel()) {
            model.setRunsNullModel(false);
            chance.setEnabled(false);
            chance.setToolTipText(model.whyNoNullModel());
        }
        chance.setSelected(model.runsNullModel());
        agreement.setSelected(model.runsAgreement());
        sweep.setSelected(model.runsThresholdSweep());

        // The two settings belong to the chance test, so they are live only when
        // it is. Editable fields that feed nothing are how a user comes to
        // believe they set something they did not.
        shuffles.setEnabled(model.runsNullModel());
        seed.setEnabled(model.runsNullModel());
        reachableP.setText(model.runsNullModel()
                ? "smallest p this can report: " + formatP(model.smallestReachableP())
                : " ");
    }

    /**
     * Why Run should stay disabled, or null when it should not.
     *
     * <p>The chooser's own reasons come first: a method that cannot run is a more
     * basic problem than the number of shuffles, and a user fixing them in order
     * should not be told about the second while the first still stands.
     */
    public String whyRunIsDisabled() {
        String fromMethods = methods.whyRunIsDisabled();
        if (fromMethods != null) {
            return fromMethods;
        }
        if (!methods.model().runsNullModel()) {
            return null;
        }
        String shuffleText = shuffles.getText().trim();
        try {
            if (Integer.parseInt(shuffleText) < 1) {
                return "Shuffles: a chance test needs at least one, not "
                        + shuffleText + ".";
            }
        } catch (NumberFormatException notANumber) {
            return "Shuffles: '" + shuffleText + "' is not a whole number.";
        }
        String seedText = seed.getText().trim();
        try {
            long value = Long.parseLong(seedText);
            if (!MethodSelectionModel.isRecordableExactly(value)) {
                return "Seed: " + seedText + " is too large to be recorded exactly,"
                        + " so the run record would not replay this run.";
            }
        } catch (NumberFormatException notANumber) {
            return "Seed: '" + seedText + "' is not a whole number.";
        }
        return null;
    }

    private void syncRun() {
        String reason = whyRunIsDisabled();
        run.setEnabled(reason == null);
        problem.setText(reason == null ? " " : reason);
        syncChecksFromModel();
    }

    /** Two significant figures; the exact value carries no extra meaning. */
    private static String formatP(double p) {
        if (p >= 0.01) {
            return String.valueOf(Math.round(p * 10000.0) / 10000.0);
        }
        return String.format("%.1e", Double.valueOf(p));
    }
}
