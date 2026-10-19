package com.gpmanager;

import java.awt.*;
import java.awt.event.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import javax.swing.*;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;

import static com.gpmanager.MilestoneTest.onEdt;
import static org.junit.Assert.*;

/** Direct controls preserve the existing commands while bounding the narrow sidebar. */
public class DirectHeaderControlsTest {
    private static final long NOW = 10_000L;
    private LongSupplier clock;
    @Before public void fixedClock() { clock = SafeMath.clock; SafeMath.clock = () -> NOW; }
    @After public void restoreClock() { SafeMath.clock = clock; }

    @Test public void liveHasNoGrindButtonsOrEmptyToolbarInAnyState() throws Exception {
        onEdt(() -> {
            Engine engine = TargetFreshStartTest.engine();
            LivePage page = MilestoneTest.page();
            for (int state = 0; state < 4; state++) {
                if (state == 1) engine.startCustom("Zulrah", NOW);
                if (state == 2) engine.togglePause(NOW + 1L);
                LiveSnapshot snapshot = TargetFreshStartTest.live(engine, NOW + 2L);
                snapshot.loggedOut = state == 3;
                page.apply(snapshot);
                assertNull("Live needs no permanent action toolbar", page.pageBar());
                assertNull(find(page.body(), "Grind"));
                assertNull(find(page.body(), "End"));
                assertSame("no empty badge row before the hero", page.hero, firstVisible(page.body()));
            }
            return null;
        });
    }

    @Test public void liveBadgesStillNavigateAndCollapseWhenCleared() throws Exception {
        onEdt(() -> {
            AtomicReference<LedgerData.Entry> opened = new AtomicReference<>();
            LivePage.Actions actions = (LivePage.Actions) java.lang.reflect.Proxy.newProxyInstance(
                    LivePage.Actions.class.getClassLoader(), new Class<?>[]{LivePage.Actions.class}, (p,m,a) -> {
                        if (m.getName().equals("openLedger")) opened.set((LedgerData.Entry) a[0]);
                        return m.getReturnType() == boolean.class ? false : null;
                    });
            LivePage page = new LivePage(actions, id -> null);
            LiveSnapshot snapshot = TargetFreshStartTest.live(TargetFreshStartTest.engine(), NOW);
            snapshot.reviewCount = 2;
            snapshot.pendingKeys = 1;
            page.apply(snapshot);
            assertTrue("Review belongs to Live's conditional content", SwingUtilities.isDescendingFrom(page.hero.icons, page.body()));
            assertTrue(SwingUtilities.isDescendingFrom(page.counts, page.body()));
            click(page.hero.icons.getComponent(0), MouseEvent.BUTTON3);
            assertNull("right-click does not navigate", opened.get());
            click(page.hero.icons.getComponent(0));
            assertNotNull("Review still opens its Ledger scope", opened.get());
            opened.set(null);
            ((JButton) page.counts.getComponent(0)).doClick();
            assertNotNull("Pending still opens Ledger", opened.get());
            snapshot.reviewCount = 0;
            snapshot.pendingKeys = 0;
            page.apply(snapshot);
            assertSame(page.hero, firstVisible(page.body()));
            return null;
        });
    }

    @Test public void aLongHistoryScopeCannotCrowdSearchOrActions() throws Exception {
        onEdt(() -> {
            SidebarPanel panel = panel();
            String name = "Wilderness Agility Course expedition with a very long saved history name";
            panel.ledgerEntry = new LedgerData.Entry(LedgerData.Scope.HISTORY, "history", name);
            panel.shell.show(Shell.LEDGER);
            SidebarPanelProbe.refresh(panel);
            LedgerPage page = panel.ledger;
            for (int width : new int[]{213, 225, 300, 213}) {
                size(page.bar, width);
                resize(page.scope);
                AbstractButton search = find(page.bar, "Search");
                AbstractButton more = find(page.bar, "···");
                assertNotNull(search);
                assertNotNull(more);
                Rectangle scope = bounds(page.scope, page.bar), searchBox = bounds(search, page.bar), menu = bounds(more, page.bar);
                assertTrue("scope cannot cover Search at " + width, scope.getMaxX() <= searchBox.x);
                assertTrue("actions stay inside " + width, menu.getMaxX() <= width);
                assertTrue(page.scope.getToolTipText().contains(name));
                assertTrue("dropdown cue remains", page.scope.getText().endsWith("⌄"));
                Insets in = page.scope.getInsets();
                assertTrue("scope text fits", page.scope.getFontMetrics(page.scope.getFont()).stringWidth(page.scope.getText())
                        <= page.scope.getWidth() - in.left - in.right);
            }
            return null;
        });
    }

    @Test public void ledgerSearchAndCorrectionMenuSurviveResize() throws Exception {
        onEdt(() -> {
            SidebarPanel panel = panel();
            panel.shell.show(Shell.LEDGER);
            SidebarPanelProbe.refresh(panel);
            LedgerPage page = panel.ledger;
            find(page.bar, "Search").doClick();
            SidebarPanelProbe.refresh(panel);
            page.search.setText("blood rune");
            SidebarPanelProbe.refresh(panel);
            size(page.bar, 213);
            resize(page.scope);
            assertEquals("blood rune", page.search.getText());
            JPopupMenu menu = page.moreMenu();
            for (String label : new String[]{"Undo last change", "Restore last undo"}) assertNotNull(find(menu, label));
            assertNotNull(find(menu, GameData.msg("undo-latest-correction")));
            assertTrue(((JMenuItem) menu.getComponent(0)).getText().startsWith("Show corrected"));
            return null;
        });
    }

