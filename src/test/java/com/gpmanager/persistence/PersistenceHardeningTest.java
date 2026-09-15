package com.gpmanager.persistence;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gpmanager.GpManagerConfig;
import com.gpmanager.engine.ContainerSnapshot;
import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.engine.TransactionClassifier;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.SessionMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Pass 10 step 43: backup rotation and fallback, unknown-field preservation, lock-free saves, migration fuzz. */
public class PersistenceHardeningTest
{
    private static final int LOGS = 1519;
    private static final int SHARK = 385;
    private static final long DAY = 86_400_000L;

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private static SavedState state(String name, long revision)
    {
        SavedState s = new SavedState(new ProfitSession(name, 1_000L), Collections.emptyList());
        s.setRevision(revision);
        return s;
    }

    @Test
    public void rotationKeepsThreeGoodSavesAndFallsBackToTheNewestReadable() throws Exception
    {
        Path dir = temporary.newFolder("rotation").toPath();
        SessionRepository repository = new SessionRepository(new Gson(), dir);
        for (int i = 1; i <= 6; i++)
        {
            assertTrue(repository.save(state("Save " + i, 0L)));
        }
        Path backup = dir.resolve("sessions.backup.json");
        assertTrue(Files.exists(backup));
        assertTrue(Files.exists(dir.resolve("sessions.backup.json.1")));
        assertTrue(Files.exists(dir.resolve("sessions.backup.json.2")));
        assertTrue(Files.exists(dir.resolve("sessions.backup.json.3")));
        assertFalse(Files.exists(dir.resolve("sessions.backup.json.4")));
        // Newest first: backup = save 5, .1 = save 4, .2 = save 3, .3 = save 2.
        assertEquals("Save 5", new Gson().fromJson(Files.readString(backup), SavedState.class).getActiveSession().getName());
        assertEquals("Save 2", new Gson().fromJson(Files.readString(dir.resolve("sessions.backup.json.3")), SavedState.class).getActiveSession().getName());

        // Primary and the first backup both damaged: the load lands on .1 and says so.
        Files.writeString(dir.resolve("sessions.json"), "{not json", StandardCharsets.UTF_8);
        Files.writeString(backup, "", StandardCharsets.UTF_8);
        SessionRepository reopened = new SessionRepository(new Gson(), dir);
        SavedState loaded = reopened.load();
        assertEquals("Save 4", loaded.getActiveSession().getName());
        assertEquals("sessions.backup.json.1", reopened.getLastRecoveredFrom());

        // A clean load afterwards clears the marker.
        assertTrue(reopened.save(state("Save 7", 0L)));
        assertEquals("Save 7", reopened.load().getActiveSession().getName());
        assertEquals("", reopened.getLastRecoveredFrom());
    }

    @Test
    public void resetDropsRotatedBackupsSoClearedDataCannotReturn() throws Exception
    {
        Path dir = temporary.newFolder("reset").toPath();
        SessionRepository repository = new SessionRepository(new Gson(), dir);
        for (int i = 1; i <= 5; i++)
        {
            assertTrue(repository.save(state("Before " + i, 0L)));
        }
        assertTrue(Files.exists(dir.resolve("sessions.backup.json.3")));
        assertTrue(repository.replaceState(state("Fresh", 0L)));
        assertFalse(Files.exists(dir.resolve("sessions.backup.json.1")));
        assertFalse(Files.exists(dir.resolve("sessions.backup.json.2")));
        assertFalse(Files.exists(dir.resolve("sessions.backup.json.3")));
        // Even with primary and backup gone, nothing older than the reset can come back.
        Files.deleteIfExists(dir.resolve("sessions.json"));
        Files.deleteIfExists(dir.resolve("sessions.backup.json"));
        SavedState loaded = new SessionRepository(new Gson(), dir).load();
        assertTrue(loaded == null || loaded.getActiveSession() == null || !loaded.getActiveSession().getName().startsWith("Before"));
    }

