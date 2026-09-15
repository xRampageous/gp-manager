package com.gpmanager;

import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.InsightsActivityCategory;
import com.gpmanager.model.InsightsActivityBreakdown;
import com.gpmanager.reward.RewardPresentationModel;
import com.gpmanager.ui.HudClock;
import com.gpmanager.ui.HudPlusProcessLabels;
import com.gpmanager.ui.InteractionContextModel;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.MenuOptionClicked;

/** Client-thread adapter for current NPC/object names, independent of loot and PK tracking. */
@Singleton
public final class InteractionContextTracker
{
    private static final Pattern TRANSIT_SCENERY = Pattern.compile(
        "(?i).*\\b(gate|door|fence|stile|trapdoor)\\b.*");

    private final Client client;
    private final GameplayIngestionFacade ingestion;
    private final GpManagerEngine engine;
    private final GpManagerConfig config;
    private final InteractionContextModel model;
    private final HudClock clock;
    private final RewardPresentationModel rewardPresentationModel;
    private String owner = "";
    private int objectSelectionAnimation = -1;

    @Inject
    public InteractionContextTracker(Client client, GameplayIngestionFacade ingestion,
        GpManagerEngine engine, GpManagerConfig config, InteractionContextModel model, HudClock clock,
        RewardPresentationModel rewardPresentationModel)
    {
        this.client = client;
        this.ingestion = ingestion;
        this.engine = engine;
        this.config = config;
        this.model = model;
        this.clock = clock;
        this.rewardPresentationModel = rewardPresentationModel;
    }

    public void clear()
    {
        owner = "";
        objectSelectionAnimation = -1;
        model.clear();
    }

    /**
     * Wipe HUD interaction and block getInteracting tick re-arm (Idle / bank).
     * Prefer this over {@link #clear()} when soft-stick must not resurrect.
     */
    public void clearAndBlockNpcTickRearm()
    {
        owner = "";
        objectSelectionAnimation = -1;
        model.clearAndBlockNpcTickRearm();
    }

    /** True while the active owner's NPC title is visible, including its release grace. */
    public boolean hasNpcContext()
    {
        return ready() && model.hasNpcContext(owner, clock.now());
    }

