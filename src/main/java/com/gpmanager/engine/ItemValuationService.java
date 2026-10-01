package com.gpmanager;
import lombok.Getter;
import java.util.*;
import java.util.function.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.*;
import static java.lang.Math.*;
import static com.gpmanager.PriceSource.*;
@Singleton
class ItemValuationService implements FlowValuator {
/** Whether the current world/economy permits ordinary automatic RuneLite market quotes. */
enum EconomyState {
NORMAL, UNSUPPORTED_SPECIAL, UNKNOWN
}

/**
* Explicit RuneLite quote route. The boolean is GP Manager's own policy choice, never the
* user's global RuneLite display-price preference, so identical observations book the same
* amount. Production binds this to {@code ItemManager::getItemPriceWithSource}.
*/
@FunctionalInterface
interface MarketQuote {
int priceOf(int itemId, boolean active);
}

final IntFunction<ItemComposition> compositions;
final MarketQuote marketQuotes;
final IntPredicate unsupportedMapping;
final GpManagerConfig config;
@Getter
volatile EconomyState economyState;
@Inject
ItemValuationService(ItemManager itemManager, GpManagerConfig config) {
// Accounting explicitly requests GP Manager's chosen RuneLite active market route (the
// boolean is always true at the call site). It must not delegate to getItemPrice(),
// whose implementation follows the user's global useWikiItemPrices display preference
// and would make identical observations book different amounts. RuneLite may itself
// fall back to guide/Jagex pricing; the selected quote is captured and frozen at
// booking time, and history is never repriced. The economy gate starts UNKNOWN until
// the plugin observes a logged-in world, so automatic quotes fail closed meanwhile.
this(config, itemManager::getItemComposition,
(id, active) -> unitPriceOf(itemManager.getItemPriceWithSource(id, active)),
id -> hasUnsupportedRuneLiteMapping(itemManager, id), EconomyState.UNKNOWN);
}

/**
* Unit-price boundary for a RuneLite quote. RuneLite 1.13.1 widened the quote to long for
* beyond-max-cash trades; the unit-price model is int, so a value that does not fit (or is
* not positive) stays unpriced for Review instead of booking a clamped, fabricated amount.
*/
static int unitPriceOf(long quote) {
return quote <= 0L || quote > Integer.MAX_VALUE ? 0 : SafeMath.toInt(quote);
}

/** Test seam: deterministic NORMAL economy and no mapping guard; the boolean stays explicit. */
ItemValuationService(GpManagerConfig config, IntFunction<ItemComposition> compositions, MarketQuote marketQuotes) {
this(config, compositions, marketQuotes, id -> false, EconomyState.NORMAL);
}

ItemValuationService(GpManagerConfig config, IntFunction<ItemComposition> compositions,
MarketQuote marketQuotes, IntPredicate unsupportedMapping, EconomyState economyState) {
this.config = config;
this.compositions = compositions;
this.marketQuotes = marketQuotes;
this.unsupportedMapping = unsupportedMapping == null ? id -> false : unsupportedMapping;
this.economyState = economyState == null ? EconomyState.UNKNOWN : economyState;
}

/** Legacy synthetic constructor retained for existing tests: NORMAL economy, no guard. */
ItemValuationService(GpManagerConfig config, IntFunction<ItemComposition> compositions, IntUnaryOperator marketPrices) {
this(config, compositions, (id, active) -> marketPrices.applyAsInt(id));
}

@Override
public List<Flow> value(Map<Integer, Long> quantityDeltas) {
return value(quantityDeltas, 0L);
}

@Override
public List<Flow> value(Map<Integer, Long> quantityDeltas, long priceCapturedAtEpochMillis) {
var flows = new ArrayList<Flow>();
Map<Integer, Integer> priceOverrides = ItemRuleParser.parsePriceOverrides(config.manualPriceOverrides());
for (Map.Entry<Integer, Long> entry : quantityDeltas.entrySet()) {
Flow flow = valueOne(entry.getKey(), entry.getValue(), priceOverrides, priceCapturedAtEpochMillis);
if (flow != null) flows.add(flow);
}
flows.sort(Comparator.comparingLong((Flow flow) -> SafeMath.abs(flow.valueDelta)).reversed());
return flows;
}

/**
* Revalues only a confirmed Drink action. Ordinary valuation remains a quote of each
* observed item variant; this hook is deliberately separate so a decant, transfer or
* production shape cannot be mistaken for consumption before action settlement.
*/
@Override
public List<Flow> normalizeConsumedFlows(List<Flow> flows, ActionKind actionKind, long priceCapturedAtEpochMillis) {
if (flows == null || flows.isEmpty() || actionKind != ActionKind.DRINK || ActionEvidence.isDecant(flows)) {
return flows;
}
Map<Integer, Integer> priceOverrides = ItemRuleParser.parsePriceOverrides(config.manualPriceOverrides());
List<ActionEvidence.PartialConsumePair> pairs = ActionEvidence.partialConsumePairs(flows);
if (pairs.isEmpty()) return flows;
// Each exact pair is an atomic accounting unit. Compute both replacement flows before
// assigning either one; an unsupported basis returns null and leaves the caller's
// original captured list untouched for Review.
var normalized = new ArrayList<>(flows);
for (ActionEvidence.PartialConsumePair pair : pairs) {
if (!normalizePotionPair(normalized, pair.lossIndex, pair.remainderIndex, priceOverrides, priceCapturedAtEpochMillis)) {
return null;
}
}
return normalized;
}

Flow doseAuthority(Flow flow, Map<Integer, Integer> priceOverrides, long now) {
if (flow == null) return null;
String one = (ActionEvidence.doseBaseName(flow.itemName) + "(1)").trim().toLowerCase(Locale.ROOT);
Flow geCandidate = null;
try {
for (int id : ItemVariationMapping.getVariations(ItemVariationMapping.map(flow.itemId))) {
Flow unit = valueOne(id, 1L, priceOverrides, now);
String unitName = unit == null ? "" : unit.itemName;
if (unitName == null || !one.equals(unitName.trim().toLowerCase(Locale.ROOT))) continue;
if (unit.getPriceSource() == MANUAL_OVERRIDE) return unit;
if (unit.getPriceSource() == GRAND_EXCHANGE && geCandidate == null) geCandidate = unit;
}
} catch (RuntimeException ignored) {
return null;
}
return geCandidate;
}

private boolean normalizePotionPair(List<Flow> normalized, int lossIndex, int remainderIndex,
Map<Integer, Integer> priceOverrides, long now) {
Flow loss = normalized.get(lossIndex);
Flow remainder = normalized.get(remainderIndex);
int lostDose = ActionEvidence.trailingDose(loss.itemName);
int gainedDose = ActionEvidence.trailingDose(remainder.itemName);
if (lostDose <= 0 || gainedDose <= 0 || lostDose != gainedDose + 1
|| !ActionEvidence.doseBaseName(loss.itemName).equals(ActionEvidence.doseBaseName(remainder.itemName))) {
return false;
}
Flow authority = doseAuthority(loss, priceOverrides, now);
if (!compatibleWithAuthority(loss, authority) || !compatibleWithAuthority(remainder, authority)) return false;
try {
long lossUnit = multiplyExact(lostDose, (long) authority.unitPrice);
long remainderUnit = multiplyExact(gainedDose, (long) authority.unitPrice);
Flow normalizedLoss = new Flow(loss.itemId, loss.itemName, loss.quantityDelta, toIntExact(lossUnit),
multiplyExact(loss.quantityDelta, lossUnit), authority.getPriceSource(), authority.getPriceCapturedAtEpochMillis());
Flow normalizedRemainder = new Flow(remainder.itemId, remainder.itemName,
remainder.quantityDelta, toIntExact(remainderUnit),
multiplyExact(remainder.quantityDelta, remainderUnit), authority.getPriceSource(),
authority.getPriceCapturedAtEpochMillis());
normalized.set(lossIndex, normalizedLoss);
normalized.set(remainderIndex, normalizedRemainder);
return true;
} catch (ArithmeticException ex) {
return false;
}
}

private static boolean compatibleWithAuthority(Flow flow, Flow authority) {
if (flow == null || authority == null) return false;
if (authority.getPriceSource() == GRAND_EXCHANGE) {
return flow.getPriceSource() == GRAND_EXCHANGE && flow.unitPrice > 0;
}
return authority.getPriceSource() == MANUAL_OVERRIDE && (flow.getPriceSource() == GRAND_EXCHANGE && flow.unitPrice > 0
|| flow.getPriceSource() == MANUAL_OVERRIDE);
}

/**
* One measured charge component valued through the same policy as ordinary observations:
* deferred claim, face value, explicit manual override, the mapping guard, the world/economy
* gate, the explicit active RuneLite market route and UNPRICED otherwise.
*/
Flow valueChargeComponent(int itemId, long quantityDelta, long now) {
return valueOne(itemId, quantityDelta, ItemRuleParser.parsePriceOverrides(config.manualPriceOverrides()), now);
}

@Override
public boolean automaticMarketQuotesAvailable() {
return economyState == EconomyState.NORMAL;
}

/**
* Client-thread world/economy refresh. UNKNOWN and special economies fail automatic market
* quotes closed; manual override and face-value policy still apply.
*/
void setEconomyState(EconomyState state) {
this.economyState = state == null ? EconomyState.UNKNOWN : state;
}

/**
* Every main-game world (members, PvP, high risk, LMS, PvP Arena, skill total...) shares the
* Grand Exchange and keeps automatic quotes. Only the separate economies (Deadman, Leagues,
* speedrunning, beta, tournament and no-save worlds) and an absent world
* observation fail closed.
*/
static EconomyState economyStateFor(Collection<WorldType> worldTypes) {
if (worldTypes == null) return EconomyState.UNKNOWN;
for (WorldType type : worldTypes) {
if (type == null) return EconomyState.UNKNOWN;
switch (type) {
case DEADMAN:
case SEASONAL:
case QUEST_SPEEDRUNNING:
case BETA_WORLD:
case TOURNAMENT_WORLD:
case NOSAVE_MODE:
return EconomyState.UNSUPPORTED_SPECIAL;
default:
break;
}
}
return EconomyState.NORMAL;
}

Flow valueOne(int itemId, long quantityDelta, Map<Integer, Integer> priceOverrides, long priceCapturedAtEpochMillis) {
boolean deferredClaim = DeferredClaims.of(itemId);
try {
ItemComposition composition = null;
try {
composition = compositions.apply(itemId);
} catch (RuntimeException ignored) {
// No definition yet: the flow keeps a placeholder name.
}
String name = composition == null || composition.getName() == null ? "Item " + itemId : composition.getName();
// An empty vial is a physical neutral; filled variants remain ordinary items.
if (itemId == ItemID.VIAL_EMPTY) return null;
boolean manualOverride = priceOverrides.containsKey(itemId);
int faceValue = faceValueOf(itemId);
// For ordinary items, a user override is authoritative even before RuneLite prices
// load; fixed currencies below retain their face value by policy.
int marketPrice = 0;
// RuneLite's ItemManager price helper is intentionally convenient: when an item has
// no direct quote it can recurse through ItemMapping and price an untradeable variant
// from another item (for example unidentified minerals or golden nuggets via coal).
// Those catalogue entries are non-GP currencies in GP Manager, so their physical
// identity must not inherit a monetary identity from the platform mapping. An
// explicit owner override remains authoritative.
if (!manualOverride && !deferredClaim && faceValue == 0 && !CurrencyProxyCatalogue.isMapped(itemId)
&& economyState == EconomyState.NORMAL && !unsupportedMapping.test(itemId)) {
try {
// GP Manager's single automatic market policy: the explicit active route,
// gated by the current world/economy and the mapping guard above.
marketPrice = max(0, marketQuotes.priceOf(itemId, true));
} catch (RuntimeException ignored) {
// No quote: the flow stays unpriced for Review.
}
}
// A proxy row values the component from a market item and its fixed exchange rate.
int[] proxy = marketPrice == 0 ? CurrencyProxyCatalogue.proxyOf(itemId) : null;
if (proxy != null) {
try {
marketPrice = max(0, marketQuotes.priceOf(proxy[0], true) / proxy[1]);
} catch (RuntimeException ignored) {
// No proxy quote: the flow stays unpriced for Review.
}
}
int unitPrice;
PriceSource priceSource;
if (deferredClaim) {
// A non-tradeable key is an unopened claim, not held wealth. Its
// contents become ordinary valued inventory gains only on receipt.
unitPrice = 0;
priceSource = DEFERRED_CLAIM;
} else if (faceValue > 0) {
unitPrice = faceValue;
priceSource = FACE_VALUE;
} else if (manualOverride) {
unitPrice = priceOverrides.get(itemId);
priceSource = MANUAL_OVERRIDE;
} else if (marketPrice > 0) {
// The item's own market quote is a supported observation. Non-GP currencies
// and untradeables without a quote stay unpriced; an exchange relationship or
// a hypothetical high-alchemy liquidation is never converted into booked GP.
unitPrice = marketPrice;
priceSource = proxy == null ? GRAND_EXCHANGE : CURRENCY_PROXY;
} else {
unitPrice = 0;
priceSource = UNPRICED;
}
long valueDelta = safeMultiply(quantityDelta, unitPrice);
// Only a non-zero selected price is a captured price. Unpriced/deferred flows
// deliberately retain zero so consumers cannot present a fabricated capture time.
long capturedAt = unitPrice > 0 && priceSource != PriceSource.UNKNOWN && priceSource != FACE_VALUE
? SafeMath.nonNeg(priceCapturedAtEpochMillis) : 0L;
return new Flow(itemId, name, quantityDelta, unitPrice, valueDelta, priceSource, capturedAt);
} catch (RuntimeException ignored) {
return new Flow(itemId, "Item " + itemId, quantityDelta, 0, 0L, deferredClaim ? DEFERRED_CLAIM : UNPRICED);
}
}

static int faceValueOf(int itemId) {
if (itemId == ItemID.COINS) return 1;
if (itemId == ItemID.PLATINUM) return 1_000;
return 0;
}

/**
* ItemManager's convenience quote recursively applies ItemMapping. Only a quantity-one,
* non-coin mapping can represent a state/base identity; quantity/rate mappings and coin
* targets would silently manufacture monetary identity for a physically distinct item.
*/
static boolean hasUnsupportedRuneLiteMapping(ItemManager itemManager, int itemId) {
try {
int canonicalId = itemManager.canonicalize(itemId);
return hasUnsupportedRuneLiteMapping(ItemMapping.map(canonicalId));
} catch (RuntimeException ignored) {
return true;
}
}

static boolean hasUnsupportedRuneLiteMapping(Collection<ItemMapping> mappings) {
if (mappings == null) return false;
for (ItemMapping mapping : mappings) {
if (mapping.getQuantity() != 1L || mapping.getTradeableItem() == ItemID.COINS) return true;
}
return false;
}

static long safeMultiply(long quantity, int price) {
try {
return multiplyExact(quantity, (long) price);
} catch (ArithmeticException ex) {
return quantity >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
}
}
}
