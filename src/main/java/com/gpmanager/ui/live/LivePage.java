package com.gpmanager;


import java.awt.geom.Path2D;
import java.awt.geom.Arc2D;
import java.awt.geom.Line2D;

import static com.gpmanager.Fmt.*;
import static com.gpmanager.GameData.msg;
import static com.gpmanager.Kit.*;
import static com.gpmanager.Kit.Tone.*;

import static java.util.Collections.*;

import com.gpmanager.Hero.Cell;
import com.gpmanager.Kit.Tone;
import com.gpmanager.LedgerData.*;
import com.gpmanager.LiveSnapshot.Recent;
import com.gpmanager.Table.Row;

import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.grandexchange.GrandExchangePlugin;
import net.runelite.client.ui.components.ThinProgressBar;
import net.runelite.client.util.ImageUtil;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.function.Function;

import javax.swing.*;

/**
 * Live = what is happening now (SIDEBAR_SPEC.md §3): the hero band, the target card with pace, and
 * the plain Recent list and optional category view. Components are retained between ticks so hover
 * and menus survive. The page renders a {@link LiveSnapshot} and holds no engine.
 */
class LivePage implements Shell.Page {
    interface Actions {
        void togglePause();

        void openLedger(Entry entry);

        default void openCategory(Semantics.Table table) {}

        void editTarget();

        /** Owner live 2026-10-13: the completed row restarts the aim or clears it. */
        default void againTarget() {}

        default void doneTarget() {}

        /** Owner live 2026-10-14: the timer's own pause, distinct from the session pause. */
        default void toggleTimerPause() {}

        /** The ACTIVE clock: choose a rate-only restart or a new session; keep the whole run. */
        default void newSession() {}

        /** The run total under the Net: Live shows the whole run again (owner 1.5.2). */
        default void showWholeRun() {}

        default void hideRecent(String name, boolean supply) {}

        /** Clicking the running Grind's name renames it. */
        default void renameGrind() {}

        /** True when gameplay alone starts tracking; otherwise use the Grinds tab. */
        default boolean autoStartArmed() {
            return true;
        }
    }

    Actions actions;
    Function<Integer, BufferedImage> sprites;
    Map<Integer, ImageIcon> icons = new HashMap<>();

    /** The hero Net pair keeps its spaces up to this many characters (owner live 2026-10-14). */
    static final int HERO_PAIR_SPACED_MAX = 12;
    JPanel body = stack(PANEL, GAP);
    Hero hero = new Hero();
    JPanel target = new JPanel(new BorderLayout(0, 3));
    JLabel targetTitle = label("", small(), LABEL), targetValue = label("", numbers(12f), TEXT);
    JLabel targetPace = label("", small(), LABEL), targetTime = label("", small(), LABEL);

    /** Under the Net while Live counts fresh: the run's whole total, "Free play +420M". */
    JLabel runTotal = label("", small(), LABEL);

    JLabel risk = label("", small(), LOSS.color);
    JPanel targetActions = transparent(new FlowLayout(FlowLayout.LEFT, 3, 0));
    /** The header's right cluster: the aim icons, single row (owner live 2026-10-14). */
    JPanel targetTopActions = transparent(new FlowLayout(FlowLayout.RIGHT, 3, 0));
    /** The last laid-out button shape; the rows rebuild only when it changes. */
    Object actionsShape;
    JButton againButton, doneButton, editButton, pauseButton;
    ThinProgressBar targetBar = new ThinProgressBar();

    /**
     * GE offers still on the exchange; shown only while there are some, then they move to Recent.
     */
    Table offers = new Table("OFFERS").setFoldable(true);

    Table recent = new Table("RECENT").plain();
    JPanel breakdown = stack(PANEL, GAP);

    /** Pending counts as small icons: loot keys, GE offers, grave reclaim. */
    JPanel counts = transparent(new FlowLayout(FlowLayout.RIGHT, 4, 0));
    JPanel signals = transparent(new FlowLayout(FlowLayout.RIGHT, 4, 0));

