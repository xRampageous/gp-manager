package com.gpmanager;
import static com.gpmanager.GameData.msg;
import java.awt.*;
import javax.swing.*;
import net.runelite.client.ui.*;
/**
* Palette, fonts and small builders shared by the sidebar and HUD+.
* Colour always means a category, never a sign.
*/
class Kit {
static final Color PANEL = ColorScheme.DARK_GRAY_COLOR;
static final Color CARD = ColorScheme.DARKER_GRAY_COLOR;
static final Color LINE = ColorScheme.BORDER_COLOR;
static final Color SELECTED = ColorScheme.DARKER_GRAY_HOVER_COLOR;
static final Color TEXT = ColorScheme.TEXT_COLOR;
static final Color LABEL = ColorScheme.LIGHT_GRAY_COLOR;
static final Color OFF = ColorScheme.MEDIUM_GRAY_COLOR;
static final Color ACCENT = ColorScheme.BRAND_ORANGE;
static final int ROW = 20;
/** Rows that carry an item icon are as tall as RuneLite's 36 x 32 item image, so it never spills. */
static final int ICON_ROW = 32;
static final int ICON_WIDTH = 36;
static final int HEADER = 22;
static final int GAP = 6;
static final int VALUE_WIDTH = 46;
/** What a value is, which decides its colour. */
enum Tone {
 GAIN(ColorScheme.GRAND_EXCHANGE_PRICE), SUPPLY(ColorScheme.GRAND_EXCHANGE_ALCH), LOSS(new Color(255, 122, 107)),
 MARKET(new Color(90, 180, 255)), REVIEW(ColorScheme.PROGRESS_INPROGRESS_COLOR), PLAIN(Color.WHITE),
 DIM(ColorScheme.LIGHT_GRAY_COLOR);
 final Color color;
 Tone(Color color) {
  this.color = color;
 }
 /** Gain above zero, loss below it. */
 static Tone sign(long value) {
  return value > 0L ? GAIN : value < 0L ? LOSS : PLAIN;
 }
 /** A booked category's tone; fees read as losses, audit-only rows as dim. */
 static Tone of(Contribution.Category category) {
  if (category == null) return DIM;
  switch (category) {
   case GAIN: return GAIN;
   case SUPPLY: return SUPPLY;
   case MARKET: return MARKET;
   case LOSS:
   case FEE: return LOSS;
   default: return DIM;
  }
 }
}

static JMenuItem item(String text, Runnable action) {
 return item(text, true, action);
}

static JMenuItem item(String text, boolean enabled, Runnable action) {
 JMenuItem item = new JMenuItem(text);
 item.setEnabled(enabled);
 item.addActionListener(e -> action.run());
 return item;
}

/** A left-aligned transparent row of controls. */
static JPanel row(Component... components) {
 var row = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
 row.setOpaque(false);
 for (Component component : components) row.add(component);
 return row;
}

static Font small() {
 return FontManager.getRunescapeSmallFont();
}

static Font body() {
 return FontManager.getRunescapeFont();
}

static Font bold() {
 return FontManager.getRunescapeBoldFont();
}

/** One page toolbar band: controls left, actions right; Ledger and Grinds share the same shape. */
static JPanel toolbar(JComponent[] controls, JComponent[] actions) {
 var row = new JPanel(new BorderLayout(4, 0));
 row.setOpaque(false);
 var left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
 left.setOpaque(false);
 for (JComponent control : controls) left.add(control);
 row.add(left, BorderLayout.CENTER);
 var right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 2));
 right.setOpaque(false);
 for (JComponent action : actions) right.add(action);
 row.add(right, BorderLayout.EAST);
 return row;
}

/** The text shortened with "\u2026" to fit a pixel budget; the full text belongs in the tooltip. */
static String fit(JLabel label, String text, int budget) {
 if (text == null) return "";
 FontMetrics metrics = label.getFontMetrics(label.getFont());
 if (budget <= 0 || metrics.stringWidth(text) <= budget) return text;
 int ellipsis = metrics.stringWidth("\u2026");
 int low = 0;
 int high = text.length();
 while (low < high) {
  int middle = (low + high + 1) >>> 1;
  if (metrics.stringWidth(text.substring(0, middle)) <= budget - ellipsis) low = middle;
  else high = middle - 1;
 }
 return text.substring(0, low) + "\u2026";
}

static JLabel label(String text, Font font, Color color) {
 var label = new JLabel(ModelText.orEmpty(text));
 // Symbols the RuneScape font cannot draw fall back to the platform face at the same size.
 label.setFont(label.getText().isEmpty() || font.canDisplayUpTo(label.getText()) < 0 ? font
 : new Font(Font.DIALOG, font.getStyle(), font.getSize()));
 label.setForeground(color);
 return label;
}

/** A label fixed to {@code width}, right-aligned, for columns that must line up. */
static JLabel column(String text, Font font, Color color, int width) {
 return column(text, font, color, width, ROW);
}

static JLabel column(String text, Font font, Color color, int width, int height) {
 JLabel label = label(text, font, color);
 label.setHorizontalAlignment(JLabel.RIGHT);
 var size = new Dimension(width, height);
 label.setPreferredSize(size);
 label.setMinimumSize(size);
 label.setMaximumSize(size);
 return label;
}

/** Full-width children at their preferred heights, {@code gap} apart; hidden ones take no room. */
static JPanel stack(Color background, int gap) {
 var panel = new JPanel(new LayoutManager() {
  public void addLayoutComponent(String name, Component comp) {
  }
  public void removeLayoutComponent(Component comp) {
  }
  public Dimension preferredLayoutSize(Container parent) {
   Insets in = parent.getInsets();
   int width = 0;
   int height = 0;
   for (Component child : parent.getComponents()) {
    if (child.isVisible()) {
     Dimension size = child.getPreferredSize();
     width = Math.max(width, size.width);
     height += (height == 0 ? 0 : gap) + size.height;
    }
   }
   return new Dimension(width + in.left + in.right, height + in.top + in.bottom);
  }
  public Dimension minimumLayoutSize(Container parent) {
   return preferredLayoutSize(parent);
  }
  public void layoutContainer(Container parent) {
   Insets in = parent.getInsets();
   int y = in.top;
   int width = parent.getWidth() - in.left - in.right;
   for (Component child : parent.getComponents()) {
    if (child.isVisible()) {
     int height = child.getPreferredSize().height;
     child.setBounds(in.left, y, width, height);
     y += height + gap;
    }
   }
  }
 });
 panel.setBackground(background);
 return panel;
}

/** A dim note that wraps to the sidebar width. */
static JLabel note(String text, Color color) {
 String safe = (ModelText.orEmpty(text)).replace("&", "&amp;").replace("<", "&lt;");
 JLabel label = label(msg("bf") + safe + "</div></html>", small(), color);
 pad(label, 0, GAP, 0, GAP);
 return label;
}

/** A flat text button; {@code primary} draws the brand-orange border. */
static JButton button(String text, boolean primary, Runnable action) {
 JButton button = new JButton(text);
 button.setFont(small());
 button.setForeground(TEXT);
 button.setBackground(CARD);
 button.setFocusPainted(false);
 button.setBorder(BorderFactory.createCompoundBorder(
 BorderFactory.createLineBorder(primary ? ACCENT : LINE), BorderFactory.createEmptyBorder(3, 8, 3, 8)));
 button.addActionListener(e -> action.run());
 return button;
}

/** Padding of {@code top, left, bottom, right}. */
static void pad(JComponent component, int top, int left, int bottom, int right) {
 component.setBorder(BorderFactory.createEmptyBorder(top, left, bottom, right));
}
}
