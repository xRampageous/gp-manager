package com.gpmanager;

import static com.gpmanager.FlowFilters.*;
import static com.gpmanager.GameData.msg;
import static com.gpmanager.SafeMath.nonNeg;
import static java.util.Collections.*;
import static java.util.Locale.*;

import com.gpmanager.SavedState.*;
import java.util.*;
import lombok.*;
import net.runelite.api.gameval.ItemID;

/**
 * PvM death → reclaim lifecycle. A separate held-stack snapshot is intersected with measured
 * negative inventory flows to establish which items actually left the player. Those exact
 * quantities remain ownership-neutral until reclaimed or the gravestone timer expires.
 *
 * <p>Fees are never inferred here; the engine can book only an observed carried-coin loss. A timer
 * expiry records an informational audit event and never creates an item loss.
 *
 * <p>Pure tick-counted state; the engine owns the single instance.
 */
class DeathReclaimLifecycle {
    /** Ticks a gravestone retains items. */
    static final int AWAIT_TICKS = 1_500; // 15 minutes

    /** Keep a grave interaction armed through the usual open/confirm/claim sequence. */
    static final int MIN_RECLAIM_ARM_TICKS = 200; // 2 minutes

    /** Bound delayed death-wipe reconciliation so later ordinary losses cannot match it. */
    static final int DEATH_WIPE_WINDOW_TICKS = 100; // 1 minute

    @Getter(AccessLevel.PACKAGE)
    boolean awaitingReclaim;

    int deathWipeTicks;
    int ageTicks;
    int reclaimTicks;
    BossRetrievalCatalogue.Service reclaimService;

    /** Stacks held at death not yet matched to a measured loss; null when no wipe is pending. */
    Map<Integer, Long> carriedAtDeath;

    /** Measured grave contents still outstanding, with the names used by the expiry note. */
    Map<Integer, Long> deathItems = new HashMap<>();

    Map<Integer, String> names = new HashMap<>();

    /** Local unsafe PvM death: the snapshot is ownership evidence, not presentation evidence. */
    void onPvmDeath(Map<Integer, Long> heldItems) {
        // A second death moves outstanding items to the new gravestone. Keep their
        // quantities and restart the single gravestone timer with the new grave.
        if (!awaitingReclaim) deathItems.clear();
        awaitingReclaim = true;
        // Missing canonical evidence is not permission to neutralize arbitrary losses.
        Map<Integer, Long> held = positiveEntries(heldItems);
        carriedAtDeath = held.isEmpty() ? null : held;
        deathWipeTicks = carriedAtDeath == null ? 0 : DEATH_WIPE_WINDOW_TICKS;
        ageTicks = 0;
        reclaimTicks = 0;
        reclaimService = null;
        completeIfEmpty();
    }

    /** True while waiting for the measured death inventory/equipment removal. */
    boolean wipePending() {
        return awaitingReclaim && carriedAtDeath != null;
    }

    /** Stop the short death-wipe whitelist when stronger later evidence owns the loss. */
    void cancelDeathWipe() {
        carriedAtDeath = null;
        deathWipeTicks = 0;
        completeIfEmpty();
    }

    /** Consume death-time candidates whose later loss is explicitly action-attributed. */
    void excludeActionLosses(Map<Integer, Long> actionLosses) {
        if (carriedAtDeath == null || Phrases.empty(actionLosses)) return;
        positiveEntries(actionLosses).forEach((itemId, lost) -> take(carriedAtDeath, itemId, lost));
        endWipeIfSpent();
    }

    /**
     * Intersect a settled negative-flow batch with the stacks held at death. The returned
     * quantities identify the exact negative flow portions that may be labelled as the
     * ownership-neutral death transfer.
     */
    Map<Integer, Long> onDeathItemsRemoved(List<Flow> flows) {
        if (!wipePending()) return emptyMap();
        var removed = new HashMap<Integer, Long>();
        for (Flow flow : flows == null ? Collections.<Flow>emptyList() : flows) {
            if (flow.quantityDelta >= 0L
                    || flow.quantityDelta == Long.MIN_VALUE
                    // A live retrieval interaction makes measured coin loss a possible
                    // fee; do not consume it as a delayed death wipe.
                    || flow.itemId == ItemID.COINS && isReclaimArmed()) {
                continue;
            }
            long matched = take(carriedAtDeath, flow.itemId, -flow.quantityDelta);
            if (matched <= 0L) continue;
            removed.put(flow.itemId, matched);
            // Coins can be part of the measured death wipe transfer, but they are
            // never reclaim whitelist items: a later coin loss may be the observed fee.
            if (flow.itemId != ItemID.COINS) {
                deathItems.merge(flow.itemId, matched, SafeMath::safeAdd);
                if (flow.itemName != null && !flow.itemName.trim().isEmpty())
                    names.put(flow.itemId, flow.itemName.trim());
            }
        }
        endWipeIfSpent();
        return removed.isEmpty() ? emptyMap() : unmodifiableMap(removed);
    }

