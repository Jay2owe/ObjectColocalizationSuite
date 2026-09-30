/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ocs;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.Calibration;
import ocs.engine.EngineInputs;
import ocs.engine.EngineRegistry;
import ocs.nullmodel.NullModelKind;
import ocs.nullmodel.NullModelRunner;
import ocs.ui.MethodChoice;
import ocs.ui.Preset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything one run needs: the images, the methods, and which extra checks.
 *
 * <p>Immutable. The dialog produces one of these, and so does a macro line, and
 * so does a Java caller — which is the point. Three ways in, one description of
 * the run, so the interactive and scripted paths cannot drift into two different
 * analyses that share a name.
 *
 * <p>Holds no engines, only their identifiers and settings; {@link OCS} builds
 * the engines. An engine is a live object with a thread pool behind it, and a
 * parameter bundle is something you can print, store and compare.
 */
public final class OCSParameters {

    /** Fewer than two channels and there is no pair to compare. */
    public static final int MIN_IMAGES = EngineInputs.MIN_CHANNELS;

    private final List<ImagePlus> labelImages;
    private final List<ImagePlus> intensityImages;
    private final List<String> channelNames;
    private final List<Roi> domain;
    private final Calibration calibration;
    private final List<String> methodIds;
    private final Map<String, Double> thresholds;
    private final boolean bidirectional;
    private final boolean nullModel;
    private final boolean agreement;
    private final boolean thresholdSweep;
    private final boolean discovery;
    private final int permutations;
    private final NullModelKind nullModelKind;
    private final long seed;
    private final double alpha;
    private final double flipThreshold;
    private final double sweepWidth;
    private final int minCompareN;
    private final double kappaLimit;
    private final String sourceName;

    private OCSParameters(Builder builder) {
        this.labelImages = copyOf(builder.labelImages);
        this.intensityImages = builder.intensityImages == null
                ? null : copyOf(builder.intensityImages);
        this.channelNames = builder.channelNames == null
                ? null : Collections.unmodifiableList(
                        new ArrayList<String>(builder.channelNames));
        this.domain = builder.domain == null
                ? Collections.unmodifiableList(new ArrayList<Roi>())
                : Collections.unmodifiableList(new ArrayList<Roi>(builder.domain));
        this.calibration = builder.calibration;
        this.methodIds = Collections.unmodifiableList(
                new ArrayList<String>(builder.methodIds));
        this.thresholds = Collections.unmodifiableMap(
                new LinkedHashMap<String, Double>(builder.thresholds));
        this.bidirectional = builder.bidirectional;
        this.nullModel = builder.nullModel;
        this.agreement = builder.agreement;
        this.thresholdSweep = builder.thresholdSweep;
        this.discovery = builder.discovery;
        this.permutations = builder.permutations;
        this.nullModelKind = builder.nullModelKind;
        this.seed = builder.seed;
        this.alpha = builder.alpha;
        this.flipThreshold = builder.flipThreshold;
        this.sweepWidth = builder.sweepWidth;
        this.minCompareN = builder.minCompareN;
        this.kappaLimit = builder.kappaLimit;
        this.sourceName = builder.sourceName != null ? builder.sourceName
                : (this.labelImages.isEmpty() || this.labelImages.get(0) == null
                        ? "" : this.labelImages.get(0).getTitle());
    }

    public static Builder builder(List<ImagePlus> labelImages) {
        return new Builder(labelImages);
    }

    public static Builder builder(ImagePlus labelsA, ImagePlus labelsB) {
        List<ImagePlus> images = new ArrayList<ImagePlus>();
        images.add(labelsA);
        images.add(labelsB);
        return new Builder(images);
    }

    // ---------- images ----------

    public List<ImagePlus> labelImages() {
        return labelImages;
    }

    /** Parallel to the label images, or null when none were supplied. */
    public List<ImagePlus> intensityImages() {
        return intensityImages;
    }

    public List<String> channelNames() {
        return channelNames;
    }

    /** Where objects may be scattered. Empty means no region was given. */
    public List<Roi> domain() {
        return domain;
    }

    public Calibration calibration() {
        return calibration;
    }

    // ---------- what to run ----------

    public List<String> methodIds() {
        return methodIds;
    }

    public Map<String, Double> thresholds() {
        return thresholds;
    }

    /**
     * Whether to report both A→B and B→A.
     *
     * <p>A reporting choice, not a computing one: every engine measures both
     * directions regardless, because "how much of A is in B" and "how much of B
     * is in A" are different questions with different answers whenever the two
     * channels have different object counts. Turning this off halves the table,
     * it does not halve the work.
     */
    public boolean isBidirectional() {
        return bidirectional;
    }

    public boolean runsNullModel() {
        return nullModel;
    }

    public boolean runsAgreement() {
        return agreement;
    }

    public boolean runsThresholdSweep() {
        return thresholdSweep;
    }

    public boolean runsDiscovery() {
        return discovery;
    }

    public int permutations() {
        return permutations;
    }

