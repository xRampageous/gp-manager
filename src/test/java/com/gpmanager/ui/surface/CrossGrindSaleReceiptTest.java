package com.gpmanager;

import com.gpmanager.GpManagerConfig.Retention;

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
        fixture.engine.startCustom("Grind A", T0);
        fixture.engine.setBaseline(new ContainerSnapshot(fixture.inventory));
        String grindA = fixture.engine.session().id();
        fixture.placeAndSell(0, SHARK, QTY, GROSS, T0 + 1_000L);

        fixture.engine.finishCustom(T0 + 5_000L);
        fixture.engine.startCustom("Grind B", T0 + 6_000L);
        fixture.engine.setBaseline(new ContainerSnapshot(fixture.inventory));
        Session grindB = fixture.engine.session();
        fixture.collectAlone(0, SHARK, RECEIVED, T0 + 10_000L);

        Transaction settlement = fixture.settlement();
        assertNotNull(settlement);
        assertTrue("counted in the collecting Grind", settlement.counts());
        assertTrue(grindB.transactions().contains(settlement));
        assertFalse(ReviewRules.needsReview(settlement));
        assertEquals("Grind B pays exactly the proven tax", -TAX, fixture.engine.metrics(T0 + 11_000L).net);

        MarketRows.Row row = fixture.row(SHARK);
        assertEquals("the row follows its settlement's session", grindB.id(), row.scopeSessionId);
        assertEquals("Grind A", row.listedDuring);
        List<String> lines = MarketText.humanLines(row, false);
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

        fixture.engine.startCustom("Grind B", T0 + 6_000L);
        fixture.engine.setBaseline(new ContainerSnapshot(fixture.inventory));
        fixture.collectAlone(0, SHARK, RECEIVED, T0 + 10_000L);

        MarketRows.Row row = fixture.row(SHARK);
        assertEquals("Overall", row.listedDuring);
        assertTrue(MarketText.humanLines(row, false).contains("Listed during|Overall"));
    }

    @Test
    public void sameSessionSaleHasNoListedDuringLabel()
    {
        Fixture fixture = new Fixture();
        fixture.engine.startCustom("Grind A", T0);
        fixture.engine.setBaseline(new ContainerSnapshot(fixture.inventory));
        fixture.placeAndSell(0, SHARK, QTY, GROSS, T0 + 1_000L);
        fixture.collectAlone(0, SHARK, RECEIVED, T0 + 10_000L);

        MarketRows.Row row = fixture.row(SHARK);
        assertEquals("", row.listedDuring);
        assertFalse(MarketText.humanLines(row, false).stream()
            .anyMatch(line -> line.startsWith("Listed during")));
    }

    private static final class Fixture
    {
        final Engine engine;
        final OfferSlots ledger = new OfferSlots();
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
                public Retention receiptRetentionDays()
                {
                    return Retention.DAYS_365;
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
            engine.noteGeCollect(at);
            inventory.put(COINS, inventory.getOrDefault(COINS, 0L) + received);
            settleAt(at);
        }

        Transaction settlement()
        {
            String id = row(SHARK).settlementId;
            for (Transaction transaction : engine.session().transactions())
            {
                if (transaction.id().equals(id))
                {
                    return transaction;
                }
            }
            return null;
        }

        MarketRows.Row row(int itemId)
        {
            for (MarketRows.Row row : engine.marketRows())
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
            OfferSlots.Transition transition = ledger.observe(
                new OfferSlots.Snapshot(slot, state, item, total, traded, price, spent)).orElse(null);
            if (transition != null)
            {
                engine.noteGeOffer(transition, "Shark", at);
            }
        }

        private void settleAt(long at)
        {
            engine.markDirty();
            ContainerSnapshot snapshot = new ContainerSnapshot(inventory);
            engine.processIfDirty(snapshot, at, null);
            engine.processIfDirty(snapshot, at + 1L, null);
        }
    }
}
