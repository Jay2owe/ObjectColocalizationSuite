package ocs.engine;

/**
 * What an engine needs before it can run.
 *
 * <p>Declared by every engine so the dialog can grey out methods the loaded data
 * cannot support, and so a run can be rejected before it starts rather than
 * failing halfway through a batch.
 */
public enum InputRequirement {

    /** Label images, one per channel. Every engine needs these. */
    LABEL_IMAGES("label images"),

    /** Raw intensity images paired to the label images, as in plugin 03. */
    INTENSITY_IMAGES("intensity images"),

    /**
     * An ROI set defining the randomization domain.
     *
     * <p>Required by the spatial-statistics engines, which need a window for
     * edge correction, and by any engine asked to produce a null model — a
     * permutation without a domain to permute within is meaningless.
     */
    ROI_DOMAIN("an ROI region set"),

    /** Voxel calibration. Anything reporting a distance, volume or radius. */
    CALIBRATION("voxel calibration");

    private final String displayName;

    InputRequirement(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
