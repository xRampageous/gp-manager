package com.gpmanager;

import com.google.gson.Gson;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import net.runelite.api.Client;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarbitID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static com.gpmanager.GpManagerPluginProbe.set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 1.7.0: Blast Furnace coffer drain belongs to the session that was active and ingesting when it
 * was observed. It books in 1,200 gp batches as before, and also before that session pauses, ends,
 * changes or is saved; paused or ineligible readings only move the balance, and a booking that
 * cannot land keeps the drain for its owner. Explicit game ticks throughout.
 */
public class BlastFurnaceCofferTest
{
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private static final class Gate extends GpManagerPlugin
    {
        boolean ready = true;

        @Override
        boolean canIngest()
        {
            return ready;
        }
    }

    private final class Rig
    {
        final Engine engine = new Engine(d -> Collections.emptyList(), new TransactionClassifier(),
            new GpManagerConfig() {});
        final Gate plugin = new Gate();
        int tick = 100;
        int balance = 100_000;

        Rig() throws Exception
        {
            Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
                new Class<?>[] {Client.class},
                (proxy, method, args) -> "getTickCount".equals(method.getName()) ? tick : null);
            set(plugin, "engine", engine);
            set(plugin, "client", client);
            set(plugin, "config", new GpManagerConfig() {});
            engine.ensureSession(1_000L);
            read(balance);
        }

        void read(int gp)
        {
            VarbitChanged event = new VarbitChanged();
            event.setVarbitId(VarbitID.BLAST_FURNACE_COFFER);
            event.setValue(gp);
            plugin.onVarbitChanged(event);
        }

