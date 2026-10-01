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
 * PR1-PRE-R5C.2C.6A.1A2 display contract: a quarantined settlement never shows an unobserved
 * Received, and a sale receipt is titled by its item — never by its Coins leg, even when an
 * unclaimed Coins row shares the collect.
 */
public class PartialCollectReceiptTest
{
    private static final long T0 = System.currentTimeMillis() - 10 * 60_000L;
    private static final int COINS = ItemID.COINS;
    private static final int BOLT = ItemID.BOLT_OF_LINEN;
    private static final int SHARK = ItemID.SHARK;
    private static final int SILVER = ItemID.SILVER_BAR;
    private static final long BOLT_GROSS = 4_350L;
    private static final long BOLT_NET = 4_270L;
    private static final long SHARK_NET = 28_710L;
    private static final long SILVER_NET = 7_380L;
    private static final long WRONG_COLLECT = BOLT_NET + SHARK_NET + SILVER_NET - 1L;

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    @Test
    public void ambiguousSettlementNeverShowsUnobservedReceived() throws Exception
    {
        Fixture fixture = ambiguousBoltFixture();
        MarketSettlementProjection.Row bolt = fixture.row(BOLT);
        assertTrue(bolt.collectionAmbiguous);
        assertEquals(MarketSettlementProjection.Lifecycle.AMBIGUOUS, bolt.lifecycle);
        String settlementId = bolt.settlementId;

        List<String> lines = MarketText.marketHumanLines(bolt, false);
        assertTrue("Received is never the unobserved allocation", lines.contains("Received|not proven"));
        assertFalse("no Received each without observed cash",
            lines.stream().anyMatch(line -> line.startsWith("Received each")));
        assertFalse("no Result is invented for a quarantined settlement",
            lines.stream().anyMatch(line -> line.startsWith("Result")));

        SemanticFinancialProjection.Receipt receipt = fixture.capture(settlementId, null).detail.receipts.get(0);
        LedgerPage page = fixture.openExact(settlementId, receipt.contributionId);
        assertEquals("EXACT", LedgerPageProbe.drill(page));
        List<String> texts = LedgerPageProbe.detailTexts(page);
        assertTrue("Received is not proven: " + texts, texts.contains("Received") && texts.contains("not proven"));
    }

    @Test
    public void saleReceiptIsTitledByItsItemAndNotTheCoinsLeg() throws Exception
    {
        Fixture fixture = ambiguousBoltFixture();
        String settlementId = fixture.row(BOLT).settlementId;
        LedgerData data = fixture.capture(settlementId, null);
        assertEquals("the market group owns exactly its sale receipt", 1, data.detail.receipts.size());
        SemanticFinancialProjection.Receipt receipt = data.detail.receipts.get(0);
        assertEquals("never the Coins leg", BOLT, receipt.itemId);
        assertEquals("Bolt of linen", MarketText.marketReceiptTitle(receipt));
        assertFalse("the group never lists an unclaimed Coins receipt",
            data.detail.receipts.stream().anyMatch(candidate -> candidate.itemId == COINS));

        // A stale contribution anchor that points at the settlement's Coins leg must never open a
        // receipt titled "Coins": the sale's own receipt and item title win.
        String coinsLegAnchor = settlementId + ":flow:1";
        LedgerPage page = fixture.openExact(settlementId, coinsLegAnchor);
        assertEquals("EXACT", LedgerPageProbe.drill(page));
        List<String> texts = LedgerPageProbe.detailTexts(page);
        assertTrue("the exact receipt header is the sale's item: " + texts,
            texts.contains("Bolt of linen"));
        assertFalse("Coins is never the receipt title",
            texts.stream().anyMatch(text -> text.startsWith("Coins  ")));

        // The unclaimed collect stays its own uncounted Review row.
        int reviewRows = 0;
        for (Transaction transaction : fixture.engine.getActiveSession().getTransactions())
        {
            if (transaction.getAutomaticType() == TransactionType.UNCERTAIN
                && transaction.getFlows().size() == 1
                && transaction.getFlows().get(0).itemId == COINS)
            {
                reviewRows++;
            }
        }
        assertEquals("the unclaimed movement is one Coins row", 1, reviewRows);
    }

    @Test
    public void singleCollectSaleKeepsItsMarketReceiptAfterItsSlotIsReused() throws Exception
    {
        Fixture fixture = new Fixture();
        long t = T0;
        fixture.placeAndSell(0, SHARK, 30L, 29_280L, t);
        fixture.clearSlot(0, SHARK, t + 10_000L);
        fixture.collect(SHARK_NET, t + 10_000L);
        String settlementId = fixture.row(SHARK).settlementId;

        // The owner smoke: the collected slot is reused by the next listing.
        fixture.placeAndSell(0, SILVER, 60L, 7_500L, t + 20_000L);

        SemanticFinancialProjection.Receipt receipt = fixture.capture(settlementId, null).detail.receipts.get(0);
        assertEquals(SHARK, receipt.itemId);
        LedgerPage page = fixture.openExact(settlementId, receipt.contributionId);
        List<String> texts = LedgerPageProbe.detailTexts(page);
        assertTrue("the sale stays a market receipt: " + texts,
            texts.contains("Received") && texts.contains(Fmt.exact(SHARK_NET) + " gp"));
        assertTrue("the receipt row is the received cash, never the item leg: " + texts,
            texts.contains(Fmt.signed(SHARK_NET)));
        assertFalse("never a generic trade: " + texts, texts.contains("Settled through a trade."));
    }

