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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The final Live surface: status, Net hero, breakdown, compact target, Recent Loot, Review. */
public class LivePresentationTest
{
    @Test
    public void multiRuneCastDoesNotAssignWholeNetToLeadRune() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        Ac cast = new Ac(now + 1_000L, null,
            Ai.CONSUMPTION, Aj.GENERIC, "Cast Ice Burst", "Vorkath", true,
            List.of(new Ab(1, "Death rune", -1, 400, -400L),
                new Ab(2, "Chaos rune", -1, 300, -300L),
                new Ab(3, "Water rune", -1, 124, -124L)),
            Bd.CONFIRMED, "Cast evidence.", null);
        cast.setActionKind(Au.CAST);
        engine.getActiveSession().kf(cast, 2_000);

        Ca.Recent live = LiveProbe.toRecent(cast);
        assertEquals("Cast", live.name);
        assertEquals("3 rune types", live.qty);
        assertEquals(-824L, live.value);
    }

    @Test
    public void liveSnapshotReadsTheCurrentSessionFacts() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis() - 5 * 60_000L;
        engine.ajl("Vorkath", Cx.GENERAL, now);
        engine.getActiveSession().kf(booked(now + 1_000L, "Dragon bones", 1, 2, 3_200, 6_400), 2_000);
        engine.getActiveSession().setProfitTargetGp(1_000_000L);
        Ca snapshot = Ca.capture(engine, now + 60_000L, Dz.NONE);

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
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        engine.getActiveSession().kf(booked(now + 1_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000);
        engine.getActiveSession().kf(booked(now + 2_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000);
        engine.getActiveSession().kf(booked(now + 3_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000);
        Ca snapshot = Ca.capture(engine, now + 4_000L, Dz.NONE);

        assertEquals("three equivalent receipts render as one row", 1, snapshot.recent.size());
        Ca.Recent row = snapshot.recent.get(0);
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
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        engine.getActiveSession().kf(booked(now + 1_000L, "Dragon bones", 1, 2, 3_200, 6_400), 2_000);
        engine.getActiveSession().kf(consumed(now + 2_000L, "Dragon bones", 1, -1, 3_200, -3_200), 2_000);
        engine.getActiveSession().kf(booked(now + 3_000L, "Unidentified mineral", 4, 1, 50, 50), 2_000);
        engine.getActiveSession().kf(unpriced(now + 4_000L, "Unidentified mineral", 4), 2_000);
        engine.getActiveSession().kf(review(now + 5_000L, "Unidentified mineral", 4, 1L), 2_000);
        Ca snapshot = Ca.capture(engine, now + 6_000L, Dz.NONE);

        assertEquals("gain, cost, priced, unpriced and review all stay separate", 5, snapshot.recent.size());
        long valueSum = 0L;
        for (Ca.Recent row : snapshot.recent)
        {
            assertEquals("every visible row is a single receipt", 1, row.receipts);
            valueSum += row.value;
        }
        assertEquals("the visible rows still sum to session net", snapshot.net, valueSum);
    }

    @Test
    public void unpricedReceiptIsMarkedUnpricedAndNeverShowsZeroAsValue() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        engine.getActiveSession().kf(unpriced(now + 1_000L, "Unidentified mineral", 8), 2_000);
        Ca snapshot = Ca.capture(engine, now + 2_000L, Dz.NONE);

        assertEquals(1, snapshot.recent.size());
        assertTrue("the row is marked unpriced", snapshot.recent.get(0).unpriced);
        assertEquals("the booked value stays 0 and the page paints the unknown marker instead",
            0L, snapshot.recent.get(0).value);
    }

    @Test
    public void uncertainReceiptIsTaggedReviewAndCountedForTheRail() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        engine.getActiveSession().kf(review(now + 1_000L, "Unidentified mineral", 8, 1L), 2_000);
        Ca snapshot = Ca.capture(engine, now + 2_000L, Dz.NONE);

        assertEquals(1, snapshot.reviewCount);
        assertEquals("review", snapshot.recent.get(0).tag);
    }

    @Test
    public void heroShowsOneNetAndTheRateNeverFakesZero() throws Exception
    {
        assertEquals("2.03M/h", LivePage.awx(true, 2_030_000L));
        assertEquals("an unestablished rate is never 0", "Calculating\u2026", LivePage.awx(false, 0L));

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
        assertEquals("no Market cell without Market activity", "", onEdt(() -> HeroProbe.cell(page.hero, "MARKET")));
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
        assertTrue("progress reads Net over target", labels(with).contains(Fmt.signed(6_400L) + " / 1.00M"));
        assertTrue("clicking the card edits the target", with.target.getMouseListeners().length > 0);

        // Owner 2026-09-28: Free play can hold a target too.
        Am engine = PresentationLifecycleTest.engine();
        engine.rm(System.currentTimeMillis());
        Dp panel = onEdt(() ->
        {
            Dp created = new Dp(engine, PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LivePage free = (LivePage) SidebarPanelProbe.livePage(panel);
        assertTrue("Free play offers a target", onEdt(() -> free.hero.net.getToolTipText()).endsWith("click to set a target"));
        engine.ahq(500_000L, null, System.currentTimeMillis());
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
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now - 20L * 60_000L);
        engine.getActiveSession().kf(booked(now - 15L * 60_000L, "Dragon bones", 1, 2,
            100_000, 200_000L), 2_000);
        engine.getActiveSession().setActiveTimeTargetMillis(5L * 3_600_000L);
        Dp panel = onEdt(() ->
        {
            Dp created = new Dp(engine, PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LivePage page = (LivePage) SidebarPanelProbe.livePage(panel);
        assertTrue("the time target keeps its card",
            anyContains(labels(page), "TARGET · " + Fmt.ra(5L * 3_600_000L)));
        assertTrue("progress reads Active Time over target", labels(page).contains(
            Fmt.ra(20L * 60_000L) + " / " + Fmt.ra(5L * 3_600_000L)));
        assertTrue("the established rate projects Net at the target",
            anyContains(labels(page), "projected Net"));
    }

    /** Owner 2026-10-01 (F12): the Live hero names the rate window and the Active time policy. */
    @Test
    public void liveHeroNamesItsRateBasisAndActiveTimePolicy() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now - 5L * 60_000L);
        engine.getActiveSession().kf(booked(now - 4L * 60_000L, "Dragon bones", 1, 2,
            100_000, 200_000L), 2_000);
        Dp panel = onEdt(() ->
        {
            Dp created = new Dp(engine, PresentationLifecycleTest.config(), null);
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
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Flipping", Cx.GENERAL, now);
        engine.getActiveSession().kf(booked(now + 1_000L, "Dragon bones", 1, 2, 2_000, 4_000L), 2_000);
        LivePage before = page(engine);
        assertFalse("no offers, no Offers table", onEdt(() -> before.offers.isVisible()));

        Bj ledger = new Bj();
        ledger.observe(new Bj.Snapshot(0, net.runelite.api.GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0));
        engine.abh(ledger.observe(new Bj.Snapshot(0,
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
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.rm(now);
        LivePage free = page(engine);
        assertTrue("Free play offers Start", onEdt(() -> free.startGrind.isVisible()));
        assertFalse("nothing to End in Free play", onEdt(() -> free.endGrind.isVisible()));
        assertNull("Free play's name is not renamed", onEdt(() -> free.hero.name.getToolTipText()));

        engine.ajl("Vorkath", Cx.GENERAL, now + 1_000L);
        LivePage grind = page(engine);
        assertFalse(onEdt(() -> grind.startGrind.isVisible()));
        assertTrue("a Grind offers End", onEdt(() -> grind.endGrind.isVisible()));
        assertEquals("Click to rename", onEdt(() -> grind.hero.name.getToolTipText()));

        engine.togglePause(now + 2_000L);
        LivePage paused = page(engine);
        assertTrue("a paused Grind can still End", onEdt(() -> paused.endGrind.isVisible()));
    }

    private static LivePage page(Am engine) throws Exception
    {
        Dp panel = onEdt(() ->
        {
            Dp created = new Dp(engine, PresentationLifecycleTest.config(), null);
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
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        engine.acu(now + 1_000L);
        Dp panel = onEdt(() ->
        {
            Dp created = new Dp(engine, PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        Ca out = SidebarPanelProbe.live(panel);
        assertTrue(out.loggedOut);
        assertEquals("LOGGED OUT", LivePage.wordOf(out));
        assertTrue(LivePage.ajn(out).startsWith("Logged out"));

        engine.resume(now + 2_000L, Ed.LIFECYCLE);
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
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        engine.agr(now + 1_000L);
        Dp panel = onEdt(() ->
        {
            Dp created = new Dp(engine, PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        Ca fresh = SidebarPanelProbe.live(panel);
        assertFalse("no session after a reset", fresh.hasSession);
        assertTrue("a reset shows the pending resume countdown",
            LivePage.wordOf(fresh).startsWith("RESUMING"));
        assertTrue(LivePage.ajn(fresh).startsWith("Not tracking"));

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
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        engine.togglePause(now + 1_000L);
        Dp panel = onEdt(() ->
        {
            Dp created = new Dp(engine, PresentationLifecycleTest.config(), null);
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
    public void liveShowsTheSameActivityLadderAsHudPlus() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);

        Dz target = new Dz(false, null, 0L, Bo.NONE, "Woodcutting", false, "Goblin");
        assertEquals("the named run, never the target", "Vorkath",
            LivePage.wf(Ca.capture(engine, now + 1_000L, target)));

        Am free = PresentationLifecycleTest.engine();
        free.rm(now);
        Dz fight = new Dz(false, null, 0L, Bo.NONE, "Greater Nechryael", false, "Goblin");
        assertEquals("Combat", LivePage.wf(Ca.capture(free, now + 1_000L, fight)));
        Dz placeholder = new Dz(false, null, 0L, Bo.NONE, "NPC loot", false, "");
        assertEquals("a fallback names nothing", "", LivePage.wf(Ca.capture(free, now + 1_000L, placeholder)));
    }

    @Test
    public void idleKeepsTheGrindIdentity() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        Dp panel = onEdt(() ->
        {
            Dp created = new Dp(engine, PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        assertEquals("plain running needs no status word", "", LivePage.wordOf(SidebarPanelProbe.live(panel)));
        engine.adh(now + 1_000L, now);
        onEdt(() ->
        {
            SidebarPanelProbe.refresh(panel);
            return null;
        });
        Ca idle = SidebarPanelProbe.live(panel);
        assertTrue(idle.idle);
        assertEquals("AWAY", LivePage.wordOf(idle));
        assertEquals("idle never destroys the grind identity", "Vorkath", LivePage.wf(idle));
        assertTrue(LivePage.ajn(idle).startsWith("Waiting for activity"));
    }

    @Test
    public void noSessionShowsDashesNeverZeros() throws Exception
    {
        LivePage page = onEdt(() ->
        {
            Dp panel = new Dp(PresentationLifecycleTest.engine(),
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
            Dp panel = new Dp(PresentationLifecycleTest.engine(),
                PresentationLifecycleTest.config(), null);
            panel.na(() -> false);
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
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Wilderness", Cx.PK, now);
        engine.getActiveSession().kf(booked(now + 1_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000);

        Dp panel = onEdt(() -> new Dp(engine, PresentationLifecycleTest.config(), null));
        onEdt(() ->
        {
            panel.axo(() -> new Bo(true, true, true, 0L));
            SidebarPanelProbe.refresh(panel);
            return null;
        });
        Ca dangerous = SidebarPanelProbe.live(panel);
        assertTrue("client-thread sample reaches presentation", dangerous.skulled);
        assertTrue(dangerous.protectItem);
        assertTrue("PK sessions keep their PvP grind context", dangerous.pvpSession);
        assertEquals("kills/deaths facts stay readable", 0, dangerous.kills);

        onEdt(() ->
        {
            panel.axo(() -> new Bo(false, false, false, 0L));
            SidebarPanelProbe.refresh(panel);
            return null;
        });
        Ca safe = SidebarPanelProbe.live(panel);
        assertFalse(safe.skulled);
        assertTrue("entering a safe boundary never reclassifies the grind", safe.pvpSession);
        assertEquals("net is stable across the boundary", dangerous.net, safe.net);
    }

    // ---- helpers ----

    private static LivePage pageWithSession(long net, @Nullable Long target, boolean review) throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        long gains = Math.abs(net) + 100L;
        engine.getActiveSession().kf(booked(now + 1_000L, "Dragon bones", 1, 2,
            (int) Math.max(1L, gains / 2), gains), 2_000);
        engine.getActiveSession().kf(consumed(now + 2_000L, "Prayer potion(4)", 3, -1, 100, -100L), 2_000);
        if (target != null)
        {
            engine.getActiveSession().setProfitTargetGp(target);
        }
        if (review)
        {
            engine.getActiveSession().kf(review(now + 3_000L, "Unidentified mineral", 8, 1L), 2_000);
        }
        Dp panel = onEdt(() ->
        {
            Dp created = new Dp(engine, PresentationLifecycleTest.config(), null);
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

    private static Ac booked(long at, String name, int itemId, long quantity, int unitPrice, long value)
    {
        return new Ac(at, null, Ai.GAIN, Aj.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Ab(itemId, name, quantity, unitPrice, value)),
            Bd.LIKELY, "Test sample.", null);
    }

    private static Ac consumed(long at, String name, int itemId, long quantity, int unitPrice, long value)
    {
        return new Ac(at, null, Ai.CONSUMPTION, Aj.GENERIC, "", "Vorkath",
            true, Collections.singletonList(new Ab(itemId, name, quantity, unitPrice, value)),
            Bd.LIKELY, "Test sample.", null);
    }

    private static Ac review(long at, String name, int itemId, long quantity)
    {
        return new Ac(at, null, Ai.UNCERTAIN, Aj.GENERIC, "", "Vorkath",
            true, Collections.singletonList(new Ab(itemId, name, quantity, 0, 0L)),
            Bd.UNCERTAIN, "Test sample: awaiting a decision.", null);
    }

    private static Ac unpriced(long at, String name, int itemId)
    {
        return new Ac(at, null, Ai.GAIN, Aj.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Ab(itemId, name, 8, 0, 0L)),
            Bd.LIKELY, "Test sample.", null);
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
