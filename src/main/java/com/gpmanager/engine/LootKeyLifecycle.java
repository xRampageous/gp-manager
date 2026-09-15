package com.gpmanager.engine;

import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.LootKeyProvenance;
import com.gpmanager.model.PkEncounter;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntPredicate;
import javax.annotation.Nullable;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;

/** Per-key, pickup-gated provenance for Wilderness loot keys. */
public final class LootKeyLifecycle
{
    /** Soft cap before overflow floor-drops. */
    public static final int KEY_SOFT_CAP = 5;
    public static final int CHEST_INTERFACE_ID = InterfaceID.WILDY_LOOT_CHEST;
    private static final int DEATH_WINDOW_TICKS = 8;
    private static final int CLAIM_EVIDENCE_TICKS = 4;
    private static final int[] KEY_ITEM_IDS = {
        ItemID.WILDY_LOOT_KEY0,
        ItemID.WILDY_LOOT_KEY1,
        ItemID.WILDY_LOOT_KEY2,
        ItemID.WILDY_LOOT_KEY3,
        ItemID.WILDY_LOOT_KEY4
    };
    private static final int[] KEY_CONTAINER_IDS = {
        InventoryID.DEADMAN_LOOT_INV0,
        InventoryID.DEADMAN_LOOT_INV1,
        InventoryID.DEADMAN_LOOT_INV2,
        InventoryID.DEADMAN_LOOT_INV3,
        InventoryID.DEADMAN_LOOT_INV4
    };

    @Nullable
    private Map<Integer, Long> localDeathInventory;
    private int localDeathTicks;
    private boolean lootChestVisible;
    private final Map<Integer, Map<Integer, Long>> observedKeyContainerContents = new HashMap<>();
    private final Map<Integer, Map<Integer, Long>> pendingClaimContents = new HashMap<>();
    private final Map<Integer, Integer> pendingClaimTicks = new HashMap<>();

    public synchronized void beginLocalDeath(Map<Integer, Long> keyInventory)
    {
        Map<Integer, Long> normalized = normalizeKeyQuantities(keyInventory);
        localDeathInventory = normalized.isEmpty() ? null : normalized;
        localDeathTicks = localDeathInventory == null ? 0 : DEATH_WINDOW_TICKS;
    }

    public synchronized void tick()
    {
        if (localDeathTicks > 0 && --localDeathTicks == 0)
        {
            localDeathInventory = null;
        }
        for (Integer keyIndex : new ArrayList<>(pendingClaimTicks.keySet()))
        {
            int remaining = pendingClaimTicks.getOrDefault(keyIndex, 0) - 1;
            if (remaining <= 0)
            {
                pendingClaimTicks.remove(keyIndex);
                pendingClaimContents.remove(keyIndex);
            }
            else
            {
                pendingClaimTicks.put(keyIndex, remaining);
            }
        }
    }

    public synchronized void clearEphemeralState()
    {
        localDeathInventory = null;
        localDeathTicks = 0;
        lootChestVisible = false;
        observedKeyContainerContents.clear();
        pendingClaimContents.clear();
        pendingClaimTicks.clear();
    }

    public synchronized void setLootChestVisible(boolean visible)
    {
        if (lootChestVisible && !visible)
        {
            // Reopening a key UI starts a fresh container-delta baseline. A
            // short, already-observed claim delta may still settle afterward.
            observedKeyContainerContents.clear();
        }
        lootChestVisible = visible;
    }

    public synchronized boolean isLootChestVisible()
    {
        return lootChestVisible;
    }

    public synchronized boolean isAwaitingLocalDeathSettle()
    {
        return localDeathInventory != null && localDeathTicks > 0;
    }

