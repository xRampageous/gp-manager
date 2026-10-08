package com.gpmanager;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
/**
* Interface-visibility reads on the client thread. The client retains bank contents after
* the interface closes, so only widget visibility is evidence of an open UI; cached
* ownership data must never suppress later gathering or supply consumption.
*/
class Ee {
/** Bank main or deposit-box UI visible — drives Banking header and Withdrew buffer. */
static boolean vp(Client /* client */a) {
 return visible(a, InterfaceID.Bankmain.ITEMS_CONTAINER) || visible(a, InterfaceID.BankDepositbox.INVENTORY)
 || visible(a, InterfaceID.BankDepositbox.CONTENTS);
}

/** Tool Leprechaun store (either panel) visible; its withdrawals and deposits are storage. */
static boolean yf(Client /* client */a) {
 return visible(a, InterfaceID.FarmingTools.FRAME) || visible(a, InterfaceID.FarmingToolsSide.UNIVERSE);
}

/** Stores and exchanges whose own open window proves a move neutral (d29): its note, else null. */
static final java.util.List<String[]> STORES = Ak.rows("d29");
static String store(Client /* client */a) {
 for (String[] /* row */b : STORES) if (visible(a, Integer.parseInt(b[0]))) return b[2];
 return null;
}

static boolean wq(Client /* client */a) {
 if (a == null) return false;
 Widget /* root */b = a.getWidget(InterfaceID.GeOffers.UNIVERSE);
 // A banker's or clerk's collection box collects offers like the exchange itself (1.2).
 return (b != null && !b.isHidden()) || visible(a, InterfaceID.GeOffersSide.ITEMS)
 || visible(a, InterfaceID.GeCollect.UNIVERSE);
}

static boolean visible(Client /* client */a, int /* componentId */b) {
 try {
  Widget /* widget */c = a == null ? null : a.getWidget(b);
  return c != null && !c.isHidden();
 } catch (RuntimeException /* ignored */d) {
  return false;
 }
}
}
