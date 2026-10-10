package com.gpmanager;

import static com.gpmanager.GameData.msg;
import static com.gpmanager.Kit.*;
import static com.gpmanager.LedgerData.Scope.*;
import static com.gpmanager.Phrases.*;

import static java.lang.Math.*;

import com.gpmanager.LedgerPage.CorrectionOutcome;

import java.util.*;

import javax.swing.*;

/** Ledger actions: scope menu, search, selection, corrections, splits and review decisions. */
class LedgerController implements LedgerPage.Actions {
    SidebarPanel panel;
    Engine engine;

    LedgerController(SidebarPanel panel) {
        this.panel = panel;
        this.engine = panel.engine;
    }

    void mutate(Runnable domain) {
        panel.mutate(domain);
    }

    public void openScopeMenu(JComponent anchor) {
        panel.historyScopeMenuPage = 0;
        showScopeMenu(anchor, panel.historyScopeMenuPage);
    }

    void showScopeMenu(JComponent anchor, int pageIndex) {
        var menu = new JPopupMenu();
        menu.add(item("Current Grind", () -> selectScope(CURRENT_GRIND, null, null)));
        menu.add(item("Runs today", () -> selectScope(TODAY, null, null)));
        var history = new ArrayList<Session>(engine.getHistory());
        history.removeIf(Objects::isNull);
        history.sort(
                Comparator.comparingLong((Session itemData) -> itemData.startedAtEpochMillis)
                        .reversed()
                        .thenComparing(Session::getId));
        if (!history.isEmpty()) {
            menu.addSeparator();
            int size = 10;
            int pages = max(1, (history.size() + size - 1) / size);
            panel.historyScopeMenuPage = max(0, min(pageIndex, pages - 1));
            int from = panel.historyScopeMenuPage * size;
            int to = min(history.size(), from + size);
            var status =
                    new JMenuItem("History " + (from + 1) + "–" + to + " of " + history.size());
            status.setEnabled(false);
            menu.add(status);
            if (pages > 1) {
                menu.add(
                        item(
                                "Previous page",
                                panel.historyScopeMenuPage > 0,
                                () ->
                                        SwingUtilities.invokeLater(
                                                () ->
                                                        showScopeMenu(
                                                                anchor,
                                                                panel.historyScopeMenuPage - 1))));
                menu.add(
                        item(
                                "Next page",
                                panel.historyScopeMenuPage + 1 < pages,
                                () ->
                                        SwingUtilities.invokeLater(
                                                () ->
                                                        showScopeMenu(
                                                                anchor,
                                                                panel.historyScopeMenuPage + 1))));
                menu.addSeparator();
            }
            for (Session session : history.subList(from, to)) {
                menu.add(
                        item(
                                msg("recent-grind") + session.getName(),
                                () -> selectScope(HISTORY, session.getId(), session.getName())));
            }
        }
        menu.show(anchor, 0, anchor.getHeight() + 2);
    }

    void selectScope(LedgerData.Scope scope, String historyId, String historyName) {
        panel.ledgerEntry = panel.ledgerEntry.withScope(scope, historyId, historyName);
        refresh();
    }

    public void costViewChanged(LedgerData.CostView view) {
        panel.ledgerEntry = panel.ledgerEntry.withCostView(view);
        refresh();
    }

    public void searchChanged(String text) {
        panel.ledgerSearch = orEmpty(text);
        panel.ledgerEntry = panel.ledgerEntry.withSearch(panel.ledgerSearch);
        refresh();
    }

    @Override
    public void selectionChanged(String transactionId, String contributionId, String groupId) {
        String transaction = empty(transactionId) ? null : transactionId;
        String contribution = empty(contributionId) ? null : contributionId;
        String group = empty(groupId) ? null : groupId;
        panel.ledgerEntry = panel.ledgerEntry.withSelection(transaction, contribution, group);
        refresh();
    }

    public LedgerData.CorrectionPreview preview(String transactionId, Correction correction) {
        return LedgerData.preview(engine, transactionId, correction, SafeMath.now());
    }

    public CorrectionOutcome correct(
            String transactionId, Correction correction, long previewRevision) {
        if (engine.getRevision() != previewRevision) return CorrectionOutcome.STALE;
        boolean applied =
                engine.correctTransaction(
                        transactionId, correction, SafeMath.now(), "Ledger correction");
        return applied ? CorrectionOutcome.APPLIED : CorrectionOutcome.REFUSED;
    }

