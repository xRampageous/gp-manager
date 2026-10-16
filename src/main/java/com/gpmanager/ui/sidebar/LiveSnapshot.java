package com.gpmanager;

import static com.gpmanager.Fmt.*;
import static com.gpmanager.GameData.msg;
import static com.gpmanager.SafeMath.*;
import static java.lang.Math.*;

import com.gpmanager.Accounting.MarketFold;
import com.gpmanager.LedgerData.CostView;
import com.gpmanager.MarketRows.Row;
import com.gpmanager.Semantics.*;
import java.awt.Color;
import java.util.*;
import javax.inject.*;
import lombok.*;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.*;
import net.runelite.client.plugins.grounditems.*;
import net.runelite.client.util.*;
import org.slf4j.*;

/**
 * Everything the Live page paints, captured in one read from the current schema-102 engine so the
 * page never holds engine references while laying out. Pure data; built by {@link #capture}.
 *
 * <p>Forward-port note: this adapter reads current canonical queries only. Facts whose producer no
 * longer exists (session goals, activity averages, party reports, live encounters, the retired
 * notable-drop threshold) are absent rather than re-derived from unrelated numbers.
 */
@AllArgsConstructor
class LiveSnapshot {
    /** Stable session identity; names are editable and may repeat between runs. */
    String sessionId;

    static final int RECENT_ROWS = 15;

    /** One session goal as the Live card shows it; read-only, from the session's current target. */
    @AllArgsConstructor
    static class Goal {
        long target;
        long current;
        boolean reached;

        /** "200k", or the Active Time target for a tracked-time goal. */
        String label;

        /** "62%". */
        String progress;

        /** The pace line while the rate is trustworthy; null when it is not. */
        String eta;

        double fraction;

        /** True when this card tracks Active Time rather than Net (owner 2026-10-01, F10). */
        boolean time;

        /**
         * The tracked Active Time pair ({@code 1h 02m/2h 30m · 47%}) when the session has a time
         * target; null with none. A Net card with both targets also shows it (owner live
         * 2026-10-13).
         */
        String timeDetail;

        /**
         * Remaining Active Time on the time window while one is pending; -1 when none is set
         * (owner live 2026-10-13, HUD warning for both-target runs).
         */
        long timeRemainingMillis;

        /** True when a time window has reached its target, on any card (owner live 2026-10-13). */
        boolean timeDone;

        /** True while the timer's own pause holds (owner live 2026-10-14). */
        boolean timerPaused;
    }

    /** One exact item contribution or an honestly labelled composite activity row. */
    @AllArgsConstructor
    static class Recent {
        int itemId;
        String name;
        String qty;
        long value;
        boolean neutral;

        /** True when the lead flow has no price: the row shows an unknown marker, never 0. */
        boolean unpriced;

        String tag;

        /** How many receipts this row coalesced; 1 for a single receipt. */
        int receipts;

        /** The newest canonical receipt behind the row, for Ledger context. */
        String receiptId;

        String contributionId;
        String actionLabel;
        long quantity;
        boolean composite;
        boolean market;
        boolean valueAvailable;

        /** The Costs view this row opens in the Ledger; null off the Costs table. */
        CostView ledgerCostView;

        /** True when this row belongs to the Review audit rather than a financial table. */
        boolean ledgerReview;

        /** Canonical realized Market result; the secondary "Result" line for Market rows. */
        long marketResult;

        /** True when {@link #value} is the observed signed settlement amount (SELL / BUY). */
        boolean marketSettlementValue;

        /**
         * "Sold" / "Bought" once settled, else where the offer stands ("Offering", "Pending");
         * empty off-Market.
         */
        String marketSide;

        /**
         * A GE offer still on the exchange (trading, or filled and not collected): Live lists it
         * under Offers.
         */
        boolean open;

        /** A charge or dose spend row: the charge hide may be offered on it (owner 1.3.4). */
        boolean chargeRow;

        /** Ground Items' highlight colour for a highlighted gain; null otherwise. */
        Color highlight;

        /**
         * The row's share of Net. A Market row moves Net by its Result only, never by the cash it
         * moved: a GE buy is neutral (owner live 2026-10-15), an unproven Result counts nothing.
         */
        long net() {
            return !market ? value : valueAvailable ? marketResult : 0L;
        }
    }

    boolean hasSession;
    String ownerLabel;
    long elapsedMillis;
    boolean paused;
    boolean idle;
    long net;
    long gains;

    /** Non-consumable costs: deaths, tax, fees, drops. {@code -1} when the split is unavailable. */
    long loss;

    /**
     * Consumables: food, potions, runes, ammo, charges. {@code -1} when the split is unavailable.
     */
    long supplies;

    /** Total booked cost; always the truthful figure, split or not. */
    long costs;

