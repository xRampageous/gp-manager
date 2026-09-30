package com.gpmanager;
import com.gpmanager.Bp.Ax;
import com.gpmanager.Bi.*;
import com.gpmanager.Br.*;
import com.gpmanager.Br.Table;
import java.time.*;
import java.util.*;
import net.runelite.api.gameval.ItemID;
import lombok.*;
import static com.gpmanager.Bi.Lifecycle.*;
import static java.util.Locale.*;
import static java.util.Collections.*;
import static com.gpmanager.Ae.*;
import static com.gpmanager.Ak.msg;
/**
* The stacked-table Ledger read model: three independently paginated financial tables (Gains,
* Costs with a local Loss/Supplies selector, Market) built from the shared semantic projection,
* plus compact Review/Corrected audit lists and a reconciled Total block.
*
* <p>Search runs against COMPLETE semantic groups: a group that owns a matching underlying
* resource or receipt is kept whole, so a header total never becomes a partial child total.
* Correction targets are always exact canonical contribution receipts, never a group.</p>
*/
@AllArgsConstructor
class Ao {
enum Scope {
CURRENT_GRIND("Current Grind"), TODAY("Runs today"), HISTORY("Recent Grind");
final String label;
Scope(String label) { this.label = label; }
public String toString() { return label; }
}
/** The Losses table's chips (owner 2026-09-28): LOSS is shown as Items; Charges are measured charge use. */
enum Bs {
ALL("All"), SUPPLIES("Supplies"), LOSS("Items"), CHARGES("Charges");
final String label;
Bs(String label) { this.label = label; }
public String toString() { return label; }
}
/** A not-yet-settled count in Pending: an unopened loot key or chest, or a death reclaim. */
@AllArgsConstructor
static class PendingItem {
final int itemId;
final String name;
final long quantity;
final String tip;
}
/** One death in scope: what dying cost, opened as its receipt. */
@AllArgsConstructor
static class Death {
final String transactionId;
final String place;
final long at;
final long value;
/** Items the death cost, from the death-loss receipt. */
final List<Ab> lost;
/** Items held at the grave or retrieval service: ownership-neutral, not counted. */
final List<Ab> kept;
/** Reclaim and retrieval fees booked for this death. */
final long fees;
}
/** Compact audit subsections; never peer financial tabs. */
enum Audit {
REVIEW("Review"), CORRECTED("Corrected");
final String label;
Audit(String label) { this.label = label; }
public String toString() { return label; }
}
/** Raw Market settlement row for a semantic group's presentation id, or null. */
Row zu(String presentationId) {
return presentationId == null || presentationId.isEmpty()
? null : marketRows.get(presentationId);
}
/** Presentation-only Ledger query state; it never enters SavedState. */
static class Entry {
final Scope scope;
public final String historySessionId;
public final String historyName;
final Bs costView;
final String search;
public final String highlightTransactionId;
public final String highlightContributionId;
public final String highlightGroupId;
/** Deep-link request to expand one audit subsection. */
public final Audit focusAudit;
Entry(Scope scope, String historySessionId, String historyName) {
this(scope, historySessionId, historyName, Bs.ALL, "", null, null, null, null);
}
Entry(Scope scope, String historySessionId, String historyName,
Bs costView, String search, String highlightTransactionId,
String highlightContributionId, String highlightGroupId,
Audit focusAudit) {
this.scope = scope == null ? Scope.CURRENT_GRIND : scope;
this.historySessionId = historySessionId;
this.historyName = historyName;
this.costView = costView == null ? Bs.ALL : costView;
this.search = Ag.axw(search);
this.highlightTransactionId = highlightTransactionId;
this.highlightContributionId = highlightContributionId;
this.highlightGroupId = highlightGroupId;
this.focusAudit = focusAudit;
}
static Entry current() {
return new Entry(Scope.CURRENT_GRIND, null, null);
}
static Entry current(Audit audit, String transactionId) {
return new Entry(Scope.CURRENT_GRIND, null, null, Bs.ALL, "", transactionId,
null, null, audit);
}
Entry withCostView(Bs next) {
return new Entry(scope, historySessionId, historyName, next, search, null, null, null, null);
}
Entry withSearch(String next) {
return new Entry(scope, historySessionId, historyName, costView, next, null, null, null, null);
}
Entry withScope(Scope next, String sessionId, String name) {
return new Entry(next, sessionId, name, costView, search, null, null, null, null);
}
/** Selection stores only stable identity. */
Entry withSelection(String transactionId, String contributionId,
String groupId) {
return new Entry(scope, historySessionId, historyName, costView, search, transactionId,
contributionId, groupId, focusAudit);
}
}
/** One table's full filtered set and its header facts; the page table pages it. */
@AllArgsConstructor
static class Eb {
final List<Group> groups;
final long total;
final int entries;
final int receipts;
final boolean incomplete;
}
/** Review audit: exact review rows with the full eligible bulk count. */
@AllArgsConstructor
static class ReviewData {
final List<Cu> rows;
final int scopeCount;
}
/** The open detail: one semantic group, its exact receipts, and any selected exact receipt. */
@AllArgsConstructor
static class Detail {
final Group group;
final List<Receipt> receipts;
public final Receipt exact;
}
/** One resource inside a receipt: "Chaos rune ×4" on its line. */
@AllArgsConstructor
static class ResourceSummary {
final int itemId;
final String itemName;
final String usedBy;
final long quantity;
final long value;
/** Sum of only the known children; shown with explicit wording when incomplete. */
final long knownSubtotal;
final boolean incomplete;
}
/** One receipt row: exactly one canonical transaction inside the selected group. */
@AllArgsConstructor
static class Card {
final String transactionId;
final long at;
final long value;
final long knownSubtotal;
final boolean incomplete;
/** Representative contribution id for selecting the receipt. */
final String contributionId;
final List<ResourceSummary> resources;
/** "Chaos rune ×4 · Death rune ×2" for the row's tooltip. */
String summaryText() {
var text = new StringBuilder();
for (ResourceSummary resource : resources) {
if (text.length() > 0) {
text.append(" \u00b7 ");
}
text.append(resource.itemName);
if (resource.quantity > 1L) {
text.append(' ').append(Fmt.times(resource.quantity));
}
}
return text.toString();
}
}
/** Additive facts shared by a receipt card and each resource inside it. */
@RequiredArgsConstructor
static class ReceiptTotal {
final Receipt head;
long quantity;
long value;
long known;
long at;
boolean incomplete;
String representative = "";
void add(Receipt receipt) {
quantity += receipt.quantity;
value += receipt.value;
at = Math.max(at, receipt.at);
incomplete |= receipt.unpriced;
if (!receipt.unpriced) {
known += receipt.value;
}
if (representative.isEmpty()) {
representative = receipt.contributionId;
}
}
}
static class ReceiptCard extends ReceiptTotal {
final Map<String, ReceiptTotal> resources = new LinkedHashMap<>();
ReceiptCard(Receipt head) {
super(head);
}
void add(Receipt receipt) {
super.add(receipt);
resources.computeIfAbsent(receipt.itemId + "|" + receipt.usedBy,
ignored -> new ReceiptTotal(receipt)).add(receipt);
}
Card card() {
var summaries = new ArrayList<ResourceSummary>();
for (ReceiptTotal resource : resources.values()) {
Receipt r = resource.head;
summaries.add(new ResourceSummary(r.itemId, r.itemName, r.usedBy, resource.quantity,
resource.value, resource.known, resource.incomplete));
}
summaries.sort(Comparator.comparing((ResourceSummary summary) -> summary.itemName,
String.CASE_INSENSITIVE_ORDER)
.thenComparingInt(summary -> summary.itemId));
return new Card(head.transactionId, at, value, known, incomplete, representative, summaries);
}
}
/** One pass builds each transaction card and its resources; newest cards first. */
static List<Card> cards(Group group) {
if (group == null) {
return emptyList();
}
var alf = new LinkedHashMap<String, ReceiptCard>();
for (Receipt receipt : group.receipts) {
alf.computeIfAbsent(receipt.transactionId, ignored -> new ReceiptCard(receipt)).add(receipt);
}
var cards = new ArrayList<Card>(alf.size());
for (ReceiptCard card : alf.values()) {
cards.add(card.card());
}
cards.sort(Comparator.comparingLong((Card card) -> card.at).reversed()
.thenComparing(card -> card.transactionId));
return cards;
}
/** The query this data answers: scope, Costs view, search and the selected receipt. */
final Entry entry;
final String scopeName;
final boolean readOnly;
final boolean compacted;
final boolean detailedHistoryAvailable;
final long net;
final long revision;
/** Capture time for honest ages without leaking the engine. */
final long capturedAt;
final Eb gains;
final Eb costs;
final Eb market;
final ReviewData review;
final List<Receipt> corrected;
final Summary total;
final boolean reconciled;
public final Detail detail;
/** Raw Market settlement rows by presentation id; presentation-only lookup for value semantics. */
final Map<String, Row> marketRows;
/** Group counts for the Losses chips by {@link Bs} ordinal, whatever view is selected. */
final int[] costCounts;
/** Keys and reclaim waiting to settle; current scope only, counts and items, never GP. */
final List<PendingItem> pendingItems;
/** Deaths in scope, newest first; their losses also stay in Costs · Loss. */
final List<Death> deaths;
static Ao capture(Am engine, long now, Entry entry) {
Entry e = entry == null ? Entry.current() : entry;
List<Ad> sessions = aig(engine, e, now);
String scopeName = scopeName(engine, e);
var sessionIds = new LinkedHashSet<String>();
var transactions = new ArrayList<Ac>();
boolean compacted = false;
long aqg = 0L;
for (Ad session : sessions) {
if (session == null) {
continue;
}
sessionIds.add(session.getId());
Ad.CompactedContribution compact = session.pl();
aqg = safeAdd(aqg, compact.getNet());
for (Ac transaction : session.getTransactions()) {
if (transaction != null) {
transactions.add(transaction);
Ax amounts =
Bp.transaction(transaction);
if (amounts.included) {
aqg = safeAdd(aqg, amounts.getNet());
}
}
}
compacted |= compact.rows > 0 || compact.revenue != 0L || compact.costs != 0L;
}
List<Row> marketRows = engine.ub();
var marketByPresentation = new HashMap<String, Row>();
for (Row market : marketRows) {
if (market != null && sessionIds.contains(market.scopeSessionId)) {
marketByPresentation.put(market.presentationId, market);
}
}
Result projection =
Br.capture(transactions, marketRows, sessionIds, null);
var tables = new EnumMap<Table, List<Group>>(Table.class);
for (Table table : Table.values()) {
tables.put(table, new ArrayList<>());
}
for (Group group : projection.groups) {
if (!group.reviewRequired && group.matchesText(e.search)) {
tables.get(group.table).add(group);
}
}
List<Group> aog = tables.get(Table.GAINS);
List<Group> aqt = tables.get(Table.COSTS_SUPPLIES);
List<Group> asn = tables.get(Table.COSTS_LOSS);
List<Group> apd = tables.get(Table.MARKET);
var supplies = new ArrayList<Group>();
var charges = new ArrayList<Group>();
for (Group group : aqt) {
(group.chargeUse ? charges : supplies).add(group);
}
var ant = new ArrayList<Group>(e.costView == Bs.LOSS ? asn
: e.costView == Bs.CHARGES ? charges : supplies);
if (e.costView == Bs.ALL) {
// All is every list together, largest cost first.
ant.addAll(charges);
ant.addAll(asn);
ant.sort(Comparator.comparingLong(group -> group.value));
}
Eb gains = avm(aog, false);
Eb costs = avm(ant, false);
Eb market = avm(apd, true);
var financial = new ArrayList<Group>();
financial.addAll(aog);
financial.addAll(aqt);
financial.addAll(asn);
financial.addAll(apd);
Summary total = Br.summarize(financial);
// ── audit ────────────────────────────────────────────────────────────────────────────
var reviewRows = new ArrayList<Cu>();
int aqb = 0;
for (Cu ave : engine.getReviewRows(now)) {
if (ave != null && sessionIds.contains(ave.sessionId)) {
aqb++;
if (!readOnly(e) && aaf(ave, e.search)) {
reviewRows.add(ave);
}
}
}
var review = new ReviewData(reviewRows, aqb);
var alo = new ArrayList<Receipt>();
for (Group group : projection.groups) {
for (Receipt receipt : group.receipts) {
if ((receipt.corrected || receipt.split) && receipt.matchesSearch(e.search)) {
alo.add(receipt);
}
}
}
alo.sort(Comparator.comparingLong((Receipt receipt) -> receipt.at)
.reversed().thenComparing(receipt -> receipt.contributionId));
// ── selected detail ──────────────────────────────────────────────────────────────────
Detail detail = null;
String zm = e.highlightContributionId;
String ady = e.highlightTransactionId;
String anj = e.highlightGroupId;
if (anj != null || zm != null || ady != null) {
Group group = auj(projection.groups, anj,
zm, ady);
if (group != null) {
List<Receipt> receipts = group.receipts;
Receipt exact = null;
if (zm != null) {
for (Receipt receipt : receipts) {
if (receipt.contributionId.equals(zm)) {
exact = receipt;
break;
}
}
}
if (exact == null && ady != null) {
exact = tb(receipts, ady);
}
detail = new Detail(group, receipts, exact);
}
}
long net = aqg;
boolean detailedHistoryAvailable = !compacted || total.entries > 0;
boolean reconciled = total.coverageComplete
&& safeAdd(safeAdd(total.gains, total.costs), total.market) == net;
return new Ao(e, scopeName, readOnly(e), compacted, detailedHistoryAvailable,
net, engine.getRevision(), now,
gains, costs, market, review, alo,
total, reconciled, detail,
marketByPresentation, new int[] {0, supplies.size(), asn.size(), charges.size()}, pendingItems(engine, e),
deaths(transactions, e.search));
}
/**
* PvP deaths are their death-loss receipts. A PvM death is its gravestone wipe (a neutral
* transfer) plus the reclaim fees that follow it; a fee with no kept wipe row stands alone.
*/
static List<Death> deaths(List<Ac> transactions, String search) {
var ordered = new ArrayList<Ac>(transactions);
ordered.sort(Comparator.comparingLong((itemData -> itemData.timestampEpochMillis)));
var deaths = new ArrayList<Death>();
int pvm = -1;
for (Ac transaction : ordered) {
Ax amounts = Bp.transaction(transaction);
long value = amounts.included ? amounts.getNet() : 0L;
Ai type = transaction.tm();
if (type == Ai.PK_DEATH_LOSS) {
deaths.add(new Death(transaction.getId(), "Death · PvP", transaction.timestampEpochMillis, value,
new ArrayList<>(transaction.getFlows()), emptyList(), 0L));
} else if (type == Ai.TRANSFER && transaction.getNote().startsWith("Death:")) {
pvm = deaths.size();
deaths.add(new Death(transaction.getId(), "Death · PvM", transaction.timestampEpochMillis, 0L,
emptyList(), new ArrayList<>(transaction.getFlows()), 0L));
} else if (type == Ai.CONSUMPTION
&& "Death reclaim".equals(transaction.getActivityName())) {
if (pvm < 0) {
pvm = deaths.size();
deaths.add(new Death(transaction.getId(), "Death · PvM", transaction.timestampEpochMillis, 0L,
emptyList(), emptyList(), 0L));
}
Death death = deaths.get(pvm);
deaths.set(pvm, new Death(death.transactionId, death.place, death.at,
safeAdd(death.value, value), death.lost, death.kept,
safeAdd(death.fees, -value)));
}
}
var shown = new ArrayList<Death>();
for (Death death : deaths) {
if (search == null || search.isEmpty()
|| death.place.toLowerCase(ROOT).contains(search.toLowerCase(ROOT))) {
shown.add(death);
}
}
shown.sort(Comparator.comparingLong((Death death) -> death.at).reversed());
return shown;
}
static List<PendingItem> pendingItems(Am engine, Entry e) {
var items = new ArrayList<PendingItem>();
if (e.scope != Scope.CURRENT_GRIND || readOnly(e)) {
return items;
}
for (SavedState.By claim : engine.getPendingClaims()) {
if (claim == null || !claim.isValid()) {
continue;
}
KeyChestCatalogue.Entry chest =
KeyChestCatalogue.rq(claim.itemOrKeyId);
String name = chest != null ? chest.getChestName() + " key"
: claim.itemOrKeyId >= ItemID.WILDY_LOOT_KEY0
&& claim.itemOrKeyId <= ItemID.WILDY_LOOT_KEY4
? "Loot key" : "Key or chest";
items.add(new PendingItem(claim.itemOrKeyId, name, claim.getQuantity(),
name + " not opened yet · books normally when it is opened"));
}
DeathReclaimStatus reclaim = engine.tt();
if (reclaim.awaiting) {
items.add(new PendingItem(0, "Death reclaim", Math.max(1L, reclaim.outstandingItemCount),
msg("ha")));
}
return items;
}
/** Ordinary tables sum every group; Market's header includes only complete results. */
static Eb avm(List<Group> groups, boolean market) {
long total = 0L;
boolean incomplete = false;
var receipts = new LinkedHashSet<String>();
for (Group group : groups) {
incomplete |= group.incomplete();
if (!market || !group.incomplete()) {
total = safeAdd(total, group.value);
}
receipts.addAll(group.receiptIds);
}
return new Eb(groups, total, groups.size(), receipts.size(), incomplete);
}
/** True when a Market lifecycle is genuinely pending rather than already realized. */
static boolean wr(Lifecycle lifecycle) {
return lifecycle == PENDING
|| lifecycle == PARTIALLY_EXECUTED
|| lifecycle == EXECUTED_UNSETTLED
|| lifecycle == PARTIALLY_REALIZED
|| lifecycle == RESUMED;
}
static Group auj(
List<Group> groups, String groupId,
String contributionId, String transactionId) {
for (Group group : groups) {
if (group.reviewRequired) {
continue;
}
if (group.containsContribution(groupId) || group.containsContribution(contributionId)
|| group.containsTransaction(transactionId)) {
return group;
}
}
// Review-required groups are still openable from the audit list detail.
for (Group group : groups) {
if (group.containsContribution(contributionId) || group.containsTransaction(transactionId)) {
return group;
}
}
return null;
}
static Receipt tb(
List<Receipt> receipts, String transactionId) {
Receipt found = null;
for (Receipt receipt : receipts) {
if (receipt.transactionId.equals(transactionId)
&& (found == null || receipt.at >= found.at)) {
found = receipt;
}
}
return found;
}
static List<Ad> aig(Am engine, Entry entry, long now) {
var sessions = new ArrayList<Ad>();
if (entry.scope == Scope.HISTORY) {
Ad historical = entry.historySessionId == null
? null : engine.ua(entry.historySessionId);
if (historical != null) {
sessions.add(historical);
}
return sessions;
}
Ad active = engine.getActiveSession();
if (active != null) {
sessions.add(active);
}
if (entry.scope == Scope.TODAY) {
ZoneId zone = engine.uf();
LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
for (Ad historical : engine.getHistory()) {
if (historical == null || (active != null && historical.getId().equals(active.getId()))) {
continue;
}
LocalDate day = Instant.ofEpochMilli(historical.startedAtEpochMillis)
.atZone(zone).toLocalDate();
if (day.equals(today)) {
sessions.add(historical);
}
}
}
return sessions;
}
static String scopeName(Am engine, Entry entry) {
if (entry.scope == Scope.HISTORY) {
if (entry.historyName != null && !entry.historyName.isEmpty()) {
return entry.historyName;
}
Ad historical = entry.historySessionId == null
? null : engine.ua(entry.historySessionId);
return historical == null ? "Recent Grind" : historical.getName();
}
return entry.scope == Scope.TODAY ? "Runs today" : "Current Grind";
}
static boolean readOnly(Entry entry) {
return entry.scope != Scope.CURRENT_GRIND;
}
static boolean aaf(Cu row, String query) {
if (Ag.blank(query)) {
return true;
}
String needle = query.trim().toLowerCase(ROOT);
if (row.why != null && row.why.toLowerCase(ROOT).contains(needle)) {
return true;
}
for (Cu.Item item : row.items) {
if (item.name != null && item.name.toLowerCase(ROOT).contains(needle)) {
return true;
}
}
return false;
}
// ── correction support (unchanged semantics: exact canonical ids only) ─────────────────────
static class Ef {
final long currentNet;
final long afterNet;
final long change;
final long revision;
Ef(long currentNet, long afterNet, long revision) {
this.currentNet = currentNet;
this.afterNet = afterNet;
this.change = afterNet - currentNet;
this.revision = revision;
}
String note() { return change == 0L ? msg("fk") : "Net changes by "
+ Fmt.signed(change) + " gp."; }
}
static Ef preview(Am engine, String transactionId,
Ah correction, long now) {
Ad session = engine.getActiveSession();
long currentNet = engine.getMetrics(now).net;
if (session == null || transactionId == null || correction == null) {
return new Ef(currentNet, currentNet, engine.getRevision());
}
Ac transaction = session.sw(transactionId);
if (transaction == null) {
return new Ef(currentNet, currentNet, engine.getRevision());
}
long before = transaction.isCounted() ? transaction.getNet() : 0L;
long after = transaction.awp(correction);
return new Ef(currentNet, currentNet - before + after, engine.getRevision());
}
}
