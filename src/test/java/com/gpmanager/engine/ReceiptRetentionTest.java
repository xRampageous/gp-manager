package com.gpmanager;

import com.google.gson.Gson;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Receipt retention compacts individually inspectable detail while preserving exact totals
 * and correction-aware metrics.
 */
public class ReceiptRetentionTest
{
    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    private static final long DAY = 86_400_000L;

    private static final GpManagerConfig CONFIG = new GpManagerConfig()
    {
        @Override
        public int maxHistorySessions()
        {
            return 2_000;
        }

        @Override
        public Db receiptRetentionDays()
        {
            return Db.DAYS_90;
        }
    };

    @Test
    public void compactsRowsStrictlyOlderThanTheCutoffByTransactionTimeAcrossOwners()
    {
        // Section L: the cutoff is transaction time, whether the owner is closed or still active.
        long now = 100L * DAY + 12L * 60L * 60L * 1_000L;
        long cutoff = now - 30L * DAY;
        Ad boundary = new Ad("Boundary", cutoff - 10_000L);
        boundary.kf(receipt(cutoff, 100L), 10);
        boundary.close(cutoff + 1_000L);
        Ad old = new Ad("Old", cutoff - 10_000L);
        old.kf(receipt(cutoff - 1L, 200L), 10);
        old.close(cutoff + 2_000L);
        Ad active = new Ad("Active", cutoff - 2L * DAY);
        active.kf(receipt(cutoff - DAY, 300L), 10);
        active.kf(receipt(cutoff + DAY, 50L), 10);

        Am engine = engine();
        engine.restore(new SavedState(null, null, false, Arrays.asList(boundary, old, old, active)), now);

        assertEquals(2, engine.pg(30, now));
        assertEquals("a row exactly at the cutoff is retained", 1, boundary.getTransactions().size());
        assertTrue(old.getTransactions().isEmpty());
        assertEquals(1L, old.compactedTransactionCount);
        assertEquals(200L, old.metrics(now).revenue);
        assertEquals("only the active owner's overdue row folds", 1, active.getTransactions().size());
        assertEquals(350L, active.metrics(now).revenue);
        assertEquals(0, engine.pg(30, now));
        assertEquals(0, engine.pg(0, now));
    }

    @Test
    public void derivedPendingClaimIdKeepsItsOriginAuditRowRetained()
    {
        long now = 400L * DAY;
        Ad session = new Ad("Loot origin", now - 200L * DAY);
        Ac audit = receipt(now - 200L * DAY + 1_000L, 0L);
        session.kf(audit, 10);
        session.close(now - 200L * DAY + 2_000L);

        SavedState state = new SavedState(null, null, false, Collections.singletonList(session));
        state.setPendingClaims(Collections.singletonList(new SavedState.By(
            audit.getId() + "#2", net.runelite.api.gameval.ItemID.WILDY_LOOT_KEY1, 1L,
            audit.timestampEpochMillis)));

        Am engine = engine();
        engine.restore(state, now);

        assertEquals("a multi-key claim protects the shared origin audit row", 1,
            session.getTransactions().size());
        assertTrue(engine.getPendingClaims().get(0).getClaimId().endsWith("#2"));
        assertEquals(0, engine.pg(90, now));
    }

