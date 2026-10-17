package com.gpmanager;

import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Review 2026-10-09: "Back up profile" holds the persistence lock while it writes; the game thread's
 * readiness check must answer at once from its last result instead of waiting behind the disk.
 */
public class BackupNoStallTest
{
    @Test
    public void aBackupHoldingTheLockNeverStallsTheGameThread() throws Exception
    {
        SessionRepository repository = new SessionRepository(new com.google.gson.Gson(),
            FilepathTestSupport.root(Files.createTempDirectory("gp-busy")), false);
        Persistence persistence = new Persistence(null, null, repository, new OrderedPersistenceWriter(repository, null), null);
        persistence.okay = true;
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread backup = new Thread(() ->
        {
            persistence.busy = true;
            synchronized (persistence)
            {
                held.countDown();
                try
                {
                    release.await(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException ignored)
                {
                    Thread.currentThread().interrupt();
                }
            }
            persistence.busy = false;
        });
        backup.start();
        assertTrue(held.await(5, TimeUnit.SECONDS));
        long started = System.nanoTime();
        assertEquals("the last answer, not a wait", true, persistence.isTrackingReady());
        assertTrue("answered at once", System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(500));
        release.countDown();
        backup.join(5_000);
    }
}
