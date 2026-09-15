package com.gpmanager.model;

import java.text.NumberFormat;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Persisted, presentation-only provenance for a Wilderness loot key.
 *
 * <p>The manifest never contributes flows or values to a session. Inventory
 * settlement remains the sole source of counted loot; this object only records
 * the manifest observed in RuneLite's key container and which settled inventory
 * gains were uniquely attributable to it.</p>
 */
public class LootKeyProvenance
{
    public enum Status
    {
        PENDING,
        CLAIMED,
        LOST_ON_DEATH
    }

    private int keyItemId;
    private long keyQuantity;
    private long remainingKeyQuantity;
    private long lostKeyQuantity;
    private String encounterId;
    private String victimName;
    private long killedAtEpochMillis;
    private long receivedAtEpochMillis;
    private Map<Integer, Long> manifestQuantities;
    private Map<Integer, Long> remainingManifestQuantities;
    private Map<Integer, Long> manifestUnitPrices;
    private Map<Integer, Long> claimedQuantities;
    private long manifestValueGp;
    private boolean manifestValueComplete;
    private long claimedValueGp;
    private boolean claimedValueComplete;
    private long manifestCapturedAtEpochMillis;
    private long claimedAtEpochMillis;
    private Status status;
    /** Claim transactions hold immutable-at-write snapshots with this flag set. */
    private boolean claimReceipt;

    public LootKeyProvenance()
    {
        // Gson
    }

    public LootKeyProvenance(
        int keyItemId,
        long keyQuantity,
        String encounterId,
        String victimName,
        long killedAtEpochMillis,
        long receivedAtEpochMillis)
    {
        this.keyItemId = keyItemId;
        this.keyQuantity = Math.max(0L, keyQuantity);
        this.remainingKeyQuantity = this.keyQuantity;
        this.encounterId = emptyToNull(encounterId);
        this.victimName = emptyToNull(victimName);
        this.killedAtEpochMillis = Math.max(0L, killedAtEpochMillis);
        this.receivedAtEpochMillis = Math.max(0L, receivedAtEpochMillis);
        this.manifestQuantities = new LinkedHashMap<>();
        this.remainingManifestQuantities = new LinkedHashMap<>();
        this.manifestUnitPrices = new LinkedHashMap<>();
        this.claimedQuantities = new LinkedHashMap<>();
        this.manifestValueComplete = false;
        this.status = Status.PENDING;
    }

    public int getKeyItemId() { return keyItemId; }
    public long getKeyQuantity() { return Math.max(0L, keyQuantity); }
    public long getRemainingKeyQuantity() { return Math.max(0L, remainingKeyQuantity); }
    public long getLostKeyQuantity() { return Math.max(0L, lostKeyQuantity); }
    public String getEncounterId() { return encounterId == null ? "" : encounterId; }
    public String getVictimName() { return victimName == null ? "" : victimName; }
    public long getKilledAtEpochMillis() { return Math.max(0L, killedAtEpochMillis); }
    public long getReceivedAtEpochMillis() { return Math.max(0L, receivedAtEpochMillis); }
    public long getManifestValueGp() { return Math.max(0L, manifestValueGp); }
    public boolean isManifestValueComplete() { return manifestValueComplete; }
    public long getClaimedValueGp() { return Math.max(0L, claimedValueGp); }
    public boolean isClaimedValueComplete() { return claimedValueComplete; }
    public long getManifestCapturedAtEpochMillis() { return Math.max(0L, manifestCapturedAtEpochMillis); }
    public long getClaimedAtEpochMillis() { return Math.max(0L, claimedAtEpochMillis); }
    public Status getStatus() { return status == null ? Status.PENDING : status; }
    public boolean isClaimReceipt() { return claimReceipt; }

    public Map<Integer, Long> getManifestQuantities()
    {
        return Collections.unmodifiableMap(safeMap(manifestQuantities));
    }

    public Map<Integer, Long> getRemainingManifestQuantities()
    {
        return Collections.unmodifiableMap(safeMap(remainingManifestQuantities));
    }

    public Map<Integer, Long> getClaimedQuantities()
    {
        return Collections.unmodifiableMap(safeMap(claimedQuantities));
    }

    public Map<Integer, Long> getManifestUnitPrices()
    {
        return Collections.unmodifiableMap(safeMap(manifestUnitPrices));
    }

    public boolean hasManifest()
    {
        return manifestQuantities != null && !manifestQuantities.isEmpty();
    }

    public boolean hasUnclaimedManifest()
    {
        return !claimReceipt
            && getStatus() != Status.LOST_ON_DEATH
            && remainingManifestQuantities != null
            && !remainingManifestQuantities.isEmpty();
    }

