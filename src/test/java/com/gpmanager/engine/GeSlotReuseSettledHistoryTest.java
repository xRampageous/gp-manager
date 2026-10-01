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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * PR1-PRE-R5C.2C.6A.1A2 owner smoke (G1): a settled sale keeps its durable market record when its
 * slot is reused by a new offer. Reuse used to delete the closed record, so the booked sale lost
 * its market presentation and read as a generic trade of its full item value.
 */
public class GeSlotReuseSettledHistoryTest
{
    private static final int COINS = ItemID.COINS;
    private static final int SHARK = ItemID.SHARK;
    private static final int MITHRIL = ItemID.MITHRIL_BAR;
    private static final long T0 = 1_000_000_000_000L;
    private static final long SHARK_QTY = 30L;
    private static final long SHARK_GROSS = 29_280L;
    private static final long SHARK_TAX = 570L;
    private static final long SHARK_RECEIVED = SHARK_GROSS - SHARK_TAX;
    private static final long MITHRIL_QTY = 30L;
    private static final long MITHRIL_GROSS = 29_340L;
    private static final long MITHRIL_TAX = 570L;
    private static final long MITHRIL_RECEIVED = MITHRIL_GROSS - MITHRIL_TAX;

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    @Test
    public void settledSaleKeepsItsMarketRecordWhenTheSlotIsReused()
    {
        Fixture fixture = new Fixture();
        long t = T0 + 10_000L;
        String shark = fixture.sellAndCollectAlone(0, SHARK, SHARK_QTY, SHARK_GROSS, SHARK_RECEIVED, t);

        fixture.placeAndSell(0, MITHRIL, MITHRIL_QTY, MITHRIL_GROSS, t + 30_000L);

        MarketSettlementProjection.Row retired = fixture.rowFor(shark);
        assertNotNull("the settled sale keeps its market record after slot reuse", retired);
        assertEquals(SHARK, retired.itemId);
        assertEquals(MarketSettlementProjection.Lifecycle.REALIZED, retired.lifecycle);
        assertEquals("Received stays the observed cash", SHARK_RECEIVED, retired.observedSettlementGp);
        assertEquals("the proven tax stays visible", SHARK_TAX, retired.inferredGeTaxGp);
        assertEquals("the new offer is the slot's only live lifecycle", 1, fixture.openRecords(0));
        assertEquals(0, fixture.reviewCount());
    }

    @Test
    public void reusedSlotStillSettlesTheNewOfferExactly()
    {
        Fixture fixture = new Fixture();
        long t = T0 + 10_000L;
        String shark = fixture.sellAndCollectAlone(0, SHARK, SHARK_QTY, SHARK_GROSS, SHARK_RECEIVED, t);
        String mithril = fixture.sellAndCollectAlone(0, MITHRIL, MITHRIL_QTY, MITHRIL_GROSS, MITHRIL_RECEIVED,
            t + 30_000L);

        assertNotNull(fixture.rowFor(shark));
        MarketSettlementProjection.Row second = fixture.rowFor(mithril);
        assertNotNull("the new offer in the reused slot settles through its own record", second);
        assertEquals(MITHRIL, second.itemId);
        assertEquals(MITHRIL_RECEIVED, second.observedSettlementGp);
        assertEquals(MITHRIL_TAX, second.inferredGeTaxGp);
        assertEquals("two booked sale settlements", 2, fixture.settlementTransactions());
        assertEquals(0, fixture.reviewCount());
        assertEquals("no unattributed Coins row", 0, fixture.unattributedCoinRows());
        assertEquals("Net is exactly the two taxes", -(SHARK_TAX + MITHRIL_TAX), fixture.net(t + 60_000L));
    }

    @Test
    public void settledHistorySurvivesRestartAlongsideTheLiveOffer()
    {
        Fixture fixture = new Fixture();
        long t = T0 + 10_000L;
        String shark = fixture.sellAndCollectAlone(0, SHARK, SHARK_QTY, SHARK_GROSS, SHARK_RECEIVED, t);
        fixture.placeAndSell(0, MITHRIL, MITHRIL_QTY, MITHRIL_GROSS, t + 30_000L);

        SavedState state = fixture.engine.createSavedState();
        Fixture restored = new Fixture();
        restored.engine.restore(state, t + 40_000L);

        assertNotNull("closed history is restored, not collapsed to one record per slot",
            restored.rowFor(shark));
        assertEquals(1, restored.openRecords(0));
        GeRecord live = restored.openRecord(0);
        assertEquals(MITHRIL, live.itemId);
        assertEquals(MITHRIL_QTY, live.getCapturedQty());
    }

    @Test
    public void closedHistoryIsBoundedByDroppingTheOldestFirst()
    {
        Fixture fixture = new Fixture();
        long t = T0 + 10_000L;
        List<String> settlements = new ArrayList<>();
        int total = GeCustodyLedger.MAX_CLOSED_RECORDS + 3;
        for (int index = 0; index < total; index++)
        {
            settlements.add(fixture.sellAndCollectAlone(0, SHARK, SHARK_QTY, SHARK_GROSS, SHARK_RECEIVED,
                t + index * 20_000L));
        }
        fixture.maintenance(t + total * 20_000L);

        int closed = 0;
        for (GeRecord record : fixture.engine.geCustody.snapshotRecords())
        {
            if (record.getStage() == GeRecord.Stage.CLOSED)
            {
                closed++;
            }
        }
        assertEquals(GeCustodyLedger.MAX_CLOSED_RECORDS, closed);
        assertNull("the oldest closed record is the one dropped", fixture.rowFor(settlements.get(0)));
        assertNotNull("the newest closed record is kept", fixture.rowFor(settlements.get(total - 1)));
        assertEquals("every sale stays booked", total, fixture.settlementTransactions());
    }

