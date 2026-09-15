package com.gpmanager.ui;

import com.gpmanager.diagnostics.RateAvailability;
import com.gpmanager.grounditems.FilteredRewardView;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.SessionMetrics;
import com.gpmanager.reward.RewardItem;
import com.gpmanager.reward.RewardObservation;
import com.gpmanager.reward.RewardPresentationModel;
import com.gpmanager.reward.RewardPresentationPhase;
import javax.annotation.Nullable;

/**
 * One coherent display snapshot for HUD and Infobox renderers. Built once per
 * render/tick so both presentations agree without repeating engine work.
 *
 * <p>Reward filtering is display-only: {@link #getCompleteReward()} keeps the
 * full observation while {@link #getReward()} exposes the visible projection.</p>
 */
public final class TrackingDisplaySnapshot
{
    private final long net;
    private final long profitPerHour;
    private final long elapsedMillis;
    private final boolean paused;
    private final boolean rateAvailable;
    private final String activity;
    private final String statusLabel;
    private final LatestDropHighlight latestDrop;
    private final RewardObservation reward;
    private final RewardObservation completeReward;
    private final FilteredRewardView filteredReward;
    private final RewardPresentationPhase rewardPhase;
    private final boolean dropAnimating;
    private final float dropAnimationProgress;
    private final long lastChangeNet;
    private final String identityBlockReason;
    @Nullable
    private final ProfitTargetPresentation profitTarget;
    private final InteractionContextModel.View interaction;
    /** Presentation-only: unit-price loot minimum for empty waiting copy. */
    private final long minimumDisplayedLootValue;
    /** When the current reveal/idle dwell ends; 0 if none / settled trip tray. */
    private final long revealExpiresAtEpochMillis;
    /** When the current cassette visual opened (entry motion). */
    private final long revealStartedAtEpochMillis;
    /** Presentation-only: in-game character idle (not session AFK pause). */
    private final boolean characterIdle;
    /** Presentation-only: bank interface open (header Banking, not Idle). */
    private final boolean bankingUiOpen;
    /** True while Ground Loot coalesce window is open (Busy gem). */
    private final boolean lootCoalescing;
    /** True while post-coalesce lock countdown (or AE unpicked hold) is active. */
    private final boolean lootBatchLocked;
    /** True while a named custom session is the active accounting owner. */
    private final boolean customSessionActive;
    /** Active session display name (Overall or custom). */
    private final String sessionName;
    /** Display-only peek of paused Overall while custom is active. */
    private final boolean overallPeekPresent;
    private final long overallNet;
    private final long overallProfitPerHour;
    private final boolean overallRateAvailable;
    /** Presentation-only region state for the title precedence ladder. */
    private final boolean neutralZoneActive;
    /** Process title confirmed by recent positive XP, independent of activity hints. */
    private final String confirmedProcessTitle;
    /** Whether the player is currently in a Wilderness/PvP-capable area. */
    private final boolean pvpPossible;
    /** Measured carried-value risk; null means unavailable or incomplete. */
    @Nullable
    private final WildernessRiskCalculator.Result wildernessRisk;

    public TrackingDisplaySnapshot(
        long net,
        long profitPerHour,
        long elapsedMillis,
        boolean paused,
        boolean rateAvailable,
        String activity,
        String statusLabel,
        LatestDropHighlight latestDrop,
        RewardObservation reward,
        RewardPresentationPhase rewardPhase,
        boolean dropAnimating,
        float dropAnimationProgress,
        long lastChangeNet,
        String identityBlockReason,
        @Nullable ProfitTargetPresentation profitTarget)
    {
        this(
            net,
            profitPerHour,
            elapsedMillis,
            paused,
            rateAvailable,
            activity,
            statusLabel,
            latestDrop,
            reward,
            reward,
            FilteredRewardView.allVisible(reward),
            rewardPhase,
            dropAnimating,
            dropAnimationProgress,
            lastChangeNet,
            identityBlockReason,
            profitTarget);
    }

