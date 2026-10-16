package com.gpmanager;

import java.util.List;
import java.util.Map;
import org.junit.Test;

import static com.gpmanager.ReviewFixesTest.T0;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Owner live 2026-10-16 (recording 20261016-225409): Wilderness Agility pays its dispenser into an
 * open looting bag the client never shows, so only the pack's share was counted.
 */
public class AgilityBagRewardTest
{
    static final int ANGLERFISH = 24592;
    static final int MED_HELM = 1147;
    static final int MANTA = 24589;
    static final int TICKET = 29460;

    @Test
    public void theAwardLineNamesEveryItem()
    {
        assertEquals(Map.of("blighted anglerfish", 3L, "rune med helm", 1L, "blighted manta ray", 1L),
            GpManagerPlugin.bagAward("3 x blighted anglerfish and 1 x rune med helm, and an extra blighted manta ray"));
        assertEquals(Map.of("blighted super restore(4)", 2L, "adamant platelegs", 1L),
            GpManagerPlugin.bagAward("2 x blighted super restore(4) and 1 x adamant platelegs"));
    }

    @Test
    public void whatThePackDidNotGetBooksAsLootIntoTheBag()
    {
        Engine engine = ReviewFixesTest.engine();
        engine.noteBagReward(Map.of(ANGLERFISH, 3L, MED_HELM, 1L, MANTA, 1L), T0 + 1_000L);
        // The pack shows only the extra manta ray and the ticket.
        var published = new java.util.ArrayList<Transaction>();
        engine.settle(new ContainerSnapshot(Map.of(MANTA, 1L, TICKET, 1L)), T0 + 2_000L, published::add);
        List<Transaction> rows = engine.getActiveSession().getTransactions();
        Transaction bag = rows.stream().filter(row -> msg("bag-reward").equals(row.getNote())).findFirst().orElseThrow();
        assertEquals(TransactionType.LOOT, bag.getType());
        assertTrue("HUD+'s tray hears about it like any other booking", published.contains(bag));
        assertEquals(2, bag.getFlows().size());
        assertEquals(3L, quantity(bag, ANGLERFISH));
        assertEquals(1L, quantity(bag, MED_HELM));
        assertEquals("the pack's manta ray is not counted twice", 0L, quantity(bag, MANTA));
        assertEquals("every item, ticket included, is counted once", 600L, engine.getMetrics(T0 + 3_000L).net);
    }

    @Test
    public void anAwardThatReachedThePackBooksNothingExtra()
    {
        Engine engine = ReviewFixesTest.engine();
        engine.noteBagReward(Map.of(MANTA, 3L, MED_HELM, 1L), T0 + 1_000L);
        engine.settle(new ContainerSnapshot(Map.of(MANTA, 3L, MED_HELM, 1L, TICKET, 1L)), T0 + 2_000L, ignored -> { });
        assertTrue(engine.getActiveSession().getTransactions().stream().noneMatch(row -> msg("bag-reward").equals(row.getNote())));
        assertEquals(500L, engine.getMetrics(T0 + 3_000L).net);
    }

    /** The bag's dispenser additions never follow a factory reset (or another account) into risk. */
    @Test
    public void bagAdditionsClearOnReset()
    {
        Engine engine = ReviewFixesTest.engine();
        engine.noteBagReward(Map.of(ANGLERFISH, 3L), T0 + 1_000L);
        engine.settle(new ContainerSnapshot(Map.of(TICKET, 1L)), T0 + 2_000L, ignored -> { });
        assertEquals(Long.valueOf(3L), engine.bagAdded.get(ANGLERFISH));
        engine.resetData(T0 + 3_000L);
        assertTrue(engine.bagAdded.isEmpty());
    }

    static String msg(String key)
    {
        return GameData.msg(key);
    }

    static long quantity(Transaction row, int itemId)
    {
        return row.getFlows().stream().filter(flow -> flow.itemId == itemId).mapToLong(flow -> flow.quantityDelta).sum();
    }
}
