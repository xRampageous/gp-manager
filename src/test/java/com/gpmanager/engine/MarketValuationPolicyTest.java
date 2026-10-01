package com.gpmanager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * PRE-R5C.2C.2: one GP Manager valuation authority. Ordinary observations, measured charge
 * components, custody basis and provenance all share the explicit active RuneLite market route,
 * the mapping guard and the world/economy gate; booked values freeze and never reprice.
 */
public class MarketValuationPolicyTest
{
    private static final long T0 = 1_700_000_000_000L;
    private static final int RUNE = ItemID.NATURERUNE;

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    // ── ordinary valuation ─────────────────────────────────────────────────────────────────────

    @Test
    public void newOrdinaryFlowUsesTheExplicitActiveMarketQuote()
    {
        Seam seam = new Seam();
        seam.quote = 184;
        ItemValuationService service = service(new GpManagerConfig() {}, seam, NORMAL);

        Flow flow = service.value(Collections.singletonMap(560, 200L), T0 + 5L).get(0);

        assertEquals(184, flow.unitPrice);
        assertEquals(36_800L, flow.valueDelta);
        assertEquals(PriceSource.GRAND_EXCHANGE, flow.getPriceSource());
        assertEquals("RuneLite market", flow.getPriceSource().toString());
        assertEquals("the explicit active route is requested", Collections.singletonList(true),
            seam.activeFlags);
        assertEquals(Collections.singletonList(560), seam.asked);
        assertEquals("capture time is the GP Manager observation time", T0 + 5L,
            flow.getPriceCapturedAtEpochMillis());
    }

    @Test
    public void quoteChangesAffectOnlyLaterFlows()
    {
        Seam seam = new Seam();
        seam.quote = 100;
        ItemValuationService service = service(new GpManagerConfig() {}, seam, NORMAL);

        Flow first = service.value(Collections.singletonMap(560, 2L), T0).get(0);
        seam.quote = 190;
        Flow later = service.value(Collections.singletonMap(560, 2L), T0 + 1L).get(0);

        assertEquals(200L, first.valueDelta);
        assertEquals(380L, later.valueDelta);
        assertEquals("the already-booked flow keeps its frozen unit price", 100, first.unitPrice);
        assertEquals(190, later.unitPrice);
    }

    @Test
    public void manualOverrideWinsWithoutAnyAutomaticQuoteCall()
    {
        Seam seam = new Seam();
        seam.failIfAsked = true;
        GpManagerConfig config = override("560=150");
        ItemValuationService service = service(config, seam, NORMAL);

        Flow flow = service.value(Collections.singletonMap(560, 2L), T0).get(0);

        assertEquals(300L, flow.valueDelta);
        assertEquals(PriceSource.MANUAL_OVERRIDE, flow.getPriceSource());
        assertTrue("manual override never queries the automatic market", seam.asked.isEmpty());
    }

    @Test
    public void coinsAndPlatinumKeepFaceValueWithoutAnyQuoteCall()
    {
        Seam seam = new Seam();
        seam.failIfAsked = true;
        ItemValuationService service = service(new GpManagerConfig() {}, seam, UNSUPPORTED_SPECIAL);

        Flow coins = service.value(Collections.singletonMap(ItemID.COINS, 7L)).get(0);
        Flow platinum = service.value(Collections.singletonMap(ItemID.PLATINUM, 2L)).get(0);

        assertEquals(1, coins.unitPrice);
        assertEquals(PriceSource.FACE_VALUE, coins.getPriceSource());
        assertEquals(7L, coins.valueDelta);
        assertEquals(1_000, platinum.unitPrice);
        assertEquals(PriceSource.FACE_VALUE, platinum.getPriceSource());
        assertEquals(2_000L, platinum.valueDelta);
        assertTrue("face value is deterministic and never a market observation", seam.asked.isEmpty());
    }

    @Test
    public void deferredClaimBypassesTheAutomaticMarket()
    {
        Seam seam = new Seam();
        seam.failIfAsked = true;
        ItemValuationService service = service(new GpManagerConfig() {}, seam, NORMAL);

        Flow flow = service.value(Collections.singletonMap(ItemID.KONAR_KEY, 1L), T0).get(0);

        assertEquals(PriceSource.DEFERRED_CLAIM, flow.getPriceSource());
        assertEquals(0L, flow.valueDelta);
    }

