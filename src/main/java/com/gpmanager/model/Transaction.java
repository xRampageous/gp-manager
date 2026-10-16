package com.gpmanager;

import static com.gpmanager.ActionKind.*;
import static com.gpmanager.Correction.*;
import static com.gpmanager.GameData.msg;
import static com.gpmanager.Phrases.*;
import static com.gpmanager.PriceSource.*;
import static com.gpmanager.SafeMath.*;
import static com.gpmanager.TransactionType.*;
import static java.lang.Math.min;
import static java.util.Collections.*;

import java.util.*;
import lombok.*;
import lombok.AllArgsConstructor;

/**
 * Canonical durable financial fact: stable id, timestamp, type/context, booked item flows, the
 * automatic counted state, confidence, correction and split state, and optional links to a PvP
 * encounter or the pending claim it settled. Booked values are never repriced.
 */
class Transaction {
    String id;
    long timestampEpochMillis;
    TransactionType type;
    Context context;
    String note;
    String activityName;
    boolean counted;
    List<Flow> flows;
    long revenue;
    long costs;
    Confidence confidence;
    String explanation;
    Correction correction;
    String correctionReason;
    String encounterId;

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

    /** Transient icon hint for an estimated charge cast: the weapon's item id; never saved. */
    transient int castItemId;

    Transaction() {
        // Gson
    }

    Transaction(
            long timestampEpochMillis,
            TransactionType type,
            Context context,
            String note,
            String activityName,
            boolean counted,
            List<Flow> flows,
            Confidence confidence,
            String explanation,
            String encounterId) {
        id = UUID.randomUUID().toString();
        this.timestampEpochMillis = timestampEpochMillis;
        this.type = type;
        this.context = context;
        this.note = orEmpty(note);
        this.activityName = normalizeActivity(activityName);
        this.counted = counted;
        this.flows = flows == null ? new ArrayList<>() : new ArrayList<>(flows);
        this.confidence = confidence == null ? Confidence.UNCERTAIN : confidence;
        this.explanation = orEmpty(explanation);
        correction = Correction.AUTO;
        correctionReason = "";
        this.encounterId = encounterId;
        recalculate();
    }

    void recalculate() {
        revenue = 0L;
        costs = 0L;
        for (Flow flow : flows) {
            if (flow.valueDelta > 0) revenue = safeAdd(revenue, flow.valueDelta);
            else if (flow.valueDelta < 0) costs = safeAdd(costs, abs(flow.valueDelta));
        }
    }

    void applyCorrection(Correction newCorrection, long now) {
        applyCorrection(newCorrection, now, "Manual correction");
    }

    void applyCorrection(Correction newCorrection, long now, String reason) {
        correction = newCorrection == null ? Correction.AUTO : newCorrection;
        correctionReason = normalizeReason(reason);
    }

    /**
     * Takes {@code amount} of one item's gains (newest flow first) or costs (oldest first) out of
     * the flows, each remainder keeping its own unit value, and returns the quantity left.
     */
    long takeQuantity(int itemId, boolean gains, long amount) {
        var ordered = new ArrayList<Flow>(getFlows());
        if (gains) reverse(ordered);
        var next = new ArrayList<Flow>();
        for (Flow flow : ordered) {
            long magnitude = abs(flow.quantityDelta);
            long take =
                    flow.itemId == itemId && (gains ? flow.isGain() : flow.isCost())
                            ? Math.min(nonNeg(amount), magnitude)
                            : 0L;
            amount -= take;
            long left = magnitude - take;
            if (take <= 0L) {
                next.add(flow);
            } else if (left > 0L) {
                long unit = flow.unitPrice;
                long signed = gains ? left : -left;
                next.add(
                        new Flow(
                                itemId,
                                flow.itemName,
                                signed,
                                flow.unitPrice,
                                unit == 0L ? flow.valueDelta / magnitude * left : signed * unit,
                                flow.getPriceSource(),
                                flow.getPriceCapturedAtEpochMillis()));
            }
        }
        if (gains) reverse(next);
        replaceFlows(next);
        return quantity(itemId, gains);
    }

    /** True while any flow still spends an item; a blowpipe shot keeps its scales after a dart. */
    boolean spends() {
        for (Flow flow : getFlows()) {
            if (flow.isCost()) return true;
        }
        return false;
    }

