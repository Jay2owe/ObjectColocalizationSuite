package ocs.sweep;

import ocs.engine.DirectionKey;
import ocs.engine.EngineResult;
import ocs.engine.ObjectScore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How much of the answer is the threshold rather than the data.
 *
 * <p>An engine that calls objects colocalized above 30% overlap is reporting a
 * choice as much as a measurement. Flip fraction asks the obvious follow-up: if
 * the cut-off had been somewhere else in a reasonable range, how many objects
 * would have been classified differently? Twelve per cent is a footnote. Sixty
 * per cent means the finding is a statement about where the line was drawn.
 *
 * <p>Deliberately scale-free — a proportion of objects — so it means the same
 * thing for a percentage threshold, a distance in micrometres and a correlation
 * coefficient, and can be compared across methods that share no units.
 *
 * <p>An object counts as flipped if its coincident flag is not the same at
 * <i>every</i> step of the ladder. Counting adjacent changes instead would
 * reward a method that oscillates and punish one that switches cleanly once,
 * which is backwards: a single clean switch is the expected behaviour of a
 * monotone measure, and it is the objects that never settle that make a finding
 * fragile.
 */
public final class FlipFraction {

    /** Above this, Discovery calls a method Fragile. A parameter, not a rule. */
    public static final double DEFAULT_FRAGILE_ABOVE = 0.10;

    private final DirectionKey direction;
    private final int objects;
    private final int flipped;

    FlipFraction(DirectionKey direction, int objects, int flipped) {
        this.direction = direction;
        this.objects = objects;
        this.flipped = flipped;
    }

    public DirectionKey direction() {
        return direction;
    }

    /** Objects present at every step of the ladder. */
    public int objects() {
        return objects;
    }

    public int flipped() {
        return flipped;
    }

    /** NaN when no object survived the whole ladder — not zero, which reads as stable. */
    public double value() {
        return objects == 0 ? Double.NaN : flipped / (double) objects;
    }

    public boolean isFragileAbove(double limit) {
        double value = value();
        return !Double.isNaN(value) && value > limit;
    }

    @Override
    public String toString() {
        return direction.label() + " flip fraction " + value()
                + " (" + flipped + "/" + objects + ")";
    }

    /**
     * Flip fractions per direction, from one engine's results across a ladder.
     *
     * <p>Objects are matched by label, and an object missing from any step is
     * excluded from the count entirely. Treating a missing object as unflipped
     * would let a method that stops reporting objects at high thresholds look
     * more stable the more it dropped.
     */
    static List<FlipFraction> across(List<EngineResult> ladderResults) {
        if (ladderResults == null || ladderResults.size() < 2) {
            throw new IllegalArgumentException(
                    "a flip fraction needs at least two threshold steps");
        }
        Set<DirectionKey> directions =
                new LinkedHashSet<DirectionKey>(ladderResults.get(0).directions());
        for (int i = 1; i < ladderResults.size(); i++) {
            directions.retainAll(ladderResults.get(i).directions());
        }

        List<FlipFraction> fractions = new ArrayList<FlipFraction>();
        for (DirectionKey direction : directions) {
            List<Map<Integer, Boolean>> steps =
                    new ArrayList<Map<Integer, Boolean>>();
            for (int i = 0; i < ladderResults.size(); i++) {
                Map<Integer, Boolean> flags = new LinkedHashMap<Integer, Boolean>();
                List<ObjectScore> scores = ladderResults.get(i).scores(direction);
                for (int s = 0; s < scores.size(); s++) {
                    flags.put(Integer.valueOf(scores.get(s).sourceLabel()),
                            Boolean.valueOf(scores.get(s).isCoincident()));
                }
                steps.add(flags);
            }

            int objects = 0;
            int flipped = 0;
            for (Integer label : steps.get(0).keySet()) {
                Boolean first = steps.get(0).get(label);
                boolean everywhere = true;
                boolean changed = false;
                for (int i = 1; i < steps.size() && everywhere; i++) {
                    Boolean here = steps.get(i).get(label);
                    if (here == null) {
                        everywhere = false;
                    } else if (!here.equals(first)) {
                        changed = true;
                    }
                }
                if (everywhere) {
                    objects++;
                    if (changed) {
                        flipped++;
                    }
                }
            }
            fractions.add(new FlipFraction(direction, objects, flipped));
        }
        return fractions;
    }
}