    /**
     * Match a positive return against the still-outstanding measured death quantity. Returns zero
     * when the item was not part of the actual settled death wipe.
     */
    long matchReturnedItem(int itemId, long quantity) {
        long matched = take(deathItems, itemId, quantity);
        if (matched > 0L && deathItems.isEmpty()) {
            // All measured grave contents have returned. Remaining stacks from the
            // death-time snapshot were kept and can no longer be a delayed wipe.
            cancelDeathWipe();
        }
        return matched;
    }

    long outstandingItemCount() {
        long count = 0L;
        for (long quantity : deathItems.values()) count = SafeMath.safeAdd(count, quantity);
        return count;
    }

    int ageTicks() {
        return ageTicks;
    }

    /**
     * Player interacted with a retrieval service. A fresh Grave / Gravestone click can always
     * re-arm while this local death remains active.
     *
     * @return true only when a local PvM death is awaiting reclaim
     */
    boolean noteReclaimIntent(BossRetrievalCatalogue.Service service, int ticks) {
        if (service == null || !awaitingReclaim && deathItems.isEmpty()) return false;
        reclaimService = service;
        reclaimTicks = Math.max(MIN_RECLAIM_ARM_TICKS, ticks);
        return true;
    }

    boolean isReclaimArmed() {
        return reclaimTicks > 0 && reclaimService != null;
    }

    /** One inventory/game tick; defers gravestone expiry while a snapshot is unsettled. */
    String tick(boolean deferGravestoneExpiry) {
        if (awaitingReclaim && ageTicks < Integer.MAX_VALUE) ageTicks++;
        if (reclaimTicks > 0 && --reclaimTicks == 0) reclaimService = null;
        if (deathWipeTicks > 0 && --deathWipeTicks == 0) cancelDeathWipe();
        if (deferGravestoneExpiry || !awaitingReclaim || ageTicks < AWAIT_TICKS) return null;
        String expired = deathItems.isEmpty() ? null : expiredItems();
        // Expired grave items wait at Death's Office for good: still yours, so they stay
        // outstanding
        // and match only while a retrieval is armed (review 2026-10-09).
        awaitingReclaim = false;
        cancelDeathWipe();
        return expired;
    }

    /** The expiry audit explanation: the outstanding items, sorted by name. */
    String expiredItems() {
        var labels = new ArrayList<String>();
        deathItems.forEach(
                (itemId, quantity) -> {
                    String name = names.getOrDefault(itemId, "item #" + itemId);
                    labels.add(
                            quantity == 1L
                                    ? name
                                    : name + " ×" + String.format(Locale.ROOT, "%,d", quantity));
                });
        labels.sort(String.CASE_INSENSITIVE_ORDER);
        return msg("gravestone-timer")
                + outstandingItemCount()
                + msg("item-s")
                + String.join(", ", labels)
                + msg("item-loss");
    }

    void reset() {
        awaitingReclaim = false;
        deathWipeTicks = 0;
        ageTicks = 0;
        reclaimTicks = 0;
        reclaimService = null;
        carriedAtDeath = null;
        deathItems.clear();
        names.clear();
    }

    /**
     * Persistable physical continuity for restart, or null when no death awaits reclaim. Item
     * identity and quantity only; the live reclaim interaction and presentation labels are
     * deliberately not persisted.
     */
    PendingDeath snapshot() {
        List<DeathItem> outstanding = items(deathItems);
        List<DeathItem> held = items(carriedAtDeath);
        return outstanding.isEmpty() && held.isEmpty()
                ? null
                : new PendingDeath(outstanding, held, deathWipeTicks, ageTicks);
    }

    static List<DeathItem> items(Map<Integer, Long> quantities) {
        var items = new ArrayList<DeathItem>();
        positiveEntries(quantities)
                .forEach((itemId, quantity) -> items.add(new DeathItem(itemId, quantity)));
        return items;
    }

    /**
     * Restore persisted physical continuity after a restart. The reclaimed quantities are matched
     * exactly as before; the delayed-wipe window resumes its remaining bounded ticks and the
     * gravestone timer keeps its age. A live reclaim interaction is never restored.
     */
    void restore(PendingDeath pending) {
        reset();
        if (pending == null || pending.isEmpty()) return;
        for (DeathItem item : pending.getOutstandingItems()) {
            if (item.getQuantity() > 0L)
                deathItems.merge(item.itemId, item.getQuantity(), SafeMath::safeAdd);
        }
        var held = new HashMap<Integer, Long>();
        for (DeathItem item : pending.getHeldAtDeath()) {
            held.merge(item.itemId, item.getQuantity(), SafeMath::safeAdd);
        }
        deathWipeTicks = Math.min(DEATH_WIPE_WINDOW_TICKS, pending.getWipeTicksRemaining());
        carriedAtDeath = deathWipeTicks > 0 && !held.isEmpty() ? held : null;
        deathWipeTicks = carriedAtDeath == null ? 0 : deathWipeTicks;
        ageTicks = pending.getGravestoneAgeTicks();
        awaitingReclaim = carriedAtDeath != null || !deathItems.isEmpty() && ageTicks < AWAIT_TICKS;
    }

