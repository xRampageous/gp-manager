package com.gpmanager;
import static com.gpmanager.LedgerData.Scope.*;
import java.util.concurrent.ConcurrentHashMap;
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
import static com.gpmanager.ActionKind.*;
import static com.gpmanager.Correction.*;
import static com.gpmanager.Phrases.*;
import static com.gpmanager.PriceSource.*;
import static com.gpmanager.SafeMath.*;
import static com.gpmanager.TransactionType.*;
import static java.util.Collections.*;
import java.util.*;
import lombok.*;
import com.gpmanager.ActionReader.PartialUse;
import static com.gpmanager.FlowFilters.matching;
import com.gpmanager.GpManagerConfig.Retention;
import static com.gpmanager.Accounting.*;
import static com.gpmanager.PauseReason.*;
import static java.lang.Math.*;
import static com.gpmanager.SafeMath.abs;
import com.gpmanager.CorrectionRecord.Change;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.function.*;
import static java.util.Comparator.*;
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
import static com.gpmanager.GameData.msg;
import com.gpmanager.SessionRepository.ReplaceOutcome;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.runelite.api.Client;
import net.runelite.client.util.Filepath;
import com.google.gson.*;
import com.gpmanager.SaveStatus.State;
import java.nio.channels.*;
import java.nio.file.*;






/**
 * Canonical durable financial fact: stable id, timestamp, type/context, booked item flows, the
 * automatic counted state, confidence, correction and split state, and optional links to a PvP
 * encounter or the pending claim it settled. Booked values are never repriced.
 */
class Transaction extends SaveCopies {
    String id;
    @SerializedName("timestampEpochMillis")
    long timestamp;
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
        id = uuid();
        this.timestamp = timestamp;
        this.type = type;
        this.context = context;
        this.note = string(note);
        activityName = normalizeActivity(activity);
        this.counted = counted;
        this.flows = copiedList(flows);
        this.confidence = confidence == null ? Confidence.UNCERTAIN : confidence;
        explanation = string(reason);
        correction = AUTO;
        correctionReason = "";
        encounterId = encounter;
        recalculate();
    }

    void recalculate() {
        revenue = 0;
        costs = 0;
        for (Flow flow : flows) {
            if (flow.value > 0) revenue = sum(revenue, flow.value);
            else if (flow.value < 0) costs = sum(costs, abs(flow.value));
        }
    }

    void applyCorrection(Correction newCorrection, String reason) {
        correction = newCorrection == null ? AUTO : newCorrection;
        correctionReason = normalizeReason(reason);
    }

    /**
     * Takes {@code amount} of one item's gains (newest flow first) or costs (oldest first) out of
     * the flows, each remainder keeping its own unit value, and returns the quantity left.
     */
    long takeQuantity(int id, boolean income, long amount) {
        var ordered = new ArrayList<>(flows());
        if (income) reverse(ordered);
        var next = new ArrayList<Flow>();
        for (Flow flow : ordered) {
            long size = abs(flow.quantity);
            long take = flow.item == id && (income ? flow.gain() : flow.cost())
                            ? min(positive(amount), size) : 0;
            amount -= take;
            long left = size - take;
            if (take <= 0)
                next.add(flow); else if (left > 0) {
                long unit = flow.unitPrice, signed = income ? left : -left;
                next.add(flow.raid(new Flow(id, flow.name, signed, flow.unitPrice,
                                unit == 0 ? flow.value / size * left : signed * unit, flow.source(),
                                flow.pricedAt())));
            }
        }
        if (income) reverse(next);
        replaceFlows(next);
        return quantity(id, income);
    }

    /** True while any flow still spends an item; a blowpipe shot keeps its scales after a dart. */
    boolean spends() {
        for (Flow flow : flows()) {
            if (flow.cost()) return true;
        }
        return false;
    }

    void replaceFlows(List<Flow> next) {
        flows = copiedList(next);
        recalculate();
    }

    /**
     * Stamps split provenance on explanation/reason without changing the correction enum (partial
     * keep stays counted).
     */
    void stampSplit(String reason) {
        String mark = reason == null ? ItemSplits.SPLIT_SHARE_MARK : reason.trim();
        if (mark.isEmpty()) mark = ItemSplits.SPLIT_SHARE_MARK;
        correctionReason = normalizeReason(mark);
        String current = explanation();
        if (!ItemSplits.splitReason(current))
            explanation = current.isEmpty() ? mark : current + " — " + mark; else if (!current.contains(mark))
            explanation = current + " — " + mark;
    }

    void setExplanation(String next) {
        explanation = string(next);
    }

    /** Total gained ({@code gains}) or lost quantity of one item across the remaining flows. */
    long quantity(int id, boolean income) {
        long total = 0;
        for (Flow flow : flows) {
            if (flow.item == id && (income ? flow.gain() : flow.cost()))
                total = sum(total, abs(flow.quantity));
        }
        return total;
    }

    void assignEncounter(String newEncounterId, boolean pkSupply) {
        encounterId = newEncounterId;
        if (pkSupply && original() == CONSUMPTION) {
            type = PK_SUPPLY_COST;
            activityName = "PKing";
            confidence = Confidence.LIKELY;
            explanation = msg("combat-supply");
        }
    }

    long gross() {
        return sum(revenue, costs);
    }

    String id() {
        if (empty(id)) id = uuid();
        return id;
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
        return context == null ? Context.GENERIC : context;
    }

    String note() {
        return string(note);
    }

    /** Presentation-only audit-row note update; accounting fields are untouched. */
    String activityName() {
        if (!blank(activityName)) return activityName.trim();
        String safeNote = note(), prefix = "Loot from ";
        return safeNote.startsWith(prefix) && safeNote.length() > prefix.length()
                ? safeNote.substring(prefix.length()).trim() : "General";
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
        if (flows == null) flows = new ArrayList<>();
        flows.removeIf(Objects::isNull);
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
        var current = correction();
        return current == AUTO ? automatic : current == forced ? gross() : 0;
    }

    long net() {
        return subtract(revenue(), costs());
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
                return subtract(0, gross());
            case TRANSFER:
            case IGNORE:
                return 0;
            default:
                return counted ? subtract(revenue, costs) : 0;
        }
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
                    String observed = action() == CAST && Spells.ICONS.containsKey(observedSpellName)
                            ? observedSpellName : null;
                    if (observed != null) return observed;
                    // Runes that identify a spell win over a click queued behind an autocast (owner
                    // 2026-09-28);
                    // otherwise the clicked name stands (a staff-covered or non-combat spell).
                    String spell = action() == CAST ? Spells.spellFor(flows()) : null;
                    var label = actionLabel();
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
        if (cache[slot] == null) cache[slot] = build.get();
        return (T) cache[slot];
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
        if (empty(observedActionLabel)) return null;
        if (action() != CAST && action() != DRINK) return null;
        var normalized = ActionLabel.of(observedActionLabel);
        return normalized == null ? null : normalized.value;
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
        long total = positive(quantity), keep = positive(min(keepQuantity, total));
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

    static final Map<String, Boolean> SPLIT_TEXT = new ConcurrentHashMap<>();

    static boolean splitReason(String reasonOrExplanation) {
        if (empty(reasonOrExplanation)) return false;
        // Receipt texts are shared strings read on every tick: decide each distinct text once.
        if (SPLIT_TEXT.size() > 10000) SPLIT_TEXT.clear();
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

}