    ImageIcon geIcon = icon(() -> ImageUtil.loadImageResource(GrandExchangePlugin.class, "ge_icon.png"));
    ImageIcon graveIcon = new ImageIcon(grave(LOSS.color));
    String drawnCounts = "";

    /** The RECENT empty sentence currently set; only a change forces a table rebuild. */
    String emptyRecent = "Nothing booked yet";

    LivePage(Actions actions, Function<Integer, BufferedImage> sprites) {
        this.actions = actions;
        this.sprites = sprites == null ? id -> null : sprites;
        signals.add(hero.icons);
        signals.add(counts);
        Hero.clickable(hero.clock, "Clock controls", actions::newSession);
        Hero.clickable(hero.name, "Rename Grind", actions::renameGrind);
        // Clicking the Net total sets or changes the target (owner 2026-09-28: no Target button).
        Hero.clickable(hero.net, "Set a Target", actions::editTarget);
        Hero.clickable(runTotal, "Save session or show run", actions::showWholeRun);
        risk.setToolTipText(msg("what-death"));
        hero.under.add(runTotal);
        hero.under.add(risk);
        target.setBackground(CARD);
        pad(target, 4, 6, 5, 6);
        // Owner live 2026-10-14: Pause and Done ride the header's right; Again / Reset / Edit sit
        // under the card. Built once; only visibility follows the aim.
        // Icon buttons keep the card one row tall; the tooltips carry the words (owner live
        // 2026-10-14). Their homes are rebuilt per state (see applyTarget): the bottom row
        // carries the completed action set; while running the icons ride the header's right.
        againButton = button("\u21BB", false, actions::againTarget);
        doneButton = button("\u2713", false, actions::doneTarget);
        editButton = button("\u2026", false, actions::editTarget);
        pauseButton = button("||", false, actions::toggleTimerPause);
        againButton.setToolTipText("Again: a new session with the same target");
        doneButton.setToolTipText("Done: clear the goal");
        editButton.setToolTipText("Edit targets");
        pauseButton.setToolTipText("Pause or resume the timer");
        var head = transparent(new BorderLayout());
        head.add(targetTitle, BorderLayout.CENTER);
        head.add(targetTopActions, BorderLayout.EAST);
        target.add(head, BorderLayout.NORTH);
        var values = stack(CARD, 1);
        values.add(targetValue);
        values.add(targetTime);
        var line = transparent(new BorderLayout(0, 2));
        line.add(values, BorderLayout.NORTH);
        targetBar.setMaximumValue(1000);
        targetBar.setBackground(LINE);
        line.add(targetBar, BorderLayout.CENTER);
        line.add(targetPace, BorderLayout.SOUTH);
        target.add(line, BorderLayout.CENTER);
        target.add(targetActions, BorderLayout.SOUTH);
        // A set target is edited by clicking its card.
        target.setToolTipText(msg("click-change"));
        Hero.clickable(target, "Edit target", actions::editTarget);
        recent.setEmptyText("Nothing booked yet");
        breakdown.setVisible(false);
        pad(body, 0, GAP, GAP, 0);
        for (var part : new JComponent[] {signals, hero, target, offers, recent, breakdown}) body.add(part);
    }

    public String id() {
        return Shell.LIVE;
    }

    public JComponent pageBar() {
        return null;
    }

    public JComponent body() {
        return body;
    }

    void category(LedgerPage page) {
        recent.setVisible(page == null);
        breakdown.setVisible(page != null);
        if (page != null && breakdown.getComponentCount() == 0) {
            breakdown.add(page.body);
        }
    }

    /** A new account starts each Live table on its first page. */
    void clearOwner() {
        recent.resetPage();
        offers.resetPage();
        category(null);
    }