    @Test
    public void unsupportedMappingStaysUnpricedDespiteAnAvailableQuote()
    {
        ItemValuationService service = new ItemValuationService(new GpManagerConfig() {}, id -> null,
            (id, active) -> 5_000, id -> id == 21_341, NORMAL);

        Flow minerals = service.value(Collections.singletonMap(21_341, 1L)).get(0);
        Flow ordinary = service.value(Collections.singletonMap(560, 1L)).get(0);

        assertEquals("a convenience mapping is not economic evidence",
            PriceSource.UNPRICED, minerals.getPriceSource());
        assertEquals(0L, minerals.valueDelta);
        assertEquals(5_000, ordinary.unitPrice);
    }

    @Test
    public void nonPositiveQuoteStaysUnpriced()
    {
        Seam seam = new Seam();
        seam.quote = 0;
        ItemValuationService service = service(new GpManagerConfig() {}, seam, NORMAL);
        assertEquals(PriceSource.UNPRICED,
            service.value(Collections.singletonMap(560, 1L)).get(0).getPriceSource());

        seam.quote = -5;
        assertEquals(PriceSource.UNPRICED,
            service.value(Collections.singletonMap(560, 1L)).get(0).getPriceSource());
    }

    @Test
    public void beyondMaxCashQuoteStaysUnpricedInsteadOfClamping()
    {
        assertEquals("an in-range quote is preserved", 184, ItemValuationService.unitPriceOf(184L));
        assertEquals("the last representable quote is preserved",
            Integer.MAX_VALUE, ItemValuationService.unitPriceOf(Integer.MAX_VALUE));
        assertEquals("a beyond-max-cash quote is not representable and stays unpriced",
            0, ItemValuationService.unitPriceOf(Integer.MAX_VALUE + 1L));
        assertEquals(0, ItemValuationService.unitPriceOf(0L));
        assertEquals(0, ItemValuationService.unitPriceOf(-5L));
    }

    @Test
    public void canonicalPathNeverDelegatesToTheUserDependentGetter()
    {
        String census = productionSource();
        assertFalse("no user-preference-dependent getItemPrice() call may remain",
            census.contains(".getItemPrice("));
        assertTrue("the explicit two-argument active route is the only production price call",
            census.contains("getItemPriceWithSource"));
    }

    @Test
    public void sourceCommentDescribesTheActiveRouteDecision()
    {
        String source = read("src/main/java/com/gpmanager/engine/ItemValuationService.java");
        assertTrue("the comment names GP Manager's own active market route",
            source.contains("active market route"));
        assertTrue("the comment states the user preference is ignored",
            source.contains("useWikiItemPrices"));
        assertTrue("the comment states history is never repriced",
            source.contains("never repriced"));
        assertTrue("the comment states RuneLite may fall back to guide pricing",
            source.contains("guide/Jagex"));
        assertFalse("no contradictory non-Wiki defense remains", source.contains("non-Wiki"));
    }

    // ── charges share the one policy ───────────────────────────────────────────────────────────

    @Test
    public void measuredChargeComponentsUseTheSameAutomaticPolicy()
    {
        Seam seam = new Seam();
        seam.quote = 100;
        ItemValuationService service = service(new GpManagerConfig() {}, seam, NORMAL);

        Flow component = service.valueChargeComponent(12934, -4L, T0 + 9L);

        assertEquals(PriceSource.GRAND_EXCHANGE, component.getPriceSource());
        assertEquals(-400L, component.valueDelta);
        assertEquals("charge components also request the explicit active route",
            Collections.singletonList(true), seam.activeFlags);
        assertEquals("component capture time remains the observation time", T0 + 9L,
            component.getPriceCapturedAtEpochMillis());
    }

