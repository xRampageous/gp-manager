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
        engine.freePlay().setNetTarget(100L, 0, 0);
        engine.startCustom("Custom", 2L);
        engine.finishCustom(3L);
        assertTrue(engine.deleteHistory(engine.history().get(0).id()));
        assertEquals(0, engine.history().size());
        assertEquals(Long.valueOf(100L), engine.freePlay().targetGp());
    }

    @Test public void resetTrackingClearsOwnersHistoryAndTargetsLikeANewInstall()
    {
        Engine engine = engine();
        engine.ensureSession(1L);
        engine.freePlay().setNetTarget(100L, 0, 0);
        engine.startCustom("Custom", 2L);
        engine.resetData(3L);
        assertEquals(0, engine.history().size());
        assertFalse(engine.customActive());
        // A reset is a new install: no owner (and so no target) until play or Start.
        assertNull(engine.session());
        assertNull(engine.freePlay());
    }

    /** Owner 1.5.1: a neutral GE change row is only kept as an audit row, like a live custody move. */
    @Test public void neutralChangeRowsLeaveWhenAuditRowsAreOff()
    {
        Engine engine = ReviewFixesTest.engine();
        Transaction change = new Transaction(5L, TransactionType.TRANSFER, Context.MARKET,
            GameData.msg("ge-custody"), "Market", false,
            java.util.List.of(new Flow(995, "Coins", 430L, 1, 430L, PriceSource.FACE_VALUE)),
            Confidence.CONFIRMED, GameData.msg("ge-neutral"), null);
        engine.session().addTransaction(change, 2_000, false);
        engine.recheck();
        assertTrue(engine.session().transactions().isEmpty());
    }

    /** Owner 1.5.1: an old "?" item that now has a price (half a pie's share) takes it on login. */
    @Test public void oldUnpricedRowsTakeTheirPriceOnLogin()
    {
        Engine engine = ReviewFixesTest.engine();
        Transaction ate = new Transaction(5L, TransactionType.CONSUMPTION, Context.GENERIC, "", "General", true,
            new java.util.ArrayList<>(java.util.List.of(new Flow(7220, "Half a summer pie", -1L, 0, 0L, PriceSource.UNPRICED))),
            Confidence.LIKELY, "", null);
        engine.session().addTransaction(ate, 2_000, false);
        engine.recheck();
        Flow pie = engine.session().transactions().get(0).flows().get(0);
        assertNotSame(PriceSource.UNPRICED, pie.source());
        assertEquals(-100L, pie.value);
    }

    @Test public void repricingRefreshesClosedFactsWithoutChangingReviewDecisions()
    {
        for (var type : new TransactionType[]{TransactionType.GAIN, TransactionType.UNCERTAIN}) {
            var engine = ReviewFixesTest.engine();
            long start = ReviewFixesTest.T0;
            engine.startCustom("Closed run", start);
            boolean counted = type == TransactionType.GAIN;
            var row = new Transaction(start + 1000, type, Context.GENERIC, "", "Closed run", counted,
                    java.util.List.of(new Flow(1234, "Observed item", 1, 0, 0, PriceSource.UNPRICED)),
                    counted ? Confidence.LIKELY : Confidence.UNCERTAIN, "Observed acquisition", null);
            engine.session().addTransaction(row, 2000, false);
            engine.finishCustom(start + 2000);
            var closed = engine.history().get(0);
            assertEquals(0, MoneySnapshot.of(engine).metrics(closed, start + 5000).net);

            engine.recheck();

            var refreshed = MoneySnapshot.of(engine);
            assertEquals(counted ? 100 : 0, closed.metrics(start + 5000).net);
            assertEquals("the cached money follows the receipt", closed.metrics(start + 5000).net,
                    refreshed.metrics(closed, start + 5000).net);
            var copy = refreshed.fact(closed).transactions.get(0);
            assertEquals("the cached receipt follows the price", 100, copy.flows().get(0).value);
            assertEquals(row.id(), copy.id());
            assertEquals(type, copy.type());
            assertEquals(counted, copy.counts());
            assertEquals(!counted, ReviewRules.needsReview(copy));
            long revision = engine.revision();
            engine.recheck();
            assertEquals("an unchanged retry is a no-op", revision, engine.revision());
            assertSame(refreshed, MoneySnapshot.of(engine));
        }
    }

    /** Owner 1.5.1: the ACTIVE clock restarts GP/h and the clock; Net, Recent and the Ledger stay. */
    @Test public void restartingTheRateKeepsTheRun()
    {
        Engine engine = engine();
        engine.ensureSession(0L);
        Session run = engine.freePlay();
        run.addTransaction(new Transaction(5L, TransactionType.GAIN, Context.GENERIC, "", "General", true,
            java.util.List.of(new Flow(995, "Coins", 1_000L, 1, 1_000L, PriceSource.FACE_VALUE)),
            Confidence.LIKELY, "", null), 2_000, false);
        long hour = 3_600_000L;
        engine.newLive(hour);
        run.addTransaction(new Transaction(hour + 5L, TransactionType.GAIN, Context.GENERIC, "", "General", true,
            java.util.List.of(new Flow(995, "Coins", 500L, 1, 500L, PriceSource.FACE_VALUE)),
            Confidence.LIKELY, "", null), 2_000, false);

        LiveSnapshot live = LiveSnapshot.capture(engine, 2 * hour, null);
        assertEquals("Net counts the new session", 500L, live.net);
        assertEquals("the run keeps its whole total", 1_500L, live.run);
        assertEquals("the clock counts from the restart", hour, live.elapsed);
        assertEquals("GP/h is what came in since", 500L, live.rate);
        assertSame("the same run, nothing moved to history", run, engine.session());
        assertTrue(engine.history().isEmpty());
        assertEquals(2, run.transactions().size());
    }

    /** Owner 2026-10-16 (after Coin Flow 1.9): only the Blast Furnace coffer's drain is a cost. */
    @Test public void blastFurnaceCofferChargesOnlyItsDrain()
    {
        assertEquals("a tick's drain", 12L, Engine.cofferDrain(10_000, 9_988, 1));
        assertEquals("a withdrawal is never drain", 0L, Engine.cofferDrain(10_000, 5_000, 1));
        assertEquals("nor after standing idle", 0L, Engine.cofferDrain(30_000, 10_000, 1_000));
        assertEquals("a deposit is never a cost", 0L, Engine.cofferDrain(5_000, 10_000, 1));
        assertEquals("the first reading only sets the balance", 0L, Engine.cofferDrain(-1, 9_000, 1));
        assertEquals("ten quiet ticks", 120L, Engine.cofferDrain(10_000, 9_880, 10));

        Engine engine = engine();
        engine.ensureSession(1L);
        Transaction drain = engine.bookCofferDrain(1_200L, 2L);
        assertNotNull(drain);
        assertEquals(-1_200L, drain.autoNet());
        assertTrue(drain.counts());
        assertEquals("Blast Furnace coffer", drain.note());
    }

    /** Owner 2026-10-16 (after Coin Flow 1.9): hiding one dose hides the whole potion. */
    @Test public void hidingOneDoseHidesEveryDose()
    {
        String hidden = ActionReader.doseBase("Prayer potion(3)") + "*";
        for (String dose : new String[] {"Prayer potion(4)", "Prayer potion(1)"})
            assertEquals(dose, LootPresentationFilterService.HIDDEN,
                LootPresentationFilterService.listState("", hidden, dose, 1L));
        assertNotEquals(LootPresentationFilterService.HIDDEN,
            LootPresentationFilterService.listState("", hidden, "Super restore(4)", 1L));
    }
}
