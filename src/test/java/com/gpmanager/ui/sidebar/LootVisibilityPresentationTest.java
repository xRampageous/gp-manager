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
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        engine.getActiveSession().kf(
            transaction(now + 1_000L, "Cheap bones", 1, Ai.GAIN, 2, 100, 200L), 2_000);
        engine.getActiveSession().kf(
            transaction(now + 2_000L, "Valuable hide", 2, Ai.GAIN, 1, 5_000, 5_000L), 2_000);
        long net = engine.getMetrics(now + 3_000L).net;

        Ca unfiltered = Ca.capture(engine, now + 3_000L, Dz.NONE);
        assertEquals("both gains are visible with no minimum", 2, unfiltered.recent.size());

        Dz minimum = new Dz(false, null, 1_000L, Bo.NONE, "", false, "");
        Ca filtered = Ca.capture(engine, now + 3_000L, minimum);
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
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        engine.getActiveSession().kf(
            transaction(now + 1_000L, "Prayer potion(4)", 3, Ai.CONSUMPTION, -2, 9_800, -19_600L), 2_000);
        // Unit price 0: the price is unknown, so the minimum cannot judge it.
        engine.getActiveSession().kf(
            transaction(now + 2_000L, "Unidentified mineral", 8, Ai.GAIN, 8, 0, 0L), 2_000);

        Dz minimum = new Dz(false, null, 1_000_000L, Bo.NONE, "", false, "");
        Ca filtered = Ca.capture(engine, now + 3_000L, minimum);
        assertEquals("costs stay discoverable and unpriced gains stay reviewable", 2, filtered.recent.size());
        Ca.Recent mineral = null;
        for (Ca.Recent row : filtered.recent)
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
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        engine.getActiveSession().kf(
            transaction(now + 1_000L, "Vial", 1, Ai.GAIN, 3, 1, 3L), 2_000);
        engine.getActiveSession().kf(
            transaction(now + 2_000L, "Dragon bones", 1, Ai.GAIN, 2, 3_200, 6_400L), 2_000);
        long net = engine.getMetrics(now + 3_000L).net;

        Dp panel = onEdt(() ->
        {
            Dp created = new Dp(engine, PresentationLifecycleTest.config(), null);
            created.mh(flow -> !"Vial".equals(flow.itemName));
            SidebarPanelProbe.refresh(created);
            return created;
        });
        Ca snapshot = SidebarPanelProbe.live(panel);
        assertNotNull(snapshot);
        assertEquals("the filtered item is not rendered", 1, snapshot.recent.size());
        assertEquals("Dragon bones", snapshot.recent.get(0).name);
        assertEquals("the booked net is unchanged by the presentation filter", net, snapshot.net);
        assertTrue("the hidden receipt stays canonical", engine.getActiveSession().getTransactions().size() == 2);
    }

    @Test
    public void minimumValueSettingParsesPlainAndSuffixedForms() throws Exception
    {
        assertEquals(0L, Fmt.axx(null));
        assertEquals(0L, Fmt.axx(""));
        assertEquals(0L, Fmt.axx("abc"));
        assertEquals(0L, Fmt.axx("0"));
        assertEquals(3_000L, Fmt.axx("3000"));
        assertEquals(200_000L, Fmt.axx("200k"));
        assertEquals(1_500_000L, Fmt.axx("1.5m"));
        assertEquals(2_500L, Fmt.axx("2,500"));
        assertEquals(0L, Fmt.axx("-5k"));
    }

    @Test
    public void panelReadsTheCurrentMinimumSettingOnEveryRefresh() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        engine.getActiveSession().kf(
            transaction(now + 1_000L, "Cheap bones", 1, Ai.GAIN, 2, 100, 200L), 2_000);

        Dp panel = onEdt(() -> new Dp(engine, minimumConfig("500"), null));
        onEdt(() ->
        {
            SidebarPanelProbe.refresh(panel);
            return null;
        });
        assertTrue("a 500 gp minimum hides a 100 gp unit-price gain", SidebarPanelProbe.live(panel).recent.isEmpty());

        Dp open = onEdt(() -> new Dp(engine, minimumConfig("0"), null));
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

    private static Ac transaction(long at, String name, int itemId, Ai type,
        long quantity, int unitPrice, long value)
    {
        return new Ac(at, null, type, Aj.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Ab(itemId, name, quantity, unitPrice, value)),
            Bd.LIKELY, "Test sample.", null);
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
