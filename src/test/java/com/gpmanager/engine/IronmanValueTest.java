package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.Map;
import net.runelite.api.ItemComposition;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * Owner 2026-10-09 (1.3.0): an ironman who turns on "value at high alch" sees items at their
 * alchemy price, the coins they can actually make; everyone else, and coins and their own price
 * overrides, are unchanged.
 */
public class IronmanValueTest
{
    private static final int WHIP = 4151;
    private final Valuation valuation = ModelProbe.valuation(new GpManagerConfig() { }, IronmanValueTest::composition, id -> id == WHIP ? 1_500_000 : 0);

    @Test
    public void highAlchOnlyWhenChosen()
    {
        assertEquals("the GE price by default", 1_500_000, valuation.valueComponent(WHIP, 1L, 0L).unitPrice);
        valuation.alch = true;
        Flow whip = valuation.valueComponent(WHIP, 1L, 0L);
        assertEquals("its high alchemy price", 72_000, whip.unitPrice);
        assertSame(PriceSource.HIGH_ALCHEMY, whip.getPriceSource());
        assertEquals("coins stay coins", 1, valuation.valueComponent(995, 1L, 0L).unitPrice);
        assertEquals("your own override still wins", 2_000_000,
            valuation.valueOne(WHIP, 1L, Map.of(WHIP, 2_000_000), 0L).unitPrice);
    }

    private static ItemComposition composition(int id)
    {
        return (ItemComposition) Proxy.newProxyInstance(ItemComposition.class.getClassLoader(),
            new Class<?>[] {ItemComposition.class}, (proxy, method, args) -> {
                if ("getName".equals(method.getName())) return id == WHIP ? "Abyssal whip" : "Coins";
                if ("getHaPrice".equals(method.getName())) return id == WHIP ? 72_000 : 0;
                Class<?> type = method.getReturnType();
                if (type == boolean.class) return "isTradeable".equals(method.getName());
                if (type == int.class) return 0;
                if (type == long.class) return 0L;
                return null;
            });
    }
}
