package com.gpmanager.ui.bento;

import com.gpmanager.model.TransactionCorrection;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;

/**
 * Tools as a settings app (SIDEBAR_BENTO.md §13.4): a search field over every row, groups as
 * cards with icon headers — Attention · Session · Data · Preferences · Help · Danger zone —
 * rows that toggle, pick or open a sub-page (`‹ Tools › Storage`) with scroll memory.
 * Reads {@link ToolsSnapshot}; every click goes through {@link Actions}.
 */
public final class ToolsPage implements BentoShell.Page
{
    public interface Actions
    {
        void decide(ToolsSnapshot.Decision decision, TransactionCorrection correction);

        /** Applies one correction to every pending decision. */
        void decideAll(TransactionCorrection correction);

        void undoLastCorrection();

        void openInsightsWealth();

        void setPartySharing(boolean on);

        void setPartyHud(boolean on);

        void showSplit();

        void copyPartyCard();

        void setLiveTileHidden(String tileId, boolean hidden);

        void moveLiveTile(String tileId, boolean up);

        void exportSessionCsv();

        void exportHistoryCsv();

        void backupProfile();

        void restoreProfile();

        void copyDataPath();

        void openExportFolder();

        void copyAsImage();

        void restoreLastUndo();

        void clearCompletedHistory();

        void restartOverall();

        void setRetention(com.gpmanager.ReceiptRetentionPeriod period);

        void compactOlderNow();

        void includeItem(int itemId);

        void clearOverride(int itemId);

        void addOverride();

        void setNotableDrop(long gp);

        void setAlertGoalReached(boolean on);

        void setWealthMilestone(long gp);

        void setSessionIdleAutoEnd(int minutes);

        void setAccent(BentoTheme.Accent accent);

        void setPvpMode(PvpMode mode);

        void setNetGraph(boolean on);

        void setExactFigures(boolean on);

        void setGeBookingObserved(boolean on);

        void setGeSellSpentIsNet(boolean on);

        void resetLayout();

        void setReducedMotion(boolean on);

        void setBoundary(String key, BoundaryMode mode);

        void openPluginSettings();

        void setDebugTrace(boolean on);

        void copyDiagnostics();

        void showShortcuts();

        void showHelp();

        void reportIssue();

        void deleteSession();

        void resetTracking();

        void factoryReset();

        String itemName(int itemId);
    }

    /** Current settings the page shows; the panel reads them from config. */
    public static final class Settings
    {
        public final String profile;
        public final boolean partySharing;
        public final boolean partyHud;
        public final long notableDropGp;
        public final BentoTheme.Accent accent;
        public final BentoTheme.Density density;
        public final PvpMode pvpMode;
        public final boolean netGraph;
        public final boolean exactFigures;
        /** Grand Exchange booking switches (pass 10 step 45); set after construction. */
        public boolean geBookingObserved;
        public boolean geSellSpentIsNet = true;

        public Settings withGe(boolean observed, boolean sellSpentIsNet)
        {
            this.geBookingObserved = observed;
            this.geSellSpentIsNet = sellSpentIsNet;
            return this;
        }
        public final boolean debugTrace;
        public final boolean canWriteConfig;
        public final BoundaryMode boundarySlayer;
        public final BoundaryMode boundaryRaid;
        public final BoundaryMode boundaryBank;
        public final com.gpmanager.ReceiptRetentionPeriod retention;
        public final boolean alertGoalReached;
        public final long wealthMilestoneGp;
        public final int sessionIdleAutoEndMinutes;
        public final boolean reducedMotion;
        /** "1.0 · schema 23 · RuneLite 1.11" for About; empty when unknown. */
        public final String about;

        public Settings(String profile, boolean partySharing, boolean partyHud, long notableDropGp, BentoTheme.Accent accent,
            BentoTheme.Density density, PvpMode pvpMode, boolean netGraph, boolean debugTrace, boolean canWriteConfig)
        {
            this(profile, partySharing, partyHud, notableDropGp, accent, density, pvpMode, netGraph, debugTrace, canWriteConfig,
                BoundaryMode.OFF, BoundaryMode.OFF, BoundaryMode.OFF, com.gpmanager.ReceiptRetentionPeriod.DAYS_90);
        }

        public Settings(String profile, boolean partySharing, boolean partyHud, long notableDropGp, BentoTheme.Accent accent,
            BentoTheme.Density density, PvpMode pvpMode, boolean netGraph, boolean debugTrace, boolean canWriteConfig,
            BoundaryMode boundarySlayer, BoundaryMode boundaryRaid, BoundaryMode boundaryBank,
            com.gpmanager.ReceiptRetentionPeriod retention)
        {
            this(profile, partySharing, partyHud, notableDropGp, accent, density, pvpMode, netGraph, debugTrace, canWriteConfig,
                boundarySlayer, boundaryRaid, boundaryBank, retention, true, 0L, false, "");
        }

        public Settings(String profile, boolean partySharing, boolean partyHud, long notableDropGp, BentoTheme.Accent accent,
            BentoTheme.Density density, PvpMode pvpMode, boolean netGraph, boolean debugTrace, boolean canWriteConfig,
            BoundaryMode boundarySlayer, BoundaryMode boundaryRaid, BoundaryMode boundaryBank,
            com.gpmanager.ReceiptRetentionPeriod retention, boolean alertGoalReached,
            long wealthMilestoneGp, boolean reducedMotion, String about)
        {
            this(profile, partySharing, partyHud, notableDropGp, accent, density, pvpMode, netGraph, debugTrace, canWriteConfig,
                boundarySlayer, boundaryRaid, boundaryBank, retention, alertGoalReached, wealthMilestoneGp, reducedMotion, about, false);
        }

        public Settings(String profile, boolean partySharing, boolean partyHud, long notableDropGp, BentoTheme.Accent accent,
            BentoTheme.Density density, PvpMode pvpMode, boolean netGraph, boolean debugTrace, boolean canWriteConfig,
            BoundaryMode boundarySlayer, BoundaryMode boundaryRaid, BoundaryMode boundaryBank,
            com.gpmanager.ReceiptRetentionPeriod retention, boolean alertGoalReached,
            long wealthMilestoneGp, boolean reducedMotion, String about, boolean exactFigures)
        {
            this(profile, partySharing, partyHud, notableDropGp, accent, density, pvpMode, netGraph, debugTrace, canWriteConfig,
                boundarySlayer, boundaryRaid, boundaryBank, retention, alertGoalReached, wealthMilestoneGp, reducedMotion, about,
                exactFigures, 0);
        }

        public Settings(String profile, boolean partySharing, boolean partyHud, long notableDropGp, BentoTheme.Accent accent,
            BentoTheme.Density density, PvpMode pvpMode, boolean netGraph, boolean debugTrace, boolean canWriteConfig,
            BoundaryMode boundarySlayer, BoundaryMode boundaryRaid, BoundaryMode boundaryBank,
            com.gpmanager.ReceiptRetentionPeriod retention, boolean alertGoalReached,
            long wealthMilestoneGp, boolean reducedMotion, String about, boolean exactFigures, int sessionIdleAutoEndMinutes)
        {
            this.sessionIdleAutoEndMinutes = sessionIdleAutoEndMinutes;
            this.retention = retention == null ? com.gpmanager.ReceiptRetentionPeriod.DAYS_90 : retention;
            this.boundarySlayer = boundarySlayer;
            this.boundaryRaid = boundaryRaid;
            this.boundaryBank = boundaryBank;
            this.profile = profile;
            this.partySharing = partySharing;
            this.partyHud = partyHud;
            this.notableDropGp = notableDropGp;
            this.accent = accent;
            this.density = density;
            this.pvpMode = pvpMode;
            this.netGraph = netGraph;
            this.exactFigures = exactFigures;
            this.debugTrace = debugTrace;
            this.canWriteConfig = canWriteConfig;
            this.alertGoalReached = alertGoalReached;
            this.wealthMilestoneGp = wealthMilestoneGp;
            this.reducedMotion = reducedMotion;
            this.about = about == null ? "" : about;
        }
    }

