package com.gpmanager;
import lombok.RequiredArgsConstructor;
import java.util.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.gameval.InterfaceID;
/** Polls the real loot-key chest interface; menu and chat wording is never key source evidence. */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class LootKeyManifestReader {
final Client client;
final Ix ix;
final Am engine;
void poll() {
 if (client == null || engine == null) return;
 boolean /* visible */b = Ee.visible(client, InterfaceID.WildyLootChest.UNIVERSE);
 engine.ahs(b);
 if (!b) return;
 for (int /* index */c = 0; c < 5; c++) {
  int /* keyItemId */d = Da.yb(c);
  ItemContainer /* aon */e = client.getItemContainer(Da.ya(c));
  if (e == null) continue;
  Map<Integer, Long> /* rawManifest */f = Da.manifestFromContainer(e, this::xj);
  var /* manifest */g = new HashMap<Integer, Long>();
  for (Map.Entry<Integer, Long> /* item */h : f.entrySet()) {
   int /* canonicalId */i = ix == null ? h.getKey() : ix.canon(h.getKey());
   long /* quantity */j = h.getValue();
   if (i <= 0 || j <= 0L) continue;
   g.merge(i, j, LootKeyManifestReader::ahl);
  }
  // An open chest is evidence only; settled inventory gains carry the financial truth.
  engine.abr(d, g);
 }
}

boolean xj(int /* itemId */a) {
 String /* name */b = itemName(a);
 return b == null || Ag.has(b, "crate", "casket");
}

String itemName(int /* itemId */a) {
 if (ix == null) return null;
 try {
  ItemComposition /* composition */b = ix.comp(a);
  return b == null || b.getName() == null || b.getName().trim().isEmpty()
  ? null : b.getName().toLowerCase(Locale.ROOT);
 } catch (RuntimeException /* unavailable */c) {
  return null;
 }
}

static long ahl(long /* left */a, long /* right */b) {
 try {
  return Math.addExact(a, b);
 } catch (ArithmeticException /* overflow */c) {
  return Long.MAX_VALUE;
 }
}
}
