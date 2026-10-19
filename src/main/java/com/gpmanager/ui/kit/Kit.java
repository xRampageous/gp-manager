package com.gpmanager;

import static javax.swing.BorderFactory.*;
import static net.runelite.client.ui.ColorScheme.*;
import static javax.swing.SwingUtilities.*;
import static java.lang.String.*;
import static java.awt.Cursor.*;
import static javax.swing.KeyStroke.*;

import javax.swing.border.Border;
import java.math.BigDecimal;

import static com.gpmanager.GameData.msg;
import static com.gpmanager.Kit.*;
import static com.gpmanager.Phrases.*;
import static java.lang.Math.*;
import static java.util.Collections.*;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.*;
import javax.swing.Timer;
import lombok.*;
import net.runelite.client.plugins.config.ConfigPlugin;
import net.runelite.client.ui.*;
import net.runelite.client.ui.components.materialtabs.*;
import net.runelite.client.util.ImageUtil;
import static com.gpmanager.SafeMath.nonNeg;
import static java.util.Locale.*;
import java.text.NumberFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

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
        try (java.io.InputStream in = Kit.class.getResourceAsStream(resource)) {
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
        holder.add(control, BorderLayout.CENTER);
        holder.add(row(actions), BorderLayout.EAST);
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
     * The text shortened with "\u2026" to fit a pixel budget; the full text belongs in the tooltip.
     */
    static String fit(JComponent label, String text, int budget) {
        if (text == null) return "";
        var metrics = label.getFontMetrics(label.getFont());
        if (budget <= 0 || metrics.stringWidth(text) <= budget) return text;
        int ellipsis = metrics.stringWidth("\u2026"), low = 0, high = text.length();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (metrics.stringWidth(text.substring(0, middle)) <= budget - ellipsis) low = middle;
            else high = middle - 1;
        }
        return text.substring(0, low) + "\u2026";
    }

    /**
     * Fits text by shrinking its font, down to 10 pt, before cutting it with an ellipsis, so a
     * long name stays readable (owner 1.5.2); the caller keeps the full text in a tooltip.
     */
    static String fitShrinking(JLabel label, String text, Font font, int budget) {
        Font size = font;
        while (budget > 0 && size.getSize2D() > 10f
                && label.getFontMetrics(size).stringWidth(orEmpty(text)) > budget)
            size = size.deriveFont(size.getSize2D() - 0.5f);
        label.setFont(size);
        return fit(label, text, budget);
    }

    static JLabel label(String text, Font font, Color color) {
        var label = new JLabel(orEmpty(text));
        // Symbols the RuneScape font cannot draw fall back to the platform face at the same size.
        label.setFont(label.getText().isEmpty() || font.canDisplayUpTo(label.getText()) < 0 ? font
                        : new Font(Font.DIALOG, font.getStyle(), font.getSize()));
        label.setForeground(color);
        return label;
    }

    static JLabel column(String text, Font font, Color color, int width, int height) {
        var label = label(text, font, color);
        label.setHorizontalAlignment(RIGHT);
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
        String safe = (orEmpty(text)).replace("&", "&amp;").replace("<", "&lt;");
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
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, scale < 1d ? RenderingHints.VALUE_INTERPOLATION_BILINEAR
                        : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
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
            add(header, BorderLayout.NORTH);
        }
        add(body, BorderLayout.CENTER);
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
        line.add(label(key, small(), LABEL), BorderLayout.WEST);
        char first = value.isEmpty() ? ' ' : value.charAt(0);
        boolean numeral = Character.isDigit(first) || first == '\u2212' || first == '-' || first == '+';
        var shown = label(value, numeral ? numbers(11.5f) : body(), (tone == null ? Tone.PLAIN : tone).color);
        shown.setHorizontalAlignment(RIGHT);
        shown.setToolTipText(exact == null ? value : exact);
        line.add(shown, BorderLayout.CENTER);
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

        /** Relative box width in a strip row; 1 keeps the even grid every other strip uses. */
        int weight = 1;

        Cell(String label, String value, Tone tone, String tip, Runnable open) {
            this.label = orEmpty(label);
            this.value = orEmpty(value);
            this.tone = tone == null ? Tone.PLAIN : tone;
            this.tip = orEmpty(tip);
            this.open = open;
        }

        Cell weight(int value) {
            weight = value;
            return this;
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
    List<List<Cell>> drawnStrips = emptyList();
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
        left.add(dot, BorderLayout.WEST);
        left.add(name, BorderLayout.CENTER);
        status.add(left, BorderLayout.CENTER);
        word.setFont(small().deriveFont(10f));
        word.setBorder(createCompoundBorder(createLineBorder(LINE),
                        createEmptyBorder(2, 3, 2, 3)));
        statusRight.add(icons);
        statusRight.add(word);
        status.add(statusRight, BorderLayout.EAST);
        clickable(dot, "Pause or resume tracking", () -> {
                    if (onDot != null) onDot.run();
                });
        pad(big, 0, 6, 4, 6);
        big.add(net, BorderLayout.CENTER);
        var pace = new JPanel();
        pace.setLayout(new BoxLayout(pace, BoxLayout.Y_AXIS));
        pace.setOpaque(false);
        rate.setAlignmentX(RIGHT_ALIGNMENT);
        clock.setAlignmentX(RIGHT_ALIGNMENT);
        pace.add(rate);
        pace.add(clock);
        big.add(pace, BorderLayout.EAST);
        var top = transparent(new BorderLayout());
        top.add(status, BorderLayout.NORTH);
        top.add(big, BorderLayout.CENTER);
        pad(under, 0, 0, 4, 0);
        under.setVisible(false);
        top.add(under, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        add(strips, BorderLayout.CENTER);
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
        String state = orEmpty(statusWord);
        word.setForeground(statusColor(state));
        word.setText(state.isEmpty() ? "" : state.substring(0, 1) + state.substring(1).toLowerCase(ROOT));
        word.setVisible(!word.getText().isEmpty());
        String text = orEmpty(title);
        name.setText(Kit.fitShrinking(name, text, bold(), nameBudget()));
        name.setToolTipText(text.isEmpty() ? null : text);
        return this;
    }

    static Color statusColor(String state) {
        switch (state) {
            case "AWAY": return ACCENT;
            case "PAUSED": return Tone.REVIEW.color;
            case "LOGGED OUT": return Tone.LOSS.color;
            case "RECONNECTING": return Tone.MARKET.color;
            case "CALIBRATING":
            case "COMPLETE": return Tone.GAIN.color;
            default: return state.startsWith("RESUMING") ? Tone.GAIN.color : LABEL;
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
        if (next.equals(drawnStrips)) return this;
        drawnStrips = new ArrayList<>(next);
        var previous = focused(strips);
        strips.removeAll();
        for (var strip : next) strips.add(strip(strip));
        strips.revalidate();
        restoreFocus(strips, previous);
        return this;
    }

    /** One row of labelled cells, as the hero and the Grinds All time card show them. */
    /**
     * The money strip Live and a Grind's detail share: Gains, then Supply and Loss when the split
     * is known, else the truthful Costs total, never invented parts.
     */
    static List<Cell> money(long income, long supplies, long lost, long cost, boolean split, Runnable open) {
        var cells = new ArrayList<Cell>();
        cells.add(new Cell("GAINS", Fmt.signed(income), Tone.GAIN, msg("loot-other") + Fmt.exact(income) + " gp",
                        open));
        if (split) {
            cells.add(new Cell("SUPPLY", Fmt.signed(-supplies), Tone.SUPPLY,
                            msg("food-potions") + Fmt.exact(supplies) + " gp"
                                    + (income > 0 && supplies >= 0 ? format(ROOT,
                                            " · %.1f%% of booked Gains", 100d * supplies / income) : ""), open));
            cells.add(new Cell("LOSS", Fmt.signed(-lost), Tone.LOSS,
                            msg("deaths-tax") + Fmt.exact(lost) + " gp", open));
        } else {
            cells.add(new Cell("COSTS", Fmt.signed(-cost), Tone.LOSS, msg("everything-spent"), open));
        }
        return cells;
    }

    static JPanel strip(List<Cell> cells) {
        boolean weighted = false;
        for (Cell cell : cells) weighted |= cell.weight != 1;
        var row = weighted ? new JPanel(new GridBagLayout())
                        : new JPanel(new GridLayout(1, max(1, cells.size()), 1, 0));
        row.setBackground(LINE);
        row.setBorder(createMatteBorder(1, 0, 0, 0, LINE));
        for (int i = 0; i < cells.size(); i++) {
            var box = box(cells.get(i));
            if (weighted) {
                var constraints = new GridBagConstraints();
                constraints.gridx = i;
                constraints.weightx = cells.get(i).weight;
                constraints.fill = GridBagConstraints.BOTH;
                constraints.insets = new Insets(0, i == 0 ? 0 : 1, 0, 0);
                row.add(box, constraints);
            } else {
                row.add(box);
            }
        }
        return row;
    }

    /** One strip cell: the label over its value, on the card tone. */
    static JPanel box(Cell cell) {
        var box = new JPanel(new BorderLayout());
        box.setBackground(CARD);
        pad(box, 2, 5, 3, 5);
        box.add(label(cell.label, small(), LABEL), BorderLayout.NORTH);
        box.add(label(cell.value, numbers(11f), cell.tone.color), BorderLayout.CENTER);
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
            this.key = orEmpty(key);
            this.icon = icon;
            this.name = orEmpty(name);
            this.qty = orEmpty(qty);
            this.value = orEmpty(value);
            this.tone = tone == null ? Tone.DIM : tone;
            this.tip = orEmpty(tip);
            this.exact = orEmpty(exact);
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
    JLabel chevron = label("▾", small(), LABEL), title = label("", small(), LABEL);
    JLabel total = label("", numbers(10.5f), LABEL);
    JButton previous = glyphButton("‹"), next = glyphButton("›");
    JLabel pageLabel = label("", numbers(10f), LABEL);

    /** One row, centred on the title's line so "‹ 1/2 ›" sits level with it. */
    JPanel right = transparent(new GridBagLayout());

    JPanel body = stack(CARD, 0);

    /** A small control beside the title, such as the Losses view picker (owner 1.1). */
    JComponent picker;

    JPanel left = transparent(new BorderLayout(4, 0));
    JComponent action;
    List<Row> rows = emptyList(), drawn = null;
    String emptyText = "Nothing booked yet";
    int rowsPerPage = 15, minRows, page;
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
        left.add(chevron, BorderLayout.WEST);
        left.add(this.title, BorderLayout.CENTER);
        header.add(left, BorderLayout.CENTER);
        header.add(right, BorderLayout.EAST);
        chevron.setVisible(false);
        chevron.setCursor(getPredefinedCursor(HAND_CURSOR));
        keyboard(chevron, "Fold table", () -> setFolded(!folded));
        previous.getAccessibleContext().setAccessibleName("Previous page");
        next.getAccessibleContext().setAccessibleName("Next page");
        chevron.addMouseListener(new MouseAdapter() {
                    public void mouseClicked(MouseEvent e) {
                        setFolded(!folded);
                    }
                });
        previous.addActionListener(e -> showPage(page - 1));
        next.addActionListener(e -> showPage(page + 1));
        add(header, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
    }

    Table setPicker(JComponent component) {
        if (picker != null) left.remove(picker);
        picker = component;
        if (component != null) left.add(component, BorderLayout.EAST);
        left.revalidate();
        return this;
    }

    Table setTitle(String text) {
        title.setText(text);
        return this;
    }

    Table setTotal(String text, Tone tone) {
        total.setText(orEmpty(text));
        total.setForeground(tone == null ? LABEL : tone.color);
        rebuildHeader();
        return this;
    }

    Table setFoldable(boolean value) {
        foldable = value;
        chevron.setVisible(value);
        rebuildHeader();
        return this;
    }

    Table setFolded(boolean value) {
        folded = foldable && value;
        chevron.setText(folded ? "▸" : "▾");
        body.setVisible(!folded);
        rebuildHeader();
        revalidate();
        return this;
    }

    /** Recent is a collapsible plain list: no pager, at most thirty already-filtered rows. */
    Table plain() {
        plain = true;
        rowsPerPage = 30;
        header.setBorder(createEmptyBorder(0, 6, 0, 2));
        return setFoldable(true);
    }

    /** Category and Ledger pages keep a bounded page size, never below one or above fifteen. */
    Table setRowsPerPage(int value) {
        rowsPerPage = max(1, min(15, value));
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
        if (per != rowsPerPage) {
            rowsPerPage = per;
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

    Table setEmptyText(String text) {
        emptyText = text;
        drawn = null;
        return this;
    }

    /** A small control on the right of the header, such as "Decide…". It replaces the pager. */
    Table setAction(JComponent component) {
        action = component;
        rebuildHeader();
        return this;
    }

    Table setRows(List<Row> value) {
        rows = value == null ? emptyList() : new ArrayList<>(value);
        showPage(page);
        fitLater();
        return this;
    }

    /** A counted page table title; empty lists keep the original bare title. */
    Table countedTitle(String title, List<Row> list) {
        return setTitle(list.isEmpty() ? title : title + " · " + list.size()).setRows(list);
    }

    void resetPage() {
        showPage(0);
    }

    int pages() {
        return pages(rows.size(), rowsPerPage);
    }

    static int pages(int count, int perPage) {
        return Kit.pageCount(count, perPage);
    }

    void showPage(int requested) {
        page = Kit.clampPage(requested, pages());
        int from = page * rowsPerPage;
        var visible = rows.subList(min(from, rows.size()), min(from + rowsPerPage, rows.size()));
        if (!visible.equals(drawn)) {
            var previous = focused(body);
            drawn = new ArrayList<>(visible);
            body.removeAll();
            if (visible.isEmpty()) {
                body.add(Kit.emptyLine(emptyText));
            }
            int rowHeight = rowHeight();
            // The quantity column hugs each row's own text, so one wide "×2 doses" row no longer
            // steals width from every name; right edges stay aligned against the value column.
            for (Row row : visible) body.add(rowComponent(row, rowHeight));
            int height = visible.isEmpty() ? max(1, minRows) * ROW : max(minRows, visible.size()) * rowHeight;
            body.setPreferredSize(new Dimension(0, height));
            restoreFocus(body, previous);
        }
        rebuildHeader();
        revalidate();
        repaint();
    }

    void rebuildHeader() {
        var previousFocus = focused(right);
        chevron.getAccessibleContext().setAccessibleName((folded ? "Expand " : "Collapse ") + title.getText());
        var parts = new ArrayList<Component>();
        if (!total.getText().isEmpty()) parts.add(total);
        if (action != null) parts.add(action);
        else if (!plain && !folded && !rows.isEmpty()) {
            Kit.applyPager(pageLabel, previous, next, page, pages(), (page + 1) + "/" + pages());
            addAll(parts, previous, pageLabel, next);
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
            icon.setHorizontalAlignment(CENTER);
            icon.setPreferredSize(new Dimension(ICON_WIDTH, ICON_ROW));
            line.add(icon, BorderLayout.WEST);
        }
        line.add(label(row.name, body().deriveFont(11.5f), row.color == null ? TEXT : row.color), BorderLayout.CENTER);
        var numbers = transparent(new BorderLayout(row.qty.isEmpty() ? 0 : 4, 0));
        if (!row.qty.isEmpty()) {
            int qty = min(70, line.getFontMetrics(numbers(10.5f)).stringWidth(row.qty) + 2);
            numbers.add(column(row.qty, numbers(10.5f), LABEL, qty, height), BorderLayout.WEST);
        }
        var value = column(row.value, numbers(10.5f), row.tone.color, VALUE_WIDTH, height);
        value.setPreferredSize(new Dimension(max(VALUE_WIDTH,
                        value.getFontMetrics(value.getFont()).stringWidth(row.value) + 2), height));
        if (!row.exact.isEmpty()) value.setToolTipText(row.exact);
        numbers.add(value, BorderLayout.EAST);
        line.add(numbers, BorderLayout.EAST);
        if (!row.tip.isEmpty()) line.setToolTipText(row.tip);
        if (row.open != null) {
            Hero.clickable(line, "Open " + row.name, row.open);
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
            line.setComponentPopupMenu(Kit.menuOf(row.menu));
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

    static final String LIVE = "live", LEDGER = "ledger", GRINDS = "grinds";
    static final String[][] TABS = {{LIVE, "Live"}, {LEDGER, "Ledger"}, {GRINDS, "Grinds"}};
    static final Border SELECTED = createCompoundBorder(
                    createMatteBorder(0, 0, 1, 0, ACCENT), createEmptyBorder(5, 2, 4, 2));
    static final Border UNSELECTED = createEmptyBorder(5, 2, 5, 2);
    MaterialTabGroup tabs = new MaterialTabGroup();
    Map<String, MaterialTab> tabById = new LinkedHashMap<>();
    JPanel bar = transparent(new BorderLayout()), sheet = stack(CARD, GAP);

    /**
     * Holds the sheet with its margin; hidden with it, so no gap sits under the toolbar (owner
     * 1.1).
     */
    JPanel sheetHolder = transparent(new BorderLayout());

    String sheetTitle = "";
    CardLayout cards = new CardLayout();
    JPanel bodies = transparent(cards);
    Map<String, Page> pages = new LinkedHashMap<>();
    Map<String, JScrollPane> scrolls = new LinkedHashMap<>();
    Map<String, Integer> scrollMemory = new LinkedHashMap<>();
    JPanel notice = new JPanel(new BorderLayout(GAP, 0));
    JLabel noticeText = label("", small(), TEXT);
    JPanel noticeAction = transparent(new BorderLayout());
    Timer noticeTimer = new Timer(4_000, e -> {
        if (focused(notice) == null) dismiss();
        else ((Timer) e.getSource()).restart();
    });

    /** A data-status failure stays until clearSticky; it ignores the timer and text clicks. */
    boolean noticeSticky;

    String current = LIVE;

    /** True while show() moves the tab strip, whose select callback would call show() again. */
    boolean selecting;

    Shell() {
        super(new BorderLayout());
        setBackground(PANEL);
        for (String[] tab : TABS) {
            var material = new MaterialTab(tab[1], tabs, null) {
                        public void setBorder(Border border) {
                            // Stock tabs pad 10 px a side; four need narrower sides at sidebar
                            // width.
                            super.setBorder(border instanceof javax.swing.border.CompoundBorder ? SELECTED : UNSELECTED);
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
            material.setHorizontalAlignment(CENTER);
            material.setFont(body());
            tabById.put(tab[0], material);
            tabs.addTab(material);
        }
        // Four equal cells: the stock centred flow wraps the fourth tab at sidebar width.
        tabs.setLayout(new GridLayout(1, TABS.length));
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
        strip.add(tabs, BorderLayout.CENTER);
        strip.add(gear, BorderLayout.EAST);
        top.add(strip, BorderLayout.NORTH);
        top.add(bar, BorderLayout.CENTER);
        sheet.setVisible(false);
        sheetHolder.setVisible(false);
        pad(sheetHolder, GAP, GAP, 0, GAP);
        sheetHolder.add(sheet, BorderLayout.CENTER);
        top.add(sheetHolder, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        add(bodies, BorderLayout.CENTER);
        notice.setBackground(CARD);
        notice.setVisible(false);
        noticeText.setCursor(getPredefinedCursor(HAND_CURSOR));
        keyboard(noticeText, "Dismiss message", this::noticeClicked);
        sheet.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(getKeyStroke("ESCAPE"), "close");
        sheet.getActionMap().put("close", new AbstractAction() {
            public void actionPerformed(ActionEvent event) { closeSheet(); }
        });
        noticeText.addMouseListener(new MouseAdapter() {
                    public void mouseClicked(MouseEvent e) {
                        noticeClicked();
                    }
                });
        notice.add(noticeText, BorderLayout.CENTER);
        notice.add(noticeAction, BorderLayout.EAST);
        noticeTimer.setRepeats(false);
        add(notice, BorderLayout.SOUTH);
    }

    void addPage(Page page) {
        pages.put(page.id(), page);
        JPanel view = new Column();
        view.setOpaque(false);
        pad(view, GAP, 0, 0, GAP);
        view.add(page.body(), BorderLayout.NORTH);
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
        if (page == null) return;
        var leaving = scrolls.get(current);
        if (leaving != null) scrollMemory.put(current, leaving.getVerticalScrollBar().getValue());
        var previous = leaving == null ? null : focused(leaving);
        current = id;
        closeSheet(false);
        var tab = tabById.get(id);
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
        if (pageBar != null) bar.add(pageBar, BorderLayout.CENTER);
        bar.setVisible(pageBar != null);
        bar.revalidate();
        cards.show(bodies, id);
        page.onShown();
        onShow.run();
        if (previous != null) restoreFocus(scrolls.get(id), previous);
        Integer remembered = scrollMemory.get(id);
        var entering = scrolls.get(id);
        if (remembered != null && entering != null) {
            invokeLater(() -> entering.getVerticalScrollBar().setValue(remembered));
        }
        repaint();
    }

    String currentPage() {
        return current;
    }

    /** A plain notification from any thread; it shows on the Swing thread. */
    void tell(String title, String detail, boolean warning) {
        if (isEventDispatchThread()) {
            notify(title, detail, warning, null, null);
        } else {
            invokeLater(() -> notify(title, detail, warning, null, null));
        }
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
        if (noticeSticky && notice.isVisible() && tip.equals(noticeText.getToolTipText())) return;
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
        Color color = warning ? Tone.REVIEW.color : Tone.GAIN.color;
        notice.setBorder(createCompoundBorder(createMatteBorder(1, 3, 0, 0, color),
                        createEmptyBorder(4, GAP, 4, GAP)));
        String safe = (title + (empty(detail) ? "" : " · " + detail)).replace("&", "&amp;")
                        .replace("<", "&lt;");
        noticeText.setText(msg("div-style") + (action == null ? 190 : 130) + "px'>" + safe + "</div></html>");
        noticeText.setToolTipText(title + (detail == null ? "" : " · " + detail));
        noticeAction.removeAll();
        if (action != null && onAction != null) {
            JButton button = button(action, true, () -> {
                                dismiss();
                                onAction.run();
                            });
            noticeAction.add(button, BorderLayout.CENTER);
        }
        notice.setVisible(true);
        if (sticky) {
            noticeTimer.stop();
        } else {
            noticeTimer.setInitialDelay(action == null ? 4_000 : 8_000);
            noticeTimer.restart();
        }
        revalidate();
        repaint();
    }

    /**
     * An in-sidebar form under the toolbar (SIDEBAR_SPEC.md §4), one at a time: a title, an
     * optional message and content, then the choices and Cancel. A choice closes the sheet first,
     * then runs; switching pages closes it.
     */
    JComponent sheetReturnFocus;

    void sheet(String title, String message, JComponent content, Choice... choices) {
        if (!sheet.isVisible()) sheetReturnFocus = focused(this);
        sheet.removeAll();
        sheetTitle = title;
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
        for (var choice : choices) {
            buttons.add(button(choice.label, choice.primary, () -> {
                                closeSheet();
                                choice.action.run();
                            }));
        }
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
        var field = new JTextField(orEmpty(initial));
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
            sheetTitle = "";
            if (restore && sheetReturnFocus != null && sheetReturnFocus.isShowing()) sheetReturnFocus.requestFocusInWindow();
            sheetReturnFocus = null;
            revalidate();
            repaint();
        }
    }

    void dismiss() {
        noticeTimer.stop();
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
            multiplier = suffix == 'k' ? 1_000 : suffix == 'm' ? 1_000_000L : 1_000_000_000L;
            text = text.substring(0, text.length() - 1).trim();
        }
        try {
            long value = round(Double.parseDouble(text) * multiplier);
            return nonNeg(value);
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
        if (abs < 1_000) return Long.toString(abs);
        // Cut off, never rounded up, like the game's own stack numbers: 999,600 is 999k (owner
        // 1.3.1).
        if (abs < 100_000) return format(ROOT, "%.1fk", abs / 100 / 10d);
        if (abs < 1_000_000) return abs / 1_000 + "k";
        if (abs < 10_000_000) return format(ROOT, "%.2fM", abs / 10_000 / 100d);
        if (abs < 100_000_000) return format(ROOT, "%.1fM", abs / 100_000 / 10d);
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
        return perHour < 0 ? "\u2212" + compact(perHour) : compact(perHour);
    }

    /** {@code h:mm:ss} for the session clock; {@code m:ss} under an hour. */
    static String clock(long ms) {
        long total = nonNeg(ms) / 1000, h = total / 3600, m = (total % 3600) / 60, s = total % 60;
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
        return nonNeg(ms) < 60_000 ? nonNeg(ms) / 1000 + "s" : durationShort(ms);
    }

    static String duration(long ms, boolean seconds) {
        long total = nonNeg(ms) / 1000, d = total / 86_400, h = (total % 86_400) / 3600, m = (total % 3600) / 60;
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
        long minutes = nonNeg(ms) / 60_000;
        if (minutes < 60) return minutes + "m";
        return minutes / 60 + "h" + (minutes % 60 > 0 ? " " + minutes % 60 + "m" : "");
    }

    /** A Net target the field reads back exactly: {@code 5m}, {@code 9.12m}, {@code 750k}. */
    static String targetNet(long gp) {
        // "1b", not "1000m", so the 1b pick lights up (owner 1.5.2 milestone).
        if (gp >= 1_000_000_000 && gp % 10_000_000 == 0)
            return BigDecimal.valueOf(gp, 9).stripTrailingZeros().toPlainString() + "b";
        if (gp >= 1_000_000 && gp % 10_000 == 0)
            return BigDecimal.valueOf(gp, 6).stripTrailingZeros().toPlainString() + "m";
        return gp < 1_000_000 && gp % 1_000 == 0 ? gp / 1_000 + "k" : exact(gp);
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

    /**
     * Day, clock and seconds: same-minute restore points stay distinguishable (owner 2026-10-01,
     * F22).
     */

    /** Relative age: {@code 3m ago}, {@code 2h ago}, {@code just now}. */
    static String age(long ageMillis) {
        long s = nonNeg(ageMillis) / 1000;
        if (s < 45) return "just now";
        if (s < 3600) return (s / 60) + "m ago";
        if (s < 86_400) return (s / 3600) + "h ago";
        return (s / 86_400) + "d ago";
    }
}
