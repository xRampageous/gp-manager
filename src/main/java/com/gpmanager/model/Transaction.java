package com.gpmanager;

import static com.gpmanager.LedgerData.Scope.*;
import static com.gpmanager.Accounting.Amounts.*;
import static java.lang.Integer.parseInt;
import static com.gpmanager.Semantics.Table.*;
import static com.gpmanager.ActionReader.*;
import static net.runelite.client.game.ItemVariationMapping.*;
import java.io.*;
import java.util.regex.Pattern;
import net.runelite.api.*;
import static net.runelite.api.gameval.ItemID.*;
import net.runelite.client.game.*;
import net.runelite.http.api.item.ItemPrice;
import com.google.gson.annotations.SerializedName;
import static com.gpmanager.GameData.*;
import static com.gpmanager.CurrencyProxyCatalogue.*;
import static com.gpmanager.ActionKind.*;
import static com.gpmanager.Correction.*;
import static com.gpmanager.Phrases.*;
import static com.gpmanager.PriceSource.*;
import static com.gpmanager.SafeMath.*;
import static com.gpmanager.TransactionType.*;
import static java.util.Collections.*;
import java.util.*;
import lombok.*;
import lombok.experimental.UtilityClass;
import static com.gpmanager.FlowFilters.*;
import com.gpmanager.GpManagerConfig.Retention;
import static com.gpmanager.Accounting.*;
import static com.gpmanager.SaveCopies.*;
import static com.gpmanager.PauseReason.*;
import static java.lang.Math.*;
import static com.gpmanager.SafeMath.abs;
import com.gpmanager.CorrectionRecord.Change;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.function.*;
import static java.util.Comparator.*;
import static java.util.Arrays.*;
import static com.gpmanager.ReviewRules.*;
import static com.gpmanager.Semantics.Coverage.*;
import static java.util.Locale.*;
import com.gpmanager.Contribution.Category;
import com.gpmanager.MarketRows.*;
import java.nio.charset.StandardCharsets;
import static com.gpmanager.Fmt.*;
import static com.gpmanager.GeOffer.Side.*;
import com.gpmanager.Semantics.*;
import static com.gpmanager.MarketRows.Lifecycle.*;
import com.gpmanager.Semantics.Table;
import com.gpmanager.GpManagerConfig.LootFilter;
import com.gpmanager.LedgerData.CostView;
import java.awt.Color;
import javax.inject.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.*;
import net.runelite.client.plugins.grounditems.*;
import net.runelite.client.util.*;
import org.slf4j.*;
import static java.nio.file.StandardCopyOption.*;
import static java.nio.file.StandardOpenOption.*;
import static com.gpmanager.SaveStatus.State.*;
import com.gpmanager.SessionRepository.ReplaceOutcome;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.google.gson.*;
import com.gpmanager.SaveStatus.State;
import java.nio.channels.*;
import java.nio.file.*;
import static com.gpmanager.GeCustodyLedger.AttributionKind.*;
import static java.time.ZoneId.*;
import static com.gpmanager.Confidence.CONFIRMED;
import static com.gpmanager.Spells.*;
import static com.gpmanager.KeyChestCatalogue.*;
import static com.gpmanager.Context.*;
import static com.gpmanager.Context.TRANSFER;
import static com.gpmanager.Context.LOOT;
import static com.gpmanager.Context.PK_LOOT;
import static com.gpmanager.Inbox.*;
import com.google.gson.Gson;
import com.gpmanager.BossRetrievalCatalogue.Service;
import com.gpmanager.ChargeCheck.ChargedItem;
import com.gpmanager.ReviewRow.Item;
import com.gpmanager.SavedState.*;
import java.time.ZoneId;
import java.math.BigInteger;
import static com.gpmanager.GeOffer.CustodyConfidence.*;
import static com.gpmanager.GeOffer.Stage.*;
import com.gpmanager.GeOffer.*;
import com.gpmanager.OfferSlots.Snapshot;
import static net.runelite.api.GrandExchangeOfferState.*;
import static com.gpmanager.Context.MARKET;
import static com.gpmanager.ActionKind.FEE;
import static com.gpmanager.GeOffer.CustodyConfidence.AMBIGUOUS;
import static com.gpmanager.PauseReason.NONE;

/**
 * Canonical durable financial fact: stable id, timestamp, type/context, booked item flows, the
 * automatic counted state, confidence, correction and split state, and optional links to a PvP
 * encounter or the pending claim it settled. Booked values are never repriced.
 */
class Transaction extends SaveCopies {
    String id;
    @SerializedName("timestampEpochMillis")
    long timestamp; TransactionType type;
    Context context; String note, activityName;
    boolean counted; List<Flow> flows;
    long revenue, costs; Confidence confidence;
    String explanation; Correction correction;
    /** Original physical attribution retained by an explicit value-only removal, absent on old rows. */
    Correction ownershipCorrection;

    /** A custody handoff Review whose sold share is already withheld from the available pool. */
    boolean handoffHeld;

    String correctionReason, encounterId;

    /**
     * Dropped or fired, its pickup window still open: picking it up shrinks this row, so it leaves
     * the paid-price record only once the window closes. Saved only while true.
     */
    Boolean onGround;

    boolean onGround() {
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
        id = uuid(); this.timestamp = timestamp; this.type = type; this.context = context;
        this.note = string(note); activityName = normalizeActivity(activity);
        this.counted = counted; this.flows = copiedList(flows);
        this.confidence = confidence == null ? Confidence.UNCERTAIN : confidence;
        explanation = string(reason); correction = AUTO; correctionReason = ""; encounterId = encounter;
        recalculate();
    }

    void recalculate() {
        revenue = 0; costs = 0;
        for (Flow flow : flows)
            if (flow.value > 0) revenue = sum(revenue, flow.value); else if (flow.value < 0) costs = sum(costs, abs(flow.value));

    }

    /** The physical decision: a financial removal keeps the decision it replaced. */
    Correction physical(Correction decision) {
        return decision == IGNORE && ownershipCorrection != null ? ownershipCorrection : decision;
    }

    void applyCorrection(Correction newCorrection, String reason) {
        correction = newCorrection == null ? AUTO : newCorrection; correctionReason = normalizeReason(reason);
    }

    /**
     * Takes {@code amount} of one item's gains (newest flow first) or costs (oldest first) out of
     * the flows, each remainder keeping its own unit value, and returns the quantity left.
     */
    long takeQuantity(int id, boolean income, long amount) {
        var ordered = copiedList(flows()); if (income) reverse(ordered); var next = flowList();
        for (Flow flow : ordered) {
            long size = abs(flow.quantity),
                take = flow.item == id && (income ? flow.gain() : flow.cost())
                            ? min(positive(amount), size) : 0;
            amount -= take; long left = size - take;
            if (take <= 0)
                next.add(flow);
            else if (left > 0) {
                long unit = flow.unitPrice, signed = income ? left : -left;
                next.add(flow.raid(new Flow(id, flow.name, signed, flow.unitPrice,
                                unit == 0 ? flow.value / size * left : signed * unit, flow.source(),
                                flow.pricedAt())));
            }
        }
        if (income) reverse(next); replaceFlows(next);
        return quantity(id, income);
    }

    /** True while any flow still spends an item; a blowpipe shot keeps its scales after a dart. */
    boolean spends() {
        return hasLoss(flows());
    }

    void replaceFlows(List<Flow> next) {
        flows = copiedList(next); recalculate();
    }

    /**
     * Stamps split provenance on explanation/reason without changing the correction enum (partial
     * keep stays counted).
     */
    void stampSplit(String reason) {
        String mark = reason == null ? ItemSplits.SPLIT_SHARE_MARK : reason.trim();
        if (mark.isEmpty()) mark = ItemSplits.SPLIT_SHARE_MARK; correctionReason = normalizeReason(mark);
        String current = explanation();
        if (!ItemSplits.splitReason(current))
            explanation = current.isEmpty() ? mark : current + " — " + mark;
        else if (!current.contains(mark))
            explanation = current + " — " + mark;
    }

    void setExplanation(String next) {
        explanation = string(next);
    }

    /** Total gained ({@code gains}) or lost quantity of one item across the remaining flows. */
    long quantity(int id, boolean income) {
        long total = 0;
        for (Flow flow : flows)
            if (flow.item == id && (income ? flow.gain() : flow.cost()))
                total = sum(total, abs(flow.quantity));

        return total;
    }

    void assignEncounter(String newEncounterId, boolean pkSupply) {
        encounterId = newEncounterId;
        if (pkSupply && original() == CONSUMPTION) {
            type = PK_SUPPLY_COST; activityName = "PKing"; confidence = Confidence.LIKELY; explanation = msg("combatSupply");
        }
    }

    long gross() {
        return sum(revenue, costs);
    }

    String id() {
        if (empty(id)) id = uuid(); return id;
    }

    TransactionType original() {
        return type == null ? ADJUSTMENT : type;
    }

    TransactionType type() {
        switch (correction()) {
            case REVENUE:
                return GAIN;
            case COST:
                return CONSUMPTION;
            case TRANSFER:
                return TransactionType.TRANSFER;
            case IGNORE:
                return ADJUSTMENT;
            default:
                return original();
        }
    }

    Context context() {
        return context == null ? GENERIC : context;
    }

    String note() {
        return string(note);
    }

    /** Presentation-only audit-row note update; accounting fields are untouched. */
    String activityName() {
        return !blank(activityName) ? activityName.trim() : lootSource(note(), GENERAL);
    }

    /** The source a "Loot from X" note names, else {@code fallback}. */
    static String lootSource(String note, String fallback) {
        String prefix = "Loot from ";
        return note.startsWith(prefix) && note.length() > prefix.length() ? note.substring(prefix.length()).trim() : fallback;
    }

    boolean counts() {
        var safeCorrection = correction();
        return safeCorrection == REVENUE || safeCorrection == COST
                || safeCorrection == AUTO && counted;
    }

    /** Raw automatic counted flag, before any correction makes the row count. */

    List<Flow> flows() {
        return unmodifiableList(flows);
    }

    /** A loaded row is cleaned once (no missing or null flows), so readers never re-check. */
    void sanitize() {
        if (flows == null) flows = flowList(); flows.removeIf(Objects::isNull);
    }

    long autoNet() {
        return subtract(revenue, costs);
    }

    long revenue() {
        return correctedAmount(REVENUE, revenue);
    }

    long costs() {
        return correctedAmount(COST, costs);
    }

    long correctedAmount(Correction forced, long automatic) {
        var current = correction(); return current == AUTO ? automatic : current == forced ? gross() : 0;
    }

    long net() {
        return subtract(revenue(), costs());
    }


    Confidence confidence() {
        return confidence == null ? Confidence.UNCERTAIN : confidence;
    }

    String explanation() {
        return string(explanation);
    }

    Correction correction() {
        return correction == null ? AUTO : correction;
    }

    /** Automatic transfers and an owner's Transfer correction share one neutral projection. */
    boolean isTransfer() {
        return type() == TransactionType.TRANSFER || correction() == Correction.TRANSFER;
    }

    String correctionReason() {
        return fallback(correctionReason, "");
    }

    String encounterId() {
        return string(encounterId);
    }

    String claimId() {
        return string(sourceClaimId);
    }

    /**
     * Links this settlement to the claim it resolved; set once, in the same revision the claim is
     * removed.
     */
    void setSourceClaimId(String id) {
        sourceClaimId = fallback(id, null);
    }

    /** Observed action behind this change, or null when no honest verb was evidenced. */
    ActionKind action() {
        return blank(actionKind) ? null : BY_WIRE.get(lower(actionKind));
    }

    /** Presentation-only stamp; never alters type, valuation, counted state or ownership. */
    void setAction(ActionKind kind) {
        actionKind = kind == null ? null : kind.wireName;
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
                    String observed = action() == CAST && ICONS.containsKey(observedSpellName)
                            ? observedSpellName : null;
                    if (observed != null) return observed;
                    // Runes that identify a spell win over a click queued behind an autocast (owner
                    // 2026-09-28);
                    // otherwise the clicked name stands (a staff-covered or non-combat spell).
                    String spell = action() == CAST ? spellFor(flows()) : null; var label = actionLabel();
                    return spell != null ? spell : label == null ? "" : label.value;
                });
    }

    /** Derived-value memo (release pass): every refresh reads each receipt; never stored. */
    transient Object[] memo;

    /**
     * Slots 6+ hold derived values; the array is replaced as soon as flows, correction, type,
     * counted, action or label change (each is replaced, never edited in place).
     */
    <T> T memo(int slot, Supplier<T> build) {
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
        if (cache[slot] == null) cache[slot] = build.get(); return (T) cache[slot];
    }

    /** Presentation only. Stores only the normalized bounded label text. */
    void setActionLabel(ActionLabel label) {
        observedActionLabel = label == null ? null : label.value;
    }

    /**
     * The only accepted label value, revalidated on every read: bounded, sanitized, non-generic and
     * attached to a CAST or DRINK transaction. Oversized, markup, control-character or generic
     * saved text reads as no label, so a damaged save degrades to generic wording instead of
     * failing.
     */
    String labelWire() {
        if (empty(observedActionLabel) || action() != CAST && action() != DRINK) return null;
        var normalized = ActionLabel.of(observedActionLabel); return normalized == null ? null : normalized.value;
    }

    static String normalizeReason(String value) {
        if (blank(value)) return "Manual correction"; String text = value.trim();
        return text.length() > 200 ? text.substring(0, 200) : text;
    }
}

@AllArgsConstructor
enum Context {
    GENERIC(0), PRODUCTION(1), LOOT(2), PK_LOOT(3), MARKET(4), PK_DEATH(5), TRANSFER(6);
    @Getter int priority;
}

enum TransactionType {
    LOOT, CONSUMPTION, PROCESSING, TRADE, TRANSFER, GAIN, ADJUSTMENT, UNCERTAIN, PK_LOOT, PK_SUPPLY_COST, PK_DEATH_LOSS;

    static boolean ownershipMove(TransactionType type) { return type == TRANSFER || type == TRADE; }
}

/**
 * Personal item-split provenance helpers. Splits adjust personal Net share via the correction
 * timeline — never Party settlement, never fake Used eats.
 */
class ItemSplits {
    static final String SPLIT_SHARE_MARK = "Split share", SPLIT_KEEP_PREFIX = "Split keep ";

    static String reason(long keepQuantity, long quantity, String note) {
        long total = positive(quantity), keep = positive(min(keepQuantity, total));
        var sb = new StringBuilder(SPLIT_KEEP_PREFIX).append(keep).append('/').append(total).append(SEPARATOR)
                        .append(SPLIT_SHARE_MARK);
        if (note != null) {
            String text = note.trim();
            if (!text.isEmpty())
                sb.append(SEPARATOR).append(text.length() > 80 ? text.substring(0, 80) : text);
        }
        String value = sb.toString(); return value.length() > 200 ? value.substring(0, 200) : value;
    }

    static final Map<String, Boolean> SPLIT_TEXT = new ConcurrentHashMap<>();

    static boolean splitReason(String reasonOrExplanation) {
        if (empty(reasonOrExplanation)) return false;
        // Receipt texts are shared strings read on every tick: decide each distinct text once.
        if (SPLIT_TEXT.size() > 10000) SPLIT_TEXT.clear();
        return SPLIT_TEXT.computeIfAbsent(reasonOrExplanation, text -> has(
                                lower(text, false), "split keep", "split share"));
    }
}

enum Correction {
    /** BOND: the retired Repair bond's name, kept so no saved value can stop reading. */
    AUTO, REVENUE, COST, TRANSFER, IGNORE, BOND
}

enum Confidence {
    CONFIRMED, LIKELY, UNCERTAIN
}

/** A deliberate correction choice for an unresolved transaction. */
@AllArgsConstructor
enum Decision {
    GAIN(REVENUE), COST(Correction.COST), TRANSFER(Correction.TRANSFER), IGNORE(Correction.IGNORE);
    Correction correction;

}

/** Shared accounting predicate for receipts that still need an owner decision. */
class ReviewRules {
    /** Proven historical spend: financial correction only; never rewrite later item basis. */
    static boolean financialDose(Transaction transaction) {
        return transaction.action() == DRINK && msg("capturedDose").equals(transaction.note());
    }

    /**
     * Only unresolved automatic classifier decisions belong in the decision inbox. Ledger may
     * separately surface unknown pricing or provenance as review hints.
     */
    static boolean needsReview(Transaction transaction) {
        return transaction != null && transaction.correction() == AUTO
                && transaction.confidence() == Confidence.UNCERTAIN;
    }
}

/** One undoable owner decision: the before/after state of every row it changed. */
class CorrectionRecord extends SaveCopies {
    String reason; List<Change> changes;

    /** Set once the decision has been undone; an undone record is skipped by later undos. */
    boolean undone;

    CorrectionRecord() {
        // Gson
    }

    CorrectionRecord(String reason, List<Change> changes) {
        this.reason = Transaction.normalizeReason(reason); this.changes = copiedList(changes);
    }

    String reason() {
        return Transaction.normalizeReason(reason);
    }


    /** The rows this decision changed. */
    List<Change> changes() {
        return readOnlyList(changes);
    }

    /** True when any change in this record targets one of the given transaction ids. */
    boolean touchesAny(Set<String> ids) {
        return any(changes(), change -> ids.contains(change.transactionId()));
    }

    /**
     * One row's before/after state. The flow and explanation snapshots are set when the decision
     * changed the row's flows (split, own-drop recovery); flows are immutable, so they are shared.
     */
    @NoArgsConstructor
    @AllArgsConstructor
    static class Change extends SaveCopies {
        String transactionId; Correction previousCorrection, newCorrection;
        List<Flow> flowSnapshot; String explanationSnapshot;

        String transactionId() {
            return string(transactionId);
        }

        Correction previousCorrection() {
            return previousCorrection == null ? AUTO : previousCorrection;
        }

        List<Flow> flowSnapshot() {
            return readOnlyList(flowSnapshot);
        }

        boolean hasFlowSnapshot() {
            return flowSnapshot != null;
        }
    }
}

@EqualsAndHashCode(exclude = {"priceCapturedAtEpochMillis", "raid"}, callSuper = false)
class Flow extends SaveCopies {
    @SerializedName("itemId")
    int item;
    @SerializedName("itemName")
    String name;
    @SerializedName("quantityDelta")
    long quantity; long unitPrice;
    @SerializedName("valueDelta")
    long value; PriceSource priceSource;

    /** Epoch-millis when the selected non-zero unit price was observed; zero when unavailable. */
    long priceCapturedAtEpochMillis;

    /** Optional raid-attempt source; independent of canonical session and flow position. */
    String raid;

    Flow() {
        // Gson
    }

    Flow(int id, String name, long delta, long price, long value) {
        this(id, name, delta, price, value, UNKNOWN);
    }

    Flow(int id, String name, long delta, long price, long value, PriceSource source) {
        this(id, name, delta, price, value, source, 0);
    }

    Flow(int id, String name, long delta, long price, long value, PriceSource source,
            long at) {
        item = id; this.name = name; quantity = delta; unitPrice = price;
        this.value = value; priceSource = source == null ? UNKNOWN : source; priceCapturedAtEpochMillis = at;
    }

    PriceSource source() {
        return getPriceSource();
    }

    /** Lombok equality must compare the same null-safe source used by accounting. */
    PriceSource getPriceSource() {
        return priceSource == null ? UNKNOWN : priceSource;
    }

    long pricedAt() {
        return positive(priceCapturedAtEpochMillis);
    }

    Flow raid(Flow derived) {
        derived.raid = raid; return derived;
    }

    /** {@code magnitude} units of this flow, signed like it, valued at {@code unit} each. */
    Flow part(long size, long unit, PriceSource source) {
        long sign = quantity < 0 ? -1 : 1;
        return raid(new Flow(item, name, sign * size, unit,
                sign * multiply(size, unit), source, pricedAt()));
    }

    Flow part(long size) {
        return part(size, unitPrice, source());
    }

    /**
     * What remains once {@code claimed} units leave at the unit price; null when nothing remains.
     */
    Flow rest(long claimed) {
        long left = abs(quantity) - claimed;
        return claimed <= 0 ? this : left <= 0 ? null : raid(new Flow(item, name, quantity < 0 ? -left : left,
                                unitPrice, value - part(claimed).value, priceSource,
                                priceCapturedAtEpochMillis));
    }

    boolean gain() {
        return quantity > 0;
    }

    boolean cost() {
        return quantity < 0;
    }
}

/** Provenance of the unit price used for an item flow. */
enum PriceSource {
    MANUAL_OVERRIDE,
    /** A catalogue exchange rate or quantity-one identity parent's value. */
    CURRENCY_PROXY, GRAND_EXCHANGE, FACE_VALUE,
    /** High-alchemy value, for an ironman who chose to value items at their alch price. */
    HIGH_ALCHEMY, DEFERRED_CLAIM,
    /** A known zero item value, including a positive proxy quote rounded below one GP. */
    NO_VALUE, UNPRICED, UNKNOWN; String label = msg("price-source." + name());

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
        if (!lower(option, false).startsWith("cast")
                || (widget >>> 16 & 0xffff) != (spellbookGroupId & 0xffff))
            return null;
        var label = of(widgetText); if (label != null) return label; label = of(widgetName);
        if (label != null) return label;
        // Some menu entries expose the exact action text directly, e.g. "Cast Ice Burst".
        // Do not inspect menuTarget: it names the affected actor or item.
        if (option.length() > 4 && option.regionMatches(true, 0, "Cast ", 0, 5)) return of(option.substring(5)); return null;
    }

    /** True when the widget id's interface group is the spellbook group. */
    static ActionLabel of(String text) {
        String normalized = useful(clean(text)); return normalized == null ? null : new ActionLabel(normalized);
    }


    static String clean(String text) {
        if (text == null) return "";
        return pattern("spell-markup").matcher(text).replaceAll(" ").replace("&nbsp;", " ")
                .replace("&#160;", " ").replaceAll("\\s+", " ").trim();
    }

    static String useful(String text) {
        return !ActionSignals.rule("spell-reserved", text) && matches("spell-label", text) ? text : null;
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
    String wireName = lower(name(), false).replace('_', '-');

    /** Stable persisted identifier. */

    /** Wire and constant names, lowercased, read on every getActionKind() (a hot path). */
    static final Map<String, ActionKind> BY_WIRE = new HashMap<>();

    static {
        for (var kind : values()) {
            BY_WIRE.put(kind.wireName, kind); BY_WIRE.put(lower(kind.name(), false), kind);
        }
    }
}

/**
 * Combat spells by exact rune cost (spells.tsv). Autocasting never clicks a spell, so a Cast with
 * no captured name reads as the one spell its runes pay for (owner 2026-09-28). A staff, tome or
 * combination rune may cover elementals; when two spells could fit, it stays a plain Cast.
 */
@UtilityClass
class Spells {
    final Map<Integer, String> CAST_ANIMATIONS = byId("cast_animations");
    final Map<String, String> ICONS = byName("spells"), COMBINATIONS = byName("combination_runes");
    final String ELEMENTS = " air water earth fire ";

    final Map<String, String> BY_LOWER = index(ICONS.keySet(), name -> lower(name, false), name -> name);

    /** The item ids a spell makes (an alch's coins, Bones to Peaches' fruit); empty for others. */
    Set<Integer> product(String name) {
        for (var row : cached("spells"))
            if (row[0].equals(name) && row.length > 2) {
                return new HashSet<>(list(asList(row[2].split(",")), Integer::parseInt));
            }

        return emptySet();
    }

    /** The spell a name spells out, ignoring case (a "Bones to peaches" tablet), else null. */
    String named(String name) {
        return name == null ? null : BY_LOWER.get(lower(name, false).trim());
    }

    /**
     * The spell's spellbook icon as a negated sprite id (the sidebar's sprite lookup reads it),
     * else -1.
     */
    int icon(String name) {
        // d19 holds a spell's sprite, or minus an item id for a row named by its item (the barrel).
        String icon = ICONS.get(name); return icon == null ? -1 : -parseInt(icon);
    }

    /** Rune recipes are parsed once; fitting arithmetic and HashMap iteration stay unchanged. */
    final Map<String, Map<String, Long>> RECIPES = new LinkedHashMap<>();

    static {
        for (var row : cached("spell_recipes")) {
            var recipe = new HashMap<String, Long>();
            for (String part : row[1].split(" ")) {
                int colon = part.indexOf(':'); recipe.put(part.substring(colon + 1), Long.parseLong(part.substring(0, colon)));
            }
            RECIPES.put(row[0], unmodifiableMap(recipe));
        }
    }

    /**
     * The spell these rune costs pay for (a whole number of casts), or null. When staff-covered
     * elementals let several fit, the one paid in full wins; if none or several are, it stays a
     * Cast.
     */
    String spellFor(List<Flow> flows) {
        String fitting = null, full = null; int fits = 0, fulls = 0;
        for (var spell : RECIPES.entrySet()) {
            int fit = fit(spell.getValue(), flows);
            if (fit > 0) {
                fits++; fitting = spell.getKey();
            }
            if (fit > 1) {
                fulls++; full = spell.getKey();
            }
        }
        return fits == 1 ? fitting : fulls == 1 ? full : null;
    }

    boolean elemental(String name) {
        String resource = lower(name).replace(" rune", "");
        return ELEMENTS.contains(" " + resource + " ") || COMBINATIONS.containsKey(resource);
    }

    /** 0 when these costs cannot be this spell, 1 when they fit it, 2 when they pay it in full. */
    int fit(Map<String, Long> recipe, List<Flow> flows) {
        var paid = new HashMap<String, Long>();
        for (Flow flow : flows) {
            String name = lower(flow.name, false); if (!name.endsWith(" rune") || flow.quantity >= 0) return 0;
            String rune = name.substring(0, name.length() - 5); boolean used = false;
            for (String element : COMBINATIONS.getOrDefault(rune, rune).split(" "))
                // A combination rune pays only the elements this spell uses (steam is water for Ice
                // Barrage).
                if (recipe.containsKey(element) || !COMBINATIONS.containsKey(rune)) {
                    paid.merge(element, -flow.quantity, Long::sum); used = true;
                }

            if (!used) return 0;
        }
        long casts = 0;
        for (var resource : recipe.entrySet())
            if (!ELEMENTS.contains(" " + resource.getKey() + " ")) {
                long got = amount(paid, resource.getKey());
                if (got == 0 || got % resource.getValue() != 0 || casts != 0 && got / resource.getValue() != casts) return 0;
                casts = got / resource.getValue();
            }

        boolean full = true;
        for (var rune : recipe.entrySet()) {
            long got = amount(paid, rune.getKey());
            // An elemental is paid in full or supplied for free. A partial amount can be a rune
            // pickup netting against a different spell's spend; it cannot prove this spell.
            if (got != 0 && got != casts * rune.getValue()) return 0; full &= got == casts * rune.getValue();
        }
        return paid.keySet().stream().allMatch(recipe::containsKey) ? full ? 2 : 1 : 0;
    }
}

/** Shared correction-aware projection for accounting read models and exports. */
@UtilityClass
class Accounting {
    Amounts transaction(Transaction transaction) {
        return transaction == null ? unavailable() : transaction.memo(8, () -> amounts(transaction));
    }

    Amounts amounts(Transaction transaction) {
        if (transaction.isTransfer())
            return transfer();
        if (!transaction.counts()) return excluded(); var flows = transaction.flows();
        if (empty(flows))
            return included(transaction.revenue(), transaction.costs());
        if (flows.stream().allMatch(Objects::isNull)) return excluded(); long[] steps = cachedSteps(transaction);
        var money = new ValueTotal();
        for (int i = 0; i < flows.size(); i++) {
            Flow flow = flows.get(i); if (flow == null) continue;
            var part = flow(transaction, flow, steps[i]); money.add(part.income, part.cost);
        }
        return included(money.positive, money.negative);
    }

    /**
     * Owner 2026-09-28: a sip or bite steps an item down ((4) to (3), pie to half pie). The part
     * left is no gain and the whole potion no cost; only the step is, as the Ledger pairs it. Per
     * flow, the value both legs of each such pair give up (0 elsewhere, and always 0 under a
     * correction).
     */
    long[] cachedSteps(Transaction transaction) {
        return transaction.memo(6, () -> steps(transaction));
    }

    long[] steps(Transaction transaction) {
        var flows = transaction.flows(); long[] steps = new long[flows.size()];
        for (var pair : consumePairs(transaction)) {
            long left = flows.get(pair.remainder).value;
            if (left > 0 && sum(flows.get(pair.lossIndex).value, left) <= 0) {
                steps[pair.lossIndex] = left; steps[pair.remainder] = left;
            }
        }
        return steps;
    }

    /** One consume gate for accounting and item presentation; transfers/decants keep raw quotes. */
    List<PartialUse> consumePairs(Transaction transaction) {
        var flows = transaction.flows(); var action = transaction.action();
        // Cheapest facts first: this runs for every flow of every receipt on each refresh (release
        // pass).
        if (!transaction.counts() || transaction.correction() != AUTO
                || transaction.type() != CONSUMPTION
                        && transaction.type() != PK_SUPPLY_COST || action != null
                        && action != DRINK && action != EAT && action != SUPPLIES
                || isDecant(flows)) return emptyList();
        return matching(partialUses(flows), pair -> flows.get(pair.lossIndex).source()
                == flows.get(pair.remainder).source());
    }

    /** Returns the additive accounting contribution of a single item-flow row. */
    Amounts flow(Transaction transaction, Flow flow) {
        if (transaction == null || flow == null) return unavailable();
        if (transaction.isTransfer())
            return transfer();
        if (!transaction.counts())
            return excluded();
        long step = 0;
        if (transaction.correction() == AUTO) {
            var flows = transaction.flows();
            for (int i = 0; i < flows.size(); i++)
                if (flows.get(i) == flow) step = cachedSteps(transaction)[i];
        }
        return flow(transaction, flow, step);
    }

    /** One correction-aware leg, shared by indexed receipt folds and detail queries. */
    Amounts flow(Transaction transaction, Flow flow, long step) {
        long value = flow.value;
        switch (transaction.correction()) {
            case REVENUE:
                return included(abs(value), 0);
            case COST:
                return included(0, abs(value));
            default:
                return included(value > 0 ? subtract(value, step) : 0,
                        value < 0 ? subtract(abs(value), step) : 0);
        }
    }

    /** Correction-aware supplies/other split shared by every retained money fold. */
    CostSplit costSplit(Transaction transaction) {
        return transaction.memo(9, () -> split(transaction));
    }

    CostSplit split(Transaction transaction) {
        var total = transaction(transaction); if (!total.available) return new CostSplit(0, 0, false);
        if (!total.included || total.cost <= 0) return new CostSplit(0, 0, true);
        if (transaction.flows().isEmpty()) return new CostSplit(0, 0, false); long supplies = 0, other = 0;
        for (Flow flow : transaction.flows()) {
            if (flow == null) continue; var money = flow(transaction, flow);
            if (!money.available) return new CostSplit(0, 0, false); if (!money.included || money.cost <= 0) continue;
            switch (CostKind.of(transaction, flow)) {
                case SUPPLIES:
                    supplies = sum(supplies, money.cost); break;
                case LOSS:
                case MARKET:
                    other = sum(other, money.cost); break;
                default:
                    return new CostSplit(0, 0, false);
            }
        }
        return new CostSplit(supplies, other, sum(supplies, other) == total.cost);
    }

    /**
     * The correction-aware fold of the counted Market-context population. Market membership is the
     * authoritative typed predicate {@code automaticType == TRADE}; corrections change effective
     * amounts but never membership. Whole transactions are folded here so callers can remove their
     * gross legs from ordinary categories without re-deriving economics.
     */
    MarketFold marketFold(List<Transaction> transactions) {
        long income = 0, cost = 0, supplies = 0, other = 0; boolean splitAvailable = true;
        int included = 0; if (transactions == null) return new MarketFold(0, 0, 0, 0, true, 0);
        for (var transaction : transactions) {
            if (transaction == null || transaction.original() != TRADE) continue;
            var money = transaction(transaction); if (!money.included) continue; included++; income = sum(income, money.income);
            cost = sum(cost, money.cost); var split = costSplit(transaction);
            if (!split.available) {
                splitAvailable = false; continue;
            }
            supplies = sum(supplies, split.supplies); other = sum(other, split.other);
        }
        return new MarketFold(income, cost, splitAvailable ? supplies : 0, splitAvailable ? other : 0,
                splitAvailable, included);
    }

    @AllArgsConstructor
    class MarketFold {
        long income, cost, supplies, other; boolean splitAvailable;

        /** Counted Market-context transactions behind this fold. */
        int transactions;

        long net() {
            return subtract(income, cost);
        }
    }

    @AllArgsConstructor
    class Amounts {
        boolean available, included, transfer; long income, cost;

        static Amounts included(long income, long cost) {
            return new Amounts(true, true, false, income, cost);
        }

        static Amounts excluded() {
            return new Amounts(true, false, false, 0, 0);
        }

        static Amounts transfer() {
            return new Amounts(true, false, true, 0, 0);
        }

        static Amounts unavailable() {
            return new Amounts(false, false, false, 0, 0);
        }

        long net() {
            return subtract(income, cost);
        }
    }

    @AllArgsConstructor
    class CostSplit {
        long supplies, other; boolean available;
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
        if (transaction == null || flow == null || transaction.action() == ActionKind.DEFERRED_CLAIM)
            return NONE;
        var effective = flow(transaction, flow);
        if (!effective.available || !effective.included || effective.cost <= 0) return NONE;
        if (flow.item == GE_TAX_ITEM_ID || transaction.original() == PK_DEATH_LOSS)
            return LOSS;
        if (transaction.original() == TRADE) return MARKET; var type = transaction.original();
        // Supplies are consumables the player used (eat / drink / cast / fire / charges /
        // processing
        // inputs), which the engine evidences with an action kind or a supply-shaped type. A plain
        // "value decreased" consumption with no evidence is an item gone, i.e. a loss.
        // A production run's input is a supply even when that action made nothing (a failed smelt).
        return type == PK_SUPPLY_COST || type == PROCESSING || transaction.action() != null
                || transaction.context() == PRODUCTION ? SUPPLIES : LOSS;
    }
}

class Session extends SaveCopies {
    /** The always-on General tracker is shown as "Overall". */
    static final String DURABLE_OWNER_NAME = "Overall";

    static final int MAX_UNDO_HISTORY = 32; String id, name, activityHint;
    SessionMode mode;

    /** True for a named Grind; false for the Overall (free play) session. */
    boolean grind;

    /** Runtime-only signal/revision for profile totals derived from session-day summaries. */
    transient Runnable changeListener;

    /** Runtime-only change count for closed-session financial view invalidation. */
    transient long saveStamp;

    /** Runtime-only pooled known-basis observer; the engine wires the tracked-basis ledger. */
    transient TrackedBasisLedger ownedValueObserver;

    /** A drop or shot nobody picked up is used up, off the ground and off the paid-price record. */
    void usedUp(Transaction row) {
        row.setOnGround(false); if (ownedValueObserver != null) ownedValueObserver.onUsedUp(row);
        notifyChanged();
    }

    void notifyOwnedValue(Transaction transaction, boolean owned) {
        if (ownedValueObserver != null) ownedValueObserver.onAdded(transaction, owned);
    }

    boolean canEditValue(Transaction transaction, Correction previous, List<Flow> previousFlows,
            Correction nextCorrection, List<Flow> next) {
        return ownedValueObserver == null || ownedValueObserver.canChange(
                        transaction, previous, previousFlows, nextCorrection, next);
    }

    boolean canCorrect(Transaction transaction, Correction next) {
        return canEditValue(transaction, transaction.correction(), transaction.flows(), next, transaction.flows());
    }

    /** One canonical batch order: chronological, then stable id. */
    static List<Transaction> correctionOrder(Collection<Transaction> targets) {
        var ordered = copiedList(new LinkedHashSet<>(targets));
        ordered.sort(comparingLong((Transaction transaction) -> transaction.timestamp)
                .thenComparing(transaction -> transaction.id()));
        return ordered;
    }

    /** Announces a changed row: totals, PvP and the owned-value observer follow it. */
    void changed(Transaction transaction, Correction previous, List<Flow> previousFlows) {
        transactionChanged(transaction);
        if (ownedValueObserver != null)
            ownedValueObserver.onChanged(transaction, previous, previousFlows);
        // A removal's retained ownership ends when the row stops being removed.
        if (transaction.correction() != IGNORE) transaction.ownershipCorrection = null;
    }

    @SerializedName("startedAtEpochMillis")
    long startedAt;
    @SerializedName("endedAtEpochMillis")
    long endedAt;

    boolean paused; PauseReason pauseReason = NONE;
    long pausedAtEpochMillis, totalPausedMillis; List<Transaction> transactions = new ArrayList<>();
    PkTally pk = new PkTally();

    List<CorrectionRecord> correctionHistory = new ArrayList<>(); List<UndoRecord> undoHistory = new ArrayList<>();
    int transactionLimit; long retainedRevenue, retainedCosts, retainedSuppliesCosts;
    boolean retainedCostSplitComplete; long compactedTransactionCount;
    Map<String,long[]> compactedRaids;
    // Nullable by design: a target is owner-local presentation metadata, never
    // an accounting input or generated transaction.
    Long profitTargetGp;
    // The session's Net when the target above was last set: the aim counts what has been earned
    // since (owner live 2026-10-14). Null on older saves counts from the run's start.
    Long profitTargetSetNet;

    /** The run's Active Time when the Net aim was set; its pace counts from there (owner 2026-10-16). */
    Long profitTargetSetActiveMillis;

    /**
     * When Live's current session began (New session, or a target set), and the run's Active Time
     * then: Live counts from here while the run keeps everything (owner 1.5.2). Null shows the
     * whole run (a fresh Grind, older saves).
     */
    Long sessionStartAt;

    long sessionStartMillis;

    /** Optional rate-only restart; older saves use the normal Live session window. */
    Long rateStartAt; long rateSetMillis;
    /** Optional frozen clock backup and the time resumed by Restore time. */
    Long savedRateMillis, restoredRateMillis;
    /** Frozen displayed duration of a saved copy; absent on canonical runs. */
    Long savedActiveMillis;

    long liveElapsed(long now) {
        long elapsed = elapsed(now), from = sessionStartAt == null ? 0 : sessionStartMillis;
        return rateStartAt != null && rateSetMillis >= 0 && rateSetMillis <= elapsed && rateStartAt <= now
                && (rateSetMillis >= from && rateStartAt >= (sessionStartAt == null ? startedAt : sessionStartAt)
                    || rateSetMillis == 0 && rateStartAt == startedAt)
                && (restoredRateMillis == null || restoredRateMillis >= 0 && restoredRateMillis <= rateSetMillis) ? elapsed - rateSetMillis + (restoredRateMillis == null ? 0 : restoredRateMillis) : positive(elapsed - from);
    }

    /** Free play's Net milestone: it counts Free play's whole Net (owner 1.5.2); null with none. */
    Long milestoneGp;
    // Signature targets: the actual Active-Time aim this Grind instance ran with, and the stable
    // My Grind lineage link it was started from. Both are metadata; neither books anything.
    Long activeTimeTargetMillis;
    // The session's own Active Time when the target above was last set; null on older saves and
    // fresh sessions, which keep counting from the run's start (owner live 2026-10-13).
    Long activeTimeTargetSetMillis;
    // The timer's own pause: the Active Time it was frozen at; null while it runs (owner live
    // 2026-10-14). Distinct from a session pause — the run keeps tracking.
    Long activeTimeTargetPauseMillis; String grindId;

    Session() {
        // Gson
    }

    Session(String name, long startedAt, SessionMode mode) {
        id = uuid(); this.name = normalizeName(name);
        this.mode = mode == null ? SessionMode.GENERAL : mode; activityHint = "General";
        this.startedAt = startedAt; transactionLimit = 2000; retainedCostSplitComplete = true;
    }

    /**
     * @param custodyOwned true for transactions already realized by a custody lifecycle; their item
     *     legs consumed their reservation and must never deplete again
     */
    void addTransaction(Transaction transaction, int maxTransactions, boolean owned) {
        if (transaction == null) return; transactionLimit = max(1, maxTransactions); transactions.add(transaction);
        transactionChanged(transaction); notifyOwnedValue(transaction, owned); evict(transactions.size() - transactionLimit);
    }

    /** Drops the oldest rows over the limit, each summarized exactly into the retained totals. */
    void evict(int overflow) {
        if (overflow <= 0) return; var removed = new ArrayList<Transaction>();
        for (var row : transactions)
            if (removed.size() < overflow && !needsReview(row)) removed.add(row);
        transactions.removeAll(removed); var ids = new HashSet<String>();
        for (var row : removed) {
            compactTransaction(row); ids.add(row.id());
        }
        pruneHistories(ids);
    }

    void recordAction(String hint) {
        setActivityHint(normalizeActivity(hint));
    }

    void setActivityHint(String hint) {
        if (blank(hint)) return; String next = normalizeActivity(hint), current = activityHint();
        if (next.equalsIgnoreCase(current)) return; activityHint = next;
    }

    void rename(String value) {
        name = normalizeName(value);
    }

    /**
     * Sets the Net aim and the run's Net at the set moment (owner live 2026-10-14): a changed value
     * counts from now, as does the same value once the aim is already reached; an unchanged,
     * unreached value keeps its count.
     */
    boolean setNetTarget(Long value, long net, long active) {
        Long next = positiveOrNull(value);
        if (Objects.equals(next, profitTargetGp))
            return next != null && targetProgress(net) >= next
                    && rebaseTarget(net, active);
        profitTargetGp = next; rebaseTarget(net, active); return true;
    }

    /** The run's Net when the aim was set; 0 with none and on older saves. */
    long profitTargetSetNet() {
        return profitTargetSetNet == null ? 0 : profitTargetSetNet;
    }

    /** What the Net aim has counted so far; sessionNet is the run's total. */
    long targetProgress(long net) {
        return sessionStartAt != null ? metrics(rowsSince(sessionStartAt), 0).net
                : subtract(net, profitTargetSetNet());
    }

    /**
     * Re-bases the set Net aim's count on the given net, keeping its value (owner live 2026-10-14):
     * Reset counts the aim from now whether or not it was reached.
     */
    boolean rebaseTarget(long net, long active) {
        profitTargetSetNet = profitTargetGp == null ? null : net;
        profitTargetSetActiveMillis = profitTargetGp == null ? null : active; return profitTargetGp != null;
    }

    /** Active Time the Net aim has run for; the whole run on older saves. */
    long profitTargetActiveMillis(long elapsed) {
        return positive(elapsed - (profitTargetSetActiveMillis == null ? 0 : profitTargetSetActiveMillis));
    }

    /** The actual Active-Time target this instance ran with; null when none was set. */
    Long timeTargetMillis() {
        return positiveOrNull(activeTimeTargetMillis);
    }

    /**
     * Sets the actual Active-Time target and restarts its count when the value changes (owner live
     * 2026-10-13): a target counts from the moment it was set, not from the run's start. An
     * unchanged value keeps the running count; a cleared one forgets it.
     */
    boolean setTimeTarget(Long value, long now) {
        Long next = positiveOrNull(value);
        if (Objects.equals(next, activeTimeTargetMillis)) {
            // Re-saving the same aim keeps a running window; a completed one starts over
            // (owner live 2026-10-13: Edit > Save after completion must begin again).
            if (next != null && elapsed(now) - timeSetMillis() >= next) {
                rearmTimeTarget(now); return true;
            }
            return false;
        }
        activeTimeTargetMillis = next; activeTimeTargetSetMillis = next == null ? null : elapsed(now);
        activeTimeTargetPauseMillis = null; return true;
    }

    /** The Active Time a set target counts from; 0 with none and on older saves. */
    long timeSetMillis() {
        return activeTimeTargetSetMillis == null || activeTimeTargetSetMillis < 0 ? 0 : activeTimeTargetSetMillis;
    }

    /** The window a set target has counted so far; frozen while the timer is paused. */
    long timeWindowMillis(long elapsed) {
        long at = activeTimeTargetPauseMillis != null ? activeTimeTargetPauseMillis : positive(elapsed);
        return positive(at - timeSetMillis());
    }

    /** True while the timer's own pause holds; the run keeps tracking (owner live 2026-10-14). */
    boolean timeTargetPaused() {
        return activeTimeTargetPauseMillis != null;
    }

    /** Freezes the timer's window where it stands; an ended window has nothing to stop. */
    boolean pauseTimeTarget(long now) {
        if (activeTimeTargetMillis == null || activeTimeTargetPauseMillis != null || timeWindowMillis(elapsed(now)) >= activeTimeTargetMillis) return false;
        activeTimeTargetPauseMillis = elapsed(now); return true;
    }

    /** Resumes a paused timer, keeping the window it had already counted. */
    boolean resumeTimeTarget(long now) {
        if (activeTimeTargetMillis == null || activeTimeTargetPauseMillis == null) return false;
        activeTimeTargetSetMillis = timeSetMillis()
                        + positive(elapsed(now) - activeTimeTargetPauseMillis);
        activeTimeTargetPauseMillis = null; return true;
    }

    /**
     * Restarts the set target's count from now without changing its value (owner live 2026-10-13).
     */
    void rearmTimeTarget(long now) {
        if (activeTimeTargetMillis != null) {
            activeTimeTargetSetMillis = elapsed(now); activeTimeTargetPauseMillis = null;
        }
    }

    /** Stable My Grind lineage link; empty when this instance was never saved as a Grind. */
    String grindId() {
        return string(grindId);
    }

    void setGrindId(String value) {
        grindId = fallback(value, null);
    }

    boolean isLinked() {
        return !grindId().isEmpty();
    }

    void pause(long now, PauseReason reason) {
        if (!paused && endedAt == 0) {
            paused = true; pausedAtEpochMillis = now; pauseReason = reason == null ? MANUAL : reason;
        }
    }

    void resume(long now) {
        if (paused && endedAt == 0) {
            totalPausedMillis = sum(totalPausedMillis, positive(now - pausedAtEpochMillis));
            pausedAtEpochMillis = 0; paused = false;
        }
        pauseReason = NONE;
    }

    PauseReason pauseReason() {
        return !paused ? NONE : pauseReason == null || pauseReason == NONE ? MANUAL : pauseReason;
    }

    /** A closed run carries on: the time it was closed counts as paused (owner 1.5.2, Restore). */
    void reopen(long now) {
        if (paused) totalPausedMillis = sum(totalPausedMillis, positive(endedAt - pausedAtEpochMillis));
        totalPausedMillis = sum(totalPausedMillis, positive(now - endedAt)); paused = false;
        pausedAtEpochMillis = 0; pauseReason = NONE; endedAt = 0; saveStamp++;
    }

    void close(long now) {
        if (endedAt != 0) return; saveStamp++; endedAt = max(now, startedAt);
    }

    boolean correctTransaction(String id, Correction decision, String reason) {
        var transaction = findTransaction(id);
        return transaction != null && correct(singletonList(transaction), decision == null ? AUTO : decision,
                        reason);
    }

    /**
     * Automatic reconciliation of an automatic estimate against a measured Check: shrink it by
     * {@code quantity} of one item, voiding the row once it spends nothing. Automatic evidence, not
     * an owner correction, so nothing is logged; the change is announced like every other change.
     */
    void settleEstimate(Transaction estimate, int id, long quantity, String reason) {
        if (estimate == null || id <= 0 || quantity <= 0) return; var previous = estimate.correction();
        var previousFlows = estimate.flows(); estimate.takeQuantity(id, false, quantity);
        estimate.confidence = CONFIRMED; if (!estimate.spends()) estimate.applyCorrection(IGNORE, reason);
        estimate.setExplanation(reason); changed(estimate, previous, previousFlows);
    }

    /**
     * Applies one decision to the still-unresolved transactions and stores one correction record,
     * so a single undo restores the complete operation.
     */
    int correctAll(List<String> ids, Correction decision, String reason) {
        var next = decision == null ? AUTO : decision; if (empty(ids) || next == AUTO) return 0;
        var targets = matching(list(new LinkedHashSet<>(ids), this::findTransaction),
                ReviewRules::needsReview);
        return correct(targets, next, fallback(reason, msg("bulkReview"))) ? targets.size() : 0;
    }

    /**
     * One owner decision over rows: every row is checked with the owned-value observer before any
     * changes (one blocked row rejects the whole decision), then one undoable record is logged.
     */
    boolean correct(List<Transaction> targets, Correction next, String reason) {
        if (!canCorrect(targets, next, now())) return false;
        var changes = new ArrayList<Change>();
        for (var transaction : correctionOrder(targets)) {
            var previous = transaction.correction(); var flows = transaction.flows();
            transaction.applyCorrection(next, reason); changed(transaction, previous, flows);
            changes.add(new Change(transaction.id(), previous, transaction.correction(), null, null));
        }
        correctionHistory.add(new CorrectionRecord(reason, changes));
        return true;
    }

    /**
     * The receipt-aware check a decision's Review preview and its application share: every row is
     * projected with the owned-value observer, and one blocked row rejects the whole decision.
     */
    boolean canCorrect(List<Transaction> targets, Correction next, long now) {
        var ordered = correctionOrder(targets);
        return !ordered.isEmpty() && (ownedValueObserver == null
                || ownedValueObserver.canChangeAll(ordered, copy -> copy.applyCorrection(next, "")));
    }

    /**
     * Personal item split: Net keeps only {@code keepQuantity} of {@code itemId}. Given-away qty is
     * removed from counted gain (not Used). Full give-away on a single-item row IGNORE-corrects.
     * Shares the correction undo stack.
     */
    boolean splitItem(String identifier, int id, long keepQuantity, String note) {
        var transaction = findTransaction(identifier); if (transaction == null || id <= 0) return false;
        long gain = transaction.quantity(id, true); if (gain <= 0) return false;
        long keep = positive(min(keepQuantity, gain)); if (keep == gain) return false;
        String reason = ItemSplits.reason(keep, gain, note); var previous = transaction.correction();
        var flows = copiedList(transaction.flows()); String explanationSnapshot = transaction.explanation();
        boolean ignore = keep <= 0 && onlyGainItem(transaction, id);
        if (!canEditValue(transaction, previous, flows, ignore ? IGNORE : previous, null))
            // The known coverage this split would shrink is reserved or already realized.
            return false;
        if (ignore) transaction.applyCorrection(IGNORE, reason); else transaction.takeQuantity(id, true, gain - keep);
        transaction.stampSplit(reason); changed(transaction, previous, flows);
        correctionHistory.add(new CorrectionRecord(reason, asList(new Change(identifier, previous,
                        transaction.correction(), flows, explanationSnapshot))));
        return true;
    }

    /** Latest applied decision; shared by Undo and its current cog label. */
    CorrectionRecord latestCorrection() {
        for (int index = correctionHistory.size() - 1; index >= 0; index--) {
            var candidate = correctionHistory.get(index); if (candidate != null && !candidate.undone) return candidate;
        }
        return null;
    }

    /** Revert the latest decision using its original flow snapshot and ownership validation. */
    boolean undoCorrection() {
        var last = latestCorrection(); if (last == null) return false;
        var changes = last.changes(); if (changes.isEmpty()) return false;
        var targets = list(changes, change -> findTransaction(change.transactionId()));
        // Every normalized target must exist exactly once before the reverse projection.
        if (targets.contains(null) || new HashSet<>(targets).size() != targets.size()) return false;
        // Validate in the exact reverse order Undo applies, without mutating receipts or snapshots.
        reverse(targets); var inverse = new ArrayList<Change>(changes); reverse(inverse);
        var undo = inverse.iterator();
        if (ownedValueObserver != null && !ownedValueObserver.canChangeAll(targets, copy -> restoreChange(copy, undo.next()))) return false;
        reverse(targets);
        for (int index = changes.size() - 1; index >= 0; index--) {
            var change = changes.get(index); var transaction = targets.get(index);
            var previous = transaction.correction(); var previousFlows = transaction.flows(); restoreChange(transaction, change);
            transaction.correctionReason = previousReason(transaction.id(), last); changed(transaction, previous, previousFlows);
        }
        last.undone = true; return true;
    }

    static void restoreChange(Transaction transaction, Change change) {
        if (change.hasFlowSnapshot()) transaction.replaceFlows(change.flowSnapshot());
        if (change.explanationSnapshot != null) transaction.setExplanation(change.explanationSnapshot);
        transaction.applyCorrection(change.previousCorrection(), "Manual correction");
    }

    /** Restore metadata from the retained decision timeline without adding save fields. */
    String previousReason(String id, CorrectionRecord undone) {
        for (int index = correctionHistory.indexOf(undone) - 1; index >= 0; index--) {
            var record = correctionHistory.get(index); if (record == null || record.undone) continue;
            for (Change change : record.changes())
                if (id.equals(change.transactionId()) && (!change.hasFlowSnapshot()
                                || change.previousCorrection() != change.newCorrection
                                || ItemSplits.splitReason(record.reason())))
                    return record.reason();

        }
        return "";
    }

    static boolean onlyGainItem(Transaction transaction, int id) {
        if (transaction == null) return false; boolean sawTarget = false;
        for (Flow flow : transaction.flows()) {
            if (flow.quantity == 0) continue; if (flow.item != id) return false;
            if (flow.quantity > 0) sawTarget = true;
        }
        return sawTarget;
    }

    /**
     * A dropped item picked back up: the Dropped row loses that quantity, and is excluded once it
     * spends nothing, so the drop and its pickup never show as a loss and a gain. Returns how many
     * the row still held to give back.
     */
    long pickUp(String dropId, int id, long quantity) {
        var drop = findTransaction(dropId);
        // A removed or excluded drop still reconciles its own pickup; other corrections keep theirs.
        var physical = drop == null ? null : drop.physical(drop.correction());
        if (physical != AUTO && physical != IGNORE || !drop.counted) return 0;
        long taken = min(quantity, drop.quantity(id, false)); if (taken <= 0) return 0; var previous = drop.correction();
        var flows = copiedList(drop.flows()); drop.takeQuantity(id, false, taken);
        if (!drop.spends()) drop.applyCorrection(IGNORE, "Picked back up"); changed(drop, previous, flows);
        return taken;
    }

    Transaction undoTransaction() {
        if (transactions.isEmpty()) return null; var removed = transactions.get(transactions.size() - 1);
        if (ownedValueObserver != null && !ownedValueObserver.canRemove(removed))
            // Known coverage was already reserved/realized downstream; leave it untouched.
            return null;
        transactions.remove(transactions.size() - 1); notifyChanged();
        if (ownedValueObserver != null) ownedValueObserver.onRemoved(removed); undoHistory.add(new UndoRecord(removed));
        int overflow = undoHistory.size() - MAX_UNDO_HISTORY; if (overflow > 0) undoHistory.subList(0, overflow).clear();
        return removed;
    }

    Transaction restoreUndo() {
        for (int index = undoHistory.size() - 1; index >= 0; index--) {
            var record = undoHistory.get(index); if (record == null || record.restored || record.transaction == null) continue;
            var restored = record.transaction;
            if (ownedValueObserver != null && !ownedValueObserver.canAdd(restored))
                // Restoring would re-add known coverage after a later realization; refuse it.
                return null;
            int effectiveLimit = transactionLimit <= 0 ? 2000 : transactionLimit;
            // Restoring an undo is also a bounded-ledger insertion: the displaced row is summarized
            // exactly.
            evict(transactions.size() + 1 - effectiveLimit); transactions.add(restored);
            transactionChanged(restored); notifyOwnedValue(restored, false); record.restored = true;
            return restored;
        }
        return null;
    }

    Transaction findTransaction(String id) {
        return empty(id) ? null : firstMatch(transactions, transaction -> id.equals(transaction.id()));
    }

    /** Counts one kill or death on this Grind and returns the id its receipts carry. */
    String addEncounter(boolean kill, long now) {
        String id = pk().record(kill); notifyChanged(); recordAction("PKing");
        return id;
    }

    void attachToEncounter(String id, String encounter, boolean pkSupply) {
        var transaction = findTransaction(id);
        if (transaction == null || empty(encounter) || !(encounter.startsWith(PkTally.KILL) || encounter.startsWith(PkTally.DEATH))) return;
        saveStamp++; transaction.assignEncounter(encounter, pkSupply); notifyChanged();
    }

    void attachRecentCosts(String encounter, long now, long lookbackMillis) {
        long cut = positive(now - positive(lookbackMillis));
        for (int index = transactions.size() - 1; index >= 0; index--) {
            var transaction = transactions.get(index); if (transaction.timestamp < cut) break;
            if (!transaction.encounterId().isEmpty()) continue;
            if (transaction.revenue == 0 && transaction.costs > 0
                    && transaction.original() == CONSUMPTION)
                attachToEncounter(transaction.id(), encounter, true);
        }
    }

    void compactTransaction(Transaction transaction) {
        if (transaction == null) return; compactedTransactionCount++; RaidHistory.compact(this, transaction);
        if (transaction.onGround()) usedUp(transaction); pk().compact(transaction, transactions);
        if (transaction.type() == TransactionType.TRANSFER) return;
        if (transaction.counts()) {
            var money = transaction(transaction); retainedRevenue = sum(retainedRevenue, money.income);
            retainedCosts = sum(retainedCosts, money.cost); var split = costSplit(transaction);
            if (!split.available) retainedCostSplitComplete = false;
            else retainedSuppliesCosts = sum(retainedSuppliesCosts, split.supplies);
        }
    }

    /** A counted row changed: advance the revision. */
    void transactionChanged(Transaction transaction) {
        if (transaction != null) notifyChanged();
    }

    PkTally pk() {
        if (pk == null) pk = new PkTally(); return pk;
    }

    long bestKill() {
        return pk().bestKill(transactions);
    }

    @AllArgsConstructor
    static class MoneyFold {
        long income, cost, supplies; boolean split = true;

        Amounts add(Transaction transaction) {
            var money = transaction(transaction); if (!money.available || !money.included) return money;
            income = sum(income, money.income); cost = sum(cost, money.cost);
            var split = costSplit(transaction); if (!split.available) this.split = false;
            else supplies = sum(supplies, split.supplies); return money;
        }
    }

    Metrics metrics(long now) {
        return metrics(new MoneyFold(retainedRevenue, retainedCosts, retainedSuppliesCosts,
                        compactedTransactionCount == 0 || retainedCostSplitComplete), transactions,
                elapsed(now));
    }

    /**
     * Rows booked at or after {@code at}: a target run's own (owner 1.5.2). A correction keeps its
     * row's time, so fixes to older rows stay out; rows compacted past the row limit drop out.
     */
    List<Transaction> rowsSince(long at) {
        return matching(transactions, row -> row != null && row.timestamp >= at);
    }

    /** Fold prospective edits on detached receipts; a null edit omits the selected rows. */
    long netAfter(Collection<String> ids, Consumer<Transaction> edit) {
        var selected = new HashSet<>(ids);
        var money = new MoneyFold(retainedRevenue, retainedCosts, retainedSuppliesCosts, true);
        for (var row : transactions) {
            if (row == null) continue;
            if (selected.contains(row.id())) {
                if (edit == null) continue; row = copy(row); edit.accept(row);
            }
            money.add(row);
        }
        return subtract(money.income, money.cost);
    }

    /** The money in these rows alone, over the given Active Time. */
    Metrics metrics(List<Transaction> rows, long elapsed) {
        return metrics(new MoneyFold(0, 0, 0, true), rows, elapsed);
    }

    Metrics metrics(MoneyFold money, List<Transaction> rows, long elapsed) {
        for (var transaction : rows) {
            if (transaction == null) continue; money.add(transaction);
        }
        if (money.supplies > money.cost) money.split = false; long net = subtract(money.income, money.cost);
        return new Metrics(activityHint(), paused, elapsed, money.income, money.cost, net, hourly(net, elapsed),
                money.split ? money.supplies : 0,
                money.split ? subtract(money.cost, money.supplies) : 0, money.split);
    }

    /**
     * The correction-aware money folded out of individually inspectable rows by compaction.
     * Retained rows plus this contribution reconcile exactly to {@link #metrics}.
     */
    @AllArgsConstructor
    static class CompactedContribution {
        long rows, income, cost, supplies; boolean split;

    }

    CompactedContribution compactedContribution() {
        return new CompactedContribution(compactedTransactionCount, retainedRevenue, retainedCosts,
                retainedSuppliesCosts, compactedTransactionCount == 0 || retainedCostSplitComplete);
    }

    static long hourly(long value, long elapsed) {
        if (elapsed <= 0) return 0;
        // round saturates at the long range by itself.
        return round(value * (3_600_000.0d / elapsed));
    }

    long elapsed(long now) {
        if (endedAt != 0 && savedActiveMillis != null && savedActiveMillis >= 0)
            return savedActiveMillis;
        long endpoint = endedAt == 0 ? now : endedAt,
            activePaused = paused ? positive(endpoint - pausedAtEpochMillis) : 0;
        return positive(endpoint - startedAt - totalPausedMillis - activePaused);
    }

    static String normalizeName(String value) {
        return fallback(value, DURABLE_OWNER_NAME);
    }

    String id() {
        if (empty(id)) id = uuid(); return id;
    }

    String name() {
        return normalizeName(name);
    }

    void notifyChanged() {
        saveStamp++; var listener = changeListener; if (listener != null) listener.run();
    }

    String activityHint() {
        return normalizeActivity(activityHint);
    }

    SessionMode mode() {
        return mode == null ? SessionMode.GENERAL : mode;
    }

    boolean isClosed() {
        return endedAt != 0;
    }

    Long targetGp() {
        return positiveOrNull(profitTargetGp);
    }

    /** A loaded session is cleaned once: no null rows or flows reach any reader. */
    void sanitize() {
        if (transactions == null) transactions = new ArrayList<>(); transactions.removeIf(Objects::isNull);
        for (var transaction : transactions) transaction.sanitize();
    }

    List<Transaction> transactions() {
        return unmodifiableList(transactions);
    }

    /**
     * Receipt retention (section L): folds retained rows dated before {@code cutoffEpochMillis}
     * into this session's correction-aware archive totals, oldest first, skipping rows the caller
     * still needs individually (an unresolved claim's audit row or its settlement). Undo/correction
     * entries that reference a folded row are pruned at the same boundary. Compacted rows are no
     * longer individually correctable; totals stay exact.
     */
    int compactBefore(long cutoffEpochMillis, Predicate<Transaction> keep) {
        if (transactions.isEmpty()) return 0;
        var folded = matching(transactions, transaction -> transaction != null
                && transaction.timestamp < cutoffEpochMillis && (keep == null || !keep.test(transaction)));
        if (folded.isEmpty()) return 0; saveStamp++;
        var foldedIds = new HashSet<String>();
        for (var transaction : folded) {
            foldedIds.add(transaction.id()); transactions.remove(transaction); compactTransaction(transaction);
        }
        pruneHistories(foldedIds); return folded.size();
    }

    void pruneHistories(Set<String> foldedIds) {
        undoHistory.removeIf(record -> record != null && record.transaction != null
                                && foldedIds.contains(record.transaction.id()));
        correctionHistory.removeIf(record -> record != null && record.touchesAny(foldedIds));
    }
}

/**
 * AUTO sessions follow the detected activity; every session the engine opens (Overall and each
 * Grind) is AUTO. GENERAL is the plain default of a bare {@code new Session}.
 */
enum SessionMode {
    AUTO, GENERAL
}

/** Persisted ownership of a paused session. NONE means the session is running. */
enum PauseReason {
    NONE, MANUAL, IDLE, LIFECYCLE, RECOVERY, CUSTOM_SESSION, WORLD
}

/** A transaction removed through the undo action, kept so the latest one can be restored. */
class UndoRecord extends SaveCopies {
    Transaction transaction; boolean restored;

    UndoRecord() {
        // Gson
    }

    UndoRecord(Transaction transaction) {
        this.transaction = transaction;
    }
}

/** Only the canonical money, active clock and rate facts consumed by presentation and export. */
class Metrics {
    String activity; boolean paused;
    long elapsed, income, cost, net, rate, supplies, other; boolean split;

    static Metrics none() {
        return new Metrics(GENERAL, false, 0, 0, 0, 0, 0, 0, 0, false);
    }

    Metrics(String activity, boolean paused, long elapsed, long income, long cost, long net,
            long rate, long suppliesCosts, long otherCosts, boolean costSplitAvailable) {
        this.activity = activity; this.paused = paused; this.elapsed = elapsed; this.income = income;
        this.cost = cost; this.net = net; this.rate = rate; supplies = suppliesCosts;
        other = otherCosts; split = costSplitAvailable && sum(suppliesCosts, otherCosts) == cost;
    }
}

/**
 * Closed-session history management and receipt retention: UTC-day compaction sweeps, retention
 * status, profile size/data-health facts and the history session reads/edits. The history list
 * itself stays on the engine because owner lifecycle appends to it. Invoked only under the {@link
 * Engine} monitor.
 */
@RequiredArgsConstructor
class SessionArchive {
    /** UTC day of the last receipt-retention sweep; null before the first one this run. */
    LocalDate swept;

    final Engine engine;

    void clear() {
        swept = null;
    }

    /** Post-restore compaction with the configured window; it counts as today's sweep. */
    void compactAfterRestore(long now) {
        compactOlderThan(retentionDays(), now); swept = utcDay(now);
    }

    void adoptFrom(SessionArchive draft) {
        swept = draft.swept;
    }

    /** The configured receipt retention in days; 0 keeps receipts forever. */
    int retentionDays() {
        var period = engine.config.receiptRetentionDays(); return period == null ? Retention.DAYS_90.days : period.days;
    }

    static LocalDate utcDay(long now) {
        return Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate();
    }

    /**
     * Compacts closed sessions strictly older than {@code days}; a zero window means forever and
     * performs no compaction. The session metadata and all retained accounting aggregates remain
     * available.
     *
     * @return receipt rows compacted
     */
    int compactOlderThan(int days, long now) {
        if (days <= 0) return 0; long cut;
        try {
            cut = Instant.ofEpochMilli(now).minus(days, ChronoUnit.DAYS).toEpochMilli();
        } catch (RuntimeException ex) {
            cut = Long.MIN_VALUE;
        }
        // Rows still needed individually: an unresolved claim's audit row and the settlement that
        // carries a claim id keep their detail until the claim resolves (section L).
        var claims = engine.pendingClaimIds();
        Predicate<Transaction> keep = transaction -> needsReview(transaction) || isPendingClaimRow(transaction, claims)
                                || !transaction.claimId().isEmpty()
                                        && claims.contains(transaction.claimId());
        int compacted = 0;
        for (var session : engine.sessions()) {
            if (session == null || session.transactions().isEmpty()) continue;
            compacted = safeAddCount(compacted, session.compactBefore(cut, keep));
        }
        if (compacted > 0) engine.changed(); return compacted;
    }

    /**
     * A multi-key audit row owns claims named {@code auditId#n}; retain its base row while any
     * derived claim remains unresolved so provenance survives receipt compaction.
     */
    static boolean isPendingClaimRow(Transaction transaction, Set<String> claims) {
        if (transaction == null || empty(claims)) return false;
        String id = transaction.id(),
            derivedPrefix = id + "#";
        return claims.contains(id)
                || any(claims, claimId -> claimId != null && claimId.startsWith(derivedPrefix));
    }

    /**
     * The game tick's daily sweep: the first tick of each later UTC day compacts once. The first
     * tick of a run only marks the day, since a restore already swept.
     */
    int maintainRetention(long now) {
        var today = utcDay(now); if (swept != null && !today.isAfter(swept)) return 0;
        boolean first = swept == null; swept = today; return first ? 0 : compactOlderThan(retentionDays(), now);
    }

    Session historySession(String id) {
        return empty(id) ? null : firstMatch(engine.history, session -> id.equals(session.id()));
    }

    Metrics historyMetrics(String id, long now) {
        var session = historySession(id); return session == null ? null : session.metrics(now);
    }

    boolean deleteHistory(String id) {
        var session = historySession(id); if (session == null) return false; engine.history.remove(session); engine.changed();
        return true;
    }
}

/**
 * The one derived, read-only semantic financial projection every presentation surface shares.
 *
 * <p>It turns canonical transactions and the derived Market settlement context into living semantic
 * groups: repeated compatible activity updates one group, totals stay additive, and no group is
 * ever a correction identity. It never books, never reprices and never persists.
 *
 * <p>Grouping laws:
 *
 * <ul>
 *   <li>Exact named {@code Cast} groups key on the proven spell name; different names never merge
 *       and exact never merges with generic.
 *   <li>Generic action groups key on a canonical input-composition signature (action kind,
 *       financial category, sorted resource item ids, a scale-normalised quantity ratio where it is
 *       safely derivable, and price/coverage compatibility). The signature is presentation grouping
 *       only and is never rendered as a spell name; ambiguous composition splits instead of
 *       over-merging.
 *   <li>Measured charge receipts become their actual booked resource rows with a {@code Used by
 *       <equipment>} context. The equipment is never a financial row and no component ever carries
 *       the whole parent Net.
 *   <li>Action composites require pure counted cost contributions of one financial category: no
 *       Gain, Market, Transfer, Review, Corrected, split or claim-linked row may collapse into one.
 *   <li>Unpriced/incomplete groups stay in their financial table with an incomplete marker; only
 *       transactions that independently need an owner decision are review-required.
 *   <li>Sort truth is {@code latestActivityAt DESC} with a stable semantic-group-id tie-break.
 * </ul>
 */
@UtilityClass
class Semantics {
    final Comparator<Group> NEWEST_GROUP = comparingLong((Group group) -> group.at).reversed()
            .thenComparing(group -> group.id);

    /** Financial table destination for the future stacked Ledger and the Live consumer. */
    enum Table {
        GAINS, COSTS_SUPPLIES, COSTS_LOSS, MARKET, AUDIT, DEATH, KILL; final String label = msg("table-label." + name());
    }

    /** Value coverage: an incomplete group never presents itself as an exact headline. */
    enum Coverage {
        COMPLETE, INCOMPLETE
    }

    /**
     * One exact canonical contribution receipt behind a group; the only correction target layer.
     * Built once by the projection and shared by the Ledger tables, the receipt list and the
     * Corrected audit.
     */
    @AllArgsConstructor
    class Receipt {
        String id, transaction; long at;
        int item; String name;

        /**
         * Semantic magnitude where known; for measured charge spends this is the normalized
         * quantity.
         */
        long quantity;

        /**
         * Effective, correction-aware additive value; 0 when the receipt is neutral or unpriced.
         */
        long value;

        /** Raw automatic value, retained for audit and correction comparison. */
        long automaticValue;

        boolean unknown, neutral, review, corrected, split; Category category;
        String verb, action, why, correction, source;

        /**
         * Measured-charge equipment context ("Toxic blowpipe"), or empty for ordinary resources.
         */
        String uses;

        /**
         * Charges: the exact captured unit price and its source; empty for other receipts (F11).
         */
        String priceBasis;

        public Row market;

        /** Shop trades: the other side of the trade, named and counted ("Bought Death rune ×50"). */
        String tradedItems;

        /** The same list alone ("Death rune ×50"), for a currency entry row's name. */
        String traded; boolean bag;

        boolean gain() {
            return category == Category.GAIN;
        }

        /** The Corrected audit search; financial table search uses the projection group instead. */
        boolean matchesSearch(String needle) {
            return empty(needle) || containsFolded(lower(needle, false), name, verb, action,
                            source, why, correction, transaction, id);
        }
    }

    /**
     * One semantic presentation group; never a correction identity and never persisted. Each unit
     * of activity starts as a group; units sharing a merge key agree on everything the key encodes
     * (table, flags, consume shape), so the newest keeps those and later ones only add value, time,
     * quantity, receipts and search terms.
     */
    class Group extends ValueTotal {
        String id;

        /** Display item id for the row sprite; -1 for action composites. */
        int item;

        Table table; String name;

        /** The honest secondary line: "Used by Toxic blowpipe", "3 rune types", "Sell". */
        String context,

            label;

        /** Measured-charge equipment context ("Toxic blowpipe"), or empty for ordinary rows. */
        String uses;

        /** Measured or estimated charge use: the Losses Charges chip, never a Supplies row. */
        boolean charge;

        long at, value; Coverage coverage;

        /** Semantic quantity where honestly known; -1 when it cannot be stated. */
        long quantity;

        /** +1 claim pickup, -1 claim spend, 0 for an ordinary resource row. */
        int claim;

        boolean dose; int count = 1;
        boolean composite, neutral, market, review, corrected, death, pvpDeath; String marketId, transaction, contributionId;
        public Set<String> ids = new LinkedHashSet<>(); public List<Receipt> entries = new ArrayList<>();
        public Set<String> terms = new LinkedHashSet<>();

        void search(String... terms) {
            addAll(this.terms, terms);
        }

        /** Retain receipt order and searchable item names together. */
        void addReceipts(Transaction transaction, List<Contribution> parts) {
            for (var part : parts) {
                entries.add(receipt(transaction, part, null, null)); terms.add(part.name);
            }
        }

        int units = 1;

        /** Units of a production run's input its failures lost (a failed iron smelt's ore). */
        long failed;

        Group(String mergeKey, int identifier, Table table, String primary, String contextLine, String label,
                String usedBy, boolean chargeUse, long at, long value, Coverage coverage, long quantity,
                boolean normalizedConsume, boolean composite, boolean neutral, boolean market, boolean review,
                boolean corrected, String marketPresentationId, String id,
                String contributionId, int claim) {
            if (GROUP_IDS.size() > 50000) GROUP_IDS.clear();
            this.id = GROUP_IDS.computeIfAbsent(mergeKey, key -> "sg:" + UUID.nameUUIDFromBytes(
                                                    key.getBytes(StandardCharsets.UTF_8)));
            item = identifier; this.table = table; name = primary; context = contextLine;
            this.label = label; uses = usedBy; charge = chargeUse; this.at = at;
            add(value); this.value = value(); this.coverage = coverage; this.quantity = quantity;
            dose = normalizedConsume; this.composite = composite; this.neutral = neutral; this.market = market;
            this.review = review; this.corrected = corrected; marketId = marketPresentationId; transaction = id;
            this.contributionId = contributionId; this.claim = claim; ids.add(id.isEmpty() ? contributionId : id); ids.remove("");
        }

        /** Adds a later unit with the same merge key. */
        Group absorb(Group unit) {
            add(unit); value = value(); at = max(at, unit.at); coverage = unit.coverage == INCOMPLETE ? INCOMPLETE : coverage;
            quantity = quantity < 0 || unit.quantity < 0 ? -1 : sum(quantity, unit.quantity); units++;
            ids.addAll(unit.ids); count = ids.isEmpty() ? units : ids.size();
            entries.addAll(unit.entries); terms.addAll(unit.terms); failed += unit.failed;
            return this;
        }

        /** Owner 1.1: "16/28" when 16 of a run's 28 units failed; "" when none or all did. */
        String failedOf() {
            return failed > 0 && failed < quantity ? failed + "/" + quantity : "";
        }

        String failedTip() {
            return failed + " of " + quantity + " failed";
        }

        boolean incomplete() {
            return coverage == INCOMPLETE;
        }

        /** True when this group owns the given semantic group or contribution identity. */
        boolean containsContribution(String id) {
            return !empty(id) && (this.id.equals(id) || any(entries, receipt -> id.equals(receipt.id)));
        }

        /** True when this group represents the given canonical transaction identity. */
        boolean containsTransaction(String id) {
            return !empty(id) && (id.equals(transaction) || ids.contains(id)
                    || any(entries, receipt -> id.equals(receipt.transaction)));
        }

        /** True when this group is a composite row (action or note), not a resource row. */
        boolean actionGroup() {
            return composite && !market;
        }

        /**
         * Prepared for PRE-R5C.2B: text search decides whether a COMPLETE group matches by looking
         * at group metadata and underlying resources; a match keeps the full total.
         */
        boolean matchesText(String query) {
            if (query == null) return true; String needle = lower(query);
            return needle.isEmpty()
                    || any(terms, term -> term != null && lower(term, false).contains(needle));
        }
    }

    /** The full derived read: the groups, newest first. */
    class Result {
        List<Group> groups; Result audit;

        Result(List<Group> groups) {
            this.groups = unmodifiableList(groups);
        }
    }

    /**
     * Reconciled financial counts over one group set. `entries` counts financial groups across
     * Gains + Costs + Market (review-required and Audit rows excluded); `receipts` counts the
     * unique counted canonical receipt identities those entries represent (neutral transfers,
     * pending/unrealised Market and Review-only receipts are excluded).
     */
    @AllArgsConstructor
    class Summary extends ValueTotal {
        /** Sum of financial Gain groups (review-required rows excluded). */
        long income;

        /** Sum of financial cost groups, negative (review-required rows excluded). */
        long cost;

        /** Sum of realised Market results (review-required and unrealised rows excluded). */
        long market;

        int entries, receipts;

        /** True when every counted financial group had complete value coverage. */
        boolean coverageComplete;

        /** Canonical Net this summary accounts for when coverage is complete. */
        long net() {
            return value();
        }
    }

    /** Recoverable grave gear is neutral and never appears as a Recent/Ledger row. */
    boolean graveGear(Transaction transaction) {
        return List.of(msg("deathItems"), msg("deathReclaimItems"), msg("reclaimExpired")).contains(transaction.note());
    }

    /** Stable group ids by merge key; hashing is paid once per key, not on every tick. */
    final Map<String, String> GROUP_IDS = new ConcurrentHashMap<>();

    /** Multi-session scope: Today and future period consumers merge compatible activity. */
    Result capture(List<Transaction> transactions, List<Row> rows, Set<String> sessionIds,
            Predicate<Flow> td) {
        return capture(transactions, rows, sessionIds, td, false);
    }

    /** The explicit Corrected audit can still inspect removals and restore their original facts. */
    Result capture(List<Transaction> transactions, List<Row> rows, Set<String> sessionIds,
            Predicate<Flow> td, boolean includeRemoved) {
        Predicate<Flow> visibility = td == null ? flow -> true : td; var units = new ArrayList<Group>();
        var representedSettlements = new LinkedHashSet<String>(); var receipts = new LinkedHashMap<String, Transaction>();
        if (transactions != null)
            for (var transaction : transactions)
                if (transaction != null) receipts.put(transaction.id(), transaction);


        if (rows != null)
            for (Row market : rows) {
                if (market == null || sessionIds == null || !sessionIds.contains(market.scopeSessionId)) continue;
                // Every collect of the offer folds into its one market row (owner 2026-10-09).
                var settlements = new ArrayList<Transaction>();
                for (String id : market.receiptIds()) {
                    if (receipts.containsKey(id)) settlements.add(receipts.get(id));
                }
                representedSettlements.addAll(market.receiptIds());
                if (!includeRemoved && market.realized() && !settlements.isEmpty()
                        && settlements.stream().allMatch(row -> row.correction() == IGNORE)) continue;
                if (!includeRemoved) settlements.removeIf(row -> row.correction() == IGNORE);
                units.add(marketUnit(market, settlements));
            }

        if (transactions != null)
            for (var transaction : transactions) {
                // An uncounted trade is custody's audit copy: its market row already shows the sale
                // (owner 2026-09-28: a second fill read "Bones ×2 · 0").
                if (transaction == null || representedSettlements.contains(transaction.id())
                        || transaction.type() == TRADE && !transaction.counts()
                        || graveGear(transaction))
                    continue;
                units.addAll(includeRemoved ? receiptUnits(transaction, visibility)
                        : transactionUnits(transaction, visibility));
            }

        bondRows(units, receipts);
        units.sort(comparingLong((Group unit) -> unit.at).reversed()
                        .thenComparing(unit -> unit.contributionId));
        var buckets = new LinkedHashMap<String, Group>(); for (Group unit : units) buckets.merge(unit.id, unit, Group::absorb);
        var groups = copiedList(buckets.values());
        for (var group : groups)
            if (group.pvpDeath && group.value < 0 && !group.incomplete() && !group.review)
                for (int cap : SKULL_TIERS) if (abs(group.value) > cap) group.item--;
        groups.sort(Semantics.NEWEST_GROUP); return new Result(groups);
    }

    /** Presentation-only: a conversion fee reads as the bond it was paid for, not as Coins. */
    static void bondRows(List<Group> units, Map<String, Transaction> receipts) {
        for (var unit : units) {
            var transaction = receipts.get(unit.transaction);
            if (transaction != null && msg("bond-fee").equals(transaction.note())) {
                unit.item = OSRS_BOND_UNTRADEABLE; unit.name = "Old school bond (untradeable)"; unit.quantity = 0;
            }
        }
    }

    final int[] SKULL_TIERS = intColumn("pvp_skulls", 0);

    /** Reconciled counts over any group set (the search-filtered Ledger tables reuse this). */
    Summary summarize(Collection<Group> groups) {
        var income = new ValueTotal(); var cost = new ValueTotal();
        var marketTotal = new ValueTotal(); int entries = 0;
        boolean complete = true; var records = new LinkedHashSet<String>();
        if (groups == null) return new Summary(0, 0, 0, 0, 0, true);
        for (Group group : groups) {
            if (group.review || group.neutral || group.table == AUDIT) continue;
            if (group.table == Semantics.Table.MARKET) {
                if (group.coverage != COMPLETE) {
                    // Pending/unrealised Market is context, not a counted financial entry.
                    complete = false; continue;
                }
                marketTotal.add(group);
            } else if (group.table == GAINS)
                income.add(group);
            else if (group.table == COSTS_SUPPLIES || group.table == COSTS_LOSS)
                cost.add(group);
            else
                continue;
            if (group.coverage == INCOMPLETE) complete = false;
            if (!group.corrected) {
                entries++; records.addAll(group.ids);
            }
        }
        var summary = new Summary(income.value(), cost.value(), marketTotal.value(), entries, records.size(), complete);
        summary.add(income); summary.add(cost); summary.add(marketTotal);
        return summary;
    }

    // ── unit construction ──────────────────────────────────────────────────────────────────────
    /**
     * A composite row's group: claim-spend, action and charge-cast units share the fixed tail (no
     * normalised consume, no market lifecycle, no claim count). Field order mirrors the
     * constructor's, so a transposition reads wrong at a glance.
     */
    Group composite(String key, int identifier, Table table, String primary,
            String contextLine, String label, boolean chargeUse, long at, long value,
            boolean unknown, long quantity, boolean neutral, boolean review, boolean corrected,
            String transactionId, String id) {
        return new Group(key, identifier, table, primary, contextLine, label, "", chargeUse,
                at, value, coverage(unknown), quantity, false, true, neutral, false, review,
                corrected, "", transactionId, id, 0);
    }

    List<Group> transactionUnits(Transaction transaction, Predicate<Flow> td) {
        return transaction.correction() == IGNORE ? emptyList() : receiptUnits(transaction, td);
    }

    List<Group> receiptUnits(Transaction transaction, Predicate<Flow> td) {
        var type = transaction.original(); var id = transaction.id();
        var action = transaction.action(); var flows = transaction.flows();
        boolean neutral = transaction.isTransfer(), review = needsReview(transaction),
            fixed = transaction.correction() != AUTO, split = Contribution.isSplit(transaction),
            claimLinked = !transaction.claimId().isEmpty()
                        || action == ActionKind.DEFERRED_CLAIM;
        long at = transaction.timestamp; String source = Contribution.sourceOf(transaction);
        if (flows.isEmpty()) return noteUnits(transaction, neutral, review, fixed, at, source);
        var projected = Contribution.project(transaction);
        // An opened claim's proceeds are the action's income: a loot minimum never hides them.
        boolean claimSpend = any(projected, contribution ->
                        contribution.source == PriceSource.DEFERRED_CLAIM && contribution.quantityDelta < 0);
        var visible = matching(projected, part -> any(part.flows, td) || neutral || review || claimSpend || part.unpriced());
        if (visible.isEmpty()) return emptyList();
        // An opened claim (a coin pouch, an unopened key) is one action row: the claim count and
        // the net it yielded, never a claim leg plus separate proceeds (owner 2026-09-29).
        if (claimSpend && !neutral && !review && !fixed && !split) {
            long spent = 0, net = 0; boolean unknown = false, claimOnly = true;
            String name = null; int icon = -1;
            for (var entry : visible) {
                long delta = entry.quantityDelta;
                if (delta < 0) {
                    if (entry.source != PriceSource.DEFERRED_CLAIM) {
                        claimOnly = false; break;
                    }
                    spent -= delta;
                    if (name == null) {
                        name = entry.name; icon = entry.item;
                    } else if (!name.equals(entry.name)) {
                        name = ""; icon = -1;
                    }
                } else if (delta > 0) {
                    net = sum(net, entry.value); unknown |= entry.unpriced();
                }
            }
            if (claimOnly && spent > 0 && net > 0) {
                String verb = verbOf(transaction), primary = empty(name) ? verb : name,
                    key = "claim:" + primary + ":" + Long.signum(net) + ":" + coverageKey(unknown) + ":" + verb
                                + ":" + type.name();
                var unit = new Group(key, icon, GAINS, primary, "", verb, "", false, at, net, coverage(unknown),
                                spent, false, false, false, false, false, false, "", id,
                                visible.get(0).id, -1);
                unit.search(primary, verb, source, id); unit.addReceipts(transaction, visible); return singletonList(unit);
            }
        }
        boolean death = type == PK_DEATH_LOSS || transaction.note().equals(msg("pvmLoss")),
            grave = neutral && List.of(msg("deathItems"), msg("deathReclaimItems")).contains(transaction.note());
        // Legacy reclaim receipts already have this reserved activity and exact coin basis.
        boolean reclaim = type == CONSUMPTION && "Death reclaim".equals(transaction.activityName())
                        && transaction.explanation().equals(transaction.note())
                        && flows.stream().allMatch(f -> f.cost() && f.item == 995 && f.source() == FACE_VALUE);
        if (action == FEE || death || grave || reclaim) {
            long feeValue = 0; boolean missingFee = false;
            for (var contribution : visible) {
                missingFee |= contribution.unpriced(); feeValue = sum(feeValue, contribution.value);
            }
            String feeName = death ? type == PK_DEATH_LOSS ? "Death: PvP" : msg("pvmLoss")
                            : grave ? "Gravestone" : transaction.note(),
                key = death ? "death:" + (empty(transaction.encounterId()) ? id : transaction.encounterId())
                            + ":" + transaction.correction() + ":" + review
                            : grave ? "grave:" + id
                            : "fee:" + reclaim + ":" + feeName + ":" + coverageKey(missingFee) + ":" + Long.signum(feeValue);
            var unit = composite(key,
                            death ? -100000 : grave ? -1 : visible.get(0).item, tableFor(financialCategory(transaction, visible.get(0))), feeName,
                            grave ? transaction.note().equals(msg("deathItems")) ? "Stored" : "Recovered" : "",
                            "", false, at, feeValue, missingFee, -1, neutral, review, fixed,
                            id, visible.get(0).id);
            unit.composite = !death && !grave; unit.death = death || grave || reclaim;
            unit.pvpDeath = type == PK_DEATH_LOSS; unit.addReceipts(transaction, visible); unit.search(feeName, source, id);
            return singletonList(unit);
        }
        boolean groupable = !neutral && !review && !fixed && !split && !claimLinked;
        var recipe = groupable && type == PROCESSING && transaction.context() == PRODUCTION && visible.size() == flows.size()
                        && visible.stream().allMatch(entry -> entry.counted && !entry.auditOnly && !entry.unpriced())
                        ? ProductionRecipes.match(flows) : null;
        if (recipe != null)
            return singletonList(actionUnit(transaction, visible, source, at,
                            firstMatch(flows, flow -> flow.item == parseInt(recipe[0]))));
        // An estimated charge use is one row named by the weapon.
        String note = transaction.note(), prefix = msg("estimatedCharge"),
            castName = note.startsWith(prefix) ? note.substring(prefix.length()).trim() : "",
            weapon = castName;
        if (!castName.isEmpty()) {
            // A worn charge feeder (blood fury) is no cast: its estimate books the charged
            // resource "Used by" it — the same shape its measured Check produces (owner
            // 2026-09-29).
            var feeder = ChargeCheck.namedVariant(castName);
            // Owner 1.1: the blowpipe's rows are its Zulrah's scales (Charges) and darts
            // (Supplies), the resource rows below; blood fury stays one "Blood fury" row, as a
            // shard covers 10,000 hits.
            if (feeder != ChargeCheck.ChargedItem.EYE_OF_AYAK && feeder != ChargeCheck.ChargedItem.BLOWPIPE)
                return singletonList(chargeCastUnit(transaction, visible, castName, source, at, neutral, review,
                                fixed));
        } else {
            // A measured charge spend books its resources as rows "Used by" the weapon, never a
            // composite.
            weapon = action == FIRE || action == CAST ? measuredChargeWeaponName(transaction) : "";
            if (ChargeCheck.namedVariant(weapon) == ChargeCheck.ChargedItem.BLOOD_FURY)
                return singletonList(chargeCastUnit(transaction, visible, weapon, source, at, neutral, review, fixed));
            if (groupable && weapon.isEmpty() && isActionComposite(transaction, visible))
                return singletonList(actionUnit(transaction, visible, source, at, null));
        }
        String usedWeapon = weapon;
        return list(visible, contribution -> resourceUnit(transaction, contribution,
                        ChargeCheck.DARTS.containsValue(contribution.item) ? "" : usedWeapon,
                        neutral, review, fixed, source, at));
    }

    /**
     * A safe action composite: pure counted costs of one financial category from one spell cast or
     * one shot. Other actions (a sapling planted with its payment) stay per-item rows, as do mixed
     * gain+cost, Market, Transfer, Review, Corrected, split and claim-linked rows.
     */
    boolean isActionComposite(Transaction transaction, List<Contribution> entries) {
        var action = transaction.action();
        if (action != CAST && action != FIRE && action != DRINK || entries.size() < 2) return false;
        // A named spell with a product (an alch's coins, Bones to Peaches' fruit) is one row by
        // its spell; a gain it does not make (a pickup) keeps the rows apart (owner live
        // 2026-10-16). Any other action keeps one shared cost category.
        var made = action == CAST ? product(transaction.spellName())
                        : emptySet();
        boolean product = !made.isEmpty(); var category = financialCategory(transaction, entries.get(0));
        if (!product && category != Category.SUPPLY && category != Category.LOSS)
            return false;
        return !any(entries, entry -> {
            var each = financialCategory(transaction, entry);
            return (product ? entry.quantityDelta > 0 ? !made.contains(entry.item)
                                    : each != Category.SUPPLY && each != Category.LOSS : each != category)
                    || !entry.counted || entry.auditOnly;
        });
    }

    Group actionUnit(Transaction transaction, List<Contribution> entries, String source, long at, Flow product) {
        var category = financialCategory(transaction, entries.get(0)); boolean unknown = false, mixed = false;
        var money = new ValueTotal();
        for (var entry : entries) {
            unknown |= entry.unpriced(); mixed |= financialCategory(transaction, entry) != category;
            money.add(entry.value);
        }
        long value = money.value();
        // A spell with a product sits by its net: an alch that made coins is a gain.
        if (mixed) category = value > 0 ? Category.GAIN : Category.SUPPLY;
        String exactName = product == null ? transaction.spellName() : product.name,
                        verb = product == null ? verbOf(transaction) : "",
            primary = exactName.isEmpty() ? verb : exactName, context = product == null ? compositionText(transaction, entries) : "",
            key = (product == null ? "action:" + transaction.action().wireName : "production:" + product.item)
                        + (exactName.isEmpty() ? ":generic=" + compositionKey(entries) : ":exact=" + exactName)
                        + ":cov=" + coverageKey(unknown) + ":" + category + ":" + Long.signum(value);
        // A row named by a spell or an item shows its icon; any other shows its first item's.
        int icon = product == null ? icon(primary) : product.item;
        var unit = composite(key, icon != -1 ? icon : entries.get(0).item, tableFor(category),
                        primary, context, verb, false, at, value, unknown, product == null ? -1 : product.quantity, false, false,
                        false, transaction.id(), entries.get(0).id);
        unit.composite = product == null; unit.positive = money.positive; unit.negative = money.negative;
        unit.search(primary, context, verb, source, transaction.id()); unit.addReceipts(transaction, entries);
        return unit;
    }

    /** One resource row; with a weapon it is a measured charge spend "Used by" that weapon. */
    Group resourceUnit(Transaction transaction, Contribution entry, String weapon, boolean neutral,
            boolean review, boolean corrected, String source, long at) {
        var category = financialCategory(transaction, entry); boolean unknown = entry.unpriced();
        // An unopened claim (a key or a coin pouch) is held at zero in Recent, never shown as a
        // value there; the Ledger's Pending line shows an observed open chest's worth instead.
        int claimSign = entry.source == PriceSource.DEFERRED_CLAIM
                        ? Long.signum(entry.quantityDelta) : 0;
        long value = neutral || claimSign != 0 || entry.auditOnly ? 0 : entry.value;
        String verb = verbOf(transaction), label = entry.action,
            actionLabel = weapon.isEmpty() ? label.isEmpty() ? verb : verb + SEPARATOR + label : "",
            context = weapon.isEmpty() ? actionLabel : "Used by " + weapon;
        // Owner 1.1: a production run's input and the same item its failures lost are one row.
        boolean run = transaction.context() == PRODUCTION && weapon.isEmpty()
                        && entry.quantityDelta < 0;
        // A confirmed NPC pickup and an inventory-only pickup share an item row. Their receipts
        // retain the original evidence; actions, corrections, Review and other types stay distinct.
        var groupingType = category == Category.GAIN && !review && !corrected
                        && !Contribution.isSplit(transaction) && transaction.action() == null
                        && transaction.original() == TransactionType.LOOT ? GAIN : transaction.original();
        String key = run ? "run:" + tableFor(category) + ":" + entry.item + ":" + coverageKey(unknown) + ":"
                                + review + ":" + corrected + ":" + neutral
                        : (weapon.isEmpty() ? "res:" : "charge:" + weapon + ":") + tableFor(category) + ":"
                                // Doses of one potion share a row whichever dose each came from
                                // (owner 2026-09-28).
                                + (entry.dose ? entry.name : entry.item) + ":"
                                + Long.signum(value) + ":" + coverageKey(unknown) + ":"
                                + entry.source.name() + ":" + label + ":" + verb + ":"
                                + groupingType.name() + ":"
                                + (entry.dose ? "norm" : "raw") + ":" + (review ? "review" : "auto")
                                + ":" + (corrected ? "corrected" : "auto") + ":"
                                + (neutral || claimSign != 0 ? "neutral" : "counted");
        var unit = new Group(key, entry.item, tableFor(category), entry.name, context, actionLabel,
                        weapon, !weapon.isEmpty(), at, value, coverage(unknown), semanticQuantity(entry),
                        entry.dose, false, neutral || claimSign != 0, false, review, corrected, "",
                        transaction.id(), entry.id, claimSign);
        unit.entries.add(receipt(transaction, entry, weapon, null));
        if (run && verb.equals(msg("failed"))) unit.failed = positive(unit.quantity);
        unit.search(entry.name, context, verb, label, weapon, source, transaction.id()); return unit;
    }

    List<Group> noteUnits(Transaction transaction, boolean neutral, boolean review, boolean corrected, long at,
            String source) {
        var id = transaction.id(); var money = transaction(transaction);
        if (!money.included && !review && !neutral) return emptyList(); long value = neutral ? 0 : money.net();
        // A flow-less note receipt: its own included Net decides the direction.
        var category = neutral || !money.included ? Category.AUDIT : money.net() > 0 ? Category.GAIN
                        : transaction.original() == TRADE ? Category.MARKET : Category.LOSS;
        String name = transaction.note().isEmpty() ? transaction.activityName() : transaction.note(),
            verb = verbOf(transaction), key = "note:" + id;
        var unit = composite(key, -1, tableFor(category), name, verb, verb, false, at, value, false, 0,
                        neutral, review, corrected, id, id + ":composite");
        unit.death = neutral && name.equals(msg("reclaimExpired"));
        unit.entries.add(new Receipt(id + ":composite", id, at, -1, name, 0, value,
                        transaction.autoNet(), false, neutral, review, corrected, Contribution.isSplit(transaction),
                        category, verb, transaction.spellName(), why(transaction, false, neutral),
                        correctionLabel(transaction.correction()), source, "", "", null, "", "", false));
        unit.search(name, verb, source, id); return singletonList(unit);
    }

    Group marketUnit(Row market, List<Transaction> settlements) {
        boolean realized = market.realized() && market.realizedResultCorrectionAware;
        // Unknown-basis liquidation has no available Result: never show 0 as if break-even were
        // known. Its one exact contribution is a proven GE tax, which stays a market cost.
        boolean knownResult = realized && (market.coverage != MarketRows.Coverage.FULLY_UNKNOWN
                                || market.manualFinancialResult),
            knownCost = realized && market.knownCostOnly, knownContribution = knownResult || knownCost;
        // A sale whose receipt was compacted is history: its money is in the retained totals and
        // there is nothing left to decide, so it shows "result unavailable", never Review.
        boolean review = market.lifecycle == MarketRows.Lifecycle.AMBIGUOUS || market.lifecycle == UNAVAILABLE;
        String side = market.side.name().equals("SELL") ? "Sell" : "Buy",
            status = knownResult ? side : knownCost ? side + msg("taxCounted") : market.realized()
                                        ? side + msg("resultUnavailable") : lifecycleLabel(market);
        long quantity = market.savedNet != null ? market.savedQuantity
                        : market.realized() ? market.settledQty : market.filledQty;
        String id = market.presentationId, key = "market:" + id;
        var unit = new Group(key, market.itemId, Semantics.Table.MARKET, market.itemName, status, "", "", false,
                        market.timestampEpochMillis, market.savedNet != null ? market.savedNet
                                        : knownContribution ? market.realizedResultGp : 0,
                        knownContribution ? COMPLETE : INCOMPLETE, quantity, false, true, false, true,
                        review, false, id, market.settlementId, "market:" + id, 0);
        unit.search(market.itemName, status, side, market.lifecycle.name(), market.side.name(), id);
        if (!market.settlementId.isEmpty()) unit.terms.add(market.settlementId);
        // D2: a market group owns only its sale item's receipt. Its settlement's Coins leg is never
        // listed as a separate receipt or grouped with an unclaimed row.
        for (var batch : settlements)
            for (var entry : Contribution.project(batch))
                if (entry.item == market.itemId)
                    unit.entries.add(receipt(batch, entry, null, market));


        return unit;
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────
    Receipt receipt(Transaction transaction, Contribution entry, String usedBy, Row marketSettlement) {
        boolean neutral = entry.category == Category.AUDIT && transaction.isTransfer(),
            review = transaction.correction() == AUTO && (entry.needsReview
                                || needsReview(transaction)),
            valueKnown = !neutral && !entry.auditOnly;
        var category = entry.category; String[] traded = tradeParts(transaction, entry);
        return new Receipt(entry.id, transaction.id(), entry.at,
                entry.item, entry.name, semanticQuantity(entry),
                valueKnown ? entry.value : 0, entry.valueDelta, entry.unpriced(),
                neutral, review, entry.corrected, entry.split, category, verbOf(transaction),
                entry.action, why(transaction, entry.unpriced(), neutral),
                correctionLabel(transaction.correction()), Contribution.sourceOf(transaction),
                string(usedBy), chargeBasis(transaction, entry), marketSettlement,
                traded == null ? "" : traded[0] + " " + traded[1], traded == null ? "" : traded[1],
                transaction.original() == PK_DEATH_LOSS && transaction.note().equals(msg("bagLoss")));
    }

    long semanticQuantity(Contribution entry) {
        return entry.dose ? entry.normalizedQuantity : abs(entry.quantityDelta);
    }

    /** Display verb for a transaction: action kind first, then split/type direction. */
    String verbOf(Transaction transaction) {
        if ("Dropped".equals(transaction.note())) return "Dropped";
        if (transaction.action() != null) return actionVerb(transaction.action());
        if (Contribution.isSplit(transaction) && transaction.correction() == AUTO)
            return "Split";
        return transaction.type() == CONSUMPTION && transaction.context() == PRODUCTION ? msg("failed") : shopExchange(transaction) ? msg("verbExchanged") : msg("verb." + transaction.type(), transaction.net() < 0 ? "Used" : "Received");
    }

    String actionVerb(ActionKind kind) {
        return msg("action." + kind, "Used");
    }

    /** Why a receipt counted, told from stored records only. */
    String why(Transaction transaction, boolean unknown, boolean neutral) {
        if (transaction.correction() != AUTO)
            return msg("why-correction." + transaction.correction());
        if (neutral) return msg("ownershipNeutralMove"); if (unknown) return msg("priceKnown");
        if (!transaction.flows().isEmpty()
                && transaction.flows().stream().allMatch(flow -> flow.source() == NO_VALUE))
            return msg("noValue");
        String note = transaction.note();
        // Owner 2026-10-01 (F11): a Charges receipt names its retained confidence.
        if (note.startsWith(msg("estimatedCharge")))
            return msg("estimatedLocal");
        if (note.startsWith(msg("measuredCharge")))
            return msg("measuredExact");
        return needsReview(transaction) ? msg("awaitingDecision") : transaction.type() == CONSUMPTION && transaction.context() == PRODUCTION ? msg("failedInputs") : msg("why-type." + transaction.type(),
                transaction.counts() ? msg("automaticCount") : "Not counted.");
    }

    /**
     * The claimed shop click behind a trade's note ("buy"/"sell"), else empty. Player trades and
     * the Grand Exchange keep their own vocabulary.
     */
    String shopClick(Transaction transaction) {
        if (transaction == null || transaction.type() != TRADE) return "";
        String note = lower(transaction.note()); return note.startsWith("buy") ? "buy" : note.startsWith("sell") ? "sell" : "";
    }

    /** A shop trade that reads "Exchanged": a claimed shop click paid in a non-coin currency. */
    boolean shopExchange(Transaction transaction) {
        // Paid in a currency other than coins (tokkul, spirit flakes, …).
        var cash = pattern("cash");
        return !shopClick(transaction).isEmpty() && transaction != null && any(transaction.flows(), flow -> CURRENCIES
                        .contains(flow.item) || !"coins".equals(lower(flow.name)) && cash.matcher(lower(flow.name)).matches());
    }

    /**
     * For a shop trade entry: the other side of the trade, named and counted, from the claimed
     * click ("buy"/"sell"). A tokkul spend entry reads "Bought Death rune ×50"; the rune's own
     * entry reads "Paid Tokkul ×14,550". Returns {"Bought", "Death rune ×50"} or null.
     */
    String[] tradeParts(Transaction transaction, Contribution self) {
        if (self == null) return null; String click = shopClick(transaction); if (click.isEmpty()) return null;
        boolean buying = "buy".equals(click); Map<Integer, String> names = new LinkedHashMap<>();
        var counts = orderedNumberMap(); boolean gain = false, anyCost = false;
        for (var item : Contribution.project(transaction)) {
            if (item == null || item.id.equals(self.id)) continue;
            long signed = item.dose ? item.normalizedQuantity : item.quantityDelta; if (signed == 0) continue;
            gain |= signed > 0; anyCost |= signed < 0;
            names.putIfAbsent(item.item, item.name); counts.merge(item.item, Math.abs(signed), Long::sum);
        }
        if (counts.isEmpty()) return null; var text = new StringBuilder();
        for (var entry : counts.entrySet()) appendItem(text, names.get(entry.getKey()), entry.getValue());
        String label = buying ? gain && !anyCost ? msg("purchaseLabel") : msg("tradedPaid")
                        : gain && !anyCost ? msg("saleUnbased") : msg("saleLabel");
        return new String[] {label, text.toString()};
    }

    /**
     * Charges: the exact captured unit price and its source, or "unpriced". Retained flow facts
     * only - never re-derived from the value (owner 2026-10-01, F11).
     */
    String chargeBasis(Transaction transaction, Contribution entry) {
        String note = transaction.note();
        if (!note.startsWith(msg("estimatedCharge")) && !note.startsWith(msg("measuredCharge")))
            return "";
        if (entry.unpriced() || entry.flows.isEmpty()
                || entry.flows.get(0).unitPrice <= 0)
            return "unpriced";
        Flow flow = entry.flows.get(0); return exact(flow.unitPrice) + msg("gp") + flow.source();
    }

    String correctionLabel(Correction decision) {
        return msg("counted-as." + decision, "Automatic");
    }

    /** One estimated charge cast: the weapon names a single cast row, like a spell. */
    Group chargeCastUnit(Transaction transaction, List<Contribution> entries, String weapon, String source,
            long at, boolean neutral, boolean review, boolean corrected) {
        boolean unknown = false; long value = 0;
        for (var entry : entries) {
            unknown |= entry.unpriced(); value = sum(value, entry.value);
        }
        String verb = verbOf(transaction), context = compositionText(transaction, entries),
            key = "chargecast:" + weapon + ":" + coverageKey(unknown) + ":" + (neutral ? "neutral" : "counted")
                        + ":" + (review ? "review" : "auto") + ":" + (corrected ? "corrected" : "auto");
        int icon = transaction.castItemId;
        if (icon <= 0 && entries.size() == 1)
            // Owner live 2026-10-13: a charge row wears what it actually spent (the vial, the
            // rune), so a charged weapon never looks like a duplicate of its own loot row. A
            // cast spending several component types has no single "what it spent": it wears the
            // weapon (owner live 2026-10-14, the amulet's law rune read as a mismatch).
            icon = entries.get(0).item;
        if (icon <= 0) {
            // The weapon hint is transient; a restart must still draw the weapon sprite,
            // including the scythe, whose Check may not have been read yet.
            var variant = ChargeCheck.namedVariant(weapon); if (variant != null) icon = variant.itemIds[0];
        }
        var unit = composite(key, icon, tableFor(financialCategory(transaction, entries.get(0))),
                        weapon, context, verb, true, at, value, unknown, 1, neutral, review,
                        corrected, transaction.id(), entries.get(0).id);
        unit.addReceipts(transaction, entries); unit.search(weapon, context, verb, source, transaction.id());
        return unit;
    }

    String measuredChargeWeaponName(Transaction transaction) {
        String note = transaction.note(), prefix = msg("measuredCharge"); if (!note.startsWith(prefix)) return "";
        String weapon = note.substring(prefix.length()).trim();
        return weapon.length() > 80 ? weapon.substring(0, 80).trim() : weapon;
    }

    String compositionText(Transaction transaction, List<Contribution> entries) {
        boolean allRunes = true; var runeTypes = new LinkedHashSet<Integer>();
        for (var entry : entries) {
            String name = lower(entry.name, false); if (!name.endsWith(" rune")) allRunes = false; else runeTypes.add(entry.item);
        }
        return transaction.action() == CAST && allRunes ? runeTypes.size() + " rune types" : entries.size() + (transaction.action() == CAST ? " components" : " items");
    }

    /**
     * Presentation grouping signature for generic action composites: sorted resource ids with a
     * GCD-normalised quantity ratio when every quantity is known, else raw quantities (split rather
     * than over-merge), plus the price source so priced/incomplete shapes stay distinct.
     */
    String compositionKey(List<Contribution> entries) {
        long gcd = 0; boolean normalisable = true;
        for (var part : entries) {
            long quantity = abs(part.quantityDelta);
            if (quantity <= 0) {
                normalisable = false; break;
            }
            gcd = gcd(gcd, quantity);
        }
        var sorted = copiedList(entries);
        sorted.sort(comparingInt((Contribution item) -> item.item)
                        .thenComparing(itemData -> itemData.id));
        var parts = new ArrayList<String>(sorted.size());
        for (var entry : sorted) {
            long quantity = abs(entry.quantityDelta), ratio = normalisable && gcd > 0 ? quantity / gcd : quantity;
            parts.add(entry.item + ":" + ratio + ":" + (entry.unpriced() ? "unpriced"
                                    : entry.source.name()));
        }
        return String.join(",", parts);
    }

    long gcd(long left, long right) {
        long a = Math.abs(left), b = Math.abs(right);
        while (b != 0) {
            long next = a % b; a = b; b = next;
        }
        return a;
    }

    /**
     * The financial destination of one contribution. A zero-value financial movement (an unpriced
     * cost or gain) is routed by its direction and positive evidence — never to Audit — so an
     * unpriced Supply stays in Costs/Supplies with an incomplete marker.
     */
    Category financialCategory(Transaction transaction, Contribution entry) {
        var category = entry.category; if (category != Category.AUDIT) return category;
        if (entry.auditOnly || transaction.isTransfer())
            return Category.AUDIT;
        long quantity = entry.quantityDelta; if (quantity > 0) return Category.GAIN; if (quantity == 0) return Category.AUDIT;
        var type = transaction.original(); return type == TRADE ? Category.MARKET : type == PK_DEATH_LOSS || type == PK_SUPPLY_COST || type == PROCESSING
                || transaction.action() != null ? type == PK_DEATH_LOSS ? Category.LOSS : Category.SUPPLY : Category.LOSS;
    }

    Table tableFor(Category category) {
        return Table.valueOf(msg("category-table." + category.name()));
    }

    String coverageKey(boolean unknown) {
        return unknown ? "incomplete" : "complete";
    }

    Coverage coverage(boolean unknown) {
        return unknown ? INCOMPLETE : COMPLETE;
    }

    String lifecycleLabel(Row market) {
        return String.format(MarketText.wording(market, "label", "@detail-unavailable"),
                        market.offeredQty, market.filledQty);
    }
}

/**
 * One correction-aware, read-only financial contribution derived from a retained Flow. The parent
 * transaction remains the only correction identity and accounting authority.
 */
class Contribution {
    enum Category {
        GAIN, SUPPLY, LOSS, MARKET, AUDIT
    }

    String id; long at;
    int flowOrdinal, item; String name;
    long quantityDelta;

    /** Raw automatic Flow value, retained for audit and correction comparison. */
    long valueDelta;

    /** Effective, correction-aware additive value used by financial presentation. */
    long value;

    PriceSource source; boolean counted;
    Category category;

    /** Transient exact action label captured from trusted client evidence; display/search only. */
    String action;

    boolean needsReview, corrected, split, auditOnly, dose; long normalizedQuantity;
    List<Flow> flows;

    Contribution(Transaction transaction, int flowOrdinal, int id, String name, long delta,
            long value, long effectiveValue, PriceSource source, Category category, boolean needsReview,
            boolean auditOnly, boolean normalizedConsume, long normalizedQuantity, List<Flow> rawFlows) {
        this.id = transaction.id() + (normalizedConsume ? ":consume:" + flowOrdinal : ":flow:" + flowOrdinal);
        at = transaction.timestamp; this.flowOrdinal = flowOrdinal; item = id; this.name = fallback(name, "Unknown item");
        quantityDelta = delta; valueDelta = value; this.value = effectiveValue; this.source = source == null ? UNKNOWN : source;
        counted = transaction.counts(); this.category = category == null ? Category.AUDIT : category;
        action = transaction.spellName(); this.needsReview = needsReview;
        corrected = transaction.correction() != AUTO; split = isSplit(transaction);
        this.auditOnly = auditOnly; dose = normalizedConsume;
        this.normalizedQuantity = normalizedQuantity; flows = frozenList(rawFlows);
    }

    static Contribution from(Transaction transaction, Flow flow, int ordinal) {
        if (transaction == null || flow == null) return null; var money = flow(transaction, flow);
        long effective = money.included ? money.net() : 0; var category = category(transaction, flow, money);
        return new Contribution(transaction, ordinal, flow.item, flow.name, flow.quantity, flow.value,
                effective, flow.source(), category, reviewState(transaction, flow), !money.included, false,
                abs(flow.quantity), singletonList(flow));
    }

    /**
     * Project one receipt into exact per-flow rows, coalescing only proven full→partial consume
     * pairs. Raw flows are retained on normalized rows for detail and audit.
     */
    static List<Contribution> project(Transaction transaction) {
        return transaction == null ? emptyList() : transaction.memo(10, () -> build(transaction));
    }

    static List<Contribution> build(Transaction transaction) {
        var flows = transaction.flows(); if (empty(flows)) return emptyList(); var result = new ArrayList<Contribution>();
        var consumed = new HashSet<Integer>();
        for (var pair : consumePairs(transaction)) {
            Flow loss = flows.get(pair.lossIndex), rest = flows.get(pair.remainder); var lossAmounts = flow(transaction, loss);
            var remainderAmounts = flow(transaction, rest); long effective = sum(lossAmounts.net(), remainderAmounts.net());
            if (!lossAmounts.included || !remainderAmounts.included || effective > 0) continue;
            int ordinal = min(pair.lossIndex, pair.remainder);
            result.add(new Contribution(transaction, ordinal, loss.item, normalizedName(loss.name), -min(
                                    abs(loss.quantity), abs(rest.quantity)),
                            sum(loss.value, rest.value), effective, loss.source(),
                            Category.SUPPLY, reviewState(transaction, loss), false, true, min(
                                    abs(loss.quantity), abs(rest.quantity)),
                            List.of(loss, rest)));
            consumed.add(pair.lossIndex); consumed.add(pair.remainder);
        }
        for (int i = 0; i < flows.size(); i++) {
            if (consumed.contains(i)) continue; Flow flow = flows.get(i);
            var money = flow(transaction, flow);
            // The last dose drunk, its vial smashed, is one more dose of the same potion.
            if (transaction.action() == DRINK && transaction.correction() == AUTO && money.included
                    && (transaction.type() == CONSUMPTION
                            || transaction.type() == PK_SUPPLY_COST) && flow.quantity < 0
                    && dose(flow.name) == 1) {
                long doses = abs(flow.quantity);
                result.add(new Contribution(transaction, i, flow.item, normalizedName(flow.name), -doses,
                                flow.value, money.net(), flow.source(), Category.SUPPLY,
                                reviewState(transaction, flow), false, true, doses, List.of(flow)));
                continue;
            }
            var entry = from(transaction, flow, i); if (entry != null) result.add(entry);
        }
        result.sort(comparingInt((Contribution item) -> item.flowOrdinal)
                        .thenComparing(itemData -> itemData.id));
        return unmodifiableList(result);
    }

    static String normalizedName(String name) {
        if (name == null) return "Unknown item";
        String doseBase = doseBase(name); if (!doseBase.equals(name.trim())) return doseBase; String lower = lower(name);
        if (rule("food", lower))
            for (var row : cached("food_prefixes"))
                if (lower.startsWith(row[0])) return name.trim().substring(row[0].length()).trim();

        return name.trim();
    }

    static Category category(Transaction transaction, Flow flow, Amounts money) {
        if (!money.available || money.transfer || !money.included) return Category.AUDIT;
        if (money.income > 0) return Category.GAIN; if (money.cost <= 0) return Category.AUDIT;
        var kind = CostKind.of(transaction, flow);
        return kind == CostKind.SUPPLIES ? Category.SUPPLY : kind == CostKind.MARKET ? Category.MARKET : Category.LOSS;
    }

    /** A shared-loot split share, told by the split marker on the reason or explanation. */
    static boolean isSplit(Transaction transaction) {
        return ItemSplits.splitReason(transaction.correctionReason())
                || ItemSplits.splitReason(transaction.explanation());
    }

    /** The receipt's activity as a display source; the default GENERAL reads as no source. */
    static String sourceOf(Transaction transaction) {
        String activity = transaction.activityName();
        return empty(activity) || GENERAL.equalsIgnoreCase(activity) ? "" : activity;
    }

    static boolean reviewState(Transaction transaction, Flow flow) {
        boolean unknown = flow.unitPrice <= 0
                        && (flow.source() == UNKNOWN || flow.source() == UNPRICED),
            uncertain = transaction.correction() == AUTO && transaction.confidence() == Confidence.UNCERTAIN;
        String blob = (string(transaction.explanation()) + " " + string(transaction.correctionReason()))
                        .toLowerCase(ROOT);
        return uncertain || unknown || ActionSignals.rule("review-note", blob);
    }

    boolean unpriced() {
        return !any(flows, flow -> flow != null && flow.unitPrice > 0) && (source == UNKNOWN || source == UNPRICED);
    }
}

/**
 * One owned, unfiltered financial snapshot. Session save stamps cover close/history changes that do
 * not advance Engine.revision. Clock, pause and activity are overlays, never cached money. A
 * changed Market presentation reuses the same financial facts rather than refolding receipts.
 */
class MoneySnapshot {
    final List<Object> key; final List<Row> market;
    final Map<Session, SessionFacts> facts; final Map<String, Rollup> rollups;

    /** Derived scopes memoize only from the captured facts; they cannot read mutable receipts. */
    final Map<List<Session>, SessionScope> scopes = new HashMap<>();

    static MoneySnapshot of(Engine engine) {
        var sessions = engine.sessions(); var parts = new ArrayList<Object>(); parts.add(engine.revision());
        parts.add(engine.session()); parts.add(engine.history());
        for (var session : sessions) {
            parts.add(session); parts.add(session.saveStamp); parts.add(session.grindId());
        }
        for (var plan : engine.plans)
            parts.add(plan.id());
        var rows = engine.marketRows(); var cached = engine.snapshot;
        return cached != null && cached.key.equals(parts) && cached.market.equals(rows) ? cached : (engine.snapshot = new MoneySnapshot(engine, sessions, parts, rows, cached));
    }

    MoneySnapshot(Engine engine, List<Session> sessions, List<Object> parts, List<Row> rows,
            MoneySnapshot previous) {
        key = unmodifiableList(parts); market = frozenList(rows);
        if (previous != null && previous.key.equals(key)) {
            facts = previous.facts; rollups = previous.rollups; return;
        }
        var built = new IdentityHashMap<Session, SessionFacts>();
        for (var session : sessions)
            if (session != null && !built.containsKey(session)) {
                var reused = previous == null ? null : previous.facts.get(session);
                built.put(session, session.isClosed() && reused != null && reused.stamp == session.saveStamp ? reused
                                : new SessionFacts(session));
            }
        facts = unmodifiableMap(built); var byGrind = new HashMap<String, Rollup>();
        for (var plan : engine.plans)
            byGrind.put(plan.id(), new Rollup(engine.completedLinked(plan.id())));
        rollups = unmodifiableMap(byGrind);
    }

    /** A saved view is scoped separately and never enters the engine's financial snapshot. */
    MoneySnapshot(SavedState.SavedSession saved) {
        key = emptyList(); market = saved.market; facts = singletonMap(saved.session, new SessionFacts(saved.session));
        rollups = emptyMap();
    }

    /** Canonical metrics keep the exact live clock and Session's saturating rate formula. */
    Metrics metrics(Session session, long now) {
        return session == null ? Metrics.none() : fact(session).metrics(session, now);
    }

    SessionFacts fact(Session session) {
        return Objects.requireNonNull(facts.get(session), "Session outside captured scope");
    }

    /** One session's shared facts, read from its retained history copy when it has one. */
    GrindHistory.Run run(Engine engine, Session session, long now) {
        var retained = facts.containsKey(session) ? session : engine.historySession(session.id());
        return new GrindHistory.Run(session, metrics(retained != null ? retained : session, now));
    }

    SessionScope scope(List<Session> sessions) {
        var scopeKey = frozenList(sessions); return scopes.computeIfAbsent(scopeKey, SessionScope::new);
    }

    /**
     * Visibility derives another presentation from frozen receipts; hero folds remain unfiltered.
     */
    Result view(SessionScope scope, Predicate<Flow> visible) {
        return visible == null ? scope.groups : Semantics.capture(scope.transactions, market, scope.ids, visible);
    }

    /** Canonical per-session money and detached Ledger receipts. */
    static class SessionFacts {
        /** Closed facts reuse the existing save stamp; a correction invalidates them. */
        final long stamp;

        final Metrics money; final List<Transaction> transactions;
        final boolean cut; final int review;
        final MarketFold market;

        Long windowStart; List<Transaction> windowRows;
        Metrics windowMoney; MarketFold windowMarket;

        /** Bounded to this financial revision and the most recently requested Live window. */
        SessionFacts window(Session session) {
            if (windowRows == null || !Objects.equals(windowStart, session.sessionStartAt)) {
                windowStart = session.sessionStartAt;
                windowRows = unmodifiableList(matching(transactions,
                        row -> row.timestamp >= windowStart));
                windowMoney = session.metrics(windowRows, 0); windowMarket = marketFold(windowRows);
            }
            return this;
        }

        SessionFacts(Session session) {
            stamp = session.saveStamp; money = session.metrics(0); var compacted = session.compactedContribution();
            cut = compacted.rows > 0 || compacted.income != 0 || compacted.cost != 0;
            transactions = unmodifiableList(list(matching(session.transactions(), Objects::nonNull), MoneySnapshot::copy));
            review = (int) transactions.stream().filter(ReviewRules::needsReview).count(); market = marketFold(transactions);
        }

        Metrics metrics(Session session, long now) {
            long elapsed = session.elapsed(now);
            return new Metrics(session.activityHint(), session.paused, elapsed, money.income, money.cost,
                    money.net, Session.hourly(money.net, elapsed), money.supplies, money.other,
                    money.split);
        }
    }

    /** The last/best rollup is over completed lineage members, in the original order. */
    class Rollup extends ValueTotal {
        final int count; final boolean has;
        final long last, at, best;

        /** Every finished run's Net and Active Time, for the Grind's average GP/h. */
        long total, millis;

        Rollup(List<Session> sessions) {
            Session latest = null; long bestNet = Long.MIN_VALUE, lastNet = 0;
            for (var session : sessions) {
                long net = fact(session).money.net; add(net); millis = sum(millis, session.elapsed(0));
                if (latest == null || session.startedAt > latest.startedAt) {
                    latest = session; lastNet = net;
                }
                bestNet = max(bestNet, net);
            }
            total = value(); count = sessions.size(); has = latest != null; last = lastNet;
            at = has ? latest.startedAt : 0; best = bestNet == Long.MIN_VALUE ? 0 : bestNet;
        }

    }

    /** Scopes fold canonical gross money, including compaction, before deriving Net. */
    class SessionScope {
        final Set<String> ids; final List<Transaction> transactions;
        final Map<String, Row> markets; final long net;
        final boolean cut; final Result groups;

        SessionScope(List<Session> sessions) {
            var idSet = new LinkedHashSet<String>(); var all = new ArrayList<Transaction>();
            var total = new ValueTotal(); boolean anyCut = false;
            for (var session : sessions) {
                if (session == null) continue; var sessionFacts = fact(session); idSet.add(session.id());
                all.addAll(sessionFacts.transactions); anyCut |= sessionFacts.cut;
                total.add(sessionFacts.money.income, sessionFacts.money.cost);
            }
            ids = unmodifiableSet(idSet); transactions = unmodifiableList(all); net = total.value(); cut = anyCut;
            var byPresentation = new HashMap<String, Row>();
            for (Row row : market)
                if (row != null && ids.contains(row.scopeSessionId))
                    byPresentation.put(row.presentationId, row);
            markets = unmodifiableMap(byPresentation); groups = Semantics.capture(transactions, market, ids, null);
        }
    }

    /** Own the receipt and its flows: later corrections cannot mutate this financial revision. */
    static Transaction copy(Transaction source) {
        var copy = shallow(source); copy.memo = null; copy.onGround = null;
        copy.flows = unmodifiableList(list(source.flows(),
                        flow -> SaveCopies.copy(Objects.requireNonNull(flow),
                                        item -> item.priceSource = item.source())));
        return copy;
    }
}

/**
 * Derived, read-only Market settlement context over durable schema-104 custody lifecycles.
 *
 * <p>It never books GP, never owns corrections and never becomes a second financial authority. For
 * a REALIZED lifecycle it explains the canonical settlement already booked; pending Execution Edge
 * is exposed separately and is never folded into a realized total.
 */
class MarketRows {
    /** Detached Market results for exactly the receipts retained in one Live window. */
    static List<Row> window(Session session, List<Row> source, List<Transaction> transactions) {
        var lookup = new HashMap<String, Transaction>();
        for (var transaction : transactions)
            if (transaction != null && !empty(transaction.id())) lookup.putIfAbsent(transaction.id(), transaction);
        var rows = new ArrayList<Row>();
        for (var original : source) {
            if (!session.id().equals(original.scopeSessionId)) continue;
            var matches = matching(list(original.receiptIds(),
                            lookup::get), Objects::nonNull);
            if (matches.isEmpty()) continue; var row = shallow(original); row.savedNet = session.metrics(matches, 0).net;
            row.savedQuantity = 0;
            for (var receipt : matches) for (Flow flow : receipt.flows())
                if (flow.item == row.itemId) row.savedQuantity = sum(row.savedQuantity, abs(flow.quantity));
            row.settlementId = matches.get(0).id(); row.earlierSettlements = "";
            for (int i = 1; i < matches.size(); i++) row.earlierSettlements += " " + matches.get(i).id(); rows.add(row);
        }
        return rows;
    }

    /** Behavioural lifecycle state; several durable stages may map to one state. */
    /** The pending states come first, before REALIZED (isPending relies on the order). */
    enum Lifecycle {
        PENDING, PARTIALLY_EXECUTED, EXECUTED_UNSETTLED, PARTIALLY_REALIZED, REALIZED, CANCELLED_RETURNED, AMBIGUOUS,
        UNAVAILABLE
    }

    /** Coverage of the realized quantity: proven prior tracked value, partial, or unknown. */
    enum Coverage {
        FULLY_KNOWN, PARTIALLY_KNOWN, FULLY_UNKNOWN
    }

    /** One immutable Market presentation row; no booking behaviour. */
    @EqualsAndHashCode(callSuper = false)
    static class Row extends SaveCopies {
        String presentationId; long timestampEpochMillis;
        int itemId; String itemName;
        GeOffer.Side side;
        long offeredQty, filledQty,

            settledQty, observedSettlementGp, settlementAdjustmentGp;

        /**
         * Observed gross-to-cash gap matching the per-item GE rule. It is the shared accepted-tax
         * predicate: when the booked settlement is a counted unknown-basis sale, this exact tax is
         * the booked Net contribution; otherwise it stays presentation evidence only.
         */
        long inferredGeTaxGp;

        /** Actual after-tax Received per unit when exactly divisible; -1 when not provable. */
        long receivedEachGp;

        /** Settlement/collection observation time; 0 when no exact settlement receipt resolves. */
        long settlementAtEpochMillis,

            realizedResultGp;
        /** Optional saved-window contribution; offer context remains frozen at its lifetime values. */
        Long savedNet; long savedQuantity;
        boolean realizedResultCorrectionAware;

        /**
         * An explicit counted correction can supply a Result even when automatic basis is unknown.
         */
        boolean manualFinancialResult;

        /**
         * The lifecycle failed closed and was quarantined: its booked allocation was never observed
         * as collected cash, so presentation must never show it as Received.
         */
        boolean collectionAmbiguous;

        /**
         * Booked contribution state: a fully-unknown sale whose counted AUTO settlement contributes
         * exactly {@code -inferredGeTaxGp}. Derivation is from the booked transaction (counted +
         * AUTO + effective net equals the shared accepted tax), never from coverage plus an
         * inferred tax alone; a pre-change uncounted sale stays UNCOUNTED.
         */
        boolean knownCostOnly;

        /** SELL: known basis consumed; BUY: created known basis (the exact spend). */
        long trackedBasisConsumedGp;

        Coverage coverage; Lifecycle lifecycle;
        String settlementId;

        /** The offer's earlier settlement ids, space-separated (a sale collected in parts). */
        String earlierSettlements;

        /**
         * The session whose scope shows this row: the session holding its booked settlement,
         * otherwise the listing (origin) session.
         */
        String scopeSessionId;

        /** Display name of the listing session when the sale settled in another one; else empty. */
        String listedDuring;

        Row(GeOffer record, Function<String, Transaction> lookup, Engine sessions) {
            presentationId = record.offerId; String originId = record.originSessionId;
            Session holder = sessions == null || record.settlementId.isEmpty() ? null
                            : sessions.sessionHolding(record.settlementId);
            scopeSessionId = holder == null ? originId : holder.id();
            Session origin = holder == null || originId.isEmpty() || originId.equals(holder.id())
                            ? null : sessions.sessionById(originId);
            listedDuring = origin == null ? "" : origin.name();
            timestampEpochMillis = record.placedAtEpochMillis; itemId = record.itemId;
            itemName = record.itemName; side = record.side; offeredQty = record.offeredQty; filledQty = record.filledQty;
            boolean sell = record.side == SELL; long settled = record.settledQty; settledQty = settled;
            observedSettlementGp = record.settledCashGp;
            settlementAdjustmentGp = sell && settled > 0 ? record.settledCashGp - record.settledExecutionGp : 0;
            // Actual after-tax settled unit value, only when the exact division is proven; -1
            // means "not exactly derivable" and is never silently rounded for display.
            long cash = abs(record.settledCashGp);
            // One shared accepted-tax predicate for booking and presentation: the per-fill tax
            // when every fill was observed at its own price, else the uniform rule.
            inferredGeTaxGp = sell && record.fillTaxExact && record.settledTaxGp > 0
                                    && record.settledExecutionGp - cash == record.settledTaxGp
                            ? record.settledTaxGp : sell ? GeTaxRule.acceptedTax(record.itemId,
                                            record.settledExecutionGp, settled, cash) : 0;
            receivedEachGp = sell && settled > 0 && cash > 0 && cash % settled == 0
                            ? cash / settled : -1;
            Transaction batch = lookup == null || record.settlementId.isEmpty() ? null
                            : lookup.apply(record.settlementId);
            settlementAtEpochMillis = batch == null ? 0 : batch.timestamp;
            if (batch != null) {
                // Correction-aware: consume the effective canonical truth of every booked
                // settlement; an offer
                // collected in parts has one per collect (owner 2026-10-09).
                boolean missing = false;
                var result = new ValueTotal();
                for (String id : (record.settlementId + string(record.earlierSettlements)).split(" ")) {
                    var part = id.isEmpty() ? null : lookup.apply(id);
                    // An earlier receipt compacted away: fall back to the booked accumulator.
                    missing |= part == null && !id.isEmpty(); if (part == null) continue;
                    var money = transaction(part);
                    result.add(money.income, money.cost);
                    manualFinancialResult |= part.correction() == REVENUE || part.correction() == COST;
                }
                realizedResultGp = missing ? record.realizedResultGp : result.value(); realizedResultCorrectionAware = !missing;
            } else
                // The canonical receipt is no longer inspectable; the booked accumulator is
                // exposed but explicitly marked as not correction-recomputed.
                realizedResultGp = record.realizedResultGp;
            long knownConsumed = sell ? min(record.consumedTrackedQty, settled) : settled;
            trackedBasisConsumedGp = settled <= 0 ? 0 : sell ? record.consumedTrackedBasisGp
                                    : abs(record.settledCashGp);
            coverage = settled <= 0 ? Coverage.FULLY_UNKNOWN : settled - knownConsumed <= 0 ? Coverage.FULLY_KNOWN
                                    : knownConsumed <= 0 ? Coverage.FULLY_UNKNOWN : Coverage.PARTIALLY_KNOWN;
            // KNOWN_COST_ONLY is a booked state, not merely coverage plus an inferred tax: a
            // pre-change uncounted sale keeps UNCOUNTED even when its custody evidence still
            // proves the historical tax, and a corrected sale is no longer automatic.
            knownCostOnly = sell && settled > 0 && coverage == Coverage.FULLY_UNKNOWN && batch != null
                            && batch.counted && batch.correction() == AUTO && inferredGeTaxGp > 0
                            && realizedResultGp == -inferredGeTaxGp;
            lifecycle = lifecycleOf(record); collectionAmbiguous = record.collectionAmbiguous; settlementId = record.settlementId;
            earlierSettlements = string(record.earlierSettlements);
        }

        /** Captured settlement order, including the existing empty/missing-id semantics. */
        List<String> receiptIds() {
            return asList((settlementId + earlierSettlements).split(" "));
        }

        boolean realized() {
            return lifecycle == REALIZED || lifecycle == PARTIALLY_REALIZED;
        }
    }

    /**
     * Project the durable custody lifecycles. {@code settlementLookup} resolves a settlement id to
     * its effective canonical transaction so corrections recompute the realized result; when it
     * cannot resolve one, the row fails closed and flags the value as not correction-recomputed.
     */
    static List<Row> rows(List<GeOffer> records, Function<String, Transaction> settlementLookup,
            Engine sessions) {
        return empty(records) ? emptyList() : unmodifiableList(list(matching(records, record -> record != null && !record.offerId.isEmpty()),
                        record -> new Row(record, settlementLookup, sessions)));
    }

    /** Behavioural lifecycle for one durable record. Pending edge is never realized here. */
    static Lifecycle lifecycleOf(GeOffer record) {
        if (record == null || record.unbased()) return UNAVAILABLE;
        if (record.confidence == GeOffer.CustodyConfidence.AMBIGUOUS || record.quantityModified
                || record.collectionAmbiguous)
            return MarketRows.Lifecycle.AMBIGUOUS;
        if (record.filledQty <= 0)
            return record.isCancelled() || record.cleared ? CANCELLED_RETURNED : PENDING;
        if (record.settledQty > 0 && record.settledQty < record.filledQty)
            return PARTIALLY_REALIZED;
        return record.settledQty >= record.filledQty && record.settledQty > 0 ? REALIZED : record.filledQty >= record.offeredQty ? EXECUTED_UNSETTLED : PARTIALLY_EXECUTED;
    }
}

/**
 * The Ledger's Market wording: one source for rows, receipts and the short GE facts, so the page
 * and the read-model tests never drift apart.
 */
@UtilityClass
class MarketText {
    /** Fixed wording by lifecycle and side; quantity formatting stays with its caller. */
    String wording(Row market, String part, String fallback) {
        return text(msg("market." + part + "." + (market.side == SELL ? "SELL." : "BUY.")
                        + market.lifecycle.name(), fallback));
    }

    /** Observed cash movement only; preserves its original signed-long arithmetic. */
    long settlementCash(Row market, boolean sell) {
        long amount = Math.abs(market.observedSettlementGp); return sell ? amount : -amount;
    }

    /** True when a Market group has proven settled quantity and an observed settlement amount. */
    boolean settled(Group group, Row market) {
        return group.market && market != null && market.realized() && market.settledQty > 0
                && market.observedSettlementGp != 0;
    }

    /** The Market row's primary signed value: observed settlement, else the canonical result. */
    long marketPrimary(LedgerData data, Group group) {
        Row market = data.marketRow(group.marketId);
        return data.entry.table != null || !settled(group, market) ? group.value : settlementCash(market, market.side.equals(SELL));
    }

    /** The Market row's secondary line: explicit side + Result, or the lifecycle context. */
    String marketLead(LedgerData data, Group group) {
        if (!group.market) return groupLead(group); Row market = data.marketRow(group.marketId);
        return settled(group, market) ? ((market.side.equals(SELL) ? "Sold" : "Bought") + " · Result "
                    + (group.coverage == COMPLETE ? signed(group.value) : "—")) : group.coverage != COMPLETE ? group.context : "Result " + signed(group.value);
    }

    boolean realized(Receipt receipt) {
        Row market = receipt.market; return market != null && market.realized() && market.settledQty > 0;
    }

    /** Signed observed settlement cash for the collected quantity (SELL positive, BUY negative). */
    long receiptPrimary(Receipt receipt) {
        Row market = receipt.market; return !realized(receipt) ? 0 : settlementCash(market, market.side == SELL);
    }

    String receiptTitle(Receipt receipt) {
        Row market = receipt.market;
        return realized(receipt) && market.settledQty > 1 ? receipt.name + "  " + times(market.settledQty) : receipt.name;
    }

    /**
     * Sold/Bought with the canonical Result as secondary information and a settlement-relative age.
     */
    String receiptSub(Receipt receipt, long captured) {
        Row market = receipt.market; String sub;
        if (realized(receipt)) {
            boolean resultAvailable = market.realizedResultCorrectionAware && (market.side != SELL
                                    || market.coverage != MarketRows.Coverage.FULLY_UNKNOWN
                                    || market.manualFinancialResult);
            String result = resultAvailable ? "Result " + gp(market.realizedResultGp)
                            : "Result —";
            sub = (market.side == SELL ? "Sold" : "Bought") + SEPARATOR + result;
        } else
            sub = market == null ? receipt.verb : orderStateText(market);
        return sub + SEPARATOR + age(positive(captured - receipt.at));
    }

    /**
     * One word for where an offer stands: Offering / Buying while it is up with nothing filled,
     * Pending while part-filled, Sold / Bought once the exchange completes it, Canceled when
     * returned.
     */
    String stateWord(Row market) {
        return market == null ? "Pending" : wording(market, "state", "Pending");
    }

    /**
     * The exact human Market lines ("label|value") for one lifecycle row; the renderer and the
     * read-model tests consume this one source. Coverage-aware: a sale whose prior tracked value is
     * unknown shows liquidation cash with an unavailable Result, never a fake zero basis. Only a
     * booked, uncorrected PROVEN tax earns a main GE tax row; exempt, unproven and pre-change sales
     * and fully known results never show one.
     */
    List<String> humanLines(Row market, boolean corrected) {
        var lines = new ArrayList<String>();
        if (market.savedNet != null) {
            lines.add("Saved session|" + gp(market.savedNet)); lines.add(msg("offerContext"));
        }
        String fillState = fillStateText(market); if (!fillState.isEmpty()) lines.add(fillState);
        if (!market.listedDuring.isEmpty())
            // Cross-Grind: booked where it was collected; the listing session is named, never
            // rewritten.
            lines.add("Listed during|" + market.listedDuring);
        boolean sell = market.side == SELL;
        if (!market.realized() || market.settledQty <= 0) {
            if (sell && market.collectionAmbiguous && market.settledQty > 0)
                // D1: a quarantined settlement's cash was never observed; Received is never shown
                // as if the player had actually collected it.
                lines.add(msg("receivedProven"));
            return lines;
        }
        lines.add((sell ? "Received" : "Spent") + "|" + exact(Math.abs(market.observedSettlementGp)) + GP_UNIT);
        boolean unknown = sell && market.coverage == MarketRows.Coverage.FULLY_UNKNOWN;
        if (sell) {
            if (market.receivedEachGp > 0)
                // The actual after-tax settled unit value; shown only when exactly divisible.
                lines.add("Received each|" + exactGp(market.receivedEachGp));
            lines.add(msg("previouslyCounted") + (unknown ? "Unknown" : exactGp(market.trackedBasisConsumedGp)));
            lines.add("Result|" + (unknown && !market.manualFinancialResult ? "—"
                                    : market.realizedResultCorrectionAware ? exactSigned(market.realizedResultGp)
                                                    + GP_UNIT + (market.coverage == MarketRows.Coverage.PARTIALLY_KNOWN
                                                            ? " · Partial" : "")
                                                    + (corrected ? msg("corrected") : "") : "—"));
            if (market.knownCostOnly)
                // Only the BOOKED state with an AUTO correction earns this row: the tax is a real
                // market cost here. Uncounted/pre-change, corrected and fully known sales never
                // show it (a full Result already has the tax inside Received).
                lines.add("GE tax|" + gp(-market.inferredGeTaxGp));
        } else {
            lines.add("Tracked value|" + exactGp(market.trackedBasisConsumedGp));
            lines.add("Result|" + (market.realizedResultCorrectionAware ? gp(market.realizedResultGp)
                                            + (corrected ? msg("corrected") : "") : "0 gp"));
        }
        if (market.settlementAdjustmentGp != 0 && market.inferredGeTaxGp <= 0)
            // An unexplained gap is the receipt's own answer and must stay visible.
            lines.add("Adjustment|" + gp(market.settlementAdjustmentGp));
        return lines;
    }

    /** Compact fill/collected state; omitted when offered, filled and collected agree. */
    String fillStateText(Row market) {
        long offered = market.offeredQty, filled = market.filledQty, collected = market.settledQty;
        if (offered <= 0 || filled == collected && collected == offered) return "";
        String filledText = "Filled " + exact(filled) + " / " + exact(offered);
        return filled == collected ? filledText : filledText + " · collected " + exact(collected);
    }

    /** Order-state wording that never mislabels placement time as settlement time. */
    String orderStateText(Row market) {
        return String.format(wording(market, "order", "%3$s"),
                        exact(market.filledQty), exact(market.offeredQty), stateWord(market));
    }

    String orderWhy(Row market) {
        return text(msg("market-why." + market.lifecycle.name(), "@collected-settlement"));
    }

    String groupLead(Group group) {
        return group.market ? group.context : !group.uses.isEmpty() ? "Used by " + group.uses : group.label;
    }
}

/**
 * The stacked-table Ledger read model: three independently paginated financial tables (Gains, Costs
 * with a local Loss/Supplies selector, Market) built from the shared semantic projection, plus
 * compact Review/Corrected audit lists and a reconciled Total block.
 *
 * <p>Search runs against COMPLETE semantic groups: a group that owns a matching underlying resource
 * or receipt is kept whole, so a header total never becomes a partial child total. Correction
 * targets are always exact canonical contribution receipts, never a group.
 */
@AllArgsConstructor
class LedgerData {
    enum Scope {
        CURRENT_GRIND, TODAY, HISTORY; final String label = msg("scope-label." + name());

        public String toString() {
            return label;
        }
    }

    /**
     * The Losses table's chips (owner 2026-09-28): LOSS is shown as Items; Charges are measured
     * charge use.
     */
    enum CostView {
        ALL, SUPPLIES, LOSS, CHARGES; final String label = msg("cost-view-label." + name());

        public String toString() {
            return label;
        }
    }

    /** A not-yet-settled count in Pending: an unopened loot key or chest, or a death reclaim. */
    @AllArgsConstructor
    static class PendingItem {
        int item; String name;
        long quantity; String tip;

        /** An observed open chest's exact worth; 0 when unseen, looted or unpriced. */
        long value;
    }

    /** Compact audit subsections; never peer financial tabs. */
    enum Audit {
        REVIEW, CORRECTED; final String label = msg("audit-label." + name());

        public String toString() {
            return label;
        }
    }

    /** Raw Market settlement row for a semantic group's presentation id, or null. */
    Row marketRow(String presentationId) {
        return empty(presentationId) ? null : marketRows.get(presentationId);
    }

    /** Presentation-only Ledger query state; it never enters SavedState. */
    static class Entry {
        Scope scope; public String session, historyName;
        CostView view; String search;
        public String transaction, highlightContributionId, group;

        /** Deep-link request to expand one audit subsection. */
        public Audit focusAudit; Table table;

        Entry live(Table table) {
            this.table = table; return this;
        }

        Entry(Scope scope, String id, String name) {
            this(scope, id, name, CostView.ALL, "", null, null, null, null);
        }

        Entry(Scope scope, String id, String name, CostView costView, String search,
                String receiptId, String highlightContributionId, String highlightGroupId,
                Audit focusAudit) {
            this.scope = scope == null ? CURRENT_GRIND : scope; session = id;
            historyName = name; view = costView == null ? CostView.ALL : costView;
            this.search = string(search); transaction = receiptId;
            this.highlightContributionId = highlightContributionId; group = highlightGroupId; this.focusAudit = focusAudit;
        }

        static Entry current() {
            return new Entry(CURRENT_GRIND, null, null);
        }

        static Entry current(Audit audit, String id) {
            return new Entry(CURRENT_GRIND, null, null, CostView.ALL, "", id, null, null, audit);
        }

        Entry withCostView(CostView next) {
            return new Entry(scope, session, historyName, next, search, null, null, null, null);
        }

        Entry withSearch(String next) {
            return new Entry(scope, session, historyName, view, next, null, null, null, null).live(table);
        }

        Entry withScope(Scope next, String id, String name) {
            return new Entry(next, id, name, view, search, null, null, null, null);
        }

        /** Selection stores only stable identity. */
        Entry withSelection(String identifier, String id, String group) {
            return new Entry(scope, session, historyName, view, search, identifier, id,
                    group, focusAudit).live(table);
        }
    }

    /** One table's full filtered set and its header facts; the page table pages it. */
    @AllArgsConstructor
    static class TableData {
        List<Group> groups; long total;
        int entries, receipts;
    }

    /** Review audit: exact review rows with the full eligible bulk count. */
    @AllArgsConstructor
    static class ReviewData {
        List<ReviewRow> rows; int total;
    }

    /** The open detail: one semantic group, its exact receipts, and any selected exact receipt. */
    @AllArgsConstructor
    static class Detail {
        Group group; List<Receipt> entries;
        public Receipt exact;
    }

    /** One resource inside a receipt: "Chaos rune ×4" on its line. */
    @AllArgsConstructor
    static class ResourceSummary {
        int item; String name, uses;
        long quantity, value;

        /** Sum of only the known children; shown with explicit wording when incomplete. */
        long knownSubtotal; boolean incomplete;
    }

    /** One receipt row: exactly one canonical transaction inside the selected group. */
    @AllArgsConstructor
    static class Card {
        String transaction; long at, value, knownSubtotal;
        boolean incomplete;

        /** Representative contribution id for selecting the receipt. */
        String id;

        List<ResourceSummary> resources;

        /** "Chaos rune ×4 · Death rune ×2" for the row's tooltip. */
        String summaryText() {
            var text = new StringBuilder(); for (var resource : resources) appendItem(text, resource.name, resource.quantity);
            return text.toString();
        }
    }

    /** Additive facts shared by a receipt card and each resource inside it. */
    @RequiredArgsConstructor
    static class ReceiptTotal extends ValueTotal {
        final Receipt head; final ValueTotal known = new ValueTotal();
        long quantity, at; boolean incomplete;
        String representative = "";

        void add(Receipt receipt) {
            quantity = sum(quantity, receipt.quantity); super.add(receipt.value); at = max(at, receipt.at);
            incomplete |= receipt.unknown; if (!receipt.unknown) known.add(receipt.value);
            if (representative.isEmpty()) representative = receipt.id;
        }

        /** Merge the buckets before subtracting, preserving canonical saturation in either order. */
        void add(ReceiptTotal other) {
            quantity = sum(quantity, other.quantity); super.add(other); known.add(other.known); at = max(at, other.at);
            incomplete |= other.incomplete; if (representative.isEmpty()) representative = other.representative;
        }

        long known() { return known.value(); }

        ResourceSummary summary() {
            return new ResourceSummary(head.item, head.name, head.uses, quantity, value(), known(), incomplete);
        }
    }

    static class ReceiptCard extends ReceiptTotal {
        Map<String, ReceiptTotal> resources = new LinkedHashMap<>();

        ReceiptCard(Receipt head) {
            super(head);
        }

        void add(Receipt receipt) {
            super.add(receipt);
            resources.computeIfAbsent(receipt.item + "|" + receipt.uses, ignored -> new ReceiptTotal(receipt))
                    .add(receipt);
        }

        List<ReceiptTotal> orderedResources() {
            var ordered = copiedList(resources.values());
            ordered.sort(comparing((ReceiptTotal total) -> total.head.name,
                                    String.CASE_INSENSITIVE_ORDER).thenComparingInt(total -> total.head.item));
            return ordered;
        }

        Card card() {
            return new Card(head.transaction, at, value(), known(), incomplete, representative,
                            list(orderedResources(), ReceiptTotal::summary));
        }
    }

    /** One pass builds each transaction card and its resources; newest cards first. */
    static List<ReceiptCard> receiptCards(Group group) {
        if (group == null) return emptyList(); var byTransaction = new LinkedHashMap<String, ReceiptCard>();
        for (var receipt : group.entries)
            byTransaction.computeIfAbsent(receipt.transaction, ignored -> new ReceiptCard(receipt)).add(receipt);
        var cards = copiedList(byTransaction.values());
        cards.sort(comparingLong((ReceiptCard card) -> card.at).reversed()
                        .thenComparing(card -> card.head.transaction));
        return cards;
    }

    static List<Card> cards(Group group) {
        return group == null ? emptyList() : list(receiptCards(group), ReceiptCard::card);
    }

    /** Keep the previous card/resource order and first metadata while merging unsimplified buckets. */
    static List<ResourceSummary> itemTotals(Group group) {
        var totals = new LinkedHashMap<String, ReceiptTotal>();
        for (var card : receiptCards(group))
            for (var resource : card.orderedResources())
                totals.computeIfAbsent(resource.head.item + "|" + resource.head.name,
                                ignored -> new ReceiptTotal(resource.head)).add(resource);
        return list(totals.values(), ReceiptTotal::summary);
    }

    /** The query this data answers: scope, Costs view, search and the selected receipt. */
    Entry entry;

    String scopeName; boolean readonly, compacted, detailed;
    long net;

    /** Capture time for honest ages without leaking the engine. */
    long at;

    TableData income, cost, market; ReviewData review;
    List<Receipt> corrected; Summary total;
    boolean balanced; public Detail detail;

    /**
     * Raw Market settlement rows by presentation id; presentation-only lookup for value semantics.
     */
    Map<String, Row> marketRows;

    /** Group counts for the Losses chips by {@link CostView} ordinal, whatever view is selected. */
    int[] costCounts;

    /** Keys and reclaim waiting to settle; current scope only, with an exact observed chest value. */
    List<PendingItem> pendingItems;

    static LedgerData capture(Engine engine, long now, Entry entry) {
        Entry e = entry == null ? Entry.current() : entry; var sessions = sessionsFor(engine, e, now);
        String scopeName = scopeName(engine, e); var saved = e.scope == HISTORY ? engine.savedSession(e.session) : null;
        var summary = saved == null ? engine.summary() : saved.view(); var scope = summary.scope(sessions);
        var ids = scope.ids; var transactions = scope.transactions;
        boolean compacted = scope.cut; long scopeNet = scope.net;
        var rows = summary.market; var markets = scope.markets;
        var result = scope.groups;
        if (e.scope == CURRENT_GRIND && e.table != null && e.table != KILL && e.table != DEATH && engine.session() != null) {
            var active = engine.session(); scopeName = "Live session";
            if (active.sessionStartAt != null) {
                transactions = active.rowsSince(active.sessionStartAt); rows = MarketRows.window(active, rows, transactions);
                markets = index(rows, row -> row.presentationId, row -> row); result = Semantics.capture(transactions, rows, ids, null);
                scopeNet = active.metrics(transactions, 0).net; compacted = false;
            }
        }
        if (e.table == KILL) result = kills(transactions); var tables = new EnumMap<Table, List<Group>>(Table.class);
        for (var table : Table.values()) tables.put(table, new ArrayList<>());
        for (Group group : result.groups)
            if (!group.review && group.matchesText(e.search)) {
                tables.get(group.death && group.neutral ? COSTS_LOSS : group.table).add(group);
                if (group.pvpDeath) tables.get(DEATH).add(group);
            }

        var gainsAll = tables.get(GAINS); var suppliesAll = tables.get(COSTS_SUPPLIES);
        var lossAll = tables.get(COSTS_LOSS); var marketAll = tables.get(Semantics.Table.MARKET);
        var supplies = new ArrayList<Group>(); var charges = new ArrayList<Group>();
        for (Group group : suppliesAll) (group.charge ? charges : supplies).add(group);
        var costs = new ArrayList<Group>(e.table == DEATH ? tables.get(DEATH)
                                : e.table == COSTS_SUPPLIES ? suppliesAll
                                : e.table == COSTS_LOSS || e.view == CostView.LOSS ? lossAll
                                : e.view == CostView.CHARGES ? charges : supplies);
        if (e.table == null && e.view == CostView.ALL) {
            // All is every list together, newest first like every other table (owner 1.3.3).
            costs.addAll(charges); costs.addAll(lossAll); costs.sort(Semantics.NEWEST_GROUP);
        }
        var income = tableData(e.table == KILL ? tables.get(KILL) : gainsAll, false); var cost = tableData(costs, false);
        var market = tableData(marketAll, true); var financial = new ArrayList<Group>();
        for (var groups : List.of(gainsAll, suppliesAll, lossAll, marketAll)) financial.addAll(groups);
        var total = Semantics.summarize(financial);
        // ── audit ────────────────────────────────────────────────────────────────────────────
        var reviewRows = new ArrayList<ReviewRow>(); int reviewScopeCount = 0;
        for (var reviewRow : engine.getReviewRows(now))
            if (reviewRow != null && ids.contains(reviewRow.session)) {
                reviewScopeCount++; if (!readOnly(e) && matchesSearch(reviewRow, e.search)) reviewRows.add(reviewRow);
            }

        var review = new ReviewData(reviewRows, saved == null ? reviewScopeCount : summary.fact(saved.session).review);
        var fixed = new ArrayList<Receipt>();
        if (result.audit == null)
            result.audit = any(transactions, row -> row.correction() == IGNORE)
                    ? Semantics.capture(transactions, rows, ids, null, true) : result;
        var audit = result.audit;
        for (Group group : audit.groups)
            for (var receipt : group.entries)
                if ((receipt.corrected || receipt.split) && receipt.matchesSearch(e.search))
                    fixed.add(receipt);


        fixed.sort(comparingLong((Receipt receipt) -> receipt.at).reversed()
                        .thenComparing(receipt -> receipt.id));
        // ── selected detail ──────────────────────────────────────────────────────────────────
        Detail detail = null;
        String anchorContribution = e.highlightContributionId, anchorTransaction = e.transaction,
            anchorGroup = e.group;
        if (anchorGroup != null || anchorContribution != null || anchorTransaction != null) {
            Group group = findGroup(e.focusAudit == Audit.CORRECTED ? audit.groups : result.groups,
                    anchorGroup, anchorContribution, anchorTransaction);
            if (group != null) {
                var records = group.entries; Receipt exact = null;
                if (anchorContribution != null)
                    for (var receipt : records)
                        if (receipt.id.equals(anchorContribution)) {
                            exact = receipt; break;
                        }


                if (exact == null && anchorTransaction != null)
                    exact = firstReceipt(records, anchorTransaction);
                // A one-entry group opens on its entry: a list of one only repeated the header.
                if (exact == null && !group.market && oneEntry(records)) exact = records.get(0);
                detail = new Detail(group, records, exact);
            }
        }
        long net = scopeNet;
        boolean detailedHistoryAvailable = !compacted || total.entries > 0,
            reconciled = total.coverageComplete && total.net() == net;
        return new LedgerData(e, scopeName, readOnly(e), compacted, detailedHistoryAvailable, net, now, income, cost,
                market, review, fixed, total, reconciled, detail, markets,
                new int[] {0, supplies.size(), lossAll.size(), charges.size()}, pendingItems(engine, e, now));
    }

    /** One retained anonymous kill, with its pickups and source-linked key proceeds. No booking. */
    static Result kills(List<Transaction> transactions) {
        var seeds = new HashMap<String, String>();
        for (var row : transactions)
            if (row.original() == TransactionType.PK_LOOT || row.note().equals(KeyClaims.LOOT_KEY_NOTE))
                seeds.put(row.id(), row.encounterId().startsWith(PkTally.KILL) ? row.encounterId() : row.id());
        var links = new HashMap<String, Set<String>>(); var groups = new LinkedHashMap<String, Group>();
        for (var row : transactions) {
            if (needsReview(row) || row.correction() == Correction.TRANSFER) continue;
            String source = row.claimId().split("#", 2)[0],
                kill = seeds.getOrDefault(row.id(), seeds.get(source));
            if (kill == null) continue;
            for (var unit : Semantics.transactionUnits(row, Flow::gain)) {
                links.computeIfAbsent(source, key -> new HashSet<>()).add(row.claimId()); unit.id = kill;
                unit.table = KILL; unit.name = "PK loot"; unit.composite = unit.neutral = false; unit.quantity = -1;
                groups.merge(kill, unit, Group::absorb).corrected |= unit.corrected;
            }
        }
        for (var row : transactions) if (row.note().equals(KeyClaims.LOOT_KEY_NOTE)) {
            var group = groups.get(seeds.get(row.id()));
            if (group != null && links.getOrDefault(row.id(), Set.of()).size()
                    < row.flows().stream().mapToLong(flow -> max(1, flow.quantity)).sum()) {
                group.coverage = INCOMPLETE; group.context = msg("ku");
            }
        }
        var result = copiedList(groups.values()); result.sort(Semantics.NEWEST_GROUP); return new Result(result);
    }

    static List<PendingItem> pendingItems(Engine engine, Entry e, long now) {
        var items = new ArrayList<PendingItem>(); if (e.scope != CURRENT_GRIND || readOnly(e)) return items;
        var valued = new HashSet<Integer>();
        for (var claim : engine.pendingClaims()) {
            if (claim == null || !claim.isValid()) continue; var chest = forKey(claim.itemOrKeyId);
            String name = chest != null ? chest.getChestName() + " key" : claim.itemOrKeyId >= WILDY_LOOT_KEY0
                                            && claim.itemOrKeyId <= WILDY_LOOT_KEY4 ? "Loot key"
                                    : "Key or chest";
            // One chest per key type: stacked claims of it name the same worth once.
            long value = valued.add(claim.itemOrKeyId) ? engine.pendingKeyValue(claim.itemOrKeyId, now) : 0;
            items.add(new PendingItem(claim.itemOrKeyId, name, claim.quantity(), name + msg("openedBooks"), value));
        }
        return items;
    }

    /** Ordinary tables sum every group; Market's header includes only complete results. */
    static TableData tableData(List<Group> groups, boolean market) {
        var total = new ValueTotal(); var records = new LinkedHashSet<String>();
        for (Group group : groups) {
            if (!market || !group.incomplete()) total.add(group); records.addAll(group.ids);
        }
        return new TableData(groups, total.value(), groups.size(), records.size());
    }

    /** True when a Market lifecycle is genuinely pending rather than already realized. */
    static boolean isPending(Lifecycle lifecycle) {
        // Lifecycle lists every pending state before REALIZED.
        return lifecycle != null && lifecycle.compareTo(REALIZED) < 0;
    }

    static Group findGroup(List<Group> groups, String groupId, String id, String identifier) {
        // Most specific anchor first: a receipt's own contribution must win over the transaction
        // it came from, or a shop drill lands on the wrong side of the trade (one rune's group).
        for (Group group : groups)
            if (!group.review && group.containsContribution(groupId)) return group;

        for (Group group : groups)
            if (!group.review && group.containsContribution(id)) return group;

        for (Group group : groups)
            if (!group.review && group.containsTransaction(identifier)) return group;

        // Review-required groups are still openable from the audit list detail.
        return firstMatch(groups, group -> group.containsContribution(id) || group.containsTransaction(identifier));
    }

    /** True when every receipt belongs to one entry: a group whose list would hold one row. */
    static boolean oneEntry(List<Receipt> records) {
        return !records.isEmpty() && records.stream().allMatch(r -> r.transaction.equals(records.get(0).transaction));
    }

    static Receipt firstReceipt(List<Receipt> records, String id) {
        Receipt found = null;
        for (var receipt : records)
            if (receipt.transaction.equals(id) && (found == null || receipt.at >= found.at))
                found = receipt;

        return found;
    }

    static List<Session> sessionsFor(Engine engine, Entry entry, long now) {
        var sessions = new ArrayList<Session>();
        if (entry.scope == HISTORY) {
            var historical = entry.session == null ? null : engine.historySession(entry.session);
            if (historical != null) sessions.add(historical); return sessions;
        }
        var active = engine.session(); if (active != null) sessions.add(active);
        if (entry.scope == TODAY) {
            var zone = engine.timeZone(); var today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
            for (var history : engine.history()) {
                if (history == null || active != null && history.id().equals(active.id())) continue;
                var day = Instant.ofEpochMilli(history.startedAt).atZone(zone).toLocalDate();
                if (day.equals(today)) sessions.add(history);
            }
        }
        return sessions;
    }

    static String scopeName(Engine engine, Entry entry) {
        if (entry.scope == HISTORY) {
            if (!empty(entry.historyName) && (entry.session == null || entry.session.startsWith("raid:")))
                return entry.historyName;
            var history = entry.session == null ? null : engine.historySession(entry.session);
            return history == null ? fallback(entry.historyName, "Recent Grind") : history.name();
        }
        return entry.scope == TODAY ? "Runs today" : "Current Grind";
    }

    static boolean readOnly(Entry entry) {
        return entry.scope != CURRENT_GRIND;
    }

    static boolean matchesSearch(ReviewRow row, String query) {
        if (blank(query)) return true; String needle = lower(query);
        return containsFolded(needle, row.why)
                        || any(row.items, item -> containsFolded(needle, item.name));
    }

    // ── correction support (unchanged semantics: exact canonical ids only) ─────────────────────
    static class CorrectionPreview {
        long currentNet, afterNet, change, revision;

        CorrectionPreview(long net, long afterNet, long revision) {
            currentNet = net; this.afterNet = afterNet; change = subtract(afterNet, net); this.revision = revision;
        }

        String note() {
            return change == 0 ? msg("netGp") : "Net changes by " + signed(change) + " gp.";
        }
    }

    /**
     * Net if a split keeps {@code keep} of {@code itemId}: the split itself runs on a detached copy
     * of the receipt, so the preview and the applied split cannot differ.
     */
    static CorrectionPreview splitPreview(Engine engine, String identifier, int id, long keep, long now) {
        synchronized (engine) {
            long net = engine.metrics(now).net; var session = engine.session();
            var transaction = session == null ? null : session.findTransaction(identifier);
            if (transaction == null)
                return new CorrectionPreview(net, net, engine.revision());
            long after = session.netAfter(singleton(identifier),
                            copy -> copy.takeQuantity(id, true, copy.quantity(id, true) - keep));
            return new CorrectionPreview(net, after, engine.revision());
        }
    }

    /** One canonical state, like {@link #splitPreview}: Net before/after and revision agree. */
    static CorrectionPreview preview(Engine engine, String id, Correction decision, long now) {
        synchronized (engine) {
            var session = engine.session(); long net = engine.metrics(now).net;
            var transaction = session == null || decision == null ? null : session.findTransaction(id);
            if (transaction == null)
                return new CorrectionPreview(net, net, engine.revision());
            // Explicit removal changes only financial inclusion; physical edits retain their guard.
            if (decision != IGNORE && !session.canCorrect(transaction, decision)) return null;
            long after = session.netAfter(singleton(id), copy -> copy.applyCorrection(decision, ""));
            return new CorrectionPreview(net, after, engine.revision());
        }
    }
}

/** Detached, read-only summary of one transaction awaiting an owner decision. */
class ReviewRow {
    String transaction, session; long at, ageMillis;
    List<Item> items; long value;
    String why; Set<Decision> decisions;

    ReviewRow(String id, String sessionId, long timestamp, long ageMillis, List<Item> items,
            long value, String why, Set<Decision> validDecisions) {
        transaction = string(id); session = string(sessionId); at = positive(timestamp); this.ageMillis = positive(ageMillis);
        this.items = unmodifiableList(copiedList(items)); this.value = value; this.why = string(why);
        decisions = unmodifiableSet(empty(validDecisions) ? EnumSet.noneOf(Decision.class)
                        : EnumSet.copyOf(validDecisions));
    }

    /** Detached item summary; value is the signed flow value for this item. */
    static class Item {
        int item; String name;
        long quantityDelta, valueDelta;

        Item(int id, String name, long delta, long value) {
            item = id; this.name = string(name); quantityDelta = delta; valueDelta = value;
        }
    }
}

/**
 * Everything the Live page paints, captured in one read from the current schema-102 engine so the
 * page never holds engine references while laying out. Pure data; built by {@link #capture}.
 *
 * <p>Forward-port note: this adapter reads current canonical queries only. Facts whose producer no
 * longer exists (session goals, activity averages, party reports, live encounters, the retired
 * notable-drop threshold) are absent rather than re-derived from unrelated numbers.
 */
@AllArgsConstructor
class LiveSnapshot {
    /** Stable session identity; names are editable and may repeat between runs. */
    String session;

    static final int RECENT_ROWS = 15;

    /** One session goal as the Live card shows it; read-only, from the session's current target. */
    @AllArgsConstructor
    static class Goal {
        long target, current; boolean reached;

        /** "200k", or the Active Time target for a tracked-time goal. */
        String label;

        /** "62%". */
        String progress;

        /** The pace line while the rate is trustworthy; null when it is not. */
        String eta;

        double fraction;

        /** True when this card tracks Active Time rather than Net (owner 2026-10-01, F10). */
        boolean time;

        /**
         * The tracked Active Time pair ({@code 1h 02m/2h 30m · 47%}) when the session has a time
         * target; null with none. A Net card with both targets also shows it (owner live
         * 2026-10-13).
         */
        String timeDetail;

        /**
         * Remaining Active Time on the time window while one is pending; -1 when none is set
         * (owner live 2026-10-13, HUD warning for both-target runs).
         */
        long remaining;

        /** True when a time window has reached its target, on any card (owner live 2026-10-13). */
        boolean timeDone;

        /** True while the timer's own pause holds (owner live 2026-10-14). */
        boolean timerPaused;
    }

    /** One exact item contribution or an honestly labelled composite activity row. */
    @AllArgsConstructor
    static class Recent extends ValueTotal {
        int item; String name, qty;
        long value; boolean neutral;

        /** True when the lead flow has no price: the row shows an unknown marker, never 0. */
        boolean unknown;

        String tag;

        /** How many receipts this row coalesced; 1 for a single receipt. */
        int entries;

        /** The newest canonical receipt behind the row, for Ledger context. */
        String receiptId,

            id, label;
        long quantity; boolean composite, market, known;

        /** The Costs view this row opens in the Ledger; null off the Costs table. */
        CostView cost;

        /** True when this row belongs to the Review audit rather than a financial table. */
        boolean ledgerReview;

        /** Canonical realized Market result; the secondary "Result" line for Market rows. */
        long marketResult;

        /** True when {@link #value} is the observed signed settlement amount (SELL / BUY). */
        boolean amount;

        /**
         * "Sold" / "Bought" once settled, else where the offer stands ("Offering", "Pending");
         * empty off-Market.
         */
        String marketSide;

        /**
         * A GE offer still on the exchange (trading, or filled and not collected): Live lists it
         * under Offers.
         */
        boolean open;

        /** An item supply spend, including doses, ammunition, food and charge resources. */
        boolean supplyRow;

        /** Ground Items' highlight colour for a highlighted gain; null otherwise. */
        Color highlight;

        /** Every receipt this row coalesced, for removing the whole row. */
        Set<String> receipts;

        /**
         * The row's share of Net. A Market row moves Net by its Result only, never by the cash it
         * moved: a GE buy is neutral (owner live 2026-10-15), an unproven Result counts nothing.
         */
        long net() {
            return !market ? value : known ? marketResult : 0;
        }
    }

    boolean present; String ownerLabel;
    long elapsed; boolean paused, idle;
    long net, income;

    /** Non-consumable costs: deaths, tax, fees, drops. {@code -1} when the split is unavailable. */
    long loss;

    /**
     * Consumables: food, potions, runes, ammo, charges. {@code -1} when the split is unavailable.
     */
    long supplies;

    /** Total booked cost; always the truthful figure, split or not. */
    long cost;

    boolean split, ready; long rate;
    Goal goal;

    /** Realized Market result for the active scope, separated from ordinary Gains/Losses. */
    long market;

    /** Pending/unrealised Market context rows; informational only, never counted in Net. */
    int pendingMarket;

    /** Free play (the engine's durable owner) rather than a named session. */
    boolean general;

    /** Activity context label for the status line; empty when the owner name speaks for it. */
    String activityLabel;

    /** Freshest client-thread interaction target; the shared activity label reads it. */
    String target;

    /** Client-thread PvP sample, presentation only. */
    boolean possible, skulled, protectItem; long risk;
    int reviews; List<Recent> recent;

    /** True for any session that has booked a fight. */
    boolean pvp;

    int kills, deaths; long bestKill;

    /** Loot keys and chests not opened yet; a count, never a GP guess. */
    int keys;

    /** The exact price of the pending chests' observed contents; 0 until a chest is seen. */
    long keyValue;

    /** Kills since the last death this Grind; 0 after a death. */
    int streak;

    /** Longest run of kills without a death this Grind. */
    int bestStreak;

    /** Paused by logout: tracking waits for the next login, numbers stay as they were. */
    boolean offline;

    /** Seconds until the pending factory-reset resume fires; 0 when none is armed. */
    long resumeSeconds;

    /** True after a resume while the tracker's derived displays settle; bookings are immediate. */
    boolean warming;

    /** True while a world hop is in flight: the status word says Reconnecting, not Logged out. */
    boolean hopping;

    /** The run's Net before the running target began; Net plus this is the whole run's. */
    long netBase;

    /** Free play's Net milestone and Free play's whole Net (owner 1.5.2); null off Free play. */
    Long milestone;

    long targetUntil,

        run;

    /** True while Live counts from a target or a Reset Net rather than the whole run. */
    boolean fresh;

    boolean milestoneReached() {
        return milestone != null && milestone > 0 && run >= milestone;
    }

    /** Anything booked in this window; nothing booked reads as a dash, never a zero. */
    boolean booked() {
        return fresh || income != 0 || cost != 0 || market != 0 || !recent.isEmpty();
    }

    /**
     * @param context plugin-side idle state, owner marks and loot visibility (may be null)
     */
    static LiveSnapshot capture(Engine engine, long now, LiveContext context) {
        var ctx = context == null ? LiveContext.NONE : context; var summary = engine.summary();
        var active = engine.session(); var metrics = summary.metrics(active, now);
        boolean custom = engine.customActive(), general = active == null || !custom;
        String owner = active == null ? "Free play" : active.name(); long elapsed = metrics.elapsed;
        var facts = active == null ? null : summary.fact(active); int reviewCount = facts == null ? 0 : facts.review;
        // One shared projection for the whole snapshot: Recent rows and the Market separation. The
        // Market rows are rebuilt on every read, so they are read once.
        var marketRows = summary.market;
        // Hero categories are canonical and unfiltered: the whole counted Market-context
        // population is removed from ordinary Gains/Supplies/Losses and represented once by its
        // correction-aware net. Recent Activity display filters never enter this math.
        var market = facts == null ? marketFold(emptyList()) : facts.market;
        // Live shows the current session: what was booked since it began; the run keeps every row
        // and its whole total (owner 1.5.2).
        var shown = metrics; Long start = active == null ? null : active.sessionStartAt;
        if (start != null) {
            var window = facts.window(active); shown = window.windowMoney; market = window.windowMarket;
        }
        long since = active == null ? 0 : active.liveElapsed(now), rate = Session.hourly(shown.net, since),
            marketResult = market.net();
        int marketPending = 0; var markets = new HashMap<String, Row>();
        for (Row row : marketRows)
            if (row != null) {
                markets.put(row.presentationId, row); marketPending += LedgerData.isPending(row.lifecycle) ? 1 : 0;
            }

        var pk = active == null ? new PkTally() : active.pk(); boolean pvpSession = pk.encounters() > 0;
        return new LiveSnapshot(active == null ? "" : active.id(), active != null, owner, since, metrics.paused,
                ctx.idle, shown.net, visibleGains(shown, market), visibleLosses(shown, market),
                visibleSupplies(shown, market), visibleCosts(shown, market), shown.split,
                RateReadiness.isEstablished(since), rate, goal(active, metrics, elapsed), marketResult,
                marketPending, general, ctx.activity, ctx.target, ctx.pvp.possible, ctx.pvp.skulled,
                ctx.pvp.protectItem, ctx.pvp.risk, reviewCount,
                recent(engine, active, marketRows, markets, ctx), pvpSession, pk.kills,
                pk.deaths, engine.bestKill(), engine.pendingClaims().size(), engine.pendingKeysValue(now),
                max(0, pk.streak), pk.bestStreak, active == null ? ctx.offline
                        : active.paused && active.pauseReason() == LIFECYCLE,
                engine.autoResumeSeconds(now), engine.calibrating(now), ctx.hopping, metrics.net - shown.net,
                general && active != null ? active.milestoneGp : null,
                general && active == engine.targetOwner ? engine.targetUntil : 0, metrics.net, start != null);
    }

    /**
     * The read-only target card: the session's current {@code profitTargetGp} against its booked
     * net. The historical goal editor, goal persistence and multi-kind goals are retired; no editor
     * affordance and no ETA without a trustworthy rate.
     */
    static Goal goal(Session active, Metrics metrics, long elapsed) {
        if (active == null) return null; Long target = active.targetGp(), duration = active.timeTargetMillis();
        if (target != null && target > 0) {
            long current = active.targetProgress(metrics.net); double fraction = max(0d, min(1d, current / (double) target));
            boolean reached = current >= target; String eta = null, timeDetail = null;
            long timeRemaining = -1; boolean timeDone = false, timerPaused = false;
            // The aim's own pace: Net and Active Time since it was set, so free play before it
            // never counts (owner 2026-10-16).
            long since = active.profitTargetActiveMillis(elapsed); boolean established = RateReadiness.isEstablished(since);
            long rate = Session.hourly(current, since);
            if (duration != null && duration > 0) {
                // Owner live 2026-10-13: with both targets set the hero keeps the live Net read
                // and the card adds the tracked Active Time pair; the countdown replaces the
                // Net-rate estimate.
                long timeElapsed = active.timeWindowMillis(elapsed); timeDetail = timeDetail(timeElapsed, duration);
                timeRemaining = positive(duration - timeElapsed); timeDone = timeElapsed >= duration;
                timerPaused = active.timeTargetPaused();
                // The time line says it all once; no projection repeats the hero's GP/h (owner 1.5.2).
            } else if (established && !reached) {
                // An established non-positive rate says so honestly (owner live 2026-10-13); a reached
                // aim adds nothing the hero does not show (owner 1.5.2).
                long minutes = rate > 0 ? round((target - current) * 60d / rate) : 0;
                eta = rate <= 0 ? "Not earning at this rate"
                                        : (minutes < 1 ? "<1m" : "~" + GrindsData.durationMinutes(minutes))
                                                + msg("playLeft");
            }
            return new Goal(target, current, reached, compact(target), reached ? "reached" : percent(fraction), eta,
                    fraction, false, timeDetail, timeRemaining, timeDone, timerPaused);
        }
        // Owner 2026-10-01 (F10): a time-only target is still a target; the card tracks Active
        // Time.
        if (duration == null || duration <= 0) return null; long timeElapsed = active.timeWindowMillis(elapsed);
        double timeFraction = max(0d, min(1d, timeElapsed / (double) duration)); boolean timeReached = timeElapsed >= duration;
        // The card counts down; a projected Net repeated the hero's GP/h (owner 1.5.2).
        return new Goal(duration, timeElapsed, timeReached, durationShort(duration),
                timeReached ? "reached" : percent(timeFraction), null, timeFraction, true,
                timeDetail(timeElapsed, duration), positive(duration - timeElapsed), timeReached,
                active.timeTargetPaused());
    }

    /**
     * "2h 50m left of 3h 00m · 6%": a time target's one line, or "Time target reached" (owner 1.5.2:
     * the time is said once).
     */
    static String timeDetail(long elapsed, long duration) {
        return elapsed >= duration ? "Time target reached" : durationCount(duration - elapsed) + " left of " + durationShort(duration) + SEPARATOR
                + percent(max(0d, elapsed / (double) duration));
    }

    /** Visible ordinary Gains: canonical unfiltered revenue minus the counted Market fold. */
    static long visibleGains(Metrics metrics, MarketFold market) {
        return subtract(metrics.income, market.income);
    }

    /** Visible Supplies, or -1 when the canonical cost split is unavailable (combined fallback). */
    static long visibleSupplies(Metrics metrics, MarketFold market) {
        return metrics.split ? subtract(metrics.supplies, market.supplies) : -1;
    }

    /** Visible Losses, or -1 when the canonical cost split is unavailable (combined fallback). */
    static long visibleLosses(Metrics metrics, MarketFold market) {
        return metrics.split ? subtract(metrics.other, market.other) : -1;
    }

    /** Visible combined Costs: canonical costs minus the counted Market gross costs. */
    static long visibleCosts(Metrics metrics, MarketFold market) {
        return subtract(metrics.cost, market.cost);
    }

    /** The last Live list and what it was built from; the key covers every input of the list. */
    static List<Object> recentKey = emptyList();

    static List<Recent> recentList = emptyList();

    /**
     * The Live list is rebuilt only when a booking (the engine revision), a Market row or the loot
     * display filter changed; otherwise every tick reuses it instead of re-projecting the Grind.
     */
    static synchronized List<Recent> recent(Engine engine, Session active,
            List<Row> market, Map<String, Row> markets, LiveContext ctx) {
        var key = asList(engine, engine.revision(), active, market,
                        ctx.minimumLoot, ctx.filter, ctx.filter == null ? "" : ctx.filter.version(),
                        ctx.filter == null ? RECENT_ROWS : ctx.filter.recentRows(),
                        String.valueOf(active == null ? null : active.sessionStartAt));
        if (!key.equals(recentKey)) {
            var summary = engine.summary();
            if (active == null || active.sessionStartAt == null) {
                var scope = summary.scope(active == null ? emptyList() : singletonList(active));
                recentList = recentRows(summary.view(scope, ctx::flowVisible), markets, ctx.filter);
            } else {
                var rows = summary.fact(active).window(active).windowRows;
                var ids = new HashSet<String>(); for (var row : rows) ids.add(row.id());
                var trades = matching(summary.market, trade -> LedgerData.isPending(trade.lifecycle)
                        || !disjoint(ids, trade.receiptIds()));
                var visible = recentRows(Semantics.capture(rows, trades, singleton(active.id()), ctx::flowVisible), markets, ctx.filter);
                var fresh = copiedList(visible); fresh.sort(comparing(row -> !row.open)); recentList = unmodifiableList(fresh);
            }
            recentKey = key;
        }
        return recentList;
    }

    static List<Recent> recentRows(Result projection, Map<String, Row> market, RecentFilter filter) {
        var rows = new ArrayList<Recent>();
        int count = 0, limit = filter == null ? RECENT_ROWS : max(1, min(30, filter.recentRows()));
        for (Group group : projection.groups) {
            if (group.neutral && bondItem(group.item)) continue;
            if (count >= limit && !group.market) continue;
            Recent row = toRecent(group, group.marketId.isEmpty() ? null
                                    : market.get(group.marketId));
            // A canceled offer that returned everything stays in the Ledger, not Recent (owner
            // 2026-09-28).
            if (row == null || row.unknown || "canceled".equals(row.tag) || !row.open && count >= limit) continue;
            // Supply spends also answer to the separate saved list; acquisitions stay visible.
            // A composite has no item quantity; its name must still match a saved hide rule.
            if (!row.open && filter != null && !filter.showRecent(row.name, max(1, row.quantity),
                            row.supplyRow))
                continue;
            // A Ground Items-highlighted gain stands out in its highlight colour (owner 1.5.0, after
            // Better Loot Notifier).
            if (filter != null && row.value > 0 && !row.market)
                row.highlight = filter.highlight(row.name, row.quantity);
            rows.add(row); if (!row.open) count++;
        }
        return unmodifiableList(rows);
    }

    /** One semantic group as the Live row the page paints, with proven Market value semantics. */
    static Recent toRecent(Group group, Row row) {
        if (group == null) return null; boolean market = group.market, realizedMarket = market && group.coverage == COMPLETE;
        long marketResult = group.value;
        boolean known = MarketText.settled(group, row),
            marketSell = row != null && row.side.equals(SELL),
            marketCanceled = row != null && row.lifecycle == CANCELLED_RETURNED;
        String marketSide = row == null ? "" : row.realized() ? marketSell ? "Sold" : "Bought"
                                : MarketText.stateWord(row);
        boolean unknown = !market && group.coverage == INCOMPLETE;
        String tag = group.review ? "review" : marketCanceled ? "canceled"
                                : market ? "market" : unknown ? "unpriced" : null,
            quantity = quantityText(group), qty, label;
        if (market) {
            // Keep observed cash and canonical Result as separate facts. Recent paints Result;
            // Ledger retains the cash evidence (owner 2026-11-04).
            String detail = realizedMarket ? marketSide.isEmpty() ? "Result " + signed(marketResult)
                                    : marketSide + " · Result " + signed(marketResult) : known
                                    ? marketSide + " · Result —" : group.context;
            // A finished trade shows its quantity like any item ("×500"); the line keeps its
            // side
            // and Result (owner 2026-10-08).
            qty = known ? quantity : null; label = detail;
        } else if (group.actionGroup()) {
            // An exact named spell is its own title, so the verb plus the receipt count is the
            // honest second line. A generic action keeps its composition, which is the only
            // context that distinguishes it.
            // Owner 2026-09-28: a count per cast like any item ("×23"), never "23 receipts", so the
            // narrow quantity column leaves every name readable.
            String casts = group.count > 1 ? times(group.count) : null;
            qty = !group.name.equalsIgnoreCase(group.label) ? casts
                            : casts == null ? group.context : group.context + SEPARATOR + casts;
            label = group.label;
        } else if (!group.failedOf().isEmpty()) {
            qty = group.failedOf(); label = group.failedTip();
        } else {
            qty = quantity;
            // A run whose every unit failed so far reads as failed, whichever receipt came first.
            label = group.failed > 0 ? msg("failed") : group.label;
        }
        CostView ledgerCostView = group.table == COSTS_SUPPLIES
                        ? group.charge ? CostView.CHARGES : CostView.SUPPLIES
                        : group.table == COSTS_LOSS ? CostView.LOSS : null;
        boolean neutral = group.neutral,
            settlementValue = known && !neutral;
        long value = neutral ? 0 : settlementValue ? MarketText.settlementCash(row, marketSell) : group.value;
        var recent = new Recent(group.item, group.name, qty, value, neutral, unknown, tag, group.count,
                group.transaction, group.contributionId, label,
                positive(group.quantity), group.composite, market,
                market ? realizedMarket : !unknown, ledgerCostView, group.review,
                marketResult, settlementValue, marketSide,
                row != null && LedgerData.isPending(row.lifecycle),
                group.table == COSTS_SUPPLIES, null, group.ids);
        if (!market || recent.known) recent.add(group); return recent;
    }

    /**
     * "1 dose"/"×3 doses" for any drink, "1 portion"/"×2 portions" for pies, pizzas and
     * cakes, otherwise "×N" (a sapling planted from its pot is not a portion).
     */
    static String quantityText(Group group) {
        // Coins read as their value only (owner 2026-10-16).
        if (group.item == 995) return null;
        String name = lower(group.name, false),
            unit = rule("food", name) ? "portion"
                        : group.label.equals("Drank") ? "dose" : null;
        if (group.dose && unit != null)
            return group.quantity == 1 ? "1 " + unit : group.quantity > 1 ? times(group.quantity) + " " + unit + "s" : null;
        if (group.claim < 0)
            // A claim spend reads as its own signed count ("-1 pouch"); a pickup stays "×N".
            return "−" + max(1, group.quantity);
        return group.quantity > 1 ? times(group.quantity) : null;
    }
}

/**
 * Plugin-side facts the Live read model cannot read from the engine: the current owner's idle-badge
 * state, the presentation marks noted for that owner, the loot visibility preferences, the cached
 * client-thread PvP sample and the activity context label.
 *
 * <p>Forward-port note: the historical context also carried the notable-drop threshold, the live
 * encounter summary and the sidebar net-graph toggle. Those producers no longer exist in schema
 * 102, so nothing is fabricated here; the fields that remain are the ones the current plugin can
 * state truthfully.
 */
class LiveContext {
    static final LiveContext NONE = new LiveContext(false, null, 0, PvpState.NONE, "", false, "", false); boolean idle;

    /** Presentation-only item filter; null means every booked row stays visible. */
    RecentFilter filter;

    /** Hide gained rows whose unit price is below this value; 0 shows all. Never hides costs. */
    long minimumLoot;

    /** Cached client-thread PvP sample; presentation only. */
    PvpState pvp;

    /** Activity context label for the status line; empty means "use the owner name". */
    String activity;

    /** Freshest client-thread interaction target for the shared activity label; empty when none. */
    String target;

    /** The client is at the login screen; only read when no Grind is active. */
    boolean offline;

    /** A world hop is in flight: presentation says Reconnecting, never Logged out. */
    boolean hopping;

    LiveContext(boolean idle, RecentFilter filter, long minimumDisplayedLootValue, PvpState pvp, String activity,
            boolean offline, String target, boolean hopping) {
        this.idle = idle; this.filter = filter; minimumLoot = positive(minimumDisplayedLootValue);
        this.pvp = pvp == null ? PvpState.NONE : pvp; this.activity = string(activity);
        this.offline = offline; this.target = string(target); this.hopping = hopping;
    }

    /** Whether one flow stays on a presentation row under the current visibility settings. */
    boolean flowVisible(Flow flow) {
        if (flow == null) return false;
        // Costs, fees and deaths are never hidden by the display loot filter.
        if (flow.quantity <= 0) return true;
        // Always show beats Ground Items' hidden list and the minimum (owner 1.5.0, after Better
        // Loot Notifier); Hidden Recent items still wins on Recent.
        // Unknown / unpriced gains remain discoverable for Review; the minimum is
        // compared against the unit price so quantity cannot defeat it.
        return filter != null && filter.alwaysShows(flow) || !(flow.unitPrice > 0 && flow.unitPrice < minimumLoot) && (filter == null || filter.isFlowIncluded(flow));
    }
}

/**
 * Client-side PvP facts the plugin observes on its tick (Wilderness / PvP world, skull, Protect
 * Item, what a death would lose). Presentation only; nothing here touches accounting.
 */
@AllArgsConstructor
class PvpState {
    static final PvpState NONE = new PvpState(false, false, false, 0);
    static final Set<Integer> ALWAYS_LOST = byId("death_always_lost").keySet(); boolean possible, skulled, protectItem;

    /** GP a death here would lose; 0 where no death can be lost. */
    long risk;

    /**
     * Every held unit but the most valuable 3 (none when skulled), one more with Protect Item, at
     * today's prices; an unpriced unit counts as 0. A stack keeps units, not the whole stack.
     * Always-lost items contribute their full value without using a protected slot.
     */
    static long risk(List<Flow> held, boolean skulled, boolean protectItem) {
        var stacks = copiedList(held); stacks.removeIf(flow -> flow == null || flow.quantity <= 0);
        stacks.sort((a, b) -> Long.compare(deathValue(b), deathValue(a)));
        long keep = (skulled ? 0 : 3) + (protectItem ? 1 : 0), risk = 0;
        for (Flow stack : stacks) {
            long kept = ALWAYS_LOST.contains(stack.item) ? 0 : min(keep, stack.quantity); keep -= kept;
            risk = sum(risk, multiply(positive(deathValue(stack)), stack.quantity - kept));
        }
        return risk;
    }

    /**
     * What a death weighs the item at. Since 2026-07-29 the game ranks every Avernic defender at
     * 600,000, its repair cost when it breaks unprotected (OSRS Wiki).
     */
    static long deathValue(Flow stack) {
        return matches("avernic", lower(stack.name)) ? 600_000
                : stack.unitPrice;
    }
}

/**
 * Presentation-only item eligibility for the Live Recent rows. The panel binds the current {@code
 * LootPresentationFilterService} decision behind this seam so the kit stays free of plugin
 * dependencies and the existing filter authority stays the only policy.
 */
@FunctionalInterface
interface RecentFilter {
    /**
     * @return true when presentation may show a row carrying this flow
     */
    boolean isFlowIncluded(Flow flow);

    /** An Always show item: shown whatever Ground Items or the minimum value say. */
    default boolean alwaysShows(Flow flow) {
        return false;
    }

    /** Ground Items' highlight colour for a highlighted item; null when it is not one. */
    default Color highlight(String name, long quantity) {
        return null;
    }

    /**
     * A supply spend row also answers to the separate supply list, so its family's spends can be
     * hidden while the item's other rows (a gain) still show.
     */
    default boolean showRecent(String name, long quantity, boolean supply) {
        return true;
    }

    /** Visible Recent rows; the reader clamps the setting to one through thirty. */
    default int recentRows() {
        return LiveSnapshot.RECENT_ROWS;
    }

    default void hideRecent(String name, boolean supply) {}

    /** Changes whenever the decisions may change, so the Live list is rebuilt only then. */
    default String version() {
        return "";
    }
}

/**
 * Small presentation-only Ground Items preference adapter. It keeps the user's list choices
 * available to filtered projections, but does not retain a second price/rule evaluator or any
 * accounting state.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class LootPresentationFilterService {
    static final int NONE = 0, HIGHLIGHTED = 1, HIDDEN = 2; final GroundItemsConfigReader configReader;
    volatile GroundItemsPrefs snapshot; volatile String fingerprint = "";

    /** Test helper with a fixed snapshot (no live ConfigManager). */
    LootPresentationFilterService(GroundItemsPrefs snapshot) {
        configReader = null; replaceSnapshotForTests(snapshot);
    }

    synchronized void refresh() {
        if (configReader == null) return; replaceSnapshotForTests(configReader.read());
    }

    /** Applies only explicit list preferences; ambiguous value/price rules stay visible. */
    boolean isFlowIncluded(Flow flow, LootFilter mode) {
        var effective = mode == null ? LootFilter.ALL_ITEMS : mode;
        if (effective == LootFilter.ALL_ITEMS || flow == null) return true; ensureSnapshot();
        var current = snapshot; if (!current.pluginEnabled) return true;
        int state = listState(current.highlights, current.hiddenCsv, flow.name,
                        positive(Math.abs(flow.quantity)));
        return state != HIDDEN && (state == HIGHLIGHTED
                || effective != LootFilter.HIGHLIGHTED_LIST_ONLY && !current.highlightedOnly);
    }

    /** Ground Items' highlight colour for an item on its highlighted list, unless filters are off. */
    Color highlight(String name, long quantity, LootFilter mode) {
        ensureSnapshot(); var current = snapshot;
        return mode == LootFilter.ALL_ITEMS || !current.pluginEnabled
                        || listState(current.highlights, "", name, quantity) != HIGHLIGHTED ? null
                : current.highlight;
    }

    /** HUD+'s big-drop line: Ground Items' insane value when the display follows it, else 10M. */
    long big(LootFilter mode) {
        ensureSnapshot(); var current = snapshot;
        return mode == LootFilter.FOLLOW_GROUND_ITEMS && current.pluginEnabled && current.insane > 0 ? current.insane
                : HudTray.BIG_DROP;
    }

    synchronized void replaceSnapshotForTests(GroundItemsPrefs value) {
        snapshot = value == null ? GroundItemsPrefs.disabled() : value; fingerprint = snapshot.fingerprint;
    }

    synchronized void ensureSnapshot() {
        if (snapshot == null) refresh(); if (snapshot == null) replaceSnapshotForTests(null);
    }

    static int listState(String highlights, String hiddenCsv, String name, long quantity) {
        int exact = listMatch(highlights, name, quantity, false); if (exact != NONE) return HIGHLIGHTED;
        exact = listMatch(hiddenCsv, name, quantity, false); if (exact != NONE) return HIDDEN;
        int wildcard = listMatch(highlights, name, quantity, true);
        return wildcard != NONE ? HIGHLIGHTED : listMatch(hiddenCsv, name, quantity, true) == NONE ? NONE : HIDDEN;
    }

    static int listMatch(String csv, String title, long quantity, boolean wildcard) {
        String name = string(title);
        for (Rule rule : rules(csv))
            if (rule.wildcard == wildcard && rule.matches(name, quantity)) return 1;

        return NONE;
    }

    /** Parsed list rules by list text: the Ground Items lists are parsed once, not per item. */
    static final Map<String, List<Rule>> RULES = new ConcurrentHashMap<>();

    static List<Rule> rules(String csv) {
        if (RULES.size() > 16) RULES.clear();
        return RULES.computeIfAbsent(string(csv), text ->
                matching(list(Text.fromCSV(text), Rule::parse), Objects::nonNull));
    }

    @AllArgsConstructor
    static class Rule {
        String name; int quantity;
        boolean lessThan, wildcard;

        static Rule parse(String value) {
            if (blank(value)) return null; String text = value.trim();
            int split = -1;
            for (int i = text.length() - 1; i >= 0; i--) {
                char c = text.charAt(i); if (Character.isDigit(c) || Character.isWhitespace(c)) continue;
                if (c == '<' || c == '>') split = i; break;
            }
            int quantity = 0; boolean less = false;
            if (split >= 0 && split + 1 < text.length())
                try {
                    quantity = parseInt(text.substring(split + 1).trim()); less = text.charAt(split) == '<';
                    text = text.substring(0, split).trim();
                } catch (NumberFormatException ignored) {
                }

            return new Rule(text, max(0, quantity), less, text.contains("*"));
        }

        boolean matches(String title, long count) {
            return (wildcard ? WildcardMatcher.matches(name, title) : name.equalsIgnoreCase(title)) && (lessThan ? count < quantity : count > quantity);
        }
    }
}

/**
 * Reads only the active Ground Items list preferences through supported RuneLite APIs. Never
 * reflects into private Ground Items fields or package-private matchers.
 */
@Singleton
@AllArgsConstructor(onConstructor_ = @Inject)
class GroundItemsConfigReader {
    static final Logger log = LoggerFactory.getLogger(GroundItemsConfigReader.class); final ConfigManager config;
    final PluginManager pluginManager;

    GroundItemsPrefs read() {
        boolean enabled = isGroundItemsEnabled(); GroundItemsConfig config;
        try {
            config = this.config.getConfig(GroundItemsConfig.class);
        } catch (RuntimeException ex) {
            log.debug(msg("groundItems"), ex); return GroundItemsPrefs.disabled();
        }
        if (config == null) return GroundItemsPrefs.disabled();
        var read = new GroundItemsPrefs(enabled, config.getHighlightItems(), config.getHiddenItems(),
                        config.showHighlightedOnly());
        read.insane = config.insaneValuePrice(); read.highlight = config.highlightedColor();
        read.fingerprint = read.buildFingerprint(); return read;
    }

    boolean isGroundItemsEnabled() {
        if (pluginManager == null) return false;
        for (var plugin : pluginManager.getPlugins())
            if (plugin instanceof GroundItemsPlugin) return pluginManager.isPluginEnabled(plugin);

        return false;
    }
}

/**
 * Immutable snapshot of the Ground Items list preferences readable through the supported {@code
 * ConfigManager.getConfig(GroundItemsConfig.class)} API. It does not retain a second price/rule
 * evaluator or touch private plugin fields.
 *
 * <p>Verified against RuneLite Ground Items (config group {@code grounditems}, public {@code
 * GroundItemsConfig} surface).
 */
@EqualsAndHashCode(of = "fingerprint")
class GroundItemsPrefs {
    /** The Ground Items config group whose changes invalidate this snapshot. */
    static final String GROUP = "grounditems";

    boolean pluginEnabled; String highlights, hiddenCsv;
    boolean highlightedOnly;

    /**
     * Ground Items' insane value: HUD+'s big-drop line while the loot display follows Ground Items.
     */
    long insane = HudTray.BIG_DROP;

    /** Ground Items' highlighted-item colour, for highlighted drops on the tray and Live. */
    Color highlight;

    String fingerprint;

    GroundItemsPrefs(boolean pluginEnabled, String highlights, String hiddenCsv, boolean showHighlightedOnly) {
        this.pluginEnabled = pluginEnabled; this.highlights = string(highlights);
        this.hiddenCsv = string(hiddenCsv); highlightedOnly = showHighlightedOnly; fingerprint = buildFingerprint();
    }

    /** Empty / disabled fallback — callers treat filtering as pass-through. */
    static GroundItemsPrefs disabled() {
        return new GroundItemsPrefs(false, "", msg("vialAshes"), false);
    }


    String buildFingerprint() {
        return pluginEnabled + "|" + highlights + "|" + hiddenCsv + "|" + highlightedOnly + "|" + highlight;
    }
}

/**
 * Game-knowledge tables (item ids, region ids, names) bundled under /com/gpmanager/model as
 * tab-separated resources: one row per line, {@code #} lines are comments. GameDataTablesTest
 * checks every table against RuneLite's named constants.
 */
@UtilityClass
class GameData {
    final String GENERAL = "General", GP_UNIT = " gp",

        SEPARATOR = " · ";

    /** Rule tables alone are cached: callers of legacy rows still receive independent arrays. */
    final Map<String, List<String[]>> ROWS = new HashMap<>();

    final Map<String, Map<String, Clause[][]>> RULES = new HashMap<>();

    synchronized List<String[]> cached(String name) {
        return ROWS.computeIfAbsent(name, GameData::rows);
    }

    /** Whole-column text aliases may contain the existing alternative/conjunction grammar. */
    String text(String value) {
        return value.matches("@[\\w.-]+") ? msg(value.substring(1)) : value;
    }

    /** The rows of {@code name}.tsv; a missing table is a packaging bug, so it fails loudly. */
    List<String[]> rows(String name) {
        var stream = GameData.class.getResourceAsStream("/com/gpmanager/model/" + name + ".tsv");
        if (stream == null) throw new IllegalStateException("Missing game data table " + name);
        var rows = new ArrayList<String[]>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine())
                if (!line.isEmpty() && !line.startsWith("#")) rows.add(line.split("\t", -1));

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return rows;
    }

    /** {@code name}.tsv keyed by its first-column id, valued by its second column. */
    Map<Integer, String> byId(String name) {
        return index(rows(name), row -> parseInt(row[0]), row -> row[1]);
    }

    /** Whole-string matching with the existing cached table pattern. */
    boolean matches(String key, String value) {
        return pattern(key).matcher(value).matches();
    }

    int[] intColumn(String name, int column) {
        return cached(name).stream().mapToInt(row -> parseInt(row[column])).toArray();
    }

    /** Ordered first-column names; useful for catalogues whose first match wins. */
    List<String> names(String name) {
        return list(rows(name), row -> row[0]);
    }

    /** Exact first-column names mapped to their second-column labels. */
    Map<String, String> byName(String name) {
        return index(rows(name), row -> row[0], row -> row[1]);
    }

    /** Ordered boolean rules: | alternatives, + conjunctions, compiled once by each catalogue. */
    synchronized Map<String, Clause[][]> rules(String name) {
        return RULES.computeIfAbsent(name, key -> {
            var compiled = new LinkedHashMap<String, Clause[][]>();
            for (var row : cached(key))
                compiled.put(row[0], stream(text(row[1]).split("\\|", -1)).filter(d -> !d.isEmpty()).map(d ->
                                                stream(d.split("\\+", -1)).map(Clause::new)
                                                        .toArray(Clause[]::new)).toArray(Clause[][]::new));
            return compiled;
        });
    }

    final Map<String, String> TEXT = byName("text");
    final Map<String, Pattern> PATTERNS = index(rows("patterns"), row -> row[0], row ->
            Pattern.compile(row[1].startsWith("@") ? msg(row[1].substring(1)) : row[1], parseInt(row[2])));

    Pattern pattern(String key) {
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
        String text = TEXT.get(key); if (text == null) throw new IllegalStateException("Missing text " + key);
        return text;
    }
}

/** Presentation-only signed totals: preserve both nonnegative buckets until the final subtraction. */
class ValueTotal {
    long positive, negative;

    void add(long value) {
        add(positive(value), value < 0 ? abs(value) : 0);
    }

    void add(ValueTotal other) {
        add(other.positive, other.negative);
    }

    void add(long income, long cost) {
        positive = sum(positive, income); negative = sum(negative, cost);
    }

    long value() { return subtract(positive, negative); }
}

/** Saturating long/int arithmetic; overflow clamps toward the sign of the addend. */
@UtilityClass
class SafeMath {
    /** The wall clock every accounting read uses; the rework's replay sets it to recorded time. */
    volatile LongSupplier clock = System::currentTimeMillis;

    long now() {
        return clock.getAsLong();
    }

    /** The value, or zero when it is negative. */
    long positive(long value) {
        return max(0, value);
    }

    /** A set aim: the value when it is above zero, otherwise none. */
    Long positiveOrNull(Long value) {
        return value != null && value > 0 ? value : null;
    }

    /** Absolute value that saturates instead of overflowing on {@code Long.MIN_VALUE}. */
    static long abs(long value) {
        return value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
    }

    long sum(long left, long right) {
        try {
            return addExact(left, right);
        } catch (ArithmeticException ex) {
            return right >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    long subtract(long left, long right) {
        try {
            return subtractExact(left, right);
        } catch (ArithmeticException ex) {
            return right < 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    long multiply(long left, long right) {
        try {
            return multiplyExact(left, right);
        } catch (ArithmeticException ex) {
            return left >= 0 == right >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    /** A long clamped into the int range (unit prices). */
    int toInt(long value) {
        return (int) max(Integer.MIN_VALUE, min(Integer.MAX_VALUE, value));
    }

    /** Non-negative count sum saturating at {@link Integer#MAX_VALUE}. */
    int safeAddCount(int left, int right) {
        return toInt(positive(left) + positive(right));
    }
}

/** Display-name defaults and phrase matching shared by the model and the classifiers. */
@UtilityClass
class Phrases {
    boolean contains(int[] values, int value) { return stream(values).anyMatch(item -> item == value); }

    /** Independent mutable flow buffers; the ordinary ArrayList constructor remains the allocator. */
    ArrayList<Flow> flowList() { return new ArrayList<>(); }

    /** Fresh HashMap with the original iteration order and last-row-wins replacement. */
    <T, K, V> HashMap<K, V> index(Collection<T> source, Function<T, K> key, Function<T, V> value) {
        var map = new HashMap<K, V>(); for (var item : source) map.put(key.apply(item), value.apply(item));
        return map;
    }

    /** New mutable ownership, including an independently owned empty list. */
    HashMap<Integer, Long> numberMap() {
        return new HashMap<>();
    }

    LinkedHashMap<Integer, Long> orderedNumberMap() {
        return new LinkedHashMap<>();
    }

    <T> ArrayList<T> copiedList(Collection<? extends T> source) {
        return new ArrayList<>(source == null ? emptyList() : source);
    }

    /** Immutable snapshot; its source must exist, and later source edits are invisible. */
    <T> List<T> frozenList(Collection<? extends T> source) {
        return unmodifiableList(new ArrayList<>(source));
    }

    /** Null reads as empty; otherwise preserve the existing live, unmodifiable view. */
    <T> List<T> readOnlyList(List<T> source) {
        return source == null ? emptyList() : unmodifiableList(source);
    }


    String uuid() {
        return UUID.randomUUID().toString();
    }

    /** Blank activity names fall back to the General bucket. */
    String normalizeActivity(String value) {
        return fallback(value, GENERAL);
    }

    String string(String text) {
        return text == null ? "" : text;
    }

    /** Trimmed lower-case text; null reads as empty. */
    String lower(String value) {
        return lower(value, true);
    }

    /** Some chat/target rules intentionally distinguish leading spaces. */
    String lower(String value, boolean trim) {
        return value == null ? "" : (trim ? value.trim() : value).toLowerCase(ROOT);
    }

    boolean empty(String text) {
        return text == null || text.isEmpty();
    }

    boolean empty(Collection<?> values) {
        return values == null || values.isEmpty();
    }

    boolean empty(Map<?, ?> values) {
        return values == null || values.isEmpty();
    }

    boolean blank(String text) {
        return text == null || text.trim().isEmpty();
    }

    /** The trimmed text, or the fallback when it is blank. */
    String fallback(String text, String fallback) {
        return blank(text) ? fallback : text.trim();
    }

    /** Trimmed lower-case text, or null when blank. */
    String norm(String value) {
        String key = lower(value); return key.isEmpty() ? null : key;
    }

    /** True when the text contains any of the words. */
    boolean has(String text, String... words) {
        return stream(words).anyMatch(word -> text.contains(word));
    }

    /** True when any non-null term contains the already lower-case needle. */
    boolean containsFolded(String lowerNeedle, String... terms) {
        return stream(terms).anyMatch(term -> term != null && term.toLowerCase(ROOT).contains(lowerNeedle));
    }

    /**
     * A compiled table clause: contains, =exact, ^prefix, $suffix, %pattern, ~case-insensitive
     * exact (Unicode equalsIgnoreCase), with optional !.
     */
    class Clause {
        String text; int kind;
        boolean negated; Pattern pattern;

        Clause(String spec) {
            if (spec.startsWith("@")) spec = msg(spec.substring(1)); negated = spec.startsWith("!");
            if (negated) spec = spec.substring(1); kind = spec.isEmpty() ? -1 : "=^$%~".indexOf(spec.charAt(0));
            text = kind < 0 ? spec : spec.substring(1); pattern = kind == 3 ? pattern(text) : null;
        }
    }

    /** Alternatives of conjunctions; all parsing and regex compilation happens at table load. */
    boolean has(String text, Clause[][] rules) {
        return text != null && stream(rules).anyMatch(clauses -> containsAll(text, clauses));
    }

    boolean containsAll(String text, Clause[] clauses) {
        return stream(clauses).allMatch(term -> (term.kind == 0 ? text.equals(term.text) : term.kind == 1
                        ? text.startsWith(term.text) : term.kind == 2 ? text.endsWith(term.text) : term.kind == 3
                                        ? term.pattern.matcher(text).matches() : term.kind == 4
                                                        ? text.equalsIgnoreCase(term.text)
                                                        : text.contains(term.text)) != term.negated);
    }
}

@Singleton
class Valuation implements FlowValuator {
    /** Exact original market prices can prove a blighted sip without today's family quote. */
    static boolean capturedDose(List<Flow> flows) {
        if (flows.size() != 2) return false; var pairs = partialUses(flows); if (pairs.size() != 1) return false;
        var pair = pairs.get(0); var loss = flows.get(pair.lossIndex);
        var rest = flows.get(pair.remainder); int doses = dose(loss.name);
        return doses > 1 && doses <= 4 && loss.item != rest.item
                && map(loss.item) == map(rest.item) && pattern("blighted-dose").matcher(loss.name).find()
                && loss.source() == GRAND_EXCHANGE && rest.source() == GRAND_EXCHANGE
                && loss.unitPrice > 0 && loss.unitPrice % doses == 0
                && rest.unitPrice == loss.unitPrice / doses * (doses - 1);
    }

    /**
     * Whether the current world/economy permits ordinary automatic RuneLite market quotes. Hardcore
     * Worlds are their own characters and economy: prices there come only from that player's own GE
     * fills (owner 2026-11-07).
     */
    enum Economy {
        NORMAL, HARDCORE, UNSUPPORTED_SPECIAL, UNKNOWN
    }

    /**
     * Explicit RuneLite quote route. The boolean is GP Manager's own policy choice, never the
     * user's global RuneLite display-price preference, so identical observations book the same
     * amount. Production binds this to {@code ItemManager::getItemPriceWithSource}.
     */
    interface MarketQuote {
        long priceOf(int id, boolean active);
    }

    static final Map<String, String> PARTS = byName("partial_food");
    /** Untradeables the game refuses to alch and that have no use left (no_value.tsv). */
    static final Set<Integer> WORTHLESS = byId("no_value").keySet();

    IntFunction<ItemComposition> compositions; MarketQuote marketQuotes;
    IntPredicate unsupportedMapping; GpManagerConfig config;
    @Getter volatile Economy economyState;

    /** Karamja gloves 1-4 worn: sampled by the plugin, read when a proxy rate is chosen. */
    volatile boolean gloves;

    /**
     * Owner 2026-11-07: what each item last traded for on a Hardcore World GE, per item (shared by
     * every Hardcore character; main worlds never read it).
     */
    final Map<Integer, Long> learned = new ConcurrentHashMap<>();

    /** A market quote floored at zero; an unavailable quote reads 0 (the flow stays unpriced). */
    long quote(int id) {
        if (hardcore()) return amount(learned, id);
        try {
            return positive(marketQuotes.priceOf(id, true));
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
                items::getItemPriceWithSource,
                id -> hasUnsupportedMapping(items, id), Economy.UNKNOWN);
    }

    Valuation(GpManagerConfig config, IntFunction<ItemComposition> compositions, MarketQuote marketQuotes,
            IntPredicate unsupportedMapping, Economy economyState) {
        this.config = config; this.compositions = compositions; this.marketQuotes = marketQuotes;
        this.unsupportedMapping = unsupportedMapping == null ? id -> false : unsupportedMapping;
        this.economyState = economyState == null ? Economy.UNKNOWN : economyState;
    }

    public List<Flow> value(Map<Integer, Long> changes) {
        return value(changes, 0);
    }

    public List<Flow> value(Map<Integer, Long> changes, long at) {
        var prices = priceOverrides();
        var flows = matching(list(changes.entrySet(), entry -> valueOne(entry.getKey(), entry.getValue(), prices, at, true)),
                Objects::nonNull);
        flows.sort(comparingLong((Flow flow) -> abs(flow.value)).reversed()); return flows;
    }

    /**
     * Revalues only a confirmed Drink action. Ordinary valuation remains a quote of each observed
     * item variant; this hook is deliberately separate so a decant, transfer or production shape
     * cannot be mistaken for consumption before action settlement.
     */
    public List<Flow> normalizeConsumedFlows(List<Flow> flows, ActionKind action, long at) {
        if (empty(flows) || action != DRINK || isDecant(flows))
            return flows;
        var prices = priceOverrides(); var pairs = partialUses(flows); if (pairs.isEmpty()) return flows;
        // Each exact pair is an atomic accounting unit. Compute both replacement flows before
        // assigning either one; an unsupported basis returns null and leaves the caller's
        // original captured list untouched for Review.
        var normalized = copiedList(flows);
        for (var pair : pairs)
            if (!normalizePotionPair(normalized, pair.lossIndex, pair.remainder, prices,
                    at))
                return null;

        return normalized;
    }

    Flow doseAuthority(Flow flow, Map<Integer, Long> prices, long now) {
        if (flow == null) return null; String one = lower(doseBase(flow.name) + "(1)");
        Flow geCandidate = null;
        try {
            for (int id : getVariations(map(flow.item))) {
                Flow unit = valueOne(id, 1, prices, now, pattern("blighted-dose").matcher(flow.name).find());
                String unitName = unit == null ? "" : unit.name; if (unitName == null || !one.equals(lower(unitName))) continue;
                if (unit.source() == MANUAL_OVERRIDE) return unit;
                if (unit.source() == GRAND_EXCHANGE && geCandidate == null)
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
    Flow doseFamilyQuote(int identifier, String name, Map<Integer, Long> prices) {
        int dose = dose(name); String base = doseBase(name); if (dose <= 0 || base.isEmpty()) return null;
        int anchor = 0; long anchorPrice = 0;
        boolean manual = false;
        try {
            for (int id : getVariations(map(identifier))) {
                if (id == identifier) continue; String otherName = compositionName(id);
                int other = dose(otherName);
                if (other <= 0 || !base.equalsIgnoreCase(doseBase(otherName)))
                    continue;
                Long override = prices.get(id);
                if (override != null && override > 0) {
                    if (!manual || other > anchor) {
                        manual = true; anchor = other; anchorPrice = override;
                    }
                    continue;
                }
                if (manual || other <= anchor) continue; long quote = quote(id);
                if (quote > 0) {
                    anchor = other; anchorPrice = quote;
                }
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        if (anchor <= 0 || anchorPrice <= 0) return null; long scaled = anchorPrice * dose / anchor;
        return scaled <= 0 ? null : new Flow(identifier, name, 1, scaled, scaled, manual ? MANUAL_OVERRIDE : GRAND_EXCHANGE, 0);
    }

    /** One variation family member's display name, "" when the definition is unavailable. */
    String compositionName(int id) {
        try {
            var composition = compositions.apply(id); return composition == null ? "" : string(composition.getName());
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private boolean normalizePotionPair(List<Flow> normalized, int index, int remainderIndex,
            Map<Integer, Long> prices, long now) {
        Flow loss = normalized.get(index), rest = normalized.get(remainderIndex);
        int lostDose = dose(loss.name), dose = dose(rest.name);
        if (lostDose <= 0 || dose <= 0 || lostDose != dose + 1 || !doseBase(loss.name)
                        .equals(doseBase(rest.name)))
            return false;
        try {
            // A confirmed sip retains the owner's captured high-alch policy. Do not invent
            // market prices or change either quote; unsupported/overflowing pairs stay Review.
            if (alch && loss.source() == HIGH_ALCHEMY && rest.source() == HIGH_ALCHEMY) {
                multiplyExact(loss.quantity, loss.unitPrice); multiplyExact(rest.quantity, rest.unitPrice);
                return loss.unitPrice >= rest.unitPrice;
            }
            Flow authority = doseAuthority(loss, prices, now);
            if (!compatibleWithAuthority(loss, authority) || !compatibleWithAuthority(rest, authority)) return false;
            long lossUnit = multiplyExact(lostDose, authority.unitPrice),
                remainderUnit = multiplyExact(dose, authority.unitPrice);
            Flow normalizedLoss = loss.raid(new Flow(loss.item, loss.name, loss.quantity, toIntExact(lossUnit),
                            multiplyExact(loss.quantity, lossUnit), authority.source(),
                            authority.pricedAt())),
                normalizedRemainder = rest.raid(new Flow(rest.item, rest.name, rest.quantity,
                            toIntExact(remainderUnit), multiplyExact(rest.quantity, remainderUnit),
                            authority.source(), authority.pricedAt()));
            normalized.set(index, normalizedLoss); normalized.set(remainderIndex, normalizedRemainder);
            return true;
        } catch (ArithmeticException ex) {
            return false;
        }
    }

    private static boolean compatibleWithAuthority(Flow flow, Flow authority) {
        return flow != null && authority != null && (authority.source() == GRAND_EXCHANGE
                ? flow.source() == GRAND_EXCHANGE && flow.unitPrice > 0 : authority.source() == MANUAL_OVERRIDE
                        && (flow.source() == GRAND_EXCHANGE && flow.unitPrice > 0 || flow.source() == MANUAL_OVERRIDE));
    }

    /**
     * One measured charge component valued through the same policy as ordinary observations:
     * deferred claim, face value, explicit manual override, the mapping guard, the world/economy
     * gate, the explicit active RuneLite market route and UNPRICED otherwise.
     */
    Flow valueComponent(int id, long delta, long now) {
        return valueOne(id, delta, priceOverrides(), now, true);
    }

    public boolean automaticMarketQuotesAvailable() {
        return economyState == Economy.NORMAL || hardcore();
    }

    public boolean hardcore() {
        return economyState == Economy.HARDCORE;
    }

    /** A Hardcore World GE fill teaches its price per item; false (nothing learned) anywhere else. */
    boolean learn(int id, long spent, int quantity) {
        if (!hardcore() || quantity <= 0) return false;
        learned.put(id, spent / quantity); return true;
    }

    /** The learned prices as item=price pairs (the price-override format) for the setting that keeps them. */
    String learned() {
        return learned.toString().replaceAll("[{}]", "");
    }

    /**
     * Client-thread world/economy refresh. UNKNOWN and special economies fail automatic market
     * quotes closed; manual override and face-value policy still apply.
     */
    void setEconomy(Economy state) {
        economyState = state == null ? Economy.UNKNOWN : state;
    }

    /** Client-thread Karamja-gloves snapshot; picks the improved proxy rate while worn. */
    void setGloves(boolean gear) {
        gloves = gear;
    }

    /**
     * Main-game worlds keep automatic quotes. Separate economies and an absent world fail closed.
     */
    static Economy economyFor(Collection<WorldType> types) {
        if (types == null) return Economy.UNKNOWN;
        for (var type : types) {
            if (type == null) return Economy.UNKNOWN;
            if (ActionSignals.rule("special-world", type.name())) return Economy.UNSUPPORTED_SPECIAL;
        }
        return types.contains(WorldType.HARDCORE_MAIN) ? Economy.HARDCORE : Economy.NORMAL;
    }

    /**
     * Owner 2026-10-09 (1.3.0): an ironman who chose high alch values items at their alchemy price;
     * ironmen cannot sell on the Grand Exchange. The plugin sets it each tick from the account
     * type.
     */
    volatile boolean alch;

    Flow valueOne(int id, long delta, Map<Integer, Long> prices, long at,
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
            claim |= matches("clue", name);
            // An empty vial is a physical neutral; filled variants remain ordinary items.
            if (id == VIAL_EMPTY) return null; boolean manual = prices.containsKey(id), market = automaticMarketQuotesAvailable();
            int faceValue = id == COINS ? 1 : id == PLATINUM ? 1000 : 0;
            // For ordinary items, a user override is authoritative even before RuneLite prices
            // load; fixed currencies below retain their face value by policy.
            long quote = 0;
            // RuneLite's ItemManager price helper is intentionally convenient: when an item has
            // no direct quote it can recurse through ItemMapping and price an untradeable variant
            // from another item (for example unidentified minerals or golden nuggets via coal).
            // Those catalogue entries are non-GP currencies in GP Manager, so their physical
            // identity must not inherit a monetary identity from the platform mapping. An
            // explicit owner override remains authoritative.
            if (!manual && !claim && faceValue == 0 && !CURRENCIES.contains(id)
                    && market && !unsupportedMapping.test(id))
                // GP Manager's single automatic market policy: the explicit active route,
                // gated by the current world/economy and the mapping guard above.
                quote = quote(id);
            // A proxy row values the component from an anchor and its fixed exchange rate.
            // The proxy's market item is priced only where market quotes are, never on unknown or
            // special worlds (Codex review 1.2).
            int[] proxy = !manual && quote == 0 && market ? PROXIES.get(id)
                            : null;
            int units = 1; Flow baseQuote = null;
            if (proxy != null) {
                // Some exchanges improve while Karamja gloves are worn; the proxy row may
                // carry the improved units after a slash (tokkul: 300,000 or 260,000 per onyx).
                units = proxy[1];
                if (gloves) {
                    int glovesUnits = GLOVES_UNITS.getOrDefault(id, 0); if (glovesUnits > 0) units = glovesUnits;
                }
                // Preserve a positive quote even when the per-unit whole-GP floor is zero.
                quote = quote(proxy[0]);
                // Identity pairs may inherit a known own/base value, never another proxy chain.
                if (quote == 0 && units == 1 && !PROXIES.containsKey(proxy[0]))
                    baseQuote = valueOne(proxy[0], 1, prices, at, false);
            }
            // RuneLite's ItemMapping carries no bracelet of ethereum entry (upstream omission,
            // verified 2026-10-12): the charged bracelet prices from its uncharged form's live
            // quote, the same relationship the revenant weapons already have.
            if (quote == 0 && id == WILD_CAVE_BRACELET_CHARGED && !manual
                    && market && !unsupportedMapping.test(id))
                // No quote: the flow stays unpriced for Review.
                quote = quote(WILD_CAVE_BRACELET_UNCHARGED);
            // A partial dose RuneLite cannot quote (Burning amulet(4)) is priced from its family's
            // highest-dose quote at the same rate per dose (owner 1.3.4, GP-Per-Hour's fractional
            // model); a manual override anywhere in the family is authoritative.
            Flow doseQuote = null;
            if (quote == 0 && doseFallback && !manual && !claim && faceValue == 0
                    && market && !CURRENCIES.contains(id)
                    && !unsupportedMapping.test(id))
                doseQuote = doseFamilyQuote(id, name, prices);
            // Half a pie, a pizza half or a cake slice is its share of the whole one's quote
            // (owner live 2026-10-16: Half a summer pie read "?").
            String part = PARTS.get(String.valueOf(id));
            if (quote == 0 && part != null && !manual && market) {
                String[] share = part.split(" "); quote = multiply(quote(parseInt(share[0])), parseInt(share[1])) / parseInt(share[2]);
            }
            long price; PriceSource source;
            if (claim) {
                // A non-tradeable key is an unopened claim, not held wealth. Its
                // contents become ordinary valued inventory gains only on receipt.
                price = 0; source = PriceSource.DEFERRED_CLAIM;
            } else if (faceValue > 0) {
                price = faceValue; source = FACE_VALUE;
            } else if (manual) {
                price = prices.get(id); source = MANUAL_OVERRIDE;
            } else if (alch && composition != null && composition.getHaPrice() > 0 && !WORTHLESS.contains(id)) {
                price = composition.getHaPrice(); source = HIGH_ALCHEMY;
            } else if (quote > 0) {
                price = quote / units; source = price == 0 ? NO_VALUE : proxy == null ? GRAND_EXCHANGE : CURRENCY_PROXY;
            } else if (baseQuote != null && baseQuote.source() != UNPRICED
                    && baseQuote.source() != PriceSource.DEFERRED_CLAIM && baseQuote.source() != UNKNOWN) {
                price = baseQuote.unitPrice; source = price > 0 ? CURRENCY_PROXY : NO_VALUE;
            } else if (doseQuote != null) {
                // The derived price follows its family anchor's source so provenance stays honest.
                price = doseQuote.unitPrice; source = doseQuote.source();
            } else if (composition != null && (ALCH_CURRENCIES.contains(id)
                    || !CURRENCIES.contains(id)
                            && (!composition.isTradeable() || market && (hardcore() || quote(NATURERUNE) > 0)))) {
                // The mapping guard blocks inherited quotes, never this item's own alch value.
                // No market quote (untradeable, or tradeable once prices have loaded): its alch
                // value for every account (a crystal shard), else worth nothing (a Progress hat),
                // a known zero rather than a missing price (owner 2026-10-16). On a Hardcore World an
                // item never traded there takes its alch value too (owner 2026-11-07). With both, the GE
                // price above wins unless an ironman chose alch values. An item the game refuses
                // to alch and that has no use left (active blood essence) is worth nothing.
                price = positive(WORTHLESS.contains(id) && !ALCH_CURRENCIES.contains(id)
                                ? 0 : composition.getHaPrice());
                source = price > 0 ? HIGH_ALCHEMY : NO_VALUE;
            } else {
                price = 0; source = UNPRICED;
            }
            long value = multiply(delta, price);
            // Only a non-zero selected price is a captured price. Unpriced/deferred flows
            // deliberately retain zero so consumers cannot present a fabricated capture time.
            long captured = price > 0 && source != UNKNOWN && source != FACE_VALUE
                            ? positive(at) : 0;
            return new Flow(id, name, delta, price, value, source, captured);
        } catch (RuntimeException ignored) {
            return new Flow(id, "Item " + id, delta, 0, 0, claim ? PriceSource.DEFERRED_CLAIM : UNPRICED);
        }
    }

    /**
     * ItemManager's convenience quote recursively applies ItemMapping. Only a quantity-one,
     * non-coin mapping can represent a state/base identity; quantity/rate mappings and coin targets
     * would silently manufacture monetary identity for a physically distinct item.
     */
    static boolean hasUnsupportedMapping(ItemManager items, int id) {
        try {
            int canonical = items.canonicalize(id); return hasUnsupportedMapping(ItemMapping.map(canonical));
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    static boolean hasUnsupportedMapping(Collection<ItemMapping> mappings) {
        return mappings != null && any(mappings, mapping -> mapping.getQuantity() != 1 || mapping.getTradeableItem() == COINS);
    }

    /** The owner's manual price overrides by item id. */
    Map<Integer, Long> priceOverrides() {
        return ItemRuleParser.parsePriceOverrides(config.manualPriceOverrides());
    }
}

class ItemRuleParser {
    static Map<Integer, Long> parsePriceOverrides(String value) {
        if (blank(value)) return emptyMap(); var result = numberMap();
        for (String token : value.split(",")) {
            String[] parts = token.trim().split("=", 2); if (parts.length != 2) continue;
            try {
                int id = parseInt(parts[0].trim()); long price = Long.parseLong(parts[1].trim());
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

    /** True on a Hardcore World, its own economy (owner 2026-11-07). */
    default boolean hardcore() {
        return false;
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

}

/**
 * The item lookups accounting needs, in one place: the canonical id, the composition, the active
 * market quote and a name search. Production answers from RuneLite's ItemManager; the rework's
 * replay answers from a recording (docs/design/REWORK_PLAN.md), so a session books the same both ways.
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
        return manager == null ? emptyList() : manager.search(name);
    }
}

/**
 * Untradeable non-GP currencies. They are unpriced unless a manual override supplies a price, a
 * proxy row prices them from a market item and its fixed exchange rate, or the currency is a
 * minigame currency worth its alch value (alch_currencies.tsv). RuneLite's price helper would
 * otherwise value some of them through an item mapping. A proxy's units cell may read
 * base/with-gloves when Karamja gloves improve that exchange.
 */
class CurrencyProxyCatalogue {
    /** Minigame currencies worth their alch value (owner 2026-11-03). */
    static final Set<Integer> ALCH_CURRENCIES = byId("alch_currencies").keySet();

    /** Guarded currencies and identity rows, including the own-alch fallback catalogue. */
    static final Set<Integer> CURRENCIES = new HashSet<>(byId("currencies").keySet());

    /** Proxy rows: item id to {market item id, units per proxy item}. */
    static final Map<Integer, int[]> PROXIES = new HashMap<>();

    /** Proxy rows whose exchange improves with Karamja gloves worn: item id to gloves units. */
    static final Map<Integer, Integer> GLOVES_UNITS = new HashMap<>();

    static {
        CURRENCIES.addAll(ALCH_CURRENCIES);
        for (var row : rows("currencies"))
            if (row.length > 2) {
                String[] units = row[2].split("/"); PROXIES.put(parseInt(row[0]), new int[] {parseInt(row[1]), parseInt(units[0])});
                if (units.length > 1)
                    GLOVES_UNITS.put(parseInt(row[0]), parseInt(units[1]));
            }

    }

}

// Persistence and account ownership.


/** Coordinates account binding, detached snapshots, and ordered persistence. */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class Persistence {
    static final Logger log = LoggerFactory.getLogger(Persistence.class); final Client client;
    final ConfigManager config; final SessionRepository repository;
    final OrderedPersistenceWriter writer; final Engine engine;
    long next = 1;
    @Getter TrackingIdentity activeIdentity;

    /**
     * Set when the bound scope holds data this plugin must not rewrite (newer schema or refused
     * migration).
     */
    String reason;

    /**
     * A restore or reset is replacing the bound account; ingestion is paused until it re-primes.
     */
    volatile boolean maintenance;

    /**
     * A sidebar job (backup) holds the lock: the game thread reuses its last readiness instead of
     * waiting.
     */
    volatile boolean busy, okay;

    /** Lifecycle identity work is serialized off the RuneLite lifecycle/client callbacks. */
    final Object lock = new Object();

    ExecutorService worker; volatile boolean closing, loading, failed, needsSnapshot, switchBlocked;

    /**
     * Serializes backup file IO without holding the coordinator monitor the client thread reads.
     */
    final Object backup = new Object();

    final AtomicLong request = new AtomicLong(), epoch = new AtomicLong();
    final AtomicBoolean readySignal = new AtomicBoolean(), closed = new AtomicBoolean();

    void start() {
        synchronized (lock) {
            if (closing) return; writer.start(); epoch.incrementAndGet();
            closing = false; loading = false; failed = false; switchBlocked = false;
            needsSnapshot = false; readySignal.set(false); closed.set(false);
            if (worker == null || worker.isShutdown())
                worker = OrderedPersistenceWriter.daemonExecutor(msg("gpManagerLifecycle"));
        }
    }

    /**
     * Runs repository-directory/configuration initialization away from the RuneLite plugin
     * lifecycle callback. Identity work queued immediately afterwards remains ordered behind this
     * operation on the same executor.
     */
    void initAsync(Runnable initialization) {
        synchronized (lock) {
            // A plugin re-enable may arrive after the prior lifecycle fenced
            // this coordinator. Reopen only the lifecycle executor; writer
            // start remains ordered inside the initialization task.
            if (closing) {
                closing = false; epoch.incrementAndGet();
            }
        }
        long epoch = this.epoch.get(); var executor = executor();
        if (executor == null) {
            failed = true; return;
        }
        try {
            executor.execute(() -> {
                        try {
                            if (!closing && epoch == this.epoch.get())
                                initialization.run();
                        } catch (RuntimeException ex) {
                            if (epoch == this.epoch.get()) failed = true; log.warn(msg("startupError"), ex);
                        }
                    });
        } catch (RuntimeException ex) {
            failed = true; log.warn(msg("scheduleFailed"), ex);
        }
    }

    TrackingIdentity resolveCurrentIdentity() {
        if (config == null) return null; String profile = config.getRSProfileKey();
        long accountHash = TrackingIdentity.ACCOUNT_HASH_INVALID;
        if (client != null)
            try {
                accountHash = client.getAccountHash();
            } catch (RuntimeException ex) {
                // Client may not expose hash before login.
            }

        return !blank(profile) ? new TrackingIdentity(profile.trim(), accountHash) : null;
    }

    /**
     * Captures the current identity without touching disk, then performs the account
     * bind/load/flush on the lifecycle executor. The next game tick consumes the ready transition
     * after the worker has installed the scope.
     */
    void syncIdentityAsync() {
        switchIdentityAsync(resolveCurrentIdentity(), false);
    }

    /** Seam for deterministic lifecycle tests and explicit profile events. */
    void switchIdentityAsync(TrackingIdentity next) {
        switchIdentityAsync(next, true);
    }

    void switchIdentityAsync(TrackingIdentity next, boolean captureOnCaller) {
        runLifecycle(captureOnCaller, "identity switch", () -> switchIdentity(next, false));
    }

    /**
     * One lifecycle job on the executor: capture the old owner's latest state (on the caller when
     * asked, else on the worker), run the job unless shut down, then publish whether it is ready.
     */
    void runLifecycle(boolean captureOnCaller, String what, BooleanSupplier job) {
        long request = this.request.incrementAndGet(); fence(nextHash(), request);
        synchronized (this) {
            if (closing) return;
            // Capture the old owner's latest state before the worker can enter
            // its disk wait. A pending earlier lifecycle operation has already
            // made the same capture at its boundary.
            if (captureOnCaller && !loading && activeIdentity != null)
                scheduleSave();
            loading = true; readySignal.set(false);
        }
        var executor = executor();
        if (executor == null) {
            finishRequest(request, false); return;
        }
        try {
            executor.execute(() -> {
                        boolean ready = false;
                        try {
                            if (!captureOnCaller)
                                synchronized (this) {
                                    if (!closing && activeIdentity != null)
                                        scheduleSaveLocked();
                                }

                            if (!closing) ready = job.getAsBoolean();
                        } catch (RuntimeException ex) {
                            log.warn(msg("unableComplete") + what, ex);
                        }
                        finishRequest(request, ready);
                    });
        } catch (RuntimeException ex) {
            log.warn(msg("saveScheduleError") + what, ex); finishRequest(request, false);
        }
    }

    ExecutorService executor() {
        synchronized (lock) {
            if (closing) return null;
            if (worker == null || worker.isShutdown())
                worker = OrderedPersistenceWriter.daemonExecutor(msg("lifecycleThread"));
            return worker;
        }
    }

    void finishRequest(long request, boolean ready) {
        if (closing && needsSnapshot) finalizeShutdown(); if (request != this.request.get()) return;
        loading = false; if (ready && !closing) readySignal.set(true);
    }

    /** True once for the client tick that may resume ingestion after an async bind. */
    boolean consumeReady() {
        return readySignal.compareAndSet(true, false);
    }

    synchronized boolean switchIdentity(TrackingIdentity next, boolean capturePrevious) {
        if (next == null) return activeIdentity != null;
        if (next.equals(activeIdentity)) {
            switchBlocked = false; return true;
        }
        if (capturePrevious) fence(next.accountHash, request.incrementAndGet()); if (closing && !capturePrevious) return false;
        if (activeIdentity != null) {
            if (capturePrevious) scheduleSave();
            // The previous owner stays bound until its latest snapshot is durable.
            if (!flush(Duration.ofSeconds(5))) {
                log.warn(msg("refusingAccountSwitch")); switchBlocked = true; return false;
            }
        }
        var previous = activeIdentity;
        long previousAppliedRevision = writer.appliedRevision(), previousNextRevision = this.next; activeIdentity = next;
        try {
            repository.bindIdentity(next); var loaded = loadScope();
            if (closing && !capturePrevious) {
                rollBack(previous, previousAppliedRevision, previousNextRevision); return false;
            }
            writer.resetRevision(loaded.revision); this.next = max(loaded.revision + 1, 1);
            engine.restoreProfile(next.rsProfileKey, loaded, now());
        } catch (RuntimeException ex) {
            rollBack(previous, previousAppliedRevision, previousNextRevision); switchBlocked = true;
            log.warn(msg("switchProfileConflict"), ex); return false;
        }
        fence(next.accountHash, request.get()); switchBlocked = false;
        if (previous == null || !previous.equals(next)) engine.beginPriming(); return true;
    }

    /** A refused switch leaves the previous account bound exactly as it was. */
    void rollBack(TrackingIdentity previous, long appliedRevision, long previousNextRevision) {
        if (previous == null) repository.unbind(); else repository.bindIdentity(previous);
        activeIdentity = previous; writer.resetRevision(appliedRevision); next = previousNextRevision;
    }

    /**
     * Fence evidence with the same generation already used to reject stale lifecycle completions.
     */
    void fence() {
        fence(nextHash(), request.incrementAndGet());
    }

    long nextHash() {
        return client == null ? activeIdentity == null ? -1 : activeIdentity.accountHash : client.getAccountHash();
    }

    /** Engine then inbox, like every chest consumption, so no owner change lands inside one. */
    void fence(long hash, long generation) {
        if (engine != null) synchronized (engine) {
            engine.events.owner(hash * 1000 + generation);
        }
    }

    void onLogout() {
        if (loading) {
            // A logout advances the existing request generation too. Publish its completion after
            // the earlier bind, so discarding that stale completion cannot leave ingestion fenced.
            runLifecycle(false, "logout", () -> false); return;
        }
        fence();
        synchronized (this) {
            if (!loading && activeIdentity != null) scheduleSaveLocked();
        }
    }

    /** Account-hash invalidation is a lifecycle event; its save/load work stays off the callback. */
    void hashInvalidatedAsync() {
        runLifecycle(false, "account clear", () -> {
                    onHashInvalidated(); return false;
                });
    }

    synchronized void onHashInvalidated() {
        if (activeIdentity != null) {
            scheduleSaveLocked();
            // An unsaved owner is kept, fenced, rather than cleared; the next bind retries its save.
            if (!flush(Duration.ofSeconds(5))) {
                log.warn(msg("accountClear")); return;
            }
            if (closing) return;
        }
        engine.clearIdentity(); activeIdentity = null; repository.unbind(); writer.resetRevision(0);
        next = 1; engine.restoreProfile(null, new SavedState(), now()); switchBlocked = false;
    }

    /** Capture a save; unchanged closed sessions may reuse their cached JSON. */
    boolean scheduleSave() {
        // A lifecycle worker may hold this monitor while it waits for a disk
        // flush or loads the next account scope. Gameplay ingestion is already
        // fenced at this point, so a callback request must return without
        // waiting behind that disk work; the lifecycle boundary has its own
        // explicit snapshot path.
        if (loading) return false;
        synchronized (this) {
            return !loading && scheduleSaveLocked();
        }
    }

    /** Caller holds this coordinator monitor; used by lifecycle boundary snapshots. */
    boolean scheduleSaveLocked() {
        var intent = currentIntent(); return intent != null && writer.submit(intent);
    }

    synchronized boolean saveNow() {
        var intent = currentIntent(); if (intent == null) return false; writer.submit(intent);
        return writer.flush(Duration.ofSeconds(5));
    }

    /** In-memory persistence health for a restrained panel warning; performs no file I/O. */
    SaveStatus saveStatus() {
        return switchBlocked ? new SaveStatus(FAILED, msg("switchSavePaused"), false, "") : writer.status();
    }

    String scopeRefusal() {
        return activeIdentity == null || !repository.isBound() || !identityMatches() ? msg("currentRunescape") : writer.conflicted(repository.generation) ? writer.status().detail : reason;
    }

    /** Outcome of a reset: whether it was applied, and why not when refused. */
    static class ResetOutcome {
        @Getter boolean applied;
        @Getter String detail;

        ResetOutcome(boolean applied, String detail) {
            this.applied = applied; this.detail = string(detail);
        }
    }

    /**
     * Charter R factory reset for the bound account only: flush, write one validated pre-reset
     * backup export, commit a fresh General owner through the destructive replace, re-prime. Other
     * account folders, unassigned root legacy and every configuration key (including the frozen V1
     * snapshot) are untouched. The replace is fenced on the revision flushed here: if another
     * client advances the account first, the reset is refused (never rebased) and the in-memory
     * profile is restored, so the user can re-run it against the newer state if they still want to.
     */
    /** Delete Profile: with a validated safety copy first, or without one (owner 1.5.2). */
    synchronized ResetOutcome factoryReset(long now, boolean backup) {
        String scope = scopeRefusal(); if (scope != null) return new ResetOutcome(false, scope); maintenance = true;
        try {
            return replaceLocked(now, backup);
        } finally {
            maintenance = false;
        }
    }

    /** Commits a fresh owner (the factory reset) after a validated safety copy, or rolls back. */
    ResetOutcome replaceLocked(long now, boolean backup) {
        if (!saveNow()) return new ResetOutcome(false, msg("currentProfile")); SavedState protectedState = null;
        if (backup)
            try {
                protectedState = SessionRepository.parseBackup(JsonCodec.gson(), writeBackup(now));
            } catch (IOException | RuntimeException ex) {
                return new ResetOutcome(false, msg("preRestore") + ex.getMessage());
            }

        SavedState before;
        synchronized (engine) {
            // Sidebar edits use the engine lock, but are not ingestion. Refuse a stale safety copy
            // before replacing data, with check/snapshot/mutation atomic and all file IO outside.
            if (protectedState != null && !backupContent(engine.exportState()).equals(backupContent(protectedState)))
                return new ResetOutcome(false, msg("resetRefused") + msg("resetDataChanged"));
            before = snapshot(); fence(); engine.resetData(now);
        }
        var result = replaceNow();
        if (!result.isCommitted()) {
            engine.restoreProfile(activeIdentity.rsProfileKey, before, now);
            String why = result.kind == ReplaceOutcome.Kind.CONFLICT ? msg("anotherClientChanged") + " " : "";
            // Owner 2026-10-01 (F25): the shared outcome names the operation that was refused.
            return new ResetOutcome(false, msg("resetRefused") + why + result.message);
        }
        engine.beginPriming(); return new ResetOutcome(true, msg("freshGeneral"));
    }

    /**
     * A copy of the bound account's save in backups/, named account-time and re-read to prove it is
     * intact; only this account's newest 10 are kept.
     */
    Filepath writeBackup(long now) throws IOException {
        synchronized (backup) {
            return writeBackupLocked(now);
        }
    }

    /** Complete backup content with only root bookkeeping headers excluded from equality. */
    static JsonObject backupContent(SavedState state) {
        var json = JsonCodec.gson().toJsonTree(state).getAsJsonObject();
        json.remove("savedAtEpochMillis"); json.remove("revision"); return json;
    }

    private Filepath writeBackupLocked(long now) throws IOException {
        repository.backup.createDirectories();
        String name = account() + "-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                                .withZone(systemDefault()).format(Instant.ofEpochMilli(now));
        var file = repository.backup.joinSegment(name + ".json");
        for (int n = 2; file.exists() && n < 1000; n++)
            // "_" sorts after ".", so a same-second copy lists as the newer one and is kept
            // (1.3.1).
            file = repository.backup.joinSegment(name + "_" + n + ".json");
        SavedState state;
        synchronized (engine) {
            state = copy(engine.exportState());
        }
        String json = JsonCodec.gson().toJson(state); file.write(json, CREATE, TRUNCATE_EXISTING, WRITE);
        SessionRepository.parseBackup(JsonCodec.gson(), file); pruneBackups(10); return file;
    }

    String account() {
        return activeIdentity == null ? "profile" : activeIdentity.rsProfileKey;
    }

    /** This account's backups, newest first. */
    List<Filepath> backups() throws IOException {
        if (!repository.backup.exists()) return new ArrayList<>();
        try (var files = repository.backup.walk(1)) {
            return files.filter(f -> f.isFile() && f.getFileName().startsWith(account() + "-"))
                    .sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList());
        }
    }

    /** Deletes all but this account's newest {@code keep} backups; returns how many went. */
    int pruneBackups(int keep) throws IOException {
        var all = backups(); for (var old : all.subList(min(keep, all.size()), all.size())) old.deleteIfExists();
        return max(0, all.size() - keep);
    }

    synchronized boolean identityMatches() {
        var current = resolveCurrentIdentity(); return current != null && current.equals(activeIdentity);
    }

    /**
     * True when the bound identity matches the client and tracking may ingest gameplay. Refused
     * switches leave the previous identity bound, so the new physical account must not feed that
     * state.
     */
    boolean isTrackingReady() {
        // Read without the monitor so a restore/reset holding it pauses ingestion instead of
        // blocking the client thread behind the disk work.
        if (maintenance || loading || failed) return false; if (busy) return okay;
        synchronized (this) {
            return okay = !maintenance && !loading && !failed && identityMatches()
                            && reason == null && !writer.conflicted(repository.generation);
        }
    }

    synchronized ReplaceOutcome replaceNow() {
        var intent = currentIntent();
        return intent == null ? ReplaceOutcome.unchanged(msg("accountIdentityBound")) : writer.replaceNow(intent);
    }

    synchronized boolean flush(Duration timeout) {
        return writer.flush(timeout);
    }

    void shutdown() {
        boolean lifecycleWasPending = loading; closing = true; epoch.incrementAndGet(); fence();
        loading = false; readySignal.set(false);
        // If a lifecycle worker is already fencing an owner, it captured that
        // owner's state before entering its disk wait. Keep the worker alive so
        // it can take one final post-shutdown snapshot after that wait returns;
        // the lifecycle callback itself still never waits for the worker.
        if (lifecycleWasPending) {
            needsSnapshot = true;
            synchronized (lock) {
                if (worker == null || worker.isShutdown()) finalizeShutdown();
                else {
                    worker.execute(this::finalizeShutdown); worker.shutdown();
                }
            }
            return;
        }
        if (repository.isBound()) scheduleSave(); writer.shutdown(Duration.ofSeconds(5));
        synchronized (lock) {
            if (worker != null) worker.shutdown();
        }
    }

    /** Completes a deferred shutdown snapshot from the lifecycle worker without waiting on it. */
    void finalizeShutdown() {
        if (!needsSnapshot || !closed.compareAndSet(false, true)) return; needsSnapshot = false;
        try {
            if (repository.isBound()) scheduleSave();
        } catch (RuntimeException ex) {
            log.warn(msg("unableCapture"), ex);
        } finally {
            writer.shutdown(Duration.ofSeconds(5));
            synchronized (lock) {
                if (worker != null && !worker.isShutdown())
                    worker.shutdown();
            }
        }
    }

    /**
     * Loads the bound scope. Any schema other than the current one is left exactly as it is and makes the
     * scope read-only; it is never rewritten.
     */
    SavedState loadScope() {
        reason = null; var loaded = repository.load();
        if (!loaded.isSupportedSchema()) {
            reason = msg("localData") + loaded.schemaVersion + msg("versionModify"); log.warn(reason);
            return new SavedState();
        }
        return loaded;
    }

    /** Freeze the persisted graph under the engine monitor; encoding runs on the writer. */
    SavedState snapshotState(SavedState header) {
        synchronized (engine) {
            var state = copy(engine.savedState());
            if (header != null) {
                state.setRevision(header.revision); state.setSavedAtEpochMillis(header.savedAtEpochMillis);
                if (activeIdentity != null) state.setOwnerKey(activeIdentity.rsProfileKey);
            }
            return state;
        }
    }

    /** A detached copy of the engine's persisted state, without a save header. */
    SavedState snapshot() {
        return snapshotState(null);
    }

    WriteIntent currentIntent() {
        if (!repository.isBound() || reason != null || writer.conflicted(repository.generation))
            return null;
        // Prefer the writer's verified lineage when a prior save is still
        // updating disk lastKnown — avoids queuing another base-N snapshot.
        long baseRevision = max(repository.revision, writer.appliedRevision());
        var header = new SavedState(); header.setRevision(max(next, baseRevision + 1));
        next = header.revision + 1; header.setSavedAtEpochMillis(now());
        return new WriteIntent(activeIdentity, repository.generation, baseRevision, snapshotState(header), null);
    }
}

/**
 * Outcome of ordinary and reset persistence attempts.
 *
 * <p>Successful saves stay quiet. Persistent failures expose a compact warning with details and a
 * safe retry action in the panel.
 */
class SaveStatus {
    enum State {
        NEVER_SAVED, OK, FAILED, CONFLICT
    }

    State state; String detail;
    boolean retry; String origin;

    SaveStatus(State state, String detail, boolean retryAvailable, String recoveredFrom) {
        this.state = state; this.detail = string(detail); retry = retryAvailable; origin = string(recoveredFrom);
    }

    static SaveStatus neverSaved() {
        return new SaveStatus(NEVER_SAVED, "", false, "");
    }

    boolean isFailure() {
        return state == FAILED || state == CONFLICT;
    }
}

/**
 * The one JSON codec the plugin uses outside the injected {@link SessionRepository}: RuneLite's
 * client Gson, bound once at plugin start-up. Plugin Hub rule: never construct a fresh Gson, so
 * nothing in production code can fall back to one — an unbound codec is a programming error.
 */
class JsonCodec {
    static volatile Gson gson;

    static void bind(Gson clientGson) {
        gson = clientGson;
    }


    static Gson gson() {
        Gson bound = gson; if (bound == null) throw new IllegalStateException(msg("jsoncodecBind"));
        return bound;
    }
}

/**
 * Stable tracking ownership identity.
 *
 * <p>Primary key is the RuneLite RS profile key when available (account hash plus world-type
 * profile). Account hash alone is retained for diagnostics and for matching profiles. Display names
 * are never used as durable identity.
 *
 * <p><b>Account isolation</b> — each RS profile key owns its own General tracker, history, targets,
 * and analytics files. Switching accounts or world-type profiles must never attach one history to
 * another.
 *
 * <p><b>Settings-profile sharing</b> — RuneLite configuration profiles (plugin enablement/settings
 * presets) share the same account data when the same RS profile is logged in. They do not split or
 * duplicate accounting history.
 */
@EqualsAndHashCode(of = "rsProfileKey")
class TrackingIdentity {
    static final long ACCOUNT_HASH_INVALID = -1; String rsProfileKey;
    long accountHash;

    TrackingIdentity(String profile, long accountHash) {
        if (blank(profile)) throw new IllegalArgumentException("rsProfileKey");
        rsProfileKey = profile.trim(); this.accountHash = accountHash;
    }

    /** Filesystem-safe directory name derived from the RS profile key. */
    String folderName() {
        var out = new StringBuilder(rsProfileKey.length());
        for (int i = 0; i < rsProfileKey.length(); i++) {
            char c = rsProfileKey.charAt(i);
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_'
                    || c == '.')
                out.append(c);
            else
                out.append('_');
        }
        String name = out.toString(); return name.isEmpty() ? "profile" : name;
    }

    public String toString() {
        return msg("profileKey") + (accountHash != ACCOUNT_HASH_INVALID) + "}";
    }
}

/**
 * Immutable destination and fencing for one persistence attempt.
 *
 * <p>Queued and retry writes must carry this intent so a later account switch cannot redirect the
 * payload. {@code expectedBaseRevision} is the on-disk revision observed when the snapshot was
 * taken; under the write lock the repository refuses the save unless disk still matches.
 *
 * <p>A non-null {@code json} is an already encoded payload and {@code state} supplies its header.
 * Otherwise {@code state} owns a detached persisted graph, encoded later by the writer.
 */
class WriteIntent {
    TrackingIdentity identity; long generation, base;
    SavedState state; String json;

    WriteIntent(TrackingIdentity identity, long scopeGeneration, long expectedBaseRevision, SavedState state,
            String json) {
        if (state == null) throw new IllegalArgumentException("state"); this.identity = identity; generation = scopeGeneration;
        base = positive(expectedBaseRevision); this.state = state; this.json = json;
    }
}

/**
 * The durable account state. Contains only facts needed after restart: owners and sessions and
 * minimal unresolved claims.
 *
 * <p>Schema {@value #CURRENT_SCHEMA_VERSION} is the 1.3.4 data schema. There is no upgrade path: an
 * older save is set aside and the profile starts fresh; anything newer is preserved read-only.
 */
class SavedState extends SaveCopies {
    /** The 1.3.4 data schema. Nothing older is read: it starts fresh with no upgrade path. */
    static final int CURRENT_SCHEMA_VERSION = 110;

    @Setter int schemaVersion = CURRENT_SCHEMA_VERSION;

    @Setter long savedAtEpochMillis;
    @Setter long revision;

    /** RS profile key that owns this file; null/empty means unbound. */
    @Setter String ownerKey;

    Session generalSession, customSession; List<Session> history = new ArrayList<>();

    /**
     * IANA timezone captured for this profile's local-date analytics. Empty means uninitialized.
     */
    String profileTimeZoneId = "";

    /** Reusable My Grinds definitions: setup metadata only, never financial history. */
    List<SavedGrind> savedGrinds = new ArrayList<>();

    /** Read-only copies of owner-selected Live windows; excluded from canonical accounting. */
    List<SavedSession> savedSessions;

    @RequiredArgsConstructor
    static class SavedSession {
        final String id; final Session session;
        final List<MarketRows.Row> market; transient MoneySnapshot view;

        MoneySnapshot view() {
            if (view == null) view = new MoneySnapshot(this); return view;
        }
    }

    /**
     * Bounded GE custody continuity: the minimum durable state that keeps exact economics across
     * restart for offers whose placement was observed. Physical custody is ownership-neutral; a
     * realized Market settlement is canonical once.
     */
    List<GeOffer> geCustody = new ArrayList<>();

    /**
     * Pooled tracked-basis continuity: available per-item previously-counted value, reserved totals
     * held by open SELL custody, the per-item realization fence and the basis epoch.
     */
    @Setter TrackedBasisState trackedBasis;

    /** Last observed looting-bag stock, including confirmed dispenser additions; risk and PK loss. */
    Map<Integer, Long> lootingBag;

    /**
     * Schema-105 compact evolution: optional bounded proven action-label presentation metadata on
     * canonical retained transactions (for example an exact "Ice Burst" name). It is revalidated on
     * load, never a financial input, and disappears with its retained receipt.
     */
    List<PendingClaim> pendingClaims;

    /**
     * Schema-101 physical continuity for an unresolved local PvM death; null on schema-100 files
     * and whenever no death awaits reclaim. Evidence only — never valuation, fees or labels.
     */
    PendingDeath pendingDeathReclaim; RaidHistory.State raidHistory;

    SavedState() {}

    /**
     * Revalidates optional schema-105 action-label presentation metadata after a load. Malformed,
     * oversized or non-compatible values degrade to null; a malformed label never fails the load,
     * never backfills and never infers a spell name from rune recipes.
     */
    List<SavedGrind> savedGrinds() {
        if (savedGrinds == null) savedGrinds = new ArrayList<>(); return savedGrinds;
    }

    void setSavedGrinds(List<SavedGrind> values) {
        savedGrinds = copiedList(values);
    }

    List<GeOffer> geCustody() {
        if (geCustody == null) geCustody = new ArrayList<>(); return geCustody;
    }

    void setGeCustody(List<GeOffer> values) {
        geCustody = matching(values, value -> value != null && !value.getOfferId().isEmpty());


    }

    TrackedBasisState trackedBasis() {
        if (trackedBasis == null) trackedBasis = new TrackedBasisState(); return trackedBasis;
    }

    /**
     * One reusable My Grind definition: setup metadata and default targets only. It never owns
     * transactions, Net, Recent Loot, Active-Time progress or correction state; those stay in the
     * canonical Session history linked through the stable {@code grindId}.
     */
    @Setter
    static class SavedGrind extends SaveCopies {
        String grindId, name; Long netTargetGp, activeTimeTargetMillis;
        boolean favorite, archived;

        SavedGrind() {}

        SavedGrind(String id, String name, Long profit, Long active, boolean favorite) {
            grindId = id; this.name = name; netTargetGp = profit; activeTimeTargetMillis = active;
            this.favorite = favorite;
        }

        String id() {
            return string(grindId);
        }

        String name() {
            return string(name);
        }

        Long targetGp() {
            return positiveOrNull(netTargetGp);
        }

        Long timeTargetMillis() {
            return positiveOrNull(activeTimeTargetMillis);
        }
    }

    SavedState(Session generalSession, Session customSession, List<Session> history) {
        savedAtEpochMillis = now(); this.generalSession = generalSession;
        this.customSession = customSession; this.history = copiedList(history);
    }

    /** True only for the current schema; a newer one is preserved read-only and never rewritten. */
    boolean isSupportedSchema() {
        return schemaVersion == CURRENT_SCHEMA_VERSION;
    }


    List<Session> history() {
        if (history == null) history = new ArrayList<>(); return history;
    }

    String profileTimeZoneId() {
        return string(profileTimeZoneId);
    }

    void setProfileTimeZoneId(String value) {
        profileTimeZoneId = fallback(value, "");
    }

    List<PendingClaim> pendingClaims() {
        return readOnlyList(pendingClaims);
    }

    void setPendingClaims(List<PendingClaim> values) {
        pendingClaims = empty(values) ? null : new ArrayList<>(values);
    }

    void setPendingDeathReclaim(PendingDeath value) {
        pendingDeathReclaim = value == null || value.isEmpty() ? null : value;
    }

    /**
     * The durable physical continuity of an unresolved local PvM death: the exact quantities the
     * gravestone/retrieval service holds, the still-unsettled held-at-death whitelist, and the
     * bounded lifecycle counters. Identity and quantity only — no GP valuation, quote, fee, coffer
     * or presentation data. Canonical transactions remain the sole financial authority.
     */
    static class PendingDeath extends SaveCopies {
        List<DeathItem> outstandingItems, heldAtDeath; int wipeTicksRemaining, gravestoneAgeTicks;

        PendingDeath() {}

        PendingDeath(List<DeathItem> outstandingItems, List<DeathItem> heldAtDeath, int wipeTicksRemaining,
                int gravestoneAgeTicks) {
            this.outstandingItems = copyItems(outstandingItems); this.heldAtDeath = copyItems(heldAtDeath);
            this.wipeTicksRemaining = max(0, wipeTicksRemaining); this.gravestoneAgeTicks = max(0, gravestoneAgeTicks);
        }

        List<DeathItem> outstandingItems() {
            return readOnlyList(outstandingItems);
        }

        List<DeathItem> heldAtDeath() {
            return readOnlyList(heldAtDeath);
        }


        boolean isEmpty() {
            return outstandingItems().isEmpty() && heldAtDeath().isEmpty();
        }

        static List<DeathItem> copyItems(List<DeathItem> values) {
            if (empty(values)) return null; var copy = matching(values, value -> value != null && value.isValid());

            return copy.isEmpty() ? null : copy;
        }
    }

    /** One persisted item identity and quantity for {@link PendingDeath}. */
    static class DeathItem extends SaveCopies {
        int itemId; long quantity;

        DeathItem() {}

        DeathItem(int id, long quantity) {
            itemId = id; this.quantity = positive(quantity);
        }

        long quantity() {
            return positive(quantity);
        }

        boolean isValid() {
            return itemId > 0 && quantity() > 0;
        }
    }

    /**
     * The one durable evidence exception: an unresolved key/chest claim that must survive restart.
     * Idempotent by {@code claimId}; a claim-closing transaction carries the same id as {@code
     * sourceClaimId}, while a partly settled claim keeps its reduced {@code quantity}. Claims
     * intentionally carry no cross-session ownership edge.
     */
    static class PendingClaim extends SaveCopies {
        String claimId; int itemOrKeyId;
        long quantity, createdAtEpochMillis;

        PendingClaim() {}

        PendingClaim(String id, int itemOrKeyId, long quantity, long createdAtEpochMillis) {
            claimId = id; this.itemOrKeyId = itemOrKeyId; this.quantity = positive(quantity);
            this.createdAtEpochMillis = positive(createdAtEpochMillis);
        }

        String claimId() {
            return string(claimId);
        }

        long quantity() {
            return positive(quantity);
        }

        long createdAtEpochMillis() {
            return positive(createdAtEpochMillis);
        }

        boolean isValid() {
            return !claimId().isEmpty() && itemOrKeyId > 0 && quantity() > 0;
        }
    }
}

/**
 * Durable session store with account-scoped files, recoverable commits, and multi-client write
 * protection.
 *
 * <p>Account data lives under {@code accounts/<profileKey>/}; before an account is known, saves go
 * to the {@code unbound} folder.
 */
@Singleton
class SessionRepository {
    static final Logger log = LoggerFactory.getLogger(SessionRepository.class);

    /** 1.0 folders: saves written before 1.0 live elsewhere and are never read or rewritten. */
    static final String ACCOUNTS_DIRECTORY = "profiles",

        UNASSIGNED_MARKER = "unbound";
    Gson gson; Filepath root;

    /** Profile backups, one folder, newest 10 per account (owner 2026-09-28). */
    Filepath backup;

    boolean accounts;

    /** The bound scope's save files. */
    ScopeFiles bound;

    boolean initialized; TrackingIdentity owner;
    long revision, generation;

    @Inject
    SessionRepository(Gson codec) {
        gson = codec; accounts = true;
    }

    SessionRepository(Gson codec, Filepath dataDirectory, boolean accountAware) {
        gson = codec; accounts = accountAware; initialize(dataDirectory);
    }

    synchronized void initialize(Filepath pluginDirectory) {
        if (pluginDirectory == null) throw new IllegalArgumentException("pluginDirectory");
        Filepath rooted = pluginDirectory.rooted();
        if (initialized) {
            if (!root.equals(rooted))
                throw new IllegalStateException(msg("gpManagerRepository"));
            return;
        }
        root = rooted; backup = rooted.joinSegment("backups"); initialized = true;
        if (accounts)
            // Production starts unbound: do not write shared root history until
            // an RS profile identity is available and explicitly bound.
            bindScope(root.joinSegment(UNASSIGNED_MARKER), null);
        else
            // Single-scope / test mode: the provided directory is the store.
            bindScope(rooted, null);
    }

    void requireInit() {
        if (!initialized) throw new IllegalStateException(msg("repositoryReady"));
    }

    /** One scope directory's primary, backup, next and write-lock files. */
    static class ScopeFiles {
        Filepath dir, state, backup, next, lock;

        ScopeFiles(Filepath dir) {
            this.dir = dir; state = dir.joinSegment("sessions.json"); backup = dir.joinSegment(msg("backupSuffix"));
            next = dir.joinSegment("sessions.next.json"); lock = dir.joinSegment("sessions.write.lock");
        }
    }

    void bindScope(Filepath directory, TrackingIdentity identity) {
        bound = new ScopeFiles(directory); owner = identity; revision = 0; generation++;
    }

    /** Directory for an account identity without changing the live bind. */
    Filepath scopeDir(TrackingIdentity identity) {
        requireInit();
        return identity == null ? accounts ? root.joinSegment(UNASSIGNED_MARKER) : root : root.joinSegment(ACCOUNTS_DIRECTORY).joinSegment(identity.folderName());
    }

    /**
     * The backup file the last {@link #load()} had to fall back to; empty when the primary loaded.
     */
    volatile String recovery = "";

    /** The folder holding the bound profile's save files. */
    Filepath dataDir() {
        requireInit(); return bound.dir;
    }

    boolean isBound() {
        return !accounts || owner != null;
    }

    /** Bind writes/loads to an account scope. Does not migrate unassigned data. */
    synchronized void bindIdentity(TrackingIdentity identity) {
        requireInit(); if (identity == null) throw new IllegalArgumentException("identity");
        var accountDir = root.joinSegment(ACCOUNTS_DIRECTORY).joinSegment(identity.folderName());
        bindScope(accountDir, identity);
    }

    /** Leave any account scope; subsequent saves refuse until rebound. */
    synchronized void unbind() {
        requireInit(); bindScope(root.joinSegment(UNASSIGNED_MARKER), null);
    }

    synchronized SavedState load() {
        requireInit(); recovery = "";
        // A save from an older schema starts fresh: it is kept beside the profile, never read.
        retireOlder(bound);
        if (bound.state.exists())
            try {
                SavedState state;
                try {
                    state = readState(bound.state);
                } catch (FileSystemException locked) {
                    // Antivirus or Windows Search holding the file is no corruption: read it once
                    // more.
                    state = readState(bound.state);
                }
                // Load establishes disk truth; do not retain a stale in-memory
                // high-water mark from a primary that was later discarded.
                revision = positive(state.revision); return state;
            } catch (IOException ex) {
                log.warn(msg("unableLoad"), ex);
                // An unavailable primary is not evidence that its older backup is current.
                if (!(ex instanceof InvalidSaveException)) throw new UncheckedIOException(ex); setAside(bound);
            }

        if (bound.backup.exists())
            try {
                var i = readState(bound.backup); log.warn(msg("recoveredGp"), bound.backup.getFileName());
                revision = positive(i.revision); recovery = bound.backup.getFileName().toString(); return i;
            } catch (IOException j) {
                log.warn(msg("unableRead"), bound.backup, j);
                if (!(j instanceof InvalidSaveException)) throw new UncheckedIOException(j);
            }

        revision = 0; return new SavedState();
    }

    SavedState readState(Filepath path) throws IOException {
        try (Reader reader = path.openReader(READ)) {
            return parseState(gson, new JsonParser().parse(reader));
        } catch (RuntimeException ex) {
            if (ex instanceof JsonIOException && ex.getCause() instanceof IOException)
                throw (IOException) ex.getCause();
            throw new InvalidSaveException(msg("unreadableSession"), ex);
        }
    }

    /** A fully read payload failed JSON validation; only this permits crash-copy recovery. */
    static class InvalidSaveException extends IOException {
        InvalidSaveException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Parses one payload. */
    static SavedState parseState(Gson codec, JsonElement json) throws IOException {
        if (json == null || !json.isJsonObject()) throw new InvalidSaveException(msg("emptySession"), null);
        var state = codec.fromJson(json, SavedState.class);
        if (state == null) throw new InvalidSaveException(msg("emptySession"), null); return state;
    }

    /** Backup parse/validation with the caller's existing Gson policy and error behavior. */
    static SavedState parseBackup(Gson codec, Filepath file) throws IOException {
        return parseState(codec, new JsonParser().parse(readText(file)));
    }

    /**
     * Save using an immutable write intent. Destination and owner key come from the intent, not
     * from a later account bind.
     */
    synchronized boolean save(WriteIntent intent) {
        return commit(intent, false).isCommitted();
    }

    /**
     * Destructive replacement fenced exactly like an ordinary save: the intent's expected base
     * revision must still be the disk revision under the account lock, otherwise the newer disk
     * state wins and the reset is refused. The original intent is attempted once; it is never
     * rebased onto a newer revision, because that would be the stale overwrite the fence exists to
     * prevent.
     */
    synchronized ReplaceOutcome replaceStateDetailed(WriteIntent intent) {
        return intent == null || intent.state == null ? ReplaceOutcome.unchanged("state was null") : commit(intent, true);
    }

    /**
     * One fenced commit of {@code intent}. Returns {@code COMMITTED} once primary (and, for resets,
     * backup alignment) finished, {@code COMMITTED_PRIMARY_ONLY} when the primary was replaced but
     * a later step faulted, {@code CONFLICT} when disk no longer matches the expected base, else
     * {@code FAILED_UNCHANGED}.
     */
    ReplaceOutcome commit(WriteIntent intent, boolean reset) {
        if (intent == null || intent.state == null) throw new IllegalArgumentException("intent"); var state = intent.state;
        var identity = intent.identity;
        if (accounts && identity == null) {
            log.warn(msg("refusingGp")); return ReplaceOutcome.unchanged(msg("accountIdentityWrite"));
        }
        var target = new ScopeFiles(scopeDir(identity));
        // Owner key is taken from the intent, never from a later live bind.
        if (identity != null) state.setOwnerKey(identity.rsProfileKey);
        state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        if (state.savedAtEpochMillis <= 0) state.setSavedAtEpochMillis(now()); FileLock lock = null;
        // Set once the primary replacement has been attempted; a fault after that
        // point is classified by inspecting the primary under the still-held lock.
        boolean primaryAttempted = false;
        try {
            target.dir.createDirectories(); lock = lockForWrite(target.lock);
            if (lock == null) {
                log.warn(msg("anotherGp")); return ReplaceOutcome.unchanged(msg("anotherClientHolds"));
            }
            retireOlder(target);
            // The disk header is authoritative: an unreadable file is a retryable refusal, never
            // absence that would skip the schema guard or fall back to the older backup revision.
            var disk = header(target.state); int diskSchema = disk == null ? SavedState.CURRENT_SCHEMA_VERSION : schemaOf(disk);
            if (diskSchema != SavedState.CURRENT_SCHEMA_VERSION) {
                // Any other schema (a newer version) is preserved read-only.
                log.warn(msg("refusingOverwrite"), diskSchema); return ReplaceOutcome.unchanged(msg("diskState") + diskSchema);
            }
            // The readable header is the authoritative CAS evidence even when its body fails
            // validation below, so a damaged optional field can never authorize a stale intent
            // through the older backup. A present but unreadable primary is corruption to preserve.
            long revision = disk != null ? revisionOf(disk) : revisionOf(header(target.backup));
            // Compare-and-swap against the base revision observed when the
            // snapshot was created. Equal competing writers both claiming base N
            // must not both succeed — and a destructive reset is no exception:
            // a newer revision written by another client wins and the reset aborts.
            if (revision != intent.base) {
                log.warn(msg("refusingConcurrent"), reset ? "reset" : "save", revision, intent.base);
                if (sameScope(identity)) this.revision = revision; return ReplaceOutcome.conflict(msg("diskRevision"));
            }
            if (state.revision <= 0)
                state.setRevision(revision + 1);
            else if (state.revision <= revision) {
                log.warn(msg("refusingNon"), state.revision, revision);
                return ReplaceOutcome.unchanged(
                        msg("advanceDisk"));
            }
            try (Writer writer = target.next.openWriter(CREATE, TRUNCATE_EXISTING,
                            WRITE)) {
                if (intent.json == null) gson.toJson(state, writer); else writer.write(intent.json);
            }
            var validated = readState(target.next);
            // Recovery only runs for the caller's own lineage: the CAS above already matched.
            SavedState previous = null;
            if (disk != null) {
                try {
                    previous = readState(target.state);
                } catch (InvalidSaveException ex) {
                    preserve(target, ex);
                }
            } else if (target.state.exists())
                preserve(target, null);

            // Both guards above used this parsed primary, read under the account lock.
            if (!reset && previous != null) replaceBackup(target, msg("backupPrefix")); primaryAttempted = true;
            // One atomic move: a crash leaves either the old save or the new one, never neither.
            replaceFile(target.next, target.state); if (sameScope(identity)) this.revision = validated.revision;
            if (reset) {
                // After a reset the crash copy must not resurrect the cleared data.
                try {
                    replaceBackup(target, msg("backupReset"));
                } catch (IOException backupEx) {
                    invalidateBackup(target, msg("resetFlushError")); log.warn(msg("resetBackupError"), backupEx);
                    return ReplaceOutcome.primaryOnly(msg("primaryReset"));
                }
                return ReplaceOutcome.committed(msg("resetCommitted"));
            }
            return ReplaceOutcome.committed("Save committed");
        } catch (IOException ex) {
            log.warn(reset ? msg("unableReplace") : msg("unableSave"), ex);
            // Still under the account lock, so a primary holding our revision can
            // only be ours: classify, never re-issue the write.
            long verified = -1;
            try {
                verified = revisionOf(header(target.state));
            } catch (IOException unavailablePrimary) {
                // The replacement cannot be verified during I/O; stay unchanged and retry.
            }
            if (primaryAttempted && verified == state.revision) {
                if (sameScope(identity)) revision = state.revision; return ReplaceOutcome.primaryOnly(msg("primaryAlready"));
            }
            return ReplaceOutcome.unchanged(reset ? msg("resetDid") : msg("saveDid"));
        } finally {
            releaseLock(lock);
        }
    }

    boolean sameScope(TrackingIdentity target) {
        return owner == null && target == null || owner != null && owner.equals(target);
    }

    @AllArgsConstructor
    static class ReplaceOutcome {
        enum Kind {
            COMMITTED, COMMITTED_PRIMARY_ONLY,
            /** Disk advanced past the expected base; nothing was written. */
            CONFLICT, FAILED_UNCHANGED
        }

        Kind kind; String message;

        static ReplaceOutcome committed(String message) {
            return new ReplaceOutcome(Kind.COMMITTED, message);
        }

        static ReplaceOutcome primaryOnly(String message) {
            return new ReplaceOutcome(Kind.COMMITTED_PRIMARY_ONLY, message);
        }

        static ReplaceOutcome unchanged(String message) {
            return new ReplaceOutcome(Kind.FAILED_UNCHANGED, message);
        }

        static ReplaceOutcome conflict(String message) {
            return new ReplaceOutcome(Kind.CONFLICT, message);
        }

        boolean isCommitted() {
            return kind == Kind.COMMITTED || kind == Kind.COMMITTED_PRIMARY_ONLY;
        }
    }

    void invalidateBackup(ScopeFiles files, String reason) throws IOException {
        if (files.backup.exists()) {
            var tombstone = files.dir.joinSegment(msg("backupInvalidated") + now() + ".json");
            files.backup.moveTo(tombstone, REPLACE_EXISTING); log.warn("{} ({})", reason, tombstone.getFileName());
        }
    }

    /**
     * One authoritative top-level read of a save file. Null only when it is absent or its payload
     * is not a JSON object; an I/O failure is reported, never treated as absence.
     */
    JsonObject header(Filepath path) throws IOException {
        try {
            return path.exists() ? new JsonParser().parse(readText(path)).getAsJsonObject() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** A payload's schema; 0 when it names none. */
    static int schemaOf(JsonObject object) {
        var value = object.get("schemaVersion"); return value != null && value.isJsonPrimitive() ? value.getAsInt() : 0;
    }

    /**
     * Sets aside a primary or backup from an older schema, so the profile starts fresh. An
     * unreadable header or a failed move is never a destructive action.
     */
    void retireOlder(ScopeFiles files) {
        for (var path : new Filepath[] {files.state, files.backup})
            try {
                var object = header(path); int schema = object == null ? SavedState.CURRENT_SCHEMA_VERSION : schemaOf(object);
                if (schema >= SavedState.CURRENT_SCHEMA_VERSION) continue;
                String name = path.getFileName().toString().replace(".json", "");
                path.moveTo(files.dir.joinSegment(name + ".schema" + schema + "-" + now() + ".json"),
                        REPLACE_EXISTING);
                log.warn(msg("olderSave"), schema);
            } catch (IOException ex) {
                log.warn(msg("unablePreserve"), ex);
            }

    }

    /** A payload's stored revision; 0 when it names none or is null. */
    static long revisionOf(JsonObject object) {
        if (object == null) return 0; var value = object.get("revision");
        return value != null && value.isJsonPrimitive() ? value.getAsLong() : 0;
    }

    // Package visibility permits deterministic disk-failure tests without
    // introducing production fault switches.
    void replaceFile(Filepath source, Filepath target) throws IOException {
        try {
            atomicMove(source, target);
        } catch (AtomicMoveNotSupportedException ex) {
            // Windows/network volumes without atomic rename: plain replace of a validated file.
            source.moveTo(target, REPLACE_EXISTING);
        } catch (AccessDeniedException ex) {
            // Antivirus or Windows Search can hold a just-written file for a moment; one more try
            // often clears it, and the writer retries later without alarming anyone (owner 1.2).
            atomicMove(source, target);
        }
    }

    static String readText(Filepath path) throws IOException {
        try (Reader reader = path.openReader(READ)) {
            var text = new StringBuilder(); char[] buffer = new char[4096];
            int count; while ((count = reader.read(buffer)) >= 0) text.append(buffer, 0, count); return text.toString();
        }
    }

    void atomicMove(Filepath source, Filepath target) throws IOException {
        source.moveTo(target, REPLACE_EXISTING, ATOMIC_MOVE);
    }

    FileLock lockForWrite(Filepath lockPath) throws IOException {
        lockPath.getParent().createDirectories(); var channel = lockPath.openFileChannel(CREATE, WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException heldInThisProcess) {
            // Another repository instance in this JVM (or a test) holds the account lock.
            lock = null;
        }
        if (lock == null) {
            channel.close(); return null;
        }
        return lock;
    }

    void releaseLock(FileLock lock) {
        if (lock == null) return;
        try {
            var channel = lock.channel(); lock.release(); channel.close();
        } catch (IOException ex) {
            log.debug(msg("unableRelease"), ex);
        }
    }

    /**
     * Sets a damaged or unreadable primary aside as crash evidence, then refuses the whole save
     * when the preservation itself failed, so no payload is ever replaced unrecovered.
     */
    private void preserve(ScopeFiles files, IOException cause) throws IOException {
        setAside(files); if (files.state.exists()) throw new IOException(msg("cannotPreserve"), cause);
    }

    void setAside(ScopeFiles files) {
        try {
            if (files.state.exists()) {
                var backup = files.dir.joinSegment("sessions.corrupt-" + now() + ".json"); files.state.moveTo(backup, REPLACE_EXISTING);
            }
        } catch (IOException backupError) {
            log.warn(msg("unablePreserve"), backupError);
        }
    }

    /** Atomic backup replacement, using each operation's original temporary file name. */
    void replaceBackup(ScopeFiles files, String tempName) throws IOException {
        var temp = files.dir.joinSegment(tempName); files.state.copyTo(temp, REPLACE_EXISTING); replaceFile(temp, files.backup);
    }
}

/**
 * One ordered background writer for ordinary saves.
 *
 * <p>Each queued write carries an immutable {@link WriteIntent} so account switches cannot redirect
 * pending work. The executor is restartable across plugin disable/enable cycles.
 */
@Singleton
class OrderedPersistenceWriter {
    static final Logger log = LoggerFactory.getLogger(OrderedPersistenceWriter.class);
    static final Duration DEFAULT_SHUTDOWN_FLUSH = Duration.ofSeconds(5); SessionRepository repository;
    Object lock = new Object(); ExecutorService executor;

    /** Ordinary saves that failed in a row; the warning waits for the third. */
    int failed;

    boolean external; AtomicLong written = new AtomicLong(0);

    /** Disk truth diverged from this scope's in-memory state; retries must never rebase it. */
    AtomicLong conflict = new AtomicLong(-1);

    AtomicReference<PendingWrite> merged = new AtomicReference<>();
    AtomicReference<SaveStatus> status = new AtomicReference<>(SaveStatus.neverSaved());
    AtomicBoolean scheduled = new AtomicBoolean(false); Object monitor = new Object();
    volatile boolean accept = true,
        retryQueued;

    /**
     * The scope generation and revision of the newest write asked for and of the newest committed
     * one; neither keeps a snapshot alive.
     */
    volatile long[] asked, done;

    /** A queued write whose attempts ran out: it waits for a newer snapshot, a retry or a restart. */
    volatile PendingWrite stuck;

    /** When a shutdown stops starting retries. */
    volatile long until;

    @Inject
    OrderedPersistenceWriter(SessionRepository repository) {
        this.repository = repository; external = false; executor = daemonExecutor("gp-manager-persist");
    }

    /** Visible for tests that supply a same-thread or controlled executor. */
    OrderedPersistenceWriter(SessionRepository repository, ExecutorService executor) {
        this.repository = repository; external = true; this.executor = executor;
    }

    /** Reopens the writer; a write kept after its attempts gets another bounded cycle. */
    void start() {
        accept = true; stuck = null; var work = merged.get(); if (work != null) work.attempts = 0;
        scheduleDrain();
    }

    /** One named daemon worker thread; persistence never keeps the client alive. */
    static ScheduledExecutorService daemonExecutor(String name) {
        return new ScheduledThreadPoolExecutor(1, runnable -> {
                    var thread = new Thread(runnable, name); thread.setDaemon(true); return thread;
                });
    }

    SaveStatus status() {
        return status.get();
    }

    long appliedRevision() {
        return written.get();
    }

    void resetRevision(long revision) {
        written.set(positive(revision));
        if (status.get().state == CONFLICT && !conflicted(repository.generation))
            status.set(SaveStatus.neverSaved());
    }

    boolean conflicted(long generation) {
        return conflict.get() == generation;
    }

    /** Enqueue an ordinary save bound to an immutable write intent. */
    boolean submit(WriteIntent intent) {
        if (intent == null || intent.state == null) return false; var detached = intent.state;
        if (detached.revision <= 0) detached.setRevision(intent.base + 1);
        // Asked for even when refused below, so a flush can never report it saved.
        var next = new PendingWrite(intent); asked = new long[] {intent.generation, next.revision};
        if (conflicted(intent.generation)) return false;
        if (!accept) {
            status.set(new SaveStatus(FAILED, msg("savingStopped"), true, "")); return false;
        }
        while (true) {
            var current = merged.get();
            if (current != null) {
                // Prefer newer revision within the same scope generation.
                if (current.intent.generation == next.intent.generation && current.revision > next.revision)
                    return true;
                // A newer scope generation supersedes older pending work for a
                // previous account — but only after the coordinator drained or
                // abandoned that scope. Drop stale-scope work if a newer scope
                // is already queued.
                if (current.intent.generation > next.intent.generation) {
                    status.set(new SaveStatus(FAILED, msg("pendingSave"), false, "")); return false;
                }
            }
            if (merged.compareAndSet(current, next)) break;
        }
        // The newer snapshot supersedes a write whose attempts ran out; nothing else keeps it.
        stuck = null; return scheduleDrain();
    }

    /**
     * Destructive replacement fenced on {@code intent.expectedBaseRevision}. The intent is
     * committed exactly once; a refusal (another client advanced the account) is reported as {@code
     * CONFLICT} and is never rebased or retried here — after reloading, the user re-runs the reset
     * against the newer state if they still want it.
     */
    ReplaceOutcome replaceNow(WriteIntent intent) {
        flush(DEFAULT_SHUTDOWN_FLUSH);
        if (conflicted(intent.generation))
            return ReplaceOutcome.conflict(status.get().detail);
        var detached = intent.state;
        // A pre-serialized payload already carries a revision past its expected base.
        if (intent.json == null) {
            detached.setRevision(max(intent.base, written.get()) + 1); detached.setSavedAtEpochMillis(now());
        }
        var result = repository.replaceStateDetailed(intent);
        if (result.isCommitted()) {
            publish(detached.revision, result.message, ""); return result;
        }
        boolean conflict = result.kind == ReplaceOutcome.Kind.CONFLICT;
        if (conflict) this.conflict.accumulateAndGet(intent.generation, Math::max);
        status.set(new SaveStatus(conflict ? CONFLICT : FAILED,
                        conflict ? result.message + msg("trackingPausedBack") : result.message, false, ""));
        return result;
    }

    /**
     * Waits for the newest write asked for so far, and is true only when it, or a newer snapshot
     * of the same scope generation, committed. An empty queue alone proves nothing.
     */
    boolean flush(Duration timeout) {
        var wait = timeout == null ? DEFAULT_SHUTDOWN_FLUSH : timeout; var want = asked; scheduleDrain();
        long deadline = System.nanoTime() + wait.toNanos();
        synchronized (monitor) {
            while (pending()) {
                long remaining = deadline - System.nanoTime(); if (remaining <= 0) break;
                try {
                    monitor.wait(min(TimeUnit.NANOSECONDS.toMillis(remaining) + 1, 250));
                } catch (InterruptedException ex) {
                    return false;
                }
            }
        }
        var ok = done; return want == null || ok != null && ok[0] == want[0] && ok[1] >= want[1];
    }

    /** Work is queued or running; a write whose attempts ran out waits for newer work. */
    boolean pending() {
        var work = merged.get(); return scheduled.get() || work != null && work != stuck;
    }

    void shutdown(Duration timeout) {
        until = System.nanoTime() + timeout.toNanos(); accept = false;
        // Plugin lifecycle callbacks must return without waiting for disk work.
        // The single writer is allowed to drain the already captured intent in
        // order; a later enable creates a fresh executor if this one has
        // terminated.  No new intent can be accepted after this fence.
        scheduleDrain(); if (!external && executor != null) closeLater((ScheduledExecutorService) executor);
    }

    void closeLater(ScheduledExecutorService target) {
        if (target.isShutdown()) return; if (pending()) target.schedule(() -> closeLater(target), 50, TimeUnit.MILLISECONDS);
        else target.shutdown();
    }

    boolean scheduleDrain() {
        if (retryQueued || !scheduled.compareAndSet(false, true)) return true; ExecutorService active;
        synchronized (lock) {
            if (!external && (executor == null || executor.isShutdown())) {
                if (!accept) {
                    scheduled.set(false); return false;
                }
                executor = daemonExecutor("gp-manager-persist");
            }
            active = executor;
        }
        try {
            active.execute(this::drain); return true;
        } catch (RuntimeException ex) {
            scheduled.set(false); log.warn(msg("lifecycleScheduleError"), ex);
            status.set(new SaveStatus(FAILED, msg("persistenceExecutor"), true, "")); return false;
        }
    }

    void drain() {
        try {
            while (true) {
                // The write stays queued until it commits, is superseded or meets a conflict.
                var work = merged.get(); if (work == null || work == stuck) return;
                if (conflicted(work.intent.generation)) {
                    merged.compareAndSet(work, null); continue;
                }
                if (work.attempts > 0 && !accept && System.nanoTime() - until >= 0) {
                    stuck = work; return;
                }
                int attempt = ++work.attempts; WriteIntent intent = rebase(work.intent);
                boolean ok = false;
                try {
                    ok = repository.save(intent);
                } catch (RuntimeException ex) {
                    log.warn(msg("gpManagerBackground"), ex);
                }
                // Only the bound scope's disk revision can prove a competing writer.
                long disk = intent.generation == repository.generation ? repository.revision : intent.base;
                if (ok) {
                    done = new long[] {work.intent.generation, work.revision}; merged.compareAndSet(work, null);
                    // Late completions for an abandoned scope must not rewrite
                    // the live applied revision or OK status for the new scope.
                    failed = 0;
                    if (intent.generation == repository.generation)
                        publish(work.revision, "", repository.recovery);
                    else
                        log.debug(msg("ignoringLate"), intent.generation, repository.generation);
                    continue;
                }
                State state = FAILED; String detail = msg("saveFailed");
                if (disk != intent.base) {
                    state = CONFLICT; conflict.accumulateAndGet(intent.generation, Math::max);
                    detail = msg("diskRevision") + msg("trackingPausedBack"); merged.compareAndSet(work, null);
                } else if (attempt < 3 && merged.get() == work
                        && (accept || System.nanoTime() - until < 0)
                        && retryLater(50L * attempt * attempt)) {
                    return;
                } else if (merged.get() == work)
                    stuck = work;
                // A failure the retries clear (a scanner briefly holding the file) is silent: the
                // old save is intact. Only a save request that keeps failing is shown (owner 1.2).
                if (intent.generation == repository.generation
                        && (state != FAILED || ++failed >= 3))
                    status.set(new SaveStatus(state, detail, state == FAILED, status.get().origin));
            }
        } finally {
            scheduled.set(false); if (pending() && accept && !retryQueued) scheduleDrain();
            synchronized (monitor) {
                monitor.notifyAll();
            }
        }
    }

    /** Schedule one bounded retry without blocking the writer; false when the window is closed. */
    boolean retryLater(long millis) {
        retryQueued = true;
        try {
            ((ScheduledExecutorService) executor).schedule(() -> {
                retryQueued = false; if (scheduled.compareAndSet(false, true)) drain();
            }, millis, TimeUnit.MILLISECONDS); return true;
        } catch (RuntimeException ex) {
            retryQueued = false; return false;
        }
    }

    /** Publish a successful write only after the caller's existing scope/generation guard. */
    void publish(long revision, String message, String detail) {
        written.set(revision); status.set(new SaveStatus(OK, message, false, detail));
    }

    /**
     * When this writer already committed revision {@code N} and disk still shows {@code N}, a newer
     * queued snapshot that captured base {@code < N} while that save was in flight is part of our
     * own lineage — rebase its expected base. If disk diverged from our applied revision, leave the
     * intent alone so CAS surfaces a real external conflict.
     */
    WriteIntent rebase(WriteIntent intent) {
        if (intent == null) return null; long own = written.get();
        if (own <= 0 || intent.base >= own || intent.generation != repository.generation) return intent;
        long disk = repository.revision;
        return disk != own ? intent : new WriteIntent(intent.identity, intent.generation, own, intent.state, intent.json);
    }

    static class PendingWrite {
        long revision; int attempts;
        WriteIntent intent;

        PendingWrite(WriteIntent intent) {
            this.intent = intent; revision = intent.state.revision;
        }
    }
}

/** Detached persisted graphs; inherited Object.clone copies scalars without reflection. */
class SaveCopies implements Cloneable {
    static <T extends SaveCopies> T shallow(T source) {
        if (source == null) return null;
        try {
            return (T) source.clone();
        } catch (CloneNotSupportedException ex) {
            throw new AssertionError(ex);
        }
    }

    static <T extends SaveCopies> T copy(T source) {
        return shallow(source);
    }

    /** Shallow clone first; each capture-free initializer detaches only its owned children. */
    static <T extends SaveCopies> T copy(T source, Consumer<T> fields) {
        T result = shallow(source); if (result != null) fields.accept(result); return result;
    }

    static <T, R> List<R> list(Collection<T> source, Function<T, R> copy) {
        if (source == null) return null; var result = new ArrayList<R>(source.size());
        for (T item : source) result.add(copy.apply(item)); return result;
    }

    static SavedState copy(SavedState source) {
        return copy(source, result -> {
            result.generalSession = copy(result.generalSession); result.customSession = copy(result.customSession);
            result.history = list(result.history, SaveCopies::copy);
            result.savedGrinds = list(result.savedGrinds, SaveCopies::copy);
            result.savedSessions = list(result.savedSessions, SaveCopies::copy);
            result.geCustody = list(result.geCustody, SaveCopies::copy); result.trackedBasis = copy(result.trackedBasis);
            result.lootingBag = result.lootingBag == null ? null : new LinkedHashMap<>(result.lootingBag);
            result.pendingClaims = list(result.pendingClaims, SaveCopies::copy);
            result.pendingDeathReclaim = copy(result.pendingDeathReclaim);
            result.raidHistory = result.raidHistory == null ? null : RaidHistory.copy(result.raidHistory);
        });
    }

    static Session copy(Session source) {
        return copy(source, result -> {
            result.changeListener = null; result.ownedValueObserver = null;
            result.saveStamp = 0; result.transactions = list(result.transactions, SaveCopies::copy); result.pk = copy(result.pk);
            result.compactedRaids = RaidHistory.copyMoney(result.compactedRaids);
            result.correctionHistory = list(result.correctionHistory, SaveCopies::copy);
            result.undoHistory = list(result.undoHistory, SaveCopies::copy);
        });
    }

    static Transaction copy(Transaction source) {
        return copy(source, result -> {
            result.memo = null; result.castItemId = 0; result.flows = list(result.flows, SaveCopies::copy);
        });
    }

    static PkTally copy(PkTally source) {
        return copy(source, result -> result.compactedKillNet = result.compactedKillNet == null ? null : new LinkedHashMap<>(result.compactedKillNet));
    }

    static CorrectionRecord copy(CorrectionRecord source) {
        return copy(source, result -> result.changes = list(result.changes, SaveCopies::copy));
    }

    static Map<Integer, BasisPool> copyPools(Map<Integer, BasisPool> source) {
        if (source == null) return null;
        var out = new LinkedHashMap<Integer, BasisPool>();
        source.forEach((id, pool) -> out.put(id, copy(pool)));
        return out;
    }

    static CorrectionRecord.Change copy(CorrectionRecord.Change source) {
        return copy(source, result -> result.flowSnapshot = list(result.flowSnapshot, SaveCopies::copy));
    }

    static UndoRecord copy(UndoRecord source) {
        return copy(source, result -> result.transaction = copy(result.transaction));
    }

    static SavedState.SavedSession copy(SavedState.SavedSession source) {
        return source == null ? null : new SavedState.SavedSession(source.id,
                copy(source.session), list(source.market, SaveCopies::copy));
    }

    static TrackedBasisState copy(TrackedBasisState source) {
        return copy(source, result -> result.pools = list(result.pools, SaveCopies::copy));
    }

    static BasisPool copy(BasisPool source) {
        return copy(source, result -> result.fenceOwners = result.fenceOwners == null ? null
                : new HashSet<>(result.fenceOwners));
    }

    static SavedState.PendingDeath copy(SavedState.PendingDeath source) {
        return copy(source, result -> {
            result.outstandingItems = list(result.outstandingItems, SaveCopies::copy);
            result.heldAtDeath = list(result.heldAtDeath, SaveCopies::copy);
        });
    }
}


@Singleton
class Engine {
    final RaidHistory raids = new RaidHistory(this); static final int MINIMUM_BASELINE_WARMUP_TICKS = 3;

    /** After a resume the sidebar/HUD show CALIBRATING this long; bookings stay immediate. */
    static final long CALIBRATING_MILLIS = 2500;

    static final String UNCLASSIFIED_LOCAL_DEATH_NOTE = msg("unclassifiedLocal"); FlowValuator valuation;
    TransactionClassifier classifier; GpManagerConfig config;
    LootPresentationFilterService eligibility; List<Session> history = new ArrayList<>();

    /** One home for pending physical evidence; Persistence supplies its owner token. */
    Inbox events = new Inbox();

    SessionArchive archive = new SessionArchive(this);

    /**
     * Profile-local date basis; persisted so travelling or changing the host zone cannot shift
     * history.
     */
    String zone = systemDefault().getId();

    /** Domain revision: every canonical mutation advances it; derived previews bind to it. */
    long revision;

    /** Derived presentation cache; never part of SavedState or an accounting authority. */
    transient MoneySnapshot snapshot;
    /** Pending dose Reviews only; at most four background attempts and one row per tick. */
    final PriorityQueue<DoseRetry> doseRetries = new PriorityQueue<>(comparingLong((DoseRetry value) -> value.at)
            .thenComparingLong(value -> value.row.timestamp));
    @RequiredArgsConstructor
    static class DoseRetry {
        final Session session; final Transaction row;
        long at; int attempts;
    }
    void queueDose(Session session, Transaction row) {
        if (delayedDose(row)) doseRetries.add(new DoseRetry(session, row));
    }
    void retryDose(long at) {
        var next = doseRetries.peek(); if (next == null || at < next.at) return; doseRetries.remove();
        if (sessionById(next.session.id()) != next.session
                || next.session.findTransaction(next.row.id()) != next.row
                || !delayedDose(next.row) || recoverDose(next.session, next.row, at)) return;
        if (++next.attempts < 4) {
            next.at = sum(at, next.attempts == 1 ? 10_000 : next.attempts == 2 ? 60_000 : 1_860_000); doseRetries.add(next);
        }
    }

    synchronized MoneySnapshot summary() {
        return MoneySnapshot.of(this);
    }

    /** Active RuneScape profile key supplied by the persistence coordinator. */
    String profile = "";

    // The general tracker is durable. A custom session is the only temporary
    // owner and takes the active pointer while General is explicitly paused.
    Session general, custom;

    /** A factory reset arms a fresh start a few seconds later, if nobody starts tracking first. */
    long startAt;

    Session current; ContainerSnapshot baseline;
    boolean warming;

    /** Epoch millis the post-resume calibration cue clears at; 0 when none is armed. */
    long warmUntil;

    ContainerSnapshot sample; int warmTicks, stable;
    boolean dirty;

    /** This opening's last bank counts (null until it is seen) and what moved since the last settle. */
    ContainerSnapshot bank; final Map<Integer, Long> banked = new HashMap<>();
    ContainerSnapshot pending; Observation cast;
    int stableTicks;

    /*
     * --- Per-source bank-transfer evidence lifetimes ---
     *
     * Bone burial (or any other consumption) that settles after the bank UI has closed must
     * classify as CONSUMPTION, not TRANSFER. Two evidence sources live in the Inbox, each with
     * its own lifetime, because they mean different things:
     *
     *   - SOFT evidence (Inbox SOFT_TRANSFER / BANK_UI, read by bankOpen()) only means "the bank
     *     widget happened to be visible". It is a weak, one-sided signal and bankClosed() drops it
     *     at once, so it can never outlive the UI and misclassify an unrelated burial/eat/drink/
     *     cast that settles after close.
     *   - HARD evidence (Inbox HARD_TRANSFER, read by hasHardTransfer()) means a real
     *     bank-container/menu deposit or withdrawal was confirmed. It survives bank close for a
     *     short, bounded TTL, so a same-tick race between the bank-container event and the
     *     inventory dirty callback still books the transfer correctly; settle or expiry clears it.
     *
     * A stale soft latch can never poison a burial after close; only live hard evidence can.
     * {@link Inbox} applies the same per-source lifetimes to consumption/drop intent.
     * `ConsumptionBurialEngineTest`, `BankBoundaryBurialTest` and
     * `PresentationFilterAccountingIsolationEngineTest#burialCallbackBeforeGameTickBankCloseMustNotLatchOldTransfer`
     * lock this in.
     */
    DeathReclaimLifecycle death = new DeathReclaimLifecycle();

    /** Session-owned measured balances; never persisted or shared across owners. */
    MeasuredChargeReadTracker checks = new MeasuredChargeReadTracker();

    ChargeEstimateJournal estimate = new ChargeEstimateJournal(); Map<Integer, Long> stored = numberMap();
    boolean zoneTransfer, capture; ContainerSnapshot storedBaseline;
    int captureTicks, restoreTicks;

    /** Active clue path for dig/tele/key cost pairing. */
    ClueCostPairing clues = new ClueCostPairing();

    /** Key/chest claims (the one durable evidence exception, section H) and their live evidence. */
    KeyClaims claims = new KeyClaims();

    @Inject
    Engine(Valuation valuationService, TransactionClassifier classifier, GpManagerConfig config) {
        this((FlowValuator) valuationService, classifier, config);
    }

    Engine(FlowValuator valuationService, TransactionClassifier classifier, GpManagerConfig config) {
        valuation = valuationService; this.classifier = classifier; this.config = config; events.reset = this::clearEphemera;
        exchange.basis = basis; basis.openReservationLookup = exchange::latestReservationTime;
    }

    @Inject
    void setContributionEligibility(LootPresentationFilterService eligibility) {
        this.eligibility = eligibility;
    }

    /**
     * Restore using a supplied clock so retention and recovery rules are deterministic in tests.
     */
    synchronized void restore(SavedState state, long now) {
        ground = null; doseRetries.clear(); history.clear(); archive.clear();
        plans.clear(); saved.clear();
        zone = state != null && validZone(state.profileTimeZoneId()) ? state.profileTimeZoneId()
                        : systemDefault().getId();
        changed(); claims.clear();
        // Known-basis coverage restores before any session can notify the observer; a pre-106
        // state starts with no known coverage (existing holdings are unknown, never zero).
        if (state != null)
            // Saved GE records are clamped once here, so their accessors read fields directly.
            for (var record : state.geCustody()) if (record != null) record.sanitize();

        basis.restore(state, now); general = null; custom = null; current = null;
        drop(DROP_CLICK);
        if (state != null) {
            if (state.savedSessions != null) for (var saved : state.savedSessions)
                if (saved != null && saved.id != null && saved.id.startsWith("saved:")
                        && saved.session != null && saved.session.isClosed() && saved.market != null) {
                    saved.session.sanitize(); this.saved.add(saved);
                }
            for (var plan : state.savedGrinds())
                if (plan != null && !plan.id().isEmpty() && !plan.name().isEmpty())
                    plans.add(plan);

            for (var session : state.history())
                if (session != null) history.add(session);

            general = state.generalSession; custom = state.customSession; if (general != null) general.grind = false;
            if (custom != null) custom.grind = true;
            for (var session : sessions()) {
                session.sanitize(); attachListeners(session); for (var row : session.transactions()) queueDose(session, row);
            }
            current = custom != null && !custom.isClosed() ? custom : general;
            if (current != null && !current.isClosed())
                if (!current.paused) {
                    long savedAt = state.savedAtEpochMillis; current.pause(savedAt > 0 ? min(now, savedAt) : now, RECOVERY);
                } else
                    current.pauseReason = RECOVERY;

        }
        // Reclaim evidence is ephemeral and belongs to the identity being replaced;
        // never carry one account's lost-item whitelist into another account's rows.
        death.reset();
        // The looting bag's dispenser additions belong to the account that earned them.
        bag.clear(); rememberBag(state == null ? null : state.lootingBag); reward.clear();
        // A restart of the same owner restores its persisted pending death/reclaim evidence.
        death.restore(state == null ? null : state.pendingDeathReclaim);
        // Custody lifecycles are profile-scoped financial continuity state.
        exchange.restore(state, now); resetTracking(false); raids.restore(state == null ? null : state.raidHistory);
        raids.recoverVisibility();
        if (state != null) {
            // Claims settled by a retained transaction are stale duplicates and must never book
            // again.
            claims.restore(state, sessions()); archive.compactAfterRestore(now);
        }
    }

    /**
     * Ongoing load-time repricing. The bounded worker owns missing-price Drink pairs; historical
     * spell, GE, GE audit-row and bond compatibility repairs have been retired. Owner
     * corrections stay unchanged, and an unchanged second pass does not advance the revision.
     * Historical grave-looking rows are never rewritten: item overlap cannot prove a recovery, so
     * ambiguous old income waits for the owner's explicit correction.
     */
    synchronized void recheck() {
        boolean changed = false; var drops = new ArrayList<Runnable>();
        for (var session : sessions()) {
            var decided = decisions(session);
            for (var row : session.transactions()) {
                if (row.correction() != AUTO || Contribution.isSplit(row)) continue;
                // 1.7.0 only, remove in the next release: a crystal tool Revert whose two dialogs outlasted
                // its click sat in Review (the tool left, its seed and dragon tool arrived). It gets the
                // Ledger's Remove and the one shard row a live Revert books. A row with any earlier
                // decision, including an undone repair, is left alone so the shards never book twice.
                var tool = revertReview(row);
                if (tool != null && decided != null && !decided.contains(row.id())) {
                    row.ownershipCorrection = AUTO; session.correct(List.of(row), IGNORE, "Crystal tool repair");
                    var shards = dismantleLoss(row.timestamp, "Dismantled " + tool.name, row.activityName(), DISMANTLE.get(tool.item));
                    drops.add(() -> session.addTransaction(shards, config.maxTransactionsPerSession(), false));
                }
                // The bounded worker owns dose repricing; the login scan cannot publish half a pair.
                if (delayedDose(row) || !any(row.flows(), flow -> flow.source() == UNPRICED)) continue;
                var previous = row.flows(); var next = list(previous, SaveCopies::copy);
                boolean priced = false;
                for (Flow flow : next) {
                    if (flow.source() != UNPRICED) continue;
                    // An item that had no price now has one: its alch value, worth nothing, or a share
                    // of the whole (half a pie, owner 1.5.1).
                    for (Flow now : valuation.value(Map.of(flow.item, flow.quantity)))
                        if (now.source() != UNPRICED) {
                            flow.unitPrice = now.unitPrice; flow.value = now.value; flow.priceSource = now.source(); priced = true;
                        }

                }
                if (priced && (row.timestamp < basis.epoch
                        || basis.canChange(row, AUTO, previous, AUTO, next))) {
                    // Publish price and ownership together; used/reserved stock cannot be recreated.
                    row.replaceFlows(next); if (row.timestamp >= basis.epoch) basis.onChanged(row, AUTO, previous);
                    session.notifyChanged(); changed = true;
                }
            }
        }

        drops.forEach(Runnable::run); if (changed || !drops.isEmpty()) changed();
    }

    static boolean delayedDose(Transaction row) {
        return needsReview(row) && row.action() == DRINK && !Contribution.isSplit(row)
                && msg("consumedDose").equals(row.explanation());
    }

    Set<String> decisions(Session session) {
        if (session.correctionHistory == null) return null; var decided = new HashSet<String>();
        for (var record : session.correctionHistory) {
            if (record == null || record.changes == null) return null;
            for (var change : record.changes()) {
                if (change == null) return null; decided.add(change.transactionId());
            }
        }
        return decided;
    }
    /** Publishes a verified dose pair atomically from the bounded pending queue. */
    boolean recoverDose(Session session, Transaction row, long at) {
        if (!delayedDose(row) || row.original() != UNCERTAIN || row.counted || row.context() != GENERIC
                || !row.note().isEmpty() || row.onGround() || !row.claimId().isEmpty()
                || !row.encounterId().isEmpty() || row.flows().size() != 2) return false;
        var decided = decisions(session); if (decided == null || decided.contains(row.id())) return false;
        var draft = copy(row);
        boolean captured = Valuation.capturedDose(row.flows()),
            known = false;
        try {
            for (int i = 0; i < draft.flows.size(); i++) {
                var flow = draft.flows.get(i); if (!blank(flow.raid)) return false;
                if (flow.source() == UNPRICED) {
                    var quote = valuation.value(Map.of(flow.item, flow.quantity), at);
                    if (quote.size() != 1 || quote.get(0).item != flow.item
                            || quote.get(0).quantity != flow.quantity) return false;
                    draft.flows.set(i, quote.get(0)); flow = draft.flows.get(i);
                } else known = true;
                if (flow.unitPrice <= 0
                        || flow.value != multiplyExact(flow.quantity, flow.unitPrice)) return false;
            }
            if (!captured) {
                var normalized = valuation.normalizeConsumedFlows(draft.flows, DRINK, at);
                if (normalized == null || normalized == draft.flows || normalized.size() != 2) return false;
                long raw = draft.flows.stream().mapToLong(flow -> flow.value).sum(),
                    cost = normalized.stream().mapToLong(flow -> flow.value).sum();
                // Known quotes are immutable. A later authority can verify the same monetary step.
                if (known && raw != cost || cost > 0) return false; draft.replaceFlows(known ? draft.flows : normalized);
            }
        } catch (ArithmeticException ex) { return false; }
        draft.type = CONSUMPTION; draft.counted = true;
        boolean physical = basis.canIntroduceSink(emptyMap(), basis.sinks(draft), row.timestamp, null)
                && basis.canApplyAll(basis.contribution(draft), 1, row.timestamp);
        if (!physical && !captured) return false; row.replaceFlows(draft.flows()); row.type = CONSUMPTION;
        row.counted = true; row.confidence = confidenceFor(row.context(), CONSUMPTION);
        row.explanation = explain(row.context(), CONSUMPTION, true); if (physical) basis.onAdded(row, false);
        else row.note = msg("capturedDose"); session.notifyChanged();
        return true;
    }

    synchronized void ensureSession(long now) {
        if (general == null || general.isClosed())
            // Overall is the durable default owner display name; it must still
            // accept automatic activity detection just as the former General default
            // session did.
            general = newSession(Session.DURABLE_OWNER_NAME, now, false);
        if (custom == null || custom.isClosed()) {
            custom = null; current = general;
        }
    }

    /** Rate and displayed clock only: retain all accounting, targets and pending action evidence. */
    synchronized void restartRate(long now, boolean savePrevious) {
        if (current == null) return; if (savePrevious) current.savedRateMillis = current.liveElapsed(now);
        current.restoredRateMillis = null; current.rateStartAt = now;
        current.rateSetMillis = current.elapsed(now); warmUntil = now + CALIBRATING_MILLIS; changed();
    }

    /** Resume a frozen clock, falling back to the original run; bookings stay unchanged. */
    synchronized void restoreRate(long now) {
        if (current == null) return; var saved = current.savedRateMillis;
        long elapsed = current.elapsed(now); if (saved != null && (saved < 0 || saved > elapsed)) return;
        current.rateStartAt = saved == null ? current.startedAt : now;
        current.rateSetMillis = saved == null ? 0 : elapsed; current.restoredRateMillis = saved; changed();
    }

    /**
     * New session (owner 1.5.2): Live's Net, Recent, GP/h and clock start at zero and a set target
     * counts again with it, while the run keeps every booking and its whole total.
     */
    synchronized void newLive(long now) {
        if (current == null) return; var run = current;
        long elapsed = run.elapsed(now); run.rebaseTarget(metrics(now).net, elapsed);
        run.rearmTimeTarget(now); run.sessionStartAt = now;
        run.sessionStartMillis = elapsed; run.rateStartAt = run.restoredRateMillis = null; changed();
    }

    /**
     * Back to the whole run on Live, from the run total under the Net (owner 1.5.2): the session
     * ends, nothing else changes. A running target is its own session, so it stays.
     */
    synchronized void showWholeRun() {
        if (current == null) return; current.rateStartAt = current.startedAt; current.rateSetMillis = 0;
        current.restoredRateMillis = null;
        if (current.targetGp() == null && current.timeTargetMillis() == null)
            current.sessionStartAt = null;
        changed();
    }

    /**
     * Reset free-play (owner 1.5.2): the run so far moves to Grinds history, nothing deleted,
     * and a new Free play starts at zero with the same milestone. Not during a Grind.
     */
    synchronized boolean startFreePlayOver(long now) {
        if (custom != null || general == null) return false;
        becomeFreePlay(newSession(Session.DURABLE_OWNER_NAME, now, false), now); return true;
    }

    /**
     * Restore (owner 1.5.2): a past Free play run becomes Free play again and the current one takes
     * its place in history, so the two can swap back; nothing is deleted. Not during a Grind.
     */
    synchronized boolean restoreFreePlay(String id, long now) {
        var old = historySession(id); if (custom != null || general == null || old == null || old.grind) return false;
        history.remove(old); old.reopen(now); old.rename(Session.DURABLE_OWNER_NAME); becomeFreePlay(old, now);
        return true;
    }

    /**
     * The current Free play moves to history as "Free play" (an empty one is dropped) and {@code
     * next} takes over, keeping the milestone and a manual pause.
     */
    void becomeFreePlay(Session next, long now) {
        var done = general; clearCollect(); done.rename("Free play"); done.close(max(done.startedAt, now));
        if (!done.transactions().isEmpty()) history.add(0, done); general = current = next; next.milestoneGp = done.milestoneGp;
        if (done.pauseReason() == MANUAL) next.pause(now, MANUAL); resetTracking(true); changed();
    }

    /** Free play's Net milestone (owner 1.5.2): it counts Free play's whole Net; null clears it. */
    synchronized boolean setMilestone(Long gp) {
        if (general == null || customActive()) return false; general.milestoneGp = positiveOrNull(gp); targetUntil = 0;
        changed(); return true;
    }

    /** Presentation countdown only; completion clears the saved milestone, never run targets. */
    long targetUntil; Session targetOwner;

    synchronized void tickMilestone(long now) {
        if (targetOwner != general) {
            targetOwner = general; targetUntil = 0;
        }
        if (general == null || customActive() || general.milestoneGp == null
                || general.milestoneGp <= 0) return;
        if (targetUntil > 0) {
            if (now >= targetUntil) setMilestone(null);
        } else if (general.metrics(now).net >= general.milestoneGp)
            targetUntil = sum(now, 8000);
    }

    synchronized void startCustom(String name, long now) {
        ensureSession(now);
        if (custom != null)
            // This transition needs an explicit Finish custom first. Starting
            // another custom session must never silently archive the current one.
            return;
        clearCollect(); if (!general.paused) general.pause(now, CUSTOM_SESSION);
        custom = newSession(name, now, true); current = custom; resetTracking(true);
    }

    synchronized boolean finishCustom(long now) {
        if (custom == null) return false; long closeAt = max(custom.startedAt, now); clearCollect();
        custom.close(closeAt); history.add(0, custom); custom = null; current = general;
        if (general != null && general.pauseReason() == CUSTOM_SESSION)
            general.resume(closeAt);
        resetTracking(true); return true;
    }

    synchronized boolean customActive() {
        return custom != null && current == custom && !custom.isClosed();
    }

    synchronized void pauseForLifecycle(long now) {
        resetTracking(false); if (live()) current.pause(now, LIFECYCLE);
    }

    /**
     * True on a separate-economy world (Leagues, Deadman, beta, tournament, speedrunning, no-save;
     * Hardcore Worlds track with their own prices, owner 2026-11-07):
     * tracking pauses there and neither starts nor resumes until it is left (owner 1.5.0).
     */
    boolean separate;

    /** Holds the world pause; any existing pause, Manual included, is left as it is. */
    synchronized void pauseForWorld(long now) {
        separate = true;
        if (live()) {
            resetTracking(false); current.pause(now, WORLD);
        }
    }

    /** Resumes an automatic pause of one of these reasons; Manual Pause always needs the owner. */
    synchronized boolean resume(long now, PauseReason... reasons) {
        if (current != null && current.paused && !separate
                && asList(reasons).contains(current.pauseReason())) {
            current.resume(now); arm(now); return true;
        }
        return false;
    }

    /**
     * Create the durable General tracker from meaningful gameplay when automatic startup is
     * enabled, on a new install and after a factory reset alike. Never resumes Manual Pause; Idle
     * and Recovery resume via {@link #resume}. Not from login alone, unless the plugin's
     * Start-tracking-on-login option asks for it at the first login (owner live 2026-10-13).
     *
     * @return true when a new General session was created
     */
    synchronized boolean autoStart(long now) {
        if (!config.autoStartSession() || separate || custom != null && !custom.isClosed()) return false;
        if (general != null && !general.isClosed()) return false; ensureSession(now); arm(now);
        return true;
    }

    /** Fresh tracking: clear the evidence and calibrate the rate from now. */
    void arm(long now) {
        resetTracking(true); warmUntil = now + CALIBRATING_MILLIS;
    }

    /** AFK pause: only a running session pauses, from now, so the timeout itself still counts. */
    synchronized void pauseIdle(long now) {
        if (live()) {
            resetTracking(false); current.pause(now, IDLE);
        }
    }

    synchronized boolean isIdlePaused() {
        return current != null && current.pauseReason() == IDLE;
    }

    synchronized void setDetectedActivity(String activity, long now) {
        if (current != null && current.mode() == SessionMode.AUTO)
            current.setActivityHint(activity);
    }

    synchronized void togglePause(long now) {
        if (current == null) {
            // Start tracking: a new install, or after a factory reset, with Automatic tracking off.
            ensureSession(now); arm(now);
        } else if (current.paused) {
            current.resume(now); arm(now);
        } else {
            resetTracking(false); current.pause(now, MANUAL); startAt = 0;
        }
    }

    /** A factory reset resumes tracking by itself a few seconds later, unless stopped first. */
    synchronized void tickAutoStart(long now) {
        if (startAt <= 0 || now < startAt) return; startAt = 0;
        if (current == null) {
            ensureSession(now); resetTracking(true);
        }
    }

    /** Seconds until the pending factory-reset resume fires; 0 when none is armed. */
    synchronized long autoResumeSeconds(long now) {
        long left = startAt - now; return left <= 0 ? 0 : (left + 999) / 1000;
    }

    /** True while the post-resume calibration cue is up; bookings are immediate and never wait. */
    synchronized boolean calibrating(long now) {
        return now < warmUntil;
    }


    /** Configured UTC retention maintenance used by the game-tick lifecycle. */
    synchronized int maintainRetention(long now) {
        return archive.maintainRetention(now);
    }

    List<Session> sessions() {
        var unique = new LinkedHashMap<String, Session>(); for (var session : history) addUnique(unique, session);
        addUnique(unique, general); addUnique(unique, custom); addUnique(unique, current);
        return copiedList(unique.values());
    }

    static void addUnique(Map<String, Session> unique, Session session) {
        if (session != null) unique.putIfAbsent(session.id(), session);
    }

    /**
     * Starts a login/session warm-up. Snapshots observed during this period are baseline material
     * only and can never become profit.
     */
    synchronized void beginPriming() {
        resetTracking(true);
    }

    /**
     * Sets an immediate trusted baseline. Primarily useful for deterministic tests and callers
     * which already know all tracked containers are loaded.
     */
    synchronized void setBaseline(ContainerSnapshot snapshot) {
        baseline = snapshot; warming = false; clearPriming();
    }

    synchronized void markDirty() {
        if (warming) return; dirty = true; stableTicks = 0;
        // Animation lag: refresh consume/drop TTLs when the inventory finally moves so
        // bury/drink intents survive until stabilization rather than expiring idle.
        events.refresh(CONSUME, 4); events.refresh(SPENT_CONSUME, 4);
        events.refresh(DROP_CLICK, max(4, config.stabilizationTicks() + 2)); captureContext();
        if (bankOpen() && (dirty || pending != null)) {
            pending().transfer = true;
            if (pending().context == GENERIC) {
                pending().context = TRANSFER; pending().note = "Bank transfer";
            }
        }
    }

    /** Owner-scoped live and stabilizing claims; HF and BO are independent sources. */
    Clue context() {
        return events.clue(LIVE_CLAIM);
    }

    Clue pending() {
        return events.clue(PENDING_CLAIM);
    }

    int clockLeft() {
        return events.left(CLOCK);
    }

    boolean hasHardTransfer() {
        return peek(HARD_TRANSFER) != null;
    }

    boolean bankOpen() {
        return peek(BANK_UI) != null;
    }

    /** The still-open consume command, from the sole pending-evidence store. */
    Observation intent() {
        return peek(CONSUME);
    }

    /**
     * Records a short-lived consume hint; accounting still waits for a stable inventory delta.
     * {@code itemId} may be {@code < 0} (open intent) when the menu did not expose an id — matching
     * then accepts a dose/partial leftover, a pure cost, or any inventory cost while intent is
     * armed so drink/bury coalesced with harvest gains still books Used/lost.
     *
     * @param destroy when true, irreversible Destroy menu — presentation stamps Destroyed; never
     *     registers recoverable own-drop.
     */
    /**
     * @param actionKind menu verb behind the click (Drink/Eat/Bury/…) or null when the option does
     *     not evidence a specific action. Presentation only.
     */
    /** Exact spell text is an optional presentation hint; it is not part of action matching. */
    synchronized void noteConsume(int id, int ticks, boolean destroy, ActionKind action,
            ActionLabel label) {
        // Eat/Bury/Drink/Cast/etc. — clear any pending Drop intent for the same click window.
        drop(DROP_CLICK); int safeTicks = max(1, ticks);
        var prior = intent();
        if (prior != null && id < 0 && prior.item >= 0) {
            // Chat/menu reinforcement without an id must not wipe a resolved item id,
            // flip a Destroy stamp to Used, or replace the resolved item's proven verb.
            // Exact spell text may refresh the label; a click that proves no name must not
            // erase the name already proven for this bounded interaction.
            events.refresh(CONSUME, safeTicks); if (label != null) prior.label = label;
            return;
        }
        if (prior != null && id >= 0 && prior.item >= 0 && prior.item != id && pending != null
                && baseline != null && pending.quantity(prior.item) < baseline.quantity(prior.item)) {
            // The earlier click's item already left the pack and its change has not settled: it
            // keeps its own evidence while the new click arms (owner live 2026-10-16: an Eat two
            // ticks after a Break left the tablet in Review).
            drop(SPENT_CONSUME); prior.kind = SPENT_CONSUME;
        }
        var e = events.put(CONSUME, id, 1, safeTicks - 1, null);
        // Prefer the pending (dirty) snapshot so a mid-window pickup is visible before the settle.
        var live = pending != null ? pending : baseline; long held = id < 0 || live == null ? -1 : live.quantity(id);
        // Keep the window's high-water: a later click re-arms at the current count, but a bite
        // that landed between the clicks must stay measurable against the stack's top (owner
        // 2026-10-12: withdraw-all then an eat while the clicks kept coming).
        if (prior != null && prior.item == id && prior.held > held) held = prior.held; e.held = held; e.flag = destroy;
        e.action = action; e.label = label;
    }

    /**
     * Clears only pending, unbooked action evidence at the profile/owner fence. Booked schema-105
     * action labels are durable metadata on canonical transactions and are never erased here;
     * lifecycle paths also reset pending consume evidence through {@link #resetTracking(boolean)}.
     */
    synchronized void clearActionEvidence() {
        drop(CONSUME, SPENT_CONSUME, CAST_SPELL, MANUAL_SPELL, PREPARATION, BOND_PAYMENT); cast = null;
    }

    /** Execution-time spell evidence survives unrelated clicks, but never supplies money. */
    synchronized void noteCast(ActionLabel autocast) {
        var manual = peek(MANUAL_SPELL); var spell = manual == null ? autocast : manual.label; drop(MANUAL_SPELL);
        events.put(CAST_SPELL, -1, 1, 2, null).label = spell;
    }

    /**
     * Chat confirmation ("You eat the chicken.") carries the verb the line proves: a chat that
     * arrives after the menu clicks lapse still names Ate/Drank instead of a bare "Used" (owner
     * 2026-10-12). A verb the menu already proved is never replaced.
     */
    synchronized void extendConsume(int ticks, ActionKind action) {
        int safeTicks = max(1, ticks);
        if (intent() != null) {
            events.refresh(CONSUME, safeTicks);
            if (action != null && intent().action == null)
                intent().action = action;
            return;
        }
        noteConsume(-1, safeTicks, false, action, null);
    }

    /**
     * Player-initiated Drop action: the confirmed inventory removal books as Dropped. Each item
     * keeps its own click, so two items dropped a tick apart settle together as one drop.
     */
    synchronized void noteDrop(int id, int ticks) {
        if (id < 0) return;
        drop(CONSUME);
        events.drop(DROP_CLICK, e -> e.item != id);
        events.add(DROP_CLICK, id, 1, max(1, ticks) - 1, null);
    }

    boolean dropped(int id) {
        return events.peek(DROP_CLICK, e -> e.item == id) != null;
    }

    /**
     * Set-up or Pick-up of a dwarf multicannon: its parts are yours wherever they stand (Nulodion
     * replaces a lost one free), so moving them is neutral (owner 1.3.3). The base-to-furnace
     * assembly runs for up to ~12 ticks after the click and its auto-load follows right behind,
     * so the custody must outlive them both (owner live 2026-10-13: 15 ticks expired right at the
     * furnace's own settle and booked it as a loss).
     */
    synchronized void moveCannon() {
        events.put(CANNON, -1, 1, 40, null);
    }

    /** Fire or Load: the balls it takes (granite first) are remembered until it is picked up. */
    synchronized void loadCannon(int ticks) {
        if (baseline != null)
            noteDrop(baseline.quantity(GRANITE_CANNONBALL) > 0 ? GRANITE_CANNONBALL
                            : MCANNONBALL, ticks);
    }

    /**
     * Sailing's additional ball tiers: bronze, iron, mithril, adamant, rune and dragon. The plain
     * steel tier is the classic id-2 ball and granite also serves ship cannons, so both already
     * sit in {@link #cannonball}; the exact ids here add the chainshot and incendiary types, so a
     * ship-cannon volley reads the same as a dwarf load (owner 1.3.4 ship sweep).
     */
    private static final Set<Integer> SAIL_CANNONBALLS = Set.copyOf(byId("sail_cannonballs").keySet());

    static boolean cannonball(int id) {
        return id == MCANNONBALL || id == GRANITE_CANNONBALL || SAIL_CANNONBALLS.contains(id);
    }

    /** The four dwarf multicannon parts, unnoted ids as placed (TWPART1..4). */
    static boolean cannonPart(int id) {
        return id == TWPART1 || id == TWPART2 || id == TWPART3
                || id == TWPART4;
    }

    /** Balls already inside a deployed cannon before this window; their return is neutral. */
    int cannonSeed;

    /** Seeds deployed parts/ammo from the native counters on a cannon interaction. */
    synchronized void syncCannon(int parts, int balls, boolean decayed) {
        if (decayed) {
            cannonSeed = 0; events.drop(DROPPED, e -> !cannonball(e.item)); return;
        }
        if (parts != 4) return;
        long loaded = events.all(DROPPED).stream().filter(e -> cannonball(e.item)).mapToLong(e -> e.qty).sum();
        cannonSeed = max(cannonSeed, balls - (int) loaded);
    }

    /** Money evidence is owner-checked and consumed under the booking monitor. */
    synchronized long bagged(Observation e, long qty) {
        if (e.owner != events.owner) return 0; long moved = events.take(e.kind, e.item, qty, v -> v == e);
        if (e.flag && moved > 0) {
            bag.merge(e.item, moved, SafeMath::sum); changed();
        }
        bagged(e.item, moved); return moved;
    }

    /** A measured Bank-all decrease is received directly; bank callbacks cannot hide it. */
    synchronized Transaction banked(Observation e, long qty, long now) {
        if (!live() || e.owner != events.owner || e.kind != CHEST || e.item < 0) return null;
        long received = events.take(e.kind, e.item, qty, v -> v == e); return received <= 0 ? null : add(new Transaction(now, TransactionType.LOOT, LOOT, msg("bankedReward"),
                        current.activityHint(), true, valuation.value(singletonMap(e.item, received)),
                        CONFIRMED, msg("bankedProof"), null), false);
    }

    /**
     * Books what really left the clicked Bank-all chest, once, for the owner whose callback sampled
     * it, and replaces the chest's proof with the new counts. The inbox is checked, spent and
     * re-armed in one hold under this monitor, so no owner fence lands inside it.
     */
    synchronized List<Transaction> chest(int id, Map<Integer, Long> counts, int tick, long owner, long now) {
        counts = cash(counts); var rows = new ArrayList<Transaction>();
        synchronized (events) {
            var e = events.peek(CHEST, v -> v.item == -1 && v.x == id);
            if (e == null || owner != events.owner || tick - e.at > 10) return rows;
            for (var event : events.all(CHEST)) {
                long gone = event.qty - amount(counts, event.item); if (event.item >= 0 && gone > 0) rows.add(banked(event, gone, now));
            }
            events.open.removeIf(v -> v.kind == CHEST && v.item >= 0);
            counts.forEach((item, qty) -> events.add(CHEST, item, qty, e.until - events.now, null));
        }
        rows.removeIf(Objects::isNull); return rows;
    }

    synchronized void fee(Observation e, long now) {
        if (e.owner == events.owner)
            bookDeathFee(events.take(Inbox.FEE, -1, e.qty, v -> v == e), e.note, now);
    }

    /**
     * A pickup that went straight into an open bag reads as an inventory gain (owner 1.2): the
     * baseline forgets it, so the next settle books it like any pickup.
     */
    /** A positive quantity arrived outside the pack (a gain); a negative one reached the pack already counted. */
    synchronized void bagged(int id, long quantity) {
        if (baseline == null || warming || quantity == 0) return;
        if (id == PLATINUM) { id = COINS; quantity = multiply(quantity, 1000); }
        var held = new HashMap<>(baseline.items); held.merge(id, -quantity, Long::sum);
        baseline = new ContainerSnapshot(held); markDirty();
    }

    /** The catch line proves new fish outside the pack; a simultaneous deposit owns no part of it. */
    synchronized Transaction trawled(int id, long quantity, long now) {
        return !live() || warming || baseline == null || quantity <= 0 ? null : add(new Transaction(now, GAIN, GENERIC, msg("trawled"), current.activityHint(), true,
                        valuation.value(singletonMap(id, quantity), now), CONFIRMED, msg("trawledProof"), null), false);
    }

    /** Clears the export identity when persistence invalidates the active account. */
    synchronized void clearIdentity() {
        profile = "";
    }

    /** Binds the identity and restores its state under the same engine lock. */
    synchronized void restoreProfile(String identity, SavedState state, long now) {
        String next = fallback(identity, ""); Gson codec = JsonCodec.gson();
        var detached = state == null ? null : codec.fromJson(codec.toJson(state), SavedState.class);
        String stateOwner = detached == null ? null : detached.ownerKey;
        if (!next.isEmpty() && !blank(stateOwner) && !next.equals(stateOwner))
            throw new IllegalArgumentException(msg("savedstateOwner"));
        var draft = prepareProfile(next, detached, now); installProfile(draft, next);
    }

    /** The bound profile's state as saved, for a backup copy of the save file. */
    synchronized SavedState exportState() {
        var snapshot = savedState(); snapshot.setOwnerKey(profile); return snapshot;
    }

    Engine prepareProfile(String identity, SavedState state, long now) {
        // An isolated engine sharing this one's valuation, classifier and config.
        var draft = new Engine(valuation, classifier, config);
        draft.setContributionEligibility(eligibility); draft.profile = identity; draft.restore(state, now);
        return draft;
    }

    void installProfile(Engine draft, String identity) {
        // Allocate copies and attach listeners before changing live fields. The
        // normal restore/migration path has already completed on the isolated engine.
        var nextHistory = copiedList(draft.history); var nextSessions = copiedList(draft.sessions());
        for (var session : nextSessions) attachListeners(session);
        // Clear identity-scoped transient evidence while durable profile fields
        // still belong to the previous owner. No reader can observe the swap
        // because agl/restoreProfile hold this engine's monitor.
        drop(DROP_CLICK); death.reset();
        // The looting bag's dispenser additions belong to the account that earned them.
        bag.clear(); contents = new HashMap<>(draft.contents); reward.clear(); clearCollect();
        resetTracking(false); coffer = -1; history = nextHistory; doseRetries.clear();
        doseRetries.addAll(draft.doseRetries); plans.clear(); plans.addAll(draft.plans); saved.clear();
        saved.addAll(draft.saved); archive.adoptFrom(draft.archive); zone = draft.zone; changed();
        claims.adoptFrom(draft.claims); death.adoptFrom(draft.death);
        exchange.adoptFrom(draft.exchange); basis.adoptFrom(draft.basis); ground = null; general = draft.general;
        custom = draft.custom; current = draft.current; profile = string(identity); raids.restore(draft.raids.snapshot());
    }

    synchronized void markContext(Context next, int ticks, String note) {
        if (next != LOOT && next != PK_LOOT) claims.cancelChest();
        if (next == TRANSFER || next == MARKET) drop(LOAD_TARGET); replaceDeathMarker(next, note);
        if (next == TRANSFER) {
            // Stale Eat/Bury intents must not reclassify bank deposits as Used/lost. A Drop click
            // survives while the bank's own counts are known: it then owns only what the bank
            // did not receive. Elsewhere it survives only for an item already gone before the
            // store was touched (owner recording 2026-10-23: a sapling dropped as the Tool
            // Leprechaun opened).
            drop(CONSUME, SPENT_CONSUME);
            if (bank == null) events.drop(DROP_CLICK, e -> pending != null && baseline != null
                            && pending.quantity(e.item) < baseline.quantity(e.item));
            noteHardTransfer(max(1, ticks));
        }
        setContext(next, ticks, note, emptyMap(), null);
    }

    /**
     * Bank interface is open on this tick. Soft UI-open refresh only — does not by itself prove a
     * deposit/withdraw movement.
     */
    synchronized void bankOpened(int ticks) {
        claims.cancelChest(); drop(LOAD_TARGET); replaceDeathMarker(TRANSFER, "Bank transfer");
        // Edge-trigger only. Live GameTick refreshes this every tick while the bank
        // widget/container is open — wiping Eat/Drink/Bury intents each tick made
        // potato-field sessions book every inventory delta as soft TRANSFER (Used/lost
        // ×0, no harvest rows) whenever bank detection stuck or fluttered.
        boolean newlyOpened = !bankOpen(); events.ttl(BANK_UI, Integer.MAX_VALUE);
        if (newlyOpened) drop(CONSUME, SPENT_CONSUME);
        events.ttl(SOFT_TRANSFER, max(events.left(SOFT_TRANSFER), max(1, ticks)));
        setContext(TRANSFER, ticks, "Bank transfer", emptyMap(), null);
    }

    /**
     * The bank's own counts. An opening's first sight is a baseline; each later change is exactly
     * what was deposited or withdrawn.
     */
    synchronized void bankContents(Map<Integer, Long> counts, long now, Consumer<Transaction> additional) {
        if (live() && bank == null && pending != null && !pending().hasTransfer() && pending().context != TRANSFER) {
            var prior = settle(pending, now, additional); clearContext(); additional.accept(prior);
        }
        var before = bank; bank = new ContainerSnapshot(cash(counts));
        if (before != null)
            bank.diff(before).forEach((id, moved) -> banked.merge(id, moved, Long::sum));
        // The plugin's six-tick correlation window.
        markContext(TRANSFER, 6, "Bank transfer");
    }

    /**
     * Bank interface closed this tick. Clears sticky soft TRANSFER hints so burial after close is
     * not classified as TRANSFER when the inventory callback raced ahead of this tick. Genuine
     * deposits keep pending TRANSFER only when hard bank-container/menu evidence was attached to
     * this pending change — or when hard evidence is still live and the inventory callback arrives
     * after close.
     */
    synchronized void bankClosed() {
        bank = null; drop(BANK_UI);
        // Soft UI-open refresh only. Hard deposit/withdraw TTL must survive close so a
        // GameTick between bank-container evidence and inventory dirty stays TRANSFER.
        if (!hasHardTransfer()) drop(SOFT_TRANSFER);
        if (dirty || pending != null) {
            if (!pending().hard && !hasHardTransfer()) {
                // Soft UI-open latch only — clear so burial after close is CONSUMPTION.
                // Genuine deposits attach hard evidence via bank-container/menu markContext.
                pending().transfer = false; if (pending().context == TRANSFER) pending().clear();
            } else if (hasHardTransfer() && !pending().hard)
                // Inventory already dirty from soft open; promote with surviving hard evidence.
                latchHardTransfer();
            return;
        }
        if (context().context == TRANSFER && !deathNote(context().note)) {
            // Drop the soft UI-open note/ticks; live hard evidence keeps TRANSFER for a late
            // inventory callback.
            context().clear(); context().context = hasHardTransfer() ? TRANSFER : GENERIC; drop(CLOCK);
        }
    }

    void noteHardTransfer(int ticks) {
        drop(LOAD_TARGET); int safeTicks = max(1, ticks);
        events.ttl(SOFT_TRANSFER, max(events.left(SOFT_TRANSFER), safeTicks));
        events.ttl(HARD_TRANSFER, max(events.left(HARD_TRANSFER), safeTicks));
        if (dirty || pending != null) latchHardTransfer();
    }

    void latchHardTransfer() {
        pending().transfer = true; pending().hard = true;
        if (pending().context == GENERIC || TRANSFER.priority > pending().context.priority) {
            // Keep the producer's own note (minigame wipe, neutral zone, death) — a
            // generic "Bank transfer" would otherwise win the priority race and mislabel
            // every non-bank transfer row.
            String note = context().context == TRANSFER && !blank(context().note) ? context().note
                            : "Bank transfer";
            applyContext(TRANSFER, note, emptyMap(), null);
        } else if (pending().context == TRANSFER) {
            pending().transfer = true; if (empty(pending().note)) pending().note = "Bank transfer";
        }
    }

    /**
     * Soft evidence: inventory became dirty while the bank UI was open. Survives bank close for
     * genuine deposits. Cleared by {@link #bankClosed()} when the pending change was only a stale
     * latch after the UI was already gone (plugin closes before dirty when {@code !isBankOpen()}).
     */
    synchronized void markLoot(Map<Integer, Long> expectedLoot, int ticks, String note, String activity) {
        var expected = positive(expectedLoot);
        if (!expected.isEmpty())
            loot(expected, ticks, note, activity, LOOT, null);
        setContext(LOOT, ticks, note, expected, null);
    }

    /**
     * Each private drop proves its own remaining quantity; attribution has the old shorter window.
     */
    void loot(Map<Integer, Long> counts, int ticks, String note, String name, Context context, String id) {
        counts.forEach((item, qty) -> {
                    var e = events.add(PRIVATE_LOOT, item, qty, 99, string(note));
                    e.at = events.now + max(1, ticks) - 1; e.name = fallback(name, GENERAL); e.context = context; e.id = id;
                });
    }

    void setContext(Context next, int ticks, String note, Map<Integer, Long> loot, String encounter) {
        var context = next == null ? GENERIC : next; var expected = positive(loot); replaceDeathMarker(context, note);
        boolean replace = clockLeft() <= 0 || context.priority > context().context.priority;
        if (replace || context == context().context)
            events.ttl(CLOCK, max(replace ? 0 : clockLeft(), max(1, ticks)));
        context().offer(context, note, expected, encounter, replace);
        if (dirty || pending != null)
            applyContext(context, note, expected, encounter);
    }

    /**
     * A death marker is a short-lived reconciliation hint, not a durable activity context. An
     * explicit later activity must own the next measured change even though TRANSFER has a higher
     * general context priority.
     */
    void replaceDeathMarker(Context context, String note) {
        boolean newDeathMarker = context == TRANSFER && deathNote(note),
            activeMarker = context().context == TRANSFER && deathNote(context().note),
            pendingMarker = pending().context == TRANSFER && deathNote(pending().note);
        if (!newDeathMarker && context != GENERIC && (activeMarker || pendingMarker)) {
            if (activeMarker) {
                context().clear(); drop(CLOCK);
            }
            if (pendingMarker) pending().clear(); drop(SOFT_TRANSFER); clearTransfer();
            death.cancelWipe();
        }
    }

    synchronized void markPkLoot(Map<Integer, Long> expectedLoot, int ticks, long now) {
        if (current == null && !autoStart(now) || !live()) return; var expected = positive(expectedLoot);
        String fight = current.addEncounter(true, now);
        if (current.mode() != SessionMode.GENERAL)
            current.attachRecentCosts(fight, now, 90000);
        String note = "PK loot";
        if (!expected.isEmpty())
            loot(expected, ticks, note, "PKing", PK_LOOT, fight);
        setContext(PK_LOOT, ticks, note, expected, fight);
    }

    /**
     * Local unsafe PvM death with a separate canonical inventory/equipment ownership snapshot. The
     * snapshot is reconciled against measured negative flows; presentation evidence remains
     * explanation-only.
     */
    synchronized void markPvmDeath(int ticks, Map<Integer, Long> heldItems, Map<Integer, Long> graveBag, long owner) {
        if (owner != events.owner) return; raids.death();
        int kind = RaidHistory.kind(raids.state.death); if (kind == 0 || kind == 2) return;
        armDeathReclaim(ticks, heldItems, graveBag);
    }

    void armDeathReclaim(int ticks, Map<Integer, Long> heldItems, Map<Integer, Long> graveBag) {
        markContext(TRANSFER, max(1, ticks), msg("deathItems"));
        // Start after the transfer marker so this new death cannot cancel its own
        // bounded whitelist when strong-context supersession is evaluated.
        death.onPvmDeath(heldItems, valuation.hardcore());
        // A positively identified Wilderness PvM death sends known bag stock to the grave.
        // It has no INV delta; retaining ownership here prevents a false gain on recovery. A
        // Hardcore World deletes that grave, so there the known stock is the death's loss.
        var bag = cash(graveBag);
        if (!death.deleted) bag.forEach((id, quantity) -> death.items.merge(id, quantity, SafeMath::sum));
        else if (live() && !bag.isEmpty()) {
            bag.replaceAll((id, quantity) -> -quantity); deathLoss(now(), valuation.value(bag), msg("hardcoreDeleted"));
        }
        if (!death.items.isEmpty()) death.awaitingReclaim = true;
    }

    /**
     * Local death with no confident PvM / PK classification. Drop stale transfer and action
     * evidence so its inventory wipe is booked from ordinary loss evidence.
     */
    synchronized void markUnknownDeath() {
        raids.state.death = null; claims.cancelChest(); death.reset(); drop(CONSUME, SPENT_CONSUME, DROP_CLICK);
        clearTransfer(); drop(SOFT_TRANSFER);
        drop(BANK_UI); events.ttl(CLOCK, max(6, config.stabilizationTicks() + 4));
        context().clear(); context().note = UNCLASSIFIED_LOCAL_DEATH_NOTE;
        if (dirty || pending != null) {
            pending().clear(); pending().note = UNCLASSIFIED_LOCAL_DEATH_NOTE;
        }
    }

    /**
     * Player interacted with a retrieval service (gravestone, Death, boss NPC or chest).
     *
     * @return true when the reclaim window was armed; ambiguous targets (a bare "Chest", a
     *     gravestone) only arm while a local PvM death is awaiting reclaim
     */
    synchronized boolean noteReclaimIntent(Service service, int ticks) {
        return death.noteReclaimIntent(service, ticks);
    }

    /** Current presentation read model for the local death-reclaim indicator. */

    static Transaction first(Transaction... options) {
        return firstMatch(asList(options), Objects::nonNull);
    }

    /**
     * Remove measured death-owned returns before minimum-value and ordinary gain classification.
     * Source-backed loot quantities are protected first; unmatched gains and all non-coin costs
     * stay in the ordinary settle. A fee is booked only from an observed coin loss with a live
     * service interaction or a matched return in the same settle.
     */
    Transaction settleReclaim(List<Flow> flows, long now, Map<Integer, Long> drops,
            boolean allowObservedFee) {
        if (empty(flows) || !death.isAwaitingReclaim() && !death.armed())
            return null;
        var service = death.service; var sourceBacked = positive(drops);
        // Source-backed loot quantities are protected first.
        var returned = claim(flows, Flow::gain, (flow, quantity) -> death.matchReturn(flow.item,
                                        quantity - take(sourceBacked, flow.item, quantity)));
        long coinsPaid = amount(losses(flows, id -> id == COINS), COINS);
        boolean hasOtherLoss = hasLoss(flows, identifier -> identifier != COINS); Transaction recovered = null;
        if (!returned.isEmpty()) {
            String source = service == null ? msg("graveRetrieval") : service.interactable;
            recovered = bookNeutral(now, msg("deathReclaimItems"), "Death reclaim", returned,
                            msg("itemsRecovered") + source + msg("afterDeath"), null);
            audit(recovered);
        }
        boolean feeEvidence = service != null || !returned.isEmpty();
        if (allowObservedFee && coinsPaid > 0 && feeEvidence && (!hasOtherLoss || !returned.isEmpty())) {
            String why = service == null ? msg("reclaimFee") : service.why(coinsPaid);
            flows.removeIf(flow -> flow.item == COINS && flow.cost()); return bookDeathFee(coinsPaid, why, now);
        }
        return recovered;
    }

    /** Create a neutral audit receipt for the measured negative portion of the death wipe. */
    Transaction deathWipeTransfer(List<Flow> flows, Map<Integer, Long> measured, long now) {
        if (empty(flows) || empty(measured)) return null; var outstanding = new HashMap<>(measured);
        var transferred = claim(flows, Flow::cost,
                        (flow, quantity) -> take(outstanding, flow.item, quantity));
        if (transferred.isEmpty()) return null;
        // Hardcore Worlds delete gravestone items for good (OSRS Wiki), whether the player then
        // resets or transfers: there the whole measured wipe is the death's loss (owner 2026-11-07).
        var bags = claim(transferred, Flow::cost, (flow, quantity) -> death.deleted || destroyedBag(flow.item) ? quantity : 0);
        Transaction loss = null;
        if (!bags.isEmpty()) loss = deathLoss(now, bags, death.deleted ? msg("hardcoreDeleted") : msg("bagDestroyed"));
        if (transferred.isEmpty()) return loss;
        Transaction transfer = bookNeutral(now, msg("deathItems"), "Death reclaim", transferred,
                        msg("ownershipNeutralTransfer"), null);
        audit(transfer); return first(loss, transfer);
    }

    /** One counted PvE death loss: a destroyed looting bag, or a Hardcore World's deleted grave. */
    Transaction deathLoss(long now, List<Flow> flows, String why) {
        return add(new Transaction(now, CONSUMPTION, GENERIC, msg("pvmLoss"), "Death reclaim", true, flows, CONFIRMED, why, null), false);
    }

    static boolean destroyedBag(int id) {
        return id == LOOTING_BAG || id == LOOTING_BAG_OPEN;
    }

    void recordZoneEntry(Map<Integer, Long> changes) {
        if (changes != null)
            changes.forEach((id, delta) -> {
                        if (id != null && delta != null && delta < 0)
                            stored.merge(id, abs(delta), SafeMath::sum);
                    });
    }

    /**
     * Excludes only positive quantities that match items actually removed on zone entry. Item ids
     * with a matched observed-loot event remain counted as loot even if they happen to overlap the
     * stored gear.
     */
    Transaction settleZoneRestore(List<Flow> flows, Map<Integer, Long> drops, long now) {
        if (restoreTicks <= 0 || stored.isEmpty() || empty(flows))
            return null;
        var sourceBacked = positive(drops);
        var restored = claim(flows, Flow::gain, (flow, quantity) -> take(stored, flow.item,
                                        quantity - take(sourceBacked, flow.item, quantity)));
        if (restored.isEmpty()) return null; if (stored.isEmpty()) restoreTicks = 0;
        return bookNeutral(now, msg("neutralZoneExit"), "Gauntlet", restored, msg("itemsRestored"), null);
    }

    static final Set<Integer> PVP_GRAVE = byId("pvp_gravestone").keySet();

    synchronized Transaction markPkDeath(long now, Map<Integer, Long> bag, Map<Integer, Long> held, long owner) {
        if (owner != events.owner) return null; raids.state.death = null; claims.cancelChest();
        if (current == null && !autoStart(now) || !live()) return null;
        var grave = positive(held); grave.keySet().retainAll(PVP_GRAVE); death.onPvmDeath(grave, valuation.hardcore());
        String fight = current.addEncounter(false, now);
        setContext(PK_DEATH, max(30, config.stabilizationTicks() + 4), "PK death loss", emptyMap(), fight);
        var lost = positive(bag); if (lost.isEmpty()) return null; lost.replaceAll((id, quantity) -> -quantity);
        return add(new Transaction(now, PK_DEATH_LOSS, PK_DEATH, msg("bagLoss"), "PKing", true,
                        valuation.value(lost, now), Confidence.LIKELY, msg("bagLossProof"), fight), false);
    }

    /**
     * An ownership-neutral storage or minigame move, with the note its classifier already chose.
     */
    synchronized void markTransfer(String note, int ticks) {
        markContext(TRANSFER, max(1, ticks), note);
    }

    /**
     * A proven Redeem click, bond-window click or gift line while tracking owns the next lone bond loss for 4 ticks,
     * through the same evidence lifecycle.
     */
    synchronized void markBondRedeem() {
        if (live()) events.put(BOND_REDEEM, 0, 1, 4, null);
    }

    /**
     * Known equipment changing wear/repair state stays owned. Pair by exact raw-id family;
     * preserve source-backed loot and charge only the observed repair fee.
     */
    Transaction wear(List<Flow> flows, long now, Map<Integer, Long> loot) {
        if (flows.isEmpty()) return null; var reserved = positive(loot);
        var keep = new HashMap<>(reserved); var pieceNet = new HashMap<String, Long>();
        for (Flow flow : flows) {
            // Only an accepted conversion owns a bond's pair (settleBond).
            String part = piece(flow); if (part == null || part.equals("bond")) continue;
            if (flow.cost() && dropped(flow.item)) return null;
            long quantity = flow.quantity;
            if (quantity > 0) quantity -= take(reserved, flow.item, quantity);
            pieceNet.merge(part, quantity, SafeMath::sum);
        }
        // Unrecognized families default to a nonzero net and cannot be paired.
        var stepped = claim(flows, flow -> pieceNet.getOrDefault(piece(flow), 1L) == 0L,
                        (flow, quantity) -> flow.gain() ? quantity - take(keep, flow.item, quantity) : quantity);
        if (stepped.isEmpty()) return null;
        for (var flow : stepped) {
            String target = STATE_BASIS.get(flow.item);
            if (target != null && flow.cost())
                basis.handoff(flow.item, parseInt(target), Math.abs(flow.quantity), now, null);
        }
        String part = piece(stepped.get(0));
        return bookNeutral(now, msg("state." + part, msg("barrowsWear")), "Equipment", stepped,
                        msg("state-why." + part, msg("barrowsGear")), null);
    }

    /**
     * An accepted bond conversion settles here, once. Its measured untradeable-to-tradeable pair is
     * the same owned bond changing state: a neutral row, and its known coverage moves to the new id.
     * The quoted total is one fee receipt, shown under the bond's name. The game takes the fee from
     * inventory coins first, up to what was held at Accept; that share is removed from this settle's
     * coin movement, so it is never charged twice and other cash stays ordinary. Without both legs
     * nothing books.
     */
    Transaction settleBond(List<Flow> flows, long now) {
        var quote = peek(BOND_PAYMENT); if (quote == null || !live()) return null;
        var old = firstMatch(flows, flow -> flow.item == OSRS_BOND_UNTRADEABLE && flow.quantity == -quote.item);
        var made = firstMatch(flows, flow -> flow.item == OSRS_BOND && flow.quantity == quote.item);
        if (old == null || made == null) return null;
        drop(BOND_PAYMENT); flows.remove(old); flows.remove(made);
        var coin = firstMatch(flows, flow -> flow.item == COINS); long share = min(quote.qty, quote.held);
        if (coin != null) {
            coin.quantity = sum(coin.quantity, share); coin.value = sum(coin.value, share); if (coin.quantity == 0) flows.remove(coin);
        }
        // The quote's fee is positive and tracking is live, so the fee row always books. What was
        // paid for the bond moves with it to the tradeable id, for its redemption, gift or sale.
        bookFee(quote.qty, msg("bond-fee"), "Bonds", now);
        basis.handoff(OSRS_BOND_UNTRADEABLE, OSRS_BOND, quote.item, now, null);
        return add(bookNeutral(now, msg("state.bond"), "Equipment", List.of(old, made), msg("state-why.bond"), null), false);
    }

    /**
     * An abyssal tentacle that runs out of charges, or is dissolved, leaves its kraken tentacle and
     * the whip inside is gone (OSRS Wiki). RuneLite prices the tentacle as the whip plus the kraken
     * tentacle, so the row nets to the whip: a loss, never a Review question (owner 1.3.3).
     */
    Transaction wornOut(List<Flow> flows, long now) {
        if (!hasLoss(flows, id -> id == ABYSSAL_TENTACLE)
                || gains(flows, id -> id == KRAKEN_TENTACLE).isEmpty()) return null;
        var pair = claim(flows, flow -> flow.item == ABYSSAL_TENTACLE ? flow.cost()
                                        : flow.item == KRAKEN_TENTACLE && flow.gain(),
                        (flow, quantity) -> 1);
        return bookCharge(now, msg("tentacleUsed"), CONFIRMED, msg("tentacleWhip"), pair, null, false);
    }

    /** A dwarf multicannon's parts set up or picked up: the same items, yours wherever they stand. */
    Transaction cannon(List<Flow> flows, long now) {
        if (peek(CANNON) == null) return null;
        var parts = claim(flows, flow -> matches("cannon", flow.name),
                        (flow, quantity) -> quantity);
        var seeded = claim(flows, flow -> flow.gain() && cannonball(flow.item), (flow, quantity) -> {
            long take = min(quantity, cannonSeed); cannonSeed -= take;
            return take;
        }); parts.addAll(seeded);
        return parts.isEmpty() ? null : bookNeutral(now, msg("cannonParts"), "Cannon", parts, msg("cannonWhy"), null);
    }

    /** Known wear/repair states, including Barrows, Moons and Perdu; never a display-name guess. */
    static final Map<Integer, String> WEAR_PIECES = byId("wear_gear"),
        STATE_BASIS = byId("owned_state_basis");

    static String piece(Flow flow) {
        return WEAR_PIECES.get(flow.item);
    }

    /**
     * A servant fetches from the bank, takes items to it and un-notes them: those moves are
     * neutral. Coins (his wage, a sawmill fee) still count, as do the logs a sawmill trip takes and
     * the planks it brings back, made from them.
     */
    Transaction servant(List<Flow> flows, long now) {
        if (peek(SERVANT) == null) return null; boolean sawmill = peek(SAWMILL) != null;
        var moved = claim(flows, flow -> flow.item != COINS && !(sawmill && wood(flow)),
                        (flow, quantity) -> quantity);
        // The sawmill trip ends when its planks come back.
        if (sawmill && any(flows, flow -> flow.gain() && wood(flow)))
            drop(SAWMILL);
        return moved.isEmpty() ? null : bookNeutral(now, msg("servantBank"), "Construction", moved,
                        msg("houseServant"), null);
    }

    /** Logs a sawmill trip takes, or the planks it brings back. */
    static boolean wood(Flow flow) {
        return matches("wood", flow.name);
    }

    /**
     * A measured Check supersedes this weapon's pending estimates. A component it read unchanged
     * was not used, so its estimates are removed, as a Check that moved nothing removes them all.
     */
    synchronized void clearPendingEstimates(String target, Set<Integer> unchanged) {
        if (current == null) return; String id = current.id();
        for (int identifier : unchanged)
            for (var receipt :
                    estimate.receipts(id, target, identifier))
                forfeit(receipt);

        estimate.clearTarget(id, target);
    }

    /**
     * A compatible Check that moved nothing closes the window for this target: any automatic
     * estimates still pending were overestimates and are removed through the announced path.
     */
    synchronized void closeChargeWindow(String target) {
        if (current == null || blank(target))
            return;
        String id = current.id();
        for (var receipt : estimate.receiptsFor(id, target))
            forfeit(receipt);
        estimate.clearTarget(id, target);
    }

    synchronized boolean markChargeLoad(ChargedItem variant, int selectedId, String selectedItemName,
            String target, int ticks) {
        if (variant == null || ticks <= 0 || !ChargeCheck.isLoadComponent(variant, selectedId, selectedItemName))
            return false;
        var e = events.put(LOAD_TARGET, selectedId, 1, ticks - 1, null); e.variant = variant;
        e.id = fallback(target, null); e.at = now(); return true;
    }

    /**
     * A load click links candidate measured losses to a physical target; it proves no load
     * quantity.
     */
    LoadTarget loadTarget(List<Flow> flows) {
        var e = peek(LOAD_TARGET); var options = flowList();
        var left = flowList(); boolean selected = false;
        for (Flow flow : e == null || flows == null ? List.<Flow>of() : flows)
            if (flow.cost() && ChargeCheck.isLoadComponent(e.variant, flow.item, flow.name)) {
                options.add(flow); selected |= flow.item == e.item;
            } else left.add(flow);

        return selected ? new LoadTarget(e.variant, e.id, left, options, e.at)
                : new LoadTarget(null, null, flows == null ? emptyList() : flows, emptyList(), 0);
    }

    /**
     * A refused automatic use (the game answered "already at that location"): the click spent
     * nothing, so the estimate it booked is removed through the announced automatic-evidence path
     * (owner 1.3.4). Other pending estimates for the target are untouched.
     */
    synchronized void forfeitEstimate(String identity, String id) {
        if (current == null || empty(id)) return; String target = fallback(identity, null); if (target == null) return;
        for (var receipt : estimate.receiptsFor(current.id(), target))
            if (id.equals(receipt.transaction)) forfeit(receipt);

    }

    /** Clear a pending charge-load intent when another action supersedes its evidence. */
    synchronized void clearChargeLoad() {
        drop(LOAD_TARGET);
    }

    /** Observe one exact Check read against that weapon's own last Check. */
    synchronized ChargeDelta observeCheck(ChargeCheck read, String target, long now) {
        return checks.observe(read, target, now);
    }

    /** Forget one kind's Check reads (every kind when null): its next Check starts fresh. */
    synchronized void resetChecks(ChargedItem variant) {
        if (variant == null) checks.reset(); else checks.resetVariant(variant);
    }

    /**
     * A supported charged weapon entering the inventory is a new physical instance: a measured
     * Check baseline for that variant can no longer be trusted to belong to the item now in the
     * slot. Fail closed and require a fresh baseline instead of booking a phantom decrease from a
     * replacement that retained the same slot identity.
     */
    void invalidateChargeBaselines(Map<Integer, Long> changes) {
        if (empty(changes)) return;
        for (var entry : changes.entrySet()) {
            Long quantity = entry.getValue(); if (quantity == null || quantity <= 0) continue;
            var variant = ChargeCheck.supported(entry.getKey()); if (variant != null) checks.resetVariant(variant);
        }
    }

    /** Start tracking the items removed by entering a region-based neutral zone. */
    synchronized void beginNeutralZone() {
        stored.clear(); zoneTransfer = true; capture = true; storedBaseline = baseline;
        // Inventory and equipment callbacks can settle in separate batches. Keep
        // reconciling against the entry snapshot for a short, fixed window so the
        // later batch is included, but in-zone supply use cannot be captured forever.
        captureTicks = max(8, config.stabilizationTicks() + 6); restoreTicks = 0;
    }

    /**
     * End the region-based transfer context. Only a matching return of an item actually removed on
     * entry remains neutral; later lobby rewards keep their normal classification.
     */
    synchronized void endNeutralZone(int ticks) {
        boolean zoneWasActive = zoneTransfer; zoneTransfer = false;
        stopCapture(); restoreTicks = stored.isEmpty() ? 0 : max(1, ticks);
        boolean pendingZone = pending().context == TRANSFER && pending().note != null
                        && pending().note.startsWith(msg("neutralZoneInstance")),
            activeZone = context().context == TRANSFER && context().note != null
                        && context().note.startsWith(msg("zoneExpired"));
        if (pendingZone) pending().clear();
        if (activeZone) {
            context().clear(); drop(CLOCK);
        }
        if (zoneWasActive || pendingZone || activeZone) {
            drop(SOFT_TRANSFER); clearTransfer();
        }
    }

    /** Begin clue/casket path so dig/tele/key spends pair as clue costs. */
    synchronized void beginCluePath(String encounter, String label) {
        clues.beginClue(encounter, label);
    }

    /** Ids of the unresolved key/chest claims; a Ledger row with one of these ids is still pc(). */
    synchronized Set<String> pendingClaimIds() {
        var ids = new LinkedHashSet<String>(); for (var claim : claims.all()) ids.add(claim.claimId());
        return ids;
    }

    synchronized List<PendingClaim> pendingClaims() {
        return claims.all();
    }

    synchronized void beginKeyDeath(Map<Integer, Long> keyInventory) {
        claims.beginLocalDeath(keyInventory);
    }

    synchronized void tickKeyClaims() {
        claims.tick();
    }

    /** Items taken apart and what doing it loses (d23). */
    static final Map<Integer, String[]> DISMANTLE = index(rows("dismantle"), row -> parseInt(row[0]), row -> row);

    /** Clears these kinds of evidence. */
    void drop(int... kinds) {
        events.drop(kinds);
    }

    /** The newest live evidence of this kind, or null. */
    Observation peek(int kind) {
        return events.peek(kind);
    }

    /** The item a Revert or Dismantle click takes apart, for the next few ticks. */
    synchronized void dismantle(int id) {
        events.put(DISMANTLE_CLICK, id, 1, 5, null);
    }

    /** What taking an item apart loses (owner 1.2: a crystal tool's 120 shards), as one confirmed row. */
    Transaction dismantleLoss(long at, String note, String activity, String[] lost) {
        return new Transaction(at, CONSUMPTION, GENERIC, note, activity, true,
                valuation.value(Map.of(parseInt(lost[2]), -Long.parseLong(lost[1]))), CONFIRMED, msg("lostDismantled"), null);
    }

    /**
     * 1.7.0 only, remove in the next release: the crystal tool that left in a Review where exactly
     * its seed and its own dragon tool (dismantle.tsv) arrived, or null.
     */
    static Flow revertReview(Transaction row) {
        if (!needsReview(row) || row.flows().size() != 3 || row.quantity(PRIF_TOOL_SEED, true) != 1) return null;
        for (Flow flow : row.flows()) {
            var lost = flow.quantity == -1 ? DISMANTLE.get(flow.item) : null;
            if (lost != null && row.quantity(parseInt(lost[3]), true) == 1) return flow;
        }
        return null;
    }

    /** Arm/cancel ordinary key-chest provenance from an actual chest object click. */
    synchronized boolean observeChest(String option, String target) {
        return claims.chestClick(option, target);
    }

    /**
     * Per-tick GE custody maintenance: a held collect resolves at its window end and expired
     * lifecycles hand off even when no further inventory change settles. Returns true when a
     * booking was added.
     */
    synchronized boolean tickGe(long now) {
        return live() && maintainMarket(now);
    }

    /** Durable schema-104 GE custody lifecycle; flows are claimed at Flow quantity level. */
    GeCustodyLedger exchange = new GeCustodyLedger();

    /**
     * Schema-106 pooled known-basis coverage; supporting ownership state, never a second ledger.
     */
    TrackedBasisLedger basis = new TrackedBasisLedger();

    /**
     * Feeds an offer transition to the custody lifecycles. Review handoffs (slot reuse, identity
     * replacement, unobserved terminal) and settlements completed by a held collect book once.
     */
    synchronized void noteGeOffer(OfferSlots.Transition transition, String name, long now) {
        for (var result : exchange.observeTransition(transition, name, now,
                        current == null ? "" : current.id()))
            bookCustody(result);
    }

    /**
     * Login/relog slot baseline. Resumes persisted custody lifecycles whose offer identity is
     * unchanged, baselines offers first seen already open without acquisition basis, and retires
     * claims made elsewhere silently. Only an observed local collection can settle new money.
     */
    synchronized void seedGeSlots(Map<Integer, OfferSlots.Snapshot> snapshots, long now) {
        for (var handoff : exchange.seedSlots(
                        snapshots, now, current == null ? "" : current.id()))
            bookCustody(handoff);
    }

    /** Receipt id → its first holding session, only while Market rows resolve settlements. */
    Map<String, Session> holders;

    /** Derived, read-only Market settlement context over the durable custody lifecycles. */
    synchronized List<MarketRows.Row> marketRows() {
        var records = exchange.records(); if (records.isEmpty()) return emptyList(); holders = new HashMap<>();
        for (var session : sessions())
            for (var transaction : session.transactions())
                if (transaction != null) holders.putIfAbsent(transaction.id(), session);
        try {
            return MarketRows.rows(records, this::transactionById, this);
        } finally {
            holders = null;
        }
    }

    /**
     * The session holding this booked transaction, or null when none is retained: Market rows
     * follow the session that holds their settlement (cross-Grind sales).
     */
    Session sessionHolding(String id) {
        return holders != null ? holders.get(id) : firstMatch(sessions(), session -> findTransaction(session, id) != null);
    }

    Session sessionById(String id) {
        return firstMatch(sessions(), session -> session != null && session.id().equals(id));
    }

    /** Effective correction-aware canonical settlement lookup for the derived Market projection. */
    Transaction transactionById(String id) {
        if (holders != null) return findTransaction(holders.get(id), id);
        for (var session : empty(id) ? List.<Session>of() : sessions()) {
            var found = findTransaction(session, id); if (found != null) return found;
        }
        return null;
    }

    static Transaction findTransaction(Session session, String id) {
        return session == null ? null : firstMatch(session.transactions(), transaction -> transaction != null && id.equals(transaction.id()));
    }

    /** Native per-slot collection evidence, kept in the same owner as custody. */
    synchronized void noteGeBox(int slot, net.runelite.api.Item[] items, IntUnaryOperator canon, long now) {
        for (var row : exchange.collectionBox(slot, items, canon, now,
                current == null ? "" : current.id(), now <= events.at(GE_COLLECT))) bookCustody(row);
    }

    /**
     * A bounded player Collect interaction with the Grand Exchange collection box. It only enables
     * exact custody collection correlation for the window; it never books anything and never owns a
     * movement by itself.
     */
    synchronized void noteGeCollect(long now) {
        long until = max(events.at(GE_COLLECT), now + 20000); events.put(GE_COLLECT, -1, 1, Integer.MAX_VALUE, null).at = until;
    }

    /**
     * Release held cash and coffer drain to their current owner before dropping transient
     * evidence; every pause, session change and profile install passes here first.
     */
    synchronized void clearCollect() {
        settleCoffer(); drop(GE_COLLECT); exchange.releaseHeldCollect().forEach(this::bookCustody);
    }

    /** Canonical custody outcomes (review handoffs and retried settlements) are booked once. */
    void bookCustody(Transaction result) {
        if (result != null && current != null)
            // Counted custody settlements already consumed their reservation; their item legs
            // must never deplete known coverage again.
            add(result, result.counts());
    }

    /** Authoritative profile/analytics timezone for presentation date scopes. */
    synchronized ZoneId timeZone() {
        try {
            return of(zone);
        } catch (RuntimeException ex) {
            return systemDefault();
        }
    }

    synchronized void setChestVisible(boolean visible) {
        claims.setLootChestVisible(visible);
    }

    synchronized void observeKeyContainer(int key, Map<Integer, Long> contents) {
        claims.observeKeyContents(key, contents);
    }

    /** The exact price of one pending loot key's observed chest contents; 0 when unknown. */
    synchronized long pendingKeyValue(int key, long now) {
        return pricedContents(claims.observedContents(key), now);
    }

    /**
     * The exact price across every pending key's observed chest, each key type once (one opening
     * takes a chest's contents). 0 when no chest was observed or a part has no quote.
     */
    synchronized long pendingKeysValue(long now) {
        long total = 0; var seen = new HashSet<Integer>();
        for (var claim : claims.all()) {
            if (claim == null || !claim.isValid() || !seen.add(claim.itemOrKeyId)) continue;
            total = sum(total, pricedContents(claims.observedContents(claim.itemOrKeyId), now));
        }
        return total;
    }

    /**
     * A chest manifest priced exactly from its observed contents: every part needs a supported
     * quote, or nothing shows (owner 2026-10-14). The game's own display rounds to a million.
     */
    private long pricedContents(Map<Integer, Long> contents, long now) {
        if (empty(contents)) return 0; var flows = valuation.value(contents, now);
        // A component the valuator silently drops (an empty vial) fails the manifest closed.
        if (flows == null || flows.size() != contents.size()) return 0; long total = 0;
        for (Flow flow : flows) {
            if (flow.unitPrice <= 0) return 0; total = sum(total, flow.value);
        }
        return total;
    }

    /** A non-counted, ownership-neutral audit row: transfers, holds, restores and reclaims. */
    Transaction bookNeutral(long now, String note, String activity, List<Flow> flows, String reason,
            String encounter) {
        return new Transaction(now, TransactionType.TRANSFER, TRANSFER, note, activity, false, flows,
                CONFIRMED, reason, encounter);
    }

    /** A session is open and not paused. */
    boolean live() {
        return current != null && !current.paused;
    }

    /**
     * Books a row; custody-owned rows already consumed their reservation and never deplete again.
     */
    Transaction add(Transaction transaction, boolean owned) {
        if (transaction.original() == TRADE || transaction.original() == PK_DEATH_LOSS)
            for (var f : transaction.flows()) if (f.cost())
            take(raids.state.supplies, net.runelite.client.game.ItemVariationMapping.map(f.item),
                    multiply(Math.abs(f.quantity), max(1, dose(f.name))));
        current.addTransaction(transaction, config.maxTransactionsPerSession(), owned); queueDose(current, transaction);
        return transaction;
    }

    /** Death storage stays visible; unrelated neutral moves follow the audit preference. */
    void audit(Transaction row) {
        boolean death = deathNote(row.note()) || row.note().equals(msg("deathReclaimItems"));
        if (!(death && raids.find(raids.state.death) != null) && (death || config.keepTransferAuditRows())) add(row, false);
    }

    /** Books the custody settlements that came due; true when any did. */
    boolean maintainMarket(long now) {
        var rows = exchange.maintenance(now, current.id()); rows.forEach(booking -> add(booking, true));
        return !rows.isEmpty();
    }

    /** A booked charge row: counted consumption with the current activity, then its action kind. */
    private Transaction bookCharge(long now, String note, Confidence confidence, String reason, List<Flow> flows,
            ActionKind kind, boolean estimate) {
        Transaction transaction = new Transaction(now, CONSUMPTION, GENERIC, note,
                        fallback(current.activityHint(), GENERAL), true, flows, confidence, reason,
                        null);
        transaction.setAction(kind);
        // An estimated blowpipe dart lands by the target like a fired bolt; its scales are used up.
        transaction.setOnGround(estimate && any(flows, Engine::groundDart)); return add(transaction, false);
    }

    /**
     * Charges the game counted out exactly (uncharging Bryophyta's staff): a measured spend, kept
     * out of the estimate journal so a later Check never reshapes it (1.3.1).
     */
    synchronized void spent(String item, List<Flow> charges, long now) {
        if (live())
            bookCharge(now, msg("measuredCharge") + item, CONFIRMED, msg("gameCounted") + item + ".",
                    charges, CAST, false);
    }

    /** One chug from the chugging barrel: its doses as one Drank row titled by the barrel. */
    synchronized void chug(List<Flow> doses, long now) {
        if (live())
            bookCharge(now, "Chugging barrel", CONFIRMED, msg("drunkChugging"), doses, DRINK,
                            false).setActionLabel(ActionLabel.of("Chugging barrel"));
    }

    /**
     * Book estimated component losses for one automatic charge use. The receipt counts immediately
     * at the captured prices with LIKELY confidence; a later measured Check reconciles it. Priced
     * components enter the transient journal so a Check can shrink them without repricing.
     */
    synchronized Transaction bookEstimatedCharge(String identity, String family, ActionKind kind,
            List<Flow> removed, long now) {
        String target = fallback(identity, null), label = fallback(family, null);
        if (!live() || target == null || label == null || empty(removed))
            return null;
        Transaction transaction = bookCharge(now, msg("estimatedCharge") + label, Confidence.LIKELY, "Estimated",
                        removed, kind == null ? CAST : kind, true);
        String id = current.id();
        for (Flow flow : transaction.flows())
            if (flow.cost() && flow.unitPrice > 0)
                estimate.record(id, target, transaction.id(), flow.item,
                        Math.abs(flow.quantity));

        if (transaction.onGround()) rememberDrop(transaction, Engine::groundDart); return transaction;
    }

    /**
     * Consume one measured component decrease against this target's pending estimates. An
     * uncorrected straddling receipt is shrunk to the measured quantity at its captured price and
     * confirmed; manually corrected receipts are only consumed, never edited. Returns the covered
     * quantity.
     */
    private long reconcileEstimate(String sessionId, String target, int id, long measured) {
        long left = measured;
        for (var receipt : estimate.receipts(sessionId, target, id)) {
            long remaining = receipt.remaining; if (remaining <= 0) continue;
            var estimate = current.findTransaction(receipt.transaction);
            boolean frozen = estimate == null || estimate.correction() != AUTO;
            if (left < remaining && !frozen) {
                // Shrink the overestimate through the session's announced automatic-evidence path.
                current.settleEstimate(estimate, id, remaining - left, "Confirmed");
                this.estimate.consume(receipt, remaining); left = 0; continue;
            }
            long take = min(left, remaining); this.estimate.consume(receipt, take); left -= take;
            if (!frozen && take >= remaining) {
                estimate.confidence = CONFIRMED; estimate.setExplanation("Confirmed");
            }
            if (left <= 0) break;
        }
        // The measured window is closed here: automatic estimates left over are overestimates
        // and are removed, while manually corrected rows are preserved for future credit.
        for (var receipt : estimate.receipts(sessionId, target, id))
            forfeit(receipt);
        return measured - left;
    }

    /** Removes one overestimated automatic receipt through the session's announced path. */
    private void forfeit(ChargeEstimateJournal.Receipt receipt) {
        if (receipt == null || receipt.remaining <= 0 || current == null) return;
        var estimate = current.findTransaction(receipt.transaction);
        if (estimate == null || estimate.correction() != AUTO) return;
        current.settleEstimate(estimate, receipt.item, receipt.remaining, "Check reconciled");
        this.estimate.consume(receipt, receipt.remaining);
    }

    /**
     * Book exact component losses from a compatible measured Check difference, first reconciling
     * any pending estimates for that target; returns the booked row, or null (zero cost) when the
     * measurement adds nothing beyond what estimates already booked. Generic inventory losses, menu
     * intent and animations cannot authorize this.
     */
    synchronized Transaction bookChargeSpend(ChargeDelta measuredDelta, String name, List<Flow> removed,
            long now, String identity) {
        if (!live() || measuredDelta == null || measuredDelta.exactCost(removed) <= 0)
            return null;
        var booked = removed; String target = fallback(identity, "");
        if (!target.isEmpty()) {
            var excess = flowList(); String id = current.id();
            for (Flow flow : removed) {
                long measured = Math.abs(flow.quantity),
                    extra = measured - reconcileEstimate(id, target, flow.item, measured)
                                - estimate.evictedOverlap(id, target, flow.item);
                if (extra <= 0) continue;
                excess.add(extra == measured ? flow : new Flow(flow.item, flow.name, -extra, flow.unitPrice,
                                        -multiply(extra, flow.unitPrice), flow.source(),
                                        flow.priceCapturedAtEpochMillis));
            }
            if (excess.isEmpty()) return null; booked = excess;
        }
        // The spend belongs to what the player is doing, not to the weapon: Top activities
        // must never grow a "Toxic blowpipe" row. The weapon stays in the note.
        return bookCharge(now, msg("measuredCharge") + fallback(name, "Measured charges"),
                        CONFIRMED, msg("measuredCheck") + measuredDelta.variant.getDisplayName() + ".",
                        booked, measuredDelta.variant == ChargedItem.BLOWPIPE ? FIRE : CAST, false);
    }

    /** Book a death reclaim fee as a plain counted CONSUMPTION cost under "Death reclaim". */
    synchronized Transaction bookDeathFee(long fee, String why, long now) {
        String label = why == null ? msg("graveFee") : why;
        var row = bookFee(fee, label, "Death reclaim", now); raids.fee(row); return row;
    }

    /** A dispenser award the chat named, waiting for its settle; empty when none. */
    Map<Integer, Long> reward = numberMap();

    long bagRewardUntil;

    /** Confirmed additions to the looting bag since the client last showed its contents. */
    Map<Integer, Long> bag = numberMap(),

        contents = numberMap();

    synchronized void rememberBag(Map<Integer, Long> contents) {
        var valid = positive(contents); valid.entrySet().removeIf(e -> e.getKey() <= 0 || e.getValue() > Integer.MAX_VALUE);
        if (valid.size() > 28) valid.clear();
        if (!this.contents.equals(valid)) {
            this.contents = valid; changed();
        }
    }

    synchronized Map<Integer, Long> bagStock() {
        var stock = new HashMap<>(contents); bag.forEach((id, quantity) -> stock.merge(id, quantity, SafeMath::sum));
        return stock;
    }

    /**
     * Wilderness Agility pays its dispenser into an open looting bag, which the client never shows
     * (owner live 2026-10-16). The chat names the whole award, so what the next settle does not
     * bring into the pack went into the bag and books as loot. The bag only ever empties into the
     * bank, so nothing books twice.
     */
    synchronized void noteBagReward(Map<Integer, Long> awarded, long now) {
        reward = new HashMap<>(awarded); bagRewardUntil = now + 6000;
    }

    Transaction settleBagReward(List<Flow> flows, long now) {
        if (reward.isEmpty()) return null; var bag = new HashMap<>(reward); reward.clear();
        if (now > bagRewardUntil || !live()) return null;
        for (Flow flow : flows)
            if (flow.gain()) bag.computeIfPresent(flow.item, (id, q) -> q - flow.quantity);
        bag.values().removeIf(quantity -> quantity <= 0); if (bag.isEmpty()) return null;
        bag.forEach((id, quantity) -> this.bag.merge(id, quantity, Long::sum));
        return add(new Transaction(now, TransactionType.LOOT, LOOT, msg("bag-reward"),
                        current.activityHint(), true, valuation.value(bag), Confidence.LIKELY,
                        msg("bagProof"), null), false);
    }

    /** The Blast Furnace coffer's drain while you work it (owner 2026-10-16, after Coin Flow 1.9). */
    synchronized Transaction bookCofferDrain(long gp, long now) {
        return bookFee(gp, msg("bfCoffer"), "Smithing", now);
    }

    /** The Blast Furnace coffer's last balance (-1 until read) and its tick; unbooked drain, when and whose. */
    int coffer = -1, cofferTick;

    long drain, drainAt; String drainer;

    /**
     * The Blast Furnace coffer drains 72,000 coins an hour, 12 a tick, while you work it (OSRS
     * Wiki). Deposits and withdrawals stay neutral: a drop counts as drain only when it is no more
     * than 12 gp a tick since the last change; anything faster is a withdrawal. Only an active,
     * ingesting session accrues drain; other readings just move the balance. Drain books in 1,200
     * gp batches (owner 2026-10-16, after Coin Flow 1.9) and before its session pauses, ends or saves.
     */
    synchronized void coffer(int gp, int tick, boolean eligible, long now) {
        long gone = cofferDrain(coffer, gp, tick - cofferTick); coffer = gp; cofferTick = tick;
        if (!eligible || !live() || gone == 0 || drain > 0 && !current.id().equals(drainer)) return; drainer = current.id();
        drain += gone; drainAt = now;
        if (drain >= 1200) settleCoffer();
    }

    /**
     * The drain in one coffer change: a drop the coffer could drain in the ticks elapsed (plus one);
     * a bigger drop is a withdrawal, and its few coins of drain are left out.
     */
    static long cofferDrain(int before, int after, int ticks) {
        long drop = before < 0 ? 0 : before - after; return drop > 0 && drop <= 12 * (max(1, ticks) + 1) ? drop : 0;
    }

    /** Books unbooked drain to the session it came from; it stays pending until that succeeds. */
    void settleCoffer() {
        if (live() && current.id().equals(drainer) && bookCofferDrain(drain, drainAt) != null)
            drain = 0;
    }

    /** A bank-paid fee as a plain counted CONSUMPTION cost under its own row name. */
    synchronized Transaction bookFee(long fee, String label, String activity, long now) {
        if (fee <= 0 || !live()) return null;
        Transaction transaction = new Transaction(now, CONSUMPTION, GENERIC, label, activity, true,
                        singletonList(new Flow(COINS, "Coins", -fee, 1, -fee, FACE_VALUE, now)), CONFIRMED, label, null);
        return add(transaction, false);
    }

    /**
     * A Ledger decision over one receipt or every receipt of a grouped row, as one change with one
     * Undo; a missing or already removed receipt refuses it all. An explicit removal changes money
     * while keeping accounted physical value.
     */
    synchronized boolean correctEntries(Collection<String> ids, Correction decision) {
        var rows = matching(list(ids, id -> current == null ? null : current.findTransaction(id)),
                row -> row != null && !(decision == IGNORE && row.correction() == IGNORE));
        if (rows.isEmpty() || rows.size() < ids.size()) return false;
        // Both physical projections then match, so the existing validator accepts a removal.
        if (decision == IGNORE) rows.forEach(row -> row.ownershipCorrection = row.correction());
        return current.correct(rows, decision, "Ledger correction");
    }

    synchronized boolean correctTransaction(String id, Correction decision, String reason) {
        return current != null && current.correctTransaction(id, decision, reason);
    }

    /** Unresolved owner decisions in the active session, newest first, as review rows. */
    synchronized List<ReviewRow> getReviewRows(long now) {
        var rows = current == null ? new ArrayList<ReviewRow>()
                : list(matching(current.transactions(), ReviewRules::needsReview),
                        transaction -> reviewRow(current, transaction, now));

        rows.sort(comparingLong((ReviewRow itemDataValue) -> itemDataValue.at).reversed()); return rows;
    }


    /** Canonical after-Net for the selected Review's two financial choices, in one engine hold. */
    synchronized Map<Decision, Long> reviewNets(String id) {
        var nets = new EnumMap<Decision, Long>(Decision.class); var row = current == null ? null : current.findTransaction(id);
        if (row != null && needsReview(row) && row.gross() != 0)
            for (var decision : List.of(Decision.GAIN, Decision.COST))
                if (current.canCorrect(row, decision.correction))
                    nets.put(decision, current.netAfter(singleton(id),
                                    copy -> copy.applyCorrection(decision.correction, "")));
        return unmodifiableMap(nets);
    }

    /** Domain revision: every canonical mutation advances it; derived previews bind to it. */
    synchronized long revision() {
        return revision;
    }

    /**
     * Exact rows and prospective personal-Net delta a Decide all would apply, computed from the
     * same canonical transaction ids that {@link #applyDecision} mutates.
     */
    synchronized DecisionPreview previewDecision(Decision decision, Predicate<ReviewRow> scope, long now) {
        var selectedIds = new ArrayList<String>();
        if (current != null && decision != null && scope != null) {
            var rows = matching(current.transactions(), transaction -> {
                var row = reviewRow(current, transaction, now);
                return needsReview(transaction) && scope.test(row);
            });
            // The projected batch is the same validation application uses.
            if (current.canCorrect(rows, decision.correction, now)) for (var transaction : rows) selectedIds.add(transaction.id());
        }
        long net = selectedIds.isEmpty() ? 0 : subtract(current.netAfter(selectedIds,
                        copy -> copy.applyCorrection(decision.correction, "")), current.metrics(now).net);
        return new DecisionPreview(decision, selectedIds, net, revision());
    }

    /**
     * Applies a preview as one atomic batch with one undo. Returns {@code -1} without touching
     * anything when the domain revision moved since the preview (stale row ids never apply).
     */
    synchronized int applyDecision(DecisionPreview preview) {
        return preview == null || current == null || preview.transactionIds.isEmpty() ? 0 : preview.revision != revision() ? -1 : current.correctAll(preview.transactionIds, preview.decision.correction,
                        "Review decision: " + preview.decision.name());
    }

    /**
     * Revision-bound Decide all impact: canonical ids, row count and exact prospective Net delta.
     */
    static class DecisionPreview {
        Decision decision; List<String> transactionIds;
        long netDelta, revision;

        DecisionPreview(Decision decision, List<String> ids, long net, long revision) {
            this.decision = decision; transactionIds = frozenList(ids); netDelta = net; this.revision = revision;
        }

        int rowCount() {
            return transactionIds.size();
        }
    }

    static ReviewRow reviewRow(Session session, Transaction transaction, long now) {
        var items = list(transaction.flows(),
                        flow -> new Item(flow.item, flow.name, flow.quantity, flow.value));
        if (items.isEmpty())
            items.add(new Item(-1, transaction.note(), 0, transaction.autoNet()));
        String why = transaction.explanation(); if (blank(why)) why = msg("uncertainTransaction");
        long timestamp = transaction.timestamp, age = now > timestamp ? now - timestamp : 0;
        return new ReviewRow(transaction.id(), session.id(), timestamp, age, items, transaction.autoNet(), why,
                EnumSet.allOf(Decision.class));
    }

    synchronized boolean splitItem(String id, int identifier, long keep, String note) {
        return current != null && current.splitItem(id, identifier, keep, note);
    }

    synchronized boolean undoCorrection() {
        return current != null && current.undoCorrection();
    }

    synchronized Transaction undoTransaction() {
        return current == null ? null : current.undoTransaction();
    }

    synchronized Transaction restoreUndo() {
        return current == null ? null : current.restoreUndo();
    }

    synchronized void recordAction(String activity) {
        if (live()) current.recordAction(activity);
    }

    /** Additional source receipts are published after canonical booking has completed. */
    synchronized Transaction processIfDirty(ContainerSnapshot current, long now,
            Consumer<Transaction> additional) {
        events.hold = dirty || pending != null
                        || current != null && baseline != null && !current.equals(baseline);
        events.tick(events.now + 1); if (live()) retryDose(now);
        settleGround();
        // Inventory alone must not create a tracker when automatic startup is
        // disabled or when no session has been started yet.
        if (this.current == null) return null;
        // adj is fed by the client's per-game-tick snapshot path.
        // Keep active-time accrual on that event path instead of analytics reads.
        // This window ages once per inventory/game-tick sample, even when the
        // inventory remains dirty across several stabilization samples.
        if (captureTicks > 0 && --captureTicks <= 0) stopCapture();
        // Death and reclaim windows are real game-tick lifecycles. Advance them before
        // any dirty/stabilization early return so inventory churn cannot pause expiry.
        int retrieval = RaidHistory.kind(raids.state.death); if (retrieval > 0 && retrieval < 3) death.age = 0;
        String expired = death.tick(events.hold);
        if (expired != null) {
            return add(bookNeutral(now, msg("reclaimExpired"), "Death reclaim", emptyList(), expired, null), false);
        }
        if (this.current.paused) {
            // An inventory gain during an Idle pause is the action that ended it (a
            // pickpocket's loot can settle before the activity detector fires): resume and
            // book it. Other pauses stay aligned so Resume never inherits pause-window
            // churn as catch-up profit (owner 2026-09-29).
            var woke = current == null || baseline == null ? null : current.diff(baseline);
            boolean idleResume = this.current.pauseReason() == IDLE && woke != null && any(woke.values(), value -> value != null && value > 0);
            if (!idleResume) {
                // A death wipe while paused still moves gear to the grave: remember it so its
                // reclaim stays neutral.
                if (death.wipePending() && woke != null) death.removed(valuation.value(woke, now));
                // Keep the baseline aligned while paused so Resume never treats
                // pause-window inventory churn as catch-up profit.
                baseline = current; if (capture) storedBaseline = current;
                warming = false; clearPending(); drop(CONSUME, SPENT_CONSUME); drop(DROP_CLICK);
                clearTransfer(); return advanceContext();
            }
            this.current.resume(now);
        }
        if (warming) {
            observePriming(current);
            return null;
        }
        if (baseline == null) {
            baseline = current; if (capture) storedBaseline = current;
            clearPending();
            return null;
        }
        if (!dirty && current.equals(baseline)) return advanceContext();
        // Container callbacks are expected, but comparing the actual snapshot
        // makes the engine resilient to a missed or reordered callback.
        if (!dirty) {
            dirty = true; captureContext();
        }
        var cast = peek(CAST_SPELL); var spell = cast == null ? null : cast.label;
        var previous = this.cast == null ? null : this.cast.label;
        if (pending != null && !pending.equals(current) && cast != null && cast != this.cast
                && !Objects.equals(previous == null ? null : previous.value,
                        spell == null ? null : spell.value)) {
            // The previous complete tick belongs to the earlier spell. Settle it before
            // accepting the new cast's snapshot, even inside the stabilization window.
            var prior = settle(pending, now, additional); clearContext();
            if (prior != null && additional != null) additional.accept(prior); dirty = true; captureContext();
        }
        if (pending == null || !pending.equals(current)) {
            pending = current; if (cast != null) this.cast = cast;
            stableTicks = 0; captureContext();
            return null;
        }
        stableTicks++; if (stableTicks < max(0, config.stabilizationTicks())) return null;
        var settled = settle(pending, now, additional); clearContext(); return settled;
    }

    /**
     * One stabilized inventory change moving through the settle stages, with the claim it settles
     * under.
     */
    static class Settle extends Clue {
        long now; ContainerSnapshot snapshot;
        List<Flow> flows;

        /**
         * A local death with no confident PvM/PK classification: routine spends stay unlabelled.
         */
        boolean suppress,

            death, wipe, ambiguousCoins;
        Map<Integer, Long> lost = emptyMap(), keys = emptyMap(); String claim;
        Transaction keyAudit; LootMatch loot = LootMatch.NONE;

        /** Source-backed quantities, kept even if the loot label is later refused. */
        Map<Integer, Long> lootItems = emptyMap();

        boolean destroy, pricingReview, combine, disassemble, drop, used, inferred, asTransfer; ActionKind intent;
        ActionLabel label, spell; int usedItem = -1, dropItem = -1;
        String activity;

        Settle(Clue claim, ContainerSnapshot committed, long now) {
            offer(claim.context, claim.note, claim.expected, claim.fight, true); transfer = claim.transfer; hard = claim.hard;
            snapshot = committed; this.now = now;
        }

        /** The matched action moved to its own slice: this change no longer claims it. */
        void release() {
            used = false; intent = null; label = null;
        }

        /** Moves {@code moved} out of this change into a fresh generic slice of its snapshot. */
        Settle slice(List<Flow> moved) {
            var slice = new Settle(new Clue(), snapshot, now); slice.flows = moved;
            slice.activity = activity; flows = copiedList(flows); flows.removeAll(moved);
            return slice;
        }
    }

    /**
     * Settles one stabilized inventory change. The stages run in order; each may book its own
     * receipt and removes the flows it owns: commit, death wipe, key tokens, held items (death
     * keys, wipe, charge loads), returns (loot, chest claims, death reclaim), neutral-zone restore,
     * a confirmed drop's own item, actions and transfers, GE custody, then classification and
     * booking of what is left.
     */
    Transaction settle(ContainerSnapshot committed, long now,
            Consumer<Transaction> additional) {
        var batch = commit(committed, now); if (batch == null) return null;
        // Published like any extra booking, so HUD+'s tray shows the bag's rewards too.
        var bag = settleBagReward(batch.flows, now); if (bag != null && additional != null) additional.accept(bag);
        // Minigame-only items (a loaned loadout, table supplies) cannot legally exist outside
        // their minigame: the grant and the exit strip vanish before any settle stage sees them, so
        // they never book, reach Review or pollute the death-wipe whitelist (Coinflow
        // MinigameQuarantine evidence, owner 2026-10-12). commit()'s swap filter runs earlier and
        // cannot pair these ids; the drink normalization runs later, so a loaned dose sip can never
        // be rewritten into real ids.
        batch.flows.removeIf(flow -> MinigameItems.isMinigameOnly(flow.item));
        raids.supplies(batch.flows, false,
                batch.lootContext() ? batch.expected : emptyMap());
        // An essence pouch's Empty/Fill carries custody of the essence it moves, never the runes a
        // craft produces in the same stabilized window: the essence stays neutral and the residual
        // classifies normally (owner 2026-10-13).
        if (batch.hasTransfer() && batch.note != null && batch.note.contains("essence_pouch")) {
            batch.flows.removeIf(flow -> flow.name != null && lower(flow.name).contains("essence")); batch.clear();
        }
        settleDeathWipe(batch); settleKeyTokens(batch); if (batch.flows.isEmpty()) return batch.keyAudit;
        var held = settleHeldItems(batch); if (batch.flows.isEmpty()) return held; var returned = settleReturns(batch);
        if (batch.flows.stream().allMatch(flow -> flow.value == 0
                                                && flow.source() != UNPRICED
                                                // A worthless item still moved: it books at 0
                                                // with a dash, never vanishes (owner 2026-10-16).
                                                && flow.source() != NO_VALUE)
                // Owner 2026-10-09: a clue scroll, scroll box or casket picked up is worth nothing
                // yet but is a
                // drop, so it books and HUD+ shows it; opening, stepping or moving them still books
                // nothing.
                && !(batch.flows.stream().allMatch(Flow::gain) && all(batch.flows, 0,
                                name -> matches("clue", name))))
            // A change with no value and no unpriced item is nothing to account for; a settled
            // claim still closed the deferred key audit row above. No value threshold exists.
            return first(returned, batch.keyAudit);
        var restore = settleZoneRestore(batch.flows, batch.lootItems, now);
        if (restore != null) {
            audit(restore); if (batch.flows.isEmpty()) return restore;
            // Only the matched entry items are neutral. Classify residual flows using
            // their actual consumption/loot evidence rather than the old region latch.
            batch.clear(); clearContext();
        }
        var bond = settleBond(batch.flows, now); if (bond != null && batch.flows.isEmpty()) return bond;
        for (var neutral : new Transaction[] {
                    servant(batch.flows, now), wear(batch.flows, now, batch.lootItems), cannon(batch.flows, now)
                })
            if (neutral != null) {
                audit(neutral); if (batch.flows.isEmpty()) return neutral;
            }
        if (batch.flows.isEmpty()) return null;

        var gear = wornOut(batch.flows, now); if (gear != null && batch.flows.isEmpty()) return gear;
        var sources = bookLootSources(batch);
        // Partial source matches classify the whole remaining settle as loot only
        // when the transfer partition consumed the unmatched quantities. Without
        // that partition, an oversized same-id stack (or unrelated extra gain)
        // must follow generic gain classification rather than inherit the loot tag.
        if (batch.loot.matched && !matchesExpectedLoot(batch.flows, batch.loot.items))
            batch.loot = LootMatch.NONE;
        var dropSlice = settleDropSlice(batch); var consumeSlice = settleConsumeSlice(batch); settleActions(batch);
        if (dropSlice != null && batch.flows.isEmpty() && sources.isEmpty()) return dropSlice;
        if (consumeSlice != null && batch.flows.isEmpty() && sources.isEmpty()) return consumeSlice;
        Transaction cast = settleSpend(batch, ActionReader::isRune, CAST),
            fired = settleSpend(batch, ActionReader::isAmmo, FIRE);
        if (batch.used && !batch.drop && !batch.destroy && !batch.hard && !batch.combine
                && !batch.suppress && resolve(
                                batch.intent, batch.flows, CONSUMPTION, batch.activity) == DRINK) {
            var normalized = valuation.normalizeConsumedFlows(batch.flows, DRINK, batch.now);
            batch.pricingReview = normalized == null; if (normalized != null) batch.flows = normalized;
        }
        if (cast == null) cast = settleSpell(batch); Transaction result;
        if (!sources.isEmpty() && batch.flows.isEmpty())
            result = sources.get(sources.size() - 1);
        else {
            var custody = settleCustody(batch);
            if (custody != null) result = custody;
            else result = settleBondRemoval(batch);
        }
        if (additional != null) {
            for (var row : sources)
                if (row != result) additional.accept(row);
            for (var row : new Transaction[] {cast, fired, dropSlice, consumeSlice})
                if (row != null) additional.accept(row);
        }
        return result;
    }

    /**
     * Custody owns proven sales first; only a proven Redeem or gift spends a bond, then the rest of
     * the settle books. A spend that leaves nothing else is the settle's row.
     */
    Transaction settleBondRemoval(Settle batch) {
        if (!batch.custodyMove() && !any(batch.flows, flow -> bondItem(flow.item) && flow.gain())) {
            var redeemed = matching(batch.flows, flow -> bondItem(flow.item) && flow.cost());
            if (peek(BOND_REDEEM) != null && !redeemed.isEmpty()) {
                // Buying a bond was neutral; redeeming or gifting it is the loss: what was paid for it
                // while tracked, else a bond's Grand Exchange price (the untradeable one has none).
                batch.flows.removeAll(redeemed); drop(BOND_REDEEM);
                long price = valuation.value(Map.of(OSRS_BOND, 1L)).get(0).unitPrice;
                var row = new Transaction(batch.now, CONSUMPTION, GENERIC, msg("bondSpent"), "Bonds", true,
                        list(redeemed, flow -> {
                            var pool = basis.known(flow.item); long used = -flow.quantity, known = min(used, pool.availableQty),
                                cost = sum(BasisMath.shareOf(pool.availableQty, pool.availableBasisGp, known), multiply(used - known, price));
                            return new Flow(flow.item, flow.name, flow.quantity, cost / used, -cost, GRAND_EXCHANGE, batch.now);
                        }), CONFIRMED, msg("bondSpentWhy"), null);
                add(row, false); if (batch.flows.isEmpty()) return row;
            }
            if (any(batch.flows, flow -> bondItem(flow.item))) batch.pricingReview = true;
        }
        return book(batch);
    }

    /**
     * Routine rune/ammo spends keep their own supply receipt beside a pickup, drink or each other.
     * Execution evidence names partial rune sets too. Utility spells retain their product and input legs.
     */
    Transaction settleSpend(Settle batch, Predicate<String> names, ActionKind kind) {
        if (batch.hasTransfer() || batch.death || batch.drop || batch.destroy || batch.asTransfer
                || peek(DROP_CLICK) != null
                || batch.exchangeOrTransfer() || batch.context == PRODUCTION
                || kind == FIRE && batch.intent == CAST
                || kind == CAST && (batch.label != null
                                && !product(named(batch.label.value)).isEmpty()
                        || batch.intent == CAST && any(batch.flows, flow -> flow.cost()
                                        && !isRune(flow.name) && !isAmmo(flow.name))))
            return null;
        var lost = matching(batch.flows, flow -> flow.cost() && names.test(flow.name));

        if (lost.isEmpty() || lost.size() == batch.flows.size() || kind == CAST && batch.spell == null
                        && batch.intent != CAST
                        && lost.stream().allMatch(flow -> elemental(flow.name))) return null;
        var slice = batch.slice(lost); slice.intent = kind; slice.label = batch.intent == kind ? batch.label : null;
        slice.spell = kind == CAST ? batch.spell : null; if (batch.intent == kind) batch.release();
        return book(slice);
    }

    /**
     * A spell with a product keeps only its own legs; a pickup that settled with it books on its
     * own (owner live 2026-10-16: 39 rune darts rode a Bones to Peaches tablet).
     */
    Transaction settleSpell(Settle batch) {
        var product = batch.used && batch.label != null
                        ? product(named(batch.label.value)) : emptySet();
        var legs = matching(batch.flows, flow -> flow.cost() || product.contains(flow.item));
        if (product.isEmpty() || legs.size() == batch.flows.size()) return null;
        var slice = batch.slice(legs); slice.intent = batch.intent; slice.label = batch.label; slice.used = true;
        batch.release(); return book(slice);
    }

    /**
     * A confirmed drop owns its item in a mixed settle (owner 1.3.4): dropping plant pots while a
     * calquat harvest lands books the pot loss as its own Dropped row, not Review. Outside a bank
     * every other flow must be a gain; a pickup back, a consume intent, a Market/PK context or bank
     * evidence without bank counts cancels the split.
     */
    Transaction settleDropSlice(Settle batch) {
        // Known bank counts separate a drop from deposits inside the same bank change.
        boolean bankChange = batch.hasTransfer() || batch.context == TRANSFER,
                banking = bankChange && (bank != null || !banked.isEmpty());
        if (batch.context == MARKET || batch.context == PK_DEATH || batch.context == PK_LOOT || bankChange && !banking)
            return null;
        if (peek(DROP_CLICK) == null || intent() != null) return null;
        for (Flow flow : batch.flows)
            // A pickup back, or (outside a bank) another loss or zero flow, leaves it ambiguous.
            if (dropped(flow.item) ? flow.quantity > 0 : flow.quantity <= 0 && !banking) return null;
        // Only what left both the inventory and the bank was dropped.
        var lost = claim(batch.flows, flow -> dropped(flow.item) && flow.quantity < 0,
                        (flow, size) -> min(size, positive(-flow.quantity - amount(banked, flow.item))));
        if (lost.isEmpty()) {
            if (banking) drop(DROP_CLICK);
            return null;
        }
        var slice = batch.slice(emptyList()); slice.flows = lost; settleActions(slice);
        return book(slice);
    }

    /**
     * A named consume whose bite lands with a same-item top-up (a bank withdrawal or a pickup in
     * one window): the bite moves to its own Supplies row and the top-up stays with the settle, so
     * the stack's acquisition is never counted as the bite's revenue (owner 2026-10-12, review).
     * Runs before settleActions so the remainder follows its own evidence — a hard bank batch
     * stays a neutral transfer, a loot batch keeps its row.
     */
    Transaction settleConsumeSlice(Settle batch) {
        var intent = intent();
        if (intent == null || intent.item < 0 || intent.flag || batch.death || batch.suppress) return null;
        if (batch.context == PK_DEATH) return null; int id = intent.item;
        var bites = flowList(); boolean toppedUp = false;
        for (Flow flow : batch.flows) {
            if (flow.item != id) continue; if (flow.quantity > 0) toppedUp = true;
            else if (flow.quantity < 0) bites.add(flow);
        }
        if (bites.isEmpty()) return null;
        // A pure bite (nothing else in the change) books on its own whenever the intent proved
        // it — including inside a live bank window, where the transfer context would otherwise
        // swallow it. A bite mixed with other items only splits when a same-item top-up is there
        // to keep; any other mixed window keeps its existing shape.
        boolean pure = bites.size() == batch.flows.size(); if (!pure && !toppedUp) return null;
        // The bite is a player action of its own, never part of the transfer or loot batch it
        // landed with: a fresh generic slice books it as the counted Supplies row.
        var slice = batch.slice(bites); slice.intent = intent.action; slice.label = intent.label; slice.used = true;
        // The bite owns the intent: take it so no later flow wears its verb or spell label.
        events.take(CONSUME, intent.item, 1, e -> true); return book(slice);
    }

    /** Different loot owners share a measured snapshot, never a source label or encounter. */
    List<Transaction> bookLootSources(Settle batch) {
        if (batch.loot.sources.size() <= 1) return emptyList(); var rows = new ArrayList<Transaction>();
        for (var source : batch.loot.sources) {
            var remaining = new HashMap<>(source.items);
            var parts = claim(batch.flows, Flow::gain, (flow, quantity) -> take(remaining, flow.item, quantity));
            if (parts.isEmpty()) continue; var slice = new Settle(new Clue(), batch.snapshot, batch.now);
            slice.offer(source.context, source.note, source.items, source.fight, true); slice.flows = parts; slice.loot = source;
            slice.lootItems = source.items; slice.activity = source.activityName;
            slice.claim = rows.isEmpty() ? batch.claim : null; rows.add(book(slice));
        }
        batch.loot = LootMatch.NONE; batch.lootItems = emptyMap(); batch.claim = null;
        if (batch.lootContext()) batch.generic(); return rows;
    }

    /** Commits the stabilized snapshot: its measured deltas, valued, under the pending claim. */
    Settle commit(ContainerSnapshot committed, long now) {
        var batch = new Settle(pending(), committed, now); batch.spell = cast == null ? null : cast.label;
        batch.suppress = UNCLASSIFIED_LOCAL_DEATH_NOTE.equals(batch.note);
        batch.death = batch.suppress || batch.context == PK_DEATH;
        if (batch.suppress)
            // Keep routine rune/ammo spends distinguishable from local death losses
            // without exposing an internal marker in the transaction note.
            batch.note = "";
        var changes = committed.diff(baseline); baseline = committed; invalidateChargeBaselines(changes);
        if (capture && storedBaseline != null) {
            if (batch.context == TRANSFER && batch.note != null
                    && batch.note.startsWith(msg("zoneRestored")))
                recordZoneEntry(committed.diff(storedBaseline));
            storedBaseline = committed;
        }
        clearPending();
        if (changes.isEmpty())
            // Same-tick acquire+consume (pickup then bury) nets to zero vs the prior
            // baseline. Recover a named consume when invent qty fell since intent arm.
            changes = consumeDeltas(committed);
        batch.flows = changes.isEmpty() ? flowList() : copiedList(valuation.value(changes, now));
        if (batch.context == GENERIC && !batch.hasTransfer())
            batch.flows = withoutSwaps(batch.flows);
        // The split rewrites settle.flows only; nothing after commit() re-reads deltas, so the two
        // structures may disagree by design (review 2026-10-12).
        splitToppedUpConsume(batch, changes, committed, now); return batch.flows.isEmpty() ? null : batch;
    }

    /**
     * A named consume whose stack was topped up in the same window (a bank withdrawal then an
     * immediate bite) nets positive and hides the bite. Split the stack's net gain into its gross
     * acquisition and the shortfall since the intent was armed, so the named consume keeps its own
     * cost flow and books Ate/Buried instead of vanishing into the transfer (owner 2026-10-12).
     */
    void splitToppedUpConsume(Settle batch, Map<Integer, Long> changes, ContainerSnapshot committed, long now) {
        var intent = intent(); if (intent == null || intent.item < 0 || intent.held < 0) return; int id = intent.item;
        Long net = changes.get(id); if (net == null || net <= 0) return;
        long lost = intent.held - committed.quantity(id); if (lost <= 0 || lost > net) return;
        for (int index = 0; index < batch.flows.size(); index++) {
            Flow flow = batch.flows.get(index); if (flow.item != id || flow.quantity <= 0) continue;
            long gross = flow.quantity + lost;
            batch.flows.set(index, new Flow(id, flow.name, gross, flow.unitPrice,
                            multiply(gross, flow.unitPrice), flow.source(),
                            flow.priceCapturedAtEpochMillis));
            break;
        }
        batch.flows.addAll(valuation.value(singletonMap(id, -lost), now));
    }

    /** A measured death wipe: stacks held at death that left INV stay ownership-neutral. */
    void settleDeathWipe(Settle batch) {
        boolean candidate = death.wipePending() && hasLoss(batch.flows),
            allowed = candidate && (batch.context == TRANSFER && deathNote(batch.note)
                                || batch.context == PK_DEATH && !batch.hard
                                || batch.context == GENERIC && !batch.hasTransfer());
        if (candidate && !allowed)
            // A stronger market, transfer, production or loot context owns this change.
            death.cancelWipe();
        Map<Integer, Long> losses =
                allowed ? actionLosses(batch.flows, batch.hard) : emptyMap();
        batch.lost = allowed ? death.removed(
                                without(batch.flows, losses::containsKey, false)) : emptyMap();
        if (!losses.isEmpty())
            // Consume action-attributed quantities from the potential wipe whitelist,
            // but leave unrelated held-at-death ids eligible in the same settle.
            death.excludeActionLosses(losses);
        batch.wipe = !batch.lost.isEmpty();
        batch.ambiguousCoins = batch.wipe && death.armed()
                        && hasLoss(batch.flows, id -> id == COINS)
                        && !batch.lost.containsKey(COINS);
        batch.death |= batch.wipe;
        if (batch.context == TRANSFER && deathNote(batch.note)
                && (!batch.wipe || batch.ambiguousCoins)) {
            // The short note is not ownership evidence. Without a measured whitelist
            // match, let the remaining change follow its actual action/classification.
            batch.clear(); clearTransfer();
        }
    }

    /**
     * Key tokens are provenance, not loot value: a credible pickup becomes a separate non-counted
     * audit row, and only later contents withdrawals contribute ordinary valued flows.
     */
    void settleKeyTokens(Settle batch) {
        batch.keys = losses(batch.flows, id -> forKey(id) != null);
        if (batch.death) {
            losses(batch.flows, KeyChestCatalogue::isDeferredKey).forEach(claims::consume); claims.cancelChest();
        }
        var lootKeys = gains(batch.flows, KeyClaims::lootKey); Transaction keyAudit = null;
        if (!lootKeys.isEmpty()) {
            boolean pickup = credibleKeyPickup(batch, batch.flows, lootKeys);
            batch.flows = without(batch.flows, KeyClaims::lootKey, true);
            batch.expected = withoutItems(batch.expected, KeyClaims::lootKey);
            keyAudit = pickup ? recordKeyAudit(lootKeys, batch.fight, batch.now) : null;
        }
        var claimKeys = gains(batch.flows, KeyChestCatalogue::isDeferredKey);
        boolean claimPickup = !claimKeys.isEmpty() && credibleKeyPickup(batch, batch.flows, claimKeys);
        batch.flows = without(batch.flows, KeyChestCatalogue::isDeferredKey, false);
        batch.expected = withoutItems(batch.expected, KeyChestCatalogue::isDeferredKey);
        var claimAudit = claimPickup && !batch.death ? recordClaimAudits(claimKeys, batch.now) : null;
        batch.keyAudit = claimAudit != null ? claimAudit : keyAudit;
        if (incoming(batch.flows, ANY).isEmpty())
            // The click plus measured key removal may settle a tick before the
            // chest's contents enter INV. Keep only that narrow causal evidence.
            claims.matchChest(batch.keys, emptyMap(), null);
    }

    /**
     * Held items: keys lost on death, the measured death wipe, key-token losses and charge loads.
     */
    Transaction settleHeldItems(Settle batch) {
        if (batch.death || claims.settlingDeath())
            claims.recordDeathLosses(batch.flows, KeyClaims.keys(batch.snapshot.items));
        Transaction wipe = batch.lost.isEmpty() ? null
                        : deathWipeTransfer(batch.flows, batch.lost, batch.now);
        if (batch.wipe && batch.context != PK_DEATH && (wipe != null || deathNote(batch.note))) {
            // The measured death-owned quantities have their own neutral receipt. Any
            // residual item changes in this settle must follow their own evidence.
            batch.clear(); clearTransfer();
        }
        // Key tokens are deferred provenance on both sides of the lifecycle: their
        // inventory loss is never a counted cost. Preserve the measured quantity for
        // death annotation, but exclude the token from transaction maths. A same-ID
        // loss in a mixed claim settle has no source identity, so it never reduces the
        // manifest key quantity on that evidence alone.
        batch.flows.removeIf(flow -> flow.cost() && KeyClaims.lootKey(flow.item));
        // Do not let this narrow menu intent compete with stronger bank/trade/
        // transfer evidence or the loot-key lifecycle. A stale load click expires
        // once a stronger ownership context is already active.
        if (batch.hard || batch.exchangeOrTransfer())
            drop(LOAD_TARGET);
        var load = loadTarget(batch.flows); Transaction loadReview = null;
        if (!load.unknown.isEmpty()) {
            batch.flows = copiedList(load.remaining);
            loadReview = load.variant != null && load.variant.isCommittedLoad() ? recordCommittedLoad(load, batch.now)
                            : recordAmbiguousLoad(load, batch.now);
        }
        return first(batch.keyAudit, wipe, loadReview);
    }

    /** Source-backed loot, key-chest claims and death reclaim returns. */
    Transaction settleReturns(Settle batch) {
        // Consume source-backed loot before the reclaim partitioner so a kill drop
        // sharing an item id with the death wipe remains counted as loot.
        batch.loot = !batch.exchangeOrTransfer() ? consumeQueuedLoot(batch.flows)
                        : LootMatch.NONE;
        batch.lootItems = batch.loot.items;
        var contents = batch.loot.matched ? incoming(batch.flows, ANY)
                        : minus(incoming(batch.flows, ANY), batch.lootItems);
        boolean chestContext = !batch.death && !batch.hasTransfer()
                        && !batch.exchangeOrTransfer()
                        && batch.context != PRODUCTION && (batch.loot.matched || batch.context == GENERIC)
                        // Only catalogued key losses and non-key gains: the shape of a chest open.
                        && !empty(batch.flows) && !any(batch.flows, flow -> flow.quantity != 0 && flow.cost() != (forKey(flow.item) != null));
        var claim = chestContext && !contents.isEmpty() ? claims.matchChest(batch.keys,
                                contents, batch.loot.matched ? batch.loot.activityName : null) : null;
        if (claim != null && isDeferredKey(claim.key)) {
            // Settlement and claim removal happen in this same revision. The settling row
            // carries the claim id only when the claim reached zero: a partial consume keeps
            // its durable remainder and must not look settled to a reload.
            String closed = claims.consume(claim.key, claim.quantity); batch.claim = closed != null ? closed : batch.claim;
        }
        if (claim != null && !batch.loot.matched)
            batch.offer(LOOT, claim.chest, contents, null, true);
        boolean deathTransfer = batch.context == TRANSFER && deathNote(batch.note),
            canSplitReturns = (!batch.hard || deathTransfer) && batch.context != MARKET
                        && (batch.context != TRANSFER || deathTransfer);
        return canSplitReturns ? settleReclaim(batch.flows, batch.now, batch.lootItems,
                        !batch.ambiguousCoins) : null;
    }

    /**
     * A displaced earlier spend whose item this change lost leads the settle; the newer click
     * waits for its own change (owner live 2026-10-16).
     */
    void settleActions(Settle batch) {
        var spent = peek(SPENT_CONSUME); var newer = intent();
        boolean lead = spent != null && hasLoss(batch.flows, id -> id == spent.item)
                        && (newer == null || !hasLoss(batch.flows, id -> id == newer.item));
        if (lead) {
            spent.kind = CONSUME; if (newer != null) newer.kind = SPENT_CONSUME;
        }
        settleOwnActions(batch);
        if (lead && newer != null) {
            var left = intent(); if (left != null) left.kind = SPENT_CONSUME; newer.kind = CONSUME;
        }
    }

    /** A confirmed consume or drop owns its losses; transfer evidence and loot name the rest. */
    void settleOwnActions(Settle batch) {
        var intent = intent(); var drop = peek(DROP_CLICK); batch.destroy = intent != null && intent.flag;
        // Match Drop before consume wipe — consumeMatched used to null dropIntent first.
        // The clicked item left and something else arrived: the item was taken apart.
        int dismantled = events.item(DISMANTLE_CLICK);
        batch.disassemble = !batch.hasTransfer() && dismantled > 0 && hasLoss(batch.flows, item -> item == dismantled)
                && any(batch.flows, Flow::gain);
        batch.combine = batch.disassemble || isDecant(batch.flows);
        if (batch.soft() && batch.combine)
            // A mixed shape while the bank is open has no trustworthy player-action
            // source; preserve the accounting classification without a Decanted stamp.
            batch.suppress = true;
        // A set-up's auto-load can settle together with the last part placed (the furnace), and
        // the login priming can clear the click intent between the click and the load: with the
        // parts custody active, ball losses are loads on their own. The clicked item must itself
        // be lost for the click path, or a lone part settle would eat the ball's intent (owner
        // live 2026-10-13).
        boolean cannonLoad = peek(CANNON) != null && intent == null && onlyCosts(batch.flows)
                        && hasLoss(batch.flows, Engine::cannonball)
                        && !hasLoss(batch.flows, id -> !cannonball(id) && !cannonPart(id));
        batch.drop = cannonLoad || drop != null && intent == null
                                && onlyCosts(batch.flows) && hasLoss(batch.flows, this::dropped)
                                && !hasLoss(batch.flows, id -> !dropped(id) && !cannonPart(id));
        batch.used = !batch.drop && !batch.combine && matchesConsume(batch.flows, batch.hard);
        if (batch.used && intent != null) {
            // Only a verb whose intent actually matched these flows may name the action; a stale
            // or unmatched click never upgrades the wording, and a named click names only a change
            // that moved its own item (owner 2026-10-16 sweep: a key used with an Eat armed read
            // "Ate"). Flow-level custody must never steal a quantity a named consume/drop intent
            // owns, so its item is captured here.
            boolean own = intent.item < 0 || intent.action == DRINK && all(
                                            batch.flows, 0, n -> dose(n) > 0)
                            || any(batch.flows, f -> f.item == intent.item);
            batch.intent = own ? intent.action : null; batch.label = own ? intent.label : null;
            batch.usedItem = intent.item;
        }
        batch.dropItem = batch.drop ? batch.flows.stream().filter(flow -> flow.cost()
                                        && (dropped(flow.item) || cannonball(flow.item)))
                                .mapToInt(flow -> flow.item).findFirst().orElse(-1) : -1;
        // Only a complete recipe plus production/Use evidence can outrank a partial-food shape.
        boolean producing = recognizeProduction(batch);
        if (!producing && !batch.used && !batch.drop && !batch.hard && !batch.combine
                && doseStep(batch.flows)) {
            // Dose leftovers are high-confidence even when the menu/chat intent expired
            // before the inventory stabilized (common with Drink + Pick coalescing).
            // Soft bank-open latch must not block this — only hard bank-container/menu
            // evidence (real deposit/withdraw) may.
            batch.used = true; batch.inferred = peek(DROP_CLICK) == null;
        }
        if (batch.used || batch.drop) {
            // A confirmed Eat/Bury/Drink/Drop wins over stale transfer evidence once its matching
            // inventory loss has stabilized. The Dropped/Destroyed stamps survive the loot note
            // below.
            batch.generic(); batch.note = batch.drop ? dropNote(batch.dropItem) : batch.destroy ? "Destroyed" : "";
        }
        if (batch.used) {
            events.take(CONSUME, events.item(CONSUME), 1, e -> true); drop(DROP_CLICK);
        }
        // Soft bank-open alone is a weak signal. Real deposits/withdrawals are one-sided;
        // Drink+Pick / Bury+Pick mixed windows must not become ownership-neutral TRANSFER
        // (that yields Used/lost ×0 and drops harvest rows when countUncertain is off).
        batch.asTransfer = !batch.used && !batch.drop && (batch.hard || batch.transfer
                                        && (onlyCosts(batch.flows) || oneSided(batch.flows, true)));
        if (batch.asTransfer) {
            // Soft evidence while bank open (one-sided), or hard evidence that outlived UI
            // close, must settle as ownership-neutral TRANSFER (not Used/lost).
            batch.context = TRANSFER; batch.note = empty(batch.note) ? "Bank transfer" : batch.note; batch.fight = null;
        } else if (batch.context == TRANSFER)
            // Stale soft TRANSFER (UI was open) must not reclassify pure inventory
            // consumption. Hard bank-container/menu evidence is required.
            batch.generic();
        batch.activity = current.activityHint();
        if (batch.loot.matched) {
            batch.context = batch.loot.context;
            if (!batch.drop && !(batch.used && batch.destroy))
                batch.note = batch.loot.note;
            batch.activity = batch.loot.activityName; batch.fight = batch.loot.fight;
        } else if (batch.lootContext()
                && !matchesExpectedLoot(batch.flows, batch.expected))
            batch.generic();
        else if (batch.context == LOOT)
            batch.activity = blank(batch.note) ? current == null ? GENERAL : current.activityHint()
                    : Transaction.lootSource(batch.note.trim(), batch.note.trim());
        else if (batch.context == PK_LOOT || batch.context == PK_DEATH)
            batch.activity = "PKing";
        else if (batch.context == PRODUCTION && ActionSignals.TRANSFORM.containsValue(batch.note))
            // A run names its own skill: the session hint waits for a second XP drop, so a first
            // item would carry the last activity (or none).
            batch.activity = "Smelting".equals(batch.note) ? "Smithing" : batch.note;
    }

    /**
     * Schema-104 flow-level custody: exact Flow quantities proven to be GE principal, returns or
     * collections are claimed before any whole-transaction classification. Returns the last custody
     * booking when nothing is left; residual quantities keep their own evidence.
     */
    Transaction settleCustody(Settle batch) {
        String id = current.id(); maintainMarket(batch.now);
        // Transaction-level owners (transfer/death/loot) block everything; per-item owners (a
        // named consume/drop intent or matched loot quantity) block only their own items so the
        // remaining quantities can still be claimed exactly.
        if (batch.pricingReview || batch.context == TRANSFER || batch.hard || batch.asTransfer
                || batch.death || batch.lootContext()
                || batch.context == PK_DEATH || batch.used && !batch.inferred && batch.usedItem < 0
                || batch.drop && batch.dropItem <= 0)
            return null;
        var blocked = new HashSet<Integer>(batch.loot.matched ? batch.loot.items.keySet() : emptySet());
        if (batch.usedItem >= 0) blocked.add(batch.usedItem);
        if (batch.dropItem > 0) blocked.add(batch.dropItem);
        var custody = exchange.partition(batch.flows, batch.now, id,
                        identifier -> !blocked.contains(identifier) && !(batch.drop && dropped(identifier)),
                        batch.now <= events.at(GE_COLLECT));
        if (custody.counted.isEmpty() && custody.transferBookings.isEmpty()) return null;
        // Custody claimed these exact quantities; the matching offer sides are spent so
        // stale placement/fill evidence can never own a later unrelated movement.
        exchange.spend(custody.claimedFlows, batch.now); var rows = copiedList(custody.counted);
        if (config.keepTransferAuditRows()) rows.addAll(custody.transferBookings); rows.forEach(booking -> add(booking, true));
        if (custody.residualFlows.isEmpty()) return custody.lastBooking();
        batch.flows = copiedList(custody.residualFlows); recognizeProduction(batch);
        return null;
    }

    /** Complete measured recipes may qualify again after a proven custody partition. */
    boolean recognizeProduction(Settle batch) {
        var recipe = ProductionRecipes.match(batch.flows);
        if (recipe == null || batch.used && !batch.inferred || peek(DROP_CLICK) != null || batch.drop || batch.hasTransfer()
                        || batch.combine || batch.death || batch.pricingReview || batch.loot.matched
                        || !(batch.context == PRODUCTION || batch.context == GENERIC && peek(PREPARATION) != null))
            return false;
        batch.used = false; batch.context = PRODUCTION; batch.activity = batch.note = recipe[1];
        return true;
    }

    /** Classifies what is left of the change and books it as one receipt. */
    Transaction book(Settle batch) {
        if (batch.used && raids.supplies(batch.flows, true, emptyMap())) {
            if (batch.flows.isEmpty()) return null; batch.note = msg("raidSupplyCredit");
        }
        // The offer ledger is item/direction-scoped evidence of a Grand Exchange movement. It may
        // only own a settle that no stronger source-specific evidence already owns — matched
        // loot/reward, a confirmed consume/drop action, or a loot/death context keeps its own
        // delta.
        boolean sourceProven = batch.loot.matched || batch.used || batch.drop
                        || batch.lootContext() || batch.context == PK_DEATH;
        // A recent offer may explain a movement no custody lifecycle claimed. Custody is
        // authoritative for GE economics, so this is ancillary evidence only: it fails closed to
        // an uncounted review row instead of a quote-valued cost or automatic proceeds.
        boolean explained = !sourceProven && !batch.exchangeOrTransfer()
                        && !batch.hard
                        // Proceeds reach the pack only through a Collect: without one a gain is a
                        // pickup or loot, never an uncollected sale (owner live 2026-10-16:
                        // ground coins went to Review as Grand Exchange).
                        && (hasLoss(batch.flows) || events.at(GE_COLLECT) >= batch.now)
                        && exchange.explains(batch.flows, batch.now);
        if (explained) batch.note = "Grand Exchange";
        if (explained || batch.context == MARKET)
            // The movement an offer side explains is the only one it may ever own, whether the
            // GE UI context or a recent offer supplied the evidence for this settle.
            exchange.spend(batch.flows, batch.now);
        // An explicit GE interaction is source evidence, not settlement authority. Any
        // residual flow that custody did not claim must await owner review, even when
        // the broad MARKET context would otherwise classify a coins-only TRADE.
        boolean unclaimed = batch.context == MARKET && batch.note != null
                        && batch.note.startsWith("Grand Exchange"),
            geReview = explained || unclaimed || batch.pricingReview;
        var type = classifier.classify(batch.context, batch.flows);
        boolean uncertain = !batch.asTransfer && !batch.hasTransfer() && type == UNCERTAIN,
            dismantle = batch.disassemble
                        && (events.item(DISMANTLE_CLICK) == WILD_CAVE_BRACELET_UNCHARGED
                                || events.item(DISMANTLE_CLICK) == WILD_CAVE_BRACELET_CHARGED);
        if (batch.disassemble && !dismantle) {
            // Taking an item apart (Revert, Dismantle) keeps its worth in the parts: neutral, never
            // Review (owner 1.2). What the parts do not give back is booked below. The bracelet of
            // ethereum is the exception: its 250-ether dismantle is worth more than the item, so
            // the parts rule would bury the gain; it settles like the same change without the
            // click (fail-closed Review, owner 2026-10-12).
            type = TransactionType.TRANSFER;
            for (Flow flow : batch.flows)
                if (flow.item == events.item(DISMANTLE_CLICK))
                    batch.note = "Dismantled " + flow.name;
        } else if (batch.used && !ownershipMove(type))
            // Dose leftovers / empty vials are mixed inventory deltas; confirmed
            // consume intent still books Used/lost rather than uncertain exclusion.
            type = CONSUMPTION;
        else if (!batch.asTransfer && type == UNCERTAIN && !batch.combine && (doseStep(batch.flows)
                        || batch.soft() && hasLoss(batch.flows))) {
            // The classifier still sees mixed value: a dose shape alone is enough once soft
            // transfer has been refused, and a refused soft bank latch in a mixed Drink/Pick/Bury
            // window books supplies rather than dropping costs and harvest as UNCERTAIN.
            type = CONSUMPTION; batch.used = true;
        } else if (uncertain && all(batch.flows, -1, name -> isRune(name)
                                        || isAmmo(name) || isClaim(name)))
            // A pickup settling with a routine spend (a dart thrown, a spell cast) is a gain net of
            // that
            // spend, as a loot receipt absorbs a cast; never an uncounted Review (owner
            // 2026-09-28).
            // An opened claim (a coin pouch) is income too: its neutral claim leg is not a cost
            // (owner 2026-09-29).
            type = GAIN;
        else if (uncertain && batch.combine && !dismantle) {
            // Combining or decanting keeps every dose, so it is neutral like a bank move, never a
            // Review row (owner 1.2); the doses keep their value until they are drunk. The bracelet
            // of ethereum's dismantle is not value-preserving, so it stays fail-closed Review
            // (owner 2026-10-12).
            type = TransactionType.TRANSFER; batch.note = "Decanted";
        } else if (uncertain && peek(DIALOGUE) != null) {
            // Owner 2026-10-09: coins for items while talking to an NPC is a purchase from its
            // dialogue (Elnock's jars, a ticket), booked like a shop's. Owner 1.3.4: items for
            // coins with a shop window open is a sale; the window raises the same conversation
            // signal. Owner 2026-11-03: a fixed NPC swap from npc_exchanges.tsv (Minimus takes a
            // spare quiver for 4,000 sunfire splinters) is a trade; what went out keeps its booked
            // value. Without a conversation each stays Review.
            var cash = pattern("cash").asMatchPredicate();
            String trade = all(batch.flows, -1, cash) ? "Bought" : all(batch.flows, 1, cash) ? "Sold"
                    : batch.flows.size() == 2 && any(batch.flows, out -> any(batch.flows, in ->
                            (in.item + " " + in.quantity).equals(EXCHANGES.get(out.item + " " + out.quantity))))
                    ? "Exchanged" : null;
            if (trade != null) {
                type = TRADE; batch.context = MARKET; batch.note = trade;
            }
        }
        // A drop settle is always CONSUMPTION, so picking it back up can shrink it.
        if (batch.drop && !ownershipMove(type)) type = CONSUMPTION;
        if (!batch.loot.matched && !geReview && !batch.death && oneSided(batch.flows, true)
                && (type == GAIN || type == TransactionType.LOOT || type == UNCERTAIN
                        || type == TransactionType.TRANSFER && batch.flows.stream().allMatch(
                                f -> events.peek(GROUND_CLICK, e -> e.item == f.item) != null))) {
            var picked = pickUpDrops(batch); if (picked != null && batch.flows.isEmpty()) return picked;
        }
        if (type == CONSUMPTION) {
            // Clue dig/tele/key spends while a clue path is active.
            if (clues.isClueCost(batch.note)) {
                batch.note = clues.costNote();
                if (clues.activeEncounter() != null)
                    batch.fight = clues.activeEncounter();
            }
            // Final presentation stamp after all note mutations.
            batch.note = batch.drop ? dropNote(batch.dropItem) : batch.destroy ? "Destroyed" : batch.note;
        }
        // Ancillary offer evidence with no claimable custody lifecycle fails closed to an
        // uncounted review row: never quote-valued cost, never automatic proceeds, no basis.
        boolean included = !geReview && type != TransactionType.TRANSFER
                        && (type != UNCERTAIN || config.countUncertainMixedChanges());
        type = geReview ? UNCERTAIN : type;
        boolean retainTransaction = included || config.keepTransferAuditRows() || type == UNCERTAIN;
        // The committed inventory baseline already deduplicates receipts. One encounter may
        // legitimately have several pickups; an encounter-wide revenue gate would discard them.
        batch.activity = type == TransactionType.TRANSFER ? "Transfer" : type == TRADE ? "Market" : batch.activity;
        Confidence confidence = unclaimed || batch.pricingReview ? Confidence.UNCERTAIN
                        : confidenceFor(batch.context, type);
        // Non-bank transfers explain themselves (minigame wipe, neutral zone, death).
        String reason = batch.pricingReview ? msg("consumedDose") : geReview ? msg("geMovement")
                                : type == TransactionType.TRANSFER && !empty(batch.note)
                                                && !batch.note.matches(msg("iBank"))
                                        ? msg("neutralPrefix") + batch.note + "."
                                        : explain(batch.context, type, included);
        Transaction transaction = new Transaction(batch.now, type, batch.context, batch.note, batch.activity,
                        included, batch.flows, confidence, reason, batch.fight);
        if (!batch.drop && !batch.destroy && !batch.suppress) {
            // Presentation-only evidence; type/valuation/counted above are unchanged.
            transaction.setAction(resolve(batch.intent, batch.flows, type, batch.activity));
            if (transaction.action() == CAST && batch.label != null)
                transaction.setActionLabel(batch.label);
            if (transaction.action() == CAST && batch.spell != null)
                transaction.observedSpellName = named(batch.spell.value);
        }
        if (type == CONSUMPTION && !batch.destroy && transaction.action() == null && !batch.flows.isEmpty()
                && batch.flows.stream().allMatch(flow -> flow.cost() && cannonball(flow.item)))
            // The cannon's balls are supplies wherever they leave the pack: a Fire or Load, a
            // ship-cannon shot from the pack, a manual drop, or the loss leg of a part-pickup
            // (owner 1.3.4).
            transaction.setAction(SUPPLIES);
        if (retainTransaction) {
            if (!ownershipMove(type) && !batch.hasTransfer()
                    && !batch.death) {
                // The relation is written only for a claim that actually closed, so restore's
                // settled-id check can never drop a partly consumed remainder.
                String closed = claims.settleLootKey(minus(incoming(batch.flows, ANY), batch.lootItems),
                                losses(batch.flows, KeyClaims::lootKey));
                batch.claim = closed != null ? closed : batch.claim;
            }
            if (batch.claim != null) transaction.setSourceClaimId(batch.claim);
            // A drop or a shot lands on the ground: it stays off the paid-price record until its
            // pickup window closes.
            transaction.setOnGround(included && (batch.drop && type == CONSUMPTION
                                    || transaction.action() == FIRE));
            add(transaction, false);
            if (!empty(batch.fight))
                current.attachToEncounter(transaction.id(), batch.fight, false);
        }
        if (batch.drop) for (Flow flow : batch.flows) if (flow.cost()) events.take(DROP_CLICK, flow.item, 1, e -> true);
        // Fired arrows and bolts land beside the target; picking them up (into the pack or the
        // ammo slot) shrinks the shot the same way as a drop (owner 1.3.3).
        if (transaction.onGround()) rememberDrop(transaction, Flow::cost);
        String[] lost = batch.disassemble ? DISMANTLE.get(events.item(DISMANTLE_CLICK)) : null;
        if (lost != null) add(dismantleLoss(batch.now, batch.note, fallback(current.activityHint(), GENERAL), lost), false);
        if (batch.disassemble) drop(DISMANTLE_CLICK); return transaction;
    }

    /** A loot-key manifest claim settled by this change's residual (non-source-backed) gains. */
    void observePriming(ContainerSnapshot current) {
        warmTicks++;
        if (sample != null && sample.equals(current))
            stable++;
        else {
            sample = current; stable = 1;
        }
        int stableTicks = max(2, config.stabilizationTicks() + 1),
            requiredObservedTicks = max(MINIMUM_BASELINE_WARMUP_TICKS, stableTicks);
        if (warmTicks >= requiredObservedTicks && stable >= stableTicks) {
            baseline = current; warming = false; clearPriming(); consumeContext();
        }
    }

    void captureContext() {
        // Late inventory after bank close: hard bank-container/menu evidence still applies
        // even when soft UI context ticks were cleared on close.
        if (hasHardTransfer()) latchHardTransfer(); if (clockLeft() <= 0) return;
        // Never attach TRANSFER to a new pending change from soft UI-open ticks alone.
        // Hard bank-container/menu evidence (or an already-latched hard pending) required.
        if (context().context == TRANSFER && !pending().hard && !hasHardTransfer())
            // Soft open context may exist while bankOpen(); do not latch it into
            // pending: soft evidence only counts while the UI remains open, and
            // bankClosed() clears soft-only pending context.
            return;
        // Capture the active context once when a pending change begins. Repeated
        // snapshot observations must not merge the same expected loot again.
        // New loot events still merge through aho/kw.
        if (pending().context == GENERIC || context().context.priority > pending().context.priority)
            applyContext(context().context, context().note, context().expected, context().fight);
    }

    void applyContext(Context next, String note, Map<Integer, Long> loot, String encounter) {
        pending().transfer |= next == TRANSFER && pending().hard; pending().offer(next, note, loot, encounter, false);
    }

    LootMatch consumeQueuedLoot(List<Flow> flows) {
        var available = incoming(flows, ANY); var matched = numberMap();
        var groups = new LinkedHashMap<List<Object>, LootMatch>();
        for (var e : copiedList(events.open)) {
            if (e.kind != PRIVATE_LOOT && e.kind != LOOT_RESERVE || !events.live(e)
                    || events.now > e.source().at) continue;
            var source = e.source(); long qty = events.take(e.kind, e.item, amount(available, e.item), v -> v == e);
            if (qty <= 0) continue; take(available, e.item, qty); matched.merge(e.item, qty, SafeMath::sum);
            LootMatch group = groups.computeIfAbsent(asList(source.note, source.name, source.context, source.id),
                            key -> new LootMatch(true, source.note, source.name, source.context, source.id,
                                            new HashMap<>(), emptyList()));
            group.items.merge(e.item, qty, SafeMath::sum);
        }
        // A measured gain also spends private proof after its attribution window has ended.
        available.forEach((item, qty) -> events.take(PRIVATE_LOOT, item, qty, e -> true));
        if (groups.isEmpty()) return LootMatch.NONE; var sources = copiedList(groups.values());
        var first = sources.get(0);
        return new LootMatch(true, first.note, first.activityName, first.context, first.fight,
                unmodifiableMap(matched), unmodifiableList(sources));
    }

    boolean matchesExpectedLoot(List<Flow> flows, Map<Integer, Long> loot) {
        var gains = incoming(flows, ANY);
        return !gains.isEmpty() && (empty(loot)
                        || gains.equals(positive(loot)));
    }

    Transaction recordKeyAudit(List<Flow> keys, String encounter, long now) {
        Transaction audit = bookNeutral(now, KeyClaims.LOOT_KEY_NOTE, "PKing", keys, msg("settledLoot"),
                        encounter);
        add(audit, false); var receivedKeys = gains(keys, KeyClaims::lootKey);
        // Claim identity must follow the key item, not incidental flow order. The normal valuator
        // sorts by captured value and its equal-value tie order comes from a map; either can vary
        // across equivalent observations while the audit row still represents the same keys.
        receivedKeys.sort(comparingInt(item -> item.item));
        for (int index = 0; index < receivedKeys.size(); index++) {
            Flow flow = receivedKeys.get(index);
            // One audit row can carry several key types; each independent claim needs its own
            // Deterministic identity for multi-key provenance.
            String id = receivedKeys.size() == 1 ? audit.id() : audit.id() + "#" + (index + 1);
            claims.open(id, flow.item, flow.quantity, now);
        }
        if (!empty(encounter))
            current.attachToEncounter(audit.id(), encounter, false);
        return audit;
    }

    Transaction recordClaimAudits(List<Flow> keys, long now) {
        Transaction last = null;
        for (Flow flow : keys) {
            if (flow.quantity <= 0 || !isDeferredKey(flow.item))
                continue;
            var entry = forKey(flow.item); if (entry == null || entry.tradeable) continue;
            Transaction audit = bookNeutral(now, "Key held · " + fallback(entry.chestName, "Chest"), entry.chestName,
                            singletonList(flow), msg("untradeableChest"), null);
            audit.setAction(ActionKind.DEFERRED_CLAIM); add(audit, false);
            claims.open(audit.id(), flow.item, flow.quantity, now); last = audit;
        }
        return last;
    }

    /**
     * Credible settled pickup evidence for an untradeable key token. Loot keys and deferred chest
     * keys share one rule: the settle must be a source-backed loot/death context or a plain settled
     * gain — never transfer, market or drop evidence — and a loot context must account for the
     * observed key quantity.
     */
    boolean credibleKeyPickup(Clue applied, List<Flow> originalFlows, List<Flow> keys) {
        var context = applied.context;
        if (context == null || applied.custodyMove() || peek(DROP_CLICK) != null)
            return false;
        var evidence = classifier.classify(context, originalFlows);
        if (evidence != GAIN && evidence != TransactionType.LOOT && evidence != TransactionType.PK_LOOT)
            return false;
        var keyQuantities = incoming(keys, ANY);
        if (context == PK_LOOT || context == LOOT)
            // The observed source callback must account for the actual key quantity;
            // a stale loot context or key-shaped bank flow cannot create provenance.
            return covers(applied.expected, keyQuantities);
        // A source-less but settled ordinary gain is still a pickup; transfer-like evidence and
        // production/uncertain/death mixes are rejected above or by this context check.
        return context == GENERIC && evidence == GAIN;
    }

    void resetTracking(boolean primeBaseline) {
        clearCollect(); drop(LOAD_TARGET); baseline = null; warming = primeBaseline;
        clearPriming(); clearContext(); resetHelpers();
        drop(PRIVATE_LOOT, LOOT_RESERVE, DISMANTLE_CLICK, CONSUME, SPENT_CONSUME, DROP_CLICK,
                CAST_SPELL, MANUAL_SPELL, PREPARATION, BOND_PAYMENT, BOND_REDEEM);
        events.drop(DROPPED, e -> cannonball(e.item));
    }

    static boolean groundDart(Flow flow) {
        return flow != null && flow.cost() && ChargeCheck.DARTS.containsValue(flow.item);
    }

    /**
     * A row whose pickup window closed (its memory expired or was cleared, or the plugin restarted)
     * is used up: what is left on it leaves the paid-price record now. Compaction settles its own
     * rows; an undone row waits, since redo brings it back.
     */
    void settleGround() {
        if (ground == null) {
            // Rebuilt from the saved flags after a restore: their memories did not survive it.
            ground = new HashMap<>();
            for (var session : sessions()) {
                for (var row : session.transactions()) remember(row);
                for (var undone : session.undoHistory)
                    if (!undone.restored) remember(undone.transaction);
            }
        }
        for (Iterator<Transaction> rows = ground.values().iterator(); rows.hasNext(); ) {
            var row = rows.next();
            if (!row.onGround()) {
                rows.remove(); continue;
            }
            if (events.peek(DROPPED, e -> row.id().equals(e.id)) != null || any(sessions(), session -> any(session
                            .undoHistory, record -> !record.restored && record.transaction == row))) continue;
            rows.remove();
            for (var session : sessions())
                if (session.transactions().contains(row)) session.usedUp(row);
        }
    }

    void remember(Transaction row) {
        if (row != null && row.onGround()) ground.put(row.id(), row);
    }


    /** Rows still on the ground by id; null until rebuilt after a restore. */
    transient Map<String, Transaction> ground;

    /**
     * An own drop or fired ammo stays on the ground, yours, for 3 minutes (300 ticks); balls that
     * went into any cannon for as long as it can stand, 35 minutes (dwarf part-pickups and ship
     * volleys alike).
     */
    void rememberDrop(Transaction drop, Predicate<Flow> onGround) {
        if (ground != null) remember(drop);
        for (Flow flow : drop.flows())
            if (onGround.test(flow))
                events.add(DROPPED, flow.item, Math.abs(flow.quantity), cannonball(flow.item) ? 2099 : 299, null)
                                .id = drop.id();

    }

    /** Fixed NPC swaps: "item quantity" going out to "item quantity" coming in. */
    static final Map<String, String> EXCHANGES = byName("npc_exchanges");

    /** A Drop's note; balls loaded into a cannon say so. */
    static String dropNote(int id) {
        return cannonball(id) ? "Loaded into the cannon" : "Dropped";
    }

    /**
     * Picking up an item you dropped minutes ago shrinks that Dropped row instead of booking a
     * second, matching gain: drop 3 bolts and pick 3 up, and neither row shows. Any pickup of the
     * item counts, since the Net is the same whichever bolts they are. The rest books as usual.
     */
    Transaction pickUpDrops(Settle batch) {
        var picked = flowList(); var rest = flowList();
        for (Flow flow : batch.flows) {
            long left = flow.quantity - pickUp(flow.item, flow.quantity);
            if (left < flow.quantity) picked.add(flow.part(flow.quantity - left));
            if (left > 0) rest.add(flow.rest(flow.quantity - left));
        }
        if (picked.isEmpty()) return null; batch.flows = rest;
        Transaction row = new Transaction(batch.now, TransactionType.TRANSFER, TRANSFER, "Picked back up",
                        "Transfer", false, picked, CONFIRMED, msg("pickedBackUp"), null);
        if (config.keepTransferAuditRows()) add(row, false); return row;
    }

    /**
     * Shrinks remembered drops and shots of the item by up to {@code quantity}; returns how many.
     */
    long pickUp(int id, long quantity) {
        long left = quantity;
        for (var e : events.all(DROPPED)) {
            long take = min(left, e.qty); if (e.item != id || take <= 0) continue; long given = current.pickUp(e.id, e.item, take);
            // A row that gave back less holds no more of the item: forget the rest.
            events.take(DROPPED, e.item, given < take ? e.qty : given, v -> v == e); left -= given;
        }
        return quantity - left;
    }

    /**
     * Fired darts picked up straight into the blowpipe (the game's ammo-picking setting): the shot
     * shrinks as a pickup into the pack does, and the pipe's next Check counts them as loaded, so
     * they are never taken off twice.
     */
    synchronized void pickUpIntoPipe(int id, long quantity, long now) {
        if (!live()) return; long given = pickUp(id, quantity);
        if (given > 0)
            checks.noteLoad(ChargedItem.BLOWPIPE, null, now, id, given);
    }

    /** Owner fencing clears only ephemera; persisted grave/key/custody continuity is retained. */
    synchronized void clearEphemera() {
        clearCollect(); resetHelpers(); cannonSeed = 0; death.ticks = 0;
        death.service = null;
    }

    /** The same non-inbox helpers also reset at an ordinary tracking-window boundary. */
    void stopCapture() {
        capture = false; storedBaseline = null; captureTicks = 0;
    }

    void resetHelpers() {
        bank = null; banked.clear(); estimate.clear(); checks.reset();
        stored.clear(); zoneTransfer = false; stopCapture(); restoreTicks = 0;
        clues.clear(); claims.clearEvidence();
    }

    void clearPending() {
        dirty = false; pending = null; cast = null; stableTicks = 0;
        drop(PENDING_CLAIM);
    }

    /** The priming window ends: its counters and any pending change reset together. */
    void clearPriming() {
        sample = null; warmTicks = 0; stable = 0; clearPending();
    }

    boolean matchesConsume(List<Flow> flows, boolean hardTransfer) {
        var intent = intent(); if (intent == null || empty(flows)) return false;
        // Match the spent stack even when a dose leftover / empty vial appears as a gain.
        if (intent.item >= 0 && hasLoss(flows, id -> id == intent.item))
            return true;
        // Open intent (menu item id missing) or id mismatch after canonicalize: still
        // confirm Drink/Eat when the delta is a dose/partial leftover, or a pure cost
        // (Bury/Cast runes/ammo) so hard TRANSFER cannot swallow the spend.
        if (doseStep(flows)) return true;
        if (onlyCosts(flows)) {
            // Named intent for a different item + hard bank deposit must stay TRANSFER
            // (stale bury of bones must not book oak-log deposit as Used/lost).
            return !(hardTransfer && intent.item >= 0);
        }
        // Live potato-field sequence: Drink + Pick + Bury often share one stabilization
        // window. Any armed consume intent (named or open) must win so Used/lost books
        // instead of UNCERTAIN exclusion — wrong/stale named ids previously skipped
        // this path because it required itemId < 0 only.
        // Hard bank-container/menu evidence: do not let mixed any-cost swallow a deposit.
        return !hardTransfer && hasLoss(flows);
    }

    /**
     * Pickup then bury (etc.) in one stabilize window nets to zero vs baseline. Named consume
     * intent stores invent qty at arm — synthesize the shortfall loss.
     */
    Map<Integer, Long> consumeDeltas(ContainerSnapshot current) {
        var intent = intent();
        if (intent == null || intent.item < 0 || intent.held < 0 || current == null)
            return emptyMap();
        long nowQty = current.quantity(intent.item), lost = intent.held - nowQty; if (lost <= 0) return emptyMap();
        var synthetic = numberMap(); synthetic.put(intent.item, -lost); return synthetic;
    }

    void consumeContext() {
        drop(LIVE_CLAIM, CLOCK, SOFT_TRANSFER);
        // HARD_TRANSFER survives until settle or its TTL expires, so bank-container
        // evidence is not lost on the idle GameTick before inventory dirty.
    }

    void clearContext() {
        consumeContext(); clearTransfer();
    }

    void clearTransfer() {
        drop(HARD_TRANSFER); banked.clear();
    }

    Confidence confidenceFor(Context context, TransactionType type) {
        return context == TRANSFER || context == MARKET || context == LOOT
                || context == PK_LOOT || context == PK_DEATH ? CONFIRMED : type == UNCERTAIN ? Confidence.UNCERTAIN : Confidence.LIKELY;
    }

    /** Loss ids claimed by exact consume/drop intent are excluded from the death wipe. */
    Map<Integer, Long> actionLosses(List<Flow> flows, boolean hardTransfer) {
        var excluded = numberMap();
        if (intent() == null)
            return peek(DROP_CLICK) == null ? excluded : losses(flows, this::dropped);
        if (intent().item >= 0)
            return losses(flows, id -> id == intent().item);
        // An open-id intent with only costs cannot distinguish which item was consumed; keep
        // every loss in this ambiguous batch counted.
        return addDoseLosses(excluded, flows) || !matchesConsume(flows, hardTransfer) ? excluded
                : losses(flows, ANY);
    }

    String explain(Context context, TransactionType type, boolean counted) {
        if (context == MARKET) return counted ? msg("tradeInventory") : msg("grandExchangeShop");
        if (context == PRODUCTION) return type == CONSUMPTION ? msg("failedInputs") : msg("simultaneousInputs");
        if (context == GENERIC) {
            if (type == UNCERTAIN) return counted ? msg("mixedFlows") : msg("mixedConfirmed");
            if (type == GAIN) return msg("stableValue"); return type == CONSUMPTION ? msg("stableStock") : msg("stableChange");
        }
        return text(msg("context-proof." + context.name()));
    }

    /**
     * A load whose exact quantities are ambiguous still moves value into charges: neutral by law,
     * never a cost and never an incomplete Review (owner 2026-09-29).
     */
    Transaction recordAmbiguousLoad(LoadTarget partition, long now) {
        if (partition == null || partition.unknown.isEmpty() || !live()) return null;
        Transaction transfer = bookNeutral(now, msg("chargeLoadTransfer"), "Charge load",
                        partition.unknown, msg("loadConfirmed"), null);
        transfer.setAction(CHARGE_LOAD_AMBIGUOUS); add(transfer, false);
        // The weapon's next Check adds these back, so a reload is never read as darts unused.
        for (Flow flow : partition.unknown)
            checks.noteLoad(partition.variant, partition.targetIdentity, partition.clickedAt,
                    flow.item, multiply(Math.abs(flow.quantity),
                            partition.variant.units));
        return transfer;
    }

    /**
     * A load whose components can never be refunded (crystal armour and the crystal shield): the
     * shards are committed to the piece, so they book as a counted Supplies spend now instead of a
     * neutral transfer (owner 2026-10-12). A revert keeps the seeds and loses these charges; the
     * piece keeps no Check baseline to reconcile.
     */
    Transaction recordCommittedLoad(LoadTarget partition, long now) {
        return partition == null || partition.unknown.isEmpty() || !live() ? null : bookCharge(now, msg("committedLoad") + fallback(partition.variant.getDisplayName(), "Crystal charges"),
                CONFIRMED, msg("committedProof") + partition.variant.getDisplayName() + ".",
                partition.unknown, partition.variant.action, false);
    }

    Transaction advanceContext() {
        if (dirty || pending != null) return null; if (clockLeft() <= 0) consumeContext();
        if (restoreTicks > 0 && --restoreTicks <= 0) {
            restoreTicks = 0; stored.clear();
        }
        return null;
    }

    /**
     * A measured death wipe may own this change: a death-held transfer, or an unclaimed generic
     * change.
     */
    static boolean deathNote(String note) {
        return note != null && note.startsWith("Death: items held");
    }

    synchronized Metrics metrics(long now) {
        return current == null ? Metrics.none() : current.metrics(now);
    }

    /** Correction-aware metrics of one owner; Hub v1 counts every booked row (charter D). */
    synchronized Session session() {
        return current;
    }

    synchronized Session freePlay() {
        return general;
    }

    synchronized List<Session> history() {
        return frozenList(history);
    }

    synchronized Session historySession(String id) {
        var saved = savedSession(id); return saved == null ? archive.historySession(id) : saved.session;
    }

    /** Metrics of a retained history session; null when the id is not in history. */
    synchronized Metrics historyMetrics(String id, long now) {
        return archive.historyMetrics(id, now);
    }

    synchronized boolean deleteHistory(String id) {
        if (saved.removeIf(saved -> saved.id.equals(id))) {
            changed(); return true;
        }
        return archive.deleteHistory(id);
    }

    /** Clears only GP Manager tracking state; configuration is intentionally outside the engine. */
    synchronized void resetData(long now) {
        raids.restore(null); doseRetries.clear(); history.clear(); saved.clear();
        changed(); claims.clear();
        // A reset is a new install: no session until gameplay (with Automatic tracking) or Start.
        general = null; custom = null; current = null;
        // Destructive reset clears the schema-104 custody lifecycles with the rest.
        exchange.reset(); basis.reset(); ground = null; clearCollect();
        // The cleared session's unbooked coffer drain would otherwise block the next session's.
        drain = 0;
        // Destructive reset clears the pending death/reclaim physical evidence with it.
        death.reset();
        // The looting bag's dispenser additions belong to the account that earned them.
        bag.clear(); contents.clear(); reward.clear(); drop(DROP_CLICK);
        resetTracking(true);
        // A reset is a fresh start: resume by itself shortly, if the player does nothing first.
        startAt = now + 4000;
    }

    synchronized long bestKill() {
        return current == null ? 0 : current.bestKill();
    }

    synchronized SavedState savedState() {
        settleCoffer(); var state = new SavedState(general, custom, history);
        if (!blank(profile))
            state.setOwnerKey(profile);
        state.setProfileTimeZoneId(zone); claims.writeTo(state);
        state.setPendingDeathReclaim(death.snapshot()); state.setSavedGrinds(plans);
        state.savedSessions = saved.isEmpty() ? null : copiedList(saved);
        var bag = bagStock(); state.lootingBag = bag.isEmpty() ? null : bag;
        // Schema-104 custody continuity travels in the same bounded generation as the canonical
        // settlements that depend on it.
        exchange.writeTo(state); basis.writeTo(state); state.raidHistory = raids.state.seen == 0 ? null : raids.snapshot();
        return state;
    }

    // ---- My Grinds: reusable setup metadata, never financial truth --------------------------
    /** Reusable My Grind definitions; setup and default targets only. */
    List<SavedGrind> plans = new ArrayList<>();

    List<SavedSession> saved = new ArrayList<>();

    synchronized SavedSession savedSession(String id) {
        if (id != null && id.startsWith("raid:") && (id.equals("raid:all") || raids.find(id.substring(5)) != null))
            return raids.read(id.substring(5));
        return id == null || !id.startsWith("saved:") ? null : firstMatch(saved, saved -> saved.id.equals(id));
    }

    synchronized boolean sameLiveWindow(Session expected, Long start) {
        return expected!=null && current==expected && Objects.equals(start,expected.sessionStartAt);
    }

    /** Freeze the current Live window without creating another counted owner of its receipts. */
    synchronized SavedSession captureLiveSession(Session expected, Long start, long now) {
        if (!sameLiveWindow(expected,start))
            return null;
        long elapsed = expected.liveElapsed(now); var copy = copy(expected);
        if (start != null) {
            copy.transactions = copy.rowsSince(start);
            copy.retainedRevenue = copy.retainedCosts = copy.retainedSuppliesCosts = copy.compactedTransactionCount = 0;
            copy.retainedCostSplitComplete = true; copy.startedAt = start;
        }
        copy.rename(custom == null ? "Free play session" : expected.name());
        copy.savedActiveMillis = elapsed; copy.endedAt = now;
        copy.paused = false; copy.totalPausedMillis = positive(now - copy.startedAt - elapsed);
        copy.sessionStartAt = copy.rateStartAt = copy.savedRateMillis = copy.restoredRateMillis = null;
        copy.profitTargetGp = copy.activeTimeTargetMillis = null; copy.undoHistory.clear(); copy.correctionHistory.clear();
        var markets = MarketRows.window(copy, marketRows(), copy.transactions());
        return new SavedSession("saved:" + uuid(), copy, markets);
    }

    synchronized String saveLiveSession(Session expected, Long start, SavedSession copy) {
        if (!sameLiveWindow(expected,start)
                || copy == null || saved.contains(copy)) return null;
        saved.add(0, copy); while (saved.size() > max(1, config.maxHistorySessions())) saved.remove(saved.size() - 1);
        changed(); return copy.id;
    }

    /** All My Grinds; archived ones only when asked for. */
    synchronized List<SavedGrind> plans(boolean includeArchived) {
        return matching(plans, plan -> !plan.archived || includeArchived);
    }

    /** Saved-Grind lookup by stable id; null when unknown. */
    synchronized SavedGrind plan(String id) {
        return firstMatch(plans, plan -> plan.id().equals(id));
    }

    /** Creates a reusable My Grind from reusable setup only; runs link to it as they start. */
    synchronized SavedGrind saveGrind(String name, Long profit, Long active, boolean favorite) {
        String clean = fallback(name, ""); if (clean.isEmpty()) return null;
        var plan = new SavedGrind(uuid(), clean, profit, active, favorite); plans.add(plan); return plan;
    }

    /**
     * Edits a My Grind. Finished runs keep the targets they ran with; a running run of this Grind
     * takes the new name and any changed target at once (owner 1.5.0: one Grind, one setup).
     */
    synchronized boolean updateSavedGrind(String id, String name, Long profit, Long active,
            boolean favorite) {
        var plan = plan(id); if (plan == null) return false; String clean = fallback(name, "");
        // Only a changed target reaches the run: a rename or a favourite never re-arms its aim.
        boolean retarget = !Objects.equals(profit, plan.netTargetGp)
                        || !Objects.equals(active, plan.activeTimeTargetMillis);
        if (!clean.isEmpty()) plan.setName(clean); plan.setNetTargetGp(profit); plan.setActiveTimeTargetMillis(active);
        plan.setFavorite(favorite); var run = custom;
        if (run != null && id.equals(run.grindId())) {
            if (!clean.isEmpty()) renameActive(clean); if (retarget) setTargets(profit, active, now());
        }
        return true;
    }

    /** The My Grind with this name, ignoring case; null when there is none. */
    synchronized SavedGrind grindNamed(String name) {
        return firstMatch(plans, plan -> plan.name().equalsIgnoreCase(fallback(name, "").trim()));
    }

    synchronized boolean archiveGrind(String id, boolean archived) {
        var plan = plan(id); if (plan == null) return false; plan.setArchived(archived);
        return true;
    }

    /** Removes the reusable definition only; canonical history and lineage links survive. */
    synchronized boolean deleteSavedGrind(String id) {
        var plan = plan(id); if (plan == null) return false; plans.remove(plan);
        return true;
    }

    /** Stable-id lineage members, newest first. Never display-name matching. */
    synchronized List<Session> linkedSessions(String id) {
        var result = new ArrayList<Session>(); if (empty(id)) return result; var active = current;
        if (active != null && id.equals(active.grindId())) result.add(active);
        for (var session : history)
            if (session != null && id.equals(session.grindId())) result.add(session);

        return result;
    }

    /** Stable-id lineage members among completed instances only, newest first. */
    synchronized List<Session> completedLinked(String id) {
        return matching(linkedSessions(id), session -> session != null && session.isClosed());
    }

    /**
     * Starts one fresh canonical Grind. Every Grind is remembered (owner 1.5.0): the run links to
     * its My Grind, found by id, else by name, else created, and the targets it starts with become
     * that Grind's targets. No financial state, loot, active time or correction state is copied.
     */
    synchronized boolean startGrind(
            String name, String id, Long profit, Long activeTarget, long now) {
        String clean = fallback(name, ""); if (clean.isEmpty() || custom != null) return false;
        var plan = plan(id); if (plan == null) plan = grindNamed(clean);
        if (plan == null) plan = saveGrind(clean, null, null, false); plan.setArchived(false); plan.setNetTargetGp(profit);
        plan.setActiveTimeTargetMillis(activeTarget); startCustom(plan.name(), now);
        var active = current; if (active == null) return false; active.setGrindId(plan.id()); active.setNetTarget(profit, 0, 0);
        active.setTimeTarget(activeTarget, now); return true;
    }

    /**
     * Renames the running Grind; a change like any other, so the revision advances and it is saved.
     */
    synchronized void renameActive(String name) {
        if (current != null) {
            current.rename(name); changed();
        }
    }

    /** Mid-Grind aim edit: changes the current target snapshot only, never financial truth. */
    synchronized boolean setTargets(Long profit, Long active, long now) {
        if (current == null) return false;
        // Both setters drop a zero or negative aim themselves.
        boolean net = current.setNetTarget(profit, metrics(now).net, current.elapsed(now)),
            time = current.setTimeTarget(active, now);
        // A new or changed target starts a session; re-saving one keeps its count, and Done or
        // Clear leaves the session counting (owner 1.5.2).
        if ((net || time) && (current.targetGp() != null
                        || current.timeTargetMillis() != null)) newLive(now);
        return true;
    }

    /**
     * The Targets sheet's save: the run's targets change and, during a Grind, so do that Grind's,
     * so the next run starts with them (owner 1.5.0). Done clears the run's aim only.
     */
    synchronized boolean saveTargets(Long profit, Long active, long now) {
        if (!setTargets(profit, active, now)) return false; var plan = custom == null ? null : plan(custom.grindId());
        if (plan != null) {
            plan.setNetTargetGp(current.targetGp()); plan.setActiveTimeTargetMillis(current.timeTargetMillis());
        }
        return true;
    }

    /** Again: a new session with the same target, in the same run (owner 1.5.2). */
    synchronized boolean restartTarget(long now) {
        if (current == null || current.targetGp() == null
                        && current.timeTargetMillis() == null) return false;
        newLive(now); return true;
    }

    /** Toggles the timer's own pause; the run keeps tracking (owner live 2026-10-14). */
    synchronized boolean toggleTimeTargetPause(long now) {
        return !(current == null || current.timeTargetMillis() == null) && (current.pauseTimeTarget(now) || current.resumeTimeTarget(now));
    }

    /** Every session the engine opens follows the detected activity (AUTO). */
    Session newSession(String name, long now, boolean plan) {
        var session = new Session(name, now, SessionMode.AUTO); session.grind = plan; attachListeners(session); changed();
        return session;
    }

    void attachListeners(Session session) {
        if (session != null) {
            session.changeListener = this::changed; session.ownedValueObserver = basis;
        }
    }

    void changed() {
        revision = revision == Long.MAX_VALUE ? 1 : revision + 1;
    }

    static boolean validZone(String value) {
        if (blank(value)) return false;
        try {
            of(value); return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * One context claim on the next inventory change: what it is, why, which loot, which encounter.
     */
    static class Clue {
        Context context = GENERIC; String note = "";
        Map<Integer, Long> expected = emptyMap(); String fight;

        /**
         * Bank/deposit evidence during this change; {@code hard} is a real container/menu movement.
         */
        boolean transfer,

            hard;

        boolean hasTransfer() { return hard || transfer; }

        boolean lootContext() { return context == LOOT || context == PK_LOOT; }

        boolean exchangeOrTransfer() { return context == TRANSFER || context == MARKET; }

        /** Bank evidence or a transfer/Market context: ownership stays with its custodian. */
        boolean custodyMove() { return hasTransfer() || exchangeOrTransfer(); }

        /** Soft evidence: the bank UI was open, but no deposit or withdrawal was confirmed. */
        boolean soft() {
            return transfer && !hard;
        }

        /** The claim gives way to ordinary classification; transfer evidence is kept. */
        void generic() {
            context = GENERIC; note = ""; expected = emptyMap(); fight = null;
        }

        void clear() {
            generic(); transfer = false; hard = false;
        }

        /**
         * A higher-priority (or forced) claim replaces this one; the same context merges its
         * details.
         */
        void offer(Context claim, String claimNote, Map<Integer, Long> loot, String encounter, boolean replace) {
            if (replace || claim.priority > context.priority) {
                context = claim; note = string(claimNote); expected = positive(loot); fight = encounter;
            } else if (claim == context) {
                note = empty(claimNote) ? note : claimNote;
                if (claim == LOOT || claim == PK_LOOT) {
                    var merged = positive(loot); expected.forEach((id, quantity) -> merged.merge(id, quantity, SafeMath::sum));
                    expected = merged;
                }
                fight = empty(encounter) ? fight : encounter;
            }
        }
    }
}

/** Source-backed quantities and their distinct source/encounter owners, in observation order. */
@AllArgsConstructor
class LootMatch {
    static final LootMatch NONE = new LootMatch(false, "", GENERAL, GENERIC, null,
                    emptyMap(), emptyList());
    boolean matched; String note, activityName;
    Context context; String fight;
    Map<Integer, Long> items; List<LootMatch> sources;
}

@Singleton
class TransactionClassifier {
    TransactionType classify(Context context, List<Flow> flows) {
        if (context == TRANSFER) return TransactionType.TRANSFER; boolean gain = false, hasCost = false;
        for (Flow flow : flows) {
            // Direction comes from quantity. An unavailable unit price produces a
            // zero value, but it is still a real gain or cost that belongs in Review.
            gain |= flow.gain(); hasCost |= flow.cost();
        }
        if (context == MARKET) return TRADE; if (context == PK_LOOT && gain) return TransactionType.PK_LOOT;
        if (context == PK_DEATH && hasCost) return PK_DEATH_LOSS; if (context == LOOT && gain) return TransactionType.LOOT;
        if (gain && hasCost)
            return context == PRODUCTION ? PROCESSING : UNCERTAIN;
        return gain ? GAIN : hasCost ? CONSUMPTION : ADJUSTMENT;
    }
}

/**
 * Pending evidence inbox:
 * <ul>
 * <li>FE fee, CH chest, TK ground click, BG bag gain, KT kit snapshot.
 * <li>DI dismantle click, GE collect interaction, TA fired-ammo pickup intent.
 * <li>DO drop click, DROPPED an own drop still on the ground, FA recoverable fired-ammo loss.
 * <li>CN a consume command, including its named inventory baseline and presentation hints.
 * <li>SC an earlier consume whose item already left the pack, displaced by a newer click.
 * <li>LT private loot (at bounds attribution); LP/RD quantities reserved by an own-pile despawn.
 * <li>CL load target, CK exact Check link, RV uncharge return; command quantities never prove money.
 * <li>CX/CP live/stabilizing claims, CT attribution clock, SF/HF soft/hard transfer, BO bank UI.
 * </ul>
 */
class Inbox {
    static final int FEE = 1, CHEST = 2, GROUND_CLICK = 3, BAG_GAIN = 4, KIT_SNAPSHOT = 5, DISMANTLE_CLICK = 6,
            GE_COLLECT = 7, BAG_STORE = 8, DROP_CLICK = 9, DROPPED = 10, CONSUME = 12, PRIVATE_LOOT = 13, LOOT_RESERVE = 14,
            LOAD_TARGET = 16, CHECK_LINK = 17, UNCHARGE = 18, LIVE_CLAIM = 19, PENDING_CLAIM = 20, CLOCK = 21,
            SOFT_TRANSFER = 22, HARD_TRANSFER = 23, BANK_UI = 24, SERVANT = 25, SAWMILL = 26, DIALOGUE = 27,
            PIPE_PICKUP = 28, CANNON = 29, SPENT_CONSUME = 30, CAST_SPELL = 31, MANUAL_SPELL = 32, LOAN = 33, RAID_STORE = 34,
            PREPARATION = 35, BOND_PAYMENT = 36, BOND_REDEEM = 37;
    volatile long owner;

    /** Legacy lifecycle ephemera clear here; their durable state keeps its tested lifecycle. */
    Runnable reset = () -> {};

    int now;

    /** Proved context stays latched while its measured snapshot stabilizes, as in 1.2. */
    boolean hold;

    List<Observation> open = new ArrayList<>();

    synchronized Observation add(int kind, int item, long qty, int ticks, String note) {
        var e = new Observation(); e.kind = kind; e.owner = owner; e.item = item;
        e.qty = qty; e.until = (int) min(Integer.MAX_VALUE, (long) now + ticks); e.note = note; open.add(e);
        return e;
    }

    /** Replace a single observation, such as the latest click or chat confirmation. */
    synchronized Observation put(int kind, int item, long qty, int ticks, String note) {
        drop(kind); return add(kind, item, qty, ticks, note);
    }

    int item(int kind) {
        var e = peek(kind); return e == null ? -1 : e.item;
    }

    long at(int kind) {
        var e = peek(kind); return e == null ? 0 : e.at;
    }


    Engine.Clue clue(int kind) {
        var e = peek(kind);
        if (e == null) {
            e = add(kind, -1, 1, Integer.MAX_VALUE, null); e.data = new Engine.Clue();
        }
        return (Engine.Clue) e.data;
    }

    int left(int kind) {
        var e = peek(kind); return e == null ? 0 : e.until - now + 1;
    }

    void ttl(int kind, int ticks) {
        if (ticks <= 0) drop(kind); else put(kind, -1, 1, ticks - 1, null);
    }

    synchronized Observation peek(int kind, Predicate<Observation> match) {
        return firstMatch(open, e -> e.kind == kind && live(e) && match.test(e));
    }

    Observation peek(int kind) {
        return peek(kind, e -> true);
    }

    /** Consume no more than the still-open proof, even when a caller retains a stale record. */
    synchronized long take(int kind, int item, long qty, Predicate<Observation> match) {
        long left = positive(qty);
        for (var e : open) {
            if (e.kind != kind || e.item != item || !live(e) || !match.test(e)) continue;
            long used = min(left, e.qty); e.qty -= used; left -= used;
            if (left == 0) break;
        }
        open.removeIf(e -> !live(e)); return positive(qty) - left;
    }

    boolean live(Observation e) {
        return e.owner == owner && e.qty > 0 && now <= e.until;
    }

    /**
     * Reserve proven private quantity for the bag, retaining its original source for settlement.
     */
    synchronized long reserve(int kind, int item, long qty, int into) {
        long unit = item == PLATINUM ? 1000 : 1;
        if (unit > 1) {
            item = COINS; long available = 0;
            for (var e : all(kind)) if (e.item == item) available = sum(available, e.qty);
            qty = min(multiply(qty, unit), available) / unit * unit;
        }
        long left = positive(qty);
        for (var e : all(kind)) {
            if (e.item != item) continue; int index = open.indexOf(e);
            long used = take(kind, item, left, v -> v == e);
            if (used > 0) {
                var part = add(into, item, used, e.until - now, e.note); part.data = e;
                open.remove(part); open.add(min(index, open.size()), part);
            }
            left -= used; if (left == 0) break;
        }
        return (positive(qty) - left) / unit;
    }

    synchronized List<Observation> all(int kind) {
        return matching(open, e -> e.kind == kind && live(e));
    }

    void refresh(int kind, int ticks) {
        for (var e : all(kind)) e.until = max(e.until, now + max(1, ticks) - 1);
    }

    synchronized void drop(int... kinds) {
        for (int kind : kinds) open.removeIf(e -> e.kind == kind);
    }

    synchronized void drop(int kind, Predicate<Observation> keep) {
        open.removeIf(e -> e.kind == kind && !keep.test(e));
    }

    synchronized void tick(int tick) {
        if (hold)
            for (var e : open)
                if (e.kind >= CLOCK && e.kind <= HARD_TRANSFER && e.until < Integer.MAX_VALUE)
                    e.until += tick - now;
        now = tick; open.removeIf(e -> !live(e));
    }

    /**
     * Sole account boundary; called with Persistence's existing request generation, including A ->
     * B -> A.
     */
    void owner(long token) {
        synchronized (this) {
            owner = token; open.clear();
        }
        // Never take the engine monitor while holding the inbox monitor.
        reset.run();
    }
}

/** One owner-scoped observation. Quantity is spent once; until is an inclusive inbox tick. */
class Observation {
    int kind, item, x, y, until; long owner, qty;

    /** Source tick/time and inventory quantity at the observation. */
    long at, held;

    /** Receipt or physical target identity, independent of the human source label. */
    String id,

        note;

    /** Typed source snapshot (kit contents); never persisted. */
    Object data;

    /** Consume command metadata: Destroy, verb, trusted spell label. */
    boolean flag;

    ActionKind action; ActionLabel label;

    /** Loot source context and activity; id is its encounter. */
    ChargedItem variant;

    Context context; String name;

    Observation source() {
        return data instanceof Observation ? ((Observation) data).source() : this;
    }
}

/** Ordered mutable selections and null-tolerant predicates/projections over settled item flows. */
@UtilityClass
class FlowFilters {
    /** The first match, including a matched null; preserve the source order. */
    <T> T firstMatch(Iterable<T> values, Predicate<? super T> match) {
        for (T value : values) if (match.test(value)) return value;
        return null;
    }

    /** Either raw id of an Old school bond (tradeable or untradeable). */
    boolean bondItem(int item) {
        return item == OSRS_BOND || item == OSRS_BOND_UNTRADEABLE;
    }

    /** Whether any value matches; a null source fails just as its stream would. */
    <T> boolean any(Collection<T> values, Predicate<? super T> match) {
        return values.stream().anyMatch(match);
    }

    /** Exactly one match, stopping at the second just as the original catalogue loops did. */
    <T> T onlyMatch(Iterable<T> values, Predicate<? super T> match) {
        T found = null;
        for (T value : values) if (match.test(value)) {
            if (found != null) return null; found = value;
        }
        return found;
    }

    Long amount(Map<?, Long> counts, Object item) {
        return counts.getOrDefault(item, 0L);
    }

    /** A fresh mutable selection, in source order; null sources are empty. */
    <T> List<T> matching(Collection<T> source, Predicate<? super T> include) {
        var selected = new ArrayList<T>();
        if (source != null)
            for (var value : source)
                if (include.test(value)) selected.add(value);
        return selected;
    }

    final IntPredicate ANY = id -> true;

    /** Positive flows whose item matches. */
    List<Flow> gains(List<Flow> flows, IntPredicate item) {
        return matching(flows, flow -> flow.quantity > 0 && item.test(flow.item));
    }

    /** Flows minus those whose item matches (positive-only when {@code gainsOnly}). */
    List<Flow> without(List<Flow> flows, IntPredicate item, boolean gainsOnly) {
        return flows == null ? emptyList() : matching(flows, flow -> !item.test(flow.item) || gainsOnly && flow.quantity <= 0);
    }

    /** Positive entries of {@code quantities} whose item does not match. */
    Map<Integer, Long> withoutItems(Map<Integer, Long> counts, IntPredicate item) {
        var out = quantities(counts, id -> !item.test(id)); return out.isEmpty() ? emptyMap() : out;
    }

    /** Positive entries only, in a fresh mutable map. */
    Map<Integer, Long> positive(Map<Integer, Long> counts) {
        return quantities(counts, ANY);
    }

    /** Accounting cash units; physical container stock remains in tokens for display. */
    Map<Integer, Long> cash(Map<Integer, Long> items) {
        var cash = positive(items); Long tokens = cash.remove(PLATINUM);
        if (tokens != null) cash.merge(COINS, multiply(tokens, 1000), SafeMath::sum); return cash;
    }

    Map<Integer, Long> quantities(Map<Integer, Long> counts, IntPredicate item) {
        var out = numberMap();
        if (counts != null)
            for (var e : counts.entrySet())
                if (e.getKey() != null && e.getValue() != null && e.getValue() > 0 && item.test(e.getKey()))
                    out.put(e.getKey(), e.getValue());
        return out;
    }

    /**
     * The shared settle-later claim: offers each matching flow's magnitude to {@code claimer},
     * which returns how much its lifecycle takes, leaves only the unclaimed rest in {@code flows}
     * and returns the claimed parts.
     */
    List<Flow> claim(List<Flow> flows, Predicate<Flow> which, ToLongBiFunction<Flow, Long> claimer) {
        var parts = flowList();
        for (int index = 0; index < flows.size(); index++) {
            Flow flow = flows.get(index); if (flow.quantity == Long.MIN_VALUE || !which.test(flow)) continue;
            long taken = claimer.applyAsLong(flow, abs(flow.quantity)); if (taken > 0) parts.add(flow.part(taken));
            Flow rest = flow.rest(taken); if (rest == null) flows.remove(index--); else flows.set(index, rest);
        }
        return parts;
    }

    /** Positive remainders of {@code a} minus {@code b}: what left between two observations. */
    Map<Integer, Long> minus(Map<Integer, Long> base, Map<Integer, Long> subtract) {
        var out = orderedNumberMap();
        if (base != null)
            for (var e : base.entrySet()) {
                Long less = subtract == null ? null : subtract.get(e.getKey());
                long left = (e.getValue() == null ? 0 : e.getValue()) - (less == null ? 0 : SafeMath.positive(less));
                if (e.getKey() != null && left > 0) out.put(e.getKey(), left);
            }

        return out;
    }

    /** True when every wanted quantity fits inside {@code available}. */
    boolean covers(Map<Integer, Long> available, Map<Integer, Long> wanted) {
        return !empty(wanted) && !empty(available) && !any(wanted.entrySet(), e -> e.getKey() == null || e.getValue() == null
                        || e.getValue() <= 0 || amount(available, e.getKey()) < e.getValue());
    }

    /** Removes up to {@code max} of one item and returns the quantity taken. */
    long take(Map<Integer, Long> counts, int id, long max) {
        if (counts == null || max <= 0) return 0; long held = amount(counts, id), taken = min(SafeMath.positive(held), max);
        if (held - taken <= 0) counts.remove(id); else counts.put(id, held - taken);
        return taken;
    }

    /** Summed positive quantities per matching item. */
    Map<Integer, Long> incoming(List<Flow> flows, IntPredicate item) {
        return quantities(flows, item, true);
    }

    /** Summed magnitudes of negative deltas per matching item. */
    Map<Integer, Long> losses(List<Flow> flows, IntPredicate item) {
        return quantities(flows, item, false);
    }

    Map<Integer, Long> quantities(List<Flow> flows, IntPredicate item, boolean income) {
        var out = numberMap();
        if (flows != null)
            for (Flow flow : flows) {
                if (!item.test(flow.item)) continue; long delta = flow.quantity;
                if (income ? delta > 0 : delta < 0) {
                    long size = abs(delta); out.merge(flow.item, size, SafeMath::sum);
                }
            }

        return out;
    }

    /**
     * Removes same-variant charge-state swaps (full -> partial -> uncharged): one physical weapon
     * changing item id is neither a gain nor a loss. A 1:1 proxy pair (X and X(i)/(ei)/(ri)) is the
     * same item changing state too, so an imbue never books a loss and a gain (owner 2026-10-12).
     * Leftover flows keep their own rows.
     */
    List<Flow> withoutSwaps(List<Flow> flows) {
        boolean[] drop = new boolean[flows.size()];
        for (int i = 0; i < flows.size(); i++) {
            Flow cost = flows.get(i); if (cost == null || cost.quantity >= 0) continue;
            var variant = ChargeCheck.supported(cost.item);
            for (int j = 0; j < flows.size(); j++) {
                Flow gain = flows.get(j);
                if (drop[j] || gain == null || gain.quantity <= 0 || gain.item == cost.item
                        || Math.abs(gain.quantity) != Math.abs(cost.quantity))
                    continue;
                if (variant != null && ChargeCheck.supported(gain.item) == variant
                        || reducedBase(cost.item) == reducedBase(gain.item)) {
                    drop[i] = true; drop[j] = true; break;
                }
            }
        }
        var result = flowList();
        for (int i = 0; i < flows.size(); i++)
            if (!drop[i]) result.add(flows.get(i));

        return result;
    }

    /** The item's 1:1 proxy base (an imbued variant reduces to its plain item), else itself. */
    int reducedBase(int id) {
        int[] proxy = PROXIES.get(id); return proxy != null && proxy[1] == 1 ? proxy[0] : id;
    }

    boolean hasLoss(List<Flow> flows) {
        return hasLoss(flows, ANY);
    }

    boolean hasLoss(List<Flow> flows, IntPredicate item) {
        return flows != null && any(flows, flow -> flow.quantity < 0 && item.test(flow.item));
    }

    /** True when every non-null flow is on one side and at least one is non-zero. */

    boolean onlyCosts(List<Flow> flows) {
        return oneSided(flows, false);
    }

    boolean oneSided(List<Flow> flows, boolean income) {
        if (empty(flows)) return false; boolean saw = false;
        for (Flow flow : flows) {
            long delta = flow.quantity; if (income ? delta < 0 : delta > 0) return false; saw |= delta != 0;
        }
        return saw;
    }
}

/**
 * PvM death → reclaim lifecycle. A separate held-stack snapshot is intersected with measured
 * negative inventory flows to establish which items actually left the player. Those exact
 * quantities remain ownership-neutral through gravestone expiry and Death’s Office retrieval.
 *
 * <p>Fees are never inferred here; the engine can book only an observed carried-coin loss. A timer
 * expiry records an informational audit event and never creates an item loss.
 *
 * <p>Pure tick-counted state; the engine owns the single instance.
 */
class DeathReclaimLifecycle {
    /** Ticks a gravestone retains items. */
    static final int AWAIT_TICKS = 1500; // 15 minutes

    /** Keep a grave interaction armed through the usual open/confirm/claim sequence. */
    static final int MIN_RECLAIM_ARM_TICKS = 200; // 2 minutes

    /** Bound delayed death-wipe reconciliation so later ordinary losses cannot match it. */
    static final int DEATH_WIPE_WINDOW_TICKS = 100; // 1 minute

    @Getter(AccessLevel.PACKAGE)
    boolean awaitingReclaim;

    int wipe, age, ticks; Service service;

    /** Stacks held at death not yet matched to a measured loss; null when no wipe is pending. */
    Map<Integer, Long> held;

    /** Measured grave contents still outstanding, with the names used by the expiry note. */
    Map<Integer, Long> items = numberMap();

    Map<Integer, String> names = new HashMap<>();

    /** A Hardcore World deletes this death's grave: nothing it measures can be reclaimed (owner 2026-11-07). */
    boolean deleted;

    /** Local unsafe PvM death: the snapshot is ownership evidence, not presentation evidence. */
    void onPvmDeath(Map<Integer, Long> heldItems, boolean deleted) {
        this.deleted = deleted;
        // Another classified death cannot destroy proven ownership by itself. Preserve
        // outstanding grave/Death’s Office quantities and restart the grave timer.
        awaitingReclaim = true;
        // Missing canonical evidence is not permission to neutralize arbitrary losses.
        var held = positive(heldItems); held.keySet().removeIf(MinigameItems::isMinigameOnly);
        this.held = held.isEmpty() ? null : held; wipe = this.held == null ? 0 : DEATH_WIPE_WINDOW_TICKS; age = 0;
        ticks = 0; service = null; completeIfEmpty();
    }

    /** True while waiting for the measured death inventory/equipment removal. */
    boolean wipePending() {
        return awaitingReclaim && held != null;
    }

    /** Stop the short death-wipe whitelist when stronger later evidence owns the loss. */
    void cancelWipe() {
        held = null; wipe = 0; completeIfEmpty();
    }

    /** Consume death-time candidates whose later loss is explicitly action-attributed. */
    void excludeActionLosses(Map<Integer, Long> losses) {
        if (held == null || empty(losses)) return; positive(losses).forEach((id, lost) -> take(held, id, lost)); finishWipe();
    }

    /**
     * Intersect a settled negative-flow batch with the stacks held at death. The returned
     * quantities identify the exact negative flow portions that may be labelled as the
     * ownership-neutral death transfer.
     */
    Map<Integer, Long> removed(List<Flow> flows) {
        if (!wipePending()) return emptyMap(); var removed = numberMap();
        for (Flow flow : flows == null ? List.<Flow>of() : flows) {
            if (flow.quantity >= 0 || flow.quantity == Long.MIN_VALUE
                    // A live retrieval interaction makes measured coin loss a possible
                    // fee; do not consume it as a delayed death wipe.
                    || flow.item == COINS && armed())
                continue;
            long matched = take(held, flow.item, -flow.quantity); if (matched <= 0) continue; removed.put(flow.item, matched);
            // Keep measured cash ownership too; the armed-service guard above protects fees.
            if (!deleted && !Engine.destroyedBag(flow.item)) {
                items.merge(flow.item, matched, SafeMath::sum);
                if (!blank(flow.name))
                    names.put(flow.item, flow.name.trim());
            }
        }
        finishWipe(); return removed.isEmpty() ? emptyMap() : unmodifiableMap(removed);
    }

    /**
     * Match a positive return against the still-outstanding measured death quantity. Returns zero
     * when the item was not part of the actual settled death wipe.
     */
    long matchReturn(int id, long quantity) {
        long matched = take(items, id, quantity);
        if (matched > 0 && items.isEmpty())
            // All measured grave contents have returned. Remaining stacks from the
            // death-time snapshot were kept and can no longer be a delayed wipe.
            cancelWipe();
        return matched;
    }

    long outstanding() {
        long count = 0; for (long quantity : items.values()) count = sum(count, quantity); return count;
    }

    /**
     * Player interacted with a retrieval service. A fresh Grave / Gravestone click can always
     * re-arm while this local death remains active.
     *
     * @return true only when a local PvM death is awaiting reclaim
     */
    boolean noteReclaimIntent(Service service, int ticks) {
        if (service == null || !awaitingReclaim && items.isEmpty()) return false;
        this.service = service; this.ticks = max(MIN_RECLAIM_ARM_TICKS, ticks); return true;
    }

    boolean armed() {
        return ticks > 0 && service != null;
    }

    /** One inventory/game tick; defers gravestone expiry while a snapshot is unsettled. */
    String tick(boolean deferExpiry) {
        if (awaitingReclaim && age < Integer.MAX_VALUE) age++; if (ticks > 0 && --ticks == 0) service = null;
        if (wipe > 0 && --wipe == 0) cancelWipe(); if (deferExpiry || !awaitingReclaim || age < AWAIT_TICKS) return null;
        String expired = items.isEmpty() ? null : expiredItems();
        // Expired grave items wait at Death's Office for good: still yours, so they stay
        // outstanding
        // and match only while a retrieval is armed (review 2026-10-09).
        awaitingReclaim = false; cancelWipe(); return expired;
    }

    /** The expiry audit explanation: the outstanding items, sorted by name. */
    String expiredItems() {
        var labels = new ArrayList<String>();
        items.forEach((id, quantity) -> {
                    String name = names.getOrDefault(id, "item #" + id);
                    labels.add(quantity == 1 ? name : name + " ×" + exact(quantity));
                });
        labels.sort(String.CASE_INSENSITIVE_ORDER);
        return msg("gravestoneTimer") + outstanding() + msg("itemS") + String.join(", ", labels)
                + msg("itemLoss");
    }

    void reset() {
        awaitingReclaim = false; wipe = 0; age = 0; ticks = 0;
        service = null; held = null; items.clear(); names.clear();
    }

    /**
     * Persistable physical continuity for restart, or null when no death awaits reclaim. Item
     * identity and quantity only; the live reclaim interaction and presentation labels are
     * deliberately not persisted.
     */
    PendingDeath snapshot() {
        var outstanding = items(items); var held = items(this.held);
        return outstanding.isEmpty() && held.isEmpty() ? null
                : new PendingDeath(outstanding, held, wipe, age);
    }

    static List<DeathItem> items(Map<Integer, Long> counts) {
        return list(positive(counts).entrySet(), entry -> new DeathItem(entry.getKey(), entry.getValue()));
    }

    /**
     * Restore persisted physical continuity after a restart. The reclaimed quantities are matched
     * exactly as before; the delayed-wipe window resumes its remaining bounded ticks and the
     * gravestone timer keeps its age. A live reclaim interaction is never restored.
     */
    void restore(PendingDeath pending) {
        reset(); if (pending == null || pending.isEmpty()) return;
        for (var item : pending.outstandingItems())
            if (item.quantity() > 0 && !MinigameItems.isMinigameOnly(item.itemId))
                items.merge(item.itemId, item.quantity(), SafeMath::sum);

        items = cash(items); var held = numberMap();
        for (var item : pending.heldAtDeath())
            held.merge(item.itemId, item.quantity(), SafeMath::sum);
        held.keySet().removeIf(MinigameItems::isMinigameOnly); held = new HashMap<>(cash(held));
        wipe = min(DEATH_WIPE_WINDOW_TICKS, max(0, pending.wipeTicksRemaining));
        this.held = wipe > 0 && !held.isEmpty() ? held : null;
        wipe = this.held == null ? 0 : wipe; age = max(0, pending.gravestoneAgeTicks);
        awaitingReclaim = this.held != null || !items.isEmpty() && age < AWAIT_TICKS;
    }

    /** Adopt the physical continuity of a staged engine during an owner install. */
    void adoptFrom(DeathReclaimLifecycle other) {
        reset(); if (other == null) return;
        awaitingReclaim = other.awaitingReclaim; wipe = other.wipe; age = other.age; ticks = other.ticks;
        service = other.service; held = other.held == null ? null : new HashMap<>(other.held);
        items.putAll(other.items); names.putAll(other.names);
    }

    void finishWipe() {
        if (held != null && held.isEmpty()) cancelWipe(); completeIfEmpty();
    }

    void completeIfEmpty() {
        if (awaitingReclaim && held == null && items.isEmpty()) reset();
    }
}

/**
 * Read-only state for the local PvM death-reclaim indicator. The item count is the sum of measured
 * outstanding stack quantities, and age is measured in active engine game ticks.
 */

/**
 * Item Retrieval Services and their published flat fees. Source: OSRS Wiki "Item Retrieval Service"
 * (checked 2026-10-11). The fee here is an <em>expectation used for labelling</em>; the booked cost
 * is always the coins actually observed leaving the inventory in the reclaim window. Fees paid from
 * the bank or Death's Coffer are not observable and are never invented.
 */
@UtilityClass
class BossRetrievalCatalogue {
    /** Marker for services whose fee depends on the run (ToA scales with raid level). */
    final long VARIABLE_FEE = -1;

    @AllArgsConstructor
    class Service {
        String key, activity, interactable; long fee;
        String note;

        /**
         * Target name shared with unrelated scenery (a bare "Chest", a gravestone): only evidence
         * when a local PvM death is still awaiting reclaim.
         */
        boolean ambiguous;

        /** Ledger explanation for an observed coin payment at this service. */
        String why(long coins) {
            var why = new StringBuilder(msg("itemRetrieval")).append(activity).append(" (").append(interactable)
                            .append(")");
            if (fee > 0) {
                why.append(", published ").append(exact(fee));
                if (coins > 0 && coins != fee)
                    why.append(", observed ").append(exact(coins));
            } else if (fee == VARIABLE_FEE)
                why.append(", fee varies");
            if (!note.isEmpty()) why.append(". ").append(note); return why.toString();
        }
    }

    final Map<String, Service> BY_INTERACTABLE = new LinkedHashMap<>();

    static {
        for (var row : rows("retrieval_services"))
            BY_INTERACTABLE.put(row[2].toLowerCase(ROOT), new Service(row[0], row[1], row[2], Long.parseLong(row[3]),
                            row[4], Boolean.parseBoolean(row[5])));
    }


    /**
     * Service for a menu target (NPC or object name) when the option is a retrieval verb (see
     * {@link #isRetrievalOption}). Ambiguous targets ("Chest", "Gravestone", "Death") are returned
     * too; the engine only acts on them while a local PvM death awaits reclaim.
     */
    Service serviceFor(String option, String target) {
        if (target == null || option == null || !matches("reclaim", lower(option)))
            return null;
        String lower = lower(target); if (lower.isEmpty()) return null;
        var exact = BY_INTERACTABLE.get(lower); if (exact != null) return exact;
        for (var entry : BY_INTERACTABLE.entrySet())
            if (!entry.getValue().ambiguous && lower.contains(entry.getKey()))
                return entry.getValue();

        return matches("grave", lower) ? BY_INTERACTABLE.get("gravestone") : null;
    }
}

/** Complete observed recipes; the catalogue authorizes shapes, never prices or forecast yield. */
@UtilityClass
class ProductionRecipes {
    final Map<Integer, List<String[]>> BY_OUTPUT = cached("production_recipes").stream().collect(
                    java.util.stream.Collectors.groupingBy(row -> parseInt(row[0])));

    String[] match(List<Flow> flows) {
        if (empty(flows)) return null;
        for (var output : flows)
            if (output.quantity > 0)
                for (var row : BY_OUTPUT.getOrDefault(output.item, emptyList()))
                    if (fits(row, output.quantity, flows)) return row;
        return null;
    }

    boolean fits(String[] row, long output, List<Flow> flows) {
        int batch = parseInt(row[2]); if (output % batch != 0) return false; long units = output / batch;
        var remaining = numberMap();
        for (var flow : flows)
            if (remaining.put(flow.item, flow.quantity) != null) return false;
        for (String leg : row[3].split(" ")) {
            String[] part = leg.split(":"); Long observed = remaining.remove(parseInt(part[0]));
            long quantity = observed == null ? 0 : observed;
            if (part[1].equals("?") ? quantity > 0 || quantity < -units
                            : quantity % units != 0 || quantity / units != Long.parseLong(part[1])) return false;
        }
        return remaining.isEmpty();
    }
}

/**
 * Derives the observed {@link ActionKind} for a settled inventory change from positive evidence
 * only: a matched menu verb plus the settled flow shape. Never upgrades weak evidence into a
 * confident verb — a dose leftover without a matched Drink click is {@link ActionKind#SUPPLIES},
 * not {@link ActionKind#DRINK}.
 *
 * <p>Presentation evidence only. Accounting type, valuation and counted state are decided elsewhere
 * and never read this class.
 */
@UtilityClass
class ActionReader {
    final Set<String> HERBLORE_INGREDIENTS = new HashSet<>(names("empty_vessels"));


    /** Menu verb prefixes (a leading "~" means "contains") and the action each evidences. */
    final Object[] VERBS = rows("action_rules").stream().filter(a -> !a[2].isEmpty())
                    .flatMap(a -> java.util.stream.Stream.of(a[0], ActionKind.valueOf(a[2]))).toArray();

    /**
     * Final stamp for a booked change.
     *
     * @param matchedIntent the menu verb whose intent matched these flows, or null when the intent
     *     was absent, stale or matched nothing
     * @param flows settled flows of the transaction
     * @param type booked accounting type (unchanged by this call)
     * @param activityName booked activity name, used only to recognise Herblore production
     */
    ActionKind resolve(ActionKind matchedIntent, List<Flow> flows, TransactionType type, String activityName) {
        if (empty(flows)) return null;
        // A decant shape can also appear in a bank transfer or trade. Only the
        // otherwise-unclassified mixed inventory row is evidence of a player action.
        if (type == UNCERTAIN && isDecant(flows)) return DECANT; String activity = lower(activityName);
        if (type == PROCESSING && activity.equals("herblore") && all(flows, -1, ActionReader::isIngredient)
                && all(flows, 1, name -> dose(name) > 0))
            return MIX;
        if (type == PROCESSING && activity.equals("cooking") && isCooking(flows))
            // Use the actual outcome: every output burnt → Burnt, otherwise Cooked.
            return all(flows, 1, name -> name.startsWith("burnt")) ? BURN : COOK;
        // Rune/ammo spends are only recognised on cost-shaped rows: a bank deposit or
        // death loss of runes is a transfer/death, not casting.
        boolean spend = type == CONSUMPTION || type == PK_SUPPLY_COST, hasLoss = hasLoss(flows),
            gain = !gains(flows, ANY).isEmpty(), lossOnly = hasLoss && !gain;
        if (spend && lossOnly && all(flows, 0, ActionReader::isRune)) return CAST;
        if (spend && lossOnly && all(flows, 0, ActionReader::isAmmo)) return FIRE; boolean doseStep = doseStep(flows);
        // Every loss must be one potion's doses (Drink) or one item (Eat/Bury/...): a coalesced
        // shark or bones under the same click is not "Drank", so it falls back to Supplies.
        boolean onePotion = oneLossFamily(flows, name -> dose(name) > 0 ? doseBase(name) : null),
            oneItem = oneLossFamily(flows, name -> name.isEmpty() ? null : name)
                        && all(flows, -1, name -> dose(name) <= 0);
        if (matchedIntent != null && hasLoss)
            switch (matchedIntent) {
                case DRINK:
                    return onePotion ? DRINK : SUPPLIES;
                case EAT:
                case BURY:
                case SCATTER:
                case OFFER:
                    return oneItem ? matchedIntent : SUPPLIES;
                case CAST:
                    return !ownershipMove(type) && !all(flows, -1,
                                            name -> !isRune(name) && named(name) == null) ? CAST
                            : doseStep ? SUPPLIES : null;
                case SUPPLIES:
                    return lossOnly && all(flows, 0, name -> name.matches(msg("logsKindling"))) ? SUPPLIES
                            : null;
                default:
                    return matchedIntent;
            }

        // Consumption is known from the leftover shape; the verb is not.
        return type == CONSUMPTION && doseStep ? SUPPLIES : null;
    }

    /**
     * Doses of one potion redistributed with no dose lost: e.g. −(3) −(1) +(4), or −(4) +(2) +(2).
     * Requires every flow to be a dosed form of the same base name.
     */
    boolean isDecant(List<Flow> flows) {
        if (flows == null || flows.size() < 2) return false; String base = null;
        long doseBalance = 0; boolean anyLoss = false, gain = false;
        for (Flow flow : flows) {
            if (flow.quantity == 0) continue; String name = lower(flow.name);
            int dose = dose(name);
            // Combining doses frees a vessel; an emptied vial/jug gain is part of the shape.
            if (dose <= 0 && flow.quantity > 0 && rule("vessel", name)) continue; String stem = doseBase(name);
            if (dose <= 0 || stem.isEmpty() || base != null && !base.equals(stem)) return false; base = stem;
            doseBalance += dose * flow.quantity; anyLoss |= flow.quantity < 0;
            gain |= flow.quantity > 0;
        }
        return anyLoss && gain && doseBalance == 0;
    }

    /** A dose/food step confirms a consume even when its menu intent has expired. */
    boolean doseStep(List<Flow> flows) {
        return !partialUses(flows).isEmpty();
    }

    /** Exact matched loss/remainder pairs for a recognized dose or partial-food consume. */
    List<PartialUse> partialUses(List<Flow> flows) {
        if (flows == null || flows.size() < 2) return emptyList(); var pairs = new ArrayList<PartialUse>();
        for (int index = 0; index < flows.size(); index++) {
            // A loss pairs only with the one remainder it could have become, and that remainder
            // only with this loss.
            var gains = stepPartners(flows, flows.get(index), true);
            if (gains.size() == 1 && stepPartners(flows, flows.get(gains.get(0)), false).size() == 1)
                pairs.add(new PartialUse(index, gains.get(0)));
        }
        return unmodifiableList(pairs);
    }

    /**
     * Indexes of the flows a loss could step to ({@code toGains}), or the losses a gain could come
     * from.
     */
    List<Integer> stepPartners(List<Flow> flows, Flow flow, boolean toIncome) {
        var partners = new ArrayList<Integer>(); Flow loss = toIncome ? flow : null, gain = toIncome ? null : flow;
        if (flow == null || (toIncome ? flow.quantity >= 0 || flow.quantity == Long.MIN_VALUE
                        : flow.quantity <= 0))
            return partners;
        for (int index = 0; index < flows.size(); index++) {
            Flow other = flows.get(index), lost = toIncome ? loss : other, gained = toIncome ? other : gain;
            if (lost != null && gained != null && lost.quantity < 0 && lost.quantity != Long.MIN_VALUE
                    && gained.quantity > 0 && -lost.quantity == gained.quantity
                    && isConsumeStep(lost.name, gained.name))
                partners.add(index);
        }
        return partners;
    }

    @AllArgsConstructor
    class PartialUse {
        int lossIndex, remainder;
    }

    boolean addDoseLosses(Map<Integer, Long> target, List<Flow> flows) {
        boolean matched = false;
        for (Flow loss : flows)
            if (!stepPartners(flows, loss, true).isEmpty()) {
                target.put(loss.item, -loss.quantity); matched = true;
            }

        return matched;
    }

    /**
     * Potion base name without its dose suffix, for receipts: "Prayer potion(4)" → "Prayer potion".
     */
    String doseBase(String name) {
        return pattern("dose-suffix").matcher(string(name).trim()).replaceFirst("").trim();
    }

    /** Dose count parsed from a trailing "(N)" suffix, or −1 when absent. */
    int dose(String name) {
        var matcher = pattern("dose-suffix").matcher(string(name).trim());
        return matcher.find() ? parseInt(matcher.group(1)) : -1;
    }

    /** Ammunition names; tips, heads and shafts are fletching supplies, not ammunition. */
    boolean isAmmo(String name) {
        String lower = lower(name); return matches("ammunition", lower) && !matches("fletching-part", lower);
    }

    /**
     * True when every loss has the same non-null family key (a matched click cannot name mixed
     * costs); false when there is no loss.
     */
    boolean oneLossFamily(List<Flow> flows, Function<String, String> family) {
        String first = null;
        for (Flow flow : flows) {
            if (flow.quantity >= 0) continue; String key = family.apply(lower(flow.name));
            if (key == null || first != null && !first.equals(key)) return false; first = key;
        }
        return first != null;
    }

    /** A cooking row must pair raw food inputs with only their cooked/burnt products. */
    boolean isCooking(List<Flow> flows) {
        var rawBases = list(matching(flows, Flow::cost), flow -> {
            String name = lower(flow.name);
            return name.startsWith("raw ") && name.length() > 4 ? name.substring(4).trim()
                            : name.startsWith("uncooked ") && name.length() > 9 ? name.substring(9).trim() : null;
        });

        return !rawBases.isEmpty() && !rawBases.contains(null) && all(flows, 1, output -> any(rawBases, raw -> output.equals(raw) || output.equals("cooked " + raw)
                                                                || output.equals("burnt " + raw)));
    }

    /**
     * True when at least one flow on the chosen side (1 gains, -1 losses, 0 both) exists and every
     * such flow's lower-case name satisfies {@code predicate}.
     */
    boolean all(List<Flow> flows, int side, Predicate<String> predicate) {
        boolean any = false;
        for (Flow flow : flows) {
            long delta = flow.quantity; if (delta == 0 || side != 0 && Long.signum(delta) != side) continue;
            if (!predicate.test(lower(flow.name))) return false; any = true;
        }
        return any;
    }

    /** Ordered intent and item-name rules, compiled beside the old implementation for parity. */
    final Map<String, Clause[][]> RULES = rules("action_rules");

    boolean rule(String group, String text) {
        return has(text, RULES.get(group));
    }

    /** First matching verb wins, before the explicit fuel fallback. */
    ActionKind verbOf(String raw) {
        String name = lower(raw);
        for (int index = 0; index < VERBS.length; index += 2)
            if (rule((String) VERBS[index], name)) return (ActionKind) VERBS[index + 1];

        return rule("supplies", name) ? SUPPLIES : null;
    }

    /** Empty vessels use the already-normalized name, exactly as before. */

    /** Rune and unopened-claim names use the shared trimmed normalization. */
    boolean isRune(String name) {
        return rule("rune", lower(name));
    }

    boolean isClaim(String name) {
        return rule("claim", lower(name));
    }

    /** Ingredient names retain their existing non-null precondition and named ingredient set. */
    boolean isIngredient(String name) {
        return !name.isEmpty() && rule("ingredient", name) && (HERBLORE_INGREDIENTS.contains(name)
                        || matches("herblore-ingredient", name));
    }

    /** Dose arithmetic is unchanged; only food/vessel vocabulary is table-driven. */
    boolean isConsumeStep(String lostName, String gainedName) {
        String lost = lower(lostName), gain = lower(gainedName); if (lost.isEmpty() || gain.isEmpty()) return false;
        if (rule("vessel", gain)) return true; int lostDose = dose(lost), dose = dose(gain);
        if (lostDose > 0) {
            String lostBase = doseBase(lost);
            if (!lostBase.isEmpty() && lostBase.equals(doseBase(gain)))
                // A suffix-less gain of the same base is the uncharged item: the last glory
                // charge leaves the plain amulet, so the pair is still one dose step (owner
                // 1.3.4). Any other suffix-less gain keeps failing closed.
                return lostDose == (dose < 0 ? 0 : dose) + 1;
        }
        return rule("food", lost) && rule("partial", gain);
    }
}

/**
 * Menu / chat vocabulary that turns a click into an accounting intent: explicit spend actions (eat,
 * drink, bury, offer, light), transform/production options that arm PRODUCTION context, gather
 * verbs that only name the activity, and the chat lines that confirm a spend after the click.
 * Anything not listed here stays ambiguous and settles through Review, never a guess.
 */
@UtilityClass
class ActionSignals {
    final Map<String, String> TRANSFORM = byName("transform_skills"), GATHER = byName("gather_skills");

    /** Compiled menu/chat predicates; d7/d10 already contain transform and gather data. */
    final Map<String, Clause[][]> RULES = rules("menu_rules");

    /** Apply one compiled predicate group. */
    boolean rule(String group, String text) {
        return has(text, RULES.get(group));
    }

    /** Widget actions eligible for selected spell evidence. */
    boolean isWidgetAction(MenuAction action) {
        return rule("widget", action == null ? null : action.name());
    }

    /** Raw consume verbs plus normalized explicit spends. */
    boolean isSpend(String option) {
        return rule("spend.raw", option) || isSpendName(option);
    }

    /** Normalized light/offer/pray and burial/scatter verbs. */
    boolean isSpendName(String option) {
        return rule("spend.norm", lower(option));
    }

    /** Use an item on a target matching both named rules; target whitespace is preserved. */
    boolean usesOn(String option, String target, String firstRule, String secondRule) {
        return rule("use", lower(option)) && rule(firstRule, lower(target, false))
                && rule(secondRule, lower(target, false));
    }

    /** Positive Sinister Offering cast evidence. */
    boolean isOffering(String option, String target) {
        return rule("cast", lower(option)) && rule("offering", lower(target, false));
    }

    /** Chaos Altar saved a bone; this cannot confirm spend. */
    boolean isSpared(String message) {
        return rule("spared", lower(message, false));
    }

    /** Inventory-spend chat, excluding a saved bone. */
    boolean isChatSpend(String message) {
        return !isSpared(message) && rule("spend.chat", lower(message, false));
    }

    /**
     * Transform/production options (station or inventory) mapped to the skill whose XP confirms
     * them.
     */
    String transformSkill(String option) {
        return TRANSFORM.getOrDefault(lower(option), "");
    }

    /** Activity hint for object gather verbs; GENERAL when the verb is not a gather. */
    String gatherActivity(String option) {
        return GATHER.getOrDefault(string(option), GENERAL);
    }

    /** Rake and plant still name the activity, but do not arm gather ownership evidence. */
    boolean namesActivityOnly(String option) {
        return option != null && !GENERAL.equals(gatherActivity(option)) && !rule("gather.exclude", option);
    }
}

/**
 * Schema-106 pooled known-basis coverage memory.
 *
 * <p>Tracked basis is a double-count-prevention memory, never a second financial ledger: canonical
 * counted transactions stay the only financial authority and this ledger never books, reprices or
 * exports GP. It records only <em>known coverage</em>: quantity whose prior GP Manager accounting
 * is proven, with the exact GP value GP Manager counted (which may legitimately be zero). Absence
 * of coverage means UNKNOWN basis, never zero basis; unknown quantity is derived per realization
 * and never persisted as a global unknown-owned ledger.
 *
 * <p>Allocation is tracked-first pooled weighted average (never FIFO/LIFO). Acquisitions add known
 * coverage. Proven SELL placements reserve their share; cancellations release the unused
 * reservation exactly; settlements consume the realized share; permanent ownership sinks deplete
 * with the same rule. A per-item realization fence blocks retroactive acquisition corrections once
 * known coverage has been realized or depleted, so canonical Net and tracked basis can never
 * disagree about what was previously counted.
 */
class TrackedBasisLedger {
    /** Exact known-coverage share a proven SELL placement captured from the available pool. */
    @AllArgsConstructor
    static class Reservation {
        long trackedQty, basisGp;
    }

    Map<Integer, BasisPool> stock = new LinkedHashMap<>(); long epoch;
    IntFunction<Long> openReservationLookup = id -> 0L;

    boolean tracks(Transaction transaction) {
        return transaction != null && transaction.timestamp >= epoch;
    }

    // ---------------------------------------------------------------------------------------
    // Acquisition / removal / change
    // ---------------------------------------------------------------------------------------
    /**
     * Book a newly added canonical transaction: add its exact known coverage, and deplete known
     * coverage for proven permanent non-custody ownership sinks. Custody-owned settlements already
     * consumed their reservation, so their item legs never deplete again.
     */
    synchronized void onAdded(Transaction transaction, boolean owned) {
        if (!tracks(transaction))
            // Pre-epoch stock is UNKNOWN coverage by migration law; nothing is inferred here.
            return;
        if (epoch <= 0 && transaction.timestamp > 0)
            epoch = transaction.timestamp;
        applyContribution(contribution(transaction), transaction.timestamp); if (!owned) depleteSinks(transaction);
    }

    /** Validate restoring a previously removed transaction; false blocks the restore. */
    synchronized boolean canAdd(Transaction transaction) {
        // Restoring an old captured receipt changes money only; onAdded cannot backfill its stock.
        return transaction != null && (!tracks(transaction) || sinks(transaction).isEmpty() && canApplyAll(contribution(transaction), 1, transaction.timestamp));
    }

    /** Validate removing a retained transaction (undo); false blocks the canonical removal. */
    synchronized boolean canRemove(Transaction transaction) {
        return !tracks(transaction) || sinks(transaction).isEmpty() && canApplyAll(contribution(transaction), -1, transaction.timestamp);
    }

    boolean canApplyAll(Map<Integer, long[]> changes, long sign, long timestamp) {
        return changes.entrySet().stream().allMatch(entry -> canApply(entry.getKey(),
                        sign * entry.getValue()[0], sign * entry.getValue()[1], timestamp));
    }

    private TrackedBasisLedger projection() {
        var projected = new TrackedBasisLedger(); projected.epoch = epoch;
        projected.openReservationLookup = openReservationLookup;
        projected.stock = snapshot();
        return projected;
    }

    Map<Integer, BasisPool> snapshot() {
        return copyPools(stock);
    }

    /**
     * Validate a complete mutation sequence in application order against a detached projection, so
     * one target's decision cannot silently change an earlier one's eligibility.
     */
    synchronized boolean canChangeAll(List<Transaction> ordered, Consumer<Transaction> change) {
        var projected = projection();
        for (var original : ordered)
            if (!projected.change(original, change)) return false;
        return true;
    }

    private boolean change(Transaction original, Consumer<Transaction> change) {
        var transaction = copy(original); var previous = transaction.correction();
        var previousFlows = transaction.flows(); change.accept(transaction);
        if (!canChange(original, previous, previousFlows, transaction.correction(), transaction.flows()))
            return false;
        onChanged(transaction, previous, previousFlows);
        return true;
    }

    /** Move known coverage between two raw ids of one owned item and fence both states. */
    synchronized void handoff(int from, int to, long quantity, long now, String owner) {
        var moved = reserve(from, quantity); release(to, moved.trackedQty, moved.basisGp);
        fenceRealized(from, now, owner); fenceRealized(to, now, owner);
    }

    synchronized void onUsedUp(Transaction transaction) {
        if (tracks(transaction)) depleteSinks(transaction);
    }

    /** Reverse a previously removed transaction's exact known coverage. */
    synchronized void onRemoved(Transaction transaction) {
        if (!tracks(transaction)) return;
        contribution(transaction).forEach((id, delta) -> applyDelta(id, -delta[0], -delta[1]));
    }

    /**
     * Validate a pending correction/split/undo before any canonical mutation. False means the
     * mutation must be rejected as-is: the item's known coverage is reserved or already realized.
     */
    synchronized boolean canChange(Transaction transaction, Correction previous, List<Flow> previousFlows,
            Correction nextCorrection, List<Flow> next) {
        if (transaction == null) return false; var before = contribution(transaction, previous, previousFlows);
        // A null next-flow set means the caller can only promise the positive contribution
        // shrinks; validating against the empty worst case is the strictest safe check.
        var after = contribution(transaction, nextCorrection, next);
        var beforeSinks = sinks(transaction, previous, previousFlows);
        var afterSinks = sinks(transaction, nextCorrection, next);
        return !(transaction.timestamp >= epoch && !beforeSinks.equals(afterSinks)
                && !canIntroduceSink(beforeSinks, afterSinks, transaction.timestamp, transaction.id)) && canApplyAll(deltas(before, after), 1, transaction.timestamp);
    }

    /** Apply the exact effective delta after a permitted correction/split/undo. */
    synchronized void onChanged(Transaction transaction, Correction previous, List<Flow> previousFlows) {
        if (!tracks(transaction)) return; var before = sinks(transaction, previous, previousFlows);
        var after = sinks(transaction);
        applyContribution(deltas(contribution(transaction, previous, previousFlows),
                        contribution(transaction)), transaction.timestamp);
        if (!before.equals(after) && before.isEmpty()) depleteSinks(transaction);
    }

    // ---------------------------------------------------------------------------------------
    // Reservation / realization / depletion
    // ---------------------------------------------------------------------------------------
    /**
     * Reserve the tracked-first known-coverage share for a quantity entering proven SELL custody.
     */
    synchronized Reservation reserve(int id, long quantity) {
        long requested = positive(quantity); var pool = known(id);
        long available = pool.availableQty, availableBasis = pool.availableBasisGp, take = min(requested, available),
            basis = BasisMath.shareOf(available, availableBasis, take);
        if (take <= 0 && basis <= 0) return new Reservation(0, 0);
        pool = pool(id); pool.availableQty = positive(available - take);
        pool.availableBasisGp = positive(availableBasis - basis); return new Reservation(take, basis);
    }

    /** A sale realized units of this item: fence retroactive corrections before {@code now}. */
    synchronized void fenceRealized(int id, long now) {
        fenceRealized(id, now, null);
    }

    /** Only a custody handoff's own Review may consume the share its fence protects. */
    synchronized void fenceRealized(int id, long now, String owner) {
        var pool = pool(id); if (owner == null) pool.fenceOwners = null;
        else if (now >= pool.fencedAt || pool.fenceOwners != null) {
            if (pool.fenceOwners == null) pool.fenceOwners = new HashSet<>(); pool.fenceOwners.add(owner);
        }
        pool.fencedAt = max(pool.fencedAt, now);
    }

    /** Return an unconsumed reservation share to the available pool (cancel/return). */
    synchronized void release(int id, long quantity, long basisGp) {
        long released = positive(quantity), releaseBasis = positive(basisGp); if (released <= 0 && releaseBasis <= 0) return;
        var pool = pool(id); pool.availableQty = positive(sum(pool.availableQty, released));
        pool.availableBasisGp = positive(sum(pool.availableBasisGp, releaseBasis));
    }

    /** Deplete known coverage for a proven permanent ownership sink; returns basis consumed. */
    synchronized long deplete(int id, long quantity, long now) {
        return deplete(id, quantity, now, null, false);
    }

    synchronized long deplete(int id, long quantity, long now, String owner, boolean held) {
        long requested = positive(quantity); if (requested <= 0) return 0; var pool = pool(id);
        // A handoff already withheld its share; only its decision identity needs retiring.
        long take = held ? 0 : min(requested, pool.availableQty),
            basis = BasisMath.shareOf(pool.availableQty, pool.availableBasisGp, take);
        pool.availableQty = positive(pool.availableQty - take); pool.availableBasisGp = positive(pool.availableBasisGp - basis);
        boolean owned = owner != null && pool.fenceOwners != null && pool.fenceOwners.remove(owner);
        if (!held && !owned || empty(pool.fenceOwners)) pool.fenceOwners = null; pool.fencedAt = max(pool.fencedAt, now);
        return basis;
    }

    /** Currency is money, never tracked owned-item value. */

    // ---------------------------------------------------------------------------------------
    // Persistence / lifecycle
    // ---------------------------------------------------------------------------------------
    synchronized void writeTo(SavedState state) {
        if (state == null) return; var out = new TrackedBasisState();
        // Epoch 0 only happens for an engine that never restored or tracked anything; a positive
        // sentinel keeps a saved pool loadable as "tracked since the beginning".
        out.basisEpochMillis = positive(epoch > 0 ? epoch : 1);
        out.pools = list(matching(stock.values(), pool -> pool.itemId > 0
                && (pool.availableQty > 0 || pool.availableBasisGp > 0 || pool.fencedAt > 0 || pool.acquiredAt > 0)),
                SaveCopies::copy);
        state.setTrackedBasis(out);
    }

    /**
     * Restore persisted continuity. A pre-106 (or absent) state starts with no known coverage at
     * the migration boundary: existing holdings are UNKNOWN coverage, no bank value seeds basis and
     * no history is reconstructed. Reserved shares live on the durable custody records.
     */
    synchronized void restore(SavedState state, long now) {
        stock.clear(); var saved = state == null ? null : state.trackedBasis();
        if (saved != null && saved.basisEpochMillis() > 0) {
            epoch = saved.basisEpochMillis();
            for (var pool : saved.pools()) {
                if (pool == null || pool.itemId <= 0) continue;
                // A save is clamped once here, so the pool's fields are read directly.
                pool.availableQty = positive(pool.availableQty); pool.availableBasisGp = positive(pool.availableBasisGp);
                pool.fencedAt = positive(pool.fencedAt); pool.acquiredAt = positive(pool.acquiredAt); stock.put(pool.itemId, pool);
            }
        } else
            epoch = now;
    }

    /** Destructive reset: tracked basis is continuity state and clears with the rest. */
    synchronized void reset() {
        stock.clear(); epoch = 0;
    }

    /** Adopt another ledger's durable state during a staged profile swap. */
    synchronized void adoptFrom(TrackedBasisLedger other) {
        reset();
        if (other != null) {
            epoch = other.epoch;
            other.stock.forEach((id, pool) -> {
                        if (pool != null) stock.put(id, pool);
                    });
        }
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------
    BasisPool pool(int id) {
        return stock.computeIfAbsent(id, BasisPool::new);
    }

    void applyContribution(Map<Integer, long[]> part, long timestamp) {
        for (var entry : part.entrySet()) {
            applyDelta(entry.getKey(), entry.getValue()[0], entry.getValue()[1]);
            if (entry.getValue()[0] > 0) {
                var pool = pool(entry.getKey()); pool.acquiredAt = max(pool.acquiredAt, timestamp);
            }
        }
    }

    /** Deplete known coverage for each proven permanent non-custody ownership sink in the row. */
    void depleteSinks(Transaction transaction) {
        var counts = sinks(transaction);
        for (var entry : counts.entrySet())
            deplete(entry.getKey(), entry.getValue(), transaction.timestamp, transaction.id, transaction.handoffHeld);
        if (!counts.isEmpty()) transaction.handoffHeld = false;
    }

    /** An owner may first count a Review removal while no later use has frozen its pool. */
    boolean canIntroduceSink(Map<Integer, Long> before, Map<Integer, Long> after, long timestamp, String owner) {
        return before.isEmpty() && !after.isEmpty() && !any(after.keySet(), id -> {
            var pool = known(id);
            return !canApply(id, 0, 0, timestamp, owner) || pool.availableQty > 0
                            && (pool.acquiredAt <= 0 || pool.acquiredAt > timestamp);
        });
    }

    /** Physical counted removals that would require exact reversal of pooled sink depletion. */
    Map<Integer, Long> sinks(TransactionType type, boolean counted, Correction decision, List<Flow> flows) {
        var counts = orderedNumberMap();
        if (!counted || ownershipMove(type) || decision == Correction.TRANSFER
                || flows == null)
            // Custody-owned trade settlements consume their reservation separately. Their financial
            // Result corrections must remain available without touching physical basis.
            return counts;
        for (Flow flow : flows) {
            if (flow.quantity >= 0 || flow.item == COINS || flow.item == PLATINUM) continue;
            long quantity = flow.quantity == Long.MIN_VALUE ? Long.MAX_VALUE : -flow.quantity;
            int id = flow.item; counts.put(id, sum(amount(counts, id), quantity));
        }
        return counts;
    }

    void applyDelta(int id, long quantity, long basisDelta) {
        if (quantity == 0 && basisDelta == 0) return; var pool = pool(id); pool.fenceOwners = null;
        pool.availableQty = positive(sum(pool.availableQty, quantity));
        pool.availableBasisGp = positive(sum(pool.availableBasisGp, basisDelta));
    }

    boolean canApply(int id, long quantity, long basisDelta, long timestamp) {
        return canApply(id, quantity, basisDelta, timestamp, null);
    }

    boolean canApply(int id, long quantity, long basisDelta, long timestamp, String owner) {
        if (timestamp < epoch) return false; Long reservationTime = openReservationLookup.apply(id);
        if (reservationTime != null && reservationTime > 0 && reservationTime >= timestamp) return false; var pool = known(id);
        return !(pool.fencedAt > 0 && pool.fencedAt >= timestamp
                        && (owner == null || pool.fenceOwners == null || !pool.fenceOwners.contains(owner))
                        // A bounded total has no exact subtractive inverse at capacity.
                        || quantity < 0 && pool.availableQty == Long.MAX_VALUE
                        || basisDelta < 0 && pool.availableBasisGp == Long.MAX_VALUE
                        || sum(pool.availableQty, quantity) < 0 || sum(pool.availableBasisGp, basisDelta) < 0);
    }

    /** The item's pool, or an identified empty one when nothing is known: never stored. */
    BasisPool known(int id) {
        var pool = stock.get(id); return pool != null ? pool : new BasisPool(id);
    }

    Map<Integer, long[]> deltas(Map<Integer, long[]> before, Map<Integer, long[]> after) {
        var out = new LinkedHashMap<Integer, long[]>();
        before.forEach((id, value) -> out.put(id, new long[] {-value[0], -value[1]}));
        after.forEach((id, value) -> {
            if (value == null && before.containsKey(id)) return;
            var delta = out.computeIfAbsent(id, key -> new long[2]); delta[0] += value[0]; delta[1] += value[1];
        });
        out.entrySet().removeIf(entry -> before.containsKey(entry.getKey())
                && entry.getValue()[0] == 0 && entry.getValue()[1] == 0);
        return out;
    }

    Map<Integer, long[]> contribution(Transaction transaction) {
        return contribution(transaction, transaction.correction(), transaction.flows());
    }

    /** The contribution the transaction would have under {@code correction} with {@code flows}. */
    Map<Integer, long[]> contribution(Transaction transaction, Correction decision, List<Flow> flows) {
        decision = transaction.physical(decision);
        return flows == null || financialDose(transaction) ? new LinkedHashMap<>() : contribution(transaction.original(),
                        effectiveCounted(transaction.counted, decision), decision, flows);
    }

    /** The row's physical sinks, retaining ownership when its financial booking was removed. */
    Map<Integer, Long> sinks(Transaction transaction) {
        return sinks(transaction, transaction.correction(), transaction.flows());
    }

    Map<Integer, Long> sinks(Transaction transaction, Correction decision, List<Flow> flows) {
        decision = transaction.physical(decision);
        // A drop or a shot still on the ground is not a use yet: picking it up shrinks the row. It
        // depletes the pool when its pickup window closes (Engine#settleGround).
        return transaction.onGround() || financialDose(transaction) ? new LinkedHashMap<>() : sinks(transaction.original(), effectiveCounted(transaction.counted, decision),
                decision, flows);
    }

    /** Effective counted state for an explicit correction (REVENUE/COST always count). */
    static boolean effectiveCounted(boolean autoCounted, Correction decision) {
        var effective = decision == null ? AUTO : decision;
        return effective == REVENUE || effective == COST || effective == AUTO && autoCounted;
    }

    /**
     * Exact known coverage a counted transaction introduces, mirroring {@code Accounting.flow} for
     * positive quantities: AUTO counts the observed positive value, REVENUE counts the gross
     * magnitude, COST/IGNORE/TRANSFER count nothing. Quantity is tracked even when the booked value
     * is legitimately zero; unpriced/unknown/deferred sources create no coverage at all.
     */
    static Map<Integer, long[]> contribution(
            TransactionType type, boolean counted, Correction decision, List<Flow> flows) {
        var out = new LinkedHashMap<Integer, long[]>(); var effective = decision == null ? AUTO : decision;
        if (type == TransactionType.TRANSFER || effective == Correction.TRANSFER || !counted || effective == IGNORE
                || flows == null)
            return out;
        for (Flow flow : flows) {
            if (flow.quantity <= 0 || flow.item == COINS || flow.item == PLATINUM) continue; var source = flow.source();
            if (source == null || source == UNPRICED || source == UNKNOWN || source == PriceSource.DEFERRED_CLAIM) continue;
            long income = effective == REVENUE ? abs(flow.value)
                            : effective == COST ? 0 : positive(flow.value);
            long[] total = out.computeIfAbsent(flow.item, id -> new long[2]);
            total[0] = sum(total[0], flow.quantity); total[1] = sum(total[1], income);
        }
        return out;
    }
}

/**
 * Persisted schema-106 tracked-basis continuity: the minimum state a pooled weighted-average
 * double-count-prevention memory needs across restart.
 *
 * <p>{@code basisEpochMillis} is the migration boundary: acquisitions booked before it are
 * untracked by law (no backfill, no bank-price inference), and basis-affecting corrections of those
 * rows are fenced. Open SELL reservation shares live on their durable custody records; the ledger
 * rebinds the pooled reserved totals from them on restore.
 */
class TrackedBasisState extends SaveCopies {
    long basisEpochMillis; List<BasisPool> pools = new ArrayList<>();

    long basisEpochMillis() {
        return positive(basisEpochMillis);
    }

    /** Never null: a pre-106 file upgrades with an empty pool. */
    List<BasisPool> pools() {
        if (pools == null) pools = new ArrayList<>(); return pools;
    }
}

/**
 * One canonical item's pooled tracked-basis state (schema 106).
 *
 * <p>It is supporting ownership state, never a financial authority: canonical counted transactions
 * stay the only financial truth. {@code available*} is the free pooled quantity/value that a new
 * SELL reservation or permanent sink may consume (a reserved share is held by its open SELL custody
 * record, not here); {@code realizationFenceEpochMillis} records the latest time pooled basis was
 * realized or permanently depleted, which fences retroactive acquisition corrections for this item.
 * No FIFO lots, no portfolio history, no mark-to-market.
 */
@EqualsAndHashCode(callSuper = false)
class BasisPool extends SaveCopies {
    int itemId; long availableQty, availableBasisGp;
    @SerializedName("realizationFenceEpochMillis")
    long fencedAt;

    /** Optional identities of custody Reviews that may consume their own released shares. */
    Set<String> fenceOwners;

    /** Latest proven known-coverage acquisition; fences retroactive counted removals. */
    @SerializedName("latestAcquisitionEpochMillis")
    long acquiredAt;

    BasisPool() {}

    BasisPool(int id) {
        itemId = id;
    }
}

/**
 * Integer-only pooled basis math shared by the known-basis ledger and the read model.
 *
 * <p>Every allocation is floor-based and the remainder stays with the other side, so parts always
 * sum to the whole: no invented GP, no decimals, no drift. All multiplication is long-safe.
 */
class BasisMath {
    /**
     * Floor-share of a pooled quantity/basis pair; consuming everything takes the exact remainder.
     */
    static long shareOf(long totalQty, long totalBasisGp, long consumeQty) {
        return multiplyDivide(totalBasisGp, consumeQty, totalQty);
    }

    /** Long-safe floor(value × numerator / denominator) for non-negative inputs. */
    static long multiplyDivide(long value, long amount, long denominator) {
        if (denominator <= 0 || amount <= 0 || value <= 0) return 0; if (amount >= denominator) return value;
        var product = BigInteger.valueOf(value).multiply(BigInteger.valueOf(amount));
        var share = product.divide(BigInteger.valueOf(denominator));
        // b < c, so the share is below a and fits a long.
        return share.longValue();
    }
}

/**
 * Durable schema-104 GE custody ledger.
 *
 * <p>Physical placement principal, cancellation returns and refunds are ownership-neutral
 * transfers. A safely realized settlement books one canonical counted Market result exactly once,
 * guarded by the persisted cumulative-execution counters. Unattributable or unbased lifecycles fail
 * closed through the existing Review domain instead of guessing.
 *
 * <p>An observed collect coin movement (a live Collect interaction with its coin gain) is
 * attributed to exactly the sold offers it proves: the offers whose slot cleared within the SLOT
 * WINDOW, or the one subset of observed after-tax nets that sums to it exactly. A
 * unique-but-unconfirmed match is held for that short window and settles only without contradicting
 * slot evidence; a tie, a contradiction or exhausted evidence fails closed, quarantining only
 * slot-evidenced candidates and leaving the rest pending. Collect cash that arrives before the
 * matching sell execution evidence keeps its longer transient hold. Both holds are transient: a
 * restart leaves the durable records to their own unobserved-settlement handoff, never a double
 * booking. A coin gain with no Collect interaction keeps the previous booking path unchanged.
 *
 * <p>The ledger never mutates booked transactions, never re-prices history, and never scans the
 * whole session history: claims are quantity-bounded against the bounded open-record set.
 */
class GeCustodyLedger {
    /** How long a placement still explains the coins or items it took from the inventory. */
    static final long PLACEMENT_WINDOW_MILLIS = 90000;

    /** How long a terminal lifecycle may await an unobserved settlement before Review handoff. */
    static final long UNOBSERVED_GRACE_MILLIS = 10 * 60 * 1000;

    /** A cleared slot whose cash or items never reached the inventory went to the bank by now. */
    static final long BANK_COLLECT_MILLIS = 30000;

    /**
     * Slot-clear evidence window: at most 3 game ticks either side of the coin gain. A slot that
     * cleared in this window proves its offer's coins were the ones collected.
     */
    static final long SLOT_WINDOW_MILLIS = 1800;

    /** One open record per GE slot; more candidates than this can never be enumerated. */
    static final int MAX_SETTLEMENT_CANDIDATES = 8;

    /** How long a fill or cancellation still explains a collected movement. */
    static final long COLLECTION_WINDOW_MILLIS = 2 * 60 * 60 * 1000;

    /** Bounded retention of reconciled lifecycles so recent Market context survives. */
    static final int MAX_CLOSED_RECORDS = 64;

    /**
     * Per-slot baseline of each observed offer; exact fill deltas come from its cumulative
     * counters.
     */
    Map<Integer, Snapshot> baseline = new HashMap<>();

    List<GeOffer> records = new ArrayList<>();

    /** Slots whose identity was replaced mid-life; the next placement is quarantined. */
    Set<Integer> replaced = new HashSet<>();

    /**
     * Aggregate GE cash observed with a live Collect interaction but not yet exactly attributable
     * to every sell lifecycle it may settle. Retained internally (never a visible receipt) until
     * the missing offer evidence arrives and the batch reconciles exactly, or the bounded lifecycle
     * expires and the cash fails closed to Review. Transient by design: a restart without the
     * evidence leaves the records to their own unobserved-settlement handoff, never a double
     * booking.
     */
    long cash,

        cashAt;

    /** Earliest observation of the still-unattributed cash; the SLOT WINDOW anchor. */
    long heldAt;

    /**
     * True while the held cash is waiting for sell execution/terminal evidence (the existing long
     * hold). Its evidence completion books the exact attribution immediately, as before; a short
     * SLOT WINDOW hold instead waits for slot confirmation.
     */
    boolean unproven;

    /**
     * Transient slot evidence: a CLEARED offer or confirmed native cash depletion identifies a
     * nearby collection's lifecycle. Never persisted; cleared on restore/reset/adopt.
     */
    Map<String, Long> clearAt = new HashMap<>();

    /**
     * Transient: live lifecycles the login seed saw as an EMPTY slot. RuneLite reports every slot
     * EMPTY at login before the server restores the offers, so the same offer arriving shortly
     * after is a late replay of it, never a new offer reusing the slot.
     */
    Map<String, Long> loginAt = new HashMap<>();

    /** At most eight transient native boxes, tied to durable offer identity. */
    Map<Integer, CollectionBox> boxes = new HashMap<>();

    @AllArgsConstructor
    static class CollectionBox {
        String offer; long cash, quantity, execution, at;
    }

    /** Full native cash depletion supplements slot evidence; it never creates money. */
    synchronized List<Transaction> collectionBox(int slot, net.runelite.api.Item[] items, IntUnaryOperator canon,
            long at, String owner, boolean collect) {
        begin(at, owner); var old = boxes.remove(slot);
        var record = forSlot(slot); if (record == null || items == null) return rows; long coins = 0;
        for (var item : items) {
            if (item == null || item.getId() <= 0) continue; if (item.getQuantity() < 0) return rows;
            if (item.getQuantity() == 0) continue; if (item.getId() == COINS) coins = sum(coins, item.getQuantity());
            // Beyond max cash (30 September 2026): a sale can pay out in platinum tokens.
            else if (item.getId() == PLATINUM) coins = sum(coins, 1000L * item.getQuantity());
            else if (canon.applyAsInt(item.getId()) != record.itemId) return rows;
        }
        if (coins > 0) boxes.put(slot, new CollectionBox(record.offerId, coins,
                record.reserved(), record.pendingExecution(), now));
        long[] net = net(record);
        if (collect && coins == 0 && old != null && old.offer.equals(record.offerId)
                && record.side == SELL && old.quantity == record.reserved()
                && old.execution == record.pendingExecution() && now >= old.at
                && now - old.at <= COLLECTION_WINDOW_MILLIS && net != null
                && old.cash >= net[0] && old.cash <= net[1]) {
            clearAt.put(record.offerId, now); reconcilePending();
        }
        return rows;
    }

    static final long LOGIN_REPLAY_MILLIS = 2 * 60 * 1000;

    /**
     * Transient: when each offer last explained a movement (its offer id, with a "$" suffix for the
     * coin side). A placement explains one loss; a fill or cancellation explains nothing more until
     * the offer is seen again.
     */
    Map<String, Long> explained = new HashMap<>();

    /** Schema-106 pooled known-basis ledger; null keeps custody purely observational. */
    TrackedBasisLedger basis;

    // The operation in progress: each synchronized entry point sets these before it works.
    /** The operation's time. */
    long now;

    /** The session the operation books into. */
    String session = "";

    /** What the operation books: settlements and Review handoffs. */
    List<Transaction> rows = new ArrayList<>();

    /** One settle's flows not yet claimed, the parts claimed so far, and the items it may claim. */
    List<Flow> residual = flowList(),

        claimed = flowList();
    IntPredicate allowed = ANY;

    /** Starts an operation at {@code now}; it books into a fresh list. */
    void begin(long now, String id) {
        this.now = now; session = id; rows = new ArrayList<>();
    }

    /** Latest capture time of an open SELL reservation for the item; 0 when none is open. */
    synchronized long latestReservationTime(int id) {
        long latest = 0;
        for (var record : records) {
            if (record.side != SELL || record.itemId != id
                    || !record.pendingStock() && record.reservedQty() <= 0 && record.reservedBasis() <= 0) continue;
            long captured = record.basisCapturedAtEpochMillis > 0 ? record.basisCapturedAtEpochMillis
                            : record.placedAtEpochMillis;
            latest = max(latest, captured);
        }
        return latest;
    }

    /** Immutable result of one settle: residual flows still needing normal classification. */
    @AllArgsConstructor
    static class Partition {
        List<Flow> residualFlows; List<Transaction> counted, transferBookings;

        /** Physical flows consumed by custody claims; used to spend matching offer evidence. */
        List<Flow> claimedFlows;

        Transaction lastBooking() {
            var rows = counted.isEmpty() ? transferBookings : counted; return rows.isEmpty() ? null : rows.get(rows.size() - 1);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Lifecycle observation
    // ---------------------------------------------------------------------------------------
    /** Apply one offer-ledger transition; returns any Review handoff or settled rows it forced. */
    synchronized List<Transaction> observeTransition(
            OfferSlots.Transition transition, String name, long now, String id) {
        begin(now, id); observe(transition.previous, transition.current, name);
        // New execution/lifecycle evidence may complete a settlement whose cash was observed
        // first; the held batch settles as soon as it is exactly attributable.
        reconcilePending(); return rows;
    }

    /**
     * Applies one slot snapshot against the slot's baseline: exact incremental fills from the
     * cumulative counters, Modify Offer, slot reuse and identity replacement. Backwards counters,
     * value moved without quantity, or a missed placement are a desync, never a fill.
     */
    void observe(Snapshot previous, Snapshot current, String name) {
        int slot = current.slot; Side side = OfferSlots.sideOf(current.state);
        var base = baseline.remove(slot);
        if (side == null) {
            var record = touch(slot, base != null ? base : previous);
            if (record != null) {
                record.cleared = true;
                // Transient slot evidence: proves which offer a nearby collect belongs to.
                clearAt.put(record.offerId, now);
            }
            return;
        }
        var replay = base == null ? lateLoginReplay(slot, side, current) : null;
        if (replay != null) {
            loginAt.remove(replay.offerId); clearAt.remove(replay.offerId);
            replay.cleared = replay.collectedAway = false; resume(replay, current); baseline.put(slot, current);
            return;
        }
        boolean active = OfferSlots.isActive(current.state);
        if (base == null || OfferSlots.sideOf(base.state) != side || base.item != current.item) {
            if (base != null) {
                // Item/side changed mid-life without Modify evidence: retire the old lifecycle and
                // quarantine the next; never inherit prior basis or progress.
                var old = touch(slot, base);
                if (old != null) {
                    old.confidence = AMBIGUOUS; handoff(old, msg("offerIdentity"));
                }
                replaced.add(slot);
            }
            baseline.put(slot, current);
            if (base == null && previous != null && previous.state != EMPTY) {
                // A missed placement cannot be dated: quarantine the lifecycle, emit no fill.
                desync(slot, current, current, name); return;
            }
            place(slot, side, current, name); advance(slot, current, current, current.traded, current.spent);
            return;
        }
        if (active && (base.quantity != current.quantity || base.price != current.price)) {
            // A price-only Modify keeps the lifecycle and its basis; a quantity change has no exact
            // per-slice basis allocation, so realization fails closed to Review.
            boolean priceOnly = base.quantity == current.quantity;
            baseline.put(slot, new Snapshot(slot, base.state, base.item, current.quantity, base.traded,
                            priceOnly ? current.price : base.price, base.spent));
            var record = touch(slot, base);
            if (record != null && priceOnly)
                record.listedPrice = positive(current.price);
            else if (record != null) {
                record.offeredQty = positive(current.quantity); record.quantityModified = true;
            }
            return;
        }
        long quantity = current.traded - base.traded, spent = current.spent - base.spent;
        baseline.put(slot, new Snapshot(slot, base.state, base.item, base.quantity, current.traded,
                        base.price, current.spent));
        if (quantity < 0 || spent < 0 || quantity == 0 && spent > 0) {
            desync(slot, base, current, name); return;
        }
        advance(slot, base, current, quantity, spent);
    }

    /** A new offer in the slot: a duplicate replay of the live lifecycle changes nothing. */
    void place(int slot, Side side, Snapshot offer, String name) {
        var previous = forSlot(slot);
        if (previous != null && loginAt.containsKey(previous.offerId)) {
            // Its slot was empty at login: the old offer was collected while logged out.
            away(previous); previous = null;
        }
        if (previous != null && sameOffer(previous, side, offer.item, offer.quantity, offer.price)
                && !previous.cleared && !previous.isTerminal()) {
            previous.lastSeenAtEpochMillis = now; return;
        }
        if (previous != null && previous.stage == OPEN && previous.reconciled()) {
            // Settled but not yet sealed (a settlement completed by an offer transition):
            // seal it exactly as the next settle would, so it stays as history.
            previous.stage = CLOSED; previous = null;
        }
        if (previous != null && previous.cleared && previous.isTerminal() && trusted(previous, previous.side)) {
            // Owner 1.2: a finished offer collected moments before its slot is reused still waits
            // for
            // its items or coins to settle; it gives up the slot instead of going to Review.
            previous.slot = -1; previous = null;
        }
        if (previous != null)
            // Slot reuse: a new offer never inherits prior basis or progress.
            handoff(previous, msg("slotReused"));
        // A settled (CLOSED) lifecycle in this slot stays as bounded market history for its
        // booked sale; only its unrealized reservation leaves with the slot.
        releaseRemaining(latestClosed(slot)); var record = newRecord(offer, name, offer.state);
        if (replaced.remove(slot)) record.confidence = AMBIGUOUS;
        // Per-fill tax is exact only for a placement observed live from an empty slot.
        record.fillTaxExact = side == SELL && record.confidence == CustodyConfidence.CONFIRMED; records.add(record);
    }

    /** Exact fill progress, then the terminal state (a final fill lands before a cancellation). */
    void advance(int slot, Snapshot offer, Snapshot current, long quantity, long spent) {
        boolean fill = quantity > 0 && spent >= 0, terminal = OfferSlots.isTerminal(current.state);
        var record = fill || terminal ? touch(slot, offer) : null;
        if (record != null && fill) recordFill(record, quantity, spent);
        if (record != null && terminal) record.offerState = current.state.name();
    }

    /** Counters with no exact lineage: quarantine the lifecycle, or start an unbased one. */
    void desync(int slot, Snapshot offer, Snapshot current, String name) {
        var record = touch(slot, offer);
        if (record != null) {
            record.confidence = AMBIGUOUS; record.fillTaxExact = false;
        } else if (offer.item > 0) {
            record = newRecord(offer, name, current.state); record.confidence = LEGACY_UNBASED; records.add(record);
        }
    }

    /**
     * The live lifecycle the login seed cleared in this slot, when this snapshot is that same
     * offer.
     */
    GeOffer lateLoginReplay(int slot, Side side, Snapshot current) {
        var record = forSlot(slot); Long clear = record == null ? null : loginAt.get(record.offerId);
        return clear != null && now - clear <= LOGIN_REPLAY_MILLIS && sameOffer(
                                record, side, current.item, current.quantity, current.price)
                && !restarted(record, current) ? record : null;
    }

    /** A re-listed identical offer restarts its counters; it is never the saved lifecycle. */
    static boolean restarted(GeOffer record, Snapshot snapshot) {
        return snapshot.traded < record.filledQty || record.isTerminal() && OfferSlots.isActive(snapshot.state);
    }

    /**
     * Resume a persisted lifecycle; progress made while away is adopted once, never as a fill. Its
     * confidence stays as it was: a relog never makes a quarantined or unbased offer trusted.
     */
    void resume(GeOffer existing, Snapshot snapshot) {
        existing.lastSeenAtEpochMillis = now; existing.offerState = snapshot.state.name();
        if (snapshot.traded != existing.filledQty)
            // Fills while logged out were never observed at their own prices.
            existing.fillTaxExact = false;
        existing.filledQty = snapshot.traded; existing.spentGp = positive(snapshot.spent);
    }

    /** A new lifecycle for the offer in this snapshot's slot, last seen in {@code state}. */
    GeOffer newRecord(Snapshot offer, String name, GrandExchangeOfferState state) {
        var record = new GeOffer(uuid(), offer.slot, OfferSlots.sideOf(offer.state), offer.item,
                        name, offer.quantity, offer.price, now, string(session));
        record.lastSeenAtEpochMillis = now; record.offerState = state.name(); return record;
    }

    /**
     * Record the login slot baseline: resume a persisted matching lifecycle, quarantine active
     * offers first seen now as legacy/unbased, and hand off records whose slot changed identity.
     */
    synchronized List<Transaction> seedSlots(Map<Integer, Snapshot> snapshots, long now, String id) {
        begin(now, id); baseline.clear(); boxes.clear(); loginAt.clear();
        for (var snapshot : snapshots.values()) {
            var existing = forSlot(snapshot.slot);
            if (snapshot.state == EMPTY) {
                // An offer cleared here before the logout or hop still settles here.
                if (existing == null) existing = latestClosed(snapshot.slot);
                else if (!existing.cleared || existing.collectedAway) {
                    existing.collectedAway = true; loginAt.put(existing.offerId, now);
                }
                if (existing != null) {
                    existing.cleared = true; existing.lastSeenAtEpochMillis = now; clearAt.put(existing.offerId, now);
                }
                continue;
            }
            Side side = OfferSlots.sideOf(snapshot.state); if (side == null) continue;
            // Pre-existing progress is only a baseline, never replayed as a fill.
            baseline.put(snapshot.slot, snapshot);
            if (existing != null && sameOffer(existing, side, snapshot.item, snapshot.quantity,
                            snapshot.price) && !restarted(existing, snapshot)) {
                resume(existing, snapshot); continue;
            }
            if (existing != null) away(existing);
            var record = newRecord(snapshot, "", snapshot.state); record.confidence = LEGACY_UNBASED;
            record.filledQty = snapshot.traded; record.spentGp = positive(snapshot.spent); records.add(record);
        }
        return rows;
    }

    /**
     * True when every moved flow is explained by a recent offer: an item or coins lost shortly
     * after a sell or buy was placed, an item or coins gained after a buy or sell filled, or the
     * other side's cancellation. Custody is authoritative for GE economics, so a movement it could
     * not claim but an offer explains is Grand Exchange activity for Review, never counted.
     */
    synchronized boolean explains(List<Flow> flows, long now) {
        this.now = now; boolean moved = false;
        for (Flow flow : flows)
            if (flow.quantity != 0) {
                moved = true; if (explaining(flow) == null) return false;
            }

        return moved;
    }

    /** Spends the offer evidence behind these movements: one offer side explains one movement. */
    synchronized void spend(List<Flow> flows, long now) {
        this.now = now;
        for (Flow flow : flows) {
            String key = flow.quantity == 0 ? null : explaining(flow); if (key != null) explained.put(key, now);
        }
    }

    /** A bond BUY lists the tradeable id and delivers the untradeable one (Wiki 13190/13192). */
    static int custodyItem(GeOffer record) {
        return record.side == BUY && record.itemId == OSRS_BOND ? OSRS_BOND_UNTRADEABLE : record.itemId;
    }

    String explaining(Flow flow) {
        boolean coins = flow.item == COINS;
        for (var record : records) {
            boolean buy = record.side == BUY; String key = record.offerId + (coins ? "$" : "");
            Long spent = explained.get(key); long seen = record.lastSeenAtEpochMillis;
            // Only a live placement moves coins (buy) or items (sell) out of the inventory; a
            // finished
            // fill of the same side or a cancellation of the other brings them back. Passive
            // progress on an open offer explains nothing.
            boolean fits = flow.cost() ? buy == coins && spent == null && !record.unbased() && !record.isCancelled()
                                    && now - record.placedAtEpochMillis <= PLACEMENT_WINDOW_MILLIS
                            : (spent == null || spent < seen) && now - seen <= COLLECTION_WINDOW_MILLIS && (buy != coins
                                            ? record.filledQty > 0 && record.isTerminal() : record.isCancelled());
            if (fits && (coins || record.itemId == flow.item || custodyItem(record) == flow.item)) return key;
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------
    // Flow-level custody claims and canonical realization
    // ---------------------------------------------------------------------------------------
    /**
     * Claim custody flows from one stabilized settle. {@code itemAllowed} excludes items already
     * owned by a stronger per-item evidence source (consume/drop/loot) without weakening the
     * whole-transaction safety gates. {@code collectionEvidence} is a bounded player Collect
     * interaction; without it, collection claims need an observed terminal offer state.
     */
    synchronized Partition partition(List<Flow> incoming, long now, String id, IntPredicate itemAllowed,
            boolean collection) {
        begin(now, id); var transfers = new ArrayList<Transaction>(); residual = copiedList(incoming);
        claimed = new ArrayList<>(); allowed = itemAllowed;
        // A settlement held from an earlier collect may now be exactly attributable.
        reconcilePending(); claimSellPlacements(); claimBuyReserves();
        if (collection) {
            var gains = incoming(residual, allowed);
            for (var record : records)
                if ((gains.containsKey(record.itemId) || gains.containsKey(custodyItem(record)))
                        && (record.side == BUY || record.isCancelled())
                        || gains.containsKey(COINS) && (record.side == SELL && record.reserved() > 0
                                || record.side == BUY && record.isCancelled())) collected(record);
        }
        claimBuyRefunds(collection); claimSellReturns(); claimBuyCollections(collection); claimSellSettlements(collection);
        if (!claimed.isEmpty())
            transfers.add(new Transaction(now, TransactionType.TRANSFER, TRANSFER,
                            msg("geCustody"), "Market", false, copiedList(claimed),
                            CONFIRMED, msg("geNeutral"), null));
        // A hold created by this settle retries immediately: a batch whose evidence is complete
        // fails closed at once instead of waiting invisibly for a later event.
        reconcilePending();
        for (var open : records)
            if (open.stage == OPEN && open.reconciled()) open.stage = CLOSED;

        return new Partition(residual, rows, transfers, claimed);
    }

    /** Prune reconciled lifecycles, expire terminal-unobserved ones and cap stale records. */
    synchronized List<Transaction> maintenance(long now, String id) {
        begin(now, id); prune();
        // A held settlement retries and, once its evidence is exhausted or its bounded
        // lifecycle expires, fails closed to Review with the observed cash preserved.
        reconcilePending(); return rows;
    }

    void prune() {
        int closed = 0;
        for (var record : records)
            if (record.stage == CLOSED) closed++;

        // Settled history is bounded: the oldest closed lifecycles leave first.
        for (; closed > MAX_CLOSED_RECORDS; closed--) {
            var first = oldestClosed(); releaseRemaining(first); records.remove(first);
        }
        for (int index = records.size() - 1; index >= 0; index--) {
            var record = records.get(index); if (record.stage == CLOSED) continue;
            if (record.reconciled()) {
                record.stage = CLOSED; continue;
            }
            // Expire by grace only a slot that is CLEARED and whose cash was never seen (a
            // collect-to-bank and similar). A SELL whose slot still shows the terminal state is
            // still in GE custody: its coins may be collected much later, so it must not expire by
            // time. BUY keeps its existing pattern (reported, not changed).
            boolean clear = record.cleared;
            // Owner 2026-10-09: Collect to bank (or a collect while GP Manager was away) settles
            // from
            // the offer itself instead of waiting for Review; held collect cash may still be its
            // own.
            if (clear && now - record.lastSeenAtEpochMillis > LOGIN_REPLAY_MILLIS
                    && loginAt.containsKey(record.offerId)) {
                away(record); continue;
            }
            if (clear && !loginAt.containsKey(record.offerId)
                    && now - record.lastSeenAtEpochMillis > BANK_COLLECT_MILLIS && cash <= 0
                    && banked(record))
                continue;
            boolean buyTerminal = record.side != SELL && record.isTerminal() && !record.unbased();
            if ((clear || buyTerminal) && now - record.lastSeenAtEpochMillis > UNOBSERVED_GRACE_MILLIS)
                handoff(record, msg("settlementObserved"));
        }
        clearAt.values().removeIf(at -> now - at > UNOBSERVED_GRACE_MILLIS);
        // Spent offer evidence goes with its offer.
        var live = new HashSet<String>(); for (var record : records) live.add(record.offerId);
        explained.keySet().removeIf(key -> !live.contains(key.replace("$", "")));
    }

    // -- Custody claims: each settle offers its flows to the lifecycles, oldest placement first --
    /** One lifecycle's claim on a flow: the magnitude it takes, or 0 to pass. */
    interface ClaimStep {
        long claim(GeOffer record, Flow flow, long remaining);
    }

    /** Offers one side's trusted lifecycles a gain or loss of coins or items, oldest first. */
    void claimFlows(Side side, boolean gain, boolean coins, ClaimStep step) {
        claim(residual, flow -> (gain ? flow.gain() : flow.cost())
                                && flow.item == COINS == coins && (coins || allowed.test(flow.item)),
                (flow, size) -> {
                    long remaining = size;
                    for (var record : byPlacement())
                        if (remaining > 0 && trusted(record, side)
                                && (coins || record.itemId == flow.item || custodyItem(record) == flow.item))
                            remaining -= positive(step.claim(record, flow, remaining));

                    return size - remaining;
                });
    }

    /** A lifecycle observed from its placement with no identity desync. */
    static boolean trusted(GeOffer record, Side side) {
        return record.side == side && !record.unbased() && record.confidence != AMBIGUOUS;
    }

    /** SELL placement principal (item loss). */
    void claimSellPlacements() {
        claimFlows(SELL, false, false, (record, flow, remaining) -> {
                    long claim = min(remaining, record.offeredQty);
                    if (record.isCancelled() || record.cleared || record.capturedQty > 0
                            || now - record.placedAtEpochMillis > PLACEMENT_WINDOW_MILLIS || claim <= 0)
                        return 0;
                    captureBasis(record, flow);
                    if (basis != null) {
                        var reservation = basis.reserve(record.itemId, claim);
                        record.reservedTrackedQty = positive(sum(record.reservedTrackedQty, reservation.trackedQty));
                        record.reservedTrackedBasisGp =
                                positive(sum(record.reservedTrackedBasisGp, reservation.basisGp));
                    }
                    record.capturedQty = claim; record.lastSeenAtEpochMillis = now; claimed.add(flow.part(claim));
                    return claim;
                });
    }

    /** BUY observed reserve (coin loss). */
    void claimBuyReserves() {
        var gainItems = incoming(residual, ANY).keySet();
        claimFlows(BUY, false, true, (record, flow, remaining) -> {
                    long reserve = positive(record.offeredQty * record.listedPrice);
                    // Owner 1.2: an offer placed, bought and collected inside one settle shows only
                    // its exact spend, the reserve with its change already back; the purchase books
                    // it, never Review.
                    boolean instant = gainItems.contains(record.itemId) && record.isTerminal()
                                    && remaining == record.spentGp;
                    long claim = instant ? remaining : min(remaining, reserve);
                    // A same-item acquisition alongside the coin loss is a purchase, not a reserve:
                    // leave the mixed movement to the normal classification path. An offer that
                    // filled at once still took these coins; only a cancelled one kept none.
                    if (record.isCancelled() || record.cleared && !instant || record.reserveGp > 0
                            || now - record.placedAtEpochMillis > PLACEMENT_WINDOW_MILLIS
                            || !instant && gainItems.contains(record.itemId) || claim <= 0)
                        return 0;
                    record.reserveGp = instant ? reserve : positive(claim); if (instant) record.refundedGp = positive(reserve - claim);
                    record.lastSeenAtEpochMillis = now; claimed.add(flow.part(claim, 1, FACE_VALUE)); return claim;
                });
    }

    /** BUY refund (coin gain). */
    void claimBuyRefunds(boolean collection) {
        var items = incoming(residual, ANY);
        claimFlows(BUY, true, true, (record, flow, remaining) -> {
                    boolean terminal = record.isTerminal() || record.cleared;
                    long refundRoom = positive((terminal ? record.offeredQty : record.filledQty)
                                    * record.listedPrice - record.spentGp) - record.refundedGp;
                    boolean collected = collection && !record.quantityModified
                                    && (max(amount(items, record.itemId), amount(items, custodyItem(record)))
                                                    >= record.reserved()
                                            && record.reserved() > 0 || remaining == refundRoom
                                            && record.collectedQty == record.filledQty
                                            && Math.abs(now - record.lastSeenAtEpochMillis) <= 2 * SLOT_WINDOW_MILLIS);
                    // An offer paid from the bank (the GE draws there once the pack runs out) never
                    // showed its reserve, yet its change still lands in the pack: as the slot
                    // clears, up to the exact change owed is the player's own coins coming back
                    // (owner live 2026-10-16: two bank-paid buys sent their change to Review).
                    Long clear = clearAt.get(record.offerId);
                    boolean bankPaid = record.reserveGp == 0 && (collected || clear != null
                                    && Math.abs(now - clear) <= 2 * SLOT_WINDOW_MILLIS);
                    long claim = min(remaining, min(positive(refundRoom),
                                            bankPaid ? refundRoom : record.reserveOutstanding()));
                    // A live collection can return price improvement on filled units while the
                    // unfilled reserve stays in custody. Terminal offers may return all change.
                    if (!(terminal || collected) || claim <= 0) return 0; record.refundedGp = positive(sum(record.refundedGp, claim));
                    claimed.add(flow.part(claim, 1, FACE_VALUE)); return claim;
                });
    }

    /** SELL cancellation return (item gain). */
    void claimSellReturns() {
        claimFlows(SELL, true, false, (record, flow, remaining) -> {
                    long claim = min(remaining, record.heldQty()); if (!(record.isCancelled() || record.cleared) || claim <= 0) return 0;
                    releaseShare(record, claim); record.returnedQty = sum(record.returnedQty, claim);
                    // The returned principal is neutral at its frozen custody basis, never the
                    // current live quote; with no frozen basis it stays the observed movement.
                    claimed.add(record.basisUnitPrice > 0 ? flow.part(
                                            claim, record.basisUnitPrice, record.getBasisSource()) : flow.part(claim));
                    return claim;
                });
    }

    /** BUY collection (item gain). */
    void claimBuyCollections(boolean collection) {
        claimFlows(BUY, true, false, (record, flow, remaining) -> {
                    long claim = min(remaining, record.reserved());
                    // Passive progress alone never turns a same-item delta into a collection.
                    if (record.quantityModified || claim <= 0
                            || !(collection || record.isTerminal() || record.cleared))
                        return 0;
                    if (record.basisSource == null) captureBasis(record, flow);
                    record.collectedQty = positive(sum(record.collectedQty, claim));
                    record.collectedValueGp = sum(record.collectedValueGp,
                                    multiply(claim, flow.unitPrice));
                    record.lastSeenAtEpochMillis = now; claimed.add(flow.part(claim));
                    if (record.collectedQty < record.filledQty)
                        // Partial collection: execution chunks are not attributable; fail closed.
                        record.collectionAmbiguous = true;
                    else
                        bookBuySettlement(record);
                    return claim;
                });
    }

    void bookBuySettlement(GeOffer record) {
        long quantity = record.collectedQty - record.settledQty, execution = record.pendingExecution();
        if (quantity <= 0) return; boolean clean = cleanSettlement(record);
        // Exact NEW-model BUY: acquired coverage carries the measured execution spend. A bond is bought
        // like any item and arrives untradeable; its redemption or gift is where its cost counts.
        var flows = flowList(); var item = itemFlow(record, quantity, execution / quantity, execution);
        item.item = custodyItem(record); flows.add(item); flows.add(coins(-execution));
        var row = buildMarket(flows, clean, clean, msg("gePurchase"));
        settled(record, row, quantity, execution, execution);
        record.collectedQty = positive(record.settledQty); record.collectedValueGp = 0;
    }

    Flow coins(long amount) {
        return new Flow(COINS, "Coins", amount, 1, amount, FACE_VALUE, now);
    }

    /** One flow of the offer's item, carrying its frozen basis source and capture time. */
    static Flow itemFlow(GeOffer record, long quantity, long price, long value) {
        return new Flow(record.itemId, record.itemName, quantity, price, value, record.getBasisSource(),
                record.basisCapturedAtEpochMillis);
    }

    /** An uncounted Market row left for Review: the observed movement, never a guess. */
    Transaction reviewRow(List<Flow> flows, String reason) {
        return new Transaction(now, UNCERTAIN, MARKET, "Grand Exchange", "Market", false, flows, Confidence.UNCERTAIN,
                reason, null);
    }

    /** Claims a whole coin gain as held or settled GE cash. */
    long claimWhole(Flow flow) {
        claimed.add(flow.part(flow.value, 1, FACE_VALUE)); return flow.value;
    }

    // -- SELL collection (coin gain) ----------------------------------------------------------
    /**
     * Attribute one observed collect coin movement to exactly the sold offers it proves.
     *
     * <p>Exact integer equality only: a movement settles a subset when the subset's observed
     * after-tax nets sum to it exactly. The slot-clear set is the primary proof; otherwise the
     * unique matching subset is confirmed by slot evidence within the SLOT WINDOW or, with no
     * contradicting evidence, at the window end. Ties and unprovable evidence fail closed: only
     * slot-evidenced candidates may be quarantined, and every other candidate stays pending for a
     * later exact collect.
     */
    void claimSellSettlements(boolean collection) {
        if (!collection) {
            // A coin gain with no Collect interaction settles a waiting sale only when it is
            // that whole sale, less at most its tax.
            claimSales(); return;
        }
        claim(residual, flow -> flow.gain() && flow.item == COINS, (flow, size) -> {
                    long cash = flow.value; var options = settlementCandidates(true);
                    // Coins may arrive before the offer's execution/terminal evidence. A live
                    // Collect interaction with a captured sell lifecycle still pending is that
                    // settlement's cash; with no candidate yet it is held below (an empty set
                    // attributes to NONE) instead of letting an unresolved Coins row reach Review.
                    // The bounded hold retries as offer evidence arrives.
                    if (options.isEmpty() && (cash <= 0 || !pendingSell()))
                        return 0;
                    var owner = attribute(options, cash, now);
                    if (owner.kind == SETTLE) {
                        settleAttributed(owner.records, cash); return claimWhole(flow);
                    }
                    if (!owner.contradicted) {
                        // Exact-but-unconfirmed, tied, or unmatched evidence: retain the observed
                        // cash
                        // transiently. The SLOT WINDOW gathers the slot evidence a fail-closed
                        // decision
                        // needs; while a sell can still receive execution evidence, the existing
                        // long hold
                        // keeps waiting for it.
                        holdCash(cash);
                        unproven |= owner.kind == AttributionKind.NONE
                                        && pendingSell();
                        return claimWhole(flow);
                    }
                    // No exact attribution: only candidates with slot evidence in this interaction
                    // may be
                    // quarantined. The unattributed movement follows the ordinary path to one
                    // uncounted
                    // Review Coins row; every other candidate stays pending for a later exact
                    // collect.
                    quarantineAmbiguous(owner.quarantine); return 0;
                });
    }

    /**
     * Booking for a coin gain with no Collect interaction (the click fell outside its window): only
     * a gain between the whole waiting sale and that sale less its largest possible tax settles it.
     */
    void claimSales() {
        claim(residual, flow -> flow.gain() && flow.item == COINS, (flow, size) -> {
                    var options = settlementCandidates(false); long totalExecution = 0;
                    for (var record : options)
                        totalExecution = sum(totalExecution, record.pendingExecution());
                    long original = flow.value;
                    // Without a Collect click only the whole waiting sale, less at most its GE
                    // tax (2%), can explain the coins: an alch or a shop sale never claims it.
                    if (original > totalExecution || original < totalExecution - totalExecution / 50) return 0;
                    long claimCash = min(original, totalExecution); if (claimCash <= 0) return 0;
                    if (options.size() > 1) {
                        boolean clean = true, allKnownCoverage = true; long tax = 0;
                        for (var record : options) {
                            clean &= cleanSettlement(record); allKnownCoverage &= record.reservedQty() >= record.reserved();
                            // Candidate fee only: the aggregate observed cash must confirm the
                            // exact combined gap.
                            tax = sum(tax, positive(pendingSellTax(record)));
                        }
                        boolean taxAttributed = tax > 0 && totalExecution - claimCash == tax;
                        if (!taxAttributed && (tax > 0 || claimCash != totalExecution) && !allKnownCoverage) {
                            clean = false; for (var record : options) record.collectionAmbiguous = true;
                        }
                        // An exact batch books each offer's after-tax share of the observed cash;
                        // any
                        // other batch books gross execution and an adjustment row for the
                        // difference.
                        long allocated = 0;
                        for (var record : options) {
                            long quantity = record.reserved(), execution = record.pendingExecution();
                            if (quantity > 0 && execution > 0) {
                                long cash = taxAttributed ? execution - positive(pendingSellTax(record)) : execution;
                                bookSellSettlement(record, quantity, cash, execution, clean); allocated = sum(allocated, cash);
                            }
                        }
                        if (claimCash != allocated)
                            // Legacy (non-collect) batch adjustment row; collect interactions never
                            // produce one.
                            rows.add(new Transaction(now, clean ? TRADE : UNCERTAIN, MARKET,
                                            msg("geSettlement"), "Market", clean,
                                            singletonList(coins(claimCash - allocated)),
                                            clean ? CONFIRMED : Confidence.UNCERTAIN,
                                            msg("geBatch"), null));
                    } else {
                        var record = options.get(0); long execution = record.pendingExecution();
                        boolean over = original > execution; record.collectionAmbiguous = record.collectionAmbiguous || over;
                        bookSellSettlement(record, record.reserved(), over ? execution : claimCash, execution,
                                !over && cleanSettlement(record));
                    }
                    claimed.add(flow.part(claimCash, 1, FACE_VALUE)); return claimCash;
                });
    }

    /**
     * Book one exactly attributed subset at each offer's own observed after-tax cash. A sale that
     * filled at several prices in one observed chunk has a tax range, not one tax: the observed
     * cash proves the subset's total, so each such offer pays its lowest possible tax plus, in
     * order, the rest up to its highest (owner 2026-10-08: a big mixed-price sale is no Review).
     */
    void settleAttributed(List<GeOffer> records, long cash) {
        boolean clean = true;
        for (var record : records) {
            clean &= cleanSettlement(record); long[] net = net(record);
            if (net != null) cash -= net[1];
        }
        // The tax beyond every offer's lowest possible tax.
        cash = -cash;
        for (var record : records) {
            long[] net = net(record);
            // An offer with no execution is never part of a match; the attribution excluded it.
            if (net == null) continue; long execution = record.pendingExecution(), extra = positive(min(net[1] - net[0], cash));
            cash -= extra; long tax = execution - net[1] + extra;
            if (pendingSellTax(record) < 0) {
                // The cash proved it: later reads (the receipt, a restart) see this exact tax.
                record.fillTaxExact = true; record.fillTaxGp = sum(record.settledTaxGp, tax);
            }
            bookSellSettlement(record, record.reserved(), execution - tax, execution, clean);
        }
    }

    /**
     * The pending execution's possible after-tax cash {lowest, highest}, or null with no execution.
     * Exact when its tax is proven; otherwise each item paid price/50 rounded down, so the stack
     * paid at most gross/50 and at least (gross - 49 per item)/50 (below the 5M per-item cap).
     */
    static long[] net(GeOffer record) {
        long execution = record.pendingExecution(), quantity = record.reserved(), tax = pendingSellTax(record);
        if (execution <= 0 || quantity <= 0) return null; if (tax >= 0) return new long[] {execution - tax, execution - tax};
        long least = execution >= 250_000_000 ? 0 : positive((execution - 49 * quantity + 49) / 50);
        return new long[] {execution - execution / 50, execution - least};
    }

    /**
     * Fail closed on the slot-evidenced candidates only. The quarantine booking keeps today's shape
     * (uncounted, collectionAmbiguous, the booked allocation recorded) while the observed cash
     * itself is never assigned to a lifecycle it was not proven part of.
     */
    void quarantineAmbiguous(List<GeOffer> eligible) {
        for (var record : eligible) {
            long quantity = record.reserved(), execution = record.pendingExecution();
            if (quantity > 0 && execution > 0) {
                record.collectionAmbiguous = true; bookSellSettlement(record, quantity, execution, execution, false);
            }
        }
    }

    /** Candidate sell lifecycles for one coin gain; the retry needs durable terminal evidence. */
    List<GeOffer> settlementCandidates(boolean collection) {
        return matching(byPlacement(), record -> record.side == SELL && record.reserved() > 0
                && record.capturedQty > 0 && record.settleable() >= record.reserved() && !record.unbased()
                && (collection || record.isTerminal() || record.cleared));
    }

    /** Retain observed collect cash transiently; the SLOT WINDOW anchors on the first hold. */
    void holdCash(long cash) {
        if (cash <= 0) return; if (this.cash <= 0) heldAt = now;
        this.cash = sum(this.cash, cash); cashAt = max(cashAt, now);
    }

    /** Exact attribution of one collect coin movement over the current candidate set. */
    Attribution attribute(List<GeOffer> options, long cash, long coinAt) {
        int size = options.size(); var eligible = new ArrayList<GeOffer>();
        if (size <= 0 || size > MAX_SETTLEMENT_CANDIDATES)
            return new Attribution(AttributionKind.NONE, eligible, eligible, false);
        // Each candidate's possible after-tax cash: one value, or a range for a mixed-price fill.
        long[][] nets = new long[size][]; var clear = new ArrayList<Integer>();
        for (int index = 0; index < size; index++) {
            var record = options.get(index); nets[index] = net(record); Long clearedAt = clearAt.get(record.offerId);
            // The candidate's slot cleared within the SLOT WINDOW of the coin gain.
            if (clearedAt != null && Math.abs(clearedAt - coinAt) <= SLOT_WINDOW_MILLIS) {
                clear.add(index); eligible.add(record);
            }
        }
        // Apply slot evidence only after enumerating every subset within the eight-offer bound.
        var matches = matchingSubsets(nets, cash, 1 << size); List<Integer> match = null;
        var kind = AttributionKind.NONE; boolean conflicts = false;
        if (matches.contains(clear)) {
            // §2.1: the slot-clear set is the primary proof; a single candidate has no competing
            // explanation either, so neither needs slot confirmation (an amount it cannot have
            // received, or an execution with no known price, still fails closed below).
            kind = SETTLE; match = clear;
        } else if (size == 1 && !matches.isEmpty()) {
            kind = SETTLE; match = matches.get(0);
        } else if (matches.size() > 1) {
            // §2.3: slot evidence may still select exactly one matching subset.
            matches.removeIf(subset -> !subset.containsAll(clear)); conflicts = !clear.isEmpty() && matches.isEmpty();
            kind = conflicts ? AttributionKind.NONE
                            : matches.size() == 1 ? SETTLE : TIE;
            match = matches.size() == 1 ? matches.get(0) : null;
        } else if (matches.size() == 1) {
            // A unique match settles when the slot-clear set is exactly it, needs confirmation
            // while
            // no slot outside it cleared, and §2.2: a slot cleared outside it contradicts it.
            match = matches.get(0); conflicts = !match.containsAll(clear);
            kind = match.equals(clear) ? SETTLE
                            : conflicts ? AttributionKind.NONE : CONFIRM;
        }
        return new Attribution(kind, list(match == null || conflicts ? List.of() : match,
                        options::get), eligible, conflicts);
    }

    /**
     * Subset enumeration over candidates with a known execution: a subset matches when the cash
     * lies within its summed {lowest, highest} after-tax cash. Saturating sums, deterministic index
     * order, at most {@code limit} matches. Returns empty when the candidate bound is exceeded, so
     * an over-full interaction can never be enumerated.
     */
    static List<List<Integer>> matchingSubsets(long[][] nets, long cash, int limit) {
        int size = nets == null ? 0 : nets.length; var matches = new ArrayList<List<Integer>>();
        if (size <= 0 || size > MAX_SETTLEMENT_CANDIDATES)
            return matches;
        for (int mask = 1; mask < 1 << size && matches.size() < limit; mask++) {
            long least = 0, most = 0; var subset = new ArrayList<Integer>();
            for (int index = 0; index < size && least >= 0; index++)
                if ((mask & 1 << index) != 0) {
                    subset.add(index); least = nets[index] == null ? -1 : sum(least, nets[index][0]);
                    most = nets[index] == null ? most : sum(most, nets[index][1]);
                }

            if (least >= 0 && least <= cash && cash <= most) matches.add(subset);
        }
        return matches;
    }

    /** Attribution outcome kinds; names are conceptual, not persisted. */
    enum AttributionKind {
        /** An exact subset is proven and may be settled now. */
        SETTLE,
        /** The unique exact subset needs slot confirmation (or the window end). */
        CONFIRM,
        /** More than one exact subset matches; slot evidence must select one. */
        TIE,
        /** No exact attribution exists. */
        NONE
    }

    @AllArgsConstructor
    static class Attribution {
        AttributionKind kind; List<GeOffer> records, quarantine;
        boolean contradicted;
    }

    /** True while an observed sell lifecycle can still receive execution/terminal evidence. */
    boolean pendingSell() {
        return any(records, record -> record.side == SELL && record.capturedQty > 0 && !record.unbased()
                && !record.isTerminal() && !record.cleared);
    }

    /**
     * Retry a held collect under the one attribution law. An exact subset settles as soon as the
     * evidence (or the SLOT WINDOW) confirms it; a tie, a contradiction, or exhausted evidence
     * fails closed to Review, never distributed by guesswork.
     */
    void reconcilePending() {
        if (cash <= 0) return;
        // The held cash IS a collect interaction, so the retry sees the same candidate set that
        // interaction saw (still-active sells whose fills may still be collected included).
        Attribution owner = attribute(settlementCandidates(true), cash,
                        heldAt);
        boolean windowClosed = now - heldAt > SLOT_WINDOW_MILLIS;
        // §2.2b: a unique match settles at the window end with no contradicting slot evidence; the
        // existing long hold also books as soon as its missing execution evidence completes.
        if (owner.kind == SETTLE || owner.kind == CONFIRM
                        && (windowClosed || unproven)) {
            settleAttributed(owner.records, cash); clearPending(); return;
        }
        // §2.2 contradiction, §2.3 an unresolved tie, and §2.4/§3 exhausted evidence fail closed:
        // only slot-evidenced candidates are quarantined and the held cash goes to Review.
        if (owner.contradicted || now - cashAt > UNOBSERVED_GRACE_MILLIS
                || owner.kind == TIE && windowClosed || owner.kind == AttributionKind.NONE
                        && (unproven ? !pendingSell() : windowClosed)) {
            quarantineAmbiguous(owner.quarantine); releasePendingSettlement();
        }
    }

    /** Releases a held collect to Review at the time it was held; returns that row. */
    synchronized List<Transaction> releaseHeldCollect() {
        begin(cashAt, session); releasePendingSettlement(); return rows;
    }

    /** Fail closed: the observed cash stays visible as an uncounted Review row, never guessed. */
    void releasePendingSettlement() {
        long held = cash; clearPending(); if (held <= 0) return;
        rows.add(reviewRow(singletonList(coins(held)), msg("geCollection")));
    }

    void bookSellSettlement(GeOffer record, long quantity, long cash, long execution, boolean clean) {
        long remaining = record.reservedQty(), remainingBasis = record.reservedBasis(),
            known = min(quantity, remaining), basis = BasisMath.shareOf(remaining, remainingBasis, known),
            proceeds = BasisMath.shareOf(quantity, cash, known);
        boolean fullyUnknown = known <= 0; record.consumedTrackedQty = sum(record.consumedTrackedQty, known);
        record.consumedTrackedBasisGp = sum(record.consumedTrackedBasisGp, basis);
        if (this.basis != null)
            // The realized quantity left ownership even when it carried no known coverage; the
            // per-item realization fence always advances.
            this.basis.fenceRealized(record.itemId, now);
        // The one shared accepted-tax predicate: this settlement's observed gross execution against
        // its actual Received cash. Only an exact per-item match counts; the tax is never inferred
        // from a reference, a guide price or a difference.
        long tax = pendingSellTax(record),
            acceptedTax = fullyUnknown && clean && tax > 0 && execution - cash == tax ? tax
                        : 0;
        boolean costOnly = acceptedTax > 0; var flows = flowList();
        if (costOnly) {
            // Fully unknown prior value with a PROVEN tax: the departing item flow is valued at
            // the observed gross execution (not at the frozen reference) against the observed
            // Received cash, so the counted automatic Net is exactly -accepted tax — a market
            // trade cost. Result stays unknown: the accumulator below receives no result here, and
            // a later correction replaces this contribution instead of stacking on it.
            long unit = execution / quantity; flows.add(itemFlow(record, -quantity, unit, -execution)); flows.add(coins(cash));
        } else if (fullyUnknown) {
            // Unknown coverage without a proven tax: the full observed movement stays as
            // liquidation evidence and is Net-neutral. It is never a fake cost and never
            // automatic profit.
            flows.add(itemFlow(record, -quantity, record.basisUnitPrice, -record.basisFor(quantity))); flows.add(coins(cash));
        } else {
            if (basis > 0) {
                long unit = known > 0 ? basis / known : 0; flows.add(itemFlow(record, -quantity, unit, -basis));
            }
            flows.add(coins(proceeds));
        }
        String reason = costOnly ? msg("saleProven") : fullyUnknown
                                ? msg("saleSettled") : known < quantity
                                        ? msg("saleQuantity") : msg("grandExchangeSale");
        // Counted when the lifecycle is exact AND either the realized quantity carries known
        // coverage
        // or the actual GE tax is proven. An unknown sale without a proven tax stays an exact,
        // uncounted evidence row (liquidation cash retained, Net-neutral, never a Review nag).
        if (tax >= 0) record.settledTaxGp = positive(sum(record.settledTaxGp, tax));
        record.realizedResultGp = sum(record.realizedResultGp, proceeds - basis);
        settled(record, buildMarket(flows, clean, clean && (!fullyUnknown || costOnly), reason), quantity,
                execution, cash);
    }

    /** Books one settlement row and advances the offer's settled counters by what it booked. */
    void settled(GeOffer record, Transaction row, long quantity, long execution, long cash) {
        rows.add(row); record.settledQty = sum(record.settledQty, quantity);
        record.settledExecutionGp = sum(record.settledExecutionGp, execution);
        record.settledCashGp = sum(record.settledCashGp, cash); record.setSettlementId(row.id());
    }

    /** Return an unconsumed reservation share when custody returns or retires a lifecycle. */
    void releaseShare(GeOffer record, long returnedQty) {
        if (returnedQty <= 0) return; long remaining = record.reservedQty(), remainingBasis = record.reservedBasis();
        if (remaining <= 0 && remainingBasis <= 0) return;
        long released = min(returnedQty, remaining),
            releaseBasis = BasisMath.shareOf(remaining, remainingBasis, released);
        if (basis != null && (released > 0 || releaseBasis > 0))
            basis.release(record.itemId, released, releaseBasis);
        record.reservedTrackedQty = positive(max(record.consumedTrackedQty, record.reservedTrackedQty - released));
        record.reservedTrackedBasisGp = positive(max(record.consumedTrackedBasisGp,
                                record.reservedTrackedBasisGp - releaseBasis));
    }

    /** Release every still-unrealized reserved share when a lifecycle leaves custody state. */
    void releaseRemaining(GeOffer record) {
        if (record == null) return; releaseShare(record, max(record.reservedQty(), record.reservedBasis()));
    }

    // ---------------------------------------------------------------------------------------
    // Builders
    // ---------------------------------------------------------------------------------------
    /**
     * One Market row: exact lifecycles book as trades, anything else stays uncounted for review.
     */
    Transaction buildMarket(List<Flow> flows, boolean exact, boolean countsInNet, String reason) {
        return new Transaction(now, exact ? TRADE : UNCERTAIN, MARKET, "Grand Exchange", "Market", countsInNet, flows,
                exact ? CONFIRMED : Confidence.UNCERTAIN, exact ? reason : reason
                                + msg("lifecycleEvidence") + msg("staysUncounted"), null);
    }

    boolean cleanSettlement(GeOffer record) {
        if (record.unbased() || record.confidence == AMBIGUOUS || record.quantityModified
                || record.collectionAmbiguous || !record.hasFrozenBasis())
            return false;
        if (record.originSessionId.isEmpty() && !empty(session))
            record.originSessionId = string(session);
        // Owner policy (2026-09-24, replaces PRE-R5B 11.19): a settlement books in the session
        // where it is collected; the listing session is kept for the "Listed during" label only
        // and is never rewritten.
        return true;
    }

    /**
     * Accumulate one observed fill. A SELL chunk's tax is exact only when its own per-item price is
     * proven (spent divisible by quantity, or an exempt item); otherwise per-fill tax is lost for
     * this lifecycle and the uniform rule over the total remains the only proof.
     */
    static void recordFill(GeOffer record, long quantity, long spent) {
        record.filledQty = sum(record.filledQty, quantity); record.spentGp = sum(record.spentGp, spent);
        if (record.side != SELL || !record.fillTaxExact || quantity <= 0) return;
        long chunkTax = GeTaxRule.provableTax(record.itemId, spent, quantity);
        if (chunkTax < 0) {
            record.fillTaxExact = false; return;
        }
        record.fillTaxGp = positive(sum(record.fillTaxGp, chunkTax));
    }

    /**
     * The exact GE tax of a SELL's pending (filled, unsettled) execution, or {@link
     * GeTaxRule#UNPROVABLE_TAX}. Per-fill tax wins when every fill was observed at a provable
     * price, since fills at different prices pay different per-item tax; otherwise the uniform rule
     * over the pending total applies.
     */
    static long pendingSellTax(GeOffer record) {
        long execution = record.pendingExecution(), quantity = record.reserved();
        if (record.fillTaxExact && execution > 0 && quantity > 0) {
            long tax = record.fillTaxGp - record.settledTaxGp; return tax >= 0 && tax <= execution ? tax : GeTaxRule.UNPROVABLE_TAX;
        }
        return GeTaxRule.provableTax(record.itemId, execution, quantity);
    }

    // ---------------------------------------------------------------------------------------
    // Persistence / lifecycle
    // ---------------------------------------------------------------------------------------
    synchronized void writeTo(SavedState state) {
        if (state != null) state.setGeCustody(copiedList(records));
    }

    synchronized void restore(SavedState state, long now) {
        reset(); if (state == null) return;
        // One live lifecycle per slot (its newest record); settled CLOSED history is kept as well.
        // A record that gave up its slot (-1) still settling is a lifecycle of its own.
        var newest = new HashMap<Object, GeOffer>(); var closedByOffer = new LinkedHashMap<String, GeOffer>();
        for (var record : state.geCustody()) {
            if (record == null || record.offerId.isEmpty()) continue;
            if (record.stage == CLOSED) closedByOffer.put(record.offerId, record);
            Object slot = record.slot < 0 ? record.offerId : record.slot; var existing = newest.get(slot);
            if (existing == null || record.placedAtEpochMillis >= existing.placedAtEpochMillis)
                newest.put(slot, record);
        }
        for (var record : newest.values()) closedByOffer.remove(record.offerId);
        records.addAll(newest.values()); records.addAll(closedByOffer.values());
        records.sort((left, right) -> Long.compare(left.placedAtEpochMillis, right.placedAtEpochMillis));
    }

    /** Destructive reset: custody state is financial continuity state and clears with it. */
    synchronized void reset() {
        records.clear(); boxes.clear(); baseline.clear(); replaced.clear();
        explained.clear(); loginAt.clear(); clearPending();
    }

    /** Adopt another ledger's durable records during a staged profile swap. */
    synchronized void adoptFrom(GeCustodyLedger other) {
        reset(); if (other != null) records.addAll(other.records);
    }

    synchronized List<GeOffer> records() {
        return frozenList(records);
    }

    /**
     * Drop any transient held collect and slot-clear evidence; durable lifecycles are untouched.
     */
    synchronized void clearPending() {
        cash = 0; cashAt = 0; heldAt = 0; unproven = false;
        clearAt.clear();
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------
    List<GeOffer> byPlacement() {
        var sorted = copiedList(records);
        sorted.sort((left, right) -> {
                    int byTime = Long.compare(left.placedAtEpochMillis, right.placedAtEpochMillis);
                    return byTime != 0 ? byTime : Integer.compare(left.slot, right.slot);
                });
        return sorted;
    }

    /** The slot's live lifecycle; settled (CLOSED) history never owns a slot. */
    GeOffer forSlot(int slot) {
        return firstMatch(records, record -> record.slot == slot && record.stage != CLOSED);
    }

    GeOffer latestClosed(int slot) {
        GeOffer latest = null;
        for (var record : records)
            if (record.slot == slot && record.stage == CLOSED && (latest == null
                            || record.placedAtEpochMillis >= latest.placedAtEpochMillis))
                latest = record;

        return latest;
    }

    GeOffer oldestClosed() {
        GeOffer first = null;
        for (var record : records)
            if (record.stage == CLOSED && (first == null || record.placedAtEpochMillis < first.placedAtEpochMillis))
                first = record;

        return first;
    }

    /**
     * The slot's live lifecycle for an offer (any side or item when unknown), falling back to its
     * latest settled history; marks it seen.
     */
    GeOffer touch(int slot, Snapshot offer) {
        Side side = offer == null ? null : OfferSlots.sideOf(offer.state); int id = offer == null ? 0 : offer.item;
        GeOffer match = null;
        for (var record : records) {
            if (record.slot != slot || side != null && side != record.side || id > 0 && record.itemId != id)
                continue;
            if (record.stage != CLOSED) {
                match = record; break;
            }
            if (match == null || record.placedAtEpochMillis >= match.placedAtEpochMillis)
                match = record;
        }
        if (match != null) match.lastSeenAtEpochMillis = now; return match;
    }

    boolean sameOffer(GeOffer record, Side side, int id, long totalQty, long price) {
        return record.side == side && record.itemId == id && record.offeredQty == totalQty
                && record.listedPrice == price;
    }

    /**
     * The slot cleared but its cash or items never reached the inventory: they went to the bank.
     * The offer itself proves what filled and what it cost, so it settles as if collected. A buy is
     * exact; a sale pays its proven tax or, filled at several prices, the highest tax it could have
     * paid, so its result is never overstated. What never sold, or was never spent, went too. False
     * leaves an untrusted or unobserved lifecycle to the Review handoff.
     */
    boolean banked(GeOffer record) {
        collected(record); boolean sell = record.side == SELL;
        if (sell && record.capturedQty <= 0 || !cleanSettlement(record)) return false;
        if (sell) {
            long[] net = net(record);
            if (net != null) {
                // The lowest after-tax cash it could have received, as an exact one-offer collect.
                boolean estimated = pendingSellTax(record) < 0; settleAttributed(singletonList(record), net[0]);
                if (estimated) rows.get(rows.size() - 1).explanation += msg("gbank");
            }
            long unsold = record.heldQty(); releaseShare(record, unsold); record.returnedQty = sum(record.returnedQty, unsold);
        } else {
            record.collectedQty = positive(record.filledQty); bookBuySettlement(record);
            record.refundedGp = positive(sum(record.refundedGp, record.reserveOutstanding()));
        }
        if (record.reconciled()) record.stage = CLOSED; return true;
    }

    /** Local collection supplies settlement authority, never retroactive acquisition basis. */
    void collected(GeOffer record) {
        if (record.confidence == AMBIGUOUS) return; if (record.unbased()) record.confidence = CustodyConfidence.CONFIRMED;
        if (record.side == SELL && record.capturedQty == 0) {
            record.capturedQty = record.offeredQty; record.basisUnitPrice = max(1, record.listedPrice);
        } else if (record.side == BUY && record.basisSource == null && record.reserved() > 0) {
            // A confirmed purchase supplies its actual cost without requiring a guide quote.
            record.basisUnitPrice = record.pendingExecution() / record.reserved();
        } else return; record.basisSource = GRAND_EXCHANGE; record.basisCapturedAtEpochMillis = now;
    }

    /** Outside-client collection retires pending custody without a booking or Review. */
    void away(GeOffer record) {
        fencePending(record, null);
        // Reserved units no longer prove owned stock; never release them as a fresh acquisition.
        record.reservedTrackedQty = record.consumedTrackedQty; record.reservedTrackedBasisGp = record.consumedTrackedBasisGp;
        record.filledQty = record.settledQty; record.spentGp = record.settledExecutionGp;
        record.capturedQty = sum(record.settledQty, record.returnedQty); record.stage = CLOSED;
        if (record.settledQty == 0) records.remove(record); loginAt.remove(record.offerId); clearAt.remove(record.offerId);
    }

    void handoff(GeOffer record, String reason) {
        var review = reviewHandoff(record, reason);
        // Returns already released their measured share. Losing SELL lineage proves no new return,
        // even when no fill was observed and there is no financial Review to generate.
        if (record.side != SELL) releaseRemaining(record);
        if (review != null && record.side == SELL && record.settleable() > 0) review.handoffHeld = true;
        fencePending(record, review); if (review != null) rows.add(review);
        records.remove(record);
    }

    /** Unknown custody also blocks later creation of coverage for stock no longer proven held. */
    void fencePending(GeOffer record, Transaction review) {
        if (basis != null && record.pendingStock())
            basis.fenceRealized(record.itemId, now, review == null ? null : review.id());
    }

    Transaction reviewHandoff(GeOffer record, String reason) {
        if (record.unbased()) return null; boolean buy = record.side == BUY;
        if (!buy && record.capturedQty <= 0)
            // The principal was never observed entering custody; its movements follow the
            // ordinary classification path and must not be surfaced twice.
            return null;
        long pending = buy ? record.reserved() : record.settleable(), execution = record.pendingExecution();
        if (pending <= 0 && execution <= 0) return null; var flows = flowList();
        long unit = record.basisUnitPrice;
        if (pending > 0) {
            long value = buy && record.collectedValueGp != 0 ? abs(record.collectedValueGp)
                            : record.basisFor(pending);
            flows.add(itemFlow(record, buy ? pending : -pending, unit, buy ? value : -value));
        }
        if (execution > 0) flows.add(coins(buy ? -execution : execution));
        return reviewRow(flows, msg("geLifecycle") + reason + msg("knownFacts"));
    }

    void captureBasis(GeOffer record, Flow flow) {
        var source = flow.source(); record.basisUnitPrice = positive(flow.unitPrice);
        record.basisSource = source == null ? UNKNOWN : source; record.basisCapturedAtEpochMillis = now;
    }

    /** Presentation/audit source name; never used for matching. */
}

/**
 * Slot-keyed, observation-only record of the Grand Exchange offer state exposed by RuneLite. It
 * screens duplicate snapshots and hands each real change to custody as a previous/current pair; it
 * assigns no financial meaning to any field and never books anything.
 *
 * <p>Call {@link #beginSeed()} before processing RuneLite's login slot replay. Snapshots received
 * until {@link #finishSeed()} establish baselines and produce no transitions. On relog, starting
 * another seed clears old slot state so the replay cannot look like new activity.
 */
@Singleton
class OfferSlots {
    Map<Integer, Snapshot> bySlot = new LinkedHashMap<>(); boolean loginSeed;

    /**
     * Begin a login/relogin baseline pass; all observations are suppressed until it is finished.
     */
    synchronized void beginSeed() {
        bySlot.clear(); loginSeed = true;
    }

    /** Finish the baseline pass after the caller has received the initial offer-slot replay. */
    synchronized void finishSeed() {
        loginSeed = false;
    }

    /**
     * Observe one immutable slot snapshot. Repeated identical snapshots, a first empty slot and all
     * login-seed observations return an empty Optional.
     */
    synchronized Optional<Transition> observe(Snapshot current) {
        Objects.requireNonNull(current, "current"); var previous = bySlot.put(current.slot, current);
        return loginSeed || current.equals(previous) || current.state == EMPTY
                        && (previous == null || previous.state == EMPTY) ? Optional.empty() : Optional.of(new Transition(previous, current));
    }

    /**
     * Take an immutable point-in-time copy of the latest per-slot observations. The returned map
     * cannot be used to mutate this ledger.
     */
    synchronized Map<Integer, Snapshot> snapshots() {
        return unmodifiableMap(new LinkedHashMap<>(bySlot));
    }

    /** The offer side a state belongs to; null for an empty slot. */
    static Side sideOf(GrandExchangeOfferState state) {
        return state == BUYING || state == BOUGHT
                        || state == CANCELLED_BUY ? BUY
                : state == SELLING || state == SOLD
                                || state == CANCELLED_SELL ? SELL : null;
    }

    static boolean isActive(GrandExchangeOfferState state) {
        return state == BUYING || state == SELLING;
    }

    static boolean isTerminal(GrandExchangeOfferState state) {
        return sideOf(state) != null && !isActive(state);
    }

    /** Immutable copy of the API fields used by the offer ledger. */
    @EqualsAndHashCode
    @AllArgsConstructor
    static class Snapshot {
        int slot; GrandExchangeOfferState state;
        int item, quantity;

        /** RuneLite's quantity bought or sold so far, copied from getQuantitySold(). */
        int traded;

        long price;

        /** Raw getSpent() observation; the model does not assign sell-side or tax semantics. */
        long spent;

        static Snapshot fromOffer(int slot, GrandExchangeOffer offer) {
            return new Snapshot(slot, offer.getState(), offer.getItemId(), offer.getTotalQuantity(),
                    offer.getQuantitySold(), offer.getPrice(), offer.getSpent());
        }
    }

    /**
     * One observed slot change: the previous snapshot (null when first seen) and the current one.
     */
    @AllArgsConstructor
    static class Transition {
        Snapshot previous, current;
    }
}

/**
 * One bounded schema-104 Grand Exchange custody lifecycle: the durable minimum needed to keep exact
 * economics across restart for an offer whose placement was observed under schema 104.
 *
 * <p>Physical custody movements (placement principal, cancellation return/refund) are
 * ownership-neutral. A safely realized settlement books one counted canonical Market result,
 * exactly once, using the persisted cumulative-execution counters as the idempotency guard.
 *
 * <p>Fields are side-specific on purpose: SELL freezes item basis at placement; BUY captures item
 * basis only when the purchased quantity enters tracked ownership. No field is a second financial
 * journal.
 */
@EqualsAndHashCode(of = "offerId", callSuper = false)
@Getter
@Setter
class GeOffer extends SaveCopies {
    /** Durable offer side. */
    enum Side {
        BUY, SELL
    }

    /** Durable lifecycle stage. Behavioural projection states are derived from these facts. */
    enum Stage {
        /** Placement observed; principal may still be arriving; execution may advance. */
        OPEN,
        /** Terminal and reconciled; eligible for safe retirement. */
        CLOSED
    }

    /** Confidence in the placement/basis lineage. */
    enum CustodyConfidence {
        /** Placement and any basis were observed live under schema 104. */
        CONFIRMED,
        /**
         * Offer was already open when schema 104 first observed it; never receives invented basis.
         */
        LEGACY_UNBASED,
        /** Identity or quantity desync; no exact basis lineage. */
        AMBIGUOUS
    }

    String offerId = ""; int slot;
    Side side = SELL; int itemId;
    String itemName = ""; long offeredQty, listedPrice, placedAtEpochMillis, lastSeenAtEpochMillis;
    String originSessionId = "", offerState = ""; Stage stage = OPEN;
    CustodyConfidence confidence = CustodyConfidence.CONFIRMED;

    /** Latest cumulative filled quantity observed from the offer counters. */
    long filledQty;

    /** Latest cumulative spent observed from the offer counters (exact execution evidence). */
    long spentGp;

    /** Filled quantity already booked into a canonical settlement. */
    long settledQty;

    /** Gross execution value already booked into a canonical settlement. */
    long settledExecutionGp;

    /** Observed settlement value already applied to this offer's settlements (0 when batch). */
    long settledCashGp;

    /**
     * Cumulative realized Market result booked for this lifecycle (derived, not a second truth).
     */
    long realizedResultGp;

    /** SELL: quantity whose basis is frozen. Never increased after the placement capture. */
    long capturedQty;

    /** SELL: quantity returned from custody on cancellation. */
    long returnedQty;

    /** BUY: observed inventory coin principal currently held by the exchange. */
    long reserveGp;

    /** BUY: observed coin principal already refunded to the inventory. */
    long refundedGp;

    /** SELL frozen basis unit price; 0 when unbased. */
    long basisUnitPrice;

    /** SELL frozen basis price source; null until a basis is captured. */
    PriceSource basisSource;

    /** SELL frozen basis capture time; 0 when unbased. */
    long basisCapturedAtEpochMillis;

    /** BUY: quantity collected into tracked ownership (basis captured at collection). */
    long collectedQty;

    /** BUY: exact value of the collected quantity (sum of observed collection valuations). */
    long collectedValueGp;

    /** Last canonical settlement transaction id linked to this offer lifecycle. */
    String settlementId = "";

    /**
     * The earlier settlement ids, space-separated: an offer collected in parts books one settlement
     * per collect, and Live and the Ledger fold all of them into its one market row (owner
     * 2026-10-09).
     */
    String earlierSettlements = "";

    /** Schema-106 pooled tracked-basis reservation captured with the SELL placement principal. */
    long reservedTrackedQty,

        reservedTrackedBasisGp;

    /** Schema-106 realized share of the reservation consumed by collected settlements. */
    long consumedTrackedQty,

        consumedTrackedBasisGp;

    /** True when quantity was modified without an exact per-slice basis allocation. */
    boolean quantityModified;

    /** True once the offer slot was observed EMPTY; the terminal/cancelled state is retained. */
    boolean cleared;

    /** Missing on older saves: never infer a local collection from unknown provenance. */
    boolean collectedAway = true;

    /** True when a collection could not be attributed exactly to the filled quantity. */
    boolean collectionAmbiguous;

    /**
     * Schema-107 SELL: exact GE tax of every observed fill, each at its own proven unit price.
     * Meaningful only while {@link #fillTaxExact}; absent (false) on pre-107 records.
     */
    long fillTaxGp;

    /** Schema-107 SELL: true while every fill was observed at a provable per-item price. */
    boolean fillTaxExact;

    /** Schema-107 SELL: the part of {@link #fillTaxGp} already booked into settlements. */
    long settledTaxGp;

    GeOffer() {
        // Gson
    }

    GeOffer(String offerId, int slot, Side side, int id, String name, long offeredQty, long listedPrice,
            long placedAt, String origin) {
        this.offerId = offerId; collectedAway = false; this.slot = slot; this.side = side;
        itemId = id; itemName = string(name); this.offeredQty = positive(offeredQty); this.listedPrice = positive(listedPrice);
        placedAtEpochMillis = positive(placedAt); originSessionId = string(origin);
    }

    /** The frozen basis price source; UNKNOWN until one is captured. */
    PriceSource getBasisSource() {
        return basisSource == null ? UNKNOWN : basisSource;
    }

    /** SELL: tracked reservation quantity still held by this lifecycle. */
    long reservedQty() {
        return positive(reservedTrackedQty - consumedTrackedQty);
    }

    /** SELL: tracked reservation basis still held by this lifecycle. */
    long reservedBasis() {
        return positive(reservedTrackedBasisGp - consumedTrackedBasisGp);
    }

    boolean pendingStock() {
        return side == SELL && capturedQty > sum(returnedQty, settledQty);
    }

    void setSettlementId(String value) {
        earlierSettlements = earlierSettlements + " " + settlementId; settlementId = string(value);
    }

    /**
     * Clamps a record read from a save once, so the accessors can read fields directly: blank text
     * and missing enums take their defaults, and no counter is negative.
     */
    void sanitize() {
        offerId = string(offerId); itemName = string(itemName);
        originSessionId = string(originSessionId); offerState = string(offerState);
        settlementId = string(settlementId); earlierSettlements = string(earlierSettlements); if (side == null) side = BUY;
        if (stage == null) stage = OPEN; if (confidence == null) confidence = CustodyConfidence.CONFIRMED;
        offeredQty = positive(offeredQty); listedPrice = positive(listedPrice);
        placedAtEpochMillis = positive(placedAtEpochMillis);
        lastSeenAtEpochMillis = positive(lastSeenAtEpochMillis); filledQty = positive(filledQty);
        spentGp = positive(spentGp); settledQty = positive(settledQty);
        settledExecutionGp = positive(settledExecutionGp); capturedQty = positive(capturedQty);
        returnedQty = positive(returnedQty); reserveGp = positive(reserveGp);
        refundedGp = positive(refundedGp); basisUnitPrice = positive(basisUnitPrice);
        basisCapturedAtEpochMillis = positive(basisCapturedAtEpochMillis);
        collectedQty = positive(collectedQty); reservedTrackedQty = positive(reservedTrackedQty);
        reservedTrackedBasisGp = positive(reservedTrackedBasisGp); consumedTrackedQty = positive(consumedTrackedQty);
        consumedTrackedBasisGp = positive(consumedTrackedBasisGp);
        fillTaxGp = positive(fillTaxGp); settledTaxGp = positive(settledTaxGp);
    }

    /** A fresh basis can still be frozen at the first matched owned movement. */
    boolean hasFrozenBasis() {
        return basisSource != null && basisSource != UNPRICED && basisSource != UNKNOWN
                && basisUnitPrice > 0;
    }

    /** Filled quantity not yet booked into a canonical settlement. */
    long reserved() {
        return positive(filledQty - settledQty);
    }

    /** Exact gross execution value of the quantity not yet booked. */
    long pendingExecution() {
        return positive(spentGp - settledExecutionGp);
    }

    /** SELL: quantity still held by the exchange (captured, not returned, not filled). */
    long heldQty() {
        return positive(capturedQty - returnedQty - filledQty);
    }

    /** SELL: basis value for a quantity at the frozen unit price; 0 when unbased. */
    long basisFor(long quantity) {
        return multiply(basisUnitPrice, positive(quantity));
    }

    /** SELL: quantity that can be settled with an exact frozen basis. */
    long settleable() {
        return min(reserved(), positive(capturedQty - settledQty));
    }

    /** BUY: reserve not yet spent on fills or returned as change; zero once the offer is done. */
    long reserveOutstanding() {
        return positive(reserveGp - spentGp - refundedGp);
    }

    /** True when the offer's last observed state is a terminal fill/cancellation. */
    boolean isTerminal() {
        return "SOLD".equals(offerState) || "BOUGHT".equals(offerState) || isCancelled();
    }

    /** True when the last observed state is an explicit cancellation. */
    boolean isCancelled() {
        return "CANCELLED_SELL".equals(offerState) || "CANCELLED_BUY".equals(offerState);
    }

    /** True when custody is fully reconciled and the record may be retired after a durable save. */
    boolean reconciled() {
        return reserved() == 0 && (isTerminal() || cleared)
                && (side == SELL ? heldQty() == 0
                        : positive(filledQty - collectedQty) == 0 && reserveOutstanding() == 0);
    }

    /** True when no durable basis can ever be attached (legacy-open or desync). */
    boolean unbased() {
        return confidence == LEGACY_UNBASED;
    }
}

/**
 * The one authoritative GE sell-tax rule shared by custody settlement and the read model.
 *
 * <p>Ordinary taxed items pay 2% of the sale price rounded DOWN per item (so sub-50 gp items pay
 * nothing), capped at 5,000,000 gp per item. The wiki's Grand Exchange tax exemption list (checked
 * 2026-09-13, ids resolved against the pinned RuneLite gameval {@code ItemID} constants) pays
 * nothing regardless of price; a real client Collect All of the owner's seven-sale batch proved the
 * exempt low-level food case (cooked chicken sold at 69 gp paid no tax). Tax is never approximated
 * as a stack-wide 2%: it rounds per ITEM. Everything is integer-only and long-safe; there is no
 * floating point here and no second exemption list anywhere else in the codebase.
 */
@UtilityClass
class GeTaxRule {
    /**
     * Items exempt from the GE convenience fee, per the wiki's Grand Exchange tax exemption list.
     * Items under 50 gp pay nothing through rounding anyway, so most tools here matter only when
     * their price spikes.
     */
    final Set<Integer> EXEMPT_IDS = byId("tax_exempt").keySet();

    /** Sentinel: no proven per-item price exists, so no tax can be asserted for an execution. */
    final long UNPROVABLE_TAX = -1;

    /**
     * The uniform per-item tax of one execution: {@link #UNPROVABLE_TAX} when the per-item price
     * cannot be proven (a non-exempt gross that is not divisible by the quantity), otherwise the
     * exact tax, which may legitimately be 0 (an exempt item, or a unit price below 50 gp). Each
     * item pays its price / 50 rounded down, at most 5,000,000 gp. The tax never exceeds gross /
     * 50, so it always fits a long.
     */
    long provableTax(int id, long gross, long quantity) {
        if (gross <= 0 || quantity <= 0) return UNPROVABLE_TAX; return EXEMPT_IDS.contains(id) ? 0 : gross % quantity != 0 ? UNPROVABLE_TAX : min(5_000_000, gross / quantity / 50) * quantity;
    }

    /**
     * The proven sell tax of one settled execution: the exact positive gap between the observed
     * gross execution and the observed Received cash, accepted only when it equals the uniform
     * per-item rule for that execution. Zero means nothing is proven — a genuinely tax-free stack,
     * an exempt item, a non-uniform execution, or a gap that does not match the rule.
     *
     * <p>Booking and the read model share this one predicate, so a booked tax can never disagree
     * with the receipt that explains it. It is a tax proof, not a result: it never implies the
     * prior tracked value of the sold units.
     */
    long acceptedTax(int id, long gross, long quantity, long receivedGp) {
        long tax = provableTax(id, gross, quantity); return tax > 0 && gross - receivedGp == tax ? tax : 0;
    }
}
