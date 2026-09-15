package com.gpmanager.reward;

import javax.annotation.Nullable;
import com.gpmanager.HudPlusAccumulationMode;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.KeyChestCatalogue;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TransactionCorrection;
import com.gpmanager.model.TransactionType;
import com.gpmanager.ui.HudTrayState;
import com.gpmanager.ui.HudPlusProcessLabels;
import com.gpmanager.ui.ProcessSkillSignals;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Singleton;

/**
 * Bounded display-only reward state: source observations, reveal → settled
 * lifecycle, skilling streaks, and dedupe across NPC / ServerNpc / Loot
 * Tracker adapters. Never mutates Net profit.
 */
@Singleton
public class RewardPresentationModel
{
    public static final long REVEAL_DWELL_MILLIS = 3_200L;
    public static final long DEDUPE_WINDOW_MILLIS = 2_500L;
    /** Nearby same-adapter encounters coalesce into one readable batch. */
    public static final long BATCH_WINDOW_MILLIS = 1_400L;
    /** Continuous combat cannot postpone batch settlement forever. */
    public static final long BATCH_MAX_DWELL_MILLIS = 4_000L;
    public static final int MAX_REVEAL_ICONS = 6;
    /** Bounded peer-flash queue so Deposit/Withdrew/Traded do not overwrite each other. */
    public static final int MAX_FLASH_QUEUE = 5;
    private static final int MAX_RECENT_KEYS = 24;

    private final AtomicInteger generationCounter = new AtomicInteger();
    private final Map<String, Long> recentDedupeKeys = new HashMap<>();
    private final SessionRewardAccumulator accumulator = new SessionRewardAccumulator();
    private final Deque<QueuedTrayFlash> flashQueue = new ArrayDeque<>();
    /** Optional engine bridge, installed by the client flow without coupling reward paint to Net. */
    private EncounterObserver encounterObserver;

    private RewardObservation current;
    private RewardPresentationPhase phase = RewardPresentationPhase.NONE;
    private long revealExpiresAtEpochMillis;
    /** Clock when the current cassette visual opened (entry motion). */
    private long revealStartedAtEpochMillis;
    private int replacementCount;
    private int revealStartCount;
    private String ownerScopeKey = "";
    private boolean suppressReveals;
    private boolean pinnedExpanded;
    /**
     * When true, Always Expanded clears this card after dwell (Banked / Recovered /
     * one-shot Dropped). False for persistent SKILLING / accumulated Used trip cards.
     */
    private boolean oneShotBriefFlash;
    private long revealDwellMillis = REVEAL_DWELL_MILLIS;
    private HudPlusAccumulationMode accumulationMode = HudPlusAccumulationMode.SESSION;
    private long streakInactivityMillis;
    private long batchOpenedAtEpochMillis;
    /** Per-encounter fingerprint used for cross-adapter dedupe and batching. */
    private String batchUnitFingerprint = "";
    /** Last observed clock — layout toggles use this when callers omit {@code now}. */
    private long lastKnownNow;

    /** Receives only an encounter already accepted by the model's dedupe path. */
    @FunctionalInterface
    public interface EncounterObserver
    {
        void onEncounter(String sourceName, long multiplicity, long lootValue,
            boolean lootValueKnown, long currentStreak, long observedAtEpochMillis);
    }

    /** Installs the engine bridge used by the future client ingestion wiring. */
    public synchronized void setEncounterObserver(@Nullable EncounterObserver observer)
    {
        this.encounterObserver = observer;
    }

    /**
     * Process spends (Firemaking light, Prayer bury, …) wait for XP before
     * painting Burned/Offered so inventory removal alone does not flash Used.
     * When XP lands before invent settles (common for bury), latch the XP so the
     * later invent delta paints once — never Used→Buried double flash.
     */
    public static final long PROCESS_SPEND_WAIT_MILLIS = 4_000L;
    /** ~600ms per game tick — PRODUCTION correlation window countdown. */
    public static final long MILLIS_PER_GAME_TICK = 600L;
    private String processSpendSkill = "";
    private int processSpendItemId = -1;
    private long processSpendIntentAtEpochMillis;
    /** Prayer only: bury | scatter | offer — drives Buried/Scattered/Offered tray. */
    private String processSpendMode = "";
    /**
     * XP for an armed process spend arrived before invent stacks. Invent settle
     * paints immediately instead of waiting out another XP window.
     */
    private long processSpendXpConfirmedAtEpochMillis;
    private String processSpendXpConfirmedSkill = "";
    /**
     * Mode retained after a Used timeout so a late XP drop can still upgrade to
     * Buried/Scattered/Offered instead of generic Prayer→Offered.
     */
    private String processSpendUpgradeMode = "";
    private ProfitTransaction pendingProcessSpend;
    private List<RewardItem> pendingProcessSpendStacks;
    private long pendingProcessSpendAtEpochMillis;
    private boolean pendingProcessSpendAnimate;
    /** When PRODUCTION is armed, HUD countdown targets this deadline. */
    private long productionCountdownExpiresAtEpochMillis;
    /**
     * Auto-collapse: keep skilling/process cassette open while character is busy
     * (not Idle). Cleared by the plugin when Character Idle fires.
     */
    private boolean activityHold;
    /**
     * Always Expanded: after a one-shot Dropped/Traded/Recovered flash, restore this
     * stripped/preserved trip card when the flash dwell ends.
     */
    private RewardObservation restoreAfterFlash;
    /**
     * Withdraws while the bank UI is open accumulate here and only flash Withdrew
     * when the bank closes — mid-banking noise stays off the tray.
     */
    private List<RewardItem> pendingBankWithdrawStacks = new ArrayList<>();
    private boolean pendingBankWithdrawAnimate = true;
    private boolean bankUiOpen;
    /** When false, Deposit/Withdrew tray flashes are suppressed (Net unchanged). */
    private boolean showBankingFlashes = true;

    /** Same-NPC Ground Loot coalesce window in game ticks (config). */
    private int lootCoalesceTicks = 2;
    /** True while same-source kills may still merge before countdown. */
    private boolean lootCoalescing;
    /** True after coalesce locks — later kills queue, tray not interrupted. */
    private boolean lootBatchLocked;
    private long coalesceOpenedAtEpochMillis;
    private long coalesceQuietUntilEpochMillis;
    /** One pending observed-loot burst while locked countdown runs. */
    private RewardSourceKind pendingLootKind;
    private String pendingLootSourceName = "";
    private String pendingLootEncounterId = "";
    private List<RewardItem> pendingLootStacks;
    private int pendingLootMultiplicity = 1;
    /** True while dequeuing a pending burst so streak totals are not double-counted. */
    private boolean openingPendingLoot;

    /**
     * Always Expanded only: confirmed skilling/process stacks across skills for the
     * current owned scope. Not used for Auto-collapse, Ground Loot, or NPC Session/Streak.
     */
    private List<RewardItem> aeSessionReceipts = new ArrayList<>();
    /** Distinct skill labels that contributed to {@link #aeSessionReceipts}. */
    private final Set<String> aeSessionSkills = new LinkedHashSet<>();

    public synchronized void setOwnerScope(String ownerScopeKey)
    {
        String next = ownerScopeKey == null ? "" : ownerScopeKey;
        if (!next.equals(this.ownerScopeKey))
        {
            clear();
            this.ownerScopeKey = next;
        }
    }

    public synchronized void setSuppressReveals(boolean suppressReveals)
    {
        this.suppressReveals = suppressReveals;
    }

    /**
     * Layout flag only: Always expanded vs Auto-collapse. Does not own the
     * reveal clock — enabling must not invent an infinite deadline, and
     * disabling must restore a finite countdown when a tray is showing.
     */
    public synchronized void setPinnedExpanded(boolean pinnedExpanded)
    {
        setPinnedExpanded(pinnedExpanded, lastKnownNow > 0L ? lastKnownNow : System.currentTimeMillis());
    }

    public synchronized void setPinnedExpanded(boolean pinnedExpanded, long now)
    {
        lastKnownNow = Math.max(lastKnownNow, now);
        boolean wasPinned = this.pinnedExpanded;
        if (wasPinned == pinnedExpanded)
        {
            // Settings refreshes must not restart reveal animations.
            return;
        }
        this.pinnedExpanded = pinnedExpanded;
        if (pinnedExpanded)
        {
            seedAeSessionReceiptsFromCurrent();
            if (current == null || !current.getSourceKind().supportsExpandedTray())
            {
                return;
            }
            // Open the list immediately via layout; keep any finite reveal clock.
            if (phase == RewardPresentationPhase.NONE)
            {
                phase = RewardPresentationPhase.SETTLED;
            }
            if (revealExpiresAtEpochMillis >= Long.MAX_VALUE / 8)
            {
                revealExpiresAtEpochMillis = now + Math.max(400L, revealDwellMillis);
            }
        }
        else
        {
            // Leaving Always expanded: bag ends; keep live card on a finite countdown.
            clearAeSessionReceipts();
            if (current == null || !current.getSourceKind().supportsExpandedTray())
            {
                return;
            }
            beginRevealWithDwell(now, revealDwellMillis);
        }
    }

    public synchronized boolean isPinnedExpanded()
    {
        return pinnedExpanded;
    }

    /** Configured HUD+ reveal dwell; used by beginReveal and progress math. */
    public synchronized void setRevealDwellMillis(long dwellMillis)
    {
        this.revealDwellMillis = Math.max(400L, dwellMillis);
    }

    /** Same-NPC Ground Loot coalesce window in game ticks (1–5). */
    public synchronized void setLootCoalesceTicks(int ticks)
    {
        this.lootCoalesceTicks = Math.max(1, Math.min(5, ticks));
    }

    public synchronized boolean isLootCoalescing()
    {
        return lootCoalescing
            && current != null
            && current.getSourceKind() != null
            && current.getSourceKind().isObservedLoot();
    }

    public synchronized boolean isLootBatchLocked()
    {
        return lootBatchLocked
            && current != null
            && current.getSourceKind() != null
            && current.getSourceKind().isObservedLoot();
    }

    private long coalesceWindowMillis()
    {
        return lootCoalesceTicks * MILLIS_PER_GAME_TICK;
    }

    private long coalesceMaxMillis()
    {
        int factor = Math.max(3, lootCoalesceTicks * 3);
        return factor * MILLIS_PER_GAME_TICK;
    }

    /**
     * True while Ground Loot coalesce, post-lock countdown, or Always Expanded
     * unpicked observed loot is still on the cassette.
     */
    private boolean isProtectedObservedLootTray()
    {
        if (current == null
            || current.getSourceKind() == null
            || !current.getSourceKind().isObservedLoot())
        {
            return false;
        }
        if (lootCoalescing || lootBatchLocked)
        {
            return true;
        }
        // AE: settled unpicked Ground Loot stays painted — later kills queue.
        return pinnedExpanded
            && current.collectionStatus()
                == com.gpmanager.reward.CollectionStatus.UNCONFIRMED;
    }


    public synchronized void setHudPlusAccumulation(
        HudPlusAccumulationMode mode,
        int streakInactivityMinutes)
    {
        accumulationMode = mode == null ? HudPlusAccumulationMode.SESSION : mode;
        streakInactivityMillis = Math.max(0, Math.min(60, streakInactivityMinutes)) * 60_000L;
    }

    public synchronized long getRevealDwellMillis()
    {
        return revealDwellMillis;
    }

    public synchronized void clear()
    {
        current = null;
        phase = RewardPresentationPhase.NONE;
        revealExpiresAtEpochMillis = 0L;
        revealStartedAtEpochMillis = 0L;
        batchOpenedAtEpochMillis = 0L;
        batchUnitFingerprint = "";
        oneShotBriefFlash = false;
        clearProcessSpendPending();
        clearProcessSpendIntent();
        clearProcessSpendXpLatch();
        productionCountdownExpiresAtEpochMillis = 0L;
        activityHold = false;
        restoreAfterFlash = null;
        flashQueue.clear();
        clearPendingBankWithdraw();
        bankUiOpen = false;
        resetLootCoalesceState();
        clearPendingLoot();
        clearAeSessionReceipts();
        accumulator.clear();
        generationCounter.incrementAndGet();
    }

    /**
     * Bank interface visibility for tray presentation. While open, withdraws are
     * buffered and only paint Withdrew when the bank closes. Does not affect Net.
     */
    public synchronized void setBankUiOpen(boolean open)
    {
        boolean wasOpen = this.bankUiOpen;
        this.bankUiOpen = open;
        if (wasOpen && !open)
        {
            long now = lastKnownNow > 0L ? lastKnownNow : System.currentTimeMillis();
            flushPendingBankWithdraw(now);
        }
    }

    public synchronized boolean isBankUiOpen()
    {
        return bankUiOpen;
    }

    /** Deposit/Withdrew tray flashes. Default on; Net never depends on this. */
    public synchronized void setShowBankingFlashes(boolean showBankingFlashes)
    {
        this.showBankingFlashes = showBankingFlashes;
        if (!showBankingFlashes)
        {
            clearPendingBankWithdraw();
        }
    }

    public synchronized boolean isShowBankingFlashes()
    {
        return showBankingFlashes;
    }

    private void clearPendingBankWithdraw()
    {
        pendingBankWithdrawStacks = new ArrayList<>();
        pendingBankWithdrawAnimate = true;
    }

    private void bufferBankWithdraw(List<RewardItem> withdrawn, boolean animate)
    {
        if (withdrawn == null || withdrawn.isEmpty())
        {
            return;
        }
        List<RewardItem> merged = new ArrayList<>(pendingBankWithdrawStacks);
        merged.addAll(withdrawn);
        pendingBankWithdrawStacks = RewardObservation.mergeStacks(merged);
        pendingBankWithdrawAnimate |= animate;
    }

    private void flushPendingBankWithdraw(long now)
    {
        if (pendingBankWithdrawStacks == null || pendingBankWithdrawStacks.isEmpty())
        {
            return;
        }
        List<RewardItem> stacks = pendingBankWithdrawStacks;
        boolean animate = pendingBankWithdrawAnimate;
        clearPendingBankWithdraw();
        if (!showBankingFlashes)
        {
            return;
        }
        offerWithdrewFlash(stacks, now, animate);
    }

