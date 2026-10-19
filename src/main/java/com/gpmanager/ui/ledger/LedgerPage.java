package com.gpmanager;

import static com.gpmanager.MarketText.*;

import java.util.function.BiConsumer;


import static com.gpmanager.Fmt.*;
import static com.gpmanager.GameData.msg;
import static com.gpmanager.Kit.*;
import static com.gpmanager.Kit.Tone.*;
import static com.gpmanager.SafeMath.nonNeg;

import static java.lang.Math.*;
import static java.util.Collections.*;

import com.gpmanager.Kit.Tone;
import com.gpmanager.LedgerData.*;
import com.gpmanager.Semantics.*;
import com.gpmanager.Table.*;
import com.gpmanager.Table.Menu;

import net.runelite.client.ui.components.IconTextField;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.function.Function;

import javax.swing.*;
import javax.swing.event.*;
import static com.gpmanager.LedgerData.Scope.*;
import static com.gpmanager.Phrases.*;
import com.gpmanager.LedgerPage.CorrectionOutcome;
import static com.gpmanager.SafeMath.now;

/**
 * Ledger = why the numbers changed (SIDEBAR_SPEC.md §3). The toolbar holds the fitted scope,
 * search and ⋯; the overview is folding paged tables (Gains, Costs with Supply / Loss, Market,
 * Pending, Review, Corrected). Opening a row shows its receipts; the selected receipt opens under
 * them with its short facts and one Correct ▾ menu. Corrections always target one exact canonical
 * receipt id.
 */
class LedgerPage implements Shell.Page {
    enum CorrectionOutcome {
        APPLIED, REFUSED, STALE
    }

    interface Actions {
        void openScopeMenu(JComponent anchor);

        default void recent() {}

        void costViewChanged(CostView view);

        void searchChanged(String text);

        /** Selection is navigation state only; a correction still targets one transaction id. */
        default void selectionChanged(
                String identifier, String id, String groupId) {}

        CorrectionPreview preview(String id, Correction decision);

        CorrectionOutcome correct(String id, Correction decision, long previewRevision);

        /** Asks how many to keep, then hands back {itemId, keep, total} and its Net preview. */
        void split(String id, BiConsumer<long[], CorrectionPreview> previewed);

        default void applySplit(String id, long[] split, long previewRevision) {}

        void undoCorrection();

        /** Pins an open mutation menu to the live session; empty when none can be mutated. */
        default String pinMenuTarget() {
            return "";
        }

        /** Undoes the latest booked change (moved here from the Live menu). */
        default void undoLast() {}

        default void restoreUndo() {}

        default void decide(String id, Decision decision) {}

        void refresh();
    }

    static final int ROWS = 5;
    Actions actions;
    Function<Integer, BufferedImage> sprites;
    Map<Integer, ImageIcon> icons = new HashMap<>();
    JPanel bar = new JPanel(new BorderLayout(4, 0));
    JButton scope, more;
    JPanel body = stack(PANEL, GAP);
    IconTextField search = new IconTextField();
    Table gains = new Table("GAINS"), costs = new Table("LOSSES"), market = new Table("TRADE");
    Table pending = new Table("PENDING"), review = new Table("REVIEW");

    /** The group-detail receipt list; persistent so its pager survives refreshes (F07). */
    Table detailReceipts = new Table("ENTRIES");

    /** What a multi-item group used or received in all, item by item (owner 2026-10-03). */
    Table detailItems = new Table("USED");

    /** The scope/session/group the detail receipts belong to; a change restarts its pager. */
    String detailReceiptsFor = "";

    Table corrected = new Table("CORRECTED");
    JPanel detail = stack(PANEL, GAP);
    LedgerData data;
    boolean searchOpen, quietSearch, showCorrected;
    String reviewSelected = "", anchored = "";
    Correction pendingCorrection;
    String pendingTransaction;
    CorrectionPreview pendingPreview;

    /** A split waiting for Confirm: {itemId, keep, total}; null for a correction. */
    long[] pendingSplit;

