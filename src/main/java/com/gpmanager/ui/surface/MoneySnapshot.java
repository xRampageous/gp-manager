package com.gpmanager;

import static com.gpmanager.SafeMath.*;

import static java.util.Collections.*;

import com.gpmanager.MarketRows.Row;

import java.util.*;
import java.util.function.Predicate;

/**
 * One owned, unfiltered financial snapshot. Session save stamps cover close/history changes that do
 * not advance Engine.revision. Clock, pause and activity are overlays, never cached money. A
 * changed Market presentation reuses the same financial facts rather than refolding receipts.
 */
class MoneySnapshot {
    final List<Object> key;
    final List<Row> marketRows;
    final Map<Session, SessionFacts> facts;
    final Map<String, Rollup> rollups;

    /** Derived scopes memoize only from the captured facts; they cannot read mutable receipts. */
    final Map<List<Session>, SessionScope> scopes = new HashMap<>();

    static MoneySnapshot of(Engine engine) {
        List<Session> sessions = engine.uniqueSessions();
        var keyParts = new ArrayList<Object>();
        keyParts.add(engine.getRevision());
        keyParts.add(engine.getActiveSession());
        keyParts.add(engine.getHistory());
        for (Session session : sessions) {
            keyParts.add(session);
            keyParts.add(session == null ? 0L : session.saveStamp);
            keyParts.add(session == null ? "" : session.getGrindId());
        }
        for (SavedState.SavedGrind grind : engine.getSavedGrinds(true))
            keyParts.add(grind.getGrindId());
        List<Row> rows = engine.marketRows();
        MoneySnapshot cached = engine.snapshot;
        if (cached != null && cached.key.equals(keyParts) && cached.marketRows.equals(rows))
            return cached;
        return engine.snapshot = new MoneySnapshot(engine, sessions, keyParts, rows, cached);
    }

    MoneySnapshot(
            Engine engine,
            List<Session> sessions,
            List<Object> keyParts,
            List<Row> rows,
            MoneySnapshot previous) {
        key = unmodifiableList(keyParts);
        marketRows = unmodifiableList(new ArrayList<>(rows));
        if (previous != null && previous.key.equals(key)) {
            facts = previous.facts;
            rollups = previous.rollups;
            return;
        }
        var built = new IdentityHashMap<Session, SessionFacts>();
        for (Session session : sessions)
            if (session != null && !built.containsKey(session)) {
                SessionFacts reused = previous == null ? null : previous.facts.get(session);
                built.put(
                        session,
                        session.isClosed() && reused != null && reused.stamp == session.saveStamp
                                ? reused
                                : new SessionFacts(session));
            }
        facts = unmodifiableMap(built);
        var byGrind = new HashMap<String, Rollup>();
        for (SavedState.SavedGrind grind : engine.getSavedGrinds(true))
            byGrind.put(grind.getGrindId(), new Rollup(engine.completedLinked(grind.getGrindId())));
        rollups = unmodifiableMap(byGrind);
    }

    /** Canonical metrics keep the exact live clock and Session's saturating rate formula. */
    Metrics metrics(Session session, long now) {
        return session == null ? Metrics.none() : fact(session).metrics(session, now);
    }

    SessionFacts fact(Session session) {
        return Objects.requireNonNull(facts.get(session), "Session outside captured scope");
    }

    /** One session's shared facts, read from its retained history copy when it has one. */
    GrindHistory.Run run(Engine engine, Session session, long now) {
        Session retained = engine.historySession(session.getId());
        return new GrindHistory.Run(session, metrics(retained != null ? retained : session, now));
    }

    SessionScope scope(List<Session> sessions) {
        List<Session> scopeKey = unmodifiableList(new ArrayList<>(sessions));
        return scopes.computeIfAbsent(scopeKey, SessionScope::new);
    }

    /**
     * Visibility derives another presentation from frozen receipts; hero folds remain unfiltered.
     */
    Semantics.Result view(SessionScope scope, Predicate<Flow> visible) {
        return visible == null
                ? scope.groups
                : Semantics.capture(scope.transactions, marketRows, scope.ids, visible);
    }

    /** Canonical per-session money and independently ordered Ledger deltas. */
    static class SessionFacts {
        /** Closed facts reuse the existing save stamp; a correction invalidates them. */
        final long stamp;

        final Metrics money;
        final List<Transaction> transactions;
        final List<Long> deltas;
        final boolean cut;
        final int review;
        final Accounting.MarketFold market;

