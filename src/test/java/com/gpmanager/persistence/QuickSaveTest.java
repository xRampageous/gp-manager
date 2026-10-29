package com.gpmanager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Full and quick saves capture fresh detached state; injected Gson retains its settings. */
public class QuickSaveTest
{
    private static final long T0 = 1_700_000_000_000L;

    @Test
    public void detachedSaveIsByteIdenticalUnderEveryGsonSetting() throws Exception
    {
        for (Gson gson : new Gson[] {new Gson(), new GsonBuilder().serializeNulls().create(),
            new GsonBuilder().disableHtmlEscaping().create(), new GsonBuilder().setPrettyPrinting().create()})
        {
            Engine engine = engine();
            Persistence coordinator = coordinator(gson, engine);
            assertEquals(plain(gson, engine), strip(PersistenceProbe.snapshotJson(coordinator, null, false)));
            assertEquals(plain(gson, engine), strip(PersistenceProbe.snapshotJson(coordinator, null, true)));
        }
    }

    @Test
    public void emptyHistoryAndNoSessionsStillMatch() throws Exception
    {
        Gson gson = new Gson();
        Engine engine = new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
        Persistence coordinator = coordinator(gson, engine);
        assertEquals(plain(gson, engine), strip(PersistenceProbe.snapshotJson(coordinator, null, true)));
    }

    @Test
    public void quickSaveFollowsChangesThatStampTheSession() throws Exception
    {
        Gson gson = new Gson();
        Engine engine = engine();
        Persistence coordinator = coordinator(gson, engine);
        PersistenceProbe.snapshotJson(coordinator, null, false);
        Session closed = engine.history.get(1);
        String row = closed.transactions().get(3).id();
        closed.correctTransaction(row, Correction.IGNORE, "test");
        assertEquals(plain(gson, engine), strip(PersistenceProbe.snapshotJson(coordinator, null, true)));
        closed.compactBefore(closed.startedAt + 5_000L, null);
        assertEquals(plain(gson, engine), strip(PersistenceProbe.snapshotJson(coordinator, null, true)));
        Session newlyClosed = new Session("Fresh", T0, SessionMode.GENERAL);
        newlyClosed.addTransaction(receipt(T0 + 1L), 2_000, false);
        newlyClosed.close(T0 + 10L);
        engine.history.add(0, newlyClosed);
        assertEquals(plain(gson, engine), strip(PersistenceProbe.snapshotJson(coordinator, null, true)));
        engine.session().addTransaction(receipt(T0 + 2L), 2_000, false);
        assertEquals(plain(gson, engine), strip(PersistenceProbe.snapshotJson(coordinator, null, true)));
    }

    @Test
    public void everyQuickAndFullSaveCapturesClosedHistoryAfresh() throws Exception
    {
        Gson gson = new Gson();
        Engine engine = engine();
        Persistence coordinator = coordinator(gson, engine);
        coordinator.currentIntent();
        for (int quick = 1; quick <= 12; quick++)
        {
            engine.history.get(0).rename("Renamed " + quick);
            WriteIntent captured = coordinator.currentIntent();
            assertNull("encoding belongs to the writer", captured.json);
            assertEquals(plain(gson, engine), strip(gson.toJson(captured.state)));
        }
        engine.history.get(0).rename("Again");
        assertEquals(plain(gson, engine), strip(gson.toJson(coordinator.currentIntent().state)));
        assertEquals(plain(gson, engine), strip(gson.toJson(coordinator.currentIntent().state)));
    }

    private static String plain(Gson gson, Engine engine)
    {
        return strip(gson.toJson(engine.savedState()));
    }

    /** Each snapshot stamps the time, and a save's header its revision; compare the rest. */
    private static String strip(String json)
    {
        return json.replaceFirst("\"savedAtEpochMillis\": ?\\d+,\\s*\"revision\": ?\\d+,", "");
    }

    private static Engine engine()
    {
        Engine engine = new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
        for (int grind = 0; grind < 5; grind++)
        {
            Session done = new Session("Grind <" + grind + "> & 'co'", T0 - (5 - grind) * 3_600_000L, SessionMode.GENERAL);
            for (int i = 0; i < 8; i++)
            {
                done.addTransaction(receipt(done.startedAt + i * 1_000L), 2_000, false);
            }
            done.close(done.startedAt + 30_000L);
            engine.history.add(0, done);
        }
        engine.history.add(2, null);
        engine.startCustom("Greater Nechryael", T0);
        engine.session().addTransaction(receipt(T0), 2_000, false);
        return engine;
    }

    private static Persistence coordinator(Gson gson, Engine engine) throws Exception
    {
        SessionRepository repository = new SessionRepository(gson,
            FilepathTestSupport.root(Files.createTempDirectory("gp-quick")), false);
        return new Persistence(null, null, repository, new OrderedPersistenceWriter(repository, null), engine);
    }

    private static Transaction receipt(long at)
    {
        return new Transaction(at, TransactionType.LOOT, Context.LOOT, "", "Guard", true,
            Collections.singletonList(new Flow(560, "Death rune", 46L, 187, 8_602L)), Confidence.CONFIRMED, "test", null);
    }
}
