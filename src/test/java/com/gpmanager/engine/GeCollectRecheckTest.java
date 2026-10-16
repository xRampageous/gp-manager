package com.gpmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static com.gpmanager.GameData.msg;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Owner live 2026-10-16: coins picked up at Commander Zilyana went to Review as "Grand Exchange
 * movement not claimable by a custody lifecycle", because a sale had finished in the last two
 * hours. The GE-collect gate stops new ones; the load-time recheck rebooks the old ones.
 */
public class GeCollectRecheckTest
{
    private static final long T0 = 1_700_000_000_000L;
    private static final int COINS = 995, BONES = 526;

    @Test
    public void aPreGateCoinPickupBooksAsTodaysPickup()
    {
        Engine engine = engine();
        engine.startCustom("Zilyana", T0);
        Session session = engine.getActiveSession();
        Transaction old = geReview(Context.GENERIC, coins(19_911L));
        String id = old.getId();
        session.addTransaction(old, 2_000);
        assertTrue("it starts in Review", ReviewRules.needsOwnerDecision(old));
        long revision = engine.getRevision();

        engine.recheck(item -> 0);

        Transaction today = pickup(19_911L);
        assertNotNull("today's code books the same coins", today);
        assertSame(today.getType(), old.getType());
        assertSame(TransactionType.GAIN, old.getType());
        assertEquals(today.isCounted(), old.isCounted());
        assertTrue(old.isCounted());
        assertSame(today.getConfidence(), old.getConfidence());
        assertEquals(today.getExplanation(), old.getExplanation());
        assertEquals(today.getNote(), old.getNote());
        assertFalse("it leaves Review", ReviewRules.needsOwnerDecision(old));
        assertEquals("same row", id, old.getId());
        assertEquals("same time", T0, old.timestampEpochMillis);
        assertEquals("same activity", "Growler", old.getActivityName());
        assertSame(Correction.AUTO, old.getCorrection());
        assertEquals("the session's Net counts it", 19_911L, session.metrics(T0).net);
        assertNotEquals("the HUD and totals hear of it", revision, engine.getRevision());

        revision = engine.getRevision();
        engine.recheck(item -> 0);
        assertEquals("a second pass changes nothing", revision, engine.getRevision());
    }

    @Test
    public void aMarketRowALossOrAnOwnerDecisionIsNeverRebooked()
    {
        Engine engine = engine();
        engine.startCustom("Zilyana", T0);
        Session session = engine.getActiveSession();
        Transaction market = geReview(Context.MARKET, coins(5_000L));
        Transaction withLoss = geReview(Context.GENERIC, coins(5_000L),
            new Flow(BONES, "Bones", -1L, 100, -100L, PriceSource.GRAND_EXCHANGE));
        Transaction withItem = geReview(Context.GENERIC, coins(5_000L),
            new Flow(BONES, "Bones", 1L, 100, 100L, PriceSource.GRAND_EXCHANGE));
        Transaction counted = geReview(Context.GENERIC, coins(5_000L));
        counted.applyCorrection(Correction.REVENUE, T0);
        for (Transaction row : List.of(market, withLoss, withItem, counted)) session.addTransaction(row, 2_000);
        long revision = engine.getRevision();

        engine.recheck(item -> 0);

        for (Transaction row : List.of(market, withLoss, withItem, counted))
        {
            assertSame(TransactionType.UNCERTAIN, row.autoType());
            assertEquals("Grand Exchange", row.getNote());
            assertEquals(msg("grand-exchange-movement"), row.getExplanation());
            assertFalse(row.autoCounted());
        }
        assertSame("the owner's Count stays theirs", Correction.REVENUE, counted.getCorrection());
        assertEquals("nothing changed", revision, engine.getRevision());
    }

    private static Flow coins(long quantity)
    {
        return new Flow(COINS, "Coins", quantity, 1, quantity, PriceSource.FACE_VALUE);
    }

    /** A row as the pre-gate rule booked it. */
    private static Transaction geReview(Context context, Flow... flows)
    {
        return new Transaction(T0, TransactionType.UNCERTAIN, context, "Grand Exchange", "Growler", false,
            List.of(flows), Confidence.UNCERTAIN, msg("grand-exchange-movement"), null);
    }

    /** The same coins landing in the pack today, with no Collect click. */
    private static Transaction pickup(long quantity)
    {
        Engine engine = engine();
        engine.startCustom("Zilyana", T0);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(Map.of(COINS, 0L))));
        engine.markDirty();
        List<Transaction> booked = new ArrayList<>();
        for (int i = 0; i < 3; i++)
        {
            Transaction settled = engine.processIfDirty(new ContainerSnapshot(new HashMap<>(Map.of(COINS, quantity))),
                T0 + 600L + i * 600L, booked::add);
            if (settled != null) booked.add(settled);
        }
        return booked.isEmpty() ? null : booked.get(0);
    }

    private static Engine engine()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override
            public int stabilizationTicks()
            {
                return 1;
            }
        };
        return new Engine(deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                int id = delta.getKey();
                flows.add(id == COINS ? coins(delta.getValue())
                    : new Flow(id, "Bones", delta.getValue(), 100, delta.getValue() * 100, PriceSource.GRAND_EXCHANGE));
            }
            return flows;
        }, new TransactionClassifier(), config);
    }
}
