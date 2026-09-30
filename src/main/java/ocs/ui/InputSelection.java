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
import ij.gui.Roi;
import ij.measure.Calibration;
import ocs.OCSMacroOptions;
import ocs.OCSParameters;
import ocs.engine.EngineInputs;
import sc.fiji.oc3d.core.ingest.RoiLabelImages;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What the menu dialog's input step chose: the label image of each channel, an
 * optional intensity image beside each, the region ROI, and where the output
 * goes.
 *
 * <p>Holds no Swing and opens no window, in the pattern of
 * {@link MethodSelectionModel}: the dialog draws this and writes back to it, so
 * every rule — how many channels, which combinations are refused, what the
 * recorded macro line says — runs and is tested without a screen.
 *
 * <p>It exists because the menu path used to take every open image as a
 * channel, silently drop any beyond five, and offer no way to give intensity
 * images, a region or an output folder — so four of the five presets could not
 * run from the menu at all.
 */
public final class InputSelection {

    /** Where the region ROI comes from. */
    public enum RegionSource {
        NONE("none"),
        ROI_MANAGER("ROI Manager"),
        SELECTION("Active selection"),
        ZIP_FILE("ROI set file (.zip)");

        private final String label;

        RegionSource(String label) {
            this.label = label;
        }

        /** The wording in the dialog's dropdown. */
        public String label() {
            return label;
        }

        public static RegionSource fromLabel(String label) {
            RegionSource[] all = values();
            for (int i = 0; i < all.length; i++) {
                if (all[i].label.equals(label) || all[i].name().equals(label)) {
                    return all[i];
                }
            }
            return NONE;
        }

        public static String[] labels() {
            RegionSource[] all = values();
            String[] labels = new String[all.length];
            for (int i = 0; i < all.length; i++) {
                labels[i] = all[i].label;
            }
            return labels;
        }
    }

    /** The dropdown entry that means "no image here". */
    public static final String NONE = "none";

    private final List<ImagePlus> open;
    private final List<ImagePlus> labels = new ArrayList<ImagePlus>();
    private final List<ImagePlus> intensities = new ArrayList<ImagePlus>();
    private RegionSource regionSource = RegionSource.NONE;
    private String regionPath;
    private List<Roi> regionRois = new ArrayList<Roi>();
    private String regionProblem;
    private String output;
    private boolean showTables = true;

    private InputSelection(List<ImagePlus> open) {
        this.open = open == null
                ? new ArrayList<ImagePlus>() : new ArrayList<ImagePlus>(open);
    }

    /** A selection over these open images, with nothing chosen yet. */
    public static InputSelection from(List<ImagePlus> open) {
        return new InputSelection(open);
    }

    public List<ImagePlus> openImages() {
        return Collections.unmodifiableList(open);
    }

    // ---------- choosing ----------

    /** Sets the label image of each channel, in order, and clears the intensity images. */
    public InputSelection channels(List<ImagePlus> chosen) {
        labels.clear();
        intensities.clear();
        if (chosen != null) {
            for (int i = 0; i < chosen.size(); i++) {
                labels.add(chosen.get(i));
                intensities.add(null);
            }
        }
        return this;
    }

    /** @param image the paired intensity image, or null for none */
    public InputSelection intensity(int channel, ImagePlus image) {
        if (channel < 0 || channel >= labels.size()) {
            throw new IllegalArgumentException("no channel " + (channel + 1)
                    + "; there are " + labels.size());
        }
        intensities.set(channel, image);
        return this;
    }

