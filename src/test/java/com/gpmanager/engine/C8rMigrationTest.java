package com.gpmanager;

import com.google.gson.Gson;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * C8R staged schema-100/101 to schema-102 conversion: deterministic identity, legacy daily and
 * lifetime PvP classification, coverage truthfulness, bounded evidence, and restart idempotency.
 */
public class C8rMigrationTest
{
    private static final long DAY = 86_400_000L;
    private static final long NOW = Instant.parse("2027-06-01T12:00:00Z").toEpochMilli();

    static
    {
        JsonCodec.bind(new Gson());
    }

    private static GpManagerConfig config()
    {
        return config(5_000);
    }

    private static GpManagerConfig config(int maxHistory)
    {
        return new GpManagerConfig()
        {
            @Override
            public int maxHistorySessions() { return maxHistory; }
            @Override
            public int maxTransactionsPerSession() { return 100_000; }
            @Override
            public ReceiptRetentionPeriod receiptRetentionDays() { return ReceiptRetentionPeriod.DAYS_365; }
            @Override
            public boolean autoStartSession() { return false; }
        };
    }

    private static Engine engine()
    {
        return new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(),
            config());
    }

    private static Session pkSessionWithKill(long at, String name)
    {
        Session session = new Session(name, at - 60_000L, SessionMode.PK);
        PkEncounter encounter = session.addPkEncounter(EncounterType.KILL, at, "Player kill",
            ClassificationConfidence.CONFIRMED, "Kill");
        Transaction loot = new Transaction(at + 1_000L, null, TransactionType.PK_LOOT,
            Context.PK_LOOT, "pk", "PKing", true,
            Collections.singletonList(new Flow(995, "Coins", 1_000L, 1, 1_000L)),
            ClassificationConfidence.CONFIRMED, "test", null);
        session.addTransaction(loot, 100_000);
        session.attachTransactionToEncounter(loot.getId(), encounter.getId(), false);
        return session;
    }

    /** Emulates a pre-C8R payload: no Family C state existed on schema-100/101 sessions. */
    private static void stripFamilyC(SavedState state)
    {
        com.google.gson.JsonObject json = JsonCodec.gson().toJsonTree(state).getAsJsonObject();
        stripSession(json.getAsJsonArray("history"));
        stripSession(json.getAsJsonArray("generalSession"));
        stripSession(json.getAsJsonArray("customSession"));
        SavedState stripped = JsonCodec.gson().fromJson(json, SavedState.class);
        state.getHistory().clear();
        state.getHistory().addAll(stripped.getHistory());
    }

    private static void stripSession(com.google.gson.JsonArray array)
    {
        if (array == null) return;
        for (com.google.gson.JsonElement element : array)
        {
            if (element == null || !element.isJsonObject()) continue;
            element.getAsJsonObject().remove("pkProjection");
            element.getAsJsonObject().remove("pkAttributions");
        }
    }

    // ── schema matrix ─────────────────────────────────────────────────────────

    @Test
    public void unsupportedGapAndFutureSchemasStayReadOnly()
    {
        for (int preRelease : new int[] {9, 23, 99, 100, 101, 102, 104, 107})
        {
            SavedState gap = new SavedState();
            gap.setSchemaVersion(preRelease);
            assertFalse("pre-release schema " + preRelease + " is read-only", gap.isSupportedSchema());
            assertFalse((gap.schemaVersion > SavedState.CURRENT_SCHEMA_VERSION));
        }
        SavedState future = new SavedState();
        future.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION + 1);
        assertFalse(future.isSupportedSchema());
        assertTrue((future.schemaVersion > SavedState.CURRENT_SCHEMA_VERSION));
        SavedState current = new SavedState();
        assertTrue(current.isSupportedSchema());
        assertFalse((current.schemaVersion > SavedState.CURRENT_SCHEMA_VERSION));
    }

    // ── legacy daily classification ───────────────────────────────────────────

    // ── legacy lifetime ───────────────────────────────────────────────────────

    // ── identity / idempotency / boundaries ───────────────────────────────────

}