    // ── fixture ────────────────────────────────────────────────────────────────────────────────

    private static long reference(int itemId)
    {
        return itemId == SHARK ? 978L : itemId == MITHRIL ? 984L : 6L;
    }

    private static String name(int itemId)
    {
        return itemId == SHARK ? "Shark" : itemId == MITHRIL ? "Mithril bar" : "Item " + itemId;
    }

    private static final class Fixture
    {
        final Engine engine;
        final OfferLedger ledger = new OfferLedger();
        final Map<Integer, Long> inventory = new HashMap<>();

        Fixture()
        {
            engine = new Engine(deltas ->
            {
                List<Flow> flows = new ArrayList<>();
                for (Map.Entry<Integer, Long> delta : deltas.entrySet())
                {
                    int id = delta.getKey();
                    long unit = id == COINS ? 1L : reference(id);
                    flows.add(new Flow(id, id == COINS ? "Coins" : name(id), delta.getValue(),
                        (int) unit, delta.getValue() * unit,
                        id == COINS ? PriceSource.FACE_VALUE : PriceSource.GRAND_EXCHANGE));
                }
                return flows;
            }, new TransactionClassifier(), new GpManagerConfig()
            {
                @Override
                public ReceiptRetentionPeriod receiptRetentionDays()
                {
                    return ReceiptRetentionPeriod.DAYS_365;
                }

                @Override
                public int stabilizationTicks()
                {
                    return 0;
                }
            });
            engine.startCustomSession("Trading", SessionMode.AUTO, T0);
            inventory.put(COINS, 1_000_000L);
            engine.setBaseline(new ContainerSnapshot(inventory));
        }

        void placeAndSell(int slot, int item, long qty, long gross, long at)
        {
            inventory.put(item, qty);
            engine.setBaseline(new ContainerSnapshot(inventory));
            offer(slot, SELLING, item, (int) qty, 0, (int) reference(item), 0, at);
            inventory.put(item, 0L);
            settleAt(at + 100L);
            offer(slot, SOLD, item, (int) qty, (int) qty, (int) reference(item), (int) gross, at + 200L);
        }

        /** List, sell and collect only this slot; returns the booked settlement transaction id. */
        String sellAndCollectAlone(int slot, int item, long qty, long gross, long received, long at)
        {
            placeAndSell(slot, item, qty, gross, at);
            long collectAt = at + 10_000L;
            offer(slot, EMPTY, item, 0, 0, 0, 0, collectAt - 600L);
            engine.noteGeCollectionIntent(collectAt);
            inventory.put(COINS, inventory.getOrDefault(COINS, 0L) + received);
            settleAt(collectAt);
            GeRecord record = openOrClosedRecord(slot, item);
            assertNotNull("the collected offer has a record", record);
            assertFalse("the single collect settled the offer", record.getSettlementId().isEmpty());
            return record.getSettlementId();
        }

        void maintenance(long at)
        {
            for (Transaction booking : engine.geCustody.maintenance(at,
                engine.getActiveSession().getId()).countedBookings)
            {
                engine.getActiveSession().addTransaction(booking, 500, true);
            }
        }

        private void offer(int slot, GrandExchangeOfferState state, int item, int total, int traded,
            int price, int spent, long at)
        {
            OfferLedger.Transition transition = ledger.observe(
                new OfferLedger.Snapshot(slot, state, item, total, traded, price, spent)).orElse(null);
            if (transition != null)
            {
                engine.noteGeOfferObservation(transition, name(item), at);
            }
        }

        private void settleAt(long at)
        {
            engine.markInventoryDirty();
            ContainerSnapshot snapshot = new ContainerSnapshot(inventory);
            engine.processIfDirty(snapshot, at);
            engine.processIfDirty(snapshot, at + 1L);
        }

        MarketSettlementProjection.Row rowFor(String settlementId)
        {
            for (MarketSettlementProjection.Row row : engine.getMarketSettlements())
            {
                if (settlementId.equals(row.settlementId))
                {
                    return row;
                }
            }
            return null;
        }

        GeRecord openOrClosedRecord(int slot, int item)
        {
            GeRecord found = null;
            for (GeRecord record : engine.geCustody.snapshotRecords())
            {
                if (record.slot == slot && record.itemId == item)
                {
                    found = record;
                }
            }
            return found;
        }

        GeRecord openRecord(int slot)
        {
            for (GeRecord record : engine.geCustody.snapshotRecords())
            {
                if (record.slot == slot && record.getStage() != GeRecord.Stage.CLOSED)
                {
                    return record;
                }
            }
            return null;
        }

        int openRecords(int slot)
        {
            int open = 0;
            for (GeRecord record : engine.geCustody.snapshotRecords())
            {
                if (record.slot == slot && record.getStage() != GeRecord.Stage.CLOSED)
                {
                    open++;
                }
            }
            return open;
        }

        int settlementTransactions()
        {
            int count = 0;
            for (Transaction transaction : engine.getActiveSession().getTransactions())
            {
                if (transaction.getAutomaticType() == TransactionType.TRADE && transaction.isCounted())
                {
                    count++;
                }
            }
            return count;
        }

        int reviewCount()
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

        int unattributedCoinRows()
        {
            int rows = 0;
            for (Transaction transaction : engine.getActiveSession().getTransactions())
            {
                if (transaction.getAutomaticType() == TransactionType.UNCERTAIN
                    && transaction.getFlows().size() == 1
                    && transaction.getFlows().get(0).itemId == COINS)
                {
                    rows++;
                }
            }
            return rows;
        }

        long net(long at)
        {
            return engine.getMetrics(at).net;
        }
    }
}
