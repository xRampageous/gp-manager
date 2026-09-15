package com.gpmanager;

import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.engine.TransactionClassifier;
import com.gpmanager.ui.HudClock;
import com.gpmanager.ui.HudPlusProcessLabels;
import com.gpmanager.ui.InteractionContextModel;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.function.Function;
import net.runelite.api.*;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.MenuOptionClicked;
import org.junit.Test;
import static org.junit.Assert.*;

public class InteractionContextTrackerTest
{
    @Test public void localNpcAppearsImmediatelyWithPkDisabledAndNoAccountingChange()
    {
        Harness h = new Harness();
        h.engage("<col=ffff00>Chicken</col>", 41);
        assertEquals("Chicken", h.view().getName());
        assertTrue(h.tracker.hasPvmContext());
        assertTrue(h.engine.getActiveSession().getTransactions().isEmpty());
        assertEquals(0L, h.engine.getMetrics(1000L).getNet());
        assertEquals(0, h.engine.getMetrics(1000L).getActionCount());
    }

    @Test public void nonCombatNpcContextDoesNotProvePvmDeath()
    {
        Harness h = new Harness();
        h.engage("Fishing spot", 100);
        assertTrue(h.tracker.hasNpcContext());
        assertFalse(h.tracker.hasPvmContext());

        h.engage("Shopkeeper", 101);
        assertTrue(h.tracker.hasNpcContext());
        assertFalse("a visible non-combat NPC does not prove PvM death", h.tracker.hasPvmContext());
    }

    @Test public void unrelatedActorsCannotReplaceOurTarget()
    {
        Harness h = new Harness();
        h.engage("Chicken", 41);
        h.tracker.onInteractingChanged(new InteractingChanged(npc("Cow", 2), npc("Goblin", 3)));
        assertEquals("Chicken", h.view().getName());
    }

    @Test public void playerNameIsTransientAndIndependentOfHistoricalPkStorage()
    {
        Harness h = new Harness();
        h.target = proxy(Player.class, method -> "getName".equals(method) ? "Example player" : null);
        h.tracker.onInteractingChanged(new InteractingChanged(h.player, h.target));
        assertEquals("Example player", h.view().getName());
        assertEquals("PvP", h.view().getCategory().getDisplayName());
        assertTrue(h.engine.getActiveSession().getTransactions().isEmpty());
        h.target = null;
        h.tracker.onGameTick();
        h.clock.fixAt(6000);
        assertFalse(h.view().isPresent());
    }

    @Test public void chestAndHarvestHaveDifferentCategories()
    {
        Harness h = new Harness();
        h.menu("Chop down", MenuAction.GAME_OBJECT_FIRST_OPTION);
        assertEquals("Skilling", h.view().getCategory().getDisplayName());
        h.objectName = "Larren's big chest";
        h.menu("Search", MenuAction.GAME_OBJECT_FIRST_OPTION);
        assertEquals("Larren's big chest", h.view().getName());
        assertEquals("Skilling", h.view().getCategory().getDisplayName());
    }

    @Test public void openGateAndDoorDoNotBecomeHudContext()
    {
        Harness h = new Harness();
        h.menu("Chop down", MenuAction.GAME_OBJECT_FIRST_OPTION);
        assertTrue(h.view().isPresent());
        h.objectName = "Gate";
        h.menu("Open", MenuAction.GAME_OBJECT_FIRST_OPTION);
        assertFalse(h.view().isPresent());
        assertTrue(InteractionContextTracker.isTransitScenery("Oak door"));
        assertTrue(InteractionContextTracker.isTransitScenery("Castle fence"));
        assertFalse(InteractionContextTracker.isTransitScenery("Oak tree"));
    }

    @Test public void movementObjectsStayUntitledWhileEngagedActivitiesGetTitles()
    {
        Harness h = new Harness();
        h.objectName = "Wilderness Ditch";
        h.menu("Cross", MenuAction.GAME_OBJECT_FIRST_OPTION);
        assertFalse("crossing is movement, even though it remains a tracked activity option",
            h.view().isPresent());
        assertTrue(HudPlusProcessLabels.isTrackedObjectOption("Cross"));

        h.objectName = "Ladder";
        h.menu("Climb-up", MenuAction.GAME_OBJECT_FIRST_OPTION);
        assertFalse("climbing a ladder is movement, not the HUD+ activity title",
            h.view().isPresent());
        assertTrue(HudPlusProcessLabels.isTrackedObjectOption("Climb-up"));

        h.objectName = "Oak tree";
        h.menu("Chop down", MenuAction.GAME_OBJECT_FIRST_OPTION);
        assertEquals("Oak tree", h.view().getName());

        h.objectName = "Brimstone chest";
        h.menu("Open", MenuAction.GAME_OBJECT_FIRST_OPTION);
        assertEquals("Brimstone chest", h.view().getName());
    }

