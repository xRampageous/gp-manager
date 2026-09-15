package com.gpmanager;

import com.gpmanager.reward.RewardPresentationModel;
import com.gpmanager.ui.CharacterIdleModel;
import com.gpmanager.ui.HudClock;
import com.gpmanager.ui.InteractionContextModel;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Player;

/**
 * Client-thread adapter for presentation-only character idle. Never pauses the
 * engine or touches GP/hr.
 */
@Singleton
public final class CharacterIdleTracker
{
    private final Client client;
    private final CharacterIdleModel model;
    private final InteractionContextModel interactionContext;
    private final InteractionContextTracker interactionTracker;
    private final RewardPresentationModel rewardPresentationModel;
    private final HudClock clock;
    private final GpManagerConfig config;

    @Inject
    public CharacterIdleTracker(
        Client client,
        CharacterIdleModel model,
        InteractionContextModel interactionContext,
        InteractionContextTracker interactionTracker,
        RewardPresentationModel rewardPresentationModel,
        HudClock clock,
        GpManagerConfig config)
    {
        this.client = client;
        this.model = model;
        this.interactionContext = interactionContext;
        this.interactionTracker = interactionTracker;
        this.rewardPresentationModel = rewardPresentationModel;
        this.clock = clock;
        this.config = config;
    }

    public void clear()
    {
        model.clear();
    }

    public boolean isCharacterIdle()
    {
        return model.isCharacterIdle();
    }

    /** Soft-busy for Make-X / process XP gaps — presentation only. */
    public void noteSkillingXp()
    {
        noteSkillingXp(null);
    }

    /**
     * Soft-busy for XP gaps. When {@code skillName} is a process skill that
     * differs from the sticky scenery title, clears that title instead of
     * re-arming it (Cooking must not stick through Firemaking XP).
     */
    public void noteSkillingXp(String skillName)
    {
        long now = clock.now();
        model.noteSkillingXp(now);
        // Keep Willow tree (etc.) titled across swing / XP gaps.
        interactionContext.refreshObjectGraceForSkill(skillName, now);
    }

    /**
     * @return true once when character idle newly becomes true (scenery/NPC cleared)
     */
    public boolean onGameTick()
    {
        model.setDelayMillis(config.characterIdleDelayMillis());
        Player local = client.getLocalPlayer();
        long now = clock.now();
        boolean animating = local != null && local.getAnimation() != -1;
        boolean interacting = local != null && local.getInteracting() != null;
        boolean objectSticky = interactionContext.hasStickyObject(now);
        boolean lootTrayBusy = rewardPresentationModel != null
            && (rewardPresentationModel.isLootCoalescing()
                || rewardPresentationModel.isLootBatchLocked());
        boolean wasIdle = model.isCharacterIdle();
        boolean newlyIdle = model.tick(
            animating, interacting, objectSticky || lootTrayBusy, now);
        if (newlyIdle)
        {
            // Character Idle releases scenery + NPC soft-stick (Dark wizard → Idle).
            // Block tick re-arm so a stale getInteracting cannot resurrect the title.
            interactionContext.clearAndBlockNpcTickRearm();
        }
        else if (wasIdle && !model.isCharacterIdle())
        {
            // Leave Idle: restore live interact / soft-stick scenery without waiting
            // for a duplicate InteractingChanged.
            interactionTracker.restoreAfterLeaveIdle();
        }
        else if (animating || interacting)
        {
            // Keep scenery titles across swing gaps — not every busy tick.
            interactionContext.refreshObjectGrace(now);
        }
        return newlyIdle;
    }
}
