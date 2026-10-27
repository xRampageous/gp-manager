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
        Valuation service = ModelProbe.valuation(new GpManagerConfig() { },
            id -> composition(names.getOrDefault(id, "Item " + id), 0), id -> prices.getOrDefault(id, 0));
        int[][] steps = {{2434, 139}, {139, 141}, {141, 143}};
        for (int[] step : steps)
        {
            java.util.Map<Integer, Long> sip = new java.util.HashMap<>();
            sip.put(step[0], -1L);
            sip.put(step[1], 1L);
            long net = 0L;
            for (Flow flow : service.normalizeConsumedFlows(
                service.value(sip, 1_725_000_000_123L), ActionKind.DRINK, 1_725_000_000_123L))
            {
                net += flow.value;
            }
            assertEquals("a sip from " + names.get(step[0]) + " costs one (1)", -1_000L, net);
        }
        assertEquals("a (4) that is not drunk keeps its own quote", 3_600L,
            service.value(Collections.singletonMap(2434, 1L)).get(0).value);
    }

    @Test public void valueRemainsQuoteOnlyForDecantShapes()
    {
        Valuation service = potionService(new GpManagerConfig() { });
        Map<Integer, Long> decant = new LinkedHashMap<>();
        decant.put(2434, -1L);
        decant.put(139, 1L);
        decant.put(143, 1L);

        List<Flow> raw = service.value(decant, 1_725_000_000_123L);
        assertEquals(-3_600L, flow(raw, 2434).value);
        assertEquals(2_600L, flow(raw, 139).value);
        assertEquals(1_000L, flow(raw, 143).value);

        List<Flow> afterDrinkGate = service.normalizeConsumedFlows(raw, ActionKind.DRINK,
            1_725_000_000_123L);
        assertEquals("dose-conserving decants stay at their captured quotes", -3_600L,
            flow(afterDrinkGate, 2434).value);
        assertEquals(2_600L, flow(afterDrinkGate, 139).value);
        assertEquals(1_000L, flow(afterDrinkGate, 143).value);
    }

    @Test public void manualOneDoseAuthorityAppliesToEveryPricedPairLeg()
    {
        GpManagerConfig config = new GpManagerConfig() {
            @Override public String manualPriceOverrides() { return "143=777,139=2331"; }
        };
        Valuation service = potionService(config);
        Map<Integer, Long> sip = new LinkedHashMap<>();
        sip.put(2434, -1L);
        sip.put(139, 1L);

        List<Flow> normalized = service.normalizeConsumedFlows(
            service.value(sip, 1_725_000_000_123L), ActionKind.DRINK, 1_725_000_000_123L);
        assertEquals(-3_108L, flow(normalized, 2434).value);
        assertEquals(2_331L, flow(normalized, 139).value);
        assertEquals(PriceSource.MANUAL_OVERRIDE, flow(normalized, 2434).source());
        assertEquals(PriceSource.MANUAL_OVERRIDE, flow(normalized, 139).source());
        assertEquals(-777L, net(normalized));
    }

    @Test public void conflictingManualVariantDoesNotNormalizeOnePairLeg()
    {
        GpManagerConfig config = new GpManagerConfig() {
            @Override public String manualPriceOverrides() { return "139=5000"; }
        };
        Valuation service = potionService(config);
        Map<Integer, Long> sip = new LinkedHashMap<>();
        sip.put(2434, -1L);
        sip.put(139, 1L);

        List<Flow> raw = service.value(sip, 1_725_000_000_123L);
        List<Flow> normalized = service.normalizeConsumedFlows(raw, ActionKind.DRINK,
            1_725_000_000_123L);
        assertNull("mixed provenance routes the original receipt to Review", normalized);
        assertEquals("mixed GE/manual pair keeps the GE loss", -3_600L,
            flow(raw, 2434).value);
        assertEquals("mixed GE/manual pair keeps the manual gain", 5_000L,
            flow(raw, 139).value);
        assertEquals(PriceSource.GRAND_EXCHANGE, flow(raw, 2434).source());
        assertEquals(PriceSource.MANUAL_OVERRIDE, flow(raw, 139).source());
    }

    @Test public void unavailableOneDoseQuoteLeavesCapturedPairUnchanged()
    {
        Valuation service = potionService(new GpManagerConfig() { }, 0);
        Map<Integer, Long> sip = new LinkedHashMap<>();
        sip.put(2434, -1L);
        sip.put(139, 1L);

        List<Flow> raw = service.value(sip, 1_725_000_000_123L);
        List<Flow> normalized = service.normalizeConsumedFlows(raw, ActionKind.DRINK,
            1_725_000_000_123L);
        assertNull("missing authority routes the original receipt to Review", normalized);
        assertEquals("a missing (1) must not become a fabricated zero price", -3_600L,
            flow(raw, 2434).value);
        assertEquals(2_600L, flow(raw, 139).value);
        assertEquals(PriceSource.GRAND_EXCHANGE, flow(raw, 2434).source());
        assertEquals(PriceSource.GRAND_EXCHANGE, flow(raw, 139).source());
    }

    @Test public void frozenTearsPriceFromTheirLiveMarketQuote()
    {
        Valuation service = ModelProbe.valuation(new GpManagerConfig() { },
            id -> composition(id == ItemID.FROZEN_TEAR ? "Frozen tear" : "Item " + id, 0),
            id -> id == ItemID.FROZEN_TEAR ? 272 : 0);
        List<Flow> raw = service.value(
            Collections.singletonMap(ItemID.FROZEN_TEAR, -3L), 1_725_000_000_123L);
        Flow tear = flow(raw, ItemID.FROZEN_TEAR);
        assertEquals("a tear is tradeable: it prices at the market quote, never face value",
            PriceSource.GRAND_EXCHANGE, tear.source());
        assertEquals(-816L, tear.value);
    }

    @Test public void coinPouchesAreUnopenedClaimsHeldAtZero()
    {
        Valuation service = ModelProbe.valuation(new GpManagerConfig() { },
            id -> composition(id == ItemID.PICKPOCKET_COIN_POUCH_ELF ? "Coin pouch" : "Item " + id, 0),
            id -> id == ItemID.PICKPOCKET_COIN_POUCH_ELF ? 1 : 995);
        long now = 1_725_000_000_123L;
        Map<Integer, Long> pickup = new LinkedHashMap<>();
        pickup.put(ItemID.PICKPOCKET_COIN_POUCH_ELF, 1L);
        Flow claim = flow(service.value(pickup, now), ItemID.PICKPOCKET_COIN_POUCH_ELF);
        assertEquals("a pouch is an unopened claim, never a fabricated quote",
            PriceSource.DEFERRED_CLAIM, claim.source());
        assertEquals(0L, claim.value);

        Map<Integer, Long> opened = new LinkedHashMap<>();
        opened.put(ItemID.PICKPOCKET_COIN_POUCH_ELF, -1L);
        opened.put(ItemID.COINS, 300L);
        List<Flow> raw = service.value(opened, now);
        assertEquals("opening the pouch is neutral, not a loss of held wealth",
            PriceSource.DEFERRED_CLAIM, flow(raw, ItemID.PICKPOCKET_COIN_POUCH_ELF).source());
        assertEquals("the coins book as income on receipt", 300L,
            flow(raw, ItemID.COINS).value);
    }

    @Test public void emptyVialIsIgnoredButFilledVialRemainsValued()
    {
        Valuation service = potionService(new GpManagerConfig() { });
        Map<Integer, Long> drink = new LinkedHashMap<>();
        drink.put(143, -1L);
        drink.put(ItemID.VIAL_EMPTY, 1L);

        List<Flow> raw = service.value(drink, 1_725_000_000_123L);
        assertNull("plain vial is a physical neutral and is omitted", findFlow(raw, ItemID.VIAL_EMPTY));
        assertTrue("an empty vial loss is neutral too",
            service.value(Collections.singletonMap(ItemID.VIAL_EMPTY, -1L),
                1_725_000_000_123L).isEmpty());

        List<Flow> normalized = service.normalizeConsumedFlows(raw, ActionKind.DRINK,
            1_725_000_000_123L);
        assertEquals(-1_000L, flow(normalized, 143).value);
        assertNull("the neutral vessel never becomes revenue", findFlow(normalized, ItemID.VIAL_EMPTY));
    }

    @Test public void filledVialAndSmashedFinalDoseKeepTheirOwnPhysicalMeaning()
    {
        Valuation service = potionService(new GpManagerConfig() { });
        Map<Integer, Long> filled = Collections.singletonMap(ItemID.VIAL_WATER, 1L);
        Flow filledVial = service.value(filled, 1_725_000_000_123L).get(0);
        assertEquals("Vial of water is not the neutral empty-vial identity", 12L,
            filledVial.value);
        assertEquals(PriceSource.GRAND_EXCHANGE, filledVial.source());

        List<Flow> smashed = service.normalizeConsumedFlows(
            service.value(Collections.singletonMap(143, -1L), 1_725_000_000_123L),
            ActionKind.DRINK, 1_725_000_000_123L);
        assertEquals("a smashed final vial still books one dose cost", -1_000L,
            smashed.get(0).value);
        assertEquals(1, smashed.size());
    }

    @Test public void normalizationFailsClosedBeforeLongOverflow()
    {
        Valuation service = potionService(new GpManagerConfig() { }, Integer.MAX_VALUE);
        Map<Integer, Long> sip = new LinkedHashMap<>();
        sip.put(2434, -Long.MAX_VALUE);
        sip.put(139, Long.MAX_VALUE);

        List<Flow> raw = service.value(sip, 1_725_000_000_123L);
        long rawLoss = flow(raw, 2434).value;
        long rawRemainder = flow(raw, 139).value;
        List<Flow> normalized = service.normalizeConsumedFlows(raw, ActionKind.DRINK,
            1_725_000_000_123L);
        assertNull("overflow routes the original receipt to Review", normalized);
        assertEquals("overflow leaves the captured loss untouched", rawLoss, flow(raw, 2434).value);
        assertEquals("overflow leaves the captured remainder untouched", rawRemainder,
            flow(raw, 139).value);
    }

    @Test public void unpricedQuantitiesAreAlwaysKeptForReviewAndNeverBecomeZeroGp()
    {
        // Hub v1 has no automatic unpriced/ignored-item accounting exclusion (charter D).
        Valuation service = ModelProbe.valuation(new GpManagerConfig() { }, id -> null, id -> 0);
        Flow flow = service.value(Collections.singletonMap(1942, 3L)).get(0);
        assertEquals(3L, flow.quantity);
        assertEquals(PriceSource.UNPRICED, flow.source());
        assertEquals(0L, flow.value);
    }

    @Test public void manualOverrideSurvivesUnavailableDefinitionWithoutReadingMarket()
    {
        GpManagerConfig config = new GpManagerConfig() {
            public String manualPriceOverrides() { return "1942=50"; }
        };
        Valuation service = ModelProbe.valuation(config,
            id -> { throw new IllegalStateException("Definition not ready"); },
            id -> { fail("Manual override must not query market"); return 0; });
        Flow flow = service.value(Collections.singletonMap(1942, 2L)).get(0);
        assertEquals(100, flow.value);
        assertEquals(PriceSource.MANUAL_OVERRIDE, flow.source());
    }

    @Test public void priceRefreshAffectsNewFlowsOnlyAndUnknownPricesStayReviewable()
    {
        GpManagerConfig config = new GpManagerConfig() { };
        int[] price = {50};
        Valuation service = ModelProbe.valuation(config, id -> null, id -> price[0]);
        Flow first = service.value(Collections.singletonMap(1942, 2L)).get(0);
        price[0] = 70;
        assertEquals(140, service.value(Collections.singletonMap(1942, 2L)).get(0).value);
        assertEquals(100, first.value);
        price[0] = 0;
        assertEquals(PriceSource.UNPRICED,
            service.value(Collections.singletonMap(1942, 2L)).get(0).source());
    }

    @Test public void explicitValuationTimeIsAttachedOnlyWhenPriceEvidenceExists()
    {
        GpManagerConfig config = new GpManagerConfig() { };
        Valuation service = ModelProbe.valuation(config, id -> null,
            id -> id == 1942 ? 75 : 0);

        Flow captured = service.value(Collections.singletonMap(1942, 2L), 1_725_000_000_123L).get(0);
        assertEquals(1_725_000_000_123L, captured.pricedAt());
        assertEquals(150L, captured.value);

        Flow legacy = service.value(Collections.singletonMap(1942, 2L)).get(0);
        assertEquals("Legacy valuation calls do not invent a capture time", 0L,
            legacy.pricedAt());

        Flow coins = service.value(Collections.singletonMap(995, 2L), 1_725_000_000_123L).get(0);
        assertEquals(PriceSource.FACE_VALUE, coins.source());
        assertEquals(2L, coins.value);
        assertEquals("A deterministic face value has no quote capture time", 0L,
            coins.pricedAt());
    }

    @Test public void coinsAndPlatinumUseFixedFaceValue()
    {
        GpManagerConfig config = new GpManagerConfig() {
            @Override public String manualPriceOverrides() { return "995=999,13204=1"; }
        };
        Valuation service = ModelProbe.valuation(config, id -> null,
            id -> { fail("fixed currencies must not query the market"); return 0; });

        Flow coins = service.value(Collections.singletonMap(ItemID.COINS, 7L)).get(0);
        assertEquals(PriceSource.FACE_VALUE, coins.source());
        assertEquals(1, coins.unitPrice);
        assertEquals(7L, coins.value);

        Flow platinum = service.value(Collections.singletonMap(ItemID.PLATINUM, 2L)).get(0);
        assertEquals(PriceSource.FACE_VALUE, platinum.source());
        assertEquals(1_000, platinum.unitPrice);
        assertEquals(2_000L, platinum.value);
    }

    @Test public void legacyFlowValuatorCanIgnoreTimestampOverload()
    {
        FlowValuator legacy = deltas -> Collections.singletonList(
            new Flow(1942, "Item", deltas.get(1942), 1, deltas.get(1942)));

        Flow flow = legacy.value(Collections.singletonMap(1942, 1L), 1_725_000_000_123L).get(0);
        assertEquals(0L, flow.pricedAt());
    }

    @Test public void tokkulPricesFromTheOnyxExchangeAndGlovesImproveIt()
    {
        // Owner 2026-10-12: a tokkul reads at the uncut onyx exchange (300,000 per onyx;
        // 260,000 while Karamja gloves are worn). A currency with no fixed exchange
        // (stardust → coins) still stays unpriced.
        Valuation service = ModelProbe.valuation(new GpManagerConfig() { }, id -> null, id -> {
            if (id == ItemID.UNCUT_ONYX) return 3_000_000;
            return 0;
        });
        Flow tokkul = service.value(Collections.singletonMap(6529, 300L)).get(0);
        assertEquals(PriceSource.CURRENCY_PROXY, tokkul.source());
        assertEquals(10, tokkul.unitPrice);
        assertEquals(3_000L, tokkul.value);

        service.setGloves(true);
        Flow gloved = service.value(Collections.singletonMap(6529, 260L)).get(0);
        assertEquals(PriceSource.CURRENCY_PROXY, gloved.source());
        assertEquals(11, gloved.unitPrice);
        assertEquals(2_860L, gloved.value);

        Flow stardust = service.value(Collections.singletonMap(46678, 5L)).get(0);
        assertEquals(PriceSource.UNPRICED, stardust.source());
        assertEquals(0L, stardust.value);
    }

    @Test public void runeLiteMappedNonGpCurrenciesStayUnpriced()
    {
        // RuneLite 1.12.39 ItemManager maps these untradeable currency ids to coal when no
        // direct quote exists. GP Manager must not turn that platform identity normalization into
        // automatic monetary identity; only an explicit owner override may price them.
        Valuation service = ModelProbe.valuation(new GpManagerConfig() {},
            id -> null, id -> 123);

        Flow minerals = service.value(Collections.singletonMap(21341, 1L)).get(0);
        assertEquals(PriceSource.UNPRICED, minerals.source());
        assertEquals(0L, minerals.value);

        Flow nuggets = service.value(Collections.singletonMap(12012, 1L)).get(0);
        assertEquals(PriceSource.UNPRICED, nuggets.source());
        assertEquals(0L, nuggets.value);
    }

    @Test public void runeLiteMappingGuardRejectsRatesAndCoinTargets()
    {
        // ItemManager maps these physically distinct items to a quantity/rate or coin value.
        // Only a quantity-one, non-coin state/base mapping may remain eligible for a quote.
        assertTrue(Valuation.hasUnsupportedMapping(
            net.runelite.client.game.ItemMapping.map(21341)));
        assertTrue(Valuation.hasUnsupportedMapping(
            net.runelite.client.game.ItemMapping.map(23510)));
        assertFalse(Valuation.hasUnsupportedMapping(
            net.runelite.client.game.ItemMapping.map(12797)));
    }

    @Test public void manualOverrideStillPricesNonGpCurrency()
    {
        GpManagerConfig config = new GpManagerConfig() {
            public String manualPriceOverrides() { return "6529=7"; }
        };
        Valuation service = ModelProbe.valuation(config, id -> null, id -> 0);
        Flow flow = service.value(Collections.singletonMap(6529, 2L)).get(0);
        assertEquals(PriceSource.MANUAL_OVERRIDE, flow.source());
        assertEquals(14, flow.value);
    }

    @Test public void anItemWithOnlyAnAlchValueIsWorthItsAlchPrice()
    {
        // Owner 2026-10-16: an item no market can quote (untradeable here) is worth its alch value
        // for every account; with a GE price as well, a normal account keeps the GE price.
        Valuation service = ModelProbe.valuation(new GpManagerConfig() { },
            id -> composition("Unpriced hat", 48_000), id -> 0);
        Flow flow = service.value(Collections.singletonMap(1942, 1L)).get(0);
        assertEquals(PriceSource.HIGH_ALCHEMY, flow.source());
        assertEquals(48_000, flow.unitPrice);
        assertEquals(48_000L, flow.value);
        Valuation quoted = ModelProbe.valuation(new GpManagerConfig() { },
            id -> composition("Unpriced hat", 48_000), id -> id == 1942 ? 50_000 : 0);
        assertEquals(PriceSource.GRAND_EXCHANGE, quoted.value(Collections.singletonMap(1942, 1L)).get(0).source());
    }

    @Test public void identityMappingNeverManufacturesCrossItemValue()
    {
        // Only the flow's own item id may be priced; a sibling quote is not evidence for it.
        Valuation service = ModelProbe.valuation(new GpManagerConfig() { },
            id -> null, id -> id == 1942 ? 100 : 0);
        Flow flow = service.value(Collections.singletonMap(1943, 1L)).get(0);
        assertEquals(PriceSource.UNPRICED, flow.source());
        assertEquals(0L, flow.value);
    }

    @Test public void untradeableBrimstoneKeyIsDeferredAtZeroEvenWithAlchemyAndOverride()
    {
        GpManagerConfig config = new GpManagerConfig() {
            public String manualPriceOverrides() { return ItemID.KONAR_KEY + "=900000"; }
        };
        Valuation service = ModelProbe.valuation(config,
            id -> composition("Brimstone key", 48_000),
            id -> { fail("Deferred keys must bypass GE and HA pricing"); return 0; });

        Flow flow = service.value(Collections.singletonMap(ItemID.KONAR_KEY, 1L)).get(0);
        assertEquals(0, flow.unitPrice);
        assertEquals(0L, flow.value);
        assertEquals(PriceSource.DEFERRED_CLAIM, flow.source());
        assertEquals("Claim on open", flow.source().toString());

        Transaction transaction = new Transaction(1L, TransactionType.TRANSFER,
            Context.TRANSFER, "Key held · Brimstone chest", "Brimstone chest", false,
            Collections.singletonList(flow), Confidence.CONFIRMED, "", null);
        Contribution contribution = Contribution.from(transaction, flow, 0);
        assertNotNull(contribution);
        assertFalse("Deferred claim is not an unknown/unpriced review edge", contribution.unpriced());
        assertFalse("Confirmed deferred claim should not be flagged for review", contribution.needsReview);
    }

    @Test public void tradeableCrystalKeyKeepsGeOpportunityCost()
    {
        Valuation service = ModelProbe.valuation(new GpManagerConfig() { },
            id -> composition("Crystal key", 10), id -> 18_000);
        Flow flow = service.value(Collections.singletonMap(ItemID.CRYSTAL_KEY, -1L)).get(0);
        assertEquals(-18_000L, flow.value);
        assertEquals(PriceSource.GRAND_EXCHANGE, flow.source());
    }

    @Test public void settledWholePotionCostsFourEqualDosesWithOrWithoutVial()
    {
        for (boolean retainVial : new boolean[] {false, true})
        {
            GpManagerConfig config = new GpManagerConfig() { };
            Engine engine = new Engine(potionService(config), new TransactionClassifier(), config);
            engine.ensureSession(1_000L);
            engine.setBaseline(new ContainerSnapshot(Map.of(2434, 1L)));
            int[] ids = {2434, 139, 141, 143};
            for (int i = 0; i < ids.length; i++)
            {
                engine.noteConsume(ids[i], 6, false, ActionKind.DRINK, null);
                Map<Integer, Long> inventory = i < 3 ? Map.of(ids[i + 1], 1L)
                    : retainVial ? Map.of(ItemID.VIAL_EMPTY, 1L) : Collections.emptyMap();
                Transaction sip = settleDrink(engine, inventory, 2_000L + i * 2_000L);
                assertEquals(TransactionType.CONSUMPTION, sip.type());
                assertEquals(-1_000L, Accounting.transaction(sip).net());
            }
            Metrics metrics = engine.metrics(11_000L);
            assertEquals(0L, metrics.income);
            assertEquals(4_000L, metrics.cost);
            LiveSnapshot live = LiveSnapshot.capture(engine, 11_000L, null);
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
        Engine engine = new Engine(potionService(config), new TransactionClassifier(), config);
        engine.ensureSession(1_000L);
        engine.setBaseline(new ContainerSnapshot(Map.of(2434, 1L)));
        engine.noteConsume(2434, 6, false, ActionKind.DRINK, null);
        Transaction sip = settleDrink(engine, Map.of(139, 1L), 2_000L);
        assertEquals(TransactionType.UNCERTAIN, sip.type());
        assertFalse(sip.counts());
        assertEquals(-3_600L, flow(sip.flows(), 2434).value);
        assertEquals(5_000L, flow(sip.flows(), 139).value);
        assertEquals(0L, engine.metrics(5_000L).net);
    }

    @Test public void unsupportedDrinkKeepsBothQuotesDespiteTerminalGeCollection()
    {
        GpManagerConfig config = new GpManagerConfig() {
            @Override public String manualPriceOverrides() { return "139=5000"; }
            @Override public boolean countUncertainMixedChanges() { return true; }
        };
        Engine engine = new Engine(potionService(config), new TransactionClassifier(), config);
        engine.ensureSession(1_000L);
        engine.setBaseline(new ContainerSnapshot(Map.of(2434, 1L)));
        OfferSlots offers = new OfferSlots();
        for (net.runelite.api.GrandExchangeOfferState state : List.of(
            net.runelite.api.GrandExchangeOfferState.BUYING, net.runelite.api.GrandExchangeOfferState.BOUGHT))
        {
            boolean filled = state == net.runelite.api.GrandExchangeOfferState.BOUGHT;
            OfferSlots.Transition transition = offers.observe(new OfferSlots.Snapshot(
                0, state, 139, 1, filled ? 1 : 0, 5000, filled ? 5000 : 0)).orElse(null);
            engine.noteGeOffer(transition, "Prayer potion(3)", filled ? 1_600L : 1_300L);
        }
        engine.noteGeCollect(1_800L);
        engine.noteConsume(2434, 6, false, ActionKind.DRINK, null);
        Transaction sip = settleDrink(engine, Map.of(139, 1L), 2_000L);
        assertEquals(TransactionType.UNCERTAIN, sip.type());
        assertFalse(sip.counts());
        assertEquals(2, sip.flows().size());
        assertEquals(-3600L, flow(sip.flows(), 2434).value);
        assertEquals(5000L, flow(sip.flows(), 139).value);
        assertEquals(0L, engine.metrics(5_000L).income);
        assertEquals(0L, engine.metrics(5_000L).cost);
    }

    static Transaction settleDrink(Engine engine, Map<Integer, Long> inventory, long now)
    {
        engine.markDirty();
        ContainerSnapshot snapshot = new ContainerSnapshot(inventory);
        assertNull(engine.processIfDirty(snapshot, now, null));
        assertNull(engine.processIfDirty(snapshot, now + 600L, null));
        Transaction sip = engine.processIfDirty(snapshot, now + 1_200L, null);
        assertNotNull(sip);
        return sip;
    }

    private static Valuation potionService(GpManagerConfig config)
    {
        return potionService(config, 1_000);
    }

    private static Valuation potionService(GpManagerConfig config, int oneDosePrice)
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
        return ModelProbe.valuation(config,
            id -> composition(names.getOrDefault(id, "Item " + id), 0),
            id -> prices.getOrDefault(id, 0));
    }

    private static Flow flow(List<Flow> flows, int itemId)
    {
        Flow found = findFlow(flows, itemId);
        if (found != null) return found;
        fail("missing flow for " + itemId);
        return null;
    }

    private static Flow findFlow(List<Flow> flows, int itemId)
    {
        for (Flow flow : flows) {
            if (flow.item == itemId) return flow;
        }
        return null;
    }

    private static long net(List<Flow> flows)
    {
        long total = 0L;
        for (Flow flow : flows) {
            total = SafeMath.sum(total, flow.value);
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
