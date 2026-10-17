package com.gpmanager;

import com.google.gson.Gson;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.StatChanged;
import net.runelite.client.callback.ClientThread;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

/** Offline plugin lifecycle: only fresh XP after enabling can confirm an ongoing Make-X run. */
public class PluginProductionResumeTest
{
    LongSupplier clock = SafeMath.clock;
    static final long T0 = 1_700_000_000_000L;
    static final int LOG = 1515, BOW = 66;

    @After public void restoreClock() { SafeMath.clock = clock; }

    @Test public void firstFletchingActionAfterQuickToggleCountsWithoutAnotherClick()
    {
        Harness harness = new Harness();
        harness.enable(T0);
        harness.plugin.activity.armProduction("Fletching", T0);
        Transaction before = harness.make(Skill.FLETCHING, T0 + 600L);
        harness.disable(T0 + 2_000L);
        harness.inventory = new ContainerSnapshot(Map.of(LOG, 5L, BOW, 10L)); // Churn while disabled is baseline material.
        harness.enable(T0 + 3_000L);
        Transaction after = harness.make(Skill.FLETCHING, T0 + 3_600L);
        assertEquals(TransactionType.PROCESSING, after.getType());
        assertTrue(after.isCounted());
        assertTrue("already booked history is preserved", harness.plugin.engine.activeSession.getTransactions().contains(before));
        assertEquals(2, harness.plugin.engine.activeSession.getTransactions().size());
        assertEquals("only two enabled actions count", 120L, harness.plugin.engine.getMetrics(T0 + 6_000L).net);
    }

    @Test public void productionCanResumeAfterTheOldRunExpiredOrWithAFreshPlugin()
    {
        for (Skill skill : new Skill[]{Skill.FLETCHING, Skill.CRAFTING, Skill.SMITHING,
                Skill.HERBLORE, Skill.COOKING, Skill.CONSTRUCTION})
                {
            Harness harness = new Harness();
            harness.plugin.activity.armProduction(skill.getName(), T0 - 60_000L);
            harness.enable(T0);
            Transaction row = harness.make(skill, T0 + 600L);
            assertEquals(skill.getName(), TransactionType.PROCESSING, row.getType());
            assertTrue(row.isCounted());
            assertEquals(60L, harness.plugin.engine.getMetrics(T0 + 3_000L).net);
        }
    }

    @Test public void readinessStartsTheResumeWindowAfterASlowBind()
    {
        Harness harness = new Harness();
        harness.plugin.ready = false;
        harness.enable(T0);
        harness.xp.put(Skill.FLETCHING, 2_000); // Disabled/fenced XP is never the action being booked.
        harness.at(T0 + 60_000L);
        harness.plugin.ready = true;
        harness.plugin.initLoggedIn();
        assertEquals(TransactionType.PROCESSING, harness.make(Skill.FLETCHING, T0 + 60_600L).getType());
        assertEquals(60L, harness.plugin.engine.getMetrics(T0 + 63_000L).net);
    }

    @Test public void enablingOrUnchangedXpCannotConfirmAMixedChange()
    {
        Harness harness = new Harness();
        harness.enable(T0);
        harness.plugin.onStatChanged(new StatChanged(Skill.FLETCHING, 1_000, 1, 1));
        harness.plugin.onStatChanged(new StatChanged(Skill.FLETCHING, 999, 1, 1));
        Transaction row = harness.change(T0 + 600L);
        assertEquals(TransactionType.UNCERTAIN, row.getType());
        assertFalse(row.isCounted());
        assertEquals(0L, harness.plugin.engine.getMetrics(T0 + 3_000L).net);
    }

    @Test public void combatAndGatherXpCannotConfirmProduction()
    {
        for (Skill skill : new Skill[]{Skill.ATTACK, Skill.MAGIC, Skill.PRAYER, Skill.MINING, Skill.WOODCUTTING})
        {
            Harness harness = new Harness();
            harness.enable(T0);
            Transaction row = harness.make(skill, T0 + 600L);
            assertEquals(skill.getName(), TransactionType.UNCERTAIN, row.getType());
            assertFalse(row.isCounted());
        }
    }

    @Test public void anIdleEnableDoesNotKeepProductionArmedForever()
    {
        Harness harness = new Harness();
        harness.enable(T0);
        assertEquals(TransactionType.UNCERTAIN, harness.make(Skill.FLETCHING, T0 + 31_000L).getType());
    }

    @Test public void anOrdinaryProfileResumeCannotRenewTheEnableWindow()
    {
        Harness harness = new Harness();
        harness.enable(T0);
        harness.at(T0 + 60_000L);
        harness.plugin.initLoggedIn();
        assertEquals(TransactionType.UNCERTAIN, harness.make(Skill.FLETCHING, T0 + 60_600L).getType());
    }

