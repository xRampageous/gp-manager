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
 int priceOf(int /* itemId */b, boolean /* active */c);
}

IntFunction<ItemComposition> compositions;
MarketQuote marketQuotes;
IntPredicate unsupportedMapping;
GpManagerConfig config;
@Getter
volatile Bk economyState;
@Inject
Bl(ItemManager /* itemManager */b, GpManagerConfig /* config */c) {
 // Accounting explicitly requests GP Manager's chosen RuneLite active market route (the
 // boolean is always true at the call site). It must not delegate to getItemPrice(),
 // whose implementation follows the user's global useWikiItemPrices display preference
 // and would make identical observations book different amounts. RuneLite may itself
 // fall back to guide/Jagex pricing; the selected quote is captured and frozen at
 // booking time, and history is never repriced. The economy gate starts UNKNOWN until
 // the plugin observes a logged-in world, so automatic quotes fail closed meanwhile.
 this(c, b::getItemComposition,
 (/* id */d, /* active */e) -> unitPriceOf(b.getItemPriceWithSource(d, e)),
 /* id */f -> vb(b, f), Bk.UNKNOWN);
}

/**
* Unit-price boundary for a RuneLite quote. RuneLite 1.13.1 widened the quote to long for
* beyond-max-cash trades; the unit-price model is int, so a value that does not fit (or is
* not positive) stays unpriced for Review instead of booking a clamped, fabricated amount.
*/
static int unitPriceOf(long /* quote */a) {
 return a <= 0L || a > Integer.MAX_VALUE ? 0 : Ae.toInt(a);
}

/** Test seam: deterministic NORMAL economy and no mapping guard; the boolean stays explicit. */
Bl(GpManagerConfig /* config */a, IntFunction<ItemComposition> /* compositions */b, MarketQuote /* marketQuotes */c) {
 this(a, b, c, /* id */d -> false, Bk.NORMAL);
}

Bl(GpManagerConfig /* config */a, IntFunction<ItemComposition> /* compositions */b,
MarketQuote /* marketQuotes */c, IntPredicate /* unsupportedMapping */d, Bk /* economyState */e) {
 this.config = a;
 this.compositions = b;
 this.marketQuotes = c;
 this.unsupportedMapping = d == null ? /* id */f -> false : d;
 this.economyState = e == null ? Bk.UNKNOWN : e;
}

/** Legacy synthetic constructor retained for existing tests: NORMAL economy, no guard. */
Bl(GpManagerConfig /* config */a, IntFunction<ItemComposition> /* compositions */b, IntUnaryOperator /* marketPrices */c) {
 this(a, b, (/* id */d, /* active */e) -> c.applyAsInt(d));
}

@Override
public List<Ab> value(Map<Integer, Long> /* quantityDeltas */a) {
 return value(a, 0L);
}

@Override
public List<Ab> value(Map<Integer, Long> /* quantityDeltas */a, long /* priceCapturedAtEpochMillis */b) {
 var /* flows */c = new ArrayList<Ab>();
 Map<Integer, Integer> /* priceOverrides */d = ov();
 for (Map.Entry<Integer, Long> /* entry */e : a.entrySet()) {
  Ab /* flow */f = axg(e.getKey(), e.getValue(), d, b);
  if (f != null) c.add(f);
 }
 c.sort(Comparator.comparingLong((Ab /* flow */g) -> Ae.abs(g.valueDelta)).reversed());
 return c;
}

/**
* Revalues only a confirmed Drink action. Ordinary valuation remains a quote of each
* observed item variant; this hook is deliberately separate so a decant, transfer or
* production shape cannot be mistaken for consumption before action settlement.
*/
@Override
public List<Ab> normalizeConsumedFlows(List<Ab> /* flows */a, Au /* actionKind */b, long /* priceCapturedAtEpochMillis */c) {
 if (a == null || a.isEmpty() || b != Au.DRINK || Bn.isDecant(a)) {
  return a;
 }
 Map<Integer, Integer> /* priceOverrides */d = ov();
 List<Bn.Cg> /* pairs */e = Bn.acr(a);
 if (e.isEmpty()) return a;
 // Each exact pair is an atomic accounting unit. Compute both replacement flows before
 // assigning either one; an unsupported basis returns null and leaves the caller's
 // original captured list untouched for Review.
 var /* normalized */f = new ArrayList<>(a);
 for (Bn.Cg /* pair */g : e) {
  if (!abj(f, g.lossIndex, g.remainderIndex, d, c)) {
   return null;
  }
 }
 return f;
}

