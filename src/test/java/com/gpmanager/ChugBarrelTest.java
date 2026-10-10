package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.ItemComposition;
import net.runelite.http.api.item.ItemPrice;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Owner 2026-10-09 (1.3.0, tested in game): a chug from the chugging barrel drinks one dose of every
 * potion in it without touching the inventory. The game names each one ("You have 3 doses of
 * Saradomin brew(1) left in your barrel."), so the doses book as one Drank row titled by the barrel,
 * at each one-dose potion's price, and filling it through its window is a move.
 */
public class ChugBarrelTest
{
    private static final int BREW = 6691;
    private static final int RESTORE = 3030;
    private static final Map<Integer, String> NAMES = Map.of(BREW, "Saradomin brew(1)", RESTORE, "Super restore(1)");
    private static final GpManagerConfig CONFIG = new GpManagerConfig()
    {
        @Override
        public int stabilizationTicks()
        {
            return 0;
        }
    };

    @Test
    public void aChugIsOneDrankRowOfOneDoseOfEachPotion()
    {
        Valuation valuation = new Valuation(CONFIG, ChugBarrelTest::composition, id -> id == BREW ? 2_400 : id == RESTORE ? 3_600 : 0);
        Engine engine = new Engine(valuation, new TransactionClassifier(), CONFIG);
        engine.startCustom("Chug", 1_000_000L);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>()));
        ChargeIntake intake = new ChargeIntake(null, new ItemLookup(null)
        {
            @Override
            List<ItemPrice> find(String name)
            {
                List<ItemPrice> found = new ArrayList<>();
                NAMES.forEach((id, item) ->
                {
                    if (item.toLowerCase().contains(name.toLowerCase()))
                    {
                        ItemPrice price = new ItemPrice();
                        price.setId(id);
                        price.setName(item);
                        found.add(price);
                    }
                });
                return found;
            }
        }, CONFIG, engine, valuation);

        java.util.regex.Pattern chug = GameData.pattern("chug");
        for (String line : new String[] {"You have 3 doses of Saradomin brew(1) left in your barrel.",
            "You have finished all doses of Super restore(1) in your barrel."})
        {
            java.util.regex.Matcher match = chug.matcher(line);
            assertTrue(line, match.matches());
            intake.chug(match.group(1), 1_001_000L);
        }
        intake.drank(1_001_600L);
        intake.drank(1_002_200L);

        List<Transaction> rows = engine.getActiveSession().getTransactions();
        assertEquals("one row per chug", 1, rows.size());
        Transaction row = rows.get(0);
        assertEquals("Chugging barrel", row.spellName());
        assertEquals(ActionKind.DRINK, row.getActionKind());
        assertEquals("one dose of each", -2_400L - 3_600L, engine.getMetrics(1_100_000L).net);
        assertEquals("one Recent row", 1, LiveProbe.recent(rows, LiveContext.NONE).size());
        assertEquals("the row shows the barrel itself", 30000, LiveProbe.recent(rows, LiveContext.NONE).get(0).itemId);
        assertEquals("a spell keeps its sprite", -15, Spells.icon("Wind Strike"));
        assertEquals("no label, no icon", -1, Spells.icon("Drank"));
        assertTrue("filling it through its window is a move",
            GameData.cachedRows("store_windows").stream().anyMatch(store -> "PrepotDevice.FRAME".equals(store[1])));
    }

    @Test
    public void uncharingBryophytasStaffSpendsTheRunesItDestroys()
    {
        // Owner recording 2026-10-09: the runes used to charge the staff are not returned.
        Valuation valuation = new Valuation(CONFIG, ChugBarrelTest::composition, id -> id == 561 ? 138 : 0);
        Engine engine = new Engine(valuation, new TransactionClassifier(), CONFIG);
        engine.startCustom("Alching", 1_000_000L);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>()));
        java.util.regex.Matcher line = GameData.pattern("bru").matcher("You remove 1000 charges from your staff, leaving it empty.");
        assertTrue(line.matches());
        new ChargeIntake(null, null, CONFIG, engine, valuation).emptied(ChargeCheck.parseCount(line.group(1)), 1_001_000L);
        assertEquals(-138_000L, engine.getMetrics(1_100_000L).net);
        Transaction row = engine.getActiveSession().getTransactions().get(0);
        assertEquals("an exact count, not an estimate", "Measured charge spend · Bryophyta's staff", row.getNote());
        assertEquals(Confidence.CONFIRMED, row.getConfidence());
    }

    private static ItemComposition composition(int id)
    {
        String name = NAMES.getOrDefault(id, "Item " + id);
        return (ItemComposition) Proxy.newProxyInstance(ItemComposition.class.getClassLoader(),
            new Class<?>[] {ItemComposition.class}, (proxy, method, args) -> {
                if ("getName".equals(method.getName())) return name;
                Class<?> type = method.getReturnType();
                if (type == boolean.class) return false;
                if (type == int.class) return 0;
                if (type == long.class) return 0L;
                return null;
            });
    }
}
