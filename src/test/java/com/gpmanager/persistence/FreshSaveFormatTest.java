package com.gpmanager;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 1.0 ships a fresh save format: a pre-1.0 save on disk is never read or rewritten. */
public class FreshSaveFormatTest
{
    private static final String OLD_SAVE =
        "{\"schemaVersion\":107,\"revision\":4,\"generalSession\":{\"name\":\"Old\",\"transactions\":[]}}";

    @Test
    public void preOneZeroSavesAreNeverReadOrRewritten() throws Exception
    {
        Path root = Files.createTempDirectory("gp-manager-fresh-format");
        TrackingIdentity identity = new TrackingIdentity("profile-key", TrackingIdentity.ACCOUNT_HASH_INVALID);
        Path oldSave = root.resolve("accounts").resolve(identity.ajc()).resolve("sessions.json");
        Files.createDirectories(oldSave.getParent());
        Files.writeString(oldSave, OLD_SAVE, StandardCharsets.UTF_8);

        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(root), true);
        repository.mc(identity);
        SavedState loaded = repository.load();
        assertNull("the old owner is never read", loaded.generalSession);
        assertEquals(SavedState.CURRENT_SCHEMA_VERSION, loaded.schemaVersion);

        assertTrue(PersistenceProbe.save(repository, new SavedState()));
        assertEquals("the old file is never rewritten", OLD_SAVE, Files.readString(oldSave, StandardCharsets.UTF_8));
    }
}
