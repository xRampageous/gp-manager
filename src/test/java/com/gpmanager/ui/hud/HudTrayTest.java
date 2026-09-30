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

    static Ac receipt(long at, Ai type, Ab... flows)
    {
        return new Ac(at, null, type, Aj.LOOT, "", "Vorkath", true,
            Arrays.asList(flows), Bd.CONFIRMED, "fixture", null);
    }

    static Ab flow(int id, String name, long quantity, int unit)
    {
        return new Ab(id, name, quantity, unit, quantity * unit, Av.GRAND_EXCHANGE);
    }

    @Test
    public void eatingAndDrinkingNeverRideTheTray()
    {
        HudTray tray = new HudTray();
        tray.booked(receipt(T0, Ai.CONSUMPTION, flow(2434, "Prayer potion(4)", -1L, 9_800),
            flow(139, "Prayer potion(3)", 1L, 7_300)), T0, false);
        Ac wine = receipt(T0 + 600L, Ai.CONSUMPTION, flow(1993, "Jug of wine", -1L, 5),
            flow(1935, "Jug", 1L, 1));
        wine.setActionKind(Au.DRINK);
        tray.booked(wine, T0 + 600L, false);
        Ac pie = receipt(T0 + 1_200L, Ai.CONSUMPTION, flow(2327, "Meat pie", -1L, 30),
            flow(2331, "Half a meat pie", 1L, 12));
        pie.setActionKind(Au.EAT);
        tray.booked(pie, T0 + 1_200L, false);
        assertTrue("a sip, a drink or a bite is not a drop", tray.entries().isEmpty());
        assertFalse(tray.aup());
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
        tray.engage("Greater Nechryael", false);
        tray.kill("Greater Nechryael", T0, false);
        tray.kill("Greater Nechryael", T0 + 20_000L, false);
        tray.kill("Greater Nechryael", T0 + 40_000L, false);
        assertEquals("Greater Nechryael", tray.streak(T0 + 41_000L).getKey());
        assertEquals(3, (int) tray.streak(T0 + 41_000L).getValue());
        tray.kill("Abyssal demon", T0 + 60_000L, false);
        assertEquals("a kill of another NPC starts its own streak", 1, (int) tray.streak(T0 + 61_000L).getValue());
    }

    /** Owner 2026-09-28: a drop reads "Dropped", and a hidden item dropped stays off the tray. */
    @Test
    public void aDropReadsDroppedAndAHiddenDropStaysHidden()
    {
        HudTray tray = new HudTray();
        Ac drop = new Ac(T0, null, Ai.CONSUMPTION, Aj.GENERIC,
            "Dropped", "Vorkath", true, Arrays.asList(flow(526, "Bones", -1L, 90)),
            Bd.CONFIRMED, "fixture", null);
        tray.booked(drop, T0, false);
        assertEquals("Dropped", tray.label());
        HudTray.Entry bones = tray.entries().get(0);
        assertEquals(HudTray.State.LOST, bones.state);
        Dz hideCheap = new Dz(false, null, 1_000L, Bo.NONE, "", false, "");
        assertFalse("below the minimum shown value, the dropped Bones stay hidden",
            hideCheap.td(Cp.flowOf(bones)));
        HudTray looting = new HudTray();
        looting.booked(receipt(T0, Ai.LOOT, flow(536, "Dragon bones", 1L, 2_000)), T0, false);
        looting.booked(drop, T0 + 600L, false);
        assertEquals("a drop mid-trip keeps the loot beside it", 2, looting.entries().size());
        assertEquals("Looted", looting.label());
    }

    @Test
    public void aPickupShowsExactlyOnce()
    {
        HudTray tray = new HudTray();
        tray.booked(receipt(T0 + 600L, Ai.LOOT, flow(536, "Dragon bones", 2L, 2_000)),
            T0 + 600L, false);
        List<HudTray.Entry> entries = tray.entries();
        assertEquals("the pickup shows exactly once", 1, entries.size());
        assertEquals(HudTray.State.RECEIVED, entries.get(0).state);
        assertEquals(2L, entries.get(0).quantity);
        assertEquals(4_000L, entries.get(0).value);
    }

    @Test
    public void rowsKeepTheirPlaceWhileQuantitiesGrow()
    {
        HudTray tray = new HudTray();
        tray.booked(receipt(T0, Ai.LOOT, flow(536, "Dragon bones", 1L, 2_000)), T0, false);
        tray.booked(receipt(T0 + 1L, Ai.LOOT, flow(1753, "Green dragonhide", 1L, 1_500)),
            T0 + 1L, false);
        tray.booked(receipt(T0 + 2L, Ai.LOOT, flow(536, "Dragon bones", 1L, 2_000)),
            T0 + 2L, false);

        List<HudTray.Entry> entries = tray.entries();
        assertEquals("the newest distinct item stays on top", "Green dragonhide", entries.get(0).name);
        assertEquals("an update grows its row in place", "Dragon bones", entries.get(1).name);
        assertEquals(2L, entries.get(1).quantity);
        assertEquals(4_000L, entries.get(1).value);
    }

    @Test
    public void aClaimedRewardKeepsItsRowPlaceAndFadeIdentity()
    {
        HudTray tray = new HudTray();
        tray.observed(995, "Coins", 5_000L, 1L, T0, false);
        assertEquals(HudTray.State.PENDING, tray.entries().get(0).state);

        tray.booked(receipt(T0 + 1L, Ai.LOOT, flow(1753, "Green dragonhide", 1L, 1_500)),
            T0 + 1L, false);
        tray.booked(receipt(T0 + 2L, Ai.GAIN, flow(995, "Coins", 5_000L, 1)),
            T0 + 2L, false);

        List<HudTray.Entry> entries = tray.entries();
        assertEquals(2, entries.size());
        assertEquals("the claim does not jump above newer loot", "Green dragonhide", entries.get(0).name);
        assertEquals(HudTray.State.CLAIMED, entries.get(1).state);
        assertEquals("the row keeps its first-seen identity", T0, entries.get(1).born);
        assertEquals(5_000L, entries.get(1).quantity);
    }

    @Test
    public void pendingRewardsBecomeClaimed()
    {
        HudTray tray = new HudTray();
        tray.observed(995, "Coins", 5_000L, 1L, T0, false);
        tray.booked(receipt(T0 + 600L, Ai.GAIN, flow(995, "Coins", 5_000L, 1)), T0 + 600L, false);
        assertEquals(1, tray.entries().size());
        assertEquals(HudTray.State.CLAIMED, tray.entries().get(0).state);
    }

    @Test
    public void routineUseNeverRidesButRoutineDropsDo()
    {
        HudTray tray = new HudTray();
        tray.booked(receipt(T0, Ai.LOOT, flow(560, "Death rune", 50L, 200),
            flow(11212, "Dragon arrow", 20L, 900)), T0, false);
        assertEquals("a rune or ammo pickup is a drop like any other", 2, tray.entries().size());
        assertEquals("Death rune", tray.entries().get(0).name);
        assertEquals("Dragon arrow", tray.entries().get(1).name);
        tray.booked(receipt(T0 + 1L, Ai.CONSUMPTION, flow(2434, "Prayer potion(4)", -1L, 9_000)),
            T0 + 1L, false);
        assertEquals("routine use still never rides", 2, tray.entries().size());
        tray.booked(receipt(T0 + 2L, Ai.TRANSFER, flow(536, "Dragon bones", 5L, 2_000)),
            T0 + 2L, false);
        assertEquals("a transfer is neutral; it never rides", 2, tray.entries().size());
    }

    @Test
    public void aDropOfTenMillionLightsGoldAndOneLessDoesNot()
    {
        HudTray tray = new HudTray();
        tray.booked(receipt(T0, Ai.LOOT, flow(1, "Almost", 1L, 9_999_999)), T0, false);
        assertFalse(tray.entries().get(0).gold);
        assertEquals("", tray.bigDrop(T0, 4_000L));

        tray.booked(receipt(T0 + 1L, Ai.LOOT, flow(2, "Twisted bow", 1L, 10_000_000)), T0 + 1L, false);
        assertTrue(tray.entries().get(0).gold);
        assertEquals("Twisted bow", tray.bigDrop(T0 + 1L, 4_000L));
    }

    @Test
    public void deathsDropsAndDestroysAreLostButOtherItemUseIsNot()
    {
        HudTray tray = new HudTray();
        tray.booked(receipt(T0, Ai.CONSUMPTION, flow(8013, "Teleport to house", -1L, 553)), T0, false);
        assertFalse("a teleport tab broken is routine use", !tray.entries.isEmpty());
        Ac dropped = receipt(T0, Ai.CONSUMPTION, flow(536, "Dragon bones", -1L, 2_000));
        dropped.note = "Dropped";
        tray.booked(dropped, T0, false);
        assertEquals(HudTray.State.LOST, tray.entries().get(0).state);
    }

    @Test
    public void ownDropRecoveryShrinksOnlyItsLostReceipt()
    {
        HudTray tray = new HudTray();
        Ac dropped = receipt(T0, Ai.CONSUMPTION,
            flow(526, "Bones", -3L, 2_000));
        dropped.note = "Dropped";
        dropped.zd();
        tray.booked(dropped, T0, false);

        Ac unrelated = receipt(T0 + 1L, Ai.CONSUMPTION,
            flow(526, "Bones", -2L, 3_000));
        unrelated.note = "Destroyed";
        tray.booked(unrelated, T0 + 1L, false);

        dropped.afg(526, false, 2L);
        tray.booked(ownDropRecovery(T0 + 2L, flow(526, "Bones", 2L, 2_000)), T0 + 2L, false);

        HudTray.Entry lost = tray.entries().stream()
            .filter(entry -> entry.state == HudTray.State.LOST && entry.itemId == 526)
            .findFirst().orElseThrow();
        assertEquals("one dropped bone and both unrelated losses remain", 3L, lost.quantity);
        assertEquals("the recovered receipt's original value leaves unrelated loss intact",
            -8_000L, lost.value);
    }

    @Test
    public void failedOwnDropTransferDoesNotHideTheDrop()
    {
        HudTray tray = new HudTray();
        Ac dropped = receipt(T0, Ai.CONSUMPTION,
            flow(526, "Bones", -2L, 2_000));
        dropped.note = "Dropped";
        dropped.zd();
        tray.booked(dropped, T0, false);

        tray.booked(ownDropRecovery(T0 + 1L, flow(526, "Bones", 1L, 2_000)), T0 + 1L, false);

        HudTray.Entry lost = tray.entries().get(0);
        assertEquals("a transfer without canonical correction stays visible", 2L, lost.quantity);
        assertEquals(-4_000L, lost.value);
    }

    @Test
    public void fullOwnDropCorrectionRemovesOnlyItsLostUnits()
    {
        HudTray tray = new HudTray();
        Ac dropped = receipt(T0, Ai.CONSUMPTION,
            flow(526, "Bones", -2L, 2_000));
        dropped.note = "Dropped";
        dropped.zd();
        tray.booked(dropped, T0, false);
        Ac unrelated = receipt(T0 + 1L, Ai.CONSUMPTION,
            flow(526, "Bones", -1L, 3_000));
        unrelated.note = "Destroyed";
        tray.booked(unrelated, T0 + 1L, false);

        dropped.afg(526, false, 2L);
        dropped.ko(Ah.IGNORE, T0 + 2L, "Own-drop recovery");
        tray.booked(ownDropRecovery(T0 + 2L, flow(526, "Bones", 2L, 2_000)), T0 + 2L, false);

        HudTray.Entry lost = tray.entries().get(0);
        assertEquals(1L, lost.quantity);
        assertEquals(-3_000L, lost.value);
    }

    private static Ac ownDropRecovery(long at, Ab... flows)
    {
        return new Ac(at, null, Ai.TRANSFER, Aj.TRANSFER,
            "Own-drop recovery", "Drop recovery", false, Arrays.asList(flows),
            Bd.CONFIRMED, "Own-drop recovery", null);
    }

    @Test
    public void anotherNpcStartsItsOwnStreakSoItsLootNeverJoinsTheLastOne()
    {
        HudTray tray = new HudTray();
        tray.kill("Guard", T0, false);
        tray.booked(receipt(T0 + 600L, Ai.LOOT, flow(995, "Coins", 30L, 1)), T0 + 600L, false);
        tray.kill("Guard", T0 + 10_000L, false);
        assertEquals("Guard", tray.streak(T0 + 10_000L).getKey());
        assertEquals(2, (int) tray.streak(T0 + 10_000L).getValue());

        tray.kill("Hill Giant", T0 + 20_000L, false);
        assertTrue("the Guard's coins leave with the Guard streak", tray.entries().isEmpty());
        tray.booked(receipt(T0 + 20_600L, Ai.LOOT, flow(532, "Big bones", 1L, 300)), T0 + 20_600L, false);
        assertEquals(1, tray.entries().size());
        assertEquals("Hill Giant", tray.streak(T0 + 20_600L).getKey());
        assertEquals(1, (int) tray.streak(T0 + 20_600L).getValue());
    }

    @Test
    public void aNewNpcsFirstKillRefreshesTheTrayToItsOwnStreak()
    {
        HudTray tray = new HudTray();
        tray.engage("Skeleton", false);
        tray.kill("Skeleton", T0, false);
        tray.booked(receipt(T0 + 600L, Ai.LOOT, flow(526, "Bones", 1L, 50)), T0 + 600L, false);
        assertEquals("the tray says what the streak did", "Looted", tray.label());

        tray.engage("Guard", false);
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
        tray.engage("Skeleton", false);
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
        tray.booked(receipt(T0, Ai.LOOT, flow(536, "Dragon bones", 1L, 2_000)), T0, false);
        assertEquals("Looted", tray.label());

        Ac chest = receipt(T0 + 1_000L, Ai.LOOT, flow(1_631, "Uncut dragonstone", 1L, 10_000));
        chest.setActionKind(Au.DEFERRED_CLAIM);
        tray.booked(chest, T0 + 1_000L, false);
        assertEquals("Claimed", tray.label());

        tray.booked(Tx.of(T0 + 2_000L, null, Ai.GAIN, Aj.GENERIC, "",
            "Thieving", true, Arrays.asList(flow(995, "Coins", 3L, 1))), T0 + 2_000L, false);
        assertEquals("Stole", tray.label());
    }

    private static Ac npcLoot(long at, String npc, Ab flow)
    {
        return Tx.of(at, null, Ai.LOOT, Aj.LOOT, "Loot from " + npc, npc, true,
            Arrays.asList(flow));
    }

    @Test
    public void aKillNeverOpensTheTrayOnlyItsLootDoes()
    {
        HudTray tray = new HudTray();
        tray.kill("Guard", T0, false);
        tray.booked(receipt(T0 + 600L, Ai.LOOT, flow(995, "Coins", 30L, 1)), T0 + 600L, false);
        assertFalse("folded after the stay", tray.open(T0 + 600L + STAY, STAY, false));

        tray.kill("Guard", T0 + 20_000L, false);
        assertFalse("the next kill leaves the tray folded", tray.open(T0 + 20_000L, STAY, false));
        tray.booked(receipt(T0 + 21_000L, Ai.LOOT, flow(995, "Coins", 25L, 1)), T0 + 21_000L, false);
        assertTrue("its loot opens it", tray.open(T0 + 21_000L, STAY, false));
    }

    @Test
    public void foldingHidesTheTrayButOnlyAQuietGapEndsTheStreak()
    {
        HudTray tray = new HudTray();
        tray.booked(receipt(T0, Ai.LOOT, flow(536, "Dragon bones", 1L, 2_000)), T0, false);
        assertTrue(tray.open(T0 + 1_000L, STAY, false));
        assertFalse("folds after the stay", tray.open(T0 + STAY, STAY, false));
        int trip = tray.tripId();
        tray.booked(receipt(T0 + STAY, Ai.LOOT, flow(1753, "Green dragonhide", 1L, 1_500)),
            T0 + STAY, false);
        assertEquals("a fold keeps the streak", 2, tray.entries().size());
        assertEquals(trip, tray.tripId());

        tray.booked(receipt(T0 + 400_000L, Ai.LOOT, flow(1753, "Green dragonhide", 1L, 1_500)),
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
    public void anotherKindOfWorkStartsANewStreakUnlessRowsKeepForTheSession()
    {
        HudTray streak = new HudTray();
        streak.booked(receipt(T0, Ai.LOOT, flow(536, "Dragon bones", 1L, 2_000)), T0, false);
        Ac ore = Tx.of(T0 + 1_000L, null, Ai.GAIN, Aj.GENERIC,
            "", "Mining", true, Arrays.asList(flow(436, "Copper ore", 1L, 50)));
        streak.booked(ore, T0 + 1_000L, false);
        assertEquals(1, streak.entries().size());
        assertEquals("Mined", streak.label());

        HudTray session = new HudTray();
        session.kill("Guard", T0, true);
        session.booked(receipt(T0, Ai.LOOT, flow(536, "Dragon bones", 1L, 2_000)), T0, true);
        session.kill("Man", T0 + 1_000L, true);
        session.booked(receipt(T0 + 1_000L, Ai.LOOT, flow(1753, "Green dragonhide", 1L, 1_500)),
            T0 + 1_000L, true);
        assertEquals("the whole session keeps every row", 2, session.entries().size());
    }
}