    boolean costSplitAvailable;
    boolean rateEstablished;
    long gpPerHour;
    Goal goal;

    /** Realized Market result for the active scope, separated from ordinary Gains/Losses. */
    long marketResult;

    /** Pending/unrealised Market context rows; informational only, never counted in Net. */
    int marketPending;

    /** True when the current world/economy blocks ordinary automatic market pricing. */
    /** Free play (the engine's durable owner) rather than a named session. */
    boolean freePlay;

    /** Activity context label for the status line; empty when the owner name speaks for it. */
    String activityLabel;

    /** Freshest client-thread interaction target; the shared activity label reads it. */
    String target;

    /** Client-thread PvP sample, presentation only. */
    boolean pvpPossible;

    boolean skulled;
    boolean protectItem;
    long risk;
    int reviewCount;
    List<Recent> recent;

    /** True for any session that has booked a fight. */
    boolean pvpSession;

    int kills;
    int deaths;
    long bestKill;

    /** Loot keys and chests not opened yet; a count, never a GP guess. */
    int pendingKeys;

    /** The exact price of the pending chests' observed contents; 0 until a chest is seen. */
    long pendingKeyValue;

    /** Items waiting at a death reclaim; 0 when nothing is waiting. */
    long reclaimItems;

    /** Kills since the last death this Grind; 0 after a death. */
    int streak;

    /** Longest run of kills without a death this Grind. */
    int bestStreak;

    /** Paused by logout: tracking waits for the next login, numbers stay as they were. */
    boolean loggedOut;

    /** Seconds until the pending factory-reset resume fires; 0 when none is armed. */
    long resumeInSeconds;

    /** True after a resume while the tracker's derived displays settle; bookings are immediate. */
    boolean calibrating;

    /** True while a world hop is in flight: the status word says Reconnecting, not Logged out. */
    boolean hopping;

    /**
     * @param context plugin-side idle state, owner marks and loot visibility (may be null)
     */
    static LiveSnapshot capture(Engine engine, long now, LiveContext context) {
        LiveContext ctx = context == null ? LiveContext.NONE : context;
        MoneySnapshot summary = engine.summary();
        Metrics metrics = summary.metrics(engine.getActiveSession(), now);
        Session active = engine.getActiveSession();
        boolean custom = engine.isCustomActive();
        boolean freePlay = active == null || !custom;
        String owner = active == null ? "Free play" : active.getName();
        long elapsed = metrics.elapsedMillis;
        MoneySnapshot.SessionFacts facts = active == null ? null : summary.fact(active);
        int reviewCount = facts == null ? 0 : facts.review;
        List<Transaction> transactions =
                facts == null ? Collections.emptyList() : facts.transactions;
        // One shared projection for the whole snapshot: Recent rows and the Market separation. The
        // Market rows are rebuilt on every read, so they are read once.
        List<Row> marketRows = summary.marketRows;
        // Hero categories are canonical and unfiltered: the whole counted Market-context
        // population is removed from ordinary Gains/Supplies/Losses and represented once by its
        // correction-aware net. Recent Activity display filters never enter this math.
        MarketFold marketFold = facts == null ? Accounting.marketFold(transactions) : facts.market;
        long marketResult = marketFold.getNet();
        int marketPending = 0;
        var marketByPresentation = new HashMap<String, Row>();
        for (Row row : marketRows) {
            if (row != null) {
                marketByPresentation.put(row.presentationId, row);
                marketPending += LedgerData.isPending(row.lifecycle) ? 1 : 0;
            }
        }
        PkTally pk = active == null ? new PkTally() : active.pk();
        boolean pvpSession = pk.encounters() > 0;
        return new LiveSnapshot(
                active == null ? "" : active.getId(),
                active != null,
                owner,
                elapsed,
                metrics.paused,
                ctx.idle,
                metrics.net,
                visibleGains(metrics, marketFold),
                visibleLosses(metrics, marketFold),
                visibleSupplies(metrics, marketFold),
                visibleCosts(metrics, marketFold),
                metrics.costSplitAvailable,
                RateReadiness.isEstablished(elapsed),
                metrics.profitPerHour,
                goal(active, metrics, elapsed),
                marketResult,
                marketPending,
                freePlay,
                ctx.activity,
                ctx.target,
                ctx.pvp.pvpPossible,
                ctx.pvp.skulled,
                ctx.pvp.protectItem,
                ctx.pvp.risk,
                reviewCount,
                recent(engine, active, transactions, marketRows, marketByPresentation, ctx),
                pvpSession,
                pk.kills,
                pk.deaths,
                engine.bestKill(),
                engine.getPendingClaims().size(),
                engine.pendingKeysValue(now),
                engine.deathStatus().awaiting
                        ? max(1L, engine.deathStatus().outstandingItemCount)
                        : 0L,
                max(0, pk.streak),
                pk.bestStreak,
                active == null
                        ? ctx.offline
                        : active.paused && active.getPauseReason() == PauseReason.LIFECYCLE,
                engine.autoResumeSeconds(now),
                engine.calibrating(now),
                ctx.hopping);
    }

