package com.gpmanager;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gpmanager.SessionRepository.ReplaceOutcome;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Collections;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Owner 2026-10-01 (F25): refused restores keep the old profile and name the operation. */
public class BackupRestoreRefusalTest
{
    private static final TrackingIdentity ALICE = new TrackingIdentity("rsprofile.alice", -1L);
    private static final long T0 = 1_700_000_000_000L;
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void aCorruptBackupIsRefusedAndTheProfileIsUntouched() throws Exception
    {
        Fixture fixture = new Fixture(temporary.newFolder().toPath(), false);
        try
        {
            Fixture.seed(fixture.coordinator);
            Filepath backup = fixture.coordinator.writePreOperationBackup(T0);
            backup.write("not json", StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            PersistenceCoordinator.ResetOutcome outcome = fixture.coordinator.restoreBackup(backup, T0 + 1_000L);

            assertFalse(outcome.isApplied());
            assertTrue("names the read failure: " + outcome.getDetail(),
                outcome.getDetail().startsWith("Backup could not be read"));
            assertEquals("Before", fixture.coordinator.engine.getActiveSession().getName());
        }
        finally
        {
            fixture.close();
        }
    }

    @Test
    public void anIncompatibleVersionBackupIsRefusedAndTheProfileIsUntouched() throws Exception
    {
        Fixture fixture = new Fixture(temporary.newFolder().toPath(), false);
        try
        {
            Fixture.seed(fixture.coordinator);
            Filepath backup = fixture.coordinator.writePreOperationBackup(T0);
            JsonObject json = new JsonParser().parse(SessionRepository.readText(backup)).getAsJsonObject();
            json.addProperty("schemaVersion", 999);
            backup.write(json.toString(), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            PersistenceCoordinator.ResetOutcome outcome = fixture.coordinator.restoreBackup(backup, T0 + 1_000L);

            assertFalse(outcome.isApplied());
            assertTrue("names the version: " + outcome.getDetail(),
                outcome.getDetail().contains("999"));
            assertEquals("Before", fixture.coordinator.engine.getActiveSession().getName());
        }
        finally
        {
            fixture.close();
        }
    }

    @Test
    public void aRefusedCommitNamesTheOperationAndKeepsTheOldProfile() throws Exception
    {
        Fixture fixture = new Fixture(temporary.newFolder().toPath(), true);
        try
        {
            Fixture.seed(fixture.coordinator);
            Filepath backup = fixture.coordinator.writePreOperationBackup(T0);
            fixture.coordinator.engine.getActiveSession().rename("Changed");
            assertTrue(fixture.coordinator.saveNow());

            PersistenceCoordinator.ResetOutcome restore = fixture.coordinator.restoreBackup(backup, T0 + 10_000L);

            assertFalse(restore.isApplied());
            assertTrue("names the restore: " + restore.getDetail(),
                restore.getDetail().startsWith("Restore was not applied:"));
            assertFalse("never the reset sentence",
                restore.getDetail().contains("Factory reset"));
            assertTrue("the failure reason rides along",
                restore.getDetail().contains("write failed"));
            assertNotNull("the pre-restore safety copy exists", restore.getPreResetBackup());
            assertEquals("the old profile is rolled back", "Changed",
                fixture.coordinator.engine.getActiveSession().getName());

            PersistenceCoordinator.ResetOutcome reset = fixture.coordinator.factoryResetCurrentAccount(T0 + 20_000L);

            assertFalse(reset.isApplied());
            assertTrue("names the reset: " + reset.getDetail(),
                reset.getDetail().startsWith("Factory reset was not applied:"));
            assertEquals("the old profile survives a refused reset", "Changed",
                fixture.coordinator.engine.getActiveSession().getName());
        }
        finally
        {
            fixture.close();
        }
    }

    private static final class Fixture
    {
        final SessionRepository repository;
        final OrderedPersistenceWriter writer;
        final Engine engine;
        final PersistenceCoordinator coordinator;

        Fixture(Path root, boolean refusedCommit) throws Exception
        {
            repository = new SessionRepository(new Gson(), FilepathTestSupport.root(root), true);
            writer = new OrderedPersistenceWriter(repository);
            engine = new Engine(d -> Collections.emptyList(), new TransactionClassifier(),
                new GpManagerConfig() {});
            JsonCodec.bind(new Gson());
            coordinator = refusedCommit
                ? new PersistenceCoordinator(null, null, repository, writer, engine)
                {
                    @Override TrackingIdentity resolveCurrentIdentity() { return ALICE; }
                    @Override synchronized ReplaceOutcome replaceStateNow() { return ReplaceOutcome.failedUnchanged("write failed"); }
                }
                : new PersistenceCoordinator(null, null, repository, writer, engine)
                {
                    @Override TrackingIdentity resolveCurrentIdentity() { return ALICE; }
                };
        }

        static void seed(PersistenceCoordinator coordinator)
        {
            assertTrue(coordinator.trySwitchIdentity(ALICE, true));
            coordinator.engine.ensureSession(1_000L);
            coordinator.engine.getActiveSession().rename("Before");
            assertTrue(coordinator.saveNow());
        }

        void close()
        {
            writer.shutdown(Duration.ofSeconds(5));
        }
    }
}
