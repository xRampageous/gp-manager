package com.gpmanager;

import java.util.EnumSet;
import java.util.Set;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Checks that moved from code into tables (2026-11-03 size pass) answer exactly as the code did:
 * the coin pouches, the Karamja gloves, the safe PvP arenas and the pending Market lifecycles; and the
 * food prefixes, sidebar tabs and sprite stacks, with the shared text-fit search (2026-11-03 trims).
 */
public class MovedTablesTest
{
    @Test
    public void everyTableIdIsItsRuneLiteConstant() throws Exception
    {
        for (String table : new String[] {"coin_pouches", "karamja_gloves"})
        {
            for (String[] row : GameData.rows(table))
            {
                assertEquals(table + " " + row[1], Integer.parseInt(row[0]), ItemID.class.getField(row[1]).getInt(null));
            }
        }
    }

    @Test
    public void coinPouchesAreTheRangeAndTheFourLaterPouches()
    {
        for (int id = ItemID.PICKPOCKET_COIN_POUCH_CITIZEN - 2; id <= ItemID.PICKPOCKET_COIN_POUCH_ELF + 2; id++)
        {
            boolean old = id >= ItemID.PICKPOCKET_COIN_POUCH_CITIZEN && id <= ItemID.PICKPOCKET_COIN_POUCH_ELF;
            assertEquals(String.valueOf(id), old, DeferredClaims.POUCHES.contains(id));
        }
        for (int id : new int[] {ItemID.PICKPOCKET_COIN_POUCH_VYRE, ItemID.PICKPOCKET_COIN_POUCH_VARLAMORE_WEALTHY,
            ItemID.PICKPOCKET_COIN_POUCH_PIRATE, ItemID.GOTR_INTRICATE_POUCH, ItemID.SOUL_WARS_SPOILS})
        {
            assertEquals(String.valueOf(id), true, DeferredClaims.of(id));
        }
        assertEquals(23, DeferredClaims.POUCHES.size());
    }

    @Test
    public void karamjaGlovesAreTheFourTiers()
    {
        assertEquals(Set.of(ItemID.ATJUN_GLOVES_EASY, ItemID.ATJUN_GLOVES_MED, ItemID.ATJUN_GLOVES_HARD,
            ItemID.ATJUN_GLOVES_ELITE), GpManagerPlugin.KARAMJA_GLOVES);
    }

    @Test
    public void safeArenasAreTheFiveRegionsAndLms()
    {
        for (int region : new int[] {9520, 8237, 8493, 8749, 9552, 13658})
        {
            assertEquals(String.valueOf(region), true, PvpContext.isSafeArenaRegion(region));
        }
        for (int region : new int[] {0, -1, 7512, 10588, 12850})
        {
            assertEquals(String.valueOf(region), false, PvpContext.isSafeArenaRegion(region));
        }
    }

    @Test
    public void pendingLifecyclesAreTheFourBeforeRealized()
    {
        var pending = EnumSet.of(MarketRows.Lifecycle.PENDING, MarketRows.Lifecycle.PARTIALLY_EXECUTED,
            MarketRows.Lifecycle.EXECUTED_UNSETTLED, MarketRows.Lifecycle.PARTIALLY_REALIZED);
        for (var lifecycle : MarketRows.Lifecycle.values())
        {
            assertEquals(lifecycle.name(), pending.contains(lifecycle), LedgerData.isPending(lifecycle));
        }
        assertEquals(false, LedgerData.isPending(null));
    }

    @Test
    public void foodPrefixesTabsAndStacksKeepTheirValuesAndOrder()
    {
        var prefixes = new java.util.ArrayList<String>();
        GameData.cached("food_prefixes").forEach(row -> prefixes.add(row[0]));
        assertEquals(java.util.List.of("whole ", "full ", "half ", "1/2 "), prefixes);
        var tabs = new java.util.ArrayList<String>();
        Shell.TABS.forEach(tab -> tabs.add(tab[0] + "=" + tab[1]));
        assertEquals(java.util.List.of(Shell.LIVE + "=Live", Shell.LEDGER + "=Ledger", Shell.GRINDS + "=Grinds",
            Shell.RAIDS + "=Raids"), tabs);
        assertEquals(java.util.Arrays.toString(new int[] {10000, 1000, 250, 100, 25, 5, 4, 3, 2}),
            java.util.Arrays.toString(HudBuilder.STACKS));
    }

    @Test
    public void theSharedTextFitFindsTheLongestPrefixWithinBudget()
    {
        assertEquals(0, Kit.fittingPrefix("abcdef", 0, String::length));
        assertEquals(3, Kit.fittingPrefix("abcdef", 3, String::length));
        assertEquals(6, Kit.fittingPrefix("abcdef", 99, String::length));
        assertEquals(0, Kit.fittingPrefix("", 5, String::length));
    }