    /**
     * When true, skilling/process live cards stay open under Auto-collapse
     * (dwell does not clear). Driven by Character Idle — Idle releases hold, then
     * Tray dwell starts. AFK pause never folds the tray.
     */
    public synchronized void setActivityHold(boolean activityHold)
    {
        boolean wasHold = this.activityHold;
        this.activityHold = activityHold;
        long now = lastKnownNow > 0L ? lastKnownNow : System.currentTimeMillis();
        if (activityHold
            && current != null
            && isActivityHoldCard(current)
            && !isBriefPeerFlash(current.getSourceKind()))
        {
            phase = RewardPresentationPhase.REVEALING;
            // Freeze countdown — blank right label + Busy gem pulse (no LIVE text).
            revealExpiresAtEpochMillis = Long.MAX_VALUE / 4;
        }
        else if (wasHold && !activityHold
            && current != null
            && isActivityHoldCard(current))
        {
            phase = RewardPresentationPhase.REVEALING;
            scheduleAutoCollapseDwell(now);
        }
    }

    /** Gather / process / Mixed cards participate in Idle-linked activity-hold. */
    public static boolean isActivityHoldCard(RewardObservation reward)
    {
        if (reward == null || reward.getSourceKind() == null)
        {
            return false;
        }
        RewardSourceKind kind = reward.getSourceKind();
        if (kind.isSkilling())
        {
            return true;
        }
        if (kind == RewardSourceKind.USED || kind == RewardSourceKind.LOST)
        {
            return HudTrayState.isProcessSpendActivity(reward.getSourceName())
                || HudPlusProcessLabels.isProcessTitle(reward.getSourceName());
        }
        return HudTrayState.resolve(reward) == HudTrayState.MIXED
            || HudPlusProcessLabels.isProcessTitle(reward.getSourceName())
            || HudTrayState.isProcessSpendActivity(reward.getSourceName());
    }

    public synchronized boolean isActivityHold()
    {
        return activityHold;
    }

    /**
     * Arms the HUD countdown for an open PRODUCTION / process window (correlation
     * ticks × {@link #MILLIS_PER_GAME_TICK}). Extends a visible process card's dwell.
     */
    public synchronized void beginProductionCountdown(long now, long durationMillis)
    {
        lastKnownNow = Math.max(lastKnownNow, now);
        long duration = Math.max(400L, durationMillis);
        productionCountdownExpiresAtEpochMillis = now + duration;
        if (current != null && current.getSourceKind().isSkilling())
        {
            phase = RewardPresentationPhase.REVEALING;
            revealExpiresAtEpochMillis = Math.max(revealExpiresAtEpochMillis,
                productionCountdownExpiresAtEpochMillis);
        }
    }

    /** Refresh PRODUCTION countdown on reinforce / matching process XP. */
    public synchronized void refreshProductionCountdown(long now, long durationMillis)
    {
        beginProductionCountdown(now, durationMillis);
    }

    /**
     * Menu armed a process spend (Light logs, Bury, …). Matching inventory
     * losses wait for {@link #confirmProcessSpendXp} before Burned/Offered.
     */
    public synchronized void noteProcessSpendIntent(String skillName, int itemId, long now)
    {
        noteProcessSpendIntent(skillName, itemId, now, "");
    }

    /**
     * @param mode Prayer tray mode wire name ({@code bury}/{@code scatter}/{@code offer}),
     *             or empty for non-Prayer / default Offered
     */
    public synchronized void noteProcessSpendIntent(
        String skillName, int itemId, long now, String mode)
    {
        if (skillName == null || skillName.trim().isEmpty())
        {
            return;
        }
        if (!HudTrayState.isProcessSpendActivity(skillName.trim()))
        {
            return;
        }
        String skill = skillName.trim();
        String nextMode = mode == null ? "" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        // Same arm already live — refresh deadline only, skip redundant field churn.
        if (skill.equalsIgnoreCase(processSpendSkill)
            && itemId == processSpendItemId
            && isProcessSpendIntentArmed(now))
        {
            lastKnownNow = Math.max(lastKnownNow, now);
            processSpendIntentAtEpochMillis = now;
            if (!nextMode.isEmpty())
            {
                processSpendMode = nextMode;
            }
            beginProductionCountdown(now, PROCESS_SPEND_WAIT_MILLIS);
            return;
        }
        lastKnownNow = Math.max(lastKnownNow, now);
        processSpendSkill = skill;
        processSpendItemId = itemId;
        processSpendIntentAtEpochMillis = now;
        processSpendMode = nextMode;
        beginProductionCountdown(now, PROCESS_SPEND_WAIT_MILLIS);
    }

    /**
     * XP drop for a process skill — promote pending / recent spend to Burned etc.
     * Bury/scatter often grant Prayer XP before invent stabilizes; latch that XP so
     * the later invent delta paints Buried once (no Used timeout flash).
     */
    public synchronized void confirmProcessSpendXp(String skillName, long now, boolean animate)
    {
        if (skillName == null || skillName.trim().isEmpty())
        {
            return;
        }
        String skill = skillName.trim();
        if (!HudTrayState.isProcessSpendActivity(skill))
        {
            return;
        }
        lastKnownNow = Math.max(lastKnownNow, now);
        String traySource = processSpendTraySource(skill);
        if (pendingProcessSpend != null && pendingProcessSpendStacks != null
            && !pendingProcessSpendStacks.isEmpty()
            && (processSpendSkill.isEmpty() || processSpendSkill.equalsIgnoreCase(skill)))
        {
            offerProcessObservation(traySource, pendingProcessSpendStacks, now, pendingProcessSpendAnimate);
            clearProcessSpendPending();
            clearProcessSpendIntent();
            clearProcessSpendXpLatch();
            return;
        }
        // Upgrade a premature generic Used flash only when its stacks belong to this
        // process skill. An unrelated food consume may settle just before a delayed XP
        // event; XP alone must never repaint that item as Burned/Buried/etc.
        if (current != null
            && current.getSourceKind() == RewardSourceKind.USED
            && "Used".equalsIgnoreCase(current.getSourceName())
            && currentUsedStacksMatchProcessSpend(skill, current)
            && now - current.getObservedAtEpochMillis() <= PROCESS_SPEND_WAIT_MILLIS)
        {
            current = current.withSourceKindAndName(RewardSourceKind.SKILLING, traySource);
            oneShotBriefFlash = false;
            phase = RewardPresentationPhase.REVEALING;
            scheduleProcessRevealDwell(now);
            clearProcessSpendIntent();
            clearProcessSpendXpLatch();
            return;
        }
        // Invent not settled yet — remember XP and extend the armed wait window.
        if (processSpendSkill.isEmpty() || processSpendSkill.equalsIgnoreCase(skill))
        {
            processSpendXpConfirmedAtEpochMillis = now;
            processSpendXpConfirmedSkill = skill;
            if (!processSpendSkill.isEmpty() && processSpendSkill.equalsIgnoreCase(skill))
            {
                processSpendIntentAtEpochMillis = now;
                beginProductionCountdown(now, PROCESS_SPEND_WAIT_MILLIS);
            }
        }
    }

    private String processSpendTraySource(String skill)
    {
        String mode = !processSpendMode.isEmpty() ? processSpendMode : processSpendUpgradeMode;
        if (skill != null && "prayer".equalsIgnoreCase(skill.trim()) && !mode.isEmpty())
        {
            return ProcessSkillSignals.PrayerSpendMode.fromWire(mode).traySourceName();
        }
        return skill == null ? "" : skill.trim();
    }

    private void clearProcessSpendPending()
    {
        pendingProcessSpend = null;
        pendingProcessSpendStacks = null;
        pendingProcessSpendAtEpochMillis = 0L;
        pendingProcessSpendAnimate = false;
    }

    private void clearProcessSpendXpLatch()
    {
        processSpendXpConfirmedAtEpochMillis = 0L;
        processSpendXpConfirmedSkill = "";
        processSpendUpgradeMode = "";
    }

    private boolean isProcessSpendXpConfirmed(String skill, long now)
    {
        if (skill == null || skill.trim().isEmpty()
            || processSpendXpConfirmedSkill.isEmpty()
            || processSpendXpConfirmedAtEpochMillis <= 0L)
        {
            return false;
        }
        return processSpendXpConfirmedSkill.equalsIgnoreCase(skill.trim())
            && now - processSpendXpConfirmedAtEpochMillis < PROCESS_SPEND_WAIT_MILLIS;
    }

    /** Clears armed process-spend intent (e.g. Make-X switches Cooking ↔ Firemaking). */
    public synchronized void clearProcessSpendIntent()
    {
        processSpendSkill = "";
        processSpendItemId = -1;
        processSpendIntentAtEpochMillis = 0L;
        processSpendMode = "";
    }

    /** Armed process-spend skill name, or empty when none. */
    public synchronized String getArmedProcessSpendSkill()
    {
        return processSpendSkill == null ? "" : processSpendSkill;
    }

    public synchronized void tick(long now)
    {
        lastKnownNow = Math.max(lastKnownNow, now);
        pruneDedupe(now);
        if (pendingProcessSpend != null
            && pendingProcessSpendAtEpochMillis > 0L
            && now - pendingProcessSpendAtEpochMillis >= PROCESS_SPEND_WAIT_MILLIS)
        {
            // No XP in time — fall back to Used so the spend is not invisible.
            // Keep bury/scatter/offer mode so a late XP drop can still upgrade the tag.
            if (!processSpendMode.isEmpty())
            {
                processSpendUpgradeMode = processSpendMode;
            }
            flashLoss(RewardSourceKind.USED, "Used", pendingProcessSpendStacks, now,
                pendingProcessSpendAnimate, true);
            clearProcessSpendPending();
            clearProcessSpendIntent();
        }
        if (processSpendIntentAtEpochMillis > 0L
            && now - processSpendIntentAtEpochMillis >= PROCESS_SPEND_WAIT_MILLIS
            && pendingProcessSpend == null)
        {
            clearProcessSpendIntent();
        }
        if (productionCountdownExpiresAtEpochMillis > 0L
            && now >= productionCountdownExpiresAtEpochMillis)
        {
            productionCountdownExpiresAtEpochMillis = 0L;
        }
        maybeAdvanceLootCoalesce(now);

        // Layout (Always expanded) is applied by the renderer via keepTrayExpanded.
        // Never skip reveal expiry here — that left an infinite deadline when unpinning.
        if (phase == RewardPresentationPhase.REVEALING && now >= revealExpiresAtEpochMillis)
        {
            if (activityHold
                && current != null
                && isActivityHoldCard(current)
                && !isBriefPeerFlash(current.getSourceKind()))
            {
                revealExpiresAtEpochMillis = Long.MAX_VALUE / 4;
                return;
            }
            if (current != null)
            {
                RewardSourceKind kind = current.getSourceKind();
                boolean unpickedLoot = kind != null
                    && kind.isObservedLoot()
                    && current.collectionStatus() == CollectionStatus.UNCONFIRMED;
                if (!pinnedExpanded)
                {
                    if (unpickedLoot || isBriefPeerFlash(kind) || kind == RewardSourceKind.SKILLING)
                    {
                        if (isQueuedPeerKind(kind) && showNextQueuedFlash(now))
                        {
                            return;
                        }
                        clearLiveCard();
                        return;
                    }
                }
                else if (shouldClearPinnedBriefFlash(kind))
                {
                    if (showNextQueuedFlash(now))
                    {
                        return;
                    }
                    if (restoreAfterFlash != null)
                    {
                        restorePreservedTripAfterFlash(now);
                        return;
                    }
                    clearLiveCard();
                    return;
                }
            }
            phase = RewardPresentationPhase.SETTLED;
            revealExpiresAtEpochMillis = 0L;
            boolean unpickedSettled = current != null
                && current.getSourceKind() != null
                && current.getSourceKind().isObservedLoot()
                && current.collectionStatus()
                    == com.gpmanager.reward.CollectionStatus.UNCONFIRMED;
            if (pinnedExpanded && unpickedSettled)
            {
                // Always Expanded: keep batch-lock so later kills queue until clear.
                lootCoalescing = false;
                lootBatchLocked = true;
            }
            else
            {
                // Drop coalesce/lock so SETTLED cards cannot block skilling forever.
                resetLootCoalesceState();
            }
        }
        else if (phase == RewardPresentationPhase.SETTLED
            && !pinnedExpanded
            && current != null
            && revealExpiresAtEpochMillis > 0L
            && now >= revealExpiresAtEpochMillis)
        {
            // Skilling / supply Auto-collapse dwell, or any other timed settled card.
            clearLiveCard();
        }
    }

    /** Auto-collapse brief cards + AE one-shot Banked/Recovered/Dropped peers. */
    static boolean isBriefPeerFlash(RewardSourceKind kind)
    {
        return kind == RewardSourceKind.USED
            || kind == RewardSourceKind.LOST
            || kind == RewardSourceKind.BANKED
            || kind == RewardSourceKind.TRADED
            || kind == RewardSourceKind.RECOVERED
            || kind == RewardSourceKind.CLAIMED;
    }

    private boolean shouldClearPinnedBriefFlash(RewardSourceKind kind)
    {
        if (kind == RewardSourceKind.BANKED
            || kind == RewardSourceKind.RECOVERED
            || kind == RewardSourceKind.TRADED
            || kind == RewardSourceKind.CLAIMED)
        {
            return true;
        }
        return oneShotBriefFlash && (kind == RewardSourceKind.USED || kind == RewardSourceKind.LOST);
    }

    /**
     * Clears the live tray/settled card without wiping session HUD totals.
     * Used by Auto-collapse timeout and activity/NPC retarget.
     */
    public synchronized void clearLiveCard()
    {
        clearLiveCard(true);
    }

    private void clearLiveCard(boolean openPending)
    {
        current = null;
        phase = RewardPresentationPhase.NONE;
        revealExpiresAtEpochMillis = 0L;
        revealStartedAtEpochMillis = 0L;
        oneShotBriefFlash = false;
        restoreAfterFlash = null;
        // Full live clear while Always Expanded drops the session receipt bag.
        if (pinnedExpanded)
        {
            clearAeSessionReceipts();
        }
        clearPendingBankWithdraw();
        resetLootCoalesceState();
        generationCounter.incrementAndGet();
        if (openPending)
        {
            // Keep Deposit/Withdrew/Traded queue so peer flashes paint after Ground Loot.
            openPendingLootIfAny(lastKnownNow > 0L ? lastKnownNow : System.currentTimeMillis());
            if (current == null)
            {
                showNextQueuedFlash(lastKnownNow > 0L ? lastKnownNow : System.currentTimeMillis());
            }
        }
        else
        {
            flashQueue.clear();
            clearPendingLoot();
        }
    }