    void apply(LiveSnapshot snapshot) {
        boolean loggedIn = !snapshot.loggedOut;
        counts(snapshot);
        hero.icons(statusIcons(snapshot));
        signals.setVisible(hero.icons.getComponentCount() + counts.getComponentCount() > 0);
        hero.status(dotColor(snapshot), statusTooltip(snapshot), identityOf(snapshot), wordOf(snapshot),
                loggedIn ? actions::togglePause : null);
        // Owner 1.5.2: Risk lives off the header, so a long name keeps its room.
        runTotal.setVisible(snapshot.fresh);
        // Clicking it shows the whole run again; a running target keeps its own session.
        runTotal.setToolTipText((snapshot.goal == null ? msg("run-total") : msg("run-total-target"))
                        + (snapshot.milestone == null ? "" : " Milestone " + compact(snapshot.milestone) + ": "
                                        + percent(Math.max(0d, Math.min(1d, (double) snapshot.runNet / snapshot.milestone)))
                                        + "."));
        runTotal.setText((snapshot.freePlay ? "Free play " : "Grind ") + signed(snapshot.runNet));
        risk.setVisible(snapshot.pvpPossible && snapshot.risk > 0);
        risk.setText("RISK " + compact(snapshot.risk));
        hero.under.setVisible(runTotal.isVisible() || risk.isVisible());
        // Nothing booked yet reads as a dash, never the font's slashed zero.
        boolean booked = snapshot.fresh || snapshot.gains != 0 || snapshot.costs != 0 || snapshot.marketResult != 0
                        || !snapshot.recent.isEmpty();
        if (snapshot.hasSession) {
            // Owner live 2026-10-13: with a Net target the hero carries the live pair
            // ("+38.0k / 200k"), so the target card never repeats the current Net; the pair counts
            // what the aim has earned since it was set (owner live 2026-10-14) and falls back to
            // the tight form when the spaced one cannot fit the hero line.
            String heroNet = signed(snapshot.net);
            if (booked && snapshot.goal != null && !snapshot.goal.time) {
                // Owner live 2026-10-14: the pair is spaced while it fits the hero line; long
                // values fall back to the tight form so it never clips.
                String spaced = signed(snapshot.goal.current) + " / " + snapshot.goal.label;
                heroNet = spaced.length() <= HERO_PAIR_SPACED_MAX ? spaced
                                : signed(snapshot.goal.current) + "/" + snapshot.goal.label;
            }
            hero.big(booked ? heroNet : "—",
                    // The pair's colour follows the number it shows (owner live 2026-10-14).
                    booked ? (snapshot.goal != null && !snapshot.goal.time ? sign(snapshot.goal.current)
                                    : sign(snapshot.net)) : DIM, exactSigned(snapshot.net) + " gp · gains "
                            + exact(snapshot.gains) + (snapshot.costSplitAvailable ? " · supplies "
                                            + exact(snapshot.supplies) + " · losses " + exact(snapshot.loss)
                                    : " · costs " + exact(snapshot.costs) + msg("split-unavailable"))
                            + (snapshot.goal == null ? msg("click-set") : snapshot.goal.time ? "" : " · aim "
                                                    + exactSigned(snapshot.goal.current) + " of "
                                                    + exact(snapshot.goal.target) + " gp"),
                    rateText(snapshot.rateEstablished, snapshot.gpPerHour), snapshot.rateEstablished,
                    clock(snapshot.elapsedMillis) + " ACTIVE");
        } else {
            hero.big("—", DIM, msg("nothing-tracked"), "—", false, "");
        }
        // Owner 2026-10-01 (F12): the hero names the rate basis and the tracked-time policy.
        hero.rate.setToolTipText(snapshot.rateEstablished ? msg("gp-h") : null);
        hero.clock.setToolTipText(snapshot.hasSession ? msg("tracked-active") : null);
        hero.strips(strips(snapshot));
        applyTarget(snapshot);
        var rows = new ArrayList<Row>();
        var open = new ArrayList<Row>();
        long openTotal = 0, recentTotal = 0;
        for (var row : snapshot.recent)
            if (row.open) {
                open.add(rowOf(row));
                openTotal += row.value;
            } else {
                rows.add(rowOf(row));
                recentTotal += row.net();
            }
        offers.setTitle("OFFERS · " + open.size()).setRows(open).setTotal(signed(openTotal), null);
        offers.setVisible(!open.isEmpty());
        // Owner 2026-10-01 (F13): the empty card says which event starts tracking, never a fake
        // start.
        String empty = snapshot.loggedOut ? msg("tracking-waits") : !snapshot.freePlay ? "Nothing booked yet"
                                : actions.autoStartArmed() ? msg("tracking-starts") : msg("press-grind");
        if (!empty.equals(emptyRecent)) {
            emptyRecent = empty;
            recent.setEmptyText(empty);
        }
        recent.countedTitle("RECENT", rows);
        recent.setTotal(rows.isEmpty() ? "" : signed(recentTotal), sign(recentTotal));
    }

