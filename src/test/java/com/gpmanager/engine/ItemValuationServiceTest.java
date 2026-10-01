package com.gpmanager;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Proxy;
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static org.junit.Assert.*;

public class ItemValuationServiceTest
{
    /** Owner 2026-09-28: each confirmed Drink sip costs one (1) potion. */
    @Test public void everySipCostsOneDoseAtTheOneDosePrice()
    {
        java.util.Map<Integer, String> names = new java.util.HashMap<>();
        names.put(2434, "Prayer potion(4)");
        names.put(139, "Prayer potion(3)");
        names.put(141, "Prayer potion(2)");
        names.put(143, "Prayer potion(1)");
        java.util.Map<Integer, Integer> prices = new java.util.HashMap<>();
        prices.put(2434, 3_600);
        prices.put(139, 2_600);
        prices.put(141, 1_900);
        prices.put(143, 1_000);
        Bl service = new Bl(new GpManagerConfig() { },
            id -> composition(names.getOrDefault(id, "Item " + id), 0), id -> prices.getOrDefault(id, 0));
        int[][] steps = {{2434, 139}, {139, 141}, {141, 143}};
        for (int[] step : steps)
        {
            java.util.Map<Integer, Long> sip = new java.util.HashMap<>();
            sip.put(step[0], -1L);
            sip.put(step[1], 1L);
            long net = 0L;
            for (Ab flow : service.normalizeConsumedFlows(
                service.value(sip, 1_725_000_000_123L), Au.DRINK, 1_725_000_000_123L))
            {
                net += flow.valueDelta;
            }
            assertEquals("a sip from " + names.get(step[0]) + " costs one (1)", -1_000L, net);
        }
        assertEquals("a (4) that is not drunk keeps its own quote", 3_600L,
            service.value(Collections.singletonMap(2434, 1L)).get(0).valueDelta);
    }

    @Test public void valueRemainsQuoteOnlyForDecantShapes()
    {
        Bl service = potionService(new GpManagerConfig() { });
        Map<Integer, Long> decant = new LinkedHashMap<>();
        decant.put(2434, -1L);
        decant.put(139, 1L);
        decant.put(143, 1L);

        List<Ab> raw = service.value(decant, 1_725_000_000_123L);
        assertEquals(-3_600L, flow(raw, 2434).valueDelta);
        assertEquals(2_600L, flow(raw, 139).valueDelta);
        assertEquals(1_000L, flow(raw, 143).valueDelta);

        List<Ab> afterDrinkGate = service.normalizeConsumedFlows(raw, Au.DRINK,
            1_725_000_000_123L);
        assertEquals("dose-conserving decants stay at their captured quotes", -3_600L,
            flow(afterDrinkGate, 2434).valueDelta);
        assertEquals(2_600L, flow(afterDrinkGate, 139).valueDelta);
        assertEquals(1_000L, flow(afterDrinkGate, 143).valueDelta);
    }

    @Test public void manualOneDoseAuthorityAppliesToEveryPricedPairLeg()
    {
        GpManagerConfig config = new GpManagerConfig() {
            @Override public String manualPriceOverrides() { return "143=777,139=2331"; }
        };
        Bl service = potionService(config);
        Map<Integer, Long> sip = new LinkedHashMap<>();
        sip.put(2434, -1L);
        sip.put(139, 1L);

        List<Ab> normalized = service.normalizeConsumedFlows(
            service.value(sip, 1_725_000_000_123L), Au.DRINK, 1_725_000_000_123L);
        assertEquals(-3_108L, flow(normalized, 2434).valueDelta);
        assertEquals(2_331L, flow(normalized, 139).valueDelta);
        assertEquals(Av.MANUAL_OVERRIDE, flow(normalized, 2434).getPriceSource());
        assertEquals(Av.MANUAL_OVERRIDE, flow(normalized, 139).getPriceSource());
        assertEquals(-777L, net(normalized));
    }

