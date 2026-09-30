package com.gpmanager;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Production-seam exact spell evidence: the real plugin menu path (spellbook selection then
 * widget-target click) must retain the exact spell name through settlement, while generic and
 * stale interactions honestly fall back to Cast. Target names are never spell identity.
 */
public class ExactSpellEvidenceProductionTest
{
    private static final long T0 = 1_000_000_000_000L;
    private static final int DEATH_RUNE = ItemID.DEATHRUNE;
    private static final int CHAOS_RUNE = ItemID.CHAOSRUNE;
    private static final int WATER_RUNE = ItemID.WATERRUNE;
    private static final int BLOOD_RUNE = ItemID.BLOODRUNE;
    private static final int NATURE_RUNE = ItemID.NATURERUNE;
    private static final int ASTRAL_RUNE = ItemID.ASTRALRUNE;
    private static final int EARTH_RUNE = ItemID.EARTHRUNE;
    private static final int[] ICE_BURST_RUNES = {DEATH_RUNE, CHAOS_RUNE, WATER_RUNE};

    @Test
    public void iceBurstTwoStageWidgetTargetBooksTheExactName() throws Exception
    {
        Harness harness = harness("Vorkath");
        Ac cast = harness.cast("Ice Burst", "Guard", ICE_BURST_RUNES, T0 + 100L);

        assertNotNull("the rune spend settles", cast);
        assertEquals(Ai.CONSUMPTION, cast.getType());
        assertTrue(cast.isCounted());
        assertEquals(Au.CAST, cast.getActionKind());
        assertNotNull("the two-stage interaction keeps its exact spell", cast.uc());
        assertEquals("Ice Burst", cast.uc().value());
        assertEquals(-180L, cast.getNet());
        assertEquals("rune accounting is untouched", 3, cast.getFlows().size());
        assertEquals("the spell spend stays a supply",
            CostKind.SUPPLIES, CostKind.of(cast, cast.getFlows().get(0)));

        Ca.Recent row = recentFor(harness, cast.getId());
        assertEquals("Ice Burst", row.name);
        assertEquals("Cast", LivePage.metaOf(row));
    }

    @Test
    public void iceBarrageTwoStageWidgetTargetBooksItsOwnExactName() throws Exception
    {
        Harness harness = harness("Vorkath");
        Ac cast = harness.cast("Ice Barrage", "Guard",
            new int[] {DEATH_RUNE, BLOOD_RUNE, WATER_RUNE}, T0 + 100L);

        assertNotNull(cast);
        assertNotNull(cast.uc());
        assertEquals("Ice Barrage", cast.uc().value());
        assertEquals("Ice Barrage", recentFor(harness, cast.getId()).name);
    }

    @Test
    public void replacementSelectionKeepsOnlyTheLatestSpell() throws Exception
    {
        Harness harness = harness("Vorkath");
        harness.select("Ice Burst");
        harness.select("Ice Barrage");
        harness.target("Guard");
        Ac cast = harness.settle(ICE_BURST_RUNES, T0 + 100L);

        assertNotNull(cast);
        assertNotNull(cast.uc());
        assertEquals("a later selection replaces the earlier evidence",
            "Ice Barrage", cast.uc().value());
    }

    @Test
    public void expiredEvidenceFallsBackToGenericCast() throws Exception
    {
        Harness harness = harness("Vorkath");
        harness.select("Ice Burst");
        harness.target("Guard");
        // No matching consumption: the bounded evidence (18 ticks at the default settings)
        // expires on its own ticks.
        harness.tickStable(24, T0 + 100L);
        Ac cast = harness.settle(ICE_BURST_RUNES, T0 + 8_000L);

        assertNotNull(cast);
        assertEquals(Au.CAST, cast.getActionKind());
        assertNull("expired evidence must never name a later rune loss",
            cast.uc());
        assertEquals("Cast", recentFor(harness, cast.getId()).name);
    }

    @Test
    public void targetNamesNeverBecomeSpellNames() throws Exception
    {
        Harness harness = harness("Vorkath");
        Ac burst = harness.cast("Ice Burst", "Vengeance", ICE_BURST_RUNES, T0 + 100L);
        assertNotNull(burst);
        assertEquals("the NPC name is not spell evidence",
            "Ice Burst", burst.uc().value());

        Harness plain = harness("Vorkath");
        plain.targetNamed("Ice Burst");
        Ac generic = plain.settle(ICE_BURST_RUNES, T0 + 100L);
        assertNotNull(generic);
        assertNull("a target named like a spell cannot fabricate one",
            generic.uc());
        assertEquals("Cast", recentFor(plain, generic.getId()).name);
    }

