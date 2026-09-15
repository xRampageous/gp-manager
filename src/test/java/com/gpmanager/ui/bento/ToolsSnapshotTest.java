package com.gpmanager.ui.bento;

import com.gpmanager.GpManagerConfig;
import com.gpmanager.engine.ContainerSnapshot;
import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.engine.TransactionClassifier;
import com.gpmanager.model.ClassificationConfidence;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.PartyProfitSummary;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TileLayout;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionCorrection;
import com.gpmanager.model.TransactionType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tools read model: review decisions come from uncertain automatic calls and leave once
 * decided, applied corrections are listed with undo, storage counts are honest, hidden Live
 * tiles follow the engine's TileLayout, and the party split is even against the mean.
 */
public class ToolsSnapshotTest
{
    static
    {
        com.gpmanager.persistence.JsonCodec.bind(new com.google.gson.Gson());
    }

    private static final int LOGS = 1519;

    @Test
    public void decisionsFollowUncertainReceiptsAndCorrections()
    {
        GpManagerEngine engine = engine();
        long now = System.currentTimeMillis();
        engine.ensureSession(now - 60_000L);
        engine.setBaseline(snapshot(Collections.emptyMap()));
        settle(engine, snapshot(map(LOGS, 5L)), now - 50_000L);
        ProfitTransaction odd = new ProfitTransaction(now - 30_000L, 30_000L, TransactionType.CONSUMPTION,
            TrackingContext.GENERIC, "Unknown change", "General", true,
            Collections.singletonList(new ItemFlow(LOGS, "Willow logs", -3, 48, -144, ItemPriceSource.GRAND_EXCHANGE)),
            ClassificationConfidence.UNCERTAIN, "Mixed change", null);
        engine.getActiveSession().addTransaction(odd, 500);

        ToolsSnapshot s = ToolsSnapshot.capture(engine, now, null, new com.gpmanager.ui.WealthLocateModel(), null, null);
        assertEquals(1, s.decisions.size());
        ToolsSnapshot.Decision d = s.decisions.get(0);
        assertEquals(odd.getId(), d.transactionId);
        assertEquals("Willow logs", d.name);
        assertEquals(-144L, d.value);
        assertEquals("Mixed change", d.why);
        assertTrue(s.applied.isEmpty());
        assertFalse(s.canUndo);
        assertEquals(0, s.storage.sessions);   // free play is not a session
        assertEquals(2L, s.storage.receipts);
        assertNull(s.wealthTotal);
        assertNull(s.historyWealthTotal);
        assertEquals(3, s.wealthChanges.size());
        for (ToolsSnapshot.WealthChange c : s.wealthChanges)
        {
            assertFalse("no bank visits recorded: every anchor is unavailable", c.available);
        }
        assertEquals(90, s.storage.retentionDays);
        assertTrue(s.storage.approximateBytes > 0L);
        assertEquals(7, s.locate.size());

        assertTrue(engine.correctTransaction(d.transactionId, TransactionCorrection.IGNORE, now, "test"));
        ToolsSnapshot after = ToolsSnapshot.capture(engine, now, null, null, null, null);
        assertTrue(after.decisions.isEmpty());
        assertEquals(1, after.applied.size());
        assertEquals("auto → ignore", after.applied.get(0).change);
        assertTrue(after.canUndo);
        assertEquals(1, after.storage.corrections);
    }

