package com.gpmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static net.runelite.api.GrandExchangeOfferState.BOUGHT;
import static net.runelite.api.GrandExchangeOfferState.BUYING;
import static net.runelite.api.GrandExchangeOfferState.EMPTY;
import static net.runelite.api.GrandExchangeOfferState.SELLING;
import static net.runelite.api.GrandExchangeOfferState.SOLD;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Schema-104 persistence contract: custody continuity is profile-scoped, bounded, resumes exact
 * execution across restart, guards idempotency with the settled counters, and quarantines
 * offers already open at the schema-104 boundary as legacy/unbased.
 */
public class GeCustodyPersistenceTest
{
    private static final int RUNE = ItemID.NATURERUNE;
    private static final int IRON_ORE = ItemID.IRON_ORE;
    private static final int COINS = ItemID.COINS;
    /** The owner's true four-sale reference items. */
    private static final int CHAOS_RUNE = ItemID.CHAOSRUNE;
    private static final int OAK_LOGS = ItemID.OAK_LOGS;
    private static final int DIAMOND_BOLTS_E =
        ItemID.XBOWS_CROSSBOW_BOLTS_ADAMANTITE_TIPPED_DIAMOND_ENCHANTED;
    private static final int SAPPHIRE_DRAGON_BOLTS_E = ItemID.DRAGON_BOLTS_ENCHANTED_SAPPHIRE;
    /** The owner's true seven-sale Collect All batch items. */
    private static final int DRAGONFRUIT = ItemID.DRAGONFRUIT;
    private static final int TEAK_PLANK = ItemID.PLANK_TEAK;
    private static final int BOLT_OF_LINEN = ItemID.BOLT_OF_LINEN;
    private static final int ANGLERFISH = ItemID.ANGLERFISH;
    private static final int COOKED_KARAMBWAN = ItemID.TBWT_COOKED_KARAMBWAN;
    private static final int SHARK = ItemID.SHARK;
    private static final int COOKED_CHICKEN = ItemID.COOKED_CHICKEN;
    /**
     * The owner's real Collect All batch, exactly as the game executed it: gross 25,030 gp,
     * actual collected cash 24,550 gp, exact tax 480 gp. The earlier 25,035/485 estimate assumed
     * a taxed 70 gp chicken; the live game exempted the 69 gp cooked chicken (wiki exemption
     * list), which the RuneLite trade history and the collected cash both prove.
     */
    private static final int[] OWNER_BATCH_ITEMS = {DRAGONFRUIT, TEAK_PLANK, BOLT_OF_LINEN,
        ANGLERFISH, COOKED_KARAMBWAN, SHARK, COOKED_CHICKEN};
    private static final int[] OWNER_BATCH_LISTED = {685, 792, 369, 1_577, 399, 946, 69};
    private static final long[] OWNER_BATCH_EXECUTION = {3_575L, 4_375L, 1_915L, 8_025L,
        1_995L, 4_800L, 345L};
    private static final long[] OWNER_BATCH_RECEIVED = {3_505L, 4_290L, 1_880L, 7_865L,
        1_960L, 4_705L, 345L};
    private static final long[] OWNER_BATCH_TAX = {70L, 85L, 35L, 160L, 35L, 95L, 0L};
    private static final long OWNER_BATCH_GROSS_TOTAL = 25_030L;
    private static final long OWNER_BATCH_TAX_TOTAL = 480L;
    private static final long OWNER_BATCH_RECEIVED_TOTAL = 24_550L;
    private static final long T0 = 1_000_000_000_000L;

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    @Test
    public void schemaIs104AndCustodyRoundTripsInsideTheCanonicalGeneration()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        engine.setBaseline(new ContainerSnapshot(inventory(COINS, 100_000L, RUNE, 10L)));

        OfferLedger ledger = new OfferLedger();
        noteGe(engine, ledger, 0, SELLING, RUNE, 10, 0, 7, 0, now);
        settle(engine, inventory(COINS, 100_000L, RUNE, 0L), now);

        SavedState state = engine.createSavedState();
        assertEquals(SavedState.CURRENT_SCHEMA_VERSION, state.schemaVersion);
        assertEquals(108, state.schemaVersion);
        assertEquals(1, state.getGeCustody().size());
        assertEquals(10L, state.getGeCustody().get(0).getCapturedQty());

