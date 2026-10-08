package com.gpmanager;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Frozen implementations cover every phrase, action, region fallback and first-match overlap. */
public class StorageRulesParityTest {
 @Test public void everyPhraseOverlapAndRegionAgrees() {
  Set<String> atoms = new LinkedHashSet<>(Arrays.asList("", " ", "leave", "exit", "restore", "enter", "join", "wipe",
   "loadout", "withdraw", "take", "clear", "end", "last man standing", "lms", "raid bag", "private storage",
   "cox storage", "toa storage", "group storage", "shared storage", "gim", "O'Brien", "O’Brien"));
  for (Nso.Kind a : Nso.Kind.values()) for (String b : a.phrases.split("\\|")) {
   atoms.add(b.replace('+', ' ')); atoms.addAll(Arrays.asList(b.split("\\+")));
  }
  Set<String> cases = new LinkedHashSet<>(atoms);
  for (String a : atoms) for (String b : atoms) cases.add(a + " " + b);
  for (String a : Arrays.asList("last man standing", "lms", "raid bag", "private storage", "cox storage", "toa storage",
   "group storage", "shared storage", "gim")) for (String b : atoms) for (String c : Arrays.asList("leave", "exit",
   "restore", "enter", "join", "wipe", "loadout", "withdraw", "take", "clear", "end")) cases.add(a + " " + b + " " + c);
  Set<String> expanded = new LinkedHashSet<>(); expanded.add(null);
  for (String a : cases) {
   expanded.add(a); expanded.add(" " + a + " "); expanded.add(a.toUpperCase(Locale.ROOT));
   expanded.add("x" + a); expanded.add(a + "x"); expanded.add("<col=ffffff>" + a + "</col>");
   expanded.add(a.replace("'", "’"));
   if (!a.isEmpty()) expanded.add(a.substring(0, a.length()-1));
  }
  Set<Integer> regions = new LinkedHashSet<>(Arrays.asList(-1, -2, 0, 1, 65535));
  regions.addAll(MinigameRegionHints.KINDS.keySet());
  for (String a : expanded) {
   assertEquals("storage " + a, name(Nso.classify(a)), name(NeutralStorageClassifier.a1(a)));
   assertEquals("receipt " + a, Nso.ajw(Nso.classify(a)), NeutralStorageClassifier.ajw(NeutralStorageClassifier.a1(a)));
   assertEquals("default " + a, name(Mgo.classify(a)), name(MinigameTransferClassifier.a1(a)));
   for (int b : regions) {
    assertEquals("region " + b + " " + a, name(Mgo.classify(a,b)), name(MinigameTransferClassifier.a1(a,b)));
    assertEquals(Mgo.ajw(Mgo.classify(a,b)), MinigameTransferClassifier.ajw(MinigameTransferClassifier.a1(a,b)));
   }
  }
 }
 @Test public void labelsAndCompiledTablesLoadOnce() {
  assertSame(Ak.x("d26"), Ak.x("d26")); assertSame(Ak.r("d26"), Ak.r("d26"));
  for (MinigameTransferClassifier.Event a : MinigameTransferClassifier.Event.values())
   assertEquals(Mgo.Event.valueOf(a.name()).note, a.note);
  for (NeutralStorageClassifier.Kind a : NeutralStorageClassifier.Kind.values()) {
   assertEquals(Nso.Kind.valueOf(a.name()).note, a.note);
   assertEquals(Nso.Kind.valueOf(a.name()).phrases, a.phrases);
  }
 }
 static String name(Enum<?> a) { return a == null ? null : a.name(); }
}