    @Test
    public void selectedWidgetMustBelongToTheSpellbook() throws Exception
    {
        Harness harness = harness("Vorkath");
        harness.selectWidget(widget(99 << 16 | 3, "<col=ff9040>Ice Burst</col>", "Cast"));
        harness.target("Guard");
        Ac cast = harness.settle(ICE_BURST_RUNES, T0 + 100L);

        assertNotNull(cast);
        assertNull("a selected widget outside the spellbook is not spell evidence",
            cast.uc());
    }

    @Test
    public void vengeanceSelfCastUsesDirectSpellbookEvidence() throws Exception
    {
        Harness harness = harness("Vorkath");
        harness.select("Vengeance");
        Ac cast = harness.settle(new int[] {DEATH_RUNE, ASTRAL_RUNE, EARTH_RUNE}, T0 + 100L);

        assertNotNull(cast);
        assertNotNull(cast.uc());
        assertEquals("Vengeance", cast.uc().value());
    }

    @Test
    public void highLevelAlchemyUsesTheSelectedSpellOnAWidgetTarget() throws Exception
    {
        Harness harness = harness("Vorkath");
        harness.select("High Level Alchemy");
        harness.targetOnWidget("Nature rune");
        Ac cast = harness.settle(new int[] {NATURE_RUNE}, T0 + 100L);

        assertNotNull(cast);
        assertNotNull(cast.uc());
        assertEquals("High Level Alchemy", cast.uc().value());
    }

    @Test
    public void profileFenceClearsPendingSpellEvidenceButKeepsBookedLabels() throws Exception
    {
        Harness harness = harness("Vorkath");
        Ac cast = harness.cast("Ice Burst", "Guard", ICE_BURST_RUNES, T0 + 100L);
        assertNotNull(cast);
        long net = cast.getNet();
        int flows = cast.getFlows().size();

        // A pending, unbooked selection exists when the owner fence runs.
        harness.select("Ice Burst");
        harness.target("Guard");
        harness.engine.ov();

        assertNotNull("booked schema-105 labels survive the presentation owner fence",
            cast.uc());
        assertEquals("Ice Burst", cast.uc().value());
        assertEquals(net, cast.getNet());
        assertEquals(flows, cast.getFlows().size());
        assertEquals(Ai.CONSUMPTION, cast.getType());
        assertTrue(cast.isCounted());

        Ac later = harness.settle(ICE_BURST_RUNES, T0 + 2_000L);
        assertNotNull(later);
        assertEquals(Au.CAST, later.getActionKind());
        assertNull("pending pre-fence evidence must not label a later cast",
            later.uc());
    }

    @Test
    public void lifecyclePauseClearsStaleSpellEvidence() throws Exception
    {
        Harness harness = harness("Vorkath");
        harness.select("Ice Burst");
        harness.target("Guard");
        // Logout/relog fence: the lifecycle pause clears transient action evidence, and the
        // post-login baseline priming keeps the first change from inheriting anything.
        harness.engine.acu(T0 + 200L);
        harness.tickStable(2, T0 + 300L);
        harness.engine.resume(T0 + 1_000L, Ed.LIFECYCLE);
        harness.engine.setBaseline(new Cc(harness.inventory));
        Ac cast = harness.settle(ICE_BURST_RUNES, T0 + 2_000L);

        assertNotNull(cast);
        assertEquals(Au.CAST, cast.getActionKind());
        assertNull("a paused/relogged session must not inherit pre-logout spell evidence",
            cast.uc());
    }

