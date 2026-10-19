package com.gpmanager;

import static com.gpmanager.ActionReader.*;
import static net.runelite.client.game.ItemVariationMapping.*;

import static java.lang.Math.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import static com.gpmanager.PriceSource.*;
import java.util.function.*;
import javax.inject.*;
import lombok.Getter;
import net.runelite.api.*;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.*;
import net.runelite.http.api.item.ItemPrice;

/**
 * Game-knowledge tables (item ids, region ids, names) bundled under /com/gpmanager/model as
 * tab-separated resources: one row per line, {@code #} lines are comments. GameDataTablesTest
 * checks every table against RuneLite's named constants.
 */
class GameData {
    /** Rule tables alone are cached: callers of legacy rows still receive independent arrays. */
    static final Map<String, List<String[]>> ROWS = new HashMap<>();

    static final Map<String, Map<String, Phrases.Clause[][]>> RULES = new HashMap<>();

    static synchronized List<String[]> cachedRows(String name) {
        return ROWS.computeIfAbsent(name, GameData::rows);
    }

    /** Whole-column text aliases may contain the existing alternative/conjunction grammar. */
    static String text(String value) {
        return value.matches("@[\\w.-]+") ? msg(value.substring(1)) : value;
    }

    /** The rows of {@code name}.tsv; a missing table is a packaging bug, so it fails loudly. */
    static List<String[]> rows(String name) {
        var stream = GameData.class.getResourceAsStream("/com/gpmanager/model/" + name + ".tsv");
        if (stream == null) throw new IllegalStateException("Missing game data table " + name);
        var rows = new ArrayList<String[]>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (!line.isEmpty() && !line.startsWith("#")) rows.add(line.split("\t", -1));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return rows;
    }

    /** {@code name}.tsv keyed by its first-column id, valued by its second column. */
    static Map<Integer, String> byId(String name) {
        var map = new HashMap<Integer, String>();
        for (String[] row : rows(name)) map.put(Integer.parseInt(row[0]), row[1]);
        return map;
    }

    static int[] intColumn(String name, int column) {
        return cachedRows(name).stream().mapToInt(row -> Integer.parseInt(row[column])).toArray();
    }

    /** Ordered first-column names; useful for catalogues whose first match wins. */
    static List<String> names(String name) {
        var names = new ArrayList<String>();
        for (String[] row : rows(name)) names.add(row[0]);
        return names;
    }

    /** Exact first-column names mapped to their second-column labels. */
    static Map<String, String> byName(String name) {
        var map = new HashMap<String, String>();
        for (String[] row : rows(name)) map.put(row[0], row[1]);
        return map;
    }

    /** Ordered boolean rules: | alternatives, + conjunctions, compiled once by each catalogue. */
    static synchronized Map<String, Phrases.Clause[][]> rules(String name) {
        if (RULES.containsKey(name)) return RULES.get(name);
        var compiled = new LinkedHashMap<String, Phrases.Clause[][]>();
        for (String[] row : cachedRows(name)) {
            compiled.put(row[0], Arrays.stream(text(row[1]).split("\\|", -1)).filter(d -> !d.isEmpty()).map(d ->
                                            Arrays.stream(d.split("\\+", -1)).map(Phrases.Clause::new)
                                                    .toArray(Phrases.Clause[]::new)).toArray(Phrases.Clause[][]::new));
        }
        RULES.put(name, compiled);
        return compiled;
    }

    static final Map<String, String> TEXT = byName("text");
    static final Map<String, Pattern> PATTERNS = new HashMap<>();

    static {
        for (String[] row : rows("patterns"))
            PATTERNS.put(row[0], Pattern.compile(row[1].startsWith("@") ? msg(row[1].substring(1)) : row[1],
                            Integer.parseInt(row[2])));
    }

    static Pattern pattern(String key) {
        return Objects.requireNonNull(PATTERNS.get(key), "Missing pattern " + key);
    }

    /**
     * Text keyed by a prefix and an enum constant ("verb.TRADE"), or the fallback when it has no
     * row.
     */
    static String msg(String key, String fallback) {
        return TEXT.getOrDefault(key, fallback);
    }

    /** Long user-facing text from text.tsv; a test checks every key the code uses. */
    static String msg(String key) {
        String text = TEXT.get(key);
        if (text == null) throw new IllegalStateException("Missing text " + key);
        return text;
    }
}

