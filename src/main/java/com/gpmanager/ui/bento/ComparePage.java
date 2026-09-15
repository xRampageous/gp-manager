package com.gpmanager.ui.bento;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.annotation.Nullable;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * Runs › Compare (SIDEBAR_BENTO.md §5): two statements side by side with deltas, the
 * same-activity average, and a pop-out to a resizable window.
 */
public final class ComparePage implements BentoShell.Page
{
    public static final String ID = "compare";

    public interface Actions
    {
        void back();

        void popOut(SessionsSnapshot.Statement left, SessionsSnapshot.Statement right, Average average);
    }

    /** The "your average" line for the left statement's activity. */
    public static final class Average
    {
        public final String activity;
        public final int sessions;
        public final long gpPerHour;
        @Nullable
        public final Long medianNet;
        /** True when every counted session had complete accounting coverage. */
        public final boolean complete;

        public Average(String activity, int sessions, long gpPerHour)
        {
            this(activity, sessions, gpPerHour, null, false);
        }

        public Average(String activity, int sessions, long gpPerHour, @Nullable Long medianNet, boolean complete)
        {
            this.activity = activity;
            this.sessions = sessions;
            this.gpPerHour = gpPerHour;
            this.medianNet = medianNet;
            this.complete = complete;
        }
    }

    private final Actions actions;
    private final JPanel bar = new JPanel();
    private final JPanel body = BentoShell.stack();
    @Nullable
    private SessionsSnapshot.Statement left;
    @Nullable
    private SessionsSnapshot.Statement right;
    @Nullable
    private Average average;
    /** The engine's per-item differences (pass 10 step 41); empty when either side's split is unavailable. */
    private java.util.List<com.gpmanager.model.SessionComparison.ItemDelta> itemDeltas = java.util.Collections.emptyList();