    @Test public void conflictingManualVariantDoesNotNormalizeOnePairLeg()
    {
        GpManagerConfig config = new GpManagerConfig() {
            @Override public String manualPriceOverrides() { return "139=5000"; }
        };
        Bl service = potionService(config);
        Map<Integer, Long> sip = new LinkedHashMap<>();
        sip.put(2434, -1L);
        sip.put(139, 1L);

        List<Ab> raw = service.value(sip, 1_725_000_000_123L);
        List<Ab> normalized = service.normalizeConsumedFlows(raw, Au.DRINK,
            1_725_000_000_123L);
        assertNull("mixed provenance routes the original receipt to Review", normalized);
        assertEquals("mixed GE/manual pair keeps the GE loss", -3_600L,
            flow(raw, 2434).valueDelta);
        assertEquals("mixed GE/manual pair keeps the manual gain", 5_000L,
            flow(raw, 139).valueDelta);
        assertEquals(Av.GRAND_EXCHANGE, flow(raw, 2434).getPriceSource());
        assertEquals(Av.MANUAL_OVERRIDE, flow(raw, 139).getPriceSource());
    }

    @Test public void unavailableOneDoseQuoteLeavesCapturedPairUnchanged()
    {
        Bl service = potionService(new GpManagerConfig() { }, 0);
        Map<Integer, Long> sip = new LinkedHashMap<>();
        sip.put(2434, -1L);
        sip.put(139, 1L);

        List<Ab> raw = service.value(sip, 1_725_000_000_123L);
        List<Ab> normalized = service.normalizeConsumedFlows(raw, Au.DRINK,
            1_725_000_000_123L);
        assertNull("missing authority routes the original receipt to Review", normalized);
        assertEquals("a missing (1) must not become a fabricated zero price", -3_600L,
            flow(raw, 2434).valueDelta);
        assertEquals(2_600L, flow(raw, 139).valueDelta);
        assertEquals(Av.GRAND_EXCHANGE, flow(raw, 2434).getPriceSource());
        assertEquals(Av.GRAND_EXCHANGE, flow(raw, 139).getPriceSource());
    }

    @Test public void frozenTearsPriceFromTheirLiveMarketQuote()
    {
        Bl service = new Bl(new GpManagerConfig() { },
            id -> composition(id == ItemID.FROZEN_TEAR ? "Frozen tear" : "Item " + id, 0),
            id -> id == ItemID.FROZEN_TEAR ? 272 : 0);
        List<Ab> raw = service.value(
            Collections.singletonMap(ItemID.FROZEN_TEAR, -3L), 1_725_000_000_123L);
        Ab tear = flow(raw, ItemID.FROZEN_TEAR);
        assertEquals("a tear is tradeable: it prices at the market quote, never face value",
            Av.GRAND_EXCHANGE, tear.getPriceSource());
        assertEquals(-816L, tear.valueDelta);
    }

    @Test public void coinPouchesAreUnopenedClaimsHeldAtZero()
    {
        Bl service = new Bl(new GpManagerConfig() { },
            id -> composition(id == ItemID.PICKPOCKET_COIN_POUCH_ELF ? "Coin pouch" : "Item " + id, 0),
            id -> id == ItemID.PICKPOCKET_COIN_POUCH_ELF ? 1 : 995);
        long now = 1_725_000_000_123L;
        Map<Integer, Long> pickup = new LinkedHashMap<>();
        pickup.put(ItemID.PICKPOCKET_COIN_POUCH_ELF, 1L);
        Ab claim = flow(service.value(pickup, now), ItemID.PICKPOCKET_COIN_POUCH_ELF);
        assertEquals("a pouch is an unopened claim, never a fabricated quote",
            Av.DEFERRED_CLAIM, claim.getPriceSource());
        assertEquals(0L, claim.valueDelta);

        Map<Integer, Long> opened = new LinkedHashMap<>();
        opened.put(ItemID.PICKPOCKET_COIN_POUCH_ELF, -1L);
        opened.put(ItemID.COINS, 300L);
        List<Ab> raw = service.value(opened, now);
        assertEquals("opening the pouch is neutral, not a loss of held wealth",
            Av.DEFERRED_CLAIM, flow(raw, ItemID.PICKPOCKET_COIN_POUCH_ELF).getPriceSource());
        assertEquals("the coins book as income on receipt", 300L,
            flow(raw, ItemID.COINS).valueDelta);
    }

