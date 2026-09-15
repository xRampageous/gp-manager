package com.gpmanager.ui;

import com.gpmanager.FeedbackStyle;
import com.gpmanager.GpManagerConfig;
import com.gpmanager.InfoBoxActivityLabel;
import com.gpmanager.LootPresentationFilter;
import com.gpmanager.TrackingDisplay;
import com.gpmanager.diagnostics.RateAvailability;
import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.grounditems.FilteredRewardView;
import com.gpmanager.grounditems.LootPresentationFilterService;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.ProfitTargetParser;
import com.gpmanager.model.SessionMetrics;
import com.gpmanager.persistence.PersistenceCoordinator;
import com.gpmanager.reward.RewardPresentationModel;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Builds one shared tracking display snapshot for HUD and Infobox.
 */
@Singleton
public class TrackingDisplayModel
{
    private final GpManagerEngine engine;
    private final GpManagerConfig config;
    private final RewardPresentationModel rewardModel;
    @Nullable
    private final PersistenceCoordinator persistence;
    private final HudClock clock;
    @Nullable
    private final LootPresentationFilterService lootFilter;
    private InteractionContextModel interactionContext;
    @Nullable
    private CharacterIdleModel characterIdleModel;
    /** Display-only place state, fed from the plugin's region classifier. */
    private boolean neutralZoneActive;
    /** Recent process skill title supported by positive XP, independent of engine activity. */
    private String confirmedProcessTitle = "";
    /** PvP-only presentation data; never reads or changes ledger accounting. */
    private boolean pvpPossible;
    @Nullable
    private WildernessRiskCalculator.Result wildernessRisk;

    @Inject
    void setInteractionContext(InteractionContextModel interactionContext)
    {
        this.interactionContext = interactionContext;
        invalidate();
    }

    @Inject
    void setCharacterIdleModel(CharacterIdleModel characterIdleModel)
    {
        this.characterIdleModel = characterIdleModel;
        invalidate();
    }

    public synchronized void setNeutralZoneActive(boolean active)
    {
        if (neutralZoneActive != active)
        {
            neutralZoneActive = active;
            invalidate();
        }
    }

    public synchronized void setConfirmedProcessTitle(String title)
    {
        String normalized = title == null ? "" : title.trim();
        if (!confirmedProcessTitle.equals(normalized))
        {
            confirmedProcessTitle = normalized;
            invalidate();
        }
    }

    public synchronized void setWildernessRisk(
        boolean pvpPossible,
        @Nullable WildernessRiskCalculator.Result risk)
    {
        WildernessRiskCalculator.Result safeRisk = pvpPossible && risk != null && risk.isComplete()
            ? risk
            : null;
        if (this.pvpPossible == pvpPossible && sameRisk(this.wildernessRisk, safeRisk))
        {
            return;
        }
        this.pvpPossible = pvpPossible;
        this.wildernessRisk = safeRisk;
        // A PvP scan runs every game tick. Drop only the display cache here so it
        // cannot reset the independent GP/hr presentation throttle.
        cached = null;
        cachedAtEpochMillis = Long.MIN_VALUE;
        cachedFilterFingerprint = "";
    }

    private static boolean sameRisk(
        @Nullable WildernessRiskCalculator.Result left,
        @Nullable WildernessRiskCalculator.Result right)
    {
        if (left == right)
        {
            return true;
        }
        if (left == null || right == null || left.isComplete() != right.isComplete())
        {
            return false;
        }
        return !left.isComplete()
            || (left.getCarriedValue() == right.getCarriedValue()
                && left.getKeptValue() == right.getKeptValue()
                && left.getRiskValue() == right.getRiskValue());
    }

    private TrackingDisplaySnapshot cached;
    private long cachedAtEpochMillis;
    private String cachedFilterFingerprint = "";
    @Nullable
    private ProfitSession previousTargetSession;
    @Nullable
    private String previousTargetSessionId;
    @Nullable
    private Long previousTargetGp;
    private boolean previousTargetReached;

