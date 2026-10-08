package com.gpmanager;

import com.google.gson.Gson;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.StatChanged;
import net.runelite.client.callback.ClientThread;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

/** Offline plugin lifecycle: only fresh XP after enabling can confirm an ongoing Make-X run. */
public class PluginProductionResumeTest {
 LongSupplier clock = Ae.clock;
 static final long T0 = 1_700_000_000_000L;
 static final int LOG = 1515, BOW = 66;

 @After public void restoreClock() { Ae.clock = clock; }

 @Test public void firstFletchingActionAfterQuickToggleCountsWithoutAnotherClick() {
  Harness h = new Harness();
  h.enable(T0);
  h.p.activity.le("Fletching", T0);
  Ac before = h.make(Skill.FLETCHING, T0 + 600L);
  h.disable(T0 + 2_000L);
  h.inventory = new Cc(Map.of(LOG, 5L, BOW, 10L)); // Churn while disabled is baseline material.
  h.enable(T0 + 3_000L);
  Ac after = h.make(Skill.FLETCHING, T0 + 3_600L);
  assertEquals(Ai.PROCESSING, after.getType());
  assertTrue(after.isCounted());
  assertTrue("already booked history is preserved", h.p.engine.activeSession.getTransactions().contains(before));
  assertEquals(2, h.p.engine.activeSession.getTransactions().size());
  assertEquals("only two enabled actions count", 120L, h.p.engine.getMetrics(T0 + 6_000L).net);
 }

 @Test public void productionCanResumeAfterTheOldRunExpiredOrWithAFreshPlugin() {
  for (Skill skill : new Skill[]{Skill.FLETCHING, Skill.CRAFTING, Skill.SMITHING,
    Skill.HERBLORE, Skill.COOKING, Skill.CONSTRUCTION}) {
   Harness h = new Harness();
   h.p.activity.le(skill.getName(), T0 - 60_000L);
   h.enable(T0);
   Ac row = h.make(skill, T0 + 600L);
   assertEquals(skill.getName(), Ai.PROCESSING, row.getType());
   assertTrue(row.isCounted());
   assertEquals(60L, h.p.engine.getMetrics(T0 + 3_000L).net);
  }
 }

 @Test public void readinessStartsTheResumeWindowAfterASlowBind() {
  Harness h = new Harness();
  h.p.ready = false;
  h.enable(T0);
  h.xp.put(Skill.FLETCHING, 2_000); // Disabled/fenced XP is never the action being booked.
  h.at(T0 + 60_000L);
  h.p.ready = true;
  h.p.ve();
  assertEquals(Ai.PROCESSING, h.make(Skill.FLETCHING, T0 + 60_600L).getType());
  assertEquals(60L, h.p.engine.getMetrics(T0 + 63_000L).net);
 }

 @Test public void enablingOrUnchangedXpCannotConfirmAMixedChange() {
  Harness h = new Harness();
  h.enable(T0);
  h.p.onStatChanged(new StatChanged(Skill.FLETCHING, 1_000, 1, 1));
  h.p.onStatChanged(new StatChanged(Skill.FLETCHING, 999, 1, 1));
  Ac row = h.change(T0 + 600L);
  assertEquals(Ai.UNCERTAIN, row.getType());
  assertFalse(row.isCounted());
  assertEquals(0L, h.p.engine.getMetrics(T0 + 3_000L).net);
 }

 @Test public void combatAndGatherXpCannotConfirmProduction() {
  for (Skill skill : new Skill[]{Skill.ATTACK, Skill.MAGIC, Skill.PRAYER, Skill.MINING, Skill.WOODCUTTING}) {
   Harness h = new Harness();
   h.enable(T0);
   Ac row = h.make(skill, T0 + 600L);
   assertEquals(skill.getName(), Ai.UNCERTAIN, row.getType());
   assertFalse(row.isCounted());
  }
 }

 @Test public void anIdleEnableDoesNotKeepProductionArmedForever() {
  Harness h = new Harness();
  h.enable(T0);
  assertEquals(Ai.UNCERTAIN, h.make(Skill.FLETCHING, T0 + 31_000L).getType());
 }

 @Test public void anOrdinaryProfileResumeCannotRenewTheEnableWindow() {
  Harness h = new Harness();
  h.enable(T0);
  h.at(T0 + 60_000L);
  h.p.ve();
  assertEquals(Ai.UNCERTAIN, h.make(Skill.FLETCHING, T0 + 60_600L).getType());
 }