/** Shared accounting predicate for receipts that still need an owner decision. */
class ReviewRules {
    /** Proven historical spend: financial correction only; never rewrite later item basis. */
    static boolean financialDose(Transaction transaction) {
        return transaction.action() == DRINK && msg("captured-dose").equals(transaction.note());
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

    String reason() {
        return Transaction.normalizeReason(reason);
    }


    /** The rows this decision changed. */
    List<Change> changes() {
        return readOnlyList(changes);
    }

    /** True when any change in this record targets one of the given transaction ids. */
    boolean touchesAny(Set<String> ids) {
        for (Change change : changes()) {
            if (ids.contains(change.transactionId())) return true;
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
    long quantity;
    long unitPrice;
    @SerializedName("valueDelta")
    long value;
    PriceSource priceSource;

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
        item = id;
        this.name = name;
        quantity = delta;
        unitPrice = price;
        this.value = value;
        priceSource = source == null ? UNKNOWN : source;
        priceCapturedAtEpochMillis = at;
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
        derived.raid = raid;
        return derived;
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
                || (widget >>> 16 & 0xffff) != (spellbookGroupId & 0xffff))
            return null;
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

    static final Map<String, String> BY_LOWER = new HashMap<>();

    static {
        for (String name : ICONS.keySet()) BY_LOWER.put(name.toLowerCase(ROOT), name);
    }

    /** The item ids a spell makes (an alch's coins, Bones to Peaches' fruit); empty for others. */
    static Set<Integer> product(String name) {
        for (String[] row : cached("spells")) {
            if (row[0].equals(name) && row.length > 2) {
                var ids = new HashSet<Integer>();
                for (String id : row[2].split(",")) ids.add(parseInt(id));
                return ids;
            }
        }
        return emptySet();
    }

    /** The spell a name spells out, ignoring case (a "Bones to peaches" tablet), else null. */
    static String named(String name) {
        return name == null ? null : BY_LOWER.get(name.toLowerCase(ROOT).trim());
    }

    /**
     * The spell's spellbook icon as a negated sprite id (the sidebar's sprite lookup reads it),
     * else -1.
     */
    static int icon(String name) {
        // d19 holds a spell's sprite, or minus an item id for a row named by its item (the barrel).
        String icon = ICONS.get(name);
        return icon == null ? -1 : -parseInt(icon);
    }

    /** Rune recipes are parsed once; fitting arithmetic and HashMap iteration stay unchanged. */
    static final Map<String, Map<String, Long>> RECIPES = new LinkedHashMap<>();

    static {
        for (String[] row : cached("spell_recipes")) {
            var recipe = new HashMap<String, Long>();
            for (String part : row[1].split(" ")) {
                int colon = part.indexOf(':');
                recipe.put(part.substring(colon + 1), Long.parseLong(part.substring(0, colon)));
            }
            RECIPES.put(row[0], unmodifiableMap(recipe));
        }
    }

    /**
     * The spell these rune costs pay for (a whole number of casts), or null. When staff-covered
     * elementals let several fit, the one paid in full wins; if none or several are, it stays a
     * Cast.
     */
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

    /** 0 when these costs cannot be this spell, 1 when they fit it, 2 when they pay it in full. */
    static int fit(Map<String, Long> recipe, List<Flow> flows) {
        var paid = new HashMap<String, Long>();
        for (Flow flow : flows) {
            String name = flow.name.toLowerCase(ROOT);
            if (!name.endsWith(" rune") || flow.quantity >= 0) return 0;
            String rune = name.substring(0, name.length() - 5);
            boolean used = false;
            for (String element : COMBINATIONS.getOrDefault(rune, rune).split(" ")) {
                // A combination rune pays only the elements this spell uses (steam is water for Ice
                // Barrage).
                if (recipe.containsKey(element) || !COMBINATIONS.containsKey(rune)) {
                    paid.merge(element, -flow.quantity, Long::sum);
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
        if (transaction == null) return unavailable();
        return transaction.memo(8, () -> amounts(transaction));
    }

    static Amounts amounts(Transaction transaction) {
        if (transaction.isTransfer())
            return transfer();
        if (!transaction.counts()) return excluded();
        var flows = transaction.flows();
        if (empty(flows))
            return included(transaction.revenue(), transaction.costs());
        long gross = 0;
        boolean included = false;
        for (Flow flow : flows) {
            if (flow == null) continue;
            included = true;
            gross = sum(gross, abs(flow.value));
        }
        if (!included) return excluded();
        switch (transaction.correction()) {
            case REVENUE:
                return included(gross, 0);
            case COST:
                return included(0, gross);
            default:
                long[] steps = cachedSteps(transaction);
                long step = 0;
                for (int i = 0; i < flows.size(); i++) {
                    if (flows.get(i) != null && flows.get(i).value > 0)
                        step = sum(step, steps[i]);
                }
                step = min(step, min(transaction.revenue(), transaction.costs()));
                return included(subtract(transaction.revenue(), step),
                        subtract(transaction.costs(), step));
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
        var flows = transaction.flows();
        long[] steps = new long[flows.size()];
        for (var pair : consumePairs(transaction)) {
            long left = flows.get(pair.remainder).value;
            if (left > 0 && sum(flows.get(pair.lossIndex).value, left) <= 0) {
                steps[pair.lossIndex] = left;
                steps[pair.remainder] = left;
            }
        }
        return steps;
    }

    /** One consume gate for accounting and item presentation; transfers/decants keep raw quotes. */
    static List<PartialUse> consumePairs(Transaction transaction) {
        var flows = transaction.flows();
        var action = transaction.action();
        // Cheapest facts first: this runs for every flow of every receipt on each refresh (release
        // pass).
        if (!transaction.counts() || transaction.correction() != AUTO
                || transaction.type() != CONSUMPTION
                        && transaction.type() != PK_SUPPLY_COST || action != null
                        && action != DRINK && action != EAT && action != SUPPLIES
                || ActionReader.isDecant(flows)) return emptyList();
        var pairs = new ArrayList<PartialUse>();
        for (var pair : ActionReader.partialUses(flows)) {
            if (flows.get(pair.lossIndex).source()
                    == flows.get(pair.remainder).source()) pairs.add(pair);
        }
        return pairs;
    }

    /** Returns the additive accounting contribution of a single item-flow row. */
    static Amounts flow(Transaction transaction, Flow flow) {
        if (transaction == null || flow == null) return unavailable();
        if (transaction.isTransfer())
            return transfer();
        if (!transaction.counts())
            return excluded();
        long value = flow.value, autoRevenue = value > 0 ? value : 0, autoCosts = value < 0 ? abs(value) : 0;
        switch (transaction.correction()) {
            case REVENUE:
                return included(abs(value), 0);
            case COST:
                return included(0, abs(value));
            default:
                var flows = transaction.flows();
                long step = 0;
                for (int i = 0; i < flows.size(); i++) {
                    if (flows.get(i) == flow) step = cachedSteps(transaction)[i];
                }
                return included(subtract(autoRevenue, value > 0 ? step : 0),
                        subtract(autoCosts, value < 0 ? step : 0));
        }
    }

    /** Correction-aware supplies/other split shared by every retained money fold. */
    static CostSplit costSplit(Transaction transaction) {
        return transaction.memo(9, () -> split(transaction));
    }

    static CostSplit split(Transaction transaction) {
        var total = transaction(transaction);
        if (!total.available) return new CostSplit(0, 0, false);
        if (!total.included || total.cost <= 0) return new CostSplit(0, 0, true);
        if (transaction.flows().isEmpty()) return new CostSplit(0, 0, false);
        long supplies = 0, other = 0;
        for (Flow flow : transaction.flows()) {
            if (flow == null) continue;
            var money = flow(transaction, flow);
            if (!money.available) return new CostSplit(0, 0, false);
            if (!money.included || money.cost <= 0) continue;
            switch (CostKind.of(transaction, flow)) {
                case SUPPLIES:
                    supplies = sum(supplies, money.cost);
                    break;
                case LOSS:
                case MARKET:
                    other = sum(other, money.cost);
                    break;
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
    static MarketFold marketFold(List<Transaction> transactions) {
        long income = 0, cost = 0, supplies = 0, other = 0;
        boolean splitAvailable = true;
        int included = 0;
        if (transactions == null) return new MarketFold(0, 0, 0, 0, true, 0);
        for (var transaction : transactions) {
            if (transaction == null || transaction.original() != TRADE) continue;
            var money = transaction(transaction);
            if (!money.included) continue;
            included++;
            income = sum(income, money.income);
            cost = sum(cost, money.cost);
            var split = costSplit(transaction);
            if (!split.available) {
                splitAvailable = false;
                continue;
            }
            supplies = sum(supplies, split.supplies);
            other = sum(other, split.other);
        }
        return new MarketFold(income, cost, splitAvailable ? supplies : 0, splitAvailable ? other : 0,
                splitAvailable, included);
    }

    @AllArgsConstructor
    static class MarketFold {
        long income, cost, supplies, other;
        boolean splitAvailable;

        /** Counted Market-context transactions behind this fold. */
        int transactions;

        long net() {
            return subtract(income, cost);
        }
    }

    @AllArgsConstructor
    static class Amounts {
        boolean available, included, transfer;
        long income, cost;

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
        if (transaction == null || flow == null || transaction.action() == ActionKind.DEFERRED_CLAIM)
            return NONE;
        var effective = Accounting.flow(transaction, flow);
        if (!effective.available || !effective.included || effective.cost <= 0) return NONE;
        if (flow.item == GE_TAX_ITEM_ID || transaction.original() == PK_DEATH_LOSS)
            return LOSS;
        if (transaction.original() == TRADE) return MARKET;
        var type = transaction.original();
        // Supplies are consumables the player used (eat / drink / cast / fire / charges /
        // processing
        // inputs), which the engine evidences with an action kind or a supply-shaped type. A plain
        // "value decreased" consumption with no evidence is an item gone, i.e. a loss.
        // A production run's input is a supply even when that action made nothing (a failed smelt).
        if (type == PK_SUPPLY_COST || type == PROCESSING || transaction.action() != null
                || transaction.context() == Context.PRODUCTION)
            return SUPPLIES;
        return LOSS;
    }
}

class Session extends SaveCopies {
    /** The always-on General tracker is shown as "Overall". */
    static final String DURABLE_OWNER_NAME = "Overall";

    static final int MAX_UNDO_HISTORY = 32;
    String id, name, activityHint;
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
        row.setOnGround(false);
        if (ownedValueObserver != null) ownedValueObserver.onUsedUp(row);
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

    /** Announces a changed row: totals, PvP and the owned-value observer follow it. */
    void changed(Transaction transaction, Correction previous, List<Flow> previousFlows) {
        transactionChanged(transaction);
        if (ownedValueObserver != null)
            ownedValueObserver.onChanged(transaction, previous, previousFlows);
    }

    void log(String reason, Change... changes) {
        correctionHistory.add(new CorrectionRecord(reason, Arrays.asList(changes)));
    }

    @SerializedName("startedAtEpochMillis")
    long startedAt;
    @SerializedName("endedAtEpochMillis")
    long endedAt;

    boolean paused;
    PauseReason pauseReason = NONE;
    long pausedAtEpochMillis, totalPausedMillis;
    List<Transaction> transactions = new ArrayList<>();
    PkTally pk = new PkTally();

    List<CorrectionRecord> correctionHistory = new ArrayList<>();
    List<UndoRecord> undoHistory = new ArrayList<>();
    int transactionLimit;
    long retainedRevenue, retainedCosts, retainedSuppliesCosts;
    boolean retainedCostSplitComplete;
    long compactedTransactionCount;
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
    Long rateStartAt;
    long rateSetMillis;
    /** Optional frozen clock backup and the time resumed by Restore time. */
    Long savedRateMillis, restoredRateMillis;
    /** Frozen displayed duration of a saved copy; absent on canonical runs. */
    Long savedActiveMillis;

    long liveElapsed(long now) {
        long elapsed = elapsed(now), from = sessionStartAt == null ? 0 : sessionStartMillis;
        if (rateStartAt != null && rateSetMillis >= 0 && rateSetMillis <= elapsed && rateStartAt <= now
                && (rateSetMillis >= from && rateStartAt >= (sessionStartAt == null ? startedAt : sessionStartAt)
                    || rateSetMillis == 0 && rateStartAt == startedAt)
                && (restoredRateMillis == null || restoredRateMillis >= 0 && restoredRateMillis <= rateSetMillis))
            return elapsed - rateSetMillis + (restoredRateMillis == null ? 0 : restoredRateMillis);
        return positive(elapsed - from);
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
    Long activeTimeTargetPauseMillis;
    String grindId;

    Session() {
        // Gson
    }

    Session(String name, long startedAt, SessionMode mode) {
        id = uuid();
        this.name = normalizeName(name);
        this.mode = mode == null ? SessionMode.GENERAL : mode;
        activityHint = "General";
        this.startedAt = startedAt;
        transactionLimit = 2000;
        retainedCostSplitComplete = true;
    }

    /**
     * @param custodyOwned true for transactions already realized by a custody lifecycle; their item
     *     legs consumed their reservation and must never deplete again
     */
    void addTransaction(Transaction transaction, int maxTransactions, boolean owned) {
        if (transaction == null) return;
        transactionLimit = max(1, maxTransactions);
        transactions.add(transaction);
        transactionChanged(transaction);
        notifyOwnedValue(transaction, owned);
        evict(transactions.size() - transactionLimit);
    }

    /** Drops the oldest rows over the limit, each summarized exactly into the retained totals. */
    void evict(int overflow) {
        if (overflow <= 0) return;
        var removed = new ArrayList<Transaction>();
        for (var row : transactions)
            if (removed.size() < overflow && !ReviewRules.needsReview(row)) removed.add(row);
        transactions.removeAll(removed);
        var ids = new HashSet<String>();
        for (var row : removed) {
            compactTransaction(row);
            ids.add(row.id());
        }
        pruneHistories(ids);
    }

    void recordAction(String hint, long now) {
        setActivityHint(normalizeActivity(hint), now);
    }

    void setActivityHint(String hint, long now) {
        if (blank(hint)) return;
        String next = normalizeActivity(hint), current = activityHint();
        if (next.equalsIgnoreCase(current)) return;
        activityHint = next;
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
        Long next = value == null || value <= 0 ? null : value;
        if (Objects.equals(next, profitTargetGp))
            return next != null && targetProgress(net) >= next
                    && rebaseTarget(net, active);
        profitTargetGp = next;
        rebaseTarget(net, active);
        return true;
    }

    /** The run's Net when the aim was set; 0 with none and on older saves. */
    long profitTargetSetNet() {
        return profitTargetSetNet == null || profitTargetSetNet < 0 ? 0 : profitTargetSetNet;
    }

    /** What the Net aim has counted so far; sessionNet is the run's total. */
    long targetProgress(long net) {
        return sessionStartAt != null ? metrics(rowsSince(sessionStartAt), 0).net
                : net - profitTargetSetNet();
    }

    /**
     * Re-bases the set Net aim's count on the given net, keeping its value (owner live 2026-10-14):
     * Reset counts the aim from now whether or not it was reached.
     */
    boolean rebaseTarget(long net, long active) {
        profitTargetSetNet = profitTargetGp == null ? null : net;
        profitTargetSetActiveMillis = profitTargetGp == null ? null : active;
        return profitTargetGp != null;
    }

    /** Active Time the Net aim has run for; the whole run on older saves. */
    long profitTargetActiveMillis(long elapsed) {
        return positive(elapsed - (profitTargetSetActiveMillis == null ? 0 : profitTargetSetActiveMillis));
    }

    /** The actual Active-Time target this instance ran with; null when none was set. */
    Long timeTargetMillis() {
        return activeTimeTargetMillis != null && activeTimeTargetMillis > 0 ? activeTimeTargetMillis : null;
    }

    /**
     * Sets the actual Active-Time target and restarts its count when the value changes (owner live
     * 2026-10-13): a target counts from the moment it was set, not from the run's start. An
     * unchanged value keeps the running count; a cleared one forgets it.
     */
    boolean setTimeTarget(Long value, long now) {
        Long next = value == null || value <= 0 ? null : value;
        if (Objects.equals(next, activeTimeTargetMillis)) {
            // Re-saving the same aim keeps a running window; a completed one starts over
            // (owner live 2026-10-13: Edit > Save after completion must begin again).
            if (next != null && elapsed(now) - timeSetMillis() >= next) {
                activeTimeTargetSetMillis = elapsed(now);
                activeTimeTargetPauseMillis = null;
                return true;
            }
            return false;
        }
        activeTimeTargetMillis = next;
        activeTimeTargetSetMillis = next == null ? null : elapsed(now);
        activeTimeTargetPauseMillis = null;
        return true;
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
        if (activeTimeTargetMillis == null || activeTimeTargetPauseMillis != null) return false;
        if (timeWindowMillis(elapsed(now)) >= activeTimeTargetMillis)
            return false;
        activeTimeTargetPauseMillis = elapsed(now);
        return true;
    }

    /** Resumes a paused timer, keeping the window it had already counted. */
    boolean resumeTimeTarget(long now) {
        if (activeTimeTargetMillis == null || activeTimeTargetPauseMillis == null) return false;
        activeTimeTargetSetMillis = timeSetMillis()
                        + positive(elapsed(now) - activeTimeTargetPauseMillis);
        activeTimeTargetPauseMillis = null;
        return true;
    }

    /**
     * Restarts the set target's count from now without changing its value (owner live 2026-10-13).
     */
    void rearmTimeTarget(long now) {
        if (activeTimeTargetMillis != null) {
            activeTimeTargetSetMillis = elapsed(now);
            activeTimeTargetPauseMillis = null;
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
            paused = true;
            pausedAtEpochMillis = now;
            pauseReason = reason == null ? MANUAL : reason;
        }
    }

    void resume(long now) {
        if (paused && endedAt == 0) {
            totalPausedMillis = sum(totalPausedMillis, positive(now - pausedAtEpochMillis));
            pausedAtEpochMillis = 0;
            paused = false;
        }
        pauseReason = NONE;
    }

    PauseReason pauseReason() {
        if (!paused) return NONE;
        return pauseReason == null || pauseReason == NONE ? MANUAL : pauseReason;
    }

    /** A closed run carries on: the time it was closed counts as paused (owner 1.5.2, Restore). */
    void reopen(long now) {
        if (paused) totalPausedMillis = sum(totalPausedMillis, positive(endedAt - pausedAtEpochMillis));
        totalPausedMillis = sum(totalPausedMillis, positive(now - endedAt));
        paused = false;
        pausedAtEpochMillis = 0;
        pauseReason = NONE;
        endedAt = 0;
        saveStamp++;
    }

    void close(long now) {
        if (endedAt != 0) return;
        saveStamp++;
        endedAt = max(now, startedAt);
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
        if (estimate == null || id <= 0 || quantity <= 0) return;
        var previous = estimate.correction();
        var previousFlows = estimate.flows();
        estimate.takeQuantity(id, false, quantity);
        estimate.confidence = Confidence.CONFIRMED;
        if (!estimate.spends()) estimate.applyCorrection(IGNORE, reason);
        estimate.setExplanation(reason);
        changed(estimate, previous, previousFlows);
    }

    /**
     * Applies one decision to the still-unresolved transactions and stores one correction record,
     * so a single undo restores the complete operation.
     */
    int correctAll(List<String> ids, Correction decision, String reason) {
        var next = decision == null ? AUTO : decision;
        if (empty(ids) || next == AUTO) return 0;
        var targets = new ArrayList<Transaction>();
        for (String id : new LinkedHashSet<>(ids)) {
            var transaction = findTransaction(id);
            if (ReviewRules.needsReview(transaction)) targets.add(transaction);
        }
        return correct(targets, next, fallback(reason, msg("bulk-review"))) ? targets.size() : 0;
    }

    /**
     * One owner decision over rows: every row is checked with the owned-value observer before any
     * changes (one blocked row rejects the whole decision), then one undoable record is logged.
     */
    boolean correct(List<Transaction> targets, Correction next, String reason) {
        var changes = new ArrayList<Change>();
        for (var transaction : targets) {
            if (!canEditValue(transaction, transaction.correction(), transaction.flows(), next,
                    transaction.flows()))
                // The item's known coverage is reserved or already realized; leave the rows
                // untouched.
                return false;
            changes.add(new Change(transaction.id(), transaction.correction(), next, null, null));
        }
        for (var transaction : targets) {
            var previous = transaction.correction();
            transaction.applyCorrection(next, reason);
            changed(transaction, previous, transaction.flows());
        }
        if (!changes.isEmpty()) correctionHistory.add(new CorrectionRecord(reason, changes));
        return !changes.isEmpty();
    }

    /**
     * Personal item split: Net keeps only {@code keepQuantity} of {@code itemId}. Given-away qty is
     * removed from counted gain (not Used). Full give-away on a single-item row IGNORE-corrects.
     * Shares the correction undo stack.
     */
    boolean splitItem(String identifier, int id, long keepQuantity, String note) {
        var transaction = findTransaction(identifier);
        if (transaction == null || id <= 0) return false;
        long gain = transaction.quantity(id, true);
        if (gain <= 0) return false;
        long keep = positive(min(keepQuantity, gain));
        if (keep == gain) return false;
        String reason = ItemSplits.reason(keep, gain, note);
        var previous = transaction.correction();
        var flows = new ArrayList<>(transaction.flows());
        String explanationSnapshot = transaction.explanation();
        boolean ignore = keep <= 0 && onlyGainItem(transaction, id);
        if (!canEditValue(transaction, previous, flows, ignore ? IGNORE : previous, null))
            // The known coverage this split would shrink is reserved or already realized.
            return false;
        if (ignore) transaction.applyCorrection(IGNORE, reason);
        else transaction.takeQuantity(id, true, gain - keep);
        transaction.stampSplit(reason);
        changed(transaction, previous, flows);
        log(reason, new Change(identifier, previous, transaction.correction(), flows,
                        explanationSnapshot));
        return true;
    }

    /**
     * Reverts the most recent correction/split when a flow snapshot is present, or restores the
     * previous correction enum otherwise.
     */
    boolean undoCorrection() {
        if (correctionHistory.isEmpty()) return false;
        CorrectionRecord last = null;
        for (int index = correctionHistory.size() - 1; index >= 0; index--) {
            var candidate = correctionHistory.get(index);
            if (candidate != null && !candidate.undone) {
                last = candidate;
                break;
            }
        }
        if (last == null) return false;
        var changes = last.changes();
        if (changes.isEmpty()) return false;
        var targets = new ArrayList<Transaction>(changes.size());
        for (Change change : changes) {
            var transaction = findTransaction(change.transactionId());
            if (transaction == null)
                // Fail closed: never partially undo a batch after retention or deletion.
                return false;
            targets.add(transaction);
        }
        // Validate the whole restore before any canonical mutation.
        for (int index = 0; index < changes.size(); index++) {
            var change = changes.get(index);
            var transaction = targets.get(index);
            var next = change.hasFlowSnapshot() ? change.flowSnapshot() : transaction.flows();
            if (!canEditValue(transaction, transaction.correction(), transaction.flows(),
                    change.previousCorrection(), next))
                return false;
        }
        for (int index = changes.size() - 1; index >= 0; index--) {
            var change = changes.get(index);
            var transaction = targets.get(index);
            var previous = transaction.correction();
            var previousFlows = transaction.flows();
            if (change.hasFlowSnapshot()) transaction.replaceFlows(change.flowSnapshot());
            if (change.explanationSnapshot != null)
                transaction.setExplanation(change.explanationSnapshot);
            transaction.applyCorrection(change.previousCorrection(), "Manual correction");
            transaction.correctionReason = previousReason(transaction.id(), last);
            changed(transaction, previous, previousFlows);
        }
        last.undone = true;
        return true;
    }

    /** Restore metadata from the retained decision timeline without adding save fields. */
    String previousReason(String id, CorrectionRecord undone) {
        for (int index = correctionHistory.indexOf(undone) - 1; index >= 0; index--) {
            var record = correctionHistory.get(index);
            if (record == null || record.undone) continue;
            for (Change change : record.changes()) {
                if (id.equals(change.transactionId()) && (!change.hasFlowSnapshot()
                                || change.previousCorrection() != change.newCorrection
                                || ItemSplits.splitReason(record.reason())))
                    return record.reason();
            }
        }
        return "";
    }

    static boolean onlyGainItem(Transaction transaction, int id) {
        if (transaction == null) return false;
        boolean sawTarget = false;
        for (Flow flow : transaction.flows()) {
            if (flow.quantity == 0) continue;
            if (flow.item != id) return false;
            if (flow.quantity > 0) sawTarget = true;
        }
        return sawTarget;
    }

    /**
     * A dropped item picked back up: the Dropped row loses that quantity, and is excluded once it
     * spends nothing, so the drop and its pickup never show as a loss and a gain. Returns how many
     * the row still held to give back.
     */
    long pickUp(String dropId, int id, long quantity, long now) {
        var drop = findTransaction(dropId);
        if (drop == null || !drop.counts() || drop.correction() != AUTO) return 0;
        long taken = min(quantity, drop.quantity(id, false));
        if (taken <= 0) return 0;
        var flows = new ArrayList<>(drop.flows());
        drop.takeQuantity(id, false, taken);
        if (!drop.spends()) drop.applyCorrection(IGNORE, "Picked back up");
        changed(drop, AUTO, flows);
        return taken;
    }

    /** Drops a neutral row the engine no longer keeps (old bank-paid GE change, owner 1.5.1). */
    void drop(Transaction transaction) {
        if (!transactions.remove(transaction)) return;
        saveStamp++;
        notifyChanged();
        if (ownedValueObserver != null) ownedValueObserver.onRemoved(transaction);
    }

    Transaction undoTransaction() {
        if (transactions.isEmpty()) return null;
        var removed = transactions.get(transactions.size() - 1);
        if (ownedValueObserver != null && !ownedValueObserver.canRemove(removed))
            // Known coverage was already reserved/realized downstream; leave it untouched.
            return null;
        transactions.remove(transactions.size() - 1);
        notifyChanged();
        if (ownedValueObserver != null) ownedValueObserver.onRemoved(removed);
        undoHistory.add(new UndoRecord(removed));
        int overflow = undoHistory.size() - MAX_UNDO_HISTORY;
        if (overflow > 0) undoHistory.subList(0, overflow).clear();
        return removed;
    }

    Transaction restoreUndo() {
        for (int index = undoHistory.size() - 1; index >= 0; index--) {
            var record = undoHistory.get(index);
            if (record == null || record.restored || record.transaction == null) continue;
            var restored = record.transaction;
            if (ownedValueObserver != null && !ownedValueObserver.canAdd(restored))
                // Restoring would re-add known coverage after a later realization; refuse it.
                return null;
            int effectiveLimit = transactionLimit <= 0 ? 2000 : transactionLimit;
            // Restoring an undo is also a bounded-ledger insertion: the displaced row is summarized
            // exactly.
            evict(transactions.size() + 1 - effectiveLimit);
            transactions.add(restored);
            transactionChanged(restored);
            notifyOwnedValue(restored, false);
            record.restored = true;
            return restored;
        }
        return null;
    }

    Transaction findTransaction(String id) {
        if (empty(id)) return null;
        for (var transaction : transactions) {
            if (id.equals(transaction.id())) return transaction;
        }
        return null;
    }

    /** Counts one kill or death on this Grind and returns the id its receipts carry. */
    String addEncounter(boolean kill, long now) {
        String id = pk().record(kill);
        notifyChanged();
        recordAction("PKing", now);
        return id;
    }

    void attachToEncounter(String id, String encounter, boolean pkSupply) {
        var transaction = findTransaction(id);
        if (transaction == null || empty(encounter) || !(encounter.startsWith(PkTally.KILL) || encounter.startsWith(PkTally.DEATH))) return;
        saveStamp++;
        transaction.assignEncounter(encounter, pkSupply);
        notifyChanged();
    }

    void attachRecentCosts(String encounter, long now, long lookbackMillis) {
        long cut = positive(now - positive(lookbackMillis));
        for (int index = transactions.size() - 1; index >= 0; index--) {
            var transaction = transactions.get(index);
            if (transaction.timestamp < cut) break;
            if (!transaction.encounterId().isEmpty()) continue;
            if (transaction.revenue == 0 && transaction.costs > 0
                    && transaction.original() == CONSUMPTION)
                attachToEncounter(transaction.id(), encounter, true);
        }
    }

    void compactTransaction(Transaction transaction) {
        if (transaction == null) return;
        compactedTransactionCount++;
        RaidHistory.compact(this, transaction);
        if (transaction.onGround()) usedUp(transaction);
        pk().compact(transaction, transactions);
        if (transaction.type() == TransactionType.TRANSFER) return;
        if (transaction.counts()) {
            var money = transaction(transaction);
            retainedRevenue = sum(retainedRevenue, money.income);
            retainedCosts = sum(retainedCosts, money.cost);
            var split = costSplit(transaction);
            if (!split.available) retainedCostSplitComplete = false;
            else retainedSuppliesCosts = sum(retainedSuppliesCosts, split.supplies);
        }
    }

    /** A counted row changed: advance the revision. */
    void transactionChanged(Transaction transaction) {
        if (transaction != null) notifyChanged();
    }

    PkTally pk() {
        if (pk == null) pk = new PkTally();
        return pk;
    }

    long bestKill() {
        return pk().bestKill(transactions);
    }

    @AllArgsConstructor
    static class MoneyFold {
        long income, cost, supplies;
        boolean split = true;

        Amounts add(Transaction transaction) {
            var money = transaction(transaction);
            if (!money.available || !money.included) return money;
            income = sum(income, money.income);
            cost = sum(cost, money.cost);
            var split = costSplit(transaction);
            if (!split.available) this.split = false;
            else supplies = sum(supplies, split.supplies);
            return money;
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

    /** The money in these rows alone, over the given Active Time. */
    Metrics metrics(List<Transaction> rows, long elapsed) {
        return metrics(new MoneyFold(0, 0, 0, true), rows, elapsed);
    }

    Metrics metrics(MoneyFold money, List<Transaction> rows, long elapsed) {
        for (var transaction : rows) {
            if (transaction == null) continue;
            money.add(transaction);
        }
        if (money.supplies > money.cost) money.split = false;
        long net = subtract(money.income, money.cost);
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
        long rows, income, cost, supplies;
        boolean split;

        long net() {
            return subtract(income, cost);
        }
    }

    CompactedContribution compactedContribution() {
        return new CompactedContribution(compactedTransactionCount, retainedRevenue, retainedCosts,
                retainedSuppliesCosts, compactedTransactionCount == 0 || retainedCostSplitComplete);
    }

    static long hourly(long value, long elapsed) {
        if (elapsed <= 0) return 0;
        // Math.round saturates at the long range by itself.
        return round(value * (3_600_000.0d / elapsed));
    }

    long elapsed(long now) {
        if (endedAt != 0 && savedActiveMillis != null && savedActiveMillis >= 0)
            return savedActiveMillis;
        long endpoint = endedAt == 0 ? now : endedAt;
        long activePaused = paused ? positive(endpoint - pausedAtEpochMillis) : 0;
        return positive(endpoint - startedAt - totalPausedMillis - activePaused);
    }

    static String normalizeName(String value) {
        return fallback(value, DURABLE_OWNER_NAME);
    }

    String id() {
        if (empty(id)) id = uuid();
        return id;
    }

    String name() {
        return normalizeName(name);
    }

    void setChangeListener(Runnable listener) {
        changeListener = listener;
    }

    void notifyChanged() {
        saveStamp++;
        var listener = changeListener;
        if (listener != null) listener.run();
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
        return profitTargetGp != null && profitTargetGp > 0 ? profitTargetGp : null;
    }

    /** A loaded session is cleaned once: no null rows or flows reach any reader. */
    void sanitize() {
        if (transactions == null) transactions = new ArrayList<>();
        transactions.removeIf(Objects::isNull);
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
        var folded = new ArrayList<Transaction>();
        for (var transaction : transactions) {
            if (transaction == null) continue;
            if (transaction.timestamp >= cutoffEpochMillis) continue;
            if (keep != null && keep.test(transaction)) continue;
            folded.add(transaction);
        }
        if (folded.isEmpty()) return 0;
        saveStamp++;
        var foldedIds = new HashSet<String>();
        for (var transaction : folded) {
            foldedIds.add(transaction.id());
            transactions.remove(transaction);
            compactTransaction(transaction);
        }
        pruneHistories(foldedIds);
        return folded.size();
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
    Transaction transaction;
    boolean restored;

    UndoRecord() {
        // Gson
    }

    UndoRecord(Transaction transaction) {
        this.transaction = transaction;
    }
}

/** Only the canonical money, active clock and rate facts consumed by presentation and export. */
class Metrics {
    String activity;
    boolean paused;
    long elapsed, income, cost, net, rate, supplies, other;
    boolean split;

    static Metrics none() {
        return new Metrics("General", false, 0, 0, 0, 0, 0, 0, 0, false);
    }

    Metrics(String activity, boolean paused, long elapsed, long income, long cost, long net,
            long rate, long suppliesCosts, long otherCosts, boolean costSplitAvailable) {
        this.activity = activity;
        this.paused = paused;
        this.elapsed = elapsed;
        this.income = income;
        this.cost = cost;
        this.net = net;
        this.rate = rate;
        supplies = suppliesCosts;
        other = otherCosts;
        split = costSplitAvailable && sum(suppliesCosts, otherCosts) == cost;
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
        compactOlderThan(retentionDays(), now);
        swept = utcDay(now);
    }

    void adoptFrom(SessionArchive draft) {
        swept = draft.swept;
    }

    /** The configured receipt retention in days; 0 keeps receipts forever. */
    int retentionDays() {
        var period = engine.config.receiptRetentionDays();
        return period == null ? Retention.DAYS_90.days : period.days;
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
        if (days <= 0) return 0;
        long cut;
        try {
            cut = Instant.ofEpochMilli(now).minus(days, ChronoUnit.DAYS).toEpochMilli();
        } catch (RuntimeException ex) {
            cut = Long.MIN_VALUE;
        }
        // Rows still needed individually: an unresolved claim's audit row and the settlement that
        // carries a claim id keep their detail until the claim resolves (section L).
        var claims = engine.pendingClaimIds();
        Predicate<Transaction> keep = transaction -> ReviewRules.needsReview(transaction) || isPendingClaimRow(transaction, claims)
                                || !transaction.claimId().isEmpty()
                                        && claims.contains(transaction.claimId());
        int compacted = 0;
        for (var session : engine.sessions()) {
            if (session == null || session.transactions().isEmpty()) continue;
            compacted = safeAddCount(compacted, session.compactBefore(cut, keep));
        }
        if (compacted > 0) engine.changed();
        return compacted;
    }

    /**
     * A multi-key audit row owns claims named {@code auditId#n}; retain its base row while any
     * derived claim remains unresolved so provenance survives receipt compaction.
     */
    static boolean isPendingClaimRow(Transaction transaction, Set<String> claims) {
        if (transaction == null || empty(claims)) return false;
        String id = transaction.id();
        if (claims.contains(id)) return true;
        String derivedPrefix = id + "#";
        for (String claimId : claims) {
            if (claimId != null && claimId.startsWith(derivedPrefix)) return true;
        }
        return false;
    }

    /**
     * The game tick's daily sweep: the first tick of each later UTC day compacts once. The first
     * tick of a run only marks the day, since a restore already swept.
     */
    int maintainRetention(long now) {
        var today = utcDay(now);
        if (swept != null && !today.isAfter(swept)) return 0;
        boolean first = swept == null;
        swept = today;
        return first ? 0 : compactOlderThan(retentionDays(), now);
    }

    Session historySession(String id) {
        if (empty(id)) return null;
        for (var session : engine.history) {
            if (id.equals(session.id())) return session;
        }
        return null;
    }

    Metrics historyMetrics(String id, long now) {
        var session = historySession(id);
        return session == null ? null : session.metrics(now);
    }

    boolean deleteHistory(String id) {
        var session = historySession(id);
        if (session == null) return false;
        engine.history.remove(session);
        engine.changed();
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
class Semantics {
    /** Financial table destination for the future stacked Ledger and the Live consumer. */
    @RequiredArgsConstructor
    enum Table {
        GAINS("GAINS"), COSTS_SUPPLIES("SUPPLY"), COSTS_LOSS("LOSS"), MARKET("TRADE"), AUDIT("Review"), DEATH("DEATH"), KILL("KILLS");
        final String label;
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
    static class Receipt {
        String id, transaction;
        long at;
        int item;
        String name;

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

        boolean unknown, neutral, review, corrected, split;
        Category category;
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
        String traded;
        boolean bag;

        boolean gain() {
            return category == Category.GAIN;
        }

        /** The Corrected audit search; financial table search uses the projection group instead. */
        boolean matchesSearch(String needle) {
            return empty(needle) || containsFolded(needle.toLowerCase(ROOT), name, verb, action,
                            source, why, correction, transaction, id);
        }
    }

    /**
     * One semantic presentation group; never a correction identity and never persisted. Each unit
     * of activity starts as a group; units sharing a merge key agree on everything the key encodes
     * (table, flags, consume shape), so the newest keeps those and later ones only add value, time,
     * quantity, receipts and search terms.
     */
    static class Group {
        String id;

        /** Display item id for the row sprite; -1 for action composites. */
        int item;

        Table table;
        String name;

        /** The honest secondary line: "Used by Toxic blowpipe", "3 rune types", "Sell". */
        String context;

        String label;

        /** Measured-charge equipment context ("Toxic blowpipe"), or empty for ordinary rows. */
        String uses;

        /** Measured or estimated charge use: the Losses Charges chip, never a Supplies row. */
        boolean charge;

        long at, value;
        Coverage coverage;

        /** Semantic quantity where honestly known; -1 when it cannot be stated. */
        long quantity;

        /** +1 claim pickup, -1 claim spend, 0 for an ordinary resource row. */
        int claim;

        boolean dose;
        int count = 1;
        boolean composite, neutral, market, review, corrected, death, pvpDeath;
        String marketId, transaction, contributionId;
        public Set<String> ids = new LinkedHashSet<>();
        public List<Receipt> entries = new ArrayList<>();
        public Set<String> terms = new LinkedHashSet<>();

        void search(String... terms) {
            addAll(this.terms, terms);
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
            item = identifier;
            this.table = table;
            name = primary;
            context = contextLine;
            this.label = label;
            uses = usedBy;
            charge = chargeUse;
            this.at = at;
            this.value = value;
            this.coverage = coverage;
            this.quantity = quantity;
            dose = normalizedConsume;
            this.composite = composite;
            this.neutral = neutral;
            this.market = market;
            this.review = review;
            this.corrected = corrected;
            marketId = marketPresentationId;
            transaction = id;
            this.contributionId = contributionId;
            this.claim = claim;
            ids.add(id.isEmpty() ? contributionId : id);
            ids.remove("");
        }

        /** Adds a later unit with the same merge key. */
        Group absorb(Group unit) {
            value = sum(value, unit.value);
            at = max(at, unit.at);
            coverage = unit.coverage == INCOMPLETE ? INCOMPLETE : coverage;
            quantity = quantity < 0 || unit.quantity < 0 ? -1 : sum(quantity, unit.quantity);
            units++;
            ids.addAll(unit.ids);
            count = ids.isEmpty() ? units : ids.size();
            entries.addAll(unit.entries);
            terms.addAll(unit.terms);
            failed += unit.failed;
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
            if (empty(id)) return false;
            if (this.id.equals(id)) return true;
            for (var receipt : entries) {
                if (id.equals(receipt.id)) return true;
            }
            return false;
        }

        /** True when this group represents the given canonical transaction identity. */
        boolean containsTransaction(String id) {
            if (empty(id)) return false;
            if (id.equals(transaction)) return true;
            if (ids.contains(id)) return true;
            for (var receipt : entries) {
                if (id.equals(receipt.transaction)) return true;
            }
            return false;
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
            if (query == null) return true;
            String needle = lower(query);
            if (needle.isEmpty()) return true;
            for (String term : terms) {
                if (term != null && term.toLowerCase(ROOT).contains(needle)) return true;
            }
            return false;
        }
    }

    /** The full derived read: the groups, newest first. */
    static class Result {
        List<Group> groups;

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
    static class Summary {
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
            return sum(sum(income, cost), market);
        }
    }

    /** Recoverable grave gear is neutral and never appears as a Recent/Ledger row. */
    static boolean graveGear(Transaction transaction) {
        return transaction.note().equals(msg("death-items"))
                || transaction.note().equals(msg("death-reclaim-items"))
                || transaction.note().equals(msg("reclaim-expired"));
    }

    /** Stable group ids by merge key; hashing is paid once per key, not on every tick. */
    static final Map<String, String> GROUP_IDS = new ConcurrentHashMap<>();

    /** Multi-session scope: Today and future period consumers merge compatible activity. */
    static Result capture(List<Transaction> transactions, List<Row> rows, Set<String> sessionIds,
            Predicate<Flow> td) {
        Predicate<Flow> visibility = td == null ? flow -> true : td;
        var units = new ArrayList<Group>();
        var representedSettlements = new LinkedHashSet<String>();
        var receipts = new LinkedHashMap<String, Transaction>();
        if (transactions != null) {
            for (var transaction : transactions) {
                if (transaction != null) receipts.put(transaction.id(), transaction);
            }
        }
        if (rows != null) {
            for (Row market : rows) {
                if (market == null || sessionIds == null || !sessionIds.contains(market.scopeSessionId)) continue;
                // Every collect of the offer folds into its one market row (owner 2026-10-09).
                var settlements = new ArrayList<Transaction>();
                for (String id : (market.settlementId + market.earlierSettlements).split(" ")) {
                    representedSettlements.add(id);
                    if (receipts.containsKey(id)) settlements.add(receipts.get(id));
                }
                units.add(marketUnit(market, settlements));
            }
        }
        if (transactions != null) {
            for (var transaction : transactions) {
                // An uncounted trade is custody's audit copy: its market row already shows the sale
                // (owner 2026-09-28: a second fill read "Bones ×2 · 0").
                if (transaction == null || representedSettlements.contains(transaction.id())
                        || transaction.type() == TRADE && !transaction.counts()
                        || graveGear(transaction))
                    continue;
                units.addAll(transactionUnits(transaction, visibility));
            }
        }
        units.sort(comparingLong((Group unit) -> unit.at).reversed()
                        .thenComparing(unit -> unit.contributionId));
        var buckets = new LinkedHashMap<String, Group>();
        for (Group unit : units) buckets.merge(unit.id, unit, Group::absorb);
        var groups = new ArrayList<>(buckets.values());
        for (var group : groups)
            if (group.pvpDeath && group.value < 0 && !group.incomplete() && !group.review)
                for (int cap : SKULL_TIERS) if (abs(group.value) > cap) group.item--;
        groups.sort(comparingLong((Group group) -> group.at).reversed()
                        .thenComparing(group -> group.id));
        return new Result(groups);
    }

    static final int[] SKULL_TIERS = intColumn("pvp_skulls", 0);

    /** Reconciled counts over any group set (the search-filtered Ledger tables reuse this). */
    static Summary summarize(Collection<Group> groups) {
        long income = 0, cost = 0, marketTotal = 0;
        int entries = 0;
        boolean complete = true;
        var records = new LinkedHashSet<String>();
        if (groups == null) return new Summary(0, 0, 0, 0, 0, true);
        for (Group group : groups) {
            if (group.review || group.neutral || group.table == Table.AUDIT) continue;
            if (group.table == MARKET) {
                if (group.coverage != COMPLETE) {
                    // Pending/unrealised Market is context, not a counted financial entry.
                    complete = false;
                    continue;
                }
                marketTotal = sum(marketTotal, group.value);
            } else if (group.table == GAINS)
                income = sum(income, group.value); else if (group.table == COSTS_SUPPLIES || group.table == COSTS_LOSS)
                cost = sum(cost, group.value); else
                continue;
            if (group.coverage == INCOMPLETE) complete = false;
            if (!group.corrected) {
                entries++;
                records.addAll(group.ids);
            }
        }
        return new Summary(income, cost, marketTotal, entries, records.size(), complete);
    }

    // ── unit construction ──────────────────────────────────────────────────────────────────────
    /**
     * A composite row's group: claim-spend, action and charge-cast units share the fixed tail (no
     * normalised consume, no market lifecycle, no claim count). Field order mirrors the
     * constructor's, so a transposition reads wrong at a glance.
     */
    static Group composite(String key, int identifier, Table table, String primary,
            String contextLine, String label, boolean chargeUse, long at, long value,
            boolean unknown, long quantity, boolean neutral, boolean review, boolean corrected,
            String transactionId, String id) {
        return new Group(key, identifier, table, primary, contextLine, label, "", chargeUse,
                at, value, coverage(unknown), quantity, false, true, neutral, false, review,
                corrected, "", transactionId, id, 0);
    }

    static List<Group> transactionUnits(Transaction transaction, Predicate<Flow> td) {
        var flows = transaction.flows();
        boolean neutral = transaction.isTransfer(), review = needsReview(transaction);
        boolean fixed = transaction.correction() != AUTO, split = Contribution.isSplit(transaction);
        boolean claimLinked = !transaction.claimId().isEmpty()
                        || transaction.action() == ActionKind.DEFERRED_CLAIM;
        long at = transaction.timestamp;
        String source = Contribution.sourceOf(transaction);
        if (flows.isEmpty()) return noteUnits(transaction, neutral, review, fixed, at, source);
        var projected = Contribution.project(transaction);
        // An opened claim's proceeds are the action's income: a loot minimum never hides them.
        boolean claimSpend = false;
        for (var contribution : projected)
            claimSpend |= contribution.source == PriceSource.DEFERRED_CLAIM && contribution.quantityDelta < 0;
        var visible = new ArrayList<Contribution>();
        for (var part : projected) {
            boolean visibleFlow = false;
            for (Flow raw : part.flows) visibleFlow |= td.test(raw);
            if (visibleFlow || neutral || review || claimSpend || part.unpriced())
                visible.add(part);
        }
        if (visible.isEmpty()) return emptyList();
        // An opened claim (a coin pouch, an unopened key) is one action row: the claim count and
        // the net it yielded, never a claim leg plus separate proceeds (owner 2026-09-29).
        if (claimSpend && !neutral && !review && !fixed && !split) {
            long spent = 0, net = 0;
            boolean unknown = false, claimOnly = true;
            String name = null;
            int icon = -1;
            for (var entry : visible) {
                long delta = entry.quantityDelta;
                if (delta < 0) {
                    if (entry.source != PriceSource.DEFERRED_CLAIM) {
                        claimOnly = false;
                        break;
                    }
                    spent -= delta;
                    if (name == null) {
                        name = entry.name;
                        icon = entry.item;
                    } else if (!name.equals(entry.name)) {
                        name = "";
                        icon = -1;
                    }
                } else if (delta > 0) {
                    net = sum(net, entry.value);
                    unknown |= entry.unpriced();
                }
            }
            if (claimOnly && spent > 0 && net > 0) {
                String verb = verbOf(transaction), primary = empty(name) ? verb : name;
                String key = "claim:" + primary + ":" + Long.signum(net) + ":" + coverageKey(unknown) + ":" + verb
                                + ":" + transaction.original().name();
                var unit = new Group(key, icon, GAINS, primary, "", verb, "", false, at, net, coverage(unknown),
                                spent, false, false, false, false, false, false, "", transaction.id(),
                                visible.get(0).id, -1);
                for (var contribution : visible)
                    unit.entries.add(receipt(transaction, contribution, null, null));
                unit.search(primary, verb, source, transaction.id());
                for (var contribution : visible)
                    unit.terms.add(contribution.name);
                return singletonList(unit);
            }
        }
        boolean death = transaction.original() == PK_DEATH_LOSS || transaction.note().equals(msg("pvm-loss"));
        boolean grave = neutral && (transaction.note().equals(msg("death-items"))
                        || transaction.note().equals(msg("death-reclaim-items")));
        // Legacy reclaim receipts already have this reserved activity and exact coin basis.
        boolean reclaim = transaction.original() == CONSUMPTION && "Death reclaim".equals(transaction.activityName())
                        && transaction.explanation().equals(transaction.note())
                        && flows.stream().allMatch(f -> f.cost() && f.item == 995 && f.source() == FACE_VALUE);
        if (transaction.action() == FEE || death || grave || reclaim) {
            long feeValue = 0;
            boolean missingFee = false;
            for (var contribution : visible) {
                missingFee |= contribution.unpriced();
                feeValue = sum(feeValue, contribution.value);
            }
            String feeName = death ? transaction.original() == PK_DEATH_LOSS ? "Death: PvP" : msg("pvm-loss")
                            : grave ? "Gravestone" : transaction.note();
            String key = death ? "death:" + (empty(transaction.encounterId()) ? transaction.id() : transaction.encounterId())
                            + ":" + transaction.correction() + ":" + review
                            : grave ? "grave:" + transaction.id()
                            : "fee:" + reclaim + ":" + feeName + ":" + coverageKey(missingFee) + ":" + Long.signum(feeValue);
            var unit = composite(key,
                            death ? -100000 : grave ? -1 : visible.get(0).item, tableFor(financialCategory(transaction, visible.get(0))), feeName,
                            grave ? transaction.note().equals(msg("death-items")) ? "Stored" : "Recovered" : "",
                            "", false, at, feeValue, missingFee, -1, neutral, review, fixed,
                            transaction.id(), visible.get(0).id);
            unit.composite = !death && !grave;
            unit.death = death || grave || reclaim;
            unit.pvpDeath = transaction.original() == PK_DEATH_LOSS;
            for (var contribution : visible) {
                unit.entries.add(receipt(transaction, contribution, null, null));
                unit.terms.add(contribution.name);
            }
            unit.search(feeName, source, transaction.id());
            return singletonList(unit);
        }
        // An estimated charge use is one row named by the weapon.
        String note = transaction.note(), prefix = msg("estimated-charge");
        String castName = note.startsWith(prefix) ? note.substring(prefix.length()).trim() : "";
        if (!castName.isEmpty()) {
            // A worn charge feeder (blood fury) is no cast: its estimate books the charged
            // resource "Used by" it — the same shape its measured Check produces (owner
            // 2026-09-29).
            var feeder = ChargeCheck.namedVariant(castName);
            // Owner 1.1: the blowpipe's rows are its Zulrah's scales (Charges) and darts
            // (Supplies);
            // blood fury stays one "Blood fury" row, as a shard covers 10,000 hits.
            if (feeder == ChargeCheck.ChargedItem.EYE_OF_AYAK || feeder == ChargeCheck.ChargedItem.BLOWPIPE) {
                var feasted = new ArrayList<Group>(visible.size());
                for (var contribution : visible)
                    feasted.add(resourceUnit(transaction, contribution, ChargeCheck.DARTS.containsValue(contribution.item) ? "" : castName, neutral,
                                    review, fixed, source, at));
                return feasted;
            }
            return singletonList(chargeCastUnit(transaction, visible, castName, source, at, neutral, review,
                            fixed));
        }
        // A measured charge spend books its resources as rows "Used by" the weapon, never a
        // composite.
        String weapon = transaction.action() == FIRE || transaction.action() == CAST
                        ? measuredChargeWeaponName(transaction) : "";
        if (ChargeCheck.namedVariant(weapon) == ChargeCheck.ChargedItem.BLOOD_FURY)
            return singletonList(chargeCastUnit(transaction, visible, weapon, source, at, neutral, review, fixed));
        if (weapon.isEmpty() && isActionComposite(
                        transaction, visible, neutral, review, fixed, split, claimLinked))
            return singletonList(actionUnit(transaction, visible, source, at));
        var units = new ArrayList<Group>(visible.size());
        for (var contribution : visible)
            units.add(resourceUnit(transaction, contribution, ChargeCheck.DARTS.containsValue(contribution.item) ? "" : weapon, neutral, review,
                            fixed, source, at));
        return units;
    }

    /**
     * A safe action composite: pure counted costs of one financial category from one spell cast or
     * one shot. Other actions (a sapling planted with its payment) stay per-item rows, as do mixed
     * gain+cost, Market, Transfer, Review, Corrected, split and claim-linked rows.
     */
    static boolean isActionComposite(Transaction transaction, List<Contribution> entries, boolean neutral,
            boolean review, boolean corrected, boolean split, boolean claimLinked) {
        if (neutral || review || corrected || split || claimLinked || transaction.action() != CAST
                        && transaction.action() != FIRE
                        && transaction.action() != DRINK)
            return false;
        if (entries.size() < 2) return false;
        // A named spell with a product (an alch's coins, Bones to Peaches' fruit) is one row by
        // its spell; a gain it does not make (a pickup) keeps the rows apart (owner live
        // 2026-10-16). Any other action keeps one shared cost category.
        Set<Integer> made = transaction.action() == CAST ? Spells.product(transaction.spellName())
                        : emptySet();
        boolean product = !made.isEmpty();
        var category = financialCategory(transaction, entries.get(0));
        if (!product && category != Category.SUPPLY && category != Category.LOSS)
            return false;
        for (var entry : entries) {
            var each = financialCategory(transaction, entry);
            if ((product ? entry.quantityDelta > 0 ? !made.contains(entry.item)
                                    : each != Category.SUPPLY && each != Category.LOSS : each != category)
                    || !entry.counted || entry.auditOnly)
                return false;
        }
        return true;
    }

    static Group actionUnit(Transaction transaction, List<Contribution> entries, String source, long at) {
        var category = financialCategory(transaction, entries.get(0));
        boolean unknown = false, mixed = false;
        long value = 0;
        for (var entry : entries) {
            unknown |= entry.unpriced();
            mixed |= financialCategory(transaction, entry) != category;
            value = sum(value, entry.value);
        }
        // A spell with a product sits by its net: an alch that made coins is a gain.
        if (mixed) category = value > 0 ? Category.GAIN : Category.SUPPLY;
        String exactName = transaction.spellName(), verb = verbOf(transaction);
        String primary = exactName.isEmpty() ? verb : exactName, context = compositionText(transaction, entries);
        String key = exactName.isEmpty() ? "action:" + transaction.action().wireName + ":generic="
                                + compositionKey(entries) + ":cov=" + coverageKey(unknown) : "action:"
                                + transaction.action().wireName + ":exact=" + exactName + ":cov="
                                + coverageKey(unknown);
        String representative = entries.get(0).id;
        // A row named by a spell or an item shows its icon; any other shows its first item's.
        int icon = Spells.icon(primary);
        var unit = composite(key, icon != -1 ? icon : entries.get(0).item, tableFor(category),
                        primary, context, verb, false, at, value, unknown, -1, false, false,
                        false, transaction.id(), representative);
        for (var part : entries)
            unit.entries.add(receipt(transaction, part, null, null));
        unit.search(primary, context, verb, source, transaction.id());
        for (var contribution : entries) unit.terms.add(contribution.name);
        return unit;
    }

    /** One resource row; with a weapon it is a measured charge spend "Used by" that weapon. */
    static Group resourceUnit(Transaction transaction, Contribution entry, String weapon, boolean neutral,
            boolean review, boolean corrected, String source, long at) {
        var category = financialCategory(transaction, entry);
        boolean unknown = entry.unpriced();
        // An unopened claim (a key or a coin pouch) is held at zero in Recent, never shown as a
        // value there; the Ledger's Pending line shows an observed open chest's worth instead.
        int claimSign = entry.source == PriceSource.DEFERRED_CLAIM
                        ? Long.signum(entry.quantityDelta) : 0;
        long value = neutral || claimSign != 0 || entry.auditOnly ? 0 : entry.value;
        String verb = verbOf(transaction), label = entry.action;
        String actionLabel = weapon.isEmpty() ? label.isEmpty() ? verb : verb + " · " + label : "";
        String context = weapon.isEmpty() ? actionLabel : "Used by " + weapon;
        // Owner 1.1: a production run's input and the same item its failures lost are one row.
        boolean run = transaction.context() == Context.PRODUCTION && weapon.isEmpty()
                        && entry.quantityDelta < 0;
        // A confirmed NPC pickup and an inventory-only pickup share an item row. Their receipts
        // retain the original evidence; actions, corrections, Review and other types stay distinct.
        TransactionType groupingType = category == Category.GAIN && !review && !corrected
                        && !Contribution.isSplit(transaction) && transaction.action() == null
                        && transaction.original() == LOOT ? GAIN : transaction.original();
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
        if (run && verb.equals(msg("failed", "Failed"))) unit.failed = positive(unit.quantity);
        unit.search(entry.name, context, verb, label, weapon, source, transaction.id());
        return unit;
    }

    static List<Group> noteUnits(Transaction transaction, boolean neutral, boolean review, boolean corrected, long at,
            String source) {
        var money = transaction(transaction);
        if (!money.included && !review && !neutral) return emptyList();
        long value = neutral ? 0 : money.net();
        var category = Contribution.noteCategory(transaction, money, neutral);
        String name = transaction.note().isEmpty() ? transaction.activityName() : transaction.note();
        String verb = verbOf(transaction), key = "note:" + transaction.id();
        var unit = composite(key, -1, tableFor(category), name, verb, verb, false, at, value, false, 0,
                        neutral, review, corrected, transaction.id(), transaction.id() + ":composite");
        unit.death = neutral && name.equals(msg("reclaim-expired"));
        unit.entries.add(new Receipt(transaction.id() + ":composite", transaction.id(), at, -1, name, 0, value,
                        transaction.autoNet(), false, neutral, review, corrected, Contribution.isSplit(transaction),
                        category, verb, transaction.spellName(), why(transaction, false, neutral),
                        correctionLabel(transaction.correction()), source, "", "", null, "", "", false));
        unit.search(name, verb, source, transaction.id());
        return singletonList(unit);
    }

    static Group marketUnit(Row market, List<Transaction> settlements) {
        boolean realized = market.realized() && market.realizedResultCorrectionAware;
        // Unknown-basis liquidation has no available Result: never show 0 as if break-even were
        // known. Its one exact contribution is a proven GE tax, which stays a market cost.
        boolean knownResult = realized && (market.coverage != MarketRows.Coverage.FULLY_UNKNOWN
                                || market.manualFinancialResult);
        boolean knownCost = realized && market.knownCostOnly, knownContribution = knownResult || knownCost;
        // A sale whose receipt was compacted is history: its money is in the retained totals and
        // there is nothing left to decide, so it shows "result unavailable", never Review.
        boolean review = market.lifecycle == AMBIGUOUS || market.lifecycle == UNAVAILABLE;
        String side = market.side.name().equals("SELL") ? "Sell" : "Buy";
        String status = knownResult ? side : knownCost ? side + msg("tax-counted") : market.realized()
                                        ? side + msg("result-unavailable") : lifecycleLabel(market);
        long quantity = market.savedNet != null ? market.savedQuantity
                        : market.realized() ? market.settledQty : market.filledQty;
        String id = market.presentationId, key = "market:" + id;
        var unit = new Group(key, market.itemId, MARKET, market.itemName, status, "", "", false,
                        market.timestampEpochMillis, market.savedNet != null ? market.savedNet
                                        : knownContribution ? market.realizedResultGp : 0,
                        knownContribution ? COMPLETE : INCOMPLETE, quantity, false, true, false, true,
                        review, false, id, market.settlementId, "market:" + id, 0);
        unit.search(market.itemName, status, side, market.lifecycle.name(), market.side.name(), id);
        if (!market.settlementId.isEmpty()) unit.terms.add(market.settlementId);
        // D2: a market group owns only its sale item's receipt. Its settlement's Coins leg is never
        // listed as a separate receipt or grouped with an unclaimed row.
        for (var batch : settlements) {
            for (var entry : Contribution.project(batch)) {
                if (entry.item == market.itemId)
                    unit.entries.add(receipt(batch, entry, null, market));
            }
        }
        return unit;
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────
    static Receipt receipt(Transaction transaction, Contribution entry, String usedBy, Row marketSettlement) {
        boolean neutral = entry.category == Category.AUDIT && transaction.isTransfer();
        boolean review = transaction.correction() == AUTO && (entry.needsReview
                                || needsReview(transaction));
        boolean valueKnown = !neutral && !entry.auditOnly;
        var category = entry.category;
        String[] traded = tradeParts(transaction, entry);
        return new Receipt(entry.id, transaction.id(), entry.at,
                entry.item, entry.name, semanticQuantity(entry),
                valueKnown ? entry.value : 0, entry.valueDelta, entry.unpriced(),
                neutral, review, entry.corrected, entry.split, category, verbOf(transaction),
                entry.action, why(transaction, entry.unpriced(), neutral),
                correctionLabel(transaction.correction()), Contribution.sourceOf(transaction),
                string(usedBy), chargeBasis(transaction, entry), marketSettlement,
                traded == null ? "" : traded[0] + " " + traded[1], traded == null ? "" : traded[1],
                transaction.original() == PK_DEATH_LOSS && transaction.note().equals(msg("bag-loss")));
    }

    static long semanticQuantity(Contribution entry) {
        if (entry.dose) return entry.normalizedQuantity;
        return abs(entry.quantityDelta);
    }

    /** Display verb for a transaction: action kind first, then split/type direction. */
    static String verbOf(Transaction transaction) {
        if ("Dropped".equals(transaction.note())) return "Dropped";
        if (transaction.action() != null) return actionVerb(transaction.action());
        if (Contribution.isSplit(transaction) && transaction.correction() == AUTO)
            return "Split";
        if (transaction.type() == CONSUMPTION && transaction.context() == Context.PRODUCTION)
            return msg("failed", "Failed");
        if (shopExchange(transaction)) return msg("verb-exchanged", "Exchanged");
        return msg("verb." + transaction.type(), transaction.net() < 0 ? "Used" : "Received");
    }

    static String actionVerb(ActionKind kind) {
        return msg("action." + kind, "Used");
    }

    /** Why a receipt counted, told from stored records only. */
    static String why(Transaction transaction, boolean unknown, boolean neutral) {
        if (transaction.correction() != AUTO)
            return msg("why-correction." + transaction.correction());
        if (neutral) return msg("ownership-neutral-move");
        if (unknown) return msg("price-known");
        if (!transaction.flows().isEmpty()
                && transaction.flows().stream().allMatch(flow -> flow.source() == NO_VALUE))
            return msg("no-value");
        String note = transaction.note();
        // Owner 2026-10-01 (F11): a Charges receipt names its retained confidence.
        if (note.startsWith(msg("estimated-charge")))
            return msg("estimated-local");
        if (note.startsWith(msg("measured-charge")))
            return msg("measured-exact");
        if (needsReview(transaction)) return msg("awaiting-decision");
        if (transaction.type() == CONSUMPTION && transaction.context() == Context.PRODUCTION)
            return msg("failed-inputs");
        return msg("why-type." + transaction.type(),
                transaction.counts() ? msg("automatic-count") : "Not counted.");
    }

    /**
     * The claimed shop click behind a trade's note ("buy"/"sell"), else empty. Player trades and
     * the Grand Exchange keep their own vocabulary.
     */
    static String shopClick(Transaction transaction) {
        if (transaction == null || transaction.type() != TRADE) return "";
        String note = lower(transaction.note());
        if (note.startsWith("buy")) return "buy";
        if (note.startsWith("sell")) return "sell";
        return "";
    }

    /** A shop trade paid in a currency other than coins (tokkul, spirit flakes, …). */
    static boolean nonCoinShopCurrency(Transaction transaction) {
        if (transaction == null) return false;
        var cash = pattern("cash");
        for (Flow flow : transaction.flows()) {
            if (CurrencyProxyCatalogue.isMapped(flow.item)) return true;
            String name = lower(flow.name);
            if (!"coins".equals(name) && cash.matcher(name).matches()) return true;
        }
        return false;
    }

    /** A shop trade that reads "Exchanged": a claimed shop click paid in a non-coin currency. */
    static boolean shopExchange(Transaction transaction) {
        return !shopClick(transaction).isEmpty() && nonCoinShopCurrency(transaction);
    }

    /**
     * For a shop trade entry: the other side of the trade, named and counted, from the claimed
     * click ("buy"/"sell"). A tokkul spend entry reads "Bought Death rune ×50"; the rune's own
     * entry reads "Paid Tokkul ×14,550". Returns {"Bought", "Death rune ×50"} or null.
     */
    static String[] tradeParts(Transaction transaction, Contribution self) {
        if (self == null) return null;
        String click = shopClick(transaction);
        if (click.isEmpty()) return null;
        boolean buying = "buy".equals(click);
        Map<Integer, String> names = new LinkedHashMap<>();
        Map<Integer, Long> counts = new LinkedHashMap<>();
        boolean gain = false, anyCost = false;
        for (var item : Contribution.project(transaction)) {
            if (item == null || item.id.equals(self.id)) continue;
            long signed = item.dose ? item.normalizedQuantity : item.quantityDelta;
            if (signed == 0) continue;
            gain |= signed > 0;
            anyCost |= signed < 0;
            names.putIfAbsent(item.item, item.name);
            counts.merge(item.item, Math.abs(signed), Long::sum);
        }
        if (counts.isEmpty()) return null;
        var text = new StringBuilder();
        for (var entry : counts.entrySet()) {
            if (text.length() > 0) text.append(" · ");
            text.append(names.get(entry.getKey()));
            if (entry.getValue() > 1) text.append(' ').append(times(entry.getValue()));
        }
        String label = buying ? gain && !anyCost ? msg("purchase-label", "Bought") : msg("traded-paid", "Paid")
                        : gain && !anyCost ? msg("sale-unbased", "Sold for") : msg("sale-label", "Sold");
        return new String[] {label, text.toString()};
    }

    /**
     * Charges: the exact captured unit price and its source, or "unpriced". Retained flow facts
     * only - never re-derived from the value (owner 2026-10-01, F11).
     */
    static String chargeBasis(Transaction transaction, Contribution entry) {
        String note = transaction.note();
        if (!note.startsWith(msg("estimated-charge")) && !note.startsWith(msg("measured-charge")))
            return "";
        if (entry.unpriced() || entry.flows.isEmpty()
                || entry.flows.get(0).unitPrice <= 0)
            return "unpriced";
        Flow flow = entry.flows.get(0);
        return exact(flow.unitPrice) + msg("gp") + flow.source();
    }

    static String correctionLabel(Correction decision) {
        return msg("counted-as." + decision, "Automatic");
    }

    /** One estimated charge cast: the weapon names a single cast row, like a spell. */
    static Group chargeCastUnit(Transaction transaction, List<Contribution> entries, String weapon, String source,
            long at, boolean neutral, boolean review, boolean corrected) {
        boolean unknown = false;
        long value = 0;
        for (var entry : entries) {
            unknown |= entry.unpriced();
            value = sum(value, entry.value);
        }
        String verb = verbOf(transaction), context = compositionText(transaction, entries);
        String key = "chargecast:" + weapon + ":" + coverageKey(unknown) + ":" + (neutral ? "neutral" : "counted")
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
            var variant = ChargeCheck.namedVariant(weapon);
            if (variant != null) icon = variant.itemIds[0];
        }
        var unit = composite(key, icon, tableFor(financialCategory(transaction, entries.get(0))),
                        weapon, context, verb, true, at, value, unknown, 1, neutral, review,
                        corrected, transaction.id(), entries.get(0).id);
        for (var part : entries) {
            unit.entries.add(receipt(transaction, part, null, null));
            unit.terms.add(part.name);
        }
        unit.search(weapon, context, verb, source, transaction.id());
        return unit;
    }

    static String measuredChargeWeaponName(Transaction transaction) {
        String note = transaction.note(), prefix = msg("measured-charge");
        if (!note.startsWith(prefix)) return "";
        String weapon = note.substring(prefix.length()).trim();
        return weapon.length() > 80 ? weapon.substring(0, 80).trim() : weapon;
    }

    static String compositionText(Transaction transaction, List<Contribution> entries) {
        boolean allRunes = true;
        var runeTypes = new LinkedHashSet<Integer>();
        for (var entry : entries) {
            String name = entry.name.toLowerCase(ROOT);
            if (!name.endsWith(" rune")) allRunes = false;
            else runeTypes.add(entry.item);
        }
        if (transaction.action() == CAST && allRunes)
            return runeTypes.size() + " rune types";
        return entries.size() + (transaction.action() == CAST ? " components" : " items");
    }

    /**
     * Presentation grouping signature for generic action composites: sorted resource ids with a
     * GCD-normalised quantity ratio when every quantity is known, else raw quantities (split rather
     * than over-merge), plus the price source so priced/incomplete shapes stay distinct.
     */
    static String compositionKey(List<Contribution> entries) {
        long gcd = 0;
        boolean normalisable = true;
        for (var part : entries) {
            long quantity = abs(part.quantityDelta);
            if (quantity <= 0) {
                normalisable = false;
                break;
            }
            gcd = gcd(gcd, quantity);
        }
        var sorted = new ArrayList<>(entries);
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

    static long gcd(long left, long right) {
        long a = Math.abs(left), b = Math.abs(right);
        while (b != 0) {
            long next = a % b;
            a = b;
            b = next;
        }
        return a;
    }

    /**
     * The financial destination of one contribution. A zero-value financial movement (an unpriced
     * cost or gain) is routed by its direction and positive evidence — never to Audit — so an
     * unpriced Supply stays in Costs/Supplies with an incomplete marker.
     */
    static Category financialCategory(Transaction transaction, Contribution entry) {
        var category = entry.category;
        if (category != Category.AUDIT) return category;
        if (entry.auditOnly || transaction.isTransfer())
            return Category.AUDIT;
        long quantity = entry.quantityDelta;
        if (quantity > 0) return Category.GAIN;
        if (quantity == 0) return Category.AUDIT;
        var type = transaction.original();
        if (type == TRADE) return Category.MARKET;
        if (type == PK_DEATH_LOSS || type == PK_SUPPLY_COST || type == PROCESSING
                || transaction.action() != null)
            return type == PK_DEATH_LOSS ? Category.LOSS : Category.SUPPLY;
        return Category.LOSS;
    }

    static Table tableFor(Category category) {
        switch (category) {
            case GAIN:
                return GAINS;
            case SUPPLY:
                return COSTS_SUPPLIES;
            case LOSS:
                return COSTS_LOSS;
            case MARKET:
                return MARKET;
            default:
                return Table.AUDIT;
        }
    }

    static String coverageKey(boolean unknown) {
        return unknown ? "incomplete" : "complete";
    }

    static Coverage coverage(boolean unknown) {
        return unknown ? INCOMPLETE : COMPLETE;
    }

    static String lifecycleLabel(Row market) {
        boolean sell = market.side == SELL;
        switch (market.lifecycle) {
            case PENDING:
                return "×" + market.offeredQty;
            case PARTIALLY_EXECUTED:
                return (sell ? "Part sold " : "Part bought ") + market.filledQty + "/" + market.offeredQty;
            case EXECUTED_UNSETTLED:
                return sell ? msg("sold-collect") : msg("bought-collect");
            case PARTIALLY_REALIZED:
                return msg("partially-realized");
            case CANCELLED_RETURNED:
                return "Returned";
            case AMBIGUOUS:
                return "Review";
            default:
                return msg("detail-unavailable");
        }
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

    String id;
    long at;
    int flowOrdinal, item;
    String name;
    long quantityDelta;

    /** Raw automatic Flow value, retained for audit and correction comparison. */
    long valueDelta;

    /** Effective, correction-aware additive value used by financial presentation. */
    long value;

    PriceSource source;
    boolean counted;
    Category category;

    /** Transient exact action label captured from trusted client evidence; display/search only. */
    String action;

    boolean needsReview, corrected, split, auditOnly, dose;
    long normalizedQuantity;
    List<Flow> flows;

    Contribution(Transaction transaction, int flowOrdinal, int id, String name, long delta,
            long value, long effectiveValue, PriceSource source, Category category, boolean needsReview,
            boolean auditOnly, boolean normalizedConsume, long normalizedQuantity, List<Flow> rawFlows) {
        this.id = transaction.id() + (normalizedConsume ? ":consume:" + flowOrdinal : ":flow:" + flowOrdinal);
        at = transaction.timestamp;
        this.flowOrdinal = flowOrdinal;
        item = id;
        this.name = fallback(name, "Unknown item");
        quantityDelta = delta;
        valueDelta = value;
        this.value = effectiveValue;
        this.source = source == null ? UNKNOWN : source;
        counted = transaction.counts();
        this.category = category == null ? Category.AUDIT : category;
        action = transaction.spellName();
        this.needsReview = needsReview;
        corrected = transaction.correction() != AUTO;
        split = isSplit(transaction);
        this.auditOnly = auditOnly;
        dose = normalizedConsume;
        this.normalizedQuantity = normalizedQuantity;
        flows = frozenList(rawFlows);
    }

    static Contribution from(Transaction transaction, Flow flow, int ordinal) {
        if (transaction == null || flow == null) return null;
        var money = Accounting.flow(transaction, flow);
        long effective = money.included ? money.net() : 0;
        var category = category(transaction, flow, money);
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
        var flows = transaction.flows();
        if (empty(flows)) return emptyList();
        var result = new ArrayList<Contribution>();
        var consumed = new HashSet<Integer>();
        for (var pair : consumePairs(transaction)) {
            Flow loss = flows.get(pair.lossIndex), rest = flows.get(pair.remainder);
            var lossAmounts = Accounting.flow(transaction, loss);
            var remainderAmounts = Accounting.flow(transaction, rest);
            long effective = sum(lossAmounts.net(), remainderAmounts.net());
            if (!lossAmounts.included || !remainderAmounts.included || effective > 0) continue;
            int ordinal = min(pair.lossIndex, pair.remainder);
            result.add(new Contribution(transaction, ordinal, loss.item, normalizedName(loss.name), -min(
                                    abs(loss.quantity), abs(rest.quantity)),
                            sum(loss.value, rest.value), effective, loss.source(),
                            Category.SUPPLY, reviewState(transaction, loss), false, true, min(
                                    abs(loss.quantity), abs(rest.quantity)),
                            List.of(loss, rest)));
            consumed.add(pair.lossIndex);
            consumed.add(pair.remainder);
        }
        for (int i = 0; i < flows.size(); i++) {
            if (consumed.contains(i)) continue;
            Flow flow = flows.get(i);
            var money = Accounting.flow(transaction, flow);
            // The last dose drunk, its vial smashed, is one more dose of the same potion.
            if (transaction.action() == DRINK && transaction.correction() == AUTO && money.included
                    && (transaction.type() == CONSUMPTION
                            || transaction.type() == PK_SUPPLY_COST) && flow.quantity < 0
                    && ActionReader.dose(flow.name) == 1) {
                long doses = abs(flow.quantity);
                result.add(new Contribution(transaction, i, flow.item, normalizedName(flow.name), -doses,
                                flow.value, money.net(), flow.source(), Category.SUPPLY,
                                reviewState(transaction, flow), false, true, doses, List.of(flow)));
                continue;
            }
            var entry = from(transaction, flow, i);
            if (entry != null) result.add(entry);
        }
        result.sort(comparingInt((Contribution item) -> item.flowOrdinal)
                        .thenComparing(itemData -> itemData.id));
        return unmodifiableList(result);
    }

    static String normalizedName(String name) {
        if (name == null) return "Unknown item";
        String doseBase = ActionReader.doseBase(name);
        if (!doseBase.equals(name.trim())) return doseBase;
        String lower = lower(name);
        if (has(lower, "pizza", "cake", "pie")) {
            for (String prefix : new String[] {"whole ", "full ", "half ", "1/2 "})
                if (lower.startsWith(prefix)) return name.trim().substring(prefix.length()).trim();
        }
        return name.trim();
    }

    static Category category(Transaction transaction, Flow flow, Amounts money) {
        if (!money.available || money.transfer || !money.included) return Category.AUDIT;
        if (money.income > 0) return Category.GAIN;
        if (money.cost <= 0) return Category.AUDIT;
        switch (CostKind.of(transaction, flow)) {
            case SUPPLIES:
                return Category.SUPPLY;
            case MARKET:
                return Category.MARKET;
            case LOSS:
                return Category.LOSS;
            default:
                return Category.LOSS;
        }
    }

    /** A shared-loot split share, told by the split marker on the reason or explanation. */
    static boolean isSplit(Transaction transaction) {
        return ItemSplits.splitReason(transaction.correctionReason())
                || ItemSplits.splitReason(transaction.explanation());
    }

    /** The receipt's activity as a display source; the default "General" reads as no source. */
    static String sourceOf(Transaction transaction) {
        String activity = transaction.activityName();
        return empty(activity) || "General".equalsIgnoreCase(activity) ? "" : activity;
    }

    /** Category of a flow-less note receipt: its own included Net decides the direction. */
    static Category noteCategory(Transaction transaction, Amounts money, boolean neutral) {
        if (neutral || !money.included) return Category.AUDIT;
        if (money.net() > 0) return Category.GAIN;
        return transaction.original() == TRADE ? Category.MARKET : Category.LOSS;
    }

    static boolean reviewState(Transaction transaction, Flow flow) {
        boolean unknown = flow.unitPrice <= 0
                        && (flow.source() == UNKNOWN || flow.source() == UNPRICED);
        boolean uncertain = transaction.correction() == AUTO && transaction.confidence() == Confidence.UNCERTAIN;
        String blob = (string(transaction.explanation()) + " " + string(transaction.correctionReason()))
                        .toLowerCase(ROOT);
        return uncertain || unknown || blob.contains("calibrat") || blob.contains("uncertain")
                        && has(blob, "plank", "sack", "pouch", "bag", "container")
                || blob.contains("charge") && blob.contains("warn");
    }

    boolean unpriced() {
        return itemUnitPriceUnavailable() && (source == UNKNOWN || source == UNPRICED);
    }

    boolean itemUnitPriceUnavailable() {
        for (Flow flow : flows) {
            if (flow != null && flow.unitPrice > 0) return false;
        }
        return true;
    }
}

/**
 * One owned, unfiltered financial snapshot. Session save stamps cover close/history changes that do
 * not advance Engine.revision. Clock, pause and activity are overlays, never cached money. A
 * changed Market presentation reuses the same financial facts rather than refolding receipts.
 */
class MoneySnapshot {
    final List<Object> key;
    final List<Row> market;
    final Map<Session, SessionFacts> facts;
    final Map<String, Rollup> rollups;

    /** Derived scopes memoize only from the captured facts; they cannot read mutable receipts. */
    final Map<List<Session>, SessionScope> scopes = new HashMap<>();

    static MoneySnapshot of(Engine engine) {
        var sessions = engine.sessions();
        var parts = new ArrayList<Object>();
        parts.add(engine.revision());
        parts.add(engine.session());
        parts.add(engine.history());
        for (var session : sessions) {
            parts.add(session);
            parts.add(session.saveStamp);
            parts.add(session.grindId());
        }
        for (var plan : engine.plans)
            parts.add(plan.id());
        var rows = engine.marketRows();
        var cached = engine.snapshot;
        if (cached != null && cached.key.equals(parts) && cached.market.equals(rows))
            return cached;
        return engine.snapshot = new MoneySnapshot(engine, sessions, parts, rows, cached);
    }

    MoneySnapshot(Engine engine, List<Session> sessions, List<Object> parts, List<Row> rows,
            MoneySnapshot previous) {
        key = unmodifiableList(parts);
        market = frozenList(rows);
        if (previous != null && previous.key.equals(key)) {
            facts = previous.facts;
            rollups = previous.rollups;
            return;
        }
        var built = new IdentityHashMap<Session, SessionFacts>();
        for (var session : sessions)
            if (session != null && !built.containsKey(session)) {
                var reused = previous == null ? null : previous.facts.get(session);
                built.put(session, session.isClosed() && reused != null && reused.stamp == session.saveStamp ? reused
                                : new SessionFacts(session));
            }
        facts = unmodifiableMap(built);
        var byGrind = new HashMap<String, Rollup>();
        for (var plan : engine.plans)
            byGrind.put(plan.id(), new Rollup(engine.completedLinked(plan.id())));
        rollups = unmodifiableMap(byGrind);
    }

    /** A saved view is scoped separately and never enters the engine's financial snapshot. */
    MoneySnapshot(SavedState.SavedSession saved) {
        key = emptyList();
        market = saved.market;
        facts = singletonMap(saved.session, new SessionFacts(saved.session));
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
        var scopeKey = frozenList(sessions);
        return scopes.computeIfAbsent(scopeKey, SessionScope::new);
    }

    /**
     * Visibility derives another presentation from frozen receipts; hero folds remain unfiltered.
     */
    Result view(SessionScope scope, Predicate<Flow> visible) {
        return visible == null ? scope.groups : Semantics.capture(scope.transactions, market, scope.ids, visible);
    }

    /** Canonical per-session money and independently ordered Ledger deltas. */
    static class SessionFacts {
        /** Closed facts reuse the existing save stamp; a correction invalidates them. */
        final long stamp;

        final Metrics money;
        final List<Transaction> transactions;
        final List<Long> deltas;
        final boolean cut;
        final int review;
        final MarketFold market;

        Long windowStart;
        List<Transaction> windowRows;
        Metrics windowMoney;
        MarketFold windowMarket;

        /** Bounded to this financial revision and the most recently requested Live window. */
        SessionFacts window(Session session) {
            if (windowRows == null || !Objects.equals(windowStart, session.sessionStartAt)) {
                windowStart = session.sessionStartAt;
                windowRows = unmodifiableList(FlowFilters.matching(transactions,
                        row -> row.timestamp >= windowStart));
                windowMoney = session.metrics(windowRows, 0);
                windowMarket = marketFold(windowRows);
            }
            return this;
        }

        SessionFacts(Session session) {
            stamp = session.saveStamp;
            money = session.metrics(0);
            var compacted = session.compactedContribution();
            cut = compacted.rows > 0 || compacted.income != 0 || compacted.cost != 0;
            var copies = new ArrayList<Transaction>();
            var deltaList = new ArrayList<Long>();
            deltaList.add(compacted.net());
            int reviews = 0;
            for (var transaction : session.transactions()) {
                if (transaction == null) continue;
                var copied = copy(transaction);
                copies.add(copied);
                var amounts = transaction(copied);
                if (amounts.included) deltaList.add(amounts.net());
                if (needsReview(copied)) reviews++;
            }
            transactions = unmodifiableList(copies);
            deltas = unmodifiableList(deltaList);
            review = reviews;
            market = marketFold(transactions);
        }

        Metrics metrics(Session session, long now) {
            long elapsed = session.elapsed(now);
            return new Metrics(session.activityHint(), session.paused, elapsed, money.income, money.cost,
                    money.net, Session.hourly(money.net, elapsed), money.supplies, money.other,
                    money.split);
        }
    }

    /** The last/best rollup is over completed lineage members, in the original order. */
    class Rollup {
        final int count;
        final boolean has;
        final long last, at, best;

        /** Every finished run's Net and Active Time, for the Grind's average GP/h. */
        long total, millis;

        Rollup(List<Session> sessions) {
            Session latest = null;
            long bestNet = Long.MIN_VALUE, lastNet = 0;
            for (var session : sessions) {
                long net = fact(session).money.net;
                total += net;
                millis += session.elapsed(0);
                if (latest == null || session.startedAt > latest.startedAt) {
                    latest = session;
                    lastNet = net;
                }
                bestNet = max(bestNet, net);
            }
            count = sessions.size();
            has = latest != null;
            last = lastNet;
            at = has ? latest.startedAt : 0;
            best = bestNet == Long.MIN_VALUE ? 0 : bestNet;
        }

    }

    /** Scope construction preserves saturation order, compaction, Market ownership and groups. */
    class SessionScope {
        final Set<String> ids;
        final List<Transaction> transactions;
        final Map<String, Row> markets;
        final long net;
        final boolean cut;
        final Result groups;

        SessionScope(List<Session> sessions) {
            var idSet = new LinkedHashSet<String>();
            var all = new ArrayList<Transaction>();
            long total = 0;
            boolean anyCut = false;
            for (var session : sessions) {
                if (session == null) continue;
                var sessionFacts = fact(session);
                idSet.add(session.id());
                all.addAll(sessionFacts.transactions);
                anyCut |= sessionFacts.cut;
                for (long delta : sessionFacts.deltas) total = sum(total, delta);
            }
            ids = unmodifiableSet(idSet);
            transactions = unmodifiableList(all);
            net = total;
            cut = anyCut;
            var byPresentation = new HashMap<String, Row>();
            for (Row row : market)
                if (row != null && ids.contains(row.scopeSessionId))
                    byPresentation.put(row.presentationId, row);
            markets = unmodifiableMap(byPresentation);
            groups = Semantics.capture(transactions, market, ids, null);
        }
    }

    /** Own the receipt and its flows: later corrections cannot mutate this financial revision. */
    static Transaction copy(Transaction source) {
        var copy = SaveCopies.shallow(source);
        copy.memo = null;
        copy.onGround = null;
        var flows = new ArrayList<Flow>();
        for (Flow flow : source.flows())
            flows.add(flow.raid(new Flow(flow.item, flow.name, flow.quantity, flow.unitPrice, flow.value,
                            flow.priceSource, flow.priceCapturedAtEpochMillis)));
        copy.flows = unmodifiableList(flows);
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
            var matches = new ArrayList<Transaction>();
            for (String id : (original.settlementId + original.earlierSettlements).split(" ")) {
                var receipt = lookup.get(id);
                if (receipt != null) matches.add(receipt);
            }
            if (matches.isEmpty()) continue;
            var row = SaveCopies.shallow(original);
            row.savedNet = session.metrics(matches, 0).net;
            row.savedQuantity = 0;
            for (var receipt : matches) for (Flow flow : receipt.flows())
                if (flow.item == row.itemId) row.savedQuantity = sum(row.savedQuantity, abs(flow.quantity));
            row.settlementId = matches.get(0).id();
            row.earlierSettlements = "";
            for (int i = 1; i < matches.size(); i++) row.earlierSettlements += " " + matches.get(i).id();
            rows.add(row);
        }
        return rows;
    }

    /** Behavioural lifecycle state; several durable stages may map to one state. */
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
        String presentationId;
        long timestampEpochMillis;
        int itemId;
        String itemName;
        GeOffer.Side side;
        long offeredQty, filledQty;

        long settledQty, observedSettlementGp, settlementAdjustmentGp;

        /**
         * Observed gross-to-cash gap matching the per-item GE rule. It is the shared accepted-tax
         * predicate: when the booked settlement is a counted unknown-basis sale, this exact tax is
         * the booked Net contribution; otherwise it stays presentation evidence only.
         */
        long inferredGeTaxGp;

        /** Actual after-tax Received per unit when exactly divisible; -1 when not provable. */
        long receivedEachGp;

        /** Settlement/collection observation time; 0 when no exact settlement receipt resolves. */
        long settlementAtEpochMillis;

        long realizedResultGp;
        /** Optional saved-window contribution; offer context remains frozen at its lifetime values. */
        Long savedNet;
        long savedQuantity;
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

        Coverage coverage;
        Lifecycle lifecycle;
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
            presentationId = record.offerId;
            String originId = record.originSessionId;
            Session holder = sessions == null || record.settlementId.isEmpty() ? null
                            : sessions.sessionHolding(record.settlementId);
            scopeSessionId = holder == null ? originId : holder.id();
            Session origin = holder == null || originId.isEmpty() || originId.equals(holder.id())
                            ? null : sessions.sessionById(originId);
            listedDuring = origin == null ? "" : origin.name();
            timestampEpochMillis = record.placedAtEpochMillis;
            itemId = record.itemId;
            itemName = record.itemName;
            side = record.side;
            offeredQty = record.offeredQty;
            filledQty = record.filledQty;
            boolean sell = record.side == SELL;
            long settled = record.settledQty;
            settledQty = settled;
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
                for (String id : (record.settlementId + string(record.earlierSettlements)).split(" ")) {
                    var part = id.isEmpty() ? null : lookup.apply(id);
                    // An earlier receipt compacted away: fall back to the booked accumulator.
                    missing |= part == null && !id.isEmpty();
                    if (part == null) continue;
                    realizedResultGp += transaction(part).net();
                    manualFinancialResult |= part.correction() == REVENUE || part.correction() == COST;
                }
                if (missing) realizedResultGp = record.realizedResultGp;
                realizedResultCorrectionAware = !missing;
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
            lifecycle = lifecycleOf(record);
            collectionAmbiguous = record.collectionAmbiguous;
            settlementId = record.settlementId;
            earlierSettlements = string(record.earlierSettlements);
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
        if (empty(records)) return emptyList();
        var rows = new ArrayList<Row>(records.size());
        for (var record : records) {
            if (record != null && !record.offerId.isEmpty())
                rows.add(new Row(record, settlementLookup, sessions));
        }
        return unmodifiableList(rows);
    }

    /** Behavioural lifecycle for one durable record. Pending edge is never realized here. */
    static Lifecycle lifecycleOf(GeOffer record) {
        if (record == null) return UNAVAILABLE;
        if (record.unbased()) return UNAVAILABLE;
        if (record.confidence == GeOffer.CustodyConfidence.AMBIGUOUS || record.quantityModified
                || record.collectionAmbiguous)
            return AMBIGUOUS;
        if (record.filledQty <= 0) {
            if (record.isCancelled() || record.cleared) return CANCELLED_RETURNED;
            return PENDING;
        }
        if (record.settledQty > 0 && record.settledQty < record.filledQty)
            return PARTIALLY_REALIZED;
        if (record.settledQty >= record.filledQty && record.settledQty > 0)
            return REALIZED;
        if (record.filledQty >= record.offeredQty) return EXECUTED_UNSETTLED;
        return PARTIALLY_EXECUTED;
    }
}

/**
 * The Ledger's Market wording: one source for rows, receipts and the short GE facts, so the page
 * and the read-model tests never drift apart.
 */
class MarketText {
    /** Observed cash movement only; preserves its original signed-long arithmetic. */
    static long settlementCash(Row market, boolean sell) {
        long amount = Math.abs(market.observedSettlementGp);
        return sell ? amount : -amount;
    }

    /** True when a Market group has proven settled quantity and an observed settlement amount. */
    static boolean settled(Group group, Row market) {
        return group.market && market != null && market.realized() && market.settledQty > 0
                && market.observedSettlementGp != 0;
    }

    /** The Market row's primary signed value: observed settlement, else the canonical result. */
    static long marketPrimary(LedgerData data, Group group) {
        Row market = data.marketRow(group.marketId);
        if (data.entry.table != null || !settled(group, market)) return group.value;
        return settlementCash(market, "SELL".equals(market.side.name()));
    }

    /** The Market row's secondary line: explicit side + Result, or the lifecycle context. */
    static String marketLead(LedgerData data, Group group) {
        if (!group.market) return groupLead(group);
        Row market = data.marketRow(group.marketId);
        if (settled(group, market))
            return ("SELL".equals(market.side.name()) ? "Sold" : "Bought") + " · Result "
                    + (group.coverage == COMPLETE ? signed(group.value) : "—");
        if (group.coverage != COMPLETE) return group.context;
        return "Result " + signed(group.value);
    }

    static boolean realized(Receipt receipt) {
        Row market = receipt.market;
        return market != null && market.realized() && market.settledQty > 0;
    }

    /** Signed observed settlement cash for the collected quantity (SELL positive, BUY negative). */
    static long receiptPrimary(Receipt receipt) {
        Row market = receipt.market;
        if (!realized(receipt)) return 0;
        return settlementCash(market, market.side == SELL);
    }

    static String receiptTitle(Receipt receipt) {
        Row market = receipt.market;
        if (realized(receipt) && market.settledQty > 1)
            return receipt.name + "  " + times(market.settledQty);
        return receipt.name;
    }

    /**
     * Sold/Bought with the canonical Result as secondary information and a settlement-relative age.
     */
    static String receiptSub(Receipt receipt, long captured) {
        Row market = receipt.market;
        String sub;
        if (realized(receipt)) {
            boolean resultAvailable = market.realizedResultCorrectionAware && (market.side != SELL
                                    || market.coverage != MarketRows.Coverage.FULLY_UNKNOWN
                                    || market.manualFinancialResult);
            String result = resultAvailable ? "Result " + exactSigned(market.realizedResultGp) + " gp"
                            : "Result —";
            sub = (market.side == SELL ? "Sold" : "Bought") + " · " + result;
        } else
            sub = market == null ? receipt.verb : orderStateText(market);
        return sub + " · " + age(positive(captured - receipt.at));
    }

    /**
     * One word for where an offer stands: Offering / Buying while it is up with nothing filled,
     * Pending while part-filled, Sold / Bought once the exchange completes it, Canceled when
     * returned.
     */
    static String stateWord(Row market) {
        if (market == null) return "Pending";
        boolean sell = market.side == SELL;
        switch (market.lifecycle) {
            case CANCELLED_RETURNED:
                return "Canceled";
            case AMBIGUOUS:
                return "Review";
            case EXECUTED_UNSETTLED:
            case REALIZED:
                return sell ? "Sold" : "Bought";
            case PENDING:
                return sell ? "Selling" : "Buying";
            default:
                return "Pending";
        }
    }

    /**
     * The exact human Market lines ("label|value") for one lifecycle row; the renderer and the
     * read-model tests consume this one source. Coverage-aware: a sale whose prior tracked value is
     * unknown shows liquidation cash with an unavailable Result, never a fake zero basis. Only a
     * booked, uncorrected PROVEN tax earns a main GE tax row; exempt, unproven and pre-change sales
     * and fully known results never show one.
     */
    static List<String> humanLines(Row market, boolean corrected) {
        var lines = new ArrayList<String>();
        if (market.savedNet != null) {
            lines.add("Saved session|" + exactSigned(market.savedNet) + " gp");
            lines.add("Offer context|At save time");
        }
        String fillState = fillStateText(market);
        if (!fillState.isEmpty()) lines.add(fillState);
        if (!market.listedDuring.isEmpty())
            // Cross-Grind: booked where it was collected; the listing session is named, never
            // rewritten.
            lines.add("Listed during|" + market.listedDuring);
        boolean sell = market.side == SELL;
        if (!market.realized() || market.settledQty <= 0) {
            if (sell && market.collectionAmbiguous && market.settledQty > 0)
                // D1: a quarantined settlement's cash was never observed; Received is never shown
                // as if the player had actually collected it.
                lines.add(msg("received-proven"));
            return lines;
        }
        lines.add((sell ? "Received" : "Spent") + "|" + exact(Math.abs(market.observedSettlementGp)) + " gp");
        boolean unknown = sell && market.coverage == MarketRows.Coverage.FULLY_UNKNOWN;
        if (sell) {
            if (market.receivedEachGp > 0)
                // The actual after-tax settled unit value; shown only when exactly divisible.
                lines.add("Received each|" + exact(market.receivedEachGp) + " gp");
            lines.add(msg("previously-counted") + (unknown ? "Unknown" : exact(market.trackedBasisConsumedGp) + " gp"));
            lines.add("Result|" + (unknown && !market.manualFinancialResult ? "—"
                                    : market.realizedResultCorrectionAware ? exactSigned(market.realizedResultGp)
                                                    + " gp" + (market.coverage == MarketRows.Coverage.PARTIALLY_KNOWN
                                                            ? " · Partial" : "")
                                                    + (corrected ? msg("corrected") : "") : "—"));
            if (market.knownCostOnly)
                // Only the BOOKED state with an AUTO correction earns this row: the tax is a real
                // market cost here. Uncounted/pre-change, corrected and fully known sales never
                // show it (a full Result already has the tax inside Received).
                lines.add("GE tax|" + exactSigned(-market.inferredGeTaxGp) + " gp");
        } else {
            lines.add("Tracked value|" + exact(market.trackedBasisConsumedGp) + " gp");
            lines.add("Result|" + (market.realizedResultCorrectionAware ? exactSigned(market.realizedResultGp) + " gp"
                                            + (corrected ? msg("corrected") : "") : "0 gp"));
        }
        if (market.settlementAdjustmentGp != 0 && market.inferredGeTaxGp <= 0)
            // An unexplained gap is the receipt's own answer and must stay visible.
            lines.add("Adjustment|" + exactSigned(market.settlementAdjustmentGp) + " gp");
        return lines;
    }

    /** Compact fill/collected state; omitted when offered, filled and collected agree. */
    static String fillStateText(Row market) {
        long offered = market.offeredQty, filled = market.filledQty, collected = market.settledQty;
        if (offered <= 0 || filled == collected && collected == offered) return "";
        if (filled == collected) return "Filled " + exact(collected) + " / " + exact(offered);
        return "Filled " + exact(filled) + " / " + exact(offered) + " · collected " + exact(collected);
    }

    /** Order-state wording that never mislabels placement time as settlement time. */
    static String orderStateText(Row market) {
        boolean sell = market.side == SELL;
        String side = sell ? "Sell order" : "Buy order";
        switch (market.lifecycle) {
            case CANCELLED_RETURNED:
                return msg("returned-canceled");
            case AMBIGUOUS:
                return "Needs review";
            case UNAVAILABLE:
                return "Unavailable";
            case EXECUTED_UNSETTLED:
                return (sell ? "Sold " : "Bought ") + exact(market.filledQty) + "/" + exact(market.offeredQty)
                        + msg("awaiting-collection");
            case PARTIALLY_EXECUTED:
                return (sell ? "Part sold " : "Part bought ") + exact(market.filledQty) + "/" + exact(market.offeredQty)
                        + " · Pending";
            default:
                return stateWord(market);
        }
    }

    static String orderWhy(Row market) {
        switch (market.lifecycle) {
            case CANCELLED_RETURNED:
                return msg("canceled-returned");
            case AMBIGUOUS:
                return msg("lifecycle-review");
            case EXECUTED_UNSETTLED:
                return msg("filled-quantity");
            default:
                return msg("collected-settlement");
        }
    }

    static String groupLead(Group group) {
        if (group.market) return group.context;
        if (!group.uses.isEmpty()) return "Used by " + group.uses;
        return group.label;
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
    @RequiredArgsConstructor
    enum Scope {
        CURRENT_GRIND("Current Grind"), TODAY("Runs today"), HISTORY("Recent Grind");
        final String label;

        public String toString() {
            return label;
        }
    }

    /**
     * The Losses table's chips (owner 2026-09-28): LOSS is shown as Items; Charges are measured
     * charge use.
     */
    @RequiredArgsConstructor
    enum CostView {
        ALL("All"), SUPPLIES("Supplies"), LOSS("Items"), CHARGES("Charges");
        final String label;

        public String toString() {
            return label;
        }
    }

    /** A not-yet-settled count in Pending: an unopened loot key or chest, or a death reclaim. */
    @AllArgsConstructor
    static class PendingItem {
        int item;
        String name;
        long quantity;
        String tip;

        /** An observed open chest's exact worth; 0 when unseen, looted or unpriced. */
        long value;
    }

    /** Compact audit subsections; never peer financial tabs. */
    @RequiredArgsConstructor
    enum Audit {
        REVIEW("Review"), CORRECTED("Corrected");
        final String label;

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
        Scope scope;
        public String session, historyName;
        CostView view;
        String search;
        public String transaction, highlightContributionId, group;

        /** Deep-link request to expand one audit subsection. */
        public Audit focusAudit;
        Table table;

        Entry live(Table table) {
            this.table = table;
            return this;
        }

        Entry(Scope scope, String id, String name) {
            this(scope, id, name, CostView.ALL, "", null, null, null, null);
        }

        Entry(Scope scope, String id, String name, CostView costView, String search,
                String receiptId, String highlightContributionId, String highlightGroupId,
                Audit focusAudit) {
            this.scope = scope == null ? CURRENT_GRIND : scope;
            session = id;
            historyName = name;
            view = costView == null ? CostView.ALL : costView;
            this.search = string(search);
            transaction = receiptId;
            this.highlightContributionId = highlightContributionId;
            group = highlightGroupId;
            this.focusAudit = focusAudit;
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
        List<Group> groups;
        long total;
        int entries, receipts;
    }

    /** Review audit: exact review rows with the full eligible bulk count. */
    @AllArgsConstructor
    static class ReviewData {
        List<ReviewRow> rows;
        int total;
    }

    /** The open detail: one semantic group, its exact receipts, and any selected exact receipt. */
    @AllArgsConstructor
    static class Detail {
        Group group;
        List<Receipt> entries;
        public Receipt exact;
    }

    /** One resource inside a receipt: "Chaos rune ×4" on its line. */
    @AllArgsConstructor
    static class ResourceSummary {
        int item;
        String name, uses;
        long quantity, value;

        /** Sum of only the known children; shown with explicit wording when incomplete. */
        long knownSubtotal;
        boolean incomplete;
    }

    /** One receipt row: exactly one canonical transaction inside the selected group. */
    @AllArgsConstructor
    static class Card {
        String transaction;
        long at, value, knownSubtotal;
        boolean incomplete;

        /** Representative contribution id for selecting the receipt. */
        String id;

        List<ResourceSummary> resources;

        /** "Chaos rune ×4 · Death rune ×2" for the row's tooltip. */
        String summaryText() {
            var text = new StringBuilder();
            for (var resource : resources) {
                if (text.length() > 0) text.append(" · ");
                text.append(resource.name);
                if (resource.quantity > 1) text.append(' ').append(times(resource.quantity));
            }
            return text.toString();
        }
    }

    /** Additive facts shared by a receipt card and each resource inside it. */
    @RequiredArgsConstructor
    static class ReceiptTotal {
        final Receipt head;
        long quantity, value, known, at;
        boolean incomplete;
        String representative = "";

        void add(Receipt receipt) {
            quantity += receipt.quantity;
            value += receipt.value;
            at = max(at, receipt.at);
            incomplete |= receipt.unknown;
            if (!receipt.unknown) known += receipt.value;
            if (representative.isEmpty()) representative = receipt.id;
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

        Card card() {
            var summaries = new ArrayList<ResourceSummary>();
            for (var resource : resources.values()) {
                var receipt = resource.head;
                summaries.add(new ResourceSummary(receipt.item, receipt.name, receipt.uses, resource.quantity,
                                resource.value, resource.known, resource.incomplete));
            }
            summaries.sort(comparing((ResourceSummary summary) -> summary.name,
                                    String.CASE_INSENSITIVE_ORDER).thenComparingInt(summary -> summary.item));
            return new Card(head.transaction, at, value, known, incomplete, representative, summaries);
        }
    }

    /** One pass builds each transaction card and its resources; newest cards first. */
    static List<Card> cards(Group group) {
        if (group == null) return emptyList();
        var byTransaction = new LinkedHashMap<String, ReceiptCard>();
        for (var receipt : group.entries)
            byTransaction.computeIfAbsent(receipt.transaction, ignored -> new ReceiptCard(receipt)).add(receipt);
        var cards = new ArrayList<Card>(byTransaction.size());
        for (var card : byTransaction.values()) cards.add(card.card());
        cards.sort(comparingLong((Card card) -> card.at).reversed()
                        .thenComparing(card -> card.transaction));
        return cards;
    }

    /** The query this data answers: scope, Costs view, search and the selected receipt. */
    Entry entry;

    String scopeName;
    boolean readonly, compacted, detailed;
    long net;

    /** Capture time for honest ages without leaking the engine. */
    long at;

    TableData income, cost, market;
    ReviewData review;
    List<Receipt> corrected;
    Summary total;
    boolean balanced;
    public Detail detail;

    /**
     * Raw Market settlement rows by presentation id; presentation-only lookup for value semantics.
     */
    Map<String, Row> marketRows;

    /** Group counts for the Losses chips by {@link CostView} ordinal, whatever view is selected. */
    int[] costCounts;

    /** Keys and reclaim waiting to settle; current scope only, with an exact observed chest value. */
    List<PendingItem> pendingItems;

    static LedgerData capture(Engine engine, long now, Entry entry) {
        Entry e = entry == null ? Entry.current() : entry;
        var sessions = sessionsFor(engine, e, now);
        String scopeName = scopeName(engine, e);
        var saved = e.scope == HISTORY ? engine.savedSession(e.session) : null;
        var summary = saved == null ? engine.summary() : saved.view();
        var scope = summary.scope(sessions);
        var ids = scope.ids;
        var transactions = scope.transactions;
        boolean compacted = scope.cut;
        long scopeNet = scope.net;
        var rows = summary.market;
        var markets = scope.markets;
        var result = scope.groups;
        if (e.scope == CURRENT_GRIND && e.table != null && e.table != KILL && e.table != DEATH && engine.session() != null) {
            var active = engine.session();
            scopeName = "Live session";
            if (active.sessionStartAt != null) {
                transactions = active.rowsSince(active.sessionStartAt);
                rows = MarketRows.window(active, rows, transactions);
                markets = new HashMap<>();
                for (var row : rows) markets.put(row.presentationId, row);
                result = Semantics.capture(transactions, rows, ids, null);
                scopeNet = active.metrics(transactions, 0).net;
                compacted = false;
            }
        }
        if (e.table == KILL) result = kills(transactions);
        var tables = new EnumMap<Table, List<Group>>(Table.class);
        for (var table : Table.values()) tables.put(table, new ArrayList<>());
        for (Group group : result.groups) {
            if (!group.review && group.matchesText(e.search)) {
                tables.get(group.death && group.neutral ? COSTS_LOSS : group.table).add(group);
                if (group.pvpDeath) tables.get(DEATH).add(group);
            }
        }
        var gainsAll = tables.get(GAINS);
        var suppliesAll = tables.get(COSTS_SUPPLIES);
        var lossAll = tables.get(COSTS_LOSS);
        var marketAll = tables.get(MARKET);
        var supplies = new ArrayList<Group>();
        var charges = new ArrayList<Group>();
        for (Group group : suppliesAll) (group.charge ? charges : supplies).add(group);
        var costs = new ArrayList<Group>(e.table == DEATH ? tables.get(DEATH)
                                : e.table == COSTS_SUPPLIES ? suppliesAll
                                : e.table == COSTS_LOSS || e.view == CostView.LOSS ? lossAll
                                : e.view == CostView.CHARGES ? charges : supplies);
        if (e.table == null && e.view == CostView.ALL) {
            // All is every list together, newest first like every other table (owner 1.3.3).
            costs.addAll(charges);
            costs.addAll(lossAll);
            costs.sort(comparingLong((Group group) -> group.at).reversed()
                            .thenComparing(group -> group.id));
        }
        var income = tableData(e.table == KILL ? tables.get(KILL) : gainsAll, false);
        var cost = tableData(costs, false);
        var market = tableData(marketAll, true);
        var financial = new ArrayList<Group>();
        financial.addAll(gainsAll);
        financial.addAll(suppliesAll);
        financial.addAll(lossAll);
        financial.addAll(marketAll);
        var total = Semantics.summarize(financial);
        // ── audit ────────────────────────────────────────────────────────────────────────────
        var reviewRows = new ArrayList<ReviewRow>();
        int reviewScopeCount = 0;
        for (var reviewRow : engine.getReviewRows(now)) {
            if (reviewRow != null && ids.contains(reviewRow.session)) {
                reviewScopeCount++;
                if (!readOnly(e) && matchesSearch(reviewRow, e.search)) reviewRows.add(reviewRow);
            }
        }
        var review = new ReviewData(reviewRows, saved == null ? reviewScopeCount : summary.fact(saved.session).review);
        var fixed = new ArrayList<Receipt>();
        for (Group group : result.groups) {
            for (var receipt : group.entries) {
                if ((receipt.corrected || receipt.split) && receipt.matchesSearch(e.search))
                    fixed.add(receipt);
            }
        }
        fixed.sort(comparingLong((Receipt receipt) -> receipt.at).reversed()
                        .thenComparing(receipt -> receipt.id));
        // ── selected detail ──────────────────────────────────────────────────────────────────
        Detail detail = null;
        String anchorContribution = e.highlightContributionId, anchorTransaction = e.transaction;
        String anchorGroup = e.group;
        if (anchorGroup != null || anchorContribution != null || anchorTransaction != null) {
            Group group = findGroup(result.groups, anchorGroup, anchorContribution, anchorTransaction);
            if (group != null) {
                var records = group.entries;
                Receipt exact = null;
                if (anchorContribution != null) {
                    for (var receipt : records) {
                        if (receipt.id.equals(anchorContribution)) {
                            exact = receipt;
                            break;
                        }
                    }
                }
                if (exact == null && anchorTransaction != null)
                    exact = firstReceipt(records, anchorTransaction);
                // A one-entry group opens on its entry: a list of one only repeated the header.
                if (exact == null && !group.market && oneEntry(records)) exact = records.get(0);
                detail = new Detail(group, records, exact);
            }
        }
        long net = scopeNet;
        boolean detailedHistoryAvailable = !compacted || total.entries > 0;
        boolean reconciled = total.coverageComplete && sum(sum(total.income, total.cost), total.market) == net;
        return new LedgerData(e, scopeName, readOnly(e), compacted, detailedHistoryAvailable, net, now, income, cost,
                market, review, fixed, total, reconciled, detail, markets,
                new int[] {0, supplies.size(), lossAll.size(), charges.size()}, pendingItems(engine, e, now));
    }

    /** One retained anonymous kill, with its pickups and source-linked key proceeds. No booking. */
    static Result kills(List<Transaction> transactions) {
        var seeds = new HashMap<String, String>();
        for (var row : transactions)
            if (row.original() == PK_LOOT || row.note().equals(KeyClaims.LOOT_KEY_NOTE))
                seeds.put(row.id(), row.encounterId().startsWith(PkTally.KILL) ? row.encounterId() : row.id());
        var links = new HashMap<String, Set<String>>();
        var groups = new LinkedHashMap<String, Group>();
        for (var row : transactions) {
            if (needsReview(row) || row.correction() == Correction.TRANSFER) continue;
            String source = row.claimId().split("#", 2)[0];
            String kill = seeds.getOrDefault(row.id(), seeds.get(source));
            if (kill == null) continue;
            for (var unit : Semantics.transactionUnits(row, Flow::gain)) {
                links.computeIfAbsent(source, key -> new HashSet<>()).add(row.claimId());
                unit.id = kill;
                unit.table = KILL;
                unit.name = "PK loot";
                unit.composite = unit.neutral = false;
                unit.quantity = -1;
                groups.merge(kill, unit, Group::absorb).corrected |= unit.corrected;
            }
        }
        for (var row : transactions) if (row.note().equals(KeyClaims.LOOT_KEY_NOTE)) {
            var group = groups.get(seeds.get(row.id()));
            if (group != null && links.getOrDefault(row.id(), Set.of()).size()
                    < row.flows().stream().mapToLong(flow -> max(1, flow.quantity)).sum()) {
                group.coverage = INCOMPLETE;
                group.context = msg("ku");
            }
        }
        var result = new ArrayList<>(groups.values());
        result.sort(comparingLong((Group group) -> group.at).reversed().thenComparing(group -> group.id));
        return new Result(result);
    }

    static List<PendingItem> pendingItems(Engine engine, Entry e, long now) {
        var items = new ArrayList<PendingItem>();
        if (e.scope != CURRENT_GRIND || readOnly(e)) return items;
        var valued = new HashSet<Integer>();
        for (var claim : engine.pendingClaims()) {
            if (claim == null || !claim.isValid()) continue;
            var chest = KeyChestCatalogue.forKey(claim.itemOrKeyId);
            String name = chest != null ? chest.getChestName() + " key" : claim.itemOrKeyId >= WILDY_LOOT_KEY0
                                            && claim.itemOrKeyId <= WILDY_LOOT_KEY4 ? "Loot key"
                                    : "Key or chest";
            // One chest per key type: stacked claims of it name the same worth once.
            long value = valued.add(claim.itemOrKeyId) ? engine.pendingKeyValue(claim.itemOrKeyId, now) : 0;
            items.add(new PendingItem(claim.itemOrKeyId, name, claim.quantity(), name + msg("opened-books"), value));
        }
        return items;
    }

    /** Ordinary tables sum every group; Market's header includes only complete results. */
    static TableData tableData(List<Group> groups, boolean market) {
        long total = 0;
        var records = new LinkedHashSet<String>();
        for (Group group : groups) {
            if (!market || !group.incomplete()) total = sum(total, group.value);
            records.addAll(group.ids);
        }
        return new TableData(groups, total, groups.size(), records.size());
    }

    /** True when a Market lifecycle is genuinely pending rather than already realized. */
    static boolean isPending(Lifecycle lifecycle) {
        return lifecycle == PENDING || lifecycle == PARTIALLY_EXECUTED || lifecycle == EXECUTED_UNSETTLED
                || lifecycle == PARTIALLY_REALIZED;
    }

    static Group findGroup(List<Group> groups, String groupId, String id, String identifier) {
        // Most specific anchor first: a receipt's own contribution must win over the transaction
        // it came from, or a shop drill lands on the wrong side of the trade (one rune's group).
        for (Group group : groups) {
            if (!group.review && group.containsContribution(groupId)) return group;
        }
        for (Group group : groups) {
            if (!group.review && group.containsContribution(id)) return group;
        }
        for (Group group : groups) {
            if (!group.review && group.containsTransaction(identifier)) return group;
        }
        // Review-required groups are still openable from the audit list detail.
        for (Group group : groups) {
            if (group.containsContribution(id) || group.containsTransaction(identifier)) return group;
        }
        return null;
    }

    /** True when every receipt belongs to one entry: a group whose list would hold one row. */
    static boolean oneEntry(List<Receipt> records) {
        return !records.isEmpty() && records.stream().allMatch(r -> r.transaction.equals(records.get(0).transaction));
    }

    static Receipt firstReceipt(List<Receipt> records, String id) {
        Receipt found = null;
        for (var receipt : records) {
            if (receipt.transaction.equals(id) && (found == null || receipt.at >= found.at))
                found = receipt;
        }
        return found;
    }

    static List<Session> sessionsFor(Engine engine, Entry entry, long now) {
        var sessions = new ArrayList<Session>();
        if (entry.scope == HISTORY) {
            var historical = entry.session == null ? null : engine.historySession(entry.session);
            if (historical != null) sessions.add(historical);
            return sessions;
        }
        var active = engine.session();
        if (active != null) sessions.add(active);
        if (entry.scope == TODAY) {
            var zone = engine.timeZone();
            var today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
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
            if (!empty(entry.historyName)) return entry.historyName;
            var history = entry.session == null ? null : engine.historySession(entry.session);
            return history == null ? "Recent Grind" : history.name();
        }
        return entry.scope == TODAY ? "Runs today" : "Current Grind";
    }

    static boolean readOnly(Entry entry) {
        return entry.scope != CURRENT_GRIND;
    }

    static boolean matchesSearch(ReviewRow row, String query) {
        if (blank(query)) return true;
        String needle = lower(query);
        if (containsFolded(needle, row.why)) return true;
        for (var item : row.items) {
            if (containsFolded(needle, item.name)) return true;
        }
        return false;
    }

    // ── correction support (unchanged semantics: exact canonical ids only) ─────────────────────
    static class CorrectionPreview {
        long currentNet, afterNet, change, revision;

        CorrectionPreview(long net, long afterNet, long revision) {
            currentNet = net;
            this.afterNet = afterNet;
            change = afterNet - net;
            this.revision = revision;
        }

        String note() {
            return change == 0 ? msg("net-gp") : "Net changes by " + signed(change) + " gp.";
        }
    }

    /**
     * Net if a split keeps {@code keep} of {@code itemId}: the split itself runs on a detached copy
     * of the receipt, so the preview and the applied split cannot differ.
     */
    static CorrectionPreview splitPreview(Engine engine, String identifier, int id, long keep, long now) {
        synchronized (engine) {
            long net = engine.metrics(now).net;
            var session = engine.session();
            var transaction = session == null ? null : session.findTransaction(identifier);
            if (transaction == null)
                return new CorrectionPreview(net, net, engine.revision());
            var copy = SaveCopies.copy(transaction);
            copy.takeQuantity(id, true, copy.quantity(id, true) - keep);
            long before = transaction.counts() ? transaction.net() : 0;
            long after = copy.counts() ? copy.net() : 0;
            return new CorrectionPreview(net, net - before + after, engine.revision());
        }
    }

    /** One canonical state, like {@link #splitPreview}: Net before/after and revision agree. */
    static CorrectionPreview preview(Engine engine, String id, Correction decision, long now) {
        synchronized (engine) {
            var session = engine.session();
            long net = engine.metrics(now).net;
            if (session == null || id == null || decision == null)
                return new CorrectionPreview(net, net, engine.revision());
            var transaction = session.findTransaction(id);
            if (transaction == null)
                return new CorrectionPreview(net, net, engine.revision());
            long before = transaction.counts() ? transaction.net() : 0, after = transaction.netUnder(decision);
            return new CorrectionPreview(net, net - before + after, engine.revision());
        }
    }
}

/** Detached, read-only summary of one transaction awaiting an owner decision. */
class ReviewRow {
    String transaction, session;
    long at, ageMillis;
    List<Item> items;
    long value;
    String why;
    Set<Decision> decisions;

    ReviewRow(String id, String sessionId, long timestamp, long ageMillis, List<Item> items,
            long value, String why, Set<Decision> validDecisions) {
        transaction = string(id);
        session = string(sessionId);
        at = positive(timestamp);
        this.ageMillis = positive(ageMillis);
        this.items = unmodifiableList(copiedList(items));
        this.value = value;
        this.why = string(why);
        var copy = empty(validDecisions) ? EnumSet.noneOf(Decision.class)
                        : EnumSet.copyOf(validDecisions);
        decisions = unmodifiableSet(copy);
    }

    /** Detached item summary; value is the signed flow value for this item. */
    static class Item {
        int item;
        String name;
        long quantityDelta, valueDelta;

        Item(int id, String name, long delta, long value) {
            item = id;
            this.name = string(name);
            quantityDelta = delta;
            valueDelta = value;
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
        long target, current;
        boolean reached;

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
    static class Recent {
        int item;
        String name, qty;
        long value;
        boolean neutral;

        /** True when the lead flow has no price: the row shows an unknown marker, never 0. */
        boolean unknown;

        String tag;

        /** How many receipts this row coalesced; 1 for a single receipt. */
        int entries;

        /** The newest canonical receipt behind the row, for Ledger context. */
        String receiptId;

        String id, label;
        long quantity;
        boolean composite, market, known;

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

        /**
         * The row's share of Net. A Market row moves Net by its Result only, never by the cash it
         * moved: a GE buy is neutral (owner live 2026-10-15), an unproven Result counts nothing.
         */
        long net() {
            return !market ? value : known ? marketResult : 0;
        }
    }

    boolean present;
    String ownerLabel;
    long elapsed;
    boolean paused, idle;
    long net, income;

    /** Non-consumable costs: deaths, tax, fees, drops. {@code -1} when the split is unavailable. */
    long loss;

    /**
     * Consumables: food, potions, runes, ammo, charges. {@code -1} when the split is unavailable.
     */
    long supplies;

    /** Total booked cost; always the truthful figure, split or not. */
    long cost;

    boolean split, ready;
    long rate;
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
    boolean possible, skulled, protectItem;
    long risk;
    int reviews;
    List<Recent> recent;

    /** True for any session that has booked a fight. */
    boolean pvp;

    int kills, deaths;
    long bestKill;

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

    long targetUntil;

    long run;

    /** True while Live counts from a target or a Reset Net rather than the whole run. */
    boolean fresh;

    boolean milestoneReached() {
        return milestone != null && milestone > 0 && run >= milestone;
    }

    /**
     * @param context plugin-side idle state, owner marks and loot visibility (may be null)
     */
    static LiveSnapshot capture(Engine engine, long now, LiveContext context) {
        var ctx = context == null ? LiveContext.NONE : context;
        var summary = engine.summary();
        var active = engine.session();
        var metrics = summary.metrics(active, now);
        boolean custom = engine.customActive(), general = active == null || !custom;
        String owner = active == null ? "Free play" : active.name();
        long elapsed = metrics.elapsed;
        var facts = active == null ? null : summary.fact(active);
        int reviewCount = facts == null ? 0 : facts.review;
        // One shared projection for the whole snapshot: Recent rows and the Market separation. The
        // Market rows are rebuilt on every read, so they are read once.
        var marketRows = summary.market;
        // Hero categories are canonical and unfiltered: the whole counted Market-context
        // population is removed from ordinary Gains/Supplies/Losses and represented once by its
        // correction-aware net. Recent Activity display filters never enter this math.
        var market = facts == null ? marketFold(emptyList()) : facts.market;
        // Live shows the current session: what was booked since it began; the run keeps every row
        // and its whole total (owner 1.5.2).
        var shown = metrics;
        Long start = active == null ? null : active.sessionStartAt;
        if (start != null) {
            var window = facts.window(active);
            shown = window.windowMoney;
            market = window.windowMarket;
        }
        long since = active == null ? 0 : active.liveElapsed(now), rate = Session.hourly(shown.net, since);
        long marketResult = market.net();
        int marketPending = 0;
        var markets = new HashMap<String, Row>();
        for (Row row : marketRows) {
            if (row != null) {
                markets.put(row.presentationId, row);
                marketPending += LedgerData.isPending(row.lifecycle) ? 1 : 0;
            }
        }
        var pk = active == null ? new PkTally() : active.pk();
        boolean pvpSession = pk.encounters() > 0;
        return new LiveSnapshot(active == null ? "" : active.id(), active != null, owner, since, metrics.paused,
                ctx.idle, shown.net, visibleGains(shown, market), visibleLosses(shown, market),
                visibleSupplies(shown, market), visibleCosts(shown, market), shown.split,
                RateReadiness.isEstablished(since), rate, goal(active, metrics, elapsed), marketResult,
                marketPending, general, ctx.activity, ctx.target, ctx.pvp.possible, ctx.pvp.skulled,
                ctx.pvp.protectItem, ctx.pvp.risk, reviewCount,
                recent(engine, active, marketRows, markets, ctx), pvpSession, pk.kills,
                pk.deaths, engine.bestKill(), engine.pendingClaims().size(), engine.pendingKeysValue(now),
                max(0, pk.streak), pk.bestStreak, active == null ? ctx.offline
                        : active.paused && active.pauseReason() == PauseReason.LIFECYCLE,
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
        if (active == null) return null;
        Long target = active.targetGp(), duration = active.timeTargetMillis();
        if (target != null && target > 0) {
            long current = active.targetProgress(metrics.net);
            double fraction = max(0d, min(1d, current / (double) target));
            boolean reached = current >= target;
            String eta = null, timeDetail = null;
            long timeRemaining = -1;
            boolean timeDone = false, timerPaused = false;
            // The aim's own pace: Net and Active Time since it was set, so free play before it
            // never counts (owner 2026-10-16).
            long since = active.profitTargetActiveMillis(elapsed);
            boolean established = RateReadiness.isEstablished(since);
            long rate = Session.hourly(current, since);
            if (duration != null && duration > 0) {
                // Owner live 2026-10-13: with both targets set the hero keeps the live Net read
                // and the card adds the tracked Active Time pair; the countdown replaces the
                // Net-rate estimate.
                long timeElapsed = active.timeWindowMillis(elapsed);
                timeDetail = timeDetail(timeElapsed, duration);
                timeRemaining = positive(duration - timeElapsed);
                timeDone = timeElapsed >= duration;
                timerPaused = active.timeTargetPaused();
                // The time line says it all once; no projection repeats the hero's GP/h (owner 1.5.2).
            } else if (established && !reached) {
                // An established non-positive rate says so honestly (owner live 2026-10-13); a reached
                // aim adds nothing the hero does not show (owner 1.5.2).
                long minutes = rate > 0 ? round((target - current) * 60d / rate) : 0;
                eta = rate <= 0 ? "Not earning at this rate"
                                        : (minutes < 1 ? "<1m" : "~" + GrindsData.durationMinutes(minutes))
                                                + msg("play-left");
            }
            return new Goal(target, current, reached, compact(target), reached ? "reached" : percent(fraction), eta,
                    fraction, false, timeDetail, timeRemaining, timeDone, timerPaused);
        }
        // Owner 2026-10-01 (F10): a time-only target is still a target; the card tracks Active
        // Time.
        if (duration == null || duration <= 0) return null;
        long timeElapsed = active.timeWindowMillis(elapsed);
        double timeFraction = max(0d, min(1d, timeElapsed / (double) duration));
        boolean timeReached = timeElapsed >= duration;
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
        if (elapsed >= duration) return "Time target reached";
        return durationCount(duration - elapsed) + " left of " + durationShort(duration) + " · "
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
        List<Object> key = Arrays.asList(engine, engine.revision(), active, market,
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
                var ids = new HashSet<String>();
                for (var row : rows) ids.add(row.id());
                var trades = FlowFilters.matching(summary.market, trade -> LedgerData.isPending(trade.lifecycle)
                        || !disjoint(ids, Arrays.asList((trade.settlementId + trade.earlierSettlements).split(" "))));
                var visible = recentRows(Semantics.capture(rows, trades, singleton(active.id()), ctx::flowVisible), markets, ctx.filter);
                var fresh = new ArrayList<Recent>();
                for (var row : visible) if (row.open) fresh.add(row);
                for (var row : visible) if (!row.open) fresh.add(row);
                recentList = unmodifiableList(fresh);
            }
            recentKey = key;
        }
        return recentList;
    }

    static List<Recent> recentRows(Result projection, Map<String, Row> market, RecentFilter filter) {
        var rows = new ArrayList<Recent>();
        int count = 0, limit = filter == null ? RECENT_ROWS : max(1, min(30, filter.recentRows()));
        for (Group group : projection.groups) {
            if (count >= limit && !group.market) continue;
            Recent row = toRecent(group, group.marketId.isEmpty() ? null
                                    : market.get(group.marketId));
            // A canceled offer that returned everything stays in the Ledger, not Recent (owner
            // 2026-09-28).
            if (row == null || "canceled".equals(row.tag) || !row.open && count >= limit) continue;
            // Supply spends also answer to the separate saved list; acquisitions stay visible.
            // A composite has no item quantity; its name must still match a saved hide rule.
            if (!row.open && filter != null && !filter.showRecent(row.name, max(1, row.quantity),
                            row.supplyRow))
                continue;
            // A Ground Items-highlighted gain stands out in its highlight colour (owner 1.5.0, after
            // Better Loot Notifier).
            if (filter != null && row.value > 0 && !row.market)
                row.highlight = filter.highlight(row.name, row.quantity);
            rows.add(row);
            if (!row.open) count++;
        }
        return unmodifiableList(rows);
    }

    /** One semantic group as the Live row the page paints, with proven Market value semantics. */
    static Recent toRecent(Group group, Row row) {
        if (group == null) return null;
        boolean market = group.market, realizedMarket = market && group.coverage == COMPLETE;
        long marketResult = group.value;
        boolean known = market && row != null && row.realized()
                        && row.settledQty > 0 && row.observedSettlementGp != 0;
        boolean marketSell = row != null && "SELL".equals(row.side.name());
        boolean marketCanceled = row != null && row.lifecycle == CANCELLED_RETURNED;
        String marketSide = row == null ? "" : row.realized() ? marketSell ? "Sold" : "Bought"
                                : MarketText.stateWord(row);
        boolean unknown = !market && group.coverage == INCOMPLETE;
        String tag = group.review ? "review" : marketCanceled ? "canceled"
                                : market ? "market" : unknown ? "unpriced" : null;
        String quantity = quantityText(group), qty, label;
        if (market) {
            // Cash movement first: the primary value is the observed signed settlement; the
            // canonical Market result is the explicit secondary line, never the primary.
            String detail = realizedMarket ? marketSide.isEmpty() ? "Result " + signed(marketResult)
                                    : marketSide + " · Result " + signed(marketResult) : known
                                    ? marketSide + " · Result —" : group.context;
            // A finished trade shows its quantity like any item ("×500"); the line keeps its
            // side
            // and Result (owner 2026-10-08).
            qty = known ? quantityText(group) : null;
            label = detail;
        } else if (group.actionGroup()) {
            // An exact named spell is its own title, so the verb plus the receipt count is the
            // honest second line. A generic action keeps its composition, which is the only
            // context that distinguishes it.
            // Owner 2026-09-28: a count per cast like any item ("×23"), never "23 receipts", so the
            // narrow quantity column leaves every name readable.
            String casts = group.count > 1 ? times(group.count) : null;
            qty = !group.name.equalsIgnoreCase(group.label) ? casts
                            : casts == null ? group.context : group.context + " · " + casts;
            label = group.label;
        } else if (!group.failedOf().isEmpty()) {
            qty = group.failedOf();
            label = group.failedTip();
        } else {
            qty = quantity;
            // A run whose every unit failed so far reads as failed, whichever receipt came first.
            label = group.failed > 0 ? msg("failed") : group.label;
        }
        CostView ledgerCostView = group.table == COSTS_SUPPLIES
                        ? group.charge ? CostView.CHARGES : CostView.SUPPLIES
                        : group.table == COSTS_LOSS ? CostView.LOSS : null;
        boolean neutral = group.neutral;
        long value = neutral ? 0 : group.value;
        boolean settlementValue = false;
        if (known && !neutral) {
            value = MarketText.settlementCash(row, marketSell);
            settlementValue = true;
        }
        return new Recent(group.item, group.name, qty, value, neutral, unknown, tag, group.count,
                group.transaction, group.contributionId, label,
                positive(group.quantity), group.composite, market,
                market ? group.coverage == COMPLETE : !unknown, ledgerCostView, group.review,
                marketResult, settlementValue, marketSide,
                row != null && LedgerData.isPending(row.lifecycle),
                group.table == COSTS_SUPPLIES, null);
    }

    /**
     * "1 dose"/"×3 doses" for any drink, "1 portion"/"×2 portions" for pies, pizzas and
     * cakes, otherwise "×N" (a sapling planted from its pot is not a portion).
     */
    static String quantityText(Group group) {
        // Coins read as their value only (owner 2026-10-16).
        if (group.item == 995) return null;
        String name = group.name.toLowerCase(ROOT);
        String unit = has(name, "pizza", "cake", "pie") ? "portion"
                        : group.label.equals("Drank") ? "dose" : null;
        if (group.dose && unit != null) {
            if (group.quantity == 1) return "1 " + unit;
            return group.quantity > 1 ? times(group.quantity) + " " + unit + "s" : null;
        }
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
    static final LiveContext NONE = new LiveContext(false, null, 0, PvpState.NONE, "", false, "", false);
    boolean idle;

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
        this.idle = idle;
        this.filter = filter;
        minimumLoot = positive(minimumDisplayedLootValue);
        this.pvp = pvp == null ? PvpState.NONE : pvp;
        this.activity = string(activity);
        this.offline = offline;
        this.target = string(target);
        this.hopping = hopping;
    }

    /** Whether one flow stays on a presentation row under the current visibility settings. */
    boolean flowVisible(Flow flow) {
        if (flow == null) return false;
        // Costs, fees and deaths are never hidden by the display loot filter.
        if (flow.quantity <= 0) return true;
        // Always show beats Ground Items' hidden list and the minimum (owner 1.5.0, after Better
        // Loot Notifier); Hidden Recent items still wins on Recent.
        if (filter != null && filter.alwaysShows(flow)) return true;
        // Unknown / unpriced gains remain discoverable for Review; the minimum is
        // compared against the unit price so quantity cannot defeat it.
        if (flow.unitPrice > 0 && flow.unitPrice < minimumLoot) return false;
        return filter == null || filter.isFlowIncluded(flow);
    }
}

/**
 * Client-side PvP facts the plugin observes on its tick (Wilderness / PvP world, skull, Protect
 * Item, what a death would lose). Presentation only; nothing here touches accounting.
 */
@AllArgsConstructor
class PvpState {
    static final PvpState NONE = new PvpState(false, false, false, 0);
    static final Set<Integer> ALWAYS_LOST = byId("death_always_lost").keySet();
    boolean possible, skulled, protectItem;

    /** GP a death here would lose; 0 where no death can be lost. */
    long risk;

    /**
     * Every held unit but the most valuable 3 (none when skulled), one more with Protect Item, at
     * today's prices; an unpriced unit counts as 0. A stack keeps units, not the whole stack.
     * Always-lost items contribute their full value without using a protected slot.
     */
    static long risk(List<Flow> held, boolean skulled, boolean protectItem) {
        var stacks = new ArrayList<>(held);
        stacks.removeIf(flow -> flow == null || flow.quantity <= 0);
        stacks.sort((a, b) -> Long.compare(deathValue(b), deathValue(a)));
        long keep = (skulled ? 0 : 3) + (protectItem ? 1 : 0), risk = 0;
        for (Flow stack : stacks) {
            long kept = ALWAYS_LOST.contains(stack.item) ? 0 : min(keep, stack.quantity);
            keep -= kept;
            risk = sum(risk, multiply(positive(deathValue(stack)), stack.quantity - kept));
        }
        return risk;
    }

    /**
     * What a death weighs the item at. Since 2026-07-29 the game ranks every Avernic defender at
     * 600,000, its repair cost when it breaks unprotected (OSRS Wiki).
     */
    static long deathValue(Flow stack) {
        return pattern("avernic").matcher(lower(stack.name)).matches() ? 600_000
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
    static final int NONE = 0, HIGHLIGHTED = 1, HIDDEN = 2;
    final GroundItemsConfigReader configReader;
    volatile GroundItemsPrefs snapshot;
    volatile String fingerprint = "";

    /** Test helper with a fixed snapshot (no live ConfigManager). */
    LootPresentationFilterService(GroundItemsPrefs snapshot) {
        configReader = null;
        replaceSnapshotForTests(snapshot);
    }

    synchronized void refresh() {
        if (configReader == null) return;
        replaceSnapshotForTests(configReader.read());
    }

    /** Applies only explicit list preferences; ambiguous value/price rules stay visible. */
    boolean isFlowIncluded(Flow flow, LootFilter mode) {
        var effective = mode == null ? LootFilter.ALL_ITEMS : mode;
        if (effective == LootFilter.ALL_ITEMS || flow == null) return true;
        ensureSnapshot();
        var current = snapshot;
        if (!current.pluginEnabled) return true;
        int state = listState(current.highlights, current.hiddenCsv, flow.name,
                        positive(Math.abs(flow.quantity)));
        if (state == HIDDEN) return false;
        return effective != LootFilter.HIGHLIGHTED_LIST_ONLY ? !current.highlightedOnly || state == HIGHLIGHTED
                : state == HIGHLIGHTED;
    }

    /** Ground Items' highlight colour for an item on its highlighted list, unless filters are off. */
    Color highlight(String name, long quantity, LootFilter mode) {
        ensureSnapshot();
        var current = snapshot;
        return mode == LootFilter.ALL_ITEMS || !current.pluginEnabled
                        || listState(current.highlights, "", name, quantity) != HIGHLIGHTED ? null
                : current.highlight;
    }

    /** HUD+'s big-drop line: Ground Items' insane value when the display follows it, else 10M. */
    long big(LootFilter mode) {
        ensureSnapshot();
        var current = snapshot;
        return mode == LootFilter.FOLLOW_GROUND_ITEMS && current.pluginEnabled && current.insane > 0 ? current.insane
                : HudTray.BIG_DROP;
    }

    synchronized void replaceSnapshotForTests(GroundItemsPrefs value) {
        snapshot = value == null ? GroundItemsPrefs.disabled() : value;
        fingerprint = snapshot.fingerprint;
    }

    synchronized void ensureSnapshot() {
        if (snapshot == null) refresh();
        if (snapshot == null) replaceSnapshotForTests(null);
    }

    static int listState(String highlights, String hiddenCsv, String name, long quantity) {
        int exact = listMatch(highlights, name, quantity, false);
        if (exact != NONE) return HIGHLIGHTED;
        exact = listMatch(hiddenCsv, name, quantity, false);
        if (exact != NONE) return HIDDEN;
        int wildcard = listMatch(highlights, name, quantity, true);
        if (wildcard != NONE) return HIGHLIGHTED;
        return listMatch(hiddenCsv, name, quantity, true) == NONE ? NONE : HIDDEN;
    }

    static int listMatch(String csv, String title, long quantity, boolean wildcard) {
        String name = string(title);
        for (Rule rule : rules(csv)) {
            if (rule.wildcard == wildcard && rule.matches(name, quantity)) return 1;
        }
        return NONE;
    }

    /** Parsed list rules by list text: the Ground Items lists are parsed once, not per item. */
    static final Map<String, List<Rule>> RULES = new ConcurrentHashMap<>();

    static List<Rule> rules(String csv) {
        if (RULES.size() > 16) RULES.clear();
        return RULES.computeIfAbsent(string(csv), text -> {
                    var parsed = new ArrayList<Rule>();
                    for (String raw : Text.fromCSV(text)) {
                        Rule rule = Rule.parse(raw);
                        if (rule != null) parsed.add(rule);
                    }
                    return parsed;
                });
    }

    @AllArgsConstructor
    static class Rule {
        String name;
        int quantity;
        boolean lessThan, wildcard;

        static Rule parse(String value) {
            if (blank(value)) return null;
            String text = value.trim();
            int split = -1;
            for (int i = text.length() - 1; i >= 0; i--) {
                char c = text.charAt(i);
                if (Character.isDigit(c) || Character.isWhitespace(c)) continue;
                if (c == '<' || c == '>') split = i;
                break;
            }
            int quantity = 0;
            boolean less = false;
            if (split >= 0 && split + 1 < text.length()) {
                try {
                    quantity = parseInt(text.substring(split + 1).trim());
                    less = text.charAt(split) == '<';
                    text = text.substring(0, split).trim();
                } catch (NumberFormatException ignored) {
                }
            }
            return new Rule(text, max(0, quantity), less, text.contains("*"));
        }

        boolean matches(String title, long count) {
            if (wildcard ? !WildcardMatcher.matches(name, title) : !name.equalsIgnoreCase(title)) return false;
            return lessThan ? count < quantity : count > quantity;
        }
    }
}

/**
 * Reads only the active Ground Items list preferences through supported RuneLite APIs. Never
 * reflects into private Ground Items fields or package-private matchers.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class GroundItemsConfigReader {
    static final Logger log = LoggerFactory.getLogger(GroundItemsConfigReader.class);
    final ConfigManager config;
    final PluginManager pluginManager;

    GroundItemsPrefs read() {
        boolean enabled = isGroundItemsEnabled();
        GroundItemsConfig config;
        try {
            config = this.config.getConfig(GroundItemsConfig.class);
        } catch (RuntimeException ex) {
            log.debug(msg("ground-items"), ex);
            return GroundItemsPrefs.disabled();
        }
        if (config == null) return GroundItemsPrefs.disabled();
        var read = new GroundItemsPrefs(enabled, config.getHighlightItems(), config.getHiddenItems(),
                        config.showHighlightedOnly());
        read.insane = config.insaneValuePrice();
        read.highlight = config.highlightedColor();
        read.fingerprint = read.buildFingerprint();
        return read;
    }

    boolean isGroundItemsEnabled() {
        if (pluginManager == null) return false;
        for (var plugin : pluginManager.getPlugins()) {
            if (plugin instanceof GroundItemsPlugin) return pluginManager.isPluginEnabled(plugin);
        }
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

    boolean pluginEnabled;
    String highlights, hiddenCsv;
    boolean highlightedOnly;

    /**
     * Ground Items' insane value: HUD+'s big-drop line while the loot display follows Ground Items.
     */
    long insane = HudTray.BIG_DROP;

    /** Ground Items' highlighted-item colour, for highlighted drops on the tray and Live. */
    Color highlight;

    String fingerprint;

    GroundItemsPrefs(boolean pluginEnabled, String highlights, String hiddenCsv, boolean showHighlightedOnly) {
        this.pluginEnabled = pluginEnabled;
        this.highlights = string(highlights);
        this.hiddenCsv = string(hiddenCsv);
        highlightedOnly = showHighlightedOnly;
        fingerprint = buildFingerprint();
    }

    /** Empty / disabled fallback — callers treat filtering as pass-through. */
    static GroundItemsPrefs disabled() {
        return new GroundItemsPrefs(false, "", msg("vial-ashes"), false);
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
class GameData {
    /** Rule tables alone are cached: callers of legacy rows still receive independent arrays. */
    static final Map<String, List<String[]>> ROWS = new HashMap<>();

    static final Map<String, Map<String, Clause[][]>> RULES = new HashMap<>();

    static synchronized List<String[]> cached(String name) {
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
        for (String[] row : rows(name)) map.put(parseInt(row[0]), row[1]);
        return map;
    }

    static int[] intColumn(String name, int column) {
        return cached(name).stream().mapToInt(row -> parseInt(row[column])).toArray();
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
    static synchronized Map<String, Clause[][]> rules(String name) {
        if (RULES.containsKey(name)) return RULES.get(name);
        var compiled = new LinkedHashMap<String, Clause[][]>();
        for (String[] row : cached(name))
            compiled.put(row[0], Arrays.stream(text(row[1]).split("\\|", -1)).filter(d -> !d.isEmpty()).map(d ->
                                            Arrays.stream(d.split("\\+", -1)).map(Clause::new)
                                                    .toArray(Clause[]::new)).toArray(Clause[][]::new));
        RULES.put(name, compiled);
        return compiled;
    }

    static final Map<String, String> TEXT = byName("text");
    static final Map<String, Pattern> PATTERNS = new HashMap<>();

    static {
        for (String[] row : rows("patterns"))
            PATTERNS.put(row[0], Pattern.compile(row[1].startsWith("@") ? msg(row[1].substring(1)) : row[1],
                            parseInt(row[2])));
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

    /** The value, or zero when it is negative. */
    static long positive(long value) {
        return max(0, value);
    }

    /** Absolute value that saturates instead of overflowing on {@code Long.MIN_VALUE}. */
    static long abs(long value) {
        return value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
    }

    static long sum(long left, long right) {
        try {
            return addExact(left, right);
        } catch (ArithmeticException ex) {
            return right >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    static long subtract(long left, long right) {
        try {
            return subtractExact(left, right);
        } catch (ArithmeticException ex) {
            return right < 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    static long multiply(long left, long right) {
        try {
            return multiplyExact(left, right);
        } catch (ArithmeticException ex) {
            return left >= 0 == right >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    /** A long clamped into the int range (unit prices). */
    static int toInt(long value) {
        return (int) max(Integer.MIN_VALUE, min(Integer.MAX_VALUE, value));
    }

    /** Non-negative count sum saturating at {@link Integer#MAX_VALUE}. */
    static int safeAddCount(int left, int right) {
        return toInt((long) max(0, left) + max(0, right));
    }
}

/** Display-name defaults and phrase matching shared by the model and the classifiers. */
class Phrases {
    /** New mutable ownership, including an independently owned empty list. */
    static <T> ArrayList<T> copiedList(Collection<? extends T> source) {
        return new ArrayList<>(source == null ? emptyList() : source);
    }

    /** Immutable snapshot; its source must exist, and later source edits are invisible. */
    static <T> List<T> frozenList(Collection<? extends T> source) {
        return unmodifiableList(new ArrayList<>(source));
    }

    /** Null reads as empty; otherwise preserve the existing live, unmodifiable view. */
    static <T> List<T> readOnlyList(List<T> source) {
        return source == null ? emptyList() : unmodifiableList(source);
    }


    static String uuid() {
        return UUID.randomUUID().toString();
    }

    /** Blank activity names fall back to the General bucket. */
    static String normalizeActivity(String value) {
        return fallback(value, "General");
    }

    static String string(String text) {
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
    static String fallback(String text, String fallback) {
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
            if (spec.startsWith("@")) spec = msg(spec.substring(1));
            negated = spec.startsWith("!");
            if (negated) spec = spec.substring(1);
            kind = spec.isEmpty() ? -1 : "=^$%~".indexOf(spec.charAt(0));
            text = kind < 0 ? spec : spec.substring(1);
            pattern = kind == 3 ? pattern(text) : null;
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
    /** Exact original market prices can prove a blighted sip without today's family quote. */
    static boolean capturedDose(List<Flow> flows) {
        if (flows.size() != 2) return false;
        var pairs = partialUses(flows);
        if (pairs.size() != 1) return false;
        var pair = pairs.get(0);
        var loss = flows.get(pair.lossIndex);
        var rest = flows.get(pair.remainder);
        int doses = dose(loss.name);
        return doses > 1 && doses <= 4 && loss.item != rest.item
                && map(loss.item) == map(rest.item) && pattern("blighted-dose").matcher(loss.name).find()
                && loss.source() == GRAND_EXCHANGE && rest.source() == GRAND_EXCHANGE
                && loss.unitPrice > 0 && loss.unitPrice % doses == 0
                && rest.unitPrice == loss.unitPrice / doses * (doses - 1);
    }

    /** Whether the current world/economy permits ordinary automatic RuneLite market quotes. */
    enum Economy {
        NORMAL, UNSUPPORTED_SPECIAL, UNKNOWN
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

    IntFunction<ItemComposition> compositions;
    MarketQuote marketQuotes;
    IntPredicate unsupportedMapping;
    GpManagerConfig config;
    @Getter volatile Economy economyState;

    /** Karamja gloves 1-4 worn: sampled by the plugin, read when a proxy rate is chosen. */
    volatile boolean gloves;

    /** A market quote floored at zero; an unavailable quote reads 0 (the flow stays unpriced). */
    long quote(int id) {
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
                items::getItemPriceWithSource,
                id -> hasUnsupportedMapping(items, id), Economy.UNKNOWN);
    }

    Valuation(GpManagerConfig config, IntFunction<ItemComposition> compositions, MarketQuote marketQuotes,
            IntPredicate unsupportedMapping, Economy economyState) {
        this.config = config;
        this.compositions = compositions;
        this.marketQuotes = marketQuotes;
        this.unsupportedMapping = unsupportedMapping == null ? id -> false : unsupportedMapping;
        this.economyState = economyState == null ? Economy.UNKNOWN : economyState;
    }

    public List<Flow> value(Map<Integer, Long> changes) {
        return value(changes, 0);
    }

    public List<Flow> value(Map<Integer, Long> changes, long at) {
        var flows = new ArrayList<Flow>();
        var prices = priceOverrides();
        for (var entry : changes.entrySet()) {
            Flow flow = valueOne(entry.getKey(), entry.getValue(), prices, at, true);
            if (flow != null) flows.add(flow);
        }
        flows.sort(Comparator.comparingLong((Flow flow) -> abs(flow.value)).reversed());
        return flows;
    }

    /**
     * Revalues only a confirmed Drink action. Ordinary valuation remains a quote of each observed
     * item variant; this hook is deliberately separate so a decant, transfer or production shape
     * cannot be mistaken for consumption before action settlement.
     */
    public List<Flow> normalizeConsumedFlows(List<Flow> flows, ActionKind action, long at) {
        if (flows == null || flows.isEmpty() || action != ActionKind.DRINK || isDecant(flows))
            return flows;
        var prices = priceOverrides();
        var pairs = partialUses(flows);
        if (pairs.isEmpty()) return flows;
        // Each exact pair is an atomic accounting unit. Compute both replacement flows before
        // assigning either one; an unsupported basis returns null and leaves the caller's
        // original captured list untouched for Review.
        var normalized = new ArrayList<>(flows);
        for (var pair : pairs) {
            if (!normalizePotionPair(normalized, pair.lossIndex, pair.remainder, prices,
                    at))
                return null;
        }
        return normalized;
    }

    Flow doseAuthority(Flow flow, Map<Integer, Long> prices, long now) {
        if (flow == null) return null;
        String one = (doseBase(flow.name) + "(1)").trim().toLowerCase(Locale.ROOT);
        Flow geCandidate = null;
        try {
            for (int id : getVariations(map(flow.item))) {
                Flow unit = valueOne(id, 1, prices, now, pattern("blighted-dose").matcher(flow.name).find());
                String unitName = unit == null ? "" : unit.name;
                if (unitName == null || !one.equals(Phrases.lower(unitName))) continue;
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
        int dose = dose(name);
        String base = doseBase(name);
        if (dose <= 0 || base.isEmpty()) return null;
        int anchor = 0;
        long anchorPrice = 0;
        boolean manual = false;
        try {
            for (int id : getVariations(map(identifier))) {
                if (id == identifier) continue;
                String otherName = compositionName(id);
                int other = dose(otherName);
                if (other <= 0 || !base.equalsIgnoreCase(doseBase(otherName)))
                    continue;
                Long override = prices.get(id);
                if (override != null && override > 0) {
                    if (!manual || other > anchor) {
                        manual = true;
                        anchor = other;
                        anchorPrice = override;
                    }
                    continue;
                }
                if (manual || other <= anchor) continue;
                long quote = quote(id);
                if (quote > 0) {
                    anchor = other;
                    anchorPrice = quote;
                }
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        if (anchor <= 0 || anchorPrice <= 0) return null;
        long scaled = anchorPrice * dose / anchor;
        if (scaled <= 0) return null;
        return new Flow(identifier, name, 1, scaled, scaled, manual ? MANUAL_OVERRIDE : GRAND_EXCHANGE, 0);
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
                multiplyExact(loss.quantity, loss.unitPrice);
                multiplyExact(rest.quantity, rest.unitPrice);
                return loss.unitPrice >= rest.unitPrice;
            }
            Flow authority = doseAuthority(loss, prices, now);
            if (!compatibleWithAuthority(loss, authority) || !compatibleWithAuthority(rest, authority)) return false;
            long lossUnit = multiplyExact(lostDose, authority.unitPrice);
            long remainderUnit = multiplyExact(dose, authority.unitPrice);
            Flow normalizedLoss = loss.raid(new Flow(loss.item, loss.name, loss.quantity, toIntExact(lossUnit),
                            multiplyExact(loss.quantity, lossUnit), authority.source(),
                            authority.pricedAt()));
            Flow normalizedRemainder = rest.raid(new Flow(rest.item, rest.name, rest.quantity,
                            toIntExact(remainderUnit), multiplyExact(rest.quantity, remainderUnit),
                            authority.source(), authority.pricedAt()));
            normalized.set(index, normalizedLoss);
            normalized.set(remainderIndex, normalizedRemainder);
            return true;
        } catch (ArithmeticException ex) {
            return false;
        }
    }

    private static boolean compatibleWithAuthority(Flow flow, Flow authority) {
        if (flow == null || authority == null) return false;
        if (authority.source() == GRAND_EXCHANGE)
            return flow.source() == GRAND_EXCHANGE && flow.unitPrice > 0;
        return authority.source() == MANUAL_OVERRIDE
                && (flow.source() == GRAND_EXCHANGE && flow.unitPrice > 0
                        || flow.source() == MANUAL_OVERRIDE);
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
        return economyState == Economy.NORMAL;
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
            switch (type) {
                case DEADMAN:
                case SEASONAL:
                case HARDCORE_MAIN:
                case QUEST_SPEEDRUNNING:
                case BETA_WORLD:
                case TOURNAMENT_WORLD:
                case NOSAVE_MODE:
                    return Economy.UNSUPPORTED_SPECIAL;
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
            claim |= pattern("clue").matcher(name).matches();
            // An empty vial is a physical neutral; filled variants remain ordinary items.
            if (id == VIAL_EMPTY) return null;
            boolean manual = prices.containsKey(id);
            int faceValue = faceValueOf(id);
            // For ordinary items, a user override is authoritative even before RuneLite prices
            // load; fixed currencies below retain their face value by policy.
            long quote = 0;
            // RuneLite's ItemManager price helper is intentionally convenient: when an item has
            // no direct quote it can recurse through ItemMapping and price an untradeable variant
            // from another item (for example unidentified minerals or golden nuggets via coal).
            // Those catalogue entries are non-GP currencies in GP Manager, so their physical
            // identity must not inherit a monetary identity from the platform mapping. An
            // explicit owner override remains authoritative.
            if (!manual && !claim && faceValue == 0 && !CurrencyProxyCatalogue.isMapped(id)
                    && automaticMarketQuotesAvailable() && !unsupportedMapping.test(id))
                // GP Manager's single automatic market policy: the explicit active route,
                // gated by the current world/economy and the mapping guard above.
                quote = quote(id);
            // A proxy row values the component from a market item and its fixed exchange rate.
            // The proxy's market item is priced only where market quotes are, never on unknown or
            // special worlds (Codex review 1.2).
            int[] proxy = !manual && quote == 0 && automaticMarketQuotesAvailable() ? CurrencyProxyCatalogue.proxyOf(id)
                            : null;
            if (proxy != null) {
                // Some exchanges improve while Karamja gloves are worn; the proxy row may
                // carry the improved units after a slash (tokkul: 300,000 or 260,000 per onyx).
                int units = proxy[1];
                if (gloves) {
                    int glovesUnits = CurrencyProxyCatalogue.glovesUnitsOf(id);
                    if (glovesUnits > 0) units = glovesUnits;
                }
                // No proxy quote: the flow stays unpriced for Review.
                quote = quote(proxy[0]) / units;
            }
            // RuneLite's ItemMapping carries no bracelet of ethereum entry (upstream omission,
            // verified 2026-10-12): the charged bracelet prices from its uncharged form's live
            // quote, the same relationship the revenant weapons already have.
            if (quote == 0 && id == WILD_CAVE_BRACELET_CHARGED && !manual
                    && automaticMarketQuotesAvailable() && !unsupportedMapping.test(id))
                // No quote: the flow stays unpriced for Review.
                quote = quote(WILD_CAVE_BRACELET_UNCHARGED);
            // A partial dose RuneLite cannot quote (Burning amulet(4)) is priced from its family's
            // highest-dose quote at the same rate per dose (owner 1.3.4, GP-Per-Hour's fractional
            // model); a manual override anywhere in the family is authoritative.
            Flow doseQuote = null;
            if (quote == 0 && doseFallback && !manual && !claim && faceValue == 0
                    && automaticMarketQuotesAvailable() && !CurrencyProxyCatalogue.isMapped(id)
                    && !unsupportedMapping.test(id))
                doseQuote = doseFamilyQuote(id, name, prices);
            // Half a pie, a pizza half or a cake slice is its share of the whole one's quote
            // (owner live 2026-10-16: Half a summer pie read "?").
            String part = PARTS.get(String.valueOf(id));
            if (quote == 0 && part != null && !manual && automaticMarketQuotesAvailable()) {
                String[] share = part.split(" ");
                quote = multiply(quote(parseInt(share[0])), parseInt(share[1])) / parseInt(share[2]);
            }
            long price;
            PriceSource source;
            if (claim) {
                // A non-tradeable key is an unopened claim, not held wealth. Its
                // contents become ordinary valued inventory gains only on receipt.
                price = 0;
                source = PriceSource.DEFERRED_CLAIM;
            } else if (faceValue > 0) {
                price = faceValue;
                source = FACE_VALUE;
            } else if (manual) {
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
                source = doseQuote.source();
            } else if (composition != null && !CurrencyProxyCatalogue.isMapped(id)
                    && !unsupportedMapping.test(id) && (!composition.isTradeable() || automaticMarketQuotesAvailable()
                                    && quote(NATURERUNE) > 0)) {
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

    static int faceValueOf(int id) {
        if (id == COINS) return 1;
        if (id == PLATINUM) return 1000;
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
            if (mapping.getQuantity() != 1 || mapping.getTradeableItem() == COINS)
                return true;
        }
        return false;
    }

    static long multiply(long quantity, long price) {
        try {
            return multiplyExact(quantity, price);
        } catch (ArithmeticException ex) {
            return quantity >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    /** The owner's manual price overrides by item id. */
    Map<Integer, Long> priceOverrides() {
        return ItemRuleParser.parsePriceOverrides(config.manualPriceOverrides());
    }
}

class ItemRuleParser {
    static Map<Integer, Long> parsePriceOverrides(String value) {
        if (Phrases.blank(value)) return emptyMap();
        var result = new HashMap<Integer, Long>();
        for (String token : value.split(",")) {
            String[] parts = token.trim().split("=", 2);
            if (parts.length != 2) continue;
            try {
                int id = parseInt(parts[0].trim());
                long price = Long.parseLong(parts[1].trim());
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
        return manager == null ? emptyList() : manager.search(name);
    }
}

/**
 * Untradeable non-GP currencies. They are unpriced unless a manual override supplies a price or a
 * proxy row prices them from a market item and its fixed exchange rate: RuneLite's price helper
 * would otherwise value some of them through an item mapping. A proxy's units cell may read
 * base/with-gloves when Karamja gloves improve that exchange.
 */
class CurrencyProxyCatalogue {
    static final Set<Integer> CURRENCIES = byId("currencies").keySet();

    /** Proxy rows: item id to {market item id, units per proxy item}. */
    static final Map<Integer, int[]> PROXIES = new HashMap<>();

    /** Proxy rows whose exchange improves with Karamja gloves worn: item id to gloves units. */
    static final Map<Integer, Integer> GLOVES_UNITS = new HashMap<>();

    static {
        for (String[] row : rows("currencies")) {
            if (row.length > 2) {
                String[] units = row[2].split("/");
                PROXIES.put(parseInt(row[0]), new int[] {parseInt(row[1]), parseInt(units[0])});
                if (units.length > 1)
                    GLOVES_UNITS.put(parseInt(row[0]), parseInt(units[1]));
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

// Persistence and account ownership.




/** Coordinates account binding, detached snapshots, and ordered persistence. */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class Persistence {
    static final Logger log = LoggerFactory.getLogger(Persistence.class);
    final Client client;
    final ConfigManager config;
    final SessionRepository repository;
    final OrderedPersistenceWriter writer;
    final Engine engine;
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

    ExecutorService worker;
    volatile boolean closing, loading, failed, needsSnapshot, switchBlocked;

    /**
     * Serializes backup file IO without holding the coordinator monitor the client thread reads.
     */
    final Object backup = new Object();

    final AtomicLong request = new AtomicLong(), epoch = new AtomicLong();
    final AtomicBoolean readySignal = new AtomicBoolean(), closed = new AtomicBoolean();

    void start() {
        synchronized (lock) {
            if (closing) return;
            writer.start();
            epoch.incrementAndGet();
            closing = false;
            loading = false;
            failed = false;
            switchBlocked = false;
            needsSnapshot = false;
            readySignal.set(false);
            closed.set(false);
            if (worker == null || worker.isShutdown())
                worker = OrderedPersistenceWriter.daemonExecutor(msg("gp-manager-lifecycle"));
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
                closing = false;
                epoch.incrementAndGet();
            }
        }
        long epoch = this.epoch.get();
        var executor = executor();
        if (executor == null) {
            failed = true;
            return;
        }
        try {
            executor.execute(() -> {
                        try {
                            if (!closing && epoch == this.epoch.get())
                                initialization.run();
                        } catch (RuntimeException ex) {
                            if (epoch == this.epoch.get()) failed = true;
                            log.warn(msg("startup-error"), ex);
                        }
                    });
        } catch (RuntimeException ex) {
            failed = true;
            log.warn(msg("schedule-failed"), ex);
        }
    }

    TrackingIdentity resolveCurrentIdentity() {
        if (config == null) return null;
        String profile = config.getRSProfileKey();
        long accountHash = TrackingIdentity.ACCOUNT_HASH_INVALID;
        if (client != null) {
            try {
                accountHash = client.getAccountHash();
            } catch (RuntimeException ex) {
                // Client may not expose hash before login.
            }
        }
        if (!blank(profile))
            return new TrackingIdentity(profile.trim(), accountHash);
        return null;
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
        long request = this.request.incrementAndGet();
        fence(nextHash(), request);
        synchronized (this) {
            if (closing) return;
            // Capture the old owner's latest state before the worker can enter
            // its disk wait. A pending earlier lifecycle operation has already
            // made the same capture at its boundary.
            if (captureOnCaller && !loading && activeIdentity != null)
                scheduleSave();
            loading = true;
            readySignal.set(false);
        }
        var executor = executor();
        if (executor == null) {
            finishRequest(request, false);
            return;
        }
        try {
            executor.execute(() -> {
                        boolean ready = false;
                        try {
                            if (!captureOnCaller) {
                                synchronized (this) {
                                    if (!closing && activeIdentity != null)
                                        scheduleSaveLocked(false);
                                }
                            }
                            if (!closing) ready = job.getAsBoolean();
                        } catch (RuntimeException ex) {
                            log.warn(msg("unable-complete") + what, ex);
                        }
                        finishRequest(request, ready);
                    });
        } catch (RuntimeException ex) {
            log.warn(msg("save-schedule-error") + what, ex);
            finishRequest(request, false);
        }
    }

    ExecutorService executor() {
        synchronized (lock) {
            if (closing) return null;
            if (worker == null || worker.isShutdown())
                worker = OrderedPersistenceWriter.daemonExecutor(msg("lifecycle-thread"));
            return worker;
        }
    }

    void finishRequest(long request, boolean ready) {
        if (closing && needsSnapshot) finalizeShutdown();
        if (request != this.request.get()) return;
        loading = false;
        if (ready && !closing) readySignal.set(true);
    }

    /** True once for the client tick that may resume ingestion after an async bind. */
    boolean consumeReady() {
        return readySignal.compareAndSet(true, false);
    }

    synchronized boolean switchIdentity(TrackingIdentity next, boolean capturePrevious) {
        if (next == null) return activeIdentity != null;
        if (next.equals(activeIdentity)) {
            switchBlocked = false;
            return true;
        }
        if (capturePrevious) fence(next.accountHash, request.incrementAndGet());
        if (closing && !capturePrevious) return false;
        if (activeIdentity != null) {
            if (capturePrevious) scheduleSave();
            // The previous owner stays bound until its latest snapshot is durable.
            if (!flush(Duration.ofSeconds(5))) {
                log.warn(msg("refusing-account-switch"));
                switchBlocked = true;
                return false;
            }
        }
        var previous = activeIdentity;
        long previousAppliedRevision = writer.appliedRevision(), previousNextRevision = this.next;
        activeIdentity = next;
        try {
            repository.bindIdentity(next);
            var loaded = loadScope();
            if (closing && !capturePrevious) {
                rollBack(previous, previousAppliedRevision, previousNextRevision);
                return false;
            }
            writer.resetRevision(loaded.revision);
            this.next = max(loaded.revision + 1, 1);
            engine.restoreProfile(next.rsProfileKey, loaded, now());
        } catch (RuntimeException ex) {
            rollBack(previous, previousAppliedRevision, previousNextRevision);
            switchBlocked = true;
            log.warn(msg("switch-profile-conflict"), ex);
            return false;
        }
        fence(next.accountHash, request.get());
        switchBlocked = false;
        if (previous == null || !previous.equals(next)) engine.beginPriming();
        return true;
    }

    /** A refused switch leaves the previous account bound exactly as it was. */
    void rollBack(TrackingIdentity previous, long appliedRevision, long previousNextRevision) {
        if (previous == null) repository.unbind();
        else repository.bindIdentity(previous);
        activeIdentity = previous;
        writer.resetRevision(appliedRevision);
        next = previousNextRevision;
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
            runLifecycle(false, "logout", () -> false);
            return;
        }
        fence();
        synchronized (this) {
            if (!loading && activeIdentity != null) scheduleSaveLocked(false);
        }
    }

    /** Account-hash invalidation is a lifecycle event; its save/load work stays off the callback. */
    void hashInvalidatedAsync() {
        runLifecycle(false, "account clear", () -> {
                    onHashInvalidated();
                    return false;
                });
    }

    synchronized void onHashInvalidated() {
        if (activeIdentity != null) {
            scheduleSaveLocked(false);
            // An unsaved owner is kept, fenced, rather than cleared; the next bind retries its save.
            if (!flush(Duration.ofSeconds(5))) {
                log.warn(msg("account-clear"));
                return;
            }
            if (closing) return;
        }
        engine.clearIdentity();
        activeIdentity = null;
        repository.unbind();
        writer.resetRevision(0);
        next = 1;
        engine.restoreProfile(null, new SavedState(), now());
        switchBlocked = false;
    }

    boolean scheduleSave() {
        return scheduleSave(false);
    }

    /** {@code quick}: a gameplay save, which may reuse unchanged closed sessions' JSON. */
    boolean scheduleSave(boolean quick) {
        // A lifecycle worker may hold this monitor while it waits for a disk
        // flush or loads the next account scope. Gameplay ingestion is already
        // fenced at this point, so a callback request must return without
        // waiting behind that disk work; the lifecycle boundary has its own
        // explicit snapshot path.
        if (loading) return false;
        synchronized (this) {
            return !loading && scheduleSaveLocked(quick);
        }
    }

    /** Caller holds this coordinator monitor; used by lifecycle boundary snapshots. */
    boolean scheduleSaveLocked(boolean quick) {
        var intent = currentIntent(quick);
        return intent != null && writer.submit(intent);
    }

    synchronized boolean saveNow() {
        var intent = currentIntent(false);
        if (intent == null) return false;
        writer.submit(intent);
        return writer.flush(Duration.ofSeconds(5));
    }

    /** In-memory persistence health for a restrained panel warning; performs no file I/O. */
    SaveStatus saveStatus() {
        return switchBlocked ? new SaveStatus(FAILED, msg("switch-save-paused"), false, "") : writer.status();
    }

    String scopeRefusal() {
        if (activeIdentity == null || !repository.isBound() || !identityMatches())
            return msg("current-runescape");
        return writer.conflicted(repository.generation) ? writer.status().detail : reason;
    }

    /** Outcome of a reset: whether it was applied, and why not when refused. */
    static class ResetOutcome {
        @Getter boolean applied;
        @Getter String detail;

        ResetOutcome(boolean applied, String detail) {
            this.applied = applied;
            this.detail = string(detail);
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
        String scope = scopeRefusal();
        if (scope != null) return new ResetOutcome(false, scope);
        maintenance = true;
        try {
            return replaceLocked(now, backup);
        } finally {
            maintenance = false;
        }
    }

    /** Commits a fresh owner (the factory reset) after a validated safety copy, or rolls back. */
    ResetOutcome replaceLocked(long now, boolean backup) {
        if (!saveNow()) return new ResetOutcome(false, msg("current-profile"));
        SavedState protectedState = null;
        if (backup) {
            try {
                protectedState = SessionRepository.parseBackup(JsonCodec.gson(), writeBackup(now));
            } catch (IOException | RuntimeException ex) {
                return new ResetOutcome(false, msg("pre-restore") + ex.getMessage());
            }
        }
        SavedState before;
        synchronized (engine) {
            // Sidebar edits use the engine lock, but are not ingestion. Refuse a stale safety copy
            // before replacing data, with check/snapshot/mutation atomic and all file IO outside.
            if (protectedState != null && !backupContent(engine.exportState()).equals(backupContent(protectedState)))
                return new ResetOutcome(false, msg("reset-refused") + msg("reset-data-changed"));
            before = snapshot();
            fence();
            engine.resetData(now);
        }
        var result = replaceNow();
        if (!result.isCommitted()) {
            engine.restoreProfile(activeIdentity.rsProfileKey, before, now);
            String why = result.kind == ReplaceOutcome.Kind.CONFLICT ? msg("another-client-changed") + " " : "";
            // Owner 2026-10-01 (F25): the shared outcome names the operation that was refused.
            return new ResetOutcome(false, msg("reset-refused") + why + result.message);
        }
        engine.beginPriming();
        return new ResetOutcome(true, msg("fresh-general"));
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
        json.remove("savedAtEpochMillis");
        json.remove("revision");
        return json;
    }

    private Filepath writeBackupLocked(long now) throws IOException {
        repository.backup.createDirectories();
        String name = account() + "-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                                .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(now));
        var file = repository.backup.joinSegment(name + ".json");
        for (int n = 2; file.exists() && n < 1000; n++)
            // "_" sorts after ".", so a same-second copy lists as the newer one and is kept
            // (1.3.1).
            file = repository.backup.joinSegment(name + "_" + n + ".json");
        SavedState state;
        synchronized (engine) {
            state = SaveCopies.copy(engine.exportState());
        }
        String json = JsonCodec.gson().toJson(state);
        file.write(json, CREATE, TRUNCATE_EXISTING, WRITE);
        SessionRepository.parseBackup(JsonCodec.gson(), file);
        pruneBackups(10);
        return file;
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
        var all = backups();
        for (var old : all.subList(min(keep, all.size()), all.size())) old.deleteIfExists();
        return max(0, all.size() - keep);
    }

    synchronized boolean identityMatches() {
        var current = resolveCurrentIdentity();
        return current != null && current.equals(activeIdentity);
    }

    /**
     * True when the bound identity matches the client and tracking may ingest gameplay. Refused
     * switches leave the previous identity bound, so the new physical account must not feed that
     * state.
     */
    boolean isTrackingReady() {
        // Read without the monitor so a restore/reset holding it pauses ingestion instead of
        // blocking the client thread behind the disk work.
        if (maintenance || loading || failed) return false;
        if (busy) return okay;
        synchronized (this) {
            return okay = !maintenance && !loading && !failed && identityMatches()
                            && reason == null && !writer.conflicted(repository.generation);
        }
    }

    synchronized ReplaceOutcome replaceNow() {
        var intent = currentIntent(false);
        if (intent == null) return ReplaceOutcome.unchanged(msg("account-identity-bound"));
        return writer.replaceNow(intent);
    }

    synchronized boolean flush(Duration timeout) {
        return writer.flush(timeout);
    }

    void shutdown() {
        boolean lifecycleWasPending = loading;
        closing = true;
        epoch.incrementAndGet();
        fence();
        loading = false;
        readySignal.set(false);
        // If a lifecycle worker is already fencing an owner, it captured that
        // owner's state before entering its disk wait. Keep the worker alive so
        // it can take one final post-shutdown snapshot after that wait returns;
        // the lifecycle callback itself still never waits for the worker.
        if (lifecycleWasPending) {
            needsSnapshot = true;
            synchronized (lock) {
                if (worker == null || worker.isShutdown()) finalizeShutdown();
                else {
                    worker.execute(this::finalizeShutdown);
                    worker.shutdown();
                }
            }
            return;
        }
        if (repository.isBound()) scheduleSave();
        writer.shutdown(Duration.ofSeconds(5));
        synchronized (lock) {
            if (worker != null) worker.shutdown();
        }
    }

    /** Completes a deferred shutdown snapshot from the lifecycle worker without waiting on it. */
    void finalizeShutdown() {
        if (!needsSnapshot || !closed.compareAndSet(false, true)) return;
        needsSnapshot = false;
        try {
            if (repository.isBound()) scheduleSave();
        } catch (RuntimeException ex) {
            log.warn(msg("unable-capture"), ex);
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
        reason = null;
        var loaded = repository.load();
        if (!loaded.isSupportedSchema()) {
            reason = msg("local-data") + loaded.schemaVersion + msg("version-modify");
            log.warn(reason);
            return new SavedState();
        }
        return loaded;
    }

    /** Freeze the persisted graph under the engine monitor; encoding runs on the writer. */
    SavedState snapshotState(SavedState header) {
        synchronized (engine) {
            var state = SaveCopies.copy(engine.savedState());
            if (header != null) {
                state.setRevision(header.revision);
                state.setSavedAtEpochMillis(header.savedAtEpochMillis);
                if (activeIdentity != null) state.setOwnerKey(activeIdentity.rsProfileKey);
            }
            return state;
        }
    }

    /** A detached copy of the engine's persisted state, without a save header. */
    SavedState snapshot() {
        return snapshotState(null);
    }

    WriteIntent currentIntent(boolean quick) {
        if (!repository.isBound() || reason != null || writer.conflicted(repository.generation))
            return null;
        // Prefer the writer's verified lineage when a prior save is still
        // updating disk lastKnown — avoids queuing another base-N snapshot.
        long baseRevision = max(repository.revision, writer.appliedRevision());
        var header = new SavedState();
        header.setRevision(max(next, baseRevision + 1));
        next = header.revision + 1;
        header.setSavedAtEpochMillis(now());
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

    State state;
    String detail;
    boolean retry;
    String origin;

    SaveStatus(State state, String detail, boolean retryAvailable, String recoveredFrom) {
        this.state = state;
        this.detail = string(detail);
        retry = retryAvailable;
        origin = string(recoveredFrom);
    }

    static SaveStatus neverSaved() {
        return new SaveStatus(NEVER_SAVED, "", false, "");
    }

    boolean isFailure() {
        return state == FAILED || state == State.CONFLICT;
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

    static boolean isBound() {
        return gson != null;
    }

    static Gson gson() {
        Gson bound = gson;
        if (bound == null) throw new IllegalStateException(msg("jsoncodec-bind"));
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
    static final long ACCOUNT_HASH_INVALID = -1;
    String rsProfileKey;
    long accountHash;

    TrackingIdentity(String profile, long accountHash) {
        if (blank(profile)) throw new IllegalArgumentException("rsProfileKey");
        rsProfileKey = profile.trim();
        this.accountHash = accountHash;
    }

    /** Filesystem-safe directory name derived from the RS profile key. */
    String folderName() {
        var out = new StringBuilder(rsProfileKey.length());
        for (int i = 0; i < rsProfileKey.length(); i++) {
            char c = rsProfileKey.charAt(i);
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_'
                    || c == '.')
                out.append(c); else
                out.append('_');
        }
        String name = out.toString();
        return name.isEmpty() ? "profile" : name;
    }

    public String toString() {
        return msg("profile-key") + (accountHash != ACCOUNT_HASH_INVALID) + "}";
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
    TrackingIdentity identity;
    long generation, base;
    SavedState state;
    String json;

    WriteIntent(TrackingIdentity identity, long scopeGeneration, long expectedBaseRevision, SavedState state,
            String json) {
        if (state == null) throw new IllegalArgumentException("state");
        this.identity = identity;
        generation = scopeGeneration;
        base = positive(expectedBaseRevision);
        this.state = state;
        this.json = json;
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

    Session generalSession, customSession;
    List<Session> history = new ArrayList<>();

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
        final String id;
        final Session session;
        final List<MarketRows.Row> market;
        transient MoneySnapshot view;

        MoneySnapshot view() {
            if (view == null) view = new MoneySnapshot(this);
            return view;
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
    PendingDeath pendingDeathReclaim;
    RaidHistory.State raidHistory;

    SavedState() {}

    /**
     * Revalidates optional schema-105 action-label presentation metadata after a load. Malformed,
     * oversized or non-compatible values degrade to null; a malformed label never fails the load,
     * never backfills and never infers a spell name from rune recipes.
     */
    List<SavedGrind> savedGrinds() {
        if (savedGrinds == null) savedGrinds = new ArrayList<>();
        return savedGrinds;
    }

    void setSavedGrinds(List<SavedGrind> values) {
        savedGrinds = copiedList(values);
    }

    List<GeOffer> geCustody() {
        if (geCustody == null) geCustody = new ArrayList<>();
        return geCustody;
    }

    void setGeCustody(List<GeOffer> values) {
        geCustody = new ArrayList<>();
        if (values != null) {
            for (var value : values) {
                if (value != null && !value.getOfferId().isEmpty()) geCustody.add(value);
            }
        }
    }

    TrackedBasisState trackedBasis() {
        if (trackedBasis == null) trackedBasis = new TrackedBasisState();
        return trackedBasis;
    }

    /**
     * One reusable My Grind definition: setup metadata and default targets only. It never owns
     * transactions, Net, Recent Loot, Active-Time progress or correction state; those stay in the
     * canonical Session history linked through the stable {@code grindId}.
     */
    @Setter
    static class SavedGrind extends SaveCopies {
        String grindId, name;
        Long netTargetGp, activeTimeTargetMillis;
        boolean favorite, archived;

        SavedGrind() {}

        SavedGrind(String id, String name, Long profit, Long active, boolean favorite) {
            grindId = id;
            this.name = name;
            netTargetGp = profit;
            activeTimeTargetMillis = active;
            this.favorite = favorite;
        }

        String id() {
            return string(grindId);
        }

        String name() {
            return string(name);
        }

        Long targetGp() {
            return netTargetGp != null && netTargetGp > 0 ? netTargetGp : null;
        }

        Long timeTargetMillis() {
            return activeTimeTargetMillis != null && activeTimeTargetMillis > 0 ? activeTimeTargetMillis : null;
        }
    }

    SavedState(Session generalSession, Session customSession, List<Session> history) {
        savedAtEpochMillis = now();
        this.generalSession = generalSession;
        this.customSession = customSession;
        this.history = copiedList(history);
    }

    /** True only for the current schema; a newer one is preserved read-only and never rewritten. */
    boolean isSupportedSchema() {
        return schemaVersion == CURRENT_SCHEMA_VERSION;
    }

    /** The owner that was tracking when this state was saved. */
    Session currentSession() {
        return customSession != null ? customSession : generalSession;
    }

    List<Session> history() {
        if (history == null) history = new ArrayList<>();
        return history;
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
        List<DeathItem> outstandingItems, heldAtDeath;
        int wipeTicksRemaining, gravestoneAgeTicks;

        PendingDeath() {}

        PendingDeath(List<DeathItem> outstandingItems, List<DeathItem> heldAtDeath, int wipeTicksRemaining,
                int gravestoneAgeTicks) {
            this.outstandingItems = copyItems(outstandingItems);
            this.heldAtDeath = copyItems(heldAtDeath);
            this.wipeTicksRemaining = max(0, wipeTicksRemaining);
            this.gravestoneAgeTicks = max(0, gravestoneAgeTicks);
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
            if (empty(values)) return null;
            var copy = new ArrayList<DeathItem>();
            for (var value : values) {
                if (value != null && value.isValid()) copy.add(value);
            }
            return copy.isEmpty() ? null : copy;
        }
    }

    /** One persisted item identity and quantity for {@link PendingDeath}. */
    static class DeathItem extends SaveCopies {
        int itemId;
        long quantity;

        DeathItem() {}

        DeathItem(int id, long quantity) {
            itemId = id;
            this.quantity = positive(quantity);
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
        String claimId;
        int itemOrKeyId;
        long quantity, createdAtEpochMillis;

        PendingClaim() {}

        PendingClaim(String id, int itemOrKeyId, long quantity, long createdAtEpochMillis) {
            claimId = id;
            this.itemOrKeyId = itemOrKeyId;
            this.quantity = positive(quantity);
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
    static final String ACCOUNTS_DIRECTORY = "profiles";

    static final String UNASSIGNED_MARKER = "unbound";
    Gson gson;
    Filepath root;

    /** Profile backups, one folder, newest 10 per account (owner 2026-09-28). */
    Filepath backup;

    boolean accounts;

    /** The bound scope's save files. */
    ScopeFiles bound;

    boolean initialized;
    TrackingIdentity owner;
    long revision, generation;

    @Inject
    SessionRepository(Gson codec) {
        gson = codec;
        accounts = true;
    }

    SessionRepository(Gson codec, Filepath dataDirectory, boolean accountAware) {
        gson = codec;
        accounts = accountAware;
        initialize(dataDirectory);
    }

    synchronized void initialize(Filepath pluginDirectory) {
        if (pluginDirectory == null) throw new IllegalArgumentException("pluginDirectory");
        Filepath rooted = pluginDirectory.rooted();
        if (initialized) {
            if (!root.equals(rooted))
                throw new IllegalStateException(msg("gp-manager-repository"));
            return;
        }
        root = rooted;
        backup = rooted.joinSegment("backups");
        initialized = true;
        if (accounts)
            // Production starts unbound: do not write shared root history until
            // an RS profile identity is available and explicitly bound.
            bindScope(root.joinSegment(UNASSIGNED_MARKER), null); else
            // Single-scope / test mode: the provided directory is the store.
            bindScope(rooted, null);
    }

    void requireInit() {
        if (!initialized) throw new IllegalStateException(msg("repository-ready"));
    }

    /** One scope directory's primary, backup, next and write-lock files. */
    static class ScopeFiles {
        Filepath dir, state, backup, next, lock;

        ScopeFiles(Filepath dir) {
            this.dir = dir;
            state = dir.joinSegment("sessions.json");
            backup = dir.joinSegment(msg("backup-suffix"));
            next = dir.joinSegment("sessions.next.json");
            lock = dir.joinSegment("sessions.write.lock");
        }
    }

    void bindScope(Filepath directory, TrackingIdentity identity) {
        bound = new ScopeFiles(directory);
        owner = identity;
        revision = 0;
        generation++;
    }

    /** Directory for an account identity without changing the live bind. */
    Filepath scopeDir(TrackingIdentity identity) {
        requireInit();
        if (identity == null)
            return accounts ? root.joinSegment(UNASSIGNED_MARKER) : root;
        return root.joinSegment(ACCOUNTS_DIRECTORY).joinSegment(identity.folderName());
    }

    /**
     * The backup file the last {@link #load()} had to fall back to; empty when the primary loaded.
     */
    volatile String recovery = "";

    /** The folder holding the bound profile's save files. */
    Filepath dataDir() {
        requireInit();
        return bound.dir;
    }

    boolean isBound() {
        return !accounts || owner != null;
    }

    /** Bind writes/loads to an account scope. Does not migrate unassigned data. */
    synchronized void bindIdentity(TrackingIdentity identity) {
        requireInit();
        if (identity == null) throw new IllegalArgumentException("identity");
        var accountDir = root.joinSegment(ACCOUNTS_DIRECTORY).joinSegment(identity.folderName());
        bindScope(accountDir, identity);
    }

    /** Leave any account scope; subsequent saves refuse until rebound. */
    synchronized void unbind() {
        requireInit();
        bindScope(root.joinSegment(UNASSIGNED_MARKER), null);
    }

    synchronized SavedState load() {
        requireInit();
        recovery = "";
        // A save from an older schema starts fresh: it is kept beside the profile, never read.
        retireOlder(bound);
        if (bound.state.exists()) {
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
                revision = positive(state.revision);
                return state;
            } catch (IOException ex) {
                log.warn(msg("unable-load"), ex);
                // An unavailable primary is not evidence that its older backup is current.
                if (!(ex instanceof InvalidSaveException)) throw new UncheckedIOException(ex);
                setAside(bound);
            }
        }
        if (bound.backup.exists()) {
            try {
                var i = readState(bound.backup);
                log.warn(msg("recovered-gp"), bound.backup.getFileName());
                revision = positive(i.revision);
                recovery = bound.backup.getFileName().toString();
                return i;
            } catch (IOException j) {
                log.warn(msg("unable-read"), bound.backup, j);
                if (!(j instanceof InvalidSaveException)) throw new UncheckedIOException(j);
            }
        }
        revision = 0;
        return new SavedState();
    }

    SavedState readState(Filepath path) throws IOException {
        try (Reader reader = path.openReader(READ)) {
            return parseState(gson, new JsonParser().parse(reader));
        } catch (RuntimeException ex) {
            if (ex instanceof JsonIOException && ex.getCause() instanceof IOException)
                throw (IOException) ex.getCause();
            throw new InvalidSaveException(msg("unreadable-session"), ex);
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
        if (json == null || !json.isJsonObject()) throw new InvalidSaveException(msg("empty-session"), null);
        var state = codec.fromJson(json, SavedState.class);
        if (state == null) throw new InvalidSaveException(msg("empty-session"), null);
        return state;
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
        if (intent == null || intent.state == null)
            return ReplaceOutcome.unchanged("state was null");
        return commit(intent, true);
    }

    /**
     * One fenced commit of {@code intent}. Returns {@code COMMITTED} once primary (and, for resets,
     * backup alignment) finished, {@code COMMITTED_PRIMARY_ONLY} when the primary was replaced but
     * a later step faulted, {@code CONFLICT} when disk no longer matches the expected base, else
     * {@code FAILED_UNCHANGED}.
     */
    ReplaceOutcome commit(WriteIntent intent, boolean reset) {
        if (intent == null || intent.state == null) throw new IllegalArgumentException("intent");
        var state = intent.state;
        var identity = intent.identity;
        if (accounts && identity == null) {
            log.warn(msg("refusing-gp"));
            return ReplaceOutcome.unchanged(msg("account-identity-write"));
        }
        var target = new ScopeFiles(scopeDir(identity));
        // Owner key is taken from the intent, never from a later live bind.
        if (identity != null) state.setOwnerKey(identity.rsProfileKey);
        state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        if (state.savedAtEpochMillis <= 0) state.setSavedAtEpochMillis(now());
        FileLock lock = null;
        // Set once the primary replacement has been attempted; a fault after that
        // point is classified by inspecting the primary under the still-held lock.
        boolean primaryAttempted = false;
        try {
            target.dir.createDirectories();
            lock = lockForWrite(target.lock);
            if (lock == null) {
                log.warn(msg("another-gp"));
                return ReplaceOutcome.unchanged(msg("another-client-holds"));
            }
            retireOlder(target);
            // The disk header is authoritative: an unreadable file is a retryable refusal, never
            // absence that would skip the schema guard or fall back to the older backup revision.
            var disk = header(target.state);
            int diskSchema = disk == null ? SavedState.CURRENT_SCHEMA_VERSION : schemaOf(disk);
            if (diskSchema != SavedState.CURRENT_SCHEMA_VERSION) {
                // Any other schema (a newer version) is preserved read-only.
                log.warn(msg("refusing-overwrite"), diskSchema);
                return ReplaceOutcome.unchanged(msg("disk-state") + diskSchema);
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
                log.warn(msg("refusing-concurrent"), reset ? "reset" : "save", revision, intent.base);
                if (sameScope(identity)) this.revision = revision;
                return ReplaceOutcome.conflict(msg("disk-revision"));
            }
            if (state.revision <= 0)
                state.setRevision(revision + 1); else if (state.revision <= revision) {
                log.warn(msg("refusing-non"), state.revision, revision);
                return ReplaceOutcome.unchanged(
                        msg("advance-disk"));
            }
            try (Writer writer = target.next.openWriter(CREATE, TRUNCATE_EXISTING,
                            WRITE)) {
                if (intent.json == null) gson.toJson(state, writer);
                else writer.write(intent.json);
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
            } else if (target.state.exists()) {
                preserve(target, null);
            }
            // Both guards above used this parsed primary, read under the account lock.
            if (!reset && previous != null) replaceBackup(target, msg("backup-prefix"));
            primaryAttempted = true;
            // One atomic move: a crash leaves either the old save or the new one, never neither.
            replaceFile(target.next, target.state);
            if (sameScope(identity)) this.revision = validated.revision;
            if (reset) {
                // After a reset the crash copy must not resurrect the cleared data.
                try {
                    replaceBackup(target, msg("backup-reset"));
                } catch (IOException backupEx) {
                    invalidateBackup(target, msg("reset-flush-error"));
                    log.warn(msg("reset-backup-error"), backupEx);
                    return ReplaceOutcome.primaryOnly(msg("primary-reset"));
                }
                return ReplaceOutcome.committed(msg("reset-committed"));
            }
            return ReplaceOutcome.committed("Save committed");
        } catch (IOException ex) {
            log.warn(reset ? msg("unable-replace") : msg("unable-save"), ex);
            // Still under the account lock, so a primary holding our revision can
            // only be ours: classify, never re-issue the write.
            long verified = -1;
            try {
                verified = revisionOf(header(target.state));
            } catch (IOException unavailablePrimary) {
                // The replacement cannot be verified during I/O; stay unchanged and retry.
            }
            if (primaryAttempted && verified == state.revision) {
                if (sameScope(identity)) revision = state.revision;
                return ReplaceOutcome.primaryOnly(msg("primary-already"));
            }
            return ReplaceOutcome.unchanged(reset ? msg("reset-did") : msg("save-did"));
        } finally {
            releaseLock(lock);
        }
    }

    boolean sameScope(TrackingIdentity target) {
        if (owner == null && target == null) return true;
        return owner != null && owner.equals(target);
    }

    @AllArgsConstructor
    static class ReplaceOutcome {
        enum Kind {
            COMMITTED, COMMITTED_PRIMARY_ONLY,
            /** Disk advanced past the expected base; nothing was written. */
            CONFLICT, FAILED_UNCHANGED
        }

        Kind kind;
        String message;

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
            var tombstone = files.dir.joinSegment(msg("backup-invalidated") + now() + ".json");
            files.backup.moveTo(tombstone, REPLACE_EXISTING);
            log.warn("{} ({})", reason, tombstone.getFileName());
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
        var value = object.get("schemaVersion");
        return value != null && value.isJsonPrimitive() ? value.getAsInt() : 0;
    }

    /**
     * Sets aside a primary or backup from an older schema, so the profile starts fresh. An
     * unreadable header or a failed move is never a destructive action.
     */
    void retireOlder(ScopeFiles files) {
        for (var path : new Filepath[] {files.state, files.backup}) {
            try {
                var object = header(path);
                int schema = object == null ? SavedState.CURRENT_SCHEMA_VERSION : schemaOf(object);
                if (schema >= SavedState.CURRENT_SCHEMA_VERSION) continue;
                String name = path.getFileName().toString().replace(".json", "");
                path.moveTo(files.dir.joinSegment(name + ".schema" + schema + "-" + now() + ".json"),
                        REPLACE_EXISTING);
                log.warn(msg("older-save"), schema);
            } catch (IOException ex) {
                log.warn(msg("unable-preserve"), ex);
            }
        }
    }

    /** A payload's stored revision; 0 when it names none or is null. */
    static long revisionOf(JsonObject object) {
        if (object == null) return 0;
        var value = object.get("revision");
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
            var text = new StringBuilder();
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) >= 0) text.append(buffer, 0, count);
            return text.toString();
        }
    }

    void atomicMove(Filepath source, Filepath target) throws IOException {
        source.moveTo(target, REPLACE_EXISTING, ATOMIC_MOVE);
    }

    FileLock lockForWrite(Filepath lockPath) throws IOException {
        lockPath.getParent().createDirectories();
        var channel = lockPath.openFileChannel(CREATE, WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException heldInThisProcess) {
            // Another repository instance in this JVM (or a test) holds the account lock.
            lock = null;
        }
        if (lock == null) {
            channel.close();
            return null;
        }
        return lock;
    }

    void releaseLock(FileLock lock) {
        if (lock == null) return;
        try {
            var channel = lock.channel();
            lock.release();
            channel.close();
        } catch (IOException ex) {
            log.debug(msg("unable-release"), ex);
        }
    }

    /**
     * Sets a damaged or unreadable primary aside as crash evidence, then refuses the whole save
     * when the preservation itself failed, so no payload is ever replaced unrecovered.
     */
    private void preserve(ScopeFiles files, IOException cause) throws IOException {
        setAside(files);
        if (files.state.exists()) throw new IOException(msg("cannot-preserve"), cause);
    }

    void setAside(ScopeFiles files) {
        try {
            if (files.state.exists()) {
                var backup = files.dir.joinSegment("sessions.corrupt-" + now() + ".json");
                files.state.moveTo(backup, REPLACE_EXISTING);
            }
        } catch (IOException backupError) {
            log.warn(msg("unable-preserve"), backupError);
        }
    }

    /** Atomic backup replacement, using each operation's original temporary file name. */
    void replaceBackup(ScopeFiles files, String tempName) throws IOException {
        var temp = files.dir.joinSegment(tempName);
        files.state.copyTo(temp, REPLACE_EXISTING);
        replaceFile(temp, files.backup);
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
    static final Duration DEFAULT_SHUTDOWN_FLUSH = Duration.ofSeconds(5);
    SessionRepository repository;
    Object lock = new Object();
    ExecutorService executor;

    /** Ordinary saves that failed in a row; the warning waits for the third. */
    int failed;

    boolean external;
    AtomicLong written = new AtomicLong(0);

    /** Disk truth diverged from this scope's in-memory state; retries must never rebase it. */
    AtomicLong conflict = new AtomicLong(-1);

    AtomicReference<PendingWrite> merged = new AtomicReference<>();
    AtomicReference<SaveStatus> status = new AtomicReference<>(SaveStatus.neverSaved());
    AtomicBoolean scheduled = new AtomicBoolean(false);
    Object monitor = new Object();
    volatile boolean accept = true;
    volatile boolean retryQueued;

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
        this.repository = repository;
        external = false;
        executor = daemonExecutor("gp-manager-persist");
    }

    /** Visible for tests that supply a same-thread or controlled executor. */
    OrderedPersistenceWriter(SessionRepository repository, ExecutorService executor) {
        this.repository = repository;
        external = true;
        this.executor = executor;
    }

    /** Reopens the writer; a write kept after its attempts gets another bounded cycle. */
    void start() {
        accept = true;
        stuck = null;
        var work = merged.get();
        if (work != null) work.attempts = 0;
        scheduleDrain();
    }

    /** One named daemon worker thread; persistence never keeps the client alive. */
    static ScheduledExecutorService daemonExecutor(String name) {
        return new ScheduledThreadPoolExecutor(1, runnable -> {
                    var thread = new Thread(runnable, name);
                    thread.setDaemon(true);
                    return thread;
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
        if (status.get().state == State.CONFLICT && !conflicted(repository.generation))
            status.set(SaveStatus.neverSaved());
    }

    boolean conflicted(long generation) {
        return conflict.get() == generation;
    }

    /** Enqueue an ordinary save bound to an immutable write intent. */
    boolean submit(WriteIntent intent) {
        if (intent == null || intent.state == null) return false;
        var detached = intent.state;
        if (detached.revision <= 0) detached.setRevision(intent.base + 1);
        // Asked for even when refused below, so a flush can never report it saved.
        var next = new PendingWrite(intent);
        asked = new long[] {intent.generation, next.revision};
        if (conflicted(intent.generation)) return false;
        if (!accept) {
            status.set(new SaveStatus(FAILED, "Saving has stopped. Restart RuneLite to try again.", true, ""));
            return false;
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
                    status.set(new SaveStatus(FAILED, msg("pending-save"), false, ""));
                    return false;
                }
            }
            if (merged.compareAndSet(current, next)) break;
        }
        // The newer snapshot supersedes a write whose attempts ran out; nothing else keeps it.
        stuck = null;
        return scheduleDrain();
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
            detached.setRevision(max(intent.base, written.get()) + 1);
            detached.setSavedAtEpochMillis(now());
        }
        var result = repository.replaceStateDetailed(intent);
        if (result.isCommitted()) {
            publish(detached.revision, result.message, "");
            return result;
        }
        boolean conflict = result.kind == ReplaceOutcome.Kind.CONFLICT;
        if (conflict) this.conflict.accumulateAndGet(intent.generation, Math::max);
        status.set(new SaveStatus(conflict ? State.CONFLICT : FAILED,
                        conflict ? result.message + msg("tracking-paused-back") : result.message, false, ""));
        return result;
    }

    /**
     * Waits for the newest write asked for so far, and is true only when it, or a newer snapshot
     * of the same scope generation, committed. An empty queue alone proves nothing.
     */
    boolean flush(Duration timeout) {
        var wait = timeout == null ? DEFAULT_SHUTDOWN_FLUSH : timeout;
        var want = asked;
        scheduleDrain();
        long deadline = System.nanoTime() + wait.toNanos();
        synchronized (monitor) {
            while (pending()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try {
                    monitor.wait(min(TimeUnit.NANOSECONDS.toMillis(remaining) + 1, 250));
                } catch (InterruptedException ex) {
                    return false;
                }
            }
        }
        var ok = done;
        return want == null || ok != null && ok[0] == want[0] && ok[1] >= want[1];
    }

    /** Work is queued or running; a write whose attempts ran out waits for newer work. */
    boolean pending() {
        var work = merged.get();
        return scheduled.get() || work != null && work != stuck;
    }

    void shutdown(Duration timeout) {
        until = System.nanoTime() + timeout.toNanos();
        accept = false;
        // Plugin lifecycle callbacks must return without waiting for disk work.
        // The single writer is allowed to drain the already captured intent in
        // order; a later enable creates a fresh executor if this one has
        // terminated.  No new intent can be accepted after this fence.
        scheduleDrain();
        if (!external && executor != null) closeLater((ScheduledExecutorService) executor);
    }

    void closeLater(ScheduledExecutorService target) {
        if (target.isShutdown()) return;
        if (pending()) target.schedule(() -> closeLater(target), 50, TimeUnit.MILLISECONDS);
        else target.shutdown();
    }

    boolean scheduleDrain() {
        if (retryQueued) return true;
        if (!scheduled.compareAndSet(false, true)) return true;
        ExecutorService active;
        synchronized (lock) {
            if (!external && (executor == null || executor.isShutdown())) {
                if (!accept) {
                    scheduled.set(false);
                    return false;
                }
                executor = daemonExecutor("gp-manager-persist");
            }
            active = executor;
        }
        try {
            active.execute(this::drain);
            return true;
        } catch (RuntimeException ex) {
            scheduled.set(false);
            log.warn(msg("lifecycle-schedule-error"), ex);
            status.set(new SaveStatus(FAILED, msg("persistence-executor"), true, ""));
            return false;
        }
    }

    void drain() {
        try {
            while (true) {
                // The write stays queued until it commits, is superseded or meets a conflict.
                var work = merged.get();
                if (work == null || work == stuck) return;
                if (conflicted(work.intent.generation)) {
                    merged.compareAndSet(work, null);
                    continue;
                }
                if (work.attempts > 0 && !accept && System.nanoTime() - until >= 0) {
                    stuck = work;
                    return;
                }
                int attempt = ++work.attempts;
                WriteIntent intent = rebase(work.intent);
                boolean ok = false;
                try {
                    ok = repository.save(intent);
                } catch (RuntimeException ex) {
                    log.warn(msg("gp-manager-background"), ex);
                }
                // Only the bound scope's disk revision can prove a competing writer.
                long disk = intent.generation == repository.generation ? repository.revision : intent.base;
                if (ok) {
                    done = new long[] {work.intent.generation, work.revision};
                    merged.compareAndSet(work, null);
                    // Late completions for an abandoned scope must not rewrite
                    // the live applied revision or OK status for the new scope.
                    failed = 0;
                    if (intent.generation == repository.generation)
                        publish(work.revision, "", repository.recovery); else
                        log.debug(msg("ignoring-late"), intent.generation, repository.generation);
                    continue;
                }
                State state = FAILED;
                String detail = msg("save-failed");
                if (disk != intent.base) {
                    state = State.CONFLICT;
                    conflict.accumulateAndGet(intent.generation, Math::max);
                    detail = msg("disk-revision") + msg("tracking-paused-back");
                    merged.compareAndSet(work, null);
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
            scheduled.set(false);
            if (pending() && accept && !retryQueued) scheduleDrain();
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
                retryQueued = false;
                if (scheduled.compareAndSet(false, true)) drain();
            }, millis, TimeUnit.MILLISECONDS);
            return true;
        } catch (RuntimeException ex) {
            retryQueued = false;
            return false;
        }
    }

    /** Publish a successful write only after the caller's existing scope/generation guard. */
    void publish(long revision, String message, String detail) {
        written.set(revision);
        status.set(new SaveStatus(OK, message, false, detail));
    }

    /**
     * When this writer already committed revision {@code N} and disk still shows {@code N}, a newer
     * queued snapshot that captured base {@code < N} while that save was in flight is part of our
     * own lineage — rebase its expected base. If disk diverged from our applied revision, leave the
     * intent alone so CAS surfaces a real external conflict.
     */
    WriteIntent rebase(WriteIntent intent) {
        if (intent == null) return null;
        long own = written.get();
        if (own <= 0 || intent.base >= own) return intent;
        if (intent.generation != repository.generation) return intent;
        long disk = repository.revision;
        if (disk != own) return intent;
        return new WriteIntent(intent.identity, intent.generation, own, intent.state, intent.json);
    }

    static class PendingWrite {
        long revision;
        int attempts;
        WriteIntent intent;

        PendingWrite(WriteIntent intent) {
            this.intent = intent;
            revision = intent.state.revision;
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
        T result = shallow(source);
        if (result != null) fields.accept(result);
        return result;
    }

    static <T> List<T> list(List<T> source, Function<T, T> copy) {
        if (source == null) return null;
        var result = new ArrayList<T>(source.size());
        for (T item : source) result.add(copy.apply(item));
        return result;
    }

    static SavedState copy(SavedState source) {
        return copy(source, result -> {
            result.generalSession = copy(result.generalSession);
            result.customSession = copy(result.customSession);
            result.history = list(result.history, SaveCopies::copy);
            result.savedGrinds = list(result.savedGrinds, SaveCopies::copy);
            result.savedSessions = list(result.savedSessions, SaveCopies::copy);
            result.geCustody = list(result.geCustody, SaveCopies::copy);
            result.trackedBasis = copy(result.trackedBasis);
            result.lootingBag = result.lootingBag == null ? null : new LinkedHashMap<>(result.lootingBag);
            result.pendingClaims = list(result.pendingClaims, SaveCopies::copy);
            result.pendingDeathReclaim = copy(result.pendingDeathReclaim);
            result.raidHistory = result.raidHistory == null ? null : RaidHistory.copy(result.raidHistory);
        });
    }

    static Session copy(Session source) {
        return copy(source, result -> {
            result.changeListener = null;
            result.ownedValueObserver = null;
            result.saveStamp = 0;
            result.transactions = list(result.transactions, SaveCopies::copy);
            result.pk = copy(result.pk);
            result.compactedRaids = RaidHistory.copyMoney(result.compactedRaids);
            result.correctionHistory = list(result.correctionHistory, SaveCopies::copy);
            result.undoHistory = list(result.undoHistory, SaveCopies::copy);
        });
    }

    static Transaction copy(Transaction source) {
        return copy(source, result -> {
            result.memo = null;
            result.castItemId = 0;
            result.flows = list(result.flows, SaveCopies::copy);
        });
    }

    static PkTally copy(PkTally source) {
        return copy(source, result -> result.compactedKillNet = result.compactedKillNet == null ? null : new LinkedHashMap<>(result.compactedKillNet));
    }

    static CorrectionRecord copy(CorrectionRecord source) {
        return copy(source, result -> result.changes = list(result.changes, SaveCopies::copy));
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

    static SavedState.PendingDeath copy(SavedState.PendingDeath source) {
        return copy(source, result -> {
            result.outstandingItems = list(result.outstandingItems, SaveCopies::copy);
            result.heldAtDeath = list(result.heldAtDeath, SaveCopies::copy);
        });
    }
}