    void applyTarget(LiveSnapshot snapshot) {
        var goal = snapshot.goal;
        hero.name.setFocusable(!snapshot.freePlay && snapshot.hasSession);
        hero.name.setToolTipText(snapshot.freePlay ? null : "Click to rename"
                                + (identityOf(snapshot).equals(hero.name.getText()) ? ""
                                        : " \u00b7 " + identityOf(snapshot)));
        target.setVisible(goal != null);
        if (goal == null) return;
        String title;
        if (goal.time) {
            // Owner 2026-10-01 (F10): a time target reads tracked Active Time, not fabricated Net.
            title = goal.reached ? "TARGET · reached" : "TARGET";
            targetValue.setText(goal.timeDetail);
            targetValue.setToolTipText(duration(goal.target) + " of active play");
            targetValue.setVisible(true);
            targetTime.setVisible(false);
        } else {
            title = "TARGET · " + goal.label + " · " + goal.progress;
            // The hero owns the live Net pair; the card never repeats it (owner live 2026-10-13).
            targetValue.setText("");
            targetValue.setToolTipText(null);
            targetValue.setVisible(false);
            boolean hasTime = goal.timeDetail != null;
            targetTime.setText(hasTime ? goal.timeDetail : "");
            targetTime.setVisible(hasTime);
        }
        targetBar.setValue((int) Math.round(goal.fraction * 1000));
        targetBar.setForeground(goal.reached ? GAIN.color : ACCENT);
        // A time target's line already counts down (owner 1.5.2: said once); a Net aim's pace line
        // carries its own wording, or waits for the rate.
        String pace = goal.eta;
        targetPace.setText(pace == null ? msg("pace-once") : pace);
        // Button layout (owner live 2026-10-14): while the aim runs its icons sit on the header's
        // right with no bottom row; a completed aim lays them along the bottom. Pause is the timer's own —
        // the run keeps tracking.
        boolean completed = goal.reached || goal.timeDone, timerPaused = goal.timerPaused;
        boolean timerSet = goal.timeDetail != null;
        // Pause needs a timer that can still run; an ended one has nothing to stop. Done and Again
        // are always there: Again is a new session with the same target (owner 1.5.2).
        pauseButton.setVisible(timerSet && !goal.timeDone);
        pauseButton.setText(timerPaused ? "\u25B6" : "||");
        var shown = new ArrayList<JButton>();
        for (var action : completed ? new JButton[] {againButton, doneButton, editButton, pauseButton}
                        : new JButton[] {pauseButton, againButton, doneButton, editButton}) {
            if (action.isVisible()) shown.add(action);
        }
        List<Object> shape = List.of(completed, shown);
        if (!shape.equals(actionsShape)) {
            actionsShape = shape;
            targetActions.removeAll();
            targetTopActions.removeAll();
            for (var action : shown) (completed ? targetActions : targetTopActions).add(action);
            // No bottom row while the run goes on: the header cluster carries it.
            targetActions.setVisible(completed);
            targetTopActions.setVisible(!completed && !shown.isEmpty());
            target.revalidate();
        }
        targetPace.setVisible(goal.eta != null || !goal.reached && goal.timeDetail == null);
        // The title shrinks before it is cut beside the icons; hover keeps it whole (owner 1.5.2).
        int room = target.getWidth() - 14
                        - (targetTopActions.isVisible() ? targetTopActions.getPreferredSize().width : 0);
        String fitted = fitShrinking(targetTitle, title, small(), room);
        // Still too long: the card says it is a target, so the label and progress go first.
        if (!fitted.equals(title))
            fitted = fitShrinking(targetTitle, title.replace("TARGET · ", ""), small(), room);
        targetTitle.setText(fitted);
        targetTitle.setToolTipText(title);
    }

