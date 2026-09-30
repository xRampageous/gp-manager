package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.MenuOptionClicked;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The interaction tracker titles NPCs only: scenery and opponent names never publish. */
public class InteractionContextTrackerTest
{
    private static final long T0 = 1_700_000_000_000L;

    @Test
    public void sceneryClicksNeverTitle()
    {
        Harness harness = new Harness();
        harness.clickObject("Bank", 100);
        assertEquals("a bank booth is not an activity", "", harness.last());
        harness.clickObject("Chop down", 200);
        assertEquals("a tree is not an activity either", "", harness.last());
    }

    @Test
    public void npcTargetsTitleButPlayerNamesDoNot()
    {
        Harness harness = new Harness();
        harness.interact(harness.npc("Goblin", 2));
        assertEquals("Goblin", harness.last());
        harness.interact(harness.player("Rival"));
        assertEquals("opponent names never display", "", harness.last());
    }

    @Test
    public void aCombatTargetSurvivesSceneryClicks()
    {
        Harness harness = new Harness();
        harness.interact(harness.npc("Vorkath", 732));
        harness.clickObject("Open", 300);
        assertEquals("Vorkath", harness.last());
    }

    private static final class Harness
    {
        private final Am engine;
        private final Client client;
        private final Player local;
        private final InteractionContextTracker tracker;
        private String published = "";

        Harness()
        {
            GpManagerConfig config = new GpManagerConfig()
            {
                @Override
                public int stabilizationTicks()
                {
                    return 0;
                }
            };
            engine = new Am(deltas ->
            {
                List<Ab> flows = new ArrayList<>();
                deltas.forEach((id, quantity) -> flows.add(new Ab(id, "Item " + id, quantity, 50, quantity * 50)));
                return flows;
            }, new TransactionClassifier(), config);
            engine.rm(T0);
            local = player("Local");
            client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
                (proxy, method, args) ->
                {
                    switch (method.getName())
                    {
                        case "getLocalPlayer": return local;
                        case "getObjectDefinition": return object(args == null || args.length == 0 ? "" : "Scenery");
                        default:
                            if (method.getReturnType() == boolean.class) return false;
                            if (method.getReturnType() == int.class) return 0;
                            if (method.getReturnType() == long.class) return 0L;
                            return null;
                    }
                });
            tracker = new InteractionContextTracker(client, engine, config);
            tracker.mf(() -> true, (name, combat) -> published = name == null ? "" : name);
        }

        String last()
        {
            return published;
        }

        void interact(Actor target)
        {
            tracker.onInteractingChanged(new InteractingChanged(local, target));
        }

        void clickObject(String option, int id)
        {
            tracker.onMenuOptionClicked(new MenuOptionClicked(menu(option, id, MenuAction.GAME_OBJECT_FIRST_OPTION)));
        }

        Player player(String name)
        {
            return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) ->
                {
                    if ("getName".equals(method.getName())) return name;
                    if ("isDead".equals(method.getName())) return false;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                });
        }

        NPC npc(String name, int combatLevel)
        {
            return (NPC) Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[] {NPC.class},
                (proxy, method, args) ->
                {
                    if ("getName".equals(method.getName())) return name;
                    if ("getCombatLevel".equals(method.getName())) return combatLevel;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                });
        }

        private static ObjectComposition object(String name)
        {
            return (ObjectComposition) Proxy.newProxyInstance(ObjectComposition.class.getClassLoader(),
                new Class<?>[] {ObjectComposition.class}, (proxy, method, args) ->
                {
                    if ("getName".equals(method.getName())) return name;
                    if ("getImpostorIds".equals(method.getName())) return null;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                });
        }

        private static MenuEntry menu(String option, int id, MenuAction action)
        {
            return (MenuEntry) Proxy.newProxyInstance(MenuEntry.class.getClassLoader(),
                new Class<?>[] {MenuEntry.class}, (proxy, method, args) ->
                {
                    if ("getOption".equals(method.getName())) return option;
                    if ("getId".equals(method.getName())) return id;
                    if ("getType".equals(method.getName())) return action;
                    if ("getIdentifier".equals(method.getName())) return id;
                    if ("getItemOp".equals(method.getName())) return 0;
                    if ("isItemOp".equals(method.getName())) return false;
                    if ("getWidget".equals(method.getName())) return null;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                });
        }
    }
}