    public void split(
            String transactionId,
            java.util.function.BiConsumer<long[], LedgerData.CorrectionPreview> previewed) {
        Session session = engine.getActiveSession();
        Transaction transaction = session == null ? null : session.findTransaction(transactionId);
        int itemId = -1;
        long total = 0L;
        String itemName = "";
        if (transaction != null) {
            for (Flow flow : transaction.getFlows()) {
                if (flow == null || flow.quantityDelta <= 0L) continue;
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
            panel.shell()
                    .tell(
                            "Not split",
                            transaction == null ? msg("entry-longer") : msg("split-needs"),
                            true);
            return;
        }
        int splitItem = itemId;
        long splitTotal = total;
        String splitName = itemName == null || itemName.isEmpty() ? "this item" : itemName;
        String owner = session.getId();
        panel.shell()
                .prompt(
                        "Split entry",
                        "Keep how many of " + splitName + " \u00d7" + total + " for your share?",
                        "",
                        value -> {
                            long keep;
                            try {
                                keep = Long.parseLong(value.replace(",", ""));
                            } catch (NumberFormatException ex) {
                                keep = -1L;
                            }
                            if (keep <= 0L || keep >= splitTotal) {
                                panel.shell()
                                        .tell(
                                                "Not split",
                                                msg("enter-number") + (splitTotal - 1),
                                                true);
                                return;
                            }
                            Session now = engine.getActiveSession();
                            if (now == null
                                    || !owner.equals(now.getId())
                                    || !splittableSplit(
                                            now.findTransaction(transactionId),
                                            splitItem,
                                            splitTotal)) {
                                panel.shell().tell("Not split", msg("entry-changed"), true);
                                return;
                            }
                            // Owner 1.1: the split shows its Net preview first, like every other
                            // correction.
                            previewed.accept(
                                    new long[] {splitItem, keep, splitTotal},
                                    LedgerData.splitPreview(
                                            engine,
                                            transactionId,
                                            splitItem,
                                            keep,
                                            SafeMath.now()));
                        });
    }

    public void applySplit(String transactionId, long[] split, long previewRevision) {
        String owner = engine.getActiveSession() == null ? "" : engine.getActiveSession().getId();
        mutate(
                () -> {
                    // The sheet may be stale: re-check the session and the exact receipt before
                    // mutating.
                    Session now = engine.getActiveSession();
                    Transaction current =
                            now == null || !owner.equals(now.getId())
                                    ? null
                                    : now.findTransaction(transactionId);
                    if (engine.getRevision() != previewRevision) {
                        panel.shell().tell("Not split", msg("entries-changed"), true);
                        return;
                    }
                    if (!splittableSplit(current, (int) split[0], split[2])) {
                        panel.shell().tell("Not split", msg("entry-changed"), true);
                        return;
                    }
                    if (!engine.splitItem(
                            transactionId,
                            (int) split[0],
                            split[1],
                            SafeMath.now(),
                            "Ledger split")) {
                        panel.shell().tell("Not split", msg("split-applied"), true);
                    }
                });
    }

    /** One item kind only, and the quantity captured at the prompt is still exact. */
    static boolean splittableSplit(Transaction transaction, int itemId, long total) {
        if (transaction == null) return false;
        int found = -1;
        long sum = 0L;
        for (Flow flow : transaction.getFlows()) {
            if (flow == null || flow.quantityDelta <= 0L) continue;
            if (found == -1) found = flow.itemId;
            else if (found != flow.itemId) return false;
            sum += flow.quantityDelta;
        }
        return found == itemId && sum == total;
    }

    String menuTarget = "";

    /** Pins an open mutation menu; the callback re-checks it against the live session. */
    @Override
    public String pinMenuTarget() {
        Session active = engine.getActiveSession();
        menuTarget = active == null ? "" : active.getId();
        return menuTarget;
    }

    /**
     * Owner 2026-10-01 (F01): a stale menu never mutates; scope and session are re-checked here.
     */
    boolean undoAllowed() {
        Session active = engine.getActiveSession();
        return !LedgerData.readOnly(panel.ledgerEntry)
                && active != null
                && !menuTarget.isEmpty()
                && menuTarget.equals(active.getId());
    }

    public void undoCorrection() {
        if (!undoAllowed()) return;
        mutate(() -> engine.undoCorrection(SafeMath.now()));
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
    public void decide(String transactionId, Decision decision) {
        Engine.DecisionPreview preview =
                engine.previewDecision(
                        decision,
                        row -> row != null && transactionId.equals(row.transactionId),
                        SafeMath.now());
        if (preview.rowCount() != 1) return;
        String message =
                correctionLabel(decision.toCorrection())
                        + " for this entry. Net changes by "
                        + Fmt.signed(preview.netDelta)
                        + " gp.";
        panel.shell()
                .confirm(
                        "Review decision",
                        message,
                        "Apply",
                        () ->
                                mutate(
                                        () -> {
                                            if (engine.applyDecision(preview, SafeMath.now()) < 0) {
                                                SwingUtilities.invokeLater(
                                                        () ->
                                                                panel.ledger.showNotice(
                                                                        msg("entries-changed")));
                                            }
                                        }));
    }

    public void refresh() {
        panel.refresh();
    }

    static String correctionLabel(Correction correction) {
        return LedgerPage.correctionLabel(correction);
    }
}
