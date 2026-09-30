/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ocs.io;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ImageProcessor;

import java.util.HashMap;
import java.util.Map;

/**
 * How big each object is, in voxels and in calibrated units.
 *
 * <p>Every method measures something <i>about</i> objects and none of them
 * measures the objects themselves, so this sits outside the engines. It is the
 * column that lets a reader ask the obvious follow-up — "is the method only
 * calling the big ones coincident?" — which no colocalization number answers on
 * its own.
 *
 * <p>One pass over the voxels per channel, counting into a map. Not one pass per
 * object: with a few thousand objects that would be a few thousand passes over
 * the same stack for a number that costs one.
 */
final class ObjectVolumes {

    private final Map<Integer, Long> voxels = new HashMap<Integer, Long>();
    private final double voxelVolume;

    private ObjectVolumes(Map<Integer, Long> counts, double voxelVolume) {
        this.voxels.putAll(counts);
        this.voxelVolume = voxelVolume;
    }

    /**
     * @param calibration the run's calibration, or null to use the image's own.
     *        The run's wins when both exist: a calibration override is
     *        deliberately supplied to correct images whose stored metadata is
     *        wrong, so honouring the image would defeat the point of supplying it.
     */
    static ObjectVolumes of(ImagePlus labelImage, Calibration calibration) {
        Map<Integer, Long> counts = new HashMap<Integer, Long>();
        ImageStack stack = labelImage.getStack();
        for (int slice = 1; slice <= stack.getSize(); slice++) {
            ImageProcessor processor = stack.getProcessor(slice);
            int size = processor.getWidth() * processor.getHeight();
            for (int index = 0; index < size; index++) {
                int label = (int) processor.getf(index % processor.getWidth(),
                        index / processor.getWidth());
                if (label <= 0) {
                    continue;
                }
                Long seen = counts.get(Integer.valueOf(label));
                counts.put(Integer.valueOf(label),
                        Long.valueOf(seen == null ? 1L : seen.longValue() + 1L));
            }
        }
        Calibration effective = calibration != null
                ? calibration : labelImage.getCalibration();
        double volume = effective == null ? 1.0
                : effective.pixelWidth * effective.pixelHeight
                        * (effective.pixelDepth <= 0 ? 1.0 : effective.pixelDepth);
        return new ObjectVolumes(counts, volume <= 0.0 ? 1.0 : volume);
    }

    /** NaN for a label that is not in this channel, never 0 — 0 is a real size. */
    double calibratedVolume(int label) {
        Long count = voxels.get(Integer.valueOf(label));
        return count == null ? Double.NaN : count.longValue() * voxelVolume;
    }

    double voxelCount(int label) {
        Long count = voxels.get(Integer.valueOf(label));
        return count == null ? Double.NaN : count.longValue();
    }
}
