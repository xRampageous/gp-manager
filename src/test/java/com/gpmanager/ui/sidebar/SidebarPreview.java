package com.gpmanager;

import java.awt.BorderLayout;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.ZoneId;
import java.util.Collections;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * Offline visual checkpoint for the restored GP Surface and Ledger: realizes the real panel
 * off-screen, books small deterministic samples into the real engine and paints each required
 * state to PNG.
 *
 * <p>It is a development harness, not plugin runtime: {@code ./gradlew panelPreview} runs it and
 * writes to {@code build/sidebar-preview}. No client, no network, no persistence.</p>
 */
public final class SidebarPreview
{
    public static void main(String[] args) throws Exception
    {
        File out = new File(args != null && args.length > 0 ? args[0] : "build/sidebar-preview");
        out.mkdirs();
        final Exception[] failure = new Exception[1];
        SwingUtilities.invokeAndWait(() ->
        {
            try
            {
                run(out);
            }
            catch (Exception ex)
            {
                failure[0] = ex;
            }
        });
        if (failure[0] != null)
        {
            throw failure[0];
        }
        System.out.println("GP Surface preview written to " + out.getAbsolutePath());
    }

    private static void run(File out) throws IOException
    {
        HudPreview.write(out);
        normalNegative(out);
        fullPositive(out);
        emptyWaiting(out);
        pvpContext(out);
        ledgerNormal(out);
        ledgerReview(out);
        ledgerDetail(out);
        ledgerCorrectionPreview(out);
        ledgerCompacted(out);
        grindsCurrent(out);
        grindsMy(out);
        grindsRecent(out);
        grindsDetail(out);
        grindsStartWithChanges(out);
        grindsDataMenu(out);
        r31FreshLive(out);
        r31GrindsNoTargets(out);
        r31GrindsTargetCalculating(out);
        r4GrindDetailComplete(out);
        r4GrindDetailNoPrevious(out);
        r4GrindDetailCompacted(out);
        r41LedgerLongRow(out);
        r41aLedgerIconPositive(out);
        r41aLedgerIconNegative(out);
    }

    // ---- Live --------------------------------------------------------------------------------

