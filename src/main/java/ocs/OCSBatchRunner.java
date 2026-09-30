/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ocs;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ocs.engine.EngineProgress;
import ocs.io.OCSOutputWriter;
import sc.fiji.oc3d.core.ingest.RoiLabelImages;
import sc.fiji.oc3d.core.io.RegexGroupDiscovery;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Runs the suite over a folder.
 *
 * <p>Opens files, closes them, and writes CSVs when auto-save is on. Shows no
 * dialogs and needs no ImageJ window, so a headless script and the batch dialog
 * go through exactly this.
 *
 * <p><b>One field at a time.</b> Fields run serially; the parallelism is inside
 * each field, where an engine parallelizes itself and the chance test spreads
 * its shuffles over the machine. Running fields side by side as well would nest
 * a pool inside a pool, which shortens nothing, and would multiply memory by the
 * number of fields in flight: each holds its label and intensity stacks, and a
 * confocal stack is not small. (0.1.0 removed an unused {@code workers} setting
 * that suggested otherwise.)
 */
public final class OCSBatchRunner {

    /** Fewest files a group needs before it is a field rather than a stray. */
    public static final int MINIMUM_GROUP_SIZE = OCSParameters.MIN_IMAGES;

    private OCSBatchRunner() {
    }

    /**
     * What the folder holds, before anything is opened.
     *
     * <p>Shown before Run because a regular expression that matches nothing, or
     * matches everything as one enormous group, is the single most common batch
     * mistake and the one that costs the most time to discover afterwards.
     */
    public static String preview(OCSBatchParameters parameters) {
        return RegexGroupDiscovery.preview(discover(parameters), MINIMUM_GROUP_SIZE);
    }

    public static OCSBatchResult run(OCSBatchParameters parameters) {
        return run(parameters, EngineProgress.SILENT);
    }

    /**
     * @throws IllegalArgumentException if the folder or the pattern is unusable.
     *         A bad batch setup fails before the first file is opened rather
     *         than four hundred times.
     */
    public static OCSBatchResult run(OCSBatchParameters parameters,
            EngineProgress progress) {
        validate(parameters);
        EngineProgress reporter =
                progress == null ? EngineProgress.SILENT : progress;

        Map<String, Map<String, List<File>>> discovered = discover(parameters);
        List<Field> fields = flatten(discovered);
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("no groups of at least "
                    + MINIMUM_GROUP_SIZE + " files matched '"
                    + parameters.labelRegex() + "' in "
                    + parameters.labelFolder().getAbsolutePath());
        }

        List<Roi> sharedDomain = loadSharedDomain(parameters);
        File root = parameters.saveDir() != null
                ? parameters.saveDir() : parameters.labelFolder();
        if (parameters.isAutoSave()) {
            // Before the first field, not after it: a save folder that cannot
            // exist would otherwise fail four hundred times, once per analysis.
            OCSOutputWriter.checkWritable(root);
        }

        // With auto-save on, a field's tables are on disk once written, and
        // holding every field's full result until the end is the whole heap on
        // a large batch. Only the name and the verdicts are kept then.
        List<OCSResult> results = new ArrayList<OCSResult>();
        List<String> fieldNames = new ArrayList<String>();
        List<OCSBatchResult.Failure> failures =
                new ArrayList<OCSBatchResult.Failure>();
        boolean cancelled = false;
        boolean firstAppend = true;
        List<String> verdictFields = new ArrayList<String>();
        List<Map<String, Map<String, ocs.agreement.Verdict>>> verdicts =
                new ArrayList<Map<String, Map<String, ocs.agreement.Verdict>>>();