    Row rowOf(Recent row) {
        var icon = Kit.icon(icons, sprites, row.itemId);
        Entry entry = entryFor(row);
        var menu = new ArrayList<Table.Menu>();
        menu.add(new Table.Menu("Open in Ledger", () -> actions.openLedger(entry)));
        // A finished trade is one item too (owner 2026-10-08: a sold Fire rune had no Hide).
        if (!row.open && row.itemId > 0 && (!row.composite || row.market))
            menu.add(new Table.Menu("Hide item from recent", () -> actions.hideRecent(row.name, false)));
        if (!row.open && row.composite && !row.market && (row.itemId < -1 || "Cast".equals(row.actionLabel)))
            menu.add(new Table.Menu("Hide spell from recent", () -> actions.hideRecent(row.name, false)));
        // Hiding a supply's spends leaves acquisitions visible; the item hide applies to both.
        if (!row.open && row.itemId > 0 && row.supplyRow)
            menu.add(new Table.Menu("Hide supply from recent", () -> actions.hideRecent(row.name, true)));
        // Ore a failed smelt lost sits beside the ore the bars used: say which is which.
        String name = msg("failed").equals(row.actionLabel) ? row.name + " (failed)" : row.name;
        Row drawn = new Row(row.contributionId + "|" + row.receiptId, icon, name, row.qty, valueText(row), toneOf(row),
                tipOf(row), numeric(row) ? exactSigned(row.value) + " gp" : "", false, () -> actions.openLedger(entry),
                menu);
        drawn.color = row.highlight;
        return drawn;
    }

    List<List<Cell>> strips(LiveSnapshot snapshot) {
        if (!snapshot.hasSession) {
            return singletonList(Arrays.asList(new Cell("GAINS", "—", DIM, "", null),
                            new Cell("SUPPLY", "—", DIM, "", null), new Cell("LOSS", "—", DIM, "", null)));
        }
        Runnable ledger = () -> actions.openLedger(Entry.current());
        var money = Hero.money(snapshot.gains, snapshot.supplies, snapshot.loss, snapshot.costs,
                        snapshot.costSplitAvailable, ledger);
        if (snapshot.marketResult != 0 || snapshot.marketPending > 0) {
            money.add(new Cell("TRADE", snapshot.marketResult == 0 ? "open" : signed(snapshot.marketResult), MARKET,
                            msg("realized-market"), ledger));
        }
        for (Cell cell : money)
            for (var table : Semantics.Table.values())
                if (table.label.equals(cell.label)) cell.open = () -> actions.openCategory(table);
        var rows = new ArrayList<List<Cell>>(singletonList(money));
        // Owner live 2026-10-13: skull and protect states are not shown (immersion); a PvP Grind
        // keeps Best kill and Streak. Risk sits beside the Review badge (owner 1.5.1: alone it
        // took a whole row).
        if (snapshot.pvpSession) {
            var pvp = new ArrayList<Cell>();
            pvp.add(new Cell("BEST KILL", snapshot.bestKill == 0 ? "—" : signed(snapshot.bestKill), GAIN,
                                        snapshot.kills + " kills · " + snapshot.deaths + " deaths", null).weight(3));
            pvp.add(new Cell("STREAK", String.valueOf(snapshot.streak), snapshot.streak > 0 ? GAIN : DIM,
                                    msg("kills-since") + snapshot.bestStreak + " this Grind", null).weight(2));
            rows.add(pvp);
        }
        return rows;
    }

    List<Cell> statusIcons(LiveSnapshot snapshot) {
        var list = new ArrayList<Cell>();
        if (snapshot.reviewCount > 0) {
            list.add(new Cell("Review", "(!)" + snapshot.reviewCount, REVIEW, snapshot.reviewCount == 1 ? msg("item-needs")
                                    : snapshot.reviewCount + msg("items-need"),
                            () -> actions.openLedger(Entry.current(Audit.REVIEW, null))));
        }
        return list;
    }

