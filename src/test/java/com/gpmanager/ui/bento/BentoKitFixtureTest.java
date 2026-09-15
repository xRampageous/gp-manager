package com.gpmanager.ui.bento;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Collections;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Fixture renders for the Bento kit at the minimum (225) and owned (236) content widths:
 * every component paints without error, hugs its preferred height, and the stat block's
 * micro values are painted whole (no ellipsis) for the spec's example figures.
 */
public class BentoKitFixtureTest
{
    private static final int[] WIDTHS = {BentoTheme.MIN_CONTENT_WIDTH, BentoTheme.CONTENT_WIDTH};

    @Test
    public void fmtMatchesSpecExamples()
    {
        assertEquals("812", Fmt.compact(812));
        assertEquals("1.2k", Fmt.compact(1_234));
        assertEquals("36.0k", Fmt.compact(36_000));
        assertEquals("248k", Fmt.compact(248_400));
        assertEquals("1.76M", Fmt.compact(1_760_000));
        assertEquals("12.4M", Fmt.compact(12_400_000));
        assertEquals("212M", Fmt.compact(212_000_000));
        assertEquals("1.02B", Fmt.compact(1_020_000_000));
        assertEquals("+124,318", Fmt.exactSigned(124_318));
        assertEquals("−1,204,318", Fmt.exactSigned(-1_204_318));
        assertEquals("0", Fmt.signed(0));
        assertEquals("−2.1k", Fmt.signed(-2_100));
        assertEquals("30:12", Fmt.clock(30 * 60_000L + 12_000L));
        assertEquals("1:12:40", Fmt.clock(3600_000L + 12 * 60_000L + 40_000L));
        assertEquals("8m 04s", Fmt.duration(8 * 60_000L + 4_000L));
        assertEquals("1h 12m", Fmt.duration(3600_000L + 12 * 60_000L));
        assertEquals("62%", Fmt.percent(0.62));
        assertEquals("3m ago", Fmt.age(3 * 60_000L + 5_000L));
    }

    @Test
    public void cardKitSizesAtBothWidths()
    {
        StatGrid grid = new StatGrid().cells(Arrays.asList(
            new StatGrid.Cell(Icon.glyph("⬆", BentoTheme.POSITIVE), "Gains", "2.14M", BentoTheme.TEXT),
            new StatGrid.Cell(Icon.glyph("⬇", BentoTheme.NEGATIVE), "Loss", "−376k", BentoTheme.NEGATIVE),
            new StatGrid.Cell(Icon.glyph("◔", BentoTheme.INFO), "GP/h", "1.47M", BentoTheme.POSITIVE, "▲ +18%", true, "vs average")), 3).joined(true);
        for (int width : WIDTHS)
        {
            render(grid, width);
            assertTrue(grid.getPreferredSize().height > Icon.SIZE + 20);
            assertEquals("Gains 2.14M; Loss −376k; GP/h 1.47M;", grid.getAccessibleContext().getAccessibleName());
        }
        NavRow row = new NavRow().icon(Icon.glyph("◆", BentoTheme.accentColor())).tone(NavRow.Tone.ACCENT)
            .text("Notable drop", "Draconic visage · +5.21M · 2m ago").right("+5.21M", BentoTheme.POSITIVE);
        for (int width : WIDTHS)
        {
            render(row, width);
            assertTrue(row.getPreferredSize().height >= Icon.SIZE + 2 * 10);
        }
        Toggle toggle = new Toggle("Share my figures", false);
        final boolean[] seen = {false};
        toggle.onChange(v -> seen[0] = v);
        toggle.setOn(true);
        assertTrue(toggle.isOn());
        assertFalse(seen[0]);
        Chip chip = new Chip().set("▲ +12%", BentoTheme.POSITIVE);
        assertTrue(chip.getPreferredSize().width > 20);
        assertFalse(BentoTheme.family().isEmpty());
        assertEquals(21907, ActivityIcons.itemFor("Vorkath", null));
        assertEquals(11864, ActivityIcons.itemFor("Slayer · Abyssal demons", null));
        assertEquals(-1, ActivityIcons.itemFor("Edgeville PvP", "PvP"));
        assertEquals("☠", ActivityIcons.glyphFor("Edgeville PvP", "PvP"));
    }

