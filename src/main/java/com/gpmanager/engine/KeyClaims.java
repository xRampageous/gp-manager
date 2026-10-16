package com.gpmanager;

import static com.gpmanager.FlowFilters.*;
import static com.gpmanager.GameData.msg;
import static com.gpmanager.Phrases.*;
import static com.gpmanager.SafeMath.nonNeg;
import static java.lang.Math.*;
import static java.util.Collections.*;

import com.gpmanager.SavedState.PendingClaim;
import java.util.*;
import java.util.function.IntPredicate;
import javax.inject.*;
import lombok.*;
import lombok.RequiredArgsConstructor;
import net.runelite.api.*;
import net.runelite.api.gameval.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;

/**
 * Key and chest claims. The account's unresolved claims are the only durable part: a claim opens
 * when a key audit row is booked (the first key type keeps the row's id, more types derive {@code
 * id#index}), shrinks as its chest contents settle or its keys are lost on death, and is removed at
 * zero. Only a claim that reached zero writes its id onto the settling transaction as {@code
 * sourceClaimId}, so a reload that sees both drops the stale claim without booking again, while a
 * partly settled claim keeps its persisted remainder. Claims never expire on their own.
 *
 * <p>Everything else is short-lived evidence: an ordinary chest's object click (and a key loss seen
 * while it is armed), the Wilderness loot-key chest container removals, and the local-death key
 * snapshot. Chest contents never become durable provenance. Invoked only under the engine monitor.
 */
class KeyClaims {
    static final int OPEN_WINDOW_TICKS = 4;
    static final int DEATH_WINDOW_TICKS = 8;
    static final int CLAIM_EVIDENCE_TICKS = 4;
    static final int[] KEY_ITEM_IDS = {
        ItemID.WILDY_LOOT_KEY0,
        ItemID.WILDY_LOOT_KEY1,
        ItemID.WILDY_LOOT_KEY2,
        ItemID.WILDY_LOOT_KEY3,
        ItemID.WILDY_LOOT_KEY4
    };
    static final int[] KEY_CONTAINER_IDS = {
        InventoryID.DEADMAN_LOOT_INV0,
        InventoryID.DEADMAN_LOOT_INV1,
        InventoryID.DEADMAN_LOOT_INV2,
        InventoryID.DEADMAN_LOOT_INV3,
        InventoryID.DEADMAN_LOOT_INV4
    };
    static final String LOOT_KEY_NOTE = msg("loot-key");
    List<PendingClaim> claims = new ArrayList<>();

    /** The chest object click, armed for a few ticks. */
    ChestOpen open;

    /** A key loss seen while armed whose contents have not settled yet. */
    ChestOpen used;

    Map<Integer, Long> localDeathKeys;
    int localDeathTicks;
    boolean lootChestVisible;
    Map<Integer, Map<Integer, Long>> observedKeyContainerContents = new HashMap<>();

    /**
     * Contents removed from each loot key's chest container, kept for a few ticks as claim
     * evidence.
     */
    Map<Integer, Removed> removedByKey = new HashMap<>();

    /** A chest opened with one key type: the key, how many were used and the chest's name. */
    @AllArgsConstructor
    static class ChestOpen {
        int keyItemId;
        long quantity;
        String chestName;

        @Getter(AccessLevel.NONE)
        int ticks;
    }

    @AllArgsConstructor
    static class Removed {
        Map<Integer, Long> contents;
        int ticks;
    }

    // -- Durable claims --
    void clear() {
        claims.clear();
    }

    /** Restores the persisted claims and drops any already settled by a retained transaction. */
    void restore(SavedState state, List<Session> sessions) {
        claims.clear();
        var settled = new HashSet<String>();
        for (Session session : sessions) {
            for (Transaction transaction :
                    session == null
                            ? Collections.<Transaction>emptyList()
                            : session.getTransactions()) {
                if (transaction != null && !transaction.sourceClaimId().isEmpty()) {
                    settled.add(transaction.sourceClaimId());
                }
            }
        }
        var seen = new HashSet<String>();
        for (PendingClaim claim : state.getPendingClaims()) {
            if (claim != null
                    && claim.isValid()
                    && seen.add(claim.getClaimId())
                    && !settled.contains(claim.getClaimId())) {
                claims.add(claim);
            }
        }
        sort();
    }

