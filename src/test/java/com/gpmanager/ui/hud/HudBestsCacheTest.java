package com.gpmanager;

import com.google.gson.Gson;
import java.util.Collections;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Owner 2026-10-01 (F04): the cached HUD bests follow restores and deletions. */
public class HudBestsCacheTest
{
    static
    {
        JsonCodec.bind(new Gson());
    }

    private static final long NOW = 1_700_000_000_000L;

    @Test
    public void deletingOrRestoringTheRecordRunRecomputesTheBests() throws Exception
    {
        GpManagerConfig config = new GpManagerConfig() {};
        Am engine = new Am(deltas -> Collections.emptyList(), new TransactionClassifier(), config);

        engine.ajl("Vorkath", Cx.GENERAL, NOW - 10_800_000L);
        String recordId = engine.getActiveSession().getId();
        SavedState.Ap saved = engine.avg("Vorkath", null, null, false, recordId);
        book(engine.getActiveSession(), NOW - 10_799_000L, 2_000_000L);
        engine.sx(NOW - 7_200_000L);

        engine.startGrind("Vorkath", saved.getGrindId(), null, null, NOW - 7_200_000L);
        book(engine.getActiveSession(), NOW - 7_199_000L, 1_000_000L);
        engine.sx(NOW - 3_600_000L);

        // A live linked run keeps the lineage in view while the closed runs are the record.
        engine.startGrind("Vorkath", saved.getGrindId(), null, null, NOW - 3_600_000L);

        Dp panel = onEdt(() -> new Dp(engine, config, null));
        panel.axn(new Cp(config, null));
        refresh(panel);
        assertNotNull("the bests cache ran", panel.bestsFor);
        assertNotNull("the lineage resolved", panel.bests);
        assertEquals("the record run is the best", 2_000_000L, panel.bests[1]);
        String recorded = panel.bestsFor;
        SavedState snapshot = engine.qm();

        assertTrue(engine.qu(recordId));
        refresh(panel);
        assertNotEquals("the key follows the history", recorded, panel.bestsFor);
        assertEquals("the next best takes over", 1_000_000L, panel.bests[1]);

        engine.restore(snapshot);
        refresh(panel);
        assertNotEquals("the same saved id on a restored profile still recomputes",
            recorded, panel.bestsFor);
        assertEquals("the restored record is back", 2_000_000L, panel.bests[1]);
    }

    private static void refresh(Dp panel) throws Exception
    {
        onEdt(() ->
        {
            SidebarPanelProbe.refresh(panel);
            return null;
        });
    }

    private static void book(Ad session, long at, long value)
    {
        session.kf(new Ac(at, null, Ai.LOOT, Aj.LOOT, "", "Vorkath", true,
            Collections.singletonList(new Ab(536, "Dragon bones", 1L, (int) value, value)),
            Bd.CONFIRMED, "fixture", null), 2_000);
    }

    @SuppressWarnings("unchecked")
    private static <T> T onEdt(java.util.concurrent.Callable<T> callable) throws Exception
    {
        Object[] result = new Object[1];
        SwingUtilities.invokeAndWait(() ->
        {
            try
            {
                result[0] = callable.call();
            }
            catch (Exception ex)
            {
                throw new RuntimeException(ex);
            }
        });
        return (T) result[0];
    }
}