    @Test public void archivedIsADirectFilterThatSurvivesRefresh() throws Exception {
        onEdt(() -> {
            SidebarPanel panel = panel();
            panel.engine.saveGrind("Visible route", null, null, false);
            panel.engine.saveGrind("Archived route", null, null, false).archived = true;
            panel.shell.show(Shell.GRINDS);
            SidebarPanelProbe.refresh(panel);
            AbstractButton archived = find(panel.grinds.bar, "Archived");
            assertNotNull("Archived is directly available", archived);
            assertFalse(archived.isSelected());
            assertFalse(rows(panel.grinds).contains("Archived route"));
            archived.doClick();
            SidebarPanelProbe.refresh(panel);
            assertTrue(archived.isSelected());
            assertTrue(rows(panel.grinds).contains("Archived route"));
            SidebarPanelProbe.refresh(panel);
            assertTrue(archived.isSelected());
            archived.doClick();
            SidebarPanelProbe.refresh(panel);
            assertFalse(rows(panel.grinds).contains("Archived route"));
            assertEquals(0L, panel.engine.getMetrics(NOW).net);
            return null;
        });
    }

    @Test public void longRunningNamesKeepNetAndEndReadable() throws Exception {
        onEdt(() -> {
            SidebarPanel panel = panel();
            String name = "Wilderness Agility Course & a long northern expedition";
            panel.engine.startCustom(name, NOW);
            TargetFreshStartTest.coins(panel.engine, NOW + 1L, 500L);
            panel.shell.show(Shell.GRINDS);
            SidebarPanelProbe.refresh(panel);
            GrindsPage page = panel.grinds;
            for (int width : new int[]{213, 225, 300, 213}) {
                size(page.bar, width);
                Rectangle start = bounds(find(page.bar, "Start Grind"), page.bar);
                Rectangle archived = bounds(find(page.bar, "Archived"), page.bar);
                assertTrue("Start and Archived do not overlap", start.getMaxX() <= archived.x);
                assertTrue("Archived stays visible", archived.getMaxX() <= width);
                size(page.running, width);
                resize(page.runningName);
                Rectangle title = bounds(page.runningName, page.running), end = bounds(find(page.running, "End"), page.running);
                assertTrue("name has its own row", title.getMaxY() <= end.y);
                assertTrue("End stays visible", end.getMaxX() <= width);
                assertTrue(page.runningName.getToolTipText().contains(panel.engine.getActiveSession().getName()));
                assertTrue("name fits", page.runningName.getFontMetrics(page.runningName.getFont()).stringWidth(page.runningName.getText()) <= page.runningName.getWidth());
                assertTrue("readable minimum", page.runningName.getFont().getSize2D() >= 10f);
                assertEquals("+500", page.runningNet.getText());
            }
            return null;
        });
    }

    @Test public void grindsRetainStartOpenLiveAndEndWhilePaused() throws Exception {
        onEdt(() -> {
            SidebarPanel panel = panel();
            panel.shell.show(Shell.GRINDS);
            SidebarPanelProbe.refresh(panel);
            find(panel.grinds.bar, "Start Grind").doClick();
            assertEquals("Start Grind", ShellProbe.sheetTitle(panel.shell));
            ShellProbe.typeSheet(panel.shell, "Zulrah");
            ShellProbe.pressSheet(panel.shell, "Start");
            assertTrue(panel.engine.isCustomActive());
            TargetFreshStartTest.coins(panel.engine, NOW + 1L, 500L);
            panel.shell.show(Shell.GRINDS);
            SidebarPanelProbe.refresh(panel);
            click(panel.grinds.runningName);
            assertEquals("the running name opens Live", Shell.LIVE, panel.shell.currentPage());
            panel.engine.togglePause(NOW + 2L);
            panel.shell.show(Shell.GRINDS);
            SidebarPanelProbe.refresh(panel);
            find(panel.grinds.running, "End").doClick();
            assertFalse(panel.engine.isCustomActive());
            assertEquals("", ShellProbe.sheetTitle(panel.shell));
            assertTrue("the ended Grind keeps its receipts", panel.engine.getHistory().stream().anyMatch(s -> s.metrics(NOW + 3L).net == 500L));
            return null;
        });
    }

    static SidebarPanel panel() {
        return new SidebarPanel(TargetFreshStartTest.engine(), PresentationLifecycleTest.config(), null, null, null);
    }

    static String rows(GrindsPage page) { return String.join(" ", TableRows.of(page.mine, "MY GRINDS")); }
    static Component firstVisible(JComponent root) { for (Component c : root.getComponents()) if (c.isVisible()) return c; return null; }
    static Rectangle bounds(Component child, JComponent root) { return SwingUtilities.convertRectangle(child.getParent(), child.getBounds(), root); }
    static void size(JComponent component, int width) { component.setSize(width, component.getPreferredSize().height); ActivityHeaderLayoutTest.layout(component); }
    static void resize(Component component) { component.dispatchEvent(new ComponentEvent(component, ComponentEvent.COMPONENT_RESIZED)); }
    static void click(Component component) { click(component, MouseEvent.BUTTON1); }
    static void click(Component component, int button) { for (MouseListener listener : component.getMouseListeners()) listener.mouseClicked(new MouseEvent(component, MouseEvent.MOUSE_CLICKED, NOW, 0, 1, 1, 1, false, button)); }
    static AbstractButton find(Component root, String text) {
        if (root instanceof AbstractButton && text.equals(((AbstractButton) root).getText())) return (AbstractButton) root;
        if (root instanceof Container) for (Component c : ((Container) root).getComponents()) { AbstractButton found = find(c, text); if (found != null) return found; }
        return null;
    }
}
