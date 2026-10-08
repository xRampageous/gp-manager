package com.gpmanager;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
/** Frozen labels cover every name/skill, overlapping curated names, raw whitespace and Unicode. */
public class LabelRulesParityTest {
 @Test public void everyLabelAndResolutionFlagAgrees() {
  Set<String> names = new LinkedHashSet<>(Arrays.asList("", " ", "General", "NPC loot", "PKing", "Trading", "Death reclaim",
   "Goblin", "O'Brien", "O’Brien", "Fıshıng", "ſtrength", "FİSHİNG"));
  names.addAll(Alo.CURATED);names.addAll(Alo.CURATED_SHORT);names.addAll(Alo.COMBAT);
  names.addAll(AgilityCourses.BY_REGION.values());
  for (net.runelite.api.Skill a : net.runelite.api.Skill.values()) names.add(a.getName());
  Set<String> cases = new LinkedHashSet<>();cases.add(null);
  for (String a : names) for (String b : names) {
   String c = a + " " + b;
   cases.add(a);cases.add(c);cases.add(" " + c + " ");cases.add(c.toUpperCase(Locale.ROOT));
   cases.add(c.toLowerCase(Locale.ROOT));cases.add("x"+c);cases.add(c+"x");cases.add(c.replace("'","’"));
   if (!a.isEmpty()) cases.add(a.substring(0,a.length()-1));
  }
  for (String a : cases) {
   assertEquals(String.valueOf(a),Alo.named(a),ActivityLabel.a1(a));
   assertEquals(String.valueOf(a),Alo.curated(a),ActivityLabel.b1(a));
   assertEquals(String.valueOf(a),Alo.clean(a),ActivityLabel.c1(a));
   for (int flag=0;flag<8;flag++) for (String owner : Arrays.asList(null,"","Overall","Vorkath","Theatre of Blood")) {
    boolean fighting=(flag&1)!=0, session=(flag&2)!=0, free=(flag&4)!=0;
    assertEquals(Alo.resolve(fighting,a,owner,session,free),ActivityLabel.d1(fighting,a,owner,session,free));
   }
  }
 }
}