    @Test
    public void unknownJsonFieldsSurviveLoadRestoreAndSaveAtEveryLevel() throws Exception
    {
        GpManagerEngine engine = engine();
        long now = 10L * DAY;
        history(engine, "Vorkath", now - DAY, 30 * 60_000L, 40L);
        Gson gson = UnknownFieldPreservation.wrap(new Gson());
        JsonObject json = new JsonParser().parse(gson.toJson(engine.createSavedState())).getAsJsonObject();

        // A newer build wrote three things this one does not know: on the state, a session and a receipt.
        json.addProperty("futureTopLevel", "kept");
        JsonArray history = json.getAsJsonArray("history");
        JsonObject session = history.get(0).getAsJsonObject();
        JsonObject nested = new JsonObject();
        nested.addProperty("a", 1);
        session.add("futureSessionField", nested);
        JsonObject receipt = session.getAsJsonArray("transactions").get(0).getAsJsonObject();
        receipt.addProperty("futureReceiptField", 42);
        // A known field is never overridden by a stray duplicate, and a null-valued known field is not "unknown".
        json.add("profileTimeZoneId", com.google.gson.JsonNull.INSTANCE);

        SavedState loaded = gson.fromJson(json, SavedState.class);
        assertEquals("kept", loaded.getUnknownJsonFields().get("futureTopLevel").getAsString());

        GpManagerEngine second = engine();
        second.restore(loaded, now);
        SavedState written = second.createSavedState();
        JsonObject out = new JsonParser().parse(gson.toJson(written)).getAsJsonObject();
        assertEquals("kept", out.get("futureTopLevel").getAsString());
        JsonObject outSession = null;
        for (JsonElement e : out.getAsJsonArray("history"))
        {
            if ("Vorkath".equals(e.getAsJsonObject().get("name").getAsString()))
            {
                outSession = e.getAsJsonObject();
            }
        }
        assertNotNull(outSession);
        assertEquals(1, outSession.getAsJsonObject("futureSessionField").get("a").getAsInt());
        assertEquals(42, outSession.getAsJsonArray("transactions").get(0).getAsJsonObject().get("futureReceiptField").getAsInt());
        // The build's own field still wins over the bag.
        assertTrue(out.has("profileTimeZoneId"));
        assertFalse(out.get("profileTimeZoneId").isJsonNull());
    }

    @Test
    public void backgroundSaveNeverHoldsTheEngineLock() throws Exception
    {
        GpManagerEngine engine = engine();
        long now = 400L * DAY;
        for (int i = 0; i < 300; i++)
        {
            history(engine, "Session " + i, now - (300 - i) * 3_600_000L, 20 * 60_000L, 40L + i);
        }
        Path dir = temporary.newFolder("lock").toPath();
        SessionRepository repository = new SessionRepository(new Gson(), dir)
        {
            @Override
            public synchronized boolean save(WriteIntent intent)
            {
                // A deliberately slow disk: the engine must stay responsive throughout.
                try
                {
                    Thread.sleep(400L);
                }
                catch (InterruptedException ex)
                {
                    Thread.currentThread().interrupt();
                }
                return super.save(intent);
            }
        };
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);
        writer.start();
        try
        {
            SavedState detached = engine.createSavedState();
            writer.submit(detached);
            long worst = 0L;
            int reads = 0;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(350L);
            while (System.nanoTime() < deadline)
            {
                long t0 = System.nanoTime();
                engine.getMetrics(now);
                engine.getOverallTotals(now);
                worst = Math.max(worst, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
                reads++;
            }
            // The disk sleeps 400 ms: a save that held the engine lock would stall a read for the whole
            // of it and let almost nothing through. A slow CI runner can still add scheduler noise, so
            // the bound is the injected hold, not a wall-clock guess.
            assertTrue("engine reads stalled " + worst + " ms while a save was in flight", worst < 400L);
            assertTrue("only " + reads + " reads completed while a save was in flight", reads >= 3);
            assertTrue(writer.flush(Duration.ofSeconds(10)));
            assertEquals(SaveStatus.State.OK, writer.getStatus().getState());
            assertTrue(writer.getStatus().getLastBytes() > 0L);
            assertTrue(writer.getStatus().getMaxDurationMillis() >= 400L);
        }
        finally
        {
            writer.shutdown(Duration.ofSeconds(5));
        }
    }

    /** Older-schema tolerance: random states degraded to schema 14/18/20/22 shapes must restore under the invariants. */
    @Test
    public void migrationDegradationFuzz()
    {
        int seeds = Integer.getInteger("gp.fuzz.seeds", 200);
        int[] schemas = {14, 18, 20, 22};
        Set<String> keepState = new HashSet<>(Arrays.asList("history", "generalSession", "customSession", "activeSession", "schemaVersion", "revision"));
        Set<String> keepSession = new HashSet<>(Arrays.asList("name", "transactions", "id", "startedAtEpochMillis"));
        Set<String> keepReceipt = new HashSet<>(Arrays.asList("flows", "type", "timestampEpochMillis"));
        Gson gson = UnknownFieldPreservation.wrap(new Gson());
        for (int seed = 0; seed < seeds; seed++)
        {
            Random random = new Random(seed);
            try
            {
                GpManagerEngine source = engine();
                long now = 500L * DAY + random.nextInt(1000) * 60_000L;
                int sessions = 1 + random.nextInt(12);
                for (int i = 0; i < sessions; i++)
                {
                    long start = now - (1 + random.nextInt(390)) * DAY - random.nextInt(12) * 3_600_000L;
                    long length = (5 + random.nextInt(240)) * 60_000L;
                    history(source, random.nextBoolean() ? "Vorkath" : "Zulrah " + i, start, length, 1 + random.nextInt(500));
                    if (random.nextInt(4) == 0)
                    {
                        source.setHistorySessionExcluded(source.getHistory().get(0).getId(), true);
                    }
                }
                JsonObject json = new JsonParser().parse(gson.toJson(source.createSavedState())).getAsJsonObject();
                int schema = schemas[random.nextInt(schemas.length)];
                json.addProperty("schemaVersion", schema);
                degrade(json, keepState, random);
                for (String owner : Arrays.asList("generalSession", "customSession", "activeSession"))
                {
                    if (json.has(owner) && json.get(owner).isJsonObject())
                    {
                        degradeSession(json.getAsJsonObject(owner), keepSession, keepReceipt, random);
                    }
                }
                if (json.has("history"))
                {
                    for (JsonElement e : json.getAsJsonArray("history"))
                    {
                        if (e.isJsonObject())
                        {
                            degradeSession(e.getAsJsonObject(), keepSession, keepReceipt, random);
                        }
                    }
                }

                SavedState degraded = gson.fromJson(json, SavedState.class);
                GpManagerEngine target = engine();
                target.restore(degraded, now);
                invariants(target, now, sessions);
                // Whatever came out restores again without loss of the invariants.
                GpManagerEngine again = engine();
                again.restore(gson.fromJson(gson.toJson(target.createSavedState()), SavedState.class), now);
                invariants(again, now, sessions);
                assertEquals(target.getHistory().size(), again.getHistory().size());
            }
            catch (Throwable t)
            {
                throw new AssertionError("migration fuzz failed for seed " + seed + " (rerun with -Dgp.fuzz.seeds=" + (seed + 1) + ")", t);
            }
        }
    }