    /**
     * The read-only target card: the session's current {@code profitTargetGp} against its booked
     * net. The historical goal editor, goal persistence and multi-kind goals are retired; no editor
     * affordance and no ETA without a trustworthy rate.
     */
    static Goal goal(Session active, Metrics metrics, long elapsed) {
        if (active == null) return null;
        Long target = active.getProfitTargetGp();
        Long timeTarget = active.getActiveTimeTargetMillis();
        if (target != null && target > 0L) {
            long current = active.profitTargetProgress(metrics.net);
            double fraction = max(0d, min(1d, current / (double) target));
            boolean reached = current >= target;
            String eta = null;
            String timeDetail = null;
            long timeRemaining = -1L;
            boolean timeDone = false;
            boolean timerPaused = false;
            // The aim's own pace: Net and Active Time since it was set, so free play before it
            // never counts (owner 2026-10-16).
            long since = active.profitTargetActiveMillis(elapsed);
            boolean established = RateReadiness.isEstablished(since);
            long rate = since <= 0L ? 0L : round(current * 3_600_000d / since);
            if (timeTarget != null && timeTarget > 0L) {
                // Owner live 2026-10-13: with both targets set the hero keeps the live Net read
                // and the card adds the tracked Active Time pair; the countdown replaces the
                // Net-rate estimate.
                long timeElapsed = active.getActiveTimeTargetWindowMillis(elapsed);
                timeDetail = timeDetail(timeElapsed, timeTarget);
                timeRemaining = max(0L, timeTarget - timeElapsed);
                timeDone = timeElapsed >= timeTarget;
                timerPaused = active.isTimeTargetPaused();
                GrindsData.Pace pace =
                        GrindsData.pace(
                                null,
                                timeTarget,
                                current,
                                established,
                                rate,
                                timeElapsed);
                if (timeDone && reached) {
                    // Owner 2026-10-14: both aims done keeps a short projection, not a blank line.
                    eta = established ? GrindsData.nextHour(current, rate) : null;
                } else if (timeDone) {
                    eta = pace == null || !pace.available ? null : pace.line;
                } else if (pace != null && pace.available) {
                    eta = durationCount(timeRemaining) + " left · " + pace.line;
                }
            } else if (established) {
                // Owner 2026-10-14: a reached Net aim keeps the short next-hour projection; an
                // established non-positive rate says so honestly (owner live 2026-10-13).
                long minutes =
                        rate > 0L
                                ? round((target - current) * 60d / rate)
                                : 0L;
                eta =
                        reached
                                ? GrindsData.nextHour(current, rate)
                                : rate <= 0L
                                        ? "Not earning at this rate"
                                        : (minutes < 1L ? "<1m" : "~" + GrindsData.durationMinutes(minutes))
                                                + msg("play-left");
            }
            return new Goal(
                    target,
                    current,
                    reached,
                    compact(target),
                    reached ? "reached" : percent(fraction),
                    eta,
                    fraction,
                    false,
                    timeDetail,
                    timeRemaining,
                    timeDone,
                    timerPaused);
        }
        // Owner 2026-10-01 (F10): a time-only target is still a target; the card tracks Active
        // Time.
        if (timeTarget == null || timeTarget <= 0L) return null;
        long timeElapsed = active.getActiveTimeTargetWindowMillis(elapsed);
        double timeFraction = max(0d, min(1d, timeElapsed / (double) timeTarget));
        boolean timeReached = timeElapsed >= timeTarget;
        // The shared Pace answer for a time target: where the booked Net lands at the target time.
        GrindsData.Pace pace =
                timeReached
                        ? null
                        : GrindsData.pace(
                                null,
                                timeTarget,
                                metrics.net,
                                RateReadiness.isEstablished(elapsed),
                                metrics.profitPerHour,
                                timeElapsed);
        return new Goal(
                timeTarget,
                timeElapsed,
                timeReached,
                durationShort(timeTarget),
                timeReached ? "reached" : percent(timeFraction),
                pace == null || !pace.available ? null : pace.line,
                timeFraction,
                true,
                timeDetail(timeElapsed, timeTarget),
                max(0L, timeTarget - timeElapsed),
                timeReached,
                active.isTimeTargetPaused());
    }

    /** "12s / 2m \u00b7 7%": the Active Time window pair a card shows for a time target. */
    static String timeDetail(long elapsed, long timeTarget) {
        // The pair never runs past its own aim: 2m/2m stays 2m/2m (owner live 2026-10-13).
        long shown = min(elapsed, timeTarget);
        double fraction = max(0d, min(1d, elapsed / (double) timeTarget));
        return durationCount(shown) + " / " + durationShort(timeTarget) + " \u00b7 " + percent(fraction);
    }

