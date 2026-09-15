package com.gpmanager.persistence;

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
    public void copiesLegacyDataIntoTheRenamedDirectory() throws Exception
    {
        Path root = Files.createTempDirectory("profit-manager-migration");
        Path legacy = root.resolve("smart-profit-tracker");
        Path current = root.resolve("profit-manager");
        Files.createDirectories(legacy.resolve("exports"));
        Files.writeString(legacy.resolve("sessions.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(legacy.resolve("exports/example.csv"), "value", StandardCharsets.UTF_8);

        SessionRepository repository = new SessionRepository(new Gson(), current, legacy);

        assertEquals(current, repository.getDataDirectory());
        assertTrue(Files.exists(current.resolve("sessions.json")));
        assertTrue(Files.exists(current.resolve("exports/example.csv")));
        assertTrue(Files.exists(legacy.resolve("sessions.json")));
    }

    @Test
    public void legacySessionsWithoutUndoMetadataRemainUsable() throws Exception
    {
        Path root = Files.createTempDirectory("profit-manager-legacy-session");
        Path current = root.resolve("profit-manager");
        Files.createDirectories(current);
        Files.writeString(
            current.resolve("sessions.json"),
            "{\"activeSession\":{\"name\":\"Legacy\",\"transactions\":[]},\"history\":[]}",
            StandardCharsets.UTF_8);

        SavedState state = new SessionRepository(new Gson(), current).load();

        assertEquals("Legacy", state.getActiveSession().getName());
        assertTrue(state.getActiveSession().getUndoHistory().isEmpty());
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
            "{\"activeSession\":{\"name\":\"Broken undo\",\"transactions\":[],\"undoHistory\":[null,{\"timestampEpochMillis\":5}]},\"history\":[]}",
            StandardCharsets.UTF_8);

        SavedState state = new SessionRepository(new Gson(), current).load();

        assertEquals(2, state.getActiveSession().getUndoHistory().size());
        assertNull(state.getActiveSession().restoreLastUndo(1_000L));
    }
}
