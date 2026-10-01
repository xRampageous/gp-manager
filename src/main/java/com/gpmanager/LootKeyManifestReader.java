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
final Engine engine;
void poll() {
if (client == null || engine == null) return;
Widget root = client.getWidget(InterfaceID.WildyLootChest.UNIVERSE);
boolean visible = root != null && !root.isHidden();
engine.setLootKeyChestVisible(visible);
if (!visible) return;
for (int index = 0; index < 5; index++) {
int keyItemId = KeyClaims.keyItemIdForIndex(index);
ItemContainer keyContents = client.getItemContainer(KeyClaims.keyContainerIdForIndex(index));
if (keyContents == null) continue;
Map<Integer, Long> rawManifest = KeyClaims.manifestFromContainer(keyContents, this::isNestedContainerItem);
var manifest = new HashMap<Integer, Long>();
for (Map.Entry<Integer, Long> item : rawManifest.entrySet()) {
int canonicalId = itemManager == null ? item.getKey() : itemManager.canonicalize(item.getKey());
long quantity = item.getValue();
if (canonicalId <= 0 || quantity <= 0L) continue;
manifest.merge(canonicalId, quantity, LootKeyManifestReader::safeAddPositive);
}
// An open chest is evidence only; settled inventory gains carry the financial truth.
engine.observeLootKeyContainer(keyItemId, manifest);
}
}

boolean isNestedContainerItem(int itemId) {
String name = itemName(itemId);
return name == null || ModelText.has(name, "crate", "casket");
}

String itemName(int itemId) {
if (itemManager == null) return null;
try {
ItemComposition composition = itemManager.getItemComposition(itemId);
return composition == null || composition.getName() == null || composition.getName().trim().isEmpty()
? null : composition.getName().toLowerCase(Locale.ROOT);
} catch (RuntimeException unavailable) {
return null;
}
}

static long safeAddPositive(long left, long right) {
try {
return Math.addExact(left, right);
} catch (ArithmeticException overflow) {
return Long.MAX_VALUE;
}
}
}
