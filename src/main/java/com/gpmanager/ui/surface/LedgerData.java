package com.gpmanager;

import static com.gpmanager.GameData.msg;
import static com.gpmanager.MarketRows.Lifecycle.*;
import static com.gpmanager.Phrases.*;
import static com.gpmanager.SafeMath.*;
import static com.gpmanager.SafeMath.nonNeg;
import static java.util.Collections.*;
import static java.util.Locale.*;

import com.gpmanager.MarketRows.*;
import com.gpmanager.Semantics.*;
import com.gpmanager.Semantics.Table;
import java.time.*;
import java.util.*;
import lombok.*;
import net.runelite.api.gameval.ItemID;

/**
 * The stacked-table Ledger read model: three independently paginated financial tables (Gains, Costs
 * with a local Loss/Supplies selector, Market) built from the shared semantic projection, plus
 * compact Review/Corrected audit lists and a reconciled Total block.
 *
 * <p>Search runs against COMPLETE semantic groups: a group that owns a matching underlying resource
 * or receipt is kept whole, so a header total never becomes a partial child total. Correction
 * targets are always exact canonical contribution receipts, never a group.
 */
@AllArgsConstructor
class LedgerData {
    @RequiredArgsConstructor
    enum Scope {
        CURRENT_GRIND("Current Grind"),
        TODAY("Runs today"),
        HISTORY("Recent Grind");
        final String label;

        public String toString() {
            return label;
        }
    }

    /**
     * The Losses table's chips (owner 2026-09-28): LOSS is shown as Items; Charges are measured
     * charge use.
     */
    @RequiredArgsConstructor
    enum CostView {
        ALL("All"),
        SUPPLIES("Supplies"),
        LOSS("Items"),
        CHARGES("Charges");
        final String label;

        public String toString() {
            return label;
        }
    }

    /** A not-yet-settled count in Pending: an unopened loot key or chest, or a death reclaim. */
    @AllArgsConstructor
    static class PendingItem {
        int itemId;
        String name;
        long quantity;
        String tip;

        /** An observed open chest's exact worth; 0 when unseen, looted or unpriced. */
        long value;
    }

    /** Compact audit subsections; never peer financial tabs. */
    @RequiredArgsConstructor
    enum Audit {
        REVIEW("Review"),
        CORRECTED("Corrected");
        final String label;

        public String toString() {
            return label;
        }
    }

    /** Raw Market settlement row for a semantic group's presentation id, or null. */
    Row marketRow(String presentationId) {
        return presentationId == null || presentationId.isEmpty()
                ? null
                : marketRows.get(presentationId);
    }

    /** Presentation-only Ledger query state; it never enters SavedState. */
    static class Entry {
        Scope scope;
        public String historySessionId;
        public String historyName;
        CostView costView;
        String search;
        public String highlightTransactionId;
        public String highlightContributionId;
        public String highlightGroupId;

        /** Deep-link request to expand one audit subsection. */
        public Audit focusAudit;

        Entry(Scope scope, String historySessionId, String historyName) {
            this(scope, historySessionId, historyName, CostView.ALL, "", null, null, null, null);
        }

        Entry(
                Scope scope,
                String historySessionId,
                String historyName,
                CostView costView,
                String search,
                String highlightTransactionId,
                String highlightContributionId,
                String highlightGroupId,
                Audit focusAudit) {
            this.scope = scope == null ? Scope.CURRENT_GRIND : scope;
            this.historySessionId = historySessionId;
            this.historyName = historyName;
            this.costView = costView == null ? CostView.ALL : costView;
            this.search = Phrases.orEmpty(search);
            this.highlightTransactionId = highlightTransactionId;
            this.highlightContributionId = highlightContributionId;
            this.highlightGroupId = highlightGroupId;
            this.focusAudit = focusAudit;
        }

        static Entry current() {
            return new Entry(Scope.CURRENT_GRIND, null, null);
        }

