package com.gpmanager;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HudTrayTest
{
    private static final long T0 = 1_700_000_000_000L;
    private static final long STAY = 5_000L;

    static Transaction receipt(long at, TransactionType type, Flow... flows)
    {
        return new Transaction(at, type, Context.LOOT, "", "Vorkath", true,
            Arrays.asList(flows), Confidence.CONFIRMED, "fixture", null);
    }

    static Flow flow(int id, String name, long quantity, int unit)
    {
        return new Flow(id, name, quantity, unit, quantity * unit, PriceSource.GRAND_EXCHANGE);
    }

    @Test
    public void eatingAndDrinkingNeverRideTheTray()
    {
        HudTray tray = new HudTray();
        tray.booked(receipt(T0, TransactionType.CONSUMPTION, flow(2434, "Prayer potion(4)", -1L, 9_800),
            flow(139, "Prayer potion(3)", 1L, 7_300)), T0, false);
        Transaction wine = receipt(T0 + 600L, TransactionType.CONSUMPTION, flow(1993, "Jug of wine", -1L, 5),
            flow(1935, "Jug", 1L, 1));
        wine.setActionKind(ActionKind.DRINK);
        tray.booked(wine, T0 + 600L, false);
        Transaction pie = receipt(T0 + 1_200L, TransactionType.CONSUMPTION, flow(2327, "Meat pie", -1L, 30),
            flow(2331, "Half a meat pie", 1L, 12));
        pie.setActionKind(ActionKind.EAT);
        tray.booked(pie, T0 + 1_200L, false);
        assertTrue("a sip, a drink or a bite is not a drop", tray.entries().isEmpty());
        assertFalse(tray.hasBooked());
    }

    /** Owner 2026-09-28: Greater Nechryael's death spawns never break its kill streak. */
    @Test
    public void spawnsNeverBreakTheKillStreak()
    {
        // The plugin never passes a spawn on: no header, engage or kill (owner: "Death spawn ×6").
        assertTrue(HudTray.minion("Death spawn"));
        assertTrue(HudTray.minion(" death SPAWN "));
        assertFalse(HudTray.minion("Greater Nechryael"));
        assertFalse(HudTray.minion(null));
        HudTray tray = new HudTray();
        tray.kill("Greater Nechryael", T0, false);
        tray.kill("Greater Nechryael", T0 + 20_000L, false);
        tray.kill("Greater Nechryael", T0 + 40_000L, false);
        assertEquals("G. Nechryael", tray.streak(T0 + 41_000L).getKey());
        assertEquals(3, (int) tray.streak(T0 + 41_000L).getValue());
        tray.kill("Abyssal demon", T0 + 60_000L, false);
        assertEquals("a kill of another NPC starts its own streak", 1, (int) tray.streak(T0 + 61_000L).getValue());
    }

    /** Owner 2026-09-28: a drop reads "Dropped", and a hidden item dropped stays off the tray. */
    @Test
    public void aDropReadsDroppedAndAHiddenDropStaysHidden()
    {
        HudTray tray = new HudTray();
        Transaction drop = new Transaction(T0, TransactionType.CONSUMPTION, Context.GENERIC,
            "Dropped", "Vorkath", true, Arrays.asList(flow(526, "Bones", -1L, 90)),
            Confidence.CONFIRMED, "fixture", null);
        tray.booked(drop, T0, false);
        assertEquals("Dropped", tray.label());
        HudTray.Entry bones = tray.entries().get(0);
        assertEquals(HudTray.State.LOST, bones.state);
        assertTrue("a lost row carries no tag: the Dropped headline and red value say it",
            bones.state.tag.isEmpty());
        LiveContext hideCheap = new LiveContext(false, null, 1_000L, PvpState.NONE, "", false, "", false);
        assertFalse("below the minimum shown value, the dropped Bones stay hidden",
            hideCheap.flowVisible(HudBuilder.flowOf(bones)));
        HudTray looting = new HudTray();
        looting.booked(receipt(T0, TransactionType.LOOT, flow(536, "Dragon bones", 1L, 2_000)), T0, false);
        looting.booked(drop, T0 + 600L, false);
        assertEquals("a drop mid-trip keeps the loot beside it", 2, looting.entries().size());
        assertEquals("Looted", looting.label());
    }

    @Test
    public void aPickupShowsExactlyOnce()
    {
        HudTray tray = new HudTray();
        tray.booked(receipt(T0 + 600L, TransactionType.LOOT, flow(536, "Dragon bones", 2L, 2_000)),
            T0 + 600L, false);
        List<HudTray.Entry> entries = tray.entries();
        assertEquals("the pickup shows exactly once", 1, entries.size());
        assertEquals(HudTray.State.RECEIVED, entries.get(0).state);
        assertEquals(2L, entries.get(0).quantity);
        assertEquals(4_000L, entries.get(0).value);
    }

    @Test
    public void aRowThatGrowsMovesToTheTop()
    {
        HudTray tray = new HudTray();
        tray.booked(receipt(T0, TransactionType.LOOT, flow(536, "Dragon bones", 1L, 2_000)), T0, false);
        tray.booked(receipt(T0 + 1L, TransactionType.LOOT, flow(1753, "Green dragonhide", 1L, 1_500)),
            T0 + 1L, false);
        tray.booked(receipt(T0 + 2L, TransactionType.LOOT, flow(536, "Dragon bones", 1L, 2_000)),
            T0 + 2L, false);

        List<HudTray.Entry> entries = tray.entries();
        // Owner 1.2: the newest loot is always on top, more of an item included.
        assertEquals("the row that last grew leads", "Dragon bones", entries.get(0).name);
        assertEquals("Green dragonhide", entries.get(1).name);
        assertEquals(2L, entries.get(0).quantity);
        assertEquals(4_000L, entries.get(0).value);
    }

    @Test
    public void aClaimedRewardLeadsAndKeepsItsFadeIdentity()
    {
        HudTray tray = new HudTray();
        tray.observed(995, "Coins", 5_000L, 1L, T0, false);
        assertEquals(HudTray.State.PENDING, tray.entries().get(0).state);

        tray.booked(receipt(T0 + 1L, TransactionType.LOOT, flow(1753, "Green dragonhide", 1L, 1_500)),
            T0 + 1L, false);
        tray.booked(receipt(T0 + 2L, TransactionType.GAIN, flow(995, "Coins", 5_000L, 1)),
            T0 + 2L, false);

        List<HudTray.Entry> entries = tray.entries();
        assertEquals(2, entries.size());
        assertEquals("the claim is the newest loot (owner 1.2)", "Coins", entries.get(0).name);
        assertEquals(HudTray.State.CLAIMED, entries.get(0).state);
        assertEquals("the row keeps its first-seen identity", T0, entries.get(0).born);
        assertEquals(5_000L, entries.get(0).quantity);
    }

    @Test
    public void pendingRewardsBecomeClaimed()
    {
        HudTray tray = new HudTray();
        tray.observed(995, "Coins", 5_000L, 1L, T0, false);
        tray.booked(receipt(T0 + 600L, TransactionType.GAIN, flow(995, "Coins", 5_000L, 1)), T0 + 600L, false);
        assertEquals(1, tray.entries().size());
        assertEquals(HudTray.State.CLAIMED, tray.entries().get(0).state);
    }

    @Test
    public void routineUseNeverRidesButRoutineDropsDo()
    {
        HudTray tray = new HudTray();
        tray.booked(receipt(T0, TransactionType.LOOT, flow(560, "Death rune", 50L, 200),
            flow(11212, "Dragon arrow", 20L, 900)), T0, false);
        assertEquals("a rune or ammo pickup is a drop like any other", 2, tray.entries().size());
        assertEquals("Death rune", tray.entries().get(0).name);
        assertEquals("Dragon arrow", tray.entries().get(1).name);
        tray.booked(receipt(T0 + 1L, TransactionType.CONSUMPTION, flow(2434, "Prayer potion(4)", -1L, 9_000)),
            T0 + 1L, false);
        assertEquals("routine use still never rides", 2, tray.entries().size());
        tray.booked(receipt(T0 + 2L, TransactionType.TRANSFER, flow(536, "Dragon bones", 5L, 2_000)),
            T0 + 2L, false);
        assertEquals("a transfer is neutral; it never rides", 2, tray.entries().size());
    }

    @Test
    public void aDropOfTenMillionLightsGoldAndOneLessDoesNot()
    {
        HudTray tray = new HudTray();
        tray.booked(receipt(T0, TransactionType.LOOT, flow(1, "Almost", 1L, 9_999_999)), T0, false);
        assertFalse(tray.entries().get(0).gold);

        tray.booked(receipt(T0 + 1L, TransactionType.LOOT, flow(2, "Twisted bow", 1L, 10_000_000)), T0 + 1L, false);
        assertTrue(tray.entries().get(0).gold);
    }

    @Test
    public void deathsDropsAndDestroysAreLostButOtherItemUseIsNot()
    {
        HudTray tray = new HudTray();
        tray.booked(receipt(T0, TransactionType.CONSUMPTION, flow(8013, "Teleport to house", -1L, 553)), T0, false);
        assertFalse("a teleport tab broken is routine use", !tray.entries.isEmpty());
        Transaction dropped = receipt(T0, TransactionType.CONSUMPTION, flow(536, "Dragon bones", -1L, 2_000));
        dropped.note = "Dropped";
        tray.booked(dropped, T0, false);
        assertEquals(HudTray.State.LOST, tray.entries().get(0).state);
    }




    @Test
    public void anotherNpcStartsItsOwnStreakSoItsLootNeverJoinsTheLastOne()
    {
        HudTray tray = new HudTray();
        tray.kill("Guard", T0, false);
        tray.booked(receipt(T0 + 600L, TransactionType.LOOT, flow(995, "Coins", 30L, 1)), T0 + 600L, false);
        tray.kill("Guard", T0 + 10_000L, false);
        assertEquals("Guard", tray.streak(T0 + 10_000L).getKey());
        assertEquals(2, (int) tray.streak(T0 + 10_000L).getValue());

        tray.kill("Hill Giant", T0 + 20_000L, false);
        assertTrue("the Guard's coins leave with the Guard streak", tray.entries().isEmpty());
        tray.booked(receipt(T0 + 20_600L, TransactionType.LOOT, flow(532, "Big bones", 1L, 300)), T0 + 20_600L, false);
        assertEquals(1, tray.entries().size());
        assertEquals("Hill Giant", tray.streak(T0 + 20_600L).getKey());
        assertEquals(1, (int) tray.streak(T0 + 20_600L).getValue());
    }

    @Test
    public void aNewNpcsFirstKillRefreshesTheTrayToItsOwnStreak()
    {
        HudTray tray = new HudTray();
        tray.kill("Skeleton", T0, false);
        tray.booked(receipt(T0 + 600L, TransactionType.LOOT, flow(526, "Bones", 1L, 50)), T0 + 600L, false);
        assertEquals("a kill streak's heading names its NPC", "Skeleton", tray.label());

        assertFalse("the Skeleton's loot stays while the Guard is only attacked", tray.entries().isEmpty());
        int trip = tray.tripId();
        tray.kill("Guard", T0 + 2_000L, false);
        assertFalse("the Guard's first kill starts its own streak", trip == tray.tripId());
        assertEquals(1, (int) tray.streak(T0 + 2_000L).getValue());
    }

    @Test
    public void anotherNpcsLeftoverLootNeverJoinsTheNewStreak()
    {
        HudTray tray = new HudTray();
        tray.kill("Skeleton", T0, false);
        tray.booked(npcLoot(T0 + 600L, "Guard", flow(995, "Coins", 30L, 1)), T0 + 600L, false);
        assertTrue("the Guard's coins, picked up late, stay off the Skeleton's tray", tray.entries().isEmpty());
        tray.booked(npcLoot(T0 + 1_200L, "Skeleton", flow(526, "Bones", 1L, 50)), T0 + 1_200L, false);
        assertEquals(1, tray.entries().size());
        assertEquals("Bones", tray.entries().get(0).name);
    }

    @Test
    public void theHeadingSaysWhatTheStreakDid()
    {
        HudTray tray = new HudTray();
        tray.booked(receipt(T0, TransactionType.LOOT, flow(536, "Dragon bones", 1L, 2_000)), T0, false);
        assertEquals("Looted", tray.label());

        Transaction chest = receipt(T0 + 1_000L, TransactionType.LOOT, flow(1_631, "Uncut dragonstone", 1L, 10_000));
        chest.setActionKind(ActionKind.DEFERRED_CLAIM);
        tray.booked(chest, T0 + 1_000L, false);
        assertEquals("Claimed", tray.label());

        tray.booked(Tx.of(T0 + 2_000L, null, TransactionType.GAIN, Context.GENERIC, "",
            "Thieving", true, Arrays.asList(flow(995, "Coins", 3L, 1))), T0 + 2_000L, false);
        assertEquals("Stole", tray.label());
    }

    private static Transaction npcLoot(long at, String npc, Flow flow)
    {
        return Tx.of(at, null, TransactionType.LOOT, Context.LOOT, "Loot from " + npc, npc, true,
            Arrays.asList(flow));
    }

    @Test
    public void aKillNeverOpensTheTrayOnlyItsLootDoes()
    {
        HudTray tray = new HudTray();
        tray.kill("Guard", T0, false);
        tray.booked(receipt(T0 + 600L, TransactionType.LOOT, flow(995, "Coins", 30L, 1)), T0 + 600L, false);
        assertFalse("folded after the stay", open(tray, T0 + 600L + STAY));

        tray.kill("Guard", T0 + 20_000L, false);
        assertFalse("the next kill leaves the tray folded", open(tray, T0 + 20_000L));
        tray.booked(receipt(T0 + 21_000L, TransactionType.LOOT, flow(995, "Coins", 25L, 1)), T0 + 21_000L, false);
        assertTrue("its loot opens it", open(tray, T0 + 21_000L));
    }

    @Test
    public void foldingHidesTheTrayButOnlyAQuietGapEndsTheStreak()
    {
        HudTray tray = new HudTray();
        tray.booked(receipt(T0, TransactionType.LOOT, flow(536, "Dragon bones", 1L, 2_000)), T0, false);
        assertTrue(open(tray, T0 + 1_000L));
        assertFalse("folds after the stay", open(tray, T0 + STAY));
        int trip = tray.tripId();
        tray.booked(receipt(T0 + STAY, TransactionType.LOOT, flow(1753, "Green dragonhide", 1L, 1_500)),
            T0 + STAY, false);
        assertEquals("a fold keeps the streak", 2, tray.entries().size());
        assertEquals(trip, tray.tripId());

        tray.booked(receipt(T0 + 400_000L, TransactionType.LOOT, flow(1753, "Green dragonhide", 1L, 1_500)),
            T0 + 400_000L, false);
        assertEquals("a quiet gap starts the next streak", 1, tray.entries().size());
        assertEquals(trip + 1, tray.tripId());
    }

    @Test
    public void theQuietGapFollowsTheConfiguredWindow()
    {
        HudTray tray = new HudTray();
        for (int i = 0; i < 4; i++)
        {
            tray.kill("Chicken", T0 + i * 5_000L, false);
        }
        assertEquals("the default window keeps a minute", 4, (int) tray.streak(T0 + 70_000L).getValue());
        assertNull("then the streak ends", tray.streak(T0 + 80_000L));

        tray.keepMillis(120_000L);
        tray.kill("Chicken", T0 + 81_000L, false);
        assertEquals("the owner's longer window applies", 5,
            (int) tray.streak(T0 + 190_000L).getValue());
        assertNull(tray.streak(T0 + 210_000L));
    }

    @Test
    public void aRunsFirstItemBookedBeforeItsSkillXpStaysInTheRun()
    {
        HudTray tray = new HudTray();
        for (int i = 0; i < 3; i++)
        {
            Transaction bar = Tx.of(T0 + i * 3_000L, null, TransactionType.PROCESSING, Context.PRODUCTION, "Smithing",
                i == 0 ? "General" : "Smithing", true,
                Arrays.asList(flow(2351, "Iron bar", 1L, 187), flow(440, "Iron ore", -1L, 72)));
            tray.booked(bar, T0 + i * 3_000L, false);
        }
        assertEquals("Smelted", tray.label());
        assertEquals(3L, tray.entries().get(0).quantity);
    }

    @Test
    public void theFirstItemAfterAnotherSkillJoinsTheNewRun()
    {
        // From the mine to the furnace: the first bar still carries the old activity.
        HudTray tray = new HudTray();
        tray.booked(Tx.of(T0, null, TransactionType.GAIN, Context.GENERIC, "", "Mining", true,
            Arrays.asList(flow(440, "Iron ore", 1L, 72))), T0, false);
        for (int i = 1; i <= 3; i++)
        {
            tray.booked(Tx.of(T0 + i * 3_000L, null, TransactionType.PROCESSING, Context.PRODUCTION, "Smithing",
                i == 1 ? "Mining" : "Smithing", true,
                Arrays.asList(flow(2351, "Iron bar", 1L, 187), flow(440, "Iron ore", -1L, 72))),
                T0 + i * 3_000L, false);
        }
        assertEquals("Smelted", tray.label());
        assertEquals(1, tray.entries().size());
        assertEquals(3L, tray.entries().get(0).quantity);
    }

    @Test
    public void skillXpNamesTheFirstItemsWork()
    {
        // A stall, a trap, a cooked fish: the XP arrives with the item, before any activity name.
        String[][] runs = {{"Thieving", "Stole", "Cake"}, {"Hunter", "Caught", "Chinchompa"},
            {"Fishing", "Fished", "Raw lobster"}, {"Smithing", "Smithed", "Iron dagger"}};
        for (String[] run : runs)
        {
            HudTray tray = new HudTray();
            for (int i = 0; i < 3; i++)
            {
                long at = T0 + i * 4_000L;
                tray.xp(run[0], at - 1_200L);
                tray.booked(Tx.of(at, null, TransactionType.GAIN, Context.GENERIC, "", i == 0 ? "General" : run[0], true,
                    Arrays.asList(flow(1891, run[2], 1L, 50))), at, false);
            }
            assertEquals(run[0], run[1], tray.label());
            assertEquals(run[0], 3L, tray.entries().get(0).quantity);
        }
    }

    @Test
    public void aDeathReadsLostAndATradeKeepsNoSkillVerb()
    {
        HudTray tray = new HudTray();
        tray.booked(receipt(T0, TransactionType.PK_DEATH_LOSS, flow(4151, "Abyssal whip", -1L, 1_500_000)), T0, false);
        assertEquals("Lost", tray.label());

        HudTray market = new HudTray();
        market.xp("Smithing", T0);
        market.booked(Tx.of(T0 + 600L, null, TransactionType.TRADE, Context.MARKET, "Grand Exchange", "Market", true,
            Arrays.asList(flow(1359, "Rune axe", 1L, 7_000))), T0 + 600L, false);
        assertEquals("a trade on an empty tray reads Traded, never a skill verb", "Traded", market.label());
    }

    @Test
    public void combatXpNeverNamesTheTray()
    {
        HudTray tray = new HudTray();
        tray.xp("Strength", T0);
        tray.xp("Magic", T0);
        tray.booked(Tx.of(T0 + 600L, null, TransactionType.GAIN, Context.GENERIC, "", "General", true,
            Arrays.asList(flow(536, "Dragon bones", 1L, 2_000))), T0 + 600L, false);
        assertEquals("Looted", tray.label());
    }

    @Test
    public void anotherKindOfWorkStartsANewStreakUnlessRowsKeepForTheSession()
    {
        HudTray streak = new HudTray();
        streak.booked(receipt(T0, TransactionType.LOOT, flow(536, "Dragon bones", 1L, 2_000)), T0, false);
        Transaction ore = Tx.of(T0 + 1_000L, null, TransactionType.GAIN, Context.GENERIC,
            "", "Mining", true, Arrays.asList(flow(436, "Copper ore", 1L, 50)));
        streak.booked(ore, T0 + 1_000L, false);
        assertEquals(1, streak.entries().size());
        assertEquals("Mined", streak.label());

        HudTray session = new HudTray();
        session.kill("Guard", T0, true);
        session.booked(receipt(T0, TransactionType.LOOT, flow(536, "Dragon bones", 1L, 2_000)), T0, true);
        session.kill("Man", T0 + 1_000L, true);
        session.booked(receipt(T0 + 1_000L, TransactionType.LOOT, flow(1753, "Green dragonhide", 1L, 1_500)),
            T0 + 1_000L, true);
        assertEquals("the whole session keeps every row", 2, session.entries().size());
    }

    /** Owner 1.1: switching back inside the quiet gap makes one mixed streak of both. */
    @Test
    public void switchingBackToAnNpcMergesBothStreaksIntoOne()
    {
        HudTray tray = new HudTray();
        tray.kill("Guard", T0, false);
        tray.booked(npcLoot(T0 + 600L, "Guard", flow(995, "Coins", 30L, 1)), T0 + 600L, false);
        int guardTrip = tray.tripId();
        tray.kill("Man", T0 + 5_000L, false);
        assertEquals("Man", tray.label());
        tray.booked(npcLoot(T0 + 5_600L, "Man", flow(526, "Bones", 1L, 50)), T0 + 5_600L, false);
        assertEquals(1, tray.entries().size());

        tray.kill("Guard", T0 + 10_000L, false);
        assertEquals("Guard & Man", tray.label());
        assertEquals("both streaks' kills", 3, (int) tray.streak(T0 + 10_000L).getValue());
        assertEquals("both streaks' rows", 2, tray.entries().size());
        assertEquals("the merged streak keeps the first one's identity", guardTrip, tray.tripId());
        tray.booked(npcLoot(T0 + 10_600L, "Man", flow(995, "Coins", 5L, 1)), T0 + 10_600L, false);
        assertEquals("either NPC's loot joins it", 35L, tray.entries().stream()
            .filter(entry -> entry.itemId == 995).findFirst().get().quantity);

        tray.kill("Cow", T0 + 15_000L, false);
        assertEquals("Cow", tray.label());
        tray.kill("Man", T0 + 20_000L, false);
        assertEquals("Guard, Man +1", tray.label());
        assertEquals(5, (int) tray.streak(T0 + 20_000L).getValue());
    }

    @Test
    public void switchingBackAfterTheQuietGapStartsAfresh()
    {
        HudTray tray = new HudTray();
        tray.kill("Guard", T0, false);
        tray.kill("Man", T0 + 5_000L, false);
        tray.kill("Guard", T0 + 70_000L, false);
        assertEquals("Guard", tray.label());
        assertEquals(1, (int) tray.streak(T0 + 70_000L).getValue());
    }

    /** The tray is open while an entry changed within the stay (HudBuilder also applies the loot filter). */
    @Test
    public void aGrandExchangeBuyReadsTraded()
    {
        // Owner 2026-10-09: a collected GE buy is no loot.
        HudTray tray = new HudTray();
        tray.booked(Tx.of(1_000L, TransactionType.TRADE, Context.MARKET, "Bought", true, java.util.List.of(
            new Flow(22372, "Bryophyta's essence", 1L, 4_940_000, 4_940_000L),
            new Flow(995, "Coins", -4_940_000L, 1, -4_940_000L))), 1_000L, false);
        assertEquals("Traded", tray.label());
        assertEquals("the item bought, at what it cost", 4_940_000L, tray.entries().get(0).value);

        // A sale rides as the item sold with the coins it brought; the coins get no row of their own.
        HudTray sale = new HudTray();
        sale.booked(Tx.of(2_000L, TransactionType.TRADE, Context.MARKET, "Sold", true, java.util.List.of(
            new Flow(21820, "Revenant ether", -1_927L, 174, -335_298L),
            new Flow(995, "Coins", 329_517L, 1, 329_517L))), 2_000L, false);
        assertEquals(1, sale.entries().size());
        assertEquals("Revenant ether", sale.entries().get(0).name);
        assertEquals(1_927L, sale.entries().get(0).quantity);
        assertEquals(329_517L, sale.entries().get(0).value);
        assertEquals("Traded", sale.label());
    }

    /** Owner 2026-10-12: a non-coin currency shop trade is its own word; coins and trades stay Traded. */
    @Test
    public void aShopExchangeReadsExchanged()
    {
        HudTray tray = new HudTray();
        tray.booked(Tx.of(1_000L, TransactionType.TRADE, Context.MARKET, "buy 50 Death rune", true,
            java.util.List.of(
                new Flow(560, "Death rune", 50L, 201, 10_050L),
                new Flow(6529, "Tokkul", -14_550L, 9, -130_950L))), 1_000L, false);
        assertEquals("Exchanged", tray.label());

        HudTray coins = new HudTray();
        coins.booked(Tx.of(2_000L, TransactionType.TRADE, Context.MARKET, "buy 1 Impling jar", true,
            java.util.List.of(
                new Flow(11260, "Impling jar", 1L, 290, 290L),
                new Flow(995, "Coins", -290L, 1, -290L))), 2_000L, false);
        assertEquals("a coin shop stays Traded", "Traded", coins.label());
    }

    private static boolean open(HudTray tray, long now)
    {
        return tray.entries().stream().anyMatch(entry -> now - entry.touched < STAY);
    }
}
