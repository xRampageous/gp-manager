package com.gpmanager;

import java.util.*;
import net.runelite.api.ItemComposition;
import org.junit.Test;
import static org.junit.Assert.*;

/** Recorded 20261024-022450: partial blighted bottles have no independent market quote. */
public class BlightedDoseTest {
    static final int[] RESTORE = {24605, 24603, 24601, 24598};
    static final int[] OVERLOAD = {29640, 29637, 29634, 29631};

    static Valuation service(int[] ids, String name, int price, String manual) {
        Map<Integer, String> names = new HashMap<>();
        for (int i = 0; i < ids.length; i++) names.put(ids[i], name + "(" + (i + 1) + ")");
        return ModelProbe.valuation(new GpManagerConfig() {
            @Override public String manualPriceOverrides() { return manual; }
        }, id -> ReviewFixesTest.proxy(ItemComposition.class,
                Map.of("getName", names.getOrDefault(id, "Item " + id), "getHaPrice", 24,
                        "isTradeable", true)), id -> id == ids[3] ? price : 0);
    }

    static List<Transaction> drinks(Valuation value, int[] ids) {
        var rows = new ArrayList<Transaction>();
        for (int i = 3; i >= 0; i--) {
            Map<Integer, Long> deltas = new LinkedHashMap<>();
            deltas.put(ids[i], -1L);
            if (i > 0) deltas.put(ids[i - 1], 1L);
            var raw = value.value(deltas, 1000);
            var normalized = value.normalizeConsumedFlows(raw, ActionKind.DRINK, 1000);
            assertNotNull("one dose must have an authoritative price", normalized);
            var row = new Transaction(1000 + i, TransactionType.CONSUMPTION, Context.GENERIC,
                    "", "PKing", true, normalized, Confidence.LIKELY, "recorded sip", null);
            row.setAction(ActionKind.DRINK);
            rows.add(row);
        }
        return rows;
    }

    @Test public void allFourRestoreDosesMergeAndCostOneBottle() {
        assertBottle(RESTORE, "Blighted super restore");
    }

    @Test public void allFourOverloadDosesUseTheirOwnFamily() {
        assertBottle(OVERLOAD, "Blighted overload");
    }

    static void assertBottle(int[] ids, String name) {
        var rows = drinks(service(ids, name, 2324, ""), ids);
        var result = SemanticProbe.capture(rows, List.of(), "", null);
        assertEquals(1, result.groups.size());
        assertEquals(4, result.groups.get(0).quantity);
        assertEquals(-2324, result.groups.get(0).value);
        for (var row : rows) {
            assertEquals(-581, row.flows().stream().mapToLong(flow -> flow.value).sum());
            for (var flow : row.flows()) assertEquals(PriceSource.GRAND_EXCHANGE, flow.source());
        }
        Session session = new Session("PKing", 0, SessionMode.GENERAL);
        rows.forEach(row -> session.addTransaction(row, 100, false));
        assertEquals(0, session.metrics(5000).income);
        assertEquals(2324, session.metrics(5000).supplies);
    }

    @Test public void manualFullQuoteRemainsAuthoritative() {
        var rows = drinks(service(RESTORE, "Blighted super restore", 2324, "24598=4000"), RESTORE);
        assertEquals(-4000, rows.stream().flatMap(row -> row.flows().stream()).mapToLong(flow -> flow.value).sum());
        for (var row : rows) for (var flow : row.flows()) assertEquals(PriceSource.MANUAL_OVERRIDE, flow.source());
    }

    @Test public void missingFamilyQuoteCannotTurnAlchFallbackIntoMarketAuthority() {
        var value = service(RESTORE, "Blighted super restore", 0, "");
        assertNull(value.normalizeConsumedFlows(value.value(Map.of(24598, -1L, 24601, 1L), 1000),
                ActionKind.DRINK, 1000));
    }

    @Test public void blightedAndOrdinaryRestoresNeverMerge() {
        var rows = drinks(service(RESTORE, "Blighted super restore", 2324, ""), RESTORE);
        int[] normal = {3030, 3028, 3026, 3024};
        // Ordinary restores have their own (1) authority, preserving their existing policy.
        var value = ModelProbe.valuation(new GpManagerConfig() {}, id -> ReviewFixesTest.proxy(ItemComposition.class,
                Map.of("getName", "Super restore(" + (Arrays.binarySearch(new int[]{3024,3026,3028,3030}, id) < 0 ? 1 :
                    (3030 - id) / 2 + 1) + ")", "isTradeable", true)), id -> (3030 - id) / 2 * 581 + 581);
        rows.addAll(drinks(value, normal));
        assertEquals(2, SemanticProbe.capture(rows, List.of(), "", null).groups.size());
    }
}