    private void resetLootCoalesceState()
    {
        lootCoalescing = false;
        lootBatchLocked = false;
        coalesceOpenedAtEpochMillis = 0L;
        coalesceQuietUntilEpochMillis = 0L;
    }

    private void clearPendingLoot()
    {
        pendingLootKind = null;
        pendingLootSourceName = "";
        pendingLootEncounterId = "";
        pendingLootStacks = null;
        pendingLootMultiplicity = 1;
    }

    private void maybeAdvanceLootCoalesce(long now)
    {
        if (!lootCoalescing
            || current == null
            || current.getSourceKind() == null
            || !current.getSourceKind().isObservedLoot())
        {
            return;
        }
        long maxEnd = coalesceOpenedAtEpochMillis + coalesceMaxMillis();
        if (now >= coalesceQuietUntilEpochMillis || now >= maxEnd)
        {
            lockLootCoalesce(now);
        }
        else
        {
            // Keep tray frozen until coalesce quiet/hard-cap.
            revealExpiresAtEpochMillis = Long.MAX_VALUE / 4;
        }
    }

    private void lockLootCoalesce(long now)
    {
        lootCoalescing = false;
        lootBatchLocked = true;
        if (!pinnedExpanded)
        {
            long dwell = Math.max(400L, revealDwellMillis);
            long quietAt = coalesceQuietUntilEpochMillis;
            long hardAt = coalesceOpenedAtEpochMillis > 0L
                ? coalesceOpenedAtEpochMillis + coalesceMaxMillis()
                : now;
            long lockAt = now;
            if (quietAt > 0L && now >= quietAt && now >= hardAt)
            {
                lockAt = Math.min(quietAt, hardAt);
            }
            else if (quietAt > 0L && now >= quietAt)
            {
                lockAt = quietAt;
            }
            else if (now >= hardAt)
            {
                lockAt = hardAt;
            }
            revealExpiresAtEpochMillis = lockAt + dwell;
            if (revealExpiresAtEpochMillis < now)
            {
                // Noticed late — expire on this tick so clear can run immediately.
                revealExpiresAtEpochMillis = now;
            }
            phase = RewardPresentationPhase.REVEALING;
        }
        else
        {
            phase = RewardPresentationPhase.REVEALING;
            refreshAutoCollapseDwell(now);
        }
    }

    private void beginLootCoalesce(long now)
    {
        lootCoalescing = true;
        lootBatchLocked = false;
        coalesceOpenedAtEpochMillis = now;
        coalesceQuietUntilEpochMillis = now + coalesceWindowMillis();
        phase = RewardPresentationPhase.REVEALING;
        revealExpiresAtEpochMillis = Long.MAX_VALUE / 4;
        markRevealVisualStart(now);
        revealStartCount++;
    }

    private void refreshLootCoalesceQuiet(long now)
    {
        coalesceQuietUntilEpochMillis = now + coalesceWindowMillis();
        revealExpiresAtEpochMillis = Long.MAX_VALUE / 4;
        if (phase != RewardPresentationPhase.REVEALING)
        {
            phase = RewardPresentationPhase.REVEALING;
        }
    }

    private void queuePendingLoot(
        RewardSourceKind kind,
        String sourceName,
        String encounterId,
        List<RewardItem> stacks,
        int encounterMultiplicity,
        long now)
    {
        String nextName = sourceName == null ? "" : sourceName.trim();
        boolean same = pendingLootKind != null
            && pendingLootKind.isObservedLoot()
            && kind.isObservedLoot()
            && nextName.equalsIgnoreCase(pendingLootSourceName == null ? "" : pendingLootSourceName);
        if (same && pendingLootStacks != null)
        {
            List<RewardItem> merged = new ArrayList<>(pendingLootStacks);
            merged.addAll(stacks);
            pendingLootStacks = RewardObservation.mergeStacks(merged);
            pendingLootMultiplicity = Math.max(1, pendingLootMultiplicity) + Math.max(1, encounterMultiplicity);
            if (encounterId != null && !encounterId.isEmpty())
            {
                pendingLootEncounterId = encounterId;
            }
            recordGenuineEncounter(kind, sourceName, stacks, now, encounterMultiplicity);
            return;
        }
        pendingLootKind = kind;
        pendingLootSourceName = nextName;
        pendingLootEncounterId = encounterId == null ? "" : encounterId;
        pendingLootStacks = new ArrayList<>(stacks);
        pendingLootMultiplicity = Math.max(1, encounterMultiplicity);
        recordGenuineEncounter(kind, sourceName, stacks, now, encounterMultiplicity);
    }

    private void openPendingLootIfAny(long now)
    {
        if (pendingLootKind == null || pendingLootStacks == null || pendingLootStacks.isEmpty())
        {
            clearPendingLoot();
            return;
        }
        if (suppressReveals)
        {
            clearPendingLoot();
            return;
        }
        RewardSourceKind kind = pendingLootKind;
        String sourceName = pendingLootSourceName;
        String encounterId = pendingLootEncounterId;
        List<RewardItem> stacks = pendingLootStacks;
        int multiplicity = pendingLootMultiplicity;
        clearPendingLoot();
        openingPendingLoot = true;
        try
        {
            offerObservation(kind, sourceName, encounterId, stacks, now, true, multiplicity);
        }
        finally
        {
            openingPendingLoot = false;
        }
    }

    private void restorePreservedTripAfterFlash(long now)
    {
        RewardObservation restored = restoreAfterFlash;
        restoreAfterFlash = null;
        if (restored == null || restored.getItems() == null || restored.getItems().isEmpty())
        {
            clearLiveCard();
            return;
        }
        current = restored.withItems(restored.getItems(), generationCounter.incrementAndGet());
        oneShotBriefFlash = false;
        phase = RewardPresentationPhase.SETTLED;
        revealExpiresAtEpochMillis = 0L;
        lastKnownNow = Math.max(lastKnownNow, now);
        syncAeSessionReceiptsFromObservation(current);
    }

    /**
     * Auto-collapse only: drop the live card when the player targets a different
     * NPC / resource. Always Expanded keeps the open tray.
     */
    public synchronized void clearLiveCardOnRetarget(String interactionName)
    {
        if (pinnedExpanded || current == null)
        {
            return;
        }
        // Non-interrupt: Ground Loot coalesce/countdown finishes even if header retargets.
        if (isProtectedObservedLootTray())
        {
            return;
        }
        if (interactionName != null && !interactionName.isEmpty())
        {
            String source = current.getSourceName();
            if (source != null && source.equalsIgnoreCase(interactionName.trim()))
            {
                return;
            }
        }
        clearLiveCard();
    }

    /**
     * Offer a source-observed reward (NPC / chest / Loot Tracker). Does not
     * touch accounting. Duplicate adapter signals for the same kill merge.
     */
    public synchronized boolean offerObservation(
        RewardSourceKind kind,
        String sourceName,
        String encounterId,
        List<RewardItem> items,
        long now,
        boolean animate)
    {
        return offerObservation(kind, sourceName, encounterId, items, now, animate, 1);
    }

    /**
     * @param encounterMultiplicity caller-proven encounter count, never inferred
     * from items or callbacks.
     */
    public synchronized boolean offerObservation(
        RewardSourceKind kind,
        String sourceName,
        String encounterId,
        List<RewardItem> items,
        long now,
        boolean animate,
        int encounterMultiplicity)
    {
        if (suppressReveals || kind == null || kind.isSkilling())
        {
            return false;
        }
        List<RewardItem> stacks = RewardObservation.mergeStacks(items);
        if (stacks.isEmpty())
        {
            return false;
        }

        String fingerprint = fingerprint(sourceName, stacks);
        String dedupeKey = kind.name() + "|" + fingerprint;
        // Cross-adapter dedupe only for the same encounter. Distinct stable IDs never
        // dedupe solely because names/items/time match. Volatile adapter keys may
        // still correlate via fingerprint within the window.
        String crossKey = "X|" + fingerprint;
        Long prior = recentDedupeKeys.get(crossKey);
        boolean incomingStable = encounterId != null
            && !encounterId.isEmpty()
            && !isVolatileEncounterKey(encounterId);
        boolean currentStable = current != null
            && current.getEncounterId() != null
            && !current.getEncounterId().isEmpty()
            && !isVolatileEncounterKey(current.getEncounterId());
        boolean distinctStableEncounters = incomingStable
            && currentStable
            && !encounterId.equals(current.getEncounterId());
        if (prior != null
            && now - prior <= DEDUPE_WINDOW_MILLIS
            && current != null
            && current.getSourceKind().isObservedLoot()
            && kind.isObservedLoot()
            && current.getSourceKind() != kind
            && !distinctStableEncounters
            && (fingerprint.equals(batchUnitFingerprint)
                || recentDedupeKeys.containsKey(crossKey)))
        {
            // Full duplicate report across adapters: retain quantities/values exactly.
            current = current.withRetainedIdentity(generationCounter.incrementAndGet());
            recentDedupeKeys.put(crossKey, now);
            recentDedupeKeys.put(dedupeKey, now);
            return false;
        }

        // Stable encounter ids (engine-assigned) merge split deliveries of the same
        // observation kind; never merge inventory-confirmed pickups into open NPC
        // loot via this path (that would then risk blanket collection confirm).
        boolean sameEncounter = current != null
            && encounterId != null
            && !encounterId.isEmpty()
            && encounterId.equals(current.getEncounterId())
            && !isVolatileEncounterKey(encounterId)
            && (kind == current.getSourceKind()
                || (kind.isObservedLoot() && current.getSourceKind().isObservedLoot()));
        if (sameEncounter)
        {
            RewardObservation previous = current;
            current = current.withMergedItems(stacks, generationCounter.incrementAndGet());
            recentDedupeKeys.put(crossKey, now);
            recentDedupeKeys.put(dedupeKey, now);
            RewardItem before = previous.bestItem();
            RewardItem after = current.bestItem();
            boolean winnerChanged = before == null || after == null
                || before.getItemId() != after.getItemId();
            if (lootCoalescing)
            {
                refreshLootCoalesceQuiet(now);
            }
            else if (!suppressReveals && winnerChanged && !lootBatchLocked)
            {
                beginReveal(now);
            }
            else if (!suppressReveals && !pinnedExpanded && lootBatchLocked)
            {
                // Split delivery of the same kill — keep countdown, do not reopen coalesce.
                refreshAutoCollapseDwell(now);
            }
            else if (!suppressReveals && !pinnedExpanded)
            {
                refreshAutoCollapseDwell(now);
            }
            return winnerChanged;
        }

        if (kind.isObservedLoot())
        {
            String incomingName = sourceName == null ? "" : sourceName.trim();
            boolean showingObserved = current != null
                && current.getSourceKind() != null
                && current.getSourceKind().isObservedLoot();
            boolean sameSource = showingObserved
                && current.getSourceKind().isObservedLoot()
                && incomingName.equalsIgnoreCase(
                    current.getSourceName() == null ? "" : current.getSourceName());

            // Locked countdown or AE settled unpicked hold: queue later kills.
            if (showingObserved && lootBatchLocked)
            {
                queuePendingLoot(kind, sourceName, encounterId, stacks, encounterMultiplicity, now);
                recentDedupeKeys.put(crossKey, now);
                recentDedupeKeys.put(dedupeKey, now);
                return false;
            }

            // Coalesce same-source within quiet window.
            if (showingObserved && lootCoalescing && sameSource)
            {
                maybeAdvanceLootCoalesce(now);
                if (lootBatchLocked)
                {
                    queuePendingLoot(kind, sourceName, encounterId, stacks, encounterMultiplicity, now);
                    recentDedupeKeys.put(crossKey, now);
                    recentDedupeKeys.put(dedupeKey, now);
                    return false;
                }
                if (batchOpenedAtEpochMillis <= 0L)
                {
                    batchOpenedAtEpochMillis = coalesceOpenedAtEpochMillis > 0L
                        ? coalesceOpenedAtEpochMillis
                        : current.getObservedAtEpochMillis();
                }
                current = current.withBatchedEncounter(stacks, generationCounter.incrementAndGet(), now);
                if (!openingPendingLoot)
                {
                    recordGenuineEncounter(kind, sourceName, stacks, now, encounterMultiplicity);
                }
                recentDedupeKeys.put(crossKey, now);
                recentDedupeKeys.put(dedupeKey, now);
                refreshLootCoalesceQuiet(now);
                return false;
            }

            // Different NPC during coalesce — replace burst.
            // First open or replace after unlocked non-loot card — begin coalesce.
        }

        int gen = generationCounter.incrementAndGet();
        if (current != null)
        {
            replacementCount++;
        }
        batchOpenedAtEpochMillis = now;
        batchUnitFingerprint = fingerprint;
        current = new RewardObservation(
            UUID.randomUUID().toString(),
            dedupeKey,
            kind,
            sourceName,
            encounterId,
            now,
            stacks,
            false,
            gen,
            ownerScopeKey);
        if (!openingPendingLoot)
        {
            recordGenuineEncounter(kind, sourceName, stacks, now, encounterMultiplicity);
        }
        recentDedupeKeys.put(crossKey, now);
        recentDedupeKeys.put(dedupeKey, now);
        clearPendingLoot();
        if (!suppressReveals && kind.isObservedLoot())
        {
            beginLootCoalesce(now);
        }
        else if (!suppressReveals)
        {
            resetLootCoalesceState();
            beginReveal(now);
        }
        else
        {
            resetLootCoalesceState();
            phase = RewardPresentationPhase.SETTLED;
            revealExpiresAtEpochMillis = 0L;
        }
        return true;
    }