    LedgerPage(Actions actions, Function<Integer, BufferedImage> sprites) {
        this.actions = actions;
        this.sprites = sprites == null ? id -> null : sprites;
        scope = button("This Grind \u2304", false, () -> {});
        scope.setPreferredSize(new Dimension(0, scope.getPreferredSize().height));
        onResize(scope, this::scopeTitle);
        scope.addActionListener(e -> actions.openScopeMenu(scope));
        JButton find = button("Search", false, this::toggleSearch);
        find.getAccessibleContext().setAccessibleName("Search");
        find.setToolTipText("Search this scope");
        more = button("\u00b7\u00b7\u00b7", false, () -> {});
        more.setToolTipText("Ledger actions");
        more.getAccessibleContext().setAccessibleName("Ledger actions");
        more.addActionListener(e -> moreMenu().show(more, 0, more.getHeight()));
        // Owner 2026-10-01: one toolbar band shared with the Grinds page; actions sit on the right.
        headerBand(bar, scope, find, more);
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
        for (var table : new Table[] {gains, costs, market, pending, review, corrected}) {
            table.setFoldable(true).setRowsPerPage(ROWS);
        }
        detailReceipts.setRowsPerPage(ROWS);
        costs.setPicker(costPicker(CostView.ALL, new int[4]));
        pending.setEmptyText("Nothing waiting");
        review.setEmptyText(msg("nothing-needs"));
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
    void resetOwner() {
        prepareEntry();
        showCorrected = false;
        searchOpen = false;
        setSearchText("");
        for (var table : new Table[] {gains, costs, market, pending, review, corrected}) {
            table.setRows(emptyList());
            table.resetPage();
        }
        detailReceiptsFor = "";
        detailReceipts.setRows(emptyList());
        detail.removeAll();
    }

    /** A new deep link replaces any previous local selection and preview. */
    void prepareEntry() {
        anchored = "";
        clearPending();
        reviewSelected = "";
    }

    void apply(LedgerData next) {
        boolean scopeChanged = data == null || data.entry.scope != next.entry.scope
                        || !String.valueOf(data.entry.historySessionId)
                                .equals(String.valueOf(next.entry.historySessionId));
        data = next;
        more.setVisible(!liveCategory());
        bar.setVisible(!liveCategory());
        int size = !liveCategory() ? ROWS : 15;
        for (var table : new Table[] {gains, costs, market})
            if (table.rowsPerPage != size) table.setRowsPerPage(size);
        String selection = next.entry.scope.name() + "|" + next.entry.highlightGroupId + "|"
                        + next.entry.highlightContributionId + "|" + next.entry.highlightTransactionId;
        if (!selection.equals(anchored)) {
            anchored = selection;
            // A preview belongs to one receipt; opening another cancels it.
            if (pendingTransaction != null && !pendingTransaction.equals(next.entry.highlightTransactionId)) {
                clearPending();
            }
        }
        scopeTitle();
        // Owner 2026-10-01 (F03): Runs today is whole runs that started today, never calendar-day
        // money.
        scope.setToolTipText(next.scopeName + (next.entry.scope == LedgerData.Scope.TODAY ? " · " + msg("current-run") : ""));
        if (scopeChanged) {
            for (var table : new Table[] {gains, costs, market, pending, review, corrected})
                table.resetPage();
        }
        searchOpen = !liveCategory() && (searchOpen || !next.entry.search.isEmpty());
        for (var part : body.getComponents())
            if (part != search || !searchOpen) body.remove(part);
        if (searchOpen) {
            if (!search.getText().equals(next.entry.search)) setSearchText(next.entry.search);
            if (search.getParent() != body) body.add(search, 0);
        }
        if (next.entry.scope == LedgerData.Scope.TODAY) {
            // Owner 2026-10-01 (F03): whole runs that started today, never calendar-day money.
            body.add(note(msg("runs-today"), LABEL));
        }
        if (next.readOnly) body.add(note(msg("read-corrections-apply"), LABEL));
        if (next.compacted && !next.detailedHistoryAvailable)
            body.add(note(msg("detailed-entries"), LABEL));
        if (next.detail != null) {
            buildDetail(next);
            body.add(detail);
        } else {
            overview(next);
        }
        body.revalidate();
        body.repaint();
    }

    // ── overview ─────────────────────────────────────────────────────────────────────────────
    void overview(LedgerData data) {
        var focus = data.entry.liveTable;
        if (focus == null || focus == Semantics.Table.GAINS) {
            fill(gains, "GAINS", data.gains.groups, data.gains.total, Tone.GAIN);
            body.add(gains);
        }
        if (focus == null || focus == Semantics.Table.COSTS_SUPPLIES || focus == Semantics.Table.COSTS_LOSS) {
            costs.setPicker(focus == null ? costPicker(data.entry.costView, data.costCounts) : null);
            fill(costs, focus == null ? "LOSSES" : focus.label,
                    data.costs.groups, data.costs.total, focus == Semantics.Table.COSTS_LOSS || data.entry.costView == CostView.LOSS ? LOSS : SUPPLY);
            body.add(costs);
        }
        if (focus != null && focus != Semantics.Table.MARKET) return;
        var settled = new ArrayList<Group>();
        var open = new ArrayList<Group>();
        long settledTotal = 0;
        for (Group group : data.market.groups) {
            var row = data.marketRow(group.marketPresentationId);
            if (!group.market || marketSettled(group, row)) {
                // A shop exchange is a MARKET group with no GE offer lifecycle: it is settled.
                settled.add(group);
                settledTotal += group.value;
                // An offer canceled before anything filled moved no money: it leaves Pending.
            } else if (row == null || row.lifecycle != MarketRows.Lifecycle.CANCELLED_RETURNED) {
                open.add(group);
            }
        }
        fill(market, "TRADE", settled, focus == null || !data.entry.search.isEmpty() ? settledTotal : data.market.total, MARKET);
        body.add(market);
        if (focus != null) return;
        if (!open.isEmpty() || !data.pendingItems.isEmpty()) {
            var rows = new ArrayList<Row>();
            for (Group group : open) rows.add(groupRow(group, DIM));
            for (var item : data.pendingItems) {
                boolean valued = item.value > 0;
                rows.add(new Row("pending|" + item.name + "|" + item.itemId, icon(item.itemId), item.name,
                                "×" + exact(item.quantity), valued ? signed(item.value) : "", DIM, item.tip,
                                valued ? exactSigned(item.value) + " gp" : "", false, null, emptyList()));
            }
            pending.setTitle("PENDING · " + rows.size()).setTotal("", DIM).setMinRows(rows.size() > ROWS ? ROWS : 0)
                    .setRows(rows);
            body.add(pending);
        }
        if (data.review.scopeCount > 0 || data.entry.focusAudit == Audit.REVIEW) buildReview(data);
        if (showCorrected || data.entry.focusAudit == Audit.CORRECTED) {
            var rows = new ArrayList<Row>();
            for (var receipt : data.corrected) rows.add(receiptRow(receipt));
            corrected.setTitle("CORRECTED · " + data.corrected.size()).setEmptyText(msg("nothing-corrected"))
                    .setRows(rows);
            body.add(corrected);
        }
    }

    void fill(Table table, String title, List<Group> groups, long total, Tone tone) {
        var rows = new ArrayList<Row>();
        for (Group group : groups) {
            // Under All, loss rows keep their own colour beside supplies.
            rows.add(groupRow(group, tone == SUPPLY && group.table == Semantics.Table.COSTS_LOSS ? LOSS : tone));
        }
        table.setTitle(title + " · " + groups.size())
                .setTotal(groups.isEmpty() && !liveCategory() || tone == DIM ? "" : signed(total), tone)
                .setMinRows(rows.size() > ROWS ? ROWS : 0).setRows(rows);
    }

    /**
     * Owner 1.1: the Losses view is a dropdown beside the header (All, Supplies, Items, Charges).
     */
    JButton costPicker(CostView selected, int[] counts) {
        var pick = new JButton(selected.label + " \u2304");
        pick.setFont(small());
        pick.setForeground(selected == CostView.ALL ? LABEL : ACCENT);
        pick.setBorder(BorderFactory.createEmptyBorder());
        pick.setContentAreaFilled(false);
        pick.setFocusPainted(false);
        focus(pick);
        pick.getAccessibleContext().setAccessibleName("Losses filter: " + selected.label);
        pick.addActionListener(e -> menu(selected, counts).show(pick, 0, pick.getHeight()));
        return pick;
    }

    JPopupMenu menu(CostView selected, int[] counts) {
        var menu = new JPopupMenu();
        for (var view : CostView.values()) {
            menu.add(item((view == selected ? "\u2713 " : "") + view.label + (view == CostView.ALL ? ""
                                            : " " + exact(counts[view.ordinal()])), () -> {
                                if (view != selected) actions.costViewChanged(view);
                            }));
        }
        return menu;
    }

    Row groupRow(Group group, Tone tone) {
        String value;
        Tone shown = tone;
        String meta = groupLead(group);
        String tip = group.primaryName + " · " + (group.receiptCount > 1 ? (meta.isEmpty() ? "" : meta + " · ")
                                        + group.receiptCount + " entries" : meta);
        if (group.reviewRequired) {
            value = "?";
            shown = REVIEW;
        } else if (group.market) {
            var row = data.marketRow(group.marketPresentationId);
            if (liveCategory() && group.incomplete()) {
                value = "?";
                shown = DIM;
            } else if (marketSettled(group, row)) {
                value = signed(marketPrimary(data, group));
                tip = group.primaryName + " · " + marketLead(data, group)
                                + msg("observed-settlement-result-affects");
            } else {
                value = stateWord(row);
                shown = DIM;
                tip = group.primaryName + " · " + group.contextLine;
            }
        } else if (group.incomplete()) {
            value = "?";
            shown = DIM;
            tip = group.primaryName + msg("incomplete-value");
        } else {
            value = worth(group.value);
        }
        String qty = group.claim < 0 ? "\u2212" + max(1, group.quantity) : !group.failedOf().isEmpty()
                                ? group.failedOf() : group.quantity > 1 && group.itemId != 995
                                                && (!group.actionGroup() || group.chargeUse) ? times(group.quantity)
                                        : "";
        if (liveCategory() && group.itemId != 995 && group.claim >= 0 && group.failedOf().isEmpty()) {
            if (group.actionGroup() && !group.chargeUse) qty = times(max(1, group.receiptCount));
            else if (group.quantity == 1) qty = times(1);
        }
        if (group.failed > 0) tip = group.primaryName + " · " + group.failedTip();
        Runnable open = () -> openGroup(group.semanticGroupId);
        return new Row(group.semanticGroupId, icon(group.itemId), group.primaryName
                        + (group.failed > 0 && group.failedOf().isEmpty() ? " (failed)" : "")
                        + (group.corrected ? " •" : ""), qty, value, shown, tip,
                group.incomplete() || group.market ? "" : exactSigned(group.value) + " gp", false, open,
                singletonList(new Menu("Open", open)));
    }

    void openGroup(String id) {
        clearPending();
        actions.selectionChanged(null, null, id);
    }

    Row receiptRow(Receipt receipt) {
        Runnable open = () -> actions.selectionChanged(receipt.transactionId, receipt.contributionId, null);
        return new Row(receipt.contributionId, icon(receipt.itemId), receipt.itemName,
                receipt.quantity > 1 && receipt.itemId != 995 ? times(receipt.quantity) : "",
                receipt.unpriced ? "?" : worth(receipt.value), receipt.unpriced ? DIM : of(receipt.category),
                receipt.itemName + " · " + receipt.correctionLabel + " · " + receipt.why,
                receipt.unpriced ? "" : exactSigned(receipt.value) + " gp", false, open,
                singletonList(new Menu("Open", open)));
    }

    void buildReview(LedgerData data) {
        var rows = new ArrayList<Row>();
        ReviewRow chosen = null;
        for (var row : data.review.rows) {
            String name = row.items.isEmpty() ? "Unknown item" : row.items.get(0).name;
            boolean selected = row.transactionId.equals(reviewSelected);
            if (selected) chosen = row;
            Runnable open = () -> {
                        reviewSelected = selected ? "" : row.transactionId;
                        apply(this.data);
                    };
            rows.add(new Row(row.transactionId, null, name, "", "?", REVIEW, name + " · " + row.why,
                            exactSigned(row.value) + " gp", selected, open, singletonList(new Menu("Decide…", open))));
        }
        review.setTitle("(!) REVIEW · " + data.review.scopeCount).setAction(null).setEmptyText(data.readOnly
                                ? msg("review-read") : data.review.scopeCount == 0 ? msg("nothing-needs")
                                        : msg("review-row")).setRows(rows);
        body.add(review);
        if (chosen != null && !data.readOnly) body.add(decisionPanel(chosen));
    }

    /** The inline decision panel under a selected review row: what it is, why, then the choices. */
    JComponent decisionPanel(ReviewRow row) {
        var panel = stack(CARD, 4);
        pad(panel, 4, GAP, 6, GAP);
        panel.add(note(row.why, LABEL));
        String preview = netPreview(row, data == null ? 0 : data.net);
        if (!preview.isEmpty()) panel.add(note(preview, TEXT));
        var buttons = row();
        for (var decision : Decision.values()) {
            if (row.validDecisions.contains(decision)) {
                buttons.add(button(reviewLabel(decision), false, () -> {
                                    reviewSelected = "";
                                    actions.decide(row.transactionId, decision);
                                }));
            }
        }
        panel.add(buttons);
        return panel;
    }

    /** What Net becomes under a gain or a cost decision; nothing counts until the owner decides. */
    static String netPreview(ReviewRow row, long net) {
        long size = abs(row.value);
        if (size == 0) return "";
        var text = new StringBuilder();
        if (row.validDecisions.contains(Decision.GAIN)) {
            text.append(msg("if-counted-gain")).append(signed(net + size));
        }
        if (row.validDecisions.contains(Decision.COST)) {
            text.append(text.length() == 0 ? msg("if-counted-cost") : " · as a cost: Net ").append(signed(net - size));
        }
        return text.toString();
    }

    JPopupMenu moreMenu() {
        var menu = new JPopupMenu();
        menu.add(item(showCorrected ? "Hide corrected" : "Show corrected"
                                        + (data == null ? "" : " (" + data.corrected.size() + ")"), () -> {
                            showCorrected = !showCorrected;
                            if (data != null) apply(data);
                        }));
        // Owner 2026-10-01 (F01): undo mutates the live run only; read-only scopes disable it.
        boolean editable = data != null && !data.readOnly && !actions.pinMenuTarget().isEmpty();
        menu.add(item(msg("undo-latest-correction"), editable, actions::undoCorrection));
        menu.addSeparator();
        menu.add(item("Undo last change", editable, actions::undoLast));
        menu.add(item("Restore last undo", editable, actions::restoreUndo));
        return menu;
    }

    // ── detail: one group's receipts; the selected receipt opens in place ────────────────────
    void buildDetail(LedgerData data) {
        detail.removeAll();
        var open = data.detail;
        Group group = open.group;
        detail.add(back(!liveCategory() ? "Ledger" : data.entry.liveTable.label, () -> actions.selectionChanged(null, null, null)));
        long latest = group.latestActivityAt;
        for (var receipt : open.receipts) latest = max(latest, receipt.at);
        int count = group.market ? open.receipts.size() : group.receiptCount;
        detail.add(title(group.primaryName, group.incomplete() ? "—" : exactSigned(group.value) + " gp",
                        group.incomplete() ? DIM : sign(group.value), (count == 0 ? "" : count + (group.market
                                                        ? count == 1 ? " trade · " : " trades · "
                                                        : count == 1 ? " entry · " : " entries · ")) + "Latest "
                                + age(nonNeg(data.capturedAt - latest))));
        var items = items(group);
        // A charged weapon always lists what it spent, even one rune type (owner 1.2).
        if (!group.market && (items.size() > 1 || group.chargeUse)) {
            detailItems.setTitle((group.chargeUse || group.value < 0 ? "USED · " : "ITEMS · ") + items.size())
                    .setRows(items);
            detail.add(detailItems);
        }
        String receiptsFor = data.entry.scope.name() + "|" + String.valueOf(data.entry.historySessionId) + "|"
                        + group.semanticGroupId;
        if (!receiptsFor.equals(detailReceiptsFor)) {
            // Owner 2026-10-01 (F07): a new group starts at the first page; refreshes keep theirs.
            detailReceiptsFor = receiptsFor;
            detailReceipts.showPage(0);
        }
        receipts(data, open);
        // An offer with nothing collected yet has no receipt to show or correct; none is invented.
        // One entry needs no list: the header names it and its detail opens below (owner 1.5.2).
        if (open.receipts.isEmpty()) detail.add(note(msg("collected-entries"), LABEL));
        else if (group.market || !LedgerData.oneEntry(open.receipts)) detail.add(detailReceipts);
        if (open.exact != null) detail.add(receipt(data, open.exact));
    }

    /** The group's items summed across its entries: "Blood rune ×124" and what it came to. */
    List<Row> items(Group group) {
        var totals = new LinkedHashMap<String, ResourceSummary>();
        for (var card : LedgerData.cards(group)) {
            for (var item : card.resources) {
                totals.merge(item.itemId + "|" + item.itemName, item, (a, b) -> new ResourceSummary(a.itemId,
                                        a.itemName, a.usedBy, a.quantity + b.quantity, a.value + b.value,
                                        a.knownSubtotal + b.knownSubtotal, a.incomplete || b.incomplete));
            }
        }
        var rows = new ArrayList<Row>();
        for (var item : totals.values()) {
            rows.add(new Row("item|" + item.itemId + "|" + item.itemName, icon(item.itemId), item.itemName,
                            item.itemId == 995 ? "" : times(item.quantity), item.incomplete ? "?" : worth(item.value),
                            item.incomplete ? DIM : sign(item.value), "",
                            item.incomplete ? "" : exactSigned(item.value) + " gp", false, null, null));
        }
        return rows;
    }

    /**
     * Every entry in the group: a trade each for Market, one canonical transaction each otherwise.
     */
    void receipts(LedgerData data, Detail open) {
        Group group = open.group;
        var chosen = open.exact;
        var rows = new ArrayList<Row>();
        if (group.market) {
            for (var receipt : open.receipts) {
                boolean realized = receiptRealized(receipt);
                long primary = receiptPrimary(receipt);
                rows.add(receiptRow(data, group, receipt.transactionId, receipt.contributionId, chosen != null
                                        && chosen.contributionId.equals(receipt.contributionId),
                                receiptTitle(receipt), realized ? signed(primary)
                                        : stateWord(receipt.marketSettlement), realized ? MARKET : DIM,
                                receiptSub(receipt, data.capturedAt) + (receipt.corrected
                                                ? msg("result-includes-manual-correction") : ""),
                                realized ? exactSigned(primary) + " gp" : "", splittable(receipt)));
            }
        } else {
            // A multi-item group's entries say what each use spent ("Blood rune ×2"), not the group
            // again.
            boolean several = items(group).size() > 1 || group.chargeUse;
            for (var card : LedgerData.cards(group)) {
                boolean split = false;
                for (var receipt : open.receipts) {
                    split |= receipt.transactionId.equals(card.transactionId) && splittable(receipt);
                }
                // A shop exchange's currency entries are named by what they bought or sold
                // (owner 2026-10-12): "Death rune ×5", not the tokkul that paid.
                var cardReceipt = LedgerData.firstReceipt(open.receipts, card.transactionId);
                String rowName = several ? card.summaryText()
                                : cardReceipt != null && !cardReceipt.tradedItemsShort.isEmpty()
                                        ? cardReceipt.tradedItemsShort : group.primaryName;
                rows.add(receiptRow(data, group, card.transactionId, card.contributionId,
                                chosen != null && chosen.transactionId.equals(card.transactionId), rowName,
                                card.incomplete ? "?" : signed(card.value), card.incomplete ? DIM : sign(card.value),
                                card.summaryText() + " · " + age(nonNeg(data.capturedAt - card.at)) + (card.incomplete
                                                ? " · known value " + exactSigned(card.knownSubtotal) + " gp" : ""),
                                card.incomplete ? "" : exactSigned(card.value) + " gp", split));
            }
        }
        detailReceipts.setTitle("ENTRIES · " + rows.size()).setRows(rows);
    }

    Row receiptRow(LedgerData data, Group group, String identifier, String id, boolean selected,
            String name, String value, Tone tone, String tip, String exact, boolean canSplit) {
        Runnable open = () -> clickReceipt(identifier, id);
        var menus = new ArrayList<Menu>();
        if (!data.readOnly) {
            corrections(identifier, id, canSplit)
                    .forEach((label, action) -> menus.add(new Menu(label, action)));
        }
        return new Row(id, icon(group.itemId), name, "", value, tone, tip, exact, selected, open, menus);
    }

    static boolean splittable(Receipt receipt) {
        // Owner 2026-10-01 (F02): a single unit cannot be split into shares.
        return !receipt.neutral && !receipt.unpriced && receipt.gain() && receipt.quantity >= 2;
    }

    void openExact(String identifier, String id) {
        actions.selectionChanged(identifier, id, null);
    }

    /** A receipt row opens its receipt under the list; clicking the open one closes it again. */
    void clickReceipt(String identifier, String id) {
        var open = data.detail;
        var chosen = open.exact;
        if (chosen != null && (open.group.market ? chosen.contributionId.equals(id)
                        : chosen.transactionId.equals(identifier))) {
            actions.selectionChanged(null, null, open.group.semanticGroupId);
        } else {
            openExact(identifier, id);
        }
    }

    /** The selected receipt under its list: why, short GE facts, then its correction. */
    JComponent receipt(LedgerData data, Receipt row) {
        var panel = stack(CARD, 4);
        pad(panel, 4, GAP, 6, GAP);
        var trade = row.marketSettlement;
        boolean realized = trade != null && trade.isRealizedIncluded() && trade.settledQty > 0;
        // Why only earns its space for exceptional states; a clean realized trade says Sold /
        // Bought.
        boolean clean = realized && trade.settledQty >= trade.filledQty && !row.review && !row.corrected && !row.split;
        // The selected row above already names the receipt and its value: say what happened, and
        // when.
        long at = trade == null ? row.at : !realized ? trade.timestampEpochMillis : trade.settlementAtEpochMillis > 0
                                        ? trade.settlementAtEpochMillis : row.at;
        String what = realized ? trade.side == GeOffer.Side.SELL ? "Sold" : "Bought" : trade != null
                                ? orderStateText(trade) : row.verb + (row.actionDisplayName.isEmpty() ? ""
                                                : " · " + row.actionDisplayName);
        panel.add(note(what + " · " + age(nonNeg(data.capturedAt - at)), TEXT));
        var facts = new KeyValue(null);
        if (trade == null) {
            panel.add(note(row.why, LABEL));
            if (!row.tradedItems.isEmpty()) panel.add(note(row.tradedItems, LABEL));
            // Owner 2026-10-01 (F11): a Charges receipt discloses its captured unit price and
            // source.
            if (!row.priceBasis.isEmpty())
                facts.put("Price", row.priceBasis, PLAIN, row.priceBasis);
        } else {
            facts = marketFacts(trade, row, clean ? null : realized ? row.why : orderWhy(trade));
        }
        if (row.corrected || row.split) {
            facts.put("Originally", "automatic · " + exactSigned(row.automaticValue) + " gp", DIM);
            facts.put("Corrected to", row.correctionLabel, PLAIN);
        }
        if (facts.lines() > 0) panel.add(facts);
        if (data.readOnly) {
            panel.add(note(msg("read-corrections-apply-current"), LABEL));
        } else if (pendingPreview != null && row.transactionId.equals(pendingTransaction)) {
            var preview = new KeyValue("PREVIEW");
            if (pendingSplit != null) {
                preview.put("Keep", "×" + exact(pendingSplit[1]), PLAIN)
                        .put("Others", "×" + exact(pendingSplit[2] - pendingSplit[1]), PLAIN);
            }
            panel.add(preview.put("Current Net", exactSigned(pendingPreview.currentNet) + " gp", PLAIN).put(
                                    pendingSplit != null ? "After split" : "After correction",
                                    exactSigned(pendingPreview.afterNet) + " gp", PLAIN).put("Change",
                                    exactSigned(pendingPreview.change) + " gp", sign(pendingPreview.change)));
            panel.add(note(pendingPreview.note(), LABEL));
            JButton confirm = button("Confirm", true, this::confirm);
            confirm.getAccessibleContext().setAccessibleName(msg("confirm-correction"));
            panel.add(row(button("Cancel", false, () -> {
                                        clearPending();
                                        actions.refresh();
                                    }), confirm));
        } else {
            JButton correct = button("Correct ⌄", false, () -> {});
            correct.getAccessibleContext().setAccessibleName(msg("correct-entry"));
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

    /**
     * The human Market lines with their explanations; "Why" leads when the state is exceptional.
     */
    static KeyValue marketFacts(MarketRows.Row trade, Receipt row, String why) {
        var facts = new KeyValue("MARKET");
        if (why != null) facts.put("Why", why, PLAIN, why);
        for (String line : humanLines(trade, row.corrected)) {
            int split = line.indexOf('|');
            String label = split < 0 ? line : line.substring(0, split);
            String value = split < 0 ? "" : line.substring(split + 1);
            facts.put(label, value, PLAIN, marketTip(trade, label, value));
        }
        return facts;
    }

    static String marketTip(MarketRows.Row trade, String label, String value) {
        if (value.endsWith("Corrected")) return msg("result-includes-manual");
        switch (label) {
            case "Received":
                return "not proven".equals(value) ? msg("collected-cash") : msg("received-cash");
            case "Spent":
                return msg("spent-cash");
            case "GE tax":
                return msg("proven-item");
            case "Previously counted":
                return "Unknown".equals(value) ? msg("gp-manager-did") : null;
            case "Result":
                if ("—".equals(value)) {
                    return msg("gp-manager-cannot") + msg("unknown-received")
                            + (trade.knownCostOnly ? msg("proven-ge") : "");
                }
                return value.endsWith("Partial") ? msg("result-includes-units") : null;
            default:
                return null;
        }
    }

    /**
     * Gain / Cost / Transfer / Ignore, then Split and Undo, for one receipt: the same list in its
     * Correct ▾ menu and its row menu. A correction opens its Net preview on that receipt first.
     */
    Map<String, Runnable> corrections(String id, String contributionId, boolean canSplit) {
        var list = new LinkedHashMap<String, Runnable>();
        for (var decision : new Correction[] {
                    Correction.REVENUE, Correction.COST, Correction.TRANSFER, Correction.IGNORE
                }) {
            list.put(correctionLabel(decision), () -> {
                        pendingCorrection = decision;
                        pendingTransaction = id;
                        pendingPreview = actions.preview(id, decision);
                        openExact(id, contributionId);
                    });
        }
        if (canSplit) {
            list.put("Split…", () -> actions.split(id, (split, preview) -> {
                                        clearPending();
                                        pendingTransaction = id;
                                        pendingSplit = split;
                                        pendingPreview = preview;
                                        openExact(id, contributionId);
                                    }));
        }
        list.put(msg("undo-latest-correction-2"), actions::undoCorrection);
        return list;
    }

    void clearPending() {
        pendingCorrection = null;
        pendingTransaction = null;
        pendingPreview = null;
        pendingSplit = null;
    }

    void confirm() {
        if (pendingSplit != null && pendingPreview != null) {
            actions.applySplit(pendingTransaction, pendingSplit, pendingPreview.revision);
            clearPending();
            actions.refresh();
            return;
        }
        if (pendingCorrection == null || pendingPreview == null || pendingTransaction == null)
            return;
        var result = actions.correct(pendingTransaction, pendingCorrection, pendingPreview.revision);
        clearPending();
        actions.refresh();
        if (result != CorrectionOutcome.APPLIED) {
            showNotice(result == CorrectionOutcome.STALE ? msg("entries-changed") : msg("correction-applied"));
        }
    }

    static String correctionLabel(Correction decision) {
        return msg("correct-to." + decision, "Automatic");
    }

    static String reviewLabel(Decision decision) {
        return msg("review." + decision, "Exclude");
    }

    void showNotice(String text) {
        if (!empty(text)) {
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
        card.add(label(value, numbers(12.5f), tone.color), BorderLayout.EAST);
        card.add(label(sub, small(), LABEL), BorderLayout.SOUTH);
        return card;
    }

    ImageIcon icon(int id) {
        return Kit.icon(icons, sprites, id);
    }

    boolean liveCategory() {
        return data != null && data.entry.liveTable != null;
    }

    void scopeTitle() {
        if (data != null) scope.setText(fit(scope, data.scopeName,
                        scope.getWidth() > 0 ? max(1, scope.getWidth() - 30) : 80) + " \u2304");
    }

    void toggleSearch() {
        if (liveCategory()) return;
        searchOpen = !searchOpen;
        if (!searchOpen) setSearchText("");
        actions.searchChanged(search.getText());
    }

    void searched() {
        if (!quietSearch) actions.searchChanged(search.getText());
    }

    void setSearchText(String text) {
        quietSearch = true;
        search.setText(text);
        quietSearch = false;
    }
}

/** Ledger actions: scope menu, search, selection, corrections, splits and review decisions. */
class LedgerController implements LedgerPage.Actions {
    SidebarPanel panel;
    Engine engine;
    boolean live;

    LedgerController(SidebarPanel panel, boolean live) {
        this(panel);
        this.live = live;
    }

    Entry entry() {
        return live ? panel.liveEntry : panel.ledgerEntry;
    }

    void entry(Entry next) {
        if (live) panel.liveEntry = next;
        else panel.ledgerEntry = next;
    }

    public void recent() {
        panel.liveLedger.resetOwner();
        panel.liveEntry = null;
        panel.live.category(null);
        refresh();
    }

    LedgerController(SidebarPanel panel) {
        this.panel = panel;
        engine = panel.engine;
    }

    void mutate(Runnable domain) {
        panel.mutate(domain);
    }

    public void openScopeMenu(JComponent anchor) {
        panel.historyScopeMenuPage = 0;
        showScopeMenu(anchor, panel.historyScopeMenuPage);
    }

    void showScopeMenu(JComponent anchor, int page) {
        var menu = new JPopupMenu();
        menu.add(item("Current Grind", () -> selectScope(CURRENT_GRIND, null, null)));
        menu.add(item("Runs today", () -> selectScope(TODAY, null, null)));
        var history = new ArrayList<>(engine.getHistory());
        history.removeIf(Objects::isNull);
        history.sort(Comparator.comparingLong((Session itemData) -> itemData.startedAtEpochMillis).reversed()
                        .thenComparing(Session::getId));
        if (!history.isEmpty()) {
            menu.addSeparator();
            int size = 10, pages = pageCount(history.size(), size);
            panel.historyScopeMenuPage = clampPage(page, pages);
            int from = panel.historyScopeMenuPage * size, to = min(history.size(), from + size);
            var status = new JMenuItem("History " + (from + 1) + "–" + to + " of " + history.size());
            status.setEnabled(false);
            menu.add(status);
            if (pages > 1) {
                menu.add(item("Previous page", panel.historyScopeMenuPage > 0, () -> SwingUtilities.invokeLater(() ->
                                                        showScopeMenu(anchor, panel.historyScopeMenuPage - 1))));
                menu.add(item("Next page", panel.historyScopeMenuPage + 1 < pages, () -> SwingUtilities.invokeLater(
                                                () -> showScopeMenu(anchor, panel.historyScopeMenuPage + 1))));
                menu.addSeparator();
            }
            for (var session : history.subList(from, to)) {
                menu.add(item(msg("recent-grind") + session.getName(),
                                () -> selectScope(HISTORY, session.getId(), session.getName())));
            }
        }
        menu.show(anchor, 0, anchor.getHeight() + 2);
    }

    void selectScope(LedgerData.Scope scope, String historyId, String historyName) {
        entry(entry().withScope(scope, historyId, historyName));
        refresh();
    }

    public void costViewChanged(LedgerData.CostView view) {
        entry(entry().withCostView(view));
        refresh();
    }

    public void searchChanged(String text) {
        if (live) return;
        panel.ledgerSearch = orEmpty(text);
        entry(entry().withSearch(orEmpty(text)));
        refresh();
    }

    @Override
    public void selectionChanged(String identifier, String id, String groupId) {
        if (live && entry() == null) return;
        String transaction = empty(identifier) ? null : identifier, entry = empty(id) ? null : id;
        String group = empty(groupId) ? null : groupId;
        entry(entry().withSelection(transaction, entry, group));
        refresh();
    }

    public LedgerData.CorrectionPreview preview(String id, Correction decision) {
        return LedgerData.preview(engine, id, decision, now());
    }

    public CorrectionOutcome correct(String id, Correction decision, long previewRevision) {
        if (live && entry() == null) return CorrectionOutcome.REFUSED;
        if (engine.getRevision() != previewRevision) return CorrectionOutcome.STALE;
        boolean applied = engine.correctTransaction(id, decision, now(), "Ledger correction");
        return applied ? CorrectionOutcome.APPLIED : CorrectionOutcome.REFUSED;
    }

    public void split(String identifier,
            BiConsumer<long[], LedgerData.CorrectionPreview> previewed) {
        var session = engine.getActiveSession();
        var transaction = session == null ? null : session.findTransaction(identifier);
        int id = -1;
        long total = 0;
        String name = "";
        if (transaction != null) {
            for (Flow flow : transaction.getFlows()) {
                if (flow.quantityDelta <= 0) continue;
                if (id == -1) {
                    id = flow.itemId;
                    name = flow.itemName;
                } else if (id != flow.itemId) {
                    id = -1;
                    break;
                }
                total += flow.quantityDelta;
            }
        }
        if (id <= 0 || total < 2) {
            // Owner 2026-10-01 (F02): only one kind of item in two or more units can be split.
            panel.shell().tell("Not split", transaction == null ? msg("entry-longer") : msg("split-needs"), true);
            return;
        }
        int splitItem = id;
        long splitTotal = total;
        String splitName = empty(name) ? "this item" : name, owner = session.getId();
        panel.shell().prompt("Split entry", "Keep how many of " + splitName + " \u00d7" + total + " for your share?",
                        "", value -> {
                            long keep;
                            try {
                                keep = Long.parseLong(value.replace(",", ""));
                            } catch (NumberFormatException ex) {
                                keep = -1L;
                            }
                            if (keep <= 0 || keep >= splitTotal) {
                                panel.shell().tell("Not split", msg("enter-number") + (splitTotal - 1), true);
                                return;
                            }
                            var now = engine.getActiveSession();
                            if (now == null || !owner.equals(now.getId()) || !splittableSplit(
                                            now.findTransaction(identifier), splitItem, splitTotal)) {
                                panel.shell().tell("Not split", msg("entry-changed"), true);
                                return;
                            }
                            // Owner 1.1: the split shows its Net preview first, like every other
                            // correction.
                            previewed.accept(new long[] {splitItem, keep, splitTotal}, LedgerData.splitPreview(engine,
                                            identifier, splitItem, keep, now()));
                        });
    }

    public void applySplit(String id, long[] split, long previewRevision) {
        String owner = engine.getActiveSession() == null ? "" : engine.getActiveSession().getId();
        mutate(() -> {
                    // The sheet may be stale: re-check the session and the exact receipt before
                    // mutating.
                    var now = engine.getActiveSession();
                    Transaction current = now == null || !owner.equals(now.getId()) ? null
                                    : now.findTransaction(id);
                    if (engine.getRevision() != previewRevision) {
                        panel.shell().tell("Not split", msg("entries-changed"), true);
                        return;
                    }
                    if (!splittableSplit(current, (int) split[0], split[2])) {
                        panel.shell().tell("Not split", msg("entry-changed"), true);
                        return;
                    }
                    if (!engine.splitItem(id, (int) split[0], split[1], now(), "Ledger split")) {
                        panel.shell().tell("Not split", msg("split-applied"), true);
                    }
                });
    }

    /** One item kind only, and the quantity captured at the prompt is still exact. */
    static boolean splittableSplit(Transaction transaction, int id, long total) {
        if (transaction == null) return false;
        int found = -1;
        long sum = 0;
        for (Flow flow : transaction.getFlows()) {
            if (flow.quantityDelta <= 0) continue;
            if (found == -1) found = flow.itemId;
            else if (found != flow.itemId) return false;
            sum += flow.quantityDelta;
        }
        return found == id && sum == total;
    }

    String menuTarget = "";

    /** Pins an open mutation menu; the callback re-checks it against the live session. */
    @Override
    public String pinMenuTarget() {
        var active = engine.getActiveSession();
        menuTarget = active == null ? "" : active.getId();
        return menuTarget;
    }

    /**
     * Owner 2026-10-01 (F01): a stale menu never mutates; scope and session are re-checked here.
     */
    boolean undoAllowed() {
        var active = engine.getActiveSession();
        return entry() != null && !LedgerData.readOnly(entry()) && active != null && !menuTarget.isEmpty()
                && menuTarget.equals(active.getId());
    }

    public void undoCorrection() {
        if (!undoAllowed()) return;
        mutate(() -> engine.undoCorrection(now()));
    }

    @Override
    public void undoLast() {
        if (!undoAllowed()) return;
        mutate(engine::undoTransaction);
    }

    @Override
    public void restoreUndo() {
        if (!undoAllowed()) return;
        mutate(engine::restoreUndo);
    }

    @Override
    /** Previews one Review decision, confirms it with its Net change, then applies it undoably. */
    public void decide(String id, Decision decision) {
        Engine.DecisionPreview preview = engine.previewDecision(decision,
                        row -> row != null && id.equals(row.transactionId), now());
        if (preview.rowCount() != 1) return;
        String message = correctionLabel(decision.toCorrection()) + " for this entry. Net changes by "
                        + signed(preview.netDelta) + " gp.";
        panel.shell().confirm("Review decision", message, "Apply", () -> mutate(() -> {
                                            if (engine.applyDecision(preview, now()) < 0) {
                                                SwingUtilities.invokeLater(() -> (live ? panel.liveLedger : panel.ledger).showNotice(
                                                                        msg("entries-changed")));
                                            }
                                        }));
    }

    public void refresh() {
        panel.refresh();
    }

    static String correctionLabel(Correction decision) {
        return LedgerPage.correctionLabel(decision);
    }
}
