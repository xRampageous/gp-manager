package com.gpmanager.engine;

import com.gpmanager.model.DeferredClaimProvenance;
import com.gpmanager.model.KeyChestCatalogue;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** Short-lived chest-open evidence plus persisted audit-row claim closure. */
public final class DeferredChestClaimLifecycle
{
    private static final int OPEN_WINDOW_TICKS = 4;

    private int openKeyItemId = -1;
    private String openChestName;
    private int openTicks;
    private int usedKeyItemId = -1;
    private long usedKeyQuantity;
    private String usedChestName;
    private int usedTicks;

    public synchronized boolean observeMenuOption(String option, String target)
    {
        KeyChestCatalogue.Entry entry = KeyChestCatalogue.entryForMenuOption(option, target);
        if (entry == null)
        {
            clear();
            return false;
        }
        openKeyItemId = entry.getKeyItemId();
        openChestName = entry.getChestName();
        openTicks = OPEN_WINDOW_TICKS;
        usedKeyItemId = -1;
        usedKeyQuantity = 0L;
        usedChestName = null;
        usedTicks = 0;
        return true;
    }

    public synchronized void cancel()
    {
        clear();
    }

    public synchronized void tick()
    {
        if (openTicks > 0 && --openTicks == 0)
        {
            openKeyItemId = -1;
            openChestName = null;
        }
        if (usedTicks > 0 && --usedTicks == 0)
        {
            usedKeyItemId = -1;
            usedKeyQuantity = 0L;
            usedChestName = null;
        }
    }

    /**
     * Correlate a settled key loss with settled positive inventory contents. A
     * click alone is never a claim, and ambiguous keys with multiple chest types
     * require the observed object name.
     */
    public synchronized Match matchContents(Map<Integer, Long> keyLosses,
        Map<Integer, Long> positiveContents, @Nullable String sourceBackedActivity)
    {
        if (positiveContents == null || positiveContents.isEmpty())
        {
            if (sourceBackedActivity != null && !sourceBackedActivity.trim().isEmpty())
            {
                clear();
                return null;
            }
            rememberUseWithoutContents(keyLosses);
            return null;
        }

        int keyId = -1;
        long quantity = 0L;
        String chestName = null;
        Map<Integer, Long> losses = positiveOnly(keyLosses);
        if (!losses.isEmpty())
        {
            if (losses.size() != 1)
            {
                clear();
                return null;
            }
            Map.Entry<Integer, Long> loss = losses.entrySet().iterator().next();
            keyId = loss.getKey();
            quantity = loss.getValue();
            int armedKeyItemId = openTicks > 0 ? openKeyItemId
                : usedTicks > 0 ? usedKeyItemId : -1;
            if (armedKeyItemId >= 0 && armedKeyItemId != keyId)
            {
                clear();
                return null;
            }
            if (openTicks > 0 && openKeyItemId == keyId)
            {
                chestName = openChestName;
            }
            else
            {
                List<KeyChestCatalogue.Entry> entries = KeyChestCatalogue.entriesForKey(keyId);
                if (entries.size() == 1)
                {
                    chestName = entries.get(0).getChestName();
                }
            }
            clearUse();
        }
        else if (usedTicks > 0)
        {
            keyId = usedKeyItemId;
            quantity = usedKeyQuantity;
            chestName = usedChestName;
            clearUse();
        }

        if (keyId < 0 || quantity <= 0L || chestName == null)
        {
            // Do not let an uncorroborated gain leave an object click armed to
            // relabel a later, unrelated key loss and contents change.
            if (!positiveContents.isEmpty())
            {
                clearOpen();
            }
            return null;
        }
        KeyChestCatalogue.Entry sourceChest = KeyChestCatalogue.entryForChestMention(sourceBackedActivity);
        if (sourceBackedActivity != null && !sourceBackedActivity.trim().isEmpty()
            && (sourceChest == null || !chestName.equalsIgnoreCase(sourceChest.getChestName())))
        {
            clear();
            return null;
        }
        if (openTicks > 0 && openKeyItemId == keyId
            && openChestName != null && !openChestName.equalsIgnoreCase(chestName))
        {
            return null;
        }

        Match match = new Match(keyId, quantity, chestName);
        clear();
        return match;
    }