        for (int i = 0; i < fields.size(); i++) {
            if (reporter.isCancelled()) {
                cancelled = true;
                break;
            }
            Field field = fields.get(i);
            reporter.report(field.name, (double) i / fields.size());
            List<ImagePlus> opened = new ArrayList<ImagePlus>();
            try {
                OCSResult result = runOne(parameters, field, sharedDomain, opened,
                        reporter);
                if (parameters.isAutoSave()) {
                    // Counted as run only once it is on disk: a field whose
                    // tables never arrived is a failure, not a result.
                    OCSOutputWriter.write(result, root);
                    OCSOutputWriter.writeFieldRecord(result, root);
                    OCSOutputWriter.append(result, root, firstAppend);
                    firstAppend = false;
                } else {
                    results.add(result);
                }
                fieldNames.add(field.name);
                verdictFields.add(field.name);
                verdicts.add(BatchVerdictAgreement.verdictsOf(result));
            } catch (ocs.engine.EngineCancelledException stopped) {
                cancelled = true;
                break;
            } catch (Exception failed) {
                // Collected, not thrown. One unreadable file in four hundred
                // must not lose the other three hundred and ninety-nine, and a
                // batch that died at the first bad field would be started
                // overnight and found dead in the morning.
                failures.add(new OCSBatchResult.Failure(field.name,
                        failed.getMessage() == null
                                ? failed.toString() : failed.getMessage()));
            } finally {
                closeAll(opened);
            }
        }
        Map<String, List<ocs.agreement.AgreementCell>> verdictAgreement =
                BatchVerdictAgreement.across(verdictFields, verdicts,
                        ocs.agreement.VerdictAgreement.DEFAULT_MINIMUM_RUNS);
        if (parameters.isAutoSave() && !verdictAgreement.isEmpty()) {
            writeVerdictAgreement(verdictAgreement, root);
        }
        reporter.report("batch finished", 1.0);
        return new OCSBatchResult(results, fieldNames, failures,
                parameters.isAutoSave() ? root : null, cancelled, verdictAgreement);
    }

    /** Next to the batch's combined tables, as {@code verdict-agreement.csv}. */
    static final String VERDICT_AGREEMENT_FILE = "verdict-agreement.csv";

    private static void writeVerdictAgreement(
            Map<String, List<ocs.agreement.AgreementCell>> cells, File root) {
        File folder = new File(new File(root, OCSOutputWriter.FOLDER), "batch");
        if (!folder.isDirectory() && !folder.mkdirs()) {
            throw new IllegalStateException("could not create "
                    + folder.getAbsolutePath());
        }
        try {
            sc.fiji.oc3d.core.io.CsvWriter.write(new File(folder, VERDICT_AGREEMENT_FILE),
                    BatchVerdictAgreement.table(cells));
        } catch (java.io.IOException failed) {
            // Not swallowed, for the reason OCSOutputWriter gives: a batch that
            // reports success while silently failing to save costs a day.
            throw new IllegalStateException("could not write "
                    + VERDICT_AGREEMENT_FILE + ": " + failed.getMessage(), failed);
        }
    }

    // ---------- one field ----------

    private static OCSResult runOne(OCSBatchParameters parameters, Field field,
            List<Roi> sharedDomain, List<ImagePlus> opened,
            EngineProgress progress) {
        List<ImagePlus> labels = open(field.files, opened);
        OCSParameters template = parameters.template();
        OCSParameters.Builder builder = OCSParameters.builder(labels)
                .sourceName(field.name)
                .channelNames(namesOf(field.files));

        if (template != null) {
            builder.methods(template.methodIds())
                    .thresholds(template.thresholds())
                    .bidirectional(template.isBidirectional())
                    .nullModel(template.runsNullModel())
                    .agreement(template.runsAgreement())
                    .thresholdSweep(template.runsThresholdSweep())
                    .permutations(template.permutations())
                    .nullModelKind(template.nullModelKind())
                    .seed(template.seed())
                    .alpha(template.alpha())
                    .flipThreshold(template.flipThreshold())
                    .minCompareN(template.minCompareN())
                    .kappaLimit(template.kappaLimit())
                    .sweepWidth(template.sweepWidth())
                    .calibration(template.calibration());
            if (template.runsDiscovery()) {
                builder.discovery(true);
            }
        }
        if (parameters.usesIntensityImages()) {
            builder.intensityImages(open(
                    intensityFor(parameters, field), opened));
        }
        if (sharedDomain != null && !sharedDomain.isEmpty()) {
            builder.domain(sharedDomain);
        }
        // The batch's own progress, so Escape stops the field in flight rather
        // than waiting for it to finish.
        return OCS.run(builder.build(), progress);
    }

    /**
     * The intensity file matching each label file, by whole context.
     *
     * @throws IllegalArgumentException if any is missing. Not padded with null:
     *         a channel silently analysed without its intensity image would
     *         produce a table that looks complete and is not.
     */
    private static List<File> intensityFor(OCSBatchParameters parameters,
            Field field) {
        List<File> matched = RegexGroupDiscovery.matchByContextAndChannel(
                field.files, compile(parameters.labelRegex(), "label"),
                parameters.intensityFolder(),
                compile(parameters.intensityRegex(), "intensity"),
                parameters.varyingGroup());
        for (int i = 0; i < matched.size(); i++) {
            if (matched.get(i) == null) {
                throw new IllegalArgumentException("no intensity image matching "
                        + field.files.get(i).getName() + " in "
                        + parameters.intensityFolder().getAbsolutePath());
            }
        }
        return matched;
    }

    private static List<ImagePlus> open(List<File> files, List<ImagePlus> opened) {
        List<ImagePlus> images = new ArrayList<ImagePlus>();
        for (int i = 0; i < files.size(); i++) {
            ImagePlus image = IJ.openImage(files.get(i).getAbsolutePath());
            if (image == null) {
                throw new IllegalArgumentException(
                        "could not open " + files.get(i).getAbsolutePath());
            }
            opened.add(image);
            images.add(image);
        }
        return images;
    }

    /**
     * Frees every image this field opened.
     *
     * <p>{@code close()} alone leaves the voxel arrays reachable; {@code flush()}
     * nulls them. Over four hundred fields that difference is the whole heap.
     */
    private static void closeAll(List<ImagePlus> opened) {
        for (int i = 0; i < opened.size(); i++) {
            ImagePlus image = opened.get(i);
            if (image != null) {
                image.close();
                image.flush();
            }
        }
        opened.clear();
    }

    // ---------- discovery ----------

    private static Map<String, Map<String, List<File>>> discover(
            OCSBatchParameters parameters) {
        validate(parameters);
        Set<File> excluded = new HashSet<File>();
        if (parameters.saveDir() != null) {
            // Only the suite's own folder, never the save directory itself.
            // Saving into the label folder is normal, and excluding that would
            // mean a batch configured the obvious way found no files at all.
            excluded.add(new File(parameters.saveDir(), OCSOutputWriter.FOLDER));
        }
        // Order within a group IS channel identity — the first file becomes
        // channel 1 — so it has to be deterministic and it has to be the one the
        // naming implies. Plain filename order puts _C1 before _C2.
        return RegexGroupDiscovery.findGroupsRecursive(
                parameters.labelFolder(), compile(parameters.labelRegex(), "label"),
                parameters.varyingGroup(), parameters.isRecursive(),
                RegexGroupDiscovery.GroupOrder.FILENAME, excluded);
    }

    /** Flattens folder → group → files into a running order, skipping strays. */
    private static List<Field> flatten(
            Map<String, Map<String, List<File>>> discovered) {
        List<Field> fields = new ArrayList<Field>();
        for (Map.Entry<String, Map<String, List<File>>> folder
                : discovered.entrySet()) {
            for (Map.Entry<String, List<File>> group
                    : folder.getValue().entrySet()) {
                if (group.getValue().size() < MINIMUM_GROUP_SIZE) {
                    continue;
                }
                String name = RegexGroupDiscovery.groupDisplayName(group.getKey());
                if (!folder.getKey().isEmpty()) {
                    // Two folders can hold a field of the same name, and a batch
                    // that overwrote one with the other would lose it silently.
                    name = folder.getKey().replace(File.separatorChar, '-')
                            + "-" + name;
                }
                fields.add(new Field(name, group.getValue()));
            }
        }
        return fields;
    }

    private static List<String> namesOf(List<File> files) {
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < files.size(); i++) {
            names.add(files.get(i).getName());
        }
        return names;
    }

    private static List<Roi> loadSharedDomain(OCSBatchParameters parameters) {
        if (parameters.sharedRegionRoi() == null) {
            return null;
        }
        try {
            Roi[] rois = RoiLabelImages.loadRoiSet(
                    parameters.sharedRegionRoi().getAbsolutePath());
            return rois == null
                    ? null : new ArrayList<Roi>(Arrays.asList(rois));
        } catch (Exception unreadable) {
            throw new IllegalArgumentException("could not read the region ROI "
                    + parameters.sharedRegionRoi().getAbsolutePath() + ": "
                    + unreadable.getMessage());
        }
    }

    // ---------- validation ----------

    private static void validate(OCSBatchParameters parameters) {
        if (parameters == null) {
            throw new IllegalArgumentException("batch parameters are required");
        }
        if (parameters.labelFolder() == null
                || !parameters.labelFolder().isDirectory()) {
            throw new IllegalArgumentException("no such folder: "
                    + parameters.labelFolder());
        }
        if (parameters.labelRegex() == null
                || parameters.labelRegex().trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "a pattern is needed to tell the channels apart");
        }
        if (parameters.varyingGroup() < 1) {
            throw new IllegalArgumentException("the varying group is which capture"
                    + " group differs between channels, counting from 1, not "
                    + parameters.varyingGroup());
        }
        Pattern pattern = compile(parameters.labelRegex(), "label");
        if (pattern.matcher("").groupCount() < parameters.varyingGroup()) {
            throw new IllegalArgumentException("the pattern has "
                    + pattern.matcher("").groupCount() + " capture group(s), so "
                    + "group " + parameters.varyingGroup() + " cannot be the one "
                    + "that varies between channels");
        }
        if (parameters.usesIntensityImages()
                && !parameters.intensityFolder().isDirectory()) {
            throw new IllegalArgumentException("no such intensity folder: "
                    + parameters.intensityFolder());
        }
        if (parameters.sharedRegionRoi() != null
                && !parameters.sharedRegionRoi().isFile()) {
            throw new IllegalArgumentException("no such region ROI file: "
                    + parameters.sharedRegionRoi());
        }
        if (parameters.saveDir() != null && parameters.saveDir().exists()
                && !parameters.saveDir().isDirectory()) {
            throw new IllegalArgumentException("the save path is not a folder: "
                    + parameters.saveDir());
        }
    }

    private static Pattern compile(String regex, String which) {
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException broken) {
            throw new IllegalArgumentException("the " + which + " pattern is not a"
                    + " valid regular expression: " + broken.getMessage());
        }
    }

    /** One field: everything imaged from the same place at the same time. */
    private static final class Field {

        private final String name;
        private final List<File> files;

        Field(String name, List<File> files) {
            this.name = name;
            this.files = files;
        }
    }

    /** Kept so the batch preview and the runner cannot disagree about grouping. */
    static Map<String, Map<String, List<File>>> groupsFor(
            OCSBatchParameters parameters) {
        return new LinkedHashMap<String, Map<String, List<File>>>(
                discover(parameters));
    }
}
