package com.gpmanager.engine;

import com.gpmanager.GpManagerConfig;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TransactionType;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.ClassificationConfidence;
import com.gpmanager.ui.ledger.LedgerItemContribution;
import java.util.Collections;
import java.lang.reflect.Proxy;
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static org.junit.Assert.*;

public class ItemValuationServiceTest
{
    @Test public void freshProfilesKeepUnpricedQuantitiesForReview()
    {
        assertFalse(new GpManagerConfig() { }.ignoreUnpricedItems());
    }

    @Test public void manualOverrideSurvivesUnavailableDefinitionWithoutReadingMarket()
    {
        GpManagerConfig config = new GpManagerConfig() {
            public String manualPriceOverrides() { return "1942=50"; }
        };
        ItemValuationService service = new ItemValuationService(config,
            id -> { throw new IllegalStateException("Definition not ready"); },
            id -> { fail("Manual override must not query market"); return 0; });
        ItemFlow flow = service.value(Collections.singletonMap(1942, 2L)).get(0);
        assertEquals(100, flow.getValueDelta());
        assertEquals(ItemPriceSource.MANUAL_OVERRIDE, flow.getPriceSource());
    }

    @Test public void priceRefreshAffectsNewFlowsOnlyAndUnknownPricesStayReviewable()
    {
        GpManagerConfig config = new GpManagerConfig() {
            public boolean ignoreUnpricedItems() { return false; }
        };
        int[] price = {50};
        ItemValuationService service = new ItemValuationService(config, id -> null, id -> price[0]);
        ItemFlow first = service.value(Collections.singletonMap(1942, 2L)).get(0);
        price[0] = 70;
        assertEquals(140, service.value(Collections.singletonMap(1942, 2L)).get(0).getValueDelta());
        assertEquals(100, first.getValueDelta());
        price[0] = 0;
        assertEquals(ItemPriceSource.UNPRICED,
            service.value(Collections.singletonMap(1942, 2L)).get(0).getPriceSource());
    }

    @Test public void explicitValuationTimeIsAttachedOnlyWhenPriceEvidenceExists()
    {
        GpManagerConfig config = new GpManagerConfig() {
            public boolean ignoreUnpricedItems() { return false; }
        };
        ItemValuationService service = new ItemValuationService(config, id -> null,
            id -> id == 1942 ? 75 : 0);

        ItemFlow captured = service.value(Collections.singletonMap(1942, 2L), 1_725_000_000_123L).get(0);
        assertEquals(1_725_000_000_123L, captured.getPriceCapturedAtEpochMillis());
        assertEquals(150L, captured.getValueDelta());

        ItemFlow legacy = service.value(Collections.singletonMap(1942, 2L)).get(0);
        assertEquals("Legacy valuation calls do not invent a capture time", 0L,
            legacy.getPriceCapturedAtEpochMillis());

        ItemFlow unpriced = service.value(Collections.singletonMap(995, 2L), 1_725_000_000_123L).get(0);
        assertEquals(ItemPriceSource.UNPRICED, unpriced.getPriceSource());
        assertEquals("A timestamp is not price evidence", 0L, unpriced.getPriceCapturedAtEpochMillis());
    }

    @Test public void wealthQuoteUsesReceiptPriceButIgnoresAccountingIgnoreIds()
    {
        GpManagerConfig config = new GpManagerConfig() {
            @Override public String ignoredItemIds() { return "1942"; }
            @Override public boolean ignoreUnpricedItems() { return false; }
        };
        ItemValuationService service = new ItemValuationService(config, id -> null,
            id -> id == 1942 ? 75 : 0);

        ItemFlow quote = service.quoteForWealth(1942, 1234L);
        assertNotNull(quote);
        assertEquals(75, quote.getUnitPrice());
        assertEquals(ItemPriceSource.GRAND_EXCHANGE, quote.getPriceSource());
        assertEquals(1234L, quote.getPriceCapturedAtEpochMillis());
    }

    @Test public void legacyFlowValuatorCanIgnoreTimestampOverload()
    {
        FlowValuator legacy = deltas -> Collections.singletonList(
            new ItemFlow(1942, "Item", deltas.get(1942), 1, deltas.get(1942)));

        ItemFlow flow = legacy.value(Collections.singletonMap(1942, 1L), 1_725_000_000_123L).get(0);
        assertEquals(0L, flow.getPriceCapturedAtEpochMillis());
    }

    @Test public void coinAnchoredProxyStaysUnpricedWithoutManualOverride()
    {
        GpManagerConfig config = new GpManagerConfig() {
            public boolean useCurrencyProxies() { return true; }
            public boolean ignoreUnpricedItems() { return false; }
        };
        ItemValuationService service = new ItemValuationService(config, id -> null, id -> 999);
        ItemFlow tear = service.value(Collections.singletonMap(21649, 2L)).get(0);
        assertEquals(ItemPriceSource.UNPRICED, tear.getPriceSource());
        assertEquals(0L, tear.getValueDelta());
    }