    public TrackingDisplaySnapshot(
        long net,
        long profitPerHour,
        long elapsedMillis,
        boolean paused,
        boolean rateAvailable,
        String activity,
        String statusLabel,
        LatestDropHighlight latestDrop,
        @Nullable RewardObservation reward,
        @Nullable RewardObservation completeReward,
        @Nullable FilteredRewardView filteredReward,
        RewardPresentationPhase rewardPhase,
        boolean dropAnimating,
        float dropAnimationProgress,
        long lastChangeNet,
        String identityBlockReason,
        @Nullable ProfitTargetPresentation profitTarget)
    {
        this(net, profitPerHour, elapsedMillis, paused, rateAvailable, activity, statusLabel,
            latestDrop, reward, completeReward, filteredReward, rewardPhase, dropAnimating,
            dropAnimationProgress, lastChangeNet, identityBlockReason, profitTarget,
            InteractionContextModel.View.EMPTY, 0L, 0L, 0L, false, false, false, false, false, "", false, 0L, 0L, false, false, "", false, null);
    }

    private TrackingDisplaySnapshot(long net, long profitPerHour, long elapsedMillis, boolean paused,
        boolean rateAvailable, String activity, String statusLabel, LatestDropHighlight latestDrop,
        RewardObservation reward, RewardObservation completeReward, FilteredRewardView filteredReward,
        RewardPresentationPhase rewardPhase, boolean dropAnimating, float dropAnimationProgress,
        long lastChangeNet, String identityBlockReason, ProfitTargetPresentation profitTarget,
        InteractionContextModel.View interaction, long minimumDisplayedLootValue,
        long revealExpiresAtEpochMillis, long revealStartedAtEpochMillis, boolean characterIdle,
        boolean bankingUiOpen, boolean lootCoalescing, boolean lootBatchLocked,
        boolean customSessionActive, String sessionName,
        boolean overallPeekPresent, long overallNet, long overallProfitPerHour,
        boolean overallRateAvailable, boolean neutralZoneActive, String confirmedProcessTitle,
        boolean pvpPossible, @Nullable WildernessRiskCalculator.Result wildernessRisk)
    {
        this.net = net;
        this.profitPerHour = profitPerHour;
        this.elapsedMillis = elapsedMillis;
        this.paused = paused;
        this.rateAvailable = rateAvailable;
        this.activity = activity == null ? "" : activity;
        this.statusLabel = statusLabel == null ? "" : statusLabel;
        this.latestDrop = latestDrop;
        this.reward = reward;
        this.completeReward = completeReward;
        this.filteredReward = filteredReward == null
            ? FilteredRewardView.allVisible(completeReward)
            : filteredReward;
        this.rewardPhase = rewardPhase == null ? RewardPresentationPhase.NONE : rewardPhase;
        this.dropAnimating = dropAnimating;
        this.dropAnimationProgress = dropAnimationProgress;
        this.lastChangeNet = lastChangeNet;
        this.identityBlockReason = identityBlockReason;
        this.profitTarget = profitTarget;
        this.interaction = interaction == null ? InteractionContextModel.View.EMPTY : interaction;
        this.minimumDisplayedLootValue = Math.max(0L, minimumDisplayedLootValue);
        this.revealExpiresAtEpochMillis = Math.max(0L, revealExpiresAtEpochMillis);
        this.revealStartedAtEpochMillis = Math.max(0L, revealStartedAtEpochMillis);
        this.characterIdle = characterIdle;
        this.bankingUiOpen = bankingUiOpen;
        this.lootCoalescing = lootCoalescing;
        this.lootBatchLocked = lootBatchLocked;
        this.customSessionActive = customSessionActive;
        this.sessionName = sessionName == null ? "" : sessionName;
        this.overallPeekPresent = overallPeekPresent;
        this.overallNet = overallNet;
        this.overallProfitPerHour = overallProfitPerHour;
        this.overallRateAvailable = overallRateAvailable;
        this.neutralZoneActive = neutralZoneActive;
        this.confirmedProcessTitle = confirmedProcessTitle == null ? "" : confirmedProcessTitle.trim();
        this.pvpPossible = pvpPossible;
        this.wildernessRisk = wildernessRisk != null && wildernessRisk.isComplete() ? wildernessRisk : null;
    }

