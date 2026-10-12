package com.gpmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Owner 1.3.3: setting up and picking up a dwarf multicannon is neutral; only the cannonballs it
 * fired count. Balls it did not fire come back on pick-up and shrink the load. Owner 1.3.4: the
 * balls it takes are Supplies on the ledger, and a change mixing a ball loss with a gain stays
 * Review rather than being stamped.
 */
public class DwarfCannonTest
{
    private static final GpManagerConfig CONFIG = new GpManagerConfig()
    {
        @Override
        public int stabilizationTicks()
        {
            return 2;
        }

        @Override
        public boolean keepTransferAuditRows()
        {
            return true;
        }
    };

    private static final Map<Integer, String> NAMES = Map.of(
        ItemID.TWPART1, "Cannon base", ItemID.TWPART2, "Cannon stand",
        ItemID.TWPART3, "Cannon barrels", ItemID.TWPART4, "Cannon furnace",
        ItemID.MCANNONBALL, "Cannonball", ItemID.GRANITE_CANNONBALL, "Granite cannonball",
        ItemID.BRONZE_CANNONBALL, "Bronze cannonball",
        ItemID.DRAGON_CHAINSHOT_CANNONBALL, "Dragon chainshot cannonball",
        ItemID.COINS, "Coins");

    private final FlowValuator valuator = deltas ->
    {
        List<Flow> flows = new ArrayList<>();
        deltas.forEach((id, quantity) -> {
            int unit = id == ItemID.MCANNONBALL ? 200
                : id == ItemID.GRANITE_CANNONBALL ? 350
                : id == ItemID.BRONZE_CANNONBALL ? 30
                : id == ItemID.DRAGON_CHAINSHOT_CANNONBALL ? 300
                : id == ItemID.COINS ? 1 : 150_000;
            flows.add(new Flow(id, NAMES.get(id), quantity, unit, quantity * unit));
        });
        return flows;
    };

    @Test
    public void onlyTheFiredBallsCount()
    {
        Engine engine = new Engine(valuator, new TransactionClassifier(), CONFIG);
        engine.ensureSession(1_000L);
        engine.setBaseline(snapshot(true, 100L));

        engine.moveCannon();
        settle(engine, snapshot(false, 100L), 1_600L);
        assertEquals("setting it up is neutral", 0L, engine.getMetrics(5_000L).net);

        engine.loadCannon(6);
        Transaction load = settle(engine, snapshot(false, 70L), 6_000L);
        assertEquals("Loaded into the cannon", load.getNote());
        assertEquals(-6_000L, engine.getMetrics(10_000L).net);
        assertEquals("the balls are a supply, not an item loss", ActionKind.SUPPLIES,
            load.getActionKind());
        assertEquals(CostKind.SUPPLIES, CostKind.of(load, load.getFlows().get(0)));

        engine.moveCannon();
        settle(engine, snapshot(true, 80L), 12_000L);
        assertEquals("20 balls were fired; parts and 10 balls came back", -4_000L, engine.getMetrics(20_000L).net);
        assertEquals(0, engine.getReviewRows(20_000L).size());
    }

    @Test
    public void aBareCannonballLossIsSuppliesToo()
    {
        // The live shape: the load settled with no drop intent attached, so it booked as a plain
        // consumption; it is still the cannon's balls (owner 1.3.4).
        Engine engine = new Engine(valuator, new TransactionClassifier(), CONFIG);
        engine.ensureSession(1_000L);
        engine.setBaseline(snapshot(true, 100L));

        Transaction loss = settle(engine, snapshot(true, 55L), 2_000L);
        assertEquals("", loss.getNote());
        assertEquals(ActionKind.SUPPLIES, loss.getActionKind());
        assertEquals(CostKind.SUPPLIES, CostKind.of(loss, loss.getFlows().get(0)));
    }

