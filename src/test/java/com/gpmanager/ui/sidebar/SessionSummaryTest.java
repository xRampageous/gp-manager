package com.gpmanager;

import com.google.gson.Gson;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import javax.swing.JMenuItem;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static com.gpmanager.MilestoneTest.onEdt;
import static com.gpmanager.TargetFreshStartTest.*;
import static org.junit.Assert.*;

/** Session sharing copies presentation facts locally and cannot mutate booked money. */
public class SessionSummaryTest
{
    LongSupplier clock;
    @Before public void setUp() { clock = SafeMath.clock; JsonCodec.bind(new Gson()); }
    @After public void tearDown() { SafeMath.clock = clock; }

    @Test public void copyLiveUsesItsCurrentReceiptWindowAndRestartedClock() throws Exception
    {
        onEdt(() ->
        {
            Engine engine = SavedSessionTest.window();
            engine.restartRate(2 * MINUTE, true);
            SafeMath.clock = () -> 3 * MINUTE;
            SidebarPanel panel = RateRestartTest.panel(engine);
            AtomicReference<String> copied = new AtomicReference<>();
            GrindsController controller = controller(panel, copied);
            String before = new Gson().toJson(engine.exportState());
            copyLive(controller);
            assertTrue(copied.get(), copied.get().contains("Net: +500 gp"));
            assertTrue(copied.get().contains("Active time: 1:00"));
            assertTrue(copied.get().contains("GP/h: 30,000"));
            assertEquals(before, new Gson().toJson(engine.exportState()));
            return null;
        });
    }

    @Test public void savedSummaryRemainsFrozenAfterMoreBookings() throws Exception
    {
        onEdt(() ->
        {
            Engine engine = SavedSessionTest.window();
            Session source = engine.session();
            String id = EngineProbe.saveLiveSession(engine, source, source.sessionStartAt, 2 * MINUTE);
            coins(engine, 2 * MINUTE + 5, 900L);
            SafeMath.clock = () -> 4 * MINUTE;
            SidebarPanel panel = RateRestartTest.panel(engine);
            AtomicReference<String> copied = new AtomicReference<>();
            GrindsController controller = controller(panel, copied);
            GrindsPage page = new GrindsPage(controller);
            page.apply(GrindsData.capture(engine, 4 * MINUTE, false, null));
            Table.Row row = page.saved.rows.stream().filter(item -> item.key.equals(id)).findFirst().orElseThrow();
            Table.Menu copy = row.menu.stream().filter(item -> item.label.equals("Copy summary")).findFirst().orElse(null);
            assertNotNull("saved session row offers Copy summary", copy);
            long allTime = GrindsData.allTime(engine, 4 * MINUTE).net;
            copy.action.run();
            assertTrue(copied.get(), copied.get().contains("Net: +500 gp"));
            assertTrue(copied.get().contains("Active time: 1:00"));
            assertTrue(copied.get().contains("GP/h: 30,000"));
            assertEquals(allTime, GrindsData.allTime(engine, 4 * MINUTE).net);
            return null;
        });
    }

    @Test public void aShortLiveWindowKeepsCalculatingAndNegativeNetUsesExactFigures() throws Exception
    {
        onEdt(() ->
        {
            Engine engine = engine();
            coins(engine, 1L, -12_345L);
            SafeMath.clock = () -> 10_000L;
            AtomicReference<String> copied = new AtomicReference<>();
            copyLive(controller(RateRestartTest.panel(engine), copied));
            assertTrue(copied.get(), copied.get().contains("Net: −12,345 gp"));
            assertTrue(copied.get().contains("GP/h: Calculating"));
            return null;
        });
    }

    @Test public void liveSummaryIncludesTheSeparatelyDisplayedMarketResult() throws Exception
    {
        onEdt(() ->
        {
            Engine engine = engine();
            engine.session().addTransaction(SavedSessionTest.trade(1L), 2_000, false);
            SafeMath.clock = () -> MINUTE;
            AtomicReference<String> copied = new AtomicReference<>();
            copyLive(controller(RateRestartTest.panel(engine), copied));
            assertTrue(copied.get(), copied.get().contains("Market: +100 gp"));
            assertTrue(copied.get().contains("Gains: 0 gp"));
            assertTrue(copied.get().contains("Net: +100 gp"));
            return null;
        });
    }