    @Test public void aManualPauseStaysHeldThroughToggleAndProductionXp()
    {
        Harness harness = new Harness();
        harness.enable(T0);
        harness.plugin.engine.activeSession.pause(T0 + 600L, PauseReason.MANUAL);
        harness.disable(T0 + 1_200L);
        harness.enable(T0 + 1_800L);
        harness.at(T0 + 2_400L);
        harness.xp.put(Skill.FLETCHING, 1_100);
        harness.plugin.onStatChanged(new StatChanged(Skill.FLETCHING, 1_100, 1, 1));
        assertEquals(PauseReason.MANUAL, harness.plugin.engine.activeSession.getPauseReason());
        assertNull(harness.plugin.engine.processIfDirty(new ContainerSnapshot(Map.of(BOW, 1L)), T0 + 3_000L, null));
        assertTrue(harness.plugin.engine.activeSession.getTransactions().isEmpty());
    }

    static class Plugin extends GpManagerPlugin
    {
        boolean ready = true;
        @Override boolean canIngest() { return ready; }
        @Override void installPresentation() { }
        @Override void removePresentation() { }
        @Override void pulse() { }
    }

    class Harness
    {
        Plugin plugin = new Plugin();
        Map<Skill, Integer> xp = new EnumMap<>(Skill.class);
        ContainerSnapshot inventory = new ContainerSnapshot(Map.of(LOG, 1L));
        List<Runnable> callbacks = new ArrayList<>();

        Harness()
        {
            JsonCodec.bind(new Gson());
            for (Skill skill : Skill.values()) xp.put(skill, 1_000);
            plugin.gson = new Gson();
            plugin.config = new GpManagerConfig()
            {
                @Override public int stabilizationTicks() { return 0; }
            };
            plugin.engine = new Engine(deltas ->
            {
                List<Flow> flows = new ArrayList<>();
                deltas.forEach((id, quantity) ->
                {
                    int price = id == LOG ? 100 : 160;
                    flows.add(new Flow(id, id == LOG ? "Yew logs" : "Yew longbow (u)", quantity, price, quantity * price));
                });
                return flows;
            }, new TransactionClassifier(), plugin.config);
            plugin.engine.ensureSession(T0);
            ItemContainer container = ReviewFixesTest.proxy(ItemContainer.class, Map.of("getItems", new Item[0]));
            plugin.client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class}, (proxy, method, args) ->
            {
                if (method.getName().equals("getSkillExperience"))
                {
                    assertNotEquals("Overall has no individual skill XP slot", Skill.OVERALL, args[0]);
                    return xp.get(args[0]);
                }
                if (method.getName().equals("getGameState")) return GameState.LOGGED_IN;
                if (method.getName().equals("getItemContainer")) return container;
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                if (method.getReturnType() == long.class) return 123L;
                return null;
            });
            plugin.clientThread = new ClientThread()
            {
                @Override public void invokeLater(Runnable task) { callbacks.add(task); }
            };
            plugin.snapshotFactory = new ContainerSnapshotFactory(plugin.client, null)
            {
                @Override ContainerSnapshot capture(boolean equipment, boolean pouch) { return inventory; }
            };
            // Disk/UI are excluded; the real plugin enable, disable, baseline and XP handlers run.
            plugin.persistence = new Persistence(plugin.client, null, null, null, plugin.engine)
            {
                @Override void initAsync(Runnable task) { }
                @Override void shutdown(boolean persist) { fence(); }
            };
            plugin.geOfferLedger = new OfferSlots();
            plugin.activity = new ActivityDetector(plugin.config, plugin.engine);
            plugin.interactionContextTracker = GpManagerPluginProbe.tracker(plugin.client, plugin.engine, plugin.config);
            plugin.characterIdleTracker = new CharacterIdleTracker(plugin.client, new CharacterIdleModel());
            plugin.charges = new ChargeIntake(plugin.client, null, plugin.config, plugin.engine, null)
            {
                @Override void learnEyeCast(Skill skill, int tick, long now) { }
            };
        }

        void at(long now) { SafeMath.clock = () -> now; }
        void enable(long now)
        {
            at(now);
            plugin.startUp();
            for (Runnable task : new ArrayList<>(callbacks)) task.run();
            callbacks.clear();
        }
        void disable(long now) { at(now); plugin.shutDown(); }
        Transaction make(Skill skill, long now)
        {
            at(now);
            xp.put(skill, xp.get(skill) + 100);
            plugin.onStatChanged(new StatChanged(skill, xp.get(skill), 1, 1));
            return change(now);
        }
        Transaction change(long now)
        {
            Map<Integer, Long> next = new java.util.HashMap<>(inventory.quantities);
            next.merge(LOG, -1L, Long::sum);
            next.merge(BOW, 1L, Long::sum);
            inventory = new ContainerSnapshot(next);
            plugin.engine.markDirty();
            assertNull(plugin.engine.processIfDirty(inventory, now, null));
            Transaction row = plugin.engine.processIfDirty(inventory, now + 600L, null);
            assertNotNull(row);
            return row;
        }
    }
}