    @Test public void emptyVialIsIgnoredButFilledVialRemainsValued()
    {
        Bl service = potionService(new GpManagerConfig() { });
        Map<Integer, Long> drink = new LinkedHashMap<>();
        drink.put(143, -1L);
        drink.put(ItemID.VIAL_EMPTY, 1L);

        List<Ab> raw = service.value(drink, 1_725_000_000_123L);
        assertNull("plain vial is a physical neutral and is omitted", findFlow(raw, ItemID.VIAL_EMPTY));
        assertTrue("an empty vial loss is neutral too",
            service.value(Collections.singletonMap(ItemID.VIAL_EMPTY, -1L),
                1_725_000_000_123L).isEmpty());

        List<Ab> normalized = service.normalizeConsumedFlows(raw, Au.DRINK,
            1_725_000_000_123L);
        assertEquals(-1_000L, flow(normalized, 143).valueDelta);
        assertNull("the neutral vessel never becomes revenue", findFlow(normalized, ItemID.VIAL_EMPTY));
    }

    @Test public void filledVialAndSmashedFinalDoseKeepTheirOwnPhysicalMeaning()
    {
        Bl service = potionService(new GpManagerConfig() { });
        Map<Integer, Long> filled = Collections.singletonMap(ItemID.VIAL_WATER, 1L);
        Ab filledVial = service.value(filled, 1_725_000_000_123L).get(0);
        assertEquals("Vial of water is not the neutral empty-vial identity", 12L,
            filledVial.valueDelta);
        assertEquals(Av.GRAND_EXCHANGE, filledVial.getPriceSource());

        List<Ab> smashed = service.normalizeConsumedFlows(
            service.value(Collections.singletonMap(143, -1L), 1_725_000_000_123L),
            Au.DRINK, 1_725_000_000_123L);
        assertEquals("a smashed final vial still books one dose cost", -1_000L,
            smashed.get(0).valueDelta);
        assertEquals(1, smashed.size());
    }

    @Test public void normalizationFailsClosedBeforeLongOverflow()
    {
        Bl service = potionService(new GpManagerConfig() { }, Integer.MAX_VALUE);
        Map<Integer, Long> sip = new LinkedHashMap<>();
        sip.put(2434, -Long.MAX_VALUE);
        sip.put(139, Long.MAX_VALUE);

        List<Ab> raw = service.value(sip, 1_725_000_000_123L);
        long rawLoss = flow(raw, 2434).valueDelta;
        long rawRemainder = flow(raw, 139).valueDelta;
        List<Ab> normalized = service.normalizeConsumedFlows(raw, Au.DRINK,
            1_725_000_000_123L);
        assertNull("overflow routes the original receipt to Review", normalized);
        assertEquals("overflow leaves the captured loss untouched", rawLoss, flow(raw, 2434).valueDelta);
        assertEquals("overflow leaves the captured remainder untouched", rawRemainder,
            flow(raw, 139).valueDelta);
    }

    @Test public void unpricedQuantitiesAreAlwaysKeptForReviewAndNeverBecomeZeroGp()
    {
        // Hub v1 has no automatic unpriced/ignored-item accounting exclusion (charter D).
        Bl service = new Bl(new GpManagerConfig() { }, id -> null, id -> 0);
        Ab flow = service.value(Collections.singletonMap(1942, 3L)).get(0);
        assertEquals(3L, flow.quantityDelta);
        assertEquals(Av.UNPRICED, flow.getPriceSource());
        assertEquals(0L, flow.valueDelta);
    }

