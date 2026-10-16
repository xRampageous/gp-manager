package com.gpmanager;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

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

    /** Owner 1.1: HUD+ stays short in the Wilderness; only Risk is shown (owner live 2026-10-13: no skull or Protect rows). */
    @Test
    public void riskLivesOnLiveAndHudPlusStaysClean() throws Exception
    {
        Engine engine = HudBuilderTest.engine();
        engine.startCustom("Revenants", HudBuilderTest.NOW);
        LiveSnapshot s = LiveSnapshot.capture(engine, HudBuilderTest.NOW, new LiveContext(false, null, 0L,
            new PvpState(true, true, false, 12_400_000L), "", false, ""));
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
        // Owner 1.5.1: risk sits beside the Review badge instead of a row of its own.
        assertFalse("no Risk row: " + cells, cells.toString().contains("RISK"));
        assertTrue(page.statusIcons(s).stream().anyMatch(cell -> "RISK 12.4M".equals(cell.value)));
        assertFalse("skull and Protect are never shown (owner live 2026-10-13)",
            cells.toString().contains("SKULL") || cells.toString().contains("PROTECT"));
    }
}
