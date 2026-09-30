/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ocs;

import ocs.agreement.AgreementCell;
import ocs.discovery.DiscoveryResult;
import ocs.engine.ColocEngine;
import ocs.engine.EngineResult;
import ocs.nullmodel.NullModelResult;
import ocs.sweep.ThresholdSweep;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one run produced.
 *
 * <p>Objects, not tables. Tables are one way of looking at this and the batch
 * layer wants a different one, so the analysis stops here and the presentation
 * is somebody else's job.
 *
 * <p>Every optional layer returns an empty list rather than null when it was not
 * asked for. An empty agreement matrix and a null one are the same thing to a
 * caller who has to check either way, and only one of them can be looped over
 * without thinking about it.
 */
public final class OCSResult {

    private final OCSParameters parameters;
    private final List<ColocEngine> engines;
    private final List<EngineResult> engineResults;
    private final List<SkippedMethod> skipped;
    private final List<NullModelResult> nullModels;
    private final List<AgreementCell> agreement;
    private final List<ThresholdSweep.Result> sweeps;
    private final List<DiscoveryResult> discovery;

    OCSResult(OCSParameters parameters, List<ColocEngine> engines,
            List<EngineResult> engineResults, List<SkippedMethod> skipped,
            List<NullModelResult> nullModels, List<AgreementCell> agreement,
            List<ThresholdSweep.Result> sweeps, List<DiscoveryResult> discovery) {
        this.parameters = parameters;
        this.engines = unmodifiable(engines);
        this.engineResults = unmodifiable(engineResults);
        this.skipped = unmodifiable(skipped);
        this.nullModels = unmodifiable(nullModels);
        this.agreement = unmodifiable(agreement);
        this.sweeps = unmodifiable(sweeps);
        this.discovery = unmodifiable(discovery);
    }

    public OCSParameters parameters() {
        return parameters;
    }

    /** The engines that ran, already built at the settings that were chosen. */
    public List<ColocEngine> engines() {
        return engines;
    }

    /** Keyed by id, for the layers that need to ask an engine about itself. */
    public Map<String, ColocEngine> enginesById() {
        Map<String, ColocEngine> byId = new LinkedHashMap<String, ColocEngine>();
        for (int i = 0; i < engines.size(); i++) {
            byId.put(engines.get(i).id(), engines.get(i));
        }
        return Collections.unmodifiableMap(byId);
    }

    /** One per method that ran, in the order the methods were given. */
    public List<EngineResult> engineResults() {
        return engineResults;
    }

    public EngineResult resultFor(String engineId) {
        for (int i = 0; i < engineResults.size(); i++) {
            if (engineResults.get(i).engineId().equals(engineId)) {
                return engineResults.get(i);
            }
        }
        return null;
    }

    /** Methods that were asked for and could not run, each with its reason. */
    public List<SkippedMethod> skipped() {
        return skipped;
    }

    /** Empty when the chance test was not run. */
    public List<NullModelResult> nullModels() {
        return nullModels;
    }

    /** Empty when the methods were not compared with each other. */
    public List<AgreementCell> agreement() {
        return agreement;
    }

    /** Empty when no threshold was swept. */
    public List<ThresholdSweep.Result> sweeps() {
        return sweeps;
    }

    /** Empty when the methods were not classified. */
    public List<DiscoveryResult> discovery() {
        return discovery;
    }

    private static <T> List<T> unmodifiable(List<T> input) {
        return Collections.unmodifiableList(input == null
                ? new ArrayList<T>() : new ArrayList<T>(input));
    }
}
