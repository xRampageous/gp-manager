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
        Am engine = engine();
        long now = T0;
        engine.ajl("Trading", Cx.AUTO, now);
        engine.setBaseline(new Cc(inventory(COINS, 100_000L)));

        Bj ledger = new Bj();
        noteGe(engine, ledger, 0, BUYING, SHARK, 5, 0, 800, 0, now);
        noteGe(engine, ledger, 0, BOUGHT, SHARK, 5, 5, 800, 4_000, now + 600L);

        engine.zk(quantities(SHARK, 1L), 100, "Loot from Goblin", "Goblin");
        Ac loot = settle(engine, inventory(COINS, 100_000L, SHARK, 1L), now + 1_200L);

        assertNotNull(loot);
        assertEquals("real loot owns its delta over a recent GE buy of the same item",
            Ai.LOOT, loot.getType());
        assertEquals("Loot from Goblin", loot.getNote());
    }

    @Test
    public void passiveProgressCannotClaimAnUnrelatedItemDelta()
    {
        Am engine = engine();
        long now = T0;
        engine.ajl("Trading", Cx.AUTO, now);
        engine.setBaseline(new Cc(inventory(COINS, 100_000L)));

        Bj ledger = new Bj();
        noteGe(engine, ledger, 0, BUYING, SHARK, 5, 0, 800, 0, now);
        noteGe(engine, ledger, 0, BUYING, SHARK, 5, 2, 800, 1_600, now + 600L);

        Ac gain = settle(engine, inventory(COINS, 100_000L, LOGS, 1L), now + 1_200L);

        assertNotNull(gain);
        assertEquals(Ai.GAIN, gain.getType());
    }

    @Test
    public void passiveProgressAloneCannotClaimTheSameItemDelta()
    {
        Am engine = engine();
        long now = T0;
        engine.ajl("Trading", Cx.AUTO, now);
        engine.setBaseline(new Cc(inventory(COINS, 100_000L)));

        Bj ledger = new Bj();
        noteGe(engine, ledger, 0, BUYING, SHARK, 5, 0, 800, 0, now);
        noteGe(engine, ledger, 0, BUYING, SHARK, 5, 2, 800, 1_600, now + 600L);

        Ac gain = settle(engine, inventory(COINS, 100_000L, SHARK, 1L), now + 1_200L);

        assertNotNull(gain);
        assertEquals("unsettled progress cannot make a same-item gain look like a collection",
            Ai.GAIN, gain.getType());
    }

    @Test
    public void shopLikeMovementAfterPassiveProgressIsNotStolenAsMarket()
    {
        Am engine = engine();
        long now = T0;
        engine.ajl("Trading", Cx.AUTO, now);
        engine.setBaseline(new Cc(inventory(COINS, 100_000L)));

        Bj ledger = new Bj();
        noteGe(engine, ledger, 0, BUYING, SHARK, 5, 0, 800, 0, now);
        noteGe(engine, ledger, 0, BUYING, SHARK, 5, 2, 800, 1_600, now + 600L);

        // A shop purchase pays coins for an item; it is not an offer settlement.
        Ac purchase = settle(engine, inventory(COINS, 99_200L, SHARK, 1L), now + 1_200L);

        assertNotNull(purchase);
        assertEquals(Ai.UNCERTAIN, purchase.getType());
    }

    @Test
    public void cancelledSellRefundCannotExplainALaterItemLoss()
    {
        Am engine = engine();
        long now = T0;
        engine.ajl("Trading", Cx.AUTO, now);
        engine.setBaseline(new Cc(inventory(COINS, 100_000L, SHARK, 5L)));

        Bj ledger = new Bj();
        noteGe(engine, ledger, 1, CANCELLED_SELL, SHARK, 5, 0, 800, 0, now);

        Ac loss = settle(engine, inventory(COINS, 100_000L, SHARK, 4L), now + 600L);

        assertNotNull(loss);
        assertEquals("a cancelled sell returns items; it cannot explain a later loss",
            Ai.CONSUMPTION, loss.getType());
    }

    @Test
    public void cancelledBuyRefundCannotExplainALaterCoinLoss()
    {
        Am engine = engine();
        long now = T0;
        engine.ajl("Trading", Cx.AUTO, now);
        engine.setBaseline(new Cc(inventory(COINS, 100_000L)));

        Bj ledger = new Bj();
        noteGe(engine, ledger, 2, CANCELLED_BUY, SHARK, 5, 0, 800, 0, now);

        Ac loss = settle(engine, inventory(COINS, 99_000L), now + 600L);

        assertNotNull(loss);
        assertEquals("a cancelled buy refunds coins; it cannot explain a later coin loss",
            Ai.CONSUMPTION, loss.getType());
    }

    @Test
    public void profileSwitchClearsRecentGeEvidence()
    {
        Am engine = engine();
        long now = T0;
        engine.ajl("Trading", Cx.AUTO, now);
        engine.setBaseline(new Cc(inventory(COINS, 100_000L)));

        Bj ledger = new Bj();
        noteGe(engine, ledger, 0, BUYING, SHARK, 5, 0, 800, 0, now);
        noteGe(engine, ledger, 0, BOUGHT, SHARK, 5, 5, 800, 4_000, now + 600L);

        engine.agl("rsprofile.ge-audit-b", new SavedState(), now + 600L);
        engine.ajl("Trading", Cx.AUTO, now + 700L);
        engine.setBaseline(new Cc(inventory(COINS, 100_000L)));

        Ac gain = settle(engine, inventory(COINS, 100_000L, SHARK, 1L), now + 1_200L);

        assertNotNull(gain);
        assertEquals("the previous owner's GE evidence must not classify the new owner's delta",
            Ai.GAIN, gain.getType());
        assertTrue("the previous owner's custody lifecycles never leak across profiles",
            engine.geCustody.aji().isEmpty());
    }

    @Test
    public void destructiveResetClearsRecentGeEvidence()
    {
        Am engine = engine();
        long now = T0;
        engine.ajl("Trading", Cx.AUTO, now);
        engine.setBaseline(new Cc(inventory(COINS, 100_000L)));

        Bj ledger = new Bj();
        noteGe(engine, ledger, 0, BUYING, SHARK, 5, 0, 800, 0, now);
        noteGe(engine, ledger, 0, BOUGHT, SHARK, 5, 5, 800, 4_000, now + 600L);

        engine.agr(now + 600L);
        engine.togglePause(now + 700L);
        engine.setBaseline(new Cc(inventory(COINS, 100_000L)));

        Ac gain = settle(engine, inventory(COINS, 100_000L, SHARK, 1L), now + 1_200L);

        assertNotNull(gain);
        assertEquals("cleared GE evidence must not reappear after a destructive reset",
            Ai.GAIN, gain.getType());
        assertTrue("destructive reset clears schema-104 custody state",
            engine.geCustody.aji().isEmpty());
    }

    @Test
    public void recentSellPlacementClaimsTheItemLossAsOwnershipNeutralCustody()
    {
        Am engine = engine();
        long now = T0;
        engine.ajl("Trading", Cx.AUTO, now);
        engine.setBaseline(new Cc(inventory(COINS, 100_000L, SHARK, 5L)));

        Bj ledger = new Bj();
        noteGe(engine, ledger, 3, SELLING, SHARK, 5, 0, 800, 0, now);

        Ac trade = settle(engine, inventory(COINS, 100_000L, SHARK, 4L), now + 600L);

        assertNotNull(trade);
        assertEquals("a proven placement principal is custody, not finalized economics",
            Ai.TRANSFER, trade.getType());
        assertEquals(Aj.TRANSFER, trade.getContext());
        assertEquals("pending SELL custody never changes canonical Net",
            0L, engine.getMetrics(now + 1_200L).net);
        assertEquals(1, engine.geCustody.aji().size());
    }

    @Test
    public void recentBuyPlacementClaimsTheCoinLossAsOwnershipNeutralCustody()
    {
        Am engine = engine();
        long now = T0;
        engine.ajl("Trading", Cx.AUTO, now);
        engine.setBaseline(new Cc(inventory(COINS, 100_000L)));

        Bj ledger = new Bj();
        noteGe(engine, ledger, 4, BUYING, SHARK, 5, 0, 800, 0, now);

        Ac trade = settle(engine, inventory(COINS, 96_000L), now + 600L);

        assertNotNull(trade);
        assertEquals("an observed buy reserve is custody, not finalized economics",
            Ai.TRANSFER, trade.getType());
        assertEquals(Aj.TRANSFER, trade.getContext());
        assertEquals("pending BUY reserve never changes canonical Net",
            0L, engine.getMetrics(now + 1_200L).net);
    }

    @Test
    public void observationsAloneNeverBookWithoutAnInventoryMovement()
    {
        Am engine = engine();
        long now = T0;
        engine.ajl("Trading", Cx.AUTO, now);
        engine.setBaseline(new Cc(inventory(COINS, 100_000L)));

        Bj ledger = new Bj();
        noteGe(engine, ledger, 0, BUYING, SHARK, 5, 0, 800, 0, now);
        noteGe(engine, ledger, 0, BOUGHT, SHARK, 5, 5, 800, 4_000, now + 600L);

        assertEquals(0, engine.getActiveSession().getTransactions().size());
        assertEquals(0L, engine.getMetrics(now + 1_200L).net);
    }

    private static void noteGe(
        Am engine,
        Bj ledger,
        int slot,
        GrandExchangeOfferState state,
        int itemId,
        int totalQuantity,
        int quantityTraded,
        int price,
        int spent,
        long at)
    {
        Bj.Transition transition = ledger.observe(
            new Bj.Snapshot(slot, state, itemId, totalQuantity, quantityTraded, price, spent))
            .orElse(null);
        if (transition != null)
        {
            engine.abh(transition, name(itemId), at);
        }
    }

    private static Ac settle(Am engine, Map<Integer, Long> next, long now)
    {
        engine.yz();
        Cc snapshot = new Cc(next);
        Ac first = engine.adj(snapshot, now);
        Ac settled = engine.adj(snapshot, now + 600L);
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

    private static Am engine()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 0; }
            @Override public boolean keepTransferAuditRows() { return true; }
        };
        return new Am(deltas ->
        {
            List<Ab> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                int id = delta.getKey();
                flows.add(new Ab(id, name(id), delta.getValue(), price(id), delta.getValue() * price(id),
                    id == COINS ? Av.FACE_VALUE : Av.GRAND_EXCHANGE));
            }
            return flows;
        }, new TransactionClassifier(), config);
    }
}
