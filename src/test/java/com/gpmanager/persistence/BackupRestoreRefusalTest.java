package com.gpmanager;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gpmanager.SessionRepository.Bm;
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
            Filepath backup = fixture.coordinator.akt(T0);
            backup.write("not json", StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            Ei.Ds outcome = fixture.coordinator.agm(backup, T0 + 1_000L);

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
            Filepath backup = fixture.coordinator.akt(T0);
            JsonObject json = new JsonParser().parse(SessionRepository.awy(backup)).getAsJsonObject();
            json.addProperty("schemaVersion", 999);
            backup.write(json.toString(), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            Ei.Ds outcome = fixture.coordinator.agm(backup, T0 + 1_000L);

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
            Filepath backup = fixture.coordinator.akt(T0);
            fixture.coordinator.engine.getActiveSession().rename("Changed");
            assertTrue(fixture.coordinator.aya());

            Ei.Ds restore = fixture.coordinator.agm(backup, T0 + 10_000L);

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

            Ei.Ds reset = fixture.coordinator.sj(T0 + 20_000L);

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
        final Am engine;
        final Ei coordinator;

        Fixture(Path root, boolean refusedCommit) throws Exception
        {
            repository = new SessionRepository(new Gson(), FilepathTestSupport.root(root), true);
            writer = new OrderedPersistenceWriter(repository);
            engine = new Am(d -> Collections.emptyList(), new TransactionClassifier(),
                new GpManagerConfig() {});
            JsonCodec.bind(new Gson());
            coordinator = refusedCommit
                ? new Ei(null, null, repository, writer, engine)
                {
                    @Override TrackingIdentity resolveCurrentIdentity() { return ALICE; }
                    @Override synchronized Bm agc() { return Bm.failedUnchanged("write failed"); }
                }
                : new Ei(null, null, repository, writer, engine)
                {
                    @Override TrackingIdentity resolveCurrentIdentity() { return ALICE; }
                };
        }

        static void seed(Ei coordinator)
        {
            assertTrue(coordinator.ajy(ALICE, true));
            coordinator.engine.rm(1_000L);
            coordinator.engine.getActiveSession().rename("Before");
            assertTrue(coordinator.aya());
        }

        void close()
        {
            writer.shutdown(Duration.ofSeconds(5));
        }
    }
}
