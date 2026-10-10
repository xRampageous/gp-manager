package com.gpmanager;

import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/**
 * Interface-visibility reads on the client thread. The client retains bank contents after the
 * interface closes, so only widget visibility is evidence of an open UI; cached ownership data must
 * never suppress later gathering or supply consumption.
 */
class Widgets {
    /** Bank main or deposit-box UI visible — drives Banking header and Withdrew buffer. */
    static boolean isBankOpen(Client client) {
        return visible(client, InterfaceID.Bankmain.ITEMS_CONTAINER)
                || visible(client, InterfaceID.BankDepositbox.INVENTORY)
                || visible(client, InterfaceID.BankDepositbox.CONTENTS);
    }

    /** Tool Leprechaun store (either panel) visible; its withdrawals and deposits are storage. */
    static boolean isToolStoreOpen(Client client) {
        return visible(client, InterfaceID.FarmingTools.FRAME)
                || visible(client, InterfaceID.FarmingToolsSide.UNIVERSE);
    }

    /**
     * Stores and exchanges whose own open window proves a move neutral (d29): its note, else null.
     */
    static final java.util.List<String[]> STORES = GameData.rows("store_windows");

    static String store(Client client) {
        for (String[] row : STORES) if (visible(client, Integer.parseInt(row[0]))) return row[2];
        return null;
    }

    static boolean isGeOpen(Client client) {
        // A banker's or clerk's collection box collects offers like the exchange itself (1.2).
        return visible(client, InterfaceID.GeOffers.UNIVERSE)
                || visible(client, InterfaceID.GeOffersSide.ITEMS)
                || visible(client, InterfaceID.GeCollect.UNIVERSE);
    }

    /** A visible widget's text, "" when it is absent or hidden. */
    static String text(Client client, int componentId) {
        Widget widget = client.getWidget(componentId);
        return widget == null || widget.isHidden() ? "" : String.valueOf(widget.getText());
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
