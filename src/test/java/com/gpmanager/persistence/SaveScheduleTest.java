package com.gpmanager;

import com.google.gson.Gson;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class SaveScheduleTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();

    static SavedState named(String name,long revision){
        var state=new SavedState(new Session(name,1_000L,SessionMode.GENERAL),null,Collections.emptyList());
        state.setRevision(revision);return state;
    }

    @Test public void refusedScheduleDoesNotAdvanceTheMarker(){
        var engine=ReviewFixesTest.engine();
        var storage=new Persistence(null,null,null,null,engine){
            boolean accepted;
            @Override boolean scheduleSave(boolean quick){return accepted;}
            @Override SaveStatus saveStatus(){return SaveStatus.neverSaved();}
        };
        var plugin=new GpManagerPlugin();plugin.engine=engine;plugin.storage=storage;plugin.savedRevision=-1;
        plugin.persist(true);assertEquals(-1,plugin.savedRevision);
        storage.accepted=true;plugin.persist(true);assertEquals(engine.revision(),plugin.savedRevision);
        assertTrue(GpManagerPlugin.saveDue(50,engine.revision(),engine.revision()-1));
        assertFalse(GpManagerPlugin.saveDue(50,engine.revision(),engine.revision()));
        assertTrue(GpManagerPlugin.saveDue(500,engine.revision(),engine.revision()));
    }

    @Test public void writerReturnsTrueForCoveredAndFalseForStaleGeneration() throws Exception {
        Path directory=temporary.newFolder().toPath();
        SessionRepository repository=new SessionRepository(new Gson(),FilepathTestSupport.root(directory),false);
        var writer=new OrderedPersistenceWriter(repository,rejecting());
        long gen=repository.generation;
        writer.merged.set(new OrderedPersistenceWriter.PendingWrite(new WriteIntent(null,gen,0L,named("Pending",9L),null)));
        assertTrue("a newer same-generation snapshot covers the request",
                writer.submit(new WriteIntent(null,gen,0L,named("Covered",2L),null)));
        writer.merged.set(new OrderedPersistenceWriter.PendingWrite(new WriteIntent(null,gen+1,0L,named("NewerScope",1L),null)));
        assertFalse("a lower generation cannot replace a newer pending scope",
                writer.submit(new WriteIntent(null,gen,0L,named("StaleScope",1L),null)));
        writer.shutdown(Duration.ZERO);
    }

    @Test public void writerRefusesStoppedConflictedAndRejectedWork() throws Exception {
        Path directory=temporary.newFolder().toPath();
        SessionRepository repository=new SessionRepository(new Gson(),FilepathTestSupport.root(directory),false);
        var executor=OrderedPersistenceWriter.daemonExecutor("save-schedule-test");
        var writer=new OrderedPersistenceWriter(repository,executor);
        try{
            long gen=repository.generation;
            assertTrue(writer.submit(new WriteIntent(null,gen,0L,named("A",1L),null)));
            assertTrue(writer.submit(new WriteIntent(null,gen,0L,named("B",2L),null)));
            assertTrue(writer.flush(Duration.ofSeconds(5)));
            writer.accept=false;
            assertFalse(writer.submit(new WriteIntent(null,gen,0L,named("Stopped",3L),null)));
            writer.accept=true;
            writer.conflict.set(gen);
            assertFalse(writer.submit(new WriteIntent(null,gen,0L,named("Conflict",4L),null)));
        }finally{
            writer.shutdown(Duration.ofSeconds(5));executor.shutdown();
        }
        var rejecting=rejecting();
        var rejected=new OrderedPersistenceWriter(repository,rejecting);
        assertFalse(rejected.submit(new WriteIntent(null,repository.generation,0L,named("Rejected",5L),null)));
        assertFalse(rejected.flush(Duration.ofMillis(50)));
        rejected.shutdown(Duration.ZERO);
    }

    static AbstractExecutorService rejecting(){
        return new AbstractExecutorService(){
            public void execute(Runnable command){throw new RejectedExecutionException();}
            public void shutdown(){}
            public List<Runnable> shutdownNow(){return List.of();}
            public boolean isShutdown(){return false;}
            public boolean isTerminated(){return false;}
            public boolean awaitTermination(long timeout,TimeUnit unit){return true;}
        };
    }
}
