package com.gpmanager.ui.bento;

import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;

/**
 * The search palette (SIDEBAR_BENTO.md §13.5): one field over items in the Ledger's scope,
 * sessions and settings. Results are cards grouped by kind; picking one opens the item sheet,
 * the session's Ledger, or the Tools row. The page is fed by {@link Source} — it never touches
 * the engine itself.
 */
public final class SearchPage implements BentoShell.Page
{
    public static final String ID = "search";

    /** One result. */
    public static final class Hit
    {
        public final String group;
        public final Icon icon;
        public final String title;
        @Nullable
        public final String subtitle;
        @Nullable
        public final String right;
        @Nullable
        public final java.awt.Color rightColor;
        public final Runnable open;

        public Hit(String group, Icon icon, String title, @Nullable String subtitle, @Nullable String right,
            @Nullable java.awt.Color rightColor, Runnable open)
        {
            this.group = group;
            this.icon = icon;
            this.title = title;
            this.subtitle = subtitle;
            this.right = right;
            this.rightColor = rightColor;
            this.open = open == null ? () -> { } : open;
        }

        /** Every word of the query must appear in the title or subtitle. */
        public boolean matches(String query)
        {
            String hay = (group + " " + title + " " + (subtitle == null ? "" : subtitle)).toLowerCase(Locale.ROOT);
            for (String word : query.toLowerCase(Locale.ROOT).split("\\s+"))
            {
                if (!word.isEmpty() && !hay.contains(word))
                {
                    return false;
                }
            }
            return true;
        }
    }

    public interface Source
    {
        /** Everything the palette may show for this query; the page filters and groups. */
        List<Hit> hits(String query);

        void back();
    }

    private static final int MAX_PER_GROUP = 6;

    private final Source source;
    private final JPanel bar = new JPanel();
    private final JTextField field = new JTextField();
    private final JPanel body = BentoShell.stack();
    private final JPanel content = new JPanel();
    private String query = "";

    public SearchPage(Source source)
    {
        this.source = source;
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setOpaque(false);
        Controls.IconButton back = new Controls.IconButton("‹", "Back", true);
        back.onClick(source::back);
        bar.add(back);
        bar.add(Box.createHorizontalStrut(6));
        field.setFont(BentoTheme.body());
        field.setForeground(BentoTheme.TEXT);
        field.setCaretColor(BentoTheme.TEXT);
        field.setBackground(BentoTheme.SURFACE);
        field.setBorder(javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createLineBorder(BentoTheme.BORDER),
            javax.swing.BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        field.getAccessibleContext().setAccessibleName("Search items, sessions and settings");
        field.setToolTipText("Items in the Ledger's scope, sessions, settings");
        field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
        {
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
        field.addKeyListener(new java.awt.event.KeyAdapter()
        {
            @Override
            public void keyPressed(java.awt.event.KeyEvent e)
            {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ESCAPE)
                {
                    source.back();
                    e.consume();
                }
                else if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER)
                {
                    openFirst();
                    e.consume();
                }
            }
        });
        bar.add(field);
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, bar.getPreferredSize().height));

        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        BentoShell.stackAdd(body, content);
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

    @Override
    public void onShown()
    {
        javax.swing.SwingUtilities.invokeLater(() ->
        {
            field.requestFocusInWindow();
            field.selectAll();
        });
        render();
    }

    /** Preset the query (a ⌕ from a page passes what it already had). */
    public void open(@Nullable String preset)
    {
        field.setText(preset == null ? "" : preset);
    }

    public String query()
    {
        return query;
    }

    private void changed()
    {
        query = field.getText() == null ? "" : field.getText().trim();
        render();
    }

    private List<Hit> current()
    {
        if (query.isEmpty())
        {
            return Collections.emptyList();
        }
        List<Hit> out = new ArrayList<>();
        for (Hit h : source.hits(query))
        {
            if (h.matches(query))
            {
                out.add(h);
            }
        }
        return out;
    }

    private void openFirst()
    {
        List<Hit> hits = current();
        if (!hits.isEmpty())
        {
            hits.get(0).open.run();
        }
    }

    private void render()
    {
        content.removeAll();
        if (query.isEmpty())
        {
            Tile hint = new Tile();
            hint.text("Type to search", BentoTheme.MUTED);
            hint.paragraph("Items in the Ledger's scope, sessions by name or category, and every Tools row. Enter opens the first result; Esc goes back.",
                BentoTheme.secondary(), BentoTheme.DIM, Tile.interiorWidth());
            hint.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            content.add(hint);
        }
        else
        {
            List<Hit> hits = current();
            if (hits.isEmpty())
            {
                ListBlock empty = new ListBlock().empty("Nothing matches \"" + query + "\"");
                empty.setAlignmentX(JComponent.LEFT_ALIGNMENT);
                content.add(empty);
            }
            java.util.LinkedHashMap<String, List<Hit>> groups = new java.util.LinkedHashMap<>();
            for (Hit h : hits)
            {
                groups.computeIfAbsent(h.group, k -> new ArrayList<>()).add(h);
            }
            for (java.util.Map.Entry<String, List<Hit>> g : groups.entrySet())
            {
                Tile card = new Tile().section(null, g.getKey(), g.getValue().size() > MAX_PER_GROUP ? "first " + MAX_PER_GROUP + " of " + g.getValue().size() : null, null);
                int n = 0;
                for (Hit h : g.getValue())
                {
                    if (n++ == MAX_PER_GROUP)
                    {
                        break;
                    }
                    if (n > 1)
                    {
                        card.gap(4);
                    }
                    NavRow row = new NavRow().icon(h.icon).text(h.title, h.subtitle).onClick(h.open);
                    if (h.right != null)
                    {
                        row.right(h.right, h.rightColor);
                    }
                    row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
                    card.add(row);
                }
                card.setAlignmentX(JComponent.LEFT_ALIGNMENT);
                content.add(card);
                content.add(Box.createVerticalStrut(BentoTheme.density().gap));
            }
        }
        content.revalidate();
        content.repaint();
    }

    /** Test/preview seam. */
    public void searchForPreview(String text)
    {
        field.setText(text);
    }

    /** Test seam: the grouped result count for the current query. */
    public int resultCountForTest()
    {
        return current().size();
    }
}
