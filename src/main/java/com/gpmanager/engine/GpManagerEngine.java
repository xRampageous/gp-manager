package com.gpmanager.engine;

import com.gpmanager.GpManagerConfig;
import com.gpmanager.LootPresentationFilter;
import com.gpmanager.ReceiptRetentionPeriod;
import com.gpmanager.grounditems.ContributionEligibility;
import com.gpmanager.grounditems.LootPresentationFilterService;
import com.gpmanager.model.ActionKind;
import com.gpmanager.model.AlertEvent;
import com.gpmanager.model.AlertKind;
import com.gpmanager.model.AlertPolicy;
import com.gpmanager.model.ActivityLifetimeMetrics;
import com.gpmanager.model.ActivityAverageSnapshot;
import com.gpmanager.model.AccountingProjection;
import com.gpmanager.model.ClassificationConfidence;
import com.gpmanager.model.ChargeLoadReviewProvenance;
import com.gpmanager.model.DeferredClaimProvenance;
import com.gpmanager.model.DeathReclaimStatus;
import com.gpmanager.model.GoalDefinition;
import com.gpmanager.model.GoalProgress;
import com.gpmanager.model.HistoryDateRange;
import com.gpmanager.model.HistoryQuery;
import com.gpmanager.model.HistorySort;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.KeyChestCatalogue;
import com.gpmanager.model.LootKeyProvenance;
import com.gpmanager.model.PkEncounter;
import com.gpmanager.model.PkEncounterType;
import com.gpmanager.model.PkPlaceSummary;
import com.gpmanager.model.PkPlaceSummaries;
import com.gpmanager.model.PkMetrics;
import com.gpmanager.model.PkLocationLedger;
import com.gpmanager.model.PkWindow;
import com.gpmanager.model.PauseReason;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.ReviewDecision;
import com.gpmanager.model.ReviewEligibility;
import com.gpmanager.model.ReviewInbox;
import com.gpmanager.model.ReviewRow;
import com.gpmanager.model.CorrectionRecord;
import com.gpmanager.model.CoinStore;
import com.gpmanager.model.DataHealthSnapshot;
import com.gpmanager.model.ProfileSizeEstimate;
import com.gpmanager.model.ReceiptRetentionStatus;
import com.gpmanager.model.RecordsSnapshot;
import com.gpmanager.model.SessionComparison;
import com.gpmanager.model.Run;
import com.gpmanager.model.RunComparisonSnapshot;
import com.gpmanager.model.RunHistorySnapshot;
import com.gpmanager.model.SessionCategory;
import com.gpmanager.model.SessionEndReason;
import com.gpmanager.model.SessionOwnerKind;
import com.gpmanager.model.SessionComparisonMetrics;
import com.gpmanager.model.SessionIntelligenceSnapshot;
import com.gpmanager.model.SessionSummary;
import com.gpmanager.model.SessionMetrics;
import com.gpmanager.model.ProfitTrendDirection;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TrackingDaySummary;
import com.gpmanager.model.DailyRollup;
import com.gpmanager.model.InsightsWindowSnapshot;
import com.gpmanager.model.OverallTotalsSnapshot;
import com.gpmanager.model.SessionNetTrendEntry;
import com.gpmanager.model.TrackingGainedItemDetail;
import com.gpmanager.model.TrackingInsightsSnapshot;
import com.gpmanager.model.WealthAnchor;
import com.gpmanager.model.WealthChangeBreakdown;
import com.gpmanager.model.WealthChangeFacts;
import com.gpmanager.model.WealthBreakdown;
import com.gpmanager.model.WealthLocationSnapshot;
import com.gpmanager.model.WealthLocationsSnapshot;
import com.gpmanager.model.WealthSnapshotHistory;
import com.gpmanager.model.WealthTrendPoint;
import com.gpmanager.model.WealthTopMover;
import com.gpmanager.model.LatestWealthSnapshot;
import com.gpmanager.model.TileLayout;
import com.gpmanager.model.TransactionCorrection;
import com.gpmanager.model.TransactionType;
import com.gpmanager.persistence.SavedState;
import com.gpmanager.persistence.ProfileBackup;
import com.gpmanager.persistence.ProfileBackupReport;
import com.gpmanager.diagnostics.DebugTrace;
import com.gpmanager.engine.evidence.TimedEvidence;
import com.gpmanager.engine.evidence.ChargeLoadTransferEvidence;
import com.gpmanager.engine.evidence.ChargeRecipeCatalogue;
import com.gpmanager.engine.evidence.ChargeFamilyIds;
import com.gpmanager.engine.evidence.MeasuredChargeRead;
import com.gpmanager.engine.evidence.MeasuredChargeDelta;
import com.gpmanager.engine.evidence.MeasuredChargeReadTracker;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class GpManagerEngine
{
    private static final int MINIMUM_BASELINE_WARMUP_TICKS = 3;
    private static final int ALERT_RING_CAPACITY = 50;
    private static final int MAX_WEALTH_MILESTONE_EVENTS_PER_READ = 50;
    /** Maximum IANA civil-date label difference between two legal UTC offsets. */
    private static final int MAX_ZONE_DATE_LABEL_DELTA_DAYS = 2;
    private static final String UNCLASSIFIED_LOCAL_DEATH_NOTE = "__unclassified-local-death__";

    private final FlowValuator valuationService;
    private final TransactionClassifier classifier;
    private final GpManagerConfig config;
    /** Recent accepted alerts are deliberately transient and never enter SavedState. */
    private final Deque<AlertEvent> recentAlerts = new ArrayDeque<>();
    private final List<Consumer<AlertEvent>> alertListeners = new ArrayList<>();
    private AlertPolicy alertPolicy = AlertPolicy.allEnabled();
    private long nextAlertSequenceId;
    private int sessionIdleAutoEndMinutes;
    @Nullable
    private Long lastCompleteWealthTotalGp;
    private int lastWealthMilestoneStepGp;
    private final Map<String, GoalAlertObservation> goalAlertObservations = new HashMap<>();
    private final Set<String> goalAlertSent = new HashSet<>();
    private static final class GoalAlertObservation
    {
        private final String definitionFingerprint;
        private boolean reached;
        private boolean needsBaseline;

        private GoalAlertObservation(String definitionFingerprint, boolean reached)
        {
            this.definitionFingerprint = definitionFingerprint;
            this.reached = reached;
        }
    }
    @Nullable
    private LootPresentationFilterService contributionEligibility;

    private List<ProfitSession> history = new ArrayList<>();
    private final List<LootExpectation> lootExpectations = new ArrayList<>();
    private List<GoalDefinition> goalDefinitions = new ArrayList<>();
    private TileLayout tileLayout = TileLayout.legacyDefaults();
    /** Last scheduled receipt-retention sweep date, stored as an ISO UTC date. */
    private String lastReceiptRetentionDayUtc = "";
    /** Old-schema receipt detail is first compacted after a later UTC day change. */
    private boolean receiptRetentionDeferredUntilDayChange;
    /** Profile-local date basis; persisted so travelling or changing the host zone cannot shift history. */
    private String profileTimeZoneId = ZoneId.systemDefault().getId();
    /** Profile-level view rebuilt only from bounded session-day aggregates, never receipts. */
    private List<DailyRollup> dailyRollups = new ArrayList<>();
    /** Bumped by every mutation that can change a day rollup; the cache below is keyed on it. */
    private long rollupGeneration;
    private long rollupCacheGeneration = -1L;
    private String rollupCacheZoneId = "";
    private List<DailyRollup> rollupCache;
    private int rollupRebuilds;
    /** Day summaries clamped on the last restore (pass 10 step 44). */
    private int repairedActiveTimeDays;
    /** Last persisted rollups preserve days no longer represented by retained sessions. */
    private List<DailyRollup> persistedDailyRollupBaseline = new ArrayList<>();
    /** Derived per-(local date, zone) totals, keyed by stable source signatures. */
    private final Map<String, OverallDayCacheEntry> overallDayCache = new HashMap<>();
    private long overallCacheGeneration;
    private long overallCacheBuiltGeneration = -1L;
    private long overallCacheAsOfNow;
    private String overallCacheZoneId = "";
    private boolean overallCacheFiltered;
    private boolean overallCacheMissingStartTimestamp;
    private boolean overallCacheUnknownNamedStartTimestamp;
    private OverallTotalsSnapshot overallAllTimeCache;
    private final Set<String> overallFutureDayKeys = new HashSet<>();
    /** Bounded profile-local point-in-time wealth reads; always excluded from Net. */
    private WealthSnapshotHistory wealthSnapshotHistory = WealthSnapshotHistory.empty();
    private final Map<CoinStore, CoinStoreObservation> coinStores = new EnumMap<>(CoinStore.class);
    public static final long COIN_STORE_FRESHNESS_MILLIS = 7L * 24L * 60L * 60L * 1000L;
    /** Active RuneScape profile key supplied by the persistence coordinator. */
    private String profileIdentityKey = "";

    // The general tracker is durable. A custom session is the only temporary
    // owner and takes the active pointer while General is explicitly paused.
    private ProfitSession generalSession;
    private ProfitSession customSession;
    private boolean generalSuspendedByCustom;
    private ProfitSession activeSession;
    /**
     * Set by {@link #resetTrackingData(long)} so Automatic tracking can re-arm a
     * fresh stopped General after data/factory reset. Cleared by ordinary
     * {@link #stop(long)}, {@link #restartGeneral(long, boolean, boolean)}, and
     * successful auto-start. Manual Pause never sets this.
     */
    private boolean autoStartEligibleAfterReset;
    private ContainerSnapshot baseline;

    private boolean baselinePriming;
    private ContainerSnapshot primingSnapshot;
    private int primingObservedTicks;
    private int primingStableTicks;

    private boolean dirty;
    private ContainerSnapshot pendingSnapshot;
    private int pendingStableTicks;

    private TrackingContext context = TrackingContext.GENERIC;
    private int contextTicks;
    private String contextNote = "";
    private Map<Integer, Long> contextExpectedLoot = Collections.emptyMap();
    private String contextEncounterId;

    private TrackingContext pendingContext = TrackingContext.GENERIC;
    private GeBookingMode geBookingMode = GeBookingMode.PROVENANCE_ONLY;
    private String pendingContextNote = "";
    private Map<Integer, Long> pendingExpectedLoot = Collections.emptyMap();
    private String pendingEncounterId;
    /** One-shot display-only snapshot captured at the local death event. */
    @Nullable
    private LocalDeathEvidence pendingLocalDeathEvidence;
    /*
     * --- Per-source bank-transfer evidence lifetimes ---
     *
     * Fixes the documented P1: bone burial (or any other consumption) that
     * settles after the bank UI has closed must classify as CONSUMPTION, not
     * TRANSFER. Two distinct evidence sources are tracked below, each with
     * its own lifetime, precisely because they mean different things:
     *
     *   - SOFT evidence (`transferEvidenceTicks` / `bankInterfaceOpen`) only
     *     means "the bank widget happened to be visible this tick." It is a
     *     weak, one-sided signal and is dropped immediately on bank close
     *     (see `markBankInterfaceClosed`) so it can never outlive the UI and
     *     misclassify an unrelated burial/eat/drink/cast that settles after
     *     close.
     *   - HARD evidence (`hardTransferContextActive` / `hardTransferEvidenceTicks`)
     *     means a real bank-container/menu deposit or withdrawal was
     *     confirmed. It survives bank close for a short, bounded TTL so a
     *     same-tick race between the bank-container event and the inventory
     *     dirty callback still books the transfer correctly.
     *
     * A stale soft latch can never poison a burial after close; only live
     * hard evidence can. See `com.gpmanager.engine.evidence.TimedEvidence`
     * for the same per-source-lifetime pattern applied to consumption/drop
     * intent, and `ConsumptionBurialEngineTest` /
     * `FollowupBoundaryReviewTest#burialCallbackBeforeGameTickBankCloseMustNotLatchOldTransfer`
     * for the regression coverage that locks this in.
     */
    /** True when bank/deposit evidence (soft or hard) occurred during the active pending change. */
    private boolean pendingTransferEvidence;
    /**
     * Hard evidence from a bank-container or deposit/withdraw menu event during
     * this pending change. Soft UI-open ticks alone never set this — otherwise
     * an inventory callback that arrives before the close tick would keep a
     * stale TRANSFER latch over a burial.
     */
    private boolean pendingHardTransferEvidence;
    /**
     * Hard TRANSFER from bank-container/menu markContext. Survives soft bank-close
     * and idle consumeContext so a GameTick between bank-container and inventory
     * does not book the deposit as CONSUMPTION. Cleared on settle or TTL expiry.
     */
    private boolean hardTransferContextActive;
    /** Remaining ticks for {@link #hardTransferContextActive}. */
    private int hardTransferEvidenceTicks;
    /**
     * Fresh bank-interface / deposit-menu evidence (not sticky loot correlation).
     * Soft-only: cleared unconditionally on bank close unless hard evidence is
     * also live (see {@link #markBankInterfaceClosed()}), so it never survives
     * past the UI closing to poison a later burial/consumption.
     */
    private int transferEvidenceTicks;
    private boolean bankInterfaceOpen;
    @Nullable
    private ConsumptionIntent consumptionIntent;
    private final DeathReclaimLifecycle deathReclaim = new DeathReclaimLifecycle();
    /** Item-on-item charge-load intent; only matched settled component losses are neutral. */
    private final ChargeLoadTransferEvidence chargeLoadTransferEvidence = new ChargeLoadTransferEvidence();
    /** Session-owned measured balances; never persisted or shared across owners. */
    private final MeasuredChargeReadTracker measuredChargeReadTracker = new MeasuredChargeReadTracker();
    private final Map<Integer, Long> neutralZoneStoredItems = new HashMap<>();
    private boolean neutralZoneTransferActive;
    private boolean captureNeutralZoneEntry;
    @Nullable
    private ContainerSnapshot neutralZoneEntryBaseline;
    private int neutralZoneEntryCaptureTicks;
    private int neutralZoneRestoreTicks;
    private long consumptionIntentGeneration;
    @Nullable
    private DropIntent dropIntent;
    private final Deque<OwnDropRecord> ownDrops = new ArrayDeque<>();
    private static final int MAX_OWN_DROPS = 32;
    /** FIFO GE sell offers waiting for Collect — used for tax XOR booking. */
    private final Deque<GeSellTaxBooking.PendingSale> pendingGeSales = new ArrayDeque<>();
    private static final int MAX_PENDING_GE_SALES = 64;
    /** One counted LOOT/PK_LOOT revenue per encounter id. */
    /** Active clue path for dig/tele/key cost pairing. */
    private final ClueCostPairing clueCostPairing = new ClueCostPairing();
    /** Live wilderness loot-key state. */
    private final LootKeyLifecycle lootKeyLifecycle = new LootKeyLifecycle();
    private final DeferredChestClaimLifecycle deferredChestClaimLifecycle =
        new DeferredChestClaimLifecycle();
    /** Chebyshev tiles — pickup must be near the recorded drop, not name-only. */
    private static final int OWN_DROP_MATCH_RADIUS = 8;
    private static final int OWN_DROP_TTL_TICKS = 200;
    private int playerWorldX;
    private int playerWorldY;
    private int playerWorldPlane;
    private boolean playerLocationKnown;
    @Nullable
    private String playerLocationLabel;

    @Nullable
    private DebugTrace debugTrace;

    @Inject
    public GpManagerEngine(
        ItemValuationService valuationService,
        TransactionClassifier classifier,
        GpManagerConfig config)
    {
        this((FlowValuator) valuationService, classifier, config);
    }

    public GpManagerEngine(
        FlowValuator valuationService,
        TransactionClassifier classifier,
        GpManagerConfig config)
    {
        this.valuationService = valuationService;
        this.classifier = classifier;
        this.config = config;
    }

    @Inject
    public void setDebugTrace(DebugTrace debugTrace)
    {
        this.debugTrace = debugTrace;
    }

    /** Replaces the transient event policy; null restores the all-enabled default. */
    public synchronized void setAlertPolicy(AlertPolicy policy)
    {
        alertPolicy = policy == null ? AlertPolicy.allEnabled() : policy;
    }

    public synchronized AlertPolicy getAlertPolicy()
    {
        return alertPolicy;
    }

    public synchronized void addAlertListener(Consumer<AlertEvent> listener)
    {
        if (listener != null && !alertListeners.contains(listener)) alertListeners.add(listener);
    }

    public synchronized void removeAlertListener(Consumer<AlertEvent> listener)
    {
        if (listener != null) alertListeners.remove(listener);
    }

    /**
     * Records an enabled alert in the bounded, newest-first, non-persisted ring
     * and notifies listeners. Sidebar-owned events such as SUPPLIES_LOW use this
     * entry point; listener failures are isolated from accounting work.
     *
     * @return true only when the event was accepted by the current policy
     */
    public boolean recordAlert(AlertEvent event)
    {
        AlertEvent recorded;
        List<Consumer<AlertEvent>> listeners;
        synchronized (this)
        {
            if (event == null || event.getKind() == null || alertPolicy == null
                || !alertPolicy.isEnabled(event.getKind()))
            {
                return false;
            }
            nextAlertSequenceId = nextAlertSequenceId == Long.MAX_VALUE
                ? 1L : nextAlertSequenceId + 1L;
            recorded = event.withSequenceId(nextAlertSequenceId);
            if (recentAlerts.size() >= ALERT_RING_CAPACITY) recentAlerts.removeFirst();
            recentAlerts.addLast(recorded);
            listeners = new ArrayList<>(alertListeners);
        }
        for (Consumer<AlertEvent> listener : listeners)
        {
            try
            {
                listener.accept(recorded);
            }
            catch (RuntimeException ignored)
            {
                // Presentation listeners cannot interrupt transaction ingestion.
            }
        }
        return true;
    }

    /** Newest first. Reading the stream never consumes or changes an event. */
    public synchronized List<AlertEvent> getRecentAlerts(int limit)
    {
        if (limit <= 0 || recentAlerts.isEmpty()) return Collections.emptyList();
        List<AlertEvent> result = new ArrayList<>(Math.min(limit, recentAlerts.size()));
        java.util.Iterator<AlertEvent> iterator = recentAlerts.descendingIterator();
        while (iterator.hasNext() && result.size() < limit) result.add(iterator.next());
        return Collections.unmodifiableList(result);
    }

    /** Zero disables ending custom sessions after idle; free play is never ended here. */
    public synchronized void setSessionIdleAutoEnd(int minutes)
    {
        sessionIdleAutoEndMinutes = Math.max(0, minutes);
    }

    public synchronized int getSessionIdleAutoEndMinutes()
    {
        return sessionIdleAutoEndMinutes;
    }

    /** Set immediately before a client-owned custom-session close (MANUAL or BOUNDARY). */
    public synchronized void setActiveSessionEndReason(SessionEndReason reason)
    {
        if (activeSession != null && !activeSession.isClosed())
        {
            activeSession.setEndReason(reason);
        }
    }

    @Inject
    void setContributionEligibility(LootPresentationFilterService contributionEligibility)
    {
        this.contributionEligibility = contributionEligibility;
    }

    public synchronized void restore(SavedState state)
    {
        restore(state, System.currentTimeMillis());
    }

    /** Restore using a supplied clock so retention and recovery rules are deterministic in tests. */
    /** Top-level fields of the loaded SavedState this build does not know; written back unchanged. */
    private java.util.Map<String, com.google.gson.JsonElement> unknownSavedStateFields = java.util.Collections.emptyMap();
    private String lastRecoveredFrom = "";
    private int rotatedBackupCount;

    public synchronized void restore(SavedState state, long now)
    {
        clearTransientAlertState();
        unknownSavedStateFields = state == null ? java.util.Collections.emptyMap()
            : com.gpmanager.persistence.UnknownFieldPreservation.copyOf(state.getUnknownJsonFields());
        history.clear();
        goalDefinitions = new ArrayList<>();
        tileLayout = TileLayout.legacyDefaults();
        lastReceiptRetentionDayUtc = "";
        receiptRetentionDeferredUntilDayChange = false;
        profileTimeZoneId = ZoneId.systemDefault().getId();
        dailyRollups = new ArrayList<>();
        persistedDailyRollupBaseline = new ArrayList<>();
        invalidateOverallTotalsCache();
        wealthSnapshotHistory = WealthSnapshotHistory.empty();
        generalSession = null;
        customSession = null;
        generalSuspendedByCustom = false;
        activeSession = null;
        clearOwnDropState();
        consumptionIntent = null;
        if (state != null)
        {
            setGoalDefinitions(state.getGoalDefinitions());
            setTileLayout(state.getTileLayout());
            profileTimeZoneId = validTimeZone(state.getProfileTimeZoneId())
                ? state.getProfileTimeZoneId() : ZoneId.systemDefault().getId();
            persistedDailyRollupBaseline = new ArrayList<>(state.getDailyRollups());
            dailyRollups = new ArrayList<>(persistedDailyRollupBaseline);
            wealthSnapshotHistory = state.getWealthSnapshotHistory().compact(now);
            coinStores.clear();
            coinStoresUnused.clear();
            for (SavedState.CoinStoreRecord record : state.getCoinStores())
            {
                CoinStore store = record == null ? null : CoinStore.forLocationId(record.getStore());
                if (store == null) continue;
                if (record.isUnused()) coinStoresUnused.add(store);
                else if (record.getValue() >= 0L && record.getObservedAtEpochMillis() > 0L)
                {
                    coinStores.put(store, new CoinStoreObservation(record.getValue(), record.getObservedAtEpochMillis()));
                }
            }
            repairedActiveTimeDays = 0;
            Set<String> repairedSessionIds = new HashSet<>();
            for (ProfitSession session : state.getHistory())
            {
                if (session == null) continue;
                int repaired = session.repairInflatedActiveTime();
                if (repaired > 0)
                {
                    repairedActiveTimeDays += repaired;
                    repairedSessionIds.add(session.getId());
                }
            }
            if (!repairedSessionIds.isEmpty())
            {
                // The persisted day rows carry the same inflation; they are rebuilt from the
                // repaired sessions instead of being trusted over them.
                persistedDailyRollupBaseline.removeIf(rollup -> rollup != null
                    && !Collections.disjoint(rollup.getSourceSessionIds(), repairedSessionIds));
                dailyRollups = new ArrayList<>(persistedDailyRollupBaseline);
            }
            lastReceiptRetentionDayUtc = state.getLastReceiptRetentionDayUtc();
            receiptRetentionDeferredUntilDayChange = state.isReceiptRetentionDeferredUntilDayChange()
                || state.getSchemaVersion() < SavedState.CURRENT_SCHEMA_VERSION;
            if (state.getSchemaVersion() < SavedState.CURRENT_SCHEMA_VERSION
                && lastReceiptRetentionDayUtc.isEmpty())
            {
                // An old profile must get one full post-restore UTC day before its
                // detail is compacted, even when the save file is several days old.
                lastReceiptRetentionDayUtc = Instant.ofEpochMilli(now)
                    .atZone(ZoneOffset.UTC).toLocalDate().toString();
            }
            for (ProfitSession session : state.getHistory())
            {
                if (session != null)
                {
                    history.add(session);
                }
            }
            if (state.hasExplicitSessionOwners())
            {
                generalSession = state.getGeneralSession();
                customSession = state.getCustomSession();
                if (generalSession != null) generalSession.setOwnerKind(SessionOwnerKind.FREE_PLAY);
                if (customSession != null) customSession.setOwnerKind(SessionOwnerKind.NAMED_SESSION);
                generalSuspendedByCustom = state.isGeneralSuspendedByCustom();
                // Schema 8 recorded this relationship as a boolean. Promote
                // it to owner-local pause metadata without touching either
                // session or its history.
                if (generalSuspendedByCustom && generalSession != null && customSession != null
                    && generalSession.isPaused() && generalSession.getPauseReason() == PauseReason.MANUAL)
                {
                    generalSession.setPauseReason(PauseReason.CUSTOM_SESSION);
                }
            }
            else
            {
                // Schema 7 and earlier had a single active session. Retain
                // that object exactly, but its named/free-play provenance is
                // unknowable and must not be inferred from its mutable label.
                generalSession = state.getActiveSession();
                if (generalSession != null) generalSession.setOwnerKind(SessionOwnerKind.UNKNOWN);
            }
            if (state.getSchemaVersion() < SavedState.CURRENT_SCHEMA_VERSION)
            {
                // Old history mixes archived custom and restarted Free play
                // owners without a durable discriminator.
                for (ProfitSession archived : history)
                    if (archived != null) archived.setOwnerKind(SessionOwnerKind.UNKNOWN);
            }
            migrateDurableOwnerName();
            for (ProfitSession session : uniqueProfileSessions())
            {
                attachOverallTotalsInvalidationListener(session);
                session.migrateCategoryOverrideFromTags();
                if (state.getSchemaVersion() < 20)
                {
                    session.markLegacyEncounterHistoryUnavailable(now, profileTimeZoneId);
                }
                boolean analyticsNeedsRebase = state.getSchemaVersion() < 16
                    || !profileTimeZoneId.equals(session.getAnalyticsTimeZoneId());
                session.configureAnalyticsTimeZone(profileTimeZoneId, analyticsNeedsRebase, now);
            }
            activeSession = customSession != null && !customSession.isClosed()
                ? customSession : generalSession;
            if (activeSession != null && !activeSession.isClosed())
            {
                activeSession.markRecoveredFromCrash();
                if (!activeSession.isPaused())
                {
                    long savedAt = state.getSavedAtEpochMillis();
                    activeSession.pause(savedAt > 0L ? Math.min(now, savedAt) : now, PauseReason.RECOVERY);
                }
                else if (!activeSession.isStopped())
                {
                    activeSession.setPauseReason(PauseReason.RECOVERY);
                }
            }
        }
        // Reclaim evidence is ephemeral and belongs to the identity being replaced;
        // never carry one account's lost-item whitelist into another account's rows.
        deathReclaim.reset();
        resetTrackingState(false);
        autoStartEligibleAfterReset = false;
        trimHistory();
        if (state != null && !receiptRetentionDeferredUntilDayChange)
        {
            ReceiptRetentionPeriod period = config.receiptRetentionDays();
            int days = period == null ? ReceiptRetentionPeriod.DAYS_90.getDays() : period.getDays();
            if (days > 0)
            {
                compactOlderThan(days, now);
            }
            lastReceiptRetentionDayUtc = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC)
                .toLocalDate().toString();
        }
        refreshDailyRollups();
    }


    /** One-time display rename General → Overall for restored durable owners. */
    private void migrateDurableOwnerName()
    {
        if (generalSession != null
            && com.gpmanager.ui.SessionOwnerLabels.LEGACY_DURABLE_OWNER_NAME
                .equalsIgnoreCase(generalSession.getName()))
        {
            generalSession.rename(com.gpmanager.ui.SessionOwnerLabels.DURABLE_OWNER_NAME);
        }
        for (ProfitSession archived : history)
        {
            if (archived != null
                && com.gpmanager.ui.SessionOwnerLabels.LEGACY_DURABLE_OWNER_NAME
                    .equalsIgnoreCase(archived.getName()))
            {
                archived.rename(com.gpmanager.ui.SessionOwnerLabels.DURABLE_OWNER_NAME);
            }
        }
    }

    public synchronized void ensureSession(long now)
    {
        if (generalSession == null || generalSession.isClosed())
        {
            // Overall is the durable default owner display name; it must still
            // accept automatic activity detection just as the former General default
            // session did.
            generalSession = newProfileSession(com.gpmanager.ui.SessionOwnerLabels.DURABLE_OWNER_NAME,
                com.gpmanager.model.SessionMode.AUTO, now, SessionOwnerKind.FREE_PLAY);
        }
        if (customSession == null || customSession.isClosed())
        {
            customSession = null;
            activeSession = generalSession;
        }
    }

    public synchronized void startNewSession(String name, long now)
    {
        startCustomSession(name, com.gpmanager.model.SessionMode.AUTO, now);
    }

    public synchronized void startCustomSession(
        String name,
        com.gpmanager.model.SessionMode mode,
        long now)
    {
        ensureSession(now);
        if (customSession != null)
        {
            // This transition needs an explicit Finish custom first. Starting
            // another custom session must never silently archive the current one.
            return;
        }
        else if (!generalSession.isPaused())
        {
            generalSession.pause(now, PauseReason.CUSTOM_SESSION);
            generalSuspendedByCustom = true;
        }
        else
        {
            generalSuspendedByCustom = false;
        }
        customSession = newProfileSession(name,
            mode == null || mode == com.gpmanager.model.SessionMode.GENERAL
                ? com.gpmanager.model.SessionMode.AUTO : mode, now, SessionOwnerKind.NAMED_SESSION);
        activeSession = customSession;
        resetTrackingState(true);
    }

    /** Persistence coordinator supplies explicit recovery facts; no file probing occurs here. */
    public synchronized void setPersistenceRecoveryHealth(String recoveredFrom, int rotatedBackups)
    {
        lastRecoveredFrom = recoveredFrom == null ? "" : recoveredFrom;
        rotatedBackupCount = Math.max(0, rotatedBackups);
    }

    public synchronized boolean finishCustomSession(long now)
    {
        if (customSession == null)
        {
            return false;
        }
        SessionEndReason reason = customSession.getEndReason();
        return finishCustomSessionAt(now, now,
            reason == null ? SessionEndReason.MANUAL : reason, false);
    }

    /** Finish a named session with an explicit client-owned reason. */
    public synchronized boolean finishCustomSession(long now, SessionEndReason reason)
    {
        if (customSession == null) return false;
        return finishCustomSessionAt(now, now,
            reason == null ? SessionEndReason.MANUAL : reason, false);
    }

    private boolean finishCustomSessionAt(long endAt, long resumeGeneralAt,
        SessionEndReason reason, boolean autoEnded)
    {
        if (customSession == null) return false;
        long closeAt = Math.max(customSession.getStartedAtEpochMillis(), endAt);
        String endedSessionId = customSession.getId();
        String endedSessionName = customSession.getName();
        evaluateGoalAlerts(closeAt, false);
        customSession.setEndReason(reason);
        archiveCustom(closeAt);
        pruneGoalAlertStateForSession(endedSessionId);
        customSession = null;
        activeSession = generalSession;
        if (generalSession != null && generalSession.getPauseReason() == PauseReason.CUSTOM_SESSION)
        {
            generalSession.resume(Math.max(closeAt, resumeGeneralAt));
        }
        generalSuspendedByCustom = false;
        resetTrackingState(true);
        if (autoEnded)
        {
            long minutes = sessionIdleAutoEndMinutes;
            recordAlert(new AlertEvent(AlertKind.SESSION_AUTO_ENDED, resumeGeneralAt,
                endedSessionId, "Session auto-ended",
                endedSessionName + " was automatically ended after " + minutes + " minutes idle.",
                minutes, -1));
        }
        return true;
    }

    public synchronized boolean isCustomSessionActive()
    {
        return customSession != null && activeSession == customSession && !customSession.isClosed();
    }

    public synchronized void renameActiveSession(String name)
    {
        if (activeSession != null)
        {
            activeSession.rename(name);
        }
    }

    public synchronized void pauseForLifecycle(long now)
    {
        if (activeSession != null && !activeSession.isPaused())
        {
            activeSession.pause(now, PauseReason.LIFECYCLE);
        }
        resetTrackingState(false);
    }

    public synchronized void resumeAfterLifecycle(long now)
    {
        if (activeSession != null && activeSession.getPauseReason() == PauseReason.LIFECYCLE)
        {
            activeSession.resume(now);
            resetTrackingState(true);
        }
    }

    public synchronized void pauseForIdle(long now)
    {
        // Never overwrite Manual / Stop / Recovery / lifecycle ownership with idle.
        if (activeSession != null && !activeSession.isPaused())
        {
            activeSession.pause(now, PauseReason.IDLE);
            resetTrackingState(false);
        }
    }

    public synchronized void resumeAfterIdle(long now)
    {
        if (activeSession != null && activeSession.getPauseReason() == PauseReason.IDLE)
        {
            activeSession.resume(now);
            resetTrackingState(true);
        }
    }

    /**
     * Resume automatic Idle and Recovery pauses on meaningful gameplay.
     * Manual Pause and Stop still require an explicit Resume control.
     */
    public synchronized void resumeAfterActivity(long now)
    {
        if (activeSession == null || !activeSession.isPaused() || activeSession.isStopped())
        {
            return;
        }
        PauseReason reason = activeSession.getPauseReason();
        if (reason == PauseReason.IDLE || reason == PauseReason.RECOVERY)
        {
            activeSession.resume(now);
            autoStartEligibleAfterReset = false;
            resetTrackingState(true);
        }
    }

    /**
     * Create or re-arm the durable General tracker from meaningful gameplay when
     * automatic startup is enabled. Never resumes Manual Pause or ordinary Stop.
     * Idle and Recovery resume via {@link #resumeAfterActivity(long)}. Never starts
     * tracking from login alone.
     *
     * <p>Data/factory reset leaves a stopped General marked
     * {@code autoStartEligibleAfterReset}; with Automatic tracking on, the next
     * gameplay activity resumes that slate and primes a fresh baseline so skilling,
     * NPC loot, and other inventory gains register again.</p>
     *
     * @return true when a new General session was created or a reset slate was re-armed
     */
    public synchronized boolean startGeneralFromActivityIfNeeded(long now)
    {
        if (!config.autoStartSession())
        {
            return false;
        }
        if (customSession != null && !customSession.isClosed())
        {
            return false;
        }
        if (autoStartEligibleAfterReset
            && generalSession != null
            && !generalSession.isClosed()
            && generalSession.isStopped())
        {
            activeSession = generalSession;
            generalSession.resume(now);
            autoStartEligibleAfterReset = false;
            resetTrackingState(true);
            return true;
        }
        if (generalSession != null && !generalSession.isClosed())
        {
            return false;
        }
        ensureSession(now);
        autoStartEligibleAfterReset = false;
        resetTrackingState(true);
        return true;
    }

    public synchronized void stop(long now)
    {
        ensureSession(now);
        autoStartEligibleAfterReset = false;
        activeSession.stop(now);
        resetTrackingState(false);
    }

    public synchronized boolean isStopped()
    {
        return activeSession != null && activeSession.isStopped();
    }

    public synchronized boolean isIdlePaused()
    {
        return activeSession != null && activeSession.getPauseReason() == PauseReason.IDLE;
    }

    public synchronized void setDetectedActivity(String activity, long now)
    {
        if (activeSession != null
            && activeSession.getMode() == com.gpmanager.model.SessionMode.AUTO)
        {
            activeSession.setActivityHint(activity, now);
        }
    }

    public synchronized void togglePause(long now)
    {
        if (activeSession == null)
        {
            // A manually invoked control must not create an unowned session
            // when automatic startup is disabled.
            ensureSession(now);
            resetTrackingState(true);
        }

        if (activeSession.isPaused())
        {
            activeSession.resume(now);
            autoStartEligibleAfterReset = false;
            resetTrackingState(true);
        }
        else
        {
            autoStartEligibleAfterReset = false;
            activeSession.pause(now, PauseReason.MANUAL);
            resetTrackingState(false);
        }
    }

    public synchronized void closeActive(long now)
    {
        if (isCustomSessionActive())
        {
            finishCustomSession(now);
        }
        else
        {
            prepareForShutdown(now);
        }
    }

    public synchronized void prepareForShutdown(long now)
    {
        if (activeSession != null && !activeSession.isPaused() && !activeSession.isStopped())
        {
            activeSession.pause(now, PauseReason.LIFECYCLE);
        }
        resetTrackingState(false);
    }

    private void archiveCustom(long now)
    {
        if (customSession == null)
        {
            return;
        }
        customSession.close(now);
        history.add(0, customSession);
        trimHistory();
    }

    private void trimHistory()
    {
        int maxHistory = Math.max(1, config.maxHistorySessions());
        if (history.size() > maxHistory)
        {
            history.subList(maxHistory, history.size()).clear();
            invalidateOverallTotalsCache();
        }
    }

    /**
     * Compacts closed sessions strictly older than {@code days}; a zero window
     * means forever and performs no compaction. The session metadata and all
     * retained accounting aggregates remain available.
     *
     * @return receipt rows compacted
     */
    public synchronized int compactOlderThan(int days, long now)
    {
        if (days <= 0)
        {
            return 0;
        }
        long cutoff;
        try
        {
            cutoff = Instant.ofEpochMilli(now).minus(days, ChronoUnit.DAYS).toEpochMilli();
        }
        catch (RuntimeException ex)
        {
            cutoff = Long.MIN_VALUE;
        }

        int compactedReceipts = 0;
        for (ProfitSession session : uniqueProfileSessions())
        {
            if (session == null || !session.isClosed()
                || session.getEndedAtEpochMillis() >= cutoff
                || session.getTransactions().isEmpty())
            {
                continue;
            }
            compactedReceipts = saturatingIntAdd(compactedReceipts,
                session.compactAllTransactions());
        }
        return compactedReceipts;
    }

    /** Daily UTC maintenance hook. Supplying time keeps lifecycle tests deterministic. */
    public synchronized int maintainReceiptRetention(int days, long now)
    {
        LocalDate today = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate previous = parseRetentionDay(lastReceiptRetentionDayUtc);
        if (previous == null)
        {
            lastReceiptRetentionDayUtc = today.toString();
            return 0;
        }
        if (!today.isAfter(previous))
        {
            return 0;
        }

        int compacted = days <= 0 ? 0 : compactOlderThan(days, now);
        lastReceiptRetentionDayUtc = today.toString();
        receiptRetentionDeferredUntilDayChange = false;
        return compacted;
    }

    /** Configured UTC retention maintenance used by the game-tick lifecycle. */
    public synchronized int maintainReceiptRetention(long now)
    {
        ReceiptRetentionPeriod period = config.receiptRetentionDays();
        return maintainReceiptRetention(period == null
            ? ReceiptRetentionPeriod.DAYS_90.getDays() : period.getDays(), now);
    }

    /** Explicitly requested compaction window, including an exact-boundary-safe pending count. */
    public synchronized ReceiptRetentionStatus getRetentionStatus(int days, long now)
    {
        int pending = days <= 0 ? 0 : pendingReceiptRetentionSessions(days, now);
        Long next = null;
        if (days > 0)
        {
            LocalDate today = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate();
            LocalDate previous = parseRetentionDay(lastReceiptRetentionDayUtc);
            next = previous != null && previous.isBefore(today)
                ? now : today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        }
        return new ReceiptRetentionStatus(days, next, pending);
    }

    public synchronized ReceiptRetentionStatus getRetentionStatus()
    {
        ReceiptRetentionPeriod period = config.receiptRetentionDays();
        return getRetentionStatus(period == null ? ReceiptRetentionPeriod.DAYS_90.getDays()
            : period.getDays(), System.currentTimeMillis());
    }

    /** Counts distinct profile sessions and estimates the UTF-8 bytes of saved JSON. */
    public synchronized ProfileSizeEstimate getProfileSizeEstimate()
    {
        long receiptCount = 0L;
        long compactedReceiptCount = 0L;
        List<ProfitSession> sessions = uniqueProfileSessions();
        for (ProfitSession session : sessions)
        {
            receiptCount = saturatingAdd(receiptCount, session.getTransactions().size());
            compactedReceiptCount = saturatingAdd(compactedReceiptCount,
                session.getCompactedTransactionCount());
        }
        long bytes = com.gpmanager.persistence.JsonCodec.gson().toJson(createSavedState())
            .getBytes(StandardCharsets.UTF_8).length;
        return new ProfileSizeEstimate(sessions.size(), receiptCount, compactedReceiptCount, bytes);
    }

    /**
     * Detached, fail-closed health facts for Tools/Diagnostics. Persistence-specific
     * recovery names and backup counts arrive through the coordinator's explicit
     * {@link #setPersistenceRecoveryHealth(String, int)} hand-off; this engine
     * view never guesses them from missing files.
     */
    public synchronized DataHealthSnapshot getDataHealth(long now)
    {
        EnumMap<DailyRollup.Dimension, Integer> unavailable =
            new EnumMap<>(DailyRollup.Dimension.class);
        for (DailyRollup.Dimension dimension : DailyRollup.Dimension.values())
        {
            unavailable.put(dimension, 0);
        }
        for (DailyRollup rollup : dailyRollups)
        {
            if (rollup == null) continue;
            for (DailyRollup.Dimension dimension : DailyRollup.Dimension.values())
            {
                if (rollup.getCoverage(dimension) == DailyRollup.Coverage.UNAVAILABLE)
                {
                    unavailable.put(dimension, unavailable.get(dimension) + 1);
                }
            }
        }

        EnumMap<CoinStore, DataHealthSnapshot.CoinStoreFreshness> stores =
            new EnumMap<>(CoinStore.class);
        for (CoinStore store : CoinStore.values())
        {
            CoinStoreObservation observation = coinStores.get(store);
            if (observation == null)
            {
                stores.put(store, DataHealthSnapshot.CoinStoreFreshness.UNOBSERVED);
            }
            else
            {
                long age = now - observation.observedAt;
                stores.put(store, age >= 0L && age <= COIN_STORE_FRESHNESS_MILLIS
                    ? DataHealthSnapshot.CoinStoreFreshness.FRESH
                    : DataHealthSnapshot.CoinStoreFreshness.STALE);
            }
        }

        int unknownBags = unknownSavedStateFields.isEmpty() ? 0 : 1;
        for (ProfitSession session : uniqueProfileSessions())
        {
            if (!session.getUnknownJsonFields().isEmpty()) unknownBags++;
            for (ProfitTransaction transaction : session.getTransactions())
            {
                if (transaction != null && !transaction.getUnknownJsonFields().isEmpty()) unknownBags++;
            }
        }
        ReceiptRetentionPeriod period = config.receiptRetentionDays();
        int retentionDays = period == null ? ReceiptRetentionPeriod.DAYS_90.getDays() : period.getDays();
        return new DataHealthSnapshot(
            repairedActiveTimeDays,
            unavailable,
            getRetentionStatus(retentionDays, now),
            getProfileSizeEstimate(),
            lastRecoveredFrom,
            rotatedBackupCount,
            unknownBags,
            stores);
    }

    private int pendingReceiptRetentionSessions(int days, long now)
    {
        long cutoff;
        try
        {
            cutoff = Instant.ofEpochMilli(now).minus(days, ChronoUnit.DAYS).toEpochMilli();
        }
        catch (RuntimeException ex)
        {
            cutoff = Long.MIN_VALUE;
        }
        int pending = 0;
        for (ProfitSession session : uniqueProfileSessions())
        {
            if (session != null && session.isClosed()
                && session.getEndedAtEpochMillis() < cutoff
                && !session.getTransactions().isEmpty())
            {
                pending++;
            }
        }
        return pending;
    }

    private List<ProfitSession> uniqueProfileSessions()
    {
        Map<String, ProfitSession> unique = new LinkedHashMap<>();
        for (ProfitSession session : history)
        {
            addUniqueSession(unique, session);
        }
        addUniqueSession(unique, generalSession);
        addUniqueSession(unique, customSession);
        addUniqueSession(unique, activeSession);
        return new ArrayList<>(unique.values());
    }

    private static void addUniqueSession(Map<String, ProfitSession> unique, ProfitSession session)
    {
        if (session != null)
        {
            unique.putIfAbsent(session.getId(), session);
        }
    }

    @Nullable
    private static LocalDate parseRetentionDay(String value)
    {
        if (value == null || value.trim().isEmpty())
        {
            return null;
        }
        try
        {
            return LocalDate.parse(value.trim());
        }
        catch (RuntimeException ex)
        {
            return null;
        }
    }

    private static int saturatingIntAdd(int left, int right)
    {
        long sum = (long) left + right;
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    /**
     * Starts a login/session warm-up. Snapshots observed during this period are
     * baseline material only and can never become profit.
     */
    public synchronized void beginBaselinePriming()
    {
        resetTrackingState(true);
    }

    /**
     * Sets an immediate trusted baseline. Primarily useful for deterministic
     * tests and callers which already know all tracked containers are loaded.
     */
    public synchronized void setBaseline(ContainerSnapshot snapshot)
    {
        baseline = snapshot;
        baselinePriming = false;
        primingSnapshot = null;
        primingObservedTicks = 0;
        primingStableTicks = 0;
        clearPendingChange();
        trace("baseline", "trusted baseline set");
    }

    public synchronized void markInventoryDirty()
    {
        if (baselinePriming)
        {
            return;
        }

        dirty = true;
        pendingStableTicks = 0;
        // Animation lag: refresh consume/drop TTLs when the inventory finally moves so
        // bury/drink intents survive until stabilization rather than expiring idle.
        if (consumptionIntent != null)
        {
            consumptionIntent.refresh(Math.max(consumptionIntent.ticksRemaining(), 4));
        }
        if (dropIntent != null)
        {
            dropIntent.refresh(Math.max(
                dropIntent.ticksRemaining(),
                Math.max(4, config.stabilizationTicks() + 2)));
        }
        captureActiveContextForPending();
        noteSoftOpenTransferForPending();
    }

    /**
     * Records a short-lived consume hint; accounting still waits for a stable inventory delta.
     * {@code itemId} may be {@code < 0} (open intent) when the menu did not expose an id —
     * matching then accepts a dose/partial leftover, a pure cost, or any inventory cost
     * while intent is armed so drink/bury coalesced with harvest gains still books Used/lost.
     */
    public synchronized void noteConsumptionIntent(int itemId, int ticks)
    {
        noteConsumptionIntent(itemId, ticks, false);
    }

    /**
     * @param destroy when true, irreversible Destroy menu — presentation stamps Destroyed;
     *                never registers recoverable own-drop.
     */
    public synchronized void noteConsumptionIntent(int itemId, int ticks, boolean destroy)
    {
        noteConsumptionIntent(itemId, ticks, destroy, null);
    }

    /**
     * @param actionKind menu verb behind the click (Drink/Eat/Bury/…) or null when the
     *                   option does not evidence a specific action. Presentation only.
     */
    public synchronized void noteConsumptionIntent(
        int itemId,
        int ticks,
        boolean destroy,
        @Nullable ActionKind actionKind)
    {
        // Eat/Bury/Drink/Cast/etc. — clear any pending Drop intent for the same click window.
        dropIntent = null;
        int safeTicks = Math.max(1, ticks);
        if (consumptionIntent != null && itemId < 0 && consumptionIntent.itemId >= 0)
        {
            // Chat/menu reinforcement without an id must not wipe a resolved item id
            // or flip a Destroy stamp to Used.
            consumptionIntent.refresh(safeTicks);
            return;
        }
        consumptionIntent = new ConsumptionIntent(
            itemId,
            safeTicks,
            ++consumptionIntentGeneration,
            destroy,
            quantityAtArmForConsume(itemId),
            actionKind);
    }

    /**
     * Invent qty for a named consume arm. Prefer the pending (dirty) snapshot so a
     * mid-window pickup is visible before bury/drink settles.
     */
    private long quantityAtArmForConsume(int itemId)
    {
        if (itemId < 0)
        {
            return -1L;
        }
        ContainerSnapshot live = pendingSnapshot != null ? pendingSnapshot : baseline;
        return live == null ? -1L : live.quantityOf(itemId);
    }

    /**
     * Extends an existing consume intent TTL, or arms an open intent when chat confirms
     * Eat/Bury/Drink after the menu click failed to expose an item id.
     * Preserves a pending Destroy stamp; never invents destroy from chat alone.
     */
    public synchronized void reinforceConsumptionIntent(int ticks)
    {
        int safeTicks = Math.max(1, ticks);
        if (consumptionIntent != null)
        {
            consumptionIntent.refresh(safeTicks);
            return;
        }
        noteConsumptionIntent(-1, safeTicks, false);
    }

    /**
     * Player-initiated Drop action. Confirmed inventory removal books Used/lost and
     * registers a recoverable own-drop record. Matching pickups reverse that loss.
     */
    public synchronized void noteDropIntent(
        int itemId,
        int ticks,
        int worldX,
        int worldY,
        int worldPlane,
        boolean hasLocation)
    {
        if (itemId < 0)
        {
            return;
        }
        consumptionIntent = null;
        dropIntent = new DropIntent(
            itemId,
            Math.max(1, ticks),
            worldX,
            worldY,
            worldPlane,
            hasLocation);
    }

    /** Latest local-player tile for conservative own-drop recovery matching. */
    public synchronized void updatePlayerWorldLocation(int worldX, int worldY, int worldPlane)
    {
        updatePlayerWorldLocation(worldX, worldY, worldPlane, null);
    }

    /**
     * Clears the export identity when persistence invalidates the active account.
     */
    public synchronized void clearProfileIdentityForSwitch()
    {
        profileIdentityKey = "";
    }

    /** Binds the identity and restores its state under the same engine lock. */
    public synchronized void restoreForProfile(@Nullable String identityKey, SavedState state, long now)
    {
        String next = identityKey == null ? "" : identityKey.trim();
        SavedState detached = detachSavedState(state);
        String stateOwner = detached == null ? null : detached.getOwnerKey();
        if (!next.isEmpty() && stateOwner != null && !stateOwner.trim().isEmpty()
            && !next.equals(stateOwner))
        {
            throw new IllegalArgumentException("SavedState owner does not match the requested profile");
        }
        GpManagerEngine staged = prepareRestoredProfile(next, detached, now);
        installPreparedProfile(staged, next);
    }

    /** Creates a detached, integrity-checked snapshot of the currently bound profile. */
    public synchronized ProfileBackup exportProfile()
    {
        if (profileIdentityKey == null || profileIdentityKey.trim().isEmpty())
        {
            throw new IllegalStateException("A RuneScape profile identity must be bound before export");
        }
        SavedState snapshot = createSavedState();
        snapshot.setOwnerKey(profileIdentityKey);
        snapshot.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        return ProfileBackup.create(snapshot, profileIdentityKey, System.currentTimeMillis());
    }

    /** Validates a backup and exercises the real restore migration path on an isolated engine. */
    public synchronized ProfileBackupReport inspectBackup(String json)
    {
        return inspectBackup(json, System.currentTimeMillis()).report;
    }

    /** Validates and atomically installs a profile backup using the supplied clock. */
    public synchronized ProfileBackupReport restoreProfile(String json, long now)
    {
        BackupInspection inspection = inspectBackup(json, now);
        if (!inspection.report.isAccepted()) return inspection.report;

        installPreparedProfile(inspection.stagedEngine, profileIdentityKey);
        return inspection.report.asApplied();
    }

    private GpManagerEngine prepareRestoredProfile(String identityKey, SavedState state, long now)
    {
        GpManagerEngine staged = new GpManagerEngine(valuationService, classifier, config);
        staged.setContributionEligibility(contributionEligibility);
        staged.profileIdentityKey = identityKey;
        staged.restore(state, now);
        return staged;
    }

    private static SavedState detachSavedState(@Nullable SavedState state)
    {
        if (state == null) return null;
        com.google.gson.Gson gson = com.gpmanager.persistence.JsonCodec.gson();
        return gson.fromJson(gson.toJson(state), SavedState.class);
    }

    private void installPreparedProfile(GpManagerEngine staged, String identityKey)
    {
        // Allocate copies and attach listeners before changing live fields. The
        // normal restore/migration path has already completed on the isolated engine.
        List<ProfitSession> nextHistory = new ArrayList<>(staged.history);
        List<GoalDefinition> nextGoals = new ArrayList<>(staged.getGoalDefinitions());
        TileLayout nextLayout = new TileLayout(staged.tileLayout.getPages());
        List<DailyRollup> nextRollups = new ArrayList<>(staged.dailyRollups);
        List<DailyRollup> nextRollupBaseline = new ArrayList<>(staged.persistedDailyRollupBaseline);
        WealthSnapshotHistory nextWealth = staged.wealthSnapshotHistory.copyForPersistence();
        List<ProfitSession> nextSessions = new ArrayList<>(staged.uniqueProfileSessions());
        for (ProfitSession session : nextSessions)
        {
            attachOverallTotalsInvalidationListener(session);
        }

        // Clear identity-scoped transient evidence while durable profile fields
        // still belong to the previous owner. No reader can observe the swap
        // because restoreForProfile/restoreProfile hold this engine's monitor.
        clearOwnDropState();
        clearTransientAlertState();
        consumptionIntent = null;
        deathReclaim.reset();
        deferredChestClaimLifecycle.cancel();
        resetTrackingState(false);

        history = nextHistory;
        goalDefinitions = nextGoals;
        tileLayout = nextLayout;
        lastReceiptRetentionDayUtc = staged.lastReceiptRetentionDayUtc;
        receiptRetentionDeferredUntilDayChange = staged.receiptRetentionDeferredUntilDayChange;
        profileTimeZoneId = staged.profileTimeZoneId;
        dailyRollups = nextRollups;
        persistedDailyRollupBaseline = nextRollupBaseline;
        wealthSnapshotHistory = nextWealth;
        generalSession = staged.generalSession;
        customSession = staged.customSession;
        generalSuspendedByCustom = staged.generalSuspendedByCustom;
        activeSession = staged.activeSession;
        profileIdentityKey = identityKey == null ? "" : identityKey;
        autoStartEligibleAfterReset = false;
        invalidateOverallTotalsCache();
    }

    private BackupInspection inspectBackup(String json, long now)
    {
        ProfileBackup backup;
        try
        {
            backup = ProfileBackup.parseAndVerify(json);
        }
        catch (IllegalArgumentException ex)
        {
            return BackupInspection.refused(new ProfileBackupReport(false, false, 0, "", "",
                Collections.emptyList(), "", ex.getMessage(), null, ""));
        }

        String refusal = null;
        if (backup.getSchemaVersion() > SavedState.CURRENT_SCHEMA_VERSION)
        {
            refusal = "Backup SavedState schema " + backup.getSchemaVersion()
                + " is newer than supported schema " + SavedState.CURRENT_SCHEMA_VERSION;
        }
        else if (profileIdentityKey == null || profileIdentityKey.trim().isEmpty())
        {
            refusal = "No active RuneScape profile identity is bound";
        }
        else if (!profileIdentityKey.equals(backup.getProfileId()))
        {
            refusal = "Backup belongs to a different RuneScape profile";
        }
        else
        {
            String stateOwner = backup.getState().getOwnerKey();
            if (stateOwner != null && !stateOwner.trim().isEmpty()
                && !profileIdentityKey.equals(stateOwner))
            {
                refusal = "SavedState owner does not match the active RuneScape profile";
            }
        }
        if (refusal != null)
        {
            return BackupInspection.refused(reportFor(backup, refusal, Collections.emptyList()));
        }

        List<String> migrations = backupMigrationSteps(backup);
        GpManagerEngine staged = new GpManagerEngine(valuationService, classifier, config);
        staged.setContributionEligibility(contributionEligibility);
        staged.profileIdentityKey = profileIdentityKey;
        try
        {
            staged.restore(backup.getState(), now);
        }
        catch (RuntimeException ex)
        {
            return BackupInspection.refused(reportFor(backup,
                "Backup restore preflight failed: " + ex.getClass().getSimpleName(), migrations));
        }
        return new BackupInspection(reportFor(backup, "", migrations), staged);
    }

    private ProfileBackupReport reportFor(ProfileBackup backup, String refusal, List<String> migrations)
    {
        return new ProfileBackupReport(refusal == null || refusal.isEmpty(), false,
            backup.getSchemaVersion(), backup.getProfileId(), backup.getProfileTimeZoneId(),
            migrations, "sessions, receipts, run history, goals, layout, daily rollups, wealth captures",
            refusal, backup.getCounts(), backup.describe());
    }

    private static List<String> backupMigrationSteps(ProfileBackup backup)
    {
        int schema = backup.getSchemaVersion();
        List<String> migrations = new ArrayList<>();
        if (schema < 16) migrations.add("Initialize profile-local analytics timezone");
        if (schema < 17) migrations.add("Rebuild daily rollups from retained session summaries where possible");
        if (schema < 18) migrations.add("Initialize absent Wealth snapshot history");
        if (schema < 19) migrations.add("Migrate recognized legacy session-category tags");
        if (schema < 20) migrations.add("Mark legacy PvM encounter coverage unavailable where unprovable");
        if (schema < 23) migrations.add("Preserve legacy owner uncertainty and defer receipt retention");
        if (backup.getState().getActiveSession() != null
            && !backup.getState().getActiveSession().isClosed())
        {
            migrations.add("Recover the open session as paused, following normal restore behavior");
        }
        return Collections.unmodifiableList(migrations);
    }

    /** Latest local-player tile and optional presentation-only area label. */
    public synchronized void updatePlayerWorldLocation(int worldX, int worldY, int worldPlane,
        @Nullable String locationLabel)
    {
        updatePlayerWorldLocation(worldX, worldY, worldPlane, locationLabel,
            System.currentTimeMillis());
    }

    /** Clock-injectable location sample; the four-argument client call remains compatible. */
    public synchronized void updatePlayerWorldLocation(int worldX, int worldY, int worldPlane,
        @Nullable String locationLabel, long now)
    {
        playerWorldX = worldX;
        playerWorldY = worldY;
        playerWorldPlane = worldPlane;
        playerLocationKnown = true;
        playerLocationLabel = locationLabel == null || locationLabel.trim().isEmpty()
            ? null : locationLabel.trim();
        if (activeSession != null)
        {
            activeSession.observePkLocation(playerLocationLabel, now);
        }
    }

    /**
     * Records the actual start of the idle stretch when the client knows it.
     * The sidebar client should pass the timestamp tracked by its idle detector;
     * the one-argument overload retains its legacy pause-detection timestamp.
     */
    public synchronized void pauseForIdle(long now, long idleStartedAt)
    {
        if (activeSession != null && !activeSession.isPaused())
        {
            long pauseAt = Math.max(activeSession.getStartedAtEpochMillis(),
                Math.min(now, idleStartedAt));
            activeSession.pause(pauseAt, PauseReason.IDLE);
            resetTrackingState(false);
        }
    }

    public synchronized void clearPlayerWorldLocation()
    {
        clearPlayerWorldLocation(System.currentTimeMillis());
    }

    public synchronized void clearPlayerWorldLocation(long now)
    {
        playerLocationKnown = false;
        playerLocationLabel = null;
        if (activeSession != null)
        {
            activeSession.observePkLocation(null, now);
        }
    }

    public synchronized void markContext(TrackingContext newContext, int ticks, String note)
    {
        if (newContext != TrackingContext.LOOT && newContext != TrackingContext.PK_LOOT)
        {
            deferredChestClaimLifecycle.cancel();
        }
        if (newContext == TrackingContext.TRANSFER || newContext == TrackingContext.MARKET)
        {
            chargeLoadTransferEvidence.clear();
        }
        supersedeDeathMarkerForExplicitContext(newContext, note);
        if (newContext == TrackingContext.TRANSFER)
        {
            // Stale Eat/Bury intents must not reclassify bank deposits as Used/lost.
            consumptionIntent = null;
            dropIntent = null;
            noteHardTransferEvidence(Math.max(1, ticks));
        }
        setActiveContext(newContext, ticks, note, Collections.emptyMap(), null);
    }

    /**
     * Bank interface is open on this tick. Soft UI-open refresh only — does not
     * by itself prove a deposit/withdraw movement.
     */
    public synchronized void markBankInterfaceOpen(int ticks)
    {
        deferredChestClaimLifecycle.cancel();
        chargeLoadTransferEvidence.clear();
        supersedeDeathMarkerForExplicitContext(TrackingContext.TRANSFER, "Bank transfer");
        // Edge-trigger only. Live GameTick refreshes this every tick while the bank
        // widget/container is open — wiping Eat/Drink/Bury intents each tick made
        // potato-field sessions book every inventory delta as soft TRANSFER (Used/lost
        // ×0, no harvest rows) whenever bank detection stuck or fluttered.
        boolean newlyOpened = !bankInterfaceOpen;
        bankInterfaceOpen = true;
        if (newlyOpened)
        {
            consumptionIntent = null;
            dropIntent = null;
        }
        transferEvidenceTicks = Math.max(transferEvidenceTicks, Math.max(1, ticks));
        setActiveContext(
            TrackingContext.TRANSFER,
            ticks,
            "Bank transfer",
            Collections.emptyMap(),
            null);
    }

    /**
     * Bank interface closed this tick. Clears sticky soft TRANSFER hints so burial
     * after close is not classified as TRANSFER when the inventory callback raced
     * ahead of this tick. Genuine deposits keep pending TRANSFER only when hard
     * bank-container/menu evidence was attached to this pending change — or when
     * hard evidence is still live and the inventory callback arrives after close.
     */
    public synchronized void markBankInterfaceClosed()
    {
        bankInterfaceOpen = false;
        // Soft UI-open refresh only. Hard deposit/withdraw TTL must survive close so a
        // GameTick between bank-container evidence and inventory dirty stays TRANSFER.
        if (!hardTransferContextActive)
        {
            transferEvidenceTicks = 0;
        }
        if (dirty || pendingSnapshot != null)
        {
            if (!pendingHardTransferEvidence && !hardTransferContextActive)
            {
                // Soft UI-open latch only — clear so burial after close is CONSUMPTION.
                // Genuine deposits attach hard evidence via bank-container/menu markContext.
                pendingTransferEvidence = false;
                if (pendingContext == TrackingContext.TRANSFER)
                {
                    pendingContext = TrackingContext.GENERIC;
                    pendingContextNote = "";
                    pendingExpectedLoot = Collections.emptyMap();
                    pendingEncounterId = null;
                }
            }
            else if (hardTransferContextActive && !pendingHardTransferEvidence)
            {
                // Inventory already dirty from soft open; promote with surviving hard evidence.
                latchHardTransferIntoPending();
            }
            return;
        }
        if (context == TrackingContext.TRANSFER && !hardTransferContextActive)
        {
            context = TrackingContext.GENERIC;
            contextTicks = 0;
            contextNote = "";
            contextExpectedLoot = Collections.emptyMap();
            contextEncounterId = null;
        }
        else if (context == TrackingContext.TRANSFER && hardTransferContextActive)
        {
            // Drop the soft UI-open note/ticks but keep hard evidence for a late inventory callback.
            contextTicks = 0;
            contextNote = "";
            contextExpectedLoot = Collections.emptyMap();
            contextEncounterId = null;
        }
    }

    private void noteHardTransferEvidence(int ticks)
    {
        chargeLoadTransferEvidence.clear();
        int safeTicks = Math.max(1, ticks);
        transferEvidenceTicks = Math.max(transferEvidenceTicks, safeTicks);
        hardTransferEvidenceTicks = Math.max(hardTransferEvidenceTicks, safeTicks);
        hardTransferContextActive = true;
        if (dirty || pendingSnapshot != null)
        {
            latchHardTransferIntoPending();
        }
    }

    private void latchHardTransferIntoPending()
    {
        pendingTransferEvidence = true;
        pendingHardTransferEvidence = true;
        if (pendingContext == TrackingContext.GENERIC
            || TrackingContext.TRANSFER.getPriority() > pendingContext.getPriority())
        {
            // Keep the producer's own note (minigame wipe, neutral zone, death) — a
            // generic "Bank transfer" would otherwise win the priority race and mislabel
            // every non-bank transfer row.
            String note = context == TrackingContext.TRANSFER
                && contextNote != null && !contextNote.trim().isEmpty()
                ? contextNote : "Bank transfer";
            applyPendingContext(
                TrackingContext.TRANSFER,
                note,
                Collections.emptyMap(),
                null);
        }
        else if (pendingContext == TrackingContext.TRANSFER)
        {
            pendingTransferEvidence = true;
            if (pendingContextNote == null || pendingContextNote.isEmpty())
            {
                pendingContextNote = "Bank transfer";
            }
        }
    }

    /**
     * Soft evidence: inventory became dirty while the bank UI was open. Survives
     * bank close for genuine deposits. Cleared by {@link #markBankInterfaceClosed()}
     * when the pending change was only a stale latch after the UI was already gone
     * (plugin closes before dirty when {@code !isBankOpen()}).
     */
    private void noteSoftOpenTransferForPending()
    {
        if (bankInterfaceOpen && (dirty || pendingSnapshot != null))
        {
            pendingTransferEvidence = true;
            if (pendingContext == TrackingContext.GENERIC)
            {
                pendingContext = TrackingContext.TRANSFER;
                pendingContextNote = "Bank transfer";
            }
        }
    }

    public synchronized void markLootContext(
        Map<Integer, Long> expectedLoot,
        int ticks,
        String note)
    {
        markLootContext(expectedLoot, ticks, note, activityFromNote(note));
    }

    public synchronized void markLootContext(
        Map<Integer, Long> expectedLoot,
        int ticks,
        String note,
        String activityName)
    {
        Map<Integer, Long> safeExpectedLoot = positiveEntries(expectedLoot);
        if (!safeExpectedLoot.isEmpty())
        {
            lootExpectations.add(new LootExpectation(
                safeExpectedLoot,
                Math.max(1, ticks),
                note,
                activityName));
        }
        setActiveContext(TrackingContext.LOOT, ticks, note, safeExpectedLoot, null);
    }

    private void setActiveContext(
        TrackingContext newContext,
        int ticks,
        String note,
        Map<Integer, Long> expectedLoot,
        String encounterId)
    {
        TrackingContext safeContext = newContext == null ? TrackingContext.GENERIC : newContext;
        Map<Integer, Long> safeExpectedLoot = positiveEntries(expectedLoot);

        supersedeDeathMarkerForExplicitContext(safeContext, note);

        if (contextTicks <= 0 || safeContext.getPriority() > context.getPriority())
        {
            context = safeContext;
            contextTicks = Math.max(1, ticks);
            contextNote = note == null ? "" : note;
            contextExpectedLoot = safeExpectedLoot;
            contextEncounterId = encounterId;
        }
        else if (safeContext == context)
        {
            contextTicks = Math.max(contextTicks, Math.max(1, ticks));
            if (note != null && !note.isEmpty())
            {
                contextNote = note;
            }
            if (safeContext == TrackingContext.LOOT || safeContext == TrackingContext.PK_LOOT)
            {
                contextExpectedLoot = mergeQuantities(contextExpectedLoot, safeExpectedLoot);
            }
            if (encounterId != null && !encounterId.isEmpty())
            {
                contextEncounterId = encounterId;
            }
        }

        if (dirty || pendingSnapshot != null)
        {
            applyPendingContext(safeContext, note, safeExpectedLoot, encounterId);
        }
    }

    /**
     * A death marker is a short-lived reconciliation hint, not a durable activity
     * context. An explicit later activity must own the next measured change even though
     * TRANSFER has a higher general context priority.
     */
    private void supersedeDeathMarkerForExplicitContext(TrackingContext safeContext, String note)
    {
        boolean newDeathMarker = safeContext == TrackingContext.TRANSFER
            && isDeathHeldNote(note);
        boolean activeDeathMarker = context == TrackingContext.TRANSFER
            && isDeathHeldNote(contextNote);
        boolean pendingDeathMarker = pendingContext == TrackingContext.TRANSFER
            && isDeathHeldNote(pendingContextNote);
        if (!newDeathMarker && safeContext != TrackingContext.GENERIC
            && (activeDeathMarker || pendingDeathMarker))
        {
            if (activeDeathMarker)
            {
                context = TrackingContext.GENERIC;
                contextTicks = 0;
                contextNote = "";
                contextExpectedLoot = Collections.emptyMap();
                contextEncounterId = null;
            }
            if (pendingDeathMarker)
            {
                pendingContext = TrackingContext.GENERIC;
                pendingContextNote = "";
                pendingExpectedLoot = Collections.emptyMap();
                pendingEncounterId = null;
                pendingTransferEvidence = false;
                pendingHardTransferEvidence = false;
            }
            transferEvidenceTicks = 0;
            clearHardTransferEvidence();
            deathReclaim.cancelDeathWipe();
        }
    }



    public synchronized void markPkLootContext(
        Map<Integer, Long> expectedLoot,
        int ticks,
        String label,
        long now)
    {
        if (activeSession == null && !startGeneralFromActivityIfNeeded(now))
        {
            return;
        }
        if (activeSession == null || activeSession.isPaused())
        {
            return;
        }
        evaluateGoalAlerts(now, false);
        Map<Integer, Long> safeExpectedLoot = positiveEntries(expectedLoot);
        PkEncounter encounter = activeSession.addPkEncounter(
            PkEncounterType.KILL,
            now,
            label,
            ClassificationConfidence.CONFIRMED,
            "RuneLite reported loot from a dead player.");
        encounter.setLocationLabel(playerLocationLabel);
        if (activeSession.getMode() != com.gpmanager.model.SessionMode.GENERAL)
        {
            activeSession.attachRecentCostsToEncounter(
                encounter.getId(),
                now,
                Math.max(0, config.pkSupplyWindowSeconds()) * 1000L);
        }

        String note = "PK loot";
        if (!safeExpectedLoot.isEmpty())
        {
            lootExpectations.add(new LootExpectation(
                safeExpectedLoot,
                Math.max(1, ticks),
                note,
                "PKing",
                TrackingContext.PK_LOOT,
                encounter.getId()));
        }
        setActiveContext(
            TrackingContext.PK_LOOT,
            ticks,
            note,
            safeExpectedLoot,
            encounter.getId());
        evaluateGoalAlerts(now, false);
    }

    /**
     * Local unsafe PvM death: inventory and equipment move to a gravestone or an Item
     * Retrieval Service while the player still owns them. The wipe settles as an
     * ownership-neutral transfer, and a later reclaim window returns the items as a
     * transfer while booking observed coins as the reclaim fee. Gravestone expiry is an
     * informational audit event; an item loss is never guessed.
     */
    public synchronized void markLocalPvmDeath(int ticks)
    {
        markLocalPvmDeath(ticks, null);
    }

    public synchronized void markLocalPvmDeath(int ticks, @Nullable LocalDeathEvidence evidence)
    {
        markLocalPvmDeath(ticks, evidence, null);
    }

    /**
     * Local unsafe PvM death with a separate canonical inventory/equipment ownership
     * snapshot. The snapshot is reconciled against measured negative flows; presentation
     * evidence remains explanation-only.
     */
    public synchronized void markLocalPvmDeath(
        int ticks,
        @Nullable LocalDeathEvidence evidence,
        @Nullable Map<Integer, Long> heldItemsAtDeath)
    {
        pendingLocalDeathEvidence = evidence;
        markContext(
            TrackingContext.TRANSFER,
            Math.max(1, ticks),
            "Death: items held by gravestone / retrieval service");
        // Start after the transfer marker so this new death cannot cancel its own
        // bounded whitelist when strong-context supersession is evaluated.
        deathReclaim.onLocalPvmDeath(heldItemsAtDeath);
    }

    /**
     * Local death with no confident PvM / PK classification. Drop stale transfer and
     * action evidence so its inventory wipe is booked from ordinary loss evidence.
     */
    public synchronized void markUnclassifiedLocalDeath()
    {
        markUnclassifiedLocalDeath(null);
    }

    public synchronized void markUnclassifiedLocalDeath(@Nullable LocalDeathEvidence evidence)
    {
        deferredChestClaimLifecycle.cancel();
        pendingLocalDeathEvidence = evidence;
        deathReclaim.reset();
        consumptionIntent = null;
        dropIntent = null;
        clearHardTransferEvidence();
        transferEvidenceTicks = 0;
        bankInterfaceOpen = false;
        context = TrackingContext.GENERIC;
        contextTicks = Math.max(config.correlationTicks(), config.stabilizationTicks() + 4);
        contextNote = UNCLASSIFIED_LOCAL_DEATH_NOTE;
        contextExpectedLoot = Collections.emptyMap();
        contextEncounterId = null;
        if (dirty || pendingSnapshot != null)
        {
            pendingContext = TrackingContext.GENERIC;
            pendingContextNote = UNCLASSIFIED_LOCAL_DEATH_NOTE;
            pendingExpectedLoot = Collections.emptyMap();
            pendingEncounterId = null;
            pendingTransferEvidence = false;
            pendingHardTransferEvidence = false;
        }
    }

    /**
     * Player interacted with a retrieval service (gravestone, Death, boss NPC or chest).
     *
     * @return true when the reclaim window was armed; ambiguous targets (a bare "Chest",
     *         a gravestone) only arm while a local PvM death is awaiting reclaim
     */
    public synchronized boolean noteDeathReclaimIntent(BossRetrievalCatalogue.Service service, int ticks)
    {
        return deathReclaim.noteReclaimIntent(service, ticks);
    }

    public synchronized boolean isAwaitingDeathReclaim()
    {
        return deathReclaim.isAwaitingReclaim();
    }

    /** Current presentation read model for the local death-reclaim indicator. */
    public synchronized DeathReclaimStatus getDeathReclaimStatus()
    {
        return new DeathReclaimStatus(
            deathReclaim.isAwaitingReclaim(),
            deathReclaim.isReclaimArmed(),
            deathReclaim.outstandingItemCount(),
            deathReclaim.ageTicks());
    }

    /**
     * Remove measured death-owned returns before minimum-value and ordinary gain
     * classification. Source-backed loot quantities are protected first; unmatched gains
     * and all non-coin costs stay in the ordinary settle. A fee is booked only from an
     * observed coin loss with a live service interaction or a matched return in the same
     * settle.
     */
    @Nullable
    private ProfitTransaction settleDeathReclaim(
        List<ItemFlow> flows,
        long now,
        Map<Integer, Long> observedLootQuantities,
        boolean allowObservedFee)
    {
        if (!deathReclaim.isAwaitingReclaim() || flows == null || flows.isEmpty())
        {
            return null;
        }

        BossRetrievalCatalogue.Service service = deathReclaim.reclaimService();
        Map<Integer, Long> sourceBackedQuantities = copyPositiveQuantities(observedLootQuantities);
        List<ItemFlow> returned = new ArrayList<>();
        List<ItemFlow> remaining = new ArrayList<>();
        long coinsPaid = 0L;
        boolean hasOtherLoss = false;
        for (ItemFlow flow : flows)
        {
            if (flow == null)
            {
                continue;
            }
            long quantity = flow.getQuantityDelta();
            if (quantity > 0L)
            {
                long sourceBacked = takeProtectedQuantity(
                    sourceBackedQuantities, flow.getItemId(), quantity);
                if (sourceBacked > 0L)
                {
                    remaining.add(positiveFlowPart(
                        flow, sourceBacked, positiveFlowValue(flow, sourceBacked)));
                }
                long unprotected = quantity - sourceBacked;
                long matched = deathReclaim.matchReturnedItem(flow.getItemId(), unprotected);
                if (matched > 0L)
                {
                    returned.add(positiveFlowPart(flow, matched, positiveFlowValue(flow, matched)));
                }
                long ordinary = unprotected - matched;
                if (ordinary > 0L)
                {
                    long ordinaryValue = flow.getValueDelta()
                        - positiveFlowValue(flow, sourceBacked)
                        - positiveFlowValue(flow, matched);
                    remaining.add(positiveFlowPart(flow, ordinary, ordinaryValue));
                }
            }
            else
            {
                remaining.add(flow);
                if (quantity < 0L && flow.getItemId() == 995 && quantity != Long.MIN_VALUE)
                {
                    coinsPaid = saturatingAdd(coinsPaid, -quantity);
                }
                else if (quantity < 0L)
                {
                    hasOtherLoss = true;
                }
            }
        }

        ProfitTransaction recovered = null;
        if (!returned.isEmpty())
        {
            String source = service == null ? "a gravestone or retrieval service" : service.interactable;
            recovered = new ProfitTransaction(
                now,
                activeSession.getElapsedMillis(now),
                TransactionType.TRANSFER,
                TrackingContext.TRANSFER,
                "Death reclaim: items recovered",
                "Death reclaim",
                false,
                returned,
                ClassificationConfidence.CONFIRMED,
                "Items recovered from " + source + " after a death; ownership never changed.",
                null);
            if (config.keepTransferAuditRows())
            {
                activeSession.addTransaction(recovered, config.maxTransactionsPerSession());
            }
        }

        ProfitTransaction fee = null;
        boolean feeEvidence = service != null || !returned.isEmpty();
        if (allowObservedFee && coinsPaid > 0L && feeEvidence && (!hasOtherLoss || !returned.isEmpty()))
        {
            String why = service == null
                ? "Death reclaim fee — observed carried-coin loss with returned death items"
                : service.why(coinsPaid);
            fee = bookDeathReclaimFee(coinsPaid, why, now, false);
            remaining.removeIf(flow -> flow != null
                && flow.getItemId() == 995
                && flow.getQuantityDelta() < 0L);
        }

        if (returned.isEmpty() && fee == null)
        {
            return null;
        }
        flows.clear();
        flows.addAll(remaining);
        trace("death-reclaim", "coins=" + coinsPaid + " returned=" + returned.size());
        return fee != null ? fee : recovered;
    }

    /** Create a neutral audit receipt for the measured negative portion of the death wipe. */
    @Nullable
    private ProfitTransaction extractDeathWipeTransfer(
        List<ItemFlow> flows,
        Map<Integer, Long> measuredLosses,
        long now,
        @Nullable LocalDeathEvidence deathEvidence)
    {
        if (flows == null || flows.isEmpty() || measuredLosses == null || measuredLosses.isEmpty())
        {
            return null;
        }
        List<ItemFlow> transferred = new ArrayList<>();
        List<ItemFlow> remaining = new ArrayList<>();
        Map<Integer, Long> outstanding = new HashMap<>(measuredLosses);
        for (ItemFlow flow : flows)
        {
            if (flow == null || flow.getQuantityDelta() >= 0L
                || flow.getQuantityDelta() == Long.MIN_VALUE)
            {
                remaining.add(flow);
                continue;
            }
            long quantity = -flow.getQuantityDelta();
            long matched = takeProtectedQuantity(outstanding, flow.getItemId(), quantity);
            if (matched <= 0L)
            {
                remaining.add(flow);
                continue;
            }
            long matchedValue = -positiveFlowValue(flow, matched);
            transferred.add(new ItemFlow(
                flow.getItemId(), flow.getItemName(), -matched, flow.getUnitPrice(), matchedValue,
                flow.getPriceSource(), flow.getPriceCapturedAtEpochMillis()));
            long residual = quantity - matched;
            if (residual > 0L)
            {
                long residualValue = flow.getValueDelta() - matchedValue;
                remaining.add(new ItemFlow(
                    flow.getItemId(), flow.getItemName(), -residual, flow.getUnitPrice(), residualValue,
                    flow.getPriceSource(), flow.getPriceCapturedAtEpochMillis()));
            }
        }
        flows.clear();
        flows.addAll(remaining);
        if (transferred.isEmpty())
        {
            return null;
        }
        String explanation = "Ownership-neutral transfer: Death: items held by gravestone / retrieval service. "
            + "Measured inventory/equipment stacks moved while ownership stayed with the player.";
        if (deathEvidence != null)
        {
            explanation = deathEvidence.appendTo(explanation, transferred);
        }
        ProfitTransaction transfer = new ProfitTransaction(
            now,
            activeSession.getElapsedMillis(now),
            TransactionType.TRANSFER,
            TrackingContext.TRANSFER,
            "Death: items held by gravestone / retrieval service",
            "Death reclaim",
            false,
            transferred,
            ClassificationConfidence.CONFIRMED,
            explanation,
            null);
        if (config.keepTransferAuditRows())
        {
            activeSession.addTransaction(transfer, config.maxTransactionsPerSession());
        }
        return transfer;
    }

    private static Map<Integer, Long> copyPositiveQuantities(@Nullable Map<Integer, Long> quantities)
    {
        if (quantities == null || quantities.isEmpty())
        {
            return new HashMap<>();
        }
        Map<Integer, Long> copy = new HashMap<>();
        for (Map.Entry<Integer, Long> entry : quantities.entrySet())
        {
            if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0L)
            {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return copy;
    }

    private static long takeProtectedQuantity(Map<Integer, Long> quantities, int itemId, long maximum)
    {
        if (quantities == null || maximum <= 0L)
        {
            return 0L;
        }
        long available = quantities.getOrDefault(itemId, 0L);
        long taken = Math.min(Math.max(0L, available), maximum);
        if (taken >= available)
        {
            quantities.remove(itemId);
        }
        else if (taken > 0L)
        {
            quantities.put(itemId, available - taken);
        }
        return taken;
    }

    private static long positiveFlowValue(ItemFlow flow, long quantity)
    {
        if (flow == null || quantity <= 0L || flow.getUnitPrice() <= 0)
        {
            return 0L;
        }
        long unitPrice = flow.getUnitPrice();
        return quantity > Long.MAX_VALUE / unitPrice
            ? Long.MAX_VALUE
            : unitPrice * quantity;
    }

    private static ItemFlow positiveFlowPart(ItemFlow source, long quantity, long value)
    {
        return new ItemFlow(source.getItemId(), source.getItemName(), quantity,
            source.getUnitPrice(), value, source.getPriceSource(), source.getPriceCapturedAtEpochMillis());
    }

    private void recordNeutralZoneEntryItems(Map<Integer, Long> deltas)
    {
        if (deltas == null)
        {
            return;
        }
        for (Map.Entry<Integer, Long> entry : deltas.entrySet())
        {
            Long delta = entry.getValue();
            if (entry.getKey() == null || delta == null || delta >= 0L)
            {
                continue;
            }
            long quantity = delta == Long.MIN_VALUE ? Long.MAX_VALUE : -delta;
            long previous = neutralZoneStoredItems.getOrDefault(entry.getKey(), 0L);
            neutralZoneStoredItems.put(entry.getKey(), previous > Long.MAX_VALUE - quantity
                ? Long.MAX_VALUE : previous + quantity);
        }
    }

    /**
     * Excludes only positive quantities that match items actually removed on zone entry.
     * Item ids with a matched observed-loot event remain counted as loot even if they
     * happen to overlap the stored gear.
     */
    @Nullable
    private ProfitTransaction settleNeutralZoneRestore(
        List<ItemFlow> flows,
        Map<Integer, Long> observedLootQuantities,
        long now)
    {
        if (neutralZoneRestoreTicks <= 0 || neutralZoneStoredItems.isEmpty()
            || flows == null || flows.isEmpty())
        {
            return null;
        }

        List<ItemFlow> restored = new ArrayList<>();
        List<ItemFlow> remaining = new ArrayList<>();
        Map<Integer, Long> sourceBackedQuantities = copyPositiveQuantities(observedLootQuantities);
        for (ItemFlow flow : flows)
        {
            if (flow == null)
            {
                continue;
            }
            long quantity = flow.getQuantityDelta();
            if (quantity <= 0L)
            {
                remaining.add(flow);
                continue;
            }
            long sourceBacked = takeProtectedQuantity(
                sourceBackedQuantities, flow.getItemId(), quantity);
            long sourceBackedValue = positiveFlowValue(flow, sourceBacked);
            if (sourceBacked > 0L)
            {
                remaining.add(positiveFlowPart(flow, sourceBacked, sourceBackedValue));
            }
            long returnableQuantity = quantity - sourceBacked;
            long stored = neutralZoneStoredItems.getOrDefault(flow.getItemId(), 0L);
            long matched = Math.min(stored, returnableQuantity);
            long matchedValue = positiveFlowValue(flow, matched);
            if (matched > 0L)
            {
                restored.add(new ItemFlow(
                    flow.getItemId(), flow.getItemName(), matched, flow.getUnitPrice(), matchedValue,
                    flow.getPriceSource(), flow.getPriceCapturedAtEpochMillis()));
                long outstanding = stored - matched;
                if (outstanding == 0L)
                {
                    neutralZoneStoredItems.remove(flow.getItemId());
                }
                else
                {
                    neutralZoneStoredItems.put(flow.getItemId(), outstanding);
                }
            }
            long ordinaryQuantity = returnableQuantity - matched;
            if (ordinaryQuantity > 0L)
            {
                long value = flow.getValueDelta() - sourceBackedValue - matchedValue;
                remaining.add(new ItemFlow(
                    flow.getItemId(), flow.getItemName(), ordinaryQuantity, flow.getUnitPrice(), value,
                    flow.getPriceSource(), flow.getPriceCapturedAtEpochMillis()));
            }
        }

        if (restored.isEmpty())
        {
            return null;
        }
        flows.clear();
        flows.addAll(remaining);
        if (neutralZoneStoredItems.isEmpty())
        {
            neutralZoneRestoreTicks = 0;
        }
        return new ProfitTransaction(
            now,
            activeSession.getElapsedMillis(now),
            TransactionType.TRANSFER,
            TrackingContext.TRANSFER,
            "Neutral zone exit restore",
            "Gauntlet",
            false,
            restored,
            ClassificationConfidence.CONFIRMED,
            "Items restored after leaving the neutral zone; ownership never changed.",
            null);
    }

    public synchronized void markPkDeath(String label, long now)
    {
        markPkDeath(label, now, null);
    }

    public synchronized void markPkDeath(
        String label,
        long now,
        @Nullable LocalDeathEvidence evidence)
    {
        deferredChestClaimLifecycle.cancel();
        deathReclaim.reset();
        if (activeSession == null && !startGeneralFromActivityIfNeeded(now))
        {
            return;
        }
        if (activeSession == null || activeSession.isPaused())
        {
            return;
        }
        pendingLocalDeathEvidence = evidence;
        PkEncounter encounter = activeSession.addPkEncounter(
            PkEncounterType.DEATH,
            now,
            label,
            ClassificationConfidence.CONFIRMED,
            "The local player died during confirmed recent player combat.");
        encounter.setLocationLabel(playerLocationLabel);
        setActiveContext(
            TrackingContext.PK_DEATH,
            Math.max(30, Math.max(config.correlationTicks(), config.stabilizationTicks() + 4)),
            "PK death loss",
            Collections.emptyMap(),
            encounter.getId());
    }

    /** Ownership-neutral storage (seed vault, leprechaun, coffers, MLM, GIM, raid bag…). */
    public synchronized void markNeutralStorageTransfer(String note, int ticks)
    {
        NeutralStorageClassifier.Kind kind = NeutralStorageClassifier.classify(note);
        String transferNote = kind == null
            ? (note == null || note.trim().isEmpty() ? "Ownership-neutral storage transfer" : note.trim())
            : NeutralStorageClassifier.transferNote(kind);
        markContext(TrackingContext.TRANSFER, Math.max(1, ticks), transferNote);
    }

    /**
     * Arm narrow transfer evidence for a supported charge component used on its
     * charged item. The menu action itself never creates or books a transaction.
     */
    public synchronized boolean markChargeLoadTransfer(
        MeasuredChargeRead.Variant variant,
        int selectedItemId,
        @Nullable String selectedItemName,
        int ticks)
    {
        return markChargeLoadTransfer(variant, selectedItemId, selectedItemName, null, ticks);
    }

    public synchronized List<GoalDefinition> getGoalDefinitions()
    {
        List<GoalDefinition> copy = new ArrayList<>();
        for (GoalDefinition definition : goalDefinitions)
        {
            if (definition != null) copy.add(definition.copy());
        }
        return Collections.unmodifiableList(copy);
    }

    /**
     * Adds caller-confirmed, deduplicated NPC evidence to the current owner.
     * The reward client should call this from its accepted
     * {@code RewardPresentationModel.recordGenuineEncounter} observer; this
     * metadata does not create a ledger row or change Net.
     */
    public synchronized boolean recordObservedEncounter(String sourceName, long multiplicity,
        long lootValue, boolean lootValueKnown, long currentStreak, long observedAtEpochMillis)
    {
        if (activeSession == null || activeSession.isClosed()
            || sourceName == null || sourceName.trim().isEmpty() || multiplicity <= 0L
            || observedAtEpochMillis <= 0L) return false;
        evaluateGoalAlerts(observedAtEpochMillis, false);
        boolean recorded = activeSession.recordObservedEncounter(sourceName, multiplicity, lootValue,
            lootValueKnown, currentStreak, observedAtEpochMillis);
        if (recorded) evaluateGoalAlerts(observedAtEpochMillis, false);
        return recorded;
    }

    /** Returns progress for one configured goal, using only totals already aggregated for its scope. */
    public synchronized GoalProgress getGoalProgress(GoalDefinition definition, long now)
    {
        if (definition == null || !definition.isConfigured())
        {
            return GoalProgress.unavailable(definition, "GOAL_NOT_CONFIGURED");
        }
        if (definition.getScope() == GoalDefinition.Scope.RUN)
        {
            return GoalProgress.unavailable(definition, "RUN_SCOPE_NOT_AVAILABLE");
        }
        if (definition.getScope() == GoalDefinition.Scope.SESSION)
        {
            return goalProgressForSession(definition, activeSession, now);
        }
        return goalProgressForToday(definition, now);
    }

    /** Progress for every stored definition, keyed by its stable profile-local id. */
    public synchronized Map<String, GoalProgress> getGoalProgress(long now)
    {
        Map<String, GoalProgress> result = new LinkedHashMap<>();
        for (GoalDefinition definition : goalDefinitions)
        {
            if (definition != null)
            {
                result.put(definition.getId(), getGoalProgress(definition, now));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private GoalProgress goalProgressForSession(GoalDefinition definition,
        ProfitSession session, long now)
    {
        if (session == null || session.isClosed())
        {
            return GoalProgress.unavailable(definition, "SESSION_UNAVAILABLE");
        }
        SessionMetrics metrics = filteredMetrics(session, now,
            Math.max(1L, config == null ? 5L : config.rollingRateMinutes()) * 60_000L);
        if ((definition.getKind() == GoalDefinition.Kind.NET
                || definition.getKind() == GoalDefinition.Kind.GP_PER_HOUR)
            && !metrics.isAccountingProjectionAvailable())
        {
            return GoalProgress.unavailable(definition, "SESSION_TOTALS_UNAVAILABLE");
        }
        long kills = 0L;
        long itemCount = 0L;
        if (definition.getKind() == GoalDefinition.Kind.KILLS)
        {
            PkMetrics pk = session.pkMetrics();
            kills = pk.getKills();
            if (session.getMode() == com.gpmanager.model.SessionMode.PK)
            {
                if (!pk.isProjectionAvailable())
                    return GoalProgress.unavailable(definition, "PK_KILL_HISTORY_UNAVAILABLE");
            }
            else
            {
                if (!session.isEncounterTotalsAvailable())
                    return GoalProgress.unavailable(definition, "ENCOUNTER_HISTORY_UNAVAILABLE");
                kills = saturatingAdd(kills, session.getEncounterTotals().getTotalEncounterCount());
            }
        }
        else if (definition.getKind() == GoalDefinition.Kind.ITEM_COUNT)
        {
            if (hasActiveContributionFilter())
                return GoalProgress.unavailable(definition, "FILTERED_ITEM_QUANTITY_UNAVAILABLE");
            if (!session.isAnalyticsItemHistoryComplete())
                return GoalProgress.unavailable(definition, "ITEM_HISTORY_TRUNCATED");
            List<TrackingDaySummary> days = session.getAnalyticsDays();
            if (days.isEmpty()) return GoalProgress.unavailable(definition, "ITEM_HISTORY_UNAVAILABLE");
            for (TrackingDaySummary day : days)
            {
                if (day == null) continue;
                if (!day.isGainedItemTotalsAvailable())
                    return GoalProgress.unavailable(definition, "ITEM_HISTORY_UNAVAILABLE");
                for (DailyRollup.ItemTotal item : day.getGainedItemTotals().values())
                {
                    if (item != null && item.getItemId() == definition.getItemId())
                    {
                        itemCount = saturatingAdd(itemCount, item.getQuantity());
                    }
                }
            }
        }
        return GoalProgress.calculate(definition, metrics.getNet(), metrics.getElapsedMillis(),
            kills, itemCount);
    }

    private GoalProgress goalProgressForToday(GoalDefinition definition, long now)
    {
        ZoneId zone = profileZone();
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        List<DailyRollup> rollups = getDailyRollups(today, today);
        if (rollups.isEmpty()) return GoalProgress.unavailable(definition, "TODAY_DATA_UNAVAILABLE");
        long revenue = 0L;
        long costs = 0L;
        long activeMillis = 0L;
        long kills = 0L;
        long itemCount = 0L;
        for (DailyRollup rollup : rollups)
        {
            if (!zone.equals(rollup.getZone()))
                return GoalProgress.unavailable(definition, "TODAY_ZONE_PARTIAL");
            if (definition.getKind() == GoalDefinition.Kind.KILLS)
            {
                if (!rollup.isComplete(DailyRollup.Dimension.EVENT_COUNTS)
                    || !rollup.isComplete(DailyRollup.Dimension.PVM_ENCOUNTERS))
                    return GoalProgress.unavailable(definition, "TODAY_KILL_COUNTS_UNAVAILABLE");
                kills = saturatingAdd(kills, rollup.getKills());
                kills = saturatingAdd(kills, rollup.getPvmEncounterCount());
            }
            else if (definition.getKind() == GoalDefinition.Kind.ITEM_COUNT)
            {
                DailyRollup.Coverage gainCoverage =
                    rollup.getCoverage(DailyRollup.Dimension.GAINED_ITEMS);
                if (gainCoverage != DailyRollup.Coverage.COMPLETE)
                    return GoalProgress.unavailable(definition, "TODAY_ITEM_COUNTS_PARTIAL");
                for (DailyRollup.ItemTotal item : rollup.getGainedItemTotals().values())
                {
                    if (item != null && item.getItemId() == definition.getItemId())
                    {
                        itemCount = saturatingAdd(itemCount, item.getQuantity());
                    }
                }
                // A complete row with no selected item proves zero for that date.
            }
            if (definition.getKind() == GoalDefinition.Kind.NET
                || definition.getKind() == GoalDefinition.Kind.GP_PER_HOUR)
            {
                if (!rollup.isComplete(DailyRollup.Dimension.ACCOUNTING)
                    || !rollup.isComplete(DailyRollup.Dimension.ACTIVE_TIME))
                    return GoalProgress.unavailable(definition, "TODAY_TOTALS_UNAVAILABLE");
                revenue = saturatingAdd(revenue, rollup.getRevenueGp());
                costs = saturatingAdd(costs, rollup.getCostsGp());
                activeMillis = saturatingAdd(activeMillis, rollup.getActiveMillis());
            }
        }
        return GoalProgress.calculate(definition, saturatingSubtract(revenue, costs),
            activeMillis, kills, itemCount);
    }

    public synchronized void setGoalDefinitions(List<GoalDefinition> definitions)
    {
        goalDefinitions = new ArrayList<>();
        if (definitions != null)
        {
            for (GoalDefinition definition : definitions)
            {
                if (definition != null) goalDefinitions.add(definition.copy());
            }
        }
    }

    /** Samples current progress only on engine mutation/clock paths, never from a read API. */
    private void evaluateGoalAlerts(long now, boolean timeSensitiveOnly)
    {
        if (activeSession == null || activeSession.isClosed()) return;
        String sessionId = activeSession.getId();
        for (GoalDefinition definition : goalDefinitions)
        {
            if (definition == null || !definition.isConfigured()) continue;
            if (timeSensitiveOnly && definition.getKind() != GoalDefinition.Kind.GP_PER_HOUR) continue;

            String key = sessionId + "\u0000" + definition.getId();
            String fingerprint = goalFingerprint(definition);
            GoalProgress progress = getGoalProgress(definition, now);
            GoalAlertObservation previous = goalAlertObservations.get(key);
            if (!progress.isAvailable())
            {
                if (previous != null) previous.needsBaseline = true;
                continue;
            }
            if (previous == null || previous.needsBaseline
                || !previous.definitionFingerprint.equals(fingerprint))
            {
                goalAlertObservations.put(key, new GoalAlertObservation(fingerprint, progress.isReached()));
                continue;
            }

            boolean crossed = !previous.reached && progress.isReached();
            previous.reached = progress.isReached();
            if (crossed && !goalAlertSent.contains(key))
            {
                String scope = definition.getScope().name().toLowerCase(java.util.Locale.ROOT);
                String kind = definition.getKind().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
                String detail = scope + " " + kind + " goal reached ("
                    + progress.getCurrentValue() + " / " + progress.getTargetValue() + ").";
                if (recordAlert(new AlertEvent(AlertKind.GOAL_REACHED, now, sessionId,
                    "Goal reached", detail, progress.getCurrentValue(),
                    definition.getKind() == GoalDefinition.Kind.ITEM_COUNT ? definition.getItemId() : -1)))
                {
                    goalAlertSent.add(key);
                }
            }
        }
    }

    private static String goalFingerprint(GoalDefinition definition)
    {
        return definition.getScope().name() + ":" + definition.getKind().name() + ":"
            + definition.getTargetValue() + ":" + definition.getItemId();
    }

    private void observeNotableDrop(ProfitTransaction transaction, long now)
    {
        int threshold = config == null ? 0 : config.notableDropThresholdGp();
        if (transaction == null || !transaction.isCounted() || threshold <= 0
            || (transaction.getAutomaticType() != TransactionType.LOOT
                && transaction.getAutomaticType() != TransactionType.PK_LOOT)
            || now < transaction.getTimestampEpochMillis()
            || now - transaction.getTimestampEpochMillis() > 30L * 60_000L)
        {
            return;
        }
        for (ItemFlow flow : transaction.getFlows())
        {
            if (flow == null || flow.getQuantityDelta() <= 0L
                || flow.getValueDelta() < threshold)
            {
                continue;
            }
            String itemName = flow.getItemName() == null || flow.getItemName().trim().isEmpty()
                ? "Item " + flow.getItemId() : flow.getItemName().trim();
            recordAlert(new AlertEvent(AlertKind.NOTABLE_DROP,
                transaction.getTimestampEpochMillis(), activeSession == null ? null : activeSession.getId(),
                "Notable drop", itemName + " · " + flow.getValueDelta() + " GP",
                flow.getValueDelta(), flow.getItemId()));
            return; // One settled receipt creates at most one drop alert.
        }
    }

    private void observeWealthMilestone(WealthSnapshotHistory.Snapshot observed, long now)
    {
        int step = config == null ? 0 : config.wealthMilestoneGp();
        if (step <= 0)
        {
            lastWealthMilestoneStepGp = 0;
            lastCompleteWealthTotalGp = null;
            return;
        }
        if (step != lastWealthMilestoneStepGp)
        {
            // A changed threshold starts with a fresh baseline; it must not
            // manufacture an alert from wealth that predated the preference.
            lastWealthMilestoneStepGp = step;
            lastCompleteWealthTotalGp = null;
        }
        Long total = WealthBreakdown.fromSnapshot(observed).getTotalGp();
        if (total == null) return;
        if (lastCompleteWealthTotalGp == null)
        {
            lastCompleteWealthTotalGp = total;
            return;
        }

        long previous = lastCompleteWealthTotalGp;
        lastCompleteWealthTotalGp = total;
        if (total <= previous) return;
        long firstMilestone = previous / step + 1L;
        long lastMilestone = total / step;
        long crossed = lastMilestone - firstMilestone + 1L;
        if (crossed <= 0L) return;

        if (crossed > MAX_WEALTH_MILESTONE_EVENTS_PER_READ)
        {
            long latest = lastMilestone * (long) step;
            recordAlert(new AlertEvent(AlertKind.WEALTH_MILESTONE, now,
                activeSession == null ? null : activeSession.getId(), "Wealth milestones crossed",
                "Observed holdings rose from " + previous + " to " + total + " GP, crossing "
                    + crossed + " milestones of " + step + " GP each; latest " + latest + " GP.",
                latest, -1));
            return;
        }
        for (long offset = 0L; offset < crossed; offset++)
        {
            long milestone = firstMilestone + offset;
            long target = milestone * (long) step;
            recordAlert(new AlertEvent(AlertKind.WEALTH_MILESTONE, now,
                activeSession == null ? null : activeSession.getId(),
                "Wealth milestone · " + target + " GP",
                "Complete observed holdings value crossed the " + target + " GP milestone.",
                target, -1));
        }
    }

    private boolean maybeAutoEndIdleCustomSession(long now)
    {
        if (sessionIdleAutoEndMinutes <= 0 || customSession == null
            || activeSession != customSession || customSession.isClosed()
            || !customSession.isPaused() || customSession.getPauseReason() != PauseReason.IDLE)
        {
            return false;
        }
        long idleStartedAt = customSession.getPausedAtEpochMillis();
        if (now < idleStartedAt) return false;
        long idleLimitMillis = (long) sessionIdleAutoEndMinutes * 60_000L;
        if (now - idleStartedAt < idleLimitMillis) return false;
        return finishCustomSessionAt(idleStartedAt, now, SessionEndReason.IDLE, true);
    }

    private void clearTransientAlertState()
    {
        recentAlerts.clear();
        lastCompleteWealthTotalGp = null;
        lastWealthMilestoneStepGp = 0;
        goalAlertObservations.clear();
        goalAlertSent.clear();
    }

    private void pruneGoalAlertStateForSession(String sessionId)
    {
        String prefix = sessionId + "\u0000";
        goalAlertObservations.keySet().removeIf(key -> key.startsWith(prefix));
        goalAlertSent.removeIf(key -> key.startsWith(prefix));
    }

    public synchronized TileLayout getTileLayout()
    {
        return tileLayout == null ? TileLayout.legacyDefaults() : new TileLayout(tileLayout.getPages());
    }

    public synchronized void setTileLayout(TileLayout layout)
    {
        tileLayout = layout == null ? TileLayout.legacyDefaults() : new TileLayout(layout.getPages());
    }

    /** Arm the narrow load intent with the target identity later used by Check. */
    public synchronized boolean markChargeLoadTransfer(
        MeasuredChargeRead.Variant variant,
        int selectedItemId,
        @Nullable String selectedItemName,
        @Nullable String targetIdentity,
        int ticks)
    {
        return chargeLoadTransferEvidence.arm(
            variant, selectedItemId, selectedItemName, targetIdentity, ticks);
    }

    /** Clear a pending charge-load intent when another action supersedes its evidence. */
    public synchronized void clearChargeLoadTransfer()
    {
        chargeLoadTransferEvidence.clear();
    }

    /**
     * Feed one exact numeric charge Check/receipt read to the session-owned
     * baseline. The first read and every incompatible/reset read returns null.
     */
    @Nullable
    public synchronized MeasuredChargeDelta observeMeasuredChargeRead(
        @Nullable MeasuredChargeRead read,
        @Nullable String targetIdentity)
    {
        return observeMeasuredChargeRead(read, targetIdentity, System.currentTimeMillis());
    }

    /** Observe Check evidence and reconcile only same-target, measured load increases. */
    @Nullable
    public synchronized MeasuredChargeDelta observeMeasuredChargeRead(
        @Nullable MeasuredChargeRead read,
        @Nullable String targetIdentity,
        long now)
    {
        MeasuredChargeRead previous = measuredChargeReadTracker.getBaseline();
        String previousIdentity = measuredChargeReadTracker.getBaselineIdentity();
        long previousObservedAt = measuredChargeReadTracker.getBaselineAtEpochMillis();
        Map<Integer, Long> exactLoadQuantities = Collections.emptyMap();
        if (read != null && targetIdentity != null && targetIdentity.equals(previousIdentity))
        {
            exactLoadQuantities = ChargeRecipeCatalogue.exactLoadQuantities(previous, read);
        }
        MeasuredChargeDelta delta = measuredChargeReadTracker.observe(read, targetIdentity, now);
        if (!exactLoadQuantities.isEmpty())
        {
            reconcileChargeLoadReviews(
                read.getVariant(), targetIdentity, exactLoadQuantities, previousObservedAt, now);
        }
        return delta;
    }

    /** A Check without trustworthy target identity invalidates the previous baseline. */
    public synchronized void resetMeasuredChargeReads()
    {
        measuredChargeReadTracker.reset();
    }

    /** LMS / raid bag / GIM invent swap — TRANSFER baseline refresh. */
    public synchronized void markMinigameTransfer(String note, int ticks)
    {
        MinigameTransferClassifier.Event event = MinigameTransferClassifier.classify(note);
        String transferNote = event == null
            ? (note == null || note.trim().isEmpty() ? "Minigame ownership-neutral transfer" : note.trim())
            : MinigameTransferClassifier.transferNote(event);
        markContext(TrackingContext.TRANSFER, Math.max(1, ticks), transferNote);
    }

    /** Start tracking the items removed by entering a region-based neutral zone. */
    public synchronized void beginNeutralZoneTransfer()
    {
        neutralZoneStoredItems.clear();
        neutralZoneTransferActive = true;
        captureNeutralZoneEntry = true;
        neutralZoneEntryBaseline = baseline;
        // Inventory and equipment callbacks can settle in separate batches. Keep
        // reconciling against the entry snapshot for a short, fixed window so the
        // later batch is included, but in-zone supply use cannot be captured forever.
        neutralZoneEntryCaptureTicks = Math.max(8, config.stabilizationTicks() + 6);
        neutralZoneRestoreTicks = 0;
    }

    /**
     * End the region-based transfer context. Only a matching return of an item actually
     * removed on entry remains neutral; later lobby rewards keep their normal classification.
     */
    public synchronized void endNeutralZoneTransfer(int ticks)
    {
        boolean zoneWasActive = neutralZoneTransferActive;
        neutralZoneTransferActive = false;
        captureNeutralZoneEntry = false;
        neutralZoneEntryBaseline = null;
        neutralZoneEntryCaptureTicks = 0;
        neutralZoneRestoreTicks = neutralZoneStoredItems.isEmpty() ? 0 : Math.max(1, ticks);

        boolean pendingZoneContext = pendingContext == TrackingContext.TRANSFER
            && pendingContextNote != null
            && pendingContextNote.startsWith("Neutral zone instance");
        boolean activeZoneContext = context == TrackingContext.TRANSFER
            && contextNote != null
            && contextNote.startsWith("Neutral zone instance");
        if (pendingZoneContext)
        {
            pendingContext = TrackingContext.GENERIC;
            pendingContextNote = "";
            pendingExpectedLoot = Collections.emptyMap();
            pendingEncounterId = null;
            pendingTransferEvidence = false;
            pendingHardTransferEvidence = false;
        }
        if (activeZoneContext)
        {
            context = TrackingContext.GENERIC;
            contextTicks = 0;
            contextNote = "";
            contextExpectedLoot = Collections.emptyMap();
            contextEncounterId = null;
        }
        if (zoneWasActive || pendingZoneContext || activeZoneContext)
        {
            transferEvidenceTicks = 0;
            clearHardTransferEvidence();
        }
    }

    /** Begin clue/casket path so dig/tele/key spends pair as clue costs. */
    public synchronized void beginCluePath(String encounterId, String label)
    {
        clueCostPairing.beginClue(encounterId, label);
    }

    public synchronized void endCluePath()
    {
        clueCostPairing.endClue();
    }

    public synchronized ClueCostPairing clueCostPairing()
    {
        return clueCostPairing;
    }

    public synchronized LootKeyLifecycle lootKeyLifecycle()
    {
        return lootKeyLifecycle;
    }

    public synchronized List<ProfitSession> getLootKeySessions()
    {
        LinkedHashSet<ProfitSession> sessions = new LinkedHashSet<>();
        sessions.addAll(history);
        if (generalSession != null)
        {
            sessions.add(generalSession);
        }
        if (customSession != null)
        {
            sessions.add(customSession);
        }
        if (activeSession != null)
        {
            sessions.add(activeSession);
        }
        return Collections.unmodifiableList(new ArrayList<>(sessions));
    }

    public synchronized void beginLootKeyLocalDeathSettle(Map<Integer, Long> keyInventory)
    {
        lootKeyLifecycle.beginLocalDeath(keyInventory);
    }

    public synchronized void tickLootKeyLifecycle()
    {
        lootKeyLifecycle.tick();
    }

    /** Arm/cancel ordinary key-chest provenance from an actual chest object click. */
    public synchronized boolean observeKeyChestInteraction(String option, String target)
    {
        return deferredChestClaimLifecycle.observeMenuOption(option, target);
    }

    public synchronized void tickDeferredChestClaimLifecycle()
    {
        deferredChestClaimLifecycle.tick();
    }

    /**
     * Returns inclusive local-date rollups. The profile merge reads bounded
     * session-day aggregates only; receipt lists are never scanned here.
     * Legacy days retained in a different zone remain separately labelled.
     */
    public synchronized List<DailyRollup> getDailyRollups(LocalDate fromDate, LocalDate toDate)
    {
        if (fromDate == null || toDate == null || fromDate.isAfter(toDate))
        {
            return Collections.emptyList();
        }
        List<DailyRollup> result = new ArrayList<>();
        for (DailyRollup rollup : refreshDailyRollups())
        {
            LocalDate date = rollup.getDate();
            if (!date.isBefore(fromDate) && !date.isAfter(toDate))
            {
                result.add(hasActiveContributionFilter()
                    ? rollup.withoutUnfilteredContributions() : rollup);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Returns retained profile totals selected through {@code now}. Each
     * (date, zone) uses its profile daily rollup when present, otherwise
     * retained session-day summaries; the two sources are never added for the
     * same key. These are day-key aggregates rather than intraday accounting
     * snapshots. This read does not advance session clocks; live owners advance
     * only from engine tick and timestamped lifecycle events. Active and paused
     * owners are deduplicated by session id, and receipts are never
     * scanned. Free play is excluded from named-session starts. Coverage stays
     * unavailable where legacy owner or accounting evidence is ambiguous.
     */
    public synchronized OverallTotalsSnapshot getOverallTotals(long now)
    {
        return overallTotals(now, null);
    }

    /**
     * Returns the same day-key facts for the profile-zone local date containing
     * {@code now}. Active owners advance from engine tick and lifecycle events;
     * selecting a date is a read and does not advance their clocks.
     * Net remains the complete retained daily aggregate, not an intraday cut.
     */
    public synchronized OverallTotalsSnapshot getOverallToday(long now)
    {
        ZoneId zone = profileZone();
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        return overallTotals(now, today);
    }

    private OverallTotalsSnapshot overallTotals(long now, @Nullable LocalDate onlyDate)
    {
        ZoneId profileZone = profileZone();
        ensureOverallDayCache(now, profileZone);
        if (overallAllTimeCache == null)
        {
            overallAllTimeCache = aggregateOverallDays(now, null, profileZone);
            overallCacheAsOfNow = now;
        }
        else if (now < overallCacheAsOfNow)
        {
            // A caller may request an earlier profile date after a future-date
            // promotion. Rebuild only the compact day projection, never receipts
            // or session-day summaries, and restore all currently future keys.
            rebuildOverallAllTimeCache(now, profileZone);
        }
        else if (overallFutureDateBecameCurrent(now))
        {
            overallCacheAsOfNow = now;
        }
        if (onlyDate != null)
        {
            overallCacheAsOfNow = now;
            return overallTodaySnapshot(now, onlyDate, profileZone);
        }
        overallCacheAsOfNow = now;
        return overallAllTimeCache;
    }

    private void rebuildOverallAllTimeCache(long now, ZoneId profileZone)
    {
        overallFutureDayKeys.clear();
        for (Map.Entry<String, OverallDayCacheEntry> entry : overallDayCache.entrySet())
        {
            OverallDayCacheEntry day = entry.getValue();
            if (day != null && isFutureOverallDay(day.date, day.zoneId, now))
                overallFutureDayKeys.add(entry.getKey());
        }
        overallAllTimeCache = aggregateOverallDays(now, null, profileZone);
        overallCacheAsOfNow = now;
    }

    private OverallTotalsSnapshot overallTodaySnapshot(long now, LocalDate today, ZoneId profileZone)
    {
        OverallDayCacheEntry day = overallDayCache.get(overallDayKey(today, profileZone));
        long net = day == null ? 0L : day.netGp;
        long activeMillis = day == null ? 0L : day.activeMillis;
        int namedStarts = day == null ? 0 : day.namedStarts;
        DailyRollup.Coverage netCoverage = day == null ? DailyRollup.Coverage.COMPLETE : day.netCoverage;
        DailyRollup.Coverage activeCoverage = day == null
            ? DailyRollup.Coverage.COMPLETE : day.activeCoverage;
        DailyRollup.Coverage namedCoverage = day == null
            ? DailyRollup.Coverage.COMPLETE : day.namedCoverage;
        DailyRollup.Coverage daysCoverage = day == null
            ? DailyRollup.Coverage.COMPLETE : day.daysCoverage;
        if (overallCacheFiltered)
        {
            net = 0L;
            netCoverage = DailyRollup.Coverage.UNAVAILABLE;
        }
        if (overallCacheUnknownNamedStartTimestamp)
        {
            // The owner is known to be eligible for named-session counting,
            // but its start date is absent and cannot be safely placed.
            namedCoverage = weakerOverallCoverage(namedCoverage, DailyRollup.Coverage.UNAVAILABLE);
        }
        if (overallCacheMissingStartTimestamp)
            daysCoverage = weakerOverallCoverage(daysCoverage, DailyRollup.Coverage.PARTIAL);

        return new OverallTotalsSnapshot(profileZone.getId(), today, net, activeMillis,
            namedStarts, day == null ? 0 : 1, day == null ? null : today,
            day == null ? "" : profileZone.getId(), netCoverage, activeCoverage,
            namedCoverage, daysCoverage);
    }

    private OverallTotalsSnapshot aggregateOverallDays(long now, @Nullable LocalDate onlyDate,
        ZoneId profileZone)
    {
        long net = 0L;
        long activeMillis = 0L;
        int namedStarts = 0;
        int trackedDays = 0;
        LocalDate firstDate = null;
        String firstZone = "";
        DailyRollup.Coverage netCoverage = DailyRollup.Coverage.COMPLETE;
        DailyRollup.Coverage activeCoverage = DailyRollup.Coverage.COMPLETE;
        DailyRollup.Coverage namedCoverage = DailyRollup.Coverage.COMPLETE;
        DailyRollup.Coverage daysCoverage = DailyRollup.Coverage.COMPLETE;
        for (OverallDayCacheEntry value : overallDayCache.values())
        {
            if (!onlyDateApplies(value.date, value.zoneId, onlyDate, profileZone)
                || isFutureOverallDay(value.date, value.zoneId, now)) continue;
            trackedDays++;
            net = saturatingAdd(net, value.netGp);
            activeMillis = saturatingAdd(activeMillis, value.activeMillis);
            namedStarts = saturatingIntAdd(namedStarts, value.namedStarts);
            netCoverage = weakerOverallCoverage(netCoverage, value.netCoverage);
            activeCoverage = weakerOverallCoverage(activeCoverage, value.activeCoverage);
            namedCoverage = weakerOverallCoverage(namedCoverage, value.namedCoverage);
            daysCoverage = weakerOverallCoverage(daysCoverage, value.daysCoverage);
            if (firstDate == null || value.date.isBefore(firstDate)
                || value.date.equals(firstDate) && value.zoneId.compareTo(firstZone) < 0)
            {
                firstDate = value.date;
                firstZone = value.zoneId;
            }
        }
        if (overallCacheFiltered)
        {
            net = 0L;
            netCoverage = DailyRollup.Coverage.UNAVAILABLE;
        }
        if (overallCacheUnknownNamedStartTimestamp)
            namedCoverage = weakerOverallCoverage(namedCoverage, DailyRollup.Coverage.UNAVAILABLE);
        if (overallCacheMissingStartTimestamp)
            daysCoverage = weakerOverallCoverage(daysCoverage, DailyRollup.Coverage.PARTIAL);

        return new OverallTotalsSnapshot(profileZone.getId(), onlyDate, net, activeMillis,
            namedStarts, trackedDays, firstDate, firstZone, netCoverage, activeCoverage,
            namedCoverage, daysCoverage);
    }

    private boolean overallFutureDateBecameCurrent(long now)
    {
        boolean changed = false;
        java.util.Iterator<String> iterator = overallFutureDayKeys.iterator();
        while (iterator.hasNext())
        {
            String key = iterator.next();
            OverallDayCacheEntry value = overallDayCache.get(key);
            if (value == null)
            {
                iterator.remove();
                changed = true;
            }
            else if (!isFutureOverallDay(value.date, value.zoneId, now))
            {
                iterator.remove();
                overallAllTimeCache = includeOverallDay(overallAllTimeCache, value, profileZone());
                changed = true;
            }
        }
        return changed;
    }

    private static OverallTotalsSnapshot includeOverallDay(OverallTotalsSnapshot totals,
        OverallDayCacheEntry day, ZoneId profileZone)
    {
        if (totals == null || day == null) return totals;
        int days = saturatingIntAdd(totals.getDaysTracked(), 1);
        LocalDate firstDate = totals.getFirstTrackedDate();
        String firstZone = totals.getFirstTrackedZoneId();
        if (firstDate == null || day.date.isBefore(firstDate)
            || day.date.equals(firstDate) && day.zoneId.compareTo(firstZone) < 0)
        {
            firstDate = day.date;
            firstZone = day.zoneId;
        }
        return new OverallTotalsSnapshot(profileZone.getId(), null,
            saturatingAdd(totals.getNetGp(), day.netGp),
            saturatingAdd(totals.getActiveMillis(), day.activeMillis),
            saturatingIntAdd(totals.getNamedSessionsStarted(), day.namedStarts), days,
            firstDate, firstZone, weakerOverallCoverage(totals.getNetCoverage(), day.netCoverage),
            weakerOverallCoverage(totals.getActiveTimeCoverage(), day.activeCoverage),
            weakerOverallCoverage(totals.getNamedSessionStartsCoverage(), day.namedCoverage),
            weakerOverallCoverage(totals.getDaysTrackedCoverage(), day.daysCoverage));
    }

    private static boolean onlyDateApplies(LocalDate valueDate, String valueZoneId,
        @Nullable LocalDate onlyDate, ZoneId profileZone)
    {
        return onlyDate == null || onlyDate.equals(valueDate)
            && profileZone.getId().equals(valueZoneId);
    }

    private static String overallDayKey(LocalDate date, ZoneId zone)
    {
        return date + "@" + zone.getId();
    }

    private static boolean isFutureOverallDay(LocalDate date, String zoneId, long now)
    {
        try
        {
            return date.isAfter(Instant.ofEpochMilli(now).atZone(ZoneId.of(zoneId)).toLocalDate());
        }
        catch (RuntimeException invalidZone)
        {
            return false;
        }
    }

    /** Advance only current owners from an engine clock event; history cannot accrue. */
    private void advanceLiveOwnerAnalytics(long now)
    {
        Set<String> seen = new HashSet<>();
        ProfitSession[] owners = {generalSession, customSession, activeSession};
        for (ProfitSession session : owners)
        {
            if (session == null || session.isClosed() || session.isPaused()
                || !seen.add(session.getId())) continue;
            session.advanceAnalyticsActiveTime(now);
        }
    }

    /**
     * Applies exact live-clock accruals to their local day rows. These updates
     * cannot affect accounting, starts, or retained non-live days, so they do
     * not rebuild the complete profile rollup.
     */
    private synchronized void applyLiveActiveTimeDeltas(
        List<ProfitSession.AnalyticsActiveTimeDelta> deltas)
    {
        if (deltas == null || deltas.isEmpty()) return;
        // Live active time changes the rollups; the Overall cache is patched in place below,
        // the rollup cache is simply dropped (it is rebuilt on the next read, never per tick).
        rollupGeneration = rollupGeneration == Long.MAX_VALUE ? 1L : rollupGeneration + 1L;
        rollupCache = null;
        boolean filtered = hasActiveContributionFilter();
        ZoneId profileZone = profileZone();
        if (overallCacheBuiltGeneration != overallCacheGeneration
            || !overallCacheZoneId.equals(profileZone.getId())
            || overallCacheFiltered != filtered) return;

        for (ProfitSession.AnalyticsActiveTimeDelta delta : deltas)
        {
            if (delta == null || delta.getDate() == null || delta.getActiveMillis() <= 0L) continue;
            ZoneId dayZone;
            try { dayZone = ZoneId.of(delta.getZoneId()); }
            catch (RuntimeException invalidZone) { dayZone = ZoneOffset.UTC; }
            String key = overallDayKey(delta.getDate(), dayZone);
            OverallDayCacheEntry previous = overallDayCache.get(key);
            boolean newDay = previous == null;
            if (newDay)
            {
                boolean profileLocal = profileZone.equals(dayZone);
                previous = new OverallDayCacheEntry(delta.getDate(), dayZone.getId(), 0L, 0L, 0,
                    filtered ? DailyRollup.Coverage.UNAVAILABLE : DailyRollup.Coverage.COMPLETE,
                    profileLocal ? DailyRollup.Coverage.COMPLETE : DailyRollup.Coverage.PARTIAL,
                    profileLocal ? DailyRollup.Coverage.COMPLETE : DailyRollup.Coverage.PARTIAL,
                    DailyRollup.Coverage.COMPLETE, "live-active:" + key);
            }
            long nextActive = saturatingAdd(previous.activeMillis, delta.getActiveMillis());
            overallDayCache.put(key, new OverallDayCacheEntry(previous.date, previous.zoneId,
                previous.netGp, nextActive, previous.namedStarts, previous.netCoverage,
                previous.activeCoverage, previous.namedCoverage, previous.daysCoverage,
                previous.signature + "|active+" + delta.getActiveMillis()));

            boolean futureAtCachedClock = isFutureOverallDay(delta.getDate(), dayZone.getId(),
                overallCacheAsOfNow);
            if (futureAtCachedClock)
            {
                overallFutureDayKeys.add(key);
                continue;
            }

            if (overallAllTimeCache != null)
            {
                int days = overallAllTimeCache.getDaysTracked();
                LocalDate firstDate = overallAllTimeCache.getFirstTrackedDate();
                String firstZone = overallAllTimeCache.getFirstTrackedZoneId();
                if (newDay)
                {
                    days = saturatingIntAdd(days, 1);
                    if (firstDate == null || delta.getDate().isBefore(firstDate)
                        || delta.getDate().equals(firstDate)
                            && dayZone.getId().compareTo(firstZone) < 0)
                    {
                        firstDate = delta.getDate();
                        firstZone = dayZone.getId();
                    }
                }
                overallAllTimeCache = new OverallTotalsSnapshot(profileZone.getId(), null,
                    overallAllTimeCache.getNetGp(),
                    saturatingAdd(overallAllTimeCache.getActiveMillis(), delta.getActiveMillis()),
                    overallAllTimeCache.getNamedSessionsStarted(), days, firstDate, firstZone,
                    overallAllTimeCache.getNetCoverage(), overallAllTimeCache.getActiveTimeCoverage(),
                    overallAllTimeCache.getNamedSessionStartsCoverage(),
                    overallAllTimeCache.getDaysTrackedCoverage());
            }
        }
    }

    /** Rebuild all rollup projections only after a day, owner, filter, or baseline mutation. */
    private void ensureOverallDayCache(long now, ZoneId profileZone)
    {
        boolean filtered = hasActiveContributionFilter();
        if (overallCacheBuiltGeneration == overallCacheGeneration
            && overallCacheZoneId.equals(profileZone.getId())
            && overallCacheFiltered == filtered) return;

        overallDayCache.clear();
        overallFutureDayKeys.clear();
        for (DailyRollup rollup : refreshDailyRollups())
        {
            if (rollup == null) continue;
            String key = dailyRollupKey(rollup);
            // refreshDailyRollups() already uses each session summary to build
            // missing rollup dates and merges persisted dates by this same key.
            // Therefore this cache rebuild needs no second session/day walk.
            overallDayCache.put(key, cachedOverallRollup(key, rollup));
            if (isFutureOverallDay(rollup.getDate(), rollup.getZoneId(), now))
                overallFutureDayKeys.add(key);
        }

        overallCacheMissingStartTimestamp = false;
        overallCacheUnknownNamedStartTimestamp = false;
        for (ProfitSession session : uniqueProfileSessions())
        {
            if (session == null || session.getStartedAtEpochMillis() > 0L) continue;
            overallCacheMissingStartTimestamp = true;
            if (session.getOwnerKind() != SessionOwnerKind.FREE_PLAY)
                overallCacheUnknownNamedStartTimestamp = true;
        }
        overallCacheZoneId = profileZone.getId();
        overallCacheFiltered = filtered;
        overallCacheBuiltGeneration = overallCacheGeneration;
        overallAllTimeCache = aggregateOverallDays(now, null, profileZone);
        overallCacheAsOfNow = now;
    }

    private OverallDayCacheEntry cachedOverallRollup(String key, DailyRollup rollup)
    {
        boolean filtered = hasActiveContributionFilter();
        String signature = rollup.getZoneId() + "|" + rollup.getRevenueGp() + "|"
            + rollup.getCostsGp() + "|" + rollup.getActiveMillis() + "|"
            + rollup.getNamedSessionStarts() + "|" + rollup.getCoverage(DailyRollup.Dimension.ACCOUNTING)
            + "|" + rollup.getCoverage(DailyRollup.Dimension.ACTIVE_TIME) + "|"
            + rollup.getCoverage(DailyRollup.Dimension.NAMED_SESSION_STARTS) + "|" + filtered
            + "|" + rollup.getSourceSessionIds();
        OverallDayCacheEntry cached = overallDayCache.get(key);
        if (cached != null && cached.signature.equals(signature)) return cached;
        OverallDayCacheEntry rebuilt = new OverallDayCacheEntry(rollup.getDate(), rollup.getZoneId(),
            filtered ? 0L : rollup.getNetGp(), rollup.getActiveMillis(),
            rollup.getNamedSessionStarts(), filtered ? DailyRollup.Coverage.UNAVAILABLE
                : rollup.getCoverage(DailyRollup.Dimension.ACCOUNTING),
            rollup.getCoverage(DailyRollup.Dimension.ACTIVE_TIME),
            rollup.getCoverage(DailyRollup.Dimension.NAMED_SESSION_STARTS),
            DailyRollup.Coverage.COMPLETE, signature);
        overallDayCache.put(key, rebuilt);
        return rebuilt;
    }

    private static DailyRollup.Coverage weakerOverallCoverage(DailyRollup.Coverage left,
        DailyRollup.Coverage right)
    {
        if (left == null) return right == null ? DailyRollup.Coverage.UNAVAILABLE : right;
        if (right == null) return left;
        return left.ordinal() < right.ordinal() ? left : right;
    }

    private static final class OverallDayCacheEntry
    {
        private final LocalDate date;
        private final String zoneId;
        private final long netGp;
        private final long activeMillis;
        private final int namedStarts;
        private final DailyRollup.Coverage netCoverage;
        private final DailyRollup.Coverage activeCoverage;
        private final DailyRollup.Coverage namedCoverage;
        private final DailyRollup.Coverage daysCoverage;
        private final String signature;

        private OverallDayCacheEntry(LocalDate date, String zoneId, long netGp, long activeMillis,
            int namedStarts, DailyRollup.Coverage netCoverage,
            DailyRollup.Coverage activeCoverage, DailyRollup.Coverage namedCoverage,
            DailyRollup.Coverage daysCoverage, String signature)
        {
            this.date = date;
            this.zoneId = zoneId;
            this.netGp = netGp;
            this.activeMillis = activeMillis;
            this.namedStarts = namedStarts;
            this.netCoverage = netCoverage;
            this.activeCoverage = activeCoverage;
            this.namedCoverage = namedCoverage;
            this.daysCoverage = daysCoverage;
            this.signature = signature;
        }
    }

    /** Append a wealth observation to bounded profile history; it never creates a transaction. */
    public synchronized void recordWealthSnapshot(WealthLocationsSnapshot snapshot, long now)
    {
        if (snapshot == null || config == null) return;
        WealthSnapshotHistory.Snapshot observed;
        if (config.wealthHistoryEnabled())
        {
            wealthSnapshotHistory = wealthSnapshotHistory.append(withFreshCoinStores(snapshot, now), now).compact(now);
            observed = wealthSnapshotHistory.getLatest();
        }
        else
        {
            observed = WealthSnapshotHistory.empty().append(snapshot, now).getLatest();
        }
        observeWealthMilestone(observed, now);
    }

    /** Records a client-observed coin store; values are wealth-only and never accounting receipts. */
    public synchronized void observeCoinStore(CoinStore store, long value, long now)
    {
        if (store == null || value < 0L) return;
        coinStores.put(store, new CoinStoreObservation(value, now));
        coinStoresUnused.remove(store);
    }

    /** Clears a store when its client read is no longer trustworthy. */
    public synchronized void clearCoinStore(CoinStore store)
    {
        if (store != null) coinStores.remove(store);
    }

    private final java.util.Set<CoinStore> coinStoresUnused = java.util.EnumSet.noneOf(CoinStore.class);

    /**
     * The owner declares a store they never use (no POH servant, never at NMZ): it counts as zero
     * toward the derived "coffers" total instead of holding Wealth > Other unavailable. A later
     * observation of the store lifts the mark.
     */
    public synchronized void markCoinStoreUnused(CoinStore store, boolean unused)
    {
        if (store == null) return;
        if (unused)
        {
            coinStoresUnused.add(store);
            coinStores.remove(store);
        }
        else coinStoresUnused.remove(store);
    }

    public synchronized boolean isCoinStoreUnused(CoinStore store)
    {
        return store != null && coinStoresUnused.contains(store);
    }

    /** Stores that are neither fresh nor marked unused: what keeps Wealth > Other unavailable, in order. */
    public synchronized List<CoinStore> getCoinStoreGaps(long now)
    {
        List<CoinStore> gaps = new ArrayList<>();
        for (CoinStore store : CoinStore.values())
        {
            if (coinStoresUnused.contains(store)) continue;
            CoinStoreObservation observation = coinStores.get(store);
            if (observation == null || now - observation.observedAt > COIN_STORE_FRESHNESS_MILLIS) gaps.add(store);
        }
        return gaps;
    }

    private WealthLocationsSnapshot withFreshCoinStores(WealthLocationsSnapshot source, long now)
    {
        List<WealthLocationSnapshot> locations = new ArrayList<>();
        if (source != null) locations.addAll(source.getLocations());
        long coffersTotal = 0L;
        long coffersObservedAt = 0L;
        for (CoinStore store : CoinStore.values())
        {
            CoinStoreObservation observation = coinStores.get(store);
            if (observation == null || now - observation.observedAt > COIN_STORE_FRESHNESS_MILLIS) continue;
            locations.removeIf(row -> row != null && store.getLocationId().equalsIgnoreCase(row.getId()));
            locations.add(new WealthLocationSnapshot(store.getLocationId(), store.getTitle(),
                WealthLocationSnapshot.Status.AVAILABLE, observation.value, observation.observedAt,
                Collections.emptyList(), "Observed coin store"));
            coffersTotal = saturatingAdd(coffersTotal, observation.value);
            coffersObservedAt = Math.max(coffersObservedAt, observation.observedAt);
        }
        boolean coffersAlreadyCaptured = false;
        for (WealthLocationSnapshot row : locations)
        {
            if (row != null && "coffers".equalsIgnoreCase(row.getId())) coffersAlreadyCaptured = true;
        }
        if (!coffersAlreadyCaptured && getCoinStoreGaps(now).isEmpty())
        {
            // Every store is fresh or declared unused: the contract's "coffers" source is complete.
            locations.add(new WealthLocationSnapshot("coffers", "Coffers",
                WealthLocationSnapshot.Status.AVAILABLE, coffersTotal, coffersObservedAt == 0L ? now : coffersObservedAt,
                Collections.emptyList(), "Sum of the observed coin stores; unused stores count as zero"));
        }
        return new WealthLocationsSnapshot(source == null ? now : source.getCapturedAtEpochMillis(), locations);
    }

    private static final class CoinStoreObservation
    {
        private final long value;
        private final long observedAt;
        private CoinStoreObservation(long value, long observedAt) { this.value = value; this.observedAt = observedAt; }
    }

    /** Recent durable wealth points; the no-clock overload is convenient for consumers. */
    public synchronized List<WealthSnapshotHistory.Snapshot> getWealthTimeline(int days)
    {
        return getWealthTimeline(days, System.currentTimeMillis());
    }

    public synchronized List<WealthSnapshotHistory.Snapshot> getWealthTimeline(int days, long now)
    {
        return wealthSnapshotHistory.getTimeline(days, now);
    }

    /** Latest point-in-time breakdown captured no later than {@code now}. */
    public synchronized WealthBreakdown getWealthBreakdown(long now)
    {
        return WealthBreakdown.fromSnapshot(latestWealthSnapshotAtOrBefore(now));
    }

    /**
     * One slot for each of the trailing profile-local calendar dates, including
     * today. A date without a retained capture is an explicit gap; values are
     * never carried forward from another date.
     */
    public synchronized List<WealthTrendPoint> getWealthTrend(int days, long now)
    {
        if (days <= 0) return Collections.emptyList();
        ZoneId zone = profileZone();
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        LocalDate first;
        try
        {
            first = today.minusDays((long) days - 1L);
        }
        catch (RuntimeException outOfRange)
        {
            return Collections.emptyList();
        }

        Map<LocalDate, WealthSnapshotHistory.Snapshot> latestByDate = new HashMap<>();
        for (WealthSnapshotHistory.Snapshot snapshot : wealthSnapshotHistory.getSnapshots())
        {
            if (snapshot == null || snapshot.getCapturedAtEpochMillis() > now) continue;
            LocalDate date = Instant.ofEpochMilli(snapshot.getCapturedAtEpochMillis())
                .atZone(zone).toLocalDate();
            if (date.isBefore(first) || date.isAfter(today)) continue;
            WealthSnapshotHistory.Snapshot current = latestByDate.get(date);
            if (current == null || snapshot.getCapturedAtEpochMillis()
                >= current.getCapturedAtEpochMillis())
            {
                latestByDate.put(date, snapshot);
            }
        }

        List<WealthTrendPoint> result = new ArrayList<>(days);
        for (LocalDate date = first; !date.isAfter(today); date = date.plusDays(1L))
        {
            WealthSnapshotHistory.Snapshot snapshot = latestByDate.get(date);
            result.add(new WealthTrendPoint(date,
                snapshot == null ? null : WealthBreakdown.fromSnapshot(snapshot)));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Snapshot-derived seven-/thirty-day wealth deltas and latest bank share.
     * These facts do not include or modify counted Net.
     */
    public synchronized WealthChangeFacts getWealthChangeFacts(long now)
    {
        WealthSnapshotHistory.Snapshot latest = latestWealthSnapshotAtOrBefore(now);
        WealthBreakdown latestBreakdown = WealthBreakdown.fromSnapshot(latest);
        return new WealthChangeFacts(wealthChangeFact(7L, now, latest),
            wealthChangeFact(30L, now, latest), latestBreakdown);
    }

    private WealthChangeFacts.WindowChange wealthChangeFact(long days, long now,
        WealthSnapshotHistory.Snapshot latest)
    {
        if (latest == null) return WealthChangeFacts.WindowChange.unavailable();
        long elapsed = days * 24L * 60L * 60L * 1000L;
        long target = now < Long.MIN_VALUE + elapsed ? Long.MIN_VALUE : now - elapsed;
        WealthSnapshotHistory.Snapshot baseline = null;
        for (WealthSnapshotHistory.Snapshot snapshot : wealthSnapshotHistory.getSnapshots())
        {
            if (snapshot.getCapturedAtEpochMillis() <= target
                && (baseline == null || snapshot.getCapturedAtEpochMillis()
                    > baseline.getCapturedAtEpochMillis()))
            {
                baseline = snapshot;
            }
        }
        if (baseline == null || baseline.getCapturedAtEpochMillis()
            >= latest.getCapturedAtEpochMillis())
        {
            return WealthChangeFacts.WindowChange.unavailable();
        }

        WealthBreakdown before = WealthBreakdown.fromSnapshot(baseline);
        WealthBreakdown after = WealthBreakdown.fromSnapshot(latest);
        if (!before.isTotalAvailable() || !after.isTotalAvailable())
        {
            return WealthChangeFacts.WindowChange.unavailable();
        }
        long beforeGp = before.getTotalGp();
        long afterGp = after.getTotalGp();
        try
        {
            long changeGp = Math.subtractExact(afterGp, beforeGp);
            Double percent = beforeGp > 0L ? changeGp * 100.0d / beforeGp : null;
            return new WealthChangeFacts.WindowChange(baseline.getCapturedAtEpochMillis(),
                latest.getCapturedAtEpochMillis(), beforeGp, afterGp, changeGp, percent);
        }
        catch (ArithmeticException overflow)
        {
            return WealthChangeFacts.WindowChange.unavailable();
        }
    }

    private WealthSnapshotHistory.Snapshot latestWealthSnapshotAtOrBefore(long now)
    {
        List<WealthSnapshotHistory.Snapshot> snapshots = wealthSnapshotHistory.getSnapshots();
        for (int i = snapshots.size() - 1; i >= 0; i--)
        {
            WealthSnapshotHistory.Snapshot snapshot = snapshots.get(i);
            if (snapshot.getCapturedAtEpochMillis() <= now) return snapshot;
        }
        return null;
    }

    /** Most recent durable wealth point and its age at {@code now}. */
    public synchronized LatestWealthSnapshot getLatestWealth(long now)
    {
        return new LatestWealthSnapshot(wealthSnapshotHistory.getLatest(), now);
    }

    public synchronized LatestWealthSnapshot getLatestWealth()
    {
        return getLatestWealth(System.currentTimeMillis());
    }

    /** Maximum number of points retained before history reports that it has been capped. */
    public synchronized int getWealthSnapshotCap()
    {
        return wealthSnapshotHistory.getSnapshotCap();
    }

    public synchronized int getWealthItemCapPerLocation()
    {
        return wealthSnapshotHistory.getItemCapPerLocation();
    }

    public synchronized boolean isWealthHistoryCapped()
    {
        return wealthSnapshotHistory.isCapped();
    }

    /**
     * Compares the newest retained wealth point with the selected saved anchor.
     * Partial capture-day Net is allocated uniformly within each four-hour
     * daily-rollup bucket; unavailable/filter-redacted coverage fails closed.
     */
    public synchronized WealthChangeBreakdown getWealthChangeSince(WealthAnchor anchor, long now)
    {
        WealthPair pair = wealthPair(anchor, now);
        if (pair == null || !hasCompleteItemLists(pair.before) || !hasCompleteItemLists(pair.after))
            return WealthChangeBreakdown.unavailable();
        Long countedNet = countedNetBetween(pair.before.getCapturedAtEpochMillis(),
            pair.after.getCapturedAtEpochMillis());
        if (countedNet == null) return WealthChangeBreakdown.unavailable();
        return WealthChangeBreakdown.between(pair.before.toWealthLocationsSnapshot(),
            pair.after.toWealthLocationsSnapshot(), countedNet);
    }

    public synchronized WealthChangeBreakdown getWealthChangeSince(WealthAnchor anchor)
    {
        return getWealthChangeSince(anchor, System.currentTimeMillis());
    }

    /**
     * Returns the aggregate counted-Net mover plus measured item market repricing.
     * Per-item earned attribution is deliberately unavailable from day aggregates.
     */
    public synchronized List<WealthTopMover> getWealthTopMovers(
        WealthAnchor anchor, int limit, long now)
    {
        int cap = Math.max(0, limit);
        if (cap == 0) return Collections.emptyList();
        WealthPair pair = wealthPair(anchor, now);
        if (pair == null || !hasCompleteItemLists(pair.before) || !hasCompleteItemLists(pair.after))
        {
            return Collections.emptyList();
        }
        Long earned = countedNetBetween(pair.before.getCapturedAtEpochMillis(),
            pair.after.getCapturedAtEpochMillis());
        if (earned == null) return Collections.emptyList();
        WealthChangeBreakdown breakdown = WealthChangeBreakdown.between(
            pair.before.toWealthLocationsSnapshot(), pair.after.toWealthLocationsSnapshot(), earned);
        if (!breakdown.isAvailable()) return Collections.emptyList();

        List<WealthTopMover> movers = new ArrayList<>();
        if (earned != 0L)
        {
            movers.add(new WealthTopMover(WealthTopMover.Reason.EARNED, 0,
                "Counted net", earned));
        }
        Map<Integer, WealthHeldValue> before = heldValues(pair.before);
        Map<Integer, WealthHeldValue> after = heldValues(pair.after);
        try
        {
            for (Map.Entry<Integer, WealthHeldValue> entry : before.entrySet())
            {
                WealthHeldValue later = after.get(entry.getKey());
                WealthHeldValue earlier = entry.getValue();
                if (later == null || !earlier.consistent || !later.consistent
                    || earlier.source != later.source
                    || !WealthChangeBreakdown.isMarketPriceSource(earlier.source)
                    || earlier.unitPrice <= 0 || later.unitPrice <= 0) continue;
                long commonQuantity = Math.min(earlier.quantity, later.quantity);
                long market = Math.multiplyExact(commonQuantity,
                    (long) later.unitPrice - earlier.unitPrice);
                if (market != 0L)
                {
                    movers.add(new WealthTopMover(WealthTopMover.Reason.MARKET,
                        entry.getKey(), later.name, market));
                }
            }
        }
        catch (ArithmeticException ex)
        {
            return Collections.emptyList();
        }
        movers.sort(Comparator.comparingLong(
            (WealthTopMover mover) -> safeAbsolute(mover.getValueChangeGp())).reversed()
            .thenComparing(WealthTopMover::getName, String.CASE_INSENSITIVE_ORDER));
        if (movers.size() > cap) movers = new ArrayList<>(movers.subList(0, cap));
        return Collections.unmodifiableList(movers);
    }

    public synchronized List<WealthTopMover> getWealthTopMovers(WealthAnchor anchor, int limit)
    {
        return getWealthTopMovers(anchor, limit, System.currentTimeMillis());
    }

    private WealthPair wealthPair(WealthAnchor anchor, long now)
    {
        List<WealthSnapshotHistory.Snapshot> snapshots = wealthSnapshotHistory.getSnapshots();
        WealthSnapshotHistory.Snapshot after = null;
        for (int i = snapshots.size() - 1; i >= 0; i--)
        {
            if (snapshots.get(i).getCapturedAtEpochMillis() <= now)
            {
                after = snapshots.get(i);
                break;
            }
        }
        if (after == null) return null;
        WealthSnapshotHistory.Snapshot before = null;
        WealthAnchor selected = anchor == null ? WealthAnchor.LAST_BANK_VISIT : anchor;
        if (selected == WealthAnchor.LAST_BANK_VISIT)
        {
            for (int i = snapshots.size() - 1; i >= 0; i--)
            {
                WealthSnapshotHistory.Snapshot candidate = snapshots.get(i);
                if (candidate.getCapturedAtEpochMillis() < after.getCapturedAtEpochMillis())
                {
                    before = candidate;
                    break;
                }
            }
        }
        else
        {
            ZoneId zone = profileZone();
            long target;
            if (selected == WealthAnchor.TODAY)
            {
                LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
                target = today.atStartOfDay(zone).toInstant().toEpochMilli();
            }
            else
            {
                long days = selected == WealthAnchor.SEVEN_DAYS ? 7L : 30L;
                long elapsed = days * 24L * 60L * 60L * 1000L;
                target = now < Long.MIN_VALUE + elapsed ? Long.MIN_VALUE : now - elapsed;
            }
            for (int i = snapshots.size() - 1; i >= 0; i--)
            {
                WealthSnapshotHistory.Snapshot candidate = snapshots.get(i);
                if (candidate.getCapturedAtEpochMillis() <= target)
                {
                    before = candidate;
                    break;
                }
            }
        }
        return before == null || before.getCapturedAtEpochMillis() >= after.getCapturedAtEpochMillis()
            ? null : new WealthPair(before, after);
    }

    /** Returns null if any contributing daily Net/bucket coverage is unavailable or partial. */
    @Nullable
    private Long countedNetBetween(long fromEpochMillis, long toEpochMillis)
    {
        if (toEpochMillis < fromEpochMillis) return null;
        if (toEpochMillis == fromEpochMillis) return 0L;
        ZoneId zone = profileZone();
        Instant from = Instant.ofEpochMilli(fromEpochMillis);
        Instant to = Instant.ofEpochMilli(toEpochMillis);
        LocalDate first = from.atZone(zone).toLocalDate();
        LocalDate last = to.minusMillis(1L).atZone(zone).toLocalDate();
        long total = 0L;
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1L))
        {
            List<DailyRollup> dateRows = getDailyRollups(date, date);
            DailyRollup exact = null;
            for (DailyRollup row : dateRows)
            {
                if (zone.getId().equals(row.getZoneId())) exact = row;
                else return null;
            }
            if (exact == null)
            {
                long dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli();
                long dayEnd = date.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli();
                long overlapStart = Math.max(fromEpochMillis, dayStart);
                long overlapEnd = Math.min(toEpochMillis, dayEnd);
                if (hasProfileSessionOverlap(overlapStart, overlapEnd)) return null;
                continue; // No profile session overlapped this date, so its counted Net is zero.
            }
            if (exact.getCoverage(DailyRollup.Dimension.ACCOUNTING) != DailyRollup.Coverage.COMPLETE
                || exact.getCoverage(DailyRollup.Dimension.FOUR_HOUR_BUCKETS) != DailyRollup.Coverage.COMPLETE)
            {
                return null;
            }
            long[] buckets = exact.getFourHourNetGp();
            for (int bucket = 0; bucket < DailyRollup.FOUR_HOUR_BUCKET_COUNT; bucket++)
            {
                ZonedDateTime bucketStart = date.atTime(bucket * 4, 0).atZone(zone);
                ZonedDateTime bucketEnd = bucket == DailyRollup.FOUR_HOUR_BUCKET_COUNT - 1
                    ? date.plusDays(1L).atStartOfDay(zone)
                    : date.atTime((bucket + 1) * 4, 0).atZone(zone);
                long start = Math.max(fromEpochMillis, bucketStart.toInstant().toEpochMilli());
                long end = Math.min(toEpochMillis, bucketEnd.toInstant().toEpochMilli());
                if (end <= start) continue;
                long bucketMillis = Duration.between(bucketStart, bucketEnd).toMillis();
                if (bucketMillis <= 0L) return null;
                long piece;
                try
                {
                    java.math.BigDecimal value = java.math.BigDecimal.valueOf(buckets[bucket])
                        .multiply(java.math.BigDecimal.valueOf(end - start))
                        .divide(java.math.BigDecimal.valueOf(bucketMillis), 0,
                            java.math.RoundingMode.HALF_UP);
                    piece = value.longValueExact();
                    total = Math.addExact(total, piece);
                }
                catch (ArithmeticException ex)
                {
                    return null;
                }
            }
        }
        return total;
    }

    private boolean hasProfileSessionOverlap(long startEpochMillis, long endEpochMillis)
    {
        if (endEpochMillis <= startEpochMillis) return false;
        for (ProfitSession session : uniqueProfileSessions())
        {
            if (session == null) continue;
            long sessionStart = session.getStartedAtEpochMillis();
            if (sessionStart <= 0L) return true;
            long sessionEnd = session.isClosed() && session.getEndedAtEpochMillis() > 0L
                ? session.getEndedAtEpochMillis() : endEpochMillis;
            if (sessionStart < endEpochMillis && sessionEnd > startEpochMillis) return true;
        }
        return false;
    }

    private static boolean hasCompleteItemLists(WealthSnapshotHistory.Snapshot snapshot)
    {
        for (WealthSnapshotHistory.Location location : snapshot.getLocations())
        {
            for (WealthSnapshotHistory.Holding holding : location.getHoldings())
            {
                if (holding.isRemainder()) return false;
            }
        }
        return true;
    }

    private static Map<Integer, WealthHeldValue> heldValues(WealthSnapshotHistory.Snapshot snapshot)
    {
        Map<Integer, WealthHeldValue> result = new HashMap<>();
        for (WealthSnapshotHistory.Location location : snapshot.getLocations())
        {
            for (WealthSnapshotHistory.Holding holding : location.getHoldings())
            {
                if (holding.isRemainder() || holding.getQuantity() <= 0L) continue;
                WealthHeldValue existing = result.get(holding.getItemId());
                if (existing == null)
                {
                    result.put(holding.getItemId(), new WealthHeldValue(holding));
                }
                else
                {
                    existing.add(holding);
                }
            }
        }
        return result;
    }

    /** Current local-date window and the immediately preceding equal-length window. */
    public synchronized InsightsWindowSnapshot getInsightsWindow(int days, long now)
    {
        int windowDays = Math.max(1, days);
        ZoneId zone = profileZone();
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        LocalDate currentFrom = today.minusDays(windowDays - 1L);
        LocalDate previousTo = currentFrom.minusDays(1L);
        LocalDate previousFrom = previousTo.minusDays(windowDays - 1L);
        List<DailyRollup> currentRows = new ArrayList<>();
        List<DailyRollup> previousRows = new ArrayList<>();
        boolean currentHasLegacyZone = false;
        boolean previousHasLegacyZone = false;
        for (DailyRollup rawRollup : refreshDailyRollups())
        {
            DailyRollup rollup = hasActiveContributionFilter()
                ? rawRollup.withoutUnfilteredContributions() : rawRollup;
            LocalDate date = rollup.getDate();
            boolean isCurrentDate = !date.isBefore(currentFrom) && !date.isAfter(today);
            boolean isPreviousDate = !date.isBefore(previousFrom) && !date.isAfter(previousTo);
            if (zone.getId().equals(rollup.getZoneId()))
            {
                if (isCurrentDate) currentRows.add(rollup);
                else if (isPreviousDate) previousRows.add(rollup);
            }
            else if (!date.isBefore(currentFrom.minusDays(MAX_ZONE_DATE_LABEL_DELTA_DAYS))
                && !date.isAfter(today.plusDays(MAX_ZONE_DATE_LABEL_DELTA_DAYS)))
            {
                currentHasLegacyZone = true;
            }
            if (!zone.getId().equals(rollup.getZoneId())
                && !date.isBefore(previousFrom.minusDays(MAX_ZONE_DATE_LABEL_DELTA_DAYS))
                && !date.isAfter(previousTo.plusDays(MAX_ZONE_DATE_LABEL_DELTA_DAYS)))
            {
                previousHasLegacyZone = true;
            }
        }
        InsightsWindowSnapshot.Window current = InsightsWindowSnapshot.Window.aggregate(currentRows);
        InsightsWindowSnapshot.Window previous = InsightsWindowSnapshot.Window.aggregate(previousRows);
        if (currentHasLegacyZone) current = partialForLegacyZone(current);
        if (previousHasLegacyZone) previous = partialForLegacyZone(previous);
        current = addWindowPerformance(current, currentRows, currentFrom, today, zone);
        previous = addWindowPerformance(previous, previousRows, previousFrom, previousTo, zone);
        current = addPkWindowDetails(current, currentRows, currentFrom, today, now, zone,
            currentHasLegacyZone);
        previous = addPkWindowDetails(previous, previousRows, previousFrom, previousTo, now, zone,
            previousHasLegacyZone);
        return new InsightsWindowSnapshot(zone.getId(), currentFrom, today,
            previousFrom, previousTo, current, previous);
    }

    /** Canonical current/previous Insights windows, with their embedded PvP projections. */
    public synchronized InsightsWindowSnapshot getPkWindow(int days, long now)
    {
        return getInsightsWindow(days, now);
    }

    /**
     * Active-time and correction-aware encounter finances by the observed
     * presentation label. Place durations cover the whole tracked PK session,
     * not only combat ticks; unknown labels remain in the explicit remainder.
     */
    public synchronized PkPlaceSummaries getPkPlaceSummaries(int days, long now)
    {
        int windowDays = Math.max(1, days);
        ZoneId zone = profileZone();
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        LocalDate fromDate = today.minusDays(windowDays - 1L);
        long from = fromDate.atStartOfDay(zone).toInstant().toEpochMilli();
        long to = now == Long.MAX_VALUE ? now : now + 1L;
        Map<String, PkPlaceAccumulator> places = new LinkedHashMap<>();
        long unlabelledMillis = 0L;
        long unlabelledEncounters = 0L;
        boolean activeTimeAvailable = true;
        boolean financeAvailable = !hasActiveContributionFilter();
        boolean encounterCountsAvailable = true;
        boolean placeFinanceInitiallyAvailable = financeAvailable;
        Set<String> retainedSessionIds = new LinkedHashSet<>();

        for (ProfitSession session : uniqueProfileSessions())
        {
            if (session == null) continue;
            retainedSessionIds.add(session.getId());
            List<PkEncounter> encounters = session.getPkEncounters();
            boolean hasPkEncounterInWindow = false;
            for (PkEncounter encounter : encounters)
            {
                if (encounter != null && encounter.getTimestampEpochMillis() >= from
                    && encounter.getTimestampEpochMillis() < to)
                {
                    hasPkEncounterInWindow = true;
                    break;
                }
            }
            boolean pkSession = session.getMode() == com.gpmanager.model.SessionMode.PK
                || session.getCategory() == SessionCategory.PKING || hasPkEncounterInWindow;
            if (!pkSession) continue;

            PkLocationLedger ledger = session.getPkLocationLedger();
            if (ledger == null)
            {
                activeTimeAvailable = false;
            }
            else
            {
                activeTimeAvailable &= ledger.hasCompleteCoverageFrom(from,
                    session.getStartedAtEpochMillis());
                for (PkLocationLedger.Segment segment : ledger.getSegments(from, to, now))
                {
                    long duration = segment.getActiveMillis();
                    String label = segment.getLocationLabel();
                    if (label == null || label.trim().isEmpty())
                    {
                        unlabelledMillis = saturatingAdd(unlabelledMillis, duration);
                    }
                    else
                    {
                        places.computeIfAbsent(label,
                            key -> new PkPlaceAccumulator(key, placeFinanceInitiallyAvailable)).activeMillis =
                            saturatingAdd(places.get(label).activeMillis, duration);
                    }
                }
            }

            for (PkEncounter encounter : encounters)
            {
                if (encounter == null || encounter.getTimestampEpochMillis() < from
                    || encounter.getTimestampEpochMillis() >= to) continue;
                String label = encounter.getLocationLabel();
                if (label == null || label.trim().isEmpty())
                {
                    unlabelledEncounters++;
                    financeAvailable = false;
                    continue;
                }
                PkPlaceAccumulator place = places.computeIfAbsent(label,
                    key -> new PkPlaceAccumulator(key, placeFinanceInitiallyAvailable));
                place.firstAt = Math.min(place.firstAt, encounter.getTimestampEpochMillis());
                place.lastAt = Math.max(place.lastAt, encounter.getTimestampEpochMillis());
                boolean kill = encounter.getType() == PkEncounterType.KILL;
                if (kill) place.kills++;
                else place.deaths++;
                if (!encounter.isFinancialSummaryAvailable() || hasActiveContributionFilter())
                {
                    place.financeAvailable = false;
                    financeAvailable = false;
                    continue;
                }
                if (!encounter.isFinancialCostSplitAvailable()) place.financeAvailable = false;
                place.attachedSuppliesGp = saturatingAdd(place.attachedSuppliesGp,
                    encounter.getFinancialSuppliesCostGp());
                if (kill)
                {
                    place.netGp = saturatingAdd(place.netGp, encounter.getFinancialNetGp());
                }
                else
                {
                    place.netGp = saturatingSubtract(place.netGp,
                        encounter.getFinancialLossGp());
                }
            }
        }

        // Persisted PK rollups can outlive a trimmed session summary. Their
        // source ids prove there was PK activity in this date window, but the
        // discarded owner no longer has a location ledger or encounter labels
        // to attribute it by place. Fail closed instead of presenting the
        // retained subset as complete.
        boolean incompletePkPlaceCoverage = false;
        LocalDate legacyZoneFrom = fromDate.minusDays(MAX_ZONE_DATE_LABEL_DELTA_DAYS);
        LocalDate legacyZoneTo = today.plusDays(MAX_ZONE_DATE_LABEL_DELTA_DAYS);
        for (DailyRollup row : refreshDailyRollups())
        {
            if (row == null) continue;
            boolean sameZone = zone.getId().equals(row.getZoneId());
            if (!sameZone)
            {
                if (row.getDate().isBefore(legacyZoneFrom) || row.getDate().isAfter(legacyZoneTo))
                    continue;
                // A foreign-zone day can overlap the requested profile-local
                // range after a zone change. We cannot safely assign its active
                // time or finance to the current place labels.
                incompletePkPlaceCoverage = true;
                List<String> legacySources = row.getSourceSessionIds();
                if (legacySources.isEmpty()) encounterCountsAvailable = false;
                else
                {
                    for (String sourceId : legacySources)
                    {
                        if (!retainedSessionIds.contains(sourceId))
                        {
                            encounterCountsAvailable = false;
                            break;
                        }
                    }
                }
                continue;
            }
            if (row.getDate().isBefore(fromDate) || row.getDate().isAfter(today)) continue;

            boolean hasPkEvidence = hasPkPlaceEvidence(row);
            boolean rowPkSourceMissing = false;
            if (hasPkEvidence)
            {
                List<String> rowSources = row.getSourceSessionIds();
                if (rowSources.isEmpty()) rowPkSourceMissing = true;
                for (String sourceId : rowSources)
                {
                    if (!retainedSessionIds.contains(sourceId))
                    {
                        rowPkSourceMissing = true;
                        break;
                    }
                }
            }
            boolean persistedPkEvidence = false;
            boolean persistedPkSourceMissing = false;
            if (!row.isComplete(DailyRollup.Dimension.PVP))
            {
                for (DailyRollup persisted : persistedDailyRollupBaseline)
                {
                    if (persisted != null && zone.getId().equals(persisted.getZoneId())
                        && row.getDate().equals(persisted.getDate())
                        && hasPkPlaceEvidence(persisted))
                    {
                        persistedPkEvidence = true;
                        List<String> persistedSources = persisted.getSourceSessionIds();
                        if (persistedSources.isEmpty()) persistedPkSourceMissing = true;
                        for (String sourceId : persistedSources)
                        {
                            if (!retainedSessionIds.contains(sourceId))
                            {
                                persistedPkSourceMissing = true;
                                break;
                            }
                        }
                        break;
                    }
                }
            }
            if (!row.isComplete(DailyRollup.Dimension.PVP)
                && (hasPkEvidence || persistedPkEvidence))
            {
                incompletePkPlaceCoverage = true;
                encounterCountsAvailable &= !rowPkSourceMissing && !persistedPkSourceMissing;
            }
            if (!hasPkEvidence) continue;
            List<String> sourceIds = row.getSourceSessionIds();
            if (sourceIds.isEmpty())
            {
                incompletePkPlaceCoverage = true;
                encounterCountsAvailable = false;
                break;
            }
            for (String sourceId : sourceIds)
            {
                if (!retainedSessionIds.contains(sourceId))
                {
                    incompletePkPlaceCoverage = true;
                    encounterCountsAvailable = false;
                    break;
                }
            }
        }
        if (incompletePkPlaceCoverage)
        {
            activeTimeAvailable = false;
            financeAvailable = false;
            for (PkPlaceAccumulator place : places.values()) place.financeAvailable = false;
        }

        List<PkPlaceSummary> summaries = new ArrayList<>();
        for (PkPlaceAccumulator place : places.values())
        {
            summaries.add(new PkPlaceSummary(place.locationLabel, place.kills, place.deaths,
                place.netGp, place.attachedSuppliesGp, place.activeMillis,
                place.firstAt == Long.MAX_VALUE ? 0L : place.firstAt,
                place.lastAt == Long.MIN_VALUE ? 0L : place.lastAt,
                encounterCountsAvailable, place.financeAvailable, activeTimeAvailable));
        }
        summaries.sort(Comparator.comparingLong(PkPlaceSummary::getNetGp).reversed()
            .thenComparing(PkPlaceSummary::getLocationLabel, String.CASE_INSENSITIVE_ORDER));
        return new PkPlaceSummaries(summaries, unlabelledMillis, unlabelledEncounters,
            encounterCountsAvailable, financeAvailable, activeTimeAvailable);
    }

    private static boolean hasPkPlaceEvidence(DailyRollup row)
    {
        if (row == null) return false;
        boolean hasPkTotals = row.getPvpKills() > 0 || row.getPvpDeaths() > 0
            || row.getPvpKillNetGp() != 0L || row.getPvpLossGp() != 0L;
        DailyRollup.CategoryTotal pkCategory = row.getCategoryTotals().get(SessionCategory.PKING);
        return hasPkTotals || (pkCategory != null
            && (pkCategory.getActiveMillis() > 0L || pkCategory.getNetGp() != 0L));
    }

    private InsightsWindowSnapshot.Window addWindowPerformance(InsightsWindowSnapshot.Window window,
        List<DailyRollup> rows, LocalDate from, LocalDate to, ZoneId zone)
    {
        if (rows == null || rows.isEmpty()) return window.withPerformance(
            InsightsWindowSnapshot.SessionHighlight.unavailable(false),
            InsightsWindowSnapshot.SessionHighlight.unavailable(false),
            InsightsWindowSnapshot.BiggestDrop.unavailable(false), null, false, window.getTopActivities());

        Map<String, ProfitSession> retained = new HashMap<>();
        for (ProfitSession session : uniqueProfileSessions())
        {
            if (session != null) retained.put(session.getId(), session);
        }
        Set<String> sourceIds = new LinkedHashSet<>();
        boolean rollupOnly = false;
        boolean dailyMetricsComplete = true;
        boolean hasWindowData = false;
        boolean accountingCoverageComplete =
            window.getCoverage(DailyRollup.Dimension.ACCOUNTING) == DailyRollup.Coverage.COMPLETE;
        dailyMetricsComplete &= accountingCoverageComplete
            && window.getCoverage(DailyRollup.Dimension.ACTIVE_TIME) == DailyRollup.Coverage.COMPLETE;
        for (DailyRollup row : rows)
        {
            if (row == null) continue;
            List<String> rowSources = row.getSourceSessionIds();
            sourceIds.addAll(rowSources);
            boolean dataWithoutOwner = rowSources.isEmpty() && hasMeaningfulDailyData(row);
            if (dataWithoutOwner) rollupOnly = true;
            for (String sourceId : rowSources)
            {
                if (!retained.containsKey(sourceId)) rollupOnly = true;
            }
            hasWindowData |= dataWithoutOwner || !rowSources.isEmpty();
            dailyMetricsComplete &= row.getCoverage(DailyRollup.Dimension.ACCOUNTING)
                == DailyRollup.Coverage.COMPLETE;
            dailyMetricsComplete &= row.getCoverage(DailyRollup.Dimension.ACTIVE_TIME)
                == DailyRollup.Coverage.COMPLETE;
        }

        Map<String, InsightsSessionTotals> sessionTotals = new LinkedHashMap<>();
        if (!rollupOnly)
        {
            for (String sourceId : sourceIds)
            {
                ProfitSession session = retained.get(sourceId);
                if (session == null) continue;
                InsightsSessionTotals totals = new InsightsSessionTotals(session);
                for (TrackingDaySummary day : session.getAnalyticsDays())
                {
                    if (day == null || !zone.getId().equals(day.getZoneId())) continue;
                    LocalDate date;
                    try { date = LocalDate.parse(day.getDay()); }
                    catch (RuntimeException invalidDate) { continue; }
                    if (date.isBefore(from) || date.isAfter(to)) continue;
                    totals.foundDay = true;
                    totals.netGp = saturatingAdd(totals.netGp, day.getNet());
                    totals.activeMillis = saturatingAdd(totals.activeMillis,
                        Math.max(0L, day.getActiveMillis()));
                    totals.complete &= day.isActiveTimeAvailable();
                }
                sessionTotals.put(sourceId, totals);
            }
        }
        for (InsightsSessionTotals totals : sessionTotals.values())
            dailyMetricsComplete &= totals.complete;

        // Session daily summaries are raw item aggregates. A live accounting filter cannot
        // be reconstructed after rollup, so keep extrema unavailable under that projection.
        boolean sessionEvidenceComplete = hasWindowData && !rollupOnly && dailyMetricsComplete
            && !hasActiveContributionFilter();
        InsightsWindowSnapshot.SessionHighlight best = InsightsWindowSnapshot.SessionHighlight.unavailable(rollupOnly);
        InsightsWindowSnapshot.SessionHighlight longest = InsightsWindowSnapshot.SessionHighlight.unavailable(rollupOnly);
        long bestNet = Long.MIN_VALUE;
        long longestMillis = -1L;
        if (sessionEvidenceComplete)
        {
            // The explicit "exclude from averages" flag applies only to the average-rate
            // denominator; these highlights remain a view of all recorded sessions.
            for (InsightsSessionTotals totals : sessionTotals.values())
            {
                if (!totals.foundDay || (totals.netGp == 0L && totals.activeMillis == 0L)) continue;
                ProfitSession session = totals.session;
                if (totals.netGp > bestNet || (totals.netGp == bestNet
                    && (best.getStartedAtEpochMillis() == 0L
                        || session.getStartedAtEpochMillis() < best.getStartedAtEpochMillis())))
                {
                    bestNet = totals.netGp;
                    best = InsightsWindowSnapshot.SessionHighlight.of(session.getId(), session.getName(),
                        session.getCategory(), totals.netGp, totals.activeMillis,
                        session.getStartedAtEpochMillis());
                }
                if (totals.activeMillis > longestMillis || (totals.activeMillis == longestMillis
                    && (longest.getStartedAtEpochMillis() == 0L
                        || session.getStartedAtEpochMillis() < longest.getStartedAtEpochMillis())))
                {
                    longestMillis = totals.activeMillis;
                    longest = InsightsWindowSnapshot.SessionHighlight.of(session.getId(), session.getName(),
                        session.getCategory(), totals.netGp, totals.activeMillis,
                        session.getStartedAtEpochMillis());
                }
            }
        }

        Long averageRate = null;
        boolean averageRollupBacked = rollupOnly;
        if (!hasActiveContributionFilter() && sessionEvidenceComplete)
        {
            long includedNet = 0L;
            long includedMillis = 0L;
            for (InsightsSessionTotals totals : sessionTotals.values())
            {
                if (totals.session.isExcludedFromAverages()) continue;
                includedNet = saturatingAdd(includedNet, totals.netGp);
                includedMillis = saturatingAdd(includedMillis, totals.activeMillis);
            }
            if (includedMillis > 0L) averageRate = weightedGpPerHour(includedNet, includedMillis);
        }

        InsightsWindowSnapshot.BiggestDrop biggestDrop = findBiggestDrop(
            sourceIds, retained, rollupOnly, accountingCoverageComplete, from, to, zone);
        return window.withPerformance(best, longest, biggestDrop, averageRate,
            averageRollupBacked, window.getTopActivities());
    }

    private InsightsWindowSnapshot.Window addPkWindowDetails(
        InsightsWindowSnapshot.Window window, List<DailyRollup> rows,
        LocalDate from, LocalDate to, long now, ZoneId zone, boolean hasLegacyZone)
    {
        if (rows == null || rows.isEmpty() || hasLegacyZone
            || !window.getPkWindow().isAggregateComplete()) return window;
        Map<String, ProfitSession> retained = new HashMap<>();
        for (ProfitSession session : uniqueProfileSessions())
        {
            if (session != null) retained.put(session.getId(), session);
        }

        Set<String> sourceIds = new LinkedHashSet<>();
        boolean sourceCoverageComplete = true;
        for (DailyRollup row : rows)
        {
            if (row == null) continue;
            List<String> ids = row.getSourceSessionIds();
            sourceIds.addAll(ids);
            if (hasPkPlaceEvidence(row) && ids.isEmpty()) sourceCoverageComplete = false;
            if (hasPkPlaceEvidence(row) && !row.isComplete(DailyRollup.Dimension.PVP))
                sourceCoverageComplete = false;
            for (String id : ids)
            {
                if (!retained.containsKey(id)) sourceCoverageComplete = false;
            }
        }
        if (hasActiveContributionFilter() || !sourceCoverageComplete) return window;

        long start = from.atStartOfDay(zone).toInstant().toEpochMilli();
        long endOfDateRange = to.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli();
        long end = Math.min(endOfDateRange, now == Long.MAX_VALUE ? now : now + 1L);
        List<PkEncounter> chronological = new ArrayList<>();
        boolean detailsComplete = true;
        long killNet = 0L;
        long deathLoss = 0L;
        long lootValue = 0L;
        long supplies = 0L;
        long detailKills = 0L;
        long detailDeaths = 0L;
        List<Long> killValues = new ArrayList<>();
        List<Long> deathValues = new ArrayList<>();
        for (String sourceId : sourceIds)
        {
            ProfitSession session = retained.get(sourceId);
            if (session == null)
            {
                detailsComplete = false;
                continue;
            }
            for (PkEncounter encounter : session.getPkEncounters())
            {
                if (encounter == null || encounter.getTimestampEpochMillis() < start
                    || encounter.getTimestampEpochMillis() >= end) continue;
                chronological.add(encounter);
                if (!encounter.isFinancialSummaryAvailable())
                {
                    detailsComplete = false;
                    continue;
                }
                if (!encounter.isFinancialCostSplitAvailable()) detailsComplete = false;
                supplies = saturatingAdd(supplies, encounter.getFinancialSuppliesCostGp());
                long net = encounter.getFinancialNetGp();
                if (encounter.getType() == PkEncounterType.KILL)
                {
                    detailKills++;
                    killNet = saturatingAdd(killNet, net);
                    lootValue = saturatingAdd(lootValue,
                        saturatingAdd(net, encounter.getFinancialCostGp()));
                    killValues.add(net);
                }
                else
                {
                    detailDeaths++;
                    long loss = encounter.getFinancialLossGp();
                    deathLoss = saturatingAdd(deathLoss, loss);
                    deathValues.add(loss);
                }
            }
        }
        chronological.sort(Comparator.comparingLong(PkEncounter::getTimestampEpochMillis));

        PkWindow aggregate = window.getPkWindow();
        if (aggregate.isAggregateComplete())
        {
            detailsComplete &= detailKills == aggregate.getKills()
                && detailDeaths == aggregate.getDeaths()
                && killNet == aggregate.getKillNetGp()
                && deathLoss == aggregate.getDeathLossGp();
        }
        if (!detailsComplete) return window;

        int streak = 0;
        int bestStreak = 0;
        List<PkWindow.Event> bestKills = new ArrayList<>();
        List<PkWindow.Event> costliestDeaths = new ArrayList<>();
        for (PkEncounter encounter : chronological)
        {
            if (encounter.getType() == PkEncounterType.KILL)
            {
                streak = streak < 0 ? 1 : streak + 1;
                bestStreak = Math.max(bestStreak, streak);
                long value = encounter.getFinancialNetGp();
                bestKills.add(new PkWindow.Event(opponentName(encounter),
                    encounter.getLocationLabel(), value, encounter.getTimestampEpochMillis()));
            }
            else
            {
                streak = 0;
                costliestDeaths.add(new PkWindow.Event(opponentName(encounter),
                    encounter.getLocationLabel(), encounter.getFinancialLossGp(),
                    encounter.getTimestampEpochMillis()));
            }
        }
        bestKills.sort(Comparator.comparingLong(PkWindow.Event::getValueGp).reversed()
            .thenComparingLong(PkWindow.Event::getTimestampEpochMillis));
        costliestDeaths.sort(Comparator.comparingLong(PkWindow.Event::getValueGp).reversed()
            .thenComparingLong(PkWindow.Event::getTimestampEpochMillis));
        bestKills = new ArrayList<>(bestKills.subList(0, Math.min(5, bestKills.size())));
        costliestDeaths = new ArrayList<>(costliestDeaths.subList(0,
            Math.min(5, costliestDeaths.size())));
        Long averageFightCost = detailKills + detailDeaths == 0L
            ? null : supplies / (detailKills + detailDeaths);
        PkWindow enriched = aggregate.withDetails(bestStreak, lootValue, supplies,
            averageFightCost, PkMetrics.median(killValues), PkMetrics.median(deathValues),
            bestKills, costliestDeaths);
        return window.withPkWindow(enriched);
    }

    private static String opponentName(PkEncounter encounter)
    {
        if (encounter == null || encounter.getLabel() == null) return "";
        String label = encounter.getLabel().trim();
        if (label.regionMatches(true, 0, "Kill: ", 0, 6)) return label.substring(6).trim();
        if (label.regionMatches(true, 0, "Death: ", 0, 7)) return label.substring(7).trim();
        return "";
    }

    private InsightsWindowSnapshot.BiggestDrop findBiggestDrop(Set<String> sourceIds,
        Map<String, ProfitSession> retained, boolean rollupOnly, boolean accountingCoverageComplete,
        LocalDate from, LocalDate to, ZoneId zone)
    {
        if (rollupOnly) return InsightsWindowSnapshot.BiggestDrop.unavailable(true);
        if (!accountingCoverageComplete || sourceIds.isEmpty() || hasActiveContributionFilter())
            return InsightsWindowSnapshot.BiggestDrop.unavailable(true);
        long largestValue = 0L;
        long largestAt = 0L;
        int largestItemId = 0;
        long largestQuantity = 0L;
        String largestName = "";
        String largestSession = "";
        BiPredicate<ProfitTransaction, ItemFlow> eligibility = activeContributionEligibility();
        for (String sourceId : sourceIds)
        {
            ProfitSession session = retained.get(sourceId);
            if (session == null) return InsightsWindowSnapshot.BiggestDrop.unavailable(true);
            if (session.getCompactedTransactionCount() > 0L)
                return InsightsWindowSnapshot.BiggestDrop.unavailable(true);
            for (ProfitTransaction transaction : session.getTransactions())
            {
                if (transaction == null) continue;
                LocalDate transactionDate = Instant.ofEpochMilli(transaction.getTimestampEpochMillis())
                    .atZone(zone).toLocalDate();
                if (transactionDate.isBefore(from) || transactionDate.isAfter(to)) continue;
                AccountingProjection.TransactionAmounts transactionAmounts =
                    AccountingProjection.transaction(transaction, eligibility);
                if (!transactionAmounts.isAvailable())
                    return InsightsWindowSnapshot.BiggestDrop.unavailable(false);
                if (!transactionAmounts.isIncluded() || transactionAmounts.getRevenue() <= 0L) continue;
                if (transaction.getFlows().isEmpty())
                    return InsightsWindowSnapshot.BiggestDrop.unavailable(false);
                for (ItemFlow flow : transaction.getFlows())
                {
                    if (flow == null) continue;
                    AccountingProjection.TransactionAmounts amounts =
                        AccountingProjection.flow(transaction, flow, eligibility);
                    if (!amounts.isAvailable())
                        return InsightsWindowSnapshot.BiggestDrop.unavailable(false);
                    if (!amounts.isIncluded() || amounts.getRevenue() <= 0L) continue;
                    long value = amounts.getRevenue();
                    if (value > largestValue || (value == largestValue
                        && (largestAt == 0L || transaction.getTimestampEpochMillis() < largestAt)))
                    {
                        largestValue = value;
                        largestAt = transaction.getTimestampEpochMillis();
                        largestItemId = flow.getItemId();
                        largestName = flow.getItemName();
                        largestQuantity = flow.getQuantityDelta() == Long.MIN_VALUE
                            ? Long.MAX_VALUE : Math.abs(flow.getQuantityDelta());
                        largestSession = sourceId;
                    }
                }
            }
        }
        if (largestValue <= 0L) return InsightsWindowSnapshot.BiggestDrop.none();
        return InsightsWindowSnapshot.BiggestDrop.of(largestItemId, largestName, largestValue,
            largestQuantity, largestSession, largestAt);
    }

    private static boolean hasMeaningfulDailyData(DailyRollup row)
    {
        if (row == null) return false;
        if (row.getRevenueGp() != 0L || row.getCostsGp() != 0L
            || row.getSuppliesCostsGp() != 0L || row.getLossCostsGp() != 0L
            || row.getActiveMillis() > 0L || row.getSessionStarts() > 0 || row.getRunStarts() > 0
            || row.getKills() > 0 || row.getDeaths() > 0 || row.getPvpKills() > 0
            || row.getPvpDeaths() > 0 || row.getPvpKillNetGp() != 0L || row.getPvpLossGp() != 0L
            || row.getPvpBestKillGp() != 0L || row.getPvpLargestLossGp() != 0L
            || row.getPvmEncounterCount() > 0L || row.isGainedItemsTruncated()
            || row.isCostItemsTruncated() || !row.getActivities().isEmpty()
            || !row.getCategoryTotals().isEmpty() || !row.getGainedItemTotals().isEmpty()
            || !row.getCostItemTotals().isEmpty()) return true;
        for (long value : row.getFourHourNetGp()) if (value != 0L) return true;
        for (long value : row.getFourHourActiveMillis()) if (value != 0L) return true;
        for (long value : row.getHourlyNetGp()) if (value != 0L) return true;
        for (long value : row.getHourlyActiveMillis()) if (value != 0L) return true;
        return false;
    }

    private static final class InsightsSessionTotals
    {
        private final ProfitSession session;
        private long netGp;
        private long activeMillis;
        private boolean foundDay;
        private boolean complete = true;

        private InsightsSessionTotals(ProfitSession session) { this.session = session; }
    }

    private static final class PkPlaceAccumulator
    {
        private final String locationLabel;
        private int kills;
        private int deaths;
        private long netGp;
        private long attachedSuppliesGp;
        private long activeMillis;
        private long firstAt = Long.MAX_VALUE;
        private long lastAt = Long.MIN_VALUE;
        private boolean financeAvailable;

        private PkPlaceAccumulator(String locationLabel, boolean financeAvailable)
        {
            this.locationLabel = locationLabel;
            this.financeAvailable = financeAvailable;
        }
    }

    public synchronized List<SessionNetTrendEntry> getSessionNetTrend(int days)
    {
        return getSessionNetTrend(days, System.currentTimeMillis());
    }

    /** Returns recent sessions in time order, retaining but flagging excluded sessions. */
    public synchronized List<SessionNetTrendEntry> getSessionNetTrend(int days, long now)
    {
        ZoneId zone = profileZone();
        LocalDate firstDate = days <= 0 ? null
            : Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(Math.max(1, days) - 1L);
        long cutoff = firstDate == null ? 0L : firstDate.atStartOfDay(zone).toInstant().toEpochMilli();
        List<ProfitSession> sessions = uniqueProfileSessions();
        sessions.sort(Comparator.comparingLong(ProfitSession::getStartedAtEpochMillis));
        List<SessionNetTrendEntry> result = new ArrayList<>();
        long rollingWindowMillis = Math.max(1, config.rollingRateMinutes()) * 60_000L;
        for (ProfitSession session : sessions)
        {
            if (session == null) continue;
            long sessionEnd = session.getEndedAtEpochMillis() > 0L
                ? session.getEndedAtEpochMillis() : now;
            if ((cutoff > 0L && sessionEnd < cutoff) || session.getStartedAtEpochMillis() > now) continue;
            SessionMetrics metrics = filteredMetrics(session, now, rollingWindowMillis);
            result.add(new SessionNetTrendEntry(session.getId(), session.getName(),
                session.getStartedAtEpochMillis(), metrics.getNet(), session.getElapsedMillis(now),
                session.isExcludedFromAverages(), metrics.isAccountingProjectionAvailable()));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Returns same-name activity statistics from retained daily summaries. Excluded
     * sessions and an explicitly excluded session id never contribute. Filtered,
     * truncated, or legacy-incomplete aggregates are reported unavailable instead
     * of being reconstructed from current receipts or treated as zero.
     */
    /** All-time records over retained history. Excluded and Free play sessions never set session records. */
    public synchronized RecordsSnapshot getRecords(long now)
    {
        long window = Math.max(1, config.rollingRateMinutes()) * 60_000L;
        RecordsSnapshot.Record bestNet = RecordsSnapshot.Record.unavailable("UNAVAILABLE_NO_SESSION");
        RecordsSnapshot.Record bestRate = RecordsSnapshot.Record.unavailable("UNAVAILABLE_NO_30_MIN_SESSION");
        RecordsSnapshot.Record longest = RecordsSnapshot.Record.unavailable("UNAVAILABLE_NO_SESSION");
        RecordsSnapshot.Record bestPvpKill = RecordsSnapshot.Record.unavailable("UNAVAILABLE_NO_PVP");
        RecordsSnapshot.Record longestPvpStreak = RecordsSnapshot.Record.unavailable("UNAVAILABLE_NO_PVP");
        RecordsSnapshot.Record bestPvpKd = RecordsSnapshot.Record.unavailable("UNAVAILABLE_NO_PVP_KD");
        for (ProfitSession session : uniqueProfileSessions())
        {
            if (session == null || !session.isClosed() || session.isExcludedFromAverages()
                || session.getOwnerKind() == SessionOwnerKind.FREE_PLAY) continue;
            SessionMetrics metrics = filteredMetrics(session, now, window);
            if (!metrics.isAccountingProjectionAvailable()) continue;
            DailyRollup.Coverage coverage = DailyRollup.Coverage.COMPLETE;
            if (!bestNet.isAvailable() || metrics.getNet() > bestNet.getValue())
                bestNet = new RecordsSnapshot.Record(metrics.getNet(), session.getId(), null, coverage, "AVAILABLE");
            if (!longest.isAvailable() || metrics.getElapsedMillis() > longest.getValue())
                longest = new RecordsSnapshot.Record(metrics.getElapsedMillis(), session.getId(), null, coverage, "AVAILABLE");
            if (metrics.getElapsedMillis() >= 30L * 60_000L
                && (!bestRate.isAvailable() || metrics.getProfitPerHour() > bestRate.getValue()))
                bestRate = new RecordsSnapshot.Record(metrics.getProfitPerHour(), session.getId(), null, coverage, "AVAILABLE");
            PkMetrics pk = session.pkMetrics(activeContributionEligibility());
            if (pk.isProjectionAvailable())
            {
                if (pk.getBestKill() > 0L && (!bestPvpKill.isAvailable() || pk.getBestKill() > bestPvpKill.getValue()))
                    bestPvpKill = new RecordsSnapshot.Record(pk.getBestKill(), session.getId(), null, coverage, "AVAILABLE");
                if (pk.getCurrentStreak() > 0 && (!longestPvpStreak.isAvailable() || pk.getCurrentStreak() > longestPvpStreak.getValue()))
                    longestPvpStreak = new RecordsSnapshot.Record((long) pk.getCurrentStreak(), session.getId(), null, coverage, "AVAILABLE");
                if (pk.getKills() >= 5 && (!bestPvpKd.isAvailable() || pk.getKillDeathRatio() > bestPvpKd.getValue()))
                    bestPvpKd = new RecordsSnapshot.Record(Math.round(pk.getKillDeathRatio() * 1_000_000d), session.getId(), null, coverage, "AVAILABLE_X1E6");
            }
        }
        LocalDate today = Instant.ofEpochMilli(now).atZone(profileZone()).toLocalDate();
        RecordsSnapshot.Record bestDay = RecordsSnapshot.Record.unavailable("UNAVAILABLE_DAY_COVERAGE");
        java.util.Map<LocalDate, Long> weeks = new java.util.LinkedHashMap<>();
        java.util.Set<LocalDate> played = new java.util.HashSet<>();
        for (DailyRollup day : getDailyRollups(today.minusDays(400), today))
        {
            if (day.getCoverage(DailyRollup.Dimension.ACTIVE_TIME) == DailyRollup.Coverage.COMPLETE
                && day.getActiveMillis() >= 60_000L) played.add(day.getDate());
            if (day.getCoverage(DailyRollup.Dimension.ACCOUNTING) != DailyRollup.Coverage.COMPLETE) continue;
            if (!bestDay.isAvailable() || day.getNetGp() > bestDay.getValue())
                bestDay = new RecordsSnapshot.Record(day.getNetGp(), "", day.getDate(), DailyRollup.Coverage.COMPLETE, "AVAILABLE");
            LocalDate monday = day.getDate().minusDays(day.getDate().getDayOfWeek().getValue() - 1L);
            weeks.merge(monday, day.getNetGp(), GpManagerEngine::saturatingAdd);
        }
        RecordsSnapshot.Record bestWeek = RecordsSnapshot.Record.unavailable("UNAVAILABLE_WEEK_COVERAGE");
        for (java.util.Map.Entry<LocalDate, Long> week : weeks.entrySet())
            if (!bestWeek.isAvailable() || week.getValue() > bestWeek.getValue())
                bestWeek = new RecordsSnapshot.Record(week.getValue(), "", week.getKey(), DailyRollup.Coverage.PARTIAL, "PARTIAL_DAYS_ALLOWED");
        int current = 0, longestStreak = 0, run = 0;
        for (LocalDate date = today.minusDays(400); !date.isAfter(today); date = date.plusDays(1))
        { run = played.contains(date) ? run + 1 : 0; longestStreak = Math.max(longestStreak, run); }
        for (LocalDate date = played.contains(today) ? today : today.minusDays(1); played.contains(date); date = date.minusDays(1)) current++;
        RecordsSnapshot.Record wealth = RecordsSnapshot.Record.unavailable("UNAVAILABLE_WEALTH_HISTORY");
        for (WealthSnapshotHistory.Snapshot snapshot : wealthSnapshotHistory.getTimeline(400, now))
        { Long total = WealthBreakdown.fromSnapshot(snapshot).getTotalGp(); if (total != null && (!wealth.isAvailable() || total > wealth.getValue())) wealth = new RecordsSnapshot.Record(total, "", null, DailyRollup.Coverage.COMPLETE, "AVAILABLE"); }
        return new RecordsSnapshot(bestNet, bestRate, longest, bestDay, bestWeek, wealth,
            new RecordsSnapshot.Record((long) current, "", today, DailyRollup.Coverage.PARTIAL, "RETAINED_WINDOW"),
            new RecordsSnapshot.Record((long) longestStreak, "", null, DailyRollup.Coverage.PARTIAL, "RETAINED_WINDOW"),
            bestPvpKill, longestPvpStreak, bestPvpKd);
    }

    /** Returns the observed GE settlement policy; default is provenance-only. */
    public synchronized GeBookingMode getGeBookingMode() { return geBookingMode; }

    /** Client/live acceptance may opt into observed booking without an engine rebuild. */
    public synchronized void setGeBookingMode(GeBookingMode mode)
    {
        geBookingMode = mode == null ? GeBookingMode.PROVENANCE_ONLY : mode;
    }

    /**
     * Whether the sell-side raw {@code getSpent()} is taken as coins already net of the exchange
     * tax (default) or as gross. Unconfirmed by the pinned API; the live completed-offer
     * comparison decides it. Only matters in {@link GeBookingMode#OBSERVED}.
     */
    private boolean geSellSpentIsNet = true;

    public synchronized void setGeSellSpentIsNet(boolean net)
    {
        geSellSpentIsNet = net;
    }

    public synchronized boolean isGeSellSpentIsNet()
    {
        return geSellSpentIsNet;
    }

    private static final int GE_OBSERVATION_RING = 20;
    private final java.util.ArrayDeque<com.gpmanager.model.GeOfferObservation> recentGeObservations = new java.util.ArrayDeque<>();

    /** Keeps the raw transition for Tools > Diagnostics; never books anything. */
    public synchronized void noteGeOfferObservation(GeOfferLedger.Transition transition, String itemName, long now)
    {
        if (transition == null || transition.getCurrent() == null) return;
        GeOfferLedger.Snapshot current = transition.getCurrent();
        GeOfferLedger.Snapshot previous = transition.getPrevious();
        // A cleared slot reports item 0 (which the cache names "Dwarf remains"); the item that was
        // in the slot is the previous snapshot's.
        boolean cleared = current.getItemId() <= 0 && previous != null && previous.getItemId() > 0;
        int itemId = cleared ? previous.getItemId() : current.getItemId();
        String state = cleared ? "CLEARED" : current.getState().name();
        recentGeObservations.addLast(new com.gpmanager.model.GeOfferObservation(now, current.getSlot(), state,
            itemId, itemName, cleared ? previous.getTotalQuantity() : current.getTotalQuantity(),
            cleared ? previous.getQuantityTraded() : current.getQuantityTraded(), transition.getQuantityTradedDelta(),
            cleared ? previous.getPrice() : current.getPrice(), transition.getSpentDelta(),
            cleared ? previous.getSpent() : current.getSpent(), transition.hasComparableProgress()));
        while (recentGeObservations.size() > GE_OBSERVATION_RING) recentGeObservations.removeFirst();
    }

    private static final long GE_PLACEMENT_WINDOW_MILLIS = 90_000L;
    private static final long GE_COLLECTION_WINDOW_MILLIS = 2L * 60L * 60L * 1000L;

    /**
     * True when every valued flow is explained by a recent offer transition: an item lost shortly
     * after a sell offer for it was placed, coins lost shortly after a buy was placed, an item gained
     * after a buy for it filled (or a sell was cancelled), coins gained after a sell filled (or a
     * buy was cancelled). One unexplained flow means the change is not a GE movement.
     */
    private boolean matchesRecentGeMovement(List<ItemFlow> flows, long now)
    {
        if (flows == null || flows.isEmpty() || recentGeObservations.isEmpty()) return false;
        boolean anyValued = false;
        for (ItemFlow flow : flows)
        {
            if (flow == null || flow.getQuantityDelta() == 0L) continue;
            anyValued = true;
            boolean coins = flow.getItemId() == GeObservedSettlement.COINS;
            boolean lost = flow.getQuantityDelta() < 0L;
            boolean explained = false;
            for (com.gpmanager.model.GeOfferObservation o : recentGeObservations)
            {
                long age = now - o.getAtEpochMillis();
                if (age < 0L) continue;
                String state = o.getState();
                boolean sellSide = state.equals("SELLING") || state.equals("SOLD") || state.equals("CANCELLED_SELL");
                boolean buySide = state.equals("BUYING") || state.equals("BOUGHT") || state.equals("CANCELLED_BUY");
                if (coins)
                {
                    if (lost && buySide && age <= GE_PLACEMENT_WINDOW_MILLIS) explained = true;
                    if (!lost && age <= GE_COLLECTION_WINDOW_MILLIS
                        && ((sellSide && (o.getQuantityTraded() > 0 || state.equals("SOLD"))) || state.equals("CANCELLED_BUY"))) explained = true;
                }
                else if (o.getItemId() == flow.getItemId())
                {
                    if (lost && sellSide && age <= GE_PLACEMENT_WINDOW_MILLIS) explained = true;
                    if (!lost && age <= GE_COLLECTION_WINDOW_MILLIS
                        && ((buySide && (o.getQuantityTraded() > 0 || state.equals("BOUGHT"))) || state.equals("CANCELLED_SELL"))) explained = true;
                }
                if (explained) break;
            }
            if (!explained) return false;
        }
        return anyValued;
    }

    /** Newest first; transient, at most 20. */
    public synchronized List<com.gpmanager.model.GeOfferObservation> getRecentGeOfferObservations(int limit)
    {
        List<com.gpmanager.model.GeOfferObservation> out = new ArrayList<>(recentGeObservations);
        Collections.reverse(out);
        return out.size() > Math.max(0, limit) ? new ArrayList<>(out.subList(0, Math.max(0, limit))) : out;
    }

    /**
     * Books one observed Grand Exchange settlement in {@link GeBookingMode#OBSERVED}: a counted
     * MARKET trade built from the slot's traded-quantity and coin deltas, carrying its provenance.
     * In {@link GeBookingMode#PROVENANCE_ONLY} nothing is booked and null is returned — the caller
     * keeps the observation as presentation provenance for the inventory-settled receipt.
     */
    @Nullable
    public synchronized ProfitTransaction bookObservedGeSettlement(GeOfferLedger.Transition transition, String itemName,
        int unitPrice, long now)
    {
        if (geBookingMode != GeBookingMode.OBSERVED || activeSession == null || activeSession.isPaused()) return null;
        GeObservedSettlement settlement = GeObservedSettlement.from(transition, itemName, unitPrice, geSellSpentIsNet,
            config.applyGeSellTax(), now).orElse(null);
        if (settlement == null) return null;
        ProfitTransaction transaction = new ProfitTransaction(
            now,
            activeSession.getElapsedMillis(now),
            TransactionType.TRADE,
            TrackingContext.MARKET,
            settlement.getSide() == GeObservedSettlement.Side.BUY ? "GE buy" : "GE sell",
            "Market",
            true,
            settlement.getFlows(),
            ClassificationConfidence.CONFIRMED,
            "Booked from the observed offer: " + settlement.describe() + ".",
            null);
        GeOfferLedger.Snapshot current = transition.getCurrent();
        transaction.setGeOfferProvenance(new com.gpmanager.model.GeOfferProvenance(current.getSlot(),
            transition.getQuantityTradedDelta(), transition.getSpentDelta(), current.getState().name()));
        evaluateGoalAlerts(now, false);
        activeSession.addTransaction(transaction, config.maxTransactionsPerSession());
        evaluateGoalAlerts(now, false);
        return transaction;
    }

    /** Pairwise, filter-aware comparison used by the Sessions compare flow. */
    public synchronized SessionComparison compareSessions(String leftId, String rightId, long now)
    {
        ProfitSession left = getHistorySession(leftId), right = getHistorySession(rightId);
        if (left == null || right == null || !left.isClosed() || !right.isClosed())
            return new SessionComparison(leftId, rightId, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, "UNAVAILABLE_SESSION");
        long window = Math.max(1, config.rollingRateMinutes()) * 60_000L;
        SessionMetrics lm = filteredMetrics(left, now, window), rm = filteredMetrics(right, now, window);
        PkMetrics lp = left.pkMetrics(activeContributionEligibility()), rp = right.pkMetrics(activeContributionEligibility());
        Long[] l = {lm.getElapsedMillis(), left.getElapsedMillis(now), lm.getNet(), lm.getProfitPerHour(), lm.getRevenue(), lm.getSuppliesCosts(), lm.getOtherCosts()};
        Long[] r = {rm.getElapsedMillis(), right.getElapsedMillis(now), rm.getNet(), rm.getProfitPerHour(), rm.getRevenue(), rm.getSuppliesCosts(), rm.getOtherCosts()};
        java.util.List<SessionComparison.ItemDelta> items = null;
        Map<Integer, ProfitSession.ItemNet> leftItems = left.getItemNets();
        Map<Integer, ProfitSession.ItemNet> rightItems = right.getItemNets();
        if (leftItems != null && rightItems != null)
        {
            Map<Integer, SessionComparison.ItemDelta> byItem = new LinkedHashMap<>();
            for (ProfitSession.ItemNet n : leftItems.values())
                byItem.put(n.getItemId(), new SessionComparison.ItemDelta(n.getItemId(), n.getItemName(), n.getNet(), 0L));
            for (ProfitSession.ItemNet n : rightItems.values())
            {
                SessionComparison.ItemDelta existing = byItem.get(n.getItemId());
                byItem.put(n.getItemId(), new SessionComparison.ItemDelta(n.getItemId(), n.getItemName(),
                    existing == null ? 0L : existing.getLeftNet(), n.getNet()));
            }
            items = new ArrayList<>(byItem.values());
            items.sort((a, b) -> Long.compare(Math.abs(b.getDelta()), Math.abs(a.getDelta())));
            if (items.size() > 5) items = new ArrayList<>(items.subList(0, 5));
        }
        // Averages are kept per activity (what the detector saw), so a custom session compares
        // against its activity when it has one and its name otherwise.
        String activity = left.getActivityHint() == null || left.getActivityHint().trim().isEmpty()
            || "General".equalsIgnoreCase(left.getActivityHint().trim()) ? left.getName() : left.getActivityHint().trim();
        ActivityAverageSnapshot average = getActivityAverage(activity, leftId);
        if (average != null && average.getSessionsCounted() <= 0) average = null;
        return new SessionComparison(leftId, rightId, l[0], r[0], l[1], r[1], l[2], r[2], l[3], r[3], l[4], r[4], l[5], r[5], l[6], r[6],
            lp.isProjectionAvailable() ? lp.getKills() : null, rp.isProjectionAvailable() ? rp.getKills() : null,
            lp.isProjectionAvailable() ? lp.getDeaths() : null, rp.isProjectionAvailable() ? rp.getDeaths() : null,
            lm.isAccountingProjectionAvailable() && rm.isAccountingProjectionAvailable() ? "AVAILABLE" : "PARTIAL",
            items, average);
    }

    // ── Session merge (pass 10 step 41) ───────────────────────────────────────

    /** What a merge did, or why it was refused; {@link #getMergedSessionId()} is null on refusal. */
    public static final class MergeOutcome
    {
        private final String mergedSessionId;
        private final List<String> originalIds;
        private final String reason;

        MergeOutcome(String mergedSessionId, List<String> originalIds, String reason)
        {
            this.mergedSessionId = mergedSessionId;
            this.originalIds = Collections.unmodifiableList(new ArrayList<>(originalIds == null ? Collections.emptyList() : originalIds));
            this.reason = reason == null ? "" : reason;
        }

        public boolean isMerged() { return mergedSessionId != null; }
        public String getMergedSessionId() { return mergedSessionId; }
        public List<String> getOriginalIds() { return originalIds; }
        /** Empty on success; otherwise one of NOT_FOUND, TOO_FEW, ACTIVE, NOT_CONSECUTIVE, MIXED_OWNER. */
        public String getReason() { return reason; }
    }

    /** The originals of the last merge, kept as they were so an undo is byte-for-byte; not persisted. */
    private static final class MergeRecord
    {
        final String mergedId;
        final List<ProfitSession> originals;
        final int historyIndex;

        MergeRecord(String mergedId, List<ProfitSession> originals, int historyIndex)
        {
            this.mergedId = mergedId;
            this.originals = originals;
            this.historyIndex = historyIndex;
        }
    }

    private MergeRecord lastMerge;

    /**
     * Merges closed history sessions that sit next to each other in history (nothing but each other
     * between them) into one session that stands for all of them; see {@link ProfitSession#mergedFrom}.
     * Day rollups are rebuilt from the sessions, so totals are unchanged. Undo with {@link #undoLastMerge}
     * until the client restarts or the next merge.
     */
    public synchronized MergeOutcome mergeHistorySessions(List<String> ids, long now)
    {
        if (ids == null || ids.size() < 2) return new MergeOutcome(null, ids, "TOO_FEW");
        java.util.Set<String> wanted = new java.util.LinkedHashSet<>(ids);
        if (wanted.size() < 2) return new MergeOutcome(null, ids, "TOO_FEW");
        if (activeSession != null && wanted.contains(activeSession.getId())) return new MergeOutcome(null, ids, "ACTIVE");
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < history.size(); i++)
        {
            ProfitSession s = history.get(i);
            if (s != null && wanted.contains(s.getId())) indexes.add(i);
        }
        if (indexes.size() != wanted.size()) return new MergeOutcome(null, ids, "NOT_FOUND");
        for (int i = 1; i < indexes.size(); i++)
        {
            if (indexes.get(i) != indexes.get(i - 1) + 1) return new MergeOutcome(null, ids, "NOT_CONSECUTIVE");
        }
        List<ProfitSession> parts = new ArrayList<>();
        for (int index : indexes) parts.add(history.get(index));
        for (ProfitSession p : parts)
        {
            if (!p.isClosed()) return new MergeOutcome(null, ids, "ACTIVE");
            if (p.getOwnerKind() != parts.get(0).getOwnerKind()) return new MergeOutcome(null, ids, "MIXED_OWNER");
        }
        // History is newest-first; the merge wants start order.
        List<ProfitSession> inStartOrder = new ArrayList<>(parts);
        inStartOrder.sort(Comparator.comparingLong(ProfitSession::getStartedAtEpochMillis));
        ProfitSession merged = ProfitSession.mergedFrom(inStartOrder, now);
        merged.configureAnalyticsTimeZone(profileTimeZoneId, false);
        attachOverallTotalsInvalidationListener(merged);
        int first = indexes.get(0);
        for (int i = indexes.size() - 1; i >= 0; i--) history.remove((int) indexes.get(i));
        history.add(first, merged);
        lastMerge = new MergeRecord(merged.getId(), parts, first);
        discardPersistedDailyRollupBaseline();
        List<String> originalIds = new ArrayList<>();
        for (ProfitSession p : parts) originalIds.add(p.getId());
        return new MergeOutcome(merged.getId(), originalIds, "");
    }

    /** Puts the last merge's originals back exactly as they were; false when there is nothing to undo. */
    public synchronized boolean undoLastMerge()
    {
        MergeRecord record = lastMerge;
        if (record == null) return false;
        int index = -1;
        for (int i = 0; i < history.size(); i++)
        {
            if (record.mergedId.equals(history.get(i).getId())) index = i;
        }
        if (index < 0) return false;
        history.remove(index);
        history.addAll(index, record.originals);
        for (ProfitSession p : record.originals) attachOverallTotalsInvalidationListener(p);
        lastMerge = null;
        discardPersistedDailyRollupBaseline();
        return true;
    }

    /** The merged session an undo would take apart, or null. */
    @Nullable
    public synchronized String getUndoableMergeId()
    {
        return lastMerge == null ? null : lastMerge.mergedId;
    }

    public synchronized ActivityAverageSnapshot getActivityAverage(String name, String excludeSessionId)
    {
        String requested = name == null ? "" : name.trim();
        if (requested.isEmpty())
        {
            return new ActivityAverageSnapshot("", 0, null, null, 0L, null,
                false, "UNAVAILABLE_EMPTY_ACTIVITY_NAME");
        }
        if (hasActiveContributionFilter())
        {
            return new ActivityAverageSnapshot(requested, 0, null, null, 0L, null,
                false, "UNAVAILABLE_ACTIVE_ACCOUNTING_FILTER");
        }

        long totalNet = 0L;
        long totalMillis = 0L;
        boolean complete = true;
        boolean durationsComplete = true;
        List<ProfitSession> profileSessions = uniqueProfileSessions();
        Set<String> availableSessionIds = new HashSet<>();
        for (ProfitSession profileSession : profileSessions)
        {
            if (profileSession != null) availableSessionIds.add(profileSession.getId());
        }
        List<Long> sessionNets = new ArrayList<>();
        for (ProfitSession session : profileSessions)
        {
            if (session == null || session.isExcludedFromAverages()
                || (excludeSessionId != null && excludeSessionId.equals(session.getId())))
            {
                continue;
            }

            List<TrackingDaySummary> days = session.getAnalyticsDays();
            if (hasTruncatedActivityHistory(session, days)
                || (days.isEmpty() && (session.getCompactedTransactionCount() > 0L
                    || !session.getTransactions().isEmpty())))
            {
                complete = false;
                durationsComplete = false;
            }

            long activityNet = 0L;
            long activityMillis = 0L;
            boolean foundActivity = false;
            boolean foundActivityNet = false;
            boolean foundActivityMillis = false;
            boolean sessionDurationsComplete = true;
            for (TrackingDaySummary day : days)
            {
                if (day == null) continue;
                Long net = matchingValue(day.getActivityNet(), requested);
                Long millis = matchingValue(day.getActivityActiveMillis(), requested);
                if (net != null)
                {
                    activityNet = saturatingAdd(activityNet, net);
                    foundActivity = true;
                    foundActivityNet = true;
                }
                if (millis != null)
                {
                    activityMillis = saturatingAdd(activityMillis, Math.max(0L, millis));
                    foundActivity = true;
                    foundActivityMillis = true;
                }
                if (!day.isActivityActiveMillisAvailable())
                {
                    sessionDurationsComplete = false;
                    durationsComplete = false;
                }
            }
            if (foundActivity)
            {
                sessionNets.add(activityNet);
                totalNet = saturatingAdd(totalNet, activityNet);
                totalMillis = saturatingAdd(totalMillis, activityMillis);
                if (!sessionDurationsComplete) durationsComplete = false;
                if (foundActivityNet && !foundActivityMillis) durationsComplete = false;
            }
        }

        // Daily rollup fallback may retain activity for sessions which have been
        // trimmed from profile history. Those rows have no per-session median
        // sample, so do not present the visible-session subset as a full average.
        for (DailyRollup rollup : refreshDailyRollups())
        {
            if (rollup == null || !containsActivityIgnoreCase(rollup, requested)) continue;
            List<String> sources = rollup.getSourceSessionIds();
            if (sources.isEmpty())
            {
                complete = false;
                durationsComplete = false;
                continue;
            }
            for (String sourceId : sources)
            {
                if (!availableSessionIds.contains(sourceId))
                {
                    complete = false;
                    durationsComplete = false;
                }
            }
        }

        if (sessionNets.isEmpty())
        {
            String status = complete ? "UNAVAILABLE_NO_ACTIVITY_DATA" : "PARTIAL_ACTIVITY_HISTORY";
            return new ActivityAverageSnapshot(requested, 0, null, null, totalMillis, null,
                complete, status);
        }

        if (!complete)
        {
            return new ActivityAverageSnapshot(requested, sessionNets.size(), null, null,
                totalMillis, null, false, "PARTIAL_ACTIVITY_HISTORY");
        }

        Long rate = durationsComplete && totalMillis > 0L
            ? weightedGpPerHour(totalNet, totalMillis) : null;
        String status = rate != null ? "AVAILABLE"
            : "UNAVAILABLE_ACTIVITY_TIME";
        return new ActivityAverageSnapshot(requested, sessionNets.size(), totalNet,
            medianSessionNet(sessionNets), totalMillis, rate, true, status);
    }

    /**
     * Returns category statistics from retained daily summaries. Excluded
     * sessions and an explicitly excluded session id never contribute. As
     * with activity averages, rollup-only sessions make per-session medians
     * incomplete rather than being represented by guessed samples.
     */
    public synchronized ActivityAverageSnapshot getCategoryAverage(
        SessionCategory category, String excludeSessionId)
    {
        if (category == null || category == SessionCategory.ALL)
        {
            return ActivityAverageSnapshot.forCategory(category, 0, null, null, 0L, null,
                false, "UNAVAILABLE_INVALID_CATEGORY");
        }
        if (hasActiveContributionFilter())
        {
            return ActivityAverageSnapshot.forCategory(category, 0, null, null, 0L, null,
                false, "UNAVAILABLE_ACTIVE_ACCOUNTING_FILTER");
        }

        long totalNet = 0L;
        long totalMillis = 0L;
        boolean complete = true;
        boolean durationsComplete = true;
        List<ProfitSession> profileSessions = uniqueProfileSessions();
        Set<String> availableSessionIds = new HashSet<>();
        for (ProfitSession profileSession : profileSessions)
        {
            if (profileSession != null) availableSessionIds.add(profileSession.getId());
        }
        List<Long> sessionNets = new ArrayList<>();
        for (ProfitSession session : profileSessions)
        {
            if (session == null || session.getCategory() != category
                || session.isExcludedFromAverages()
                || (excludeSessionId != null && excludeSessionId.equals(session.getId())))
            {
                continue;
            }

            List<TrackingDaySummary> days = session.getAnalyticsDays();
            if (hasTruncatedActivityHistory(session, days)
                || (days.isEmpty() && (session.getCompactedTransactionCount() > 0L
                    || !session.getTransactions().isEmpty())))
            {
                complete = false;
                durationsComplete = false;
            }

            long sessionNet = 0L;
            long sessionMillis = 0L;
            boolean hasDay = false;
            boolean sessionDurationsComplete = true;
            for (TrackingDaySummary day : days)
            {
                if (day == null) continue;
                hasDay = true;
                sessionNet = saturatingAdd(sessionNet, day.getNet());
                sessionMillis = saturatingAdd(sessionMillis, Math.max(0L, day.getActiveMillis()));
                if (!day.isActiveTimeAvailable())
                {
                    sessionDurationsComplete = false;
                    durationsComplete = false;
                }
            }
            if (hasDay)
            {
                sessionNets.add(sessionNet);
                totalNet = saturatingAdd(totalNet, sessionNet);
                totalMillis = saturatingAdd(totalMillis, sessionMillis);
                if (!sessionDurationsComplete) durationsComplete = false;
            }
        }

        for (DailyRollup rollup : refreshDailyRollups())
        {
            if (rollup == null) continue;
            DailyRollup.Coverage categoryCoverage =
                rollup.getCoverage(DailyRollup.Dimension.CATEGORIES);
            boolean containsCategory = rollup.getCategoryTotals().containsKey(category);
            if (categoryCoverage != DailyRollup.Coverage.COMPLETE)
            {
                complete = false;
                durationsComplete = false;
            }
            // A complete split with no entry proves zero. An old or partial
            // split with no entry cannot prove this category was absent.
            if (!containsCategory) continue;
            List<String> sources = rollup.getSourceSessionIds();
            if (sources.isEmpty())
            {
                complete = false;
                durationsComplete = false;
                continue;
            }
            for (String sourceId : sources)
            {
                if (!availableSessionIds.contains(sourceId))
                {
                    complete = false;
                    durationsComplete = false;
                }
            }
        }

        if (sessionNets.isEmpty())
        {
            return ActivityAverageSnapshot.forCategory(category, 0, null, null, totalMillis, null,
                complete, complete ? "UNAVAILABLE_NO_CATEGORY_DATA" : "PARTIAL_CATEGORY_HISTORY");
        }
        if (!complete)
        {
            return ActivityAverageSnapshot.forCategory(category, sessionNets.size(), null, null,
                totalMillis, null, false, "PARTIAL_CATEGORY_HISTORY");
        }

        Long rate = durationsComplete && totalMillis > 0L
            ? weightedGpPerHour(totalNet, totalMillis) : null;
        return ActivityAverageSnapshot.forCategory(category, sessionNets.size(), totalNet,
            medianSessionNet(sessionNets), totalMillis, rate, true,
            rate != null ? "AVAILABLE" : "UNAVAILABLE_CATEGORY_TIME");
    }

    private static Long matchingValue(Map<String, Long> values, String requested)
    {
        if (values == null) return null;
        long total = 0L;
        boolean found = false;
        for (Map.Entry<String, Long> entry : values.entrySet())
        {
            if (entry.getKey() != null && entry.getKey().trim().equalsIgnoreCase(requested)
                && entry.getValue() != null)
            {
                total = saturatingAdd(total, entry.getValue());
                found = true;
            }
        }
        return found ? total : null;
    }

    private static boolean containsActivityIgnoreCase(DailyRollup rollup, String requested)
    {
        for (String activity : rollup.getActivities().keySet())
        {
            if (activity != null && activity.trim().equalsIgnoreCase(requested)) return true;
        }
        return false;
    }

    private static boolean hasTruncatedActivityHistory(ProfitSession session,
        List<TrackingDaySummary> days)
    {
        long analyticsStart = session.getAnalyticsStartedAtEpochMillis();
        if (analyticsStart <= 0L || days == null || days.isEmpty()) return false;
        long earliestDayStart = Long.MAX_VALUE;
        for (TrackingDaySummary day : days)
        {
            if (day == null) continue;
            try
            {
                long dayStart = LocalDate.parse(day.getDay()).atStartOfDay(day.getZone())
                    .toInstant().toEpochMilli();
                earliestDayStart = Math.min(earliestDayStart, dayStart);
            }
            catch (RuntimeException ignored)
            {
                return true;
            }
        }
        return earliestDayStart != Long.MAX_VALUE && earliestDayStart > analyticsStart;
    }

    private static Double medianSessionNet(List<Long> values)
    {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) return (double) sorted.get(middle);
        return sorted.get(middle - 1) / 2.0d + sorted.get(middle) / 2.0d;
    }

    private static long weightedGpPerHour(long net, long activeMillis)
    {
        try
        {
            return BigDecimal.valueOf(net).multiply(BigDecimal.valueOf(3_600_000L))
                .divide(BigDecimal.valueOf(activeMillis), 0, RoundingMode.HALF_UP).longValueExact();
        }
        catch (ArithmeticException overflow)
        {
            return net < 0L ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    private InsightsWindowSnapshot.Window partialForLegacyZone(InsightsWindowSnapshot.Window window)
    {
        for (DailyRollup.Dimension dimension : DailyRollup.Dimension.values())
        {
            window = window.withCoverage(dimension, DailyRollup.Coverage.PARTIAL);
        }
        return window;
    }

    /** How many times the profile rollups were rebuilt from sessions; test scope. */
    int rollupRebuildsForTest()
    {
        return rollupRebuilds;
    }

    private List<DailyRollup> refreshDailyRollups()
    {
        ZoneId profileZone = profileZone();
        if (rollupCache != null && rollupCacheGeneration == rollupGeneration
            && profileZone.getId().equals(rollupCacheZoneId))
        {
            dailyRollups = rollupCache;
            return Collections.unmodifiableList(new ArrayList<>(rollupCache));
        }
        rollupRebuilds++;
        Map<String, DailyRollup.Builder> builders = new LinkedHashMap<>();
        List<ProfitSession> sessions = uniqueProfileSessions();
        for (ProfitSession session : sessions)
        {
            if (session == null) continue;
            for (TrackingDaySummary day : session.getAnalyticsDays())
            {
                if (day == null || day.getDay().isEmpty()) continue;
                LocalDate date;
                try { date = LocalDate.parse(day.getDay()); }
                catch (RuntimeException ex) { continue; }
                ZoneId dayZone;
                try { dayZone = ZoneId.of(day.getZoneId()); }
                catch (RuntimeException ex) { dayZone = ZoneOffset.UTC; }
                DailyRollup.Builder builder = dailyBuilder(builders, date, dayZone);
                builder.addSourceSessionId(session.getId());
                builder.addAccounting(day.getRevenue(), day.getCosts());
                if (day.isCostSplitAvailable())
                    builder.addCostSplit(day.getSuppliesCosts(), day.getOtherCosts());
                else builder.coverage(DailyRollup.Dimension.COST_SPLIT, DailyRollup.Coverage.UNAVAILABLE);
                builder.addActiveMillis(day.getActiveMillis());
                if (!day.isActiveTimeAvailable() || day.isActiveTimeRebuilt())
                    builder.coverage(DailyRollup.Dimension.ACTIVE_TIME, DailyRollup.Coverage.PARTIAL);
                builder.addActivityNet(day.getActivityNet(), day.getActivityActiveMillis(), session.getId());
                if (!day.isActivityActiveMillisAvailable())
                    builder.coverage(DailyRollup.Dimension.ACTIVITIES, DailyRollup.Coverage.PARTIAL);
                builder.addCategory(session.getCategory(), day.getNet(), day.getActiveMillis());
                if (!day.isActiveTimeAvailable())
                    builder.coverage(DailyRollup.Dimension.CATEGORIES, DailyRollup.Coverage.PARTIAL);
                builder.addPvmEncounters(day.getEncounterTotals(), day.isEncounterTotalsAvailable());
                builder.addGainedItems(day.getGainedItemTotals().values());
                builder.addCostItems(day.getCostItemTotals().values());
                if (!day.isGainedItemTotalsAvailable())
                    builder.coverage(DailyRollup.Dimension.GAINED_ITEMS, DailyRollup.Coverage.PARTIAL);
                else
                    builder.coverage(DailyRollup.Dimension.GAINED_ITEMS, DailyRollup.Coverage.COMPLETE);
                if (!day.isCostItemTotalsAvailable())
                    builder.coverage(DailyRollup.Dimension.COST_ITEMS, DailyRollup.Coverage.PARTIAL);
                else
                    builder.coverage(DailyRollup.Dimension.COST_ITEMS, DailyRollup.Coverage.COMPLETE);
                builder.addFourHourBuckets(day.getFourHourNetGp(), day.getFourHourActiveMillis());
                if (!day.isFourHourBucketsAvailable())
                    builder.coverage(DailyRollup.Dimension.FOUR_HOUR_BUCKETS, DailyRollup.Coverage.UNAVAILABLE);
                if (day.isHourlyBucketsAvailable())
                {
                    builder.addHourlyBuckets(day.getHourlyNetGp(), day.getHourlyActiveMillis());
                }
                else
                {
                    builder.coverage(DailyRollup.Dimension.HOURLY_BUCKETS,
                        DailyRollup.Coverage.UNAVAILABLE);
                }
                builder.coverage(DailyRollup.Dimension.EVENT_COUNTS, DailyRollup.Coverage.COMPLETE);
                if (!profileZone.equals(dayZone))
                {
                    for (DailyRollup.Dimension dimension : DailyRollup.Dimension.values())
                        builder.coverage(dimension, DailyRollup.Coverage.PARTIAL);
                }
            }

            if (session.getStartedAtEpochMillis() > 0L)
            {
                LocalDate date = Instant.ofEpochMilli(session.getStartedAtEpochMillis())
                    .atZone(profileZone).toLocalDate();
                SessionOwnerKind ownerKind = session.getOwnerKind();
                dailyBuilder(builders, date, profileZone).addSourceSessionId(session.getId())
                    .addSessionStart(session.getStartedAtEpochMillis())
                    .addNamedSessionStart(session.getStartedAtEpochMillis(),
                        ownerKind == SessionOwnerKind.NAMED_SESSION,
                        ownerKind != SessionOwnerKind.UNKNOWN);
            }
            else
            {
                markSessionEventCoverage(builders, session, true);
            }

            for (Run run : session.getRuns(System.currentTimeMillis()))
            {
                if (run == null) continue;
                long started = run.getStartedAtEpochMillis();
                if (started > 0L)
                {
                    LocalDate date = Instant.ofEpochMilli(started).atZone(profileZone).toLocalDate();
                    dailyBuilder(builders, date, profileZone).addSourceSessionId(session.getId())
                        .addRunStart(started);
                }
                else
                {
                    markSessionEventCoverage(builders, session);
                }
            }

            for (PkEncounter encounter : session.getPkEncounters())
            {
                if (encounter == null) continue;
                LocalDate date = Instant.ofEpochMilli(encounter.getTimestampEpochMillis())
                    .atZone(profileZone).toLocalDate();
                DailyRollup.Builder builder = dailyBuilder(builders, date, profileZone);
                builder.addSourceSessionId(session.getId());
                boolean kill = encounter.getType() == PkEncounterType.KILL;
                builder.addEventCounts(kill ? 1 : 0, kill ? 0 : 1, 0, 0);
                if (encounter.isFinancialSummaryAvailable())
                {
                    builder.addPkEncounter(encounter, encounter.getFinancialNetGp(),
                        encounter.getFinancialLossGp());
                }
                else
                {
                    builder.addPvp(kill ? 1 : 0, kill ? 0 : 1, 0L, 0L, 0L, 0L)
                        .coverage(DailyRollup.Dimension.PVP, DailyRollup.Coverage.UNAVAILABLE);
                }
            }
        }

        List<DailyRollup> result = new ArrayList<>();
        for (DailyRollup.Builder builder : builders.values()) result.add(builder.build());
        result.sort(Comparator.comparing(DailyRollup::getDate)
            .thenComparing(DailyRollup::getZoneId));
        result = mergePersistedDailyRollups(result);
        dailyRollups = result;
        rollupCache = result;
        rollupCacheGeneration = rollupGeneration;
        rollupCacheZoneId = profileZone.getId();
        return Collections.unmodifiableList(new ArrayList<>(result));
    }

    /**
     * Retains serialized profile days that are no longer represented by a
     * session summary. A rebuilt day replaces its cached copy when it covers
     * every source session recorded by that snapshot. If some persisted source
     * is absent from the rebuilt slice, current values remain visible as a
     * partial subset rather than being added to the aggregate and double-counted.
     */
    private List<DailyRollup> mergePersistedDailyRollups(List<DailyRollup> rebuilt)
    {
        Map<String, DailyRollup> merged = new LinkedHashMap<>();
        if (rebuilt != null)
        {
            for (DailyRollup rollup : rebuilt)
            {
                if (rollup != null) merged.put(dailyRollupKey(rollup), rollup);
            }
        }
        if (persistedDailyRollupBaseline != null)
        {
            for (DailyRollup persisted : persistedDailyRollupBaseline)
            {
                if (persisted == null) continue;
                String key = dailyRollupKey(persisted);
                DailyRollup current = merged.get(key);
                if (current == null)
                {
                    merged.put(key, persisted);
                }
                else
                {
                    boolean persistedSourcesCovered = !persisted.getSourceSessionIds().isEmpty()
                        && current.getSourceSessionIds().containsAll(persisted.getSourceSessionIds());
                    boolean activitySessionCountMismatch = persistedSourcesCovered
                        && persisted.getCoverage(DailyRollup.Dimension.ACTIVITY_SESSION_COUNTS)
                            == DailyRollup.Coverage.COMPLETE
                        && current.getCoverage(DailyRollup.Dimension.ACTIVITY_SESSION_COUNTS)
                            == DailyRollup.Coverage.COMPLETE
                        && !current.hasSameActivitySessionCounts(persisted);
                    boolean namedSessionStartMismatch = persistedSourcesCovered
                        && persisted.getCoverage(DailyRollup.Dimension.NAMED_SESSION_STARTS)
                            == DailyRollup.Coverage.COMPLETE
                        && current.getCoverage(DailyRollup.Dimension.NAMED_SESSION_STARTS)
                            == DailyRollup.Coverage.COMPLETE
                        && current.getNamedSessionStarts() != persisted.getNamedSessionStarts();
                    if (!persistedSourcesCovered
                        && !current.hasSameRecordedValuesExceptCategories(persisted))
                    {
                        DailyRollup.Builder uncertain = DailyRollup.builder(current.getDate(), current.getZone())
                            .merge(current);
                        for (DailyRollup.Dimension dimension : DailyRollup.Dimension.values())
                        {
                            uncertain.coverage(dimension, DailyRollup.Coverage.PARTIAL);
                        }
                        merged.put(key, uncertain.build());
                    }
                    else if (activitySessionCountMismatch || namedSessionStartMismatch)
                    {
                        DailyRollup.Builder uncertain = DailyRollup.builder(current.getDate(), current.getZone())
                            .merge(current);
                        if (activitySessionCountMismatch)
                        {
                            uncertain.coverage(DailyRollup.Dimension.ACTIVITY_SESSION_COUNTS,
                                DailyRollup.Coverage.PARTIAL);
                        }
                        if (namedSessionStartMismatch)
                        {
                            uncertain.coverage(DailyRollup.Dimension.NAMED_SESSION_STARTS,
                                DailyRollup.Coverage.PARTIAL);
                        }
                        merged.put(key, uncertain.build());
                    }
                    else if (!persistedSourcesCovered)
                    {
                        // The legacy aggregate may contain category sources no
                        // longer retained as sessions. Keep the pre-category
                        // dimensions intact when they reconcile, but do not
                        // claim a complete category split.
                        DailyRollup.Builder uncertain = DailyRollup.builder(current.getDate(), current.getZone())
                            .merge(current)
                            .coverage(DailyRollup.Dimension.CATEGORIES, DailyRollup.Coverage.PARTIAL)
                            .coverage(DailyRollup.Dimension.PVM_ENCOUNTERS, DailyRollup.Coverage.PARTIAL)
                            .coverage(DailyRollup.Dimension.PVM_ENCOUNTER_SOURCES,
                                DailyRollup.Coverage.PARTIAL)
                            .coverage(DailyRollup.Dimension.ACTIVITY_SESSION_COUNTS,
                                DailyRollup.Coverage.PARTIAL)
                            .coverage(DailyRollup.Dimension.NAMED_SESSION_STARTS,
                                DailyRollup.Coverage.UNAVAILABLE)
                            .coverage(DailyRollup.Dimension.HOURLY_BUCKETS,
                                DailyRollup.Coverage.PARTIAL);
                        merged.put(key, uncertain.build());
                    }
                }
            }
        }
        List<DailyRollup> result = new ArrayList<>(merged.values());
        result.sort(Comparator.comparing(DailyRollup::getDate)
            .thenComparing(DailyRollup::getZoneId));
        return result;
    }

    private static String dailyRollupKey(DailyRollup rollup)
    {
        return rollup.getDay() + "@" + rollup.getZoneId();
    }

    /** Explicit history deletion/reset must not resurrect its cached daily totals. */
    private void discardPersistedDailyRollupBaseline()
    {
        persistedDailyRollupBaseline.clear();
        dailyRollups.clear();
        invalidateOverallTotalsCache();
    }

    private void markSessionEventCoverage(Map<String, DailyRollup.Builder> builders,
        ProfitSession session)
    {
        markSessionEventCoverage(builders, session, false);
    }

    private void markSessionEventCoverage(Map<String, DailyRollup.Builder> builders,
        ProfitSession session, boolean ownerStartMissing)
    {
        for (TrackingDaySummary day : session.getAnalyticsDays())
        {
            if (day == null) continue;
            try
            {
                LocalDate date = LocalDate.parse(day.getDay());
                DailyRollup.Builder builder = dailyBuilder(builders, date, ZoneId.of(day.getZoneId()));
                builder
                    .addSourceSessionId(session.getId())
                    .coverage(DailyRollup.Dimension.EVENT_COUNTS, DailyRollup.Coverage.PARTIAL);
                if (ownerStartMissing && session.getOwnerKind() != SessionOwnerKind.FREE_PLAY)
                {
                    builder.coverage(DailyRollup.Dimension.NAMED_SESSION_STARTS,
                        DailyRollup.Coverage.UNAVAILABLE);
                }
            }
            catch (RuntimeException ignored) { }
        }
    }

    private static DailyRollup.Builder dailyBuilder(Map<String, DailyRollup.Builder> builders,
        LocalDate date, ZoneId zone)
    {
        String key = date + "@" + zone.getId();
        DailyRollup.Builder builder = builders.get(key);
        if (builder == null)
        {
            builder = DailyRollup.builder(date, zone);
            builders.put(key, builder);
        }
        return builder;
    }

    private ZoneId profileZone()
    {
        try { return ZoneId.of(profileTimeZoneId); }
        catch (RuntimeException ex) { return ZoneId.systemDefault(); }
    }

    public synchronized void clearDeferredChestClaimLifecycle()
    {
        deferredChestClaimLifecycle.cancel();
    }

    public synchronized void setLootKeyChestVisible(boolean visible)
    {
        lootKeyLifecycle.setLootChestVisible(visible);
    }

    public synchronized void observeLootKeyContainer(
        int keyItemId,
        Map<Integer, Long> contents,
        Map<Integer, Long> unitPrices,
        long manifestValueGp,
        boolean valueComplete,
        long now)
    {
        lootKeyLifecycle.observeKeyContainerContents(
            getLootKeySessions(), keyItemId, contents, unitPrices, manifestValueGp, valueComplete, now);
    }

    /**
     * Book exact component losses from a compatible measured Check difference.
     * Generic inventory losses, menu intent and animations cannot authorize this.
     */
    public synchronized com.gpmanager.engine.evidence.ChargeSpendBooking.Result bookChargeSpend(
        MeasuredChargeDelta measuredDelta,
        @Nullable String itemName,
        List<ItemFlow> componentLosses,
        long now)
    {
        if (activeSession == null || activeSession.isPaused())
        {
            return new com.gpmanager.engine.evidence.ChargeSpendBooking.Result(
                false, 0L, "No active unpaused session; zero charge cost booked.",
                "Measured charge spend requires an active unpaused session.",
                Collections.emptyList(), null);
        }
        com.gpmanager.engine.evidence.ChargeSpendBooking.Result result =
            com.gpmanager.engine.evidence.ChargeSpendBooking.tryBook(
                measuredDelta, itemName, componentLosses,
                pendingChargeLoadComponents(measuredDelta == null ? null : measuredDelta.getVariant()));
        if (!result.booked)
        {
            return result;
        }
        // The spend belongs to what the player is doing, not to the weapon: Top activities
        // must never grow a "Toxic blowpipe" row. The weapon stays in the note.
        String weapon = itemName == null || itemName.trim().isEmpty() ? "Measured charges" : itemName.trim();
        String hint = activeSession.getActivityHint();
        String activity = hint == null || hint.trim().isEmpty() ? "General" : hint.trim();
        ProfitTransaction transaction = new ProfitTransaction(
            now,
            activeSession.getElapsedMillis(now),
            TransactionType.CONSUMPTION,
            TrackingContext.GENERIC,
            "Measured charge spend · " + weapon,
            activity,
            true,
            result.flows,
            ClassificationConfidence.CONFIRMED,
            result.explanation,
            null);
        transaction.setActionKind(ChargeFamilyIds.BLOWPIPE.equals(measuredDelta.getFamilyId())
            ? com.gpmanager.model.ActionKind.FIRE
            : com.gpmanager.model.ActionKind.CAST);
        evaluateGoalAlerts(now, false);
        activeSession.addTransaction(transaction, config.maxTransactionsPerSession());
        evaluateGoalAlerts(now, false);
        return new com.gpmanager.engine.evidence.ChargeSpendBooking.Result(
            result.booked,
            result.costGp,
            result.warning,
            result.explanation,
            result.flows,
            transaction);
    }

    /**
     * Book a death reclaim fee as counted PK_FEE / Lost cost.
     */
    public synchronized ProfitTransaction bookDeathReclaimFee(
        long feeGp,
        String why,
        long now)
    {
        return bookDeathReclaimFee(feeGp, why, now, true);
    }

    /**
     * @param pvp true books the PK_FEE / PK_DEATH row used for player-combat deaths; false
     *            books a plain counted CONSUMPTION cost under the "Death reclaim" activity
     */
    public synchronized ProfitTransaction bookDeathReclaimFee(
        long feeGp,
        String why,
        long now,
        boolean pvp)
    {
        if (feeGp <= 0L || activeSession == null || activeSession.isPaused())
        {
            return null;
        }
        ItemFlow feeFlow = new ItemFlow(
            995,
            "Coins",
            -feeGp,
            1,
            -feeGp,
            com.gpmanager.model.ItemPriceSource.GRAND_EXCHANGE,
            now);
        ProfitTransaction transaction = new ProfitTransaction(
            now,
            activeSession.getElapsedMillis(now),
            pvp ? TransactionType.PK_FEE : TransactionType.CONSUMPTION,
            pvp ? TrackingContext.PK_DEATH : TrackingContext.GENERIC,
            why == null ? DeathReclaimFees.graveFeeWhy(false) : why,
            "Death reclaim",
            true,
            Collections.singletonList(feeFlow),
            ClassificationConfidence.CONFIRMED,
            why == null ? DeathReclaimFees.graveFeeWhy(false) : why,
            null);
        evaluateGoalAlerts(now, false);
        activeSession.addTransaction(transaction, config.maxTransactionsPerSession());
        evaluateGoalAlerts(now, false);
        return transaction;
    }

    public synchronized void setSessionMode(com.gpmanager.model.SessionMode mode)
    {
        if (activeSession != null)
        {
            activeSession.setMode(mode);
        }
    }

    /** Sets or clears the active session's explicit category override. */
    public synchronized boolean setActiveSessionCategory(SessionCategory category)
    {
        return activeSession != null && activeSession.setCategoryOverride(category);
    }

    public synchronized boolean correctTransaction(
        String transactionId,
        TransactionCorrection correction,
        long now)
    {
        return correctTransaction(transactionId, correction, now, "Manual correction");
    }

    public synchronized boolean correctTransaction(
        String transactionId,
        TransactionCorrection correction,
        long now,
        String reason)
    {
        if (activeSession != null) evaluateGoalAlerts(now, false);
        boolean changed = activeSession != null
            && activeSession.correctTransaction(transactionId, correction, now, reason);
        if (changed) evaluateGoalAlerts(now, false);
        return changed;
    }

    /** Returns the active custom/general session's still-unresolved decisions as detached rows. */
    public synchronized ReviewInbox getReviewInbox(long now)
    {
        if (activeSession == null)
        {
            return ReviewInbox.empty();
        }
        List<ReviewRow> rows = new ArrayList<>();
        Map<String, Integer> countsByReason = new LinkedHashMap<>();
        long oldestAge = 0L;
        for (ProfitTransaction transaction : activeSession.getTransactions())
        {
            if (!ReviewEligibility.needsOwnerDecision(transaction))
            {
                continue;
            }
            ReviewRow row = reviewRow(activeSession, transaction, now);
            rows.add(row);
            countsByReason.merge(row.getWhy(), 1, Integer::sum);
            oldestAge = Math.max(oldestAge, row.getAgeMillis());
        }
        rows.sort(Comparator.comparingLong(ReviewRow::getTimestampEpochMillis).reversed());
        return new ReviewInbox(activeSession.getId(), rows, countsByReason, oldestAge);
    }

    /** Applies one decision to matching unresolved rows as a single undoable batch. */
    public synchronized int decideAll(
        ReviewDecision decision,
        Predicate<ReviewRow> scope,
        long now)
    {
        if (activeSession == null || decision == null || scope == null)
        {
            return 0;
        }
        List<String> selectedIds = new ArrayList<>();
        for (ProfitTransaction transaction : activeSession.getTransactions())
        {
            if (!ReviewEligibility.needsOwnerDecision(transaction))
            {
                continue;
            }
            ReviewRow row = reviewRow(activeSession, transaction, now);
            if (scope.test(row))
            {
                selectedIds.add(row.getTransactionId());
            }
        }
        if (selectedIds.isEmpty())
        {
            return 0;
        }
        evaluateGoalAlerts(now, false);
        int changed = activeSession.correctPendingTransactions(selectedIds,
            decision.toCorrection(), now, "Review decision: " + decision.name());
        if (changed > 0) evaluateGoalAlerts(now, false);
        return changed;
    }

    /** Returns recent applied correction operations, newest first, with their undo links. */
    public synchronized List<CorrectionRecord> getAppliedDecisions(int limit)
    {
        if (activeSession == null || limit <= 0)
        {
            return Collections.emptyList();
        }
        List<CorrectionRecord> history = activeSession.getCorrectionLog();
        List<CorrectionRecord> result = new ArrayList<>(Math.min(limit, history.size()));
        for (int index = history.size() - 1; index >= 0 && result.size() < limit; index--)
        {
            result.add(history.get(index));
        }
        return Collections.unmodifiableList(result);
    }

    private static ReviewRow reviewRow(ProfitSession session, ProfitTransaction transaction, long now)
    {
        List<ReviewRow.Item> items = new ArrayList<>();
        for (ItemFlow flow : transaction.getFlows())
        {
            if (flow != null)
            {
                items.add(new ReviewRow.Item(flow.getItemId(), flow.getItemName(),
                    flow.getQuantityDelta(), flow.getValueDelta()));
            }
        }
        if (items.isEmpty())
        {
            items.add(new ReviewRow.Item(-1, transaction.getNote(), 0L, transaction.getAutomaticNet()));
        }
        String why = transaction.getExplanation();
        if (why == null || why.trim().isEmpty())
        {
            why = "Uncertain transaction";
        }
        long timestamp = transaction.getTimestampEpochMillis();
        long age = now > timestamp ? now - timestamp : 0L;
        java.util.EnumSet<ReviewDecision> decisions = java.util.EnumSet.allOf(ReviewDecision.class);
        return new ReviewRow(transaction.getId(), session.getId(), timestamp, age, items,
            transaction.getAutomaticNet(), why, decisions);
    }

    public synchronized boolean applyItemSplit(
        String transactionId,
        int itemId,
        long keepQuantity,
        long now,
        String optionalNote)
    {
        if (activeSession != null) evaluateGoalAlerts(now, false);
        boolean changed = activeSession != null
            && activeSession.applyItemSplit(transactionId, itemId, keepQuantity, now, optionalNote);
        if (changed) evaluateGoalAlerts(now, false);
        return changed;
    }

    public synchronized boolean undoLastCorrection(long now)
    {
        if (activeSession != null) evaluateGoalAlerts(now, false);
        boolean changed = activeSession != null && activeSession.undoLastCorrection(now);
        if (changed) evaluateGoalAlerts(now, false);
        return changed;
    }

    public synchronized ProfitTransaction undoLastTransaction()
    {
        return undoLastTransaction(System.currentTimeMillis());
    }

    public synchronized ProfitTransaction undoLastTransaction(long now)
    {
        if (activeSession != null) evaluateGoalAlerts(now, false);
        ProfitTransaction undone = activeSession == null ? null : activeSession.undoLastTransaction(now);
        if (undone != null) evaluateGoalAlerts(now, false);
        return undone;
    }

    public synchronized ProfitTransaction restoreLastUndo(long now)
    {
        if (activeSession != null) evaluateGoalAlerts(now, false);
        ProfitTransaction restored = activeSession == null ? null : activeSession.restoreLastUndo(now);
        if (restored != null) evaluateGoalAlerts(now, false);
        return restored;
    }

    public synchronized void recordAction(String activityHint)
    {
        if (activeSession != null && !activeSession.isPaused())
        {
            activeSession.recordAction(activityHint);
        }
    }

    public synchronized ProfitTransaction processIfDirty(ContainerSnapshot current, long now)
    {
        // Inventory alone must not create a tracker when automatic startup is
        // disabled or when no session has been started yet.
        if (activeSession == null)
        {
            return null;
        }

        // processIfDirty is fed by the client's per-game-tick snapshot path.
        // Keep active-time accrual on that event path instead of analytics reads.
        advanceLiveOwnerAnalytics(now);
        evaluateGoalAlerts(now, true);
        if (maybeAutoEndIdleCustomSession(now))
        {
            // The snapshot belongs to the just-closed custom owner; never replay
            // it into resumed General on the same tick.
            return null;
        }

        // This window ages once per inventory/game-tick sample, even when the
        // inventory remains dirty across several stabilization samples.
        ageNeutralZoneEntryCapture();

        // Death and reclaim windows are real game-tick lifecycles. Advance them before
        // any dirty/stabilization early return so inventory churn cannot pause expiry.
        DeathReclaimLifecycle.Expired expiredDeathReclaim = deathReclaim.tick(
            dirty || pendingSnapshot != null
                || (current != null && baseline != null && !current.equals(baseline)));
        if (expiredDeathReclaim != null)
        {
            ProfitTransaction expiry = deathReclaimExpiryTransaction(now, expiredDeathReclaim);
            activeSession.addTransaction(expiry, config.maxTransactionsPerSession());
            recordAlert(new AlertEvent(AlertKind.RECLAIM_EXPIRED, now, activeSession.getId(),
                "Death reclaim expired", expiry.getExplanation(), 0L, -1));
            return expiry;
        }

        if (activeSession.isPaused())
        {
            // Keep the baseline aligned while paused so Resume never treats
            // pause-window inventory churn as catch-up profit.
            baseline = current;
            if (captureNeutralZoneEntry)
            {
                neutralZoneEntryBaseline = current;
            }
            baselinePriming = false;
            clearPendingChange();
            consumptionIntent = null;
            dropIntent = null;
            clearHardTransferEvidence();
            return advanceContext(now);
        }

        if (baselinePriming)
        {
            observePrimingSnapshot(current);
            return null;
        }

        if (baseline == null)
        {
            baseline = current;
            if (captureNeutralZoneEntry)
            {
                neutralZoneEntryBaseline = current;
            }
            clearPendingChange();
            return null;
        }

        if (!dirty && current.equals(baseline))
        {
            return advanceContext(now);
        }

        // Container callbacks are expected, but comparing the actual snapshot
        // makes the engine resilient to a missed or reordered callback.
        if (!dirty)
        {
            dirty = true;
            captureActiveContextForPending();
        }

        if (pendingSnapshot == null || !pendingSnapshot.equals(current))
        {
            pendingSnapshot = current;
            pendingStableTicks = 0;
            captureActiveContextForPending();
            return null;
        }

        pendingStableTicks++;
        if (pendingStableTicks < Math.max(0, config.stabilizationTicks()))
        {
            return null;
        }

        ContainerSnapshot committedSnapshot = pendingSnapshot;
        TrackingContext appliedContext = pendingContext;
        String appliedNote = pendingContextNote;
        LocalDeathEvidence appliedDeathEvidence = pendingLocalDeathEvidence;
        boolean suppressActionEvidence = UNCLASSIFIED_LOCAL_DEATH_NOTE.equals(appliedNote);
        boolean unclassifiedLocalDeathSettlement = suppressActionEvidence;
        if (suppressActionEvidence)
        {
            // Keep routine rune/ammo spends distinguishable from local death losses
            // without exposing an internal marker in the transaction note.
            appliedNote = "";
        }
        Map<Integer, Long> expectedLoot = pendingExpectedLoot;
        String appliedEncounterId = pendingEncounterId;
        boolean transferEvidence = pendingTransferEvidence;
        boolean hardTransferEvidence = pendingHardTransferEvidence;
        boolean softTransferEvidence = transferEvidence && !hardTransferEvidence;

        Map<Integer, Long> deltas = committedSnapshot.diff(baseline);
        baseline = committedSnapshot;
        if (captureNeutralZoneEntry && neutralZoneEntryBaseline != null)
        {
            Map<Integer, Long> entryDeltas = committedSnapshot.diff(neutralZoneEntryBaseline);
            if (appliedContext == TrackingContext.TRANSFER
                && appliedNote != null
                && appliedNote.startsWith("Neutral zone instance"))
            {
                recordNeutralZoneEntryItems(entryDeltas);
            }
            neutralZoneEntryBaseline = committedSnapshot;
        }
        clearPendingChange();

        if (deltas.isEmpty())
        {
            // Same-tick acquire+consume (pickup then bury) nets to zero vs the prior
            // baseline. Recover a named consume when invent qty fell since intent arm.
            deltas = sameTickConsumeDeltas(committedSnapshot);
            if (deltas.isEmpty())
            {
                consumeContext();
                clearHardTransferEvidence();
                return null;
            }
        }

        List<ItemFlow> flows = valuationService.value(deltas, now);
        if (flows.isEmpty())
        {
            consumeContext();
            clearHardTransferEvidence();
            return null;
        }

        boolean deathWipeCandidate = deathReclaim.isDeathWipePending() && hasNegativeFlow(flows);
        boolean deathWipeContextAllowed = deathWipeCandidate
            && deathWipeContextEligible(appliedContext, appliedNote, transferEvidence,
                hardTransferEvidence, softTransferEvidence);
        if (deathWipeCandidate && !deathWipeContextAllowed)
        {
            // A stronger market, transfer, production or loot context owns this change.
            deathReclaim.cancelDeathWipe();
        }
        Map<Integer, Long> actionLosses = deathWipeContextAllowed
            ? confirmedActionLossQuantities(flows, hardTransferEvidence)
            : Collections.emptyMap();
        List<ItemFlow> deathCandidateFlows = deathWipeContextAllowed
            ? withoutExcludedItemLosses(flows, actionLosses)
            : Collections.emptyList();
        Map<Integer, Long> deathWipeLosses = deathWipeContextAllowed
            ? deathReclaim.onDeathItemsRemoved(deathCandidateFlows)
            : Collections.emptyMap();
        if (!actionLosses.isEmpty())
        {
            // Consume action-attributed quantities from the potential wipe whitelist,
            // but leave unrelated held-at-death ids eligible in the same settle.
            deathReclaim.excludeActionLosses(actionLosses);
        }
        boolean deathWipeSettlement = !deathWipeLosses.isEmpty();
        boolean ambiguousCoinLossDuringDeathWipe = deathWipeSettlement
            && deathReclaim.isReclaimArmed()
            && hasNegativeFlowForItem(flows, 995)
            && !deathWipeLosses.containsKey(995);
        boolean localDeathSettlement = deathWipeSettlement
            || unclassifiedLocalDeathSettlement
            || appliedContext == TrackingContext.PK_DEATH;
        boolean appliedDeathTransferNote = appliedContext == TrackingContext.TRANSFER
            && appliedNote != null
            && appliedNote.startsWith("Death: items held");
        if (appliedDeathTransferNote
            && (!deathWipeSettlement || ambiguousCoinLossDuringDeathWipe))
        {
            // The short note is not ownership evidence. Without a measured whitelist
            // match, let the remaining change follow its actual action/classification.
            appliedContext = TrackingContext.GENERIC;
            appliedNote = "";
            appliedEncounterId = null;
            transferEvidence = false;
            hardTransferEvidence = false;
            softTransferEvidence = false;
            clearHardTransferEvidence();
        }
        Map<Integer, Long> cataloguedKeyLosses = negativeCataloguedKeyQuantities(flows);
        Map<Integer, Long> deferredClaimKeyLosses = negativeDeferredClaimKeyQuantities(flows);
        ProfitTransaction deferredClaimAuditUpdate = null;
        String deferredClaimSourceRunId = null;
        boolean deferredClaimRunResolved = false;
        boolean deferredClaimMatched = false;
        if (localDeathSettlement && !deferredClaimKeyLosses.isEmpty())
        {
            for (Map.Entry<Integer, Long> loss : deferredClaimKeyLosses.entrySet())
            {
                List<ProfitTransaction> changed = DeferredChestClaimLifecycle.closeLostOnDeath(
                    getLootKeySessions(), loss.getKey(), loss.getValue(), now);
                if (!changed.isEmpty())
                {
                    deferredClaimAuditUpdate = changed.get(changed.size() - 1);
                }
            }
            deferredChestClaimLifecycle.cancel();
        }

        // The key item itself is provenance, not loot value. Preserve a separate
        // non-counted audit row from the settled INV gain and let only later
        // contents withdrawals contribute their ordinary valued flows.
        List<ItemFlow> deferredKeyFlows = positiveLootKeyFlows(flows);
        ProfitTransaction lootKeyAuditTransaction = null;
        if (!deferredKeyFlows.isEmpty())
        {
            boolean keyPickupEvidence = hasCredibleLootKeyPickupEvidence(
                appliedContext, flows, expectedLoot, deferredKeyFlows,
                transferEvidence, hardTransferEvidence, softTransferEvidence);
            flows = withoutPositiveLootKeyFlows(flows);
            expectedLoot = withoutLootKeyItems(expectedLoot);
            if (keyPickupEvidence)
            {
                lootKeyAuditTransaction = recordLootKeyAuditRow(deferredKeyFlows, appliedEncounterId, now);
            }
        }
        List<ItemFlow> deferredClaimKeyGains = positiveDeferredClaimKeyFlows(flows);
        ProfitTransaction deferredClaimAuditTransaction = null;
        if (!deferredClaimKeyGains.isEmpty())
        {
            boolean keyPickupEvidence = hasCredibleDeferredClaimPickupEvidence(
                appliedContext, flows, expectedLoot, deferredClaimKeyGains,
                transferEvidence, hardTransferEvidence, softTransferEvidence);
            flows = withoutDeferredClaimKeyFlows(flows);
            expectedLoot = withoutDeferredClaimKeys(expectedLoot);
            if (keyPickupEvidence && !localDeathSettlement)
            {
                deferredClaimAuditTransaction = recordDeferredClaimAuditRows(deferredClaimKeyGains, now);
            }
        }
        else
        {
            flows = withoutDeferredClaimKeyFlows(flows);
            expectedLoot = withoutDeferredClaimKeys(expectedLoot);
        }
        ProfitTransaction keyAuditTransaction = deferredClaimAuditTransaction != null
            ? deferredClaimAuditTransaction : lootKeyAuditTransaction;
        if (localDeathSettlement)
        {
            deferredChestClaimLifecycle.cancel();
        }
        if (positiveFlowQuantities(flows).isEmpty())
        {
            // The click plus measured key removal may settle a tick before the
            // chest's contents enter INV. Keep only that narrow causal evidence.
            deferredChestClaimLifecycle.matchContents(
                cataloguedKeyLosses, Collections.emptyMap(), null);
        }
        if (flows.isEmpty())
        {
            consumeContext();
            clearHardTransferEvidence();
            return keyAuditTransaction != null ? keyAuditTransaction : deferredClaimAuditUpdate;
        }

        List<ItemFlow> deathEvidenceFlows = localDeathSettlement
            ? new ArrayList<>(flows)
            : Collections.emptyList();
        if (localDeathSettlement || lootKeyLifecycle.isAwaitingLocalDeathSettle())
        {
            lootKeyLifecycle.recordDeathLosses(
                getLootKeySessions(), flows,
                LootKeyLifecycle.keyQuantities(committedSnapshot.getQuantities()), now);
        }
        ProfitTransaction deathWipeTransfer = null;
        if (!deathWipeLosses.isEmpty())
        {
            deathWipeTransfer = extractDeathWipeTransfer(
                flows, deathWipeLosses, now, appliedDeathEvidence);
        }
        if (deathWipeSettlement
            && (deathWipeTransfer != null
                || (appliedNote != null && appliedNote.startsWith("Death: items held"))))
        {
            // The measured death-owned quantities have their own neutral receipt. Any
            // residual item changes in this settle must follow their own evidence.
            appliedContext = TrackingContext.GENERIC;
            appliedNote = "";
            appliedEncounterId = null;
            transferEvidence = false;
            hardTransferEvidence = false;
            softTransferEvidence = false;
            clearHardTransferEvidence();
        }
        // Key tokens are deferred provenance on both sides of the lifecycle: their
        // inventory loss is never a counted cost. Preserve the measured quantity for
        // death annotation, but exclude the token from transaction maths. A same-ID
        // loss in a mixed claim settle has no source identity, so it never reduces the
        // manifest key quantity on that evidence alone.
        flows = withoutDeferredLootKeyTokenLosses(flows, negativeLootKeyQuantities(flows));
        // Do not let this narrow menu intent compete with stronger bank/trade/
        // transfer evidence or the loot-key lifecycle. A stale load click expires
        // once a stronger ownership context is already active.
        if (hardTransferEvidence
            || appliedContext == TrackingContext.TRANSFER
            || appliedContext == TrackingContext.MARKET)
        {
            chargeLoadTransferEvidence.clear();
        }
        ChargeLoadTransferEvidence.Partition chargeLoadPartition = chargeLoadTransferEvidence.partition(flows);
        ProfitTransaction chargeLoadReview = null;
        if (chargeLoadPartition.ambiguousQuantity)
        {
            flows = new ArrayList<>(chargeLoadPartition.remaining);
            chargeLoadReview = recordAmbiguousChargeLoadReview(chargeLoadPartition, now);
        }
        else if (chargeLoadPartition.hasTransfer())
        {
            flows = new ArrayList<>(chargeLoadPartition.remaining);
            recordChargeLoadTransfer(chargeLoadPartition, now);
        }
        if (flows.isEmpty())
        {
            consumeContext();
            clearHardTransferEvidence();
            if (keyAuditTransaction != null) return keyAuditTransaction;
            if (deferredClaimAuditUpdate != null) return deferredClaimAuditUpdate;
            if (deathWipeTransfer != null) return deathWipeTransfer;
            return chargeLoadReview;
        }

        List<LootKeyProvenance> lootKeyClaimReceipts = Collections.emptyList();

        // Consume source-backed loot before the reclaim partitioner so a kill drop
        // sharing an item id with the death wipe remains counted as loot.
        LootMatch queuedLoot = appliedContext != TrackingContext.TRANSFER
            && appliedContext != TrackingContext.MARKET
            ? consumeQueuedLoot(flows)
            : LootMatch.NONE;
        // Keep the exact source-backed quantities even if the full-settle check below
        // clears the loot label. They remain unavailable to a key manifest claim.
        Map<Integer, Long> queuedLootMatchedQuantities = queuedLoot.getMatchedQuantities();

        Map<Integer, Long> chestContents = queuedLoot.isMatched()
            ? positiveFlowQuantities(flows)
            : positiveFlowQuantitiesExcluding(flows, queuedLootMatchedQuantities);
        String sourceBackedActivity = queuedLoot.isMatched() ? queuedLoot.getActivityName() : null;
        boolean chestClaimContext = !localDeathSettlement
            && !hardTransferEvidence
            && !softTransferEvidence
            && appliedContext != TrackingContext.TRANSFER
            && appliedContext != TrackingContext.MARKET
            && appliedContext != TrackingContext.PRODUCTION
            && (queuedLoot.isMatched() || appliedContext == TrackingContext.GENERIC)
            && hasOnlyGainContentsOrCataloguedKeyCosts(flows);
        if (chestClaimContext && !chestContents.isEmpty())
        {
            DeferredChestClaimLifecycle.Match chestClaim = deferredChestClaimLifecycle.matchContents(
                cataloguedKeyLosses, chestContents, sourceBackedActivity);
            if (chestClaim != null)
            {
                if (KeyChestCatalogue.isDeferredClaimKey(chestClaim.getKeyItemId()))
                {
                    deferredClaimMatched = true;
                    List<ProfitTransaction> updated = DeferredChestClaimLifecycle.closeClaim(
                        getLootKeySessions(), chestClaim.getKeyItemId(), chestClaim.getQuantity(), now);
                    if (!updated.isEmpty())
                    {
                        deferredClaimAuditUpdate = updated.get(updated.size() - 1);
                        deferredClaimSourceRunId = uniqueActiveSessionRunFor(updated);
                        deferredClaimRunResolved = deferredClaimSourceRunId != null;
                    }
                }
                if (!queuedLoot.isMatched())
                {
                    appliedContext = TrackingContext.LOOT;
                    appliedNote = chestClaim.getChestName();
                    expectedLoot = chestContents;
                    appliedEncounterId = null;
                }
            }
        }

        boolean deathTransferContext = appliedContext == TrackingContext.TRANSFER
            && appliedNote != null
            && appliedNote.startsWith("Death: items held");
        boolean canPartitionDeathReturns = !hardTransferEvidence || deathTransferContext;
        canPartitionDeathReturns = canPartitionDeathReturns
            && appliedContext != TrackingContext.MARKET
            && (appliedContext != TrackingContext.TRANSFER || deathTransferContext);
        ProfitTransaction deathReclaimTransaction = canPartitionDeathReturns
            ? settleDeathReclaim(
                flows, now, queuedLoot.getMatchedQuantities(), !ambiguousCoinLossDuringDeathWipe)
            : null;
        if (flows.isEmpty())
        {
            consumeContext();
            clearHardTransferEvidence();
            if (deathReclaimTransaction != null) return deathReclaimTransaction;
            if (keyAuditTransaction != null) return keyAuditTransaction;
            return deferredClaimAuditUpdate;
        }

        long gross = grossValue(flows);
        if (gross < Math.max(0, config.minimumTransactionValue())
            && !hasUnpricedFlow(flows))
        {
            // A low-value chest reward may be intentionally omitted from the
            // accounting ledger, but its settled claim still closes the deferred
            // key audit row above.
            consumeContext();
            clearHardTransferEvidence();
            if (deathReclaimTransaction != null) return deathReclaimTransaction;
            return keyAuditTransaction != null ? keyAuditTransaction : deferredClaimAuditUpdate;
        }

        ProfitTransaction neutralRestore = settleNeutralZoneRestore(
            flows, queuedLoot.getMatchedQuantities(), now);
        if (neutralRestore != null)
        {
            if (config.keepTransferAuditRows())
            {
                activeSession.addTransaction(neutralRestore, config.maxTransactionsPerSession());
            }
            if (flows.isEmpty())
            {
                consumeContext();
                clearHardTransferEvidence();
                return neutralRestore;
            }
            // Only the matched entry items are neutral. Classify residual flows using
            // their actual consumption/loot evidence rather than the old region latch.
            appliedContext = TrackingContext.GENERIC;
            appliedNote = "";
            appliedEncounterId = null;
            transferEvidence = false;
            hardTransferEvidence = false;
            softTransferEvidence = false;
            consumeContext();
            clearHardTransferEvidence();
        }

        // Partial source matches classify the whole remaining settle as loot only
        // when the transfer partition consumed the unmatched quantities. Without
        // that partition, an oversized same-id stack (or unrelated extra gain)
        // must follow generic gain classification rather than inherit the loot tag.
        if (queuedLoot.isMatched()
            && !matchesExpectedLoot(flows, queuedLoot.getMatchedQuantities()))
        {
            queuedLoot = LootMatch.NONE;
        }

        boolean destroyConsumption = consumptionIntent != null && consumptionIntent.destroy;
        // Match Drop before consume wipe — consumeMatched used to null dropIntent first.
        boolean decantShape = ActionEvidence.isDecant(flows);
        if (softTransferEvidence && decantShape)
        {
            // A mixed shape while the bank is open has no trustworthy player-action
            // source; preserve the accounting classification without a Decanted stamp.
            suppressActionEvidence = true;
        }
        boolean confirmedOwnDrop = matchesDropIntent(flows);
        boolean consumptionMatched = !confirmedOwnDrop
            && !decantShape
            && matchesConsumptionIntent(flows, hardTransferEvidence);
        // Only a verb whose intent actually matched these flows may name the action;
        // a stale or unmatched click never upgrades the wording.
        ActionKind matchedActionIntent = consumptionMatched && consumptionIntent != null
            ? consumptionIntent.actionKind : null;
        if (!consumptionMatched
            && !confirmedOwnDrop
            && !hardTransferEvidence
            && !decantShape
            && isDoseOrPartialConsumeDelta(flows))
        {
            // Dose leftovers are high-confidence even when the menu/chat intent expired
            // before the inventory stabilized (common with Drink + Pick coalescing).
            // Soft bank-open latch must not block this — only hard bank-container/menu
            // evidence (real deposit/withdraw) may.
            consumptionMatched = true;
        }
        if (consumptionMatched)
        {
            // A confirmed Eat/Bury/Drink/etc. action wins over stale transfer evidence,
            // but only once its matching inventory loss has stabilized.
            appliedContext = TrackingContext.GENERIC;
            appliedNote = "";
            appliedEncounterId = null;
            consumptionIntent = null;
            dropIntent = null;
        }

        if (confirmedOwnDrop)
        {
            appliedContext = TrackingContext.GENERIC;
            appliedNote = "";
            appliedEncounterId = null;
        }

        // Presentation stamps after note wipe — offerLossStacks reads the note.
        // Re-applied again after queued-loot note mutate so Dropped/Destroyed survive.
        if (confirmedOwnDrop)
        {
            appliedNote = "Dropped";
        }
        else if (consumptionMatched && destroyConsumption)
        {
            appliedNote = "Destroyed";
        }

        // Soft bank-open alone is a weak signal. Real deposits/withdrawals are one-sided;
        // Drink+Pick / Bury+Pick mixed windows must not become ownership-neutral TRANSFER
        // (that yields Used/lost ×0 and drops harvest rows when countUncertain is off).
        boolean treatAsTransfer = !consumptionMatched && !confirmedOwnDrop && (
            hardTransferEvidence
                || (softTransferEvidence && (hasOnlyCosts(flows) || hasOnlyGains(flows))));

        if (treatAsTransfer)
        {
            // Soft evidence while bank open (one-sided), or hard evidence that outlived UI
            // close, must settle as ownership-neutral TRANSFER (not Used/lost).
            appliedContext = TrackingContext.TRANSFER;
            if (appliedNote == null || appliedNote.isEmpty())
            {
                appliedNote = "Bank transfer";
            }
            appliedEncounterId = null;
        }
        else if (appliedContext == TrackingContext.TRANSFER && !treatAsTransfer)
        {
            // Stale soft TRANSFER (UI was open) must not reclassify pure inventory
            // consumption. Hard bank-container/menu evidence is required.
            appliedContext = TrackingContext.GENERIC;
            appliedNote = "";
            appliedEncounterId = null;
        }

        String activityName = activeSession.getActivityHint();
        if (queuedLoot.isMatched())
        {
            appliedContext = queuedLoot.getContext();
            // Preserve Dropped/Destroyed presentation stamps (Bugbot: loot note overwrite).
            if (!confirmedOwnDrop && !(consumptionMatched && destroyConsumption))
            {
                appliedNote = queuedLoot.getNote();
            }
            activityName = queuedLoot.getActivityName();
            appliedEncounterId = queuedLoot.getEncounterId();
        }
        else if ((appliedContext == TrackingContext.LOOT
            || appliedContext == TrackingContext.PK_LOOT)
            && !matchesExpectedLoot(flows, expectedLoot))
        {
            appliedContext = TrackingContext.GENERIC;
            appliedNote = "";
            appliedEncounterId = null;
        }
        else if (appliedContext == TrackingContext.LOOT)
        {
            activityName = activityFromNote(appliedNote);
        }
        else if (appliedContext == TrackingContext.PK_LOOT
            || appliedContext == TrackingContext.PK_DEATH)
        {
            activityName = "PKing";
        }

        // The offer ledger is the strongest evidence of a Grand Exchange movement: an item that just
        // went into a sell offer, coins that just went into a buy, or a collection of something the
        // slots reported bought or sold. It settles as MARKET whether or not a widget check fired.
        if (appliedContext != TrackingContext.MARKET && appliedContext != TrackingContext.TRANSFER
            && !hardTransferEvidence && matchesRecentGeMovement(flows, now))
        {
            appliedContext = TrackingContext.MARKET;
            appliedNote = "Grand Exchange";
        }
        TransactionType type = classifier.classify(appliedContext, flows);
        if (consumptionMatched
            && type != TransactionType.TRANSFER
            && type != TransactionType.TRADE)
        {
            // Dose leftovers / empty vials are mixed inventory deltas; confirmed
            // consume intent still books Used/lost rather than uncertain exclusion.
            type = TransactionType.CONSUMPTION;
        }
        else if (!treatAsTransfer
            && type == TransactionType.UNCERTAIN
            && !decantShape
            && isDoseOrPartialConsumeDelta(flows))
        {
            // Classifier still sees mixed value; dose shape alone is enough once soft
            // transfer has been refused above.
            type = TransactionType.CONSUMPTION;
            consumptionMatched = true;
        }
        else if (!treatAsTransfer
            && softTransferEvidence
            && type == TransactionType.UNCERTAIN
            && !decantShape
            && hasAnyCost(flows))
        {
            // Soft bank latch refused for a mixed Drink/Pick/Bury window. Book supplies
            // rather than dropping both costs and harvest as uncounted UNCERTAIN.
            type = TransactionType.CONSUMPTION;
            consumptionMatched = true;
        }
        // Drop settle is always CONSUMPTION so registerOwnDrop + pickup recovery work.
        if (confirmedOwnDrop
            && type != TransactionType.TRANSFER
            && type != TransactionType.TRADE)
        {
            type = TransactionType.CONSUMPTION;
        }
        OwnDropMatch ownDropRecovery = null;
        if (!queuedLoot.isMatched()
            && type != TransactionType.TRANSFER
            && type != TransactionType.TRADE
            && type != TransactionType.CONSUMPTION
            && type != TransactionType.PK_DEATH_LOSS
            && type != TransactionType.PK_SUPPLY_COST
            && type != TransactionType.PK_FEE
            && hasOnlyGains(flows))
        {
            ownDropRecovery = matchOwnDropRecovery(flows);
        }
        if (ownDropRecovery != null)
        {
            // Recovering your own ground items reverses the earlier loss — not new revenue.
            type = TransactionType.TRANSFER;
            appliedContext = TrackingContext.GENERIC;
            appliedNote = "Own-drop recovery";
            activityName = "Drop recovery";
            appliedEncounterId = null;
        }
        // Key-manifest evidence is provenance only. Neither an open widget nor a
        // manifest match can rewrite the independently classified transaction.
        // Clue dig/tele/key spends while clue path active.
        if (ownDropRecovery == null
            && type == TransactionType.CONSUMPTION
            && clueCostPairing.classifySpend(appliedNote) == ClueCostPairing.SupplyKind.CLUE_COST)
        {
            appliedNote = clueCostPairing.costNote();
            if (clueCostPairing.getActiveEncounterId() != null)
            {
                appliedEncounterId = clueCostPairing.getActiveEncounterId();
            }
        }
        // Final presentation stamp after all note mutations (except recovery note).
        if (ownDropRecovery == null && type == TransactionType.CONSUMPTION)
        {
            if (confirmedOwnDrop)
            {
                appliedNote = "Dropped";
            }
            else if (destroyConsumption)
            {
                appliedNote = "Destroyed";
            }
        }
        boolean observedGeMovement = false;
        if (geBookingMode == GeBookingMode.OBSERVED
            && ownDropRecovery == null
            && appliedContext == TrackingContext.MARKET
            && type == TransactionType.TRADE
            && (appliedNote == null || !appliedNote.toLowerCase(java.util.Locale.ROOT).contains("player trade")))
        {
            // Every GE coin and item movement is settled from the offer observations in this mode;
            // the inventory side (placing, collecting, the bank collection) is an ownership-neutral
            // transfer, never a second booking of the same trade.
            type = TransactionType.TRANSFER;
            observedGeMovement = true;
        }
        boolean counted = type != TransactionType.TRANSFER
            && (type != TransactionType.UNCERTAIN || config.countUncertainMixedChanges());
        boolean retainTransaction = counted || config.keepTransferAuditRows() || type == TransactionType.UNCERTAIN;
        // The committed inventory baseline already deduplicates receipts. One
        // encounter may legitimately have several pickups; an encounter-wide
        // revenue gate would silently discard every pickup after the first.
        trace("classify", type
            + " counted=" + counted
            + " ctx=" + appliedContext
            + " softTx=" + softTransferEvidence
            + " hardTx=" + hardTransferEvidence
            + " consume=" + consumptionMatched
            + " flows=" + flows.size());
        if (type == TransactionType.TRANSFER)
        {
            activityName = ownDropRecovery != null ? "Drop recovery" : observedGeMovement ? "Market" : "Transfer";
        }
        else if (type == TransactionType.TRADE)
        {
            activityName = "Market";
        }

        ClassificationConfidence confidence = ownDropRecovery != null
            ? ClassificationConfidence.CONFIRMED
            : confidenceFor(appliedContext, type);
        String explanation = ownDropRecovery != null
            ? "Recovered own ground drop; earlier loss reversed at original valuation."
            : explanationFor(appliedContext, type, counted);
        if (type == TransactionType.TRANSFER && ownDropRecovery == null
            && appliedNote != null && !appliedNote.isEmpty()
            && !"Bank transfer".equalsIgnoreCase(appliedNote)
            && !"Bank deposit".equalsIgnoreCase(appliedNote)
            && !"Bank/deposit transfer".equalsIgnoreCase(appliedNote))
        {
            // Non-bank transfers explain themselves (minigame wipe, neutral zone, death).
            explanation = "Ownership-neutral transfer: " + appliedNote + ".";
        }

        if (observedGeMovement)
        {
            explanation = "Grand Exchange movement; the trade itself is booked from the observed offer.";
        }
        // GE sell tax: capture offer item-loss, book tax on Collect without double-count.
        if (ownDropRecovery == null
            && appliedContext == TrackingContext.MARKET
            && type == TransactionType.TRADE)
        {
            for (GeSellTaxBooking.PendingSale sale : GeSellTaxBooking.capturePendingSales(
                flows, appliedNote, counted))
            {
                if (pendingGeSales.size() >= MAX_PENDING_GE_SALES)
                {
                    pendingGeSales.pollFirst();
                }
                pendingGeSales.addLast(sale);
            }
            if (config.applyGeSellTax())
            {
                List<GeSellTaxBooking.PendingSale> mutablePending =
                    new ArrayList<>(pendingGeSales);
                GeSellTaxBooking.Result taxResult = GeSellTaxBooking.applyOnCollect(
                    flows, appliedNote, true, mutablePending, now);
                pendingGeSales.clear();
                pendingGeSales.addAll(mutablePending);
                flows = taxResult.flows;
                if (!taxResult.explanationSuffix.isEmpty())
                {
                    explanation = explanation.isEmpty()
                        ? taxResult.explanationSuffix
                        : explanation + " " + taxResult.explanationSuffix + ".";
                }
            }
        }

        ProfitTransaction transaction = new ProfitTransaction(
            now,
            activeSession.getElapsedMillis(now),
            type,
            appliedContext,
            appliedNote,
            activityName,
            counted,
            flows,
            confidence,
            explanation,
            appliedEncounterId);
        if (localDeathSettlement && appliedDeathEvidence != null && hasNegativeFlow(deathEvidenceFlows))
        {
            // Evidence enriches the explanation only; the already-final flows,
            // classification, valuation and counted state remain untouched.
            transaction.rewriteExplanation(appliedDeathEvidence.appendTo(
                transaction.getExplanation(), deathEvidenceFlows));
        }
        if (ownDropRecovery == null
            && !confirmedOwnDrop
            && !destroyConsumption
            && !suppressActionEvidence)
        {
            // Presentation-only evidence; type/valuation/counted above are unchanged.
            transaction.setActionKind(ActionEvidence.resolve(
                matchedActionIntent, flows, type, activityName));
        }

        if (retainTransaction)
        {
            if (type != TransactionType.TRANSFER
                && type != TransactionType.TRADE
                && !hardTransferEvidence
                && !softTransferEvidence
                && !localDeathSettlement)
            {
                Map<Integer, Long> residualPositiveFlows = positiveFlowQuantitiesExcluding(
                    flows, queuedLootMatchedQuantities);
                lootKeyClaimReceipts = lootKeyLifecycle.recordClaim(
                    getLootKeySessions(), residualPositiveFlows, now);
            }
            for (LootKeyProvenance receipt : lootKeyClaimReceipts)
            {
                transaction.addLootKeyProvenance(receipt);
            }
            if (deferredClaimMatched)
            {
                if (deferredClaimRunResolved)
                {
                    transaction.setRunId(deferredClaimSourceRunId);
                }
                else
                {
                    // Chest contents are real receipts, but without a source key
                    // row in this owner their run cannot be guessed by timestamp.
                    transaction.markRunAssignmentPending();
                }
            }
            evaluateGoalAlerts(now, false);
            activeSession.addTransaction(transaction, config.maxTransactionsPerSession());
            observeNotableDrop(transaction, now);
            evaluateGoalAlerts(now, false);
            if (appliedEncounterId != null && !appliedEncounterId.isEmpty())
            {
                activeSession.attachTransactionToEncounter(
                    transaction.getId(),
                    appliedEncounterId,
                    false);
            }
        }

        if (ownDropRecovery != null)
        {
            applyOwnDropRecovery(ownDropRecovery, now);
        }
        else if (confirmedOwnDrop && type == TransactionType.CONSUMPTION && counted)
        {
            registerOwnDrop(transaction, flows);
        }

        if (confirmedOwnDrop)
        {
            dropIntent = null;
        }

        consumeContext();
        clearHardTransferEvidence();
        return transaction;
    }

    private void observePrimingSnapshot(ContainerSnapshot current)
    {
        primingObservedTicks++;
        if (primingSnapshot != null && primingSnapshot.equals(current))
        {
            primingStableTicks++;
        }
        else
        {
            primingSnapshot = current;
            primingStableTicks = 1;
        }

        int requiredStableTicks = Math.max(2, config.stabilizationTicks() + 1);
        int requiredObservedTicks = Math.max(MINIMUM_BASELINE_WARMUP_TICKS, requiredStableTicks);
        if (primingObservedTicks >= requiredObservedTicks
            && primingStableTicks >= requiredStableTicks)
        {
            baseline = current;
            baselinePriming = false;
            primingSnapshot = null;
            primingObservedTicks = 0;
            primingStableTicks = 0;
            clearPendingChange();
            consumeContext();
        }
    }

    private void captureActiveContextForPending()
    {
        // Late inventory after bank close: hard bank-container/menu evidence still applies
        // even when soft UI context ticks were cleared on close.
        if (hardTransferContextActive)
        {
            latchHardTransferIntoPending();
        }

        if (contextTicks <= 0)
        {
            return;
        }

        // Never attach TRANSFER to a new pending change from soft UI-open ticks alone.
        // Hard bank-container/menu evidence (or an already-latched hard pending) required.
        if (context == TrackingContext.TRANSFER
            && !pendingHardTransferEvidence
            && !hardTransferContextActive)
        {
            // Soft open context may exist while bankInterfaceOpen; do not latch it
            // into pending - noteSoftOpenTransferForPending handles settle evidence
            // only while the UI remains open, and close clears soft-only pending.
            return;
        }

        // Capture the active context once when a pending change begins. Repeated
        // snapshot observations must not merge the same expected loot again.
        // New loot events still merge through setActiveContext/applyPendingContext.
        if (pendingContext == TrackingContext.GENERIC
            || context.getPriority() > pendingContext.getPriority())
        {
            applyPendingContext(context, contextNote, contextExpectedLoot, contextEncounterId);
        }
    }

    private void applyPendingContext(
        TrackingContext newContext,
        String note,
        Map<Integer, Long> expectedLoot,
        String encounterId)
    {
        if (newContext == TrackingContext.TRANSFER && pendingHardTransferEvidence)
        {
            pendingTransferEvidence = true;
        }
        if (newContext.getPriority() > pendingContext.getPriority())
        {
            pendingContext = newContext;
            pendingContextNote = note == null ? "" : note;
            pendingExpectedLoot = positiveEntries(expectedLoot);
            pendingEncounterId = encounterId;
            return;
        }

        if (newContext == pendingContext)
        {
            if (note != null && !note.isEmpty())
            {
                pendingContextNote = note;
            }
            if (newContext == TrackingContext.LOOT || newContext == TrackingContext.PK_LOOT)
            {
                pendingExpectedLoot = mergeQuantities(pendingExpectedLoot, expectedLoot);
            }
            if (encounterId != null && !encounterId.isEmpty())
            {
                pendingEncounterId = encounterId;
            }
        }
    }

    private LootMatch consumeQueuedLoot(List<ItemFlow> flows)
    {
        if (lootExpectations.isEmpty())
        {
            return LootMatch.NONE;
        }

        boolean matched = false;
        String note = "";
        String activityName = "General";
        TrackingContext matchedContext = TrackingContext.GENERIC;
        String matchedEncounterId = null;
        Map<Integer, Long> matchedQuantities = new HashMap<>();
        Map<Integer, Long> availablePositiveQuantities = new HashMap<>();
        for (ItemFlow flow : flows)
        {
            if (flow.getQuantityDelta() > 0L)
            {
                availablePositiveQuantities.merge(
                    flow.getItemId(),
                    flow.getQuantityDelta(),
                    Long::sum);
            }
        }

        for (int index = 0; index < lootExpectations.size();)
        {
            LootExpectation expectation = lootExpectations.get(index);
            Map<Integer, Long> expectationMatches = expectation.consumeMatching(availablePositiveQuantities);
            if (!expectationMatches.isEmpty())
            {
                for (Map.Entry<Integer, Long> entry : expectationMatches.entrySet())
                {
                    matchedQuantities.merge(entry.getKey(), entry.getValue(), Long::sum);
                }
                if (!matched)
                {
                    matched = true;
                    note = expectation.getNote();
                    activityName = expectation.getActivityName();
                    matchedContext = expectation.getContext();
                    matchedEncounterId = expectation.getEncounterId();
                }
            }

            if (expectation.isComplete() || expectation.isExpired())
            {
                lootExpectations.remove(index);
            }
            else
            {
                index++;
            }
        }

        return matched
            ? new LootMatch(true, note, activityName, matchedContext, matchedEncounterId, matchedQuantities)
            : LootMatch.NONE;
    }

    private void advanceLootExpectations()
    {
        for (int index = lootExpectations.size() - 1; index >= 0; index--)
        {
            LootExpectation expectation = lootExpectations.get(index);
            expectation.tick();
            if (expectation.isExpired() || expectation.isComplete())
            {
                lootExpectations.remove(index);
            }
        }
    }

    private String activityFromNote(String note)
    {
        if (note == null || note.trim().isEmpty())
        {
            return activeSession == null ? "General" : activeSession.getActivityHint();
        }

        String trimmed = note.trim();
        String prefix = "Loot from ";
        return trimmed.startsWith(prefix) && trimmed.length() > prefix.length()
            ? trimmed.substring(prefix.length()).trim()
            : trimmed;
    }

    private boolean matchesExpectedLoot(List<ItemFlow> flows, Map<Integer, Long> expectedLoot)
    {
        Map<Integer, Long> positiveFlows = new HashMap<>();
        for (ItemFlow flow : flows)
        {
            if (flow.getQuantityDelta() > 0L)
            {
                positiveFlows.merge(
                    flow.getItemId(),
                    flow.getQuantityDelta(),
                    Long::sum);
            }
        }

        if (positiveFlows.isEmpty())
        {
            return false;
        }

        if (expectedLoot == null || expectedLoot.isEmpty())
        {
            return true;
        }

        Map<Integer, Long> positiveExpected = new HashMap<>();
        for (Map.Entry<Integer, Long> expected : expectedLoot.entrySet())
        {
            long expectedQuantity = expected.getValue() == null ? 0L : expected.getValue();
            if (expected.getKey() != null && expectedQuantity > 0L)
            {
                positiveExpected.put(expected.getKey(), expectedQuantity);
            }
        }
        return positiveFlows.equals(positiveExpected);
    }

    private ProfitTransaction recordLootKeyAuditRow(
        List<ItemFlow> keyFlows,
        @Nullable String encounterId,
        long now)
    {
        ProfitTransaction audit = new ProfitTransaction(
            now,
            activeSession.getElapsedMillis(now),
            TransactionType.TRANSFER,
            TrackingContext.TRANSFER,
            LootKeyLifecycle.keyReceivedNote(),
            "PKing",
            false,
            keyFlows,
            ClassificationConfidence.CONFIRMED,
            "Settled loot-key pickup retained as a non-counted audit row; key contents are valued only when they enter INV.",
            encounterId);
        for (ItemFlow flow : keyFlows)
        {
            if (flow != null && flow.getQuantityDelta() > 0L
                && LootKeyLifecycle.isLootKeyItem(flow.getItemId()))
            {
                audit.addLootKeyProvenance(LootKeyLifecycle.receivedEntry(
                    flow.getItemId(), flow.getQuantityDelta(), encounterId, getLootKeySessions(), now));
            }
        }
        activeSession.addTransaction(audit, config.maxTransactionsPerSession());
        if (encounterId != null && !encounterId.isEmpty())
        {
            activeSession.attachTransactionToEncounter(audit.getId(), encounterId, false);
        }
        return audit;
    }

    private ProfitTransaction recordDeferredClaimAuditRows(List<ItemFlow> keyFlows, long now)
    {
        ProfitTransaction last = null;
        for (ItemFlow flow : keyFlows)
        {
            if (flow == null || flow.getQuantityDelta() <= 0L
                || !KeyChestCatalogue.isDeferredClaimKey(flow.getItemId()))
            {
                continue;
            }
            KeyChestCatalogue.Entry entry = KeyChestCatalogue.entryForKey(flow.getItemId());
            if (entry == null || entry.isTradeable())
            {
                continue;
            }
            DeferredClaimProvenance provenance = DeferredChestClaimLifecycle.receivedEntry(
                flow.getItemId(), flow.getItemName(), entry.getActivityLabel(),
                flow.getQuantityDelta(), now);
            ProfitTransaction audit = new ProfitTransaction(
                now,
                activeSession.getElapsedMillis(now),
                TransactionType.TRANSFER,
                TrackingContext.TRANSFER,
                provenance.summary(),
                entry.getActivityLabel(),
                false,
                Collections.singletonList(flow),
                ClassificationConfidence.CONFIRMED,
                "Untradeable chest key held as a non-counted claim token; contents are valued only after a settled INV gain.",
                null);
            audit.setActionKind(ActionKind.DEFERRED_CLAIM);
            audit.setDeferredClaimProvenance(provenance);
            activeSession.addTransaction(audit, config.maxTransactionsPerSession());
            last = audit;
        }
        return last;
    }

    @Nullable
    private String uniqueActiveSessionRunFor(List<ProfitTransaction> sourceRows)
    {
        if (activeSession == null || sourceRows == null || sourceRows.isEmpty())
        {
            return null;
        }
        List<ProfitTransaction> owned = activeSession.getTransactions();
        String selected = null;
        for (ProfitTransaction source : sourceRows)
        {
            if (source == null || source.getRunId() == null || !owned.contains(source))
            {
                return null;
            }
            if (selected != null && !selected.equals(source.getRunId()))
            {
                return null;
            }
            selected = source.getRunId();
        }
        return selected;
    }

    private boolean hasCredibleDeferredClaimPickupEvidence(
        TrackingContext context,
        List<ItemFlow> originalFlows,
        Map<Integer, Long> expectedLoot,
        List<ItemFlow> keyFlows,
        boolean transferEvidence,
        boolean hardTransferEvidence,
        boolean softTransferEvidence)
    {
        if (context == null
            || (context != TrackingContext.GENERIC
                && context != TrackingContext.LOOT
                && context != TrackingContext.PK_LOOT)
            || transferEvidence
            || hardTransferEvidence
            || softTransferEvidence
            || dropIntent != null)
        {
            return false;
        }
        TransactionType evidenceType = classifier.classify(context, originalFlows);
        if (evidenceType != TransactionType.GAIN
            && evidenceType != TransactionType.LOOT
            && evidenceType != TransactionType.PK_LOOT)
        {
            return false;
        }
        Map<Integer, Long> keyQuantities = positiveFlowQuantities(keyFlows);
        if (context == TrackingContext.PK_LOOT || context == TrackingContext.LOOT)
        {
            return LootKeyProvenance.containsQuantities(expectedLoot, keyQuantities);
        }
        return evidenceType == TransactionType.GAIN;
    }

    private static List<ItemFlow> positiveLootKeyFlows(List<ItemFlow> flows)
    {
        List<ItemFlow> keys = new ArrayList<>();
        if (flows != null)
        {
            for (ItemFlow flow : flows)
            {
                if (flow != null && flow.getQuantityDelta() > 0L
                    && LootKeyLifecycle.isLootKeyItem(flow.getItemId()))
                {
                    keys.add(flow);
                }
            }
        }
        return keys;
    }

    private static List<ItemFlow> positiveDeferredClaimKeyFlows(List<ItemFlow> flows)
    {
        List<ItemFlow> keys = new ArrayList<>();
        if (flows != null)
        {
            for (ItemFlow flow : flows)
            {
                if (flow != null && flow.getQuantityDelta() > 0L
                    && KeyChestCatalogue.isDeferredClaimKey(flow.getItemId()))
                {
                    keys.add(flow);
                }
            }
        }
        return keys;
    }

    private static List<ItemFlow> withoutDeferredClaimKeyFlows(List<ItemFlow> flows)
    {
        if (flows == null || flows.isEmpty())
        {
            return flows == null ? Collections.emptyList() : flows;
        }
        List<ItemFlow> remaining = new ArrayList<>();
        for (ItemFlow flow : flows)
        {
            if (flow != null && !KeyChestCatalogue.isDeferredClaimKey(flow.getItemId()))
            {
                remaining.add(flow);
            }
        }
        return remaining;
    }

    private static Map<Integer, Long> withoutDeferredClaimKeys(Map<Integer, Long> quantities)
    {
        if (quantities == null || quantities.isEmpty())
        {
            return Collections.emptyMap();
        }
        Map<Integer, Long> filtered = new HashMap<>();
        for (Map.Entry<Integer, Long> item : quantities.entrySet())
        {
            if (item.getKey() != null && !KeyChestCatalogue.isDeferredClaimKey(item.getKey())
                && item.getValue() != null && item.getValue() > 0L)
            {
                filtered.put(item.getKey(), item.getValue());
            }
        }
        return filtered;
    }

    private static Map<Integer, Long> negativeCataloguedKeyQuantities(List<ItemFlow> flows)
    {
        Map<Integer, Long> negative = new HashMap<>();
        if (flows != null)
        {
            for (ItemFlow flow : flows)
            {
                if (flow != null && flow.getQuantityDelta() < 0L
                    && KeyChestCatalogue.isCataloguedKey(flow.getItemId()))
                {
                    long quantity = flow.getQuantityDelta() == Long.MIN_VALUE
                        ? Long.MAX_VALUE : -flow.getQuantityDelta();
                    negative.merge(flow.getItemId(), quantity, GpManagerEngine::saturatingAdd);
                }
            }
        }
        return negative;
    }

    private static Map<Integer, Long> negativeDeferredClaimKeyQuantities(List<ItemFlow> flows)
    {
        Map<Integer, Long> negative = new HashMap<>();
        if (flows != null)
        {
            for (ItemFlow flow : flows)
            {
                if (flow != null && flow.getQuantityDelta() < 0L
                    && KeyChestCatalogue.isDeferredClaimKey(flow.getItemId()))
                {
                    long quantity = flow.getQuantityDelta() == Long.MIN_VALUE
                        ? Long.MAX_VALUE : -flow.getQuantityDelta();
                    negative.merge(flow.getItemId(), quantity, GpManagerEngine::saturatingAdd);
                }
            }
        }
        return negative;
    }

    private static boolean hasOnlyGainContentsOrCataloguedKeyCosts(List<ItemFlow> flows)
    {
        if (flows == null || flows.isEmpty())
        {
            return false;
        }
        for (ItemFlow flow : flows)
        {
            if (flow == null || flow.getQuantityDelta() == 0L)
            {
                continue;
            }
            if (flow.getQuantityDelta() < 0L)
            {
                if (!KeyChestCatalogue.isCataloguedKey(flow.getItemId()))
                {
                    return false;
                }
            }
            else if (KeyChestCatalogue.isCataloguedKey(flow.getItemId()))
            {
                return false;
            }
        }
        return true;
    }

    private static List<ItemFlow> withoutPositiveLootKeyFlows(List<ItemFlow> flows)
    {
        List<ItemFlow> remaining = new ArrayList<>();
        if (flows != null)
        {
            for (ItemFlow flow : flows)
            {
                if (flow == null || flow.getQuantityDelta() <= 0L
                    || !LootKeyLifecycle.isLootKeyItem(flow.getItemId()))
                {
                    remaining.add(flow);
                }
            }
        }
        return remaining;
    }

    private boolean hasCredibleLootKeyPickupEvidence(
        TrackingContext context,
        List<ItemFlow> originalFlows,
        Map<Integer, Long> expectedLoot,
        List<ItemFlow> keyFlows,
        boolean transferEvidence,
        boolean hardTransferEvidence,
        boolean softTransferEvidence)
    {
        if (context == null
            || context == TrackingContext.TRANSFER
            || context == TrackingContext.MARKET
            || transferEvidence
            || hardTransferEvidence
            || softTransferEvidence
            || dropIntent != null)
        {
            return false;
        }
        TransactionType evidenceType = classifier.classify(context, originalFlows);
        if (evidenceType != TransactionType.GAIN
            && evidenceType != TransactionType.LOOT
            && evidenceType != TransactionType.PK_LOOT)
        {
            return false;
        }
        Map<Integer, Long> keyQuantities = positiveFlowQuantities(keyFlows);
        if (context == TrackingContext.PK_LOOT || context == TrackingContext.LOOT)
        {
            // The observed source callback must account for the actual key quantity;
            // a stale loot context or key-shaped bank flow cannot create provenance.
            return LootKeyProvenance.containsQuantities(expectedLoot, keyQuantities);
        }
        // A source-less but settled ordinary gain is still a pickup; transfer-like
        // evidence and production/uncertain mixes are rejected above.
        return context == TrackingContext.GENERIC && evidenceType == TransactionType.GAIN;
    }

    private static Map<Integer, Long> withoutLootKeyItems(Map<Integer, Long> quantities)
    {
        if (quantities == null || quantities.isEmpty())
        {
            return Collections.emptyMap();
        }
        Map<Integer, Long> filtered = new HashMap<>();
        for (Map.Entry<Integer, Long> item : quantities.entrySet())
        {
            if (item.getKey() != null && !LootKeyLifecycle.isLootKeyItem(item.getKey())
                && item.getValue() != null && item.getValue() > 0L)
            {
                filtered.put(item.getKey(), item.getValue());
            }
        }
        return filtered;
    }

    private static Map<Integer, Long> positiveFlowQuantities(List<ItemFlow> flows)
    {
        Map<Integer, Long> positive = new HashMap<>();
        if (flows != null)
        {
            for (ItemFlow flow : flows)
            {
                if (flow != null && flow.getQuantityDelta() > 0L)
                {
                    positive.merge(flow.getItemId(), flow.getQuantityDelta(), GpManagerEngine::saturatingAdd);
                }
            }
        }
        return positive;
    }

    private static Map<Integer, Long> positiveFlowQuantitiesExcluding(
        List<ItemFlow> flows,
        Map<Integer, Long> excludedQuantities)
    {
        Map<Integer, Long> remaining = positiveFlowQuantities(flows);
        if (excludedQuantities == null || excludedQuantities.isEmpty())
        {
            return remaining;
        }
        for (Map.Entry<Integer, Long> excluded : excludedQuantities.entrySet())
        {
            if (excluded.getKey() == null || excluded.getValue() == null || excluded.getValue() <= 0L)
            {
                continue;
            }
            long available = remaining.getOrDefault(excluded.getKey(), 0L);
            long residual = Math.max(0L, available - Math.min(available, excluded.getValue()));
            if (residual == 0L)
            {
                remaining.remove(excluded.getKey());
            }
            else
            {
                remaining.put(excluded.getKey(), residual);
            }
        }
        return remaining;
    }

    private static Map<Integer, Long> negativeLootKeyQuantities(List<ItemFlow> flows)
    {
        Map<Integer, Long> negative = new HashMap<>();
        if (flows != null)
        {
            for (ItemFlow flow : flows)
            {
                if (flow != null && flow.getQuantityDelta() < 0L
                    && LootKeyLifecycle.isLootKeyItem(flow.getItemId()))
                {
                    long delta = flow.getQuantityDelta();
                    negative.merge(flow.getItemId(), delta == Long.MIN_VALUE ? Long.MAX_VALUE : -delta,
                        GpManagerEngine::saturatingAdd);
                }
            }
        }
        return negative;
    }

    /** Remove all deferred key-token losses from GP accounting; callers retain deltas for provenance. */
    private static List<ItemFlow> withoutDeferredLootKeyTokenLosses(
        List<ItemFlow> flows,
        Map<Integer, Long> deferredLosses)
    {
        if (flows == null || flows.isEmpty() || deferredLosses == null || deferredLosses.isEmpty())
        {
            return flows == null ? Collections.emptyList() : flows;
        }
        Map<Integer, Long> remainingToRemove = new HashMap<>(deferredLosses);
        List<ItemFlow> result = new ArrayList<>(flows.size());
        for (ItemFlow flow : flows)
        {
            if (flow == null || flow.getQuantityDelta() >= 0L
                || !LootKeyLifecycle.isLootKeyItem(flow.getItemId()))
            {
                result.add(flow);
                continue;
            }
            long quantity = Math.abs(flow.getQuantityDelta());
            long toRemove = Math.min(quantity, remainingToRemove.getOrDefault(flow.getItemId(), 0L));
            if (toRemove <= 0L)
            {
                result.add(flow);
                continue;
            }
            remainingToRemove.put(flow.getItemId(), remainingToRemove.get(flow.getItemId()) - toRemove);
            long remainingQuantity = quantity - toRemove;
            if (remainingQuantity > 0L)
            {
                long remainingValue = remainingValue(flow, remainingQuantity, quantity);
                result.add(new ItemFlow(flow.getItemId(), flow.getItemName(), -remainingQuantity,
                    flow.getUnitPrice(), remainingValue, flow.getPriceSource(), flow.getPriceCapturedAtEpochMillis()));
            }
        }
        return result;
    }

    private static long remainingValue(ItemFlow flow, long remainingQuantity, long originalQuantity)
    {
        if (flow.getUnitPrice() > 0)
        {
            try
            {
                return -Math.multiplyExact((long) flow.getUnitPrice(), remainingQuantity);
            }
            catch (ArithmeticException ignored)
            {
                return Long.MIN_VALUE;
            }
        }
        if (originalQuantity <= 0L)
        {
            return 0L;
        }
        return flow.getValueDelta() / originalQuantity * remainingQuantity;
    }

    private Map<Integer, Long> positiveEntries(Map<Integer, Long> values)
    {
        if (values == null || values.isEmpty())
        {
            return Collections.emptyMap();
        }

        Map<Integer, Long> result = new HashMap<>();
        for (Map.Entry<Integer, Long> entry : values.entrySet())
        {
            if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0L)
            {
                result.merge(entry.getKey(), entry.getValue(), Long::sum);
            }
        }
        return result.isEmpty() ? Collections.emptyMap() : result;
    }

    private Map<Integer, Long> mergeQuantities(
        Map<Integer, Long> left,
        Map<Integer, Long> right)
    {
        Map<Integer, Long> result = new HashMap<>();
        if (left != null)
        {
            result.putAll(left);
        }
        if (right != null)
        {
            for (Map.Entry<Integer, Long> entry : right.entrySet())
            {
                if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0L)
                {
                    result.merge(entry.getKey(), entry.getValue(), Long::sum);
                }
            }
        }
        return result.isEmpty() ? Collections.emptyMap() : result;
    }

    private void resetTrackingState(boolean primeBaseline)
    {
        chargeLoadTransferEvidence.clear();
        measuredChargeReadTracker.reset();
        baseline = null;
        baselinePriming = primeBaseline;
        primingSnapshot = null;
        primingObservedTicks = 0;
        primingStableTicks = 0;
        clearPendingChange();
        consumeContext();
        clearHardTransferEvidence();
        neutralZoneStoredItems.clear();
        neutralZoneTransferActive = false;
        captureNeutralZoneEntry = false;
        neutralZoneEntryBaseline = null;
        neutralZoneEntryCaptureTicks = 0;
        neutralZoneRestoreTicks = 0;
        lootExpectations.clear();
        consumptionIntent = null;
        pendingGeSales.clear();
        clueCostPairing.clear();
        lootKeyLifecycle.clearEphemeralState();
        deferredChestClaimLifecycle.cancel();
    }

    private void clearPendingChange()
    {
        dirty = false;
        pendingSnapshot = null;
        pendingStableTicks = 0;
        pendingContext = TrackingContext.GENERIC;
        pendingContextNote = "";
        pendingExpectedLoot = Collections.emptyMap();
        pendingEncounterId = null;
        pendingLocalDeathEvidence = null;
        pendingTransferEvidence = false;
        pendingHardTransferEvidence = false;
    }

    private boolean matchesConsumptionIntent(List<ItemFlow> flows, boolean hardTransferEvidence)
    {
        if (consumptionIntent == null || flows == null || flows.isEmpty())
        {
            return false;
        }
        // Match the spent stack even when a dose leftover / empty vial appears as a gain.
        if (consumptionIntent.itemId >= 0)
        {
            for (ItemFlow flow : flows)
            {
                if (flow != null
                    && flow.getItemId() == consumptionIntent.itemId
                    && flow.getQuantityDelta() < 0L)
                {
                    return true;
                }
            }
        }
        // Open intent (menu item id missing) or id mismatch after canonicalize: still
        // confirm Drink/Eat when the delta is a dose/partial leftover, or a pure cost
        // (Bury/Cast runes/ammo) so hard TRANSFER cannot swallow the spend.
        if (isDoseOrPartialConsumeDelta(flows))
        {
            return true;
        }
        if (hasOnlyCosts(flows))
        {
            // Named intent for a different item + hard bank deposit must stay TRANSFER
            // (stale bury of bones must not book oak-log deposit as Used/lost).
            if (hardTransferEvidence && consumptionIntent.itemId >= 0)
            {
                return false;
            }
            return true;
        }
        // Live potato-field sequence: Drink + Pick + Bury often share one stabilization
        // window. Any armed consume intent (named or open) must win so Used/lost books
        // instead of UNCERTAIN exclusion — wrong/stale named ids previously skipped
        // this path because it required itemId < 0 only.
        // Hard bank-container/menu evidence: do not let mixed any-cost swallow a deposit.
        if (hardTransferEvidence)
        {
            return false;
        }
        return hasAnyCost(flows);
    }

    /**
     * Pickup then bury (etc.) in one stabilize window nets to zero vs baseline.
     * Named consume intent stores invent qty at arm — synthesize the shortfall loss.
     */
    private Map<Integer, Long> sameTickConsumeDeltas(ContainerSnapshot current)
    {
        if (consumptionIntent == null
            || consumptionIntent.itemId < 0
            || consumptionIntent.quantityAtArm < 0L
            || current == null)
        {
            return Collections.emptyMap();
        }
        long nowQty = current.quantityOf(consumptionIntent.itemId);
        long lost = consumptionIntent.quantityAtArm - nowQty;
        if (lost <= 0L)
        {
            return Collections.emptyMap();
        }
        Map<Integer, Long> synthetic = new HashMap<>();
        synthetic.put(consumptionIntent.itemId, -lost);
        return synthetic;
    }

    /**
     * Potion/food dose steps: a higher dose leaves and a lesser dose / empty vessel appears.
     * Extra pure gains in the same window (e.g. harvested potatoes) are ignored so a live
     * coalesced Drink+Pick delta still confirms. Inspired by Supplies Tracker consumable
     * slot confirmation (BSD-2-Clause patterns; GP Manager books net inventory value).
     */
    public static boolean isDoseOrPartialConsumeDelta(List<ItemFlow> flows)
    {
        if (flows == null || flows.size() < 2)
        {
            return false;
        }
        for (ItemFlow loss : flows)
        {
            if (loss == null || loss.getQuantityDelta() >= 0L)
            {
                continue;
            }
            for (ItemFlow gain : flows)
            {
                if (gain == null || gain.getQuantityDelta() <= 0L)
                {
                    continue;
                }
                if (Math.abs(loss.getQuantityDelta()) != gain.getQuantityDelta())
                {
                    continue;
                }
                if (looksLikeDoseOrVesselStep(loss.getItemName(), gain.getItemName()))
                {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasAnyCost(List<ItemFlow> flows)
    {
        if (flows == null || flows.isEmpty())
        {
            return false;
        }
        for (ItemFlow flow : flows)
        {
            if (flow != null && flow.getQuantityDelta() < 0L)
            {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeDoseOrVesselStep(String lostName, String gainedName)
    {
        String lost = lostName == null ? "" : lostName.trim().toLowerCase();
        String gained = gainedName == null ? "" : gainedName.trim().toLowerCase();
        if (lost.isEmpty() || gained.isEmpty())
        {
            return false;
        }
        if (gained.equals("vial")
            || gained.equals("empty vial")
            || gained.startsWith("empty ")
            || gained.equals("jug")
            || gained.equals("empty jug")
            || gained.equals("beer glass")
            || gained.equals("empty cup"))
        {
            return true;
        }
        int lostDose = trailingDose(lost);
        int gainedDose = trailingDose(gained);
        if (lostDose > 0 && gainedDose >= 0 && lostDose == gainedDose + 1)
        {
            String lostBase = stripTrailingDose(lost);
            String gainedBase = stripTrailingDose(gained);
            return !lostBase.isEmpty() && lostBase.equals(gainedBase);
        }
        // Pizza / pie / cake halves share a stem and are not (N) dose coded the same way.
        if ((lost.contains("pizza") || lost.contains(" pie") || lost.contains("cake")
            || lost.contains("pie"))
            && (gained.contains("pizza") || gained.contains(" pie") || gained.contains("cake")
            || gained.contains("slice") || gained.contains("1/2") || gained.contains("half")))
        {
            return true;
        }
        return false;
    }

    private static int trailingDose(String name)
    {
        java.util.regex.Matcher matcher = DOSE_SUFFIX.matcher(name);
        if (!matcher.find())
        {
            return -1;
        }
        try
        {
            return Integer.parseInt(matcher.group(1));
        }
        catch (NumberFormatException ex)
        {
            return -1;
        }
    }

    private static String stripTrailingDose(String name)
    {
        return DOSE_SUFFIX.matcher(name).replaceFirst("").trim();
    }

    private static final java.util.regex.Pattern DOSE_SUFFIX =
        java.util.regex.Pattern.compile("\\((\\d)\\)\\s*$");

    private boolean matchesDropIntent(List<ItemFlow> flows)
    {
        if (dropIntent == null || flows == null || flows.isEmpty() || consumptionIntent != null)
        {
            return false;
        }
        boolean sawCost = false;
        for (ItemFlow flow : flows)
        {
            if (flow == null)
            {
                return false;
            }
            if (flow.getQuantityDelta() > 0L)
            {
                return false;
            }
            if (flow.getQuantityDelta() < 0L)
            {
                if (flow.getItemId() != dropIntent.itemId)
                {
                    return false;
                }
                sawCost = true;
            }
        }
        return sawCost;
    }

    private static boolean hasOnlyGains(List<ItemFlow> flows)
    {
        if (flows == null || flows.isEmpty())
        {
            return false;
        }
        boolean sawGain = false;
        for (ItemFlow flow : flows)
        {
            if (flow == null || flow.getQuantityDelta() < 0L)
            {
                return false;
            }
            if (flow.getQuantityDelta() > 0L)
            {
                sawGain = true;
            }
        }
        return sawGain;
    }

    private static boolean hasOnlyCosts(List<ItemFlow> flows)
    {
        if (flows == null || flows.isEmpty())
        {
            return false;
        }
        boolean sawCost = false;
        for (ItemFlow flow : flows)
        {
            if (flow == null || flow.getQuantityDelta() > 0L)
            {
                return false;
            }
            if (flow.getQuantityDelta() < 0L)
            {
                sawCost = true;
            }
        }
        return sawCost;
    }

    private void registerOwnDrop(ProfitTransaction transaction, List<ItemFlow> flows)
    {
        if (transaction == null || flows == null)
        {
            return;
        }
        for (ItemFlow flow : flows)
        {
            if (flow == null || flow.getQuantityDelta() >= 0L)
            {
                continue;
            }
            long qty = Math.abs(flow.getQuantityDelta());
            if (qty <= 0L)
            {
                continue;
            }
            int unit = flow.getUnitPrice();
            if (unit == 0 && qty > 0L)
            {
                unit = (int) Math.min(Integer.MAX_VALUE, Math.abs(flow.getValueDelta()) / qty);
            }
            OwnDropRecord record = new OwnDropRecord(
                transaction.getId(),
                flow.getItemId(),
                qty,
                unit,
                dropIntent != null && dropIntent.hasLocation,
                dropIntent == null ? 0 : dropIntent.worldX,
                dropIntent == null ? 0 : dropIntent.worldY,
                dropIntent == null ? 0 : dropIntent.worldPlane,
                OWN_DROP_TTL_TICKS);
            ownDrops.addLast(record);
            while (ownDrops.size() > MAX_OWN_DROPS)
            {
                ownDrops.removeFirst();
            }
        }
    }

    private OwnDropMatch matchOwnDropRecovery(List<ItemFlow> flows)
    {
        if (ownDrops.isEmpty() || flows == null)
        {
            return null;
        }
        // Conservative: single-item pickups only. Mixed stacks are ambiguous.
        ItemFlow gain = null;
        for (ItemFlow flow : flows)
        {
            if (flow == null || flow.getQuantityDelta() <= 0L)
            {
                continue;
            }
            if (gain != null)
            {
                return null;
            }
            gain = flow;
        }
        if (gain == null)
        {
            return null;
        }
        OwnDropRecord best = null;
        int bestDistance = Integer.MAX_VALUE;
        boolean ambiguousUnlocated = false;
        for (OwnDropRecord record : ownDrops)
        {
            if (record == null || record.remainingQuantity <= 0L || record.itemId != gain.getItemId())
            {
                continue;
            }
            if (record.hasLocation)
            {
                if (!playerLocationKnown || playerWorldPlane != record.worldPlane)
                {
                    continue;
                }
                int distance = Math.max(
                    Math.abs(playerWorldX - record.worldX),
                    Math.abs(playerWorldY - record.worldY));
                if (distance > OWN_DROP_MATCH_RADIUS)
                {
                    continue;
                }
                boolean exactQty = gain.getQuantityDelta() == record.remainingQuantity;
                boolean bestExact = best != null && gain.getQuantityDelta() == best.remainingQuantity;
                if (best == null
                    || distance < bestDistance
                    || (distance == bestDistance && exactQty && !bestExact))
                {
                    best = record;
                    bestDistance = distance;
                }
            }
            else
            {
                // Unlocated dumps (no tile at Drop click) must still recover once the
                // player location is known — previously only matched when location was
                // unknown, so live pickups never reversed those losses.
                if (best != null && best.hasLocation)
                {
                    continue;
                }
                if (best != null && !best.hasLocation)
                {
                    ambiguousUnlocated = true;
                    continue;
                }
                best = record;
                bestDistance = 0;
            }
        }
        if (ambiguousUnlocated || best == null)
        {
            return null;
        }
        long recoverQty = Math.min(gain.getQuantityDelta(), best.remainingQuantity);
        if (recoverQty <= 0L)
        {
            return null;
        }
        return new OwnDropMatch(best, recoverQty);
    }

    private void applyOwnDropRecovery(OwnDropMatch match, long now)
    {
        if (match == null || match.record == null || activeSession == null)
        {
            return;
        }
        OwnDropRecord record = match.record;
        boolean recovered = activeSession.recoverOwnDropCosts(
            record.transactionId,
            record.itemId,
            match.quantity,
            now,
            "Own-drop recovery");
        if (!recovered)
        {
            return;
        }
        record.remainingQuantity -= match.quantity;
        if (record.remainingQuantity <= 0L)
        {
            ownDrops.remove(record);
        }
    }

    private void expireOwnDrops()
    {
        if (ownDrops.isEmpty())
        {
            return;
        }
        ownDrops.removeIf(record -> record == null || record.tick());
    }

    private void clearOwnDropState()
    {
        dropIntent = null;
        ownDrops.clear();
        playerLocationKnown = false;
        playerLocationLabel = null;
    }

    private void consumeContext()
    {
        context = TrackingContext.GENERIC;
        contextTicks = 0;
        contextNote = "";
        contextExpectedLoot = Collections.emptyMap();
        contextEncounterId = null;
        transferEvidenceTicks = 0;
        // hardTransferContextActive survives until settle or hardTransferEvidenceTicks expiry
        // so bank-container evidence is not lost on the idle GameTick before inventory dirty.
    }

    private void clearHardTransferEvidence()
    {
        hardTransferContextActive = false;
        hardTransferEvidenceTicks = 0;
    }

    private ClassificationConfidence confidenceFor(
        TrackingContext appliedContext,
        TransactionType type)
    {
        if (appliedContext == TrackingContext.TRANSFER
            || appliedContext == TrackingContext.MARKET
            || appliedContext == TrackingContext.LOOT
            || appliedContext == TrackingContext.PK_LOOT
            || appliedContext == TrackingContext.PK_DEATH)
        {
            return ClassificationConfidence.CONFIRMED;
        }
        if (type == TransactionType.UNCERTAIN)
        {
            return ClassificationConfidence.UNCERTAIN;
        }
        return ClassificationConfidence.LIKELY;
    }

    private static boolean hasNegativeFlow(List<ItemFlow> flows)
    {
        if (flows == null)
        {
            return false;
        }
        for (ItemFlow flow : flows)
        {
            if (flow != null && flow.getQuantityDelta() < 0L)
            {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNegativeFlowForItem(List<ItemFlow> flows, int itemId)
    {
        if (flows == null)
        {
            return false;
        }
        for (ItemFlow flow : flows)
        {
            if (flow != null && flow.getItemId() == itemId && flow.getQuantityDelta() < 0L)
            {
                return true;
            }
        }
        return false;
    }

    /** Loss ids claimed by exact consume/drop intent are excluded from the death wipe. */
    private Map<Integer, Long> confirmedActionLossQuantities(
        List<ItemFlow> flows,
        boolean hardTransferEvidence)
    {
        Map<Integer, Long> excluded = new HashMap<>();
        if (flows == null || flows.isEmpty())
        {
            return excluded;
        }

        if (consumptionIntent != null)
        {
            if (consumptionIntent.itemId >= 0)
            {
                addNegativeFlowQuantitiesForItem(excluded, flows, consumptionIntent.itemId);
            }
            else if (!addDoseStepLossQuantities(excluded, flows)
                && matchesConsumptionIntent(flows, hardTransferEvidence))
            {
                // An open-id intent with only costs cannot distinguish which item was
                // consumed; keep every negative flow in this ambiguous batch counted.
                addAllNegativeFlowQuantities(excluded, flows);
            }
        }
        if (dropIntent != null && consumptionIntent == null)
        {
            addNegativeFlowQuantitiesForItem(excluded, flows, dropIntent.itemId);
        }
        return excluded;
    }

    private static void addNegativeFlowQuantitiesForItem(
        Map<Integer, Long> target,
        List<ItemFlow> flows,
        int itemId)
    {
        for (ItemFlow flow : flows)
        {
            if (flow != null && flow.getItemId() == itemId && flow.getQuantityDelta() < 0L
                && flow.getQuantityDelta() != Long.MIN_VALUE)
            {
                target.put(itemId, -flow.getQuantityDelta());
            }
        }
    }

    private static void addAllNegativeFlowQuantities(Map<Integer, Long> target, List<ItemFlow> flows)
    {
        for (ItemFlow flow : flows)
        {
            if (flow != null && flow.getQuantityDelta() < 0L && flow.getQuantityDelta() != Long.MIN_VALUE)
            {
                target.put(flow.getItemId(), -flow.getQuantityDelta());
            }
        }
    }

    private static boolean addDoseStepLossQuantities(Map<Integer, Long> target, List<ItemFlow> flows)
    {
        boolean matched = false;
        for (ItemFlow loss : flows)
        {
            if (loss == null || loss.getQuantityDelta() >= 0L || loss.getQuantityDelta() == Long.MIN_VALUE)
            {
                continue;
            }
            for (ItemFlow gain : flows)
            {
                if (gain != null && gain.getQuantityDelta() > 0L
                    && -loss.getQuantityDelta() == gain.getQuantityDelta()
                    && looksLikeDoseOrVesselStep(loss.getItemName(), gain.getItemName()))
                {
                    target.put(loss.getItemId(), -loss.getQuantityDelta());
                    matched = true;
                    break;
                }
            }
        }
        return matched;
    }

    private static List<ItemFlow> withoutExcludedItemLosses(
        List<ItemFlow> flows,
        Map<Integer, Long> excluded)
    {
        if (flows == null || flows.isEmpty() || excluded == null || excluded.isEmpty())
        {
            return flows == null ? Collections.emptyList() : new ArrayList<>(flows);
        }
        List<ItemFlow> result = new ArrayList<>();
        for (ItemFlow flow : flows)
        {
            if (flow != null && flow.getQuantityDelta() < 0L
                && excluded.containsKey(flow.getItemId()))
            {
                continue;
            }
            result.add(flow);
        }
        return result;
    }

    private String explanationFor(
        TrackingContext appliedContext,
        TransactionType type,
        boolean counted)
    {
        switch (appliedContext)
        {
            case TRANSFER:
                return "Excluded because a bank or deposit interface was active.";
                case MARKET:
                return counted
                    ? "Grand Exchange / shop inventory change observed as TRADE (inventory deltas; GE sell tax applied when enabled)."
                    : "Grand Exchange / shop inventory change observed but not counted.";
            case LOOT:
                return "Matched item IDs and quantities to a RuneLite NPC-loot event.";
            case PK_LOOT:
                return "Matched item IDs and quantities to a RuneLite player-loot event.";
            case PK_DEATH:
                return "Inventory/equipment value fell after a confirmed player-combat death.";
            case PRODUCTION:
                return "Simultaneous inputs and outputs were observed during a production context.";
            case GENERIC:
            default:
                if (type == TransactionType.UNCERTAIN)
                {
                    return counted
                        ? "Mixed gains and costs had no confirmed context; counted by configuration."
                        : "Mixed gains and costs had no confirmed context and were excluded pending review.";
                }
                if (type == TransactionType.GAIN)
                {
                    return "Stable inventory/equipment value increased without a stronger context.";
                }
                if (type == TransactionType.CONSUMPTION)
                {
                    return "Stable inventory/equipment value decreased without a stronger context.";
                }
                return "Stable inventory/equipment change was observed.";
        }
    }

    private long grossValue(List<ItemFlow> flows)
    {
        long total = 0L;
        for (ItemFlow flow : flows)
        {
            long value = flow.getValueDelta() == Long.MIN_VALUE
                ? Long.MAX_VALUE
                : Math.abs(flow.getValueDelta());
            try
            {
                total = Math.addExact(total, value);
            }
            catch (ArithmeticException ex)
            {
                return Long.MAX_VALUE;
            }
        }
        return total;
    }

    private void recordChargeLoadTransfer(
        ChargeLoadTransferEvidence.Partition partition,
        long now)
    {
        if (!config.keepTransferAuditRows()
            || partition == null
            || !partition.hasTransfer()
            || activeSession == null
            || activeSession.isPaused())
        {
            return;
        }
        String itemName = partition.variant == null
            ? "Charged item"
            : partition.variant.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        ProfitTransaction transfer = new ProfitTransaction(
            now,
            activeSession.getElapsedMillis(now),
            TransactionType.TRANSFER,
            TrackingContext.TRANSFER,
            "Charge load transfer",
            itemName,
            false,
            partition.transferred,
            ClassificationConfidence.CONFIRMED,
            "Measured component loss matched a Use-on-item charge load; later measured Check differences own charge-use costs.",
            null);
        activeSession.addTransaction(transfer, config.maxTransactionsPerSession());
    }

    private ProfitTransaction recordAmbiguousChargeLoadReview(
        ChargeLoadTransferEvidence.Partition partition,
        long now)
    {
        if (partition == null || partition.ambiguousCandidates.isEmpty()
            || activeSession == null || activeSession.isPaused())
        {
            return null;
        }
        ProfitTransaction review = new ProfitTransaction(
            now,
            activeSession.getElapsedMillis(now),
            TransactionType.UNCERTAIN,
            TrackingContext.GENERIC,
            ChargeLoadTransferEvidence.AMBIGUOUS_QUANTITY_NOTE,
            "Charge load",
            false,
            partition.ambiguousCandidates,
            ClassificationConfidence.UNCERTAIN,
            "The component loss is held uncounted until a same-target measured Check confirms the load or you decide how to classify it.",
            null);
        review.setActionKind(ActionKind.CHARGE_LOAD_AMBIGUOUS);
        review.setChargeLoadReviewProvenance(new ChargeLoadReviewProvenance(
            partition.variant == null ? "" : partition.variant.name(),
            partition.selectedItemId,
            partition.targetIdentity,
            now));
        activeSession.addTransaction(review, config.maxTransactionsPerSession());
        return review;
    }

    private void reconcileChargeLoadReviews(
        MeasuredChargeRead.Variant variant,
        String targetIdentity,
        Map<Integer, Long> exactLoadQuantities,
        long previousReadAt,
        long now)
    {
        if (variant == null || targetIdentity == null || targetIdentity.trim().isEmpty()
            || exactLoadQuantities == null || exactLoadQuantities.isEmpty()
            || activeSession == null || activeSession.isPaused())
        {
            return;
        }

        long windowMillis = Math.max(1, Math.min(60, config.chargeLoadReviewMinutes())) * 60_000L;
        List<ProfitTransaction> matches = new ArrayList<>();
        String expectedVariant = variant.name();
        for (ProfitTransaction transaction : activeSession.getTransactions())
        {
            if (transaction == null
                || transaction.getAutomaticType() != TransactionType.UNCERTAIN
                || transaction.getCorrection() != TransactionCorrection.AUTO)
            {
                continue;
            }
            ChargeLoadReviewProvenance provenance = transaction.getChargeLoadReviewProvenance();
            if (provenance == null
                || !expectedVariant.equalsIgnoreCase(provenance.getVariantWireName())
                || !targetIdentity.equals(provenance.getMatchedTargetIdentity()))
            {
                continue;
            }
            long observedAt = provenance.getObservedAtEpochMillis();
            if (observedAt <= now
                && now - observedAt <= windowMillis
                && previousReadAt > 0L
                && previousReadAt <= observedAt
                && observedAt - previousReadAt <= windowMillis)
            {
                matches.add(transaction);
            }
        }

        // Multiple matching losses have ambiguous source attribution. Leave all
        // of them in Review instead of assigning one measured load arbitrarily.
        if (matches.size() != 1)
        {
            return;
        }

        ProfitTransaction review = matches.get(0);
        if (hasDuplicateChargeComponentIds(review.getFlows()))
        {
            return;
        }
        ChargeLoadReviewSplit split = splitChargeLoadReview(review.getFlows(), exactLoadQuantities);
        if (split.confirmed.isEmpty())
        {
            return;
        }

        if (split.remaining.isEmpty())
        {
            review.resolveChargeLoadReviewAsTransfer(split.confirmed);
            return;
        }

        review.replaceFlows(split.remaining);
        ProfitTransaction transfer = new ProfitTransaction(
            now,
            activeSession.getElapsedMillis(now),
            TransactionType.TRANSFER,
            TrackingContext.TRANSFER,
            "Charge load transfer",
            "Charge load",
            false,
            split.confirmed,
            ClassificationConfidence.CONFIRMED,
            "A same-target measured Check confirmed these component quantities were loaded; unconfirmed quantities remain in Review.",
            null);
        transfer.setActionKind(ActionKind.CHARGE_LOAD_AMBIGUOUS);
        transfer.setChargeLoadReviewProvenance(review.getChargeLoadReviewProvenance());
        transfer.setRunId(review.getRunId());
        activeSession.addTransaction(transfer, config.maxTransactionsPerSession());
    }

    private static boolean hasDuplicateChargeComponentIds(List<ItemFlow> flows)
    {
        Set<Integer> seen = new HashSet<>();
        if (flows == null)
        {
            return false;
        }
        for (ItemFlow flow : flows)
        {
            if (flow != null && flow.getQuantityDelta() < 0L && !seen.add(flow.getItemId()))
            {
                return true;
            }
        }
        return false;
    }

    private static ChargeLoadReviewSplit splitChargeLoadReview(
        List<ItemFlow> flows,
        Map<Integer, Long> measuredQuantities)
    {
        List<ItemFlow> confirmed = new ArrayList<>();
        List<ItemFlow> remaining = new ArrayList<>();
        Map<Integer, Long> available = new HashMap<>(measuredQuantities);
        if (flows == null)
        {
            return new ChargeLoadReviewSplit(confirmed, remaining);
        }
        for (ItemFlow flow : flows)
        {
            if (flow == null || flow.getQuantityDelta() >= 0L)
            {
                if (flow != null) remaining.add(flow);
                continue;
            }

            long measured = available.getOrDefault(flow.getItemId(), 0L);
            long quantity = flow.getQuantityDelta() == Long.MIN_VALUE
                ? Long.MAX_VALUE
                : -flow.getQuantityDelta();
            long confirmQuantity = Math.min(quantity, measured);
            if (confirmQuantity <= 0L || flow.getUnitPrice() < 0L)
            {
                remaining.add(flow);
                continue;
            }

            long expectedValue;
            try
            {
                expectedValue = Math.multiplyExact(flow.getQuantityDelta(), (long) flow.getUnitPrice());
            }
            catch (ArithmeticException ex)
            {
                remaining.add(flow);
                continue;
            }
            if (expectedValue != flow.getValueDelta())
            {
                remaining.add(flow);
                continue;
            }

            try
            {
                long confirmedDelta = Math.negateExact(confirmQuantity);
                long confirmedValue = Math.multiplyExact(confirmedDelta, (long) flow.getUnitPrice());
                confirmed.add(new ItemFlow(
                    flow.getItemId(), flow.getItemName(), confirmedDelta, flow.getUnitPrice(),
                    confirmedValue, flow.getPriceSource(), flow.getPriceCapturedAtEpochMillis()));
                long residualQuantity = quantity - confirmQuantity;
                if (residualQuantity > 0L)
                {
                    long residualDelta = Math.negateExact(residualQuantity);
                    long residualValue = Math.multiplyExact(residualDelta, (long) flow.getUnitPrice());
                    remaining.add(new ItemFlow(
                        flow.getItemId(), flow.getItemName(), residualDelta, flow.getUnitPrice(),
                        residualValue, flow.getPriceSource(), flow.getPriceCapturedAtEpochMillis()));
                }
                available.put(flow.getItemId(), measured - confirmQuantity);
            }
            catch (ArithmeticException ex)
            {
                remaining.add(flow);
            }
        }
        return new ChargeLoadReviewSplit(confirmed, remaining);
    }

    private Set<Integer> pendingChargeLoadComponents(@Nullable MeasuredChargeRead.Variant variant)
    {
        Set<Integer> components = new LinkedHashSet<>();
        if (variant == null || activeSession == null)
        {
            return components;
        }
        for (ProfitTransaction transaction : activeSession.getTransactions())
        {
            if (transaction == null)
            {
                continue;
            }
            ChargeLoadReviewProvenance provenance = transaction.getChargeLoadReviewProvenance();
            if (provenance == null || !variant.name().equalsIgnoreCase(provenance.getVariantWireName()))
            {
                continue;
            }
            boolean unresolvedReview = transaction.getAutomaticType() == TransactionType.UNCERTAIN
                && transaction.getCorrection() == TransactionCorrection.AUTO;
            boolean ownerCountedDecision = transaction.getCorrection() == TransactionCorrection.COST
                || transaction.getCorrection() == TransactionCorrection.REVENUE;
            if (!unresolvedReview && !ownerCountedDecision)
            {
                continue;
            }
            for (ItemFlow flow : transaction.getFlows())
            {
                if (flow != null && flow.getQuantityDelta() < 0L)
                {
                    components.add(flow.getItemId());
                }
            }
        }
        return components;
    }

    private static final class ChargeLoadReviewSplit
    {
        private final List<ItemFlow> confirmed;
        private final List<ItemFlow> remaining;

        private ChargeLoadReviewSplit(List<ItemFlow> confirmed, List<ItemFlow> remaining)
        {
            this.confirmed = confirmed;
            this.remaining = remaining;
        }
    }

    private boolean hasUnpricedFlow(List<ItemFlow> flows)
    {
        for (ItemFlow flow : flows)
        {
            if (flow.getPriceSource() == com.gpmanager.model.ItemPriceSource.UNPRICED)
            {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private ProfitTransaction advanceContext(long now)
    {
        chargeLoadTransferEvidence.tick();
        if (dirty || pendingSnapshot != null)
        {
            return null;
        }

        if (contextTicks > 0)
        {
            contextTicks--;
        }
        if (contextTicks <= 0)
        {
            consumeContext();
        }
        if (transferEvidenceTicks > 0)
        {
            transferEvidenceTicks--;
        }
        if (hardTransferEvidenceTicks > 0)
        {
            hardTransferEvidenceTicks--;
            if (hardTransferEvidenceTicks <= 0)
            {
                hardTransferContextActive = false;
            }
        }
        else if (hardTransferContextActive)
        {
            hardTransferContextActive = false;
        }
        if (neutralZoneRestoreTicks > 0 && --neutralZoneRestoreTicks <= 0)
        {
            neutralZoneRestoreTicks = 0;
            neutralZoneStoredItems.clear();
        }
        if (consumptionIntent != null && consumptionIntent.tick())
        {
            consumptionIntent = null;
        }
        if (dropIntent != null && dropIntent.tick())
        {
            dropIntent = null;
        }
        expireOwnDrops();
        advanceLootExpectations();
        return null;
    }

    private ProfitTransaction deathReclaimExpiryTransaction(
        long now,
        DeathReclaimLifecycle.Expired expiredDeathReclaim)
    {
        String explanation = "The gravestone timer expired with "
            + expiredDeathReclaim.itemCount() + " item(s) outstanding: "
            + expiredDeathReclaim.items()
            + ". No item loss or fee was inferred.";
        return new ProfitTransaction(
            now,
            activeSession.getElapsedMillis(now),
            TransactionType.TRANSFER,
            TrackingContext.TRANSFER,
            "Death reclaim expired",
            "Death reclaim",
            false,
            Collections.emptyList(),
            ClassificationConfidence.CONFIRMED,
            explanation,
            null);
    }

    private static boolean deathWipeContextEligible(
        TrackingContext context,
        @Nullable String note,
        boolean transferEvidence,
        boolean hardTransferEvidence,
        boolean softTransferEvidence)
    {
        boolean deathTransferNote = context == TrackingContext.TRANSFER
            && note != null
            && note.startsWith("Death: items held");
        boolean unclaimedGeneric = context == TrackingContext.GENERIC
            && !transferEvidence
            && !hardTransferEvidence
            && !softTransferEvidence;
        return deathTransferNote || unclaimedGeneric;
    }

    private static boolean isDeathHeldNote(@Nullable String note)
    {
        return note != null && note.startsWith("Death: items held");
    }

    private void ageNeutralZoneEntryCapture()
    {
        if (neutralZoneEntryCaptureTicks > 0 && --neutralZoneEntryCaptureTicks <= 0)
        {
            neutralZoneEntryCaptureTicks = 0;
            captureNeutralZoneEntry = false;
            neutralZoneEntryBaseline = null;
        }
    }

    private boolean hasActiveContributionFilter()
    {
        return contributionEligibility != null
            && config.accountingItemFilter() != LootPresentationFilter.ALL_ITEMS;
    }

    public synchronized SessionMetrics getMetrics(long now)
    {
        if (activeSession == null)
        {
            return new SessionMetrics(
                "No session",
                "General",
                false,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0,
                0,
                0);
        }

        long windowMillis = Math.max(1, config.rollingRateMinutes()) * 60_000L;
        return filteredMetrics(activeSession, now, windowMillis);
    }

    public synchronized SessionMetrics filteredMetrics(ProfitSession session, long now, long windowMillis)
    {
        if (session == null)
        {
            return new SessionMetrics("No session", "General", false, 0L, 0L, 0L,
                0L, 0L, 0L, 0, 0, 0);
        }
        if (!hasActiveContributionFilter())
        {
            return session.metrics(now, windowMillis);
        }
        return session.metrics(now, windowMillis, activeContributionEligibility());
    }

    /**
     * The single accounting-eligibility contract applied uniformly across
     * HUD/HUD+/Infobox metrics, Ledger, session history, Insights, and CSV
     * exports. Returns {@code null} (meaning "count everything") unless the
     * advanced {@code accountingItemFilter} setting is active. See
     * {@link ContributionEligibility} for why this is kept distinct from the
     * display-only {@code lootPresentationFilter}.
     */
    @Nullable
    public synchronized BiPredicate<ProfitTransaction, ItemFlow> activeContributionEligibility()
    {
        if (!hasActiveContributionFilter())
        {
            return null;
        }
        return ContributionEligibility.forAccounting(contributionEligibility, config.accountingItemFilter());
    }

    public synchronized ProfitSession getActiveSession()
    {
        return activeSession;
    }

    /** Explicit run boundary inside the active session; no UI is coupled to this model API. */
    @Nullable
    public synchronized Run startRun(String name, long now)
    {
        return activeSession == null ? null : activeSession.startRun(name, now);
    }

    public synchronized boolean stopRun(long now)
    {
        return activeSession != null && activeSession.stopRun(now);
    }

    @Nullable
    public synchronized RunHistorySnapshot getRunHistorySnapshot(long now)
    {
        return activeSession == null ? null
            : activeSession.runHistorySnapshot(now, activeContributionEligibility());
    }

    @Nullable
    public synchronized RunComparisonSnapshot compareRuns(
        String leftRunId,
        String rightRunId,
        long now)
    {
        return activeSession == null ? null
            : activeSession.compareRuns(leftRunId, rightRunId, now, activeContributionEligibility());
    }

    public synchronized ProfitSession getGeneralSession()
    {
        return generalSession;
    }

    public synchronized List<ProfitSession> getHistory()
    {
        return Collections.unmodifiableList(new ArrayList<>(history));
    }

    public synchronized SessionComparisonMetrics getSessionComparison(long now, int limit)
    {
        long currentNet = currentNet(now);
        if (activeSession != null)
        {
            SessionMetrics activeMetrics = filteredMetrics(activeSession, now,
                Math.max(1, config.rollingRateMinutes()) * 60_000L);
            if (!activeMetrics.isAccountingProjectionAvailable()
                || !activeMetrics.isTransactionCountAvailable())
            {
                return SessionComparisonMetrics.unavailable(currentNet,
                    "UNAVAILABLE_ACTIVE_SESSION_DETAIL");
            }
        }
        return comparisonForSessions(eligibleHistory(Math.max(1, limit)), now, currentNet);
    }

    public synchronized SessionIntelligenceSnapshot getSessionIntelligence(long now, int recentLimit)
    {
        long currentNet = currentNet(now);
        long windowMillis = Math.max(1, config.rollingRateMinutes()) * 60_000L;
        if (activeSession != null)
        {
            SessionMetrics activeMetrics = filteredMetrics(activeSession, now, windowMillis);
            if (!activeMetrics.isAccountingProjectionAvailable()
                || !activeMetrics.isTransactionCountAvailable())
            {
                return new SessionIntelligenceSnapshot(
                    SessionComparisonMetrics.unavailable(currentNet, "UNAVAILABLE_ACTIVE_SESSION_DETAIL"),
                    SessionComparisonMetrics.unavailable(currentNet, "UNAVAILABLE_ACTIVE_SESSION_DETAIL"),
                    Collections.emptyList(), "", 0L, "", 0L, "", 0L,
                    "", 0L, "", 0L, Collections.emptyList(),
                    ProfitTrendDirection.INSUFFICIENT_DATA, "UNAVAILABLE_ACTIVE_SESSION_DETAIL");
            }
        }
        List<ProfitSession> lifetimeSessions = eligibleHistory(Integer.MAX_VALUE);
        for (ProfitSession session : lifetimeSessions)
        {
            SessionMetrics metrics = filteredMetrics(session, now, windowMillis);
            if (!metrics.isAccountingProjectionAvailable() || !metrics.isTransactionCountAvailable())
            {
                return new SessionIntelligenceSnapshot(
                    SessionComparisonMetrics.unavailable(currentNet, "UNAVAILABLE_SESSION_DETAIL"),
                    SessionComparisonMetrics.unavailable(currentNet, "UNAVAILABLE_SESSION_DETAIL"),
                    Collections.emptyList(), "", 0L, "", 0L, "", 0L,
                    "", 0L, "", 0L, Collections.emptyList(),
                    ProfitTrendDirection.INSUFFICIENT_DATA, "UNAVAILABLE_SESSION_DETAIL");
            }
            if (!session.pkMetrics(activeContributionEligibility()).isProjectionAvailable())
            {
                return new SessionIntelligenceSnapshot(
                    SessionComparisonMetrics.unavailable(currentNet, "UNAVAILABLE_PK_DETAIL"),
                    SessionComparisonMetrics.unavailable(currentNet, "UNAVAILABLE_PK_DETAIL"),
                    Collections.emptyList(), "", 0L, "", 0L, "", 0L,
                    "", 0L, "", 0L, Collections.emptyList(),
                    ProfitTrendDirection.INSUFFICIENT_DATA, "UNAVAILABLE_PK_DETAIL");
            }
        }
        int recentCount = Math.min(Math.max(1, recentLimit), lifetimeSessions.size());
        List<ProfitSession> recentSessions = new ArrayList<>(lifetimeSessions.subList(0, recentCount));

        SessionComparisonMetrics recent = comparisonForSessions(recentSessions, now, currentNet);
        SessionComparisonMetrics lifetime = comparisonForSessions(lifetimeSessions, now, currentNet);

        Map<SessionCategory, LifetimeAccumulator> byCategory = new EnumMap<>(SessionCategory.class);
        String bestNetName = "";
        long bestNet = 0L;
        String bestRateName = "";
        long bestRate = 0L;
        String longestName = "";
        long longestDuration = 0L;
        String bestPkKillName = "";
        long bestPkKill = 0L;
        String largestPkDeathName = "";
        long largestPkDeath = 0L;
        boolean first = true;

        for (ProfitSession session : lifetimeSessions)
        {
            SessionMetrics metrics = filteredMetrics(session, now, windowMillis);
            SessionCategory category = session.getCategory();
            LifetimeAccumulator accumulator = byCategory.computeIfAbsent(
                category,
                LifetimeAccumulator::new);
            accumulator.accept(session, metrics);

            if (first || metrics.getNet() > bestNet)
            {
                bestNet = metrics.getNet();
                bestNetName = session.getName();
            }
            if (first || metrics.getProfitPerHour() > bestRate)
            {
                bestRate = metrics.getProfitPerHour();
                bestRateName = session.getName();
            }
            if (first || metrics.getElapsedMillis() > longestDuration)
            {
                longestDuration = metrics.getElapsedMillis();
                longestName = session.getName();
            }

            PkMetrics pk = session.pkMetrics(activeContributionEligibility());
            if (pk.isProjectionAvailable() && pk.getBestKill() > bestPkKill)
            {
                bestPkKill = pk.getBestKill();
                bestPkKillName = session.getName();
            }
            if (pk.isProjectionAvailable() && pk.getLargestDeathLoss() > largestPkDeath)
            {
                largestPkDeath = pk.getLargestDeathLoss();
                largestPkDeathName = session.getName();
            }
            first = false;
        }

        List<ActivityLifetimeMetrics> activities = new ArrayList<>();
        for (SessionCategory category : SessionCategory.values())
        {
            if (category == SessionCategory.ALL)
            {
                continue;
            }
            LifetimeAccumulator accumulator = byCategory.get(category);
            if (accumulator != null)
            {
                activities.add(accumulator.toMetrics());
            }
        }

        List<Long> trend = new ArrayList<>();
        for (int index = recentSessions.size() - 1; index >= 0; index--)
        {
            trend.add(filteredMetrics(recentSessions.get(index), now, windowMillis).getNet());
        }
        ProfitTrendDirection direction = trendDirection(trend);

        return new SessionIntelligenceSnapshot(
            recent,
            lifetime,
            activities,
            bestNetName,
            bestNet,
            bestRateName,
            bestRate,
            longestName,
            longestDuration,
            bestPkKillName,
            bestPkKill,
            largestPkDeathName,
            largestPkDeath,
            trend,
            direction);
    }

    private long currentNet(long now)
    {
        long windowMillis = Math.max(1, config.rollingRateMinutes()) * 60_000L;
        return activeSession == null
            ? 0L
            : filteredMetrics(activeSession, now, windowMillis).getNet();
    }

    private List<ProfitSession> eligibleHistory(int limit)
    {
        List<ProfitSession> result = new ArrayList<>();
        int maximum = Math.max(0, limit);
        for (ProfitSession session : history)
        {
            if (result.size() >= maximum)
            {
                break;
            }
            if (session.isExcludedFromAverages() || session.getTransactions().isEmpty())
            {
                continue;
            }
            result.add(session);
        }
        return result;
    }

    private SessionComparisonMetrics comparisonForSessions(
        List<ProfitSession> sessions,
        long now,
        long currentNet)
    {
        long windowMillis = Math.max(1, config.rollingRateMinutes()) * 60_000L;
        int considered = 0;
        int profitable = 0;
        long totalNet = 0L;
        long totalRate = 0L;
        long best = Long.MIN_VALUE;
        long worst = Long.MAX_VALUE;
        boolean projectionAvailable = true;

        for (ProfitSession session : sessions)
        {
            SessionMetrics metrics = filteredMetrics(session, now, windowMillis);
            if (!metrics.isAccountingProjectionAvailable() || !metrics.isTransactionCountAvailable())
            {
                projectionAvailable = false;
                continue;
            }
            if (metrics.getTransactionCount() == 0)
            {
                continue;
            }
            considered++;
            if (metrics.getNet() > 0L)
            {
                profitable++;
            }
            totalNet = saturatingAdd(totalNet, metrics.getNet());
            totalRate = saturatingAdd(totalRate, metrics.getProfitPerHour());
            best = Math.max(best, metrics.getNet());
            worst = Math.min(worst, metrics.getNet());
        }

        if (considered == 0)
        {
            return projectionAvailable ? SessionComparisonMetrics.empty(currentNet)
                : SessionComparisonMetrics.unavailable(currentNet, "UNAVAILABLE_SESSION_DETAIL");
        }

        if (!projectionAvailable)
        {
            return SessionComparisonMetrics.unavailable(currentNet, "UNAVAILABLE_SESSION_DETAIL");
        }

        long averageNet = totalNet / considered;
        return new SessionComparisonMetrics(
            considered,
            profitable,
            averageNet,
            totalRate / considered,
            best,
            worst,
            currentNet,
            saturatingSubtract(currentNet, averageNet));
    }

    private static ProfitTrendDirection trendDirection(List<Long> trend)
    {
        if (trend.size() < 2)
        {
            return ProfitTrendDirection.INSUFFICIENT_DATA;
        }
        int midpoint = Math.max(1, trend.size() / 2);
        long olderTotal = 0L;
        for (int index = 0; index < midpoint; index++)
        {
            olderTotal = saturatingAdd(olderTotal, trend.get(index));
        }
        long newerTotal = 0L;
        for (int index = midpoint; index < trend.size(); index++)
        {
            newerTotal = saturatingAdd(newerTotal, trend.get(index));
        }
        long olderAverage = olderTotal / midpoint;
        long newerAverage = newerTotal / Math.max(1, trend.size() - midpoint);
        if (newerAverage > olderAverage)
        {
            return ProfitTrendDirection.IMPROVING;
        }
        if (newerAverage < olderAverage)
        {
            return ProfitTrendDirection.DECLINING;
        }
        return ProfitTrendDirection.FLAT;
    }

    private static long saturatingAdd(long left, long right)
    {
        try
        {
            return Math.addExact(left, right);
        }
        catch (ArithmeticException ex)
        {
            return right >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    private static long saturatingSubtract(long left, long right)
    {
        try
        {
            return Math.subtractExact(left, right);
        }
        catch (ArithmeticException ex)
        {
            return right < 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    public synchronized List<ProfitSession> getHistory(HistoryQuery query, long now)
    {
        HistoryQuery safeQuery = query == null ? HistoryQuery.all() : query;
        List<ProfitSession> result = new ArrayList<>();
        for (ProfitSession session : history)
        {
            if (safeQuery.getCategory() != SessionCategory.ALL
                && session.getCategory() != safeQuery.getCategory())
            {
                continue;
            }
            if (safeQuery.isFavoritesOnly() && !session.isFavorite())
            {
                continue;
            }
            if (!safeQuery.getDateRange().includes(session.getStartedAtEpochMillis(), now))
            {
                continue;
            }
            if (!session.matchesSearch(safeQuery.getSearchText()))
            {
                continue;
            }
            result.add(session);
        }

        long windowMillis = Math.max(1, config.rollingRateMinutes()) * 60_000L;
        Comparator<ProfitSession> comparator;
        HistorySort sort = safeQuery.getSort();
        if (sort == HistorySort.OLDEST)
        {
            comparator = Comparator.comparingLong(ProfitSession::getStartedAtEpochMillis);
        }
        else if (sort == HistorySort.PROFIT_HIGH)
        {
            comparator = Comparator.comparingLong(
                (ProfitSession session) -> filteredMetrics(session, now, windowMillis).getNet()).reversed();
        }
        else if (sort == HistorySort.PROFIT_LOW)
        {
            comparator = Comparator.comparingLong(
                session -> filteredMetrics(session, now, windowMillis).getNet());
        }
        else if (sort == HistorySort.RATE_HIGH)
        {
            comparator = Comparator.comparingLong(
                (ProfitSession session) -> filteredMetrics(session, now, windowMillis).getProfitPerHour()).reversed();
        }
        else if (sort == HistorySort.DURATION_LONG)
        {
            comparator = Comparator.comparingLong(
                (ProfitSession session) -> session.getElapsedMillis(now)).reversed();
        }
        else if (sort == HistorySort.NAME_A_Z)
        {
            comparator = Comparator.comparing(
                ProfitSession::getName,
                String.CASE_INSENSITIVE_ORDER);
        }
        else
        {
            comparator = Comparator.comparingLong(ProfitSession::getStartedAtEpochMillis).reversed();
        }
        comparator = comparator.thenComparing(
            Comparator.comparingLong(ProfitSession::getStartedAtEpochMillis).reversed());
        result.sort(comparator);
        return Collections.unmodifiableList(result);
    }

    public synchronized List<ProfitSession> getFilteredHistory(SessionCategory category)
    {
        return getHistory(
            new HistoryQuery(
                category,
                "",
                HistoryDateRange.ALL_TIME,
                HistorySort.NEWEST,
                false),
            System.currentTimeMillis());
    }

    public synchronized ProfitSession getHistorySession(String sessionId)
    {
        if (sessionId == null || sessionId.isEmpty())
        {
            return null;
        }
        for (ProfitSession session : history)
        {
            if (sessionId.equals(session.getId()))
            {
                return session;
            }
        }
        return null;
    }

    public synchronized SessionSummary getHistorySummary(String sessionId, long now)
    {
        ProfitSession session = getHistorySession(sessionId);
        if (session == null)
        {
            return null;
        }
        long windowMillis = Math.max(1, config.rollingRateMinutes()) * 60_000L;
        return new SessionSummary(session, now, windowMillis,
            filteredMetrics(session, now, windowMillis),
            session.pkMetrics(activeContributionEligibility()));
    }

    public synchronized boolean renameHistorySession(String sessionId, String name)
    {
        ProfitSession session = getHistorySession(sessionId);
        if (session == null)
        {
            return false;
        }
        session.rename(name);
        return true;
    }

    public synchronized boolean setHistorySessionTags(String sessionId, String tags)
    {
        ProfitSession session = getHistorySession(sessionId);
        if (session == null)
        {
            return false;
        }
        session.setTags(tags);
        return true;
    }

    public synchronized boolean setHistorySessionCategory(
        String sessionId, SessionCategory category)
    {
        ProfitSession session = getHistorySession(sessionId);
        return session != null && session.setCategoryOverride(category);
    }

    public synchronized boolean setHistorySessionNotes(String sessionId, String notes)
    {
        ProfitSession session = getHistorySession(sessionId);
        if (session == null)
        {
            return false;
        }
        session.setNotes(notes);
        return true;
    }

    public synchronized boolean setHistorySessionFavorite(String sessionId, boolean favorite)
    {
        ProfitSession session = getHistorySession(sessionId);
        if (session == null)
        {
            return false;
        }
        session.setFavorite(favorite);
        return true;
    }

    public synchronized boolean setHistorySessionExcluded(String sessionId, boolean excluded)
    {
        ProfitSession session = getHistorySession(sessionId);
        if (session == null)
        {
            return false;
        }
        session.setExcludedFromAverages(excluded);
        return true;
    }

    public synchronized boolean deleteHistorySession(String sessionId)
    {
        if (sessionId == null || sessionId.isEmpty())
        {
            return false;
        }
        for (int index = 0; index < history.size(); index++)
        {
            if (sessionId.equals(history.get(index).getId()))
            {
                history.remove(index);
                discardPersistedDailyRollupBaseline();
                return true;
            }
        }
        return false;
    }

    public synchronized int clearCompletedHistory()
    {
        int count = history.size();
        history.clear();
        if (count > 0) discardPersistedDailyRollupBaseline();
        return count;
    }

    /** Replaces General only when no temporary owner can lose data. */
    public synchronized boolean restartGeneral(long now, boolean archivePrevious, boolean preserveTarget)
    {
        if (customSession != null && !customSession.isClosed())
        {
            return false;
        }
        Long target = generalSession == null || !preserveTarget ? null : generalSession.getProfitTargetGp();
        if (archivePrevious && generalSession != null && !generalSession.isClosed())
        {
            if (generalSession.getEndReason() == null)
            {
                generalSession.setEndReason(SessionEndReason.MANUAL);
            }
            generalSession.close(now);
            history.add(0, generalSession);
            trimHistory();
        }
        if (generalSession != null) pruneGoalAlertStateForSession(generalSession.getId());
        generalSession = newProfileSession(com.gpmanager.ui.SessionOwnerLabels.DURABLE_OWNER_NAME,
            com.gpmanager.model.SessionMode.AUTO, now, SessionOwnerKind.FREE_PLAY);
        if (target != null) generalSession.setProfitTargetGp(target);
        generalSession.stop(now);
        // Restart General always requires explicit Resume — never auto-start wake.
        autoStartEligibleAfterReset = false;
        customSession = null;
        generalSuspendedByCustom = false;
        activeSession = generalSession;
        resetTrackingState(true);
        return true;
    }

    /** Clears only GP Manager tracking state; configuration is intentionally outside the engine. */
    public synchronized void resetTrackingData(long now)
    {
        clearTransientAlertState();
        history.clear();
        discardPersistedDailyRollupBaseline();
        wealthSnapshotHistory = WealthSnapshotHistory.empty();
        generalSession = newProfileSession(com.gpmanager.ui.SessionOwnerLabels.DURABLE_OWNER_NAME,
            com.gpmanager.model.SessionMode.AUTO, now, SessionOwnerKind.FREE_PLAY);
        customSession = null;
        generalSuspendedByCustom = false;
        activeSession = generalSession;
        lootExpectations.clear();
        clearOwnDropState();
        // With Automatic tracking on, leave General running + baseline priming so
        // skilling / NPC loot / inventory gains register after reset without an
        // extra Resume. When off, stop and allow one auto-start wake if the user
        // enables Automatic tracking later in this client session.
        if (config.autoStartSession())
        {
            autoStartEligibleAfterReset = false;
        }
        else
        {
            generalSession.stop(now);
            autoStartEligibleAfterReset = true;
        }
        resetTrackingState(true);
    }

    /**
     * After Factory reset reseeds config defaults, ensure Automatic tracking's
     * fresh General is running (not left stopped from a pre-wipe preference).
     */
    public synchronized void ensureArmedAfterConfigReseed(long now)
    {
        if (!config.autoStartSession() || activeSession == null || activeSession.isClosed())
        {
            return;
        }
        if (activeSession.isStopped() || activeSession.isPaused())
        {
            activeSession.resume(now);
            autoStartEligibleAfterReset = false;
            resetTrackingState(true);
        }
    }

    /** True when data reset left a stopped General that auto-start may wake. */
    public synchronized boolean isAutoStartEligibleAfterReset()
    {
        return autoStartEligibleAfterReset;
    }

    public synchronized boolean correctHistoryTransaction(
        String sessionId,
        String transactionId,
        TransactionCorrection correction,
        long now)
    {
        return correctHistoryTransaction(sessionId, transactionId, correction, now, "Manual correction");
    }

    public synchronized boolean correctHistoryTransaction(
        String sessionId,
        String transactionId,
        TransactionCorrection correction,
        long now,
        String reason)
    {
        ProfitSession session = getHistorySession(sessionId);
        return session != null
            && session.correctTransaction(transactionId, correction, now, reason);
    }

    public synchronized List<ProfitTransaction> getRecentTransactions(int limit)
    {
        if (activeSession == null)
        {
            return Collections.emptyList();
        }

        List<ProfitTransaction> transactions = activeSession.getTransactions();
        int from = Math.max(0, transactions.size() - Math.max(0, limit));
        List<ProfitTransaction> result = new ArrayList<>(transactions.subList(from, transactions.size()));
        Collections.reverse(result);
        return result;
    }

    /** Entire retained owner ledger, newest first. Compacted rows are not recoverable detail. */
    public synchronized List<ProfitTransaction> getRetainedTransactions()
    {
        if (activeSession == null)
        {
            return Collections.emptyList();
        }
        List<ProfitTransaction> result = new ArrayList<>(activeSession.getTransactions());
        Collections.reverse(result);
        return result;
    }

    public synchronized List<com.gpmanager.model.ActivityMetrics> getActivityBreakdown()
    {
        return activeSession == null
            ? Collections.emptyList()
            : activeSession.activityBreakdown(activeContributionEligibility());
    }

    public synchronized String getActivityBreakdownProjectionStatus()
    {
        return activeSession == null ? "NO_SESSION"
            : activeSession.activityProjectionStatus(activeContributionEligibility());
    }



    public synchronized PkMetrics getPkMetrics()
    {
        return activeSession == null
            ? new PkMetrics(0, 0, 0, 0L, 0L, 0L, 0L, 0L)
            : activeSession.pkMetrics(activeContributionEligibility());
    }

    /**
     * Latest other PK session, selected by start time across active owners and
     * history. The returned PkMetrics net is the previous-session comparison value.
     */
    @Nullable
    public synchronized SessionSummary getPreviousPkSessionSummary(@Nullable String excludeId)
    {
        long now = System.currentTimeMillis();
        ProfitSession previous = null;
        for (ProfitSession session : uniqueProfileSessions())
        {
            if (session == null || (excludeId != null && excludeId.equals(session.getId()))) continue;
            if (session.getMode() != com.gpmanager.model.SessionMode.PK
                && session.getCategory() != SessionCategory.PKING
                && session.getPkEncounters().isEmpty()) continue;
            if (previous == null || session.getStartedAtEpochMillis()
                > previous.getStartedAtEpochMillis()) previous = session;
        }
        if (previous == null) return null;
        long rateWindow = Math.max(1, config.rollingRateMinutes()) * 60_000L;
        SessionMetrics metrics = filteredMetrics(previous, now, rateWindow);
        PkMetrics pkMetrics = previous.pkMetrics(activeContributionEligibility());
        return new SessionSummary(previous, now, rateWindow, metrics, pkMetrics);
    }

    public synchronized String getRecentPkEncountersProjectionStatus()
    {
        if (activeSession == null) return "NO_SESSION";
        return activeSession.pkProjectionStatus(activeContributionEligibility());
    }

    public synchronized List<PkEncounter> getRecentPkEncounters(int limit)
    {
        if (activeSession == null || !activeSession.isPkProjectionAvailable(activeContributionEligibility()))
        {
            return Collections.emptyList();
        }
        List<PkEncounter> encounters = activeSession.getPkEncounters();
        int from = Math.max(0, encounters.size() - Math.max(0, limit));
        List<PkEncounter> result = new ArrayList<>(encounters.subList(from, encounters.size()));
        Collections.reverse(result);
        return result;
    }

    public synchronized int getPendingLootExpectationCount()
    {
        return lootExpectations.size();
    }

    public synchronized boolean isBaselineReady()
    {
        return baseline != null && !baselinePriming;
    }

    public synchronized boolean isBaselinePriming()
    {
        return baselinePriming;
    }

    public synchronized boolean hasPendingCorrelation()
    {
        return pendingSnapshot != null || !lootExpectations.isEmpty();
    }

    /**
     * Combines unique General, custom and archived tracker objects. UTC day
     * boundaries are explicit so travelling profiles do not reinterpret saved
     * summaries. days=0 means all retained analytics days, not inferred lifetime.
     */
    /**
     * @deprecated Use {@link #getDailyRollups(LocalDate, LocalDate)} or
     * {@link #getInsightsWindow(int, long)} for profile-local, coverage-aware analytics.
     */
    @Deprecated
    public synchronized TrackingInsightsSnapshot getTrackingInsights(int days, long now)
    {
        if (hasActiveContributionFilter())
        {
            // The persisted daily analytics are aggregate-only and cannot be
            // re-evaluated against an item filter without inventing a zero or
            // leaking the old raw totals.
            return new TrackingInsightsSnapshot(0, 0L, 0L, 0L, 0L,
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyList(), "UNAVAILABLE_DAILY_ITEM_ATTRIBUTION",
                config.notableDropThresholdGp());
        }
        List<ProfitSession> sessions = new ArrayList<>(history);
        if (generalSession != null) sessions.add(generalSession);
        if (customSession != null) sessions.add(customSession);
        Set<String> seen = new HashSet<>();
        Map<String, Long> activities = new LinkedHashMap<>();
        Map<String, Long> items = new LinkedHashMap<>();
        Map<String, Long> legacyItems = new LinkedHashMap<>();
        Map<String, TrackingGainedItemDetail> itemDetails = new LinkedHashMap<>();
        Set<String> recordedDays = new HashSet<>();
        long revenue = 0L;
        long costs = 0L;
        long suppliesCosts = 0L;
        long otherCosts = 0L;
        boolean costSplitAvailable = true;
        long active = 0L;
        long availableSince = 0L;
        boolean filtered = hasActiveContributionFilter();
        LocalDate earliest = days <= 0 ? null : Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC)
            .toLocalDate().minusDays(Math.max(0, days - 1));
        for (ProfitSession session : sessions)
        {
            if (session == null || !seen.add(session.getId())) continue;
            boolean inRequestedRange = false;
            for (TrackingDaySummary day : session.getAnalyticsDays())
            {
                if (day == null || day.getDay().isEmpty()) continue;
                LocalDate date;
                try { date = LocalDate.parse(day.getDay()); }
                catch (RuntimeException ex) { continue; }
                if (earliest != null && date.isBefore(earliest)) continue;
                inRequestedRange = true;
                recordedDays.add(day.getDay());
                if (!filtered)
                {
                    revenue = saturatingAdd(revenue, day.getRevenue());
                    costs = saturatingAdd(costs, day.getCosts());
                    suppliesCosts = saturatingAdd(suppliesCosts, day.getSuppliesCosts());
                    otherCosts = saturatingAdd(otherCosts, day.getOtherCosts());
                    if (!day.isCostSplitAvailable()) costSplitAvailable = false;
                }
                active = saturatingAdd(active, day.getActiveMillis());
                for (Map.Entry<String, Long> entry : day.getActivityNet().entrySet())
                {
                    activities.merge(entry.getKey(), entry.getValue(), GpManagerEngine::saturatingAdd);
                }
                for (Map.Entry<String, Long> entry : day.getUndetailedGainedItems().entrySet())
                {
                    legacyItems.merge(entry.getKey(), entry.getValue(), GpManagerEngine::saturatingAdd);
                }
                for (TrackingGainedItemDetail detail : day.getGainedItemDetails())
                {
                    if (detail == null || detail.getValue() <= 0L) continue;
                    TrackingGainedItemDetail aggregate = itemDetails.get(detail.aggregationKey());
                    if (aggregate == null)
                    {
                        itemDetails.put(detail.aggregationKey(), detail.copy());
                    }
                    else
                    {
                        aggregate.merge(detail);
                    }
                }
            }
            if (filtered && inRequestedRange)
            {
                SessionMetrics metrics = filteredMetrics(session, now,
                    Math.max(1, config.rollingRateMinutes()) * 60_000L);
                revenue = saturatingAdd(revenue, metrics.getRevenue());
                costs = saturatingAdd(costs, metrics.getCosts());
                suppliesCosts = saturatingAdd(suppliesCosts, metrics.getSuppliesCosts());
                otherCosts = saturatingAdd(otherCosts, metrics.getOtherCosts());
                if (!metrics.isCostSplitAvailable()) costSplitAvailable = false;
            }
            long started = session.getAnalyticsStartedAtEpochMillis();
            if (started > 0L && (availableSince == 0L || started < availableSince)) availableSince = started;
        }
        for (Map.Entry<String, Long> entry : legacyItems.entrySet())
        {
            items.merge(entry.getKey(), entry.getValue(), GpManagerEngine::saturatingAdd);
        }
        List<TrackingGainedItemDetail> details = new ArrayList<>(itemDetails.values());
        for (TrackingGainedItemDetail detail : details)
        {
            items.merge(detail.getItemName(), detail.getValue(), GpManagerEngine::saturatingAdd);
        }
        return new TrackingInsightsSnapshot(recordedDays.size(), revenue, costs, active,
            availableSince, activities, items, legacyItems, details,
            "AVAILABLE", config.notableDropThresholdGp(), Collections.emptyList(),
            TrackingInsightsSnapshot.MILESTONES_UNAVAILABLE_NO_DURABLE_ENCOUNTER_EVIDENCE,
            costSplitAvailable ? suppliesCosts : 0L,
            costSplitAvailable ? otherCosts : 0L, costSplitAvailable);
    }

    public synchronized String getTrackingInsightsProjectionStatus()
    {
        return hasActiveContributionFilter()
            ? "UNAVAILABLE_DAILY_ITEM_ATTRIBUTION" : "AVAILABLE";
    }

    /** Daily net points for the same UTC scope as {@link #getTrackingInsights}. */
    public synchronized List<Long> getTrackingInsightsTrend(int days, long now)
    {
        if (hasActiveContributionFilter())
        {
            return Collections.emptyList();
        }
        List<ProfitSession> sessions = new ArrayList<>(history);
        if (generalSession != null) sessions.add(generalSession);
        if (customSession != null) sessions.add(customSession);
        Set<String> seen = new HashSet<>();
        Map<String, Long> byDay = new java.util.TreeMap<>();
        boolean filtered = hasActiveContributionFilter();
        LocalDate earliest = days <= 0 ? null : Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC)
            .toLocalDate().minusDays(Math.max(0, days - 1));
        for (ProfitSession session : sessions)
        {
            if (session == null || !seen.add(session.getId())) continue;
            for (TrackingDaySummary day : session.getAnalyticsDays())
            {
                if (day == null || day.getDay().isEmpty()) continue;
                try
                {
                    if (earliest == null || !LocalDate.parse(day.getDay()).isBefore(earliest))
                    {
                        byDay.merge(day.getDay(), filtered
                            ? filteredNetForDay(session, day.getDay())
                            : day.getNet(), GpManagerEngine::saturatingAdd);
                    }
                }
                catch (RuntimeException ignored)
                {
                    // Ignore malformed optional analytics only; accounting state remains usable.
                }
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(byDay.values()));
    }

    /** Dated daily net points. Missing keys are unrecorded days, never inferred zeroes. */
    public synchronized Map<String, Long> getTrackingInsightsTrendByDay(int days, long now)
    {
        if (hasActiveContributionFilter())
        {
            return Collections.emptyMap();
        }
        List<ProfitSession> sessions = new ArrayList<>(history);
        if (generalSession != null) sessions.add(generalSession);
        if (customSession != null) sessions.add(customSession);
        Set<String> seen = new HashSet<>();
        Map<String, Long> byDay = new java.util.TreeMap<>();
        boolean filtered = hasActiveContributionFilter();
        LocalDate earliest = days <= 0 ? null : Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC)
            .toLocalDate().minusDays(Math.max(0, days - 1));
        for (ProfitSession session : sessions)
        {
            if (session == null || !seen.add(session.getId())) continue;
            for (TrackingDaySummary day : session.getAnalyticsDays())
            {
                if (day == null || day.getDay().isEmpty()) continue;
                try
                {
                    if (earliest == null || !LocalDate.parse(day.getDay()).isBefore(earliest))
                    {
                        byDay.merge(day.getDay(), filtered
                            ? filteredNetForDay(session, day.getDay())
                            : day.getNet(), GpManagerEngine::saturatingAdd);
                    }
                }
                catch (RuntimeException ignored)
                {
                    // Optional analytics must not make the tracker unusable.
                }
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(byDay));
    }

    private long filteredNetForDay(ProfitSession session, String day)
    {
        long net = 0L;
        for (ProfitTransaction transaction : session.getTransactions())
        {
            if (transaction == null || transaction.getType() == TransactionType.TRANSFER
                || !transaction.isCounted()
                || !day.equals(Instant.ofEpochMilli(transaction.getTimestampEpochMillis())
                    .atZone(ZoneOffset.UTC).toLocalDate().toString()))
            {
                continue;
            }
            for (ItemFlow flow : transaction.getFlows())
            {
                if (flow != null && contributionEligibility.isFlowIncluded(
                    flow, config.accountingItemFilter()))
                {
                    net = saturatingAdd(net, flow.getValueDelta());
                }
            }
        }
        return net;
    }

    public synchronized SavedState createSavedState()
    {
        SavedState state = new SavedState(generalSession, customSession, generalSuspendedByCustom, history);
        state.setUnknownJsonFields(com.gpmanager.persistence.UnknownFieldPreservation.copyOf(unknownSavedStateFields));
        if (profileIdentityKey != null && !profileIdentityKey.trim().isEmpty())
            state.setOwnerKey(profileIdentityKey);
        state.setGoalDefinitions(goalDefinitions);
        state.setTileLayout(tileLayout);
        state.setLastReceiptRetentionDayUtc(lastReceiptRetentionDayUtc);
        state.setReceiptRetentionDeferredUntilDayChange(receiptRetentionDeferredUntilDayChange);
        state.setProfileTimeZoneId(profileTimeZoneId);
        List<DailyRollup> snapshotRollups = refreshDailyRollups();
        state.setDailyRollups(snapshotRollups);
        persistedDailyRollupBaseline = new ArrayList<>(snapshotRollups);
        invalidateOverallTotalsCache();
        wealthSnapshotHistory = wealthSnapshotHistory.compact(System.currentTimeMillis());
        state.setWealthSnapshotHistory(wealthSnapshotHistory);
        List<SavedState.CoinStoreRecord> stores = new ArrayList<>();
        for (Map.Entry<CoinStore, CoinStoreObservation> e : coinStores.entrySet())
        {
            stores.add(new SavedState.CoinStoreRecord(e.getKey().getLocationId(), e.getValue().value, e.getValue().observedAt));
        }
        for (CoinStore store : coinStoresUnused)
        {
            stores.add(new SavedState.CoinStoreRecord(store.getLocationId(), 0L, 0L, true));
        }
        state.setCoinStores(stores);
        return state;
    }

    private ProfitSession newProfileSession(String name, com.gpmanager.model.SessionMode mode, long now,
        SessionOwnerKind ownerKind)
    {
        ProfitSession session = new ProfitSession(name, now, mode);
        session.setOwnerKind(ownerKind);
        session.configureAnalyticsTimeZone(profileTimeZoneId, false);
        attachOverallTotalsInvalidationListener(session);
        invalidateOverallTotalsCache();
        return session;
    }

    private void attachOverallTotalsInvalidationListener(ProfitSession session)
    {
        if (session != null)
        {
            session.setAnalyticsChangeListener(this::invalidateOverallTotalsCache);
            session.setAnalyticsActiveTimeListener(this::applyLiveActiveTimeDeltas);
        }
    }

    private synchronized void invalidateOverallTotalsCache()
    {
        rollupGeneration = rollupGeneration == Long.MAX_VALUE ? 1L : rollupGeneration + 1L;
        rollupCache = null;
        overallCacheGeneration = overallCacheGeneration == Long.MAX_VALUE ? 1L : overallCacheGeneration + 1L;
        overallCacheBuiltGeneration = -1L;
        overallCacheAsOfNow = 0L;
        overallDayCache.clear();
        overallFutureDayKeys.clear();
        overallAllTimeCache = null;
    }

    private static boolean validTimeZone(String value)
    {
        if (value == null || value.trim().isEmpty()) return false;
        try { ZoneId.of(value); return true; }
        catch (RuntimeException ex) { return false; }
    }

    private static long safeAbsolute(long value)
    {
        return value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
    }

    private static final class WealthPair
    {
        private final WealthSnapshotHistory.Snapshot before;
        private final WealthSnapshotHistory.Snapshot after;

        private WealthPair(WealthSnapshotHistory.Snapshot before,
            WealthSnapshotHistory.Snapshot after)
        {
            this.before = before;
            this.after = after;
        }
    }

    private static final class WealthHeldValue
    {
        private long quantity;
        private final int unitPrice;
        private final com.gpmanager.model.ItemPriceSource source;
        private final String name;
        private boolean consistent = true;

        private WealthHeldValue(WealthSnapshotHistory.Holding holding)
        {
            quantity = holding.getQuantity();
            unitPrice = holding.getUnitPrice();
            source = holding.getPriceSource();
            name = holding.getItemName();
            consistent = holding.isValueAvailable();
        }

        private void add(WealthSnapshotHistory.Holding holding)
        {
            quantity = Math.addExact(quantity, holding.getQuantity());
            consistent &= holding.isValueAvailable()
                && unitPrice == holding.getUnitPrice()
                && source == holding.getPriceSource();
        }
    }

    /**
     * Per-source evidence: a confirmed Eat/Drink/Bury/Cast action. Decays on its
     * own TTL via {@link TimedEvidence} — independent of bank-open/close evidence,
     * so a stale or active bank signal can never suppress a confirmed consume.
     * See {@code com.gpmanager.engine.evidence} for why evidence lifetimes are
     * tracked per-source rather than with one shared flag.
     */
    private static final class ConsumptionIntent extends TimedEvidence
    {
        private final int itemId;
        private final long generation;
        /** Irreversible Destroy menu — presentation Destroyed, never own-drop recovery. */
        private final boolean destroy;
        /**
         * Invent qty of {@link #itemId} when intent armed (−1 unknown). Used to recover
         * same-tick acquire+consume that nets to zero vs the session baseline.
         */
        private final long quantityAtArm;
        /** Menu verb that armed this intent (B10 presentation evidence); null when unknown. */
        @Nullable
        private final ActionKind actionKind;

        private ConsumptionIntent(
            int itemId,
            int ticksRemaining,
            long generation,
            boolean destroy,
            long quantityAtArm,
            @Nullable ActionKind actionKind)
        {
            super(ticksRemaining);
            this.itemId = itemId;
            this.generation = generation;
            this.destroy = destroy;
            this.quantityAtArm = quantityAtArm;
            this.actionKind = actionKind;
        }
    }

    /**
     * Per-source evidence: a confirmed player-initiated Drop action, held until a
     * matching pickup reverses the loss or the TTL lapses. Independent lifetime
     * from bank/consume evidence — see {@code com.gpmanager.engine.evidence}.
     */
    private static final class DropIntent extends TimedEvidence
    {
        private final int itemId;
        private final int worldX;
        private final int worldY;
        private final int worldPlane;
        private final boolean hasLocation;

        private DropIntent(
            int itemId,
            int ticksRemaining,
            int worldX,
            int worldY,
            int worldPlane,
            boolean hasLocation)
        {
            super(ticksRemaining);
            this.itemId = itemId;
            this.worldX = worldX;
            this.worldY = worldY;
            this.worldPlane = worldPlane;
            this.hasLocation = hasLocation;
        }
    }

    private static final class OwnDropRecord
    {
        private final String transactionId;
        private final int itemId;
        private long remainingQuantity;
        private final int unitPrice;
        private final boolean hasLocation;
        private final int worldX;
        private final int worldY;
        private final int worldPlane;
        private int ticksRemaining;

        private OwnDropRecord(
            String transactionId,
            int itemId,
            long remainingQuantity,
            int unitPrice,
            boolean hasLocation,
            int worldX,
            int worldY,
            int worldPlane,
            int ticksRemaining)
        {
            this.transactionId = transactionId;
            this.itemId = itemId;
            this.remainingQuantity = remainingQuantity;
            this.unitPrice = unitPrice;
            this.hasLocation = hasLocation;
            this.worldX = worldX;
            this.worldY = worldY;
            this.worldPlane = worldPlane;
            this.ticksRemaining = ticksRemaining;
        }

        private boolean tick()
        {
            return --ticksRemaining <= 0;
        }
    }

    private static final class OwnDropMatch
    {
        private final OwnDropRecord record;
        private final long quantity;

        private OwnDropMatch(OwnDropRecord record, long quantity)
        {
            this.record = record;
            this.quantity = quantity;
        }
    }

    private static final class LifetimeAccumulator
    {
        private final SessionCategory category;
        private int sessions;
        private int profitable;
        private long revenue;
        private long costs;
        private long net;
        private long duration;
        private long totalRate;
        private String bestName = "";
        private long bestNet = Long.MIN_VALUE;
        private String worstName = "";
        private long worstNet = Long.MAX_VALUE;

        private LifetimeAccumulator(SessionCategory category)
        {
            this.category = category;
        }

        private void accept(ProfitSession session, SessionMetrics metrics)
        {
            sessions++;
            if (metrics.getNet() > 0L)
            {
                profitable++;
            }
            revenue = saturatingAdd(revenue, metrics.getRevenue());
            costs = saturatingAdd(costs, metrics.getCosts());
            net = saturatingAdd(net, metrics.getNet());
            duration = saturatingAdd(duration, metrics.getElapsedMillis());
            totalRate = saturatingAdd(totalRate, metrics.getProfitPerHour());
            if (metrics.getNet() > bestNet)
            {
                bestNet = metrics.getNet();
                bestName = session.getName();
            }
            if (metrics.getNet() < worstNet)
            {
                worstNet = metrics.getNet();
                worstName = session.getName();
            }
        }

        private ActivityLifetimeMetrics toMetrics()
        {
            return new ActivityLifetimeMetrics(
                category,
                sessions,
                profitable,
                revenue,
                costs,
                net,
                duration,
                sessions == 0 ? 0L : net / sessions,
                sessions == 0 ? 0L : totalRate / sessions,
                bestName,
                bestNet == Long.MIN_VALUE ? 0L : bestNet,
                worstName,
                worstNet == Long.MAX_VALUE ? 0L : worstNet);
        }
    }

    private static final class BackupInspection
    {
        private final ProfileBackupReport report;
        @Nullable
        private final GpManagerEngine stagedEngine;

        private BackupInspection(ProfileBackupReport report, @Nullable GpManagerEngine stagedEngine)
        {
            this.report = report;
            this.stagedEngine = stagedEngine;
        }

        private static BackupInspection refused(ProfileBackupReport report)
        {
            return new BackupInspection(report, null);
        }
    }

    private void trace(String category, String detail)
    {
        if (debugTrace != null)
        {
            debugTrace.record(category, detail);
        }
    }

}