    public static DeferredClaimProvenance receivedEntry(int keyItemId, String keyItemName,
        String chestName, long quantity, long now)
    {
        return new DeferredClaimProvenance(keyItemId, keyItemName, chestName, quantity, now);
    }

    /** Apply a confirmed claim to the newest pending rows for this exact key ID. */
    public static List<ProfitTransaction> closeClaim(List<ProfitSession> sessions,
        int keyItemId, long quantity, long now)
    {
        return closeRows(sessions, keyItemId, quantity, now, false);
    }

    /** Close measured key losses on a local death without booking a key cost. */
    public static List<ProfitTransaction> closeLostOnDeath(List<ProfitSession> sessions,
        int keyItemId, long quantity, long now)
    {
        return closeRows(sessions, keyItemId, quantity, now, true);
    }

    private static List<ProfitTransaction> closeRows(List<ProfitSession> sessions,
        int keyItemId, long quantity, long now, boolean death)
    {
        long remaining = Math.max(0L, quantity);
        if (sessions == null || remaining == 0L)
        {
            return Collections.emptyList();
        }
        List<ProfitTransaction> changed = new ArrayList<>();
        for (int sessionIndex = sessions.size() - 1; sessionIndex >= 0 && remaining > 0L; sessionIndex--)
        {
            ProfitSession session = sessions.get(sessionIndex);
            if (session == null)
            {
                continue;
            }
            List<ProfitTransaction> transactions = session.getTransactions();
            for (int txIndex = transactions.size() - 1; txIndex >= 0 && remaining > 0L; txIndex--)
            {
                ProfitTransaction transaction = transactions.get(txIndex);
                if (transaction == null || transaction.getType() != com.gpmanager.model.TransactionType.TRANSFER
                    || transaction.isCounted())
                {
                    continue;
                }
                DeferredClaimProvenance provenance = transaction.getDeferredClaimProvenance();
                if (provenance == null || !provenance.isPending() || provenance.getKeyItemId() != keyItemId)
                {
                    continue;
                }
                long applied = Math.min(remaining, provenance.getRemainingQuantity());
                DeferredClaimProvenance updated = death
                    ? provenance.lostOnDeath(applied, now)
                    : provenance.claim(applied, now);
                transaction.setDeferredClaimProvenance(updated);
                transaction.setNote(updated.summary());
                remaining -= applied;
                changed.add(transaction);
            }
        }
        return Collections.unmodifiableList(changed);
    }

    private void rememberUseWithoutContents(Map<Integer, Long> keyLosses)
    {
        Map<Integer, Long> losses = positiveOnly(keyLosses);
        if (openTicks <= 0 || openKeyItemId < 0 || losses.size() != 1
            || !losses.containsKey(openKeyItemId))
        {
            return;
        }
        usedKeyItemId = openKeyItemId;
        usedKeyQuantity = losses.get(openKeyItemId);
        usedChestName = openChestName;
        usedTicks = OPEN_WINDOW_TICKS;
        openTicks = 0;
        openKeyItemId = -1;
        openChestName = null;
    }

    private void clearUse()
    {
        usedKeyItemId = -1;
        usedKeyQuantity = 0L;
        usedChestName = null;
        usedTicks = 0;
    }

    private void clearOpen()
    {
        openKeyItemId = -1;
        openChestName = null;
        openTicks = 0;
    }

    private void clear()
    {
        clearOpen();
        clearUse();
    }

    private static Map<Integer, Long> positiveOnly(Map<Integer, Long> values)
    {
        if (values == null || values.isEmpty())
        {
            return Collections.emptyMap();
        }
        java.util.HashMap<Integer, Long> result = new java.util.HashMap<>();
        for (Map.Entry<Integer, Long> value : values.entrySet())
        {
            if (value.getKey() != null && value.getValue() != null && value.getValue() > 0L)
            {
                result.put(value.getKey(), value.getValue());
            }
        }
        return result;
    }

    public static final class Match
    {
        private final int keyItemId;
        private final long quantity;
        private final String chestName;

        private Match(int keyItemId, long quantity, String chestName)
        {
            this.keyItemId = keyItemId;
            this.quantity = quantity;
            this.chestName = chestName;
        }

        public int getKeyItemId() { return keyItemId; }
        public long getQuantity() { return quantity; }
        public String getChestName() { return chestName; }
    }
}
