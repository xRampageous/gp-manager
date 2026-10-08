package com.gpmanager;
import com.google.gson.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Collections;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Real coordinator reset safety: copies precede destruction, and stale exports cannot qualify. */
public class ResetSafetyCopyTest {
 @Rule public TemporaryFolder temporary = new TemporaryFolder();
 static long T = 1_700_000_000_000L;
 static TrackingIdentity A = new TrackingIdentity("rsprofile.safety", -1L);
 static class F implements AutoCloseable {
  SessionRepository r;
  OrderedPersistenceWriter w;
  Ei e;
  F(Path a) {
   this(a, false);
  }
  F(Path a, boolean c) {
   JsonCodec.bind(new Gson());
   r = new SessionRepository(new Gson(), FilepathTestSupport.root(a), true);
   w = new OrderedPersistenceWriter(r);
   Am b = new Am(d -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
   e = new Ei(null, null, r, w, b) {
    @Override TrackingIdentity resolveCurrentIdentity() { return A; }
    @Override Filepath akt(long d) throws java.io.IOException {
     Filepath f = super.akt(d);
     if (c) engine.getActiveSession().rename("Edited during copy");
     return f;
    }
    @Override Filepath b1(long d) throws java.io.IOException {
     Filepath f = super.b1(d);
     if (c) engine.getActiveSession().rename("Edited during copy");
     return f;
    }
   };
   assertTrue(e.ajy(A, true));
   b.rm(T); b.getActiveSession().rename("Before reset");
   b.getActiveSession().kf(receipt(T), 2_000);
   Ad d = new Ad("Closed history", T - 60_000L);
   d.kf(receipt(T - 30_000L), 2_000); d.close(T - 1_000L);
   b.history.add(d);
   assertTrue(e.aya());
  }
  public void close() { w.shutdown(Duration.ofSeconds(5)); }
 }
 static Ac receipt(long a) {
  return new Ac(a, null, Ai.LOOT, Aj.LOOT, "", "Guard", true,
   Collections.singletonList(new Ab(560, "Death rune", 46L, 187, 8_602L)), Bd.CONFIRMED, "test", null);
 }
 @Test public void directResetKeepsTheCompletePriorStateAndExistingSuccessNotice() throws Exception {
  try (F a = new F(temporary.newFolder().toPath())) {
   JsonObject b = Ei.b2(a.e.engine.sl());
   Ei.Ds c = a.e.sj(T + 1_000L);
   assertTrue(c.getDetail(), c.isApplied());
   assertNull("keep the existing successful Reset display", c.getPreResetBackup());
   assertEquals(1, a.e.backups().size());
   Filepath d = a.e.backups().get(0);
   SavedState e = SessionRepository.r1(JsonCodec.gson(), d);
   assertTrue(e.ye());
   assertEquals(b, Ei.b2(e));
   assertEquals(1, e.generalSession.getTransactions().size());
   assertEquals(1, e.history.size());
   assertNull(a.e.engine.getActiveSession());
   assertTrue(a.e.agm(d, T + 2_000L).isApplied());
   assertEquals("Before reset", a.e.engine.getActiveSession().getName());
  }
 }
 @Test public void explicitCopyIsReusedDespiteLaterSaveHeaders() throws Exception {
  try (F a = new F(temporary.newFolder().toPath())) {
   Filepath b = a.e.akt(T);
   assertTrue(a.e.aya());
   assertTrue(a.e.sj(T + 1_000L).isApplied());
   assertEquals(Collections.singletonList(b), a.e.backups());
  }
 }
 @Test public void changedFactsRequireANewCopy() throws Exception {
  try (F a = new F(temporary.newFolder().toPath())) {
   Filepath b = a.e.akt(T);
   a.e.engine.getActiveSession().rename("Changed before reset");
   a.e.engine.getActiveSession().kf(receipt(T + 500L), 2_000);
   assertTrue(a.e.sj(T + 1_000L).isApplied());
   assertEquals(2, a.e.backups().size());
   assertEquals("Before reset", SessionRepository.r1(JsonCodec.gson(), b).generalSession.getName());
   assertEquals("Changed before reset", SessionRepository.r1(JsonCodec.gson(), a.e.backups().get(0)).generalSession.getName());
   assertEquals(2, SessionRepository.r1(JsonCodec.gson(), a.e.backups().get(0)).generalSession.getTransactions().size());
  }
 }
 @Test public void backupFailureRefusesResetBeforeMutatingTheOwner() throws Exception {
  Path a = temporary.newFolder().toPath();
  try (F b = new F(a)) {
   Files.writeString(a.resolve("backups"), "blocked directory");
   JsonObject c = Ei.b2(b.e.engine.sl());
   Ei.Ds d = b.e.sj(T);
   assertFalse(d.isApplied());
   assertTrue(d.getDetail(), d.getDetail().startsWith(Ak.msg("b2")));
   assertEquals(c, Ei.b2(b.e.engine.sl()));
   assertEquals("Before reset", b.r.load().generalSession.getName());
   assertFalse(b.e.maintenance);
  }
 }
 @Test public void corruptIncompatibleAndWrongOwnerExportsArePreservedButNotReused() throws Exception {
  for (int a = 0; a < 3; a++) {
   try (F b = new F(temporary.newFolder().toPath())) {
    Filepath c = b.e.akt(T);
    JsonObject d = new JsonParser().parse(SessionRepository.awy(c)).getAsJsonObject();
    if (a == 1) d.addProperty("schemaVersion", 999);
    if (a == 2) d.addProperty("ownerKey", "another.owner");
    String e = a == 0 ? "{broken" : d.toString();
    c.write(e, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    assertTrue(b.e.sj(T + 1_000L).isApplied());
    assertEquals(2, b.e.backups().size());
    assertEquals("existing evidence is preserved", e, SessionRepository.awy(c));
    SavedState f = SessionRepository.r1(JsonCodec.gson(), b.e.backups().get(0));
    assertTrue(f.ye());
    assertEquals(A.rsProfileKey, f.ownerKey);
    assertEquals("Before reset", f.generalSession.getName());
   }
  }
 }
 @Test public void aSidebarEditDuringCopyRefusesResetAndKeepsTheNewerData() throws Exception {
  try (F a = new F(temporary.newFolder().toPath(), true)) {
   Ei.Ds b = a.e.sj(T);
   assertFalse(b.isApplied());
   assertTrue(b.getDetail(), b.getDetail().contains("data changed"));
   assertEquals("Edited during copy", a.e.engine.getActiveSession().getName());
   assertEquals("Before reset", SessionRepository.r1(JsonCodec.gson(), a.e.backups().get(0)).generalSession.getName());
   assertEquals(1, a.e.engine.getActiveSession().getTransactions().size());
   assertFalse(a.e.maintenance);
  }
 }
 @Test public void aSidebarEditDuringCopyAlsoRefusesRestoreAndKeepsTheNewerData() throws Exception {
  try (F a = new F(temporary.newFolder().toPath(), true)) {
   Filepath b = a.e.akt(T);
   a.e.engine.getActiveSession().rename("Current before restore");
   Ei.Ds c = a.e.agm(b, T + 1_000L);
   assertFalse(c.isApplied());
   assertTrue(c.getDetail(), c.getDetail().startsWith("Restore was not applied: data changed"));
   assertEquals("Edited during copy", a.e.engine.getActiveSession().getName());
   assertEquals("Current before restore", SessionRepository.r1(JsonCodec.gson(), a.e.backups().get(0)).generalSession.getName());
   assertFalse(a.e.maintenance);
  }
 }
}
