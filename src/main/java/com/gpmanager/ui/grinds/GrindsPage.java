package com.gpmanager;


import net.runelite.client.util.LinkBrowser;
import java.awt.datatransfer.StringSelection;

import static com.gpmanager.Fmt.*;
import static com.gpmanager.GameData.msg;
import static com.gpmanager.GameData.pattern;
import static com.gpmanager.Kit.*;
import static com.gpmanager.Kit.Tone.*;
import static com.gpmanager.Phrases.*;
import static com.gpmanager.SafeMath.*;
import static java.lang.Math.*;

import com.gpmanager.GrindsData.*;
import com.gpmanager.Hero.Cell;
import com.gpmanager.LedgerData.Entry;
import com.gpmanager.SavedState.SavedGrind;
import com.gpmanager.Table.*;
import com.gpmanager.Table.Menu;
import java.awt.*;
import java.time.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import lombok.*;
import static java.util.Collections.singletonList;
import static java.util.Locale.ROOT;

/**
 * Grinds = management (SIDEBAR_SPEC.md §3): the Running line linking to Live, the My Grinds table
 * with the selected row's panel, and Recent grinds opening Grind detail (hero, highlights, vs
 * previous, personal bests). The page renders a {@link GrindsData} and holds no engine.
 */
class GrindsPage implements Shell.Page {
    interface Actions {
        /** Shows Live. */
        void back();

        void openLedger(String id);

        void endGrind();

        void startGrind(String id, String nameOverride);

        void openTargets();

        /** Opens the Start Grind sheet. */
        void newGrind();

        void editSavedGrind(String id);

        void favoriteSavedGrind(String id, boolean favorite);

        void archiveSavedGrind(String id, boolean archived);

        void deleteSavedGrind(String id);

        void startAgain(String id);

        void openDetail(String id);

        void deleteHistorical(String id);

        default void copySummary(String id) {}

        /** A past Free play run becomes Free play again (owner 1.5.2). */
        void restoreFreePlay(String id);

        void showArchived(boolean show);

        void refresh();
    }

    Actions actions;
    JPanel body = stack(PANEL, GAP), bar = new JPanel(new BorderLayout(4, 0)), list = stack(PANEL, GAP);
    JPanel detail = stack(PANEL, GAP), running = new JPanel(new BorderLayout(GAP, 0));
    JLabel runningName = label("", bold(), TEXT), runningNet = label("", numbers(11.5f), TEXT);
    Table mine = new Table("MY GRINDS");
    JPanel selectedPanel = stack(CARD, 0);
    Table recent = new Table("RECENT GRINDS"), savedSessions = new Table("SAVED SESSIONS");

    /** The current Free play and past ones Reset free-play kept (owner 1.5.2). */
    Table freePlay = new Table("FREE PLAY");

    /** All time as one compact strip; hovering it gives the full breakdown. */
    JPanel allTime = stack(CARD, 0);

    Hero hero = new Hero();
    GrindsData data;
    String selectedId;
    JCheckBox archived = new JCheckBox("Archived");

    GrindsPage(Actions actions) {
        this.actions = actions;
        JButton start = button("Start Grind", true, actions::newGrind);
        focus(archived);
        archived.setOpaque(false);
        archived.setFont(small());
        archived.setForeground(LABEL);
        archived.setToolTipText("Include archived Grinds");
        archived.addActionListener(e -> actions.showArchived(archived.isSelected()));
        // Owner 2026-10-01: the same toolbar band as the Ledger; the controls live in the header.
        headerBand(bar, start, archived);
        running.setBackground(CARD);
        pad(running, 2, 6, 2, 2);
        Hero.clickable(runningName, "Open Live", actions::back);
        onResize(runningName, this::runningTitle);
        running.add(runningName, BorderLayout.NORTH);
        var facts = transparent(new BorderLayout(GAP, 0));
        facts.add(runningNet, BorderLayout.CENTER);
        JButton end = button("End", false, actions::endGrind);
        end.setForeground(LOSS.color);
        end.setToolTipText(msg("end-grind"));
        facts.add(end, BorderLayout.EAST);
        running.add(facts, BorderLayout.CENTER);
        mine.setEmptyText(msg("saved-grinds"));
        recent.setEmptyText(msg("finished-grinds")).setFill(true);
        pad(selectedPanel, 0, 0, 4, 0);
        // Owner 2026-09-28: My Grinds and Recent grinds first; All time closes the page.
        savedSessions.setEmptyText("Save a Live session to keep it here.");
        for (var part : new JComponent[] {running, mine, selectedPanel, recent, savedSessions, freePlay, allTime})
            list.add(part);
        pad(body, 0, GAP, GAP, 0);
        body.add(list);
        body.add(detail);
    }

    public String id() {
        return Shell.GRINDS;
    }

    public JComponent pageBar() {
        return bar;
    }

    public JComponent body() {
        return body;
    }

    void apply(GrindsData next) {
        data = next;
        list.setVisible(next.detail == null);
        detail.setVisible(next.detail != null);
        if (next.detail != null) {
            buildDetail(next);
        } else {
            applyRunning(next);
            applyAllTime(next.allTime);
            applyMine(next);
            applyRecent(next);
        }
        body.revalidate();
        body.repaint();
    }

    // ---- list ---------------------------------------------------------------------------------
    void applyRunning(GrindsData data) {
        running.setVisible(data.hasActive);
        if (!data.hasActive) return;
        runningTitle();
        runningName.setToolTipText("Open Live · " + data.activeName);
        runningNet.setText(signed(data.activeNet));
        runningNet.setForeground(sign(data.activeNet).color);
        running.setToolTipText(rateLine(data.activeRateEstablished, data.activeGpPerHour) + " · "
                        + duration(data.activeMillis) + " active · "
                        + targetsLine(data.activeNetTarget, data.activeTimeTarget));
        runningNet.setToolTipText(exactSigned(data.activeNet) + " gp");
    }

    void runningTitle() {
        if (data != null && data.hasActive) runningName.setText(fitShrinking(runningName,
                        "● " + data.activeName, bold(), runningName.getWidth() > 0 ? runningName.getWidth() : 201));
    }

    /** Every retained session, Free play included; bests are finished Grinds only. */
    void applyAllTime(GrindsData.AllTime overall) {
        var tip = new StringBuilder("<html>All time · ").append(overall.grinds)
                        .append(overall.grinds == 1 ? " Grind" : " Grinds").append("<br>Net ")
                        .append(exactSigned(overall.net)).append(" gp<br>Active ")
                        .append(duration(overall.activeMillis));
        if (overall.splitAvailable) {
            tip.append("<br>Supplies ").append(signed(-overall.supplies)).append(" · Losses ")
                    .append(signed(-overall.losses));
        }
        if (overall.bestRateName != null) {
            tip.append("<br>Best GP/h ").append(overall.bestRateName).append(" · ").append(rate(overall.bestRate));
        }
        String best = overall.bestNetName == null ? ""
                        : "Best · " + overall.bestNetName + " " + signed(overall.bestNet);
        allTime.removeAll();
        var title = label("ALL TIME · " + overall.grinds, small(), LABEL);
        pad(title, 3, 6, 3, 6);
        allTime.add(title);
        allTime.add(Hero.strip(List.of(new Cell("NET", signed(overall.net), sign(overall.net),
                                        exactSigned(overall.net) + " gp", null), new Cell("GP/H",
                                        overall.gpPerHour == null ? "—" : rate(overall.gpPerHour), PLAIN, null, null),
                                new Cell("ACTIVE", durationShort(overall.activeMillis), PLAIN,
                                        duration(overall.activeMillis), null))));
        if (!best.isEmpty()) {
            var bestLine = label(best, small(), sign(overall.bestNet).color);
            pad(bestLine, 3, 6, 4, 6);
            allTime.add(bestLine);
        }
        allTime.setToolTipText(tip.append("</html>").toString());
        for (var child : allTime.getComponents()) {
            if (child instanceof JComponent && ((JComponent) child).getToolTipText() == null) {
                ((JComponent) child).setToolTipText(allTime.getToolTipText());
            }
        }
        allTime.revalidate();
    }

