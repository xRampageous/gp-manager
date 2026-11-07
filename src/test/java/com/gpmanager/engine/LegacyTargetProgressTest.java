package com.gpmanager;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyTargetProgressTest {
    private static final long HOUR = 3_600_000L;
    private static final long UNIT = 4_000_000_000_000_000_000L;

    private static Engine negativeStart() {
        JsonCodec.bind(new com.google.gson.Gson());
        var engine = TargetFreshStartTest.engine();
        TargetFreshStartTest.coins(engine, 5, -100);
        engine.setTargets(100L, null, HOUR);
        return engine;
    }

    @Test public void anOlderTargetMustRetainItsNegativeStartingNet() {
        var engine = negativeStart();
        engine.session().sessionStartAt = null;
        TargetFreshStartTest.coins(engine, HOUR + 5, 50);
        var live = TargetFreshStartTest.live(engine, 2 * HOUR);
        assertEquals(-50, live.net);
        assertEquals("a 50-gp gain after a -100-gp start is 50 progress", 50, live.goal.current);
    }

    @Test public void theNegativeBaselineMustRemainUsableAfterReload() {
        var engine = negativeStart();
        engine.session().sessionStartAt = null;
        TargetFreshStartTest.coins(engine, HOUR + 5, 50);
        var saved = SaveCopies.copy(engine.savedState());
        var restored = TargetFreshStartTest.engine();
        restored.restore(saved, 2 * HOUR);
        var live = TargetFreshStartTest.live(restored, 2 * HOUR);
        assertEquals(-100, restored.session().profitTargetSetNet.longValue());
        assertEquals(-50, live.net);
        assertEquals(50, live.goal.current);
    }

    private static Engine overflowLoss(boolean oldWindow) {
        JsonCodec.bind(new com.google.gson.Gson());
        var engine = TargetFreshStartTest.engine();
        hugeReceipt(engine, 5, true);
        String first = engine.session().transactions().get(0).id();
        engine.setTargets(100L, null, HOUR);
        if (oldWindow) engine.session().sessionStartAt = null;
        assertTrue(engine.correctEntries(List.of(first), Correction.IGNORE));
        hugeReceipt(engine, HOUR + 5, false);
        hugeReceipt(engine, HOUR + 6, false);
        assertEquals(-2 * UNIT, engine.metrics(2 * HOUR).net);
        return engine;
    }

    private static void hugeReceipt(Engine engine, long at, boolean gain) {
        var flow = new Flow(1203, "Iron dagger", gain ? 1 : -1, UNIT, gain ? UNIT : -UNIT,
                PriceSource.MANUAL_OVERRIDE);
        engine.session().addTransaction(Tx.of(at, gain ? TransactionType.GAIN : TransactionType.CONSUMPTION,
                Context.GENERIC, "", true, List.of(flow)), 2000, false);
    }

    @Test public void aLargeLegacyTargetLossMustNotWrapIntoReachedProfit() {
        var engine = overflowLoss(true);
        var live = TargetFreshStartTest.live(engine, 2 * HOUR);
        assertEquals(Long.MIN_VALUE, live.goal.current);
        assertFalse(live.goal.reached);
    }

    @Test public void resavingAnUnreachedLegacyTargetMustNotRestartIt() {
        var engine = overflowLoss(true);
        engine.setTargets(100L, null, 2 * HOUR);
        assertNull("an unchanged unreached target keeps its existing window", engine.session().sessionStartAt);
        assertEquals(UNIT, engine.session().profitTargetSetNet.longValue());
    }

    @Test public void aCurrentTargetWindowKeepsItsOwnNegativeStartProgress() {
        var engine = negativeStart();
        TargetFreshStartTest.coins(engine, HOUR + 5, 50);
        var live = TargetFreshStartTest.live(engine, 2 * HOUR);
        assertEquals(50, live.net);
        assertEquals(50, live.goal.current);
    }

    @Test public void aCurrentWindowDoesNotWrapTheSameLargeLoss() {
        var engine = overflowLoss(false);
        var live = TargetFreshStartTest.live(engine, 2 * HOUR);
        assertEquals(-2 * UNIT, live.goal.current);
        assertFalse(live.goal.reached);
    }

    @Test public void positiveAndMissingLegacyBaselinesKeepTheirOrdinaryMeaning() {
        var session = new Session("Audit", 0, SessionMode.GENERAL);
        session.setNetTarget(100L, 100, 0);
        assertEquals(50, session.targetProgress(150));
        session.profitTargetSetNet = null;
        assertEquals(50, session.targetProgress(50));
    }
}
