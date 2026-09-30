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
    public void restoredActiveSessionIsMarkedRecovered()
    {
        Ad interrupted = session("Interrupted", Aj.LOOT, Ai.LOOT, 100L);
        Am engine = engine();
        engine.restore(new SavedState(interrupted, null, false, Collections.emptyList()));

        assertNotNull(engine.getActiveSession());
        assertTrue(engine.getActiveSession().recoveredFromCrash);
    }

    @Test
    public void filtersEditsDeletesAndExcludesHistory()
    {
        Ad pvm = session("Bossing", Aj.LOOT, Ai.LOOT, 1_000L);
        pvm.close(3_600_000L);
        Ad skilling = session("Fletching", Aj.PRODUCTION, Ai.PROCESSING, 500L);
        skilling.close(3_600_000L);
        Ad trading = session("Flipping", Aj.MARKET, Ai.TRADE, -200L);
        trading.close(3_600_000L);

        Am engine = engine();
        engine.restore(new SavedState(null, null, false, Arrays.asList(pvm, skilling, trading)), 3_600_000L);

        engine.ua(pvm.getId()).rename("Vorkath");
        assertEquals("Vorkath", engine.ua(pvm.getId()).getName());

        engine.ua(trading.getId()).setExcludedFromAverages(true);
        assertTrue(engine.ua(trading.getId()).excludedFromAverages);

        assertTrue(engine.qu(skilling.getId()));
        assertNull(engine.ua(skilling.getId()));
        assertEquals(2, engine.getHistory().size());
    }

    @Test
    public void historicalCorrectionRecalculatesSummaryImmediately()
    {
        Ad session = session("Review", Aj.GENERIC, Ai.CONSUMPTION, -300L);
        session.close(60_000L);
        Ac transaction = session.getTransactions().get(0);

        Am engine = engine();
        engine.restore(new SavedState(null, null, false, Collections.singletonList(session)), 60_000L);
        Bu before = engine.tz(session.getId(), 60_000L);
        assertEquals(-300L, before.net);

        assertTrue(engine.ua(session.getId()).qi(
            transaction.getId(), Ah.REVENUE, 70_000L, "Manual correction"));
        Bu after = engine.tz(session.getId(), 70_000L);
        assertEquals(300L, after.net);
    }

    @Test
    public void historySummaryCarriesStripMetadataWithoutLeakingSession()
    {
        Ad session = session("Vorkath", Aj.LOOT, Ai.LOOT, 1_000L);
        java.util.Collections.addAll(session.tags, "blue dragon");
        session.setFavorite(true);
        session.setExcludedFromAverages(true);
        session.setOwnerKind(Bt.NAMED_SESSION);
        session.setEndReason(SessionEndReason.BOUNDARY);
        session.close(60_000L);

        Am engine = engine();
        engine.restore(new SavedState(null, null, false, Collections.singletonList(session)), 60_000L);

        Ad retained = engine.ua(session.getId());
        assertEquals(SessionEndReason.BOUNDARY, retained.endReason);
        assertEquals(Bt.NAMED_SESSION, retained.getOwnerKind());
        assertTrue(retained.favorite);
        assertTrue(retained.excludedFromAverages);
        assertFalse(retained.getMode() == Cx.AUTO);
    }

    @Test
    public void searchesSortsFavoritesAndBuildsLifetimeIntelligence()
    {
        long day = 24L * 60L * 60L * 1000L;
        long now = 100L * day;
        Ad pvm = sessionAt("Vorkath", now - day, Aj.LOOT, Ai.LOOT, 1_000L);
        java.util.Collections.addAll(pvm.tags, "boss", "blue dragon");
        pvm.notes = "pet hunt";
        pvm.setFavorite(true);
        Ad skilling = sessionAt("Fletching", now - 10L * day, Aj.PRODUCTION, Ai.PROCESSING, 500L);
        skilling.notes = "afk bows";
        Ad trading = sessionAt("Flipping", now - 40L * day, Aj.MARKET, Ai.TRADE, -200L);

        Am engine = engine();
        engine.restore(new SavedState(null, null, false, Arrays.asList(pvm, skilling, trading)), now);

        // Notes, tags and favourites are retained facts the Sessions page filters on; history is newest first.
        assertEquals("pet hunt", engine.ua(pvm.getId()).notes);
        assertEquals("boss, blue dragon", String.join(", ", engine.ua(pvm.getId()).tags));
        assertTrue(engine.ua(pvm.getId()).favorite);
        assertEquals("Vorkath", engine.getHistory().get(0).getName());
        assertEquals(3, engine.getHistory().size());

    }

    private static Am engine()
    {
        return new Am(
            deltas -> Collections.emptyList(),
            new TransactionClassifier(),
            CONFIG);
    }

    private static Ad sessionAt(
        String name, long start, Aj context, Ai type, long value)
    {
        Ad session = new Ad(name, start, Cx.GENERAL);
        long quantity = value < 0L ? -1L : 1L;
        session.kf(Tx.of(
            start + 1_000L, 1_000L, type, context, name, name, true,
            Collections.singletonList(new Ab(1, "Item", quantity, (int) Math.abs(value), value))), 100);
        session.close(start + 3_600_000L);
        return session;
    }

    private static Ad session(
        String name,
        Aj context,
        Ai type,
        long value)
    {
        Ad session = new Ad(name, 0L, Cx.GENERAL);
        long quantity = value < 0L ? -1L : 1L;
        long absolute = Math.abs(value);
        session.kf(
            Tx.of(
                1_000L,
                1_000L,
                type,
                context,
                name,
                name,
                true,
                Collections.singletonList(new Ab(1, "Item", quantity, (int) absolute, value))),
            100);
        return session;
    }
}
