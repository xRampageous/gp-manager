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
