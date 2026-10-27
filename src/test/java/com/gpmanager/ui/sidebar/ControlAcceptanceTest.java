package com.gpmanager;

import com.google.gson.Gson;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static com.gpmanager.DirectHeaderControlsTest.*;
import static com.gpmanager.MilestoneTest.onEdt;
import static org.junit.Assert.*;

/** Owner-approved labels/feedback, with offline keyboard and scaled narrow-layout checks. */
public class ControlAcceptanceTest {
    LongSupplier clock;
    @Before public void setUp() { clock = SafeMath.clock; }
    @After public void tearDown() { SafeMath.clock = clock; }

    @Test public void ledgerTabOpensTheWholeRunWithoutChangingTheLiveWindow() throws Exception {
        onEdt(() -> {
            SidebarPanel panel = SavedSessionTest.panel(SavedSessionTest.window());
            String before = new Gson().toJson(panel.engine.exportState());
            assertNull("Recent has no duplicate Ledger shortcut", find(panel.live.recent, "Ledger"));
            panel.shell.show(Shell.LEDGER);
            SidebarPanelProbe.refresh(panel);
            assertEquals(Shell.LEDGER, panel.shell.currentPage());
            assertEquals(LedgerData.Entry.current().scope, panel.scope.scope);
            assertEquals("", panel.ledgerSearch);
            assertEquals(1_500L, panel.ledger.data.net);
            assertEquals("opening the breakdown preserves the fresh Live window", before,
                    new Gson().toJson(panel.engine.exportState()));
            return null;
        });
    }

    @Test public void hiddenItemsSuppliesAndSpellsKeepTheirBookingsAndAcknowledgeTheAction() throws Exception {
        onEdt(() -> {
            SidebarPanel panel = SavedSessionTest.panel(SavedSessionTest.window());
            AtomicInteger hidden = new AtomicInteger();
            panel.bindFilter(new RecentFilter() {
                public boolean isFlowIncluded(Flow flow) { return true; }
                public void hideRecent(String name, boolean supply) {
                    assertEquals(name.equals("Prayer potion"), supply);
                    hidden.incrementAndGet();
                }
            });
            String before = new Gson().toJson(panel.engine.exportState());
            for (String name : new String[]{"Rune kiteshield", "Prayer potion", "Ice Burst"}) {
                panel.new LiveActions().hideRecent(name, name.equals("Prayer potion"));
                assertEquals("Hidden from Recent · " + name + " · Booked totals unchanged.", ShellProbe.noticeText(panel.shell));
                assertEquals(before, new Gson().toJson(panel.engine.exportState()));
            }
            assertEquals(3, hidden.get());
            return null;
        });
    }

    @Test public void missingFilterDoesNotClaimThatAnItemWasHidden() throws Exception {
        onEdt(() -> {
            SidebarPanel panel = SavedSessionTest.panel(SavedSessionTest.window());
            panel.new LiveActions().hideRecent("Rune kiteshield", false);
            assertEquals("", ShellProbe.noticeText(panel.shell));
            return null;
        });
    }

    @Test public void hidingCannotDismissAnUnresolvedSaveFailure() throws Exception {
        onEdt(() -> {
            SidebarPanel panel = SavedSessionTest.panel(SavedSessionTest.window());
            AtomicInteger hidden = new AtomicInteger();
            panel.bindFilter(new RecentFilter() {
                public boolean isFlowIncluded(Flow flow) { return true; }
                public void hideRecent(String name, boolean supply) { hidden.incrementAndGet(); }
            });
            panel.shell.notifySticky("Save failed", "Retry when storage is available", null, null);
            panel.new LiveActions().hideRecent("Rune kiteshield", false);
            assertEquals(1, hidden.get());
            assertEquals("Save failed · Retry when storage is available", ShellProbe.noticeText(panel.shell));
            return null;
        });
    }

    @Test public void iconActionsHaveReadableAccessibleNames() throws Exception {
        onEdt(() -> {
            SidebarPanel panel = DirectHeaderControlsTest.panel();
            AbstractButton more = find(panel.ledger.bar, "···");
            assertEquals("Ledger actions", more.getAccessibleContext().getAccessibleName());
            AbstractButton gear = byTooltip(panel.shell, GameData.msg("export-backups"));
            assertNotNull(gear);
            assertEquals("GP Manager actions", gear.getAccessibleContext().getAccessibleName());
            assertEquals("GP Manager actions", gear.getToolTipText());
            return null;
        });
    }

