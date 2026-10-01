package com.gpmanager;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The current loot visibility settings are presentation-only: they change which Live rows
 * appear, never what was booked, and they never hide costs or unpriced gains.
 */
public class LootVisibilityPresentationTest
{
    @Test
    public void minimumDisplayedLootValueHidesCheapPricedGainsOnly() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, now);
        engine.getActiveSession().addTransaction(
            transaction(now + 1_000L, "Cheap bones", 1, TransactionType.GAIN, 2, 100, 200L), 2_000);
        engine.getActiveSession().addTransaction(
            transaction(now + 2_000L, "Valuable hide", 2, TransactionType.GAIN, 1, 5_000, 5_000L), 2_000);
        long net = engine.getMetrics(now + 3_000L).net;

        LiveSnapshot unfiltered = LiveSnapshot.capture(engine, now + 3_000L, LiveContext.NONE);
        assertEquals("both gains are visible with no minimum", 2, unfiltered.recent.size());

        LiveContext minimum = new LiveContext(false, null, 1_000L, PvpState.NONE, "", false, "");
        LiveSnapshot filtered = LiveSnapshot.capture(engine, now + 3_000L, minimum);
        assertEquals("the cheap gain is hidden from presentation", 1, filtered.recent.size());
        assertEquals("Valuable hide", filtered.recent.get(0).name);
        assertEquals("canonical net is untouched by presentation filtering", net,
            engine.getMetrics(now + 3_000L).net);
        assertEquals("the hidden receipt is still canonical session truth",
            2, engine.getActiveSession().getTransactions().size());
    }

    @Test
    public void minimumDisplayedLootValueNeverHidesCostsOrUnpricedGains() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, now);
        engine.getActiveSession().addTransaction(
            transaction(now + 1_000L, "Prayer potion(4)", 3, TransactionType.CONSUMPTION, -2, 9_800, -19_600L), 2_000);
        // Unit price 0: the price is unknown, so the minimum cannot judge it.
        engine.getActiveSession().addTransaction(
            transaction(now + 2_000L, "Unidentified mineral", 8, TransactionType.GAIN, 8, 0, 0L), 2_000);

        LiveContext minimum = new LiveContext(false, null, 1_000_000L, PvpState.NONE, "", false, "");
        LiveSnapshot filtered = LiveSnapshot.capture(engine, now + 3_000L, minimum);
        assertEquals("costs stay discoverable and unpriced gains stay reviewable", 2, filtered.recent.size());
        LiveSnapshot.Recent mineral = null;
        for (LiveSnapshot.Recent row : filtered.recent)
        {
            if ("Unidentified mineral".equals(row.name))
            {
                mineral = row;
            }
        }
        assertNotNull("the unpriced gain stays visible", mineral);
        assertTrue("the unpriced row keeps its unknown marker", mineral.unpriced);
    }

    @Test
    public void itemFilterHidesOnlyPresentationAndKeepsCanonicalTruth() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, now);
        engine.getActiveSession().addTransaction(
            transaction(now + 1_000L, "Vial", 1, TransactionType.GAIN, 3, 1, 3L), 2_000);
        engine.getActiveSession().addTransaction(
            transaction(now + 2_000L, "Dragon bones", 1, TransactionType.GAIN, 2, 3_200, 6_400L), 2_000);
        long net = engine.getMetrics(now + 3_000L).net;

        SidebarPanel panel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(engine, PresentationLifecycleTest.config(), null);
            created.bindFilter(flow -> !"Vial".equals(flow.itemName));
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LiveSnapshot snapshot = SidebarPanelProbe.live(panel);
        assertNotNull(snapshot);
        assertEquals("the filtered item is not rendered", 1, snapshot.recent.size());
        assertEquals("Dragon bones", snapshot.recent.get(0).name);
        assertEquals("the booked net is unchanged by the presentation filter", net, snapshot.net);
        assertTrue("the hidden receipt stays canonical", engine.getActiveSession().getTransactions().size() == 2);
    }

    @Test
    public void minimumValueSettingParsesPlainAndSuffixedForms() throws Exception
    {
        assertEquals(0L, Fmt.parseGp(null));
        assertEquals(0L, Fmt.parseGp(""));
        assertEquals(0L, Fmt.parseGp("abc"));
        assertEquals(0L, Fmt.parseGp("0"));
        assertEquals(3_000L, Fmt.parseGp("3000"));
        assertEquals(200_000L, Fmt.parseGp("200k"));
        assertEquals(1_500_000L, Fmt.parseGp("1.5m"));
        assertEquals(2_500L, Fmt.parseGp("2,500"));
        assertEquals(0L, Fmt.parseGp("-5k"));
    }

    @Test
    public void panelReadsTheCurrentMinimumSettingOnEveryRefresh() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, now);
        engine.getActiveSession().addTransaction(
            transaction(now + 1_000L, "Cheap bones", 1, TransactionType.GAIN, 2, 100, 200L), 2_000);

        SidebarPanel panel = onEdt(() -> new SidebarPanel(engine, minimumConfig("500"), null));
        onEdt(() ->
        {
            SidebarPanelProbe.refresh(panel);
            return null;
        });
        assertTrue("a 500 gp minimum hides a 100 gp unit-price gain", SidebarPanelProbe.live(panel).recent.isEmpty());

        SidebarPanel open = onEdt(() -> new SidebarPanel(engine, minimumConfig("0"), null));
        onEdt(() ->
        {
            SidebarPanelProbe.refresh(open);
            return null;
        });
        assertFalse("no minimum shows the same booked gain", SidebarPanelProbe.live(open).recent.isEmpty());
    }

    private static GpManagerConfig minimumConfig(String minimum)
    {
        return new GpManagerConfig()
        {
            @Override
            public int stabilizationTicks()
            {
                return 1;
            }

            @Override
            public String minimumDisplayedLootValue()
            {
                return minimum;
            }
        };
    }

    private static Transaction transaction(long at, String name, int itemId, TransactionType type,
        long quantity, int unitPrice, long value)
    {
        return new Transaction(at, null, type, Context.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Flow(itemId, name, quantity, unitPrice, value)),
            ClassificationConfidence.LIKELY, "Test sample.", null);
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> callable) throws Exception
    {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() ->
        {
            try
            {
                result.set(callable.call());
            }
            catch (Exception ex)
            {
                failure.set(ex);
            }
        });
        if (failure.get() != null)
        {
            throw failure.get();
        }
        return result.get();
    }
}
