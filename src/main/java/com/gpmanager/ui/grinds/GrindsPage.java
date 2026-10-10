package com.gpmanager;

import static com.gpmanager.Fmt.*;
import static com.gpmanager.GameData.msg;
import static com.gpmanager.Kit.*;
import static com.gpmanager.Kit.Tone.*;

import com.gpmanager.GrindsData.*;
import com.gpmanager.Hero.Cell;
import com.gpmanager.SavedState.SavedGrind;
import com.gpmanager.Table.*;
import com.gpmanager.Table.Menu;

import lombok.*;

import java.awt.*;
import java.time.*;
import java.util.*;
import java.util.List;

import javax.swing.*;

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

        /**
         * Saves the viewed run as a My Grind (F20): the id selects the run, never the active one.
         */
        void saveThisGrind(String sessionId);

        /** Opens the Start Grind sheet. */
        void newGrind();

        /** Saves a new, empty My Grind from a name. */
        void addGrind();

        /**
         * Updates a saved Grind's defaults from the viewed run (F20): the session id names the run.
         */
        void updateSavedGrind(String grindId, String sessionId);

        void editSavedGrind(String grindId);

        void startWithChanges(String grindId);

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
    JLabel runningNet = label("", Kit.body(), TEXT);
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
        JButton add = button("+ Add Grind", false, actions::addGrind);
        JButton more = button("\u00b7\u00b7\u00b7", false, () -> {});
        more.setToolTipText("Grinds options");
        more.getAccessibleContext().setAccessibleName("Grinds options");
        more.addActionListener(e -> actions.openDataMenu(more));
        // Owner 2026-10-01: the same toolbar band as the Ledger; the controls live in the header.
        Kit.headerBand(bar, new JComponent[] {start, add}, new JComponent[] {more});
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
        var menu = new ArrayList<RowAction>();
        menu.add(new RowAction("Open in Ledger", () -> actions.openLedger(data.activeSessionId)));
        menu.add(new RowAction("Targets…", actions::openTargets));
        menu.add(
                data.activeGrindId.isEmpty()
                        ? new RowAction(
                                "Save This Grind",
                                () -> actions.saveThisGrind(data.activeSessionId))
                        : new RowAction(
                                "Update Saved Grind",
                                () ->
                                        actions.updateSavedGrind(
                                                data.activeGrindId, data.activeSessionId)));
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
        running.setComponentPopupMenu(popup(menu));
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
            rows.add(
                    new Row(
                            id,
                            null,
                            (def.favorite ? "★ " : "") + def.getName(),
                            grind.linkedCount == 0
                                    ? "NEW"
                                    : grind.linkedCount
                                            + (grind.linkedCount == 1 ? " run" : " runs"),
                            grind.hasLast ? signed(grind.lastNet) : "—",
                            grind.hasLast ? sign(grind.lastNet) : DIM,
                            def.getName()
                                    + " · "
                                    + targetsLine(
                                            def.getNetTargetGp(), def.getActiveTimeTargetMillis()),
                            grind.hasLast ? "Last run " + exactSigned(grind.lastNet) + " gp" : "",
                            isSelected,
                            () -> select(isSelected ? null : id),
                            rowMenu(grindMenu(grind))));
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
        JButton more = button("···", false, () -> {});
        more.setToolTipText("More for this Grind");
        more.addActionListener(e -> popup(grindMenu(grind)).show(more, 0, more.getHeight()));
        buttons.add(more);
        selectedPanel.add(buttons);
    }

    List<RowAction> grindMenu(MyGrind grind) {
        SavedGrind def = grind.definition;
        String id = def.getGrindId();
        return Arrays.asList(
                new RowAction("Start", () -> actions.startGrind(id, null)),
                new RowAction(msg("start-changes"), () -> actions.startWithChanges(id)),
                new RowAction("Edit…", () -> actions.editSavedGrind(id)),
                new RowAction(
                        def.archived ? "Unarchive" : "Archive",
                        () -> actions.archiveSavedGrind(id, !def.archived)),
                new RowAction("Delete…", () -> actions.deleteSavedGrind(id)));
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
        var strip = new ArrayList<Cell>();
        strip.add(
                new Cell(
                        "GAINS",
                        signed(detail.revenue),
                        GAIN,
                        exact(detail.revenue) + " gp",
                        null));
        if (detail.splitAvailable) {
            strip.add(
                    new Cell(
                            "SUPPLY",
                            signed(-detail.supplies),
                            SUPPLY,
                            exact(detail.supplies) + " gp",
                            null));
            strip.add(
                    new Cell(
                            "LOSS",
                            signed(-detail.losses),
                            LOSS,
                            exact(detail.losses) + " gp",
                            null));
        } else {
            long costs = detail.revenue - detail.net;
            strip.add(new Cell("COSTS", signed(-costs), LOSS, msg("supply-loss-split"), null));
        }
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
        var menu = new ArrayList<RowAction>();
        menu.add(
                detail.linkedGrindId.isEmpty()
                        ? new RowAction(
                                "Save This Grind", () -> actions.saveThisGrind(detail.sessionId))
                        : new RowAction(
                                "Update Saved Grind",
                                () ->
                                        actions.updateSavedGrind(
                                                detail.linkedGrindId, detail.sessionId)));
        if (detail.closed) {
            menu.add(new RowAction("Delete…", () -> actions.deleteHistorical(detail.sessionId)));
        }
        JButton more = button("···", false, () -> {});
        more.addActionListener(e -> popup(menu).show(more, 0, more.getHeight()));
        buttons.add(more);
        this.detail.add(buttons);
        this.detail.add(note(msg("open-ledger"), LABEL));
    }

    /** A menu entry usable both as a row's right-click menu and a ⋯ popup. */
    @AllArgsConstructor
    static class RowAction {
        String label;
        Runnable run;
    }

    static List<Menu> rowMenu(List<RowAction> items) {
        var menu = new ArrayList<Menu>();
        for (RowAction item : items) menu.add(new Menu(item.label, item.run));
        return menu;
    }

    static JPopupMenu popup(List<RowAction> items) {
        return Kit.menuOf(rowMenu(items));
    }

    // ---- text ---------------------------------------------------------------------------------
    /** Date and duration context: Today/Yesterday/date plus Active Time, never internal ids. */
    static String contextLine(Recent recent, long now) {
        return contextLine(recent, now, ZoneId.systemDefault());
    }

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
        if (netTargetGp != null) parts.add(compact(netTargetGp) + " Net");
        if (activeTimeTargetMillis != null) parts.add(duration(activeTimeTargetMillis) + " active");
        return parts.isEmpty() ? "No targets" : String.join(" · ", parts);
    }
}
