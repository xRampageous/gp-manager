package com.gpmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

import static net.runelite.api.GrandExchangeOfferState.BOUGHT;
import static net.runelite.api.GrandExchangeOfferState.BUYING;
import static net.runelite.api.GrandExchangeOfferState.CANCELLED_BUY;
import static net.runelite.api.GrandExchangeOfferState.CANCELLED_SELL;
import static net.runelite.api.GrandExchangeOfferState.SELLING;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * C3a GE evidence ownership: the offer ledger is item/direction-scoped settlement evidence, never a
 * blanket MARKET hint. Stronger source evidence keeps its delta, terminal/cancel offer states never
 * explain a later movement, and account/profile plus destructive-reset boundaries clear the evidence.
 */
public class GeEvidenceOwnershipTest
{
    private static final int LOGS = 1519;
    private static final int SHARK = 385;
    private static final int COINS = 995;
    private static final long T0 = 1_000_000_000_000L;

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    @Test
    public void realLootKeepsItsDeltaWhenARecentBuyForTheSameItemExists()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        engine.setBaseline(new ContainerSnapshot(inventory(COINS, 100_000L)));

        OfferLedger ledger = new OfferLedger();
        noteGe(engine, ledger, 0, BUYING, SHARK, 5, 0, 800, 0, now);
        noteGe(engine, ledger, 0, BOUGHT, SHARK, 5, 5, 800, 4_000, now + 600L);

        engine.markLootContext(quantities(SHARK, 1L), 100, "Loot from Goblin", "Goblin");
        Transaction loot = settle(engine, inventory(COINS, 100_000L, SHARK, 1L), now + 1_200L);

