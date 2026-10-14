package com.gpmanager;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The final Live surface: status, Net hero, breakdown, compact target, Recent Loot, Review. */
public class LivePresentationTest
{
    @Test
    public void multiRuneCastDoesNotAssignWholeNetToLeadRune() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);
        Transaction cast = new Transaction(now + 1_000L,
            TransactionType.CONSUMPTION, Context.GENERIC, "Cast Ice Burst", "Vorkath", true,
            List.of(new Flow(1, "Death rune", -1, 400, -400L),
                new Flow(2, "Chaos rune", -1, 300, -300L),
                new Flow(3, "Water rune", -1, 124, -124L)),
            Confidence.CONFIRMED, "Cast evidence.", null);
        cast.setActionKind(ActionKind.CAST);
        engine.getActiveSession().addTransaction(cast, 2_000);

        LiveSnapshot.Recent live = LiveProbe.toRecent(cast);
        assertEquals("Cast", live.name);
        assertEquals("3 rune types", live.qty);
        assertEquals(-824L, live.value);
    }

    @Test
    public void liveSnapshotReadsTheCurrentSessionFacts() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis() - 5 * 60_000L;
        engine.startCustom("Vorkath", now);
        engine.getActiveSession().addTransaction(booked(now + 1_000L, "Dragon bones", 1, 2, 3_200, 6_400), 2_000);
        engine.getActiveSession().setProfitTargetGp(1_000_000L);
        LiveSnapshot snapshot = LiveSnapshot.capture(engine, now + 60_000L, LiveContext.NONE);

        assertTrue(snapshot.hasSession);
        assertFalse(snapshot.freePlay);
        assertEquals("Vorkath", snapshot.ownerLabel);
        assertEquals(6_400L, snapshot.gains);
        assertEquals(6_400L, snapshot.net);
        assertNotNull("the current target is a supported read-only fact", snapshot.goal);
        assertEquals(1_000_000L, snapshot.goal.target);
        assertEquals(1, snapshot.recent.size());
        assertEquals("Dragon bones", snapshot.recent.get(0).name);
        assertEquals("×2", snapshot.recent.get(0).qty);
        assertFalse(snapshot.recent.get(0).unpriced);
    }

    @Test
    public void repeatedEquivalentChangesCoalesceIntoOneTruthfulRow() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);
        engine.getActiveSession().addTransaction(booked(now + 1_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000);
        engine.getActiveSession().addTransaction(booked(now + 2_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000);
        engine.getActiveSession().addTransaction(booked(now + 3_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000);
        LiveSnapshot snapshot = LiveSnapshot.capture(engine, now + 4_000L, LiveContext.NONE);

        assertEquals("three equivalent receipts render as one row", 1, snapshot.recent.size());
        LiveSnapshot.Recent row = snapshot.recent.get(0);
        assertEquals("Dragon bones", row.name);
        assertEquals("the row counts the receipts it speaks for", 3, row.receipts);
        assertEquals("quantity is summed truthfully", "\u00d73", row.qty);
        assertEquals("value is summed truthfully", 9_600L, row.value);
        assertEquals("the canonical receipts stay untouched", 3,
            engine.getActiveSession().getTransactions().size());
        assertEquals("the canonical net still counts every receipt", 9_600L, snapshot.net);
    }

    @Test
    public void coalescingNeverErasesASemanticDistinction() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);
        engine.getActiveSession().addTransaction(booked(now + 1_000L, "Dragon bones", 1, 2, 3_200, 6_400), 2_000);
        engine.getActiveSession().addTransaction(consumed(now + 2_000L, "Dragon bones", 1, -1, 3_200, -3_200), 2_000);
        engine.getActiveSession().addTransaction(booked(now + 3_000L, "Unidentified mineral", 4, 1, 50, 50), 2_000);
        engine.getActiveSession().addTransaction(unpriced(now + 4_000L, "Unidentified mineral", 4), 2_000);
        engine.getActiveSession().addTransaction(review(now + 5_000L, "Unidentified mineral", 4, 1L), 2_000);
        LiveSnapshot snapshot = LiveSnapshot.capture(engine, now + 6_000L, LiveContext.NONE);

        assertEquals("gain, cost, priced, unpriced and review all stay separate", 5, snapshot.recent.size());
        long valueSum = 0L;
        for (LiveSnapshot.Recent row : snapshot.recent)
        {
            assertEquals("every visible row is a single receipt", 1, row.receipts);
            valueSum += row.value;
        }
        assertEquals("the visible rows still sum to session net", snapshot.net, valueSum);
    }

    @Test
    public void unpricedReceiptIsMarkedUnpricedAndNeverShowsZeroAsValue() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);
        engine.getActiveSession().addTransaction(unpriced(now + 1_000L, "Unidentified mineral", 8), 2_000);
        LiveSnapshot snapshot = LiveSnapshot.capture(engine, now + 2_000L, LiveContext.NONE);

        assertEquals(1, snapshot.recent.size());
        assertTrue("the row is marked unpriced", snapshot.recent.get(0).unpriced);
        assertEquals("the booked value stays 0 and the page paints the unknown marker instead",
            0L, snapshot.recent.get(0).value);
    }

    @Test
    public void uncertainReceiptIsTaggedReviewAndCountedForTheRail() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);
        engine.getActiveSession().addTransaction(review(now + 1_000L, "Unidentified mineral", 8, 1L), 2_000);
        LiveSnapshot snapshot = LiveSnapshot.capture(engine, now + 2_000L, LiveContext.NONE);

        assertEquals(1, snapshot.reviewCount);
        assertEquals("review", snapshot.recent.get(0).tag);
    }

    @Test
    public void heroShowsOneNetAndTheRateNeverFakesZero() throws Exception
    {
        assertEquals("2.03M/h", LivePage.rateText(true, 2_030_000L));
        assertEquals("an unestablished rate is never 0", "Calculating\u2026", LivePage.rateText(false, 0L));

        LivePage page = pageWithSession(6_400L, null, false);
        assertEquals("the big line is the canonical Net", Fmt.signed(6_400L), onEdt(() -> HeroProbe.netText(page.hero)));
        assertFalse("TOTAL is gone", anyContains(labels(page), "TOTAL"));
        assertEquals("GP/h is not a strip cell", "", onEdt(() -> HeroProbe.cell(page.hero, "GP/H")));
    }

    @Test
    public void stripShowsTheVouchedSplit() throws Exception
    {
        LivePage page = pageWithSession(6_400L, null, false);
        assertEquals(Fmt.signed(6_500L), onEdt(() -> HeroProbe.cell(page.hero, "GAINS")));
        assertFalse("supplies show", onEdt(() -> HeroProbe.cell(page.hero, "SUPPLY")).isEmpty());
        assertFalse("losses show", onEdt(() -> HeroProbe.cell(page.hero, "LOSS")).isEmpty());
        assertEquals("no aggregate cell when the split is vouched for", "", onEdt(() -> HeroProbe.cell(page.hero, "COSTS")));
        assertEquals("no Trade cell without Trade activity", "", onEdt(() -> HeroProbe.cell(page.hero, "TRADE")));
    }

    @Test
    public void suppliesAndLossesNeverUseTheReviewAmber()
    {
        assertFalse(Kit.Tone.SUPPLY.color.equals(Kit.Tone.REVIEW.color));
        assertFalse(Kit.Tone.LOSS.color.equals(Kit.Tone.REVIEW.color));
    }

    @Test
    public void ribbonAndSparklineAreNotRenderedOnLive() throws Exception
    {
        LivePage page = pageWithSession(6_400L, null, false);
        assertFalse("no default sparkline", anyClassContains(page, "Sparkline"));
        assertFalse("no chart wall", anyClassContains(page, "Chart"));
    }

    @Test
    public void targetCardShowsProgressOrTheNetTotalSetsOne() throws Exception
    {
        LivePage without = pageWithSession(6_400L, null, false);
        assertTrue("the Net total says it sets a target", onEdt(() -> without.hero.net.getToolTipText()).endsWith("click to set a target"));
        assertFalse("no target row or card", anyContains(labels(without), "TARGET"));
        assertTrue("clicking the Net total sets a target", without.hero.net.getMouseListeners().length > 0);

        LivePage with = pageWithSession(6_400L, 1_000_000L, false);
        assertTrue("the target is clearly labelled", anyContains(labels(with), "TARGET · 1.00M"));
        assertTrue("the Net pair rides the hero",
            onEdt(() -> HeroProbe.netText(with.hero)).equals(Fmt.signed(6_400L) + "/1.00M"));
        assertEquals("the pair is never duplicated on the card",
            1, Collections.frequency(labels(with), Fmt.signed(6_400L) + "/1.00M"));

        LivePage big = pageWithSession(11_600_000L, 1_000_000L, false);
        assertEquals("a long pair uses the tight form",
            Fmt.signed(11_600_000L) + "/1.00M", onEdt(() -> HeroProbe.netText(big.hero)));
        assertTrue("the hero names the exact aim on hover",
            onEdt(() -> with.hero.net.getToolTipText()).contains("of 1,000,000 gp"));
        assertTrue("clicking the card edits the target", with.target.getMouseListeners().length > 0);

        // Owner 2026-09-28: Free play can hold a target too.
        Engine engine = PresentationLifecycleTest.engine();
        engine.ensureSession(System.currentTimeMillis());
        SidebarPanel panel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(engine, PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LivePage free = (LivePage) SidebarPanelProbe.livePage(panel);
        assertTrue("Free play offers a target", onEdt(() -> free.hero.net.getToolTipText()).endsWith("click to set a target"));
        engine.setTargets(500_000L, null, System.currentTimeMillis());
        onEdt(() ->
        {
            SidebarPanelProbe.refresh(panel);
            return null;
        });
        assertTrue("a Free play target shows its card", anyContains(labels(free), "TARGET · 500k"));
    }

    /** Owner 2026-10-01 (F10): a time-only target keeps its card, with tracked-time progress. */
    @Test
    public void timeOnlyTargetShowsTrackedTimeProgress() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now - 20L * 60_000L);
        engine.getActiveSession().addTransaction(booked(now - 15L * 60_000L, "Dragon bones", 1, 2,
            100_000, 200_000L), 2_000);
        engine.getActiveSession().setActiveTimeTargetMillis(5L * 3_600_000L, System.currentTimeMillis());
        SidebarPanel panel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(engine, PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LivePage page = (LivePage) SidebarPanelProbe.livePage(panel);
        assertTrue("the time target keeps its card", labels(page).contains(
            "TARGET · " + Fmt.durationShort(5L * 3_600_000L)));
        assertTrue("a fresh set starts its own count at zero", labels(page).contains(
            Fmt.durationCount(0L) + " / " + Fmt.durationShort(5L * 3_600_000L) + " · 0%"));
        assertEquals("a time target leaves the hero on the plain Net",
            Fmt.signed(200_000L), onEdt(() -> HeroProbe.netText(page.hero)));
        // Owner live 2026-10-13: the window counts from the set moment, not the run's start.
        engine.getActiveSession().activeTimeTargetSetMillis =
            engine.getActiveSession().getElapsedMillis(System.currentTimeMillis()) - 20L * 60_000L;
        onEdt(() ->
        {
            SidebarPanelProbe.refresh(panel);
            return null;
        });
        assertTrue("the window counts from when it was set", labels(page).contains(
            Fmt.durationShort(20L * 60_000L) + " / " + Fmt.durationShort(5L * 3_600_000L)
                + " · " + Fmt.percent(20L * 60_000L / (double) (5L * 3_600_000L))));
        assertTrue("the established rate projects Net at the target",
            anyContains(labels(page), "projected Net"));
    }

    /** Owner live 2026-10-13: both targets set keep the Net read and add the Active Time timer. */
    @Test
    public void bothTargetsShowTheNetReadAndActiveTimeTimer() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now - 20L * 60_000L);
        engine.getActiveSession().addTransaction(
            booked(now - 15L * 60_000L, "Dragon bones", 1, 1, 100_000, 100_000L), 2_000);
        engine.setTargets(200_000L, 5L * 3_600_000L, System.currentTimeMillis());
        // The fixtures show aims set at the run's start: baseline zero.
        engine.getActiveSession().profitTargetSetNet = 0L;
        // The Timer window is the run's last 20m: the count starts when the target is set.
        engine.getActiveSession().activeTimeTargetSetMillis =
            engine.getActiveSession().getElapsedMillis(System.currentTimeMillis()) - 20L * 60_000L;
        SidebarPanel panel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(engine, PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LivePage page = (LivePage) SidebarPanelProbe.livePage(panel);
        List<String> labels = labels(page);
        assertTrue("the Net target keeps its header",
            anyContains(labels, "TARGET · " + Fmt.compact(200_000L)));
        assertEquals("the hero carries the live Net pair",
            Fmt.signed(100_000L) + " / " + Fmt.compact(200_000L),
            onEdt(() -> HeroProbe.netText(page.hero)));
        assertEquals("the pair is never duplicated on the card",
            1, Collections.frequency(labels, Fmt.signed(100_000L) + " / " + Fmt.compact(200_000L)));
        assertTrue("the Active Time pair rides under it", labels.contains(
            Fmt.durationShort(20L * 60_000L) + " / " + Fmt.durationShort(5L * 3_600_000L)
                + " · " + Fmt.percent(20L * 60_000L / (double) (5L * 3_600_000L))));
        assertTrue("the countdown carries the projection",
            anyContains(labels, "left · ~") && anyContains(labels, "projected Net"));
    }

    /** A completed aim swaps the pace line for the Again / Done / Edit row (owner live 2026-10-13). */
    @Test
    public void bothTargetReachedStatesShowTheCompletedRow() throws Exception
    {
        // Net reached, time pending: the completed row replaces the countdown.
        Engine reachedNet = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        reachedNet.startCustom("Vorkath", now - 20L * 60_000L);
        reachedNet.getActiveSession().addTransaction(
            booked(now - 15L * 60_000L, "Dragon bones", 1, 1, 100_000, 100_000L), 2_000);
        reachedNet.setTargets(10_000L, 5L * 3_600_000L, System.currentTimeMillis());
        // The aim counts the run's net (baseline at zero), not from this mid-test save.
        reachedNet.getActiveSession().profitTargetSetNet = 0L;
        SidebarPanel netPanel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(reachedNet, PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LivePage netPage = (LivePage) SidebarPanelProbe.livePage(netPanel);
        assertTrue("a reached Net offers the completed row",
            anyContains(labels(netPage), "\u21BB") && anyContains(labels(netPage), "\u2713")
                && anyContains(labels(netPage), "\u2026"));

        // Time reached, net pending: the time line stays and the row appears too.
        Engine reachedTime = PresentationLifecycleTest.engine();
        reachedTime.startCustom("Vorkath", now - 10L * 60_000L);
        reachedTime.getActiveSession().addTransaction(
            booked(now - 5L * 60_000L, "Dragon bones", 1, 1, 100_000, 100_000L), 2_000);
        reachedTime.setTargets(200_000L, 5L * 60_000L, System.currentTimeMillis());
        // The window is the run's whole 10m, past the 5m target (the count starts at the run).
        reachedTime.getActiveSession().activeTimeTargetSetMillis = 0L;
        SidebarPanel timePanel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(reachedTime, PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LivePage timePage = (LivePage) SidebarPanelProbe.livePage(timePanel);
        assertTrue("the window clamps at its aim",
            anyContains(labels(timePage), "5m / 5m · 100%"));
        assertTrue("a reached window offers the completed row too",
            anyContains(labels(timePage), "\u21BB"));
    }

    /** Owner 2026-10-14: a reached aim keeps a short next-hour projection on the card. */
    @Test
    public void aReachedAimKeepsTheNextHourProjection() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now - 20L * 60_000L);
        engine.getActiveSession().addTransaction(
            booked(now - 15L * 60_000L, "Dragon bones", 1, 1, 100_000, 100_000L), 2_000);
        engine.setTargets(10_000L, null, System.currentTimeMillis());
        // The aim counts the run's net (baseline at zero), not from this mid-test save.
        engine.getActiveSession().profitTargetSetNet = 0L;
        SidebarPanel panel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(engine, PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LivePage page = (LivePage) SidebarPanelProbe.livePage(panel);
        assertTrue("the header still says reached", anyContains(labels(page), "reached"));
        assertTrue("a reached aim keeps the next-hour projection",
            anyContains(labels(page), "in 1h"));

        // An immature rate keeps the line blank rather than fabricating an hour.
        Engine fresh = PresentationLifecycleTest.engine();
        fresh.startCustom("Vorkath", System.currentTimeMillis() - 5_000L);
        fresh.getActiveSession().addTransaction(
            booked(System.currentTimeMillis() - 2_000L, "Dragon bones", 1, 1, 5_000, 5_000L),
            2_000);
        fresh.setTargets(1L, null, System.currentTimeMillis());
        fresh.getActiveSession().profitTargetSetNet = 0L;
        SidebarPanel freshPanel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(fresh, PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LivePage freshPage = (LivePage) SidebarPanelProbe.livePage(freshPanel);
        assertFalse("no established rate, no projection",
            anyContains(labels(freshPage), "in 1h"));
    }

    /** Owner live 2026-10-13: Again restarts the completed setup, Done clears the goal. */
    @Test
    public void completedRowRestartsOrClearsTheGoal() throws Exception
    {
        // Done clears both aims.
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now - 20L * 60_000L);
        engine.getActiveSession().addTransaction(
            booked(now - 15L * 60_000L, "Dragon bones", 1, 1, 100_000, 100_000L), 2_000);
        engine.setTargets(200_000L, null, System.currentTimeMillis());
        // The aim counts the run's net (baseline at zero), not from this mid-test save.
        engine.getActiveSession().profitTargetSetNet = 0L;
        SidebarPanel panel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(engine, PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LivePage page = (LivePage) SidebarPanelProbe.livePage(panel);
        assertFalse("a pending aim shows no completed row", onEdt(() -> page.againButton.isVisible()));

        engine.getActiveSession().addTransaction(
            booked(now - 10L * 60_000L, "Zulrah's scales", 12934, 1, 150_000, 150_000L), 2_000);
        onEdt(() ->
        {
            SidebarPanelProbe.refresh(panel);
            return null;
        });
        assertTrue("a reached aim shows the completed row", onEdt(() -> page.againButton.isVisible()));
        onEdt(() ->
        {
            page.doneButton.doClick();
            return null;
        });
        assertNull("Done clears the Net aim", engine.getActiveSession().getProfitTargetGp());
        assertNull("Done clears the timer too", engine.getActiveSession().getActiveTimeTargetMillis());

        // Again on a reached Net asks: Retain net re-bases the aim in the same run, Start fresh
        // re-runs the setup as a new instance (owner live 2026-10-14).
        Engine again = PresentationLifecycleTest.engine();
        long now2 = System.currentTimeMillis();
        again.startCustom("Zulrah", now2 - 20L * 60_000L);
        again.getActiveSession().addTransaction(
            booked(now2 - 15L * 60_000L, "Zulrah's scales", 12934, 1, 50_000, 50_000L), 2_000);
        again.setTargets(10_000L, null, now2);
        again.getActiveSession().profitTargetSetNet = 0L;
        String firstId = again.getActiveSession().getId();
        SidebarPanel retainPanel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(again, PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LivePage retainPage = (LivePage) SidebarPanelProbe.livePage(retainPanel);
        onEdt(() ->
        {
            retainPage.againButton.doClick();
            return null;
        });
        assertEquals("the fork waits for the choice", firstId, again.getActiveSession().getId());
        onEdt(() ->
        {
            ShellProbe.pressSheet(retainPanel.shell(), "Retain net");
            return null;
        });
        assertEquals("Retain net keeps the run", firstId, again.getActiveSession().getId());
        assertEquals("and re-bases the aim on the net so far", 50_000L,
            again.getActiveSession().getProfitTargetSetNet());
        assertEquals(Long.valueOf(10_000L), again.getActiveSession().getProfitTargetGp());

        // Retain leaves a running timer exactly as it was, pause included (owner live 2026-10-14).
        Engine bothRun = PresentationLifecycleTest.engine();
        long now5 = System.currentTimeMillis();
        bothRun.startCustom("Zulrah", now5 - 20L * 60_000L);
        bothRun.getActiveSession().addTransaction(
            booked(now5 - 15L * 60_000L, "Zulrah's scales", 12934, 1, 50_000, 50_000L), 2_000);
        bothRun.setTargets(10_000L, 5L * 60_000L, now5);
        bothRun.getActiveSession().profitTargetSetNet = 0L;
        bothRun.getActiveSession().pauseTimeTarget(now5);
        String bothId = bothRun.getActiveSession().getId();
        SidebarPanel bothPanel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(bothRun, PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LivePage bothPage = (LivePage) SidebarPanelProbe.livePage(bothPanel);
        onEdt(() ->
        {
            bothPage.againButton.doClick();
            return null;
        });
        onEdt(() ->
        {
            ShellProbe.pressSheet(bothPanel.shell(), "Retain net");
            return null;
        });
        assertEquals("the run stays", bothId, bothRun.getActiveSession().getId());
        assertTrue("the timer stays paused", bothRun.getActiveSession().isTimeTargetPaused());
        assertEquals("the aim re-bases", 50_000L,
            bothRun.getActiveSession().getProfitTargetSetNet());
        assertEquals("and the aim keeps its value", Long.valueOf(10_000L),
            bothRun.getActiveSession().getProfitTargetGp());

        // Reset re-bases a still-running Net aim in place without completing it (owner live
        // 2026-10-14).
        Engine reset = PresentationLifecycleTest.engine();
        long now6 = System.currentTimeMillis();
        reset.startCustom("Zulrah", now6 - 20L * 60_000L);
        reset.getActiveSession().addTransaction(
            booked(now6 - 15L * 60_000L, "Zulrah's scales", 12934, 1, 50_000, 50_000L), 2_000);
        reset.setTargets(200_000L, null, now6);
        reset.getActiveSession().profitTargetSetNet = 0L;
        String resetId = reset.getActiveSession().getId();
        SidebarPanel resetPanel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(reset, PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LivePage resetPage = (LivePage) SidebarPanelProbe.livePage(resetPanel);
        assertTrue("a running aim offers Reset", onEdt(() -> resetPage.resetButton.isVisible()));
        assertFalse("and not Again", onEdt(() -> resetPage.againButton.isVisible()));
        onEdt(() ->
        {
            resetPage.resetButton.doClick();
            return null;
        });
        assertEquals("Reset keeps the run", resetId, reset.getActiveSession().getId());
        assertEquals("restarts the aim's count at the net so far", 0L,
            reset.getActiveSession().profitTargetProgress(reset.getMetrics(now6).net));
        assertEquals("the run's net stays", 50_000L, reset.getMetrics(now6).net);

        // The other side of the fork starts the setup over as a fresh instance.
        Engine fresh = PresentationLifecycleTest.engine();
        long now4 = System.currentTimeMillis();
        fresh.startCustom("Zulrah", now4 - 20L * 60_000L);
        fresh.getActiveSession().addTransaction(
            booked(now4 - 15L * 60_000L, "Zulrah's scales", 12934, 1, 50_000, 50_000L), 2_000);
        fresh.setTargets(10_000L, null, now4);
        fresh.getActiveSession().profitTargetSetNet = 0L;
        String freshId = fresh.getActiveSession().getId();
        SidebarPanel freshPanel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(fresh, PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LivePage freshPage = (LivePage) SidebarPanelProbe.livePage(freshPanel);
        onEdt(() ->
        {
            freshPage.againButton.doClick();
            return null;
        });
        onEdt(() ->
        {
            ShellProbe.pressSheet(freshPanel.shell(), "Start fresh");
            return null;
        });
        assertNotEquals("Start fresh starts a new instance", freshId,
            fresh.getActiveSession().getId());
        assertEquals("the setup travels", "Zulrah", fresh.getActiveSession().getName());
        assertEquals("the Net aim travels", Long.valueOf(10_000L),
            fresh.getActiveSession().getProfitTargetGp());

        // The timer's own pause (owner live 2026-10-14): a running timer offers Pause; pausing
        // freezes the window and shows Done / Edit / Resume; the run itself keeps tracking.
        Engine timed = PresentationLifecycleTest.engine();
        long now3 = System.currentTimeMillis();
        timed.startCustom("Vorkath", now3 - 5L * 60_000L);
        timed.getActiveSession().setActiveTimeTargetMillis(5L * 60_000L, now3);
        SidebarPanel third = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(timed, PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LivePage thirdPage = (LivePage) SidebarPanelProbe.livePage(third);
        assertTrue("a running timer offers its cluster",
            onEdt(() -> thirdPage.targetTopActions.isVisible()));
        assertFalse("with no bottom row while it runs",
            onEdt(() -> thirdPage.targetActions.isVisible()));
        assertEquals("with the pause icon", "||", onEdt(() -> thirdPage.pauseButton.getText()));
        assertFalse("Done waits for the pause", onEdt(() -> thirdPage.doneButton.isVisible()));
        onEdt(() ->
        {
            thirdPage.pauseButton.doClick();
            return null;
        });
        assertTrue("the timer pauses", timed.getActiveSession().isTimeTargetPaused());
        assertFalse("the run itself keeps tracking", timed.getActiveSession().paused);
        onEdt(() ->
        {
            SidebarPanelProbe.refresh(third);
            return null;
        });
        assertEquals("with the resume icon", "\u25B6", onEdt(() -> thirdPage.pauseButton.getText()));
        assertTrue("Done shows while paused", onEdt(() -> thirdPage.doneButton.isVisible()));
        assertTrue("and Edit", onEdt(() -> thirdPage.editButton.isVisible()));
        assertFalse("but not Again", onEdt(() -> thirdPage.againButton.isVisible()));
        onEdt(() ->
        {
            thirdPage.pauseButton.doClick();
            return null;
        });
        assertFalse("Resume runs the timer again", timed.getActiveSession().isTimeTargetPaused());
    }

    /** Owner live 2026-10-14: a Net aim counts what the run earns after it is set. */
    @Test
    public void aNetAimCountsFromWhenItWasSet() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now - 20L * 60_000L);
        engine.getActiveSession().addTransaction(
            booked(now - 15L * 60_000L, "Dragon bones", 1, 1, 100_000, 100_000L), 2_000);
        engine.setTargets(200_000L, null, System.currentTimeMillis());
        SidebarPanel panel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(engine, PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LivePage page = (LivePage) SidebarPanelProbe.livePage(panel);
        assertEquals("the aim starts at zero", "0 / " + Fmt.compact(200_000L),
            onEdt(() -> HeroProbe.netText(page.hero)));

        engine.getActiveSession().addTransaction(
            booked(now - 10L * 60_000L, "Zulrah's scales", 12934, 1, 50_000, 50_000L), 2_000);
        onEdt(() ->
        {
            SidebarPanelProbe.refresh(panel);
            return null;
        });
        assertEquals("and counts what came after",
            Fmt.signed(50_000L) + "/" + Fmt.compact(200_000L),
            onEdt(() -> HeroProbe.netText(page.hero)));
    }

    /** Owner 2026-10-01 (F12): the Live hero names the rate window and the Active time policy. */
    @Test
    public void liveHeroNamesItsRateBasisAndActiveTimePolicy() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now - 5L * 60_000L);
        engine.getActiveSession().addTransaction(booked(now - 4L * 60_000L, "Dragon bones", 1, 2,
            100_000, 200_000L), 2_000);
        SidebarPanel panel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(engine, PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LivePage page = (LivePage) SidebarPanelProbe.livePage(panel);
        assertEquals("GP/h over active time",
            onEdt(() -> page.hero.rate.getToolTipText()));
        assertTrue("the clock names the policy: " + onEdt(() -> page.hero.clock.getToolTipText()),
            onEdt(() -> page.hero.clock.getToolTipText()).startsWith("Tracked active time"));
    }

    /** Owner 2026-09-28: GE offers still on the exchange sit above Recent, then move into it. */
    @Test
    public void openGeOffersListAboveRecentOnlyWhileOnTheExchange() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Flipping", now);
        engine.getActiveSession().addTransaction(booked(now + 1_000L, "Dragon bones", 1, 2, 2_000, 4_000L), 2_000);
        LivePage before = page(engine);
        assertFalse("no offers, no Offers table", onEdt(() -> before.offers.isVisible()));

        OfferSlots ledger = new OfferSlots();
        ledger.observe(new OfferSlots.Snapshot(0, net.runelite.api.GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0));
        engine.noteGeOffer(ledger.observe(new OfferSlots.Snapshot(0,
            net.runelite.api.GrandExchangeOfferState.SELLING, 560, 100, 0, 180, 0)).get(), "Death rune", now + 2_000L);
        LivePage open = page(engine);
        assertTrue("an offer on the exchange shows under Offers", onEdt(() -> open.offers.isVisible()));
        assertEquals(1, TableRows.of(open.offers, "OFFERS").size());
        assertTrue(TableRows.of(open.offers, "OFFERS").get(0).startsWith("Death rune"));
        assertTrue("and not in Recent", TableRows.of(open.recent, "RECENT").stream()
            .noneMatch(row -> row.startsWith("Death rune")));
    }

    /** Release pass 2026-09-28: Start in Free play, End and rename with a running Grind, paused too. */
    @Test
    public void heroControlsFollowFreePlayAndGrindStates() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ensureSession(now);
        LivePage free = page(engine);
        assertTrue("Free play offers Start", onEdt(() -> free.startGrind.isVisible()));
        assertFalse("nothing to End in Free play", onEdt(() -> free.endGrind.isVisible()));
        assertNull("Free play's name is not renamed", onEdt(() -> free.hero.name.getToolTipText()));

        engine.startCustom("Vorkath", now + 1_000L);
        LivePage grind = page(engine);
        assertFalse(onEdt(() -> grind.startGrind.isVisible()));
        assertTrue("a Grind offers End", onEdt(() -> grind.endGrind.isVisible()));
        assertEquals("Click to rename", onEdt(() -> grind.hero.name.getToolTipText()));

        engine.togglePause(now + 2_000L);
        LivePage paused = page(engine);
        assertTrue("a paused Grind can still End", onEdt(() -> paused.endGrind.isVisible()));
    }

    private static LivePage page(Engine engine) throws Exception
    {
        SidebarPanel panel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(engine, PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        return (LivePage) SidebarPanelProbe.livePage(panel);
    }

    @Test
    public void recentTableHeaderCountsRowsAndLinksToLedger() throws Exception
    {
        LivePage page = pageWithSession(6_400L, null, false);
        List<String> labels = labels(page);
        assertTrue("header copy: " + labels, labels.contains("RECENT \u00b7 2"));
        assertTrue("action copy", labels.contains("View all"));
        assertTrue("rows show their names", labels.contains("Dragon bones"));
    }

    @Test
    public void reviewIsAQuietCountIconNeverABanner() throws Exception
    {
        LivePage clean = pageWithSession(6_400L, null, false);
        assertFalse("no review icon when nothing needs a decision", anyContains(labels(clean), "(!)"));
        assertFalse(anyContains(labels(clean), "needs review"));

        LivePage review = pageWithSession(6_400L, null, true);
        assertTrue("the review icon carries the count", labels(review).contains("(!)1"));
        assertFalse("no review banner row", anyContains(labels(review), "item needs review"));
    }

    @Test
    public void logoutShowsLoggedOutWithoutAResumeAction() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);
        engine.pauseForLifecycle(now + 1_000L);
        SidebarPanel panel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(engine, PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LiveSnapshot out = SidebarPanelProbe.live(panel);
        assertTrue(out.loggedOut);
        assertEquals("LOGGED OUT", LivePage.wordOf(out));
        assertTrue(LivePage.statusTooltip(out).startsWith("Logged out"));

        engine.resume(now + 2_000L, PauseReason.LIFECYCLE);
        onEdt(() ->
        {
            SidebarPanelProbe.refresh(panel);
            return null;
        });
        assertEquals("the resume cue replaces the logged-out word (F28)", "CALIBRATING",
            LivePage.wordOf(SidebarPanelProbe.live(panel)));
    }

    @Test
    public void factoryResetStartsFromTheStatusGemLikeANewInstall() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);
        engine.resetData(now + 1_000L);
        SidebarPanel panel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(engine, PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LiveSnapshot fresh = SidebarPanelProbe.live(panel);
        assertFalse("no session after a reset", fresh.hasSession);
        assertTrue("a reset shows the pending resume countdown",
            LivePage.wordOf(fresh).startsWith("RESUMING"));
        assertTrue(LivePage.statusTooltip(fresh).startsWith("Not tracking"));

        LivePage page = (LivePage) SidebarPanelProbe.livePage(panel);
        assertTrue("the gem starts tracking", onEdt(() -> HeroProbe.dotClickable(page.hero)));
        assertFalse("the gem is the start control; no Start button below the hero",
            onEdt(() -> LivePageProbe.hasTrackingButton(page.body())));
        onEdt(() ->
        {
            page.hero.onDot.run();
            return null;
        });
        assertTrue("the gem begins Free play", engine.getActiveSession() != null && !engine.getActiveSession().paused);
    }

    @Test
    public void aManualPauseResumesFromTheGemWithoutATrackingButton() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);
        engine.togglePause(now + 1_000L);
        SidebarPanel panel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(engine, PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LivePage page = (LivePage) SidebarPanelProbe.livePage(panel);
        assertTrue(engine.getActiveSession().paused);
        assertTrue(onEdt(() -> HeroProbe.dotClickable(page.hero)));
        assertFalse(onEdt(() -> LivePageProbe.hasTrackingButton(page.body())));
        onEdt(() ->
        {
            page.hero.onDot.run();
            return null;
        });
        assertFalse("the gem resumes the same Grind", engine.getActiveSession().paused);
        assertEquals("Vorkath", engine.getActiveSession().getName());
    }

    @Test
    public void liveNamesTheRunAndFreePlayKeepsItsActivity() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);

        LiveContext target = new LiveContext(false, null, 0L, PvpState.NONE, "Woodcutting", false, "Goblin");
        assertEquals("the named run, never the activity", "Vorkath",
            LivePage.identityOf(LiveSnapshot.capture(engine, now + 1_000L, target)));

        Engine free = PresentationLifecycleTest.engine();
        free.ensureSession(now);
        // Owner live 2026-10-14: free play shows the detected activity label ("Trade").
        LiveContext trade = new LiveContext(false, null, 0L, PvpState.NONE, "Trade", false, "");
        assertEquals("Trade", LivePage.identityOf(LiveSnapshot.capture(free, now + 1_000L, trade)));
        LiveContext quiet = new LiveContext(false, null, 0L, PvpState.NONE, "", false, "");
        assertEquals("", LivePage.identityOf(LiveSnapshot.capture(free, now + 1_000L, quiet)));
    }

    @Test
    public void idleKeepsTheGrindIdentity() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);
        SidebarPanel panel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(engine, PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        assertEquals("plain running needs no status word", "", LivePage.wordOf(SidebarPanelProbe.live(panel)));
        engine.getActiveSession().pause(now + 1_000L, PauseReason.IDLE);
        onEdt(() ->
        {
            SidebarPanelProbe.refresh(panel);
            return null;
        });
        LiveSnapshot idle = SidebarPanelProbe.live(panel);
        assertTrue(idle.idle);
        assertEquals("AWAY", LivePage.wordOf(idle));
        assertEquals("idle never destroys the grind identity", "Vorkath", LivePage.identityOf(idle));
        assertTrue(LivePage.statusTooltip(idle).startsWith("Waiting for activity"));
    }

    @Test
    public void noSessionShowsDashesNeverZeros() throws Exception
    {
        LivePage page = onEdt(() ->
        {
            SidebarPanel panel = new SidebarPanel(PresentationLifecycleTest.engine(),
                PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.refresh(panel);
            return (LivePage) SidebarPanelProbe.livePage(panel);
        });
        List<String> labels = labels(page);
        assertFalse("nothing going on shows no name", anyContains(labels, "Free play") || labels.contains("Just play."));
        assertEquals("\u2014", onEdt(() -> HeroProbe.netText(page.hero)));
        assertEquals("\u2014", onEdt(() -> HeroProbe.cell(page.hero, "GAINS")));
        assertTrue("the empty card names the tracking trigger (F13)",
            labels.contains("Tracking starts with your first gameplay"));
        assertFalse("no fake goal state", anyContains(labels, "TARGET"));
        assertFalse("no fake review state", anyContains(labels, "(!)"));
    }

    @Test
    public void loggedOutWithoutAGrindJustSaysLoggedOut() throws Exception
    {
        LivePage page = onEdt(() ->
        {
            SidebarPanel panel = new SidebarPanel(PresentationLifecycleTest.engine(),
                PresentationLifecycleTest.config(), null);
            panel.bindLoggedIn(() -> false);
            SidebarPanelProbe.refresh(panel);
            assertEquals("LOGGED OUT", LivePage.wordOf(SidebarPanelProbe.live(panel)));
            return (LivePage) SidebarPanelProbe.livePage(panel);
        });
        List<String> labels = labels(page);
        assertFalse("no status name beside the word", anyContains(labels, "Free play"));
        assertFalse(labels.contains("Log in to start tracking"));
    }

    @Test
    public void pvpFactsStayAvailableAndSafeStateNeverRestructuresTheSurface() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Wilderness", now);
        engine.getActiveSession().addTransaction(booked(now + 1_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000);
        engine.getActiveSession().addEncounter(false, now + 1_500L);

        SidebarPanel panel = onEdt(() -> new SidebarPanel(engine, PresentationLifecycleTest.config(), null));
        onEdt(() ->
        {
            panel.bindPvp(() -> new PvpState(true, true, true, 0L));
            SidebarPanelProbe.refresh(panel);
            return null;
        });
        LiveSnapshot dangerous = SidebarPanelProbe.live(panel);
        assertTrue("client-thread sample reaches presentation", dangerous.skulled);
        assertTrue(dangerous.protectItem);
        assertTrue("a session with a fight keeps its PvP grind context", dangerous.pvpSession);
        assertEquals("kills/deaths facts stay readable", 0, dangerous.kills);

        onEdt(() ->
        {
            panel.bindPvp(() -> new PvpState(false, false, false, 0L));
            SidebarPanelProbe.refresh(panel);
            return null;
        });
        LiveSnapshot safe = SidebarPanelProbe.live(panel);
        assertFalse(safe.skulled);
        assertTrue("entering a safe boundary never reclassifies the grind", safe.pvpSession);
        assertEquals("net is stable across the boundary", dangerous.net, safe.net);
    }

    // ---- helpers ----

    private static LivePage pageWithSession(long net, @Nullable Long target, boolean review) throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);
        long gains = Math.abs(net) + 100L;
        engine.getActiveSession().addTransaction(booked(now + 1_000L, "Dragon bones", 1, 2,
            (int) Math.max(1L, gains / 2), gains), 2_000);
        engine.getActiveSession().addTransaction(consumed(now + 2_000L, "Prayer potion(4)", 3, -1, 100, -100L), 2_000);
        if (target != null)
        {
            engine.getActiveSession().setProfitTargetGp(target);
        }
        if (review)
        {
            engine.getActiveSession().addTransaction(review(now + 3_000L, "Unidentified mineral", 8, 1L), 2_000);
        }
        SidebarPanel panel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(engine, PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        return (LivePage) SidebarPanelProbe.livePage(panel);
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

    private static Transaction booked(long at, String name, int itemId, long quantity, int unitPrice, long value)
    {
        return new Transaction(at, TransactionType.GAIN, Context.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Flow(itemId, name, quantity, unitPrice, value)),
            Confidence.LIKELY, "Test sample.", null);
    }

    private static Transaction consumed(long at, String name, int itemId, long quantity, int unitPrice, long value)
    {
        return new Transaction(at, TransactionType.CONSUMPTION, Context.GENERIC, "", "Vorkath",
            true, Collections.singletonList(new Flow(itemId, name, quantity, unitPrice, value)),
            Confidence.LIKELY, "Test sample.", null);
    }

    private static Transaction review(long at, String name, int itemId, long quantity)
    {
        return new Transaction(at, TransactionType.UNCERTAIN, Context.GENERIC, "", "Vorkath",
            true, Collections.singletonList(new Flow(itemId, name, quantity, 0, 0L)),
            Confidence.UNCERTAIN, "Test sample: awaiting a decision.", null);
    }

    private static Transaction unpriced(long at, String name, int itemId)
    {
        return new Transaction(at, TransactionType.GAIN, Context.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Flow(itemId, name, 8, 0, 0L)),
            Confidence.LIKELY, "Test sample.", null);
    }

    /** Both painted regions of a Live page: its bar and its body. */
    private static List<Component> roots(LivePage page)
    {
        List<Component> roots = new ArrayList<>();
        if (page.pageBar() != null)
        {
            roots.add(page.pageBar());
        }
        roots.add(page.body());
        return roots;
    }

    private static List<String> labels(LivePage page)
    {
        List<String> out = new ArrayList<>();
        for (Component root : roots(page))
        {
            walkLabels(root, out);
        }
        return out;
    }

    private static void walkLabels(Component component, List<String> out)
    {
        if (!component.isVisible())
        {
            return;
        }
        if (component instanceof JLabel || component instanceof javax.swing.AbstractButton)
        {
            String text = component instanceof JLabel ? ((JLabel) component).getText()
                : ((javax.swing.AbstractButton) component).getText();
            if (text != null && !text.isEmpty())
            {
                out.add(text);
            }
        }
        if (component instanceof Container)
        {
            for (Component child : ((Container) component).getComponents())
            {
                walkLabels(child, out);
            }
        }
    }

    private static boolean contains(LivePage page, Class<?> type)
    {
        for (Component root : roots(page))
        {
            if (containsIn(root, type))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean containsIn(Component root, Class<?> type)
    {
        if (type.isInstance(root))
        {
            return true;
        }
        if (root instanceof Container)
        {
            for (Component child : ((Container) root).getComponents())
            {
                if (containsIn(child, type))
                {
                    return true;
                }
            }
        }
        return false;
    }

    private static <T> T find(LivePage page, Class<T> type)
    {
        for (Component root : roots(page))
        {
            T found = findIn(root, type);
            if (found != null)
            {
                return found;
            }
        }
        return null;
    }

    private static <T> T findIn(Component root, Class<T> type)
    {
        if (type.isInstance(root))
        {
            return type.cast(root);
        }
        if (root instanceof Container)
        {
            for (Component child : ((Container) root).getComponents())
            {
                T found = findIn(child, type);
                if (found != null)
                {
                    return found;
                }
            }
        }
        return null;
    }

    private static int count(LivePage page, Class<?> type)
    {
        int total = 0;
        for (Component root : roots(page))
        {
            total += countIn(root, type);
        }
        return total;
    }

    private static int countIn(Component root, Class<?> type)
    {
        int total = type.isInstance(root) ? 1 : 0;
        if (root instanceof Container)
        {
            for (Component child : ((Container) root).getComponents())
            {
                total += countIn(child, type);
            }
        }
        return total;
    }

    private static boolean anyClassContains(LivePage page, String needle)
    {
        for (Component root : roots(page))
        {
            if (anyClassContainsIn(root, needle))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean anyClassContainsIn(Component root, String needle)
    {
        if (root.getClass().getSimpleName().contains(needle))
        {
            return true;
        }
        if (root instanceof Container)
        {
            for (Component child : ((Container) root).getComponents())
            {
                if (anyClassContainsIn(child, needle))
                {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean anyContains(List<String> labels, String needle)
    {
        for (String label : labels)
        {
            if (label.contains(needle))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean anyAccessibleContains(LivePage page, String needle)
    {
        for (Component root : roots(page))
        {
            if (anyAccessibleContainsIn(root, needle))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean anyAccessibleContainsIn(Component root, String needle)
    {
        if (root.getAccessibleContext() != null)
        {
            String name = root.getAccessibleContext().getAccessibleName();
            String description = root.getAccessibleContext().getAccessibleDescription();
            if ((name != null && name.contains(needle)) || (description != null && description.contains(needle)))
            {
                return true;
            }
        }
        if (root instanceof Container)
        {
            for (Component child : ((Container) root).getComponents())
            {
                if (anyAccessibleContainsIn(child, needle))
                {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    public void activityNamesUseTheCuratedShortForms()
    {
        assertEquals("KBD", Fmt.activity("King Black Dragon"));
        assertEquals("GE Clerk", Fmt.activity("Grand Exchange Clerk"));
        assertEquals("KQ", Fmt.activity("kalphite queen"));
        assertEquals("ToB (HM)", Fmt.activity("Theatre of Blood (Hard Mode)"));
        assertEquals("CoX (CM)", Fmt.activity("Chambers of Xeric (Challenge Mode)"));
        assertEquals("ToA (Expert)", Fmt.activity("Tombs of Amascut (Expert Mode)"));
        assertEquals("GotR", Fmt.activity("Guardians of the Rift"));
        assertEquals("Nightmare", Fmt.activity("The Nightmare"));
        assertEquals("Hofuthand", Fmt.activity("Hofuthand (weapons and armor)"));
        assertEquals("Vorkath", Fmt.activity("Vorkath"));
    }
}