    void applyMine(GrindsData data) {
        var rows = new ArrayList<Row>();
        MyGrind selected = null;
        for (var plan : data.myGrinds) {
            var def = plan.definition;
            String id = def.getGrindId();
            boolean isSelected = id.equals(selectedId);
            if (isSelected) selected = plan;
            String runs = plan.linkedCount + (plan.linkedCount == 1 ? " run" : " runs");
            // Owner 1.5.0: the average rate sits in the wide middle column, the last run's Net on
            // the right (a rate with "/h" overflowed the value column).
            rows.add(new Row(id, null, (def.favorite ? "★ " : "") + def.getName(), plan.linkedCount == 0 ? "NEW"
                                    : plan.hasRate ? rate(plan.rate) + "/h" : runs,
                            plan.hasLast ? signed(plan.lastNet) : "—", plan.hasLast ? sign(plan.lastNet) : DIM,
                            def.getName() + " · " + runs + " · " + targetsLine(
                                            def.getNetTargetGp(), def.getActiveTimeTargetMillis()),
                            plan.hasLast ? "Last run " + exactSigned(plan.lastNet) + " gp" : "", isSelected,
                            () -> select(isSelected ? null : id), grindMenu(plan)));
        }
        mine.countedTitle("MY GRINDS", rows);
        if (selected == null) selectedId = null;
        applySelected(selected);
    }

    void select(String id) {
        selectedId = id;
        if (data != null) {
            applyMine(data);
            body.revalidate();
        }
    }

    void applySelected(MyGrind plan) {
        selectedPanel.removeAll();
        selectedPanel.setVisible(plan != null);
        if (plan == null) return;
        var def = plan.definition;
        var facts = new KeyValue(null);
        // The row already shows the last run's Net and the average GP/h (owner 1.5.2: no repeats).
        facts.put("Last run", plan.hasLast ? when(plan.lastStartedAt, now()) : "—", plan.hasLast ? PLAIN : DIM);
        facts.put("Best", plan.hasBest ? signed(plan.bestNet) : "—", plan.hasBest ? sign(plan.bestNet) : DIM);
        facts.put("Target", targetsLine(def.getNetTargetGp(), def.getActiveTimeTargetMillis()), PLAIN);
        selectedPanel.add(facts);
        var buttons = transparent(new FlowLayout(FlowLayout.LEFT, 4, 2));
        // Owner 2026-10-01 (F14): the selected saved Grind starts right here, beside its menu.
        JButton start = button("Start", true, () -> actions.startGrind(def.getGrindId(), null));
        start.setToolTipText("Start " + def.getName());
        buttons.add(start);
        buttons.add(button("Edit", false, () -> actions.editSavedGrind(def.getGrindId())));
        JButton more = button("···", false, () -> {});
        more.setToolTipText("More for this Grind");
        more.addActionListener(e -> popup(grindMenu(plan)).show(more, 0, more.getHeight()));
        buttons.add(more);
        selectedPanel.add(buttons);
    }

    List<Menu> grindMenu(MyGrind plan) {
        var def = plan.definition;
        String id = def.getGrindId();
        return Arrays.asList(new Menu("Start", () -> actions.startGrind(id, null)),
                new Menu("Edit…", () -> actions.editSavedGrind(id)), new Menu(
                        def.favorite ? "Unfavourite" : "★ Favourite",
                        () -> actions.favoriteSavedGrind(id, !def.favorite)), new Menu(
                        def.archived ? "Unarchive" : "Archive", () -> actions.archiveSavedGrind(id, !def.archived)),
                new Menu("Delete…", () -> actions.deleteSavedGrind(id)));
    }

    void applyRecent(GrindsData data) {
        long now = now();
        var rows = new ArrayList<Row>();
        var free = new ArrayList<Row>();
        var saved = new ArrayList<Row>();
        for (var run : data.recent) {
            String id = run.sessionId;
            // Free play runs list apart: the current one opens Live; a past one can be restored.
            boolean current = run.freePlay && id.equals(data.activeSessionId);
            var zone = data.profileZone == null ? ZoneId.systemDefault() : data.profileZone;
            String when = dayLabel(Instant.ofEpochMilli(run.startedAt).atZone(zone).toLocalDate(), now, zone);
            (id.startsWith("saved:") ? saved : run.freePlay ? free : rows).add(new Row(id, null, run.name,
                                    current ? nonBlank(data.milestone, "Now") : when, valueText(run.net), sign(run.net),
                                    run.name + " · " + contextLine(run, now, data.profileZone),
                                    exactSigned(run.net) + " gp", false,
                                    current ? actions::back : () -> actions.openDetail(id), current ? Arrays.asList(
                                                    new Menu("Open in Ledger", () -> actions.openLedger(id)),
                                                    new Menu("Copy summary", () -> actions.copySummary(id)))
                                            : Arrays.asList(new Menu("Open in Ledger", () -> actions.openLedger(id)),
                                                    new Menu("Copy summary", () -> actions.copySummary(id)),
                                                    run.freePlay
                                                            ? new Menu("Restore\u2026", () -> actions.restoreFreePlay(id))
                                                            : new Menu("Start again", () -> actions.startAgain(id)),
                                                    new Menu("Delete\u2026", () -> actions.deleteHistorical(id)))));
        }
        recent.countedTitle("RECENT GRINDS", rows);
        savedSessions.countedTitle("SAVED SESSIONS", saved);
        freePlay.countedTitle("FREE PLAY", free);
        freePlay.setVisible(!free.isEmpty());
    }

