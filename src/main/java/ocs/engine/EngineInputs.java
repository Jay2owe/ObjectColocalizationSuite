package ocs.engine;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.Calibration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything any engine might need, assembled once and handed to all of them.
 *
 * <p>This is the superset input contract the plan warns about: 2–5 label images,
 * optionally paired intensity images, optionally an ROI set defining the
 * randomization domain, plus calibration. Assembling it once and validating it
 * once — rather than each engine reading what it likes — is what stops the
 * superset of inputs becoming the superset of validation failures.
 *
 * <p>Engines must treat this as read-only. The same instance is handed to every
 * selected engine and, during a null-model run, reused across hundreds of
 * permutations; an engine that mutates a pixel array here corrupts every
 * subsequent method silently.
 */
public final class EngineInputs {

    /** Matches the input contract in FIRST_BUILD_PLAN.md. */
    public static final int MIN_CHANNELS = 2;
    public static final int MAX_CHANNELS = 5;

    private final List<ImagePlus> labelImages;
    private final List<ImagePlus> intensityImages;
    private final List<String> channelNames;
    private final List<Roi> domain;
    private final Calibration calibration;

    private EngineInputs(Builder builder) {
        this.labelImages = Collections.unmodifiableList(new ArrayList<ImagePlus>(builder.labelImages));
        this.intensityImages = Collections.unmodifiableList(new ArrayList<ImagePlus>(builder.intensityImages));
        this.channelNames = Collections.unmodifiableList(new ArrayList<String>(builder.channelNames));
        this.domain = Collections.unmodifiableList(new ArrayList<Roi>(builder.domain));
        this.calibration = builder.calibration;
    }

    public static Builder builder(List<ImagePlus> labelImages) {
        return new Builder(labelImages);
    }

    public List<ImagePlus> labelImages() {
        return labelImages;
    }

    /** Empty when no intensity images were loaded. */
    public List<ImagePlus> intensityImages() {
        return intensityImages;
    }

    public List<String> channelNames() {
        return channelNames;
    }

    /** Empty when no ROI region set was loaded. */
    public List<Roi> domain() {
        return domain;
    }

    /** Never null — falls back to an uncalibrated {@link Calibration}. */
    public Calibration calibration() {
        return calibration;
    }

    public int channelCount() {
        return labelImages.size();
    }

    public String channelName(int index) {
        return channelNames.get(index);
    }

    public boolean has(InputRequirement requirement) {
        switch (requirement) {
            case LABEL_IMAGES:
                return !labelImages.isEmpty();
            case INTENSITY_IMAGES:
                return intensityImages.size() == labelImages.size();
            case ROI_DOMAIN:
                return !domain.isEmpty();
            case CALIBRATION:
                return calibration.scaled();
            default:
                throw new IllegalArgumentException("unhandled requirement " + requirement);
        }
    }

    /**
     * Every requirement in the given set that this input does not satisfy.
     *
     * <p>Returned as a list rather than thrown so the dialog can grey out an
     * engine and say why, instead of letting the user press Run and then fail.
     */
    public List<InputRequirement> missing(Iterable<InputRequirement> required) {
        List<InputRequirement> missing = new ArrayList<InputRequirement>();
        for (InputRequirement requirement : required) {
            if (!has(requirement)) {
                missing.add(requirement);
            }
        }
        return missing;
    }

    /** Every ordered channel pair, which is what most engines iterate over. */
    public List<DirectionKey> allDirections() {
        List<DirectionKey> directions = new ArrayList<DirectionKey>();
        for (int source = 0; source < channelCount(); source++) {
            for (int target = 0; target < channelCount(); target++) {
                if (source != target) {
                    directions.add(new DirectionKey(
                            source, channelName(source), target, channelName(target)));
                }
            }
        }
        return directions;
    }

    public static final class Builder {

        private final List<ImagePlus> labelImages;
        private List<ImagePlus> intensityImages = new ArrayList<ImagePlus>();
        private List<String> channelNames = new ArrayList<String>();
        private List<Roi> domain = new ArrayList<Roi>();
        private Calibration calibration;

        private Builder(List<ImagePlus> labelImages) {
            if (labelImages == null) {
                throw new IllegalArgumentException("label images must not be null");
            }
            this.labelImages = new ArrayList<ImagePlus>(labelImages);
        }

        public Builder intensityImages(List<ImagePlus> images) {
            this.intensityImages = images == null
                    ? new ArrayList<ImagePlus>() : new ArrayList<ImagePlus>(images);
            return this;
        }

        public Builder channelNames(List<String> names) {
            this.channelNames = names == null
                    ? new ArrayList<String>() : new ArrayList<String>(names);
            return this;
        }

