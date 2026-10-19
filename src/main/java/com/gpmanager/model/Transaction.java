package com.gpmanager;


import static com.gpmanager.GameData.*;

import static com.gpmanager.ActionKind.*;
import static com.gpmanager.Correction.*;
import static com.gpmanager.Phrases.*;
import static com.gpmanager.PriceSource.*;
import static com.gpmanager.SafeMath.*;
import static com.gpmanager.TransactionType.*;
import static java.lang.Math.min;
import static java.util.Collections.*;

import java.util.*;
import lombok.*;
import com.gpmanager.ActionReader.PartialUse;
import static com.gpmanager.SafeMath.abs;
import static java.util.Locale.ROOT;

/**
 * Canonical durable financial fact: stable id, timestamp, type/context, booked item flows, the
 * automatic counted state, confidence, correction and split state, and optional links to a PvP
 * encounter or the pending claim it settled. Booked values are never repriced.
 */
class Transaction extends SaveCopies {
    String id;
    long timestampEpochMillis;
    TransactionType type;
    Context context;
    String note, activityName;
    boolean counted;
    List<Flow> flows;
    long revenue, costs;
    Confidence confidence;
    String explanation;
    Correction correction;
    String correctionReason, encounterId;

    /**
     * Dropped or fired, its pickup window still open: picking it up shrinks this row, so it leaves
     * the paid-price record only once the window closes. Saved only while true.
     */
    Boolean onGround;

    boolean isOnGround() {
        return Boolean.TRUE.equals(onGround);
    }

    void setOnGround(boolean value) {
        onGround = value ? Boolean.TRUE : null;
    }

    /** The pending claim this settlement resolved, when any; idempotency key on load. */
    String sourceClaimId;

    /** Observed action wire name (B10 presentation evidence); null when unsupported/legacy. */
    String actionKind;

    /**
     * Durable bounded exact action label (schema 105), e.g. "Ice Burst". Presentation-only metadata
     * attached to an already-booked canonical transaction: it never feeds classification,
     * valuation, counted state, ownership, Review or correction. Revalidated on load; invalid text
     * degrades to null and generic presentation.
     */
    String observedActionLabel;

    /** Spell observed at execution, independently of a subsequently queued menu command. */
    String observedSpellName;

    /** Transient icon hint for an estimated charge cast: the weapon's item id; never saved. */
    transient int castItemId;

    Transaction() {
        // Gson
    }

    Transaction(long timestamp, TransactionType type, Context context, String note, String activity,
            boolean counted, List<Flow> flows, Confidence confidence, String reason, String encounter) {
        id = uuid();
        timestampEpochMillis = timestamp;
        this.type = type;
        this.context = context;
        this.note = orEmpty(note);
        activityName = normalizeActivity(activity);
        this.counted = counted;
        this.flows = flows == null ? new ArrayList<>() : new ArrayList<>(flows);
        this.confidence = confidence == null ? Confidence.UNCERTAIN : confidence;
        explanation = orEmpty(reason);
        correction = AUTO;
        correctionReason = "";
        encounterId = encounter;
        recalculate();
    }

    void recalculate() {
        revenue = 0;
        costs = 0;
        for (Flow flow : flows) {
            if (flow.valueDelta > 0) revenue = safeAdd(revenue, flow.valueDelta);
            else if (flow.valueDelta < 0) costs = safeAdd(costs, abs(flow.valueDelta));
        }
    }

    void applyCorrection(Correction newCorrection, long now) {
        applyCorrection(newCorrection, now, "Manual correction");
    }

    void applyCorrection(Correction newCorrection, long now, String reason) {
        correction = newCorrection == null ? AUTO : newCorrection;
        correctionReason = normalizeReason(reason);
    }

    /**
     * Takes {@code amount} of one item's gains (newest flow first) or costs (oldest first) out of
     * the flows, each remainder keeping its own unit value, and returns the quantity left.
     */
    long takeQuantity(int id, boolean income, long amount) {
        var ordered = new ArrayList<>(getFlows());
        if (income) reverse(ordered);
        var next = new ArrayList<Flow>();
        for (Flow flow : ordered) {
            long size = abs(flow.quantityDelta);
            long take = flow.itemId == id && (income ? flow.isGain() : flow.isCost())
                            ? min(nonNeg(amount), size) : 0;
            amount -= take;
            long left = size - take;
            if (take <= 0) {
                next.add(flow);
            } else if (left > 0) {
                long unit = flow.unitPrice, signed = income ? left : -left;
                next.add(new Flow(id, flow.itemName, signed, flow.unitPrice,
                                unit == 0 ? flow.valueDelta / size * left : signed * unit, flow.getPriceSource(),
                                flow.getPriceCapturedAtEpochMillis()));
            }
        }
        if (income) reverse(next);
        replaceFlows(next);
        return quantity(id, income);
    }

    /** True while any flow still spends an item; a blowpipe shot keeps its scales after a dart. */
    boolean spends() {
        for (Flow flow : getFlows()) {
            if (flow.isCost()) return true;
        }
        return false;
    }