    /**
     * @param source   where the region comes from
     * @param zipPath  the ROI set file, for {@link RegionSource#ZIP_FILE}; ignored
     *                 otherwise
     * @param rois     the ROIs already in hand, for the ROI Manager and the active
     *                 selection; for a file, null means read it from
     *                 {@code zipPath}
     */
    public InputSelection region(RegionSource source, String zipPath, Roi[] rois) {
        regionSource = source == null ? RegionSource.NONE : source;
        regionPath = zipPath == null || zipPath.trim().isEmpty() ? null : zipPath.trim();
        regionRois = new ArrayList<Roi>();
        regionProblem = null;
        switch (regionSource) {
            case NONE:
                return this;
            case ROI_MANAGER:
                addAll(rois);
                if (regionRois.isEmpty()) {
                    regionProblem = "The ROI Manager is empty. Add the region ROI to"
                            + " it, or choose another region source.";
                }
                return this;
            case SELECTION:
                addAll(rois);
                if (regionRois.isEmpty()) {
                    regionProblem = "There is no active selection to use as the"
                            + " region. Draw one on an image, or choose another"
                            + " region source.";
                }
                return this;
            case ZIP_FILE:
                if (rois != null) {
                    addAll(rois);
                } else {
                    load();
                }
                if (regionProblem == null && regionRois.isEmpty()) {
                    regionProblem = "The ROI set file " + quoted(regionPath)
                            + " holds no ROIs.";
                }
                return this;
            default:
                throw new IllegalStateException("unhandled region source " + regionSource);
        }
    }

    /**
     * @param folder     auto-save folder, or null/blank for none
     * @param showTables whether the result tables open as windows
     */
    public InputSelection output(String folder, boolean showTables) {
        this.output = folder == null || folder.trim().isEmpty() ? null : folder.trim();
        this.showTables = showTables;
        return this;
    }

    // ---------- reading back ----------

    public List<ImagePlus> labels() {
        return Collections.unmodifiableList(labels);
    }

    /** Parallel to {@link #labels()}; an entry is null where that channel has none. */
    public List<ImagePlus> intensities() {
        return Collections.unmodifiableList(intensities);
    }

    /** True when every channel has an intensity image. */
    public boolean hasIntensity() {
        if (intensities.isEmpty()) {
            return false;
        }
        for (int i = 0; i < intensities.size(); i++) {
            if (intensities.get(i) == null) {
                return false;
            }
        }
        return true;
    }

    public RegionSource regionSource() {
        return regionSource;
    }

    public String regionPath() {
        return regionPath;
    }

    public List<Roi> region() {
        return Collections.unmodifiableList(regionRois);
    }

    /** What is wrong with the region alone, or null; for replaying a macro line. */
    public String regionProblem() {
        return regionProblem;
    }

    public String output() {
        return output;
    }

    public boolean showTables() {
        return showTables;
    }

    /** The first label image's calibration: what every method will measure in. */
    public Calibration calibration() {
        return labels.isEmpty() ? new Calibration() : labels.get(0).getCalibration();
    }

    /** One line for the dialog: voxel size and unit, or that there is none. */
    public String calibrationText() {
        return describe(calibration());
    }

    public static String describe(Calibration calibration) {
        if (calibration == null || !calibration.scaled()) {
            return "Calibration: none (sizes and distances in pixels)";
        }
        return "Calibration: " + number(calibration.pixelWidth) + " x "
                + number(calibration.pixelHeight) + " x "
                + number(calibration.pixelDepth) + " " + calibration.getUnit()
                + " per voxel";
    }

    // ---------- what is wrong ----------

    /**
     * Everything that stops a run, in the words the user needs, or an empty list
     * when the selection can run.
     */
    public List<String> problems() {
        List<String> problems = new ArrayList<String>();
        if (labels.size() < EngineInputs.MIN_CHANNELS) {
            problems.add("Choose at least " + EngineInputs.MIN_CHANNELS
                    + " label images, one per channel.");
        }
        if (labels.size() > EngineInputs.MAX_CHANNELS) {
            problems.add("At most " + EngineInputs.MAX_CHANNELS + " channels can be"
                    + " compared at once; " + labels.size() + " were chosen.");
        }
        for (int i = 0; i < labels.size(); i++) {
            for (int j = i + 1; j < labels.size(); j++) {
                if (labels.get(i) == labels.get(j)) {
                    problems.add("Channels " + (i + 1) + " and " + (j + 1)
                            + " are both " + quoted(labels.get(i).getTitle())
                            + "; each channel needs its own label image.");
                } else if (titleOf(labels.get(i)).equals(titleOf(labels.get(j)))) {
                    problems.add("Channels " + (i + 1) + " and " + (j + 1)
                            + " have the same title " + quoted(titleOf(labels.get(i)))
                            + "; rename one, because the title names its columns.");
                }
            }
        }
        String geometry = geometryProblem();
        if (geometry != null) {
            problems.add(geometry);
        }
        boolean any = false;
        for (int i = 0; i < intensities.size(); i++) {
            any |= intensities.get(i) != null;
        }
        if (any) {
            for (int i = 0; i < intensities.size(); i++) {
                if (intensities.get(i) == null) {
                    problems.add("Channel " + (i + 1) + " ("
                            + quoted(titleOf(labels.get(i))) + ") has no intensity"
                            + " image. Give every channel one, or none.");
                }
            }
        }
        if (regionProblem != null) {
            problems.add(regionProblem);
        }
        if (output != null) {
            File folder = new File(output);
            if (folder.exists() && !folder.isDirectory()) {
                problems.add("The output folder " + quoted(output)
                        + " is a file, not a folder.");
            }
        }
        return problems;
    }

