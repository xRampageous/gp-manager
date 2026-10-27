package com.gpmanager;

import com.google.gson.Gson;
import java.awt.Component;
import java.awt.Container;
import javax.swing.*;
import org.junit.Before;
import org.junit.Test;

import static com.gpmanager.MilestoneTest.onEdt;
import static org.junit.Assert.*;

/** Interaction regressions: retain target drafts and refresh commands whose labels change. */
public class PolishInteractionTest {
    private static final long NOW = 10_000L;

    @Before public void setUp() { JsonCodec.bind(new Gson()); }

    @Test public void invalidStartTargetsKeepTheNameFieldsAndSavedPickerPage() throws Exception {
        onEdt(() -> {
            SidebarPanel panel = DirectHeaderControlsTest.panel();
            for (int i = 0; i < 8; i++) panel.engine.saveGrind("Saved route " + i, null, null, false);
            new GrindsController(panel).newGrind();
            Shell shell = panel.shell;
            ShellProbe.pressSheet(shell, "›");
            field(shell, "nameField").setText("Wilderness route");
            field(shell, "netField").setText("lots");
            field(shell, "timeField").setText("1h 30m");
            ShellProbe.pressSheet(shell, "Start");
            assertEquals("Start Grind", shell.heading);
            assertEquals("Wilderness route", field(shell, "nameField").getText());
            assertEquals("lots", field(shell, "netField").getText());
            assertEquals("1h 30m", field(shell, "timeField").getText());
            assertTrue("saved picker stays on its page", ShellProbe.sheetButtonTexts(shell).contains("Saved route 7"));
            assertFalse(panel.engine.customActive());
            assertEquals(8, panel.engine.plans(true).size());
            field(shell, "netField").setText("5m");
            ShellProbe.pressSheet(shell, "Start");
            assertEquals("", shell.heading);
            assertEquals("Wilderness route", panel.engine.session().name());
            assertEquals(Long.valueOf(5_000_000L), panel.engine.session().targetGp());
            assertEquals(Long.valueOf(5_400_000L), panel.engine.session().timeTargetMillis());
            return null;
        });
    }

    @Test public void invalidMilestoneKeepsEveryTargetDraftUntilCorrectedOrCancelled() throws Exception {
        onEdt(() -> {
            SidebarPanel panel = DirectHeaderControlsTest.panel();
            panel.engine.saveTargets(1_000L, 60_000L, NOW);
            panel.engine.setMilestone(400_000L);
            TargetFreshStartTest.coins(panel.engine, NOW + 1L, 500L);
            new GrindsController(panel).openTargets();
            Shell shell = panel.shell;
            field(shell, "netField").setText("2m");
            field(shell, "timeField").setText("3h");
            field(shell, "milestoneField").setText("many");
            ShellProbe.pressSheet(shell, "Save");
            assertEquals("Set a Target", shell.heading);
            assertEquals("2m", field(shell, "netField").getText());
            assertEquals("3h", field(shell, "timeField").getText());
            assertEquals("many", field(shell, "milestoneField").getText());
            assertEquals(Long.valueOf(1_000L), panel.engine.session().targetGp());
            assertEquals(Long.valueOf(60_000L), panel.engine.session().timeTargetMillis());
            assertEquals(Long.valueOf(400_000L), panel.engine.session().milestoneGp);
            assertEquals(500L, panel.engine.metrics(NOW + 2L).net);
            field(shell, "milestoneField").setText("500k");
            ShellProbe.pressSheet(shell, "Save");
            assertEquals("", shell.heading);
            assertEquals(Long.valueOf(2_000_000L), panel.engine.session().targetGp());
            assertEquals(Long.valueOf(10_800_000L), panel.engine.session().timeTargetMillis());
            assertEquals(Long.valueOf(500_000L), panel.engine.session().milestoneGp);
            assertEquals("setting targets preserves the whole run", 500L, panel.engine.metrics(NOW + 2L).net);
            new GrindsController(panel).openTargets();
            field(shell, "netField").setText("bad");
            ShellProbe.pressSheet(shell, "Save");
            ShellProbe.pressSheet(shell, "Cancel");
            assertEquals("", shell.heading);
            assertEquals(Long.valueOf(2_000_000L), panel.engine.session().targetGp());
            return null;
        });
    }