/**
 * Saturating long/int arithmetic shared by the accounting model; overflow clamps toward the sign of
 * the addend.
 */
class SafeMath {
    /** The wall clock every accounting read uses; the rework's replay sets it to recorded time. */
    static volatile LongSupplier clock = System::currentTimeMillis;

    static long now() {
        return clock.getAsLong();
    }

    /** Absolute value that saturates instead of overflowing on {@code Long.MIN_VALUE}. */
    /** The value, or zero when it is negative. */
    static long nonNeg(long value) {
        return max(0, value);
    }

    static long abs(long value) {
        return value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
    }

    static long safeAdd(long left, long right) {
        try {
            return addExact(left, right);
        } catch (ArithmeticException ex) {
            return right >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    static long safeSubtract(long left, long right) {
        try {
            return subtractExact(left, right);
        } catch (ArithmeticException ex) {
            return right < 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    static long safeMultiply(long left, long right) {
        try {
            return multiplyExact(left, right);
        } catch (ArithmeticException ex) {
            return (left >= 0) == (right >= 0) ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    /** A long clamped into the int range (unit prices). */
    static int toInt(long value) {
        return (int) max(Integer.MIN_VALUE, min(Integer.MAX_VALUE, value));
    }

    /** Non-negative count sum saturating at {@link Integer#MAX_VALUE}. */
    static int safeAddCount(int left, int right) {
        long sum = (long) max(0, left) + max(0, right);
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }
}

/** Display-name defaults and phrase matching shared by the model and the classifiers. */
class Phrases {
    static String uuid() {
        return UUID.randomUUID().toString();
    }

    /** Blank activity names fall back to the General bucket. */
    static String normalizeActivity(String value) {
        return nonBlank(value, "General");
    }

    static String orEmpty(String text) {
        return text == null ? "" : text;
    }

    /** Trimmed lower-case text; null reads as empty. */
    static String lower(String value) {
        return lower(value, true);
    }

    /** Some chat/target rules intentionally distinguish leading spaces. */
    static String lower(String value, boolean trim) {
        return value == null ? "" : (trim ? value.trim() : value).toLowerCase(Locale.ROOT);
    }

    static boolean empty(String text) {
        return text == null || text.isEmpty();
    }

    static boolean empty(Collection<?> values) {
        return values == null || values.isEmpty();
    }

    static boolean empty(Map<?, ?> values) {
        return values == null || values.isEmpty();
    }

    static boolean blank(String text) {
        return text == null || text.trim().isEmpty();
    }

    /** The trimmed text, or the fallback when it is blank. */
    static String nonBlank(String text, String fallback) {
        return blank(text) ? fallback : text.trim();
    }

    /** Trimmed lower-case text, or null when blank. */
    static String norm(String value) {
        String key = lower(value);
        return key.isEmpty() ? null : key;
    }

    /** True when the text contains any of the words. */
    static boolean has(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) return true;
        }
        return false;
    }

    /** True when any non-null term contains the already lower-case needle. */
    static boolean containsFolded(String lowerNeedle, String... terms) {
        for (String term : terms) {
            if (term != null && term.toLowerCase(Locale.ROOT).contains(lowerNeedle)) return true;
        }
        return false;
    }

    /**
     * A compiled table clause: contains, =exact, ^prefix, $suffix, %pattern, ~case-insensitive
     * exact (Unicode equalsIgnoreCase), with optional !.
     */
    static class Clause {
        String text;
        int kind;
        boolean negated;
        Pattern pattern;

        Clause(String spec) {
            if (spec.startsWith("@")) spec = GameData.msg(spec.substring(1));
            negated = spec.startsWith("!");
            if (negated) spec = spec.substring(1);
            kind = spec.isEmpty() ? -1 : "=^$%~".indexOf(spec.charAt(0));
            text = kind < 0 ? spec : spec.substring(1);
            pattern = kind == 3 ? GameData.pattern(text) : null;
        }
    }

    /** Alternatives of conjunctions; all parsing and regex compilation happens at table load. */
    static boolean has(String text, Clause[][] rules) {
        if (text == null) return false;
        for (Clause[] clauses : rules) if (containsAll(text, clauses)) return true;
        return false;
    }

    static boolean containsAll(String text, Clause[] clauses) {
        for (var term : clauses) {
            boolean hit = term.kind == 0 ? text.equals(term.text) : term.kind == 1 ? text.startsWith(term.text)
                                    : term.kind == 2 ? text.endsWith(term.text) : term.kind == 3
                                                    ? term.pattern.matcher(text).matches() : term.kind == 4
                                                            ? text.equalsIgnoreCase(term.text)
                                                            : text.contains(term.text);
            if (hit == term.negated) return false;
        }
        return true;
    }
}

@Singleton
class Valuation implements FlowValuator {
    /** Whether the current world/economy permits ordinary automatic RuneLite market quotes. */
    enum Economy {
        NORMAL, UNSUPPORTED_SPECIAL, UNKNOWN
    }

    /**
     * Explicit RuneLite quote route. The boolean is GP Manager's own policy choice, never the
     * user's global RuneLite display-price preference, so identical observations book the same
     * amount. Production binds this to {@code ItemManager::getItemPriceWithSource}.
     */
    @FunctionalInterface
    interface MarketQuote {
        int priceOf(int id, boolean active);
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
    int quoteOrZero(int id) {
        try {
            return max(0, marketQuotes.priceOf(id, true));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    @Inject
    Valuation(ItemManager items, GpManagerConfig config) {
        // Accounting explicitly requests GP Manager's chosen RuneLite active market route (the
        // boolean is always true at the call site). It must not delegate to getItemPrice(),
        // whose implementation follows the user's global useWikiItemPrices display preference
        // and would make identical observations book different amounts. RuneLite may itself
        // fall back to guide/Jagex pricing; the selected quote is captured and frozen at
        // booking time, and history is never repriced. The economy gate starts UNKNOWN until
        // the plugin observes a logged-in world, so automatic quotes fail closed meanwhile.
        this(config, items::getItemComposition,
                (id, active) -> unitPriceOf(items.getItemPriceWithSource(id, active)),
                id -> hasUnsupportedMapping(items, id), Economy.UNKNOWN);
    }

    /**
     * Unit-price boundary for a RuneLite quote. RuneLite 1.13.1 widened the quote to long for
     * beyond-max-cash trades; the unit-price model is int, so a value that does not fit (or is not
     * positive) stays unpriced for Review instead of booking a clamped, fabricated amount.
     */
    static int unitPriceOf(long quote) {
        return quote <= 0 || quote > Integer.MAX_VALUE ? 0 : SafeMath.toInt(quote);
    }

    /** Test seam: deterministic NORMAL economy and no mapping guard; the boolean stays explicit. */
    Valuation(GpManagerConfig config, IntFunction<ItemComposition> compositions, MarketQuote marketQuotes) {
        this(config, compositions, marketQuotes, id -> false, Economy.NORMAL);
    }

    Valuation(GpManagerConfig config, IntFunction<ItemComposition> compositions, MarketQuote marketQuotes,
            IntPredicate unsupportedMapping, Economy economyState) {
        this.config = config;
        this.compositions = compositions;
        this.marketQuotes = marketQuotes;
        this.unsupportedMapping = unsupportedMapping == null ? id -> false : unsupportedMapping;
        this.economyState = economyState == null ? Economy.UNKNOWN : economyState;
    }

    @Override
    public List<Flow> value(Map<Integer, Long> changes) {
        return value(changes, 0);
    }

    @Override
    public List<Flow> value(Map<Integer, Long> changes, long capturedAt) {
        var flows = new ArrayList<Flow>();
        var prices = priceOverrides();
        for (var entry : changes.entrySet()) {
            Flow flow = valueOne(entry.getKey(), entry.getValue(), prices, capturedAt);
            if (flow != null) flows.add(flow);
        }
        flows.sort(Comparator.comparingLong((Flow flow) -> SafeMath.abs(flow.valueDelta)).reversed());
        return flows;
    }

    /**
     * Revalues only a confirmed Drink action. Ordinary valuation remains a quote of each observed
     * item variant; this hook is deliberately separate so a decant, transfer or production shape
     * cannot be mistaken for consumption before action settlement.
     */
    @Override
    public List<Flow> normalizeConsumedFlows(List<Flow> flows, ActionKind action, long capturedAt) {
        if (flows == null || flows.isEmpty() || action != ActionKind.DRINK || isDecant(flows)) {
            return flows;
        }
        var prices = priceOverrides();
        var pairs = partialUses(flows);
        if (pairs.isEmpty()) return flows;
        // Each exact pair is an atomic accounting unit. Compute both replacement flows before
        // assigning either one; an unsupported basis returns null and leaves the caller's
        // original captured list untouched for Review.
        var normalized = new ArrayList<>(flows);
        for (var pair : pairs) {
            if (!normalizePotionPair(normalized, pair.lossIndex, pair.remainderIndex, prices,
                    capturedAt)) {
                return null;
            }
        }
        return normalized;
    }

    Flow doseAuthority(Flow flow, Map<Integer, Integer> prices, long now) {
        if (flow == null) return null;
        String one = (doseBaseName(flow.itemName) + "(1)").trim().toLowerCase(Locale.ROOT);
        Flow geCandidate = null;
        try {
            for (int id : getVariations(map(flow.itemId))) {
                Flow unit = valueOne(id, 1, prices, now, false);
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
    Flow doseFamilyQuote(int identifier, String name, Map<Integer, Integer> prices) {
        int dose = trailingDose(name);
        String base = doseBaseName(name);
        if (dose <= 0 || base.isEmpty()) return null;
        int anchorDose = 0, anchorPrice = 0;
        boolean manual = false;
        try {
            for (int id : getVariations(map(identifier))) {
                if (id == identifier) continue;
                String otherName = compositionName(id);
                int otherDose = trailingDose(otherName);
                if (otherDose <= 0 || !base.equalsIgnoreCase(doseBaseName(otherName))) {
                    continue;
                }
                Integer override = prices.get(id);
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
        if (scaled <= 0 || scaled > Integer.MAX_VALUE) return null;
        return new Flow(identifier, name, 1L, (int) scaled, scaled, manual ? MANUAL_OVERRIDE : GRAND_EXCHANGE, 0L);
    }

    /** One variation family member's display name, "" when the definition is unavailable. */
    String compositionName(int id) {
        try {
            var composition = compositions.apply(id);
            return composition == null || composition.getName() == null ? "" : composition.getName();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private boolean normalizePotionPair(List<Flow> normalized, int lossIndex, int remainderIndex,
            Map<Integer, Integer> prices, long now) {
        Flow loss = normalized.get(lossIndex), rest = normalized.get(remainderIndex);
        int lostDose = trailingDose(loss.itemName), dose = trailingDose(rest.itemName);
        if (lostDose <= 0 || dose <= 0 || lostDose != dose + 1 || !doseBaseName(loss.itemName)
                        .equals(doseBaseName(rest.itemName))) {
            return false;
        }
        Flow authority = doseAuthority(loss, prices, now);
        if (!compatibleWithAuthority(loss, authority) || !compatibleWithAuthority(rest, authority)) return false;
        try {
            long lossUnit = multiplyExact(lostDose, (long) authority.unitPrice);
            long remainderUnit = multiplyExact(dose, (long) authority.unitPrice);
            Flow normalizedLoss = new Flow(loss.itemId, loss.itemName, loss.quantityDelta, toIntExact(lossUnit),
                            multiplyExact(loss.quantityDelta, lossUnit), authority.getPriceSource(),
                            authority.getPriceCapturedAtEpochMillis());
            Flow normalizedRemainder = new Flow(rest.itemId, rest.itemName, rest.quantityDelta,
                            toIntExact(remainderUnit), multiplyExact(rest.quantityDelta, remainderUnit),
                            authority.getPriceSource(), authority.getPriceCapturedAtEpochMillis());
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
    Flow valueComponent(int id, long delta, long now) {
        return valueOne(id, delta, priceOverrides(), now);
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
    void setKaramjaGlovesWorn(boolean gear) {
        karamjaGlovesWorn = gear;
    }

    /**
     * Every main-game world (members, PvP, high risk, LMS, PvP Arena, skill total...) shares the
     * Grand Exchange and keeps automatic quotes. Only the separate economies (Deadman, Leagues,
     * speedrunning, beta, tournament and no-save worlds) and an absent world observation fail
     * closed.
     */
    static Economy economyFor(Collection<WorldType> worldTypes) {
        if (worldTypes == null) return Economy.UNKNOWN;
        for (var type : worldTypes) {
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

    Flow valueOne(int id, long delta, Map<Integer, Integer> prices,
            long capturedAt) {
        return valueOne(id, delta, prices, capturedAt, true);
    }

    Flow valueOne(int id, long delta, Map<Integer, Integer> prices, long priceCapturedAtEpochMillis,
            boolean doseFallback) {
        boolean claim = DeferredClaims.of(id);
        try {
            ItemComposition composition = null;
            try {
                composition = compositions.apply(id);
            } catch (RuntimeException ignored) {
                // No definition yet: the flow keeps a placeholder name.
            }
            String name = composition == null || composition.getName() == null ? "Item " + id
                            : composition.getName();
            // A clue scroll, scroll box or reward casket is worth nothing until its casket opens
            // (owner 2026-10-08).
            claim |= GameData.pattern("clue").matcher(name).matches();
            // An empty vial is a physical neutral; filled variants remain ordinary items.
            if (id == ItemID.VIAL_EMPTY) return null;
            boolean manualOverride = prices.containsKey(id);
            int faceValue = faceValueOf(id);
            // For ordinary items, a user override is authoritative even before RuneLite prices
            // load; fixed currencies below retain their face value by policy.
            int quote = 0;
            // RuneLite's ItemManager price helper is intentionally convenient: when an item has
            // no direct quote it can recurse through ItemMapping and price an untradeable variant
            // from another item (for example unidentified minerals or golden nuggets via coal).
            // Those catalogue entries are non-GP currencies in GP Manager, so their physical
            // identity must not inherit a monetary identity from the platform mapping. An
            // explicit owner override remains authoritative.
            if (!manualOverride && !claim && faceValue == 0 && !CurrencyProxyCatalogue.isMapped(id)
                    && economyState == Economy.NORMAL && !unsupportedMapping.test(id)) {
                // GP Manager's single automatic market policy: the explicit active route,
                // gated by the current world/economy and the mapping guard above.
                quote = quoteOrZero(id);
            }
            // A proxy row values the component from a market item and its fixed exchange rate.
            // The proxy's market item is priced only where market quotes are, never on unknown or
            // special worlds (Codex review 1.2).
            int[] proxy = quote == 0 && economyState == Economy.NORMAL ? CurrencyProxyCatalogue.proxyOf(id)
                            : null;
            if (proxy != null) {
                // Some exchanges improve while Karamja gloves are worn; the proxy row may
                // carry the improved units after a slash (tokkul: 300,000 or 260,000 per onyx).
                int units = proxy[1];
                if (karamjaGlovesWorn) {
                    int glovesUnits = CurrencyProxyCatalogue.glovesUnitsOf(id);
                    if (glovesUnits > 0) units = glovesUnits;
                }
                // No proxy quote: the flow stays unpriced for Review.
                quote = quoteOrZero(proxy[0]) / units;
            }
            // RuneLite's ItemMapping carries no bracelet of ethereum entry (upstream omission,
            // verified 2026-10-12): the charged bracelet prices from its uncharged form's live
            // quote, the same relationship the revenant weapons already have.
            if (quote == 0 && id == ItemID.WILD_CAVE_BRACELET_CHARGED && !manualOverride
                    && economyState == Economy.NORMAL && !unsupportedMapping.test(id)) {
                // No quote: the flow stays unpriced for Review.
                quote = quoteOrZero(ItemID.WILD_CAVE_BRACELET_UNCHARGED);
            }
            // A partial dose RuneLite cannot quote (Burning amulet(4)) is priced from its family's
            // highest-dose quote at the same rate per dose (owner 1.3.4, GP-Per-Hour's fractional
            // model); a manual override anywhere in the family is authoritative.
            Flow doseQuote = null;
            if (quote == 0 && doseFallback && !manualOverride && !claim && faceValue == 0
                    && economyState == Economy.NORMAL && !CurrencyProxyCatalogue.isMapped(id)
                    && !unsupportedMapping.test(id)) {
                doseQuote = doseFamilyQuote(id, name, prices);
            }
            // Half a pie, a pizza half or a cake slice is its share of the whole one's quote
            // (owner live 2026-10-16: Half a summer pie read "?").
            String part = PARTS.get(String.valueOf(id));
            if (quote == 0 && part != null && !manualOverride && economyState == Economy.NORMAL) {
                String[] share = part.split(" ");
                quote = (int)
                                ((long) quoteOrZero(Integer.parseInt(share[0]))
                                        * Integer.parseInt(share[1])
                                        / Integer.parseInt(share[2]));
            }
            int price;
            PriceSource source;
            if (claim) {
                // A non-tradeable key is an unopened claim, not held wealth. Its
                // contents become ordinary valued inventory gains only on receipt.
                price = 0;
                source = DEFERRED_CLAIM;
            } else if (faceValue > 0) {
                price = faceValue;
                source = FACE_VALUE;
            } else if (manualOverride) {
                price = prices.get(id);
                source = MANUAL_OVERRIDE;
            } else if (alch && composition != null && composition.getHaPrice() > 0) {
                price = composition.getHaPrice();
                source = HIGH_ALCHEMY;
            } else if (quote > 0) {
                // The item's own market quote is a supported observation. Non-GP currencies
                // without a quote stay unpriced; an exchange relationship is never converted into
                // booked GP.
                price = quote;
                source = proxy == null ? GRAND_EXCHANGE : CURRENCY_PROXY;
            } else if (doseQuote != null) {
                // The derived price follows its family anchor's source so provenance stays honest.
                price = doseQuote.unitPrice;
                source = doseQuote.getPriceSource();
            } else if (composition != null && !CurrencyProxyCatalogue.isMapped(id)
                    && !unsupportedMapping.test(id) && (!composition.isTradeable() || economyState == Economy.NORMAL
                                    && quoteOrZero(ItemID.NATURERUNE) > 0)) {
                // No market quote (untradeable, or tradeable once prices have loaded): its alch
                // value for every account (a crystal shard), else worth nothing (a Progress hat),
                // a known zero rather than a missing price (owner 2026-10-16). With both, the GE
                // price above wins unless an ironman chose alch values.
                price = max(0, composition.getHaPrice());
                source = price > 0 ? HIGH_ALCHEMY : NO_VALUE;
            } else {
                price = 0;
                source = UNPRICED;
            }
            long value = safeMultiply(delta, price);
            // Only a non-zero selected price is a captured price. Unpriced/deferred flows
            // deliberately retain zero so consumers cannot present a fabricated capture time.
            long captured = price > 0 && source != UNKNOWN && source != FACE_VALUE
                            ? SafeMath.nonNeg(priceCapturedAtEpochMillis) : 0;
            return new Flow(id, name, delta, price, value, source, captured);
        } catch (RuntimeException ignored) {
            return new Flow(id, "Item " + id, delta, 0, 0L, claim ? DEFERRED_CLAIM : UNPRICED);
        }
    }

    static int faceValueOf(int id) {
        if (id == ItemID.COINS) return 1;
        if (id == ItemID.PLATINUM) return 1_000;
        return 0;
    }

    /**
     * ItemManager's convenience quote recursively applies ItemMapping. Only a quantity-one,
     * non-coin mapping can represent a state/base identity; quantity/rate mappings and coin targets
     * would silently manufacture monetary identity for a physically distinct item.
     */
    static boolean hasUnsupportedMapping(ItemManager items, int id) {
        try {
            int canonical = items.canonicalize(id);
            return hasUnsupportedMapping(ItemMapping.map(canonical));
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    static boolean hasUnsupportedMapping(Collection<ItemMapping> mappings) {
        if (mappings == null) return false;
        for (var mapping : mappings) {
            if (mapping.getQuantity() != 1 || mapping.getTradeableItem() == ItemID.COINS)
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
                int id = Integer.parseInt(parts[0].trim()), price = Integer.parseInt(parts[1].trim());
                if (id >= 0 && price >= 0) result.put(id, price);
            } catch (NumberFormatException ignored) {
                // Invalid entries are ignored so a typo cannot break tracking.
            }
        }
        return result;
    }
}

@FunctionalInterface
interface FlowValuator {
    List<Flow> value(Map<Integer, Long> changes);

    /**
     * Values flows using the caller's evidence-capture time. Existing valuators remain
     * source-compatible and may ignore the timestamp until they support price provenance.
     */
    default List<Flow> value(Map<Integer, Long> changes, long capturedAt) {
        return value(changes);
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
            List<Flow> flows, ActionKind action, long capturedAt) {
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
                PROXIES.put(Integer.parseInt(row[0]), new int[] {Integer.parseInt(row[1]), Integer.parseInt(units[0])});
                if (units.length > 1) {
                    GLOVES_UNITS.put(Integer.parseInt(row[0]), Integer.parseInt(units[1]));
                }
            }
        }
    }

    static boolean isMapped(int id) {
        return CURRENCIES.contains(id);
    }

    /** The proxy price source for this item, or null. */
    static int[] proxyOf(int id) {
        return PROXIES.get(id);
    }

    /** The gloves-worn units for this proxy row, or 0 when its rate does not change. */
    static int glovesUnitsOf(int id) {
        return GLOVES_UNITS.getOrDefault(id, 0);
    }
}
