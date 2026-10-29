package com.gpmanager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JMenuItem;
import org.junit.Test;

import static com.gpmanager.MilestoneTest.onEdt;
import static org.junit.Assert.*;

/** The global cog uses the existing correction stack, with captured owner and run fences. */
public class CogRemovalUndoTest {
    static final long NOW = 1_700_000_000_000L;

    static class Fixture {
        final Engine engine = PresentationLifecycleTest.engine();
        final AtomicInteger saves = new AtomicInteger();
        final SidebarPanel panel;

        Fixture() throws Exception {
            JsonCodec.bind(new com.google.gson.Gson());
            engine.startCustom("Current run", NOW);
            var storage = new Persistence(null, null, null, null, engine) {
                @Override boolean scheduleSave() { saves.incrementAndGet(); return true; }
                @Override SaveStatus saveStatus() { return SaveStatus.neverSaved(); }
            };
            panel = onEdt(() -> new SidebarPanel(engine, new GpManagerConfig() {}, null, null, storage));
        }

        Transaction book(int item, long value, long at) {
            var row = Tx.of(at, TransactionType.GAIN, Context.GENERIC, "", true,
                    List.of(new Flow(item, "Item " + item, 1, value, value, PriceSource.GRAND_EXCHANGE)));
            engine.session().addTransaction(row, 2_000, false);
            return row;
        }

        void click(String label) throws Exception {
            var item = action(label);
            onEdt(() -> { item.doClick(); return null; });
        }

        JMenuItem action(String label) throws Exception {
            return onEdt(() -> {
                for (var component : SidebarPanelProbe.dataMenu(panel).getComponents())
                    if (component instanceof JMenuItem && label.equals(((JMenuItem) component).getText()))
                        return (JMenuItem) component;
                throw new AssertionError("Missing cog action: " + label);
            });
        }
    }

    @Test public void noRemovalHasADisabledUndoAction() throws Exception {
        var fixture = new Fixture();
        assertFalse(fixture.action("Undo last removal").isEnabled());
        assertEquals(0, fixture.saves.get());
    }

    @Test public void theCogRestoresTheRemovedBookingAndOriginalQuote() throws Exception {
        var fixture = new Fixture();
        var row = fixture.book(1275, 18_635, NOW + 1);
        assertTrue(fixture.engine.correctTransaction(row.id(), Correction.IGNORE, "Removed by player"));
        var action = fixture.action("Undo last removal");
        assertTrue(action.isEnabled());
        onEdt(() -> { action.doClick(); return null; });
        assertEquals(Correction.AUTO, row.correction());
        assertEquals(18_635, row.flows().get(0).unitPrice);
        assertEquals(18_635, fixture.engine.metrics(NOW + 10).net);
        assertEquals(1, fixture.saves.get());
        assertFalse(fixture.action("Undo last removal").isEnabled());
    }

    @Test public void financialRemovalKeepsCogUndoAvailableAfterLaterUse() throws Exception {
        var fixture = new Fixture();
        var row = fixture.book(1275, 18_635, NOW + 1);
        assertTrue(fixture.engine.removeEntry(row.id(), "Removed by player"));
        fixture.engine.session().addTransaction(Tx.of(NOW + 2, TransactionType.CONSUMPTION,
                Context.GENERIC, "", true,
                List.of(new Flow(1275, "Rune pickaxe", -1, 18_635, -18_635,
                        PriceSource.GRAND_EXCHANGE))), 2_000, false);
        assertEquals(-18_635, fixture.engine.metrics(NOW + 10).net);
        var gson = new com.google.gson.Gson();
        String stock = gson.toJson(fixture.engine.exportState().trackedBasis());
        fixture.click("Undo last removal");
        assertEquals(Correction.AUTO, row.correction());
        assertEquals(18_635, row.flows().get(0).unitPrice);
        assertEquals(0, fixture.engine.metrics(NOW + 10).net);
        assertEquals(stock, gson.toJson(fixture.engine.exportState().trackedBasis()));
        assertEquals(1, fixture.saves.get());
    }

    @Test public void staleMenusCannotUndoANewerCorrection() throws Exception {
        var fixture = new Fixture();
        var first = fixture.book(1275, 18_635, NOW + 1);
        assertTrue(fixture.engine.correctTransaction(first.id(), Correction.IGNORE, "first"));
        var stale = fixture.action("Undo last removal");
        var second = fixture.book(10499, 600, NOW + 2);
        assertTrue(fixture.engine.correctTransaction(second.id(), Correction.IGNORE, "second"));
        onEdt(() -> { stale.doClick(); return null; });
        assertEquals(Correction.IGNORE, first.correction());
        assertEquals(Correction.IGNORE, second.correction());
        fixture.click("Undo last removal");
        assertEquals(Correction.IGNORE, first.correction());
        assertEquals(Correction.AUTO, second.correction());
        assertEquals(600, fixture.engine.metrics(NOW + 10).net);
    }

