package com.gpmanager;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class RemovedEntryVisibilityTest {
    static final long NOW = 1_700_000_000_000L;

    @Test public void removedBookingLeavesTheTablesAndUndoRestoresItsOriginalQuote() {
        var engine = PresentationLifecycleTest.engine();
        engine.startCustom("Removed entry", NOW);
        var row = Tx.of(NOW + 1, TransactionType.GAIN, Context.GENERIC, "", true,
                List.of(new Flow(10499, "Ava's accumulator", 1, 600, 600),
                        new Flow(19564, "Royal seed pod", 1, 12, 12)));
        engine.add(row, false);
        assertEquals(612, engine.metrics(NOW + 2).net);
        assertTrue(engine.correctTransaction(row.id(), Correction.IGNORE, "Removed by player"));
        assertEquals(0, engine.metrics(NOW + 2).net);
        assertTrue("removed entries must not become neutral Recent rows",
                LiveSnapshot.capture(engine, NOW + 2, LiveContext.NONE).recent.isEmpty());
        var data = LedgerData.capture(engine, NOW + 2, LedgerData.Entry.current());
        assertTrue(data.income.groups.isEmpty());
        assertTrue(data.cost.groups.isEmpty());
        assertTrue(data.market.groups.isEmpty());
        assertFalse("the explicit correction audit remains available", data.corrected.isEmpty());
        assertSame("unchanged reads reuse the revision's correction projection", data.corrected.get(0),
                LedgerData.capture(engine, NOW + 3, LedgerData.Entry.current()).corrected.get(0));
        assertNull(LedgerData.capture(engine, NOW + 2,
                LedgerData.Entry.current().withSelection(row.id(), null, null)).detail);
        assertTrue("original receipts stay available for Undo", engine.session().transactions().contains(row));
        assertTrue(engine.undoCorrection());
        assertEquals(612, engine.metrics(NOW + 2).net);
        assertEquals(2, row.flows().size());
        assertEquals(600, row.flows().get(0).unitPrice);
        assertEquals(12, row.flows().get(1).unitPrice);
        assertFalse(LiveSnapshot.capture(engine, NOW + 2, LiveContext.NONE).recent.isEmpty());
    }

    @Test public void removedRaidLootStaysHiddenAfterReloadWithoutRemovingTheAttempt() {
        var fixture = new RaidHistoryTest();
        var row = fixture.claimed();
        String attempt = fixture.id();
        assertTrue(fixture.engine.correctTransaction(row.id(), Correction.IGNORE, "Removed by player"));
        var gson = new com.google.gson.Gson();
        var saved = gson.fromJson(gson.toJson(fixture.engine.exportState()), SavedState.class);
        var restored = ReviewFixesTest.engine();
        restored.restore(saved, fixture.now());
        for (String id : new String[] {"all", attempt}) {
            var raid = restored.raids.read(id);
            assertEquals(0, raid.session.metrics(fixture.now()).net);
            assertTrue("removed raid loot must not survive as an item row",
                    raid.view().scope(List.of(raid.session)).groups.groups.isEmpty());
        }
        assertNotNull(restored.raids.find(attempt));
        assertTrue(restored.undoCorrection());
        assertEquals(5_000, restored.raids.read("all").session.metrics(fixture.now()).net);
    }

    @Test public void removingOneBookingKeepsOtherBookingsAndNeutralTransfers() {
        var engine = PresentationLifecycleTest.engine();
        engine.startCustom("Partial removal", NOW);
        var first = Tx.of(NOW + 1, TransactionType.GAIN, Context.GENERIC, "", true,
                List.of(new Flow(536, "Dragon bones", 1, 100, 100)));
        var second = Tx.of(NOW + 2, TransactionType.GAIN, Context.GENERIC, "", true,
                List.of(new Flow(536, "Dragon bones", 2, 100, 200)));
        var transfer = Tx.of(NOW + 3, TransactionType.TRANSFER, Context.TRANSFER, "Bank transfer", false,
                List.of(new Flow(385, "Shark", 1, 900, 900)));
        engine.add(first, false);
        engine.add(second, false);
        engine.add(transfer, false);
        assertTrue(engine.correctTransaction(second.id(), Correction.IGNORE, "Removed by player"));
        var snapshot = LiveSnapshot.capture(engine, NOW + 4, LiveContext.NONE);
        assertEquals(100, snapshot.net);
        assertTrue(snapshot.recent.stream().noneMatch(entry -> entry.receiptId.equals(second.id())));
        assertTrue(snapshot.recent.stream().anyMatch(entry -> entry.receiptId.equals(first.id())));
        assertTrue(Semantics.capture(List.of(transfer), List.of(), java.util.Set.of(engine.session().id()), null)
                .groups.stream().anyMatch(group -> group.neutral));
    }
}