    /** Visible ordinary Gains: canonical unfiltered revenue minus the counted Market fold. */
    static long visibleGains(Metrics metrics, MarketFold market) {
        return safeSubtract(metrics.revenue, market.revenue);
    }

    /** Visible Supplies, or -1 when the canonical cost split is unavailable (combined fallback). */
    static long visibleSupplies(Metrics metrics, MarketFold market) {
        return metrics.costSplitAvailable
                ? safeSubtract(metrics.suppliesCosts, market.supplies)
                : -1L;
    }

    /** Visible Losses, or -1 when the canonical cost split is unavailable (combined fallback). */
    static long visibleLosses(Metrics metrics, MarketFold market) {
        return metrics.costSplitAvailable ? safeSubtract(metrics.otherCosts, market.other) : -1L;
    }

    /** Visible combined Costs: canonical costs minus the counted Market gross costs. */
    static long visibleCosts(Metrics metrics, MarketFold market) {
        return safeSubtract(metrics.costs, market.costs);
    }

    /** The last Live list and what it was built from; the key covers every input of the list. */
    static List<Object> recentKey = Collections.emptyList();

    static List<Recent> recentList = Collections.emptyList();

    /**
     * The Live list is rebuilt only when a booking (the engine revision), a Market row or the loot
     * display filter changed; otherwise every tick reuses it instead of re-projecting the Grind.
     */
    static synchronized List<Recent> recent(
            Engine engine,
            Session active,
            List<Transaction> transactions,
            List<Row> marketRows,
            Map<String, Row> marketByPresentation,
            LiveContext ctx) {
        List<Object> key =
                Arrays.asList(
                        engine,
                        engine.getRevision(),
                        active,
                        marketRows,
                        ctx.minimumDisplayedLootValue,
                        ctx.filter,
                        ctx.filter == null ? "" : ctx.filter.version());
        if (!key.equals(recentKey)) {
            recentList =
                    recentRows(
                            engine.summary()
                                    .view(
                                            engine.summary()
                                                    .scope(
                                                            active == null
                                                                    ? Collections.emptyList()
                                                                    : Collections.singletonList(
                                                                            active)),
                                            ctx::flowVisible),
                            marketByPresentation,
                            ctx.filter);
            recentKey = key;
        }
        return recentList;
    }

    static List<Recent> recentRows(
            Semantics.Result projection, Map<String, Row> marketRows, RecentFilter filter) {
        var rows = new ArrayList<Recent>();
        for (Group group : projection.groups) {
            Recent row =
                    toRecent(
                            group,
                            group.marketPresentationId.isEmpty()
                                    ? null
                                    : marketRows.get(group.marketPresentationId));
            // A canceled offer that returned everything stays in the Ledger, not Recent (owner
            // 2026-09-28).
            if (row == null || "canceled".equals(row.tag)) continue;
            // A charge or dose spend also answers to the separate charge list; the item list still
            // gates every row and a dose gain is never hidden with the spends (owner 1.3.4).
            if (!row.open
                    && filter != null
                    && !filter.showRecent(
                            row.name,
                            row.quantity,
                            group.table == Semantics.Table.COSTS_SUPPLIES && group.chargeRow())) {
                continue;
            }
            // A Ground Items-highlighted gain stands out in its highlight colour (owner 1.5.0, after
            // Better Loot Notifier).
            if (filter != null && row.value > 0L && !row.market)
                row.highlight = filter.highlight(row.name, row.quantity);
            rows.add(row);
            if (rows.size() == RECENT_ROWS) break;
        }
        return Collections.unmodifiableList(rows);
    }

