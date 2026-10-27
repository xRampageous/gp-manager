package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.ItemComposition;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Owner 1.3.4: a dose variant RuneLite cannot quote is priced from its family's full quote at the
 * same rate per dose (the GP-Per-Hour fractional model), so a burning-amulet teleport books a
 * fifth of the amulet instead of the full price or an unknown row.
 */
public class DoseFamilyValueTest
{
    private static final int BURNING_5 = 21166;
    private static final int BURNING_4 = 21169;
    private static final int BURNING_3 = 21171;
    private static final int BURNING_2 = 21173;
    private static final int BURNING_1 = 21175;

    private static final Map<Integer, String> NAMES = Map.of(
        BURNING_5, "Burning amulet(5)",
        BURNING_4, "Burning amulet(4)",
        BURNING_3, "Burning amulet(3)",
        BURNING_2, "Burning amulet(2)",
        BURNING_1, "Burning amulet(1)");

    @Test
    public void aPartialDoseIsPricedFromItsFamilyFullQuote()
    {
        Valuation service = service(new GpManagerConfig() { }, Map.of(BURNING_5, 2_231));

        assertEquals("the full amulet keeps its own quote", 2_231, one(service, BURNING_5).unitPrice);
        assertEquals(1_784, one(service, BURNING_4).unitPrice);
        assertEquals(1_338, one(service, BURNING_3).unitPrice);
        assertEquals(892, one(service, BURNING_2).unitPrice);
        assertEquals(446, one(service, BURNING_1).unitPrice);
        assertEquals(PriceSource.GRAND_EXCHANGE, one(service, BURNING_4).source());

        Map<Integer, Long> swap = new HashMap<>();
        swap.put(BURNING_3, -1L);
        swap.put(BURNING_2, 1L);
        long net = 0L;
        for (Flow flow : service.value(swap, 1_000L)) net += flow.value;
        assertEquals("one teleport is a fifth of the amulet", -446L, net);
    }

    @Test
    public void aManualOverrideAnywhereInTheFamilyIsAuthoritative()
    {
        GpManagerConfig full = new GpManagerConfig()
        {
            @Override
            public String manualPriceOverrides()
            {
                return "21166=2500";
            }
        };
        Valuation fromFull = service(full, Map.of());
        assertEquals(2_000, one(fromFull, BURNING_4).unitPrice);
        assertEquals(PriceSource.MANUAL_OVERRIDE, one(fromFull, BURNING_4).source());

        GpManagerConfig oneDose = new GpManagerConfig()
        {
            @Override
            public String manualPriceOverrides()
            {
                return "21175=500";
            }
        };
        Valuation fromOne = service(oneDose, Map.of(BURNING_5, 2_231));
        assertEquals("an override outranks the family quote", 2_000,
            one(fromOne, BURNING_4).unitPrice);
        assertEquals(PriceSource.MANUAL_OVERRIDE, one(fromOne, BURNING_4).source());
    }

    @Test
    public void withoutAnyFamilyQuoteNothingIsInvented()
    {
        Valuation service = service(new GpManagerConfig() { }, Map.of());
        assertEquals(0, one(service, BURNING_4).unitPrice);
        assertEquals(PriceSource.UNPRICED, one(service, BURNING_4).source());
        assertEquals(PriceSource.UNPRICED, one(service, 999_999).source());
    }

    static Valuation service(GpManagerConfig config, Map<Integer, Integer> prices)
    {
        return ModelProbe.valuation(config,
            id -> composition(NAMES.getOrDefault(id, "Item " + id)),
            id -> prices.getOrDefault(id, 0));
    }

    private static ItemComposition composition(String name)
    {
        return (ItemComposition) Proxy.newProxyInstance(ItemComposition.class.getClassLoader(),
            new Class<?>[] {ItemComposition.class}, (proxy, method, args) ->
            {
                if ("getName".equals(method.getName())) return name;
                // Potions and amulets trade on the Grand Exchange.
                if (method.getReturnType() == boolean.class) return "isTradeable".equals(method.getName());
                if (method.getReturnType() == int.class) return 0;
                if (method.getReturnType() == long.class) return 0L;
                return null;
            });
    }

    private static Flow one(Valuation service, int itemId)
    {
        List<Flow> flows = service.value(Map.of(itemId, 1L), 1_000L);
        assertNotNull(flows);
        return flows.stream().filter(flow -> flow.item == itemId).findFirst().orElseThrow();
    }
}