    @Test
    public void graniteCannonballsAreSupplies()
    {
        Engine engine = new Engine(valuator, new TransactionClassifier(), CONFIG);
        engine.ensureSession(1_000L);
        engine.setBaseline(held(ItemID.GRANITE_CANNONBALL, 30L));

        Transaction loss = settle(engine, held(ItemID.GRANITE_CANNONBALL, 10L), 2_000L);
        assertEquals(ActionKind.SUPPLIES, loss.getActionKind());
        assertEquals(CostKind.SUPPLIES, CostKind.of(loss, loss.getFlows().get(0)));
    }

    @Test
    public void aChangeMixingBallsWithAnotherGainIsNotStamped()
    {
        Engine engine = new Engine(valuator, new TransactionClassifier(), CONFIG);
        engine.ensureSession(1_000L);
        engine.setBaseline(held(ItemID.MCANNONBALL, 100L, ItemID.COINS, 10L));

        Transaction mixed = settle(
            engine, held(ItemID.MCANNONBALL, 95L, ItemID.COINS, 20L), 2_000L);
        assertEquals(TransactionType.UNCERTAIN, mixed.getType());
        assertNull(mixed.getActionKind());
        assertEquals(1, engine.getReviewRows(9_000L).size());
    }

    @Test
    public void aSetUpAutoLoadAndUseLoadAreArmedAndOnlyFiredBallsBook()
    {
        // The live shape (owner 2026-10-13): Set-up auto-loads the balls it finds with the parts
        // and using a ball on the cannon loads too. Both must book on ground, so an Empty return
        // shrinks them instead of booking a gain, and only what never comes back books.
        Engine engine = new Engine(valuator, new TransactionClassifier(), CONFIG);
        engine.ensureSession(1_000L);
        engine.setBaseline(ContainerSnapshot.empty());
        settle(engine, held(ItemID.MCANNONBALL, 90L), 2_000L);
        assertEquals(18_000L, engine.getMetrics(10_000L).net);

        engine.moveCannon();
        engine.loadCannon(6);
        Transaction setup = settle(engine, held(ItemID.MCANNONBALL, 45L), 20_000L);
        assertEquals("Loaded into the cannon", setup.getNote());
        assertTrue(setup.isOnGround());
        assertEquals("the 45 it took book and can come back", 18_000L - 9_000L,
            engine.getMetrics(50_000L).net);

        engine.loadCannon(6);
        settle(engine, held(ItemID.MCANNONBALL, 25L), 60_000L);
        assertEquals(9_000L - 4_000L, engine.getMetrics(90_000L).net);

        settle(engine, held(ItemID.MCANNONBALL, 60L), 100_000L);
        assertEquals("the 35 returned shrink the loads; no gain books", 1L,
            engine.getActiveSession().getTransactions().stream()
                .filter(row -> row.isCounted() && row.getType() == TransactionType.GAIN)
                .count());
        assertEquals("30 balls stayed out: 65 loaded, 35 back", 18_000L - 30L * 200L,
            engine.getMetrics(150_000L).net);
        assertEquals(0, engine.getReviewRows(150_000L).size());

        wait(engine, held(ItemID.MCANNONBALL, 60L), 2_150L, 200_000L);
        assertEquals("nothing more books after the windows", 18_000L - 30L * 200L,
            engine.getMetrics(10_000_000L).net);
        assertEquals(1L,
            engine.getActiveSession().getTransactions().stream()
                .filter(row -> row.isCounted() && row.getType() == TransactionType.GAIN)
                .count());
    }

    @Test
    public void aSlowSetUpKeepsTheFurnaceNeutralAndArmsItsAutoLoad()
    {
        // The live shape (owner 2026-10-13, second session): the last part settles about 15
        // ticks after the click together with the auto-load, right at the old 15-tick custody
        // edge. The furnace must stay neutral and the 45 balls must still be a load.
        Engine engine = new Engine(valuator, new TransactionClassifier(), CONFIG);
        engine.ensureSession(1_000L);
        engine.setBaseline(snapshot(true, 90L));

        engine.moveCannon();
        engine.loadCannon(40);
        wait(engine, snapshot(true, 90L), 20L, 2_000L);
        Transaction load = settle(engine, snapshot(false, 45L), 20_000L);
        assertEquals("Loaded into the cannon", load.getNote());
        assertTrue(load.isOnGround());
        assertEquals("parts are neutral; only the loaded 45 book", -(45L * 200L),
            engine.getMetrics(90_000L).net);

        settle(engine, snapshot(false, 80L), 100_000L);
        assertEquals("the return shrinks the load", -(10L * 200L), engine.getMetrics(150_000L).net);
        assertEquals(0, engine.getReviewRows(150_000L).size());
    }

