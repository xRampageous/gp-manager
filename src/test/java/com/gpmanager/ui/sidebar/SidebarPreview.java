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
    /** Fixed clock for pixel comparisons; restore it when called in another test JVM. */
    public static void main(String[] args) throws Exception
    {
     java.util.function.LongSupplier c = SafeMath.clock;
     try {
      SafeMath.clock = () -> java.time.Instant.parse("2026-10-06T07:57:00Z").toEpochMilli();
      sh(args);
     } finally {
      SafeMath.clock = c;
     }
    }

    /** Render the same offline cases with a fixed clock and development badge. */
    static void sh(String[] args) throws Exception
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
        raidLoot(out);
        pausedPill(out);
        timeTarget(out);
        bothTargets(out);
        ledgerNormal(out);
        ledgerDetail(out);
        ledgerLootKey(out);
        ledgerCorrectionPreview(out);
        ledgerChargeUsed(out);
        ledgerChargeOneHit(out);
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
        long now = SafeMath.now() - 20 * 60_000L;
        engine.startCustom("Vorkath", now);
        engine.getActiveSession().addTransaction(consumed(now + 1_000L, "Prayer potion(4)", 2434, -12, 9_800, -117_600L), 2_000);
        liveShot(out, "a-live-normal-negative.png", engine, PvpState.NONE);
    }

    /** B. Full / positive: positive Net, five Recent rows, two-row target, Needs Review. */
    private static void fullPositive(File out) throws IOException
    {
        Engine engine = engine();
        long now = SafeMath.now() - 45 * 60_000L;
        engine.startCustom("Vorkath", now);
        engine.getActiveSession().addTransaction(booked(now + 1_000L, "Dragon bones", 536, 2, 3_930, 7_860), 2_000);
        engine.getActiveSession().addTransaction(booked(now + 2_000L, "Battlestaff", 1397, 5, 8_782, 43_910), 2_000);
        engine.getActiveSession().addTransaction(consumed(now + 3_000L, "Prayer potion(4)", 2434, -2, 9_800, -19_600L), 2_000);
        engine.getActiveSession().addTransaction(booked(now + 4_000L, "Skeletal visage", 22006, 1, 11_590_000, 11_590_000), 2_000);
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
        long now = SafeMath.now() - 15 * 60_000L;
        engine.startCustom("Wilderness", now);
        engine.getActiveSession().addTransaction(booked(now + 1_000L, "Dragon bones", 536, 1, 3_930, 3_930), 2_000);
        engine.getActiveSession().addTransaction(pkDeath(now + 2_000L), 2_000);
        // Owner live 2026-10-13: a PK kill whose victim dropped a loot key (unopened) and bones.
        engine.getActiveSession().addTransaction(pkKill(now + 4_000L), 2_000);
        engine.getActiveSession().addTransaction(keyClaim(now + 6_000L), 2_000);
        engine.getActiveSession().addEncounter(false, now + 4_000L);
        liveShot(out, "d-live-pvp-context.png", engine, new PvpState(true, true, true, 0L));
    }

    /** E. A looted raid: Theatre of Blood, a busy run and the scythe. */
    private static void raidLoot(File out) throws IOException
    {
        Engine engine = engine();
        long now = SafeMath.now() - 35 * 60_000L;
        engine.startCustom("Theatre of Blood", now);
        engine.getActiveSession().addTransaction(booked(now + 1_000L, "Scythe of vitur", 22486, 1, 1_219_300_000, 1_219_300_000L), 2_000);
        engine.getActiveSession().addTransaction(booked(now + 2_000L, "Blood rune", 565, 56, 339, 18_984L), 2_000);
        engine.getActiveSession().addTransaction(booked(now + 3_000L, "Death rune", 560, 128, 187, 23_936L), 2_000);
        engine.getActiveSession().addTransaction(booked(now + 4_000L, "Nature rune", 561, 33, 199, 6_567L), 2_000);
        // Vials of blood charge the scythe: a charge component, booked under Supplies (owner
        // live 2026-10-13: as a bare consumption the engine honestly called it a loss).
        engine.bookEstimatedCharge("worn:scythe", "Scythe of vitur", ActionKind.SUPPLIES,
            java.util.List.of(new Flow(22446, "Vial of blood", -4L, 8_600, -34_400L)), now + 5_000L);
        liveShot(out, "e-live-raid-tob.png", engine, PvpState.NONE);
    }

    /** X. EXPERIMENT scratch: paused session, so the hero's status word chip is visible. */
    private static void pausedPill(File out) throws IOException
    {
        Engine engine = engine();
        long now = SafeMath.now() - 8 * 60_000L;
        engine.startCustom("Vorkath", now);
        engine.getActiveSession().addTransaction(booked(now + 1_000L, "Dragon bones", 536, 2, 3_930, 7_860), 2_000);
        engine.togglePause(SafeMath.now());
        liveShot(out, "x-live-paused-pill.png", engine, PvpState.NONE);
    }

    /** F. A time target in progress: the Target row reads its countdown (owner live 2026-10-13). */
    private static void timeTarget(File out) throws IOException
    {
        Engine engine = engine();
        long now = SafeMath.now() - 35 * 60_000L;
        engine.startCustom("Zulrah", now);
        engine.getActiveSession().addTransaction(booked(now + 1_000L, "Zulrah's scales", 12934, 247, 154, 38_038L), 2_000);
        engine.getActiveSession().setActiveTimeTargetMillis(3L * 3_600_000L, SafeMath.now());
        // The fixture shows a target set 35m ago (the timer counts from the set moment).
        engine.getActiveSession().activeTimeTargetSetMillis =
                engine.getActiveSession().getElapsedMillis(SafeMath.now()) - 35L * 60_000L;
        liveShot(out, "f-live-time-target.png", engine, PvpState.NONE);
    }

    /**
     * F2. Both targets: the hero carries the live Net pair and the card adds the Active Time
     * timer without repeating the Net (owner live 2026-10-13).
     */
    private static void bothTargets(File out) throws IOException
    {
        Engine engine = engine();
        long now = SafeMath.now() - 62 * 60_000L;
        engine.startCustom("Zulrah", now);
        engine.getActiveSession().addTransaction(booked(now + 1_000L, "Zulrah's scales", 12934, 247, 154, 38_038L), 2_000);
        engine.setTargets(200_000L, 150L * 60_000L, SafeMath.now());
        // The fixture's aim counts the run's net: baseline zero.
        engine.getActiveSession().profitTargetSetNet = 0L;
        // The fixture shows the timer set at the run's start (62m in), not a fresh zero.
        engine.getActiveSession().activeTimeTargetSetMillis =
                engine.getActiveSession().getElapsedMillis(SafeMath.now()) - 62L * 60_000L;
        liveShot(out, "f2-live-both-targets.png", engine, PvpState.NONE);
    }

    // ---- Ledger ------------------------------------------------------------------------------

    private static void ledgerNormal(File out) throws IOException
    {
        ledgerShot(out, "led-a-ledger-normal.png", mixedEngine(), null, null);
    }

    /** The loot key's ledger entry: the value it held, with the claimed setup listed under it. */
    private static void ledgerLootKey(File out) throws IOException
    {
        Engine engine = engine();
        long now = SafeMath.now() - 8 * 60_000L;
        engine.startCustom("Wilderness", now);
        engine.getActiveSession().addTransaction(booked(now + 1_000L, "Dragon bones", 536, 1, 3_930, 3_930L), 2_000);
        Transaction key = keyClaim(now + 2_000L);
        engine.getActiveSession().addTransaction(key, 2_000);
        ledgerShot(out, "led-h-ledger-key.png", engine, key.getId(), null);
    }

    private static void ledgerDetail(File out) throws IOException    {
        Engine engine = mixedEngine();
        ledgerShot(out, "led-c-ledger-detail.png", engine,
            engine.getActiveSession().getTransactions().get(0).getId(), null);
    }

    /** Owner 2026-10-03: a charged weapon's row lists what its uses spent, item by item. */
    private static void ledgerChargeUsed(File out) throws IOException
    {
        Engine engine = engine();
        long now = SafeMath.now() - 20 * 60_000L;
        engine.startCustom("Theatre of Blood", now);
        Transaction first = null;
        for (int hit = 0; hit < 62; hit++)
        {
            java.util.List<Flow> spent = new java.util.ArrayList<>(java.util.List.of(
                new Flow(565, "Blood rune", -2L, 339, -678L)));
            if (hit % 50 == 49)
            {
                spent.add(new Flow(22446, "Vial of blood", -1L, 8_600, -8_600L));
            }
            Transaction booked = engine.bookEstimatedCharge("worn:scythe", "Scythe of vitur", ActionKind.SUPPLIES, spent, now + hit * 2_400L);
            first = first == null ? booked : first;
        }
        ledgerShot(out, "led-f-ledger-charge-used.png", engine, first == null ? null : first.getId(), null);
    }

    /** Owner 1.2: a single scythe hit still names the blood runes it spent. */
    private static void ledgerChargeOneHit(File out) throws IOException
    {
        Engine engine = engine();
        long now = SafeMath.now() - 5 * 60_000L;
        engine.startCustom("Theatre of Blood", now);
        Transaction hit = engine.bookEstimatedCharge("worn:scythe", "Scythe of vitur", ActionKind.SUPPLIES,
            java.util.List.of(new Flow(565, "Blood rune", -2L, 339, -678L)), now);
        ledgerShot(out, "led-g-ledger-charge-one-hit.png", engine, hit == null ? null : hit.getId(), null);
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
        long now = SafeMath.now() - 48 * 60 * 60_000L;
        engine.startCustom("Old grind", now);
        for (int i = 0; i < 8; i++)
        {
            engine.getActiveSession().addTransaction(booked(now + i, "Old bone " + i, 1, 1, 100, 100), 2_000);
        }
        engine.getActiveSession().compactBefore(now + 7L, transaction -> false);
        ledgerShot(out, "led-e-ledger-compacted.png", engine, null, null);
    }


    // ---- R3.1B rate / pace / nav-focus fixtures ----------------------------------------------

    /** A. Fresh Live Grind: zero Net and an unavailable rate must never read as 0/h. */
    private static void r31FreshLive(File out) throws IOException
    {
        Engine engine = engine();
        long now = SafeMath.now();
        engine.startCustom("2323", now - 20_000L);
        engine.getActiveSession().addTransaction(booked(now - 15_000L, "Willow logs", 1519, 5, 22, 110), 2_000);
        engine.getActiveSession().addTransaction(consumed(now - 10_000L, "Prayer potion(4)", 2434, -1, 210, -210L), 2_000);
        liveShot(out, "r31-a-fresh-live.png", engine, PvpState.NONE);
    }

    /** B. Current Grind with no targets: no Pace question, so no Pace row and no dead space. */
    private static void r31GrindsNoTargets(File out) throws IOException
    {
        Engine engine = engine();
        long now = SafeMath.now();
        engine.startCustom("Vorkath", now - 20_000L);
        engine.getActiveSession().addTransaction(booked(now - 15_000L, "Dragon bones", 536, 1, 3_930, 3_930), 2_000);
        SidebarPanel panel = panel(engine, PvpState.NONE);
        panel.shell().show(Shell.GRINDS);
        paint(panel, new File(out, "r31-b-grinds-no-targets.png"));
    }

    /** C. Target set but the rate is immature: the honest Calculating state, not a projection. */
    private static void r31GrindsTargetCalculating(File out) throws IOException
    {
        Engine engine = engine();
        long now = SafeMath.now();
        engine.startCustom("Vorkath", now - 20_000L);
        engine.getActiveSession().addTransaction(booked(now - 15_000L, "Dragon bones", 536, 1, 3_930, 3_930), 2_000);
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
        long now = SafeMath.now();
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
        long now = SafeMath.now();
        finishedLinked(engine, "Vorkath", now - 4 * 3_600_000L, 3_600_000L, 400_000L, 3 * 3_600_000L);
        engine.getHistory().get(0).compactBefore(now - 2 * 3_600_000L, transaction -> false);
        SidebarPanel panel = panel(engine, PvpState.NONE);
        panel.shell().show(Shell.GRINDS);
        SidebarPanelProbe.openGrindsDetail(panel, latestSessionNamed(engine, "Vorkath"));
        paint(panel, new File(out, "r4-g-grind-detail-compacted.png"));
    }

    private static Engine insightsFixture()
    {
        Engine engine = engine();
        long now = SafeMath.now();
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
        engine.getActiveSession().addTransaction(booked(startedAt + 1_000L, "Dragon bones", 536, 1,
            (int) gain, gain), 2_000);
        Transaction supply = consumed(startedAt + 2_000L, "Prayer potion(4)", 2434, -1,
            (int) Math.max(1L, cost / 3L), -cost);
        supply.setActionKind(ActionKind.DRINK);
        engine.getActiveSession().addTransaction(supply, 2_000);
        engine.finishCustom(startedAt + activeMillis);
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
        long now = SafeMath.now() - 5 * 60_000L;
        engine.startCustom("Vorkath", now);
        engine.getActiveSession().addTransaction(booked(now + 1_000L, "Cooked chicken", 2140, 1, 62, 62L), 2_000);
        Transaction drink = consumed(now + 2_000L, "Super attack potion(4)", 3, -1, 2_400, -2_400L);
        drink.setActionKind(ActionKind.DRINK);
        engine.getActiveSession().addTransaction(drink, 2_000);
        engine.getActiveSession().addTransaction(new Transaction(now + 3_000L, TransactionType.GAIN,
            Context.GENERIC, "", "Wilderness Agility", true,
            Collections.singletonList(new Flow(1,
                "Extremely long ancient item name for width", 1, 99_900_000, 99_900_000L)),
            Confidence.LIKELY, "Preview sample.", null), 2_000);
        SidebarPanel panel = panel(engine, PvpState.NONE);
        panel.shell().show(Shell.LEDGER);
        paint(panel, new File(out, "r41-f-ledger-long-row.png"));
    }

    private static Engine openOnlyFixture()
    {
        Engine engine = engine();
        long now = SafeMath.now();
        engine.startCustom("Vorkath", now - 3_600_000L);
        engine.getActiveSession().addTransaction(booked(now - 3_500_000L, "Dragon bones", 536, 1, 3_930, 3_930L),
            2_000);
        return engine;
    }

    // ---- R4.1A fitting fixtures ----------------------------------------------------------------

    /** A. Ledger with a real dummy icon, long item/source and a large positive value. */
    private static void r41aLedgerIconPositive(File out) throws IOException
    {
        SidebarPanel panel = panel(ledgerIconEngine(99_900_000L), PvpState.NONE);
        SidebarPanelProbe.bindSprites(panel, SidebarPreview::previewSprite);
        panel.shell().show(Shell.LEDGER);
        paint(panel, new File(out, "r41a-a-ledger-icon-positive.png"));
    }

    /** B. Ledger with a real dummy icon, long item/source and a large negative value. */
    private static void r41aLedgerIconNegative(File out) throws IOException
    {
        SidebarPanel panel = panel(ledgerIconEngine(-99_900_000L), PvpState.NONE);
        SidebarPanelProbe.bindSprites(panel, SidebarPreview::previewSprite);
        panel.shell().show(Shell.LEDGER);
        paint(panel, new File(out, "r41a-b-ledger-icon-negative.png"));
    }

    private static Engine ledgerIconEngine(long longValue)
    {
        Engine engine = engine();
        long now = SafeMath.now() - 5 * 60_000L;
        engine.startCustom("Vorkath", now);
        engine.getActiveSession().addTransaction(consumed(now + 1_000L, "Cooked chicken", 2140, 1, 62, -62L),
            2_000);
        engine.getActiveSession().addTransaction(new Transaction(now + 2_000L,
            longValue >= 0L ? TransactionType.GAIN : TransactionType.CONSUMPTION, Context.GENERIC, "",
            "Extremely Long Wilderness Activity Name", true,
            Collections.singletonList(new Flow(1, "Extremely long ancient item name for width", 1,
                (int) Math.abs(longValue), longValue)),
            Confidence.LIKELY, "Preview sample.", null), 2_000);
        return engine;
    }

    private static Engine longNameFixture()
    {
        Engine engine = engine();
        long now = SafeMath.now();
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
        engine.getActiveSession().addTransaction(booked(start + 1_000L, "Dragon bones", 536, 1,
            (int) net, net), 2_000);
        engine.finishCustom(end);
    }

    /** Deterministic visible item imagery so the icon-present row path is actually exercised. */
    /** The fetched real item image when present; else the fixture's dummy sprite. */
    private static BufferedImage previewSprite(int itemId)
    {
        BufferedImage real = PreviewIcons.sprite(itemId);
        return real == null ? dummySprite() : real;
    }

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
        long now = SafeMath.now() - 30 * 60_000L;
        engine.startCustom("Vorkath", now);
        engine.getActiveSession().addTransaction(booked(now + 1_000L, "Willow logs", 1519, 5, 22, 110), 2_000);
        engine.getActiveSession().addTransaction(consumed(now + 2_000L, "Prayer potion(4)", 2434, -2, 9_800, -19_600L), 2_000);
        engine.getActiveSession().addTransaction(booked(now + 3_000L, "Battlestaff", 1397, 5, 8_782, 43_910), 2_000);
        engine.getActiveSession().addTransaction(pkDeath(now + 4_000L), 2_000);
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
        long now = SafeMath.now();
        finishedGrind(engine, "Vorkath", now - day, 2_840_000L);
        finishedGrind(engine, "Zulrah", now - 2 * day, -412_000L);
        finishedGrind(engine, "Muspah", now - 3 * day, 612_000L);
        SidebarPanel panel = panel(engine, PvpState.NONE);
        panel.shell().show(Shell.GRINDS);
        paint(panel, new File(out, "g-c-recent-grinds.png"));
    }

    private static void finishedGrind(Engine engine, String name, long startedAt, long net)
    {
        engine.startCustom(name, startedAt);
        engine.getActiveSession().addTransaction(booked(startedAt + 1_000L, "Willow logs", 1519, 5, 42,
            Math.abs(net) / 5), 2_000);
        engine.getActiveSession().addTransaction(consumed(startedAt + 2_000L, "Prayer potion(4)", 2434, -5, 100,
            net < 0 ? net * 4 / 5 : -Math.abs(net) / 5), 2_000);
        engine.finishCustom(startedAt + 3_000L);
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
        long now = SafeMath.now() - 2 * 3_600_000L;
        engine.startCustom("Vorkath", now);
        engine.getActiveSession().addTransaction(booked(now + 1_000L, "Dragon bones", 536, 2, 3_930, 7_860), 2_000);
        engine.getActiveSession().addTransaction(booked(now + 2_000L, "Battlestaff", 1397, 5, 8_782, 43_910), 2_000);
        engine.getActiveSession().addTransaction(consumed(now + 3_000L, "Prayer potion(4)", 2434, -2, 9_800, -19_600L), 2_000);
        engine.getActiveSession().setProfitTargetGp(5_000_000L);
        engine.getActiveSession().setActiveTimeTargetMillis(3L * 3_600_000L, SafeMath.now());
        engine.saveGrind("Vorkath", 5_000_000L, 3L * 3_600_000L, true, engine.getActiveSession().getId());
        engine.finishCustom(now + 10_000L);

        engine.startCustom("Zulrah", SafeMath.now() - 60_000L);
        engine.getActiveSession().addTransaction(booked(SafeMath.now() - 50_000L, "Magic logs", 1513, 5, 699, 3_495), 2_000);
        engine.saveGrind("Zulrah", 2_000_000L, null, false, engine.getActiveSession().getId());
        engine.getActiveSession().setProfitTargetGp(5_000_000L);
        engine.getActiveSession().setActiveTimeTargetMillis(3L * 3_600_000L, SafeMath.now());
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
                    correction, SafeMath.now()), correction);
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
        DevBadge.apply(panel, BuildInfo.of("1.2.0", "1.2.0-dev", "108",
            "2026-10-06T07:57:00Z", "preview", "1.13.1", true));
        panel.bindPvp(() -> pvp);
        SidebarPanelProbe.bindSprites(panel, SidebarPreview::previewSprite);
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
        return new Transaction(at, TransactionType.LOOT, Context.LOOT, "Loot from Vorkath", "Vorkath", true,
            Collections.singletonList(new Flow(itemId, name, quantity, unitPrice, value)),
            Confidence.LIKELY, "Preview sample.", null);
    }

    private static Transaction consumed(long at, String name, int itemId, long quantity, int unitPrice, long value)
    {
        if (name.endsWith("(4)"))
        {
            // A potion is drunk a dose at a time: each sip turns a (4) into a (3), as in game.
            int left = unitPrice * 3 / 4;
            Transaction sips = new Transaction(at, TransactionType.CONSUMPTION, Context.GENERIC,
                "", "Vorkath", true, java.util.Arrays.asList(
                    new Flow(itemId, name, quantity, unitPrice, quantity * unitPrice),
                    new Flow(itemId + 10_000, name.replace("(4)", "(3)"), -quantity, left, -quantity * left)),
                Confidence.LIKELY, "Preview sample.", null);
            sips.setActionKind(ActionKind.DRINK);
            return sips;
        }
        return new Transaction(at, TransactionType.CONSUMPTION, Context.GENERIC, "", "Vorkath",
            true, Collections.singletonList(new Flow(itemId, name, quantity, unitPrice, value)),
            Confidence.LIKELY, "Preview sample.", null);
    }

    private static Transaction pkDeath(long at)
    {
        return new Transaction(at, TransactionType.PK_DEATH_LOSS, Context.PK_LOOT, "Death: Rival",
            "Wilderness", true, Collections.singletonList(new Flow(536, "Dragon bones", -1, 3_930, -3_930L)),
            Confidence.LIKELY, "Preview sample.", null);
    }

    /** The kill's direct loot; the key arrives as a claim and books when it is opened. */
    private static Transaction pkKill(long at)
    {
        return new Transaction(at, TransactionType.PK_LOOT, Context.PK_LOOT, "Kill: Rival",
            "Wilderness", true,
            Collections.singletonList(new Flow(536, "Dragon bones", 1, 3_930, 3_930L)),
            Confidence.LIKELY, "Preview sample.", PkTally.KILL + "preview");
    }

    /**
     * The opened key (owner live 2026-10-13): the pocket owns the value, the claimed setup books
     * under it — one action row, no claim leg plus separate proceeds.
     */
    private static Transaction keyClaim(long at)
    {
        return new Transaction(at, TransactionType.PK_LOOT, Context.PK_LOOT, "Looted key",
            "Wilderness", true,
            java.util.Arrays.asList(
                new Flow(26651, "Loot key", -1, 0, 0L, PriceSource.DEFERRED_CLAIM),
                new Flow(26219, "Osmumten's fang", 1, 16_472_410, 16_472_410L),
                new Flow(6737, "Berserker ring", 1, 3_667_795, 3_667_795L)),
            Confidence.LIKELY, "Claimed at the chest \u2014 the setup it held.",
            PkTally.KILL + "preview");
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