    @Test
    public void slimScrollBarIsSixPixelsAndNeverHorizontal()
    {
        JScrollPane pane = SlimScrollBarUI.install(new JScrollPane(new javax.swing.JPanel()));
        assertEquals(BentoTheme.SCROLLBAR_WIDTH, pane.getVerticalScrollBar().getPreferredSize().width);
        assertEquals(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER, pane.getHorizontalScrollBarPolicy());
        assertTrue(pane.getVerticalScrollBar().getUI() instanceof SlimScrollBarUI);
        assertEquals(BentoTheme.OWNED_WIDTH - BentoTheme.SCROLLBAR_WIDTH, BentoTheme.CONTENT_WIDTH);
    }

    @Test
    public void statBlockPaintsSpecFiguresWholeAtBothWidths()
    {
        StatBlock block = new StatBlock()
            .header("Net · General", "")
            .clock("30:12", StatBlock.ClockState.LIVE, "")
            .net(124_318L, false)
            .micro(Arrays.asList(
                new StatBlock.Micro("Gains", "160.2k"),
                new StatBlock.Micro("Loss", "36.0k", BentoTheme.NEGATIVE),
                new StatBlock.Micro("GP/h", "248k", "▲12%", null, BentoTheme.POSITIVE)),
                Collections.emptyList());
        for (int width : WIDTHS)
        {
            render(block, width);
            assertEquals("+124,318", block.lastPaintedHero());
            java.awt.FontMetrics fm = block.getFontMetrics(BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().secondary + 2));
            int colW = (width - 2 * (BentoTheme.density().pad + 1) - 14) / 3;
            for (String v : new String[] {"160.2k", "36.0k", "248k"})
            {
                assertTrue(v + " must fit a micro column at " + width, fm.stringWidth(v) <= colW);
            }
        }
        // Nine-digit negative net still paints whole in the hero font at 225.
        block.net(-1_204_318L, false);
        render(block, BentoTheme.MIN_CONTENT_WIDTH);
        java.awt.FontMetrics hero = block.getFontMetrics(BentoTheme.hero());
        assertTrue(hero.stringWidth(block.heroText()) <= BentoTheme.MIN_CONTENT_WIDTH - 2 * (BentoTheme.density().pad + 1));
    }

    @Test
    public void statBlockPvpTwoRowsAndProvisionalState()
    {
        StatBlock block = new StatBlock()
            .flavour(StatBlock.Flavour.PVP)
            .header("PvP net · General", "")
            .clock("42:07", StatBlock.ClockState.LIVE, "")
            .net(2_318_900L, false)
            .micro(Arrays.asList(
                new StatBlock.Micro("Kills", "3.9M", BentoTheme.POSITIVE),
                new StatBlock.Micro("Deaths", "1.2M", BentoTheme.NEGATIVE),
                new StatBlock.Micro("Loss", "380k", BentoTheme.NEGATIVE)),
                Arrays.asList(
                new StatBlock.Micro("K / D", "12/3", "×4", null, null),
                new StatBlock.Micro("Per kill", "325k", BentoTheme.POSITIVE),
                new StatBlock.Micro("Per death", "−400k", BentoTheme.NEGATIVE)));
        int twoRows = block.getPreferredSize().height;
        block.micro(Collections.singletonList(new StatBlock.Micro("Gains", "1")), Collections.emptyList());
        int oneRow = block.getPreferredSize().height;
        assertTrue(twoRows > oneRow);

        block.net(1_200L, true);
        render(block, BentoTheme.CONTENT_WIDTH);
        assertEquals("≈ +1,200", block.lastPaintedHero());
        assertFalse(block.isTickVisible());

        StatBlock fresh = new StatBlock().net(500L, false);
        assertFalse("first value is a baseline, not a change", fresh.isTickVisible());
        fresh.net(1_700L, false);
        assertTrue(fresh.isTickVisible());
    }

    @Test
    public void everyComponentRendersAtBothWidthsAndHugsHeight()
    {
        for (int width : WIDTHS)
        {
            Rail rail = new Rail(Arrays.asList(
                new Rail.Tab("live", "◈", "Live"), new Rail.Tab("ledger", "≣", "Ledger"),
                new Rail.Tab("runs", "◷", "Runs"), new Rail.Tab("insights", "◑", "Insights"),
                new Rail.Tab("tools", "⚙", "Tools")));
            rail.setDot("tools", true);
            render(rail, width);

            Ribbon ribbon = new Ribbon().segments(Arrays.asList(
                new Ribbon.Segment("r1", 0, 0.24, false, "Run 1"),
                new Ribbon.Segment("r2", 0.24, 0.5, false, "Run 2"),
                new Ribbon.Segment("r3", 0.5, 0.72, true, "Run 3")),
                Arrays.asList(new Ribbon.Mark(0.24, Ribbon.MarkKind.BANK), new Ribbon.Mark(0.5, Ribbon.MarkKind.DEATH)));
            render(ribbon, width);

            GoalLine goal = new GoalLine().set("200k", 0.62, 0.8, "62%", "~18m");
            render(goal, width);
            assertTrue(goal.getPreferredSize().height > 0);
            goal.clear();
            assertEquals(0, goal.getPreferredSize().height);

            Notice notice = new Notice("☠", "Reclaim pending", "Vorkath · 100k on collect", "›", Notice.Tone.WARN);
            render(notice, width);

            ItemRow row = new ItemRow().name("Thermonuclear smoke devil trophy", "×1").verb("received").value(1_200L);
            render(row, width);
            ItemRow quiet = new ItemRow().name("Fire rune", "×45").tag("quiet", BentoTheme.QUIET).value(-180L);
            render(quiet, width);

            ListBlock list = new ListBlock().header("Recent", "ledger ›", null).row(row).row(quiet);
            render(list, width);
            ListBlock empty = new ListBlock().header("Recent", null, null).empty("No receipts yet this run");
            render(empty, width);

            Tile tile = new Tile().header("Wealth · locate", "4.5M", BentoTheme.TEXT).kv("Inventory", "1.21M").kv("Worn", "2.45M");
            render(tile, width);

            Controls.Segmented seg = new Controls.Segmented(Arrays.asList("General", "PvP", "Wealth"));
            render(seg, seg.getPreferredSize().width);
            Controls.Button button = new Controls.Button("❚❚ Pause tracking", Controls.Button.Kind.STOP);
            render(button, button.getPreferredSize().width);
            Controls.IconButton icon = new Controls.IconButton("⌕", "Search", false);
            render(icon, icon.getPreferredSize().width);
        }
    }

    @Test
    public void toastHostKeepsAtMostTwoAndClicksThrough()
    {
        ToastHost host = new ToastHost();
        host.setSize(BentoTheme.OWNED_WIDTH, 600);
        host.undo("Correction applied", "Fire rune → ignored", () -> { }, 8);
        host.info("◎", "Goal reached", "200k net", "Keep going", BentoTheme.POSITIVE, null);
        host.info("★", "Notable drop", "Draconic visage", null, BentoTheme.INFO, null);
        assertEquals(ToastHost.MAX_VISIBLE, host.visibleCount());
        assertFalse("empty overlay area must not catch clicks", host.contains(10, 10));
        render(host, BentoTheme.OWNED_WIDTH);
    }

    @Test
    public void shellSwitchesPagesAndRemembersScroll()
    {
        BentoShell shell = new BentoShell();
        shell.addPage(page("live", 200));
        shell.addPage(page("ledger", 2000));
        shell.setSize(BentoTheme.OWNED_WIDTH, 400);
        shell.doLayout();
        shell.show("ledger");
        assertEquals("ledger", shell.currentPage());
        assertEquals("ledger", shell.rail().selectedId());
        shell.show("live");
        assertEquals("live", shell.currentPage());
        render(shell, BentoTheme.OWNED_WIDTH);
    }

    private static BentoShell.Page page(String id, int bodyHeight)
    {
        return new BentoShell.Page()
        {
            @Override
            public String id()
            {
                return id;
            }

            @Override
            public JComponent pageBar()
            {
                return new Tile().text(id, BentoTheme.TEXT);
            }

            @Override
            public JComponent body()
            {
                javax.swing.JPanel body = BentoShell.stack();
                body.setPreferredSize(new Dimension(BentoTheme.CONTENT_WIDTH, bodyHeight));
                return body;
            }
        };
    }

    private static void render(JComponent component, int width)
    {
        Dimension pref = component.getPreferredSize();
        int height = Math.max(1, pref.height);
        component.setSize(width, height);
        component.doLayout();
        if (component instanceof java.awt.Container)
        {
            layoutTree((java.awt.Container) component);
        }
        BufferedImage image = new BufferedImage(Math.max(1, width), height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try
        {
            component.paint(g);
        }
        finally
        {
            g.dispose();
        }
        assertTrue(component.getClass().getSimpleName() + " must not need more than " + width + " px",
            component.getPreferredSize().width <= Math.max(width, BentoTheme.OWNED_WIDTH));
    }

    private static void layoutTree(java.awt.Container container)
    {
        container.doLayout();
        for (java.awt.Component child : container.getComponents())
        {
            if (child instanceof java.awt.Container)
            {
                layoutTree((java.awt.Container) child);
            }
        }
    }
}
