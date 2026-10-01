package com.gpmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static net.runelite.api.GrandExchangeOfferState.SELLING;
import static net.runelite.api.GrandExchangeOfferState.SOLD;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Owner smoke (G2 re-test): a collect held for exact attribution must resolve on game ticks, not
 * only when a later inventory change happens to settle. The hold is transient, so waiting for an
 * unrelated movement left the cash invisible and lost it on logout.
 */
public class GeCustodyTickTest
{
    private static final int COINS = ItemID.COINS;
    private static final int SHARK = ItemID.SHARK;
    private static final long T0 = 1_000_000_000_000L;
    private static final long RECEIVED = 29_280L - 570L;

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    @Test
    public void heldCollectResolvesOnTheTickAfterItsWindowWithoutAnotherInventoryChange()
    {
        Map<Integer, Long> inventory = new HashMap<>();
        Am engine = engine();
        engine.ajl("Trading", Cx.AUTO, T0);
        inventory.put(COINS, 1_000_000L);
        inventory.put(SHARK, 60L);
        engine.setBaseline(new Cc(inventory));
        Bj ledger = new Bj();
        long t = T0 + 10_000L;
        // Two identical sold offers: one collect's cash matches either, so it is a tie.
        offer(engine, ledger, 0, SELLING, 0, 0, t);
        offer(engine, ledger, 1, SELLING, 0, 0, t + 50L);
        inventory.put(SHARK, 0L);
        settle(engine, inventory, t + 100L);
        offer(engine, ledger, 0, SOLD, 30, 29_280, t + 200L);
        offer(engine, ledger, 1, SOLD, 30, 29_280, t + 250L);

        engine.abg(t + 10_000L);
        inventory.put(COINS, 1_000_000L + RECEIVED);
        settle(engine, inventory, t + 10_000L);
        assertEquals("the tied cash is held for slot evidence", RECEIVED,
            EngineProbe.pendingSettlementCash(engine.geCustody));
        assertEquals(0, reviewRows(engine));

        assertFalse("inside the window nothing is decided", engine.ajv(t + 10_600L));
        assertTrue("the first tick after the window resolves it", engine.ajv(t + 12_400L));
        assertEquals(0L, EngineProbe.pendingSettlementCash(engine.geCustody));
        assertTrue("a tie with no slot evidence fails closed visibly", reviewRows(engine) > 0);
        assertEquals("nothing is counted from an unproven tie", 0L, engine.getMetrics(t + 13_000L).net);
    }

    @Test
    public void pausedTrackingNeverBooksFromTheTick()
    {
        Am engine = engine();
        engine.ajl("Trading", Cx.AUTO, T0);
        engine.togglePause(T0 + 1_000L);
        assertFalse(engine.ajv(T0 + 2_000L));
    }

    private static int reviewRows(Am engine)
    {
        int count = 0;
        for (Ac transaction : engine.getActiveSession().getTransactions())
        {
            if (Eh.aal(transaction))
            {
                count++;
            }
        }
        return count;
    }

    private static void offer(Am engine, Bj ledger, int slot,
        GrandExchangeOfferState state, int traded, int spent, long at)
    {
        ledger.observe(new Bj.Snapshot(slot, state, SHARK, 30, traded, 978, spent))
            .ifPresent(transition -> engine.abh(transition, "Shark", at));
    }

    private static void settle(Am engine, Map<Integer, Long> inventory, long at)
    {
        engine.yz();
        Cc snapshot = new Cc(inventory);
        engine.adj(snapshot, at);
        engine.adj(snapshot, at + 1L);
    }

    private static Am engine()
    {
        return new Am(deltas ->
        {
            List<Ab> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                int id = delta.getKey();
                long unit = id == COINS ? 1L : 978L;
                flows.add(new Ab(id, id == COINS ? "Coins" : "Shark", delta.getValue(), (int) unit,
                    delta.getValue() * unit,
                    id == COINS ? Av.FACE_VALUE : Av.GRAND_EXCHANGE));
            }
            return flows;
        }, new TransactionClassifier(), new GpManagerConfig()
        {
            @Override
            public Db receiptRetentionDays()
            {
                return Db.DAYS_365;
            }

            @Override
            public int stabilizationTicks()
            {
                return 0;
            }
        });
    }
}
