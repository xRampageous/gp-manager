package com.gpmanager;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
/** Every reward and key-chest name, overlapping pools, curly apostrophe and near misses. */
public class RewardRulesParityTest {
 @Test public void everyOldNameAndOverlapAgrees() {
  Set<String> names = new LinkedHashSet<>(Arrays.asList("", " ", "Goblin", "Abyssal demon", "chest", "O'Brien", "O’Brien"));
  for (String[] a : Rco.SOURCES) names.add(a[0]);
  for (KeyChestCatalogue.Entry a : KeyChestCatalogue.ENTRIES) names.add(a.chestName);
  Set<String> cases = new LinkedHashSet<>(); cases.add(null);
  for (String a : names) for (String b : names) {
   String c = a + " " + b;
   cases.add(a); cases.add(c); cases.add(c.toUpperCase(Locale.ROOT)); cases.add(" " + c + " ");
   cases.add("x" + c); cases.add(c + "x"); cases.add(c.replace("'", "’")); cases.add(c + "\nextra");
   if (!a.isEmpty()) cases.add(a.substring(0,a.length()-1));
  }
  for (String a : cases) assertEquals(String.valueOf(a), Rco.xn(a), RewardChestCatalogue.a1(a));
 }
}