    /**
     * Observe one key's server-owned manifest while its loot-key interface is
     * visible. Only removals from this container can correlate settled INV gains
     * to a claim receipt; ordinary inventory gains never use manifest matching
     * alone.
     */
    public synchronized boolean observeKeyContainerContents(
        List<ProfitSession> sessions,
        int keyItemId,
        Map<Integer, Long> contents,
        Map<Integer, Long> unitPrices,
        long manifestValueGp,
        boolean valueComplete,
        long now)
    {
        int keyIndex = keyIndexForItemId(keyItemId);
        if (!lootChestVisible || keyIndex < 0 || contents == null)
        {
            return false;
        }
        boolean attached = !contents.isEmpty() && captureManifest(
            sessions, keyItemId, contents, unitPrices, manifestValueGp, valueComplete, now);
        Map<Integer, Long> next = positiveEntries(contents);
        Map<Integer, Long> previous = observedKeyContainerContents.put(keyIndex, next);
        if (previous != null)
        {
            Map<Integer, Long> removed = removedQuantities(previous, next);
            if (!removed.isEmpty())
            {
                Map<Integer, Long> accumulated = new HashMap<>(pendingClaimContents.getOrDefault(keyIndex,
                    Collections.emptyMap()));
                mergeInto(accumulated, removed);
                pendingClaimContents.put(keyIndex, accumulated);
                pendingClaimTicks.put(keyIndex, CLAIM_EVIDENCE_TICKS);
            }
        }
        return attached;
    }

    /** Add provenance only to a key transaction that was created from an INV gain. */
    public static LootKeyProvenance receivedEntry(
        int keyItemId,
        long quantity,
        @Nullable String encounterId,
        @Nullable ProfitSession session,
        long receivedAt)
    {
        return receivedEntry(keyItemId, quantity, encounterId,
            session == null ? Collections.emptyList() : Collections.singletonList(session), receivedAt);
    }

    public static LootKeyProvenance receivedEntry(
        int keyItemId,
        long quantity,
        @Nullable String encounterId,
        List<ProfitSession> sessions,
        long receivedAt)
    {
        PkEncounter encounter = null;
        if (encounterId != null && sessions != null)
        {
            for (ProfitSession session : sessions)
            {
                if (session != null)
                {
                    encounter = session.findPkEncounter(encounterId);
                    if (encounter != null)
                    {
                        break;
                    }
                }
            }
        }
        String victim = "";
        long killedAt = 0L;
        if (encounter != null)
        {
            String label = encounter.getLabel();
            if (label != null && label.toLowerCase(Locale.ROOT).startsWith("kill:"))
            {
                victim = label.substring(label.indexOf(':') + 1).trim();
            }
            killedAt = encounter.getTimestampEpochMillis();
        }
        return new LootKeyProvenance(keyItemId, quantity, encounterId, victim, killedAt, receivedAt);
    }

    /**
     * Attach a manifest to exactly one pending audit row of this key type. If
     * duplicate pending rows make identity ambiguous, retain them unchanged.
     */
    public static boolean captureManifest(
        @Nullable ProfitSession session,
        int keyItemId,
        Map<Integer, Long> manifest,
        long valueGp,
        boolean valueComplete,
        long now)
    {
        return captureManifest(session == null ? Collections.emptyList() : Collections.singletonList(session),
            keyItemId, manifest, Collections.emptyMap(), valueGp, valueComplete, now);
    }

    public static boolean captureManifest(
        List<ProfitSession> sessions,
        int keyItemId,
        Map<Integer, Long> manifest,
        long valueGp,
        boolean valueComplete,
        long now)
    {
        return captureManifest(sessions, keyItemId, manifest, Collections.emptyMap(),
            valueGp, valueComplete, now);
    }