    void replaceFlows(List<Flow> next) {
        flows = next == null ? new ArrayList<>() : new ArrayList<>(next);
        recalculate();
    }

    /**
     * Resolve an automatic charge-load Review row as a neutral transfer after a same-target
     * measured Check confirms its component quantities.
     */

    /**
     * Stamps split provenance on explanation/reason without changing the correction enum (partial
     * keep stays counted).
     */
    void stampSplit(String reason, long now) {
        String mark = reason == null ? ItemSplits.SPLIT_SHARE_MARK : reason.trim();
        if (mark.isEmpty()) mark = ItemSplits.SPLIT_SHARE_MARK;
        correctionReason = normalizeReason(mark);
        String current = getExplanation();
        if (!ItemSplits.isSplitReason(current)) {
            explanation = current.isEmpty() ? mark : current + " — " + mark;
        } else if (!current.contains(mark)) {
            explanation = current + " — " + mark;
        }
    }

    void setExplanation(String next) {
        explanation = orEmpty(next);
    }

    /** Total gained ({@code gains}) or lost quantity of one item across the remaining flows. */
    long quantity(int id, boolean income) {
        long total = 0;
        for (Flow flow : flows) {
            if (flow.itemId == id && (income ? flow.isGain() : flow.isCost())) {
                total = safeAdd(total, abs(flow.quantityDelta));
            }
        }
        return total;
    }

    void assignEncounter(String newEncounterId, boolean pkSupply) {
        encounterId = newEncounterId;
        if (pkSupply && autoType() == CONSUMPTION) {
            type = PK_SUPPLY_COST;
            activityName = "PKing";
            confidence = Confidence.LIKELY;
            explanation = msg("supply-loss-occurred");
        }
    }

    long gross() {
        return safeAdd(revenue, costs);
    }

    String getId() {
        if (empty(id)) id = uuid();
        return id;
    }

    TransactionType autoType() {
        return type == null ? ADJUSTMENT : type;
    }

    TransactionType getType() {
        switch (getCorrection()) {
            case REVENUE:
                return GAIN;
            case COST:
                return CONSUMPTION;
            case TRANSFER:
                return TransactionType.TRANSFER;
            case IGNORE:
                return ADJUSTMENT;
            default:
                return autoType();
        }
    }

    Context getContext() {
        return context == null ? Context.GENERIC : context;
    }

    String getNote() {
        return orEmpty(note);
    }

    /** Presentation-only audit-row note update; accounting fields are untouched. */
    String getActivityName() {
        if (!blank(activityName)) return activityName.trim();
        String safeNote = getNote(), prefix = "Loot from ";
        return safeNote.startsWith(prefix) && safeNote.length() > prefix.length()
                ? safeNote.substring(prefix.length()).trim() : "General";
    }

    boolean isCounted() {
        var safeCorrection = getCorrection();
        return safeCorrection == REVENUE || safeCorrection == COST
                || (safeCorrection == AUTO && counted);
    }

    /** Raw automatic counted flag, before any correction makes the row count. */
    boolean autoCounted() {
        return counted;
    }

    List<Flow> getFlows() {
        return unmodifiableList(flows);
    }

    /** A loaded row is cleaned once (no missing or null flows), so readers never re-check. */
    void sanitize() {
        if (flows == null) flows = new ArrayList<>();
        flows.removeIf(Objects::isNull);
    }

    long autoNet() {
        return safeSubtract(revenue, costs);
    }

    long getRevenue() {
        return correctedAmount(REVENUE, revenue);
    }

    long getCosts() {
        return correctedAmount(COST, costs);
    }

    long correctedAmount(Correction forced, long automatic) {
        var current = getCorrection();
        return current == AUTO ? automatic : current == forced ? gross() : 0;
    }

    long getNet() {
        return safeSubtract(getRevenue(), getCosts());
    }

    /**
     * Personal-Net contribution this row would have under {@code hypothetical} (0 when it would not
     * count).
     */
    long netUnder(Correction hypothetical) {
        switch (hypothetical == null ? AUTO : hypothetical) {
            case REVENUE:
                return gross();
            case COST:
                return safeSubtract(0, gross());
            case TRANSFER:
            case IGNORE:
                return 0;
            default:
                return counted ? safeSubtract(revenue, costs) : 0;
        }
    }

    Confidence getConfidence() {
        return confidence == null ? Confidence.UNCERTAIN : confidence;
    }

    String getExplanation() {
        return orEmpty(explanation);
    }

    Correction getCorrection() {
        return correction == null ? AUTO : correction;
    }

    /** Automatic transfers and an owner's Transfer correction share one neutral projection. */
    boolean isTransfer() {
        return getType() == TransactionType.TRANSFER || getCorrection() == Correction.TRANSFER;
    }

    String correctionReason() {
        return nonBlank(correctionReason, "");
    }

    String getEncounterId() {
        return orEmpty(encounterId);
    }

    String sourceClaimId() {
        return orEmpty(sourceClaimId);
    }

    /**
     * Links this settlement to the claim it resolved; set once, in the same revision the claim is
     * removed.
     */
    void setSourceClaimId(String id) {
        sourceClaimId = nonBlank(id, null);
    }