    @Test public void laterNonRemovalCorrectionsAreLabelledAndUndoneHonestly() throws Exception {
        var fixture = new Fixture();
        var removed = fixture.book(1275, 18_635, NOW + 1);
        assertTrue(fixture.engine.correctTransaction(removed.id(), Correction.IGNORE, "removed"));
        var other = fixture.book(995, 600, NOW + 2);
        assertTrue(fixture.engine.correctTransaction(other.id(), Correction.COST, "cost"));
        fixture.click("Undo latest correction");
        assertEquals(Correction.AUTO, other.correction());
        assertEquals(Correction.IGNORE, removed.correction());
        assertTrue(fixture.action("Undo last removal").isEnabled());
        assertEquals(600, fixture.engine.metrics(NOW + 10).net);
    }

    @Test public void menusAndQueuedCallbacksCannotCrossOwnerOrRunChanges() throws Exception {
        for (int scenario = 0; scenario < 3; scenario++) {
            var fixture = new Fixture();
            var row = fixture.book(1275, 18_635, NOW + 1);
            assertTrue(fixture.engine.correctTransaction(row.id(), Correction.IGNORE, "removed"));
            var action = fixture.action("Undo last removal");
            var queue = new ArrayList<Runnable>();
            if (scenario == 1) {
                fixture.panel.mutationOwner = queue::add;
                onEdt(() -> { action.doClick(); return null; });
            }
            if (scenario < 2)
                onEdt(() -> { fixture.panel.suspend(); fixture.panel.resume(); return null; });
            else {
                fixture.engine.finishCustom(NOW + 2);
                fixture.engine.startCustom("Another run", NOW + 3);
            }
            if (scenario == 1) queue.forEach(Runnable::run);
            else onEdt(() -> { action.doClick(); return null; });
            assertEquals(Correction.IGNORE, row.correction());
            assertEquals(0, fixture.engine.metrics(NOW + 10).net);
        }
    }

    @Test public void aLaterOwnershipFenceRefusesUndoAndExplainsWhy() throws Exception {
        var fixture = new Fixture();
        var row = fixture.book(1275, 18_635, NOW + 1);
        assertTrue(fixture.engine.correctTransaction(row.id(), Correction.IGNORE, "removed"));
        fixture.engine.basis.fenceRealized(1275, NOW + 2);
        fixture.click("Undo last removal");
        assertEquals(Correction.IGNORE, row.correction());
        assertEquals(0, fixture.engine.metrics(NOW + 10).net);
        assertEquals(0, EngineProbe.knownCoverageQty(fixture.engine, 1275));
        assertTrue(onEdt(() -> ShellProbe.noticeText(fixture.panel.shell))
                .contains(GameData.msg("correction-blocked")));
    }

    @Test public void viewingHistoryDoesNotMakeTheCogUndoThatHistoricalRun() throws Exception {
        var fixture = new Fixture();
        var old = fixture.book(1275, 18_635, NOW + 1);
        assertTrue(fixture.engine.correctTransaction(old.id(), Correction.IGNORE, "old"));
        String history = fixture.engine.session().id();
        fixture.engine.finishCustom(NOW + 2);
        fixture.engine.startCustom("New run", NOW + 3);
        fixture.panel.scope = new LedgerData.Entry(LedgerData.Scope.HISTORY, history, "Old run");
        assertFalse(fixture.action("Undo last removal").isEnabled());
        var current = fixture.book(10499, 600, NOW + 4);
        assertTrue(fixture.engine.correctTransaction(current.id(), Correction.IGNORE, "current"));
        fixture.click("Undo last removal");
        assertEquals(Correction.AUTO, current.correction());
        assertEquals(Correction.IGNORE, old.correction());
        assertEquals(600, fixture.engine.metrics(NOW + 10).net);
    }

    @Test public void reloadKeepsTheRemovalUndoableWithoutEditingSavedCopies() throws Exception {
        var fixture = new Fixture();
        var row = fixture.book(1275, 18_635, NOW + 1);
        assertTrue(fixture.engine.correctTransaction(row.id(), Correction.IGNORE, "removed"));
        var gson = new com.google.gson.Gson();
        var state = gson.fromJson(gson.toJson(fixture.engine.exportState()), SavedState.class);
        fixture.engine.restore(state, NOW + 10);
        fixture.click("Undo last removal");
        assertEquals(18_635, fixture.engine.metrics(NOW + 20).net);
        assertEquals(18_635, fixture.engine.session().findTransaction(row.id()).flows().get(0).unitPrice);
    }
}