    @Test
    public void measuredChargeManualOverrideIsValuedThroughTheSamePolicy()
    {
        Seam seam = new Seam();
        seam.failIfAsked = true;
        ItemValuationService service = service(override("12934=250"), seam, NORMAL);

        Flow component = service.valueChargeComponent(12934, -4L, T0);

        assertEquals(PriceSource.MANUAL_OVERRIDE, component.getPriceSource());
        assertEquals(-1_000L, component.valueDelta);
        assertTrue(seam.asked.isEmpty());
    }

    @Test
    public void everySupportedChargeFamilyPricesAndBooksUnderTheSharedPolicy()
    {
        String[][] families = {
            {"Your Trident of the seas has 2,000 charges.",
                "Your Trident of the seas has 1,990 charges.", "Trident of the seas"},
            {"Your Trident of the swamp has 2,000 charges.",
                "Your Trident of the swamp has 1,990 charges.", "Trident of the swamp"},
            {"Your Trident of the swamp (e) has 2,000 charges.",
                "Your Trident of the swamp (e) has 1,990 charges.", "Trident of the swamp (e)"},
            {"Your Tumeken's shadow has 2,000 charges remaining.",
                "Your Tumeken's shadow has 1,990 charges remaining.", "Tumeken's shadow"},
            {"Your Sanguinesti staff has 100 charges remaining.",
                "Your Sanguinesti staff has 98 charges remaining.", "Sanguinesti staff"},
            {"Your Holy sanguinesti staff has 100 charges remaining.",
                "Your Holy sanguinesti staff has 98 charges remaining.", "Holy sanguinesti staff"},
            {"Your warped sceptre has 100 charges remaining.",
                "Your warped sceptre has 98 charges remaining.", "Warped sceptre"},
            {"Your venator bow has 100 charges remaining.",
                "Your venator bow has 98 charges remaining.", "Venator bow"},
            {"Your echo venator bow has 100 charges remaining.",
                "Your echo venator bow has 98 charges remaining.", "Echo venator bow"},
            {"The pendant has 100 charges.",
                "The pendant has 98 charges.", "Pendant of Ates"},
            {"Your tome has been charged with Burnt Pages. It currently holds 1,000 charges.",
                "Your tome has been charged with Burnt Pages. It currently holds 980 charges.",
                "Tome of fire"},
            {"Your Amulet of blood fury will work for 6,627 more hits.",
                "Your Amulet of blood fury will work for 6,620 more hits.", "Amulet of blood fury"},
            {"Darts: Adamant dart x 1,000. Scales: 100 (1.0%).",
                "Darts: Adamant dart x 997. Scales: 96 (1.0%).", "Toxic blowpipe"},
        };
        for (String[] family : families)
        {
            Engine engine = new Engine(
                deltas -> Collections.<Flow>emptyList(), new TransactionClassifier(), config());
            engine.ensureSession(T0);
            assertNull(family[2], observe(engine, family[0], T0));
            ChargeDelta delta = observe(engine, family[1], T0 + 600L);
            assertNotNull(family[2], delta);

            Seam seam = new Seam();
            seam.quote = 100;
            ItemValuationService service = service(new GpManagerConfig() {}, seam, NORMAL);
            List<Flow> losses = new ArrayList<>();
            for (ChargeDelta.ComponentDelta component : delta.getComponentDeltas())
            {
                int id = component.getItemId();
                Flow valued = service.valueChargeComponent(id, component.getQuantityDelta(), T0 + 600L);
                int unit = valued.unitPrice;
                assertTrue(family[2] + " component " + id + " must be priceable", unit > 0);
                int perItem = ChargeRead.unitsPerPricedItem(delta.getVariant(), id);
                if (perItem > 1)
                {
                    unit = Math.max(1, unit / perItem);
                }
                long value = component.getQuantityDelta() * unit;
                losses.add(new Flow(id, "Item " + id, component.getQuantityDelta(), unit, value,
                    valued.getPriceSource(), T0 + 600L));
                assertFalse(family[2] + " component " + id + " must not be mapping-blocked",
                    ItemValuationService.hasUnsupportedRuneLiteMapping(
                        net.runelite.client.game.ItemMapping.map(id)));
            }

            Transaction result = engine.bookChargeSpend(delta, family[2], losses, T0 + 600L);
            assertTrue(family[2] + " books its measured spend", result != null && result.getNet() < 0L);
        }
    }

