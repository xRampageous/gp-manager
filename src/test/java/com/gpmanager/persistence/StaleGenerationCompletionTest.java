package com.gpmanager;

import com.google.gson.Gson;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.runelite.api.Client;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Charter section 16: an asynchronous save for account generation A that completes only after the
 * coordinator moved to generation B (or to the unbound state) lands in A's folder and nothing else.
 * B's engine state, revision, generation, disk lineage and {@link SaveStatus} are untouched. Uses
 * latches on the repository, never sleeps.
 */
public class StaleGenerationCompletionTest
{
    private static final String ALICE = "rsprofile.alice";
    private static final String BOB = "rsprofile.bob";
    private static final long NOW = 1_800_000_000_000L;

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    /** Blocks the first save whose intent carries {@code gate}'s generation until released. */
    private static final class GatedRepository extends SessionRepository
    {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile long gatedGeneration = -1L;

        GatedRepository(Path root)
        {
            super(new Gson(), FilepathTestSupport.root(root), true);
        }

        @Override
        public boolean save(WriteIntent intent)
        {
            if (intent.scopeGeneration == gatedGeneration)
            {
                gatedGeneration = -1L;
                entered.countDown();
                try
                {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("gate never released");
                }
                catch (InterruptedException ex)
                {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(ex);
                }
            }
            return super.save(intent);
        }
    }

    private static final class Rig
    {
        final GatedRepository repository;
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final OrderedPersistenceWriter writer;
        final Engine engine;
        final PersistenceCoordinator coordinator;

        Rig(Path root)
        {
            repository = new GatedRepository(root);
            writer = new OrderedPersistenceWriter(repository, executor);
            engine = EngineTestFixtures.engine(new GpManagerConfig()
            {
                @Override public boolean autoStartSession() { return false; }
            });
            coordinator = new PersistenceCoordinator(client(), IsolatedConfigManager.create(ALICE),
                repository, writer, engine);
            coordinator.start();
        }

        private static Client client()
        {
            return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getAccountHash")) return 77L;
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    return null;
                });
        }

        /** Alice books a note, then a save for her generation is started and parked inside the repository. */
        long startParkedAliceSave() throws Exception
        {
            assertTrue(coordinator.trySwitchIdentity(new TrackingIdentity(ALICE, TrackingIdentity.ACCOUNT_HASH_INVALID), true));
            engine.ensureSession(NOW - 10_000L);
            engine.getGeneralSession().rename("Alice general");
            assertTrue(coordinator.saveNow());
            long aliceGeneration = repository.scopeGeneration;
            engine.getGeneralSession().notes = "in flight";
            repository.gatedGeneration = aliceGeneration;
            coordinator.scheduleSave();
            assertTrue("drain entered the repository", repository.entered.await(10, TimeUnit.SECONDS));
            return aliceGeneration;
        }

        void stop()
        {
            repository.release.countDown();
            writer.shutdown(Duration.ofSeconds(5));
            executor.shutdownNow();
        }
    }

    @Test
    public void lateSaveForPreviousAccountCannotTouchTheNewAccount() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        Rig rig = new Rig(root);
        try
        {
            long aliceGeneration = rig.startParkedAliceSave();
            Path alicePrimary = FilepathTestSupport.path(
                rig.repository.scopeDirectoryFor(new TrackingIdentity(ALICE, TrackingIdentity.ACCOUNT_HASH_INVALID)).joinSegment("sessions.json"));
            String aliceBefore = Files.readString(alicePrimary);

            // Logout, then Bob logs in while Alice's write is still parked. No flush is possible
            // (the write is in flight), so the switch goes through the unbound state.
            rig.coordinator.onAccountHashInvalidated(false);
            assertTrue(rig.coordinator.trySwitchIdentity(new TrackingIdentity(BOB, TrackingIdentity.ACCOUNT_HASH_INVALID), true));
            rig.engine.ensureSession(NOW);
            rig.engine.getGeneralSession().rename("Bob general");
            long bobGeneration = rig.repository.scopeGeneration;
            assertNotEquals(aliceGeneration, bobGeneration);
            String bobGeneralId = rig.engine.getGeneralSession().getId();
            long bobApplied = rig.writer.getAppliedRevision();
            long bobDisk = rig.repository.lastKnownDiskRevision;
            SaveStatus bobStatus = rig.writer.getStatus();
            Path bobPrimary = FilepathTestSupport.path(rig.repository.getDataDirectory().joinSegment("sessions.json"));
            assertFalse(Files.exists(bobPrimary));

            // Release Alice's completion and wait for the drain to finish.
            rig.repository.release.countDown();
            assertTrue(rig.writer.flush(Duration.ofSeconds(10)));

            // Alice's folder got her write; Bob's world is exactly as it was.
            assertNotEquals(aliceBefore, Files.readString(alicePrimary));
            assertTrue(Files.readString(alicePrimary).contains("in flight"));
            assertFalse("no durable state for Bob was created by Alice's completion", Files.exists(bobPrimary));
            assertEquals(bobGeneration, rig.repository.scopeGeneration);
            assertEquals(bobApplied, rig.writer.getAppliedRevision());
            assertEquals(bobDisk, rig.repository.lastKnownDiskRevision);
            assertEquals(bobStatus.state, rig.writer.getStatus().state);
            assertSame(bobStatus, rig.writer.getStatus());
            assertEquals(bobGeneralId, rig.engine.getGeneralSession().getId());
            assertEquals("Bob general", rig.engine.getGeneralSession().getName());
            assertEquals(BOB, rig.engine.exportState().ownerKey);

            // Bob's own first save then starts a fresh lineage at revision 1.
            assertTrue(rig.coordinator.saveNow());
            assertEquals(1L, rig.repository.load().revision);
            assertEquals("Bob general", rig.repository.load().getActiveSession().getName());
        }
        finally
        {
            rig.stop();
        }
    }

    @Test
    public void lateSaveAfterLogoutCannotRecreateStateOrReportForTheUnboundScope() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        Rig rig = new Rig(root);
        try
        {
            rig.startParkedAliceSave();
            rig.coordinator.onAccountHashInvalidated(false);
            assertNull(rig.coordinator.getActiveIdentity());
            assertFalse(rig.repository.isBound());
            SaveStatus unboundStatus = rig.writer.getStatus();
            long unboundGeneration = rig.repository.scopeGeneration;

            rig.repository.release.countDown();
            assertTrue(rig.writer.flush(Duration.ofSeconds(10)));

            // Unbound: nothing is written to the root or the unassigned marker, status is not
            // rewritten for the abandoned generation, and a new save is refused outright.
            assertFalse(rig.repository.getDataDirectory().joinSegment("sessions.json").exists());
            assertEquals(unboundGeneration, rig.repository.scopeGeneration);
            assertSame(unboundStatus, rig.writer.getStatus());
            assertEquals(0L, rig.writer.getAppliedRevision());
            assertFalse(rig.coordinator.saveNow());
            rig.coordinator.scheduleSave();
            assertTrue(rig.writer.flush(Duration.ofSeconds(5)));
            assertFalse(PersistenceProbe.replaceStateDetailed(rig.repository, new SavedState()).isCommitted());
            assertFalse(rig.repository.getDataDirectory().joinSegment("sessions.json").exists());
        }
        finally
        {
            rig.stop();
        }
    }

}