    public TrackingDisplaySnapshot withInteraction(InteractionContextModel.View context)
    {
        return new TrackingDisplaySnapshot(net, profitPerHour, elapsedMillis, paused, rateAvailable,
            activity, statusLabel, latestDrop, reward, completeReward, filteredReward, rewardPhase,
            dropAnimating, dropAnimationProgress, lastChangeNet, identityBlockReason, profitTarget,
            context, minimumDisplayedLootValue, revealExpiresAtEpochMillis, revealStartedAtEpochMillis,
            characterIdle, bankingUiOpen, lootCoalescing, lootBatchLocked, customSessionActive, sessionName,
            overallPeekPresent, overallNet, overallProfitPerHour, overallRateAvailable, neutralZoneActive,
            confirmedProcessTitle, pvpPossible, wildernessRisk);
    }

    public TrackingDisplaySnapshot withMinimumDisplayedLootValue(long minimumValue)
    {
        return new TrackingDisplaySnapshot(net, profitPerHour, elapsedMillis, paused, rateAvailable,
            activity, statusLabel, latestDrop, reward, completeReward, filteredReward, rewardPhase,
            dropAnimating, dropAnimationProgress, lastChangeNet, identityBlockReason, profitTarget,
            interaction, minimumValue, revealExpiresAtEpochMillis, revealStartedAtEpochMillis,
            characterIdle, bankingUiOpen, lootCoalescing, lootBatchLocked, customSessionActive, sessionName,
            overallPeekPresent, overallNet, overallProfitPerHour, overallRateAvailable, neutralZoneActive,
            confirmedProcessTitle, pvpPossible, wildernessRisk);
    }

    public TrackingDisplaySnapshot withRevealExpires(long expiresAtEpochMillis)
    {
        return withRevealTiming(expiresAtEpochMillis, revealStartedAtEpochMillis);
    }

    public TrackingDisplaySnapshot withRevealTiming(long expiresAtEpochMillis, long startedAtEpochMillis)
    {
        return new TrackingDisplaySnapshot(net, profitPerHour, elapsedMillis, paused, rateAvailable,
            activity, statusLabel, latestDrop, reward, completeReward, filteredReward, rewardPhase,
            dropAnimating, dropAnimationProgress, lastChangeNet, identityBlockReason, profitTarget,
            interaction, minimumDisplayedLootValue, expiresAtEpochMillis, startedAtEpochMillis,
            characterIdle, bankingUiOpen, lootCoalescing, lootBatchLocked, customSessionActive, sessionName,
            overallPeekPresent, overallNet, overallProfitPerHour, overallRateAvailable, neutralZoneActive,
            confirmedProcessTitle, pvpPossible, wildernessRisk);
    }

    public TrackingDisplaySnapshot withCharacterIdle(boolean idle)
    {
        return new TrackingDisplaySnapshot(net, profitPerHour, elapsedMillis, paused, rateAvailable,
            activity, statusLabel, latestDrop, reward, completeReward, filteredReward, rewardPhase,
            dropAnimating, dropAnimationProgress, lastChangeNet, identityBlockReason, profitTarget,
            interaction, minimumDisplayedLootValue, revealExpiresAtEpochMillis, revealStartedAtEpochMillis,
            idle, bankingUiOpen, lootCoalescing, lootBatchLocked, customSessionActive, sessionName,
            overallPeekPresent, overallNet, overallProfitPerHour, overallRateAvailable, neutralZoneActive,
            confirmedProcessTitle, pvpPossible, wildernessRisk);
    }

    public TrackingDisplaySnapshot withBankingUiOpen(boolean banking)
    {
        return new TrackingDisplaySnapshot(net, profitPerHour, elapsedMillis, paused, rateAvailable,
            activity, statusLabel, latestDrop, reward, completeReward, filteredReward, rewardPhase,
            dropAnimating, dropAnimationProgress, lastChangeNet, identityBlockReason, profitTarget,
            interaction, minimumDisplayedLootValue, revealExpiresAtEpochMillis, revealStartedAtEpochMillis,
            characterIdle, banking, lootCoalescing, lootBatchLocked, customSessionActive, sessionName,
            overallPeekPresent, overallNet, overallProfitPerHour, overallRateAvailable, neutralZoneActive,
            confirmedProcessTitle, pvpPossible, wildernessRisk);
    }