    /** Observed action behind this change, or null when no honest verb was evidenced. */
    ActionKind getActionKind() {
        return fromWireName(actionKind);
    }

    /** Presentation-only stamp; never alters type, valuation, counted state or ownership. */
    void setActionKind(ActionKind kind) {
        actionKind = kind == null ? null : kind.wireName();
    }

    /** Exact action text captured from a trusted client widget, or null for generic/legacy rows. */
    ActionLabel actionLabel() {
        return ActionLabel.of(labelWire());
    }

    /**
     * Execution identity wins. Older receipts retain their rune/menu fallback; "" when neither
     * identifies a spell. The derived name is never stored.
     */
    String spellName() {
        return memo(7, () -> {
                    String observed = getActionKind() == CAST && Spells.ICONS.containsKey(observedSpellName)
                            ? observedSpellName : null;
                    if (observed != null) return observed;
                    // Runes that identify a spell win over a click queued behind an autocast (owner
                    // 2026-09-28);
                    // otherwise the clicked name stands (a staff-covered or non-combat spell).
                    String spell = getActionKind() == CAST ? Spells.spellFor(getFlows()) : null;
                    var label = actionLabel();
                    return spell != null ? spell : label == null ? "" : label.value();
                });
    }

    /** Derived-value memo (release pass): every refresh reads each receipt; never stored. */
    transient Object[] memo;

    /**
     * Slots 6+ hold derived values; the array is replaced as soon as flows, correction, type,
     * counted, action or label change (each is replaced, never edited in place).
     */
    @SuppressWarnings("unchecked")
    <T> T memo(int slot, java.util.function.Supplier<T> build) {
        Object[] cache = memo;
        if (cache == null || cache[0] != flows || cache[1] != correction || cache[2] != type
                || cache[3] != (Boolean) counted || cache[4] != actionKind || cache[5] != observedActionLabel
                || cache[11] != observedSpellName) {
            cache = new Object[] {
                        flows, correction, type, counted, actionKind, observedActionLabel, null, null, null, null, null,
                        observedSpellName
                    };
            memo = cache;
        }
        if (cache[slot] == null) cache[slot] = build.get();
        return (T) cache[slot];
    }

    /** Presentation only. Stores only the normalized bounded label text. */
    void setActionLabel(ActionLabel label) {
        observedActionLabel = label == null ? null : label.value();
    }

    /**
     * The only accepted label value, revalidated on every read: bounded, sanitized, non-generic and
     * attached to a CAST or DRINK transaction. Oversized, markup, control-character or generic
     * saved text reads as no label, so a damaged save degrades to generic wording instead of
     * failing.
     */
    String labelWire() {
        if (empty(observedActionLabel)) return null;
        if (getActionKind() != CAST && getActionKind() != DRINK) return null;
        var normalized = ActionLabel.of(observedActionLabel);
        return normalized == null ? null : normalized.value();
    }

    static String normalizeReason(String value) {
        if (blank(value)) return "Manual correction";
        String text = value.trim();
        return text.length() > 200 ? text.substring(0, 200) : text;
    }
}

@AllArgsConstructor
enum Context {
    GENERIC(0), PRODUCTION(1), LOOT(2), PK_LOOT(3), MARKET(4), PK_DEATH(5), TRANSFER(6);
    @Getter int priority;
}

enum TransactionType {
    LOOT, CONSUMPTION, PROCESSING, TRADE, TRANSFER, GAIN, ADJUSTMENT, UNCERTAIN, PK_LOOT, PK_SUPPLY_COST, PK_DEATH_LOSS
}

/**
 * Personal item-split provenance helpers. Splits adjust personal Net share via the correction
 * timeline — never Party settlement, never fake Used eats.
 */
class ItemSplits {
    static final String SPLIT_SHARE_MARK = "Split share", SPLIT_KEEP_PREFIX = "Split keep ";

    static String reason(long keepQuantity, long quantity, String note) {
        long total = nonNeg(quantity), keep = nonNeg(min(keepQuantity, total));
        var sb = new StringBuilder(SPLIT_KEEP_PREFIX).append(keep).append('/').append(total).append(" · ")
                        .append(SPLIT_SHARE_MARK);
        if (note != null) {
            String text = note.trim();
            if (!text.isEmpty())
                sb.append(" · ").append(text.length() > 80 ? text.substring(0, 80) : text);
        }
        String value = sb.toString();
        return value.length() > 200 ? value.substring(0, 200) : value;
    }

    static final Map<String, Boolean> SPLIT_TEXT = new java.util.concurrent.ConcurrentHashMap<>();

    static boolean isSplitReason(String reasonOrExplanation) {
        if (empty(reasonOrExplanation)) return false;
        // Receipt texts are shared strings read on every tick: decide each distinct text once.
        if (SPLIT_TEXT.size() > 10_000) SPLIT_TEXT.clear();
        return SPLIT_TEXT.computeIfAbsent(reasonOrExplanation, text -> has(
                                text.toLowerCase(ROOT), "split keep", "split share"));
    }
}

