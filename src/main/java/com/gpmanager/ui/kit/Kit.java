package com.gpmanager;

import static java.awt.RenderingHints.*;
import static com.gpmanager.Hero.*;

import static com.gpmanager.Semantics.Table.*;
import static com.gpmanager.Kit.Tone.MARKET;
import static com.gpmanager.Kit.SELECTED;
import static com.gpmanager.Shell.*;
import static java.lang.String.valueOf;

import static javax.swing.SwingUtilities.isDescendingFrom;
import static javax.swing.SwingUtilities.getAncestorOfClass;
import static javax.swing.SwingUtilities.invokeLater;
import static javax.swing.SwingUtilities.convertPoint;
import static javax.swing.SwingUtilities.isEventDispatchThread;

import com.gpmanager.Table.Row;
import static com.gpmanager.Kit.TEXT;
import static java.awt.BorderLayout.*;
import static javax.swing.BorderFactory.*;
import static net.runelite.client.ui.ColorScheme.*;
import static java.lang.String.*;
import static java.awt.Cursor.*;
import static javax.swing.KeyStroke.*;
import javax.swing.border.*;
import java.math.BigDecimal;
import static com.gpmanager.Kit.*;
import static com.gpmanager.Phrases.*;
import static java.lang.Math.*;
import static java.util.Collections.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.function.*;
import javax.swing.*;
import javax.swing.Timer;
import lombok.*;
import net.runelite.client.plugins.config.ConfigPlugin;
import net.runelite.client.ui.*;
import net.runelite.client.ui.components.materialtabs.*;
import static java.util.Locale.*;
import java.text.NumberFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import static com.gpmanager.HudTray.State.*;
import static java.awt.Color.*;
import static com.gpmanager.GameData.*;
import static com.gpmanager.Fmt.*;
import static com.gpmanager.Kit.Tone.*;
import static com.gpmanager.SafeMath.*;
import com.gpmanager.HudFrame.*;
import com.gpmanager.GpManagerConfig.TextSize;
import net.runelite.client.ui.overlay.*;
import static com.gpmanager.TransactionType.*;
import static com.gpmanager.Kit.Tone.GAIN;
import static java.lang.Math.abs;
import static com.gpmanager.FlowFilters.*;
import static com.gpmanager.ChargeCheck.digits;
import static com.gpmanager.MarketText.*;
import com.gpmanager.LedgerData.*;
import com.gpmanager.Semantics.*;
import com.gpmanager.Table.Menu;
import net.runelite.client.ui.components.*;
import javax.swing.event.*;
import static com.gpmanager.LedgerData.Scope.*;
import com.gpmanager.LedgerPage.CorrectionOutcome;
import java.awt.geom.*;
import com.gpmanager.LiveSnapshot.Recent;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.grandexchange.GrandExchangePlugin;
import java.io.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import net.runelite.client.game.*;
import org.slf4j.*;
import net.runelite.client.util.*;
import java.awt.datatransfer.StringSelection;
import com.gpmanager.GrindsData.*;
import com.gpmanager.LedgerData.Entry;
import com.gpmanager.SavedState.*;
import net.runelite.api.*;
import net.runelite.api.events.MenuOptionClicked;
import com.gpmanager.LedgerData.Detail;

/**
 * Palette, fonts and small builders shared by the sidebar and HUD+. Colour always means a category,
 * never a sign.
 */
class Kit {
    static final Color PANEL = DARK_GRAY_COLOR, CARD = DARKER_GRAY_COLOR, LINE = BORDER_COLOR;
    static final Color SELECTED = DARKER_GRAY_HOVER_COLOR, TEXT = TEXT_COLOR, LABEL = LIGHT_GRAY_COLOR;
    static final Color OFF = MEDIUM_GRAY_COLOR, ACCENT = BRAND_ORANGE;
    static final int ROW = 20;

    /** The item's sprite as an icon, cached per item until the sprite image changes. */
    static ImageIcon icon(Map<Integer, ImageIcon> cache, Function<Integer, BufferedImage> sprites, int id) {
        var image = sprites.apply(id);
        if (image == null) return null;
        var icon = cache.get(id);
        if (icon == null || icon.getImage() != image) {
            icon = new ImageIcon(image);
            cache.put(id, icon);
        }
        return icon;
    }

    /** Icon rows: every sprite is cropped and fitted into one ICON_BOX square (owner 1.1). */
    static final int ICON_ROW = 28;

    static final int ICON_WIDTH = 24, ICON_BOX = 24, HEADER = 22, GAP = 6;

    /** Text width inside a sheet, which sits inside the panel's own padding. */
    static final int SHEET_TEXT = 165, VALUE_WIDTH = 46;

    /** What a value is, which decides its colour. */
    @RequiredArgsConstructor
    enum Tone {
        GAIN(GRAND_EXCHANGE_PRICE), SUPPLY(GRAND_EXCHANGE_ALCH), LOSS(new Color(255, 122, 107)),
        MARKET(new Color(90, 180, 255)), REVIEW(PROGRESS_INPROGRESS_COLOR), PLAIN(Color.WHITE),
        DIM(LIGHT_GRAY_COLOR);
        final Color color;

        /** Gain above zero, loss below it. */
        static Tone sign(long value) {
            return value > 0 ? GAIN : value < 0 ? LOSS : PLAIN;
        }

        /** A booked category's tone; fees read as losses, audit-only rows as dim. */
        static Tone of(Contribution.Category category) {
            if (category == null) return DIM;
            switch (category) {
                case GAIN:
                    return GAIN;
                case SUPPLY:
                    return SUPPLY;
                case MARKET:
                    return MARKET;
                case LOSS:
                    return LOSS;
                default:
                    return DIM;
            }
        }
    }

    static JMenuItem item(String text, Runnable action) {
        return item(text, true, action);
    }

    static JMenuItem item(String text, boolean enabled, Runnable action) {
        var item = new JMenuItem(text);
        item.setEnabled(enabled);
        item.addActionListener(e -> action.run());
        return item;
    }

    /** A transparent panel with the caller's unchanged layout. */
    static JPanel transparent(LayoutManager layout) {
        var panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }

    /** A left-aligned transparent row of controls. */
    static JPanel row(Component... components) {
        var row = transparent(new FlowLayout(FlowLayout.LEFT, 2, 0));
        for (var component : components) row.add(component);
        return row;
    }

    /** Text is Inter (GE Uncut direction); the bundled OFL files fall back to RuneLite's fonts. */
    static Font small() {
        return SMALL_TXT;
    }

    static Font body() {
        return BODY_TXT;
    }

    static Font bold() {
        return BOLD_TXT;
    }

    private static final Font BODY_TXT =
            loaded("/com/gpmanager/fonts/Inter-Regular.ttf", 12.5f, FontManager.getRunescapeFont());
    private static final Font SMALL_TXT = BODY_TXT.deriveFont(11f);
    private static final Font BOLD_TXT = loaded("/com/gpmanager/fonts/Inter-SemiBold.ttf", 12.5f,
                    FontManager.getRunescapeBoldFont());

    /** A bundled resource face when the build ships it; else the RuneLite font. */
    private static Font loaded(String resource, float size, Font fallback) {
        try (InputStream in = Kit.class.getResourceAsStream(resource)) {
            if (in != null) return Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(size);
        } catch (Exception unavailable) {
            // A build without the font bundle keeps RuneLite's own fonts.
        }
        return fallback;
    }

    /**
     * One page toolbar band: controls left, actions right; Ledger and Grinds share the same shape.
     */
    static void headerBand(JPanel holder, JComponent control, JComponent... actions) {
        holder.setOpaque(false);
        pad(holder, 2, 4, 2, 2);
        holder.add(control, CENTER);
        holder.add(row(actions), EAST);
    }

    /** Refit bounded text when its allocated width changes, without rebuilding the page. */
    static void onResize(JComponent component, Runnable resized) {
        component.addComponentListener(new ComponentAdapter() {
                    public void componentResized(ComponentEvent event) { resized.run(); }
                });
    }

    /** The empty table line, with the existing font, tone and inset dimensions. */
    static JLabel emptyLine(String text) {
        var label = label(text, small(), LABEL);
        pad(label, 3, 6, 3, 6);
        return label;
    }

    /** Table and sheet page count; retain the original integer boundary behavior. */
    static int pageCount(int count, int perPage) {
        return max(1, (count + perPage - 1) / perPage);
    }

    /** Clamp a requested table/sheet page to the existing nonempty page range. */
    static int clampPage(int page, int pages) {
        return max(0, min(page, pages - 1));
    }

    /** Apply pager text and navigation state; callers retain their different text formats. */
    static void applyPager(JLabel label, JButton previous, JButton next, int page, int pages, String text) {
        label.setText(text);
        previous.setEnabled(page > 0);
        next.setEnabled(page < pages - 1);
    }

    /** The same ordered row/Grind menu: labels, listeners and Swing defaults remain intact. */
    static JPopupMenu menuOf(List<Table.Menu> items) {
        var menu = new JPopupMenu();
        for (var item : items) menu.add(item(item.label, () -> item.action.run()));
        return menu;
    }

    /**
     * The text shortened with "…" to fit a pixel budget; the full text belongs in the tooltip.
     */
    static String fit(JComponent label, String text, int budget) {
        if (text == null) return "";
        var metrics = label.getFontMetrics(label.getFont());
        if (budget <= 0 || metrics.stringWidth(text) <= budget) return text;
        int ellipsis = metrics.stringWidth("…"), low = 0, high = text.length();
        while (low < high) {
            int middle = low + high + 1 >>> 1;
            if (metrics.stringWidth(text.substring(0, middle)) <= budget - ellipsis) low = middle;
            else high = middle - 1;
        }
        return text.substring(0, low) + "…";
    }

    /**
     * Fits text by shrinking its font, down to 10 pt, before cutting it with an ellipsis, so a
     * long name stays readable (owner 1.5.2); the caller keeps the full text in a tooltip.
     */
    static String fitShrinking(JLabel label, String text, Font font, int budget) {
        Font size = font;
        while (budget > 0 && size.getSize2D() > 10f
                && label.getFontMetrics(size).stringWidth(string(text)) > budget)
            size = size.deriveFont(size.getSize2D() - 0.5f);
        label.setFont(size);
        return fit(label, text, budget);
    }

    static JLabel label(String text, Font font, Color color) {
        var label = new JLabel(string(text));
        // Symbols the RuneScape font cannot draw fall back to the platform face at the same size.
        label.setFont(label.getText().isEmpty() || font.canDisplayUpTo(label.getText()) < 0 ? font
                        : new Font(Font.DIALOG, font.getStyle(), font.getSize()));
        label.setForeground(color);
        return label;
    }

    static JLabel column(String text, Font font, Color color, int width, int height) {
        var label = label(text, font, color);
        label.setHorizontalAlignment(SwingConstants.RIGHT);
        var size = new Dimension(width, height);
        label.setPreferredSize(size);
        label.setMinimumSize(size);
        label.setMaximumSize(size);
        return label;
    }

    /**
     * Full-width children at their preferred heights, {@code gap} apart; hidden ones take no room.
     */
    static JPanel stack(Color background, int gap) {
        var panel = new JPanel(stackLayout(gap));
        panel.setBackground(background);
        return panel;
    }

    static LayoutManager stackLayout(int gap) {
        return new LayoutManager() {
            public void addLayoutComponent(String name, Component comp) {}

            public void removeLayoutComponent(Component comp) {}

            public Dimension preferredLayoutSize(Container parent) {
                var in = parent.getInsets();
                int width = 0, height = 0;
                for (var child : parent.getComponents()) {
                    if (child.isVisible()) {
                        var size = child.getPreferredSize();
                        width = max(width, size.width);
                        height += (height == 0 ? 0 : gap) + size.height;
                    }
                }
                return new Dimension(width + in.left + in.right, height + in.top + in.bottom);
            }

            public Dimension minimumLayoutSize(Container parent) {
                return preferredLayoutSize(parent);
            }

            public void layoutContainer(Container parent) {
                var in = parent.getInsets();
                int y = in.top, width = parent.getWidth() - in.left - in.right;
                for (var child : parent.getComponents()) {
                    if (child.isVisible()) {
                        int height = child.getPreferredSize().height;
                        child.setBounds(in.left, y, width, height);
                        y += height + gap;
                    }
                }
            }
        };
    }

    /** A dim note that wraps to the sidebar width. */
    static JLabel note(String text, Color color) {
        return note(text, color, 190);
    }

    /** Wrapped small text; a sheet's narrower box uses a smaller width so nothing is cut. */
    static JLabel note(String text, Color color, int width) {
        String safe = (string(text)).replace("&", "&amp;").replace("<", "&lt;");
        var label = label("<html><div style='width:" + width + "px'>" + safe + "</div></html>", small(), color);
        pad(label, 0, GAP, 0, GAP);
        return label;
    }

    /** Local component keys only; never hooks the game canvas or a window dispatcher. */
    static void key(JComponent component, String stroke, Runnable action) {
        component.getInputMap().put(getKeyStroke(stroke), stroke);
        component.getActionMap().put(stroke, new AbstractAction() {
            public void actionPerformed(ActionEvent event) {
                if (component.isEnabled() && component.isShowing()) action.run();
            }
        });
    }

    static void focus(JComponent component) {
        if (Boolean.TRUE.equals(component.getClientProperty("gp-focus"))) return;
        component.putClientProperty("gp-focus", true);
        component.setFocusable(true);
        if (component instanceof AbstractButton) {
            ((AbstractButton) component).setFocusPainted(false);
            var map = component.getInputMap();
            for (String prefix : new String[]{"", "released "})
                map.put(getKeyStroke(prefix + "ENTER"), map.get(getKeyStroke(prefix + "SPACE")));
            component.setName(((AbstractButton) component).getText());
        }
        component.addHierarchyListener(event -> {
            var owner = getAncestorOfClass(Shell.class, component);
            if (owner != null) component.putClientProperty("gp-shell", owner);
        });
        component.addFocusListener(new FocusAdapter() {
            public void focusGained(FocusEvent event) {
                component.scrollRectToVisible(new Rectangle(component.getSize()));
                component.repaint();
            }
            public void focusLost(FocusEvent event) {
                component.putClientProperty("gp-armed", false);
                component.repaint();
            }
        });
        for (String stroke : new String[]{"shift F10", "CONTEXT_MENU"}) key(component, stroke, () -> {
            var menu = component.getComponentPopupMenu();
            if (menu != null) menu.show(component, 0, component.getHeight());
        });
    }

    static void keyboard(JComponent component, String name, Runnable action) {
        focus(component);
        component.setName(name);
        component.getAccessibleContext().setAccessibleName(name);
        for (String stroke : new String[]{"ENTER", "SPACE"}) {
            key(component, stroke, () -> component.putClientProperty("gp-armed", true));
            key(component, "released " + stroke, () -> {
                boolean armed = Boolean.TRUE.equals(component.getClientProperty("gp-armed"));
                component.putClientProperty("gp-armed", false);
                if (armed) action.run();
            });
        }
    }

    static JComponent focused(Component component) {
        if (component.isFocusOwner() && component instanceof JComponent) return (JComponent) component;
        if (component instanceof Container) for (Component child : ((Container) component).getComponents()) {
            var found = focused(child);
            if (found != null) return found;
        }
        return null;
    }

    static JComponent focusTarget(Component component, String name) {
        if (component instanceof JComponent && component.isVisible() && component.isEnabled()) {
            var value = (JComponent) component;
            if (value.isFocusable() && (Boolean.TRUE.equals(value.getClientProperty("gp-focus")) || value instanceof JTextField)
                    && (name == null || name.equals(value.getName()))) return value;
            if (component instanceof Container) for (Component child : ((Container) component).getComponents()) {
                var found = focusTarget(child, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    static void restoreFocus(JComponent root, JComponent previous) {
        if (previous == null || !root.isShowing()) return;
        var target = focusTarget(root, previous.getName());
        if (target == null) target = focusTarget(root, null);
        if (target != null) target.requestFocusInWindow();
    }

    static void moveFocus(JComponent component, int offset) {
        var parent = component.getParent();
        if (parent == null) return;
        int start = parent.getComponentZOrder(component) + offset;
        if (offset < -1) start = 0;
        if (offset > 1) start = parent.getComponentCount() - 1;
        for (int i = start; i >= 0 && i < parent.getComponentCount(); i += offset < 0 ? -1 : 1) {
            var next = parent.getComponent(i);
            if (next instanceof JComponent && Boolean.TRUE.equals(((JComponent) next).getClientProperty("gp-focus"))
                    && next.isVisible() && next.isEnabled() && next.isFocusable()) {
                if (next instanceof MaterialTab) ((MaterialTabGroup) parent).select((MaterialTab) next);
                next.requestFocusInWindow();
                return;
            }
        }
    }

    /** Cancel only this panel's held actions and popup menus at an owner boundary. */
    static void cancelKeys(JComponent root) {
        var manager = MenuSelectionManager.defaultManager();
        for (var element : manager.getSelectedPath()) if (element instanceof JPopupMenu) {
            var invoker = ((JPopupMenu) element).getInvoker();
            if (invoker != null && (isDescendingFrom(invoker, root)
                    || invoker instanceof JComponent && ((JComponent) invoker).getClientProperty("gp-shell") == root)) {
                manager.clearSelectedPath();
                break;
            }
        }
        disarm(root);
    }

    static void disarm(Component component) {
        if (component instanceof JComponent && Boolean.TRUE.equals(((JComponent) component).getClientProperty("gp-focus"))) {
            ((JComponent) component).putClientProperty("gp-armed", false);
            if (component instanceof AbstractButton) {
                var model = ((AbstractButton) component).getModel();
                model.setArmed(false);
                model.setPressed(false);
            }
        }
        if (component instanceof Container) for (var child : ((Container) component).getComponents()) disarm(child);
    }

    /** A flat text button; {@code primary} draws the brand-orange border. */
    static JButton button(String text, boolean primary, Runnable action) {
        var button = new JButton(text);
        button.setFont(small());
        button.setForeground(TEXT);
        button.setBackground(CARD);
        button.setBorder(createCompoundBorder(createLineBorder(primary ? ACCENT : LINE),
                        createEmptyBorder(3, 8, 3, 8)));
        button.addActionListener(e -> action.run());
        focus(button);
        return button;
    }

    /** Padding of {@code top, left, bottom, right}. */
    static void pad(JComponent component, int top, int left, int bottom, int right) {
        component.setBorder(createEmptyBorder(top, left, bottom, right));
    }

    /** The sprite's visible pixels: transparent margins cut away. */
    static Rectangle visible(BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight();
        int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
        int left = w, top = h, right = -1, bottom = -1;
        for (int i = 0; i < argb.length; i++) {
            if (argb[i] >>> 24 != 0) {
                left = min(left, i % w);
                right = max(right, i % w);
                top = min(top, i / w);
                bottom = i / w;
            }
        }
        return right < 0 ? new Rectangle(w, h) : new Rectangle(left, top, right - left + 1, bottom - top + 1);
    }

    /**
     * A sprite cropped to its visible pixels, scaled down (never up, so pixel art stays sharp) to
     * fit a {@code box} square, and centred: every icon sits the same way in every row.
     */
    static BufferedImage fitIcon(BufferedImage image, int box) {
        var crop = visible(image);
        double scale = min(1d, box / (double) max(crop.width, crop.height));
        int w = max(1, (int) round(crop.width * scale)), h = max(1, (int) round(crop.height * scale));
        var out = new BufferedImage(box, box, BufferedImage.TYPE_INT_ARGB);
        var g = out.createGraphics();
        g.setRenderingHint(KEY_INTERPOLATION, scale < 1d ? VALUE_INTERPOLATION_BILINEAR
                        : VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        int x = (box - w) / 2, y = (box - h) / 2;
        g.drawImage(image, x, y, x + w, y + h, crop.x, crop.y, crop.x + crop.width, crop.y + crop.height, null);
        g.dispose();
        return out;
    }

    /** JetBrains Mono for every number; the resource falls back to the platform face. */
    static Font numbers(float size) {
        return NUMBERS.deriveFont(size);
    }

    private static final Font NUMBERS = loaded("/com/gpmanager/fonts/JetBrainsMono-Regular.ttf", 12f,
                    new Font(Font.MONOSPACED, Font.PLAIN, 12));

}

/**
 * The key-value table (SIDEBAR_SPEC.md §2): an optional 22 px header, then 20 px lines with the key
 * on the left and the value right-aligned in its tone. Hovering a value shows its exact form.
 */
class KeyValue extends JPanel {
    JPanel body = stack(CARD, 0);

    KeyValue(String title) {
        super(new BorderLayout());
        setBackground(CARD);
        if (title != null) {
            var header = label(title, small(), LABEL);
            header.setBorder(createCompoundBorder(createMatteBorder(0, 0, 1, 0, LINE),
                            createEmptyBorder(0, 6, 0, 6)));
            header.setPreferredSize(new Dimension(0, HEADER));
            add(header, NORTH);
        }
        add(body, CENTER);
    }

    KeyValue clear() {
        body.removeAll();
        revalidate();
        repaint();
        return this;
    }

    KeyValue put(String key, String value, Tone tone) {
        return put(key, value, tone, null);
    }

    /** Adds a line; {@code exact} is the value's hover text, such as "−184,612 gp". */
    KeyValue put(String key, String value, Tone tone, String exact) {
        var line = transparent(new BorderLayout(GAP, 0));
        pad(line, 0, 6, 0, 6);
        line.setPreferredSize(new Dimension(0, ROW));
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW));
        // The key keeps its width; a value too long for the rest ends in "…" and hovers in full.
        line.add(label(key, small(), LABEL), WEST);
        char first = value.isEmpty() ? ' ' : value.charAt(0);
        boolean numeral = Character.isDigit(first) || first == '−' || first == '-' || first == '+';
        var shown = label(value, numeral ? numbers(11.5f) : body(), (tone == null ? PLAIN : tone).color);
        shown.setHorizontalAlignment(SwingConstants.RIGHT);
        shown.setToolTipText(exact == null ? value : exact);
        line.add(shown, CENTER);
        body.add(line);
        body.setPreferredSize(new Dimension(0, body.getComponentCount() * ROW));
        revalidate();
        return this;
    }

    int lines() {
        return body.getComponentCount();
    }
}

/**
 * The hero band (SIDEBAR_SPEC.md §2): a 28 px status line (dot, name, status word, quiet count
 * icons, controls), the big line (Net left, rate and clock right) and one or two strips of cells.
 * Values update in place each tick; components are rebuilt only when the shape changes, so hover
 * and open menus survive.
 */
class Hero extends JPanel {
    /** One strip cell or status icon. Equality covers what is drawn. */
    @EqualsAndHashCode(exclude = "open")
    static class Cell {
        String label, value;
        Tone tone;
        String tip;
        Runnable open;

        Cell(String label, String value, Tone tone, String tip, Runnable open) {
            this.label = string(label);
            this.value = string(value);
            this.tone = tone == null ? PLAIN : tone;
            this.tip = string(tip);
            this.open = open;
        }

    }

    JLabel dot = label("●", body(), OFF), name = label("", bold(), Color.WHITE), word = label("", small(), LABEL);
    JPanel icons = transparent(new FlowLayout(FlowLayout.RIGHT, 0, 0));

    /** Net in the GP/h face at its own size: RuneLite's pixel fonts blur when scaled. */
    JLabel net = label("", bold(), TEXT);

    JLabel rate = label("", bold(), TEXT), clock = label("", small(), LABEL);

    /**
     * A slim line under the Net: the run's whole total while Live counts fresh, and Risk where you
     * can be attacked (owner 1.5.2: out of the header). Hidden when it has nothing to say.
     */
    JPanel under = transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));

    JPanel strips = stack(CARD, 0), statusRight = transparent(new GridBagLayout());
    JPanel big = transparent(new BorderLayout(GAP, 0));
    List<Cell> drawnIcons = emptyList();
    List<List<Cell>> drawn = emptyList();
    Runnable onDot;

    Hero() {
        super(new BorderLayout());
        setBackground(CARD);
        net.setFont(numbers(16f));
        rate.setFont(numbers(12f));
        var status = transparent(new BorderLayout(2, 0));
        status.setPreferredSize(new Dimension(0, 28));
        pad(status, 0, 4, 0, 4);
        var left = transparent(new BorderLayout(2, 0));
        left.add(dot, WEST);
        left.add(name, CENTER);
        status.add(left, CENTER);
        word.setFont(small().deriveFont(10f));
        word.setBorder(createCompoundBorder(createLineBorder(LINE),
                        createEmptyBorder(2, 3, 2, 3)));
        statusRight.add(icons);
        statusRight.add(word);
        status.add(statusRight, EAST);
        clickable(dot, "Pause or resume tracking", () -> {
                    if (onDot != null) onDot.run();
                });
        pad(big, 0, 6, 4, 6);
        big.add(net, CENTER);
        var pace = new JPanel();
        pace.setLayout(new BoxLayout(pace, BoxLayout.Y_AXIS));
        pace.setOpaque(false);
        rate.setAlignmentX(RIGHT_ALIGNMENT);
        clock.setAlignmentX(RIGHT_ALIGNMENT);
        pace.add(rate);
        pace.add(clock);
        big.add(pace, EAST);
        var top = transparent(new BorderLayout());
        top.add(status, NORTH);
        top.add(big, CENTER);
        pad(under, 0, 0, 4, 0);
        under.setVisible(false);
        top.add(under, SOUTH);
        add(top, NORTH);
        add(strips, CENTER);
    }

    /**
     * The dot's colour says the state; clicking it runs {@code toggle} (pause / resume) when set.
     */
    Hero status(Color dotColor, String dotTip, String title, String statusWord, Runnable toggle) {
        dot.setForeground(dotColor);
        dot.setToolTipText(dotTip);
        dot.setCursor(toggle == null ? getDefaultCursor() : getPredefinedCursor(HAND_CURSOR));
        onDot = toggle;
        dot.setFocusable(toggle != null);
        String state = string(statusWord);
        word.setForeground(statusColor(state));
        word.setText(state.isEmpty() ? "" : state.substring(0, 1) + state.substring(1).toLowerCase(ROOT));
        word.setVisible(!word.getText().isEmpty());
        String text = string(title);
        name.setText(fitShrinking(name, text, bold(), nameBudget()));
        name.setToolTipText(text.isEmpty() ? null : text);
        return this;
    }

    static Color statusColor(String state) {
        switch (state) {
            case "AWAY": return ACCENT;
            case "PAUSED": return REVIEW.color;
            case "LOGGED OUT": return LOSS.color;
            case "RECONNECTING": return MARKET.color;
            case "CALIBRATING":
            case "COMPLETE": return GAIN.color;
            default: return state.startsWith("RESUMING") ? GAIN.color : LABEL;
        }
    }

    /** Pixel budget for the name after the dot, the status word and the right-hand controls. */
    int nameBudget() {
        int width = getWidth();
        if (width <= 0 && getParent() != null) width = getParent().getWidth();
        if (width <= 0) width = 225;
        int used = 12 + dot.getPreferredSize().width + statusRight.getPreferredSize().width;
        return max(40, width - used);
    }

    /** Quiet count icons; only those above zero should be passed. */
    Hero icons(List<Cell> value) {
        List<Cell> next = value == null ? emptyList() : value;
        if (next.equals(drawnIcons)) return this;
        drawnIcons = new ArrayList<>(next);
        var previous = focused(icons);
        icons.removeAll();
        for (Cell cell : next) {
            var icon = label(cell.value, small(), cell.tone.color);
            // Keep the quiet status counts separated without widening the activity header.
            pad(icon, 0, 4, 0, 0);
            icon.setToolTipText(cell.tip);
            clickable(icon, "Open " + cell.label, cell.open);
            icons.add(icon);
        }
        icons.revalidate();
        restoreFocus(icons, previous);
        return this;
    }

    /** The big line: Net (tone by sign), its exact hover, the rate and the active clock. */
    Hero big(String netText, Tone tone, String exact, String rateText, boolean rateReady, String clockText) {
        big.setVisible(netText != null);
        if (netText == null) return this;
        // RuneLite's font has no true minus sign (U+2212); a hyphen reads the same.
        net.setText(netText.replace('−', '-'));
        net.setForeground(tone.color);
        net.setToolTipText(exact);
        rate.setText(rateText);
        rate.setForeground(rateReady ? TEXT : LABEL);
        clock.setText(clockText);
        return this;
    }

    /** One or two strips of 2-4 cells; a null or empty list hides the strips. */
    Hero strips(List<List<Cell>> value) {
        List<List<Cell>> next = value == null ? emptyList() : value;
        if (next.equals(drawn)) return this;
        drawn = new ArrayList<>(next);
        var previous = focused(strips);
        strips.removeAll();
        for (var strip : next) strips.add(strip(strip));
        strips.revalidate();
        restoreFocus(strips, previous);
        return this;
    }

    /**
     * The money strip Live and a Grind's detail share: Gains, then Supply and Loss when the split
     * is known, else the truthful Costs total, never invented parts.
     */
    static List<Cell> money(long income, long supplies, long lost, long cost, boolean split, Runnable open) {
        var cells = new ArrayList<Cell>();
        cells.add(new Cell("GAINS", signed(income), GAIN, msg("loot-other") + exact(income) + " gp",
                        open));
        if (split) {
            cells.add(new Cell("SUPPLY", signed(-supplies), SUPPLY,
                            msg("food-potions") + exact(supplies) + " gp"
                                    + (income > 0 && supplies >= 0 ? format(ROOT,
                                            " · %.1f%% of booked Gains", 100d * supplies / income) : ""), open));
            cells.add(new Cell("LOSS", signed(-lost), LOSS,
                            msg("deaths-tax") + exact(lost) + " gp", open));
        } else
            cells.add(new Cell("COSTS", signed(-cost), LOSS, msg("everything-spent"), open));
        return cells;
    }

    /** One row of labelled cells, as the hero and the Grinds All time card show them. */
    static JPanel strip(List<Cell> cells) {
        var row = new JPanel(new GridLayout(1, max(1, cells.size()), 1, 0));
        row.setBackground(LINE);
        row.setBorder(createMatteBorder(1, 0, 0, 0, LINE));
        for (var cell : cells) {
            var box = box(cell);
            if (cells.size() == 4) pad(box, 2, 2, 3, 2);
            row.add(box);
        }
        return row;
    }

    /** One strip cell: the label over its value, on the card tone. */
    static JPanel box(Cell cell) {
        var box = new JPanel(new BorderLayout());
        box.setBackground(CARD);
        pad(box, 2, 5, 3, 5);
        box.add(label(cell.label, small(), LABEL), NORTH);
        box.add(label(cell.value, numbers(11f), cell.tone.color), CENTER);
        box.setToolTipText(cell.tip);
        clickable(box, "View " + cell.label, cell.open);
        return box;
    }

    static void clickable(JComponent component, String name, Runnable open) {
        if (open == null) return;
        keyboard(component, name, open);
        component.setCursor(getPredefinedCursor(HAND_CURSOR));
        component.addMouseListener(new MouseAdapter() {
                    public void mouseClicked(MouseEvent e) {
                        if (e.getButton() == MouseEvent.BUTTON1) open.run();
                    }
                });
    }
}

/**
 * The paged table every list is built from (SIDEBAR_SPEC.md §2): a 22 px header with an optional
 * fold chevron, "LABEL · COUNT", a total in its tone and {@code ‹ 1/4 ›}; 20 px rows with the
 * quantity and value in fixed right-aligned columns. A table keeps its page until {@link
 * #resetPage()} (a scope change).
 */
class Table extends JPanel {
    /** One row. Equality covers what is drawn, so an unchanged page is not rebuilt. */
    @EqualsAndHashCode(exclude = "open")
    static class Row {
        String key;
        Icon icon;
        String name, qty, value;
        Tone tone;
        String tip, exact;
        boolean selected;
        Runnable open;
        List<Menu> menu;

        /** The name's colour when it is not plain text: a Ground Items-highlighted drop. */
        Color color;

        Row(String key, Icon icon, String name, String qty, String value, Tone tone, String tip, String exact,
                boolean selected, Runnable open, List<Menu> menu) {
            this.key = string(key);
            this.icon = icon;
            this.name = string(name);
            this.qty = string(qty);
            this.value = string(value);
            this.tone = tone == null ? DIM : tone;
            this.tip = string(tip);
            this.exact = string(exact);
            this.selected = selected;
            this.open = open;
            this.menu = menu == null ? emptyList() : menu;
        }

    }

    /** A right-click menu entry. */
    @AllArgsConstructor
    @EqualsAndHashCode(exclude = "action")
    static class Menu {
        String label;
        Runnable action;
    }

    JPanel header = new JPanel(new BorderLayout(GAP, 0));
    JLabel arrow = label("▾", small(), LABEL), title = label("", small(), LABEL);
    JLabel total = label("", numbers(10.5f), LABEL);
    JButton previous = glyphButton("‹"), next = glyphButton("›");
    JLabel counter = label("", numbers(10f), LABEL);

    /** One row, centred on the title's line so "‹ 1/2 ›" sits level with it. */
    JPanel right = transparent(new GridBagLayout());

    JPanel body = stack(CARD, 0);

    /** A small control beside the title, such as the Losses view picker (owner 1.1). */
    JComponent picker;

    JPanel left = transparent(new BorderLayout(4, 0));
    JComponent action;
    List<Row> rows = emptyList(), drawn = null;
    String emptyText = "No entries";
    int size = 15, minRows, page;
    boolean foldable;
    @Getter boolean folded;

    /** Rows per page follow the room left in the sidebar (3 to 15) instead of a fixed count. */
    boolean fill, plain;

    Table(String title) {
        super(new BorderLayout());
        setBackground(CARD);
        this.title.setText(title);
        header.setBackground(CARD);
        header.setBorder(createCompoundBorder(createMatteBorder(0, 0, 1, 0, LINE),
                        createEmptyBorder(0, 6, 0, 2)));
        header.setPreferredSize(new Dimension(0, HEADER));
        left.add(arrow, WEST);
        left.add(this.title, CENTER);
        header.add(left, CENTER);
        header.add(right, EAST);
        arrow.setVisible(false);
        arrow.setCursor(getPredefinedCursor(HAND_CURSOR));
        keyboard(arrow, "Fold table", () -> setFolded(!folded));
        previous.getAccessibleContext().setAccessibleName("Previous page");
        next.getAccessibleContext().setAccessibleName("Next page");
        arrow.addMouseListener(new MouseAdapter() {
                    public void mouseClicked(MouseEvent e) {
                        setFolded(!folded);
                    }
                });
        previous.addActionListener(e -> showPage(page - 1));
        next.addActionListener(e -> showPage(page + 1));
        add(header, NORTH);
        add(body, CENTER);
    }

    Table setPicker(JComponent component) {
        if (picker != null) left.remove(picker);
        picker = component;
        if (component != null) left.add(component, EAST);
        left.revalidate();
        return this;
    }

    Table setTitle(String text) {
        title.setText(text);
        return this;
    }

    Table setTotal(String text, Tone tone) {
        total.setText(string(text));
        total.setForeground(tone == null ? LABEL : tone.color);
        refreshHeader();
        return this;
    }

    Table setFoldable(boolean value) {
        foldable = value;
        arrow.setVisible(value);
        refreshHeader();
        return this;
    }

    Table setFolded(boolean value) {
        folded = foldable && value;
        arrow.setText(folded ? "▸" : "▾");
        body.setVisible(!folded);
        refreshHeader();
        revalidate();
        return this;
    }

    /** Recent is a collapsible plain list: no pager, at most thirty already-filtered rows. */
    Table plain() {
        plain = true;
        size = 30;
        header.setBorder(createEmptyBorder(0, 6, 0, 2));
        return setFoldable(true);
    }