    public static boolean captureManifest(
        List<ProfitSession> sessions,
        int keyItemId,
        Map<Integer, Long> manifest,
        Map<Integer, Long> unitPrices,
        long valueGp,
        boolean valueComplete,
        long now)
    {
        if (sessions == null || !isLootKeyItem(keyItemId) || manifest == null || manifest.isEmpty())
        {
            return false;
        }
        ProfitTransaction match = null;
        int matchIndex = -1;
        int pendingKeyRows = 0;
        for (ProfitSession session : sessions)
        {
            if (session == null)
            {
                continue;
            }
            for (int i = session.getTransactions().size() - 1; i >= 0; i--)
            {
                ProfitTransaction transaction = session.getTransactions().get(i);
                List<LootKeyProvenance> entries = transaction.getLootKeyProvenance();
                for (int j = 0; j < entries.size(); j++)
                {
                    LootKeyProvenance entry = entries.get(j);
                    if (entry.getKeyItemId() == keyItemId
                        && entry.getStatus() == LootKeyProvenance.Status.PENDING
                        && entry.getRemainingKeyQuantity() > 0L)
                    {
                        pendingKeyRows++;
                        if (entry.getRemainingKeyQuantity() != 1L || entry.hasManifest())
                        {
                            continue;
                        }
                        if (match != null)
                        {
                            return false;
                        }
                        match = transaction;
                        matchIndex = j;
                    }
                }
            }
        }
        if (match == null || pendingKeyRows != 1)
        {
            return false;
        }
        List<LootKeyProvenance> entries = new ArrayList<>(match.getLootKeyProvenance());
        LootKeyProvenance updated = entries.get(matchIndex);
        updated.captureManifest(manifest, unitPrices, valueGp, valueComplete, now);
        entries.set(matchIndex, updated);
        match.setLootKeyProvenance(entries);
        return updated.hasManifest();
    }

    /**
     * Attach a claim receipt only when all positive settled inventory quantities
     * fit one and only one pending key manifest. This is descriptive metadata;
     * it does not alter transaction type, valuation, flows, or counted state.
     */
    public synchronized List<LootKeyProvenance> recordClaim(
        List<ProfitSession> sessions,
        Map<Integer, Long> settledPositiveFlows,
        long now)
    {
        return recordClaim(sessions, settledPositiveFlows, Collections.emptyMap(), now);
    }

    public synchronized List<LootKeyProvenance> recordClaim(
        List<ProfitSession> sessions,
        Map<Integer, Long> settledPositiveFlows,
        Map<Integer, Long> settledKeyLosses,
        long now)
    {
        if (sessions == null || settledPositiveFlows == null || settledPositiveFlows.isEmpty())
        {
            return Collections.emptyList();
        }
        ProfitTransaction match = null;
        int matchIndex = -1;
        int matchKeyIndex = -1;
        int candidateCount = 0;
        Map<Integer, Long> matchedEvidence = null;
        for (Map.Entry<Integer, Integer> evidence : pendingClaimTicks.entrySet())
        {
            int keyIndex = evidence.getKey();
            Map<Integer, Long> removedContents = pendingClaimContents.get(keyIndex);
            if (evidence.getValue() <= 0 || !LootKeyProvenance.containsQuantities(removedContents, settledPositiveFlows))
            {
                continue;
            }
            int keyItemId = keyItemIdForIndex(keyIndex);
            for (ProfitSession session : sessions)
            {
                if (session == null)
                {
                    continue;
                }
                for (int i = session.getTransactions().size() - 1; i >= 0; i--)
                {
                    ProfitTransaction transaction = session.getTransactions().get(i);
                    List<LootKeyProvenance> entries = transaction.getLootKeyProvenance();
                    for (int j = 0; j < entries.size(); j++)
                    {
                        LootKeyProvenance entry = entries.get(j);
                        if (entry.getKeyItemId() == keyItemId
                            && entry.hasUnclaimedManifest()
                            && LootKeyProvenance.containsQuantities(
                                entry.getRemainingManifestQuantities(), settledPositiveFlows))
                        {
                            candidateCount++;
                            if (candidateCount == 1)
                            {
                                match = transaction;
                                matchIndex = j;
                                matchKeyIndex = keyIndex;
                                matchedEvidence = removedContents;
                            }
                        }
                    }
                }
            }
        }
        if (candidateCount != 1 || match == null || matchedEvidence == null)
        {
            return Collections.emptyList();
        }

        List<LootKeyProvenance> entries = new ArrayList<>(match.getLootKeyProvenance());
        LootKeyProvenance entry = entries.get(matchIndex);
        LootKeyProvenance receipt = entry.claim(
            settledPositiveFlows,
            now,
            settledKeyLosses == null
                ? 0L
                : settledKeyLosses.getOrDefault(keyItemIdForIndex(matchKeyIndex), 0L));
        if (receipt == null)
        {
            return Collections.emptyList();
        }
        entries.set(matchIndex, entry);
        match.setLootKeyProvenance(entries);

        Map<Integer, Long> remainingEvidence = subtractQuantities(matchedEvidence, settledPositiveFlows);
        if (remainingEvidence.isEmpty())
        {
            pendingClaimContents.remove(matchKeyIndex);
            pendingClaimTicks.remove(matchKeyIndex);
        }
        else
        {
            pendingClaimContents.put(matchKeyIndex, remainingEvidence);
            pendingClaimTicks.put(matchKeyIndex, CLAIM_EVIDENCE_TICKS);
        }
        return Collections.singletonList(receipt);
    }

