package com.gpmanager;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SessionRepositoryRecoveryTest
{
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private SavedState state(String name)
    {
        return new SavedState(new Session(name, 1_000L, SessionMode.GENERAL), null, Collections.emptyList());
    }

    @Test
    public void damagedPrimaryRecoversPreviousSaveAndPreservesEvidence() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false);
        PersistenceProbe.save(repository, state("Previous"));
        PersistenceProbe.save(repository, state("Latest"));
        Files.writeString(directory.resolve("sessions.json"), "{broken");

        assertEquals("Previous", repository.load().currentSession().name());
        try (java.util.stream.Stream<Path> paths = Files.list(directory))
        {
            assertTrue(paths.anyMatch(path -> path.getFileName().toString().startsWith("sessions.corrupt-")));
        }
        PersistenceProbe.save(repository, state("Recovered"));
        assertEquals("Recovered", repository.load().currentSession().name());
    }

    @Test
    public void missingOrEmptyPrimaryRecoversBackup() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false);
        PersistenceProbe.save(repository, state("Previous"));
        PersistenceProbe.save(repository, state("Latest"));
        Files.delete(directory.resolve("sessions.json"));
        assertEquals("Previous", repository.load().currentSession().name());
        Files.writeString(directory.resolve("sessions.json"), "");
        assertEquals("Previous", repository.load().currentSession().name());
    }

    @Test
    public void failedPrimaryReplacementKeepsCommittedState() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false);
        PersistenceProbe.save(repository, state("Committed"));
        SessionRepository failing = new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false)
        {
            @Override
            void replaceFile(Filepath source, Filepath target) throws IOException
            {
                if (target.getFileName().toString().equals("sessions.json"))
                {
                    throw new IOException("Simulated full disk during replacement");
                }
                super.replaceFile(source, target);
            }
        };
        PersistenceProbe.save(failing, state("Uncommitted"));
        assertEquals("Committed", repository.load().currentSession().name());
        assertTrue(Files.readString(directory.resolve("sessions.backup.json")).contains("Committed"));
    }

    @Test
    public void corruptPrimaryCannotPoisonGoodBackupOnSave() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false);
        PersistenceProbe.save(repository, state("Good backup"));
        PersistenceProbe.save(repository, state("Latest"));
        Files.writeString(directory.resolve("sessions.json"), "null");
        PersistenceProbe.save(repository, state("New"));
        assertTrue(Files.readString(directory.resolve("sessions.backup.json")).contains("Good backup"));
        assertEquals("New", repository.load().currentSession().name());
    }

    @Test
    public void longSessionSaveReloadPreservesTotalsAfterCompaction() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        Session session = new Session("Soak", 1_000L, SessionMode.GENERAL);
        for (int i = 0; i < 200; i++)
        {
            for (int row = 0; row < 50; row++)
            {
                session.addTransaction(Tx.of(
                    2_000L + i * 50L + row,
                    TransactionType.GAIN,
                    Context.GENERIC, "Gain", true,
                    Collections.singletonList(new Flow(995, "Coins", 1, 1, 1))), 100, false);
            }
            SessionRepository writer = new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false);
            assertTrue("durable save cycle " + i, PersistenceProbe.saveThroughWriter(writer,
                new SavedState(session, null, Collections.emptyList())));
            SavedState reloaded = new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false).load();
            session = reloaded.currentSession();
            assertEquals((i + 1L) * 50L, session.metrics(20_000L).net);
            assertTrue(session.transactions().size() <= 100);
            assertEquals(SavedState.CURRENT_SCHEMA_VERSION, reloaded.schemaVersion);
        }
        assertEquals(9_900L, session.compactedTransactionCount);
    }

    @Test
    public void explicitGeneralAndCustomOwnersRoundTripWithoutMerging() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        Session general = new Session("General", 1_000L, SessionMode.GENERAL);
        Session custom = new Session("Custom", 2_000L, SessionMode.GENERAL);
        general.setNetTarget(1_000_000_000L, 0, 0);
        custom.setNetTarget(500_000_000L, 0, 0);
        Session archived = new Session("Archived", 500L, SessionMode.GENERAL);
        archived.close(900L);

        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false);
        PersistenceProbe.save(repository, new SavedState(general, custom, Collections.singletonList(archived)));
        SavedState restored = repository.load();

        assertEquals(general.id(), restored.generalSession.id());
        assertEquals(custom.id(), restored.customSession.id());
        assertEquals(Long.valueOf(1_000_000_000L), restored.generalSession.targetGp());
        assertEquals(Long.valueOf(500_000_000L), restored.customSession.targetGp());
        assertEquals(1, restored.history().size());
        assertEquals(archived.id(), restored.history().get(0).id());
    }

    @Test
    public void replacementStateReplacesBothPrimaryAndRecoveryCopies() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false);
        PersistenceProbe.save(repository, state("Old"));
        PersistenceProbe.save(repository, state("Older backup"));
        assertTrue(PersistenceProbe.replaceStateDetailed(repository, state("Fresh stopped General")).isCommitted());
        assertEquals("Fresh stopped General", repository.load().currentSession().name());
        Files.delete(directory.resolve("sessions.json"));
        assertEquals("Fresh stopped General", repository.load().currentSession().name());
    }
}
