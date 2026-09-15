package com.gpmanager.ui.bento;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import javax.annotation.Nullable;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * Live · General (SIDEBAR_BENTO.md §3). Owns no engine reference: {@link #apply} takes a
 * {@link LiveSnapshot}; actions are delegated through {@link Actions}.
 */
public final class LivePage implements BentoShell.Page
{
    /** What the page can ask the plugin to do. */
    public interface Actions
    {
        void togglePause();

        void openSessionMenu(JComponent anchor);

        void openGoalEditor();

        void openLedger();

        void openReview();
    }

    private final Actions actions;
    private final Function<Integer, BufferedImage> sprites;

    private final JPanel bar = new JPanel();
    private final Controls.Button pause = new Controls.Button("❚❚ Pause", Controls.Button.Kind.STOP);
    private final Controls.Button session = new Controls.Button("Session ▾", Controls.Button.Kind.DEFAULT);
    private final javax.swing.JLabel todayLabel = Tile.label("", BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().secondary), BentoTheme.MUTED);

    private final JPanel body = BentoShell.stack();
    private final JPanel context = new JPanel();
    private final Controls.Segmented modeSwitch = new Controls.Segmented(Arrays.asList("General", "PvP"));
    private final javax.swing.JLabel activityLabel = Tile.label("", BentoTheme.bodyBold(), BentoTheme.TEXT);
    private final javax.swing.JLabel riskLabel = Tile.label("", BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().secondary), BentoTheme.PVP);
    private final javax.swing.JLabel skullIcon = new javax.swing.JLabel();
    private final javax.swing.JLabel protectIcon = new javax.swing.JLabel();
    private final HeroCard hero = new HeroCard();
    private final StatGrid grid = new StatGrid();
    private final StatGrid gridB = new StatGrid();
    private final StatGrid gridC = new StatGrid();
    private final JPanel encounterHost = new JPanel();
    private final JPanel partyHost = new JPanel();
    private final Ribbon ribbon = new Ribbon();
    private final GoalLine goal = new GoalLine();
    private final JPanel notices = new JPanel();
    private final JPanel recentHost = new JPanel();
    private final JPanel emptyHost = new JPanel();
    /** The optional tiles (goal · party · notices · recent) in the owner's order. */
    private final JPanel optional = new JPanel();
    private List<String> tileOrder = Arrays.asList(ToolsSnapshot.LIVE_TILES);

    private String activity = "";
    private boolean pvpLayout;
    private boolean userPickedMode;
    private PvpMode pvpMode = PvpMode.AUTO;
    private PvpState pvp = PvpState.NONE;
    private boolean netGraph;
    private java.util.Set<String> hiddenTiles = java.util.Collections.emptySet();
    private java.util.function.IntFunction<BufferedImage> gameSprites = id -> null;
    @Nullable
    private LiveSnapshot last;

    public LivePage(Actions actions, Function<Integer, BufferedImage> sprites)
    {
        this.actions = actions;
        this.sprites = sprites == null ? id -> null : sprites;

        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setOpaque(false);
        pause.onClick(actions::togglePause);
        session.onClick(() -> actions.openSessionMenu(session));
        bar.add(pause);
        bar.add(Box.createHorizontalStrut(5));
        bar.add(session);
        bar.add(Box.createHorizontalGlue());
        todayLabel.setToolTipText("Overall today: free play plus every session started today");
        bar.add(todayLabel);
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, bar.getPreferredSize().height));

        context.setLayout(new BoxLayout(context, BoxLayout.X_AXIS));
        context.setOpaque(false);
        modeSwitch.onSelect(i ->
        {
            userPickedMode = true;
            pvpLayout = i == 1;
            modeSwitch.selectedFill(pvpLayout ? BentoTheme.PVP_SURFACE : null);
            if (last != null)
            {
                apply(last);
            }
        });
        context.add(modeSwitch);
        context.add(Box.createHorizontalGlue());
        skullIcon.setToolTipText("Skulled");
        protectIcon.setToolTipText("Protect Item is on");
        skullIcon.setVisible(false);
        protectIcon.setVisible(false);
        context.add(skullIcon);
        context.add(Box.createHorizontalStrut(4));
        context.add(protectIcon);
        context.add(Box.createHorizontalStrut(4));
        context.add(activityLabel);
        riskLabel.setToolTipText("What you would lose right now");
        context.add(riskLabel);
        context.setMaximumSize(new Dimension(Integer.MAX_VALUE, context.getPreferredSize().height));
        BentoShell.stackAdd(body, context);

        BentoShell.stackAdd(body, hero);
        BentoShell.stackAdd(body, grid);
        BentoShell.stackAdd(body, gridB);
        BentoShell.stackAdd(body, gridC);

        BentoShell.stackAdd(body, ribbon);

        goal.onClick(actions::openGoalEditor);
        partyHost.setLayout(new BoxLayout(partyHost, BoxLayout.Y_AXIS));
        partyHost.setOpaque(false);
        partyHost.setVisible(false);
        notices.setLayout(new BoxLayout(notices, BoxLayout.Y_AXIS));
        notices.setOpaque(false);
        encounterHost.setLayout(new BoxLayout(encounterHost, BoxLayout.Y_AXIS));
        encounterHost.setOpaque(false);
        encounterHost.setVisible(false);
        recentHost.setLayout(new BoxLayout(recentHost, BoxLayout.Y_AXIS));
        recentHost.setOpaque(false);
        optional.setLayout(new GapStack(BentoTheme.density().gap));
        optional.setOpaque(false);
        BentoShell.stackAdd(body, optional);

        emptyHost.setLayout(new BoxLayout(emptyHost, BoxLayout.Y_AXIS));
        emptyHost.setOpaque(false);
        BentoShell.stackAdd(body, emptyHost);
        rebuildOptional();
    }

    /** Tools › Layout: the order of the optional tiles. */
    public void setTileOrder(List<String> order)
    {
        tileOrder = order == null || order.isEmpty() ? Arrays.asList(ToolsSnapshot.LIVE_TILES) : new ArrayList<>(order);
        rebuildOptional();
    }

    /** Re-adds the visible optional hosts in order with the standard gap; hidden ones leave no hole. */
    private void rebuildOptional()
    {
        optional.removeAll();
        boolean any = false;
        for (String id : tileOrder)
        {
            List<JComponent> hosts = new ArrayList<>();
            switch (id)
            {
                case "goal":
                    hosts.add(goal);
                    break;
                case "party":
                    hosts.add(partyHost);
                    break;
                case "notices":
                    hosts.add(notices);
                    hosts.add(encounterHost);
                    break;
                case "recent":
                    hosts.add(recentHost);
                    break;
                default:
                    break;
            }
            for (JComponent host : hosts)
            {
                if (!host.isVisible() || (host == goal && !goal.isPresent()))
                {
                    continue;
                }
                host.setAlignmentX(JComponent.LEFT_ALIGNMENT);
                optional.add(host);
                any = true;
            }
        }
        optional.setVisible(any);
        optional.revalidate();
        optional.repaint();
    }

    @Override
    public String id()
    {
        return BentoShell.LIVE;
    }

    @Override
    public JComponent pageBar()
    {
        return bar;
    }

    @Override
    public JComponent body()
    {
        return body;
    }

    /** Activity name shown on the context line (from the interaction context / metrics hint). */
    public void setActivity(String value)
    {
        activity = value == null ? "" : value;
        // The owner already reads "General"; only a real activity earns the label.
        activityLabel.setText("General".equalsIgnoreCase(activity) ? "" : activity);
    }

    /** Under the hero: Supplies when any, and Overall today so the whole day is always in view. */
    private static String overallLine(LiveSnapshot s)
    {
        StringBuilder sb = new StringBuilder();
        if (s.supplies > 0L)
        {
            sb.append("Supplies ").append(Fmt.compact(s.supplies));
        }
        if (s.hasSession && s.sessionsToday > 0)
        {
            sb.append(sb.length() == 0 ? "" : " · ").append("Overall today ").append(Fmt.signed(s.overallToday));
        }
        return sb.toString();
    }

    /** PvP layout policy and the client's danger facts; sprites come from the game via the plugin. */
    public void setPvp(PvpMode mode, PvpState state, java.util.function.IntFunction<BufferedImage> gameSpriteById)
    {
        pvpMode = mode == null ? PvpMode.AUTO : mode;
        pvp = state == null ? PvpState.NONE : state;
        gameSprites = gameSpriteById == null ? id -> null : gameSpriteById;
    }

    public void setNetGraph(boolean enabled)
    {
        netGraph = enabled;
    }

    /** Tools › Layout: which optional Live tiles the owner hid. */
    public void setHiddenTiles(java.util.Set<String> ids)
    {
        hiddenTiles = ids == null ? java.util.Collections.emptySet() : ids;
    }

    public boolean pvpLayout()
    {
        return pvpLayout;
    }

    /** Test/preview seam. */
    public void showPvpForPreview(boolean on)
    {
        modeSwitch.select(on ? 1 : 0, true);
    }

    /** Party is information only on Live (SIDEBAR_BENTO.md §8); controls live in Tools. */
    public void applyParty(@Nullable com.gpmanager.model.PartyProfitSummary party, long now)
    {
        partyHost.removeAll();
        boolean show = party != null && party.isInParty() && party.getMemberCount() > 0 && !hiddenTiles.contains("party");
        partyHost.setVisible(show);
        if (!show)
        {
            rebuildOptional();
            return;
        }
        Tile tile = new Tile();
        String right = party.getReportingCount() + "/" + party.getMemberCount() + " sharing";
        tile.header("Party · " + Fmt.signed(party.getNet()), right, BentoTheme.DIM);
        for (com.gpmanager.model.PartyProfitSummary.Member m : party.getMembers())
        {
            String name = m.getDisplayName() + (m.isLocal() ? " (you)" : "");
            String value;
            java.awt.Color color;
            if (!m.isReporting())
            {
                value = "not sharing";
                color = BentoTheme.DIM;
            }
            else
            {
                value = Fmt.signed(m.getNet()) + (m.hasReportedRate() ? " · " + Fmt.rate(m.getProfitPerHour()) + "/h" : "");
                color = m.isFreshReport() ? BentoTheme.signColor(m.getNet()) : BentoTheme.DIM;
                if (!m.isFreshReport())
                {
                    value += " · stale";
                }
            }
            tile.kv(name, value, color);
        }
        tile.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        partyHost.add(tile);
        partyHost.revalidate();
        partyHost.repaint();
        rebuildOptional();
    }

    public void apply(LiveSnapshot s)
    {
        last = s;
        // PvP layout: the owner's pick wins for this visit; otherwise the policy decides.
        boolean autoPvp = pvpMode == PvpMode.ALWAYS
            || (pvpMode == PvpMode.AUTO && (pvp.pvpPossible || s.pvpSession));
        if (!userPickedMode && autoPvp != pvpLayout)
        {
            pvpLayout = autoPvp;
            modeSwitch.select(pvpLayout ? 1 : 0, false);
            modeSwitch.selectedFill(pvpLayout ? BentoTheme.PVP_SURFACE : null);
        }
        BufferedImage skull = pvp.skulled ? gameSprites.apply(pvp.highRisk ? 1046 : 439) : null;
        skullIcon.setIcon(skull == null ? null : new javax.swing.ImageIcon(skull));
        skullIcon.setText(skull == null && pvp.skulled ? "☠" : "");
        skullIcon.setForeground(BentoTheme.PVP);
        skullIcon.setVisible(pvp.skulled);
        BufferedImage prot = pvp.protectItem ? gameSprites.apply(123) : null;
        protectIcon.setIcon(prot == null ? null : new javax.swing.ImageIcon(prot));
        protectIcon.setText(prot == null && pvp.protectItem ? "⛨" : "");
        protectIcon.setForeground(BentoTheme.INFO);
        protectIcon.setVisible(pvp.protectItem);
        // A PKer's first question in the Wilderness: what am I risking?
        riskLabel.setText(pvp.pvpPossible && pvp.riskKnown ? "  risk " + Fmt.compact(pvp.riskValue) : "");

        // Page bar.
        if (!s.hasSession)
        {
            pause.setText("▶ Start");
            pause.setKind(Controls.Button.Kind.RESUME);
            pause.setToolTipText("Start tracking now - or just play, it starts on your first change");
        }
        else if (s.stopped)
        {
            pause.setText("▶ Resume");
            pause.setKind(Controls.Button.Kind.RESUME);
            pause.setToolTipText("Tracking is stopped until you press Resume");
        }
        else if (s.paused)
        {
            pause.setText("▶ Resume");
            pause.setKind(Controls.Button.Kind.RESUME);
            pause.setToolTipText("Resume with a fresh inventory baseline");
        }
        else
        {
            pause.setText("❚❚ Pause");
            pause.setKind(Controls.Button.Kind.STOP);
            pause.setToolTipText("Pause immediately; stays paused until you resume");
        }
        session.setText(s.freePlay ? "Start ▾" : "Session ▾");
        // Overall today at the bar's right edge: the one day figure Live keeps in view.
        boolean showToday = s.hasSession && (s.sessionsToday > 0 || s.overallToday != s.net);
        todayLabel.setText(showToday ? "Today " + Fmt.signed(s.overallToday) : "");
        todayLabel.setForeground(BentoTheme.signColor(s.overallToday));
        session.setToolTipText(s.freePlay ? "Start a session, undo, or restore" : "End or rename this session, goal, undo");

        // Hero card: title · owner, the clock as the status, sign-coloured net with the change chip.
        String owner = s.freePlay ? SessionsSnapshot.FREE_PLAY : s.ownerLabel + (s.auto ? " ⚙" : "");
        String status;
        java.awt.Color dot;
        if (s.accountHold)
        {
            status = "Switching profile";
            dot = BentoTheme.WARN;
        }
        else if (!s.hasSession || s.stopped)
        {
            status = "Waiting";
            dot = BentoTheme.DIM;
        }
        else if (s.paused && !s.idle)
        {
            status = "Paused " + Fmt.clock(s.elapsedMillis);
            dot = BentoTheme.WARN;
        }
        else if (s.idle)
        {
            status = "Idle " + Fmt.clock(s.elapsedMillis);
            dot = BentoTheme.DIM;
        }
        else
        {
            status = "Live " + Fmt.clock(s.elapsedMillis);
            dot = BentoTheme.POSITIVE;
        }
        String overall = overallLine(s);
        // The context row (General | PvP) only earns its space when PvP is in play.
        context.setVisible(pvp.pvpPossible || s.pvpSession || userPickedMode || pvpMode == PvpMode.ALWAYS);
        if (pvpLayout)
        {
            String delta = null;
            boolean up = true;
            if (s.pvpPreviousNet != null && s.pvpPreviousNet != 0L)
            {
                double d = (s.net - s.pvpPreviousNet) / (double) Math.abs(s.pvpPreviousNet);
                delta = (d >= 0 ? "▲ +" : "▼ ") + Math.round(d * 100) + "%";
                up = d >= 0;
            }
            List<HeroCard.Line> lines = new ArrayList<>();
            hero.title(owner, pvp.skulled ? "PvP · skulled" : "PvP").caption("Total")
                .captionNote(s.sinceBank == null ? null : Fmt.signed(s.sinceBank) + " since bank")
                .status(status, dot, true)
                .hero(Fmt.signed(s.net), BentoTheme.signColor(s.net))
                .chip(delta, up ? BentoTheme.POSITIVE : BentoTheme.NEGATIVE)
                .lines(lines)
                .sparkline(s.netSeries, netGraph && s.netSeries.length > 1, BentoTheme.signColor(s.net));
            hero.setToolTipText("Loot value minus what you lost · " + Fmt.exactSigned(s.net) + " gp · " + s.kills + " kills · " + s.deaths + " deaths"
                + " · best kill " + Fmt.exact(s.bestKill) + (delta == null ? "" : " · " + delta + " vs your previous PvP session")
                + (pvp.pvpPossible && pvp.riskKnown ? " · risking " + Fmt.compact(pvp.riskValue) : "") + (overall.isEmpty() ? "" : " · " + overall));
            grid.cells(Collections.emptyList(), 4);
            String lootDelta = null;
            String lossDelta = null;
            boolean lootUp = true;
            boolean lossUp = true;
            if (s.pvpAverageKillNet != null && s.pvpAverageKillNet != 0L)
            {
                double d = (s.killNet - s.pvpAverageKillNet) / (double) Math.abs(s.pvpAverageKillNet);
                lootDelta = (d >= 0 ? "▲ +" : "▼ ") + Math.round(d * 100) + "%";
                lootUp = d >= 0;
            }
            if (s.pvpAverageDeathLoss != null && s.pvpAverageDeathLoss != 0L)
            {
                double d = (s.deathLoss - s.pvpAverageDeathLoss) / (double) Math.abs(s.pvpAverageDeathLoss);
                lossDelta = (d >= 0 ? "▲ +" : "▼ ") + Math.round(d * 100) + "%";
                lossUp = d < 0;
            }
            // Live › PvP: the money in the net card — Gains · Loss · Per kill; combat statistics live on Insights › PvP.
            hero.strip(s.hasSession ? Arrays.asList(
                new StatGrid.Cell(Icon.glyph("◆", BentoTheme.WARN), "Gains", Fmt.compact(s.killNet), BentoTheme.TEXT, lootDelta, lootUp, "Loot value · vs your PvP session average"),
                new StatGrid.Cell(Icon.glyph("◇", BentoTheme.WARN), "Loss", s.deathLoss > 0L ? "−" + Fmt.compact(s.deathLoss) : "0",
                    s.deathLoss > 0L ? BentoTheme.NEGATIVE : BentoTheme.TEXT, lossDelta, lossUp, "Lost on death · vs your PvP session average"),
                new StatGrid.Cell(Icon.glyph("⊕", BentoTheme.POSITIVE), "Per kill", s.kills == 0 ? "—" : Fmt.compact(s.killNet / s.kills), BentoTheme.TEXT,
                    null, true, "Average loot per kill" + (s.deaths == 0 ? "" : " · average loss per death " + Fmt.compact(s.deathLoss / s.deaths))))
                : Collections.emptyList());
            gridB.setVisible(false);
            gridC.setVisible(false);
        }
        else
        {
            hero.title(owner, null).caption("Total")
                .captionNote(s.sinceBank == null ? null : Fmt.signed(s.sinceBank) + " since bank")
                .status(status, dot, true)
                .net(s.net)
                .lines(Collections.emptyList())
                .sparkline(s.netSeries, netGraph && s.netSeries.length > 1, BentoTheme.signColor(s.net));
            hero.setToolTipText(Fmt.exactSigned(s.net) + " gp · gains " + Fmt.exact(s.gains) + " · supplies " + Fmt.exact(s.supplies)
                + " · loss " + Fmt.exact(s.loss) + (s.rateEstablished ? " · " + Fmt.exact(s.gpPerHour) + "/h" : "")
                + (overall.isEmpty() ? "" : " · " + overall));
            String rateDelta = null;
            boolean rateUp = true;
            if (s.rateVsAverage != null)
            {
                rateDelta = (s.rateVsAverage >= 0 ? "▲ +" : "▼ ") + Math.round(s.rateVsAverage * 100) + "%";
                rateUp = s.rateVsAverage >= 0;
            }
            // Gains · Supplies · Loss · GP/h live inside the net card: the whole equation, one card.
            hero.strip(s.hasSession ? Arrays.asList(
                new StatGrid.Cell(Icon.glyph("◉", BentoTheme.WARN), "Gains", Fmt.compact(s.gains), BentoTheme.TEXT),
                new StatGrid.Cell(Icon.glyph("▣", BentoTheme.WARN), "Supplies", s.supplies > 0L ? "−" + Fmt.compact(s.supplies) : "0",
                    s.supplies > 0L ? BentoTheme.WARN : BentoTheme.TEXT, null, true, "Consumables: food, potions, runes, ammo, charges"),
                new StatGrid.Cell(Icon.glyph("◐", BentoTheme.WARN), "Loss", s.loss > 0L ? "−" + Fmt.compact(s.loss) : "0",
                    s.loss > 0L ? BentoTheme.NEGATIVE : BentoTheme.TEXT, null, true, "Deaths, tax, fees, drops"),
                new StatGrid.Cell(Icon.glyph("◷", BentoTheme.INFO), "GP/h", s.rateEstablished ? Fmt.rate(s.gpPerHour) : "—",
                    s.rateEstablished ? BentoTheme.TEXT : BentoTheme.DIM, rateDelta, rateUp,
                    rateDelta == null ? "Rate settles after a few minutes" : "vs your average for this session name")) : Collections.emptyList());
            grid.cells(Collections.emptyList(), 3);
            gridB.setVisible(false);
            gridC.setVisible(false);
        }
        grid.setVisible(false);

        // Ribbon: one bar for the session with its marks (bank, death, key, task, raid).
        boolean showRibbon = s.hasSession && s.elapsedMillis > 60_000L && !s.ribbonMarks.isEmpty();
        ribbon.setVisible(showRibbon);
        ribbon.segments(Collections.singletonList(new Ribbon.Segment("session", 0d, 1d, true,
            owner + " · " + Fmt.duration(s.elapsedMillis))), s.ribbonMarks);

        // Goal.
        goal.setVisible(!hiddenTiles.contains("goal"));
        if (s.goal != null)
        {
            goal.set(s.goal.label, s.goal.fraction, -1d, s.goal.progress, s.goal.eta);
            goal.setToolTipText(LiveSnapshot.Goal.kindLabel(s.goal.kind) + " goal · " + s.goal.label + " · " + s.goal.progress
                + (s.goal.available ? "" : " · " + s.goal.unavailableReason) + " · click to change");
        }
        else
        {
            goal.clear();
        }

        // Notices (spec priority order), as navigable cards.
        notices.removeAll();
        List<NavRow> list = new ArrayList<>();
        if (s.accountHold)
        {
            list.add(new NavRow().icon(Icon.glyph("◔", BentoTheme.WARN)).tone(NavRow.Tone.WARN).chevron(false)
                .text("Switching profile", "tracking resumes when the account is ready"));
        }
        if (s.neutralZone)
        {
            list.add(new NavRow().icon(Icon.glyph("◈", BentoTheme.accentColor())).tone(NavRow.Tone.ACCENT).chevron(false)
                .text("Neutral zone", "gear and inventory held neutral · nothing books"));
        }
        if (pvp.pvpPossible && !pvpLayout && pvp.riskKnown)
        {
            list.add(new NavRow().icon(Icon.glyph("⚔", BentoTheme.PVP)).tone(NavRow.Tone.PVP).chevron(false)
                .text("In danger", "risking " + Fmt.compact(pvp.riskValue) + (pvp.skulled ? " · skulled" : "")));
        }
        if (s.reclaimPending)
        {
            String detail = (s.reclaimOutstanding > 0L ? s.reclaimOutstanding + (s.reclaimOutstanding == 1L ? " item" : " items") + " · " : "")
                + (s.reclaimArmed ? "collecting" : "fee books when you collect")
                + (s.reclaimAgeMillis > 0L ? " · " + Fmt.age(s.reclaimAgeMillis) : "");
            list.add(new NavRow().icon(Icon.glyph("☠", BentoTheme.WARN)).tone(NavRow.Tone.WARN).chevron(false)
                .text("Reclaim pending", detail));
        }
        if (s.keysHeld > 0)
        {
            list.add(new NavRow().icon(Icon.glyph("⚿", BentoTheme.WARN)).tone(NavRow.Tone.PLAIN).chevron(true)
                .text("Key held", s.keysHeld == 1 ? "claim on open" : s.keysHeld + " keys · claim on open").onClick(actions::openLedger));
        }
        if (s.notableDrop != null)
        {
            LiveSnapshot.NotableDrop d = s.notableDrop;
            list.add(new NavRow().icon(Icon.sprite(sprites.apply(d.itemId), "◆", BentoTheme.accentColor())).tone(NavRow.Tone.ACCENT)
                .text("Notable drop received!", d.name + " (" + Fmt.signed(d.value) + ")")
                .onClick(actions::openLedger));
        }
        for (int i = 0; i < list.size(); i++)
        {
            if (i > 0)
            {
                notices.add(Box.createVerticalStrut(4));
            }
            NavRow n = list.get(i);
            n.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            notices.add(n);
        }
        notices.setVisible(!list.isEmpty() && !hiddenTiles.contains("notices"));

        // Encounter row: what you are fighting and what it pays.
        encounterHost.removeAll();
        if (s.encounter != null && !pvpLayout)
        {
            LiveSnapshot.Encounter e = s.encounter;
            NavRow row = new NavRow().icon(ActivityIcons.icon(e.name, null, sprites))
                .text(e.name + " · " + e.count + " · " + Fmt.compact(e.perKill) + "/kill", null)
                .right(e.streak > 1 ? "×" + e.streak + " streak" : null, BentoTheme.POSITIVE)
                .onClick(actions::openLedger);
            row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            encounterHost.add(row);
            encounterHost.setVisible(true);
        }
        else
        {
            encounterHost.setVisible(false);
        }

        // Recent.
        recentHost.removeAll();
        emptyHost.removeAll();
        if (!s.hasSession)
        {
            Tile empty = new Tile();
            empty.padding(18, 12, 18, 12);
            javax.swing.JLabel big = Tile.label("◌", BentoTheme.font(java.awt.Font.PLAIN, 26f), BentoTheme.DIM);
            big.setAlignmentX(JComponent.CENTER_ALIGNMENT);
            javax.swing.JLabel title = Tile.label("Waiting for your first change", BentoTheme.bodyBold(), BentoTheme.MUTED);
            title.setAlignmentX(JComponent.CENTER_ALIGNMENT);
            empty.add(big);
            empty.add(Box.createVerticalStrut(6));
            empty.add(title);
            empty.add(Box.createVerticalStrut(4));
            for (String line : Tile.wrap("Pick something up, drink, or bank and it starts by itself - or press Start above.",
                empty.getFontMetrics(BentoTheme.secondary()), Tile.interiorWidth() - 8))
            {
                javax.swing.JLabel hint = Tile.label(line, BentoTheme.secondary(), BentoTheme.DIM);
                hint.setAlignmentX(JComponent.CENTER_ALIGNMENT);
                empty.add(hint);
            }
            empty.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            emptyHost.add(empty);
            emptyHost.setVisible(true);
            recentHost.setVisible(false);
        }
        else
        {
            emptyHost.setVisible(false);
            if (pvpLayout && !s.pvpEvents.isEmpty())
            {
                Tile events = new Tile().section(null, "Recent PvP Events (" + s.pvpEvents.size() + ")", "All activity", actions::openLedger);
                events.padding(4, 4, 4, 4);
                for (LiveSnapshot.PvpEvent ev : s.pvpEvents)
                {
                    String sub = (ev.location.isEmpty() ? "" : ev.location + " · ") + Fmt.age(System.currentTimeMillis() - ev.at) + " ago";
                    NavRow row = new NavRow().icon(Icon.glyph(ev.kill ? "☠" : "⚔", ev.kill ? BentoTheme.POSITIVE : BentoTheme.NEGATIVE))
                        .text((ev.kill ? "PKed " : "Died to ") + ev.opponent, sub)
                        .right(Fmt.signed(ev.value), BentoTheme.signColor(ev.value))
                        .onClick(actions::openLedger);
                    row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
                    events.add(row);
                    events.gap(3);
                }
                events.setAlignmentX(JComponent.LEFT_ALIGNMENT);
                recentHost.add(events);
                recentHost.add(Box.createVerticalStrut(BentoTheme.density().gap));
            }
            ListBlock recent = new ListBlock().header(null, "Recent (" + s.recent.size() + ")", "All drops →", actions::openLedger);
            if (s.recent.isEmpty())
            {
                recent.empty("No receipts yet this session");
            }
            for (LiveSnapshot.Recent r : s.recent)
            {
                ItemRow row = new ItemRow()
                    .sprite(r.itemId > 0 ? sprites.apply(r.itemId) : null)
                    .name(r.name, r.qty)
                    .onClick(actions::openLedger);
                if (r.neutral)
                {
                    row.neutral();
                }
                else
                {
                    row.value(r.value);
                }
                if (r.tag != null)
                {
                    row.tag(r.tag, "review".equals(r.tag) ? BentoTheme.WARN : BentoTheme.QUIET);
                }
                recent.row(row);
            }
            recent.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            recentHost.add(recent);
            recentHost.setVisible(!hiddenTiles.contains("recent"));
        }
        rebuildOptional();
        body.revalidate();
        body.repaint();
    }

    @Nullable
    public String activity()
    {
        return activity;
    }
}