    /** One semantic group as the Live row the page paints, with proven Market value semantics. */
    static Recent toRecent(Group group, Row marketRow) {
        if (group == null) return null;
        boolean market = group.market;
        boolean realizedMarket = market && group.coverage == Coverage.COMPLETE;
        long marketResult = group.value;
        boolean marketSettlementProven =
                market
                        && marketRow != null
                        && marketRow.isRealizedIncluded()
                        && marketRow.settledQty > 0L
                        && marketRow.observedSettlementGp != 0L;
        boolean marketSell = marketRow != null && "SELL".equals(marketRow.side.name());
        boolean marketCanceled =
                marketRow != null && marketRow.lifecycle == MarketRows.Lifecycle.CANCELLED_RETURNED;
        String marketSide =
                marketRow == null
                        ? ""
                        : marketRow.isRealizedIncluded()
                                ? marketSell ? "Sold" : "Bought"
                                : MarketText.stateWord(marketRow);
        boolean unpriced = !market && group.coverage == Coverage.INCOMPLETE;
        String tag =
                group.reviewRequired
                        ? "review"
                        : marketCanceled
                                ? "canceled"
                                : market ? "market" : unpriced ? "unpriced" : null;
        String quantity = quantityText(group);
        String qty;
        String actionLabel;
        if (market) {
            // Cash movement first: the primary value is the observed signed settlement; the
            // canonical Market result is the explicit secondary line, never the primary.
            String detail =
                    realizedMarket
                            ? (marketSide.isEmpty()
                                    ? "Result " + signed(marketResult)
                                    : marketSide + " \u00b7 Result " + signed(marketResult))
                            : marketSettlementProven
                                    ? marketSide + " \u00b7 Result \u2014"
                                    : group.contextLine;
            // A finished trade shows its quantity like any item ("\u00d7500"); the line keeps its
            // side
            // and Result (owner 2026-10-08).
            qty = marketSettlementProven ? quantityText(group) : null;
            actionLabel = detail;
        } else if (group.actionGroup()) {
            // An exact named spell is its own title, so the verb plus the receipt count is the
            // honest second line. A generic action keeps its composition, which is the only
            // context that distinguishes it.
            // Owner 2026-09-28: a count per cast like any item ("×23"), never "23 receipts", so the
            // narrow quantity column leaves every name readable.
            String casts = group.receiptCount > 1 ? times(group.receiptCount) : null;
            qty =
                    !group.primaryName.equalsIgnoreCase(group.actionLabel)
                            ? casts
                            : casts == null ? group.contextLine : group.contextLine + " · " + casts;
            actionLabel = group.actionLabel;
        } else if (!group.failedOf().isEmpty()) {
            qty = group.failedOf();
            actionLabel = group.failedTip();
        } else {
            qty = quantity;
            // A run whose every unit failed so far reads as failed, whichever receipt came first.
            actionLabel = group.failed > 0L ? msg("failed") : group.actionLabel;
        }
        CostView ledgerCostView =
                group.table == Semantics.Table.COSTS_SUPPLIES
                        ? group.chargeUse ? CostView.CHARGES : CostView.SUPPLIES
                        : group.table == Semantics.Table.COSTS_LOSS ? CostView.LOSS : null;
        boolean neutral = group.neutral;
        long value = neutral ? 0L : group.value;
        boolean settlementValue = false;
        if (marketSettlementProven && !neutral) {
            value =
                    marketSell
                            ? Math.abs(marketRow.observedSettlementGp)
                            : -Math.abs(marketRow.observedSettlementGp);
            settlementValue = true;
        }
        return new Recent(
                group.itemId,
                group.primaryName,
                qty,
                value,
                neutral,
                unpriced,
                tag,
                group.receiptCount,
                group.representativeTransactionId,
                group.representativeContributionId,
                actionLabel,
                SafeMath.nonNeg(group.quantity),
                group.composite,
                market,
                market ? group.coverage == Coverage.COMPLETE : !unpriced,
                ledgerCostView,
                group.reviewRequired,
                marketResult,
                settlementValue,
                marketSide,
                marketRow != null && LedgerData.isPending(marketRow.lifecycle),
                group.chargeRow(),
                null);
    }

    /**
     * "1 dose"/"\u00d73 doses" for any drink, "1 portion"/"\u00d72 portions" for pies, pizzas and
     * cakes, otherwise "\u00d7N" (a sapling planted from its pot is not a portion).
     */
    static String quantityText(Group group) {
        // Coins read as their value only (owner 2026-10-16).
        if (group.itemId == 995) return null;
        String name = group.primaryName.toLowerCase(Locale.ROOT);
        String unit =
                Phrases.has(name, "pizza", "cake", "pie")
                        ? "portion"
                        : group.actionLabel.equals("Drank") ? "dose" : null;
        if (group.normalizedConsume && unit != null) {
            if (group.quantity == 1L) return "1 " + unit;
            return group.quantity > 1L ? times(group.quantity) + " " + unit + "s" : null;
        }
        if (group.claim < 0) {
            // A claim spend reads as its own signed count ("-1 pouch"); a pickup stays "×N".
            return "\u2212" + Math.max(1L, group.quantity);
        }
        return group.quantity > 1L ? times(group.quantity) : null;
    }
}

/**
 * Plugin-side facts the Live read model cannot read from the engine: the current owner's idle-badge
 * state, the presentation marks noted for that owner, the loot visibility preferences, the cached
 * client-thread PvP sample and the activity context label.
 *
 * <p>Forward-port note: the historical context also carried the notable-drop threshold, the live
 * encounter summary and the sidebar net-graph toggle. Those producers no longer exist in schema
 * 102, so nothing is fabricated here; the fields that remain are the ones the current plugin can
 * state truthfully.
 */
class LiveContext {
    static final LiveContext NONE =
            new LiveContext(false, null, 0L, PvpState.NONE, "", false, "", false);
    boolean idle;

