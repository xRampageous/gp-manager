package com.gpmanager;

import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Login recheck must never infer a grave recovery from item overlap. Genuine income stays booked
 * before and after a death, ambiguous historical rows wait for the owner's explicit correction,
 * and an actual neutral recovery books nothing.
 */
public class LegacyGraveRepairTest
{
    private static final int WHIP = 4151;
    private static final int AVA = 10499;
    private static final int SEED_POD = 19564;
    private static final int BONES = 526;
    private static final int TWISTED_BOW = 20997;
    private static final long T0 = ReviewFixesTest.T0;
    private static final long MINUTE = 60_000L;
    private static final long VALUE = 1_600_000L;

    @Test
    public void genuineNpcLootBeforeALaterGraveKeepsItsBookedIncome()
    {
        Engine engine = ReviewFixesTest.engine();
        var session = engine.session();
        var loot = loot(T0 + 1_000L, WHIP, "Abyssal whip", VALUE);
        session.addTransaction(loot, 2_000, false);
        session.addTransaction(grave(T0 + 5_000L, WHIP, "Abyssal whip", VALUE), 2_000, false);
        assertEquals(VALUE, session.metrics(T0 + MINUTE).net);

        engine.recheck();

        assertEquals("a later grave cannot erase earlier NPC loot", VALUE,
                session.metrics(T0 + MINUTE).net);
        assertEquals(Correction.AUTO, loot.correction());
        assertEquals("no automatic correction was invented", 0, session.correctionHistory.size());
    }

    @Test
    public void genuineNpcLootAfterAnEarlierGraveKeepsItsBookedIncome()
    {
        Engine engine = ReviewFixesTest.engine();
        var session = engine.session();
        session.addTransaction(grave(T0 + 1_000L, WHIP, "Abyssal whip", VALUE), 2_000, false);
        var loot = loot(T0 + 30L * MINUTE, WHIP, "Abyssal whip", VALUE);
        session.addTransaction(loot, 2_000, false);
        assertEquals(VALUE, session.metrics(T0 + 31L * MINUTE).net);

        engine.recheck();

        assertEquals("a new confirmed NPC drop is not a grave recovery", VALUE,
                session.metrics(T0 + 31L * MINUTE).net);
        assertEquals(Correction.AUTO, loot.correction());
    }

    @Test
    public void aFreshPvpDeathKeepsItsOffsetAfterRecheckAndReload()
    {
        Engine engine = ReviewFixesTest.engine();
        var session = engine.session();
        var loot = loot(T0 + 1_000L, WHIP, "Abyssal whip", VALUE);
        session.addTransaction(loot, 2_000, false);
        session.addTransaction(Tx.of(T0 + 5_000L, TransactionType.PK_DEATH_LOSS,
                Context.PK_DEATH, "Death: PvP", true,
                List.of(new Flow(WHIP, "Abyssal whip", -1, VALUE, -VALUE))), 2_000, false);
        assertEquals("loot and its later PK loss offset each other", 0,
                session.metrics(T0 + MINUTE).net);

        engine.recheck();

        assertEquals(0, session.metrics(T0 + MINUTE).net);
        assertEquals(Correction.AUTO, loot.correction());

        engine.restoreProfile("grave-reload", engine.savedState(), T0 + 2L * MINUTE);
        assertEquals("reload keeps the same booked offset", 0, engine.metrics(T0 + 3L * MINUTE).net);
        engine.recheck();
        assertEquals(0, engine.metrics(T0 + 4L * MINUTE).net);
        assertEquals(Correction.AUTO, engine.session().findTransaction(loot.id()).correction());
    }

    @Test
    public void genuineRaidPkAndChestProceedsStayCounted()
    {
        Engine engine = ReviewFixesTest.engine();
        var session = engine.session();
        var raid = loot(T0 + 1_000L, TWISTED_BOW, "Twisted bow", 1_000_000L);
        var pk = pkLoot(T0 + 2_000L, AVA, "Ava's accumulator", 12L);
        var chest = loot(T0 + 3_000L, SEED_POD, "Royal seed pod", 600L);
        session.addTransaction(raid, 2_000, false);
        session.addTransaction(pk, 2_000, false);
        session.addTransaction(chest, 2_000, false);
        session.addTransaction(Tx.of(T0 + 4_000L, TransactionType.TRANSFER, Context.GENERIC,
                GameData.msg("death-items"), false,
                List.of(new Flow(TWISTED_BOW, "Twisted bow", -1, 1_000_000L, -1_000_000L),
                        new Flow(AVA, "Ava's accumulator", -1, 12L, -12L),
                        new Flow(SEED_POD, "Royal seed pod", -1, 600L, -600L))), 2_000, false);

        engine.recheck();

        assertEquals(1_000_612L, session.metrics(T0 + MINUTE).net);
        assertEquals(Correction.AUTO, raid.correction());
        assertEquals(Correction.AUTO, pk.correction());
        assertEquals(Correction.AUTO, chest.correction());
    }

    @Test
    public void anActualNeutralRecoveryStillBooksNoIncome()
    {
        Engine engine = ReviewFixesTest.engine();
        var session = engine.session();
        session.addTransaction(grave(T0 + 1_000L, WHIP, "Abyssal whip", VALUE), 2_000, false);
        session.addTransaction(recovery(T0 + 2_000L, WHIP, "Abyssal whip", VALUE), 2_000, false);
        assertEquals(0, session.metrics(T0 + 3_000L).net);

        engine.recheck();

        assertEquals("the gear return stays ownership-neutral", 0,
                session.metrics(T0 + 3_000L).net);
    }