    @Test public void targetSwitchIsImmediateAndNullInteractionExpiresWithoutRefreshingTimer()
    {
        Harness h = new Harness();
        h.engage("Chicken", 41);
        h.engage("Cow", 42);
        assertEquals("Cow", h.view().getName());
        h.target = null;
        h.tracker.onInteractingChanged(new InteractingChanged(h.player, null));
        h.clock.fixAt(5999L);
        h.tracker.onGameTick();
        assertEquals("Cow", h.view().getName());
        h.clock.fixAt(6000L);
        assertFalse(h.view().isPresent());
    }

    @Test public void tickFallbackRecognizesExistingInteractionAndNameTransformation()
    {
        Harness h = new Harness();
        h.target = npc("Cow", 42);
        assertFalse("context model has not received the tick/event yet", h.view().isPresent());
        assertTrue("live actor must still suppress a competing Prayer title", h.tracker.hasFreshInteractionContext());
        h.tracker.onGameTick();
        assertEquals("Cow", h.view().getName());
        h.target = npc("Cow calf", 43);
        h.tracker.onGameTick();
        assertEquals("Cow calf", h.view().getName());
    }

    @Test public void freshContextCheckDoesNotRearmStaleActorAfterIdleOrBank()
    {
        Harness h = new Harness();
        h.engage("Dark wizard", 172);
        h.tracker.clearAndBlockNpcTickRearm();
        assertFalse("blocked stale getInteracting is not fresh evidence", h.tracker.hasFreshInteractionContext());
    }

    @Test public void objectSelectionUsesCompositionNameAndBecomesActiveWithNewAnimation()
    {
        Harness h = new Harness();
        h.menu("Chop down", MenuAction.GAME_OBJECT_FIRST_OPTION);
        assertEquals("Oak tree (selected)", h.view().getLabel());
        assertEquals("Oak tree", h.view().getHudPlusName());
        h.animation = 879;
        h.tracker.onGameTick();
        assertEquals("Oak tree", h.view().getLabel());
        assertTrue(h.engine.getActiveSession().getTransactions().isEmpty());
    }

    @Test public void furnaceSmeltShowsSmeltingOnHudPlus()
    {
        Harness h = new Harness();
        h.objectName = "Furnace";
        h.menu("Smelt", MenuAction.GAME_OBJECT_FIRST_OPTION);
        assertEquals("Furnace", h.view().getName());
        assertEquals("Smelting", h.view().getHudPlusName());
        assertEquals("Skilling", h.view().getCategory().getDisplayName());
    }

    @Test public void cancelledUnfulfilledObjectSelectionExpiresAndWalkCancelsIt()
    {
        Harness h = new Harness();
        h.menu("Chop down", MenuAction.GAME_OBJECT_FIRST_OPTION);
        h.clock.fixAt(6000L);
        assertFalse(h.view().isPresent());
        h.menu("Chop down", MenuAction.GAME_OBJECT_FIRST_OPTION);
        h.menu("Walk here", MenuAction.WALK);
        assertFalse(h.view().isPresent());
    }

    @Test public void examineAndBankChestDoNotBecomeActivities()
    {
        Harness h = new Harness();
        h.menu("Examine", MenuAction.EXAMINE_OBJECT);
        assertFalse(h.view().isPresent());
        h.objectName = "Bank chest";
        h.menu("Open", MenuAction.GAME_OBJECT_FIRST_OPTION);
        assertFalse(h.view().isPresent());
    }

    @Test public void existingAnimationDoesNotConfirmNewSelectedObject()
    {
        Harness h = new Harness();
        h.animation = 829;
        h.menu("Mine", MenuAction.GAME_OBJECT_FIRST_OPTION);
        h.tracker.onGameTick();
        assertTrue(h.view().isSelected());
    }