        /** {@code ticks} readings, one tick apart, each draining 12 gp. */
        void work(int ticks)
        {
            for (int i = 0; i < ticks; i++)
            {
                tick++;
                balance -= 12;
                read(balance);
            }
        }
    }

    static long drained(Session session)
    {
        long total = 0;
        if (session != null)
        {
            for (Transaction row : session.transactions())
            {
                if ("Blast Furnace coffer".equals(row.note())) total -= row.autoNet();
            }
        }
        return total;
    }

    @Test
    public void aPauseBooksTheActiveDrainAndNothingObservedWhilePaused() throws Exception
    {
        Rig rig = new Rig();
        Session run = rig.engine.session();
        rig.work(99);
        assertEquals("1,188 gp waits for its batch", 0L, drained(run));
        rig.engine.togglePause(5_000L);
        assertEquals("the pause books the active 1,188 gp to its session", 1_188L, drained(run));
        rig.work(1);
        rig.engine.togglePause(6_000L);
        rig.work(1);
        assertEquals("paused drain is never a cost; the resumed tick waits for its batch", 1_188L, drained(run));
    }

    @Test
    public void pausedOnlyDrainNeverAppearsAndTheResumeBaselineKeepsTheNextActiveDrain() throws Exception
    {
        Rig rig = new Rig();
        Session run = rig.engine.session();
        rig.engine.togglePause(2_000L);
        rig.work(50);
        rig.engine.togglePause(3_000L);
        rig.work(99);
        assertEquals("no paused drain rode into the active batch", 0L, drained(run));
        rig.work(1);
        assertEquals("the first resumed drop counted", 1_200L, drained(run));
    }

    @Test
    public void aDataResetDropsTheClearedDrainAndTheNextSessionStillBooks() throws Exception
    {
        Rig rig = new Rig();
        rig.work(50);
        rig.engine.resetData(5_000L);
        rig.engine.ensureSession(10_000L);
        Session next = rig.engine.session();
        rig.work(100);
        assertEquals("the reset's 600 gp is gone; the new session books its own batch", 1_200L, drained(next));
    }

    @Test
    public void readingsWhileTrackingCannotIngestOnlyMoveTheBalance() throws Exception
    {
        Rig rig = new Rig();
        Session run = rig.engine.session();
        rig.plugin.ready = false;
        rig.work(100);
        rig.plugin.ready = true;
        rig.work(99);
        assertEquals(0L, drained(run));
        rig.work(1);
        assertEquals(1_200L, drained(run));
    }

    @Test
    public void aBookingThatCannotLandKeepsTheDrainForItsOwnerAndBooksItOnce() throws Exception
    {
        Rig rig = new Rig();
        Session run = rig.engine.session();
        rig.work(99);
        // A pause that skipped the engine's boundary (a missed hook): the save cannot book.
        run.pause(5_000L, PauseReason.MANUAL);
        rig.work(1);
        rig.engine.savedState();
        assertEquals(0L, drained(run));
        run.resume(6_000L);
        rig.work(1);
        assertEquals("1,188 kept for its owner plus the active tick", 1_200L, drained(run));
        rig.engine.savedState();
        rig.engine.pauseForLifecycle(7_000L);
        assertEquals("booked exactly once", 1_200L, drained(run));
    }

    @Test
    public void aBelowThresholdBatchIsInEverySaveAndTheShutdownSnapshot() throws Exception
    {
        Rig rig = new Rig();
        rig.work(50);
        SavedState saved = rig.engine.savedState();
        assertEquals("a save carries the 600 gp observed so far", 600L, drained(saved.currentSession()));
        rig.work(25);
        rig.engine.pauseForLifecycle(8_000L);
        assertEquals("disable pauses only after booking", 900L, drained(rig.engine.savedState().currentSession()));
    }

    @Test
    public void everySessionBoundaryBooksToTheSessionThatDrained() throws Exception
    {
        Rig idle = new Rig();
        idle.work(10);
        idle.engine.pauseIdle(2_000L);
        assertEquals(120L, drained(idle.engine.session()));

        Rig world = new Rig();
        world.work(10);
        world.engine.pauseForWorld(2_000L);
        assertEquals(120L, drained(world.engine.session()));

        Rig grind = new Rig();
        Session general = grind.engine.session();
        grind.work(10);
        grind.engine.startCustom("Bars", 2_000L);
        assertEquals("custom start books Free play's drain first", 120L, drained(general));
        Session custom = grind.engine.session();
        grind.work(5);
        assertTrue(grind.engine.finishCustom(3_000L));
        assertEquals("finishing books the Grind's drain to the Grind", 60L, drained(custom));
        assertEquals(120L, drained(general));

        Rig reset = new Rig();
        Session before = reset.engine.session();
        reset.work(10);
        assertTrue(reset.engine.startFreePlayOver(2_000L));
        assertEquals("reset free play books into the run that moved to history", 120L, drained(before));
        assertEquals(0L, drained(reset.engine.session()));
    }

    @Test
    public void depositsWithdrawalsAndTheFirstReadingStayNeutral() throws Exception
    {
        Rig rig = new Rig();
        rig.tick++;
        rig.read(150_000);
        rig.tick++;
        rig.read(50_000);
        rig.tick += 500;
        rig.read(40_000);
        rig.engine.pauseForLifecycle(2_000L);
        assertEquals(0L, drained(rig.engine.session()));
    }

    @Test
    public void aNewProfileStartsFromAFreshBalance() throws Exception
    {
        Rig rig = new Rig();
        JsonCodec.bind(new Gson());
        rig.engine.restoreProfile("rsprofile.bob", new SavedState(), 2_000L);
        rig.engine.ensureSession(2_000L);
        rig.work(100);
        assertEquals("the first reading after the switch only sets the balance", 0L, drained(rig.engine.session()));
        rig.work(1);
        assertEquals(1_200L, drained(rig.engine.session()));
    }

    @Test
    public void anAccountSwitchSavesTheBatchWithItsOwnerOnly() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        TrackingIdentity alice = new TrackingIdentity("rsprofile.alice", -1L);
        TrackingIdentity bob = new TrackingIdentity("rsprofile.bob", -1L);
        JsonCodec.bind(new Gson());
        Rig rig = new Rig();
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(root), true);
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);
        TrackingIdentity[] who = {alice};
        Persistence coordinator = new Persistence(null, null, repository, writer, rig.engine)
        {
            @Override
            TrackingIdentity resolveCurrentIdentity()
            {
                return who[0];
            }
        };
        try
        {
            assertTrue(coordinator.switchIdentity(alice, true));
            rig.engine.ensureSession(3_000L);
            rig.work(1);
            rig.work(40);
            who[0] = bob;
            assertTrue(coordinator.switchIdentity(bob, true));
            rig.engine.ensureSession(4_000L);
            rig.work(20);
            assertTrue(coordinator.saveNow());
            who[0] = alice;
            assertTrue(coordinator.switchIdentity(alice, true));

            SessionRepository reader = new SessionRepository(new Gson(), FilepathTestSupport.root(root), true);
            reader.bindIdentity(alice);
            assertEquals("Alice's 480 gp saved with Alice, once", 480L, drained(reader.load().currentSession()));
            reader.bindIdentity(bob);
            assertEquals("Bob's first reading only set the balance", 228L, drained(reader.load().currentSession()));
            assertEquals(480L, drained(rig.engine.session()));
        }
        finally
        {
            writer.shutdown(Duration.ofSeconds(5));
        }
    }
}