    /** Presentation-only item filter; null means every booked row stays visible. */
    RecentFilter filter;

    /** Hide gained rows whose unit price is below this value; 0 shows all. Never hides costs. */
    long minimumDisplayedLootValue;

    /** Cached client-thread PvP sample; presentation only. */
    PvpState pvp;

    /** Activity context label for the status line; empty means "use the owner name". */
    String activity;

    /** Freshest client-thread interaction target for the shared activity label; empty when none. */
    String target;

    /** The client is at the login screen; only read when no Grind is active. */
    boolean offline;

    /** A world hop is in flight: presentation says Reconnecting, never Logged out. */
    boolean hopping;

    LiveContext(
            boolean idle,
            RecentFilter filter,
            long minimumDisplayedLootValue,
            PvpState pvp,
            String activity,
            boolean offline,
            String target) {
        this(idle, filter, minimumDisplayedLootValue, pvp, activity, offline, target, false);
    }

    LiveContext(
            boolean idle,
            RecentFilter filter,
            long minimumDisplayedLootValue,
            PvpState pvp,
            String activity,
            boolean offline,
            String target,
            boolean hopping) {
        this.idle = idle;
        this.filter = filter;
        this.minimumDisplayedLootValue = SafeMath.nonNeg(minimumDisplayedLootValue);
        this.pvp = pvp == null ? PvpState.NONE : pvp;
        this.activity = Phrases.orEmpty(activity);
        this.offline = offline;
        this.target = Phrases.orEmpty(target);
        this.hopping = hopping;
    }

    /** Whether one flow stays on a presentation row under the current visibility settings. */
    boolean flowVisible(Flow flow) {
        if (flow == null) return false;
        // Costs, fees and deaths are never hidden by the display loot filter.
        if (flow.quantityDelta <= 0L) return true;
        // Always show beats Ground Items' hidden list and the minimum (owner 1.5.0, after Better
        // Loot Notifier); Hidden Recent items still wins on Recent.
        if (filter != null && filter.alwaysShows(flow)) return true;
        // Unknown / unpriced gains remain discoverable for Review; the minimum is
        // compared against the unit price so quantity cannot defeat it.
        if (flow.unitPrice > 0 && flow.unitPrice < minimumDisplayedLootValue) return false;
        return filter == null || filter.isFlowIncluded(flow);
    }
}

/**
 * Client-side PvP facts the plugin observes on its tick (Wilderness / PvP world, skull, Protect
 * Item, what a death would lose). Presentation only; nothing here touches accounting.
 */
@AllArgsConstructor
class PvpState {
    static final PvpState NONE = new PvpState(false, false, false, 0L);
    boolean pvpPossible;
    boolean skulled;
    boolean protectItem;

    /** GP a death here would lose; 0 where no death can be lost. */
    long risk;

    /**
     * Every held unit but the most valuable 3 (none when skulled), one more with Protect Item, at
     * today's prices; an unpriced unit counts as 0. A stack keeps units, not the whole stack.
     */
    static long risk(List<Flow> held, boolean skulled, boolean protectItem) {
        var stacks = new ArrayList<Flow>();
        for (Flow flow : held) {
            if (flow != null && flow.quantityDelta > 0L) stacks.add(flow);
        }
        stacks.sort((a, b) -> Long.compare(deathValue(b), deathValue(a)));
        long keep = (skulled ? 0 : 3) + (protectItem ? 1 : 0);
        long risk = 0L;
        for (Flow stack : stacks) {
            long kept = Math.min(keep, stack.quantityDelta);
            keep -= kept;
            risk =
                    SafeMath.safeAdd(
                            risk, Math.max(0L, deathValue(stack)) * (stack.quantityDelta - kept));
        }
        return risk;
    }

    /**
     * What a death weighs the item at. Since 2026-07-29 the game ranks every Avernic defender at
     * 600,000, its repair cost when it breaks unprotected (OSRS Wiki).
     */
    static long deathValue(Flow stack) {
        return GameData.pattern("avernic").matcher(Phrases.lower(stack.itemName)).matches()
                ? 600_000L
                : stack.unitPrice;
    }
}

/**
 * Presentation-only item eligibility for the Live Recent rows. The panel binds the current {@code
 * LootPresentationFilterService} decision behind this seam so the kit stays free of plugin
 * dependencies and the existing filter authority stays the only policy.
 */
@FunctionalInterface
interface RecentFilter {
    /**
     * @return true when presentation may show a row carrying this flow
     */
    boolean isFlowIncluded(Flow flow);

    /** An Always show item: shown whatever Ground Items or the minimum value say. */
    default boolean alwaysShows(Flow flow) {
        return false;
    }

