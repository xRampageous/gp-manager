package com.gpmanager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.MenuAction;
import org.junit.Test;
import static org.junit.Assert.*;

/** Old and new rules see every branch, phrase, overlap, normalization boundary and near miss. */
public class DwRulesParityTest {
 @Test public void menuActionsIncludeEveryPublicEnumValueAndNull() {
  assertEquals(Dwo.yj(null), Dw.a1(null));
  for (MenuAction a : MenuAction.values()) assertEquals(a.name(), Dwo.yj(a), Dw.a1(a));
 }

 @Test public void menuAndChatPredicatesAgreeOnEveryDistinguishedPhrase() {
  List<String> inputs = inputs();
  for (String a : inputs) {
   assertEquals("spend: " + a, Dwo.wy(a), Dw.b1(a));
   assertEquals("offered: " + a, Dwo.xi(a), Dw.c1(a));
   assertEquals("spared: " + a, Dwo.vt(a), Dw.g1(a));
   assertEquals("chat: " + a, Dwo.vy(a), Dw.h1(a));
   assertEquals("unchanged transform data: " + a, Dwo.ajx(a), Dw.ajx(a));
   assertEquals("unchanged gather data: " + a, Dwo.ka(a), Dw.ka(a));
   assertEquals("gather: " + a, Dwo.xb(a), Dw.i1(a));
  }
 }

 @Test public void itemTargetsIncludeEveryFuelBoneArrowAndOverlappingPhrase() {
  List<String> targets = inputs();
  for (String a : Arrays.asList(null, "", "use", " USE ", "use-on", "cast", " CAST ", "casting", "xuse")) {
   for (String b : targets) {
    assertEquals("fuel: " + a + " / " + b, Dwo.ww(a,b), Dw.d1(a,b));
    assertEquals("altar: " + a + " / " + b, Dwo.xt(a,b), Dw.e1(a,b));
    assertEquals("offering: " + a + " / " + b, Dwo.xp(a,b), Dw.f1(a,b));
   }
  }
 }

 static List<String> inputs() {
  Set<String> atoms = new LinkedHashSet<>(Arrays.asList("", " ", "eat", "drink", "bury", "scatter", "empty", "break",
   "release", "cast", "activate", "light", "tend-to", "tend to", "offer", "pray", "pray-at", "use", "rake", "plant",
   "logs", "juniper", "redwood", "blisterwood", "pyre", "kindling", "tinderbox", "->", "→", "fire", "campfire", "fletching knife", "brazier", "funeral pyre", "bruma kindling", "sacred oil", "altar", "bones",
   "ashes", "ensouled", "blessed bone", "sinister offering", "dark lord spares", "dig", "you bury", "you offer ",
   "you scatter ", "you drink ", "you eat ", "dose of potion left", "O'Brien", "O’Brien"));
  atoms.add(Ak.msg("ao")); atoms.add(Ak.msg("aa"));
  atoms.addAll(Dwo.TRANSFORM.keySet()); atoms.addAll(Dwo.GATHER.keySet());
  // Every compiled clause has a witness, and each pair tests conjunctions and precedence.
  Set<String> paired = new LinkedHashSet<>(atoms);
  for (String a : atoms) for (String b : atoms) paired.add(a + " " + b);
  Set<String> expanded = new LinkedHashSet<>();
  expanded.add(null);
  for (String a : paired) {
   expanded.add(a); expanded.add(" " + a + " "); expanded.add(a.toUpperCase(java.util.Locale.ROOT));
   expanded.add("x" + a); expanded.add(a + "x"); expanded.add("\t" + a + "\t");
   if (!a.isEmpty()) expanded.add(a.substring(0, a.length()-1));
   expanded.add(a.replace("'", "’"));
  }
  return new ArrayList<>(expanded);
 }
}
