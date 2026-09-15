package com.gpmanager.ui.bento;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.annotation.Nullable;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

/**
 * Ledger (SIDEBAR_BENTO.md §4): one page bar (scope ▾ · view · search), sections by how the
 * money moved, rows icon · name · qty · change, details only when a row is expanded.
 */
public final class LedgerPage implements BentoShell.Page
{
    public interface Actions
    {
        void correct(LedgerSnapshot.Row row, com.gpmanager.model.TransactionCorrection correction);

        void excludeItem(int itemId, String name);

        void openItemSheet(int itemId, String name);

        void scopeChanged(LedgerSnapshot.Scope scope);

        void searchChanged(String text);
    }

    private final Actions actions;
    private final Function<Integer, BufferedImage> sprites;

    private final JPanel bar = new JPanel();
    private final Controls.Button scopeButton = new Controls.Button("This session ▾", Controls.Button.Kind.DEFAULT);
    private final Controls.IconButton byItem = new Controls.IconButton("▤", "By item", true);
    private final Controls.IconButton search = new Controls.IconButton("⌕", "Search", true);
    private final JPanel body = BentoShell.stack();
    private final JPanel searchRow = new JPanel();
    private final JTextField searchField = new JTextField();
    private final JPanel reviewHost = new JPanel();
    private final JPanel sections = new JPanel();
    private final JPanel footer = new JPanel();

    private final EnumSet<LedgerSnapshot.Section> collapsed = EnumSet.of(LedgerSnapshot.Section.NEUTRAL, LedgerSnapshot.Section.CLAIMS);
    /** Which tab the Costs card shows: Supplies or Loss. */
    @Nullable
    private LedgerSnapshot.Section costsTab;
    private LedgerSnapshot.Scope scope = LedgerSnapshot.Scope.SESSION;
    @Nullable
    private String historySessionId;
    private String historySessionName = "";
    @Nullable
    private String expandedKey;
    private boolean reviewOnly;
    @Nullable
    private LedgerSnapshot last;

