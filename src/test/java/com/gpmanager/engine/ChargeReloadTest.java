package com.gpmanager;

import java.util.*;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * A Check counts darts loaded since the last Check as loaded, not unused: picked-up darts reloaded
 * before a Check are not subtracted twice, and an exact top-up still books what was fired.
 */
public class ChargeReloadTest
{
    private static final int DART = 807;
    private static final int SCALES = 12934;
    private static final String PIPE = "pipe";

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
        deltas.forEach((id, quantity) -> flows.add(new Flow(id, id == DART ? "Iron dart" : "Zulrah's scales",
            quantity, 10, quantity * 10, PriceSource.GRAND_EXCHANGE)));
        return flows;
    };

    private static Flow dartsSpent(long quantity)
    {
        return new Flow(DART, "Iron dart", -quantity, 10, -quantity * 10, PriceSource.GRAND_EXCHANGE);
    }

    private static ChargeCheck read(long darts)
    {
        return ChargeCheck.parse("Darts: Iron dart x " + darts + ". Scales: 100 (1.0%).");
    }

    /** A Checked pipe holding 100 darts, then {@code shots} shots of one dart each. */
    private Engine fired(int shots)
    {
        Engine engine = new Engine(valuator, new TransactionClassifier(), CONFIG);
        engine.ensureSession(1_000L);
        engine.setBaseline(ContainerSnapshot.empty());
        engine.observeCheck(read(100), PIPE, 1_000L);
        for (int shot = 0; shot < shots; shot++)
        {
            engine.bookEstimatedCharge(PIPE, "Toxic blowpipe", ActionKind.CAST,
                Collections.singletonList(dartsSpent(1)), 1_100L + shot);
        }
        return engine;
    }

    private static void settle(Engine engine, long darts, long firstTick)
    {
        ContainerSnapshot snapshot = new ContainerSnapshot(Collections.singletonMap(DART, darts));
        engine.markDirty();
        for (long tick = firstTick; tick <= firstTick + 1_800L; tick += 600L)
        {
            engine.processIfDirty(snapshot, tick, null);
        }
    }

    /** Runs a measured Check the way ChargeIntake does: book the spend, then close the window. */
    private static void check(Engine engine, long darts, long now)
    {
        ChargeDelta delta = engine.observeCheck(read(darts), PIPE, now);
        assertEquals(ChargeDelta.CheckResult.SPEND, delta.result);
        var losses = new ArrayList<Flow>();
        for (ChargeDelta.ComponentDelta component : delta.componentDeltas)
        {
            losses.add(component.itemId == DART ? dartsSpent(-component.quantityDelta)
                : new Flow(SCALES, "Zulrah's scales", component.quantityDelta, 10,
                    component.quantityDelta * 10, PriceSource.GRAND_EXCHANGE));
        }
        engine.bookChargeSpend(delta, "Toxic blowpipe", losses, now, PIPE);
        engine.clearPendingEstimates(PIPE, delta.unchanged);
    }

    @Test
    public void pickedUpDartsReloadedBeforeACheckStaySpentOnce()
    {
        Engine engine = fired(10);
        settle(engine, 5L, 2_000L);
        assertEquals("5 picked back up", 50L, engine.getMetrics(5_000L).costs);

        assertTrue(engine.markChargeLoad(ChargeCheck.ChargedItem.BLOWPIPE, DART, "Iron dart", PIPE, 20));
        settle(engine, 0L, 6_000L);
        assertEquals("the reload is a transfer", 50L, engine.getMetrics(9_000L).costs);

        check(engine, 95L, 10_000L);
        assertEquals("10 fired, 5 recovered: 5 darts lost", 50L, engine.getMetrics(11_000L).costs);
    }

    @Test
    public void anExactTopUpStillBooksWhatWasFired()
    {
        Engine engine = fired(5);
        // Five fresh darts already in the pack (not the fired ones) top the pipe back up.
        engine.setBaseline(new ContainerSnapshot(Collections.singletonMap(DART, 5L)));
        assertTrue(engine.markChargeLoad(ChargeCheck.ChargedItem.BLOWPIPE, DART, "Iron dart", PIPE, 20));
        settle(engine, 0L, 6_000L);

        check(engine, 100L, 10_000L);
        assertEquals("5 fired, count unchanged after a 5-dart top-up", 50L, engine.getMetrics(11_000L).costs);
    }

    @Test
    public void dartsPickedUpStraightIntoThePipeAreNotLost()
    {
        // Twelve shots; eight of the fired darts are picked up and go straight back into the pipe,
        // so the pack never changes and the next Check reads only four fewer.
        Engine engine = fired(12);
        assertEquals("before a Check every shot is an estimate", 120L, engine.getMetrics(2_000L).costs);
        check(engine, 96L, 10_000L);
        assertEquals("the Check measures 4 darts lost", 40L, engine.getMetrics(11_000L).costs);
    }

    @Test
    public void dartsPickedUpIntoThePipeShrinkTheShotAndTheCheckAgrees()
    {
        Engine engine = fired(12);
        engine.pickUpIntoPipe(DART, 8L, 5_000L);
        assertEquals("8 of the 12 fired darts went back into the pipe", 40L,
            engine.getMetrics(6_000L).costs);
        check(engine, 96L, 10_000L);
        assertEquals("the Check agrees: 4 darts lost, never 0", 40L, engine.getMetrics(11_000L).costs);
    }

    @Test
    public void aPipePickupWithNoFiredDartsLeftChangesNothing()
    {
        Engine engine = fired(2);
        engine.pickUpIntoPipe(DART, 8L, 5_000L);
        assertEquals("only the 2 fired darts can come back", 0L, engine.getMetrics(6_000L).costs);
    }
}