    /**
     * Skilling / inventory-confirmed gains, process transforms, and consumption losses.
     * Stacks the same item; never opens the multi-item reveal tray for skilling.
     */
    public synchronized void offerSkillingOrConfirmed(ProfitTransaction transaction, long now, boolean animate)
    {
        if (transaction == null || suppressReveals)
        {
            return;
        }
        if (transaction.getCorrection() != TransactionCorrection.AUTO)
        {
            return;
        }
        TransactionType type = transaction.getType();
        if (type == TransactionType.TRANSFER)
        {
            if (isOwnDropRecovery(transaction))
            {
                notifyOwnDropRecovery(transaction, now, animate);
            }
            else
            {
                notifyHardBankTransfer(transaction, now, animate);
            }
            return;
        }
        if (type == TransactionType.TRADE)
        {
            notifyTradeFlash(transaction, now, animate);
            return;
        }

        List<RewardItem> gains = itemsFromTransaction(transaction);
        List<RewardItem> losses = itemsFromLossTransaction(transaction);
        boolean mixed = !gains.isEmpty() && !losses.isEmpty();
        String activity = transaction.getActivityName() == null
            ? "" : transaction.getActivityName().trim();

        // B10: decanting redistributes doses — a "Decanted" card, never Mixed or a drink.
        if (transaction.getActionKind() == com.gpmanager.model.ActionKind.DECANT && mixed)
        {
            if (isProtectedObservedLootTray())
            {
                return;
            }
            List<RewardItem> decantStacks = itemsFromProcessTransaction(transaction);
            if (!decantStacks.isEmpty())
            {
                offerProcessObservation(
                    com.gpmanager.model.ActionKind.DECANT.completedVerb(), decantStacks, now, animate);
            }
            return;
        }
        // Unified process path: PROCESSING or paired process-skill settles → Mixed.
        if (type == TransactionType.PROCESSING
            || (mixed
                && type != TransactionType.CONSUMPTION
                && type != TransactionType.PK_SUPPLY_COST
                && type != TransactionType.PK_DEATH_LOSS
                && type != TransactionType.PK_FEE
                && isProcessObservationActivity(activity)))
        {
            if (isProtectedObservedLootTray())
            {
                return;
            }
            List<RewardItem> stacks = itemsFromProcessTransaction(transaction);
            if (!stacks.isEmpty())
            {
                offerProcessObservation(resolveSkillingSourceName(transaction), stacks, now, animate);
            }
            return;
        }

        boolean lossType = type == TransactionType.CONSUMPTION
            || type == TransactionType.PK_SUPPLY_COST
            || type == TransactionType.PK_DEATH_LOSS
            || type == TransactionType.PK_FEE;
        List<RewardItem> stacks = lossType ? losses : gains;
        if (stacks.isEmpty())
        {
            return;
        }
        if (lossType)
        {
            if (isProtectedObservedLootTray())
            {
                return;
            }
            // Potion/food dose steps book both the spent dose and leftover vessel so
            // tray GP matches session net (not the full higher-dose GE alone).
            if (mixed && com.gpmanager.engine.GpManagerEngine.isDoseOrPartialConsumeDelta(
                transaction.getFlows()))
            {
                List<RewardItem> doseStacks = itemsFromProcessTransaction(transaction);
                if (!doseStacks.isEmpty())
                {
                    // Dose (4)→(3) keeps both stacks for net-honest GP, but the tag comes from
                    // action evidence: Drank / Ate / Supplies used (B10) — never Mixed.
                    offerProcessObservation(
                        ActionPresentation.doseTraySource(transaction), doseStacks, now, animate);
                    return;
                }
            }
            offerLossStacks(transaction, stacks, now, animate);
            return;
        }
        List<RewardItem> gained = stacks;
        RewardItem incomingBest = bestOf(gained);
        String encounterId = transaction.getEncounterId() == null ? "" : transaction.getEncounterId();

        // Pending Rewards invent collect → Received (not Ground Loot).
        if (tryCollectPendingRewards(gained, encounterId, now, animate))
        {
            return;
        }

        // Confirm against the open Ground Loot tray when provenance matches, or when
        // adapter keys are volatile/empty but inventory gains overlap remaining stacks
        // from the same NPC/source (npc:… keys never pass matchesRewardEncounter).
        if (tryConfirmOpenObservedLoot(transaction, gained, encounterId, now))
        {
            return;
        }

        KeyChestCatalogue.Entry chest = KeyChestCatalogue.entryForChestMention(activity);
        if (chest != null)
        {
            if (isProtectedObservedLootTray())
            {
                return;
            }
            offerObservation(
                RewardSourceKind.INVENTORY_CONFIRMED,
                chest.getChestName(),
                "key-chest|" + chest.getChestName().toLowerCase(Locale.ROOT) + '|' + now,
                gained,
                now,
                animate);
            return;
        }

        if (!encounterId.isEmpty())
        {
            // Do not replace a locked/coalescing Ground Loot tray with a separate Received card.
            if (isProtectedObservedLootTray())
            {
                return;
            }
            offerObservation(
                RewardSourceKind.INVENTORY_CONFIRMED,
                transaction.getActivityName() == null || transaction.getActivityName().isEmpty()
                    ? "Loot"
                    : transaction.getActivityName(),
                encounterId,
                gained,
                now,
                animate);
            // Only fully-confirm a dedicated inventory-confirmed observation of the
            // acquired stacks. Never blanket-confirm an open NPC/chest tray — that
            // marked unpicked top drops as Collected under Auto-collapse.
            if (current != null && current.getSourceKind() == RewardSourceKind.INVENTORY_CONFIRMED)
            {
                current = current.withCollectionConfirmed(current.getGeneration());
            }
            return;
        }

        boolean skillingActivity = isSkillingActivityName(transaction.getActivityName())
            || type == TransactionType.PROCESSING;

        // Recent pickups / skilling must not interrupt Ground Loot coalesce/countdown.
        if (isProtectedObservedLootTray())
        {
            return;
        }

        // Recent pickups: nearby inventory receipts without encounter match. Different
        // item types append; same item updates quantity. Never invent a kill/chest.
        if (!skillingActivity)
        {
            // Always open the cassette for floor pickups → Received. A single-stack
            // Auto-collapse path used to jump straight to SETTLED, so Net counted
            // while the tray never revealed (e.g. picking one Water rune pile).
            if (current != null
                && current.getSourceKind().isRecentPickup()
                && now - current.getObservedAtEpochMillis() <= BATCH_WINDOW_MILLIS)
            {
                current = current.withMergedItems(gained, generationCounter.incrementAndGet());
                openTrayForNewItems(now);
                return;
            }

            int gen = generationCounter.incrementAndGet();
            if (current != null)
            {
                replacementCount++;
            }
            current = new RewardObservation(
                UUID.randomUUID().toString(),
                "pickup|" + now,
                RewardSourceKind.RECENT_PICKUPS,
                "Recent pickups",
                "",
                now,
                gained,
                true,
                gen,
                ownerScopeKey);
            openTrayForNewItems(now);
            return;
        }

        // Always Expanded: session-wide confirmed receipts across skills/activities.
        if (pinnedExpanded)
        {
            mergeAeSessionReceipts(resolveSkillingSourceName(transaction), gained, now, animate);
            return;
        }

        // Skilling: same item stacks always merge. Hold also merges other items
        // within the same skill. Different skill → replace (fall through).
        if (current != null && current.getSourceKind().isSkilling())
        {
            boolean sameItem = incomingBest != null
                && current.bestItem() != null
                && current.bestItem().getItemId() == incomingBest.getItemId();
            String nextSkill = resolveSkillingSourceName(transaction);
            boolean sameSkill = sameSkillName(current.getSourceName(), nextSkill);
            if (sameItem || (activityHold && sameSkill))
            {
                current = current.withMergedItems(gained, generationCounter.incrementAndGet());
                oneShotBriefFlash = false;
                phase = RewardPresentationPhase.REVEALING;
                if (activityHold)
                {
                    revealExpiresAtEpochMillis = Long.MAX_VALUE / 4;
                }
                else
                {
                    scheduleAutoCollapseDwell(now);
                }
                return;
            }
        }

        int gen = generationCounter.incrementAndGet();
        if (current != null)
        {
            replacementCount++;
        }
        current = new RewardObservation(
            UUID.randomUUID().toString(),
            "skill|" + (incomingBest == null ? 0 : incomingBest.getItemId()),
            RewardSourceKind.SKILLING,
            resolveSkillingSourceName(transaction),
            "",
            now,
            gained,
            true,
            gen,
            ownerScopeKey);
        oneShotBriefFlash = false;
        // Reveal so HUD+ paints Chopped/Fished: like Used/Lost under Auto-collapse.
        phase = RewardPresentationPhase.REVEALING;
        scheduleAutoCollapseDwell(now);
        if (animate)
        {
            revealStartCount++;
            markRevealVisualStart(now);
        }
    }

    private void scheduleAutoCollapseDwell(long now)
    {
        refreshAutoCollapseDwell(now);
    }

    /** Finite dwell for Auto-collapse idle and AE one-shot peer flashes. */
    private void refreshAutoCollapseDwell(long now)
    {
        revealExpiresAtEpochMillis = now + Math.max(400L, revealDwellMillis);
    }

    /** Marks a new cassette visual (entry motion). Does not change dwell expiry. */
    private void markRevealVisualStart(long now)
    {
        revealStartedAtEpochMillis = now;
    }

    /**
     * Own-drop pickup reversed an earlier Dropped loss. Presentation-only
     * {@link RewardSourceKind#RECOVERED} flash — never Net invent; engine already
     * reversed costs. Distinct from Banked (ownership move) and Received (new loot).
     */
    private synchronized void notifyOwnDropRecovery(
        ProfitTransaction transaction, long now, boolean animate)
    {
        List<RewardItem> stacks = itemsFromTransaction(transaction);
        if (stacks.isEmpty())
        {
            // Do not wipe unrelated Ground Loot when recovery stacks are empty.
            return;
        }
        if (pinnedExpanded
            && current != null
            && HudTrayState.isPreservedOnAlwaysExpandedBank(current)
            && !isBriefPeerFlash(current.getSourceKind()))
        {
            // Always Expanded: flash Recovered, then restore Chopped/Received trip.
            flashThenMaybeRestore(
                RewardSourceKind.RECOVERED, "Recovered", stacks, current, now, animate);
            return;
        }
        clearLiveCard();
        offerPresentationFlash(RewardSourceKind.RECOVERED, "Recovered", stacks, now, animate, true);
    }

    static boolean isOwnDropRecovery(ProfitTransaction transaction)
    {
        if (transaction == null)
        {
            return false;
        }
        String note = transaction.getNote() == null ? "" : transaction.getNote().trim();
        if ("Own-drop recovery".equalsIgnoreCase(note))
        {
            return true;
        }
        String activity = transaction.getActivityName() == null ? "" : transaction.getActivityName().trim();
        return "Drop recovery".equalsIgnoreCase(activity);
    }

    /**
     * Hard bank deposit/withdraw (TRANSFER). Presentation only — never counted.
     * Auto-collapse: Deposit flash while banking; Withdrew only after the bank UI
     * closes (withdraws buffer while open). Always Expanded: qty-strip overlapping
     * tray stacks on deposit; Deposit flash when the tray empties.
     */
    private synchronized void notifyHardBankTransfer(
        ProfitTransaction transaction, long now, boolean animate)
    {
        List<RewardItem> deposited = itemsFromBankDeposit(transaction);
        boolean isDeposit = !deposited.isEmpty();
        if (!isDeposit)
        {
            List<RewardItem> withdrawn = itemsFromTransaction(transaction);
            if (!withdrawn.isEmpty())
            {
                clearGroundOnTradeOrWithdraw(withdrawn);
            }
            if (withdrawn.isEmpty() || suppressReveals)
            {
                return;
            }
            if (!showBankingFlashes)
            {
                return;
            }
            // While bank is open: accumulate only — flash Withdrew on close.
            if (bankUiOpen)
            {
                bufferBankWithdraw(withdrawn, animate);
                return;
            }
            offerWithdrewFlash(withdrawn, now, animate);
            return;
        }
        // Pending Rewards banked from reward UI → Claimed.
        if (current != null
            && current.getSourceKind() == RewardSourceKind.PENDING_REWARDS
            && trayItemIdsOverlap(current, deposited))
        {
            clearLiveCard();
            offerPresentationFlash(RewardSourceKind.CLAIMED, "Claimed", deposited, now, animate, true);
            return;
        }
        if (pinnedExpanded)
        {
            if (isProtectedObservedLootTray())
            {
                if (showBankingFlashes)
                {
                    enqueueOrShowFlash(
                        RewardSourceKind.BANKED, "Deposit", deposited, null, now, animate, true);
                }
                return;
            }
            if (current == null || HudTrayState.isGroundUnconfirmed(current))
            {
                clearLiveCard();
                flashBankedDeposit(deposited, now, animate);
                return;
            }
            if (!trayItemIdsOverlap(current, deposited))
            {
                return;
            }
            RewardObservation stripped = stripOverlappingQuantities(current, deposited);
            if (stripped == null || stripped.getItems().isEmpty())
            {
                clearLiveCard();
                flashBankedDeposit(deposited, now, animate);
                return;
            }
            current = stripped;
            syncAeSessionReceiptsFromObservation(stripped);
            phase = RewardPresentationPhase.SETTLED;
            oneShotBriefFlash = false;
            return;
        }
        if (isShowingQueuedPeerFlash())
        {
            if (showBankingFlashes)
            {
                enqueueOrShowFlash(
                    RewardSourceKind.BANKED, "Deposit", deposited, null, now, animate, true);
            }
            return;
        }
        // Do not wipe a locked/coalescing Ground Loot tray — queue Deposit behind it.
        if (isProtectedObservedLootTray())
        {
            if (showBankingFlashes)
            {
                enqueueOrShowFlash(
                    RewardSourceKind.BANKED, "Deposit", deposited, null, now, animate, true);
            }
            return;
        }
        clearLiveCard();
        flashBankedDeposit(deposited, now, animate);
    }

    private void flashBankedDeposit(List<RewardItem> deposited, long now, boolean animate)
    {
        if (!showBankingFlashes || deposited == null || deposited.isEmpty())
        {
            return;
        }
        offerPresentationFlash(RewardSourceKind.BANKED, "Deposit", deposited, now, animate, true);
    }

