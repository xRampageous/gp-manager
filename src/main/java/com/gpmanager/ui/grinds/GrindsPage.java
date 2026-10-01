package com.gpmanager;
import com.gpmanager.Table.Menu;
import com.gpmanager.SavedState.SavedGrind;
import com.gpmanager.Hero.Cell;
import com.gpmanager.Kit.Tone;
import com.gpmanager.Table.*;
import com.gpmanager.GrindsData.*;
import java.awt.*;
import java.time.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import lombok.*;
import static com.gpmanager.Kit.Tone.*;
import static com.gpmanager.Kit.*;
import static com.gpmanager.Fmt.*;
import static com.gpmanager.GameData.msg;
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
void startGrind(String grindId, Long netOverride, Long timeOverride, String nameOverride);
void openTargets();
/** Saves the viewed run as a My Grind (F20): the id selects the run, never the active one. */
void saveThisGrind(String sessionId);
/** Opens the Start Grind sheet. */
void newGrind();
/** Saves a new, empty My Grind from a name. */
void addGrind();
/** Updates a saved Grind's defaults from the viewed run (F20): the session id names the run. */
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

final Actions actions;
final JPanel body = stack(PANEL, GAP);
final JPanel bar = new JPanel(new BorderLayout(4, 0));
final JPanel list = stack(PANEL, GAP);
final JPanel detail = stack(PANEL, GAP);
final JPanel running = new JPanel(new BorderLayout(GAP, 0));
final JLabel runningName = label("", bold(), TEXT);
final JLabel runningNet = label("", Kit.body(), TEXT);
final Table mine = new Table("MY GRINDS");
final JPanel selectedPanel = stack(CARD, 0);
final Table recent = new Table("RECENT GRINDS");
/** All time as one compact strip; hovering it gives the full breakdown. */
final JPanel allTime = stack(CARD, 0);
final Hero hero = new Hero();
GrindsData data;
String selectedId;
GrindsPage(Actions actions) {
this.actions = actions;
bar.setOpaque(false);
JButton start = button("Start Grind", true, actions::newGrind);
JButton add = button("+ Add Grind", false, actions::addGrind);
JButton more = button("\u00b7\u00b7\u00b7", false, () -> { });
more.setToolTipText("Grinds options");
more.getAccessibleContext().setAccessibleName("Grinds options");
more.addActionListener(e -> actions.openDataMenu(more));
// Owner 2026-10-01: the same toolbar band as the Ledger; the controls live in the header.
bar.add(Kit.toolbar(new JComponent[] {start, add}, new JComponent[] {more}), BorderLayout.NORTH);
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
end.setToolTipText(msg("q"));
runningButtons.add(live);
runningButtons.add(end);
running.add(runningButtons, BorderLayout.EAST);
mine.setEmptyText(msg("gx"));
recent.setEmptyText(msg("by")).setFill(true);
pad(selectedPanel, 0, 0, 4, 0);
// Owner 2026-09-28: My Grinds and Recent grinds first; All time closes the page.
for (JComponent part : new JComponent[] {running, mine, selectedPanel, recent, allTime}) list.add(part);
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
void applyRunning(GrindsData d) {
running.setVisible(d.hasActive);
if (!d.hasActive) return;
var menu = new ArrayList<Act>();
menu.add(new Act("Open in Ledger", () -> actions.openLedger(d.activeSessionId)));
menu.add(new Act("Targets…", actions::openTargets));
menu.add(d.activeGrindId.isEmpty() ? new Act("Save This Grind", () -> actions.saveThisGrind(d.activeSessionId))
: new Act("Update Saved Grind", () -> actions.updateSavedGrind(d.activeGrindId, d.activeSessionId)));
runningName.setText("<html><font color='#6ee16e'>●</font> " + d.activeName.replace("<", "&lt;") + "</html>");
runningNet.setText(signed(d.activeNet));
runningNet.setForeground(sign(d.activeNet).color);
running.setToolTipText(rateLine(d.activeRateEstablished, d.activeGpPerHour) + " · "
+ duration(d.activeMillis) + " active · " + targetsLine(d.activeNetTarget, d.activeTimeTarget));
runningNet.setToolTipText(exactSigned(d.activeNet) + " gp");
running.setComponentPopupMenu(popup(menu));
}

/** Every retained session, Free play included; bests are finished Grinds only. */
void applyAllTime(GrindsData.AllTime a) {
var tip = new StringBuilder("<html>All time · ").append(a.grinds).append(a.grinds == 1 ? " Grind" : " Grinds")
.append("<br>Net ").append(exactSigned(a.net)).append(" gp<br>Active ").append(duration(a.activeMillis));
if (a.splitAvailable) {
tip.append("<br>Supplies ").append(signed(-a.supplies)).append(" · Losses ").append(signed(-a.losses));
}
if (a.bestRateName != null) {
tip.append("<br>Best GP/h ").append(a.bestRateName).append(" · ").append(rate(a.bestRate));
}
String best = a.bestNetName == null ? "" : "Best · " + a.bestNetName + " " + signed(a.bestNet);
allTime.removeAll();
JLabel title = label("ALL TIME · " + a.grinds, small(), LABEL);
pad(title, 3, 6, 3, 6);
allTime.add(title);
allTime.add(Hero.strip(List.of(new Cell("NET", signed(a.net), sign(a.net), exactSigned(a.net) + " gp", null),
new Cell("GP/H", a.gpPerHour == null ? "—" : rate(a.gpPerHour), PLAIN, null, null),
new Cell("ACTIVE", durationCompact(a.activeMillis), PLAIN, duration(a.activeMillis), null))));
if (!best.isEmpty()) {
JLabel bestLine = label(best, small(), sign(a.bestNet).color);
pad(bestLine, 3, 6, 4, 6);
allTime.add(bestLine);
}
allTime.setToolTipText(tip.append("</html>").toString());
for (Component child : allTime.getComponents()) {
if (child instanceof JComponent && ((JComponent) child).getToolTipText() == null) {
((JComponent) child).setToolTipText(allTime.getToolTipText());
}
}
allTime.revalidate();
}

void applyMine(GrindsData d) {
var rows = new ArrayList<Row>();
MyGrind selected = null;
for (MyGrind grind : d.myGrinds) {
SavedGrind def = grind.definition;
String id = def.getGrindId();
boolean isSelected = id.equals(selectedId);
if (isSelected) selected = grind;
rows.add(new Row(id, null, (def.favorite ? "★ " : "") + def.getName(),
grind.linkedSessions == 0 ? "NEW" : grind.linkedSessions + (grind.linkedSessions == 1 ? " run" : " runs"),
grind.hasLast ? signed(grind.lastNet) : "—", grind.hasLast ? sign(grind.lastNet) : DIM,
def.getName() + " · " + targetsLine(def.getNetTargetGp(), def.getActiveTimeTargetMillis()),
grind.hasLast ? "Last run " + exactSigned(grind.lastNet) + " gp" : "", isSelected,
() -> select(isSelected ? null : id), rowMenu(grindMenu(grind))));
}
mine.setTitle(rows.isEmpty() ? "MY GRINDS" : "MY GRINDS · " + rows.size()).setRows(rows);
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
facts.put("Last run", grind.hasLast ? signed(grind.lastNet) + " · "
+ when(grind.lastStartedAt, System.currentTimeMillis()) : "—", grind.hasLast ? sign(grind.lastNet) : DIM);
facts.put("Best", grind.hasBest ? signed(grind.bestNet) : "—", grind.hasBest ? sign(grind.bestNet) : DIM);
facts.put("Target", targetsLine(def.getNetTargetGp(), def.getActiveTimeTargetMillis()), PLAIN);
selectedPanel.add(facts);
var buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
buttons.setOpaque(false);
// Owner 2026-10-01 (F14): the selected saved Grind starts right here, beside its menu.
JButton start = button("Start", true, () -> actions.startGrind(def.getGrindId(), null, null, null));
start.setToolTipText("Start " + def.getName());
buttons.add(start);
JButton more = button("···", false, () -> { });
more.setToolTipText("More for this Grind");
more.addActionListener(e -> popup(grindMenu(grind)).show(more, 0, more.getHeight()));
buttons.add(more);
selectedPanel.add(buttons);
}

List<Act> grindMenu(MyGrind grind) {
SavedGrind def = grind.definition;
String id = def.getGrindId();
return Arrays.asList(new Act("Start", () -> actions.startGrind(id, null, null, null)),
new Act("Start with changes…", () -> actions.startWithChanges(id)), new Act("Edit…", () -> actions.editSavedGrind(id)),
new Act(def.archived ? "Unarchive" : "Archive", () -> actions.archiveSavedGrind(id, !def.archived)),
new Act("Delete…", () -> actions.deleteSavedGrind(id)));
}

void applyRecent(GrindsData d) {
long now = System.currentTimeMillis();
var rows = new ArrayList<Row>();
for (Recent run : d.recent) {
String id = run.sessionId;
ZoneId zone = d.profileZone == null ? ZoneId.systemDefault() : d.profileZone;
String when = dayLabel(Instant.ofEpochMilli(run.startedAt).atZone(zone).toLocalDate(), now, zone);
rows.add(new Row(id, null, run.name, when, valueText(run.net),
sign(run.net), run.name + " · " + contextLine(run, now, d.profileZone),
exactSigned(run.net) + " gp", false, () -> actions.openDetail(id), Arrays.asList(
new Menu("Open in Ledger", () -> actions.openLedger(id)), new Menu("Start again", () -> actions.startAgain(id)),
new Menu("Delete…", () -> actions.deleteHistorical(id)))));
}
recent.setTitle(rows.isEmpty() ? "RECENT GRINDS" : "RECENT GRINDS · " + rows.size()).setRows(rows);
}

// ---- detail -------------------------------------------------------------------------------
void buildDetail(GrindsData d) {
GrindsData.Detail g = d.detail;
detail.removeAll();
JButton back = button("‹ Grinds", false, () -> actions.openDetail(null));
var top = new JPanel(new BorderLayout());
top.setOpaque(false);
top.add(back, BorderLayout.WEST);
top.add(label(when(g.startedAt, System.currentTimeMillis()).toUpperCase(Locale.ROOT),
small(), LABEL), BorderLayout.EAST);
pad(top, 0, 0, 0, 6);
detail.add(top);
hero.status(g.closed ? OFF : GAIN.color, g.closed ? "Finished" : "Running", g.name, g.closed ? "COMPLETE" : "", null);
hero.big(signed(g.net), sign(g.net), exactSigned(g.net) + " gp",
g.rateEstablished ? rate(g.gpPerHour) + "/h" : "—", g.rateEstablished, duration(g.activeMillis) + " ACTIVE");
// Owner 2026-10-01 (F12): a completed run's rate is the whole-run basis.
hero.rate.setToolTipText(g.rateEstablished ? "GP/h over active time" : null);
var strip = new ArrayList<Cell>();
strip.add(new Cell("GAINS", signed(g.revenue), GAIN, exact(g.revenue) + " gp", null));
if (g.splitAvailable) {
strip.add(new Cell("SUPPLY", signed(-g.supplies), SUPPLY, exact(g.supplies) + " gp", null));
strip.add(new Cell("LOSS", signed(-g.losses), LOSS, exact(g.losses) + " gp", null));
} else {
long costs = g.revenue - g.net;
strip.add(new Cell("COSTS", signed(-costs), LOSS, msg("ba"), null));
}
hero.strips(Collections.singletonList(strip));
detail.add(hero);
var outcome = new KeyValue("HIGHLIGHTS");
if (g.closed && g.history != null) highlights(outcome, g.history.recap);
outcome.put("Target", g.netOutcome(), PLAIN);
if (g.activeTimeTargetMillis != null) outcome.put("Time", g.timeOutcome(), PLAIN);
if (d.activePace != null && d.activePace.present && d.activePace.available && g.sessionId.equals(d.activeSessionId)) {
outcome.put("Pace", d.activePace.line, DIM);
// Owner 2026-10-01 (F10): a combined target also shows its required average.
if (d.activePace.second != null) outcome.put("", d.activePace.second, DIM);
}
detail.add(outcome);
if (g.closed && g.history != null && !g.history.recap.sources.isEmpty()) {
var sources = new KeyValue("LOOT BY SOURCE");
for (GrindHistory.Highlight source : g.history.recap.sources) {
sources.put(source.name, source.valueText(), GAIN);
}
detail.add(sources);
}
if (g.compacted) detail.add(note(msg("fs"), LABEL));
if (g.closed && g.history != null) buildRecap(g.history);
var buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
buttons.setOpaque(false);
buttons.add(button("Start again", true, () -> actions.startAgain(g.sessionId)));
buttons.add(button("Open in Ledger", false, () -> actions.openLedger(g.sessionId)));
var menu = new ArrayList<Act>();
menu.add(g.linkedGrindId.isEmpty() ? new Act("Save This Grind", () -> actions.saveThisGrind(g.sessionId))
: new Act("Update Saved Grind", () -> actions.updateSavedGrind(g.linkedGrindId, g.sessionId)));
if (g.closed) {
menu.add(new Act("Delete…", () -> actions.deleteHistorical(g.sessionId)));
}
JButton more = button("···", false, () -> { });
more.addActionListener(e -> popup(menu).show(more, 0, more.getHeight()));
buttons.add(more);
detail.add(buttons);
detail.add(note(msg("z"), LABEL));
}

static void highlights(KeyValue kv, GrindHistory.Recap recap) {
if (recap.highlightsUnavailable) kv.put(msg("bl"), "", DIM);
if (recap.biggestGain != null) kv.put(recap.biggestGain.name, recap.biggestGain.valueText(), GAIN);
if (recap.biggestCost != null) kv.put(recap.biggestCost.name, recap.biggestCost.valueText(), LOSS);
}

void buildRecap(GrindHistory history) {
GrindHistory.Comparison c = history.comparison;
var vs = new KeyValue("VS PREVIOUS RUN");
if (c.excluded) {
vs.put(msg("cj"), "", DIM);
} else if (!c.available) {
vs.put("No previous run yet", "", DIM);
} else {
vs.put("Net", signed(c.netDelta), sign(c.netDelta));
if (c.rateDelta != null) vs.put("GP/h", signed(c.rateDelta) + "/h", sign(c.rateDelta));
if (c.suppliesDelta != null) vs.put("Supplies", signed(c.suppliesDelta), SUPPLY);
// Time is not money: a shorter run is neither a gain nor a loss.
vs.put("Active time", deltaTime(c.activeDelta), PLAIN);
}
detail.add(vs);
GrindHistory.PBs pbs = history.pbs;
if (!pbs.excluded && pbs.bestNet != null) {
var best = new KeyValue("PERSONAL BESTS");
best.put("Best Net", pbText(pbs.newBestNet, pbs.matchesBestNet, signed(pbs.bestNet)), pbs.newBestNet ? GAIN : PLAIN);
if (pbs.bestGpPerHour != null) {
best.put("Best GP/h", pbText(pbs.newBestGpPerHour, pbs.matchesBestGpPerHour,
rate(pbs.bestGpPerHour) + "/h"), pbs.newBestGpPerHour ? GAIN : PLAIN);
}
detail.add(best);
}
}

/** A menu entry usable both as a row's right-click menu and a ⋯ popup. */
@AllArgsConstructor
static class Act {
final String label;
final Runnable run;
}

static List<Menu> rowMenu(List<Act> items) {
var menu = new ArrayList<Menu>();
for (Act item : items) menu.add(new Menu(item.label, item.run));
return menu;
}

static JPopupMenu popup(List<Act> items) {
var menu = new JPopupMenu();
for (Act item : items) {
var entry = new JMenuItem(item.label);
entry.addActionListener(e -> item.run.run());
menu.add(entry);
}
return menu;
}

// ---- text ---------------------------------------------------------------------------------
static String pbText(boolean newBest, boolean matches, String value) {
return newBest ? "NEW PB · " + value : matches ? "Matches PB · " + value : value;
}

/** Signed Active-Time delta in the compact duration language. */
static String deltaTime(long deltaMillis) {
if (deltaMillis == 0L) return "0m";
return (deltaMillis > 0L ? "+" : "−") + durationCompact(Math.abs(deltaMillis));
}

/** Date and duration context: Today/Yesterday/date plus Active Time, never internal ids. */
static String contextLine(Recent recent, long now) {
return contextLine(recent, now, ZoneId.systemDefault());
}

static String contextLine(Recent recent, long now, ZoneId zone) {
ZoneId z = zone == null ? ZoneId.systemDefault() : zone;
return dayLabel(Instant.ofEpochMilli(recent.startedAt).atZone(z).toLocalDate(), now, z)
+ " · " + durationCompact(recent.activeMillis);
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
