package com.gpmanager;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** The simplified repository must keep exact outcomes, file contents and recovery order. */
public class SaveRepositoryParityTest {
 static SavedState state(long a, String b) {
  SavedState c = new SavedState(new Ad(b, 1_000L), null, false, Collections.emptyList());
  c.revision = a; c.savedAtEpochMillis = 10_000L; return c;
 }
 static List<Object> run(boolean a, int b) throws Exception {
  Path c = Files.createTempDirectory("gp-save-parity");
  Gson d = new Gson();
  SessionRepository e = a ? new Orepo(d, FilepathTestSupport.root(c)) : new SessionRepository(d, FilepathTestSupport.root(c));
  List<Object> f = new ArrayList<>();
  SavedState g = state(1L, "First"); g.generalSession.id = "first";
  f.add(e.save(new Cs(null, e.scopeGeneration, 0L, g)));
  SavedState h = state(2L, "Second"); h.generalSession.id = "second";
  f.add(e.save(new Cs(null, e.scopeGeneration, 1L, h)));
  if (b == 1) Files.writeString(c.resolve("sessions.json"), "{broken");
  if (b == 2) Files.delete(c.resolve("sessions.json"));
  if (b == 3) {
   SavedState i = state(3L, "Reset"); i.generalSession.id = "reset";
   SessionRepository.Bm j = e.replaceStateDetailed(new Cs(null, e.scopeGeneration, 2L, i));
   f.add(j.kind); f.add(j.message);
  }
  if (b == 4) {
   SavedState i = state(3L, "Stale"); i.generalSession.id = "stale";
   SessionRepository.Bm j = e.replaceStateDetailed(new Cs(null, e.scopeGeneration, 1L, i));
   f.add(j.kind); f.add(j.message);
  }
  f.add(d.toJsonTree(e.load())); f.add(e.lastKnownDiskRevision); f.add(e.lastRecoveredFrom);
  f.add(e.bound.stage.exists()); f.add(e.bound.next.exists());
  f.add(e.adm(e.bound.backup));
  if (e.bound.state.exists()) f.add(SessionRepository.awy(e.bound.state));
  if (e.bound.backup.exists()) f.add(SessionRepository.awy(e.bound.backup));
  return f;
 }
 @Test public void ordinaryRecoveryResetAndConflictMatchTheFrozenRepository() throws Exception {
  java.util.function.LongSupplier a = Ae.clock;
  try {
   Ae.clock = () -> 10_000L;
   for (int b = 0; b < 5; b++) assertEquals("repository case " + b, run(true, b), run(false, b));
  } finally { Ae.clock = a; }
 }
}