    /** The sub-pages; ROOT is the grouped overview. */
    public enum View
    {
        ROOT("Tools"), REVIEW("Review"), ALERTS("Alerts"), LAYOUT("Layout"), PARTY("Party"), STORAGE("Storage"),
        RULES("Rules"), APPEARANCE("Appearance"), DIAGNOSTICS("Diagnostics"), HELP("Help");

        public final String title;

        View(String title)
        {
            this.title = title;
        }
    }

    /** One searchable row: where it lives, what it says, and how to build it. */
    private static final class Entry
    {
        final String group;
        final View view;
        final String title;
        final String subtitle;
        final String keywords;
        final Supplier<JComponent> factory;

        Entry(String group, View view, String title, String subtitle, String keywords, Supplier<JComponent> factory)
        {
            this.group = group;
            this.view = view;
            this.title = title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.keywords = keywords == null ? "" : keywords;
            this.factory = factory;
        }

        boolean matches(String q)
        {
            String hay = (group + " " + view.title + " " + title + " " + subtitle + " " + keywords).toLowerCase(Locale.ROOT);
            for (String word : q.split("\\s+"))
            {
                if (!word.isEmpty() && !hay.contains(word))
                {
                    return false;
                }
            }
            return true;
        }
    }

    private static final long[] DROP_THRESHOLDS = {100_000L, 250_000L, 500_000L, 1_000_000L, 5_000_000L, 10_000_000L};
    private static final long[] MILESTONES = {0L, 1_000_000L, 10_000_000L, 50_000_000L, 100_000_000L, 500_000_000L, 1_000_000_000L};
    private static final int[] IDLE_MINUTES = {0, 5, 10, 15, 20, 30, 60};
    private static final Color ORANGE = new Color(0xff8c42);

    private final Actions actions;
    private final Function<Integer, BufferedImage> sprites;
    private final JPanel bar = new JPanel();
    private final JPanel body = BentoShell.stack();
    private final JPanel searchRow = new JPanel();
    private final JTextField searchField = new JTextField();
    private final JPanel content = new JPanel();
    private final java.util.Map<View, Integer> scrollMemory = new java.util.EnumMap<>(View.class);
    private View view = View.ROOT;
    private String query = "";
    private String rulesFilter = "";
    @Nullable
    private ToolsSnapshot last;
    private Settings settings = new Settings("", false, false, 1_000_000L, BentoTheme.Accent.MINT,
        BentoTheme.Density.COMFORTABLE, PvpMode.AUTO, false, false, false);

