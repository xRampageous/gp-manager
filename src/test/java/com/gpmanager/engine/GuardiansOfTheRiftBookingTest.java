package com.gpmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Owner live test 2026-10-12/13: emptying an essence pouch right before an altar craft made the
 * custody evidence neutralize the whole settle, hiding the crafted runes; depositing runes at
 * the rift's deposit pool booked a bogus Cast. The pouch owns only its essence; the craft's
 * runes classify normally, and the deposit pool is a bank move.
 */
public class GuardiansOfTheRiftBookingTest
{
    private static final int ESSENCE = ItemID.GOTR_GUARDIAN_ESSENCE;
    private static final int PURE_ESSENCE = ItemID.BLANKRUNE_HIGH;
    private static final int FIRE_RUNE = ItemID.FIRERUNE;
    private static final int STONE = ItemID.GOTR_GUARDIAN_STONE_ELEMENTAL;

    private static final Map<Integer, String> NAMES = Map.of(
        ESSENCE, "Guardian essence", PURE_ESSENCE, "Pure essence", FIRE_RUNE, "Fire rune",
        STONE, "Elemental guardian stone");
    private static final Map<Integer, Integer> PRICES = Map.of(
        ESSENCE, 30, PURE_ESSENCE, 5, FIRE_RUNE, 5, STONE, 30);

    private static final String POUCH_CUSTODY = "Container empty_to_inventory (essence_pouch)";
    private static final String DEPOSIT_POOL = "Deposit pool transfer";

    private static Engine engine()
    {
        Engine engine = new Engine(deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            deltas.forEach((id, quantity) -> flows.add(new Flow(id, NAMES.get(id), quantity, PRICES.get(id),
                quantity * PRICES.get(id))));
            return flows;
        }, new TransactionClassifier(), new GpManagerConfig()
        {
        });
        engine.ensureSession(1_000_000L);
        return engine;
    }

    private static Engine settle(
        Map<Integer, Long> before, Map<Integer, Long> after, String custodyNote)
    {
        Engine engine = engine();
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(before)));
        if (custodyNote != null) engine.markContext(Context.TRANSFER, 6, custodyNote);
        engine.markDirty();
        for (int tick = 1; tick <= 5; tick++)
        {
            engine.processIfDirty(new ContainerSnapshot(new HashMap<>(after)), 1_000_000L + tick * 600L, null);
        }
        return engine;
    }

    @Test
    public void aCraftInThePouchCustodyStillBooksItsRunes()
    {
        // The live shape: the pouch was emptied, then the craft consumed the essence and produced
        // runes plus a guardian stone. The strip removes the minigame items (essence, stone); the
        // custody must not swallow the runes with them.
        Engine engine = settle(Map.of(ESSENCE, 1L), Map.of(FIRE_RUNE, 132L, STONE, 40L), POUCH_CUSTODY);
        List<Transaction> rows = engine.session().transactions();
        assertEquals(rows.toString(), 1, rows.size());
        assertTrue(rows.get(0).counts());
        assertEquals(132L * 5L, engine.metrics(1_010_000L).net);
    }

    @Test
    public void thePouchEmptyItselfStaysNeutral()
    {
        // Pure essence has no minigame strip: the custody removal itself keeps the move neutral.
        Engine engine = settle(Map.of(), Map.of(PURE_ESSENCE, 28L), POUCH_CUSTODY);
        assertTrue(engine.session().transactions().toString(),
            engine.session().transactions().isEmpty());
        assertEquals(0L, engine.metrics(1_010_000L).net);
    }

    @Test
    public void aMergedEmptyAndCraftSplitsIntoTheRunesOnly()
    {
        // Empty (28 essence) and craft (24 essence used) inside one window: the net essence +4 is
        // the pouch's own leftover and stays neutral; the runes are the craft's real gain.
        Engine engine = settle(Map.of(), Map.of(PURE_ESSENCE, 4L, FIRE_RUNE, 96L), POUCH_CUSTODY);
        List<Transaction> rows = engine.session().transactions();
        assertEquals(rows.toString(), 1, rows.size());
        assertTrue(rows.get(0).counts());
        assertEquals(96L * 5L, engine.metrics(1_010_000L).net);
    }

    @Test
    public void aCraftWithoutCustodyStaysACountedGain()
    {
        // The control: with no pouch click in the window the runes were already the one gain.
        Engine engine = settle(Map.of(ESSENCE, 1L), Map.of(FIRE_RUNE, 132L, STONE, 40L), null);
        assertEquals(132L * 5L, engine.metrics(1_010_000L).net);
    }

    @Test
    public void theDepositPoolDepositBooksNoCast()
    {
        // "Deposit-runes" on the deposit pool marks a custody transfer (neutral_transfers.tsv):
        // the runes leave for the bank and nothing counts, instead of a rune-only Cast.
        Engine engine = settle(Map.of(FIRE_RUNE, 253L), Map.of(), DEPOSIT_POOL);
        assertFalse(engine.session().transactions().stream().anyMatch(Transaction::counts));
        assertEquals(0L, engine.metrics(1_010_000L).net);
    }

    @Test
    public void theDepositPoolNoteComesFromItsTableRow()
    {
        // The plugin hands the joined menu text to NeutralTransfers; the row's note is this text.
        assertEquals(DEPOSIT_POOL, NeutralTransfers.storage("Deposit-runes Deposit Pool"));
    }
}
