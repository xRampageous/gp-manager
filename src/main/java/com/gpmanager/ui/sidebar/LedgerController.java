package com.gpmanager;
import com.gpmanager.LedgerPage.Ea;
import java.util.*;
import javax.swing.*;
import static com.gpmanager.Ag.*;
import static com.gpmanager.Ao.Scope.*;
import static java.lang.Math.*;
import static com.gpmanager.Kit.*;
import static com.gpmanager.Ak.msg;
/** Ledger actions: scope menu, search, selection, corrections, splits and review decisions. */
class LedgerController implements LedgerPage.Actions {
final Dp panel;
final Am engine;
LedgerController(Dp panel) {
this.panel = panel;
this.engine = panel.engine;
}
void mutate(Runnable domain) {
panel.mutate(domain);
}
void se(Ad session) {
panel.se(session);
}
public void openScopeMenu(JComponent anchor) {
panel.historyScopeMenuPage = 0;
ail(anchor, panel.historyScopeMenuPage);
}
void ail(JComponent anchor, int pageIndex) {
var menu = new JPopupMenu();
menu.add(item("Current Grind", () -> aia(CURRENT_GRIND, null, null)));
menu.add(item("Runs today", () -> aia(TODAY, null, null)));
var history = new ArrayList<Ad>(engine.getHistory());
history.removeIf(Objects::isNull);
history.sort(Comparator.comparingLong((Ad itemData) -> itemData.startedAtEpochMillis).reversed()
.thenComparing(Ad::getId));
if (!history.isEmpty()) {
menu.addSeparator();
int size = 10;
int pages = max(1, (history.size() + size - 1) / size);
panel.historyScopeMenuPage = max(0, min(pageIndex, pages - 1));
int from = panel.historyScopeMenuPage * size;
int to = min(history.size(), from + size);
var status = new JMenuItem("History " + (from + 1) + "–" + to + " of " + history.size());
status.setEnabled(false);
menu.add(status);
if (pages > 1) {
menu.add(item("Previous page", panel.historyScopeMenuPage > 0, () -> SwingUtilities.invokeLater(
() -> ail(anchor, panel.historyScopeMenuPage - 1))));
menu.add(item("Next page", panel.historyScopeMenuPage + 1 < pages, () -> SwingUtilities.invokeLater(
() -> ail(anchor, panel.historyScopeMenuPage + 1))));
menu.addSeparator();
}
for (Ad session : history.subList(from, to)) {
menu.add(item("Recent Grind \u00b7 " + session.getName(), () -> aia(HISTORY,
session.getId(), session.getName())));
}
}
menu.show(anchor, 0, anchor.getHeight() + 2);
}
void aia(Ao.Scope scope, String historyId,
String historyName) {
panel.ledgerEntry = panel.ledgerEntry.withScope(scope, historyId, historyName);
refresh();
}
public void costViewChanged(Ao.Bs view) {
panel.ledgerEntry = panel.ledgerEntry.withCostView(view);
refresh();
}
public void searchChanged(String text) {
panel.ledgerSearch = axw(text);
panel.ledgerEntry = panel.ledgerEntry.withSearch(panel.ledgerSearch);
refresh();
}
@Override
public void selectionChanged(String transactionId, String contributionId,
String groupId) {
String transaction = empty(transactionId) ? null : transactionId;
String contribution = empty(contributionId) ? null : contributionId;
String group = empty(groupId) ? null : groupId;
panel.ledgerEntry = panel.ledgerEntry.withSelection(transaction, contribution, group);
refresh();
}
public Ao.Ef preview(String transactionId, Ah correction) {
return Ao.preview(engine, transactionId, correction, System.currentTimeMillis());
}
public Ea correct(String transactionId, Ah correction,
long previewRevision) {
if (engine.getRevision() != previewRevision) {
return Ea.STALE;
}
boolean applied = engine.qi(transactionId, correction, System.currentTimeMillis(),
"Ledger correction");
return applied ? Ea.APPLIED
: Ea.REFUSED;
}
public void split(String transactionId) {
Ad session = engine.getActiveSession();
Ac transaction = session == null ? null : session.sw(transactionId);
int itemId = -1;
long total = 0L;
String itemName = "";
if (transaction != null) {
for (Ab flow : transaction.getFlows()) {
if (flow == null || flow.quantityDelta <= 0L) {
continue;
}
if (itemId == -1) {
itemId = flow.itemId;
itemName = flow.itemName;
} else if (itemId != flow.itemId) {
itemId = -1;
break;
}
total += flow.quantityDelta;
}
}
if (itemId <= 0 || total < 2L) {
// Owner 2026-10-01 (F02): only one kind of item in two or more units can be split.
panel.shell().tell("Not split",
transaction == null ? "that receipt is no longer here"
: "split needs one item with at least two units", true);
return;
}
int splitItem = itemId;
long splitTotal = total;
String splitName = itemName == null || itemName.isEmpty() ? "this item" : itemName;
String owner = session.getId();
panel.shell().prompt("Split receipt",
"Keep how many of " + splitName + " \u00d7" + total + " for your share?", "", value -> {
long keep;
try {
keep = Long.parseLong(value.replace(",", ""));
} catch (NumberFormatException ex) {
keep = -1L;
}
if (keep <= 0L || keep >= splitTotal) {
panel.shell().tell("Not split", "enter a number from 1 to " + (splitTotal - 1), true);
return;
}
long atq = keep;
mutate(() -> {
// The sheet may be stale: re-check the session and the exact receipt before mutating.
Ad now = engine.getActiveSession();
Ac current = now == null || !owner.equals(now.getId()) ? null : now.sw(transactionId);
if (!splittableSplit(current, splitItem, splitTotal)) {
panel.shell().tell("Not split", "that receipt changed; nothing was split", true);
return;
}
if (!engine.kr(transactionId, splitItem, atq, System.currentTimeMillis(), "Ledger split")) {
panel.shell().tell("Not split", "that split was not applied", true);
}
});
});
}
/** One item kind only, and the quantity captured at the prompt is still exact. */
static boolean splittableSplit(Ac transaction, int itemId, long total) {
if (transaction == null) {
return false;
}
int found = -1;
long sum = 0L;
for (Ab flow : transaction.getFlows()) {
if (flow == null || flow.quantityDelta <= 0L) {
continue;
}
if (found == -1) {
found = flow.itemId;
} else if (found != flow.itemId) {
return false;
}
sum += flow.quantityDelta;
}
return found == itemId && sum == total;
}
String menuTarget = "";
/** Pins an open mutation menu; the callback re-checks it against the live session. */
@Override
public String pinMenuTarget() {
Ad active = engine.getActiveSession();
menuTarget = active == null ? "" : active.getId();
return menuTarget;
}
/** Owner 2026-10-01 (F01): a stale menu never mutates; scope and session are re-checked here. */
boolean undoAllowed() {
Ad active = engine.getActiveSession();
return !Ao.readOnly(panel.ledgerEntry)
&& active != null && !menuTarget.isEmpty() && menuTarget.equals(active.getId());
}
public void undoCorrection() {
if (!undoAllowed()) {
return;
}
mutate(() -> engine.akb(System.currentTimeMillis()));
}
@Override
public void undoLast() {
if (!undoAllowed()) {
return;
}
mutate(() -> engine.akc(System.currentTimeMillis()));
}
@Override
public void restoreUndo() {
if (!undoAllowed()) {
return;
}
mutate(() -> engine.agn(System.currentTimeMillis()));
}
public void decideAll(Cl decision) {
decide(decision, row -> row != null && Ao.aaf(row, panel.ledgerSearch), false);
}
@Override
public void decide(String transactionId, Cl decision) {
decide(decision, row -> row != null && transactionId.equals(row.transactionId), true);
}
/** Previews one decision over the chosen Review rows, confirms, then applies it as one undoable batch. */
void decide(Cl decision, java.util.function.Predicate<Cu> rows,
boolean single) {
Am.DecideAllPreview preview = engine.adu(decision, rows, System.currentTimeMillis());
int count = preview.rowCount();
if (count == 0 || single && count != 1) {
return;
}
String message = qj(decision.ajo())
+ (single ? " for this receipt" : " for " + count + (count == 1 ? " receipt" : " receipts"))
+ ". Net changes by " + Fmt.signed(preview.netDelta) + " gp." + (single ? "" : " One Undo reverts the whole batch.");
panel.shell().confirm(single ? "Review decision" : "Decide all", message, "Apply", () -> mutate(() -> {
if (engine.kp(preview, System.currentTimeMillis()) < 0) {
SwingUtilities.invokeLater(() -> panel.ledger.aiu(
msg("gz")));
}
}));
}
@Override
public void exportCsv() {
se(panel.ledgerEntry.scope == HISTORY
? engine.ua(panel.ledgerEntry.historySessionId) : engine.getActiveSession());
}
public void refresh() {
panel.refresh();
}
static String qj(Ah correction) {
return LedgerPage.qj(correction);
}
}