enum Correction {
    AUTO, REVENUE, COST, TRANSFER, IGNORE
}

enum Confidence {
    CONFIRMED, LIKELY, UNCERTAIN
}

/** A deliberate correction choice for an unresolved transaction. */
@AllArgsConstructor
enum Decision {
    GAIN(REVENUE), COST(Correction.COST), TRANSFER(Correction.TRANSFER), IGNORE(Correction.IGNORE);
    Correction correction;

    Correction toCorrection() {
        return correction;
    }
}

/** Shared accounting predicate for receipts that still need an owner decision. */
class ReviewRules {
    /**
     * Only unresolved automatic classifier decisions belong in the decision inbox. Ledger may
     * separately surface unknown pricing or provenance as review hints.
     */
    static boolean needsOwnerDecision(Transaction transaction) {
        return transaction != null && transaction.getCorrection() == AUTO
                && transaction.getConfidence() == Confidence.UNCERTAIN;
    }
}

/** One undoable owner decision: the before/after state of every row it changed. */
class CorrectionRecord extends SaveCopies {
    String reason;
    List<Change> changes;

    /** Set once the decision has been undone; an undone record is skipped by later undos. */
    boolean undone;

    CorrectionRecord() {
        // Gson
    }

    CorrectionRecord(String reason, List<Change> changes) {
        this.reason = Transaction.normalizeReason(reason);
        this.changes = new ArrayList<>(changes);
    }

    String getReason() {
        return Transaction.normalizeReason(reason);
    }

    boolean isUndone() {
        return undone;
    }

    /** The rows this decision changed. */
    List<Change> getChanges() {
        return changes == null ? emptyList() : unmodifiableList(changes);
    }

    /** True when any change in this record targets one of the given transaction ids. */
    boolean touchesAny(Set<String> ids) {
        for (Change change : getChanges()) {
            if (ids.contains(change.getTransactionId())) return true;
        }
        return false;
    }

    /**
     * One row's before/after state. The flow and explanation snapshots are set when the decision
     * changed the row's flows (split, own-drop recovery); flows are immutable, so they are shared.
     */
    @NoArgsConstructor
    @AllArgsConstructor
    static class Change extends SaveCopies {
        String transactionId;
        Correction previousCorrection, newCorrection;
        List<Flow> flowSnapshot;
        String explanationSnapshot;

        String getTransactionId() {
            return orEmpty(transactionId);
        }

        Correction getPreviousCorrection() {
            return previousCorrection == null ? AUTO : previousCorrection;
        }

        List<Flow> getFlowSnapshot() {
            return flowSnapshot == null ? Collections.<Flow>emptyList() : unmodifiableList(flowSnapshot);
        }

        boolean hasFlowSnapshot() {
            return flowSnapshot != null;
        }
    }
}

@EqualsAndHashCode(exclude = "priceCapturedAtEpochMillis", callSuper = false)
class Flow extends SaveCopies {
    int itemId;
    String itemName;
    long quantityDelta;
    int unitPrice;
    long valueDelta;
    PriceSource priceSource;

    /** Epoch-millis when the selected non-zero unit price was observed; zero when unavailable. */
    long priceCapturedAtEpochMillis;

    Flow() {
        // Gson
    }

    Flow(int id, String name, long delta, int price, long value) {
        this(id, name, delta, price, value, UNKNOWN);
    }

    Flow(int id, String name, long delta, int price, long value, PriceSource source) {
        this(id, name, delta, price, value, source, 0);
    }

    Flow(int id, String name, long delta, int price, long value, PriceSource source,
            long capturedAt) {
        itemId = id;
        itemName = name;
        quantityDelta = delta;
        unitPrice = price;
        valueDelta = value;
        priceSource = source == null ? UNKNOWN : source;
        priceCapturedAtEpochMillis = capturedAt;
    }

    PriceSource getPriceSource() {
        return priceSource == null ? UNKNOWN : priceSource;
    }

    long getPriceCapturedAtEpochMillis() {
        return nonNeg(priceCapturedAtEpochMillis);
    }

    /** {@code magnitude} units of this flow, signed like it, valued at {@code unit} each. */
    Flow part(long size, long unit, PriceSource source) {
        long sign = quantityDelta < 0 ? -1L : 1L;
        return new Flow(itemId, itemName, sign * size, toInt(unit),
                sign * safeMultiply(size, unit), source, getPriceCapturedAtEpochMillis());
    }

    Flow part(long size) {
        return part(size, unitPrice, getPriceSource());
    }

    /**
     * What remains once {@code claimed} units leave at the unit price; null when nothing remains.
     */
    Flow rest(long claimed) {
        long left = abs(quantityDelta) - claimed;
        return claimed <= 0 ? this : left <= 0 ? null : new Flow(itemId, itemName, quantityDelta < 0 ? -left : left,
                                unitPrice, valueDelta - part(claimed).valueDelta, priceSource,
                                priceCapturedAtEpochMillis);
    }

    boolean isGain() {
        return quantityDelta > 0;
    }

    boolean isCost() {
        return quantityDelta < 0;
    }
}

