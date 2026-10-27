package com.gpmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static net.runelite.api.GrandExchangeOfferState.EMPTY;
import static net.runelite.api.GrandExchangeOfferState.SELLING;
import static net.runelite.api.GrandExchangeOfferState.SOLD;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * G1 (2026-10-23 review): a finished offer collected on another device and re-listed identically
 * while logged out restarts its counters. Matching on slot, side, item, quantity and price alone
 * used to resume the saved lifecycle over the new counters. The owner now explicitly excludes
 * claims made elsewhere: reset counters retire the old offer silently and never create a booking.
 * A genuine still-unclaimed offer keeps its original identity and can be collected here.
 */
public class GeIdenticalRelistLoginTest
{
    private static final int RUNE = ItemID.NATURERUNE;
    private static final int COINS = ItemID.COINS;
    private static final long T0 = 1_000_000_000_000L;

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    @Test
    public void identicalRelistSeenByTheLoginSeedDoesNotBackfillAnAwayCollection()
    {
        Engine restored = soldThenRestored();
        Map<Integer, OfferSlots.Snapshot> slots = new HashMap<>();
        slots.put(0, new OfferSlots.Snapshot(0, SELLING, RUNE, 26, 0, 210, 0));
        restored.seedGeSlots(slots, tomorrow() + 2_000L);
        assertAwayCollectionIgnored(restored);
        assertEquals("another device's activity is never a Review", 0, reviews(restored));
    }

    @Test
    public void identicalRelistArrivingAfterAnEmptySeedDoesNotBackfillAnAwayCollection()
    {
        Engine restored = soldThenRestored();
        OfferSlots live = emptySeed(restored);
        noteGe(restored, live, 0, SELLING, RUNE, 26, 0, 210, 0, tomorrow() + 4_000L);
        assertAwayCollectionIgnored(restored);
        assertEquals("another device's activity is never a Review", 0, reviews(restored));
    }

    /** Control: the genuine same offer resumed at login keeps working exactly as before. */
    @Test
    public void theSameUnfinishedOfferStillResumesAtLogin()
    {
        Engine engine = priced();
        engine.startCustom("Trading", T0);
        engine.setBaseline(new ContainerSnapshot(inventory(COINS, 100_000L, RUNE, 26L)));
        OfferSlots ledger = new OfferSlots();
        noteGe(engine, ledger, 0, SELLING, RUNE, 26, 0, 210, 0, T0);
        settle(engine, inventory(COINS, 100_000L, RUNE, 0L), T0 + 200L);
        noteGe(engine, ledger, 0, SELLING, RUNE, 26, 10, 210, 2_100, T0 + 60_000L);
        Engine restored = restore(engine);
        Map<Integer, OfferSlots.Snapshot> slots = new HashMap<>();
        slots.put(0, new OfferSlots.Snapshot(0, SELLING, RUNE, 26, 14, 210, 2_940));
        restored.seedGeSlots(slots, tomorrow() + 2_000L);
        assertEquals(1, restored.exchange.records().size());
        assertEquals(14L, restored.exchange.records().get(0).filledQty);
        assertEquals(0, reviews(restored));
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────

    private static long tomorrow()
    {
        return T0 + 20L * 60L * 60L * 1000L;
    }

    /** Listed and fully sold while online; the coins were collected on another device. */
    private static Engine soldThenRestored()
    {
        Engine engine = priced();
        engine.startCustom("Trading", T0);
        engine.setBaseline(new ContainerSnapshot(inventory(COINS, 100_000L, RUNE, 26L)));
        OfferSlots ledger = new OfferSlots();
        noteGe(engine, ledger, 0, SELLING, RUNE, 26, 0, 210, 0, T0);
        settle(engine, inventory(COINS, 100_000L, RUNE, 0L), T0 + 200L);
        noteGe(engine, ledger, 0, SOLD, RUNE, 26, 26, 210, 5_460, T0 + 60_000L);
        return restore(engine);
    }

    private static Engine restore(Engine engine)
    {
        SavedState state = engine.savedState();
        Engine restored = priced();
        restored.restore(state, tomorrow());
        restored.resume(tomorrow(), PauseReason.IDLE, PauseReason.RECOVERY);
        ContainerSnapshot held = new ContainerSnapshot(inventory(COINS, 100_000L));
        for (int i = 0; i < 20 && EngineProbe.isBaselinePriming(restored); i++)
            restored.processIfDirty(held, tomorrow() + i * 600L, null);
        return restored;
    }

    private static OfferSlots emptySeed(Engine restored)
    {
        OfferSlots live = new OfferSlots();
        live.beginSeed();
        Map<Integer, OfferSlots.Snapshot> slots = new HashMap<>();
        for (int slot = 0; slot < 8; slot++)
        {
            OfferSlots.Snapshot empty = new OfferSlots.Snapshot(slot, EMPTY, 0, 0, 0, 0, 0);
            live.observe(empty);
            slots.put(slot, empty);
        }
        live.finishSeed();
        restored.seedGeSlots(slots, tomorrow() + 2_000L);
        return live;
    }

    /** The owner excludes claims made elsewhere, even when this client saw earlier fills. */
    private static void assertAwayCollectionIgnored(Engine engine)
    {
        engine.tickGe(tomorrow() + 20L * 60_000L);
        assertEquals("the away claim is not a new Market booking", 0,
            engine.session().transactions().stream().filter(row -> row.type() == TransactionType.TRADE).count());
        assertEquals(0L, engine.metrics(tomorrow() + 20L * 60_000L).net);
    }

    private static int reviews(Engine engine)
    {
        int count = 0;
        for (Transaction row : engine.session().transactions()) if (ReviewRules.needsReview(row)) count++;
        return count;
    }

    private static void noteGe(Engine engine, OfferSlots ledger, int slot, GrandExchangeOfferState state,
        int itemId, int total, int traded, int price, int spent, long at)
    {
        OfferSlots.Transition transition = ledger.observe(
            new OfferSlots.Snapshot(slot, state, itemId, total, traded, price, spent)).orElse(null);
        if (transition != null) engine.noteGeOffer(transition, "Nature rune", at);
    }

    private static void settle(Engine engine, Map<Integer, Long> next, long now)
    {
        engine.markDirty();
        ContainerSnapshot snapshot = new ContainerSnapshot(next);
        for (int i = 0; i < 3; i++) engine.processIfDirty(snapshot, now + i * 600L, null);
    }

    private static Map<Integer, Long> inventory(Object... pairs)
    {
        Map<Integer, Long> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((Integer) pairs[i], (Long) pairs[i + 1]);
        return map;
    }

    private static Engine priced()
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
                long unit = delta.getKey() == COINS ? 1L : 210L;
                flows.add(new Flow(delta.getKey(), delta.getKey() == COINS ? "Coins" : "Nature rune",
                    delta.getValue(), (int) unit, delta.getValue() * unit,
                    delta.getKey() == COINS ? PriceSource.FACE_VALUE : PriceSource.GRAND_EXCHANGE));
            }
            return flows;
        }, new TransactionClassifier(), config);
    }
}