    /**
     * Mark pending records as lost only for key units that disappeared from INV
     * in a local-death settlement. Manifests never create a loss flow.
     */
    public synchronized Map<Integer, Long> recordDeathLosses(
        List<ProfitSession> sessions,
        List<ItemFlow> settledFlows,
        Map<Integer, Long> currentKeyInventory,
        long now)
    {
        if (sessions == null || localDeathInventory == null || localDeathTicks <= 0)
        {
            return Collections.emptyMap();
        }
        Map<Integer, Long> flowLosses = new HashMap<>();
        if (settledFlows != null)
        {
            for (ItemFlow flow : settledFlows)
            {
                if (flow != null && flow.getQuantityDelta() < 0L && isLootKeyItem(flow.getItemId()))
                {
                    flowLosses.merge(flow.getItemId(), -flow.getQuantityDelta(), LootKeyLifecycle::safeAdd);
                }
            }
        }
        Map<Integer, Long> current = normalizeKeyQuantities(currentKeyInventory);
        Map<Integer, Long> confirmedLost = new HashMap<>();
        for (int keyItemId : KEY_ITEM_IDS)
        {
            long before = localDeathInventory.getOrDefault(keyItemId, 0L);
            long after = current.getOrDefault(keyItemId, 0L);
            long observedGone = Math.min(Math.max(0L, before - after), flowLosses.getOrDefault(keyItemId, 0L));
            long matchedLoss = observedGone > 0L
                ? updatePendingKey(sessions, keyItemId, observedGone, true)
                : 0L;
            if (matchedLoss > 0L)
            {
                confirmedLost.put(keyItemId, matchedLoss);
            }
        }
        // Inventory/equipment changes can stabilize in separate batches after a
        // death. Keep the bounded snapshot until each confirmed key loss is
        // consumed or the game-tick window expires; an unrelated first batch must
        // not make a later key-loss flow look like an ordinary cost.
        for (Map.Entry<Integer, Long> lost : confirmedLost.entrySet())
        {
            long remaining = localDeathInventory.getOrDefault(lost.getKey(), 0L) - lost.getValue();
            if (remaining <= 0L)
            {
                localDeathInventory.remove(lost.getKey());
            }
            else
            {
                localDeathInventory.put(lost.getKey(), remaining);
            }
        }
        if (localDeathInventory.isEmpty())
        {
            localDeathInventory = null;
            localDeathTicks = 0;
        }
        return Collections.unmodifiableMap(confirmedLost);
    }

    public static void recordClaimedKey(
        List<ProfitSession> sessions,
        int keyItemId,
        long quantity)
    {
        updatePendingKey(sessions, keyItemId, quantity, false);
    }

