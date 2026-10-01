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
import static org.junit.Assert.assertTrue;

/**
 * G2 (owner policy 2026-09-24): a GE sale listed in one Grind and collected in another books in
 * the collecting Grind, is labelled with the listing Grind, and never appears in (or rewrites) the
 * closed origin Grind's Ledger.
 */
public class CrossGrindSaleReceiptTest
{
    private static final long T0 = System.currentTimeMillis() - 10 * 60_000L;
    private static final int COINS = ItemID.COINS;
    private static final int SHARK = ItemID.SHARK;
    private static final long QTY = 30L;
    private static final long GROSS = 29_280L;
    private static final long TAX = 570L;
    private static final long RECEIVED = GROSS - TAX;

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    @Test
    public void saleListedInOneGrindBooksAndIsLabelledInTheCollectingGrind()
    {
        Fixture fixture = new Fixture();
        fixture.engine.startCustomSession("Grind A", SessionMode.AUTO, T0);
        fixture.engine.setBaseline(new ContainerSnapshot(fixture.inventory));
        String grindA = fixture.engine.getActiveSession().getId();
        fixture.placeAndSell(0, SHARK, QTY, GROSS, T0 + 1_000L);

        fixture.engine.finishCustomSession(T0 + 5_000L);
        fixture.engine.startCustomSession("Grind B", SessionMode.AUTO, T0 + 6_000L);
        fixture.engine.setBaseline(new ContainerSnapshot(fixture.inventory));
        Session grindB = fixture.engine.getActiveSession();
        fixture.collectAlone(0, SHARK, RECEIVED, T0 + 10_000L);

        Transaction settlement = fixture.settlement();
        assertNotNull(settlement);
        assertTrue("counted in the collecting Grind", settlement.isCounted());
        assertTrue(grindB.getTransactions().contains(settlement));
        assertFalse(ReviewEligibility.needsOwnerDecision(settlement));
        assertEquals("Grind B pays exactly the proven tax", -TAX, fixture.engine.getMetrics(T0 + 11_000L).net);

        MarketSettlementProjection.Row row = fixture.row(SHARK);
        assertEquals("the row follows its settlement's session", grindB.getId(), row.scopeSessionId);
        assertEquals("Grind A", row.listedDuring);
        List<String> lines = MarketText.marketHumanLines(row, false);
        assertTrue("labelled with the listing Grind: " + lines, lines.contains("Listed during|Grind A"));
        assertTrue(lines.contains("Received|" + Fmt.exact(RECEIVED) + " gp"));

        LedgerData current = fixture.capture(LedgerData.Scope.CURRENT_GRIND, null, null);
        assertEquals("the collecting Grind shows the sale once", 1, current.market.receipts);
        LedgerData origin = fixture.capture(LedgerData.Scope.HISTORY, grindA, "Grind A");
        assertEquals("the closed origin Grind is not rewritten", 0, origin.market.receipts);
        assertEquals(0L, origin.net);
    }

    @Test
    public void saleListedInFreePlayIsLabelledOverallInTheGrind()
    {
        Fixture fixture = new Fixture();
        fixture.engine.ensureSession(T0);
        fixture.engine.setBaseline(new ContainerSnapshot(fixture.inventory));
        fixture.placeAndSell(0, SHARK, QTY, GROSS, T0 + 1_000L);

        fixture.engine.startCustomSession("Grind B", SessionMode.AUTO, T0 + 6_000L);
        fixture.engine.setBaseline(new ContainerSnapshot(fixture.inventory));
        fixture.collectAlone(0, SHARK, RECEIVED, T0 + 10_000L);

        MarketSettlementProjection.Row row = fixture.row(SHARK);
        assertEquals("Overall", row.listedDuring);
        assertTrue(MarketText.marketHumanLines(row, false).contains("Listed during|Overall"));
    }

    @Test
    public void sameSessionSaleHasNoListedDuringLabel()
    {
        Fixture fixture = new Fixture();
        fixture.engine.startCustomSession("Grind A", SessionMode.AUTO, T0);
        fixture.engine.setBaseline(new ContainerSnapshot(fixture.inventory));
        fixture.placeAndSell(0, SHARK, QTY, GROSS, T0 + 1_000L);
        fixture.collectAlone(0, SHARK, RECEIVED, T0 + 10_000L);

        MarketSettlementProjection.Row row = fixture.row(SHARK);
        assertEquals("", row.listedDuring);
        assertFalse(MarketText.marketHumanLines(row, false).stream()
            .anyMatch(line -> line.startsWith("Listed during")));
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
                    long unit = id == COINS ? 1L : 978L;
                    flows.add(new Flow(id, id == COINS ? "Coins" : "Shark", delta.getValue(),
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
            inventory.put(COINS, 1_000_000L);
        }

        void placeAndSell(int slot, int item, long qty, long gross, long at)
        {
            inventory.put(item, qty);
            engine.setBaseline(new ContainerSnapshot(inventory));
            offer(slot, SELLING, item, (int) qty, 0, 978, 0, at);
            inventory.put(item, 0L);
            settleAt(at + 100L);
            offer(slot, SOLD, item, (int) qty, (int) qty, 978, (int) gross, at + 200L);
        }

        void collectAlone(int slot, int item, long received, long at)
        {
            offer(slot, EMPTY, item, 0, 0, 0, 0, at - 600L);
            engine.noteGeCollectionIntent(at);
            inventory.put(COINS, inventory.getOrDefault(COINS, 0L) + received);
            settleAt(at);
        }

        Transaction settlement()
        {
            String id = row(SHARK).settlementId;
            for (Transaction transaction : engine.getActiveSession().getTransactions())
            {
                if (transaction.getId().equals(id))
                {
                    return transaction;
                }
            }
            return null;
        }

        MarketSettlementProjection.Row row(int itemId)
        {
            for (MarketSettlementProjection.Row row : engine.getMarketSettlements())
            {
                if (row.itemId == itemId)
                {
                    return row;
                }
            }
            throw new AssertionError("no market row for " + itemId);
        }

        LedgerData capture(LedgerData.Scope scope, String historyId, String historyName)
        {
            return LedgerData.capture(engine, T0 + 20_000L,
                new LedgerData.Entry(scope, historyId, historyName,
                    LedgerData.CostView.SUPPLIES, "", null, null, null, null));
        }

        private void offer(int slot, GrandExchangeOfferState state, int item, int total, int traded,
            int price, int spent, long at)
        {
            OfferLedger.Transition transition = ledger.observe(
                new OfferLedger.Snapshot(slot, state, item, total, traded, price, spent)).orElse(null);
            if (transition != null)
            {
                engine.noteGeOfferObservation(transition, "Shark", at);
            }
        }

        private void settleAt(long at)
        {
            engine.markInventoryDirty();
            ContainerSnapshot snapshot = new ContainerSnapshot(inventory);
            engine.processIfDirty(snapshot, at);
            engine.processIfDirty(snapshot, at + 1L);
        }
    }
}