    void adoptFrom(KeyClaims staged) {
        claims.clear();
        claims.addAll(staged.claims);
    }

    void writeTo(SavedState state) {
        state.setPendingClaims(new ArrayList<>(claims));
    }

    List<PendingClaim> all() {
        return unmodifiableList(claims);
    }

    /** Opens a claim keyed by the audit row's id; a second open with the same id is ignored. */
    void open(String claimId, int keyItemId, long quantity, long now) {
        if (empty(claimId) || keyItemId <= 0 || quantity <= 0L || indexOf(claimId) >= 0) return;
        claims.add(new PendingClaim(claimId, keyItemId, quantity, now));
        sort();
    }

    /**
     * Applies a settlement or death loss of {@code quantity} keys to the oldest claims for the key.
     * Returns the id of the last claim it touched when that claim closed; null otherwise.
     */
    String consume(int keyItemId, long quantity) {
        String closed = null;
        long remaining = nonNeg(quantity);
        for (int index = 0; index < claims.size() && remaining > 0L; index++) {
            PendingClaim claim = claims.get(index);
            if (claim.itemOrKeyId != keyItemId) continue;
            long applied = min(remaining, claim.getQuantity());
            remaining -= applied;
            closed = reduce(index, applied) ? claim.getClaimId() : null;
            index -= closed == null ? 0 : 1;
        }
        return closed;
    }

    /** Settles one specific claim by {@code quantity}; returns its id when it closed. */
    String settle(String claimId, long quantity) {
        int index = indexOf(claimId);
        boolean closed = index >= 0 && reduce(index, nonNeg(quantity));
        sort();
        return closed ? claimId : null;
    }

    /** Shrinks the claim at {@code index}; true when it reached zero and was removed. */
    boolean reduce(int index, long quantity) {
        PendingClaim claim = claims.get(index);
        long left = claim.getQuantity() - quantity;
        if (left > 0L) {
            claims.set(
                    index,
                    new PendingClaim(
                            claim.getClaimId(),
                            claim.itemOrKeyId,
                            left,
                            claim.getCreatedAtEpochMillis()));
            return false;
        }
        claims.remove(index);
        return true;
    }

    int indexOf(String claimId) {
        for (int index = 0; index < claims.size(); index++) {
            if (claims.get(index).getClaimId().equals(claimId)) return index;
        }
        return -1;
    }

    /** Created-at then claim id: equal timestamps never reorder across save and reload. */
    void sort() {
        claims.sort(
                Comparator.comparingLong(PendingClaim::getCreatedAtEpochMillis)
                        .thenComparing(PendingClaim::getClaimId));
    }

    // -- Short-lived evidence --
    /** All transient evidence goes; the durable claims stay. */
    void clearEvidence() {
        cancelChest();
        localDeathKeys = null;
        localDeathTicks = 0;
        lootChestVisible = false;
        observedKeyContainerContents.clear();
        removedByKey.clear();
    }

    void tick() {
        open = open == null || --open.ticks <= 0 ? null : open;
        used = used == null || --used.ticks <= 0 ? null : used;
        if (localDeathTicks > 0 && --localDeathTicks == 0) localDeathKeys = null;
        removedByKey.values().removeIf(removed -> --removed.ticks <= 0);
    }

    /** Arms (or disarms) ordinary key-chest provenance from an actual chest object click. */
    boolean chestClick(String option, String target) {
        KeyChestCatalogue.Entry entry = KeyChestCatalogue.entryForMenu(option, target);
        used = null;
        open =
                entry == null
                        ? null
                        : new ChestOpen(entry.keyItemId, 0L, entry.chestName, OPEN_WINDOW_TICKS);
        return open != null;
    }

    void cancelChest() {
        open = null;
        used = null;
    }