    @Test
    public void aLoadRightAfterALoginPrimeStillBooksAsALoad()
    {
        // The login priming clears the click intent; the parts custody must carry the load
        // evidence through it (owner live 2026-10-13, second session: the first set-up's
        // auto-load booked a full loss because the prime wiped its intent).
        Engine engine = new Engine(valuator, new TransactionClassifier(), CONFIG);
        engine.ensureSession(1_000L);
        engine.setBaseline(snapshot(true, 90L));

        engine.moveCannon();
        engine.loadCannon(40);
        engine.resetTracking(true);
        engine.setBaseline(snapshot(true, 90L));
        engine.markDirty();

        Transaction load = settle(engine, snapshot(false, 45L), 20_000L);
        assertEquals("Loaded into the cannon", load.getNote());
        assertEquals(-(45L * 200L), engine.getMetrics(50_000L).net);

        settle(engine, snapshot(false, 80L), 60_000L);
        assertEquals("the return shrinks the load, never a gain", -(10L * 200L),
            engine.getMetrics(90_000L).net);
        assertEquals(0L, engine.getActiveSession().getTransactions().stream()
            .filter(row -> row.isCounted() && row.getType() == TransactionType.GAIN).count());
    }

    @Test
    public void shipCannonballTiersAreSuppliesToo()
    {
        // Sailing's plain tiers, chainshot and incendiary are the same cannon economy: the balls
        // leaving the pack for a ship cannon read as Supplies like the dwarf load (owner 1.3.4
        // ship sweep).
        Engine engine = new Engine(valuator, new TransactionClassifier(), CONFIG);
        engine.ensureSession(1_000L);
        engine.setBaseline(held(ItemID.BRONZE_CANNONBALL, 40L, ItemID.DRAGON_CHAINSHOT_CANNONBALL, 5L));

        Transaction loss = settle(engine,
            held(ItemID.BRONZE_CANNONBALL, 25L, ItemID.DRAGON_CHAINSHOT_CANNONBALL, 2L), 2_000L);
        assertEquals(ActionKind.SUPPLIES, loss.getActionKind());
        assertEquals(CostKind.SUPPLIES, CostKind.of(loss, loss.getFlows().get(0)));
        assertEquals(-(15L * 30L) - (3L * 300L), engine.getMetrics(5_000L).net);
    }

    private static ContainerSnapshot snapshot(boolean parts, long balls)
    {
        Map<Integer, Long> held = new HashMap<>();
        if (parts)
        {
            held.put(ItemID.TWPART1, 1L);
            held.put(ItemID.TWPART2, 1L);
            held.put(ItemID.TWPART3, 1L);
            held.put(ItemID.TWPART4, 1L);
        }
        held.put(ItemID.MCANNONBALL, balls);
        return new ContainerSnapshot(held);
    }

    private static ContainerSnapshot held(Object... idsAndQuantities)
    {
        Map<Integer, Long> held = new HashMap<>();
        for (int index = 0; index < idsAndQuantities.length; index += 2)
        {
            held.merge((Integer) idsAndQuantities[index], (Long) idsAndQuantities[index + 1],
                Long::sum);
        }
        return new ContainerSnapshot(held);
    }

    private static Transaction settle(Engine engine, ContainerSnapshot snapshot, long firstTick)
    {
        Transaction row = null;
        for (long t = firstTick; row == null && t < firstTick + 6_000L; t += 600L) row = engine.processIfDirty(snapshot, t);
        assertNotNull(row);
        return row;
    }

    private static void wait(Engine engine, ContainerSnapshot snapshot, long ticks, long firstTick)
    {
        long now = firstTick;
        for (long tick = 0; tick < ticks; tick++) engine.processIfDirty(snapshot, now += 600L);
    }
}
