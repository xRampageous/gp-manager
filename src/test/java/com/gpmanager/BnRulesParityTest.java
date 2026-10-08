package com.gpmanager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pre-table oracle versus compiled rules, with all names, doses, overlaps and null contracts. */
public class BnRulesParityTest {
 @Test public void everyActionPrefixAndOverlappingVerbKeepsItsPrecedence() {
  for (String a : DwRulesParityTest.inputs()) assertEquals(a, Bno.tu(a), Bn.a1(a));
  for (String a : Arrays.asList("drink", "eat", "bury", "scatter", "offer", "cast", "light", "tend-to", "tend to", "use")) {
   for (String b : Arrays.asList("drink", "eat", "bury", "scatter", "offer", "cast")) {
    assertEquals(a+b, Bno.tu(a+b), Bn.a1(a+b));
    assertEquals(b+a, Bno.tu(b+a), Bn.a1(b+a));
   }
  }
 }

 @Test public void everyVesselRuneClaimAndIngredientNameIsIdentical() {
  for (String a : names()) {
   assertEquals("vessel " + a, Bno.wl(a), Bn.b1(a));
   assertEquals("rune " + a, Bno.xu(a), Bn.d1(a));
   assertEquals("claim " + a, Bno.claim(a), Bn.e1(a));
   assertEquals("ingredient " + a, result(() -> Bno.xc(a)), result(() -> Bn.f1(a)));
  }
 }

 @Test public void everyDoseAndPartialFoodStepKeepsItsOutcome() {
  List<String> names = names();
  for (String a : names) for (String b : names)
   assertEquals(a+" / "+b, Bno.wu(a,b), Bn.c1(a,b));
 }

 static Object result(Supplier<?> s) {
  try { return s.get(); } catch (RuntimeException e) { return e.getClass(); }
 }

 static List<String> names() {
  Set<String> atoms = new LinkedHashSet<>(Bno.HERBLORE_INGREDIENTS);
  atoms.addAll(Arrays.asList("", "empty ", "vial", "jug", "beer glass", "coin pouch", "coin pouches", "reward casket (hard)", "clue scroll (elite)", "impling jar", "eclectic impling jar", "impling jars",
   "air rune", "steam rune", "rune", "rune pouch", "pizza", "cake", "pie", "shark", "2/3 cake", "slice of cake",
   "half a meat pie", "1/2 meat pizza", "prayer potion", "prayer potion(4)", "grimy guam leaf",
   "guam leaf", "weed", "leaf", "grass", "root", "dust", "berries", "berry", "horn", "fungus", "nest", "eggs",
   "guam leaf(1)", "O'Brien", "O’Brien"));
  for (String suffix : Arrays.asList("weed", "leaf", "grass", "root", "dust", "berries", "berry", "horn", "fungus", "nest", "eggs")) atoms.add("x " + suffix);
  for (int i=0; i<=9; i++) atoms.add("prayer potion("+i+")");
  Set<String> out = new LinkedHashSet<>(); out.add(null);
  for (String a : atoms) {
   out.add(a); out.add(" "+a+" "); out.add(a.toUpperCase(java.util.Locale.ROOT));
   out.add("x"+a); out.add(a+"x"); out.add(a.replace("'","’"));
  }
  return new ArrayList<>(out);
 }
}