    /**
     * Correlate a settled key loss with settled positive inventory contents. A click alone is never
     * a claim, and ambiguous keys with multiple chest types require the observed object name.
     */
    ChestOpen matchChest(
            Map<Integer, Long> keyLosses,
            Map<Integer, Long> positiveContents,
            String sourceBackedActivity) {
        boolean sourced = !blank(sourceBackedActivity);
        Map<Integer, Long> losses = positiveEntries(keyLosses);
        if (empty(positiveContents)) {
            if (sourced) {
                cancelChest();
            } else if (open != null && losses.size() == 1 && losses.containsKey(open.keyItemId)) {
                // The key left before its contents: remember the use for a few ticks.
                used =
                        new ChestOpen(
                                open.keyItemId,
                                losses.get(open.keyItemId),
                                open.chestName,
                                OPEN_WINDOW_TICKS);
                open = null;
            }
            return null;
        }
        int keyId = -1;
        long quantity = 0L;
        String chestName = null;
        if (!losses.isEmpty()) {
            ChestOpen armed = open != null ? open : used;
            Map.Entry<Integer, Long> loss = losses.entrySet().iterator().next();
            if (losses.size() != 1 || armed != null && armed.keyItemId != loss.getKey()) {
                cancelChest();
                return null;
            }
            keyId = loss.getKey();
            quantity = loss.getValue();
            List<KeyChestCatalogue.Entry> entries = KeyChestCatalogue.entriesForKey(keyId);
            chestName =
                    open != null && open.keyItemId == keyId
                            ? open.chestName
                            : entries.size() == 1 ? entries.get(0).chestName : null;
            used = null;
        } else if (used != null) {
            keyId = used.keyItemId;
            quantity = used.quantity;
            chestName = used.chestName;
            used = null;
        }
        if (keyId < 0 || quantity <= 0L || chestName == null) {
            // Do not let an uncorroborated gain leave an object click armed to
            // relabel a later, unrelated key loss and contents change.
            open = null;
            return null;
        }
        KeyChestCatalogue.Entry sourceChest =
                sourced ? KeyChestCatalogue.entryForChest(sourceBackedActivity) : null;
        if (sourced
                && (sourceChest == null || !chestName.equalsIgnoreCase(sourceChest.chestName))) {
            cancelChest();
            return null;
        }
        if (open != null
                && open.keyItemId == keyId
                && open.chestName != null
                && !open.chestName.equalsIgnoreCase(chestName)) {
            return null;
        }
        cancelChest();
        return new ChestOpen(keyId, quantity, chestName, 0);
    }

    void beginLocalDeath(Map<Integer, Long> keyInventory) {
        Map<Integer, Long> keys = keyQuantities(keyInventory);
        localDeathKeys = keys.isEmpty() ? null : keys;
        localDeathTicks = localDeathKeys == null ? 0 : DEATH_WINDOW_TICKS;
    }

    boolean awaitingDeathSettle() {
        return localDeathKeys != null && localDeathTicks > 0;
    }

    void setLootChestVisible(boolean visible) {
        if (lootChestVisible && !visible) {
            // Reopening a key UI starts a fresh container-delta baseline. A
            // short, already-observed claim delta may still settle afterward.
            observedKeyContainerContents.clear();
        }
        lootChestVisible = visible;
    }

    /**
     * Observe one loot key's server-owned contents while its interface is visible. Only removals
     * from this container can correlate settled INV gains to a claim.
     */
    void observeKeyContents(int keyItemId, Map<Integer, Long> contents) {
        int keyIndex = keyIndex(keyItemId);
        if (!lootChestVisible || keyIndex < 0 || contents == null) return;
        Map<Integer, Long> next = positiveEntries(contents);
        Map<Integer, Long> removed = minus(observedKeyContainerContents.put(keyIndex, next), next);
        if (!removed.isEmpty()) {
            Removed evidence =
                    removedByKey.computeIfAbsent(keyIndex, key -> new Removed(new HashMap<>(), 0));
            removed.forEach(
                    (itemId, quantity) ->
                            evidence.contents.merge(itemId, quantity, SafeMath::safeAdd));
            evidence.ticks = CLAIM_EVIDENCE_TICKS;
        }
    }

    /** The last contents observed in one loot key's chest, or an empty map (read-only). */
    Map<Integer, Long> observedContents(int keyItemId) {
        int keyIndex = keyIndex(keyItemId);
        Map<Integer, Long> contents =
                keyIndex < 0 ? null : observedKeyContainerContents.get(keyIndex);
        return contents == null ? emptyMap() : contents;
    }