        static Entry current(Audit audit, String transactionId) {
            return new Entry(
                    Scope.CURRENT_GRIND,
                    null,
                    null,
                    CostView.ALL,
                    "",
                    transactionId,
                    null,
                    null,
                    audit);
        }

        Entry withCostView(CostView next) {
            return new Entry(
                    scope, historySessionId, historyName, next, search, null, null, null, null);
        }

        Entry withSearch(String next) {
            return new Entry(
                    scope, historySessionId, historyName, costView, next, null, null, null, null);
        }

        Entry withScope(Scope next, String sessionId, String name) {
            return new Entry(next, sessionId, name, costView, search, null, null, null, null);
        }

        /** Selection stores only stable identity. */
        Entry withSelection(String transactionId, String contributionId, String groupId) {
            return new Entry(
                    scope,
                    historySessionId,
                    historyName,
                    costView,
                    search,
                    transactionId,
                    contributionId,
                    groupId,
                    focusAudit);
        }
    }

    /** One table's full filtered set and its header facts; the page table pages it. */
    @AllArgsConstructor
    static class TableData {
        List<Group> groups;
        long total;
        int entries;
        int receipts;
    }

    /** Review audit: exact review rows with the full eligible bulk count. */
    @AllArgsConstructor
    static class ReviewData {
        List<ReviewRow> rows;
        int scopeCount;
    }

    /** The open detail: one semantic group, its exact receipts, and any selected exact receipt. */
    @AllArgsConstructor
    static class Detail {
        Group group;
        List<Receipt> receipts;
        public Receipt exact;
    }

    /** One resource inside a receipt: "Chaos rune ×4" on its line. */
    @AllArgsConstructor
    static class ResourceSummary {
        int itemId;
        String itemName;
        String usedBy;
        long quantity;
        long value;

        /** Sum of only the known children; shown with explicit wording when incomplete. */
        long knownSubtotal;

        boolean incomplete;
    }

    /** One receipt row: exactly one canonical transaction inside the selected group. */
    @AllArgsConstructor
    static class Card {
        String transactionId;
        long at;
        long value;
        long knownSubtotal;
        boolean incomplete;

        /** Representative contribution id for selecting the receipt. */
        String contributionId;

        List<ResourceSummary> resources;

        /** "Chaos rune ×4 · Death rune ×2" for the row's tooltip. */
        String summaryText() {
            var text = new StringBuilder();
            for (ResourceSummary resource : resources) {
                if (text.length() > 0) text.append(" \u00b7 ");
                text.append(resource.itemName);
                if (resource.quantity > 1L) text.append(' ').append(Fmt.times(resource.quantity));
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
            if (!receipt.unpriced) known += receipt.value;
            if (representative.isEmpty()) representative = receipt.contributionId;
        }
    }

    static class ReceiptCard extends ReceiptTotal {
        Map<String, ReceiptTotal> resources = new LinkedHashMap<>();

        ReceiptCard(Receipt head) {
            super(head);
        }

        void add(Receipt receipt) {
            super.add(receipt);
            resources
                    .computeIfAbsent(
                            receipt.itemId + "|" + receipt.usedBy,
                            ignored -> new ReceiptTotal(receipt))
                    .add(receipt);
        }

        Card card() {
            var summaries = new ArrayList<ResourceSummary>();
            for (ReceiptTotal resource : resources.values()) {
                Receipt receipt = resource.head;
                summaries.add(
                        new ResourceSummary(
                                receipt.itemId,
                                receipt.itemName,
                                receipt.usedBy,
                                resource.quantity,
                                resource.value,
                                resource.known,
                                resource.incomplete));
            }
            summaries.sort(
                    Comparator.comparing(
                                    (ResourceSummary summary) -> summary.itemName,
                                    String.CASE_INSENSITIVE_ORDER)
                            .thenComparingInt(summary -> summary.itemId));
            return new Card(
                    head.transactionId, at, value, known, incomplete, representative, summaries);
        }
    }

