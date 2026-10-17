package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The tool and staff charge rows (owner 2026-10-14): crystal tools, infernal tools, echo boots
 * and the slayer's staff (e) are load-costed — their components book a counted Supplies spend at
 * load and a Check books nothing; the bone staff's chaos runes load neutrally, each cast spends
 * one and its Check measures; the upgrades keep the base's worth.
 */
public class ToolChargeRowsTest
{
    private static final long T0 = 1_000_000L;
    private static final int SHARD = ItemID.PRIF_CRYSTAL_SHARD;
    private static final int STONE = ItemID.SMOULDERING_STONE;
    private static final int ECHO = ItemID.ECHO_CRYSTAL;
    private static final int ENCHANT = ItemID.SLAYER_ENCHANTMENT;
    private static final int CHAOS = ItemID.CHAOSRUNE;

    private static final GpManagerConfig CONFIG = new GpManagerConfig()
    {
        @Override
        public int stabilizationTicks()
        {
            return 0;
        }

        @Override
        public boolean keepTransferAuditRows()
        {
            return true;
        }
    };

    @Test
    public void crystalToolShardsBookAtLoadAndTheirCheckBooksNothing()
    {
        Engine engine = HudBuilderTest.engine();
        engine.ensureSession(T0);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(Map.of(SHARD, 500L))));
        engine.markChargeLoad(ChargeCheck.ChargedItem.CRYSTAL_AXE, SHARD, "Crystal shard",
            "149:0:23673:CRYSTAL_AXE", ChargeIntake.LOAD_TICKS);
        engine.markDirty();
        for (int tick = 1; tick <= 5; tick++)
        {
            engine.processIfDirty(new ContainerSnapshot(new HashMap<>(Map.of(SHARD, 380L))),
                T0 + tick * 600L, null);
        }
        assertEquals("a dismantle loses the shard charges, so the load books", true,
            engine.getActiveSession().getTransactions().stream().anyMatch(Transaction::isCounted));
        assertTrue("and it is a cost", engine.getMetrics(T0 + 10_000L).net < 0L);

        ChargeIntake intake = new ChargeIntake(client(null, null), null, CONFIG, engine, null);
        assertTrue("the tool's Check is a read, consumed",
            intake.observeCheck("Your crystal axe has 9,000 charges remaining.", T0 + 12_000L));
        assertTrue("and books nothing",
            engine.getActiveSession().getTransactions().stream().noneMatch(
                row -> row.isCounted() && row.getNote() != null
                    && row.getNote().toLowerCase().contains("check")));
    }

    @Test
    public void infernalToolLoadsBookForTheStoneAndForTheDragonTool()
    {
        Engine engine = HudBuilderTest.engine();
        engine.ensureSession(T0);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(Map.of(STONE, 2L, ItemID.DRAGON_AXE, 1L))));
        engine.markChargeLoad(ChargeCheck.ChargedItem.INFERNAL_AXE, STONE, "Smouldering stone",
            "149:0:13241:INFERNAL_AXE", ChargeIntake.LOAD_TICKS);
        engine.markDirty();
        for (int tick = 1; tick <= 5; tick++)
        {
            engine.processIfDirty(new ContainerSnapshot(new HashMap<>(Map.of(STONE, 1L, ItemID.DRAGON_AXE, 1L))),
                T0 + tick * 600L, null);
        }
        assertTrue("the stone books as a cost", engine.getMetrics(T0 + 10_000L).net < 0L);

        engine.markChargeLoad(ChargeCheck.ChargedItem.INFERNAL_AXE, ItemID.DRAGON_AXE, "Dragon axe",
            "149:0:13241:INFERNAL_AXE", ChargeIntake.LOAD_TICKS);
        engine.markDirty();
        for (int tick = 1; tick <= 5; tick++)
        {
            engine.processIfDirty(new ContainerSnapshot(new HashMap<>(Map.of(STONE, 1L, ItemID.DRAGON_AXE, 0L))),
                T0 + 6_000L + tick * 600L, null);
        }
        assertTrue("the dragon-tool recharge books too",
            engine.getActiveSession().getTransactions().stream()
                .filter(Transaction::isCounted).count() >= 2);
    }

    @Test
    public void echoCrystalsAndTheSlayerEnchantmentBookAtLoad()
    {
        Engine engine = HudBuilderTest.engine();
        engine.ensureSession(T0);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(Map.of(ECHO, 1L, ENCHANT, 1L))));
        engine.markChargeLoad(ChargeCheck.ChargedItem.ECHO_BOOTS, ECHO, "Echo crystal",
            "149:0:28945:ECHO_BOOTS", ChargeIntake.LOAD_TICKS);
        engine.markDirty();
        for (int tick = 1; tick <= 5; tick++)
        {
            engine.processIfDirty(new ContainerSnapshot(new HashMap<>(Map.of(ECHO, 0L, ENCHANT, 1L))),
                T0 + tick * 600L, null);
        }
        assertTrue("the echo crystal books as a cost", engine.getMetrics(T0 + 10_000L).net < 0L);

        engine.markChargeLoad(ChargeCheck.ChargedItem.SLAYER_STAFF_E, ENCHANT, "Slayer's enchantment",
            "149:0:21255:SLAYER_STAFF_E", ChargeIntake.LOAD_TICKS);
        engine.markDirty();
        for (int tick = 1; tick <= 5; tick++)
        {
            engine.processIfDirty(new ContainerSnapshot(new HashMap<>(Map.of(ECHO, 0L, ENCHANT, 0L))),
                T0 + 6_000L + tick * 600L, null);
        }
        assertTrue("the enchantment books too",
            engine.getActiveSession().getTransactions().stream()
                .filter(Transaction::isCounted).count() >= 2);
    }

    @Test
    public void boneStaffChaosLoadsAreNeutralAndACastSpendsOne()
    {
        // The load: chaos runes move into charges, so nothing is a cost (Uncharge refunds them).
        Engine engine = HudBuilderTest.engine();
        engine.ensureSession(T0);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(Map.of(CHAOS, 500L))));
        engine.markChargeLoad(ChargeCheck.ChargedItem.BONE_STAFF, CHAOS, "Chaos rune",
            "149:0:28796:BONE_STAFF", ChargeIntake.LOAD_TICKS);
        engine.markDirty();
        Transaction load = null;
        for (int tick = 1; tick <= 5; tick++)
        {
            Transaction row = engine.processIfDirty(
                new ContainerSnapshot(new HashMap<>(Map.of(CHAOS, 400L))), T0 + tick * 600L, null);
            if (row != null) load = row;
        }
        assertNotNull(load);
        assertEquals("a refundable load is a neutral transfer", TransactionType.TRANSFER, load.getType());
        assertEquals(0L, engine.getMetrics(T0 + 10_000L).net);

        // A cast books one chaos rune from the pinned cast animations.
        Valuation valuation = ModelProbe.valuation(CONFIG, ToolChargeRowsTest::compositionOf,
            id -> id == CHAOS ? 100 : 0);
        Engine castEngine = new Engine(ToolChargeRowsTest::flows, new TransactionClassifier(), CONFIG);
        castEngine.startCustom("Scurrius", T0);
        castEngine.setBaseline(new ContainerSnapshot(new HashMap<>()));
        ChargeIntake intake = new ChargeIntake(
            client(container(EquipmentInventorySlot.WEAPON.getSlotIdx(), ItemID.RAT_BONE_STAFF), null),
            lookup(), CONFIG, castEngine, valuation);
        intake.onAnimation(1162, T0);
        List<Transaction> rows = castEngine.getActiveSession().getTransactions();
        assertEquals(rows.toString(), 1, rows.size());
        assertEquals(1L, rows.get(0).quantity(CHAOS, false));
        assertTrue(rows.get(0).isCounted());
    }

    @Test
    public void boneStaffCheckMeasuresTheCasts()
    {
        Engine engine = new Engine(ToolChargeRowsTest::flows, new TransactionClassifier(), CONFIG);
        String identity = ChargeIntake.weaponIdentity(
            InterfaceID.Inventory.ITEMS, 5, ItemID.RAT_BONE_STAFF, ChargeCheck.ChargedItem.BONE_STAFF);
        ChargeCheck first = ChargeCheck.parse("Your bone staff has 900 charges.");
        assertNotNull("the Check shape parses", first);
        assertEquals(ChargeCheck.ChargedItem.BONE_STAFF, first.variant);
        ChargeOutcomeTest.none(engine.observeCheck(first, identity, T0));
        ChargeDelta delta = engine.observeCheck(
            ChargeCheck.parse("Your bone staff has 850 charges."), identity, T0 + 1_000L);
        assertNotNull(delta);
        assertEquals(-50L, delta.componentDeltas.stream()
            .filter(component -> component.getItemId() == CHAOS)
            .mapToLong(ChargeDelta.ComponentDelta::getQuantityDelta).sum());
    }

    @Test
    public void theToolUpgradesSwapNeutral()
    {
        assertSwapNeutral(ItemID.DRAGON_AXE, ItemID.INFERNAL_AXE);
        assertSwapNeutral(ItemID.DRAGON_PICKAXE, ItemID.INFERNAL_PICKAXE);
        assertSwapNeutral(ItemID.DRAGON_HARPOON, ItemID.INFERNAL_HARPOON);
        assertSwapNeutral(ItemID.GUARDIAN_BOOTS, ItemID.ECHO_BOOTS);
        assertSwapNeutral(ItemID.SLAYER_STAFF, ItemID.SLAYER_STAFF_ENCHANTED);
        // A crystal tool's revert is the dismantle rule (only its shards cost), so it is not a
        // proxy pair.
        List<Flow> crystal = new ArrayList<>();
        crystal.add(new Flow(ItemID.CRYSTAL_HARPOON, "Crystal harpoon", -1L, 100, -100L));
        crystal.add(new Flow(ItemID.DRAGON_HARPOON, "Dragon harpoon", 1L, 100, 100L));
        assertFalse("the crystal tools keep their dismantle rule",
            FlowFilters.withoutSwaps(crystal).isEmpty());
    }

    private static void assertSwapNeutral(int base, int upgraded)
    {
        List<Flow> flows = new ArrayList<>();
        flows.add(new Flow(base, "Item " + base, -1L, 100, -100L));
        flows.add(new Flow(upgraded, "Item " + upgraded, 1L, 100, 100L));
        assertTrue("swap " + base + " -> " + upgraded, FlowFilters.withoutSwaps(flows).isEmpty());
    }

    private static List<Flow> flows(Map<Integer, Long> deltas)
    {
        List<Flow> flows = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : deltas.entrySet())
        {
            flows.add(new Flow(entry.getKey(), "Item " + entry.getKey(), entry.getValue(), 100,
                entry.getValue() * 100));
        }
        return flows;
    }

    private static ItemComposition compositionOf(int id)
    {
        String name = id == CHAOS ? "Chaos rune" : "Item " + id;
        return (ItemComposition) Proxy.newProxyInstance(ItemComposition.class.getClassLoader(),
            new Class<?>[] {ItemComposition.class}, (proxy, method, args) ->
            {
                if ("getName".equals(method.getName())) return name;
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                if (method.getReturnType() == long.class) return 0L;
                return null;
            });
    }

    private static ItemLookup lookup()
    {
        return new ItemLookup(null)
        {
            @Override
            ItemComposition composition(int id)
            {
                return compositionOf(id);
            }
        };
    }

    private static Client client(ItemContainer worn, ItemContainer inventory)
    {
        return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
            (proxy, method, args) ->
            {
                if ("getItemContainer".equals(method.getName()))
                {
                    int containerId = (int) args[0];
                    if (containerId == InventoryID.WORN) return worn;
                    if (containerId == InventoryID.INV) return inventory;
                    return null;
                }
                if ("getTickCount".equals(method.getName())) return 10;
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                if (method.getReturnType() == long.class) return 0L;
                return null;
            });
    }

    private static ItemContainer container(int slot, int itemId)
    {
        return (ItemContainer) Proxy.newProxyInstance(ItemContainer.class.getClassLoader(),
            new Class<?>[] {ItemContainer.class}, (proxy, method, args) ->
            {
                if ("getItem".equals(method.getName()))
                    return (int) args[0] == slot ? new Item(itemId, 1) : null;
                if ("size".equals(method.getName())) return 28;
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                if (method.getReturnType() == long.class) return 0L;
                return null;
            });
    }
}
