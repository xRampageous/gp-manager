package com.gpmanager;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Optional local copied saves only: frozen/current load and durable round-trip parity. */
public class OwnerSaveParityTest {
 @Rule public TemporaryFolder temporary = new TemporaryFolder();
 @Test public void copiedOwnerFilesLoadAndRoundTripExactlyAsBefore() throws Exception {
  String a = System.getProperty("gp.saveParityRoot", "");
  Assume.assumeTrue("local copied-save fixture is optional", !a.isEmpty());
  Path b = Paths.get(a);
  List<Path> c;
  try (var d = Files.walk(b)) {
   c = d.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json")).collect(Collectors.toList());
  }
  assertFalse("fixture contains saves", c.isEmpty());
  LongSupplier d = Ae.clock;
  try {
   Ae.clock = () -> 1_700_000_000_000L;
   for (Path e : c) {
    byte[] f = Files.readAllBytes(e);
    assertEquals("old/new copied-save outcome", run(true, f), run(false, f));
    assertArrayEquals("copied input is never rewritten", f, Files.readAllBytes(e));
   }
  } finally { Ae.clock = d; }
 }
 List<Object> run(boolean a, byte[] b) throws Exception {
  Path c = temporary.newFolder().toPath();
  Files.write(c.resolve("sessions.json"), b);
  Gson d = new Gson();
  SessionRepository e = a ? new Orepo(d, FilepathTestSupport.root(c)) : new SessionRepository(d, FilepathTestSupport.root(c));
  SavedState f = e.load();
  List<Object> g = new ArrayList<>();
  g.add(d.toJsonTree(f)); g.add(e.lastKnownDiskRevision); g.add(e.lastRecoveredFrom);
  boolean h = Files.exists(c.resolve("sessions.json"));
  g.add(h);
  if (!h) {
   try (var i = Files.list(c)) {
    List<Path> j = i.filter(Files::isRegularFile).collect(Collectors.toList());
    assertEquals("corrupt evidence is preserved", 1, j.size());
    assertArrayEquals(b, Files.readAllBytes(j.get(0)));
   }
  } else if (!f.ye()) {
   assertArrayEquals("unsupported schema is untouched", b, Files.readAllBytes(c.resolve("sessions.json")));
  } else {
   f.revision = e.lastKnownDiskRevision + 1L;
   f.savedAtEpochMillis = 1_700_000_000_000L;
   JsonElement i = d.toJsonTree(f);
   assertTrue(e.save(new Cs(null, e.scopeGeneration, e.lastKnownDiskRevision, f)));
   assertEquals("every durable value survives round trip", i, d.toJsonTree(e.load()));
   g.add(Files.readString(c.resolve("sessions.json")));
   g.add(Files.readString(FilepathTestSupport.path(e.bound.backup)));
  }
  return g;
 }
}