    // ---- detail -------------------------------------------------------------------------------
    void buildDetail(GrindsData data) {
        var detail = data.detail;
        this.detail.removeAll();
        JButton back = button("‹ Grinds", false, () -> actions.openDetail(null));
        var top = transparent(new BorderLayout());
        top.add(back, BorderLayout.WEST);
        top.add(label(when(detail.startedAt, now()).toUpperCase(ROOT), small(), LABEL),
                BorderLayout.EAST);
        pad(top, 0, 0, 0, 6);
        this.detail.add(top);
        hero.status(detail.closed ? OFF : GAIN.color, detail.closed ? "Finished" : "Running", detail.name,
                detail.closed ? "COMPLETE" : "", null);
        hero.big(signed(detail.net), sign(detail.net), exactSigned(detail.net) + " gp",
                detail.rateEstablished ? rate(detail.gpPerHour) + "/h" : "—", detail.rateEstablished,
                duration(detail.activeMillis) + " ACTIVE");
        // Owner 2026-10-01 (F12): a completed run's rate is the whole-run basis.
        hero.rate.setToolTipText(detail.rateEstablished ? msg("gp-h") : null);
        var strip = Hero.money(detail.revenue, detail.supplies, detail.losses, detail.revenue - detail.net,
                        detail.splitAvailable, null);
        hero.strips(singletonList(strip));
        this.detail.add(hero);
        // Only targets the run had: an empty "No Net target" line said nothing (owner 1.5.2).
        var result = new KeyValue("HIGHLIGHTS");
        boolean targeted = detail.netTargetGp != null || detail.activeTimeTargetMillis != null;
        if (detail.netTargetGp != null) result.put("Target", detail.netOutcome(), PLAIN);
        if (detail.activeTimeTargetMillis != null) result.put("Time", detail.timeOutcome(), PLAIN);
        if (data.activePace != null && data.activePace.present && data.activePace.available
                && detail.sessionId.equals(data.activeSessionId)) {
            // A time target's countdown is already the Time row (owner 1.5.2: no repeat).
            boolean timed = detail.activeTimeTargetMillis != null;
            if (!timed) result.put("Pace", data.activePace.line, DIM);
            // Owner 2026-10-01 (F10): a combined target also shows its required average.
            if (data.activePace.second != null) result.put(timed ? "Pace" : "", data.activePace.second, DIM);
        }
        if (targeted) this.detail.add(result);
        if (detail.compacted) this.detail.add(note(msg("summary-detailed"), LABEL));
        var buttons = transparent(new FlowLayout(FlowLayout.LEFT, 4, 2));
        // A Free play run is restored, never started again as a Grind (owner 1.5.2).
        boolean free = data.recent.stream().anyMatch(run -> run.freePlay && run.sessionId.equals(detail.sessionId));
        if (!free) buttons.add(button("Start again", true, () -> actions.startAgain(detail.sessionId)));
        else if (detail.closed) buttons.add(button("Restore", true, () -> actions.restoreFreePlay(detail.sessionId)));
        buttons.add(button("Open in Ledger", false, () -> actions.openLedger(detail.sessionId)));
        if (detail.closed) {
            var menu = List.of(new Menu("Delete…", () -> actions.deleteHistorical(detail.sessionId)));
            JButton more = button("···", false, () -> {});
            more.addActionListener(e -> popup(menu).show(more, 0, more.getHeight()));
            buttons.add(more);
        }
        this.detail.add(buttons);
        this.detail.add(note(msg("open-ledger"), LABEL));
    }

    /** A row's right-click menu, shown as a ⋯ popup. */
    static JPopupMenu popup(List<Menu> items) {
        return menuOf(items);
    }

    // ---- text ---------------------------------------------------------------------------------
    /** Date and duration context: Today/Yesterday/date plus Active Time, never internal ids. */
    static String contextLine(Recent recent, long now, ZoneId zone) {
        var z = zone == null ? ZoneId.systemDefault() : zone;
        return dayLabel(Instant.ofEpochMilli(recent.startedAt).atZone(z).toLocalDate(), now, z) + " · "
                + durationShort(recent.activeMillis);
    }

    /** Understandable signed money: explicit "0 GP" for zero, never a bare 0. */
    static String valueText(long net) {
        return net == 0 ? "0 GP" : signed(net);
    }

    static String rateLine(boolean established, long gpPerHour) {
        return established ? rate(gpPerHour) + "/h" : "—  GP/h";
    }

    static String targetsLine(Long profitTarget, Long activeTarget) {
        var parts = new ArrayList<String>();
        if (profitTarget != null) parts.add(targetNet(profitTarget) + " Net");
        if (activeTarget != null) parts.add(targetTime(activeTarget));
        return parts.isEmpty() ? "No targets" : String.join(" · ", parts);
    }
}

/** Grinds actions: start, save, edit, targets, history, export, backups and recovery. */
class GrindsController implements GrindsPage.Actions {
    SidebarPanel panel;
    Engine engine;
    Shell shell;
    SessionRepository repository;
    Persistence persistence;

    /** One bond's live price for the target form's preset; 0 hides the button (owner 1.5.0). */
    static java.util.function.LongSupplier bondPrice = () -> 0;

    GrindsController(SidebarPanel panel) {
        this.panel = panel;
        engine = panel.engine;
        shell = panel.shell();
        repository = panel.repository;
        persistence = panel.persistence;
    }

    void mutate(Runnable domain) {
        panel.mutate(domain);
    }

    void openEntry(Entry entry) {
        panel.openEntry(entry);
    }

    public void back() {
        panel.grindsDetailId = null;
        shell.show(Shell.LIVE);
        refresh();
    }

    public void openLedger(String id) {
        var active = engine.getActiveSession();
        if (active != null && active.getId().equals(id)) {
            openEntry(Entry.current());
        } else {
            var history = id == null ? null : engine.historySession(id);
            openEntry(new Entry(LedgerData.Scope.HISTORY, id, history == null ? null : history.getName()));
        }
    }

    public void endGrind() {
        var ending = engine.getActiveSession();
        if (ending == null || !engine.isCustomActive()) return;
        String endedId = ending.getId(), endedName = ending.getName();
        Object owner = panel.ownerToken;
        mutate(() -> {
                    if (engine.getActiveSession() != ending || !engine.finishCustom(now())) return;
                    var ended = engine.historyMetrics(endedId, now());
                    Runnable recap = () -> {
                        if (panel.suspended || panel.ownerToken != owner) return;
                        shell.notify(endedName + " ended", ended == null ? "" : signed(ended.net), false, "View recap",
                                () -> {
                                    panel.grindsDetailId = endedId;
                                    shell.show(Shell.GRINDS);
                                    refresh();
                                });
                    };
                    if (SwingUtilities.isEventDispatchThread()) recap.run();
                    else SwingUtilities.invokeLater(recap);
                });
    }

