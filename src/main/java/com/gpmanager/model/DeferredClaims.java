package com.gpmanager;
import net.runelite.api.gameval.ItemID;
/** Untradeable unopened claims held at zero: their contents become income only on receipt. */
class DeferredClaims {
/** A key/chest claim or a coin pouch. */
static boolean of(int itemId) {
return KeyChestCatalogue.wg(itemId) || pouch(itemId);
}
/** Coin pouches: each holds coins that only book when opened. */
static boolean pouch(int itemId) {
return itemId >= ItemID.PICKPOCKET_COIN_POUCH_CITIZEN
&& itemId <= ItemID.PICKPOCKET_COIN_POUCH_ELF
|| itemId == ItemID.PICKPOCKET_COIN_POUCH_VYRE
|| itemId == ItemID.PICKPOCKET_COIN_POUCH_VARLAMORE_WEALTHY
|| itemId == ItemID.PICKPOCKET_COIN_POUCH_PIRATE;
}
}