    @Test public void manualOverrideSurvivesUnavailableDefinitionWithoutReadingMarket()
    {
        GpManagerConfig config = new GpManagerConfig() {
            public String manualPriceOverrides() { return "1942=50"; }
        };
        Bl service = new Bl(config,
            id -> { throw new IllegalStateException("Definition not ready"); },
            id -> { fail("Manual override must not query market"); return 0; });
        Ab flow = service.value(Collections.singletonMap(1942, 2L)).get(0);
        assertEquals(100, flow.valueDelta);
        assertEquals(Av.MANUAL_OVERRIDE, flow.getPriceSource());
    }

    @Test public void priceRefreshAffectsNewFlowsOnlyAndUnknownPricesStayReviewable()
    {
        GpManagerConfig config = new GpManagerConfig() { };
        int[] price = {50};
        Bl service = new Bl(config, id -> null, id -> price[0]);
        Ab first = service.value(Collections.singletonMap(1942, 2L)).get(0);
        price[0] = 70;
        assertEquals(140, service.value(Collections.singletonMap(1942, 2L)).get(0).valueDelta);
        assertEquals(100, first.valueDelta);
        price[0] = 0;
        assertEquals(Av.UNPRICED,
            service.value(Collections.singletonMap(1942, 2L)).get(0).getPriceSource());
    }

    @Test public void explicitValuationTimeIsAttachedOnlyWhenPriceEvidenceExists()
    {
        GpManagerConfig config = new GpManagerConfig() { };
        Bl service = new Bl(config, id -> null,
            id -> id == 1942 ? 75 : 0);

        Ab captured = service.value(Collections.singletonMap(1942, 2L), 1_725_000_000_123L).get(0);
        assertEquals(1_725_000_000_123L, captured.getPriceCapturedAtEpochMillis());
        assertEquals(150L, captured.valueDelta);

        Ab legacy = service.value(Collections.singletonMap(1942, 2L)).get(0);
        assertEquals("Legacy valuation calls do not invent a capture time", 0L,
            legacy.getPriceCapturedAtEpochMillis());

        Ab coins = service.value(Collections.singletonMap(995, 2L), 1_725_000_000_123L).get(0);
        assertEquals(Av.FACE_VALUE, coins.getPriceSource());
        assertEquals(2L, coins.valueDelta);
        assertEquals("A deterministic face value has no quote capture time", 0L,
            coins.getPriceCapturedAtEpochMillis());
    }

    @Test public void coinsAndPlatinumUseFixedFaceValue()
    {
        GpManagerConfig config = new GpManagerConfig() {
            @Override public String manualPriceOverrides() { return "995=999,13204=1"; }
        };
        Bl service = new Bl(config, id -> null,
            id -> { fail("fixed currencies must not query the market"); return 0; });

        Ab coins = service.value(Collections.singletonMap(ItemID.COINS, 7L)).get(0);
        assertEquals(Av.FACE_VALUE, coins.getPriceSource());
        assertEquals(1, coins.unitPrice);
        assertEquals(7L, coins.valueDelta);

        Ab platinum = service.value(Collections.singletonMap(ItemID.PLATINUM, 2L)).get(0);
        assertEquals(Av.FACE_VALUE, platinum.getPriceSource());
        assertEquals(1_000, platinum.unitPrice);
        assertEquals(2_000L, platinum.valueDelta);
    }

    @Test public void legacyFlowValuatorCanIgnoreTimestampOverload()
    {
        FlowValuator legacy = deltas -> Collections.singletonList(
            new Ab(1942, "Item", deltas.get(1942), 1, deltas.get(1942)));

        Ab flow = legacy.value(Collections.singletonMap(1942, 1L), 1_725_000_000_123L).get(0);
        assertEquals(0L, flow.getPriceCapturedAtEpochMillis());
    }

