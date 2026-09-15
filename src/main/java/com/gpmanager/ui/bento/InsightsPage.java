package com.gpmanager.ui.bento;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import javax.annotation.Nullable;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * Insights (SIDEBAR_BENTO.md §6): page bar {@code Insights · [7d | 30d | All]}, context row
 * {@code General | PvP | Wealth} with the "with party" filter and CSV export, then the mode's
 * tiles. Every figure is read from {@link InsightsSnapshot}; the page never touches the engine.
 */
public final class InsightsPage implements BentoShell.Page
{
    public interface Actions
    {
        void rangeChanged(InsightsSnapshot.Range range);

        void modeChanged(Mode mode);

        void partyFilterChanged(boolean partyOnly);

        void openItem(int itemId, String name);

        void openSession(String sessionId);

        void exportCsv(InsightsSnapshot snapshot, Mode mode);

        /** A coin store the owner never has counts as zero instead of holding Other unavailable. */
        void markCoinStoreUnused(com.gpmanager.model.CoinStore store, boolean unused);
    }

    public enum Mode
    {
        GENERAL("General"), PVP("PvP"), WEALTH("Wealth");

        public final String label;

        Mode(String label)
        {
            this.label = label;
        }
    }

    private final Actions actions;
    private final Function<Integer, BufferedImage> sprites;
    private final JPanel bar = new JPanel();
    private final JPanel body = BentoShell.stack();
    private final JPanel content = new JPanel();
    private final Controls.Segmented rangeSwitch = new Controls.Segmented(Arrays.asList(
        InsightsSnapshot.Range.ALL.label, InsightsSnapshot.Range.D30.label, InsightsSnapshot.Range.D7.label));
    private final Controls.Segmented modeSwitch = new Controls.Segmented(Arrays.asList(
        Mode.GENERAL.label, Mode.PVP.label, Mode.WEALTH.label));
    private final Controls.IconButton party = new Controls.IconButton("⚑", "Only sessions with a party", true);
    private final Controls.IconButton export = new Controls.IconButton("⇪", "Copy this view as CSV", true);
    private InsightsSnapshot.Range range = InsightsSnapshot.Range.ALL;
    private Mode mode = Mode.GENERAL;
    private boolean partyOnly;
    private boolean topItemsFolded;
    @Nullable
    private InsightsSnapshot last;

