package com.gpmanager;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
        SavedState s = new SavedState(new Session(name, 1_000L), null, false, Collections.emptyList());
        s.setRevision(revision);
        return s;
    }

    @Test
    public void oneCrashCopyHoldsThePreviousSaveAndRecoversIt() throws Exception
    {
        Path dir = temporary.newFolder("rotation").toPath();
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(dir));
        for (int i = 1; i <= 6; i++)
        {
            assertTrue(PersistenceProbe.save(repository, state("Save " + i, 0L)));
        }
        // Owner 2026-09-28: one crash copy (the previous good save), not four near-identical ones.
        Path backup = dir.resolve("sessions.backup.json");
        assertTrue(Files.exists(backup));
        assertFalse(Files.exists(dir.resolve("sessions.backup.json.1")));
        assertEquals("Save 5", new Gson().fromJson(Files.readString(backup), SavedState.class).getActiveSession().getName());

        // A damaged primary loads from the crash copy and says so.
        Files.writeString(dir.resolve("sessions.json"), "{not json", StandardCharsets.UTF_8);
        SessionRepository reopened = new SessionRepository(new Gson(), FilepathTestSupport.root(dir));
        SavedState loaded = reopened.load();
        assertEquals("Save 5", loaded.getActiveSession().getName());
        assertEquals("sessions.backup.json", reopened.lastRecoveredFrom);

        // A clean load afterwards clears the marker.
        assertTrue(PersistenceProbe.save(reopened, state("Save 7", 0L)));
        assertEquals("Save 7", reopened.load().getActiveSession().getName());
        assertEquals("", reopened.lastRecoveredFrom);
    }

    @Test
    public void resetDropsRotatedBackupsSoClearedDataCannotReturn() throws Exception
    {
        Path dir = temporary.newFolder("reset").toPath();
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(dir));
        for (int i = 1; i <= 5; i++)
        {
            assertTrue(PersistenceProbe.save(repository, state("Before " + i, 0L)));
        }
        assertTrue(PersistenceProbe.replaceStateDetailed(repository, state("Fresh", 0L)).isCommitted());
        assertFalse(Files.exists(dir.resolve("sessions.backup.json.1")));
        // Even with primary and backup gone, nothing older than the reset can come back.
        Files.deleteIfExists(dir.resolve("sessions.json"));
        Files.deleteIfExists(dir.resolve("sessions.backup.json"));
        SavedState loaded = new SessionRepository(new Gson(), FilepathTestSupport.root(dir)).load();
        assertTrue(loaded == null || loaded.getActiveSession() == null || !loaded.getActiveSession().getName().startsWith("Before"));
    }

    @Test
    public void unknownFieldsAreIgnoredOnLoadAndNeverWrittenBackWhileNewerSchemasAreRefused() throws Exception
    {
        // Hub v1 replaced the perpetual unknown-field compatibility layer with one supported
        // migration: stray fields on a same-schema payload are dropped, a newer schema is read-only.
        Engine engine = engine();
        long now = 10L * DAY;
        history(engine, "Vorkath", now - DAY, 30 * 60_000L, 40L);
        Gson gson = new Gson();
        JsonObject json = new JsonParser().parse(gson.toJson(engine.createSavedState())).getAsJsonObject();
        assertEquals(SavedState.CURRENT_SCHEMA_VERSION, json.get("schemaVersion").getAsInt());
        json.addProperty("futureTopLevel", "dropped");
        JsonArray history = json.getAsJsonArray("history");
        JsonObject session = history.get(0).getAsJsonObject();
        session.addProperty("futureSessionField", 1);
        session.getAsJsonArray("transactions").get(0).getAsJsonObject().addProperty("futureReceiptField", 42);

        SavedState loaded = SessionRepository.parseState(gson, json);
        assertFalse((loaded.schemaVersion > SavedState.CURRENT_SCHEMA_VERSION));
        assertTrue(loaded.isSupportedSchema());
        Engine second = engine();
        second.restore(loaded, now);
        assertEquals(engine.getHistory().get(0).metrics(now).net,
            second.getHistory().get(0).metrics(now).net);
        String out = gson.toJson(second.createSavedState());
        assertFalse(out.contains("futureTopLevel"));
        assertFalse(out.contains("futureSessionField"));
        assertFalse(out.contains("futureReceiptField"));

        json.addProperty("schemaVersion", SavedState.CURRENT_SCHEMA_VERSION + 1);
        SavedState newer = SessionRepository.parseState(gson, json);
        assertTrue((newer.schemaVersion > SavedState.CURRENT_SCHEMA_VERSION));

        JsonObject preRelease = new JsonParser().parse(gson.toJson(engine.createSavedState())).getAsJsonObject();
        preRelease.addProperty("schemaVersion", 23);
        SavedState preReleaseState = SessionRepository.parseState(gson, preRelease);
        assertFalse("a pre-release shape is read-only", preReleaseState.isSupportedSchema());
        assertFalse((preReleaseState.schemaVersion > SavedState.CURRENT_SCHEMA_VERSION));
    }

    @Test
    public void preReleaseSchemaIsReadOnlyAndNeverOverwritten() throws Exception
    {
        // No public build wrote a compact schema below 102: such a primary is refused read-only
        // and the file is left byte-for-byte as it was.
        Path directory = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory));
        SavedState version101 = state("Schema 101", 1L);
        version101.setSchemaVersion(101);
        Path primary = FilepathTestSupport.path(repository.getDataDirectory().joinSegment("sessions.json"));
        String original = new Gson().toJson(version101);
        Files.writeString(primary, original, StandardCharsets.UTF_8);

        assertFalse(repository.load().isSupportedSchema());
        assertFalse(repository.save(new WriteIntent(null, repository.scopeGeneration, 1L,
            state("Current", 2L))));
        assertEquals(original, Files.readString(primary, StandardCharsets.UTF_8));
    }

    @Test
    public void backgroundSaveNeverHoldsTheEngineLock() throws Exception
    {
        Engine engine = engine();
        long now = 400L * DAY;
        for (int i = 0; i < 300; i++)
        {
            history(engine, "Session " + i, now - (300 - i) * 3_600_000L, 20 * 60_000L, 40L + i);
        }
        Path dir = temporary.newFolder("lock").toPath();
        CountDownLatch inSave = new CountDownLatch(1);
        CountDownLatch readDone = new CountDownLatch(1);
        AtomicBoolean readDuringSave = new AtomicBoolean();
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(dir))
        {
            @Override
            public synchronized boolean save(WriteIntent intent)
            {
                // The save stays in flight until an engine read has finished. A save that held the
                // engine lock would block that read, and this wait would time out instead. No
                // wall-clock threshold, so a slow runner cannot fail it.
                inSave.countDown();
                try
                {
                    readDuringSave.set(readDone.await(5L, TimeUnit.SECONDS));
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
            writer.submit(new WriteIntent(null,
                repository.scopeGeneration, repository.lastKnownDiskRevision, detached));
            assertTrue("the save started", inSave.await(5L, TimeUnit.SECONDS));
            engine.getMetrics(now);
            readDone.countDown();
            assertTrue(writer.flush(Duration.ofSeconds(10)));
            assertTrue("an engine read finished while the save was in flight", readDuringSave.get());
            assertEquals(SaveStatus.State.OK, writer.getStatus().state);
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
        Gson gson = new Gson();
        for (int seed = 0; seed < seeds; seed++)
        {
            Random random = new Random(seed);
            try
            {
                Engine source = engine();
                long now = 500L * DAY + random.nextInt(1000) * 60_000L;
                int sessions = 1 + random.nextInt(12);
                for (int i = 0; i < sessions; i++)
                {
                    long start = now - (1 + random.nextInt(390)) * DAY - random.nextInt(12) * 3_600_000L;
                    long length = (5 + random.nextInt(240)) * 60_000L;
                    history(source, random.nextBoolean() ? "Vorkath" : "Zulrah " + i, start, length, 1 + random.nextInt(500));
                    if (random.nextInt(4) == 0)
                    {
                        source.getHistorySession(source.getHistory().get(0).getId()).setExcludedFromAverages(true);
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
                Engine target = engine();
                target.restore(degraded, now);
                invariants(target, now, sessions);
                // Whatever came out restores again without loss of the invariants.
                Engine again = engine();
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

    private static void invariants(Engine engine, long now, int sessionsPlayed)
    {
        List<Session> history = engine.getHistory();
        assertTrue(history.size() <= sessionsPlayed + 1);
        Set<String> ids = new HashSet<>();
        for (Session s : history)
        {
            assertTrue("duplicate session id " + s.getId(), ids.add(s.getId()));
            assertTrue(s.getElapsedMillis(now) >= 0L);
            assertNotNull(engine.getHistoryMetrics(s.getId(), now));
        }
        if (engine.getGeneralSession() == null && engine.getActiveSession() == null && history.isEmpty())
        {
            fail("everything vanished");
        }
    }

    // ── fixture ─────────────────────────────────────────────────────────────

    private static String history(Engine engine, String name, long startedAt, long length, long logs)
    {
        engine.startCustomSession(name, SessionMode.AUTO, startedAt);
        String id = engine.getActiveSession().getId();
        engine.setBaseline(new ContainerSnapshot(Collections.emptyMap()));
        settle(engine, new ContainerSnapshot(map(LOGS, logs)), startedAt + 60_000L);
        assertTrue(engine.finishCustomSession(startedAt + length));
        return id;
    }

    private static Engine engine()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 0; }
        };
        return new Engine(deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                int id = delta.getKey();
                int price = id == LOGS ? 48 : 800;
                flows.add(new Flow(id, id == LOGS ? "Willow logs" : "Shark", delta.getValue(), price,
                    delta.getValue() * price, PriceSource.GRAND_EXCHANGE));
            }
            return flows;
        }, new TransactionClassifier(), config);
    }

    private static Transaction settle(Engine engine, ContainerSnapshot snapshot, long now)
    {
        engine.markInventoryDirty();
        Transaction first = engine.processIfDirty(snapshot, now);
        Transaction settled = engine.processIfDirty(snapshot, now + 600L);
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
