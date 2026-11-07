package com.gpmanager;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.*;

/** Recent's shortcut must use the same preview, confirmation and owner fences as Ledger. */
public class RecentExcludeTest {
    private static final long NOW = 1_700_000_000_000L;

    private static final class Fixture {
        final Engine engine = PresentationLifecycleTest.engine();
        final AtomicInteger saves = new AtomicInteger();
        final SidebarPanel panel;

        Fixture() throws Exception {
            JsonCodec.bind(new com.google.gson.Gson());
            engine.startCustom("Recent exclusion", NOW);
            var storage = new Persistence(null, null, null, null, engine) {
                @Override boolean scheduleSave() { saves.incrementAndGet(); return true; }
                @Override SaveStatus saveStatus() { return SaveStatus.neverSaved(); }
            };
            panel = onEdt(() -> new SidebarPanel(engine, new GpManagerConfig() {}, null, null, storage));
        }

        Transaction book(long at, Flow... flows) {
            var row = Tx.of(at, TransactionType.GAIN, Context.GENERIC, "", true, List.of(flows));
            row.confidence = Confidence.CONFIRMED;
            engine.session().addTransaction(row, 2_000, false);
            return row;
        }

        Table.Menu exclude(int item) throws Exception {
            return onEdt(() -> {
                var snapshot = LiveSnapshot.capture(engine, NOW + 10_000, LiveContext.NONE);
                var recent = snapshot.recent.stream().filter(row -> row.item == item).findFirst().orElseThrow();
                return panel.live.rowOf(recent).menu.stream()
                        .filter(menu -> menu.label.startsWith("Remove")).findFirst()
                        .orElseThrow(() -> new AssertionError("Recent must offer Exclude for a booked entry"));
            });
        }

        void open(Table.Menu menu) throws Exception {
            onEdt(() -> { menu.action.run(); panel.refreshNow(); return null; });
        }

        /** The whole-row question's own button. */
        void removeAll() throws Exception {
            onEdt(() -> {
                var button = button(panel.shell.sheet, "Remove");
                assertNotNull("Missing Remove control", button);
                button.doClick();
                panel.refreshNow();
                return null;
            });
        }

        void press(String text) throws Exception {
            onEdt(() -> {
                var button = button(panel.ledger.detail, text);
                assertNotNull("Missing " + text + " control", button);
                button.doClick();
                panel.refreshNow();
                return null;
            });
        }
    }

    @Test public void excludePreviewsThenConfirmsAndCanBeUndone() throws Exception {
        var f = new Fixture();
        var row = f.book(NOW + 1_000, new Flow(10499, "Ava's accumulator", 1, 600, 600));
        var menu = f.exclude(10499);
        assertEquals("Remove entry…", menu.label);
        f.open(menu);
        assertEquals(Correction.IGNORE, f.panel.ledger.pendingCorrection);
        assertEquals(row.id(), f.panel.ledger.transaction);
        assertEquals(-600, f.panel.ledger.preview.change);
        assertEquals("opening the preview cannot change money", 600, f.engine.metrics(NOW + 2_000).net);
        assertEquals(0, f.saves.get());

        f.press("Cancel");
        assertTrue(row.counts());
        assertEquals(0, f.saves.get());
        f.open(menu);
        f.press("Confirm");
        assertEquals(Correction.IGNORE, row.correction());
        assertEquals(0, f.engine.metrics(NOW + 2_000).net);
        assertEquals("the normal mutation path saves the decision", 1, f.saves.get());
        assertEquals(600, row.flows().get(0).unitPrice);
        assertEquals(600, row.flows().get(0).value);
        assertTrue("removed bookings leave the ordinary table", f.panel.ledger.data.income.groups.isEmpty());
        onEdt(() -> {
            LedgerPageProbe.toggleCorrected(f.panel.ledger);
            assertEquals("the separate audit remains available", 1, f.panel.ledger.corrected.rows.size());
            f.panel.ledger.corrected.rows.get(0).open.run();
            f.panel.refreshNow();
            assertNotNull("the audit still opens the removed receipt", f.panel.ledger.data.detail);
            assertEquals(row.id(), f.panel.ledger.data.detail.exact.transaction);
            return null;
        });
        onEdt(() -> { f.panel.ledger.actions.undoCorrection(); return null; });
        assertEquals(600, f.engine.metrics(NOW + 2_000).net);
        assertEquals(2, f.saves.get());
    }

