package com.gpmanager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Owner 1.3.3: a drop picked back up shrinks the Dropped row instead of adding a matching gain. */
public class DropPickupTest
{
    private static final int BOLTS = 9140;
    private static final GpManagerConfig CONFIG = new GpManagerConfig()
    {
        @Override
        public int stabilizationTicks()
        {
            return 2;
        }
    };

    private final FlowValuator valuator = deltas ->
    {
        List<Flow> flows = new ArrayList<>();
        deltas.forEach((id, quantity) -> flows.add(new Flow(id, "Iron bolts", quantity, 10, quantity * 10)));
        return flows;
    };

    private Engine dropped(long bolts)
    {
        Engine engine = new Engine(valuator, new TransactionClassifier(), CONFIG);
        engine.ensureSession(1_000L);
        engine.setBaseline(new ContainerSnapshot(Collections.singletonMap(BOLTS, 3L)));
        engine.noteDrop(BOLTS, 6);
        Transaction drop = settle(engine, snapshot(3L - bolts), 1_600L);
        assertEquals("Dropped", drop.note());
        return engine;
    }

    @Test
    public void pickingAllOfADropBackUpLeavesNeitherRow()
    {
        Engine engine = dropped(3L);
        assertEquals(-30L, engine.metrics(3_000L).net);
        settle(engine, snapshot(3L), 4_000L);
        assertEquals("Net is back where it started", 0L, engine.metrics(6_000L).net);
        List<Transaction> rows = engine.session().transactions();
        assertEquals("only the Dropped row is kept, excluded", 1, rows.size());
        assertEquals(Correction.IGNORE, rows.get(0).correction());
        assertFalse(rows.get(0).counts());
    }

    @Test
    public void pickingPartOfADropBackUpShrinksIt()
    {
        Engine engine = dropped(3L);
        settle(engine, snapshot(1L), 4_000L);
        Transaction drop = engine.session().transactions().get(0);
        assertTrue(drop.counts());
        assertEquals("2 bolts stay on the ground", -20L, engine.metrics(6_000L).net);
        assertEquals(1, engine.session().transactions().size());
    }

    @Test
    public void pickingUpMoreThanWasDroppedBooksOnlyTheExtra()
    {
        Engine engine = dropped(1L);
        Transaction extra = settle(engine, snapshot(5L), 4_000L);
        assertEquals(TransactionType.GAIN, extra.type());
        assertEquals("1 cancels the drop, 2 are new", 20L, extra.revenue());
        assertEquals(20L, engine.metrics(6_000L).net);
    }

    @Test
    public void pickingUpFiredBoltsShrinksTheShot()
    {
        Engine engine = new Engine(valuator, new TransactionClassifier(), CONFIG);
        engine.ensureSession(1_000L);
        engine.setBaseline(snapshot(100L));
        Transaction fired = settle(engine, snapshot(60L), 1_600L);
        assertEquals(ActionKind.FIRE, fired.action());
        assertEquals(400L, engine.metrics(3_000L).cost);
        engine.markDirty();
        settle(engine, snapshot(90L), 4_000L);
        Metrics metrics = engine.metrics(6_000L);
        assertEquals("never income", 0L, metrics.income);
        assertEquals("10 bolts stayed on the ground", 100L, metrics.cost);
    }

    private static final int DART = 807;
    private static final int SCALES = 12934;

    /** A blowpipe shot estimated at one dart and one scale, 10 gp each. */
    private Engine blowpipeShot()
    {
        Engine engine = new Engine(valuator, new TransactionClassifier(), CONFIG);
        engine.ensureSession(1_000L);
        engine.setBaseline(FixtureReads.empty());
        Transaction shot = engine.bookEstimatedCharge("pipe", "Toxic blowpipe", ActionKind.CAST,
            Arrays.asList(new Flow(DART, "Iron dart", -1L, 10, -10L),
                new Flow(SCALES, "Zulrah's scales", -1L, 10, -10L)), 1_100L);
        assertEquals(20L, shot.costs());
        return engine;
    }

