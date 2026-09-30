/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ocs.ui;

import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.gui.Roi;

import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

/**
 * The input step of the menu dialog: which image is each channel, which
 * intensity image goes with it, the region ROI and the output folder.
 *
 * <p>Its own dialog, before the method chooser, so neither grows past the
 * height of a laptop screen. Thin on purpose: every rule is in
 * {@link InputSelection}, which runs without a screen. This only draws the
 * fields, reads them back, and shows the dialog again, with the problems
 * written on it, until the choice can run or the user cancels.
 */
public final class InputDialog {

    public static final String TITLE = "Object Colocalization Suite - Inputs";

    /** Field labels. Public so the GUI checks and the docs name the same fields. */
    public static final String REGION = "Region_ROI";
    public static final String REGION_FILE = "ROI_set_file";
    public static final String OUTPUT = "Output_folder";
    public static final String SHOW_TABLES = "Show_result_tables";

    private static final int CHANNEL_ROWS = ocs.engine.EngineInputs.MAX_CHANNELS;

    private InputDialog() {
    }

    public static String labelsField(int channel) {
        return "Channel_" + (channel + 1) + "_labels";
    }

    public static String intensityField(int channel) {
        return "Channel_" + (channel + 1) + "_intensity";
    }

    /**
     * Shows the dialog until its choice can run.
     *
     * @param open        the open images, in window order
     * @param managerRois the ROI Manager's contents, or null when it is not open
     * @param selection   the active image's selection, or null
     * @return the choice, or null if the user cancelled
     */
    public static InputSelection ask(List<ImagePlus> open, Roi[] managerRois,
            Roi selection) {
        InputSelection previous = null;
        while (true) {
            GenericDialog dialog = new GenericDialog(TITLE);
            List<String> titles = new ArrayList<String>();
            for (int i = 0; i < open.size(); i++) {
                titles.add(open.get(i).getTitle());
            }
            String[] required = titles.toArray(new String[titles.size()]);
            List<String> optionalList = new ArrayList<String>();
            optionalList.add(InputSelection.NONE);
            optionalList.addAll(titles);
            String[] optional = optionalList.toArray(new String[optionalList.size()]);

            List<ImagePlus> defaults = previous != null ? previous.labels()
                    : InputSelection.defaultChannels(open, RememberedMethods.rememberedChannels());

            dialog.addMessage("Label images, one per channel (channels 3 to 5 optional)");
            for (int c = 0; c < CHANNEL_ROWS; c++) {
                String value = c < defaults.size() ? defaults.get(c).getTitle()
                        : (c < 2 && c < required.length ? required[c] : InputSelection.NONE);
                dialog.addChoice(labelsField(c), c < 2 ? required : optional, value);
            }
            dialog.addMessage("Intensity images (optional): one per channel, or none");
            for (int c = 0; c < CHANNEL_ROWS; c++) {
                String value = InputSelection.NONE;
                if (previous != null && c < previous.intensities().size()
                        && previous.intensities().get(c) != null) {
                    value = previous.intensities().get(c).getTitle();
                } else if (previous == null) {
                    String remembered = RememberedMethods.rememberedIntensity(c);
                    if (InputSelection.byTitle(open, remembered) != null) {
                        value = remembered;
                    }
                }
                dialog.addChoice(intensityField(c), optional, value);
            }
            dialog.addMessage(defaults.isEmpty() ? InputSelection.describe(null)
                    : InputSelection.describe(defaults.get(0).getCalibration())
                            + " (read from channel 1)");

            InputSelection.RegionSource source = previous != null
                    ? previous.regionSource() : RememberedMethods.rememberedRegionSource();
            dialog.addChoice(REGION, InputSelection.RegionSource.labels(), source.label());
            dialog.addFileField(REGION_FILE, previous != null && previous.regionPath() != null
                    ? previous.regionPath() : RememberedMethods.rememberedRegionFile());
            dialog.addDirectoryField(OUTPUT, previous != null && previous.output() != null
                    ? previous.output() : RememberedMethods.rememberedOutput());
            dialog.addCheckbox(SHOW_TABLES, previous != null
                    ? previous.showTables() : RememberedMethods.rememberedShowTables());

            if (previous != null) {
                StringBuilder text = new StringBuilder();
                List<String> problems = previous.problems();
                for (int i = 0; i < problems.size(); i++) {
                    text.append(i == 0 ? "" : "\n").append(problems.get(i));
                }
                dialog.addMessage(text.toString(), new Font("SansSerif", Font.PLAIN, 12),
                        new Color(0xB0, 0x30, 0x20));
            }
            dialog.showDialog();
            if (dialog.wasCanceled()) {
                return null;
            }

            List<ImagePlus> labels = new ArrayList<ImagePlus>();
            List<Integer> kept = new ArrayList<Integer>();
            for (int c = 0; c < CHANNEL_ROWS; c++) {
                ImagePlus image = InputSelection.byTitle(open, dialog.getNextChoice());
                if (image != null) {
                    labels.add(image);
                    kept.add(Integer.valueOf(c));
                }
            }
            ImagePlus[] intensity = new ImagePlus[CHANNEL_ROWS];
            for (int c = 0; c < CHANNEL_ROWS; c++) {
                intensity[c] = InputSelection.byTitle(open, dialog.getNextChoice());
            }
            InputSelection.RegionSource region =
                    InputSelection.RegionSource.fromLabel(dialog.getNextChoice());
            String regionFile = dialog.getNextString();
            String output = dialog.getNextString();
            boolean show = dialog.getNextBoolean();

            InputSelection chosen = InputSelection.from(open).channels(labels);
            for (int i = 0; i < kept.size(); i++) {
                chosen.intensity(i, intensity[kept.get(i).intValue()]);
            }
            Roi[] rois = null;
            if (region == InputSelection.RegionSource.ROI_MANAGER) {
                rois = managerRois == null ? new Roi[0] : managerRois;
            } else if (region == InputSelection.RegionSource.SELECTION) {
                rois = selection == null ? new Roi[0] : new Roi[] {selection};
            }
            chosen.region(region, regionFile, rois).output(output, show);
            if (chosen.problems().isEmpty()) {
                RememberedMethods.rememberInputs(chosen);
                return chosen;
            }
            previous = chosen;
        }
    }
}
