package com.gpmanager.ui.bento;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/**
 * The sidebar skeleton (SIDEBAR_BENTO.md §2): rail on top, a page bar per page, then the
 * page body inside a slim-scrollbar scroll pane; a toast overlay floats above everything.
 * Pages register with {@link #addPage}; the shell owns navigation, scroll memory and toasts.
 */
public final class BentoShell extends JPanel
{
    /** A page contributes a page bar (row 2) and a scrollable body. */
    public interface Page
    {
        String id();

        JComponent pageBar();

        JComponent body();

        /** Called when the page becomes visible; refresh from read models here. */
        default void onShown()
        {
        }
    }

    public static final String LIVE = "live";
    public static final String LEDGER = "ledger";
    public static final String SESSIONS = "sessions";
    public static final String INSIGHTS = "insights";
    public static final String TOOLS = "tools";
    /** Gap between page content and the scrollbar. */
    public static final int RIGHT_INSET = 6;

    private final Rail rail = new Rail(Arrays.asList(
        new Rail.Tab(LIVE, "◈", "Live"),
        new Rail.Tab(LEDGER, "≣", "Ledger"),
        new Rail.Tab(SESSIONS, "◷", "Sessions"),
        new Rail.Tab(INSIGHTS, "◑", "Insights"),
        new Rail.Tab(TOOLS, "⚙", "Tools")));
    private final CardLayout barLayout = new CardLayout();
    private final JPanel bars = new JPanel(barLayout);
    private final CardLayout bodyLayout = new CardLayout();
    // Bodies scroll; they must never ask the column for their full content height, or the
    // rail and page bar get squeezed when a page is taller than the sidebar.
    private final JPanel bodies = new JPanel(bodyLayout)
    {
        @Override
        public Dimension getPreferredSize()
        {
            return new Dimension(super.getPreferredSize().width, 120);
        }

        @Override
        public Dimension getMinimumSize()
        {
            return new Dimension(0, 60);
        }
    };
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final Map<String, JScrollPane> scrollPanes = new LinkedHashMap<>();
    private final Map<String, Integer> scrollMemory = new LinkedHashMap<>();
    private final ToastHost toasts = new ToastHost();
    private final JLayeredPane layers = new JLayeredPane();
    private final JPanel column = new JPanel();
    private String current = LIVE;

    public BentoShell()
    {
        super(new BorderLayout());
        setOpaque(true);
        setBackground(BentoTheme.BG);
        setPreferredSize(new Dimension(BentoTheme.OWNED_WIDTH, 600));

        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setOpaque(false);
        column.setBorder(BorderFactory.createEmptyBorder(6, 8, 0, 2));
        rail.setAlignmentX(LEFT_ALIGNMENT);
        bars.setOpaque(false);
        bars.setAlignmentX(LEFT_ALIGNMENT);
        bars.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, RIGHT_INSET + BentoTheme.SCROLLBAR_WIDTH));
        bodies.setOpaque(false);
        bodies.setAlignmentX(LEFT_ALIGNMENT);
        column.add(rail);
        column.add(bars);
        column.add(bodies);

        layers.setLayout(null);
        layers.add(column, JLayeredPane.DEFAULT_LAYER);
        layers.add(toasts, JLayeredPane.PALETTE_LAYER);
        add(layers, BorderLayout.CENTER);

        rail.onSelect(this::show);
        addComponentListener(new java.awt.event.ComponentAdapter()
        {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e)
            {
                layoutLayers();
            }
        });
    }

    private void layoutLayers()
    {
        int w = getWidth();
        int h = getHeight();
        column.setBounds(0, 0, w, h);
        toasts.setBounds(0, 0, w, h);
        column.revalidate();
        toasts.relayout();
    }

    @Override
    public void doLayout()
    {
        super.doLayout();
        layers.setBounds(0, 0, getWidth(), getHeight());
        layoutLayers();
    }

    public void addPage(Page page)
    {
        pages.put(page.id(), page);
        JComponent bar = page.pageBar();
        bar.setAlignmentX(LEFT_ALIGNMENT);
        bars.add(bar, page.id());
        // The card container must hug its tallest bar or BoxLayout hands it spare height.
        bars.setMaximumSize(new Dimension(Integer.MAX_VALUE, bars.getPreferredSize().height));

        JComponent body = page.body();
        body.setAlignmentX(LEFT_ALIGNMENT);
        JPanel north = new ScrollableColumn();
        north.setOpaque(false);
        // Keep content clear of the slim scrollbar and give the right edge the same air as the left.
        north.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, RIGHT_INSET));
        north.add(body, BorderLayout.NORTH);
        JScrollPane scroll = SlimScrollBarUI.install(new JScrollPane(north));
        scrollPanes.put(page.id(), scroll);
        bodies.add(scroll, page.id());
    }

    public void show(String id)
    {
        if (!pages.containsKey(id))
        {
            return;
        }
        JScrollPane leaving = scrollPanes.get(current);
        if (leaving != null)
        {
            scrollMemory.put(current, leaving.getVerticalScrollBar().getValue());
        }
        current = id;
        rail.select(id);
        barLayout.show(bars, id);
        bodyLayout.show(bodies, id);
        Page page = pages.get(id);
        page.onShown();
        JScrollPane entering = scrollPanes.get(id);
        Integer remembered = scrollMemory.get(id);
        if (entering != null && remembered != null)
        {
            SwingUtilities.invokeLater(() -> entering.getVerticalScrollBar().setValue(remembered));
        }
    }

    public String currentPage()
    {
        return current;
    }

    public Rail rail()
    {
        return rail;
    }

    public ToastHost toasts()
    {
        return toasts;
    }

    /** Viewport view that always matches the viewport width so nothing is ever clipped horizontally. */
    private static final class ScrollableColumn extends JPanel implements javax.swing.Scrollable
    {
        ScrollableColumn()
        {
            super(new BorderLayout());
        }

        @Override
        public Dimension getPreferredScrollableViewportSize()
        {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle visibleRect, int orientation, int direction)
        {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle visibleRect, int orientation, int direction)
        {
            return Math.max(24, visibleRect.height - 24);
        }

        @Override
        public boolean getScrollableTracksViewportWidth()
        {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight()
        {
            return false;
        }
    }

    /** Vertical stack with the standard gap between children — the body of every page. */
    public static JPanel stack()
    {
        JPanel panel = new JPanel();
        // One gap between visible cards only: a hidden card leaves no hole.
        panel.setLayout(new GapStack(BentoTheme.density().gap));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        return panel;
    }

    /** Add to a page stack; the layout supplies the gap. */
    public static void stackAdd(JPanel stack, Component component)
    {
        if (component instanceof JComponent)
        {
            ((JComponent) component).setAlignmentX(LEFT_ALIGNMENT);
        }
        stack.add(component);
    }
}