    /** One pass builds each transaction card and its resources; newest cards first. */
    static List<Card> cards(Group group) {
        if (group == null) return emptyList();
        var byTransaction = new LinkedHashMap<String, ReceiptCard>();
        for (Receipt receipt : group.receipts) {
            byTransaction
                    .computeIfAbsent(receipt.transactionId, ignored -> new ReceiptCard(receipt))
                    .add(receipt);
        }
        var cards = new ArrayList<Card>(byTransaction.size());
        for (ReceiptCard card : byTransaction.values()) cards.add(card.card());
        cards.sort(
                Comparator.comparingLong((Card card) -> card.at)
                        .reversed()
                        .thenComparing(card -> card.transactionId));
        return cards;
    }

    /** The query this data answers: scope, Costs view, search and the selected receipt. */
    Entry entry;

    String scopeName;
    boolean readOnly;
    boolean compacted;
    boolean detailedHistoryAvailable;
    long net;

    /** Capture time for honest ages without leaking the engine. */
    long capturedAt;

    TableData gains;
    TableData costs;
    TableData market;
    ReviewData review;
    List<Receipt> corrected;
    Summary total;
    boolean reconciled;
    public Detail detail;

    /**
     * Raw Market settlement rows by presentation id; presentation-only lookup for value semantics.
     */
    Map<String, Row> marketRows;

    /** Group counts for the Losses chips by {@link CostView} ordinal, whatever view is selected. */
    int[] costCounts;

    /** Keys and reclaim waiting to settle; current scope only, with an exact observed chest value. */
    List<PendingItem> pendingItems;

