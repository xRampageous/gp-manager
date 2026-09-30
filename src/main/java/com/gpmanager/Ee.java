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
static boolean vp(Client client) {
return visible(client, InterfaceID.Bankmain.ITEMS_CONTAINER)
|| visible(client, InterfaceID.BankDepositbox.INVENTORY)
|| visible(client, InterfaceID.BankDepositbox.CONTENTS);
}
/** Tool Leprechaun store (either panel) visible; its withdrawals and deposits are storage. */
static boolean yf(Client client) {
return visible(client, InterfaceID.FarmingTools.FRAME)
|| visible(client, InterfaceID.FarmingToolsSide.UNIVERSE);
}
static boolean wq(Client client) {
if (client == null) {
return false;
}
Widget root = client.getWidget(InterfaceID.GE_OFFERS, 0);
return (root != null && !root.isHidden()) || visible(client, InterfaceID.GeOffersSide.ITEMS);
}
static boolean visible(Client client, int componentId) {
try {
Widget widget = client == null ? null : client.getWidget(componentId);
return widget != null && !widget.isHidden();
} catch (RuntimeException ignored) {
return false;
}
}
}