/** Provenance of the unit price used for an item flow. */
@AllArgsConstructor
enum PriceSource {
    MANUAL_OVERRIDE("manual"),
    /** A catalogue proxy price: a market item's quote divided by its fixed exchange rate. */
    CURRENCY_PROXY("currency proxy"), GRAND_EXCHANGE("RuneLite market"), FACE_VALUE("face value"),
    /** High-alchemy value, for an ironman who chose to value items at their alch price. */
    HIGH_ALCHEMY("high alchemy"), DEFERRED_CLAIM("Claim on open"),
    /** Untradeable with no alch value: a known zero, never shown as an unknown price. */
    NO_VALUE("no value"), UNPRICED("unpriced"), UNKNOWN("unknown");
    String label;

    public String toString() {
        return label;
    }
}

/**
 * Bounded client-observed action text: durable presentation metadata since schema 105, never
 * financial.
 */
@AllArgsConstructor
class ActionLabel {
    static final int MAX_LENGTH = 64;
    String value;

    /**
     * Accept a name only from a Cast interaction whose associated widget belongs to the spellbook.
     * The menu target is deliberately absent from this API: NPC/player/item names cannot become
     * spell labels.
     *
     * <p>Used for both the direct spellbook click and the selected spell widget that RuneLite still
     * exposes during the following widget-target interaction.
     */
    static ActionLabel fromSpellMenu(String menuOption, int widget, int spellbookGroupId, String widgetText,
            String widgetName) {
        String option = clean(menuOption);
        if (!option.toLowerCase(ROOT).startsWith("cast")
                || ((widget >>> 16) & 0xffff) != (spellbookGroupId & 0xffff)) {
            return null;
        }
        String text = useful(clean(widgetText));
        if (text != null) return new ActionLabel(text);
        String name = useful(clean(widgetName));
        if (name != null) return new ActionLabel(name);
        // Some menu entries expose the exact action text directly, e.g. "Cast Ice Burst".
        // Do not inspect menuTarget: it names the affected actor or item.
        if (option.length() > 4 && option.regionMatches(true, 0, "Cast ", 0, 5)) {
            String direct = useful(clean(option.substring(5)));
            if (direct != null) return new ActionLabel(direct);
        }
        return null;
    }

    /** True when the widget id's interface group is the spellbook group. */
    static ActionLabel of(String text) {
        String normalized = useful(clean(text));
        return normalized == null ? null : new ActionLabel(normalized);
    }

    String value() {
        return value;
    }

    static String clean(String text) {
        if (text == null) return "";
        String withoutMarkup = text.replaceAll("<[^>]*>", " ").replaceAll("@[a-zA-Z0-9]{3,6}@", " ")
                        .replace("&nbsp;", " ").replace("&#160;", " ");
        return withoutMarkup.replaceAll("\\s+", " ").trim();
    }

    static String useful(String text) {
        if (text.isEmpty() || text.length() > MAX_LENGTH) return null;
        if ("cast".equalsIgnoreCase(text) || "autocast".equalsIgnoreCase(text)
                || "cancel".equalsIgnoreCase(text)) return null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != ' ' && c != '\'' && c != '-' && c != ',')
                return null;
        }
        return text;
    }

    public String toString() {
        return value;
    }
}

/**
 * Observed player action behind a settled inventory change. Presentation-only evidence: it names
 * what the player did (drank, ate, decanted, cast) so HUD/receipts/Live/Ledger can use honest
 * wording. It never changes {@link TransactionType}, valuation, counted state or session ownership.
 *
 * <p>Persisted on {@link Transaction} by wire name; absent on rows saved before B10 and on rows
 * whose evidence never supported a specific verb. Unsupported evidence must not acquire a more
 * confident verb — callers fall back to {@link #SUPPLIES} or a generic label rather than guessing.
 */
enum ActionKind {
    /** Drink intent plus a settled dose/vial decrease. */
    DRINK,
    /** Eat intent plus settled food loss (partial foods use the observed portion). */
    EAT,
    /** Herblore production: ingredients in, potion out. Not every paired transform. */
    MIX,
    /** Dose-conserving redistribution (3+1 → 4). Never implies doses were drunk. */
    DECANT, BURY, OFFER, SCATTER,
    /** Supported rune consumption. Spell name only when evidenced. Ledger detail only. */
    CAST,
    /** Supported ammunition consumption. Ledger detail only. */
    FIRE,
    /** Untradeable key audit rows are inspectable in Ledger only. */
    DEFERRED_CLAIM,
    /** Ambiguous charge-load settlements are explanatory Ledger notes only. */
    CHARGE_LOAD_AMBIGUOUS, COOK, BURN,
    /** Settled supply loss whose specific action is not evidenced. */
    SUPPLIES,
    /** A bank-paid fee row (the retired boat recovery): one action row named by its own note. */
    FEE;

    /** The constant's name, lowercased with hyphens: the wire name saved on every row. */
    String wireName = name().toLowerCase(ROOT).replace('_', '-');

    /** Stable persisted identifier. */
    String wireName() {
        return wireName;
    }

    static ActionKind fromWireName(String value) {
        return blank(value) ? null : BY_WIRE.get(lower(value));
    }

