package com.gpmanager.persistence;

import com.google.gson.Gson;
import com.gpmanager.model.ProfitSession;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
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
        return new SavedState(new ProfitSession(name, 1_000L), Collections.emptyList());
    }

    @Test
    public void damagedPrimaryRecoversPreviousSaveAndPreservesEvidence() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), directory);
        repository.save(state("Previous"));
        repository.save(state("Latest"));
        Files.writeString(directory.resolve("sessions.json"), "{broken");

        assertEquals("Previous", repository.load().getActiveSession().getName());
        try (java.util.stream.Stream<Path> paths = Files.list(directory))
        {
            assertTrue(paths.anyMatch(path -> path.getFileName().toString().startsWith("sessions.corrupt-")));
        }
        repository.save(state("Recovered"));
        assertEquals("Recovered", repository.load().getActiveSession().getName());
    }

    @Test
    public void missingOrEmptyPrimaryRecoversBackup() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), directory);
        repository.save(state("Previous"));
        repository.save(state("Latest"));
        Files.delete(directory.resolve("sessions.json"));
        assertEquals("Previous", repository.load().getActiveSession().getName());
        Files.writeString(directory.resolve("sessions.json"), "");
        assertEquals("Previous", repository.load().getActiveSession().getName());
    }

    @Test
    public void failedPrimaryReplacementKeepsCommittedState() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), directory);
        repository.save(state("Committed"));
        SessionRepository failing = new SessionRepository(new Gson(), directory)
        {
            @Override
            void replaceFile(Path source, Path target) throws IOException
            {
                if (target.getFileName().toString().equals("sessions.json"))
                {
                    throw new IOException("Simulated full disk during replacement");
                }
                super.replaceFile(source, target);
            }
        };
        failing.save(state("Uncommitted"));
        assertEquals("Committed", repository.load().getActiveSession().getName());
        assertTrue(Files.readString(directory.resolve("sessions.backup.json")).contains("Committed"));
    }

    @Test
    public void corruptPrimaryCannotPoisonGoodBackupOnSave() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), directory);
        repository.save(state("Good backup"));
        repository.save(state("Latest"));
        Files.writeString(directory.resolve("sessions.json"), "null");
        repository.save(state("New"));
        assertTrue(Files.readString(directory.resolve("sessions.backup.json")).contains("Good backup"));
        assertEquals("New", repository.load().getActiveSession().getName());
    }

    @Test
    public void longSessionSaveReloadPreservesTotalsAfterCompaction() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        ProfitSession session = new ProfitSession("Soak", 1_000L);
        for (int i = 0; i < 200; i++)
        {
            for (int row = 0; row < 50; row++)
            {
                session.addTransaction(new com.gpmanager.model.ProfitTransaction(
                    2_000L + i * 50L + row,
                    com.gpmanager.model.TransactionType.GAIN,
                    com.gpmanager.model.TrackingContext.GENERIC, "Gain", true,
                    Collections.singletonList(new com.gpmanager.model.ItemFlow(995, "Coins", 1, 1, 1))), 100);
            }
            SessionRepository writer = new SessionRepository(new Gson(), directory);
            writer.save(new SavedState(session, Collections.emptyList()));
            SavedState reloaded = new SessionRepository(new Gson(), directory).load();
            session = reloaded.getActiveSession();
            assertEquals((i + 1L) * 50L, session.metrics(20_000L, 60_000L).getNet());
            assertTrue(session.getTransactions().size() <= 100);
            assertEquals(SavedState.CURRENT_SCHEMA_VERSION, reloaded.getSchemaVersion());
        }
        assertEquals(9_900L, session.getCompactedTransactionCount());
    }

    @Test
    public void explicitGeneralAndCustomOwnersRoundTripWithoutMerging() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        ProfitSession general = new ProfitSession("General", 1_000L);
        ProfitSession custom = new ProfitSession("Custom", 2_000L);
        general.setProfitTargetGp(1_000_000_000L);
        custom.setProfitTargetGp(500_000_000L);
        ProfitSession archived = new ProfitSession("Archived", 500L);
        archived.close(900L);

        SessionRepository repository = new SessionRepository(new Gson(), directory);
        repository.save(new SavedState(general, custom, true, Collections.singletonList(archived)));
        SavedState restored = repository.load();

        assertEquals(general.getId(), restored.getGeneralSession().getId());
        assertEquals(custom.getId(), restored.getCustomSession().getId());
        assertEquals(Long.valueOf(1_000_000_000L), restored.getGeneralSession().getProfitTargetGp());
        assertEquals(Long.valueOf(500_000_000L), restored.getCustomSession().getProfitTargetGp());
        assertTrue(restored.isGeneralSuspendedByCustom());
        assertEquals(1, restored.getHistory().size());
        assertEquals(archived.getId(), restored.getHistory().get(0).getId());
    }

    @Test
    public void replacementStateReplacesBothPrimaryAndRecoveryCopies() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), directory);
        repository.save(state("Old"));
        repository.save(state("Older backup"));
        assertTrue(repository.replaceState(state("Fresh stopped General")));
        assertEquals("Fresh stopped General", repository.load().getActiveSession().getName());
        Files.delete(directory.resolve("sessions.json"));
        assertEquals("Fresh stopped General", repository.load().getActiveSession().getName());
    }
}
