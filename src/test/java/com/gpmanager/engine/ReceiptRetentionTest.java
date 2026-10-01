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
        public ReceiptRetentionPeriod receiptRetentionDays()
        {
            return ReceiptRetentionPeriod.DAYS_90;
        }
    };

    @Test
    public void compactsRowsStrictlyOlderThanTheCutoffByTransactionTimeAcrossOwners()
    {
        // Section L: the cutoff is transaction time, whether the owner is closed or still active.
        long now = 100L * DAY + 12L * 60L * 60L * 1_000L;
        long cutoff = now - 30L * DAY;
        Session boundary = new Session("Boundary", cutoff - 10_000L);
        boundary.addTransaction(receipt(cutoff, 100L), 10);
        boundary.close(cutoff + 1_000L);
        Session old = new Session("Old", cutoff - 10_000L);
        old.addTransaction(receipt(cutoff - 1L, 200L), 10);
        old.close(cutoff + 2_000L);
        Session active = new Session("Active", cutoff - 2L * DAY);
        active.addTransaction(receipt(cutoff - DAY, 300L), 10);
        active.addTransaction(receipt(cutoff + DAY, 50L), 10);

        Engine engine = engine();
        engine.restore(new SavedState(null, null, false, Arrays.asList(boundary, old, old, active)), now);

        assertEquals(2, engine.compactOlderThan(30, now));
        assertEquals("a row exactly at the cutoff is retained", 1, boundary.getTransactions().size());
        assertTrue(old.getTransactions().isEmpty());
        assertEquals(1L, old.compactedTransactionCount);
        assertEquals(200L, old.metrics(now).revenue);
        assertEquals("only the active owner's overdue row folds", 1, active.getTransactions().size());
        assertEquals(350L, active.metrics(now).revenue);
        assertEquals(0, engine.compactOlderThan(30, now));
        assertEquals(0, engine.compactOlderThan(0, now));
    }

    @Test
    public void derivedPendingClaimIdKeepsItsOriginAuditRowRetained()
    {
        long now = 400L * DAY;
        Session session = new Session("Loot origin", now - 200L * DAY);
        Transaction audit = receipt(now - 200L * DAY + 1_000L, 0L);
        session.addTransaction(audit, 10);
        session.close(now - 200L * DAY + 2_000L);

        SavedState state = new SavedState(null, null, false, Collections.singletonList(session));
        state.setPendingClaims(Collections.singletonList(new SavedState.PendingClaim(
            audit.getId() + "#2", net.runelite.api.gameval.ItemID.WILDY_LOOT_KEY1, 1L,
            audit.timestampEpochMillis)));

        Engine engine = engine();
        engine.restore(state, now);

        assertEquals("a multi-key claim protects the shared origin audit row", 1,
            session.getTransactions().size());
        assertTrue(engine.getPendingClaims().get(0).getClaimId().endsWith("#2"));
        assertEquals(0, engine.compactOlderThan(90, now));
    }

    @Test
    public void compactionKeepsMetadataEncountersAndExactTotals()
    {
        long now = 400L * DAY;
        Session session = new Session("Favourite trip", now - 200L * DAY, SessionMode.PK);
        java.util.Collections.addAll(session.tags, "boss", "pet");
        session.notes = "keep this note";
        session.setFavorite(true);
        session.setExcludedFromAverages(true);

        session.addTransaction(receipt(now - 200L * DAY + 1_000L, 100L), 10);
        session.addTransaction(receipt(now - 200L * DAY + 61_000L, 250L), 10);
        session.addTransaction(receipt(now - 200L * DAY + 62_500L, -40L), 10);
        PkEncounter encounter = session.addPkEncounter(EncounterType.KILL, now - 200L * DAY + 62_000L,
            "Player kill", null, "observed encounter");
        session.attachTransactionToEncounter(session.getTransactions().get(1).getId(), encounter.getId(), false);
        session.close(now - 200L * DAY + 120_000L);

        SessionMetrics before = session.metrics(now);
        PkMetrics pkBefore = session.pkMetrics();
        Engine engine = engine();
        engine.restore(new SavedState(null, null, false, Collections.singletonList(session)), now);

        assertEquals("current-schema restore already compacted the overdue rows", 0,
            engine.compactOlderThan(90, now));

        assertTrue(session.getTransactions().isEmpty());
        assertEquals(3L, session.compactedTransactionCount);
        assertEquals("boss, pet", String.join(", ", session.tags));
        assertEquals("keep this note", session.notes);
        assertTrue(session.favorite);
        assertTrue(session.excludedFromAverages);
        assertEquals("detail beyond the receipt horizon is compacted", 0, session.getPkEncounters().size());
        assertEquals(PkDetailScope.RETAINED_WINDOW,
            session.pkProjection.getDetailScope());
        assertEquals("compacted detail stays represented by exact counts",
            1, session.pkProjection.getKills());

        SessionMetrics after = session.metrics(now);
        assertEquals(before.revenue, after.revenue);
        assertEquals(before.costs, after.costs);
        assertEquals(before.net, after.net);
        assertEquals(350L, after.revenue);
        assertEquals(40L, after.costs);
        PkMetrics pkAfter = session.pkMetrics();
        assertEquals(pkBefore.kills, pkAfter.kills);
        assertEquals(pkBefore.net, pkAfter.net);
        assertEquals(pkBefore.bestKill, pkAfter.bestKill);
    }

    @Test
    public void currentSchemaRestoreCompactsOverdueRowsWithoutWaitingForUtcRollover()
    {
        long now = 120L * DAY + 12L * 60L * 60L * 1_000L;
        Session overdue = closedWithReceipt("Overdue", now - 120L * DAY - 1L, 750L);
        SavedState current = new SavedState(null, null, false, Collections.singletonList(overdue));
        current.setLastReceiptRetentionDayUtc(LocalDate.ofEpochDay(120L).toString());

        Engine engine = engine();
        engine.restore(current, now);

        assertTrue(overdue.getTransactions().isEmpty());
        assertEquals(1L, overdue.compactedTransactionCount);
        assertEquals(750L, overdue.metrics(now).revenue);
        assertEquals(LocalDate.ofEpochDay(120L).toString(),
            engine.createSavedState().getLastReceiptRetentionDayUtc());
    }

    @Test
    public void shippingRetentionWindowsAreBoundedAndUnknownReadsAs90Days()
    {
        assertEquals(Arrays.asList(30, 90, 180, 365), Arrays.asList(
            ReceiptRetentionPeriod.DAYS_30.getDays(), ReceiptRetentionPeriod.DAYS_90.getDays(),
            ReceiptRetentionPeriod.DAYS_180.getDays(), ReceiptRetentionPeriod.DAYS_365.getDays()));
        assertEquals(4, ReceiptRetentionPeriod.values().length);
        for (ReceiptRetentionPeriod period : ReceiptRetentionPeriod.values())
        {
            assertFalse("no shipping window means retain forever", period.getDays() <= 0);
        }
    }

    private static Session closedWithReceipt(String name, long endedAt, long value)
    {
        long start = Math.max(0L, endedAt - 10_000L);
        Session session = new Session(name, start);
        session.addTransaction(receipt(start + 1_000L, value), 10);
        session.close(endedAt);
        return session;
    }

    private static Transaction receipt(long timestamp, long value)
    {
        return Tx.of(timestamp, value < 0L ? TransactionType.CONSUMPTION : TransactionType.LOOT,
            Context.LOOT, "receipt", true, Collections.singletonList(new Flow(995, "Coins",
                value < 0L ? -1L : 1L, (int) Math.min(Integer.MAX_VALUE, Math.abs(value)), value)));
    }

    private static Engine engine()
    {
        return new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(), CONFIG);
    }
}