    /** Wire and constant names, lowercased, read on every getActionKind() (a hot path). */
    static final Map<String, ActionKind> BY_WIRE = new HashMap<>();

    static {
        for (var kind : values()) {
            BY_WIRE.put(kind.wireName, kind);
            BY_WIRE.put(kind.name().toLowerCase(ROOT), kind);
        }
    }
}

/**
 * Combat spells by exact rune cost (spells.tsv). Autocasting never clicks a spell, so a Cast with
 * no captured name reads as the one spell its runes pay for (owner 2026-09-28). A staff, tome or
 * combination rune may cover elementals; when two spells could fit, it stays a plain Cast.
 */
class Spells {
    static final Map<Integer, String> CAST_ANIMATIONS = byId("cast_animations");
    static final Map<String, String> ICONS = byName("spells"), COMBINATIONS = byName("combination_runes");
    static final String ELEMENTS = " air water earth fire ";


    /**
     * The spell these rune costs pay for (a whole number of casts), or null. When staff-covered
     * elementals let several fit, the one paid in full wins; if none or several are, it stays a
     * Cast.
     */

    /**
     * The spell's spellbook icon as a negated sprite id (the sidebar's sprite lookup reads it),
     * else -1.
     */
    static final Map<String, String> BY_LOWER = new HashMap<>();

    static {
        for (String name : ICONS.keySet()) BY_LOWER.put(name.toLowerCase(ROOT), name);
    }

    /** The item ids a spell makes (an alch's coins, Bones to Peaches' fruit); empty for others. */
    static Set<Integer> product(String name) {
        for (String[] row : cachedRows("spells")) {
            if (row[0].equals(name) && row.length > 2) {
                var ids = new HashSet<Integer>();
                for (String id : row[2].split(",")) ids.add(Integer.parseInt(id));
                return ids;
            }
        }
        return emptySet();
    }

    /** The spell a name spells out, ignoring case (a "Bones to peaches" tablet), else null. */
    static String named(String name) {
        return name == null ? null : BY_LOWER.get(name.toLowerCase(ROOT).trim());
    }

    static int icon(String name) {
        // d19 holds a spell's sprite, or minus an item id for a row named by its item (the barrel).
        String icon = ICONS.get(name);
        return icon == null ? -1 : -Integer.parseInt(icon);
    }

    /** 0 when these costs cannot be this spell, 1 when they fit it, 2 when they pay it in full. */

    /** Rune recipes are parsed once; fitting arithmetic and HashMap iteration stay unchanged. */
    static final Map<String, Map<String, Long>> RECIPES = new LinkedHashMap<>();

    static {
        for (String[] row : cachedRows("spell_recipes")) {
            var recipe = new HashMap<String, Long>();
            for (String part : row[1].split(" ")) {
                int colon = part.indexOf(':');
                recipe.put(part.substring(colon + 1), Long.parseLong(part.substring(0, colon)));
            }
            RECIPES.put(row[0], unmodifiableMap(recipe));
        }
    }

    static String spellFor(List<Flow> flows) {
        String fitting = null, full = null;
        int fits = 0, fulls = 0;
        for (var spell : RECIPES.entrySet()) {
            int fit = fit(spell.getValue(), flows);
            if (fit > 0) {
                fits++;
                fitting = spell.getKey();
            }
            if (fit > 1) {
                fulls++;
                full = spell.getKey();
            }
        }
        return fits == 1 ? fitting : fulls == 1 ? full : null;
    }

    static boolean elemental(String name) {
        String resource = lower(name).replace(" rune", "");
        return ELEMENTS.contains(" " + resource + " ") || COMBINATIONS.containsKey(resource);
    }

    static int fit(Map<String, Long> recipe, List<Flow> flows) {
        var paid = new HashMap<String, Long>();
        for (Flow flow : flows) {
            String name = flow.itemName.toLowerCase(ROOT);
            if (!name.endsWith(" rune") || flow.quantityDelta >= 0) return 0;
            String rune = name.substring(0, name.length() - 5);
            boolean used = false;
            for (String element : COMBINATIONS.getOrDefault(rune, rune).split(" ")) {
                // A combination rune pays only the elements this spell uses (steam is water for Ice
                // Barrage).
                if (recipe.containsKey(element) || !COMBINATIONS.containsKey(rune)) {
                    paid.merge(element, -flow.quantityDelta, Long::sum);
                    used = true;
                }
            }
            if (!used) return 0;
        }
        long casts = 0;
        for (var resource : recipe.entrySet()) {
            if (!ELEMENTS.contains(" " + resource.getKey() + " ")) {
                long got = paid.getOrDefault(resource.getKey(), 0L);
                if (got == 0 || got % resource.getValue() != 0 || casts != 0 && got / resource.getValue() != casts) return 0;
                casts = got / resource.getValue();
            }
        }
        boolean full = true;
        for (var rune : recipe.entrySet()) {
            long got = paid.getOrDefault(rune.getKey(), 0L);
            // An elemental is paid in full or supplied for free. A partial amount can be a rune
            // pickup netting against a different spell's spend; it cannot prove this spell.
            if (got != 0 && got != casts * rune.getValue()) return 0;
            full &= got == casts * rune.getValue();
        }
        return paid.keySet().stream().allMatch(recipe::containsKey) ? full ? 2 : 1 : 0;
    }
}

