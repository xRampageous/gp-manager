package com.gpmanager;

import static com.gpmanager.Fmt.*;
import static com.gpmanager.GameData.msg;
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
import java.time.ZoneId;
import java.util.*;
import java.util.List;
import javax.swing.*;
import lombok.*;

/**
 * Grinds = management (SIDEBAR_SPEC.md §3): the Running line linking to Live, the My Grinds table
 * with the selected row's panel, and Recent grinds opening Grind detail (hero, highlights, vs
 * previous, personal bests). The page renders a {@link GrindsData} and holds no engine.
 */
class GrindsPage implements Shell.Page {
    interface Actions {
        /** Shows Live. */
        void back();

        void openLedger(String sessionId);

        void endGrind();

        void startGrind(String grindId, String nameOverride);

        void openTargets();

        /** Opens the Start Grind sheet. */
        void newGrind();

        void editSavedGrind(String grindId);

        void favoriteSavedGrind(String grindId, boolean favorite);

        void archiveSavedGrind(String grindId, boolean archived);

        void deleteSavedGrind(String grindId);

        void startAgain(String sessionId);

        void openDetail(String sessionId);

        void deleteHistorical(String sessionId);

        /** The Grinds ⋯ menu: backups and recovery. */
        void openDataMenu(JComponent anchor);

        void refresh();
    }

    Actions actions;
    JPanel body = stack(PANEL, GAP);
    JPanel bar = new JPanel(new BorderLayout(4, 0));
    JPanel list = stack(PANEL, GAP);
    JPanel detail = stack(PANEL, GAP);
    JPanel running = new JPanel(new BorderLayout(GAP, 0));
    JLabel runningName = label("", bold(), TEXT);
    JLabel runningNet = label("", numbers(11.5f), TEXT);
    Table mine = new Table("MY GRINDS");
    JPanel selectedPanel = stack(CARD, 0);
    Table recent = new Table("RECENT GRINDS");

    /** All time as one compact strip; hovering it gives the full breakdown. */
    JPanel allTime = stack(CARD, 0);

    Hero hero = new Hero();
    GrindsData data;
    String selectedId;

    GrindsPage(Actions actions) {
        this.actions = actions;
        JButton start = button("Start Grind", true, actions::newGrind);
        JButton more = button("\u00b7\u00b7\u00b7", false, () -> {});
        more.setToolTipText("Grinds options");
        more.getAccessibleContext().setAccessibleName("Grinds options");
        more.addActionListener(e -> actions.openDataMenu(more));
        // Owner 2026-10-01: the same toolbar band as the Ledger; the controls live in the header.
        Kit.headerBand(bar, new JComponent[] {start}, new JComponent[] {more});
        running.setBackground(CARD);
        pad(running, 2, 6, 2, 2);
        runningName.setText("● ");
        runningName.setForeground(TEXT);
        running.add(runningName, BorderLayout.WEST);
        runningNet.setHorizontalAlignment(JLabel.RIGHT);
        running.add(runningNet, BorderLayout.CENTER);
        var runningButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        runningButtons.setOpaque(false);
        JButton live = button("Live ›", false, actions::back);
        live.setToolTipText("Open Live");
        JButton end = button("End", false, actions::endGrind);
        end.setForeground(LOSS.color);
        end.setToolTipText(msg("end-grind"));
        runningButtons.add(live);
        runningButtons.add(end);
        running.add(runningButtons, BorderLayout.EAST);
        mine.setEmptyText(msg("saved-grinds"));
        recent.setEmptyText(msg("finished-grinds")).setFill(true);
        pad(selectedPanel, 0, 0, 4, 0);
        // Owner 2026-09-28: My Grinds and Recent grinds first; All time closes the page.
        for (JComponent part : new JComponent[] {running, mine, selectedPanel, recent, allTime})
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
        runningName.setText(msg("text-2") + data.activeName.replace("<", "&lt;") + "</html>");
        runningNet.setText(signed(data.activeNet));
        runningNet.setForeground(sign(data.activeNet).color);
        running.setToolTipText(
                rateLine(data.activeRateEstablished, data.activeGpPerHour)
                        + " · "
                        + duration(data.activeMillis)
                        + " active · "
                        + targetsLine(data.activeNetTarget, data.activeTimeTarget));
        runningNet.setToolTipText(exactSigned(data.activeNet) + " gp");
    }

    /** Every retained session, Free play included; bests are finished Grinds only. */
    void applyAllTime(GrindsData.AllTime allTime) {
        var tip =
                new StringBuilder("<html>All time · ")
                        .append(allTime.grinds)
                        .append(allTime.grinds == 1 ? " Grind" : " Grinds")
                        .append("<br>Net ")
                        .append(exactSigned(allTime.net))
                        .append(" gp<br>Active ")
                        .append(duration(allTime.activeMillis));
        if (allTime.splitAvailable) {
            tip.append("<br>Supplies ")
                    .append(signed(-allTime.supplies))
                    .append(" · Losses ")
                    .append(signed(-allTime.losses));
        }
        if (allTime.bestRateName != null) {
            tip.append("<br>Best GP/h ")
                    .append(allTime.bestRateName)
                    .append(" · ")
                    .append(rate(allTime.bestRate));
        }
        String best =
                allTime.bestNetName == null
                        ? ""
                        : "Best · " + allTime.bestNetName + " " + signed(allTime.bestNet);
        this.allTime.removeAll();
        JLabel title = label("ALL TIME · " + allTime.grinds, small(), LABEL);
        pad(title, 3, 6, 3, 6);
        this.allTime.add(title);
        this.allTime.add(
                Hero.strip(
                        List.of(
                                new Cell(
                                        "NET",
                                        signed(allTime.net),
                                        sign(allTime.net),
                                        exactSigned(allTime.net) + " gp",
                                        null),
                                new Cell(
                                        "GP/H",
                                        allTime.gpPerHour == null ? "—" : rate(allTime.gpPerHour),
                                        PLAIN,
                                        null,
                                        null),
                                new Cell(
                                        "ACTIVE",
                                        durationShort(allTime.activeMillis),
                                        PLAIN,
                                        duration(allTime.activeMillis),
                                        null))));
        if (!best.isEmpty()) {
            JLabel bestLine = label(best, small(), sign(allTime.bestNet).color);
            pad(bestLine, 3, 6, 4, 6);
            this.allTime.add(bestLine);
        }
        this.allTime.setToolTipText(tip.append("</html>").toString());
        for (Component child : this.allTime.getComponents()) {
            if (child instanceof JComponent && ((JComponent) child).getToolTipText() == null) {
                ((JComponent) child).setToolTipText(this.allTime.getToolTipText());
            }
        }
        this.allTime.revalidate();
    }