        SessionFacts(Session session) {
            stamp = session.saveStamp;
            money = session.metrics(0L);
            Session.CompactedContribution compacted = session.compactedContribution();
            cut = compacted.rows > 0L || compacted.revenue != 0L || compacted.costs != 0L;
            var copies = new ArrayList<Transaction>();
            var deltaList = new ArrayList<Long>();
            deltaList.add(compacted.getNet());
            int reviews = 0;
            for (Transaction transaction : session.getTransactions()) {
                if (transaction == null) continue;
                Transaction copied = copy(transaction);
                copies.add(copied);
                Accounting.Amounts amounts = Accounting.transaction(copied);
                if (amounts.included) deltaList.add(amounts.getNet());
                if (ReviewRules.needsOwnerDecision(copied)) reviews++;
            }
            transactions = unmodifiableList(copies);
            deltas = unmodifiableList(deltaList);
            review = reviews;
            market = Accounting.marketFold(transactions);
        }

        Metrics metrics(Session session, long now) {
            long elapsed = session.getElapsedMillis(now);
            return new Metrics(
                    session.getActivityHint(),
                    session.paused,
                    elapsed,
                    money.revenue,
                    money.costs,
                    money.net,
                    Session.hourly(money.net, elapsed),
                    money.suppliesCosts,
                    money.otherCosts,
                    money.costSplitAvailable);
        }
    }

    /** The last/best rollup is over completed lineage members, in the original order. */
    class Rollup {
        final int count;
        final boolean has;
        final long last, at, best;

        Rollup(List<Session> sessions) {
            Session latest = null;
            long bestNet = Long.MIN_VALUE, lastNet = 0L;
            for (Session session : sessions) {
                long net = runMoney(session);
                if (latest == null || session.startedAtEpochMillis > latest.startedAtEpochMillis) {
                    latest = session;
                    lastNet = net;
                }
                bestNet = Math.max(bestNet, net);
            }
            count = sessions.size();
            has = latest != null;
            last = lastNet;
            at = has ? latest.startedAtEpochMillis : 0L;
            best = bestNet == Long.MIN_VALUE ? 0L : bestNet;
        }

        long runMoney(Session session) {
            return fact(session).money.net;
        }
    }

    /** Scope construction preserves saturation order, compaction, Market ownership and groups. */
    class SessionScope {
        final Set<String> ids;
        final List<Transaction> transactions;
        final Map<String, Row> markets;
        final long net;
        final boolean cut;
        final Semantics.Result groups;

        SessionScope(List<Session> sessions) {
            var idSet = new LinkedHashSet<String>();
            var all = new ArrayList<Transaction>();
            long total = 0L;
            boolean anyCut = false;
            for (Session session : sessions) {
                if (session == null) continue;
                SessionFacts sessionFacts = fact(session);
                idSet.add(session.getId());
                all.addAll(sessionFacts.transactions);
                anyCut |= sessionFacts.cut;
                for (long delta : sessionFacts.deltas) total = safeAdd(total, delta);
            }
            ids = unmodifiableSet(idSet);
            transactions = unmodifiableList(all);
            net = total;
            cut = anyCut;
            var byPresentation = new HashMap<String, Row>();
            for (Row row : marketRows)
                if (row != null && ids.contains(row.scopeSessionId))
                    byPresentation.put(row.presentationId, row);
            markets = unmodifiableMap(byPresentation);
            groups = Semantics.capture(transactions, marketRows, ids, null);
        }
    }

    /** Own the receipt and its flows: later corrections cannot mutate this financial revision. */
    static Transaction copy(Transaction source) {
        Transaction copy = new Transaction();
        copy.id = source.id;
        copy.timestampEpochMillis = source.timestampEpochMillis;
        copy.type = source.type;
        copy.context = source.context;
        copy.note = source.note;
        copy.activityName = source.activityName;
        copy.counted = source.counted;
        copy.revenue = source.revenue;
        copy.costs = source.costs;
        copy.confidence = source.confidence;
        copy.explanation = source.explanation;
        copy.correction = source.correction;
        copy.correctionReason = source.correctionReason;
        copy.encounterId = source.encounterId;
        copy.sourceClaimId = source.sourceClaimId;
        copy.actionKind = source.actionKind;
        copy.observedActionLabel = source.observedActionLabel;
        copy.castItemId = source.castItemId;
        var flows = new ArrayList<Flow>();
        for (Flow flow : source.getFlows()) {
            if (flow == null) {
                flows.add(null);
                continue;
            }
            Flow copiedFlow = new Flow();
            copiedFlow.itemId = flow.itemId;
            copiedFlow.itemName = flow.itemName;
            copiedFlow.quantityDelta = flow.quantityDelta;
            copiedFlow.unitPrice = flow.unitPrice;
            copiedFlow.valueDelta = flow.valueDelta;
            copiedFlow.priceSource = flow.priceSource;
            copiedFlow.priceCapturedAtEpochMillis = flow.priceCapturedAtEpochMillis;
            flows.add(copiedFlow);
        }
        copy.flows = unmodifiableList(flows);
        return copy;
    }
}