        assertNotNull(loot);
        assertEquals("real loot owns its delta over a recent GE buy of the same item",
            TransactionType.LOOT, loot.getType());
        assertEquals("Loot from Goblin", loot.getNote());
    }

    @Test
    public void passiveProgressCannotClaimAnUnrelatedItemDelta()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        engine.setBaseline(new ContainerSnapshot(inventory(COINS, 100_000L)));

        OfferLedger ledger = new OfferLedger();
        noteGe(engine, ledger, 0, BUYING, SHARK, 5, 0, 800, 0, now);
        noteGe(engine, ledger, 0, BUYING, SHARK, 5, 2, 800, 1_600, now + 600L);

        Transaction gain = settle(engine, inventory(COINS, 100_000L, LOGS, 1L), now + 1_200L);

        assertNotNull(gain);
        assertEquals(TransactionType.GAIN, gain.getType());
    }

    @Test
    public void passiveProgressAloneCannotClaimTheSameItemDelta()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        engine.setBaseline(new ContainerSnapshot(inventory(COINS, 100_000L)));

        OfferLedger ledger = new OfferLedger();
        noteGe(engine, ledger, 0, BUYING, SHARK, 5, 0, 800, 0, now);
        noteGe(engine, ledger, 0, BUYING, SHARK, 5, 2, 800, 1_600, now + 600L);

        Transaction gain = settle(engine, inventory(COINS, 100_000L, SHARK, 1L), now + 1_200L);

        assertNotNull(gain);
        assertEquals("unsettled progress cannot make a same-item gain look like a collection",
            TransactionType.GAIN, gain.getType());
    }

    @Test
    public void shopLikeMovementAfterPassiveProgressIsNotStolenAsMarket()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        engine.setBaseline(new ContainerSnapshot(inventory(COINS, 100_000L)));

        OfferLedger ledger = new OfferLedger();
        noteGe(engine, ledger, 0, BUYING, SHARK, 5, 0, 800, 0, now);
        noteGe(engine, ledger, 0, BUYING, SHARK, 5, 2, 800, 1_600, now + 600L);

        // A shop purchase pays coins for an item; it is not an offer settlement.
        Transaction purchase = settle(engine, inventory(COINS, 99_200L, SHARK, 1L), now + 1_200L);

        assertNotNull(purchase);
        assertEquals(TransactionType.UNCERTAIN, purchase.getType());
    }

    @Test
    public void cancelledSellRefundCannotExplainALaterItemLoss()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        engine.setBaseline(new ContainerSnapshot(inventory(COINS, 100_000L, SHARK, 5L)));

        OfferLedger ledger = new OfferLedger();
        noteGe(engine, ledger, 1, CANCELLED_SELL, SHARK, 5, 0, 800, 0, now);

        Transaction loss = settle(engine, inventory(COINS, 100_000L, SHARK, 4L), now + 600L);

        assertNotNull(loss);
        assertEquals("a cancelled sell returns items; it cannot explain a later loss",
            TransactionType.CONSUMPTION, loss.getType());
    }

    @Test
    public void cancelledBuyRefundCannotExplainALaterCoinLoss()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        engine.setBaseline(new ContainerSnapshot(inventory(COINS, 100_000L)));

        OfferLedger ledger = new OfferLedger();
        noteGe(engine, ledger, 2, CANCELLED_BUY, SHARK, 5, 0, 800, 0, now);

        Transaction loss = settle(engine, inventory(COINS, 99_000L), now + 600L);

        assertNotNull(loss);
        assertEquals("a cancelled buy refunds coins; it cannot explain a later coin loss",
            TransactionType.CONSUMPTION, loss.getType());
    }

    @Test
    public void profileSwitchClearsRecentGeEvidence()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        engine.setBaseline(new ContainerSnapshot(inventory(COINS, 100_000L)));

        OfferLedger ledger = new OfferLedger();
        noteGe(engine, ledger, 0, BUYING, SHARK, 5, 0, 800, 0, now);
        noteGe(engine, ledger, 0, BOUGHT, SHARK, 5, 5, 800, 4_000, now + 600L);

        engine.restoreForProfile("rsprofile.ge-audit-b", new SavedState(), now + 600L);
        engine.startCustomSession("Trading", SessionMode.AUTO, now + 700L);
        engine.setBaseline(new ContainerSnapshot(inventory(COINS, 100_000L)));

        Transaction gain = settle(engine, inventory(COINS, 100_000L, SHARK, 1L), now + 1_200L);

        assertNotNull(gain);
        assertEquals("the previous owner's GE evidence must not classify the new owner's delta",
            TransactionType.GAIN, gain.getType());
        assertTrue("the previous owner's custody lifecycles never leak across profiles",
            engine.geCustody.snapshotRecords().isEmpty());
    }

    @Test
    public void destructiveResetClearsRecentGeEvidence()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        engine.setBaseline(new ContainerSnapshot(inventory(COINS, 100_000L)));

        OfferLedger ledger = new OfferLedger();
        noteGe(engine, ledger, 0, BUYING, SHARK, 5, 0, 800, 0, now);
        noteGe(engine, ledger, 0, BOUGHT, SHARK, 5, 5, 800, 4_000, now + 600L);

        engine.resetTrackingData(now + 600L);
        engine.togglePause(now + 700L);
        engine.setBaseline(new ContainerSnapshot(inventory(COINS, 100_000L)));

        Transaction gain = settle(engine, inventory(COINS, 100_000L, SHARK, 1L), now + 1_200L);

        assertNotNull(gain);
        assertEquals("cleared GE evidence must not reappear after a destructive reset",
            TransactionType.GAIN, gain.getType());
        assertTrue("destructive reset clears schema-104 custody state",
            engine.geCustody.snapshotRecords().isEmpty());
    }

    @Test
    public void recentSellPlacementClaimsTheItemLossAsOwnershipNeutralCustody()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        engine.setBaseline(new ContainerSnapshot(inventory(COINS, 100_000L, SHARK, 5L)));

        OfferLedger ledger = new OfferLedger();
        noteGe(engine, ledger, 3, SELLING, SHARK, 5, 0, 800, 0, now);

        Transaction trade = settle(engine, inventory(COINS, 100_000L, SHARK, 4L), now + 600L);

        assertNotNull(trade);
        assertEquals("a proven placement principal is custody, not finalized economics",
            TransactionType.TRANSFER, trade.getType());
        assertEquals(Context.TRANSFER, trade.getContext());
        assertEquals("pending SELL custody never changes canonical Net",
            0L, engine.getMetrics(now + 1_200L).net);
        assertEquals(1, engine.geCustody.snapshotRecords().size());
    }

    @Test
    public void recentBuyPlacementClaimsTheCoinLossAsOwnershipNeutralCustody()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        engine.setBaseline(new ContainerSnapshot(inventory(COINS, 100_000L)));

        OfferLedger ledger = new OfferLedger();
        noteGe(engine, ledger, 4, BUYING, SHARK, 5, 0, 800, 0, now);

        Transaction trade = settle(engine, inventory(COINS, 96_000L), now + 600L);

        assertNotNull(trade);
        assertEquals("an observed buy reserve is custody, not finalized economics",
            TransactionType.TRANSFER, trade.getType());
        assertEquals(Context.TRANSFER, trade.getContext());
        assertEquals("pending BUY reserve never changes canonical Net",
            0L, engine.getMetrics(now + 1_200L).net);
    }

    @Test
    public void observationsAloneNeverBookWithoutAnInventoryMovement()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        engine.setBaseline(new ContainerSnapshot(inventory(COINS, 100_000L)));

        OfferLedger ledger = new OfferLedger();
        noteGe(engine, ledger, 0, BUYING, SHARK, 5, 0, 800, 0, now);
        noteGe(engine, ledger, 0, BOUGHT, SHARK, 5, 5, 800, 4_000, now + 600L);

        assertEquals(0, engine.getActiveSession().getTransactions().size());
        assertEquals(0L, engine.getMetrics(now + 1_200L).net);
    }

    private static void noteGe(
        Engine engine,
        OfferLedger ledger,
        int slot,
        GrandExchangeOfferState state,
        int itemId,
        int totalQuantity,
        int quantityTraded,
        int price,
        int spent,
        long at)
    {
        OfferLedger.Transition transition = ledger.observe(
            new OfferLedger.Snapshot(slot, state, itemId, totalQuantity, quantityTraded, price, spent))
            .orElse(null);
        if (transition != null)
        {
            engine.noteGeOfferObservation(transition, name(itemId), at);
        }
    }

    private static Transaction settle(Engine engine, Map<Integer, Long> next, long now)
    {
        engine.markInventoryDirty();
        ContainerSnapshot snapshot = new ContainerSnapshot(next);
        Transaction first = engine.processIfDirty(snapshot, now);
        Transaction settled = engine.processIfDirty(snapshot, now + 600L);
        return settled == null ? first : settled;
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

    private static Map<Integer, Long> quantities(int itemId, long quantity)
    {
        Map<Integer, Long> map = new HashMap<>();
        map.put(itemId, quantity);
        return map;
    }

    private static String name(int id)
    {
        return id == LOGS ? "Willow logs" : id == SHARK ? "Shark" : id == COINS ? "Coins" : "Item " + id;
    }

    private static int price(int id)
    {
        return id == LOGS ? 48 : id == SHARK ? 800 : id == COINS ? 1 : 0;
    }

    private static Engine engine()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 0; }
            @Override public boolean keepTransferAuditRows() { return true; }
        };
        return new Engine(deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                int id = delta.getKey();
                flows.add(new Flow(id, name(id), delta.getValue(), price(id), delta.getValue() * price(id),
                    id == COINS ? PriceSource.FACE_VALUE : PriceSource.GRAND_EXCHANGE));
            }
            return flows;
        }, new TransactionClassifier(), config);
    }
}
