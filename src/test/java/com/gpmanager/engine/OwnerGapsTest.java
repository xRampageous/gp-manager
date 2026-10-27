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
 * Owner 2026-10-09 (1.3.0): clue drops ride HUD+ again, opening caskets or impling jars is one
 * tray row per container, NPC dialogue purchases book like a shop's, a house servant's bank trips
 * are neutral, and the PvP tutorial arena is a lending zone.
 */
public class OwnerGapsTest
{
    private static final int COINS = 995;
    private static final int CLUE = 2722;
    private static final int CASKET = 20544;
    private static final int PLATEBODY = 1127;
    private static final int THIRD_AGE = 10348;
    private static final int TEAK_PLANK = 8780;
    private static final int TEAK_LOGS = 6333;
    private static final int JAR = 11260;
    private static final Map<Integer, String> NAMES = Map.of(COINS, "Coins", CLUE, "Clue scroll (hard)",
        CASKET, "Reward casket (hard)", PLATEBODY, "Rune platebody", THIRD_AGE, "3rd age platebody",
        TEAK_PLANK, "Teak plank", TEAK_LOGS, "Teak logs", JAR, "Impling jar");
    private static final Map<Integer, Integer> PRICES = Map.of(COINS, 1, PLATEBODY, 38_000,
        THIRD_AGE, 900_000_000, TEAK_PLANK, 600, TEAK_LOGS, 90, JAR, 290);

    private final Map<Integer, Long> inventory = new HashMap<>(Map.of(COINS, 100_000L));
    private final Engine engine = new Engine(deltas ->
    {
        List<Flow> flows = new ArrayList<>();
        deltas.forEach((id, quantity) ->
        {
            String name = NAMES.getOrDefault(id, "Item " + id);
            boolean clue = GameData.pattern("clue").matcher(name).matches();
            int unit = clue ? 0 : PRICES.getOrDefault(id, 0);
            flows.add(new Flow(id, name, quantity, unit, quantity * unit,
                id == COINS ? PriceSource.FACE_VALUE : clue ? PriceSource.DEFERRED_CLAIM : PriceSource.GRAND_EXCHANGE));
        });
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
    public void aClueDropRidesTheTrayWithNoQuestionMark()
    {
        start();
        Transaction drop = change(CLUE, 1L);
        assertTrue("a clue drop books now", drop != null);
        HudTray tray = new HudTray();
        tray.booked(drop, now, false, now);
        HudTray.Entry entry = tray.entries().get(0);
        assertEquals("Clue scroll (hard)", entry.name);
        assertEquals("worth nothing yet, and no question mark", "", HudBuilder.value(entry));
        assertNull("opening it books nothing", change(CLUE, -1L, CASKET, 1L));
    }

    @Test
    public void openingCasketsIsOneRowPerCasketTypeWithItsTotal()
    {
        HudTray tray = new HudTray();
        tray.booked(opened(PLATEBODY, 38_000L), now, false, now);
        tray.booked(opened(PLATEBODY, 38_000L), now + 600L, false, now + 600L);
        assertEquals("one row, not one per reward", 1, tray.entries().size());
        HudTray.Entry casket = tray.entries().get(0);
        assertEquals("Reward casket (hard)", casket.name);
        assertEquals(2L, casket.quantity);
        assertEquals(76_000L, casket.value);
        assertEquals("Opened", tray.label());

        tray.booked(opened(THIRD_AGE, 900_000_000L), now + 1_200L, false, now + 1_200L);
        assertTrue("a big drop keeps its own gold row", tray.entries().stream()
            .anyMatch(entry -> entry.item == THIRD_AGE && entry.gold));

        Flow empty = new Flow(JAR, "Impling jar", -1L, 290, -290L);
        Flow full = new Flow(11248, "Eclectic impling jar", 1L, 20_000, 20_000L);
        assertNull("catching an impling is no opening", HudTray.opened(List.of(empty, full)));
        assertEquals("looting the jar is", 11248, HudTray.opened(List.of(new Flow(11248, "Eclectic impling jar", -1L, 20_000, -20_000L),
            new Flow(PLATEBODY, "Rune platebody", 1L, 38_000, 38_000L))).item);
    }

    @Test
    public void coinsForAnItemWhileTalkingToAnNpcIsAPurchase()
    {
        start();
        assertTrue("without a conversation it stays Review", ReviewRules.needsReview(change(COINS, -2_000L, JAR, 1L)));
        engine.events.put(Inbox.DIALOGUE, 0, 1L, 4, null);
        Transaction bought = change(COINS, -2_000L, JAR, 1L);
        assertSame(TransactionType.TRADE, bought.type());
        assertSame(Context.MARKET, bought.context());
        assertTrue(bought.counts());
        assertTrue("not Review", !ReviewRules.needsReview(bought));
        assertEquals("paid 2,000 for a 290 jar; the first is still in Review", -1_710L, net());
    }

    @Test
    public void aServantsBankTripsAreNeutralButHisWageAndTheSawmillCount()
    {
        start();
        engine.events.put(Inbox.SERVANT, 0, 1L, 4, null);
        change(TEAK_PLANK, 26L);
        assertEquals("planks fetched from the bank", 0L, net());

        engine.events.put(Inbox.SERVANT, 0, 1L, 4, null);
        change(COINS, -10_000L, TEAK_PLANK, 26L);
        assertEquals("his wage counts; the planks he brought do not", -10_000L, net());

        engine.events.put(Inbox.SERVANT, 0, 1L, 4, null);
        engine.events.put(Inbox.SAWMILL, 0, 1L, 200, null);
        change(TEAK_LOGS, -26L, COINS, -13_000L);
        engine.events.put(Inbox.SERVANT, 0, 1L, 4, null);
        change(TEAK_PLANK, 26L);
        assertEquals("the sawmill: logs and fee spent, planks made", -10_000L - 26L * 90L - 13_000L + 26L * 600L, net());

        engine.events.put(Inbox.SERVANT, 0, 1L, 4, null);
        change(TEAK_PLANK, -26L);
        assertEquals("taking planks to the bank after the sawmill trip is neutral again",
            -10_000L - 26L * 90L - 13_000L + 26L * 600L, net());
    }

    @Test
    public void thePvpTutorialArenaIsALendingZone()
    {
        assertTrue(MinigameRegionHints.pvpTutorial(10588));
        assertTrue("Ferox Enclave itself is not", !MinigameRegionHints.pvpTutorial(12344));
        assertTrue("the Gauntlet keeps its own rule", MinigameRegionHints.isNeutralZoneRegion(7512) && !MinigameRegionHints.pvpTutorial(7512));
    }

    private Transaction opened(int reward, long value)
    {
        return Tx.of(now, TransactionType.GAIN, Context.GENERIC, "", true, List.of(
            new Flow(CASKET, "Reward casket (hard)", -1L, 0, 0L, PriceSource.DEFERRED_CLAIM),
            new Flow(reward, NAMES.get(reward), 1L, (int) Math.min(Integer.MAX_VALUE, value), value)));
    }

    private void start()
    {
        engine.startCustom("Gaps", now);
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
