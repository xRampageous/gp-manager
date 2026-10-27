package com.gpmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Owner 2026-10-09 (1.3.0): Barrows gear wearing down a stage or repaired is the same item, so
 * neutral, while the repair fee counts; an NPC paid in a skilling currency is a purchase; an
 * Imp-in-a-box sends items to the bank; a fishing key half is an ordinary gain.
 */
public class WearAndCurrencyTest
{
    private static final int COINS = 995;
    private static final int HELM = 4716;
    private static final int HELM_100 = 4880;
    private static final int HELM_75 = 4881;
    private static final int HELM_0 = 4884;
    private static final int KEY_HALF = 985;
    private static final int FLAKES = 25129;
    private static final int FISH_BARREL = 25582;
    private static final Map<Integer, String> NAMES = Map.of(COINS, "Coins", HELM, "Dharok's helm",
        HELM_100, "Dharok's helm 100", HELM_75, "Dharok's helm 75", HELM_0, "Dharok's helm 0",
        KEY_HALF, "Tooth half of key", FLAKES, "Spirit flakes", FISH_BARREL, "Fish barrel");
    private static final Map<Integer, Integer> PRICES = Map.of(COINS, 1, HELM, 1_200_000, HELM_100, 1_200_000,
        HELM_75, 1_200_000, HELM_0, 60_000, KEY_HALF, 9_543, FLAKES, 20, FISH_BARREL, 0);

    private final Map<Integer, Long> inventory = new HashMap<>(Map.of(COINS, 1_000_000L, HELM, 1L, FLAKES, 6_000L));
    private final Engine engine = new Engine(deltas ->
    {
        List<Flow> flows = new ArrayList<>();
        deltas.forEach((id, quantity) -> flows.add(new Flow(id, NAMES.get(id), quantity, PRICES.get(id),
            quantity * PRICES.get(id), id == COINS ? PriceSource.FACE_VALUE : PriceSource.GRAND_EXCHANGE)));
        return flows;
    }, new TransactionClassifier(), new GpManagerConfig()
    {
        @Override
        public int stabilizationTicks()
        {
            return 0;
        }
    });
    private long now = 1_000_000L;

    @Test
    public void barrowsWearIsNeutralAndOnlyTheRepairFeeCounts()
    {
        start();
        change(HELM, -1L, HELM_100, 1L);
        change(HELM_100, -1L, HELM_75, 1L);
        change(HELM_75, -1L, HELM_0, 1L);
        assertEquals("wearing down books nothing", 0L, net());
        assertTrue("and nothing waits in Review", engine.session().transactions().stream().noneMatch(ReviewRules::needsReview));

        engine.events.put(Inbox.DIALOGUE, 0, 1L, 4, null);
        Transaction repair = change(HELM_0, -1L, COINS, -100_000L, HELM, 1L);
        assertTrue(repair.counts());
        assertEquals("only the fee counts", -100_000L, net());

        assertNull("an ordinary Barrows buy is not wear", Engine.piece(new Flow(COINS, "Coins", 1L, 1, 1L)));
        assertEquals("dharok's helm", Engine.piece(new Flow(HELM_75, "Dharok's helm 75", 1L, 0, 0L)));
    }

    @Test
    public void brokenWearKeepsSourceConfirmedLootOfTheSamePiece()
    {
        assertWearKeepsLoot(HELM_75, HELM_0);
    }

    @Test
    public void unbrokenWearKeepsSourceConfirmedLootOfTheSamePiece()
    {
        assertWearKeepsLoot(HELM_100, HELM_75);
    }

    private void assertWearKeepsLoot(int before, int after)
    {
        start();
        change(HELM, -1L, before, 1L);
        engine.markLoot(Map.of(HELM, 1L), 30, "Loot from fixture NPC", "Fixture NPC");
        Transaction loot = change(before, -1L, after, 1L, HELM, 1L);

        Metrics metrics = engine.metrics(now + 60_000L);
        assertEquals(1_200_000L, metrics.net);
        assertEquals(1_200_000L, metrics.income);
        assertEquals(0L, metrics.cost);
        assertSame(TransactionType.LOOT, loot.type());
        assertTrue(loot.counts());
        assertEquals(1, loot.flows().size());
        assertEquals(HELM, loot.flows().get(0).item);
        assertEquals(1L, loot.flows().get(0).quantity);
        assertTrue(engine.session().transactions().stream().noneMatch(ReviewRules::needsReview));
    }

    @Test
    public void anNpcPaidInSkillingCurrencyIsAPurchase()
    {
        start();
        engine.events.put(Inbox.DIALOGUE, 0, 1L, 4, null);
        Transaction bought = change(FLAKES, -6_000L, FISH_BARREL, 1L);
        assertSame(TransactionType.TRADE, bought.type());
        assertSame(Context.MARKET, bought.context());
        assertTrue("not Review", !ReviewRules.needsReview(bought));
    }

    @Test
    public void anImpInABoxBanksAndAKeyHalfIsAGain()
    {
        assertEquals("Sent to the bank by an imp-in-a-box", NeutralTransfers.storage("use dragon bones -> imp-in-a-box(2)"));
        assertEquals("Master scroll book", NeutralTransfers.storage("use teleport to house -> master scroll book"));
        assertTrue("a loot sack is held at zero until opened", ActionReader.isClaim("Hunters' loot sack (expert)"));
        for (String bag : new String[] {"Tiny port coin bag", "Port coin bag", "Large port reward bag", "Hallowed sack"})
        {
            assertTrue(bag + " is held at zero until opened", ActionReader.isClaim(bag));
        }
        assertTrue("an empty sack is no claim", !ActionReader.isClaim("Empty sack"));
        start();
        Transaction half = change(KEY_HALF, 1L);
        assertSame(TransactionType.GAIN, half.type());
        assertEquals(9_543L, net());
    }

    private void start()
    {
        engine.startCustom("Barrows", now);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(inventory)));
    }

    private Transaction change(Object... idsAndDeltas)
    {
        for (int index = 0; index < idsAndDeltas.length; index += 2)
        {
            inventory.merge((Integer) idsAndDeltas[index], (Long) idsAndDeltas[index + 1], Long::sum);
        }
        inventory.values().removeIf(quantity -> quantity == 0L);
        int before = engine.session().transactions().size();
        now += 3_000L;
        engine.processIfDirty(new ContainerSnapshot(new HashMap<>(inventory)), now, null);
        engine.processIfDirty(new ContainerSnapshot(new HashMap<>(inventory)), now + 600L, null);
        List<Transaction> all = engine.session().transactions();
        return all.size() > before ? all.get(all.size() - 1) : null;
    }

    private long net()
    {
        return engine.metrics(now + 60_000L).net;
    }
}
