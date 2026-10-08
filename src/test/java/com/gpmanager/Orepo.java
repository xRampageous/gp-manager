package com.gpmanager;
import com.google.gson.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import javax.inject.*;
import org.slf4j.*;
import net.runelite.client.util.Filepath;
import lombok.*;
import static com.gpmanager.Ak.msg;
import static com.gpmanager.Ae.nonNeg;

/** Frozen Phase-4 start algorithms; inherited file, parse and lock seams are unchanged. */
class Orepo extends SessionRepository {
 Orepo(Gson a, Filepath b) { super(a, b); }
synchronized SavedState load() {
 afp();
 lastRecoveredFrom = "";
 po();
 if (bound.state.exists()) {
  try {
   SavedState /* state */b = avc(bound.state);
   // Load establishes disk truth; do not retain a stale in-memory
   // high-water mark from a primary that was later discarded.
   lastKnownDiskRevision = nonNeg(b.revision);
   return b;
  } catch (Exception /* ex */c) {
   log.warn(msg("io"), c);
   adf(bound);
  }
 }
 if (ln(bound.backup).stream().anyMatch(Filepath::exists)) {
  // After a committed reset, backup must not resurrect cleared data.
  // If a stage file says PRIMARY_COMMITTED, prefer repairing from
  // primary/next rather than falling back to an older backup.
  Cv /* stage */d = avb();
  if (d == Cv.PRIMARY_COMMITTED && bound.next.exists()) {
   try {
    SavedState /* fromNext */e = avc(bound.next);
    afl(bound.next, bound.state);
    lastKnownDiskRevision = nonNeg(e.revision);
    try {
     bound.state.copyTo(bound.backup, StandardCopyOption.REPLACE_EXISTING);
     akv(bound.stage, Cv.COMPLETE);
     bound.stage.deleteIfExists();
    } catch (IOException /* backupEx */f) {
     vj(bound, msg("ip"));
    }
    return e;
   } catch (Exception /* ex */g) {
    log.warn(msg("u"), g);
   }
  }
  for (Filepath /* candidate */h : ln(bound.backup)) {
   try {
    SavedState /* recovered */i = avc(h);
    log.warn(msg("ay"), h.getFileName());
    lastKnownDiskRevision = nonNeg(i.revision);
    lastRecoveredFrom = h.getFileName().toString();
    return i;
   } catch (Exception /* ex */j) { log.warn(msg("au"), h, j); }
  }
 }
 lastKnownDiskRevision = 0L;
 return new SavedState();
}

Bm pq(Cs /* intent */b, boolean /* destructiveReset */c) {
 if (b == null || b.state == null) throw new IllegalArgumentException("intent");
 SavedState /* state */d = b.state;
 TrackingIdentity /* targetIdentity */e = b.identity;
 if (accountAware && e == null) {
  log.warn(msg("iq"));
  return Bm.failedUnchanged(msg("af"));
 }
 var /* target */f = new ScopeFiles(ahw(e));
 // Owner key is taken from the intent, never from a later live bind.
 if (e != null) d.setOwnerKey(e.rsProfileKey);
 d.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
 if (d.savedAtEpochMillis <= 0L) d.setSavedAtEpochMillis(Ae.now());
 FileLock /* lock */g = null;
 // Set once the primary replacement has been attempted; a fault after that
 // point is classified by inspecting the primary under the still-held lock.
 boolean /* app */h = false;
 try {
  f.dir.createDirectories();
  g = jw(f.lock);
  if (g == null) {
   log.warn(msg("ir"));
   return Bm.failedUnchanged(msg("v"));
  }
  Optional<Integer> /* alr */i = peek(f.state).map(/* object */j -> j.has("schemaVersion")
  && j.get("schemaVersion").isJsonPrimitive() ? j.get("schemaVersion").getAsInt() : 0);
  if (i.isPresent() && i.get() != SavedState.CURRENT_SCHEMA_VERSION) {
   // Any other schema (a newer version) is preserved read-only.
   log.warn(msg("is"), i.get());
   return Bm.failedUnchanged(msg("b8") + i.get());
  }
  long /* alq */k = ado(f.state).orElse(adm(f.backup).orElse(0L));
  // Compare-and-swap against the base revision observed when the
  // snapshot was created. Equal competing writers both claiming base N
  // must not both succeed — and a destructive reset is no exception:
  // a newer revision written by another client wins and the reset aborts.
  if (k != b.expectedBaseRevision) {
   log.warn(msg("it"), c ? "reset" : "save", k, b.expectedBaseRevision);
   if (ahb(e)) lastKnownDiskRevision = k;
   return Bm.conflict(msg("b6")
   + b.expectedBaseRevision + ", found " + k + ")");
  }
  if (d.revision <= 0L) {
   d.setRevision(k + 1L);
  } else if (d.revision <= k) {
   log.warn(msg("iu"), d.revision, k);
   return Bm.failedUnchanged("Revision " + d.revision + msg("b7") + k);
  }
  try (Writer /* writer */m = f.next.openWriter(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
  StandardOpenOption.WRITE)) {
   if (b.json == null) gson.toJson(d, m);
   else m.write(b.json);
  }
  SavedState /* validated */n = avc(f.next);
  akv(f.stage, Cv.NEXT_READY);
  if (!c && f.state.exists()) {
   boolean /* valid */o = false;
   try {
    avc(f.state);
    o = true;
   } catch (Exception /* ex */p) {
    adf(f);
    if (f.state.exists()) throw new IOException(msg("iv"), p);
   }
   if (o) {
    Filepath /* aqv */q = f.dir.joinSegment(msg("cn"));
    f.state.copyTo(q, StandardCopyOption.REPLACE_EXISTING);
    afl(q, f.backup);
   }
  }
  h = true;
  afl(f.next, f.state);
  akv(f.stage, Cv.PRIMARY_COMMITTED);
  if (ahb(e)) lastKnownDiskRevision = n.revision;
  if (c) {
   try {
    Filepath /* ank */r = f.dir.joinSegment(msg("bk"));
    f.state.copyTo(r, StandardCopyOption.REPLACE_EXISTING);
    afl(r, f.backup);
    akv(f.stage, Cv.COMPLETE);
    f.stage.deleteIfExists();
    f.next.deleteIfExists();
   } catch (IOException /* backupEx */s) {
    vj(f, msg("iw"));
    akv(f.stage, Cv.PRIMARY_COMMITTED);
    log.warn(msg("ix"), s);
    return Bm.committedPrimaryOnly(msg("l"));
   }
   return Bm.committed(msg("ai"));
  }
  akv(f.stage, Cv.COMPLETE);
  f.stage.deleteIfExists();
  f.next.deleteIfExists();
  return Bm.committed("Save committed");
 } catch (IOException /* ex */t) {
  log.warn(c ? msg("ad") : msg("be"), t);
  // Still under the account lock, so a primary holding our revision can
  // only be ours: classify, never re-issue the write.
  if (h && ado(f.state).orElse(-1L) == d.revision) {
   if (ahb(e)) lastKnownDiskRevision = d.revision;
   return Bm.committedPrimaryOnly(msg("iy"));
  }
  return Bm.failedUnchanged(c ? msg("iz") : msg("ja"));
 } finally {
  afv(g);
 }
}

Optional<Long> adm(Filepath /* primaryBackup */a) {
 for (Filepath /* candidate */b : ln(a)) {
  Optional<Long> /* revision */c = ado(b);
  if (c.isPresent()) return c;
 }
 return Optional.empty();
}

static List<Filepath> ln(Filepath /* primaryBackup */a) {
 return List.of(a);
}
}
