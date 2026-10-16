package com.gpmanager;

import org.junit.Test;
import static org.junit.Assert.*;

public class DataManagementEngineTest
{
    private Engine engine()
    {
        return new Engine(deltas -> java.util.Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
    }

    @Test public void clearHistoryPreservesOwnersAndTargets()
    {
        Engine engine = engine();
        engine.ensureSession(1L);
        engine.getGeneralSession().setProfitTargetGp(100L);
        engine.startCustom("Custom", 2L);
        engine.finishCustom(3L);
        assertTrue(engine.deleteHistory(engine.getHistory().get(0).getId()));
        assertEquals(0, engine.getHistory().size());
        assertEquals(Long.valueOf(100L), engine.getGeneralSession().getProfitTargetGp());
    }

    @Test public void resetTrackingClearsOwnersHistoryAndTargetsLikeANewInstall()
    {
        Engine engine = engine();
        engine.ensureSession(1L);
        engine.getGeneralSession().setProfitTargetGp(100L);
        engine.startCustom("Custom", 2L);
        engine.resetData(3L);
        assertEquals(0, engine.getHistory().size());
        assertFalse(engine.isCustomActive());
        // A reset is a new install: no owner (and so no target) until play or Start.
        assertNull(engine.getActiveSession());
        assertNull(engine.getGeneralSession());
    }

    /** Owner 2026-10-16: the ACTIVE clock's soft reset keeps the run, never deletes it. */
    @Test public void startFreshMovesFreePlayToHistoryAndStartsAtZero()
    {
        Engine engine = engine();
        engine.ensureSession(1L);
        Session before = engine.getGeneralSession();
        before.addTransaction(new Transaction(5L, TransactionType.GAIN, Context.GENERIC, "", "General", true,
            java.util.List.of(new Flow(995, "Coins", 1_000L, 1, 1_000L, PriceSource.FACE_VALUE)),
            Confidence.LIKELY, "", null), 2_000);
        before.pause(8L, PauseReason.MANUAL);

        assertTrue(engine.startFreshFreePlay(10L));
        Session kept = engine.getHistory().get(0);
        assertSame("the run is kept, not deleted", before, kept);
        assertEquals("Free play", kept.getName());
        assertTrue(kept.isClosed());
        assertEquals(1, kept.getTransactions().size());
        Session fresh = engine.getGeneralSession();
        assertNotSame(before, fresh);
        assertSame(fresh, engine.getActiveSession());
        assertTrue("Live starts at zero", fresh.getTransactions().isEmpty());
        assertSame("a manual pause holds", PauseReason.MANUAL, fresh.getPauseReason());

        engine.startCustom("Vorkath", 20L);
        assertFalse("a running Grind ends through End Grind instead", engine.startFreshFreePlay(30L));
    }

    /** Owner 2026-10-16 (after Coin Flow 1.9): only the Blast Furnace coffer's drain is a cost. */
    @Test public void blastFurnaceCofferChargesOnlyItsDrain()
    {
        assertEquals("a tick's drain", 12L, GpManagerPlugin.cofferDrain(10_000, 9_988, 1));
        assertEquals("a withdrawal is never drain", 0L, GpManagerPlugin.cofferDrain(10_000, 5_000, 1));
        assertEquals("nor after standing idle", 0L, GpManagerPlugin.cofferDrain(30_000, 10_000, 1_000));
        assertEquals("a deposit is never a cost", 0L, GpManagerPlugin.cofferDrain(5_000, 10_000, 1));
        assertEquals("the first reading only sets the balance", 0L, GpManagerPlugin.cofferDrain(-1, 9_000, 1));
        assertEquals("ten quiet ticks", 120L, GpManagerPlugin.cofferDrain(10_000, 9_880, 10));

        Engine engine = engine();
        engine.ensureSession(1L);
        Transaction drain = engine.bookCofferDrain(1_200L, 2L);
        assertNotNull(drain);
        assertEquals(-1_200L, drain.autoNet());
        assertTrue(drain.isCounted());
        assertEquals("Blast Furnace coffer", drain.getNote());
    }

    /** Owner 2026-10-16 (after Coin Flow 1.9): hiding one dose hides the whole potion. */
    @Test public void hidingOneDoseHidesEveryDose()
    {
        String hidden = LootPresentationFilterService.chargePattern("Prayer potion(3)");
        for (String dose : new String[] {"Prayer potion(4)", "Prayer potion(1)"})
            assertEquals(dose, LootPresentationFilterService.HIDDEN,
                LootPresentationFilterService.listState("", hidden, dose, 1L));
        assertNotEquals(LootPresentationFilterService.HIDDEN,
            LootPresentationFilterService.listState("", hidden, "Super restore(4)", 1L));
    }
}
