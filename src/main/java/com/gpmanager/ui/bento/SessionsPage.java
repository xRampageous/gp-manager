package com.gpmanager.ui.bento;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.Nullable;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * Sessions (SIDEBAR_BENTO.md §5, §13.3): the current card (a session or free play, with
 * Duration · Total time · Profit/Loss), then Previous sessions as cards — icon, name, category ·
 * activity, day and time, duration, key stat, net, ★ — most recent first, or the full history
 * grouped by day with same-name folds behind "All sessions ›". ⚖ Compare picks two.
 * One level only: nothing lives inside a session.
 */
public final class SessionsPage implements BentoShell.Page
{
    public interface Actions
    {
        void startSession();

        void endSession();

        void startAgain(String name);

        void openLedgerFor(String sessionId, String name);

        void compare(SessionsSnapshot.Statement left, SessionsSnapshot.Statement right);

        void sessionMenu(JComponent anchor, SessionsSnapshot.SessionRow session);

        void favoritesToggled(boolean favoritesOnly);

        void toggleFavorite(SessionsSnapshot.SessionRow session);

        void openSearch();

        /** Compare this session with the previous one of the same name. */
        void compareWithPrevious(SessionsSnapshot.SessionRow session);

        /** Compare this session with one the owner picks. */
        void compareWith(SessionsSnapshot.SessionRow session);

        /** A one-line summary to the clipboard. */
        void copySummary(SessionsSnapshot.SessionRow session);
    }

    /** How many rows the recent list shows before "All sessions ›". */
    static final int RECENT_ROWS = 8;

    private final Actions actions;
    private final Function<Integer, BufferedImage> sprites;
    private final JPanel bar = new JPanel();
    private final Controls.Button compareButton = new Controls.Button("Compare", Controls.Button.Kind.GHOST);
    private final Controls.IconButton cancelPick = new Controls.IconButton("✕", "Stop picking", true);
    private final Controls.IconButton favorites = new Controls.IconButton("★", "Favourites only", true);
    private final JPanel body = BentoShell.stack();
    private final JPanel content = new JPanel();
    private final JPanel footer = new JPanel();
    private final Controls.Button footerCancel = new Controls.Button("Cancel", Controls.Button.Kind.GHOST);

    private final Map<String, SessionsSnapshot.Statement> selected = new LinkedHashMap<>();
    private final Set<String> expandedSessions = new java.util.HashSet<>();
    private final Set<String> openFolds = new java.util.HashSet<>();
    private final Set<String> openGroups = new java.util.HashSet<>();
    private boolean favoritesOnly;
    private boolean picking;
    private boolean showAll;
    @Nullable
    private SessionsSnapshot last;