/** Shared correction-aware projection for accounting read models and exports. */
class Accounting {
    static Amounts transaction(Transaction transaction) {
        if (transaction == null) return Amounts.unavailable();
        return transaction.memo(8, () -> amounts(transaction));
    }

    static Amounts amounts(Transaction transaction) {
        if (transaction.isTransfer()) {
            return Amounts.transfer();
        }
        if (!transaction.isCounted()) return Amounts.excluded();
        var flows = transaction.getFlows();
        if (empty(flows)) {
            return Amounts.included(transaction.getRevenue(), transaction.getCosts());
        }
        long gross = 0;
        boolean included = false;
        for (Flow flow : flows) {
            if (flow == null) continue;
            included = true;
            gross = safeAdd(gross, abs(flow.valueDelta));
        }
        if (!included) return Amounts.excluded();
        switch (transaction.getCorrection()) {
            case REVENUE:
                return Amounts.included(gross, 0);
            case COST:
                return Amounts.included(0, gross);
            default:
                long[] steps = cachedSteps(transaction);
                long step = 0;
                for (int i = 0; i < flows.size(); i++) {
                    if (flows.get(i) != null && flows.get(i).valueDelta > 0)
                        step = safeAdd(step, steps[i]);
                }
                step = min(step, min(transaction.getRevenue(), transaction.getCosts()));
                return Amounts.included(safeSubtract(transaction.getRevenue(), step),
                        safeSubtract(transaction.getCosts(), step));
        }
    }

    /**
     * Owner 2026-09-28: a sip or bite steps an item down ((4) to (3), pie to half pie). The part
     * left is no gain and the whole potion no cost; only the step is, as the Ledger pairs it. Per
     * flow, the value both legs of each such pair give up (0 elsewhere, and always 0 under a
     * correction).
     */
    static long[] cachedSteps(Transaction transaction) {
        return transaction.memo(6, () -> steps(transaction));
    }

    static long[] steps(Transaction transaction) {
        var flows = transaction.getFlows();
        long[] steps = new long[flows.size()];
        for (var pair : consumePairs(transaction)) {
            long left = flows.get(pair.remainderIndex).valueDelta;
            if (left > 0 && safeAdd(flows.get(pair.lossIndex).valueDelta, left) <= 0) {
                steps[pair.lossIndex] = left;
                steps[pair.remainderIndex] = left;
            }
        }
        return steps;
    }

    /** One consume gate for accounting and item presentation; transfers/decants keep raw quotes. */
    static List<PartialUse> consumePairs(Transaction transaction) {
        var flows = transaction.getFlows();
        var action = transaction.getActionKind();
        // Cheapest facts first: this runs for every flow of every receipt on each refresh (release
        // pass).
        if (!transaction.isCounted() || transaction.getCorrection() != AUTO
                || transaction.getType() != CONSUMPTION
                        && transaction.getType() != PK_SUPPLY_COST || action != null
                        && action != DRINK && action != EAT && action != ActionKind.SUPPLIES
                || ActionReader.isDecant(flows)) return emptyList();
        var pairs = new ArrayList<PartialUse>();
        for (var pair : ActionReader.partialUses(flows)) {
            if (flows.get(pair.lossIndex).getPriceSource()
                    == flows.get(pair.remainderIndex).getPriceSource()) pairs.add(pair);
        }
        return pairs;
    }

    /** Returns the additive accounting contribution of a single item-flow row. */
    static Amounts flow(Transaction transaction, Flow flow) {
        if (transaction == null || flow == null) return Amounts.unavailable();
        if (transaction.isTransfer()) {
            return Amounts.transfer();
        }
        if (!transaction.isCounted()) {
            return Amounts.excluded();
        }
        long value = flow.valueDelta, autoRevenue = value > 0 ? value : 0, autoCosts = value < 0 ? abs(value) : 0;
        switch (transaction.getCorrection()) {
            case REVENUE:
                return Amounts.included(abs(value), 0);
            case COST:
                return Amounts.included(0, abs(value));
            default:
                var flows = transaction.getFlows();
                long step = 0;
                for (int i = 0; i < flows.size(); i++) {
                    if (flows.get(i) == flow) step = cachedSteps(transaction)[i];
                }
                return Amounts.included(safeSubtract(autoRevenue, value > 0 ? step : 0),
                        safeSubtract(autoCosts, value < 0 ? step : 0));
        }
    }

    /** Correction-aware supplies/other split shared by every retained money fold. */
    static CostSplit costSplit(Transaction transaction) {
        return transaction.memo(9, () -> split(transaction));
    }