    /** Rebuilds the count icons only when a count changes, so hover survives a tick. */
    void counts(LiveSnapshot snapshot) {
        String key = snapshot.pendingKeys + "|" + snapshot.pendingKeyValue + "|" + snapshot.marketPending + "|"
                        + snapshot.reclaimItems;
        if (key.equals(drawnCounts)) return;
        drawnCounts = key;
        var previous = focused(counts);
        counts.removeAll();
        Runnable pending = () -> actions.openLedger(Entry.current());
        var keyImage = sprites.apply(ItemID.WILDY_LOOT_KEY0);
        String keyTip = msg("loot-keys") + snapshot.pendingKeys + (snapshot.pendingKeyValue > 0
                                ? msg("loot-keys-worth") + compact(snapshot.pendingKeyValue) : "");
        count(snapshot.pendingKeys, keyImage == null ? null : new ImageIcon(keyImage), "key", DIM, keyTip, pending);
        count(snapshot.marketPending, geIcon, "GE", MARKET, msg("grand-exchange-offers") + snapshot.marketPending,
                pending);
        count(snapshot.reclaimItems, graveIcon, "grave", LOSS,
                msg("items-waiting-reclaimed-after") + snapshot.reclaimItems, pending);
        counts.setVisible(counts.getComponentCount() > 0);
        counts.revalidate();
        restoreFocus(counts, previous);
        counts.repaint();
    }

    void count(long n, ImageIcon image, String fallback, Tone tone, String tip, Runnable open) {
        if (n <= 0) return;
        // A borderless button, not a mouse listener: the safety audit bans mouse-event names.
        var label = new JButton(image == null ? fallback + " " + n : String.valueOf(n));
        label.setFont(small());
        label.setForeground(tone.color);
        label.setIcon(image == null || image.getIconHeight() <= 12 ? image : new ImageIcon(
                                image.getImage().getScaledInstance(-1, 12, Image.SCALE_SMOOTH)));
        label.setIconTextGap(2);
        label.setToolTipText(tip);
        label.setBorder(null);
        label.setBorderPainted(true);
        label.setContentAreaFilled(false);
        label.setFocusPainted(false);
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        focus(label);
        label.setName(fallback);
        label.getAccessibleContext().setAccessibleName("Open " + fallback + " details");
        label.addActionListener(e -> open.run());
        counts.add(label);
    }

