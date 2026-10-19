package com.gpmanager;

import com.google.gson.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Blocks the actual serializer while another capture and mutation run. No live client. */
public class SaveEncodingOwnershipTest {
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
            engine.getActiveSession().rename("Second snapshot");
            java.util.concurrent.ExecutorService captures=java.util.concurrent.Executors.newSingleThreadExecutor();
            WriteIntent second;
            try { second=captures.submit(()->disk.currentIntent(true)).get(2,TimeUnit.SECONDS); }
            finally { captures.shutdownNow(); }
            writer.submit(second);
            engine.getActiveSession().rename("After capture");
            release.countDown();
            if(!writer.flush(Duration.ofSeconds(5)))throw new AssertionError("writer did not drain");
            if(failure.get()!=null)throw new AssertionError(failure.get());
            SavedState loaded=repository.load();
            if(!loaded.getActiveSession().getName().equals("Second snapshot"))throw new AssertionError("snapshot mutated or latest was lost");
            if(loaded.getActiveSession().metrics(1L).net!=500L)throw new AssertionError("booked value changed");
            if(writer.getStatus().state!=SaveStatus.State.OK)throw new AssertionError(writer.getStatus().detail);

        }finally {release.countDown();writer.shutdown(Duration.ofSeconds(5));}
    }
}