    /** Replace a pending entry's manifest; never call this from a display-only offer. */
    public void captureManifest(Map<Integer, Long> quantities, long valueGp, boolean valueComplete, long now)
    {
        captureManifest(quantities, Collections.emptyMap(), valueGp, valueComplete, now);
    }

    public void captureManifest(
        Map<Integer, Long> quantities,
        Map<Integer, Long> unitPrices,
        long valueGp,
        boolean valueComplete,
        long now)
    {
        if (getStatus() != Status.PENDING || claimReceipt || getRemainingKeyQuantity() != 1L
            || hasManifest() || quantities == null || quantities.isEmpty())
        {
            return;
        }
        manifestQuantities = safeMap(quantities);
        remainingManifestQuantities = safeMap(quantities);
        manifestUnitPrices = safeMap(unitPrices);
        manifestValueGp = Math.max(0L, valueGp);
        manifestValueComplete = valueComplete;
        manifestCapturedAtEpochMillis = Math.max(0L, now);
    }

    /**
     * Apply a uniquely matched settled inventory gain. Returns a detached receipt
     * snapshot; source manifest quantities themselves never become item flows.
     */
    public LootKeyProvenance claim(Map<Integer, Long> settledGains, long now)
    {
        return claim(settledGains, now, 0L);
    }

    /** Apply one uniquely matched claim and its observed consumed key together. */
    public LootKeyProvenance claim(Map<Integer, Long> settledGains, long now, long consumedKeyQuantity)
    {
        if (!hasUnclaimedManifest() || claimReceipt || settledGains == null
            || settledGains.isEmpty() || !containsQuantities(remainingManifestQuantities, settledGains))
        {
            return null;
        }
        // Apply the key loss before taking the immutable claim receipt snapshot.
        // It is safe only after the manifest/inventory match above has succeeded.
        noteClaimedKey(consumedKeyQuantity);
        Map<Integer, Long> claimed = safeMap(settledGains);
        for (Map.Entry<Integer, Long> entry : claimed.entrySet())
        {
            int id = entry.getKey();
            long quantity = entry.getValue();
            long remain = remainingManifestQuantities.getOrDefault(id, 0L) - quantity;
            if (remain <= 0L)
            {
                remainingManifestQuantities.remove(id);
            }
            else
            {
                remainingManifestQuantities.put(id, remain);
            }
            claimedQuantities.merge(id, quantity, LootKeyProvenance::safeAdd);
        }
        if (claimedAtEpochMillis == 0L)
        {
            claimedAtEpochMillis = Math.max(0L, now);
        }

        LootKeyProvenance receipt = copy();
        receipt.claimReceipt = true;
        receipt.claimedAtEpochMillis = Math.max(0L, now);
        receipt.claimedQuantities = claimed;
        receipt.claimedValueComplete = true;
        receipt.claimedValueGp = 0L;
        for (Map.Entry<Integer, Long> entry : claimed.entrySet())
        {
            long quantity = entry.getValue();
            Long unitPrice = manifestUnitPrices == null ? null : manifestUnitPrices.get(entry.getKey());
            if (unitPrice == null || unitPrice <= 0L)
            {
                receipt.claimedValueComplete = false;
                continue;
            }
            try
            {
                receipt.claimedValueGp = Math.addExact(receipt.claimedValueGp,
                    Math.multiplyExact(unitPrice, quantity));
            }
            catch (ArithmeticException ignored)
            {
                receipt.claimedValueGp = Long.MAX_VALUE;
                receipt.claimedValueComplete = false;
            }
        }
        receipt.status = Status.CLAIMED;
        if (remainingManifestQuantities.isEmpty())
        {
            status = Status.CLAIMED;
        }
        return receipt;
    }

    /** Reduce held-key quantity only from a matching negative inventory delta. */
    public long noteLostOnDeath(long quantity)
    {
        if (getStatus() != Status.PENDING || claimReceipt || quantity <= 0L)
        {
            return 0L;
        }
        long lost = Math.min(getRemainingKeyQuantity(), quantity);
        remainingKeyQuantity -= lost;
        lostKeyQuantity = safeAdd(lostKeyQuantity, lost);
        if (remainingKeyQuantity <= 0L)
        {
            remainingKeyQuantity = 0L;
            status = Status.LOST_ON_DEATH;
        }
        return lost;
    }