    public InsightsPage(Actions actions, Function<Integer, BufferedImage> sprites)
    {
        this.actions = actions;
        this.sprites = sprites == null ? id -> null : sprites;

        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setOpaque(false);
        bar.add(Tile.label("Insights", BentoTheme.font(java.awt.Font.BOLD, 13f), BentoTheme.TEXT));
        bar.add(Box.createHorizontalGlue());
        party.onClick(() ->
        {
            partyOnly = !partyOnly;
            party.setOn(partyOnly);
            actions.partyFilterChanged(partyOnly);
        });
        bar.add(party);
        bar.add(Box.createHorizontalStrut(4));
        export.onClick(() ->
        {
            if (last != null)
            {
                actions.exportCsv(last, mode);
            }
        });
        bar.add(export);
        bar.add(Box.createHorizontalStrut(8));
        rangeSwitch.select(0, false);
        rangeSwitch.onSelect(i ->
        {
            range = InsightsSnapshot.Range.values()[i];
            actions.rangeChanged(range);
        });
        bar.add(rangeSwitch);
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, bar.getPreferredSize().height));

        JPanel context = new JPanel();
        context.setLayout(new BoxLayout(context, BoxLayout.X_AXIS));
        context.setOpaque(false);
        modeSwitch.onSelect(i ->
        {
            mode = Mode.values()[i];
            modeSwitch.selectedFill(mode == Mode.PVP ? BentoTheme.PVP_SURFACE : mode == Mode.WEALTH ? BentoTheme.WEALTH_SURFACE : null);
            actions.modeChanged(mode);
            render();
        });
        modeSwitch.fill(true);
        modeSwitch.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        context.add(modeSwitch);
        context.setMaximumSize(new Dimension(Integer.MAX_VALUE, context.getPreferredSize().height));
        BentoShell.stackAdd(body, context);

        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        content.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        BentoShell.stackAdd(body, content);
    }

    @Override
    public String id()
    {
        return BentoShell.INSIGHTS;
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

    public InsightsSnapshot.Range range()
    {
        return range;
    }

    public Mode mode()
    {
        return mode;
    }

    public boolean partyOnly()
    {
        return partyOnly;
    }

    /** Test/preview seam. */
    public void showMode(Mode value)
    {
        modeSwitch.select(value.ordinal(), true);
    }

    /** Test/preview seam: picks the range and asks for the matching snapshot. */
    public void showRange(InsightsSnapshot.Range value)
    {
        rangeSwitch.select(value.ordinal(), true);
    }

    public void apply(InsightsSnapshot s)
    {
        last = s;
        render();
    }

    private void render()
    {
        content.removeAll();
        InsightsSnapshot s = last;
        if (s != null)
        {
            switch (mode)
            {
                case PVP:
                    renderPvp(s);
                    break;
                case WEALTH:
                    renderWealth(s);
                    break;
                default:
                    renderGeneral(s);
                    break;
            }
        }
        content.revalidate();
        content.repaint();
    }

    // ── General ────────────────────────────────────────────────────────────────────────

    private static String period(InsightsSnapshot s)
    {
        return s.range == InsightsSnapshot.Range.D7 ? "Last 7 days" : s.range == InsightsSnapshot.Range.D30 ? "Last 30 days" : "All time";
    }

    private static String previousLabel(InsightsSnapshot s)
    {
        return s.range == InsightsSnapshot.Range.D7 ? "previous 7 days" : "previous 30 days";
    }

    private static String pct(double d)
    {
        return (d >= 0 ? "▲ +" : "▼ ") + Math.abs(Math.round(d * 100)) + "%";
    }

    /** "▲ +18%" against a previous figure, null when there is nothing to compare. */
    @Nullable
    private static String delta(long now, @Nullable Long before)
    {
        if (before == null || before == 0L)
        {
            return null;
        }
        return pct((now - before) / (double) Math.abs(before));
    }

    private static double[] cumulative(List<InsightsSnapshot.SessionPoint> trend)
    {
        List<Double> out = new ArrayList<>();
        double running = 0d;
        for (InsightsSnapshot.SessionPoint p : trend)
        {
            if (p.excluded)
            {
                continue;
            }
            running += p.net;
            out.add(running);
        }
        double[] arr = new double[out.size()];
        for (int i = 0; i < arr.length; i++)
        {
            arr[i] = out.get(i);
        }
        return arr;
    }

    /** Two half-width cards side by side. */
    private void pair(JComponent left, JComponent right)
    {
        JPanel row = new JPanel(new GridLayout(1, 2, BentoTheme.density().gap, 0));
        row.setOpaque(false);
        row.add(left);
        row.add(right);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        add(row);
    }

    private void renderGeneral(InsightsSnapshot s)
    {
        String period = period(s);
        if (s.sessionCount == 0 && s.net == 0L && s.activeMillis == 0L)
        {
            Tile empty = new Tile();
            empty.text(period + " has nothing yet.", BentoTheme.MUTED);
            empty.paragraph(s.partyOnly ? "The party filter is on." : "Play a little — Insights fill in as sessions end.",
                BentoTheme.secondary(), BentoTheme.DIM, Tile.interiorWidth());
            add(empty);
            return;
        }
        InsightsSnapshot.Highlights hl = s.highlights;

        // Answer card.
        HeroCard hero = new HeroCard().title(period, null);
        Double vs = s.vsPrevious();
        if (s.range != InsightsSnapshot.Range.ALL)
        {
            hero.status("vs " + previousLabel(s).replace("previous ", "prior "), null, true);
            if (vs != null)
            {
                hero.chip(pct(vs), vs >= 0 ? BentoTheme.POSITIVE : BentoTheme.NEGATIVE);
            }
        }
        else
        {
            hero.status(s.sessionCount + (s.sessionCount == 1 ? " session" : " sessions"), null, true);
        }
        String rateDelta = delta(s.averageRate, hl.previousAverageRate);
        String bestDelta = s.bestSession == null ? null : delta(s.bestSession.net, hl.previousBestNet);
        String deathsDelta = hl.previousDeaths == null || hl.previousDeaths == 0 ? null
            : pct((s.deaths - hl.previousDeaths) / (double) hl.previousDeaths);
        hero.hero(Fmt.signed(s.net), BentoTheme.signColor(s.net)).caption("Total")
            .lines(Collections.emptyList())
            .sparkline(cumulative(s.trend), true, BentoTheme.POSITIVE)
            // Performance lives inside the answer card: Tracked · Avg GP/h · Best run · Deaths with their deltas.
            .strip(Arrays.asList(
                new StatGrid.Cell(Icon.glyph("◷", BentoTheme.INFO), "Tracked", Fmt.durationCompact(s.activeMillis), BentoTheme.TEXT),
                new StatGrid.Cell(Icon.glyph("◉", BentoTheme.WARN), "Avg/h", s.averageRate == 0L ? "—" : Fmt.rate(s.averageRate), BentoTheme.TEXT,
                    rateDelta, rateDelta != null && rateDelta.startsWith("▲"), null),
                new StatGrid.Cell(Icon.glyph("♛", BentoTheme.WARN), "Best", s.bestSession == null ? "—" : Fmt.compact(s.bestSession.net), BentoTheme.TEXT,
                    bestDelta, bestDelta != null && bestDelta.startsWith("▲"), null),
                new StatGrid.Cell(Icon.glyph("☠", BentoTheme.MUTED), "Deaths", Integer.toString(s.deaths), BentoTheme.TEXT,
                    deathsDelta, deathsDelta != null && deathsDelta.startsWith("▼"), null)));
        hero.setToolTipText("Total profit across all activities · " + Fmt.exactSigned(s.net) + " gp · " + Fmt.duration(s.activeMillis) + " tracked"
            + (s.freePlayNet != 0L ? " · free play " + Fmt.signed(s.freePlayNet) : "")
            + (vs == null ? "" : " · " + pct(vs) + " vs the " + previousLabel(s)));
        add(hero);

        // Top activities, full width.
        List<MiniCard.Row> acts = new ArrayList<>();
        long positive = 0L;
        for (InsightsSnapshot.Activity a : s.activities)
        {
            positive += Math.max(0L, a.net);
        }
        int shown = 0;
        Color[] bars = {BentoTheme.POSITIVE, BentoTheme.INFO, BentoTheme.QUIET, BentoTheme.WARN, BentoTheme.PVP};
        for (InsightsSnapshot.Activity a : s.activities)
        {
            if (shown == 3)
            {
                break;
            }
            double share = positive <= 0L || a.net <= 0L ? 0d : a.net / (double) positive;
            acts.add(new MiniCard.Row(ActivityIcons.icon(a.name, null, sprites), a.name, Fmt.compact(a.net), BentoTheme.TEXT, share, bars[shown % bars.length])
                .note(share > 0d ? "(" + Math.round(share * 100) + "%)" : null));
            shown++;
        }
        if (!acts.isEmpty())
        {
            MiniCard top = new MiniCard().full(true).title(Icon.art(net.runelite.api.gameval.SpriteID.SideIcons.STATS, BentoTheme.TEXT), "Top Activities").rows(acts);
            top.setToolTipText("Share of the window's positive net");
            add(top);
        }

        // Top items: the header folds the list (remembered while the sidebar is open).
        if (!s.topItems.isEmpty())
        {
            ListBlock items = new ListBlock().header(null, (topItemsFolded ? "› " : "⌄ ") + "Top Items",
                topItemsFolded ? s.topItems.size() + " items" : "View all",
                topItemsFolded ? (Runnable) () -> { topItemsFolded = false; render(); }
                    : () -> actions.openItem(s.topItems.get(0).itemId, s.topItems.get(0).name));
            items.onHeaderClick(() ->
            {
                topItemsFolded = !topItemsFolded;
                render();
            });
            if (!topItemsFolded)
            {
                boolean first = true;
                for (InsightsSnapshot.Item it : s.topItems)
                {
                    ItemRow r = new ItemRow().sprite(sprites.apply(it.itemId)).name(it.name, Fmt.times(it.quantity))
                        .value(Fmt.compact(it.value), BentoTheme.TEXT).first(first);
                    r.onClick(() -> actions.openItem(it.itemId, it.name));
                    items.row(r);
                    first = false;
                }
            }
            add(items);
        }

        // Highlights for a window; on All they fold into Records (the same rows would otherwise repeat).
        boolean allTime = s.range == InsightsSnapshot.Range.ALL && !s.records.isEmpty();
        Tile highlights = allTime ? recordsCard(s) : new Tile().section(null, "Highlights", null, null);
        if (allTime)
        {
            highlights.gap(4);
        }
        NavRow drop = new NavRow().icon(hl.biggestDropItemId > 0 ? Icon.sprite(sprites.apply(hl.biggestDropItemId), "", BentoTheme.WARN) : Icon.glyph("", BentoTheme.WARN))
            .text("Biggest drop", hl.biggestDropName == null ? "none yet" : hl.biggestDropName)
            .right(hl.biggestDropName == null ? "—" : Fmt.compact(hl.biggestDropValue), hl.biggestDropName == null ? BentoTheme.DIM : BentoTheme.TEXT);
        if (hl.biggestDropItemId > 0)
        {
            drop.onClick(() -> actions.openItem(hl.biggestDropItemId, hl.biggestDropName));
        }
        highlights.add(drop);
        highlights.gap(4);
        highlights.add(new NavRow().icon(Icon.glyph("", BentoTheme.INFO)).chevron(false)
            .text("Best hour", hl.bestHourLabel == null ? "needs a day of play" : hl.bestHourLabel)
            .right(hl.bestHourLabel == null ? "—" : Fmt.signed(hl.bestHourNet), hl.bestHourLabel == null ? BentoTheme.DIM : BentoTheme.signColor(hl.bestHourNet)));
        highlights.gap(4);
        highlights.add(new NavRow().icon(Icon.glyph("", BentoTheme.MUTED)).chevron(false)
            .text("Longest session", hl.longestName == null ? "none yet" : hl.longestName + " · " + Fmt.dayLabel(
                java.time.Instant.ofEpochMilli(hl.longestAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate(), s.now))
            .right(hl.longestName == null ? "—" : Fmt.durationCompact(hl.longestMillis), hl.longestName == null ? BentoTheme.DIM : BentoTheme.TEXT));
        add(highlights);

        footer(s);
    }

    private Tile recordsCard(InsightsSnapshot s)
    {
        InsightsSnapshot.Records r = s.records;
        Tile card = new Tile().section(null, "Records", null, null);
        boolean first = true;
        // Longest session already sits in Highlights; Records adds what the window view never shows.
        for (InsightsSnapshot.Record rec : Arrays.asList(r.bestSession, r.bestRate, r.bestDay, r.bestWeek, r.wealthHigh))
        {
            if (rec == null)
            {
                continue;
            }
            if (!first)
            {
                card.gap(4);
            }
            first = false;
            String value = rec == r.longestSession ? Fmt.durationCompact(rec.value)
                : rec == r.bestRate ? Fmt.rate(rec.value) + "/h"
                : rec == r.wealthHigh ? Fmt.compact(rec.value) : Fmt.signed(rec.value);
            java.awt.Color color = rec == r.longestSession || rec == r.bestRate || rec == r.wealthHigh ? BentoTheme.TEXT : BentoTheme.signColor(rec.value);
            NavRow row = new NavRow().icon(Icon.glyph("", BentoTheme.MUTED)).chevron(rec.sessionId != null)
                .text(rec.label, rec.detail).right(value, color);
            if (rec.sessionId != null)
            {
                String id = rec.sessionId;
                row.onClick(() -> actions.openSession(id));
            }
            card.add(row);
        }
        if (r.streakLongest > 0)
        {
            if (!first)
            {
                card.gap(4);
            }
            card.add(new NavRow().icon(Icon.glyph("", BentoTheme.MUTED)).chevron(false)
                .text("Play streak", r.streakCurrent > 0 ? "Current " + r.streakCurrent + (r.streakCurrent == 1 ? " day" : " days") : "No play today")
                .right("Best " + r.streakLongest + (r.streakLongest == 1 ? " day" : " days"), BentoTheme.TEXT));
        }
        return card;
    }

    // ── PvP ─────────────────────────────────────────────────────────────────────────────

    private void renderPvp(InsightsSnapshot s)
    {
        InsightsSnapshot.Pvp p = s.pvp;
        String period = period(s);
        if (p.sessions == 0)
        {
            Tile empty = new Tile();
            empty.text(period + " has no PvP sessions.", BentoTheme.MUTED);
            empty.text("Kills and deaths land here once a fight is booked.", BentoTheme.DIM);
            add(empty);
            return;
        }
        // The PvP total: loot minus what deaths and fights cost — the PvP share of the General total.
        HeroCard hero = new HeroCard().title(period, "PvP")
            .status(s.range == InsightsSnapshot.Range.ALL ? p.sessions + (p.sessions == 1 ? " session" : " sessions")
                : "vs " + previousLabel(s).replace("previous ", "prior "), null, true)
            .hero(Fmt.signed(p.net), BentoTheme.signColor(p.net)).caption("Total");
        if (p.previousNet != null && p.previousNet != 0L)
        {
            double d = (p.net - p.previousNet) / (double) Math.abs(p.previousNet);
            hero.chip(pct(d), d >= 0 ? BentoTheme.POSITIVE : BentoTheme.NEGATIVE);
        }
        hero.lines(Collections.emptyList()).sparkline(p.series, true, BentoTheme.POSITIVE)
            .strip(Arrays.asList(
                new StatGrid.Cell(Icon.glyph("◆", BentoTheme.WARN), "Loot", Fmt.compact(p.killNet), BentoTheme.TEXT),
                new StatGrid.Cell(Icon.glyph("◇", BentoTheme.WARN), "Loss", Fmt.compact(p.deathLoss), BentoTheme.TEXT),
                new StatGrid.Cell(Icon.glyph("⊕", BentoTheme.POSITIVE), "Per kill", p.kills == 0 ? "—" : Fmt.compact(p.perKill()), BentoTheme.TEXT,
                    null, true, "Average loot per kill · median " + Fmt.compact(p.medianKill)
                        + (p.deaths == 0 ? "" : " · average loss per death " + Fmt.compact(p.perDeath())))));
        hero.setToolTipText("PvP total: loot minus deaths and fight supplies · counted inside the General total · " + Fmt.exactSigned(p.net) + " gp · "
            + p.kills + " kills and " + p.deaths + " deaths · " + p.sessions + (p.sessions == 1 ? " session" : " sessions")
            + " · median kill " + Fmt.compact(p.medianKill) + " · median death " + Fmt.compact(p.medianDeath)
            + (p.previousNet == null ? "" : " · chip vs the " + previousLabel(s)));
        add(hero);

        // Combat: one card of Kills · Deaths · K/D · Streak; fight cost rides in the tooltip.
        StatGrid combat = new StatGrid().joined(true).layout(StatGrid.Layout.ICON_TOP).cells(Arrays.asList(
            new StatGrid.Cell(Icon.glyph("⚔", BentoTheme.POSITIVE), "Kills", Integer.toString(p.kills), BentoTheme.POSITIVE),
            new StatGrid.Cell(Icon.glyph("☠", BentoTheme.NEGATIVE), "Deaths", Integer.toString(p.deaths), BentoTheme.NEGATIVE),
            new StatGrid.Cell(Icon.glyph("◑", BentoTheme.WARN), "K/D", String.format(java.util.Locale.ROOT, "%.2f", p.kd()), BentoTheme.TEXT),
            new StatGrid.Cell(Icon.glyph("✦", BentoTheme.WARN), "Streak", Integer.toString(p.streak), BentoTheme.WARN, null, true,
                "Best kill streak · supplies per fight " + (p.fights() == 0 ? "—" : Fmt.compact(p.suppliesPerFight())))), 4);
        add(combat);

        if (!p.bestKills.isEmpty())
        {
            Tile best = new Tile().section(Icon.art(net.runelite.api.gameval.SpriteID.SideIcons.COMBAT, BentoTheme.WARN), "Best Kills", null, null);
            boolean first = true;
            for (InsightsSnapshot.Fight f : p.bestKills)
            {
                if (!first)
                {
                    best.gap(4);
                }
                best.add(new NavRow().icon(Icon.glyph("☠", BentoTheme.TEXT)).chevron(false)
                    .text(f.label.replaceFirst("(?i)^kill:\\s*", ""), (f.location.isEmpty() ? "" : f.location + " · ") + Fmt.age(s.now - f.at))
                    .right(Fmt.compact(f.value), BentoTheme.POSITIVE));
                first = false;
            }
            add(best);
        }

        if (!p.worstDeaths.isEmpty())
        {
            Tile worst = new Tile().section(Icon.art(net.runelite.api.gameval.SpriteID.OrbIcon.HITPOINTS, BentoTheme.NEGATIVE), "Costliest Deaths", null, null);
            boolean first = true;
            for (InsightsSnapshot.Fight f : p.worstDeaths)
            {
                if (!first)
                {
                    worst.gap(4);
                }
                worst.add(new NavRow().icon(Icon.glyph("☠", BentoTheme.NEGATIVE)).chevron(false)
                    .text(f.label.replaceFirst("(?i)^death:\\s*", ""), (f.location.isEmpty() ? "" : f.location + " · ") + Fmt.age(s.now - f.at))
                    .right(Fmt.signed(f.value), BentoTheme.NEGATIVE));
                first = false;
            }
            add(worst);
        }

        // Top PK trips: the finished PvP sessions in the window, best net first — each counted in
        // General and in Top activities too; a trip opens in the Ledger.
        if (!p.trips.isEmpty())
        {
            Tile trips = new Tile().section(Icon.art(net.runelite.api.gameval.SpriteID.SideIcons.MINIGAMES, BentoTheme.TEXT), "Top PK Trips", null, null);
            boolean first = true;
            for (InsightsSnapshot.Trip t : p.trips)
            {
                if (!first)
                {
                    trips.gap(4);
                }
                String sub = Fmt.dayLabel(java.time.Instant.ofEpochMilli(t.startedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate(), s.now)
                    + " · " + Fmt.durationCompact(t.activeMillis) + " · " + t.kills + (t.kills == 1 ? " kill" : " kills")
                    + (t.deaths > 0 ? " · " + t.deaths + (t.deaths == 1 ? " death" : " deaths") : "")
                    + (t.place.isEmpty() ? "" : " · " + t.place);
                String id = t.sessionId;
                trips.add(new NavRow().icon(Icon.glyph("", BentoTheme.TEXT))
                    .text(t.name, sub)
                    .right(Fmt.signed(t.net), BentoTheme.signColor(t.net))
                    .onClick(() -> actions.openSession(id)));
                first = false;
            }
            trips.setToolTipText("Finished PvP sessions in the window · also part of the General total and Top activities");
            add(trips);
        }

        if (!p.deathPlaces.isEmpty())
        {
            Tile where = new Tile().section(Icon.art(net.runelite.api.gameval.SpriteID.SideIcons.KOUREND, BentoTheme.TEXT), "Where You Fight", null, null);
            boolean first = true;
            for (InsightsSnapshot.Place pl : p.deathPlaces)
            {
                if (!first)
                {
                    where.gap(4);
                }
                String sub = (pl.activeMillis >= 0L ? Fmt.durationCompact(pl.activeMillis) + " · " : "")
                    + pl.kills + (pl.kills == 1 ? " kill" : " kills") + (pl.deaths > 0 ? " · " + pl.deaths + (pl.deaths == 1 ? " death" : " deaths") : "");
                where.add(new NavRow().icon(Icon.glyph("", BentoTheme.TEXT)).chevron(false)
                    .text(pl.label, sub).right(Fmt.signed(pl.net), BentoTheme.signColor(pl.net)));
                first = false;
            }
            where.setToolTipText("Fights by place · time covers the whole tracked PvP session in that place");
            add(where);
        }

        // PvP records over everything retained; only where the range is All.
        InsightsSnapshot.Records r = s.records;
        if (s.range == InsightsSnapshot.Range.ALL && (r.bestKill != null || r.longestKillStreak != null || r.bestKd != null))
        {
            Tile records = new Tile().section(Icon.art(net.runelite.api.gameval.SpriteID.SideIcons.ACHIEVEMENT_DIARIES, BentoTheme.WARN), "Records", null, null);
            boolean first = true;
            for (InsightsSnapshot.Record rec : java.util.Arrays.asList(r.bestKill, r.longestKillStreak, r.bestKd))
            {
                if (rec == null)
                {
                    continue;
                }
                if (!first)
                {
                    records.gap(4);
                }
                first = false;
                String value = rec == r.bestKill ? Fmt.compact(rec.value)
                    : rec == r.longestKillStreak ? rec.value + (rec.value == 1L ? " kill" : " kills")
                    : String.format(java.util.Locale.ROOT, "%.2f", rec.value / 1_000_000d);
                NavRow row = new NavRow().chevron(rec.sessionId != null).text(rec.label, rec.detail)
                    .right(value, rec == r.bestKill ? BentoTheme.POSITIVE : BentoTheme.TEXT);
                if (rec.sessionId != null)
                {
                    String id = rec.sessionId;
                    row.onClick(() -> actions.openSession(id));
                }
                records.add(row);
            }
            add(records);
        }

        footer(s);
    }

    // ── Wealth ───────────────────────────────────────────────────────────────────────────

    private static String wealthClass(InsightsSnapshot.WealthLocation l)
    {
        String id = (l.id + " " + l.title).toLowerCase(java.util.Locale.ROOT);
        if (id.contains("bank"))
        {
            return "Bank";
        }
        if (id.contains("worn") || id.contains("equip"))
        {
            return "Equipped";
        }
        if (id.contains("ge") || id.contains("grand") || id.contains("collection"))
        {
            return "GE";
        }
        return "Other";
    }

    private void renderWealth(InsightsSnapshot s)
    {
        InsightsSnapshot.Wealth w = s.wealth;
        InsightsSnapshot.WealthChange thirty = null;
        InsightsSnapshot.WealthChange any = null;
        for (InsightsSnapshot.WealthChange c : w.changes)
        {
            if (c.available)
            {
                any = any == null ? c : any;
                if (c.label.startsWith("30"))
                {
                    thirty = c;
                }
            }
        }
        HeroCard hero = new HeroCard().title("Tracked Wealth", null)
            .status(!w.available ? "not read yet" : Fmt.age(s.now - w.capturedAt), w.available ? BentoTheme.POSITIVE : BentoTheme.DIM, false)
            .hero(w.available ? Fmt.compact(w.total) : "—", w.available ? BentoTheme.POSITIVE : BentoTheme.DIM).caption("Wealth");
        InsightsSnapshot.WealthChange lead = thirty != null ? thirty : any;
        String leadText = null;
        if (lead != null)
        {
            long base = w.total - lead.total();
            String pctText = base > 0L ? " " + String.format(java.util.Locale.ROOT, "%.1f%%", Math.abs(lead.total()) * 100d / base) : "";
            leadText = (lead.total() >= 0 ? "▲ " : "▼ ") + Fmt.compact(Math.abs(lead.total())) + pctText;
            hero.chip(leadText, BentoTheme.signColor(lead.total()));
        }
        // The per-day trend when the engine has one (gaps carry the last captured total so the
        // line reads as a hold, never as a drop to zero); the raw capture timeline otherwise.
        double[] series;
        if (w.trend.size() > 1)
        {
            List<Double> pts = new ArrayList<>();
            double lastSeen = Double.NaN;
            for (InsightsSnapshot.TrendPoint p : w.trend)
            {
                if (p.captured)
                {
                    lastSeen = p.total;
                }
                if (!Double.isNaN(lastSeen))
                {
                    pts.add(lastSeen);
                }
            }
            series = new double[pts.size()];
            for (int i = 0; i < series.length; i++)
            {
                series[i] = pts.get(i);
            }
        }
        else
        {
            series = new double[w.timeline.size()];
            for (int i = 0; i < series.length; i++)
            {
                series[i] = w.timeline.get(i);
            }
        }
        List<StatGrid.Cell> wealthStrip = new ArrayList<>();
        boolean remainder = false;
        for (InsightsSnapshot.WealthGroup g : w.groups)
        {
            if (wealthStrip.size() == 3)
            {
                break;
            }
            double share = g.share >= 0d ? g.share : (w.total <= 0L ? 0d : g.value / (double) w.total);
            wealthStrip.add(new StatGrid.Cell(Icon.glyph("Bank".equals(g.label) ? "◉" : "Equipped".equals(g.label) ? "⛨" : "GE".equals(g.label) ? "⇄" : "◍",
                "Bank".equals(g.label) ? BentoTheme.POSITIVE : "Equipped".equals(g.label) ? BentoTheme.INFO : "GE".equals(g.label) ? BentoTheme.QUIET : BentoTheme.WARN),
                g.label + " " + Math.round(share * 100) + "%", Fmt.compact(g.value), BentoTheme.TEXT));
            remainder |= g.remainder;
        }
        hero.lines(Collections.emptyList()).sparkline(series, true, BentoTheme.POSITIVE).strip(w.available ? wealthStrip : Collections.emptyList());
        hero.setToolTipText("Tracks bank value, equipped items and market gains · " + (w.available ? Fmt.exact(w.total) + " gp · never part of Net · prices at capture"
            : "open the bank once to record a visit") + (lead == null ? "" : " · " + leadText + " " + lead.label.toLowerCase(java.util.Locale.ROOT)));
        add(hero);

        // The bar breakdown only when the strip cannot tell the whole story (a fourth group or a remainder).
        if (w.available && (w.groups.size() > 3 || remainder))
        {
            Tile breakdown = new Tile().section(null, "Wealth Breakdown", Fmt.compact(w.total), null);
            for (InsightsSnapshot.WealthGroup g : w.groups)
            {
                String glyph = "Bank".equals(g.label) ? "◉" : "Equipped".equals(g.label) ? "⛨" : "GE".equals(g.label) ? "⇄" : "◍";
                Color color = "Bank".equals(g.label) ? BentoTheme.POSITIVE : "Equipped".equals(g.label) ? BentoTheme.INFO
                    : "GE".equals(g.label) ? BentoTheme.QUIET : BentoTheme.WARN;
                double share = g.share >= 0d ? g.share : (w.total <= 0L ? 0d : g.value / (double) w.total);
                breakdown.add(shareRow(Icon.glyph(glyph, color), g.label + (g.remainder ? " +" : ""), Fmt.compact(g.value), share, color));
                breakdown.gap(4);
            }
            add(breakdown);
        }

        Tile sits = new Tile().section(Icon.art(net.runelite.api.gameval.SpriteID.BanktabIcons.ALL_ITEMS, BentoTheme.MUTED), "Where It Sits", null, null);
        if (w.locations.isEmpty())
        {
            sits.text("Open the bank once to record a visit.", BentoTheme.DIM);
        }
        boolean first = true;
        for (InsightsSnapshot.WealthLocation l : w.locations)
        {
            if (!first)
            {
                sits.gap(4);
            }
            sits.add(new NavRow().icon(Icon.glyph(wealthClass(l).equals("Bank") ? "◉" : wealthClass(l).equals("Equipped") ? "⛨" : "⇄", BentoTheme.MUTED))
                .chevron(false).text(l.title, l.valueAvailable ? null : l.status)
                .right(l.valueAvailable ? Fmt.compact(l.value) : "—", l.valueAvailable ? BentoTheme.TEXT : BentoTheme.DIM));
            first = false;
        }
        add(sits);

        // Coin stores: what keeps Other (and the complete total) available. A store the owner never
        // has can be declared unused so it counts as zero instead of holding the total back.
        if (!w.coinStores.isEmpty())
        {
            Tile coffers = new Tile().section(Icon.art(net.runelite.api.gameval.SpriteID.GeSmallicons.GUIDE_PRICE, BentoTheme.MUTED), "Coffers", null, null);
            boolean firstStore = true;
            for (InsightsSnapshot.CoinStoreRow c : w.coinStores)
            {
                if (!firstStore)
                {
                    coffers.gap(4);
                }
                firstStore = false;
                String sub = c.unused ? "counts as zero" : "fresh".equals(c.state) ? "read within 7 days"
                    : "stale".equals(c.state) ? "last read over 7 days ago · open it once" : "not read yet · open it once";
                NavRow row = new NavRow().chevron(false).text(c.title, sub)
                    .right(c.unused ? "unused" : c.value == null ? "—" : Fmt.compact(c.value), c.unused || c.value == null ? BentoTheme.DIM : BentoTheme.TEXT);
                coffers.add(row);
                if (!"fresh".equals(c.state))
                {
                    Controls.Button mark = new Controls.Button(c.unused ? "Counts again" : "Mark unused", Controls.Button.Kind.GHOST);
                    mark.setToolTipText(c.unused ? "Start expecting a read of this store again" : "I never use this store: count it as zero");
                    final InsightsSnapshot.CoinStoreRow rowRef = c;
                    mark.onClick(() -> actions.markCoinStoreUnused(rowRef.store, !rowRef.unused));
                    coffers.gap(2).row(Tile.buttons(mark));
                }
            }
            coffers.setToolTipText("Other is only complete when every store is fresh or unused");
            add(coffers);
        }

        Tile facts = new Tile().section(Icon.glyph("✦", BentoTheme.WARN), "Insights", null, null);
        int rows = 0;
        for (InsightsSnapshot.WealthChange c : w.changes)
        {
            if (!c.available)
            {
                continue;
            }
            if (rows > 0)
            {
                facts.gap(4);
            }
            long base = w.total - c.total();
            String pctText = base > 0L ? " (" + String.format(java.util.Locale.ROOT, "%.1f%%", Math.abs(c.total()) * 100d / base) + ")" : "";
            String sub = "earned " + Fmt.signed(c.earned) + " · market " + Fmt.signed(c.market)
                + (c.unexplained != 0L ? " · unexplained " + Fmt.signed(c.unexplained) : "");
            facts.add(new NavRow().icon(Icon.glyph(c.total() >= 0L ? "↗" : "↘", BentoTheme.signColor(c.total()))).chevron(false)
                .text("Wealth " + (c.total() >= 0L ? "up " : "down ") + Fmt.compact(c.total()) + pctText + " · " + c.label.toLowerCase(java.util.Locale.ROOT), sub));
            rows++;
        }
        if (rows == 0)
        {
            facts.text("Needs two bank visits to compare.", BentoTheme.DIM);
        }
        add(facts);

        if (!w.movers.isEmpty())
        {
            ListBlock movers = new ListBlock().header(null, "Top movers · 30 days", null, null);
            boolean f2 = true;
            for (InsightsSnapshot.Mover m : w.movers)
            {
                ItemRow r = new ItemRow().sprite(m.itemId > 0 ? sprites.apply(m.itemId) : null).name(m.name, null)
                    .verb(m.reason).value(Fmt.signed(m.change), BentoTheme.signColor(m.change)).first(f2);
                if (m.itemId > 0)
                {
                    r.onClick(() -> actions.openItem(m.itemId, m.name));
                }
                movers.row(r);
                f2 = false;
            }
            add(movers);
        }
    }

    /** icon · name · value · share% · bar — one Wealth breakdown line. */
    private static JComponent shareRow(Icon icon, String name, String value, double share, Color color)
    {
        JComponent row = new JComponent()
        {
            @Override
            public Dimension getPreferredSize()
            {
                return new Dimension(Tile.interiorWidth(), Icon.SIZE + 6);
            }

            @Override
            public Dimension getMaximumSize()
            {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }

            @Override
            protected void paintComponent(java.awt.Graphics g)
            {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                try
                {
                    BentoTheme.quality(g2);
                    int w = getWidth();
                    int h = getHeight();
                    int cy = h / 2;
                    icon.paint(g2, 0, cy - Icon.SIZE / 2, Icon.SIZE, false);
                    int x = Icon.SIZE + 8;
                    g2.setFont(BentoTheme.body());
                    java.awt.FontMetrics fm = g2.getFontMetrics();
                    int base = cy + (fm.getAscent() - fm.getDescent()) / 2;
                    int barW = 36;
                    java.awt.Font pctFont = BentoTheme.font(java.awt.Font.PLAIN, BentoTheme.density().micro);
                    java.awt.Font valueFont = BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().secondary);
                    int pctW = g2.getFontMetrics(pctFont).stringWidth("100%");
                    int valueW = g2.getFontMetrics(valueFont).stringWidth(value);
                    int nameAvail = w - x - barW - 6 - pctW - 6 - valueW - 6;
                    g2.setFont(BentoTheme.secondary());
                    fm = g2.getFontMetrics();
                    base = cy + (fm.getAscent() - fm.getDescent()) / 2;
                    g2.setColor(BentoTheme.TEXT);
                    g2.drawString(StatBlock.fit(name, fm, Math.max(24, nameAvail)), x, base);
                    g2.setFont(valueFont);
                    g2.setColor(BentoTheme.TEXT);
                    int vx = w - barW - 6 - pctW - 6 - valueW;
                    g2.drawString(value, vx, base);
                    g2.setFont(pctFont);
                    g2.setColor(BentoTheme.MUTED);
                    String pctText = Math.round(share * 100) + "%";
                    g2.drawString(pctText, w - barW - 6 - g2.getFontMetrics().stringWidth(pctText), base);
                    g2.setColor(BentoTheme.SOFT);
                    g2.fillRoundRect(w - barW, cy - 3, barW, 6, 6, 6);
                    int fw = (int) Math.round(barW * Math.max(0d, Math.min(1d, share)));
                    if (fw > 0)
                    {
                        g2.setColor(color);
                        g2.fillRoundRect(w - barW, cy - 3, Math.max(6, fw), 6, 6, 6);
                    }
                }
                finally
                {
                    g2.dispose();
                }
            }
        };
        row.setToolTipText(name + " · " + value + " · " + Math.round(share * 100) + "%");
        row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        return row;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    private void footer(InsightsSnapshot s)
    {
        StringBuilder sb = new StringBuilder(s.rollupBacked ? "Profile rollups" : s.receiptsComplete ? "Counted receipts" : "Counted receipts · some compacted");
        sb.append(" · ").append(s.sessionCount).append(s.sessionCount == 1 ? " session" : " sessions");
        if (s.excludedCount > 0)
        {
            sb.append(" · ").append(s.excludedCount).append(" excluded from averages");
        }
        if (s.partyOnly)
        {
            sb.append(" · with party");
        }
        JLabel f = Tile.label(StatBlock.fit(sb.toString(), new JLabel().getFontMetrics(BentoTheme.secondary()), BentoTheme.MIN_CONTENT_WIDTH - 8), BentoTheme.secondary(), BentoTheme.DIM);
        f.setToolTipText(sb + " · prices at capture");
        f.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        content.add(f);
    }

    private void add(JComponent c)
    {
        c.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        content.add(c);
        content.add(Box.createVerticalStrut(BentoTheme.density().gap));
    }

    /** CSV of the current view; shared with the panel's export action. */
    static String csv(InsightsSnapshot s, Mode mode)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("range,").append(s.range.label).append('\n');
        if (mode == Mode.PVP)
        {
            InsightsSnapshot.Pvp p = s.pvp;
            sb.append("metric,value\n");
            sb.append("sessions,").append(p.sessions).append('\n');
            sb.append("kills,").append(p.kills).append('\n');
            sb.append("deaths,").append(p.deaths).append('\n');
            sb.append("kill_net,").append(p.killNet).append('\n');
            sb.append("death_loss,").append(p.deathLoss).append('\n');
            sb.append("median_kill,").append(p.medianKill).append('\n');
            sb.append("median_death,").append(p.medianDeath).append('\n');
            sb.append("supplies,").append(p.supplies).append('\n');
            return sb.toString();
        }
        if (mode == Mode.WEALTH)
        {
            sb.append("location,value,available\n");
            for (InsightsSnapshot.WealthLocation l : s.wealth.locations)
            {
                sb.append(q(l.title)).append(',').append(l.value).append(',').append(l.valueAvailable).append('\n');
            }
            return sb.toString();
        }
        sb.append("section,name,quantity,value\n");
        sb.append("total,net,,").append(s.net).append('\n');
        sb.append("total,active_ms,,").append(s.activeMillis).append('\n');
        for (InsightsSnapshot.SessionPoint p : s.trend)
        {
            sb.append("session,").append(q(p.name)).append(',').append(p.excluded ? "excluded" : "").append(',').append(p.net).append('\n');
        }
        for (InsightsSnapshot.Activity a : s.activities)
        {
            sb.append("activity,").append(q(a.name)).append(',').append(a.sessions).append(',').append(a.net).append('\n');
        }
        for (InsightsSnapshot.Item i : s.topItems)
        {
            sb.append("item,").append(q(i.name)).append(',').append(i.quantity).append(',').append(i.value).append('\n');
        }
        for (InsightsSnapshot.Item i : s.topCosts)
        {
            sb.append("cost,").append(q(i.name)).append(',').append(i.quantity).append(',').append(-i.value).append('\n');
        }
        return sb.toString();
    }

    private static String q(String v)
    {
        return '"' + v.replace("\"", "\"\"") + '"';
    }
}