        public Builder domain(List<Roi> rois) {
            this.domain = rois == null ? new ArrayList<Roi>() : new ArrayList<Roi>(rois);
            return this;
        }

        public Builder calibration(Calibration calibration) {
            this.calibration = calibration;
            return this;
        }

        public EngineInputs build() {
            validate();
            return new EngineInputs(this);
        }

        private void validate() {
            int count = labelImages.size();
            if (count < MIN_CHANNELS || count > MAX_CHANNELS) {
                throw new IllegalArgumentException("expected " + MIN_CHANNELS + "–"
                        + MAX_CHANNELS + " label images, got " + count);
            }
            for (int i = 0; i < count; i++) {
                if (labelImages.get(i) == null) {
                    throw new IllegalArgumentException("label image " + (i + 1) + " is null");
                }
            }
            requireOneVolumePerLabelImage();
            requireMatchingGeometry();

            if (!intensityImages.isEmpty() && intensityImages.size() != count) {
                throw new IllegalArgumentException("expected " + count
                        + " intensity images to pair with the label images, got "
                        + intensityImages.size());
            }

            if (channelNames.isEmpty()) {
                for (int i = 0; i < count; i++) {
                    channelNames.add(defaultName(i));
                }
            } else if (channelNames.size() != count) {
                throw new IllegalArgumentException("expected " + count
                        + " channel names, got " + channelNames.size());
            }
            requireDistinctNames();

            if (calibration == null) {
                calibration = labelImages.get(0).getCalibration().copy();
            }
        }

        /**
         * Every engine indexes label and intensity images by the same voxel
         * coordinate. Mismatched dimensions would not throw — they would read
         * the wrong voxel and report a confident wrong number.
         */
        private void requireMatchingGeometry() {
            ImagePlus first = labelImages.get(0);
            int width = first.getWidth();
            int height = first.getHeight();
            int slices = first.getStackSize();

            List<ImagePlus> all = new ArrayList<ImagePlus>(labelImages);
            all.addAll(intensityImages);
            for (int i = 0; i < all.size(); i++) {
                ImagePlus image = all.get(i);
                if (image == null) {
                    throw new IllegalArgumentException("intensity image "
                            + (i - labelImages.size() + 1) + " is null");
                }
                if (image.getWidth() != width
                        || image.getHeight() != height
                        || image.getStackSize() != slices) {
                    throw new IllegalArgumentException("all images must share dimensions; "
                            + first.getTitle() + " is " + width + "×" + height + "×" + slices
                            + " but " + image.getTitle() + " is " + image.getWidth() + "×"
                            + image.getHeight() + "×" + image.getStackSize());
                }
            }
        }

        /**
         * A label image is one volume of object numbers.
         *
         * <p>An RGB image holds packed colour words, not object numbers, and a
         * multi-channel or time-lapse stack interleaves its planes, so reading
         * either as one volume assigns voxels to objects that do not exist.
         * Refused here, for every method, rather than by whichever engine
         * happens to check.
         */
        private void requireOneVolumePerLabelImage() {
            for (int i = 0; i < labelImages.size(); i++) {
                ImagePlus image = labelImages.get(i);
                String which = "label image " + (i + 1) + " ('" + image.getTitle() + "')";
                if (image.getType() == ImagePlus.COLOR_RGB) {
                    throw new IllegalArgumentException(which + " is an RGB colour"
                            + " image. A label image holds one object number per"
                            + " voxel (8-, 16- or 32-bit); convert or re-export it.");
                }
                if (image.getNChannels() > 1) {
                    throw new IllegalArgumentException(which + " has "
                            + image.getNChannels() + " channels. Give each channel"
                            + " as its own label image (Image > Color > Split"
                            + " Channels).");
                }
                if (image.getNFrames() > 1) {
                    throw new IllegalArgumentException(which + " is a time series"
                            + " with " + image.getNFrames() + " frames. Give one"
                            + " time point at a time (Image > Duplicate, one frame).");
                }
            }
        }

        /** Channel names key the output columns, so duplicates would collide. */
        private void requireDistinctNames() {
            for (int i = 0; i < channelNames.size(); i++) {
                String name = channelNames.get(i);
                if (name == null || name.trim().isEmpty()) {
                    channelNames.set(i, defaultName(i));
                    name = channelNames.get(i);
                }
                for (int j = 0; j < i; j++) {
                    if (name.equals(channelNames.get(j))) {
                        throw new IllegalArgumentException(
                                "channel names must be distinct, '" + name + "' appears twice");
                    }
                }
            }
        }

        private String defaultName(int index) {
            ImagePlus image = labelImages.get(index);
            String title = image.getTitle();
            return title == null || title.trim().isEmpty() ? "C" + (index + 1) : title;
        }
    }
}