    /** Key consumption after a uniquely matched claim; no value is booked here. */
    public long noteClaimedKey(long quantity)
    {
        if (getStatus() != Status.PENDING || claimReceipt || quantity <= 0L)
        {
            return 0L;
        }
        long consumed = Math.min(remainingKeyQuantity, quantity);
        remainingKeyQuantity = Math.max(0L, remainingKeyQuantity - consumed);
        if (remainingKeyQuantity == 0L && !hasUnclaimedManifest())
        {
            status = Status.CLAIMED;
        }
        return consumed;
    }

    public LootKeyProvenance copy()
    {
        LootKeyProvenance copy = new LootKeyProvenance();
        copy.keyItemId = keyItemId;
        copy.keyQuantity = keyQuantity;
        copy.remainingKeyQuantity = remainingKeyQuantity;
        copy.lostKeyQuantity = lostKeyQuantity;
        copy.encounterId = encounterId;
        copy.victimName = victimName;
        copy.killedAtEpochMillis = killedAtEpochMillis;
        copy.receivedAtEpochMillis = receivedAtEpochMillis;
        copy.manifestQuantities = safeMap(manifestQuantities);
        copy.remainingManifestQuantities = safeMap(remainingManifestQuantities);
        copy.manifestUnitPrices = safeMap(manifestUnitPrices);
        copy.claimedQuantities = safeMap(claimedQuantities);
        copy.manifestValueGp = manifestValueGp;
        copy.manifestValueComplete = manifestValueComplete;
        copy.claimedValueGp = claimedValueGp;
        copy.claimedValueComplete = claimedValueComplete;
        copy.manifestCapturedAtEpochMillis = manifestCapturedAtEpochMillis;
        copy.claimedAtEpochMillis = claimedAtEpochMillis;
        copy.status = getStatus();
        copy.claimReceipt = claimReceipt;
        return copy;
    }

    /** A ledger-only sentence; callers must not feed this text into accounting. */
    public String ledgerSummary()
    {
        StringBuilder text = new StringBuilder("Loot key");
        if (hasManifest())
        {
            text.append(" · ").append(NumberFormat.getIntegerInstance(Locale.ROOT)
                .format(manifestValueGp)).append(" gp manifest");
            if (!manifestValueComplete)
            {
                text.append(" (partial GE price data)");
            }
        }
        if (!getVictimName().isEmpty())
        {
            text.append(" · from ").append(getVictimName());
        }
        if (claimReceipt)
        {
            text.append(" · claimed ").append(NumberFormat.getIntegerInstance(Locale.ROOT)
                .format(claimedValueGp)).append(" gp");
            if (!claimedValueComplete)
            {
                text.append(" (partial GE price data)");
            }
            text.append(" · claimed ").append(elapsedSinceKill());
        }
        else if (getStatus() == Status.LOST_ON_DEATH)
        {
            text.append(" · Key lost on death");
        }
        else if (getLostKeyQuantity() > 0L)
        {
            text.append(" · ").append(getLostKeyQuantity()).append(" lost on death; ")
                .append(getRemainingKeyQuantity()).append(" pending");
        }
        else if (getStatus() == Status.CLAIMED)
        {
            text.append(" · claimed");
        }
        else if (getRemainingKeyQuantity() == 0L && hasUnclaimedManifest())
        {
            text.append(" · key consumed; contents pending");
        }
        else
        {
            text.append(" · pending");
        }
        return text.toString();
    }

    private String elapsedSinceKill()
    {
        if (killedAtEpochMillis <= 0L || claimedAtEpochMillis <= 0L)
        {
            return "time unknown after kill";
        }
        long seconds = Math.max(0L, claimedAtEpochMillis - killedAtEpochMillis) / 1000L;
        return String.format(Locale.ROOT, "%02d:%02d after kill", seconds / 60L, seconds % 60L);
    }

    public static boolean containsQuantities(Map<Integer, Long> available, Map<Integer, Long> wanted)
    {
        if (available == null || wanted == null || wanted.isEmpty())
        {
            return false;
        }
        for (Map.Entry<Integer, Long> entry : wanted.entrySet())
        {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0L
                || available.getOrDefault(entry.getKey(), 0L) < entry.getValue())
            {
                return false;
            }
        }
        return true;
    }

    private static Map<Integer, Long> safeMap(Map<Integer, Long> incoming)
    {
        Map<Integer, Long> result = new LinkedHashMap<>();
        if (incoming != null)
        {
            for (Map.Entry<Integer, Long> entry : incoming.entrySet())
            {
                if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0L)
                {
                    result.merge(entry.getKey(), entry.getValue(), LootKeyProvenance::safeAdd);
                }
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

    private static String emptyToNull(String value)
    {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
