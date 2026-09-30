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
import ocs.agreement.AgreementCell;
import ocs.agreement.AgreementMatrix;
import ocs.discovery.DiscoveryClassifier;
import ocs.discovery.DiscoveryResult;
import ocs.discovery.MethodEvidence;
import ocs.engine.ColocEngine;
import ocs.engine.EngineCancelledException;
import ocs.engine.EngineInputs;
import ocs.engine.EngineProgress;
import ocs.engine.EngineRegistry;
import ocs.engine.EngineResult;
import ocs.engine.ThresholdBearing;
import ocs.nullmodel.NullModelResult;
import ocs.nullmodel.NullModelRunner;
import ocs.sweep.ThresholdSweep;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Run the suite from Java, with no dialog and no windows.
 *
 * <pre>
 * OCSResult result = OCS.run(OCSParameters.builder(labelImages)
 *         .methods("cpc", "volume-overlap")
 *         .build());
 * </pre>
 *
 * <p>Opens no dialogs, shows no windows, writes no files, needs no active
 * ImageJ window, and never calls {@code IJ.error} or {@code System.exit} as
 * control flow. Problems are exceptions; cancellation is
 * {@link EngineProgress#isCancelled()}.
 *
 * <p><b>Threading.</b> This runs the methods one after another. Each engine
 * parallelizes inside itself where that pays, and the chance test parallelizes
 * over its shuffles; the layer above — batch — parallelizes over images. Adding
 * a pool here would nest inside the batch pool and oversubscribe the machine
 * without shortening anything.
 */
public final class OCS {

    private OCS() {
    }

    public static OCSResult run(ImagePlus labelsA, ImagePlus labelsB) {
        return run(OCSParameters.builder(labelsA, labelsB).build());
    }

    public static OCSResult run(OCSParameters parameters) {
        return run(parameters, EngineProgress.SILENT);
    }

    /**
     * @throws IllegalArgumentException if the parameters name a method that does
     *         not exist, or ask for a chance test with no region to shuffle
     *         within
     * @throws EngineCancelledException if {@code progress} reports cancellation;
     *         never a partial result, because a half-finished run is
     *         indistinguishable from a complete one in every summary column
     */
    public static OCSResult run(OCSParameters parameters, EngineProgress progress) {
        validate(parameters);
        EngineProgress reporter =
                progress == null ? EngineProgress.SILENT : progress;
        EngineInputs inputs = parameters.toEngineInputs();
        EngineRegistry registry = EngineRegistry.createDefault();

        List<SkippedMethod> skipped = new ArrayList<SkippedMethod>();
        List<ColocEngine> engines =
                resolve(parameters, registry, inputs, skipped);

        List<EngineResult> results = compute(engines, inputs, reporter);

        List<NullModelResult> nullModels = parameters.runsNullModel()
                ? chanceTest(parameters, engines, inputs, reporter)
                : new ArrayList<NullModelResult>();

        List<AgreementCell> agreement = new ArrayList<AgreementCell>();
        if (parameters.runsAgreement()) {
            reporter.report("comparing the methods", -1.0);
            agreement = AgreementMatrix.withMinimumN(parameters.minCompareN())
                    .overObjects(results, byId(engines));
        }

        List<ThresholdSweep.Result> sweeps = new ArrayList<ThresholdSweep.Result>();
        if (parameters.runsThresholdSweep()) {
            reporter.report("checking how much each setting matters", -1.0);
            sweeps = ThresholdSweep.of(engines, inputs,
                    parameters.sweepWidth(), reporter);
        }

        List<DiscoveryResult> discovery = new ArrayList<DiscoveryResult>();
        if (parameters.runsDiscovery()) {
            reporter.report("classifying the methods", -1.0);
            List<MethodEvidence> evidence = MethodEvidence.gather(
                    nullModels, sweeps, parameters.alpha());
            discovery = DiscoveryClassifier.with(parameters.flipThreshold(),
                            parameters.minCompareN(), parameters.kappaLimit())
                    .classify(evidence, byId(engines), agreement);
        }

        return new OCSResult(parameters, engines, results, skipped,
                nullModels, agreement, sweeps, discovery);
    }

    // ---------- the steps ----------

    /**
     * Turns ids into engines, each built at the setting that was asked for.
     *
     * <p>Two different failures, deliberately handled differently. An <b>unknown
     * id</b> throws: a macro naming a method that no longer exists must fail
     * loudly rather than quietly run a smaller analysis than the one it says it
     * ran. A <b>known method this data cannot feed</b> is recorded and skipped,
     * because naming every method is the normal case — that is what the presets
     * do — and on label images without intensity images two of them genuinely
     * cannot run. Skipping is not silence: the reason travels in the result.
     */
    private static List<ColocEngine> resolve(OCSParameters parameters,
            EngineRegistry registry, EngineInputs inputs,
            List<SkippedMethod> skipped) {
        List<ColocEngine> engines = new ArrayList<ColocEngine>();
        List<String> ids = parameters.methodIds();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            if (!registry.has(id)) {
                throw new IllegalArgumentException("no method with id '" + id
                        + "'. Method ids are public API; see the id table in the"
                        + " documentation for the current set.");
            }
            ColocEngine engine = registry.byId(id);
            List<ocs.engine.InputRequirement> missing =
                    inputs.missing(engine.requires());
            if (!missing.isEmpty()) {
                skipped.add(new SkippedMethod(id, whyNot(missing)));
                continue;
            }
            Double setting = parameters.thresholds().get(id);
            if (setting != null && engine instanceof ThresholdBearing) {
                engine = ((ThresholdBearing) engine)
                        .withThreshold(setting.doubleValue());
            }
            engines.add(engine);
        }
        return engines;
    }

    /**
     * Runs each method in turn, reporting progress weighted by what each costs.
     *
     * <p>Weighted rather than counted, because the spread is about two orders of
     * magnitude: a progress bar that moved a thirteenth per method would sit at
     * 90% for most of the run and read as frozen.
     */
    private static List<EngineResult> compute(List<ColocEngine> engines,
            EngineInputs inputs, EngineProgress progress) {
        double total = 0.0;
        for (int i = 0; i < engines.size(); i++) {
            total += Math.max(engines.get(i).relativeCost(), 0.0);
        }
        List<EngineResult> results = new ArrayList<EngineResult>();
        double done = 0.0;
        for (int i = 0; i < engines.size(); i++) {
            if (progress.isCancelled()) {
                throw new EngineCancelledException("run cancelled");
            }
            ColocEngine engine = engines.get(i);
            progress.report(engine.displayName(),
                    total <= 0.0 ? -1.0 : done / total);
            results.add(engine.compute(inputs, progress));
            done += Math.max(engine.relativeCost(), 0.0);
        }
        progress.report("methods finished", total <= 0.0 ? -1.0 : 1.0);
        return results;
    }

    /**
     * @throws IllegalArgumentException if there is no region to shuffle within.
     *         Refused rather than defaulted to the whole frame: objects
     *         scattered over parts of the image that are not tissue collide less
     *         by chance, which makes every observation look more significant
     *         than it is.
     */
    private static List<NullModelResult> chanceTest(OCSParameters parameters,
            List<ColocEngine> engines, EngineInputs inputs,
            EngineProgress progress) {
        if (inputs.domain().isEmpty()) {
            throw new IllegalArgumentException("the chance test needs a region ROI"
                    + " saying where objects may be scattered: region_roi= in a"
                    + " macro, the Region field in the dialog, or domain(...) from"
                    + " Java. Or turn the chance test off (some presets switch it"
                    + " on). It will not default to the whole frame, because"
                    + " scattering objects over the parts that are not tissue"
                    + " makes every result look more significant than it is.");
        }
        progress.report("testing against chance", -1.0);
        return NullModelRunner.builder()
                .permutations(parameters.permutations())
                .seed(parameters.seed())
                .kind(parameters.nullModelKind())
                .build()
                .run(engines, inputs, progress);
    }

    // ---------- validation ----------

    private static void validate(OCSParameters parameters) {
        if (parameters == null) {
            throw new IllegalArgumentException("parameters must not be null");
        }
        List<ImagePlus> labels = parameters.labelImages();
        if (labels.size() < OCSParameters.MIN_IMAGES) {
            throw new IllegalArgumentException("at least "
                    + OCSParameters.MIN_IMAGES + " label images are needed; got "
                    + labels.size());
        }
        for (int i = 0; i < labels.size(); i++) {
            if (labels.get(i) == null) {
                throw new IllegalArgumentException(
                        "label image " + (i + 1) + " is null");
            }
            for (int j = i + 1; j < labels.size(); j++) {
                if (labels.get(i) == labels.get(j)) {
                    // Not a copy of the same picture — literally the same object.
                    // Every pair would then be a channel against itself, which
                    // reports perfect colocalization and looks like a result.
                    throw new IllegalArgumentException("label images " + (i + 1)
                            + " and " + (j + 1) + " are the same ImagePlus");
                }
            }
        }
        if (parameters.methodIds().isEmpty()) {
            throw new IllegalArgumentException("no methods were asked for");
        }
        if (parameters.permutations() < 1) {
            throw new IllegalArgumentException(
                    "a chance test needs at least one shuffle, not "
                            + parameters.permutations());
        }
        if (!(parameters.alpha() > 0.0 && parameters.alpha() < 1.0)) {
            throw new IllegalArgumentException(
                    "alpha must lie strictly between 0 and 1, not "
                            + parameters.alpha());
        }
    }

    private static String whyNot(List<ocs.engine.InputRequirement> missing) {
        StringBuilder reason = new StringBuilder("needs ");
        for (int i = 0; i < missing.size(); i++) {
            if (i > 0) {
                reason.append(i == missing.size() - 1 ? " and " : ", ");
            }
            reason.append(missing.get(i).displayName());
        }
        return reason.toString();
    }

    private static Map<String, ColocEngine> byId(List<ColocEngine> engines) {
        java.util.Map<String, ColocEngine> map =
                new java.util.LinkedHashMap<String, ColocEngine>();
        for (int i = 0; i < engines.size(); i++) {
            map.put(engines.get(i).id(), engines.get(i));
        }
        return map;
    }
}
