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
class BossRetrievalCatalogue {
/** Marker for services whose fee depends on the run (ToA scales with raid level). */
static final long VARIABLE_FEE = -1L;
/** Marker for tiered / percentage fees (gravestone, Death's Office) — see DeathReclaimFees. */
static final long TIERED_FEE = -2L;
@AllArgsConstructor
static class Service {
 final String key;
 final String activity;
 final String interactable;
 final long expectedFee;
 final String note;
 /**
 * Target name shared with unrelated scenery (a bare "Chest", a gravestone): only
 * evidence when a local PvM death is still awaiting reclaim.
 */
 final boolean ambiguousTarget;
 /** Ledger explanation for an observed coin payment at this service. */
 String why(long observedCoins) {
  var why = new StringBuilder("Item retrieval fee — ").append(activity).append(" (").append(interactable).append(")");
  if (expectedFee > 0L) {
   why.append(", published ").append(String.format(ROOT, "%,d", expectedFee));
   if (observedCoins > 0L && observedCoins != expectedFee) {
    why.append(", observed ").append(String.format(ROOT, "%,d", observedCoins));
   }
  } else if (expectedFee == VARIABLE_FEE) {
   why.append(", fee varies");
  }
  if (!note.isEmpty()) why.append(". ").append(note);
  return why.toString();
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
static Service axr(String option, String target) {
 if (target == null || option == null || !option.trim().toLowerCase(ROOT).matches(msg("e"))) {
  return null;
 }
 String lower = target.trim().toLowerCase(ROOT);
 if (lower.isEmpty()) return null;
 Service exact = BY_INTERACTABLE.get(lower);
 if (exact != null) return exact;
 for (Map.Entry<String, Service> entry : BY_INTERACTABLE.entrySet()) {
  if (!entry.getValue().ambiguousTarget && lower.contains(entry.getKey())) return entry.getValue();
 }
 if (lower.startsWith("gravestone") || lower.startsWith("grave")) return BY_INTERACTABLE.get("gravestone");
 return null;
}

/** Menu verbs that open or complete a reclaim. */}