    /**
     * Displayed GP/hr is sampled at most once per {@link #RATE_DISPLAY_THROTTLE_MILLIS}
     * so HUD+/HUD/Infobox numbers do not flicker every engine tick. The true
     * (unthrottled) rate remains available via {@link #trueProfitPerHour(long)} for
     * the panel and other callers that read {@link GpManagerEngine#getMetrics(long)}
     * directly.
     */
    private static final long RATE_DISPLAY_THROTTLE_MILLIS = 1_000L;
    private long displayedProfitPerHour;
    private long rateSampledAtEpochMillis = Long.MIN_VALUE;
    private String rateSampleSessionId = "";

    @Inject
    public TrackingDisplayModel(
        GpManagerEngine engine,
        GpManagerConfig config,
        RewardPresentationModel rewardModel,
        @Nullable PersistenceCoordinator persistence,
        HudClock clock,
        @Nullable LootPresentationFilterService lootFilter)
    {
        this.engine = engine;
        this.config = config;
        this.rewardModel = rewardModel;
        this.persistence = persistence;
        this.clock = clock == null ? new HudClock() : clock;
        this.lootFilter = lootFilter;
    }

    /** Test/preview constructor retaining LatestDropModel-era call sites. */
    public TrackingDisplayModel(
        GpManagerEngine engine,
        GpManagerConfig config,
        LatestDropModel ignoredLegacy,
        @Nullable PersistenceCoordinator persistence)
    {
        this(engine, config, new RewardPresentationModel(), persistence, new HudClock(), null);
    }

    public TrackingDisplayModel(
        GpManagerEngine engine,
        GpManagerConfig config,
        RewardPresentationModel rewardModel,
        @Nullable PersistenceCoordinator persistence)
    {
        this(engine, config, rewardModel, persistence, new HudClock(), null);
    }

    public TrackingDisplayModel(
        GpManagerEngine engine,
        GpManagerConfig config,
        RewardPresentationModel rewardModel,
        @Nullable PersistenceCoordinator persistence,
        @Nullable LootPresentationFilterService lootFilter)
    {
        this(engine, config, rewardModel, persistence, new HudClock(), lootFilter);
    }

    public HudClock clock()
    {
        return clock;
    }

    /** Drop cached snapshot after filter/config refresh without replaying rewards. */
    public synchronized void invalidate()
    {
        cached = null;
        cachedAtEpochMillis = Long.MIN_VALUE;
        cachedFilterFingerprint = "";
        // Force an immediate (non-throttled) rate resample on the next build().
        rateSampledAtEpochMillis = Long.MIN_VALUE;
    }

    public synchronized TrackingDisplaySnapshot snapshot()
    {
        return snapshot(clock.now());
    }

    public synchronized TrackingDisplaySnapshot snapshot(long now)
    {
        String filterFp = filterFingerprint();
        if (cached != null
            && cachedAtEpochMillis == now
            && filterFp.equals(cachedFilterFingerprint))
        {
            return cached;
        }
        cached = build(now);
        cachedAtEpochMillis = now;
        cachedFilterFingerprint = filterFp;
        return cached;
    }

