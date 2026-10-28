package com.gpmanager;

import java.util.List;
import java.util.function.LongSupplier;
import org.junit.*;
import static com.gpmanager.MilestoneTest.onEdt;
import static org.junit.Assert.*;

public class SavedSessionRenameTest {
    LongSupplier clock;
    @Before public void before() { clock = SafeMath.clock; JsonCodec.bind(new com.google.gson.Gson()); }
    @After public void after() { SafeMath.clock = clock; }

    static SidebarPanel panel() {
        Engine engine = SavedSessionTest.window();
        Session source = engine.session();
        EngineProbe.saveLiveSession(engine, source, source.sessionStartAt, 120_000L);
        SidebarPanel panel = SavedSessionTest.panel(engine);
        panel.shell.show(Shell.GRINDS); panel.refreshNow(); return panel;
    }

    @Test public void rowRenamePersistsWithoutChangingReceiptsClockOrTotals() throws Exception {
        onEdt(() -> {
            SidebarPanel panel = panel(); Engine engine = panel.engine;
            var saved = engine.saved.get(0); String id = saved.id;
            String receipts = JsonCodec.gson().toJson(saved.session.transactions());
            String markets = JsonCodec.gson().toJson(saved.market);
            long elapsed = saved.session.elapsed(120_000L), revision = engine.revision();
            assertEquals(List.of("Open in Ledger", "Copy summary", "Rename…", "Start again", "Delete…"),
                panel.plans.saved.rows.get(0).menu.stream().map(menu -> menu.label).collect(java.util.stream.Collectors.toList()));
            panel.plans.saved.rows.get(0).menu.stream().filter(menu -> menu.label.equals("Rename…")).findFirst().get().action.run();
            ShellProbe.typeSheet(panel.shell, "  Evening Vorkath  "); ShellProbe.pressSheet(panel.shell, "OK");
            assertEquals("Evening Vorkath", saved.session.name()); assertEquals(id, saved.id);
            assertEquals(receipts, JsonCodec.gson().toJson(saved.session.transactions()));
            assertEquals(markets, JsonCodec.gson().toJson(saved.market));
            assertEquals(elapsed, saved.session.elapsed(120_000L)); assertTrue(engine.revision() > revision);
            assertEquals(500L, saved.session.metrics(120_000L).net);
            assertEquals(1500L, GrindsData.allTime(engine, 120_000L).net);
            panel.refreshNow();
            assertEquals("Evening Vorkath", panel.plans.saved.rows.get(0).name);
            SavedState state = JsonCodec.gson().fromJson(JsonCodec.gson().toJson(engine.exportState()), SavedState.class);
            Engine loaded = TargetFreshStartTest.engine(); loaded.restoreProfile("owner", state, 180_000L);
            assertEquals("Evening Vorkath", loaded.savedSession(id).session.name());
            return null;
        });
    }

    @Test public void blankAndCancelLeaveNameAndRevisionUntouched() throws Exception {
        onEdt(() -> {
            SidebarPanel panel = panel(); var saved = panel.engine.saved.get(0);
            String name = saved.session.name(); long revision = panel.engine.revision();
            var controller = new GrindsController(panel);
            controller.renameSession(saved.id); ShellProbe.typeSheet(panel.shell, "   "); ShellProbe.pressSheet(panel.shell, "OK");
            controller.renameSession(saved.id); ShellProbe.typeSheet(panel.shell, "Cancelled"); ShellProbe.pressSheet(panel.shell, "Cancel");
            assertEquals(name, saved.session.name()); assertEquals(revision, panel.engine.revision()); return null;
        });
    }

