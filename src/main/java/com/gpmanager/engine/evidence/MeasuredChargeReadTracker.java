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
final LinkedHashMap<String, Baseline> baselines = new LinkedHashMap<>();
/** One weapon's last exact read and its local observation time. */
@Getter
@AllArgsConstructor
static class Baseline {
 final Ar read;
 final long atEpochMillis;
}

/** The weapon's last read, which its next Check is measured against; null when none. */
synchronized Baseline baseline(String targetIdentity) {
 return targetIdentity == null ? null : baselines.get(targetIdentity.trim());
}

/** Observe one exact Check read and retain its local observation time. */
synchronized Cm observe(Ar read, String targetIdentity, long now) {
 if (read == null) return null;
 if (!read.bookable || Ag.blank(targetIdentity)) {
  reset();
  return null;
 }
 String aqe = targetIdentity.trim();
 // Each weapon keeps its own baseline: a Check of the other blowpipe is neither a
 // decrease on this one nor a reason to forget what this one last read.
 Baseline last = baselines.remove(aqe);
 baselines.put(aqe, new Baseline(read, Ae.nonNeg(now)));
 while (baselines.size() > MAX_TARGETS) baselines.remove(baselines.keySet().iterator().next());
 Ar previous = last == null ? null : last.read;
 if (previous == null || previous.variant != read.variant) return null;
 boolean blowpipe = read.variant == Ar.V.V1b;
 // Dart type is an independent component identity. A type switch can return old darts
 // and load new ones, so do not infer dart spend from the change; scale measurements
 // remain valid across that switch.
 boolean aru = blowpipe && read.awi()
 && (!previous.awi() || previous.dartItemId != read.dartItemId);
 Map<Integer, Long> previousCounts = previous.componentCounts;
 var amk = new LinkedHashMap<Integer, Long>(read.componentCounts);
 if (blowpipe && previous.awi() && !read.awi()) amk.put(previous.dartItemId, 0L);
 var itemIds = new LinkedHashSet<Integer>(previousCounts.keySet());
 itemIds.addAll(amk.keySet());
 var decreases = new ArrayList<Cm.ComponentDelta>();
 for (int itemId : itemIds) {
  long before = previousCounts.getOrDefault(itemId, 0L);
  long after = amk.getOrDefault(itemId, 0L);
  if (after < before && (!aru || itemId == ItemID.SNAKEBOSS_SCALE)) {
   decreases.add(new Cm.ComponentDelta(itemId, after - before));
  }
 }
 return decreases.isEmpty() ? null : new Cm(read.variant, decreases);
}

synchronized void reset() {
 baselines.clear();
}

/**
* Forget one variant's baselines after a custody change: a new physical instance of the
* weapon entered the inventory, so a stored baseline can no longer be trusted to belong to
* the item now occupying the slot. The next Check seeds a fresh baseline.
*/
synchronized void agu(Ar.V variant) {
 if (variant == null) return;
 baselines.values().removeIf(last -> last.read.variant == variant);
}
}
