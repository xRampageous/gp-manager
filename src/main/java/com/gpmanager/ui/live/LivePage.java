package com.gpmanager;
import com.gpmanager.Hero.Cell;
import com.gpmanager.Kit.Tone;
import com.gpmanager.Table.Row;
import com.gpmanager.Ca.Recent;
import com.gpmanager.Ao.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.function.Function;
import javax.swing.*;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.grandexchange.GrandExchangePlugin;
import net.runelite.client.ui.components.ThinProgressBar;
import net.runelite.client.util.ImageUtil;
import static java.util.Collections.*;
import static com.gpmanager.Kit.Tone.*;
import static com.gpmanager.Kit.*;
import static com.gpmanager.Fmt.*;
import static com.gpmanager.Ak.msg;
/**
* Live = what is happening now (SIDEBAR_SPEC.md §3): the hero band, the target card with pace,
* and the Recent table. Every component is built once; a tick only updates values, so hover,
* menus and the Recent page survive. The page renders a {@link Ca} and holds no engine.
*/
class LivePage implements Shell.Page {
interface Actions {
 void togglePause();
 void openLedger(Entry entry);
 void editTarget();
 default void hideRecent(String name) { }
 /** Opens the Start Grind sheet. */
 default void startGrind() {
 }
 /** Clicking the running Grind's name renames it. */
 default void renameGrind() {
 }
 default void endGrind() {
 }
 /** True when gameplay alone starts tracking; false when the Grind button does. */
 default boolean autoStartArmed() {
  return true;
 }
}

final Actions actions;
final Function<Integer, BufferedImage> sprites;
final Map<Integer, ImageIcon> icons = new HashMap<>();
final JPanel body = stack(PANEL, GAP);
final Hero hero = new Hero();
final JPanel target = new JPanel(new BorderLayout(0, 3));
final JLabel targetTitle = label("", small(), LABEL);
final JLabel targetValue = label("", Kit.body(), TEXT);
final JLabel targetPace = label("", small(), LABEL);
final ThinProgressBar targetBar = new ThinProgressBar();
/** Free play offers Start (the Start Grind sheet); a running Grind offers End instead. */
final JButton startGrind;
final JButton endGrind;
/** GE offers still on the exchange; shown only while there are some, then they move to Recent. */
final Table offers = new Table("OFFERS");
final Table recent = new Table("RECENT");
/** Pending counts as small icons: loot keys, GE offers, grave reclaim. */
final JPanel counts = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
final ImageIcon geIcon = icon(() -> ImageUtil.loadImageResource(GrandExchangePlugin.class, "ge_icon.png"));
final ImageIcon graveIcon = new ImageIcon(grave(LOSS.color));
String drawnCounts = "";
/** The RECENT empty sentence currently set; only a change forces a table rebuild. */
String emptyRecent = "Nothing booked yet";
LivePage(Actions actions, Function<Integer, BufferedImage> sprites) {
 this.actions = actions;
 this.sprites = sprites == null ? id -> null : sprites;
 startGrind = button("Grind", false, actions::startGrind);
 startGrind.setToolTipText("Start a Grind");
 endGrind = button("End", false, actions::endGrind);
 endGrind.setToolTipText("End this Grind");
 counts.setOpaque(false);
 hero.controls(counts, startGrind, endGrind);
 Hero.clickable(hero.name, actions::renameGrind);
 // Clicking the Net total sets or changes the target (owner 2026-09-28: no Target button).
 Hero.clickable(hero.net, actions::editTarget);
 target.setBackground(CARD);
 pad(target, 4, 6, 5, 6);
 var head = new JPanel(new BorderLayout());
 head.setOpaque(false);
 head.add(targetTitle, BorderLayout.CENTER);
 target.add(head, BorderLayout.NORTH);
 var line = new JPanel(new BorderLayout(0, 2));
 line.setOpaque(false);
 line.add(targetValue, BorderLayout.NORTH);
 targetBar.setMaximumValue(1000);
 targetBar.setBackground(LINE);
 line.add(targetBar, BorderLayout.CENTER);
 line.add(targetPace, BorderLayout.SOUTH);
 target.add(line, BorderLayout.CENTER);
 // A set target is edited by clicking its card.
 target.setToolTipText(msg("cd"));
 Hero.clickable(target, actions::editTarget);
 recent.setRowsPerPage(Ca.RECENT_ROWS).setFill(true).setEmptyText("Nothing booked yet");
 JButton all = button("View all", false, () -> actions.openLedger(Entry.current()));
 recent.setAction(all);
 pad(body, 0, GAP, GAP, 0);
 for (JComponent part : new JComponent[] {hero, target, offers, recent}) body.add(part);
}

public String id() {
 return Shell.LIVE;
}

public JComponent pageBar() {
 return null;
}

public JComponent body() {
 return body;
}

/** Removes the prior owner's content while the host is fenced for a profile switch. */
void ot() {
 recent.setRows(emptyList());
 recent.avd();
 hero.big(null, PLAIN, "", "", false, "");
 hero.strips(emptyList());
 hero.icons(emptyList());
 counts.removeAll();
 drawnCounts = "";
 target.setVisible(false);
 offers.setRows(emptyList());
 offers.setVisible(false);
}

void apply(Ca s) {
 boolean arg = !s.loggedOut;
 hero.status(awd(s), ajn(s), wf(s), wordOf(s), arg ? actions::togglePause : null);
 hero.icons(ajq(s));
 counts(s);
 // Nothing booked yet reads as a dash, never the font's slashed zero.
 boolean booked = s.gains != 0L || s.costs != 0L || s.marketResult != 0L || !s.recent.isEmpty();
 if (s.hasSession) {
  hero.big(booked ? signed(s.net) : "—", booked ? sign(s.net) : DIM,
  ru(s.net) + " gp · gains " + exact(s.gains) + (s.costSplitAvailable
  ? " · supplies " + exact(s.supplies) + " · losses " + exact(s.loss)
  : " · costs " + exact(s.costs) + " (split unavailable)")
  + (s.goal == null ? " · click to set a target" : ""), awx(s.rateEstablished, s.gpPerHour), s.rateEstablished,
  clock(s.elapsedMillis) + " ACTIVE");
 } else {
  hero.big("—", DIM, "Nothing tracked yet", "—", false, "");
 }
 // Owner 2026-10-01 (F12): the hero names the rate basis and the tracked-time policy.
 hero.rate.setToolTipText(s.rateEstablished ? "GP/h over active time" : null);
 hero.clock.setToolTipText(s.hasSession ? msg("fu") : null);
 hero.strips(strips(s));
 la(s);
 var rows = new ArrayList<Row>();
 var open = new ArrayList<Row>();
 for (Recent row : s.recent) (row.open ? open : rows).add(rowOf(row));
 offers.setTitle("OFFERS · " + open.size()).setRows(open);
 offers.setVisible(!open.isEmpty());
 // Owner 2026-10-01 (F13): the empty card says which event starts tracking, never a fake start.
 String empty = s.loggedOut ? msg("fv") : !s.freePlay ? "Nothing booked yet"
 : actions.autoStartArmed() ? "Tracking starts with your first gameplay" : "Press Grind to start tracking";
 if (!empty.equals(emptyRecent)) {
  emptyRecent = empty;
  recent.setEmptyText(empty);
 }
 recent.setTitle(rows.isEmpty() ? "RECENT" : "RECENT · " + rows.size()).setRows(rows);
}

void la(Ca s) {
 Ca.Goal goal = s.goal;
 startGrind.setVisible(s.freePlay && !s.loggedOut);
 endGrind.setVisible(!s.freePlay);
 hero.name.setToolTipText(s.freePlay ? null
 : "Click to rename" + (wf(s).equals(hero.name.getText()) ? "" : " \u00b7 " + wf(s)));
 target.setVisible(goal != null);
 if (goal == null) return;
 targetTitle.setText("TARGET · " + goal.label + " · " + goal.progress);
 if (goal.time) {
  // Owner 2026-10-01 (F10): a time target reads tracked Active Time, not fabricated Net.
  targetValue.setText(ra(goal.current) + " / " + goal.label);
  targetValue.setToolTipText(duration(goal.target) + " of active play");
 } else {
  targetValue.setText(signed(goal.current) + " / " + goal.label);
  targetValue.setToolTipText(ru(goal.current) + " gp of " + exact(goal.target) + " gp");
 }
 targetBar.setValue((int) Math.round(goal.fraction * 1000));
 targetBar.setForeground(goal.reached ? GAIN.color : ACCENT);
 targetPace.setText(goal.eta == null ? msg("ce") : goal.time ? goal.eta : goal.eta + " of play left at this rate");
 targetPace.setVisible(!goal.reached);
}

Row rowOf(Recent row) {
 ImageIcon icon = null;
 BufferedImage image = sprites.apply(row.itemId);
 if (image != null) {
  icon = icons.get(row.itemId);
  if (icon == null || icon.getImage() != image) {
   icon = new ImageIcon(image);
   icons.put(row.itemId, icon);
  }
 }
 Entry entry = yn(row);
 var menu = new ArrayList<Table.Menu>();
 menu.add(new Table.Menu("Open in Ledger", () -> actions.openLedger(entry)));
 if (!row.open && row.itemId > 0 && !row.composite)
 menu.add(new Table.Menu("Hide from recent", () -> actions.hideRecent(row.name)));
 // Ore a failed smelt lost sits beside the ore the bars used: say which is which.
 String name = msg("zz").equals(row.actionLabel) ? row.name + " (failed)" : row.name;
 return new Row(row.contributionId + "|" + row.receiptId, icon, name, row.qty,
 avo(row), toneOf(row), tipOf(row), numeric(row) ? ru(row.value) + " gp" : "", false,
 () -> actions.openLedger(entry), menu);
}

List<List<Cell>> strips(Ca s) {
 if (!s.hasSession) {
  return singletonList(Arrays.asList(new Cell("GAINS", "—", DIM, "", null), new Cell("SUPPLY", "—", DIM, "", null),
  new Cell("LOSS", "—", DIM, "", null)));
 }
 Runnable ledger = () -> actions.openLedger(Entry.current());
 var money = new ArrayList<Cell>();
 money.add(new Cell("GAINS", signed(s.gains), GAIN, "Loot and other counted gains · " + exact(s.gains) + " gp", ledger));
 if (s.costSplitAvailable) {
  money.add(new Cell("SUPPLY", signed(-s.supplies), SUPPLY, msg("jl") + exact(s.supplies) + " gp", ledger));
  money.add(new Cell("LOSS", signed(-s.loss), LOSS, msg("jm") + exact(s.loss) + " gp", ledger));
 } else {
  // The split cannot be vouched for: the truthful total, never invented parts.
  money.add(new Cell("COSTS", signed(-s.costs), LOSS, msg("ft"), ledger));
 }
 if (s.marketResult != 0L || s.marketPending > 0) {
  money.add(new Cell("MARKET", s.marketResult == 0L ? "open" : signed(s.marketResult), MARKET, msg("gt"), ledger));
 }
 var rows = new ArrayList<List<Cell>>(singletonList(money));
 if (s.pvpSession) {
  rows.add(Arrays.asList(new Cell("BEST KILL", s.bestKill == 0L ? "—" : signed(s.bestKill), GAIN,
  s.kills + " kills · " + s.deaths + " deaths", null),
  new Cell("STREAK", String.valueOf(s.streak), s.streak > 0 ? GAIN : DIM,
  msg("gp") + s.bestStreak + " this Grind", null)));
 }
 // Owner 1.1: where a death can be lost, its risk and the skull live here, not on HUD+.
 if (s.pvpPossible || s.pvpSession) {
  rows.add(Arrays.asList(new Cell("RISK", s.pvpPossible ? compact(s.risk) : "—", s.risk > 0L ? LOSS : DIM,
  "What a death here would lose: all but your 3 most valuable items (none skulled), +1 with Protect Item", null),
  new Cell("SKULL", s.skulled ? "On" : "Off", s.skulled ? LOSS : DIM, msg("he"), null),
  new Cell("PROTECT", s.protectItem ? "On" : "Off", s.protectItem ? PLAIN : DIM, "Protect Item prayer", null)));
 }
 return rows;
}

List<Cell> ajq(Ca s) {
 var list = new ArrayList<Cell>();
 if (s.reviewCount > 0) {
  list.add(new Cell("", "(!)" + s.reviewCount, REVIEW,
  s.reviewCount == 1 ? msg("cf") : s.reviewCount + " items need your decision",
  () -> actions.openLedger(Entry.current(Audit.REVIEW, null))));
 }
 return list;
}

/** Rebuilds the count icons only when a count changes, so hover survives a tick. */
void counts(Ca s) {
 String key = s.pendingKeys + "|" + s.marketPending + "|" + s.reclaimItems;
 if (key.equals(drawnCounts)) return;
 drawnCounts = key;
 counts.removeAll();
 Runnable pending = () -> actions.openLedger(Entry.current());
 BufferedImage aop = sprites.apply(ItemID.WILDY_LOOT_KEY0);
 count(s.pendingKeys, aop == null ? null : new ImageIcon(aop), "key", DIM,
 "Loot keys not yet opened: " + s.pendingKeys, pending);
 count(s.marketPending, geIcon, "GE", MARKET, "Grand Exchange offers still open: " + s.marketPending, pending);
 count(s.reclaimItems, graveIcon, "grave", LOSS, msg("jn") + s.reclaimItems, pending);
 counts.revalidate();
 counts.repaint();
}

void count(long n, ImageIcon image, String fallback, Tone tone, String tip, Runnable open) {
 if (n <= 0L) return;
 // A borderless button, not a mouse listener: the safety audit bans mouse-event names.
 var label = new JButton(image == null ? fallback + " " + n : String.valueOf(n));
 label.setFont(small());
 label.setForeground(tone.color);
 label.setIcon(image == null || image.getIconHeight() <= 12 ? image
 : new ImageIcon(image.getImage().getScaledInstance(-1, 12, Image.SCALE_SMOOTH)));
 label.setIconTextGap(2);
 label.setToolTipText(tip);
 label.setBorder(null);
 label.setBorderPainted(false);
 label.setContentAreaFilled(false);
 label.setFocusPainted(false);
 label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
 label.addActionListener(e -> open.run());
 counts.add(label);
}

static ImageIcon icon(java.util.function.Supplier<BufferedImage> load) {
 try {
  BufferedImage image = load.get();
  return image == null ? null : new ImageIcon(image);
 } catch (RuntimeException ex) {
  return null;
 }
}

/** The design's 11px outline headstone with a cross; RuneLite ships no grave icon. */
static BufferedImage grave(Color color) {
 var image = new BufferedImage(11, 11, BufferedImage.TYPE_INT_ARGB);
 Graphics2D g = image.createGraphics();
 g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
 g.setColor(color);
 g.setStroke(new BasicStroke(1.2f));
 java.awt.geom.Path2D.Float axk = new java.awt.geom.Path2D.Float();
 axk.moveTo(2.5f, 10f);
 axk.lineTo(2.5f, 4.5f);
 axk.append(new java.awt.geom.Arc2D.Float(2.5f, 1.5f, 6f, 6f, 180f, -180f, java.awt.geom.Arc2D.OPEN), true);
 axk.lineTo(8.5f, 10f);
 axk.closePath();
 g.draw(axk);
 g.draw(new java.awt.geom.Line2D.Float(5.5f, 4f, 5.5f, 7.5f));
 g.draw(new java.awt.geom.Line2D.Float(4f, 5.5f, 7f, 5.5f));
 g.dispose();
 return image;
}

static Color awd(Ca s) {
 if (s.loggedOut) return OFF;
 if (s.paused && !s.idle) return REVIEW.color;
 if (!s.hasSession || s.idle) return OFF;
 return GAIN.color;
}

static Tone toneOf(Recent row) {
 if ("review".equals(row.tag)) return REVIEW;
 if (!numeric(row)) return DIM;
 if (row.market) return MARKET;
 if (row.value >= 0L) return GAIN;
 return row.ledgerCostView == Bs.LOSS ? LOSS : SUPPLY;
}

static boolean numeric(Recent row) {
 return !row.neutral && !row.unpriced && !"canceled".equals(row.tag)
 && (!row.market || row.marketSettlementValue || row.valueAvailable);
}

/** "1.93M/h" once the rate is established; never 0/h before that. */
static String awx(boolean established, long gpPerHour) {
 return established ? rate(gpPerHour) + "/h" : "Calculating…";
}

static String tipOf(Recent row) {
 var tip = new StringBuilder(row.name).append(" · ").append(metaOf(row));
 if ("review".equals(row.tag)) tip.append(" · needs review");
 if (row.unpriced) tip.append(msg("jw"));
 if (row.market && !row.valueAvailable && !row.marketSettlementValue)
 tip.append(" · Market result is not realized");
 if (row.market && row.marketSettlementValue)
 tip.append(row.valueAvailable ? msg("gs") : msg("gu"));
 if (row.receipts > 1 && !metaOf(row).contains("receipt"))
 tip.append(" · ").append(row.receipts).append(" receipts");
 return tip.toString();
}

/** The right-side value: settled cash is available independently of Result. */
static String avo(Recent row) {
 if (row.neutral) return "\u2014";
 if (row.unpriced) return "?";
 if ("canceled".equals(row.tag)) return "Canceled";
 if (row.market && !row.marketSettlementValue && !row.valueAvailable)
 return "review".equals(row.tag) ? "?" : row.marketSide.isEmpty() ? "Pending" : row.marketSide;
 return signed(row.value);
}

/** What the row is: its state for review / unpriced rows, else action and quantity. */
static String metaOf(Recent row) {
 if ("review".equals(row.tag)) return "Needs review";
 if ("unpriced".equals(row.tag)) return "Unpriced";
 String qty = row.qty == null ? "" : " · " + row.qty;
 if (!row.market && (row.actionLabel.isEmpty() || (row.composite && row.name.equalsIgnoreCase(row.actionLabel))))
 return Ag.axw(row.qty);
 return row.actionLabel + qty;
}

/** Stable receipt identity drives Live navigation; search text stays a user-owned filter. */
static Entry yn(Recent row) {
 return new Entry(Ao.Scope.CURRENT_GRIND, null, null,
 row.ledgerCostView == null ? Bs.SUPPLIES : row.ledgerCostView, "", row.receiptId, row.contributionId, null,
 row.ledgerReview ? Audit.REVIEW : null);
}

/** The small status word, only when something is off: tracking itself is the green dot. */
static String wordOf(Ca s) {
 if (s.hopping) return "RECONNECTING";
 if (s.loggedOut) return "LOGGED OUT";
 if (!s.hasSession && s.resumeInSeconds > 0L) return "RESUMING " + s.resumeInSeconds + "s";
 if (!s.hasSession) return "";
 if (s.paused && !s.idle) return "PAUSED";
 if (s.idle) return "AWAY";
 return s.calibrating ? "CALIBRATING" : "";
}

/** The shared activity label: fresh NPC target, else the session activity, else the Grind name. */
static String wf(Ca s) {
 return ActivityLabel.resolve(!s.target.isEmpty(), s.activityLabel, s.ownerLabel, s.hasSession, s.freePlay);
}

static String ajn(Ca s) {
 String wording = s.hopping ? "Reconnecting" : s.loggedOut ? msg("gq") : !s.hasSession ? msg("bq")
 : s.paused && !s.idle ? msg("bb") : s.idle ? msg("fc") : msg("bn");
 var tip = new StringBuilder(wording);
 if (s.hasSession) tip.append(" · active ").append(duration(s.elapsedMillis));
 return tip.toString();
}
}
