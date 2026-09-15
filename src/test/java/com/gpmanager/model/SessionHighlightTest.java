package com.gpmanager.model;

import com.google.gson.Gson;
import com.gpmanager.persistence.SavedState;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SessionHighlightTest
{
    @Test
    public void observedKillsOutrankGatheredItemsAndReceipts()
    {
        ProfitSession session = new ProfitSession("Hunt", 1_000L, SessionMode.AUTO);
        session.recordObservedEncounter("Goblin", 3L, 120L, true, 3L, 2_000L);
        session.addTransaction(gain(1511, "Logs", 25L), 100);
        session.addTransaction(gain(526, "Bones", 1L), 100);

        SessionHighlight highlight = session.getSessionHighlight();
        assertEquals(SessionHighlight.Kind.KILLS, highlight.getKind());
        assertEquals(3L, highlight.getCount());
        assertEquals("Goblin", highlight.getSourceName());
    }

    @Test
    public void gatheredAndDropRulesUseCountedGains()
    {
        ProfitSession gathered = new ProfitSession("Woodcutting", 1_000L, SessionMode.AUTO);
        gathered.addTransaction(gain(1511, "Logs", 25L), 100);
        assertEquals(SessionHighlight.Kind.GATHERED, gathered.getSessionHighlight().getKind());
        assertEquals(25L, gathered.getSessionHighlight().getQuantity());

        ProfitSession drops = new ProfitSession("Slayer", 1_000L, SessionMode.AUTO);
        drops.addTransaction(gain(526, "Bones", 1L), 100);
        assertEquals(SessionHighlight.Kind.DROPS, drops.getSessionHighlight().getKind());
        assertEquals(1L, drops.getSessionHighlight().getCount());
    }

    @Test
    public void pkWinsOverPvmAndLegacyEncounterUnknownDoesNotFallThrough()
    {
        ProfitSession pk = new ProfitSession("PK", 1_000L, SessionMode.PK);
        pk.recordObservedEncounter("Goblin", 50L, 500L, true, 3L, 2_000L);
        pk.addPkEncounter(PkEncounterType.KILL, 2_000L,
            "Target", ClassificationConfidence.CONFIRMED, "Observed player loot");
        assertEquals(1, pk.pkMetrics().getKills());
        assertEquals(SessionHighlight.Kind.PK_KILLS, pk.getSessionHighlight().getKind());
        assertEquals(1L, pk.getSessionHighlight().getCount());

        ProfitSession legacy = new ProfitSession("Legacy", 1_000L, SessionMode.AUTO);
        legacy.addTransaction(gain(1511, "Logs", 40L), 100);
        legacy.markLegacyEncounterHistoryUnavailable();
        assertFalse(legacy.getSessionHighlight().isAvailable());
        assertEquals(SessionHighlight.Kind.UNAVAILABLE, legacy.getSessionHighlight().getKind());
        assertEquals("ENCOUNTER_HISTORY_UNAVAILABLE",
            legacy.getSessionHighlight().getUnavailableReason());
    }

    @Test
    public void countedGainedReceiptCountSurvivesCompactionWithoutDoubleCounting()
    {
        ProfitSession session = new ProfitSession("Drops", 1_000L, SessionMode.AUTO);
        session.addTransaction(gain(526, "Bones", 1L), 1);
        session.addTransaction(gain(532, "Big bones", 1L), 1);
        assertEquals(2L, session.getCountedGainedReceiptCount());
        assertEquals(SessionHighlight.Kind.DROPS, session.getSessionHighlight().getKind());
        assertEquals(2L, session.getSessionHighlight().getCount());
    }

    @Test
    public void encounterHighlightAndReceiptCountSurviveSessionEndJsonRestoreAndCompaction()
    {
        ProfitSession session = new ProfitSession("Goblin task", 1_000L, SessionMode.AUTO);
        session.recordObservedEncounter("Goblin", 4L, 240L, true, 4L, 2_000L);
        session.addTransaction(gain(526, "Bones", 1L), 1);
        session.addTransaction(gain(532, "Big bones", 1L), 1);
        session.close(3_000L);

        SavedState saved = new SavedState(session, Collections.emptyList());
        saved.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        SavedState restoredState = new Gson().fromJson(new Gson().toJson(saved), SavedState.class);
        ProfitSession restored = restoredState.getActiveSession();

        assertEquals(4L, restored.getEncounterTotals().getTotalEncounterCount());
        assertEquals(2L, restored.getCountedGainedReceiptCount());
        SessionSummary summary = new SessionSummary(restored, 4_000L, 60_000L);
        assertEquals(SessionHighlight.Kind.KILLS, summary.getHighlight().getKind());
        assertEquals(4L, summary.getHighlight().getCount());
        assertEquals("Goblin", summary.getHighlight().getSourceName());
        assertEquals(4L, summary.getHighlight().getKills());
        assertEquals("Goblin", summary.getHighlight().getTopSourceName());
        assertEquals(1L, summary.getHighlight().getTopItemQuantity());
        assertEquals(2L, summary.getHighlight().getDrops());
        assertTrue(summary.getHighlight().areKillsAvailable());
        assertTrue(summary.getHighlight().isTopItemAvailable());
        assertTrue(summary.getHighlight().areDropsAvailable());
    }

    private static ProfitTransaction gain(int itemId, String name, long quantity)
    {
        return new ProfitTransaction(2_000L, null, TransactionType.GAIN,
            TrackingContext.LOOT, "Observed gain", "Slayer", true,
            Collections.singletonList(new ItemFlow(itemId, name, quantity, 1, quantity)));
    }
}
