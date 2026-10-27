package com.gpmanager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Owner report 2026-09-28: an Ice Barrage cast settling with a pickup showed as loose Death, Blood and
 * Water "losses" on the loot receipt, and a spell clicked while autocasting renamed the autocast.
 */
public class CastWithPickupTest
{
    private static final long T0 = 1_700_000_000_000L;
    private static final Map<Integer, String> NAMES = new HashMap<>();

    static
    {
        NAMES.put(560, "Death rune");
        NAMES.put(565, "Blood rune");
        NAMES.put(555, "Water rune");
        NAMES.put(562, "Chaos rune");
        NAMES.put(808, "Steel dart");
        NAMES.put(2434, "Prayer potion(4)");
        NAMES.put(139, "Prayer potion(3)");
        NAMES.put(561, "Nature rune");
        NAMES.put(554, "Fire rune");
        NAMES.put(995, "Coins");
        NAMES.put(4698, "Mud rune");
        NAMES.put(1359, "Rune axe");
    }

    @Test
    public void aCastSettlingWithAPickupBooksAsItsOwnCast()
    {
        Engine engine = engine();
        engine.startCustom("Nechryael", T0);
        Map<Integer, Long> held = new HashMap<>();
        held.put(560, 100L);
        held.put(565, 100L);
        held.put(555, 100L);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(held)));
        held.put(560, 96L);
        held.put(565, 98L);
        held.put(555, 94L);
        held.put(1359, 1L);
        engine.markDirty();
        List<Transaction> booked = new ArrayList<>();
        for (int i = 0; i < 3; i++)
        {
            Transaction settled = engine.processIfDirty(new ContainerSnapshot(new HashMap<>(held)),
                T0 + 600L + i * 600L, booked::add);
            if (settled != null)
            {
                booked.add(settled);
            }
        }
        Transaction cast = booked.stream().filter(t -> t.action() == ActionKind.CAST).findFirst().orElse(null);
        assertNotNull("the runes book as a Cast: " + booked, cast);
        assertEquals("Ice Barrage", cast.spellName());
        assertEquals(3, cast.flows().size());
        assertTrue("the pickup keeps only its gain", booked.stream().anyMatch(t -> t != cast
            && t.flows().size() == 1 && t.flows().get(0).item == 1359 && t.counts()));
    }

    @Test
    public void aQueuedClickNeverRenamesTheSpellItsRunesPaid()
    {
        Transaction barrage = new Transaction(T0, TransactionType.CONSUMPTION, Context.GENERIC,
            "", "Nechryael", true, Arrays.asList(new Flow(560, "Death rune", -4L, 187, -748L),
                new Flow(565, "Blood rune", -2L, 341, -682L), new Flow(555, "Water rune", -6L, 5, -30L)),
            Confidence.CONFIRMED, "fixture", null);
        barrage.setAction(ActionKind.CAST);
        barrage.setActionLabel(ActionLabel.of("Ice Burst"));
        assertEquals("Ice Barrage", barrage.spellName());

        Transaction alch = new Transaction(T0, TransactionType.CONSUMPTION, Context.GENERIC,
            "", "Magic", true, Arrays.asList(new Flow(561, "Nature rune", -1L, 100, -100L),
                new Flow(554, "Fire rune", -5L, 5, -25L)), Confidence.CONFIRMED, "fixture", null);
        alch.setAction(ActionKind.CAST);
        alch.setActionLabel(ActionLabel.of("High Level Alchemy"));
        assertEquals("a spell outside the combat table keeps its click", "High Level Alchemy", alch.spellName());
    }

    @Test
    public void aPotionAndACastKeepTheirOwnSupplyReceipts()
    {
        Engine engine = engine();
        engine.startCustom("Nechryael", T0);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(Map.of(
            562, 100L, 560, 100L, 555, 100L, 2434, 1L))));
        engine.noteConsume(2434, 10, false, ActionKind.DRINK, null);
        List<Transaction> booked = settle(engine, Map.of(562, 96L, 560, 98L, 555, 96L, 139, 1L));
        assertEquals("the drink and cast are separate", 2, booked.size());
        Transaction cast = booked.stream().filter(t -> t.action() == ActionKind.CAST)
            .findFirst().orElseThrow(AssertionError::new);
        Transaction drink = booked.stream().filter(t -> t.action() == ActionKind.DRINK)
            .findFirst().orElseThrow(AssertionError::new);
        assertEquals("Ice Burst", cast.spellName());
        assertEquals(3, cast.flows().size());
        assertEquals(2, drink.flows().size());
        assertEquals("no costs are lost or counted twice", -1_100L,
            booked.stream().mapToLong(Transaction::net).sum());
        assertSupplies(booked);
    }

    @Test
    public void aCastAndDartsSettlingWithLootAreSeparateSupplies()
    {
        Engine engine = engine();
        engine.startCustom("Nechryael", T0);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(Map.of(
            562, 100L, 560, 100L, 555, 100L, 808, 100L))));
        engine.markLoot(Map.of(1359, 1L), 30, "Loot from Greater Nechryael", "Greater Nechryael");
        List<Transaction> booked = settle(engine, Map.of(562, 96L, 560, 98L, 555, 96L, 808, 97L, 1359, 1L));
        assertEquals(3, booked.size());
        assertEquals(1L, booked.stream().filter(t -> t.action() == ActionKind.CAST).count());
        assertEquals(1L, booked.stream().filter(t -> t.action() == ActionKind.FIRE).count());
        Transaction loot = booked.stream().filter(t -> t.type() == TransactionType.LOOT)
            .findFirst().orElseThrow(AssertionError::new);
        assertEquals("loot owns no supply legs", 1, loot.flows().size());
        assertEquals(6_190L, booked.stream().mapToLong(Transaction::net).sum());
        assertSupplies(booked);
    }

    @Test
    public void aRunePickupKeepsItsMeasuredGainAndCannotInventAWaterBlast()
    {
        Engine engine = engine();
        engine.startCustom("Nechryael", T0);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(Map.of(562, 100L, 560, 100L, 555, 100L))));
        List<Transaction> booked = settle(engine, Map.of(562, 146L, 560, 98L, 555, 96L));
        assertEquals(2, booked.size());
        Transaction cast = booked.stream().filter(t -> t.action() == ActionKind.CAST)
            .findFirst().orElseThrow(AssertionError::new);
        assertEquals("incomplete spell evidence stays generic", "", cast.spellName());
        assertEquals("measured gain, without invented missing rune costs", 46L, booked.stream()
            .flatMap(t -> t.flows().stream()).filter(f -> f.item == 562).mapToLong(f -> f.quantity).sum());
        assertEquals(4_000L, booked.stream().mapToLong(Transaction::net).sum());
        assertSupplies(booked);
    }

    @Test
    public void alchingAmmunitionKeepsTheInputWithItsUtilitySpell()
    {
        Engine engine = engine();
        engine.startCustom("Alchemy", T0);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(Map.of(561, 100L, 554, 100L, 808, 100L))));
        engine.noteConsume(808, 10, false, ActionKind.CAST, ActionLabel.of("High Level Alchemy"));
        List<Transaction> booked = settle(engine, Map.of(561, 99L, 554, 95L, 808, 99L, 995, 6L));
        assertEquals("the dart was alched, never fired", 1, booked.size());
        assertEquals("High Level Alchemy", booked.get(0).spellName());
        assertEquals(4, booked.get(0).flows().size());
    }

    @Test
    public void bankMovesAndExplicitDropsNeverBecomeRoutineSpends()
    {
        for (boolean bank : new boolean[] {true, false})
        {
            Engine engine = engine();
            engine.startCustom("Nechryael", T0);
            engine.setBaseline(new ContainerSnapshot(new HashMap<>(Map.of(562, 100L, 560, 100L, 555, 100L))));
            if (bank) engine.markTransfer("Bank transfer", 10);
            else engine.noteDrop(560, 10);
            List<Transaction> booked = settle(engine, Map.of(562, 96L, 560, 98L, 555, 96L, 1359, 1L));
            assertTrue("stronger evidence prevents an automatic Cast", booked.stream()
                .noneMatch(t -> t.action() == ActionKind.CAST || t.action() == ActionKind.FIRE));
        }
    }

    @Test
    public void elementalRunesTurningIntoCombinationRunesDoNotProveACast()
    {
        // The existing 20261013-014425 recording: no spell click or catalytic rune spend.
        Engine engine = engine();
        engine.startCustom("Rune changes", T0);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(Map.of(555, 100L))));
        List<Transaction> booked = settle(engine, Map.of(555, 78L, 4698, 24L));
        assertEquals(1, booked.size());
        assertTrue(booked.stream().noneMatch(t -> t.action() == ActionKind.CAST));
    }

    private static List<Transaction> settle(Engine engine, Map<Integer, Long> after)
    {
        engine.markDirty();
        List<Transaction> booked = new ArrayList<>();
        for (int tick = 0; tick < 3; tick++)
        {
            Transaction row = engine.processIfDirty(new ContainerSnapshot(new HashMap<>(after)),
                T0 + 600L + tick * 600L, booked::add);
            if (row != null) booked.add(row);
        }
        return booked;
    }

    private static void assertSupplies(List<Transaction> booked)
    {
        for (Transaction row : booked)
            for (Flow flow : row.flows())
                if (flow.cost()) assertEquals("spent rune/ammo/dose is a supply", CostKind.SUPPLIES,
                    CostKind.of(row, flow));
    }

    private static Engine engine()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override
            public int stabilizationTicks()
            {
                return 1;
            }
        };
        return new Engine(deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                int unit = delta.getKey() == 1359 ? 7_202 : delta.getKey() == 2434 ? 400
                    : delta.getKey() == 139 ? 300 : delta.getKey() == 808 ? 4 : delta.getKey() == 995 ? 1 : 100;
                flows.add(new Flow(delta.getKey(), NAMES.get(delta.getKey()), delta.getValue(), unit,
                    delta.getValue() * unit, PriceSource.GRAND_EXCHANGE));
            }
            return flows;
        }, new TransactionClassifier(), config);
    }
}