    @Test
    public void pickingUpABlowpipeDartKeepsTheShotsScaleCost()
    {
        Engine engine = blowpipeShot();
        engine.markDirty();
        settle(engine, new ContainerSnapshot(Collections.singletonMap(DART, 1L)), 2_000L);
        List<Transaction> rows = engine.session().transactions();
        assertEquals("no gain row for the dart", 1, rows.size());
        assertTrue(rows.get(0).counts());
        assertEquals(0L, rows.get(0).quantity(DART, false));
        Metrics metrics = engine.metrics(4_000L);
        assertEquals(0L, metrics.income);
        assertEquals("the scale is still spent", 10L, metrics.cost);
    }

    @Test
    public void aCheckAfterADartPickupNeverCountsTheDartAgain()
    {
        Engine engine = blowpipeShot();
        engine.markDirty();
        settle(engine, new ContainerSnapshot(Collections.singletonMap(DART, 1L)), 2_000L);
        ChargeDelta delta = new ChargeDelta(ChargeCheck.ChargedItem.BLOWPIPE,
            Arrays.asList(new ChargeDelta.ComponentDelta(DART, -1L),
                new ChargeDelta.ComponentDelta(SCALES, -1L)), ChargeDelta.CheckResult.SPEND);
        engine.bookChargeSpend(delta, "Toxic blowpipe",
            Arrays.asList(new Flow(DART, "Iron dart", -1L, 10, -10L),
                new Flow(SCALES, "Zulrah's scales", -1L, 10, -10L)), 5_000L, "pipe");
        assertEquals("measured 20 less the 10 picked up", 10L, engine.metrics(6_000L).cost);
    }

    @Test
    public void aDartTheCheckAlreadyTookBackIsAnOrdinaryGain()
    {
        Engine engine = blowpipeShot();
        ChargeDelta delta = new ChargeDelta(ChargeCheck.ChargedItem.BLOWPIPE,
            Collections.singletonList(new ChargeDelta.ComponentDelta(SCALES, -1L)), ChargeDelta.CheckResult.SPEND);
        engine.bookChargeSpend(delta, "Toxic blowpipe",
            Collections.singletonList(new Flow(SCALES, "Zulrah's scales", -1L, 10, -10L)), 1_500L, "pipe");
        engine.clearPendingEstimates("pipe", Collections.singleton(DART));
        Transaction shot = engine.session().transactions().get(0);
        assertEquals("the Check found no dart used", 0L, shot.quantity(DART, false));

        engine.markDirty();
        Transaction gain = settle(engine, new ContainerSnapshot(Collections.singletonMap(DART, 1L)), 2_000L);
        assertEquals(TransactionType.GAIN, gain.type());
        assertEquals(10L, gain.revenue());
        assertEquals(10L, engine.metrics(4_000L).cost);
    }

    @Test
    public void scalesPickedUpAreAnOrdinaryGain()
    {
        Engine engine = blowpipeShot();
        engine.markDirty();
        Transaction gain = settle(engine, new ContainerSnapshot(Collections.singletonMap(SCALES, 1L)), 2_000L);
        assertEquals(TransactionType.GAIN, gain.type());
        assertEquals(20L, engine.metrics(4_000L).cost);
    }

    private static ContainerSnapshot snapshot(long bolts)
    {
        return new ContainerSnapshot(Collections.singletonMap(BOLTS, bolts));
    }

    private static Transaction settle(Engine engine, ContainerSnapshot snapshot, long firstTick)
    {
        assertNull(engine.processIfDirty(snapshot, firstTick, null));
        assertNull(engine.processIfDirty(snapshot, firstTick + 600L, null));
        Transaction tx = engine.processIfDirty(snapshot, firstTick + 1_200L, null);
        assertNotNull(tx);
        return tx;
    }
}
