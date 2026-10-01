package com.gpmanager;
import com.gpmanager.AccountingProjection.MarketFold;
import com.gpmanager.MarketSettlementProjection.Row;
import com.gpmanager.SemanticFinancialProjection.*;
import com.gpmanager.LedgerData.CostView;
import java.util.*;
import lombok.*;
import static java.lang.Math.*;
import static com.gpmanager.SafeMath.*;
import static com.gpmanager.Fmt.*;
/**
* Everything the Live page paints, captured in one read from the current schema-102 engine so
* the page never holds engine references while laying out. Pure data; built by {@link #capture}.
*
* <p>Forward-port note: this adapter reads current canonical queries only. Facts whose producer
* no longer exists (session goals, activity averages, party reports, live encounters, the retired
* notable-drop threshold) are absent rather than re-derived from unrelated numbers.</p>
*/
@AllArgsConstructor
class LiveSnapshot {
/** Stable session identity; names are editable and may repeat between runs. */
final String sessionId;
static final int RECENT_ROWS = 15;
/** One session goal as the Live card shows it; read-only, from the session's current target. */
@AllArgsConstructor
static class Goal {
 final long target;
 final long current;
 final boolean reached;
 /** "200k", or the Active Time target for a tracked-time goal. */
 final String label;
 /** "62%". */
 final String progress;
 /** The pace line while the rate is trustworthy; null when it is not. */
 final String eta;
 final double fraction;
 /** True when this card tracks Active Time rather than Net (owner 2026-10-01, F10). */
 final boolean time;
}

/** One exact item contribution or an honestly labelled composite activity row. */
@AllArgsConstructor
static class Recent {
 final int itemId;
 final String name;
 final String qty;
 final long value;
 final boolean neutral;
 /** True when the lead flow has no price: the row shows an unknown marker, never 0. */
 final boolean unpriced;
 final String tag;
 /** How many receipts this row coalesced; 1 for a single receipt. */
 final int receipts;
 /** The newest canonical receipt behind the row, for Ledger context. */
 final String receiptId;
 final String contributionId;
 final String actionLabel;
 final long quantity;
 final boolean composite;
 final boolean market;
 final boolean valueAvailable;
 /** The Costs view this row opens in the Ledger; null off the Costs table. */
 final CostView ledgerCostView;
 /** True when this row belongs to the Review audit rather than a financial table. */
 final boolean ledgerReview;
 /** Canonical realized Market result; the secondary "Result" line for Market rows. */
 final long marketResult;
 /** True when {@link #value} is the observed signed settlement amount (SELL / BUY). */
 final boolean marketSettlementValue;
 /** "Sold" / "Bought" once settled, else where the offer stands ("Offering", "Pending"); empty off-Market. */
 final String marketSide;
 /** A GE offer still on the exchange (trading, or filled and not collected): Live lists it under Offers. */
 final boolean open;
}

final boolean hasSession;
final String ownerLabel;
final long elapsedMillis;
final boolean paused;
final boolean idle;
final long net;
final long gains;
/** Non-consumable costs: deaths, tax, fees, drops. {@code -1} when the split is unavailable. */
final long loss;
/** Consumables: food, potions, runes, ammo, charges. {@code -1} when the split is unavailable. */
final long supplies;
/** Total booked cost; always the truthful figure, split or not. */
final long costs;
final boolean costSplitAvailable;
final boolean rateEstablished;
final long gpPerHour;
final Goal goal;
/** Realized Market result for the active scope, separated from ordinary Gains/Losses. */
final long marketResult;
/** Pending/unrealised Market context rows; informational only, never counted in Net. */
final int marketPending;
/** True when the current world/economy blocks ordinary automatic market pricing. */
/** Free play (the engine's durable owner) rather than a named session. */
final boolean freePlay;
/** Activity context label for the status line; empty when the owner name speaks for it. */
final String activityLabel;
/** Freshest client-thread interaction target; the shared activity label reads it. */
final String target;
/** Client-thread PvP sample, presentation only. */
final boolean pvpPossible;
final boolean skulled;
final boolean protectItem;
final int reviewCount;
final List<Recent> recent;
/** True for PK-mode sessions or any session that has booked a fight. */
final boolean pvpSession;
final int kills;
final int deaths;
final long killNet;
final long deathLoss;
final long bestKill;
/** Loot keys and chests not opened yet; a count, never a GP guess. */
final int pendingKeys;
/** Items waiting at a death reclaim; 0 when nothing is waiting. */
final long reclaimItems;
/** Kills since the last death this Grind; 0 after a death. */
final int streak;
/** Longest run of kills without a death this Grind. */
final int bestStreak;
/** Paused by logout: tracking waits for the next login, numbers stay as they were. */
final boolean loggedOut;
/** The Grind's active-time target, or 0 when none is set. */
final long timeTargetMillis;
/** Seconds until the pending factory-reset resume fires; 0 when none is armed. */
final long resumeInSeconds;
/** True after a resume while the tracker's derived displays settle; bookings are immediate. */
final boolean calibrating;
/** True while a world hop is in flight: the status word says Reconnecting, not Logged out. */
final boolean hopping;
/** @param context plugin-side idle state, owner marks and loot visibility (may be null) */    static LiveSnapshot capture(Engine engine, long now, LiveContext context) {
 LiveContext ctx = context == null ? LiveContext.NONE : context;
 SessionMetrics metrics = engine.getMetrics(now);
 Session active = engine.getActiveSession();
 boolean custom = engine.isCustomSessionActive();
 boolean freePlay = active == null || !custom;
 String owner = active == null ? "Free play" : active.getName();
 long elapsed = metrics.elapsedMillis;
 int review = 0;
 List<Transaction> transactions = active == null ? Collections.emptyList() : active.getTransactions();
 for (Transaction t : transactions) {
  if (t == null) continue;
  if (ReviewEligibility.needsOwnerDecision(t)) review++;
 }
 // One shared projection for the whole snapshot: Recent rows and the Market separation. The
 // Market rows are rebuilt on every read, so they are read once.
 List<Row> marketRows = engine.getMarketSettlements();
 // Hero categories are canonical and unfiltered: the whole counted Market-context
 // population is removed from ordinary Gains/Supplies/Losses and represented once by its
 // correction-aware net. Recent Activity display filters never enter this math.
 MarketFold marketFold = AccountingProjection.marketFold(transactions);
 long marketResult = marketFold.getNet();
 int marketPending = 0;
 var marketByPresentation = new HashMap<String, Row>();
 for (Row row : marketRows) {
  if (row != null) {
   marketByPresentation.put(row.presentationId, row);
   marketPending += LedgerData.isGenuinelyPending(row.lifecycle) ? 1 : 0;
  }
 }
 PkMetrics pk = active == null ? null : engine.getPkMetrics();
 boolean pvpSession = active != null
 && (active.getMode() == SessionMode.PK || (pk != null && pk.getEncounterCount() > 0));
 return new LiveSnapshot(active == null ? "" : active.getId(), active != null, owner, elapsed, metrics.paused, ctx.idle,
 metrics.net, visibleGains(metrics, marketFold), visibleLosses(metrics, marketFold),
 visibleSupplies(metrics, marketFold), visibleCosts(metrics, marketFold), metrics.costSplitAvailable,
 RateReadiness.isFullRateEstablished(elapsed), metrics.profitPerHour,
 goal(active, metrics, elapsed), marketResult, marketPending, freePlay, ctx.activity, ctx.target, ctx.pvp.pvpPossible,
 ctx.pvp.skulled, ctx.pvp.protectItem, review,
 recent(engine, active, transactions, marketRows, marketByPresentation, ctx), pvpSession, pk == null ? 0 : pk.kills,
 pk == null ? 0 : pk.deaths, pk == null ? 0L : pk.totalKillNet, pk == null ? 0L : pk.totalDeathLoss,
 pk == null ? 0L : pk.bestKill, engine.getPendingClaims().size(), engine.getDeathReclaimStatus().awaiting
 ? max(1L, engine.getDeathReclaimStatus().outstandingItemCount) : 0L, pk == null ? 0 : max(0, pk.currentStreak),
 active == null ? 0 : active.bestPkStreak(),
 active == null ? ctx.offline : active.paused && active.getPauseReason() == PauseReason.LIFECYCLE,
 active == null || active.getActiveTimeTargetMillis() == null ? 0L : active.getActiveTimeTargetMillis(),
 engine.autoResumeSeconds(now), engine.calibrating(now), ctx.hopping);
}

/**
* The read-only target card: the session's current {@code profitTargetGp} against its booked
* net. The historical goal editor, goal persistence and multi-kind goals are retired; no
* editor affordance and no ETA without a trustworthy rate.
*/
static Goal goal(Session active, SessionMetrics metrics, long elapsed) {
 if (active == null) return null;
 Long target = active.getProfitTargetGp();
 if (target != null && target > 0L) {
  long current = metrics.net;
  double fraction = max(0d, min(1d, current / (double) target));
  boolean reached = current >= target;
  String eta = null;
  if (!reached && elapsed >= 60_000L && metrics.profitPerHour > 0L) {
   long minutes = round((target - current) * 60d / metrics.profitPerHour);
   eta = minutes < 1L ? "<1m" : "~" + GrindsData.durationMinutes(minutes);
  }
  return new Goal(target, current, reached, compact(target),
  reached ? "reached" : percent(fraction), eta, fraction, false);
 }
 // Owner 2026-10-01 (F10): a time-only target is still a target; the card tracks Active Time.
 Long timeTarget = active.getActiveTimeTargetMillis();
 if (timeTarget == null || timeTarget <= 0L) return null;
 double timeFraction = max(0d, min(1d, elapsed / (double) timeTarget));
 boolean timeReached = elapsed >= timeTarget;
 // The shared Pace answer for a time target: where the booked Net lands at the target time.
 GrindsData.Pace pace = timeReached ? null
 : GrindsData.pace(null, timeTarget, metrics.net, true, metrics.profitPerHour, elapsed);
 return new Goal(timeTarget, elapsed, timeReached, durationCompact(timeTarget),
 timeReached ? "reached" : percent(timeFraction),
 pace == null || !pace.available ? null : pace.line, timeFraction, true);
}

/** Visible ordinary Gains: canonical unfiltered revenue minus the counted Market fold. */
static long visibleGains(SessionMetrics metrics, MarketFold market) {
 return safeSubtract(metrics.revenue, market.revenue);
}

/** Visible Supplies, or -1 when the canonical cost split is unavailable (combined fallback). */
static long visibleSupplies(SessionMetrics metrics, MarketFold market) {
 return metrics.costSplitAvailable ? safeSubtract(metrics.suppliesCosts, market.supplies) : -1L;
}

/** Visible Losses, or -1 when the canonical cost split is unavailable (combined fallback). */
static long visibleLosses(SessionMetrics metrics, MarketFold market) {
 return metrics.costSplitAvailable ? safeSubtract(metrics.otherCosts, market.other) : -1L;
}

/** Visible combined Costs: canonical costs minus the counted Market gross costs. */
static long visibleCosts(SessionMetrics metrics, MarketFold market) {
 return safeSubtract(metrics.costs, market.costs);
}

/** The last Live list and what it was built from; the key covers every input of the list. */
static List<Object> recentKey = Collections.emptyList();
static List<Recent> recentList = Collections.emptyList();
/**
* The Live list is rebuilt only when a booking (the engine revision), a Market row or the loot
* display filter changed; otherwise every tick reuses it instead of re-projecting the Grind.
*/
static synchronized List<Recent> recent(Engine engine, Session active,
List<Transaction> transactions, List<Row> marketRows, Map<String, Row> marketByPresentation, LiveContext ctx) {
 List<Object> key = Arrays.asList(engine, engine.getRevision(), active, marketRows,
 ctx.minimumDisplayedLootValue, ctx.filter, ctx.filter == null ? "" : ctx.filter.version());
 if (!key.equals(recentKey)) {
  recentList = recentRows(SemanticFinancialProjection.capture(transactions, marketRows,
  active == null ? "" : active.getId(), ctx::flowVisible), marketByPresentation, ctx.filter);
  recentKey = key;
 }
 return recentList;
}

static List<Recent> recentRows(SemanticFinancialProjection.Result projection,
java.util.Map<String, Row> marketRows, RecentFilter filter) {
 var rows = new ArrayList<Recent>();
 for (Group group : projection.groups) {
  // Charges live in the Ledger only.
  if (group.chargeUse) continue;
  Recent row = toRecent(group, group.marketPresentationId.isEmpty() ? null : marketRows.get(group.marketPresentationId));
  // A canceled offer that returned everything stays in the Ledger, not Recent (owner 2026-09-28).
  if (row == null || "canceled".equals(row.tag)
  || !row.open && filter != null && !filter.showRecent(row.name, row.quantity)) {
   continue;
  }
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
 boolean marketSettlementProven = market && marketRow != null
 && marketRow.isRealizedIncluded() && marketRow.settledQty > 0L && marketRow.observedSettlementGp != 0L;
 boolean marketSell = marketRow != null && "SELL".equals(marketRow.side.name());
 boolean marketCanceled = marketRow != null
 && marketRow.lifecycle == MarketSettlementProjection.Lifecycle.CANCELLED_RETURNED;
 String marketSide = marketRow == null ? ""
 : marketRow.isRealizedIncluded() ? marketSell ? "Sold" : "Bought" : MarketText.stateWord(marketRow);
 boolean unpriced = !market && group.coverage == Coverage.INCOMPLETE;
 String tag = group.reviewRequired ? "review" : marketCanceled ? "canceled"
 : market ? "market" : unpriced ? "unpriced" : null;
 String quantity = quantityText(group);
 String qty;
 String actionLabel;
 if (market) {
  // Cash movement first: the primary value is the observed signed settlement; the
  // canonical Market result is the explicit secondary line, never the primary.
  String qtyText = marketSettlementProven ? quantityText(group) : null;
  String detail = realizedMarket ? (marketSide.isEmpty() ? "Result " + signed(marketResult)
  : marketSide + " \u00b7 Result " + signed(marketResult)) : marketSettlementProven
  ? marketSide + " \u00b7 Result \u2014" : group.contextLine;
  qty = null;
  actionLabel = qtyText == null ? detail : qtyText + " \u00b7 " + detail;
 } else if (group.actionGroup()) {
  // An exact named spell is its own title, so the verb plus the receipt count is the
  // honest second line. A generic action keeps its composition, which is the only
  // context that distinguishes it.
  // Owner 2026-09-28: a count per cast like any item ("×23"), never "23 receipts", so the
  // narrow quantity column leaves every name readable.
  String casts = group.receiptCount > 1 ? times(group.receiptCount) : null;
  qty = !group.primaryName.equalsIgnoreCase(group.actionLabel) ? casts
  : casts == null ? group.contextLine : group.contextLine + " · " + casts;
  actionLabel = group.actionLabel;
 } else {
  qty = quantity;
  actionLabel = group.actionLabel;
 }
 CostView ledgerCostView = group.table == SemanticFinancialProjection.Table.COSTS_SUPPLIES
 ? group.chargeUse ? CostView.CHARGES : CostView.SUPPLIES : group.table == SemanticFinancialProjection.Table.COSTS_LOSS
 ? CostView.LOSS : null;
 boolean neutral = group.neutral;
 long value = neutral ? 0L : group.value;
 boolean settlementValue = false;
 if (marketSettlementProven && !neutral) {
  value = marketSell ? Math.abs(marketRow.observedSettlementGp) : -Math.abs(marketRow.observedSettlementGp);
  settlementValue = true;
 }
 return new Recent(group.itemId, group.primaryName, qty, value, neutral, unpriced, tag,
 group.receiptCount, group.representativeTransactionId, group.representativeContributionId,
 actionLabel, SafeMath.nonNeg(group.quantity), group.composite, market,
 market ? group.coverage == Coverage.COMPLETE : !unpriced, ledgerCostView, group.reviewRequired,
 marketResult, settlementValue, marketSide, marketRow != null && LedgerData.isGenuinelyPending(marketRow.lifecycle));
}

/**
* "1 dose"/"\u00d73 doses" for any drink, "1 portion"/"\u00d72 portions" for pies,
* pizzas and cakes, otherwise "\u00d7N" (a sapling planted from its pot is not a portion).
*/
static String quantityText(Group group) {
 String name = group.primaryName.toLowerCase(Locale.ROOT);
 String unit = ModelText.has(name, "pizza", "cake", "pie") ? "portion"
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
