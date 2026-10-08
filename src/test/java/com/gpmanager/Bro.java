package com.gpmanager;
import static com.gpmanager.Ak.msg;
import java.util.*;
import lombok.*;
import static java.util.Locale.*;
/**
* Item Retrieval Services and their published flat fees. Source: OSRS Wiki
* "Item Retrieval Service" (checked 2026-09-13). The fee here is an <em>expectation used
* for labelling</em>; the booked cost is always the coins actually observed leaving the
* inventory in the reclaim window. Fees paid from the bank or Death's Coffer are not
* observable and are never invented.
*/
class Bro {
/** Marker for services whose fee depends on the run (ToA scales with raid level). */
static final long VARIABLE_FEE = -1L;
/** Marker for tiered / percentage fees (gravestone, Death's Office) — see DeathReclaimFees. */
static final long TIERED_FEE = -2L;
@AllArgsConstructor
static class Service {
 String key;
 String activity;
 String interactable;
 long expectedFee;
 String note;
 /**
 * Target name shared with unrelated scenery (a bare "Chest", a gravestone): only
 * evidence when a local PvM death is still awaiting reclaim.
 */
 boolean ambiguousTarget;
 /** Ledger explanation for an observed coin payment at this service. */
 String why(long /* observedCoins */b) {
  var /* why */c = new StringBuilder(msg("a4")).append(activity).append(" (").append(interactable).append(")");
  if (expectedFee > 0L) {
   c.append(", published ").append(String.format(ROOT, "%,d", expectedFee));
   if (b > 0L && b != expectedFee) {
    c.append(", observed ").append(String.format(ROOT, "%,d", b));
   }
  } else if (expectedFee == VARIABLE_FEE) {
   c.append(", fee varies");
  }
  if (!note.isEmpty()) c.append(". ").append(note);
  return c.toString();
 }
}

static final Map<String, Service> BY_INTERACTABLE = new LinkedHashMap<>();
static {
 for (String[] r : Ak.rows("d2")) {
  BY_INTERACTABLE.put(r[2].toLowerCase(ROOT),
  new Service(r[0], r[1], r[2], Long.parseLong(r[3]), r[4], Boolean.parseBoolean(r[5])));
 }
}

/**
* Service for a menu target (NPC or object name) when the option is a retrieval verb
* (see {@link #isRetrievalOption}). Ambiguous targets ("Chest", "Gravestone", "Death")
* are returned too; the engine only acts on them while a local PvM death awaits reclaim.
*/
static Service axr(String /* option */a, String /* target */b) {
 if (b == null || a == null || !Ag.lower(a).matches(msg("e"))) {
  return null;
 }
 String /* lower */c = Ag.lower(b);
 if (c.isEmpty()) return null;
 Service /* exact */d = BY_INTERACTABLE.get(c);
 if (d != null) return d;
 for (Map.Entry<String, Service> /* entry */f : BY_INTERACTABLE.entrySet()) {
  if (!f.getValue().ambiguousTarget && c.contains(f.getKey())) return f.getValue();
 }
 if (c.startsWith("gravestone") || c.startsWith("grave")) return BY_INTERACTABLE.get("gravestone");
 return null;
}

/** Menu verbs that open or complete a reclaim. */}
