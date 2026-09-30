/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */

import ij.IJ;
import ij.ImagePlus;
import ij.Macro;
import ij.WindowManager;
import ij.gui.Roi;
import ij.measure.ResultsTable;
import ij.plugin.PlugIn;
import ij.plugin.frame.Recorder;
import ij.plugin.frame.RoiManager;
import ocs.OCS;
import ocs.OCSErrors;
import ocs.OCSLabelImages;
import ocs.OCSMacroOptions;
import ocs.OCSMacroOptionsParser;
import ocs.OCSParameters;
import ocs.OCSResult;
import ocs.engine.EngineCancelledException;
import ocs.engine.EngineProgress;
import ocs.engine.EngineRegistry;
import ocs.io.OCSOutputWriter;
import ocs.io.OCSTables;
import ocs.ui.InputDialog;
import ocs.ui.InputSelection;
import ocs.ui.MethodChoice;
import ocs.ui.MethodChoiceDialog;


import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Fiji menu entry: Plugins &gt; Object Colocalization Suite.
 *
 * <p>Two ways in and one way through. With macro options it takes them; without,
 * it opens the chooser. Either way it builds an {@code OCSParameters} and hands
 * it to {@link OCS}, so there is exactly one description of a run and the
 * interactive and scripted paths cannot answer the same question differently.
 *
 * <p>Deliberately thin, and it is the <i>only</i> class in the plugin that
 * touches {@code IJ.error}, the results windows or the recorder. Everything it
 * calls runs headless.
 */
public class Object_Colocalization_Suite implements PlugIn {

    @Override
    public void run(String arg) {
        String options = Macro.getOptions();
        try {
            if (options != null && !options.trim().isEmpty()) {
                runFromMacro(options);
            } else {
                runFromDialog();
            }
        } catch (EngineCancelledException cancelled) {
            IJ.showStatus("Object Colocalization Suite: cancelled");
        } catch (Exception failed) {
            // The user's problem, phrased for them, never a stack trace: wrong
            // input, or a file that cannot be read or saved. The stack-trace
            // window is kept for genuine bugs, where it is what to report.
            String message = OCSErrors.messageFor(failed);
            if (message != null) {
                IJ.error("Object Colocalization Suite", message);
            } else {
                IJ.handleException(failed);
            }
        } catch (OutOfMemoryError exhausted) {
            IJ.error("Object Colocalization Suite", OCSErrors.memoryMessage());
        } finally {
            IJ.showProgress(1.0);
        }
    }

    // ---------- from a macro ----------

    private void runFromMacro(String optionsText) throws Exception {
        OCSMacroOptions options = OCSMacroOptionsParser.parse(optionsText);
        List<ImagePlus> labels = OCSLabelImages.resolve(options.channels());

        OCSParameters.Builder builder = OCSParameters.builder(labels)
                .channelNames(options.channels());
        if (!options.intensityImages().isEmpty()) {
            builder.intensityImages(
                    OCSLabelImages.resolve(options.intensityImages()));
        }
        if (options.regionRoi() != null && !options.regionRoi().trim().isEmpty()) {
            builder.domain(loadRegion(options.regionRoi()));
        }
        options.applyTo(builder);
        checkOutput(options.output());

        OCSResult result = OCS.run(builder.build(), statusBar());
        deliver(result, options.output(), options.hideDisplay());
    }

    // ---------- from the dialog ----------

    private void runFromDialog() throws Exception {
        List<ImagePlus> open = OCSLabelImages.openImages();
        if (open.size() < OCSParameters.MIN_IMAGES) {
            throw new IllegalArgumentException("open at least "
                    + OCSParameters.MIN_IMAGES + " label images first — one per"
                    + " channel, each holding one object per label value");
        }
        if (Recorder.record) {
            // The Inputs dialog is a GenericDialog, which would otherwise record
            // its own fields as a second line that the macro grammar cannot read.
            // The one recorded line is written by record() below.
            Recorder.disableCommandRecording();
        }
        InputSelection inputs = InputDialog.ask(open, managerRois(), activeSelection());
        if (inputs == null) {
            return;
        }

        MethodChoice choice = MethodChoiceDialog.ask(
                EngineRegistry.createDefault(),
                inputs.toBuilder().build().toEngineInputs(),
                IJ.getInstance());
        if (choice == null) {
            return;
        }

        OCSParameters parameters = inputs.toBuilder().from(choice).build();
        checkOutput(inputs.output());
        OCSResult result = OCS.run(parameters, statusBar());
        record(inputs, parameters);
        deliver(result, inputs.output(), !inputs.showTables());
    }

