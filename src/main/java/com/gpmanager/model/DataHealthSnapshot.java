package com.gpmanager.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Fail-closed profile health facts for Tools/Diagnostics.
 *
 * <p>This is a detached read model.  A missing or stale source is represented
 * explicitly; callers must not interpret an empty value as zero accounting.</p>
 */
public final class DataHealthSnapshot
{
    public enum CoinStoreFreshness
    {
        FRESH,
        STALE,
        UNOBSERVED
    }

    private final int rebuiltDays;
    private final Map<DailyRollup.Dimension, Integer> unavailableDays;
    private final ReceiptRetentionStatus retention;
    private final ProfileSizeEstimate profileSize;
    private final String recoveredFrom;
    private final int rotatedBackupCount;
    private final int unknownFieldBags;
    private final Map<CoinStore, CoinStoreFreshness> coinStores;

    public DataHealthSnapshot(
        int rebuiltDays,
        Map<DailyRollup.Dimension, Integer> unavailableDays,
        ReceiptRetentionStatus retention,
        ProfileSizeEstimate profileSize,
        String recoveredFrom,
        int rotatedBackupCount,
        int unknownFieldBags,
        Map<CoinStore, CoinStoreFreshness> coinStores)
    {
        this.rebuiltDays = Math.max(0, rebuiltDays);
        EnumMap<DailyRollup.Dimension, Integer> coverage =
            new EnumMap<>(DailyRollup.Dimension.class);
        for (DailyRollup.Dimension dimension : DailyRollup.Dimension.values())
        {
            int count = unavailableDays == null || unavailableDays.get(dimension) == null
                ? 0 : unavailableDays.get(dimension);
            coverage.put(dimension, Math.max(0, count));
        }
        this.unavailableDays = Collections.unmodifiableMap(coverage);
        this.retention = retention == null ? new ReceiptRetentionStatus(0, null, 0) : retention;
        this.profileSize = profileSize == null ? new ProfileSizeEstimate(0, 0, 0, 0) : profileSize;
        this.recoveredFrom = recoveredFrom == null ? "" : recoveredFrom;
        this.rotatedBackupCount = Math.max(0, rotatedBackupCount);
        this.unknownFieldBags = Math.max(0, unknownFieldBags);
        EnumMap<CoinStore, CoinStoreFreshness> stores = new EnumMap<>(CoinStore.class);
        for (CoinStore store : CoinStore.values())
        {
            CoinStoreFreshness freshness = coinStores == null ? null : coinStores.get(store);
            stores.put(store, freshness == null ? CoinStoreFreshness.UNOBSERVED : freshness);
        }
        this.coinStores = Collections.unmodifiableMap(stores);
    }

    public int getRebuiltDays() { return rebuiltDays; }
    public Map<DailyRollup.Dimension, Integer> getUnavailableDays() { return unavailableDays; }
    public int getUnavailableDays(DailyRollup.Dimension dimension)
    {
        return dimension == null ? 0 : unavailableDays.getOrDefault(dimension, 0);
    }
    public ReceiptRetentionStatus getRetention() { return retention; }
    public ProfileSizeEstimate getProfileSize() { return profileSize; }
    public String getRecoveredFrom() { return recoveredFrom; }
    public int getRotatedBackupCount() { return rotatedBackupCount; }
    public int getUnknownFieldBags() { return unknownFieldBags; }
    public Map<CoinStore, CoinStoreFreshness> getCoinStores() { return coinStores; }
    public CoinStoreFreshness getCoinStoreFreshness(CoinStore store)
    {
        return store == null ? CoinStoreFreshness.UNOBSERVED
            : coinStores.getOrDefault(store, CoinStoreFreshness.UNOBSERVED);
    }
}