    // ── custody basis freezes under the shared policy ──────────────────────────────────────────

    @Test
    public void sellReferenceFreezesOnceAndUnknownLiquidationStaysNeutral()
    {
        Seam seam = new Seam();
        seam.quote = 6;
        Market market = new Market(service(new GpManagerConfig() {}, seam, NORMAL));
        market.inventory = with(market.inventory, RUNE, 10L);
        market.engine.setBaseline(new ContainerSnapshot(market.inventory));

        market.offer(0, GrandExchangeOfferState.SELLING, RUNE, 10, 0, 7, 0);
        market.settle(with(market.inventory, RUNE, 0L));
        seam.quote = 9;
        market.offer(0, GrandExchangeOfferState.SOLD, RUNE, 10, 10, 7, 70);
        market.settle(with(market.inventory, ItemID.COINS, 100_070L));

        MarketSettlementProjection.Row row = market.engine.getMarketSettlements().get(0);
        assertEquals("reference frozen at the placement quote", 60L, MarketFacts.basisValueGp(MarketFacts.record(market.engine, row)));
        assertEquals(70L, row.observedSettlementGp);
        assertEquals("a later quote never reprices the frozen reference", 60L,
            row.geReferenceGp);
        assertEquals("GE difference is execution information only", 10L, row.geDifferenceGp);
        assertEquals("pre-existing stock is UNKNOWN coverage, never zero",
            MarketSettlementProjection.Coverage.FULLY_UNKNOWN, row.coverage);
        assertEquals("unknown liquidation never becomes automatic Net", 0L,
            market.engine.getMetrics(market.now).net);
    }

    @Test
    public void manualOverrideReferenceStaysFrozenWhenTheOverrideChanges()
    {
        int[] overridePrice = {150};
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public String manualPriceOverrides()
            {
                return RUNE + "=" + overridePrice[0];
            }
        };
        Seam seam = new Seam();
        seam.failIfAsked = true;
        Market market = new Market(service(config, seam, NORMAL));
        market.inventory = with(market.inventory, RUNE, 10L);
        market.engine.setBaseline(new ContainerSnapshot(market.inventory));

        market.offer(0, GrandExchangeOfferState.SELLING, RUNE, 10, 0, 7, 0);
        market.settle(with(market.inventory, RUNE, 0L));
        overridePrice[0] = 200;
        market.offer(0, GrandExchangeOfferState.SOLD, RUNE, 10, 10, 7, 70);
        market.settle(with(market.inventory, ItemID.COINS, 100_070L));