    private static long updatePendingKey(
        List<ProfitSession> sessions,
        int keyItemId,
        long quantity,
        boolean death)
    {
        if (sessions == null || quantity <= 0L)
        {
            return 0L;
        }
        ProfitTransaction match = null;
        int matchIndex = -1;
        for (ProfitSession session : sessions)
        {
            if (session == null)
            {
                continue;
            }
            for (ProfitTransaction transaction : session.getTransactions())
            {
                List<LootKeyProvenance> entries = transaction.getLootKeyProvenance();
                for (int i = 0; i < entries.size(); i++)
                {
                    LootKeyProvenance entry = entries.get(i);
                    if (entry.getKeyItemId() == keyItemId && entry.getStatus() == LootKeyProvenance.Status.PENDING)
                    {
                        if (match != null)
                        {
                            return 0L;
                        }
                        match = transaction;
                        matchIndex = i;
                    }
                }
            }
        }
        if (match != null)
        {
            List<LootKeyProvenance> entries = new ArrayList<>(match.getLootKeyProvenance());
            LootKeyProvenance entry = entries.get(matchIndex);
            long matchedQuantity;
            if (death)
            {
                matchedQuantity = entry.noteLostOnDeath(quantity);
            }
            else
            {
                matchedQuantity = entry.noteClaimedKey(quantity);
            }
            if (matchedQuantity <= 0L)
            {
                return 0L;
            }
            entries.set(matchIndex, entry);
            match.setLootKeyProvenance(entries);
            return matchedQuantity;
        }
        return 0L;
    }

    public static boolean isLootKeyItem(int itemId)
    {
        return keyIndexForItemId(itemId) >= 0;
    }

    public static int keyIndexForItemId(int itemId)
    {
        for (int index = 0; index < KEY_ITEM_IDS.length; index++)
        {
            if (KEY_ITEM_IDS[index] == itemId)
            {
                return index;
            }
        }
        return -1;
    }

    public static int keyItemIdForIndex(int index)
    {
        return index < 0 || index >= KEY_ITEM_IDS.length ? -1 : KEY_ITEM_IDS[index];
    }

    public static int keyContainerIdForIndex(int index)
    {
        return index < 0 || index >= KEY_CONTAINER_IDS.length ? -1 : KEY_CONTAINER_IDS[index];
    }

    public static int keyIndexForContainerId(int containerId)
    {
        for (int index = 0; index < KEY_CONTAINER_IDS.length; index++)
        {
            if (KEY_CONTAINER_IDS[index] == containerId)
            {
                return index;
            }
        }
        return -1;
    }

    public static Map<Integer, Long> manifestFromContainer(
        @Nullable ItemContainer container,
        @Nullable IntPredicate nestedCrateItem)
    {
        // Refuse an incomplete evidence policy rather than accidentally valuing a
        // nested crate as if its unopened contents were already manifest items.
        if (container == null || container.getItems() == null || nestedCrateItem == null)
        {
            return Collections.emptyMap();
        }
        Map<Integer, Long> manifest = new LinkedHashMap<>();
        for (Item item : container.getItems())
        {
            if (item == null || item.getId() < 0 || item.getQuantity() <= 0
                || nestedCrateItem.test(item.getId()))
            {
                continue;
            }
            manifest.merge(item.getId(), (long) item.getQuantity(), LootKeyLifecycle::safeAdd);
        }
        return Collections.unmodifiableMap(manifest);
    }

    public static Map<Integer, Long> keyQuantities(@Nullable ItemContainer inventory)
    {
        if (inventory == null || inventory.getItems() == null)
        {
            return Collections.emptyMap();
        }
        Map<Integer, Long> result = new HashMap<>();
        for (Item item : inventory.getItems())
        {
            if (item != null && item.getQuantity() > 0 && isLootKeyItem(item.getId()))
            {
                result.merge(item.getId(), (long) item.getQuantity(), LootKeyLifecycle::safeAdd);
            }
        }
        return result;
    }

