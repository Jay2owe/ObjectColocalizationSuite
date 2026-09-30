/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */

import ij.IJ;
import ij.Macro;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import ij.plugin.frame.Recorder;
import ocs.OCSBatchMacro;
import ocs.OCSBatchParameters;
import ocs.OCSBatchResult;
import ocs.OCSBatchRunner;
import ocs.OCSErrors;
import ocs.OCSMacroOptions;
import ocs.engine.EngineProgress;

import java.io.File;

/**
 * The Fiji menu entry: Plugins &gt; Object Colocalization Suite Batch.
 *
 * <p>A folder in, a folder of CSVs out. The dialog is an ImageJ
 * {@link GenericDialog} rather than a bespoke Swing one, which buys
 * macro-recording and headless replay for nothing — a batch is the thing people
 * most want to script, and a hand-built dialog would have to reimplement both.
 *
 * <p>The <b>preview</b> is the important control. A regular expression that
 * matches nothing, or that collapses four hundred files into one group, is the
 * commonest batch mistake and the most expensive to discover after the fact.
 */
public class OCS_Batch implements PlugIn {

    private static final String TITLE = "Object Colocalization Suite Batch";

    /** The menu label, which is what a recorded line runs. */
    private static final String COMMAND = "Batch (folder)";

    @Override
    public void run(String arg) {
        try {
            String options = Macro.getOptions();
            OCSBatchParameters parameters = options == null || options.trim().isEmpty()
                    ? fromDialog() : OCSBatchMacro.parse(options);
            if (parameters == null) {
                return;
            }
            OCSBatchResult result = OCSBatchRunner.run(parameters, statusBar());
            report(result);
        } catch (Exception failed) {
            // Wrong input or an unusable folder is a sentence; only a genuine
            // bug gets ImageJ's stack-trace window.
            String message = OCSErrors.messageFor(failed);
            if (message != null) {
                IJ.error(TITLE, message);
            } else {
                IJ.handleException(failed);
            }
        } catch (OutOfMemoryError exhausted) {
            IJ.error(TITLE, OCSErrors.memoryMessage()
                    + "\nFields that finished before it are saved.");
        } finally {
            IJ.showProgress(1.0);
        }
    }

    // ---------- setting it up ----------

    private OCSBatchParameters fromDialog() {
        if (Recorder.record) {
            // The GenericDialog would otherwise record its own labels as keys
            // (label=, file=, ...) that the batch grammar does not read, so a
            // recorded batch would not replay. record() writes the real line.
            Recorder.disableCommandRecording();
        }
        GenericDialog dialog =
                new GenericDialog("Object Colocalization Suite — batch");
        // Folder and file fields rather than plain text: each gets a Browse
        // button, and a dropped folder fills it, so nobody has to type a path.
        dialog.addDirectoryField("Label folder", "", 40);
        dialog.addStringField("File pattern", OCSBatchMacro.DEFAULT_PATTERN, 40);
        dialog.addNumericField("Channel group", OCSBatchMacro.DEFAULT_CHANNEL_GROUP, 0);
        dialog.addCheckbox("Include subfolders", false);
        dialog.addDirectoryField("Intensity folder (optional)", "", 40);
        dialog.addStringField("Intensity pattern (blank = as the labels)", "", 40);
        dialog.addFileField("Region ROI file (optional)", "", 40);
        dialog.addDirectoryField("Save to (blank = beside the labels)", "", 40);
        dialog.addStringField("Methods", OCSBatchMacro.DEFAULT_METHODS, 40);
        dialog.addStringField("Other options (optional)", "", 40);
        dialog.addMessage("The channel group is which bracketed part of the "
                + "pattern\ndiffers between channels of the same field, "
                + "counting from 1.\nOther options use the single-run macro "
                + "words, e.g. null_model permutations=999 sweep_width=0.3");
        dialog.showDialog();
        if (dialog.wasCanceled()) {
            return null;
        }

        String folder = dialog.getNextString().trim();
        String pattern = dialog.getNextString().trim();
        int group = (int) dialog.getNextNumber();
        boolean recursive = dialog.getNextBoolean();
        String intensityFolder = dialog.getNextString().trim();
        String intensityPattern = dialog.getNextString().trim();
        String regionRoi = dialog.getNextString().trim();
        String saveTo = dialog.getNextString().trim();
        String methods = dialog.getNextString().trim();
        String other = dialog.getNextString().trim();

        if (folder.isEmpty()) {
            throw new IllegalArgumentException("a label folder is needed");
        }
        String analysis = (methods.isEmpty() ? ""
                : OCSMacroOptions.METHODS + "=[" + methods + "] ") + other;
        if (!regionRoi.isEmpty()) {
            // The template's own check that a chance test has a region to use.
            analysis = OCSMacroOptions.REGION_ROI + "=[" + regionRoi + "] " + analysis;
        }
        OCSBatchParameters.Builder builder =
                OCSBatchParameters.builder(new File(folder), pattern, group)
                        .recursive(recursive)
                        .template(OCSBatchMacro.template(analysis.trim()));
        if (!intensityFolder.isEmpty()) {
            builder.intensity(new File(intensityFolder), intensityPattern);
        }
        if (!regionRoi.isEmpty()) {
            builder.sharedRegionRoi(new File(regionRoi));
        }
        if (!saveTo.isEmpty()) {
            builder.saveDir(new File(saveTo));
        }
        OCSBatchParameters parameters = builder.build();

        // Shown after the settings and before anything is opened, so a pattern
        // that finds nothing costs one dialog rather than a whole evening.
        GenericDialog confirm = new GenericDialog("What will run");
        confirm.addMessage(OCSBatchRunner.preview(parameters));
        confirm.setOKLabel("Run");
        confirm.showDialog();
        if (confirm.wasCanceled()) {
            return null;
        }
        if (Recorder.record) {
            String analysisOnly = (methods.isEmpty() ? ""
                    : OCSMacroOptions.METHODS + "=[" + methods + "] ") + other;
            Recorder.record("run", COMMAND, OCSMacroOptions.forRecorder(
                    OCSBatchMacro.toMacroOptions(parameters, analysisOnly.trim())));
        }
        return parameters;
    }

    // ---------- afterwards ----------

    /**
     * Says what happened, including what did not.
     *
     * <p>Failures are named rather than counted. "398 of 400 fields ran" is a
     * relief; "398 ran, these two did not and here is why" is something somebody
     * can act on before the results go into a figure.
     */
    private static void report(OCSBatchResult result) {
        StringBuilder text = new StringBuilder();
        text.append(result.fieldsRun()).append(" field(s) analysed");
        if (result.wasCancelled()) {
            text.append(" before you stopped it");
        }
        text.append(".\n");
        if (result.outputFolder() != null) {
            text.append("Saved under ").append(result.outputFolder()
                    .getAbsolutePath()).append('\n');
        }
        if (!result.failures().isEmpty()) {
            text.append('\n').append(result.failures().size())
                    .append(" field(s) did not run:\n");
            for (int i = 0; i < result.failures().size(); i++) {
                text.append("    ").append(result.failures().get(i)).append('\n');
            }
        }
        IJ.showMessage(TITLE, text.toString());
    }

    private static EngineProgress statusBar() {
        return new EngineProgress() {
            @Override
            public void report(String stage, double fraction) {
                IJ.showStatus("OCS batch: " + stage);
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
