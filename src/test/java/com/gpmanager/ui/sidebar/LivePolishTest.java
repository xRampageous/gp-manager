package com.gpmanager;

import java.awt.Component;
import java.util.ArrayList;
import org.junit.Before;
import org.junit.Test;

import static com.gpmanager.MilestoneTest.onEdt;
import static com.gpmanager.TargetFreshStartTest.*;
import static org.junit.Assert.*;

/** Target resizing and account loading must never interrupt or expose another owner's Live view. */
public class LivePolishTest {
    @Before public void bindJsonForProfileRestore() {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    @Test public void runningTimeTargetSurvivesTightResizeWidths() throws Exception {
        onEdt(() -> {
            LivePage page = MilestoneTest.page();
            LiveSnapshot snapshot = target(true, false);
            page.apply(snapshot);
            for (int room : new int[]{1, 8, 20}) {
                page.target.setSize(14 + page.top.getPreferredSize().width + room, 100);
                page.apply(snapshot);
                assertEquals("TARGET", page.heading.getToolTipText());
                assertFalse("a narrow title keeps an ellipsis", page.heading.getText().isEmpty());
                assertTrue(page.heading.getFont().getSize2D() >= 10f);
            }
            return null;
        });
    }

    @Test public void everyTargetStateKeepsItsTooltipAcrossNormalAndNarrowWidths() throws Exception {
        onEdt(() -> {
            for (boolean time : new boolean[]{true, false}) for (boolean reached : new boolean[]{false, true}) {
                LivePage page = MilestoneTest.page();
                LiveSnapshot snapshot = target(time, reached);
                page.apply(snapshot);
                String tooltip = page.heading.getToolTipText();
                int controls = page.top.isVisible() ? page.top.getPreferredSize().width : 0;
                for (int width : new int[]{213, 225, 300, controls + 15}) {
                    page.target.setSize(width, 100);
                    page.apply(snapshot);
                    assertEquals(tooltip, page.heading.getToolTipText());
                    assertFalse(page.heading.getText().isEmpty());
                    assertTrue(page.heading.getFont().getSize2D() >= 10f);
                }
            }
            return null;
        });
    }

    @Test public void loadingHidesAllOldOwnerDetailsUntilTheNextSnapshot() throws Exception {
        onEdt(() -> {
            Engine engine = engine();
            engine.startCustom("Old owner", 10L);
            coins(engine, 11L, 500L);
            engine.setTargets(1_000L, null, 20L);
            SidebarPanel panel = new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null);
            SidebarPanelProbe.refresh(panel);
            LivePage page = panel.live;
            LiveSnapshot old = live(engine, 30L);
            old.possible = true;
            old.risk = 75_000L;
            old.reviews = 2;
            old.keys = 1;
            page.apply(old);
            assertTrue(visible(page.risk, panel));
            assertTrue(visible(page.total, panel));
            String owner = engine.session().id();
            long net = engine.metrics(30L).net;

            panel.suspend();

            for (Component detail : new Component[]{page.hero, page.total, page.risk,
                    page.hero.icons, page.counts, page.target, page.recent, page.offers})
                assertFalse("no old owner detail is visible: " + detail.getClass().getSimpleName(), visible(detail, panel));
            assertEquals(owner, engine.session().id());
            assertEquals(net, engine.metrics(30L).net);

            engine.restoreProfile("second-owner", new SavedState(), 40L);
            engine.startCustom("Zulrah", 41L);
            coins(engine, 42L, 700L);
            panel.resume();

            assertTrue(visible(page.hero, panel));
            assertEquals("Zulrah", page.hero.name.getText());
            assertFalse(visible(page.risk, panel));
            assertFalse(visible(page.total, panel));
            assertFalse(visible(page.target, panel));
            assertEquals(0, page.counts.getComponentCount());
            assertEquals(700L, engine.metrics(43L).net);
            assertNull("Live has no duplicate Grind controls", page.pageBar());
            return null;
        });
    }

    @Test public void anEmptyProfileRestoresTheEmptyLiveView() throws Exception {
        onEdt(() -> {
            Engine engine = engine();
            engine.startCustom("Old owner", 10L);
            coins(engine, 11L, 500L);
            SidebarPanel panel = new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null);
            SidebarPanelProbe.refresh(panel);
            panel.suspend();
            engine.restoreProfile("empty-owner", new SavedState(), 20L);
            panel.resume();
            assertTrue(visible(panel.live.hero, panel));
            assertEquals("", panel.live.hero.name.getText());
            assertEquals("—", panel.live.hero.net.getText());
            assertTrue(panel.live.recent.rows.isEmpty());
            assertFalse(visible(panel.live.target, panel));
            assertTrue(visible(panel.live.recent, panel));
            assertEquals(0L, engine.metrics(21L).net);
            return null;
        });
    }

    @Test public void loadingResetsBothRecentAndOffersPagination() throws Exception {
        onEdt(() -> {
            LivePage page = MilestoneTest.page();
            var rows = new ArrayList<Table.Row>();
            for (int i = 0; i < 20; i++) rows.add(TableRows.row("row-" + i, "Item " + i, "1", "+1", Kit.Tone.GAIN));
            for (Table table : new Table[]{page.recent, page.offers}) {
                table.setPageSize(5).setRows(rows);
                table.next.doClick();
                assertEquals(1, table.page);
            }
            page.clearOwner();
            assertEquals(0, page.recent.page);
            assertEquals(0, page.offers.page);
            return null;
        });
    }

    @Test public void switchingTabsDuringLoadingCannotRevealTheOldOwner() throws Exception {
        onEdt(() -> {
            Engine engine = engine();
            engine.startCustom("Old owner", 10L);
            coins(engine, 11L, 500L);
            SidebarPanel panel = new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null);
            panel.shell.show(Shell.GRINDS);
            SidebarPanelProbe.refresh(panel);
            assertTrue(visible(panel.plans.running, panel));
            panel.suspend();
            for (String tab : new String[]{Shell.GRINDS, Shell.LEDGER, Shell.LIVE}) {
                panel.shell.show(tab);
                SidebarPanelProbe.refresh(panel);
                Shell.Page page = panel.shell.pages.get(tab);
                assertFalse(tab + " has no old body", visible(page.body(), panel));
                assertFalse(tab + " has no old toolbar", visible(page.pageBar(), panel));
            }
            engine.restoreProfile("next-owner", new SavedState(), 20L);
            engine.startCustom("Zulrah", 21L);
            panel.resume();
            assertTrue(visible(panel.live.hero, panel));
            panel.shell.show(Shell.GRINDS);
            assertFalse("Grinds waits for its new snapshot", visible(panel.plans.body, panel));
            SidebarPanelProbe.refresh(panel);
            assertTrue(visible(panel.plans.running, panel));
            assertTrue(panel.plans.caption.getText().contains("Zulrah"));
            assertFalse(panel.plans.caption.getText().contains("Old owner"));
            return null;
        });
    }

    static LiveSnapshot target(boolean time, boolean reached) {
        Engine engine = engine();
        engine.setTargets(time ? null : 1_000L, time ? 1_000L : null, 10L);
        if (!time && reached) coins(engine, 11L, 1_000L);
        return live(engine, time && reached ? 1_020L : 20L);
    }

    static boolean visible(Component child, Component root) {
        for (Component component = child; component != null; component = component.getParent()) {
            if (!component.isVisible()) return false;
            if (component == root) return true;
        }
        return false;
    }
}