    public TrackingDisplaySnapshot withLootCoalescing(boolean coalescing)
    {
        return new TrackingDisplaySnapshot(net, profitPerHour, elapsedMillis, paused, rateAvailable,
            activity, statusLabel, latestDrop, reward, completeReward, filteredReward, rewardPhase,
            dropAnimating, dropAnimationProgress, lastChangeNet, identityBlockReason, profitTarget,
            interaction, minimumDisplayedLootValue, revealExpiresAtEpochMillis, revealStartedAtEpochMillis,
            characterIdle, bankingUiOpen, coalescing, lootBatchLocked, customSessionActive, sessionName,
            overallPeekPresent, overallNet, overallProfitPerHour, overallRateAvailable, neutralZoneActive,
            confirmedProcessTitle, pvpPossible, wildernessRisk);
    }

    public boolean isLootCoalescing()
    {
        return lootCoalescing;
    }

    public TrackingDisplaySnapshot withLootBatchLocked(boolean locked)
    {
        return new TrackingDisplaySnapshot(net, profitPerHour, elapsedMillis, paused, rateAvailable,
            activity, statusLabel, latestDrop, reward, completeReward, filteredReward, rewardPhase,
            dropAnimating, dropAnimationProgress, lastChangeNet, identityBlockReason, profitTarget,
            interaction, minimumDisplayedLootValue, revealExpiresAtEpochMillis, revealStartedAtEpochMillis,
            characterIdle, bankingUiOpen, lootCoalescing, locked, customSessionActive, sessionName,
            overallPeekPresent, overallNet, overallProfitPerHour, overallRateAvailable, neutralZoneActive,
            confirmedProcessTitle, pvpPossible, wildernessRisk);
    }

    public boolean isLootBatchLocked()
    {
        return lootBatchLocked;
    }

    /** Coalesce window or post-lock / AE unpicked hold — Busy gem + soft-busy Idle. */
    public boolean isLootTrayBusy()
    {
        return lootCoalescing || lootBatchLocked;
    }

    public TrackingDisplaySnapshot withSessionChrome(
        boolean customActive,
        String activeSessionName,
        boolean peekPresent,
        long peekNet,
        long peekProfitPerHour,
        boolean peekRateAvailable)
    {
        return new TrackingDisplaySnapshot(net, profitPerHour, elapsedMillis, paused, rateAvailable,
            activity, statusLabel, latestDrop, reward, completeReward, filteredReward, rewardPhase,
            dropAnimating, dropAnimationProgress, lastChangeNet, identityBlockReason, profitTarget,
            interaction, minimumDisplayedLootValue, revealExpiresAtEpochMillis, revealStartedAtEpochMillis,
            characterIdle, bankingUiOpen, lootCoalescing, lootBatchLocked, customActive,
            activeSessionName == null ? "" : activeSessionName,
            peekPresent, peekNet, peekProfitPerHour, peekRateAvailable, neutralZoneActive, confirmedProcessTitle, pvpPossible, wildernessRisk);
    }

    public TrackingDisplaySnapshot withNeutralZoneActive(boolean active)
    {
        return new TrackingDisplaySnapshot(net, profitPerHour, elapsedMillis, paused, rateAvailable,
            activity, statusLabel, latestDrop, reward, completeReward, filteredReward, rewardPhase,
            dropAnimating, dropAnimationProgress, lastChangeNet, identityBlockReason, profitTarget,
            interaction, minimumDisplayedLootValue, revealExpiresAtEpochMillis, revealStartedAtEpochMillis,
            characterIdle, bankingUiOpen, lootCoalescing, lootBatchLocked, customSessionActive, sessionName,
            overallPeekPresent, overallNet, overallProfitPerHour, overallRateAvailable, active,
            confirmedProcessTitle, pvpPossible, wildernessRisk);
    }

    public TrackingDisplaySnapshot withConfirmedProcessTitle(String title)
    {
        return new TrackingDisplaySnapshot(net, profitPerHour, elapsedMillis, paused, rateAvailable,
            activity, statusLabel, latestDrop, reward, completeReward, filteredReward, rewardPhase,
            dropAnimating, dropAnimationProgress, lastChangeNet, identityBlockReason, profitTarget,
            interaction, minimumDisplayedLootValue, revealExpiresAtEpochMillis, revealStartedAtEpochMillis,
            characterIdle, bankingUiOpen, lootCoalescing, lootBatchLocked, customSessionActive, sessionName,
            overallPeekPresent, overallNet, overallProfitPerHour, overallRateAvailable, neutralZoneActive,
            title, pvpPossible, wildernessRisk);
    }