    @Test
    public void compactionKeepsMetadataEncountersAndExactTotals()
    {
        long now = 400L * DAY;
        Ad session = new Ad("Favourite trip", now - 200L * DAY, Cx.PK);
        java.util.Collections.addAll(session.tags, "boss", "pet");
        session.notes = "keep this note";
        session.setFavorite(true);
        session.setExcludedFromAverages(true);

        session.kf(receipt(now - 200L * DAY + 1_000L, 100L), 10);
        session.kf(receipt(now - 200L * DAY + 61_000L, 250L), 10);
        session.kf(receipt(now - 200L * DAY + 62_500L, -40L), 10);
        Bx encounter = session.ke(Be.KILL, now - 200L * DAY + 62_000L,
            "Player kill", null, "observed encounter");
        session.ll(session.getTransactions().get(1).getId(), encounter.getId(), false);
        session.close(now - 200L * DAY + 120_000L);

        Bu before = session.metrics(now);
        Dt pkBefore = session.ava();
        Am engine = engine();
        engine.restore(new SavedState(null, null, false, Collections.singletonList(session)), now);

        assertEquals("current-schema restore already compacted the overdue rows", 0,
            engine.pg(90, now));

        assertTrue(session.getTransactions().isEmpty());
        assertEquals(3L, session.compactedTransactionCount);
        assertEquals("boss, pet", String.join(", ", session.tags));
        assertEquals("keep this note", session.notes);
        assertTrue(session.favorite);
        assertTrue(session.excludedFromAverages);
        assertEquals("detail beyond the receipt horizon is compacted", 0, session.getPkEncounters().size());
        assertEquals(Di.RETAINED_WINDOW,
            session.pkProjection.ud());
        assertEquals("compacted detail stays represented by exact counts",
            1, session.pkProjection.getKills());

        Bu after = session.metrics(now);
        assertEquals(before.revenue, after.revenue);
        assertEquals(before.costs, after.costs);
        assertEquals(before.net, after.net);
        assertEquals(350L, after.revenue);
        assertEquals(40L, after.costs);
        Dt pkAfter = session.ava();
        assertEquals(pkBefore.kills, pkAfter.kills);
        assertEquals(pkBefore.net, pkAfter.net);
        assertEquals(pkBefore.bestKill, pkAfter.bestKill);
    }

    @Test
    public void currentSchemaRestoreCompactsOverdueRowsWithoutWaitingForUtcRollover()
    {
        long now = 120L * DAY + 12L * 60L * 60L * 1_000L;
        Ad overdue = closedWithReceipt("Overdue", now - 120L * DAY - 1L, 750L);
        SavedState current = new SavedState(null, null, false, Collections.singletonList(overdue));
        current.setLastReceiptRetentionDayUtc(LocalDate.ofEpochDay(120L).toString());

        Am engine = engine();
        engine.restore(current, now);

        assertTrue(overdue.getTransactions().isEmpty());
        assertEquals(1L, overdue.compactedTransactionCount);
        assertEquals(750L, overdue.metrics(now).revenue);
        assertEquals(LocalDate.ofEpochDay(120L).toString(),
            engine.qm().getLastReceiptRetentionDayUtc());
    }

    @Test
    public void shippingRetentionWindowsAreBoundedAndUnknownReadsAs90Days()
    {
        assertEquals(Arrays.asList(30, 90, 180, 365), Arrays.asList(
            Db.DAYS_30.getDays(), Db.DAYS_90.getDays(),
            Db.DAYS_180.getDays(), Db.DAYS_365.getDays()));
        assertEquals(4, Db.values().length);
        for (Db period : Db.values())
        {
            assertFalse("no shipping window means retain forever", period.getDays() <= 0);
        }
    }

    private static Ad closedWithReceipt(String name, long endedAt, long value)
    {
        long start = Math.max(0L, endedAt - 10_000L);
        Ad session = new Ad(name, start);
        session.kf(receipt(start + 1_000L, value), 10);
        session.close(endedAt);
        return session;
    }

    private static Ac receipt(long timestamp, long value)
    {
        return Tx.of(timestamp, value < 0L ? Ai.CONSUMPTION : Ai.LOOT,
            Aj.LOOT, "receipt", true, Collections.singletonList(new Ab(995, "Coins",
                value < 0L ? -1L : 1L, (int) Math.min(Integer.MAX_VALUE, Math.abs(value)), value)));
    }

    private static Am engine()
    {
        return new Am(deltas -> Collections.emptyList(), new TransactionClassifier(), CONFIG);
    }
}
