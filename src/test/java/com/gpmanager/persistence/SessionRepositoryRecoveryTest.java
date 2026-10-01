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
        return new SavedState(new Ad(name, 1_000L), null, false, Collections.emptyList());
    }

    @Test
    public void damagedPrimaryRecoversPreviousSaveAndPreservesEvidence() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory));
        PersistenceProbe.save(repository, state("Previous"));
        PersistenceProbe.save(repository, state("Latest"));
        Files.writeString(directory.resolve("sessions.json"), "{broken");

        assertEquals("Previous", repository.load().getActiveSession().getName());
        try (java.util.stream.Stream<Path> paths = Files.list(directory))
        {
            assertTrue(paths.anyMatch(path -> path.getFileName().toString().startsWith("sessions.corrupt-")));
        }
        PersistenceProbe.save(repository, state("Recovered"));
        assertEquals("Recovered", repository.load().getActiveSession().getName());
    }

    @Test
    public void missingOrEmptyPrimaryRecoversBackup() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory));
        PersistenceProbe.save(repository, state("Previous"));
        PersistenceProbe.save(repository, state("Latest"));
        Files.delete(directory.resolve("sessions.json"));
        assertEquals("Previous", repository.load().getActiveSession().getName());
        Files.writeString(directory.resolve("sessions.json"), "");
        assertEquals("Previous", repository.load().getActiveSession().getName());
    }

    @Test
    public void failedPrimaryReplacementKeepsCommittedState() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory));
        PersistenceProbe.save(repository, state("Committed"));
        SessionRepository failing = new SessionRepository(new Gson(), FilepathTestSupport.root(directory))
        {
            @Override
            void afl(Filepath source, Filepath target) throws IOException
            {
                if (target.getFileName().toString().equals("sessions.json"))
                {
                    throw new IOException("Simulated full disk during replacement");
                }
                super.afl(source, target);
            }
        };
        PersistenceProbe.save(failing, state("Uncommitted"));
        assertEquals("Committed", repository.load().getActiveSession().getName());
        assertTrue(Files.readString(directory.resolve("sessions.backup.json")).contains("Committed"));
    }

    @Test
    public void corruptPrimaryCannotPoisonGoodBackupOnSave() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory));
        PersistenceProbe.save(repository, state("Good backup"));
        PersistenceProbe.save(repository, state("Latest"));
        Files.writeString(directory.resolve("sessions.json"), "null");
        PersistenceProbe.save(repository, state("New"));
        assertTrue(Files.readString(directory.resolve("sessions.backup.json")).contains("Good backup"));
        assertEquals("New", repository.load().getActiveSession().getName());
    }

    @Test
    public void longSessionSaveReloadPreservesTotalsAfterCompaction() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        Ad session = new Ad("Soak", 1_000L);
        for (int i = 0; i < 200; i++)
        {
            for (int row = 0; row < 50; row++)
            {
                session.kf(Tx.of(
                    2_000L + i * 50L + row,
                    Ai.GAIN,
                    Aj.GENERIC, "Gain", true,
                    Collections.singletonList(new Ab(995, "Coins", 1, 1, 1))), 100);
            }
            SessionRepository writer = new SessionRepository(new Gson(), FilepathTestSupport.root(directory));
            PersistenceProbe.save(writer, new SavedState(session, null, false, Collections.emptyList()));
            SavedState reloaded = new SessionRepository(new Gson(), FilepathTestSupport.root(directory)).load();
            session = reloaded.getActiveSession();
            assertEquals((i + 1L) * 50L, session.metrics(20_000L).net);
            assertTrue(session.getTransactions().size() <= 100);
            assertEquals(SavedState.CURRENT_SCHEMA_VERSION, reloaded.schemaVersion);
        }
        assertEquals(9_900L, session.compactedTransactionCount);
    }

    @Test
    public void explicitGeneralAndCustomOwnersRoundTripWithoutMerging() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        Ad general = new Ad("General", 1_000L);
        Ad custom = new Ad("Custom", 2_000L);
        general.setProfitTargetGp(1_000_000_000L);
        custom.setProfitTargetGp(500_000_000L);
        Ad archived = new Ad("Archived", 500L);
        archived.close(900L);

        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory));
        PersistenceProbe.save(repository, new SavedState(general, custom, true, Collections.singletonList(archived)));
        SavedState restored = repository.load();

        assertEquals(general.getId(), restored.generalSession.getId());
        assertEquals(custom.getId(), restored.customSession.getId());
        assertEquals(Long.valueOf(1_000_000_000L), restored.generalSession.getProfitTargetGp());
        assertEquals(Long.valueOf(500_000_000L), restored.customSession.getProfitTargetGp());
        assertTrue(restored.generalSuspendedByCustom);
        assertEquals(1, restored.getHistory().size());
        assertEquals(archived.getId(), restored.getHistory().get(0).getId());
    }

    @Test
    public void replacementStateReplacesBothPrimaryAndRecoveryCopies() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory));
        PersistenceProbe.save(repository, state("Old"));
        PersistenceProbe.save(repository, state("Older backup"));
        assertTrue(PersistenceProbe.replaceStateDetailed(repository, state("Fresh stopped General")).isCommitted());
        assertEquals("Fresh stopped General", repository.load().getActiveSession().getName());
        Files.delete(directory.resolve("sessions.json"));
        assertEquals("Fresh stopped General", repository.load().getActiveSession().getName());
    }
}