    /** True while any fresh NPC/object/player title candidate is visible. */
    public boolean hasFreshInteractionContext()
    {
        if (!ready())
        {
            return false;
        }
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null || localPlayer.isDead())
        {
            return false;
        }
        Actor target = localPlayer.getInteracting();
        if (!model.isNpcTickRearmBlocked() && (target instanceof NPC || target instanceof Player))
        {
            // InteractingChanged can lag this synchronous XP callback. Respect
            // the Idle/bank block so a stale client target cannot resurrect a title.
            return true;
        }
        return model.snapshot(owner, clock.now()).isPresent();
    }

    /** True when the active owner's visible actor context positively identifies PvM. */
    public boolean hasPvmContext()
    {
        return ready() && model.hasPvmContext(owner, clock.now());
    }

    private boolean ready()
    {
        ProfitSession session = engine.getActiveSession();
        String nextOwner = session == null ? "" : session.getId();
        if (!nextOwner.equals(owner))
        {
            clear();
            owner = nextOwner;
        }
        if (nextOwner.isEmpty() || !config.autoActivityDetection() || !ingestion.canIngestGameplay()
            || engine.isStopped())
        {
            model.clear();
            return false;
        }
        return true;
    }

    public void onInteractingChanged(InteractingChanged event)
    {
        if (!ready() || event == null || event.getSource() != client.getLocalPlayer()) return;
        // Dead local player: never keep / re-arm NPC titles (Dark wizard after death).
        if (localPlayerDead())
        {
            model.clear();
            return;
        }
        if (event.getTarget() instanceof NPC)
        {
            NPC npc = (NPC) event.getTarget();
            applyNpcOrPlayer(npc.getIndex(), npc.getId(), npc.getName(), true, npc.getCombatLevel() > 0);
        }
        else if (event.getTarget() instanceof Player)
        {
            Player player = (Player) event.getTarget();
            applyNpcOrPlayer(player.getId(), -1, player.getName(), false, false);
        }
        else
        {
            model.releaseNpc(owner, clock.now());
        }
    }

    private void applyNpcOrPlayer(int index, int id, String name, boolean npc, boolean combatNpc)
    {
        InteractionContextModel.View before = model.snapshot(owner, clock.now());
        if (npc)
        {
            model.npc(owner, index, id, name, combatNpc);
        }
        else
        {
            model.player(owner, index, name);
        }
        InteractionContextModel.View after = model.snapshot(owner, clock.now());
        notifyRetarget(before, after);
    }

    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        if (!ready() || event == null || event.isConsumed() || event.getMenuAction() == null) return;
        String action = event.getMenuOption() == null ? "" : event.getMenuOption().trim().toLowerCase(Locale.ROOT);
        String type = event.getMenuAction().name();
        boolean objectOp = type.startsWith("GAME_OBJECT_") && type.endsWith("_OPTION");
        if (!objectOp)
        {
            // Any different intentional action cancels selected/active object context.
            // NPC names continue to be controlled by actual Actor interaction events.
            model.cancelObject(owner);
            return;
        }
        ObjectComposition object = client.getObjectDefinition(event.getId());
        if (object != null && object.getImpostorIds() != null) object = object.getImpostor();
        if (object == null)
        {
            model.cancelObject(owner);
            return;
        }
        String name = object.getName();
        if (!HudPlusProcessLabels.isTitledObjectOption(action, name))
        {
            model.cancelObject(owner);
            return;
        }
        Player player = client.getLocalPlayer();
        objectSelectionAnimation = player == null ? -1 : player.getAnimation();
        InteractionContextModel.View before = model.snapshot(owner, clock.now());
        String gerund = HudPlusProcessLabels.gerundFromMenuOption(action);
        if (gerund == null)
        {
            gerund = HudPlusProcessLabels.gerundFromScenery(name);
        }
        model.selectObject(
            owner,
            event.getId() + ":" + event.getParam0() + ":" + event.getParam1(),
            name,
            clock.now(),
            objectCategory(action, name),
            gerund == null ? "" : gerund);
        InteractionContextModel.View after = model.snapshot(owner, clock.now());
        notifyRetarget(before, after);
    }

    static boolean isTransitScenery(String name)
    {
        return name != null && TRANSIT_SCENERY.matcher(name).matches();
    }

    private void notifyRetarget(InteractionContextModel.View before, InteractionContextModel.View after)
    {
        if (!after.isPresent())
        {
            return;
        }
        if (before.isPresent() && before.getName().equalsIgnoreCase(after.getName()))
        {
            // Same Chicken / same tree — do not wipe mid-dwell.
            return;
        }
        // Distinct target (or first context while a loot card is showing) clears Auto-collapse.
        rewardPresentationModel.clearLiveCardOnRetarget(after.getName());
    }

    private static InsightsActivityCategory objectCategory(String action, String name)
    {
        if (InsightsActivityBreakdown.categorize(name) == InsightsActivityCategory.RAIDS)
            return InsightsActivityCategory.RAIDS;
        if (HudPlusProcessLabels.gerundFromMenuOption(action) != null
            || HudPlusProcessLabels.gerundFromScenery(name) != null
            || GpManagerPlugin.isHarvestGatherOption(action)
            || HudPlusProcessLabels.isTrackedObjectOption(action)
            || "plant".equals(action)
            || "rake".equals(action)
            || "search".equals(action))
        {
            return InsightsActivityCategory.SKILLING;
        }
        return InsightsActivityCategory.OTHER;
    }

    public void onGameTick()
    {
        if (!ready()) return;
        Player player = client.getLocalPlayer();
        if (player == null) { model.clear(); return; }
        // Keep HUD+ clear while dead — client may still report the killer as target.
        if (player.isDead())
        {
            model.clear();
            return;
        }
        long now = clock.now();
        if (player.getInteracting() instanceof NPC)
        {
            // Idle/bank cleared soft-stick: ignore stale getInteracting until a
            // fresh InteractingChanged (npc()/player() clears the block).
            if (model.isNpcTickRearmBlocked())
            {
                return;
            }
            NPC npc = (NPC) player.getInteracting();
            applyNpcOrPlayer(npc.getIndex(), npc.getId(), npc.getName(), true, npc.getCombatLevel() > 0);
            return;
        }
        if (player.getInteracting() instanceof Player)
        {
            if (model.isNpcTickRearmBlocked())
            {
                return;
            }
            Player target = (Player) player.getInteracting();
            applyNpcOrPlayer(target.getId(), -1, target.getName(), false, false);
            return;
        }
        model.releaseNpc(owner, now);
        InteractionContextModel.View view = model.snapshot(owner, now);
        int animation = player.getAnimation();
        if (animation != -1 && (!view.isSelected() || animation != objectSelectionAnimation))
        {
            model.confirmObject(owner, now);
        }
        if (animation == -1) objectSelectionAnimation = -1;
    }

    /**
     * Leave Character Idle: re-apply live interact target even when tick re-arm
     * was blocked, or soft-restore last scenery while animating.
     */
    public void restoreAfterLeaveIdle()
    {
        if (!ready())
        {
            return;
        }
        Player player = client.getLocalPlayer();
        if (player == null || player.isDead())
        {
            return;
        }
        long now = clock.now();
        if (player.getInteracting() instanceof NPC)
        {
            NPC npc = (NPC) player.getInteracting();
            applyNpcOrPlayer(npc.getIndex(), npc.getId(), npc.getName(), true, npc.getCombatLevel() > 0);
            return;
        }
        if (player.getInteracting() instanceof Player)
        {
            Player target = (Player) player.getInteracting();
            applyNpcOrPlayer(target.getId(), -1, target.getName(), false, false);
            return;
        }
        if (player.getAnimation() != -1)
        {
            model.softRestoreLastObject(owner, now);
        }
    }

    private boolean localPlayerDead()
    {
        Player local = client.getLocalPlayer();
        return local != null && local.isDead();
    }
}