    /** Adds live PvP risk presentation data without changing accounting state. */
    public TrackingDisplaySnapshot withWildernessRisk(
        boolean pvpPossible,
        @Nullable WildernessRiskCalculator.Result risk)
    {
        return new TrackingDisplaySnapshot(net, profitPerHour, elapsedMillis, paused, rateAvailable,
            activity, statusLabel, latestDrop, reward, completeReward, filteredReward, rewardPhase,
            dropAnimating, dropAnimationProgress, lastChangeNet, identityBlockReason, profitTarget,
            interaction, minimumDisplayedLootValue, revealExpiresAtEpochMillis, revealStartedAtEpochMillis,
            characterIdle, bankingUiOpen, lootCoalescing, lootBatchLocked, customSessionActive, sessionName,
            overallPeekPresent, overallNet, overallProfitPerHour, overallRateAvailable, neutralZoneActive,
            confirmedProcessTitle, pvpPossible, risk);
    }

    public boolean isPvpPossible()
    {
        return pvpPossible;
    }

    public boolean hasKnownWildernessRisk()
    {
        return wildernessRisk != null;
    }

    @Nullable
    public WildernessRiskCalculator.Result getWildernessRisk()
    {
        return wildernessRisk;
    }

    public boolean isCustomSessionActive()
    {
        return customSessionActive;
    }

    public String getSessionName()
    {
        return sessionName;
    }

    public boolean isOverallPeekPresent()
    {
        return overallPeekPresent;
    }

    public long getOverallNet()
    {
        return overallNet;
    }

    public long getOverallProfitPerHour()
    {
        return overallProfitPerHour;
    }

    public boolean isOverallRateAvailable()
    {
        return overallRateAvailable;
    }

    public InteractionContextModel.View getInteraction() { return interaction; }

    public long getMinimumDisplayedLootValue()
    {
        return minimumDisplayedLootValue;
    }

    public long getRevealExpiresAtEpochMillis()
    {
        return revealExpiresAtEpochMillis;
    }

    public long getRevealStartedAtEpochMillis()
    {
        return revealStartedAtEpochMillis;
    }

    public boolean isCharacterIdle()
    {
        return characterIdle;
    }

    public boolean isBankingUiOpen()
    {
        return bankingUiOpen;
    }

    public boolean isNeutralZoneActive()
    {
        return neutralZoneActive;
    }

    public String getConfirmedProcessTitle()
    {
        return confirmedProcessTitle;
    }

    /**
     * Whole seconds remaining until Auto-collapse / peer-flash clear; 0 when no
     * finite dwell (Always Expanded settled trip tray).
     */
    public int dwellSecondsRemaining(long now)
    {
        if (revealExpiresAtEpochMillis <= 0L
            || revealExpiresAtEpochMillis >= Long.MAX_VALUE / 8
            || rewardPhase != RewardPresentationPhase.REVEALING)
        {
            return 0;
        }
        long remaining = revealExpiresAtEpochMillis - now;
        if (remaining <= 0L)
        {
            return 0;
        }
        return (int) Math.max(1L, (remaining + 999L) / 1000L);
    }

    public String getDisplayActivity()
    {
        if (interaction.isPresent()) return interaction.getLabel();
        return completeReward != null && (activity.isEmpty() || "Tracking".equals(activity))
            ? completeReward.displaySourceLabel() : activity;
    }

    /**
     * Former "From {source}" tray caption. Removed — HUD+ state tags
     * ({@code Received}, {@code Chopped:}, etc.) carry that context.
     */
    public String getRewardContextLabel()
    {
        return "";
    }

    public static TrackingDisplaySnapshot from(
        SessionMetrics metrics,
        String activity,
        String statusLabel,
        RewardPresentationModel rewardModel,
        long now,
        long revealDwellMillis,
        long lastChangeNet,
        String identityBlockReason,
        @Nullable ProfitTargetPresentation profitTarget)
    {
        return from(
            metrics,
            activity,
            statusLabel,
            rewardModel,
            null,
            now,
            revealDwellMillis,
            lastChangeNet,
            identityBlockReason,
            profitTarget);
    }