    public LedgerPage(Actions actions, Function<Integer, BufferedImage> sprites)
    {
        this.actions = actions;
        this.sprites = sprites == null ? id -> null : sprites;

        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setOpaque(false);
        bar.add(Tile.label("Ledger", BentoTheme.font(java.awt.Font.BOLD, 13f), BentoTheme.TEXT));
        bar.add(Box.createHorizontalStrut(8));
        scopeButton.onClick(this::showScopeMenu);
        bar.add(scopeButton);
        bar.add(Box.createHorizontalGlue());
        byItem.setOn(true);
        bar.add(byItem);
        bar.add(Box.createHorizontalStrut(4));
        search.onClick(this::toggleSearch);
        bar.add(search);
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, bar.getPreferredSize().height));

        searchRow.setLayout(new BoxLayout(searchRow, BoxLayout.X_AXIS));
        searchRow.setOpaque(false);
        searchField.setFont(BentoTheme.body());
        searchField.setForeground(BentoTheme.TEXT);
        searchField.setCaretColor(BentoTheme.TEXT);
        searchField.setBackground(BentoTheme.SURFACE);
        searchField.setBorder(javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createLineBorder(BentoTheme.BORDER),
            javax.swing.BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
        {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e)
            {
                actions.searchChanged(searchField.getText());
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e)
            {
                actions.searchChanged(searchField.getText());
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e)
            {
                actions.searchChanged(searchField.getText());
            }
        });
        searchRow.add(searchField);
        searchRow.setVisible(false);
        searchRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, searchField.getPreferredSize().height));
        BentoShell.stackAdd(body, searchRow);

        reviewHost.setLayout(new BoxLayout(reviewHost, BoxLayout.Y_AXIS));
        reviewHost.setOpaque(false);
        BentoShell.stackAdd(body, reviewHost);

        sections.setLayout(new BoxLayout(sections, BoxLayout.Y_AXIS));
        sections.setOpaque(false);
        BentoShell.stackAdd(body, sections);

        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        footer.setOpaque(false);
        BentoShell.stackAdd(body, footer);
    }

    /** Preview/test seam: expand the first row of the first non-empty section. */
    public void expandFirstForPreview()
    {
        if (last == null)
        {
            return;
        }
        for (LedgerSnapshot.Section section : LedgerSnapshot.Section.values())
        {
            List<LedgerSnapshot.Row> rows = last.rows(section);
            if (!rows.isEmpty() && !collapsed.contains(section))
            {
                LedgerSnapshot.Row row = rows.get(0);
                expandedKey = section + "|" + row.itemId + "|" + row.name;
                apply(last);
                return;
            }
        }
    }

    @Override
    public String id()
    {
        return BentoShell.LEDGER;
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

    public LedgerSnapshot.Scope scope()
    {
        return scope;
    }

    public void setScope(LedgerSnapshot.Scope value)
    {
        scope = value == null ? LedgerSnapshot.Scope.SESSION : value;
        historySessionId = null;
        scopeButton.setText(scope.label + " ▾");
    }

    /** Show a retained session instead of the live scopes. */
    public void setHistorySession(String sessionId, String name)
    {
        historySessionId = sessionId;
        historySessionName = name == null ? "" : name;
        expandedKey = null;
        scopeButton.setText(StatBlock.fit(historySessionName, scopeButton.getFontMetrics(BentoTheme.secondary()), 110) + " ▾");
    }

    @Nullable
    public String historySessionId()
    {
        return historySessionId;
    }

    private void showScopeMenu()
    {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        menu.setBackground(BentoTheme.ALT);
        menu.setBorder(javax.swing.BorderFactory.createLineBorder(BentoTheme.BORDER));
        if (historySessionId != null)
        {
            javax.swing.JMenuItem current = new javax.swing.JMenuItem("●  " + historySessionName + " (retained)");
            current.setEnabled(false);
            current.setFont(BentoTheme.secondary());
            menu.add(current);
            menu.addSeparator();
        }
        for (LedgerSnapshot.Scope s : LedgerSnapshot.Scope.values())
        {
            javax.swing.JMenuItem item = new javax.swing.JMenuItem((historySessionId == null && s == scope ? "●  " : "○  ") + s.label);
            item.setFont(BentoTheme.secondary());
            item.setForeground(BentoTheme.TEXT);
            item.setBackground(BentoTheme.ALT);
            item.setOpaque(true);
            item.addActionListener(e ->
            {
                setScope(s);
                actions.scopeChanged(s);
            });
            menu.add(item);
        }
        menu.show(scopeButton, 0, scopeButton.getHeight());
    }

    private void toggleSearch()
    {
        boolean show = !searchRow.isVisible();
        searchRow.setVisible(show);
        search.setOn(show);
        if (show)
        {
            searchField.requestFocusInWindow();
        }
        else
        {
            searchField.setText("");
        }
        body.revalidate();
    }

    public void apply(LedgerSnapshot s)
    {
        last = s;
        sections.removeAll();
        reviewHost.removeAll();
        if (s.reviewCount > 0)
        {
            Notice pill = new Notice("▲", s.reviewCount + (s.reviewCount == 1 ? " needs a decision" : " need a decision"),
                "", reviewOnly ? "show all" : "show", Notice.Tone.WARN)
                .onClick(() ->
                {
                    reviewOnly = !reviewOnly;
                    apply(s);
                });
            pill.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            reviewHost.add(pill);
            reviewHost.setVisible(true);
        }
        else
        {
            reviewOnly = false;
            reviewHost.setVisible(false);
        }

        boolean any = false;
        for (LedgerSnapshot.Section section : LedgerSnapshot.Section.values())
        {
            List<LedgerSnapshot.Row> rows = s.rows(section);
            if (reviewOnly)
            {
                rows = filterReview(rows);
            }
            if (section == LedgerSnapshot.Section.LOSSES)
            {
                continue; // inside the Costs card
            }
            boolean always = !reviewOnly && (section == LedgerSnapshot.Section.GAINS || section == LedgerSnapshot.Section.SUPPLIES);
            if (rows.isEmpty() && !always)
            {
                continue;
            }
            any |= !rows.isEmpty();
            if (sections.getComponentCount() > 0)
            {
                sections.add(Box.createVerticalStrut(BentoTheme.density().gap));
            }
            boolean open = !collapsed.contains(section);
            Tile card = new Tile();
            if (section == LedgerSnapshot.Section.SUPPLIES)
            {
                // Supplies and Loss are one Costs card with two tabs: both are money out, told apart by why.
                List<LedgerSnapshot.Row> lossRows = reviewOnly ? filterReview(s.rows(LedgerSnapshot.Section.LOSSES)) : s.rows(LedgerSnapshot.Section.LOSSES);
                // Loss first; until the owner picks a tab, open on the one that has something to show.
                LedgerSnapshot.Section shown = costsTab != null ? costsTab
                    : lossRows.isEmpty() && !rows.isEmpty() ? LedgerSnapshot.Section.SUPPLIES : LedgerSnapshot.Section.LOSSES;
                List<LedgerSnapshot.Row> shownRows = shown == LedgerSnapshot.Section.LOSSES ? lossRows : rows;
                card.add(costsHeader(shownRows.size(), s.subtotal(LedgerSnapshot.Section.SUPPLIES) + s.subtotal(LedgerSnapshot.Section.LOSSES), open));
                if (open)
                {
                    card.gap(6);
                    Controls.Segmented tabs = new Controls.Segmented(Arrays.asList(
                        "Loss · " + lossRows.size(), "Supplies · " + rows.size()));
                    tabs.select(shown == LedgerSnapshot.Section.SUPPLIES ? 1 : 0, false);
                    tabs.onSelect(i ->
                    {
                        costsTab = i == 1 ? LedgerSnapshot.Section.SUPPLIES : LedgerSnapshot.Section.LOSSES;
                        apply(s);
                    });
                    tabs.setToolTipText("Supplies: consumables · Loss: deaths, tax, fees, drops");
                    tabs.setAlignmentX(JComponent.LEFT_ALIGNMENT);
                    card.add(tabs);
                    if (shownRows.isEmpty())
                    {
                        card.gap(6);
                        card.add(emptyState(shown));
                    }
                    addRows(card, s, shown, shownRows);
                }
                any |= !lossRows.isEmpty();
            }
            else
            {
                card.add(sectionHeader(section, rows.size(), s.subtotal(section), open));
                if (open)
                {
                    if (rows.isEmpty())
                    {
                        card.gap(6);
                        card.add(emptyState(section));
                    }
                    addRows(card, s, section, rows);
                }
            }
            card.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            sections.add(card);
        }
        if (!any && reviewOnly)
        {
            ListBlock empty = new ListBlock().empty("Nothing needs a decision");
            empty.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            sections.add(empty);
        }
        else if (!any && sections.getComponentCount() == 0)
        {
            ListBlock empty = new ListBlock().empty(emptySentence(s.scope));
            empty.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            sections.add(empty);
        }

        // Total card: what the page adds up to, with the equation — the figure never yields.
        footer.removeAll();
        long net = s.gains - s.loss;
        Tile total = new Tile();
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        JPanel right = new JPanel();
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        right.setOpaque(false);
        right.setAlignmentY(JComponent.CENTER_ALIGNMENT);
        JLabel big = Tile.label(Fmt.signed(net), BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().body + 6), BentoTheme.signColor(net));
        big.setAlignmentX(JComponent.RIGHT_ALIGNMENT);
        right.add(big);
        int rightW = big.getPreferredSize().width;
        int leftAvail = Math.max(60, Tile.interiorWidth() - rightW - 10);

        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setOpaque(false);
        col.setAlignmentY(JComponent.CENTER_ALIGNMENT);
        java.awt.Font titleFont = BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().body + 1);
        JLabel title = Tile.label("Total", titleFont, BentoTheme.TEXT);
        title.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        col.add(title);
        // The scope already reads in the page bar; the card says what it counted.
        JLabel counts = Tile.label(StatBlock.fit(s.receiptCount + (s.receiptCount == 1 ? " receipt · " : " receipts · ")
            + s.itemCount + (s.itemCount == 1 ? " item" : " items"), col.getFontMetrics(BentoTheme.secondary()), leftAvail), BentoTheme.secondary(), BentoTheme.MUTED);
        counts.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        col.add(counts);
        col.setMaximumSize(new Dimension(leftAvail, Integer.MAX_VALUE));
        row.add(col);
        row.add(Box.createHorizontalGlue());
        row.add(right);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        total.add(row);
        total.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        total.setToolTipText(Fmt.exact(s.gains) + " gained − " + Fmt.exact(s.loss) + " spent or lost = " + Fmt.exactSigned(net) + " gp");
        footer.add(total);
        footer.setVisible(s.receiptCount > 0);
        body.revalidate();
        body.repaint();
    }

    private static String emptySentence(LedgerSnapshot.Scope scope)
    {
        switch (scope)
        {
            case TODAY:
                return "No receipts today";
            default:
                return "No receipts yet this session";
        }
    }

    private static List<LedgerSnapshot.Row> filterReview(List<LedgerSnapshot.Row> rows)
    {
        List<LedgerSnapshot.Row> out = new java.util.ArrayList<>();
        for (LedgerSnapshot.Row r : rows)
        {
            if ("review".equals(r.tag))
            {
                out.add(r);
            }
        }
        return out;
    }

    private static String qtyText(LedgerSnapshot.Row row)
    {
        long q = Math.abs(row.quantity);
        if (q == 0L)
        {
            return null;
        }
        return q == 1L && row.receipts.size() == 1 ? null : Fmt.times(q);
    }

    private static java.awt.Color tagColor(String tag)
    {
        switch (tag)
        {
            case "review":
                return BentoTheme.WARN;
            case "claim":
                return BentoTheme.INFO;
            case "ignored":
                return BentoTheme.DIM;
            default:
                return BentoTheme.QUIET;
        }
    }

    private static Icon sectionIcon(LedgerSnapshot.Section section)
    {
        switch (section)
        {
            case GAINS:
                return Icon.art(net.runelite.api.gameval.SpriteID.SideIcons.INVENTORY, BentoTheme.WARN);
            case MARKET:
                return Icon.art(net.runelite.api.gameval.SpriteID.GeSmallicons.COLLECTION_BOX_OFFER_SELL, BentoTheme.INFO);
            case SUPPLIES:
                return Icon.art(net.runelite.api.gameval.SpriteID.OrbIcon.HITPOINTS, new java.awt.Color(0xff8c42));
            case LOSSES:
                return Icon.art(net.runelite.api.gameval.SpriteID.OrbIcon.HITPOINTS, BentoTheme.NEGATIVE);
            case CLAIMS:
                return Icon.art(net.runelite.api.gameval.SpriteID.BanktabIcons.ADD, BentoTheme.QUIET);
            default:
                return Icon.art(net.runelite.api.gameval.SpriteID.BanktabIcons.ALL_ITEMS, BentoTheme.MUTED);
        }
    }

    private static java.awt.Color sectionTint(LedgerSnapshot.Section section)
    {
        switch (section)
        {
            case GAINS:
                return BentoTheme.WARN;
            case MARKET:
                return BentoTheme.INFO;
            case SUPPLIES:
                return new java.awt.Color(0xff8c42);
            case LOSSES:
                return BentoTheme.NEGATIVE;
            case CLAIMS:
                return BentoTheme.QUIET;
            default:
                return BentoTheme.MUTED;
        }
    }

    private static String scopeLabel(LedgerSnapshot.Scope scope)
    {
        return scope == LedgerSnapshot.Scope.TODAY ? "Today" : "This session";
    }

    /** ⌄ · icon box · Gains · 1 ······ +250 — the card's title line; click folds the section. */
    private JComponent sectionHeader(LedgerSnapshot.Section section, int count, long subtotal, boolean open)
    {
        JPanel line = new JPanel();
        line.setLayout(new BoxLayout(line, BoxLayout.X_AXIS));
        line.setOpaque(false);
        JLabel tri = Tile.label(open ? "⌄" : "›", BentoTheme.symbol(BentoTheme.density().body + 1), BentoTheme.MUTED);
        tri.setPreferredSize(new Dimension(12, tri.getPreferredSize().height));
        line.add(tri);
        line.add(Box.createHorizontalStrut(6));
        Tile.IconBox box = new Tile.IconBox(sectionIcon(section), sectionTint(section));
        box.setAlignmentY(JComponent.CENTER_ALIGNMENT);
        line.add(box);
        line.add(Box.createHorizontalStrut(8));
        line.add(Tile.label(section.label, BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().body + 1), BentoTheme.TEXT));
        line.add(Box.createHorizontalStrut(5));
        line.add(Tile.label("· " + count, BentoTheme.secondary(), BentoTheme.MUTED));
        line.add(Box.createHorizontalGlue());
        String right;
        java.awt.Color color;
        if (section == LedgerSnapshot.Section.NEUTRAL)
        {
            right = "0";
            color = BentoTheme.DIM;
        }
        else if (section == LedgerSnapshot.Section.CLAIMS)
        {
            right = "not counted";
            color = BentoTheme.DIM;
        }
        else
        {
            right = count == 0 ? "0" : Fmt.signed(subtotal);
            color = count == 0 ? BentoTheme.DIM : BentoTheme.signColor(subtotal);
        }
        line.add(Tile.label(right, BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().body + 1), color));
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, line.getPreferredSize().height));
        line.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        line.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        line.addMouseListener(new java.awt.event.MouseAdapter()
        {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e)
            {
                if (collapsed.contains(section))
                {
                    collapsed.remove(section);
                }
                else
                {
                    collapsed.add(section);
                }
                if (last != null)
                {
                    apply(last);
                }
            }
        });
        return line;
    }

    /** The item rows of one section, each its own card, with the expansion under the open one. */
    private void addRows(Tile card, LedgerSnapshot s, LedgerSnapshot.Section section, List<LedgerSnapshot.Row> rows)
    {
        for (LedgerSnapshot.Row row : rows)
        {
            String key = section + "|" + row.itemId + "|" + row.name;
            boolean expanded = key.equals(expandedKey);
            ItemRow item = new ItemRow()
                .card(true)
                .sprite(row.itemId > 0 ? sprites.apply(row.itemId) : null)
                .name(row.name, qtyText(row))
                .open(expanded)
                .onClick(() ->
                {
                    expandedKey = expanded ? null : key;
                    apply(s);
                });
            item.onContext(() -> showCorrectMenu(item, row));
            if (row.tag != null)
            {
                item.tag(row.tag, tagColor(row.tag));
            }
            if (row.section == LedgerSnapshot.Section.NEUTRAL || row.section == LedgerSnapshot.Section.CLAIMS || !row.counted)
            {
                item.value("0", BentoTheme.DIM);
            }
            else
            {
                item.value(row.value);
            }
            card.gap(5);
            item.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            card.add(item);
            if (expanded)
            {
                JComponent xp = expansion(row);
                xp.setAlignmentX(JComponent.LEFT_ALIGNMENT);
                card.add(xp);
            }
        }
    }

    /** ⌄ · icon box · Costs · N ······ −10.5k — the fused Supplies + Loss card's title line. */
    private JComponent costsHeader(int count, long subtotal, boolean open)
    {
        JPanel line = new JPanel();
        line.setLayout(new BoxLayout(line, BoxLayout.X_AXIS));
        line.setOpaque(false);
        JLabel tri = Tile.label(open ? "⌄" : "›", BentoTheme.symbol(BentoTheme.density().body + 1), BentoTheme.MUTED);
        tri.setPreferredSize(new Dimension(12, tri.getPreferredSize().height));
        line.add(tri);
        line.add(Box.createHorizontalStrut(6));
        Tile.IconBox box = new Tile.IconBox(sectionIcon(LedgerSnapshot.Section.SUPPLIES), sectionTint(LedgerSnapshot.Section.SUPPLIES));
        box.setAlignmentY(JComponent.CENTER_ALIGNMENT);
        line.add(box);
        line.add(Box.createHorizontalStrut(8));
        line.add(Tile.label("Costs", BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().body + 1), BentoTheme.TEXT));
        line.add(Box.createHorizontalStrut(5));
        line.add(Tile.label("· " + count, BentoTheme.secondary(), BentoTheme.MUTED));
        line.add(Box.createHorizontalGlue());
        line.add(Tile.label(subtotal == 0L ? "0" : Fmt.signed(subtotal), BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().body + 1),
            subtotal == 0L ? BentoTheme.DIM : BentoTheme.signColor(subtotal)));
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, line.getPreferredSize().height));
        line.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        line.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        line.setToolTipText("Supplies and Loss together · click to fold");
        line.addMouseListener(new java.awt.event.MouseAdapter()
        {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e)
            {
                if (collapsed.contains(LedgerSnapshot.Section.SUPPLIES))
                {
                    collapsed.remove(LedgerSnapshot.Section.SUPPLIES);
                }
                else
                {
                    collapsed.add(LedgerSnapshot.Section.SUPPLIES);
                }
                if (last != null)
                {
                    apply(last);
                }
            }
        });
        return line;
    }

    /** Centred ring icon, sentence and hint for a section with nothing in it. */
    private static JComponent emptyState(LedgerSnapshot.Section section)
    {
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setOpaque(false);
        box.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 0, 8, 0));
        JComponent ring = new JComponent()
        {
            @Override
            public Dimension getPreferredSize()
            {
                return new Dimension(30, 30);
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
                    g2.setColor(BentoTheme.BORDER);
                    g2.drawOval(0, 0, 29, 29);
                    g2.setFont(BentoTheme.symbol(14f));
                    g2.setColor(BentoTheme.DIM);
                    java.awt.FontMetrics fm = g2.getFontMetrics();
                    String glyph = section == LedgerSnapshot.Section.LOSSES ? "☐" : "◌";
                    g2.drawString(glyph, (30 - fm.stringWidth(glyph)) / 2, (30 - fm.getHeight()) / 2 + fm.getAscent());
                }
                finally
                {
                    g2.dispose();
                }
            }
        };
        ring.setAlignmentX(JComponent.CENTER_ALIGNMENT);
        box.add(ring);
        box.add(Box.createVerticalStrut(6));
        String title;
        String hint;
        switch (section)
        {
            case GAINS:
                title = "No gains yet";
                hint = "Drops and pickups will appear here.";
                break;
            case SUPPLIES:
                title = "No supplies used";
                hint = "Food, potions, runes and ammo will appear here.";
                break;
            default:
                title = "No losses recorded";
                hint = "Items lost on death will appear here.";
        }
        JLabel t = Tile.label(title, BentoTheme.bodyBold(), BentoTheme.MUTED);
        t.setAlignmentX(JComponent.CENTER_ALIGNMENT);
        box.add(t);
        JLabel h = Tile.label(hint, BentoTheme.secondary(), BentoTheme.DIM);
        h.setAlignmentX(JComponent.CENTER_ALIGNMENT);
        box.add(h);
        box.setMaximumSize(new Dimension(Integer.MAX_VALUE, box.getPreferredSize().height));
        box.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        return box;
    }

    private JComponent expansion(LedgerSnapshot.Row row)
    {
        JPanel xp = new JPanel();
        xp.setLayout(new BoxLayout(xp, BoxLayout.Y_AXIS));
        xp.setOpaque(true);
        xp.setBackground(BentoTheme.ALT);
        xp.setBorder(javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createMatteBorder(1, 0, 0, 0, BentoTheme.BORDER),
            javax.swing.BorderFactory.createEmptyBorder(4, 9, 8, 9)));
        LedgerSnapshot.Receipt newest = row.receipts.isEmpty() ? null : row.receipts.get(0);
        java.awt.Font small = BentoTheme.font(java.awt.Font.PLAIN, BentoTheme.density().micro + 1.5f);
        if (newest != null)
        {
            xp.add(kv("How", capitalize(row.verb) + " · " + row.receipts.size() + (row.receipts.size() == 1 ? " receipt" : " receipts"), small));
            String price = newest.priceSource + (newest.priceCapturedAt > 0L ? " · " + timeOf(newest.priceCapturedAt) : "");
            xp.add(kv("Price", price, small));
            String counted = newest.counted ? "yes · " + row.section.label.toLowerCase() : "no";
            if (!newest.confidence.isEmpty())
            {
                counted += " · " + newest.confidence;
            }
            xp.add(kv("Counted", counted, small));
            if (newest.correction != com.gpmanager.model.TransactionCorrection.AUTO)
            {
                xp.add(kv("Correction", newest.correction.name().toLowerCase(), small));
            }
            if (!newest.why.isEmpty())
            {
                xp.add(Box.createVerticalStrut(2));
                int width = Tile.interiorWidth() - 22;
                for (String line : Tile.wrap(newest.why, xp.getFontMetrics(small), width))
                {
                    JLabel why = Tile.label(line, small, BentoTheme.DIM);
                    why.setAlignmentX(JComponent.LEFT_ALIGNMENT);
                    xp.add(why);
                }
            }
        }
        xp.add(Box.createVerticalStrut(4));
        int shown = 0;
        for (LedgerSnapshot.Receipt r : row.receipts)
        {
            if (shown == 5)
            {
                JLabel more = Tile.label("… " + (row.receipts.size() - 5) + " more", small, BentoTheme.DIM);
                more.setAlignmentX(JComponent.LEFT_ALIGNMENT);
                xp.add(more);
                break;
            }
            JLabel valueLabel = Tile.label(r.counted ? Fmt.signed(r.value) : "0", small, r.counted ? BentoTheme.signColor(r.value) : BentoTheme.DIM);
            String left = timeOf(r.timestamp) + "   " + (r.quantity >= 0 ? "+" : "−") + Fmt.exact(Math.abs(r.quantity));
            JPanel line = Tile.line(
                Tile.label(StatBlock.fit(left, xp.getFontMetrics(small), Tile.interiorWidth() - 22 - valueLabel.getPreferredSize().width - 8), small, BentoTheme.MUTED),
                valueLabel);
            line.setBorder(javax.swing.BorderFactory.createEmptyBorder(1, 0, 1, 0));
            xp.add(line);
            shown++;
        }
        xp.add(Box.createVerticalStrut(6));
        Controls.Button correct = new Controls.Button("Correct ▾", Controls.Button.Kind.DEFAULT);
        correct.onClick(() -> showCorrectMenu(correct, row));
        Controls.Button exclude = new Controls.Button("Exclude", Controls.Button.Kind.DEFAULT);
        exclude.onClick(() -> actions.excludeItem(row.itemId, row.name));
        exclude.setToolTipText("Never count this item (accounting exclusion)");
        Controls.Button sheet = new Controls.Button("Item ›", Controls.Button.Kind.GHOST);
        sheet.onClick(() -> actions.openItemSheet(row.itemId, row.name));
        JPanel buttons = Tile.buttons(correct, exclude, sheet);
        buttons.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, -5, 0, 0));
        xp.add(buttons);
        xp.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        xp.setMaximumSize(new Dimension(Integer.MAX_VALUE, xp.getPreferredSize().height));
        return xp;
    }

    private void showCorrectMenu(JComponent anchor, LedgerSnapshot.Row row)
    {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        menu.setBackground(BentoTheme.ALT);
        menu.setBorder(javax.swing.BorderFactory.createLineBorder(BentoTheme.BORDER));
        addCorrection(menu, row, "Automatic", com.gpmanager.model.TransactionCorrection.AUTO);
        addCorrection(menu, row, "Count as gain", com.gpmanager.model.TransactionCorrection.REVENUE);
        addCorrection(menu, row, "Count as cost", com.gpmanager.model.TransactionCorrection.COST);
        addCorrection(menu, row, "Mark as transfer", com.gpmanager.model.TransactionCorrection.TRANSFER);
        addCorrection(menu, row, "Ignore", com.gpmanager.model.TransactionCorrection.IGNORE);
        menu.addSeparator();
        javax.swing.JMenuItem scopeNote = new javax.swing.JMenuItem("Applies to all " + row.receipts.size()
            + (row.receipts.size() == 1 ? " receipt" : " receipts") + " of this item in scope");
        scopeNote.setEnabled(false);
        scopeNote.setFont(BentoTheme.font(java.awt.Font.PLAIN, BentoTheme.density().micro + 1));
        menu.add(scopeNote);
        menu.show(anchor, 0, anchor.getHeight());
    }

    private void addCorrection(javax.swing.JPopupMenu menu, LedgerSnapshot.Row row, String label,
        com.gpmanager.model.TransactionCorrection correction)
    {
        javax.swing.JMenuItem item = new javax.swing.JMenuItem(label);
        item.setFont(BentoTheme.secondary());
        item.setForeground(BentoTheme.TEXT);
        item.setBackground(BentoTheme.ALT);
        item.setOpaque(true);
        item.addActionListener(e -> actions.correct(row, correction));
        menu.add(item);
    }

    private static JPanel kv(String key, String value, java.awt.Font font)
    {
        JLabel k = Tile.label(key, font, BentoTheme.DIM);
        int avail = Tile.interiorWidth() - 22 - k.getPreferredSize().width - 8;
        JPanel line = Tile.line(k, Tile.label(StatBlock.fit(value, k.getFontMetrics(font), Math.max(30, avail)), font, BentoTheme.TEXT));
        line.setBorder(javax.swing.BorderFactory.createEmptyBorder(1, 0, 1, 0));
        return line;
    }

    private static String timeOf(long epochMillis)
    {
        return java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.systemDefault())
            .toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
    }

    private static String capitalize(String s)
    {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
