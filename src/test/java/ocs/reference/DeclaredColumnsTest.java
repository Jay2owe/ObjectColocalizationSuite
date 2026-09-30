package ocs.reference;

import ocs.OCS;
import ocs.OCSParameters;
import ocs.OCSResult;
import ocs.engine.ColocEngine;
import ocs.engine.ColumnSpec;
import ocs.engine.CurveSeries;
import ocs.engine.DirectionKey;
import ocs.engine.EngineRegistry;
import ocs.engine.EngineResult;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Every column an engine declares must be a column it actually produces.
 *
 * <p>{@code ColocEngine.columns()} is a promise made before the run: the dialog
 * shows it, the run record writes it, and a batch relies on it to guarantee that
 * every field contributes the same columns. Nothing checked it against what the
 * engines emit — so an engine could declare a column, compute the number, and
 * throw it away, and every existing test would still pass. Four of them did:
 * volumetric overlap, Jaccard/Dice, containment and bounding-box overlap between
 * them declared nine columns they never filled, including containment's two
 * facing percentages, whose own description says they are "the number a single
 * overlap percentage never shows".
 *
 * <p>The failure is quiet in the worst way. A declared-but-empty column is not a
 * crash and not a wrong number — it is an absence, and an absence looks exactly
 * like a method that had nothing to say.
 *
 * <h2>Where a column is allowed to live</h2>
 *
 * An {@code EngineResult} has four channels, and a declared column is satisfied
 * by whichever one suits its shape:
 *
 * <ul>
 *   <li>the <b>primary</b> column travels in {@code ObjectScore.value()}, or —
 *       for a curve-valued engine, whose primary column names the curve rather
 *       than a number — in the curve itself;</li>
 *   <li>a column declared with {@code ColumnSpec.partner(...)} travels in
 *       {@code ObjectScore.partnerLabel()};</li>
 *   <li>a per-object column travels as a <b>supporting</b> array;</li>
 *   <li>a field-wide column travels in the <b>whole-direction</b> map, and a
 *       swept-axis one among the <b>curve</b> scalars.</li>
 * </ul>
 *
 * <p>Anything else is a column that exists only in the declaration.
 */
public class DeclaredColumnsTest {

    @BeforeClass
    public static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    /**
     * The check itself, over every engine the registry ships, run on the
     * reference dataset so that each engine has real objects to describe.
     *
     * <p>Run on {@link ReferenceDataset#correlated()} because it is the only case
     * carrying intensity images, and an engine that cannot run produces no
     * directions and would pass this test vacuously.
     */
    @Test
    public void everyEngineFillsEveryColumnItDeclares() {
        ReferenceDataset.Case reference = ReferenceDataset.correlated();
        OCSResult result = OCS.run(OCSParameters.builder(reference.labelImages())
                .intensityImages(reference.intensityImages())
                .domain(reference.wholeField())
                .allMethods()
                .sourceName(reference.name())
                .build());

        EngineRegistry registry = EngineRegistry.createDefault();
        List<String> unfilled = new ArrayList<String>();
        int checked = 0;

        List<EngineResult> engineResults = result.engineResults();
        for (int i = 0; i < engineResults.size(); i++) {
            EngineResult engineResult = engineResults.get(i);
            ColocEngine engine = registry.byId(engineResult.engineId());
            List<DirectionKey> directions = engineResult.directions();
            if (directions.isEmpty()) {
                fail(engineResult.engineId() + " produced no directions at all, so "
                        + "this test would pass it without checking anything");
            }

            for (int d = 0; d < directions.size(); d++) {
                DirectionKey direction = directions.get(d);
                List<ColumnSpec> columns = engine.columns();
                for (int c = 0; c < columns.size(); c++) {
                    ColumnSpec column = columns.get(c);
                    checked++;
                    if (!isCarried(engineResult, direction, column)) {
                        unfilled.add(engine.id() + " declares '" + column.name()
                                + "' and fills nothing for it in "
                                + direction.label());
                    }
                }
            }
        }

        assertTrue("no engine's columns were checked; the fixture ran nothing",
                checked > 0);
        if (!unfilled.isEmpty()) {
            fail("declared columns that reach no output channel:\n  "
                    + join(unfilled));
        }
    }

    /**
     * The negative control for the check above. A column name no engine declares
     * must not be reported as carried, or the check would pass whatever an engine
     * declared.
     */
    @Test
    public void theCheckRejectsAColumnNothingProduces() {
        ReferenceDataset.Case reference = ReferenceDataset.partial();
        OCSResult result = OCS.run(OCSParameters.builder(reference.labelImages())
                .methods("volume-overlap")
                .sourceName(reference.name())
                .build());

        EngineResult overlap = result.resultFor("volume-overlap");
        ColumnSpec invented = ColumnSpec.of("Column That Does Not Exist", "",
                "invented by this test", ocs.engine.ScaleKind.UNBOUNDED);

        assertTrue("a column no engine emits must read as unfilled",
                !isCarried(overlap, overlap.directions().get(0), invented));
    }

    // ------------------------------------------------------------------

    /** Whether {@code column} reaches any of the result's four channels. */
    private static boolean isCarried(EngineResult result, DirectionKey direction,
            ColumnSpec column) {
        if (column.isPrimary()) {
            if (result.hasObjectScores()) {
                // ObjectScore.value() is the primary, by definition.
                return true;
            }
            if (result.hasCurves()) {
                // A curve engine's primary column names the curve rather than a
                // number: "Cross-K" is the measure, and its values are the
                // radii-indexed series inside. So the primary is satisfied by a
                // curve that exists and has points in it, and the named-column
                // rule below applies only to the scalars beside it.
                CurveSeries curve = result.curve(direction);
                return curve != null && curve.length() > 0;
            }
            return carriedByWholeDirection(result, direction, column);
        }
        if (column.isPartner()) {
            // ObjectScore.partnerLabel() carries exactly one partner per object,
            // so a partner column needs no separate array.
            return result.hasObjectScores();
        }
        return result.supportingNames(direction).contains(column.name())
                || carriedByWholeDirection(result, direction, column)
                || carriedByCurve(result, direction, column);
    }

    private static boolean carriedByWholeDirection(EngineResult result,
            DirectionKey direction, ColumnSpec column) {
        Map<String, Double> values = result.wholeDirection(direction);
        return values != null && values.containsKey(column.name());
    }

    private static boolean carriedByCurve(EngineResult result,
            DirectionKey direction, ColumnSpec column) {
        CurveSeries curve = result.curve(direction);
        if (curve == null) {
            return false;
        }
        return curve.hasSeries(column.name())
                || curve.scalars().containsKey(column.name())
                || column.name().equals(curve.xName());
    }

    private static String join(List<String> lines) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                joined.append("\n  ");
            }
            joined.append(lines.get(i));
        }
        return joined.toString();
    }
}