    private static void degrade(JsonObject object, Set<String> keep, Random random)
    {
        List<String> keys = new ArrayList<>(object.keySet());
        for (String key : keys)
        {
            if (!keep.contains(key) && random.nextInt(3) == 0)
            {
                object.remove(key);
            }
        }
    }

    private static void degradeSession(JsonObject session, Set<String> keepSession, Set<String> keepReceipt, Random random)
    {
        degrade(session, keepSession, random);
        if (session.has("transactions") && session.get("transactions").isJsonArray())
        {
            for (JsonElement e : session.getAsJsonArray("transactions"))
            {
                if (e.isJsonObject())
                {
                    degrade(e.getAsJsonObject(), keepReceipt, random);
                }
            }
        }
    }

    private static void invariants(GpManagerEngine engine, long now, int sessionsPlayed)
    {
        List<ProfitSession> history = engine.getHistory();
        assertTrue(history.size() <= sessionsPlayed + 1);
        Set<String> ids = new HashSet<>();
        for (ProfitSession s : history)
        {
            assertTrue("duplicate session id " + s.getId(), ids.add(s.getId()));
            assertTrue(s.getElapsedMillis(now) >= 0L);
            assertNotNull(engine.getHistorySummary(s.getId(), now));
        }
        assertTrue(engine.getOverallTotals(now).getActiveMillis() >= 0L);
        assertNotNull(engine.getOverallToday(now));
        assertNotNull(engine.getInsightsWindow(30, now));
        assertNotNull(engine.getRecords(now));
        for (com.gpmanager.model.DailyRollup day : engine.getDailyRollups(java.time.LocalDate.now().minusDays(400), java.time.LocalDate.now()))
        {
            assertTrue(day.getActiveMillis() >= 0L);
            assertTrue(day.getActiveMillis() <= DAY + 3_600_000L);
        }
        if (engine.getGeneralSession() == null && engine.getActiveSession() == null && history.isEmpty())
        {
            fail("everything vanished");
        }
    }

    // ── fixture ─────────────────────────────────────────────────────────────

    private static String history(GpManagerEngine engine, String name, long startedAt, long length, long logs)
    {
        engine.startCustomSession(name, SessionMode.AUTO, startedAt);
        String id = engine.getActiveSession().getId();
        engine.setBaseline(new ContainerSnapshot(Collections.emptyMap()));
        settle(engine, new ContainerSnapshot(map(LOGS, logs)), startedAt + 60_000L);
        assertTrue(engine.finishCustomSession(startedAt + length));
        return id;
    }

    private static GpManagerEngine engine()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 0; }
            @Override public int minimumTransactionValue() { return 1; }
        };
        return new GpManagerEngine(deltas ->
        {
            List<ItemFlow> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                int id = delta.getKey();
                int price = id == LOGS ? 48 : 800;
                flows.add(new ItemFlow(id, id == LOGS ? "Willow logs" : "Shark", delta.getValue(), price,
                    delta.getValue() * price, ItemPriceSource.GRAND_EXCHANGE));
            }
            return flows;
        }, new TransactionClassifier(), config);
    }

    private static ProfitTransaction settle(GpManagerEngine engine, ContainerSnapshot snapshot, long now)
    {
        engine.markInventoryDirty();
        ProfitTransaction first = engine.processIfDirty(snapshot, now);
        ProfitTransaction settled = engine.processIfDirty(snapshot, now + 600L);
        return settled == null ? first : settled;
    }

    private static Map<Integer, Long> map(Object... pairs)
    {
        Map<Integer, Long> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
        {
            map.put((Integer) pairs[i], (Long) pairs[i + 1]);
        }
        return map;
    }
}
