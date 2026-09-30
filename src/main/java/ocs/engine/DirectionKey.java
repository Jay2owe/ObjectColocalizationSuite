package ocs.engine;

/**
 * One ordered source-to-target channel pair.
 *
 * <p>Direction matters and is not a detail. "80% of microglia touch a plaque" and
 * "3% of plaques are touched by a microglion" are the same overlap measured from
 * opposite ends, and reporting one when the reader assumed the other is the most
 * common way an object-colocalization number gets misread. Every result is
 * therefore keyed by an ordered pair, never by an unordered one.
 *
 * <p>Symmetric measures — Pearson's r, Jaccard — still produce a direction; they
 * simply produce the same number in both. That costs one redundant column and
 * removes a whole class of ambiguity from the output table.
 */
public final class DirectionKey {

    private final int sourceIndex;
    private final int targetIndex;
    private final String sourceName;
    private final String targetName;

    public DirectionKey(int sourceIndex, String sourceName, int targetIndex, String targetName) {
        if (sourceIndex < 0 || targetIndex < 0) {
            throw new IllegalArgumentException("channel indices must not be negative");
        }
        if (sourceIndex == targetIndex) {
            throw new IllegalArgumentException(
                    "source and target must differ, both were " + sourceIndex);
        }
        this.sourceIndex = sourceIndex;
        this.targetIndex = targetIndex;
        this.sourceName = sourceName == null ? "C" + (sourceIndex + 1) : sourceName;
        this.targetName = targetName == null ? "C" + (targetIndex + 1) : targetName;
    }

    public int sourceIndex() {
        return sourceIndex;
    }

    public int targetIndex() {
        return targetIndex;
    }

    public String sourceName() {
        return sourceName;
    }

    public String targetName() {
        return targetName;
    }

    /** Human-readable form used in table cells and log lines. */
    public String label() {
        return sourceName + " → " + targetName;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DirectionKey)) {
            return false;
        }
        DirectionKey that = (DirectionKey) other;
        return sourceIndex == that.sourceIndex && targetIndex == that.targetIndex;
    }

    @Override
    public int hashCode() {
        return 31 * sourceIndex + targetIndex;
    }

    @Override
    public String toString() {
        return "DirectionKey[" + label() + "]";
    }
}