    /** Withdrew tray flash — Auto-collapse idle/queue or AE restore. */
    private void offerWithdrewFlash(List<RewardItem> withdrawn, long now, boolean animate)
    {
        if (!showBankingFlashes || withdrawn == null || withdrawn.isEmpty())
        {
            return;
        }
        if (pinnedExpanded)
        {
            RewardObservation preserved = null;
            if (current != null && !isBriefPeerFlash(current.getSourceKind()))
            {
                preserved = current;
            }
            flashThenMaybeRestore(
                RewardSourceKind.BANKED, "Withdrew", withdrawn, preserved, now, animate);
            return;
        }
        // Merge into live Withdrew / queue peers — same path as Deposit so multi-click
        // withdraws accumulate under one tray (+N more), not a second flash.
        if (isShowingQueuedPeerFlash())
        {
            enqueueOrShowFlash(
                RewardSourceKind.BANKED, "Withdrew", withdrawn, null, now, animate, true);
            return;
        }
        // Do not wipe a locked/coalescing Ground Loot tray — queue behind it.
        if (isProtectedObservedLootTray())
        {
            enqueueOrShowFlash(
                RewardSourceKind.BANKED, "Withdrew", withdrawn, null, now, animate, true);
            return;
        }
        clearLiveCard();
        offerPresentationFlash(
            RewardSourceKind.BANKED, "Withdrew", withdrawn, now, animate, true);
    }

    /** TRADE: clear overlapping Ground Loot; flash Traded under AC and AE. */
    private synchronized void notifyTradeFlash(
        ProfitTransaction transaction, long now, boolean animate)
    {
        List<RewardItem> stacks = itemsFromTransaction(transaction);
        if (stacks.isEmpty())
        {
            stacks = itemsFromLossTransaction(transaction);
        }
        RewardObservation preserved = null;
        if (pinnedExpanded
            && current != null
            && isStripableTripTray(current)
            && !isBriefPeerFlash(current.getSourceKind()))
        {
            if (!trayItemIdsOverlap(current, stacks))
            {
                // Chopped trip with unrelated trade stacks — flash Traded, restore trip.
                preserved = current;
            }
            else
            {
                RewardObservation stripped = stripOverlappingQuantities(current, absoluteStacks(stacks));
                if (stripped != null && !stripped.getItems().isEmpty())
                {
                    preserved = stripped;
                    syncAeSessionReceiptsFromObservation(stripped);
                }
            }
        }
        clearGroundOnTradeOrWithdraw(stacks);
        if (stacks.isEmpty() || suppressReveals)
        {
            return;
        }
        flashThenMaybeRestore(RewardSourceKind.TRADED, "Traded", stacks, preserved, now, animate);
    }

    private synchronized void clearGroundOnTradeOrWithdraw()
    {
        clearGroundOnTradeOrWithdraw(null);
    }

    /** Clear Ground Loot only when stacks overlap (or when stacks unknown). */
    private synchronized void clearGroundOnTradeOrWithdraw(List<RewardItem> stacks)
    {
        if (current != null && HudTrayState.isGroundUnconfirmed(current))
        {
            if (stacks == null || stacks.isEmpty() || trayItemIdsOverlap(current, stacks))
            {
                clearLiveCard();
            }
        }
    }

    /**
     * Reward-interface / chest / clue observation — Pending Rewards (not Ground Loot).
     */
    public synchronized boolean offerPendingRewards(
        String sourceName,
        String encounterId,
        List<RewardItem> items,
        long now,
        boolean animate)
    {
        return offerObservation(
            RewardSourceKind.PENDING_REWARDS,
            sourceName == null || sourceName.isEmpty() ? "Pending Rewards" : sourceName,
            encounterId,
            items,
            now,
            animate);
    }

    /**
     * Reward-interface bank / invent claim — Claimed (not Banked). Minimal hook
     * for chest adapters; presentation only.
     */
    public synchronized boolean offerClaimedRewards(
        String sourceName,
        String encounterId,
        List<RewardItem> items,
        long now,
        boolean animate)
    {
        return offerObservation(
            RewardSourceKind.CLAIMED,
            sourceName == null || sourceName.isEmpty() ? "Claimed" : sourceName,
            encounterId,
            items,
            now,
            animate);
    }

    /**
     * Container store/retrieve flash — Stored. Ownership-neutral contents.
     */
    public synchronized void offerStoredFlash(
        String sourceName,
        List<RewardItem> items,
        long now,
        boolean animate)
    {
        offerPresentationFlash(
            RewardSourceKind.STORED,
            sourceName == null || sourceName.isEmpty() ? "Stored" : sourceName,
            items,
            now,
            animate,
            true);
    }

    /**
     * Invent collect while Pending Rewards is open → Received accent (AE stays open).
     */
    private boolean tryCollectPendingRewards(
        List<RewardItem> gained,
        String encounterId,
        long now,
        boolean animate)
    {
        if (current == null
            || current.getSourceKind() != RewardSourceKind.PENDING_REWARDS
            || gained == null
            || gained.isEmpty()
            || !trayItemIdsOverlap(current, gained))
        {
            return false;
        }
        int gen = generationCounter.incrementAndGet();
        current = new RewardObservation(
            current.getRewardId(),
            current.getDedupeKey(),
            RewardSourceKind.INVENTORY_CONFIRMED,
            current.getSourceName(),
            encounterId == null || encounterId.isEmpty() ? current.getEncounterId() : encounterId,
            now,
            gained,
            true,
            gen,
            ownerScopeKey);
        oneShotBriefFlash = false;
        restoreAfterFlash = null;
        openTrayForNewItems(now);
        if (animate && !pinnedExpanded)
        {
            revealStartCount++;
            markRevealVisualStart(now);
        }
        return true;
    }

    /**
     * One-shot peer flash that may restore a preserved AE trip after dwell.
     * Queues behind an in-flight peer flash so Deposit → Withdrew is not dropped.
     */
    private void flashThenMaybeRestore(
        RewardSourceKind kind,
        String sourceName,
        List<RewardItem> stacks,
        RewardObservation restore,
        long now,
        boolean animate)
    {
        enqueueOrShowFlash(kind, sourceName, stacks, restore, now, animate, true);
    }

    private synchronized void offerPresentationFlash(
        RewardSourceKind kind,
        String sourceName,
        List<RewardItem> stacks,
        long now,
        boolean animate)
    {
        enqueueOrShowFlash(kind, sourceName, stacks, null, now, animate, true);
    }

    private synchronized void offerPresentationFlash(
        RewardSourceKind kind,
        String sourceName,
        List<RewardItem> stacks,
        long now,
        boolean animate,
        boolean oneShot)
    {
        enqueueOrShowFlash(kind, sourceName, stacks, null, now, animate, oneShot);
    }

    /**
     * Peer flashes that serialize through the tray queue (Deposit/Withdrew/Traded/…).
     * Used/Lost stay immediate so death is never stuck behind food.
     */
    static boolean isQueuedPeerKind(RewardSourceKind kind)
    {
        return kind == RewardSourceKind.BANKED
            || kind == RewardSourceKind.TRADED
            || kind == RewardSourceKind.RECOVERED
            || kind == RewardSourceKind.CLAIMED;
    }

    private boolean isShowingQueuedPeerFlash()
    {
        return current != null
            && phase == RewardPresentationPhase.REVEALING
            && isQueuedPeerKind(current.getSourceKind());
    }

    private void enqueueOrShowFlash(
        RewardSourceKind kind,
        String sourceName,
        List<RewardItem> stacks,
        RewardObservation restore,
        long now,
        boolean animate,
        boolean oneShot)
    {
        if (stacks == null || stacks.isEmpty() || suppressReveals || kind == null)
        {
            return;
        }
        if (restore != null)
        {
            restoreAfterFlash = restore;
        }
        String label = sourceName == null || sourceName.isEmpty() ? kind.getLabel() : sourceName;
        // Same Deposit/Withdrew/Traded still on screen — merge into the live tray so
        // Visible item rows + "+N more" covers the full withdraw, not a second flash.
        if (isQueuedPeerKind(kind)
            && isShowingQueuedPeerFlash()
            && current.getSourceKind() == kind
            && label.equalsIgnoreCase(safeTrimSource(current.getSourceName())))
        {
            current = current.withMergedItems(stacks, generationCounter.incrementAndGet());
            oneShotBriefFlash = oneShot;
            phase = RewardPresentationPhase.REVEALING;
            scheduleAutoCollapseDwell(now);
            if (restore != null)
            {
                restoreAfterFlash = restore;
            }
            return;
        }
        // Do not wipe a locked/coalescing Ground Loot tray — queue the peer flash.
        if (isQueuedPeerKind(kind) && isProtectedObservedLootTray())
        {
            enqueueFlash(kind, sourceName, stacks, restore, animate, oneShot);
            return;
        }
        if (isQueuedPeerKind(kind) && isShowingQueuedPeerFlash())
        {
            enqueueFlash(kind, sourceName, stacks, restore, animate, oneShot);
            return;
        }
        showFlashNow(kind, sourceName, stacks, now, animate, oneShot);
    }

    private static String safeTrimSource(String sourceName)
    {
        return sourceName == null ? "" : sourceName.trim();
    }

    private void enqueueFlash(
        RewardSourceKind kind,
        String sourceName,
        List<RewardItem> stacks,
        RewardObservation restore,
        boolean animate,
        boolean oneShot)
    {
        String label = sourceName == null || sourceName.isEmpty() ? kind.getLabel() : sourceName;
        List<RewardItem> copy = new ArrayList<>(stacks);
        QueuedTrayFlash tail = flashQueue.peekLast();
        if (tail != null
            && tail.kind == kind
            && Objects.equals(tail.sourceName, label))
        {
            // Coalesce rapid same-tag peers (e.g. multi-item Deposit) into one flash.
            List<RewardItem> merged = new ArrayList<>(tail.stacks);
            merged.addAll(copy);
            flashQueue.removeLast();
            flashQueue.addLast(new QueuedTrayFlash(
                kind, label, RewardObservation.mergeStacks(merged),
                restore != null ? restore : tail.restore, animate, oneShot));
            return;
        }
        while (flashQueue.size() >= MAX_FLASH_QUEUE)
        {
            flashQueue.pollFirst();
        }
        flashQueue.addLast(new QueuedTrayFlash(kind, label, copy, restore, animate, oneShot));
    }

    /** @return true when the next queued peer flash is now showing */
    private boolean showNextQueuedFlash(long now)
    {
        QueuedTrayFlash next = flashQueue.pollFirst();
        if (next == null)
        {
            return false;
        }
        if (next.restore != null)
        {
            restoreAfterFlash = next.restore;
        }
        showFlashNow(next.kind, next.sourceName, next.stacks, now, next.animate, next.oneShot);
        return true;
    }

    private void showFlashNow(
        RewardSourceKind kind,
        String sourceName,
        List<RewardItem> stacks,
        long now,
        boolean animate,
        boolean oneShot)
    {
        String label = sourceName == null || sourceName.isEmpty() ? kind.getLabel() : sourceName;
        int gen = generationCounter.incrementAndGet();
        current = new RewardObservation(
            UUID.randomUUID().toString(),
            kind.name().toLowerCase(java.util.Locale.ROOT) + "|" + fingerprint(label, stacks),
            kind,
            label,
            "",
            now,
            stacks,
            true,
            gen,
            ownerScopeKey);
        oneShotBriefFlash = oneShot;
        phase = RewardPresentationPhase.REVEALING;
        scheduleAutoCollapseDwell(now);
        if (animate)
        {
            revealStartCount++;
            markRevealVisualStart(now);
        }
    }

    /** Test/diag: pending peer flashes waiting behind the live cassette. */
    public synchronized int getFlashQueueSize()
    {
        return flashQueue.size();
    }

    private static final class QueuedTrayFlash
    {
        final RewardSourceKind kind;
        final String sourceName;
        final List<RewardItem> stacks;
        final RewardObservation restore;
        final boolean animate;
        final boolean oneShot;

        QueuedTrayFlash(
            RewardSourceKind kind,
            String sourceName,
            List<RewardItem> stacks,
            RewardObservation restore,
            boolean animate,
            boolean oneShot)
        {
            this.kind = kind;
            this.sourceName = sourceName;
            this.stacks = stacks;
            this.restore = restore;
            this.animate = animate;
            this.oneShot = oneShot;
        }
    }

    private static List<RewardItem> itemsFromBankDeposit(ProfitTransaction transaction)
    {
        List<RewardItem> items = new ArrayList<>();
        if (transaction == null || transaction.getFlows() == null)
        {
            return items;
        }
        for (ItemFlow flow : transaction.getFlows())
        {
            if (flow == null || flow.getQuantityDelta() >= 0L)
            {
                continue;
            }
            long qty = Math.abs(flow.getQuantityDelta());
            long value = Math.abs(flow.getValueDelta());
            items.add(new RewardItem(
                flow.getItemId(),
                flow.getItemName(),
                qty,
                value,
                flow.getPriceSource() != ItemPriceSource.UNPRICED
                    && flow.getPriceSource() != ItemPriceSource.UNKNOWN,
                flow.getPriceSource()));
        }
        return RewardObservation.mergeStacks(items);
    }

