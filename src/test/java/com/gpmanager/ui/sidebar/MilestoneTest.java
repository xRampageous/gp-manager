package com.gpmanager;

import static com.gpmanager.TargetFreshStartTest.HOUR;
import static com.gpmanager.TargetFreshStartTest.coins;
import static com.gpmanager.TargetFreshStartTest.live;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Owner 1.5.2: Free play's Net milestone counts all of Free play; a notice says when it is crossed. */
public class MilestoneTest
{
    static LivePage page()
    {
        LivePage.Actions actions = (LivePage.Actions) java.lang.reflect.Proxy.newProxyInstance(
            LivePage.Actions.class.getClassLoader(), new Class<?>[] {LivePage.Actions.class},
            (proxy, method, args) -> method.getReturnType() == boolean.class ? false : null);
        return new LivePage(actions, id -> null);
    }

    @Test
    public void itCountsAllOfFreePlayWhileATargetCountsFresh()
    {
        Engine engine = TargetFreshStartTest.engine();
        coins(engine, 5L, 400_000_000L);
        assertTrue(engine.setMilestone(1_000_000_000L));
        engine.setTargets(5_000_000L, null, HOUR);
        LiveSnapshot live = live(engine, HOUR);
        assertEquals("the target counts fresh", 0L, live.net);
        assertEquals("the milestone counts all of Free play", 400_000_000L, live.runNet);
        assertFalse(live.milestoneReached());

        coins(engine, HOUR + 5L, 600_000_000L);
        assertTrue(live(engine, HOUR + 10L).milestoneReached());
    }

    @Test
    public void aGrindLeavesItAloneAndCannotSetIt()
    {
        Engine engine = TargetFreshStartTest.engine();
        engine.setMilestone(1_000_000_000L);
        engine.startCustom("Vorkath", HOUR);
        assertNull(live(engine, HOUR).milestone);
        assertFalse(engine.setMilestone(5_000_000_000L));
        assertEquals(Long.valueOf(1_000_000_000L), engine.getGeneralSession().milestoneGp);
    }

    /** Owner 1.5.2: no bar and no HUD+ line; one small notice when it is crossed while playing. */
    @Test
    public void crossingItShowsOneNoticeButNotAtLogin() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ensureSession(now - HOUR);
        engine.setMilestone(1_000L);
        coins(engine, now - 10_000L, 600L);
        SidebarPanel panel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        assertFalse(ShellProbe.noticeText(panel.shell()).contains("Milestone"));
        coins(engine, now - 5_000L, 600L);
        assertTrue(onEdt(() ->
        {
            SidebarPanelProbe.refresh(panel);
            return ShellProbe.noticeText(panel.shell());
        }).startsWith("Milestone reached"));

        SidebarPanel login = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        assertFalse("already reached at login: no notice",
            ShellProbe.noticeText(login.shell()).contains("Milestone"));
    }

    /** The HUD+ kill-streak line measures the whole run, so a target start never moves it. */
    @Test
    public void aTargetStartLeavesTheHudNetBaseWhole()
    {
        Engine engine = TargetFreshStartTest.engine();
        coins(engine, 5L, 1_000L);
        HudBuilder builder = new HudBuilder(HudBuilderTest.config(false, 4), null);
        builder.update(live(engine, 10L), f -> true, 10L);
        engine.setTargets(5_000_000L, null, 20L);
        builder.update(live(engine, 30L), f -> true, 30L);
        assertEquals(1_000L, builder.lastNet);
    }

    @Test
    public void theTargetsSheetOffersItInFreePlayOnly() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        engine.ensureSession(System.currentTimeMillis());
        SidebarPanel panel = onEdt(() -> new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null));
        Shell shell = panel.shell();
        GrindsController grinds = new GrindsController(panel);
        assertTrue("a milestone pick", onEdt(() ->
        {
            grinds.openTargets();
            boolean picked = ShellProbe.pressSheetText(shell, "5b");
            ShellProbe.pressSheet(shell, "Save");
            return picked;
        }));
        assertEquals(Long.valueOf(5_000_000_000L), engine.getGeneralSession().milestoneGp);
        assertNull("a milestone alone sets no target", engine.getActiveSession().getProfitTargetGp());
        assertNull(engine.getActiveSession().sessionStartAt);

        engine.startCustom("Vorkath", System.currentTimeMillis());
        assertFalse("no milestone in a Grind", onEdt(() ->
        {
            grinds.openTargets();
            return ShellProbe.pressSheetText(shell, "5b");
        }));
    }

    static <T> T onEdt(java.util.concurrent.Callable<T> callable) throws Exception
    {
        java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Exception> failure = new java.util.concurrent.atomic.AtomicReference<>();
        javax.swing.SwingUtilities.invokeAndWait(() ->
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

    /** While Live counts fresh, the line under the Net says what the run holds. */
    @Test
    public void underTheNetLiveSaysWhatTheRunHolds() throws Exception
    {
        Engine engine = TargetFreshStartTest.engine();
        coins(engine, 5L, 420_000_000L);
        LivePage page = page();
        assertFalse("the whole run needs no total line", onEdt(() ->
        {
            page.apply(live(engine, HOUR));
            return page.runTotal.isVisible();
        }));
        engine.setTargets(5_000_000L, null, HOUR);
        LiveSnapshot live = live(engine, HOUR);
        assertEquals("Free play " + Fmt.signed(420_000_000L), onEdt(() ->
        {
            page.apply(live);
            return page.runTotal.isVisible() ? page.runTotal.getText() : "";
        }));
        assertEquals("Live itself counts fresh", 0L, live.net);
    }

    @Test
    public void aFactoryResetClearsIt()
    {
        Engine engine = TargetFreshStartTest.engine();
        engine.setMilestone(1_000_000_000L);
        engine.resetData(HOUR);
        engine.ensureSession(HOUR);
        assertNull(engine.getGeneralSession().milestoneGp);
    }
}