    void applyMine(GrindsData data) {
        var rows = new ArrayList<Row>();
        MyGrind selected = null;
        for (MyGrind grind : data.myGrinds) {
            SavedGrind def = grind.definition;
            String id = def.getGrindId();
            boolean isSelected = id.equals(selectedId);
            if (isSelected) selected = grind;
            String runs = grind.linkedCount + (grind.linkedCount == 1 ? " run" : " runs");
            // Owner 1.5.0: the average rate sits in the wide middle column, the last run's Net on
            // the right (a rate with "/h" overflowed the value column).
            rows.add(
                    new Row(
                            id,
                            null,
                            (def.favorite ? "★ " : "") + def.getName(),
                            grind.linkedCount == 0
                                    ? "NEW"
                                    : grind.hasRate ? rate(grind.rate) + "/h" : runs,
                            grind.hasLast ? signed(grind.lastNet) : "—",
                            grind.hasLast ? sign(grind.lastNet) : DIM,
                            def.getName()
                                    + " · "
                                    + runs
                                    + " · "
                                    + targetsLine(
                                            def.getNetTargetGp(), def.getActiveTimeTargetMillis()),
                            grind.hasLast ? "Last run " + exactSigned(grind.lastNet) + " gp" : "",
                            isSelected,
                            () -> select(isSelected ? null : id),
                            grindMenu(grind)));
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

    void applySelected(MyGrind grind) {
        selectedPanel.removeAll();
        selectedPanel.setVisible(grind != null);
        if (grind == null) return;
        SavedGrind def = grind.definition;
        var facts = new KeyValue(null);
        facts.put(
                "Last run",
                grind.hasLast
                        ? signed(grind.lastNet) + " · " + when(grind.lastStartedAt, SafeMath.now())
                        : "—",
                grind.hasLast ? sign(grind.lastNet) : DIM);
        facts.put(
                "Best",
                grind.hasBest ? signed(grind.bestNet) : "—",
                grind.hasBest ? sign(grind.bestNet) : DIM);
        facts.put("Average", grind.hasRate ? rate(grind.rate) + "/h" : "—", PLAIN);
        facts.put(
                "Target",
                targetsLine(def.getNetTargetGp(), def.getActiveTimeTargetMillis()),
                PLAIN);
        selectedPanel.add(facts);
        var buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        buttons.setOpaque(false);
        // Owner 2026-10-01 (F14): the selected saved Grind starts right here, beside its menu.
        JButton start = button("Start", true, () -> actions.startGrind(def.getGrindId(), null));
        start.setToolTipText("Start " + def.getName());
        buttons.add(start);
        buttons.add(button("Edit", false, () -> actions.editSavedGrind(def.getGrindId())));
        JButton more = button("···", false, () -> {});
        more.setToolTipText("More for this Grind");
        more.addActionListener(e -> popup(grindMenu(grind)).show(more, 0, more.getHeight()));
        buttons.add(more);
        selectedPanel.add(buttons);
    }

    List<Menu> grindMenu(MyGrind grind) {
        SavedGrind def = grind.definition;
        String id = def.getGrindId();
        return Arrays.asList(
                new Menu("Start", () -> actions.startGrind(id, null)),
                new Menu("Edit…", () -> actions.editSavedGrind(id)),
                new Menu(
                        def.favorite ? "Unfavourite" : "★ Favourite",
                        () -> actions.favoriteSavedGrind(id, !def.favorite)),
                new Menu(
                        def.archived ? "Unarchive" : "Archive",
                        () -> actions.archiveSavedGrind(id, !def.archived)),
                new Menu("Delete…", () -> actions.deleteSavedGrind(id)));
    }

    void applyRecent(GrindsData data) {
        long now = SafeMath.now();
        var rows = new ArrayList<Row>();
        for (Recent run : data.recent) {
            String id = run.sessionId;
            ZoneId zone = data.profileZone == null ? ZoneId.systemDefault() : data.profileZone;
            String when =
                    dayLabel(
                            Instant.ofEpochMilli(run.startedAt).atZone(zone).toLocalDate(),
                            now,
                            zone);
            rows.add(
                    new Row(
                            id,
                            null,
                            run.name,
                            when,
                            valueText(run.net),
                            sign(run.net),
                            run.name + " · " + contextLine(run, now, data.profileZone),
                            exactSigned(run.net) + " gp",
                            false,
                            () -> actions.openDetail(id),
                            Arrays.asList(
                                    new Menu("Open in Ledger", () -> actions.openLedger(id)),
                                    new Menu("Start again", () -> actions.startAgain(id)),
                                    new Menu("Delete…", () -> actions.deleteHistorical(id)))));
        }
        recent.countedTitle("RECENT GRINDS", rows);
    }

    // ---- detail -------------------------------------------------------------------------------
    void buildDetail(GrindsData data) {
        GrindsData.Detail detail = data.detail;
        this.detail.removeAll();
        JButton back = button("‹ Grinds", false, () -> actions.openDetail(null));
        var top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(back, BorderLayout.WEST);
        top.add(
                label(
                        when(detail.startedAt, SafeMath.now()).toUpperCase(Locale.ROOT),
                        small(),
                        LABEL),
                BorderLayout.EAST);
        pad(top, 0, 0, 0, 6);
        this.detail.add(top);
        hero.status(
                detail.closed ? OFF : GAIN.color,
                detail.closed ? "Finished" : "Running",
                detail.name,
                detail.closed ? "COMPLETE" : "",
                null);
        hero.big(
                signed(detail.net),
                sign(detail.net),
                exactSigned(detail.net) + " gp",
                detail.rateEstablished ? rate(detail.gpPerHour) + "/h" : "—",
                detail.rateEstablished,
                duration(detail.activeMillis) + " ACTIVE");
        // Owner 2026-10-01 (F12): a completed run's rate is the whole-run basis.
        hero.rate.setToolTipText(detail.rateEstablished ? msg("gp-h") : null);
        var strip =
                Hero.money(
                        detail.revenue,
                        detail.supplies,
                        detail.losses,
                        detail.revenue - detail.net,
                        detail.splitAvailable,
                        null);
        hero.strips(Collections.singletonList(strip));
        this.detail.add(hero);
        var outcome = new KeyValue("HIGHLIGHTS");
        outcome.put("Target", detail.netOutcome(), PLAIN);
        if (detail.activeTimeTargetMillis != null) outcome.put("Time", detail.timeOutcome(), PLAIN);
        if (data.activePace != null
                && data.activePace.present
                && data.activePace.available
                && detail.sessionId.equals(data.activeSessionId)) {
            outcome.put("Pace", data.activePace.line, DIM);
            // Owner 2026-10-01 (F10): a combined target also shows its required average.
            if (data.activePace.second != null) outcome.put("", data.activePace.second, DIM);
        }
        this.detail.add(outcome);
        if (detail.compacted) this.detail.add(note(msg("summary-detailed"), LABEL));
        var buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        buttons.setOpaque(false);
        buttons.add(button("Start again", true, () -> actions.startAgain(detail.sessionId)));
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
        return Kit.menuOf(items);
    }

    // ---- text ---------------------------------------------------------------------------------
    /** Date and duration context: Today/Yesterday/date plus Active Time, never internal ids. */
    static String contextLine(Recent recent, long now, ZoneId zone) {
        ZoneId z = zone == null ? ZoneId.systemDefault() : zone;
        return dayLabel(Instant.ofEpochMilli(recent.startedAt).atZone(z).toLocalDate(), now, z)
                + " · "
                + durationShort(recent.activeMillis);
    }

    /** Understandable signed money: explicit "0 GP" for zero, never a bare 0. */
    static String valueText(long net) {
        return net == 0L ? "0 GP" : signed(net);
    }

    static String rateLine(boolean established, long gpPerHour) {
        return established ? rate(gpPerHour) + "/h" : "—  GP/h";
    }

    static String targetsLine(Long netTargetGp, Long activeTimeTargetMillis) {
        var parts = new ArrayList<String>();
        if (netTargetGp != null) parts.add(targetNet(netTargetGp) + " Net");
        if (activeTimeTargetMillis != null) parts.add(targetTime(activeTimeTargetMillis));
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
        this.engine = panel.engine;
        this.shell = panel.shell();
        this.repository = panel.repository;
        this.persistence = panel.persistence;
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

    public void openLedger(String sessionId) {
        Session active = engine.getActiveSession();
        if (active != null && active.getId().equals(sessionId)) {
            openEntry(Entry.current());
        } else {
            Session historical = sessionId == null ? null : engine.historySession(sessionId);
            openEntry(
                    new Entry(
                            LedgerData.Scope.HISTORY,
                            sessionId,
                            historical == null ? null : historical.getName()));
        }
    }

    public void endGrind() {
        Session ending = engine.getActiveSession();
        if (ending == null || !engine.isCustomActive()) return;
        String endedId = ending.getId();
        String endedName = ending.getName();
        // Ending is a deliberate menu pick and keeps history, so it needs no second confirmation.
        mutate(
                () -> {
                    if (!engine.finishCustom(SafeMath.now())) return;
                    Metrics ended = engine.historyMetrics(endedId, SafeMath.now());
                    // Owner 2026-10-01 (F14): the ended run stays one click from its recap.
                    shell.notify(
                            endedName + " ended",
                            ended == null ? "" : signed(ended.net),
                            false,
                            "View recap",
                            () -> {
                                panel.grindsDetailId = endedId;
                                shell.show(Shell.GRINDS);
                                refresh();
                            });
                });
    }

    /** Soft reset from the Live clock; a running Grind is ended instead (owner 2026-10-16). */
    public void startFresh() {
        if (engine.isCustomActive()) {
            shell.sheet(
                    "End this Grind?",
                    msg("end-for-fresh"),
                    null,
                    new Shell.Choice("End Grind", true, this::endGrind));
        } else {
            shell.sheet(
                    "Start fresh?",
                    msg("start-fresh"),
                    null,
                    new Shell.Choice(
                            "Start fresh",
                            true,
                            () -> mutate(() -> engine.startFreshFreePlay(SafeMath.now()))));
        }
    }

    /** A short getting-started guide; its words live in text.tsv (owner 2026-10-16). */
    void guide() {
        JPanel content = stack(CARD, 4);
        for (String part : msg("guide").split(" \\| ")) {
            JLabel line = note(part, TEXT, SHEET_TEXT);
            line.setBorder(null);
            content.add(line);
        }
        shell.sheet("Getting started", null, content);
    }

    public void startGrind(String grindId, String nameOverride) {
        SavedGrind definition = grindId == null ? null : engine.savedGrind(grindId);
        String name =
                nameOverride != null && !nameOverride.trim().isEmpty()
                        ? nameOverride.trim()
                        : definition == null ? "" : definition.getName();
        Long net = definition == null ? null : definition.getNetTargetGp();
        Long time = definition == null ? null : definition.getActiveTimeTargetMillis();
        if (definition != null) grindId = definition.getGrindId();
        if (grindId != null
                && engine.isCustomActive()
                && engine.getActiveSession() != null
                && grindId.equals(engine.getActiveSession().getGrindId())) {
            shell.show(Shell.LIVE);
            refresh();
            return;
        }
        startGrind(name, grindId, net, time);
    }

    /**
     * Start a Grind through the switch guard and show it on Live, saying Free play is kept (owner
     * 1.5.0: a fresh Grind's empty Live read as lost data).
     */
    void startGrind(String name, String grindId, Long net, Long time) {
        whenSwitchAllowed(
                name,
                () -> {
                    mutate(() -> engine.startGrind(name, grindId, net, time, SafeMath.now()));
                    panel.grindsDetailId = null;
                    shell.show(Shell.LIVE);
                    shell.tell(name + " started", msg("free-play-kept"), false);
                });
    }

    public void openTargets() {
        Session active = engine.getActiveSession();
        if (active == null) return;
        targetSheet(
                "Targets",
                null,
                grindForm(null, active.getProfitTargetGp(), active.getActiveTimeTargetMillis()),
                "Save",
                this::openTargets,
                (name, net, time) -> {
                    Long netTarget = GrindsData.applyNet(net, active.getProfitTargetGp());
                    Long timeTarget =
                            GrindsData.applyTime(time, active.getActiveTimeTargetMillis());
                    mutate(() -> engine.saveTargets(netTarget, timeTarget, SafeMath.now()));
                });
    }

    /**
     * The one Start Grind sheet, from Live and Grinds (owner 1.5.0): your Grinds start in one
     * click, the one you are playing right now first; or a name with its targets, which becomes a
     * Grind. Owner 2026-10-01 (F22): the list is bounded to five rows with paging.
     */
    public void newGrind() {
        Session active = engine.getActiveSession();
        String doing = active == null || engine.isCustomActive() ? "" : active.getActivityHint();
        if (ActivityDetector.GENERAL.equalsIgnoreCase(doing)) doing = "";
        SavedGrind playing = engine.grindNamed(doing);
        var saved = new ArrayList<>(engine.getSavedGrinds(false));
        saved.sort(
                Comparator.comparing((SavedGrind grind) -> grind != playing)
                        .thenComparing(grind -> !grind.favorite));
        JPanel content = stack(CARD, 2);
        new Paged(
                        saved.size(),
                        (box, page) -> {
                            int from = Math.min(page * CHOICE_ROWS, saved.size());
                            for (SavedGrind grind :
                                    saved.subList(
                                            from, Math.min(from + CHOICE_ROWS, saved.size()))) {
                                JButton start =
                                        button(
                                                (grind.favorite ? "\u2605 " : "")
                                                        + grind.getName()
                                                        + (grind == playing ? " \u00b7 now" : ""),
                                                false,
                                                () -> {
                                                    shell.closeSheet();
                                                    startGrind(grind.getGrindId(), null);
                                                });
                                start.setHorizontalAlignment(SwingConstants.LEFT);
                                start.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 22));
                                box.add(start);
                            }
                        })
                .build(content);
        if (!saved.isEmpty()) content.add(label("Or a new one", small(), LABEL));
        // What you are doing now names a new Grind, unless it already is one (listed first).
        JPanel form = grindForm(playing == null ? doing : "", null, null);
        content.add(form);
        shell.sheet(
                "Start Grind",
                null,
                content,
                new Shell.Choice(
                        "Start",
                        true,
                        () -> {
                            String name = field(form, "nameField");
                            String net = field(form, "netField");
                            String time = field(form, "timeField");
                            if (name.isEmpty()) return;
                            if (!GrindsData.inputsValid(net, time)) {
                                shell.tell("Targets not saved", msg("use-number"), true);
                                return;
                            }
                            SavedGrind known = engine.grindNamed(name);
                            startGrind(
                                    known == null ? name : known.getName(),
                                    known == null ? null : known.getGrindId(),
                                    GrindsData.applyNet(net, null),
                                    GrindsData.applyTime(time, null));
                        }));
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
        int[] page = {0};
        JButton prev = button("\u2039", false, () -> turn(-1));
        JButton next = button("\u203a", false, () -> turn(1));
        JLabel range = label("", small(), LABEL);
        JPanel bar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));