    /**
     * Which way the chance test shuffles a channel.
     *
     * <p>Two different null hypotheses, not two speeds of one. Per-object
     * relocation asks whether these objects would meet this often if each were
     * placed independently; whole-channel displacement asks whether the two
     * channels would meet this often if each kept its own arrangement but not
     * its position relative to the other. Their <i>p</i> values are not
     * interchangeable, which is why this travels in the run record.
     */
    public NullModelKind nullModelKind() {
        return nullModelKind;
    }

    public long seed() {
        return seed;
    }

    /** Significance level for the chance test and for Discovery's verdicts. */
    public double alpha() {
        return alpha;
    }

    /** Flip fraction above which Discovery calls a method fragile. */
    /**
     * How far either side of each method's chosen threshold the sweep reaches,
     * as a fraction of what that measure can be.
     *
     * <p>One number that means the same thing on every method, because the six
     * threshold-bearing engines are on four different scales: 0.2 is ±20
     * percentage points for an overlap percentage, ±0.2 for a Jaccard index,
     * and ±0.4 for a correlation running −1 to 1. A fixed absolute width could
     * not be right on more than one of them.
     *
     * <p>{@code 1.0} or more sweeps each measure's whole range.
     */
    public double sweepWidth() {
        return sweepWidth;
    }

    public double flipThreshold() {
        return flipThreshold;
    }

    /** Fewest shared objects an agreement cell needs before it is trusted. */
    public int minCompareN() {
        return minCompareN;
    }

    /** Kappa below which two methods are treated as disagreeing. */
    public double kappaLimit() {
        return kappaLimit;
    }

    /**
     * What to call this run in the {@code Image} column of every table.
     *
     * <p>Defaults to the first label image's title. It is one name for a run
     * that took several images, because a batch aggregating hundreds of runs
     * needs one key per run, and "which of the two channel titles" is not a
     * question anyone should have to answer while reading a spreadsheet.
     */
    public String sourceName() {
        return sourceName;
    }

    /** The bundle as the engines want it. */
    public EngineInputs toEngineInputs() {
        EngineInputs.Builder builder = EngineInputs.builder(labelImages);
        if (intensityImages != null) {
            builder.intensityImages(intensityImages);
        }
        if (channelNames != null) {
            builder.channelNames(channelNames);
        }
        if (!domain.isEmpty()) {
            builder.domain(domain);
        }
        if (calibration != null) {
            builder.calibration(calibration);
        }
        return builder.build();
    }

    private static List<ImagePlus> copyOf(List<ImagePlus> input) {
        return Collections.unmodifiableList(new ArrayList<ImagePlus>(input));
    }

    public static final class Builder {

        private final List<ImagePlus> labelImages;
        private List<ImagePlus> intensityImages;
        private List<String> channelNames;
        private List<Roi> domain;
        private Calibration calibration;
        private List<String> methodIds = Preset.defaultPreset().engineIds();
        private Map<String, Double> thresholds = new LinkedHashMap<String, Double>();
        private boolean bidirectional = true;
        private boolean nullModel;
        private boolean agreement;
        private boolean thresholdSweep;
        private boolean discovery;
        private int permutations = NullModelRunner.DEFAULT_PERMUTATIONS;
        private NullModelKind nullModelKind = NullModelRunner.DEFAULT_KIND;
        private long seed = NullModelRunner.DEFAULT_SEED;
        private double alpha = ocs.agreement.Verdict.DEFAULT_ALPHA;
        private double flipThreshold = ocs.sweep.FlipFraction.DEFAULT_FRAGILE_ABOVE;
        private double sweepWidth = ocs.sweep.ThresholdSweep.DEFAULT_SWEEP_WIDTH;
        private int minCompareN = ocs.agreement.AgreementCell.DEFAULT_MINIMUM_N;
        private double kappaLimit =
                ocs.discovery.DiscoveryClassifier.DEFAULT_KAPPA_LIMIT;
        private String sourceName;

        private Builder(List<ImagePlus> labelImages) {
            this.labelImages = labelImages == null
                    ? new ArrayList<ImagePlus>() : new ArrayList<ImagePlus>(labelImages);
        }

        public Builder intensityImages(List<ImagePlus> images) {
            this.intensityImages = images == null
                    ? null : new ArrayList<ImagePlus>(images);
            return this;
        }

        public Builder channelNames(List<String> names) {
            this.channelNames = names == null ? null : new ArrayList<String>(names);
            return this;
        }

        public Builder domain(List<Roi> rois) {
            this.domain = rois == null ? null : new ArrayList<Roi>(rois);
            return this;
        }

        public Builder calibration(Calibration calibration) {
            this.calibration = calibration;
            return this;
        }

        /**
         * @param ids engine ids, which are public API — see the id table in
         *            {@code 02_CONTRACT.md}. Unknown ids are rejected at
         *            {@link OCS#run}, not silently dropped: a macro naming a
         *            method that no longer exists must fail loudly rather than
         *            quietly run a smaller analysis.
         */
        public Builder methods(String... ids) {
            List<String> list = new ArrayList<String>();
            for (int i = 0; i < ids.length; i++) {
                list.add(ids[i]);
            }
            this.methodIds = list;
            return this;
        }

