package com.gpmanager;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SessionRepositoryMigrationTest
{
    @Test
    public void legacySessionsWithoutUndoMetadataRemainUsable() throws Exception
    {
        Path root = Files.createTempDirectory("profit-manager-legacy-session");
        Path current = root.resolve("profit-manager");
        Files.createDirectories(current);
        Files.writeString(
            current.resolve("sessions.json"),
            "{\"generalSession\":{\"name\":\"Legacy\",\"transactions\":[]},\"history\":[]}",
            StandardCharsets.UTF_8);

        SavedState state = new SessionRepository(new Gson(), FilepathTestSupport.root(current)).load();

        assertEquals("Legacy", state.getActiveSession().getName());
        assertTrue(state.getActiveSession().undoHistory.isEmpty());
        assertNull(state.getActiveSession().restoreLastUndo(1_000L));
    }

    @Test
    public void malformedUndoSnapshotsAreIgnoredDuringRecovery() throws Exception
    {
        Path root = Files.createTempDirectory("profit-manager-malformed-undo");
        Path current = root.resolve("profit-manager");
        Files.createDirectories(current);
        Files.writeString(
            current.resolve("sessions.json"),
            "{\"generalSession\":{\"name\":\"Broken undo\",\"transactions\":[],\"undoHistory\":[null,{\"timestampEpochMillis\":5}]},\"history\":[]}",
            StandardCharsets.UTF_8);

        SavedState state = new SessionRepository(new Gson(), FilepathTestSupport.root(current)).load();

        assertEquals(2, state.getActiveSession().undoHistory.size());
        assertNull(state.getActiveSession().restoreLastUndo(1_000L));
    }
}