    /**
     * Settles the one single-key loot-key claim whose chest removal (seen within the evidence
     * window) covers these settled gains. One matched manifest accounts for one physical key at
     * most, so a stacked claim shrinks one unit at a time; exhausting the evidence supplies that
     * unit even when the key-loss callback arrived separately. Returns the claim id when it closed.
     * Descriptive: never alters the settling transaction's type, valuation or flows.
     */
    String settleLootKey(
            Map<Integer, Long> settledPositiveFlows, Map<Integer, Long> settledKeyLosses) {
        if (empty(settledPositiveFlows)) return null;
        PendingClaim match = null;
        int matchKeyIndex = -1;
        for (Map.Entry<Integer, Removed> evidence : removedByKey.entrySet()) {
            PendingClaim claim =
                    covers(evidence.getValue().contents, settledPositiveFlows)
                            ? uniquePending(keyItemId(evidence.getKey()))
                            : null;
            if (claim != null && claim.getQuantity() > 0L) {
                if (match != null) return null;
                match = claim;
                matchKeyIndex = evidence.getKey();
            }
        }
        if (match == null) return null;
        long consumedKeys =
                settledKeyLosses == null
                        ? 0L
                        : settledKeyLosses.getOrDefault(keyItemId(matchKeyIndex), 0L);
        Removed evidence = removedByKey.get(matchKeyIndex);
        evidence.contents = minus(evidence.contents, settledPositiveFlows);
        evidence.ticks = CLAIM_EVIDENCE_TICKS;
        if (evidence.contents.isEmpty()) removedByKey.remove(matchKeyIndex);
        return settle(
                match.getClaimId(),
                evidence.contents.isEmpty() && consumedKeys <= 0L ? 1L : consumedKeys);
    }

    /** The one unresolved claim for this key item when exactly one exists; null otherwise. */
    PendingClaim uniquePending(int keyItemId) {
        PendingClaim match = null;
        for (PendingClaim claim : claims) {
            if (claim.itemOrKeyId != keyItemId) continue;
            if (match != null) return null;
            match = claim;
        }
        return match;
    }

    /**
     * Closes pending loot-key claims only for key units that disappeared from INV in a local-death
     * settlement, measured against the death snapshot.
     */
    void recordDeathLosses(List<Flow> settledFlows, Map<Integer, Long> currentKeyInventory) {
        if (!awaitingDeathSettle()) return;
        Map<Integer, Long> flowLosses = lossQuantities(settledFlows, KeyClaims::isLootKey);
        Map<Integer, Long> gone = minus(localDeathKeys, keyQuantities(currentKeyInventory));
        for (int keyItemId : KEY_ITEM_IDS) {
            long pendingKeys = 0L;
            for (PendingClaim claim : claims)
                pendingKeys += claim.itemOrKeyId == keyItemId ? claim.getQuantity() : 0L;
            long matchedLoss =
                    min(
                            pendingKeys,
                            min(
                                    gone.getOrDefault(keyItemId, 0L),
                                    flowLosses.getOrDefault(keyItemId, 0L)));
            if (matchedLoss > 0L) {
                consume(keyItemId, matchedLoss);
                // Inventory/equipment changes can stabilize in separate batches after a death.
                // Keep the bounded snapshot until each confirmed key loss is consumed or the
                // game-tick window expires; an unrelated first batch must not make a later
                // key-loss flow look like an ordinary cost.
                take(localDeathKeys, keyItemId, matchedLoss);
            }
        }
        if (localDeathKeys.isEmpty()) {
            localDeathKeys = null;
            localDeathTicks = 0;
        }
    }

    // -- Key identity --
    static boolean isLootKey(int itemId) {
        return keyIndex(itemId) >= 0;
    }

    static int keyIndex(int itemId) {
        for (int index = 0; index < KEY_ITEM_IDS.length; index++) {
            if (KEY_ITEM_IDS[index] == itemId) return index;
        }
        return -1;
    }

    static int keyItemId(int index) {
        return index < 0 || index >= KEY_ITEM_IDS.length ? -1 : KEY_ITEM_IDS[index];
    }

    static int keyContainerId(int index) {
        return index < 0 || index >= KEY_CONTAINER_IDS.length ? -1 : KEY_CONTAINER_IDS[index];
    }

    /**
     * Positive item quantities in a container, skipping items {@code skip} rejects (nested crates).
     */
    static Map<Integer, Long> manifestFromContainer(ItemContainer container, IntPredicate skip) {
        // Refuse an incomplete evidence policy rather than accidentally valuing a
        // nested crate as if its unopened contents were already manifest items.
        var manifest = new LinkedHashMap<Integer, Long>();
        if (container == null || container.getItems() == null || skip == null) return emptyMap();
        for (Item item : container.getItems()) {
            if (item != null
                    && item.getId() >= 0
                    && item.getQuantity() > 0
                    && !skip.test(item.getId())) {
                manifest.merge(item.getId(), (long) item.getQuantity(), SafeMath::safeAdd);
            }
        }
        return unmodifiableMap(manifest);
    }

