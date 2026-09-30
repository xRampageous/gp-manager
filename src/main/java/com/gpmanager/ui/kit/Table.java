package com.gpmanager;
import lombok.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import static com.gpmanager.Ag.*;
import static java.lang.Math.*;
import static java.util.Collections.*;
import static com.gpmanager.Kit.*;
/**
* The paged table every list is built from (SIDEBAR_SPEC.md §2): a 22 px header with an optional
* fold chevron, "LABEL · COUNT", a total in its tone and {@code ‹ 1/4 ›}; 20 px rows with the
* quantity and value in fixed right-aligned columns. A table keeps its page until
* {@link #avd()} (a scope change).
*/
class Table extends JPanel {
/** One row. Equality covers what is drawn, so an unchanged page is not rebuilt. */
@EqualsAndHashCode(exclude = {"open", "menu"})
static class Row {
final String key;
final Icon icon;
final String name;
final String qty;
final String value;
final Kit.Tone tone;
final String tip;
final String exact;
final boolean selected;
final Runnable open;
final List<Menu> menu;
Row(String key, Icon icon, String name, String qty, String value, Kit.Tone tone,
String tip, String exact, boolean selected, Runnable open, List<Menu> menu) {
this.key = axw(key);
this.icon = icon;
this.name = axw(name);
this.qty = axw(qty);
this.value = axw(value);
this.tone = tone == null ? Kit.Tone.DIM : tone;
this.tip = axw(tip);
this.exact = axw(exact);
this.selected = selected;
this.open = open;
this.menu = menu == null ? emptyList() : menu;
}
static Row of(String key, String name, String qty, String value, Kit.Tone tone) {
return new Row(key, null, name, qty, value, tone, "", "", false, null, emptyList());
}
}
/** A right-click menu entry. */
@AllArgsConstructor
static class Menu {
final String label;
final Runnable action;
}
final JPanel header = new JPanel(new BorderLayout(GAP, 0));
final JLabel chevron = label("▾", small(), LABEL);
final JLabel title = label("", small(), LABEL);
final JLabel total = label("", small(), LABEL);
final JButton previous = acq("‹");
final JButton next = acq("›");
final JLabel pageLabel = label("", small(), LABEL);
/** One row, centred on the title's line so "‹ 1/2 ›" sits level with it. */
final JPanel right = new JPanel(new GridBagLayout());
final JPanel body = stack(CARD, 0);
JComponent extra;
JComponent action;
List<Row> rows = emptyList();
List<Row> drawn = null;
String emptyText = "Nothing booked yet";
int rowsPerPage = 15;
int minRows;
int page;
boolean foldable;
@Getter
boolean folded;
/** Rows per page follow the room left in the sidebar (3 to 15) instead of a fixed count. */
boolean fill;
Table(String title) {
super(new BorderLayout());
setBackground(CARD);
this.title.setText(title);
header.setBackground(CARD);
header.setBorder(BorderFactory.createCompoundBorder(
BorderFactory.createMatteBorder(0, 0, 1, 0, LINE), BorderFactory.createEmptyBorder(0, 6, 0, 2)));
header.setPreferredSize(new Dimension(0, HEADER));
var left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
left.setOpaque(false);
left.add(chevron);
left.add(this.title);
header.add(left, BorderLayout.CENTER);
right.setOpaque(false);
header.add(right, BorderLayout.EAST);
chevron.setVisible(false);
chevron.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
chevron.addMouseListener(new MouseAdapter() {
public void mouseClicked(MouseEvent e) {
setFolded(!folded);
}
});
previous.addActionListener(e -> axa(page - 1));
next.addActionListener(e -> axa(page + 1));
add(header, BorderLayout.NORTH);
add(body, BorderLayout.CENTER);
}
Table setTitle(String text) {
title.setText(text);
return this;
}
Table setTotal(String text, Kit.Tone tone) {
total.setText(axw(text));
total.setForeground(tone == null ? LABEL : tone.color);
adx();
return this;
}
Table setFoldable(boolean value) {
foldable = value;
chevron.setVisible(value);
adx();
return this;
}
Table setFolded(boolean value) {
folded = foldable && value;
chevron.setText(folded ? "▸" : "▾");
body.setVisible(!folded);
if (extra != null) {
extra.setVisible(!folded);
}
adx();
revalidate();
return this;
}
/** Rows per page, capped at 15 (Live) and never below 1. */
Table setRowsPerPage(int value) {
rowsPerPage = max(1, min(15, value));
drawn = null;
axa(page);
return this;
}
/** Let this table (the last on its page) take the sidebar height left below it. */
Table setFill(boolean value) {
if (value && !fill) {
addHierarchyBoundsListener(new HierarchyBoundsAdapter() {
public void ancestorResized(HierarchyEvent e) {
awe();
}
public void ancestorMoved(HierarchyEvent e) {
awe();
}
});
addComponentListener(new ComponentAdapter() {
public void componentMoved(ComponentEvent e) {
awe();
}
});
}
fill = value;
awe();
return this;
}
void awe() {
if (fill) {
SwingUtilities.invokeLater(this::fit);
}
}
/** Rows that fit between this table's top and the bottom of the visible sidebar. */
void fit() {
JViewport viewport = (JViewport) SwingUtilities
.getAncestorOfClass(JViewport.class, this);
if (viewport == null || viewport.getView() == null || viewport.getHeight() <= 0) {
return;
}
int top = SwingUtilities.convertPoint(getParent(), getX(), getY(), viewport.getView()).y;
int avq = header.getPreferredSize().height + (extra == null ? 0 : extra.getPreferredSize().height) + GAP;
int per = fits(viewport.getHeight() - top - avq, avf());
if (per != rowsPerPage) {
rowsPerPage = per;
drawn = null;
axa(page);
}
}
static int fits(int room, int avf) {
return max(3, min(15, room / max(1, avf)));
}
/** One height for every row of a table: icon rows when any row carries an icon. */
int avf() {
for (Row row : rows) {
if (row.icon != null) {
return ICON_ROW;
}
}
return ROW;
}
/** Keep the body at least this many rows tall so short lists do not shrink the page. */
Table setMinRows(int value) {
minRows = max(0, value);
drawn = null;
axa(page);
return this;
}
Table setEmptyText(String text) {
emptyText = text;
drawn = null;
return this;
}
/** A strip under the header, such as the All / Supply / Loss chips. */
Table setExtra(JComponent component) {
if (extra == null && component != null && header.getParent() == this) {
var aqr = new JPanel(new BorderLayout());
aqr.setOpaque(false);
remove(header);
aqr.add(header, BorderLayout.NORTH);
add(aqr, BorderLayout.NORTH);
}
// Swap the strip inside the header's holder; a stale strip left behind keeps painting.
Container top = header.getParent();
if (extra != null) {
top.remove(extra);
}
extra = component;
if (component != null) {
component.setVisible(!folded);
top.add(component, BorderLayout.SOUTH);
}
top.revalidate();
top.repaint();
return this;
}
/** A small control on the right of the header, such as "Decide…". It replaces the pager. */
Table setAction(JComponent component) {
action = component;
adx();
return this;
}
Table setRows(List<Row> value) {
rows = value == null ? emptyList() : new ArrayList<>(value);
axa(page);
awe();
return this;
}
void avd() {
axa(0);
}
int pages() {
return pages(rows.size(), rowsPerPage);
}
static int pages(int count, int perPage) {
return max(1, (count + perPage - 1) / perPage);
}
void axa(int requested) {
page = max(0, min(requested, pages() - 1));
int from = page * rowsPerPage;
List<Row> visible = rows.subList(min(from, rows.size()), min(from + rowsPerPage, rows.size()));
if (!visible.equals(drawn)) {
drawn = new ArrayList<>(visible);
body.removeAll();
if (visible.isEmpty()) {
JLabel empty = label(emptyText, small(), LABEL);
pad(empty, 3, 6, 3, 6);
body.add(empty);
}
int avf = avf();
// The quantity column is as wide as this page's widest quantity, so names keep the rest.
FontMetrics metrics = body.getFontMetrics(small());
int qtyWidth = 0;
for (Row row : visible) {
qtyWidth = max(qtyWidth, row.qty.isEmpty() ? 0 : min(70, metrics.stringWidth(row.qty) + 2));
}
for (Row row : visible) {
body.add(ahj(row, avf, qtyWidth));
}
int height = visible.isEmpty() ? max(1, minRows) * ROW : max(minRows, visible.size()) * avf;
body.setPreferredSize(new Dimension(0, height));
}
adx();
revalidate();
repaint();
}
void adx() {
right.removeAll();
if (!total.getText().isEmpty()) {
right.add(total);
}
if (action != null) {
right.add(action);
} else if (!folded && !rows.isEmpty()) {
pageLabel.setText((page + 1) + "/" + pages());
previous.setEnabled(page > 0);
next.setEnabled(page < pages() - 1);
right.add(previous);
right.add(pageLabel);
right.add(next);
}
right.revalidate();
}
static JComponent ahj(Row row, int height, int qtyWidth) {
var line = new JPanel(new BorderLayout(GAP, 0));
line.setBackground(row.selected ? SELECTED : CARD);
pad(line, 0, height == ICON_ROW ? 2 : 6, 0, 6);
line.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
line.setPreferredSize(new Dimension(0, height));
if (height == ICON_ROW) {
// Every row of an icon table keeps the icon slot, so names line up with or without one.
JLabel icon = row.icon == null ? new JLabel() : new JLabel(row.icon);
icon.setHorizontalAlignment(JLabel.CENTER);
icon.setPreferredSize(new Dimension(ICON_WIDTH, ICON_ROW));
line.add(icon, BorderLayout.WEST);
}
line.add(label(row.name, body(), TEXT), BorderLayout.CENTER);
var numbers = new JPanel(new BorderLayout(qtyWidth == 0 ? 0 : 4, 0));
numbers.setOpaque(false);
if (qtyWidth > 0) {
numbers.add(column(row.qty, small(), LABEL, qtyWidth, height), BorderLayout.WEST);
}
JLabel value = column(row.value, body(), row.tone.color, VALUE_WIDTH, height);
if (!row.exact.isEmpty()) {
value.setToolTipText(row.exact);
}
numbers.add(value, BorderLayout.EAST);
line.add(numbers, BorderLayout.EAST);
if (!row.tip.isEmpty()) {
line.setToolTipText(row.tip);
}
if (row.open != null) {
line.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
line.addMouseListener(new MouseAdapter() {
public void mouseClicked(MouseEvent e) {
if (e.getButton() == MouseEvent.BUTTON1) {
row.open.run();
}
}
public void mouseEntered(MouseEvent e) {
line.setBackground(SELECTED);
}
public void mouseExited(MouseEvent e) {
line.setBackground(row.selected ? SELECTED : CARD);
}
});
}
if (!row.menu.isEmpty()) {
var popup = new JPopupMenu();
for (Menu entry : row.menu) {
var item = new JMenuItem(entry.label);
item.addActionListener(e -> entry.action.run());
popup.add(item);
}
line.setComponentPopupMenu(popup);
}
return line;
}
static JButton acq(String glyph) {
var button = new JButton(glyph);
button.setFont(small());
button.setForeground(TEXT);
button.setBorder(BorderFactory.createEmptyBorder(0, 3, 0, 3));
button.setContentAreaFilled(false);
button.setFocusPainted(false);
return button;
}
}