 @Test public void aManualPauseStaysHeldThroughToggleAndProductionXp() {
  Harness h = new Harness();
  h.enable(T0);
  h.p.engine.activeSession.pause(T0 + 600L, Ed.MANUAL);
  h.disable(T0 + 1_200L);
  h.enable(T0 + 1_800L);
  h.at(T0 + 2_400L);
  h.xp.put(Skill.FLETCHING, 1_100);
  h.p.onStatChanged(new StatChanged(Skill.FLETCHING, 1_100, 1, 1));
  assertEquals(Ed.MANUAL, h.p.engine.activeSession.getPauseReason());
  assertNull(h.p.engine.adj(new Cc(Map.of(BOW, 1L)), T0 + 3_000L));
  assertTrue(h.p.engine.activeSession.getTransactions().isEmpty());
 }

 static class Plugin extends GpManagerPlugin {
  boolean ready = true;
  @Override boolean nh() { return ready; }
  @Override void installPresentation() { }
  @Override void afe() { }
  @Override void pulse() { }
 }

 class Harness {
  Plugin p = new Plugin();
  Map<Skill, Integer> xp = new EnumMap<>(Skill.class);
  Cc inventory = new Cc(Map.of(LOG, 1L));
  List<Runnable> callbacks = new ArrayList<>();

  Harness() {
   JsonCodec.bind(new Gson());
   for (Skill s : Skill.values()) xp.put(s, 1_000);
   p.gson = new Gson();
   p.config = new GpManagerConfig() {
    @Override public int stabilizationTicks() { return 0; }
   };
   p.engine = new Am(d -> {
    List<Ab> flows = new ArrayList<>();
    d.forEach((id, q) -> {
     int price = id == LOG ? 100 : 160;
     flows.add(new Ab(id, id == LOG ? "Yew logs" : "Yew longbow (u)", q, price, q * price));
    });
    return flows;
   }, new TransactionClassifier(), p.config);
   p.engine.rm(T0);
   ItemContainer container = ReviewFixesTest.proxy(ItemContainer.class, Map.of("getItems", new Item[0]));
   p.client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class}, (o, m, a) -> {
    if (m.getName().equals("getSkillExperience")) {
     assertNotEquals("Overall has no individual skill XP slot", Skill.OVERALL, a[0]);
     return xp.get(a[0]);
    }
    if (m.getName().equals("getGameState")) return GameState.LOGGED_IN;
    if (m.getName().equals("getItemContainer")) return container;
    if (m.getReturnType() == boolean.class) return false;
    if (m.getReturnType() == int.class) return 0;
    if (m.getReturnType() == long.class) return 123L;
    return null;
   });
   p.clientThread = new ClientThread() {
    @Override public void invokeLater(Runnable r) { callbacks.add(r); }
   };
   p.snapshotFactory = new ContainerSnapshotFactory(p.client, null) {
    @Override Cc capture(boolean equipment, boolean pouch) { return inventory; }
   };
   // Disk/UI are excluded; the real plugin enable, disable, baseline and XP handlers run.
   p.persistence = new Ei(p.client, null, null, null, p.engine) {
    @Override void vr(Runnable r) { }
    @Override void shutdown(boolean persist) { eo(); }
   };
   p.geOfferLedger = new Bj();
   p.activity = new ActivityDetector(p.config, p.engine);
   p.interactionContextTracker = GpManagerPluginProbe.tracker(p.client, p.engine, p.config);
   p.characterIdleTracker = new CharacterIdleTracker(p.client, new CharacterIdleModel());
   p.charges = new ChargeIntake(p.client, null, p.config, p.engine, null) {
    @Override void aks(Skill skill, int tick, long now) { }
   };
  }

  void at(long now) { Ae.clock = () -> now; }
  void enable(long now) {
   at(now);
   p.startUp();
   for (Runnable r : new ArrayList<>(callbacks)) r.run();
   callbacks.clear();
  }
  void disable(long now) { at(now); p.shutDown(); }
  Ac make(Skill skill, long now) {
   at(now);
   xp.put(skill, xp.get(skill) + 100);
   p.onStatChanged(new StatChanged(skill, xp.get(skill), 1, 1));
   return change(now);
  }
  Ac change(long now) {
   Map<Integer, Long> next = new java.util.HashMap<>(inventory.quantities);
   next.merge(LOG, -1L, Long::sum);
   next.merge(BOW, 1L, Long::sum);
   inventory = new Cc(next);
   p.engine.yz();
   assertNull(p.engine.adj(inventory, now));
   Ac row = p.engine.adj(inventory, now + 600L);
   assertNotNull(row);
   return row;
  }
 }
}
