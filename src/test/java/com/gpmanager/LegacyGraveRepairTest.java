package com.gpmanager;

import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/** Historical grave claims must never remain booked as income, but evidence-free/mixed loot stays. */
public class LegacyGraveRepairTest
{
    private static final int AVA = 10499;
    private static final int SEED_POD = 19564;
    private static final int WHIP = 4151;
    private static final int BONES = 526;

    @Test
    public void pvpGraveItemsAreCorrectedAfterADeath()
    {
        Engine engine = ReviewFixesTest.engine();
        var session = engine.session();
        session.pk().deaths = 1;
        session.addTransaction(death(TransactionType.PK_DEATH_LOSS, "Death", AVA), 200, false);
        var ava = gain(AVA + 1, "Ava's accumulator", 1, 12);
        var seed = gain(AVA + 2, "Royal seed pod", 1, 600);
        session.addTransaction(ava, 200, false);
        session.addTransaction(seed, 200, false);

        engine.recheck(id -> 0);

        assertEquals(Correction.IGNORE, ava.correction());
        assertEquals(Correction.IGNORE, seed.correction());
    }

    @Test
    public void pvmHeldGearIsCorrectedWithoutAPvpDeath()
    {
        Engine engine = ReviewFixesTest.engine();
        var session = engine.session();
        session.addTransaction(death(TransactionType.TRANSFER, GameData.msg("death-items"), WHIP), 200, false);
        var whip = gain(AVA + 1, "Abyssal whip", 1, 1_600_000);
        session.addTransaction(whip, 200, false);

        engine.recheck(id -> 0);

        assertEquals(Correction.IGNORE, whip.correction());
    }

    @Test
    public void mixedLootAndRowsWithoutDeathEvidenceStayUntouched()
    {
        Engine engine = ReviewFixesTest.engine();
        var session = engine.session();
        session.addTransaction(death(TransactionType.PK_DEATH_LOSS, "Death", AVA), 200, false);
        var mixed = Tx.of(AVA + 1, TransactionType.GAIN, Context.GENERIC, "Mixed", true,
                List.of(new Flow(SEED_POD, "Royal seed pod", 1, 600, 600),
                        new Flow(BONES, "Bones", 1, 2, 2)));
        var noDeath = gain(AVA + 2, "Royal seed pod", 1, 600);
        session.addTransaction(mixed, 200, false);
        session.addTransaction(noDeath, 200, false);

        engine.recheck(id -> 0);

        assertNotEquals(Correction.IGNORE, mixed.correction());
        assertNotEquals(Correction.IGNORE, noDeath.correction());
    }

    @Test
    public void repairIsIdempotentAndRetainsTheCorrection()
    {
        Engine engine = ReviewFixesTest.engine();
        var session = engine.session();
        session.pk().deaths = 1;
        var seed = gain(AVA, "Royal seed pod", 1, 600);
        session.addTransaction(seed, 200, false);

        engine.recheck(id -> 0);
        engine.recheck(id -> 0);

        assertEquals(Correction.IGNORE, seed.correction());
        assertEquals(1, session.correctionHistory.size());
    }

    private static Transaction death(TransactionType type, String note, int item)
    {
        return Tx.of(ReviewFixesTest.T0, type, type == TransactionType.PK_DEATH_LOSS ? Context.PK_DEATH : Context.GENERIC,
                note, true, List.of(new Flow(item, "Held item", -1, 1, -1)));
    }

    private static Transaction gain(long at, String name, long quantity, long price)
    {
        int item = name.contains("Royal") ? SEED_POD : name.contains("Ava") ? AVA : WHIP;
        return Tx.of(at, TransactionType.GAIN, Context.GENERIC, "Loot", true,
                List.of(new Flow(item, name, quantity, price, quantity * price)));
    }
}
