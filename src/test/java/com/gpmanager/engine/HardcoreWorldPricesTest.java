package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.EnumSet;
import java.util.Map;
import net.runelite.api.ItemComposition;
import net.runelite.api.WorldType;
import net.runelite.client.config.ConfigItem;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Owner 2026-11-07: a Hardcore Worlds character only ever plays Hardcore Worlds, so GP Manager
 * tracks it with that economy's own prices: an item's alch value until the player trades it on the
 * Hardcore GE, then the price that fill paid per item. Main worlds never read those prices, and
 * Deadman, Leagues and the other special worlds stay paused.
 */
public class HardcoreWorldPricesTest
{
    static final int WHIP = 4151, TRINKET = 1234, MAIN_QUOTE = 2_000_000;

    static ItemComposition definition(int id)
    {
        return (ItemComposition) Proxy.newProxyInstance(ItemComposition.class.getClassLoader(),
            new Class<?>[]{ItemComposition.class}, (proxy, method, args) -> {
                switch (method.getName())
                {
                    case "getName": return id == WHIP ? "Abyssal whip" : "Trinket";
                    case "getId": return id;
                    case "getHaPrice": return id == WHIP ? 72_000 : 0;
                    case "isTradeable": return true;
                    default: return method.getReturnType() == boolean.class ? false
                        : method.getReturnType() == int.class ? 0 : null;
                }
            });
    }

    static Valuation valuation(Valuation.Economy economy)
    {
        return new Valuation(new GpManagerConfig() {}, HardcoreWorldPricesTest::definition,
            (id, active) -> MAIN_QUOTE, id -> false, economy);
    }

    static Flow value(Valuation valuation, int id)
    {
        return valuation.value(Map.of(id, 1L), 1_000L).get(0);
    }

    @Test
    public void hardcoreWorldsAreTheirOwnEconomyWhileOtherModesStayPaused()
    {
        assertEquals(Valuation.Economy.HARDCORE,
            Valuation.economyFor(EnumSet.of(WorldType.MEMBERS, WorldType.HARDCORE_MAIN)));
        for (WorldType paused : new WorldType[] {WorldType.DEADMAN, WorldType.SEASONAL, WorldType.QUEST_SPEEDRUNNING,
            WorldType.BETA_WORLD, WorldType.TOURNAMENT_WORLD, WorldType.NOSAVE_MODE})
        {
            assertEquals(paused.name(), Valuation.Economy.UNSUPPORTED_SPECIAL,
                Valuation.economyFor(EnumSet.of(WorldType.MEMBERS, paused)));
        }
        assertEquals(Valuation.Economy.NORMAL, Valuation.economyFor(EnumSet.of(WorldType.MEMBERS)));
    }

    @Test
    public void anItemNeverTradedOnAHardcoreWorldTakesItsAlchValueNotTheMainGamePrice()
    {
        Flow whip = value(valuation(Valuation.Economy.HARDCORE), WHIP);
        assertEquals(72_000L, whip.unitPrice);
        assertEquals(PriceSource.HIGH_ALCHEMY, whip.source());
        Flow trinket = value(valuation(Valuation.Economy.HARDCORE), TRINKET);
        assertEquals("no alch value and no trade: worth nothing", 0L, trinket.unitPrice);
        assertEquals(PriceSource.NO_VALUE, trinket.source());
    }

    @Test
    public void aHardcoreGeFillTeachesThatItemsPriceForLaterDrops()
    {
        Valuation valuation = valuation(Valuation.Economy.HARDCORE);
        assertTrue(valuation.learn(WHIP, 5_000_000L, 2));
        Flow whip = value(valuation, WHIP);
        assertEquals("the fill's price per item", 2_500_000L, whip.unitPrice);
        assertEquals(PriceSource.GRAND_EXCHANGE, whip.source());
        assertTrue(valuation.learn(WHIP, 2_400_000L, 1));
        assertEquals("the latest fill wins", 2_400_000L, value(valuation, WHIP).unitPrice);
    }

    @Test
    public void mainWorldsNeverReadHardcorePricesAndOnlyHardcoreFillsTeach()
    {
        Valuation valuation = valuation(Valuation.Economy.HARDCORE);
        valuation.learn(WHIP, 5_000_000L, 2);
        valuation.setEconomy(Valuation.Economy.NORMAL);
        assertEquals((long) MAIN_QUOTE, value(valuation, WHIP).unitPrice);
        assertFalse("a main-world fill teaches nothing", valuation.learn(TRINKET, 900L, 1));
        valuation.setEconomy(Valuation.Economy.UNSUPPORTED_SPECIAL);
        assertFalse("a Deadman fill teaches nothing", valuation.learn(TRINKET, 900L, 1));
        valuation.setEconomy(Valuation.Economy.HARDCORE);
        assertFalse("an empty slot teaches nothing", valuation.learn(TRINKET, 0L, 0));
        assertEquals(Map.of(WHIP, 2_500_000L), valuation.learned);
    }

    /** The same measured death wipe: deleted (a counted loss) on a Hardcore World, gravestone storage elsewhere. */
    @Test
    public void aHardcoreDeathBooksWhatTheGravestoneDeletedAsALoss()
    {
        for (Valuation.Economy economy : new Valuation.Economy[] {Valuation.Economy.HARDCORE, Valuation.Economy.NORMAL})
        {
            Valuation valuation = valuation(economy);
            Engine engine = new Engine(valuation, new TransactionClassifier(), new GpManagerConfig() {});
            engine.ensureSession(1_000L);
            Flow whip = valuation.value(Map.of(WHIP, -1L), 1_000L).get(0);
            engine.death.onPvmDeath(Map.of(WHIP, 1L), valuation.hardcore());
            Transaction row = engine.deathWipeTransfer(new java.util.ArrayList<>(java.util.List.of(whip)), Map.of(WHIP, 1L), 2_000L);
            boolean hardcore = economy == Valuation.Economy.HARDCORE;
            assertEquals(economy.name(), hardcore, row.counts());
            if (hardcore)
            {
                assertEquals("Death: PvE", row.note());
                assertEquals(GameData.msg("hardcore-deleted"), row.explanation());
                assertEquals("the whip at its alch value is gone", -72_000L, engine.metrics(3_000L).net);
            }
            else
            {
                assertEquals("main-world gravestone items stay owned", 0L, engine.metrics(3_000L).net);
            }
        }
    }

    @Test
    public void learnedPricesRoundTripThroughTheHiddenSetting() throws Exception
    {
        Valuation valuation = valuation(Valuation.Economy.HARDCORE);
        valuation.learn(WHIP, 5_000_000L, 2);
        valuation.learn(TRINKET, 900L, 3);
        assertEquals(valuation.learned, ItemRuleParser.parsePriceOverrides(valuation.learned()));
        ConfigItem item = GpManagerConfig.class.getMethod("hardcorePrices").getAnnotation(ConfigItem.class);
        assertEquals("the key the plugin writes", "hardcorePrices", item.keyName());
        assertTrue(item.hidden());
    }
}