    public static TrackingDisplaySnapshot from(
        SessionMetrics metrics,
        String activity,
        String statusLabel,
        RewardPresentationModel rewardModel,
        @Nullable FilteredRewardView filteredView,
        long now,
        long revealDwellMillis,
        long lastChangeNet,
        String identityBlockReason,
        @Nullable ProfitTargetPresentation profitTarget)
    {
        if (rewardModel != null)
        {
            rewardModel.tick(now);
        }
        RewardObservation complete = rewardModel == null ? null : rewardModel.currentForHud(now);
        FilteredRewardView filtered = filteredView == null
            ? FilteredRewardView.allVisible(complete)
            : filteredView;
        RewardObservation display = filtered.asDisplayObservation();
        // All-filtered: keep phase for coherence but hide item tray.
        RewardPresentationPhase phase = rewardModel == null
            ? RewardPresentationPhase.NONE
            : (filtered.isAllFiltered() ? RewardPresentationPhase.NONE : rewardModel.phase());
        boolean revealing = !filtered.isAllFiltered()
            && rewardModel != null
            && rewardModel.isRevealing(now);
        float progress = rewardModel == null
            ? 1f
            : rewardModel.revealProgress(now, revealDwellMillis);
        long expires = rewardModel == null ? 0L : rewardModel.getRevealExpiresAtEpochMillis();
        long started = rewardModel == null ? 0L : rewardModel.getRevealStartedAtEpochMillis();
        LatestDropHighlight drop = toHighlight(filtered);
        boolean rateOk = metrics != null && RateAvailability.isEstablished(metrics.getElapsedMillis());
        return new TrackingDisplaySnapshot(
            metrics == null ? 0L : metrics.getNet(),
            metrics == null ? 0L : metrics.getProfitPerHour(),
            metrics == null ? 0L : metrics.getElapsedMillis(),
            metrics != null && metrics.isPaused(),
            rateOk,
            activity,
            statusLabel,
            drop,
            display,
            complete,
            filtered,
            phase,
            revealing,
            progress,
            lastChangeNet,
            identityBlockReason,
            profitTarget).withRevealTiming(expires, started)
            .withLootCoalescing(rewardModel != null && rewardModel.isLootCoalescing())
            .withLootBatchLocked(rewardModel != null && rewardModel.isLootBatchLocked());
    }

    private static LatestDropHighlight toHighlight(FilteredRewardView filtered)
    {
        if (filtered == null || !filtered.hasVisibleItems())
        {
            return null;
        }
        RewardItem best = filtered.getSelectedVisibleItem();
        RewardObservation complete = filtered.getObservation();
        if (best == null || complete == null)
        {
            return null;
        }
        return new LatestDropHighlight(
            best.getItemId(),
            best.getItemName(),
            best.getQuantity(),
            best.getRecordedValue(),
            best.isValueKnown(),
            best.getPriceSource() == null ? ItemPriceSource.UNKNOWN : best.getPriceSource(),
            complete.getEncounterId(),
            complete.getRewardId(),
            complete.getObservedAtEpochMillis(),
            complete.getGeneration());
    }

    public long getNet()
    {
        return net;
    }

    public long getProfitPerHour()
    {
        return profitPerHour;
    }

    public long getElapsedMillis()
    {
        return elapsedMillis;
    }

    public boolean isPaused()
    {
        return paused;
    }

    public boolean isRateAvailable()
    {
        return rateAvailable;
    }

    public String getActivity()
    {
        return activity;
    }

    public String getStatusLabel()
    {
        return statusLabel;
    }

    public LatestDropHighlight getLatestDrop()
    {
        return latestDrop;
    }

    /** Visible reward projection for trays/rows; null when absent or all-filtered. */
    public RewardObservation getReward()
    {
        return reward;
    }

    /** Complete unfiltered observation; accounting identity stays here. */
    @Nullable
    public RewardObservation getCompleteReward()
    {
        return completeReward;
    }

    public FilteredRewardView getFilteredReward()
    {
        return filteredReward;
    }

    public RewardPresentationPhase getRewardPhase()
    {
        return rewardPhase;
    }

    public boolean isDropAnimating()
    {
        return dropAnimating;
    }

    public float getDropAnimationProgress()
    {
        return dropAnimationProgress;
    }

    public long getLastChangeNet()
    {
        return lastChangeNet;
    }

    public String getIdentityBlockReason()
    {
        return identityBlockReason;
    }

    /** Shared profit-target view model; null when no target is configured. */
    @Nullable
    public ProfitTargetPresentation getProfitTarget()
    {
        return profitTarget;
    }
}