    private synchronized void offerLossStacks(
        ProfitTransaction transaction,
        List<RewardItem> losses,
        long now,
        boolean animate)
    {
        TransactionType type = transaction.getType();
        boolean deathLoss = type == TransactionType.PK_DEATH_LOSS || type == TransactionType.PK_FEE;
        String lossVerb = deathLoss ? "Lost" : lossVerbFromTransaction(transaction);
        boolean hasExplicitAction = ActionPresentation.lossVerb(transaction) != null;
        if (!deathLoss
            && !hasExplicitAction
            && !"Dropped".equals(lossVerb)
            && !"Destroyed".equals(lossVerb))
        {
            String activity = transaction.getActivityName() == null
                ? "" : transaction.getActivityName().trim();
            boolean activityProcess = HudTrayState.isImmediateProcessSpendActivity(activity);
            boolean intentArmed = isProcessSpendIntentArmed(now)
                && processSpendMatchesStacks(losses);
            // Menu/chat intent wins over any stale immediate process activity
            // (Cooking leftover must not absorb Firemaking log losses, etc.).
            if (intentArmed)
            {
                boolean staleActivityLabel = activityProcess
                    && !processSpendSkill.equalsIgnoreCase(activity);
                boolean conflictingSkillingTray = current != null
                    && current.getSourceKind().isSkilling()
                    && !sameSkillName(current.getSourceName(), processSpendSkill)
                    && HudTrayState.isImmediateProcessSpendActivity(processSpendSkill);
                if (staleActivityLabel || conflictingSkillingTray)
                {
                    offerProcessObservation(processSpendTraySource(processSpendSkill), losses, now, animate);
                    clearProcessSpendPending();
                    clearProcessSpendIntent();
                    clearProcessSpendXpLatch();
                    return;
                }
                // Prayer XP often lands before invent settles — paint once, no Used wait.
                if (isProcessSpendXpConfirmed(processSpendSkill, now))
                {
                    offerProcessObservation(processSpendTraySource(processSpendSkill), losses, now, animate);
                    clearProcessSpendPending();
                    clearProcessSpendIntent();
                    clearProcessSpendXpLatch();
                    return;
                }
                // Wait for XP (Burned/Offered) — do not flash Used yet.
                pendingProcessSpend = transaction;
                pendingProcessSpendStacks = new ArrayList<>(losses);
                pendingProcessSpendAtEpochMillis = now;
                pendingProcessSpendAnimate = animate;
                // Keep countdown visible target even before the card flashes.
                beginProductionCountdown(now, PROCESS_SPEND_WAIT_MILLIS);
                return;
            }
            if (activityProcess)
            {
                offerProcessObservation(activity, losses, now, animate);
                clearProcessSpendPending();
                clearProcessSpendIntent();
                return;
            }
        }

        RewardItem incomingBest = bestOf(losses);
        RewardSourceKind lossKind = deathLoss ? RewardSourceKind.LOST : RewardSourceKind.USED;
        String lossLabel = lossVerb;
        // Losses accumulate on Keep-expanded Used/Lost of the same verb/label;
        // Auto-collapse shows one stack. Different label under AE → replace (fall through).
        if (current != null
            && (current.getSourceKind() == RewardSourceKind.USED
                || current.getSourceKind() == RewardSourceKind.LOST))
        {
            boolean sameItem = incomingBest != null
                && current.bestItem() != null
                && current.bestItem().getItemId() == incomingBest.getItemId();
            boolean sameLabel = sameSkillName(current.getSourceName(), lossLabel);
            if (sameItem || (pinnedExpanded && sameLabel))
            {
                current = current.withMergedItems(losses, generationCounter.incrementAndGet());
                if (pinnedExpanded && current.getItems().size() > 1)
                {
                    current = current.withDisplaySourceName(lossLabel);
                }
                oneShotBriefFlash = false;
                if (pinnedExpanded)
                {
                    openTrayForNewItems(now);
                }
                else
                {
                    phase = RewardPresentationPhase.REVEALING;
                    scheduleAutoCollapseDwell(now);
                }
                return;
            }
        }

        if (pinnedExpanded && current != null && isStripableTripTray(current))
        {
            if (deathLoss)
            {
                clearLiveCard();
                flashLoss(lossKind, lossLabel, losses, now, animate, true);
                return;
            }
            if (!trayItemIdsOverlap(current, losses))
            {
                // Food/prayer while Chopped — keep the trip tray.
                return;
            }
            RewardObservation stripped = stripOverlappingQuantities(current, absoluteStacks(losses));
            if (stripped != null && !stripped.getItems().isEmpty())
            {
                if ("Dropped".equals(lossLabel) || "Destroyed".equals(lossLabel))
                {
                    // Flash Dropped/Destroyed, then restore stripped trip after dwell.
                    syncAeSessionReceiptsFromObservation(stripped);
                    flashThenMaybeRestore(lossKind, lossLabel, losses, stripped, now, animate);
                    return;
                }
                current = stripped;
                syncAeSessionReceiptsFromObservation(stripped);
                phase = RewardPresentationPhase.SETTLED;
                oneShotBriefFlash = false;
                return;
            }
            // Tray emptied by dump/consume of its stacks — one-shot loss flash.
            clearLiveCard();
            flashLoss(lossKind, lossLabel, losses, now, animate, true);
            return;
        }

        // Auto-collapse or empty/non-trip tray: clean loss flash (never merge onto SKILLING).
        flashLoss(lossKind, lossLabel, losses, now, animate, true);
    }

    private boolean isProcessSpendIntentArmed(long now)
    {
        return !processSpendSkill.isEmpty()
            && processSpendIntentAtEpochMillis > 0L
            && now - processSpendIntentAtEpochMillis < PROCESS_SPEND_WAIT_MILLIS;
    }

    private boolean processSpendMatchesStacks(List<RewardItem> losses)
    {
        if (losses == null || losses.isEmpty())
        {
            return false;
        }
        boolean openIntent = processSpendItemId < 0;
        if (processSpendItemId >= 0)
        {
            for (RewardItem item : losses)
            {
                if (item != null && item.getItemId() == processSpendItemId)
                {
                    return true;
                }
            }
            // Menu id can disagree with the settled stack (noted / alternate remains).
            // Fall through to open-family match for the armed skill.
        }
        // An open menu/chat intent has no item identity. Every settled loss must
        // therefore belong to that skill's known spend family; one matching bone
        // cannot drag an unrelated shark into a Buried receipt. Named intents keep
        // their alternate/noted-item fallback, but still require a family match.
        boolean foundFamilyStack = false;
        for (RewardItem item : losses)
        {
            if (item == null)
            {
                return false;
            }
            boolean family = com.gpmanager.ui.ProcessSkillSignals.matchesOpenSpendFamily(
                processSpendSkill, item.getItemName(), item.getItemId());
            if (openIntent && !family)
            {
                return false;
            }
            foundFamilyStack |= family;
        }
        return foundFamilyStack;
    }

    private static boolean currentUsedStacksMatchProcessSpend(
        String skillName,
        RewardObservation observation)
    {
        if (skillName == null || skillName.trim().isEmpty()
            || observation == null || observation.getItems() == null
            || observation.getItems().isEmpty())
        {
            return false;
        }
        boolean found = false;
        for (RewardItem item : observation.getItems())
        {
            if (item == null)
            {
                continue;
            }
            found = true;
            if (!com.gpmanager.ui.ProcessSkillSignals.matchesOpenSpendFamily(
                skillName, item.getItemName(), item.getItemId()))
            {
                return false;
            }
        }
        return found;
    }

    /**
     * Unified process presenter: loss-only (Burned/Offered) or paired (−in +out → Mixed).
     */
    private void offerProcessObservation(
        String skillName,
        List<RewardItem> stacks,
        long now,
        boolean animate)
    {
        if (stacks == null || stacks.isEmpty())
        {
            return;
        }
        String skill = skillName == null || skillName.trim().isEmpty()
            ? "Processing" : skillName.trim();
        RewardItem incomingBest = bestOf(stacks);

        // Always Expanded: keep session receipts across process skill changes.
        if (pinnedExpanded)
        {
            mergeAeSessionReceipts(skill, stacks, now, animate);
            return;
        }

        // Different skill + no item overlap under hold → fall through and replace
        // (do not suppress Cooking while Firemaking is open). Overlap still switches below.
        // Activity-hold: merge same-skill process cards.
        if (activityHold
            && current != null
            && current.getSourceKind().isSkilling()
            && skill.equalsIgnoreCase(current.getSourceName()))
        {
            current = current.withMergedItems(stacks, generationCounter.incrementAndGet());
            oneShotBriefFlash = false;
            phase = RewardPresentationPhase.REVEALING;
            revealExpiresAtEpochMillis = Long.MAX_VALUE / 4;
            scheduleProcessRevealDwell(now);
            revealExpiresAtEpochMillis = Long.MAX_VALUE / 4;
            return;
        }
        // Hold: overlapping gather→process (Chopped → Mixed/Fletched) switches
        // into the process card — do not net −in against gather qty (that hid Mixed).
        if (activityHold
            && current != null
            && current.getSourceKind().isSkilling()
            && !skill.equalsIgnoreCase(current.getSourceName())
            && trayItemIdsOverlap(current, stacks))
        {
            int switchGen = generationCounter.incrementAndGet();
            replacementCount++;
            current = new RewardObservation(
                UUID.randomUUID().toString(),
                "process|" + (incomingBest == null ? 0 : incomingBest.getItemId()),
                RewardSourceKind.SKILLING,
                skill,
                "",
                now,
                stacks,
                true,
                switchGen,
                ownerScopeKey);
            oneShotBriefFlash = false;
            restoreAfterFlash = null;
            phase = RewardPresentationPhase.REVEALING;
            revealExpiresAtEpochMillis = Long.MAX_VALUE / 4;
            if (animate)
            {
                revealStartCount++;
                markRevealVisualStart(now);
            }
            scheduleProcessRevealDwell(now);
            revealExpiresAtEpochMillis = Long.MAX_VALUE / 4;
            return;
        }
        int gen = generationCounter.incrementAndGet();
        if (current != null)
        {
            replacementCount++;
        }
        current = new RewardObservation(
            UUID.randomUUID().toString(),
            "process|" + (incomingBest == null ? 0 : incomingBest.getItemId()),
            RewardSourceKind.SKILLING,
            skill,
            "",
            now,
            stacks,
            true,
            gen,
            ownerScopeKey);
        oneShotBriefFlash = false;
        restoreAfterFlash = null;
        phase = RewardPresentationPhase.REVEALING;
        if (animate)
        {
            revealStartCount++;
            markRevealVisualStart(now);
        }
        scheduleProcessRevealDwell(now);
        if (activityHold)
        {
            revealExpiresAtEpochMillis = Long.MAX_VALUE / 4;
        }
    }

    private void clearAeSessionReceipts()
    {
        aeSessionReceipts = new ArrayList<>();
        aeSessionSkills.clear();
    }

    private void seedAeSessionReceiptsFromCurrent()
    {
        if (!pinnedExpanded
            || current == null
            || !current.getSourceKind().isSkilling()
            || current.getItems() == null
            || current.getItems().isEmpty())
        {
            return;
        }
        if (!aeSessionReceipts.isEmpty())
        {
            return;
        }
        aeSessionReceipts = new ArrayList<>(current.getItems());
        aeSessionSkills.clear();
        String skill = current.getSourceName() == null ? "" : current.getSourceName().trim();
        if (!skill.isEmpty() && !"Session".equalsIgnoreCase(skill))
        {
            aeSessionSkills.add(skill);
        }
    }

    private void syncAeSessionReceiptsFromObservation(RewardObservation observation)
    {
        if (!pinnedExpanded)
        {
            return;
        }
        if (observation == null || observation.getItems() == null || observation.getItems().isEmpty())
        {
            clearAeSessionReceipts();
            return;
        }
        aeSessionReceipts = new ArrayList<>(observation.getItems());
        String skill = observation.getSourceName() == null ? "" : observation.getSourceName().trim();
        if (!skill.isEmpty() && !"Session".equalsIgnoreCase(skill))
        {
            aeSessionSkills.clear();
            aeSessionSkills.add(skill);
        }
        else if (aeSessionSkills.size() <= 1 && !skill.isEmpty())
        {
            // Keep multi-skill set when observation is already Session-tagged.
        }
    }

    /**
     * Always Expanded session bag: merge confirmed skilling/process stacks across skills.
     */
    private void mergeAeSessionReceipts(
        String skillName,
        List<RewardItem> stacks,
        long now,
        boolean animate)
    {
        if (stacks == null || stacks.isEmpty())
        {
            return;
        }
        String skill = skillName == null || skillName.trim().isEmpty()
            ? "Skilling" : skillName.trim();
        if (!"Session".equalsIgnoreCase(skill))
        {
            aeSessionSkills.add(skill);
        }
        List<RewardItem> merged = new ArrayList<>(aeSessionReceipts);
        merged.addAll(stacks);
        aeSessionReceipts = filterNonZeroStacks(RewardObservation.mergeStacks(merged));
        if (aeSessionReceipts.isEmpty())
        {
            clearAeSessionReceipts();
            if (current != null && isBriefPeerFlash(current.getSourceKind()))
            {
                restoreAfterFlash = null;
                return;
            }
            clearLiveCard();
            return;
        }
        // Peer flash on screen — keep bag + restore snapshot; do not wipe the flash.
        if (current != null && isBriefPeerFlash(current.getSourceKind()))
        {
            restoreAfterFlash = buildAeSessionObservation(now);
            return;
        }
        publishAeSessionTray(now, animate);
    }

    private RewardObservation buildAeSessionObservation(long now)
    {
        String sourceName = aeSessionSkills.size() <= 1
            ? (aeSessionSkills.isEmpty() ? "Skilling" : aeSessionSkills.iterator().next())
            : "Session";
        RewardItem best = bestOf(aeSessionReceipts);
        return new RewardObservation(
            UUID.randomUUID().toString(),
            "ae-session|" + (best == null ? 0 : best.getItemId()),
            RewardSourceKind.SKILLING,
            sourceName,
            "",
            now,
            new ArrayList<>(aeSessionReceipts),
            true,
            generationCounter.incrementAndGet(),
            ownerScopeKey);
    }

    private void publishAeSessionTray(long now, boolean animate)
    {
        if (current != null)
        {
            replacementCount++;
        }
        current = buildAeSessionObservation(now);
        oneShotBriefFlash = false;
        restoreAfterFlash = null;
        openTrayForNewItems(now);
        if (animate && revealStartedAtEpochMillis <= 0L)
        {
            revealStartCount++;
            markRevealVisualStart(now);
        }
    }

    private static List<RewardItem> filterNonZeroStacks(List<RewardItem> stacks)
    {
        if (stacks == null || stacks.isEmpty())
        {
            return new ArrayList<>();
        }
        List<RewardItem> kept = new ArrayList<>(stacks.size());
        for (RewardItem item : stacks)
        {
            if (item == null)
            {
                continue;
            }
            if (item.getQuantity() == 0L && item.getRecordedValue() == 0L)
            {
                continue;
            }
            kept.add(item);
        }
        return kept;
    }

    /** Dwell until tray collapse, extended to open PRODUCTION / process-spend deadline. */
    private void scheduleProcessRevealDwell(long now)
    {
        long dwellEnd = now + Math.max(400L, revealDwellMillis);
        if (productionCountdownExpiresAtEpochMillis > now)
        {
            dwellEnd = Math.max(dwellEnd, productionCountdownExpiresAtEpochMillis);
        }
        revealExpiresAtEpochMillis = dwellEnd;
        phase = RewardPresentationPhase.REVEALING;
    }

    private static boolean isProcessObservationActivity(String activity)
    {
        return HudTrayState.isProcessSpendActivity(activity)
            || HudPlusProcessLabels.isProcessTitle(activity)
            || HudPlusProcessLabels.isTransformProcessSkill(activity);
    }

    /** Gains and losses on one card for PROCESSING / Mixed transforms. */
    static List<RewardItem> itemsFromProcessTransaction(ProfitTransaction transaction)
    {
        List<RewardItem> items = new ArrayList<>();
        items.addAll(itemsFromTransaction(transaction));
        items.addAll(itemsFromLossTransaction(transaction));
        return RewardObservation.mergeStacks(items);
    }