    /** Ground Items' highlight colour for a highlighted item; null when it is not one. */
    default Color highlight(String name, long quantity) {
        return null;
    }

    /**
     * A charge spend row also answers to the separate charge list, so its family's spends can be
     * hidden while the item's other rows (a gain) still show.
     */
    default boolean showRecent(String name, long quantity, boolean charge) {
        return true;
    }

    default void hideRecent(String name, boolean charge) {}

    /** Changes whenever the decisions may change, so the Live list is rebuilt only then. */
    default String version() {
        return "";
    }
}

/**
 * Small presentation-only Ground Items preference adapter. It keeps the user's list choices
 * available to filtered projections, but does not retain a second price/rule evaluator or any
 * accounting state.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class LootPresentationFilterService {
    static final int NONE = 0;
    static final int HIGHLIGHTED = 1;
    static final int HIDDEN = 2;
    final GroundItemsConfigReader configReader;
    volatile GroundItemsPrefs snapshot;
    volatile String fingerprint = "";

    /** Test helper with a fixed snapshot (no live ConfigManager). */
    LootPresentationFilterService(GroundItemsPrefs snapshot) {
        configReader = null;
        replaceSnapshotForTests(snapshot);
    }

    synchronized void refresh() {
        if (configReader == null) return;
        replaceSnapshotForTests(configReader.read());
    }

    /** Applies only explicit list preferences; ambiguous value/price rules stay visible. */
    boolean isFlowIncluded(Flow flow, LootFilter mode) {
        LootFilter effective = mode == null ? LootFilter.ALL_ITEMS : mode;
        if (effective == LootFilter.ALL_ITEMS || flow == null) return true;
        ensureSnapshot();
        GroundItemsPrefs current = snapshot;
        if (!current.pluginEnabled) return true;
        int state =
                listState(
                        current.highlightedCsv,
                        current.hiddenCsv,
                        flow.itemName,
                        SafeMath.nonNeg(Math.abs(flow.quantityDelta)));
        if (state == HIDDEN) return false;
        return effective != LootFilter.HIGHLIGHTED_LIST_ONLY
                ? !current.showHighlightedOnly || state == HIGHLIGHTED
                : state == HIGHLIGHTED;
    }

    /** Ground Items' highlight colour for an item on its highlighted list, unless filters are off. */
    Color highlight(String name, long quantity, LootFilter mode) {
        ensureSnapshot();
        GroundItemsPrefs current = snapshot;
        return mode == LootFilter.ALL_ITEMS
                        || !current.pluginEnabled
                        || listState(current.highlightedCsv, "", name, quantity) != HIGHLIGHTED
                ? null
                : current.highlight;
    }

    /** HUD+'s big-drop line: Ground Items' insane value when the display follows it, else 10M. */
    long big(LootFilter mode) {
        ensureSnapshot();
        GroundItemsPrefs current = snapshot;
        return mode == LootFilter.FOLLOW_GROUND_ITEMS
                        && current.pluginEnabled
                        && current.insane > 0L
                ? current.insane
                : HudTray.BIG_DROP;
    }

    synchronized void replaceSnapshotForTests(GroundItemsPrefs value) {
        snapshot = value == null ? GroundItemsPrefs.disabled() : value;
        fingerprint = snapshot.fingerprint();
    }

    synchronized void ensureSnapshot() {
        if (snapshot == null) refresh();
        if (snapshot == null) replaceSnapshotForTests(null);
    }

    static int listState(String highlightedCsv, String hiddenCsv, String itemName, long quantity) {
        int exact = listMatch(highlightedCsv, itemName, quantity, false);
        if (exact != NONE) return HIGHLIGHTED;
        exact = listMatch(hiddenCsv, itemName, quantity, false);
        if (exact != NONE) return HIDDEN;
        int wildcard = listMatch(highlightedCsv, itemName, quantity, true);
        if (wildcard != NONE) return HIGHLIGHTED;
        return listMatch(hiddenCsv, itemName, quantity, true) == NONE ? NONE : HIDDEN;
    }

    /** The Recent charge pattern for a row: its dose family, wildcarded (Burning amulet*). */
    static String chargePattern(String itemName) {
        return ActionReader.doseBaseName(itemName) + "*";
    }

    static int listMatch(String csv, String itemName, long quantity, boolean wildcard) {
        String name = Phrases.orEmpty(itemName);
        for (Rule rule : rules(csv)) {
            if (rule.wildcard == wildcard && rule.matches(name, quantity)) return 1;
        }
        return NONE;
    }

    /** Parsed list rules by list text: the Ground Items lists are parsed once, not per item. */
    static final java.util.Map<String, java.util.List<Rule>> RULES =
            new java.util.concurrent.ConcurrentHashMap<>();

    static java.util.List<Rule> rules(String csv) {
        if (RULES.size() > 16) RULES.clear();
        return RULES.computeIfAbsent(
                Phrases.orEmpty(csv),
                text -> {
                    var parsed = new java.util.ArrayList<Rule>();
                    for (String raw : Text.fromCSV(text)) {
                        Rule rule = Rule.parse(raw);
                        if (rule != null) parsed.add(rule);
                    }
                    return parsed;
                });
    }

    @AllArgsConstructor
    static class Rule {
        String name;
        int quantity;
        boolean lessThan;
        boolean wildcard;

        static Rule parse(String value) {
            if (Phrases.blank(value)) return null;
            String text = value.trim();
            int split = -1;
            for (int i = text.length() - 1; i >= 0; i--) {
                char c = text.charAt(i);
                if (Character.isDigit(c) || Character.isWhitespace(c)) continue;
                if (c == '<' || c == '>') split = i;
                break;
            }
            int quantity = 0;
            boolean less = false;
            if (split >= 0 && split + 1 < text.length()) {
                try {
                    quantity = Integer.parseInt(text.substring(split + 1).trim());
                    less = text.charAt(split) == '<';
                    text = text.substring(0, split).trim();
                } catch (NumberFormatException ignored) {
                }
            }
            return new Rule(text, Math.max(0, quantity), less, text.contains("*"));
        }

        boolean matches(String itemName, long count) {
            if (wildcard
                    ? !WildcardMatcher.matches(name, itemName)
                    : !name.equalsIgnoreCase(itemName)) return false;
            return lessThan ? count < quantity : count > quantity;
        }
    }
}