    private static Roi[] managerRois() {
        RoiManager manager = RoiManager.getInstance();
        return manager == null ? null : manager.getRoisAsArray();
    }

    private static Roi activeSelection() {
        ImagePlus image = WindowManager.getCurrentImage();
        return image == null ? null : image.getRoi();
    }

    // ---------- results ----------

    private void deliver(OCSResult result, String output, boolean hideDisplay)
            throws Exception {
        Map<String, ResultsTable> tables = OCSTables.all(result);
        if (!hideDisplay) {
            for (Map.Entry<String, ResultsTable> entry : tables.entrySet()) {
                entry.getValue().show(windowTitle(entry.getKey()));
            }
        }
        if (output != null && !output.trim().isEmpty()) {
            File folder = OCSOutputWriter.write(result, new File(output.trim()));
            IJ.showStatus("Saved to " + folder.getAbsolutePath());
        }
        if (!result.skipped().isEmpty() && !hideDisplay) {
            // Said out loud rather than left in a table nobody opened. "I asked
            // for thirteen methods and got eleven" is exactly the surprise that
            // makes somebody distrust the ten that did run.
            StringBuilder note = new StringBuilder();
            note.append("These methods were asked for and could not run:\n\n");
            for (int i = 0; i < result.skipped().size(); i++) {
                note.append("    ").append(result.skipped().get(i)).append('\n');
            }
            IJ.showMessage("Object Colocalization Suite", note.toString());
        }
    }

    private static String windowTitle(String tableName) {
        return "OCS " + tableName;
    }

    // ---------- plumbing ----------

    /** Before the run, so an unusable save folder costs a message, not a run. */
    private static void checkOutput(String output) {
        if (output != null && !output.trim().isEmpty()) {
            OCSOutputWriter.checkWritable(new File(output.trim()));
        }
    }

    /**
     * The region a macro's {@code region_roi=} names: a ROI set file, or, for the
     * two keywords, whatever the ROI Manager or the active selection holds now.
     */
    private static List<Roi> loadRegion(String value) {
        InputSelection.RegionSource source = InputSelection.sourceForMacroValue(value);
        Roi[] onScreen = null;
        if (source == InputSelection.RegionSource.ROI_MANAGER) {
            onScreen = managerRois() == null ? new Roi[0] : managerRois();
        } else if (source == InputSelection.RegionSource.SELECTION) {
            Roi selection = activeSelection();
            onScreen = selection == null ? new Roi[0] : new Roi[] {selection};
        }
        InputSelection region = InputSelection.from(null)
                .region(source, value, onScreen);
        if (region.regionProblem() != null) {
            throw new IllegalArgumentException(region.regionProblem());
        }
        return new ArrayList<Roi>(region.region());
    }

    /**
     * Writes the line that would repeat this run.
     *
     * <p>Built from the parameters rather than from the dialog's widgets, so a
     * recorded line and a hand-written one go through exactly the same parser
     * and cannot mean different things.
     */
    private static void record(InputSelection inputs, OCSParameters parameters) {
        if (!Recorder.record) {
            return;
        }
        Recorder.record("run", "Object Colocalization Suite",
                OCSMacroOptions.forRecorder(inputs.recordedOptions(parameters)));
        String note = inputs.replayNote();
        if (note != null) {
            IJ.log(note);
        }
    }

    /** ImageJ's own status bar and its Escape key, behind our own interface. */
    private static EngineProgress statusBar() {
        return new EngineProgress() {
            @Override
            public void report(String stage, double fraction) {
                IJ.showStatus("OCS: " + stage);
                if (fraction >= 0.0) {
                    IJ.showProgress(fraction);
                }
            }

            @Override
            public boolean isCancelled() {
                return IJ.escapePressed();
            }
        };
    }
}