    @Test
    public void hiddenLiveTilesComeFromTheEngineLayout()
    {
        GpManagerEngine engine = engine();
        ToolsSnapshot none = ToolsSnapshot.capture(engine, 1_000L, null, null, null, null);
        assertTrue(none.hiddenLiveTiles.isEmpty());

        Map<String, TileLayout.PageLayout> pages = new HashMap<>();
        pages.put(ToolsSnapshot.LIVE_PAGE, new TileLayout.PageLayout(Collections.emptyList(),
            new java.util.HashSet<>(Arrays.asList("party", "recent"))));
        engine.setTileLayout(new TileLayout(pages));
        ToolsSnapshot some = ToolsSnapshot.capture(engine, 1_000L, null, null, null, null);
        assertEquals(2, some.hiddenLiveTiles.size());
        assertTrue(some.hiddenLiveTiles.contains("party"));
        assertTrue(some.hiddenLiveTiles.contains("recent"));
        // No saved order reads as the default order.
        assertEquals(Arrays.asList(ToolsSnapshot.LIVE_TILES), some.liveTileOrder);

        // A saved order wins; unknown ids are dropped and missing ones append in default order.
        pages.put(ToolsSnapshot.LIVE_PAGE, new TileLayout.PageLayout(Arrays.asList("recent", "bogus", "goal"), Collections.emptySet()));
        engine.setTileLayout(new TileLayout(pages));
        ToolsSnapshot ordered = ToolsSnapshot.capture(engine, 1_000L, null, null, null, null);
        assertEquals(Arrays.asList("recent", "goal", "party", "notices"), ordered.liveTileOrder);
    }

    @Test
    public void evenSplitAgainstTheMeanIgnoresNonReporters()
    {
        PartyProfitSummary party = new PartyProfitSummary(true, 3, 0L, 0L, 900_000L, 0L, 0L, Arrays.asList(
            new PartyProfitSummary.Member("A", 0L, 0L, 600_000L, 0L, 0L, 1, true, true, true, false, 0L),
            new PartyProfitSummary.Member("B", 0L, 0L, 300_000L, 0L, 0L, 1, false, true, true, false, 0L),
            PartyProfitSummary.Member.notReporting("C", false)));
        List<long[]> rows = ToolsSnapshot.evenSplit(party);
        assertEquals(2, rows.size());
        assertEquals(450_000L, rows.get(0)[2]);
        assertEquals(150_000L, rows.get(0)[3]);   // A owes the pool
        assertEquals(-150_000L, rows.get(1)[3]);  // B is owed
        assertTrue(ToolsSnapshot.evenSplit(null).isEmpty());
        assertTrue(ToolsSnapshot.evenSplit(PartyProfitSummary.empty()).isEmpty());
    }

    @Test
    public void livePvpFiguresAndSeriesComeFromTheEngine()
    {
        GpManagerEngine engine = engine();
        long now = System.currentTimeMillis();
        engine.ensureSession(now - 60_000L);
        engine.setBaseline(snapshot(Collections.emptyMap()));
        settle(engine, snapshot(map(LOGS, 5L)), now - 50_000L);
        engine.setBaseline(snapshot(map(LOGS, 5L)));
        settle(engine, snapshot(map(LOGS, 9L)), now - 40_000L);
        LiveSnapshot s = LiveSnapshot.capture(engine, now, false, false);
        assertFalse(s.pvpSession);
        assertEquals(0, s.kills);
        assertEquals(2, s.netSeries.length);
        assertEquals(240d, s.netSeries[0], 0.001d);
        assertEquals(432d, s.netSeries[1], 0.001d);
        assertEquals(0d, s.kd(), 0.001d);
    }

    private static GpManagerEngine engine()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 0; }
            @Override public int minimumTransactionValue() { return 1; }
        };
        return new GpManagerEngine(deltas ->
        {
            List<ItemFlow> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                flows.add(new ItemFlow(delta.getKey(), "Willow logs", delta.getValue(), 48, delta.getValue() * 48,
                    ItemPriceSource.GRAND_EXCHANGE));
            }
            return flows;
        }, new TransactionClassifier(), config);
    }

    private static void settle(GpManagerEngine engine, ContainerSnapshot snapshot, long now)
    {
        engine.markInventoryDirty();
        engine.processIfDirty(snapshot, now);
        engine.processIfDirty(snapshot, now + 600L);
    }

    private static ContainerSnapshot snapshot(Map<Integer, Long> items)
    {
        return new ContainerSnapshot(items);
    }

    private static Map<Integer, Long> map(Object... pairs)
    {
        Map<Integer, Long> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
        {
            map.put((Integer) pairs[i], (Long) pairs[i + 1]);
        }
        return map;
    }
}