    public TrackingDisplaySnapshot build(long now)
    {
        ProfitSession active = engine.getActiveSession();
        SessionMetrics metrics = engine.getMetrics(now);
        String activity = active == null
            ? ""
            : InfoBoxActivityLabel.resolve(
                metrics.getActivityHint(),
                active.getMode(),
                metrics.isPaused(),
                metrics.getActionCount(),
                metrics.getTransactionCount());
        if (active != null && metrics.getActionCount() == 0 && metrics.getTransactionCount() == 0)
        {
            // Keep a word for first use; once anything has been recorded, the neutral
            // status gem carries the generic live state without a redundant title.
            activity = "Waiting";
        }
        String status = TrackingStatus.resolve(engine, persistence, metrics, active);
        long lastChange = lastChangeNet(active);
        ProfitTargetPresentation target = buildTargetPresentation(active, metrics.getNet());
        SessionMetrics displayMetrics = withThrottledRate(metrics, now, active);
        String block = persistence != null && persistence.isIdentitySwitchHeld()
            ? persistence.identityBlockReason()
            : null;
        FilteredRewardView filtered = null;
        if (lootFilter != null && rewardModel != null)
        {
            LootPresentationFilter mode = config.lootPresentationFilter();
            filtered = lootFilter.filterReward(
                rewardModel.currentForHud(now),
                mode,
                config.reuseGroundItemsHighlightColors(),
                ProfitTargetParser.parseOrZero(config.minimumDisplayedLootValue()));
        }
        InteractionContextModel.View context = interactionContext == null || active == null
            || engine.isStopped() || (persistence != null && persistence.isIdentitySwitchHeld())
            || !config.autoActivityDetection()
            ? InteractionContextModel.View.EMPTY
            : interactionContext.snapshot(active.getId(), now);
        long lootMin = ProfitTargetParser.parseOrZero(config.minimumDisplayedLootValue());
        boolean characterIdle = characterIdleModel != null && characterIdleModel.isCharacterIdle();
        boolean bankingUi = characterIdleModel != null && characterIdleModel.isBankUiOpen();
        boolean customActive = engine.isCustomSessionActive();
        String sessionName = active == null
            ? ""
            : SessionOwnerLabels.durableDisplayName(active.getName());
        if (customActive && active != null)
        {
            sessionName = active.getName() == null ? "" : active.getName().trim();
        }
        boolean peekPresent = false;
        long peekNet = 0L;
        long peekRate = 0L;
        boolean peekRateOk = false;
        if (customActive)
        {
            ProfitSession overall = engine.getGeneralSession();
            if (overall != null && !overall.isClosed())
            {
                long windowMillis = Math.max(1, config.rollingRateMinutes()) * 60_000L;
                SessionMetrics overallMetrics = engine.filteredMetrics(overall, now, windowMillis);
                peekPresent = true;
                peekNet = overallMetrics.getNet();
                peekRate = overallMetrics.getProfitPerHour();
                peekRateOk = RateAvailability.isEstablished(overallMetrics.getElapsedMillis());
            }
        }
        return TrackingDisplaySnapshot.from(
            displayMetrics,
            activity,
            status,
            rewardModel,
            filtered,
            now,
            config.hudPlusRevealDurationMillis(),
            lastChange,
            block,
            target.isPresent() ? target : null)
            .withInteraction(context)
            .withMinimumDisplayedLootValue(lootMin)
            .withCharacterIdle(characterIdle)
            .withBankingUiOpen(bankingUi)
            .withNeutralZoneActive(neutralZoneActive)
            .withConfirmedProcessTitle(config.autoActivityDetection() ? confirmedProcessTitle : "")
            .withWildernessRisk(pvpPossible, wildernessRisk)
            .withSessionChrome(customActive, sessionName, peekPresent, peekNet, peekRate, peekRateOk);
    }

    /**
     * True, unthrottled GP/hr for the current session — bypasses the HUD display
     * throttle. Callers such as the panel already read {@link GpManagerEngine#getMetrics(long)}
     * directly and are unaffected by the throttle, but this is exposed for any
     * caller that only has a {@link TrackingDisplayModel} reference.
     */
    public long trueProfitPerHour(long now)
    {
        return engine.getMetrics(now).getProfitPerHour();
    }

