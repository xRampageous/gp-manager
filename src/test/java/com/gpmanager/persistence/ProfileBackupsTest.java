package com.gpmanager;

import com.google.gson.Gson;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

/** Owner 2026-09-28: backups in their own folder, newest 10 per account, clearable and restorable. */
public class ProfileBackupsTest
{
    private static final TrackingIdentity ALICE = new TrackingIdentity("rsprofile.alice", -1L);
    private static final long T0 = 1_700_000_000_000L;
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void backupsKeepTenClearToOneAndRestoreWithASafetyCopy() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(root), true);
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);
        Engine engine = new Engine(d -> Collections.emptyList(),
            new TransactionClassifier(), new GpManagerConfig() {});
        PersistenceCoordinator coordinator = new PersistenceCoordinator(null, null, repository, writer, engine)
        {
            @Override TrackingIdentity resolveCurrentIdentity() { return ALICE; }
        };
        try
        {
            JsonCodec.bind(new Gson());
            assertTrue(coordinator.trySwitchIdentity(ALICE, true));
            engine.ensureSession(1_000L);
            engine.getActiveSession().rename("Before restore");
            assertTrue(coordinator.saveNow());

            Filepath first = coordinator.writePreOperationBackup(T0);
            assertEquals("backups", first.getParent().getFileName());
            assertTrue(first.getFileName().startsWith("rsprofile.alice-"));
            for (int i = 1; i <= 11; i++)
            {
                coordinator.writePreOperationBackup(T0 + i * 1_000L);
            }
            List<Filepath> kept = coordinator.backups();
            assertEquals("only the newest 10 per account are kept", 10, kept.size());
            assertFalse("the oldest went first", kept.contains(first));

            engine.getActiveSession().rename("Changed");
            assertTrue(coordinator.saveNow());
            PersistenceCoordinator.ResetOutcome outcome = coordinator.restoreBackup(kept.get(0), T0 + 20_000L);
            assertTrue(outcome.getDetail(), outcome.isApplied());
            assertEquals("Before restore", engine.getActiveSession().getName());
            assertEquals("a safety copy of the replaced data is made first", 10, coordinator.backups().size());

            assertEquals(9, coordinator.pruneBackups(1));
            assertEquals(1, coordinator.backups().size());
        }
        finally { writer.shutdown(Duration.ofSeconds(5)); }
    }
}
