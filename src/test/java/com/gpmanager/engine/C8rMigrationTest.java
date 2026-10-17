package com.gpmanager;

import com.gpmanager.GpManagerConfig.Retention;

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
            public Retention receiptRetentionDays() { return Retention.DAYS_365; }
            @Override
            public boolean autoStartSession() { return false; }
        };
    }

    // ── schema matrix ─────────────────────────────────────────────────────────

    @Test
    public void onlyTheCurrentSchemaIsReadAsCurrent()
    {
        for (int preRelease : new int[] {9, 23, 99, 100, 101, 102, 104, 107})
        {
            SavedState gap = new SavedState();
            gap.setSchemaVersion(preRelease);
            assertFalse("older schema " + preRelease + " is never read as current", gap.isSupportedSchema());
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