    public ComparePage(Actions actions)
    {
        this.actions = actions;
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setOpaque(false);
        Controls.Button back = new Controls.Button("‹", Controls.Button.Kind.GHOST);
        back.onClick(actions::back);
        bar.add(back);
        bar.add(Tile.label("Compare", BentoTheme.font(java.awt.Font.BOLD, 13f), BentoTheme.TEXT));
        bar.add(Box.createHorizontalGlue());
        Controls.IconButton pop = new Controls.IconButton("↗", "Open in a window", true);
        pop.onClick(() ->
        {
            if (left != null && right != null)
            {
                actions.popOut(left, right, average);
            }
        });
        bar.add(pop);
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, bar.getPreferredSize().height));
    }

    @Override
    public String id()
    {
        return ID;
    }

    @Override
    public JComponent pageBar()
    {
        return bar;
    }

    @Override
    public JComponent body()
    {
        return body;
    }

    public void apply(SessionsSnapshot.Statement l, SessionsSnapshot.Statement r, @Nullable Average avg)
    {
        apply(l, r, avg, java.util.Collections.emptyList());
    }

    public void apply(SessionsSnapshot.Statement l, SessionsSnapshot.Statement r, @Nullable Average avg,
        java.util.List<com.gpmanager.model.SessionComparison.ItemDelta> deltas)
    {
        left = l;
        right = r;
        average = avg;
        itemDeltas = deltas == null ? java.util.Collections.emptyList() : deltas;
        body.removeAll();
        BentoShell.stackAdd(body, table(l, r, false));
        if (!itemDeltas.isEmpty())
        {
            // What moved most between the two, item by item: right minus left, like the table.
            Tile items = new Tile().section(null, "Biggest item differences", null, null);
            boolean first = true;
            for (com.gpmanager.model.SessionComparison.ItemDelta d : itemDeltas)
            {
                if (!first)
                {
                    items.gap(4);
                }
                first = false;
                items.add(new NavRow().chevron(false)
                    .text(d.getItemName(), Fmt.signed(d.getLeftNet()) + " → " + Fmt.signed(d.getRightNet()))
                    .right(Fmt.signed(d.getDelta()), BentoTheme.signColor(d.getDelta())));
            }
            BentoShell.stackAdd(body, items);
        }
        if (avg != null && avg.sessions > 0)
        {
            Tile same = new Tile().header("Same activity · " + avg.activity, null, null);
            same.kv("Your average (" + avg.sessions + (avg.sessions == 1 ? " session)" : " sessions)"), Fmt.rate(avg.gpPerHour) + "/h");
            if (avg.medianNet != null)
            {
                same.kv("Median session", Fmt.signed(avg.medianNet), BentoTheme.signColor(avg.medianNet));
            }
            if (!avg.complete)
            {
                same.text("some sessions have partial coverage", BentoTheme.DIM);
            }
            if (r.rateAvailable && avg.gpPerHour != 0L)
            {
                double d = (r.gpPerHour - avg.gpPerHour) / (double) Math.abs(avg.gpPerHour);
                same.kv(r.title + " vs average", pct(d), d >= 0 ? BentoTheme.POSITIVE : BentoTheme.NEGATIVE);
            }
            BentoShell.stackAdd(body, same);
        }
        Tile note = new Tile().header("Reading this", null, null);
        note.paragraph("Deltas are right minus left. GP/h uses active time only. Supplies are consumables; "
            + "Loss is everything else. Left on ground is information, not part of net.",
            BentoTheme.secondary(), BentoTheme.DIM, Tile.interiorWidth());
        BentoShell.stackAdd(body, note);
        body.revalidate();
        body.repaint();
    }

    /** Shared by the page and the pop-out window. */
    static JComponent table(SessionsSnapshot.Statement l, SessionsSnapshot.Statement r, boolean wide)
    {
        Tile tile = new Tile();
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        java.awt.Font head = BentoTheme.micro();
        java.awt.Font cell = wide ? BentoTheme.body() : BentoTheme.secondary();
        int row = 0;
        addCell(grid, row, 0, "", head, BentoTheme.DIM, GridBagConstraints.WEST);
        addCell(grid, row, 1, StatBlock.fit(l.title, tileMetrics(head), wide ? 220 : 70), head, BentoTheme.TEXT, GridBagConstraints.EAST);
        addCell(grid, row, 2, StatBlock.fit(r.title, tileMetrics(head), wide ? 220 : 70), head, BentoTheme.TEXT, GridBagConstraints.EAST);
        row++;
        if (!l.when.isEmpty() || !r.when.isEmpty())
        {
            addCell(grid, row, 0, "", head, BentoTheme.DIM, GridBagConstraints.WEST);
            addCell(grid, row, 1, StatBlock.fit(l.when, tileMetrics(head), wide ? 220 : 70), head, BentoTheme.DIM, GridBagConstraints.EAST);
            addCell(grid, row, 2, StatBlock.fit(r.when, tileMetrics(head), wide ? 220 : 70), head, BentoTheme.DIM, GridBagConstraints.EAST);
            row++;
        }
        row = metric(grid, row, "Net", l.net, r.net, true, cell);
        if (l.rateAvailable || r.rateAvailable)
        {
            row = metric(grid, row, "GP/h", l.rateAvailable ? l.gpPerHour : null, r.rateAvailable ? r.gpPerHour : null, true, cell);
        }
        row = metric(grid, row, "Gains", l.loot, r.loot, false, cell);
        row = metric(grid, row, "Supplies", l.supplies, r.supplies, false, cell);
        row = metric(grid, row, "Loss", l.loss, r.loss, false, cell);
        if (l.kills > 0 || r.kills > 0)
        {
            row = plain(grid, row, "Kills", Integer.toString(l.kills), Integer.toString(r.kills), cell);
        }
        if (l.deaths > 0 || r.deaths > 0)
        {
            row = plain(grid, row, "Deaths", Integer.toString(l.deaths), Integer.toString(r.deaths), cell);
        }
        row = plain(grid, row, "Duration", Fmt.duration(l.durationMillis), Fmt.duration(r.durationMillis), cell);
        if (l.leftOnGround != null || r.leftOnGround != null)
        {
            plain(grid, row, "Left on ground", l.leftOnGround == null ? "—" : Fmt.compact(l.leftOnGround),
                r.leftOnGround == null ? "—" : Fmt.compact(r.leftOnGround), cell);
        }
        grid.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        tile.add(grid);
        tile.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        return tile;
    }

    private static java.awt.FontMetrics tileMetrics(java.awt.Font font)
    {
        return new JLabel().getFontMetrics(font);
    }

    private static int metric(JPanel grid, int row, String label, @Nullable Long l, @Nullable Long r, boolean signed, java.awt.Font cell)
    {
        addCell(grid, row, 0, label, cell, BentoTheme.MUTED, GridBagConstraints.WEST);
        addCell(grid, row, 1, l == null ? "—" : signed ? Fmt.signed(l) : Fmt.compact(l), cell,
            l == null ? BentoTheme.DIM : signed ? BentoTheme.signColor(l) : BentoTheme.TEXT, GridBagConstraints.EAST);
        String rightText = r == null ? "—" : signed ? Fmt.signed(r) : Fmt.compact(r);
        Color rightColor = r == null ? BentoTheme.DIM : signed ? BentoTheme.signColor(r) : BentoTheme.TEXT;
        if (l != null && r != null && l != 0L)
        {
            double d = (r - l) / (double) Math.abs(l);
            rightText += "  " + pct(d);
        }
        addCell(grid, row, 2, rightText, cell, rightColor, GridBagConstraints.EAST);
        return row + 1;
    }

    private static int plain(JPanel grid, int row, String label, String l, String r, java.awt.Font cell)
    {
        addCell(grid, row, 0, label, cell, BentoTheme.MUTED, GridBagConstraints.WEST);
        addCell(grid, row, 1, l, cell, BentoTheme.TEXT, GridBagConstraints.EAST);
        addCell(grid, row, 2, r, cell, BentoTheme.TEXT, GridBagConstraints.EAST);
        return row + 1;
    }

    private static void addCell(JPanel grid, int row, int col, String text, java.awt.Font font, Color color, int anchor)
    {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = col;
        c.gridy = row;
        c.weightx = col == 0 ? 1 : 0;
        c.anchor = anchor;
        c.insets = new Insets(1, col == 0 ? 0 : 8, 1, 0);
        c.fill = GridBagConstraints.HORIZONTAL;
        JLabel label = Tile.label(text, font, color);
        label.setHorizontalAlignment(anchor == GridBagConstraints.EAST ? JLabel.RIGHT : JLabel.LEFT);
        grid.add(label, c);
    }

    static String pct(double d)
    {
        long p = Math.round(d * 100d);
        return (p >= 0 ? "+" : "−") + Math.abs(p) + "%";
    }
}
