package com.gpmanager;

import static com.gpmanager.Fmt.*;
import static com.gpmanager.GameData.msg;
import static com.gpmanager.Kit.*;

import com.gpmanager.LedgerData.Entry;
import com.gpmanager.SavedState.SavedGrind;

import javax.swing.*;

/** Grinds actions: start, save, edit, targets, history, export, backups and recovery. */
class GrindsController implements GrindsPage.Actions {
    SidebarPanel panel;
    Engine engine;
    Shell shell;
    SessionRepository repository;
    Persistence persistence;

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
                            ended == null ? "View recap" : signed(ended.net) + msg("view-recap"),
                            false,
                            "View recap",
                            () -> {
                                panel.grindsDetailId = endedId;
                                shell.show(Shell.GRINDS);
                                refresh();
                            });
                });
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

    /** Start a Grind through the switch guard, mutating the domain under it. */
    void startGrind(String name, String grindId, Long net, Long time) {
        whenSwitchAllowed(
                name,
                () ->
                        mutate(
                                () ->
                                        engine.startGrind(
                                                name, grindId, net, time, SafeMath.now())));
    }

    public void openTargets() {
        Session active = engine.getActiveSession();
        if (active == null) return;
        targetSheet(
                "Targets",
                msg("blank-clears"),
                grindForm(null, active.getProfitTargetGp(), active.getActiveTimeTargetMillis()),
                "Save",
                this::openTargets,
                (name, net, time) -> {
                    Long netTarget = GrindsData.applyNet(net, active.getProfitTargetGp());
                    Long timeTarget =
                            GrindsData.applyTime(time, active.getActiveTimeTargetMillis());
                    mutate(() -> engine.setTargets(netTarget, timeTarget, SafeMath.now()));
                });
    }

    /** Owner 2026-10-01 (F20): the run an action was opened on; never whatever is active now. */
    Session sessionFor(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return engine.getActiveSession();
        Session active = engine.getActiveSession();
        return active != null && sessionId.equals(active.getId())
                ? active
                : engine.historySession(sessionId);
    }

    public void saveThisGrind(String sessionId) {
        Session target = sessionFor(sessionId);
        if (target == null || (target == engine.getActiveSession() && !engine.isCustomActive()))
            return;
        Long net = target.getProfitTargetGp();
        Long time = target.getActiveTimeTargetMillis();
        String identity = target.getId();
        shell.prompt(
                "Save This Grind",
                "Grind name",
                target.getName(),
                cleanName ->
                        mutate(
                                () -> {
                                    // Re-validate after the prompt: the viewed run must still
                                    // resolve to the same identity.
                                    Session now = sessionFor(sessionId);
                                    if (now == null || !identity.equals(now.getId())) return;
                                    SavedGrind created =
                                            engine.saveGrind(cleanName, net, time, false, identity);
                                    if (created != null) {
                                        shell.tell(
                                                "Saved as My Grind",
                                                cleanName + msg("future-defaults"),
                                                false);
                                    }
                                }));
    }

    /**
     * The one Start Grind sheet, from Live and Grinds: a saved Grind starts in one click
     * (favourites first, with its targets); a new name starts once, unsaved (Add Grind saves one
     * for later). Owner 2026-10-01 (F22): the saved list is bounded to five rows with paging.
     */
    public void newGrind() {
        JPanel content = stack(CARD, 2);
        java.util.ArrayList<SavedGrind> saved =
                new java.util.ArrayList<>(engine.getSavedGrinds(false));
        saved.sort(java.util.Comparator.comparing(grind -> !grind.favorite));
        new Paged(
                        saved.size(),
                        (box, page) -> {
                            int from = Math.min(page * CHOICE_ROWS, saved.size());
                            for (SavedGrind grind :
                                    saved.subList(
                                            from, Math.min(from + CHOICE_ROWS, saved.size()))) {
                                JButton start =
                                        button(
                                                (grind.favorite ? "\u2605 " : "") + grind.getName(),
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
        addField(content, "nameField", saved.isEmpty() ? "Grind name" : "Or a new Grind", "");
        shell.sheet(
                "Start Grind",
                saved.isEmpty() ? "" : msg("pick-saved"),
                content,
                new Shell.Choice(
                        "Start",
                        true,
                        () -> {
                            String name = field(content, "nameField");
                            if (!name.isEmpty()) startGrind(null, name);
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

    public void addGrind() {
        shell.prompt(
                "Add Grind",
                "Grind name",
                "",
                name -> mutate(() -> engine.saveGrind(name, null, null, false, null)));
    }

    public void updateSavedGrind(String grindId, String sessionId) {
        SavedGrind definition = engine.savedGrind(grindId);
        Session target = sessionFor(sessionId);
        if (definition == null || target == null) return;
        Long net = target.getProfitTargetGp();
        Long time = target.getActiveTimeTargetMillis();
        String identity = target.getId();
        shell.confirm(
                "Update " + definition.getName(),
                msg("future-runs"),
                "Update",
                () ->
                        mutate(
                                () -> {
                                    // Re-validate after the confirmation: definition and viewed run
                                    // must still be the same.
                                    SavedGrind current = engine.savedGrind(grindId);
                                    Session now = sessionFor(sessionId);
                                    if (current == null
                                            || now == null
                                            || !identity.equals(now.getId())) return;
                                    engine.updateSavedGrind(
                                            grindId,
                                            current.getName(),
                                            net,
                                            time,
                                            current.favorite);
                                }));
    }

    public void editSavedGrind(String grindId) {
        SavedGrind definition = engine.savedGrind(grindId);
        if (definition == null) return;
        JPanel form =
                grindForm(
                        definition.getName(),
                        definition.getNetTargetGp(),
                        definition.getActiveTimeTargetMillis(),
                        definition.favorite);
        targetSheet(
                "Edit " + definition.getName(),
                msg("saved-defaults"),
                form,
                "Save",
                () -> editSavedGrind(grindId),
                (name, net, time) -> {
                    String cleanName = name.isEmpty() ? definition.getName() : name;
                    Long netTarget = GrindsData.applyNet(net, definition.getNetTargetGp());
                    Long timeTarget =
                            GrindsData.applyTime(time, definition.getActiveTimeTargetMillis());
                    // Owner 2026-10-01 (F21): the editor shows and saves the existing Favorite
                    // flag.
                    boolean favorite = flag(form, "favoriteField");
                    mutate(
                            () ->
                                    engine.updateSavedGrind(
                                            grindId, cleanName, netTarget, timeTarget, favorite));
                });
    }

    public void startWithChanges(String grindId) {
        SavedGrind definition = engine.savedGrind(grindId);
        if (definition == null) return;
        targetSheet(
                "Start with changes",
                msg("run-saved"),
                startWithChangesForm(definition),
                "Start",
                () -> startWithChanges(grindId),
                (typed, net, time) -> {
                    String name = typed.isEmpty() ? definition.getName() : typed;
                    Long netTarget = GrindsData.applyNet(net, null);
                    Long timeTarget = GrindsData.applyTime(time, null);
                    String startGrindId = definition.getGrindId();
                    // One-off changes apply to this instance only; saved defaults stay untouched.
                    startGrind(name, startGrindId, netTarget, timeTarget);
                });
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

    public void startAgain(String sessionId) {
        Session session = sessionId == null ? null : engine.historySession(sessionId);
        if (session == null) {
            Session active = engine.getActiveSession();
            session = active != null && active.getId().equals(sessionId) ? active : null;
        }
        if (session == null) return;
        String name = session.getName();
        String linkedGrindId = session.getGrindId();
        Long net = session.getProfitTargetGp();
        Long time = session.getActiveTimeTargetMillis();
        startGrind(name, linkedGrindId, net, time);
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

    /**
     * The Start-with-changes form: name and the two targets; the saved definition is never edited.
     */
    JPanel startWithChangesForm(SavedGrind definition) {
        return grindForm(
                definition.getName(),
                definition.getNetTargetGp(),
                definition.getActiveTimeTargetMillis());
    }

    /**
     * Name (when given), the two target fields and, when editing a saved Grind, its Favorite flag.
     */
    static JPanel grindForm(String name, Long net, Long time) {
        return grindForm(name, net, time, null);
    }

    static JPanel grindForm(String name, Long net, Long time, Boolean favorite) {
        JPanel form = stack(CARD, 2);
        if (name != null) addField(form, "nameField", "Name", name);
        addField(form, "netField", msg("net-target"), net == null ? "" : String.valueOf(net));
        addField(
                form, "timeField", msg("active-time-target"), time == null ? "" : targetTime(time));
        if (favorite != null)
            addField(form, "favoriteField", "\u2605 Favorite", favorite.booleanValue());
        return form;
    }

    static void addField(JPanel form, String key, String label, String value) {
        JTextField field = new JTextField(value);
        form.add(label(label, small(), LABEL));
        form.add(field);
        form.putClientProperty(key, field);
    }

    static void addField(JPanel form, String key, String label, boolean selected) {
        JCheckBox box = new JCheckBox(label, selected);
        box.setOpaque(false);
        box.setFont(small());
        form.add(box);
        form.putClientProperty(key, box);
    }

    static boolean flag(JPanel form, String key) {
        Object box = form.getClientProperty(key);
        return box instanceof JCheckBox && ((JCheckBox) box).isSelected();
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
        String detail = outcome.isApplied() ? msg("fresh-profile") : outcome.getDetail();
        shell.tell(
                outcome.isApplied() ? "Profile reset" : "Reset refused",
                detail,
                !outcome.isApplied());
    }
}