    private void flashLoss(
        RewardSourceKind lossKind,
        String lossLabel,
        List<RewardItem> losses,
        long now,
        boolean animate,
        boolean oneShot)
    {
        if (losses == null || losses.isEmpty() || lossKind == null)
        {
            return;
        }
        RewardItem incomingBest = bestOf(losses);
        int gen = generationCounter.incrementAndGet();
        if (current != null)
        {
            replacementCount++;
        }
        current = new RewardObservation(
            UUID.randomUUID().toString(),
            "loss|" + (incomingBest == null ? 0 : incomingBest.getItemId()),
            lossKind,
            lossLabel,
            "",
            now,
            losses,
            true,
            gen,
            ownerScopeKey);
        oneShotBriefFlash = oneShot;
        if (pinnedExpanded)
        {
            openTrayForNewItems(now);
            scheduleAutoCollapseDwell(now);
        }
        else
        {
            phase = RewardPresentationPhase.REVEALING;
            scheduleAutoCollapseDwell(now);
            if (animate)
            {
                revealStartCount++;
                markRevealVisualStart(now);
            }
        }
    }

    private static boolean isStripableTripTray(RewardObservation reward)
    {
        if (reward == null)
        {
            return false;
        }
        RewardSourceKind kind = reward.getSourceKind();
        return kind.isSkilling()
            || kind == RewardSourceKind.INVENTORY_CONFIRMED
            || (kind.isObservedLoot() && reward.collectionStatus() != CollectionStatus.UNCONFIRMED)
            || kind.isRecentPickup();
    }

