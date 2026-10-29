package com.gpmanager;

import com.google.gson.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Blocks the actual serializer while another capture and mutation run. No live client. */
public class SaveEncodingOwnershipTest {
    @org.junit.Test public void manualBackupEncodingReleasesEngineAndKeepsItsDetachedSnapshot() throws Exception {
        Gson plain = new Gson();
        Engine engine = TargetFreshStartTest.engine();
        engine.profile = "rsprofile.backup-probe";
        engine.session().rename("Before backup");
        TargetFreshStartTest.coins(engine, 1L, 500L);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean held = new AtomicBoolean();
        Gson gson = new GsonBuilder().registerTypeAdapter(Session.class,
                (JsonSerializer<Session>) (value, type, context) -> {
                    held.set(Thread.holdsLock(engine));
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new JsonIOException("backup serializer gate timed out");
                    } catch (InterruptedException ex) { throw new JsonIOException(ex); }
                    return plain.toJsonTree(value);
                }).create();
        JsonCodec.bind(gson);
        Path directory = Files.createTempDirectory(Path.of("build"), "backup-encoding-");
        SessionRepository repository = new SessionRepository(gson, FilepathTestSupport.root(directory), false);
        Persistence disk = new Persistence(null, null, repository, new OrderedPersistenceWriter(repository, null), engine);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<net.runelite.client.util.Filepath> backup = workers.submit(() -> disk.writeBackup(1_700_000_000_000L));
        try {
            org.junit.Assert.assertTrue(entered.await(3, TimeUnit.SECONDS));
            org.junit.Assert.assertFalse("manual backup JSON must not hold the engine", held.get());
            workers.submit(() -> {
                synchronized (engine) {
                    engine.session().rename("After capture");
                    TargetFreshStartTest.coins(engine, 2L, 7L);
                }
            }).get(1, TimeUnit.SECONDS);
            release.countDown();
            SavedState saved = SessionRepository.parseBackup(plain, backup.get(3, TimeUnit.SECONDS));
            org.junit.Assert.assertEquals("Before backup", FixtureReads.currentSession(saved).name());
            org.junit.Assert.assertEquals("rsprofile.backup-probe", saved.ownerKey);
            org.junit.Assert.assertEquals(500L, FixtureReads.currentSession(saved).metrics(3L).net);
            org.junit.Assert.assertEquals(507L, engine.metrics(3L).net);
        } finally {
            release.countDown();
            workers.shutdown();
            org.junit.Assert.assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            JsonCodec.bind(plain);
        }
    }

    @org.junit.Test public void blockedEncodingDoesNotHoldTheEngineOrDelayAnotherCapture() throws Exception {
        Gson plain=new Gson();JsonCodec.bind(plain);
        Engine engine=TargetFreshStartTest.engine();
        TargetFreshStartTest.coins(engine,1L,500L);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicBoolean first=new AtomicBoolean(true);
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread caller=Thread.currentThread();
        Gson gson=new GsonBuilder().registerTypeAdapter(Session.class,(JsonSerializer<Session>)(value,type,context)-> {
            if(Thread.currentThread()==caller || Thread.holdsLock(engine)) {
                failure.set(new AssertionError("serializer on capture thread or under engine lock"));
                throw new JsonIOException("unsafe serializer ownership");
            }
            if(first.compareAndSet(true,false)) {
                entered.countDown();
                try {if(!release.await(5,TimeUnit.SECONDS))throw new JsonIOException("serializer gate timed out");}
                catch(InterruptedException ex){throw new JsonIOException(ex);}
            }
            return plain.toJsonTree(value);
        }).create();
        SessionRepository repository=new SessionRepository(gson,FilepathTestSupport.root(Files.createTempDirectory(Path.of("build"),"worker-probe-")),false);
        OrderedPersistenceWriter writer=new OrderedPersistenceWriter(repository);
        Persistence disk=new Persistence(null,null,repository,writer,engine);
        try {
            writer.submit(disk.currentIntent());
            if(!entered.await(3,TimeUnit.SECONDS))throw new AssertionError("worker did not enter serializer");
            engine.session().rename("Second snapshot");
            java.util.concurrent.ExecutorService captures=java.util.concurrent.Executors.newSingleThreadExecutor();
            WriteIntent second;
            try { second=captures.submit(()->disk.currentIntent()).get(2,TimeUnit.SECONDS); }
            finally { captures.shutdownNow(); }
            writer.submit(second);
            engine.session().rename("After capture");
            release.countDown();
            if(!writer.flush(Duration.ofSeconds(5)))throw new AssertionError("writer did not drain");
            if(failure.get()!=null)throw new AssertionError(failure.get());
            SavedState loaded=repository.load();
            if(!FixtureReads.currentSession(loaded).name().equals("Second snapshot"))throw new AssertionError("snapshot mutated or latest was lost");
            if(FixtureReads.currentSession(loaded).metrics(1L).net!=500L)throw new AssertionError("booked value changed");
            if(writer.status().state!=SaveStatus.State.OK)throw new AssertionError(writer.status().detail);

        }finally {release.countDown();writer.shutdown(Duration.ofSeconds(5));}
    }
}