Ab rf(Ab /* flow */a, Map<Integer, Integer> /* priceOverrides */b, long /* now */c) {
 if (a == null) return null;
 String /* one */d = (Bn.qz(a.itemName) + "(1)").trim().toLowerCase(Locale.ROOT);
 Ab /* geCandidate */e = null;
 try {
  for (int /* id */f : ItemVariationMapping.getVariations(ItemVariationMapping.map(a.itemId))) {
   Ab /* unit */g = axg(f, 1L, b, c);
   String /* aqz */h = g == null ? "" : g.itemName;
   if (h == null || !d.equals(Ag.lower(h))) continue;
   if (g.getPriceSource() == MANUAL_OVERRIDE) return g;
   if (g.getPriceSource() == GRAND_EXCHANGE && e == null) e = g;
  }
 } catch (RuntimeException /* ignored */i) {
  return null;
 }
 return e;
}

private boolean abj(List<Ab> /* normalized */a, int /* lossIndex */b, int /* remainderIndex */c,
Map<Integer, Integer> /* priceOverrides */d, long /* now */e) {
 Ab /* loss */f = a.get(b);
 Ab /* remainder */g = a.get(c);
 int /* lostDose */h = Bn.ajr(f.itemName);
 int /* gainedDose */i = Bn.ajr(g.itemName);
 if (h <= 0 || i <= 0 || h != i + 1
 || !Bn.qz(f.itemName).equals(Bn.qz(g.itemName))) {
  return false;
 }
 Ab /* authority */j = rf(f, d, e);
 if (!pm(f, j) || !pm(g, j)) return false;
 try {
  long /* apa */k = multiplyExact(h, (long) j.unitPrice);
  long /* apy */l = multiplyExact(i, (long) j.unitPrice);
  Ab /* asu */m = new Ab(f.itemId, f.itemName, f.quantityDelta, toIntExact(k),
  multiplyExact(f.quantityDelta, k), j.getPriceSource(), j.getPriceCapturedAtEpochMillis());
  Ab /* asv */n = new Ab(g.itemId, g.itemName,
  g.quantityDelta, toIntExact(l),
  multiplyExact(g.quantityDelta, l), j.getPriceSource(),
  j.getPriceCapturedAtEpochMillis());
  a.set(b, m);
  a.set(c, n);
  return true;
 } catch (ArithmeticException /* ex */o) {
  return false;
 }
}

private static boolean pm(Ab /* flow */a, Ab /* authority */b) {
 if (a == null || b == null) return false;
 if (b.getPriceSource() == GRAND_EXCHANGE) {
  return a.getPriceSource() == GRAND_EXCHANGE && a.unitPrice > 0;
 }
 return b.getPriceSource() == MANUAL_OVERRIDE && (a.getPriceSource() == GRAND_EXCHANGE && a.unitPrice > 0
 || a.getPriceSource() == MANUAL_OVERRIDE);
}

/**
* One measured charge component valued through the same policy as ordinary observations:
* deferred claim, face value, explicit manual override, the mapping guard, the world/economy
* gate, the explicit active RuneLite market route and UNPRICED otherwise.
*/
Ab akj(int /* itemId */a, long /* quantityDelta */b, long /* now */c) {
 return axg(a, b, ov(), c);
}

@Override
public boolean automaticMarketQuotesAvailable() {
 return economyState == Bk.NORMAL;
}

/**
* Client-thread world/economy refresh. UNKNOWN and special economies fail automatic market
* quotes closed; manual override and face-value policy still apply.
*/
void setEconomyState(Bk /* state */a) {
 this.economyState = a == null ? Bk.UNKNOWN : a;
}

