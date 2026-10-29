package com.gpmanager;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

/** Schema 110: an older save is set aside and the profile starts fresh; a newer one is never touched. */
public class SchemaFreshStartTest
{
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private static SavedState state(String name)
    {
        return new SavedState(new Session(name, 1_000L, SessionMode.GENERAL), null, Collections.emptyList());
    }

    /** Rewrites the saved file's schema field; a negative schema removes it. */
    private static void stamp(Path directory, int schema) throws Exception
    {
        for (String name : new String[] {"sessions.json", "sessions.backup.json"})
        {
            Path file = directory.resolve(name);
            if (!Files.exists(file)) continue;
            JsonObject json = new JsonParser().parse(Files.readString(file)).getAsJsonObject();
            if (schema < 0) json.remove("schemaVersion");
            else json.addProperty("schemaVersion", schema);
            Files.writeString(file, json.toString());
        }
    }

    private static long setAside(Path directory) throws Exception
    {
        try (java.util.stream.Stream<Path> paths = Files.list(directory))
        {
            return paths.filter(path -> path.getFileName().toString().contains(".schema")).count();
        }
    }

    private void startsFresh(int olderSchema) throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false);
        assertTrue(PersistenceProbe.save(repository, state("Old")));
        assertTrue(PersistenceProbe.save(repository, state("Older backup")));
        stamp(directory, olderSchema);

        SavedState loaded = repository.load();
        assertNull("nothing from the older save is read", FixtureReads.currentSession(loaded));
        assertTrue(loaded.isSupportedSchema());
        assertEquals("primary and backup are kept beside the profile", 2L, setAside(directory));

        assertTrue("the fresh profile saves normally", PersistenceProbe.save(repository, state("New")));
        assertEquals("New", FixtureReads.currentSession(repository.load()).name());
    }

    @Test
    public void anOlderSaveIsSetAsideAndTheProfileStartsFresh() throws Exception
    {
        startsFresh(108);
    }

    @Test
    public void theReleasedSaveIsSetAsideAndTheProfileStartsFresh() throws Exception
    {
        // The actual 1.3.3 (schema 109) → 1.3.4 (schema 110) transition.
        startsFresh(109);
    }

    @Test
    public void aSaveWithoutASchemaStartsFreshInsteadOfBlockingEverySave() throws Exception
    {
        startsFresh(-1);
    }

    @Test
    public void aSaveThatMeetsAnOlderFileSetsItAsideInsteadOfRefusing() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository writer = new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false);
        assertTrue(PersistenceProbe.save(writer, state("Old")));
        stamp(directory, 108);

        SessionRepository fresh = new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false);
        assertTrue(fresh.save(new WriteIntent(null, fresh.generation, fresh.revision,
            state("New"), null)));
        assertEquals("New", FixtureReads.currentSession(fresh.load()).name());
        assertEquals(1L, setAside(directory));
    }

    @Test
    public void aNewerSaveIsNeverReadAsCurrentOrOverwritten() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false);
        assertTrue(PersistenceProbe.save(repository, state("Future")));
        stamp(directory, SavedState.CURRENT_SCHEMA_VERSION + 1);
        String before = Files.readString(directory.resolve("sessions.json"));

        assertFalse(repository.load().isSupportedSchema());
        assertFalse(PersistenceProbe.save(repository, state("Downgrade")));
        assertEquals(before, Files.readString(directory.resolve("sessions.json")));
        assertEquals(0L, setAside(directory));
    }
}