    @Test
    public void everyMarketLifecycleKeepsItsWordingForBothSides()
    {
        String[][] expected = {
            {"PENDING", "Selling", "Buying", "×12345", "×12345", "Selling", "Buying"},
            {"PARTIALLY_EXECUTED", "Pending", "Pending", "Part sold 6789/12345", "Part bought 6789/12345",
                "Part sold 6,789/12,345 · Pending", "Part bought 6,789/12,345 · Pending"},
            {"EXECUTED_UNSETTLED", "Sold", "Bought", "Sold · collect", "Bought · collect",
                "Sold 6,789/12,345 · Awaiting collection", "Bought 6,789/12,345 · Awaiting collection"},
            {"PARTIALLY_REALIZED", "Pending", "Pending", "Partially realized", "Partially realized", "Pending", "Pending"},
            {"REALIZED", "Sold", "Bought", "Detail unavailable", "Detail unavailable", "Sold", "Bought"},
            {"CANCELLED_RETURNED", "Canceled", "Canceled", "Returned", "Returned", "Returned · Canceled", "Returned · Canceled"},
            {"AMBIGUOUS", "Review", "Review", "Review", "Review", "Needs review", "Needs review"},
            {"UNAVAILABLE", "Pending", "Pending", "Detail unavailable", "Detail unavailable", "Unavailable", "Unavailable"}
        };
        assertEquals(MarketRows.Lifecycle.values().length, expected.length);
        assertEquals("Pending", MarketText.stateWord(null));
        for (String[] words : expected)
            for (int side = 0; side < 2; side++)
            {
                var row = new MarketRows.Row(new GeOffer(), null, null);
                row.side = side == 0 ? GeOffer.Side.SELL : GeOffer.Side.BUY;
                row.lifecycle = MarketRows.Lifecycle.valueOf(words[0]);
                row.offeredQty = 12345;
                row.filledQty = 6789;
                assertEquals(words[0], words[1 + side], MarketText.stateWord(row));
                assertEquals(words[0], words[3 + side], Semantics.lifecycleLabel(row));
                assertEquals(words[0], words[5 + side], MarketText.orderStateText(row));
                String why = words[0].equals("CANCELLED_RETURNED") ? "canceled-returned"
                    : words[0].equals("AMBIGUOUS") ? "lifecycle-review"
                    : words[0].equals("EXECUTED_UNSETTLED") ? "filled-quantity" : "collected-settlement";
                assertEquals(words[0], GameData.msg(why), MarketText.orderWhy(row));
            }
    }

    @Test
    public void categoryDestinationsAndTonesKeepTheirMappings()
    {
        String[][] expected = {{"GAIN", "GAINS", "GAIN"}, {"SUPPLY", "COSTS_SUPPLIES", "SUPPLY"},
            {"LOSS", "COSTS_LOSS", "LOSS"}, {"MARKET", "MARKET", "MARKET"}, {"AUDIT", "AUDIT", "DIM"}};
        assertEquals(Contribution.Category.values().length, expected.length);
        for (String[] mapping : expected)
        {
            var category = Contribution.Category.valueOf(mapping[0]);
            assertEquals(mapping[0], mapping[1], Semantics.tableFor(category).name());
            assertEquals(mapping[0], mapping[2], Kit.Tone.of(category).name());
        }
        assertEquals(Kit.Tone.DIM, Kit.Tone.of(null));
    }

    @Test
    public void enumLabelsRemainExactlyTheSame()
    {
        assertEquals("[manual, currency proxy, RuneLite market, face value, high alchemy, Claim on open, no value, unpriced, unknown]",
            java.util.Arrays.toString(PriceSource.values()));
        assertEquals("[Current Grind, Runs today, Recent Grind]", java.util.Arrays.toString(LedgerData.Scope.values()));
        assertEquals("[All, Supplies, Items, Charges]", java.util.Arrays.toString(LedgerData.CostView.values()));
        assertEquals("[Review, Corrected]", java.util.Arrays.toString(LedgerData.Audit.values()));
        var labels = new java.util.ArrayList<String>();
        for (var table : Semantics.Table.values()) labels.add(table.label);
        assertEquals(java.util.List.of("GAINS", "SUPPLY", "LOSS", "TRADE", "Review", "DEATH", "KILLS"), labels);
        for (var state : HudTray.State.values()) assertEquals("", state.tag);
    }

    @Test
    public void fixedContextExplanationsKeepTheirTextForEveryTypeAndCountedState()
    {
        var expected = java.util.Map.of(Context.TRANSFER, "excluded-because", Context.LOOT, "matched-item-ids",
            Context.PK_LOOT, "matched-stock", Context.PK_DEATH, "inventory-equipment");
        Engine engine = ReviewFixesTest.engine();
        expected.forEach((context, key) -> {
            for (var type : TransactionType.values())
                for (boolean counted : new boolean[] {false, true})
                    assertEquals(context.name(), GameData.msg(key), engine.explain(context, type, counted));
        });
    }
}
