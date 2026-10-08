package com.gpmanager;
import java.util.*;
import net.runelite.api.events.ItemDespawned;
import org.junit.Test;
import static com.gpmanager.ReviewFixesTest.*;
import static org.junit.Assert.*;
/** Real monetary paths retain their receipts while proof is reserved, exhausted or fenced. */
public class InboxMoneyTest {
 @Test public void logoutDuringABindCompletesItsNewGeneration() throws Exception {
  Ei c = new Ei(null, null, null, null, engine());
  var entered = new java.util.concurrent.CountDownLatch(1);
  var release = new java.util.concurrent.CountDownLatch(1);
  try {
   c.agw(() -> false, false, "test bind", persist -> {
    entered.countDown();
    try { release.await(3, java.util.concurrent.TimeUnit.SECONDS); }
    catch (InterruptedException ex) { throw new AssertionError(ex); }
    return true;
   });
   assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
   long generation = c.lifecycleRequest.get();
   c.onLogout(false);
   release.countDown();
   c.lifecycleExecutor.shutdown();
   assertTrue(c.lifecycleExecutor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS));
   assertEquals(generation + 1, c.lifecycleRequest.get());
   assertFalse(c.lifecyclePending);
   assertFalse(c.py());
  } finally {
   release.countDown();
   c.lifecycleExecutor.shutdownNow();
  }
 }
 @Test public void ownerFenceRetainsDurableGraveContinuityAndClearsTheInteraction() {
  Am e = engine();
  e.deathReclaim.ace(Map.of(BONES, 2L));
  e.deathReclaim.reclaimTicks = 10;
  e.neutralZoneStoredItems.put(BONES, 1L);
  e.eb.owner(1001L);
  assertTrue(e.deathReclaim.isAwaitingReclaim());
  assertEquals(Long.valueOf(2L), e.deathReclaim.carriedAtDeath.get(BONES));
  assertEquals(0, e.deathReclaim.reclaimTicks);
  assertTrue(e.neutralZoneStoredItems.isEmpty());
 }
 @Test public void existingCoordinatorGenerationFencesAnAccountCycle() {
  Am e = engine();
  Ei c = new Ei(null, null, null, null, e);
  c.activeIdentity = new TrackingIdentity("A", 1);
  c.eo();
  Ev fee = e.eb.add(Eb.FE, -1, 500L, 10, "Reclaim fee (bank)");
  c.activeIdentity = new TrackingIdentity("B", 2);
  c.eo();
  c.activeIdentity = new TrackingIdentity("A", 1);
  c.eo();
  assertEquals(3L, c.lifecycleRequest.get());
  assertEquals(1003L, e.eb.owner);
  e.eb.open.add(fee);
  e.fee(fee, T0);
  assertEquals(0L, e.getMetrics(T0).costs);
 }
 @Test public void bagBooksOnlyThePrivateQuantityAndNeverTwice() {
  Am e = engine();
  e.zk(Map.of(BONES, 2L), 50, "Loot from Guard", "Guard");
  int[] tick = {11};
  Plugin p = plugin(e, tick);
  Ev click = take(p);
  click.item = BONES; click.at = 10; click.x = 10; click.y = 10;
  p.onItemDespawned(new ItemDespawned(ReviewFollowupTest.tile(10, 10), ReviewFollowupTest.bones()));
  assertEquals(2L, qty(p, Eb.BG));
  assertFalse(e.yours(BONES));
  p.onItemDespawned(new ItemDespawned(ReviewFollowupTest.tile(10, 10), ReviewFollowupTest.bones()));
  tick[0]++;
  p.bagSettle();
  e.adj(Cc.empty(), T0 + 600);
  e.adj(Cc.empty(), T0 + 1200);
  assertEquals(200L, e.getMetrics(T0 + 1200).revenue);
  p.bagSettle();
  e.adj(Cc.empty(), T0 + 1800);
  assertEquals(200L, e.getMetrics(T0 + 1800).revenue);
 }
 @Test public void reservationKeepsTheFirstEncounterAheadOfTheSecond() {
  Am e = engine();
  e.loot(Map.of(BONES, 1L), 50, "first", "Guard", Aj.LOOT, "one");
  e.loot(Map.of(BONES, 1L), 50, "second", "Goblin", Aj.LOOT, "two");
  assertEquals(1L, e.eb.reserve(Eb.LT, BONES, 1L, Eb.LP));
  Cy source = e.qc(List.of(new Ab(BONES, "Bones", 1L, 100, 100L)));
  assertEquals("first", source.note);
  assertEquals("one", source.encounterId);
  assertEquals(1L, e.eb.peek(Eb.LT).qty);
 }
 @Test public void ordinaryPickupAlsoExhaustsPrivateProof() {
  Am e = engine();
  e.zk(Map.of(BONES, 1L), 6, "Loot from Guard", "Guard");
  e.qc(List.of(new Ab(BONES, "Bones", 1L, 100, 100L)));
  assertFalse(e.yours(BONES));
  assertEquals(0L, e.eb.reserve(Eb.LT, BONES, 1L, Eb.LP));
 }
}