    @Test public void ledgerTabKeepsFocusedKeyboardBindings() throws Exception {
        onEdt(() -> {
            SidebarPanel panel = SavedSessionTest.panel(SavedSessionTest.window());
            JComponent tab = panel.shell.buttons.get(Shell.LEDGER);
            assertTrue("Tab may focus Ledger", tab.isFocusable());
            assertTrue(tab.getFocusTraversalKeysEnabled());
            for (String stroke : new String[]{"SPACE", "ENTER"}) {
                Object key = tab.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(stroke));
                assertNotNull("focused tab binding: " + stroke, tab.getActionMap().get(key));
            }
            Path audit = Path.of("build", "control-acceptance", "keyboard-audit.txt");
            Files.createDirectories(audit.getParent());
            Files.writeString(audit, "OFFLINE_SWING_MODEL_CHECK; no OS/game input\n"
                    + "Ledger tab focusable=" + tab.isFocusable() + ", focused Space/Enter bindings=present\n"
                    + "Hero Net focused Enter binding=" + panel.live.hero.net.getInputMap().get(KeyStroke.getKeyStroke("ENTER")) + "\n"
                    + "Hero Net focused Space binding=" + panel.live.hero.net.getInputMap().get(KeyStroke.getKeyStroke("SPACE")) + "\n"
                    + "Native focus traversal, OS DPI and live RuneLite remain owner checks.\n");
            return null;
        });
    }

    @Test public void recentTotalStaysReadableAtNarrowWidthsAndScaledRendering() throws Exception {
        onEdt(() -> {
            SidebarPanel panel = SavedSessionTest.panel(SavedSessionTest.window());
            panel.live.recent.setTitle("RECENT · 15").setTotal(Fmt.signed(-2_147_483_647L), Kit.Tone.LOSS);
            for (int width : new int[]{213, 225, 300}) {
                size(panel.live.recent, width - 2 * Kit.GAP);
                assertNull(find(panel.live.recent, "Ledger"));
                Rectangle box = bounds(panel.live.recent.total, panel.live.recent);
                assertTrue("total stays within Recent at " + width, box.x >= 0 && box.getMaxX() <= panel.live.recent.getWidth());
                assertTrue("total fits at " + width, panel.live.recent.total.getFontMetrics(panel.live.recent.total.getFont())
                        .stringWidth(panel.live.recent.total.getText()) <= panel.live.recent.total.getWidth());
                assertTrue("Recent count fits beside a large total at " + width,
                        panel.live.recent.title.getFontMetrics(panel.live.recent.title.getFont())
                                .stringWidth(panel.live.recent.title.getText()) <= panel.live.recent.title.getWidth());
                for (double scale : new double[]{1d, 1.25d, 1.5d}) {
                    JComponent recent = panel.live.recent;
                    BufferedImage image = new BufferedImage((int) Math.ceil(recent.getWidth() * scale),
                            (int) Math.ceil(recent.getHeight() * scale), BufferedImage.TYPE_INT_ARGB);
                    Graphics2D graphics = image.createGraphics();
                    try { graphics.scale(scale, scale); recent.printAll(graphics); }
                    finally { graphics.dispose(); }
                    Path out = Path.of("build", "control-acceptance", "recent-" + width + "-" + (int) (scale * 100) + ".png");
                    Files.createDirectories(out.getParent());
                    assertTrue(ImageIO.write(image, "png", out.toFile()));
                    assertTrue(Files.size(out) > 0L);
                }
            }
            return null;
        });
    }

    @Test public void plainRecentKeepsTheTotalWithoutHeaderButtonsOrPaging() throws Exception {
        onEdt(() -> {
            SidebarPanel panel = SavedSessionTest.panel(SavedSessionTest.window());
            try {
                Table recent = panel.live.recent;
                recent.setTitle("RECENT · 15").setTotal(Fmt.signed(-2_147_483_647L), Kit.Tone.LOSS);
                for (int width : new int[]{213, 225, 300}) {
                    size(recent, width - 2 * Kit.GAP);
                    assertNull(find(recent, "Ledger"));
                    assertNull(recent.action);
                    assertNull(recent.previous.getParent());
                    assertNull(recent.next.getParent());
                    assertEquals(1, recent.right.getComponentCount());
                    assertSame(recent.total, recent.right.getComponent(0));
                    Rectangle amount = bounds(recent.total, recent);
                    assertTrue("total fits the header", amount.x >= 0 && amount.getMaxX() <= recent.getWidth());
                }
            } finally { panel.shell.timer.stop(); }
            return null;
        });
    }

    static AbstractButton byTooltip(Component root, String text) {
        if (root instanceof AbstractButton && text.equals(((AbstractButton) root).getToolTipText()))
            return (AbstractButton) root;
        if (root instanceof Container) for (Component child : ((Container) root).getComponents()) {
            AbstractButton found = byTooltip(child, text);
            if (found != null) return found;
        }
        return null;
    }
}
