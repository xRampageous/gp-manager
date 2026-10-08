package com.gpmanager;
import net.runelite.api.gameval.ItemID;
import java.util.*;
import lombok.*;
/**
* Compares explicit charge reads. One read establishes a baseline; only measured
* component decreases can produce a booking-ready delta.
*/
class MeasuredChargeReadTracker {
/** Bounded number of same-variant weapons tracked side by side (two blowpipes, a spare trident). */
static final int MAX_TARGETS = 8;
/** One baseline per stable target identity, most recently read last. */
LinkedHashMap<String, Baseline> baselines = new LinkedHashMap<>();
/** One weapon's last exact read and its local observation time. */
@Getter
@AllArgsConstructor
static class Baseline {
 Ar read;
 long atEpochMillis;
}

/** The weapon's last read, which its next Check is measured against; null when none. */
synchronized Baseline baseline(String /* targetIdentity */a) {
 return a == null ? null : baselines.get(a.trim());
}

/** Observe one exact Check read and retain its local observation time. */
synchronized Cm observe(Ar /* read */b, String /* targetIdentity */c, long /* now */d) {
 if (b == null) return new Cm(null, Cm.K.MISMATCH);
 if (!b.bookable || Ag.blank(c)) {
  reset();
  return new Cm(b.variant, Cm.K.MISMATCH);
 }
 String /* aqe */e = c.trim();
 // Each weapon keeps its own baseline: a Check of the other blowpipe is neither a
 // decrease on this one nor a reason to forget what this one last read.
 Baseline /* last */f = baselines.remove(e);
 baselines.put(e, new Baseline(b, Ae.nonNeg(d)));
 while (baselines.size() > MAX_TARGETS) baselines.remove(baselines.keySet().iterator().next());
 Ar /* previous */g = f == null ? null : f.read;
 if (g == null) return new Cm(b.variant, Cm.K.SEED);
 if (g.variant != b.variant) return new Cm(b.variant, Cm.K.MISMATCH);
 boolean /* blowpipe */h = b.variant == Ar.V.V1b;
 // Dart type is an independent component identity. A type switch can return old darts
 // and load new ones, so do not infer dart spend from the change; scale measurements
 // remain valid across that switch.
 boolean /* aru */i = h && b.awi()
 && (!g.awi() || g.dartItemId != b.dartItemId);
 Map<Integer, Long> /* previousCounts */j = g.componentCounts;
 var /* amk */k = new LinkedHashMap<Integer, Long>(b.componentCounts);
 if (h && g.awi() && !b.awi()) k.put(g.dartItemId, 0L);
 var /* itemIds */l = new LinkedHashSet<Integer>(j.keySet());
 l.addAll(k.keySet());
 var /* decreases */m = new ArrayList<Cm.ComponentDelta>();
 boolean /* same */n = true;
 for (int /* itemId */o : l) {
  long /* before */p = j.getOrDefault(o, 0L);
  long /* after */q = k.getOrDefault(o, 0L);
  n &= q == p;
  if (q < p && (!i || o == ItemID.SNAKEBOSS_SCALE)) {
   m.add(new Cm.ComponentDelta(o, q - p));
  }
 }
 // Independent decreases still prove spend when a different component was loaded.
 return new Cm(b.variant, m, !m.isEmpty() ? Cm.K.SPEND : n ? Cm.K.SAME : Cm.K.LOAD);
}

synchronized void reset() {
 baselines.clear();
}

/**
* Forget one variant's baselines after a custody change: a new physical instance of the
* weapon entered the inventory, so a stored baseline can no longer be trusted to belong to
* the item now occupying the slot. The next Check seeds a fresh baseline.
*/
synchronized void agu(Ar.V /* variant */a) {
 if (a == null) return;
 baselines.values().removeIf(/* last */b -> b.read.variant == a);
}
}
