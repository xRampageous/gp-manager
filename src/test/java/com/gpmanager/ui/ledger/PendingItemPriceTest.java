package com.gpmanager;

import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** Unknown values retain receipts and known totals; a classification Review has its own label. */
public class PendingItemPriceTest {
    private static final long NOW = 1_700_000_000_000L;

    @Test public void pendingPricesKeepQuantityAndKnownMoneyAcrossLiveAndLedger() throws Exception {
        SwingTestSupport.onEdt(() -> {
            var engine = ReviewFixesTest.engine();
            engine.startCustom("Price pending", NOW);
            var transaction = Tx.of(NOW + 1000, TransactionType.GAIN, Context.GENERIC, "", true,
                    List.of(new Flow(995, "Coins", 100, 1, 100, PriceSource.FACE_VALUE),
                            new Flow(999999, "Undefined item", 2, 0, 0, PriceSource.UNPRICED)));
            engine.session().addTransaction(transaction, 2000, false);
            long revision = engine.revision();
            var snapshot = LiveSnapshot.capture(engine, NOW + 2000, LiveContext.NONE);
            assertTrue("unpriced receipts wait in Ledger",snapshot.recent.stream().noneMatch(row -> row.unknown));
            assertEquals(100, snapshot.net);
            assertEquals(2, transaction.quantity(999999, true));
            var entry = LedgerData.Entry.current();
            var data = LedgerData.capture(engine, NOW + 2000, entry);
            var group = data.income.groups.stream().filter(row -> row.incomplete()).findFirst().orElseThrow();
            data = LedgerData.capture(engine, NOW + 2000, entry.withSelection(null, null, group.id));
            var page = new LedgerPage(ReviewFixesTest.proxy(LedgerPage.Actions.class, Map.of()), id -> null);
            page.apply(data);
            assertEquals("Price pending", page.groupRow(group, Kit.Tone.GAIN).value);
            var item = page.items(group).stream().filter(row -> row.name.equals("Undefined item")).findFirst().orElseThrow();
            assertEquals("Price pending", item.value);
            assertEquals("", item.exact);
            assertTrue(page.receipts.rows.stream().anyMatch(row -> row.value.equals("Price pending") && row.exact.isEmpty()));
            assertEquals("Price pending", page.receiptRow(group.entries.stream()
                    .filter(row -> row.unknown).findFirst().orElseThrow()).value);
            assertEquals(PriceSource.UNPRICED, transaction.flows().get(1).source());
            assertEquals(revision, engine.revision());
            assertEquals(100, engine.metrics(NOW + 2000).net);
            return null;
        });
    }

    @Test public void aPricedReviewRequestsADecisionInsteadOfAPrice() throws Exception {
        SwingTestSupport.onEdt(() -> {
            var engine = ReviewFixesTest.engine();
            engine.startCustom("Review", NOW);
            var transaction = new Transaction(NOW + 1000, TransactionType.UNCERTAIN, Context.GENERIC,
                    "", "Review", true, List.of(new Flow(526, "Bones", 1, 100, 100, PriceSource.GRAND_EXCHANGE)),
                    Confidence.UNCERTAIN, "No action evidence", null);
            engine.session().addTransaction(transaction, 2000, false);
            var data = LedgerData.capture(engine, NOW + 2000, LedgerData.Entry.current());
            var page = new LedgerPage(ReviewFixesTest.proxy(LedgerPage.Actions.class, Map.of()), id -> null);
            page.apply(data);
            assertEquals("Review", page.review.rows.get(0).value);
            var group = SemanticProbe.capture(List.of(transaction), List.of(), "", null).groups.get(0);
            assertEquals("Review", page.groupRow(group, Kit.Tone.REVIEW).value);
            assertEquals(100, transaction.flows().get(0).value);
            assertEquals(Correction.AUTO, transaction.correction());
            return null;
        });
    }
}