    @Test public void nonGpCurrencyWithoutOverrideStaysUnpriced()
    {
        // An exchange relationship (tokkul → onyx, stardust → coins) is not a supported
        // observation and must never be converted into proxy GP.
        Bl service = new Bl(new GpManagerConfig() { }, id -> null, id -> {
            if (id == 6573) return 3_000_000;
            return 0;
        });
        Ab tokkul = service.value(Collections.singletonMap(6529, 300L)).get(0);
        assertEquals(Av.UNPRICED, tokkul.getPriceSource());
        assertEquals(0, tokkul.unitPrice);
        assertEquals(0L, tokkul.valueDelta);

        Ab stardust = service.value(Collections.singletonMap(46678, 5L)).get(0);
        assertEquals(Av.UNPRICED, stardust.getPriceSource());
        assertEquals(0L, stardust.valueDelta);
    }

    @Test public void runeLiteMappedNonGpCurrenciesStayUnpriced()
    {
        // RuneLite 1.12.39 ItemManager maps these untradeable currency ids to coal when no
        // direct quote exists. GP Manager must not turn that platform identity normalization into
        // automatic monetary identity; only an explicit owner override may price them.
        Bl service = new Bl(new GpManagerConfig() {},
            id -> null, id -> 123);

        Ab minerals = service.value(Collections.singletonMap(21341, 1L)).get(0);
        assertEquals(Av.UNPRICED, minerals.getPriceSource());
        assertEquals(0L, minerals.valueDelta);

        Ab nuggets = service.value(Collections.singletonMap(12012, 1L)).get(0);
        assertEquals(Av.UNPRICED, nuggets.getPriceSource());
        assertEquals(0L, nuggets.valueDelta);
    }

    @Test public void runeLiteMappingGuardRejectsRatesAndCoinTargets()
    {
        // ItemManager maps these physically distinct items to a quantity/rate or coin value.
        // Only a quantity-one, non-coin state/base mapping may remain eligible for a quote.
        assertTrue(Bl.vb(
            net.runelite.client.game.ItemMapping.map(21341)));
        assertTrue(Bl.vb(
            net.runelite.client.game.ItemMapping.map(23510)));
        assertFalse(Bl.vb(
            net.runelite.client.game.ItemMapping.map(12797)));
    }

    @Test public void manualOverrideStillPricesNonGpCurrency()
    {
        GpManagerConfig config = new GpManagerConfig() {
            public String manualPriceOverrides() { return "6529=7"; }
        };
        Bl service = new Bl(config, id -> null, id -> 0);
        Ab flow = service.value(Collections.singletonMap(6529, 2L)).get(0);
        assertEquals(Av.MANUAL_OVERRIDE, flow.getPriceSource());
        assertEquals(14, flow.valueDelta);
    }

    @Test public void highAlchemyMetadataNeverBooksValue()
    {
        // HA is metadata, not alchemy evidence or liquidation truth: the item stays unpriced
        // and nothing about the alchemy price books money on its own.
        Bl service = new Bl(new GpManagerConfig() { },
            id -> composition("Unpriced hat", 48_000), id -> 0);
        Ab flow = service.value(Collections.singletonMap(1942, 1L)).get(0);
        assertEquals(Av.UNPRICED, flow.getPriceSource());
        assertEquals(0, flow.unitPrice);
        assertEquals(0L, flow.valueDelta);
    }

    @Test public void identityMappingNeverManufacturesCrossItemValue()
    {
        // Only the flow's own item id may be priced; a sibling quote is not evidence for it.
        Bl service = new Bl(new GpManagerConfig() { },
            id -> null, id -> id == 1942 ? 100 : 0);
        Ab flow = service.value(Collections.singletonMap(1943, 1L)).get(0);
        assertEquals(Av.UNPRICED, flow.getPriceSource());
        assertEquals(0L, flow.valueDelta);
    }

