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
        CategoryToggleTest.previews(out);
        DeathViewTest.previews(out);
        var previewClock = SafeMath.clock;
        KillViewTest.previews(out);
        if (SafeMath.clock != previewClock) throw new AssertionError("KILLS preview leaked its clock");
        LiveCategoryLayoutTest.retainedPreviews(out);
        PvpDeathSkullTest.previews(out);
        RaidHistoryUiTest.previews(out);
        savedSessions(out);
        HudPreview.write(out);
        for (int width : new int[]{213, 225, 300})
            paintComponent(GeStatusPresentationTest.preview(width), new File(out, "ge-status-" + width + ".png"));
        Engine pickups = PresentationLifecycleTest.engine();
        long at = SafeMath.now() - 60_000L;
        pickups.startCustom("Greater Nechryael", at);
        pickups.session().addTransaction(RecentSupplyAndPickupTest.item(TransactionType.GAIN, null,
            565, "Blood rune", 200L, 344, at + 1L), 2000, false);
        pickups.session().addTransaction(RecentSupplyAndPickupTest.item(TransactionType.LOOT, null,
            1359, "Rune axe", 10L, 7060, at + 2L), 2000, false);
        pickups.session().addTransaction(RecentSupplyAndPickupTest.item(TransactionType.LOOT, null,
            565, "Blood rune", 120L, 344, at + 3L), 2000, false);
        liveShot(out, "f6-live-combined-blood-pickups.png", pickups, PvpState.NONE);
        for (String title : new String[]{"Wilderness Agility Course", "Chambers of Xeric: Challenge Mode"}) {
            Engine activity = PresentationLifecycleTest.engine();
            activity.ensureSession(at);
            activity.session().setActivityHint(title);
            SidebarPanel panel = panel(activity, PvpState.NONE);
            paint(panel,new File(out,"header-"+title.substring(0,title.indexOf(' ')).toLowerCase()+".png"));
            if (title.equals("Wilderness Agility Course")) {
                activity.session().pause(SafeMath.now(), PauseReason.IDLE);
                liveShot(out, "header-wilderness-away.png", activity, PvpState.NONE);
                activity.resume(SafeMath.now(), PauseReason.IDLE);
                liveShot(out, "header-wilderness-calibrating.png", activity, PvpState.NONE);
            }
        }
        Engine loading = PresentationLifecycleTest.engine();
        loading.startCustom("Previous account", at);
        SidebarPanel switching = panel(loading, PvpState.NONE);
        SidebarPanelProbe.refresh(switching);
        switching.suspend();
        paint(switching, new File(out, "profile-loading.png"));
        directHeaders(out, at);
        retainedTargetDrafts(out);
        statusAndRateControls(out);
        normalNegative(out);
        fullPositive(out);
        emptyWaiting(out);
        pvpContext(out);
        LiveDeathFeedbackTest.previews(out);
        CapturedBlightedRecoveryTest.previews(out);
        raidLoot(out);
        pausedPill(out);
        timeTarget(out);
        bothTargets(out);
        ledgerNormal(out);
        ledgerDetail(out);
        deathDetails(out);
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
        sheets(out);
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

    private static void savedSessions(File out) throws IOException {
        JsonCodec.bind(new com.google.gson.Gson());
        long now = SafeMath.now();
        Engine engine = PresentationLifecycleTest.engine();
        engine.ensureSession(now - 3_600_000L);
        TargetFreshStartTest.coins(engine, now - 3_600_000L + 5L, 500_000L);
        engine.newLive(now - 60_000L);
        TargetFreshStartTest.coins(engine, now - 59_995L, 25_000L);
        SidebarPanel prompt = panel(engine, PvpState.NONE);
        prompt.actions.saveSessionPrompt();
        paint(prompt, new File(out, "saved-session-prompt.png"));
        String id = EngineProbe.saveLiveSession(engine, engine.session(), engine.session().sessionStartAt, now);
        engine.showWholeRun();
        SidebarPanel list = panel(engine, PvpState.NONE);
        list.shell.show(Shell.GRINDS);
        paint(list, new File(out, "saved-sessions-grinds.png"));
        SidebarPanel detail = panel(engine, PvpState.NONE);
        detail.selection = id;
        detail.shell.show(Shell.GRINDS);
        paint(detail, new File(out, "saved-session-detail.png"));
        SidebarPanel ledger = panel(engine, PvpState.NONE);
        ledger.actions.openLedger(id);
        paint(ledger, new File(out, "saved-session-ledger.png"));
    }

    private static void statusAndRateControls(File out) throws IOException
    {
        long now = SafeMath.now();
        long hour = 3_600_000L;
        Engine engine = PresentationLifecycleTest.engine();
        engine.ensureSession(now - hour);
        engine.session().setActivityHint("Wilderness Agility Course");
        TargetFreshStartTest.coins(engine, now - hour + 5L, 500_000L);
        SidebarPanel controls = panel(engine, PvpState.NONE);
        new GrindsController(controls).newSession();
        paint(controls, new File(out, "session-controls.png"));
        for (int width : new int[]{213, 225, 300}) {
            SidebarPanel sized = panel(engine, PvpState.NONE);
            new GrindsController(sized).newSession();
            java.awt.Window frame = SwingUtilities.getWindowAncestor(sized);
            frame.setSize(width, frame.getHeight());
            frame.validate();
            paint(sized, new File(out, "session-controls-" + width + ".png"));
        }
        SidebarPanel restarted = panel(engine, PvpState.NONE);
        java.util.function.LongSupplier savedClock = SafeMath.clock;
        try {
            SafeMath.clock = () -> now - hour / 2L;
            new GrindsController(restarted).newSession();
            ShellProbe.pressSheet(restarted.shell, "Restart GP/h");
        } finally { SafeMath.clock = savedClock; }
        TargetFreshStartTest.coins(engine, now - hour / 2L + 5L, 50_000L);
        paint(restarted, new File(out, "rate-restarted-keeps-net.png"));
        for (int seconds : new int[]{60, 72, 120}) {
            Engine retained = PresentationLifecycleTest.engine();
            retained.ensureSession(now - hour);
            TargetFreshStartTest.coins(retained, now - hour + 5L, 566_329L);
            retained.restartRate(now - seconds * 1_000L, true);
            liveShot(out, "rate-retained-net-" + seconds + "s.png", retained, PvpState.NONE);
        }
        engine.pauseForLifecycle(now);
        liveShot(out, "status-logged-out.png", engine, PvpState.NONE);
        SidebarPanel reconnecting = panel(engine, PvpState.NONE);
        reconnecting.bindHopping(() -> true);
        paint(reconnecting, new File(out, "status-reconnecting.png"));
        Engine resuming = PresentationLifecycleTest.engine();
        resuming.startAt = now + 3_000L;
        liveShot(out, "status-resuming.png", resuming, PvpState.NONE);
        SidebarPanel deletion = panel(engine, PvpState.NONE);
        new GrindsController(deletion).factoryReset();
        paint(deletion, new File(out, "delete-profile-choices.png"));
    }

    private static void retainedTargetDrafts(File out) throws IOException
    {
        Engine engine = PresentationLifecycleTest.engine();
        engine.ensureSession(SafeMath.now() - 60_000L);
        for (int i = 0; i < 8; i++) engine.saveGrind("Saved route " + i, null, null, false);
        SidebarPanel panel = panel(engine, PvpState.NONE);
        GrindsController controls = new GrindsController(panel);
        controls.newGrind();
        ShellProbe.pressSheet(panel.shell, "›");
        PolishInteractionTest.field(panel.shell, "nameField").setText("Wilderness route");
        PolishInteractionTest.field(panel.shell, "netField").setText("lots");
        PolishInteractionTest.field(panel.shell, "timeField").setText("1h 30m");
        ShellProbe.pressSheet(panel.shell, "Start");
        paint(panel, new File(out, "polish-start-invalid.png"));
        // paint disposes its frame; each shot needs a fresh realized panel.
        panel = panel(engine, PvpState.NONE);
        controls = new GrindsController(panel);
        controls.openTargets();
        PolishInteractionTest.field(panel.shell, "netField").setText("2m");
        PolishInteractionTest.field(panel.shell, "timeField").setText("3h");
        PolishInteractionTest.field(panel.shell, "milestoneField").setText("many");
        ShellProbe.pressSheet(panel.shell, "Save");
        paint(panel, new File(out, "polish-target-invalid.png"));
    }

    private static void directHeaders(File out, long at) throws IOException
    {
        Engine engine = PresentationLifecycleTest.engine();
        String name = "Wilderness Agility Course & a long northern expedition";
        engine.startCustom(name, at);
        String id = engine.session().id();
        engine.session().addTransaction(booked(at + 1L, "Blood rune", 565, 200, 344, 68_800L), 2_000, false);
        engine.saveGrind("Barrows", null, null, false).archived = true;
        SidebarPanel grinds = panel(engine, PvpState.NONE);
        grinds.shell.show(Shell.GRINDS);
        grinds.plans.archived.doClick();
        paint(grinds, new File(out, "direct-grinds-long-name.png"));
        engine.finishCustom(at + 10_000L);
        SidebarPanel ledger = panel(engine, PvpState.NONE);
        ledger.scope = new LedgerData.Entry(LedgerData.Scope.HISTORY, id, name);
        ledger.shell.show(Shell.LEDGER);
        paint(ledger, new File(out, "direct-ledger-long-scope.png"));
        Engine review = PresentationLifecycleTest.engine();
        review.startCustom("Zulrah", at);
        review.session().addTransaction(new Transaction(at + 1L, TransactionType.UNCERTAIN, Context.GENERIC,
            "", "Zulrah", false, java.util.List.of(new Flow(995, "Coins", -500L, 1, -500L)), Confidence.UNCERTAIN,
            "Unconfirmed movement.", null), 2_000, false);
        liveShot(out, "direct-live-review.png", review, PvpState.NONE);
    }

    /** A. Normal / negative: negative Net, GP/h, breakdown, one Recent row, no target, no review. */
    private static void normalNegative(File out) throws IOException
    {
        Engine engine = engine();
        long now = SafeMath.now() - 20 * 60_000L;
        engine.startCustom("Vorkath", now);
        engine.session().addTransaction(consumed(now + 1_000L, "Prayer potion(4)", 2434, -12, 9_800, -117_600L), 2_000, false);
        liveShot(out, "a-live-normal-negative.png", engine, PvpState.NONE);
    }

    /** B. Full / positive: positive Net, five Recent rows, two-row target, Needs Review. */
    private static void fullPositive(File out) throws IOException
    {
        Engine engine = engine();
        long now = SafeMath.now() - 45 * 60_000L;
        engine.startCustom("Vorkath", now);
        engine.session().addTransaction(booked(now + 1_000L, "Dragon bones", 536, 2, 3_930, 7_860), 2_000, false);
        engine.session().addTransaction(booked(now + 2_000L, "Battlestaff", 1397, 5, 8_782, 43_910), 2_000, false);
        engine.session().addTransaction(consumed(now + 3_000L, "Prayer potion(4)", 2434, -2, 9_800, -19_600L), 2_000, false);
        engine.session().addTransaction(booked(now + 4_000L, "Skeletal visage", 22006, 1, 11_590_000, 11_590_000), 2_000, false);
        engine.session().setNetTarget(1_000_000L, 0, 0);
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
        engine.session().addTransaction(booked(now + 1_000L, "Dragon bones", 536, 1, 3_930, 3_930), 2_000, false);
        engine.session().addTransaction(pkDeath(now + 2_000L), 2_000, false);
        // Owner live 2026-10-13: a PK kill whose victim dropped a loot key (unopened) and bones.
        engine.session().addTransaction(pkKill(now + 4_000L), 2_000, false);
        engine.session().addTransaction(keyClaim(now + 6_000L), 2_000, false);
        engine.session().addEncounter(false, now + 2_000L);
        engine.session().addEncounter(true, now + 4_000L);
        liveShot(out, "d-live-pvp-context.png", engine, new PvpState(true, true, true, 140_000L));
    }

    /** E. A looted raid: Theatre of Blood, a busy run and the scythe. */
    private static void raidLoot(File out) throws IOException
    {
        Engine engine = engine();
        long now = SafeMath.now() - 35 * 60_000L;
        engine.startCustom("Theatre of Blood", now);
        engine.session().addTransaction(booked(now + 1_000L, "Scythe of vitur", 22486, 1, 1_219_300_000, 1_219_300_000L), 2_000, false);
        engine.session().addTransaction(booked(now + 2_000L, "Blood rune", 565, 56, 339, 18_984L), 2_000, false);
        engine.session().addTransaction(booked(now + 3_000L, "Death rune", 560, 128, 187, 23_936L), 2_000, false);
        engine.session().addTransaction(booked(now + 4_000L, "Nature rune", 561, 33, 199, 6_567L), 2_000, false);
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
        engine.session().addTransaction(booked(now + 1_000L, "Dragon bones", 536, 2, 3_930, 7_860), 2_000, false);
        engine.togglePause(SafeMath.now());
        liveShot(out, "x-live-paused-pill.png", engine, PvpState.NONE);
    }

    /** F. A time target in progress: the Target row reads its countdown (owner live 2026-10-13). */
    private static void timeTarget(File out) throws IOException
    {
        Engine engine = engine();
        long now = SafeMath.now() - 35 * 60_000L;
        engine.startCustom("Zulrah", now);
        engine.session().addTransaction(booked(now + 1_000L, "Zulrah's scales", 12934, 247, 154, 38_038L), 2_000, false);
        engine.session().setTimeTarget(3L * 3_600_000L, SafeMath.now());
        // The fixture shows a target set 35m ago (the timer counts from the set moment).
        engine.session().activeTimeTargetSetMillis =
                engine.session().elapsed(SafeMath.now()) - 35L * 60_000L;
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
        engine.session().addTransaction(booked(now + 1_000L, "Zulrah's scales", 12934, 247, 154, 38_038L), 2_000, false);
        engine.setTargets(200_000L, 150L * 60_000L, SafeMath.now());
        // The fixture's aim counts the run's net: baseline zero.
        engine.session().profitTargetSetNet = 0L;
        engine.session().sessionStartAt = null;
        engine.session().profitTargetSetActiveMillis = 0L;
        // The fixture shows the timer set at the run's start (62m in), not a fresh zero.
        engine.session().activeTimeTargetSetMillis =
                engine.session().elapsed(SafeMath.now()) - 62L * 60_000L;
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
        engine.session().addTransaction(booked(now + 1_000L, "Dragon bones", 536, 1, 3_930, 3_930L), 2_000, false);
        Transaction key = keyClaim(now + 2_000L);
        engine.session().addTransaction(key, 2_000, false);
        ledgerShot(out, "led-h-ledger-key.png", engine, key.id(), null);
    }

    private static void ledgerDetail(File out) throws IOException    {
        Engine engine = mixedEngine();
        ledgerShot(out, "led-c-ledger-detail.png", engine,
            engine.session().transactions().get(0).id(), null);
    }

    private static void deathDetails(File out) throws IOException {
        Engine engine=engine(); long now=SafeMath.now(); engine.ensureSession(now-60000);
        Transaction bag=DeathRowGroupingTest.bag("death:preview"),gear=DeathRowGroupingTest.gear("death:preview");
        bag.timestamp=now-6000; gear.timestamp=now-5400;
        engine.session().addTransaction(bag,2000,false); engine.session().addTransaction(gear,2000,false);
        liveShot(out,"death-recent.png",engine,PvpState.NONE);
        for (boolean contents : new boolean[]{false,true}) {
            SidebarPanel panel=panel(engine,PvpState.NONE);panel.shell.show(Shell.LEDGER);
            LedgerPageProbe.select(panel.ledger,gear.id()); SidebarPanelProbe.refresh(panel);
            if(contents) panel.ledger.items(panel.ledger.data.detail.group).stream()
                    .filter(row->row.name.equals("Looting bag")).findFirst().orElseThrow().open.run();
            paint(panel,new File(out,contents?"death-bag-contents.png":"death-detail.png"));
        }
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
        ledgerShot(out, "led-f-ledger-charge-used.png", engine, first == null ? null : first.id(), null);
    }

    /** Owner 1.2: a single scythe hit still names the blood runes it spent. */
    private static void ledgerChargeOneHit(File out) throws IOException
    {
        Engine engine = engine();
        long now = SafeMath.now() - 5 * 60_000L;
        engine.startCustom("Theatre of Blood", now);
        Transaction hit = engine.bookEstimatedCharge("worn:scythe", "Scythe of vitur", ActionKind.SUPPLIES,
            java.util.List.of(new Flow(565, "Blood rune", -2L, 339, -678L)), now);
        ledgerShot(out, "led-g-ledger-charge-one-hit.png", engine, hit == null ? null : hit.id(), null);
    }

    private static void ledgerCorrectionPreview(File out) throws IOException
    {
        Engine engine = mixedEngine();
        ledgerShot(out, "led-d-correction-preview.png", engine,
            engine.session().transactions().get(0).id(), Correction.TRANSFER);
    }

    private static void ledgerCompacted(File out) throws IOException
    {
        Engine engine = engine();
        long now = SafeMath.now() - 48 * 60 * 60_000L;
        engine.startCustom("Old grind", now);
        for (int i = 0; i < 8; i++)
        {
            engine.session().addTransaction(booked(now + i, "Old bone " + i, 1, 1, 100, 100), 2_000, false);
        }
        engine.session().compactBefore(now + 7L, transaction -> false);
        ledgerShot(out, "led-e-ledger-compacted.png", engine, null, null);
    }


    // ---- R3.1B rate / pace / nav-focus fixtures ----------------------------------------------

    /** A. Fresh Live Grind: zero Net and an unavailable rate must never read as 0/h. */
    private static void r31FreshLive(File out) throws IOException
    {
        Engine engine = engine();
        long now = SafeMath.now();
        engine.startCustom("2323", now - 20_000L);
        engine.session().addTransaction(booked(now - 15_000L, "Willow logs", 1519, 5, 22, 110), 2_000, false);
        engine.session().addTransaction(consumed(now - 10_000L, "Prayer potion(4)", 2434, -1, 210, -210L), 2_000, false);
        liveShot(out, "r31-a-fresh-live.png", engine, PvpState.NONE);
    }

    /** B. Current Grind with no targets: no Pace question, so no Pace row and no dead space. */
    private static void r31GrindsNoTargets(File out) throws IOException
    {
        Engine engine = engine();
        long now = SafeMath.now();
        engine.startCustom("Vorkath", now - 20_000L);
        engine.session().addTransaction(booked(now - 15_000L, "Dragon bones", 536, 1, 3_930, 3_930), 2_000, false);
        SidebarPanel panel = panel(engine, PvpState.NONE);
        panel.shell.show(Shell.GRINDS);
        paint(panel, new File(out, "r31-b-grinds-no-targets.png"));
    }

    /** C. Target set but the rate is immature: the honest Calculating state, not a projection. */
    private static void r31GrindsTargetCalculating(File out) throws IOException
    {
        Engine engine = engine();
        long now = SafeMath.now();
        engine.startCustom("Vorkath", now - 20_000L);
        engine.session().addTransaction(booked(now - 15_000L, "Dragon bones", 536, 1, 3_930, 3_930), 2_000, false);
        engine.session().setNetTarget(5_000_000L, 0, 0);
        SidebarPanel panel = panel(engine, PvpState.NONE);
        panel.shell.show(Shell.GRINDS);
        paint(panel, new File(out, "r31-c-grinds-target-calculating.png"));
        panel.shell.show(Shell.LIVE);
        paint(panel, new File(out, "r31-d-live-net-running.png"));
    }

    // ---- R4 Insights / recap fixtures ---------------------------------------------------------

    /** E. Completed Grind detail: recap, targets, highlights, previous comparison, PB. */
    private static void r4GrindDetailComplete(File out) throws IOException
    {
        Engine engine = insightsFixture();
        SidebarPanel panel = panel(engine, PvpState.NONE);
        panel.shell.show(Shell.GRINDS);
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
        panel.shell.show(Shell.GRINDS);
        SidebarPanelProbe.openGrindsDetail(panel, latestSessionNamed(engine, "Muspah"));
        paint(panel, new File(out, "r4-f-grind-detail-no-previous.png"));
    }

    /** G. Compacted Grind: aggregate recap survives; the exact highlight fails closed. */
    private static void r4GrindDetailCompacted(File out) throws IOException
    {
        Engine engine = engine();
        long now = SafeMath.now();
        finishedLinked(engine, "Vorkath", now - 4 * 3_600_000L, 3_600_000L, 400_000L, 3 * 3_600_000L);
        engine.history().get(0).compactBefore(now - 2 * 3_600_000L, transaction -> false);
        SidebarPanel panel = panel(engine, PvpState.NONE);
        panel.shell.show(Shell.GRINDS);
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
        for (SavedState.SavedGrind saved : engine.plans(true))
        {
            if (name.equals(saved.name()))
            {
                definition = saved;
            }
        }
        if (definition == null)
        {
            definition = engine.saveGrind(name, null, null, false);
        }
        engine.startGrind(name, definition.id(), null, null, startedAt);
        engine.session().addTransaction(booked(startedAt + 1_000L, "Dragon bones", 536, 1,
            (int) gain, gain), 2_000, false);
        Transaction supply = consumed(startedAt + 2_000L, "Prayer potion(4)", 2434, -1,
            (int) Math.max(1L, cost / 3L), -cost);
        supply.setAction(ActionKind.DRINK);
        engine.session().addTransaction(supply, 2_000, false);
        engine.finishCustom(startedAt + activeMillis);
    }

    @Nullable
    private static String latestSessionNamed(Engine engine, String name)
    {
        String found = null;
        long at = Long.MIN_VALUE;
        for (Session session : engine.history())
        {
            if (session != null && name.equals(session.name()) && session.endedAt > at)
            {
                at = session.endedAt;
                found = session.id();
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
        engine.session().addTransaction(booked(now + 1_000L, "Cooked chicken", 2140, 1, 62, 62L), 2_000, false);
        Transaction drink = consumed(now + 2_000L, "Super attack potion(4)", 3, -1, 2_400, -2_400L);
        drink.setAction(ActionKind.DRINK);
        engine.session().addTransaction(drink, 2_000, false);
        engine.session().addTransaction(new Transaction(now + 3_000L, TransactionType.GAIN,
            Context.GENERIC, "", "Wilderness Agility", true,
            Collections.singletonList(new Flow(1,
                "Extremely long ancient item name for width", 1, 99_900_000, 99_900_000L)),
            Confidence.LIKELY, "Preview sample.", null), 2_000, false);
        SidebarPanel panel = panel(engine, PvpState.NONE);
        panel.shell.show(Shell.LEDGER);
        paint(panel, new File(out, "r41-f-ledger-long-row.png"));
    }

    private static Engine openOnlyFixture()
    {
        Engine engine = engine();
        long now = SafeMath.now();
        engine.startCustom("Vorkath", now - 3_600_000L);
        engine.session().addTransaction(booked(now - 3_500_000L, "Dragon bones", 536, 1, 3_930, 3_930L),
            2_000, false);
        return engine;
    }

    // ---- R4.1A fitting fixtures ----------------------------------------------------------------

    /** A. Ledger with a real dummy icon, long item/source and a large positive value. */
    private static void r41aLedgerIconPositive(File out) throws IOException
    {
        SidebarPanel panel = panel(ledgerIconEngine(99_900_000L), PvpState.NONE);
        SidebarPanelProbe.bindSprites(panel, SidebarPreview::previewSprite);
        panel.shell.show(Shell.LEDGER);
        paint(panel, new File(out, "r41a-a-ledger-icon-positive.png"));
    }

    /** B. Ledger with a real dummy icon, long item/source and a large negative value. */
    private static void r41aLedgerIconNegative(File out) throws IOException
    {
        SidebarPanel panel = panel(ledgerIconEngine(-99_900_000L), PvpState.NONE);
        SidebarPanelProbe.bindSprites(panel, SidebarPreview::previewSprite);
        panel.shell.show(Shell.LEDGER);
        paint(panel, new File(out, "r41a-b-ledger-icon-negative.png"));
    }

    private static Engine ledgerIconEngine(long longValue)
    {
        Engine engine = engine();
        long now = SafeMath.now() - 5 * 60_000L;
        engine.startCustom("Vorkath", now);
        engine.session().addTransaction(consumed(now + 1_000L, "Cooked chicken", 2140, 1, 62, -62L),
            2_000, false);
        engine.session().addTransaction(new Transaction(now + 2_000L,
            longValue >= 0L ? TransactionType.GAIN : TransactionType.CONSUMPTION, Context.GENERIC, "",
            "Extremely Long Wilderness Activity Name", true,
            Collections.singletonList(new Flow(1, "Extremely long ancient item name for width", 1,
                (int) Math.abs(longValue), longValue)),
            Confidence.LIKELY, "Preview sample.", null), 2_000, false);
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
        for (SavedState.SavedGrind saved : engine.plans(true))
        {
            if (name.equals(saved.name()))
            {
                definition = saved;
            }
        }
        if (definition == null)
        {
            definition = engine.saveGrind(name, null, null, false);
        }
        engine.startGrind(name, definition.id(), null, null, start);
        engine.session().addTransaction(booked(start + 1_000L, "Dragon bones", 536, 1,
            (int) net, net), 2_000, false);
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
        engine.session().addTransaction(booked(now + 1_000L, "Willow logs", 1519, 5, 22, 110), 2_000, false);
        engine.session().addTransaction(consumed(now + 2_000L, "Prayer potion(4)", 2434, -2, 9_800, -19_600L), 2_000, false);
        engine.session().addTransaction(booked(now + 3_000L, "Battlestaff", 1397, 5, 8_782, 43_910), 2_000, false);
        engine.session().addTransaction(pkDeath(now + 4_000L), 2_000, false);
        return engine;
    }


    // ---- Grinds ------------------------------------------------------------------------------

    private static void grindsCurrent(File out) throws IOException
    {
        Engine engine = grindsFixture();
        SidebarPanel panel = panel(engine, PvpState.NONE);
        panel.shell.show(Shell.GRINDS);
        paint(panel, new File(out, "g-a-grinds-current.png"));
    }

    private static void grindsMy(File out) throws IOException
    {
        Engine engine = grindsFixture();
        SidebarPanel panel = panel(engine, PvpState.NONE);
        panel.shell.show(Shell.GRINDS);
        panel.plans.select(engine.plans(false).get(0).grindId);
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
        panel.shell.show(Shell.GRINDS);
        paint(panel, new File(out, "g-c-recent-grinds.png"));
    }

    private static void finishedGrind(Engine engine, String name, long startedAt, long net)
    {
        engine.startCustom(name, startedAt);
        engine.session().addTransaction(booked(startedAt + 1_000L, "Willow logs", 1519, 5, 42,
            Math.abs(net) / 5), 2_000, false);
        engine.session().addTransaction(consumed(startedAt + 2_000L, "Prayer potion(4)", 2434, -5, 100,
            net < 0 ? net * 4 / 5 : -Math.abs(net) / 5), 2_000, false);
        engine.finishCustom(startedAt + 3_000L);
    }

    private static void grindsDetail(File out) throws IOException
    {
        Engine engine = grindsFixture();
        String historyId = engine.history().isEmpty() ? null : engine.history().get(0).id();
        SidebarPanel panel = panel(engine, PvpState.NONE);
        panel.shell.show(Shell.GRINDS);
        if (historyId != null)
        {
            SidebarPanelProbe.openGrindsDetail(panel, historyId);
        }
        paint(panel, new File(out, "g-d-grind-detail.png"));
    }

    /** The one Start sheet: what Free play is doing now first, then a new name with targets. */
    private static void grindsStartWithChanges(File out) throws IOException
    {
        for (String doing : new String[] {"Vorkath", "Muspah"})
        {
            Engine engine = grindsFixture();
            engine.finishCustom(SafeMath.now());
            engine.setDetectedActivity(doing, SafeMath.now());
            SidebarPanel panel = panel(engine, PvpState.NONE);
            panel.shell.show(Shell.GRINDS);
            new GrindsController(panel).newGrind();
            paint(panel, new File(out, "g-e-start-grind-" + doing.toLowerCase() + ".png"));
        }
    }

    private static void grindsDataMenu(File out) throws IOException
    {
        Engine engine = grindsFixture();
        SidebarPanel panel = panel(engine, PvpState.NONE);
        panel.shell.show(Shell.GRINDS);
        javax.swing.JPopupMenu menu = SidebarPanelProbe.dataMenu(panel);
        SidebarPanelProbe.disposeFrame(panel);
        menu.setSize(Math.max(200, menu.getPreferredSize().width), Math.max(40, menu.getPreferredSize().height));
        // An unshown popup has no layout yet: place its items before painting.
        menu.doLayout();
        paintComponent(menu, new File(out, "g-f-data-recovery-menu.png"));
    }

    /** The New session and Reset free-play sheets at sidebar width. */
    private static void sheets(File out) throws IOException
    {
        Engine free = engine();
        free.ensureSession(SafeMath.now() - 60_000L);
        SidebarPanel fresh = panel(free, PvpState.NONE);
        new GrindsController(fresh).newSession();
        paint(fresh, new File(out, "g-h-restart-rate.png"));
        SidebarPanel over = panel(grindsFixture(), PvpState.NONE);
        new GrindsController(over).startFreePlayOver();
        paint(over, new File(out, "g-h2-free-play-over.png"));
        // Owner 1.5.2: FREE PLAY lists the current run and a past one to restore.
        Engine freePlay = grindsFixture();
        freePlay.finishCustom(SafeMath.now() - 60_000L);
        freePlay.startFreePlayOver(SafeMath.now() - 30_000L);
        SidebarPanel freePlayPanel = panel(freePlay, PvpState.NONE);
        freePlayPanel.shell.show(Shell.GRINDS);
        paint(freePlayPanel, new File(out, "g-l2-grinds-free-play.png"));
        Engine pastFree = engine();
        long pastStart = SafeMath.now() - 3_600_000L;
        pastFree.ensureSession(pastStart);
        Session pastRun = pastFree.freePlay();
        pastRun.addTransaction(booked(pastStart + 1_000L, "Zulrah's scales", 12934, 4_000, 210, 840_000L), 2_000, false);
        pastFree.startFreePlayOver(SafeMath.now() - 60_000L);
        SidebarPanel pastPanel = panel(pastFree, PvpState.NONE);
        pastPanel.shell.show(Shell.GRINDS);
        new GrindsController(pastPanel).openDetail(pastRun.id());
        paint(pastPanel, new File(out, "g-l3-free-play-detail.png"));
        SidebarPanel reset = panel(grindsFixture(), PvpState.NONE);
        new GrindsController(reset).factoryReset();
        paint(reset, new File(out, "g-i-factory-reset.png"));
        SidebarPanel targets = panel(grindsFixture(), PvpState.NONE);
        new GrindsController(targets).openTargets();
        paint(targets, new File(out, "g-j-targets.png"));
        // Owner 1.5.2: Free play's Targets add the milestone; a target starts Live fresh.
        Engine play = engine();
        long start = SafeMath.now() - 2 * 3_600_000L;
        play.ensureSession(start);
        play.session().addTransaction(booked(start + 1_000L, "Zulrah's scales", 12934, 2_000_000, 210, 420_000_000L), 2_000, false);
        play.setMilestone(1_000_000_000L);
        SidebarPanel playTargets = panel(play, PvpState.NONE);
        new GrindsController(playTargets).openTargets();
        paint(playTargets, new File(out, "g-l-targets-free-play.png"));
        play.setTargets(5_000_000L, null, SafeMath.now() - 20L * 60_000L);
        play.session().addTransaction(booked(SafeMath.now() - 10L * 60_000L, "Zulrah's scales", 12934, 4_000, 210, 840_000L), 2_000, false);
        liveShot(out, "f3-live-target-fresh-milestone.png", play, PvpState.NONE);
        play.session().addTransaction(booked(SafeMath.now() - 5L * 60_000L, "Zulrah's scales", 12934, 3_000_000, 210, 630_000_000L), 2_000, false);
        liveShot(out, "f4-live-milestone-reached.png", play, PvpState.NONE);
        // Risk under the Net and the milestone bar keep a long name's room in the header.
        play.setTargets(null, null, SafeMath.now());
        liveShot(out, "f5-live-risk-milestone.png", play, new PvpState(true, true, false, 12_400_000L));
        play.newLive(SafeMath.now() - 2L * 60_000L);
        play.session().addTransaction(booked(SafeMath.now() - 60_000L, "Zulrah's scales", 12934, 1_000, 210, 210_000L), 2_000, false);
        liveShot(out, "f7-live-after-reset-net.png", play, new PvpState(true, true, false, 12_400_000L));
        Engine wild = engine();
        wild.startCustom("Wilderness Bandit camp", SafeMath.now() - 30L * 60_000L);
        wild.session().addTransaction(booked(SafeMath.now() - 20L * 60_000L, "Zulrah's scales", 12934, 4_000, 210, 840_000L), 2_000, false);
        liveShot(out, "f6-live-long-name-risk.png", wild, new PvpState(true, true, false, 12_400_000L));
        wild.setTargets(5_000_000L, 3L * 3_600_000L, SafeMath.now() - 10L * 60_000L);
        wild.session().addTransaction(booked(SafeMath.now() - 5L * 60_000L, "Zulrah's scales", 12934, 2_000, 210, 420_000L), 2_000, false);
        liveShot(out, "f8-live-long-name-both-targets.png", wild, new PvpState(true, true, false, 12_400_000L));
        Engine saved = grindsFixture();
        SidebarPanel edit = panel(saved, PvpState.NONE);
        new GrindsController(edit).editSavedGrind(saved.plans(false).get(0).grindId);
        paint(edit, new File(out, "g-k-edit-grind.png"));
    }

    private static Engine grindsFixture()
    {
        Engine engine = engine();
        long now = SafeMath.now() - 2 * 3_600_000L;
        engine.startCustom("Vorkath", now);
        engine.session().addTransaction(booked(now + 1_000L, "Dragon bones", 536, 2, 3_930, 7_860), 2_000, false);
        engine.session().addTransaction(booked(now + 2_000L, "Battlestaff", 1397, 5, 8_782, 43_910), 2_000, false);
        engine.session().addTransaction(consumed(now + 3_000L, "Prayer potion(4)", 2434, -2, 9_800, -19_600L), 2_000, false);
        engine.session().setNetTarget(5_000_000L, 0, 0);
        engine.session().setTimeTarget(3L * 3_600_000L, SafeMath.now());
        engine.session().setGrindId(engine.saveGrind("Vorkath", 5_000_000L, 3L * 3_600_000L, true).id());
        engine.finishCustom(now + 3_600_000L);

        engine.startCustom("Zulrah", SafeMath.now() - 60_000L);
        engine.session().addTransaction(booked(SafeMath.now() - 50_000L, "Magic logs", 1513, 5, 699, 3_495), 2_000, false);
        engine.session().setGrindId(engine.saveGrind("Zulrah", 2_000_000L, null, false).id());
        engine.session().setNetTarget(5_000_000L, 0, 0);
        engine.session().setTimeTarget(3L * 3_600_000L, SafeMath.now());
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
        panel.shell.show(Shell.LIVE);
        SidebarPanelProbe.refresh(panel);
        paint(panel, new File(out, name));
    }

    private static void ledgerShot(File out, String name, Engine engine, @Nullable String transactionId,
        @Nullable Correction correction) throws IOException
    {
        SidebarPanel panel = panel(engine, PvpState.NONE);
        panel.shell.show(Shell.LEDGER);
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
        SidebarPanel panel = new SidebarPanel(engine, config(), null, null, null);
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
        // A second pass after layout, as the client's next tick does: fitted text sees its width.
        SidebarPanelProbe.refresh(panel);
        panel.validate();
        DirectHeaderControlsTest.resize(panel.ledger.scope);
        DirectHeaderControlsTest.resize(panel.plans.caption);
        ShellProbe.scrollCurrentToTop(panel.shell);
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
            sips.setAction(ActionKind.DRINK);
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