    /** The Live clock offers restart, new-session and original-time controls. */
    public void newSession() {
        var content = stack(CARD, 4);
        var help = note("Restart keeps Net.", LABEL, 110);
        help.setToolTipText("<html>" + msg("new-session").replace(". ", ".<br>") + "</html>");
        content.add(help);
        var keep = new JCheckBox("Keep previous clock", true);
        keep.setOpaque(false);
        keep.setFont(small());
        keep.setForeground(TEXT);
        focus(keep);
        keep.setToolTipText("Freeze this clock for Restore time; leave unchecked to keep any older backup.");
        content.add(keep);
        var pair = transparent(new GridLayout(1, 2, 4, 0));
        var compact = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE),
                BorderFactory.createEmptyBorder(3, 4, 3, 4));
        JButton fresh = button("New session", false, () -> {
            shell.closeSheet();
            mutate(() -> engine.newLiveSession(now()));
        });
        fresh.setBorder(compact);
        fresh.setToolTipText(msg("session-new-tip"));
        pair.add(fresh);
        JButton restore = button("Restore time", false, () -> {
            shell.closeSheet();
            mutate(() -> engine.restoreRate(now()));
        });
        restore.setBorder(compact);
        restore.setToolTipText(msg("session-restore-tip"));
        pair.add(restore);
        content.add(pair);
        shell.sheet("Session controls", null, content,
                new Shell.Choice("Restart GP/h", true, () -> mutate(() -> engine.restartRate(now(), keep.isSelected()))));
    }

    void saveSessionPrompt() {
        var source = engine.getActiveSession();
        if (source == null) return;
        Long start = source.sessionStartAt;
        shell.sheet("Save session?", msg("save-session"), null,
                new Shell.Choice("Save session", true, () -> mutate(() -> {
                            if (engine.saveLiveSession(source, start, now()) != null) {
                                engine.showWholeRun();
                                shell.tell("Session saved", "Grinds › Saved sessions", false);
                            }
                        })),
                new Shell.Choice("Continue without saving", false, () -> mutate(() -> {
                            if (engine.getActiveSession() == source && Objects.equals(start, source.sessionStartAt))
                                engine.showWholeRun();
                        })));
    }

    /** Reset free-play from the gear menu: the run so far moves to Grinds history (owner 1.5.2). */
    void startFreePlayOver() {
        shell.confirm("Reset free-play?", msg("free-play-over"), "Reset", () -> {
                    mutate(() -> engine.startFreePlayOver(now()));
                    shell.show(Shell.LIVE);
                    shell.tell("Free play reset", "The old run is in Grinds.", false);
                });
    }

    /** A short getting-started guide; its words live in text.tsv (owner 2026-10-16). */
    void guide() {
        var content = stack(CARD, 4);
        for (String part : msg("guide").split(" \\| ")) {
            var line = note(part, TEXT, SHEET_TEXT);
            line.setBorder(null);
            content.add(line);
        }
        shell.sheet("Getting started", null, content);
    }

    public void startGrind(String id, String nameOverride) {
        var definition = id == null ? null : engine.savedGrind(id);
        String name = !blank(nameOverride) ? nameOverride.trim()
                        : definition == null ? "" : definition.getName();
        Long net = definition == null ? null : definition.getNetTargetGp();
        Long time = definition == null ? null : definition.getActiveTimeTargetMillis();
        if (definition != null) id = definition.getGrindId();
        if (id != null && engine.isCustomActive() && engine.getActiveSession() != null
                && id.equals(engine.getActiveSession().getGrindId())) {
            shell.show(Shell.LIVE);
            refresh();
            return;
        }
        startGrind(name, id, net, time);
    }

    /**
     * Start a Grind through the switch guard and show it on Live, saying Free play is kept (owner
     * 1.5.0: a fresh Grind's empty Live read as lost data).
     */
    void startGrind(String name, String id, Long net, Long time) {
        whenSwitchAllowed(name, () -> {
                    mutate(() -> engine.startGrind(name, id, net, time, now()));
                    panel.grindsDetailId = null;
                    shell.show(Shell.LIVE);
                    shell.tell(name + " started", msg("free-play-kept"), false);
                });
    }

    public void openTargets() {
        var active = engine.getActiveSession();
        if (active == null) return;
        var form = grindForm(null, active.getProfitTargetGp(), active.getActiveTimeTargetMillis());
        // Free play's milestone counts all of Free play; a target counts fresh (owner 1.5.2).
        boolean general = !engine.isCustomActive();
        if (general) {
            form.add(label("Milestone · all of Free play", small(), LABEL));
            picks(form, inline(form, "milestoneField", "Net",
                            active.milestoneGp == null ? "" : targetNet(active.milestoneGp)), "100m", "500m", "1b",
                    "2b", "5b", "Clear=");
        }
        targetSheet("Set a Target", form, "Save", (name, net, time) -> {
                    Long target = GrindsData.applyNet(net, active.getProfitTargetGp());
                    Long duration = GrindsData.applyTime(time, active.getActiveTimeTargetMillis());
                    Long milestone = GrindsData.applyNet(field(form, "milestoneField"), active.milestoneGp);
                    mutate(() -> {
                                engine.saveTargets(target, duration, now());
                                if (general) engine.setMilestone(milestone);
                            });
                });
    }

    /**
     * The one Start Grind sheet, from Live and Grinds (owner 1.5.0): your Grinds start in one
     * click, the one you are playing right now first; or a name with its targets, which becomes a
     * Grind. Owner 2026-10-01 (F22): the list is bounded to five rows with paging.
     */
    public void newGrind() {
        var active = engine.getActiveSession();
        String doing = active == null || engine.isCustomActive() ? "" : active.getActivityHint();
        if (ActivityDetector.GENERAL.equalsIgnoreCase(doing)) doing = "";
        var playing = engine.grindNamed(doing);
        var saved = new ArrayList<>(engine.getSavedGrinds(false));
        saved.sort(Comparator.comparing((SavedGrind definition) -> definition != playing)
                        .thenComparing(grind -> !grind.favorite));
        var content = stack(CARD, 2);
        new Paged(saved.size(), (box, page) -> {
                            int from = min(page * CHOICE_ROWS, saved.size());
                            for (var plan : saved.subList(from, min(from + CHOICE_ROWS, saved.size()))) {
                                JButton start = button((plan.favorite ? "\u2605 " : "") + plan.getName()
                                                        + (plan == playing ? " \u00b7 now" : ""), false, () -> {
                                                    shell.closeSheet();
                                                    startGrind(plan.getGrindId(), null);
                                                });
                                start.setHorizontalAlignment(SwingConstants.LEFT);
                                start.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
                                box.add(start);
                            }
                        }).build(content);
        if (!saved.isEmpty()) content.add(label("Or a new one", small(), LABEL));
        // What you are doing now names a new Grind, unless it already is one (listed first).
        var form = grindForm(playing == null ? doing : "", null, null);
        form.add(content, 0);
        targetSheet("Start Grind", form, "Start", (name, net, time) -> {
                            if (name.isEmpty()) return;
                            var known = engine.grindNamed(name);
                            startGrind(known == null ? name : known.getName(),
                                    known == null ? null : known.getGrindId(), GrindsData.applyNet(net, null),
                                    GrindsData.applyTime(time, null));
                        });
    }

    /** Choice rows a bounded sheet list shows at once (owner 2026-10-01, F22). */
    static final int CHOICE_ROWS = 5;

    /**
     * One bounded choice list: at most five rows, with previous/next paging when there are more.
     * Paging refills the list box in place, so the sheet - and any typed name - is never rebuilt.
     */
    static final class Paged {
        interface RowFill {
            void fill(JPanel box, int page);
        }

        JPanel box = stack(CARD, 2);
        int total;
        RowFill fill;
        int page;
        JButton prev = button("\u2039", false, () -> turn(-1)), next = button("\u203a", false, () -> turn(1));
        JLabel range = label("", small(), LABEL);
        JPanel bar = transparent(new FlowLayout(FlowLayout.LEFT, 4, 0));

        Paged(int total, RowFill fill) {
            this.total = total;
            this.fill = fill;
            bar.add(prev);
            bar.add(next);
            bar.add(range);
        }

        /** Adds the list (and its pager) to the sheet body and renders the first page. */
        JPanel build(JPanel content) {
            content.add(box);
            if (total > CHOICE_ROWS) content.add(bar);
            redraw();
            return box;
        }

        void turn(int delta) {
            page = clampPage(page + delta, pages());
            redraw();
        }

        int pages() {
            return pageCount(total, CHOICE_ROWS);
        }

        void redraw() {
            box.removeAll();
            fill.fill(box, page);
            int from = page * CHOICE_ROWS;
            applyPager(range, prev, next, page, pages(), total == 0 ? "" : (from + 1) + "\u2013"
                                    + min(total, from + CHOICE_ROWS) + " of " + total);
            box.revalidate();
        }
    }

    /** Name and targets; a running run of this Grind follows the change (owner 1.5.0). */
    public void editSavedGrind(String id) {
        var definition = engine.savedGrind(id);
        if (definition == null) return;
        targetSheet("Edit " + definition.getName(), grindForm(definition.getName(), definition.getNetTargetGp(),
                        definition.getActiveTimeTargetMillis()), "Save",
                (name, net, time) -> {
                    Long target = GrindsData.applyNet(net, definition.getNetTargetGp());
                    Long duration = GrindsData.applyTime(time, definition.getActiveTimeTargetMillis());
                    mutate(() -> engine.updateSavedGrind(id, name, target, duration, definition.favorite));
                });
    }

    public void favoriteSavedGrind(String id, boolean favorite) {
        var definition = engine.savedGrind(id);
        if (definition == null) return;
        mutate(() -> engine.updateSavedGrind(id, definition.getName(), definition.getNetTargetGp(),
                                definition.getActiveTimeTargetMillis(), favorite));
    }

    public void archiveSavedGrind(String id, boolean archived) {
        if (!archived) {
            mutate(() -> engine.archiveGrind(id, false));
            return;
        }
        shell.confirm("Archive Grind", msg("hides-my"), "Archive",
                () -> mutate(() -> engine.archiveGrind(id, true)));
    }

    public void deleteSavedGrind(String id) {
        var definition = engine.savedGrind(id);
        if (definition == null) return;
        shell.confirm("Delete " + definition.getName(), msg("removes-my"), "Delete",
                () -> mutate(() -> engine.deleteSavedGrind(id)));
    }

    /** Starts the run's Grind with its current setup; an older one-off run becomes a Grind. */
    public void startAgain(String id) {
        var active = engine.getActiveSession();
        Session session = empty(id) || active != null && id.equals(active.getId()) ? active
                        : engine.historySession(id);
        if (session == null) return;
        var plan = engine.savedGrind(session.getGrindId());
        if (plan == null) plan = engine.grindNamed(session.getName());
        if (plan != null) {
            startGrind(plan.getGrindId(), null);
        } else {
            startGrind(session.getName(), null, session.getProfitTargetGp(), session.getActiveTimeTargetMillis());
        }
    }

    public void openDetail(String id) {
        panel.grindsDetailId = id;
        refresh();
    }

    public void restoreFreePlay(String id) {
        shell.confirm("Restore this Free play?", msg("restore-free-play"), "Restore", () -> {
                    mutate(() -> engine.restoreFreePlay(id, now()));
                    panel.grindsDetailId = null;
                    shell.show(Shell.LIVE);
                    shell.tell("Free play restored", "The other run is under Grinds \u203a FREE PLAY.", false);
                });
    }

    public void deleteHistorical(String id) {
        var session = engine.historySession(id);
        if (session == null) return;
        shell.confirm("Delete " + session.getName(), msg("removes-run"), "Delete", () -> {
                    panel.grindsDetailId = null;
                    mutate(() -> engine.deleteHistory(id));
                });
    }

    public void showArchived(boolean show) {
        panel.grindsShowArchived = show;
        refresh();
    }

    /** The gear menu beside the tabs: profile-wide data actions, from any page. */
    JPopupMenu dataMenu() {
        var menu = new JPopupMenu();
        menu.add(item("Copy Live summary", engine.getActiveSession() != null, () -> copySummary(null)));
        menu.add(item("Getting started", this::guide));
        menu.add(item("Back up profile", persistence != null, () -> backUpProfile()));
        menu.add(item(msg("copy-data"), () -> copyDataPath()));
        menu.add(item("Report a bug", () -> LinkBrowser.browse(msg("bug-report"))));
        menu.add(item("Changelog", () -> LinkBrowser.browse(msg("changelog"))));
        menu.addSeparator();
        menu.add(item("Reset free-play\u2026", !engine.isCustomActive(), this::startFreePlayOver));
        menu.add(item(msg("factory-reset"), persistence != null, () -> factoryReset()));
        return menu;
    }

    public void refresh() {
        panel.refresh();
    }

    /** Name (when given) and the two targets, each with its quick picks. */
    static JPanel grindForm(String name, Long net, Long time) {
        var form = stack(CARD, 2);
        if (name != null) inline(form, "nameField", "Name", name);
        long bond = bondPrice.getAsLong();
        picks(form, inline(form, "netField", "Net", net == null ? "" : targetNet(net)), "5m",
                bond > 0 ? "Bond=" + targetNet(bond) : "10m", "20m", "50m", "100m", "1b", "Clear=");
        picks(form, inline(form, "timeField", "Time", time == null ? "" : targetTime(time)), "30m", "1h", "2h", "3h",
                "4h", "6h", "Clear=");
        return form;
    }

    /** A label beside its field (owner 1.5.0: a short, sleek target sheet). */
    static JTextField inline(JPanel form, String key, String label, String value) {
        var field = new JTextField(value);
        var name = label(label, small(), LABEL);
        name.setPreferredSize(new Dimension(34, name.getPreferredSize().height));
        var line = transparent(new BorderLayout());
        line.add(name, BorderLayout.WEST);
        line.add(field);
        form.add(line);
        form.putClientProperty(key, field);
        return field;
    }

    /**
     * One-click values under a target field ("label=value" when they differ; Clear empties it). The
     * pick matching the field is lit, so the sheet shows the current target at a glance.
     */
    static void picks(JPanel form, JTextField field, String... picks) {
        var row = transparent(new GridLayout(1, 0));
        pad(row, 0, 0, 2, 0);
        Map<JButton, String> values = new LinkedHashMap<>();
        Runnable light = () -> values.forEach((chip, value) -> chip.setForeground(
                                                value.equalsIgnoreCase(field.getText().trim()) ? ACCENT : LABEL));
        for (String pick : picks) {
            String[] part = pick.split("=", -1);
            String value = part[part.length - 1];
            JButton chip = button(part[0], false, () -> {
                                field.setText(value);
                                light.run();
                            });
            pad(chip, 1, 0, 1, 0);
            chip.setContentAreaFilled(false);
            values.put(chip, value);
            row.add(chip);
        }
        field.addCaretListener(e -> light.run());
        light.run();
        form.add(row);
    }

    static String field(JPanel form, String key) {
        Object field = form.getClientProperty(key);
        return field instanceof JTextField ? ((JTextField) field).getText().trim() : "";
    }

    interface TargetSave {
        void save(String name, String net, String time);
    }

    /**
     * Malformed targets keep the same draft and saved-picker page open for correction.
     */
    void targetSheet(String title, JPanel form, String action, TargetSave save) {
        shell.sheet(title, null, form, new Shell.Choice(action, true, () -> {
                            String net = field(form, "netField"), time = field(form, "timeField");
                            if (GrindsData.inputsValid(net, time) && GrindsData.parseNet(field(form, "milestoneField"))
                                            != GrindsData.INVALID) {
                                save.save(field(form, "nameField"), net, time);
                            } else {
                                shell.tell("Targets not saved", msg("use-number"), true);
                                targetSheet(title, form, action, save);
                            }
                        }));
    }

    /** One Grind at a time: starting another asks to end the running one first (spec §4). */
    void whenSwitchAllowed(String nextName, Runnable start) {
        if (engine.getActiveSession() == null || !engine.isCustomActive()) {
            start.run();
            return;
        }
        var now = panel.lastSnapshot;
        String running = engine.getActiveSession().getName() + (now == null ? ""
                                : " (" + signed(now.net) + ", " + clock(now.elapsedMillis) + ")");
        // The engine never replaces a running Grind silently: Switch ends it first, then starts.
        shell.confirm("Switch Grind", "End " + running + " and start " + nextName + "?", "Switch", () -> {
                    mutate(() -> engine.finishCustom(now()));
                    start.run();
                });
    }

    /** Copies the selected view's exact figures without writing or mutating its session. */
    public void copySummary(String id) {
        String text;
        long now = now();
        synchronized (engine) {
            if (id == null) {
                var live = LiveSnapshot.capture(engine, now, null);
                if (!live.hasSession) return;
                text = summary(live.freePlay ? "Free play" : live.ownerLabel, live.net, live.gains, live.costs,
                        live.supplies, live.loss, live.costSplitAvailable, live.elapsedMillis);
                if (live.marketResult != 0)
                    text += String.format(ROOT, msg("summary-market"), exactSigned(live.marketResult));
            } else {
                var session = engine.historySession(id);
                if (session == null) return;
                var money = engine.savedSession(id) != null ? session.metrics(now)
                        : engine.summary().run(engine, session, now).metrics;
                text = summary(session.getName(), money.net, money.revenue, money.costs, money.suppliesCosts,
                        money.otherCosts, money.costSplitAvailable, money.elapsedMillis);
            }
        }
        try {
            copyText(text);
            shell.tell("Summary copied", "Ready to paste.", false);
        } catch (RuntimeException failed) {
            shell.failed("Copy failed", failed);
        }
    }

    static String summary(String name, long net, long income, long cost, long supplies, long lost,
            boolean split, long elapsed) {
        return String.format(ROOT, msg("session-summary"), name, exactSigned(net), clock(elapsed),
                RateReadiness.isEstablished(elapsed) ? exact(Session.hourly(net, elapsed)) : "Calculating…",
                exact(income), exact(cost)) + (split ? String.format(ROOT, msg("summary-costs"),
                        exact(supplies), exact(lost)) : "");
    }

    /** Puts text on the local system clipboard. */
    void copyText(String text) {
        var selection = new StringSelection(text);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
    }

    /**
     * Disk work runs on the persistence worker, never Swing's thread, so RuneLite stays responsive;
     * its result comes back on Swing's thread only while the completed account/lifecycle still owns
     * the UI. Reset may advance its own lifecycle generation during the work.
     */
    <T> void off(java.util.concurrent.Callable<T> work, java.util.function.Consumer<T> done, String failed) {
        var worker = persistence.lifecycleExecutor();
        if (worker == null) return;
        long request = persistence.lifecycleRequest.get();
        var owner = persistence.activeIdentity;
        worker.execute(() -> {
                    try {
                        persistence.busy = true;
                        Runnable presentation;
                        long completed;
                        synchronized (persistence) {
                            if (request != persistence.lifecycleRequest.get()
                                            || !Objects.equals(owner, persistence.activeIdentity)) return;
                            try {
                                T result = work.call();
                                presentation = () -> done.accept(result);
                            } catch (Exception ex) {
                                presentation = () -> shell.failed(failed, ex);
                            }
                            // Reset legitimately advances the generation; pin its finished result.
                            completed = persistence.lifecycleRequest.get();
                        }
                        Runnable ready = presentation;
                        SwingUtilities.invokeLater(() -> {
                                    if (!persistence.lifecycleShutdown && completed == persistence.lifecycleRequest.get()
                                                    && Objects.equals(owner, persistence.activeIdentity)) ready.run();
                                });
                    } finally {
                        persistence.busy = false;
                    }
                });
    }

    void backUpProfile() {
        off(() -> persistence.writeBackup(now()), backup -> shell.tell(
                                "Profile backed up", String.valueOf(backup.getFileName()), false), "Backup failed");
    }

    void copyDataPath() {
        if (repository == null) return;
        String path = String.valueOf(repository.dataDir());
        copyText(path);
        shell.tell(msg("data-folder"), path, false);
    }

    /**
     * Delete Profile (owner 1.5.2): Back up & delete writes a validated safety copy first and a
     * failed copy refuses; Delete without backup cannot be undone.
     */
    void factoryReset() {
        shell.sheet("Delete Profile", msg("resets-gp"), null,
                new Shell.Choice("Back up & delete", true, () -> resetNow(true)),
                new Shell.Choice("Delete without backup", false, () -> resetNow(false)));
    }

    /** The direct delete: one confirmation already happened in the sheet. */
    void resetNow(boolean backUp) {
        off(() -> backUp ? persistence.factoryReset(now()) : persistence.factoryReset(now(), false),
                this::reset, "Delete failed");
    }

    void reset(Persistence.ResetOutcome result) {
        // The profile was replaced: HUD+ must not keep the old trip's loot or moments.
        if (result.isApplied()) panel.resetProfile();
        if (result.isApplied()) {
            // The safety copy is one click from its folder (owner 2026-10-16).
            shell.notify("Profile reset", msg("fresh-profile"), false, "Copy folder path", this::copyDataPath);
        } else {
            shell.tell("Reset refused", result.getDetail(), true);
        }
    }
}

