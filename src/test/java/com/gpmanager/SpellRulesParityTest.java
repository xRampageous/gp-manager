package com.gpmanager;
import java.util.*;
import java.util.function.Supplier;
import org.junit.Test;
import static org.junit.Assert.*;
/** Every frozen recipe, whole casts, staff coverage, combinations, bad/zero flows and ambiguity. */
public class SpellRulesParityTest {
 @Test public void everyRecipeAndDistinguishedFlowShapeAgrees() {
  List<List<Ab>> cases = new ArrayList<>();
  cases.add(null); cases.add(Collections.emptyList()); cases.add(Collections.singletonList(null));
  cases.add(Arrays.asList(new Ab(1,null,-1,1,-1)));
  for (String[] row : Spo.TABLE) {
   for (long multiple : new long[]{-1,0,1,2,10,Long.MAX_VALUE}) {
    List<Ab> exact = new ArrayList<>();
    for (String part : row[2].split(" ")) {
     String[] kv = part.split(":"); exact.add(flow(kv[1] + " rune", Long.parseLong(kv[0])*multiple));
    }
    cases.add(exact);
    List<Ab> reverse = new ArrayList<>(exact); Collections.reverse(reverse); cases.add(reverse);
    List<Ab> staff = new ArrayList<>();
    for (Ab a : exact) if (!Spo.ELEMENTS.contains(" " + a.itemName.replace(" rune", "") + " ")) staff.add(a);
    cases.add(staff);
    for (int i=0;i<exact.size();i++) {
     List<Ab> omitted = new ArrayList<>(exact); omitted.remove(i); cases.add(omitted);
     Ab rune = exact.get(i);
     for (long delta : new long[]{-1,1}) {
      List<Ab> wrong = new ArrayList<>(exact); wrong.set(i,flow(rune.itemName,-rune.quantityDelta+delta)); cases.add(wrong);
     }
     for (String combination : Spo.COMBINATIONS.keySet()) {
      List<Ab> combined = new ArrayList<>(exact); combined.set(i,flow(combination+" rune",-rune.quantityDelta)); cases.add(combined);
     }
    }
    for (String name : Arrays.asList("Nature rune","Steel dart","O'Brien rune","O’Brien rune","Air run","AIR RUNE"," air rune ")) {
     List<Ab> foreign = new ArrayList<>(exact); foreign.add(flow(name,1)); cases.add(foreign);
    }
    List<Ab> split = new ArrayList<>();
    for (Ab a : exact) { split.add(flow(a.itemName,-a.quantityDelta/2)); split.add(flow(a.itemName,-a.quantityDelta+a.quantityDelta/2)); }
    cases.add(split);
   }
   assertEquals(Spo.icon(row[0]), Spells.icon(row[0]));
   assertEquals(Spo.icon(row[0].toUpperCase(Locale.ROOT)),Spells.icon(row[0].toUpperCase(Locale.ROOT)));
  }
  assertEquals(Spo.icon(null),Spells.icon(null)); assertEquals(Spo.icon(""),Spells.icon(""));
  for (List<Ab> flows : cases) {
   assertEquals(result(() -> Spo.named(flows)), result(() -> Spells.a1(flows)));
   for (String[] row : Spo.TABLE)
    assertEquals(row[0], result(() -> Spo.fit(row[2],flows)), result(() -> Spells.f1(Spells.R.get(row[0]),flows)));
  }
 }
 static Ab flow(String name,long count) { return new Ab(1,name,-count,1,-count); }
 static Object result(Supplier<?> call) {
  try { return call.get(); } catch (RuntimeException a) { return a.getClass(); }
 }
}