    void replaceFlows(List<Flow> nextFlows) {
        flows = nextFlows == null ? new ArrayList<>() : new ArrayList<>(nextFlows);
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
    long quantity(int itemId, boolean gains) {
        long total = 0L;
        for (Flow flow : flows) {
            if (flow.itemId == itemId && (gains ? flow.isGain() : flow.isCost())) {
                total = safeAdd(total, abs(flow.quantityDelta));
            }
        }
        return total;
    }

    void assignEncounter(String newEncounterId, boolean asPkSupplyCost) {
        encounterId = newEncounterId;
        if (asPkSupplyCost && autoType() == CONSUMPTION) {
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
        if (empty(id)) id = UUID.randomUUID().toString();
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
            case AUTO:
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
        String safeNote = getNote();
        String prefix = "Loot from ";
        return safeNote.startsWith(prefix) && safeNote.length() > prefix.length()
                ? safeNote.substring(prefix.length()).trim()
                : "General";
    }

    boolean isCounted() {
        Correction safeCorrection = getCorrection();
        return safeCorrection == Correction.REVENUE
                || safeCorrection == Correction.COST
                || (safeCorrection == Correction.AUTO && counted);
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
        flows.removeIf(java.util.Objects::isNull);
    }

    long autoNet() {
        return safeSubtract(revenue, costs);
    }

    long getRevenue() {
        switch (getCorrection()) {
            case REVENUE:
                return gross();
            case COST:
            case TRANSFER:
            case IGNORE:
                return 0L;
            case AUTO:
            default:
                return revenue;
        }
    }

    long getCosts() {
        switch (getCorrection()) {
            case COST:
                return gross();
            case REVENUE:
            case TRANSFER:
            case IGNORE:
                return 0L;
            case AUTO:
            default:
                return costs;
        }
    }

    long getNet() {
        return safeSubtract(getRevenue(), getCosts());
    }

    /**
     * Personal-Net contribution this row would have under {@code hypothetical} (0 when it would not
     * count).
     */
    long netUnder(Correction hypothetical) {
        switch (hypothetical == null ? Correction.AUTO : hypothetical) {
            case REVENUE:
                return gross();
            case COST:
                return safeSubtract(0L, gross());
            case TRANSFER:
            case IGNORE:
                return 0L;
            case AUTO:
            default:
                return counted ? safeSubtract(revenue, costs) : 0L;
        }
    }

    Confidence getConfidence() {
        return confidence == null ? Confidence.UNCERTAIN : confidence;
    }

    String getExplanation() {
        return orEmpty(explanation);
    }

    Correction getCorrection() {
        return correction == null ? Correction.AUTO : correction;
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
    void setSourceClaimId(String claimId) {
        sourceClaimId = nonBlank(claimId, null);
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
     * A Cast's name for presentation: the captured spell, else (autocast never clicks one) the
     * spell its exact runes pay for; "" otherwise. Never stored.
     */
    String spellName() {
        return memo(
                7,
                () -> {
                    // Runes that identify a spell win over a click queued behind an autocast (owner
                    // 2026-09-28);
                    // otherwise the clicked name stands (a staff-covered or non-combat spell).
                    String spell = getActionKind() == CAST ? Spells.spellFor(getFlows()) : null;
                    ActionLabel label = actionLabel();
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
        if (cache == null
                || cache[0] != flows
                || cache[1] != correction
                || cache[2] != type
                || cache[3] != (Boolean) counted
                || cache[4] != actionKind
                || cache[5] != observedActionLabel) {
            cache =
                    new Object[] {
                        flows,
                        correction,
                        type,
                        counted,
                        actionKind,
                        observedActionLabel,
                        null,
                        null,
                        null,
                        null,
                        null
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
        if (getActionKind() != CAST && getActionKind() != ActionKind.DRINK) return null;
        ActionLabel normalized = ActionLabel.of(observedActionLabel);
        return normalized == null ? null : normalized.value();
    }

    static String normalizeReason(String value) {
        if (blank(value)) return "Manual correction";
        String trimmed = value.trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
    }
}

@AllArgsConstructor
enum Context {
    GENERIC(0),
    PRODUCTION(1),
    LOOT(2),
    PK_LOOT(3),
    MARKET(4),
    PK_DEATH(5),
    TRANSFER(6);
    @Getter int priority;
}

enum TransactionType {
    LOOT,
    CONSUMPTION,
    PROCESSING,
    TRADE,
    TRANSFER,
    GAIN,
    ADJUSTMENT,
    UNCERTAIN,
    PK_LOOT,
    PK_SUPPLY_COST,
    PK_DEATH_LOSS
}

/**
 * Personal item-split provenance helpers. Splits adjust personal Net share via the correction
 * timeline — never Party settlement, never fake Used eats.
 */
class ItemSplits {
    static final String SPLIT_SHARE_MARK = "Split share";
    static final String SPLIT_KEEP_PREFIX = "Split keep ";

    static String reason(long keepQuantity, long totalQuantity, String optionalNote) {
        long total = nonNeg(totalQuantity);
        long keep = nonNeg(min(keepQuantity, total));
        var sb =
                new StringBuilder(SPLIT_KEEP_PREFIX)
                        .append(keep)
                        .append('/')
                        .append(total)
                        .append(" · ")
                        .append(SPLIT_SHARE_MARK);
        if (optionalNote != null) {
            String trimmed = optionalNote.trim();
            if (!trimmed.isEmpty())
                sb.append(" · ").append(trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed);
        }
        String value = sb.toString();
        return value.length() > 200 ? value.substring(0, 200) : value;
    }

    static final java.util.Map<String, Boolean> SPLIT_TEXT =
            new java.util.concurrent.ConcurrentHashMap<>();

    static boolean isSplitReason(String reasonOrExplanation) {
        if (Phrases.empty(reasonOrExplanation)) return false;
        // Receipt texts are shared strings read on every tick: decide each distinct text once.
        if (SPLIT_TEXT.size() > 10_000) SPLIT_TEXT.clear();
        return SPLIT_TEXT.computeIfAbsent(
                reasonOrExplanation,
                text ->
                        Phrases.has(
                                text.toLowerCase(java.util.Locale.ROOT),
                                "split keep",
                                "split share"));
    }
}

enum Correction {
    AUTO,
    REVENUE,
    COST,
    TRANSFER,
    IGNORE
}

enum Confidence {
    CONFIRMED,
    LIKELY,
    UNCERTAIN
}

/** A deliberate correction choice for an unresolved transaction. */
@AllArgsConstructor
enum Decision {
    GAIN(REVENUE),
    COST(Correction.COST),
    TRANSFER(Correction.TRANSFER),
    IGNORE(Correction.IGNORE);
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
        return transaction != null
                && transaction.getCorrection() == Correction.AUTO
                && transaction.getConfidence() == Confidence.UNCERTAIN;
    }
}

/** One undoable owner decision: the before/after state of every row it changed. */
class CorrectionRecord {
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
    boolean touchesAny(Set<String> transactionIds) {
        for (Change change : getChanges()) {
            if (transactionIds.contains(change.getTransactionId())) return true;
        }
        return false;
    }

    /**
     * One row's before/after state. The flow and explanation snapshots are set when the decision
     * changed the row's flows (split, own-drop recovery); flows are immutable, so they are shared.
     */
    @NoArgsConstructor
    @AllArgsConstructor
    static class Change {
        String transactionId;
        Correction previousCorrection;
        Correction newCorrection;
        List<Flow> flowSnapshot;
        String explanationSnapshot;

        String getTransactionId() {
            return orEmpty(transactionId);
        }

        Correction getPreviousCorrection() {
            return previousCorrection == null ? Correction.AUTO : previousCorrection;
        }

        List<Flow> getFlowSnapshot() {
            return flowSnapshot == null
                    ? Collections.<Flow>emptyList()
                    : unmodifiableList(flowSnapshot);
        }

        boolean hasFlowSnapshot() {
            return flowSnapshot != null;
        }
    }
}

@EqualsAndHashCode(exclude = "priceCapturedAtEpochMillis")
class Flow {
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

    Flow(int itemId, String itemName, long quantityDelta, int unitPrice, long valueDelta) {
        this(itemId, itemName, quantityDelta, unitPrice, valueDelta, UNKNOWN);
    }

    Flow(
            int itemId,
            String itemName,
            long quantityDelta,
            int unitPrice,
            long valueDelta,
            PriceSource priceSource) {
        this(itemId, itemName, quantityDelta, unitPrice, valueDelta, priceSource, 0L);
    }

    Flow(
            int itemId,
            String itemName,
            long quantityDelta,
            int unitPrice,
            long valueDelta,
            PriceSource priceSource,
            long priceCapturedAtEpochMillis) {
        this.itemId = itemId;
        this.itemName = itemName;
        this.quantityDelta = quantityDelta;
        this.unitPrice = unitPrice;
        this.valueDelta = valueDelta;
        this.priceSource = priceSource == null ? UNKNOWN : priceSource;
        this.priceCapturedAtEpochMillis = priceCapturedAtEpochMillis;
    }

    PriceSource getPriceSource() {
        return priceSource == null ? UNKNOWN : priceSource;
    }

    long getPriceCapturedAtEpochMillis() {
        return SafeMath.nonNeg(priceCapturedAtEpochMillis);
    }

    /** {@code magnitude} units of this flow, signed like it, valued at {@code unit} each. */
    Flow part(long magnitude, long unit, PriceSource source) {
        long sign = quantityDelta < 0L ? -1L : 1L;
        return new Flow(
                itemId,
                itemName,
                sign * magnitude,
                SafeMath.toInt(unit),
                sign * SafeMath.safeMultiply(magnitude, unit),
                source,
                getPriceCapturedAtEpochMillis());
    }

    Flow part(long magnitude) {
        return part(magnitude, unitPrice, getPriceSource());
    }

    /**
     * What remains once {@code claimed} units leave at the unit price; null when nothing remains.
     */
    Flow rest(long claimed) {
        long left = SafeMath.abs(quantityDelta) - claimed;
        return claimed <= 0L
                ? this
                : left <= 0L
                        ? null
                        : new Flow(
                                itemId,
                                itemName,
                                quantityDelta < 0L ? -left : left,
                                unitPrice,
                                valueDelta - part(claimed).valueDelta,
                                priceSource,
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
    CURRENCY_PROXY("currency proxy"),
    GRAND_EXCHANGE("RuneLite market"),
    FACE_VALUE("face value"),
    /** High-alchemy value, for an ironman who chose to value items at their alch price. */
    HIGH_ALCHEMY("high alchemy"),
    DEFERRED_CLAIM("Claim on open"),
    /** Untradeable with no alch value: a known zero, never shown as an unknown price. */
    NO_VALUE("no value"),
    UNPRICED("unpriced"),
    UNKNOWN("unknown");
    String label;

    public String toString() {
        return label;
    }
}