    @Test public void currencyProxyValuesTokkulViaCounterpart()
    {
        GpManagerConfig config = new GpManagerConfig() {
            public boolean useCurrencyProxies() { return true; }
            public boolean ignoreUnpricedItems() { return false; }
        };
        // Tokkul (6529) → onyx (6573) at 300_000 tokkul per onyx; onyx GE 3_000_000 → 10 gp/tokkul
        ItemValuationService service = new ItemValuationService(config, id -> null, id -> {
            if (id == 6573) return 3_000_000;
            return 0;
        });
        ItemFlow flow = service.value(Collections.singletonMap(6529, 300L)).get(0);
        assertEquals(ItemPriceSource.CURRENCY_PROXY, flow.getPriceSource());
        assertEquals(10, flow.getUnitPrice());
        assertEquals(3_000L, flow.getValueDelta());
    }

    @Test public void manualOverrideBeatsCurrencyProxy()
    {
        GpManagerConfig config = new GpManagerConfig() {
            public boolean useCurrencyProxies() { return true; }
            public String manualPriceOverrides() { return "6529=7"; }
        };
        ItemValuationService service = new ItemValuationService(config, id -> null, id -> 3_000_000);
        ItemFlow flow = service.value(Collections.singletonMap(6529, 2L)).get(0);
        assertEquals(ItemPriceSource.MANUAL_OVERRIDE, flow.getPriceSource());
        assertEquals(14, flow.getValueDelta());
    }

    @Test public void untradeableBrimstoneKeyIsDeferredAtZeroEvenWithAlchemyAndOverride()
    {
        GpManagerConfig config = new GpManagerConfig() {
            public boolean useHighAlchemyFallback() { return true; }
            public boolean ignoreUnpricedItems() { return true; }
            public String manualPriceOverrides() { return ItemID.KONAR_KEY + "=900000"; }
        };
        ItemValuationService service = new ItemValuationService(config,
            id -> composition("Brimstone key", 48_000),
            id -> { fail("Deferred keys must bypass GE and HA pricing"); return 0; });

        ItemFlow flow = service.value(Collections.singletonMap(ItemID.KONAR_KEY, 1L)).get(0);
        assertEquals(0, flow.getUnitPrice());
        assertEquals(0L, flow.getValueDelta());
        assertEquals(ItemPriceSource.DEFERRED_CLAIM, flow.getPriceSource());
        assertEquals("Claim on open", flow.getPriceSource().toString());

        ProfitTransaction transaction = new ProfitTransaction(1L, null, TransactionType.TRANSFER,
            TrackingContext.TRANSFER, "Key held · Brimstone chest", "Brimstone chest", false,
            Collections.singletonList(flow), ClassificationConfidence.CONFIRMED, "", null);
        LedgerItemContribution contribution = LedgerItemContribution.from(transaction, flow, 0);
        assertNotNull(contribution);
        assertFalse("Deferred claim is not an unknown/unpriced review edge", contribution.isUnpriced());
        assertFalse("Confirmed deferred claim should not be flagged for review", contribution.isNeedsReview());
    }

    @Test public void tradeableCrystalKeyKeepsGeOpportunityCost()
    {
        GpManagerConfig config = new GpManagerConfig() {
            public boolean useHighAlchemyFallback() { return true; }
        };
        ItemValuationService service = new ItemValuationService(config,
            id -> composition("Crystal key", 10), id -> 18_000);
        ItemFlow flow = service.value(Collections.singletonMap(ItemID.CRYSTAL_KEY, -1L)).get(0);
        assertEquals(-18_000L, flow.getValueDelta());
        assertEquals(ItemPriceSource.GRAND_EXCHANGE, flow.getPriceSource());
    }

    private static ItemComposition composition(String name, int highAlchemy)
    {
        return (ItemComposition) Proxy.newProxyInstance(ItemComposition.class.getClassLoader(),
            new Class<?>[] {ItemComposition.class}, (proxy, method, args) -> {
                if ("getName".equals(method.getName())) return name;
                if ("getHaPrice".equals(method.getName())) return highAlchemy;
                Class<?> type = method.getReturnType();
                if (type == boolean.class) return false;
                if (type == byte.class) return (byte) 0;
                if (type == short.class) return (short) 0;
                if (type == int.class) return 0;
                if (type == long.class) return 0L;
                if (type == float.class) return 0F;
                if (type == double.class) return 0D;
                if (type == char.class) return '\0';
                return null;
            });
    }
}
