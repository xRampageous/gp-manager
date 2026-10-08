package com.gpmanager;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Every old verb/name and its near misses, overlapping service names, null and apostrophes. */
public class CatalogRulesParityTest {
 @Test public void reclaimAndDispenserPredicatesKeepAllDistinguishedInputs() {
  Set<String> names = new LinkedHashSet<>(Arrays.asList("", " ", "Banker", "Grave", "gravestone", "graveyard",
   "agility dispenser", "ticket dispenser", "agility dispense", "ticket dispense", "Death's Office", "Death’s Office"));
  names.addAll(Bro.BY_INTERACTABLE.keySet());
  Set<String> targets = new LinkedHashSet<>(); targets.add(null);
  for (String a : names) for (String b : names) {
   String c = a + " " + b;
   targets.add(a); targets.add(c); targets.add(c.toUpperCase(Locale.ROOT)); targets.add(" " + c + " ");
   targets.add("x" + c); targets.add(c + "x"); targets.add(c.replace("'", "’")); targets.add(c + "\nextra");
   if (!a.isEmpty()) targets.add(a.substring(0,a.length()-1));
  }
  Set<String> options = new LinkedHashSet<>(); options.add(null);
  for (String a : Arrays.asList("", "claim", "reclaim", "retrieve", "loot", "collect", "talk", "check", "search", "open")) {
   options.add(a); options.add(a.toUpperCase(Locale.ROOT)); options.add(" " + a + " ");
   options.add(a + "-all"); options.add("x" + a); options.add(a + "\nextra");
   if (!a.isEmpty()) options.add(a.substring(0,a.length()-1));
  }
  for (String a : targets) {
   assertEquals("dispenser " + a, Ago.xd(a), AgilityCourses.a1(a));
   for (String b : options) assertEquals(b + " / " + a, key(Bro.axr(b,a)), key(BossRetrievalCatalogue.a1(b,a)));
  }
  for (Bro.Service a : Bro.BY_INTERACTABLE.values()) {
   BossRetrievalCatalogue.Service b = BossRetrievalCatalogue.a1("Claim", a.interactable);
   assertEquals(a.activity, b.activity); assertEquals(a.interactable, b.interactable);
   assertEquals(a.expectedFee, b.expectedFee); assertEquals(a.note, b.note); assertEquals(a.ambiguousTarget,b.ambiguousTarget);
   for (long c : new long[]{Long.MIN_VALUE,-1,0,1,25000,50000,100000,Long.MAX_VALUE}) assertEquals(a.why(c),b.why(c));
  }
 }
 static String key(Bro.Service a) { return a == null ? null : a.key; }
 static String key(BossRetrievalCatalogue.Service a) { return a == null ? null : a.key; }
}
