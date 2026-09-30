package com.gpmanager;
import static com.gpmanager.Ak.msg;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.Timer;
import net.runelite.client.plugins.config.ConfigPlugin;
import net.runelite.client.ui.components.materialtabs.*;
import net.runelite.client.util.ImageUtil;
import lombok.AllArgsConstructor;
import static com.gpmanager.Kit.*;
/**
* The sidebar frame (SIDEBAR_SPEC.md §1) on stock RuneLite parts: the Material tab strip, the
* shown page's toolbar, the page body in a plain scroll pane (the RuneLite look styles it), then
* one notification line at the bottom. Remembers each page's scroll.
*/
class Shell extends JPanel {
/** A page contributes an optional toolbar and a scrolling body. */
interface Page {
String id();
/** The row under the tabs; null when the page has none. */
JComponent pageBar();
JComponent body();
default void onShown() {
}
}
/** One button on a sheet. */
@AllArgsConstructor
static class Choice {
final String label;
final boolean primary;
final Runnable action;
}
static final String LIVE = "live";
static final String LEDGER = "ledger";
static final String GRINDS = "grinds";
static final String[][] TABS = {{LIVE, "Live"}, {LEDGER, "Ledger"}, {GRINDS, "Grinds"}};
static final javax.swing.border.Border SELECTED = BorderFactory.createCompoundBorder(
BorderFactory.createMatteBorder(0, 0, 1, 0, ACCENT), BorderFactory.createEmptyBorder(5, 2, 4, 2));
static final javax.swing.border.Border UNSELECTED = BorderFactory.createEmptyBorder(5, 2, 5, 2);
final MaterialTabGroup tabs = new MaterialTabGroup();
final Map<String, MaterialTab> tabById = new LinkedHashMap<>();
final JPanel bar = new JPanel(new BorderLayout());
final JPanel sheet = stack(CARD, GAP);
String sheetTitle = "";
final CardLayout cards = new CardLayout();
final JPanel bodies = new JPanel(cards);
final Map<String, Page> pages = new LinkedHashMap<>();
final Map<String, JScrollPane> scrolls = new LinkedHashMap<>();
final Map<String, Integer> scrollMemory = new LinkedHashMap<>();
final JPanel notice = new JPanel(new BorderLayout(GAP, 0));
final JLabel noticeText = label("", small(), TEXT);
final JPanel noticeAction = new JPanel(new BorderLayout());
final Timer noticeTimer = new Timer(4_000, e -> dismiss());
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
public void setBorder(javax.swing.border.Border border) {
// Stock tabs pad 10 px a side; four need narrower sides at sidebar width.
super.setBorder(border instanceof javax.swing.border.CompoundBorder ? SELECTED : UNSELECTED);
}
};
material.setOnSelectEvent(() -> {
if (!selecting) {
show(tab[0]);
}
return true;
});
material.setHorizontalAlignment(JLabel.CENTER);
material.setFont(body());
tabById.put(tab[0], material);
tabs.addTab(material);
}
// Four equal cells: the stock centred flow wraps the fourth tab at sidebar width.
tabs.setLayout(new GridLayout(1, TABS.length));
bar.setOpaque(false);
// Owner 2026-10-01: the page toolbar attaches to the tab strip and shares one band shape.
pad(bar, 0, GAP, GAP, GAP);
var top = new JPanel(new BorderLayout());
top.setOpaque(false);
// The gear beside the tabs holds profile-wide data actions, reachable from any page.
var strip = new JPanel(new BorderLayout());
strip.setOpaque(false);
strip.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, LINE));
JButton gear = new JButton(new ImageIcon(ImageUtil.loadImageResource(ConfigPlugin.class, msg("ey"))));
gear.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
gear.setContentAreaFilled(false);
gear.setFocusPainted(false);
gear.setToolTipText(msg("ck"));
gear.getAccessibleContext().setAccessibleName("Profile data");
gear.addActionListener(e -> onGear.accept(gear));
strip.add(tabs, BorderLayout.CENTER);
strip.add(gear, BorderLayout.EAST);
top.add(strip, BorderLayout.NORTH);
top.add(bar, BorderLayout.CENTER);
sheet.setVisible(false);
var amz = new JPanel(new BorderLayout());
amz.setOpaque(false);
pad(amz, GAP, GAP, 0, GAP);
amz.add(sheet, BorderLayout.CENTER);
top.add(amz, BorderLayout.SOUTH);
add(top, BorderLayout.NORTH);
bodies.setOpaque(false);
add(bodies, BorderLayout.CENTER);
notice.setBackground(CARD);
notice.setVisible(false);
noticeText.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
noticeText.addMouseListener(new MouseAdapter() {
public void mouseClicked(MouseEvent e) {
noticeClicked();
}
});
noticeAction.setOpaque(false);
notice.add(noticeText, BorderLayout.CENTER);
notice.add(noticeAction, BorderLayout.EAST);
noticeTimer.setRepeats(false);
add(notice, BorderLayout.SOUTH);
}
void axm(Page page) {
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
scrolls.put(page.id(), scroll);
bodies.add(scroll, page.id());
}
/** Opens the profile data menu (export, backups, reset) from the gear beside the tabs. */
Consumer<JComponent> onGear = anchor -> { };
/** Runs after a page is shown, so its owner can fill it at once instead of on the next tick. */
Runnable onShow = () -> { };
void show(String id) {
Page page = pages.get(id);
if (page == null) {
return;
}
JScrollPane leaving = scrolls.get(current);
if (leaving != null) {
scrollMemory.put(current, leaving.getVerticalScrollBar().getValue());
}
current = id;
pc();
MaterialTab tab = tabById.get(id);
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
if (pageBar != null) {
bar.add(pageBar, BorderLayout.CENTER);
}
bar.setVisible(pageBar != null);
bar.revalidate();
cards.show(bodies, id);
page.onShown();
onShow.run();
Integer remembered = scrollMemory.get(id);
JScrollPane entering = scrolls.get(id);
if (remembered != null && entering != null) {
SwingUtilities.invokeLater(() -> entering.getVerticalScrollBar().setValue(remembered));
}
repaint();
}
String qt() {
return current;
}
/** A plain notification from any thread; it shows on the Swing thread. */
void tell(String title, String detail, boolean warning) {
if (SwingUtilities.isEventDispatchThread()) {
notify(title, detail, warning, null, null);
} else {
SwingUtilities.invokeLater(() -> notify(title, detail, warning, null, null));
}
}
/** "Export failed · reason" for a failed file action. */
void failed(String title, Exception ex) {
tell(title, ex.getMessage() == null ? "see the log" : ex.getMessage(), true);
}
/**
* One notification at a time at the bottom of the sidebar: about 4 seconds, 8 when it offers
* an action, and a click on the text dismisses it. A sticky data-status failure outranks
* routine news and only clearSticky takes it down (owner 2026-10-01, F06).
*/
void notify(String title, String detail, boolean warning, String action,
Runnable onAction) {
if (noticeSticky) {
return;
}
render(title, detail, warning, action, onAction, false);
}
/** Keeps a save failure visible until the backend recovers; no timer and no click dismiss. */
void notifySticky(String title, String detail, String action,
Runnable onAction) {
String tip = title + (detail == null ? "" : " · " + detail);
if (noticeSticky && notice.isVisible() && tip.equals(noticeText.getToolTipText())) {
return;
}
noticeSticky = true;
render(title, detail, true, action, onAction, true);
}
/** A successful status transition takes the sticky failure down. */
void clearSticky() {
if (!noticeSticky) {
return;
}
noticeSticky = false;
if (notice.isVisible()) {
dismiss();
}
}
/** The text click dismisses routine news; a sticky failure stays. */
void noticeClicked() {
if (!noticeSticky) {
dismiss();
}
}
void render(String title, String detail, boolean warning, String action,
Runnable onAction, boolean sticky) {
Color color = warning ? Kit.Tone.REVIEW.color : Kit.Tone.GAIN.color;
notice.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 3, 0, 0, color),
BorderFactory.createEmptyBorder(4, GAP, 4, GAP)));
String safe = (title + (Ag.empty(detail) ? "" : " · " + detail))
.replace("&", "&amp;").replace("<", "&lt;");
noticeText.setText(msg("co") + (action == null ? 190 : 130) + "px'>" + safe + "</div></html>");
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
void sheet(String title, String message, JComponent content, Choice... choices) {
sheet.removeAll();
sheetTitle = title;
sheet.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, ACCENT),
BorderFactory.createEmptyBorder(GAP, GAP, GAP, GAP)));
sheet.add(label(title, bold(), Color.WHITE));
if (message != null && !message.isEmpty()) {
JLabel note = note(message, LABEL);
note.setBorder(null);
sheet.add(note);
}
if (content != null) {
sheet.add(content);
}
var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
buttons.setOpaque(false);
for (Choice choice : choices) {
buttons.add(button(choice.label, choice.primary, () -> {
pc();
choice.action.run();
}));
}
buttons.add(button("Cancel", false, this::pc));
sheet.add(buttons);
sheet.setVisible(true);
revalidate();
repaint();
}
/** A one-line text sheet; OK or Enter passes the trimmed text when it is not empty. */
void prompt(String title, String label, String initial, Consumer<String> onOk) {
JTextField field = new JTextField(Ag.axw(initial));
JPanel content = stack(CARD, 2);
content.add(label(label, small(), LABEL));
content.add(field);
Runnable ok = () -> {
String value = field.getText().trim();
if (!value.isEmpty()) {
onOk.accept(value);
}
};
field.addActionListener(e -> {
pc();
ok.run();
});
sheet(title, null, content, new Choice("OK", true, ok));
SwingUtilities.invokeLater(field::requestFocusInWindow);
}
/** Asks before an action; it runs only on the named button. */
void confirm(String title, String message, String action, Runnable onConfirm) {
sheet(title, message, null, new Choice(action, true, onConfirm));
}
void pc() {
if (sheet.isVisible()) {
sheet.setVisible(false);
sheet.removeAll();
sheetTitle = "";
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
return Math.max(24, visible.height - 24);
}
public boolean getScrollableTracksViewportWidth() {
return true;
}
public boolean getScrollableTracksViewportHeight() {
return false;
}
}
}