    @Test public void pauseIsPreservedAndIdentityHoldClearsTransientContext()
    {
        Harness h = new Harness();
        h.engine.togglePause(1100L);
        h.engage("Chicken", 41);
        assertTrue(h.engine.getActiveSession().isPaused());
        assertEquals("Chicken", h.view().getName());
        h.identityReady = false;
        h.tracker.onGameTick();
        assertFalse(h.view().isPresent());
    }

    @Test public void disabledDetectionAndOwnerChangesCannotExposeOldTarget()
    {
        Harness h = new Harness();
        h.engage("Chicken", 41);
        assertFalse(h.model.snapshot("another-session", 1000L).isPresent());
        h.detect = false;
        h.tracker.onGameTick();
        assertFalse(h.view().isPresent());
    }

    @Test public void localPlayerDeathClearsNpcAndBlocksReapplyWhileDead()
    {
        Harness h = new Harness();
        h.engage("Dark wizard", 172);
        assertEquals("Dark wizard", h.view().getName());
        // Client may still report the killer as interacting after death.
        h.dead = true;
        h.tracker.onInteractingChanged(new InteractingChanged(h.player, h.target));
        assertFalse(h.view().isPresent());
        h.tracker.onGameTick();
        assertFalse("Dead local must not re-arm NPC title from getInteracting",
            h.view().isPresent());
        h.dead = false;
        h.tracker.onGameTick();
        assertEquals("Dark wizard", h.view().getName());
    }

    @Test public void processXpDoesNotDisplaceFreshNpcOrOakTree()
    {
        Harness h = new Harness();
        h.engage("Chicken", 41);
        // A process XP signal cannot outrank the fresh actor target.
        assertEquals("Chicken", h.view().getHudPlusName());
        h.target = null;

        h.menu("Chop down", MenuAction.GAME_OBJECT_FIRST_OPTION);
        h.animation = 879;
        h.tracker.onGameTick();
        assertEquals("Oak tree", h.view().getHudPlusName());
        assertFalse(h.model.refreshObjectGraceForSkill("Cooking", h.clock.now()));
        assertEquals("Oak tree", h.view().getHudPlusName());
    }

    @Test public void mismatchedProcessXpDoesNotEraseSelectedStationTarget()
    {
        Harness h = new Harness();
        h.objectName = "Fire";
        h.menu("Cook", MenuAction.GAME_OBJECT_FIRST_OPTION);
        h.animation = 897;
        h.tracker.onGameTick();
        assertEquals("Cooking", h.view().getHudPlusName());
        assertFalse(h.model.refreshObjectGraceForSkill("Firemaking", h.clock.now()));
        assertEquals("Cooking", h.view().getHudPlusName());
    }

    @Test public void releaseNpcKeepsGraceButClearWipesImmediately()
    {
        Harness h = new Harness();
        h.engage("Dark wizard", 172);
        h.target = null;
        h.tracker.onGameTick();
        assertTrue("releaseNpc starts grace — still present", h.view().isPresent());
        assertEquals("Dark wizard", h.view().getName());
        h.model.clear();
        assertFalse("death path uses clear, not grace release", h.view().isPresent());
    }

    @Test public void latePrayerSignalsStaySuppressedOnlyWhileReleasedNpcIsRetained()
    {
        Harness h = new Harness();
        h.engage("Dark wizard", 172);
        h.target = null;
        h.tracker.onGameTick();

        assertTrue(h.tracker.hasNpcContext());
        for (int signal = 0; signal < 2; signal++) // delayed chat and XP can both arrive
        {
            boolean suppressed = HudPlusProcessLabels.shouldSuppressProcessSignalDuringNpcEngagement(
                "Prayer", h.tracker.hasNpcContext());
            assertTrue("late Prayer signal must not replace the retained NPC title", suppressed);
            assertEquals("Dark wizard", h.view().getHudPlusName());
        }

        h.clock.fixAt(1000L + InteractionContextModel.GRACE_MILLIS + 1L);
        assertFalse("suppression ends with the existing display grace", h.tracker.hasNpcContext());
        assertFalse(HudPlusProcessLabels.shouldSuppressProcessSignalDuringNpcEngagement(
            "Prayer", h.tracker.hasNpcContext()));
        assertFalse("target expires on its own; no process-signal clear is needed", h.view().isPresent());
    }

