package com.gpmanager;
import com.gpmanager.Bi.Row;
import com.gpmanager.Br.*;
import java.util.*;
import static com.gpmanager.Aa.Side.*;
import static com.gpmanager.Fmt.*;
import static com.gpmanager.Ak.msg;
/**
* The Ledger's Market wording: one source for rows, receipts and the short GE facts, so the page
* and the read-model tests never drift apart.
*/
class Dc {
/** True when a Market group has proven settled quantity and an observed settlement amount. */
static boolean zv(Group group,
Row market) {
return group.market && market != null && market.isRealizedIncluded()
&& market.settledQty > 0L && market.observedSettlementGp != 0L;
}
/** The Market row's primary signed value: observed settlement, else the canonical result. */
static long zw(Ao data, Group group) {
Row market = data.zu(group.marketPresentationId);
if (!zv(group, market)) {
return group.value;
}
boolean sell = "SELL".equals(market.side.name());
return sell ? Math.abs(market.observedSettlementGp)
: -Math.abs(market.observedSettlementGp);
}
/** The Market row's secondary line: explicit side + Result, or the lifecycle context. */
static String aag(Ao data, Group group) {
if (!group.market) {
return auo(group);
}
Row market = data.zu(group.marketPresentationId);
if (zv(group, market)) {
return ("SELL".equals(market.side.name()) ? "Sold" : "Bought")
+ " \u00b7 Result " + (group.coverage == Br.Coverage.COMPLETE
? signed(group.value) : "\u2014");
}
if (group.coverage != Br.Coverage.COMPLETE) {
return group.qe;
}
return "Result " + signed(group.value);
}
static boolean zq(Receipt receipt) {
Row market = receipt.marketSettlement;
return market != null && market.isRealizedIncluded() && market.settledQty > 0L;
}
/** Signed observed settlement cash for the collected quantity (SELL positive, BUY negative). */
static long zx(Receipt receipt) {
Row market = receipt.marketSettlement;
if (!zq(receipt)) {
return 0L;
}
return market.side == SELL
? Math.abs(market.observedSettlementGp)
: -Math.abs(market.observedSettlementGp);
}
static String aab(Receipt receipt) {
Row market = receipt.marketSettlement;
if (zq(receipt) && market.settledQty > 1L) {
return receipt.itemName + "  " + times(market.settledQty);
}
return receipt.itemName;
}
/** Sold/Bought with the canonical Result as secondary information and a settlement-relative age. */
static String aaa(Receipt receipt, long capturedAt) {
Row market = receipt.marketSettlement;
String sub;
if (zq(receipt)) {
boolean atl = market.realizedResultCorrectionAware
&& (market.side != SELL
|| market.coverage != Bi.Coverage.FULLY_UNKNOWN
|| market.manualFinancialResult);
String result = atl
? "Result " + ru(market.realizedResultGp) + " gp"
: "Result \u2014";
sub = (market.side == SELL ? "Sold" : "Bought") + " \u00b7 " + result;
} else {
sub = market == null ? receipt.verb : acp(market);
}
return sub + " \u00b7 " + age(Ae.nonNeg(capturedAt - receipt.at));
}
/**
* One word for where an offer stands: Offering / Buying while it is up with nothing filled,
* Pending while part-filled, Sold / Bought once the exchange completes it, Canceled when returned.
*/
static String avj(Row market) {
if (market == null) {
return "Pending";
}
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
static List<String> zs(Row market, boolean corrected) {
var lines = new ArrayList<String>();
String aof = ta(market);
if (!aof.isEmpty()) {
lines.add(aof);
}
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
lines.add((sell ? "Received" : "Spent") + "|"
+ exact(Math.abs(market.observedSettlementGp)) + " gp");
boolean unknown = sell
&& market.coverage == Bi.Coverage.FULLY_UNKNOWN;
if (sell) {
if (market.receivedEachGp > 0L) {
// The actual after-tax settled unit value; shown only when exactly divisible.
lines.add("Received each|" + exact(market.receivedEachGp) + " gp");
}
lines.add("Previously counted|" + (unknown ? "Unknown"
: exact(market.trackedBasisConsumedGp) + " gp"));
lines.add("Result|" + (unknown && !market.manualFinancialResult
? "\u2014" : market.realizedResultCorrectionAware
? ru(market.realizedResultGp) + " gp"
+ (market.coverage == Bi.Coverage.PARTIALLY_KNOWN
? " \u00b7 Partial" : "")
+ (corrected ? " \u00b7 Corrected" : "")
: "\u2014"));
if (market.knownCostOnly) {
// Only the BOOKED state with an AUTO correction earns this row: the tax is a real
// market cost here. Uncounted/pre-change, corrected and fully known sales never
// show it (a full Result already has the tax inside Received).
lines.add("GE tax|" + ru(-market.inferredGeTaxGp) + " gp");
}
} else {
lines.add("Tracked value|" + exact(market.trackedBasisConsumedGp) + " gp");
lines.add("Result|" + (market.realizedResultCorrectionAware
? ru(market.realizedResultGp) + " gp"
+ (corrected ? " \u00b7 Corrected" : "")
: "0 gp"));
}
if (market.settlementAdjustmentGp != 0L && market.inferredGeTaxGp <= 0L) {
// An unexplained gap is the receipt's own answer and must stay visible.
lines.add("Adjustment|" + ru(market.settlementAdjustmentGp) + " gp");
}
if (market.geDifferenceAvailable) {
// After-tax comparable: SELL compares net received cash with the tax-adjusted
// reference, never with a gross pre-tax number. Execution/reference only.
if (!sell && market.basisUnitPrice > 0L) {
lines.add("GE reference|" + exact(market.basisUnitPrice) + " gp ea");
}
lines.add("GE difference|" + ru(market.geDifferenceGp) + " gp");
}
return lines;
}
/** Compact fill/collected state; omitted when offered, filled and collected agree. */
static String ta(Row market) {
long offered = market.offeredQty;
long filled = market.filledQty;
long collected = market.settledQty;
if (offered <= 0L || (filled == collected && collected == offered)) {
return "";
}
if (filled == collected) {
return "Filled " + exact(collected) + " / " + exact(offered);
}
return "Filled " + exact(filled) + " / " + exact(offered)
+ " \u00b7 collected " + exact(collected);
}
/** Order-state wording that never mislabels placement time as settlement time. */
static String acp(Row market) {
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
return (sell ? "Sold " : "Bought ") + exact(market.filledQty) + "/"
+ exact(market.offeredQty) + msg("jj");
case PARTIALLY_EXECUTED:
return (sell ? "Part sold " : "Part bought ") + exact(market.filledQty)
+ "/" + exact(market.offeredQty) + " \u00b7 Pending";
default:
return avj(market);
}
}
static String awu(Row market) {
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
static String auo(Group group) {
if (group.market) {
return group.qe;
}
if (!group.usedBy.isEmpty()) {
return "Used by " + group.usedBy;
}
return group.actionLabel;
}
}