/**
 * Grinds read model: the current canonical Grind, the reusable My Grinds definitions with their
 * derived Last/Best subset, the Recent Grinds history and one Grind detail. Targets and Pace are
 * derived from canonical facts; estimates never feed accounting, completion or PBs.
 */
@AllArgsConstructor
class GrindsData {
    /** A target field that did not parse: it never mutates and never means "clear". */
    static final long INVALID = -1;

    /**
     * Friendly Net-target parsing: 5m, 5M, 5000k, 5,000,000. Blank is 0 (no target); malformed is
     * {@link #INVALID}.
     */
    static long parseNet(String raw) {
        if (blank(raw)) return 0;
        // Strict whole-string form: digits with optional decimals and one k/m/b suffix. Anything
        // else is malformed and must never be treated as "clear".
        String text = lower(raw).replace(",", "").replace("_", "").replace(" ", "");
        if (!pattern("target-net").matcher(text).matches()) return INVALID;
        long value = parseGp(text);
        return value > 0 ? value : INVALID;
    }

    /**
     * Friendly Active-Time parsing: 90m, 3h, "3h 30m", plain minutes. Blank is 0 (no target);
     * malformed is {@link #INVALID}.
     */
    static long parseTime(String raw) {
        if (blank(raw)) return 0;
        String text = lower(raw).replace(",", "").replace("_", "").trim();
        try {
            if (pattern("target-minutes").matcher(text).matches()) {
                long minutes = round(Double.parseDouble(text));
                return minutes > 0 ? multiplyExact(minutes, 60_000L) : INVALID;
            }
            // Strict whole-string form: optional "Nh" then optional "Mm" and nothing else.
            var matcher = pattern("target-time").matcher(text);
            if (!matcher.matches() || (matcher.group(1) == null && matcher.group(2) == null)) {
                return INVALID;
            }
            long total = 0;
            if (matcher.group(1) != null)
                total = addExact(total, round(Double.parseDouble(matcher.group(1)) * 3_600_000d));
            if (matcher.group(2) != null)
                total = addExact(total, round(Double.parseDouble(matcher.group(2)) * 60_000d));
            return total > 0 && total < Long.MAX_VALUE ? total : INVALID;
        } catch (ArithmeticException overflow) {
            return INVALID;
        }
    }