    /**
     * Every label and intensity image must share width, height and slice count.
     * Names both images, because "the images differ" is not something anyone can
     * act on when five are chosen.
     */
    private String geometryProblem() {
        List<ImagePlus> all = new ArrayList<ImagePlus>();
        for (int i = 0; i < labels.size(); i++) {
            if (labels.get(i) != null) {
                all.add(labels.get(i));
            }
        }
        for (int i = 0; i < intensities.size(); i++) {
            if (intensities.get(i) != null) {
                all.add(intensities.get(i));
            }
        }
        if (all.size() < 2) {
            return null;
        }
        ImagePlus first = all.get(0);
        for (int i = 1; i < all.size(); i++) {
            ImagePlus image = all.get(i);
            if (image.getWidth() != first.getWidth()
                    || image.getHeight() != first.getHeight()
                    || image.getStackSize() != first.getStackSize()) {
                return quoted(titleOf(first)) + " is " + size(first) + " but "
                        + quoted(titleOf(image)) + " is " + size(image)
                        + "; every label and intensity image must have the same"
                        + " width, height and number of slices.";
            }
        }
        return null;
    }

    // ---------- into a run ----------

    /**
     * The run's images, names and region. Methods and checks come from the
     * chooser afterwards.
     *
     * @throws IllegalArgumentException listing the problems, if there are any
     */
    public OCSParameters.Builder toBuilder() {
        List<String> problems = problems();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(join(problems, "\n"));
        }
        OCSParameters.Builder builder = OCSParameters.builder(labels)
                .channelNames(titles(labels));
        if (hasIntensity()) {
            builder.intensityImages(intensities);
        }
        if (!regionRois.isEmpty()) {
            builder.domain(regionRois);
        }
        return builder;
    }

    /**
     * The macro options that repeat this run: the images, the region, the output
     * and every setting in {@code parameters}. Parsed by
     * {@code OCSMacroOptionsParser}, this gives back the same parameters.
     */
    public String recordedOptions(OCSParameters parameters) {
        List<String> intensityTitles = hasIntensity()
                ? titles(intensities) : new ArrayList<String>();
        return OCSMacroOptions.forRecording(parameters, titles(labels), intensityTitles,
                regionMacroValue(), macroPath(output), !showTables).toMacroOptions();
    }

    /** What {@code region_roi=} records for this region, or null for none. */
    String regionMacroValue() {
        switch (regionSource) {
            case ROI_MANAGER:
                return OCSMacroOptions.REGION_FROM_ROI_MANAGER;
            case SELECTION:
                return OCSMacroOptions.REGION_FROM_SELECTION;
            case ZIP_FILE:
                return macroPath(regionPath);
            default:
                return null;
        }
    }

    /**
     * A note for the Log when the recorded line depends on what is on screen at
     * replay time, or null when it does not.
     */
    public String replayNote() {
        if (regionSource == RegionSource.ROI_MANAGER) {
            return "Object Colocalization Suite: the recorded line reads the region"
                    + " from the ROI Manager when it is replayed; save the region as"
                    + " a .zip and use region_roi=<path> to fix it.";
        }
        if (regionSource == RegionSource.SELECTION) {
            return "Object Colocalization Suite: the recorded line reads the region"
                    + " from the active image's selection when it is replayed; save"
                    + " it as a .zip and use region_roi=<path> to fix it.";
        }
        return null;
    }

    /**
     * Which region source a {@code region_roi=} value names: the two keywords, or
     * a file path.
     */
    public static RegionSource sourceForMacroValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return RegionSource.NONE;
        }
        String text = value.trim();
        if (OCSMacroOptions.REGION_FROM_ROI_MANAGER.equalsIgnoreCase(text)) {
            return RegionSource.ROI_MANAGER;
        }
        if (OCSMacroOptions.REGION_FROM_SELECTION.equalsIgnoreCase(text)) {
            return RegionSource.SELECTION;
        }
        return RegionSource.ZIP_FILE;
    }

    /**
     * The label images the dialog opens on.
     *
     * <p>The remembered channels when every one of them is still open; otherwise
     * the first two open images, with the optional channels 3 to 5 left at none.
     *
     * <p>Not every open image. The intensity methods need the raw images open
     * beside the labels, and offering those as label channels 3 and 4 made the
     * first OK of that ordinary setup fail with "channel 3 has no intensity
     * image" (found by the GUI checks). A third label channel is one choice away;
     * a wrong one pre-filled is a trap.
     */
    public static List<ImagePlus> defaultChannels(List<ImagePlus> open,
            List<String> rememberedTitles) {
        List<ImagePlus> chosen = new ArrayList<ImagePlus>();
        if (rememberedTitles != null && rememberedTitles.size() >= EngineInputs.MIN_CHANNELS) {
            for (int i = 0; i < rememberedTitles.size(); i++) {
                ImagePlus image = byTitle(open, rememberedTitles.get(i));
                if (image == null) {
                    chosen.clear();
                    break;
                }
                chosen.add(image);
            }
            if (!chosen.isEmpty()) {
                return chosen;
            }
        }
        int take = Math.min(open.size(), EngineInputs.MIN_CHANNELS);
        for (int i = 0; i < take; i++) {
            chosen.add(open.get(i));
        }
        return chosen;
    }

    /** The open image with this exact title, or null. */
    public static ImagePlus byTitle(List<ImagePlus> images, String title) {
        if (title == null || NONE.equals(title)) {
            return null;
        }
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i) != null && title.equals(images.get(i).getTitle())) {
                return images.get(i);
            }
        }
        return null;
    }

    // ---------- plumbing ----------

    private void load() {
        if (regionPath == null) {
            regionProblem = "Choose the .zip ROI set file that holds the region.";
            return;
        }
        File file = new File(regionPath);
        if (!file.isFile()) {
            regionProblem = "There is no ROI set file " + quoted(regionPath) + ".";
            return;
        }
        try {
            addAll(RoiLabelImages.loadRoiSet(file.getAbsolutePath()));
        } catch (Exception unreadable) {
            regionProblem = "Could not read ROIs from " + quoted(regionPath) + ": "
                    + unreadable.getMessage();
        }
    }

    private void addAll(Roi[] rois) {
        if (rois == null) {
            return;
        }
        regionRois.addAll(Arrays.asList(rois));
        regionRois.removeAll(Collections.<Roi>singleton(null));
    }

    /**
     * Forward slashes, because the macro grammar refuses backslashes and ImageJ
     * reads forward slashes on every platform.
     */
    static String macroPath(String path) {
        return path == null ? null : path.replace('\\', '/');
    }

    private static List<String> titles(List<ImagePlus> images) {
        List<String> titles = new ArrayList<String>();
        for (int i = 0; i < images.size(); i++) {
            titles.add(titleOf(images.get(i)));
        }
        return titles;
    }

    private static String titleOf(ImagePlus image) {
        return image == null || image.getTitle() == null ? "" : image.getTitle();
    }

    private static String size(ImagePlus image) {
        return image.getWidth() + " x " + image.getHeight() + " x "
                + image.getStackSize();
    }

    private static String quoted(String text) {
        return "'" + text + "'";
    }

    private static String number(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    private static String join(List<String> parts, String separator) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                text.append(separator);
            }
            text.append(parts.get(i));
        }
        return text.toString();
    }
}