    @Test
    public void ledgerGroupsRepeatedExactSpellsAndSeparatesDifferentSpells() throws Exception
    {
        Harness harness = harness("Vorkath");
        assertNotNull(harness.cast("Ice Burst", "Guard", ICE_BURST_RUNES, T0 + 100L));
        assertNotNull(harness.cast("Ice Burst", "Guard", ICE_BURST_RUNES, T0 + 2_000L));
        assertNotNull(harness.cast("Ice Barrage", "Guard", ICE_BURST_RUNES, T0 + 3_000L));
        // A generic cast with no exact evidence stays generic.
        harness.targetNamed("Guard");
        assertNotNull(harness.settle(ICE_BURST_RUNES, T0 + 4_000L));

        Ao data = Ao.capture(harness.engine, T0 + 5_000L,
            new Ao.Entry(Ao.Scope.CURRENT_GRIND, null, null,
                Ao.Bs.SUPPLIES, "", null, null, null, null));
        Br.Group burstGroup = groupNamed(data.costs.groups, "Ice Burst", "Cast");
        Br.Group barrage = groupNamed(data.costs.groups, "Ice Barrage", "Cast");
        Br.Group generic = groupNamed(data.costs.groups, "Cast", "Cast");
        assertNotNull(burstGroup);
        assertNotNull(barrage);
        assertNotNull(generic);
        assertTrue("repeated exact casts aggregate before paging", burstGroup.actionGroup());
        assertEquals(2, burstGroup.receiptCount);
        assertEquals(-360L, burstGroup.value);
        assertEquals("a different spell is never merged in", 1, barrage.receiptCount);
        assertEquals(-180L, barrage.value);
        assertEquals("a generic cast is never mislabelled", 1, generic.receiptCount);
        assertFalse(burstGroup.semanticGroupId.equals(barrage.semanticGroupId));
        assertFalse(burstGroup.semanticGroupId.equals(generic.semanticGroupId));
        assertEquals("grouped exact spells reconcile to the canonical session net",
            -720L, data.costs.total);
    }

    // ── production harness ─────────────────────────────────────────────────────────────────────

    private static final class Harness
    {
        final Am engine;
        final GpManagerPlugin plugin;
        final MutableSpellClient client;
        final Map<Integer, Long> inventory = new HashMap<>();

        Harness(Am engine, GpManagerPlugin plugin, MutableSpellClient client)
        {
            this.engine = engine;
            this.plugin = plugin;
            this.client = client;
        }

        void select(String spell)
        {
            client.selected = spellWidget(spell);
            plugin.onMenuOptionClicked(click("Cast", spell, MenuAction.CC_OP, spellWidget(spell), -1));
        }

        void selectWidget(Widget widget)
        {
            client.selected = widget;
            plugin.onMenuOptionClicked(click("Cast", "unknown", MenuAction.CC_OP, widget, -1));
        }

        void target(String targetName)
        {
            plugin.onMenuOptionClicked(click("Cast", targetName, MenuAction.WIDGET_TARGET_ON_NPC, null, -1));
        }

        void targetNamed(String targetName)
        {
            client.selected = null;
            target(targetName);
        }

        void targetOnWidget(String targetName)
        {
            plugin.onMenuOptionClicked(click("Cast", targetName, MenuAction.WIDGET_TARGET_ON_WIDGET, null, -1));
        }

        Ac cast(String spell, String targetName, int[] runes, long now)
        {
            select(spell);
            target(targetName);
            return settle(runes, now);
        }

        Ac settle(int[] runes, long now)
        {
            for (int rune : runes)
            {
                inventory.put(rune, inventory.get(rune) - 1L);
            }
            return settleStable(engine, inventory, now);
        }

        void tickStable(int ticks, long now)
        {
            Cc snapshot = new Cc(inventory);
            for (int i = 0; i < ticks; i++)
            {
                engine.adj(snapshot, now + i * 600L);
            }
        }
    }

    private static Harness harness(String sessionName) throws Exception
    {
        Am engine = productionEngine();
        long now = T0;
        engine.ajl(sessionName, Cx.AUTO, now);
        MutableSpellClient client = new MutableSpellClient();
        GpManagerPlugin plugin = plugin(engine, client.client());
        Harness harness = new Harness(engine, plugin, client);
        harness.inventory.put(ItemID.COINS, 100_000L);
        for (int rune : new int[] {DEATH_RUNE, CHAOS_RUNE, WATER_RUNE, BLOOD_RUNE, NATURE_RUNE,
            ASTRAL_RUNE, EARTH_RUNE})
        {
            harness.inventory.put(rune, 20L);
        }
        engine.setBaseline(new Cc(harness.inventory));
        return harness;
    }

    private static Ca.Recent recentFor(Harness harness, String transactionId)
    {
        Ca snapshot = Ca.capture(harness.engine, T0 + 60_000L, Dz.NONE);
        return snapshot.recent.stream()
            .filter(row -> transactionId.equals(row.receiptId))
            .findFirst().orElseThrow(AssertionError::new);
    }

    private static Br.Group groupNamed(
        List<Br.Group> groups, String name, String action)
    {
        for (Br.Group group : groups)
        {
            if (name.equals(group.primaryName) && action.equals(group.actionLabel.isEmpty() ? ""
                : group.actionLabel))
            {
                return group;
            }
        }
        return null;
    }

