package com.gpmanager;

import java.util.*;
import javax.swing.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** The overview reuses retained raid receipts; browsing never creates another accounting owner. */
public class RaidInlineLootTest {
    static Transaction book(RaidHistoryTest fixture, int kind, int item, long quantity) {
        fixture.engine.ensureSession(fixture.now());
        var run = fixture.engine.raids.start(kind, "Normal", fixture.now(), false);
        var flow = new Flow(item, "Item " + item, quantity, 100, quantity * 100);
        flow.raid = run.id;
        var row = new Transaction(fixture.now(), TransactionType.LOOT, Context.GENERIC, "", "General",
                true, List.of(flow), Confidence.CONFIRMED, "", null);
        fixture.engine.add(row, false);
        fixture.engine.raids.received(run.id, fixture.engine.session(), fixture.now());
        return row;
    }
    static SidebarPanel overview(RaidHistoryTest fixture) {
        var panel = new RaidHistoryUiTest().panel(fixture.engine);
        panel.refreshNow();
        panel.shell.show(Shell.RAIDS);
        panel.raids.open(null, null);
        panel.refreshNow();
        return panel;
    }
    @Test public void overviewShowsTheLootLedgerAboveRaidsWithoutAnotherClick() throws Exception {
        var fixture = new RaidHistoryTest();
        fixture.claimed();
        SwingUtilities.invokeAndWait(() -> {
            String before = RaidHistoryUiTest.graph(fixture.engine);
            var panel = overview(fixture);
            var text = new ArrayList<String>();
            RaidHistoryUiTest.labels(panel.raids.overview, text);
            assertTrue(text.contains("All Time Raids"));
            assertNull(RaidHistoryUiTest.button(panel.raids.body, "Loot "));
            assertNull(RaidHistoryUiTest.button(panel.raids.body, "View all receipts"));
            assertNotNull(panel.raids.ledger.data);
            assertEquals("raid:all", panel.raids.ledger.data.entry.session);
            assertTrue(LedgerData.readOnly(panel.raids.ledger.data.entry));
            assertTrue(SwingUtilities.isDescendingFrom(panel.raids.ledger.income, panel.raids.overview));
            assertTrue(panel.raids.body.getComponentZOrder(panel.raids.overview)
                    < panel.raids.body.getComponentZOrder(panel.raids.history));
            assertEquals(5_000, panel.raids.ledger.data.income.total);
            assertEquals(before, RaidHistoryUiTest.graph(fixture.engine));
        });
    }
    @Test public void anOverviewRefreshReusesTheDerivedViewUntilTheRevisionChanges() throws Exception {
        var fixture = new RaidHistoryTest();
        fixture.claimed();
        SwingUtilities.invokeAndWait(() -> {
            var panel = overview(fixture);
            long revision = fixture.engine.revision();
            var first = fixture.engine.raids.read("all");
            assertEquals(5_000, panel.raids.ledger.data.income.total);
            panel.refresh();
            panel.refreshNow();
            assertEquals(revision, fixture.engine.revision());
            assertSame("a same-revision overview refresh reuses the derived view", first,
                    fixture.engine.raids.read("all"));
            fixture.engine.bookDeathFee(500_000L, null, fixture.now());
            panel.refreshNow();
            assertNotSame("a genuine change invalidates the derived view", first,
                    fixture.engine.raids.read("all"));
            assertEquals("the same-revision capture kept its canonical total", 5_000,
                    panel.raids.ledger.data.income.total);
        });
    }
    @Test public void pagingFoldingAndItemDrilldownSurviveRefreshWithoutChangingMoney() throws Exception {
        var fixture = new RaidHistoryTest();
        for (int index = 0; index < 8; index++) book(fixture, index % 4, 560 + index, 1);
        book(fixture, 0, 560, 2);
        SwingUtilities.invokeAndWait(() -> {
            var panel = overview(fixture);
            var loot = panel.raids.ledger.income;
            String before = RaidHistoryUiTest.graph(fixture.engine);
            assertEquals(8, loot.rows.size());
            assertEquals(5, loot.size);
            assertEquals(5, loot.drawn.size());
            assertEquals(2, loot.pages());
            assertTrue(loot.foldable);
            assertTrue(loot.arrow.isVisible());
            assertEquals(1_000, panel.raids.ledger.data.income.total);
            assertEquals(3, panel.raids.ledger.data.income.groups.stream()
                    .filter(group -> group.item == 560).findFirst().orElseThrow().quantity);
            loot.next.doClick();
            assertEquals(1, loot.page);
            assertEquals(3, loot.drawn.size());
            loot.setFolded(true);
            assertFalse(loot.body.isVisible());
            panel.refresh();
            panel.refreshNow();
            assertEquals(1, loot.page);
            assertTrue(loot.folded);
            loot.setFolded(false);
            var selected = loot.drawn.get(0);
            selected.open.run();
            panel.refreshNow();
            assertNotNull(panel.raids.ledger.data.detail);
            assertEquals(selected.key, panel.raids.ledger.data.detail.group.id);
            assertEquals("raid:all", panel.raids.entry.session);
            assertEquals(Shell.RAIDS, panel.shell.current);
            assertTrue(LedgerData.readOnly(panel.raids.entry));
            assertEquals(before, RaidHistoryUiTest.graph(fixture.engine));
        });
    }
    @Test public void compactedLootKeepsItsAllTimeTotalWithoutInventingItemRows() throws Exception {
        var fixture = new RaidHistoryTest();
        var row = fixture.claimed();
        var session = fixture.engine.session();
        session.transactions.remove(row);
        session.compactTransaction(row);
        session.notifyChanged();
        SwingUtilities.invokeAndWait(() -> {
            String before = RaidHistoryUiTest.graph(fixture.engine);
            var panel = overview(fixture);
            assertNotNull(panel.raids.ledger.data);
            assertTrue(panel.raids.ledger.income.rows.isEmpty());
            assertEquals("Saved loot entries", panel.raids.ledger.income.title.getToolTipText());
            assertEquals(5_000, panel.raids.ledger.data.income.total);
            assertEquals(Fmt.signed(5_000), panel.raids.ledger.income.total.getText());
            assertEquals(before, RaidHistoryUiTest.graph(fixture.engine));
        });
    }
    @Test public void oldItemActionsCannotFollowAnAccountSwitch() throws Exception {
        var fixture = new RaidHistoryTest();
        fixture.claimed();
        SwingUtilities.invokeAndWait(() -> {
            var panel = overview(fixture);
            var oldOpen = panel.raids.ledger.income.rows.get(0).open;
            panel.suspend();
            fixture.engine.restoreProfile("second", new SavedState(), fixture.now());
            book(fixture, 0, 565, 7);
            panel.resume();
            panel.refreshNow();
            panel.shell.show(Shell.RAIDS);
            panel.refreshNow();
            String before = RaidHistoryUiTest.graph(fixture.engine);
            oldOpen.run();
            panel.refreshNow();
            assertNull(panel.raids.entry.group);
            assertEquals(700, panel.raids.ledger.data.income.total);
            assertEquals(before, RaidHistoryUiTest.graph(fixture.engine));
        });
    }
}