    static CostSplit split(Transaction transaction) {
        var total = transaction(transaction);
        if (!total.available) return new CostSplit(0L, 0L, false);
        if (!total.included || total.costs <= 0) return new CostSplit(0L, 0L, true);
        if (transaction.getFlows().isEmpty()) return new CostSplit(0L, 0L, false);
        long supplies = 0, other = 0;
        for (Flow flow : transaction.getFlows()) {
            if (flow == null) continue;
            var money = flow(transaction, flow);
            if (!money.available) return new CostSplit(0L, 0L, false);
            if (!money.included || money.costs <= 0) continue;
            switch (CostKind.of(transaction, flow)) {
                case SUPPLIES:
                    supplies = safeAdd(supplies, money.costs);
                    break;
                case LOSS:
                case MARKET:
                    other = safeAdd(other, money.costs);
                    break;
                default:
                    return new CostSplit(0L, 0L, false);
            }
        }
        return new CostSplit(supplies, other, safeAdd(supplies, other) == total.costs);
    }

    /**
     * The correction-aware fold of the counted Market-context population. Market membership is the
     * authoritative typed predicate {@code automaticType == TRADE}; corrections change effective
     * amounts but never membership. Whole transactions are folded here so callers can remove their
     * gross legs from ordinary categories without re-deriving economics.
     */
    static MarketFold marketFold(List<Transaction> transactions) {
        long income = 0, cost = 0, supplies = 0, other = 0;
        boolean splitAvailable = true;
        int included = 0;
        if (transactions == null) return new MarketFold(0L, 0L, 0L, 0L, true, 0);
        for (var transaction : transactions) {
            if (transaction == null || transaction.autoType() != TRADE) continue;
            var money = transaction(transaction);
            if (!money.included) continue;
            included++;
            income = safeAdd(income, money.revenue);
            cost = safeAdd(cost, money.costs);
            var split = costSplit(transaction);
            if (!split.available) {
                splitAvailable = false;
                continue;
            }
            supplies = safeAdd(supplies, split.supplies);
            other = safeAdd(other, split.other);
        }
        return new MarketFold(income, cost, splitAvailable ? supplies : 0, splitAvailable ? other : 0,
                splitAvailable, included);
    }

    @AllArgsConstructor
    static class MarketFold {
        long revenue, costs, supplies, other;
        boolean splitAvailable;

        /** Counted Market-context transactions behind this fold. */
        int transactions;

        long getNet() {
            return safeSubtract(revenue, costs);
        }
    }

    @AllArgsConstructor
    static class Amounts {
        boolean available, included, transfer;
        long revenue, costs;

        static Amounts included(long income, long cost) {
            return new Amounts(true, true, false, income, cost);
        }

        static Amounts excluded() {
            return new Amounts(true, false, false, 0L, 0L);
        }

        static Amounts transfer() {
            return new Amounts(true, false, true, 0L, 0L);
        }

        static Amounts unavailable() {
            return new Amounts(false, false, false, 0L, 0L);
        }

        long getNet() {
            return safeSubtract(revenue, costs);
        }
    }

    @AllArgsConstructor
    static class CostSplit {
        long supplies, other;
        boolean available;
    }
}

/**
 * Model-layer classification for one effective counted item cost.
 *
 * <p>The inputs intentionally match the Ledger's item contribution boundary: a transaction plus one
 * flow. This keeps classification independent of Swing and allows every read model to apply the
 * same rule after compaction.
 *
 * <p>The category is selected by typed canonical data only: the synthetic tax flow id, the
 * transaction type and the observed action kind. Item names, activity labels, notes, explanations
 * and owner correction reasons are presentation; renaming any of them can never change the booked
 * category.
 */
enum CostKind {
    NONE, SUPPLIES, LOSS,
    /** Trade costs are market movement and remain inside the non-supplies total. */
    MARKET;

    /**
     * Historical synthetic GE sell-tax flow id. New booking stopped in Band 3 C2; the constant
     * stays so saved history keeps its typed loss category and the Ledger tax filter still reads.
     */
    static final int GE_TAX_ITEM_ID = -99502;

    /**
     * Classifies only an effective cost after counted/correction semantics. Non-cost, ignored,
     * transfer, uncounted and deferred claim flows return {@link #NONE}.
     */
    static CostKind of(Transaction transaction, Flow flow) {
        if (transaction == null || flow == null || transaction.getActionKind() == ActionKind.DEFERRED_CLAIM) {
            return NONE;
        }
        var effective = Accounting.flow(transaction, flow);
        if (!effective.available || !effective.included || effective.costs <= 0) return NONE;
        if (flow.itemId == GE_TAX_ITEM_ID || transaction.autoType() == PK_DEATH_LOSS) {
            return LOSS;
        }
        if (transaction.autoType() == TRADE) return MARKET;
        var type = transaction.autoType();
        // Supplies are consumables the player used (eat / drink / cast / fire / charges /
        // processing
        // inputs), which the engine evidences with an action kind or a supply-shaped type. A plain
        // "value decreased" consumption with no evidence is an item gone, i.e. a loss.
        // A production run's input is a supply even when that action made nothing (a failed smelt).
        if (type == PK_SUPPLY_COST || type == PROCESSING || transaction.getActionKind() != null
                || transaction.getContext() == Context.PRODUCTION) {
            return SUPPLIES;
        }
        return LOSS;
    }
}
