package ocs.engine.object;

import ij.ImagePlus;
import ocs.engine.ColocEngine;
import ocs.engine.ColumnSpec;
import ocs.engine.DirectionKey;
import ocs.engine.EngineCancelledException;
import ocs.engine.EngineFamily;
import ocs.engine.EngineInputs;
import ocs.engine.EngineProgress;
import ocs.engine.EngineResult;
import ocs.engine.InputRequirement;
import ocs.engine.ObjectScore;
import ocs.engine.ScaleKind;
import sc.fiji.cpc.core.Channel;
import sc.fiji.cpc.core.CoincidenceObject;
import sc.fiji.cpc.core.CoincidenceResult;
import sc.fiji.cpc.core.PairwiseCoincidenceRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Centroid coincidence — an object colocalizes when its centroid falls inside an
 * object in the target channel.
 *
 * <p>Wraps {@code cpc-core}, the same engine CPC ships, so a result here and a
 * result from CPC on the same labels are the same number by construction rather
 * than by agreement. The cross-plugin agreement test in the test plan asserts
 * exactly that; if it ever fails, one of the two is wrong.
 *
 * <p>Purely geometric: no threshold, so it reports {@code n/a} for threshold
 * stability rather than a flip fraction. That is a point in its favour when
 * Discovery classifies it.
 */
public final class CentroidCoincidenceEngine implements ColocEngine {

    private static final ColumnSpec COINCIDENT = ColumnSpec.primary(
            "Centroid Coincident", "",
            "1 when this object's centroid falls inside a target object, else 0",
            ScaleKind.BINARY);

    private static final ColumnSpec PARTNER = ColumnSpec.partner(
            "Centroid Partner",
            "Label of the target object containing this centroid, or 0");

    @Override
    public String id() {
        return "cpc";
    }

    @Override
    public String displayName() {
        return "Centroid coincidence";
    }

    @Override
    public EngineFamily family() {
        return EngineFamily.OBJECT;
    }

    @Override
    public Set<InputRequirement> requires() {
        return Collections.singleton(InputRequirement.LABEL_IMAGES);
    }

    @Override
    public List<ColumnSpec> columns() {
        return Arrays.asList(COINCIDENT, PARTNER);
    }

    @Override
    public boolean isSymmetric() {
        // A's centroid being inside B says nothing about B's centroid being
        // inside A — a small object inside a large one is the asymmetric case
        // this measure exists to detect.
        return false;
    }

    @Override
    public double relativeCost() {
        // One centroid scan plus a lookup per object. Cheap next to anything
        // that touches every voxel more than once.
        return 1.0;
    }

    @Override
    public EngineResult compute(EngineInputs inputs, EngineProgress progress) {
        List<Channel> channels = new ArrayList<Channel>();
        for (int i = 0; i < inputs.channelCount(); i++) {
            ImagePlus labels = inputs.labelImages().get(i);
            channels.add(Channel.of(inputs.channelName(i), labels));
        }

        if (progress.isCancelled()) {
            throw new EngineCancelledException(id());
        }
        progress.report("Centroid coincidence", 0.0);

        CoincidenceResult coincidence = PairwiseCoincidenceRunner.run(channels, true);

        // cpc-core keys its directions by channel name; the registry keys by
        // index. Map once rather than per direction.
        Map<String, Integer> indexByName = new HashMap<String, Integer>();
        for (int i = 0; i < inputs.channelCount(); i++) {
            indexByName.put(inputs.channelName(i), Integer.valueOf(i));
        }

        EngineResult.Builder result = EngineResult.forEngine(id());
        List<sc.fiji.cpc.core.DirectionResult> directions = coincidence.directions();
        for (int d = 0; d < directions.size(); d++) {
            if (progress.isCancelled()) {
                throw new EngineCancelledException(id());
            }
            sc.fiji.cpc.core.DirectionResult direction = directions.get(d);

            Integer source = indexByName.get(direction.sourceName());
            Integer target = indexByName.get(direction.targetName());
            if (source == null || target == null) {
                // A channel name the inputs do not know about means the two
                // sides disagree about what was analysed. Fail rather than
                // silently dropping a direction from the table.
                throw new IllegalStateException("cpc-core reported direction "
                        + direction.sourceName() + " -> " + direction.targetName()
                        + ", which is not among the input channels "
                        + inputs.channelNames());
            }

            List<ObjectScore> scores = new ArrayList<ObjectScore>();
            for (CoincidenceObject object : direction.objects()) {
                scores.add(new ObjectScore(
                        object.label(),
                        object.partnerLabel(),
                        object.isCoincident() ? 1.0 : 0.0,
                        object.isCoincident()));
            }

            result.direction(new DirectionKey(
                    source.intValue(), direction.sourceName(),
                    target.intValue(), direction.targetName()), scores);

            progress.report("Centroid coincidence", (d + 1.0) / directions.size());
        }
        return result.build();
    }
}
