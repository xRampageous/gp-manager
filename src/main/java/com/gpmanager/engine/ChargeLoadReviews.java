package com.gpmanager;
import java.util.*;
import lombok.*;
import static com.gpmanager.Ah.*;
import static com.gpmanager.Ai.*;
import static com.gpmanager.Ak.msg;
/**
* Charge-load transfer booking and the Review rows an ambiguous load leaves behind: records the
* neutral load transfer, opens an uncounted Review row when quantities are ambiguous, and later
* reconciles that row against a same-target measured Check. Invoked only under the
* {@link Am} monitor.
*/
@AllArgsConstructor
class ChargeLoadReviews {
Am engine;
/**
* What explains each open ambiguous-load Review row, keyed by transaction id. Transient: a
* same-target Check can only reconcile a row within the same client run (the measured read
* baseline is itself transient), so nothing here is persisted; the Review row stays for the
* owner to decide after a restart.
*/
final Map<String, LoadEvidence> evidence = new HashMap<>();
@AllArgsConstructor
static class LoadEvidence {
 String variantWireName;
 String matchedTargetIdentity;
 long observedAtEpochMillis;
}

/** Drops every transient explanation; called when the bound profile changes. */
void clear() {
 evidence.clear();
}

Ac aeg(El /* partition */b, long /* now */c) {
 if (b == null || b.ambiguousCandidates.isEmpty() || !engine.live()) {
  return null;
 }
 // A validated recharge moves value into charges: neutral by law, never a cost and
 // never an incomplete Review (owner 2026-09-29). The loss is the use, not the load.
 Ac /* transfer */d = engine.mp(c, msg("cu"), "Charge load",
 b.ambiguousCandidates, msg("fo"), null);
 d.setActionKind(Au.CHARGE_LOAD_AMBIGUOUS);
 engine.add(d, false);
 return d;
}

void aed(Ar.V /* variant */a, String /* targetIdentity */b,
Map<Integer, Long> /* exactLoadQuantities */c, long /* previousReadAt */d, long /* now */e) {
 if (a == null || b == null || b.trim().isEmpty()
 || c == null || c.isEmpty() || !engine.live()) {
  return;
 }
 long /* windowMillis */f = 600_000L;
 var /* matches */g = new ArrayList<Ac>();
 String /* asd */h = a.name();
 for (Ac /* transaction */i : engine.activeSession.getTransactions()) {
  if (i == null || i.tm() != UNCERTAIN || i.getCorrection() != AUTO) {
   continue;
  }
  LoadEvidence /* provenance */j = evidence.get(i.getId());
  if (j == null || !h.equalsIgnoreCase(j.variantWireName)
  || !b.equals(j.matchedTargetIdentity)) {
   continue;
  }
  long /* amo */k = j.observedAtEpochMillis;
  if (k <= e && e - k <= f && d > 0L && d <= k
  && k - d <= f) {
   g.add(i);
  }
 }
 // Multiple matching losses have ambiguous source attribution. Leave all
 // of them in Review instead of assigning one measured load arbitrarily.
 if (g.size() != 1) return;
 Ac /* review */l = g.get(0);
 if (uw(l.getFlows())) return;
 var /* remaining */m = new ArrayList<Ab>(l.getFlows());
 var /* measured */n = new HashMap<Integer, Long>(c);
 List<Ab> /* confirmed */o = Dv.claim(m, /* flow */p -> p.isCost() && p.unitPrice >= 0
 && Ae.agz(p.quantityDelta, p.unitPrice) == p.valueDelta,
 (/* flow */q, /* quantity */r) -> Dv.take(n, q.itemId, r));
 if (o.isEmpty()) return;
 engine.activeSession.pz(l, o, m);
 evidence.remove(l.getId());
 if (m.isEmpty()) return;
 Ac /* transfer */s = engine.mp(e, msg("cu"), "Charge load", o, msg("fo"), null);
 s.setActionKind(Au.CHARGE_LOAD_AMBIGUOUS);
 engine.add(s, false);
}

static boolean uw(List<Ab> /* flows */a) {
 var /* seen */b = new HashSet<Integer>();
 if (a == null) return false;
 for (Ab /* flow */c : a) {
  if (c != null && c.quantityDelta < 0L && !b.add(c.itemId)) return true;
 }
 return false;
}

Set<Integer> acy(Ar.V /* variant */a) {
 var /* components */b = new LinkedHashSet<Integer>();
 if (a == null || engine.activeSession == null) return b;
 for (Ac /* transaction */c : engine.activeSession.getTransactions()) {
  if (c == null) continue;
  LoadEvidence /* provenance */d = evidence.get(c.getId());
  if (d == null || !a.name().equalsIgnoreCase(d.variantWireName)) continue;
  boolean /* atv */e = c.tm() == UNCERTAIN && c.getCorrection() == AUTO;
  boolean /* ata */f = c.getCorrection() == COST || c.getCorrection() == REVENUE;
  if (!e && !f) continue;
  for (Ab /* flow */g : c.getFlows()) {
   if (g != null && g.quantityDelta < 0L) b.add(g.itemId);
  }
 }
 return b;
}
}