        public Builder methods(List<String> ids) {
            this.methodIds = ids == null
                    ? new ArrayList<String>() : new ArrayList<String>(ids);
            return this;
        }

        /** Every method named by the preset, whether or not this data supports it. */
        public Builder preset(String name) {
            Preset preset = Preset.byName(name);
            this.methodIds = new ArrayList<String>(preset.engineIds());
            this.nullModel = preset.runsNullModel();
            this.agreement = preset.runsAgreement();
            this.thresholdSweep = preset.runsThresholdSweep();
            this.discovery = preset.runsDiscovery();
            return this;
        }

        /** Every method the registry knows. */
        public Builder allMethods() {
            List<String> ids = new ArrayList<String>();
            List<ocs.engine.ColocEngine> engines = EngineRegistry.createDefault().all();
            for (int i = 0; i < engines.size(); i++) {
                ids.add(engines.get(i).id());
            }
            this.methodIds = ids;
            return this;
        }

        public Builder threshold(String engineId, double value) {
            this.thresholds.put(engineId, Double.valueOf(value));
            return this;
        }

        public Builder thresholds(Map<String, Double> values) {
            this.thresholds = values == null
                    ? new LinkedHashMap<String, Double>()
                    : new LinkedHashMap<String, Double>(values);
            return this;
        }

        /**
         * Takes the methods, settings and checks straight from what the dialog
         * produced.
         *
         * <p>The images are not part of a {@link MethodChoice} and must still be
         * supplied here — a slip describes the analysis, not the data.
         */
        public Builder from(MethodChoice choice) {
            this.methodIds = new ArrayList<String>(choice.engineIds());
            this.thresholds = new LinkedHashMap<String, Double>(choice.thresholds());
            this.nullModel = choice.runsNullModel();
            this.agreement = choice.runsAgreement();
            this.thresholdSweep = choice.runsThresholdSweep();
            this.discovery = choice.runsDiscovery();
            this.permutations = choice.permutations();
            this.seed = choice.seed();
            return this;
        }

        public Builder bidirectional(boolean bidirectional) {
            this.bidirectional = bidirectional;
            return this;
        }

        public Builder nullModel(boolean on) {
            this.nullModel = on;
            return this;
        }

        public Builder agreement(boolean on) {
            this.agreement = on;
            return this;
        }

        public Builder thresholdSweep(boolean on) {
            this.thresholdSweep = on;
            return this;
        }

        /**
         * Classify every method as Usable / Uninformative here / Fragile /
         * Divergent / Not applicable.
         *
         * <p>Turns on the evidence it needs. Discovery reads the chance test's
         * verdicts, the agreement matrix and the threshold sweep, so asking for
         * the classification without them would produce five "not applicable"
         * rows and look like a broken feature rather than a missing input.
         */
        public Builder discovery(boolean on) {
            this.discovery = on;
            if (on) {
                this.nullModel = true;
                this.agreement = true;
                this.thresholdSweep = true;
            }
            return this;
        }

        public Builder permutations(int count) {
            this.permutations = count;
            return this;
        }

        /**
         * @param kind how to shuffle a channel; see
         *             {@link OCSParameters#nullModelKind()}. Defaults to
         *             {@link NullModelRunner#DEFAULT_KIND}; select per-object
         *             explicitly when the stronger re-packing null is required
         */
        public Builder nullModelKind(NullModelKind kind) {
            this.nullModelKind = kind;
            return this;
        }

        public Builder seed(long seed) {
            this.seed = seed;
            return this;
        }

        public Builder alpha(double alpha) {
            this.alpha = alpha;
            return this;
        }

        /**
         * @param sweepWidth fraction of each measure's own range to reach
         *                   either side of its chosen threshold; 1.0 or more
         *                   sweeps the whole range
         * @throws IllegalArgumentException if the width is not positive. Zero
         *         would be a sweep of one point, which reports every method
         *         perfectly stable — the most flattering possible answer, and
         *         one nothing in the output would mark as vacuous
         */
        public Builder sweepWidth(double sweepWidth) {
            if (!(sweepWidth > 0.0)) {
                throw new IllegalArgumentException("the threshold sweep must have"
                        + " a width, not " + sweepWidth
                        + "; use 1.0 or more to sweep the whole range");
            }
            this.sweepWidth = sweepWidth;
            return this;
        }

        public Builder flipThreshold(double flipThreshold) {
            this.flipThreshold = flipThreshold;
            return this;
        }

        public Builder minCompareN(int minCompareN) {
            this.minCompareN = minCompareN;
            return this;
        }

        public Builder kappaLimit(double kappaLimit) {
            this.kappaLimit = kappaLimit;
            return this;
        }

        /** Overrides the {@code Image} column; defaults to the first title. */
        public Builder sourceName(String name) {
            this.sourceName = name;
            return this;
        }

        public OCSParameters build() {
            return new OCSParameters(this);
        }
    }
}
