package com.gpmanager;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * A scanner (antivirus, Windows Search) briefly holding the save file is not a failure a player
 * should see (owner 1.2): the rename retries once, and only a failure that keeps happening warns.
 */
public class SaveFailureQuietTest
{
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void aLockedRenameIsTriedOnceMore() throws Exception
    {
        Path dir = temporary.newFolder("rename").toPath();
        Files.writeString(dir.resolve("sessions.next.json"), "{}", StandardCharsets.UTF_8);
        int[] attempts = {0};
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(dir), false)
        {
            @Override
            void atomicMove(Filepath source, Filepath target) throws java.io.IOException
            {
                if (attempts[0]++ == 0)
                {
                    throw new AccessDeniedException(String.valueOf(source));
                }
                super.atomicMove(source, target);
            }
        };
        Filepath root = FilepathTestSupport.root(dir);
        repository.replaceFile(root.joinSegment("sessions.next.json"), root.joinSegment("sessions.json"));
        assertEquals("one retry", 2, attempts[0]);
        assertTrue("the save landed", Files.exists(dir.resolve("sessions.json")));
    }

    @Test
    public void onlyAFailureThatKeepsHappeningWarns() throws Exception
    {
        boolean[] fail = {true};
        SessionRepository repository = new SessionRepository(new Gson(),
            FilepathTestSupport.root(temporary.newFolder("writer").toPath()), false)
        {
            @Override
            synchronized boolean save(WriteIntent intent)
            {
                return !fail[0];
            }
        };
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);
        try
        {
            for (int attempt = 1; attempt <= 2; attempt++)
            {
                save(writer, repository, attempt);
                assertNotEquals("a one-off failure stays quiet (attempt " + attempt + ")",
                    SaveStatus.State.FAILED, writer.getStatus().state);
            }
            save(writer, repository, 3);
            assertEquals("the third failure in a row warns", SaveStatus.State.FAILED, writer.getStatus().state);

            fail[0] = false;
            save(writer, repository, 4);
            assertEquals(SaveStatus.State.OK, writer.getStatus().state);
            fail[0] = true;
            save(writer, repository, 5);
            assertNotEquals("a success starts the count again", SaveStatus.State.FAILED, writer.getStatus().state);
        }
        finally
        {
            writer.shutdown(Duration.ofSeconds(5));
        }
    }

    private static void save(OrderedPersistenceWriter writer, SessionRepository repository, long revision)
    {
        SavedState state = new SavedState();
        state.setRevision(revision);
        writer.submit(new WriteIntent(null, repository.scopeGeneration, repository.lastKnownDiskRevision, state, null));
        assertTrue(writer.flush(Duration.ofSeconds(5)));
    }
}
