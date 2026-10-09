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
    private final Am engine = new Am(deltas ->
    {
        List<Ab> flows = new ArrayList<>();
        deltas.forEach((id, quantity) ->
        {
            String name = NAMES.getOrDefault(id, "Item " + id);
            boolean clue = Ak.pattern("clue").matcher(name).matches();
            int unit = clue ? 0 : PRICES.getOrDefault(id, 0);
            flows.add(new Ab(id, name, quantity, unit, quantity * unit,
                id == COINS ? Av.FACE_VALUE : clue ? Av.DEFERRED_CLAIM : Av.GRAND_EXCHANGE));
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
        Ac drop = change(CLUE, 1L);
        assertTrue("a clue drop books now", drop != null);
        HudTray tray = new HudTray();
        tray.booked(drop, now, false);
        HudTray.Entry entry = tray.entries().get(0);
        assertEquals("Clue scroll (hard)", entry.name);
        assertEquals("worth nothing yet, and no question mark", "", Cp.value(entry));
        assertNull("opening it books nothing", change(CLUE, -1L, CASKET, 1L));
    }

    @Test
    public void openingCasketsIsOneRowPerCasketTypeWithItsTotal()
    {
        HudTray tray = new HudTray();
        tray.booked(opened(PLATEBODY, 38_000L), now, false);
        tray.booked(opened(PLATEBODY, 38_000L), now + 600L, false);
        assertEquals("one row, not one per reward", 1, tray.entries().size());
        HudTray.Entry casket = tray.entries().get(0);
        assertEquals("Reward casket (hard)", casket.name);
        assertEquals(2L, casket.quantity);
        assertEquals(76_000L, casket.value);
        assertEquals("Opened", tray.label());

        tray.booked(opened(THIRD_AGE, 900_000_000L), now + 1_200L, false);
        assertTrue("a big drop keeps its own gold row", tray.entries().stream()
            .anyMatch(entry -> entry.itemId == THIRD_AGE && entry.gold));

        Ab empty = new Ab(JAR, "Impling jar", -1L, 290, -290L);
        Ab full = new Ab(11248, "Eclectic impling jar", 1L, 20_000, 20_000L);
        assertNull("catching an impling is no opening", HudTray.opened(List.of(empty, full)));
        assertEquals("looting the jar is", 11248, HudTray.opened(List.of(new Ab(11248, "Eclectic impling jar", -1L, 20_000, -20_000L),
            new Ab(PLATEBODY, "Rune platebody", 1L, 38_000, 38_000L))).itemId);
    }

    @Test
    public void coinsForAnItemWhileTalkingToAnNpcIsAPurchase()
    {
        start();
        assertTrue("without a conversation it stays Review", Eh.aal(change(COINS, -2_000L, JAR, 1L)));
        engine.eb.put(Eb.DG, 0, 1L, 4, null);
        Ac bought = change(COINS, -2_000L, JAR, 1L);
        assertSame(Ai.TRADE, bought.getType());
        assertSame(Aj.MARKET, bought.getContext());
        assertTrue(bought.isCounted());
        assertTrue("not Review", !Eh.aal(bought));
        assertEquals("paid 2,000 for a 290 jar; the first is still in Review", -1_710L, net());
    }

    @Test
    public void aServantsBankTripsAreNeutralButHisWageAndTheSawmillCount()
    {
        start();
        engine.eb.put(Eb.SV, 0, 1L, 4, null);
        change(TEAK_PLANK, 26L);
        assertEquals("planks fetched from the bank", 0L, net());

        engine.eb.put(Eb.SV, 0, 1L, 4, null);
        change(COINS, -10_000L, TEAK_PLANK, 26L);
        assertEquals("his wage counts; the planks he brought do not", -10_000L, net());

        engine.eb.put(Eb.SV, 0, 1L, 4, null);
        engine.eb.put(Eb.SW, 0, 1L, 200, null);
        change(TEAK_LOGS, -26L, COINS, -13_000L);
        engine.eb.put(Eb.SV, 0, 1L, 4, null);
        change(TEAK_PLANK, 26L);
        assertEquals("the sawmill: logs and fee spent, planks made", -10_000L - 26L * 90L - 13_000L + 26L * 600L, net());

        engine.eb.put(Eb.SV, 0, 1L, 4, null);
        change(TEAK_PLANK, -26L);
        assertEquals("taking planks to the bank after the sawmill trip is neutral again",
            -10_000L - 26L * 90L - 13_000L + 26L * 600L, net());
    }

    @Test
    public void thePvpTutorialArenaIsALendingZone()
    {
        assertTrue(MinigameRegionHints.xa(10588));
        assertTrue("Ferox Enclave itself is not", !MinigameRegionHints.xa(12344));
        assertTrue("the Gauntlet keeps its own rule", MinigameRegionHints.xl(7512) && !MinigameRegionHints.xa(7512));
    }

    private Ac opened(int reward, long value)
    {
        return Tx.of(now, Ai.GAIN, Aj.GENERIC, "", true, List.of(
            new Ab(CASKET, "Reward casket (hard)", -1L, 0, 0L, Av.DEFERRED_CLAIM),
            new Ab(reward, NAMES.get(reward), 1L, (int) Math.min(Integer.MAX_VALUE, value), value)));
    }

    private void start()
    {
        engine.ajl("Gaps", Cx.GENERAL, now);
        engine.setBaseline(new Cc(new HashMap<>(inventory)));
    }

    private Ac change(Object... idsAndDeltas)
    {
        for (int index = 0; index < idsAndDeltas.length; index += 2)
        {
            inventory.merge((Integer) idsAndDeltas[index], (Long) idsAndDeltas[index + 1], Long::sum);
        }
        inventory.values().removeIf(quantity -> quantity == 0L);
        int before = engine.getActiveSession().getTransactions().size();
        now += 3_000L;
        engine.adj(new Cc(new HashMap<>(inventory)), now);
        engine.adj(new Cc(new HashMap<>(inventory)), now + 600L);
        List<Ac> all = engine.getActiveSession().getTransactions();
        return all.size() > before ? all.get(all.size() - 1) : null;
    }

    private long net()
    {
        return engine.getMetrics(now + 60_000L).net;
    }
}
