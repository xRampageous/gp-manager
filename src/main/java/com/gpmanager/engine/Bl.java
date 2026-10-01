package com.gpmanager;
import lombok.Getter;
import java.util.*;
import java.util.function.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.*;
import static java.lang.Math.*;
import static com.gpmanager.Av.*;
@Singleton
class Bl implements FlowValuator {
/** Whether the current world/economy permits ordinary automatic RuneLite market quotes. */
enum Bk {
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
volatile Bk economyState;
@Inject
Bl(ItemManager itemManager, GpManagerConfig config) {
 // Accounting explicitly requests GP Manager's chosen RuneLite active market route (the
 // boolean is always true at the call site). It must not delegate to getItemPrice(),
 // whose implementation follows the user's global useWikiItemPrices display preference
 // and would make identical observations book different amounts. RuneLite may itself
 // fall back to guide/Jagex pricing; the selected quote is captured and frozen at
 // booking time, and history is never repriced. The economy gate starts UNKNOWN until
 // the plugin observes a logged-in world, so automatic quotes fail closed meanwhile.
 this(config, itemManager::getItemComposition,
 (id, active) -> unitPriceOf(itemManager.getItemPriceWithSource(id, active)),
 id -> vb(itemManager, id), Bk.UNKNOWN);
}

/**
* Unit-price boundary for a RuneLite quote. RuneLite 1.13.1 widened the quote to long for
* beyond-max-cash trades; the unit-price model is int, so a value that does not fit (or is
* not positive) stays unpriced for Review instead of booking a clamped, fabricated amount.
*/
static int unitPriceOf(long quote) {
 return quote <= 0L || quote > Integer.MAX_VALUE ? 0 : Ae.toInt(quote);
}

/** Test seam: deterministic NORMAL economy and no mapping guard; the boolean stays explicit. */
Bl(GpManagerConfig config, IntFunction<ItemComposition> compositions, MarketQuote marketQuotes) {
 this(config, compositions, marketQuotes, id -> false, Bk.NORMAL);
}

Bl(GpManagerConfig config, IntFunction<ItemComposition> compositions,
MarketQuote marketQuotes, IntPredicate unsupportedMapping, Bk economyState) {
 this.config = config;
 this.compositions = compositions;
 this.marketQuotes = marketQuotes;
 this.unsupportedMapping = unsupportedMapping == null ? id -> false : unsupportedMapping;
 this.economyState = economyState == null ? Bk.UNKNOWN : economyState;
}

/** Legacy synthetic constructor retained for existing tests: NORMAL economy, no guard. */
Bl(GpManagerConfig config, IntFunction<ItemComposition> compositions, IntUnaryOperator marketPrices) {
 this(config, compositions, (id, active) -> marketPrices.applyAsInt(id));
}

@Override
public List<Ab> value(Map<Integer, Long> quantityDeltas) {
 return value(quantityDeltas, 0L);
}

@Override
public List<Ab> value(Map<Integer, Long> quantityDeltas, long priceCapturedAtEpochMillis) {
 var flows = new ArrayList<Ab>();
 Map<Integer, Integer> priceOverrides = ItemRuleParser.parsePriceOverrides(config.manualPriceOverrides());
 for (Map.Entry<Integer, Long> entry : quantityDeltas.entrySet()) {
  Ab flow = axg(entry.getKey(), entry.getValue(), priceOverrides, priceCapturedAtEpochMillis);
  if (flow != null) flows.add(flow);
 }
 flows.sort(Comparator.comparingLong((Ab flow) -> Ae.abs(flow.valueDelta)).reversed());
 return flows;
}

/**
* Revalues only a confirmed Drink action. Ordinary valuation remains a quote of each
* observed item variant; this hook is deliberately separate so a decant, transfer or
* production shape cannot be mistaken for consumption before action settlement.
*/
@Override
public List<Ab> normalizeConsumedFlows(List<Ab> flows, Au actionKind, long priceCapturedAtEpochMillis) {
 if (flows == null || flows.isEmpty() || actionKind != Au.DRINK || Bn.isDecant(flows)) {
  return flows;
 }
 Map<Integer, Integer> priceOverrides = ItemRuleParser.parsePriceOverrides(config.manualPriceOverrides());
 List<Bn.Cg> pairs = Bn.acr(flows);
 if (pairs.isEmpty()) return flows;
 // Each exact pair is an atomic accounting unit. Compute both replacement flows before
 // assigning either one; an unsupported basis returns null and leaves the caller's
 // original captured list untouched for Review.
 var normalized = new ArrayList<>(flows);
 for (Bn.Cg pair : pairs) {
  if (!abj(normalized, pair.lossIndex, pair.remainderIndex, priceOverrides, priceCapturedAtEpochMillis)) {
   return null;
  }
 }
 return normalized;
}

Ab rf(Ab flow, Map<Integer, Integer> priceOverrides, long now) {
 if (flow == null) return null;
 String one = (Bn.qz(flow.itemName) + "(1)").trim().toLowerCase(Locale.ROOT);
 Ab geCandidate = null;
 try {
  for (int id : ItemVariationMapping.getVariations(ItemVariationMapping.map(flow.itemId))) {
   Ab unit = axg(id, 1L, priceOverrides, now);
   String aqz = unit == null ? "" : unit.itemName;
   if (aqz == null || !one.equals(aqz.trim().toLowerCase(Locale.ROOT))) continue;
   if (unit.getPriceSource() == MANUAL_OVERRIDE) return unit;
   if (unit.getPriceSource() == GRAND_EXCHANGE && geCandidate == null) geCandidate = unit;
  }
 } catch (RuntimeException ignored) {
  return null;
 }
 return geCandidate;
}

private boolean abj(List<Ab> normalized, int lossIndex, int remainderIndex,
Map<Integer, Integer> priceOverrides, long now) {
 Ab loss = normalized.get(lossIndex);
 Ab remainder = normalized.get(remainderIndex);
 int lostDose = Bn.ajr(loss.itemName);
 int gainedDose = Bn.ajr(remainder.itemName);
 if (lostDose <= 0 || gainedDose <= 0 || lostDose != gainedDose + 1
 || !Bn.qz(loss.itemName).equals(Bn.qz(remainder.itemName))) {
  return false;
 }
 Ab authority = rf(loss, priceOverrides, now);
 if (!pm(loss, authority) || !pm(remainder, authority)) return false;
 try {
  long apa = multiplyExact(lostDose, (long) authority.unitPrice);
  long apy = multiplyExact(gainedDose, (long) authority.unitPrice);
  Ab asu = new Ab(loss.itemId, loss.itemName, loss.quantityDelta, toIntExact(apa),
  multiplyExact(loss.quantityDelta, apa), authority.getPriceSource(), authority.getPriceCapturedAtEpochMillis());
  Ab asv = new Ab(remainder.itemId, remainder.itemName,
  remainder.quantityDelta, toIntExact(apy),
  multiplyExact(remainder.quantityDelta, apy), authority.getPriceSource(),
  authority.getPriceCapturedAtEpochMillis());
  normalized.set(lossIndex, asu);
  normalized.set(remainderIndex, asv);
  return true;
 } catch (ArithmeticException ex) {
  return false;
 }
}

private static boolean pm(Ab flow, Ab authority) {
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
Ab akj(int itemId, long quantityDelta, long now) {
 return axg(itemId, quantityDelta, ItemRuleParser.parsePriceOverrides(config.manualPriceOverrides()), now);
}

@Override
public boolean automaticMarketQuotesAvailable() {
 return economyState == Bk.NORMAL;
}

/**
* Client-thread world/economy refresh. UNKNOWN and special economies fail automatic market
* quotes closed; manual override and face-value policy still apply.
*/
void setEconomyState(Bk state) {
 this.economyState = state == null ? Bk.UNKNOWN : state;
}

/**
* Every main-game world (members, PvP, high risk, LMS, PvP Arena, skill total...) shares the
* Grand Exchange and keeps automatic quotes. Only the separate economies (Deadman, Leagues,
* speedrunning, beta, tournament and no-save worlds) and an absent world
* observation fail closed.
*/
static Bk rh(Collection<WorldType> worldTypes) {
 if (worldTypes == null) return Bk.UNKNOWN;
 for (WorldType type : worldTypes) {
  if (type == null) return Bk.UNKNOWN;
  switch (type) {
   case DEADMAN:
   case SEASONAL:
   case QUEST_SPEEDRUNNING:
   case BETA_WORLD:
   case TOURNAMENT_WORLD:
   case NOSAVE_MODE:
   return Bk.UNSUPPORTED_SPECIAL;
   default:
   break;
  }
 }
 return Bk.NORMAL;
}

Ab axg(int itemId, long quantityDelta, Map<Integer, Integer> priceOverrides, long priceCapturedAtEpochMillis) {
 boolean alp = DeferredClaims.of(itemId);
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
  boolean apc = priceOverrides.containsKey(itemId);
  int alv = sm(itemId);
  // For ordinary items, a user override is authoritative even before RuneLite prices
  // load; fixed currencies below retain their face value by policy.
  int amf = 0;
  // RuneLite's ItemManager price helper is intentionally convenient: when an item has
  // no direct quote it can recurse through ItemMapping and price an untradeable variant
  // from another item (for example unidentified minerals or golden nuggets via coal).
  // Those catalogue entries are non-GP currencies in GP Manager, so their physical
  // identity must not inherit a monetary identity from the platform mapping. An
  // explicit owner override remains authoritative.
  if (!apc && !alp && alv == 0 && !CurrencyProxyCatalogue.awk(itemId)
  && economyState == Bk.NORMAL && !unsupportedMapping.test(itemId)) {
   try {
    // GP Manager's single automatic market policy: the explicit active route,
    // gated by the current world/economy and the mapping guard above.
    amf = max(0, marketQuotes.priceOf(itemId, true));
   } catch (RuntimeException ignored) {
    // No quote: the flow stays unpriced for Review.
   }
  }
  // A proxy row values the component from a market item and its fixed exchange rate.
  int[] proxy = amf == 0 ? CurrencyProxyCatalogue.axy(itemId) : null;
  if (proxy != null) {
   try {
    amf = max(0, marketQuotes.priceOf(proxy[0], true) / proxy[1]);
   } catch (RuntimeException ignored) {
    // No proxy quote: the flow stays unpriced for Review.
   }
  }
  int unitPrice;
  Av priceSource;
  if (alp) {
   // A non-tradeable key is an unopened claim, not held wealth. Its
   // contents become ordinary valued inventory gains only on receipt.
   unitPrice = 0;
   priceSource = DEFERRED_CLAIM;
  } else if (alv > 0) {
   unitPrice = alv;
   priceSource = FACE_VALUE;
  } else if (apc) {
   unitPrice = priceOverrides.get(itemId);
   priceSource = MANUAL_OVERRIDE;
  } else if (amf > 0) {
   // The item's own market quote is a supported observation. Non-GP currencies
   // and untradeables without a quote stay unpriced; an exchange relationship or
   // a hypothetical high-alchemy liquidation is never converted into booked GP.
   unitPrice = amf;
   priceSource = proxy == null ? GRAND_EXCHANGE : CURRENCY_PROXY;
  } else {
   unitPrice = 0;
   priceSource = UNPRICED;
  }
  long valueDelta = agz(quantityDelta, unitPrice);
  // Only a non-zero selected price is a captured price. Unpriced/deferred flows
  // deliberately retain zero so consumers cannot present a fabricated capture time.
  long capturedAt = unitPrice > 0 && priceSource != Av.UNKNOWN && priceSource != FACE_VALUE
  ? Ae.nonNeg(priceCapturedAtEpochMillis) : 0L;
  return new Ab(itemId, name, quantityDelta, unitPrice, valueDelta, priceSource, capturedAt);
 } catch (RuntimeException ignored) {
  return new Ab(itemId, "Item " + itemId, quantityDelta, 0, 0L, alp ? DEFERRED_CLAIM : UNPRICED);
 }
}

static int sm(int itemId) {
 if (itemId == ItemID.COINS) return 1;
 if (itemId == ItemID.PLATINUM) return 1_000;
 return 0;
}

/**
* ItemManager's convenience quote recursively applies ItemMapping. Only a quantity-one,
* non-coin mapping can represent a state/base identity; quantity/rate mappings and coin
* targets would silently manufacture monetary identity for a physically distinct item.
*/
static boolean vb(ItemManager itemManager, int itemId) {
 try {
  int canonicalId = itemManager.canonicalize(itemId);
  return vb(ItemMapping.map(canonicalId));
 } catch (RuntimeException ignored) {
  return true;
 }
}

static boolean vb(Collection<ItemMapping> mappings) {
 if (mappings == null) return false;
 for (ItemMapping mapping : mappings) {
  if (mapping.getQuantity() != 1L || mapping.getTradeableItem() == ItemID.COINS) return true;
 }
 return false;
}

static long agz(long quantity, int price) {
 try {
  return multiplyExact(quantity, (long) price);
 } catch (ArithmeticException ex) {
  return quantity >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
 }
}
}