    /** Owner 2026-11-02: a grouped row removes every entry behind it, as one change with one Undo. */
    @Test public void groupedRowsRemoveEveryEntryAsOneUndoableChange() throws Exception {
        var f = new Fixture();
        var first = f.book(NOW + 1_000, new Flow(536, "Dragon bones", 1, 100, 100));
        var latest = f.book(NOW + 2_000, new Flow(536, "Dragon bones", 2, 100, 200));
        var menu = f.exclude(536);
        assertEquals("Remove all 2 entries…", menu.label);
        f.open(menu);
        assertTrue("asking first changes nothing", first.counts() && latest.counts());
        assertEquals(0, f.saves.get());
        String question = onEdt(() -> text(f.panel.shell.sheet));
        assertTrue(question, question.contains("Remove all 2 entries") && question.contains("−300"));
        f.removeAll();
        assertFalse(first.counts());
        assertFalse(latest.counts());
        assertEquals(0, f.engine.metrics(NOW + 3_000).net);
        assertEquals("one save for the whole row", 1, f.saves.get());
        assertTrue("one Undo brings both back", f.engine.undoCorrection());
        assertTrue(first.counts() && latest.counts());
        assertEquals(300, f.engine.metrics(NOW + 3_000).net);
    }

    @Test public void aGroupedRemovalNeverAppliesToAChangedLedgerOrAnotherOwner() throws Exception {
        var f = new Fixture();
        var first = f.book(NOW + 1_000, new Flow(536, "Dragon bones", 1, 100, 100));
        var latest = f.book(NOW + 2_000, new Flow(536, "Dragon bones", 2, 100, 200));
        f.open(f.exclude(536));
        f.book(NOW + 2_500, new Flow(385, "Shark", 1, 900, 900));
        f.removeAll();
        assertTrue("a booking after the question makes it stale", first.counts() && latest.counts());
        assertEquals(1_200, f.engine.metrics(NOW + 3_000).net);
        var menu = f.exclude(536);
        onEdt(() -> { f.panel.suspend(); f.panel.resume(); menu.action.run(); return null; });
        assertFalse("a stale menu asks nothing", onEdt(() -> f.panel.shell.sheet.isVisible()));
        assertTrue(first.counts() && latest.counts());
    }

    @Test public void theWholeRowPreviewSaturatesAsNetDoes() throws Exception {
        var f = new Fixture();
        for (int i = 1; i <= 3; i++)
            f.book(NOW + i * 600L, new Flow(536, "Dragon bones", 2_000_000_000L, 2_000_000_000L, 4_000_000_000_000_000_000L));
        assertEquals(Long.MAX_VALUE, f.engine.metrics(NOW + 3_000).net);
        f.open(f.exclude(536));
        String question = onEdt(() -> text(f.panel.shell.sheet));
        assertTrue("removing only gains can never preview a rise: " + question,
                question.contains(Fmt.signed(-Long.MAX_VALUE)));
        f.removeAll();
        assertEquals(0, f.engine.metrics(NOW + 3_000).net);
    }

