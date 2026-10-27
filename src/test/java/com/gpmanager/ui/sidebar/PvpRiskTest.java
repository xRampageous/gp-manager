package com.gpmanager;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import net.runelite.api.gameval.ItemID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Owner 1.1: HUD+ shows what a death in the Wilderness would lose. Nothing is booked. */
public class PvpRiskTest
{
    private static Flow held(int id, long quantity, int unit)
    {
        return new Flow(id, "Item " + id, quantity, unit, quantity * unit);
    }

    private static final List<Flow> GEAR = Arrays.asList(held(1, 1L, 50_000_000), held(2, 1L, 20_000_000),
        held(3, 1L, 10_000_000), held(4, 1L, 5_000_000), held(5, 10L, 1_000), held(6, 3L, 0));

    @Test
    public void unskulledKeepsTheThreeMostValuable()
    {
        assertEquals(5_010_000L, PvpState.risk(GEAR, false, false));
        assertEquals("Protect Item keeps a fourth", 10_000L, PvpState.risk(GEAR, false, true));
    }

    @Test
    public void skulledKeepsNothingOrOneWithProtectItem()
    {
        assertEquals(85_010_000L, PvpState.risk(GEAR, true, false));
        assertEquals(35_010_000L, PvpState.risk(GEAR, true, true));
    }

    @Test
    public void anAvernicDefenderWeighsItsRepairCost()
    {
        // Since 2026-07-29 the game ranks it at 600,000 whatever its hilt trades for.
        List<Flow> gear = Arrays.asList(new Flow(22322, "Avernic defender", 1L, 40_000_000, 40_000_000L),
            held(1, 1L, 3_000_000), held(2, 1L, 2_000_000), held(3, 1L, 1_000_000));
        assertEquals("kept below the three dearer items; losing it costs the repair", 600_000L, PvpState.risk(gear, false, false));
        assertEquals("the tradeable hilt keeps its price", 40_000_000L,
            PvpState.deathValue(new Flow(22477, "Avernic defender hilt", 1L, 40_000_000, 40_000_000L)));
    }

    @Test
    public void aStackKeepsUnitsNotTheWholeStack()
    {
        List<Flow> darts = Arrays.asList(held(11230, 500L, 1_200), held(7, 1L, 100));
        assertEquals("three darts kept, the cheap item lost", 497L * 1_200 + 100, PvpState.risk(darts, false, false));
    }

    @Test public void chargedEthereumCannotUseAnyDefaultOrPrayerProtectionSlot() {
        ethereumAlwaysLost(ItemID.WILD_CAVE_BRACELET_CHARGED);
    }

    @Test public void braceletExceptionTableUsesExactlyBothRuneLiteStates() throws Exception {
        assertEquals(java.util.Set.of(ItemID.WILD_CAVE_BRACELET_CHARGED, ItemID.WILD_CAVE_BRACELET_UNCHARGED),
            PvpState.ALWAYS_LOST);
        for (String[] row : GameData.rows("death_always_lost"))
            assertEquals(Integer.parseInt(row[0]), ItemID.class.getField(row[1]).getInt(null));
    }

    @Test public void unchargedEthereumCannotUseAnyDefaultOrPrayerProtectionSlot() {
        ethereumAlwaysLost(ItemID.WILD_CAVE_BRACELET_UNCHARGED);
    }

    private static void ethereumAlwaysLost(int id) {
        List<Flow> ordinary = Arrays.asList(held(1, 1, 40_000), held(2, 1, 30_000),
            held(3, 1, 20_000), held(4, 1, 10_000));
        List<Flow> with = new java.util.ArrayList<>(ordinary);
        // ID determines eligibility, even if a definition/name is unavailable.
        with.add(held(id, 1, 50_000));
        for (boolean skull : new boolean[]{false, true}) for (boolean prayer : new boolean[]{false, true})
            assertEquals("skull=" + skull + " prayer=" + prayer,
                50_000 + PvpState.risk(ordinary, skull, prayer), PvpState.risk(with, skull, prayer));
    }