    /** Category and Ledger pages keep a bounded page size, never below one or above fifteen. */
    Table setPageSize(int value) {
        size = max(1, min(15, value));
        drawn = null;
        showPage(page);
        return this;
    }

    /** Let this table (the last on its page) take the sidebar height left below it. */
    Table setFill(boolean value) {
        if (value && !fill) {
            addHierarchyBoundsListener(new HierarchyBoundsAdapter() {
                        public void ancestorResized(HierarchyEvent e) {
                            fitLater();
                        }

                        public void ancestorMoved(HierarchyEvent e) {
                            fitLater();
                        }
                    });
            addComponentListener(new ComponentAdapter() {
                        public void componentMoved(ComponentEvent e) {
                            fitLater();
                        }
                    });
        }
        fill = value;
        fitLater();
        return this;
    }

    void fitLater() {
        if (fill) invokeLater(this::fit);
    }

    /** Rows that fit between this table's top and the bottom of the visible sidebar. */
    void fit() {
        var viewport = (JViewport) getAncestorOfClass(JViewport.class, this);
        if (viewport == null || viewport.getView() == null || viewport.getHeight() <= 0) return;
        int top = convertPoint(getParent(), getX(), getY(), viewport.getView()).y;
        int chrome = header.getPreferredSize().height + GAP;
        int per = fits(viewport.getHeight() - top - chrome, rowHeight());
        if (per != size) {
            size = per;
            drawn = null;
            showPage(page);
        }
    }

    static int fits(int room, int rowHeight) {
        return max(3, min(15, room / max(1, rowHeight)));
    }

    /** One height for every row of a table: icon rows when any row carries an icon. */
    int rowHeight() {
        for (Row row : rows) {
            if (row.icon != null) return ICON_ROW;
        }
        return ROW;
    }

    /** Keep the body at least this many rows tall so short lists do not shrink the page. */
    Table setMinRows(int value) {
        minRows = max(0, value);
        drawn = null;
        showPage(page);
        return this;
    }

    Table setEmpty(String text) {
        if (text.equals(emptyText)) return this;
        emptyText = text;
        drawn = null;
        return this;
    }

    /** A small control on the right of the header, such as "Decide…". It replaces the pager. */
    Table setAction(JComponent component) {
        action = component;
        refreshHeader();
        return this;
    }

    Table setRows(List<Row> value) {
        rows = value == null ? emptyList() : new ArrayList<>(value);
        showPage(page);
        fitLater();
        return this;
    }

    /** A counted page table title; empty lists keep the original bare title. */
    Table rowTitle(String title, List<Row> list) {
        return setTitle(list.isEmpty() ? title : title + " · " + list.size()).setRows(list);
    }

    void resetPage() {
        showPage(0);
    }

    int pages() {
        return pages(rows.size(), size);
    }

    static int pages(int count, int perPage) {
        return pageCount(count, perPage);
    }

    void showPage(int requested) {
        page = clampPage(requested, pages());
        int from = page * size;
        var visible = rows.subList(min(from, rows.size()), min(from + size, rows.size()));
        if (!visible.equals(drawn)) {
            var previous = focused(body);
            drawn = new ArrayList<>(visible);
            body.removeAll();
            if (visible.isEmpty())
                body.add(emptyLine(emptyText));
            int rowHeight = rowHeight();
            // The quantity column hugs each row's own text, so one wide "×2 doses" row no longer
            // steals width from every name; right edges stay aligned against the value column.
            for (Row row : visible) body.add(rowComponent(row, rowHeight));
            int height = visible.isEmpty() ? max(1, minRows) * ROW : max(minRows, visible.size()) * rowHeight;
            body.setPreferredSize(new Dimension(0, height));
            restoreFocus(body, previous);
        }
        refreshHeader();
        revalidate();
        repaint();
    }

    void refreshHeader() {
        var previousFocus = focused(right);
        arrow.getAccessibleContext().setAccessibleName((folded ? "Expand " : "Collapse ") + title.getText());
        var parts = new ArrayList<Component>();
        if (!total.getText().isEmpty()) parts.add(total);
        if (action != null) parts.add(action);
        else if (!plain && !folded && !rows.isEmpty()) {
            applyPager(counter, previous, next, page, pages(), (page + 1) + "/" + pages());
            addAll(parts, previous, counter, next);
        }
        if (!parts.equals(Arrays.asList(right.getComponents()))) {
            right.removeAll();
            for (Component part : parts) {
                var gap = new GridBagConstraints();
                if (part == action) gap.insets = new Insets(0, 4, 0, 0);
                right.add(part, gap);
            }
            right.revalidate();
        }
        restoreFocus(right, previousFocus);
    }

    static JComponent rowComponent(Row row, int height) {
        var line = new JPanel(new BorderLayout(4, 0));
        line.setBackground(row.selected ? SELECTED : CARD);
        pad(line, 0, height == ICON_ROW ? 2 : 6, 0, 4);
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        line.setPreferredSize(new Dimension(0, height));
        if (height == ICON_ROW) {
            // Every row of an icon table keeps the icon slot, so names line up with or without one.
            var icon = row.icon == null ? new JLabel() : new JLabel(row.icon);
            icon.setHorizontalAlignment(SwingConstants.CENTER);
            icon.setPreferredSize(new Dimension(ICON_WIDTH, ICON_ROW));
            line.add(icon, WEST);
        }
        line.add(label(row.name, body().deriveFont(11.5f), row.color == null ? TEXT : row.color), CENTER);
        var numbers = transparent(new BorderLayout(row.qty.isEmpty() ? 0 : 4, 0));
        if (!row.qty.isEmpty()) {
            int qty = min(70, line.getFontMetrics(numbers(10.5f)).stringWidth(row.qty) + 2);
            numbers.add(column(row.qty, numbers(10.5f), LABEL, qty, height), WEST);
        }
        var value = column(row.value, numbers(10.5f), row.tone.color, VALUE_WIDTH, height);
        value.setPreferredSize(new Dimension(max(VALUE_WIDTH,
                        value.getFontMetrics(value.getFont()).stringWidth(row.value) + 2), height));
        if (!row.exact.isEmpty()) value.setToolTipText(row.exact);
        numbers.add(value, EAST);
        line.add(numbers, EAST);
        if (!row.tip.isEmpty()) line.setToolTipText(row.tip);
        if (row.open != null) {
            clickable(line, "Open " + row.name, row.open);
            line.addMouseListener(new MouseAdapter() {
                        public void mouseEntered(MouseEvent e) {
                            line.setBackground(SELECTED);
                        }

                        public void mouseExited(MouseEvent e) {
                            line.setBackground(row.selected ? SELECTED : CARD);
                        }
                    });
        }
        if (!row.menu.isEmpty()) {
            line.setComponentPopupMenu(menuOf(row.menu));
            if (row.open == null) keyboard(line, "Menu for " + row.name, () -> line.getComponentPopupMenu().show(line, 0, line.getHeight()));
        }
        line.setName(row.key);
        if (row.open != null || !row.menu.isEmpty()) {
            key(line, "UP", () -> moveFocus(line, -1));
            key(line, "DOWN", () -> moveFocus(line, 1));
        }
        return line;
    }

    static JButton glyphButton(String glyph) {
        var button = new JButton(glyph);
        button.setFont(small());
        button.setForeground(TEXT);
        pad(button, 0, 3, 0, 3);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        focus(button);
        return button;
    }
}

/**
 * The sidebar frame (SIDEBAR_SPEC.md §1) on stock RuneLite parts: the Material tab strip, the shown
 * page's toolbar, the page body in a plain scroll pane (the RuneLite look styles it), then one
 * notification line at the bottom. Remembers each page's scroll.
 */
class Shell extends JPanel {
    /** A page contributes an optional toolbar and a scrolling body. */
    interface Page {
        String id();

        /** The row under the tabs; null when the page has none. */
        JComponent pageBar();

        JComponent body();

        /** Account loading hides each page until its next snapshot is ready. */
        default void visible(boolean shown) {
            body().setVisible(shown);
            body().revalidate();
            if (pageBar() != null) pageBar().setVisible(shown);
        }

        default void onShown() {}
    }

    /** One button on a sheet. */
    @AllArgsConstructor
    static class Choice {
        String label;
        boolean primary;
        Runnable action;
    }

    static final String LIVE = "live", LEDGER = "ledger", GRINDS = "grinds", RAIDS = "raids";
    static final String[][] TABS = {{LIVE, "Live"}, {LEDGER, "Ledger"}, {GRINDS, "Grinds"}, {RAIDS, "Raids"}};
    static final Border SELECTED = createCompoundBorder(
                    createMatteBorder(0, 0, 1, 0, ACCENT), createEmptyBorder(5, 1, 4, 1));
    static final Border UNSELECTED = createEmptyBorder(5, 1, 5, 1);
    MaterialTabGroup tabs = new MaterialTabGroup();
    Map<String, MaterialTab> buttons = new LinkedHashMap<>();
    JPanel bar = transparent(new BorderLayout()), sheet = stack(CARD, GAP);

    /**
     * Holds the sheet with its margin; hidden with it, so no gap sits under the toolbar (owner
     * 1.1).
     */
    JPanel sheetHolder = transparent(new BorderLayout());

    String heading = "";
    CardLayout cards = new CardLayout();
    JPanel bodies = transparent(cards);
    Map<String, Page> pages = new LinkedHashMap<>();
    Map<String, JScrollPane> scrolls = new LinkedHashMap<>();
    Map<String, Integer> scrollMemory = new LinkedHashMap<>();
    JPanel notice = new JPanel(new BorderLayout(GAP, 0));
    JLabel message = label("", small(), TEXT);
    JPanel noticeAction = transparent(new BorderLayout());
    Timer timer = new Timer(4000, e -> {
        if (focused(notice) == null) dismiss();
        else ((Timer) e.getSource()).restart();
    });

    /** A data-status failure stays until clearSticky; it ignores the timer and text clicks. */
    boolean noticeSticky;

    String current = LIVE;

    /**
     * True while show() moves the tab strip, whose select callback would call show() again.
     */
    boolean selecting;

    Shell() {
        super(new BorderLayout());
        setBackground(PANEL);
        for (String[] tab : TABS) {
            var material = new MaterialTab(tab[1], tabs, null) {
                        public void setBorder(Border border) {
                            // Stock tabs pad 10 px a side; four need narrower sides at sidebar
                            // width.
                            super.setBorder(border instanceof CompoundBorder ? SELECTED : UNSELECTED);
                        }
                    };
            keyboard(material, tab[1], () -> tabs.select(material));
            key(material, "LEFT", () -> moveFocus(material, -1));
            key(material, "RIGHT", () -> moveFocus(material, 1));
            key(material, "HOME", () -> moveFocus(material, -99));
            key(material, "END", () -> moveFocus(material, 99));
            material.setOnSelectEvent(() -> {
                        if (!selecting) show(tab[0]);
                        return true;
                    });
            material.setHorizontalAlignment(SwingConstants.CENTER);
            material.setFont(body());
            buttons.put(tab[0], material);
            tabs.addTab(material);
        }
        // Four equal cells: the stock centred flow wraps the fourth tab at sidebar width.
        tabs.setLayout(new GridLayout(1, 0));
        tabs.remove(buttons.get(RAIDS));
        // Owner 2026-10-01: the page toolbar attaches to the tab strip and shares one band shape;
        // owner 1.1: it sits close to the first table.
        pad(bar, 0, GAP, 2, GAP);
        var top = transparent(new BorderLayout());
        // The gear beside the tabs holds profile-wide data actions, reachable from any page.
        var strip = transparent(new BorderLayout());
        strip.setBorder(createMatteBorder(0, 0, 1, 0, LINE));
        var gear = new JButton(new ImageIcon(ImageUtil.loadImageResource(ConfigPlugin.class, msg("config-edit"))));
        pad(gear, 0, 6, 0, 6);
        gear.setContentAreaFilled(false);
        gear.setFocusPainted(false);
        gear.setToolTipText(msg("export-backups"));
        gear.getAccessibleContext().setAccessibleName("GP Manager actions");
        focus(gear);
        gear.setName("GP Manager actions");
        gear.addActionListener(e -> onGear.accept(gear));
        strip.add(tabs, CENTER);
        strip.add(gear, EAST);
        top.add(strip, NORTH);
        top.add(bar, CENTER);
        sheet.setVisible(false);
        sheetHolder.setVisible(false);
        pad(sheetHolder, GAP, GAP, 0, GAP);
        sheetHolder.add(sheet, CENTER);
        top.add(sheetHolder, SOUTH);
        add(top, NORTH);
        add(bodies, CENTER);
        notice.setBackground(CARD);
        notice.setVisible(false);
        message.setCursor(getPredefinedCursor(HAND_CURSOR));
        keyboard(message, "Dismiss message", this::noticeClicked);
        sheet.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(getKeyStroke("ESCAPE"), "close");
        sheet.getActionMap().put("close", new AbstractAction() {
            public void actionPerformed(ActionEvent event) { closeSheet(); }
        });
        message.addMouseListener(new MouseAdapter() {
                    public void mouseClicked(MouseEvent e) {
                        noticeClicked();
                    }
                });
        notice.add(message, CENTER);
        notice.add(noticeAction, EAST);
        timer.setRepeats(false);
        add(notice, SOUTH);
    }

    /** Toggle a registered tab without retaining an empty layout cell. */
    void raidsAvailable(boolean available) {
        var tab = buttons.get(RAIDS);
        if (available == (tab.getParent() == tabs)) return;
        if (available) tabs.add(tab);
        else {
            if (RAIDS.equals(current)) show(LIVE);
            tabs.remove(tab);
        }
        tabs.revalidate();
        tabs.repaint();
    }

    void addPage(Page page) {
        pages.put(page.id(), page);
        JPanel view = new Column();
        view.setOpaque(false);
        pad(view, GAP, 0, 0, GAP);
        view.add(page.body(), NORTH);
        var scroll = new JScrollPane(view, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                        JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setOpaque(false);
        scroll.setOpaque(false);
        scroll.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        // The client can resize the sidebar without laying this pane out again, leaving its bar
        // stuck until a click; Live and Grinds re-fit their tables on resize, so they never
        // showed it. Every page now lays out again after a resize (owner 1.5.0).
        scroll.addComponentListener(new ComponentAdapter() {
                    public void componentResized(ComponentEvent e) {
                        invokeLater(() -> {
                                    view.revalidate();
                                    scroll.revalidate();
                                    scroll.repaint();
                                });
                    }
                });
        scrolls.put(page.id(), scroll);
        bodies.add(scroll, page.id());
    }

    /** Opens the profile data menu (export, backups, reset) from the gear beside the tabs. */
    Consumer<JComponent> onGear = anchor -> {};

    /** Runs after a page is shown, so its owner can fill it at once instead of on the next tick. */
    Runnable onShow = () -> {};

    void show(String id) {
        Page page = pages.get(id);
        if (page == null || RAIDS.equals(id) && buttons.get(RAIDS).getParent() != tabs) return;
        var leaving = scrolls.get(current);
        if (leaving != null) scrollMemory.put(current, leaving.getVerticalScrollBar().getValue());
        var previous = leaving == null ? null : focused(leaving);
        current = id;
        closeSheet(false);
        var tab = buttons.get(id);
        if (tab != null && !tab.isSelected()) {
            selecting = true;
            try {
                tabs.select(tab);
            } finally {
                selecting = false;
            }
        }
        bar.removeAll();
        JComponent pageBar = page.pageBar();
        if (pageBar != null) bar.add(pageBar, CENTER);
        bar.setVisible(pageBar != null);
        bar.revalidate();
        cards.show(bodies, id);
        page.onShown();
        onShow.run();
        if (previous != null) restoreFocus(scrolls.get(id), previous);
        Integer remembered = scrollMemory.get(id);
        var entering = scrolls.get(id);
        if (remembered != null && entering != null)
            invokeLater(() -> entering.getVerticalScrollBar().setValue(remembered));
        repaint();
    }

    String currentPage() {
        return current;
    }

    /** A plain notification from any thread; it shows on the Swing thread. */
    void tell(String title, String detail, boolean warning) {
        if (isEventDispatchThread())
            notify(title, detail, warning, null, null); else
            invokeLater(() -> notify(title, detail, warning, null, null));
    }

    /** "Export failed · reason" for a failed file action. */
    void failed(String title, Exception ex) {
        tell(title, ex.getMessage() == null ? "see the log" : ex.getMessage(), true);
    }

    /**
     * One notification at a time at the bottom of the sidebar: about 4 seconds, 8 when it offers an
     * action, and a click on the text dismisses it. A sticky data-status failure outranks routine
     * news and only clearSticky takes it down (owner 2026-10-01, F06).
     */
    void notify(String title, String detail, boolean warning, String action, Runnable onAction) {
        if (noticeSticky) return;
        render(title, detail, warning, action, onAction, false);
    }

    /** Keeps a save failure visible until the backend recovers; no timer and no click dismiss. */
    void notifySticky(String title, String detail, String action, Runnable onAction) {
        String tip = title + (detail == null ? "" : " · " + detail);
        if (noticeSticky && notice.isVisible() && tip.equals(message.getToolTipText())) return;
        noticeSticky = true;
        render(title, detail, true, action, onAction, true);
    }

    /** A successful status transition takes the sticky failure down. */
    void clearSticky() {
        if (!noticeSticky) return;
        noticeSticky = false;
        if (notice.isVisible()) dismiss();
    }

    /** The text click dismisses routine news; a sticky failure stays. */
    void noticeClicked() {
        if (!noticeSticky) dismiss();
    }

    void render(String title, String detail, boolean warning, String action, Runnable onAction, boolean sticky) {
        Color color = warning ? REVIEW.color : GAIN.color;
        notice.setBorder(createCompoundBorder(createMatteBorder(1, 3, 0, 0, color),
                        createEmptyBorder(4, GAP, 4, GAP)));
        String safe = (title + (empty(detail) ? "" : " · " + detail)).replace("&", "&amp;")
                        .replace("<", "&lt;");
        message.setText(msg("div-style") + (action == null ? 190 : 130) + "px'>" + safe + "</div></html>");
        message.setToolTipText(title + (detail == null ? "" : " · " + detail));
        noticeAction.removeAll();
        if (action != null && onAction != null) {
            JButton button = button(action, true, () -> {
                                dismiss();
                                onAction.run();
                            });
            noticeAction.add(button, CENTER);
        }
        notice.setVisible(true);
        if (sticky)
            timer.stop(); else {
            timer.setInitialDelay(action == null ? 4000 : 8000);
            timer.restart();
        }
        revalidate();
        repaint();
    }

    /**
     * An in-sidebar form under the toolbar (SIDEBAR_SPEC.md §4), one at a time: a title, an
     * optional message and content, then the choices and Cancel. A choice closes the sheet first,
     * then runs; switching pages closes it.
     */
    JComponent returnFocus;

    void sheet(String title, String message, JComponent content, Choice... choices) {
        if (!sheet.isVisible()) returnFocus = focused(this);
        sheet.removeAll();
        heading = title;
        sheet.setBorder(createCompoundBorder(createMatteBorder(1, 0, 0, 0, ACCENT),
                        createEmptyBorder(GAP, GAP, GAP, GAP)));
        sheet.add(label(title, bold(), Color.WHITE));
        if (!empty(message)) {
            var note = note(message, LABEL, SHEET_TEXT);
            note.setBorder(null);
            sheet.add(note);
        }
        if (content != null) sheet.add(content);
        var buttons = transparent(choices.length > 1 ? new GridLayout(0, 1, 0, 4)
                        : new FlowLayout(FlowLayout.RIGHT, 4, 0));
        for (var choice : choices)
            buttons.add(button(choice.label, choice.primary, () -> {
                                closeSheet();
                                choice.action.run();
                            }));
        buttons.add(button(choices.length == 0 ? "Close" : "Cancel", false, this::closeSheet));
        sheet.add(buttons);
        sheet.setVisible(true);
        sheetHolder.setVisible(true);
        var first = focusTarget(sheet, null);
        if (first != null) first.requestFocusInWindow();
        revalidate();
        repaint();
    }

    /** A one-line text sheet; OK or Enter passes the trimmed text when it is not empty. */
    void prompt(String title, String label, String initial, Consumer<String> onOk) {
        var field = new JTextField(string(initial));
        var content = stack(CARD, 2);
        content.add(label(label, small(), LABEL));
        content.add(field);
        Runnable ok = () -> {
                    String value = field.getText().trim();
                    if (!value.isEmpty()) onOk.accept(value);
                };
        field.addActionListener(e -> {
                    closeSheet();
                    ok.run();
                });
        sheet(title, null, content, new Choice("OK", true, ok));
        invokeLater(field::requestFocusInWindow);
    }

    /** Asks before an action; it runs only on the named button. */
    void confirm(String title, String message, String action, Runnable onConfirm) {
        sheet(title, message, null, new Choice(action, true, onConfirm));
    }

    void closeSheet() { closeSheet(true); }

    void closeSheet(boolean restore) {
        if (sheet.isVisible()) {
            sheet.setVisible(false);
            sheetHolder.setVisible(false);
            sheet.removeAll();
            heading = "";
            if (restore && returnFocus != null && returnFocus.isShowing()) returnFocus.requestFocusInWindow();
            returnFocus = null;
            revalidate();
            repaint();
        }
    }

    void dismiss() {
        timer.stop();
        notice.setVisible(false);
        revalidate();
    }

    /** Follows the viewport width, so nothing is ever clipped or scrolled sideways. */
    static class Column extends JPanel implements Scrollable {
        Column() {
            super(new BorderLayout());
        }

        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 24;
        }

        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return max(24, visible.height - 24);
        }

        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}

/**
 * Number, time and label formatting shared by the sidebar (compact by default, exact on demand).
 */
class Fmt {
    static final NumberFormat EXACT = NumberFormat.getIntegerInstance(UK);

    /** Curated short activity names (master plan, Activity presentation): exact names first. */
    static final List<String[]> SHORT = GameData.rows("short_names");

    /**
     * Phrases shortened wherever they appear ("Theatre of Blood (Hard Mode)" becomes "ToB (HM)").
     */
    static final List<String[]> PHRASES = GameData.rows("short_phrases");

    /**
     * The short activity name every surface shows: curated names (KBD, GE Clerk), raid names with
     * their mode (ToB (HM)), and any other trailing "(qualifier)" dropped.
     */
    static String activity(String name) {
        String value = name == null ? "" : name.trim();
        for (String[] row : SHORT) {
            if (row[0].equalsIgnoreCase(value)) return row[1];
        }
        for (String[] row : PHRASES)
            value = value.replaceAll("(?i)" + java.util.regex.Pattern.quote(row[0]), row[1]);
        int open = value.lastIndexOf(" (");
        boolean raid = value.startsWith("ToB") || value.startsWith("CoX") || value.startsWith("ToA");
        return open > 0 && value.endsWith(")") && !raid ? value.substring(0, open) : value;
    }

