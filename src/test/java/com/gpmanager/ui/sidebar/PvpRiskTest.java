package com.gpmanager;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Owner 1.1: HUD+ shows what a death in the Wilderness would lose. Nothing is booked. */
public class PvpRiskTest
{
    private static Ab held(int id, long quantity, int unit)
    {
        return new Ab(id, "Item " + id, quantity, unit, quantity * unit);
    }

    private static final List<Ab> GEAR = Arrays.asList(held(1, 1L, 50_000_000), held(2, 1L, 20_000_000),
        held(3, 1L, 10_000_000), held(4, 1L, 5_000_000), held(5, 10L, 1_000), held(6, 3L, 0));

    @Test
    public void unskulledKeepsTheThreeMostValuable()
    {
        assertEquals(5_010_000L, Bo.risk(GEAR, false, false));
        assertEquals("Protect Item keeps a fourth", 10_000L, Bo.risk(GEAR, false, true));
    }

    @Test
    public void skulledKeepsNothingOrOneWithProtectItem()
    {
        assertEquals(85_010_000L, Bo.risk(GEAR, true, false));
        assertEquals(35_010_000L, Bo.risk(GEAR, true, true));
    }

    @Test
    public void aStackKeepsUnitsNotTheWholeStack()
    {
        List<Ab> darts = Arrays.asList(held(11230, 500L, 1_200), held(7, 1L, 100));
        assertEquals("three darts kept, the cheap item lost", 497L * 1_200 + 100, Bo.risk(darts, false, false));
    }

    /** Owner 1.1: HUD+ stays short in the Wilderness; risk, skull and Protect live on Live. */
    @Test
    public void riskLivesOnLiveAndHudPlusStaysClean() throws Exception
    {
        Am engine = HudBuilderTest.engine();
        engine.ajl("Revenants", Cx.GENERAL, HudBuilderTest.NOW);
        Ca s = Ca.capture(engine, HudBuilderTest.NOW, new Dz(false, null, 0L,
            new Bo(true, true, false, 12_400_000L), "", false, ""));
        Cp builder = new Cp(HudBuilderTest.config(false, 4), null);
        assertEquals("no PvP line on HUD+", "", builder.update(s, f -> true, HudBuilderTest.NOW).context);

        LivePage.Actions actions = (LivePage.Actions) java.lang.reflect.Proxy.newProxyInstance(
            LivePage.Actions.class.getClassLoader(), new Class<?>[] {LivePage.Actions.class},
            (proxy, method, args) -> method.getReturnType() == boolean.class ? false : null);
        java.util.List<String> cells = new java.util.ArrayList<>();
        for (java.util.List<Hero.Cell> row : new LivePage(actions, id -> null).strips(s))
        {
            for (Hero.Cell cell : row)
            {
                cells.add(cell.label + " " + cell.value);
            }
        }
        assertTrue(cells.toString(), cells.containsAll(java.util.Arrays.asList("RISK 12.4M", "SKULL On", "PROTECT Off")));
    }
}