    /** Adopt the physical continuity of a staged engine during an owner install. */
    void adoptFrom(DeathReclaimLifecycle other) {
        reset();
        if (other == null) return;
        awaitingReclaim = other.awaitingReclaim;
        deathWipeTicks = other.deathWipeTicks;
        ageTicks = other.ageTicks;
        reclaimTicks = other.reclaimTicks;
        reclaimService = other.reclaimService;
        carriedAtDeath = other.carriedAtDeath == null ? null : new HashMap<>(other.carriedAtDeath);
        deathItems.putAll(other.deathItems);
        names.putAll(other.names);
    }

    void endWipeIfSpent() {
        if (carriedAtDeath != null && carriedAtDeath.isEmpty()) cancelDeathWipe();
        completeIfEmpty();
    }

    void completeIfEmpty() {
        if (awaitingReclaim && carriedAtDeath == null && deathItems.isEmpty()) reset();
    }
}

/**
 * Read-only state for the local PvM death-reclaim indicator. The item count is the sum of measured
 * outstanding stack quantities, and age is measured in active engine game ticks.
 */
class DeathReclaimStatus {
    boolean awaiting;
    boolean armed;
    long outstandingItemCount;
    long ageTicks;

    DeathReclaimStatus(boolean awaiting, boolean armed, long outstandingItemCount, long ageTicks) {
        this.awaiting = awaiting;
        this.armed = awaiting && armed;
        this.outstandingItemCount = nonNeg(outstandingItemCount);
        this.ageTicks = nonNeg(ageTicks);
    }
}

/**
 * Item Retrieval Services and their published flat fees. Source: OSRS Wiki "Item Retrieval Service"
 * (checked 2026-10-11). The fee here is an <em>expectation used for labelling</em>; the booked cost
 * is always the coins actually observed leaving the inventory in the reclaim window. Fees paid from
 * the bank or Death's Coffer are not observable and are never invented.
 */
class BossRetrievalCatalogue {
    /** Marker for services whose fee depends on the run (ToA scales with raid level). */
    static final long VARIABLE_FEE = -1L;

    /** Marker for tiered / percentage fees (gravestone, Death's Office) — see DeathReclaimFees. */
    static final long TIERED_FEE = -2L;

    @AllArgsConstructor
    static class Service {
        String key;
        String activity;
        String interactable;
        long expectedFee;
        String note;

        /**
         * Target name shared with unrelated scenery (a bare "Chest", a gravestone): only evidence
         * when a local PvM death is still awaiting reclaim.
         */
        boolean ambiguousTarget;

        /** Ledger explanation for an observed coin payment at this service. */
        String why(long observedCoins) {
            var why =
                    new StringBuilder(msg("item-retrieval"))
                            .append(activity)
                            .append(" (")
                            .append(interactable)
                            .append(")");
            if (expectedFee > 0L) {
                why.append(", published ").append(String.format(ROOT, "%,d", expectedFee));
                if (observedCoins > 0L && observedCoins != expectedFee) {
                    why.append(", observed ").append(String.format(ROOT, "%,d", observedCoins));
                }
            } else if (expectedFee == VARIABLE_FEE) {
                why.append(", fee varies");
            }
            if (!note.isEmpty()) why.append(". ").append(note);
            return why.toString();
        }
    }

    static final Map<String, Service> BY_INTERACTABLE = new LinkedHashMap<>();

    static {
        for (String[] row : GameData.rows("retrieval_services")) {
            BY_INTERACTABLE.put(
                    row[2].toLowerCase(ROOT),
                    new Service(
                            row[0],
                            row[1],
                            row[2],
                            Long.parseLong(row[3]),
                            row[4],
                            Boolean.parseBoolean(row[5])));
        }
    }

    /**
     * Service for a menu target (NPC or object name) when the option is a retrieval verb (see
     * {@link #isRetrievalOption}). Ambiguous targets ("Chest", "Gravestone", "Death") are returned
     * too; the engine only acts on them while a local PvM death awaits reclaim.
     */

    /** Menu verbs that open or complete a reclaim. */
    /** Existing reclaim vocabulary and grave prefix compiled once from d18. */
    static final java.util.regex.Pattern RECLAIM = GameData.pattern("reclaim");

    static final java.util.regex.Pattern GRAVE = GameData.pattern("grave");

    static Service serviceFor(String option, String target) {
        if (target == null || option == null || !RECLAIM.matcher(Phrases.lower(option)).matches()) {
            return null;
        }
        String lower = Phrases.lower(target);
        if (lower.isEmpty()) return null;
        Service exact = BY_INTERACTABLE.get(lower);
        if (exact != null) return exact;
        for (Map.Entry<String, Service> entry : BY_INTERACTABLE.entrySet()) {
            if (!entry.getValue().ambiguousTarget && lower.contains(entry.getKey()))
                return entry.getValue();
        }
        if (GRAVE.matcher(lower).matches()) return BY_INTERACTABLE.get("gravestone");
        return null;
    }
}
