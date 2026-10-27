package com.gpmanager;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import javax.swing.JLabel;
import org.junit.Test;

import static com.gpmanager.SwingTestSupport.onEdt;
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
        cast.setAction(ActionKind.CAST);
        engine.session().addTransaction(cast, 2_000, false);

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
        engine.session().addTransaction(booked(now + 1_000L, "Dragon bones", 1, 2, 3_200, 6_400), 2_000, false);
        engine.session().setNetTarget(1_000_000L, 0, 0);
        LiveSnapshot snapshot = LiveSnapshot.capture(engine, now + 60_000L, LiveContext.NONE);

        assertTrue(snapshot.present);
        assertFalse(snapshot.general);
        assertEquals("Vorkath", snapshot.ownerLabel);
        assertEquals(6_400L, snapshot.income);
        assertEquals(6_400L, snapshot.net);
        assertNotNull("the current target is a supported read-only fact", snapshot.goal);
        assertEquals(1_000_000L, snapshot.goal.target);
        assertEquals(1, snapshot.recent.size());
        assertEquals("Dragon bones", snapshot.recent.get(0).name);
        assertEquals("×2", snapshot.recent.get(0).qty);
        assertFalse(snapshot.recent.get(0).unknown);
    }

    @Test
    public void repeatedEquivalentChangesCoalesceIntoOneTruthfulRow() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);
        engine.session().addTransaction(booked(now + 1_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000, false);
        engine.session().addTransaction(booked(now + 2_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000, false);
        engine.session().addTransaction(booked(now + 3_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000, false);
        LiveSnapshot snapshot = LiveSnapshot.capture(engine, now + 4_000L, LiveContext.NONE);

        assertEquals("three equivalent receipts render as one row", 1, snapshot.recent.size());
        LiveSnapshot.Recent row = snapshot.recent.get(0);
        assertEquals("Dragon bones", row.name);
        assertEquals("the row counts the receipts it speaks for", 3, row.entries);
        assertEquals("quantity is summed truthfully", "\u00d73", row.qty);
        assertEquals("value is summed truthfully", 9_600L, row.value);
        assertEquals("the canonical receipts stay untouched", 3,
            engine.session().transactions().size());
        assertEquals("the canonical net still counts every receipt", 9_600L, snapshot.net);
    }

    @Test
    public void coalescingNeverErasesASemanticDistinction() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);
        engine.session().addTransaction(booked(now + 1_000L, "Dragon bones", 1, 2, 3_200, 6_400), 2_000, false);
        engine.session().addTransaction(consumed(now + 2_000L, "Dragon bones", 1, -1, 3_200, -3_200), 2_000, false);
        engine.session().addTransaction(booked(now + 3_000L, "Unidentified mineral", 4, 1, 50, 50), 2_000, false);
        engine.session().addTransaction(unpriced(now + 4_000L, "Unidentified mineral", 4), 2_000, false);
        engine.session().addTransaction(review(now + 5_000L, "Unidentified mineral", 4, 1L), 2_000, false);
        LiveSnapshot snapshot = LiveSnapshot.capture(engine, now + 6_000L, LiveContext.NONE);

        assertEquals("gain, cost, priced, unpriced and review all stay separate", 5, snapshot.recent.size());
        long valueSum = 0L;
        for (LiveSnapshot.Recent row : snapshot.recent)
        {
            assertEquals("every visible row is a single receipt", 1, row.entries);
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
        engine.session().addTransaction(unpriced(now + 1_000L, "Unidentified mineral", 8), 2_000, false);
        LiveSnapshot snapshot = LiveSnapshot.capture(engine, now + 2_000L, LiveContext.NONE);

        assertEquals(1, snapshot.recent.size());
        assertTrue("the row is marked unpriced", snapshot.recent.get(0).unknown);
        assertEquals("the booked value stays 0 and the page paints the unknown marker instead",
            0L, snapshot.recent.get(0).value);
    }

    @Test
    public void uncertainReceiptIsTaggedReviewAndCountedForTheRail() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);
        engine.session().addTransaction(review(now + 1_000L, "Unidentified mineral", 8, 1L), 2_000, false);
        LiveSnapshot snapshot = LiveSnapshot.capture(engine, now + 2_000L, LiveContext.NONE);

        assertEquals(1, snapshot.reviews);
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
        assertTrue("the Net total says it sets a target", onEdt(() -> without.hero.net.getToolTipText()).contains("click to set a target"));
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
            SidebarPanel created = new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LivePage free = (LivePage) SidebarPanelProbe.livePage(panel);
        assertTrue("Free play offers a target", onEdt(() -> free.hero.net.getToolTipText()).contains("click to set a target"));
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
        engine.session().addTransaction(booked(now - 15L * 60_000L, "Dragon bones", 1, 2,
            100_000, 200_000L), 2_000, false);
        engine.session().setTimeTarget(5L * 3_600_000L, System.currentTimeMillis());
        SidebarPanel panel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LivePage page = (LivePage) SidebarPanelProbe.livePage(panel);
        // Owner 1.5.2: the time is said once, "5h 00m left of 5h 00m · 0%".
        assertTrue("the time target keeps its card", labels(page).contains("TARGET"));
        assertTrue("a fresh set starts its own count at zero", anyContains(labels(page),
            " left of " + Fmt.durationShort(5L * 3_600_000L) + " · 0%"));
        assertEquals("a time target leaves the hero on the plain Net",
            Fmt.signed(200_000L), onEdt(() -> HeroProbe.netText(page.hero)));
        // Owner live 2026-10-13: the window counts from the set moment, not the run's start.
        engine.session().activeTimeTargetSetMillis =
            engine.session().elapsed(System.currentTimeMillis()) - 20L * 60_000L;
        onEdt(() ->
        {
            SidebarPanelProbe.refresh(panel);
            return null;
        });
        assertTrue("the window counts from when it was set", anyContains(labels(page),
            " left of " + Fmt.durationShort(5L * 3_600_000L) + " · " + Fmt.percent(20L * 60_000L / (double) (5L * 3_600_000L))));
        assertTrue("one line says it", labels(page).stream().noneMatch(label -> label.endsWith(" left")));
        assertFalse("with no projected Net", anyContains(labels(page), "projected"));
    }

    /** Owner live 2026-10-13: both targets set keep the Net read and add the Active Time timer. */
    @Test
    public void bothTargetsShowTheNetReadAndActiveTimeTimer() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now - 20L * 60_000L);
        engine.session().addTransaction(
            booked(now - 15L * 60_000L, "Dragon bones", 1, 1, 100_000, 100_000L), 2_000, false);
        engine.setTargets(200_000L, 5L * 3_600_000L, System.currentTimeMillis());
        // The fixtures show aims set at the run's start: baseline zero.
        engine.session().profitTargetSetNet = 0L;
        engine.session().sessionStartAt = null;
        engine.session().profitTargetSetActiveMillis = 0L;
        // The Timer window is the run's last 20m: the count starts when the target is set.
        engine.session().activeTimeTargetSetMillis =
            engine.session().elapsed(System.currentTimeMillis()) - 20L * 60_000L;
        SidebarPanel panel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null);
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
        assertTrue("the time line rides under it", anyContains(labels,
            " left of " + Fmt.durationShort(5L * 3_600_000L) + " · " + Fmt.percent(20L * 60_000L / (double) (5L * 3_600_000L))));
        assertTrue("said once, with no projection", labels.stream().noneMatch(label -> label.endsWith(" left"))
            && !anyContains(labels, "projected"));
    }

    /** A completed aim swaps the pace line for the Again / Done / Edit row (owner live 2026-10-13). */
    @Test
    public void bothTargetReachedStatesShowTheCompletedRow() throws Exception
    {
        // Net reached, time pending: the completed row replaces the countdown.
        Engine reachedNet = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        reachedNet.startCustom("Vorkath", now - 20L * 60_000L);
        reachedNet.session().addTransaction(
            booked(now - 15L * 60_000L, "Dragon bones", 1, 1, 100_000, 100_000L), 2_000, false);
        reachedNet.setTargets(10_000L, 5L * 3_600_000L, System.currentTimeMillis());
        // The aim counts the run's net (baseline at zero), not from this mid-test save.
        reachedNet.session().profitTargetSetNet = 0L;
        reachedNet.session().sessionStartAt = null;
        reachedNet.session().profitTargetSetActiveMillis = 0L;
        SidebarPanel netPanel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(reachedNet, PresentationLifecycleTest.config(), null, null, null);
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
        reachedTime.session().addTransaction(
            booked(now - 5L * 60_000L, "Dragon bones", 1, 1, 100_000, 100_000L), 2_000, false);
        reachedTime.setTargets(200_000L, 5L * 60_000L, System.currentTimeMillis());
        // The window is the run's whole 10m, past the 5m target (the count starts at the run).
        reachedTime.session().activeTimeTargetSetMillis = 0L;
        reachedTime.session().sessionStartAt = null;
        SidebarPanel timePanel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(reachedTime, PresentationLifecycleTest.config(), null, null, null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LivePage timePage = (LivePage) SidebarPanelProbe.livePage(timePanel);
        assertTrue("the window says it is done", anyContains(labels(timePage), "Time target reached"));
        assertTrue("a reached window offers the completed row too",
            anyContains(labels(timePage), "\u21BB"));
    }

    /** Owner 1.5.2: a reached aim adds no projection; the hero's GP/h already says the pace. */
    @Test
    public void aReachedAimAddsNoProjection() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now - 20L * 60_000L);
        engine.session().addTransaction(
            booked(now - 15L * 60_000L, "Dragon bones", 1, 1, 100_000, 100_000L), 2_000, false);
        engine.setTargets(10_000L, null, System.currentTimeMillis());
        // The aim counts the run's net (baseline at zero), not from this mid-test save.
        engine.session().profitTargetSetNet = 0L;
        engine.session().sessionStartAt = null;
        engine.session().profitTargetSetActiveMillis = 0L;
        SidebarPanel panel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LivePage page = (LivePage) SidebarPanelProbe.livePage(panel);
        assertTrue("the header still says reached", anyContains(labels(page), "reached"));
        assertFalse("no next-hour projection", anyContains(labels(page), "in 1h"));

        // An immature rate keeps the line blank rather than fabricating an hour.
        Engine fresh = PresentationLifecycleTest.engine();
        fresh.startCustom("Vorkath", System.currentTimeMillis() - 5_000L);
        fresh.session().addTransaction(
            booked(System.currentTimeMillis() - 2_000L, "Dragon bones", 1, 1, 5_000, 5_000L),
            2_000, false);
        fresh.setTargets(1L, null, System.currentTimeMillis());
        fresh.session().profitTargetSetNet = 0L;
        fresh.session().sessionStartAt = null;
        fresh.session().profitTargetSetActiveMillis = 0L;
        SidebarPanel freshPanel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(fresh, PresentationLifecycleTest.config(), null, null, null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LivePage freshPage = (LivePage) SidebarPanelProbe.livePage(freshPanel);
        assertFalse("no established rate, no projection",
            anyContains(labels(freshPage), "in 1h"));
    }

    /**
     * Owner 1.5.2: the card offers Edit, Pause (a running timer), Done and Again. Done clears the
     * target and the session keeps counting; Again is a new session with the same target.
     */
    @Test
    public void targetCardOffersDoneAndAgain() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now - 20L * 60_000L);
        engine.setTargets(200_000L, 5L * 60_000L, now - 10L * 60_000L);
        engine.session().addTransaction(
            booked(now - 5L * 60_000L, "Dragon bones", 1, 1, 100_000, 100_000L), 2_000, false);
        String runId = engine.session().id();
        Long session = engine.session().sessionStartAt;
        SidebarPanel panel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LivePage page = (LivePage) SidebarPanelProbe.livePage(panel);
        assertTrue("Again is always there", onEdt(() -> page.againButton.isVisible()));
        assertTrue("so is Done", onEdt(() -> page.doneButton.isVisible()));
        assertTrue(onEdt(() -> page.editButton.isVisible()));

        onEdt(() ->
        {
            page.againButton.doClick();
            return null;
        });
        assertEquals("Again keeps the run", runId, engine.session().id());
        assertEquals("and the target", Long.valueOf(200_000L), engine.session().targetGp());
        assertNotEquals("with a new session", session, engine.session().sessionStartAt);
        assertEquals("that counts from zero", 0L,
            engine.session().targetProgress(engine.metrics(System.currentTimeMillis()).net));
        assertFalse(engine.session().timeTargetPaused());

        engine.session().addTransaction(
            booked(System.currentTimeMillis() + 1L, "Zulrah's scales", 12934, 1, 40_000, 40_000L), 2_000, false);
        Long afterAgain = engine.session().sessionStartAt;
        onEdt(() ->
        {
            SidebarPanelProbe.refresh(panel);
            page.doneButton.doClick();
            return null;
        });
        assertNull("Done clears the Net aim", engine.session().targetGp());
        assertNull("and the timer", engine.session().timeTargetMillis());
        assertEquals("the session keeps counting", afterAgain, engine.session().sessionStartAt);
        assertEquals(40_000L, onEdt(() ->
            LiveSnapshot.capture(engine, System.currentTimeMillis() + 2L, null)).net);

        // The timer's own pause (owner live 2026-10-14): the run itself keeps tracking.
        Engine timed = PresentationLifecycleTest.engine();
        long now3 = System.currentTimeMillis();
        timed.startCustom("Vorkath", now3 - 5L * 60_000L);
        timed.session().setTimeTarget(5L * 60_000L, now3);
        SidebarPanel third = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(timed, PresentationLifecycleTest.config(), null, null, null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LivePage thirdPage = (LivePage) SidebarPanelProbe.livePage(third);
        assertTrue("a running timer offers its cluster",
            onEdt(() -> thirdPage.top.isVisible()));
        assertFalse("with no bottom row while it runs",
            onEdt(() -> thirdPage.targetActions.isVisible()));
        assertEquals("with the pause icon", "||", onEdt(() -> thirdPage.pause.getText()));
        onEdt(() ->
        {
            thirdPage.pause.doClick();
            return null;
        });
        assertTrue("the timer pauses", timed.session().timeTargetPaused());
        assertFalse("the run itself keeps tracking", timed.session().paused);
        onEdt(() ->
        {
            SidebarPanelProbe.refresh(third);
            return null;
        });
        assertEquals("with the resume icon", "\u25B6", onEdt(() -> thirdPage.pause.getText()));
        onEdt(() ->
        {
            thirdPage.pause.doClick();
            return null;
        });
        assertFalse("Resume runs the timer again", timed.session().timeTargetPaused());
    }

    /** Owner live 2026-10-14: a Net aim counts what the run earns after it is set. */
    @Test
    public void aNetAimCountsFromWhenItWasSet() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now - 20L * 60_000L);
        engine.session().addTransaction(
            booked(now - 15L * 60_000L, "Dragon bones", 1, 1, 100_000, 100_000L), 2_000, false);
        long set = System.currentTimeMillis();
        engine.setTargets(200_000L, null, set);
        SidebarPanel panel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LivePage page = (LivePage) SidebarPanelProbe.livePage(panel);
        assertEquals("the aim starts at zero", "0 / " + Fmt.compact(200_000L),
            onEdt(() -> HeroProbe.netText(page.hero)));

        engine.session().addTransaction(
            booked(set + 1L, "Zulrah's scales", 12934, 1, 50_000, 50_000L), 2_000, false);
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
        engine.session().addTransaction(booked(now - 4L * 60_000L, "Dragon bones", 1, 2,
            100_000, 200_000L), 2_000, false);
        SidebarPanel panel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null);
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
        engine.session().addTransaction(booked(now + 1_000L, "Dragon bones", 1, 2, 2_000, 4_000L), 2_000, false);
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

    /** Direct controls: Live retains rename in running/paused Grinds; Start and End are on Grinds. */
    @Test
    public void heroControlsFollowFreePlayAndGrindStates() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ensureSession(now);
        LivePage free = page(engine);
        assertNull("Free play has no duplicate toolbar", onEdt(free::pageBar));
        assertNull("Free play's name is not renamed", onEdt(() -> free.hero.name.getToolTipText()));

        engine.startCustom("Vorkath", now + 1_000L);
        LivePage grind = page(engine);
        assertNull("Grind commands stay on Grinds", onEdt(grind::pageBar));
        assertEquals("Click to rename", onEdt(() -> grind.hero.name.getToolTipText()));

        engine.togglePause(now + 2_000L);
        LivePage paused = page(engine);
        assertEquals("a paused Grind can still be renamed", "Click to rename", onEdt(() -> paused.hero.name.getToolTipText()));
    }

    private static LivePage page(Engine engine) throws Exception
    {
        SidebarPanel panel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        return (LivePage) SidebarPanelProbe.livePage(panel);
    }

    @Test
    public void recentListHeaderCountsRowsWithoutADuplicateLedgerShortcut() throws Exception
    {
        LivePage page = pageWithSession(6_400L, null, false);
        List<String> labels = labels(page);
        assertTrue("header copy: " + labels, labels.contains("RECENT \u00b7 2"));
        assertFalse("Ledger is already a tab", labels.contains("Ledger"));
        assertFalse("Recent keeps its total", page.recent.total.getText().isEmpty());
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
            SidebarPanel created = new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LiveSnapshot out = SidebarPanelProbe.live(panel);
        assertTrue(out.offline);
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
            SidebarPanel created = new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LiveSnapshot fresh = SidebarPanelProbe.live(panel);
        assertFalse("no session after a reset", fresh.present);
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
        assertTrue("the gem begins Free play", engine.session() != null && !engine.session().paused);
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
            SidebarPanel created = new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LivePage page = (LivePage) SidebarPanelProbe.livePage(panel);
        assertTrue(engine.session().paused);
        assertTrue(onEdt(() -> HeroProbe.dotClickable(page.hero)));
        assertFalse(onEdt(() -> LivePageProbe.hasTrackingButton(page.body())));
        onEdt(() ->
        {
            page.hero.onDot.run();
            return null;
        });
        assertFalse("the gem resumes the same Grind", engine.session().paused);
        assertEquals("Vorkath", engine.session().name());
    }

    @Test
    public void liveNamesTheRunAndFreePlayKeepsItsActivity() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);

        LiveContext target = new LiveContext(false, null, 0L, PvpState.NONE, "Woodcutting", false, "Goblin", false);
        assertEquals("the named run, never the activity", "Vorkath",
            LivePage.identityOf(LiveSnapshot.capture(engine, now + 1_000L, target)));

        Engine free = PresentationLifecycleTest.engine();
        free.ensureSession(now);
        // Owner live 2026-10-14: free play shows the detected activity label ("Trade").
        LiveContext trade = new LiveContext(false, null, 0L, PvpState.NONE, "Trade", false, "", false);
        assertEquals("Trade", LivePage.identityOf(LiveSnapshot.capture(free, now + 1_000L, trade)));
        LiveContext quiet = new LiveContext(false, null, 0L, PvpState.NONE, "", false, "", false);
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
            SidebarPanel created = new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        assertEquals("plain running needs no status word", "", LivePage.wordOf(SidebarPanelProbe.live(panel)));
        engine.session().pause(now + 1_000L, PauseReason.IDLE);
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
                PresentationLifecycleTest.config(), null, null, null);
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
                PresentationLifecycleTest.config(), null, null, null);
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
        engine.session().addTransaction(booked(now + 1_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000, false);
        engine.session().addEncounter(false, now + 1_500L);

        SidebarPanel panel = onEdt(() -> new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null));
        onEdt(() ->
        {
            panel.bindPvp(() -> new PvpState(true, true, true, 0L));
            SidebarPanelProbe.refresh(panel);
            return null;
        });
        LiveSnapshot dangerous = SidebarPanelProbe.live(panel);
        assertTrue("client-thread sample reaches presentation", dangerous.skulled);
        assertTrue(dangerous.protectItem);
        assertTrue("a session with a fight keeps its PvP grind context", dangerous.pvp);
        assertEquals("kills/deaths facts stay readable", 0, dangerous.kills);

        onEdt(() ->
        {
            panel.bindPvp(() -> new PvpState(false, false, false, 0L));
            SidebarPanelProbe.refresh(panel);
            return null;
        });
        LiveSnapshot safe = SidebarPanelProbe.live(panel);
        assertFalse(safe.skulled);
        assertTrue("entering a safe boundary never reclassifies the grind", safe.pvp);
        assertEquals("net is stable across the boundary", dangerous.net, safe.net);
    }

    // ---- helpers ----

    private static LivePage pageWithSession(long net, @Nullable Long target, boolean review) throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);
        long gains = Math.abs(net) + 100L;
        engine.session().addTransaction(booked(now + 1_000L, "Dragon bones", 1, 2,
            (int) Math.max(1L, gains / 2), gains), 2_000, false);
        engine.session().addTransaction(consumed(now + 2_000L, "Prayer potion(4)", 3, -1, 100, -100L), 2_000, false);
        if (target != null)
        {
            engine.session().setNetTarget(target, 0, 0);
        }
        if (review)
        {
            engine.session().addTransaction(review(now + 3_000L, "Unidentified mineral", 8, 1L), 2_000, false);
        }
        SidebarPanel panel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        return (LivePage) SidebarPanelProbe.livePage(panel);
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

    @Test
    public void genericSkillsAndNpcsStayButMagicNeverTitlesFreePlay() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        engine.ensureSession(1_000L);
        SidebarPanel panel = onEdt(() -> new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null));
        engine.setDetectedActivity("Magic", 2_000L);
        assertEquals("", onEdt(panel::activityLabel));
        for (String name : List.of("Woodcutting", "Fishing", "Mining", "Vorkath", "Theatre of Blood"))
        {
            engine.setDetectedActivity(name, 2_100L);
            assertEquals("keep generic skills and named activities", name, onEdt(panel::activityLabel));
        }
        engine.startCustom("Magic", 3_000L);
        assertEquals("Magic", LivePage.identityOf(LiveSnapshot.capture(engine, 4_000L, null)));
    }
}