    static LedgerData capture(Engine engine, long now, Entry entry) {
        Entry e = entry == null ? Entry.current() : entry;
        List<Session> sessions = sessionsFor(engine, e, now);
        String scopeName = scopeName(engine, e);
        MoneySnapshot summary = engine.summary();
        MoneySnapshot.SessionScope scope = summary.scope(sessions);
        Set<String> ids = scope.ids;
        List<Transaction> transactions = scope.transactions;
        boolean compacted = scope.cut;
        long scopeNet = scope.net;
        List<Row> marketRows = summary.marketRows;
        Map<String, Row> markets = scope.markets;
        Result result = scope.groups;
        var tables = new EnumMap<Table, List<Group>>(Table.class);
        for (Table table : Table.values()) tables.put(table, new ArrayList<>());
        for (Group group : result.groups) {
            if (!group.reviewRequired && group.matchesText(e.search))
                tables.get(group.table).add(group);
        }
        List<Group> gainsAll = tables.get(Table.GAINS);
        List<Group> suppliesAll = tables.get(Table.COSTS_SUPPLIES);
        List<Group> lossAll = tables.get(Table.COSTS_LOSS);
        List<Group> marketAll = tables.get(Table.MARKET);
        var supplies = new ArrayList<Group>();
        var charges = new ArrayList<Group>();
        for (Group group : suppliesAll) (group.chargeUse ? charges : supplies).add(group);
        var costsAll =
                new ArrayList<Group>(
                        e.costView == CostView.LOSS
                                ? lossAll
                                : e.costView == CostView.CHARGES ? charges : supplies);
        if (e.costView == CostView.ALL) {
            // All is every list together, newest first like every other table (owner 1.3.3).
            costsAll.addAll(charges);
            costsAll.addAll(lossAll);
            costsAll.sort(
                    Comparator.comparingLong((Group group) -> group.latestActivityAt)
                            .reversed()
                            .thenComparing(group -> group.semanticGroupId));
        }
        TableData gains = tableData(gainsAll, false);
        TableData costs = tableData(costsAll, false);
        TableData market = tableData(marketAll, true);
        var financial = new ArrayList<Group>();
        financial.addAll(gainsAll);
        financial.addAll(suppliesAll);
        financial.addAll(lossAll);
        financial.addAll(marketAll);
        Summary total = Semantics.summarize(financial);
        // ── audit ────────────────────────────────────────────────────────────────────────────
        var reviewRows = new ArrayList<ReviewRow>();
        int reviewScopeCount = 0;
        for (ReviewRow reviewRow : engine.getReviewRows(now)) {
            if (reviewRow != null && ids.contains(reviewRow.sessionId)) {
                reviewScopeCount++;
                if (!readOnly(e) && matchesSearch(reviewRow, e.search)) reviewRows.add(reviewRow);
            }
        }
        var review = new ReviewData(reviewRows, reviewScopeCount);
        var correctedAll = new ArrayList<Receipt>();
        for (Group group : result.groups) {
            for (Receipt receipt : group.receipts) {
                if ((receipt.corrected || receipt.split) && receipt.matchesSearch(e.search))
                    correctedAll.add(receipt);
            }
        }
        correctedAll.sort(
                Comparator.comparingLong((Receipt receipt) -> receipt.at)
                        .reversed()
                        .thenComparing(receipt -> receipt.contributionId));
        // ── selected detail ──────────────────────────────────────────────────────────────────
        Detail detail = null;
        String anchorContribution = e.highlightContributionId;
        String anchorTransaction = e.highlightTransactionId;
        String anchorGroup = e.highlightGroupId;
        if (anchorGroup != null || anchorContribution != null || anchorTransaction != null) {
            Group group =
                    findGroup(result.groups, anchorGroup, anchorContribution, anchorTransaction);
            if (group != null) {
                List<Receipt> receipts = group.receipts;
                Receipt exact = null;
                if (anchorContribution != null) {
                    for (Receipt receipt : receipts) {
                        if (receipt.contributionId.equals(anchorContribution)) {
                            exact = receipt;
                            break;
                        }
                    }
                }
                if (exact == null && anchorTransaction != null) {
                    exact = firstReceipt(receipts, anchorTransaction);
                }
                detail = new Detail(group, receipts, exact);
            }
        }
        long net = scopeNet;
        boolean detailedHistoryAvailable = !compacted || total.entries > 0;
        boolean reconciled =
                total.coverageComplete
                        && safeAdd(safeAdd(total.gains, total.costs), total.market) == net;
        return new LedgerData(
                e,
                scopeName,
                readOnly(e),
                compacted,
                detailedHistoryAvailable,
                net,
                now,
                gains,
                costs,
                market,
                review,
                correctedAll,
                total,
                reconciled,
                detail,
                markets,
                new int[] {0, supplies.size(), lossAll.size(), charges.size()},
                pendingItems(engine, e, now));
    }

    static List<PendingItem> pendingItems(Engine engine, Entry e, long now) {
        var items = new ArrayList<PendingItem>();
        if (e.scope != Scope.CURRENT_GRIND || readOnly(e)) return items;
        var valued = new HashSet<Integer>();
        for (SavedState.PendingClaim claim : engine.getPendingClaims()) {
            if (claim == null || !claim.isValid()) continue;
            KeyChestCatalogue.Entry chest = KeyChestCatalogue.entryForKey(claim.itemOrKeyId);
            String name =
                    chest != null
                            ? chest.getChestName() + " key"
                            : claim.itemOrKeyId >= ItemID.WILDY_LOOT_KEY0
                                            && claim.itemOrKeyId <= ItemID.WILDY_LOOT_KEY4
                                    ? "Loot key"
                                    : "Key or chest";
            // One chest per key type: stacked claims of it name the same worth once.
            long value =
                    valued.add(claim.itemOrKeyId)
                            ? engine.pendingKeyValue(claim.itemOrKeyId, now)
                            : 0L;
            items.add(
                    new PendingItem(
                            claim.itemOrKeyId, name, claim.getQuantity(), name + msg("opened-books"), value));
        }
        DeathReclaimStatus reclaim = engine.deathStatus();
        if (reclaim.awaiting) {
            items.add(
                    new PendingItem(
                            0,
                            "Death reclaim",
                            Math.max(1L, reclaim.outstandingItemCount),
                            msg("items-waiting-reclaimed"),
                            0L));
        }
        return items;
    }