    /** A. Normal / negative: negative Net, GP/h, breakdown, one Recent row, no target, no review. */
    private static void normalNegative(File out) throws IOException
    {
        Engine engine = engine();
        long now = System.currentTimeMillis() - 20 * 60_000L;
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, now);
        engine.getActiveSession().addTransaction(consumed(now + 1_000L, "Prayer potion(4)", 3, -12, 9_800, -117_600L), 2_000);
        liveShot(out, "a-live-normal-negative.png", engine, PvpState.NONE);
    }

    /** B. Full / positive: positive Net, five Recent rows, two-row target, Needs Review. */
    private static void fullPositive(File out) throws IOException
    {
        Engine engine = engine();
        long now = System.currentTimeMillis() - 45 * 60_000L;
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, now);
        engine.getActiveSession().addTransaction(booked(now + 1_000L, "Dragon bones", 1, 2, 3_200, 6_400), 2_000);
        engine.getActiveSession().addTransaction(booked(now + 2_000L, "Battlestaff", 2, 5, 4_400, 22_000), 2_000);
        engine.getActiveSession().addTransaction(consumed(now + 3_000L, "Prayer potion(4)", 3, -2, 9_800, -19_600L), 2_000);
        engine.getActiveSession().addTransaction(booked(now + 4_000L, "Vorkath's head", 5, 1, 1_450_000, 1_450_000), 2_000);
        engine.getActiveSession().addTransaction(review(now + 5_000L, "Unidentified mineral", 8, 2L), 2_000);
        engine.getActiveSession().setProfitTargetGp(1_000_000L);
        liveShot(out, "b-live-full-positive.png", engine, PvpState.NONE);
    }

    /** C. Empty / waiting: no session, no fake grind launcher, no dead cards. */
    private static void emptyWaiting(File out) throws IOException
    {
        liveShot(out, "c-live-empty-waiting.png", engine(), PvpState.NONE);
    }

    /** D. PvP context: truthful skull / Protect Item marks and the PvP grind tint, nothing tactical. */
    private static void pvpContext(File out) throws IOException
    {
        Engine engine = engine();
        long now = System.currentTimeMillis() - 15 * 60_000L;
        engine.startCustomSession("Wilderness", SessionMode.PK, now);
        engine.getActiveSession().addTransaction(booked(now + 1_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000);
        engine.getActiveSession().addTransaction(pkDeath(now + 2_000L), 2_000);
        liveShot(out, "d-live-pvp-context.png", engine, new PvpState(true, true, true));
    }

    // ---- Ledger ------------------------------------------------------------------------------

    private static void ledgerNormal(File out) throws IOException
    {
        ledgerShot(out, "led-a-ledger-normal.png", mixedEngine(), null, null);
    }

    private static void ledgerReview(File out) throws IOException
    {
        SidebarPanel panel = panel(mixedEngine(), PvpState.NONE);
        SidebarPanelProbe.openLedger(panel, LedgerData.Entry.current(LedgerData.Audit.REVIEW, null));
        paint(panel, new File(out, "led-b-ledger-review.png"));
    }

    private static void ledgerDetail(File out) throws IOException
    {
        Engine engine = mixedEngine();
        ledgerShot(out, "led-c-ledger-detail.png", engine,
            engine.getActiveSession().getTransactions().get(0).getId(), null);
    }

    private static void ledgerCorrectionPreview(File out) throws IOException
    {
        Engine engine = mixedEngine();
        ledgerShot(out, "led-d-correction-preview.png", engine,
            engine.getActiveSession().getTransactions().get(0).getId(), Correction.TRANSFER);
    }

    private static void ledgerCompacted(File out) throws IOException
    {
        Engine engine = engine();
        long now = System.currentTimeMillis() - 48 * 60 * 60_000L;
        engine.startCustomSession("Old grind", SessionMode.GENERAL, now);
        for (int i = 0; i < 8; i++)
        {
            engine.getActiveSession().addTransaction(booked(now + i, "Old bone " + i, 1, 1, 100, 100), 2_000);
        }
        engine.getActiveSession().compactTransactionsBefore(now + 7L, transaction -> false);
        ledgerShot(out, "led-e-ledger-compacted.png", engine, null, null);
    }


    // ---- R3.1B rate / pace / nav-focus fixtures ----------------------------------------------

    /** A. Fresh Live Grind: zero Net and an unavailable rate must never read as 0/h. */
    private static void r31FreshLive(File out) throws IOException
    {
        Engine engine = engine();
        long now = System.currentTimeMillis();
        engine.startCustomSession("2323", SessionMode.GENERAL, now - 20_000L);
        engine.getActiveSession().addTransaction(booked(now - 15_000L, "Willow logs", 3, 5, 42, 210), 2_000);
        engine.getActiveSession().addTransaction(consumed(now - 10_000L, "Prayer potion(4)", 3, -1, 210, -210L), 2_000);
        liveShot(out, "r31-a-fresh-live.png", engine, PvpState.NONE);
    }

    /** B. Current Grind with no targets: no Pace question, so no Pace row and no dead space. */
    private static void r31GrindsNoTargets(File out) throws IOException
    {
        Engine engine = engine();
        long now = System.currentTimeMillis();
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, now - 20_000L);
        engine.getActiveSession().addTransaction(booked(now - 15_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000);
        SidebarPanel panel = panel(engine, PvpState.NONE);
        panel.shell().show(Shell.GRINDS);
        paint(panel, new File(out, "r31-b-grinds-no-targets.png"));
    }

    /** C. Target set but the rate is immature: the honest Calculating state, not a projection. */
    private static void r31GrindsTargetCalculating(File out) throws IOException
    {
        Engine engine = engine();
        long now = System.currentTimeMillis();
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, now - 20_000L);
        engine.getActiveSession().addTransaction(booked(now - 15_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000);
        engine.getActiveSession().setProfitTargetGp(5_000_000L);
        SidebarPanel panel = panel(engine, PvpState.NONE);
        panel.shell().show(Shell.GRINDS);
        paint(panel, new File(out, "r31-c-grinds-target-calculating.png"));
    }

    // ---- R4 Insights / recap fixtures ---------------------------------------------------------

    /** E. Completed Grind detail: recap, targets, highlights, previous comparison, PB. */
    private static void r4GrindDetailComplete(File out) throws IOException
    {
        Engine engine = insightsFixture();
        SidebarPanel panel = panel(engine, PvpState.NONE);
        panel.shell().show(Shell.GRINDS);
        SidebarPanelProbe.openGrindsDetail(panel, latestSessionNamed(engine, "Vorkath"));
        paint(panel, new File(out, "r4-e-grind-detail-complete.png"));
    }

    /** F. Completed Grind detail with no linked previous run: no fake comparison, no dead gap. */
    private static void r4GrindDetailNoPrevious(File out) throws IOException
    {
        Engine engine = engine();
        long now = System.currentTimeMillis();
        finishedGrind(engine, "Muspah", now - 3 * 3_600_000L, 612_000L);
        SidebarPanel panel = panel(engine, PvpState.NONE);
        panel.shell().show(Shell.GRINDS);
        SidebarPanelProbe.openGrindsDetail(panel, latestSessionNamed(engine, "Muspah"));
        paint(panel, new File(out, "r4-f-grind-detail-no-previous.png"));
    }

    /** G. Compacted Grind: aggregate recap survives; the exact highlight fails closed. */
    private static void r4GrindDetailCompacted(File out) throws IOException
    {
        Engine engine = engine();
        long now = System.currentTimeMillis();
        finishedLinked(engine, "Vorkath", now - 4 * 3_600_000L, 3_600_000L, 400_000L, 3 * 3_600_000L);
        engine.getHistory().get(0).compactTransactionsBefore(now - 2 * 3_600_000L, transaction -> false);
        SidebarPanel panel = panel(engine, PvpState.NONE);
        panel.shell().show(Shell.GRINDS);
        SidebarPanelProbe.openGrindsDetail(panel, latestSessionNamed(engine, "Vorkath"));
        paint(panel, new File(out, "r4-g-grind-detail-compacted.png"));
    }

    private static Engine insightsFixture()
    {
        Engine engine = engine();
        long now = System.currentTimeMillis();
        long day = 24L * 3_600_000L;
        finishedLinked(engine, "Vorkath", now - 2 * day, 4_200_000L, 620_000L, 2 * 3_600_000L);
        finishedLinked(engine, "Vorkath", now - 6 * 3_600_000L, 2_840_000L, 180_000L, 1 * 3_600_000L);
        finishedLinked(engine, "Zulrah", now - day, 1_300_000L, 240_000L, 90 * 60_000L);
        return engine;
    }

    private static void finishedLinked(Engine engine, String name, long startedAt, long gain, long cost,
        long activeMillis)
    {
        SavedState.SavedGrind definition = null;
        for (SavedState.SavedGrind saved : engine.getSavedGrinds(true))
        {
            if (name.equals(saved.getName()))
            {
                definition = saved;
            }
        }
        if (definition == null)
        {
            definition = engine.saveGrind(name, null, null, false, null);
        }
        engine.startGrind(name, definition.getGrindId(), null, null, startedAt);
        engine.getActiveSession().addTransaction(booked(startedAt + 1_000L, "Dragon bones", 1, 1,
            (int) gain, gain), 2_000);
        Transaction supply = consumed(startedAt + 2_000L, "Prayer potion(4)", 3, -1,
            (int) Math.max(1L, cost / 3L), -cost);
        supply.setActionKind(ActionKind.DRINK);
        engine.getActiveSession().addTransaction(supply, 2_000);
        engine.finishCustomSession(startedAt + activeMillis);
    }

    @Nullable
    private static String latestSessionNamed(Engine engine, String name)
    {
        String found = null;
        long at = Long.MIN_VALUE;
        for (Session session : engine.getHistory())
        {
            if (session != null && name.equals(session.getName()) && session.endedAtEpochMillis > at)
            {
                at = session.endedAtEpochMillis;
                found = session.getId();
            }
        }
        return found;
    }

    // ---- R4.1 real-width hardening fixtures ---------------------------------------------------

    /** F. Ledger receipt rows: long item and long distinct activity at real width. */
    private static void r41LedgerLongRow(File out) throws IOException
    {
        Engine engine = engine();
        long now = System.currentTimeMillis() - 5 * 60_000L;
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, now);
        engine.getActiveSession().addTransaction(booked(now + 1_000L, "Cooked chicken", 2140, 1, 740, 740L), 2_000);
        Transaction drink = consumed(now + 2_000L, "Super attack potion(4)", 3, -1, 9_800, -19_600L);
        drink.setActionKind(ActionKind.DRINK);
        engine.getActiveSession().addTransaction(drink, 2_000);
        engine.getActiveSession().addTransaction(new Transaction(now + 3_000L, null, TransactionType.GAIN,
            Context.GENERIC, "", "Wilderness Agility", true,
            Collections.singletonList(new Flow(1,
                "Extremely long ancient item name for width", 1, 99_900_000, 99_900_000L)),
            ClassificationConfidence.LIKELY, "Preview sample.", null), 2_000);
        SidebarPanel panel = panel(engine, PvpState.NONE);
        panel.shell().show(Shell.LEDGER);
        paint(panel, new File(out, "r41-f-ledger-long-row.png"));
    }

    private static Engine openOnlyFixture()
    {
        Engine engine = engine();
        long now = System.currentTimeMillis();
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, now - 3_600_000L);
        engine.getActiveSession().addTransaction(booked(now - 3_500_000L, "Dragon bones", 1, 1, 3_200, 3_200L),
            2_000);
        return engine;
    }

    // ---- R4.1A fitting fixtures ----------------------------------------------------------------

    /** A. Ledger with a real dummy icon, long item/source and a large positive value. */
    private static void r41aLedgerIconPositive(File out) throws IOException
    {
        SidebarPanel panel = panel(ledgerIconEngine(99_900_000L), PvpState.NONE);
        SidebarPanelProbe.bindSprites(panel, id -> dummySprite());
        panel.shell().show(Shell.LEDGER);
        paint(panel, new File(out, "r41a-a-ledger-icon-positive.png"));
    }

    /** B. Ledger with a real dummy icon, long item/source and a large negative value. */
    private static void r41aLedgerIconNegative(File out) throws IOException
    {
        SidebarPanel panel = panel(ledgerIconEngine(-99_900_000L), PvpState.NONE);
        SidebarPanelProbe.bindSprites(panel, id -> dummySprite());
        panel.shell().show(Shell.LEDGER);
        paint(panel, new File(out, "r41a-b-ledger-icon-negative.png"));
    }

    private static Engine ledgerIconEngine(long longValue)
    {
        Engine engine = engine();
        long now = System.currentTimeMillis() - 5 * 60_000L;
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, now);
        engine.getActiveSession().addTransaction(consumed(now + 1_000L, "Cooked chicken", 2140, 1, 740, -740L),
            2_000);
        engine.getActiveSession().addTransaction(new Transaction(now + 2_000L, null,
            longValue >= 0L ? TransactionType.GAIN : TransactionType.CONSUMPTION, Context.GENERIC, "",
            "Extremely Long Wilderness Activity Name", true,
            Collections.singletonList(new Flow(1, "Extremely long ancient item name for width", 1,
                (int) Math.abs(longValue), longValue)),
            ClassificationConfidence.LIKELY, "Preview sample.", null), 2_000);
        return engine;
    }

    private static Engine longNameFixture()
    {
        Engine engine = engine();
        long now = System.currentTimeMillis();
        longNameLinked(engine, now - 5 * 3_600_000L, now - 4 * 3_600_000L, 2_000_000L);
        longNameLinked(engine, now - 3 * 3_600_000L, now - 1 * 3_600_000L, 3_000_000L);
        return engine;
    }

    private static void longNameLinked(Engine engine, long start, long end, long net)
    {
        String name = "Extremely Long Vorkath V With A Very Long Suffix";
        SavedState.SavedGrind definition = null;
        for (SavedState.SavedGrind saved : engine.getSavedGrinds(true))
        {
            if (name.equals(saved.getName()))
            {
                definition = saved;
            }
        }
        if (definition == null)
        {
            definition = engine.saveGrind(name, null, null, false, null);
        }
        engine.startGrind(name, definition.getGrindId(), null, null, start);
        engine.getActiveSession().addTransaction(booked(start + 1_000L, "Dragon bones", 1, 1,
            (int) net, net), 2_000);
        engine.finishCustomSession(end);
    }

    /** Deterministic visible item imagery so the icon-present row path is actually exercised. */
    private static BufferedImage dummySprite()
    {
        // RuneLite item images are 36 x 32; the item itself sits inside that box.
        BufferedImage image = new BufferedImage(36, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try
        {
            g.setColor(new java.awt.Color(0x35d6a3));
            g.fillRect(6, 4, 24, 24);
            g.setColor(new java.awt.Color(0x0a0b0c));
            g.fillRect(14, 12, 8, 8);
        }
        finally
        {
            g.dispose();
        }
        return image;
    }

    private static Engine mixedEngine()
    {
        Engine engine = engine();
        long now = System.currentTimeMillis() - 30 * 60_000L;
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, now);
        engine.getActiveSession().addTransaction(booked(now + 1_000L, "Willow logs", 3, 5, 42, 210), 2_000);
        engine.getActiveSession().addTransaction(consumed(now + 2_000L, "Prayer potion(4)", 3, -2, 9_800, -19_600L), 2_000);
        engine.getActiveSession().addTransaction(booked(now + 3_000L, "Battlestaff", 2, 5, 4_400, 22_000), 2_000);
        engine.getActiveSession().addTransaction(pkDeath(now + 4_000L), 2_000);
        engine.getActiveSession().addTransaction(review(now + 5_000L, "Unidentified mineral", 8, 2L), 2_000);
        return engine;
    }


    // ---- Grinds ------------------------------------------------------------------------------

    private static void grindsCurrent(File out) throws IOException
    {
        Engine engine = grindsFixture();
        SidebarPanel panel = panel(engine, PvpState.NONE);
        panel.shell().show(Shell.GRINDS);
        paint(panel, new File(out, "g-a-grinds-current.png"));
    }

    private static void grindsMy(File out) throws IOException
    {
        Engine engine = grindsFixture();
        SidebarPanel panel = panel(engine, PvpState.NONE);
        panel.shell().show(Shell.GRINDS);
        paint(panel, new File(out, "g-b-my-grinds.png"));
    }

    private static void grindsRecent(File out) throws IOException
    {
        Engine engine = engine();
        long day = 24L * 3_600_000L;
        long now = System.currentTimeMillis();
        finishedGrind(engine, "Vorkath", now - day, 2_840_000L);
        finishedGrind(engine, "Zulrah", now - 2 * day, -412_000L);
        finishedGrind(engine, "Muspah", now - 3 * day, 612_000L);
        SidebarPanel panel = panel(engine, PvpState.NONE);
        panel.shell().show(Shell.GRINDS);
        paint(panel, new File(out, "g-c-recent-grinds.png"));
    }

    private static void finishedGrind(Engine engine, String name, long startedAt, long net)
    {
        engine.startCustomSession(name, SessionMode.GENERAL, startedAt);
        engine.getActiveSession().addTransaction(booked(startedAt + 1_000L, "Willow logs", 3, 5, 42,
            Math.abs(net) / 5), 2_000);
        engine.getActiveSession().addTransaction(consumed(startedAt + 2_000L, "Prayer potion(4)", 3, -5, 100,
            net < 0 ? net * 4 / 5 : -Math.abs(net) / 5), 2_000);
        engine.finishCustomSession(startedAt + 3_000L);
    }

    private static void grindsDetail(File out) throws IOException
    {
        Engine engine = grindsFixture();
        String historyId = engine.getHistory().isEmpty() ? null : engine.getHistory().get(0).getId();
        SidebarPanel panel = panel(engine, PvpState.NONE);
        panel.shell().show(Shell.GRINDS);
        if (historyId != null)
        {
            SidebarPanelProbe.openGrindsDetail(panel, historyId);
        }
        paint(panel, new File(out, "g-d-grind-detail.png"));
    }

    private static void grindsStartWithChanges(File out) throws IOException
    {
        Engine engine = grindsFixture();
        String grindId = engine.getSavedGrinds(false).get(0).getGrindId();
        SidebarPanel panel = panel(engine, PvpState.NONE);
        panel.shell().show(Shell.GRINDS);
        javax.swing.JPanel form = SidebarPanelProbe.startWithChangesForm(panel, grindId);
        SidebarPanelProbe.disposeFrame(panel);
        form.setSize(220, Math.max(120, form.getPreferredSize().height));
        paintComponent(form, new File(out, "g-e-start-with-changes.png"));
    }

    private static void grindsDataMenu(File out) throws IOException
    {
        Engine engine = grindsFixture();
        SidebarPanel panel = panel(engine, PvpState.NONE);
        panel.shell().show(Shell.GRINDS);
        javax.swing.JPopupMenu menu = SidebarPanelProbe.dataMenu(panel);
        SidebarPanelProbe.disposeFrame(panel);
        menu.setSize(Math.max(200, menu.getPreferredSize().width), Math.max(40, menu.getPreferredSize().height));
        // An unshown popup has no layout yet: place its items before painting.
        menu.doLayout();
        paintComponent(menu, new File(out, "g-f-data-recovery-menu.png"));
    }

    private static Engine grindsFixture()
    {
        Engine engine = engine();
        long now = System.currentTimeMillis() - 2 * 3_600_000L;
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, now);
        engine.getActiveSession().addTransaction(booked(now + 1_000L, "Dragon bones", 1, 2, 3_200, 6_400), 2_000);
        engine.getActiveSession().addTransaction(booked(now + 2_000L, "Battlestaff", 2, 5, 4_400, 22_000), 2_000);
        engine.getActiveSession().addTransaction(consumed(now + 3_000L, "Prayer potion(4)", 3, -2, 9_800, -19_600L), 2_000);
        engine.getActiveSession().setProfitTargetGp(5_000_000L);
        engine.getActiveSession().setActiveTimeTargetMillis(3L * 3_600_000L);
        engine.saveGrind("Vorkath", 5_000_000L, 3L * 3_600_000L, true, engine.getActiveSession().getId());
        engine.finishCustomSession(now + 10_000L);

        engine.startCustomSession("Zulrah", SessionMode.GENERAL, System.currentTimeMillis() - 60_000L);
        engine.getActiveSession().addTransaction(booked(System.currentTimeMillis() - 50_000L, "Magic logs", 5, 5, 1_000, 5_000), 2_000);
        engine.saveGrind("Zulrah", 2_000_000L, null, false, engine.getActiveSession().getId());
        engine.getActiveSession().setProfitTargetGp(5_000_000L);
        engine.getActiveSession().setActiveTimeTargetMillis(3L * 3_600_000L);
        return engine;
    }

    private static void paintComponent(javax.swing.JComponent component, File file) throws IOException
    {
        BufferedImage image = new BufferedImage(component.getWidth(), component.getHeight(),
            BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try
        {
            component.paint(g);
        }
        finally
        {
            g.dispose();
        }
        ImageIO.write(image, "png", file);
    }

    // ---- capture -----------------------------------------------------------------------------

    private static void liveShot(File out, String name, Engine engine, PvpState pvp) throws IOException
    {
        SidebarPanel panel = panel(engine, pvp);
        panel.shell().show(Shell.LIVE);
        SidebarPanelProbe.refresh(panel);
        paint(panel, new File(out, name));
    }

    private static void ledgerShot(File out, String name, Engine engine, @Nullable String transactionId,
        @Nullable Correction correction) throws IOException
    {
        SidebarPanel panel = panel(engine, PvpState.NONE);
        panel.shell().show(Shell.LEDGER);
        SidebarPanelProbe.refresh(panel);
        if (transactionId != null && !transactionId.isEmpty())
        {
            LedgerPageProbe.select(SidebarPanelProbe.ledgerPage(panel), transactionId);
            if (correction != null)
            {
                LedgerPageProbe.preview(SidebarPanelProbe.ledgerPage(panel), LedgerData.preview(engine, transactionId,
                    correction, System.currentTimeMillis()), correction);
            }
            SidebarPanelProbe.refresh(panel);
        }
        paint(panel, new File(out, name));
    }

    private static SidebarPanel panel(Engine engine, PvpState pvp)
    {
        JFrame frame = new JFrame("GP Manager preview");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        SidebarPanel panel = new SidebarPanel(engine, config(), null);
        DevBadge.apply(panel, BuildInfo.load());
        panel.bindPvp(() -> pvp);
        SidebarPanelProbe.bindSprites(panel, id -> dummySprite());
        Container content = frame.getContentPane();
        content.add(panel, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(242 + 6, 760));
        frame.pack();
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
        frame.validate();
        SidebarPanelProbe.frame(panel, frame);
        return panel;
    }

    private static void paint(SidebarPanel panel, File file) throws IOException
    {
        SidebarPanelProbe.refresh(panel);
        panel.validate();
        ShellProbe.scrollCurrentToTop(panel.shell());
        BufferedImage image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try
        {
            panel.paint(g);
        }
        finally
        {
            g.dispose();
        }
        SidebarPanelProbe.disposeFrame(panel);
        ImageIO.write(image, "png", file);
    }

    // ---- fixtures ----------------------------------------------------------------------------

    private static Transaction booked(long at, String name, int itemId, long quantity, int unitPrice, long value)
    {
        return new Transaction(at, null, TransactionType.LOOT, Context.LOOT, "Loot from Vorkath", "Vorkath", true,
            Collections.singletonList(new Flow(itemId, name, quantity, unitPrice, value)),
            ClassificationConfidence.LIKELY, "Preview sample.", null);
    }

    private static Transaction consumed(long at, String name, int itemId, long quantity, int unitPrice, long value)
    {
        if (name.endsWith("(4)"))
        {
            // A potion is drunk a dose at a time: each sip turns a (4) into a (3), as in game.
            int left = unitPrice * 3 / 4;
            Transaction sips = new Transaction(at, null, TransactionType.CONSUMPTION, Context.GENERIC,
                "", "Vorkath", true, java.util.Arrays.asList(
                    new Flow(itemId, name, quantity, unitPrice, quantity * unitPrice),
                    new Flow(itemId + 10_000, name.replace("(4)", "(3)"), -quantity, left, -quantity * left)),
                ClassificationConfidence.LIKELY, "Preview sample.", null);
            sips.setActionKind(ActionKind.DRINK);
            return sips;
        }
        return new Transaction(at, null, TransactionType.CONSUMPTION, Context.GENERIC, "", "Vorkath",
            true, Collections.singletonList(new Flow(itemId, name, quantity, unitPrice, value)),
            ClassificationConfidence.LIKELY, "Preview sample.", null);
    }

    private static Transaction review(long at, String name, int itemId, long quantity)
    {
        return new Transaction(at, null, TransactionType.UNCERTAIN, Context.GENERIC, "", "Vorkath",
            true, Collections.singletonList(new Flow(itemId, name, quantity, 0, 0L)),
            ClassificationConfidence.UNCERTAIN, "Preview sample: awaiting a decision.", null);
    }

    private static Transaction pkDeath(long at)
    {
        return new Transaction(at, null, TransactionType.PK_DEATH_LOSS, Context.PK_LOOT, "Death: Rival",
            "Wilderness", true, Collections.singletonList(new Flow(1, "Dragon bones", -1, 3_200, -3_200L)),
            ClassificationConfidence.LIKELY, "Preview sample.", null);
    }

    private static Engine engine()
    {
        return new Engine(deltas ->
        {
            java.util.List<Flow> flows = new java.util.ArrayList<>();
            deltas.forEach((id, quantity) -> flows.add(new Flow(id, "Item " + id, quantity, 50, quantity * 50)));
            return flows;
        }, new TransactionClassifier(), config());
    }

    private static GpManagerConfig config()
    {
        return new GpManagerConfig()
        {
            @Override
            public int stabilizationTicks()
            {
                return 1;
            }
        };
    }

    private SidebarPreview()
    {
    }
}
