package com.gpmanager;

import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Owner 2026-10-01 (F19): a PvP folio with an unknown cost split shows complete Costs,
 * never a fake zero Supplies, and the split rows otherwise.
 */
public class PvpFolioCostFallbackTest
{
    private static final long NOW = 1_700_000_000_000L;

    @Test
    public void anUnknownSplitShowsCompleteCostsNeverZeroSupplies()
    {
        Cp builder = new Cp(new GpManagerConfig() {}, null);
        List<Cb.Line> lines = builder.folio(pvp(-1L, -1L, 2_000L, 1_500L, false),
            Collections.emptyList(), null, NOW, 0);

        Cb.Line costs = row(lines, "Costs");
        assertNotNull("the complete cost is named", costs);
        assertEquals("−2.0k", costs.value);
        assertNull("no fake zero Supplies", row(lines, "Supplies"));
        assertNull("the split rows collapse", row(lines, "Deaths / losses"));
    }

    @Test
    public void aKnownSplitKeepsSuppliesAndDeaths()
    {
        Cp builder = new Cp(new GpManagerConfig() {}, null);
        List<Cb.Line> lines = builder.folio(pvp(800L, 400L, 2_000L, 400L, true),
            Collections.emptyList(), null, NOW, 0);

        assertEquals("−800", row(lines, "Supplies").value);
        assertEquals("−400", row(lines, "Deaths / losses").value);
        assertNull("the fallback row stays out", row(lines, "Costs"));
    }

    private static Cb.Line row(List<Cb.Line> lines, String label)
    {
        for (Cb.Line line : lines)
        {
            if (label.equals(line.label))
            {
                return line;
            }
        }
        return null;
    }

    private static Ca pvp(long supplies, long loss, long costs, long deathLoss,
        boolean splitAvailable)
    {
        return new Ca("pk-run", true, "PK Trip", 3_600_000L, false, false,
            0L, 0L, loss, supplies, costs, splitAvailable,
            false, 0L, null, 0L, 0, false, "", "",
            false, false, false, 0, Collections.emptyList(), true,
            2, 1, 0L, deathLoss, 0L, 0, 0L, 0, 0, false, 0L, 0L, false, false);
    }
}