    // ── fixture ────────────────────────────────────────────────────────────────────────────────

    /** A three-sale collect that is one coin short: everything fails closed with slot evidence. */
    private static Fixture ambiguousBoltFixture()
    {
        Fixture fixture = new Fixture();
        long t = T0;
        fixture.placeAndSell(0, BOLT, 10L, BOLT_GROSS, t);
        fixture.placeAndSell(1, SHARK, 30L, 29_280L, t + 2_000L);
        fixture.placeAndSell(2, SILVER, 60L, 7_500L, t + 4_000L);
        fixture.clearSlot(0, BOLT, t + 10_000L);
        fixture.clearSlot(1, SHARK, t + 10_050L);
        fixture.clearSlot(2, SILVER, t + 10_100L);
        fixture.collect(WRONG_COLLECT, t + 10_000L);
        fixture.unrelatedTransfer(t + 12_500L);
        return fixture;
    }

    private static long reference(int itemId)
    {
        switch (itemId)
        {
            case BOLT: return 467L;
            case SHARK: return 978L;
            case SILVER: return 121L;
            default: return 6L;
        }
    }

    private static String name(int itemId)
    {
        switch (itemId)
        {
            case BOLT: return "Bolt of linen";
            case SHARK: return "Shark";
            case SILVER: return "Silver bar";
            default: return "Item " + itemId;
        }
    }

    private static final class Fixture
    {
        final Engine engine;
        final OfferLedger ledger = new OfferLedger();
        final Map<Integer, Long> inventory = new HashMap<>();
        long now = T0;

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
            engine.startCustomSession("Trading", SessionMode.AUTO, now);
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
            offer(slot, SOLD, item, (int) qty, (int) qty, (int) reference(item), (int) gross,
                at + 200L);
        }

        void collect(long amount, long at)
        {
            engine.noteGeCollectionIntent(at);
            inventory.put(COINS, inventory.getOrDefault(COINS, 0L) + amount);
            settleAt(at);
        }

        void clearSlot(int slot, int item, long at)
        {
            offer(slot, EMPTY, item, 0, 0, 0, 0, at);
        }

        /** An unrelated bank transfer still runs custody maintenance and closes the window. */
        void unrelatedTransfer(long at)
        {
            engine.markContext(Context.TRANSFER, 10, "Bank transfer");
            inventory.put(COINS, inventory.getOrDefault(COINS, 0L) + 1_000L);
            settleAt(at);
        }

        private void offer(int slot, GrandExchangeOfferState state, int item, int total, int traded,
            int price, int spent, long at)
        {
            OfferLedger.Transition transition = ledger.observe(
                new OfferLedger.Snapshot(slot, state, item, total, traded, price, spent))
                .orElse(null);
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

        /** Resolve the market detail by its settlement transaction (the ambiguous row's anchor). */
        LedgerData capture(String transactionId, String contributionId)
        {
            LedgerData data = LedgerData.capture(engine, now + 20_000L,
                new LedgerData.Entry(LedgerData.Scope.CURRENT_GRIND, null, null,
                    LedgerData.CostView.SUPPLIES, "", transactionId, contributionId, null, null));
            assertNotNull(data.detail);
            return data;
        }

        LedgerPage openExact(String transactionId, String contributionId) throws Exception
        {
            LedgerData data = capture(transactionId, contributionId);
            assertNotNull(data.detail.exact);
            return onEdt(() ->
            {
                LedgerPage page = new LedgerPage(new LedgerNoop(), id -> null);
                page.apply(data);
                return page;
            });
        }

        private static <T> T onEdt(java.util.concurrent.Callable<T> callable) throws Exception
        {
            if (javax.swing.SwingUtilities.isEventDispatchThread())
            {
                return callable.call();
            }
            java.util.concurrent.atomic.AtomicReference<T> value =
                new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
            javax.swing.SwingUtilities.invokeAndWait(() ->
            {
                try { value.set(callable.call()); }
                catch (Throwable ex) { failure.set(ex); }
            });
            if (failure.get() != null)
            {
                throw new AssertionError(failure.get());
            }
            return value.get();
        }
    }

    private static final class LedgerNoop implements LedgerPage.Actions
    {
        @Override public void openScopeMenu(javax.swing.JComponent anchor) { }
        @Override public void costViewChanged(LedgerData.CostView view) { }
        @Override public void searchChanged(String text) { }
        @Override public LedgerData.CorrectionPreview preview(String id,
            Correction correction)
        {
            return null;
        }
        @Override public LedgerPage.CorrectionOutcome correct(String id,
            Correction correction, long previewRevision)
        {
            return LedgerPage.CorrectionOutcome.REFUSED;
        }
        @Override public void split(String id) { }
        @Override public void undoCorrection() { }
        @Override public void decideAll(ReviewDecision decision) { }
        @Override public void refresh() { }
    }
}