    /** Ordinary tables sum every group; Market's header includes only complete results. */
    static TableData tableData(List<Group> groups, boolean market) {
        long total = 0L;
        var receipts = new LinkedHashSet<String>();
        for (Group group : groups) {
            if (!market || !group.incomplete()) total = safeAdd(total, group.value);
            receipts.addAll(group.receiptIds);
        }
        return new TableData(groups, total, groups.size(), receipts.size());
    }

    /** True when a Market lifecycle is genuinely pending rather than already realized. */
    static boolean isPending(Lifecycle lifecycle) {
        return lifecycle == PENDING
                || lifecycle == PARTIALLY_EXECUTED
                || lifecycle == EXECUTED_UNSETTLED
                || lifecycle == PARTIALLY_REALIZED;
    }

    static Group findGroup(
            List<Group> groups, String groupId, String contributionId, String transactionId) {
        // Most specific anchor first: a receipt's own contribution must win over the transaction
        // it came from, or a shop drill lands on the wrong side of the trade (one rune's group).
        for (Group group : groups) {
            if (!group.reviewRequired && group.containsContribution(groupId)) return group;
        }
        for (Group group : groups) {
            if (!group.reviewRequired && group.containsContribution(contributionId)) return group;
        }
        for (Group group : groups) {
            if (!group.reviewRequired && group.containsTransaction(transactionId)) return group;
        }
        // Review-required groups are still openable from the audit list detail.
        for (Group group : groups) {
            if (group.containsContribution(contributionId)
                    || group.containsTransaction(transactionId)) return group;
        }
        return null;
    }

    static Receipt firstReceipt(List<Receipt> receipts, String transactionId) {
        Receipt found = null;
        for (Receipt receipt : receipts) {
            if (receipt.transactionId.equals(transactionId)
                    && (found == null || receipt.at >= found.at)) {
                found = receipt;
            }
        }
        return found;
    }

    static List<Session> sessionsFor(Engine engine, Entry entry, long now) {
        var sessions = new ArrayList<Session>();
        if (entry.scope == Scope.HISTORY) {
            Session historical =
                    entry.historySessionId == null
                            ? null
                            : engine.historySession(entry.historySessionId);
            if (historical != null) sessions.add(historical);
            return sessions;
        }
        Session active = engine.getActiveSession();
        if (active != null) sessions.add(active);
        if (entry.scope == Scope.TODAY) {
            ZoneId zone = engine.timeZone();
            LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
            for (Session historical : engine.getHistory()) {
                if (historical == null
                        || (active != null && historical.getId().equals(active.getId()))) continue;
                LocalDate day =
                        Instant.ofEpochMilli(historical.startedAtEpochMillis)
                                .atZone(zone)
                                .toLocalDate();
                if (day.equals(today)) sessions.add(historical);
            }
        }
        return sessions;
    }

    static String scopeName(Engine engine, Entry entry) {
        if (entry.scope == Scope.HISTORY) {
            if (entry.historyName != null && !entry.historyName.isEmpty()) return entry.historyName;
            Session historical =
                    entry.historySessionId == null
                            ? null
                            : engine.historySession(entry.historySessionId);
            return historical == null ? "Recent Grind" : historical.getName();
        }
        return entry.scope == Scope.TODAY ? "Runs today" : "Current Grind";
    }

    static boolean readOnly(Entry entry) {
        return entry.scope != Scope.CURRENT_GRIND;
    }

    static boolean matchesSearch(ReviewRow row, String query) {
        if (Phrases.blank(query)) return true;
        String needle = Phrases.lower(query);
        if (Phrases.containsFolded(needle, row.why)) return true;
        for (ReviewRow.Item item : row.items) {
            if (Phrases.containsFolded(needle, item.name)) return true;
        }
        return false;
    }

