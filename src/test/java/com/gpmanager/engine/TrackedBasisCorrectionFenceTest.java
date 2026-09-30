package com.gpmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static net.runelite.api.GrandExchangeOfferState.CANCELLED_SELL;
import static net.runelite.api.GrandExchangeOfferState.SELLING;
import static net.runelite.api.GrandExchangeOfferState.SOLD;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * PRE-R5C.2C.6A correction fence: a basis-affecting acquisition correction is allowed only while
 * it can be reflected exactly before downstream realization. Once known coverage is reserved or
 * realized the correction is rejected BEFORE any canonical mutation. No clamp, no history rewrite.
 */
public class TrackedBasisCorrectionFenceTest
{
    private static final int LOGS = ItemID.LOGS;
    private static final int COINS = ItemID.COINS;
    private static final long T0 = 1_000_000_000_000L;

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    @Test
    public void acquisitionCorrectionBeforeRealizationIsAllowedAndExact()
    {
        Harness h = new Harness(159);
        String gainId = h.gain(LOGS, 5L, 775L, h.now - 60_000L);
        assertEquals(5L, EngineProbe.knownCoverageQty(h.engine, LOGS));

        assertTrue(h.engine.qi(gainId, Ah.IGNORE, h.now, "test"));

        assertEquals("the exact delta leaves the pool empty", 0L, EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertEquals(0L, EngineProbe.knownCoverageBasisGp(h.engine, LOGS));
        assertEquals("canonical exclusion still applies", 0L, h.net());
    }

    @Test
    public void undoPreRealizationCorrectionRestoresCoverage()
    {
        Harness h = new Harness(159);
        String gainId = h.gain(LOGS, 5L, 775L, h.now - 60_000L);
        h.engine.qi(gainId, Ah.IGNORE, h.now, "test");

        assertTrue(h.engine.akb(h.now));

        assertEquals(5L, EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertEquals(775L, EngineProbe.knownCoverageBasisGp(h.engine, LOGS));
        assertEquals(775L, h.net());
    }

    @Test
    public void correctionWhileReservationOpenIsBlocked()
    {
        Harness h = new Harness(159);
        String gainId = h.gain(LOGS, 5L, 775L, h.now - 60_000L);
        h.inventory = with(h.inventory, LOGS, 5L);
        h.engine.setBaseline(new Cc(h.inventory));
        h.offer(0, SELLING, LOGS, 5, 0, 159, 0);
        h.settle(with(h.inventory, LOGS, 0L));
        assertEquals(5L, EngineProbe.reservedQty(h.engine, LOGS));

        assertFalse("an open reservation freezes basis-affecting corrections",
            h.engine.qi(gainId, Ah.IGNORE, h.now, "test"));

        assertEquals("the canonical acquisition is untouched", Ah.AUTO,
            h.transaction(gainId).getCorrection());
        assertEquals("the reservation is untouched", 5L, EngineProbe.reservedQty(h.engine, LOGS));
        assertEquals(775L, EngineProbe.reservedBasisGp(h.engine, LOGS));
        assertEquals(775L, h.net());
    }

    @Test
    public void zeroRealizationCancellationClearsTheTemporaryBlock()
    {
        Harness h = new Harness(159);
        String gainId = h.gain(LOGS, 5L, 775L, h.now - 60_000L);
        h.inventory = with(h.inventory, LOGS, 5L);
        h.engine.setBaseline(new Cc(h.inventory));
        h.offer(0, SELLING, LOGS, 5, 0, 159, 0);
        h.settle(with(h.inventory, LOGS, 0L));
        assertFalse(h.engine.qi(gainId, Ah.IGNORE, h.now, "test"));

        h.offer(0, CANCELLED_SELL, LOGS, 5, 0, 159, 0);
        h.settle(with(h.inventory, LOGS, 5L));

        assertEquals("the exact reservation is restored", 5L, EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertTrue("the temporary freeze clears after a zero-realization cancel",
            h.engine.qi(gainId, Ah.IGNORE, h.now, "test"));
        assertEquals(0L, EngineProbe.knownCoverageQty(h.engine, LOGS));
    }

    @Test
    public void correctionAfterCollectedSellIsBlockedBeforeCanonicalMutation()
    {
        Harness h = new Harness(159);
        String gainId = h.gain(LOGS, 5L, 775L, h.now - 60_000L);
        h.sell(0, LOGS, 5L, 159, 790L);
        assertEquals(790L, h.net());

        assertFalse("realized known coverage cannot be changed retroactively",
            h.engine.qi(gainId, Ah.IGNORE, h.now, "test"));

        assertEquals(Ah.AUTO, h.transaction(gainId).getCorrection());
        assertEquals("the realized sale is untouched", 790L, h.net());
        assertEquals(0L, EngineProbe.availableQty(h.engine, LOGS));
        assertEquals(0L, EngineProbe.reservedQty(h.engine, LOGS));
    }

    @Test
    public void splitAfterPermanentSinkIsBlocked()
    {
        Harness h = new Harness(100);
        String gainId = h.gain(LOGS, 10L, 1_000L, h.now - 60_000L);
        h.sink(LOGS, 4L, h.now - 30_000L);
        assertEquals(600L, EngineProbe.knownCoverageBasisGp(h.engine, LOGS));

        assertFalse("a shrink split after a permanent sink cannot be reflected exactly",
            h.engine.kr(gainId, LOGS, 5L, h.now, "test"));

        assertEquals(6L, EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertEquals(600L, EngineProbe.knownCoverageBasisGp(h.engine, LOGS));
        assertEquals(1_000L, h.transaction(gainId).getRevenue());
    }

    @Test
    public void spentKnownCoverageCannotBeReclassifiedOrUndoneWithoutExactReversal()
    {
        Harness h = new Harness(100);
        h.gain(LOGS, 10L, 1_000L, h.now - 60_000L);
        h.sink(LOGS, 4L, h.now - 30_000L);
        Ac sink = h.engine.getActiveSession().getTransactions().get(1);

        assertFalse(h.engine.qi(sink.getId(), Ah.TRANSFER,
            h.now, "bank deposit"));
        assertFalse(h.engine.qi(sink.getId(), Ah.IGNORE,
            h.now, "not spent"));
        assertNull(h.engine.akc(h.now));
        assertEquals(Ah.AUTO, sink.getCorrection());
        assertEquals(6L, EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertEquals(600L, EngineProbe.knownCoverageBasisGp(h.engine, LOGS));

        assertTrue("Result attribution may change without reversing physical depletion",
            h.engine.qi(sink.getId(), Ah.COST, h.now, "cost"));
        assertEquals(6L, EngineProbe.knownCoverageQty(h.engine, LOGS));
    }

    @Test
    public void ownerCountedReviewRemovalDepletesBasisBeforeLaterRealization()
    {
        Harness h = new Harness(100);
        h.gain(LOGS, 10L, 1_000L, h.now - 60_000L);
        Ac uncounted = new Ac(h.now - 30_000L, null,
            Ai.UNCERTAIN, Aj.GENERIC, "", "Review", false,
            Collections.singletonList(new Ab(LOGS, "Logs", -4L, 100, -400L,
                Av.GRAND_EXCHANGE)), Bd.UNCERTAIN, "", null);
        h.engine.getActiveSession().kf(uncounted, 500);

        assertTrue(h.engine.qi(uncounted.getId(), Ah.COST,
            h.now, "spent"));
        assertEquals(Ah.COST, uncounted.getCorrection());
        assertEquals(6L, EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertEquals(600L, EngineProbe.knownCoverageBasisGp(h.engine, LOGS));
        assertFalse("the counted sink cannot later be undone without exact reversal",
            h.engine.qi(uncounted.getId(), Ah.IGNORE,
                h.now, "actually transfer"));
    }

    @Test
    public void delayedReviewDecisionCannotConsumeLaterAcquiredBasis()
    {
        Harness h = new Harness(100);
        h.gain(LOGS, 10L, 1_000L, h.now - 60_000L);
        Ac review = new Ac(h.now - 30_000L, null,
            Ai.UNCERTAIN, Aj.GENERIC, "", "Review", false,
            Collections.singletonList(new Ab(LOGS, "Logs", -4L, 100, -400L,
                Av.GRAND_EXCHANGE)), Bd.UNCERTAIN, "", null);
        h.engine.getActiveSession().kf(review, 500);
        h.gain(LOGS, 5L, 1_000L, h.now - 10_000L);

        assertFalse(h.engine.qi(review.getId(), Ah.COST,
            h.now, "late decision"));
        assertEquals(Ah.AUTO, review.getCorrection());
        assertEquals(15L, EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertEquals(2_000L, EngineProbe.knownCoverageBasisGp(h.engine, LOGS));
    }

    @Test
    public void provenOwnDropRecoveryRestoresExactKnownShareAcrossRestart()
    {
        Harness h = new Harness(155);
        h.gain(LOGS, 5L, 775L, h.now - 60_000L);
        Ac drop = new Ac(h.now - 30_000L, null,
            Ai.CONSUMPTION, Aj.GENERIC, "Dropped", "Used", true,
            Collections.singletonList(new Ab(LOGS, "Logs", -4L, 155, -620L,
                Av.GRAND_EXCHANGE)), Bd.CONFIRMED, "", null);
        drop.zd();
        h.engine.getActiveSession().kf(drop, 500);
        assertEquals(1L, EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertEquals(155L, EngineProbe.knownCoverageBasisGp(h.engine, LOGS));

        assertTrue(h.engine.getActiveSession().afc(drop.getId(), LOGS, 2L,
            h.now, "picked up"));
        assertEquals(3L, EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertEquals(465L, EngineProbe.knownCoverageBasisGp(h.engine, LOGS));

        com.google.gson.Gson gson = new com.google.gson.Gson();
        SavedState saved = gson.fromJson(gson.toJson(h.engine.qm()), SavedState.class);
        Am restarted = engine(new int[] {155});
        restarted.restore(saved, h.now + 1_000L);
        assertTrue(restarted.getActiveSession().afc(drop.getId(), LOGS, 2L,
            h.now + 2_000L, "picked up"));
        assertEquals(5L, EngineProbe.knownCoverageQty(restarted, LOGS));
        assertEquals(775L, EngineProbe.knownCoverageBasisGp(restarted, LOGS));
    }

    @Test
    public void unsafeUndoThatWouldReAddBasisAfterLaterSaleIsBlocked()
    {
        Harness h = new Harness(159);
        String gainId = h.gain(LOGS, 5L, 775L, h.now - 60_000L);
        assertTrue(h.engine.qi(gainId, Ah.IGNORE, h.now, "test"));
        assertEquals(0L, EngineProbe.knownCoverageQty(h.engine, LOGS));

        h.sell(0, LOGS, 5L, 159, 790L);
        assertEquals("unknown liquidation stays Net-neutral", 0L, h.net());

        assertFalse("undoing would re-add basis after a later sale",
            h.engine.akb(h.now));
        assertEquals(0L, EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertEquals(0L, h.net());
    }

    @Test
    public void sellResultCorrectionStaysAllowedAndKeepsBasisAndGeDifference()
    {
        Harness h = new Harness(159);
        h.gain(LOGS, 5L, 775L, h.now - 60_000L);
        h.sell(0, LOGS, 5L, 159, 790L);
        Bi.Row before = h.rows().get(0);
        assertEquals(15L, before.realizedResultGp);
        assertEquals("after-tax cash against the tax-adjusted reference", 10L,
            before.geDifferenceGp);
        Aa record = h.engine.geCustody.aji().get(0);

        Ac settlement = h.transaction(before.settlementId);
        assertNotNull(settlement);
        assertTrue("financial attribution corrections stay available",
            h.engine.qi(settlement.getId(), Ah.COST, h.now, "test"));

        assertEquals("physical basis consumption is unchanged", 775L,
            record.getConsumedTrackedBasisGp());
        Bi.Row after = h.rows().get(0);
        assertEquals("GE execution evidence is never rewritten", 10L, after.geDifferenceGp);
        assertTrue(after.realizedResultCorrectionAware);
        assertEquals("the corrected financial result is visible", -1_565L, after.realizedResultGp);
    }

    @Test
    public void preEpochAcquisitionCorrectionIsBlocked()
    {
        Harness h = new Harness(159);
        String gainId = h.gain(LOGS, 5L, 775L, h.now - 60_000L);
        SavedState legacy = h.engine.qm();
        legacy.setSchemaVersion(105);
        legacy.setTrackedBasis(null);

        Am migrated = engine(new int[] {159});
        migrated.restore(legacy, h.now + 1_000L);
        Ac gain = migrated.getActiveSession().sw(gainId);
        assertNotNull(gain);

        assertFalse("pre-106 coverage is unknown and cannot be changed retroactively",
            migrated.qi(gainId, Ah.IGNORE, h.now + 2_000L, "test"));
        assertEquals(Ah.AUTO, gain.getCorrection());
    }

    // ---- harness ----------------------------------------------------------------------------

    private static final class Harness
    {
        final int[] quote;
        final Am engine;
        final Bj ledger = new Bj();
        long now = T0;
        Map<Integer, Long> inventory = new HashMap<>();

        Harness(int initialQuote)
        {
            quote = new int[] { initialQuote };
            engine = engine(quote);
            engine.ajl("Trading", Cx.AUTO, now);
            inventory.put(COINS, 1_000_000L);
            engine.setBaseline(new Cc(inventory));
        }

        String gain(int item, long qty, long value, long at)
        {
            Ac transaction = new Ac(at, null, Ai.GAIN,
                Aj.GENERIC, "", "Loot", true,
                Collections.singletonList(new Ab(item, name(item), qty,
                    (int) (qty > 0L ? value / qty : 0L), value, Av.GRAND_EXCHANGE)),
                Bd.CONFIRMED, "", null);
            engine.getActiveSession().kf(transaction, 500);
            return transaction.getId();
        }

        void sink(int item, long qty, long at)
        {
            engine.getActiveSession().kf(new Ac(at, null,
                Ai.CONSUMPTION, Aj.GENERIC, "", "Used", true,
                Collections.singletonList(new Ab(item, name(item), -qty, 100, -qty * 100L,
                    Av.GRAND_EXCHANGE)),
                Bd.CONFIRMED, "", null), 500);
        }

        void sell(int slot, int item, long qty, int limit, long cash)
        {
            inventory = with(inventory, item, qty);
            engine.setBaseline(new Cc(inventory));
            offer(slot, SELLING, item, (int) qty, 0, limit, 0);
            inventory = with(inventory, item, 0L);
            settle(inventory);
            offer(slot, SOLD, item, (int) qty, (int) qty, limit, (int) cash);
            inventory = with(inventory, COINS, inventory.getOrDefault(COINS, 0L) + cash);
            settle(inventory);
        }

        void offer(int slot, GrandExchangeOfferState state, int item, int total, int traded,
            int price, int spent)
        {
            now += 600L;
            Bj.Transition transition = ledger.observe(
                new Bj.Snapshot(slot, state, item, total, traded, price, spent)).orElse(null);
            if (transition != null)
            {
                engine.abh(transition, name(transition.current.itemId), now);
            }
        }

        Ac settle(Map<Integer, Long> next)
        {
            now += 600L;
            inventory = new HashMap<>(next);
            engine.yz();
            Cc snapshot = new Cc(inventory);
            Ac result = null;
            for (int i = 0; i < 3; i++)
            {
                Ac settled = engine.adj(snapshot, now);
                if (settled != null)
                {
                    result = settled;
                }
                now += 600L;
            }
            return result;
        }

        Ac transaction(String id)
        {
            return engine.getActiveSession().sw(id);
        }

        long net()
        {
            return engine.getMetrics(now).net;
        }

        List<Bi.Row> rows()
        {
            return engine.ub();
        }
    }

    private static String name(int id)
    {
        return id == COINS ? "Coins" : id == LOGS ? "Logs" : "Item " + id;
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

    private static Am engine(int[] quote)
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
                int unit = id == COINS ? 1 : quote[0];
                Av source = id == COINS ? Av.FACE_VALUE
                    : unit > 0 ? Av.GRAND_EXCHANGE : Av.UNPRICED;
                flows.add(new Ab(id, name(id), delta.getValue(), unit, delta.getValue() * unit,
                    source));
            }
            return flows;
        }, new TransactionClassifier(), config);
    }
}