    /**
     * Returns {@code metrics} unchanged except for a rate-limited (~1s) GP/hr value,
     * so HUD+/HUD/Infobox numbers do not flicker every engine tick. Net, revenue,
     * costs, and elapsed time are always current. Resets immediately on session
     * change or {@link #invalidate()} so a fresh session never inherits a stale rate.
     */
    private SessionMetrics withThrottledRate(SessionMetrics metrics, long now, @Nullable ProfitSession active)
    {
        if (metrics == null)
        {
            return null;
        }
        String sessionId = active == null ? "" : String.valueOf(active.getId());
        boolean sessionChanged = !sessionId.equals(rateSampleSessionId);
        if (sessionChanged
            || rateSampledAtEpochMillis == Long.MIN_VALUE
            || now < rateSampledAtEpochMillis
            || now - rateSampledAtEpochMillis >= RATE_DISPLAY_THROTTLE_MILLIS)
        {
            displayedProfitPerHour = metrics.getProfitPerHour();
            rateSampledAtEpochMillis = now;
            rateSampleSessionId = sessionId;
        }
        if (displayedProfitPerHour == metrics.getProfitPerHour())
        {
            return metrics;
        }
        return new SessionMetrics(
            metrics.getSessionName(),
            metrics.getActivityHint(),
            metrics.isPaused(),
            metrics.getElapsedMillis(),
            metrics.getRevenue(),
            metrics.getCosts(),
            metrics.getNet(),
            displayedProfitPerHour,
            metrics.getRollingProfitPerHour(),
            metrics.getTransactionCount(),
            metrics.getTransferCount(),
            metrics.getActionCount());
    }

    public TrackingDisplay trackingDisplay()
    {
        return config.trackingDisplay();
    }

    public FeedbackStyle feedbackStyle()
    {
        return config.feedbackStyle();
    }

    public RewardPresentationModel rewardModel()
    {
        return rewardModel;
    }

    private String filterFingerprint()
    {
        LootPresentationFilter mode = config.lootPresentationFilter();
        boolean colors = config.reuseGroundItemsHighlightColors();
        String gi = lootFilter == null ? "" : lootFilter.configFingerprint();
        return String.valueOf(mode) + '|' + colors + '|' + gi + '|'
            + ProfitTargetParser.parseOrZero(config.minimumDisplayedLootValue()) + '|'
            + (interactionContext == null ? 0L : interactionContext.revision());
    }

    private long lastChangeNet(ProfitSession active)
    {
        if (active == null)
        {
            return 0L;
        }
        List<ProfitTransaction> txs = active.getTransactions();
        if (txs == null || txs.isEmpty())
        {
            return 0L;
        }
        ProfitTransaction last = txs.get(txs.size() - 1);
        if (last == null || last.getType() == com.gpmanager.model.TransactionType.TRANSFER
            || !last.isCounted())
        {
            return 0L;
        }
        if (config.accountingItemFilter() == LootPresentationFilter.ALL_ITEMS
            || last.getFlows() == null || last.getFlows().isEmpty())
        {
            return last.getNet();
        }
        long net = 0L;
        for (com.gpmanager.model.ItemFlow flow : last.getFlows())
        {
            if (flow != null && (lootFilter == null || lootFilter.isFlowIncluded(
                flow, config.accountingItemFilter())))
            {
                net += flow.getValueDelta();
            }
        }
        return net;
    }

    private ProfitTargetPresentation buildTargetPresentation(@Nullable ProfitSession active, long net)
    {
        Long target = active == null ? null : active.getProfitTargetGp();
        if (target == null || target <= 0L)
        {
            clearPreviousTarget();
            return ProfitTargetPresentation.unset();
        }

        String sessionId = active.getId();
        boolean sameTarget = target.equals(previousTargetGp)
            && sameSession(active, sessionId);
        boolean previousReached = sameTarget ? previousTargetReached : net >= target;
        ProfitTargetPresentation presentation = ProfitTargetPresentation.of(target, net, previousReached);
        previousTargetSession = active;
        previousTargetSessionId = sessionId;
        previousTargetGp = target;
        previousTargetReached = presentation.isReached();
        return presentation;
    }

    private boolean sameSession(ProfitSession active, @Nullable String sessionId)
    {
        if (sessionId != null && previousTargetSessionId != null)
        {
            return sessionId.equals(previousTargetSessionId);
        }
        return active == previousTargetSession;
    }

    private void clearPreviousTarget()
    {
        previousTargetSession = null;
        previousTargetSessionId = null;
        previousTargetGp = null;
        previousTargetReached = false;
    }
}