    static ImageIcon icon(java.util.function.Supplier<BufferedImage> load) {
        try {
            var image = load.get();
            return image == null ? null : new ImageIcon(image);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** The design's 11px outline headstone with a cross; RuneLite ships no grave icon. */
    static BufferedImage grave(Color color) {
        var image = new BufferedImage(11, 11, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(color);
        g.setStroke(new BasicStroke(1.2f));
        var stone = new Path2D.Float();
        stone.moveTo(2.5f, 10f);
        stone.lineTo(2.5f, 4.5f);
        stone.append(new Arc2D.Float(2.5f, 1.5f, 6f, 6f, 180f, -180f, Arc2D.OPEN), true);
        stone.lineTo(8.5f, 10f);
        stone.closePath();
        g.draw(stone);
        g.draw(new Line2D.Float(5.5f, 4f, 5.5f, 7.5f));
        g.draw(new Line2D.Float(4f, 5.5f, 7f, 5.5f));
        g.dispose();
        return image;
    }

    static Color dotColor(LiveSnapshot snapshot) {
        if (snapshot.loggedOut) return OFF;
        if (snapshot.paused && !snapshot.idle) return REVIEW.color;
        if (!snapshot.hasSession || snapshot.idle) return OFF;
        return GAIN.color;
    }

    static Tone toneOf(Recent row) {
        if ("review".equals(row.tag)) return REVIEW;
        if (!numeric(row)) return DIM;
        if (row.market) return MARKET;
        if (row.value >= 0) return GAIN;
        return row.ledgerCostView == CostView.LOSS ? LOSS : SUPPLY;
    }

    static boolean numeric(Recent row) {
        return !row.neutral && !row.unpriced && !"canceled".equals(row.tag)
                && (!row.market || row.marketSettlementValue || row.valueAvailable);
    }

    /** "1.93M/h" once the rate is established; never 0/h before that. */
    static String rateText(boolean established, long gpPerHour) {
        return established ? rate(gpPerHour) + "/h" : "Calculating…";
    }

    static String tipOf(Recent row) {
        var tip = new StringBuilder(row.name).append(" · ").append(metaOf(row));
        if ("review".equals(row.tag)) tip.append(" · needs review");
        if (row.unpriced) tip.append(msg("value-incomplete"));
        if (row.market && !row.valueAvailable && !row.marketSettlementValue)
            tip.append(msg("market-result"));
        if (row.market && row.marketSettlementValue)
            tip.append(row.valueAvailable ? msg("primary-value") : msg("observed-settlement-result"));
        if (row.receipts > 1 && !metaOf(row).contains("entries"))
            tip.append(" · ").append(row.receipts).append(" entries");
        return tip.toString();
    }

    /** The right-side value: settled cash is available independently of Result. */
    static String valueText(Recent row) {
        if (row.neutral) return "\u2014";
        if (row.unpriced) return "?";
        if ("canceled".equals(row.tag)) return "Canceled";
        if (row.market && !row.marketSettlementValue && !row.valueAvailable)
            return "review".equals(row.tag) ? "?" : row.marketSide.isEmpty() ? "Pending" : row.marketSide;
        return row.market ? signed(row.value) : worth(row.value);
    }

    /** What the row is: its state for review / unpriced rows, else action and quantity. */
    static String metaOf(Recent row) {
        if ("review".equals(row.tag)) return "Needs review";
        if ("unpriced".equals(row.tag)) return "Unpriced";
        String qty = row.qty == null ? "" : " · " + row.qty;
        if (!row.market && (row.actionLabel.isEmpty() || (row.composite && row.name.equalsIgnoreCase(row.actionLabel))))
            return Phrases.orEmpty(row.qty);
        return row.actionLabel + qty;
    }

    /** Stable receipt identity drives Live navigation; search text stays a user-owned filter. */
    static Entry entryFor(Recent row) {
        return new Entry(LedgerData.Scope.CURRENT_GRIND, null, null,
                // The Ledger always opens its Losses view on All (owner live 2026-10-13); the
                // row's own cost view still colours the tray and its menus.
                LedgerData.CostView.ALL, "", row.receiptId, row.contributionId, null,
                row.ledgerReview ? Audit.REVIEW : null);
    }

    /** The small status word, only when something is off: tracking itself is the green dot. */
    static String wordOf(LiveSnapshot snapshot) {
        if (snapshot.hopping) return "RECONNECTING";
        if (snapshot.loggedOut) return "LOGGED OUT";
        if (!snapshot.hasSession && snapshot.resumeInSeconds > 0)
            return "RESUMING " + snapshot.resumeInSeconds + "s";
        if (!snapshot.hasSession) return "";
        if (snapshot.paused && !snapshot.idle) return "PAUSED";
        if (snapshot.idle) return "AWAY";
        return snapshot.calibrating ? "CALIBRATING" : "";
    }

    /**
     * The status line names the run: a named Grind shows its name; free play shows the detected
     * activity label when there is one ("Trade"), blank otherwise (owner live 2026-10-14; the
     * 2026-10-13 retirement kept activity noise out of named runs).
     */
    static String identityOf(LiveSnapshot snapshot) {
        return activity(snapshot.freePlay ? snapshot.activityLabel : snapshot.ownerLabel);
    }

    static String statusTooltip(LiveSnapshot snapshot) {
        String wording = snapshot.hopping ? "Reconnecting" : snapshot.loggedOut ? msg("logged-out")
                                : !snapshot.hasSession ? msg("tracking-click") : snapshot.paused && !snapshot.idle
                                                ? msg("tracking-paused-click") : snapshot.idle ? msg("waiting-activity")
                                                        : msg("live-tracking");
        var tip = new StringBuilder(wording);
        if (snapshot.hasSession) tip.append(" · active ").append(duration(snapshot.elapsedMillis));
        return tip.toString();
    }
}
