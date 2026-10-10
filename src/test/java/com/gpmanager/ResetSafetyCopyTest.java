package com.gpmanager;

import com.google.gson.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Collections;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

/** Real coordinator reset safety: copies precede destruction, and stale exports cannot qualify. */
public class ResetSafetyCopyTest
{
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    static final long NOW = 1_700_000_000_000L;
    static final TrackingIdentity PROFILE = new TrackingIdentity("rsprofile.safety", -1L);

    /** A saved profile with one open and one closed session, on a real repository and writer. */
    static class Fixture implements AutoCloseable
    {
        SessionRepository repository;
        OrderedPersistenceWriter writer;
        Persistence persistence;

        Fixture(Path directory)
        {
            this(directory, false);
        }

        /** @param editDuringCopy rename the open session while each backup copy is written */
        Fixture(Path directory, boolean editDuringCopy)
        {
            JsonCodec.bind(new Gson());
            repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory), true);
            writer = new OrderedPersistenceWriter(repository);
            Engine engine = new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(),
                new GpManagerConfig() {});
            persistence = new Persistence(null, null, repository, writer, engine)
            {
                @Override
                TrackingIdentity resolveCurrentIdentity()
                {
                    return PROFILE;
                }

                @Override
                Filepath writeBackup(long now) throws java.io.IOException
                {
                    Filepath backup = super.writeBackup(now);
                    if (editDuringCopy) engine.getActiveSession().rename("Edited during copy");
                    return backup;
                }
            };
            assertTrue(persistence.switchIdentity(PROFILE, true));
            engine.ensureSession(NOW);
            engine.getActiveSession().rename("Before reset");
            engine.getActiveSession().addTransaction(receipt(NOW), 2_000);
            Session closed = new Session("Closed history", NOW - 60_000L);
            closed.addTransaction(receipt(NOW - 30_000L), 2_000);
            closed.close(NOW - 1_000L);
            engine.history.add(closed);
            assertTrue(persistence.saveNow());
        }

        @Override
        public void close()
        {
            writer.shutdown(Duration.ofSeconds(5));
        }
    }

    static Transaction receipt(long at)
    {
        return new Transaction(at, TransactionType.LOOT, Context.LOOT, "", "Guard", true,
            Collections.singletonList(new Flow(560, "Death rune", 46L, 187, 8_602L)),
            Confidence.CONFIRMED, "test", null);
    }

    static SavedState read(Filepath backup) throws Exception
    {
        return SessionRepository.parseBackup(JsonCodec.gson(), backup);
    }

    @Test
    public void directResetKeepsTheCompletePriorStateAndExistingSuccessNotice() throws Exception
    {
        try (Fixture fixture = new Fixture(temporary.newFolder().toPath()))
        {
            Persistence persistence = fixture.persistence;
            JsonObject before = Persistence.backupContent(persistence.engine.exportState());
            Persistence.ResetOutcome reset = persistence.factoryReset(NOW + 1_000L);
            assertTrue(reset.getDetail(), reset.isApplied());
            assertEquals(1, persistence.backups().size());
            SavedState copy = read(persistence.backups().get(0));
            assertTrue(copy.isSupportedSchema());
            assertEquals(before, Persistence.backupContent(copy));
            assertEquals(1, copy.generalSession.getTransactions().size());
            assertEquals(1, copy.history.size());
            assertNull(persistence.engine.getActiveSession());
        }
    }

    @Test
    public void anEarlierBackupIsKeptAndResetWritesItsOwnCopy() throws Exception
    {
        try (Fixture fixture = new Fixture(temporary.newFolder().toPath()))
        {
            Persistence persistence = fixture.persistence;
            Filepath backup = persistence.writeBackup(NOW);
            assertTrue(persistence.saveNow());
            assertTrue(persistence.factoryReset(NOW + 1_000L).isApplied());
            assertEquals(2, persistence.backups().size());
            assertTrue(persistence.backups().contains(backup));
            assertEquals("Before reset", read(persistence.backups().get(0)).generalSession.getName());
        }
    }

    @Test
    public void changedFactsRequireANewCopy() throws Exception
    {
        try (Fixture fixture = new Fixture(temporary.newFolder().toPath()))
        {
            Persistence persistence = fixture.persistence;
            Filepath backup = persistence.writeBackup(NOW);
            persistence.engine.getActiveSession().rename("Changed before reset");
            persistence.engine.getActiveSession().addTransaction(receipt(NOW + 500L), 2_000);
            assertTrue(persistence.factoryReset(NOW + 1_000L).isApplied());
            assertEquals(2, persistence.backups().size());
            assertEquals("Before reset", read(backup).generalSession.getName());
            SavedState newest = read(persistence.backups().get(0));
            assertEquals("Changed before reset", newest.generalSession.getName());
            assertEquals(2, newest.generalSession.getTransactions().size());
        }
    }

    @Test
    public void backupFailureRefusesResetBeforeMutatingTheOwner() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        try (Fixture fixture = new Fixture(directory))
        {
            Persistence persistence = fixture.persistence;
            Files.writeString(directory.resolve("backups"), "blocked directory");
            JsonObject before = Persistence.backupContent(persistence.engine.exportState());
            Persistence.ResetOutcome reset = persistence.factoryReset(NOW);
            assertFalse(reset.isApplied());
            assertTrue(reset.getDetail(), reset.getDetail().startsWith(GameData.msg("pre-restore")));
            assertEquals(before, Persistence.backupContent(persistence.engine.exportState()));
            assertEquals("Before reset", fixture.repository.load().generalSession.getName());
            assertFalse(persistence.maintenance);
        }
    }

    @Test
    public void corruptIncompatibleAndWrongOwnerExportsArePreservedButNotReused() throws Exception
    {
        for (int damage = 0; damage < 3; damage++)
        {
            try (Fixture fixture = new Fixture(temporary.newFolder().toPath()))
            {
                Persistence persistence = fixture.persistence;
                Filepath backup = persistence.writeBackup(NOW);
                JsonObject json = new JsonParser().parse(SessionRepository.readText(backup)).getAsJsonObject();
                if (damage == 1) json.addProperty("schemaVersion", 999);
                if (damage == 2) json.addProperty("ownerKey", "another.owner");
                String damaged = damage == 0 ? "{broken" : json.toString();
                backup.write(damaged, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                assertTrue(persistence.factoryReset(NOW + 1_000L).isApplied());
                assertEquals(2, persistence.backups().size());
                assertEquals("existing evidence is preserved", damaged, SessionRepository.readText(backup));
                SavedState fresh = read(persistence.backups().get(0));
                assertTrue(fresh.isSupportedSchema());
                assertEquals(PROFILE.rsProfileKey, fresh.ownerKey);
                assertEquals("Before reset", fresh.generalSession.getName());
            }
        }
    }

    @Test
    public void aSidebarEditDuringCopyRefusesResetAndKeepsTheNewerData() throws Exception
    {
        try (Fixture fixture = new Fixture(temporary.newFolder().toPath(), true))
        {
            Persistence persistence = fixture.persistence;
            Persistence.ResetOutcome reset = persistence.factoryReset(NOW);
            assertFalse(reset.isApplied());
            assertTrue(reset.getDetail(), reset.getDetail().contains("data changed"));
            assertEquals("Edited during copy", persistence.engine.getActiveSession().getName());
            assertEquals("Before reset", read(persistence.backups().get(0)).generalSession.getName());
            assertEquals(1, persistence.engine.getActiveSession().getTransactions().size());
            assertFalse(persistence.maintenance);
        }
    }
}