    static Map<Integer, Long> keyQuantities(ItemContainer inventory) {
        return manifestFromContainer(inventory, itemId -> !isLootKey(itemId));
    }

    static Map<Integer, Long> keyQuantities(Map<Integer, Long> quantities) {
        Map<Integer, Long> keys = positiveEntries(quantities);
        keys.keySet().removeIf(itemId -> !isLootKey(itemId));
        return keys;
    }
}

/**
 * While a clue/casket/key path is active, digs / teleports / key spends attach as costs against
 * that clue's Pending Rewards → Claimed batch. Unmatched supply spends stay normal Used.
 */
class ClueCostPairing {
    String activeClueEncounterId;
    String activeClueLabel;

    synchronized void beginClue(String encounterId, String label) {
        activeClueEncounterId = emptyToNull(encounterId);
        activeClueLabel = emptyToNull(label);
    }

    synchronized void endClue() {
        activeClueEncounterId = null;
        activeClueLabel = null;
    }

    synchronized String activeEncounter() {
        return activeClueEncounterId;
    }

    /**
     * Classify a spend note while a clue path may be open. Dig / teleport / clue key → CLUE_COST;
     * food / potions → UNRELATED_USED.
     */
    synchronized boolean isClueCost(String noteOrOption) {
        String lower = Phrases.norm(noteOrOption);
        return activeClueEncounterId != null && lower != null && looksLikeClueCost(lower);
    }

    static boolean looksLikeClueCost(String lowerNote) {
        if (Phrases.empty(lowerNote)) return false;
        return lowerNote.contains("dig")
                || lowerNote.contains("teleport")
                || lowerNote.contains("tele ")
                || lowerNote.contains("clue")
                || lowerNote.contains("casket")
                || lowerNote.contains("spade")
                || lowerNote.contains("key") && (Phrases.has(lowerNote, "clue", "chest"));
    }

    static boolean looksLikeClueActivity(String name) {
        String lower = Phrases.norm(name);
        return lower != null && (Phrases.has(lower, "clue", "casket", "treasure trail"));
    }

    synchronized String costNote() {
        String label = activeClueLabel == null ? "clue" : activeClueLabel;
        return "Clue cost paired to " + label;
    }

    synchronized void clear() {
        endClue();
    }

    static String emptyToNull(String value) {
        if (Phrases.blank(value)) return null;
        return value.trim();
    }
}