    @Test public void theWholeRowPreviewIsTheActualNetChangeBesideSaturationAndCosts() throws Exception {
        var f = new Fixture();
        var cheap = f.book(NOW + 1_000, new Flow(536, "Dragon bones", 1, 100, 100));
        var dear = f.book(NOW + 1_500, new Flow(536, "Dragon bones", 1, 200, 200));
        f.book(NOW + 2_000, new Flow(385, "Shark", 1, 900, Long.MAX_VALUE - 100));
        var cost = Tx.of(NOW + 2_500, TransactionType.CONSUMPTION, Context.GENERIC, "", true,
                List.of(new Flow(995, "Coins", -1, 50, -50)));
        cost.confidence = Confidence.CONFIRMED;
        f.engine.session().addTransaction(cost, 2_000, false);
        long before = f.engine.metrics(NOW + 3_000).net;
        f.open(f.exclude(536));
        String question = onEdt(() -> text(f.panel.shell.sheet));
        f.removeAll();
        long after = f.engine.metrics(NOW + 3_000).net;
        assertFalse(cheap.counts() || dear.counts());
        assertTrue("the Shark and the cost stay", cost.counts());
        assertNotEquals("a raw sum would say -300", -300, after - before);
        assertTrue("preview " + question + " must be the real change " + (after - before),
                question.contains("by " + Fmt.signed(after - before) + " gp"));
    }

    @Test public void aMissingOrAlreadyRemovedReceiptRefusesTheWholeRemoval() throws Exception {
        var f = new Fixture();
        var first = f.book(NOW + 1_000, new Flow(536, "Dragon bones", 1, 100, 100));
        var latest = f.book(NOW + 2_000, new Flow(536, "Dragon bones", 2, 100, 200));
        assertFalse("an unknown id refuses every row", f.engine.correctEntries(List.of(first.id(), "missing"), Correction.IGNORE));
        assertTrue(first.counts() && latest.counts());
        f.open(f.exclude(536));
        assertTrue(f.engine.correctEntries(List.of(first.id()), Correction.IGNORE));
        f.removeAll();
        assertTrue("the question no longer matches the ledger", latest.counts());
        assertEquals(200, f.engine.metrics(NOW + 3_000).net);
        assertFalse("an already-removed row refuses the batch",
                f.engine.correctEntries(List.of(first.id(), latest.id()), Correction.IGNORE));
        assertTrue(latest.counts());
    }

    @Test public void cancellingTheWholeRowQuestionChangesNothing() throws Exception {
        var f = new Fixture();
        var first = f.book(NOW + 1_000, new Flow(536, "Dragon bones", 1, 100, 100));
        var latest = f.book(NOW + 2_000, new Flow(536, "Dragon bones", 2, 100, 200));
        f.open(f.exclude(536));
        onEdt(() -> { button(f.panel.shell.sheet, "Cancel").doClick(); return null; });
        assertFalse(onEdt(() -> f.panel.shell.sheet.isVisible()));
        assertTrue(first.counts() && latest.counts());
        assertEquals(0, f.saves.get());
        assertFalse("nothing to undo", f.engine.undoCorrection());
    }

    @Test public void aQueuedWholeRowRemovalCannotCrossAnOwnerOrRunChange() throws Exception {
        for (boolean ownerSwitch : new boolean[] {true, false}) {
            var f = new Fixture();
            var first = f.book(NOW + 1_000, new Flow(536, "Dragon bones", 1, 100, 100));
            var latest = f.book(NOW + 2_000, new Flow(536, "Dragon bones", 2, 100, 200));
            f.open(f.exclude(536));
            var queued = new ArrayList<Runnable>();
            f.panel.mutationOwner = queued::add;
            f.removeAll();
            onEdt(() -> {
                if (ownerSwitch) { f.panel.suspend(); f.panel.resume(); }
                else {
                    f.engine.finishCustom(NOW + 3_000);
                    f.engine.startCustom("Next Grind", NOW + 4_000);
                }
                return null;
            });
            queued.forEach(Runnable::run);
            assertTrue(first.counts() && latest.counts());
            assertFalse("nothing to undo", f.engine.undoCorrection());
        }
    }

