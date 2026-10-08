package com.gpmanager;
import java.util.*;
import java.lang.reflect.Proxy;
import net.runelite.api.Client;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static org.junit.Assert.*;
/** Typed outcomes retain exact component quantities and physical-target isolation. */
public class ChargeOutcomeTest {
 /** Legacy null meant no booking; SAME and SPEND must never satisfy those assertions. */
 static void none(Cm c) { none("no booking", c); }
 static void none(String n, Cm c) {
  assertNotNull(n, c);
  assertTrue(n, c.k == Cm.K.SEED || c.k == Cm.K.LOAD || c.k == Cm.K.MISMATCH);
  assertTrue(n, c.componentDeltas.isEmpty());
 }
 static Cm kind(Cm.K k, Cm c) {
  assertNotNull(c);
  assertEquals(k, c.k);
  return c;
 }
 static Ar tr(long n) { return Ar.acs("Your Trident of the seas has " + n + " charges."); }
 @Test public void firstAndUnchangedReadsNameDifferentOutcomes() {
  MeasuredChargeReadTracker t = new MeasuredChargeReadTracker();
  kind(Cm.K.SEED, t.observe(tr(10), "a", 1));
  Cm c = kind(Cm.K.SAME, t.observe(tr(10), "a", 2));
  assertTrue(c.componentDeltas.isEmpty());
  assertEquals(2L, t.baseline("a").atEpochMillis);
 }
 @Test public void loadSeedsTheNextExactSpendWithoutAComponentLoss() {
  MeasuredChargeReadTracker t = new MeasuredChargeReadTracker();
  kind(Cm.K.SEED, t.observe(tr(10), "a", 1));
  Cm load = kind(Cm.K.LOAD, t.observe(tr(20), "a", 2));
  assertTrue(load.componentDeltas.isEmpty());
  Cm spend = kind(Cm.K.SPEND, t.observe(tr(19), "a", 3));
  assertEquals(4, spend.componentDeltas.size());
  assertEquals(-1L, spend.componentDeltas.stream().filter(d -> d.itemId == ItemID.DEATHRUNE).findFirst().get().quantityDelta);
 }
 @Test public void aMixedBlowpipeLoadKeepsIndependentMeasuredScaleSpend() {
  MeasuredChargeReadTracker t = new MeasuredChargeReadTracker();
  t.observe(Ar.acs("Darts: Adamant dart x 10. Scales: 100 (1.0%)."), "a", 1);
  Cm c = kind(Cm.K.SPEND, t.observe(Ar.acs("Darts: Adamant dart x 12. Scales: 98 (1.0%)."), "a", 2));
  assertEquals(1, c.componentDeltas.size());
  assertEquals(ItemID.SNAKEBOSS_SCALE, c.componentDeltas.get(0).itemId);
  assertEquals(-2L, c.componentDeltas.get(0).quantityDelta);
 }
 @Test public void anUnsupportedReadMismatchesAndClearsThePriorBaseline() {
  MeasuredChargeReadTracker t = new MeasuredChargeReadTracker();
  t.observe(tr(10), "a", 1);
  kind(Cm.K.MISMATCH, t.observe(Ar.acs("Darts: Exotic dart x 100. Scales: 90 (1.0%)."), "a", 2));
  assertNull(t.baseline("a"));
  kind(Cm.K.SEED, t.observe(tr(9), "a", 3));
 }
 @Test public void aMissingReadKeepsTheLastExactBaselineAsBefore() {
  MeasuredChargeReadTracker t = new MeasuredChargeReadTracker();
  t.observe(tr(10), "a", 1);
  kind(Cm.K.MISMATCH, t.observe(null, "a", 2));
  assertNotNull(t.baseline("a"));
  kind(Cm.K.SPEND, t.observe(tr(9), "a", 3));
 }
 @Test public void aVariantMismatchAndTwoPhysicalTargetsRemainIsolated() {
  MeasuredChargeReadTracker t = new MeasuredChargeReadTracker();
  kind(Cm.K.SEED, t.observe(tr(10), "a", 1));
  kind(Cm.K.SEED, t.observe(tr(2), "b", 2));
  kind(Cm.K.SPEND, t.observe(tr(9), "a", 3));
  kind(Cm.K.SAME, t.observe(tr(2), "b", 4));
  kind(Cm.K.MISMATCH, t.observe(Ar.acs("Your Trident of the swamp has 8 charges."), "a", 5));
  kind(Cm.K.SPEND, t.observe(Ar.acs("Your Trident of the swamp has 7 charges."), "a", 6));
 }
 @Test public void aMeasuredLoadArmsTransferWithoutRequiringChargeWordingOrPrices() {
  GpManagerConfig c = new GpManagerConfig() {};
  Am e = new Am(d -> Collections.emptyList(), new TransactionClassifier(), c);
  e.rm(1_000L);
  Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class}, (p, m, a) -> {
   if (m.getName().equals("getTickCount")) return 10;
   if (m.getReturnType() == boolean.class) return false;
   if (m.getReturnType() == int.class) return 0;
   if (m.getReturnType() == long.class) return 0L;
   return null;
  });
  ChargeIntake i = new ChargeIntake(client, null, c, e, null);
  assertTrue(i.ck(Ar.V.TRIDENT_SEAS, "a", 10, 2));
  assertTrue(i.abt("Your Trident of the seas has 10 charges.", 1_001L));
  assertNull(e.eb.peek(Eb.SF));
  assertTrue(i.ck(Ar.V.TRIDENT_SEAS, "a", 10, 2));
  assertTrue(i.abt("Your Trident of the seas has 20 charges.", 1_002L));
  assertNotNull(e.eb.peek(Eb.SF));
  assertTrue(e.getActiveSession().getTransactions().isEmpty());
 }
 @Test public void nonSpendOutcomesCannotAuthorizeValuedLosses() {
  Cm c = new Cm(Ar.V.TRIDENT_SEAS, Collections.singletonList(new Cm.ComponentDelta(ItemID.DEATHRUNE, -1)), Cm.K.LOAD);
  assertEquals(0L, c.aui(Collections.singletonList(new Ab(ItemID.DEATHRUNE, "Death rune", -1, 100, -100, Av.GRAND_EXCHANGE)), Collections.emptySet()));
 }
}
