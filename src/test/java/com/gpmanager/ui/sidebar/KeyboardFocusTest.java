package com.gpmanager;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Offline component-model tests. No native key injection, global dispatcher or game input. */
public class KeyboardFocusTest {
    static class Label extends JLabel {
        boolean shown = true;
        boolean owner = true;
        int requested;
        @Override public boolean isShowing() { return shown; }
        @Override public boolean isFocusOwner() { return owner; }
        @Override public boolean requestFocusInWindow() { requested++; return true; }
    }
    static class Panel extends JPanel {
        @Override public boolean isShowing() { return true; }
    }
    static class Menu extends JPopupMenu {
        int shown;
        @Override public void show(Component c, int x, int y) { shown++; }
        @Override public void menuSelectionChanged(boolean included) { }
    }
    static void keyboard(JComponent c, String name, Runnable action) { Kit.keyboard(c, name, action); }
    static void cancel(JComponent c) { Kit.cancelKeys(c); }
    static JComponent target(Component c, String name) { return Kit.focusTarget(c, name); }
    static void fire(JComponent component, String stroke) {
        Object key = component.getInputMap().get(KeyStroke.getKeyStroke(stroke));
        Action action = key == null ? null : component.getActionMap().get(key);
        assertNotNull("Binding for " + stroke, action);
        action.actionPerformed(new ActionEvent(component, ActionEvent.ACTION_PERFORMED, stroke));
    }
    static void edt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }

    @Test public void releaseActivatesOnceAndFocusLossDisarms() throws Exception {
        edt(() -> {
            Label label = new Label(); AtomicInteger runs = new AtomicInteger();
            keyboard(label, "Open receipt", runs::incrementAndGet);
            assertTrue(label.isFocusable());
            assertEquals("Open receipt", label.getAccessibleContext().getAccessibleName());
            fire(label, "SPACE"); fire(label, "SPACE");
            fire(label, "released SPACE"); fire(label, "released SPACE");
            assertEquals(1, runs.get());
            fire(label, "ENTER");
            for (FocusListener listener : label.getFocusListeners()) listener.focusLost(new FocusEvent(label, FocusEvent.FOCUS_LOST));
            fire(label, "released ENTER");
            assertEquals(1, runs.get());
            fire(label, "ENTER"); fire(label, "released ENTER");
            assertEquals(2, runs.get());
        });
    }
    @Test public void hiddenAndDisabledControlsDoNotActivate() throws Exception {
        edt(() -> {
            Label label = new Label(); AtomicInteger runs = new AtomicInteger();
            keyboard(label, "Open receipt", runs::incrementAndGet);
            label.shown = false; fire(label, "ENTER"); fire(label, "released ENTER");
            label.shown = true; label.setEnabled(false); fire(label, "SPACE"); fire(label, "released SPACE");
            assertEquals(0, runs.get());
        });
    }
    @Test public void nativeEnterUsesSpaceAndContextKeysUseTheCurrentMenu() throws Exception {
        edt(() -> {
            JButton button = Kit.button("Run", false, () -> { });
            JCheckBox checkbox = new JCheckBox("Keep previous clock"); Kit.focus(checkbox);
            assertFalse("checkboxes do not draw native focus outlines", checkbox.isFocusPainted());
            for (String prefix : new String[]{"", "released "})
                assertEquals(button.getInputMap().get(KeyStroke.getKeyStroke(prefix + "SPACE")),
                    button.getInputMap().get(KeyStroke.getKeyStroke(prefix + "ENTER")));
            Label label = new Label(); keyboard(label, "Receipt", () -> { });
            Menu old = new Menu(); label.setComponentPopupMenu(old); fire(label, "shift F10");
            Menu current = new Menu(); label.setComponentPopupMenu(current); fire(label, "CONTEXT_MENU");
            assertEquals(1, old.shown); assertEquals(1, current.shown);
        });
    }
    @Test public void refreshedRowsRestoreStableNamesAndSkipDisabledOrRemovedTargets() throws Exception {
        edt(() -> {
            Panel root = new Panel(); Label old = new Label(); old.setName("Receipt A");
            Label current = new Label(); keyboard(current, "Receipt A", () -> { }); root.add(current);
            Kit.restoreFocus(root, old);
            assertEquals(1, current.requested);
            current.setFocusable(false); assertNull(target(root, null)); current.setFocusable(true);
            Label disabled = new Label(); keyboard(disabled, "disabled", () -> { }); disabled.setEnabled(false); root.add(disabled);
            root.add(new JPanel());
            Label next = new Label(); keyboard(next, "next", () -> { }); root.add(next);
            Kit.moveFocus(current, 1);
            assertEquals(1, next.requested); assertEquals(0, disabled.requested);
            root.remove(current);
            Kit.restoreFocus(root, old);
            assertEquals(2, next.requested);
        });
    }
    @Test public void focusKeepsOriginalBordersInsetsAndGraphicsWithoutAnOutline() throws Exception {
        edt(() -> {
            Label label = new Label(); label.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
            Insets insets = label.getInsets(); keyboard(label, "Open receipt", () -> { });
            assertEquals(insets, label.getInsets());
            BufferedImage image = new BufferedImage(40, 20, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics(); graphics.setColor(Color.MAGENTA);
            label.getBorder().paintBorder(label, graphics, 0, 0, 40, 20);
            assertEquals(Color.MAGENTA, graphics.getColor()); graphics.dispose();
            assertEquals(0, image.getRGB(0, 0));
            label = new Label(); label.setBorder(BorderFactory.createLineBorder(Kit.ACCENT));
            keyboard(label, "Primary", () -> { }); graphics = image.createGraphics();
            label.getBorder().paintBorder(label, graphics, 0, 0, 40, 20); graphics.dispose();
            assertEquals(Kit.ACCENT.getRGB(), image.getRGB(0, 0));
        });
    }
    static void assertBorderColor(JComponent control, int color) {
        BufferedImage image = new BufferedImage(40, 20, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        if (control.getBorder() != null) control.getBorder().paintBorder(new Label(), graphics, 0, 0, 40, 20);
        graphics.dispose();
        assertEquals("focus keeps the original border appearance", color, image.getRGB(0, 0));
    }
    @Test public void paddingAfterKeyboardSetupKeepsExactInsetsWithoutAnOutline() throws Exception {
        edt(() -> {
            Label label = new Label(); keyboard(label, "Padded action", () -> { });
            Kit.pad(label, 3, 2, 3, 2);
            assertEquals(new Insets(3, 2, 3, 2), label.getInsets());
            assertBorderColor(label, 0);
        });
    }
    @Test public void compactSessionButtonsKeepTheirOriginalBorderAfterStyling() throws Exception {
        edt(() -> {
            SidebarPanel panel = DirectHeaderControlsTest.panel();
            try {
                panel.grindActions.newSession();
                for (String name : new String[]{"New session", "Restore time"}) {
                    AbstractButton button = DirectHeaderControlsTest.find(panel.shell.sheet, name);
                    assertNotNull(name, button);
                    assertEquals(new Insets(4, 5, 4, 5), button.getInsets());
                    assertBorderColor(button, Kit.LINE.getRGB());
                }
            } finally { panel.shell.noticeTimer.stop(); }
        });
    }
    @Test public void pendingCountButtonsStayBorderlessWhenFocused() throws Exception {
        edt(() -> {
            SidebarPanel panel = DirectHeaderControlsTest.panel();
            try {
                panel.live.counts.removeAll();
                panel.live.count(1, null, "Pending", Kit.Tone.DIM, "Open pending", () -> { });
                AbstractButton button = (AbstractButton) panel.live.counts.getComponent(0);
                assertEquals(new Insets(0, 0, 0, 0), button.getInsets());
                assertFalse(button.isFocusPainted());
                assertBorderColor(button, 0);
            } finally { panel.shell.noticeTimer.stop(); }
        });
    }
    @Test public void escapeCancelsWithoutCommittingAndTabsHaveLocalNavigation() throws Exception {
        edt(() -> {
            Shell shell = new Shell(); AtomicInteger committed = new AtomicInteger();
            try {
                shell.sheet("Confirm", null, null, new Shell.Choice("Commit", true, committed::incrementAndGet));
                Object escape = shell.sheet.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(KeyStroke.getKeyStroke("ESCAPE"));
                assertNotNull("Escape cancels this sheet", escape);
                shell.sheet.getActionMap().get(escape).actionPerformed(new ActionEvent(shell.sheet, 0, "ESCAPE"));
                assertFalse(shell.sheet.isVisible()); assertEquals(0, committed.get());
                for (JComponent tab : shell.tabById.values()) {
                    assertTrue(tab.isFocusable());
                    for (String key : new String[]{"ENTER", "SPACE", "LEFT", "RIGHT", "HOME", "END"})
                        assertNotNull("Tab " + key, tab.getInputMap().get(KeyStroke.getKeyStroke(key)));
                }
            } finally { shell.noticeTimer.stop(); }
        });
    }
    @Test public void ownerBoundaryDisarmsHeldActionsAndClosesOnlyOwnedPopups() throws Exception {
        edt(() -> {
            Panel root = new Panel(); Label label = new Label(); AtomicInteger runs = new AtomicInteger();
            keyboard(label, "Receipt", runs::incrementAndGet); root.add(label);
            JButton button = Kit.button("Run", false, runs::incrementAndGet); root.add(button);
            fire(label, "ENTER"); button.getModel().setArmed(true); button.getModel().setPressed(true);
            Menu own = new Menu(); own.setInvoker(label);
            MenuSelectionManager manager = MenuSelectionManager.defaultManager();
            try {
                manager.setSelectedPath(new MenuElement[]{own}); cancel(root);
                assertEquals(0, manager.getSelectedPath().length);
                fire(label, "released ENTER"); assertEquals(0, runs.get()); assertFalse(button.getModel().isPressed());
                Menu foreign = new Menu(); foreign.setInvoker(new JPanel()); manager.setSelectedPath(new MenuElement[]{foreign});
                cancel(root); assertEquals(1, manager.getSelectedPath().length);
            } finally { manager.clearSelectedPath(); }
        });
    }
    @Test public void aMenuFromARebuiltRowStillBelongsToItsOriginalShell() throws Exception {
        edt(() -> {
            Shell shell = new Shell(); Label row = new Label();
            MenuSelectionManager manager = MenuSelectionManager.defaultManager();
            try {
                keyboard(row, "Receipt", () -> { });
                shell.add(row, BorderLayout.CENTER);
                Menu menu = new Menu(); menu.setInvoker(row);
                manager.setSelectedPath(new MenuElement[]{menu});
                // A refresh can replace a mouse-opened row before any keyboard focus event.
                shell.remove(row);
                cancel(shell);
                assertEquals("owner switches close detached old-row menus too", 0, manager.getSelectedPath().length);
            } finally { manager.clearSelectedPath(); shell.noticeTimer.stop(); }
        });
    }
    @Test public void queuedActionsCannotCrossAnOwnerChangeAndValidationIsAtomic() throws Exception {
        edt(() -> {
            SidebarPanel panel = DirectHeaderControlsTest.panel();
            java.util.List<Runnable> queued = new java.util.ArrayList<>(); AtomicInteger runs = new AtomicInteger();
            try {
                panel.mutationOwner = queued::add; panel.mutate(runs::incrementAndGet);
                panel.suspend(); panel.resume(); queued.remove(0).run(); assertEquals(0, runs.get());
                panel.mutate(() -> { assertTrue(Thread.holdsLock(panel.engine)); runs.incrementAndGet(); });
                queued.remove(0).run(); assertEquals(1, runs.get());
            } finally { panel.shell.noticeTimer.stop(); }
        });
    }
    @Test public void liveHeaderControlsAndFoldsExposeKeyboardNames() throws Exception {
        edt(() -> {
            SidebarPanel panel = DirectHeaderControlsTest.panel();
            try {
                for (JComponent component : new JComponent[]{panel.live.hero.dot, panel.live.hero.name, panel.live.hero.net, panel.live.hero.clock,
                        panel.live.runTotal, panel.live.recent.chevron}) {
                    assertTrue(component.getClass().getName(), component.isFocusable());
                    assertNotNull(component.getAccessibleContext().getAccessibleName());
                    assertNotNull(component.getInputMap().get(KeyStroke.getKeyStroke("ENTER")));
                }
            } finally { panel.shell.noticeTimer.stop(); }
        });
    }
}