    /**
     * Reads a GP amount with an optional k/m/b suffix; unreadable or negative amounts return 0.
     * Target inputs validate their whole-string syntax first; the display minimum is permissive.
     */
    static long parseGp(String raw) {
        if (raw == null) return 0;
        String text = lower(raw).replace(",", "").replace("_", "");
        if (text.isEmpty()) return 0;
        long multiplier = 1;
        if (text.endsWith("k") || text.endsWith("m") || text.endsWith("b")) {
            char suffix = text.charAt(text.length() - 1);
            multiplier = suffix == 'k' ? 1000 : suffix == 'm' ? 1_000_000L : 1_000_000_000L;
            text = text.substring(0, text.length() - 1).trim();
        }
        try {
            long value = round(Double.parseDouble(text) * multiplier);
            return positive(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** {@code +1.2k}, {@code −36.0k}, {@code 0}. Uses a true minus sign. */
    static String signed(long value) {
        if (value == 0) return "0";
        return (value > 0 ? "+" : "−") + compact(value);
    }

    /** An item row's worth: a dash when it is worth nothing (owner live 2026-10-16). */
    static String worth(long value) {
        return value == 0 ? "—" : signed(value);
    }

    /**
     * Unsigned compact with fixed precision so columns line up: {@code 812} · {@code 1.2k} · {@code
     * 36.0k} · {@code 248k} · {@code 1.76M} · {@code 12.4M} · {@code 212M} · {@code 1.02B}.
     */
    static String compact(long value) {
        long abs = SafeMath.abs(value);
        if (abs < 1000) return Long.toString(abs);
        // Cut off, never rounded up, like the game's own stack numbers: 999,600 is 999k (owner
        // 1.3.1).
        if (abs < 100000) return format(ROOT, "%.1fk", abs / 100 / 10d);
        if (abs < 1_000_000) return abs / 1000 + "k";
        if (abs < 10_000_000) return format(ROOT, "%.2fM", abs / 10000 / 100d);
        if (abs < 100_000_000) return format(ROOT, "%.1fM", abs / 100000 / 10d);
        if (abs < 1_000_000_000) return abs / 1_000_000 + "M";
        if (abs < 10_000_000_000L) return format(ROOT, "%.2fB", abs / 10_000_000 / 100d);
        return format(ROOT, "%.1fB", abs / 100_000_000 / 10d);
    }

    /** {@code +124,318} with grouping and a true minus sign. */
    static String exactSigned(long value) {
        if (value == 0) return "0";
        return (value > 0 ? "+" : "") + exact(value).replace('-', '−');
    }

    static String exact(long value) {
        return EXACT.format(value);
    }

    /** Rates read like figures: {@code 248k}, or {@code −13.0k} when the hour is losing. */
    static String rate(long perHour) {
        return perHour < 0 ? "−" + compact(perHour) : compact(perHour);
    }

    /** {@code h:mm:ss} for the session clock; {@code m:ss} under an hour. */
    static String clock(long ms) {
        long total = positive(ms) / 1000, h = total / 3600, m = (total % 3600) / 60, s = total % 60;
        return h > 0 ? format(ROOT, "%d:%02d:%02d", h, m, s) : format(ROOT, "%d:%02d", m, s);
    }

    /** Short duration for labels: {@code 8m 04s}, {@code 1h 12m}, {@code 2d 3h}. */
    static String duration(long ms) {
        return duration(ms, true);
    }

    static String dayLabel(LocalDate day, long now, ZoneId zone) {
        var effectiveZone = zone == null ? ZoneId.systemDefault() : zone;
        var today = Instant.ofEpochMilli(now).atZone(effectiveZone).toLocalDate();
        long ago = ChronoUnit.DAYS.between(day, today);
        if (ago == 0) return "Today";
        if (ago == 1) return "Yesterday";
        String pattern = day.getYear() == today.getYear() ? "EEE d MMM" : "d MMM yyyy";
        return day.format(DateTimeFormatter.ofPattern(pattern, ROOT));
    }

    /** Duration without seconds for tight cells: {@code 30m}, {@code 7h 40m}, {@code 2d 3h}. */
    static String durationShort(long ms) {
        return duration(ms, false);
    }

    /** A counting timer value: {@code 12s} under a minute, then {@code 1m}, {@code 1h 02m}. */
    static String durationCount(long ms) {
        return positive(ms) < 60000 ? positive(ms) / 1000 + "s" : durationShort(ms);
    }

    static String duration(long ms, boolean seconds) {
        long total = positive(ms) / 1000, d = total / 86400, h = (total % 86400) / 3600, m = (total % 3600) / 60;
        long s = total % 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + format(ROOT, "%02d", m) + "m";
        return m + "m" + (seconds ? format(ROOT, " %02ds", s) : "");
    }

    /**
     * A target time in the form the Targets field reads back exactly: {@code 30m}, {@code 3h},
     * {@code 26h 30m}.
     */
    static String targetTime(long ms) {
        long minutes = positive(ms) / 60000;
        if (minutes < 60) return minutes + "m";
        return minutes / 60 + "h" + (minutes % 60 > 0 ? " " + minutes % 60 + "m" : "");
    }

    /** A Net target the field reads back exactly: {@code 5m}, {@code 9.12m}, {@code 750k}. */
    static String targetNet(long gp) {
        // "1b", not "1000m", so the 1b pick lights up (owner 1.5.2 milestone).
        if (gp >= 1_000_000_000 && gp % 10_000_000 == 0)
            return BigDecimal.valueOf(gp, 9).stripTrailingZeros().toPlainString() + "b";
        if (gp >= 1_000_000 && gp % 10000 == 0)
            return BigDecimal.valueOf(gp, 6).stripTrailingZeros().toPlainString() + "m";
        return gp < 1_000_000 && gp % 1000 == 0 ? gp / 1000 + "k" : exact(gp);
    }

    /** {@code ×27} quantity marker. */
    static String times(long quantity) {
        return "×" + EXACT.format(abs(quantity));
    }

    /** {@code 62%} clamped. */
    static String percent(double fraction) {
        int pct = (int) round(max(0d, min(1d, fraction)) * 100d);
        return pct + "%";
    }

    /**
     * Day and clock for a session start: {@code Today 20:45}, {@code Yesterday 09:12}, {@code Mon 8
     * Sep 20:45}.
     */
    static String when(long epochMillis, long now) {
        var zone = ZoneId.systemDefault();
        var moment = Instant.ofEpochMilli(epochMillis).atZone(zone);
        return dayLabel(moment.toLocalDate(), now, zone) + " "
                + moment.format(DateTimeFormatter.ofPattern("HH:mm", ROOT));
    }

    /** Relative age: {@code 3m ago}, {@code 2h ago}, {@code just now}. */
    static String age(long ageMillis) {
        long s = positive(ageMillis) / 1000;
        if (s < 45) return "just now";
        if (s < 3600) return s / 60 + "m ago";
        if (s < 86400) return s / 3600 + "h ago";
        return s / 86400 + "d ago";
    }
}

/**
 * Builds one {@link HudFrame} per game tick from the same Live snapshot the sidebar paints, plus
 * the tray and the moments. It reads, formats and publishes; it never books anything.
 */
@RequiredArgsConstructor
class HudBuilder {
    static final Color GOLD = new Color(255, 200, 40);

    /** The tray slides open and shut over this long; the chip fades out over it. */
    static final long FADE_MILLIS = 300;

    /** The chip beside Net shows one game tick's booked change this long, independent of the tray. */
    static final long CHIP_MILLIS = 4000;

    /** Item sprites by id and stack size, so coins show their pile rather than one coin. */
    interface Icons {
        BufferedImage of(int id, int quantity);
    }

    /** Counts at which stack sprites (coins) change; bucketing keeps the sprite cache small. */
    static final int[] STACKS = {10000, 1000, 250, 100, 25, 5, 4, 3, 2};

    final GpManagerConfig config;
    final Icons icons;
    final HudTray tray = new HudTray();
    String sessionOwner = "";
    Color color = DIM.color;
    long openedAt;

    /** The streak the per-kill line measures, and Net just before it began. */
    String baseKey;

    long base;

    /** The streak before, so a merged mixed streak measures from where it first began. */
    String prevKey;

    long prevBase, lastNet;

    volatile String interaction = "";
    volatile HudFrame current = HudFrame.HIDDEN;

    HudFrame current() {
        return current;
    }

    /**
     * A factory reset replaced the bound profile: every presentation trace of the old trip is
     * dropped, so HUD+ comes back exactly as on a new install. EDT only.
     */
    void resetPresentation() {
        tray.clear();
        sessionOwner = "";
        color = DIM.color;
        openedAt = 0;
        baseKey = null;
        current = HudFrame.HIDDEN;
    }

    long stayMillis() {
        return config.hudTraySeconds() * 1000;
    }

    boolean keepSession() {
        return config.hudTrayKeeps() == GpManagerConfig.HudTrayKeeps.SESSION;
    }

    /** The NPC the player is fighting; empty when released or not a fight. */
    void interaction(String name, boolean combat) {
        interaction = name == null || !combat ? "" : name.trim();
    }

    /** The fight target; the shared activity label reads only whether there is one. */
    String target() {
        return interaction;
    }

    /** The panel's display filter, for highlighted names; null highlights nothing. */
    RecentFilter filter;

    /**
     * @param visible the display loot filter; hidden rows stay unnamed, and only their count shows
     */
    HudFrame update(LiveSnapshot snapshot, Predicate<Flow> visible, long now) {
        String owner = snapshot.session;
        if (!owner.equals(sessionOwner)) {
            // Free play to a Grind (or back) starts a clean tray; the first sighting keeps what is
            // there.
            if (!sessionOwner.isEmpty()) tray.clear();
            sessionOwner = owner;
            baseKey = null;
            openedAt = 0;
        }
        boolean plan = snapshot.present && !snapshot.general;
        // A new streak measures its Net from the last tick before its first event; the whole run's
        // Net, so a target starting Live fresh mid-streak never moves it (owner 1.5.2).
        long runNet = snapshot.net + snapshot.netBase;
        String streak = tray.streakKey();
        if (!streak.equals(baseKey)) {
            long base = streak.equals(prevKey) ? prevBase : baseKey == null ? runNet : lastNet;
            prevKey = baseKey;
            prevBase = this.base;
            this.base = base;
            baseKey = streak;
        }
        lastNet = runNet;
        // Nothing booked yet reads as a dash, never a zero (the sidebar's first-run rule).
        boolean booked = snapshot.present && (snapshot.fresh || snapshot.income != 0 || snapshot.cost != 0
                                || snapshot.market != 0 || !snapshot.recent.isEmpty());
        tray.keepMillis(config.streakEndSeconds() * 1000);
        // "Hide when not tracking": nothing to track, logged out or paused hides HUD+; Free play
        // shows
        // once something is booked (observed, unbooked tray activity does not count).
        boolean live = snapshot.present && !snapshot.offline && !snapshot.paused && !snapshot.idle;
        boolean shown = config.showHud() && (!config.hudHideWhenIdle()
                                || live && (plan || booked || tray.hasBooked()));
        if (!shown) {
            current = HudFrame.HIDDEN;
            return current;
        }
        boolean tracking = snapshot.present && !snapshot.offline;
        Gem gem = !tracking ? Gem.OFF : snapshot.paused && !snapshot.idle ? Gem.PAUSED : snapshot.idle ? Gem.AWAY
                                        : snapshot.possible ? Gem.PVP : Gem.LIVE;
        String target = "";
        double progress = -1d;
        boolean targetWarning = false, targetDone = false;
        if (snapshot.goal != null) {
            var goal = snapshot.goal;
            // Owner live 2026-10-13: the target rides the progress bar's end, not the money row,
            // so "/ 200k" never stretches HUD+ wide. A time target shows its live countdown
            // there and warns in its final seconds. A Net aim's label counts down what is left
            // while the bar fills (owner live 2026-10-14); its own timer's countdown replaces the
            // label only in the final seconds it warns for, and a paused timer never warns.
            long left = positive(goal.target - goal.current), remaining = goal.time ? left : goal.remaining;
            int warnSeconds = config.hudTargetWarningSeconds();
            targetWarning = warnSeconds > 0 && !goal.timerPaused && remaining > 0 && remaining <= warnSeconds * 1000;
            target = goal.time || targetWarning ? durationCount(remaining) : compact(left);
            progress = !goal.time && goal.current <= 0 ? 0d : max(0d, min(1d, goal.fraction));
            targetDone = goal.reached;
        }
        String rate = !snapshot.present ? "—" : LivePage.rateText(snapshot.ready, snapshot.rate);
        boolean always = config.hudTray() == GpManagerConfig.HudTray.ALWAYS_OPEN;
        // Owner 2026-10-03: hidden loot never opens or holds the tray open; an open tray's footer
        // counts it.
        var listed = new ArrayList<HudTray.Entry>();
        long shownAt = 0;
        int hidden = 0;
        for (HudTray.Entry entry : tray.entries()) {
            if (visible.test(flowOf(entry))) {
                listed.add(entry);
                shownAt = max(shownAt, entry.seen);
            } else if (entry.state != TRADED)
                hidden++;
        }
        boolean open = !listed.isEmpty() && (always || now - shownAt < stayMillis() + FADE_MILLIS);
        openedAt = !open ? 0 : openedAt == 0 ? now : openedAt;
        var rows = new ArrayList<HudFrame.Row>();
        // The chip totals the newest booking tick; tray rows retain their running quantities.
        long chipUntil = tray.lastDeltaAt() == 0 ? 0 : tray.lastDeltaAt() + CHIP_MILLIS;
        long trip = now < chipUntil ? tray.lastDelta() : 0;
        listed.sort(Comparator.comparingLong((HudTray.Entry entry) -> entry.seen).reversed()
                .thenComparing(entry -> !(entry.gold || filter != null
                        && filter.highlight(entry.name, entry.quantity) != null))
                .thenComparing(Comparator.comparingLong((HudTray.Entry entry) -> SafeMath.abs(entry.value)).reversed()));
        // A big drop keeps its row however much cheaper loot follows it; "+N more" takes the rest.
        int plain = config.hudTrayRows();
        for (HudTray.Entry entry : listed) if (entry.gold) plain--;
        for (HudTray.Entry entry : listed) {
            if (open && rows.size() < config.hudTrayRows() && (entry.gold || plain-- > 0))
                rows.add(row(entry));
        }
        int more = open ? listed.size() - rows.size() : 0;
        String footer = more > 0 ? "+" + more + " more" : "";
        if (open && hidden > 0)
            footer = (footer.isEmpty() ? "" : footer + " · ") + "+" + hidden + " hidden";
        String context = contextLine(snapshot);
        if (snapshot.reviews == 0 && snapshot.returns == 0 && snapshot.targetUntil > 0
                && snapshot.milestone != null) {
            long left = snapshot.targetUntil - now;
            if (left <= 0) context = "";
            else if (!config.reducedMotion() && left < 1000)
                color = new Color(color.getRed(), color.getGreen(), color.getBlue(),
                        (int) (255 * left / 1000));
        }
        current = new HudFrame(true, gem, title(snapshot, now),
                        snapshot.present ? timer(snapshot.elapsed) : "", booked ? signed(snapshot.net) : "—",
                        booked ? tone(snapshot.net) : DIM.color, target, progress, targetWarning, targetDone,
                        trip != 0 ? signed(trip) : "", tone(trip), chipUntil, rate, context, color,
                        trayLabel(snapshot, now), rows, footer, tray.tripId(), always ? 0 : shownAt + stayMillis(),
                        openedAt, now);
        return current;
    }

    /**
     * Owner 1.1: HUD+ stays clean. A running kill streak reads "KC: 12" (spawns and side targets
     * aside, and only while the fight is fresh); the run's name lives on the sidebar only (owner
     * live 2026-10-14).
     */
    String title(LiveSnapshot snapshot, long now) {
        var streak = tray.streak(now);
        if (streak != null && snapshot.present && !interaction.isEmpty())
            return "KC: " + streak.getValue();
        return "";
    }

    /**
     * Needs review, else Reclaim waiting. Pace, PvP streak, skull and risk live in the sidebar
     * (owner 2026-09-28, 1.1); the PvP gem marks danger. Sets {@link #lineColor}.
     */
    String contextLine(LiveSnapshot snapshot) {
        color = DIM.color;
        if (snapshot.reviews > 0) {
            color = REVIEW.color;
            return "(!) " + snapshot.reviews
                    + (snapshot.reviews == 1 ? " item needs review" : " items need review");
        }
        if (snapshot.returns > 0)
            return "Reclaim waiting · " + snapshot.returns + (snapshot.returns == 1 ? " item" : " items");
        if (snapshot.milestoneReached() || snapshot.milestone != null && snapshot.targetUntil > 0) {
            color = GAIN.color;
            return "Milestone reached · " + compact(snapshot.milestone);
        }
        if (snapshot.warming) return "Calibrating…";
        return "";
    }

    static boolean counted(HudTray.Entry entry) {
        return entry.state != PENDING && entry.state != TRADED;
    }

    static Flow flowOf(HudTray.Entry entry) {
        // A hidden item stays off the tray whichever way it moved (owner 2026-09-28: a dropped one
        // too).
        return new Flow(entry.item, entry.name, entry.quantity, entry.unitPrice,
                entry.value);
    }

    HudFrame.Row row(HudTray.Entry entry) {
        // "(u)" is the game's own short form for uncharged, as in "Craw's bow (u)" (owner 1.2).
        var icon = icon(entry);
        Color highlight = filter == null ? null : filter.highlight(entry.name, entry.quantity);
        String name = entry.name.replace(" (uncharged)", " (u)")
                + (entry.item == 995 ? "" : " " + times(entry.quantity));
        String value = value(entry), narrow = null;
        if (config.hudCompactItems() && icon != null) {
            name = "";
            String quantity = "×" + compact(entry.quantity);
            boolean priced = !value.isEmpty() && !value.equals("?");
            value = (priced ? rate(entry.value) : value) + (value.isEmpty() ? "" : " ") + quantity;
            if (priced && config.hudSemiCompactValues() && SafeMath.abs(entry.value) < 10_000_000) {
                narrow = value;
                value = (entry.value < 0 ? exactSigned(entry.value) : exact(entry.value)) + " " + quantity;
            }
        }
        var row = new HudFrame.Row(icon, name, entry.state.tag, value, name.isEmpty() && highlight != null ? highlight : color(entry),
                entry.gold, entry.born, highlight);
        row.narrow = narrow;
        return row;
    }

    static String value(HudTray.Entry entry) {
        // A clue drop is worth nothing until its casket opens: no value, never "?" (owner
        // 2026-10-09).
        return entry.value == 0 && entry.unitPrice == 0
                ? entry.worthless || pattern("clue").matcher(entry.name).matches() ? "" : "?"
                : counted(entry) || entry.state == TRADED ? signed(entry.value) : compact(entry.value);
    }

    static Color color(HudTray.Entry entry) {
        return entry.gold ? GOLD : entry.state == LOST ? LOSS.color : entry.state == TRADED ? MARKET.color
                                : counted(entry) ? GAIN.color : DIM.color;
    }

    /**
     * The tray heading; a kill streak adds its Net per kill (owner 1.1: the hover panel is gone).
     */
    String trayLabel(LiveSnapshot snapshot, long now) {
        var streak = tray.streak(now);
        return streak == null || streak.getValue() < 2 || !snapshot.present ? tray.label() : tray.label()
                        + " · " + signed((snapshot.net + snapshot.netBase - base) / streak.getValue())
                        + "/kill";
    }

    BufferedImage icon(HudTray.Entry entry) {
        return icons == null || entry.item <= 0 || !config.showItemIcons() ? null
                : icons.of(entry.item, stack(entry.quantity));
    }

    static int stack(long quantity) {
        for (int stack : STACKS) {
            if (quantity >= stack) return stack;
        }
        return 1;
    }

    String timer(long ms) {
        if (config.hudTimer() == GpManagerConfig.HudTimer.Full) return clock(ms);
        long total = positive(ms) / 1000, hours = total / 3600, minutes = total % 3600 / 60;
        return hours > 0 ? String.format(ROOT, "%dh%02dm", hours, minutes)
                : String.format(ROOT, "%dm%02ds", minutes, total % 60);
    }

    static Color tone(long value) {
        return sign(value).color;
    }
}

/**
 * Everything HUD+ paints, built once per game tick by {@link HudBuilder}. Strings are already
 * formatted and colours already chosen, so painting only reads fields.
 */
@RequiredArgsConstructor
class HudFrame {
    static final HudFrame HIDDEN = new HudFrame(false, Gem.OFF, "", "", "", WHITE, "", -1d, false, false, "",
                    WHITE, 0, "", "", WHITE, "", emptyList(), "", 0, 0, 0, 0);

    /** Tracking state dot: live, paused, away, not tracking, PvP. */
    enum Gem {
        LIVE, PAUSED, AWAY, OFF, PVP
    }

    /** One tray row: sprite, "Copper ore ×1", value and its colour; gold for a big drop. */
    @RequiredArgsConstructor
    static class Row {
        final BufferedImage icon;
        final String name, tag, value;
        /** Short form when optional full figures exceed the HUD width. */
        String narrow;
        final Color color;
        final boolean gold;

        /** When the row first appeared, for its fade-in. */
        final long born;

        /** Ground Items' highlight colour for the name; null draws it white. */
        final Color highlight;
    }

    final boolean visible;
    final Gem gem;
    final String title, timer, net;
    final Color netColor;

    /**
     * What the aim has counted, what is left of it, or a time target's countdown, drawn at the
     * progress bar's end; empty with no target.
     */
    final String target;

    /** Target bar fill 0..1, or -1 with no target. Never negative for a losing Net. */
    final double progress;

    /** True in a time target's final warning seconds (owner live 2026-10-13). */
    final boolean warning;

    /**
     * True once the aim (or the time window) is reached: the bar's fill turns green then, whichever
     * way the gauge counts (owner live 2026-10-14).
     */
    final boolean targetDone;

    /**
     * The newest booking's change beside Net ("+23") for a few seconds, whether or not the tray
     * shows.
     */
    final String trip;

    final Color tripColor;

    /** When the chip is gone; it fades out just before. */
    final long chipUntil;

    /** "1.93M/h", "Calculating…" or "—". */
    final String rate;

    final String context;
    final Color contextColor;

    final String trayLabel;
    final List<Row> rows;

    /** "+3 more" past the visible rows, else empty. */
    final String more;

    /** Changes when a new trip starts; the tray width only grows within one trip. */
    final int tripId;

    /** When the tray began to fold; it slides shut over the next moment. 0 when always open. */
    final long foldAt;

    /** When the tray opened; it slides open over the next moment. */
    final long openedAt;

    /** The builder's clock when this snapshot was made; HUD+ animates on it. */
    final long builtAt;
}

/**
 * HUD+ (Pulse role, Trip layout): gem, title and timer; Net first with the target folded in, the
 * newest change and GP/h; one contextual line; the item tray. Width, "…" cuts and sprite crops are
 * worked out once per snapshot; only the tray's slide changes the height between snapshots. It has
 * no mouse listener and no menu entries, so stray clicks do nothing.
 */
class HudOverlay extends Overlay {
    static final int PAD = 5;

    /** HUD+ fits its content between these; the Max width setting lowers the top. */
    static final int MIN_WIDTH = 72;

    static final int MAX_WIDTH = 320, ICON = 20;
    int rowHeight = ICON, indent = ICON + 4;
    static final Color TEAL = new Color(0, 200, 180), CORAL = new Color(255, 127, 80);
    static final Color GOLD_ROW = new Color(255, 200, 40, 50);
    static final long FADE_IN_MILLIS = 350;

    /** Cadence of the final-seconds countdown blink (owner live 2026-10-13). */
    static final long WARNING_BLINK_MILLIS = 500;
    HudBuilder builder;
    GpManagerConfig config;
    TextSize fontSize;
    Font body, bold, big;
    int opacity = -1;
    Color background;
    int widthTrip = Integer.MIN_VALUE, grownWidth;

    /** The snapshot the layout below was measured for, and that layout. */
    HudFrame measured;

    int measuredKey;
    Dimension size;
    int restHeight, trayHeight;
    boolean compact;

    /**
     * No title: the timer and rate share the gem row, so no row is left half empty (owner
     * 2026-09-28).
     */
    boolean rising;

    int[] moneyX = new int[4], moneyY = new int[4];
    int moneyHeight;

    /** Wall clock minus the snapshot's clock, fixed when a snapshot is first painted. */
    HudFrame clocked;

    long clockOffset;
    String title = "", context = "";
    String[] names = {};
    BufferedImage[] sprites = {};

    HudOverlay(HudBuilder builder, GpManagerConfig config) {
        this.builder = builder;
        this.config = config;
        setPosition(OverlayPosition.TOP_LEFT);
    }

    public Dimension render(Graphics2D g) {
        var frame = builder.current();
        if (!frame.visible) return null;
        fonts();
        var fb = g.getFontMetrics(body);
        var fh = g.getFontMetrics(bold);
        var fg = g.getFontMetrics(big);
        int key = fontSize.ordinal() * 1000 + config.hudMaxWidth();
        if (frame != measured || key != measuredKey) {
            measure(frame, fb, fh, fg);
            measured = frame;
            measuredKey = key;
        }
        long wall = now();
        if (frame != clocked) {
            clocked = frame;
            clockOffset = wall - frame.builtAt;
        }
        long now = wall - clockOffset;
        float open = trayOpen(frame, now, !config.reducedMotion());
        int height = restHeight + round(trayHeight * open);
        if (size.height != height) size = new Dimension(size.width, height);
        // Bitmap faces and item sprites stay sharp without smoothing; restored afterwards.
        Object textHint = g.getRenderingHint(KEY_TEXT_ANTIALIASING);
        Object imageHint = g.getRenderingHint(KEY_INTERPOLATION);
        var plain = g.getComposite();
        Shape clip = g.getClip();
        g.setRenderingHint(KEY_TEXT_ANTIALIASING, VALUE_TEXT_ANTIALIAS_OFF);
        g.setRenderingHint(KEY_INTERPOLATION, VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        try {
            paint(g, frame, fb, fh, fg, plain, now, open);
        } finally {
            g.setComposite(plain);
            g.setClip(clip);
            if (textHint != null)
                g.setRenderingHint(KEY_TEXT_ANTIALIASING, textHint);
            if (imageHint != null) g.setRenderingHint(KEY_INTERPOLATION, imageHint);
        }
        return size;
    }

    /**
     * How far the tray is open, 0..1: it eases open when it appears and shut once it folds. Its
     * slide never waits on the chip beside Net.
     */
    static float trayOpen(HudFrame frame, long now, boolean motion) {
        if (frame.rows.isEmpty() && frame.more.isEmpty()) return 0f;
        if (!motion) return 1f;
        float opening = ease((now - frame.openedAt) / (float) HudBuilder.FADE_MILLIS);
        return frame.foldAt == 0 ? opening : min(opening, ease((frame.foldAt + HudBuilder.FADE_MILLIS - now)
                                        / (float) HudBuilder.FADE_MILLIS));
    }

    static float ease(float t) {
        float x = max(0f, min(1f, t));
        return x * x * (3f - 2f * x);
    }

    void paint(Graphics2D g, HudFrame frame, FontMetrics fb, FontMetrics fh, FontMetrics fg, Composite plain, long now,
            float open) {
        int width = size.width, right = width - PAD;
        boolean motion = !config.reducedMotion();
        g.setColor(background);
        g.fillRect(0, 0, width, size.height);
        int y = PAD;
        g.setColor(gemColor(frame.gem, motion, now));
        g.fillOval(PAD, y + fh.getHeight() / 2 - 3, 7, 7);
        Color rateColor = frame.rate.endsWith("/h") ? WHITE : LABEL;
        // The timer keeps the left; the kill count owns the right, with the rate under it on the
        // money row (owner live 2026-10-14) — the timer never jumps sides.
        String head = rising ? frame.rate : title;
        text(g, body, frame.timer, PAD + 11, y + fh.getAscent(), LABEL);
        text(g, body, head, right - width(fb, head), y + fh.getAscent(), rising ? rateColor : WHITE);
        if (compact) return;
        y += fh.getHeight() + 1;
        // The chip keeps its own few seconds and fades on its own, whatever the tray is doing.
        float chip = !motion ? 1f : ease((frame.chipUntil - now) / (float) HudBuilder.FADE_MILLIS);
        int baseline = y + fg.getAscent();
        text(g, big, frame.net, PAD, baseline, frame.netColor);
        fade(g, plain, chip);
        text(g, body, frame.trip, PAD + moneyX[2], baseline + moneyY[2], frame.tripColor);
        g.setComposite(plain);
        if (!rising)
            text(g, body, frame.rate, right - width(fb, frame.rate), baseline + moneyY[3], rateColor);
        y += moneyHeight;
        if (frame.progress >= 0d) {
            // The target label rides the bar's end; the bar shortens to make room (owner live
            // 2026-10-13: "/ 200k" on the money row stretched HUD+ wide).
            int room = frame.target.isEmpty() ? 0 : 6 + width(fb, frame.target);
            int barY = y + (room == 0 ? 0 : (fb.getHeight() - 3) / 2);
            g.setColor(LINE);
            g.fillRect(PAD, barY, width - PAD * 2 - room, 3);
            g.setColor(frame.targetDone ? GAIN.color : frame.warning ? LOSS.color : ACCENT);
            g.fillRect(PAD, barY, (int) round((width - PAD * 2 - room) * frame.progress), 3);
            if (room > 0) {
                // The final-seconds warning blinks unless reduced motion, then stays solid
                // (owner live 2026-10-13).
                Color targetColor = LABEL;
                if (frame.warning) {
                    boolean dim = !config.reducedMotion() && (now / WARNING_BLINK_MILLIS) % 2 == 0;
                    targetColor = dim ? LABEL : LOSS.color;
                }
                text(g, body, frame.target, right - width(fb, frame.target), y + fb.getAscent(), targetColor);
            }
            y += room == 0 ? 5 : fb.getHeight();
        }
        if (!context.isEmpty()) {
            text(g, body, context, PAD, y + fb.getAscent(), frame.contextColor);
            y += fb.getHeight();
        }
        if (trayHeight == 0) return;
        // The tray slides: clipped to the height it has opened to, and fading with it.
        g.clipRect(0, 0, width, size.height);
        fade(g, plain, open);
        // The heading sits tight on its rows: no leading, no gap (owner 1.1).
        text(g, body, frame.trayLabel, PAD, y + fb.getAscent(), LABEL);
        y += fb.getAscent() + fb.getDescent();
        for (int i = 0; i < names.length; i++) {
            HudFrame.Row row = frame.rows.get(i);
            fade(g, plain, motion ? min(open, (now - row.born) / (float) FADE_IN_MILLIS) : open);
            if (row.gold) {
                g.setColor(GOLD_ROW);
                g.fillRect(0, y, width, rowHeight);
            }
            // Sprites are fitted once per snapshot: shrunk smoothly, never blown up, centred.
            if (sprites[i] != null) g.drawImage(sprites[i], PAD, y, null);
            int textY = y + (rowHeight + fb.getAscent()) / 2 - 1;
            String value = row.narrow != null && right(fb, row) > width - PAD * 2 - indent ? row.narrow : row.value;
            int valueX = row.name.isEmpty() ? PAD + indent : right - width(fb, value);
            text(g, body, names[i], PAD + indent, textY, row.highlight == null ? WHITE : row.highlight);
            text(g, body, value, valueX, textY, row.color);
            text(g, body, row.tag, row.name.isEmpty() ? valueX + width(fb, value) + 4
                            : valueX - 4 - width(fb, row.tag), textY, LABEL);
            y += rowHeight;
        }
        fade(g, plain, open);
        text(g, body, frame.more, PAD, y + fb.getAscent(), LABEL);
    }

    static void fade(Graphics2D g, Composite plain, float alpha) {
        g.setComposite(alpha >= 1f ? plain : AlphaComposite.SrcOver.derive(max(0f, alpha)));
    }

    /**
     * Width, height, fitted text and sprite crops for one snapshot. While the tray is open the
     * width only grows, so arriving rows never jolt it; once it folds HUD+ settles back.
     */
    void measure(HudFrame frame, FontMetrics fb, FontMetrics fh, FontMetrics fg) {
        int rows = frame.rows.size();
        boolean tray = !frame.rows.isEmpty() || !frame.more.isEmpty();
        compact = frame.title.isEmpty() && "—".equals(frame.net) && frame.target.isEmpty() && frame.trip.isEmpty()
                        && frame.context.isEmpty() && !tray;
        rising = frame.title.isEmpty();
        // Ticking figures are measured with their widest digits, so the width never wobbles per
        // second.
        int header = 11 + 8 + steady(fb, frame.timer) + (rising ? steady(fb, frame.rate) : 0);
        int content = compact ? header : max(width(fb, frame.context), steady(fg, frame.net)
                                        + (frame.trip.isEmpty() ? 0 : 6 + steady(fb, frame.trip))
                                        + (rising ? 0 : 8 + steady(fb, frame.rate)));
        // The bar row needs room for its end label even when the money row is tiny.
        content = max(content, frame.target.isEmpty() ? 0 : 6 + steady(fb, frame.target));
        // Owner 1.1: with item icons off a row is text-high and starts at the edge.
        boolean icons = config.showItemIcons();
        rowHeight = icons ? ICON : fb.getAscent() + fb.getDescent() + 2;
        indent = icons ? ICON + 4 : 0;
        for (HudFrame.Row row : frame.rows)
            content = max(content, indent + 8 + width(fb, row.name) + right(fb, row));
        content = max(content, width(fb, frame.more));
        int ceiling = max(MIN_WIDTH, min(MAX_WIDTH, config.hudMaxWidth()));
        int width = max(MIN_WIDTH, min(ceiling, max(content, width(fb, frame.title) + header) + PAD * 2));
        if (!tray || frame.tripId != widthTrip) {
            widthTrip = frame.tripId;
            grownWidth = 0;
        }
        grownWidth = !tray ? 0 : min(ceiling, max(grownWidth, width));
        width = max(width, grownWidth);
        int inner = width - PAD * 2;
        // A short status stays on one line only when both figures fit the selected ceiling.
        compact &= content <= inner;
        moneyHeight = moneyLayout(frame, fb, fg, inner);
        title = fit(fb, frame.title, inner - header);
        if (!title.equals(frame.title) && frame.title.startsWith("KC: "))
            // The count survives tight widths: the prefix goes before the ellipsis does
            // (owner live 2026-10-14).
            title = fit(fb, frame.title.substring(4), inner - header);
        context = fit(fb, frame.context, inner);
        names = new String[rows];
        sprites = new BufferedImage[rows];
        for (int i = 0; i < rows; i++) {
            HudFrame.Row row = frame.rows.get(i);
            // Owner 1.2: a long name shortens before its count, so the "×2" always shows.
            String count = row.name.substring(max(0, row.name.lastIndexOf(" ×")));
            if (!count.startsWith(" ×")) count = "";
            names[i] = row.name.isEmpty() ? "" : fit(fb, row.name.substring(0, row.name.length() - count.length()),
                                    inner - indent - 8 - right(fb, row) - width(fb, count)) + count;
            sprites[i] = row.icon == null ? null : fitIcon(row.icon, ICON);
        }
        restHeight = PAD * 2 + fh.getHeight() + (compact ? 0 : 1 + moneyHeight + (frame.progress >= 0d
                                                ? frame.target.isEmpty() ? 5 : fb.getHeight() : 0)
                                        + (context.isEmpty() ? 0 : fb.getHeight()));
        trayHeight = !tray ? 0 : fb.getAscent() + fb.getDescent() + rows * rowHeight
                                + (frame.more.isEmpty() ? 0 : fb.getHeight());
        size = new Dimension(width, restHeight + trayHeight);
    }

    /** Net leads; a new change or rate that cannot share its line wraps below it. */
    int moneyLayout(HudFrame frame, FontMetrics fb, FontMetrics fg, int inner) {
        String[] values = {frame.net, "", frame.trip, rising ? "" : frame.rate};
        int x = steady(fg, frame.net), y = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i].isEmpty()) {
                moneyX[i] = x;
                moneyY[i] = y;
                continue;
            }
            int gap = values[i].isEmpty() || i == 1 ? 0 : i == 2 ? 6 : 8, width = steady(fb, values[i]);
            if (x + gap + width > inner) {
                x = 0;
                y += fg.getHeight();
                gap = 0;
            }
            moneyX[i] = x + gap;
            moneyY[i] = y;
            x += gap + width;
        }
        return y + fg.getHeight();
    }

    /** Width of text with every digit counted as the font's widest digit. */
    static int steady(FontMetrics metrics, String value) {
        char widest = '0';
        for (char digit = '1'; digit <= '9'; digit++)
            widest = metrics.charWidth(digit) > metrics.charWidth(widest) ? digit : widest;
        return width(metrics, value.replaceAll("[0-9]", valueOf(widest)));
    }

    /** A row's value and tag, the part that never shortens. */
    static int right(FontMetrics fb, HudFrame.Row row) {
        return width(fb, row.value) + (row.tag.isEmpty() ? 0 : width(fb, row.tag) + 4);
    }

    /** The text, or its longest start that fits {@code room} pixels followed by "…". */
    static String fit(FontMetrics metrics, String value, int room) {
        if (width(metrics, value) <= room) return value;
        int budget = room - metrics.stringWidth("…"), low = 0, high = value.length();
        while (low < high) {
            int middle = low + high + 1 >>> 1;
            if (width(metrics, value.substring(0, middle)) <= budget) low = middle;
            else high = middle - 1;
        }
        return value.substring(0, low).trim() + "…";
    }

    void fonts() {
        var wanted = config.hudTextSize();
        if (wanted != fontSize) {
            fontSize = wanted;
            Font small = FontManager.getRunescapeSmallFont(), regular = FontManager.getRunescapeFont();
            Font heavy = FontManager.getRunescapeBoldFont();
            // RuneLite's pixel fonts only render cleanly at their own size, so sizes pick fonts
            // rather than scale them: Small, Normal, and Large (the bold face throughout).
            body = wanted == TextSize.Small ? small : wanted == TextSize.Large ? heavy : regular;
            bold = wanted == TextSize.Small ? regular : heavy;
            big = wanted == TextSize.Small ? regular : body;
        }
        int alpha = config.hudOpacity();
        if (alpha != opacity) {
            opacity = alpha;
            background = new Color(PANEL.getRed(), PANEL.getGreen(), PANEL.getBlue(),
                            max(0, min(255, alpha * 255 / 100)));
        }
    }

    Font body() {
        fonts();
        return body;
    }

    Font bold() {
        fonts();
        return bold;
    }

    static Color gemColor(Gem gem, boolean motion, long now) {
        switch (gem) {
            case LIVE:
                return TEAL;
            case PAUSED:
                return CORAL;
            case AWAY:
                return motion ? new Color(255, 127, 80, 110 + (int) (100 * abs(sin(now / 400d)))) : CORAL.darker();
            case PVP:
                return LOSS.color;
            default:
                return OFF;
        }
    }

    /** Width of text as {@link #text} draws it. */
    static int width(FontMetrics metrics, String value) {
        return value == null ? 0 : metrics.stringWidth(value.replace('−', '-'));
    }

    /** Text with the one-pixel shadow every RuneLite overlay uses. */
    static void text(Graphics2D g, Font font, String value, int x, int y, Color color) {
        if (empty(value)) return;
        // RuneLite's font has no true minus sign (U+2212); a hyphen reads the same.
        String shown = value.replace('−', '-');
        g.setFont(font);
        g.setColor(BLACK);
        g.drawString(shown, x + 1, y + 1);
        g.setColor(color);
        g.drawString(shown, x, y);
    }
}

/**
 * The HUD+ item tray: what the current streak received, claimed, lost or traded. A streak is one
 * run of the same work: fighting one NPC, or one skill. Attacking another NPC, another kind of
 * work, or a quiet gap starts the next streak, so one NPC's loot never lands on another's tray. Fed
 * on the client thread by kills, bookings and reward sightings, read on the EDT by {@link
 * HudBuilder}. Presentation only; nothing here books or values money, it only mirrors the bookings.
 */
class HudTray {
    /**
     * A single drop worth this much lights its row gold and raises a moment, unless Ground Items
     * sets it.
     */
    static final long BIG_DROP = 10_000_000;

    long big = BIG_DROP;

    @RequiredArgsConstructor
    enum State {
        // A tray row always reads icon, name ×count and value; the state stays internal
        // (owner 2026-10-13: no "claimed"/"pending"/"recovered" words on loot rows).
        RECEIVED(""), PENDING(""), CLAIMED(""), LOST(""), RECOVERED(""), TRADED("");
        final String tag;
    }

    /** One tray entry; quantities of the same item and state add up. */
    @RequiredArgsConstructor
    static class Entry {
        final int item;
        final String name;
        final State state;
        final long unitPrice;
        long quantity, value;
        boolean gold;
        long born;

        /**
         * The last time this entry grew; the tray opens and folds by its visible entries' times.
         */
        long seen;

        /** Untradeable with no alch value: the tray shows no value, never "?". */
        boolean worthless;
    }

    Map<String, Entry> entries = new LinkedHashMap<>();

    /**
     * The NPC this streak first killed ("" before its first kill), every NPC it killed, and its
     * kills.
     */
    String source = "";

    Set<String> counts = new LinkedHashSet<>();
    int kills;

    /**
     * The streak a switch of target ended, kept through its quiet gap: switching back merges it.
     */
    Map<String, Entry> prevEntries = new LinkedHashMap<>();

    Set<String> previous = new LinkedHashSet<>();
    String prevSource = "";
    int prevKills, prevTrip;
    long prevEventAt, eventAt;

    /**
     * Changes with every new streak (and on clear); HUD+ rebases the streak's Net and width on it.
     */
    int tripId;

    String label = "Looted";

    /** What the newest booking added (counted rows only): the chip beside Net. */
    long delta;

    long deltaAt;

    long lastBatch = Long.MIN_VALUE;

    /** A booking settled; mirror its visible item flows onto the tray. */
    synchronized void booked(Transaction transaction, long now, boolean xy, long batch) {
        if (transaction == null) return;
        if (!transaction.counts() || transaction.isTransfer() || transaction.type() == UNCERTAIN
                // Owner 2026-09-28: eating and drinking stay off HUD+ (a sip's "(3)" is no drop).
                || transaction.action() == ActionKind.EAT || transaction.action() == ActionKind.DRINK
                || ActionReader.doseStep(transaction.flows()))
            return;
        boolean market = transaction.context() == Context.MARKET || transaction.type() == TRADE;
        boolean claimed = transaction.action() == ActionKind.DEFERRED_CLAIM
                        || !transaction.claimId().isEmpty();
        if (!counts.isEmpty() && transaction.note().startsWith("Loot from ")
                && !has(counts, transaction.activityName()))
            // Another NPC's leftover drop, picked up after this streak began: it counts in Net, not
            // here.
            return;
        boolean reclaim = (transaction.activityName() + " " + transaction.note()).toLowerCase(ROOT)
                        .contains("reclaim");
        // Only deaths, drops and destroys ride the tray as lost; other item use is routine.
        String drop = Phrases.has(transaction.note(), "Dropped", "Destroyed") ? transaction.note() : "";
        boolean lost = transaction.original() == PK_DEATH_LOSS || transaction.context() == Context.PK_DEATH
                        || !drop.isEmpty();
        // Owner 2026-10-09: opening caskets or impling jars is one row per container with its
        // total, so
        // a stack opened never floods the tray; a big drop still gets its own gold row.
        Flow container = opened(transaction.flows());
        if (container != null && !market && !lost) {
            event(now, xy, "Opened", null);
            beginBatch(batch, now);
            long net = 0, count = 0;
            for (Flow flow : transaction.flows()) {
                net = sum(net, flow.value);
                if (flow.cost() && flow.item == container.item) count -= flow.quantity;
                if (flow.gain() && flow.value >= big)
                    add(flow.item, flow.name, RECEIVED, flow.unitPrice, flow.quantity, flow.value,
                                            deltaAt, 0).gold = true;
            }
            add(container.item, container.name, RECEIVED, 0, count, net, deltaAt, 0);
            delta = sum(delta, net);
            return;
        }
        boolean any = false;
        long delta = 0;
        // Owner 2026-10-09: a trade rides the tray as its item, bought or sold, with the coins it
        // took
        // or brought when it is the one item; its coins never get a row of their own.
        long coins = 0;
        int items = 0;
        for (Flow flow : transaction.flows()) {
            if (flow.item == 995) coins += flow.value;
            else items++;
        }
        for (Flow flow : transaction.flows()) {
            if (flow.quantity == 0 || market && flow.item == 995) continue;
            boolean gain = flow.quantity > 0;
            // Routine rune/ammo/charge use never rides the tray, but a pickup is a drop
            // like any other (owner 2026-09-29).
            if (!gain && !market && routine(flow.name)) continue;
            if (!gain && !market && !lost) continue;
            if (!any) {
                // A drop or a GE or player trade joins the running trip; on an empty tray it reads
                // "Dropped", "Traded" (owners 2026-09-28, 2026-10-09) or "Exchanged" for a shop.
                event(now, xy, claimed ? "Claimed" : market || !drop.isEmpty() ? null
                                        : lost ? "Lost" : verb(transaction, flow.name, now), null);
                if ((market || !drop.isEmpty()) && entries.isEmpty())
                    label = market ? Semantics.shopExchange(transaction) ? "Exchanged" : "Traded" : drop;
                any = true;
                if (!market) beginBatch(batch, now);
            }
            int id = flow.item;
            long quantity = abs(flow.quantity);
            State state = market ? TRADED : !gain ? LOST : reclaim ? RECOVERED
                                            : claimed ? CLAIMED : RECEIVED;
            long born = 0;
            if (state == RECEIVED || state == CLAIMED) {
                // A claim turns its pending row into Claimed in place, keeping its first-seen
                // identity.
                Entry pending = entries.get(key(PENDING, id));
                if (pending != null && take(PENDING, id, quantity) > 0) {
                    state = CLAIMED;
                    born = pending.born;
                }
            }
            Entry entry = add(id, flow.name, state, flow.unitPrice, quantity, !market ? flow.value
                                    : items == 1 && coins != 0 ? abs(coins) : abs(flow.value), market ? now : deltaAt, born);
            entry.worthless |= flow.source() == PriceSource.NO_VALUE;
            delta = sum(delta, state == TRADED ? 0 : flow.value);
            if (gain && state != TRADED && flow.value >= big)
                entry.gold = true;
        }
        if (any) this.delta = sum(this.delta, delta);
    }

    /** One tick is one chip, even when its receipts arrive at different milliseconds. */
    void beginBatch(long batch, long now) {
        if (batch == lastBatch) return;
        lastBatch = batch;
        delta = 0;
        deltaAt = now;
    }

    /**
     * A reward window reported loot it is holding; nothing is counted until it settles. Ground loot
     * never comes here: the tray lists what was received, so a pickup appears once, in place.
     */
    synchronized void observed(int id, String name, long quantity, long price, long now, boolean xy) {
        if (quantity <= 0) return;
        event(now, xy, null, null);
        add(id, name, PENDING, price, quantity, price * quantity, now, 0);
    }

    /**
     * One NPC loot event is one kill; a kill of another NPC starts that NPC's streak, unless it was
     * killed in the streak before, still inside that streak's quiet gap: then both are one mixed
     * streak again (owner 1.1).
     */
    synchronized void kill(String npc, long now, boolean xy) {
        String name = npc == null ? "" : npc.trim();
        if (name.isEmpty() || "NPC loot".equals(name)) return;
        if (!has(counts, name)) {
            if (kills > 0) {
                // The new target's first kill refreshes the tray to its own streak; loot already
                // booked before any kill stays (owner 2026-10-01).
                boolean back = has(previous, name) && now - prevEventAt < quietMillis();
                var rows = new LinkedHashMap<>(entries);
                var previous = new LinkedHashSet<>(counts);
                int had = kills, trip = tripId;
                long last = eventAt;
                String from = source;
                restart(xy);
                if (back) {
                    if (!xy) {
                        merge(prevEntries.values());
                        merge(rows.values());
                    }
                    counts.addAll(this.previous);
                    counts.addAll(previous);
                    kills = prevKills + had;
                    tripId = prevTrip;
                    source = prevSource;
                    this.previous = new LinkedHashSet<>();
                } else {
                    prevEntries = rows;
                    this.previous = previous;
                    prevKills = had;
                    prevTrip = trip;
                    prevEventAt = last;
                    prevSource = from;
                }
            }
            label = "Looted";
        }
        event(now, xy, "Looted", name);
        kills++;
    }

