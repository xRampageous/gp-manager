package com.gpmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Combining or decanting potions keeps every dose: neutral, never a Review row (owner 1.2). */
public class DecantNeutralTest
{
    private static final int PRAYER_4 = 2434;
    private static final int PRAYER_3 = 139;
    private static final int PRAYER_2 = 141;
    private static final int PRAYER_1 = 143;
    private static final int VIAL = 229;
    private static final Map<Integer, String> NAMES = Map.of(PRAYER_4, "Prayer potion(4)", PRAYER_3, "Prayer potion(3)",
        PRAYER_2, "Prayer potion(2)", PRAYER_1, "Prayer potion(1)", VIAL, "Vial");
    private static final Map<Integer, Integer> PRICES = Map.of(PRAYER_4, 9_800, PRAYER_3, 7_400, PRAYER_2, 4_900,
        PRAYER_1, 2_500, VIAL, 3);

    private static Am engine(boolean auditRows)
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override
            public int stabilizationTicks()
            {
                return 2;
            }

            @Override
            public boolean keepTransferAuditRows()
            {
                return auditRows;
            }
        };
        return new Am(deltas ->
        {
            List<Ab> flows = new ArrayList<>();
            deltas.forEach((id, quantity) -> flows.add(new Ab(id, NAMES.get(id), quantity, PRICES.get(id),
                quantity * PRICES.get(id))));
            return flows;
        }, new TransactionClassifier(), config);
    }

    private static Am settle(boolean auditRows, Map<Integer, Long> before, Map<Integer, Long> after)
    {
        Am engine = engine(auditRows);
        engine.rm(1_000_000L);
        engine.setBaseline(new Cc(new HashMap<>(before)));
        engine.yz();
        for (int tick = 1; tick <= 5; tick++)
        {
            engine.adj(new Cc(new HashMap<>(after)), 1_000_000L + tick * 600L);
        }
        return engine;
    }

    @Test
    public void combiningByHandBooksNothingAndNeedsNoReview()
    {
        Am engine = settle(false, Map.of(PRAYER_3, 1L, PRAYER_1, 1L), Map.of(PRAYER_4, 1L, VIAL, 1L));
        assertEquals("no money moved", 0L, engine.getMetrics(1_010_000L).net);
        assertTrue("no Review row: " + engine.getActiveSession().getTransactions(),
            engine.getActiveSession().getTransactions().isEmpty());
    }

    @Test
    public void aDecantIsANeutralTransferInTheAudit()
    {
        Am engine = settle(true, Map.of(PRAYER_2, 2L), Map.of(PRAYER_4, 1L));
        List<Ac> rows = engine.getActiveSession().getTransactions();
        assertEquals(rows.toString(), 1, rows.size());
        Ac decant = rows.get(0);
        assertEquals(Ai.TRANSFER, decant.tm());
        assertEquals("Decanted", decant.getNote());
        assertEquals(false, decant.isCounted());
        assertEquals(0L, engine.getMetrics(1_010_000L).net);
    }
}