    /** Applies a Net input to an existing target: INVALID keeps the existing value untouched. */
    static Long applyNet(String raw, Long existing) {
        return apply(parseNet(raw), existing);
    }

    /** Applies an Active-Time input to an existing target: INVALID keeps the existing value. */
    static Long applyTime(String raw, Long existing) {
        return apply(parseTime(raw), existing);
    }

    static Long apply(long parsed, Long existing) {
        return parsed == INVALID ? existing : parsed == 0 ? null : (Long) parsed;
    }

    /** True when both fields are safe to apply (valid or deliberately blank). */
    static boolean inputsValid(String netRaw, String timeRaw) {
        return parseNet(netRaw) != INVALID && parseTime(timeRaw) != INVALID;
    }

    /** Derived pace over canonical facts; nothing here is booked or persisted. */
    @AllArgsConstructor
    static class Pace {
        /** True when a Pace question exists at all (at least one target). */
        boolean present;

        boolean available;
        String line, second;

        /** No target at all: there is no Pace question to answer, so no row renders. */
        static Pace none() {
            return new Pace(false, false, "", null);
        }
    }

    /**
     * Mode A/B/C pace. A: time until the Net target. B: projected Net at the Active-Time target. C:
     * both plus the required average. Unavailable evidence reads "Calculating…" instead of a
     * fabricated precision; estimates stay visually muted.
     */
    static Pace pace(Long profitTarget, Long targetMillis, long net, boolean rateEstablished, long rate,
            long activeMillis) {
        if (profitTarget == null && targetMillis == null) return Pace.none();
        if (!rateEstablished) return new Pace(true, false, "Calculating\u2026", null);
        if (profitTarget != null && targetMillis == null) {
            if (net >= profitTarget) return new Pace(true, true, "Target reached", null);
            if (rate <= 0) {
                // The rate is established and genuinely non-positive: an ETA would be fabricated.
                return new Pace(true, true, "Not earning at this rate", null);
            }
            long minutes = nonNeg(round(safeSubtract(profitTarget, net) * 60d / rate));
            return new Pace(true, true, "~" + durationMinutes(minutes) + " remaining", null);
        }
        // A time target counts down; a projected Net only repeated GP/h (owner 1.5.2).
        long remaining = nonNeg(targetMillis - activeMillis);
        String left = durationMinutes(remaining / 60_000) + " left";
        if (profitTarget == null) {
            return new Pace(true, true, remaining <= 0 ? "Time target reached" : left, null);
        }
        if (remaining <= 0) {
            return new Pace(true, true, "Time target reached", "Required average was "
                            + compact(requiredAverage(profitTarget, net, activeMillis)) + "/h");
        }
        long required = requiredAverage(profitTarget, net, targetMillis);
        return new Pace(true, true, left, "Required average " + compact(required) + "/h");
    }

