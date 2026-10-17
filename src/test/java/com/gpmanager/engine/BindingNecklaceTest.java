package com.gpmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The binding necklace (16 uses, destroyed when its charges run out) is consumption gear: its
 * crumble books one counted loss at its price, and nothing per-charge is inferred (owner
 * 2026-10-13; VarPlayer 487 confirms charges exist but the crumble is the honest observable).
 */
public class BindingNecklaceTest
{
    private static final int NECKLACE = ItemID.MAGIC_EMERALD_NECKLACE;

    private static Engine settle(Map<Integer, Long> before, Map<Integer, Long> after)
    {
        Engine engine = new Engine(deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            deltas.forEach((id, quantity) -> flows.add(new Flow(id, "Binding necklace", quantity, 620,
                quantity * 620)));
            return flows;
        }, new TransactionClassifier(), new GpManagerConfig()
        {
        });
        engine.ensureSession(1_000_000L);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(before)));
        engine.markDirty();
        for (int tick = 1; tick <= 5; tick++)
        {
            engine.processIfDirty(new ContainerSnapshot(new HashMap<>(after)), 1_000_000L + tick * 600L, null);
        }
        return engine;
    }

    @Test
    public void theCrumbleBooksOneCountedLoss()
    {
        Engine engine = settle(Map.of(NECKLACE, 1L), Map.of());
        List<Transaction> rows = engine.getActiveSession().getTransactions();
        assertEquals(rows.toString(), 1, rows.size());
        assertTrue(rows.get(0).isCounted());
        assertEquals(-620L, engine.getMetrics(1_010_000L).net);
    }
}