/**
 * Reads only the active Ground Items list preferences through supported RuneLite APIs. Never
 * reflects into private Ground Items fields or package-private matchers.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class GroundItemsConfigReader {
    static final Logger log = LoggerFactory.getLogger(GroundItemsConfigReader.class);
    final ConfigManager configManager;
    final PluginManager pluginManager;

    GroundItemsPrefs read() {
        boolean enabled = isGroundItemsEnabled();
        GroundItemsConfig config;
        try {
            config = configManager.getConfig(GroundItemsConfig.class);
        } catch (RuntimeException ex) {
            log.debug(msg("ground-items"), ex);
            return GroundItemsPrefs.disabled();
        }
        if (config == null) return GroundItemsPrefs.disabled();
        var read =
                new GroundItemsPrefs(
                        enabled,
                        config.getHighlightItems(),
                        config.getHiddenItems(),
                        config.showHighlightedOnly());
        read.insane = config.insaneValuePrice();
        read.highlight = config.highlightedColor();
        read.fingerprint = read.buildFingerprint();
        return read;
    }

    boolean isGroundItemsEnabled() {
        if (pluginManager == null) return false;
        for (Plugin plugin : pluginManager.getPlugins()) {
            if (plugin instanceof GroundItemsPlugin) return pluginManager.isPluginEnabled(plugin);
        }
        return false;
    }
}

/**
 * Immutable snapshot of the Ground Items list preferences readable through the supported {@code
 * ConfigManager.getConfig(GroundItemsConfig.class)} API. It does not retain a second price/rule
 * evaluator or touch private plugin fields.
 *
 * <p>Verified against RuneLite Ground Items (config group {@code grounditems}, public {@code
 * GroundItemsConfig} surface).
 */
@EqualsAndHashCode(of = "fingerprint")
class GroundItemsPrefs {
    /** The Ground Items config group whose changes invalidate this snapshot. */
    static final String GROUP = "grounditems";

    boolean pluginEnabled;
    String highlightedCsv;
    String hiddenCsv;
    boolean showHighlightedOnly;

    /**
     * Ground Items' insane value: HUD+'s big-drop line while the loot display follows Ground Items.
     */
    long insane = HudTray.BIG_DROP;

    /** Ground Items' highlighted-item colour, for highlighted drops on the tray and Live. */
    Color highlight;

    String fingerprint;

    GroundItemsPrefs(
            boolean pluginEnabled,
            String highlightedCsv,
            String hiddenCsv,
            boolean showHighlightedOnly) {
        this.pluginEnabled = pluginEnabled;
        this.highlightedCsv = Phrases.orEmpty(highlightedCsv);
        this.hiddenCsv = Phrases.orEmpty(hiddenCsv);
        this.showHighlightedOnly = showHighlightedOnly;
        fingerprint = buildFingerprint();
    }

    /** Empty / disabled fallback — callers treat filtering as pass-through. */
    static GroundItemsPrefs disabled() {
        return new GroundItemsPrefs(false, "", msg("vial-ashes"), false);
    }

    String fingerprint() {
        return fingerprint;
    }

    String buildFingerprint() {
        return pluginEnabled
                + "|"
                + highlightedCsv
                + "|"
                + hiddenCsv
                + "|"
                + showHighlightedOnly
                + "|"
                + highlight;
    }
}