    @Test public void changingOwnerClosesAnInvalidDraftAndNewFormsUseTheNewOwner() throws Exception {
        onEdt(() -> {
            SidebarPanel panel = DirectHeaderControlsTest.panel();
            new GrindsController(panel).newGrind();
            field(panel.shell, "nameField").setText("Previous owner route");
            field(panel.shell, "netField").setText("invalid");
            ShellProbe.pressSheet(panel.shell, "Start");
            assertEquals("Start Grind", panel.shell.heading);
            panel.suspend();
            panel.engine.restoreProfile("next-owner", new SavedState(), NOW);
            panel.resume();
            assertEquals("", panel.shell.heading);
            new GrindsController(panel).newGrind();
            assertFalse(field(panel.shell, "nameField").getText().contains("Previous owner"));
            assertEquals("", field(panel.shell, "netField").getText());
            assertFalse(panel.engine.customActive());
            return null;
        });
    }

    @Test public void invalidEditTimeKeepsTheTypedNameAndExistingSavedSetup() throws Exception {
        onEdt(() -> {
            SidebarPanel panel = DirectHeaderControlsTest.panel();
            SavedState.SavedGrind saved = panel.engine.saveGrind("Zulrah", 1_000L, 60_000L, true);
            new GrindsController(panel).editSavedGrind(saved.id());
            Shell shell = panel.shell;
            field(shell, "nameField").setText("Zulrah bowfa");
            field(shell, "netField").setText("5m");
            field(shell, "timeField").setText("forever");
            ShellProbe.pressSheet(shell, "Save");
            assertEquals("Edit Zulrah", shell.heading);
            assertEquals("Zulrah bowfa", field(shell, "nameField").getText());
            assertEquals("5m", field(shell, "netField").getText());
            assertEquals("forever", field(shell, "timeField").getText());
            assertEquals("Zulrah", saved.name());
            assertEquals(Long.valueOf(1_000L), saved.targetGp());
            field(shell, "timeField").setText("2h");
            ShellProbe.pressSheet(shell, "Save");
            assertEquals("", shell.heading);
            assertEquals("Zulrah bowfa", saved.name());
            assertEquals(Long.valueOf(5_000_000L), saved.targetGp());
            assertEquals(Long.valueOf(7_200_000L), saved.timeTargetMillis());
            assertTrue(saved.favorite);
            return null;
        });
    }

    @Test public void archiveAndUnarchiveMenusFollowTheSavedStateWithArchivedVisible() throws Exception {
        onEdt(() -> {
            SidebarPanel panel = DirectHeaderControlsTest.panel();
            SavedState.SavedGrind saved = panel.engine.saveGrind("Zulrah", null, null, false);
            TargetFreshStartTest.coins(panel.engine, NOW, 500L);
            panel.shell.show(Shell.GRINDS);
            panel.archived = true;
            SidebarPanelProbe.refresh(panel);
            menu(panel, "Archive").doClick();
            assertEquals("Archive Grind", panel.shell.heading);
            ShellProbe.pressSheet(panel.shell, "Archive");
            SidebarPanelProbe.refresh(panel);
            assertTrue(saved.archived);
            menu(panel, "Unarchive").doClick();
            SidebarPanelProbe.refresh(panel);
            assertFalse(saved.archived);
            assertNotNull(menu(panel, "Archive"));
            assertEquals(500L, panel.engine.metrics(NOW + 1L).net);
            assertEquals(1, panel.engine.plans(true).size());
            return null;
        });
    }

    static JMenuItem menu(SidebarPanel panel, String text) {
        JComponent row = (JComponent) panel.plans.mine.body.getComponent(0);
        for (Component item : row.getComponentPopupMenu().getComponents()) {
            if (item instanceof JMenuItem && text.equals(((JMenuItem) item).getText())) return (JMenuItem) item;
        }
        throw new AssertionError("missing row command " + text);
    }

    static JTextField field(Shell shell, String key) {
        JTextField found = field(shell.sheet, key);
        assertNotNull("missing field " + key, found);
        return found;
    }

    static JTextField field(Component root, String key) {
        if (root instanceof JComponent) {
            Object value = ((JComponent) root).getClientProperty(key);
            if (value instanceof JTextField) return (JTextField) value;
        }
        if (root instanceof Container) for (Component child : ((Container) root).getComponents()) {
            JTextField found = field(child, key);
            if (found != null) return found;
        }
        return null;
    }
}
