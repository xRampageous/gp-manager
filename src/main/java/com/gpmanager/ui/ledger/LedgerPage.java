package com.gpmanager;
import com.gpmanager.Table.Menu;
import com.gpmanager.Ao.*;
import com.gpmanager.Table.*;
import com.gpmanager.Kit.Tone;
import com.gpmanager.Br.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.function.Function;
import javax.swing.*;
import javax.swing.event.*;
import net.runelite.client.ui.components.IconTextField;
import static com.gpmanager.Ae.nonNeg;
import static java.lang.Math.*;
import static java.util.Collections.*;
import static com.gpmanager.Kit.Tone.*;
import static com.gpmanager.Kit.*;
import static com.gpmanager.Fmt.*;
import static com.gpmanager.Ak.msg;
/**
* Ledger = why the numbers changed (SIDEBAR_SPEC.md §3). The toolbar holds the scope, the Net,
* search and ⋯; the overview is folding paged tables (Gains, Costs with Supply / Loss, Market,
* Deaths, Pending, Review, Corrected). Opening a row shows its receipts; the selected receipt opens
* under them with its short facts and one Correct ▾ menu. Corrections always target one exact
* canonical receipt id.
*/
class LedgerPage implements Shell.Page {
enum Ea {
 APPLIED, REFUSED, STALE
}

interface Actions {
 void openScopeMenu(JComponent anchor);
 void costViewChanged(Bs view);
 void searchChanged(String text);
 /** Selection is navigation state only; a correction still targets one transaction id. */
 default void selectionChanged(String transactionId, String contributionId, String groupId) {
 }
 Ef preview(String transactionId, Ah correction);
 Ea correct(String transactionId, Ah correction, long previewRevision);
 /** Asks how many to keep, then hands back {itemId, keep, total} and its Net preview. */
 void split(String transactionId, java.util.function.BiConsumer<long[], Ef> previewed);
 default void applySplit(String transactionId, long[] split, long previewRevision) {
 }
 void undoCorrection();
 /** Pins an open mutation menu to the live session; empty when none can be mutated. */
 default String pinMenuTarget() {
  return "";
 }
 /** Undoes the latest booked change (moved here from the Live menu). */
 default void undoLast() {
 }
 default void restoreUndo() {
 }
 void decideAll(Cl decision);
 default void decide(String transactionId, Cl decision) {
 }
 void refresh();
}

static final int ROWS = 5;
final Actions actions;
final Function<Integer, BufferedImage> sprites;
final Map<Integer, ImageIcon> icons = new HashMap<>();
final JPanel bar = new JPanel(new BorderLayout(4, 0));
final JButton scope;
final JPanel body = stack(PANEL, GAP);
final IconTextField search = new IconTextField();
final Table gains = new Table("GAINS");
final Table costs = new Table("LOSSES");
final Table market = new Table("MARKET");
final Table deaths = new Table("DEATHS");
final Table pending = new Table("PENDING");
final Table review = new Table("REVIEW");
/** The group-detail receipt list; persistent so its pager survives refreshes (F07). */
final Table detailReceipts = new Table("RECEIPTS");
/** The scope/session/group the detail receipts belong to; a change restarts its pager. */
String detailReceiptsFor = "";
final Table corrected = new Table("CORRECTED");
final JPanel detail = stack(PANEL, GAP);
Ao data;
boolean searchOpen;
boolean quietSearch;
boolean showCorrected;
String reviewSelected = "";
String deathSelected = "";
String anchored = "";
Ah pendingCorrection;
String pendingTransaction;
Ef pendingPreview;
/** A split waiting for Confirm: {itemId, keep, total}; null for a correction. */
long[] pendingSplit;
LedgerPage(Actions actions, Function<Integer, BufferedImage> sprites) {
 this.actions = actions;
 this.sprites = sprites == null ? id -> null : sprites;
 bar.setOpaque(false);
 scope = button("This Grind \u25be", false, () -> { });
 scope.addActionListener(e -> actions.openScopeMenu(scope));
 JButton find = button("Search", false, this::akg);
 find.setToolTipText("Search this scope");
 JButton more = button("\u00b7\u00b7\u00b7", false, () -> { });
 more.setToolTipText("Ledger actions");
 more.addActionListener(e -> awo().show(more, 0, more.getHeight()));
 // Owner 2026-10-01: one toolbar band shared with the Grinds page; actions sit on the right.
 bar.add(Kit.toolbar(new JComponent[] {scope}, new JComponent[] {find, more}), BorderLayout.NORTH);
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
 for (Table table : new Table[] {gains, costs, market, deaths, pending, review, corrected}) {
  table.setFoldable(true).setRowsPerPage(ROWS);
 }
 detailReceipts.setRowsPerPage(ROWS);
 costs.setPicker(auf(Bs.ALL, new int[4]));
 pending.setEmptyText("Nothing waiting");
 review.setEmptyText(msg("cp"));
 pad(body, 0, GAP, GAP, 0);
}

public String id() {
 return Shell.LEDGER;
}

public JComponent pageBar() {
 return bar;
}

public JComponent body() {
 return body;
}

/** Reset presentation-only state before another owner is shown. */
void agf() {
 ads();
 showCorrected = false;
 searchOpen = false;
 ahv("");
 for (Table table : new Table[] {gains, costs, market, deaths, pending, review, corrected}) {
  table.setRows(emptyList());
  table.avd();
 }
 detailReceiptsFor = "";
 detailReceipts.setRows(emptyList());
 detail.removeAll();
}

/** A new deep link replaces any previous local selection and preview. */
void ads() {
 anchored = "";
 ou();
 reviewSelected = "";
}

void apply(Ao next) {
 boolean atn = data == null || data.entry.scope != next.entry.scope
 || !String.valueOf(data.entry.historySessionId).equals(String.valueOf(next.entry.historySessionId));
 data = next;
 String selection = next.entry.scope.name() + "|" + next.entry.highlightGroupId + "|"
 + next.entry.highlightContributionId + "|" + next.entry.highlightTransactionId;
 if (!selection.equals(anchored)) {
  anchored = selection;
  // A preview belongs to one receipt; opening another cancels it.
  if (pendingTransaction != null && !pendingTransaction.equals(next.entry.highlightTransactionId)) {
   ou();
  }
 }
 scope.setText(next.scopeName + " \u25be");
 // Owner 2026-10-01 (F03): Runs today is whole runs that started today, never calendar-day money.
 scope.setToolTipText(next.entry.scope == Ao.Scope.TODAY
 ? msg("es") : null);
 if (atn) {
  for (Table table : new Table[] {gains, costs, market, deaths, pending, review, corrected}) table.avd();
 }
 body.removeAll();
 searchOpen = searchOpen || !next.entry.search.isEmpty();
 if (searchOpen) {
  if (!search.getText().equals(next.entry.search)) ahv(next.entry.search);
  body.add(search);
 }
 if (next.entry.scope == Ao.Scope.TODAY) {
  // Owner 2026-10-01 (F03): whole runs that started today, never calendar-day money.
  body.add(note(msg("fn"), LABEL));
 }
 if (next.readOnly) body.add(note(msg("jb"), LABEL));
 if (next.compacted && !next.detailedHistoryAvailable) body.add(note(msg("fq"), LABEL));
 if (next.detail != null) {
  mw(next);
  body.add(detail);
 } else {
  overview(next);
 }
 body.revalidate();
 body.repaint();
}

// ── overview ─────────────────────────────────────────────────────────────────────────────
void overview(Ao d) {
 fill(gains, "GAINS", d.gains.groups, d.gains.total, d.gains.incomplete, Tone.GAIN);
 costs.setPicker(auf(d.entry.costView, d.costCounts));
 fill(costs, "LOSSES", d.costs.groups, d.costs.total, d.costs.incomplete,
 d.entry.costView == Bs.LOSS ? LOSS : SUPPLY);
 var settled = new ArrayList<Group>();
 var open = new ArrayList<Group>();
 long aqk = 0L;
 for (Group group : d.market.groups) {
  var row = d.zu(group.marketPresentationId);
  if (Dc.zv(group, row)) {
   settled.add(group);
   aqk += group.value;
  // An offer canceled before anything filled moved no money: it leaves Pending.
  } else if (row == null || row.lifecycle != Bi.Lifecycle.CANCELLED_RETURNED) {
   open.add(group);
  }
 }
 fill(market, "MARKET", settled, aqk, d.market.incomplete, MARKET);
 body.add(gains);
 body.add(costs);
 body.add(market);
 if (!d.deaths.isEmpty()) {
  Death alh = null;
  long lost = 0L;
  var rows = new ArrayList<Row>();
  for (Death death : d.deaths) {
   lost += death.value;
   boolean selected = death.transactionId.equals(deathSelected);
   if (selected) alh = death;
   Runnable apk = () -> {
    deathSelected = selected ? "" : death.transactionId;
    apply(data);
   };
   rows.add(new Row("death|" + death.transactionId, null, death.place, when(death.at, d.capturedAt),
   signed(death.value), LOSS, death.place + msg("fr"),
   ru(death.value) + " gp", selected, apk,
   Arrays.asList(new Menu("Details", apk), new Menu("Open receipt",
   () -> actions.selectionChanged(death.transactionId, null, null)))));
  }
  deaths.setTitle("DEATHS · " + rows.size()).setTotal(signed(lost), LOSS)
  .setMinRows(rows.size() > ROWS ? ROWS : 0).setRows(rows);
  body.add(deaths);
  if (alh != null) body.add(sa(alh));
 }
 if (!open.isEmpty() || !d.pendingItems.isEmpty()) {
  var rows = new ArrayList<Row>();
  for (Group group : open) rows.add(awh(group, DIM));
  for (Ao.PendingItem item : d.pendingItems) {
   rows.add(new Row("pending|" + item.name + "|" + item.itemId, icon(item.itemId), item.name,
   "×" + exact(item.quantity), "", DIM, item.tip, "", false, null, emptyList()));
  }
  pending.setTitle("PENDING · " + rows.size()).setTotal("", DIM).setMinRows(rows.size() > ROWS ? ROWS : 0).setRows(rows);
  body.add(pending);
 }
 if (d.review.scopeCount > 0 || d.entry.focusAudit == Audit.REVIEW) nu(d);
 if (showCorrected || d.entry.focusAudit == Audit.CORRECTED) {
  var rows = new ArrayList<Row>();
  for (Receipt receipt : d.corrected) rows.add(aen(receipt));
  corrected.setTitle("CORRECTED · " + d.corrected.size()).setEmptyText(msg("bg")).setRows(rows);
  body.add(corrected);
 }
}

void fill(Table table, String title, List<Group> groups, long total, boolean incomplete, Tone tone) {
 var rows = new ArrayList<Row>();
 for (Group group : groups) {
  // Under All, loss rows keep their own colour beside supplies.
  rows.add(awh(group, tone == SUPPLY && group.table == Br.Table.COSTS_LOSS ? LOSS : tone));
 }
 table.setTitle(title + " · " + groups.size() + (incomplete ? " · incomplete" : ""))
 .setTotal(groups.isEmpty() || tone == DIM ? "" : signed(total), tone).setMinRows(rows.size() > ROWS ? ROWS : 0)
 .setRows(rows);
 table.setToolTipText(incomplete ? msg("ae") : null);
}

/** Owner 1.1: the Losses view is a dropdown beside the header (All, Supplies, Items, Charges). */
JButton auf(Bs selected, int[] counts) {
 var pick = new JButton(selected.label + " \u25be");
 pick.setFont(small());
 pick.setForeground(selected == Bs.ALL ? LABEL : ACCENT);
 pick.setBorder(BorderFactory.createEmptyBorder());
 pick.setContentAreaFilled(false);
 pick.setFocusPainted(false);
 pick.getAccessibleContext().setAccessibleName("Losses filter: " + selected.label);
 pick.addActionListener(e -> menu(selected, counts).show(pick, 0, pick.getHeight()));
 return pick;
}

JPopupMenu menu(Bs selected, int[] counts) {
 var menu = new JPopupMenu();
 for (Bs view : Bs.values()) {
  menu.add(item((view == selected ? "\u2713 " : "") + view.label
  + (view == Bs.ALL ? "" : " " + exact(counts[view.ordinal()])), () -> {
   if (view != selected) actions.costViewChanged(view);
  }));
 }
 return menu;
}

Row awh(Group group, Tone tone) {
 String value;
 Tone shown = tone;
 String meta = Dc.auo(group);
 String tip = group.primaryName + " · " + (group.receiptCount > 1
 ? (meta.isEmpty() ? "" : meta + " · ") + group.receiptCount + " receipts" : meta);
 if (group.reviewRequired) {
  value = "?";
  shown = REVIEW;
 } else if (group.market) {
  Bi.Row row = data.zu(group.marketPresentationId);
  if (Dc.zv(group, row)) {
   value = signed(Dc.zw(data, group));
   tip = group.primaryName + " · " + Dc.aag(data, group) + msg("jc");
  } else {
   value = Dc.avj(row);
   shown = DIM;
   tip = group.primaryName + " · " + group.qe;
  }
 } else if (group.incomplete()) {
  value = "?";
  shown = DIM;
  tip = group.primaryName + " · incomplete value coverage";
 } else {
  value = signed(group.value);
 }
 String qty = group.claim < 0 ? "\u2212" + Math.max(1L, group.quantity)
 : group.quantity > 1L && (!group.actionGroup() || group.chargeUse) ? times(group.quantity) : "";
 Runnable open = () -> auz(group.semanticGroupId);
 return new Row(group.semanticGroupId, icon(group.itemId), group.primaryName
 + (group.corrected ? " •" : ""), qty, value, shown, tip,
 group.incomplete() || group.market ? "" : ru(group.value) + " gp", false, open,
 singletonList(new Menu("Open", open)));
}

void auz(String groupId) {
 ou();
 actions.selectionChanged(null, null, groupId);
}

Row aen(Receipt receipt) {
 Runnable open = () -> actions.selectionChanged(receipt.transactionId, receipt.contributionId, null);
 return new Row(receipt.contributionId, icon(receipt.itemId), receipt.itemName,
 receipt.quantity > 1L ? times(receipt.quantity) : "",
 receipt.unpriced ? "?" : signed(receipt.value), receipt.unpriced ? DIM : of(receipt.category),
 receipt.itemName + " · " + receipt.qj + " · " + receipt.why,
 receipt.unpriced ? "" : ru(receipt.value) + " gp", false, open, singletonList(new Menu("Open", open)));
}

void nu(Ao d) {
 var rows = new ArrayList<Row>();
 Cu chosen = null;
 for (Cu row : d.review.rows) {
  String name = row.items.isEmpty() ? "Unknown item" : row.items.get(0).name;
  boolean selected = row.transactionId.equals(reviewSelected);
  if (selected) chosen = row;
  Runnable open = () -> {
   reviewSelected = selected ? "" : row.transactionId;
   apply(data);
  };
  rows.add(new Row(row.transactionId, null, name, "", "?", REVIEW,
  name + " · " + row.why, ru(row.value) + " gp", selected, open, singletonList(new Menu("Decide…", open))));
 }
 JButton all = button("Decide all…", false, () -> { });
 all.setToolTipText(msg("x") + d.review.rows.size() + " of " + d.review.scopeCount + ")");
 all.setEnabled(!d.review.rows.isEmpty() && !d.readOnly);
 all.addActionListener(e -> qv().show(all, 0, all.getHeight()));
 var bulk = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
 bulk.setOpaque(false);
 bulk.add(all);
 bulk.add(label(d.review.rows.size() + " of " + d.review.scopeCount, small(), LABEL));
 // Owner 2026-10-01 (F05): Decide all gets its own line; the header keeps its pager.
 review.setTitle("(!) REVIEW · " + d.review.scopeCount).setAction(null)
 .setExtra(d.readOnly || d.review.rows.isEmpty() ? null : bulk).setEmptyText(d.readOnly ? msg("jd")
 : d.review.scopeCount == 0 ? msg("cp") : msg("bc")).setRows(rows);
 body.add(review);
 if (chosen != null && !d.readOnly) body.add(qw(chosen));
}

/** One death opened in place, as in the death mock: Lost, Kept, then the costs of dying. */
JComponent sa(Death death) {
 JPanel panel = stack(CARD, 4);
 pad(panel, 4, GAP, 6, GAP);
 panel.add(note(aug(death, data), LABEL));
 long ame = 0L;
 var lost = new ArrayList<Row>();
 for (Ab flow : death.lost) {
  if (flow.quantityDelta < 0L) {
   ame += flow.valueDelta;
   lost.add(axq("lost", flow, LOSS, true));
  }
 }
 var kept = new ArrayList<Row>();
 for (Ab flow : death.kept) {
  if (flow.quantityDelta < 0L) kept.add(axq("kept", flow, DIM, false));
 }
 if (!lost.isEmpty()) panel.add(new Table("LOST").setTotal(signed(ame), LOSS).setRows(lost));
 if (!kept.isEmpty()) panel.add(new Table("KEPT").setRows(kept));
 var costs = new Dd("COSTS OF DYING");
 costs.put("Items lost", signed(ame), ame < 0L ? LOSS : DIM);
 costs.put("Reclaim fee", signed(-death.fees), death.fees > 0L ? LOSS : DIM);
 costs.put("Total", signed(death.value), death.value < 0L ? LOSS : PLAIN);
 panel.add(costs);
 panel.add(note(msg("fy"), LABEL));
 return panel;
}

/** The death mock's header line: when, which kind, and whether a reclaim still waits. */
static String aug(Death death, Ao d) {
 boolean waiting = d != null && !death.kept.isEmpty() && d.pendingItems.stream()
 .anyMatch(item -> item.itemId == 0 && "Death reclaim".equals(item.name));
 return (when(death.at, d == null ? death.at : d.capturedAt) + " · "
 + (death.place.endsWith("PvP") ? "PvP" : "PvM") + (waiting ? " · reclaim waiting" : "")).toUpperCase(Locale.ROOT);
}

Row axq(String kind, Ab flow, Tone tone, boolean valued) {
 long quantity = abs(flow.quantityDelta);
 return new Row(kind + "|" + flow.itemId, icon(flow.itemId), flow.itemName,
 quantity > 1L ? times(quantity) : "", valued ? signed(flow.valueDelta) : "0", tone,
 flow.itemName, valued ? ru(flow.valueDelta) + " gp" : "", false, null, emptyList());
}

/** The inline decision panel under a selected review row: what it is, why, then the choices. */
JComponent qw(Cu row) {
 JPanel panel = stack(CARD, 4);
 pad(panel, 4, GAP, 6, GAP);
 panel.add(note(row.why, LABEL));
 String preview = abx(row, data == null ? 0L : data.net);
 if (!preview.isEmpty()) panel.add(note(preview, TEXT));
 var buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
 buttons.setOpaque(false);
 for (Cl decision : Cl.values()) {
  if (row.validDecisions.contains(decision)) {
   buttons.add(button(ahh(decision), false, () -> {
    reviewSelected = "";
    actions.decide(row.transactionId, decision);
   }));
  }
 }
 panel.add(buttons);
 return panel;
}

/** What Net becomes under a gain or a cost decision; nothing counts until the owner decides. */
static String abx(Cu row, long net) {
 long size = abs(row.value);
 if (size == 0L) return "";
 var text = new StringBuilder();
 if (row.validDecisions.contains(Cl.GAIN)) {
  text.append("If counted as a gain: Net ").append(signed(net + size));
 }
 if (row.validDecisions.contains(Cl.COST)) {
  text.append(text.length() == 0 ? "If counted as a cost: Net " : " · as a cost: Net ").append(signed(net - size));
 }
 return text.toString();
}

JPopupMenu qv() {
 var menu = new JPopupMenu();
 for (Cl decision : Cl.values()) {
  menu.add(item("Decide all as " + ahh(decision), () -> actions.decideAll(decision)));
 }
 return menu;
}

JPopupMenu awo() {
 var menu = new JPopupMenu();
 menu.add(item(showCorrected ? "Hide corrected" : "Show corrected"
 + (data == null ? "" : " (" + data.corrected.size() + ")"), () -> {
  showCorrected = !showCorrected;
  if (data != null) apply(data);
 }));
 // Owner 2026-10-01 (F01): undo mutates the live run only; read-only scopes disable it.
 boolean editable = data != null && !data.readOnly && !actions.pinMenuTarget().isEmpty();
 menu.add(item(msg("ez"), editable, actions::undoCorrection));
 menu.addSeparator();
 menu.add(item("Undo last change", editable, actions::undoLast));
 menu.add(item("Restore last undo", editable, actions::restoreUndo));
 return menu;
}

// ── detail: one group's receipts; the selected receipt opens in place ────────────────────
void mw(Ao d) {
 detail.removeAll();
 Detail open = d.detail;
 Group group = open.group;
 detail.add(back("Ledger", () -> actions.selectionChanged(null, null, null)));
 long latest = group.latestActivityAt;
 for (Receipt receipt : open.receipts) latest = max(latest, receipt.at);
 int count = group.market ? open.receipts.size() : group.receiptCount;
 detail.add(title(group.primaryName, group.incomplete() ? "Incomplete" : ru(group.value) + " gp",
 group.incomplete() ? DIM : sign(group.value), (count == 0 ? "" : count
 + (group.market ? count == 1 ? " trade · " : " trades · " : count == 1 ? " receipt · " : " receipts · "))
 + "Latest " + age(nonNeg(d.capturedAt - latest))));
 String receiptsFor = d.entry.scope.name() + "|" + String.valueOf(d.entry.historySessionId)
 + "|" + group.semanticGroupId;
 if (!receiptsFor.equals(detailReceiptsFor)) {
  // Owner 2026-10-01 (F07): a new group starts at the first page; refreshes keep theirs.
  detailReceiptsFor = receiptsFor;
  detailReceipts.axa(0);
 }
 receipts(d, open);
 // An offer with nothing collected yet has no receipt to show or correct; none is invented.
 detail.add(open.receipts.isEmpty() ? note(msg("cl"), LABEL) : detailReceipts);
 if (open.exact != null) detail.add(receipt(d, open.exact));
}

/** Every receipt in the group: a trade each for Market, one canonical transaction each otherwise. */
void receipts(Ao d, Detail open) {
 Group group = open.group;
 Receipt chosen = open.exact;
 var rows = new ArrayList<Row>();
 if (group.market) {
  for (Receipt receipt : open.receipts) {
   boolean realized = Dc.zq(receipt);
   long primary = Dc.zx(receipt);
   rows.add(aen(d, group, receipt.transactionId, receipt.contributionId,
   chosen != null && chosen.contributionId.equals(receipt.contributionId),
   Dc.aab(receipt), realized ? signed(primary)
   : Dc.avj(receipt.marketSettlement), realized ? MARKET : DIM,
   Dc.aaa(receipt, d.capturedAt)
   + (receipt.corrected ? " · Result includes a manual correction." : ""),
   realized ? ru(primary) + " gp" : "", splittable(receipt)));
  }
 } else {
  for (Ao.Card card : Ao.cards(group)) {
   boolean split = false;
   for (Receipt receipt : open.receipts) {
    split |= receipt.transactionId.equals(card.transactionId) && splittable(receipt);
   }
   rows.add(aen(d, group, card.transactionId, card.contributionId,
   chosen != null && chosen.transactionId.equals(card.transactionId), group.primaryName,
   card.incomplete ? "?" : signed(card.value), card.incomplete ? DIM : sign(card.value),
   card.summaryText() + " · " + age(nonNeg(d.capturedAt - card.at))
   + (card.incomplete ? " · known value " + ru(card.knownSubtotal) + " gp" : ""),
   card.incomplete ? "" : ru(card.value) + " gp", split));
  }
 }
 detailReceipts.setTitle("RECEIPTS · " + rows.size()).setRows(rows);
}

Row aen(Ao d, Group group, String transactionId,
String contributionId, boolean selected, String name, String value, Tone tone, String tip, String exact,
boolean splittable) {
 Runnable open = () -> clickReceipt(transactionId, contributionId);
 var menus = new ArrayList<Menu>();
 if (!d.readOnly) {
  corrections(transactionId, contributionId, splittable).forEach((label, action) -> menus.add(new Menu(label, action)));
 }
 return new Row(contributionId, icon(group.itemId), name, "", value, tone, tip, exact, selected, open, menus);
}

static boolean splittable(Receipt receipt) {
 // Owner 2026-10-01 (F02): a single unit cannot be split into shares.
 return !receipt.neutral && !receipt.unpriced && receipt.gain() && receipt.quantity >= 2L;
}

void auy(String transactionId, String contributionId) {
 actions.selectionChanged(transactionId, contributionId, null);
}

/** A receipt row opens its receipt under the list; clicking the open one closes it again. */
void clickReceipt(String transactionId, String contributionId) {
 Detail open = data.detail;
 Receipt chosen = open.exact;
 if (chosen != null && (open.group.market ? chosen.contributionId.equals(contributionId)
 : chosen.transactionId.equals(transactionId))) {
  actions.selectionChanged(null, null, open.group.semanticGroupId);
 } else {
  auy(transactionId, contributionId);
 }
}

/** The selected receipt under its list: why, short GE facts, then its correction. */
JComponent receipt(Ao d, Receipt row) {
 JPanel panel = stack(CARD, 4);
 pad(panel, 4, GAP, 6, GAP);
 Bi.Row trade = row.marketSettlement;
 boolean realized = trade != null && trade.isRealizedIncluded() && trade.settledQty > 0L;
 // Why only earns its space for exceptional states; a clean realized trade says Sold / Bought.
 boolean clean = realized && trade.settledQty >= trade.filledQty && !row.review && !row.corrected && !row.split;
 // The selected row above already names the receipt and its value: say what happened, and when.
 panel.add(note(realized ? (trade.side == Aa.Side.SELL ? "Sold" : "Bought") + " · " + age(nonNeg(d.capturedAt
 - (trade.settlementAtEpochMillis > 0L ? trade.settlementAtEpochMillis : row.at)))
 : trade != null ? Dc.acp(trade) + " · " + age(nonNeg(d.capturedAt - trade.timestampEpochMillis))
 : row.verb + (row.actionDisplayName.isEmpty() ? "" : " · " + row.actionDisplayName)
 + " · " + age(nonNeg(d.capturedAt - row.at)), TEXT));
 var facts = new Dd(null);
 if (trade == null) {
  panel.add(note(row.why, LABEL));
  // Owner 2026-10-01 (F11): a Charges receipt discloses its captured unit price and source.
  if (!row.priceBasis.isEmpty()) facts.put("Price", row.priceBasis, PLAIN, row.priceBasis);
 } else {
  facts = zt(trade, row, clean ? null : realized ? row.why : Dc.awu(trade));
 }
 if (row.corrected || row.split) {
  facts.put("Originally", "automatic · " + ru(row.automaticValue) + " gp", DIM);
  facts.put("Corrected to", row.qj, PLAIN);
 }
 if (facts.lines() > 0) panel.add(facts);
 if (d.readOnly) {
  panel.add(note(msg("je"), LABEL));
 } else if (pendingPreview != null && row.transactionId.equals(pendingTransaction)) {
  var preview = new Dd("PREVIEW");
  if (pendingSplit != null) {
   preview.put("Keep", "×" + exact(pendingSplit[1]), PLAIN)
   .put("Others", "×" + exact(pendingSplit[2] - pendingSplit[1]), PLAIN);
  }
  panel.add(preview.put("Current Net", ru(pendingPreview.currentNet) + " gp", PLAIN)
  .put(pendingSplit != null ? "After split" : "After correction", ru(pendingPreview.afterNet) + " gp", PLAIN)
  .put("Change", ru(pendingPreview.change) + " gp", sign(pendingPreview.change)));
  panel.add(note(pendingPreview.note(), LABEL));
  JButton confirm = button("Confirm", true, this::confirm);
  confirm.getAccessibleContext().setAccessibleName("Confirm correction");
  panel.add(row(button("Cancel", false, () -> {
   ou();
   actions.refresh();
  }), confirm));
 } else {
  JButton correct = button("Correct ▾", false, () -> { });
  correct.getAccessibleContext().setAccessibleName(msg("fa"));
  correct.addActionListener(e -> correctMenu(row).show(correct, 0, correct.getHeight()));
  panel.add(row(correct));
 }
 return panel;
}

JPopupMenu correctMenu(Receipt row) {
 var menu = new JPopupMenu();
 corrections(row.transactionId, row.contributionId, splittable(row))
 .forEach((label, action) -> menu.add(item(label, action)));
 return menu;
}

/** The human Market lines with their explanations; "Why" leads when the state is exceptional. */
static Dd zt(Bi.Row trade, Receipt row, String why) {
 var facts = new Dd("MARKET");
 if (why != null) facts.put("Why", why, PLAIN, why);
 for (String line : Dc.zs(trade, row.corrected)) {
  int split = line.indexOf('|');
  String label = split < 0 ? line : line.substring(0, split);
  String value = split < 0 ? "" : line.substring(split + 1);
  facts.put(label, value, PLAIN, aut(trade, label, value));
 }
 return facts;
}

static String aut(Bi.Row trade, String label, String value) {
 if (value.endsWith("Corrected")) return msg("am");
 switch (label) {
  case "Received":
  return "not proven".equals(value) ? msg("hk") : msg("hj");
  case "Spent":
  return msg("hm");
  case "GE tax":
  return msg("hh");
  case "Previously counted":
  return "Unknown".equals(value) ? msg("hi") : null;
  case "Result":
  if ("—".equals(value)) {
   return msg("jf") + msg("jg") + (trade.knownCostOnly ? msg("hn") : "");
  }
  return value.endsWith("Partial") ? msg("hl") : null;
  case "GE difference":
  String reference = trade.geReferenceGp >= 0L
  ? " The frozen after-tax reference is " + exact(trade.geReferenceGp) + " gp across "
  + exact(trade.settledQty) + " collected" + (trade.expectedReferenceTaxGp > 0L ? " after "
  + exact(trade.expectedReferenceTaxGp) + " gp expected GE tax" : "") + "." : "";
  return msg("jh") + "proceeds." + reference + " It does not affect Net.";
  default:
  return null;
 }
}

/**
* Gain / Cost / Transfer / Ignore, then Split and Undo, for one receipt: the same list in its
* Correct ▾ menu and its row menu. A correction opens its Net preview on that receipt first.
*/
Map<String, Runnable> corrections(String transactionId, String contributionId, boolean splittable) {
 var list = new LinkedHashMap<String, Runnable>();
 for (Ah correction : new Ah[] {Ah.REVENUE,
  Ah.COST, Ah.TRANSFER, Ah.IGNORE}) {
  list.put(qj(correction), () -> {
   pendingCorrection = correction;
   pendingTransaction = transactionId;
   pendingPreview = actions.preview(transactionId, correction);
   auy(transactionId, contributionId);
  });
 }
 if (splittable) {
  list.put("Split…", () -> actions.split(transactionId, (split, preview) -> {
   ou();
   pendingTransaction = transactionId;
   pendingSplit = split;
   pendingPreview = preview;
   auy(transactionId, contributionId);
  }));
 }
 list.put(msg("fb"), actions::undoCorrection);
 return list;
}

void ou() {
 pendingCorrection = null;
 pendingTransaction = null;
 pendingPreview = null;
 pendingSplit = null;
}

void confirm() {
 if (pendingSplit != null && pendingPreview != null) {
  actions.applySplit(pendingTransaction, pendingSplit, pendingPreview.revision);
  ou();
  actions.refresh();
  return;
 }
 if (pendingCorrection == null || pendingPreview == null || pendingTransaction == null) return;
 Ea outcome = actions.correct(pendingTransaction, pendingCorrection, pendingPreview.revision);
 ou();
 actions.refresh();
 if (outcome != Ea.APPLIED) {
  aiu(outcome == Ea.STALE ? msg("gz") : "That correction was not applied");
 }
}

static String qj(Ah correction) {
 return msg("correct-to." + correction, "Automatic");
}

static String ahh(Cl decision) {
 return msg("review." + decision, "Exclude");
}

void aiu(String text) {
 if (text != null && !text.isEmpty()) {
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
 card.add(label(name, bold(), Color.WHITE), BorderLayout.CENTER);
 card.add(label(value, bold(), tone.color), BorderLayout.EAST);
 card.add(label(sub, small(), LABEL), BorderLayout.SOUTH);
 return card;
}

ImageIcon icon(int itemId) {
 BufferedImage image = sprites.apply(itemId);
 if (image == null) return null;
 ImageIcon icon = icons.get(itemId);
 if (icon == null || icon.getImage() != image) {
  icon = new ImageIcon(image);
  icons.put(itemId, icon);
 }
 return icon;
}

void akg() {
 searchOpen = !searchOpen;
 if (!searchOpen) ahv("");
 actions.searchChanged(search.getText());
}

void searched() {
 if (!quietSearch) actions.searchChanged(search.getText());
}

void ahv(String text) {
 quietSearch = true;
 search.setText(text);
 quietSearch = false;
}
}