    static boolean trayItemIdsOverlap(RewardObservation tray, List<RewardItem> stacks)
    {
        if (tray == null || tray.getItems() == null || stacks == null)
        {
            return false;
        }
        java.util.HashSet<Integer> ids = new java.util.HashSet<>();
        for (RewardItem item : stacks)
        {
            if (item != null)
            {
                ids.add(item.getItemId());
            }
        }
        if (ids.isEmpty())
        {
            return false;
        }
        for (RewardItem item : tray.getItems())
        {
            if (item != null && ids.contains(item.getItemId()))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Subtract deposited/dumped quantities from tray stacks (absolute amounts).
     * Returns a new observation, or empty-items observation when fully stripped.
     */
    static RewardObservation stripOverlappingQuantities(
        RewardObservation tray,
        List<RewardItem> removeStacks)
    {
        if (tray == null)
        {
            return null;
        }
        Map<Integer, Long> removeQty = new HashMap<>();
        if (removeStacks != null)
        {
            for (RewardItem item : removeStacks)
            {
                if (item == null)
                {
                    continue;
                }
                long qty = Math.abs(item.getQuantity());
                if (qty > 0L)
                {
                    removeQty.merge(item.getItemId(), qty, Long::sum);
                }
            }
        }
        List<RewardItem> kept = new ArrayList<>();
        for (RewardItem item : tray.getItems())
        {
            if (item == null)
            {
                continue;
            }
            long remove = removeQty.getOrDefault(item.getItemId(), 0L);
            if (remove <= 0L)
            {
                kept.add(item);
                continue;
            }
            long have = Math.abs(item.getQuantity());
            long left = have - remove;
            removeQty.put(item.getItemId(), Math.max(0L, remove - have));
            if (left <= 0L)
            {
                continue;
            }
            long sign = item.getQuantity() < 0L ? -1L : 1L;
            long value = item.isValueKnown() && have > 0L
                ? Math.round((double) item.getRecordedValue() * left / have)
                : item.getRecordedValue();
            kept.add(new RewardItem(
                item.getItemId(),
                item.getItemName(),
                sign * left,
                value,
                item.isValueKnown(),
                item.getPriceSource()));
        }
        return tray.withItems(kept, tray.getGeneration());
    }

    /** Loss stacks may be negative qty; strip math wants absolute amounts. */
    private static List<RewardItem> absoluteStacks(List<RewardItem> stacks)
    {
        if (stacks == null || stacks.isEmpty())
        {
            return stacks;
        }
        List<RewardItem> abs = new ArrayList<>(stacks.size());
        for (RewardItem item : stacks)
        {
            if (item == null)
            {
                continue;
            }
            abs.add(new RewardItem(
                item.getItemId(),
                item.getItemName(),
                Math.abs(item.getQuantity()),
                Math.abs(item.getRecordedValue()),
                item.isValueKnown(),
                item.getPriceSource()));
        }
        return abs;
    }

    /** Presentation verb for non-death losses: Dropped / Destroyed / Used. */
    static String lossVerbFromTransaction(ProfitTransaction transaction)
    {
        if (transaction == null)
        {
            return "Used";
        }
        String evidenced = ActionPresentation.lossVerb(transaction);
        if (evidenced != null)
        {
            return evidenced;
        }
        String note = transaction.getNote() == null ? "" : transaction.getNote().trim();
        if ("Dropped".equalsIgnoreCase(note))
        {
            return "Dropped";
        }
        if ("Destroyed".equalsIgnoreCase(note))
        {
            return "Destroyed";
        }
        String activity = transaction.getActivityName() == null ? "" : transaction.getActivityName().trim();
        if ("Dropped".equalsIgnoreCase(activity))
        {
            return "Dropped";
        }
        if ("Destroyed".equalsIgnoreCase(activity))
        {
            return "Destroyed";
        }
        return "Used";
    }

    static boolean isSkillingActivityName(String activityName)
    {
        if (activityName == null || activityName.trim().isEmpty())
        {
            return false;
        }
        String name = activityName.trim();
        if ("Skilling".equalsIgnoreCase(name)
            || "Woodcutting".equalsIgnoreCase(name)
            || "Mining".equalsIgnoreCase(name)
            || "Fishing".equalsIgnoreCase(name)
            || "Farming".equalsIgnoreCase(name)
            || "Hunter".equalsIgnoreCase(name)
            || "Cooking".equalsIgnoreCase(name)
            || "Smithing".equalsIgnoreCase(name)
            || "Crafting".equalsIgnoreCase(name)
            || "Fletching".equalsIgnoreCase(name)
            || "Herblore".equalsIgnoreCase(name)
            || "Runecraft".equalsIgnoreCase(name)
            || "Runecrafting".equalsIgnoreCase(name)
            || "Construction".equalsIgnoreCase(name)
            || "Firemaking".equalsIgnoreCase(name)
            || "Prayer".equalsIgnoreCase(name)
            || "Thieving".equalsIgnoreCase(name)
            || "Magic".equalsIgnoreCase(name)
            || "Agility".equalsIgnoreCase(name)
            || "Processing".equalsIgnoreCase(name))
        {
            return true;
        }
        return false;
    }

    static boolean sameSkillName(String left, String right)
    {
        if (left == null || right == null)
        {
            return false;
        }
        String a = left.trim();
        String b = right.trim();
        return !a.isEmpty() && !b.isEmpty() && a.equalsIgnoreCase(b);
    }

    /** Activity name for skilling tray verbs; PROCESSING without a skill → Made. */
    static String resolveSkillingSourceName(ProfitTransaction transaction)
    {
        if (transaction == null)
        {
            return "Skilling";
        }
        String activity = transaction.getActivityName();
        boolean named = activity != null
            && !activity.trim().isEmpty()
            && !"General".equalsIgnoreCase(activity.trim());
        if (named)
        {
            return activity.trim();
        }
        if (transaction.getType() == TransactionType.PROCESSING)
        {
            return "Processing";
        }
        return "Skilling";
    }

    public synchronized void invalidateTransaction(String transactionId)
    {
        // Acquisition-derived skilling/confirmed rows clear; pure observations stay
        // but lose confirmation evidence when the matching acquisition is corrected.
        if (current == null || transactionId == null || transactionId.isEmpty())
        {
            return;
        }
        if (current.getSourceKind() == RewardSourceKind.SKILLING
            || current.getSourceKind() == RewardSourceKind.RECENT_PICKUPS
            || current.getSourceKind() == RewardSourceKind.INVENTORY_CONFIRMED)
        {
            clear();
        }
    }

    /**
     * Remove confirmation evidence after a counted acquisition is corrected/undone.
     * Does not replay reveal or invent profit.
     */
    public synchronized void revokeAcquisitionEvidence(ProfitTransaction transaction)
    {
        if (current == null || transaction == null || !current.getSourceKind().isObservedLoot())
        {
            return;
        }
        String encounterId = transaction.getEncounterId() == null ? "" : transaction.getEncounterId();
        if (!matchesRewardEncounter(current, encounterId))
        {
            return;
        }
        List<RewardItem> gained = itemsFromTransaction(transaction);
        if (gained.isEmpty())
        {
            return;
        }
        RewardObservation updated = current.withCollectionRemoved(
            gained, generationCounter.incrementAndGet());
        if (updated != current)
        {
            current = updated;
        }
    }

    /** Configurable reveal dwell; entry animation duration remains renderer-local. */
    public synchronized void beginRevealWithDwell(long now, long dwellMillis)
    {
        lastKnownNow = Math.max(lastKnownNow, now);
        phase = RewardPresentationPhase.REVEALING;
        long dwell = Math.max(400L, dwellMillis);
        // Always finite. Always-expanded layout is separate from this clock.
        revealExpiresAtEpochMillis = now + dwell;
        revealStartedAtEpochMillis = now;
        revealStartCount++;
    }

    public synchronized RewardObservation current()
    {
        return current;
    }

    /**
     * HUD-only projection: reveal lifecycle remains short, source totals do not.
     * The most recently observed source selects the source aggregate.
     */
    public synchronized RewardObservation currentForHud(long now)
    {
        // Always Expanded: live card only — session/streak aggregates must not skew
        // Ground Loot ↔ Received presentation tags.
        if (pinnedExpanded)
        {
            return current;
        }
        if (current == null || !current.getSourceKind().isObservedLoot())
        {
            return current;
        }
        // Mid-pickup / open Ground Loot: keep live stacks only. Session totals would
        // resurrect already-Received loot as lingering Ground Loot. Header may still
        // show ×N from the session/streak encounter clock.
        CollectionStatus liveStatus = current.collectionStatus();
        if (liveStatus == CollectionStatus.UNCONFIRMED || liveStatus == CollectionStatus.PARTIAL)
        {
            SessionRewardAccumulator.Snapshot liveTotals = accumulator.current(
                current.getSourceName(), current.getSourceKind(), accumulationMode, now, streakInactivityMillis);
            // Timed-out streak: do not fall back to the last live card.
            if (accumulationMode == HudPlusAccumulationMode.STREAK
                && (liveTotals == null
                    || liveTotals.getEncounterCount() <= 0
                    || liveTotals.getItems().isEmpty()))
            {
                return null;
            }
            int encounters = current.getBatch() == null ? 1 : current.getBatch().getEncounterCount();
            if (liveTotals != null && liveTotals.getEncounterCount() > encounters)
            {
                encounters = liveTotals.getEncounterCount();
            }
            if (encounters <= 1 || (current.getBatch() != null
                && current.getBatch().getEncounterCount() == encounters))
            {
                return current;
            }
            return new RewardObservation(
                current.getRewardId(),
                current.getDedupeKey(),
                current.getSourceKind(),
                current.getSourceName(),
                current.getEncounterId(),
                current.getObservedAtEpochMillis(),
                current.getItems(),
                current.getConfirmedQuantities(),
                current.getGeneration(),
                current.getOwnerScopeKey(),
                new RewardBatchState(encounters, true, false));
        }
        SessionRewardAccumulator.Snapshot totals = accumulator.current(
            current.getSourceName(), current.getSourceKind(), accumulationMode, now, streakInactivityMillis);
        if (totals == null)
        {
            return current;
        }
        // A timed-out streak has no HUD aggregate. Do not fall back to the last
        // short-lived observation, which would resurrect an expired streak.
        if (accumulationMode == HudPlusAccumulationMode.STREAK
            && (totals.getEncounterCount() <= 0 || totals.getItems().isEmpty()))
        {
            return null;
        }
        if (totals.getEncounterCount() <= 0 || totals.getItems().isEmpty())
        {
            return current;
        }
        return new RewardObservation(
            current.getRewardId(),
            current.getDedupeKey(),
            current.getSourceKind(),
            current.getSourceName(),
            current.getEncounterId(),
            current.getObservedAtEpochMillis(),
            totals.getItems(),
            current.getConfirmedQuantities(),
            current.getGeneration(),
            current.getOwnerScopeKey(),
            new RewardBatchState(totals.getEncounterCount(), true, false));
    }

    /** Live › Encounter row: the current loot source's session totals. Presentation only. */
    public static final class EncounterSummary
    {
        public final String sourceName;
        public final int encounters;
        public final int streak;
        public final long lootValue;

        EncounterSummary(String sourceName, int encounters, int streak, long lootValue)
        {
            this.sourceName = sourceName;
            this.encounters = encounters;
            this.streak = streak;
            this.lootValue = lootValue;
        }
    }

    /** @return the current NPC / player loot source's session encounter totals, or null when none is live */
    @Nullable
    public synchronized EncounterSummary sessionEncounter(long now)
    {
        RewardObservation current = current();
        if (current == null || current.getSourceName() == null || current.getSourceName().trim().isEmpty())
        {
            return null;
        }
        RewardSourceKind kind = current.getSourceKind();
        if (kind != RewardSourceKind.NPC_LOOT && kind != RewardSourceKind.SERVER_NPC_LOOT
            && kind != RewardSourceKind.LOOT_TRACKER && kind != RewardSourceKind.PLAYER_LOOT)
        {
            return null;
        }
        SessionRewardAccumulator.Snapshot session = accumulator.current(
            current.getSourceName(), kind, HudPlusAccumulationMode.SESSION, now, streakInactivityMillis);
        SessionRewardAccumulator.Snapshot streak = accumulator.current(
            current.getSourceName(), kind, HudPlusAccumulationMode.STREAK, now, streakInactivityMillis);
        if (session == null || session.getEncounterCount() <= 0)
        {
            return null;
        }
        long value = 0L;
        for (RewardItem item : session.getItems())
        {
            if (item != null && item.isValueKnown() && !item.isLoss())
            {
                value += item.getRecordedValue();
            }
        }
        return new EncounterSummary(current.getSourceName().trim(), session.getEncounterCount(),
            streak == null ? 0 : streak.getEncounterCount(), value);
    }

    public synchronized HudPlusAccumulationMode getAccumulationMode()
    {
        return accumulationMode;
    }

    public synchronized RewardPresentationPhase phase()
    {
        return phase;
    }

    public synchronized boolean isRevealing(long now)
    {
        tick(now);
        return phase == RewardPresentationPhase.REVEALING;
    }

    public synchronized long getRevealExpiresAtEpochMillis()
    {
        long expires = revealExpiresAtEpochMillis;
        if (productionCountdownExpiresAtEpochMillis > expires)
        {
            expires = productionCountdownExpiresAtEpochMillis;
        }
        if (pendingProcessSpendAtEpochMillis > 0L)
        {
            expires = Math.max(expires,
                pendingProcessSpendAtEpochMillis + PROCESS_SPEND_WAIT_MILLIS);
        }
        return expires;
    }

    /**
     * Cassette entry progress 0→1. Activity-hold (frozen dwell) and settled
     * states report fully entered. Does not encode exit fade — that is
     * renderer-local from {@link #getRevealExpiresAtEpochMillis()}.
     */
    public synchronized float revealProgress(long now, long dwellMillis)
    {
        if (!isRevealing(now))
        {
            return 1f;
        }
        // Busy / Pending Rewards freeze expiry — tray stays fully shown.
        if (revealExpiresAtEpochMillis >= Long.MAX_VALUE / 8)
        {
            return 1f;
        }
        long started = revealStartedAtEpochMillis > 0L
            ? revealStartedAtEpochMillis
            : revealExpiresAtEpochMillis - Math.max(400L, dwellMillis);
        long age = Math.max(0L, now - started);
        long entryWindow = Math.min(
            Math.max(400L, dwellMillis),
            com.gpmanager.ui.HudPlusTrayMotion.ENTRY_MILLIS);
        // Prefer the short entry window so fade-in completes quickly; fall back
        // to dwell-fraction only when startedAt is missing and dwell is tiny.
        if (revealStartedAtEpochMillis > 0L)
        {
            return Math.max(0f, Math.min(1f, age / (float) Math.max(1L, entryWindow)));
        }
        long duration = Math.max(400L, dwellMillis);
        return Math.max(0f, Math.min(1f, age / (float) duration));
    }

    public synchronized long getRevealStartedAtEpochMillis()
    {
        return revealStartedAtEpochMillis;
    }

    public synchronized int getGeneration()
    {
        return current == null ? generationCounter.get() : current.getGeneration();
    }

    public synchronized int getReplacementCount()
    {
        return replacementCount;
    }

    public synchronized int getRevealStartCount()
    {
        return revealStartCount;
    }

    private void beginReveal(long now)
    {
        beginRevealWithDwell(now, revealDwellMillis);
    }

    /**
     * Opens the tray for newly arrived items. Always-expanded layout keeps the
     * list visible after the finite reveal accent ends; Auto-collapse uses the
     * same clock to return to the compact header.
     */
    private void openTrayForNewItems(long now)
    {
        // Activity-hold must stay frozen — beginReveal would start a finite dwell
        // that races the next syncActivityHold and can paint a false countdown.
        if (activityHold
            && current != null
            && isActivityHoldCard(current)
            && !isBriefPeerFlash(current.getSourceKind()))
        {
            phase = RewardPresentationPhase.REVEALING;
            revealExpiresAtEpochMillis = Long.MAX_VALUE / 4;
            return;
        }
        // Pickup confirm on open Ground Loot must not restart coalesce or replay entry.
        if (lootCoalescing)
        {
            phase = RewardPresentationPhase.REVEALING;
            revealExpiresAtEpochMillis = Long.MAX_VALUE / 4;
            return;
        }
        if (lootBatchLocked)
        {
            phase = RewardPresentationPhase.REVEALING;
            if (!pinnedExpanded)
            {
                refreshAutoCollapseDwell(now);
            }
            return;
        }
        if (phase == RewardPresentationPhase.REVEALING
            && revealExpiresAtEpochMillis < Long.MAX_VALUE / 8
            && now < revealExpiresAtEpochMillis)
        {
            // Extend the existing quiet countdown without restarting entry motion.
            revealExpiresAtEpochMillis = now + Math.max(400L, revealDwellMillis);
            return;
        }
        beginReveal(now);
    }

    private void recordGenuineEncounter(
        RewardSourceKind kind,
        String sourceName,
        List<RewardItem> items,
        long now,
        int encounterMultiplicity)
    {
        if (kind != null && kind.isObservedLoot())
        {
            accumulator.recordEncounter(
                sourceName, kind, items, now, encounterMultiplicity, streakInactivityMillis);
            if (encounterObserver != null && (kind == RewardSourceKind.NPC_LOOT
                || kind == RewardSourceKind.SERVER_NPC_LOOT || kind == RewardSourceKind.LOOT_TRACKER))
            {
                long value = 0L;
                boolean valueKnown = items != null;
                if (items != null)
                {
                    for (RewardItem item : items)
                    {
                        if (item == null || item.getQuantity() <= 0L) continue;
                        if (!item.isValueKnown())
                        {
                            valueKnown = false;
                        }
                        else
                        {
                            value = saturatedAdd(value, Math.max(0L, item.getRecordedValue()));
                        }
                    }
                }
                SessionRewardAccumulator.Snapshot streak = accumulator.current(sourceName, kind,
                    HudPlusAccumulationMode.STREAK, now, streakInactivityMillis);
                encounterObserver.onEncounter(sourceName, Math.max(1, encounterMultiplicity),
                    value, valueKnown, streak == null ? 0L : streak.getEncounterCount(), now);
            }
        }
    }

    private static long saturatedAdd(long left, long right)
    {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + Math.max(0L, right);
    }

    /** Adapter keys include a per-event suffix; engine encounter UUIDs do not. */
    static boolean isVolatileEncounterKey(String encounterId)
    {
        return encounterId != null && (encounterId.startsWith("npc:")
            || encounterId.startsWith("server-npc:")
            || encounterId.startsWith("loottracker:")
            || encounterId.startsWith("player:"));
    }

    /**
     * Reliable provenance match only. Volatile adapter keys and empty ids never
     * match here; see {@link #tryConfirmOpenObservedLoot} for soft confirm of
     * remaining stacks on an open Ground Loot tray.
     */
    static boolean matchesRewardEncounter(RewardObservation reward, String encounterId)
    {
        if (reward == null || encounterId == null || encounterId.isEmpty())
        {
            return false;
        }
        if (isVolatileEncounterKey(encounterId) || isVolatileEncounterKey(reward.getEncounterId()))
        {
            return false;
        }
        return encounterId.equals(reward.getEncounterId());
    }

    /**
     * Apply inventory gains to the open observed-loot tray when safe.
     * Stable encounter IDs use exact match. Volatile {@code npc:}/{@code loottracker:}
     * keys (and empty ids) confirm only overlapping <em>remaining</em> stacks when
     * the transaction is loot-affined to the tray source — so a picked Hammer leaves
     * Ground Loot without confirming a different kill's loot.
     */
    private boolean tryConfirmOpenObservedLoot(
        ProfitTransaction transaction,
        List<RewardItem> gained,
        String encounterId,
        long now)
    {
        if (current == null
            || !current.getSourceKind().isObservedLoot()
            || gained == null
            || gained.isEmpty())
        {
            return false;
        }
        boolean stableMatch = matchesRewardEncounter(current, encounterId);
        boolean softMatch = !stableMatch
            && canSoftConfirmOpenLoot(current, transaction, gained);
        // Already-collected tray: absorb duplicate invent settles for this encounter
        // so callers do not reopen a fresh INVENTORY_CONFIRMED Received flash.
        if (current.collectionStatus() == CollectionStatus.COLLECTED)
        {
            return stableMatch;
        }
        if (!stableMatch && !softMatch)
        {
            return false;
        }
        RewardObservation updated = current.withCollectionApplied(
            gained, generationCounter.incrementAndGet());
        if (updated == current)
        {
            // No remaining overlap. Same stable encounter = duplicate settle — absorb.
            // Different encounter / no soft match already returned false above.
            return stableMatch;
        }
        CollectionStatus beforeStatus = current.collectionStatus();
        current = updated;
        lastKnownNow = Math.max(lastKnownNow, now);
        // Soft floor-pickup on volatile Ground Loot → always reveal Received
        // (Auto-collapse and Always Expanded). Stable-encounter confirms under AE
        // also accent when UNCONFIRMED → Received.
        boolean becameReceived = beforeStatus == CollectionStatus.UNCONFIRMED
            && updated.collectionStatus() != CollectionStatus.UNCONFIRMED;
        if (!suppressReveals && (softMatch || becameReceived))
        {
            openTrayForNewItems(now);
        }
        else if (phase != RewardPresentationPhase.REVEALING)
        {
            phase = RewardPresentationPhase.SETTLED;
        }
        return true;
    }

    static boolean canSoftConfirmOpenLoot(
        RewardObservation reward,
        ProfitTransaction transaction,
        List<RewardItem> gained)
    {
        if (reward == null || transaction == null || gained == null || gained.isEmpty())
        {
            return false;
        }
        String rewardEnc = reward.getEncounterId() == null ? "" : reward.getEncounterId();
        // Soft confirm only for adapter-keyed / empty open trays. Stable engine
        // encounter IDs must use matchesRewardEncounter (already tried by caller).
        boolean rewardStable = !rewardEnc.isEmpty() && !isVolatileEncounterKey(rewardEnc);
        if (rewardStable)
        {
            return false;
        }
        if (!hasRemainingOverlap(reward, gained))
        {
            return false;
        }
        // Floor pickup into inventory while Ground Loot is open: LOOT/PK_LOOT is
        // enough. Affinity helps when present but must not block potion/hammer takes
        // after loot-context expiry left activity as General.
        TransactionType type = transaction.getType();
        if (type == TransactionType.LOOT || type == TransactionType.PK_LOOT)
        {
            return true;
        }
        return isLootAffinedToSource(reward.getSourceName(), transaction);
    }

    static boolean hasRemainingOverlap(RewardObservation reward, List<RewardItem> gained)
    {
        if (reward == null || gained == null)
        {
            return false;
        }
        for (RewardItem item : gained)
        {
            if (item != null && item.getQuantity() > 0L
                && reward.remainingQuantity(item.getItemId()) > 0L)
            {
                return true;
            }
        }
        return false;
    }

    static boolean isLootAffinedToSource(String sourceName, ProfitTransaction transaction)
    {
        if (transaction == null)
        {
            return false;
        }
        TransactionType type = transaction.getType();
        if (type != TransactionType.LOOT && type != TransactionType.PK_LOOT)
        {
            return false;
        }
        String source = sourceName == null ? "" : sourceName.trim();
        if (source.isEmpty())
        {
            return false;
        }
        String activity = transaction.getActivityName() == null
            ? "" : transaction.getActivityName().trim();
        if (!activity.isEmpty() && activity.equalsIgnoreCase(source))
        {
            return true;
        }
        String note = transaction.getNote() == null ? "" : transaction.getNote();
        String lowerNote = note.toLowerCase(Locale.ROOT);
        String lowerSource = source.toLowerCase(Locale.ROOT);
        return lowerNote.contains(lowerSource)
            || lowerNote.contains("loot from " + lowerSource);
    }

    private void pruneDedupe(long now)
    {
        if (recentDedupeKeys.size() <= MAX_RECENT_KEYS)
        {
            Iterator<Map.Entry<String, Long>> it = recentDedupeKeys.entrySet().iterator();
            while (it.hasNext())
            {
                Map.Entry<String, Long> entry = it.next();
                if (now - entry.getValue() > DEDUPE_WINDOW_MILLIS * 4)
                {
                    it.remove();
                }
            }
            return;
        }
        recentDedupeKeys.entrySet().removeIf(e -> now - e.getValue() > DEDUPE_WINDOW_MILLIS);
    }

    private static String fingerprint(String sourceName, List<RewardItem> stacks)
    {
        StringBuilder sb = new StringBuilder(sourceName == null ? "" : sourceName).append('|');
        List<RewardItem> sorted = new ArrayList<>(stacks);
        sorted.sort((a, b) -> Integer.compare(a.getItemId(), b.getItemId()));
        for (RewardItem item : sorted)
        {
            sb.append(item.getItemId()).append('x').append(item.getQuantity()).append(';');
        }
        return sb.toString();
    }

    private static List<RewardItem> itemsFromTransaction(ProfitTransaction transaction)
    {
        List<RewardItem> items = new ArrayList<>();
        if (transaction.getFlows() == null)
        {
            return items;
        }
        for (ItemFlow flow : transaction.getFlows())
        {
            if (flow == null || flow.getQuantityDelta() <= 0L)
            {
                continue;
            }
            boolean known = !(flow.getPriceSource() == ItemPriceSource.UNKNOWN && flow.getValueDelta() == 0L);
            items.add(new RewardItem(
                flow.getItemId(),
                flow.getItemName(),
                flow.getQuantityDelta(),
                known ? flow.getValueDelta() : 0L,
                known,
                flow.getPriceSource()));
        }
        return RewardObservation.mergeStacks(items);
    }

    /** Negative quantity / value stacks for HUD+ used/loss rows. */
    private static List<RewardItem> itemsFromLossTransaction(ProfitTransaction transaction)
    {
        List<RewardItem> items = new ArrayList<>();
        if (transaction.getFlows() == null)
        {
            return items;
        }
        for (ItemFlow flow : transaction.getFlows())
        {
            if (flow == null)
            {
                continue;
            }
            long qty = flow.getQuantityDelta();
            long value = flow.getValueDelta();
            // Prefer explicit quantity losses; otherwise treat negative value as a used cost.
            if (qty >= 0L && value >= 0L)
            {
                continue;
            }
            long shownQty = qty < 0L ? qty : -1L;
            boolean known = !(flow.getPriceSource() == ItemPriceSource.UNKNOWN && value == 0L);
            items.add(new RewardItem(
                flow.getItemId(),
                flow.getItemName(),
                shownQty,
                known ? (value <= 0L ? value : -Math.abs(value)) : 0L,
                known,
                flow.getPriceSource()));
        }
        return RewardObservation.mergeStacks(items);
    }

    private static RewardItem bestOf(List<RewardItem> items)
    {
        RewardItem best = null;
        for (RewardItem item : items)
        {
            if (item.beats(best))
            {
                best = item;
            }
        }
        return best;
    }
}