    @Test
    public void deathFeeAccountingRemainsIntact()
    {
        Engine engine = ReviewFixesTest.engine();
        engine.bookDeathFee(500_000L, null, T0 + 1_000L);
        assertEquals(-500_000L, engine.metrics(T0 + 2_000L).net);

        engine.recheck();

        assertEquals(-500_000L, engine.metrics(T0 + 3_000L).net);
        assertEquals("the observed fee stays one counted row", 1,
                engine.session().transactions().stream()
                        .filter(row -> "Death reclaim".equals(row.activityName()) && row.type() == TransactionType.CONSUMPTION)
                        .count());
    }

    @Test
    public void ambiguousHistoricalGainsWaitForExplicitCorrection()
    {
        Engine engine = ReviewFixesTest.engine();
        var session = engine.session();
        session.addTransaction(grave(T0 + 1_000L, WHIP, "Abyssal whip", VALUE), 2_000, false);
        var ambiguous = gain(T0 + 2_000L, WHIP, "Abyssal whip", VALUE);
        session.addTransaction(ambiguous, 2_000, false);

        engine.recheck();

        assertEquals("item overlap alone cannot prove a recovery", VALUE,
                session.metrics(T0 + MINUTE).net);
        assertEquals(Correction.AUTO, ambiguous.correction());

        assertTrue(session.correctTransaction(ambiguous.id(), Correction.IGNORE, "not income"));
        assertEquals(0, session.metrics(T0 + MINUTE).net);
        engine.recheck();
        assertEquals("an explicit decision is never re-derived", 0, session.metrics(T0 + MINUTE).net);
        assertEquals(Correction.IGNORE, ambiguous.correction());
        assertEquals(1, session.correctionHistory.size());
    }

    @Test
    public void ownerDecisionsAndOriginalPricesSurviveRecheck()
    {
        Engine engine = ReviewFixesTest.engine();
        var session = engine.session();
        var legacyIgnored = gain(T0 + 1_000L, WHIP, "Abyssal whip", VALUE);
        session.addTransaction(legacyIgnored, 2_000, false);
        assertTrue(session.correctTransaction(legacyIgnored.id(), Correction.IGNORE, "old cleanup"));
        var loot = loot(T0 + 2_000L, AVA, "Ava's accumulator", 12L);

        engine.recheck();

        assertEquals("an old IGNORE is never bulk-reversed", Correction.IGNORE, legacyIgnored.correction());
        assertEquals(Correction.AUTO, loot.correction());
        assertEquals("the captured quote is kept, not repriced", 12L, loot.flows().get(0).unitPrice);
        assertEquals(12L, loot.flows().get(0).value);
        assertEquals(0, session.metrics(T0 + MINUTE).net);
    }

    @Test
    public void repeatedRecheckAndReloadRemainStable()
    {
        Engine engine = ReviewFixesTest.engine();
        var session = engine.session();
        var loot = loot(T0 + 1_000L, WHIP, "Abyssal whip", VALUE);
        session.addTransaction(loot, 2_000, false);
        session.addTransaction(grave(T0 + 5_000L, WHIP, "Abyssal whip", VALUE), 2_000, false);

        engine.recheck();
        engine.recheck();
        assertEquals(VALUE, session.metrics(T0 + MINUTE).net);
        assertEquals(0, session.correctionHistory.size());

        engine.restoreProfile("grave-reload", engine.savedState(), T0 + 2L * MINUTE);
        engine.recheck();
        engine.recheck();

        assertEquals(VALUE, engine.metrics(T0 + 3L * MINUTE).net);
        assertEquals(0, engine.session().correctionHistory.size());
        assertNotNull(engine.session().findTransaction(loot.id()));
        assertEquals(Correction.AUTO, engine.session().findTransaction(loot.id()).correction());
    }

    private static Transaction loot(long at, int item, String name, long value)
    {
        var row = Tx.of(at, TransactionType.LOOT, Context.LOOT, name, true,
                List.of(new Flow(item, name, 1, value, value)));
        row.confidence = Confidence.CONFIRMED;
        return row;
    }

    private static Transaction pkLoot(long at, int item, String name, long value)
    {
        var row = Tx.of(at, TransactionType.PK_LOOT, Context.PK_LOOT, name, true,
                List.of(new Flow(item, name, 1, value, value)));
        row.confidence = Confidence.CONFIRMED;
        return row;
    }

    private static Transaction gain(long at, int item, String name, long value)
    {
        return Tx.of(at, TransactionType.GAIN, Context.GENERIC, "Loot", true,
                List.of(new Flow(item, name, 1, value, value)));
    }

    private static Transaction grave(long at, int item, String name, long value)
    {
        return Tx.of(at, TransactionType.TRANSFER, Context.GENERIC, GameData.msg("death-items"),
                false, List.of(new Flow(item, name, -1, value, -value)));
    }

    private static Transaction recovery(long at, int item, String name, long value)
    {
        return Tx.of(at, TransactionType.TRANSFER, Context.GENERIC,
                GameData.msg("death-reclaim-items"), false,
                List.of(new Flow(item, name, 1, value, value)));
    }
}
