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
                        .filter(menu -> menu.label.startsWith("Exclude")).findFirst()
                        .orElseThrow(() -> new AssertionError("Recent must offer Exclude for a booked entry"));
            });
        }

        void open(Table.Menu menu) throws Exception {
            onEdt(() -> { menu.action.run(); panel.refreshNow(); return null; });
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
        assertEquals("Exclude booking…", menu.label);
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
        onEdt(() -> { f.panel.ledger.actions.undoCorrection(); return null; });
        assertEquals(600, f.engine.metrics(NOW + 2_000).net);
        assertEquals(2, f.saves.get());
    }

    @Test public void groupedRowsExplicitlyTargetOnlyTheLatestBooking() throws Exception {
        var f = new Fixture();
        var first = f.book(NOW + 1_000, new Flow(536, "Dragon bones", 1, 100, 100));
        var latest = f.book(NOW + 2_000, new Flow(536, "Dragon bones", 2, 100, 200));
        var menu = f.exclude(536);
        assertEquals("Exclude latest booking…", menu.label);
        f.open(menu);
        assertEquals(latest.id(), f.panel.ledger.transaction);
        assertEquals(-200, f.panel.ledger.preview.change);
        f.press("Confirm");
        assertTrue(first.counts());
        assertFalse(latest.counts());
        assertEquals(100, f.engine.metrics(NOW + 3_000).net);
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

    @Test public void aBasisLockedEntryRetainsTheLedgersRefusal() throws Exception {
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
            return f.panel.live.rowOf(recent).menu.stream().filter(item -> item.label.startsWith("Exclude"))
                    .findFirst().orElseThrow();
        });
        f.open(menu);
        f.press("Confirm");
        assertTrue("Recent must not bypass the existing ownership fence", gain.counts());
        assertEquals(600, f.engine.metrics(NOW + 4_000).net);
        assertTrue(onEdt(() -> ShellProbe.noticeText(f.panel.shell)).contains("Not applied"));
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
            assertFalse(f.panel.live.rowOf(row).menu.stream().anyMatch(menu -> menu.label.startsWith("Exclude")));
            row.open = false;
            row.ledgerReview = true;
            var menu = f.panel.live.rowOf(row).menu;
            assertFalse(menu.stream().anyMatch(item -> item.label.startsWith("Exclude")));
            assertTrue(menu.stream().anyMatch(item -> item.label.equals("Open in Ledger")));
            return null;
        });
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
