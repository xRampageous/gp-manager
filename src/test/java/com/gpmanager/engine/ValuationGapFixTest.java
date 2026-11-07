package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemMapping;
import org.junit.Test;
import static org.junit.Assert.*;

/** Resolve known own/base values without admitting upstream rate/coin quotes or guessed prices. */
public class ValuationGapFixTest {
    private static final long NOW = 1_700_000_000_000L;

    private static ItemComposition definition(int id, String name, int ha, boolean tradeable) {
        return (ItemComposition) Proxy.newProxyInstance(ItemComposition.class.getClassLoader(),
                new Class<?>[]{ItemComposition.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getId": return id;
                        case "getName": return name;
                        case "getHaPrice": return ha;
                        case "isTradeable": return tradeable;
                        default: return method.getReturnType() == boolean.class ? false
                                : method.getReturnType() == int.class ? 0 : null;
                    }
                });
    }

    private static Valuation service(Map<Integer, ItemComposition> definitions, Map<Integer, Long> quotes) {
        return new Valuation(new GpManagerConfig() {}, definitions::get,
                (id, active) -> quotes.getOrDefault(id, 0L),
                id -> Valuation.hasUnsupportedMapping(ItemMapping.map(id)), Valuation.Economy.NORMAL);
    }

    private static Flow value(Valuation service, int id) {
        return service.value(Map.of(id, 2L), NOW).get(0);
    }

    private static void guardedOwnValue(int ha) {
        int checked = 0;
        for (int id = 1; id < 65_536; id++) {
            if (!Valuation.hasUnsupportedMapping(ItemMapping.map(id))
                    || CurrencyProxyCatalogue.CURRENCIES.contains(id)) continue;
            int item = id;
            var service = new Valuation(new GpManagerConfig() {},
                    candidate -> candidate == item ? definition(item, "Known own value", ha, false) : null,
                    (candidate, active) -> { fail("upstream mapping must not price " + item); return 0; },
                    candidate -> Valuation.hasUnsupportedMapping(ItemMapping.map(candidate)), Valuation.Economy.NORMAL);
            var flow = value(service, item);
            assertEquals("own definition " + item, ha, flow.unitPrice);
            assertEquals(ha == 0 ? PriceSource.NO_VALUE : PriceSource.HIGH_ALCHEMY, flow.source());
            assertEquals(2L * ha, flow.value);
            checked++;
        }
        assertTrue("exercise all current rate/coin mapping families", checked >= 280);
    }

    @Test public void rateAndCoinMappingsCannotHideTheItemsOwnHa() { guardedOwnValue(17); }
    @Test public void rateAndCoinMappingsCannotTurnKnownOwnZeroIntoUnknown() { guardedOwnValue(0); }

    @Test public void anUndefinedGuardedItemRemainsUnknown() {
        var service = service(Map.of(), Map.of(ItemID.NATURERUNE, 100L));
        var row = value(service, ItemID.DORGESH_CONSTRUCTION_BONE);
        assertEquals(PriceSource.UNPRICED, row.source());
        assertEquals(2, row.quantity);
        assertEquals("Item " + ItemID.DORGESH_CONSTRUCTION_BONE, row.name);
    }

    @Test public void aTradeableGuardedItemWaitsForLoadedPricesBeforeOwnHaFallback() {
        int id = ItemID.BATTLE_TORTOISE_SHELL;
        var definitions = Map.of(id, definition(id, "Tortoise shell", 9, true));
        assertEquals(PriceSource.UNPRICED, value(service(definitions, Map.of()), id).source());
        var flow = value(service(definitions, Map.of(ItemID.NATURERUNE, 100L)), id);
        assertEquals(9, flow.unitPrice);
        assertEquals(PriceSource.HIGH_ALCHEMY, flow.source());
    }

    @Test public void salveIdentityKeepsItsParentsKnownZero() {
        for (int id : new int[] {12017, 25250, 26763, 12018, 25278, 26782}) {
            var service = service(Map.of(id, definition(id, "Imbued salve", 180, false),
                    4081, definition(4081, "Salve amulet", 0, false)), Map.of());
            var flow = value(service, id);
            assertEquals(0, flow.unitPrice);
            assertEquals(PriceSource.NO_VALUE, flow.source());
        }
    }

    @Test public void inertSailorsAmuletUsesItsKnownIdentityParentValue() {
        int id = ItemID.SAILORS_AMULET_EMPTY, parent = ItemID.SAILORS_AMULET;
        var service = service(Map.of(id, definition(id, "Sailors' amulet (inert)", 210, false),
                parent, definition(parent, "Sailors' amulet", 210, false)), Map.of());
        var flow = value(service, id);
        assertEquals(210, flow.unitPrice);
        assertEquals(420, flow.value);
        assertEquals(PriceSource.CURRENCY_PROXY, flow.source());
    }

    @Test public void aMissingIdentityParentDoesNotBecomeZero() {
        int id = ItemID.SAILORS_AMULET_EMPTY;
        var flow = value(service(Map.of(id, definition(id, "Sailors' amulet (inert)", 210, false)), Map.of()), id);
        assertEquals(PriceSource.UNPRICED, flow.source());
    }

    @Test public void identityFallbackCannotRecurseThroughAnotherProxy() {
        int id = ItemID.SAILORS_AMULET_EMPTY;
        int[] original = CurrencyProxyCatalogue.PROXIES.put(id, new int[] {id, 1});
        try {
            var flow = value(service(Map.of(id, definition(id, "Sailors' amulet", 210, false)), Map.of()), id);
            assertEquals(PriceSource.UNPRICED, flow.source());
        } finally {
            CurrencyProxyCatalogue.PROXIES.put(id, original);
        }
    }

    @Test public void manualAndIronmanPrioritiesStayAheadOfIdentityFallback() {
        int id = ItemID.SAILORS_AMULET_EMPTY, parent = ItemID.SAILORS_AMULET;
        var definitions = Map.of(id, definition(id, "Inert amulet", 210, false),
                parent, definition(parent, "Charged amulet", 17, false));
        var manual = new Valuation(new GpManagerConfig() {
            @Override public String manualPriceOverrides() { return id + "=999"; }
        }, definitions::get, (candidate, active) -> { fail("manual override must bypass quotes"); return 0; },
                candidate -> false, Valuation.Economy.NORMAL);
        assertEquals(999, value(manual, id).unitPrice);
        assertEquals(PriceSource.MANUAL_OVERRIDE, value(manual, id).source());
        var iron = service(definitions, Map.of());
        iron.alch = true;
        assertEquals(210, value(iron, id).unitPrice);
        assertEquals(PriceSource.HIGH_ALCHEMY, value(iron, id).source());
    }

    @Test public void verifiedCurrencyFallbacksUseOwnHaOrKnownZero() {
        for (var entry : Map.of(23964, 600, 4067, 0, 21656, 0, 23866, 0, 22820, 30).entrySet()) {
            int id = entry.getKey(), ha = entry.getValue();
            var flow = value(service(Map.of(id, definition(id, "Known currency", ha, false)), Map.of()), id);
            assertEquals("own fallback " + id, ha, flow.unitPrice);
            assertEquals(ha > 0 ? PriceSource.HIGH_ALCHEMY : PriceSource.NO_VALUE, flow.source());
        }
    }

    @Test public void theFiveExistingCurrencyRatesAndZeroExceptionsAreUnchanged() {
        for (var entry : Map.of(26792, 30, 25527, 30, 21341, 30, 12012, 60, 24711, 2880).entrySet()) {
            int id = entry.getKey();
            assertEquals(entry.getValue().longValue(),
                    value(service(Map.of(id, definition(id, "Existing currency", entry.getValue(), false)), Map.of()), id).unitPrice);
        }
        for (int id : Valuation.WORTHLESS) {
            var flow = value(service(Map.of(id, definition(id, "Existing zero exception", 60_000, false)), Map.of()), id);
            assertEquals(PriceSource.NO_VALUE, flow.source());
            assertEquals(0, flow.value);
        }
    }

    @Test public void aQuotedSubGpProxyIsKnownAtTheExistingWholeGpFloor() {
        var service = service(Map.of(ItemID.SPIRIT_FLAKES,
                definition(ItemID.SPIRIT_FLAKES, "Spirit flakes", 1, false)), Map.of(ItemID.SOAKED_PAGE, 239L));
        var flow = value(service, ItemID.SPIRIT_FLAKES);
        assertEquals(PriceSource.NO_VALUE, flow.source());
        assertEquals("keep the current whole-gp unit rounding", 0, flow.unitPrice);
        assertEquals(0, flow.value);
        assertEquals(2, flow.quantity);
        assertEquals(0, flow.pricedAt());
    }

    @Test public void aGenuinelyMissingProxyQuoteStaysUnknown() {
        var service = service(Map.of(ItemID.SPIRIT_FLAKES,
                definition(ItemID.SPIRIT_FLAKES, "Spirit flakes", 1, false)), Map.of());
        assertEquals(PriceSource.UNPRICED, value(service, ItemID.SPIRIT_FLAKES).source());
    }

    @Test public void aKnownZeroProxyPickupRetainsItsItemReceipt() {
        var service = service(Map.of(ItemID.SPIRIT_FLAKES,
                definition(ItemID.SPIRIT_FLAKES, "Spirit flakes", 1, false)), Map.of(ItemID.SOAKED_PAGE, 239L));
        var engine = new Engine(service, new TransactionClassifier(), new GpManagerConfig() {
            @Override public int stabilizationTicks() { return 0; }
        });
        JsonCodec.bind(new com.google.gson.Gson());
        engine.startCustom("Zero proxy", NOW);
        engine.setBaseline(new ContainerSnapshot(Map.of()));
        var next = new ContainerSnapshot(Map.of(ItemID.SPIRIT_FLAKES, 2L));
        engine.processIfDirty(next, NOW + 3000, null);
        engine.processIfDirty(next, NOW + 3600, null);
        assertEquals(1, engine.session().transactions().size());
        var row = engine.session().transactions().get(0);
        assertEquals(2, row.quantity(ItemID.SPIRIT_FLAKES, true));
        assertFalse(ReviewRules.needsReview(row));
        assertEquals(0, engine.metrics(NOW + 4000).net);
    }

    @Test public void spoilsOfWarIsADeferredContainerEvenWithAlchemyAndOverride() {
        int id = ItemID.SOUL_WARS_SPOILS;
        var service = new Valuation(new GpManagerConfig() {
            @Override public String manualPriceOverrides() { return id + "=999"; }
        }, candidate -> definition(candidate, "Spoils of War", 100, false),
                (candidate, active) -> { fail("a deferred container never queries market"); return 0; },
                candidate -> false, Valuation.Economy.NORMAL);
        service.alch = true;
        assertEquals(PriceSource.DEFERRED_CLAIM, value(service, id).source());
        assertEquals(0, value(service, id).value);
    }

    @Test public void openingSpoilsCountsOnlyItsActualContents() {
        int id = ItemID.SOUL_WARS_SPOILS;
        var service = service(Map.of(id, definition(id, "Spoils of War", 0, false)), Map.of());
        var engine = new Engine(service, new TransactionClassifier(), new GpManagerConfig() {
            @Override public int stabilizationTicks() { return 0; }
        });
        JsonCodec.bind(new com.google.gson.Gson());
        engine.startCustom("Soul Wars", NOW);
        engine.setBaseline(new ContainerSnapshot(Map.of(id, 1L)));
        var next = new ContainerSnapshot(Map.of(ItemID.COINS, 1250L));
        engine.processIfDirty(next, NOW + 3000, null);
        engine.processIfDirty(next, NOW + 3600, null);
        var rows = engine.session().transactions();
        assertEquals(1, rows.size());
        assertFalse(ReviewRules.needsReview(rows.get(0)));
        assertEquals(1250, engine.metrics(NOW + 4000).net);
    }
}