    @Test public void aGroupedRemovalSurvivesSaveAndReloadAsOneUndo() throws Exception {
        var f = new Fixture();
        var first = f.book(NOW + 1_000, new Flow(536, "Dragon bones", 1, 100, 100));
        var latest = f.book(NOW + 2_000, new Flow(536, "Dragon bones", 2, 100, 200));
        f.open(f.exclude(536));
        f.removeAll();
        var next = PresentationLifecycleTest.engine();
        next.restore(SaveCopies.copy(f.engine.savedState()), NOW + 5_000);
        assertEquals("the removal was saved", 0, next.metrics(NOW + 5_000).net);
        assertTrue("one Undo after reload brings both back", next.undoCorrection());
        assertEquals(300, next.metrics(NOW + 5_000).net);
        assertTrue(next.session().transactions().stream()
                .filter(row -> row.id().equals(first.id()) || row.id().equals(latest.id())).allMatch(Transaction::counts));
    }

    @Test public void aMultiItemBookingUsesItsWholeValueAndPreservesQuotes() throws Exception {
        var f = new Fixture();
        var row = f.book(NOW + 1_000, new Flow(10499, "Ava's accumulator", 1, 600, 600),
                new Flow(19564, "Royal seed pod", 1, 12, 12));
        f.open(f.exclude(10499));
        assertEquals("the preview must show the complete booking's impact", -612,
                f.panel.ledger.preview.change);
        f.press("Confirm");
        assertEquals(0, f.engine.metrics(NOW + 2_000).net);
        assertEquals(2, row.flows().size());
        assertEquals(12, row.flows().get(1).unitPrice);
    }

    @Test public void anOpenMenuCannotFollowAnOwnerOrGrindSwitch() throws Exception {
        for (boolean ownerSwitch : new boolean[] {true, false}) {
            var f = new Fixture();
            var original = f.book(NOW + 1_000, new Flow(536, "Dragon bones", 1, 100, 100));
            var menu = f.exclude(536);
            onEdt(() -> {
                if (ownerSwitch) { f.panel.suspend(); f.panel.resume(); }
                else {
                    f.engine.finishCustom(NOW + 2_000);
                    f.engine.startCustom("Next Grind", NOW + 3_000);
                }
                menu.action.run();
                return null;
            });
            assertNull("a stale Recent menu cannot open a correction on a different scope",
                    f.panel.ledger.pendingCorrection);
            assertTrue(original.counts());
            assertEquals(0, f.saves.get());
        }
    }

    @Test public void anInterveningBookingMakesThePreviewStale() throws Exception {
        var f = new Fixture();
        var original = f.book(NOW + 1_000, new Flow(536, "Dragon bones", 1, 100, 100));
        f.open(f.exclude(536));
        f.book(NOW + 2_000, new Flow(385, "Shark", 1, 900, 900));
        f.press("Confirm");
        assertTrue(original.counts());
        assertEquals(1_000, f.engine.metrics(NOW + 3_000).net);
    }

    @Test public void aQueuedConfirmationCannotCrossAnOwnerRoundTrip() throws Exception {
        var f = new Fixture();
        var row = f.book(NOW + 1_000, new Flow(536, "Dragon bones", 1, 100, 100));
        f.open(f.exclude(536));
        var queued = new ArrayList<Runnable>();
        f.panel.mutationOwner = queued::add;
        f.press("Confirm");
        assertTrue(row.counts());
        onEdt(() -> { f.panel.suspend(); f.panel.resume(); return null; });
        queued.forEach(Runnable::run);
        assertTrue(row.counts());
        assertEquals(0, f.saves.get());
    }

