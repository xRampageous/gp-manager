package com.gpmanager;
import net.runelite.api.gameval.ItemID;
/** Untradeable unopened claims held at zero: their contents become income only on receipt. */
class DeferredClaims {
/** A key/chest claim or a coin pouch. */
static boolean of(int /* itemId */a) {
 return KeyChestCatalogue.wg(a) || pouch(a);
}

/** Coin pouches: each holds coins that only book when opened. */
static boolean pouch(int /* itemId */a) {
 return a >= ItemID.PICKPOCKET_COIN_POUCH_CITIZEN && a <= ItemID.PICKPOCKET_COIN_POUCH_ELF
 || a == ItemID.PICKPOCKET_COIN_POUCH_VYRE || a == ItemID.PICKPOCKET_COIN_POUCH_VARLAMORE_WEALTHY
 || a == ItemID.PICKPOCKET_COIN_POUCH_PIRATE;
}
}