    public ToolsPage(Actions actions, Function<Integer, BufferedImage> sprites)
    {
        this.actions = actions;
        this.sprites = sprites == null ? id -> null : sprites;
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setOpaque(false);

        searchRow.setLayout(new BoxLayout(searchRow, BoxLayout.X_AXIS));
        searchRow.setOpaque(false);
        searchField.setFont(BentoTheme.body());
        searchField.setForeground(BentoTheme.TEXT);
        searchField.setCaretColor(BentoTheme.TEXT);
        searchField.setBackground(BentoTheme.SURFACE);
        searchField.setBorder(javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createLineBorder(BentoTheme.BORDER),
            javax.swing.BorderFactory.createEmptyBorder(5, 8, 5, 8)));
        searchField.setToolTipText("Search settings");
        searchField.getAccessibleContext().setAccessibleName("Search settings");
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
        {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e)
            {
                queryChanged();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e)
            {
                queryChanged();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e)
            {
                queryChanged();
            }
        });
        JLabel glass = Tile.label("⌕", BentoTheme.symbol(BentoTheme.density().body + 2), BentoTheme.DIM);
        glass.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 2, 0, 6));
        searchRow.add(glass);
        searchRow.add(searchField);
        searchRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, searchField.getPreferredSize().height));
        BentoShell.stackAdd(body, searchRow);

        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        content.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        BentoShell.stackAdd(body, content);
        rebuildBar();
    }

    @Override
    public String id()
    {
        return BentoShell.TOOLS;
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

    public View view()
    {
        return view;
    }

    @Nullable
    public ToolsSnapshot lastForPreview()
    {
        return last;
    }

    /** Every Tools row as a palette hit; opening one shows its page with the row in view. */
    public List<SearchPage.Hit> hits(Runnable showTools)
    {
        List<SearchPage.Hit> out = new ArrayList<>();
        if (last == null)
        {
            return out;
        }
        for (Entry e : entries(last))
        {
            String where = e.view == View.ROOT ? e.group : e.group + " › " + e.view.title;
            Runnable open = () ->
            {
                searchField.setText("");
                show(e.view);
                showTools.run();
            };
            out.add(new SearchPage.Hit("Settings", Icon.glyph("⚙", BentoTheme.MUTED), e.title, where + (e.subtitle.isEmpty() ? "" : " · " + e.subtitle),
                null, null, open));
        }
        return out;
    }

    /** Test/preview seam: type into the search field. */
    public void searchForPreview(String text)
    {
        searchField.setText(text == null ? "" : text);
    }

    /** Opens a sub-page (or the overview); remembers where the previous view was scrolled to. */
    public void show(View next)
    {
        rememberScroll();
        view = next == null ? View.ROOT : next;
        rebuildBar();
        render();
        restoreScroll();
    }

    private void queryChanged()
    {
        query = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        render();
    }

    public void apply(ToolsSnapshot s, Settings current)
    {
        last = s;
        settings = current == null ? settings : current;
        render();
    }

    private void rebuildBar()
    {
        bar.removeAll();
        if (view == View.ROOT)
        {
            bar.add(Tile.label("Tools", BentoTheme.font(java.awt.Font.BOLD, 13f), BentoTheme.TEXT));
            bar.add(Box.createHorizontalGlue());
            if (!settings.profile.isEmpty())
            {
                bar.add(Tile.label("profile · " + settings.profile, BentoTheme.secondary(), BentoTheme.DIM));
            }
        }
        else
        {
            Controls.IconButton back = new Controls.IconButton("‹", "Back to Tools", true);
            back.onClick(() -> show(View.ROOT));
            bar.add(back);
            bar.add(Box.createHorizontalStrut(8));
            bar.add(Tile.label("Tools", BentoTheme.font(java.awt.Font.BOLD, 13f), BentoTheme.MUTED));
            bar.add(Tile.label(" › " + view.title, BentoTheme.font(java.awt.Font.BOLD, 13f), BentoTheme.TEXT));
            bar.add(Box.createHorizontalGlue());
        }
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, bar.getPreferredSize().height));
        bar.revalidate();
        bar.repaint();
    }

    private void rememberScroll()
    {
        javax.swing.JScrollPane pane = (javax.swing.JScrollPane) javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane.class, body);
        if (pane != null)
        {
            scrollMemory.put(view, pane.getVerticalScrollBar().getValue());
        }
    }

    private void restoreScroll()
    {
        javax.swing.JScrollPane pane = (javax.swing.JScrollPane) javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane.class, body);
        if (pane != null)
        {
            Integer value = scrollMemory.get(view);
            javax.swing.SwingUtilities.invokeLater(() -> pane.getVerticalScrollBar().setValue(value == null ? 0 : value));
        }
    }

    // ── rendering ────────────────────────────────────────────────────────────────────────

    private void render()
    {
        content.removeAll();
        ToolsSnapshot s = last;
        if (s != null)
        {
            List<Entry> entries = entries(s);
            if (!query.isEmpty())
            {
                renderSearch(entries);
            }
            else if (view == View.ROOT)
            {
                renderRoot(s, entries);
            }
            else
            {
                renderView(s, entries);
            }
        }
        content.revalidate();
        content.repaint();
    }

    /** Search results: every matching row, its group named above the first of each group. */
    private void renderSearch(List<Entry> entries)
    {
        String lastGroup = null;
        int n = 0;
        for (Entry e : entries)
        {
            if (!e.matches(query))
            {
                continue;
            }
            if (!e.group.equals(lastGroup))
            {
                JLabel head = Tile.micro(e.group + (e.view == View.ROOT ? "" : " › " + e.view.title), BentoTheme.DIM);
                head.setBorder(javax.swing.BorderFactory.createEmptyBorder(n == 0 ? 0 : 6, 2, 4, 2));
                head.setAlignmentX(JComponent.LEFT_ALIGNMENT);
                content.add(head);
                lastGroup = e.group;
            }
            JComponent c = e.factory.get();
            c.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            content.add(c);
            content.add(Box.createVerticalStrut(4));
            n++;
        }
        if (n == 0)
        {
            ListBlock empty = new ListBlock().empty("Nothing matches \"" + query + "\"");
            add(empty);
        }
    }

    private void renderRoot(ToolsSnapshot s, List<Entry> entries)
    {
        String[] groups = {"Attention", "Session", "Data", "Preferences", "Help", "Danger zone"};
        int[] art = {net.runelite.api.gameval.SpriteID.SideIcons.QUEST, net.runelite.api.gameval.SpriteID.SideIcons.MINIGAMES, net.runelite.api.gameval.SpriteID.BanktabIcons.WRENCH, net.runelite.api.gameval.SpriteID.SideIcons.OPTIONS,
            net.runelite.api.gameval.SpriteID.WikiIcon.SELECTED, net.runelite.api.gameval.SpriteID.SideIcons.LOGOUT};
        Color[] tints = {BentoTheme.WARN, BentoTheme.INFO, ORANGE, BentoTheme.MUTED, BentoTheme.MUTED, BentoTheme.NEGATIVE};
        for (int g = 0; g < groups.length; g++)
        {
            Tile card = new Tile();
            if ("Danger zone".equals(groups[g]))
            {
                card.borderColor(BentoTheme.withAlpha(BentoTheme.NEGATIVE, 110));
            }
            String right = "Attention".equals(groups[g]) && !s.decisions.isEmpty() ? "Review · " + s.decisions.size() : null;
            JPanel head = new JPanel();
            head.setLayout(new BoxLayout(head, BoxLayout.X_AXIS));
            head.setOpaque(false);
            Tile.IconBox box = new Tile.IconBox(Icon.art(art[g], tints[g]), tints[g]);
            box.setAlignmentY(JComponent.CENTER_ALIGNMENT);
            head.add(box);
            head.add(Box.createHorizontalStrut(8));
            head.add(Tile.label(groups[g], BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().body + 1), BentoTheme.TEXT));
            head.add(Box.createHorizontalGlue());
            if (right != null)
            {
                head.add(new Chip().set(right, BentoTheme.WARN));
            }
            head.setMaximumSize(new Dimension(Integer.MAX_VALUE, head.getPreferredSize().height));
            head.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            card.add(head);
            card.gap(8);
            boolean first = true;
            for (Entry e : entries)
            {
                if (!e.group.equals(groups[g]) || e.view != View.ROOT)
                {
                    continue;
                }
                if (!first)
                {
                    card.gap(4);
                }
                JComponent c = e.factory.get();
                c.setAlignmentX(JComponent.LEFT_ALIGNMENT);
                card.add(c);
                first = false;
            }
            add(card);
        }
        JLabel foot = Tile.label("Each danger-zone action asks twice and offers a recovery export first.", BentoTheme.secondary(), BentoTheme.DIM);
        foot.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        content.add(foot);
    }

    private void renderView(ToolsSnapshot s, List<Entry> entries)
    {
        switch (view)
        {
            case REVIEW:
                renderReview(s);
                return;
            case RULES:
                renderRules(s);
                return;
            default:
                break;
        }
        Tile card = new Tile();
        boolean first = true;
        for (Entry e : entries)
        {
            if (e.view != view)
            {
                continue;
            }
            if (!first)
            {
                card.gap(4);
            }
            JComponent c = e.factory.get();
            c.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            card.add(c);
            first = false;
        }
        add(card);
        String note = viewNote(s);
        if (note != null)
        {
            JLabel foot = Tile.label(note, BentoTheme.secondary(), BentoTheme.DIM);
            foot.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            for (String line : Tile.wrap(note, foot.getFontMetrics(BentoTheme.secondary()), BentoTheme.MIN_CONTENT_WIDTH - 8))
            {
                JLabel l = Tile.label(line, BentoTheme.secondary(), BentoTheme.DIM);
                l.setAlignmentX(JComponent.LEFT_ALIGNMENT);
                content.add(l);
            }
        }
    }

    @Nullable
    private String viewNote(ToolsSnapshot s)
    {
        switch (view)
        {
            case LAYOUT:
                return "The net card, the stat strip and the ribbon stay put; the rest can be hidden or reordered.";
            case ALERTS:
                return "Alerts show as sidebar toasts; the HUD+ line follows the same switches.";
            case STORAGE:
                return "Compaction keeps every session's totals and folds old receipts into summaries. A backup is the whole profile as JSON.";
            case APPEARANCE:
                return "Reduced motion stops the net flash and the toast slide.";
            case PARTY:
                return s.party == null || !s.party.isInParty() ? "Join a RuneLite party to compare and split." : null;
            default:
                return null;
        }
    }

    // ── entries ──────────────────────────────────────────────────────────────────────────

    /** Every row on every page, so the overview, the sub-pages and search all read one list. */
    private List<Entry> entries(ToolsSnapshot s)
    {
        List<Entry> list = new ArrayList<>();
        int n = s.decisions.size();
        long now = System.currentTimeMillis();

        // Attention.
        list.add(new Entry("Attention", View.ROOT, "Review inbox",
            n == 0 ? "nothing pending" : n + (n == 1 ? " needs a decision" : " need a decision") + " · oldest " + Fmt.age(now - s.decisions.get(n - 1).at),
            "review decide uncertain pending correction", () -> nav(Icon.glyph("?", BentoTheme.WARN), "Review inbox",
                n == 0 ? "Nothing pending" : n + (n == 1 ? " needs a decision" : " need a decision") + " · oldest " + Fmt.age(now - s.decisions.get(n - 1).at),
                n == 0 ? null : Integer.toString(n), n == 0 ? NavRow.Tone.PLAIN : NavRow.Tone.WARN, () -> show(View.REVIEW))));
        Long shown = s.historyWealthTotal != null ? s.historyWealthTotal : s.wealthTotal;
        StringBuilder wsub = new StringBuilder();
        for (ToolsSnapshot.WealthChange c : s.wealthChanges)
        {
            if (c.available)
            {
                wsub.append(wsub.length() == 0 ? "" : " · ").append(c.label.toLowerCase(Locale.ROOT).replace("since ", "")).append(' ').append(Fmt.signed(c.total()));
            }
        }
        String wealthSub = wsub.length() == 0 ? (shown == null ? "not read yet" : "no bank visits to compare yet") : wsub.toString();
        list.add(new Entry("Attention", View.ROOT, "Wealth", wealthSub, "wealth bank locate insights", () ->
            nav(Icon.glyph("◉", BentoTheme.POSITIVE), "Wealth", wealthSub, shown == null ? "—" : Fmt.compact(shown), NavRow.Tone.PLAIN,
                actions::openInsightsWealth)));

        // Session.
        list.add(new Entry("Session", View.ROOT, "Slayer task boundary", settings.boundarySlayer.toString(), "automatic boundaries slayer task",
            () -> pickerRow("Slayer task", settings.boundarySlayer.toString(), () -> boundaryMenu("boundarySlayer", settings.boundarySlayer))));
        list.add(new Entry("Session", View.ROOT, "Raid boundary", settings.boundaryRaid.toString(), "automatic boundaries raid cox tob toa",
            () -> pickerRow("Raid", settings.boundaryRaid.toString(), () -> boundaryMenu("boundaryRaid", settings.boundaryRaid))));
        list.add(new Entry("Session", View.ROOT, "Bank visit boundary", settings.boundaryBank.toString(), "automatic boundaries bank trip",
            () -> pickerRow("Bank visit", settings.boundaryBank.toString(), () -> boundaryMenu("boundaryBank", settings.boundaryBank))));
        list.add(new Entry("Session", View.ROOT, "Alerts", "notable drop ≥ " + Fmt.compact(settings.notableDropGp)
            + (settings.alertGoalReached ? " · goal" : "") + (settings.sessionIdleAutoEndMinutes > 0 ? " · idle " + settings.sessionIdleAutoEndMinutes + "m" : ""),
            "alerts notable drop goal milestone toast idle",
            () -> nav(Icon.glyph("✦", BentoTheme.WARN), "Alerts", "Notable drop ≥ " + Fmt.compact(settings.notableDropGp)
                + (settings.alertGoalReached ? " · goal reached" : "") + (settings.sessionIdleAutoEndMinutes > 0 ? " · idle " + settings.sessionIdleAutoEndMinutes + "m" : ""),
                null, NavRow.Tone.PLAIN, () -> show(View.ALERTS))));
        list.add(new Entry("Session", View.ROOT, "Layout", s.hiddenLiveTiles.isEmpty() ? "all Live tiles shown" : s.hiddenLiveTiles.size() + " hidden",
            "layout live tiles order hide show", () -> nav(Icon.glyph("▥", BentoTheme.INFO), "Layout",
                s.hiddenLiveTiles.isEmpty() ? "All Live tiles shown" : s.hiddenLiveTiles.size() + " hidden", null, NavRow.Tone.PLAIN, () -> show(View.LAYOUT))));
        com.gpmanager.model.PartyProfitSummary p = s.party;
        String partySub = p == null || !p.isInParty() ? "not in a party" : p.getMemberCount() + (p.getMemberCount() == 1 ? " member" : " members")
            + (settings.partySharing ? " · sharing" : "");
        list.add(new Entry("Session", View.ROOT, "Party", partySub, "party share split hud members", () ->
            nav(Icon.glyph("⚑", BentoTheme.QUIET), "Party", partySub, null, NavRow.Tone.PLAIN, () -> show(View.PARTY))));

        // Data.
        ToolsSnapshot.Storage st = s.storage;
        String storageSub = (st.approximateBytes <= 0L ? "—" : bytes(st.approximateBytes)) + " · " + Fmt.exact(st.receipts) + " receipts · kept " + settings.retention;
        list.add(new Entry("Data", View.ROOT, "Storage", storageSub, "storage size receipts retention compact export csv backup profile clear history archive",
            () -> nav(Icon.glyph("▣", ORANGE), "Storage", storageSub, null, NavRow.Tone.PLAIN, () -> show(View.STORAGE))));
        String rulesSub = s.excludedItems.size() + " excluded · " + s.priceOverrides.size() + (s.priceOverrides.size() == 1 ? " override" : " overrides");
        list.add(new Entry("Data", View.ROOT, "Rules", rulesSub, "rules excluded items price overrides include clear",
            () -> nav(Icon.glyph("⚖", BentoTheme.MUTED), "Rules", rulesSub, null, NavRow.Tone.PLAIN, () -> show(View.RULES))));

        // Preferences.
        list.add(new Entry("Preferences", View.ROOT, "Appearance", settings.accent + " · " + settings.density + " · PvP " + settings.pvpMode,
            "appearance accent density pvp layout net graph reduced motion theme",
            () -> nav(Icon.glyph("◐", BentoTheme.accentColor()), "Appearance", settings.accent + " · " + settings.density + " · PvP " + settings.pvpMode,
                null, NavRow.Tone.PLAIN, () -> show(View.APPEARANCE))));
        list.add(new Entry("Preferences", View.ROOT, "Diagnostics", settings.debugTrace ? "debug trace on" : "plugin settings · report · about",
            "diagnostics plugin settings debug trace report shortcuts about version schema",
            () -> nav(Icon.glyph("⚙", BentoTheme.MUTED), "Diagnostics", settings.debugTrace ? "Debug trace on" : "Plugin settings · report · about",
                null, NavRow.Tone.PLAIN, () -> show(View.DIAGNOSTICS))));

        // Help.
        list.add(new Entry("Help", View.ROOT, "How tracking works", "supplies, loss, sessions and free play", "help principles how tracking works",
            () -> nav(Icon.glyph("?", BentoTheme.MUTED), "How tracking works", "Supplies, loss, sessions and free play", null, NavRow.Tone.PLAIN, actions::showHelp)));
        list.add(new Entry("Help", View.ROOT, "Shortcuts", "keys that move around the sidebar", "help shortcuts keyboard keys",
            () -> nav(Icon.glyph("⌘", BentoTheme.MUTED), "Shortcuts", "Keys that move around the sidebar", null, NavRow.Tone.PLAIN, actions::showShortcuts)));
        list.add(new Entry("Help", View.ROOT, "Report an issue", "copies the diagnostics report", "help report issue bug diagnostics",
            () -> nav(Icon.glyph("⚑", BentoTheme.MUTED), "Report an issue", "Copies the diagnostics report to paste", null, NavRow.Tone.PLAIN, actions::reportIssue)));

        // Danger zone.
        list.add(new Entry("Danger zone", View.ROOT, "Delete session…", "one finished session and its receipts", "danger delete session",
            () -> nav(Icon.glyph("✕", BentoTheme.NEGATIVE), "Delete session…", "One finished session and its receipts", null, NavRow.Tone.NEGATIVE,
                s.historySessions > 0 ? actions::deleteSession : null)));
        list.add(new Entry("Danger zone", View.ROOT, "Reset tracking…", "free play and the live session start over", "danger reset tracking",
            () -> nav(Icon.glyph("↺", BentoTheme.NEGATIVE), "Reset tracking…", "Free play and the live session start over", null, NavRow.Tone.NEGATIVE, actions::resetTracking)));
        list.add(new Entry("Danger zone", View.ROOT, "Factory reset…", "everything this profile has tracked", "danger factory reset wipe",
            () -> nav(Icon.glyph("☠", BentoTheme.NEGATIVE), "Factory reset…", "Everything this profile has tracked", null, NavRow.Tone.NEGATIVE, actions::factoryReset)));

        // Sub-page rows (also searchable).
        alertsEntries(list);
        layoutEntries(list, s);
        layoutResetEntry(list);
        partyEntries(list, s);
        storageEntries(list, s);
        appearanceEntries(list);
        diagnosticsEntries(list);
        return list;
    }

    private void alertsEntries(List<Entry> list)
    {
        list.add(new Entry("Session", View.ALERTS, "Notable drop over", Fmt.compact(settings.notableDropGp), "notable drop threshold toast",
            () -> pickerRow("Notable drop over", Fmt.compact(settings.notableDropGp), () ->
            {
                JPopupMenu menu = menu();
                for (long v : DROP_THRESHOLDS)
                {
                    menu.add(item((v == settings.notableDropGp ? "● " : "○ ") + Fmt.compact(v), () -> actions.setNotableDrop(v)));
                }
                return menu;
            })));
        list.add(new Entry("Session", View.ALERTS, "Goal reached", "toast · HUD", "goal reached alert toast hud",
            () -> toggleRow("Goal reached", "Toast and HUD+ line when the session goal is met", settings.alertGoalReached, actions::setAlertGoalReached)));
        list.add(new Entry("Session", View.ALERTS, "Wealth milestone every", settings.wealthMilestoneGp <= 0L ? "off" : Fmt.compact(settings.wealthMilestoneGp),
            "wealth milestone every", () -> pickerRow("Wealth milestone every", settings.wealthMilestoneGp <= 0L ? "off" : Fmt.compact(settings.wealthMilestoneGp), () ->
            {
                JPopupMenu menu = menu();
                for (long v : MILESTONES)
                {
                    menu.add(item((v == settings.wealthMilestoneGp ? "● " : "○ ") + (v == 0L ? "off" : Fmt.compact(v)), () -> actions.setWealthMilestone(v)));
                }
                return menu;
            })));
        list.add(new Entry("Session", View.ALERTS, "End session after idle", settings.sessionIdleAutoEndMinutes <= 0 ? "off" : settings.sessionIdleAutoEndMinutes + "m",
            "end session idle auto timeout minutes", () -> pickerRow("End session after idle",
                settings.sessionIdleAutoEndMinutes <= 0 ? "off" : settings.sessionIdleAutoEndMinutes + "m", () ->
            {
                JPopupMenu menu = menu();
                for (int m : IDLE_MINUTES)
                {
                    menu.add(item((m == settings.sessionIdleAutoEndMinutes ? "● " : "○ ") + (m == 0 ? "off" : m + "m"),
                        () -> actions.setSessionIdleAutoEnd(m)));
                }
                return menu;
            })));
    }

    private void layoutEntries(List<Entry> list, ToolsSnapshot s)
    {
        String[] ids = s.liveTileOrder.isEmpty() ? ToolsSnapshot.LIVE_TILES : s.liveTileOrder.toArray(new String[0]);
        for (int i = 0; i < ids.length; i++)
        {
            String id = ids[i];
            String label = tileLabel(id);
            boolean shownTile = !s.hiddenLiveTiles.contains(id);
            boolean canUp = i > 0;
            boolean canDown = i < ids.length - 1;
            list.add(new Entry("Session", View.LAYOUT, label, shownTile ? "shown" : "hidden", "layout live tile " + id + " order",
                () ->
                {
                    JPanel line = new JPanel();
                    line.setLayout(new BoxLayout(line, BoxLayout.X_AXIS));
                    line.setOpaque(false);
                    Controls.IconButton up = new Controls.IconButton("▲", "Move " + label + " up", false);
                    up.setEnabled(canUp);
                    up.onClick(() -> actions.moveLiveTile(id, true));
                    Controls.IconButton down = new Controls.IconButton("▼", "Move " + label + " down", false);
                    down.setEnabled(canDown);
                    down.onClick(() -> actions.moveLiveTile(id, false));
                    line.add(up);
                    line.add(down);
                    line.add(Box.createHorizontalStrut(6));
                    line.add(Tile.label(label, BentoTheme.body(), shownTile ? BentoTheme.TEXT : BentoTheme.DIM));
                    line.add(Box.createHorizontalGlue());
                    Toggle t = new Toggle(label, shownTile).onChange(on -> actions.setLiveTileHidden(id, !on));
                    line.add(t);
                    line.setMaximumSize(new Dimension(Integer.MAX_VALUE, line.getPreferredSize().height));
                    return line;
                }));
        }
    }

    private void layoutResetEntry(List<Entry> list)
    {
        list.add(new Entry("Session", View.LAYOUT, "Reset layout", "default order, everything shown", "layout reset default",
            () -> buttonsRow(ghost("Reset to default", actions::resetLayout))));
    }

    private static String tileLabel(String id)
    {
        switch (id)
        {
            case "party":
                return "Party";
            case "notices":
                return "Notices";
            case "recent":
                return "Recent";
            case "goal":
                return "Goal";
            default:
                return id;
        }
    }

    private void partyEntries(List<Entry> list, ToolsSnapshot s)
    {
        com.gpmanager.model.PartyProfitSummary p = s.party;
        list.add(new Entry("Session", View.PARTY, "Share my figures", null, "party share figures",
            () -> toggleRow("Share my figures", "Your net and rate go to the party", settings.partySharing, actions::setPartySharing)));
        list.add(new Entry("Session", View.PARTY, "Party in HUD", null, "party hud overlay",
            () -> toggleRow("Party in HUD", "Members' figures under the HUD+ card", settings.partyHud, actions::setPartyHud)));
        if (p != null && p.isInParty())
        {
            for (com.gpmanager.model.PartyProfitSummary.Member m : p.getMembers())
            {
                String name = m.getDisplayName() + (m.isLocal() ? " (you)" : "");
                String value;
                Color color;
                if (!m.isReporting())
                {
                    value = "not sharing";
                    color = BentoTheme.DIM;
                }
                else
                {
                    value = Fmt.signed(m.getNet()) + (m.hasReportedRate() ? " · " + Fmt.rate(m.getProfitPerHour()) + "/h" : "") + (m.isFreshReport() ? "" : " · stale");
                    color = m.isFreshReport() ? BentoTheme.signColor(m.getNet()) : BentoTheme.DIM;
                }
                list.add(new Entry("Session", View.PARTY, name, value, "party member " + name,
                    () -> Tile.line(Tile.label(name, BentoTheme.body(), BentoTheme.TEXT), Tile.label(value, BentoTheme.bodyBold(), color))));
            }
            list.add(new Entry("Session", View.PARTY, "Split", "even split of the party's net", "party split copy",
                () -> buttonsRow(button("Split ▾", actions::showSplit), button("Copy party card", actions::copyPartyCard))));
        }
    }

    private void storageEntries(List<Entry> list, ToolsSnapshot s)
    {
        ToolsSnapshot.Storage st = s.storage;
        list.add(new Entry("Data", View.STORAGE, "Profile", st.sessions + (st.sessions == 1 ? " session" : " sessions") + " + free play", "storage profile sessions",
            () -> kv("Profile", st.sessions + (st.sessions == 1 ? " session" : " sessions") + " + free play", BentoTheme.TEXT)));
        list.add(new Entry("Data", View.STORAGE, "Size", bytes(st.approximateBytes), "storage size bytes receipts compacted",
            () -> kv("Size", (st.approximateBytes <= 0L ? "—" : bytes(st.approximateBytes)) + " · " + Fmt.exact(st.receipts) + " receipts"
                + (st.compacted > 0L ? " · " + Fmt.exact(st.compacted) + " compacted" : ""), BentoTheme.MUTED)));
        list.add(new Entry("Data", View.STORAGE, "Receipts kept", settings.retention.toString(), "storage retention receipts kept days forever",
            () -> pickerRow("Receipts kept", settings.retention.toString(), () ->
            {
                JPopupMenu menu = menu();
                for (com.gpmanager.ReceiptRetentionPeriod r : com.gpmanager.ReceiptRetentionPeriod.values())
                {
                    menu.add(item((r == settings.retention ? "● " : "○ ") + r, () -> actions.setRetention(r)));
                }
                return menu;
            })));
        if (st.retentionDays > 0)
        {
            String when = st.nextCompactionAt == null ? "next day change" : "in " + Fmt.duration(Math.max(0L, st.nextCompactionAt - System.currentTimeMillis()));
            list.add(new Entry("Data", View.STORAGE, "Next compaction", when, "storage compaction pending",
                () -> kv("Next compaction", (st.pendingCompaction > 0 ? st.pendingCompaction + " pending · " : "") + when, BentoTheme.DIM)));
        }
        list.add(new Entry("Data", View.STORAGE, "Compact older now", "fold old receipts into summaries", "storage compact now",
            () ->
            {
                Controls.Button b = button("Compact older now", actions::compactOlderNow);
                b.setEnabled(st.retentionDays > 0 && st.pendingCompaction > 0);
                return buttonsRow(b);
            }));
        list.add(new Entry("Data", View.STORAGE, "Export CSV", "this session or a finished one", "storage export csv session history",
            () ->
            {
                Controls.Button hist = button("History…", actions::exportHistoryCsv);
                hist.setEnabled(s.historySessions > 0);
                return buttonsRow(button("Export session CSV", actions::exportSessionCsv), hist);
            }));
        list.add(new Entry("Data", View.STORAGE, "Backup profile", "the whole profile as JSON, dated, in the export folder", "storage backup profile json export",
            () -> nav(Icon.glyph("⇪", BentoTheme.INFO), "Backup profile", "The whole profile as JSON, dated, in the export folder", null, NavRow.Tone.PLAIN, actions::backupProfile)));
        list.add(new Entry("Data", View.STORAGE, "Restore from backup", "checked first; a recovery export is written before anything changes", "storage restore backup import json",
            () -> nav(Icon.glyph("↺", BentoTheme.WARN), "Restore backup…", "Checked first; a recovery export is written before anything changes", null, NavRow.Tone.PLAIN, actions::restoreProfile)));
        list.add(new Entry("Data", View.STORAGE, "Open export folder", "CSVs and backups", "storage export folder open",
            () -> buttonsRow(button("Open export folder", actions::openExportFolder), button("Copy data folder path", actions::copyDataPath))));
        list.add(new Entry("Data", View.STORAGE, "Copy as image", "the sidebar as a picture", "storage copy image",
            () -> buttonsRow(button("Copy as image", actions::copyAsImage))));
        list.add(new Entry("Data", View.STORAGE, "Restore last undo", "bring back the last receipt you removed", "storage restore undo",
            () -> buttonsRow(button("Restore last undo", actions::restoreLastUndo))));
        list.add(new Entry("Data", View.STORAGE, "Clear history…", "finished sessions go; free play stays", "storage clear history",
            () ->
            {
                Controls.Button clear = ghost("Clear history…", actions::clearCompletedHistory);
                clear.setEnabled(s.historySessions > 0);
                return buttonsRow(clear, ghost("Archive free play…", actions::restartOverall));
            }));
    }

    private void appearanceEntries(List<Entry> list)
    {
        list.add(new Entry("Preferences", View.APPEARANCE, "Accent", settings.accent.toString(), "appearance accent colour color swatch",
            () ->
            {
                JPanel line = new JPanel();
                line.setLayout(new BoxLayout(line, BoxLayout.X_AXIS));
                line.setOpaque(false);
                line.add(Tile.label("Accent", BentoTheme.body(), BentoTheme.TEXT));
                line.add(Box.createHorizontalGlue());
                for (BentoTheme.Accent a : BentoTheme.Accent.values())
                {
                    line.add(swatch(a));
                    line.add(Box.createHorizontalStrut(4));
                }
                line.setMaximumSize(new Dimension(Integer.MAX_VALUE, line.getPreferredSize().height));
                return line;
            }));
        list.add(new Entry("Preferences", View.APPEARANCE, "PvP layout", settings.pvpMode.toString(), "appearance pvp layout auto always never wilderness",
            () -> pickerRow("PvP layout", settings.pvpMode.toString(), () ->
            {
                JPopupMenu menu = menu();
                for (PvpMode m : PvpMode.values())
                {
                    menu.add(item((m == settings.pvpMode ? "● " : "○ ") + m, () -> actions.setPvpMode(m)));
                }
                return menu;
            })));
        list.add(new Entry("Preferences", View.APPEARANCE, "Net graph", settings.netGraph ? "on" : "off", "appearance net graph sparkline",
            () -> toggleRow("Net graph", "Sparkline beside the Live figure", settings.netGraph, actions::setNetGraph)));
        list.add(new Entry("Preferences", View.APPEARANCE, "Exact figures", settings.exactFigures ? "on" : "off", "appearance exact figures full number compact",
            () -> toggleRow("Exact figures", "−6,544 on the net card instead of −6.5k", settings.exactFigures, actions::setExactFigures)));
        list.add(new Entry("Preferences", View.APPEARANCE, "Reduced motion", settings.reducedMotion ? "on" : "off", "appearance reduced motion animation",
            () -> toggleRow("Reduced motion", "No flashes or slides", settings.reducedMotion, actions::setReducedMotion)));
    }

    private void diagnosticsEntries(List<Entry> list)
    {
        list.add(new Entry("Preferences", View.DIAGNOSTICS, "Plugin settings", "the RuneLite configuration panel", "diagnostics plugin settings runelite config",
            () -> nav(Icon.glyph("⚙", BentoTheme.MUTED), "Plugin settings", "The RuneLite configuration panel", null, NavRow.Tone.PLAIN, actions::openPluginSettings)));
        list.add(new Entry("Preferences", View.DIAGNOSTICS, "Debug trace", settings.debugTrace ? "on" : "off", "diagnostics debug trace log",
            () -> toggleRow("Debug trace", "Verbose engine log for a bug report", settings.debugTrace, actions::setDebugTrace)));
        list.add(new Entry("Preferences", View.DIAGNOSTICS, "Diagnostics report", "copy to the clipboard", "diagnostics report copy clipboard",
            () -> nav(Icon.glyph("⎘", BentoTheme.MUTED), "Diagnostics report", "Copy to the clipboard", null, NavRow.Tone.PLAIN, actions::copyDiagnostics)));
        list.add(new Entry("Preferences", View.DIAGNOSTICS, "Shortcuts", null, "diagnostics shortcuts keyboard",
            () -> nav(Icon.glyph("⌘", BentoTheme.MUTED), "Shortcuts", "Keys that move around the sidebar", null, NavRow.Tone.PLAIN, actions::showShortcuts)));
        list.add(new Entry("Preferences", View.DIAGNOSTICS, "About", settings.about, "diagnostics about version schema runelite build",
            () -> kv("About", settings.about.isEmpty() ? "GP Manager" : settings.about, BentoTheme.MUTED)));
        if (last != null && last.dataHealth != null)
        {
            com.gpmanager.model.DataHealthSnapshot h = last.dataHealth;
            int unavailable = 0;
            for (Integer n : h.getUnavailableDays().values()) unavailable += n == null ? 0 : n;
            String health = (h.getRebuiltDays() > 0 ? h.getRebuiltDays() + " repaired days · " : "")
                + (h.getUnknownFieldBags() > 0 ? h.getUnknownFieldBags() + " newer-build fields kept · " : "")
                + (unavailable > 0 ? unavailable + " day-dimensions unavailable" : "all day dimensions covered");
            list.add(new Entry("Preferences", View.DIAGNOSTICS, "Data health", health, "diagnostics data health rebuilt repaired coverage",
                () -> nav(null, "Data health", health, null, NavRow.Tone.PLAIN, null)));
            if (!last.recoveredFrom.isEmpty())
            {
                String note = "Loaded from " + last.recoveredFrom + " · the primary save was unreadable";
                list.add(new Entry("Preferences", View.DIAGNOSTICS, "Recovered save", note, "diagnostics recovered backup",
                    () -> nav(null, "Recovered save", note, null, NavRow.Tone.WARN, null)));
            }
        }
        // The raw offer transitions the client reported: "reported" next to what you collected
        // decides whether a sell's coins come back before or after the 2% tax.
        List<com.gpmanager.model.GeOfferObservation> ge = last == null ? java.util.Collections.emptyList() : last.geObservations;
        for (int i = 0; i < ge.size(); i++)
        {
            com.gpmanager.model.GeOfferObservation o = ge.get(i);
            String state = o.getState().replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
            String title = (i == 0 ? "Last GE offers · " : "") + state + " · "
                + (o.getQuantityTradedDelta() > 0 ? o.getQuantityTradedDelta() + " × " : "") + o.getItemName();
            String sub = "slot " + (o.getSlot() + 1) + (o.getQuantityTradedDelta() > 0
                ? " · reported " + Fmt.exact(o.getSpentDelta()) + " gp · listed " + Fmt.exact(o.getListedValueForDelta()) + " gp"
                : " · " + o.getQuantityTraded() + " / " + o.getTotalQuantity() + " at " + Fmt.exact(o.getListedPrice()) + " gp");
            list.add(new Entry("Preferences", View.DIAGNOSTICS, title, sub, "diagnostics grand exchange offer reported spent tax",
                () -> nav(Icon.glyph("⇄", BentoTheme.MUTED), title, sub, null, NavRow.Tone.PLAIN, null)));
        }
    }

    // ── Review sub-page ──────────────────────────────────────────────────────────────────

    private void renderReview(ToolsSnapshot s)
    {
        int n = s.decisions.size();
        Tile head = new Tile();
        if (n > 0)
        {
            head.borderColor(BentoTheme.WARN_BORDER);
        }
        head.header(n == 0 ? "Nothing pending" : n + (n == 1 ? " needs a decision" : " need a decision"),
            n == 0 ? null : "oldest " + Fmt.age(System.currentTimeMillis() - s.decisions.get(n - 1).at), BentoTheme.DIM);
        if (n > 0)
        {
            Controls.Button all = new Controls.Button("Decide all ▾", Controls.Button.Kind.PRIMARY);
            all.onClick(() ->
            {
                JPopupMenu menu = menu();
                menu.add(item("● Count all as gain", () -> actions.decideAll(TransactionCorrection.REVENUE)));
                menu.add(item("● Count all as cost", () -> actions.decideAll(TransactionCorrection.COST)));
                menu.add(item("● Mark all as transfer", () -> actions.decideAll(TransactionCorrection.TRANSFER)));
                menu.add(item("● Ignore all", () -> actions.decideAll(TransactionCorrection.IGNORE)));
                menu.show(all, 0, all.getHeight());
            });
            String lead = StatBlock.fit("One call for every row below", head.getFontMetrics(BentoTheme.secondary()),
                Tile.interiorWidth() - all.getPreferredSize().width - 10);
            head.row(Tile.line(Tile.label(lead, BentoTheme.secondary(), BentoTheme.MUTED), all));
            all.setToolTipText("One call for every row; a single Undo last reverts the whole batch");
        }
        else
        {
            head.text("The Ledger's review filter is empty too.", BentoTheme.DIM);
        }
        add(head);
        for (ToolsSnapshot.Decision d : s.decisions)
        {
            Tile card = new Tile();
            ItemRow row = new ItemRow().sprite(d.itemId < 0 ? null : sprites.apply(d.itemId)).name(d.name, null)
                .verb(Fmt.age(System.currentTimeMillis() - d.at)).value(Fmt.signed(d.value), BentoTheme.signColor(d.value))
                .tag("?", BentoTheme.WARN);
            row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            card.add(row);
            Controls.Button decide = new Controls.Button("Decide ▾", Controls.Button.Kind.DEFAULT);
            decide.onClick(() -> decideMenu(decide, d));
            java.awt.FontMetrics fm = card.getFontMetrics(BentoTheme.secondary());
            String why = StatBlock.fit(d.why.isEmpty() ? "automatic call was uncertain" : d.why, fm,
                Tile.interiorWidth() - decide.getPreferredSize().width - 12);
            JLabel whyLabel = Tile.label(why, BentoTheme.secondary(), BentoTheme.DIM);
            whyLabel.setToolTipText(d.why);
            card.row(Tile.line(whyLabel, decide));
            add(card);
        }
        if (!s.applied.isEmpty())
        {
            Tile applied = new Tile().section(Icon.glyph("↶", BentoTheme.MUTED), "Applied", s.applied.size() + " recent", null);
            int shown = 0;
            for (ToolsSnapshot.Applied a : s.applied)
            {
                if (shown++ == 6)
                {
                    break;
                }
                applied.kv(a.change, Fmt.age(System.currentTimeMillis() - a.at), BentoTheme.DIM);
            }
            Controls.Button undo = new Controls.Button("Undo last", Controls.Button.Kind.DEFAULT);
            undo.setEnabled(s.canUndo);
            undo.onClick(actions::undoLastCorrection);
            applied.gap(4).row(Tile.buttons(undo));
            add(applied);
        }
    }

    private void decideMenu(JComponent anchor, ToolsSnapshot.Decision d)
    {
        JPopupMenu menu = menu();
        menu.add(item("● Count as gain", () -> actions.decide(d, TransactionCorrection.REVENUE)));
        menu.add(item("● Count as cost", () -> actions.decide(d, TransactionCorrection.COST)));
        menu.add(item("● Mark as transfer", () -> actions.decide(d, TransactionCorrection.TRANSFER)));
        menu.add(item("● Ignore", () -> actions.decide(d, TransactionCorrection.IGNORE)));
        menu.show(anchor, 0, anchor.getHeight());
    }

    // ── Rules sub-page ───────────────────────────────────────────────────────────────────

    private void renderRules(ToolsSnapshot s)
    {
        JTextField filter = new JTextField(rulesFilter);
        filter.setFont(BentoTheme.body());
        filter.setForeground(BentoTheme.TEXT);
        filter.setCaretColor(BentoTheme.TEXT);
        filter.setBackground(BentoTheme.SURFACE);
        filter.setToolTipText("Filter items");
        filter.setBorder(javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createLineBorder(BentoTheme.BORDER),
            javax.swing.BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
        {
            private void changed()
            {
                rulesFilter = filter.getText() == null ? "" : filter.getText().trim().toLowerCase(Locale.ROOT);
                javax.swing.SwingUtilities.invokeLater(() ->
                {
                    render();
                    for (java.awt.Component c : content.getComponents())
                    {
                        if (c instanceof JTextField)
                        {
                            c.requestFocusInWindow();
                            ((JTextField) c).setCaretPosition(((JTextField) c).getText().length());
                        }
                    }
                });
            }

            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e)
            {
                changed();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e)
            {
                changed();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e)
            {
                changed();
            }
        });
        filter.setMaximumSize(new Dimension(Integer.MAX_VALUE, filter.getPreferredSize().height));
        filter.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        content.add(filter);
        content.add(Box.createVerticalStrut(BentoTheme.density().gap));

        Tile excluded = new Tile().section(Icon.glyph("⊘", BentoTheme.MUTED), "Excluded items", s.excludedItems.size() + " excluded", null);
        int shown = 0;
        for (int id : s.excludedItems)
        {
            String name = actions.itemName(id);
            if (!rulesFilter.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(rulesFilter))
            {
                continue;
            }
            ItemRow row = new ItemRow().card(true).sprite(sprites.apply(id)).name(name, null).verb("excluded")
                .value("include", BentoTheme.accentColor());
            row.onClick(() -> actions.includeItem(id));
            row.setToolTipText("Click to count this item again");
            row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            excluded.gap(4);
            excluded.add(row);
            shown++;
        }
        if (shown == 0)
        {
            excluded.text(s.excludedItems.isEmpty() ? "Nothing excluded. Exclude an item from its sheet in the Ledger." : "No excluded item matches.", BentoTheme.DIM);
        }
        add(excluded);

        Tile overrides = new Tile().section(Icon.glyph("◉", BentoTheme.WARN), "Price overrides", s.priceOverrides.size() + (s.priceOverrides.size() == 1 ? " override" : " overrides"), null);
        shown = 0;
        for (java.util.Map.Entry<Integer, Integer> e : s.priceOverrides.entrySet())
        {
            int id = e.getKey();
            String name = actions.itemName(id);
            if (!rulesFilter.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(rulesFilter))
            {
                continue;
            }
            ItemRow row = new ItemRow().card(true).sprite(sprites.apply(id)).name(name, null)
                .verb(Fmt.exact(e.getValue()) + " gp").value("clear", BentoTheme.accentColor());
            row.onClick(() -> actions.clearOverride(id));
            row.setToolTipText("Click to return to market price");
            row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            overrides.gap(4);
            overrides.add(row);
            shown++;
        }
        if (shown == 0)
        {
            overrides.text(s.priceOverrides.isEmpty() ? "Every item uses the market price." : "No override matches.", BentoTheme.DIM);
        }
        overrides.gap(6).row(Tile.buttons(button("＋ Add an override…", actions::addOverride)));
        add(overrides);

        Tile ge = new Tile().section(null, "Grand Exchange", null, null);
        ge.add(toggleRow("Book trades from offers", "Settle buys and sells from the offer slots, not inventory changes",
            settings.geBookingObserved, actions::setGeBookingObserved));
        ge.gap(4);
        ge.add(toggleRow("Sell coins are after tax", "Off if Diagnostics shows reported = listed (gross)",
            settings.geSellSpentIsNet, actions::setGeSellSpentIsNet));
        ge.setToolTipText("Off by default until a live sale confirms the reported coins: compare Tools › Diagnostics › Last GE offers with what you collected");
        add(ge);
    }

    // ── row kit ──────────────────────────────────────────────────────────────────────────

    private static NavRow nav(Icon icon, String title, @Nullable String subtitle, @Nullable String right, NavRow.Tone tone, @Nullable Runnable onClick)
    {
        NavRow row = new NavRow().icon(icon).text(title, subtitle).tone(tone).chevron(onClick != null);
        if (right != null)
        {
            row.right(right, tone == NavRow.Tone.WARN ? BentoTheme.WARN : BentoTheme.TEXT);
        }
        row.onClick(onClick);
        if (onClick == null)
        {
            row.setEnabled(false);
        }
        return row;
    }

    private JPanel toggleRow(String label, @Nullable String hint, boolean on, java.util.function.Consumer<Boolean> onChange)
    {
        JPanel line = new JPanel();
        line.setLayout(new BoxLayout(line, BoxLayout.X_AXIS));
        line.setOpaque(false);
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setOpaque(false);
        JLabel l = Tile.label(label, BentoTheme.body(), BentoTheme.TEXT);
        l.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        col.add(l);
        if (hint != null && !hint.isEmpty())
        {
            JLabel h = Tile.label(StatBlock.fit(hint, l.getFontMetrics(BentoTheme.secondary()), Tile.interiorWidth() - 52), BentoTheme.secondary(), BentoTheme.MUTED);
            h.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            col.add(h);
        }
        col.setAlignmentY(JComponent.CENTER_ALIGNMENT);
        line.add(col);
        line.add(Box.createHorizontalGlue());
        Toggle t = new Toggle(label, on).onChange(onChange);
        t.setEnabled(settings.canWriteConfig);
        t.setAlignmentY(JComponent.CENTER_ALIGNMENT);
        line.add(t);
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, line.getPreferredSize().height));
        return line;
    }

    private JPanel pickerRow(String label, String value, Supplier<JPopupMenu> menu)
    {
        Controls.Button pick = new Controls.Button(value + " ▾", Controls.Button.Kind.DEFAULT);
        pick.onClick(() -> menu.get().show(pick, 0, pick.getHeight()));
        pick.setEnabled(settings.canWriteConfig);
        return Tile.line(Tile.label(label, BentoTheme.body(), BentoTheme.TEXT), pick);
    }

    private JPopupMenu boundaryMenu(String key, BoundaryMode current)
    {
        JPopupMenu menu = menu();
        for (BoundaryMode m : BoundaryMode.values())
        {
            menu.add(item((m == current ? "● " : "○ ") + m, () -> actions.setBoundary(key, m)));
        }
        return menu;
    }

    private JComponent swatch(BentoTheme.Accent a)
    {
        boolean picked = a == settings.accent;
        JComponent dot = new JComponent()
        {
            @Override
            public Dimension getPreferredSize()
            {
                return new Dimension(22, 22);
            }

            @Override
            public Dimension getMaximumSize()
            {
                return getPreferredSize();
            }

            @Override
            protected void paintComponent(java.awt.Graphics g)
            {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                try
                {
                    BentoTheme.quality(g2);
                    g2.setColor(a.color);
                    g2.fillOval(3, 3, 16, 16);
                    if (picked)
                    {
                        g2.setColor(BentoTheme.TEXT);
                        g2.setStroke(new java.awt.BasicStroke(2f));
                        g2.drawOval(1, 1, 20, 20);
                    }
                }
                finally
                {
                    g2.dispose();
                }
            }
        };
        dot.setToolTipText(a.toString());
        dot.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        dot.addMouseListener(new java.awt.event.MouseAdapter()
        {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e)
            {
                actions.setAccent(a);
            }
        });
        return dot;
    }

    private static JPanel kv(String key, String value, Color color)
    {
        JLabel k = Tile.label(key, BentoTheme.body(), BentoTheme.TEXT);
        int avail = Tile.interiorWidth() - k.getPreferredSize().width - 12;
        JLabel v = Tile.label(StatBlock.fit(value, k.getFontMetrics(BentoTheme.secondary()), Math.max(30, avail)), BentoTheme.secondary(), color);
        v.setToolTipText(value);
        return Tile.line(k, v);
    }

    private static Controls.Button button(String text, Runnable action)
    {
        return new Controls.Button(text, Controls.Button.Kind.DEFAULT).onClick(action);
    }

    private static Controls.Button ghost(String text, Runnable action)
    {
        return new Controls.Button(text, Controls.Button.Kind.GHOST).onClick(action);
    }

    private static JPanel buttonsRow(java.awt.Component... buttons)
    {
        JPanel p = Tile.buttons(buttons);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    private static String bytes(long b)
    {
        if (b <= 0L)
        {
            return "—";
        }
        if (b < 1_024L)
        {
            return b + " B";
        }
        if (b < 1_048_576L)
        {
            return String.format(Locale.ROOT, "%.0f KB", b / 1_024d);
        }
        return String.format(Locale.ROOT, "%.1f MB", b / 1_048_576d);
    }

    private static JPopupMenu menu()
    {
        JPopupMenu menu = new JPopupMenu();
        menu.setBackground(BentoTheme.ALT);
        menu.setBorder(javax.swing.BorderFactory.createLineBorder(BentoTheme.BORDER));
        return menu;
    }

    private static JMenuItem item(String text, Runnable action)
    {
        JMenuItem item = new JMenuItem(text);
        item.setFont(BentoTheme.body());
        item.setBackground(BentoTheme.ALT);
        item.setForeground(BentoTheme.TEXT);
        item.addActionListener(e -> action.run());
        return item;
    }

    private void add(JComponent c)
    {
        c.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        content.add(c);
        content.add(Box.createVerticalStrut(BentoTheme.density().gap));
    }
}