    /** Key enter INV — never revenue. */
    public static boolean shouldDeferKeyValue(int itemId)
    {
        return isLootKeyItem(itemId);
    }

    public static boolean looksLikeLootChestUi(String name)
    {
        if (name == null || name.trim().isEmpty())
        {
            return false;
        }
        String lower = name.trim().toLowerCase(Locale.ROOT);
        return lower.contains("loot chest") || lower.contains("wilderness loot");
    }

    public static String keyReceivedNote()
    {
        return "Loot key received - value deferred";
    }

    public static String keyOpenedNote()
    {
        return "Loot key opened";
    }

    public static String keyConsumedNote()
    {
        return "Loot key consumed - value already deferred";
    }

    public static String keyDestroyedNote()
    {
        return "Loot key destroyed - contents unreachable";
    }

    private static Map<Integer, Long> normalizeKeyQuantities(Map<Integer, Long> incoming)
    {
        Map<Integer, Long> out = new HashMap<>();
        if (incoming != null)
        {
            for (int keyItemId : KEY_ITEM_IDS)
            {
                Long quantity = incoming.get(keyItemId);
                if (quantity != null && quantity > 0L)
                {
                    out.put(keyItemId, quantity);
                }
            }
        }
        return out;
    }

    private static Map<Integer, Long> positiveEntries(Map<Integer, Long> incoming)
    {
        Map<Integer, Long> result = new LinkedHashMap<>();
        if (incoming != null)
        {
            for (Map.Entry<Integer, Long> entry : incoming.entrySet())
            {
                if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0L)
                {
                    result.merge(entry.getKey(), entry.getValue(), LootKeyLifecycle::safeAdd);
                }
            }
        }
        return result;
    }

    public static Map<Integer, Long> keyQuantities(Map<Integer, Long> quantities)
    {
        Map<Integer, Long> result = new HashMap<>();
        if (quantities != null)
        {
            for (int keyItemId : KEY_ITEM_IDS)
            {
                Long quantity = quantities.get(keyItemId);
                if (quantity != null && quantity > 0L)
                {
                    result.put(keyItemId, quantity);
                }
            }
        }
        return result;
    }

    private static Map<Integer, Long> removedQuantities(
        Map<Integer, Long> previous,
        Map<Integer, Long> current)
    {
        Map<Integer, Long> removed = new LinkedHashMap<>();
        for (Map.Entry<Integer, Long> entry : previous.entrySet())
        {
            long decrease = entry.getValue() - current.getOrDefault(entry.getKey(), 0L);
            if (decrease > 0L)
            {
                removed.put(entry.getKey(), decrease);
            }
        }
        return removed;
    }

    private static void mergeInto(Map<Integer, Long> target, Map<Integer, Long> additions)
    {
        for (Map.Entry<Integer, Long> entry : additions.entrySet())
        {
            if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0L)
            {
                target.merge(entry.getKey(), entry.getValue(), LootKeyLifecycle::safeAdd);
            }
        }
    }

    private static Map<Integer, Long> subtractQuantities(
        Map<Integer, Long> available,
        Map<Integer, Long> removed)
    {
        Map<Integer, Long> result = new LinkedHashMap<>(available);
        for (Map.Entry<Integer, Long> entry : removed.entrySet())
        {
            long remaining = result.getOrDefault(entry.getKey(), 0L) - entry.getValue();
            if (remaining <= 0L)
            {
                result.remove(entry.getKey());
            }
            else
            {
                result.put(entry.getKey(), remaining);
            }
        }
        return result;
    }

    private static long safeAdd(long left, long right)
    {
        try
        {
            return Math.addExact(left, right);
        }
        catch (ArithmeticException ignored)
        {
            return Long.MAX_VALUE;
        }
    }
}