    @Test public void aBasisLockedEntryCanBeRemovedFinanciallyAndUndone() throws Exception {
        var f = new Fixture();
        var gain = f.book(NOW + 1_000,
                new Flow(536, "Dragon bones", 10, 100, 1_000, PriceSource.GRAND_EXCHANGE));
        f.engine.session().addTransaction(Tx.of(NOW + 2_000, TransactionType.CONSUMPTION,
                Context.GENERIC, "", true,
                List.of(new Flow(536, "Dragon bones", -4, 100, -400, PriceSource.GRAND_EXCHANGE))),
                2_000, false);
        var menu = onEdt(() -> {
            var recent = LiveSnapshot.capture(f.engine, NOW + 3_000, LiveContext.NONE).recent.stream()
                    .filter(row -> row.receiptId.equals(gain.id())).findFirst().orElseThrow();
            return f.panel.live.rowOf(recent).menu.stream().filter(item -> item.label.startsWith("Remove"))
                    .findFirst().orElseThrow();
        });
        var gson = new com.google.gson.Gson();
        String stock = gson.toJson(f.engine.basis.stock), flows = gson.toJson(gain.flows());
        f.open(menu);
        assertNotNull(onEdt(() -> button(f.panel.ledger.detail, "Confirm")));
        assertTrue(gain.counts());
        assertEquals(600, f.engine.metrics(NOW + 4_000).net);
        f.press("Confirm");
        assertEquals(Correction.IGNORE, gain.correction());
        assertEquals(-400, f.engine.metrics(NOW + 4_000).net);
        assertEquals(stock, gson.toJson(f.engine.basis.stock));
        assertEquals(flows, gson.toJson(gain.flows()));
        assertEquals(1, f.saves.get());
        assertTrue(f.engine.undoCorrection());
        assertEquals(600, f.engine.metrics(NOW + 4_000).net);
        assertEquals(stock, gson.toJson(f.engine.basis.stock));
    }

    @Test public void aLegacyToaPickaxeCanBeRemovedWithoutRemovingItsSeparateReturnCost() throws Exception {
        var f = new Fixture();
        var gain = f.book(NOW - 4 * 86_400_000L,
                new Flow(1275, "Rune pickaxe", 1, 18_635, 18_635, PriceSource.GRAND_EXCHANGE));
        f.engine.session().addTransaction(Tx.of(NOW - 3 * 86_400_000L, TransactionType.CONSUMPTION,
                Context.GENERIC, "", true,
                List.of(new Flow(1275, "Rune pickaxe", -1, 18_635, -18_635, PriceSource.GRAND_EXCHANGE))),
                2_000, false);
        var gson = new com.google.gson.Gson();
        String before = gson.toJson(gain), stock = gson.toJson(f.engine.basis.stock);
        var menu = onEdt(() -> {
            var recent = LiveSnapshot.capture(f.engine, NOW, LiveContext.NONE).recent.stream()
                    .filter(row -> row.receiptId.equals(gain.id())).findFirst().orElseThrow();
            return f.panel.live.rowOf(recent).menu.stream().filter(item -> item.label.startsWith("Remove"))
                    .findFirst().orElseThrow();
        });
        f.open(menu);
        assertNotNull(onEdt(() -> button(f.panel.ledger.detail, "Confirm")));
        assertEquals(before, gson.toJson(gain));
        assertEquals(0, f.saves.get());
        assertEquals(0, f.engine.metrics(NOW).net);
        f.press("Confirm");
        assertEquals(Correction.IGNORE, gain.correction());
        assertEquals(-18_635, f.engine.metrics(NOW).net);
        assertEquals(stock, gson.toJson(f.engine.basis.stock));
        assertEquals(18_635, gain.flows().get(0).unitPrice);
        assertEquals(1, f.saves.get());
        assertTrue(f.engine.undoCorrection());
        assertEquals(0, f.engine.metrics(NOW).net);
        assertEquals(stock, gson.toJson(f.engine.basis.stock));
    }