    /**
     * The live kill streak ("Guard & Man", 5), or null before a kill or once the streak went quiet.
     */
    synchronized Map.Entry<String, Integer> streak(long now) {
        return kills == 0 || now - eventAt >= quietMillis() ? null
                : new AbstractMap.SimpleImmutableEntry<>(names(), kills);
    }

    /** The streak's NPCs: "Guard", "Guard & Man", "Guard, Man +1"; empty before a kill. */
    synchronized String names() {
        var list = new ArrayList<String>();
        for (String npc : counts) list.add(activity(npc));
        int n = list.size();
        return n == 0 ? "" : n == 1 ? list.get(0) : n == 2 ? list.get(0) + " & " + list.get(1)
                                : list.get(0) + ", " + list.get(1) + " +" + (n - 2);
    }

    static boolean has(Set<String> names, String name) {
        for (String each : names) {
            if (each.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /** Owner or Grind changed: start clean. */
    synchronized void clear() {
        entries.clear();
        tripId++;
        source = "";
        counts.clear();
        previous = new LinkedHashSet<>();
        kills = 0;
        eventAt = 0;
        delta = 0;
        deltaAt = 0;
        lastBatch = Long.MIN_VALUE;
        label = "Looted";
    }

    /** Newest arrivals first; a row keeps its place while its quantity grows. */
    synchronized List<Entry> entries() {
        var list = new ArrayList<>(entries.values());
        // Owner 1.2: the row that last grew leads, so the newest loot is always on top.
        list.sort((a, b) -> a.seen != b.seen ? Long.compare(b.seen, a.seen) : Long.compare(b.born, a.born));
        return list;
    }

    /** The newest game tick's counted tray changes; 0 before any. */
    synchronized long lastDelta() {
        return delta;
    }

    /** When the newest booking's chip was set; 0 before any. */
    synchronized long lastDeltaAt() {
        return deltaAt;
    }

    /** Something booked rides the tray; a reward window's held (pending) loot is not booked yet. */
    synchronized boolean hasBooked() {
        return entries.values().stream().anyMatch(entry -> entry.state != PENDING);
    }

    synchronized int tripId() {
        return tripId;
    }

    /**
     * The tray heading: a kill streak's NPCs, else what the streak did ("Stole", "Claimed", "Mined"
     * ...).
     */
    synchronized String label() {
        return "Looted".equals(label) && !counts.isEmpty() ? names() : label;
    }

    /**
     * Every tray event belongs to a streak. A quiet gap, another kind of work ("Mined" after
     * "Looted") or a kill of another NPC ends it; the next streak starts with an empty tray unless
     * rows keep for the whole session. Folding only hides the tray; it never ends a streak.
     */
    void event(long now, boolean xy, String kind, String npc) {
        if (eventAt > 0 && now - eventAt >= quietMillis() || kind != null && !kind.equals(label)
                || npc != null && !counts.isEmpty() && !has(counts, npc))
            restart(xy);
        if (kind != null) label = kind;
        if (npc != null) {
            if (source.isEmpty()) source = npc;
            counts.add(npc);
        }
        eventAt = now;
    }

    void restart(boolean xy) {
        if (!xy)
            entries.clear();
        tripId++;
        source = "";
        counts.clear();
        kills = 0;
        eventAt = 0;
    }

    /** Identity of the streak the per-kill line measures: a new streak, or its first kill. */
    synchronized String streakKey() {
        return tripId + ":" + source;
    }

    /**
     * A streak goes quiet after three of its own kill intervals: at least a minute, at most five.
     */
    volatile long keepMillis = 60000;

    /** Owner-set streak window: the tray ends a streak this long after its last kill. */
    synchronized void keepMillis(long ms) {
        keepMillis = max(10000, min(3_600_000, ms));
    }

    long quietMillis() {
        return keepMillis;
    }

    long take(State from, int id, long quantity) {
        Entry source = entries.get(key(from, id));
        if (source == null) return 0;
        long moved = min(quantity, source.quantity);
        source.quantity -= moved;
        source.value = multiply(source.unitPrice, source.quantity);
        if (source.quantity <= 0) entries.remove(key(from, id));
        return moved;
    }

    void merge(Collection<Entry> rows) {
        for (Entry row : rows) {
            Entry entry = add(row.item, row.name, row.state, row.unitPrice, row.quantity, row.value, 0, row.born);
            entry.gold |= row.gold;
            entry.worthless |= row.worthless;
        }
    }

    Entry add(int id, String name, State state, long price, long quantity, long value, long now, long born) {
        Entry entry = entries.computeIfAbsent(key(state, id), ignored -> new Entry(id,
                                        name == null ? "Unknown item" : name, state, price));
        if (entry.born == 0) entry.born = born != 0 ? born : now;
        entry.seen = now;
        entry.quantity = sum(entry.quantity, quantity);
        entry.value = sum(entry.value, value);
        return entry;
    }

    static String key(State state, int id) {
        return state.name() + ":" + id;
    }

    /**
     * The casket or impling jar a booking opened: every cost is one, and something came out of it.
     * A caught impling spends an empty "Impling jar", which is no opening.
     */
    static Flow opened(List<Flow> flows) {
        return ActionReader.all(flows, -1, name -> pattern("clue").matcher(name).matches()
                                                || name.endsWith(" impling jar"))
                        && ActionReader.all(flows, 1, name -> true)
                ? flows.stream().filter(Flow::cost).findFirst().get() : null;
    }

    /** Runes, ammunition and charges are routine use: they count in Net but never ride the tray. */
    static boolean routine(String name) {
        // Runes, arrows, bolts, darts, javelins, throwing weapons and Zulrah's scales (d18
        // "routine").
        return pattern("routine").matcher(lower(name)).matches();
    }

    static final List<String[]> VERBS = rows("tray_verbs");
    static final Set<String> MINIONS = new HashSet<>();

    static {
        rows("boss_spawns").forEach(row -> MINIONS.add(row[0]));
    }

    /**
     * A boss's spawn (Greater Nechryael's death spawns): no header, streak, kill or activity of its
     * own.
     */
    static boolean minion(String npc) {
        return npc != null && MINIONS.contains(lower(npc));
    }

    /** The newest skilling XP's tray verb ("Mined") and when it arrived. */
    String xpVerb = "Looted";

    long xpAt;

    /** Skill XP arrived: an item booked moments later is that skill's work. */
    synchronized void xp(String skill, long now) {
        String kind = labelFor(skill);
        if ("Looted".equals(kind)) return;
        xpVerb = kind;
        xpAt = now;
    }

    /**
     * The tray verb for a receipt: a production run's own skill, else the skill whose XP came with
     * the item, else the activity. The activity names a skill only after its second XP drop, so it
     * can still be General, or the last skill, on a run's first item: every run stays one streak.
     */
    String verb(Transaction transaction, String item, long now) {
        String kind = transaction.context() == Context.PRODUCTION ? labelFor(transaction.note()) : "Looted";
        if ("Looted".equals(kind) && (transaction.type() == TransactionType.GAIN || transaction.type() == PROCESSING)
                && now - xpAt < 6000)
            kind = xpVerb;
        if ("Looted".equals(kind)) kind = labelFor(transaction.activityName());
        // Smithing XP makes bars at a furnace and items at an anvil.
        return "Smithed".equals(kind) && item != null && item.endsWith(" bar") ? "Smelted" : kind;
    }

    static String labelFor(String activity) {
        String lower = lower(activity);
        for (String[] row : VERBS) {
            if (lower.contains(row[0])) return row[1];
        }
        return "Looted";
    }
}

/**
 * Ledger = why the numbers changed (SIDEBAR_SPEC.md §3). The toolbar holds the fitted scope,
 * search and ⋯; the overview is folding paged tables (Gains, Costs with Supply / Loss, Market,
 * Pending, Review, Corrected). Opening a row shows its receipts; the selected receipt opens under
 * them with its short facts and one Correct ▾ menu. Corrections always target one exact canonical
 * receipt id.
 */
class LedgerPage implements Shell.Page {
    enum CorrectionOutcome {
        APPLIED, REFUSED, STALE
    }

    interface Actions {
        void openScopeMenu(JComponent anchor);

        default void recent() {}

        void costViewChanged(CostView view);

        void searchChanged(String text);

        /** Selection is navigation state only; a correction still targets one transaction id. */
        default void selectionChanged(
                String identifier, String id, String group) {}

        CorrectionPreview preview(String id, Correction decision);

        /** Null when the outcome reports back later, from the mutation owner. */
        CorrectionOutcome correct(String id, Correction decision, long revision);

        /** Asks how many to keep, then hands back {itemId, keep, total} and its Net preview. */
        void split(String id, BiConsumer<long[], CorrectionPreview> previewed);

        default void applySplit(String id, long[] split, long revision) {}

        void undoCorrection();

        /** Pins an open mutation menu to the live session; empty when none can be mutated. */
        default String pinMenuTarget() {
            return "";
        }

        /** Undoes the latest booked change (moved here from the Live menu). */
        default void undoLast() {}

        default void restoreUndo() {}

        default void decide(String id, Decision decision) {}

        void refresh();
    }

    static final int ROWS = 5;
    int noteWidth = 190;
    boolean raid;
    JLabel note(String text, Color color) { return Kit.note(text, color, noteWidth); }
    Actions actions;
    Function<Integer, BufferedImage> sprites;
    Map<Integer, ImageIcon> icons = new HashMap<>();
    JPanel bar = new JPanel(new BorderLayout(4, 0));
    JButton scope, more;
    JPanel body = stack(PANEL, GAP);
    IconTextField search = new IconTextField();
    Table income = new Table("GAINS"), cost = new Table("LOSSES"), market = new Table("TRADE");
    Table pending = new Table("PENDING"), review = new Table("REVIEW");

    /** The group-detail receipt list; persistent so its pager survives refreshes (F07). */
    Table receipts = new Table("ENTRIES");

    /** What a multi-item group used or received in all, item by item (owner 2026-10-03). */
    Table detailItems = new Table("USED");

    /** The scope/session/group the detail receipts belong to; a change restarts its pager. */
    String receiptOwner = "";
    String bagDetail = "";

    Table corrected = new Table("CORRECTED");
    JPanel detail = stack(PANEL, GAP);
    LedgerData data;
    boolean searching, quietSearch, corrections;
    String reviewSelected = "", anchored = "";
    Correction pendingCorrection;
    String transaction;
    CorrectionPreview preview;

    /** A split waiting for Confirm: {itemId, keep, total}; null for a correction. */
    long[] share;

    LedgerPage(Actions actions, Function<Integer, BufferedImage> sprites) {
        this.actions = actions;
        this.sprites = sprites == null ? id -> null : sprites;
        scope = button("This Grind ⌄", false, () -> {});
        scope.setPreferredSize(new Dimension(0, scope.getPreferredSize().height));
        onResize(scope, this::scopeTitle);
        scope.addActionListener(e -> actions.openScopeMenu(scope));
        JButton find = button("Search", false, this::toggleSearch);
        find.getAccessibleContext().setAccessibleName("Search");
        find.setToolTipText("Search this scope");
        more = button("···", false, () -> {});
        more.setToolTipText("Ledger actions");
        more.getAccessibleContext().setAccessibleName("Ledger actions");
        more.addActionListener(e -> moreMenu().show(more, 0, more.getHeight()));
        // Owner 2026-10-01: one toolbar band shared with the Grinds page; actions sit on the right.
        headerBand(bar, scope, find, more);
        search.setIcon(IconTextField.Icon.SEARCH);
        search.setBackground(CARD);
        search.setHoverBackgroundColor(SELECTED);
        search.setPreferredSize(new Dimension(0, 26));
        search.getDocument().addDocumentListener(new DocumentListener() {
                            public void insertUpdate(DocumentEvent e) {
                                searched();
                            }

                            public void removeUpdate(DocumentEvent e) {
                                searched();
                            }

                            public void changedUpdate(DocumentEvent e) {
                                searched();
                            }
                        });
        for (var table : new Table[] {income, cost, market, pending, review, corrected})
            table.setFoldable(true).setPageSize(ROWS);
        receipts.setPageSize(ROWS);
        cost.setPicker(costPicker(CostView.ALL, new int[4]));
        pending.setEmpty("Nothing waiting");
        review.setEmpty(msg("nothing-needs"));
        pad(body, 0, GAP, GAP, 0);
    }

    public String id() {
        return LEDGER;
    }

    public JComponent pageBar() {
        return bar;
    }

    public JComponent body() {
        return body;
    }

    /** Reset presentation-only state before another owner is shown. */
    void resetOwner() {
        prepareEntry();
        corrections = false;
        searching = false;
        setSearchText("");
        for (var table : new Table[] {income, cost, market, pending, review, corrected}) {
            table.setRows(emptyList());
            table.resetPage();
        }
        receiptOwner = "";
        receipts.setRows(emptyList());
        detail.removeAll();
    }

    /** A new deep link replaces any previous local selection and preview. */
    void prepareEntry() {
        bagDetail = "";
        anchored = "";
        clearPending();
        reviewSelected = "";
    }

    void apply(LedgerData next) {
        boolean scopeChanged = data == null || data.entry.scope != next.entry.scope
                        || !valueOf(data.entry.session)
                                .equals(valueOf(next.entry.session));
        data = next;
        more.setVisible(!liveCategory());
        bar.setVisible(!liveCategory());
        int size = liveCategory() ? 15 : ROWS;
        for (var table : new Table[] {income, cost, market})
            if (table.size != size) table.setPageSize(size);
        String selection = next.entry.scope.name() + "|" + next.entry.group + "|"
                        + next.entry.highlightContributionId + "|" + next.entry.transaction;
        if (!selection.equals(anchored)) {
            anchored = selection;
            // A preview belongs to one receipt; opening another cancels it.
            if (transaction != null && !transaction.equals(next.entry.transaction))
                clearPending();
        }
        scopeTitle();
        // Owner 2026-10-01 (F03): Runs today is whole runs that started today, never calendar-day
        // money.
        scope.setToolTipText(next.scopeName + (next.entry.scope == TODAY ? " · " + msg("current-run") : ""));
        if (scopeChanged) {
            for (var table : new Table[] {income, cost, market, pending, review, corrected})
                table.resetPage();
        }
        searching = !liveCategory() && (searching || !next.entry.search.isEmpty());
        for (var part : body.getComponents())
            if (part != search || !searching) body.remove(part);
        if (searching) {
            if (!search.getText().equals(next.entry.search)) setSearchText(next.entry.search);
            if (search.getParent() != body) body.add(search, 0);
        }
        if (next.entry.scope == TODAY)
            // Owner 2026-10-01 (F03): whole runs that started today, never calendar-day money.
            body.add(note(msg("runs-today"), LABEL));
        if (next.readonly && !raid) body.add(note(msg("correction-help"), LABEL));
        if (!liveCategory() && next.compacted && !next.detailed)
            body.add(note(msg("detailed-entries"), LABEL));
        if (next.entry.table == KILL) body.add(note(msg("kn"), LABEL));
        if (next.detail != null) {
            buildDetail(next);
            body.add(detail);
        } else
            overview(next);
        body.revalidate();
        body.repaint();
    }

    // ── overview ─────────────────────────────────────────────────────────────────────────────
    void overview(LedgerData data) {
        var focus = data.entry.table;
        if (focus == null || focus == GAINS || focus == KILL) {
            fill(income, raid ? "LOOT" : focus == KILL ? "KILLS" : "GAINS", data.income.groups, data.income.total, GAIN);
            body.add(income);
        }
        if (focus == null || focus == COSTS_SUPPLIES || focus == COSTS_LOSS || focus == DEATH) {
            cost.setPicker(focus == null && !raid ? costPicker(data.entry.view, data.costCounts) : null);
            fill(cost, raid ? "DEATH FEES" : focus == null ? "LOSSES" : focus.label,
                    data.cost.groups, data.cost.total, focus == COSTS_LOSS || focus == DEATH || data.entry.view == CostView.LOSS ? LOSS : SUPPLY);
            body.add(cost);
        }
        if (raid || focus != null && focus != Semantics.Table.MARKET) return;
        var settled = new ArrayList<Group>();
        var open = new ArrayList<Group>();
        long settledTotal = 0;
        for (Group group : data.market.groups) {
            var row = data.marketRow(group.marketId);
            if (!group.market || settled(group, row)) {
                // A shop exchange is a MARKET group with no GE offer lifecycle: it is settled.
                settled.add(group);
                settledTotal += group.value;
                // An offer canceled before anything filled moved no money: it leaves Pending.
            } else if (row == null || row.lifecycle != MarketRows.Lifecycle.CANCELLED_RETURNED)
                open.add(group);
        }
        fill(market, "TRADE", settled, focus == null || !data.entry.search.isEmpty() ? settledTotal : data.market.total, MARKET);
        body.add(market);
        if (focus != null) return;
        if (!open.isEmpty() || !data.pendingItems.isEmpty()) {
            var rows = new ArrayList<Row>();
            for (Group group : open) rows.add(groupRow(group, DIM));
            for (var item : data.pendingItems) {
                boolean valued = item.value > 0;
                rows.add(new Row("pending|" + item.name + "|" + item.item, icon(item.item), item.name,
                                "×" + exact(item.quantity), valued ? signed(item.value) : "", DIM, item.tip,
                                valued ? exactSigned(item.value) + " gp" : "", false, null, emptyList()));
            }
            pending.setTitle("PENDING · " + rows.size()).setTotal("", DIM).setMinRows(rows.size() > ROWS ? ROWS : 0)
                    .setRows(rows);
            body.add(pending);
        }
        if (data.review.total > 0 || data.entry.focusAudit == Audit.REVIEW) buildReview(data);
        if (corrections || data.entry.focusAudit == Audit.CORRECTED) {
            var rows = new ArrayList<Row>();
            for (var receipt : data.corrected) rows.add(receiptRow(receipt));
            corrected.setTitle("CORRECTED · " + data.corrected.size()).setEmpty(msg("nothing-corrected"))
                    .setRows(rows);
            body.add(corrected);
        }
    }

    void fill(Table table, String title, List<Group> groups, long total, Tone tone) {
        var rows = new ArrayList<Row>();
        for (Group group : groups)
            // Under All, loss rows keep their own colour beside supplies.
            rows.add(groupRow(group, tone == SUPPLY && group.table == COSTS_LOSS ? LOSS : tone));
        table.setTitle(title + " · " + groups.size())
                .setTotal(groups.isEmpty() && !liveCategory() || tone == DIM ? "" : signed(total), tone)
                .setMinRows(liveCategory() ? 0 : min(ROWS, rows.size())).setRows(rows);
    }

    /**
     * Owner 1.1: the Losses view is a dropdown beside the header (All, Supplies, Items, Charges).
     */
    JButton costPicker(CostView selected, int[] counts) {
        var pick = new JButton(selected.label + " ⌄");
        pick.setFont(small());
        pick.setForeground(selected == CostView.ALL ? LABEL : ACCENT);
        pick.setBorder(BorderFactory.createEmptyBorder());
        pick.setContentAreaFilled(false);
        pick.setFocusPainted(false);
        focus(pick);
        pick.getAccessibleContext().setAccessibleName("Losses filter: " + selected.label);
        pick.addActionListener(e -> menu(selected, counts).show(pick, 0, pick.getHeight()));
        return pick;
    }

    JPopupMenu menu(CostView selected, int[] counts) {
        var menu = new JPopupMenu();
        for (var view : CostView.values())
            menu.add(item((view == selected ? "✓ " : "") + view.label + (view == CostView.ALL ? ""
                                            : " " + exact(counts[view.ordinal()])), () -> {
                                if (view != selected) actions.costViewChanged(view);
                            }));
        return menu;
    }

    Row groupRow(Group group, Tone tone) {
        String value;
        Tone shown = tone;
        String meta = groupLead(group);
        String tip = group.name + " · " + (group.count > 1 ? (meta.isEmpty() ? "" : meta + " · ")
                                        + group.count + " entries" : meta);
        if (group.review) {
            value = "?";
            shown = REVIEW;
        } else if (group.market) {
            var row = data.marketRow(group.marketId);
            if (liveCategory() && group.incomplete()) {
                value = "?";
                shown = DIM;
            } else if (settled(group, row)) {
                value = signed(marketPrimary(data, group));
                tip = group.name + " · " + marketLead(data, group)
                                + msg("settlement-profit");
            } else {
                value = stateWord(row);
                shown = DIM;
                tip = group.name + " · " + group.context;
            }
        } else if (group.incomplete()) {
            value = "?";
            shown = DIM;
            tip = group.name + msg("incomplete-value");
        } else
            value = worth(group.value);
        String qty = group.claim < 0 ? "−" + max(1, group.quantity) : !group.failedOf().isEmpty()
                                ? group.failedOf() : group.quantity > 1 && group.item != 995
                                                && (!group.actionGroup() || group.charge) ? times(group.quantity)
                                        : "";
        if (liveCategory() && group.item != 995 && group.claim >= 0 && group.failedOf().isEmpty()) {
            if (group.dose) qty = LiveSnapshot.quantityText(group);
            else if (group.actionGroup() && !group.charge) qty = times(max(1, group.count));
            else if (group.quantity == 1) qty = times(1);
        }
        if (group.failed > 0) tip = group.name + " · " + group.failedTip();
        Runnable open = () -> openGroup(group.id);
        return new Row(group.id, icon(group.item), group.name
                        + (group.failed > 0 && group.failedOf().isEmpty() ? " (failed)" : "")
                        + (group.corrected ? " •" : ""), qty, value, shown, tip,
                group.incomplete() || group.market ? "" : exactSigned(group.value) + " gp", false, open,
                singletonList(new Menu("Open", open)));
    }

    void openGroup(String id) {
        clearPending();
        actions.selectionChanged(null, null, id);
    }

    Row receiptRow(Receipt receipt) {
        Runnable open = () -> actions.selectionChanged(receipt.transaction, receipt.id, null);
        return new Row(receipt.id, icon(receipt.item), receipt.name,
                receipt.quantity > 1 && receipt.item != 995 ? times(receipt.quantity) : "",
                receipt.unknown ? "?" : worth(receipt.value), receipt.unknown ? DIM : of(receipt.category),
                receipt.name + " · " + receipt.correction + " · " + receipt.why,
                receipt.unknown ? "" : exactSigned(receipt.value) + " gp",
                data.detail != null && data.detail.exact == receipt, open,
                singletonList(new Menu("Open", open)));
    }

    void buildReview(LedgerData data) {
        var rows = new ArrayList<Row>();
        ReviewRow chosen = null;
        for (var row : data.review.rows) {
            String name = row.items.isEmpty() ? "Unknown item" : row.items.get(0).name;
            boolean selected = row.transaction.equals(reviewSelected);
            if (selected) chosen = row;
            Runnable open = () -> {
                        reviewSelected = selected ? "" : row.transaction;
                        apply(this.data);
                    };
            rows.add(new Row(row.transaction, null, name, "", "?", REVIEW, name + " · " + row.why,
                            exactSigned(row.value) + " gp", selected, open, singletonList(new Menu("Decide…", open))));
        }
        review.setTitle("(!) REVIEW · " + data.review.total).setAction(null).setEmpty(data.readonly
                                ? msg("review-read") : data.review.total == 0 ? msg("nothing-needs")
                                        : msg("review-row")).setRows(rows);
        body.add(review);
        if (chosen != null && !data.readonly) body.add(decisionPanel(chosen));
    }

    /** The inline decision panel under a selected review row: what it is, why, then the choices. */
    JComponent decisionPanel(ReviewRow row) {
        var panel = stack(CARD, 4);
        pad(panel, 4, GAP, 6, GAP);
        panel.add(note(row.why, LABEL));
        String preview = netPreview(row, data == null ? 0 : data.net);
        if (!preview.isEmpty()) panel.add(note(preview, TEXT));
        var buttons = row();
        for (var decision : Decision.values()) {
            if (row.decisions.contains(decision))
                buttons.add(button(reviewLabel(decision), false, () -> {
                                    reviewSelected = "";
                                    actions.decide(row.transaction, decision);
                                }));
        }
        panel.add(buttons);
        return panel;
    }

    /** What Net becomes under a gain or a cost decision; nothing counts until the owner decides. */
    static String netPreview(ReviewRow row, long net) {
        long size = Math.abs(row.value);
        if (size == 0) return "";
        var text = new StringBuilder();
        if (row.decisions.contains(Decision.GAIN))
            text.append(msg("gain-help")).append(signed(net + size));
        if (row.decisions.contains(Decision.COST))
            text.append(text.length() == 0 ? msg("if-counted-cost") : " · as a cost: Net ").append(signed(net - size));
        return text.toString();
    }

    JPopupMenu moreMenu() {
        var menu = new JPopupMenu();
        menu.add(item(corrections ? "Hide corrected" : "Show corrected"
                                        + (data == null ? "" : " (" + data.corrected.size() + ")"), () -> {
                            corrections = !corrections;
                            if (data != null) apply(data);
                        }));
        // Owner 2026-10-01 (F01): undo mutates the live run only; read-only scopes disable it.
        boolean editable = data != null && !data.readonly && !actions.pinMenuTarget().isEmpty();
        menu.add(item(msg("undo-tip"), editable, actions::undoCorrection));
        menu.addSeparator();
        menu.add(item("Undo last change", editable, actions::undoLast));
        menu.add(item("Restore last undo", editable, actions::restoreUndo));
        return menu;
    }

    // ── detail: one group's receipts; the selected receipt opens in place ────────────────────
    void buildDetail(LedgerData data) {
        detail.removeAll();
        var open = data.detail;
        Group group = open.group;
        detail.add(back(raid ? data.entry.session.equals("raid:all") ? "All raids" : "Attempt" : !liveCategory() ? "Ledger" : data.entry.table.label, () -> actions.selectionChanged(null, null, null)));
        long latest = group.at;
        for (var receipt : open.entries) latest = max(latest, receipt.at);
        int count = group.market ? open.entries.size() : group.count;
        var heading = title(group.name, group.incomplete() ? "—" : exactSigned(group.value) + " gp",
                        group.incomplete() ? DIM : sign(group.value), (count == 0 ? "" : count + (group.market
                                                        ? count == 1 ? " trade · " : " trades · "
                                                        : count == 1 ? " entry · " : " entries · ")) + "Latest "
                                + age(positive(data.at - latest)));
        if (group.item <= -100000) heading.add(new JLabel(icon(group.item)), WEST);
        detail.add(heading);
        var items = items(group);
        // A charged weapon always lists what it spent, even one rune type (owner 1.2).
        boolean bagOpen = group.ids.contains(bagDetail);
        if (bagOpen) detail.add(back(group.name, () -> bagDetail = ""));
        if (!group.market && (items.size() > 1 || group.charge || (group.death || group.table == KILL) && !items.isEmpty())) {
            detailItems.setTitle((bagOpen ? "LOOTING BAG · " : group.death && !group.neutral ? "LOST · " : group.charge || group.value < 0 ? "USED · " : "ITEMS · ") + items.size())
                    .setRows(items);
            detail.add(detailItems);
        }
        String receiptsFor = data.entry.scope.name() + "|" + valueOf(data.entry.session) + "|"
                        + group.id;
        if (!receiptsFor.equals(receiptOwner)) {
            // Owner 2026-10-01 (F07): a new group starts at the first page; refreshes keep theirs.
            receiptOwner = receiptsFor;
            receipts.showPage(0);
        }
        receipts(data, open);
        // An offer with nothing collected yet has no receipt to show or correct; none is invented.
        // One entry needs no list: the header names it and its detail opens below (owner 1.5.2).
        if (open.entries.isEmpty()) detail.add(note(msg("collected-entries"), LABEL));
        else if (group.market || !LedgerData.oneEntry(open.entries)) detail.add(receipts);
        if (open.exact != null) detail.add(receipt(data, open.exact));
    }

    /** The group's items summed across its entries: "Blood rune ×124" and what it came to. */
    List<Row> items(Group group) {
        if (group.death || group.table == KILL) {
            var rows = new ArrayList<Row>();
            var contents = new ArrayList<Receipt>();
            boolean bag = group.entries.stream().anyMatch(entry -> entry.bag);
            for (var entry : group.entries) {
                if (entry.bag || bag && Engine.destroyedBag(entry.item)) contents.add(entry);
                else rows.add(receiptRow(entry));
            }
            if (bag) {
                if (group.ids.contains(bagDetail)) {
                    rows.clear();
                    for (var entry : contents) rows.add(receiptRow(entry));
                    return rows;
                }
                long value = contents.stream().mapToLong(entry -> entry.value).reduce(0, SafeMath::sum);
                boolean missing = contents.stream().anyMatch(entry -> entry.unknown);
                String selectedBag = contents.stream().filter(entry -> entry.bag).findFirst().orElseThrow().transaction;
                rows.add(new Row("bag", icon(ItemID.LOOTING_BAG), "Looting bag", "", missing ? "?" : worth(value),
                                missing ? DIM : sign(value), "View contents", missing ? "" : exactSigned(value) + " gp", false,
                                () -> { bagDetail = selectedBag; apply(data); }, null));
            }
            return rows;
        }
        var totals = new LinkedHashMap<String, ResourceSummary>();
        for (var card : LedgerData.cards(group))
            for (var item : card.resources)
                totals.merge(item.item + "|" + item.name, item, (a, b) -> new ResourceSummary(a.item,
                                        a.name, a.uses, a.quantity + b.quantity, a.value + b.value,
                                        a.knownSubtotal + b.knownSubtotal, a.incomplete || b.incomplete));
        var rows = new ArrayList<Row>();
        for (var item : totals.values())
            rows.add(new Row("item|" + item.item + "|" + item.name, icon(item.item), item.name,
                            item.item == 995 ? "" : times(item.quantity), item.incomplete ? "?" : worth(item.value),
                            item.incomplete ? DIM : sign(item.value), "",
                            item.incomplete ? "" : exactSigned(item.value) + " gp", false, null, null));
        return rows;
    }

    /**
     * Every entry in the group: a trade each for Market, one canonical transaction each otherwise.
     */
    void receipts(LedgerData data, Detail open) {
        Group group = open.group;
        var chosen = open.exact;
        var rows = new ArrayList<Row>();
        if (group.market) {
            for (var receipt : open.entries) {
                boolean realized = realized(receipt);
                long primary = receiptPrimary(receipt);
                rows.add(receiptRow(data, group, receipt.transaction, receipt.id, chosen != null
                                        && chosen.id.equals(receipt.id),
                                receiptTitle(receipt), realized ? signed(primary)
                                        : stateWord(receipt.market), realized ? MARKET : DIM,
                                receiptSub(receipt, data.at) + (receipt.corrected
                                                ? msg("result-corrected") : ""),
                                realized ? exactSigned(primary) + " gp" : "", splittable(receipt)));
            }
        } else {
            // A multi-item group's entries say what each use spent ("Blood rune ×2"), not the group
            // again.
            boolean several = items(group).size() > 1 || group.charge;
            for (var card : LedgerData.cards(group)) {
                boolean split = false;
                for (var receipt : open.entries)
                    split |= receipt.transaction.equals(card.transaction) && splittable(receipt);
                // A shop exchange's currency entries are named by what they bought or sold
                // (owner 2026-10-12): "Death rune ×5", not the tokkul that paid.
                var cardReceipt = LedgerData.firstReceipt(open.entries, card.transaction);
                String rowName = several ? card.summaryText()
                                : cardReceipt != null && !cardReceipt.traded.isEmpty()
                                        ? cardReceipt.traded : group.name;
                rows.add(receiptRow(data, group, card.transaction, card.id,
                                chosen != null && chosen.transaction.equals(card.transaction), rowName,
                                card.incomplete ? "?" : signed(card.value), card.incomplete ? DIM : sign(card.value),
                                card.summaryText() + " · " + age(positive(data.at - card.at)) + (card.incomplete
                                                ? " · known value " + exactSigned(card.knownSubtotal) + " gp" : ""),
                                card.incomplete ? "" : exactSigned(card.value) + " gp", split));
            }
        }
        receipts.setTitle("ENTRIES · " + rows.size()).setRows(rows);
    }

    Row receiptRow(LedgerData data, Group group, String identifier, String id, boolean selected,
            String name, String value, Tone tone, String tip, String exact, boolean canSplit) {
        Runnable open = () -> clickReceipt(identifier, id);
        var menus = new ArrayList<Menu>();
        if (!data.readonly)
            corrections(identifier, id, canSplit)
                    .forEach((label, action) -> menus.add(new Menu(label, action)));
        return new Row(id, icon(group.item), name, "", value, tone, tip, exact, selected, open, menus);
    }

    static boolean splittable(Receipt receipt) {
        // Owner 2026-10-01 (F02): a single unit cannot be split into shares.
        return !receipt.neutral && !receipt.unknown && receipt.gain() && receipt.quantity >= 2;
    }

    /** A receipt row opens its receipt under the list; clicking the open one closes it again. */
    void clickReceipt(String identifier, String id) {
        var open = data.detail;
        var chosen = open.exact;
        if (chosen != null && (open.group.market ? chosen.id.equals(id)
                        : chosen.transaction.equals(identifier)))
            actions.selectionChanged(null, null, open.group.id); else
            actions.selectionChanged(identifier, id, null);
    }

    /** The selected receipt under its list: why, short GE facts, then its correction. */
    JComponent receipt(LedgerData data, Receipt row) {
        var panel = stack(CARD, 4);
        pad(panel, 4, GAP, 6, GAP);
        var trade = row.market;
        boolean realized = trade != null && trade.realized() && trade.settledQty > 0;
        // Why only earns its space for exceptional states; a clean realized trade says Sold /
        // Bought.
        boolean clean = realized && trade.settledQty >= trade.filledQty && !row.review && !row.corrected && !row.split;
        // The selected row above already names the receipt and its value: say what happened, and
        // when.
        long at = trade == null ? row.at : !realized ? trade.timestampEpochMillis : trade.settlementAtEpochMillis > 0
                                        ? trade.settlementAtEpochMillis : row.at;
        String what = realized ? trade.side == GeOffer.Side.SELL ? "Sold" : "Bought" : trade != null
                                ? orderStateText(trade) : row.verb + (row.action.isEmpty() ? ""
                                                : " · " + row.action);
        panel.add(note(what + " · " + age(positive(data.at - at)), TEXT));
        var facts = new KeyValue(null);
        if (trade == null) {
            panel.add(note(row.why, LABEL));
            if (data.detail.group.death) panel.add(note("Corrections apply to the whole entry.", LABEL));
            if (!row.tradedItems.isEmpty()) panel.add(note(row.tradedItems, LABEL));
            // Owner 2026-10-01 (F11): a Charges receipt discloses its captured unit price and
            // source.
            if (!row.priceBasis.isEmpty())
                facts.put("Price", row.priceBasis, PLAIN, row.priceBasis);
        } else
            facts = marketFacts(trade, row, clean ? null : realized ? row.why : orderWhy(trade));
        if (row.corrected || row.split) {
            facts.put("Originally", "automatic · " + exactSigned(row.automaticValue) + " gp", DIM);
            facts.put("Corrected to", row.correction, PLAIN);
        }
        if (facts.lines() > 0) panel.add(facts);
        if (data.readonly)
            panel.add(note(msg("correction-rules"), LABEL)); else if (preview != null && row.transaction.equals(transaction)) {
            var preview = new KeyValue("PREVIEW");
            if (share != null)
                preview.put("Keep", "×" + exact(share[1]), PLAIN)
                        .put("Others", "×" + exact(share[2] - share[1]), PLAIN);
            panel.add(preview.put("Current Net", exactSigned(this.preview.currentNet) + " gp", PLAIN).put(
                                    share != null ? "After split" : "After correction",
                                    exactSigned(this.preview.afterNet) + " gp", PLAIN).put("Change",
                                    exactSigned(this.preview.change) + " gp", sign(this.preview.change)));
            panel.add(note(this.preview.note(), LABEL));
            JButton confirm = button("Confirm", true, this::confirm);
            confirm.getAccessibleContext().setAccessibleName(msg("confirm-correction"));
            panel.add(row(button("Cancel", false, () -> {
                                        clearPending();
                                        actions.refresh();
                                    }), confirm));
        } else {
            JButton correct = button("Correct ⌄", false, () -> {});
            correct.getAccessibleContext().setAccessibleName(msg("correct-entry"));
            correct.addActionListener(e -> correctMenu(row).show(correct, 0, correct.getHeight()));
            panel.add(row(correct));
        }
        return panel;
    }

    JPopupMenu correctMenu(Receipt row) {
        var menu = new JPopupMenu();
        corrections(row.transaction, row.id, splittable(row))
                .forEach((label, action) -> menu.add(item(label, action)));
        return menu;
    }

    /**
     * The human Market lines with their explanations; "Why" leads when the state is exceptional.
     */
    static KeyValue marketFacts(MarketRows.Row trade, Receipt row, String why) {
        var facts = new KeyValue("MARKET");
        if (why != null) facts.put("Why", why, PLAIN, why);
        for (String line : humanLines(trade, row.corrected)) {
            int split = line.indexOf('|');
            String label = split < 0 ? line : line.substring(0, split);
            String value = split < 0 ? "" : line.substring(split + 1);
            facts.put(label, value, PLAIN, marketTip(trade, label, value));
        }
        return facts;
    }

    static String marketTip(MarketRows.Row trade, String label, String value) {
        if (value.endsWith("Corrected")) return msg("result-adjusted");
        switch (label) {
            case "Received":
                return "not proven".equals(value) ? msg("collected-cash") : msg("received-cash");
            case "Spent":
                return msg("spent-cash");
            case "GE tax":
                return msg("proven-item");
            case "Previously counted":
                return "Unknown".equals(value) ? msg("gp-manager-did") : null;
            case "Result":
                if ("—".equals(value))
                    return msg("gp-manager-cannot") + msg("unknown-received")
                            + (trade.knownCostOnly ? msg("proven-ge") : "");
                return value.endsWith("Partial") ? msg("result-covered") : null;
            default:
                return null;
        }
    }

    /**
     * Gain / Cost / Transfer / Ignore, then Split and Undo, for one receipt: the same list in its
     * Correct ▾ menu and its row menu. A correction opens its Net preview on that receipt first.
     */
    Map<String, Runnable> corrections(String id, String contributionId, boolean canSplit) {
        actions.pinMenuTarget();
        var list = new LinkedHashMap<String, Runnable>();
        for (var decision : new Correction[] {
                    Correction.REVENUE, Correction.COST, Correction.TRANSFER, Correction.IGNORE
                })
            list.put(correctionLabel(decision), () -> {
                        pendingCorrection = decision;
                        transaction = id;
                        preview = actions.preview(id, decision);
                        actions.selectionChanged(id, contributionId, null);
                    });
        if (canSplit)
            list.put("Split…", () -> actions.split(id, (split, preview) -> {
                                        clearPending();
                                        transaction = id;
                                        share = split;
                                        this.preview = preview;
                                        actions.selectionChanged(id, contributionId, null);
                                    }));
        list.put(msg("undo-reason"), actions::undoCorrection);
        return list;
    }

    void clearPending() {
        pendingCorrection = null;
        transaction = null;
        preview = null;
        share = null;
    }

    void confirm() {
        if (share != null && preview != null) {
            actions.applySplit(transaction, share, preview.revision);
            clearPending();
            actions.refresh();
            return;
        }
        if (pendingCorrection == null || preview == null || transaction == null)
            return;
        var result = actions.correct(transaction, pendingCorrection, preview.revision);
        clearPending();
        actions.refresh();
        if (result != null && result != CorrectionOutcome.APPLIED)
            showNotice(result == CorrectionOutcome.STALE ? msg("entries-changed") : msg("correction-applied"));
    }

    static String correctionLabel(Correction decision) {
        return msg("correct-to." + decision, "Automatic");
    }

    static String reviewLabel(Decision decision) {
        return msg("review." + decision, "Exclude");
    }

    void showNotice(String text) {
        if (!empty(text)) {
            body.add(note(text, REVIEW.color));
            body.revalidate();
        }
    }

    // ── small builders ───────────────────────────────────────────────────────────────────────
    JComponent back(String label, Runnable action) {
        JButton back = button("‹ " + label, false, () -> {
                            action.run();
                            if (data != null) apply(data);
                        });
        back.getAccessibleContext().setAccessibleName("Back to " + label);
        return row(back);
    }

    /** The detail title card: name and value on one line, a dim line under it. */
    static JComponent title(String name, String value, Tone tone, String sub) {
        var card = new JPanel(new BorderLayout(GAP, 2));
        card.setBackground(CARD);
        pad(card, 5, GAP, 5, GAP);
        card.add(label(name, bold(), Color.WHITE), CENTER);
        card.add(label(value, numbers(12.5f), tone.color), EAST);
        card.add(label(sub, small(), LABEL), SOUTH);
        return card;
    }

    ImageIcon icon(int id) {
        return Kit.icon(icons, sprites, id);
    }

    boolean liveCategory() {
        return data != null && data.entry.table != null;
    }

    void scopeTitle() {
        if (data != null) scope.setText(fit(scope, data.scopeName,
                        scope.getWidth() > 0 ? max(1, scope.getWidth() - 30) : 80) + " ⌄");
    }

    void toggleSearch() {
        if (liveCategory()) return;
        searching = !searching;
        if (!searching) setSearchText("");
        actions.searchChanged(search.getText());
    }

    void searched() {
        if (!quietSearch) actions.searchChanged(search.getText());
    }

    void setSearchText(String text) {
        quietSearch = true;
        search.setText(text);
        quietSearch = false;
    }
}

/** Ledger actions: scope menu, search, selection, corrections, splits and review decisions. */
class LedgerController implements LedgerPage.Actions {
    SidebarPanel panel;
    Engine engine;
    boolean live;

    LedgerController(SidebarPanel panel, boolean live) {
        this(panel);
        this.live = live;
    }

    Entry entry() {
        return live ? panel.category : panel.scope;
    }

    void entry(Entry next) {
        if (live) panel.category = next;
        else panel.scope = next;
    }

    public void recent() {
        panel.categoryPage.resetOwner();
        panel.category = null;
        panel.live.category(null);
        refresh();
    }

    LedgerController(SidebarPanel panel) {
        this.panel = panel;
        engine = panel.engine;
    }

    void mutate(Runnable domain) {
        panel.mutate(domain);
    }

    public void openScopeMenu(JComponent anchor) {
        panel.page = 0;
        showScopeMenu(anchor, panel.page);
    }

    void showScopeMenu(JComponent anchor, int page) {
        var menu = new JPopupMenu();
        menu.add(item("Current Grind", () -> selectScope(CURRENT_GRIND, null, null)));
        menu.add(item("Runs today", () -> selectScope(TODAY, null, null)));
        var history = new ArrayList<>(engine.history());
        history.removeIf(Objects::isNull);
        history.sort(Comparator.comparingLong((Session itemData) -> itemData.startedAt).reversed()
                        .thenComparing(Session::id));
        if (!history.isEmpty()) {
            menu.addSeparator();
            int size = 10, pages = pageCount(history.size(), size);
            panel.page = clampPage(page, pages);
            int from = panel.page * size, to = min(history.size(), from + size);
            var status = new JMenuItem("History " + (from + 1) + "–" + to + " of " + history.size());
            status.setEnabled(false);
            menu.add(status);
            if (pages > 1) {
                menu.add(item("Previous page", panel.page > 0, () -> invokeLater(() ->
                                                        showScopeMenu(anchor, panel.page - 1))));
                menu.add(item("Next page", panel.page + 1 < pages, () -> invokeLater(
                                                () -> showScopeMenu(anchor, panel.page + 1))));
                menu.addSeparator();
            }
            for (var session : history.subList(from, to))
                menu.add(item(msg("recent-grind") + session.name(),
                                () -> selectScope(HISTORY, session.id(), session.name())));
        }
        menu.show(anchor, 0, anchor.getHeight() + 2);
    }

    void selectScope(Scope scope, String id, String name) {
        entry(entry().withScope(scope, id, name));
        refresh();
    }

    public void costViewChanged(CostView view) {
        entry(entry().withCostView(view));
        refresh();
    }

    public void searchChanged(String text) {
        if (live) return;
        panel.ledgerSearch = string(text);
        entry(entry().withSearch(string(text)));
        refresh();
    }

    @Override
    public void selectionChanged(String identifier, String id, String groupId) {
        if (live && entry() == null) return;
        String transaction = empty(identifier) ? null : identifier, entry = empty(id) ? null : id;
        String group = empty(groupId) ? null : groupId;
        entry(entry().withSelection(transaction, entry, group));
        refresh();
    }

    public CorrectionPreview preview(String id, Correction decision) {
        return LedgerData.preview(engine, id, decision, now());
    }

    /**
     * Checked against the previewed revision and applied in one engine hold on the mutation owner,
     * which also saves at once; a refusal reports back while its owner is still shown.
     */
    public CorrectionOutcome correct(String id, Correction decision, long previewRevision) {
        if (live && entry() == null) return CorrectionOutcome.REFUSED;
        Object owner = panel.ownerToken;
        mutate(() -> {
                    boolean stale = engine.revision() != previewRevision;
                    // A toast, not a page note: the refresh after this edit rebuilds the page.
                    if (stale || !engine.correctTransaction(id, decision, "Ledger correction"))
                        later(owner, () -> panel.shell.tell("Not applied",
                                        stale ? msg("entries-changed") : msg("correction-applied"), true));
                });
        return null;
    }

    /** Feedback from an edit run on the mutation owner, shown only while that owner is current. */
    void later(Object owner, Runnable feedback) {
        Runnable shown = () -> {
                    if (owner == panel.ownerToken) feedback.run();
                };
        if (isEventDispatchThread()) shown.run();
        else invokeLater(shown);
    }

    public void split(String identifier,
            BiConsumer<long[], CorrectionPreview> previewed) {
        Object token = panel.ownerToken;
        int id = -1;
        long total = 0;
        String name = "", owner = null;
        // The prompt's facts come from one engine hold; the EDT keeps no live receipt.
        synchronized (engine) {
            var session = engine.session();
            var transaction = session == null ? null : session.findTransaction(identifier);
            if (transaction != null) {
                owner = session.id();
                for (Flow flow : transaction.flows()) {
                    if (flow.quantity <= 0) continue;
                    if (id == -1) {
                        id = flow.item;
                        name = flow.name;
                    } else if (id != flow.item) {
                        id = -1;
                        break;
                    }
                    total += flow.quantity;
                }
            }
        }
        if (id <= 0 || total < 2) {
            // Owner 2026-10-01 (F02): only one kind of item in two or more units can be split.
            panel.shell.tell("Not split", owner == null ? msg("entry-longer") : msg("split-needs"), true);
            return;
        }
        int splitItem = id;
        long splitTotal = total;
        String splitName = empty(name) ? "this item" : name, splitOwner = owner;
        panel.shell.prompt("Split entry", "Keep how many of " + splitName + " ×" + total + " for your share?",
                        "", value -> {
                            long keep;
                            try {
                                keep = Long.parseLong(value.replace(",", ""));
                            } catch (NumberFormatException ex) {
                                keep = -1;
                            }
                            if (keep <= 0 || keep >= splitTotal) {
                                panel.shell.tell("Not split", msg("enter-number") + (splitTotal - 1), true);
                                return;
                            }
                            CorrectionPreview preview = null;
                            // Owner, session, item and quantity are re-checked in the preview's hold.
                            synchronized (engine) {
                                var now = engine.session();
                                if (token == panel.ownerToken && now != null && splitOwner.equals(now.id())
                                        && splittableSplit(now.findTransaction(identifier), splitItem, splitTotal))
                                    preview = LedgerData.splitPreview(engine, identifier, splitItem, keep, now());
                            }
                            if (preview == null) {
                                panel.shell.tell("Not split", msg("entry-changed"), true);
                                return;
                            }
                            // Owner 1.1: the split shows its Net preview first, like every other
                            // correction.
                            previewed.accept(new long[] {splitItem, keep, splitTotal}, preview);
                        });
    }

    public void applySplit(String id, long[] split, long previewRevision) {
        var active = engine.session();
        String owner = active == null ? "" : active.id();
        Object token = panel.ownerToken;
        mutate(() -> {
                    // The sheet may be stale: re-check the session and the exact receipt before
                    // mutating.
                    var now = engine.session();
                    Transaction current = now == null || !owner.equals(now.id()) ? null
                                    : now.findTransaction(id);
                    String why = engine.revision() != previewRevision ? msg("entries-changed")
                                    : !splittableSplit(current, (int) split[0], split[2]) ? msg("entry-changed")
                                    : engine.splitItem(id, (int) split[0], split[1], "Ledger split") ? null
                                    : msg("split-applied");
                    if (why != null) later(token, () -> panel.shell.tell("Not split", why, true));
                });
    }

    /** One item kind only, and the quantity captured at the prompt is still exact. */
    static boolean splittableSplit(Transaction transaction, int id, long total) {
        if (transaction == null) return false;
        int found = -1;
        long sum = 0;
        for (Flow flow : transaction.flows()) {
            if (flow.quantity <= 0) continue;
            if (found == -1) found = flow.item;
            else if (found != flow.item) return false;
            sum += flow.quantity;
        }
        return found == id && sum == total;
    }

    String menuTarget = "";

    /** Pins an open mutation menu; the callback re-checks it against the live session. */
    @Override
    public String pinMenuTarget() {
        var active = engine.session();
        menuTarget = active == null ? "" : active.id();
        return menuTarget;
    }

    /**
     * Owner 2026-10-01 (F01): a stale menu never mutates; scope and session are re-checked here.
     */
    boolean undoAllowed() {
        var active = engine.session();
        return entry() != null && !LedgerData.readOnly(entry()) && active != null && !menuTarget.isEmpty()
                && menuTarget.equals(active.id());
    }

    public void undoCorrection() {
        undo(() -> engine.undoCorrection());
    }

    /** Pin the dispatch too: a queued callback cannot follow a new session or menu. */
    void undo(Runnable action) {
        if (!undoAllowed()) return;
        String target = menuTarget;
        mutate(() -> { if (target.equals(menuTarget) && undoAllowed()) action.run(); });
    }

    @Override
    public void undoLast() {
        undo(engine::undoTransaction);
    }

    @Override
    public void restoreUndo() {
        undo(engine::restoreUndo);
    }

    @Override
    /** Previews one Review decision, confirms it with its Net change, then applies it undoably. */
    public void decide(String id, Decision decision) {
        Engine.DecisionPreview preview = engine.previewDecision(decision,
                        row -> row != null && id.equals(row.transaction), now());
        if (preview.rowCount() != 1) return;
        String message = correctionLabel(decision.correction) + " for this entry. Net changes by "
                        + signed(preview.netDelta) + " gp.";
        Object owner = panel.ownerToken;
        panel.shell.confirm("Review decision", message, "Apply", () -> mutate(() -> {
                                            if (engine.applyDecision(preview, now()) < 0)
                                                later(owner, () -> panel.shell.tell("Not applied",
                                                                        msg("entries-changed"), true));
                                        }));
    }

    public void refresh() {
        panel.refresh();
    }

    static String correctionLabel(Correction decision) {
        return LedgerPage.correctionLabel(decision);
    }
}

/**
 * Live = what is happening now (SIDEBAR_SPEC.md §3): the hero band, the target card with pace, and
 * the plain Recent list and optional category view. Components are retained between ticks so hover
 * and menus survive. The page renders a {@link LiveSnapshot} and holds no engine.
 */
class LivePage implements Shell.Page {
    interface Actions {
        void togglePause();

        void openLedger(Entry entry);

        default void openCategory(Semantics.Table table) {}

        void editTarget();

        /** Owner live 2026-10-13: the completed row restarts the aim or clears it. */
        default void againTarget() {}

        default void doneTarget() {}

        /** Owner live 2026-10-14: the timer's own pause, distinct from the session pause. */
        default void toggleTimerPause() {}

        /** The ACTIVE clock: choose a rate-only restart or a new session; keep the whole run. */
        default void newSession() {}

        /** The run total under the Net: Live shows the whole run again (owner 1.5.2). */
        default void showWholeRun() {}

        default void hideRecent(String name, boolean supply) {}

        /** Clicking the running Grind's name renames it. */
        default void renameGrind() {}

        /** True when gameplay alone starts tracking; otherwise use the Grinds tab. */
        default boolean autoStartArmed() {
            return true;
        }
    }

    Actions actions;
    Function<Integer, BufferedImage> sprites;
    Map<Integer, ImageIcon> icons = new HashMap<>();

    /** The hero Net pair keeps its spaces up to this many characters (owner live 2026-10-14). */
    static final int HERO_PAIR_SPACED_MAX = 12;
    JPanel body = stack(PANEL, GAP);
    Hero hero = new Hero();
    JPanel target = new JPanel(new BorderLayout(0, 3));
    JLabel heading = label("", small(), LABEL), value = label("", numbers(12f), TEXT);
    JLabel pace = label("", small(), LABEL), targetTime = label("", small(), LABEL);

    /** Under the Net while Live counts fresh: the run's whole total, "Free play +420M". */
    JLabel total = label("", small(), LABEL);

    JLabel risk = label("", small(), LOSS.color);
    JPanel targetActions = transparent(new FlowLayout(FlowLayout.LEFT, 3, 0));
    /** The header's right cluster: the aim icons, single row (owner live 2026-10-14). */
    JPanel top = transparent(new FlowLayout(FlowLayout.RIGHT, 3, 0));
    /** The last laid-out button shape; the rows rebuild only when it changes. */
    Object actionsShape;
    JButton againButton, doneButton, editButton, pause;
    ThinProgressBar targetBar = new ThinProgressBar();

    /**
     * GE offers still on the exchange; shown only while there are some, then they move to Recent.
     */
    Table offers = new Table("OFFERS").setFoldable(true);

    Table recent = new Table("RECENT").plain();
    JPanel breakdown = stack(PANEL, GAP);

    /** Pending counts as small icons: loot keys, GE offers, grave reclaim. */
    JPanel counts = transparent(new FlowLayout(FlowLayout.RIGHT, 4, 0));
    JPanel signals = transparent(new FlowLayout(FlowLayout.RIGHT, 4, 0));

    ImageIcon geIcon = icon(() -> ImageUtil.loadImageResource(GrandExchangePlugin.class, "ge_icon.png"));
    ImageIcon graveIcon = new ImageIcon(grave(LOSS.color));
    String drawnCounts = "";

    /** The RECENT empty sentence currently set; only a change forces a table rebuild. */
    String emptyRecent = "Nothing booked yet";

    boolean alwaysPvp;

    LivePage(Actions actions, Function<Integer, BufferedImage> sprites) {
        this.actions = actions;
        this.sprites = sprites == null ? id -> null : sprites;
        signals.add(hero.icons);
        signals.add(counts);
        clickable(hero.clock, "Clock controls", actions::newSession);
        clickable(hero.name, "Rename Grind", actions::renameGrind);
        // Clicking the Net total sets or changes the target (owner 2026-09-28: no Target button).
        clickable(hero.net, "Set a Target", actions::editTarget);
        clickable(total, "Save session or show run", actions::showWholeRun);
        risk.setToolTipText(msg("what-death"));
        hero.under.add(total);
        hero.under.add(risk);
        target.setBackground(CARD);
        pad(target, 4, 6, 5, 6);
        // Owner live 2026-10-14: Pause and Done ride the header's right; Again / Reset / Edit sit
        // under the card. Built once; only visibility follows the aim.
        // Icon buttons keep the card one row tall; the tooltips carry the words (owner live
        // 2026-10-14). Their homes are rebuilt per state (see applyTarget): the bottom row
        // carries the completed action set; while running the icons ride the header's right.
        againButton = button("↻", false, actions::againTarget);
        doneButton = button("✓", false, actions::doneTarget);
        editButton = button("…", false, actions::editTarget);
        pause = button("||", false, actions::toggleTimerPause);
        againButton.setToolTipText("Again: a new session with the same target");
        doneButton.setToolTipText("Done: clear the goal");
        editButton.setToolTipText("Edit targets");
        pause.setToolTipText("Pause or resume the timer");
        var head = transparent(new BorderLayout());
        head.add(heading, CENTER);
        head.add(top, EAST);
        target.add(head, NORTH);
        var values = stack(CARD, 1);
        values.add(value);
        values.add(targetTime);
        var line = transparent(new BorderLayout(0, 2));
        line.add(values, NORTH);
        targetBar.setMaximumValue(1000);
        targetBar.setBackground(LINE);
        line.add(targetBar, CENTER);
        line.add(pace, SOUTH);
        target.add(line, CENTER);
        target.add(targetActions, SOUTH);
        // A set target is edited by clicking its card.
        target.setToolTipText(msg("click-change"));
        clickable(target, "Edit target", actions::editTarget);
        recent.setEmpty("Nothing booked yet");
        breakdown.setVisible(false);
        pad(body, 0, GAP, GAP, 0);
        for (var part : new JComponent[] {hero, signals, target, offers, recent, breakdown}) body.add(part);
    }

    public String id() {
        return LIVE;
    }

    public JComponent pageBar() {
        return null;
    }

    public JComponent body() {
        return body;
    }

    void category(LedgerPage page) {
        recent.setVisible(page == null);
        breakdown.setVisible(page != null);
        if (page != null && breakdown.getComponentCount() == 0) {
            page.body.setBorder(null);
            breakdown.add(page.body);
        }
    }

    /** A new account starts each Live table on its first page. */
    void clearOwner() {
        recent.resetPage();
        offers.resetPage();
        category(null);
    }

    void apply(LiveSnapshot snapshot) {
        boolean loggedIn = !snapshot.offline;
        counts(snapshot);
        hero.icons(statusIcons(snapshot));
        signals.setVisible(hero.icons.getComponentCount() + counts.getComponentCount() > 0);
        hero.status(dotColor(snapshot), statusTooltip(snapshot), identityOf(snapshot), wordOf(snapshot),
                loggedIn ? actions::togglePause : null);
        // Owner 1.5.2: Risk lives off the header, so a long name keeps its room.
        total.setVisible(snapshot.fresh);
        // Clicking it shows the whole run again; a running target keeps its own session.
        total.setToolTipText((snapshot.goal == null ? msg("run-total") : msg("run-total-target"))
                        + (snapshot.milestone == null ? "" : " Milestone " + compact(snapshot.milestone) + ": "
                                        + percent(Math.max(0d, Math.min(1d, (double) snapshot.run / snapshot.milestone)))
                                        + "."));
        total.setText((snapshot.general ? "Free play " : "Grind ") + signed(snapshot.run));
        risk.setVisible(snapshot.possible && snapshot.risk > 0);
        risk.setText("RISK " + compact(snapshot.risk));
        hero.under.setVisible(total.isVisible() || risk.isVisible());
        // Nothing booked yet reads as a dash, never the font's slashed zero.
        boolean booked = snapshot.fresh || snapshot.income != 0 || snapshot.cost != 0 || snapshot.market != 0
                        || !snapshot.recent.isEmpty();
        if (snapshot.present) {
            // Owner live 2026-10-13: with a Net target the hero carries the live pair
            // ("+38.0k / 200k"), so the target card never repeats the current Net; the pair counts
            // what the aim has earned since it was set (owner live 2026-10-14) and falls back to
            // the tight form when the spaced one cannot fit the hero line.
            String heroNet = signed(snapshot.net);
            if (booked && snapshot.goal != null && !snapshot.goal.time) {
                // Owner live 2026-10-14: the pair is spaced while it fits the hero line; long
                // values fall back to the tight form so it never clips.
                String spaced = signed(snapshot.goal.current) + " / " + snapshot.goal.label;
                heroNet = spaced.length() <= HERO_PAIR_SPACED_MAX ? spaced
                                : signed(snapshot.goal.current) + "/" + snapshot.goal.label;
            }
            hero.big(booked ? heroNet : "—",
                    // The pair's colour follows the number it shows (owner live 2026-10-14).
                    booked ? snapshot.goal != null && !snapshot.goal.time ? sign(snapshot.goal.current)
                                    : sign(snapshot.net) : DIM, exactSigned(snapshot.net) + " gp · gains "
                            + exact(snapshot.income) + (snapshot.split ? " · supplies "
                                            + exact(snapshot.supplies) + " · losses " + exact(snapshot.loss)
                                    : " · costs " + exact(snapshot.cost) + msg("split-unavailable"))
                            + (snapshot.goal == null ? msg("click-set") : snapshot.goal.time ? "" : " · aim "
                                                    + exactSigned(snapshot.goal.current) + " of "
                                                    + exact(snapshot.goal.target) + " gp"),
                    rateText(snapshot.ready, snapshot.rate), snapshot.ready,
                    clock(snapshot.elapsed) + " ACTIVE");
        } else
            hero.big("—", DIM, msg("nothing-tracked"), "—", false, "");
        // Owner 2026-10-01 (F12): the hero names the rate basis and the tracked-time policy.
        hero.rate.setToolTipText(snapshot.ready ? msg("gp-h") : null);
        hero.clock.setToolTipText(snapshot.present ? msg("tracked-active") : null);
        hero.strips(strips(snapshot));
        applyTarget(snapshot);
        var rows = new ArrayList<Row>();
        var open = new ArrayList<Row>();
        long openTotal = 0, recentTotal = 0;
        for (var row : snapshot.recent)
            if (row.open) {
                open.add(rowOf(row));
                openTotal += row.value;
            } else {
                rows.add(rowOf(row));
                recentTotal += row.net();
            }
        offers.setTitle("OFFERS · " + open.size()).setRows(open).setTotal(signed(openTotal), null);
        offers.setVisible(!open.isEmpty());
        // Owner 2026-10-01 (F13): the empty card says which event starts tracking, never a fake
        // start.
        String empty = snapshot.offline ? msg("tracking-waits") : !snapshot.general ? "Nothing booked yet"
                                : actions.autoStartArmed() ? msg("tracking-starts") : msg("press-grind");
        if (!empty.equals(emptyRecent)) {
            emptyRecent = empty;
            recent.setEmpty(empty);
        }
        recent.rowTitle("RECENT", rows);
        recent.setTotal(rows.isEmpty() ? "" : signed(recentTotal), sign(recentTotal));
    }

    void applyTarget(LiveSnapshot snapshot) {
        var goal = snapshot.goal;
        hero.name.setFocusable(!snapshot.general && snapshot.present);
        hero.name.setToolTipText(snapshot.general ? null : "Click to rename"
                                + (identityOf(snapshot).equals(hero.name.getText()) ? ""
                                        : " · " + identityOf(snapshot)));
        target.setVisible(goal != null);
        if (goal == null) return;
        String title;
        if (goal.time) {
            // Owner 2026-10-01 (F10): a time target reads tracked Active Time, not fabricated Net.
            title = goal.reached ? "TARGET · reached" : "TARGET";
            value.setText(goal.timeDetail);
            value.setToolTipText(duration(goal.target) + " of active play");
            value.setVisible(true);
            targetTime.setVisible(false);
        } else {
            title = "TARGET · " + goal.label + " · " + goal.progress;
            // The hero owns the live Net pair; the card never repeats it (owner live 2026-10-13).
            value.setText("");
            value.setToolTipText(null);
            value.setVisible(false);
            boolean hasTime = goal.timeDetail != null;
            targetTime.setText(hasTime ? goal.timeDetail : "");
            targetTime.setVisible(hasTime);
        }
        targetBar.setValue((int) round(goal.fraction * 1000));
        targetBar.setForeground(goal.reached ? GAIN.color : ACCENT);
        // A time target's line already counts down (owner 1.5.2: said once); a Net aim's pace line
        // carries its own wording, or waits for the rate.
        String pace = goal.eta;
        this.pace.setText(pace == null ? msg("pace-once") : pace);
        // Button layout (owner live 2026-10-14): while the aim runs its icons sit on the header's
        // right with no bottom row; a completed aim lays them along the bottom. Pause is the timer's own —
        // the run keeps tracking.
        boolean completed = goal.reached || goal.timeDone, timerPaused = goal.timerPaused;
        boolean timerSet = goal.timeDetail != null;
        // Pause needs a timer that can still run; an ended one has nothing to stop. Done and Again
        // are always there: Again is a new session with the same target (owner 1.5.2).
        pause.setVisible(timerSet && !goal.timeDone);
        pause.setText(timerPaused ? "▶" : "||");
        var shown = new ArrayList<JButton>();
        for (var action : completed ? new JButton[] {againButton, doneButton, editButton, pause}
                        : new JButton[] {pause, againButton, doneButton, editButton}) {
            if (action.isVisible()) shown.add(action);
        }
        List<Object> shape = List.of(completed, shown);
        if (!shape.equals(actionsShape)) {
            actionsShape = shape;
            targetActions.removeAll();
            top.removeAll();
            for (var action : shown) (completed ? targetActions : top).add(action);
            // No bottom row while the run goes on: the header cluster carries it.
            targetActions.setVisible(completed);
            top.setVisible(!completed && !shown.isEmpty());
            target.revalidate();
        }
        this.pace.setVisible(goal.eta != null || !goal.reached && goal.timeDetail == null);
        // The title shrinks before it is cut beside the icons; hover keeps it whole (owner 1.5.2).
        int room = target.getWidth() - 14
                        - (top.isVisible() ? top.getPreferredSize().width : 0);
        String fitted = fitShrinking(heading, title, small(), room);
        // Still too long: the card says it is a target, so the label and progress go first.
        if (!fitted.equals(title))
            fitted = fitShrinking(heading, title.replace("TARGET · ", ""), small(), room);
        heading.setText(fitted);
        heading.setToolTipText(title);
    }

    Row rowOf(Recent row) {
        var icon = Kit.icon(icons, sprites, row.item);
        Entry entry = entryFor(row);
        var menu = new ArrayList<Table.Menu>();
        menu.add(new Table.Menu("Open in Ledger", () -> actions.openLedger(entry)));
        // A finished trade is one item too (owner 2026-10-08: a sold Fire rune had no Hide).
        if (!row.open && row.item > 0 && (!row.composite || row.market))
            menu.add(new Table.Menu("Hide item from recent", () -> actions.hideRecent(row.name, false)));
        if (!row.open && row.composite && !row.market && (row.item < -1 || "Cast".equals(row.label)))
            menu.add(new Table.Menu("Hide spell from recent", () -> actions.hideRecent(row.name, false)));
        // Hiding a supply's spends leaves acquisitions visible; the item hide applies to both.
        if (!row.open && row.item > 0 && row.supplyRow)
            menu.add(new Table.Menu("Hide supply from recent", () -> actions.hideRecent(row.name, true)));
        // Ore a failed smelt lost sits beside the ore the bars used: say which is which.
        String name = msg("failed").equals(row.label) ? row.name + " (failed)" : row.name;
        Row drawn = new Row(row.id + "|" + row.receiptId, icon, name, row.qty, valueText(row), toneOf(row),
                tipOf(row), numeric(row) ? exactSigned(row.value) + " gp" : "", false, () -> actions.openLedger(entry),
                menu);
        drawn.color = row.highlight;
        return drawn;
    }

    List<List<Cell>> strips(LiveSnapshot snapshot) {
        if (!snapshot.present)
            return singletonList(Arrays.asList(new Cell("GAINS", "—", DIM, "", null),
                            new Cell("SUPPLY", "—", DIM, "", null), new Cell("LOSS", "—", DIM, "", null)));
        Runnable ledger = () -> actions.openLedger(Entry.current());
        var money = money(snapshot.income, snapshot.supplies, snapshot.loss, snapshot.cost,
                        snapshot.split, ledger);
        if (snapshot.market != 0 || snapshot.pendingMarket > 0)
            money.add(new Cell("TRADE", snapshot.market == 0 ? "open" : signed(snapshot.market), MARKET,
                            msg("realized-market"), ledger));
        for (Cell cell : money)
            for (var table : Semantics.Table.values())
                if (table.label.equals(cell.label)) cell.open = () -> actions.openCategory(table);
        var rows = new ArrayList<List<Cell>>(singletonList(money));
        // Auto follows the dangerous zone; Always exposes recorded stats anywhere. Risk keeps
        // its independent actual-zone predicate. Skull and Protect Item remain hidden.
        if (snapshot.possible || alwaysPvp) {
            var pvp = new ArrayList<Cell>();
            pvp.add(new Cell("KILLS", valueOf(snapshot.kills), GAIN, msg("kt"),
                            () -> actions.openCategory(KILL)));
            pvp.add(new Cell("DEATHS", valueOf(snapshot.deaths), LOSS,
                            "PvP deaths this Grind. " + msg("death-view"), () -> actions.openCategory(DEATH)));
            pvp.add(new Cell("BEST", snapshot.bestKill == 0 ? "—" : signed(snapshot.bestKill), GAIN,
                                        "Best kill · " + snapshot.kills + " kills · " + snapshot.deaths + " deaths", null));
            pvp.add(new Cell("STREAK", valueOf(snapshot.streak), snapshot.streak > 0 ? GAIN : DIM,
                                    msg("kills-since") + snapshot.bestStreak + " this Grind", null));
            rows.add(pvp);
        }
        return rows;
    }

    List<Cell> statusIcons(LiveSnapshot snapshot) {
        var list = new ArrayList<Cell>();
        if (snapshot.reviews > 0)
            list.add(new Cell("Review", "(!)" + snapshot.reviews, REVIEW, snapshot.reviews == 1 ? msg("item-needs")
                                    : snapshot.reviews + msg("items-need"),
                            () -> actions.openLedger(Entry.current(Audit.REVIEW, null))));
        return list;
    }

    /** Rebuilds the count icons only when a count changes, so hover survives a tick. */
    void counts(LiveSnapshot snapshot) {
        String key = snapshot.keys + "|" + snapshot.keyValue + "|" + snapshot.pendingMarket + "|"
                        + snapshot.returns;
        if (key.equals(drawnCounts)) return;
        drawnCounts = key;
        var previous = focused(counts);
        counts.removeAll();
        Runnable pending = () -> actions.openLedger(Entry.current());
        var keyImage = sprites.apply(ItemID.WILDY_LOOT_KEY0);
        String keyTip = msg("loot-keys") + snapshot.keys + (snapshot.keyValue > 0
                                ? msg("loot-keys-worth") + compact(snapshot.keyValue) : "");
        count(snapshot.keys, keyImage == null ? null : new ImageIcon(keyImage), "key", DIM, keyTip, pending);
        count(snapshot.pendingMarket, geIcon, "GE", MARKET, msg("ge-offers") + snapshot.pendingMarket,
                pending);
        count(snapshot.returns, graveIcon, "grave", LOSS,
                msg("grave-expired") + snapshot.returns, pending);
        counts.setVisible(counts.getComponentCount() > 0);
        counts.revalidate();
        restoreFocus(counts, previous);
        counts.repaint();
    }

    void count(long n, ImageIcon image, String fallback, Tone tone, String tip, Runnable open) {
        if (n <= 0) return;
        // A borderless button, not a mouse listener: the safety audit bans mouse-event names.
        var label = new JButton(image == null ? fallback + " " + n : valueOf(n));
        label.setFont(small());
        label.setForeground(tone.color);
        label.setIcon(image == null || image.getIconHeight() <= 12 ? image : new ImageIcon(
                                image.getImage().getScaledInstance(-1, 12, Image.SCALE_SMOOTH)));
        label.setIconTextGap(2);
        label.setToolTipText(tip);
        label.setBorder(null);
        label.setBorderPainted(true);
        label.setContentAreaFilled(false);
        label.setFocusPainted(false);
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        focus(label);
        label.setName(fallback);
        label.getAccessibleContext().setAccessibleName("Open " + fallback + " details");
        label.addActionListener(e -> open.run());
        counts.add(label);
    }

    static ImageIcon icon(Supplier<BufferedImage> load) {
        try {
            var image = load.get();
            return image == null ? null : new ImageIcon(image);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** The design's 11px outline headstone with a cross; RuneLite ships no grave icon. */
    static BufferedImage grave(Color color) {
        var image = new BufferedImage(11, 11, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        g.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);
        g.setColor(color);
        g.setStroke(new BasicStroke(1.2f));
        var stone = new Path2D.Float();
        stone.moveTo(2.5f, 10f);
        stone.lineTo(2.5f, 4.5f);
        stone.append(new Arc2D.Float(2.5f, 1.5f, 6f, 6f, 180f, -180f, Arc2D.OPEN), true);
        stone.lineTo(8.5f, 10f);
        stone.closePath();
        g.draw(stone);
        g.draw(new Line2D.Float(5.5f, 4f, 5.5f, 7.5f));
        g.draw(new Line2D.Float(4f, 5.5f, 7f, 5.5f));
        g.dispose();
        return image;
    }

    static Color dotColor(LiveSnapshot snapshot) {
        if (snapshot.offline) return OFF;
        if (snapshot.paused && !snapshot.idle) return REVIEW.color;
        if (!snapshot.present || snapshot.idle) return OFF;
        return GAIN.color;
    }

    static Tone toneOf(Recent row) {
        if ("review".equals(row.tag)) return REVIEW;
        if (!numeric(row)) return DIM;
        if (row.market) return MARKET;
        if (row.value >= 0) return GAIN;
        return row.cost == CostView.LOSS ? LOSS : SUPPLY;
    }

    static boolean numeric(Recent row) {
        return !row.neutral && !row.unknown && !"canceled".equals(row.tag)
                && (!row.market || row.amount || row.known);
    }

    /** "1.93M/h" once the rate is established; never 0/h before that. */
    static String rateText(boolean established, long gpPerHour) {
        return established ? rate(gpPerHour) + "/h" : "Calculating…";
    }

    static String tipOf(Recent row) {
        var tip = new StringBuilder(row.name).append(" · ").append(metaOf(row));
        if ("review".equals(row.tag)) tip.append(" · needs review");
        if (row.unknown) tip.append(msg("value-incomplete"));
        if (row.market && !row.known && !row.amount)
            tip.append(msg("market-result"));
        if (row.market && row.amount)
            tip.append(row.known ? msg("primary-value") : msg("settlement-result"));
        if (row.entries > 1 && !metaOf(row).contains("entries"))
            tip.append(" · ").append(row.entries).append(" entries");
        return tip.toString();
    }

    /** The right-side value: settled cash is available independently of Result. */
    static String valueText(Recent row) {
        if (row.neutral) return "—";
        if (row.unknown) return "?";
        if ("canceled".equals(row.tag)) return "Canceled";
        if (row.market && !row.amount && !row.known)
            return "review".equals(row.tag) ? "?" : row.marketSide.isEmpty() ? "Pending" : row.marketSide;
        return row.market ? signed(row.value) : worth(row.value);
    }

    /** What the row is: its state for review / unpriced rows, else action and quantity. */
    static String metaOf(Recent row) {
        if ("review".equals(row.tag)) return "Needs review";
        if ("unpriced".equals(row.tag)) return "Unpriced";
        String qty = row.qty == null ? "" : " · " + row.qty;
        if (!row.market && (row.label.isEmpty() || row.composite && row.name.equalsIgnoreCase(row.label)))
            return string(row.qty);
        return row.label + qty;
    }

    /** Stable receipt identity drives Live navigation; search text stays a user-owned filter. */
    static Entry entryFor(Recent row) {
        return new Entry(CURRENT_GRIND, null, null,
                // The Ledger always opens its Losses view on All (owner live 2026-10-13); the
                // row's own cost view still colours the tray and its menus.
                CostView.ALL, "", row.receiptId, row.id, null,
                row.ledgerReview ? Audit.REVIEW : null);
    }

    /** The small status word, only when something is off: tracking itself is the green dot. */
    static String wordOf(LiveSnapshot snapshot) {
        if (snapshot.hopping) return "RECONNECTING";
        if (snapshot.offline) return "LOGGED OUT";
        if (!snapshot.present && snapshot.resumeSeconds > 0)
            return "RESUMING " + snapshot.resumeSeconds + "s";
        if (!snapshot.present) return "";
        if (snapshot.paused && !snapshot.idle) return "PAUSED";
        if (snapshot.idle) return "AWAY";
        return snapshot.warming ? "CALIBRATING" : "";
    }

    /**
     * The status line names the run: a named Grind shows its name; free play shows the detected
     * activity label when there is one ("Trade"), blank otherwise (owner live 2026-10-14; the
     * 2026-10-13 retirement kept activity noise out of named runs).
     */
    static String identityOf(LiveSnapshot snapshot) {
        return activity(snapshot.general ? snapshot.activityLabel : snapshot.ownerLabel);
    }

    static String statusTooltip(LiveSnapshot snapshot) {
        String wording = snapshot.hopping ? "Reconnecting" : snapshot.offline ? msg("logged-out")
                                : !snapshot.present ? msg("tracking-click") : snapshot.paused && !snapshot.idle
                                                ? msg("tracking-paused-click") : snapshot.idle ? msg("waiting-activity")
                                                        : msg("live-tracking");
        var tip = new StringBuilder(wording);
        if (snapshot.present) tip.append(" · active ").append(duration(snapshot.elapsed));
        return tip.toString();
    }
}

/**
 * The GP Manager sidebar as a RuneLite PluginPanel: owns the GP Surface shell and the Live page,
 * reads engine state only inside {@link #refresh()} on the EDT, and asks the plugin for any domain
 * mutation through its client-thread owner.
 */
class SidebarPanel extends PluginPanel {
    Engine engine;
    GpManagerConfig config;
    ItemManager itemManager;
    volatile SpriteManager spriteManager;

    /** Domain-mutation owner (charter V); the plugin binds the client thread, tests run direct. */
    volatile Consumer<Runnable> mutationOwner = Runnable::run;

    Shell shell = new Shell();
    LivePage live;
    LedgerPage ledger, categoryPage;
    Entry category;
    GrindsPage plans;
    RaidsPage raids;
    GrindsController actions;
    SessionRepository repository;
    Persistence storage;
    String selection;
    boolean archived;
    int page;

    /** The Ledger's typed context; presentation state only. */
    Entry scope = Entry.current();

    String ledgerSearch = "";
    ConcurrentHashMap<Integer, BufferedImage> sprites = new ConcurrentHashMap<>();
    volatile Supplier<PvpState> pvpSource = () -> PvpState.NONE;

    /** HUD+ reads the same per-tick Live snapshot; null in previews and tests. */
    volatile HudBuilder hud;

    volatile BooleanSupplier loggedInSource = () -> true;

    /** True while a world hop is in flight; presentation only. */
    volatile BooleanSupplier hoppingSource = () -> false;

    /** Preview/test seam: deterministic item sprites; null keeps the real ItemManager lookup. */
    volatile Function<Integer, BufferedImage> spriteOverride;

    /** Presentation-only loot visibility; null keeps every booked row visible. */
    volatile RecentFilter filter;

    volatile boolean active;
    boolean scheduled;

    /** A real change asked for the visible page to be re-read on the next drain. */
    boolean pageForced = true;

    /**
     * The revision and minute each page was last filled at. Switching back to a page whose data has
     * not changed shows its existing rows instead of re-reading and rebuilding them.
     */
    Map<String, String> pageKeys = new HashMap<>();

    volatile boolean suspended;
    volatile Object ownerToken = new Object();
    final Object ownerLock = new Object();
    Object clearedOwner;
    LiveSnapshot snapshot;

    /**
     * The plugin supplies the current export/backup/recovery services; tests and previews may not.
     */
    SidebarPanel(Engine engine, GpManagerConfig config, ItemManager items, SessionRepository repository,
            Persistence persistence) {
        super(false);
        this.engine = engine;
        this.config = config;
        itemManager = items;
        this.repository = repository;
        storage = persistence;
        actions = new GrindsController(this);
        setLayout(new BorderLayout());
        setBackground(PANEL);
        live = new LivePage(new LiveActions(), this::sprite);
        ledger = new LedgerPage(new LedgerController(this), this::sprite);
        plans = new GrindsPage(actions);
        shell.addPage(live);
        shell.addPage(ledger);
        shell.addPage(plans);
        raids = new RaidsPage(this);
        shell.addPage(raids);
        add(shell, CENTER);
        shell.show(LIVE);
        shell.onShow = this::schedule;
        shell.onGear = anchor -> actions.dataMenu().show(anchor, 0, anchor.getHeight() + 2);
        invokeLater(() -> active = true);
    }

    /** Client-thread PvP facts (dangerous context, skull, Protect Item); presentation only. */
    void bindPvp(Supplier<PvpState> source) {
        pvpSource = source == null ? () -> PvpState.NONE : source;
    }

    /** HUD+ builds from this panel's per-tick snapshot, even while the sidebar is closed. */
    void bindHud(HudBuilder builder) {
        hud = builder;
    }

    /**
     * A factory reset replaced the profile: the bound HUD+ trip starts clean, like a new profile.
     */
    void resetProfile() {
        var builder = hud;
        if (builder != null) {
            builder.resetPresentation();
            // Repaint on the next EDT pass instead of waiting up to a game tick.
            refresh();
        }
    }

    /** Whether the client is logged in; previews and tests stay logged in. */
    void bindLoggedIn(BooleanSupplier source) {
        loggedInSource = source == null ? () -> true : source;
    }

    /** Binds the transient hop signal; presentation only. */
    void bindHopping(BooleanSupplier source) {
        hoppingSource = source == null ? () -> false : source;
    }

    /**
     * The current loot visibility decision (the existing filter authority, bound by the plugin).
     * Presentation only: hidden rows stay booked, accounted and queryable; nothing here values.
     */
    void bindFilter(RecentFilter source) {
        filter = source;
        refresh();
    }

    /**
     * Coalesced refresh after a real change (an action, a booking, a setting): the visible page is
     * re-read. Safe from any thread and never blocks the caller.
     */
    void refresh() {
        if (isEventDispatchThread()) {
            pageForced = true;
            schedule();
            return;
        }
        invokeLater(this::refresh);
    }

    /**
     * The plugin's per-game-tick pulse. Live values update in place; another visible page is
     * re-read only when the engine revision or the minute changed, so its rows (and any menu open
     * on them) are not rebuilt every tick.
     */
    void tick() {
        lastTickAt = now();
        if (isEventDispatchThread()) {
            schedule();
            return;
        }
        invokeLater(this::tick);
    }

    /**
     * Game ticks stop while Windows resizes the client window, so a one-second fallback keeps the
     * Live clock and rate moving; it stays quiet while ticks arrive.
     */
    volatile long lastTickAt;

    Timer clockPulse = new Timer(1000, e -> {
                        if (now() - lastTickAt > 1200) schedule();
                    });

    public void addNotify() {
        super.addNotify();
        clockPulse.start();
    }

    public void removeNotify() {
        clockPulse.stop();
        super.removeNotify();
    }

    void schedule() {
        if (!scheduled) {
            scheduled = true;
            invokeLater(this::drain);
        }
    }

    void drain() {
        scheduled = false;
        refreshNow();
    }

    void refreshNow() {
        boolean showing = active || isShowing();
        if (!showing && hud == null) return;
        showPersistenceWarning();
        if (suspended) return;
        long now = now();
        var pvp = pvpSource.get();
        if (pvp == null) pvp = PvpState.NONE;
        String page = shell.currentPage();
        long minute = now / 60000;
        String key;
        LiveSnapshot snapshot;
        LedgerData ledgerData = null;
        GrindsData data = null;
        RaidHistory.State raidState = null;
        Map<String,long[]> raidMoney = null;
        boolean raidProgress;
        // One consistent read: the client thread mutates sessions under the engine's monitor, so
        // no list is iterated here while it changes. Nothing inside this block touches Swing.
        LiveContext context;
        synchronized (engine) {
            key = engine.revision() + ":" + minute;
            raidProgress = engine.raids.unlocked();
            var boundHud = hud;
            context = new LiveContext(engine.isIdlePaused(), filter, parseGp(config.minimumDisplayedLootValue()),
                            pvp, activityLabel(), !loggedInSource.getAsBoolean(),
                            boundHud == null ? "" : boundHud.target(), hoppingSource.getAsBoolean());
            snapshot = LiveSnapshot.capture(engine, now, context);
            if (pageForced)
                // A setting, search or scope change can alter a page without a new revision.
                pageKeys.clear();
            boolean pageDue = showing && !key.equals(pageKeys.get(page));
            if (category != null && this.snapshot != null
                    && !this.snapshot.session.equals(snapshot.session)) category = null;
            if (pageDue && LIVE.equals(page) && category != null) {
                ledgerData = LedgerData.capture(engine, now, category);
                if (category.search.isEmpty()) {
                    if (category.table != KILL) ledgerData.income.total = snapshot.income;
                    if (category.table != DEATH)
                        ledgerData.cost.total = category.table == COSTS_LOSS
                                ? -snapshot.loss : -snapshot.supplies;
                    ledgerData.market.total = snapshot.market;
                }
            } else if (pageDue && LEDGER.equals(page))
                ledgerData = LedgerData.capture(engine, now, scope); else if (pageDue && RAIDS.equals(page)) {
                if (raids.selected != null && !raids.selected.equals("all") && engine.raids.find(raids.selected) == null) raids.selected = null;
                engine.raids.read(raids.selected);
                raidState = engine.raids.snapshot();
                for (var run : raidState.runs) run.partial |= engine.raids.missing.contains(run.id);
                raidMoney = engine.raids.totals;
                if (raids.selected != null) ledgerData = LedgerData.capture(engine, now, raids.entry);
            } else if (pageDue && GRINDS.equals(page))
                data = GrindsData.capture(engine, now, archived, selection);
        }
        if (hud != null) {
            hud.filter = context.filter;
            hud.update(snapshot, context::flowVisible, now);
        }
        if (!showing) return;
        shell.raidsAvailable(raidProgress);
        pageForced = false;
        this.snapshot = snapshot;
        live.alwaysPvp = config.pvpView() == GpManagerConfig.PvpView.Always;
        live.apply(snapshot);
        live.category(category == null ? null : categoryPage);
        if (raidState != null)
            raids.apply(raidState,raidMoney,ledgerData,now); else if (ledgerData != null)
            (LIVE.equals(page) ? categoryPage : ledger).apply(ledgerData); else if (data != null)
            plans.apply(data);
        shell.pages.get(page).visible(true);
        if (ledgerData != null || data != null || raidState != null) pageKeys.put(page, key);
    }

    /** Reports only backend-proven recovery or save failure states; successful saves stay quiet. */
    void showPersistenceWarning() {
        if (storage == null) return;
        var status = storage.saveStatus();
        if (status == null) return;
        if (status.isFailure()) {
            // Owner 2026-10-01 (F06): a save failure stays visible until the backend recovers.
            String detail = status.detail.isEmpty() ? status.state.name() : status.detail;
            shell.notifySticky("Data not saved", detail, status.retry ? "Retry" : null,
                    status.retry ? storage::scheduleSave : null);
            return;
        }
        shell.clearSticky();
        if (status.origin != null && !status.origin.isEmpty())
            shell.notify(msg("recovered-backup"), status.origin, false, null, null);
    }

    String activityLabel() {
        var active = engine.session();
        String hint = active == null ? "" : active.activityHint();
        return "General".equalsIgnoreCase(hint) || "Magic".equalsIgnoreCase(hint) ? "" : hint;
    }

    BufferedImage sprite(int id) {
        // Owner 1.1: item icons can be turned off everywhere.
        if (!config.showItemIcons()) return null;
        if (id <= -100000) return sprites.computeIfAbsent(id, key -> fitIcon(
                ImageUtil.loadImageResource(Kit.class, "skulls/" + (-key - 100000) + ".png"), ICON_BOX));
        if (id < -1 && spriteManager != null && !sprites.containsKey(id))
            // A named spell's row shows its spellbook icon; the sprite id arrives negated.
            spriteManager.getSpriteAsync(-id, 0, image -> {
                        if (image != null) spriteLoaded(id, image);
                    });
        if (id <= 0) return sprites.get(id);
        if (spriteOverride != null) {
            var override = spriteOverride.apply(id);
            if (override != null) return override;
        }
        if (itemManager == null) return null;
        var cached = sprites.get(id);
        if (cached != null) return cached;
        try {
            var image = itemManager.getImage(id, 20, false);
            if (image != null) {
                sprites.put(id, image);
                // Late sprites must rebuild the rows drawn without them, not just repaint: rows
                // keep
                // the icon they were built with until the page re-applies (owner 2026-10-01).
                image.onLoaded(() -> spriteLoaded(id, image));
            }
            return image;
        } catch (RuntimeException ex) {
            // The sprite cache is unavailable (headless preview): the row keeps its item name.
            return null;
        }
    }

    /** A sprite that arrived after its row was drawn: cache it and force the rows to rebuild. */
    void spriteLoaded(int id, BufferedImage image) {
        sprites.put(id, fitIcon(image, ICON_BOX));
        refresh();
    }

    void suspend() {
        Object owner;
        synchronized (ownerLock) {
            suspended = true;
            owner = ownerToken = new Object();
        }
        sprites.clear();
        engine.clearActionEvidence();
        onEdt(() -> clearOwner(owner));
    }

    /** EDT-only; a direct resume must finish its pending clear before showing this owner. */
    void clearOwner(Object owner) {
        if (owner != ownerToken || owner == clearedOwner) return;
        clearedOwner = owner;
        cancelKeys(shell);
        shell.dismiss();
        scope = Entry.current();
        category = null;
        if (categoryPage != null) categoryPage.resetOwner();
        ledgerSearch = "";
        selection = null;
        page = 0;
        snapshot = null;
        pageKeys.clear();
        pageForced = true;
        shell.clearSticky();
        ledger.resetOwner();
        raids.open(null,null);
        shell.raidsAvailable(false);
        live.clearOwner();
        shell.pages.values().forEach(page -> page.visible(false));
        shell.show(LIVE);
    }

    void resume() {
        Object owner = ownerToken;
        onEdt(() -> {
                    if (owner != ownerToken) return;
                    clearOwner(owner);
                    synchronized (ownerLock) {
                        if (owner != ownerToken) return;
                        suspended = false;
                    }
                    refreshNow();
                });
    }

    static void onEdt(Runnable task) {
        if (isEventDispatchThread()) {
            task.run();
            return;
        }
        invokeLater(task);
    }

    class LiveActions implements LivePage.Actions {
        public void togglePause() {
            mutate(() -> engine.togglePause(now()));
        }

        public void openLedger(Entry entry) {
            openEntry(entry);
        }

        public void openCategory(Semantics.Table table) {
            if (category != null && category.table == table) {
                categoryPage.actions.recent();
                return;
            }
            category = Entry.current().live(table);
            if (categoryPage == null) categoryPage = new LedgerPage(new LedgerController(SidebarPanel.this, true), SidebarPanel.this::sprite);
            categoryPage.resetOwner();
            live.category(categoryPage);
            refresh();
        }

        public void editTarget() {
            actions.openTargets();
        }

        /** Again: a new session with the same target, in the same run (owner 1.5.2). */
        public void againTarget() {
            mutate(() -> engine.restartTarget(now()));
        }

        public void doneTarget() {
            mutate(() -> engine.setTargets(null, null, now()));
        }

        /** Owner live 2026-10-14: the timer's own pause; the session pause stays the hero dot. */
        public void toggleTimerPause() {
            mutate(() -> engine.toggleTimeTargetPause(now()));
        }

        @Override
        public boolean autoStartArmed() {
            return config.autoStartSession();
        }

        @Override
        public void hideRecent(String name, boolean charge) {
            if (filter != null) {
                filter.hideRecent(name, charge);
                shell.tell("Hidden from Recent", name + " · Booked totals unchanged.", false);
            }
            refresh();
        }

        @Override
        public void renameGrind() {
            if (engine.customActive())
                shell.prompt("Rename Grind", "Grind name", engine.session().name(),
                        name -> mutate(() -> engine.renameActive(name)));
        }

        @Override
        public void showWholeRun() {
            actions.saveSessionPrompt();
        }

        public void newSession() {
            actions.newSession();
        }

    }

    /** Opens the Ledger at a typed context. */
    void openEntry(Entry entry) {
        ledger.prepareEntry();
        scope = entry;
        ledgerSearch = entry.search;
        shell.show(LEDGER);
        refresh();
    }

    void mutate(Runnable domain) {
        // The change runs on the mutation owner (the client thread in the plugin); the redraw is
        // queued only after it has applied, so the sidebar never repaints the pre-click state.
        Object owner = ownerToken;
        if (suspended) return;
        mutationOwner.accept(() -> {
                    synchronized (engine) {
                        if (suspended || ownerToken != owner) return;
                        domain.run();
                    }
                    // A sidebar edit (rename, targets, Start/End) saves now rather than at the next
                    // heartbeat.
                    if (storage != null) storage.scheduleSave();
                    refresh();
                });
    }
}

/**
 * Owns the one GP Manager NavigationButton; install is idempotent, so disable/enable cannot
 * duplicate it, and uninstall always removes exactly what install added.
 *
 * <p>Sidebar navigation and overlay registration have separate lifecycles. The icon loader never
 * returns null, so a missing image cannot silently remove the navigation button.
 */
class NavButton {
    static final Logger log = LoggerFactory.getLogger(NavButton.class);

    interface NavigationHost {
        void add(NavigationButton button);

        void remove(NavigationButton button);
    }

    NavigationButton button;

    /** The runtime sidebar icon; never null so the required button cannot vanish silently. */
    static BufferedImage loadIcon() {
        try (InputStream stream = NavButton.class.getResourceAsStream(msg("profit-manager"))) {
            var image = stream == null ? null : ImageIO.read(stream);
            if (image != null) return image;
            log.warn(msg("gp-manager-icon"));
        } catch (IOException ex) {
            log.warn(msg("gp-manager-icon-resource"), ex);
        }
        return new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    }

    static NavigationHost toolbarHost(ClientToolbar toolbar) {
        if (toolbar == null) return null;
        return new NavigationHost() {
            public void add(NavigationButton navigationButton) {
                toolbar.addNavigation(navigationButton);
            }

            public void remove(NavigationButton navigationButton) {
                toolbar.removeNavigation(navigationButton);
            }
        };
    }

    /**
     * @return true when this call installed the button (host, panel and icon present).
     */
    synchronized boolean install(NavigationHost navigation, PluginPanel panel, BufferedImage icon) {
        if (button != null || navigation == null || panel == null || icon == null) return false;
        NavigationButton next = NavigationButton.builder().tooltip("GP Manager").icon(icon).priority(5).panel(panel)
                        .build();
        navigation.add(next);
        button = next;
        return true;
    }

    synchronized void uninstall(NavigationHost navigation) {
        if (button != null && navigation != null) navigation.remove(button);
        button = null;
    }

    synchronized NavigationButton button() {
        return button;
    }
}

/**
 * Grinds = management (SIDEBAR_SPEC.md §3): the Running line linking to Live, the My Grinds table
 * with the selected row's panel, and Recent grinds opening Grind detail (hero, highlights, vs
 * previous, personal bests). The page renders a {@link GrindsData} and holds no engine.
 */
class GrindsPage implements Shell.Page {
    interface Actions {
        /** Shows Live. */
        void back();

        void openLedger(String id);

        void endGrind();

        void startGrind(String id, String nameOverride);

        void openTargets();

        /** Opens the Start Grind sheet. */
        void newGrind();

        void editSavedGrind(String id);

        void favoriteSavedGrind(String id, boolean favorite);

        void archiveSavedGrind(String id, boolean archived);

        void deleteSavedGrind(String id);

        void startAgain(String id);

        void openDetail(String id);

        void deleteHistorical(String id);

        default void copySummary(String id) {}

        default void renameSession(String id) {}

        /** A past Free play run becomes Free play again (owner 1.5.2). */
        void restoreFreePlay(String id);

        void showArchived(boolean show);

        void refresh();
    }

    Actions actions;
    JPanel body = stack(PANEL, GAP), bar = new JPanel(new BorderLayout(4, 0)), list = stack(PANEL, GAP);
    JPanel detail = stack(PANEL, GAP), running = new JPanel(new BorderLayout(GAP, 0));
    JLabel caption = label("", bold(), TEXT), runningNet = label("", numbers(11.5f), TEXT);
    Table mine = new Table("MY GRINDS");
    JPanel selectedPanel = stack(CARD, 0);
    Table recent = new Table("RECENT GRINDS"), saved = new Table("SAVED SESSIONS");

    /** The current Free play and past ones Reset free-play kept (owner 1.5.2). */
    Table general = new Table("FREE PLAY");

    /** All time as one compact strip; hovering it gives the full breakdown. */
    JPanel overall = stack(CARD, 0);

    Hero hero = new Hero();
    GrindsData data;
    String selectedId;
    JCheckBox archived = new JCheckBox("Archived");

    GrindsPage(Actions actions) {
        this.actions = actions;
        JButton start = button("Start Grind", true, actions::newGrind);
        focus(archived);
        archived.setOpaque(false);
        archived.setFont(small());
        archived.setForeground(LABEL);
        archived.setToolTipText("Include archived Grinds");
        archived.addActionListener(e -> actions.showArchived(archived.isSelected()));
        // Owner 2026-10-01: the same toolbar band as the Ledger; the controls live in the header.
        headerBand(bar, start, archived);
        running.setBackground(CARD);
        pad(running, 2, 6, 2, 2);
        clickable(caption, "Open Live", actions::back);
        onResize(caption, this::runningTitle);
        running.add(caption, NORTH);
        var facts = transparent(new BorderLayout(GAP, 0));
        facts.add(runningNet, CENTER);
        JButton end = button("End", false, actions::endGrind);
        end.setForeground(LOSS.color);
        end.setToolTipText(msg("end-grind"));
        facts.add(end, EAST);
        running.add(facts, CENTER);
        mine.setEmpty(msg("saved-grinds"));
        recent.setEmpty(msg("finished-grinds")).setFill(true);
        pad(selectedPanel, 0, 0, 4, 0);
        // Owner 2026-09-28: My Grinds and Recent grinds first; All time closes the page.
        saved.setEmpty("Save a Live session to keep it here.");
        for (var part : new JComponent[] {running, mine, selectedPanel, recent, saved, general, overall})
            list.add(part);
        pad(body, 0, GAP, GAP, 0);
        body.add(list);
        body.add(detail);
    }

    public String id() {
        return GRINDS;
    }

    public JComponent pageBar() {
        return bar;
    }

    public JComponent body() {
        return body;
    }

    void apply(GrindsData next) {
        data = next;
        list.setVisible(next.detail == null);
        detail.setVisible(next.detail != null);
        if (next.detail != null)
            buildDetail(next); else {
            applyRunning(next);
            applyAllTime(next.overall);
            applyMine(next);
            applyRecent(next);
        }
        body.revalidate();
        body.repaint();
    }

    // ---- list ---------------------------------------------------------------------------------
    void applyRunning(GrindsData data) {
        running.setVisible(data.hasActive);
        if (!data.hasActive) return;
        runningTitle();
        caption.setToolTipText("Open Live · " + data.activeName);
        runningNet.setText(signed(data.activeNet));
        runningNet.setForeground(sign(data.activeNet).color);
        running.setToolTipText(rateLine(data.ready, data.rate) + " · "
                        + duration(data.activeMillis) + " active · "
                        + targetsLine(data.activeNetTarget, data.activeTimeTarget));
        runningNet.setToolTipText(exactSigned(data.activeNet) + " gp");
    }

    void runningTitle() {
        if (data != null && data.hasActive) caption.setText(fitShrinking(caption,
                        "● " + data.activeName, bold(), caption.getWidth() > 0 ? caption.getWidth() : 201));
    }

    /** Every retained session, Free play included; bests are finished Grinds only. */
    void applyAllTime(AllTime overall) {
        var tip = new StringBuilder("<html>All time · ").append(overall.plans)
                        .append(overall.plans == 1 ? " Grind" : " Grinds").append("<br>Net ")
                        .append(exactSigned(overall.net)).append(" gp<br>Active ")
                        .append(duration(overall.activeMillis));
        if (overall.splitAvailable)
            tip.append("<br>Supplies ").append(signed(-overall.supplies)).append(" · Losses ")
                    .append(signed(-overall.losses));
        if (overall.rateName != null)
            tip.append("<br>Best GP/h ").append(overall.rateName).append(" · ").append(rate(overall.bestRate));
        String best = overall.bestName == null ? ""
                        : "Best · " + overall.bestName + " " + signed(overall.bestNet);
        this.overall.removeAll();
        var title = label("ALL TIME · " + overall.plans, small(), LABEL);
        pad(title, 3, 6, 3, 6);
        this.overall.add(title);
        this.overall.add(strip(List.of(new Cell("NET", signed(overall.net), sign(overall.net),
                                        exactSigned(overall.net) + " gp", null), new Cell("GP/H",
                                        overall.rate == null ? "—" : rate(overall.rate), PLAIN, null, null),
                                new Cell("ACTIVE", durationShort(overall.activeMillis), PLAIN,
                                        duration(overall.activeMillis), null))));
        if (!best.isEmpty()) {
            var bestLine = label(best, small(), sign(overall.bestNet).color);
            pad(bestLine, 3, 6, 4, 6);
            this.overall.add(bestLine);
        }
        this.overall.setToolTipText(tip.append("</html>").toString());
        for (var child : this.overall.getComponents()) {
            if (child instanceof JComponent && ((JComponent) child).getToolTipText() == null)
                ((JComponent) child).setToolTipText(this.overall.getToolTipText());
        }
        this.overall.revalidate();
    }

    void applyMine(GrindsData data) {
        var rows = new ArrayList<Row>();
        MyGrind selected = null;
        for (var plan : data.plans) {
            var def = plan.definition;
            String id = def.id();
            boolean isSelected = id.equals(selectedId);
            if (isSelected) selected = plan;
            String runs = plan.linkedCount + (plan.linkedCount == 1 ? " run" : " runs");
            // Owner 1.5.0: the average rate sits in the wide middle column, the last run's Net on
            // the right (a rate with "/h" overflowed the value column).
            rows.add(new Row(id, null, (def.favorite ? "★ " : "") + def.name(), plan.linkedCount == 0 ? "NEW"
                                    : plan.hasRate ? rate(plan.rate) + "/h" : runs,
                            plan.hasLast ? signed(plan.lastNet) : "—", plan.hasLast ? sign(plan.lastNet) : DIM,
                            def.name() + " · " + runs + " · " + targetsLine(
                                            def.targetGp(), def.timeTargetMillis()),
                            plan.hasLast ? "Last run " + exactSigned(plan.lastNet) + " gp" : "", isSelected,
                            () -> select(isSelected ? null : id), grindMenu(plan)));
        }
        mine.rowTitle("MY GRINDS", rows);
        if (selected == null) selectedId = null;
        applySelected(selected);
    }

    void select(String id) {
        selectedId = id;
        if (data != null) {
            applyMine(data);
            body.revalidate();
        }
    }

    void applySelected(MyGrind plan) {
        selectedPanel.removeAll();
        selectedPanel.setVisible(plan != null);
        if (plan == null) return;
        var def = plan.definition;
        var facts = new KeyValue(null);
        // The row already shows the last run's Net and the average GP/h (owner 1.5.2: no repeats).
        facts.put("Last run", plan.hasLast ? when(plan.lastStartedAt, now()) : "—", plan.hasLast ? PLAIN : DIM);
        facts.put("Best", plan.hasBest ? signed(plan.bestNet) : "—", plan.hasBest ? sign(plan.bestNet) : DIM);
        facts.put("Target", targetsLine(def.targetGp(), def.timeTargetMillis()), PLAIN);
        selectedPanel.add(facts);
        var buttons = transparent(new FlowLayout(FlowLayout.LEFT, 4, 2));
        // Owner 2026-10-01 (F14): the selected saved Grind starts right here, beside its menu.
        JButton start = button("Start", true, () -> actions.startGrind(def.id(), null));
        start.setToolTipText("Start " + def.name());
        buttons.add(start);
        buttons.add(button("Edit", false, () -> actions.editSavedGrind(def.id())));
        JButton more = button("···", false, () -> {});
        more.setToolTipText("More for this Grind");
        more.addActionListener(e -> menuOf(grindMenu(plan)).show(more, 0, more.getHeight()));
        buttons.add(more);
        selectedPanel.add(buttons);
    }

    List<Menu> grindMenu(MyGrind plan) {
        var def = plan.definition;
        String id = def.id();
        return Arrays.asList(new Menu("Start", () -> actions.startGrind(id, null)),
                new Menu("Edit…", () -> actions.editSavedGrind(id)), new Menu(
                        def.favorite ? "Unfavourite" : "★ Favourite",
                        () -> actions.favoriteSavedGrind(id, !def.favorite)), new Menu(
                        def.archived ? "Unarchive" : "Archive", () -> actions.archiveSavedGrind(id, !def.archived)),
                new Menu("Delete…", () -> actions.deleteSavedGrind(id)));
    }

    void applyRecent(GrindsData data) {
        long now = now();
        var rows = new ArrayList<Row>();
        var free = new ArrayList<Row>();
        var saved = new ArrayList<Row>();
        for (var run : data.recent) {
            String id = run.session;
            // Free play runs list apart: the current one opens Live; a past one can be restored.
            boolean current = run.general && id.equals(data.activeSessionId);
            var zone = data.zone == null ? ZoneId.systemDefault() : data.zone;
            String when = dayLabel(Instant.ofEpochMilli(run.startedAt).atZone(zone).toLocalDate(), now, zone);
            var menus = new ArrayList<>(Arrays.asList(new Menu("Open in Ledger", () -> actions.openLedger(id)),
                    new Menu("Copy summary", () -> actions.copySummary(id))));
            if (!current) {
                if (id.startsWith("saved:")) menus.add(new Menu("Rename…", () -> actions.renameSession(id)));
                menus.add(run.general ? new Menu("Restore…", () -> actions.restoreFreePlay(id))
                        : new Menu("Start again", () -> actions.startAgain(id)));
                menus.add(new Menu("Delete…", () -> actions.deleteHistorical(id)));
            }
            (id.startsWith("saved:") ? saved : run.general ? free : rows).add(new Row(id, null, run.name,
                                    current ? fallback(data.target, "Now") : when, valueText(run.net), sign(run.net),
                                    run.name + " · " + contextLine(run, now, data.zone),
                                    exactSigned(run.net) + " gp", false,
                                    current ? actions::back : () -> actions.openDetail(id), menus));
        }
        recent.rowTitle("RECENT GRINDS", rows);
        this.saved.rowTitle("SAVED SESSIONS", saved);
        general.rowTitle("FREE PLAY", free);
        general.setVisible(!free.isEmpty());
    }

    // ---- detail -------------------------------------------------------------------------------
    void buildDetail(GrindsData data) {
        var detail = data.detail;
        this.detail.removeAll();
        JButton back = button("‹ Grinds", false, () -> actions.openDetail(null));
        var top = transparent(new BorderLayout());
        top.add(back, WEST);
        top.add(label(when(detail.startedAt, now()).toUpperCase(ROOT), small(), LABEL),
                EAST);
        pad(top, 0, 0, 0, 6);
        this.detail.add(top);
        hero.status(detail.closed ? OFF : GAIN.color, detail.closed ? "Finished" : "Running", detail.name,
                detail.closed ? "COMPLETE" : "", null);
        hero.big(signed(detail.net), sign(detail.net), exactSigned(detail.net) + " gp",
                detail.ready ? rate(detail.rate) + "/h" : "—", detail.ready,
                duration(detail.activeMillis) + " ACTIVE");
        // Owner 2026-10-01 (F12): a completed run's rate is the whole-run basis.
        hero.rate.setToolTipText(detail.ready ? msg("gp-h") : null);
        var strip = money(detail.income, detail.supplies, detail.losses, detail.income - detail.net,
                        detail.splitAvailable, null);
        hero.strips(singletonList(strip));
        this.detail.add(hero);
        // Only targets the run had: an empty "No Net target" line said nothing (owner 1.5.2).
        var result = new KeyValue("HIGHLIGHTS");
        boolean targeted = detail.netTarget != null || detail.timeTarget != null;
        if (detail.netTarget != null) result.put("Target", detail.netOutcome(), PLAIN);
        if (detail.timeTarget != null) result.put("Time", detail.timeOutcome(), PLAIN);
        if (data.pace != null && data.pace.present && data.pace.available
                && detail.session.equals(data.activeSessionId)) {
            // A time target's countdown is already the Time row (owner 1.5.2: no repeat).
            boolean timed = detail.timeTarget != null;
            if (!timed) result.put("Pace", data.pace.line, DIM);
            // Owner 2026-10-01 (F10): a combined target also shows its required average.
            if (data.pace.second != null) result.put(timed ? "Pace" : "", data.pace.second, DIM);
        }
        if (targeted) this.detail.add(result);
        if (detail.compacted) this.detail.add(note(msg("summary-detailed"), LABEL));
        var buttons = transparent(new FlowLayout(FlowLayout.LEFT, 4, 2));
        // A Free play run is restored, never started again as a Grind (owner 1.5.2).
        boolean free = data.recent.stream().anyMatch(run -> run.general && run.session.equals(detail.session));
        if (!free) buttons.add(button("Start again", true, () -> actions.startAgain(detail.session)));
        else if (detail.closed) buttons.add(button("Restore", true, () -> actions.restoreFreePlay(detail.session)));
        buttons.add(button("Open in Ledger", false, () -> actions.openLedger(detail.session)));
        if (detail.closed) {
            var menu = List.of(new Menu("Delete…", () -> actions.deleteHistorical(detail.session)));
            JButton more = button("···", false, () -> {});
            more.addActionListener(e -> menuOf(menu).show(more, 0, more.getHeight()));
            buttons.add(more);
        }
        this.detail.add(buttons);
        this.detail.add(note(msg("open-ledger"), LABEL));
    }

    /** A row's right-click menu, shown as a ⋯ popup. */

    // ---- text ---------------------------------------------------------------------------------
    /** Date and duration context: Today/Yesterday/date plus Active Time, never internal ids. */
    static String contextLine(GrindsData.Recent recent, long now, ZoneId zone) {
        var z = zone == null ? ZoneId.systemDefault() : zone;
        return dayLabel(Instant.ofEpochMilli(recent.startedAt).atZone(z).toLocalDate(), now, z) + " · "
                + durationShort(recent.activeMillis);
    }

    /** Understandable signed money: explicit "0 GP" for zero, never a bare 0. */
    static String valueText(long net) {
        return net == 0 ? "0 GP" : signed(net);
    }

    static String rateLine(boolean established, long gpPerHour) {
        return established ? rate(gpPerHour) + "/h" : "—  GP/h";
    }

    static String targetsLine(Long profit, Long active) {
        var parts = new ArrayList<String>();
        if (profit != null) parts.add(targetNet(profit) + " Net");
        if (active != null) parts.add(targetTime(active));
        return parts.isEmpty() ? "No targets" : String.join(" · ", parts);
    }
}

/** Grinds actions: start, save, edit, targets, history, export, backups and recovery. */
class GrindsController implements GrindsPage.Actions {
    SidebarPanel panel;
    Engine engine;
    Shell shell;
    SessionRepository repository;
    Persistence storage;

    /** One bond's live price for the target form's preset; 0 hides the button (owner 1.5.0). */
    static LongSupplier bondPrice = () -> 0;

    GrindsController(SidebarPanel panel) {
        this.panel = panel;
        engine = panel.engine;
        shell = panel.shell;
        repository = panel.repository;
        storage = panel.storage;
    }

    void mutate(Runnable domain) {
        panel.mutate(domain);
    }

    void openEntry(Entry entry) {
        panel.openEntry(entry);
    }

    public void back() {
        panel.selection = null;
        shell.show(LIVE);
        refresh();
    }

    public void openLedger(String id) {
        var active = engine.session();
        if (active != null && active.id().equals(id))
            openEntry(Entry.current()); else {
            var history = id == null ? null : engine.historySession(id);
            openEntry(new Entry(LedgerData.Scope.HISTORY, id, history == null ? null : history.name()));
        }
    }

    public void endGrind() {
        var ending = engine.session();
        if (ending == null || !engine.customActive()) return;
        String endedId = ending.id(), endedName = ending.name();
        Object owner = panel.ownerToken;
        mutate(() -> {
                    if (engine.session() != ending || !engine.finishCustom(now())) return;
                    var ended = engine.historyMetrics(endedId, now());
                    Runnable recap = () -> {
                        if (panel.suspended || panel.ownerToken != owner) return;
                        shell.notify(endedName + " ended", ended == null ? "" : signed(ended.net), false, "View recap",
                                () -> {
                                    panel.selection = endedId;
                                    shell.show(GRINDS);
                                    refresh();
                                });
                    };
                    if (SwingUtilities.isEventDispatchThread()) recap.run();
                    else SwingUtilities.invokeLater(recap);
                });
    }

    /** The Live clock offers restart, new-session and original-time controls. */
    public void newSession() {
        var content = stack(CARD, 4);
        var help = note("Restart keeps Net.", LABEL, 110);
        help.setToolTipText("<html>" + msg("new-session").replace(". ", ".<br>") + "</html>");
        content.add(help);
        var keep = new JCheckBox("Keep previous clock", true);
        keep.setOpaque(false);
        keep.setFont(small());
        keep.setForeground(TEXT);
        focus(keep);
        keep.setToolTipText("Freeze this clock for Restore time; leave unchecked to keep any older backup.");
        content.add(keep);
        var pair = transparent(new GridLayout(1, 2, 4, 0));
        var compact = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE),
                BorderFactory.createEmptyBorder(3, 4, 3, 4));
        JButton fresh = button("New session", false, () -> {
            shell.closeSheet();
            mutate(() -> engine.newLive(now()));
        });
        fresh.setBorder(compact);
        fresh.setToolTipText(msg("session-new-tip"));
        pair.add(fresh);
        JButton restore = button("Restore time", false, () -> {
            shell.closeSheet();
            mutate(() -> engine.restoreRate(now()));
        });
        restore.setBorder(compact);
        restore.setToolTipText(msg("session-restore-tip"));
        pair.add(restore);
        content.add(pair);
        shell.sheet("Session controls", null, content,
                new Shell.Choice("Restart GP/h", true, () -> mutate(() -> engine.restartRate(now(), keep.isSelected()))));
    }

    void viewRecap() {
        Session source;
        Long start;
        SavedSession copy;
        String text;
        synchronized (engine) {
            source=engine.session();
            if (source==null) return;
            start=source.sessionStartAt;
            long at=now();
            copy=engine.captureLiveSession(source,start,at);
            text=liveSummary(LiveSnapshot.capture(engine,at,null));
        }
        Object owner=panel.ownerToken;
        var content=stack(CARD,2);
        content.add(note("Snapshot · Not saved",LABEL,110));
        for (String line:text.split("\\R"))
            content.add(note(line,TEXT,110));
        content.add(button("Save session",true,()->{
            if (owner!=panel.ownerToken || panel.suspended) return;
            shell.closeSheet();
            mutate(()->savedNotice(engine.saveLiveSession(source,start,copy)!=null));
        }));
        shell.sheet("Session recap",null,content);
    }

    /** Save session's answer; a session that changed while its sheet was open saves nothing. */
    void savedNotice(boolean saved) {
        shell.tell(saved ? "Session saved" : "Not saved", saved ? "Grinds › Saved sessions" : msg("session-changed"), !saved);
    }

    void saveSessionPrompt() {
        var source = engine.session();
        if (source == null) return;
        Long start = source.sessionStartAt;
        shell.sheet("Save session?", msg("save-session"), null,
                new Shell.Choice("Save session", true, () -> mutate(() -> {
                            boolean saved = engine.saveLiveSession(source, start, engine.captureLiveSession(source, start, now())) != null;
                            if (saved) engine.showWholeRun();
                            savedNotice(saved);
                        })),
                new Shell.Choice("Continue without saving", false, () -> mutate(() -> {
                            if (engine.sameLiveWindow(source,start))
                                engine.showWholeRun();
                        })));
    }

    /** Reset free-play from the gear menu: the run so far moves to Grinds history (owner 1.5.2). */
    void startFreePlayOver() {
        shell.confirm("Reset free-play?", msg("free-play-over"), "Reset", () -> {
                    mutate(() -> engine.startFreePlayOver(now()));
                    shell.show(LIVE);
                    shell.tell("Free play reset", "The old run is in Grinds.", false);
                });
    }

    /** A short getting-started guide; its words live in text.tsv (owner 2026-10-16). */
    void guide() {
        var content = stack(CARD, 4);
        for (String part : msg("guide").split(" \\| ")) {
            var line = note(part, TEXT, SHEET_TEXT);
            line.setBorder(null);
            content.add(line);
        }
        shell.sheet("Getting started", null, content);
    }

    public void startGrind(String id, String nameOverride) {
        var definition = id == null ? null : engine.plan(id);
        String name = !blank(nameOverride) ? nameOverride.trim()
                        : definition == null ? "" : definition.name();
        Long net = definition == null ? null : definition.targetGp();
        Long time = definition == null ? null : definition.timeTargetMillis();
        if (definition != null) id = definition.id();
        if (id != null && engine.customActive() && engine.session() != null
                && id.equals(engine.session().grindId())) {
            shell.show(LIVE);
            refresh();
            return;
        }
        startGrind(name, id, net, time);
    }

    /**
     * Start a Grind through the switch guard and show it on Live, saying Free play is kept (owner
     * 1.5.0: a fresh Grind's empty Live read as lost data).
     */
    void startGrind(String name, String id, Long net, Long time) {
        whenSwitchAllowed(name, () -> {
                    mutate(() -> engine.startGrind(name, id, net, time, now()));
                    panel.selection = null;
                    shell.show(LIVE);
                    shell.tell(name + " started", msg("free-play-kept"), false);
                });
    }

    public void openTargets() {
        var active = engine.session();
        if (active == null) return;
        var form = grindForm(null, active.targetGp(), active.timeTargetMillis());
        // Free play's milestone counts all of Free play; a target counts fresh (owner 1.5.2).
        boolean general = !engine.customActive();
        if (general) {
            form.add(label("Milestone · all of Free play", small(), LABEL));
            picks(form, inline(form, "milestoneField", "Net",
                            active.milestoneGp == null ? "" : targetNet(active.milestoneGp)), "100m", "500m", "1b",
                    "2b", "5b", "Clear=");
        }
        targetSheet("Set a Target", form, "Save", (name, net, time) -> {
                    Long target = GrindsData.applyNet(net, active.targetGp());
                    Long duration = GrindsData.applyTime(time, active.timeTargetMillis());
                    Long milestone = GrindsData.applyNet(field(form, "milestoneField"), active.milestoneGp);
                    mutate(() -> {
                                engine.saveTargets(target, duration, now());
                                if (general) engine.setMilestone(milestone);
                            });
                });
    }

    /**
     * The one Start Grind sheet, from Live and Grinds (owner 1.5.0): your Grinds start in one
     * click, the one you are playing right now first; or a name with its targets, which becomes a
     * Grind. Owner 2026-10-01 (F22): the list is bounded to five rows with paging.
     */
    public void newGrind() {
        var active = engine.session();
        String doing = active == null || engine.customActive() ? "" : active.activityHint();
        if (ActivityDetector.GENERAL.equalsIgnoreCase(doing)) doing = "";
        var playing = engine.grindNamed(doing);
        var saved = new ArrayList<>(engine.plans(false));
        saved.sort(Comparator.comparing((SavedGrind definition) -> definition != playing)
                        .thenComparing(grind -> !grind.favorite));
        var content = stack(CARD, 2);
        new Paged(saved.size(), (box, page) -> {
                            int from = min(page * CHOICE_ROWS, saved.size());
                            for (var plan : saved.subList(from, min(from + CHOICE_ROWS, saved.size()))) {
                                JButton start = button((plan.favorite ? "★ " : "") + plan.name()
                                                        + (plan == playing ? " · now" : ""), false, () -> {
                                                    shell.closeSheet();
                                                    startGrind(plan.id(), null);
                                                });
                                start.setHorizontalAlignment(SwingConstants.LEFT);
                                start.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
                                box.add(start);
                            }
                        }).build(content);
        if (!saved.isEmpty()) content.add(label("Or a new one", small(), LABEL));
        // What you are doing now names a new Grind, unless it already is one (listed first).
        var form = grindForm(playing == null ? doing : "", null, null);
        form.add(content, 0);
        targetSheet("Start Grind", form, "Start", (name, net, time) -> {
                            if (name.isEmpty()) return;
                            var known = engine.grindNamed(name);
                            startGrind(known == null ? name : known.name(),
                                    known == null ? null : known.id(), GrindsData.applyNet(net, null),
                                    GrindsData.applyTime(time, null));
                        });
    }

    /** Choice rows a bounded sheet list shows at once (owner 2026-10-01, F22). */
    static final int CHOICE_ROWS = 5;

    /**
     * One bounded choice list: at most five rows, with previous/next paging when there are more.
     * Paging refills the list box in place, so the sheet - and any typed name - is never rebuilt.
     */
    static final class Paged {
        interface RowFill {
            void fill(JPanel box, int page);
        }

        JPanel box = stack(CARD, 2);
        int total;
        RowFill fill;
        int page;
        JButton prev = button("‹", false, () -> turn(-1)), next = button("›", false, () -> turn(1));
        JLabel range = label("", small(), LABEL);
        JPanel bar = transparent(new FlowLayout(FlowLayout.LEFT, 4, 0));

        Paged(int total, RowFill fill) {
            this.total = total;
            this.fill = fill;
            bar.add(prev);
            bar.add(next);
            bar.add(range);
        }

        /** Adds the list (and its pager) to the sheet body and renders the first page. */
        JPanel build(JPanel content) {
            content.add(box);
            if (total > CHOICE_ROWS) content.add(bar);
            redraw();
            return box;
        }

        void turn(int delta) {
            page = clampPage(page + delta, pages());
            redraw();
        }

        int pages() {
            return pageCount(total, CHOICE_ROWS);
        }

        void redraw() {
            box.removeAll();
            fill.fill(box, page);
            int from = page * CHOICE_ROWS;
            applyPager(range, prev, next, page, pages(), total == 0 ? "" : (from + 1) + "–"
                                    + min(total, from + CHOICE_ROWS) + " of " + total);
            box.revalidate();
        }
    }

    /** Name and targets; a running run of this Grind follows the change (owner 1.5.0). */
    public void editSavedGrind(String id) {
        var definition = engine.plan(id);
        if (definition == null) return;
        targetSheet("Edit " + definition.name(), grindForm(definition.name(), definition.targetGp(),
                        definition.timeTargetMillis()), "Save",
                (name, net, time) -> {
                    Long target = GrindsData.applyNet(net, definition.targetGp());
                    Long duration = GrindsData.applyTime(time, definition.timeTargetMillis());
                    mutate(() -> engine.updateSavedGrind(id, name, target, duration, definition.favorite));
                });
    }

    public void renameSession(String id) {
        var saved = engine.savedSession(id);
        if (saved == null) return;
        shell.prompt("Rename session", "Name", saved.session.name(), name -> mutate(() -> {
            if (engine.savedSession(id) != saved) return;
            saved.session.rename(name);
            engine.changed();
        }));
    }

    public void favoriteSavedGrind(String id, boolean favorite) {
        var definition = engine.plan(id);
        if (definition == null) return;
        mutate(() -> engine.updateSavedGrind(id, definition.name(), definition.targetGp(),
                                definition.timeTargetMillis(), favorite));
    }

    public void archiveSavedGrind(String id, boolean archived) {
        if (!archived) {
            mutate(() -> engine.archiveGrind(id, false));
            return;
        }
        shell.confirm("Archive Grind", msg("hides-my"), "Archive",
                () -> mutate(() -> engine.archiveGrind(id, true)));
    }

    public void deleteSavedGrind(String id) {
        var definition = engine.plan(id);
        if (definition == null) return;
        shell.confirm("Delete " + definition.name(), msg("removes-my"), "Delete",
                () -> mutate(() -> engine.deleteSavedGrind(id)));
    }

    /** Starts the run's Grind with its current setup; an older one-off run becomes a Grind. */
    public void startAgain(String id) {
        var active = engine.session();
        Session session = empty(id) || active != null && id.equals(active.id()) ? active
                        : engine.historySession(id);
        if (session == null) return;
        var plan = engine.plan(session.grindId());
        if (plan == null) plan = engine.grindNamed(session.name());
        if (plan != null)
            startGrind(plan.id(), null); else
            startGrind(session.name(), null, session.targetGp(), session.timeTargetMillis());
    }

    public void openDetail(String id) {
        panel.selection = id;
        refresh();
    }

    public void restoreFreePlay(String id) {
        shell.confirm("Restore this Free play?", msg("restore-free-play"), "Restore", () -> {
                    mutate(() -> engine.restoreFreePlay(id, now()));
                    panel.selection = null;
                    shell.show(LIVE);
                    shell.tell("Free play restored", "The other run is under Grinds › FREE PLAY.", false);
                });
    }

    public void deleteHistorical(String id) {
        var session = engine.historySession(id);
        if (session == null) return;
        shell.confirm("Delete " + session.name(), msg("removes-run"), "Delete", () -> {
                    panel.selection = null;
                    mutate(() -> engine.deleteHistory(id));
                });
    }

    public void showArchived(boolean show) {
        panel.archived = show;
        refresh();
    }

    /** The gear menu beside the tabs: profile-wide data actions, from any page. */
    JPopupMenu dataMenu() {
        var menu = new JPopupMenu();
        menu.add(item("View recap", engine.session() != null, this::viewRecap));
        menu.add(item("Copy Live summary", engine.session() != null, () -> copySummary(null)));
        menu.add(item("Getting started", this::guide));
        menu.add(item("Back up profile", storage != null, () -> backUpProfile()));
        menu.add(item(msg("copy-data"), () -> copyDataPath()));
        menu.add(item("Report a bug", () -> LinkBrowser.browse(msg("bug-report"))));
        menu.add(item("Changelog", () -> LinkBrowser.browse(msg("changelog"))));
        menu.addSeparator();
        menu.add(item("Reset free-play…", !engine.customActive(), this::startFreePlayOver));
        menu.add(item(msg("factory-reset"), storage != null, () -> factoryReset()));
        return menu;
    }

    public void refresh() {
        panel.refresh();
    }

    /** Name (when given) and the two targets, each with its quick picks. */
    static JPanel grindForm(String name, Long net, Long time) {
        var form = stack(CARD, 2);
        if (name != null) inline(form, "nameField", "Name", name);
        long bond = bondPrice.getAsLong();
        picks(form, inline(form, "netField", "Net", net == null ? "" : targetNet(net)), "5m",
                bond > 0 ? "Bond=" + targetNet(bond) : "10m", "20m", "50m", "100m", "1b", "Clear=");
        picks(form, inline(form, "timeField", "Time", time == null ? "" : targetTime(time)), "30m", "1h", "2h", "3h",
                "4h", "6h", "Clear=");
        return form;
    }

    /** A label beside its field (owner 1.5.0: a short, sleek target sheet). */
    static JTextField inline(JPanel form, String key, String label, String value) {
        var field = new JTextField(value);
        var name = label(label, small(), LABEL);
        name.setPreferredSize(new Dimension(34, name.getPreferredSize().height));
        var line = transparent(new BorderLayout());
        line.add(name, WEST);
        line.add(field);
        form.add(line);
        form.putClientProperty(key, field);
        return field;
    }

    /**
     * One-click values under a target field ("label=value" when they differ; Clear empties it). The
     * pick matching the field is lit, so the sheet shows the current target at a glance.
     */
    static void picks(JPanel form, JTextField field, String... picks) {
        var row = transparent(new GridLayout(1, 0));
        pad(row, 0, 0, 2, 0);
        Map<JButton, String> values = new LinkedHashMap<>();
        Runnable light = () -> values.forEach((chip, value) -> chip.setForeground(
                                                value.equalsIgnoreCase(field.getText().trim()) ? ACCENT : LABEL));
        for (String pick : picks) {
            String[] part = pick.split("=", -1);
            String value = part[part.length - 1];
            JButton chip = button(part[0], false, () -> {
                                field.setText(value);
                                light.run();
                            });
            pad(chip, 1, 0, 1, 0);
            chip.setContentAreaFilled(false);
            values.put(chip, value);
            row.add(chip);
        }
        field.addCaretListener(e -> light.run());
        light.run();
        form.add(row);
    }

    static String field(JPanel form, String key) {
        Object field = form.getClientProperty(key);
        return field instanceof JTextField ? ((JTextField) field).getText().trim() : "";
    }

    interface TargetSave {
        void save(String name, String net, String time);
    }

    /**
     * Malformed targets keep the same draft and saved-picker page open for correction.
     */
    void targetSheet(String title, JPanel form, String action, TargetSave save) {
        shell.sheet(title, null, form, new Shell.Choice(action, true, () -> {
                            String net = field(form, "netField"), time = field(form, "timeField");
                            if (GrindsData.inputsValid(net, time) && GrindsData.parseNet(field(form, "milestoneField"))
                                            != GrindsData.INVALID)
                                save.save(field(form, "nameField"), net, time); else {
                                shell.tell("Targets not saved", msg("use-number"), true);
                                targetSheet(title, form, action, save);
                            }
                        }));
    }

    /** One Grind at a time: starting another asks to end the running one first (spec §4). */
    void whenSwitchAllowed(String nextName, Runnable start) {
        if (engine.session() == null || !engine.customActive()) {
            start.run();
            return;
        }
        var now = panel.snapshot;
        String running = engine.session().name() + (now == null ? ""
                                : " (" + signed(now.net) + ", " + clock(now.elapsed) + ")");
        // The engine never replaces a running Grind silently: Switch ends it first, then starts.
        shell.confirm("Switch Grind", "End " + running + " and start " + nextName + "?", "Switch", () -> {
                    mutate(() -> engine.finishCustom(now()));
                    start.run();
                });
    }

    /** Copies the selected view's exact figures without writing or mutating its session. */
    public void copySummary(String id) {
        String text;
        long now = now();
        synchronized (engine) {
            if (id == null) {
                var live = LiveSnapshot.capture(engine, now, null);
                if (!live.present) return;
                text = liveSummary(live);
            } else {
                var session = engine.historySession(id);
                if (session == null) return;
                var money = engine.savedSession(id) != null ? session.metrics(now)
                        : engine.summary().run(engine, session, now).metrics;
                text = summary(session.name(), money.net, money.income, money.cost, money.supplies,
                        money.other, money.split, money.elapsed);
            }
        }
        shareSummary(() -> text);
    }

    void shareSummary(Supplier<String> text) {
        try {
            copyText(text.get());
            shell.tell("Summary copied", "Ready to paste.", false);
        } catch (RuntimeException failed) {
            shell.failed("Copy failed", failed);
        }
    }

    static String liveSummary(LiveSnapshot live) {
        return summary(live.general ? "Free play" : live.ownerLabel, live.net, live.income, live.cost,
                live.supplies, live.loss, live.split, live.elapsed)
                + (live.market==0 ? "" : String.format(ROOT,msg("summary-market"),exactSigned(live.market)));
    }

    static String summary(String name, long net, long income, long cost, long supplies, long lost,
            boolean split, long elapsed) {
        return String.format(ROOT, msg("session-summary"), name, exactSigned(net), clock(elapsed),
                RateReadiness.isEstablished(elapsed) ? exact(Session.hourly(net, elapsed)) : "Calculating…",
                exact(income), exact(cost)) + (split ? String.format(ROOT, msg("summary-costs"),
                        exact(supplies), exact(lost)) : "");
    }

    /** Puts text on the local system clipboard. */
    void copyText(String text) {
        var selection = new StringSelection(text);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
    }

    /**
     * Disk work runs on the persistence worker, never Swing's thread, so RuneLite stays responsive;
     * its result comes back on Swing's thread only while the completed account/lifecycle still owns
     * the UI. Reset may advance its own lifecycle generation during the work.
     */
    <T> void off(java.util.concurrent.Callable<T> work, Consumer<T> done, String failed) {
        var worker = storage.executor();
        if (worker == null) return;
        long request = storage.request.get();
        var owner = storage.activeIdentity;
        worker.execute(() -> {
                    try {
                        storage.busy = true;
                        Runnable presentation;
                        long completed;
                        synchronized (storage) {
                            if (request != storage.request.get()
                                            || !Objects.equals(owner, storage.activeIdentity)) return;
                            try {
                                T result = work.call();
                                presentation = () -> done.accept(result);
                            } catch (Exception ex) {
                                presentation = () -> shell.failed(failed, ex);
                            }
                            // Reset legitimately advances the generation; pin its finished result.
                            completed = storage.request.get();
                        }
                        Runnable ready = presentation;
                        SwingUtilities.invokeLater(() -> {
                                    if (!storage.closing && completed == storage.request.get()
                                                    && Objects.equals(owner, storage.activeIdentity)) ready.run();
                                });
                    } finally {
                        storage.busy = false;
                    }
                });
    }

    void backUpProfile() {
        off(() -> storage.writeBackup(now()), backup -> shell.tell(
                                "Profile backed up", valueOf(backup.getFileName()), false), "Backup failed");
    }

    void copyDataPath() {
        if (repository == null) return;
        String path = valueOf(repository.dataDir());
        copyText(path);
        shell.tell(msg("data-folder"), path, false);
    }

    /**
     * Delete Profile (owner 1.5.2): Back up & delete writes a validated safety copy first and a
     * failed copy refuses; Delete without backup cannot be undone.
     */
    void factoryReset() {
        shell.sheet("Delete Profile", msg("resets-gp"), null,
                new Shell.Choice("Back up & delete", true, () -> resetNow(true)),
                new Shell.Choice("Delete without backup", false, () -> resetNow(false)));
    }

    /** The direct delete: one confirmation already happened in the sheet. */
    void resetNow(boolean backup) {
        off(() -> storage.factoryReset(now(), backup),
                this::reset, "Delete failed");
    }

    void reset(Persistence.ResetOutcome result) {
        // The profile was replaced: HUD+ must not keep the old trip's loot or moments.
        if (result.isApplied()) panel.resetProfile();
        if (result.isApplied())
            // The safety copy is one click from its folder (owner 2026-10-16).
            shell.notify("Profile reset", msg("fresh-profile"), false, "Copy folder path", this::copyDataPath); else
            shell.tell("Reset refused", result.getDetail(), true);
    }
}

/**
 * Grinds read model: the current canonical Grind, the reusable My Grinds definitions with their
 * derived Last/Best subset, the Recent Grinds history and one Grind detail. Targets and Pace are
 * derived from canonical facts; estimates never feed accounting, completion or PBs.
 */
@AllArgsConstructor
class GrindsData {
    /** A target field that did not parse: it never mutates and never means "clear". */
    static final long INVALID = -1;

    /**
     * Friendly Net-target parsing: 5m, 5M, 5000k, 5,000,000. Blank is 0 (no target); malformed is
     * {@link #INVALID}.
     */
    static long parseNet(String raw) {
        if (blank(raw)) return 0;
        // Strict whole-string form: digits with optional decimals and one k/m/b suffix. Anything
        // else is malformed and must never be treated as "clear".
        String text = lower(raw).replace(",", "").replace("_", "").replace(" ", "");
        if (!pattern("target-net").matcher(text).matches()) return INVALID;
        long value = parseGp(text);
        return value > 0 ? value : INVALID;
    }

    /**
     * Friendly Active-Time parsing: 90m, 3h, "3h 30m", plain minutes. Blank is 0 (no target);
     * malformed is {@link #INVALID}.
     */
    static long parseTime(String raw) {
        if (blank(raw)) return 0;
        String text = lower(raw).replace(",", "").replace("_", "").trim();
        try {
            if (pattern("target-minutes").matcher(text).matches()) {
                long minutes = round(Double.parseDouble(text));
                return minutes > 0 ? multiplyExact(minutes, 60000) : INVALID;
            }
            // Strict whole-string form: optional "Nh" then optional "Mm" and nothing else.
            var matcher = pattern("target-time").matcher(text);
            if (!matcher.matches() || matcher.group(1) == null && matcher.group(2) == null)
                return INVALID;
            long total = 0;
            if (matcher.group(1) != null)
                total = addExact(total, round(Double.parseDouble(matcher.group(1)) * 3_600_000d));
            if (matcher.group(2) != null)
                total = addExact(total, round(Double.parseDouble(matcher.group(2)) * 60_000d));
            return total > 0 && total < Long.MAX_VALUE ? total : INVALID;
        } catch (ArithmeticException overflow) {
            return INVALID;
        }
    }

    /** Applies a Net input to an existing target: INVALID keeps the existing value untouched. */
    static Long applyNet(String raw, Long existing) {
        return apply(parseNet(raw), existing);
    }

    /** Applies an Active-Time input to an existing target: INVALID keeps the existing value. */
    static Long applyTime(String raw, Long existing) {
        return apply(parseTime(raw), existing);
    }

    static Long apply(long parsed, Long existing) {
        return parsed == INVALID ? existing : parsed == 0 ? null : parsed;
    }

    /** True when both fields are safe to apply (valid or deliberately blank). */
    static boolean inputsValid(String netRaw, String timeRaw) {
        return parseNet(netRaw) != INVALID && parseTime(timeRaw) != INVALID;
    }

    /** Derived pace over canonical facts; nothing here is booked or persisted. */
    @AllArgsConstructor
    static class Pace {
        /** True when a Pace question exists at all (at least one target). */
        boolean present;

        boolean available;
        String line, second;

        /** No target at all: there is no Pace question to answer, so no row renders. */
        static Pace none() {
            return new Pace(false, false, "", null);
        }
    }

    /**
     * Mode A/B/C pace. A: time until the Net target. B: projected Net at the Active-Time target. C:
     * both plus the required average. Unavailable evidence reads "Calculating…" instead of a
     * fabricated precision; estimates stay visually muted.
     */
    static Pace pace(Long profit, Long targetMillis, long net, boolean ready, long rate,
            long active) {
        if (profit == null && targetMillis == null) return Pace.none();
        if (!ready) return new Pace(true, false, "Calculating…", null);
        if (profit != null && targetMillis == null) {
            if (net >= profit) return new Pace(true, true, "Target reached", null);
            if (rate <= 0)
                // The rate is established and genuinely non-positive: an ETA would be fabricated.
                return new Pace(true, true, "Not earning at this rate", null);
            long minutes = positive(round(subtract(profit, net) * 60d / rate));
            return new Pace(true, true, "~" + durationMinutes(minutes) + " remaining", null);
        }
        // A time target counts down; a projected Net only repeated GP/h (owner 1.5.2).
        long remaining = positive(targetMillis - active);
        String left = durationMinutes(remaining / 60000) + " left";
        if (profit == null)
            return new Pace(true, true, remaining <= 0 ? "Time target reached" : left, null);
        if (remaining <= 0)
            return new Pace(true, true, "Time target reached", "Required average was "
                            + compact(requiredAverage(profit, net, active)) + "/h");
        long required = requiredAverage(profit, net, targetMillis);
        return new Pace(true, true, left, "Required average " + compact(required) + "/h");
    }

    static long requiredAverage(long target, long net, long byMillis) {
        return Session.hourly(positive(subtract(target, net)), byMillis);
    }

    static String durationMinutes(long minutes) {
        return minutes < 90 ? minutes + "m" : minutes / 60 + "h " + minutes % 60 + "m";
    }

    /** One reusable definition plus its derived Last/Best subset from linked canonical history. */
    @AllArgsConstructor
    static class MyGrind {
        SavedGrind definition;
        int linkedCount;
        boolean hasLast;
        long lastNet, lastStartedAt;
        boolean hasBest;
        long bestNet;

        /** Average GP/h over every finished run (owner 1.5.0: Grinds compare by rate). */
        long rate;

        boolean hasRate;
    }

    /** One recent canonical instance, rendered with Grind language. */
    @AllArgsConstructor
    static class Recent {
        String session, name;
        long startedAt, net, activeMillis;

        /** A Free play run (current or reset), listed under FREE PLAY (owner 1.5.2). */
        boolean general;
    }

    /** One instance's detail: financial summary, targets and target outcome. */
    @AllArgsConstructor
    static class Detail {
        String session, name;
        long startedAt, net;
        /** What the Net aim counted when this ran (the run's Net minus its set baseline). */
        long targetProgress, income, supplies, losses;
        boolean splitAvailable;
        long activeMillis;
        /** The Active Time window the run's time target counts from (set moment to now/end). */
        long targetWindowMillis;
        boolean ready;
        long rate;
        Long netTarget, timeTarget;
        boolean closed, compacted;

        /** Factual target outcome wording; never "Failed". */
        String netOutcome() {
            return GrindHistory.netOutcome(netTarget, targetProgress, closed);
        }

        String timeOutcome() {
            return GrindHistory.timeOutcome(timeTarget, targetWindowMillis);
        }
    }

    boolean hasActive;
    String activeName, activeSessionId;
    long activeNet, rate;
    boolean ready;
    long activeMillis;
    Long activeNetTarget, activeTimeTarget;
    Pace pace;
    List<MyGrind> plans;
    List<Recent> recent;

    /** Free play's milestone progress, "1B · 42%"; empty with none (owner 1.5.2). */
    String target;

    ZoneId zone;
    Detail detail;
    AllTime overall;

    /** The All time card: every retained session (Free play included) from canonical metrics. */
    @AllArgsConstructor
    static class AllTime {
        long net, activeMillis;
        Long rate;
        int plans;
        boolean splitAvailable;
        long supplies, losses;
        String bestName;
        long bestNet;
        String rateName;
        long bestRate;
    }

    static AllTime allTime(Engine engine, long now) {
        var summary = engine.summary();
        long net = 0, active = 0, supplies = 0, lost = 0, best = 0, bestRate = 0;
        var grinds = new HashSet<String>();
        boolean split = true;
        String bestName = null, rateName = null;
        var seen = new HashSet<String>();
        var sessions = new ArrayList<>(engine.history());
        sessions.add(engine.session());
        sessions.add(engine.freePlay());
        for (var session : sessions) {
            if (session == null || !seen.add(session.id())) continue;
            var run = summary.run(engine, session, now);
            var metrics = run.metrics;
            net = sum(net, metrics.net);
            active = sum(active, metrics.elapsed);
            split &= metrics.split;
            supplies = sum(supplies, metrics.supplies);
            lost = sum(lost, metrics.other);
            if (session.grind)
                // Runs of one saved Grind count once; an unsaved Grind is its own name.
                grinds.add(session.isLinked() ? session.grindId() : "name:" + session.name());
            if (!session.isClosed()) continue;
            if (bestName == null || run.net() > best) {
                bestName = session.name();
                best = run.net();
            }
            if (run.rate != null && (rateName == null || run.rate > bestRate)) {
                rateName = session.name();
                bestRate = run.rate;
            }
        }
        return new AllTime(net, active, RateReadiness.isEstablished(active) ? Session.hourly(net, active) : null,
                grinds.size(), split, supplies, lost, bestName, best, rateName, bestRate);
    }

    static GrindsData capture(Engine engine, long now, boolean includeArchived, String id) {
        var summary = engine.summary();
        var active = engine.session();
        boolean custom = engine.customActive();
        long net = 0, rate = 0;
        boolean ready = false;
        long elapsed = 0;
        Long target = null, duration = null;
        String activeName = "", activeSessionId = "";
        if (active != null) {
            var metrics = summary.metrics(engine.session(), now);
            net = metrics.net;
            rate = metrics.rate;
            ready = RateReadiness.isEstablished(metrics.elapsed);
            elapsed = metrics.elapsed;
            target = active.targetGp();
            duration = active.timeTargetMillis();
            activeName = active.name();
            activeSessionId = active.id();
        }
        // The Active Time target's own window counts from when it was set (owner live 2026-10-13);
        // the displayed Active Time above stays the run's total.
        long targetWindowMillis = active == null ? 0 : active.timeWindowMillis(elapsed);
        Pace pace = pace(target, duration, active == null ? net : active.targetProgress(net),
                        ready, rate, targetWindowMillis);
        var plans = new ArrayList<MyGrind>();
        for (var definition : engine.plans(includeArchived)) {
            var rollup = summary.rollups.get(definition.id());
            plans.add(new MyGrind(definition, rollup.count, rollup.has, rollup.last, rollup.at, rollup.has,
                            rollup.best, Session.hourly(rollup.total, rollup.millis),
                            RateReadiness.isEstablished(rollup.millis)));
        }
        plans.sort(Comparator.comparing((MyGrind plan) -> !plan.definition.favorite)
                        .thenComparing(grind -> -grind.lastStartedAt).thenComparing(
                                grind -> grind.definition.name(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(grind -> grind.definition.id()));
        var recent = new ArrayList<Recent>();
        var runs = new ArrayList<>(engine.history());
        // The current Free play heads FREE PLAY; a running Grind pauses it, so it waits then.
        if (!custom && engine.freePlay() != null) runs.add(engine.freePlay());
        for (var session : runs) {
            if (session == null) continue;
            var metrics = summary.run(engine, session, now).metrics;
            recent.add(new Recent(session.id(),
                            session.isClosed() || session.grind ? session.name() : "Free play",
                            session.startedAt, metrics.net, metrics.elapsed, !session.grind));
        }
        recent.sort(Comparator.comparingLong((Recent item) -> item.startedAt).reversed()
                        .thenComparing(item -> item.session));
        for (var saved : engine.saved) {
            var money = saved.session.metrics(now);
            recent.add(new Recent(saved.id, saved.session.name(), saved.session.endedAt,
                    money.net, money.elapsed, false));
        }
        Detail detail = null;
        if (!empty(id)) {
            Session selected = active != null && id.equals(active.id()) ? active
                            : engine.historySession(id);
            if (selected != null) {
                boolean liveDetail = active != null && id.equals(active.id());
                Metrics metrics = engine.savedSession(id) != null ? selected.metrics(now)
                                : liveDetail ? summary.metrics(engine.session(), now)
                                : summary.run(engine, selected, now).metrics;
                detail = new Detail(id, selected.name(), selected.startedAt, metrics.net,
                                selected.targetProgress(metrics.net), metrics.income,
                                metrics.split ? metrics.supplies : -1,
                                metrics.split ? metrics.other : -1, metrics.split,
                                metrics.elapsed, selected.timeWindowMillis(metrics.elapsed),
                                RateReadiness.isEstablished(metrics.elapsed),
                                Session.hourly(metrics.net, metrics.elapsed), selected.targetGp(),
                                selected.timeTargetMillis(), selected.isClosed(),
                                selected.retainedRevenue != 0 || selected.retainedCosts != 0);
            }
        }
        var profileZone = engine.timeZone();
        return new GrindsData(active != null && custom, activeName, activeSessionId, net, rate, ready,
                elapsed, target, duration, pace, plans, recent,
                milestoneText(engine.freePlay(), now), profileZone, detail, allTime(engine, now));
    }

    /** "1B · 42%" or "1B ✓" for Free play's milestone; empty with none. */
    static String milestoneText(Session general, long now) {
        Long gp = general == null ? null : general.milestoneGp;
        if (gp == null) return "";
        long net = general.metrics(now).net;
        return compact(gp) + " · " + (net >= gp ? "✓" : percent(max(0d, (double) net / gp)));
    }
}

/**
 * Shared Grind facts: one session's run metrics and the factual target wording the Grinds pages
 * show. Nothing here books, prices, persists or feeds accounting.
 */
class GrindHistory {
    /**
     * One session's shared facts: the retained summary's metrics, else the session's own
     * correction-aware totals, plus the full-session rate once established. Grinds, Grind history
     * and the All time card all read sessions through this, so the pages cannot disagree.
     */
    static class Run {
        Session session;
        Metrics metrics;
        Long rate;

        /** Detached metrics supplied by the revision summary; fields keep their existing names. */
        Run(Session session, Metrics metrics) {
            this.session = session;
            this.metrics = metrics;
            rate = RateReadiness.isEstablished(metrics.elapsed)
                            ? Session.hourly(metrics.net, metrics.elapsed) : null;
        }

        long net() {
            return metrics.net;
        }
    }

    /** Factual target wording; never "Failed". Shared with the Grinds detail. */
    static String netOutcome(Long profit, long net, boolean closed) {
        if (profit == null) return "No Net target";
        if (net >= profit) return "Reached " + compact(profit) + " Net";
        return closed ? msg("ended-before") + signed(profit - net) + " remaining"
                : msg("reached") + signed(profit - net) + " remaining";
    }

    /** Factual Active-Time target wording; never "Failed". Shared with the Grinds detail. */
    static String timeOutcome(Long active, long activeMillis) {
        if (active == null) return msg("time-paused");
        if (activeMillis >= active) return msg("time-target");
        return duration(active - activeMillis) + msg("active-time-remaining");
    }

    /** Full-session rate: Net over canonical Active Time; zero Active Time has no rate. */
}

/**
 * One presentation law for "is this GP/h trustworthy yet". A computed rate is only meaningful once
 * the Grind has real Active Time behind it; before that the canonical number is a data-availability
 * artifact, not a rate, and every surface must render the unavailable form ("—") instead of a
 * fabricated 0/h.
 *
 * <p>The minimum window is the historical accepted value (the retired {@code RateAvailability} used
 * the same 60 seconds); it is presentation-only and never affects accounting.
 */
class RateReadiness {
    /** Minimum Active Time before a computed rate is presentation-trustworthy. */
    static final long MIN_ACTIVE_MILLIS = 60000;

    /**
     * GP/h is Net over canonical Active Time; it is shown once that Active Time reaches the floor.
     *
     * @param activeMillis Active Time of the displayed rate window
     */
    static boolean isEstablished(long active) {
        return active >= MIN_ACTIVE_MILLIS;
    }
}

/** Observed raid history; canonical receipts remain the sole financial owners. */
class RaidHistory {
    static final List<String[]> RULES = GameData.rows("raid_history");
    static final Map<Integer,String> PICKAXES = byId("raid_pickaxes"), PICKAXE_STORES = byId("raid_pickaxe_stores");

    static class Run extends SaveCopies {
        String id, mode;
        long started, ended;
        boolean complete, claimed, partial, died, failed;
        Set<String> owners = new HashSet<>();
    }

    static class State extends SaveCopies {
        List<Run> runs = new ArrayList<>();
        List<Claim> claims = new ArrayList<>();
        long[] clears = new long[RULES.size()];
        int seen;
        String death;
        Map<Integer, Long> supplies = new HashMap<>();
    }

    static class Claim extends SaveCopies {
        String id;
        int chest;
        int[] epoch;
        Map<Integer, Long> remaining = new LinkedHashMap<>();
        boolean observed;
    }

    @AllArgsConstructor
    static class Sample {
        int kind;
        boolean inside;
        String mode;
        int[] counters;
    }

    final Engine engine;
    State state = new State();
    Run active;
    Sample[] previous = new Sample[RULES.size()];
    int absent, absentTick = -1;
    long revision = -1;
    String selected;
    Map<String, long[]> totals = new HashMap<>();
    Set<String> missing = new HashSet<>();
    SavedSession view;

    RaidHistory(Engine engine) {
        this.engine = engine;
    }

    static int kind(String id) {
        return id != null && (id.length() == 1 || id.length() == 38 && id.charAt(1) == ':')
                && id.charAt(0) >= '0' && id.charAt(0) - '0' < RULES.size() ? id.charAt(0) - '0' : -1;
    }

    Run find(String id) {
        for (var run : state.runs) if (Objects.equals(id, run.id)) return run;
        return null;
    }

    String activeId() {
        return active == null ? null : active.id;
    }

    void changed() {
        revision = -1;
        engine.changed();
    }

    Run start(int kind, String mode, long now, boolean partial) {
        var run = new Run();
        run.id = kind + ":" + uuid();
        run.mode = mode;
        run.started = now;
        run.partial = partial;
        state.runs.add(0, run);
        state.seen |= 1 << kind;
        changed();
        return run;
    }

    void observe(Sample sample, long now, int tick) {
        int kind = sample.kind;
        if (kind < 0 || kind >= RULES.size()) return;
        var old = previous[kind];
        previous[kind] = sample;
        if (sample.inside) {
            if (active == null || kind(active.id) != kind || active.complete && old != null && !old.inside) {
                end(now);
                if (kind != 3 && old != null && !old.inside) state.claims.removeIf(claim -> kind(claim.id) == kind);
                active = start(kind, sample.mode, now, old == null || old.inside);
                prune();
            }
            absent = 0;
        }
        if (active != null && kind(active.id) == kind) {
            if (old != null && old.counters.length == sample.counters.length) {
                int count = 0;
                boolean invalid = false;
                for (int i = 0; i < sample.counters.length; i++) {
                    long delta = (long) sample.counters[i] - old.counters[i];
                    count += delta == 1 ? 1 : 0;
                    invalid |= delta != 0 && delta != 1;
                }
                if (invalid || count > 1) {
                    active.partial = true;
                    changed();
                } else if (count == 1) complete(active, now);
            }
            if (!sample.inside && absentTick != tick) {
                absentTick = tick;
                if (++absent >= 3) end(now);
            }
        }
    }

    void complete(Run run, long now) {
        if (run.complete) return;
        run.complete = true;
        run.ended = now;
        int kind = kind(run.id);
        int chest = Integer.parseInt(RULES.get(kind)[2]);
        state.claims.removeIf(item -> item.chest == chest && !run.id.equals(item.id));
        var claim = state.claims.stream().filter(item -> run.id.equals(item.id)).findFirst().orElse(null);
        if (claim == null) {
            claim = new Claim();
            claim.id = run.id;
            claim.chest = chest;
            state.claims.add(claim);
        }
        claim.epoch = previous[kind].counters.clone();
        changed();
    }

    void end(long now) {
        if (active == null) return;
        if (active.ended == 0) active.ended = now;
        active = null;
        absent = 0;
        changed();
    }

    boolean unlocked() {
        return state.seen != 0 || !state.runs.isEmpty() || Arrays.stream(state.clears).anyMatch(n -> n > 0);
    }

    void death() {
        var sample = active == null ? null : previous[kind(active.id)];
        state.death = sample == null || sample.inside ? activeId() : null;
        if (state.death != null) active.died = true;
        changed();
    }

    void fee(Transaction row) {
        var run = find(state.death);
        if (run == null || row == null) return;
        for (var flow : row.flows()) flow.raid = run.id;
        run.owners.add(engine.session().id());
        changed();
    }

    /** A source-confirmed dose allowance, spent first when borrowed and owned stock mix. */
    boolean supplies(List<Flow> flows, boolean use, Map<Integer, Long> loot) {
        if (!use) claim(flows, f -> PICKAXES.containsKey(f.item), (f, q) -> engine.events.take(Inbox.RAID_STORE, -1,
                q - (f.gain() ? min(q, loot.getOrDefault(f.item, 0L)) : 0),
                e -> e.data == state && e.x == Long.signum(f.quantity)));
        if (state.supplies.isEmpty() && engine.events.peek(Inbox.LOAN) == null) return false;
        if (!use) {
            claim(flows, Flow::gain, (flow, quantity) -> {
                long n = engine.events.take(Inbox.LOAN, flow.item,
                        quantity - min(quantity, loot.getOrDefault(flow.item, 0L)), e -> e.data == state);
                if (n > 0) {
                    state.supplies.merge(ItemVariationMapping.map(flow.item),
                            multiply(n, max(1, ActionReader.dose(flow.name))), SafeMath::sum);
                    changed();
                }
                return n;
            });
            // A borrowed hammer removed outside a death wipe is stripped without a booking.
            claim(flows, f -> f.item == 2347 && f.cost() && !engine.death.wipePending(), (f, q) -> {
                long n = take(state.supplies, 2347, q); if (n > 0) changed(); return n;
            });
            return false;
        }
        boolean applied = false;
        for (int family : new ArrayList<>(state.supplies.keySet())) {
            var group = matching(flows, f -> ItemVariationMapping.map(f.item) == family);
            long used = 0, cost = 0, vials = 0;
            for (var f : group) {
                int dose = ActionReader.dose(f.name);
                used = subtract(used, multiply(f.quantity, max(1, dose)));
                cost = subtract(cost, f.value);
                if (dose == 1 && f.cost()) vials = sum(vials, SafeMath.abs(f.quantity));
            }
            long free = take(state.supplies, family, used);
            if (free == 0) continue;
            changed();
            applied = true;
            var containers = new HashMap<Integer, Long>(); containers.put(229, min(free, vials));
            claim(flows, f -> f.gain() && f.item == 229, (f, q) -> take(containers, 229, q));
            if (free == used) flows.removeAll(group);
            else {
                // Captured unit quotes and physical quantities stay exact; only paid GP remains.
                long credit = cost <= 0 ? 0 : multiply(cost, free) / used;
                for (var f : group) if (f.cost()) {
                    long n = min(credit, SafeMath.abs(f.value)); f.value = sum(f.value, n); credit -= n;
                }
            }
        }
        return applied;
    }

    void received(String id, Session owner, long now) {
        var run = find(id);
        if (run == null) {
            run = start(kind(id), "Partial capture", now, true);
            run.id = id;
            prune();
        }
        run.complete = true;
        run.claimed = true;
        if (run.ended == 0) run.ended = now;
        run.owners.add(owner.id());
        changed();
    }

    void logout(long now) {
        if (active != null) active.partial = true;
        end(now);
        restore(snapshot());
    }

    void restore(State saved) {
        state = copy(saved);
        active = null;
        previous = new Sample[RULES.size()];
        absent = 0;
        absentTick = -1;
        if (state.runs == null) state.runs = new ArrayList<>();
        state.runs.removeIf(run -> run == null || kind(run.id) < 0 || run.id.length() != 38 || run.started <= 0);
        state.seen &= (1 << RULES.size()) - 1;
        for (var run : state.runs) {
            state.seen |= 1 << kind(run.id);
            if (run.owners == null) run.owners = new HashSet<>();
            if (run.ended == 0) {
                run.ended = run.started;
                run.partial = true;
            }
        }
        if (state.claims == null) state.claims = new ArrayList<>();
        state.claims.removeIf(claim -> claim == null || kind(claim.id) < 0 || claim.epoch == null || claim.remaining == null
                || claim.chest != Integer.parseInt(RULES.get(kind(claim.id))[2]));
        for (var claim : state.claims) claim.remaining = cash(claim.remaining);
        state.clears = state.clears == null ? new long[RULES.size()] : Arrays.copyOf(state.clears, RULES.size());
        for (int i = 0; i < RULES.size(); i++) state.clears[i] = max(0, state.clears[i]);
        prune();
        changed();
    }

    State snapshot() {
        return copy(state);
    }

    static State copy(State saved) {
        if (saved == null) return new State();
        var copy = SaveCopies.shallow(saved);
        copy.runs = SaveCopies.list(saved.runs, run -> {
            var next = SaveCopies.shallow(run);
            if (next != null) next.owners = run.owners == null ? null : new HashSet<>(run.owners);
            return next;
        });
        copy.claims = SaveCopies.list(saved.claims, claim -> {
            var next = SaveCopies.shallow(claim);
            if (next != null) {
                next.epoch = claim.epoch == null ? null : claim.epoch.clone();
                next.remaining = claim.remaining == null ? null : new LinkedHashMap<>(claim.remaining);
            }
            return next;
        });
        copy.supplies = positive(saved.supplies);
        copy.clears = saved.clears == null ? null : saved.clears.clone();
        return copy;
    }

    void prune() {
        int count = 0;
        var kept = new HashSet<String>();
        var iterator = state.runs.iterator();
        while (iterator.hasNext()) {
            var run = iterator.next();
            boolean pending = state.claims.stream().anyMatch(claim -> run.id.equals(claim.id));
            if (run == active || pending || Objects.equals(run.id, state.death)
                    || ++count <= max(1, engine.config.maxHistorySessions())) kept.add(run.id);
            else {
                if (run.complete) state.clears[kind(run.id)] = sum(state.clears[kind(run.id)], 1);
                iterator.remove();
            }
        }
        for (var session : engine.sessions()) if (session.compactedRaids != null) {
            var next = new HashMap<String, long[]>();
            session.compactedRaids.forEach((id, money) -> add(next, kept.contains(id) ? id : Integer.toString(kind(id)), money));
            session.compactedRaids = next;
        }
    }

    static void add(Map<String, long[]> values, String id, long[] money) {
        if (kind(id) < 0 || money == null || money.length != 2 || money[0] < 0 || money[1] < 0) return;
        var total = values.computeIfAbsent(id, key -> new long[2]);
        for (int i = 0; i < 2; i++) total[i] = sum(total[i], money[i]);
    }

    static void compact(Session session, Transaction row) {
        for (var flow : row.flows()) if (flow != null && kind(flow.raid) >= 0) {
            if (session.compactedRaids == null) session.compactedRaids = new HashMap<>();
            var money = Accounting.flow(row, flow);
            add(session.compactedRaids, flow.raid, new long[] {money.income, money.cost});
        }
    }

    static Map<String, long[]> copyMoney(Map<String, long[]> source) {
        if (source == null) return null;
        var copy = new HashMap<String, long[]>();
        source.forEach((id, money) -> copy.put(id, money == null ? null : money.clone()));
        return copy;
    }

    /** One revision-scoped fold when Raids is viewed; a selected attempt reuses existing Ledger. */
    SavedSession read(String id) {
        if (revision == engine.revision() && Objects.equals(id, selected)) return view;
        boolean all = "all".equals(id);
        selected = id;
        totals = new HashMap<>();
        missing = new HashSet<>();
        var owners = new HashSet<String>();
        var derived = new Session("Raid receipts", 1, SessionMode.GENERAL);
        for (var session : engine.sessions()) {
            owners.add(session.id());
            if (session.compactedRaids != null) session.compactedRaids.forEach((key, money) -> {
                add(totals, key, money);
                if (key.length() > 1) add(totals, Integer.toString(kind(key)), money);
                if (all || Objects.equals(key, id)) {
                    derived.retainedRevenue = sum(derived.retainedRevenue, money[0]);
                    derived.retainedCosts = sum(derived.retainedCosts, money[1]);
                    derived.compactedTransactionCount++;
                }
            });
            for (var row : session.transactions()) {
                var flows = new ArrayList<Flow>();
                for (var flow : row.flows()) if (flow != null && kind(flow.raid) >= 0) {
                    var money = Accounting.flow(row, flow);
                    var value = new long[] {money.income, money.cost};
                    add(totals, flow.raid, value);
                    add(totals, Integer.toString(kind(flow.raid)), value);
                    if (all || Objects.equals(id, flow.raid)) flows.add(SaveCopies.copy(flow));
                }
                if (!flows.isEmpty()) {
                    var copy = SaveCopies.copy(row);
                    copy.replaceFlows(flows);
                    derived.transactions.add(copy);
                }
            }
        }
        for (var run : state.runs) if (!owners.containsAll(run.owners)) missing.add(run.id);
        view = new SavedSession("raid:" + id, derived, List.of());
        revision = engine.revision();
        return view;
    }

    long[] money(String id) {
        return totals.getOrDefault(id, new long[2]);
    }

    static Map<Integer, Long> positive(Map<Integer, Long> values) {
        return FlowFilters.quantities(values, item -> item > 0);
    }

    /** A short-lived request only annotates already-booked canonical quantities. */
    static class Request {
        Claim claim;
        State owner;
        int tick;
        long at;
        boolean bank, inventory;
        Map<Integer, Long> last, proof = new HashMap<>();
        Map<Transaction, Session> rows = new LinkedHashMap<>();
    }

    static class Native {
        final GpManagerPlugin plugin;
        final RaidHistory history;
        Request request;
        List<Request> requests = new ArrayList<>();

        Native(GpManagerPlugin plugin) {
            this.plugin = plugin;
            history = plugin.engine.raids;
        }

        boolean ready() {
            return plugin.client != null && plugin.client.isClientThread()
                    && plugin.client.getGameState() == GameState.LOGGED_IN;
        }

        boolean live(Request request) {
            if (request == null || request.owner != history.state || !history.state.claims.contains(request.claim))
                return false;
            if (!ready()) return false;
            int tick = plugin.client.getTickCount();
            return tick >= request.tick && (long) tick - request.tick <= 10;
        }

        static int number(String[] row, int column) {
            return Integer.parseInt(row[column]);
        }

        Sample sample(String[] row) {
            var client = plugin.client;
            int kind = number(row, 0), slot = client.getVarbitValue(number(row, 7)) - (kind == 2 ? 1 : 0);
            boolean inside;
            if (kind == 0) inside = client.getVarbitValue(number(row, 6)) > 0 && slot >= 0
                    && client.getVarbitValue(number(row, 8)) > 0;
            else {
                String name = client.getLocalPlayer() == null ? null : client.getLocalPlayer().getName();
                String member = slot < 0 || slot >= number(row, 10) ? null : client.getVarcStrValue(number(row, 9) + slot);
                inside = name != null && member != null && Text.standardize(name).equals(Text.standardize(member))
                        && Widgets.visible(client, number(row, 5) << 16)
                        && (kind == 3 ? client.isInInstancedRegion() && client.getVarbitValue(number(row, 12)) == 0
                                : kind == 2 ? client.isInInstancedRegion() && client.getVarbitValue(number(row, 8)) == 1
                                : client.getVarbitValue(number(row, 8)) == 2 || client.getVarbitValue(number(row, 8)) == 3);
            }
            int[] counters = Arrays.stream(row[11].split(",")).mapToInt(Integer::parseInt).map(client::getVarpValue).toArray();
            return new Sample(kind, inside, kind == 0 ? client.getVarbitValue(number(row, 12)) > 0 ? "Challenge" : "Normal"
                    : kind == 2 ? "Level " + client.getVarbitValue(number(row, 12)) : "Observed mode", counters);
        }

        void chat(String message, long now) {
            synchronized (history.engine) {
                var run = history.active;
                if (run != null && (kind(run.id) == 2 || kind(run.id) == 3) && !run.complete
                        && message.equalsIgnoreCase("You failed to survive the " + RULES.get(kind(run.id))[1].replaceFirst("^The ", "") + ".") && ready()) {
                    run.failed = run.died = true;
                    history.state.death = run.id;
                    if (kind(run.id) == 2) {
                        var held = plugin.containers.captureDeathOwned();
                        history.engine.armDeathReclaim(plugin.consumeTicks(), held == null ? null : held.items, emptyMap());
                    }
                    history.end(now);
                }
            }
        }

        void pulse(long now) {
            synchronized (history.engine) {
                if (!ready()) return;
                for (var rule : RULES) history.observe(sample(rule), now, plugin.client.getTickCount());
                requests.removeIf(value -> !live(value));
                if (!live(request)) request = null;
            }
        }

        Claim preview(String[] rule) {
            int kind = number(rule, 0), chest = number(rule, 2), flag = number(rule, 4);
            var container = plugin.client.getItemContainer(chest);
            var sample = sample(rule);
            if (container == null || !Widgets.visible(plugin.client, number(rule, 3) << 16)
                    || (flag > 0 ? plugin.client.getVarbitValue(flag) == 0 : !sample.inside)) return null;
            var claim = history.state.claims.stream().filter(item -> item.chest == chest).findFirst().orElse(null);
            if (claim != null && !Arrays.equals(claim.epoch, sample.counters)) return null;
            if (claim == null) {
                claim = new Claim();
                claim.id = history.active != null && kind(history.active.id) == kind
                        ? history.active.id : kind + ":" + uuid();
                claim.chest = chest;
                claim.epoch = sample.counters;
                history.state.claims.add(claim);
            }
            var contents = cash(plugin.counts(container));
            if (!claim.observed) claim.remaining = positive(contents);
            else {
                for (var item : claim.remaining.entrySet())
                    item.setValue(min(item.getValue(), contents.getOrDefault(item.getKey(), 0L)));
                claim.remaining.values().removeIf(qty -> qty <= 0);
            }
            claim.observed = true;
            return claim;
        }

        void menu(MenuOptionClicked event, long now) {
            synchronized (history.engine) {
                if (event.isConsumed()) return;
                if (live(request)) container(request.claim.chest, plugin.client.getItemContainer(request.claim.chest));
                requests.removeIf(value -> !live(value));
                var rule = RULES.stream().filter(row -> number(row, 3) == event.getParam1() >>> 16).findFirst().orElse(null);
                String option = lower(Text.removeTags(event.getMenuOption()), false);
                if (ready() && event.getMenuAction() != null && event.getMenuAction().name().startsWith("GAME_OBJECT_")
                        && (option.equals("take-pickaxe") || option.equals("deposit-pickaxe")) && PICKAXE_STORES.containsKey(event.getId())) {
                    var move = history.engine.events.put(Inbox.RAID_STORE, -1, 1, plugin.consumeTicks(), null);
                    move.data = history.state;
                    move.x = option.startsWith("take") ? 1 : -1;
                }
                var active = history.active;
                if (ready() && active != null && sample(RULES.get(kind(active.id))).inside
                        && (option.startsWith("take") || option.startsWith("buy"))) {
                    String action = event.getMenuAction() == null ? "" : event.getMenuAction().name();
                    for (var loan : cached("raid_supplies")) {
                        int item = number(loan, 3), stock = number(loan, 4);
                        if (number(loan, 0) != kind(active.id) || !action.startsWith(loan[1])
                                || number(loan, 2) != (action.startsWith("CC_OP") ? event.getParam1() >>> 16 : event.getId())
                                || stock > 0 && !plugin.counts(plugin.client.getItemContainer(stock)).containsKey(event.getItemId())) continue;
                        String count = option.replaceFirst("^(take|buy)-", "");
                        long n = item > 0 ? 1 : count.matches("[0-9]+") ? min(28, digits(count)) : 28;
                        item = item > 0 ? item : plugin.containers.canonical(event.getItemId());
                        if (item > 0) history.engine.events.add(Inbox.LOAN, item, n, 6, null).data = history.state;
                    }
                }
                // Only an executed reward disposition ends the current request.
                if (rule != null && ActionSignals.rule("chest-action", option))
                    request = null;
                if (!option.startsWith("take") && !option.equals("bank-all")) return;
                if (rule == null || !ready()) return;
                var claim = preview(rule);
                if (claim == null || claim.remaining.isEmpty()) return;
                boolean bank = option.equals("bank-all");
                requests.removeIf(value -> value.claim != claim);
                request = requests.stream().filter(value -> value.bank == bank).findFirst().orElse(null);
                if (request == null) {
                    request = new Request();
                    request.claim = claim;
                    request.owner = history.state;
                    request.tick = plugin.client.getTickCount();
                    request.at = now;
                    request.bank = bank;
                    requests.add(request);
                }
                request.last = positive(claim.remaining);
            }
        }

        void container(int id, ItemContainer container) {
            synchronized (history.engine) {
                if (id == net.runelite.api.gameval.InventoryID.INV)
                    for (var value : requests) if (live(value) && !value.bank) value.inventory = true;
                if (!live(request) || id != request.claim.chest || container == null) return;
                var next = cash(plugin.counts(container));
                if (next.entrySet().stream().anyMatch(item -> item.getValue() > request.last.getOrDefault(item.getKey(), 0L))) {
                    requests.removeIf(value -> value.claim == request.claim);
                    request = null;
                    return;
                }
                request.last.forEach((item, qty) -> {
                    long gone = qty - next.getOrDefault(item, 0L);
                    if (gone > 0) request.proof.merge(item, gone, SafeMath::sum);
                });
                request.last = next;
                request.claim.remaining = positive(next);
                assign(request);
            }
        }

        void booked(Transaction row, boolean bank, int chest) {
            synchronized (history.engine) {
                if (row == null || row.isTransfer() || row.original() == TransactionType.TRADE
                        || requests.stream().anyMatch(value -> value.rows.containsKey(row))) return;
                var owner = history.engine.sessionHolding(row.id());
                if (owner == null) return;
                for (var value : requests) if (live(value) && row.timestamp >= value.at && bank == value.bank
                        && (bank ? chest == value.claim.chest : value.inventory) && value.rows.size() < 32) {
                    value.rows.put(row, owner);
                    assign(value);
                    return;
                }
            }
        }

        void assign(Request request) {
            for (var entry : request.rows.entrySet()) {
                var row = entry.getKey();
                var owner = entry.getValue();
                if (owner.findTransaction(row.id()) != row) continue;
                var old = row.flows;
                var next = new ArrayList<Flow>();
                var proof = new HashMap<>(request.proof);
                boolean changed = false;
                var before = Accounting.transaction(row);
                for (var flow : old) {
                    long qty = flow == null || flow.quantity <= 0 || flow.raid != null ? 0
                            : min(flow.quantity, proof.getOrDefault(flow.item, 0L));
                    if (qty == 0) {
                        next.add(flow);
                        continue;
                    }
                    var part = qty == flow.quantity ? SaveCopies.copy(flow) : flow.part(qty);
                    part.raid = request.claim.id;
                    next.add(part);
                    if (qty < flow.quantity) next.add(flow.rest(qty));
                    proof.put(flow.item, proof.get(flow.item) - qty);
                    changed = true;
                }
                if (!changed) continue;
                row.replaceFlows(next);
                var after = Accounting.transaction(row);
                if (before.income != after.income || before.cost != after.cost || before.included != after.included) {
                    row.replaceFlows(old);
                    continue;
                }
                request.proof = proof;
                owner.transactionChanged(row);
                history.received(request.claim.id, owner, row.timestamp);
            }
        }
    }
}

/** Existing paged Ledger tables embedded in the selected raid, with read-only receipt details. */
class RaidsPage implements Shell.Page {
    SidebarPanel panel;
    JPanel body = stack(PANEL, GAP), overview = stack(PANEL, GAP), detail = stack(PANEL, GAP);
    Table history = new Table("ATTEMPTS");
    LedgerPage ledger;
    Entry entry = Entry.current();
    Integer kind;
    String selected;

    RaidsPage(SidebarPanel panel) {
        this.panel = panel;
        var actions = new LedgerController(panel) {
            Entry entry() {
                return RaidsPage.this.entry;
            }

            void entry(Entry next) {
                RaidsPage.this.entry = next;
            }
        };
        ledger = new LedgerPage(actions, panel::sprite);
        ledger.noteWidth = 112;
        ledger.raid = true;
        history.setPageSize(15);
        pad(body, 0, GAP, GAP, GAP);
        body.add(overview);
        body.add(history);
        body.add(detail);
    }

    public String id() {
        return RAIDS;
    }

    public JComponent pageBar() {
        return null;
    }

    public JComponent body() {
        return body;
    }

    void open(Integer kind, String id) {
        this.kind = kind;
        selected = id;
        entry = Entry.current().withScope(LedgerData.Scope.HISTORY, "raid:" + id, "Raid receipts");
        ledger.resetOwner();
        history.resetPage();
        panel.refresh();
    }

    static long net(long[] money) {
        return subtract(money[0], money[1]);
    }

    /** Net, Loot and Death fees as one card, each in its tone; hovering Net shows it in full. */
    static KeyValue card(String title, long[] money) {
        long net = net(money);
        return new KeyValue(title).put("Net total", signed(net), sign(net), exactSigned(net) + " gp")
                .put("Loot", signed(money[0]), sign(money[0])).put("Death fees", signed(-money[1]), sign(-money[1]));
    }

    /** One raid or attempt row: Net on the right in its tone, the exact amount on hover. */
    static Table.Row row(String id, String name, String detail, long[] money, String tip, Runnable open) {
        long net = net(money);
        return new Table.Row(id, null, name, detail, signed(net), sign(net), tip, exactSigned(net) + " gp", false,
                open, List.of());
    }

    void apply(RaidHistory.State state, Map<String, long[]> totals, LedgerData data, long now) {
        overview.removeAll();
        detail.removeAll();
        history.setVisible(selected == null);
        var rows = new ArrayList<Table.Row>();
        if (kind != null || selected != null) overview.add(button(selected == null || kind == null ? "‹ Raids"
                : "‹ Attempts", false, () -> open(selected == null ? null : kind, null)));
        if (kind == null) {
            long[] all = new long[2];
            totals.forEach((id, money) -> {
                if (id.length() == 1) for (int i = 0; i < 2; i++) all[i] = sum(all[i], money[i]);
            });
            overview.add(card("ALL RAIDS · ALL TIME", all));
            if (selected == null) overview.add(button("View all receipts", false, () -> open(null, "all")));
        }
        for (int i = 0; i < RaidHistory.RULES.size(); i++) if ((state.seen & 1 << i) != 0
                && (kind == null && selected == null || kind != null && kind == i)) {
            final int raid = i;
            long[] money = totals.getOrDefault(Integer.toString(i), new long[2]);
            long clears = state.clears[i] + state.runs.stream()
                    .filter(run -> RaidHistory.kind(run.id) == raid && run.complete).count();
            String name = RaidHistory.RULES.get(i)[1], count = "Clears " + clears;
            if (kind == null) rows.add(row(Integer.toString(i), name, "", money, count, () -> open(raid, null)));
            else if (selected == null) overview.add(note(name + " · " + count + " · Net " + signed(net(money)), TEXT, 128));
        }
        for (var run : state.runs) if (kind != null && RaidHistory.kind(run.id) == kind) {
            long[] money = totals.getOrDefault(run.id, new long[2]);
            if (!run.complete && !run.failed && money[1] == 0) continue;
            long took = max(0, (run.ended == 0 ? now : run.ended) - run.started);
            String status = run.claimed ? "Loot received" : run.complete ? "Not claimed"
                    : money[1] == 0 ? "Failed" : "Death Fee";
            String caption = status + (run.partial ? " · Partial capture" : "");
            rows.add(row(run.id, status, durationShort(took), money, caption, () -> open(kind, run.id)));
            if (Objects.equals(run.id, selected)) {
                String title = RaidHistory.RULES.get(kind)[1];
                detail.add(note(title + " · " + run.mode, TEXT, 128));
                detail.add(note(caption, LABEL, 128));
                detail.add(card(null, money));
                Object owner = panel.ownerToken;
                detail.add(button("Copy summary", false, () -> {
                    if (owner != panel.ownerToken || panel.suspended) return;
                    panel.actions.shareSummary(() -> GrindsController.summary(title + " · " + caption, net(money),
                            money[0], money[1], 0, money[1], true, took));
                }));
            }
        }
        if (selected != null && data != null) {
            ledger.apply(data);
            detail.add(ledger.body());
        }
        history.rowTitle(kind == null ? "RAIDS" : "ATTEMPTS", rows);
        detail.setVisible(selected != null);
        body.revalidate();
        body.repaint();
    }
}
