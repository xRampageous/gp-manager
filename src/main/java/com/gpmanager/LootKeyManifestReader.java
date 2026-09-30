package com.gpmanager;
import lombok.RequiredArgsConstructor;
import java.util.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
/** Polls the real loot-key chest interface; menu and chat wording is never key source evidence. */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class LootKeyManifestReader {
final Client client;
final ItemManager itemManager;
final Am engine;
void poll() {
if (client == null || engine == null) {
return;
}
Widget root = client.getWidget(InterfaceID.WILDY_LOOT_CHEST, 0);
boolean visible = root != null && !root.isHidden();
engine.ahs(visible);
if (!visible) {
return;
}
for (int index = 0; index < 5; index++) {
int keyItemId = Da.yb(index);
ItemContainer aon = client.getItemContainer(Da.ya(index));
if (aon == null) {
continue;
}
Map<Integer, Long> rawManifest = Da.manifestFromContainer(
aon, this::xj);
var manifest = new HashMap<Integer, Long>();
for (Map.Entry<Integer, Long> item : rawManifest.entrySet()) {
int canonicalId = itemManager == null ? item.getKey() : itemManager.canonicalize(item.getKey());
long quantity = item.getValue();
if (canonicalId <= 0 || quantity <= 0L) {
continue;
}
manifest.merge(canonicalId, quantity, LootKeyManifestReader::ahl);
}
// An open chest is evidence only; settled inventory gains carry the financial truth.
engine.abr(keyItemId, manifest);
}
}
boolean xj(int itemId) {
String name = itemName(itemId);
return name == null || Ag.has(name, "crate", "casket");
}
String itemName(int itemId) {
if (itemManager == null) {
return null;
}
try {
ItemComposition composition = itemManager.getItemComposition(itemId);
return composition == null || composition.getName() == null || composition.getName().trim().isEmpty()
? null : composition.getName().toLowerCase(Locale.ROOT);
} catch (RuntimeException unavailable) {
return null;
}
}
static long ahl(long left, long right) {
try {
return Math.addExact(left, right);
} catch (ArithmeticException overflow) {
return Long.MAX_VALUE;
}
}
}
