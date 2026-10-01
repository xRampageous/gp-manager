package com.gpmanager;
import lombok.EqualsAndHashCode;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import static com.gpmanager.ModelText.*;
import static java.util.Collections.*;
import static com.gpmanager.Kit.*;
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
 final String label;
 final String value;
 final Kit.Tone tone;
 final String tip;
 final Runnable open;
 Cell(String label, String value, Kit.Tone tone, String tip, Runnable open) {
  this.label = orEmpty(label);
  this.value = orEmpty(value);
  this.tone = tone == null ? Kit.Tone.PLAIN : tone;
  this.tip = orEmpty(tip);
  this.open = open;
 }
}

final JLabel dot = label("●", body(), OFF);
final JLabel name = label("", bold(), Color.WHITE);
final JLabel word = label("", small(), LABEL);
final JPanel icons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
/** Net in the GP/h face at its own size: RuneLite's pixel fonts blur when scaled. */
final JLabel net = label("", bold(), TEXT);
final JLabel rate = label("", bold(), TEXT);
final JLabel clock = label("", small(), LABEL);
final JPanel strips = stack(CARD, 0);
final JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
final JPanel big = new JPanel(new BorderLayout(GAP, 0));
List<Cell> drawnIcons = emptyList();
List<List<Cell>> drawnStrips = emptyList();
Runnable onDot;
Hero() {
 super(new BorderLayout());
 setBackground(CARD);
 var status = new JPanel(new BorderLayout(4, 0));
 status.setOpaque(false);
 status.setPreferredSize(new Dimension(0, 28));
 pad(status, 0, 6, 0, 4);
 var left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 5));
 left.setOpaque(false);
 left.add(dot);
 left.add(name);
 left.add(word);
 status.add(left, BorderLayout.CENTER);
 var right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 5));
 right.setOpaque(false);
 icons.setOpaque(false);
 controls.setOpaque(false);
 right.add(icons);
 right.add(controls);
 status.add(right, BorderLayout.EAST);
 dot.addMouseListener(new MouseAdapter() {
  public void mouseClicked(MouseEvent e) {
   if (onDot != null) onDot.run();
  }
 });
 big.setOpaque(false);
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
 var top = new JPanel(new BorderLayout());
 top.setOpaque(false);
 top.add(status, BorderLayout.NORTH);
 top.add(big, BorderLayout.CENTER);
 add(top, BorderLayout.NORTH);
 add(strips, BorderLayout.CENTER);
}

/** The dot's colour says the state; clicking it runs {@code toggle} (pause / resume) when set. */
Hero status(Color dotColor, String dotTip, String title, String statusWord, Runnable toggle) {
 dot.setForeground(dotColor);
 dot.setToolTipText(dotTip);
 dot.setCursor(toggle == null ? Cursor.getDefaultCursor() : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
 onDot = toggle;
 // Owner 2026-10-01 (F17): a long name is bounded; the full text stays in its tooltip.
 name.setText(Kit.fit(name, title, nameBudget()));
 name.setToolTipText(orEmpty(title).isEmpty() ? null : title);
 word.setText(orEmpty(statusWord));
 word.setVisible(!word.getText().isEmpty());
 return this;
}

/** Pixel budget for the name after the dot, the status word and the right-hand controls. */
int nameBudget() {
 int width = getWidth();
 if (width <= 0 && getParent() != null) width = getParent().getWidth();
 if (width <= 0) {
  // Before the first layout: the standard sidebar budget.
  return 120;
 }
 int used = 16 + dot.getPreferredSize().width + word.getPreferredSize().width
 + icons.getPreferredSize().width + controls.getPreferredSize().width + 24;
 return Math.max(40, width - used);
}

/** Quiet count icons; only those above zero should be passed. */
Hero icons(List<Cell> value) {
 List<Cell> next = value == null ? emptyList() : value;
 if (next.equals(drawnIcons)) return this;
 drawnIcons = new ArrayList<>(next);
 icons.removeAll();
 for (Cell cell : next) {
  JLabel icon = label(cell.value, small(), cell.tone.color);
  icon.setToolTipText(cell.tip);
  clickable(icon, cell.open);
  icons.add(icon);
 }
 icons.revalidate();
 return this;
}

/** Small icon controls on the far right of the status line. */
Hero controls(JComponent... components) {
 controls.removeAll();
 for (JComponent component : components) controls.add(component);
 controls.revalidate();
 return this;
}

/** The big line: Net (tone by sign), its exact hover, the rate and the active clock. */
Hero big(String netText, Kit.Tone tone, String exact, String rateText, boolean rateReady, String clockText) {
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
 strips.removeAll();
 for (List<Cell> strip : next) strips.add(strip(strip));
 strips.revalidate();
 return this;
}

/** One row of labelled cells, as the hero and the Grinds All time card show them. */
static JPanel strip(List<Cell> cells) {
 var row = new JPanel(new GridLayout(1, Math.max(1, cells.size()), 1, 0));
 row.setBackground(LINE);
 row.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, LINE));
 for (Cell cell : cells) {
  var box = new JPanel(new BorderLayout());
  box.setBackground(CARD);
  pad(box, 2, 5, 3, 5);
  box.add(label(cell.label, small(), LABEL), BorderLayout.NORTH);
  box.add(label(cell.value, body(), cell.tone.color), BorderLayout.CENTER);
  box.setToolTipText(cell.tip);
  clickable(box, cell.open);
  row.add(box);
 }
 return row;
}

static void clickable(JComponent component, Runnable open) {
 if (open == null) return;
 component.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
 component.addMouseListener(new MouseAdapter() {
  public void mouseClicked(MouseEvent e) {
   if (e.getButton() == MouseEvent.BUTTON1) open.run();
  }
 });
}
}