/**
* Every main-game world (members, PvP, high risk, LMS, PvP Arena, skill total...) shares the
* Grand Exchange and keeps automatic quotes. Only the separate economies (Deadman, Leagues,
* speedrunning, beta, tournament and no-save worlds) and an absent world
* observation fail closed.
*/
static Bk rh(Collection<WorldType> /* worldTypes */a) {
 if (a == null) return Bk.UNKNOWN;
 for (WorldType /* type */b : a) {
  if (b == null) return Bk.UNKNOWN;
  switch (b) {
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

/**
* Owner 2026-10-09 (1.3.0): an ironman who chose high alch values items at their alchemy price;
* ironmen cannot sell on the Grand Exchange. The plugin sets it each tick from the account type.
*/
volatile boolean alch;

Ab axg(int /* itemId */b, long /* quantityDelta */c, Map<Integer, Integer> /* priceOverrides */d, long /* priceCapturedAtEpochMillis */e) {
 boolean /* alp */f = DeferredClaims.of(b);
 try {
  ItemComposition /* composition */g = null;
  try {
   g = compositions.apply(b);
  } catch (RuntimeException /* ignored */h) {
   // No definition yet: the flow keeps a placeholder name.
  }
  String /* name */i = g == null || g.getName() == null ? "Item " + b : g.getName();
  // A clue scroll, scroll box or reward casket is worth nothing until its casket opens (owner 2026-10-08).
  f |= Ak.pattern("clue").matcher(i).matches();
  // An empty vial is a physical neutral; filled variants remain ordinary items.
  if (b == ItemID.VIAL_EMPTY) return null;
  boolean /* apc */j = d.containsKey(b);
  int /* alv */k = sm(b);
  // For ordinary items, a user override is authoritative even before RuneLite prices
  // load; fixed currencies below retain their face value by policy.
  int /* amf */l = 0;
  // RuneLite's ItemManager price helper is intentionally convenient: when an item has
  // no direct quote it can recurse through ItemMapping and price an untradeable variant
  // from another item (for example unidentified minerals or golden nuggets via coal).
  // Those catalogue entries are non-GP currencies in GP Manager, so their physical
  // identity must not inherit a monetary identity from the platform mapping. An
  // explicit owner override remains authoritative.
  if (!j && !f && k == 0 && !CurrencyProxyCatalogue.awk(b)
  && economyState == Bk.NORMAL && !unsupportedMapping.test(b)) {
   try {
    // GP Manager's single automatic market policy: the explicit active route,
    // gated by the current world/economy and the mapping guard above.
    l = max(0, marketQuotes.priceOf(b, true));
   } catch (RuntimeException /* ignored */m) {
    // No quote: the flow stays unpriced for Review.
   }
  }
  // A proxy row values the component from a market item and its fixed exchange rate.
  // The proxy's market item is priced only where market quotes are, never on unknown or
 // special worlds (Codex review 1.2).
 int[] /* proxy */n = l == 0 && economyState == Bk.NORMAL ? CurrencyProxyCatalogue.axy(b) : null;
  if (n != null) {
   try {
    l = max(0, marketQuotes.priceOf(n[0], true) / n[1]);
   } catch (RuntimeException /* ignored */o) {
    // No proxy quote: the flow stays unpriced for Review.
   }
  }
  int /* unitPrice */p;
  Av /* priceSource */q;
  if (f) {
   // A non-tradeable key is an unopened claim, not held wealth. Its
   // contents become ordinary valued inventory gains only on receipt.
   p = 0;
   q = DEFERRED_CLAIM;
  } else if (k > 0) {
   p = k;
   q = FACE_VALUE;
  } else if (j) {
   p = d.get(b);
   q = MANUAL_OVERRIDE;
  } else if (alch && g != null && g.getHaPrice() > 0) {
   p = g.getHaPrice();
   q = HIGH_ALCHEMY;
  } else if (l > 0) {
   // The item's own market quote is a supported observation. Non-GP currencies
   // and untradeables without a quote stay unpriced; an exchange relationship or
   // a hypothetical high-alchemy liquidation is never converted into booked GP.
   p = l;
   q = n == null ? GRAND_EXCHANGE : CURRENCY_PROXY;
  } else {
   p = 0;
   q = UNPRICED;
  }
  long /* valueDelta */r = agz(c, p);
  // Only a non-zero selected price is a captured price. Unpriced/deferred flows
  // deliberately retain zero so consumers cannot present a fabricated capture time.
  long /* capturedAt */t = p > 0 && q != Av.UNKNOWN && q != FACE_VALUE
  ? Ae.nonNeg(e) : 0L;
  return new Ab(b, i, c, p, r, q, t);
 } catch (RuntimeException /* ignored */u) {
  return new Ab(b, "Item " + b, c, 0, 0L, f ? DEFERRED_CLAIM : UNPRICED);
 }
}

static int sm(int /* itemId */a) {
 if (a == ItemID.COINS) return 1;
 if (a == ItemID.PLATINUM) return 1_000;
 return 0;
}

/**
* ItemManager's convenience quote recursively applies ItemMapping. Only a quantity-one,
* non-coin mapping can represent a state/base identity; quantity/rate mappings and coin
* targets would silently manufacture monetary identity for a physically distinct item.
*/
static boolean vb(ItemManager /* itemManager */a, int /* itemId */b) {
 try {
  int /* canonicalId */c = a.canonicalize(b);
  return vb(ItemMapping.map(c));
 } catch (RuntimeException /* ignored */d) {
  return true;
 }
}

static boolean vb(Collection<ItemMapping> /* mappings */a) {
 if (a == null) return false;
 for (ItemMapping /* mapping */b : a) {
  if (b.getQuantity() != 1L || b.getTradeableItem() == ItemID.COINS) return true;
 }
 return false;
}

static long agz(long /* quantity */a, int /* price */b) {
 try {
  return multiplyExact(a, (long) b);
 } catch (ArithmeticException /* ex */c) {
  return a >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
 }
}

/** The owner's manual price overrides by item id. */
Map<Integer, Integer> ov() {
 return ItemRuleParser.parsePriceOverrides(config.manualPriceOverrides());
}
}