    static long requiredAverage(long target, long net, long byMillis) {
        return Session.hourly(nonNeg(safeSubtract(target, net)), byMillis);
    }

    static String durationMinutes(long minutes) {
        return minutes < 90 ? minutes + "m" : (minutes / 60) + "h " + (minutes % 60) + "m";
    }

    /** One reusable definition plus its derived Last/Best subset from linked canonical history. */
    @AllArgsConstructor
    static class MyGrind {
        SavedState.SavedGrind definition;
        int linkedCount;
        boolean hasLast;
        long lastNet, lastStartedAt;
        boolean hasBest;
        long bestNet;

        /** Average GP/h over every finished run (owner 1.5.0: Grinds compare by rate). */
        long rate;

        boolean hasRate;
    }

    /** One recent canonical instance, rendered with Grind language. */
    @AllArgsConstructor
    static class Recent {
        String sessionId, name;
        long startedAt, net, activeMillis;

        /** A Free play run (current or reset), listed under FREE PLAY (owner 1.5.2). */
        boolean freePlay;
    }

    /** One instance's detail: financial summary, targets and target outcome. */
    @AllArgsConstructor
    static class Detail {
        String sessionId, name;
        long startedAt, net;
        /** What the Net aim counted when this ran (the run's Net minus its set baseline). */
        long targetProgress, revenue, supplies, losses;
        boolean splitAvailable;
        long activeMillis;
        /** The Active Time window the run's time target counts from (set moment to now/end). */
        long targetWindowMillis;
        boolean rateEstablished;
        long gpPerHour;
        Long netTargetGp, activeTimeTargetMillis;
        boolean closed, compacted;

        /** Factual target outcome wording; never "Failed". */
        String netOutcome() {
            return GrindHistory.netOutcome(netTargetGp, targetProgress, closed);
        }

        String timeOutcome() {
            return GrindHistory.timeOutcome(activeTimeTargetMillis, targetWindowMillis);
        }
    }

    boolean hasActive;
    String activeName, activeSessionId;
    long activeNet, activeGpPerHour;
    boolean activeRateEstablished;
    long activeMillis;
    Long activeNetTarget, activeTimeTarget;
    Pace activePace;
    List<MyGrind> myGrinds;
    List<Recent> recent;

    /** Free play's milestone progress, "1B \u00b7 42%"; empty with none (owner 1.5.2). */
    String milestone;

    ZoneId profileZone;
    Detail detail;
    AllTime allTime;

    /** The All time card: every retained session (Free play included) from canonical metrics. */
    @AllArgsConstructor
    static class AllTime {
        long net, activeMillis;
        Long gpPerHour;
        int grinds;
        boolean splitAvailable;
        long supplies, losses;
        String bestNetName;
        long bestNet;
        String bestRateName;
        long bestRate;
    }

    static AllTime allTime(Engine engine, long now) {
        var summary = engine.summary();
        long net = 0, active = 0, supplies = 0, lost = 0, best = 0, bestRate = 0;
        var grinds = new HashSet<String>();
        boolean split = true;
        String bestNetName = null, rateName = null;
        var seen = new HashSet<String>();
        var sessions = new ArrayList<>(engine.getHistory());
        sessions.add(engine.getActiveSession());
        sessions.add(engine.getGeneralSession());
        for (var session : sessions) {
            if (session == null || !seen.add(session.getId())) continue;
            var run = summary.run(engine, session, now);
            var metrics = run.metrics;
            net = safeAdd(net, metrics.net);
            active = safeAdd(active, metrics.elapsedMillis);
            split &= metrics.costSplitAvailable;
            supplies = safeAdd(supplies, metrics.suppliesCosts);
            lost = safeAdd(lost, metrics.otherCosts);
            if (session.grind) {
                // Runs of one saved Grind count once; an unsaved Grind is its own name.
                grinds.add(session.isLinked() ? session.getGrindId() : "name:" + session.getName());
            }
            if (!session.isClosed()) continue;
            if (bestNetName == null || run.net() > best) {
                bestNetName = session.getName();
                best = run.net();
            }
            if (run.gpPerHour != null && (rateName == null || run.gpPerHour > bestRate)) {
                rateName = session.getName();
                bestRate = run.gpPerHour;
            }
        }
        return new AllTime(net, active, RateReadiness.isEstablished(active) ? Session.hourly(net, active) : null,
                grinds.size(), split, supplies, lost, bestNetName, best, rateName, bestRate);
    }

