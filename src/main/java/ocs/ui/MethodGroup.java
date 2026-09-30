package ocs.ui;

import ocs.engine.ColocEngine;
import ocs.engine.EngineFamily;
import ocs.engine.EngineRegistry;
import ocs.engine.ThresholdBearing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Methods that ask the user for the same thing, shown as one table.
 *
 * <p>Thirteen methods listed as thirteen checkboxes with thirteen settings
 * beside them is the dialog that made the parent pipeline hard to adopt. But
 * they are not thirteen unrelated things: volume overlap, bounding-box overlap
 * and containment all want one number, a percentage, and mean roughly the same
 * thing by it. Put them in one table with a shared column header and the reader
 * sees three variants of one idea instead of three separate decisions.
 *
 * <p>Think of a hotel booking form. Room type, bed size and floor are not asked
 * as three unrelated questions on three screens; they sit in one block because
 * they are all "what kind of room". Same idea here.
 *
 * <p>A group is <b>presentation only</b>. Nothing downstream — the registry, the
 * null model, the agreement matrix — knows groups exist, so regrouping the
 * dialog can never change a number.
 */
public final class MethodGroup {

    private final String title;
    private final String subtitle;
    private final EngineFamily family;
    private final List<String> engineIds;
    private final String sharedColumn;
    private final String sharedColumnUnit;
    private final boolean advanced;

    MethodGroup(String title, String subtitle, EngineFamily family,
            List<String> engineIds, String sharedColumn, String sharedColumnUnit,
            boolean advanced) {
        this.title = title;
        this.subtitle = subtitle;
        this.family = family;
        this.engineIds = Collections.unmodifiableList(new ArrayList<String>(engineIds));
        this.sharedColumn = sharedColumn;
        this.sharedColumnUnit = sharedColumnUnit;
        this.advanced = advanced;
    }

    /** Section heading: {@code "Overlap-based"}. */
    public String title() {
        return title;
    }

    /** One line under the heading saying what the group answers. */
    public String subtitle() {
        return subtitle;
    }

    public EngineFamily family() {
        return family;
    }

    public List<String> engineIds() {
        return engineIds;
    }

    /**
     * The column header shared by every row, or null where the methods take no
     * setting.
     *
     * <p>Null rather than an empty string, so a group with nothing to configure
     * renders as a plain list rather than as a table with a blank column — an
     * empty column reads as a setting somebody forgot to fill in.
     */
    public String sharedColumn() {
        return sharedColumn;
    }

    /** {@code "%"}, {@code "µm"}, or empty. Never null when a column exists. */
    public String sharedColumnUnit() {
        return sharedColumnUnit;
    }

    public boolean hasSharedColumn() {
        return sharedColumn != null;
    }

    /**
     * Whether this group starts collapsed behind an "Advanced" disclosure.
     *
     * <p>Collapsed, never hidden: a method a user cannot find is a method that
     * does not exist to them, and the count in the section header is what tells
     * them there is something inside worth opening.
     */
    public boolean isAdvanced() {
        return advanced;
    }

    /**
     * The shipped layout, in the order the dialog draws it.
     *
     * <p>Cheap and broadly meaningful first, expensive and specialised last, so
     * a user reading top to bottom meets the methods they are most likely to
     * want before the ones that will cost them minutes.
     */
    public static List<MethodGroup> defaultLayout() {
        List<MethodGroup> groups = new ArrayList<MethodGroup>();

        groups.add(new MethodGroup("Object overlap",
                "How much of one object is inside another?",
                EngineFamily.OBJECT,
                Arrays.asList("volume-overlap", "bounding-box", "containment"),
                "Threshold", "%", false));

        groups.add(new MethodGroup("Object position",
                "Are the objects in the same place, without requiring overlap?",
                EngineFamily.OBJECT,
                Arrays.asList("cpc"), null, "", false));

        groups.add(new MethodGroup("Object shape agreement",
                "How similar are the two objects as shapes?",
                EngineFamily.OBJECT,
                Arrays.asList("jaccard-dice"), "Jaccard index", "", true));

        groups.add(new MethodGroup("Object proximity",
                "How close is the nearest partner?",
                EngineFamily.OBJECT,
                Arrays.asList("distance-tolerance"),
                "Tolerance", "calibrated units", true));

        groups.add(new MethodGroup("Intensity correlation",
                "Do the two signals rise and fall together?",
                EngineFamily.INTENSITY,
                Arrays.asList("per-object-intensity"), "Coincident above r", "", false));

        groups.add(new MethodGroup("Whole-image intensity",
                "The classic field-wide Pearson and Manders numbers.",
                EngineFamily.INTENSITY,
                Arrays.asList("whole-image-intensity"), null, "", true));

        groups.add(new MethodGroup("Spatial statistics",
                "Is the arrangement of one channel around the other more than chance? "
                        + "These share one set of radii, simulations and seed.",
                EngineFamily.SPATIAL,
                Arrays.asList("cross-g", "cross-k", "cross-l", "cross-pair-correlation"),
                null, "", true));

        groups.add(new MethodGroup("Territories",
                "Do one channel's objects sit inside the other's territory?",
                EngineFamily.TERRITORY,
                Arrays.asList("territory-occupancy"), null, "", true));

        return Collections.unmodifiableList(groups);
    }

    /**
     * Every registered engine appears in exactly one group.
     *
     * <p>Checked rather than assumed. A method added to the registry and
     * forgotten here would be silently unreachable from the dialog — present in
     * the macro API, absent from the interface, and impossible to explain.
     *
     * @throws IllegalStateException naming what is missing or duplicated
     */
    public static void requireCompleteCover(List<MethodGroup> groups,
            EngineRegistry registry) {
        List<String> covered = new ArrayList<String>();
        for (int i = 0; i < groups.size(); i++) {
            List<String> ids = groups.get(i).engineIds();
            for (int j = 0; j < ids.size(); j++) {
                if (covered.contains(ids.get(j))) {
                    throw new IllegalStateException("engine '" + ids.get(j)
                            + "' appears in more than one dialog group");
                }
                if (!registry.has(ids.get(j))) {
                    throw new IllegalStateException("dialog group '"
                            + groups.get(i).title() + "' names '" + ids.get(j)
                            + "', which is not a registered engine");
                }
                covered.add(ids.get(j));
            }
        }
        List<ColocEngine> all = registry.all();
        for (int i = 0; i < all.size(); i++) {
            if (!covered.contains(all.get(i).id())) {
                throw new IllegalStateException("engine '" + all.get(i).id()
                        + "' is registered but appears in no dialog group, so no "
                        + "user could ever switch it on");
            }
        }
    }

    /**
     * The setting each row starts at, read from the engine itself.
     *
     * <p>Read rather than restated, so the dialog cannot drift from the engine's
     * own default — the two being edited separately is how a macro recorded from
     * the interface starts producing different numbers from the interface.
     *
     * @return NaN for a row whose engine takes no setting
     */
    public static double defaultSettingOf(EngineRegistry registry, String engineId) {
        ColocEngine engine = registry.byId(engineId);
        if (engine instanceof ThresholdBearing) {
            return ((ThresholdBearing) engine).threshold();
        }
        return Double.NaN;
    }
}