    @Test public void detailMenuRenamesTheSavedCopyWithoutChangingItsBookings() throws Exception {
        onEdt(() -> {
            SidebarPanel panel = panel();
            var engine = panel.engine;
            var saved = engine.saved.get(0);
            String id = saved.id, canonicalName = engine.session().name();
            String receipts = JsonCodec.gson().toJson(saved.session.transactions());
            String markets = JsonCodec.gson().toJson(saved.market);
            long elapsed = saved.session.elapsed(120_000L);
            panel.plans.actions.openDetail(id);
            panel.refreshNow();
            var menu = panel.plans.detailMenu(id);
            assertEquals(List.of("Rename…", "Delete…"), menu.stream().map(item -> item.label)
                    .collect(java.util.stream.Collectors.toList()));
            menu.get(0).action.run();
            assertEquals("Rename session", ShellProbe.sheetTitle(panel.shell));
            ShellProbe.typeSheet(panel.shell, "  Evening supplies  ");
            ShellProbe.pressSheet(panel.shell, "OK");
            panel.refreshNow();
            assertEquals("Evening supplies", saved.session.name());
            assertEquals("Evening supplies", panel.plans.data.detail.name);
            assertEquals("Evening supplies", panel.plans.hero.name.getToolTipText());
            panel.plans.actions.openDetail(null);
            panel.refreshNow();
            assertEquals("Evening supplies", panel.plans.saved.rows.get(0).name);
            assertEquals(canonicalName, engine.session().name());
            assertEquals(id, saved.id);
            assertEquals(receipts, JsonCodec.gson().toJson(saved.session.transactions()));
            assertEquals(markets, JsonCodec.gson().toJson(saved.market));
            assertEquals(elapsed, saved.session.elapsed(120_000L));
            assertEquals(500, saved.session.metrics(120_000L).net);
            assertEquals(1_500, GrindsData.allTime(engine, 120_000L).net);
            SavedState state = JsonCodec.gson().fromJson(JsonCodec.gson().toJson(engine.exportState()), SavedState.class);
            Engine restored = TargetFreshStartTest.engine();
            restored.restoreProfile("owner", state, 180_000L);
            assertEquals("Evening supplies", restored.savedSession(id).session.name());
            return null;
        });
    }

    @Test public void cancellingDetailRenameLeavesTheCopyAndCanonicalRunUntouched() throws Exception {
        onEdt(() -> {
            SidebarPanel panel = panel();
            var saved = panel.engine.saved.get(0);
            String name = saved.session.name();
            long revision = panel.engine.revision();
            panel.plans.actions.openDetail(saved.id);
            panel.refreshNow();
            panel.plans.detailMenu(saved.id).stream().filter(item -> item.label.equals("Rename…"))
                    .findFirst().orElseThrow().action.run();
            ShellProbe.typeSheet(panel.shell, "Cancelled");
            ShellProbe.pressSheet(panel.shell, "Cancel");
            assertEquals(name, saved.session.name());
            assertEquals(revision, panel.engine.revision());
            assertEquals(List.of("Rename…", "Delete…"), panel.plans.detailMenu("canonical-history-id").stream()
                    .map(item -> item.label).collect(java.util.stream.Collectors.toList()));
            return null;
        });
    }

    @Test public void finishedRunCanBeRenamedFromItsDetailWithoutChangingHistoryOrTotals() throws Exception {
        onEdt(() -> {
            Engine engine = SavedSessionTest.window();
            Session run = engine.session();
            String receipts = JsonCodec.gson().toJson(run.transactions());
            long elapsed = run.elapsed(120_000L);
            assertTrue(engine.startFreePlayOver(120_000L));
            SidebarPanel panel = SavedSessionTest.panel(engine);
            panel.shell.show(Shell.GRINDS);
            panel.plans.actions.openDetail(run.id());
            panel.refreshNow();
            panel.plans.detailMenu(run.id()).stream().filter(item -> item.label.equals("Rename…"))
                    .findFirst().orElseThrow().action.run();
            assertEquals("Rename session", ShellProbe.sheetTitle(panel.shell));
            ShellProbe.typeSheet(panel.shell, "Morning supplies");
            ShellProbe.pressSheet(panel.shell, "OK");
            panel.refreshNow();
            assertEquals("Morning supplies", panel.plans.data.detail.name);
            assertEquals(receipts, JsonCodec.gson().toJson(run.transactions()));
            assertEquals(elapsed, run.elapsed(180_000L));
            assertEquals(1_500, GrindsData.allTime(engine, 180_000L).net);
            SavedState state = JsonCodec.gson().fromJson(JsonCodec.gson().toJson(engine.exportState()), SavedState.class);
            Engine restored = TargetFreshStartTest.engine();
            restored.restoreProfile("owner", state, 180_000L);
            assertEquals("Morning supplies", restored.historySession(run.id()).name());
            assertEquals(receipts, JsonCodec.gson().toJson(restored.historySession(run.id()).transactions()));
            assertEquals(1_500, GrindsData.allTime(restored, 180_000L).net);
            return null;
        });
    }