        Engine restored = engine();
        restored.restore(state, now + 1_000L);
        assertEquals("custody continuity survives restart", 1,
            restored.geCustody.snapshotRecords().size());
        assertEquals(10L, restored.geCustody.snapshotRecords().get(0).getCapturedQty());
        assertEquals("resumed confidence after restore",
            GeRecord.Confidence.RESUMED,
            restored.geCustody.snapshotRecords().get(0).getConfidence());
    }

    @Test
    public void resumedOfferContinuesExactExecutionAndSettlesOnce()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        engine.setBaseline(new ContainerSnapshot(inventory(COINS, 100_000L, RUNE, 10L)));
        gain(engine, RUNE, 10L, 60L, now - 60_000L);

        OfferLedger ledger = new OfferLedger();
        noteGe(engine, ledger, 0, SELLING, RUNE, 10, 0, 7, 0, now);
        settle(engine, inventory(COINS, 100_000L, RUNE, 0L), now);
        noteGe(engine, ledger, 0, SELLING, RUNE, 10, 4, 7, 28, now + 1_000L);
        SavedState state = engine.createSavedState();

        Engine restored = engine();
        restored.restore(state, now + 2_000L);
        restored.resume(now + 2_500L, PauseReason.IDLE, PauseReason.RECOVERY);
        prime(restored, inventory(COINS, 100_000L), now + 2_600L);
        // Login seed resumes the same offer; while-away progress is adopted as exact cumulative.
        Map<Integer, OfferLedger.Snapshot> slots = new HashMap<>();
        slots.put(0, new OfferLedger.Snapshot(0, SOLD, RUNE, 10, 10, 7, 70));
        restored.seedGeOfferSlots(slots, now + 2_500L);

        Transaction settlement = settle(restored, inventory(COINS, 100_070L), now + 3_500L);

        assertNotNull(settlement);
        assertEquals(TransactionType.TRADE, settlement.getType());
        assertTrue(settlement.isCounted());
        assertEquals("resumed basis still consumes the exact known coverage",
            -60L, flow(settlement, RUNE));
        assertEquals(70L, flow(settlement, COINS));
        assertEquals("the counted value plus the realized edge", 70L,
            restored.getMetrics(now + 4_500L).net);

        // A second replay of the same terminal collection must never book again.
        Transaction again = settle(restored, inventory(COINS, 100_070L), now + 5_000L);
        assertTrue(again == null || !again.isCounted());
        assertEquals("only the resumed settlement is counted beyond the gain", 2,
            countCounted(restored));
    }

    /** Owner report: an offer that sold in yesterday's client session is collected today. */
    @Test
    public void saleFromAnEarlierClientSessionCollectedNextDaySettlesWithoutReview()
    {
        for (boolean soldWhileOnline : new boolean[]{true, false})
        {
            Engine engine = engine(210L);
            long now = T0;
            engine.startCustomSession("Trading", SessionMode.AUTO, now);
            engine.setBaseline(new ContainerSnapshot(inventory(COINS, 100_000L, RUNE, 26L)));
            OfferLedger ledger = new OfferLedger();
            noteGe(engine, ledger, 0, SELLING, RUNE, 26, 0, 210, 0, now);
            settle(engine, inventory(COINS, 100_000L, RUNE, 0L), now + 200L);
            if (soldWhileOnline)
            {
                noteGe(engine, ledger, 0, SOLD, RUNE, 26, 26, 210, 5_460, now + 60_000L);
            }
            SavedState state = engine.createSavedState();

            long tomorrow = now + 20L * 60L * 60L * 1000L;
            Engine restored = engine(210L);
            restored.restore(state, tomorrow);
            restored.resume(tomorrow, PauseReason.IDLE, PauseReason.RECOVERY);
            prime(restored, inventory(COINS, 100_000L), tomorrow);
            Map<Integer, OfferLedger.Snapshot> slots = new HashMap<>();
            slots.put(0, new OfferLedger.Snapshot(0, SOLD, RUNE, 26, 26, 210, 5_460));
            restored.seedGeOfferSlots(slots, tomorrow + 2_000L);

            restored.noteGeCollectionIntent(tomorrow + 60_000L);
            settle(restored, inventory(COINS, 105_356L), tomorrow + 60_000L);
            OfferLedger live = new OfferLedger();
            live.observe(new OfferLedger.Snapshot(0, SOLD, RUNE, 26, 26, 210, 5_460));
            OfferLedger.Transition cleared = live.observe(
                new OfferLedger.Snapshot(0, EMPTY, 0, 0, 0, 0, 0)).orElse(null);
            if (cleared != null)
            {
                restored.noteGeOfferObservation(cleared, "", tomorrow + 60_600L);
            }
            for (Transaction booking : restored.geCustody.maintenance(tomorrow + 70_000L,
                restored.getActiveSession().getId()).countedBookings)
            {
                restored.getActiveSession().addTransaction(booking, 500, true);
            }

            assertEquals("sold " + (soldWhileOnline ? "online" : "offline") + ": no Review row", 0,
                reviewCount(restored));
            assertEquals(0, countUncertainCoinRows(restored));
            assertEquals(MarketSettlementProjection.Lifecycle.REALIZED,
                restored.getMarketSettlements().get(0).lifecycle);
        }
    }

    /**
     * The login seed can run before the server restores the offers: RuneLite first reports every
     * slot EMPTY. The yesterday's SOLD offer then arrives as a late replay; it resumes the same
     * lifecycle instead of looking like slot reuse, and today's collect settles without Review.
     */
    @Test
    public void lateLoginReplayOfYesterdaysSaleResumesTheLifecycle()
    {
        for (boolean soldWhileOnline : new boolean[]{true, false})
        {
            Engine engine = engine(210L);
            long now = T0;
            engine.startCustomSession("Trading", SessionMode.AUTO, now);
            engine.setBaseline(new ContainerSnapshot(inventory(COINS, 100_000L, RUNE, 26L)));
            OfferLedger ledger = new OfferLedger();
            noteGe(engine, ledger, 0, SELLING, RUNE, 26, 0, 210, 0, now);
            settle(engine, inventory(COINS, 100_000L, RUNE, 0L), now + 200L);
            if (soldWhileOnline)
            {
                noteGe(engine, ledger, 0, SOLD, RUNE, 26, 26, 210, 5_460, now + 60_000L);
            }
            SavedState state = engine.createSavedState();

            long tomorrow = now + 20L * 60L * 60L * 1000L;
            Engine restored = engine(210L);
            restored.restore(state, tomorrow);
            restored.resume(tomorrow, PauseReason.IDLE, PauseReason.RECOVERY);
            prime(restored, inventory(COINS, 100_000L), tomorrow);
            // The seed only saw the login EMPTY placeholders.
            OfferLedger live = new OfferLedger();
            live.beginLoginSeed();
            Map<Integer, OfferLedger.Snapshot> slots = new HashMap<>();
            for (int slot = 0; slot < 8; slot++)
            {
                OfferLedger.Snapshot empty = new OfferLedger.Snapshot(slot, EMPTY, 0, 0, 0, 0, 0);
                live.observe(empty);
                slots.put(slot, empty);
            }
            live.finishLoginSeed();
            restored.seedGeOfferSlots(slots, tomorrow + 2_000L);
            // The server's restored offer arrives a few ticks later.
            noteGe(restored, live, 0, SOLD, RUNE, 26, 26, 210, 5_460, tomorrow + 4_000L);

            restored.noteGeCollectionIntent(tomorrow + 60_000L);
            settle(restored, inventory(COINS, 105_356L), tomorrow + 60_000L);
            noteGe(restored, live, 0, EMPTY, 0, 0, 0, 0, 0, tomorrow + 60_600L);
            for (Transaction booking : restored.geCustody.maintenance(tomorrow + 70_000L,
                restored.getActiveSession().getId()).countedBookings)
            {
                restored.getActiveSession().addTransaction(booking, 500, true);
            }

            String label = "sold " + (soldWhileOnline ? "online" : "offline");
            assertEquals(label + ": no Review row", 0, reviewCount(restored));
            assertEquals(label, 0, countUncertainCoinRows(restored));
            assertEquals(label, 1, restored.getMarketSettlements().size());
            assertEquals(label, MarketSettlementProjection.Lifecycle.REALIZED,
                restored.getMarketSettlements().get(0).lifecycle);
        }
    }

    /**
     * Owner report 2026-09-28: two iron ore buys (20 at 76) filled at once at 75 each. The instant
     * fill made the placement reserve and the 20 coins change each a Coins Review row. Both must be
     * GE custody: no Review, Net unchanged, two realized purchases, and both offers closed.
     */
    @Test
    public void instantlyFilledBuysKeepReserveAndChangeInCustody()
    {
        for (boolean changeBeforeClear : new boolean[]{true, false})
        {
            Engine engine = engine(73L);
            long now = T0;
            engine.startCustomSession("Mining", SessionMode.AUTO, now);
            Map<Integer, Long> held = inventory(COINS, 100_000L);
            engine.setBaseline(new ContainerSnapshot(new HashMap<>(held)));
            OfferLedger ledger = new OfferLedger();
            for (int slot = 0; slot < 2; slot++)
            {
                long at = now + slot * 5_000L;
                noteGe(engine, ledger, slot, EMPTY, 0, 0, 0, 0, 0, at);
                noteGe(engine, ledger, slot, BUYING, IRON_ORE, 20, 0, 76, 0, at);
                // The market filled it before the coins leaving the inventory settled.
                noteGe(engine, ledger, slot, BOUGHT, IRON_ORE, 20, 20, 76, 1_500, at + 100L);
                held.put(COINS, held.get(COINS) - 1_520L);
                settle(engine, new HashMap<>(held), at + 600L);
            }
            for (int slot = 0; slot < 2; slot++)
            {
                long at = now + 40_000L + slot * 10_000L;
                engine.noteGeCollectionIntent(at);
                engine.markContext(Context.MARKET, 10, "Grand Exchange collect");
                if (!changeBeforeClear)
                {
                    noteGe(engine, ledger, slot, EMPTY, 0, 0, 0, 0, 0, at);
                }
                held.merge(IRON_ORE, 20L, Long::sum);
                held.merge(COINS, 20L, Long::sum);
                settle(engine, new HashMap<>(held), at + 100L);
                if (changeBeforeClear)
                {
                    noteGe(engine, ledger, slot, EMPTY, 0, 0, 0, 0, 0, at + 1_300L);
                }
            }
            for (Transaction booking : engine.geCustody.maintenance(now + 20L * 60_000L,
                engine.getActiveSession().getId()).countedBookings)
            {
                engine.getActiveSession().addTransaction(booking, 500, true);
            }

            String label = changeBeforeClear ? "change before clear" : "clear before change";
            assertEquals(label + ": no Review row", 0, reviewCount(engine));
            assertEquals(label + ": a buy at its own spend is Net-neutral", 0L,
                engine.getMetrics(now + 21L * 60_000L).net);
            assertEquals(label, 2, engine.getMarketSettlements().size());
            for (MarketSettlementProjection.Row row : engine.getMarketSettlements())
            {
                assertEquals(label, MarketSettlementProjection.Lifecycle.REALIZED, row.lifecycle);
            }
            for (GeRecord record : engine.geCustody.snapshotRecords())
            {
                assertEquals(label + ": reserve = spend + change, so the offer closes",
                    GeRecord.Stage.CLOSED, record.getStage());
            }
        }
    }

    @Test
    public void legacyOpenOfferAtSeedIsQuarantinedWithoutInventedBasis()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        engine.setBaseline(new ContainerSnapshot(inventory(COINS, 100_000L, RUNE, 10L)));

        Map<Integer, OfferLedger.Snapshot> slots = new HashMap<>();
        slots.put(0, new OfferLedger.Snapshot(0, SELLING, RUNE, 10, 4, 7, 28));
        engine.seedGeOfferSlots(slots, now);

        List<GeRecord> records = engine.geCustody.snapshotRecords();
        assertEquals(1, records.size());
        assertEquals(GeRecord.Confidence.LEGACY_UNBASED, records.get(0).getConfidence());
        assertFalse("no basis is invented for a pre-upgrade offer",
            records.get(0).hasFrozenBasis());
        assertEquals("the persisted cumulative is adopted as a baseline only", 4L,
            records.get(0).getFilledQty());

        // Its movements follow the ordinary classification path; custody records nothing.
        Transaction loss = settle(engine, inventory(COINS, 100_000L, RUNE, 6L), now + 1_000L);
        assertNotNull(loss);
        assertEquals(records.size(), engine.geCustody.snapshotRecords().size());
    }

    @Test
    public void profileIsolationReplacesCustodyState()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        engine.setBaseline(new ContainerSnapshot(inventory(COINS, 100_000L, RUNE, 10L)));

        OfferLedger ledger = new OfferLedger();
        noteGe(engine, ledger, 0, SELLING, RUNE, 10, 0, 7, 0, now);
        settle(engine, inventory(COINS, 100_000L, RUNE, 0L), now);
        SavedState profileA = engine.createSavedState();
        profileA.setOwnerKey("rsprofile.ge-audit-a");
        assertEquals(1, profileA.getGeCustody().size());

        engine.restoreForProfile("rsprofile.ge-audit-b", new SavedState(), now + 1_000L);
        assertTrue("another profile never sees the previous custody state",
            engine.geCustody.snapshotRecords().isEmpty());

        engine.restoreForProfile("rsprofile.ge-audit-a", profileA, now + 2_000L);
        assertEquals("the owning profile recovers exactly its own custody state",
            1, engine.geCustody.snapshotRecords().size());
    }

    @Test
    public void missingOrCorruptCustodyFieldsFailClosed()
    {
        SavedState state = new SavedState();
        state.setSchemaVersion(103);
        assertTrue("pre-104 files carry no custody", state.getGeCustody().isEmpty());

        List<GeRecord> records = new ArrayList<>();
        GeRecord invalid = new GeRecord();
        records.add(invalid);
        state.setGeCustody(records);
        assertTrue("records without a stable offer id are dropped",
            state.getGeCustody().isEmpty());

        GeRecord unknownEnums = new GeRecord("offer-1", 0, GeRecord.Side.BUY,
            RUNE, "Nature rune", 10L, 7L, T0, "");
        unknownEnums.setStage(GeRecord.Stage.OPEN);
        unknownEnums.setConfidence(GeRecord.Confidence.LEGACY_UNBASED);
        state.setGeCustody(Collections.singletonList(unknownEnums));

        Engine engine = engine();
        engine.restore(state, T0 + 1_000L);
        assertEquals(1, engine.geCustody.snapshotRecords().size());
        assertEquals("legacy/unbased records never invent basis",
false, engine.geCustody.snapshotRecords().get(0).hasFrozenBasis());
    }

    @Test
    public void crossGrindSettlementBooksWhereCollectedWithoutReview()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustomSession("Grind A", SessionMode.AUTO, now);
        engine.setBaseline(new ContainerSnapshot(inventory(COINS, 100_000L, RUNE, 10L)));

        OfferLedger ledger = new OfferLedger();
        noteGe(engine, ledger, 0, SELLING, RUNE, 10, 0, 7, 0, now);
        settle(engine, inventory(COINS, 100_000L, RUNE, 0L), now);
        noteGe(engine, ledger, 0, SOLD, RUNE, 10, 10, 7, 70, now + 500L);

        // A different Grind becomes current before the collection arrives.
        engine.finishCustomSession(now + 1_000L);
        engine.startCustomSession("Grind B", SessionMode.AUTO, now + 1_100L);
        engine.setBaseline(new ContainerSnapshot(inventory(COINS, 100_000L)));

        Transaction settlement = settle(engine, inventory(COINS, 100_070L), now + 2_000L);
        assertNotNull(settlement);
        // Owner policy 2026-09-24 (replaces PRE-R5B 11.19): the exact settlement books in the
        // collecting Grind; the closed origin Grind is never rewritten.
        assertEquals("a cross-Grind settlement is an exact market settlement",
            TransactionType.TRADE, settlement.getType());
        assertFalse("never a Review nag", ReviewEligibility.needsOwnerDecision(settlement));
        assertTrue("booked in the collecting Grind",
            engine.getActiveSession().getTransactions().contains(settlement));
        assertEquals("an untaxed unknown-basis sale stays Net-neutral", 0L,
            engine.getMetrics(now + 3_000L).net);
    }

    @Test
    public void batchCollectAllKeepsExactCombinedAdjustment()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        engine.setBaseline(new ContainerSnapshot(inventory(COINS, 100_000L, RUNE, 20L)));
        gain(engine, RUNE, 20L, 120L, now - 60_000L);

        OfferLedger ledger = new OfferLedger();
        noteGe(engine, ledger, 0, SELLING, RUNE, 10, 0, 7, 0, now);
        settle(engine, inventory(COINS, 100_000L, RUNE, 10L), now);
        noteGe(engine, ledger, 1, SELLING, RUNE, 10, 0, 7, 0, now + 200L);
        settle(engine, inventory(COINS, 100_000L, RUNE, 0L), now + 400L);
        noteGe(engine, ledger, 0, SOLD, RUNE, 10, 10, 7, 70, now + 600L);
        noteGe(engine, ledger, 1, SOLD, RUNE, 10, 10, 7, 70, now + 800L);

        // One combined collection of 138 coins for gross execution 140 (2 coins of shortfall).
        Transaction last = settle(engine, inventory(COINS, 100_138L), now + 1_000L);
        assertNotNull(last);
        long total = 0L;
        int countedRows = 0;
        for (Transaction transaction : engine.getActiveSession().getTransactions())
        {
            if (transaction != null && transaction.isCounted()
                && transaction.getAutomaticType() == TransactionType.TRADE)
            {
                countedRows++;
                total += transaction.getNet();
            }
        }
        assertEquals("per-offer executions plus one batch adjustment", 3, countedRows);
        assertEquals("the counted value plus the exact realized edge equals the observed cash",
            138L, engine.getMetrics(now + 2_000L).net);
        assertEquals(18L, total);
    }

    @Test
    public void ownerFourSaleBatchAttributesAfterTaxCashAndTaxAdjustedReference()
    {
        Engine engine = ownerEngine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        Map<Integer, Long> held = inventory(COINS, 100_000L, CHAOS_RUNE, 1_000L, OAK_LOGS, 50L,
            DIAMOND_BOLTS_E, 10L, SAPPHIRE_DRAGON_BOLTS_E, 50L);
        engine.setBaseline(new ContainerSnapshot(held));

        OfferLedger ledger = new OfferLedger();
        int[] items = {CHAOS_RUNE, OAK_LOGS, DIAMOND_BOLTS_E, SAPPHIRE_DRAGON_BOLTS_E};
        long[] quantity = {1_000L, 50L, 10L, 50L};
        long[] reference = {104L, 41L, 157L, 2_802L};
        long[] gross = {104_000L, 2_000L, 1_510L, 133_450L};
        long[] tax = {2_000L, 0L, 30L, 2_650L};
        long[] received = {102_000L, 2_000L, 1_480L, 130_800L};
        long[] grossReference = {104_000L, 2_050L, 1_570L, 140_100L};
        long[] expectedReferenceTax = {2_000L, 0L, 30L, 2_800L};
        long[] netReference = {102_000L, 2_050L, 1_540L, 137_300L};
        long[] difference = {0L, -50L, -60L, -6_500L};
        for (int slot = 0; slot < items.length; slot++)
        {
            noteGe(engine, ledger, slot, SELLING, items[slot], (int) quantity[slot], 0,
                (int) reference[slot], 0, now + slot * 2_000L);
            held.put(items[slot], held.get(items[slot]) - quantity[slot]);
            settle(engine, held, now + slot * 2_000L + 200L);
            noteGe(engine, ledger, slot, SOLD, items[slot], (int) quantity[slot],
                (int) quantity[slot], (int) reference[slot], (int) gross[slot],
                now + slot * 2_000L + 1_000L);
        }
        // One collection pays exactly the after-tax 236,280 for 240,960 gross execution.
        settle(engine, inventory(COINS, 336_280L, CHAOS_RUNE, 0L, OAK_LOGS, 0L,
            DIAMOND_BOLTS_E, 0L, SAPPHIRE_DRAGON_BOLTS_E, 0L), now + 10_000L);

        Map<Integer, Integer> slotByItem = new HashMap<>();
        for (int slot = 0; slot < items.length; slot++)
        {
            slotByItem.put(items[slot], slot);
        }
        long grossTotal = 0L;
        long taxTotal = 0L;
        long receivedTotal = 0L;
        long referenceTotal = 0L;
        long differenceTotal = 0L;
        int rows = 0;
        for (MarketSettlementProjection.Row row : engine.getMarketSettlements())
        {
            int slot = slotByItem.get(row.itemId);
            rows++;
            assertEquals("Received is the actual after-tax cash", received[slot],
                row.observedSettlementGp);
            assertEquals("actual tax is decomposed, never booked", -tax[slot],
                row.settlementAdjustmentGp);
            assertEquals(tax[slot], row.inferredGeTaxGp);
            assertEquals(gross[slot], MarketFacts.realizedGrossGp(MarketFacts.record(engine, row)));
            assertEquals(MarketSettlementProjection.Coverage.FULLY_UNKNOWN, row.coverage);
            assertEquals(0L, MarketFacts.trackedQtyConsumed(MarketFacts.record(engine, row)));
            assertEquals(quantity[slot], MarketFacts.unknownQtyRealized(MarketFacts.record(engine, row)));
            assertEquals(MarketSettlementProjection.Lifecycle.REALIZED, row.lifecycle);
            assertFalse(row.manualFinancialResult);
            assertEquals(grossReference[slot], MarketFacts.grossGeReferenceGp(MarketFacts.record(engine, row)));
            assertEquals(expectedReferenceTax[slot], row.expectedReferenceTaxGp);
            assertEquals(netReference[slot], row.geReferenceGp);
            assertEquals(difference[slot], row.geDifferenceGp);
            grossTotal += MarketFacts.realizedGrossGp(MarketFacts.record(engine, row));
            taxTotal += -row.settlementAdjustmentGp;
            receivedTotal += row.observedSettlementGp;
            referenceTotal += row.geReferenceGp;
            differenceTotal += row.geDifferenceGp;
        }
        assertEquals(4, rows);
        assertEquals(240_960L, grossTotal);
        assertEquals(4_680L, taxTotal);
        assertEquals(236_280L, receivedTotal);
        assertEquals(242_890L, referenceTotal);
        assertEquals("the individual differences sum exactly", -6_610L, differenceTotal);
        assertEquals("unknown bank stock contributes exactly the proven tax per sale",
            -4_680L, engine.getMetrics(now + 12_000L).net);
        for (Transaction transaction : engine.getActiveSession().getTransactions())
        {
            assertFalse("no counted Coins fee row may exist: " + transaction,
                transaction != null && transaction.isCounted()
                    && transaction.getAutomaticType() == TransactionType.TRADE
                    && transaction.getFlows().size() == 1
                    && transaction.getFlows().get(0).itemId == COINS);
            if (transaction != null && transaction.isCounted()
                && transaction.getAutomaticType() == TransactionType.TRADE)
            {
                assertEquals("each counted settlement books exactly its accepted tax",
                    -tax[slotByItem.get(transaction.getFlows().get(0).itemId)],
                    transaction.getNet());
            }
        }
    }

    @Test
    public void ownerSevenSaleCollectAllSettlesExactlyInOneBatch()
    {
        Engine engine = ownerEngine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        Map<Integer, Long> held = ownerBatchInventory(100_000L);
        engine.setBaseline(new ContainerSnapshot(held));

        OfferLedger ledger = new OfferLedger();
        placeOwnerBatch(engine, ledger, held, now, true);
        // One Collect All pays exactly the after-tax 24,550 for 25,030 gross execution.
        settle(engine, inventory(COINS, 100_000L + OWNER_BATCH_RECEIVED_TOTAL), now + 20_000L);

        assertOwnerBatchSettled(engine, now + 22_000L);
        assertEquals("nothing is retained once the batch is exact", 0L,
            EngineProbe.pendingSettlementCash(engine.geCustody));
    }

    @Test
    public void ownerSevenSaleCollectAllIsIndependentOfCoinsFirstOrdering()
    {
        Engine engine = ownerEngine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        Map<Integer, Long> held = ownerBatchInventory(100_000L);
        engine.setBaseline(new ContainerSnapshot(held));

        OfferLedger ledger = new OfferLedger();
        // Placements observed; the offers have not reported any fill or terminal evidence yet.
        placeOwnerBatch(engine, ledger, held, now, false);
        // The aggregate collect arrives first, proven by the live Collect interaction.
        engine.noteGeCollectionIntent(now + 20_000L);
        settle(engine, inventory(COINS, 100_000L + OWNER_BATCH_RECEIVED_TOTAL), now + 20_000L);
        assertEquals("the observed collect is retained internally, not reviewed",
            OWNER_BATCH_RECEIVED_TOTAL, EngineProbe.pendingSettlementCash(engine.geCustody));
        assertEquals("a held collect never reaches the decision inbox", 0, reviewCount(engine));
        assertTrue("no settlement exists before the execution evidence arrives",
            engine.getMarketSettlements().stream()
                .noneMatch(row -> row.lifecycle == MarketSettlementProjection.Lifecycle.REALIZED));

        // The fill/terminal evidence arrives afterward; the held batch settles exactly.
        completeOwnerBatch(engine, ledger, now + 30_000L);
        assertOwnerBatchSettled(engine, now + 40_000L);
        assertEquals(0L, EngineProbe.pendingSettlementCash(engine.geCustody));
    }

    @Test
    public void singleSaleCoinsFirstSettlesExactlyWhenTheTerminalEvidenceArrives()
    {
        Engine engine = engine(210L);
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        Map<Integer, Long> held = inventory(COINS, 100_000L, RUNE, 26L);
        engine.setBaseline(new ContainerSnapshot(held));

        OfferLedger ledger = new OfferLedger();
        noteGe(engine, ledger, 0, SELLING, RUNE, 26, 0, 210, 0, now);
        held.put(RUNE, 0L);
        settle(engine, held, now + 200L);
        // The collect arrives before any fill/terminal evidence.
        engine.noteGeCollectionIntent(now + 1_000L);
        settle(engine, inventory(COINS, 105_356L), now + 1_000L);
        assertEquals("the collect is retained until the sale is proven", 5_356L,
            EngineProbe.pendingSettlementCash(engine.geCustody));
        assertEquals(0, reviewCount(engine));

        // The SOLD evidence completes the exact sale.
        noteGe(engine, ledger, 0, SOLD, RUNE, 26, 26, 210, 5_460, now + 2_000L);
        MarketSettlementProjection.Row row = engine.getMarketSettlements().get(0);
        assertEquals(5_356L, row.observedSettlementGp);
        assertEquals(-104L, row.settlementAdjustmentGp);
        assertEquals(104L, row.inferredGeTaxGp);
        assertEquals(MarketSettlementProjection.Lifecycle.REALIZED, row.lifecycle);
        assertEquals("the proven tax is the only unknown-basis contribution", -104L,
            engine.getMetrics(now + 3_000L).net);
        assertEquals(0L, EngineProbe.pendingSettlementCash(engine.geCustody));
    }

    @Test
    public void ownerSevenSaleCollectAllIsIndependentOfInterleavedOrdering()
    {
        Engine engine = ownerEngine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        Map<Integer, Long> held = ownerBatchInventory(100_000L);
        engine.setBaseline(new ContainerSnapshot(held));

        OfferLedger ledger = new OfferLedger();
        placeOwnerBatch(engine, ledger, held, now, false);
        // Only the first three offers are terminal when the collect arrives.
        for (int slot = 0; slot < 3; slot++)
        {
            completeOwnerSale(engine, ledger, slot, now + 20_000L + slot * 1_000L);
        }
        engine.noteGeCollectionIntent(now + 25_000L);
        settle(engine, inventory(COINS, 100_000L + OWNER_BATCH_RECEIVED_TOTAL), now + 25_000L);
        assertEquals("a partially proven batch is never partially booked",
            OWNER_BATCH_RECEIVED_TOTAL, EngineProbe.pendingSettlementCash(engine.geCustody));
        assertEquals(0, reviewCount(engine));

        // The remaining four terminal states arrive afterward.
        for (int slot = 3; slot < OWNER_BATCH_ITEMS.length; slot++)
        {
            completeOwnerSale(engine, ledger, slot, now + 30_000L + slot * 1_000L);
        }
        assertOwnerBatchSettled(engine, now + 40_000L);
        assertEquals(0L, EngineProbe.pendingSettlementCash(engine.geCustody));
    }

    @Test
    public void ownerSevenSaleCollectAllSettlesWhenFillsFinalizeBeforeTerminalState()
    {
        Engine engine = ownerEngine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        Map<Integer, Long> held = ownerBatchInventory(100_000L);
        engine.setBaseline(new ContainerSnapshot(held));

        OfferLedger ledger = new OfferLedger();
        placeOwnerBatch(engine, ledger, held, now, false);
        // Every fill is final and comparable, but the terminal SOLD states are still unobserved.
        for (int slot = 0; slot < OWNER_BATCH_ITEMS.length; slot++)
        {
            noteGe(engine, ledger, slot, SELLING, OWNER_BATCH_ITEMS[slot], 5, 5,
                OWNER_BATCH_LISTED[slot], (int) OWNER_BATCH_EXECUTION[slot],
                now + 20_000L + slot * 1_000L);
        }
        engine.noteGeCollectionIntent(now + 30_000L);
        settle(engine, inventory(COINS, 100_000L + OWNER_BATCH_RECEIVED_TOTAL), now + 30_000L);
        assertEquals("the collect is retained until the SLOT WINDOW confirms it",
            OWNER_BATCH_RECEIVED_TOTAL, EngineProbe.pendingSettlementCash(engine.geCustody));
        // The unique exact attribution books at the window end when no contradicting slot evidence
        // arrived; the terminal SOLD states are still unobserved.
        for (Transaction booking : engine.geCustody.maintenance(now + 33_500L,
            engine.getActiveSession().getId()).countedBookings)
        {
            engine.getActiveSession().addTransaction(booking, 500, true);
        }
        assertOwnerBatchSettled(engine, now + 33_500L);

        // The late terminal states must never double book.
        completeOwnerBatch(engine, ledger, now + 40_000L);
        assertOwnerBatchSettled(engine, now + 42_000L);
        assertEquals(0L, EngineProbe.pendingSettlementCash(engine.geCustody));
    }

    @Test
    public void ownerSevenSaleWrongAggregateGapFailsClosedWithoutAbsorbingIt()
    {
        Engine engine = ownerEngine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        Map<Integer, Long> held = ownerBatchInventory(100_000L);
        engine.setBaseline(new ContainerSnapshot(held));

        OfferLedger ledger = new OfferLedger();
        placeOwnerBatch(engine, ledger, held, now, true);
        // Five coins short of the exact after-tax settlement: the gap is not the tax rule.
        settle(engine, inventory(COINS, 100_000L + OWNER_BATCH_RECEIVED_TOTAL - 5L), now + 20_000L);

        assertEquals("no invented distribution may reach Net", 0L,
            engine.getMetrics(now + 22_000L).net);
        assertEquals("an all-terminal wrong gap is never held", 0L,
            EngineProbe.pendingSettlementCash(engine.geCustody));
        Map<Integer, Integer> slotByItem = ownerBatchSlots();
        for (MarketSettlementProjection.Row row : engine.getMarketSettlements())
        {
            int slot = slotByItem.get(row.itemId);
            assertEquals("gross execution is retained, not split",
                OWNER_BATCH_EXECUTION[slot], row.observedSettlementGp);
            assertEquals(MarketSettlementProjection.Lifecycle.AMBIGUOUS, row.lifecycle);
        }
        assertEquals("the unexplained gap stays one uncounted Coins row", 1,
            countUncertainCoinRows(engine));
        assertEquals("seven quarantined receipts plus the gap row", 8, reviewCount(engine));
    }

    @Test
    public void mixedCollectWithUnrelatedCoinsIsNeverAbsorbedToBalanceTheEquation()
    {
        Engine engine = ownerEngine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        Map<Integer, Long> held = ownerBatchInventory(100_000L);
        engine.setBaseline(new ContainerSnapshot(held));

        OfferLedger ledger = new OfferLedger();
        placeOwnerBatch(engine, ledger, held, now, true);
        // The collect movement carries 500 unrelated coins: the equation cannot balance.
        engine.noteGeCollectionIntent(now + 20_000L);
        settle(engine, inventory(COINS, 100_000L + OWNER_BATCH_RECEIVED_TOTAL + 500L), now + 20_000L);
        // The collect cleared every slot within the SLOT WINDOW; once the window closes without an
        // exact attribution, the slot-evidenced offers are quarantined and the whole movement
        // fails closed as one uncounted row.
        for (int slot = 0; slot < OWNER_BATCH_ITEMS.length; slot++)
        {
            noteGe(engine, ledger, slot, EMPTY, 0, 0, 0, 0, 0, now + 20_600L + slot * 100L);
        }
        for (Transaction booking : engine.geCustody.maintenance(now + 23_000L,
            engine.getActiveSession().getId()).countedBookings)
        {
            engine.getActiveSession().addTransaction(booking, 500, true);
        }

        assertEquals("the mixed movement is not retained as a GE settlement", 0L,
            EngineProbe.pendingSettlementCash(engine.geCustody));
        Map<Integer, Integer> slotByItem = ownerBatchSlots();
        for (MarketSettlementProjection.Row row : engine.getMarketSettlements())
        {
            int slot = slotByItem.get(row.itemId);
            assertEquals(MarketSettlementProjection.Lifecycle.AMBIGUOUS, row.lifecycle);
            assertEquals("gross evidence is preserved, never split to fit",
                OWNER_BATCH_EXECUTION[slot], row.observedSettlementGp);
        }
        assertEquals("the unexplained movement stays its own uncounted row", 1,
            countUncertainCoinRows(engine));
        long excess = 0L;
        for (Transaction transaction : engine.getActiveSession().getTransactions())
        {
            if (transaction.getAutomaticType() == TransactionType.UNCERTAIN
                && transaction.getFlows().size() == 1
                && transaction.getFlows().get(0).itemId == COINS)
            {
                excess = transaction.getFlows().get(0).valueDelta;
            }
        }
        assertEquals("nothing is absorbed into the batch: the whole mixed movement stays uncounted",
            OWNER_BATCH_RECEIVED_TOTAL + 500L, excess);
        assertEquals("nothing is counted", 0L, engine.getMetrics(now + 24_000L).net);
    }

    @Test
    public void duplicateSettlesAndReplayedEvidenceNeverDoubleBookTheBatch()
    {
        Engine engine = ownerEngine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        Map<Integer, Long> held = ownerBatchInventory(100_000L);
        engine.setBaseline(new ContainerSnapshot(held));

        OfferLedger ledger = new OfferLedger();
        placeOwnerBatch(engine, ledger, held, now, true);
        settle(engine, inventory(COINS, 100_000L + OWNER_BATCH_RECEIVED_TOTAL), now + 20_000L);
        assertOwnerBatchSettled(engine, now + 22_000L);

        int transactions = engine.getActiveSession().getTransactions().size();
        long net = engine.getMetrics(now + 22_000L).net;
        // A duplicate collect movement and replayed terminal snapshots add nothing.
        settle(engine, inventory(COINS, 100_000L + OWNER_BATCH_RECEIVED_TOTAL), now + 30_000L);
        completeOwnerBatch(engine, ledger, now + 31_000L);
        engine.geCustody.maintenance(now + 32_000L, engine.getActiveSession().getId());

        assertEquals(transactions, engine.getActiveSession().getTransactions().size());
        assertEquals(net, engine.getMetrics(now + 32_000L).net);
        assertOwnerBatchSettled(engine, now + 32_000L);
    }

    @Test
    public void heldCollectFailsClosedWhenTheExecutionEvidenceNeverArrives()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        Map<Integer, Long> held = inventory(COINS, 100_000L, RUNE, 10L);
        engine.setBaseline(new ContainerSnapshot(held));

        OfferLedger ledger = new OfferLedger();
        noteGe(engine, ledger, 0, SELLING, RUNE, 10, 0, 7, 0, now);
        held.put(RUNE, 0L);
        settle(engine, held, now + 200L);
        engine.noteGeCollectionIntent(now + 1_000L);
        settle(engine, inventory(COINS, 100_070L), now + 1_000L);
        assertEquals("the observed collect is retained", 70L,
            EngineProbe.pendingSettlementCash(engine.geCustody));

        // A later offer event past the bounded lifecycle releases the cash to Review.
        noteGe(engine, ledger, 1, SELLING, RUNE, 10, 0, 7, 0, now + 11 * 60_000L);
        assertEquals("the expired hold is released, never guessed", 0L,
            EngineProbe.pendingSettlementCash(engine.geCustody));
        assertEquals("the observed cash stays visible as one uncounted row", 1,
            countUncertainCoinRows(engine));
        assertEquals(0L, engine.getMetrics(now + 12 * 60_000L).net);
    }

    @Test
    public void unrelatedCollectWindowCoinsWithoutSellEvidenceAreNeverHeld()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        engine.setBaseline(new ContainerSnapshot(inventory(COINS, 100_000L)));

        engine.noteGeCollectionIntent(now + 1_000L);
        engine.markContext(Context.MARKET, 10, "Grand Exchange collect");
        settle(engine, inventory(COINS, 100_500L), now + 1_000L);

        assertEquals("no sell lifecycle may absorb an unrelated movement", 0L,
            EngineProbe.pendingSettlementCash(engine.geCustody));
        assertTrue("no custody settlement is invented", engine.getMarketSettlements().isEmpty());
        assertEquals("the unexplained cash stays on the ordinary review path", 1,
            reviewCount(engine));
        assertEquals(0L, engine.getMetrics(now + 2_000L).net);
    }

    @Test
    public void singleUnknownSaleReceiptsAfterTaxCashAndTaxAdjustedReference()
    {
        Engine engine = engine(210L);
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        engine.setBaseline(new ContainerSnapshot(inventory(COINS, 100_000L, RUNE, 26L)));
        OfferLedger ledger = new OfferLedger();
        noteGe(engine, ledger, 0, SELLING, RUNE, 26, 0, 210, 0, now);
        settle(engine, inventory(COINS, 100_000L, RUNE, 0L), now + 200L);
        noteGe(engine, ledger, 0, SOLD, RUNE, 26, 26, 210, 5_460, now + 1_000L);
        settle(engine, inventory(COINS, 105_356L), now + 2_000L);

        MarketSettlementProjection.Row row = engine.getMarketSettlements().get(0);
        assertEquals(5_460L, MarketFacts.realizedGrossGp(MarketFacts.record(engine, row)));
        assertEquals("the receipt shows actual after-tax cash", 5_356L,
            row.observedSettlementGp);
        assertEquals(-104L, row.settlementAdjustmentGp);
        assertEquals(104L, row.inferredGeTaxGp);
        assertEquals(5_460L, MarketFacts.grossGeReferenceGp(MarketFacts.record(engine, row)));
        assertEquals(104L, row.expectedReferenceTaxGp);
        assertEquals(5_356L, row.geReferenceGp);
        assertEquals("after-tax Received against the tax-adjusted reference", 0L,
            row.geDifferenceGp);
        assertEquals(MarketSettlementProjection.Coverage.FULLY_UNKNOWN, row.coverage);
        assertEquals("unknown liquidation contributes exactly the proven tax", -104L,
            engine.getMetrics(now + 3_000L).net);
    }

    @Test
    public void knownBasisSaleResultUsesAfterTaxCashWithoutASecondTaxCost()
    {
        Engine engine = engine(210L);
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        engine.setBaseline(new ContainerSnapshot(inventory(COINS, 100_000L, RUNE, 26L)));
        gain(engine, RUNE, 26L, 5_000L, now - 60_000L);
        OfferLedger ledger = new OfferLedger();
        noteGe(engine, ledger, 0, SELLING, RUNE, 26, 0, 210, 0, now);
        settle(engine, inventory(COINS, 100_000L, RUNE, 0L), now + 200L);
        noteGe(engine, ledger, 0, SOLD, RUNE, 26, 26, 210, 5_460, now + 1_000L);
        Transaction settlement = settle(engine, inventory(COINS, 105_356L), now + 2_000L);

        assertNotNull(settlement);
        assertTrue(settlement.isCounted());
        MarketSettlementProjection.Row row = engine.getMarketSettlements().get(0);
        assertEquals(MarketSettlementProjection.Coverage.FULLY_KNOWN, row.coverage);
        assertEquals(5_000L, row.trackedBasisConsumedGp);
        assertEquals(5_356L, MarketFacts.knownProceedsGp(MarketFacts.record(engine, row)));
        assertEquals("Result = after-tax Received - proven tracked basis", 356L,
            row.realizedResultGp);
        assertEquals("GE difference stays independent of Result", 0L, row.geDifferenceGp);
        assertEquals("tax is never booked a second time", 5_356L,
            engine.getMetrics(now + 3_000L).net);
    }

    @Test
    public void unexplainedBatchGapWithUnknownStockFailsClosed()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        engine.setBaseline(new ContainerSnapshot(inventory(COINS, 100_000L, RUNE, 20L)));
        OfferLedger ledger = new OfferLedger();
        noteGe(engine, ledger, 0, SELLING, RUNE, 10, 0, 7, 0, now);
        settle(engine, inventory(COINS, 100_000L, RUNE, 10L), now);
        noteGe(engine, ledger, 1, SELLING, RUNE, 10, 0, 7, 0, now + 200L);
        settle(engine, inventory(COINS, 100_000L, RUNE, 0L), now + 400L);
        noteGe(engine, ledger, 0, SOLD, RUNE, 10, 10, 7, 70, now + 600L);
        noteGe(engine, ledger, 1, SOLD, RUNE, 10, 10, 7, 70, now + 800L);
        settle(engine, inventory(COINS, 100_138L), now + 1_000L);

        assertEquals(0L, engine.getMetrics(now + 2_000L).net);
        for (MarketSettlementProjection.Row row : engine.getMarketSettlements())
        {
            assertEquals(MarketSettlementProjection.Lifecycle.AMBIGUOUS, row.lifecycle);
            assertEquals("unexplained gross evidence is retained, not split",
                70L, row.observedSettlementGp);
        }
        for (Transaction transaction : engine.getActiveSession().getTransactions())
        {
            assertFalse("no counted Coins loss may be invented for the gap: " + transaction,
                transaction != null && transaction.isCounted()
                    && transaction.getAutomaticType() == TransactionType.TRADE);
        }
    }

    private static long flow(Transaction transaction, int itemId)
    {
        for (Flow itemFlow : transaction.getFlows())
        {
            if (itemFlow.itemId == itemId)
            {
                return itemFlow.valueDelta;
            }
        }
        return 0L;
    }

    /** Establish exact known coverage for an item (schema-106 tracked basis). */
    private static void gain(Engine engine, int item, long quantity, long value, long at)
    {
        engine.getActiveSession().addTransaction(new Transaction(at, null,
            TransactionType.GAIN, Context.GENERIC, "", "Loot", true,
            Collections.singletonList(new Flow(item, "Nature rune", quantity,
                (int) (quantity > 0L ? value / quantity : 0L), value,
                PriceSource.GRAND_EXCHANGE)),
            ClassificationConfidence.CONFIRMED, "", null), 500);
    }

    private static int countCounted(Engine engine)
    {
        int counted = 0;
        for (Transaction transaction : engine.getActiveSession().getTransactions())
        {
            if (transaction != null && transaction.isCounted())
            {
                counted++;
            }
        }
        return counted;
    }

    private static void noteGe(Engine engine, OfferLedger ledger, int slot,
        GrandExchangeOfferState state, int itemId, int totalQuantity, int quantityTraded, int price,
        int spent, long at)
    {
        OfferLedger.Transition transition = ledger.observe(
            new OfferLedger.Snapshot(slot, state, itemId, totalQuantity, quantityTraded, price, spent))
            .orElse(null);
        if (transition != null)
        {
            engine.noteGeOfferObservation(transition, itemName(itemId), at);
        }
    }

    /** The owner's batch inventory with the seven items at five each. */
    private static Map<Integer, Long> ownerBatchInventory(long coins)
    {
        Map<Integer, Long> held = inventory(COINS, coins);
        for (int item : OWNER_BATCH_ITEMS)
        {
            held.put(item, 5L);
        }
        return held;
    }

    /** Place the owner's seven sell offers and capture each placement principal. */
    private static void placeOwnerBatch(Engine engine, OfferLedger ledger,
        Map<Integer, Long> held, long now, boolean terminal)
    {
        for (int slot = 0; slot < OWNER_BATCH_ITEMS.length; slot++)
        {
            int item = OWNER_BATCH_ITEMS[slot];
            noteGe(engine, ledger, slot, SELLING, item, 5, 0, OWNER_BATCH_LISTED[slot], 0,
                now + slot * 2_000L);
            held.put(item, 0L);
            settle(engine, held, now + slot * 2_000L + 200L);
            if (terminal)
            {
                completeOwnerSale(engine, ledger, slot, now + slot * 2_000L + 1_000L);
            }
        }
    }

    /** Feed the owner's terminal SOLD evidence for every offer. */
    private static void completeOwnerBatch(Engine engine, OfferLedger ledger, long now)
    {
        for (int slot = 0; slot < OWNER_BATCH_ITEMS.length; slot++)
        {
            completeOwnerSale(engine, ledger, slot, now + slot * 1_000L);
        }
    }

    private static void completeOwnerSale(Engine engine, OfferLedger ledger, int slot,
        long at)
    {
        noteGe(engine, ledger, slot, SOLD, OWNER_BATCH_ITEMS[slot], 5, 5, OWNER_BATCH_LISTED[slot],
            (int) OWNER_BATCH_EXECUTION[slot], at);
    }

    /** The exact owner-batch accounting every ordering must converge to. */
    private static void assertOwnerBatchSettled(Engine engine, long now)
    {
        Map<Integer, Integer> slotByItem = ownerBatchSlots();
        long grossTotal = 0L;
        long taxTotal = 0L;
        long receivedTotal = 0L;
        int rows = 0;
        for (MarketSettlementProjection.Row row : engine.getMarketSettlements())
        {
            Integer slot = slotByItem.get(row.itemId);
            if (slot == null)
            {
                continue;
            }
            rows++;
            assertEquals("Received is the actual after-tax cash",
                OWNER_BATCH_RECEIVED[slot], row.observedSettlementGp);
            assertEquals("the actual tax is decomposed, never booked",
                -OWNER_BATCH_TAX[slot], row.settlementAdjustmentGp);
            assertEquals(OWNER_BATCH_TAX[slot], row.inferredGeTaxGp);
            assertEquals(OWNER_BATCH_EXECUTION[slot], MarketFacts.realizedGrossGp(MarketFacts.record(engine, row)));
            assertEquals("the actual after-tax unit value is exact",
                OWNER_BATCH_RECEIVED[slot] / 5L, row.receivedEachGp);
            assertEquals(MarketSettlementProjection.Coverage.FULLY_UNKNOWN, row.coverage);
            assertEquals(MarketSettlementProjection.Lifecycle.REALIZED, row.lifecycle);
            assertFalse(row.manualFinancialResult);
            if (OWNER_BATCH_TAX[slot] > 0L)
            {
                assertTrue("every taxed unknown-basis receipt books exactly its proven tax",
                    row.knownCostOnly);
                assertEquals(-OWNER_BATCH_TAX[slot], row.realizedResultGp);
            }
            else
            {
                assertFalse("the exempt sale is never KNOWN_COST_ONLY", row.knownCostOnly);
                assertEquals(0L, row.realizedResultGp);
            }
            grossTotal += MarketFacts.realizedGrossGp(MarketFacts.record(engine, row));
            taxTotal += -row.settlementAdjustmentGp;
            receivedTotal += row.observedSettlementGp;
        }
        assertEquals(7, rows);
        assertEquals(OWNER_BATCH_GROSS_TOTAL, grossTotal);
        assertEquals(OWNER_BATCH_TAX_TOTAL, taxTotal);
        assertEquals(OWNER_BATCH_RECEIVED_TOTAL, receivedTotal);
        assertEquals("unknown bank stock drops by exactly the proven batch tax", -480L,
            engine.getMetrics(now).net);
        for (Transaction transaction : engine.getActiveSession().getTransactions())
        {
            assertFalse("no uncounted review row may exist for an exact batch: " + transaction,
                ReviewEligibility.needsOwnerDecision(transaction));
            assertFalse("no synthetic Coins fee row may exist: " + transaction,
                transaction != null && transaction.isCounted()
                    && transaction.getAutomaticType() == TransactionType.TRADE
                    && transaction.getFlows().size() == 1
                    && transaction.getFlows().get(0).itemId == COINS);
            if (transaction != null && transaction.isCounted()
                && transaction.getAutomaticType() == TransactionType.TRADE)
            {
                assertEquals("one counted settlement per sale, each exactly its accepted tax",
                    -OWNER_BATCH_TAX[slotByItem.get(transaction.getFlows().get(0).itemId)],
                    transaction.getNet());
            }
        }
    }

    private static Map<Integer, Integer> ownerBatchSlots()
    {
        Map<Integer, Integer> slotByItem = new HashMap<>();
        for (int slot = 0; slot < OWNER_BATCH_ITEMS.length; slot++)
        {
            slotByItem.put(OWNER_BATCH_ITEMS[slot], slot);
        }
        return slotByItem;
    }

    private static int reviewCount(Engine engine)
    {
        int reviews = 0;
        for (Transaction transaction : engine.getActiveSession().getTransactions())
        {
            if (ReviewEligibility.needsOwnerDecision(transaction))
            {
                reviews++;
            }
        }
        return reviews;
    }

    private static int countUncertainCoinRows(Engine engine)
    {
        int rows = 0;
        for (Transaction transaction : engine.getActiveSession().getTransactions())
        {
            if (transaction != null && transaction.getAutomaticType() == TransactionType.UNCERTAIN
                && transaction.getFlows().size() == 1
                && transaction.getFlows().get(0).itemId == COINS)
            {
                rows++;
            }
        }
        return rows;
    }

    private static String itemName(int itemId)
    {
        switch (itemId)
        {
            case RUNE: return "Nature rune";
            case COINS: return "Coins";
            case CHAOS_RUNE: return "Chaos rune";
            case OAK_LOGS: return "Oak logs";
            case DIAMOND_BOLTS_E: return "Diamond bolts (e)";
            case SAPPHIRE_DRAGON_BOLTS_E: return "Sapphire dragon bolts (e)";
            case DRAGONFRUIT: return "Dragonfruit";
            case TEAK_PLANK: return "Teak plank";
            case BOLT_OF_LINEN: return "Bolt of linen";
            case ANGLERFISH: return "Anglerfish";
            case COOKED_KARAMBWAN: return "Cooked karambwan";
            case SHARK: return "Shark";
            case COOKED_CHICKEN: return "Cooked chicken";
            default: return "Item " + itemId;
        }
    }

    /** Production login priming: feed the stable baseline until the engine is ready to book. */
    private static void prime(Engine engine, Map<Integer, Long> inventory, long now)
    {
        ContainerSnapshot snapshot = new ContainerSnapshot(inventory);
        for (int i = 0; i < 20 && EngineProbe.isBaselinePriming(engine); i++)
        {
            engine.processIfDirty(snapshot, now + i * 600L);
        }
    }

    private static Transaction settle(Engine engine, Map<Integer, Long> next, long now)
    {
        engine.markInventoryDirty();
        ContainerSnapshot snapshot = new ContainerSnapshot(next);
        Transaction result = null;
        for (int i = 0; i < 3; i++)
        {
            Transaction settled = engine.processIfDirty(snapshot, now + i * 600L);
            if (settled != null)
            {
                result = settled;
            }
        }
        return result;
    }

    private static Map<Integer, Long> inventory(Object... pairs)
    {
        Map<Integer, Long> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
        {
            map.put((Integer) pairs[i], (Long) pairs[i + 1]);
        }
        return map;
    }

    private static Engine engine()
    {
        return engine(6L);
    }

    /** One frozen reference unit for every non-coin item (the schema-106 test default is 6). */
    private static Engine engine(long unitPrice)
    {
        return pricedEngine(id -> unitPrice);
    }

    /** The owner's true frozen references per item, as shown in the reference screenshots. */
    private static Engine ownerEngine()
    {
        return pricedEngine(GeCustodyPersistenceTest::ownerReference);
    }

    private static long ownerReference(int itemId)
    {
        switch (itemId)
        {
            case CHAOS_RUNE: return 104L;
            case OAK_LOGS: return 41L;
            case DIAMOND_BOLTS_E: return 157L;
            case SAPPHIRE_DRAGON_BOLTS_E: return 2_802L;
            // The owner's seven-sale batch frozen references, as persisted live.
            case DRAGONFRUIT: return 750L;
            case TEAK_PLANK: return 860L;
            case BOLT_OF_LINEN: return 404L;
            case ANGLERFISH: return 1_634L;
            case COOKED_KARAMBWAN: return 410L;
            case SHARK: return 994L;
            case COOKED_CHICKEN: return 66L;
            default: return 6L;
        }
    }

    private static Engine pricedEngine(java.util.function.IntToLongFunction unitOf)
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 1; }
            @Override public boolean keepTransferAuditRows() { return true; }
        };
        return new Engine(deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                int id = delta.getKey();
                long unit = id == COINS ? 1L : unitOf.applyAsLong(id);
                flows.add(new Flow(id, itemName(id), delta.getValue(), (int) unit,
                    delta.getValue() * unit,
                    id == COINS ? PriceSource.FACE_VALUE : PriceSource.GRAND_EXCHANGE));
            }
            return flows;
        }, new TransactionClassifier(), config);
    }
}
