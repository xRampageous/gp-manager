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

        assertTrue(h.engine.correctTransaction(gainId, Correction.IGNORE, "test"));

        assertEquals("the exact delta leaves the pool empty", 0L, EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertEquals(0L, EngineProbe.knownCoverageBasisGp(h.engine, LOGS));
        assertEquals("canonical exclusion still applies", 0L, h.net());
    }

    @Test
    public void undoPreRealizationCorrectionRestoresCoverage()
    {
        Harness h = new Harness(159);
        String gainId = h.gain(LOGS, 5L, 775L, h.now - 60_000L);
        h.engine.correctTransaction(gainId, Correction.IGNORE, "test");

        assertTrue(h.engine.undoCorrection());

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
        h.engine.setBaseline(new ContainerSnapshot(h.inventory));
        h.offer(0, SELLING, LOGS, 5, 0, 159, 0);
        h.settle(with(h.inventory, LOGS, 0L));
        assertEquals(5L, EngineProbe.reservedQty(h.engine, LOGS));

        assertFalse("an open reservation freezes basis-affecting corrections",
            h.engine.correctTransaction(gainId, Correction.IGNORE, "test"));

        assertEquals("the canonical acquisition is untouched", Correction.AUTO,
            h.transaction(gainId).correction());
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
        h.engine.setBaseline(new ContainerSnapshot(h.inventory));
        h.offer(0, SELLING, LOGS, 5, 0, 159, 0);
        h.settle(with(h.inventory, LOGS, 0L));
        assertFalse(h.engine.correctTransaction(gainId, Correction.IGNORE, "test"));

        h.offer(0, CANCELLED_SELL, LOGS, 5, 0, 159, 0);
        h.settle(with(h.inventory, LOGS, 5L));

        assertEquals("the exact reservation is restored", 5L, EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertTrue("the temporary freeze clears after a zero-realization cancel",
            h.engine.correctTransaction(gainId, Correction.IGNORE, "test"));
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
            h.engine.correctTransaction(gainId, Correction.IGNORE, "test"));

        assertEquals(Correction.AUTO, h.transaction(gainId).correction());
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
            h.engine.splitItem(gainId, LOGS, 5L, "test"));

        assertEquals(6L, EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertEquals(600L, EngineProbe.knownCoverageBasisGp(h.engine, LOGS));
        assertEquals(1_000L, h.transaction(gainId).revenue());
    }

    @Test
    public void spentKnownCoverageCannotBeReclassifiedOrUndoneWithoutExactReversal()
    {
        Harness h = new Harness(100);
        h.gain(LOGS, 10L, 1_000L, h.now - 60_000L);
        h.sink(LOGS, 4L, h.now - 30_000L);
        Transaction sink = h.engine.session().transactions().get(1);

        assertFalse(h.engine.correctTransaction(sink.id(), Correction.TRANSFER, "bank deposit"));
        assertFalse(h.engine.correctTransaction(sink.id(), Correction.IGNORE, "not spent"));
        assertNull(h.engine.undoTransaction());
        assertEquals(Correction.AUTO, sink.correction());
        assertEquals(6L, EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertEquals(600L, EngineProbe.knownCoverageBasisGp(h.engine, LOGS));

        assertTrue("Result attribution may change without reversing physical depletion",
            h.engine.correctTransaction(sink.id(), Correction.COST, "cost"));
        assertEquals(6L, EngineProbe.knownCoverageQty(h.engine, LOGS));
    }

    @Test
    public void ownerCountedReviewRemovalDepletesBasisBeforeLaterRealization()
    {
        Harness h = new Harness(100);
        h.gain(LOGS, 10L, 1_000L, h.now - 60_000L);
        Transaction uncounted = new Transaction(h.now - 30_000L,
            TransactionType.UNCERTAIN, Context.GENERIC, "", "Review", false,
            Collections.singletonList(new Flow(LOGS, "Logs", -4L, 100, -400L,
                PriceSource.GRAND_EXCHANGE)), Confidence.UNCERTAIN, "", null);
        h.engine.session().addTransaction(uncounted, 500, false);

        assertTrue(h.engine.correctTransaction(uncounted.id(), Correction.COST, "spent"));
        assertEquals(Correction.COST, uncounted.correction());
        assertEquals(6L, EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertEquals(600L, EngineProbe.knownCoverageBasisGp(h.engine, LOGS));
        assertFalse("the counted sink cannot later be undone without exact reversal",
            h.engine.correctTransaction(uncounted.id(), Correction.IGNORE, "actually transfer"));
    }

    @Test
    public void delayedReviewDecisionCannotConsumeLaterAcquiredBasis()
    {
        Harness h = new Harness(100);
        h.gain(LOGS, 10L, 1_000L, h.now - 60_000L);
        Transaction review = new Transaction(h.now - 30_000L,
            TransactionType.UNCERTAIN, Context.GENERIC, "", "Review", false,
            Collections.singletonList(new Flow(LOGS, "Logs", -4L, 100, -400L,
                PriceSource.GRAND_EXCHANGE)), Confidence.UNCERTAIN, "", null);
        h.engine.session().addTransaction(review, 500, false);
        h.gain(LOGS, 5L, 1_000L, h.now - 10_000L);

        assertFalse(h.engine.correctTransaction(review.id(), Correction.COST, "late decision"));
        assertEquals(Correction.AUTO, review.correction());
        assertEquals(15L, EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertEquals(2_000L, EngineProbe.knownCoverageBasisGp(h.engine, LOGS));
    }


    @Test
    public void unsafeUndoThatWouldReAddBasisAfterLaterSaleIsBlocked()
    {
        Harness h = new Harness(159);
        String gainId = h.gain(LOGS, 5L, 775L, h.now - 60_000L);
        assertTrue(h.engine.correctTransaction(gainId, Correction.IGNORE, "test"));
        assertEquals(0L, EngineProbe.knownCoverageQty(h.engine, LOGS));

        h.sell(0, LOGS, 5L, 159, 790L);
        assertEquals("unknown liquidation stays Net-neutral", 0L, h.net());

        assertFalse("undoing would re-add basis after a later sale",
            h.engine.undoCorrection());
        assertEquals(0L, EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertEquals(0L, h.net());
    }

    @Test
    public void sellResultCorrectionStaysAllowedAndKeepsBasisAndGeDifference()
    {
        Harness h = new Harness(159);
        h.gain(LOGS, 5L, 775L, h.now - 60_000L);
        h.sell(0, LOGS, 5L, 159, 790L);
        MarketRows.Row before = h.rows().get(0);
        assertEquals(15L, before.realizedResultGp);
        assertEquals("after-tax cash", 790L, before.observedSettlementGp);
        GeOffer record = h.engine.exchange.records().get(0);

        Transaction settlement = h.transaction(before.settlementId);
        assertNotNull(settlement);
        assertTrue("financial attribution corrections stay available",
            h.engine.correctTransaction(settlement.id(), Correction.COST, "test"));

        assertEquals("physical basis consumption is unchanged", 775L,
            record.getConsumedTrackedBasisGp());
        MarketRows.Row after = h.rows().get(0);
        assertEquals("GE execution evidence is never rewritten", 790L, after.observedSettlementGp);
        assertTrue(after.realizedResultCorrectionAware);
        assertEquals("the corrected financial result is visible", -1_565L, after.realizedResultGp);
    }

    @Test
    public void preEpochAcquisitionCorrectionIsBlocked()
    {
        Harness h = new Harness(159);
        String gainId = h.gain(LOGS, 5L, 775L, h.now - 60_000L);
        SavedState legacy = h.engine.savedState();
        legacy.setSchemaVersion(105);
        legacy.setTrackedBasis(null);

        Engine migrated = engine(new int[] {159});
        migrated.restore(legacy, h.now + 1_000L);
        Transaction gain = migrated.session().findTransaction(gainId);
        assertNotNull(gain);

        assertFalse("pre-106 coverage is unknown and cannot be changed retroactively",
            migrated.correctTransaction(gainId, Correction.IGNORE, "test"));
        assertEquals(Correction.AUTO, gain.correction());
    }

    // ---- harness ----------------------------------------------------------------------------

    private static final class Harness
    {
        final int[] quote;
        final Engine engine;
        final OfferSlots ledger = new OfferSlots();
        long now = T0;
        Map<Integer, Long> inventory = new HashMap<>();

        Harness(int initialQuote)
        {
            quote = new int[] { initialQuote };
            engine = engine(quote);
            engine.startCustom("Trading", now);
            inventory.put(COINS, 1_000_000L);
            engine.setBaseline(new ContainerSnapshot(inventory));
        }

        String gain(int item, long qty, long value, long at)
        {
            Transaction transaction = new Transaction(at, TransactionType.GAIN,
                Context.GENERIC, "", "Loot", true,
                Collections.singletonList(new Flow(item, name(item), qty,
                    (int) (qty > 0L ? value / qty : 0L), value, PriceSource.GRAND_EXCHANGE)),
                Confidence.CONFIRMED, "", null);
            engine.session().addTransaction(transaction, 500, false);
            return transaction.id();
        }

        void sink(int item, long qty, long at)
        {
            engine.session().addTransaction(new Transaction(at,
                TransactionType.CONSUMPTION, Context.GENERIC, "", "Used", true,
                Collections.singletonList(new Flow(item, name(item), -qty, 100, -qty * 100L,
                    PriceSource.GRAND_EXCHANGE)),
                Confidence.CONFIRMED, "", null), 500, false);
        }

        void sell(int slot, int item, long qty, int limit, long cash)
        {
            inventory = with(inventory, item, qty);
            engine.setBaseline(new ContainerSnapshot(inventory));
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
            OfferSlots.Transition transition = ledger.observe(
                new OfferSlots.Snapshot(slot, state, item, total, traded, price, spent)).orElse(null);
            if (transition != null)
            {
                engine.noteGeOffer(transition, name(transition.current.item), now);
            }
        }

        Transaction settle(Map<Integer, Long> next)
        {
            now += 600L;
            inventory = new HashMap<>(next);
            engine.markDirty();
            ContainerSnapshot snapshot = new ContainerSnapshot(inventory);
            Transaction result = null;
            for (int i = 0; i < 3; i++)
            {
                Transaction settled = engine.processIfDirty(snapshot, now, null);
                if (settled != null)
                {
                    result = settled;
                }
                now += 600L;
            }
            return result;
        }

        Transaction transaction(String id)
        {
            return engine.session().findTransaction(id);
        }

        long net()
        {
            return engine.metrics(now).net;
        }

        List<MarketRows.Row> rows()
        {
            return engine.marketRows();
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

    private static Engine engine(int[] quote)
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
                int unit = id == COINS ? 1 : quote[0];
                PriceSource source = id == COINS ? PriceSource.FACE_VALUE
                    : unit > 0 ? PriceSource.GRAND_EXCHANGE : PriceSource.UNPRICED;
                flows.add(new Flow(id, name(id), delta.getValue(), unit, delta.getValue() * unit,
                    source));
            }
            return flows;
        }, new TransactionClassifier(), config);
    }
}
