package com.gpmanager.persistence;

import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.GoalDefinition;
import com.gpmanager.model.TileLayout;
import com.gpmanager.model.DailyRollup;
import com.gpmanager.model.WealthSnapshotHistory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SavedState implements com.gpmanager.persistence.UnknownFieldPreservation.RetainsUnknownFields
{
    /** Unknown JSON fields carried through load → save (pass 10 step 43); never persisted directly. */
    private transient java.util.Map<String, com.google.gson.JsonElement> unknownJsonFields = java.util.Collections.emptyMap();

    @Override
    public java.util.Map<String, com.google.gson.JsonElement> getUnknownJsonFields()
    {
        return unknownJsonFields == null ? java.util.Collections.emptyMap() : unknownJsonFields;
    }

    @Override
    public void setUnknownJsonFields(java.util.Map<String, com.google.gson.JsonElement> fields)
    {
        unknownJsonFields = fields == null || fields.isEmpty() ? java.util.Collections.emptyMap() : new java.util.LinkedHashMap<>(fields);
    }

    /** Schema 10 adds account ownership, 11 runs, 12 prices, 13 goals/layout, 14 cost splits, 15 receipt retention, 16 profile zone, 17 daily rollups, 18 wealth history, 19 categories, 20 encounter summaries, 21 hourly Insights coverage, 22 PK location ledger, 23 typed session owner and named-start coverage. */
    public static final int CURRENT_SCHEMA_VERSION = 23;

    private int schemaVersion = CURRENT_SCHEMA_VERSION;
    private long savedAtEpochMillis;
    private long revision;
    /** RS profile key that owns this file; null/empty means unassigned legacy. */
    private String ownerKey;
    // Legacy active-session field. It is read for migration but new saves use
    // the explicit general/custom ownership fields below.
    private ProfitSession activeSession;
    private ProfitSession generalSession;
    private ProfitSession customSession;
    private boolean generalSuspendedByCustom;
    private List<ProfitSession> history = new ArrayList<>();
    /** Goal definitions persist with this account profile; progress is derived from scoped totals. */
    private List<GoalDefinition> goalDefinitions = new ArrayList<>();
    /** Empty layout means each consumer uses its built-in order and shows every tile. */
    private TileLayout tileLayout = TileLayout.legacyDefaults();
    /** UTC date of the last scheduled receipt-retention sweep, empty before first tick. */
    private String lastReceiptRetentionDayUtc = "";
    /** Old-schema receipts wait for the first UTC day change before compaction. */
    private boolean receiptRetentionDeferredUntilDayChange;
    /** IANA timezone captured for this profile's local-date analytics. Empty means legacy/uninitialized. */
    private String profileTimeZoneId = "";
    /** Profile-level daily rollups; kept beside sessions so Insights is not receipt-backed. */
    private List<DailyRollup> dailyRollups = new ArrayList<>();
    /** Read-only profile-local wealth history; never contributes to accounting Net. */
    private WealthSnapshotHistory wealthSnapshotHistory = WealthSnapshotHistory.empty();

    public SavedState()
    {
    }

    public SavedState(ProfitSession activeSession, List<ProfitSession> history)
    {
        this.savedAtEpochMillis = System.currentTimeMillis();
        this.activeSession = activeSession;
        this.history = new ArrayList<>(history);
    }

    public SavedState(
        ProfitSession generalSession,
        ProfitSession customSession,
        boolean generalSuspendedByCustom,
        List<ProfitSession> history)
    {
        this.savedAtEpochMillis = System.currentTimeMillis();
        this.generalSession = generalSession;
        this.customSession = customSession;
        this.generalSuspendedByCustom = generalSuspendedByCustom;
        this.history = new ArrayList<>(history == null ? new ArrayList<>() : history);
    }

    public int getSchemaVersion()
    {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion)
    {
        this.schemaVersion = schemaVersion;
    }

    public long getSavedAtEpochMillis()
    {
        return savedAtEpochMillis;
    }

    public void setSavedAtEpochMillis(long savedAtEpochMillis)
    {
        this.savedAtEpochMillis = savedAtEpochMillis;
    }

    public long getRevision()
    {
        return revision;
    }

    public void setRevision(long revision)
    {
        this.revision = revision;
    }

    public String getOwnerKey()
    {
        return ownerKey;
    }

    public void setOwnerKey(String ownerKey)
    {
        this.ownerKey = ownerKey;
    }

    public ProfitSession getActiveSession()
    {
        return activeSession != null ? activeSession : customSession != null ? customSession : generalSession;
    }

    public ProfitSession getGeneralSession()
    {
        return generalSession;
    }

    public ProfitSession getCustomSession()
    {
        return customSession;
    }

    public boolean isGeneralSuspendedByCustom()
    {
        return generalSuspendedByCustom;
    }

    public boolean hasExplicitSessionOwners()
    {
        return generalSession != null || customSession != null;
    }

    public List<ProfitSession> getHistory()
    {
        if (history == null)
        {
            history = new ArrayList<>();
        }
        return history;
    }

    public List<GoalDefinition> getGoalDefinitions()
    {
        if (goalDefinitions == null)
        {
            goalDefinitions = new ArrayList<>();
        }
        List<GoalDefinition> copy = new ArrayList<>(goalDefinitions.size());
        for (GoalDefinition definition : goalDefinitions)
        {
            if (definition != null)
            {
                copy.add(definition.copy());
            }
        }
        return Collections.unmodifiableList(copy);
    }

    public void setGoalDefinitions(List<GoalDefinition> definitions)
    {
        goalDefinitions = new ArrayList<>();
        if (definitions != null)
        {
            for (GoalDefinition definition : definitions)
            {
                if (definition != null)
                {
                    goalDefinitions.add(definition.copy());
                }
            }
        }
    }

    public TileLayout getTileLayout()
    {
        return tileLayout == null ? TileLayout.legacyDefaults() : new TileLayout(tileLayout.getPages());
    }

    public void setTileLayout(TileLayout tileLayout)
    {
        this.tileLayout = tileLayout == null ? TileLayout.legacyDefaults() : new TileLayout(tileLayout.getPages());
    }

    public String getLastReceiptRetentionDayUtc()
    {
        return lastReceiptRetentionDayUtc == null ? "" : lastReceiptRetentionDayUtc;
    }

    public void setLastReceiptRetentionDayUtc(String day)
    {
        lastReceiptRetentionDayUtc = day == null ? "" : day.trim();
    }

    public boolean isReceiptRetentionDeferredUntilDayChange()
    {
        return receiptRetentionDeferredUntilDayChange;
    }

    public void setReceiptRetentionDeferredUntilDayChange(boolean deferred)
    {
        receiptRetentionDeferredUntilDayChange = deferred;
    }

    public String getProfileTimeZoneId()
    {
        return profileTimeZoneId == null ? "" : profileTimeZoneId;
    }

    public void setProfileTimeZoneId(String value)
    {
        profileTimeZoneId = value == null ? "" : value.trim();
    }

    public List<DailyRollup> getDailyRollups()
    {
        return dailyRollups == null ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(dailyRollups));
    }

    public void setDailyRollups(List<DailyRollup> values)
    {
        dailyRollups = new ArrayList<>();
        if (values != null)
        {
            for (DailyRollup value : values)
            {
                if (value != null) dailyRollups.add(value);
            }
        }
    }

    /** A client-observed coin store (pass 10 step 42); absent on saves that predate it. */
    public static final class CoinStoreRecord
    {
        private String store;
        private long value;
        private long observedAtEpochMillis;
        /** The owner said this store is not part of their wealth; it counts as zero, never as missing. */
        private boolean unused;

        public CoinStoreRecord()
        {
        }

        public CoinStoreRecord(String store, long value, long observedAtEpochMillis)
        {
            this(store, value, observedAtEpochMillis, false);
        }

        public CoinStoreRecord(String store, long value, long observedAtEpochMillis, boolean unused)
        {
            this.store = store;
            this.value = value;
            this.observedAtEpochMillis = observedAtEpochMillis;
            this.unused = unused;
        }

        public String getStore() { return store == null ? "" : store; }
        public long getValue() { return value; }
        public long getObservedAtEpochMillis() { return observedAtEpochMillis; }
        public boolean isUnused() { return unused; }
    }

    private List<CoinStoreRecord> coinStores;

    public List<CoinStoreRecord> getCoinStores()
    {
        return coinStores == null ? Collections.emptyList() : Collections.unmodifiableList(coinStores);
    }

    public void setCoinStores(List<CoinStoreRecord> values)
    {
        coinStores = values == null || values.isEmpty() ? null : new ArrayList<>(values);
    }

    public WealthSnapshotHistory getWealthSnapshotHistory()
    {
        return wealthSnapshotHistory == null ? WealthSnapshotHistory.empty()
            : wealthSnapshotHistory.copyForPersistence();
    }

    public void setWealthSnapshotHistory(WealthSnapshotHistory value)
    {
        wealthSnapshotHistory = value == null ? WealthSnapshotHistory.empty()
            : value.copyForPersistence();
    }
}
