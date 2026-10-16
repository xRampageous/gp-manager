package com.gpmanager;

import static com.gpmanager.PriceSource.*;
import static java.lang.Math.*;

import java.util.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.*;
import javax.inject.*;
import lombok.Getter;
import net.runelite.api.*;
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.*;
import net.runelite.client.game.ItemManager;
import net.runelite.http.api.item.ItemPrice;

@Singleton
class Valuation implements FlowValuator {
    /** Whether the current world/economy permits ordinary automatic RuneLite market quotes. */
    enum Economy {
        NORMAL,
        UNSUPPORTED_SPECIAL,
        UNKNOWN
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

    static final Map<String, String> PARTS = GameData.byName("partial_food");

    IntFunction<ItemComposition> compositions;
    MarketQuote marketQuotes;
    IntPredicate unsupportedMapping;
    GpManagerConfig config;
    @Getter volatile Economy economyState;

    /** Karamja gloves 1-4 worn: sampled by the plugin, read when a proxy rate is chosen. */
    volatile boolean karamjaGlovesWorn;

    /** A market quote floored at zero; an unavailable quote reads 0 (the flow stays unpriced). */
    int quoteOrZero(int itemId) {
        try {
            return max(0, marketQuotes.priceOf(itemId, true));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    @Inject
    Valuation(ItemManager itemManager, GpManagerConfig config) {
        // Accounting explicitly requests GP Manager's chosen RuneLite active market route (the
        // boolean is always true at the call site). It must not delegate to getItemPrice(),
        // whose implementation follows the user's global useWikiItemPrices display preference
        // and would make identical observations book different amounts. RuneLite may itself
        // fall back to guide/Jagex pricing; the selected quote is captured and frozen at
        // booking time, and history is never repriced. The economy gate starts UNKNOWN until
        // the plugin observes a logged-in world, so automatic quotes fail closed meanwhile.
        this(
                config,
                itemManager::getItemComposition,
                (id, active) -> unitPriceOf(itemManager.getItemPriceWithSource(id, active)),
                id -> hasUnsupportedMapping(itemManager, id),
                Economy.UNKNOWN);
    }

    /**
     * Unit-price boundary for a RuneLite quote. RuneLite 1.13.1 widened the quote to long for
     * beyond-max-cash trades; the unit-price model is int, so a value that does not fit (or is not
     * positive) stays unpriced for Review instead of booking a clamped, fabricated amount.
     */
    static int unitPriceOf(long quote) {
        return quote <= 0L || quote > Integer.MAX_VALUE ? 0 : SafeMath.toInt(quote);
    }

    /** Test seam: deterministic NORMAL economy and no mapping guard; the boolean stays explicit. */
    Valuation(
            GpManagerConfig config,
            IntFunction<ItemComposition> compositions,
            MarketQuote marketQuotes) {
        this(config, compositions, marketQuotes, id -> false, Economy.NORMAL);
    }

    Valuation(
            GpManagerConfig config,
            IntFunction<ItemComposition> compositions,
            MarketQuote marketQuotes,
            IntPredicate unsupportedMapping,
            Economy economyState) {
        this.config = config;
        this.compositions = compositions;
        this.marketQuotes = marketQuotes;
        this.unsupportedMapping = unsupportedMapping == null ? id -> false : unsupportedMapping;
        this.economyState = economyState == null ? Economy.UNKNOWN : economyState;
    }

    /** Legacy synthetic constructor retained for existing tests: NORMAL economy, no guard. */
    Valuation(
            GpManagerConfig config,
            IntFunction<ItemComposition> compositions,
            IntUnaryOperator marketPrices) {
        this(config, compositions, (id, active) -> marketPrices.applyAsInt(id));
    }

    @Override
    public List<Flow> value(Map<Integer, Long> quantityDeltas) {
        return value(quantityDeltas, 0L);
    }

    @Override
    public List<Flow> value(Map<Integer, Long> quantityDeltas, long priceCapturedAtEpochMillis) {
        var flows = new ArrayList<Flow>();
        Map<Integer, Integer> priceOverrides = priceOverrides();
        for (Map.Entry<Integer, Long> entry : quantityDeltas.entrySet()) {
            Flow flow =
                    valueOne(
                            entry.getKey(),
                            entry.getValue(),
                            priceOverrides,
                            priceCapturedAtEpochMillis);
            if (flow != null) flows.add(flow);
        }
        flows.sort(
                Comparator.comparingLong((Flow flow) -> SafeMath.abs(flow.valueDelta)).reversed());
        return flows;
    }

    /**
     * Revalues only a confirmed Drink action. Ordinary valuation remains a quote of each observed
     * item variant; this hook is deliberately separate so a decant, transfer or production shape
     * cannot be mistaken for consumption before action settlement.
     */
    @Override
    public List<Flow> normalizeConsumedFlows(
            List<Flow> flows, ActionKind actionKind, long priceCapturedAtEpochMillis) {
        if (flows == null
                || flows.isEmpty()
                || actionKind != ActionKind.DRINK
                || ActionReader.isDecant(flows)) {
            return flows;
        }
        Map<Integer, Integer> priceOverrides = priceOverrides();
        List<ActionReader.PartialUse> pairs = ActionReader.partialUses(flows);
        if (pairs.isEmpty()) return flows;
        // Each exact pair is an atomic accounting unit. Compute both replacement flows before
        // assigning either one; an unsupported basis returns null and leaves the caller's
        // original captured list untouched for Review.
        var normalized = new ArrayList<>(flows);
        for (ActionReader.PartialUse pair : pairs) {
            if (!normalizePotionPair(
                    normalized,
                    pair.lossIndex,
                    pair.remainderIndex,
                    priceOverrides,
                    priceCapturedAtEpochMillis)) {
                return null;
            }
        }
        return normalized;
    }

    Flow doseAuthority(Flow flow, Map<Integer, Integer> priceOverrides, long now) {
        if (flow == null) return null;
        String one =
                (ActionReader.doseBaseName(flow.itemName) + "(1)").trim().toLowerCase(Locale.ROOT);
        Flow geCandidate = null;
        try {
            for (int id :
                    ItemVariationMapping.getVariations(ItemVariationMapping.map(flow.itemId))) {
                Flow unit = valueOne(id, 1L, priceOverrides, now, false);
                String unitName = unit == null ? "" : unit.itemName;
                if (unitName == null || !one.equals(Phrases.lower(unitName))) continue;
                if (unit.getPriceSource() == MANUAL_OVERRIDE) return unit;
                if (unit.getPriceSource() == GRAND_EXCHANGE && geCandidate == null)
                    geCandidate = unit;
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return geCandidate;
    }

    /**
     * The per-dose price for a dose variant with no quote of its own (owner 1.3.4): the family's
     * highest-dose quote scaled by this variant's dose, so a Burning amulet(4) is four fifths of
     * the (5) and one teleport costs a fifth. A manual override anywhere in the family is
     * authoritative; RuneLite's variation mapping names the family and anything malformed fails
     * closed to null.
     */
    Flow doseFamilyQuote(int itemId, String itemName, Map<Integer, Integer> priceOverrides) {
        int dose = ActionReader.trailingDose(itemName);
        String base = ActionReader.doseBaseName(itemName);
        if (dose <= 0 || base.isEmpty()) return null;
        int anchorDose = 0;
        int anchorPrice = 0;
        boolean manual = false;
        try {
            for (int id : ItemVariationMapping.getVariations(ItemVariationMapping.map(itemId))) {
                if (id == itemId) continue;
                String otherName = compositionName(id);
                int otherDose = ActionReader.trailingDose(otherName);
                if (otherDose <= 0
                        || !base.equalsIgnoreCase(ActionReader.doseBaseName(otherName))) {
                    continue;
                }
                Integer override = priceOverrides.get(id);
                if (override != null && override > 0) {
                    if (!manual || otherDose > anchorDose) {
                        manual = true;
                        anchorDose = otherDose;
                        anchorPrice = override;
                    }
                    continue;
                }
                if (manual || otherDose <= anchorDose) continue;
                int quote = quoteOrZero(id);
                if (quote > 0) {
                    anchorDose = otherDose;
                    anchorPrice = quote;
                }
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        if (anchorDose <= 0 || anchorPrice <= 0) return null;
        long scaled = (long) anchorPrice * dose / anchorDose;
        if (scaled <= 0L || scaled > Integer.MAX_VALUE) return null;
        return new Flow(
                itemId,
                itemName,
                1L,
                (int) scaled,
                scaled,
                manual ? MANUAL_OVERRIDE : GRAND_EXCHANGE,
                0L);
    }

    /** One variation family member's display name, "" when the definition is unavailable. */
    String compositionName(int id) {
        try {
            ItemComposition composition = compositions.apply(id);
            return composition == null || composition.getName() == null
                    ? ""
                    : composition.getName();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private boolean normalizePotionPair(
            List<Flow> normalized,
            int lossIndex,
            int remainderIndex,
            Map<Integer, Integer> priceOverrides,
            long now) {
        Flow loss = normalized.get(lossIndex);
        Flow remainder = normalized.get(remainderIndex);
        int lostDose = ActionReader.trailingDose(loss.itemName);
        int gainedDose = ActionReader.trailingDose(remainder.itemName);
        if (lostDose <= 0
                || gainedDose <= 0
                || lostDose != gainedDose + 1
                || !ActionReader.doseBaseName(loss.itemName)
                        .equals(ActionReader.doseBaseName(remainder.itemName))) {
            return false;
        }
        Flow authority = doseAuthority(loss, priceOverrides, now);
        if (!compatibleWithAuthority(loss, authority)
                || !compatibleWithAuthority(remainder, authority)) return false;
        try {
            long lossUnit = multiplyExact(lostDose, (long) authority.unitPrice);
            long remainderUnit = multiplyExact(gainedDose, (long) authority.unitPrice);
            Flow normalizedLoss =
                    new Flow(
                            loss.itemId,
                            loss.itemName,
                            loss.quantityDelta,
                            toIntExact(lossUnit),
                            multiplyExact(loss.quantityDelta, lossUnit),
                            authority.getPriceSource(),
                            authority.getPriceCapturedAtEpochMillis());
            Flow normalizedRemainder =
                    new Flow(
                            remainder.itemId,
                            remainder.itemName,
                            remainder.quantityDelta,
                            toIntExact(remainderUnit),
                            multiplyExact(remainder.quantityDelta, remainderUnit),
                            authority.getPriceSource(),
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
        return authority.getPriceSource() == MANUAL_OVERRIDE
                && (flow.getPriceSource() == GRAND_EXCHANGE && flow.unitPrice > 0
                        || flow.getPriceSource() == MANUAL_OVERRIDE);
    }

    /**
     * One measured charge component valued through the same policy as ordinary observations:
     * deferred claim, face value, explicit manual override, the mapping guard, the world/economy
     * gate, the explicit active RuneLite market route and UNPRICED otherwise.
     */
    Flow valueComponent(int itemId, long quantityDelta, long now) {
        return valueOne(itemId, quantityDelta, priceOverrides(), now);
    }

    @Override
    public boolean automaticMarketQuotesAvailable() {
        return economyState == Economy.NORMAL;
    }

    /**
     * Client-thread world/economy refresh. UNKNOWN and special economies fail automatic market
     * quotes closed; manual override and face-value policy still apply.
     */
    void setEconomyState(Economy state) {
        economyState = state == null ? Economy.UNKNOWN : state;
    }

    /** Client-thread Karamja-gloves snapshot; picks the improved proxy rate while worn. */
    void setKaramjaGlovesWorn(boolean worn) {
        karamjaGlovesWorn = worn;
    }

    /**
     * Every main-game world (members, PvP, high risk, LMS, PvP Arena, skill total...) shares the
     * Grand Exchange and keeps automatic quotes. Only the separate economies (Deadman, Leagues,
     * speedrunning, beta, tournament and no-save worlds) and an absent world observation fail
     * closed.
     */
    static Economy economyFor(Collection<WorldType> worldTypes) {
        if (worldTypes == null) return Economy.UNKNOWN;
        for (WorldType type : worldTypes) {
            if (type == null) return Economy.UNKNOWN;
            switch (type) {
                case DEADMAN:
                case SEASONAL:
                case QUEST_SPEEDRUNNING:
                case BETA_WORLD:
                case TOURNAMENT_WORLD:
                case NOSAVE_MODE:
                    return Economy.UNSUPPORTED_SPECIAL;
                default:
                    break;
            }
        }
        return Economy.NORMAL;
    }

    /**
     * Owner 2026-10-09 (1.3.0): an ironman who chose high alch values items at their alchemy price;
     * ironmen cannot sell on the Grand Exchange. The plugin sets it each tick from the account
     * type.
     */
    volatile boolean alch;

    Flow valueOne(
            int itemId,
            long quantityDelta,
            Map<Integer, Integer> priceOverrides,
            long priceCapturedAtEpochMillis) {
        return valueOne(itemId, quantityDelta, priceOverrides, priceCapturedAtEpochMillis, true);
    }

    Flow valueOne(
            int itemId,
            long quantityDelta,
            Map<Integer, Integer> priceOverrides,
            long priceCapturedAtEpochMillis,
            boolean doseFallback) {
        boolean deferredClaim = DeferredClaims.of(itemId);
        try {
            ItemComposition composition = null;
            try {
                composition = compositions.apply(itemId);
            } catch (RuntimeException ignored) {
                // No definition yet: the flow keeps a placeholder name.
            }
            String name =
                    composition == null || composition.getName() == null
                            ? "Item " + itemId
                            : composition.getName();
            // A clue scroll, scroll box or reward casket is worth nothing until its casket opens
            // (owner 2026-10-08).
            deferredClaim |= GameData.pattern("clue").matcher(name).matches();
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
            if (!manualOverride
                    && !deferredClaim
                    && faceValue == 0
                    && !CurrencyProxyCatalogue.isMapped(itemId)
                    && economyState == Economy.NORMAL
                    && !unsupportedMapping.test(itemId)) {
                // GP Manager's single automatic market policy: the explicit active route,
                // gated by the current world/economy and the mapping guard above.
                marketPrice = quoteOrZero(itemId);
            }
            // A proxy row values the component from a market item and its fixed exchange rate.
            // The proxy's market item is priced only where market quotes are, never on unknown or
            // special worlds (Codex review 1.2).
            int[] proxy =
                    marketPrice == 0 && economyState == Economy.NORMAL
                            ? CurrencyProxyCatalogue.proxyOf(itemId)
                            : null;
            if (proxy != null) {
                // Some exchanges improve while Karamja gloves are worn; the proxy row may
                // carry the improved units after a slash (tokkul: 300,000 or 260,000 per onyx).
                int units = proxy[1];
                if (karamjaGlovesWorn) {
                    int glovesUnits = CurrencyProxyCatalogue.glovesUnitsOf(itemId);
                    if (glovesUnits > 0) units = glovesUnits;
                }
                // No proxy quote: the flow stays unpriced for Review.
                marketPrice = quoteOrZero(proxy[0]) / units;
            }
            // RuneLite's ItemMapping carries no bracelet of ethereum entry (upstream omission,
            // verified 2026-10-12): the charged bracelet prices from its uncharged form's live
            // quote, the same relationship the revenant weapons already have.
            if (marketPrice == 0
                    && itemId == ItemID.WILD_CAVE_BRACELET_CHARGED
                    && !manualOverride
                    && economyState == Economy.NORMAL
                    && !unsupportedMapping.test(itemId)) {
                // No quote: the flow stays unpriced for Review.
                marketPrice = quoteOrZero(ItemID.WILD_CAVE_BRACELET_UNCHARGED);
            }
            // A partial dose RuneLite cannot quote (Burning amulet(4)) is priced from its family's
            // highest-dose quote at the same rate per dose (owner 1.3.4, GP-Per-Hour's fractional
            // model); a manual override anywhere in the family is authoritative.
            Flow doseQuote = null;
            if (marketPrice == 0
                    && doseFallback
                    && !manualOverride
                    && !deferredClaim
                    && faceValue == 0
                    && economyState == Economy.NORMAL
                    && !CurrencyProxyCatalogue.isMapped(itemId)
                    && !unsupportedMapping.test(itemId)) {
                doseQuote = doseFamilyQuote(itemId, name, priceOverrides);
            }
            // Half a pie, a pizza half or a cake slice is its share of the whole one's quote
            // (owner live 2026-10-16: Half a summer pie read "?").
            String part = PARTS.get(String.valueOf(itemId));
            if (marketPrice == 0
                    && part != null
                    && !manualOverride
                    && economyState == Economy.NORMAL) {
                String[] share = part.split(" ");
                marketPrice =
                        (int)
                                ((long) quoteOrZero(Integer.parseInt(share[0]))
                                        * Integer.parseInt(share[1])
                                        / Integer.parseInt(share[2]));
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
            } else if (alch && composition != null && composition.getHaPrice() > 0) {
                unitPrice = composition.getHaPrice();
                priceSource = HIGH_ALCHEMY;
            } else if (marketPrice > 0) {
                // The item's own market quote is a supported observation. Non-GP currencies
                // without a quote stay unpriced; an exchange relationship is never converted into
                // booked GP.
                unitPrice = marketPrice;
                priceSource = proxy == null ? GRAND_EXCHANGE : CURRENCY_PROXY;
            } else if (doseQuote != null) {
                // The derived price follows its family anchor's source so provenance stays honest.
                unitPrice = doseQuote.unitPrice;
                priceSource = doseQuote.getPriceSource();
            } else if (composition != null
                    && !CurrencyProxyCatalogue.isMapped(itemId)
                    && !unsupportedMapping.test(itemId)
                    && (!composition.isTradeable()
                            || economyState == Economy.NORMAL
                                    && quoteOrZero(ItemID.NATURERUNE) > 0)) {
                // No market quote (untradeable, or tradeable once prices have loaded): its alch
                // value for every account (a crystal shard), else worth nothing (a Progress hat),
                // a known zero rather than a missing price (owner 2026-10-16). With both, the GE
                // price above wins unless an ironman chose alch values.
                unitPrice = max(0, composition.getHaPrice());
                priceSource = unitPrice > 0 ? HIGH_ALCHEMY : NO_VALUE;
            } else {
                unitPrice = 0;
                priceSource = UNPRICED;
            }
            long valueDelta = safeMultiply(quantityDelta, unitPrice);
            // Only a non-zero selected price is a captured price. Unpriced/deferred flows
            // deliberately retain zero so consumers cannot present a fabricated capture time.
            long capturedAt =
                    unitPrice > 0 && priceSource != PriceSource.UNKNOWN && priceSource != FACE_VALUE
                            ? SafeMath.nonNeg(priceCapturedAtEpochMillis)
                            : 0L;
            return new Flow(
                    itemId, name, quantityDelta, unitPrice, valueDelta, priceSource, capturedAt);
        } catch (RuntimeException ignored) {
            return new Flow(
                    itemId,
                    "Item " + itemId,
                    quantityDelta,
                    0,
                    0L,
                    deferredClaim ? DEFERRED_CLAIM : UNPRICED);
        }
    }

    static int faceValueOf(int itemId) {
        if (itemId == ItemID.COINS) return 1;
        if (itemId == ItemID.PLATINUM) return 1_000;
        return 0;
    }

    /**
     * ItemManager's convenience quote recursively applies ItemMapping. Only a quantity-one,
     * non-coin mapping can represent a state/base identity; quantity/rate mappings and coin targets
     * would silently manufacture monetary identity for a physically distinct item.
     */
    static boolean hasUnsupportedMapping(ItemManager itemManager, int itemId) {
        try {
            int canonicalId = itemManager.canonicalize(itemId);
            return hasUnsupportedMapping(ItemMapping.map(canonicalId));
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    static boolean hasUnsupportedMapping(Collection<ItemMapping> mappings) {
        if (mappings == null) return false;
        for (ItemMapping mapping : mappings) {
            if (mapping.getQuantity() != 1L || mapping.getTradeableItem() == ItemID.COINS)
                return true;
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

    /** The owner's manual price overrides by item id. */
    Map<Integer, Integer> priceOverrides() {
        return ItemRuleParser.parsePriceOverrides(config.manualPriceOverrides());
    }
}

class ItemRuleParser {
    static Map<Integer, Integer> parsePriceOverrides(String value) {
        if (Phrases.blank(value)) return Collections.emptyMap();
        var result = new HashMap<Integer, Integer>();
        for (String token : value.split(",")) {
            String[] parts = token.trim().split("=", 2);
            if (parts.length != 2) continue;
            try {
                int itemId = Integer.parseInt(parts[0].trim());
                int price = Integer.parseInt(parts[1].trim());
                if (itemId >= 0 && price >= 0) result.put(itemId, price);
            } catch (NumberFormatException ignored) {
                // Invalid entries are ignored so a typo cannot break tracking.
            }
        }
        return result;
    }
}

@FunctionalInterface
interface FlowValuator {
    List<Flow> value(Map<Integer, Long> quantityDeltas);

    /**
     * Values flows using the caller's evidence-capture time. Existing valuators remain
     * source-compatible and may ignore the timestamp until they support price provenance.
     */
    default List<Flow> value(Map<Integer, Long> quantityDeltas, long priceCapturedAtEpochMillis) {
        return value(quantityDeltas);
    }

    /**
     * Applies an action-aware normalization after the engine has confirmed a consumption. Synthetic
     * valuators keep their observed flows unchanged; the canonical valuator may normalize only the
     * explicitly evidenced action and must preserve all source provenance.
     *
     * <p>Callers must invoke this after action settlement and before custody or booking. The
     * timestamp is the same observation timestamp used to value the committed inventory delta. A
     * {@code null} result means the confirmed action had an unsupported or conflicting price basis;
     * callers must keep the original captured flows and route the receipt to Review.
     */
    default List<Flow> normalizeConsumedFlows(
            List<Flow> flows, ActionKind actionKind, long priceCapturedAtEpochMillis) {
        return flows;
    }

    /**
     * Whether the current world/economy permits ordinary automatic market quotes. Synthetic test
     * valuators default to the deterministic NORMAL fixture; the canonical {@link Valuation}
     * overrides this with its observed economy snapshot.
     */
    default boolean automaticMarketQuotesAvailable() {
        return true;
    }
}

/**
 * The item lookups accounting needs, in one place: the canonical id, the composition, the active
 * market quote and a name search. Production answers from RuneLite's ItemManager; the rework's
 * replay answers from a recording (docs/REWORK_PLAN.md), so a session books the same both ways.
 */
@Singleton
class ItemLookup {
    ItemManager manager;

    @Inject
    ItemLookup(ItemManager manager) {
        this.manager = manager;
    }

    int canon(int id) {
        return manager == null ? id : manager.canonicalize(id);
    }

    ItemComposition composition(int id) {
        return manager == null ? null : manager.getItemComposition(id);
    }

    List<ItemPrice> find(String name) {
        return manager == null ? Collections.emptyList() : manager.search(name);
    }
}

/**
 * Untradeable non-GP currencies. They are unpriced unless a manual override supplies a price or a
 * proxy row prices them from a market item and its fixed exchange rate: RuneLite's price helper
 * would otherwise value some of them through an item mapping. A proxy's units cell may read
 * base/with-gloves when Karamja gloves improve that exchange.
 */
class CurrencyProxyCatalogue {
    static final Set<Integer> CURRENCIES = GameData.byId("currencies").keySet();

    /** Proxy rows: item id to {market item id, units per proxy item}. */
    static final Map<Integer, int[]> PROXIES = new HashMap<>();

    /** Proxy rows whose exchange improves with Karamja gloves worn: item id to gloves units. */
    static final Map<Integer, Integer> GLOVES_UNITS = new HashMap<>();

    static {
        for (String[] row : GameData.rows("currencies")) {
            if (row.length > 2) {
                String[] units = row[2].split("/");
                PROXIES.put(
                        Integer.parseInt(row[0]),
                        new int[] {Integer.parseInt(row[1]), Integer.parseInt(units[0])});
                if (units.length > 1) {
                    GLOVES_UNITS.put(Integer.parseInt(row[0]), Integer.parseInt(units[1]));
                }
            }
        }
    }

    static boolean isMapped(int itemId) {
        return CURRENCIES.contains(itemId);
    }

    /** The proxy price source for this item, or null. */
    static int[] proxyOf(int itemId) {
        return PROXIES.get(itemId);
    }

    /** The gloves-worn units for this proxy row, or 0 when its rate does not change. */
    static int glovesUnitsOf(int itemId) {
        return GLOVES_UNITS.getOrDefault(itemId, 0);
    }
}
