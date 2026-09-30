package ocs.nullmodel;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.OvalRoi;
import ij.gui.Roi;
import ij.process.ShortProcessor;
import ocs.engine.ColocEngine;
import ocs.engine.ColumnSpec;
import ocs.engine.DirectionKey;
import ocs.engine.EngineFamily;
import ocs.engine.EngineInputs;
import ocs.engine.EngineProgress;
import ocs.engine.EngineResult;
import ocs.engine.InputRequirement;
import ocs.engine.ObjectScore;
import ocs.engine.ScaleKind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Shared fixtures. Hand-computable sizes, so every expectation is checkable. */
final class NullFixtures {

    static final int SIZE = 40;

    private NullFixtures() {
    }

    /** Single-slice label image with 2×2 blocks at the given top-left corners. */
    static ImagePlus blocks(String title, int[][] corners) {
        return blocks(title, corners, 1);
    }

    static ImagePlus blocks(String title, int[][] corners, int slices) {
        ImageStack stack = new ImageStack(SIZE, SIZE);
        for (int z = 0; z < slices; z++) {
            stack.addSlice(new ShortProcessor(SIZE, SIZE));
        }
        for (int i = 0; i < corners.length; i++) {
            int label = i + 1;
            for (int z = 0; z < slices; z++) {
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        stack.getProcessor(z + 1)
                                .set(corners[i][0] + dx, corners[i][1] + dy, label);
                    }
                }
            }
        }
        return new ImagePlus(title, stack);
    }

    /** Four 2×2 objects on a wide grid, well clear of each other. */
    static int[][] fourApart() {
        return new int[][] {{6, 6}, {6, 26}, {26, 6}, {26, 26}};
    }

    /**
     * A circular region inscribed in the frame.
     *
     * <p>Deliberately not a rectangle: the four corners of its bounding box lie
     * outside it, so an implementation that scatters into the box instead of the
     * region puts objects there and the domain test catches it. That confusion
     * is defect 9 in the ledger and the bug Coloc 2 is reported to have.
     */
    static Roi circle() {
        return new OvalRoi(2, 2, SIZE - 4, SIZE - 4);
    }

    static EngineInputs inputs(ImagePlus a, ImagePlus b, List<Roi> domain) {
        return EngineInputs.builder(Arrays.asList(a, b))
                .channelNames(Arrays.asList("A", "B"))
                .domain(domain)
                .build();
    }

    static EngineInputs withCircle(ImagePlus a, ImagePlus b) {
        return inputs(a, b, Arrays.<Roi>asList(circle()));
    }

    /**
     * An engine whose answer depends only on <i>where</i> the objects are, so a
     * permutation genuinely moves its statistic.
     *
     * <p>Counts source objects sharing at least one voxel with any target object.
     * Deliberately trivial and exact — the null-model tests are about the runner's
     * bookkeeping, and a fixture engine with hand-checkable output keeps a failure
     * pointing at the runner rather than at somebody else's statistics.
     */
    static final class TouchCountEngine implements ColocEngine {

        private final String id;

        TouchCountEngine(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String displayName() {
            return "Touch count (fixture)";
        }

        @Override
        public EngineFamily family() {
            return EngineFamily.OBJECT;
        }

        @Override
        public Set<InputRequirement> requires() {
            return EnumSet.of(InputRequirement.LABEL_IMAGES);
        }

        @Override
        public List<ColumnSpec> columns() {
            return Arrays.asList(ColumnSpec.primary("Touching", "",
                    "1 where the source object shares a voxel with a target object",
                    ScaleKind.BINARY));
        }

        @Override
        public boolean isSymmetric() {
            return false;
        }

        @Override
        public double relativeCost() {
            return 1.0;
        }

        @Override
        public EngineResult compute(EngineInputs inputs, EngineProgress progress) {
            EngineResult.Builder builder = EngineResult.forEngine(id);
            for (DirectionKey direction : inputs.allDirections()) {
                builder.direction(direction, score(inputs, direction));
            }
            return builder.build();
        }

        private List<ObjectScore> score(EngineInputs inputs, DirectionKey direction) {
            ImagePlus source = inputs.labelImages().get(direction.sourceIndex());
            ImagePlus target = inputs.labelImages().get(direction.targetIndex());
            int width = source.getWidth();
            int height = source.getHeight();
            int depth = source.getStackSize();

            int maxLabel = 0;
            for (int z = 0; z < depth; z++) {
                for (int p = 0; p < width * height; p++) {
                    int label = (int) source.getStack().getProcessor(z + 1).getf(p);
                    if (label > maxLabel) {
                        maxLabel = label;
                    }
                }
            }
            int[] partner = new int[maxLabel + 1];
            for (int z = 0; z < depth; z++) {
                for (int p = 0; p < width * height; p++) {
                    int label = (int) source.getStack().getProcessor(z + 1).getf(p);
                    int other = (int) target.getStack().getProcessor(z + 1).getf(p);
                    if (label > 0 && other > 0 && partner[label] == 0) {
                        partner[label] = other;
                    }
                }
            }
            List<ObjectScore> scores = new ArrayList<ObjectScore>();
            for (int label = 1; label <= maxLabel; label++) {
                boolean present = false;
                for (int z = 0; z < depth && !present; z++) {
                    for (int p = 0; p < width * height && !present; p++) {
                        present = ((int) source.getStack().getProcessor(z + 1).getf(p)) == label;
                    }
                }
                if (present) {
                    boolean touching = partner[label] > 0;
                    scores.add(new ObjectScore(label, partner[label],
                            touching ? 1.0 : 0.0, touching));
                }
            }
            return scores;
        }
    }

    /** Cancels once {@code afterCalls} progress reports have been seen. */
    static final class CancelAfter implements EngineProgress {

        private final int afterCalls;
        private int seen;

        CancelAfter(int afterCalls) {
            this.afterCalls = afterCalls;
        }

        @Override
        public synchronized void report(String stage, double fraction) {
            seen++;
        }

        @Override
        public synchronized boolean isCancelled() {
            return seen >= afterCalls;
        }
    }
}
