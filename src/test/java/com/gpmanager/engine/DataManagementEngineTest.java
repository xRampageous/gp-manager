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

    /** Owner 1.5.1: a neutral GE change row is only kept as an audit row, like a live custody move. */
    @Test public void neutralChangeRowsLeaveWhenAuditRowsAreOff()
    {
        Engine engine = ReviewFixesTest.engine();
        Transaction change = new Transaction(5L, TransactionType.TRANSFER, Context.MARKET,
            GameData.msg("grand-exchange-custody"), "Market", false,
            java.util.List.of(new Flow(995, "Coins", 430L, 1, 430L, PriceSource.FACE_VALUE)),
            Confidence.CONFIRMED, GameData.msg("ownership-neutral-grand"), null);
        engine.getActiveSession().addTransaction(change, 2_000, false);
        engine.recheck(id -> 0);
        assertTrue(engine.getActiveSession().getTransactions().isEmpty());
    }

    /** Owner 1.5.1: an old "?" item that now has a price (half a pie's share) takes it on login. */
    @Test public void oldUnpricedRowsTakeTheirPriceOnLogin()
    {
        Engine engine = ReviewFixesTest.engine();
        Transaction ate = new Transaction(5L, TransactionType.CONSUMPTION, Context.GENERIC, "", "General", true,
            new java.util.ArrayList<>(java.util.List.of(new Flow(7220, "Half a summer pie", -1L, 0, 0L, PriceSource.UNPRICED))),
            Confidence.LIKELY, "", null);
        engine.getActiveSession().addTransaction(ate, 2_000, false);
        engine.recheck(id -> 0);
        Flow pie = engine.getActiveSession().getTransactions().get(0).getFlows().get(0);
        assertNotSame(PriceSource.UNPRICED, pie.getPriceSource());
        assertEquals(-100L, pie.valueDelta);
    }

    /** Owner 1.5.1: the ACTIVE clock restarts GP/h and the clock; Net, Recent and the Ledger stay. */
    @Test public void restartingTheRateKeepsTheRun()
    {
        Engine engine = engine();
        engine.ensureSession(0L);
        Session run = engine.getGeneralSession();
        run.addTransaction(new Transaction(5L, TransactionType.GAIN, Context.GENERIC, "", "General", true,
            java.util.List.of(new Flow(995, "Coins", 1_000L, 1, 1_000L, PriceSource.FACE_VALUE)),
            Confidence.LIKELY, "", null), 2_000, false);
        long hour = 3_600_000L;
        engine.newLiveSession(hour);
        run.addTransaction(new Transaction(hour + 5L, TransactionType.GAIN, Context.GENERIC, "", "General", true,
            java.util.List.of(new Flow(995, "Coins", 500L, 1, 500L, PriceSource.FACE_VALUE)),
            Confidence.LIKELY, "", null), 2_000, false);

        LiveSnapshot live = LiveSnapshot.capture(engine, 2 * hour, null);
        assertEquals("Net counts the new session", 500L, live.net);
        assertEquals("the run keeps its whole total", 1_500L, live.runNet);
        assertEquals("the clock counts from the restart", hour, live.elapsedMillis);
        assertEquals("GP/h is what came in since", 500L, live.gpPerHour);
        assertSame("the same run, nothing moved to history", run, engine.getActiveSession());
        assertTrue(engine.getHistory().isEmpty());
        assertEquals(2, run.getTransactions().size());
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