/**
 * Wiki-sourced key/chest pairs used for deferred-claim valuation and provenance. Chest names and
 * locations follow the OSRS Wiki pages for the <a
 * href="https://oldschool.runescape.wiki/w/Crystal_chest">Crystal chest</a>, <a
 * href="https://oldschool.runescape.wiki/w/Elf_crystal_chest">Elven Crystal Chest</a>, <a
 * href="https://oldschool.runescape.wiki/w/Larran%27s_small_chest">Larran's small chest</a>, <a
 * href="https://oldschool.runescape.wiki/w/Larran%27s_big_chest">Larran's big chest</a>, <a
 * href="https://oldschool.runescape.wiki/w/Ogre_Coffin">Ogre coffin</a>, <a
 * href="https://oldschool.runescape.wiki/w/Muddy_chest">Muddy chest</a>, <a
 * href="https://oldschool.runescape.wiki/w/Sinister_chest">Sinister chest</a>, <a
 * href="https://oldschool.runescape.wiki/w/Grubby_chest">Grubby chest</a>, <a
 * href="https://oldschool.runescape.wiki/w/Brimstone_chest">Brimstone chest</a>, <a
 * href="https://oldschool.runescape.wiki/w/Moons_rewards">Moon chest</a>, and <a
 * href="https://oldschool.runescape.wiki/w/Category%3AShades_of_Mort%27ton_%28minigame%29">Shades
 * of Mort'ton chest/key variants</a> (for example, <a
 * href="https://oldschool.runescape.wiki/w/Steel_Chest_%28brown%29">Steel Chest (brown)</a>). Key
 * names/tradeability follow the item pages for <a
 * href="https://oldschool.runescape.wiki/w/Crystal_key">Crystal key</a>, <a
 * href="https://oldschool.runescape.wiki/w/Enhanced_crystal_key">Enhanced crystal key</a>, <a
 * href="https://oldschool.runescape.wiki/w/Larran%27s_key">Larran's key</a>, <a
 * href="https://oldschool.runescape.wiki/w/Ogre_coffin_key">Ogre coffin key</a>, <a
 * href="https://oldschool.runescape.wiki/w/Muddy_key">Muddy key</a>, <a
 * href="https://oldschool.runescape.wiki/w/Sinister_key">Sinister key</a>, <a
 * href="https://oldschool.runescape.wiki/w/Grubby_key">Grubby key</a>, <a
 * href="https://oldschool.runescape.wiki/w/Brimstone_key">Brimstone key</a>, <a
 * href="https://oldschool.runescape.wiki/w/Moon_key">Moon key</a>, and <a
 * href="https://oldschool.runescape.wiki/w/Shade_key">Shade key variants</a> and the <a
 * href="https://oldschool.runescape.wiki/w/Bronze_key_crimson">Bronze key crimson</a>, <a
 * href="https://oldschool.runescape.wiki/w/Steel_key_brown">Steel key brown</a>, <a
 * href="https://oldschool.runescape.wiki/w/Black_key_black">Black key black</a>, <a
 * href="https://oldschool.runescape.wiki/w/Silver_key_red">Silver key red</a>, and <a
 * href="https://oldschool.runescape.wiki/w/Gold_key_purple">Gold key purple</a> pages. Tradeable
 * keys keep their market opportunity cost. Untradeable keys represent an unclaimed reward and are
 * deliberately held at zero until contents enter inventory. The Wiki explicitly documents
 * consumption for Crystal, Muddy, Sinister and Grubby keys (<a
 * href="https://oldschool.runescape.wiki/w/Crystal_key">Crystal key</a>, <a
 * href="https://oldschool.runescape.wiki/w/Muddy_chest">Muddy chest</a>, <a
 * href="https://oldschool.runescape.wiki/w/Money_making_guide/Opening_sinister_chests">Sinister
 * chest</a>, <a
 * href="https://oldschool.runescape.wiki/w/Money_making_guide/Opening_grubby_chests">Grubby
 * chest</a>). The Enhanced crystal, Larran, Ogre coffin, Brimstone, Moon and Shade pages describe
 * using their keys but do not explicitly state that chest use consumes them. Every claim match
 * therefore still requires a measured inventory key loss; a click only disambiguates a chest, and
 * live acceptance must confirm consumption for those families.
 */
class KeyChestCatalogue {
    static final List<Entry> ENTRIES = new ArrayList<>();

    static {
        for (String[] row : GameData.rows("key_chests")) {
            ENTRIES.add(new Entry(Integer.parseInt(row[0]), row[1], Boolean.parseBoolean(row[2])));
        }
    }

    /** Catalogue entries for a key item; Larran's key has both chest variants. */
    static List<Entry> entriesForKey(int keyItemId) {
        var entries = new ArrayList<Entry>();
        for (Entry entry : ENTRIES) {
            if (entry.keyItemId == keyItemId) entries.add(entry);
        }
        return entries;
    }

    /** First known chest association for an item, or {@code null} when not catalogued. */
    static Entry entryForKey(int keyItemId) {
        List<Entry> entries = entriesForKey(keyItemId);
        return entries.isEmpty() ? null : entries.get(0);
    }

    /** Match an observed object target that contains one exact catalogue chest label. */
    static Entry entryForChest(String target) {
        if (Phrases.blank(target)) return null;
        String normalizedTarget = normalize(target);
        Entry best = null;
        for (Entry entry : ENTRIES) {
            String name = normalize(entry.chestName);
            if (normalizedTarget.contains(name)
                    && (best == null || name.length() > normalize(best.chestName).length())) {
                best = entry;
            }
        }
        return best;
    }

    /** Arm a short-lived chest label for measured key-loss disambiguation only. */
    static Entry entryForMenu(String option, String target) {
        String action = option == null ? "" : normalize(option);
        if (!(action.equals("open")
                || action.startsWith("open ")
                || action.equals("unlock")
                || action.startsWith("unlock ")
                || action.equals("search")
                || action.startsWith("search "))) {
            return null;
        }
        return entryForChest(target);
    }