    @Test public void genericOldPickaxeGainsAreNeverGuessedToBeRaidReturns() throws Exception {
        var f = new Fixture();
        var first = f.book(NOW - 4 * 86_400_000L,
                new Flow(1275, "Rune pickaxe", 1, 18_635, 18_635, PriceSource.GRAND_EXCHANGE));
        var second = f.book(NOW - 3 * 86_400_000L,
                new Flow(1275, "Rune pickaxe", 1, 18_635, 18_635, PriceSource.GRAND_EXCHANGE));
        f.engine.recheck();
        assertEquals(Correction.AUTO, first.correction());
        assertEquals(Correction.AUTO, second.correction());
        assertEquals(37_270, f.engine.metrics(NOW).net);
        assertEquals(18_635, first.flows().get(0).unitPrice);
        assertEquals(18_635, second.flows().get(0).unitPrice);
    }

    @Test public void thePreviewKeepsConfirmationVisibleAtNarrowWidths() throws Exception {
        var f = new Fixture();
        f.book(NOW + 1_000, new Flow(10499, "Ava's accumulator", 1, 600, 600));
        f.open(f.exclude(10499));
        onEdt(() -> {
            var directory = java.nio.file.Path.of("build", "recent-exclude-20261029", "previews");
            java.nio.file.Files.createDirectories(directory);
            for (int width : new int[] {213, 225, 300}) {
                var body = f.panel.ledger.body;
                DirectHeaderControlsTest.size(body, width);
                var confirm = button(f.panel.ledger.detail, "Confirm");
                assertNotNull(confirm);
                var bounds = DirectHeaderControlsTest.bounds(confirm, body);
                assertTrue("Confirm must stay within " + width + " px", bounds.x >= 0 && bounds.getMaxX() <= width);
                var image = new java.awt.image.BufferedImage(width, body.getHeight(),
                        java.awt.image.BufferedImage.TYPE_INT_ARGB);
                var graphics = image.createGraphics();
                try { body.printAll(graphics); } finally { graphics.dispose(); }
                assertTrue(javax.imageio.ImageIO.write(image, "png", directory.resolve("exclude-" + width + ".png").toFile()));
            }
            assertTrue(f.engine.session().transactions().get(0).counts());
            return null;
        });
    }

    @Test public void openOffersAndUncountedReviewRowsKeepTheirExistingActions() throws Exception {
        var f = new Fixture();
        f.book(NOW + 1_000, new Flow(536, "Dragon bones", 1, 100, 100));
        onEdt(() -> {
            var row = LiveSnapshot.capture(f.engine, NOW + 2_000, LiveContext.NONE).recent.get(0);
            row.open = true;
            assertFalse(f.panel.live.rowOf(row).menu.stream().anyMatch(menu -> menu.label.startsWith("Remove")));
            row.open = false;
            row.ledgerReview = true;
            var menu = f.panel.live.rowOf(row).menu;
            assertFalse(menu.stream().anyMatch(item -> item.label.startsWith("Remove")));
            assertTrue(menu.stream().anyMatch(item -> item.label.equals("Open in Ledger")));
            return null;
        });
    }

    private static String text(Container root) {
        var all = new StringBuilder();
        for (Component child : root.getComponents()) {
            if (child instanceof javax.swing.JLabel) all.append(((javax.swing.JLabel) child).getText()).append(' ');
            if (child instanceof javax.swing.text.JTextComponent)
                all.append(((javax.swing.text.JTextComponent) child).getText()).append(' ');
            if (child instanceof Container) all.append(text((Container) child));
        }
        return all.toString();
    }

    private static JButton button(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton && text.equals(((JButton) child).getText())) return (JButton) child;
            if (child instanceof Container) {
                var found = button((Container) child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T onEdt(Callable<T> work) throws Exception {
        Object[] value = new Object[1];
        Exception[] failure = new Exception[1];
        SwingUtilities.invokeAndWait(() -> {
            try { value[0] = work.call(); } catch (Exception ex) { failure[0] = ex; }
        });
        if (failure[0] != null) throw failure[0];
        return (T) value[0];
    }
}
