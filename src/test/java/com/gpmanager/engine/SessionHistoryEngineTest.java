package com.gpmanager;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SessionHistoryEngineTest
{
    private static final GpManagerConfig CONFIG = new GpManagerConfig()
    {
        @Override
        public int maxHistorySessions()
        {
            return 100;
        }
    };

    @Test
    public void restoredActiveSessionWaitsPausedForRecovery()
    {
        Session interrupted = session("Interrupted", Context.LOOT, TransactionType.LOOT, 100L);
        Engine engine = engine();
        engine.restore(new SavedState(interrupted, null, Collections.emptyList()));

        assertNotNull(engine.getActiveSession());
        assertEquals(PauseReason.RECOVERY, engine.getActiveSession().getPauseReason());
    }

    @Test
    public void editsAndDeletesHistory()
    {
        Session pvm = session("Bossing", Context.LOOT, TransactionType.LOOT, 1_000L);
        pvm.close(3_600_000L);
        Session skilling = session("Fletching", Context.PRODUCTION, TransactionType.PROCESSING, 500L);
        skilling.close(3_600_000L);
        Session trading = session("Flipping", Context.MARKET, TransactionType.TRADE, -200L);
        trading.close(3_600_000L);

        Engine engine = engine();
        engine.restore(new SavedState(null, null, Arrays.asList(pvm, skilling, trading)), 3_600_000L);

        engine.historySession(pvm.getId()).rename("Vorkath");
        assertEquals("Vorkath", engine.historySession(pvm.getId()).getName());

        assertTrue(engine.deleteHistory(skilling.getId()));
        assertNull(engine.historySession(skilling.getId()));
        assertEquals(2, engine.getHistory().size());
    }

    @Test
    public void historicalCorrectionRecalculatesSummaryImmediately()
    {
        Session session = session("Review", Context.GENERIC, TransactionType.CONSUMPTION, -300L);
        session.close(60_000L);
        Transaction transaction = session.getTransactions().get(0);

        Engine engine = engine();
        engine.restore(new SavedState(null, null, Collections.singletonList(session)), 60_000L);
        Metrics before = engine.historyMetrics(session.getId(), 60_000L);
        assertEquals(-300L, before.net);

        assertTrue(engine.historySession(session.getId()).correctTransaction(
            transaction.getId(), Correction.REVENUE, 70_000L, "Manual correction"));
        Metrics after = engine.historyMetrics(session.getId(), 70_000L);
        assertEquals(300L, after.net);
    }

    @Test
    public void historySummaryCarriesStripMetadataWithoutLeakingSession()
    {
        Session session = session("Vorkath", Context.LOOT, TransactionType.LOOT, 1_000L);
        session.grind = true;
        session.close(60_000L);

        Engine engine = engine();
        engine.restore(new SavedState(null, null, Collections.singletonList(session)), 60_000L);

        Session retained = engine.historySession(session.getId());
        assertTrue(retained.grind);
        assertFalse(retained.getMode() == SessionMode.AUTO);
    }

    @Test
    public void historyIsNewestFirst()
    {
        long day = 24L * 60L * 60L * 1000L;
        long now = 100L * day;
        Session pvm = sessionAt("Vorkath", now - day, Context.LOOT, TransactionType.LOOT, 1_000L);
        Session skilling = sessionAt("Fletching", now - 10L * day, Context.PRODUCTION, TransactionType.PROCESSING, 500L);
        Session trading = sessionAt("Flipping", now - 40L * day, Context.MARKET, TransactionType.TRADE, -200L);

        Engine engine = engine();
        engine.restore(new SavedState(null, null, Arrays.asList(pvm, skilling, trading)), now);

        assertEquals("Vorkath", engine.getHistory().get(0).getName());
        assertEquals(3, engine.getHistory().size());

    }

    private static Engine engine()
    {
        return new Engine(
            deltas -> Collections.emptyList(),
            new TransactionClassifier(),
            CONFIG);
    }

    private static Session sessionAt(
        String name, long start, Context context, TransactionType type, long value)
    {
        Session session = new Session(name, start, SessionMode.GENERAL);
        long quantity = value < 0L ? -1L : 1L;
        session.addTransaction(Tx.of(
            start + 1_000L, 1_000L, type, context, name, name, true,
            Collections.singletonList(new Flow(1, "Item", quantity, (int) Math.abs(value), value))), 100);
        session.close(start + 3_600_000L);
        return session;
    }

    private static Session session(
        String name,
        Context context,
        TransactionType type,
        long value)
    {
        Session session = new Session(name, 0L, SessionMode.GENERAL);
        long quantity = value < 0L ? -1L : 1L;
        long absolute = Math.abs(value);
        session.addTransaction(
            Tx.of(
                1_000L,
                1_000L,
                type,
                context,
                name,
                name,
                true,
                Collections.singletonList(new Flow(1, "Item", quantity, (int) absolute, value))),
            100);
        return session;
    }
}
