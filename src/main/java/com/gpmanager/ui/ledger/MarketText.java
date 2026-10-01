package com.gpmanager;
import com.gpmanager.MarketSettlementProjection.Row;
import com.gpmanager.SemanticFinancialProjection.*;
import java.util.*;
import static com.gpmanager.GeRecord.Side.*;
import static com.gpmanager.Fmt.*;
import static com.gpmanager.GameData.msg;
/**
* The Ledger's Market wording: one source for rows, receipts and the short GE facts, so the page
* and the read-model tests never drift apart.
*/
class MarketText {
/** True when a Market group has proven settled quantity and an observed settlement amount. */
static boolean marketSettled(Group group, Row market) {
return group.market && market != null && market.isRealizedIncluded()
&& market.settledQty > 0L && market.observedSettlementGp != 0L;
}

/** The Market row's primary signed value: observed settlement, else the canonical result. */
static long marketPrimary(LedgerData data, Group group) {
Row market = data.marketRowFor(group.marketPresentationId);
if (!marketSettled(group, market)) return group.value;
boolean sell = "SELL".equals(market.side.name());
return sell ? Math.abs(market.observedSettlementGp) : -Math.abs(market.observedSettlementGp);
}

/** The Market row's secondary line: explicit side + Result, or the lifecycle context. */
static String marketLead(LedgerData data, Group group) {
if (!group.market) return groupLead(group);
Row market = data.marketRowFor(group.marketPresentationId);
if (marketSettled(group, market)) {
return ("SELL".equals(market.side.name()) ? "Sold" : "Bought")
+ " \u00b7 Result " + (group.coverage == SemanticFinancialProjection.Coverage.COMPLETE
? signed(group.value) : "\u2014");
}
if (group.coverage != SemanticFinancialProjection.Coverage.COMPLETE) return group.contextLine;
return "Result " + signed(group.value);
}

static boolean marketReceiptRealized(Receipt receipt) {
Row market = receipt.marketSettlement;
return market != null && market.isRealizedIncluded() && market.settledQty > 0L;
}

/** Signed observed settlement cash for the collected quantity (SELL positive, BUY negative). */
static long marketReceiptPrimary(Receipt receipt) {
Row market = receipt.marketSettlement;
if (!marketReceiptRealized(receipt)) return 0L;
return market.side == SELL ? Math.abs(market.observedSettlementGp) : -Math.abs(market.observedSettlementGp);
}

static String marketReceiptTitle(Receipt receipt) {
Row market = receipt.marketSettlement;
if (marketReceiptRealized(receipt) && market.settledQty > 1L) {
return receipt.itemName + "  " + times(market.settledQty);
}
return receipt.itemName;
}

/** Sold/Bought with the canonical Result as secondary information and a settlement-relative age. */
static String marketReceiptSub(Receipt receipt, long capturedAt) {
Row market = receipt.marketSettlement;
String sub;
if (marketReceiptRealized(receipt)) {
boolean resultAvailable = market.realizedResultCorrectionAware && (market.side != SELL
|| market.coverage != MarketSettlementProjection.Coverage.FULLY_UNKNOWN || market.manualFinancialResult);
String result = resultAvailable ? "Result " + exactSigned(market.realizedResultGp) + " gp" : "Result \u2014";
sub = (market.side == SELL ? "Sold" : "Bought") + " \u00b7 " + result;
} else {
sub = market == null ? receipt.verb : orderStateText(market);
}
return sub + " \u00b7 " + age(SafeMath.nonNeg(capturedAt - receipt.at));
}

/**
* One word for where an offer stands: Offering / Buying while it is up with nothing filled,
* Pending while part-filled, Sold / Bought once the exchange completes it, Canceled when returned.
*/
static String stateWord(Row market) {
if (market == null) return "Pending";
boolean sell = market.side == SELL;
switch (market.lifecycle) {
case CANCELLED_RETURNED: return "Canceled";
case AMBIGUOUS: return "Review";
case EXECUTED_UNSETTLED:
case REALIZED: return sell ? "Sold" : "Bought";
case PENDING:
case RESUMED: return sell ? "Offering" : "Buying";
default: return "Pending";
}
}

/**
* The exact human Market lines ("label|value") for one lifecycle row; the renderer and the
* read-model tests consume this one source. Coverage-aware: a sale whose prior tracked value is
* unknown shows liquidation cash with an unavailable Result, never a fake zero basis. Only a
* booked, uncorrected PROVEN tax earns a main GE tax row; exempt, unproven and pre-change sales
* and fully known results never show one.
*/
static List<String> marketHumanLines(Row market, boolean corrected) {
var lines = new ArrayList<String>();
String fillState = fillStateText(market);
if (!fillState.isEmpty()) lines.add(fillState);
if (!market.listedDuring.isEmpty()) {
// Cross-Grind: booked where it was collected; the listing session is named, never rewritten.
lines.add("Listed during|" + market.listedDuring);
}
boolean sell = market.side == SELL;
if (!market.isRealizedIncluded() || market.settledQty <= 0L) {
if (sell && market.collectionAmbiguous && market.settledQty > 0L) {
// D1: a quarantined settlement's cash was never observed; Received is never shown
// as if the player had actually collected it.
lines.add("Received|not proven");
}
return lines;
}
lines.add((sell ? "Received" : "Spent") + "|" + exact(Math.abs(market.observedSettlementGp)) + " gp");
boolean unknown = sell && market.coverage == MarketSettlementProjection.Coverage.FULLY_UNKNOWN;
if (sell) {
if (market.receivedEachGp > 0L) {
// The actual after-tax settled unit value; shown only when exactly divisible.
lines.add("Received each|" + exact(market.receivedEachGp) + " gp");
}
lines.add("Previously counted|" + (unknown ? "Unknown" : exact(market.trackedBasisConsumedGp) + " gp"));
lines.add("Result|" + (unknown && !market.manualFinancialResult ? "\u2014" : market.realizedResultCorrectionAware
? exactSigned(market.realizedResultGp) + " gp" + (market.coverage == MarketSettlementProjection.Coverage.PARTIALLY_KNOWN
? " \u00b7 Partial" : "") + (corrected ? " \u00b7 Corrected" : "") : "\u2014"));
if (market.knownCostOnly) {
// Only the BOOKED state with an AUTO correction earns this row: the tax is a real
// market cost here. Uncounted/pre-change, corrected and fully known sales never
// show it (a full Result already has the tax inside Received).
lines.add("GE tax|" + exactSigned(-market.inferredGeTaxGp) + " gp");
}
} else {
lines.add("Tracked value|" + exact(market.trackedBasisConsumedGp) + " gp");
lines.add("Result|" + (market.realizedResultCorrectionAware ? exactSigned(market.realizedResultGp) + " gp"
+ (corrected ? " \u00b7 Corrected" : "") : "0 gp"));
}
if (market.settlementAdjustmentGp != 0L && market.inferredGeTaxGp <= 0L) {
// An unexplained gap is the receipt's own answer and must stay visible.
lines.add("Adjustment|" + exactSigned(market.settlementAdjustmentGp) + " gp");
}
if (market.geDifferenceAvailable) {
// After-tax comparable: SELL compares net received cash with the tax-adjusted
// reference, never with a gross pre-tax number. Execution/reference only.
if (!sell && market.basisUnitPrice > 0L) lines.add("GE reference|" + exact(market.basisUnitPrice) + " gp ea");
lines.add("GE difference|" + exactSigned(market.geDifferenceGp) + " gp");
}
return lines;
}

/** Compact fill/collected state; omitted when offered, filled and collected agree. */
static String fillStateText(Row market) {
long offered = market.offeredQty;
long filled = market.filledQty;
long collected = market.settledQty;
if (offered <= 0L || (filled == collected && collected == offered)) return "";
if (filled == collected) return "Filled " + exact(collected) + " / " + exact(offered);
return "Filled " + exact(filled) + " / " + exact(offered) + " \u00b7 collected " + exact(collected);
}

/** Order-state wording that never mislabels placement time as settlement time. */
static String orderStateText(Row market) {
boolean sell = market.side == SELL;
String side = sell ? "Sell order" : "Buy order";
switch (market.lifecycle) {
case CANCELLED_RETURNED:
return msg("ji");
case AMBIGUOUS:
return "Needs review";
case UNAVAILABLE:
return "Unavailable";
case CLOSED_UNOBSERVED:
return "Unobserved";
case EXECUTED_UNSETTLED:
return (sell ? "Sold " : "Bought ") + exact(market.filledQty) + "/" + exact(market.offeredQty) + msg("jj");
case PARTIALLY_EXECUTED:
return (sell ? "Part sold " : "Part bought ") + exact(market.filledQty)
+ "/" + exact(market.offeredQty) + " \u00b7 Pending";
default:
return stateWord(market);
}
}

static String orderWhy(Row market) {
switch (market.lifecycle) {
case CANCELLED_RETURNED:
return msg("jk");
case AMBIGUOUS:
return msg("ge");
case EXECUTED_UNSETTLED:
return msg("gf");
default:
return msg("j");
}
}

static String groupLead(Group group) {
if (group.market) return group.contextLine;
if (!group.usedBy.isEmpty()) return "Used by " + group.usedBy;
return group.actionLabel;
}
}