    public SessionsPage(Actions actions, Function<Integer, BufferedImage> sprites)
    {
        this.actions = actions;
        this.sprites = sprites == null ? id -> null : sprites;
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setOpaque(false);
        JPanel titles = new JPanel();
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titles.setOpaque(false);
        JLabel title = Tile.label("Sessions", BentoTheme.font(java.awt.Font.BOLD, 13f), BentoTheme.TEXT);
        title.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        titles.add(title);
        titles.setAlignmentY(JComponent.CENTER_ALIGNMENT);
        bar.add(titles);
        bar.add(Box.createHorizontalGlue());
        compareButton.setAlignmentY(JComponent.CENTER_ALIGNMENT);
        favorites.setAlignmentY(JComponent.CENTER_ALIGNMENT);
        Controls.IconButton search = new Controls.IconButton("⌕", "Search items, sessions and settings", true);
        search.setAlignmentY(JComponent.CENTER_ALIGNMENT);
        search.onClick(actions::openSearch);
        bar.add(search);
        bar.add(Box.createHorizontalStrut(4));
        compareButton.setToolTipText("Pick two sessions to compare");
        compareButton.onClick(this::compareClicked);
        bar.add(compareButton);
        cancelPick.setAlignmentY(JComponent.CENTER_ALIGNMENT);
        cancelPick.setVisible(false);
        cancelPick.onClick(() ->
        {
            picking = false;
            selected.clear();
            reapply();
        });
        bar.add(Box.createHorizontalStrut(2));
        bar.add(cancelPick);
        bar.add(Box.createHorizontalStrut(4));
        favorites.onClick(() ->
        {
            favoritesOnly = !favoritesOnly;
            favorites.setOn(favoritesOnly);
            actions.favoritesToggled(favoritesOnly);
        });
        bar.add(favorites);
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, bar.getPreferredSize().height));

        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        BentoShell.stackAdd(body, content);

        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        footer.setOpaque(false);
        footerCancel.onClick(() ->
        {
            picking = false;
            selected.clear();
            reapply();
        });
        BentoShell.stackAdd(body, footer);
    }

    @Override
    public String id()
    {
        return BentoShell.SESSIONS;
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

    public boolean favoritesOnly()
    {
        return favoritesOnly;
    }

    private void compareClicked()
    {
        List<SessionsSnapshot.Statement> two = new ArrayList<>(selected.values());
        if (two.size() == 2)
        {
            actions.compare(two.get(0), two.get(1));
            return;
        }
        if (!picking)
        {
            picking = true;
            reapply();
        }
    }

    private void reapply()
    {
        if (last != null)
        {
            apply(last);
        }
    }

    public void apply(SessionsSnapshot s)
    {
        last = s;
        content.removeAll();

        content.add(currentCard(s));
        content.add(Box.createVerticalStrut(BentoTheme.density().gap + 2));

        // Previous sessions: the most recent few as cards, or every day grouped behind All sessions ›.
        JPanel head = new JPanel();
        head.setLayout(new BoxLayout(head, BoxLayout.X_AXIS));
        head.setOpaque(false);
        head.add(Tile.label(showAll ? "All sessions" : "Previous sessions", BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().body), BentoTheme.TEXT));
        head.add(Box.createHorizontalGlue());
        int total = 0;
        for (SessionsSnapshot.Group g : s.groups)
        {
            total += g.sessionCount;
        }
        if (total > 0)
        {
            Controls.Button more = new Controls.Button(showAll ? "Recent ›" : "All sessions ›", Controls.Button.Kind.GHOST);
            more.setToolTipText(showAll ? "Back to the most recent sessions" : total + (total == 1 ? " session" : " sessions") + " grouped by day");
            more.onClick(() ->
            {
                showAll = !showAll;
                reapply();
            });
            more.setPreferredSize(new Dimension(more.getPreferredSize().width - 4, 18));
            head.add(more);
        }
        head.setMaximumSize(new Dimension(Integer.MAX_VALUE, head.getPreferredSize().height));
        head.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        content.add(head);
        content.add(Box.createVerticalStrut(6));

        if (s.groups.isEmpty())
        {
            ListBlock empty = new ListBlock().empty(favoritesOnly ? "No favourites yet" : "No finished sessions yet");
            empty.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            content.add(empty);
        }
        else if (showAll)
        {
            for (SessionsSnapshot.Group g : s.groups)
            {
                boolean open = g.recent || openGroups.contains(g.label);
                content.add(groupHeader(g, open));
                if (open)
                {
                    addFolds(g, g.folds);
                }
                content.add(Box.createVerticalStrut(BentoTheme.density().gap));
            }
        }
        else
        {
            int shown = 0;
            for (SessionsSnapshot.Group g : s.groups)
            {
                List<SessionsSnapshot.Fold> slice = new ArrayList<>();
                for (SessionsSnapshot.Fold f : g.folds)
                {
                    if (shown >= RECENT_ROWS)
                    {
                        break;
                    }
                    slice.add(f);
                    shown++;
                }
                addFolds(g, slice);
                if (shown >= RECENT_ROWS)
                {
                    break;
                }
            }
        }
        refreshFooter();
        content.revalidate();
        content.repaint();
    }

    private void addFolds(SessionsSnapshot.Group g, List<SessionsSnapshot.Fold> folds)
    {
        for (SessionsSnapshot.Fold f : folds)
        {
            if (f.sessions.size() == 1)
            {
                content.add(sessionCard(f.first(), 0));
            }
            else
            {
                String key = g.label + "|" + f.name + "|" + f.first().id;
                boolean open = openFolds.contains(key);
                content.add(foldCard(f, key, open));
                if (open)
                {
                    for (SessionsSnapshot.SessionRow row : f.sessions)
                    {
                        content.add(Box.createVerticalStrut(4));
                        content.add(sessionCard(row, 12));
                    }
                }
            }
            content.add(Box.createVerticalStrut(6));
        }
    }

    /** ● Current session / ● Free play: icon · name · category · rate; Duration · Total time · Profit/Loss; End / Start. */
    private JComponent currentCard(SessionsSnapshot s)
    {
        Tile card = new Tile();
        boolean inSession = s.active != null && !s.active.freePlay;
        // The current card wears the sign of its net: mint while up, red while down (§13 reference).
        java.awt.Color tone = s.active == null ? BentoTheme.MUTED : s.active.net < 0L ? BentoTheme.NEGATIVE : BentoTheme.accentColor();
        if (inSession)
        {
            card.borderColor(BentoTheme.withAlpha(tone, 150));
        }
        if (s.active == null)
        {
            card.header("Sessions", null, null);
            card.text("Waiting for your first change", BentoTheme.DIM);
            card.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            return card;
        }
        SessionsSnapshot.SessionRow a = s.active;

        JPanel status = new JPanel();
        status.setLayout(new BoxLayout(status, BoxLayout.X_AXIS));
        status.setOpaque(false);
        JLabel dot = Tile.label("●", BentoTheme.symbol(BentoTheme.density().micro), inSession ? tone : BentoTheme.MUTED);
        status.add(dot);
        status.add(Box.createHorizontalStrut(5));
        status.add(Tile.label(inSession ? "Current session" : "Free play", BentoTheme.secondary(), inSession ? tone : BentoTheme.MUTED));
        status.add(Box.createHorizontalGlue());
        if (a.auto)
        {
            status.add(new Chip().set("⚙ Auto", BentoTheme.INFO));
        }
        else if (!inSession)
        {
            status.add(Tile.label("always on", BentoTheme.secondary(), BentoTheme.DIM));
        }
        status.setMaximumSize(new Dimension(Integer.MAX_VALUE, status.getPreferredSize().height));
        status.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        card.add(status);
        card.gap(8);

        String name = inSession ? a.name : SessionsSnapshot.FREE_PLAY;
        String sub = inSession ? join(a.kind, a.activity) : "Overall today " + Fmt.signed(s.overallToday);
        Icon icon = inSession
            ? ActivityIcons.icon(a.activity.isEmpty() ? a.name : a.activity, a.kind, sprites)
            : Icon.glyph("◎", BentoTheme.MUTED);
        card.add(identityRow(icon, name, sub, a.rateAvailable ? Fmt.rate(a.gpPerHour) + "/h" : "rate pending",
            a.rateAvailable ? BentoTheme.signColor(a.gpPerHour) : BentoTheme.DIM,
            BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().body + 2), null, BentoTheme.bodyBold()));
        card.setToolTipText((a.rateAvailable ? Fmt.rate(a.gpPerHour) + "/h · " : "rate pending · ") + Fmt.exactSigned(a.net) + " gp");
        card.gap(10);

        StatGrid grid = new StatGrid().joined(true).layout(StatGrid.Layout.ICON_TOP);
        List<StatGrid.Cell> cells = new ArrayList<>();
        cells.add(new StatGrid.Cell(Icon.glyph("◷", BentoTheme.INFO), "Duration", Fmt.durationCompact(a.durationMillis), BentoTheme.TEXT,
            null, true, (inSession ? "Active time in this session: " : "Active time in free play: ") + Fmt.duration(a.durationMillis)));
        cells.add(new StatGrid.Cell(Icon.glyph("⚒", BentoTheme.MUTED), "Total time", Fmt.durationCompact(s.overallActiveMillis), BentoTheme.TEXT,
            null, true, "Overall: every session and free play, all time — " + Fmt.duration(s.overallActiveMillis)));
        cells.add(new StatGrid.Cell(Icon.glyph("◉", BentoTheme.MUTED), "Net",
            Fmt.signed(a.net), BentoTheme.signColor(a.net), null, true, "Net for " + (inSession ? "this session" : "free play")));
        grid.cells(cells, 3);
        grid.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        card.add(grid);
        card.gap(10);

        Controls.Button action = inSession
            ? new Controls.Button("❚❚ End session", Controls.Button.Kind.STOP)
            : new Controls.Button("＋ Start a session", Controls.Button.Kind.PRIMARY);
        action.setToolTipText(inSession ? "End \"" + a.name + "\" · tracking continues as free play" : "Name it, pick a category");
        action.onClick(inSession ? actions::endSession : actions::startSession);
        action.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        action.setMaximumSize(new Dimension(Integer.MAX_VALUE, action.getPreferredSize().height + 4));
        action.setPreferredSize(new Dimension(Tile.interiorWidth(), action.getPreferredSize().height + 4));
        card.add(action);
        card.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        return card;
    }

    /** icon · title over subtitle · right text (and an optional component under it). */
    private JComponent identityRow(Icon icon, String title, String subtitle, @Nullable String right, java.awt.Color rightColor,
        java.awt.Font titleFont, @Nullable JComponent underRight)
    {
        return identityRow(icon, title, subtitle, right, rightColor, titleFont, underRight, BentoTheme.bodyBold());
    }

    private JComponent identityRow(Icon icon, String title, String subtitle, @Nullable String right, java.awt.Color rightColor,
        java.awt.Font titleFont, @Nullable JComponent underRight, java.awt.Font rightFont)
    {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        Tile.IconBox iconLabel = new Tile.IconBox(icon, BentoTheme.MUTED);
        int iconSpan = 0;
        if (icon.hasSprite() || icon.isControlForTest())
        {
            iconLabel.setAlignmentY(JComponent.TOP_ALIGNMENT);
            row.add(iconLabel);
            row.add(Box.createHorizontalStrut(10));
            iconSpan = Tile.IconBox.SIZE + 10;
        }

        JPanel rightCol = new JPanel();
        rightCol.setLayout(new BoxLayout(rightCol, BoxLayout.Y_AXIS));
        rightCol.setOpaque(false);
        rightCol.setAlignmentY(JComponent.TOP_ALIGNMENT);
        int rightW = 0;
        if (right != null && !right.isEmpty())
        {
            JLabel r = Tile.label(right, rightFont, rightColor);
            r.setAlignmentX(JComponent.RIGHT_ALIGNMENT);
            rightCol.add(r);
            rightW = r.getPreferredSize().width;
        }
        if (underRight != null)
        {
            underRight.setAlignmentX(JComponent.RIGHT_ALIGNMENT);
            rightCol.add(underRight);
            rightW = Math.max(rightW, underRight.getPreferredSize().width);
        }

        int avail = Math.max(48, Tile.interiorWidth() - iconSpan - rightW - 8);
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setOpaque(false);
        col.setAlignmentY(JComponent.TOP_ALIGNMENT);
        JLabel t = Tile.label(StatBlock.fit(title, row.getFontMetrics(titleFont), avail), titleFont, BentoTheme.TEXT);
        t.setToolTipText(title);
        t.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        col.add(t);
        if (subtitle != null && !subtitle.isEmpty())
        {
            JLabel st = Tile.label(StatBlock.fit(subtitle, row.getFontMetrics(BentoTheme.secondary()), avail), BentoTheme.secondary(), BentoTheme.MUTED);
            st.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            col.add(st);
        }
        col.setMaximumSize(new Dimension(avail, Integer.MAX_VALUE));
        row.add(col);
        row.add(Box.createHorizontalGlue());
        row.add(rightCol);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        return row;
    }

    private JComponent groupHeader(SessionsSnapshot.Group g, boolean open)
    {
        JPanel line = new JPanel();
        line.setLayout(new BoxLayout(line, BoxLayout.X_AXIS));
        line.setOpaque(false);
        String glyph = g.recent ? "" : (open ? "▾ " : "▸ ");
        line.add(Tile.micro(glyph + g.label.toUpperCase(), BentoTheme.DIM));
        line.add(Box.createHorizontalStrut(6));
        line.add(Tile.label(g.sessionCount + (g.sessionCount == 1 ? " session" : " sessions"),
            BentoTheme.font(java.awt.Font.PLAIN, BentoTheme.density().micro + 1), BentoTheme.DIM));
        line.add(Box.createHorizontalGlue());
        line.add(Tile.label(Fmt.signed(g.net), BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().secondary), BentoTheme.signColor(g.net)));
        line.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 2, 4, 2));
        // Max after the border: a max below the preferred height makes BoxLayout squeeze the whole column.
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, line.getPreferredSize().height));
        line.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        if (!g.recent)
        {
            line.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            line.addMouseListener(new java.awt.event.MouseAdapter()
            {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e)
                {
                    if (!openGroups.remove(g.label))
                    {
                        openGroups.add(g.label);
                    }
                    reapply();
                }
            });
        }
        return line;
    }

    /** Several same-name sessions in a row read as one card; open it to see each. */
    private JComponent foldCard(SessionsSnapshot.Fold f, String key, boolean open)
    {
        SessionsSnapshot.SessionRow first = f.first();
        Tile card = new Tile();
        card.padding(7, 10, 7, 10);
        Icon icon = ActivityIcons.icon(first.activity.isEmpty() ? f.name : first.activity, first.kind, sprites);
        String meta = "×" + f.sessions.size() + " · " + Fmt.durationCompact(f.durationMillis) + " · " + Fmt.when(first.startedAt, System.currentTimeMillis());
        card.add(stripLine(icon, f.name, meta, Fmt.signed(f.net), BentoTheme.signColor(f.net), open ? "▾" : "▸", BentoTheme.DIM));
        card.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        card.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        card.setToolTipText(open ? "Hide the " + f.sessions.size() + " sessions" : "Show the " + f.sessions.size() + " sessions");
        card.addMouseListener(new java.awt.event.MouseAdapter()
        {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e)
            {
                if (!openFolds.remove(key))
                {
                    openFolds.add(key);
                }
                reapply();
            }
        });
        return card;
    }

    /** One session as a card; click the name for Ledger · ⟲ Again · ⋯, ★ toggles the favourite. */
    private JComponent sessionCard(SessionsSnapshot.SessionRow row, int indent)
    {
        boolean picked = selected.containsKey(row.asStatement.key);
        Tile card = new Tile();
        // Picking: a picked strip is highlighted (accent border and a soft fill); nothing else changes.
        if (picked)
        {
            card.borderColor(BentoTheme.accentColor()).tint(BentoTheme.accentSoft());
        }
        else if (row.pvp)
        {
            card.borderColor(BentoTheme.withAlpha(BentoTheme.PVP, 120));
        }
        Icon icon = ActivityIcons.icon(row.activity.isEmpty() ? row.name : row.activity, row.kind, sprites);
        boolean expanded = expandedSessions.contains(row.id) && !picking;
        JComponent bodyRow;
        if (!expanded)
        {
            // Collapsed: one strip — name · category · when ······ net ›
            card.padding(7, 10, 7, 10);
            String meta = Fmt.when(row.startedAt, System.currentTimeMillis());
            bodyRow = stripLine(icon, row.name, meta, Fmt.signed(row.net), BentoTheme.signColor(row.net),
                row.favorite ? "★" : "›", row.favorite ? BentoTheme.WARN : BentoTheme.DIM);
            card.add(bodyRow);
        }
        else
        {
            // Expanded: the full card — category · rate, when, the footer strip and the actions.
            Controls.IconButton star = new Controls.IconButton(row.favorite ? "★" : "☆", row.favorite ? "Favourite · click to remove" : "Mark as favourite", false);
            star.setOn(row.favorite);
            star.onClick(() -> actions.toggleFavorite(row));
            String sub = join(row.kind, row.activity) + (row.rateAvailable ? " · " + Fmt.rate(row.gpPerHour) + "/h" : "")
                + (row.auto ? " · auto" : "") + (row.excludedFromAverages ? " · excluded" : "");
            String when = Fmt.when(row.startedAt, System.currentTimeMillis()) + endedLabel(row.endReason) + (row.compacted ? " · summary only" : "");
            bodyRow = cardBody(icon, row.name, sub, when, null, star);
            card.add(bodyRow);
            card.gap(6);
            String key = row.keyStat.isEmpty() ? (row.deaths > 0 ? row.deaths + (row.deaths == 1 ? " death" : " deaths") : "—") : row.keyStat;
            card.add(metaStrip(
                new Meta("", Fmt.durationCompact(row.durationMillis), BentoTheme.TEXT),
                new Meta("", key, BentoTheme.TEXT),
                new Meta("", Fmt.signed(row.net), BentoTheme.signColor(row.net))));
            Controls.Button ledger = new Controls.Button("Ledger", Controls.Button.Kind.DEFAULT);
            ledger.setEnabled(!row.compacted);
            ledger.setToolTipText(row.compacted ? "Summary only — receipts were compacted" : "Open this session in the Ledger");
            ledger.onClick(() -> actions.openLedgerFor(row.id, row.name));
            Controls.Button again = new Controls.Button("⟲ Again", Controls.Button.Kind.DEFAULT);
            again.setToolTipText("Start a new session with this name");
            again.setEnabled(!row.freePlay);
            again.onClick(() -> actions.startAgain(row.name));
            Controls.Button more = new Controls.Button("⋯", Controls.Button.Kind.DEFAULT);
            more.setToolTipText("Rename, tags, notes, compare, copy, exclude, delete");
            more.onClick(() -> actions.sessionMenu(more, row));
            card.gap(6).row(Tile.buttons(ledger, again, more));
        }
        card.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        card.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        java.awt.event.MouseAdapter click = new java.awt.event.MouseAdapter()
        {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e)
            {
                if (picking)
                {
                    toggle(row.asStatement);
                    return;
                }
                if (!expandedSessions.remove(row.id))
                {
                    expandedSessions.add(row.id);
                }
                reapply();
            }
        };
        bodyRow.addMouseListener(click);
        card.addMouseListener(click);
        if (indent > 0)
        {
            JPanel wrap = new JPanel();
            wrap.setLayout(new BoxLayout(wrap, BoxLayout.X_AXIS));
            wrap.setOpaque(false);
            wrap.add(Box.createHorizontalStrut(indent));
            wrap.add(card);
            wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
            wrap.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            return wrap;
        }
        return card;
    }

    /** icon · name (+ count chip) / category · activity / when — with a star or fold arrow at the top right. */
    private JComponent cardBody(Icon icon, String name, String sub, String when, @Nullable Chip count, @Nullable JComponent topRight)
    {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        Tile.IconBox iconLabel = new Tile.IconBox(icon, BentoTheme.MUTED);
        int iconSpan = 0;
        if (icon.hasSprite() || icon.isControlForTest())
        {
            iconLabel.setAlignmentY(JComponent.TOP_ALIGNMENT);
            row.add(iconLabel);
            row.add(Box.createHorizontalStrut(10));
            iconSpan = Tile.IconBox.SIZE + 10;
        }
        int rightW = topRight == null ? 0 : topRight.getPreferredSize().width + 6;
        int avail = Math.max(48, Tile.interiorWidth() - iconSpan - rightW);
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setOpaque(false);
        col.setAlignmentY(JComponent.TOP_ALIGNMENT);
        JPanel title = new JPanel();
        title.setLayout(new BoxLayout(title, BoxLayout.X_AXIS));
        title.setOpaque(false);
        int chipW = count == null ? 0 : count.getPreferredSize().width + 6;
        java.awt.Font nameFont = BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().body + 1);
        JLabel t = Tile.label(StatBlock.fit(name, row.getFontMetrics(nameFont), avail - chipW), nameFont, BentoTheme.TEXT);
        t.setToolTipText(name);
        title.add(t);
        if (count != null)
        {
            title.add(Box.createHorizontalStrut(6));
            title.add(count);
        }
        title.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        title.setMaximumSize(new Dimension(avail, title.getPreferredSize().height));
        col.add(title);
        if (!sub.isEmpty())
        {
            JLabel sl = Tile.label(StatBlock.fit(sub, row.getFontMetrics(BentoTheme.secondary()), avail), BentoTheme.secondary(), BentoTheme.MUTED);
            sl.setToolTipText(sub);
            sl.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            col.add(sl);
        }
        if (!when.isEmpty())
        {
            java.awt.Font metaFont = BentoTheme.font(java.awt.Font.PLAIN, BentoTheme.density().micro + 1);
            JLabel m = Tile.label(StatBlock.fit(when, row.getFontMetrics(metaFont), avail), metaFont, BentoTheme.DIM);
            m.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            col.add(m);
        }
        col.setMaximumSize(new Dimension(avail, Integer.MAX_VALUE));
        row.add(col);
        row.add(Box.createHorizontalGlue());
        if (topRight != null)
        {
            topRight.setAlignmentY(JComponent.TOP_ALIGNMENT);
            row.add(topRight);
        }
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        return row;
    }

    /**
     * One collapsed line: [icon] name · meta ······ value ›. The name yields to the meta, the meta
     * yields to the value; the trailing glyph is a fold arrow or a ★.
     */
    private JComponent stripLine(Icon icon, String name, String meta, String value, java.awt.Color valueColor, String trailing, java.awt.Color trailingColor)
    {
        JPanel line = new JPanel();
        line.setLayout(new BoxLayout(line, BoxLayout.X_AXIS));
        line.setOpaque(false);
        int used = 0;
        if (icon.hasSprite() || icon.isControlForTest())
        {
            Tile.IconLabel il = new Tile.IconLabel(icon);
            il.setAlignmentY(JComponent.CENTER_ALIGNMENT);
            line.add(il);
            line.add(Box.createHorizontalStrut(8));
            used += Icon.SIZE + 8;
        }
        JLabel v = Tile.label(value, BentoTheme.bodyBold(), valueColor);
        JLabel t = Tile.label(trailing, BentoTheme.symbol(BentoTheme.density().body), trailingColor);
        int right = v.getPreferredSize().width + 6 + t.getPreferredSize().width + 4;
        int avail = Math.max(60, Tile.interiorWidth() - used - right);
        java.awt.FontMetrics nm = line.getFontMetrics(BentoTheme.bodyBold());
        java.awt.FontMetrics mm = line.getFontMetrics(BentoTheme.secondary());
        String nameText = StatBlock.fit(name, nm, Math.min(nm.stringWidth(name), Math.max(40, avail - 30)));
        int metaAvail = avail - nm.stringWidth(nameText) - 8;
        String metaText = metaAvail > 24 ? StatBlock.fit(meta, mm, metaAvail) : "";
        JLabel n = Tile.label(nameText, BentoTheme.bodyBold(), BentoTheme.TEXT);
        n.setToolTipText(name + " · " + meta + " · click to expand");
        n.setAlignmentY(JComponent.CENTER_ALIGNMENT);
        line.add(n);
        if (!metaText.isEmpty())
        {
            line.add(Box.createHorizontalStrut(8));
            JLabel m = Tile.label(metaText, BentoTheme.secondary(), BentoTheme.MUTED);
            m.setAlignmentY(JComponent.CENTER_ALIGNMENT);
            line.add(m);
        }
        line.add(Box.createHorizontalGlue());
        v.setAlignmentY(JComponent.CENTER_ALIGNMENT);
        line.add(v);
        line.add(Box.createHorizontalStrut(6));
        t.setAlignmentY(JComponent.CENTER_ALIGNMENT);
        line.add(t);
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, line.getPreferredSize().height));
        line.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        return line;
    }

    /** One footer cell: glyph · text. */
    private static final class Meta
    {
        final String glyph;
        final String text;
        final java.awt.Color color;

        Meta(String glyph, String text, java.awt.Color color)
        {
            this.glyph = glyph;
            this.text = text == null ? "" : text;
            this.color = color;
        }
    }

    /** ◷ 2h 16m │ ⚒ ~7.4k logs │ ◉ +1.23M — three cells with dividers across the card's width. */
    /** " · ended idle" / " · ended by boundary" — only the closes worth a word; a manual End says nothing. */
    static String endedLabel(@Nullable com.gpmanager.model.SessionEndReason reason)
    {
        if (reason == null)
        {
            return "";
        }
        switch (reason)
        {
            case IDLE:
                return " · ended idle";
            case BOUNDARY:
                return " · ended by boundary";
            case SHUTDOWN:
                return " · ended at logout";
            default:
                return "";
        }
    }

    private static JComponent metaStrip(Meta... cells)
    {
        JComponent strip = new JComponent()
        {
            @Override
            public Dimension getPreferredSize()
            {
                return new Dimension(Tile.interiorWidth(), getFontMetrics(BentoTheme.bodyBold()).getHeight() + 10);
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
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_FRACTIONALMETRICS, java.awt.RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                    int w = getWidth();
                    int h = getHeight();
                    g2.setColor(BentoTheme.BORDER);
                    g2.drawLine(0, 0, w, 0);
                    int n = cells.length;
                    // Each cell gets what its text needs; slack is shared, a shortfall is scaled so
                    // the three never run into each other.
                    java.awt.Font bold = BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().secondary);
                    java.awt.FontMetrics glyphFm = getFontMetrics(BentoTheme.symbol(BentoTheme.density().secondary));
                    int[] widths = new int[n];
                    int need = 0;
                    for (int i = 0; i < n; i++)
                    {
                        java.awt.FontMetrics fm = getFontMetrics(BentoTheme.fontFor(cells[i].text, bold));
                        widths[i] = (cells[i].glyph.isEmpty() ? 0 : glyphFm.stringWidth(cells[i].glyph) + 3) + fm.stringWidth(cells[i].text) + (i == 0 ? 4 : 10);
                        need += widths[i];
                    }
                    if (need <= w)
                    {
                        int slack = (w - need) / n;
                        for (int i = 0; i < n; i++)
                        {
                            widths[i] += slack;
                        }
                    }
                    else
                    {
                        for (int i = 0; i < n; i++)
                        {
                            widths[i] = Math.max(30, widths[i] * w / need);
                        }
                    }
                    int x = 0;
                    for (int i = 0; i < n; i++)
                    {
                        Meta c = cells[i];
                        if (i > 0)
                        {
                            g2.setColor(BentoTheme.BORDER);
                            g2.drawLine(x, 8, x, h - 4);
                        }
                        int cx = x + (i == 0 ? 0 : 7);
                        int cy = (h + 2) / 2;
                        g2.setFont(BentoTheme.symbol(BentoTheme.density().secondary));
                        java.awt.FontMetrics gm = g2.getFontMetrics();
                        if (!c.glyph.isEmpty())
                        {
                            g2.setColor(BentoTheme.MUTED);
                            g2.drawString(c.glyph, cx, cy + (gm.getAscent() - gm.getDescent()) / 2);
                            cx += gm.stringWidth(c.glyph) + 3;
                        }
                        g2.setFont(BentoTheme.fontFor(c.text, bold));
                        java.awt.FontMetrics fm = g2.getFontMetrics();
                        g2.setColor(c.color);
                        String text = StatBlock.fit(c.text, fm, Math.max(16, x + widths[i] - cx - 3));
                        g2.drawString(text, cx, cy + (fm.getAscent() - fm.getDescent()) / 2);
                        x += widths[i];
                    }
                }
                finally
                {
                    g2.dispose();
                }
            }
        };
        StringBuilder tip = new StringBuilder();
        for (Meta c : cells)
        {
            tip.append(tip.length() == 0 ? "" : " · ").append(c.text);
        }
        strip.setToolTipText(tip.toString());
        strip.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        return strip;
    }

    private static String join(String kind, String activity)
    {
        String k = kind == null ? "" : kind;
        String a = activity == null ? "" : activity;
        if (k.isEmpty())
        {
            return a;
        }
        if (a.isEmpty() || a.equalsIgnoreCase(k))
        {
            return k;
        }
        return k + " · " + a;
    }

    private void toggle(SessionsSnapshot.Statement statement)
    {
        if (selected.remove(statement.key) == null)
        {
            if (selected.size() == 2)
            {
                String first = selected.keySet().iterator().next();
                selected.remove(first);
            }
            selected.put(statement.key, statement);
        }
        picking = true;
        reapply();
    }

    private void refreshFooter()
    {
        int n = selected.size();
        // One dynamic control: Compare → Compare 0/2 · ✕ → Compare › (mint) · ✕.
        compareButton.setText(n == 2 ? "Compare ›" : picking ? "Compare " + n + "/2" : "Compare");
        compareButton.setKind(n == 2 ? Controls.Button.Kind.PRIMARY : picking ? Controls.Button.Kind.DEFAULT : Controls.Button.Kind.GHOST);
        compareButton.setToolTipText(n == 2 ? "Compare the two picked sessions" : picking ? "Pick " + (2 - n) + " more" : "Pick two sessions to compare");
        cancelPick.setVisible(picking);
        footer.setVisible(false);
        bar.revalidate();
        bar.repaint();
    }

    /** Test/preview seam: expand a session and select statements as a user would. */
    public void selectForPreview(@Nullable String expandSessionId, SessionsSnapshot.Statement... statements)
    {
        if (expandSessionId != null)
        {
            expandedSessions.add(expandSessionId);
        }
        for (SessionsSnapshot.Statement st : statements)
        {
            toggle(st);
        }
        if (statements.length == 0)
        {
            picking = false;
        }
        reapply();
    }

    /** Test/preview seam: the grouped history view. */
    public void showAllForPreview(boolean all)
    {
        showAll = all;
        reapply();
    }

    @Nullable
    public SessionsSnapshot lastForPreview()
    {
        return last;
    }

    public void clearSelection()
    {
        selected.clear();
        picking = false;
        reapply();
    }
}