    static boolean isKey(int keyItemId) {
        return entryForKey(keyItemId) != null;
    }

    /** Deferred claims have no held value and are excluded from high-alchemy fallback. */
    static boolean isDeferredKey(int keyItemId) {
        Entry entry = entryForKey(keyItemId);
        return entry != null && !entry.tradeable;
    }

    static List<Entry> entries() {
        return Collections.unmodifiableList(ENTRIES);
    }

    static String normalize(String value) {
        return value.trim().replace('\u2019', '\'').toLowerCase(Locale.ROOT);
    }

    /** Immutable key-to-chest information for accounting and presentation. */
    @AllArgsConstructor
    static class Entry {
        @Getter int keyItemId;
        @Getter String chestName;
        @Getter boolean tradeable;
    }
}

/** Untradeable unopened claims held at zero: their contents become income only on receipt. */
class DeferredClaims {
    /** A key/chest claim or a pouch. */
    static boolean of(int itemId) {
        return KeyChestCatalogue.isDeferredKey(itemId) || pouch(itemId);
    }

    /** Coin pouches and the Guardians of the Rift intricate pouch: contents book when opened. */
    static boolean pouch(int itemId) {
        return itemId >= ItemID.PICKPOCKET_COIN_POUCH_CITIZEN
                        && itemId <= ItemID.PICKPOCKET_COIN_POUCH_ELF
                || itemId == ItemID.PICKPOCKET_COIN_POUCH_VYRE
                || itemId == ItemID.PICKPOCKET_COIN_POUCH_VARLAMORE_WEALTHY
                || itemId == ItemID.PICKPOCKET_COIN_POUCH_PIRATE
                || itemId == ItemID.GOTR_INTRICATE_POUCH;
    }
}

/**
 * Reward-interface / chest adapters → Pending Rewards → Claimed once. Name matching for Loot
 * Tracker EVENT + live reward UIs.
 */
class RewardChestCatalogue {
    /** True when this LT/event name is a reward chest / raid / clue pool — not floor loot. */

    /** Reward fragments share the same once-compiled matcher as other evidence tables. */
    static final Map<String, Phrases.Clause[][]> RULES = GameData.rules("reward_chests");

    static boolean isRewardChest(String raw) {
        if (Phrases.blank(raw)) return false;
        if (KeyChestCatalogue.entryForChest(raw) != null) return true;
        String name = Phrases.lower(raw);
        return RULES.values().stream().anyMatch(c -> Phrases.has(name, c));
    }
}

/** Polls the real loot-key chest interface; menu and chat wording is never key source evidence. */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class LootKeyManifestReader {
    final Client client;
    final ItemLookup lookup;
    final Engine engine;

    void poll() {
        if (client == null || engine == null) return;
        boolean visible = Widgets.visible(client, InterfaceID.WildyLootChest.UNIVERSE);
        engine.setChestVisible(visible);
        if (!visible) return;
        for (int index = 0; index < 5; index++) {
            int keyItemId = KeyClaims.keyItemId(index);
            ItemContainer keyContents = client.getItemContainer(KeyClaims.keyContainerId(index));
            if (keyContents == null) continue;
            Map<Integer, Long> rawManifest =
                    KeyClaims.manifestFromContainer(keyContents, this::isNestedContainerItem);
            var manifest = new HashMap<Integer, Long>();
            for (Map.Entry<Integer, Long> item : rawManifest.entrySet()) {
                int canonicalId = lookup == null ? item.getKey() : lookup.canon(item.getKey());
                long quantity = item.getValue();
                if (canonicalId <= 0 || quantity <= 0L) continue;
                manifest.merge(canonicalId, quantity, LootKeyManifestReader::safeAddPositive);
            }
            // An open chest is evidence only; settled inventory gains carry the financial truth.
            engine.observeKeyContainer(keyItemId, manifest);
        }
    }

    boolean isNestedContainerItem(int itemId) {
        String name = itemName(itemId);
        return name == null || Phrases.has(name, "crate", "casket");
    }

    String itemName(int itemId) {
        if (lookup == null) return null;
        try {
            ItemComposition composition = lookup.composition(itemId);
            return composition == null
                            || composition.getName() == null
                            || composition.getName().trim().isEmpty()
                    ? null
                    : composition.getName().toLowerCase(Locale.ROOT);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    static long safeAddPositive(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
