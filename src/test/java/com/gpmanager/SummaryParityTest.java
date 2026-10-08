package com.gpmanager;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Whole-output parity and dependency boundaries that a revision-only cache would miss. */
public class SummaryParityTest {
 static final long NOW = 1_791_270_000_000L;
 static Am engine() {
  return new Am(d -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
 }
 static Ac receipt(Ai a, Aj b, long c) {
  return Tx.of(c, a, b, "Observed", true, Arrays.asList(
  new Ab(561, "Nature rune", -2L, 120, -240L), new Ab(1515, "Yew logs", 3L, 200, 600L)));
 }
 @Test public void emptyAndEveryReceiptKindContextAndCorrectionKeepAllOutputs() {
  Am a = engine(); SummaryParity.check(a, NOW);
  a.ajl("Production", Cx.GENERAL, NOW - 3_600_000L);
  Ad b = a.getActiveSession(); long c = NOW - 1_000L;
  for (Ai d : Ai.values()) for (Aj e : Aj.values()) {
   Ac f = receipt(d, e, c++);
   f.counted = d.ordinal() % 2 == 0;
   f.confidence = Bd.values()[e.ordinal() % Bd.values().length];
   f.correction = Ah.values()[e.ordinal() % Ah.values().length];
   f.actionKind = Au.values()[(d.ordinal() + e.ordinal()) % Au.values().length].name();
   b.kf(f, 2_000);
  }
  b.setProfitTargetGp(5_000_000L); b.setActiveTimeTargetMillis(7_200_000L);
  SummaryParity.check(a, NOW);
 }
 @Test public void sameMoneyRevisionKeepsClockPauseAndActivityFresh() {
  Am a = engine(); a.ajl("Cooking", Cx.GENERAL, NOW - 10_000L);
  Ad b = a.getActiveSession(); b.kf(receipt(Ai.LOOT, Aj.LOOT, NOW - 1_000L), 100);
  Sm c = a.summary();
  assertSame(c, a.summary());
  b.pause(NOW, Ed.MANUAL); b.setActivityHint("Cooking", NOW);
  assertSame(c, a.summary());
  SummaryParity.check(a, NOW + 60_000L);
  b.resume(NOW + 60_000L);
  assertSame(c, a.summary());
  SummaryParity.check(a, NOW + 120_000L);
 }
 @Test public void correctionsInvalidateAndEarlierFactsOwnTheirFlows() {
  Am a = engine(); a.ajl("Vorkath", Cx.GENERAL, NOW - 3_600_000L);
  Ad b = a.getActiveSession();
  Ac c = Tx.of(NOW - 1_000L, Ai.LOOT, Aj.LOOT, "Loot", true,
  Collections.singletonList(new Ab(1515, "Yew logs", 3L, 200, 600L))); b.kf(c, 100);
  Sm d = a.summary(); long e = d.metrics(b, NOW).net;
  assertTrue(b.qi(c.id, Ah.IGNORE, NOW, "Observed correction"));
  assertNotSame(d, a.summary());
  assertEquals(e, d.metrics(b, NOW).net);
  assertEquals(Ah.AUTO, d.fact(b).t.get(0).correction);
  assertNotSame(c.flows.get(0), d.fact(b).t.get(0).flows.get(0));
  SummaryParity.check(a, NOW);
 }
 @Test public void linkedClosedCompactedExcludedAndLiveRunsKeepTheirFallbacks() {
  Am a = engine(); SavedState.Ap b = a.avg("Vorkath", 5_000L, 3_600_000L, true, null);
  for (int c = 0; c < 3; c++) {
   assertTrue(a.startGrind("Vorkath", b.getGrindId(), 5_000L, 3_600_000L, NOW - (c + 1) * 7_200_000L));
   Ad d = a.getActiveSession(); d.kf(receipt(Ai.LOOT, Aj.LOOT, d.startedAtEpochMillis + 1_000L), 100);
   d.setExcludedFromAverages(c == 1); a.sx(d.startedAtEpochMillis + 3_600_000L);
   if (c == 2) d.pj(d.endedAtEpochMillis, t -> false);
  }
  assertTrue(a.startGrind("Vorkath", b.getGrindId(), null, null, NOW - 20_000L));
  a.getActiveSession().kf(receipt(Ai.LOOT, Aj.LOOT, NOW - 1_000L), 100);
  SummaryParity.check(a, NOW);
 }
 @Test public void closeAndRestoreReplaceTheFactsWithoutASecondCounter() {
  Am a = engine(); a.ajl("Mining", Cx.GENERAL, NOW - 3_600_000L);
  Ad b = a.getActiveSession(); b.kf(receipt(Ai.GAIN, Aj.GENERIC, NOW - 1_000L), 100);
  Sm c = a.summary(); b.close(NOW);
  assertNotSame(c, a.summary());
  SummaryParity.check(a, NOW);
  Am d = engine(); d.ajl("Other", Cx.GENERAL, NOW);
  a.restore(d.sl(), NOW);
  assertNotSame(c, a.summary());
  SummaryParity.check(a, NOW);
 }
 @Test public void scopePreservesSaturatingLedgerOrderAndUnfilteredHeroMoney() {
  Am a = engine(); a.ajl("Extremes", Cx.GENERAL, NOW - 3_600_000L);
  Ad b = a.getActiveSession();
  for (long c : new long[]{Long.MAX_VALUE - 1L, 100L, -Long.MAX_VALUE + 2L}) {
   b.kf(Tx.of(NOW - 1_000L, c < 0L ? Ai.CONSUMPTION : Ai.LOOT, c < 0L ? Aj.GENERIC : Aj.LOOT,
   "Extreme", true, Collections.singletonList(new Ab(561, "Nature rune", c < 0L ? -1L : 1L, 1, c))), 100);
  }
  SummaryParity.check(a, NOW);
  Ca c = Nc.capture(a, NOW, new Dz(false, null, Long.MAX_VALUE, Bo.NONE, "", false, ""));
  assertEquals(a.getMetrics(NOW).net, c.net);
 }
 @Test public void addingAndLinkingDefinitionsRefreshesWithoutChangingDomainRevision() {
  Am a = engine(); a.ajl("Vorkath", Cx.GENERAL, NOW - 3_600_000L);
  Ad b = a.getActiveSession(); b.kf(receipt(Ai.LOOT, Aj.LOOT, NOW - 1_000L), 100);
  a.sx(NOW); Sm c = a.summary(); long d = a.getRevision();
  SavedState.Ap e = a.avg("Vorkath", 5_000L, null, true, b.getId());
  assertEquals(d, a.getRevision()); assertNotSame(c, a.summary());
  assertEquals(1, a.summary().g.get(e.getGrindId()).count);
  SummaryParity.check(a, NOW);
  c = a.summary(); assertTrue(a.deleteSavedGrind(e.getGrindId()));
  assertEquals(d, a.getRevision()); assertNotSame(c, a.summary());
  SummaryParity.check(a, NOW);
 }
 @Test public void liveBookingsReuseClosedFactsButClosedCorrectionsReplaceThem() {
  Am a = engine(); a.ajl("Finished", Cx.GENERAL, NOW - 7_200_000L);
  Ad b = a.getActiveSession();
  Ac c = Tx.of(NOW - 7_100_000L, Ai.LOOT, Aj.LOOT, "Loot", true,
  Collections.singletonList(new Ab(1515, "Yew logs", 3L, 200, 600L))); b.kf(c, 100);
  a.sx(NOW - 3_600_000L); Sm.F d = a.summary().fact(b);
  a.ajl("Running", Cx.GENERAL, NOW - 1_000L);
  a.getActiveSession().kf(receipt(Ai.PROCESSING, Aj.PRODUCTION, NOW), 100);
  assertSame(d, a.summary().fact(b));
  assertTrue(b.qi(c.getId(), Ah.IGNORE, NOW, "Correction"));
  assertNotSame(d, a.summary().fact(b));
  SummaryParity.check(a, NOW);
 }
}