    // ── correction support (unchanged semantics: exact canonical ids only) ─────────────────────
    static class CorrectionPreview {
        long currentNet;
        long afterNet;
        long change;
        long revision;

        CorrectionPreview(long currentNet, long afterNet, long revision) {
            this.currentNet = currentNet;
            this.afterNet = afterNet;
            change = afterNet - currentNet;
            this.revision = revision;
        }

        String note() {
            return change == 0L ? msg("net-gp") : "Net changes by " + Fmt.signed(change) + " gp.";
        }
    }

    /**
     * Net if a split keeps {@code keep} of {@code itemId}: the split itself runs on a detached copy
     * of the receipt, so the preview and the applied split cannot differ.
     */
    static CorrectionPreview splitPreview(
            Engine engine, String transactionId, int itemId, long keep, long now) {
        synchronized (engine) {
            long currentNet = engine.getMetrics(now).net;
            Session session = engine.getActiveSession();
            Transaction transaction =
                    session == null ? null : session.findTransaction(transactionId);
            if (transaction == null)
                return new CorrectionPreview(currentNet, currentNet, engine.getRevision());
            var gson = JsonCodec.gson();
            Transaction copy = gson.fromJson(gson.toJson(transaction), Transaction.class);
            copy.takeQuantity(itemId, true, copy.quantity(itemId, true) - keep);
            long before = transaction.isCounted() ? transaction.getNet() : 0L;
            long after = copy.isCounted() ? copy.getNet() : 0L;
            return new CorrectionPreview(
                    currentNet, currentNet - before + after, engine.getRevision());
        }
    }

    static CorrectionPreview preview(
            Engine engine, String transactionId, Correction correction, long now) {
        Session session = engine.getActiveSession();
        long currentNet = engine.getMetrics(now).net;
        if (session == null || transactionId == null || correction == null) {
            return new CorrectionPreview(currentNet, currentNet, engine.getRevision());
        }
        Transaction transaction = session.findTransaction(transactionId);
        if (transaction == null)
            return new CorrectionPreview(currentNet, currentNet, engine.getRevision());
        long before = transaction.isCounted() ? transaction.getNet() : 0L;
        long after = transaction.netUnder(correction);
        return new CorrectionPreview(currentNet, currentNet - before + after, engine.getRevision());
    }
}

/** Detached, read-only summary of one transaction awaiting an owner decision. */
class ReviewRow {
    String transactionId;
    String sessionId;
    long timestampEpochMillis;
    long ageMillis;
    List<Item> items;
    long value;
    String why;
    Set<Decision> validDecisions;

    ReviewRow(
            String transactionId,
            String sessionId,
            long timestampEpochMillis,
            long ageMillis,
            List<Item> items,
            long value,
            String why,
            Set<Decision> validDecisions) {
        this.transactionId = orEmpty(transactionId);
        this.sessionId = orEmpty(sessionId);
        this.timestampEpochMillis = nonNeg(timestampEpochMillis);
        this.ageMillis = nonNeg(ageMillis);
        this.items =
                Collections.unmodifiableList(
                        items == null ? new ArrayList<Item>() : new ArrayList<>(items));
        this.value = value;
        this.why = orEmpty(why);
        EnumSet<Decision> copy =
                validDecisions == null || validDecisions.isEmpty()
                        ? EnumSet.noneOf(Decision.class)
                        : EnumSet.copyOf(validDecisions);
        this.validDecisions = Collections.unmodifiableSet(copy);
    }

    /** Detached item summary; value is the signed flow value for this item. */
    static class Item {
        int itemId;
        String name;
        long quantityDelta;
        long valueDelta;

        Item(int itemId, String name, long quantityDelta, long valueDelta) {
            this.itemId = itemId;
            this.name = orEmpty(name);
            this.quantityDelta = quantityDelta;
            this.valueDelta = valueDelta;
        }
    }
}
