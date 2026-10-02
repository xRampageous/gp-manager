package com.gpmanager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * 1.1 save stutter: a save writes each history session on its own and splices them into the
 * bytes one toJson gives; the periodic gameplay save reuses a closed session's JSON until it
 * changes, and every 10th quick save writes everything afresh.
 */
public class QuickSaveTest
{
    private static final long T0 = 1_700_000_000_000L;

    @Test
    public void splicedSaveIsByteIdenticalUnderEveryGsonSetting() throws Exception
    {
        for (Gson gson : new Gson[] {new Gson(), new GsonBuilder().serializeNulls().create(),
            new GsonBuilder().disableHtmlEscaping().create(), new GsonBuilder().setPrettyPrinting().create()})
        {
            Am engine = engine();
            Ei coordinator = coordinator(gson, engine);
            assertEquals(plain(gson, engine), strip(coordinator.aiw(null, false)));
            assertEquals(plain(gson, engine), strip(coordinator.aiw(null, true)));
        }
    }

    @Test
    public void emptyHistoryAndNoSessionsStillMatch() throws Exception
    {
        Gson gson = new Gson();
        Am engine = new Am(deltas -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
        Ei coordinator = coordinator(gson, engine);
        assertEquals(plain(gson, engine), strip(coordinator.aiw(null, true)));
    }

    @Test
    public void quickSaveFollowsChangesThatStampTheSession() throws Exception
    {
        Gson gson = new Gson();
        Am engine = engine();
        Ei coordinator = coordinator(gson, engine);
        coordinator.aiw(null, false);
        Ad closed = engine.history.get(1);
        String row = closed.getTransactions().get(3).getId();
        closed.qi(row, Ah.IGNORE, T0, "test");
        assertEquals(plain(gson, engine), strip(coordinator.aiw(null, true)));
        closed.pj(closed.startedAtEpochMillis + 5_000L, null);
        assertEquals(plain(gson, engine), strip(coordinator.aiw(null, true)));
        Ad newlyClosed = new Ad("Fresh", T0);
        newlyClosed.kf(receipt(T0 + 1L), 2_000);
        newlyClosed.close(T0 + 10L);
        engine.history.add(0, newlyClosed);
        assertEquals(plain(gson, engine), strip(coordinator.aiw(null, true)));
        engine.getActiveSession().kf(receipt(T0 + 2L), 2_000);
        assertEquals(plain(gson, engine), strip(coordinator.aiw(null, true)));
    }

    @Test
    public void everyTenthQuickSaveAndEveryFullSaveWriteEverythingAfresh() throws Exception
    {
        Gson gson = new Gson();
        Am engine = engine();
        Ei coordinator = coordinator(gson, engine);
        coordinator.qn(false);
        // A rename never stamps the session: only sidebar edits rename, and they save in full.
        engine.history.get(0).rename("Renamed");
        for (int quick = 1; quick < 10; quick++)
        {
            assertNotEquals(plain(gson, engine), strip(coordinator.qn(true).json));
        }
        assertEquals(plain(gson, engine), strip(coordinator.qn(true).json));
        engine.history.get(0).rename("Again");
        assertEquals(plain(gson, engine), strip(coordinator.qn(false).json));
        assertEquals(plain(gson, engine), strip(coordinator.qn(true).json));
    }

    private static String plain(Gson gson, Am engine)
    {
        return strip(gson.toJson(engine.qm()));
    }

    /** Each snapshot stamps the time, and a save's header its revision; compare the rest. */
    private static String strip(String json)
    {
        return json.replaceFirst("\"savedAtEpochMillis\": ?\\d+,\\s*\"revision\": ?\\d+,", "");
    }

    private static Am engine()
    {
        Am engine = new Am(deltas -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
        for (int grind = 0; grind < 5; grind++)
        {
            Ad done = new Ad("Grind <" + grind + "> & 'co'", T0 - (5 - grind) * 3_600_000L);
            for (int i = 0; i < 8; i++)
            {
                done.kf(receipt(done.startedAtEpochMillis + i * 1_000L), 2_000);
            }
            done.close(done.startedAtEpochMillis + 30_000L);
            engine.history.add(0, done);
        }
        engine.history.add(2, null);
        engine.ajl("Greater Nechryael", Cx.GENERAL, T0);
        engine.getActiveSession().kf(receipt(T0), 2_000);
        return engine;
    }

    private static Ei coordinator(Gson gson, Am engine) throws Exception
    {
        SessionRepository repository = new SessionRepository(gson,
            FilepathTestSupport.root(Files.createTempDirectory("gp-quick")));
        return new Ei(null, null, repository, new OrderedPersistenceWriter(repository, null), engine);
    }

    private static Ac receipt(long at)
    {
        return new Ac(at, null, Ai.LOOT, Aj.LOOT, "", "Guard", true,
            Collections.singletonList(new Ab(560, "Death rune", 46L, 187, 8_602L)), Bd.CONFIRMED, "test", null);
    }
}
