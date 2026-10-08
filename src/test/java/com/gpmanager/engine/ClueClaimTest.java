package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.ItemComposition;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Owner 2026-10-08: clue scrolls, scroll boxes and impling jars went to Review. A treasure trail's scrolls,
 * boxes and caskets are claims on the casket's loot, held at zero like a coin pouch, so only the
 * casket's contents count, once it is opened.
 */
public class ClueClaimTest
{
    private static final int CLUE = 2722;
    private static final int NEXT_STEP = 2723;
    private static final int BOX = 24364;
    private static final int CASKET = 20544;
    private static final int PLATEBODY = 1127;
    private static final int COINS = 995;
    private static final int MYSTERY = 30000;
    private static final int EMPTY_JAR = 11260;
    private static final int ECLECTIC = 11248;
    private static final Map<Integer, String> NAMES = new HashMap<>();

    static
    {
        NAMES.put(CLUE, "Clue scroll (hard)");
        NAMES.put(NEXT_STEP, "Clue scroll (hard)");
        NAMES.put(BOX, "Scroll box (hard)");
        NAMES.put(CASKET, "Reward casket (hard)");
        NAMES.put(PLATEBODY, "Rune platebody");
        NAMES.put(COINS, "Coins");
        NAMES.put(MYSTERY, "Mystery scroll");
        NAMES.put(EMPTY_JAR, "Impling jar");
        NAMES.put(ECLECTIC, "Eclectic impling jar");
    }

    private static final GpManagerConfig CONFIG = new GpManagerConfig()
    {
        @Override
        public int stabilizationTicks()
        {
            return 0;
        }
    };

    private final Map<Integer, Long> inventory = new HashMap<>();
    private final Am engine = new Am(new Bl(CONFIG, ClueClaimTest::composition,
        id -> id == PLATEBODY ? 38_000 : id == ECLECTIC ? 20_000 : id == EMPTY_JAR ? 290 : 0), new TransactionClassifier(), CONFIG);
    private long now = 1_000_000L;

    @Test
    public void namesMatchEveryTierAndNothingElse()
    {
        for (String name : new String[] {"Clue scroll (beginner)", "Clue scroll (master)", "Scroll box (elite)",
            "Reward casket (medium)", "Clue bottle (easy)", "Clue nest (hard)", "Clue geode (elite)",
            "Challenge scroll (hard)", "Puzzle box", "Light box"})
        {
            assertTrue(name, Ak.pattern("clue").matcher(name).matches());
        }
        for (String name : new String[] {"Casket", "Clue scroll", "Scroll box (hard) extra", "Bird nest",
            "Clue hunter garb", "Mystery box"})
        {
            assertFalse(name, Ak.pattern("clue").matcher(name).matches());
        }
    }

    @Test
    public void aWholeTrailBooksOnlyTheCasketsLoot()
    {
        engine.ajl("Clues", Cx.GENERAL, now);
        engine.setBaseline(new Cc(new HashMap<>(inventory)));

        change(BOX, 1L);
        change(BOX, -1L, CLUE, 1L);
        change(CLUE, -1L, NEXT_STEP, 1L);
        change(NEXT_STEP, -1L, CASKET, 1L);
        assertEquals("a scroll, a box or a casket is worth nothing yet", 0L, net());
        assertEquals(0, reviews());

        change(CASKET, -1L, PLATEBODY, 1L, COINS, 5_000L);
        assertEquals("the casket's loot counts once it is opened", 43_000L, net());
        assertEquals(0, reviews());
    }

    /** In game the reward window reports the casket first (Loot Tracker), which opens the clue path. */
    @Test
    public void aCasketOpenedThroughItsRewardWindowCountsItsLoot()
    {
        inventory.put(CASKET, 1L);
        engine.ajl("Clues", Cx.GENERAL, now);
        engine.setBaseline(new Cc(new HashMap<>(inventory)));
        engine.ls("loot:Clue Scroll (Hard)", "Clue Scroll (Hard)");

        change(CASKET, -1L, PLATEBODY, 1L, COINS, 5_000L);
        assertEquals(43_000L, net());
        assertEquals(0, reviews());
    }

    /** Owner 2026-10-08: every impling jar, caught or looted, books without Review. */
    @Test
    public void catchingAndLootingAnImplingCountsWithoutReview()
    {
        inventory.put(EMPTY_JAR, 2L);
        engine.ajl("Hunter", Cx.GENERAL, now);
        engine.setBaseline(new Cc(new HashMap<>(inventory)));

        change(EMPTY_JAR, -1L, ECLECTIC, 1L);
        assertEquals("the caught impling is worth its jar less the empty one", 19_710L, net());

        change(ECLECTIC, -1L, PLATEBODY, 1L, EMPTY_JAR, 1L);
        assertEquals("its loot and the empty jar replace the full jar", 19_710L + 38_290L - 20_000L, net());

        // One jar in ten breaks: the loot alone replaces it.
        change(EMPTY_JAR, -1L, ECLECTIC, 1L);
        change(ECLECTIC, -1L, PLATEBODY, 1L);
        assertEquals(19_710L + 18_290L + 19_710L + 18_000L, net());
        assertEquals(0, reviews());
    }

    @Test
    public void anUntradeableThatIsNoClueKeepsItsUnknownPrice()
    {
        engine.ajl("Clues", Cx.GENERAL, now);
        engine.setBaseline(new Cc(new HashMap<>(inventory)));
        change(MYSTERY, 1L);
        Ab flow = engine.getActiveSession().getTransactions().get(0).getFlows().get(0);
        assertEquals(Av.UNPRICED, flow.getPriceSource());
    }

    private void change(Object... idsAndDeltas)
    {
        for (int index = 0; index < idsAndDeltas.length; index += 2)
        {
            inventory.merge((Integer) idsAndDeltas[index], (Long) idsAndDeltas[index + 1], Long::sum);
        }
        inventory.values().removeIf(quantity -> quantity == 0L);
        now += 3_000L;
        engine.adj(new Cc(new HashMap<>(inventory)), now);
        engine.adj(new Cc(new HashMap<>(inventory)), now + 600L);
    }

    private long net()
    {
        return engine.getMetrics(now + 60_000L).net;
    }

    private int reviews()
    {
        int count = 0;
        for (Ac transaction : engine.getActiveSession().getTransactions())
        {
            if (Eh.aal(transaction))
            {
                count++;
            }
        }
        return count;
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