    @Test public void multipleEthereumUnitsRemainAtRiskWithoutConsumingSlots() {
        assertEquals(150_000, PvpState.risk(Arrays.asList(
            held(ItemID.WILD_CAVE_BRACELET_UNCHARGED, 3, 50_000), held(1, 1, 100)), false, true));
    }

    @Test public void onlyEthereumItemsRemainAtRiskEvenWithFourFreeProtectionSlots() {
        assertEquals(90_000, PvpState.risk(Arrays.asList(
            held(ItemID.WILD_CAVE_BRACELET_CHARGED, 1, 50_000),
            held(ItemID.WILD_CAVE_BRACELET_UNCHARGED, 1, 40_000)), false, true));
    }

    @Test public void anOrdinaryBraceletStillUsesProtection() {
        assertEquals(100, PvpState.risk(Arrays.asList(
            new Flow(ItemID.ZENYTE_BRACELET, "Tormented bracelet", 1, 50_000, 50_000),
            held(1, 1, 40_000), held(2, 1, 30_000), held(3, 1, 100)), false, false));
    }

    @Test public void nativeOctober23LoadoutProtectsGloryBodyAndChaps() {
        List<Flow> gear = Arrays.asList(held(ItemID.WILD_CAVE_BRACELET_CHARGED, 1, 42_833),
            held(1704, 1, 11_369), held(2503, 1, 7_335), held(2497, 1, 3_258),
            held(24598, 1, 2_059), held(861, 1, 704), held(10499, 1, 600),
            held(7946, 21, 140), held(21326, 1182, 119), held(19564, 1, 12), held(11941, 1, 6));
        assertEquals(189_812, PvpState.risk(gear, false, false));
        assertEquals("recorded active quotes plus remembered bag; native guide is a different valuation",
            324_351, PvpState.risk(gear, false, false) + 134_539);
        assertEquals("Risk is a read only projection", 42_833, gear.get(0).unitPrice);
    }

    /** Owner 1.1: HUD+ stays short in the Wilderness; only Risk is shown (owner live 2026-10-13: no skull or Protect rows). */
    @Test
    public void riskLivesOnLiveAndHudPlusStaysClean() throws Exception
    {
        Engine engine = HudBuilderTest.engine();
        engine.startCustom("Revenants", HudBuilderTest.NOW);
        LiveSnapshot s = LiveSnapshot.capture(engine, HudBuilderTest.NOW, new LiveContext(false, null, 0L,
            new PvpState(true, true, false, 12_400_000L), "", false, "", false));
        HudBuilder builder = new HudBuilder(HudBuilderTest.config(false, 4), null);
        assertEquals("no PvP line on HUD+", "", builder.update(s, f -> true, HudBuilderTest.NOW).context);

        LivePage.Actions actions = (LivePage.Actions) java.lang.reflect.Proxy.newProxyInstance(
            LivePage.Actions.class.getClassLoader(), new Class<?>[] {LivePage.Actions.class},
            (proxy, method, args) -> method.getReturnType() == boolean.class ? false : null);
        java.util.List<String> cells = new java.util.ArrayList<>();
        LivePage page = new LivePage(actions, id -> null);
        for (java.util.List<Hero.Cell> row : page.strips(s))
        {
            for (Hero.Cell cell : row)
            {
                cells.add(cell.label + " " + cell.value);
            }
        }
        assertFalse("no Risk row: " + cells, cells.toString().contains("RISK"));
        // Owner 1.5.2: risk sits under the Net, beside the clock, out of the header.
        assertTrue(page.statusIcons(s).stream().noneMatch(cell -> cell.value.contains("RISK")));
        javax.swing.SwingUtilities.invokeAndWait(() -> page.apply(s));
        assertEquals("RISK 12.4M", page.risk.getText());
        assertTrue(page.risk.isVisible() && page.hero.under.isVisible());
        assertFalse("no run total while Live shows the whole run", page.total.isVisible());
        assertFalse("skull and Protect are never shown (owner live 2026-10-13)",
            cells.toString().contains("SKULL") || cells.toString().contains("PROTECT"));
    }
}