    private static Ac settleStable(Am engine, Map<Integer, Long> next, long now)
    {
        engine.yz();
        Cc snapshot = new Cc(next);
        Ac result = null;
        for (int i = 0; i < 3; i++)
        {
            Ac settled = engine.adj(snapshot, now + i * 600L);
            if (settled != null)
            {
                result = settled;
            }
        }
        return result;
    }

    private static Am productionEngine()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 2; }
            @Override public boolean keepTransferAuditRows() { return true; }
        };
        return new Am(deltas ->
        {
            List<Ab> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                int id = delta.getKey();
                flows.add(new Ab(id, runeName(id), delta.getValue(), price(id),
                    delta.getValue() * price(id),
                    id == ItemID.COINS ? Av.FACE_VALUE : Av.GRAND_EXCHANGE));
            }
            return flows;
        }, new TransactionClassifier(), config);
    }

    private static GpManagerPlugin plugin(Am engine, Client client) throws Exception
    {
        GpManagerConfig config = new GpManagerConfig() {};
        GpManagerPlugin plugin = new GpManagerPluginProbe();
        set(plugin, "engine", engine);
        set(plugin, "config", config);
        set(plugin, "client", client);
        set(plugin, "interactionContextTracker",
            GpManagerPluginProbe.tracker(client, engine, config));
        set(plugin, "charges", new ChargeIntake(client, null, config, engine, null));
        return plugin;
    }

    private static MenuOptionClicked click(String option, String target, MenuAction action,
        @javax.annotation.Nullable Widget widget, int itemId)
    {
        MenuEntry entry = (MenuEntry) Proxy.newProxyInstance(MenuEntry.class.getClassLoader(),
            new Class<?>[] {MenuEntry.class}, (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getOption": return option;
                    case "getTarget": return target;
                    case "getType": return action;
                    case "getWidget": return widget;
                    case "getItemId": return itemId;
                    case "getIdentifier": return 0;
                    case "getItemOp": return 0;
                    case "isItemOp": return false;
                    default:
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class) return 0;
                        if (method.getReturnType() == long.class) return 0L;
                        return null;
                }
            });
        return new MenuOptionClicked(entry);
    }

    private static Widget spellWidget(String spell)
    {
        return widget(WidgetID.SPELLBOOK_GROUP_ID << 16 | 17,
            "<col=ff9040>" + spell + "</col>", "Cast");
    }

    private static Widget widget(int id, String text, String name)
    {
        return (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(),
            new Class<?>[] {Widget.class}, (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getId": return id;
                    case "getText": return text;
                    case "getName": return name;
                    case "getItemId": return -1;
                    default:
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class) return 0;
                        if (method.getReturnType() == long.class) return 0L;
                        return null;
                }
            });
    }

    /** Client double whose selected spell widget can be set per interaction. */
    private static final class MutableSpellClient
    {
        @javax.annotation.Nullable
        Widget selected;

        Client client()
        {
            return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
                new Class<?>[] {Client.class}, (proxy, method, args) ->
                {
                    switch (method.getName())
                    {
                        case "getGameState": return GameState.LOGGED_IN;
                        case "getSelectedWidget": return selected;
                        case "getWidget": return null;
                        case "getItemContainer": return null;
                        case "getLocalPlayer": return null;
                        case "getTickCount": return 0;
                        default:
                            if (method.getReturnType() == boolean.class) return false;
                            if (method.getReturnType() == int.class) return 0;
                            if (method.getReturnType() == long.class) return 0L;
                            return null;
                    }
                });
        }
    }

    private static String runeName(int id)
    {
        if (id == ItemID.COINS) return "Coins";
        if (id == DEATH_RUNE) return "Death rune";
        if (id == CHAOS_RUNE) return "Chaos rune";
        if (id == WATER_RUNE) return "Water rune";
        if (id == BLOOD_RUNE) return "Blood rune";
        if (id == NATURE_RUNE) return "Nature rune";
        if (id == ASTRAL_RUNE) return "Astral rune";
        if (id == EARTH_RUNE) return "Earth rune";
        return "Item " + id;
    }

    private static int price(int id)
    {
        if (id == ItemID.COINS) return 1;
        if (id == DEATH_RUNE) return 100;
        if (id == CHAOS_RUNE) return 50;
        if (id == WATER_RUNE) return 30;
        if (id == BLOOD_RUNE) return 200;
        if (id == NATURE_RUNE) return 100;
        if (id == ASTRAL_RUNE) return 50;
        if (id == EARTH_RUNE) return 10;
        return 10;
    }

    private static void set(Object target, String name, Object value) throws Exception
    {
        Field field = GpManagerPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
