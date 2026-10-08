package com.gpmanager;
import org.junit.Test;
import static org.junit.Assert.*;
/** Account ABA, quantity conservation, and inclusive expiry for every inbox kind. */
public class EbTest {
 @Test public void ownerCycleRejectsEveryOldObservationEvenWhenReinserted() {
  Eb b = new Eb();
  for (int kind = Eb.FE; kind <= Eb.BO; kind++) {
   b.owner(1001L);
   Ev old = b.add(kind, 526, 3L, 5, "A");
   b.owner(2002L);
   assertTrue(b.open.isEmpty());
   b.owner(1003L);
   b.open.add(old);
   assertNull(b.peek(kind));
   assertEquals(0L, b.take(kind, 526, 3L, e -> true));
  }
 }
 @Test public void oversizedUseIsBoundedAndCannotRepeat() {
  for (int kind = Eb.FE; kind <= Eb.BO; kind++) {
  Eb b = new Eb();
  b.add(kind, 526, 3L, 5, "bag");
  assertEquals(2L, b.take(kind, 526, 2L, e -> true));
  assertEquals(1L, b.take(kind, 526, 100L, e -> true));
  assertEquals(0L, b.take(kind, 526, 1L, e -> true));
  }
 }
 @Test public void expiryIncludesTheBoundaryTick() {
  for (int kind = Eb.FE; kind <= Eb.BO; kind++) {
  Eb b = new Eb();
  b.tick(10);
  b.add(kind, 526, 3L, 5, null);
  b.tick(15);
  assertEquals(1L, b.take(kind, 526, 1L, e -> true));
  b.tick(16);
  assertEquals(0L, b.take(kind, 526, 2L, e -> true));
  }
 }
 @Test public void sourcePredicateAndItemMustBothMatch() {
  Eb b = new Eb();
  b.add(Eb.CH, 526, 3L, 5, "left");
  b.add(Eb.CH, 526, 4L, 5, "right");
  assertEquals(0L, b.take(Eb.CH, 995, 100L, e -> true));
  assertEquals(4L, b.take(Eb.CH, 526, 100L, e -> "right".equals(e.note)));
  assertEquals(3L, b.peek(Eb.CH).qty);
 }
}