    @Test public void untradeableBrimstoneKeyIsDeferredAtZeroEvenWithAlchemyAndOverride()
    {
        GpManagerConfig config = new GpManagerConfig() {
            public String manualPriceOverrides() { return ItemID.KONAR_KEY + "=900000"; }
        };
        Bl service = new Bl(config,
            id -> composition("Brimstone key", 48_000),
            id -> { fail("Deferred keys must bypass GE and HA pricing"); return 0; });

        Ab flow = service.value(Collections.singletonMap(ItemID.KONAR_KEY, 1L)).get(0);
        assertEquals(0, flow.unitPrice);
        assertEquals(0L, flow.valueDelta);
        assertEquals(Av.DEFERRED_CLAIM, flow.getPriceSource());
        assertEquals("Claim on open", flow.getPriceSource().toString());

        Ac transaction = new Ac(1L, null, Ai.TRANSFER,
            Aj.TRANSFER, "Key held · Brimstone chest", "Brimstone chest", false,
            Collections.singletonList(flow), Bd.CONFIRMED, "", null);
        Af contribution = Af.from(transaction, flow);
        assertNotNull(contribution);
        assertFalse("Deferred claim is not an unknown/unpriced review edge", contribution.isUnpriced());
        assertFalse("Confirmed deferred claim should not be flagged for review", contribution.needsReview);
    }

    @Test public void tradeableCrystalKeyKeepsGeOpportunityCost()
    {
        Bl service = new Bl(new GpManagerConfig() { },
            id -> composition("Crystal key", 10), id -> 18_000);
        Ab flow = service.value(Collections.singletonMap(ItemID.CRYSTAL_KEY, -1L)).get(0);
        assertEquals(-18_000L, flow.valueDelta);
        assertEquals(Av.GRAND_EXCHANGE, flow.getPriceSource());
    }

    @Test public void settledWholePotionCostsFourEqualDosesWithOrWithoutVial()
    {
        for (boolean retainVial : new boolean[] {false, true})
        {
            GpManagerConfig config = new GpManagerConfig() { };
            Am engine = new Am(potionService(config), new TransactionClassifier(), config);
            engine.rm(1_000L);
            engine.setBaseline(new Cc(Map.of(2434, 1L)));
            int[] ids = {2434, 139, 141, 143};
            for (int i = 0; i < ids.length; i++)
            {
                engine.noteConsumptionIntent(ids[i], 6, false, Au.DRINK);
                Map<Integer, Long> inventory = i < 3 ? Map.of(ids[i + 1], 1L)
                    : retainVial ? Map.of(ItemID.VIAL_EMPTY, 1L) : Collections.emptyMap();
                Ac sip = settleDrink(engine, inventory, 2_000L + i * 2_000L);
                assertEquals(Ai.CONSUMPTION, sip.getType());
                assertEquals(-1_000L, Bp.transaction(sip).getNet());
            }
            Bu metrics = engine.getMetrics(11_000L);
            assertEquals(0L, metrics.revenue);
            assertEquals(4_000L, metrics.costs);
            Ca live = Ca.capture(engine, 11_000L, null);
            assertEquals(1, live.recent.size());
            assertEquals("Drank · ×4 doses", LivePage.metaOf(live.recent.get(0)));
        }
    }

    @Test public void unsupportedDoseBasisSettlesUncountedReviewWithOriginalQuotes()
    {
        GpManagerConfig config = new GpManagerConfig() {
            @Override public String manualPriceOverrides() { return "139=5000"; }
            @Override public boolean countUncertainMixedChanges() { return true; }
        };
        Am engine = new Am(potionService(config), new TransactionClassifier(), config);
        engine.rm(1_000L);
        engine.setBaseline(new Cc(Map.of(2434, 1L)));
        engine.noteConsumptionIntent(2434, 6, false, Au.DRINK);
        Ac sip = settleDrink(engine, Map.of(139, 1L), 2_000L);
        assertEquals(Ai.UNCERTAIN, sip.getType());
        assertFalse(sip.isCounted());
        assertEquals(-3_600L, flow(sip.getFlows(), 2434).valueDelta);
        assertEquals(5_000L, flow(sip.getFlows(), 139).valueDelta);
        assertEquals(0L, engine.getMetrics(5_000L).net);
    }