        MarketSettlementProjection.Row row = market.engine.getMarketSettlements().get(0);
        assertEquals("editing an override never reprices the frozen reference", 1_470L,
            row.geReferenceGp);
        assertEquals("after-tax cash against the tax-adjusted frozen reference", -1_400L,
            row.geDifferenceGp);
        assertEquals(0L, market.engine.getMetrics(market.now).net);
    }

    @Test
    public void buyAcquisitionBasisUsesTheExactSpend()
    {
        Seam seam = new Seam();
        seam.quote = 186;
        Market market = new Market(service(new GpManagerConfig() {}, seam, NORMAL));

        market.offer(0, GrandExchangeOfferState.BUYING, RUNE, 200, 0, 180, 0);
        market.settle(with(market.inventory, ItemID.COINS, 100_000L - 36_000L));
        market.offer(0, GrandExchangeOfferState.BOUGHT, RUNE, 200, 200, 180, 36_000);
        market.settle(with(market.inventory, ItemID.COINS, 100_000L - 36_000L, RUNE, 200L));

        MarketSettlementProjection.Row row = market.engine.getMarketSettlements().get(0);
        assertEquals("the frozen reference stays the placement quote", 37_200L,
            MarketFacts.basisValueGp(MarketFacts.record(market.engine, row)));
        assertEquals(36_000L, row.observedSettlementGp);
        assertEquals("the asset value is the exact spend", 36_000L,
            row.trackedBasisConsumedGp);
        assertEquals("a NEW BUY is Net-neutral", 0L, row.realizedResultGp);
        assertEquals("GE difference = reference - spend", 1_200L, row.geDifferenceGp);
        assertEquals(0L, market.engine.getMetrics(market.now).net);
        assertEquals("the acquired quantity becomes known coverage", 200L,
            EngineProbe.knownCoverageQty(market.engine, RUNE));
        assertEquals(36_000L, EngineProbe.knownCoverageBasisGp(market.engine, RUNE));
    }

    // ── provenance and history ─────────────────────────────────────────────────────────────────

    @Test
    public void deathReclaimFeeCoinsUseFaceValueWithoutChangingAmounts()
    {
        Engine engine = new Engine(
            deltas -> Collections.<Flow>emptyList(), new TransactionClassifier(), config());
        engine.ensureSession(T0);

        Transaction fee = engine.bookDeathReclaimFee(1_000L, "Grave fee", T0 + 1L);

        assertNotNull(fee);
        assertEquals(TransactionType.PK_FEE, fee.getType());
        assertTrue(fee.isCounted());
        Flow flow = fee.getFlows().get(0);
        assertEquals(ItemID.COINS, flow.itemId);
        assertEquals(PriceSource.FACE_VALUE, flow.getPriceSource());
        assertEquals(-1_000L, flow.valueDelta);
        assertEquals(-1_000L, engine.getMetrics(T0 + 1L).net);
    }

    @Test
    public void fixedCurrencyConstructionCensusFindsNoOtherGrandExchangeStamp()
    {
        assertFalse("the death-reclaim fee no longer claims market provenance",
            read("src/main/java/com/gpmanager/engine/Engine.java")
                .contains("Av.GRAND_EXCHANGE"));
        assertFalse("charge intake no longer prices components itself",
            read("src/main/java/com/gpmanager/ChargeIntake.java").contains("getItemPrice("));
    }

    @Test
    public void aGrindMayKeepOldGuidePricedAndNewActivePricedReceipts()
    {
        Seam seam = new Seam();
        seam.quote = 190;
        ItemValuationService service = service(new GpManagerConfig() {}, seam, NORMAL);
        Engine engine = new Engine(service, new TransactionClassifier(), config());
        engine.ensureSession(T0);
        Session session = engine.getActiveSession();

        // An older receipt frozen under the previous explicit route: unit 184, no capture time.
        session.addTransaction(new Transaction(T0 + 1L, null, TransactionType.GAIN,
            Context.GENERIC, "", "Old drop", true,
            Collections.singletonList(new Flow(560, "Death rune", 200L, 184, 36_800L,
                PriceSource.GRAND_EXCHANGE)),
            ClassificationConfidence.CONFIRMED, "", null), 100);
        // A new receipt booked under the unified policy.
        Flow newFlow = service.value(Collections.singletonMap(560, 200L), T0 + 2L).get(0);
        session.addTransaction(new Transaction(T0 + 2L, null, TransactionType.GAIN,
            Context.GENERIC, "", "New drop", true, Collections.singletonList(newFlow),
            ClassificationConfidence.CONFIRMED, "", null), 100);

        assertEquals(184, session.getTransactions().get(0).getFlows().get(0).unitPrice);
        assertEquals(190, session.getTransactions().get(1).getFlows().get(0).unitPrice);
        assertEquals("both frozen bookings sum honestly", 36_800L + 38_000L,
            engine.getMetrics(T0 + 3L).revenue);
    }

    @Test
    public void schemaIs107AndNoWikiPriceSourceWasAdded()
    {
        assertEquals(108, SavedState.CURRENT_SCHEMA_VERSION);
        for (PriceSource source : PriceSource.values())
        {
            assertFalse("no new active/wiki enum constant: " + source,
                source.name().contains("WIKI"));
        }
        assertEquals("RuneLite market", PriceSource.GRAND_EXCHANGE.toString());
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────

    private static final class Seam
    {
        final List<Boolean> activeFlags = new ArrayList<>();
        final List<Integer> asked = new ArrayList<>();
        int quote = 100;
        boolean failIfAsked;
    }

    private static ItemValuationService service(GpManagerConfig config, Seam seam,
        ItemValuationService.EconomyState state)
    {
        return new ItemValuationService(config, id -> null, (id, active) ->
        {
            if (seam.failIfAsked)
            {
                throw new AssertionError("automatic market must not be queried");
            }
            seam.activeFlags.add(active);
            seam.asked.add(id);
            return seam.quote;
        }, id -> false, state);
    }

    private static GpManagerConfig override(String overrides)
    {
        return new GpManagerConfig()
        {
            @Override public String manualPriceOverrides()
            {
                return overrides;
            }
        };
    }

    private static GpManagerConfig config()
    {
        return new GpManagerConfig()
        {
            @Override public int stabilizationTicks()
            {
                return 0;
            }
        };
    }

    private static ChargeDelta observe(Engine engine, String chat, long now)
    {
        ChargeRead read = ChargeRead.parseCheckMessage(chat);
        assertNotNull(chat, read);
        ChargeDelta delta = engine.observeMeasuredChargeRead(read, "149:5:0:22323:X", now);
        return delta == null || delta.getComponentDeltas().isEmpty() ? null : delta;
    }

    private static Map<Integer, Long> with(Map<Integer, Long> base, Object... pairs)
    {
        Map<Integer, Long> map = new HashMap<>(base);
        for (int i = 0; i < pairs.length; i += 2)
        {
            long quantity = (Long) pairs[i + 1];
            if (quantity <= 0L)
            {
                map.remove((Integer) pairs[i]);
            }
            else
            {
                map.put((Integer) pairs[i], quantity);
            }
        }
        return map;
    }

    private static String name(int id)
    {
        if (id == ItemID.COINS)
        {
            return "Coins";
        }
        return id == RUNE ? "Nature rune" : "Item " + id;
    }

    private static final class Market
    {
        final Engine engine;
        final OfferLedger ledger = new OfferLedger();
        long now = T0;
        Map<Integer, Long> inventory = new HashMap<>();

        Market(ItemValuationService service)
        {
            engine = new Engine(service, new TransactionClassifier(), config());
            engine.startCustomSession("Trading", SessionMode.AUTO, now);
            inventory.put(ItemID.COINS, 100_000L);
            engine.setBaseline(new ContainerSnapshot(inventory));
        }

        void offer(int slot, GrandExchangeOfferState state, int item, int total, int traded,
            int price, int spent)
        {
            now += 600L;
            OfferLedger.Transition transition = ledger.observe(
                new OfferLedger.Snapshot(slot, state, item, total, traded, price, spent)).orElse(null);
            if (transition != null)
            {
                engine.noteGeOfferObservation(transition,
                    name(transition.current.itemId), now);
            }
        }

        void settle(Map<Integer, Long> next)
        {
            now += 600L;
            inventory = new HashMap<>(next);
            engine.markInventoryDirty();
            ContainerSnapshot snapshot = new ContainerSnapshot(inventory);
            for (int i = 0; i < 3; i++)
            {
                engine.processIfDirty(snapshot, now);
                now += 600L;
            }
        }
    }

    private static final ItemValuationService.EconomyState NORMAL =
        ItemValuationService.EconomyState.NORMAL;
    private static final ItemValuationService.EconomyState UNSUPPORTED_SPECIAL =
        ItemValuationService.EconomyState.UNSUPPORTED_SPECIAL;

    private static String read(String relative)
    {
        try
        {
            return new String(Files.readAllBytes(Paths.get(relative)), StandardCharsets.UTF_8);
        }
        catch (Exception ex)
        {
            throw new AssertionError("Unable to read " + relative, ex);
        }
    }

    private static String productionSource()
    {
        try (Stream<Path> files = Files.walk(Paths.get("src/main/java")))
        {
            return files.filter(path -> path.toString().endsWith(".java"))
                .map(path ->
                {
                    try
                    {
                        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                    }
                    catch (Exception ex)
                    {
                        throw new AssertionError("Unable to read " + path, ex);
                    }
                })
                .collect(Collectors.joining("\n"));
        }
        catch (Exception ex)
        {
            throw new AssertionError("Unable to census production sources", ex);
        }
    }
}
