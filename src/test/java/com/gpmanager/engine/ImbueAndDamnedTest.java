package com.gpmanager;

import java.lang.reflect.Proxy;
import net.runelite.api.ItemComposition;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Owner 2026-10-09 (1.3.0): imbuing keeps an item's worth, so an imbued black mask or slayer helmet
 * is worth the plain black mask and an NMZ imbue nets nothing. The Amulet of the damned degrades
 * like Barrows gear (a neutral swap) and is worth its full self until it turns to dust, when its
 * worth counts as used up (OSRS Wiki).
 */
public class ImbueAndDamnedTest
{
    private final Valuation valuation = ModelProbe.valuation(new GpManagerConfig() { }, ImbueAndDamnedTest::composition,
        id -> id == 8921 ? 1_000_000 : id == 12851 ? 29_000 : 0);

    @Test
    public void imbuedMasksAndHelmetsAreWorthThePlainMask()
    {
        for (int imbued : new int[] {11784, 11774, 25276, 26771, 11865, 25177, 26674})
        {
            assertEquals("item " + imbued, 1_000_000, valuation.valueComponent(imbued, 1L, 0L).unitPrice);
        }
    }

    @Test
    public void theAmuletOfTheDamnedWearsLikeBarrowsAndIsWorthItsFullSelf()
    {
        assertEquals(29_000, valuation.valueComponent(12853, 1L, 0L).unitPrice);
        String full = Engine.piece(new Flow(12851, "Amulet of the Damned (full)", -1L, 29_000, -29_000L));
        assertNotNull(full);
        assertEquals("the same piece degrading", full, Engine.piece(new Flow(12853, "Amulet of the Damned", 1L, 29_000, 29_000L)));
    }

    private static ItemComposition composition(int id)
    {
        return (ItemComposition) Proxy.newProxyInstance(ItemComposition.class.getClassLoader(),
            new Class<?>[] {ItemComposition.class}, (proxy, method, args) -> {
                if ("getName".equals(method.getName())) return "Item " + id;
                Class<?> type = method.getReturnType();
                if (type == boolean.class) return "isTradeable".equals(method.getName());
                if (type == int.class) return 0;
                if (type == long.class) return 0L;
                return null;
            });
    }
}