        Paged(int total, RowFill fill) {
            this.total = total;
            this.fill = fill;
            bar.setOpaque(false);
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
            page[0] = Kit.clampPage(page[0] + delta, pages());
            redraw();
        }

        int pages() {
            return Kit.pageCount(total, CHOICE_ROWS);
        }

        void redraw() {
            box.removeAll();
            fill.fill(box, page[0]);
            int from = page[0] * CHOICE_ROWS;
            Kit.applyPager(
                    range,
                    prev,
                    next,
                    page[0],
                    pages(),
                    total == 0
                            ? ""
                            : (from + 1)
                                    + "\u2013"
                                    + Math.min(total, from + CHOICE_ROWS)
                                    + " of "
                                    + total);
            box.revalidate();
        }
    }

    /** Name and targets; a running run of this Grind follows the change (owner 1.5.0). */
    public void editSavedGrind(String grindId) {
        SavedGrind definition = engine.savedGrind(grindId);
        if (definition == null) return;
        targetSheet(
                "Edit " + definition.getName(),
                null,
                grindForm(
                        definition.getName(),
                        definition.getNetTargetGp(),
                        definition.getActiveTimeTargetMillis()),
                "Save",
                () -> editSavedGrind(grindId),
                (name, net, time) -> {
                    Long netTarget = GrindsData.applyNet(net, definition.getNetTargetGp());
                    Long timeTarget =
                            GrindsData.applyTime(time, definition.getActiveTimeTargetMillis());
                    mutate(
                            () ->
                                    engine.updateSavedGrind(
                                            grindId,
                                            name,
                                            netTarget,
                                            timeTarget,
                                            definition.favorite));
                });
    }

    public void favoriteSavedGrind(String grindId, boolean favorite) {
        SavedGrind definition = engine.savedGrind(grindId);
        if (definition == null) return;
        mutate(
                () ->
                        engine.updateSavedGrind(
                                grindId,
                                definition.getName(),
                                definition.getNetTargetGp(),
                                definition.getActiveTimeTargetMillis(),
                                favorite));
    }

    public void archiveSavedGrind(String grindId, boolean archived) {
        if (!archived) {
            mutate(() -> engine.archiveGrind(grindId, false));
            return;
        }
        shell.confirm(
                "Archive Grind",
                msg("hides-my"),
                "Archive",
                () -> mutate(() -> engine.archiveGrind(grindId, true)));
    }

    public void deleteSavedGrind(String grindId) {
        SavedGrind definition = engine.savedGrind(grindId);
        if (definition == null) return;
        shell.confirm(
                "Delete " + definition.getName(),
                msg("removes-my"),
                "Delete",
                () -> mutate(() -> engine.deleteSavedGrind(grindId)));
    }

    /** Starts the run's Grind with its current setup; an older one-off run becomes a Grind. */
    public void startAgain(String sessionId) {
        Session active = engine.getActiveSession();
        Session session =
                empty(sessionId) || active != null && sessionId.equals(active.getId())
                        ? active
                        : engine.historySession(sessionId);
        if (session == null) return;
        SavedGrind grind = engine.savedGrind(session.getGrindId());
        if (grind == null) grind = engine.grindNamed(session.getName());
        if (grind != null) {
            startGrind(grind.getGrindId(), null);
        } else {
            startGrind(
                    session.getName(),
                    null,
                    session.getProfitTargetGp(),
                    session.getActiveTimeTargetMillis());
        }
    }

    public void openDetail(String sessionId) {
        panel.grindsDetailId = sessionId;
        refresh();
    }

    public void deleteHistorical(String sessionId) {
        Session session = engine.historySession(sessionId);
        if (session == null) return;
        shell.confirm(
                "Delete " + session.getName(),
                msg("removes-run"),
                "Delete for good",
                () -> {
                    panel.grindsDetailId = null;
                    mutate(() -> engine.deleteHistory(sessionId));
                });
    }

    /** The Grinds page's own menu: only what changes that page. */
    public void openDataMenu(JComponent anchor) {
        var menu = new JPopupMenu();
        JCheckBoxMenuItem archived =
                new JCheckBoxMenuItem("Show archived", panel.grindsShowArchived);
        archived.addActionListener(
                e -> {
                    panel.grindsShowArchived = archived.isSelected();
                    refresh();
                });
        menu.add(archived);
        menu.show(anchor, 0, anchor.getHeight() + 2);
    }

    /** The gear menu beside the tabs: profile-wide data actions, from any page. */
    JPopupMenu dataMenu() {
        var menu = new JPopupMenu();
        menu.add(item("Getting started", this::guide));
        menu.add(item("Back up profile", persistence != null, () -> backUpProfile()));
        menu.add(item(msg("copy-data"), () -> copyDataPath()));
        menu.add(
                item(
                        "Report a bug",
                        () -> net.runelite.client.util.LinkBrowser.browse(msg("bug-report"))));
        menu.add(
                item(
                        "Changelog",
                        () -> net.runelite.client.util.LinkBrowser.browse(msg("changelog"))));
        menu.addSeparator();
        menu.add(item(msg("factory-reset"), persistence != null, () -> factoryReset()));
        return menu;
    }

    public void refresh() {
        panel.refresh();
    }

    /** Name (when given) and the two targets, each with its quick picks. */
    static JPanel grindForm(String name, Long net, Long time) {
        JPanel form = stack(CARD, 2);
        if (name != null) inline(form, "nameField", "Name", name);
        long bond = bondPrice.getAsLong();
        picks(
                form,
                inline(form, "netField", "Net", net == null ? "" : targetNet(net)),
                "5m",
                bond > 0 ? "Bond=" + targetNet(bond) : "10m",
                "20m",
                "50m",
                "100m",
                "1b",
                "Clear=");
        picks(
                form,
                inline(form, "timeField", "Time", time == null ? "" : targetTime(time)),
                "30m",
                "1h",
                "2h",
                "3h",
                "4h",
                "6h",
                "Clear=");
        return form;
    }

    /** A label beside its field (owner 1.5.0: a short, sleek target sheet). */
    static JTextField inline(JPanel form, String key, String label, String value) {
        JTextField field = new JTextField(value);
        JLabel name = label(label, small(), LABEL);
        name.setPreferredSize(new Dimension(34, name.getPreferredSize().height));
        JPanel line = new JPanel(new BorderLayout());
        line.setOpaque(false);
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
        JPanel row = new JPanel(new GridLayout(1, 0));
        row.setOpaque(false);
        pad(row, 0, 0, 2, 0);
        Map<JButton, String> values = new LinkedHashMap<>();
        Runnable light =
                () ->
                        values.forEach(
                                (chip, value) ->
                                        chip.setForeground(
                                                value.equalsIgnoreCase(field.getText().trim())
                                                        ? ACCENT
                                                        : LABEL));
        for (String pick : picks) {
            String[] part = pick.split("=", -1);
            String value = part[part.length - 1];
            JButton chip =
                    button(
                            part[0],
                            false,
                            () -> {
                                field.setText(value);
                                light.run();
                            });
            chip.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
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
     * A name-and-targets sheet; malformed targets never become a silent clear: say so and reopen
     * it.
     */
    void targetSheet(
            String title,
            String detail,
            JPanel form,
            String action,
            Runnable reopen,
            TargetSave save) {
        shell.sheet(
                title,
                detail,
                form,
                new Shell.Choice(
                        action,
                        true,
                        () -> {
                            String net = field(form, "netField");
                            String time = field(form, "timeField");
                            if (GrindsData.inputsValid(net, time)) {
                                save.save(field(form, "nameField"), net, time);
                            } else {
                                shell.tell("Targets not saved", msg("use-number"), true);
                                reopen.run();
                            }
                        }));
    }

    /** One Grind at a time: starting another asks to end the running one first (spec §4). */
    void whenSwitchAllowed(String nextName, Runnable start) {
        if (engine.getActiveSession() == null || !engine.isCustomActive()) {
            start.run();
            return;
        }
        LiveSnapshot now = panel.lastSnapshot;
        String running =
                engine.getActiveSession().getName()
                        + (now == null
                                ? ""
                                : " (" + signed(now.net) + ", " + clock(now.elapsedMillis) + ")");
        // The engine never replaces a running Grind silently: Switch ends it first, then starts.
        shell.confirm(
                "Switch Grind",
                "End " + running + " and start " + nextName + "?",
                "Switch",
                () -> {
                    mutate(() -> engine.finishCustom(SafeMath.now()));
                    start.run();
                });
    }

    /** Puts a path on the system clipboard. */
    static void copyPath(String path) {
        java.awt.datatransfer.StringSelection selection =
                new java.awt.datatransfer.StringSelection(path);
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
    }

    /**
     * Disk work runs on the persistence worker, never Swing's thread, so RuneLite stays responsive;
     * its result comes back on Swing's thread (Codex review 1.2).
     */
    <T> void off(
            java.util.concurrent.Callable<T> work,
            java.util.function.Consumer<T> done,
            String failed) {
        java.util.concurrent.ExecutorService worker = persistence.lifecycleExecutor();
        if (worker == null) return;
        // Bound to the account it was confirmed on: holding the persistence lock, no account switch
        // can
        // land mid-job, and a switch before it starts refuses it (Codex follow-up 1.2).
        TrackingIdentity owner = persistence.activeIdentity;
        worker.execute(
                () -> {
                    try {
                        T result;
                        persistence.busy = true;
                        synchronized (persistence) {
                            if (!java.util.Objects.equals(owner, persistence.activeIdentity)) {
                                SwingUtilities.invokeLater(
                                        () ->
                                                shell.tell(
                                                        "Account changed",
                                                        msg("nothing-changed"),
                                                        true));
                                return;
                            }
                            result = work.call();
                        }
                        SwingUtilities.invokeLater(() -> done.accept(result));
                    } catch (Exception ex) {
                        SwingUtilities.invokeLater(() -> shell.failed(failed, ex));
                    } finally {
                        persistence.busy = false;
                    }
                });
    }

    void backUpProfile() {
        off(
                () -> persistence.writeBackup(SafeMath.now()),
                backup ->
                        shell.tell(
                                "Profile backed up", String.valueOf(backup.getFileName()), false),
                "Backup failed");
    }

    void copyDataPath() {
        if (repository == null) return;
        String path = String.valueOf(repository.dataDir());
        copyPath(path);
        shell.tell(msg("data-folder"), path, false);
    }

    void factoryReset() {
        // Reset always writes its own validated safety copy first; a failed copy refuses the reset.
        shell.sheet(
                "Factory reset",
                msg("resets-gp"),
                null,
                new Shell.Choice("Reset", false, this::resetNow));
    }

    /** The existing direct reset: one confirmation already happened in the sheet. */
    void resetNow() {
        off(() -> persistence.factoryReset(SafeMath.now()), this::reset, "Reset failed");
    }

    void reset(Persistence.ResetOutcome outcome) {
        // The profile was replaced: HUD+ must not keep the old trip's loot or moments.
        if (outcome.isApplied()) panel.resetProfile();
        if (outcome.isApplied()) {
            // The safety copy is one click from its folder (owner 2026-10-16).
            shell.notify(
                    "Profile reset", msg("fresh-profile"), false, "Copy folder path", this::copyDataPath);
        } else {
            shell.tell("Reset refused", outcome.getDetail(), true);
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
    static final long INVALID = -1L;

    /**
     * Friendly Net-target parsing: 5m, 5M, 5000k, 5,000,000. Blank is 0 (no target); malformed is
     * {@link #INVALID}.
     */
    static long parseNet(String raw) {
        if (blank(raw)) return 0L;
        // Strict whole-string form: digits with optional decimals and one k/m/b suffix. Anything
        // else is malformed and must never be treated as "clear".
        String text = Phrases.lower(raw).replace(",", "").replace("_", "").replace(" ", "");
        if (!text.matches("\\d+(?:\\.\\d+)?[kmb]?")) return INVALID;
        long value = parseGp(text);
        return value > 0L ? value : INVALID;
    }

    /**
     * Friendly Active-Time parsing: 90m, 3h, "3h 30m", plain minutes. Blank is 0 (no target);
     * malformed is {@link #INVALID}.
     */
    static long parseTime(String raw) {
        if (blank(raw)) return 0L;
        String text = Phrases.lower(raw).replace(",", "").replace("_", "").trim();
        if (text.matches("\\d+(?:\\.\\d+)?")) {
            long minutes = round(Double.parseDouble(text));
            return minutes > 0L ? minutes * 60_000L : INVALID;
        }
        // Strict whole-string form: optional "Nh" then optional "Mm" and nothing else.
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile(
                                "^(?:(\\d+(?:\\.\\d+)?)\\s*h)?\\s*(?:(\\d+(?:\\.\\d+)?)\\s*m)?$")
                        .matcher(text);
        if (!matcher.matches() || (matcher.group(1) == null && matcher.group(2) == null)) {
            return INVALID;
        }
        long total = 0L;
        if (matcher.group(1) != null)
            total += round(Double.parseDouble(matcher.group(1)) * 3_600_000d);
        if (matcher.group(2) != null)
            total += round(Double.parseDouble(matcher.group(2)) * 60_000d);
        return total > 0L ? total : INVALID;
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
        return parsed == INVALID ? existing : parsed == 0L ? null : (Long) parsed;
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
        String line;
        String second;

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
    static Pace pace(
            Long netTargetGp,
            Long timeTargetMillis,
            long net,
            boolean rateEstablished,
            long gpPerHour,
            long activeMillis) {
        if (netTargetGp == null && timeTargetMillis == null) return Pace.none();
        if (!rateEstablished) return new Pace(true, false, "Calculating\u2026", null);
        if (netTargetGp != null && timeTargetMillis == null) {
            if (net >= netTargetGp) return new Pace(true, true, "Target reached", null);
            if (gpPerHour <= 0L) {
                // The rate is established and genuinely non-positive: an ETA would be fabricated.
                return new Pace(true, true, "Not earning at this rate", null);
            }
            long minutes = nonNeg(round((netTargetGp - net) * 60d / gpPerHour));
            return new Pace(true, true, "~" + durationMinutes(minutes) + " remaining", null);
        }
        long remaining = nonNeg(timeTargetMillis - activeMillis);
        long projected = net + round(gpPerHour * (remaining / 3_600_000d));
        if (netTargetGp == null) {
            return remaining <= 0L
                    ? new Pace(true, true, "Time target reached", null)
                    : new Pace(true, true, "~" + signed(projected) + " projected Net", null);
        }
        if (remaining <= 0L) {
            return new Pace(
                    true,
                    true,
                    "Time target reached",
                    "Required average was "
                            + compact(requiredAverage(netTargetGp, net, activeMillis))
                            + "/h");
        }
        long required = requiredAverage(netTargetGp, net, timeTargetMillis);
        return new Pace(
                true,
                true,
                "~"
                        + signed(projected)
                        + " projected at "
                        + durationMinutes(timeTargetMillis / 60_000L),
                "Required average " + compact(required) + "/h");
    }

    static long requiredAverage(long target, long net, long byMillis) {
        if (byMillis <= 0L) return 0L;
        return round(nonNeg(target - net) * 3_600_000d / byMillis);
    }

    /**
     * A reached aim's short projection: where the next hour lands at the established rate; the
     * rate itself lives on the hero, so the line stays one short phrase (owner 2026-10-14).
     */
    static String nextHour(long net, long gpPerHour) {
        return "~" + signed(net + gpPerHour) + " in 1h";
    }

    static String durationMinutes(long minutes) {
        return minutes < 90L ? minutes + "m" : (minutes / 60L) + "h " + (minutes % 60L) + "m";
    }

    /** One reusable definition plus its derived Last/Best subset from linked canonical history. */
    @AllArgsConstructor
    static class MyGrind {
        SavedState.SavedGrind definition;
        int linkedCount;
        boolean hasLast;
        long lastNet;
        long lastStartedAt;
        boolean hasBest;
        long bestNet;

        /** Average GP/h over every finished run (owner 1.5.0: Grinds compare by rate). */
        long rate;

        boolean hasRate;
    }

    /** One recent canonical instance, rendered with Grind language. */
    @AllArgsConstructor
    static class Recent {
        String sessionId;
        String name;
        long startedAt;
        long net;
        long activeMillis;
    }

    /** One instance's detail: financial summary, targets and target outcome. */
    @AllArgsConstructor
    static class Detail {
        String sessionId;
        String name;
        long startedAt;
        long net;
        /** What the Net aim counted when this ran (the run's Net minus its set baseline). */
        long targetProgress;
        long revenue;
        long supplies;
        long losses;
        boolean splitAvailable;
        long activeMillis;
        /** The Active Time window the run's time target counts from (set moment to now/end). */
        long targetWindowMillis;
        boolean rateEstablished;
        long gpPerHour;
        Long netTargetGp;
        Long activeTimeTargetMillis;
        boolean closed;
        boolean compacted;

        /** Factual target outcome wording; never "Failed". */
        String netOutcome() {
            return GrindHistory.netOutcome(netTargetGp, targetProgress, closed);
        }

        String timeOutcome() {
            return GrindHistory.timeOutcome(activeTimeTargetMillis, targetWindowMillis);
        }
    }

    boolean hasActive;
    String activeName;
    String activeSessionId;
    long activeNet;
    long activeGpPerHour;
    boolean activeRateEstablished;
    long activeMillis;
    Long activeNetTarget;
    Long activeTimeTarget;
    Pace activePace;
    List<MyGrind> myGrinds;
    List<Recent> recent;
    ZoneId profileZone;
    Detail detail;
    AllTime allTime;

    /** The All time card: every retained session (Free play included) from canonical metrics. */
    @AllArgsConstructor
    static class AllTime {
        long net;
        long activeMillis;
        Long gpPerHour;
        int grinds;
        boolean splitAvailable;
        long supplies;
        long losses;
        String bestNetName;
        long bestNet;
        String bestRateName;
        long bestRate;
    }

    static AllTime allTime(Engine engine, long now) {
        MoneySnapshot summary = engine.summary();
        long net = 0L, active = 0L, supplies = 0L, losses = 0L, bestNet = 0L, bestRate = 0L;
        var grinds = new HashSet<String>();
        boolean split = true;
        String bestNetName = null;
        String bestRateName = null;
        var seen = new HashSet<String>();
        var sessions = new ArrayList<Session>(engine.getHistory());
        sessions.add(engine.getActiveSession());
        sessions.add(engine.getGeneralSession());
        for (Session session : sessions) {
            if (session == null || !seen.add(session.getId())) continue;
            var run = summary.run(engine, session, now);
            Metrics metrics = run.metrics;
            net = safeAdd(net, metrics.net);
            active = safeAdd(active, metrics.elapsedMillis);
            split &= metrics.costSplitAvailable;
            supplies = safeAdd(supplies, metrics.suppliesCosts);
            losses = safeAdd(losses, metrics.otherCosts);
            if (session.grind) {
                // Runs of one saved Grind count once; an unsaved Grind is its own name.
                grinds.add(session.isLinked() ? session.getGrindId() : "name:" + session.getName());
            }
            if (!session.isClosed()) continue;
            if (bestNetName == null || run.net() > bestNet) {
                bestNetName = session.getName();
                bestNet = run.net();
            }
            if (run.gpPerHour != null && (bestRateName == null || run.gpPerHour > bestRate)) {
                bestRateName = session.getName();
                bestRate = run.gpPerHour;
            }
        }
        return new AllTime(
                net,
                active,
                RateReadiness.isEstablished(active) ? Session.hourly(net, active) : null,
                grinds.size(),
                split,
                supplies,
                losses,
                bestNetName,
                bestNet,
                bestRateName,
                bestRate);
    }

    static GrindsData capture(
            Engine engine, long now, boolean includeArchived, String detailSessionId) {
        MoneySnapshot summary = engine.summary();
        Session active = engine.getActiveSession();
        boolean custom = engine.isCustomActive();
        long net = 0L;
        long rate = 0L;
        boolean rateEstablished = false;
        long activeMillis = 0L;
        Long netTarget = null;
        Long timeTarget = null;
        String activeName = "";
        String activeSessionId = "";
        if (active != null) {
            Metrics metrics = summary.metrics(engine.getActiveSession(), now);
            net = metrics.net;
            rate = metrics.profitPerHour;
            rateEstablished = RateReadiness.isEstablished(metrics.elapsedMillis);
            activeMillis = metrics.elapsedMillis;
            netTarget = active.getProfitTargetGp();
            timeTarget = active.getActiveTimeTargetMillis();
            activeName = active.getName();
            activeSessionId = active.getId();
        }
        // The Active Time target's own window counts from when it was set (owner live 2026-10-13);
        // the displayed Active Time above stays the run's total.
        long targetWindowMillis =
                active == null ? 0L : active.getActiveTimeTargetWindowMillis(activeMillis);
        Pace pace =
                pace(
                        netTarget,
                        timeTarget,
                        active == null ? net : active.profitTargetProgress(net),
                        rateEstablished,
                        rate,
                        targetWindowMillis);
        var myGrinds = new ArrayList<MyGrind>();
        for (SavedState.SavedGrind definition : engine.getSavedGrinds(includeArchived)) {
            MoneySnapshot.Rollup rollup = summary.rollups.get(definition.getGrindId());
            myGrinds.add(
                    new MyGrind(
                            definition,
                            rollup.count,
                            rollup.has,
                            rollup.last,
                            rollup.at,
                            rollup.has,
                            rollup.best,
                            rollup.millis > 0L ? rollup.total * 3_600_000L / rollup.millis : 0L,
                            RateReadiness.isEstablished(rollup.millis)));
        }
        myGrinds.sort(
                Comparator.comparing((MyGrind grind) -> !grind.definition.favorite)
                        .thenComparing(grind -> -grind.lastStartedAt)
                        .thenComparing(
                                grind -> grind.definition.getName(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(grind -> grind.definition.getGrindId()));
        var recent = new ArrayList<Recent>();
        for (Session session : engine.getHistory()) {
            if (session == null) continue;
            Metrics metrics = summary.run(engine, session, now).metrics;
            recent.add(
                    new Recent(
                            session.getId(),
                            session.getName(),
                            session.startedAtEpochMillis,
                            metrics.net,
                            metrics.elapsedMillis));
        }
        recent.sort(
                Comparator.comparingLong((Recent item) -> item.startedAt)
                        .reversed()
                        .thenComparing(item -> item.sessionId));
        Detail detail = null;
        if (detailSessionId != null && !detailSessionId.isEmpty()) {
            Session selected =
                    active != null && detailSessionId.equals(active.getId())
                            ? active
                            : engine.historySession(detailSessionId);
            if (selected != null) {
                boolean liveDetail = active != null && detailSessionId.equals(active.getId());
                Metrics metrics =
                        liveDetail
                                ? summary.metrics(engine.getActiveSession(), now)
                                : summary.run(engine, selected, now).metrics;
                detail =
                        new Detail(
                                selected.getId(),
                                selected.getName(),
                                selected.startedAtEpochMillis,
                                metrics.net,
                                selected.profitTargetProgress(metrics.net),
                                metrics.revenue,
                                metrics.costSplitAvailable ? metrics.suppliesCosts : -1L,
                                metrics.costSplitAvailable ? metrics.otherCosts : -1L,
                                metrics.costSplitAvailable,
                                metrics.elapsedMillis,
                                selected.getActiveTimeTargetWindowMillis(metrics.elapsedMillis),
                                RateReadiness.isEstablished(metrics.elapsedMillis),
                                Session.hourly(metrics.net, metrics.elapsedMillis),
                                selected.getProfitTargetGp(),
                                selected.getActiveTimeTargetMillis(),
                                selected.isClosed(),
                                selected.compactedRevenue() != 0L
                                        || selected.compactedCosts() != 0L);
            }
        }
        ZoneId profileZone = engine.timeZone();
        return new GrindsData(
                active != null && custom,
                activeName,
                activeSessionId,
                net,
                rate,
                rateEstablished,
                activeMillis,
                netTarget,
                timeTarget,
                pace,
                myGrinds,
                recent,
                profileZone,
                detail,
                allTime(engine, now));
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
            gpPerHour =
                    RateReadiness.isEstablished(metrics.elapsedMillis)
                            ? Session.hourly(metrics.net, metrics.elapsedMillis)
                            : null;
        }

        long net() {
            return metrics.net;
        }
    }

    /** Factual target wording; never "Failed". Shared with the Grinds detail. */
    static String netOutcome(Long netTargetGp, long net, boolean closed) {
        if (netTargetGp == null) return "No Net target";
        if (net >= netTargetGp) return "Reached " + compact(netTargetGp) + " Net";
        return closed
                ? msg("ended-before") + signed(netTargetGp - net) + " remaining"
                : msg("reached") + signed(netTargetGp - net) + " remaining";
    }

    /** Factual Active-Time target wording; never "Failed". Shared with the Grinds detail. */
    static String timeOutcome(Long activeTimeTargetMillis, long activeMillis) {
        if (activeTimeTargetMillis == null) return msg("active-time-target-2");
        if (activeMillis >= activeTimeTargetMillis) return msg("time-target");
        return duration(activeTimeTargetMillis - activeMillis) + msg("active-time-remaining");
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
    static final long MIN_ACTIVE_MILLIS = 60_000L;

    /**
     * GP/h is Net over canonical Active Time; it is shown once that Active Time reaches the floor.
     *
     * @param activeMillis canonical Active Time of the Grind
     */
    static boolean isEstablished(long activeMillis) {
        return activeMillis >= MIN_ACTIVE_MILLIS;
    }
}
