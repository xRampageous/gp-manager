package com.gpmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.ItemComposition;
import org.junit.Test;

import static org.junit.Assert.*;

/** Known alch-priced sips keep the selected policy without concealing unsupported pairs. */
public class HighAlchSipReviewTest {
    static final Map<Integer, String> NAMES = Map.of(2434, "Prayer potion(4)", 139,
            "Prayer potion(3)", 141, "Prayer potion(2)", 143, "Prayer potion(1)");

    static Valuation service(boolean alch, Map<Integer, Integer> values) {
        var service = ModelProbe.valuation(new GpManagerConfig() {}, id -> ReviewFixesTest.proxy(
                ItemComposition.class, Map.of("getName", NAMES.getOrDefault(id, "Item " + id),
                        "getHaPrice", values.getOrDefault(id, 0), "isTradeable", true)), id -> 1000);
        service.alch = alch;
        return service;
    }

    static List<Flow> pair(long quantity, int loss, int rest, PriceSource source) {
        return List.of(new Flow(2434, "Prayer potion(4)", -quantity, loss,
                        SafeMath.multiply(-quantity, loss), source),
                new Flow(139, "Prayer potion(3)", quantity, rest,
                        SafeMath.multiply(quantity, rest), source));
    }

    @Test public void alchSipRetainsCapturedQuotesAndLeavesReview() {
        // The bottle's base value makes these non-proportional: a sip loses 23, not the (1)'s 45.
        var values = new HashMap<>(Map.of(2434, 114, 139, 91, 141, 68, 143, 45));
        var service = service(true, values);
        var engine = new Engine(service, new TransactionClassifier(), new GpManagerConfig() {});
        engine.ensureSession(1000);
        engine.setBaseline(new ContainerSnapshot(Map.of(2434, 1L)));
        engine.noteConsume(2434, 6, false, ActionKind.DRINK, null);
        var row = ItemValuationServiceTest.settleDrink(engine, Map.of(139, 1L), 2000);

        assertEquals(TransactionType.CONSUMPTION, row.type());
        assertEquals(ActionKind.DRINK, row.action());
        assertTrue(row.counts());
        assertTrue(engine.getReviewRows(4000).isEmpty());
        assertEquals(-23, engine.metrics(4000).net);
        for (var flow : row.flows()) {
            assertEquals(PriceSource.HIGH_ALCHEMY, flow.source());
            assertEquals(values.get(flow.item).intValue(), flow.unitPrice);
        }
        values.replaceAll((id, price) -> price * 2);
        assertEquals("a later quote cannot reprice the sip", -23, engine.metrics(5000).net);
        var codec = new com.google.gson.Gson();
        var restored = codec.fromJson(codec.toJson(engine.session()), Session.class);
        assertEquals("reload keeps the captured difference", -23, restored.metrics(5000).net);
    }

    @Test public void normalModeRetainsUnsupportedAlchFallback() {
        assertNull(service(false, Map.of()).normalizeConsumedFlows(
                pair(1, 400, 300, PriceSource.HIGH_ALCHEMY), ActionKind.DRINK, 1000));
    }

    @Test public void reversedAlchPairStaysReview() {
        assertNull(service(true, Map.of()).normalizeConsumedFlows(
                pair(1, 300, 400, PriceSource.HIGH_ALCHEMY), ActionKind.DRINK, 1000));
    }

    @Test public void overflowingQuantitiesStayReview() {
        assertNull(service(true, Map.of()).normalizeConsumedFlows(
                pair(Long.MAX_VALUE, 400, 300, PriceSource.HIGH_ALCHEMY), ActionKind.DRINK, 1000));
    }

    @Test public void mixedSourcesStayReview() {
        var raw = new ArrayList<>(pair(1, 400, 300, PriceSource.HIGH_ALCHEMY));
        raw.set(1, new Flow(139, "Prayer potion(3)", 1, 300, 300, PriceSource.GRAND_EXCHANGE));
        assertNull(service(true, Map.of()).normalizeConsumedFlows(raw, ActionKind.DRINK, 1000));
    }

    @Test public void equalAlchPricesRemainZeroUnderTheSelectedPolicy() {
        var result = service(true, Map.of()).normalizeConsumedFlows(
                pair(1, 400, 400, PriceSource.HIGH_ALCHEMY), ActionKind.DRINK, 1000);
        assertNotNull(result);
        assertEquals(0, result.stream().mapToLong(flow -> flow.value).sum());
    }
}