    @Test public void unsupportedDrinkKeepsBothQuotesDespiteTerminalGeCollection()
    {
        GpManagerConfig config = new GpManagerConfig() {
            @Override public String manualPriceOverrides() { return "139=5000"; }
            @Override public boolean countUncertainMixedChanges() { return true; }
        };
        Am engine = new Am(potionService(config), new TransactionClassifier(), config);
        engine.rm(1_000L);
        engine.setBaseline(new Cc(Map.of(2434, 1L)));
        Bj offers = new Bj();
        for (net.runelite.api.GrandExchangeOfferState state : List.of(
            net.runelite.api.GrandExchangeOfferState.BUYING, net.runelite.api.GrandExchangeOfferState.BOUGHT))
        {
            boolean filled = state == net.runelite.api.GrandExchangeOfferState.BOUGHT;
            Bj.Transition transition = offers.observe(new Bj.Snapshot(
                0, state, 139, 1, filled ? 1 : 0, 5000, filled ? 5000 : 0)).orElse(null);
            engine.abh(transition, "Prayer potion(3)", filled ? 1_600L : 1_300L);
        }
        engine.abg(1_800L);
        engine.noteConsumptionIntent(2434, 6, false, Au.DRINK);
        Ac sip = settleDrink(engine, Map.of(139, 1L), 2_000L);
        assertEquals(Ai.UNCERTAIN, sip.getType());
        assertFalse(sip.isCounted());
        assertEquals(2, sip.getFlows().size());
        assertEquals(-3600L, flow(sip.getFlows(), 2434).valueDelta);
        assertEquals(5000L, flow(sip.getFlows(), 139).valueDelta);
        assertEquals(0L, engine.getMetrics(5_000L).revenue);
        assertEquals(0L, engine.getMetrics(5_000L).costs);
    }

    private static Ac settleDrink(Am engine, Map<Integer, Long> inventory, long now)
    {
        engine.yz();
        Cc snapshot = new Cc(inventory);
        assertNull(engine.adj(snapshot, now));
        assertNull(engine.adj(snapshot, now + 600L));
        Ac sip = engine.adj(snapshot, now + 1_200L);
        assertNotNull(sip);
        return sip;
    }

    private static Bl potionService(GpManagerConfig config)
    {
        return potionService(config, 1_000);
    }

    private static Bl potionService(GpManagerConfig config, int oneDosePrice)
    {
        Map<Integer, String> names = new HashMap<>();
        names.put(2434, "Prayer potion(4)");
        names.put(139, "Prayer potion(3)");
        names.put(141, "Prayer potion(2)");
        names.put(143, "Prayer potion(1)");
        names.put(ItemID.VIAL_EMPTY, "Vial");
        names.put(ItemID.VIAL_WATER, "Vial of water");
        Map<Integer, Integer> prices = new HashMap<>();
        prices.put(2434, 3_600);
        prices.put(139, 2_600);
        prices.put(141, 1_900);
        prices.put(143, oneDosePrice);
        prices.put(ItemID.VIAL_EMPTY, 8);
        prices.put(ItemID.VIAL_WATER, 12);
        return new Bl(config,
            id -> composition(names.getOrDefault(id, "Item " + id), 0),
            id -> prices.getOrDefault(id, 0));
    }

    private static Ab flow(List<Ab> flows, int itemId)
    {
        Ab found = findFlow(flows, itemId);
        if (found != null) return found;
        fail("missing flow for " + itemId);
        return null;
    }

    private static Ab findFlow(List<Ab> flows, int itemId)
    {
        for (Ab flow : flows) {
            if (flow.itemId == itemId) return flow;
        }
        return null;
    }

    private static long net(List<Ab> flows)
    {
        long total = 0L;
        for (Ab flow : flows) {
            total = Ae.safeAdd(total, flow.valueDelta);
        }
        return total;
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
