package com.gpmanager;

import com.google.gson.Gson;
import java.lang.reflect.Proxy;
import java.util.*;
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static org.junit.Assert.*;

/** Charged TFA items have no feed row: their removable contents are valued separately. */
public class ChargedParentValuationTest {
    static final long OBLIGATOR_PRICE = 3_274_989_430L, TOME_PRICE = 55_400_000L;

    static Valuation valuation(String overrides, boolean alch, boolean refuseQuotes) {
        var config = new GpManagerConfig() {
            @Override public String manualPriceOverrides() { return overrides; }
        };
        var valuation = new Valuation(config, id -> (ItemComposition) Proxy.newProxyInstance(
                ItemComposition.class.getClassLoader(), new Class<?>[]{ItemComposition.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getName")) return "Charged equipment";
                    if (method.getName().equals("getHaPrice"))
                        return id == ItemID.ARCHIVISTS_FOLIO ? 300 : 2_400_000;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                }), (id, active) -> {
                    if (refuseQuotes) throw new AssertionError("An explicit override needs no market quote");
                    assertTrue(active);
                    return id == ItemID.OBLIGATOR_UNCHARGED ? OBLIGATOR_PRICE
                            : id == ItemID.ZORYAS_TOME_UNCHARGED ? TOME_PRICE
                            : id == ItemID.NATURERUNE ? 100 : 0;
                }, id -> false, Valuation.Economy.NORMAL);
        valuation.alch = alch;
        return valuation;
    }

    @Test public void zeroChargedQuotesUseTheirFullTradeableParents() {
        var valuation = valuation("", false, false);
        for (var entry : Map.of(ItemID.OBLIGATOR, OBLIGATOR_PRICE, ItemID.ZORYAS_TOME, TOME_PRICE).entrySet()) {
            Flow flow = valuation.value(Map.of(entry.getKey(), -2L)).get(0);
            assertEquals(entry.getValue().longValue(), flow.unitPrice);
            assertEquals(-2 * entry.getValue(), flow.value);
            assertEquals(PriceSource.CURRENCY_PROXY, flow.source());
        }
    }

    @Test public void directOverridesStayAuthoritativeWithoutMarketCalls() {
        var valuation = valuation("34715=3500000000,34725=60000000", false, true);
        assertEquals(3_500_000_000L, valuation.value(Map.of(ItemID.OBLIGATOR, 1L)).get(0).unitPrice);
        assertEquals(60_000_000L, valuation.value(Map.of(ItemID.ZORYAS_TOME, 1L)).get(0).unitPrice);
        assertEquals(PriceSource.MANUAL_OVERRIDE,
                valuation.value(Map.of(ItemID.ZORYAS_TOME, 1L)).get(0).source());
    }

    @Test public void ironmanAlchStillWinsAndFolioHasNoSaleProxy() {
        for (int id : new int[]{ItemID.OBLIGATOR, ItemID.ZORYAS_TOME}) {
            Flow flow = valuation("", true, false).value(Map.of(id, 1L)).get(0);
            assertEquals(2_400_000, flow.unitPrice);
            assertEquals(PriceSource.HIGH_ALCHEMY, flow.source());
        }
        assertNull(CurrencyProxyCatalogue.PROXIES.get(ItemID.ARCHIVISTS_FOLIO));
        Flow folio = valuation("", false, false).value(Map.of(ItemID.ARCHIVISTS_FOLIO, 1L)).get(0);
        assertEquals(300, folio.unitPrice);
        assertEquals(PriceSource.HIGH_ALCHEMY, folio.source());
    }

    @Test public void oldFrozenAlchRowsAreNotRepricedOnRestore() {
        JsonCodec.bind(new Gson());
        Engine engine = new Engine(valuation("", false, false), new TransactionClassifier(), ReviewFixesTest.CONFIG);
        engine.ensureSession(ReviewFixesTest.T0);
        var row = Tx.of(ReviewFixesTest.T0, TransactionType.GAIN, Context.GENERIC, "Old quote", true,
                List.of(new Flow(ItemID.OBLIGATOR, "Obligator", 1, 2_400_000, 2_400_000,
                        PriceSource.HIGH_ALCHEMY)));
        engine.session().addTransaction(row, 2000, false);
        engine.restore(engine.savedState(), ReviewFixesTest.T0 + 1000);
        Flow restored = engine.session().transactions().get(0).flows().get(0);
        assertEquals(2_400_000, restored.unitPrice);
        assertEquals(2_400_000, restored.value);
        assertEquals(PriceSource.HIGH_ALCHEMY, restored.source());
        assertEquals(110, engine.savedState().schemaVersion);
    }
}