    @Test public void emptyProfileDisablesLiveCopy() throws Exception
    {
        onEdt(() ->
        {
            Engine engine = engine();
            engine.resetData(0L);
            assertNull(engine.session());
            JMenuItem copy = liveItem(controller(RateRestartTest.panel(engine), new AtomicReference<>()));
            assertFalse(copy.isEnabled());
            return null;
        });
    }

    @Test public void supplyTooltipShowsTheUncappedShareOfBookedGains()
    {
        assertTrue(Hero.money(1_000, 180, 20, 200, true, null).get(1).tip.contains("18.0% of booked Gains"));
        assertTrue(Hero.money(100, 150, 0, 150, true, null).get(1).tip.contains("150.0% of booked Gains"));
        assertTrue(Hero.money(1_000, 0, 0, 0, true, null).get(1).tip.contains("0.0% of booked Gains"));
    }

    @Test public void unavailableDenominatorsAndCostSplitsNeverInventAPercentage()
    {
        assertFalse(Hero.money(0, 50, 0, 50, true, null).get(1).tip.contains("%"));
        assertFalse(Hero.money(-1, 50, 0, 50, true, null).get(1).tip.contains("%"));
        assertFalse(Hero.money(100, -1, 0, 50, true, null).get(1).tip.contains("%"));
        assertEquals("COSTS", Hero.money(100, -1, -1, 50, false, null).get(1).label);
        assertFalse(Hero.money(100, -1, -1, 50, false, null).get(1).tip.contains("%"));
    }

    @Test public void supplyShareUsesFloatingPointBeforeMultiplicationAndKeepsValuesUnchanged()
    {
        Hero.Cell supply = Hero.money(Long.MAX_VALUE, Long.MAX_VALUE, 0, Long.MAX_VALUE, true, null).get(1);
        assertTrue(supply.tip, supply.tip.contains("100.0% of booked Gains"));
        assertEquals(Fmt.signed(-Long.MAX_VALUE), supply.value);
    }

    @Test public void aStaleSavedRowCannotCopyAfterItsSessionWasDeleted() throws Exception
    {
        onEdt(() ->
        {
            Engine engine = SavedSessionTest.window();
            Session source = engine.session();
            String id = EngineProbe.saveLiveSession(engine, source, source.sessionStartAt, 2 * MINUTE);
            AtomicReference<String> copied = new AtomicReference<>("unchanged");
            GrindsController controller = controller(RateRestartTest.panel(engine), copied);
            assertTrue(engine.deleteHistory(id));
            controller.copySummary(id);
            assertEquals("unchanged", copied.get());
            return null;
        });
    }

    @Test public void clipboardFailureReportsLocallyAndKeepsTheSession() throws Exception
    {
        onEdt(() ->
        {
            Engine engine = SavedSessionTest.window();
            SidebarPanel panel = RateRestartTest.panel(engine);
            SafeMath.clock = () -> 2 * MINUTE;
            GrindsController controller = new GrindsController(panel)
            {
                @Override void copyText(String text) { throw new IllegalStateException("clipboard busy"); }
            };
            copyLive(controller);
            assertEquals("Copy failed · clipboard busy", panel.shell.message.getToolTipText());
            assertEquals(1_500L, engine.metrics(2 * MINUTE).net);
            return null;
        });
    }

    @Test public void summariesKeepUnavailableCostSplitsUnsplit()
    {
        String text = GrindsController.summary("Old run", 50, 100, 50, -1, -1, false, MINUTE);
        assertTrue(text.contains("Costs: 50 gp"));
        assertFalse(text.contains("Supplies:"));
        assertFalse(text.contains("Losses:"));
    }

    static GrindsController controller(SidebarPanel panel, AtomicReference<String> copied)
    {
        return new GrindsController(panel)
        {
            @Override void copyText(String text) { copied.set(text); }
        };
    }

    static JMenuItem liveItem(GrindsController controller)
    {
        for (java.awt.Component component : controller.dataMenu().getComponents())
            if (component instanceof JMenuItem && "Copy Live summary".equals(((JMenuItem) component).getText()))
                return (JMenuItem) component;
        fail("gear menu offers Copy Live summary");
        return null;
    }

    static void copyLive(GrindsController controller) { liveItem(controller).doClick(); }
}