    @Test public void idleOrBankBlockPreventsStaleTickRearmUntilFreshEngage()
    {
        Harness h = new Harness();
        h.engage("Dark wizard", 172);
        assertEquals("Dark wizard", h.view().getName());
        // Character Idle / bank open: wipe + block getInteracting tick re-arm.
        h.tracker.clearAndBlockNpcTickRearm();
        assertFalse(h.view().isPresent());
        // Client may still report the old NPC as interacting after bank/Idle.
        h.tracker.onGameTick();
        assertFalse("blocked tick must not resurrect Dark wizard", h.view().isPresent());
        // Real engage clears the block.
        h.engage("Dark wizard", 172);
        assertEquals("Dark wizard", h.view().getName());
    }

    @Test public void deathClearStillAllowsTickRearmAfterRespawn()
    {
        Harness h = new Harness();
        h.engage("Dark wizard", 172);
        h.tracker.clear();
        assertFalse(h.view().isPresent());
        // Death path uses clear() without block — still fighting after respawn.
        h.tracker.onGameTick();
        assertEquals("Dark wizard", h.view().getName());
    }

    private static final class Harness
    {
        Actor target;
        int animation = -1;
        boolean dead = false;
        boolean identityReady = true;
        boolean detect = true;
        String objectName = "Oak tree";
        final HudClock clock = new HudClock();
        final InteractionContextModel model = new InteractionContextModel();
        final GpManagerConfig config = new GpManagerConfig() {
            public boolean enablePkTracking() { return false; }
            public boolean autoActivityDetection() { return detect; }
        };
        final GpManagerEngine engine = new GpManagerEngine(deltas -> Collections.emptyList(), new TransactionClassifier(), config);
        final Player player = proxy(Player.class, method -> {
            if ("getInteracting".equals(method)) return target;
            if ("getAnimation".equals(method)) return animation;
            if ("isDead".equals(method)) return dead;
            return null;
        });
        final ObjectComposition object = proxy(ObjectComposition.class,
            method -> "getName".equals(method) ? objectName : null);
        final Client client = proxy(Client.class, method -> {
            if ("getLocalPlayer".equals(method)) return player;
            if ("getObjectDefinition".equals(method)) return object;
            return null;
        });
        final InteractionContextTracker tracker = new InteractionContextTracker(client,
            new GameplayIngestionFacade(() -> true, () -> identityReady), engine, config, model, clock,
            new com.gpmanager.reward.RewardPresentationModel());

        Harness() { clock.fixAt(1000L); engine.ensureSession(1000L); }
        InteractionContextModel.View view() { return model.snapshot(engine.getActiveSession().getId(), clock.now()); }
        void engage(String name, int id)
        {
            target = npc(name, id);
            tracker.onInteractingChanged(new InteractingChanged(player, target));
        }
        void menu(String option, MenuAction action)
        {
            MenuEntry entry = proxy(MenuEntry.class, method -> {
                if ("getType".equals(method)) return action;
                if ("getOption".equals(method)) return option;
                if ("getTarget".equals(method)) return "Misleading menu text";
                if ("getIdentifier".equals(method)) return 1751;
                return null;
            });
            tracker.onMenuOptionClicked(new MenuOptionClicked(entry));
        }
    }

    private static NPC npc(String name, int id)
    {
        return proxy(NPC.class, method -> {
            if ("getName".equals(method)) return name;
            if ("getId".equals(method)) return id;
            if ("getIndex".equals(method)) return 1;
            if ("getCombatLevel".equals(method)) return "Fishing spot".equals(name) || "Shopkeeper".equals(name) ? 0 : 1;
            return null;
        });
    }

    private static <T> T proxy(Class<T> type, Function<String, Object> values)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (instance, method, args) -> {
            if ("equals".equals(method.getName())) return instance == args[0];
            if ("hashCode".equals(method.getName())) return System.identityHashCode(instance);
            Object value = values.apply(method.getName());
            if (value != null || !method.getReturnType().isPrimitive()) return value;
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == long.class) return 0L;
            if (method.getReturnType() == double.class) return 0d;
            if (method.getReturnType() == float.class) return 0f;
            return 0;
        }));
    }
}