    @Test public void renamingAFinishedGrindKeepsItsTemplateAndLineage() throws Exception {
        onEdt(() -> {
            Engine engine = TargetFreshStartTest.engine();
            var template = engine.saveGrind("Vorkath", 5_000L, null, false);
            assertTrue(engine.startGrind("Vorkath", template.id(), 5_000L, null, 1_000L));
            TargetFreshStartTest.coins(engine, 2_000L, 750L);
            Session run = engine.session();
            assertTrue(engine.finishCustom(120_000L));
            String receipts = JsonCodec.gson().toJson(run.transactions());
            SidebarPanel panel = SavedSessionTest.panel(engine);
            panel.shell.show(Shell.GRINDS);
            panel.plans.actions.openDetail(run.id());
            panel.refreshNow();
            panel.plans.detailMenu(run.id()).get(0).action.run();
            ShellProbe.typeSheet(panel.shell, "Evening Vorkath");
            ShellProbe.pressSheet(panel.shell, "OK");
            panel.refreshNow();
            assertEquals("Evening Vorkath", panel.plans.data.detail.name);
            assertEquals("Vorkath", engine.plan(template.id()).name());
            assertEquals(template.id(), run.grindId());
            assertEquals(Long.valueOf(5_000L), run.targetGp());
            assertEquals(receipts, JsonCodec.gson().toJson(run.transactions()));
            assertEquals(750L, GrindsData.allTime(engine, 120_000L).net);
            return null;
        });
    }

    @Test public void anAlreadyOpenedSavedLedgerUsesTheRenamedTitle() throws Exception {
        renamedLedgerTitle(true);
    }

    @Test public void anAlreadyOpenedFinishedLedgerUsesTheRenamedTitle() throws Exception {
        renamedLedgerTitle(false);
    }

    void renamedLedgerTitle(boolean savedCopy) throws Exception {
        onEdt(() -> {
            SidebarPanel panel = panel();
            String id;
            if (savedCopy) id = panel.engine.saved.get(0).id;
            else {
                Session run = panel.engine.session();
                assertTrue(panel.engine.startFreePlayOver(120_000L));
                id = run.id();
            }
            panel.actions.openLedger(id);
            panel.refreshNow();
            long net = panel.ledger.data.net;
            assertEquals(panel.engine.historySession(id).name(), panel.ledger.data.scopeName);
            panel.shell.show(Shell.GRINDS);
            panel.actions.openDetail(id);
            panel.refreshNow();
            panel.plans.detailMenu(id).get(0).action.run();
            ShellProbe.typeSheet(panel.shell, "Renamed run");
            ShellProbe.pressSheet(panel.shell, "OK");
            panel.shell.show(Shell.LEDGER);
            panel.refreshNow();
            assertEquals("Renamed run", panel.ledger.data.scopeName);
            assertEquals(net, panel.ledger.data.net);
            assertTrue(panel.ledger.data.readonly);
            var raids = new LedgerData.Entry(LedgerData.Scope.HISTORY, "raid:all", "Raid receipts");
            assertEquals("Raid receipts", LedgerData.scopeName(panel.engine, raids));
            return null;
        });
    }

    @Test public void deletedCopyCannotBeRenamedOrRecreated() throws Exception {
        onEdt(() -> {
            SidebarPanel panel = panel(); var saved = panel.engine.saved.get(0); String name = saved.session.name();
            new GrindsController(panel).renameSession(saved.id); ShellProbe.typeSheet(panel.shell, "Deleted");
            panel.engine.deleteHistory(saved.id); ShellProbe.pressSheet(panel.shell, "OK");
            assertNull(panel.engine.savedSession(saved.id)); assertEquals(name, saved.session.name()); return null;
        });
    }

    @Test public void restoredCopyWithSameIdRejectsTheOldPrompt() throws Exception {
        onEdt(() -> {
            SidebarPanel panel = panel(); var saved = panel.engine.saved.get(0); String name = saved.session.name();
            new GrindsController(panel).renameSession(saved.id); ShellProbe.typeSheet(panel.shell, "Stale");
            SavedState state = JsonCodec.gson().fromJson(JsonCodec.gson().toJson(panel.engine.exportState()), SavedState.class);
            panel.engine.restoreProfile("other-owner", state, 180_000L);
            ShellProbe.pressSheet(panel.shell, "OK");
            assertNotSame(saved, panel.engine.savedSession(saved.id));
            assertEquals(name, panel.engine.savedSession(saved.id).session.name()); return null;
        });
    }
}