    static GrindsData capture(Engine engine, long now, boolean includeArchived, String detailId) {
        var summary = engine.summary();
        var active = engine.getActiveSession();
        boolean custom = engine.isCustomActive();
        long net = 0, rate = 0;
        boolean rateEstablished = false;
        long elapsed = 0;
        Long target = null, duration = null;
        String activeName = "", activeSessionId = "";
        if (active != null) {
            var metrics = summary.metrics(engine.getActiveSession(), now);
            net = metrics.net;
            rate = metrics.profitPerHour;
            rateEstablished = RateReadiness.isEstablished(metrics.elapsedMillis);
            elapsed = metrics.elapsedMillis;
            target = active.getProfitTargetGp();
            duration = active.getActiveTimeTargetMillis();
            activeName = active.getName();
            activeSessionId = active.getId();
        }
        // The Active Time target's own window counts from when it was set (owner live 2026-10-13);
        // the displayed Active Time above stays the run's total.
        long targetWindowMillis = active == null ? 0 : active.getActiveTimeTargetWindowMillis(elapsed);
        Pace pace = pace(target, duration, active == null ? net : active.profitTargetProgress(net),
                        rateEstablished, rate, targetWindowMillis);
        var plans = new ArrayList<MyGrind>();
        for (var definition : engine.getSavedGrinds(includeArchived)) {
            var rollup = summary.rollups.get(definition.getGrindId());
            plans.add(new MyGrind(definition, rollup.count, rollup.has, rollup.last, rollup.at, rollup.has,
                            rollup.best, Session.hourly(rollup.total, rollup.millis),
                            RateReadiness.isEstablished(rollup.millis)));
        }
        plans.sort(Comparator.comparing((MyGrind plan) -> !plan.definition.favorite)
                        .thenComparing(grind -> -grind.lastStartedAt).thenComparing(
                                grind -> grind.definition.getName(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(grind -> grind.definition.getGrindId()));
        var recent = new ArrayList<Recent>();
        var runs = new ArrayList<>(engine.getHistory());
        // The current Free play heads FREE PLAY; a running Grind pauses it, so it waits then.
        if (!custom && engine.getGeneralSession() != null) runs.add(engine.getGeneralSession());
        for (var session : runs) {
            if (session == null) continue;
            var metrics = summary.run(engine, session, now).metrics;
            recent.add(new Recent(session.getId(),
                            session.isClosed() || session.grind ? session.getName() : "Free play",
                            session.startedAtEpochMillis, metrics.net, metrics.elapsedMillis, !session.grind));
        }
        recent.sort(Comparator.comparingLong((Recent item) -> item.startedAt).reversed()
                        .thenComparing(item -> item.sessionId));
        for (var saved : engine.savedSessions) {
            var money = saved.session.metrics(now);
            recent.add(new Recent(saved.id, saved.session.getName(), saved.session.endedAtEpochMillis,
                    money.net, money.elapsedMillis, false));
        }
        Detail detail = null;
        if (!empty(detailId)) {
            Session selected = active != null && detailId.equals(active.getId()) ? active
                            : engine.historySession(detailId);
            if (selected != null) {
                boolean liveDetail = active != null && detailId.equals(active.getId());
                Metrics metrics = engine.savedSession(detailId) != null ? selected.metrics(now)
                                : liveDetail ? summary.metrics(engine.getActiveSession(), now)
                                : summary.run(engine, selected, now).metrics;
                detail = new Detail(detailId, selected.getName(), selected.startedAtEpochMillis, metrics.net,
                                selected.profitTargetProgress(metrics.net), metrics.revenue,
                                metrics.costSplitAvailable ? metrics.suppliesCosts : -1L,
                                metrics.costSplitAvailable ? metrics.otherCosts : -1L, metrics.costSplitAvailable,
                                metrics.elapsedMillis, selected.getActiveTimeTargetWindowMillis(metrics.elapsedMillis),
                                RateReadiness.isEstablished(metrics.elapsedMillis),
                                Session.hourly(metrics.net, metrics.elapsedMillis), selected.getProfitTargetGp(),
                                selected.getActiveTimeTargetMillis(), selected.isClosed(),
                                selected.compactedRevenue() != 0 || selected.compactedCosts() != 0);
            }
        }
        var profileZone = engine.timeZone();
        return new GrindsData(active != null && custom, activeName, activeSessionId, net, rate, rateEstablished,
                elapsed, target, duration, pace, plans, recent,
                milestoneText(engine.getGeneralSession(), now), profileZone, detail, allTime(engine, now));
    }

    /** "1B \u00b7 42%" or "1B \u2713" for Free play's milestone; empty with none. */
    static String milestoneText(Session general, long now) {
        Long gp = general == null ? null : general.milestoneGp;
        if (gp == null) return "";
        long net = general.metrics(now).net;
        return compact(gp) + " \u00b7 " + (net >= gp ? "\u2713" : percent(max(0d, (double) net / gp)));
    }
}

/**
 * Shared Grind facts: one session's run metrics and the factual target wording the Grinds pages
 * show. Nothing here books, prices, persists or feeds accounting.
 */
class GrindHistory {
    /**
     * One session's shared facts: the retained summary's metrics, else the session's own
     * correction-aware totals, plus the full-session rate once established. Grinds, Grind history
     * and the All time card all read sessions through this, so the pages cannot disagree.
     */
    static class Run {
        Session session;
        Metrics metrics;
        Long gpPerHour;

        /** Detached metrics supplied by the revision summary; fields keep their existing names. */
        Run(Session session, Metrics metrics) {
            this.session = session;
            this.metrics = metrics;
            gpPerHour = RateReadiness.isEstablished(metrics.elapsedMillis)
                            ? Session.hourly(metrics.net, metrics.elapsedMillis) : null;
        }

        long net() {
            return metrics.net;
        }
    }

    /** Factual target wording; never "Failed". Shared with the Grinds detail. */
    static String netOutcome(Long profitTarget, long net, boolean closed) {
        if (profitTarget == null) return "No Net target";
        if (net >= profitTarget) return "Reached " + compact(profitTarget) + " Net";
        return closed ? msg("ended-before") + signed(profitTarget - net) + " remaining"
                : msg("reached") + signed(profitTarget - net) + " remaining";
    }

    /** Factual Active-Time target wording; never "Failed". Shared with the Grinds detail. */
    static String timeOutcome(Long activeTarget, long activeMillis) {
        if (activeTarget == null) return msg("active-time-target-2");
        if (activeMillis >= activeTarget) return msg("time-target");
        return duration(activeTarget - activeMillis) + msg("active-time-remaining");
    }

    /** Full-session rate: Net over canonical Active Time; zero Active Time has no rate. */
}

/**
 * One presentation law for "is this GP/h trustworthy yet". A computed rate is only meaningful once
 * the Grind has real Active Time behind it; before that the canonical number is a data-availability
 * artifact, not a rate, and every surface must render the unavailable form ("—") instead of a
 * fabricated 0/h.
 *
 * <p>The minimum window is the historical accepted value (the retired {@code RateAvailability} used
 * the same 60 seconds); it is presentation-only and never affects accounting.
 */
class RateReadiness {
    /** Minimum Active Time before a computed rate is presentation-trustworthy. */
    static final long MIN_ACTIVE_MILLIS = 60_000;

    /**
     * GP/h is Net over canonical Active Time; it is shown once that Active Time reaches the floor.
     *
     * @param activeMillis Active Time of the displayed rate window
     */
    static boolean isEstablished(long activeMillis) {
        return activeMillis >= MIN_ACTIVE_MILLIS;
    }
}
