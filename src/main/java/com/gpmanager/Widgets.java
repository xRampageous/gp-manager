package com.gpmanager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

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

    /** Shop windows whose own open UI turns a coin movement into a trade (owner 1.3.4). */
    static final java.util.List<String[]> SHOPS = GameData.rows("shop_windows");

    static String store(Client client) {
        for (String[] row : STORES) if (visible(client, Integer.parseInt(row[0]))) return row[2];
        return null;
    }

    /** A shop window (classic or omni) visible: purchases and sales are traded, not Review. */
    static boolean isShopOpen(Client client) {
        for (String[] row : SHOPS) if (visible(client, Integer.parseInt(row[0]))) return true;
        return false;
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

    /**
     * The fee a Recover click's row shows. The live window clicks the fee box itself ("57,500"),
     * so its own number counts when the boat's name sits among its preceding siblings; otherwise
     * the boat's name label anchors the walk and the fee is the first number after it (or the one
     * number in the row's own subtree). 0 when the row cannot be resolved, so the amountless bank
     * line never invents a fee (owner live 2026-10-14).
     */
    static long rowFee(Widget clicked, String targetName) {
        String target = targetName == null ? "" : targetName.trim().toLowerCase(Locale.ROOT);
        if (clicked == null || target.isEmpty()) return 0L;
        long own = pureNumber(clicked.getText());
        if (own > 0L && namedBefore(clicked, target, 8)) return own;
        Widget scope = clicked;
        for (int level = 0; level < 4 && scope != null; level++, scope = scope.getParent()) {
            Widget label = findLabel(scope, target, 4);
            if (label == null) continue;
            long fee = feeAfter(label);
            if (fee > 0L) return fee;
            Widget row = label;
            for (int inner = 0; inner < 4 && row != null; inner++, row = row.getParent()) {
                fee = rowFeeFrom(row);
                if (fee > 0L) return fee;
            }
        }
        return 0L;
    }

    /** The pure-number value of a text ("4,500", "250 gp"), else 0. */
    private static long pureNumber(String raw) {
        String text = cleanText(raw).trim();
        if (text.isEmpty() || !text.matches("[\\d,]{1,12}(?: gp| coins)?")) return 0L;
        long value = ChargeCheck.parseCount(text.replaceFirst("(?i) (?:gp|coins)$", ""));
        return value > 0L ? value : 0L;
    }

    /**
     * True when the boat's name sits between this fee and the previous row's number: in the flat
     * live layout the row ends at its fee box, so the walk stops there (owner live 2026-10-14).
     */
    private static boolean namedBefore(Widget widget, String target, int back) {
        Widget parent = widget.getParent();
        if (parent == null) return false;
        Widget[] siblings = children(parent);
        int start = indexOf(siblings, widget);
        if (start < 0) return false;
        for (int index = start - 1; index >= 0 && index >= start - back; index--) {
            String text = cleanText(siblings[index].getText()).toLowerCase(Locale.ROOT);
            if (text.isEmpty()) continue;
            if (pureNumber(text) > 0L) return false;
            if (text.contains(target)) return true;
        }
        return false;
    }

    /** The first pure-number text among the label's following siblings (the row's fee box). */
    private static long feeAfter(Widget label) {
        Widget parent = label.getParent();
        if (parent == null) return 0L;
        Widget[] siblings = children(parent);
        int start = indexOf(siblings, label);
        if (start < 0) return 0L;
        for (int index = start + 1; index < siblings.length && index <= start + 6; index++) {
            long value = pureNumber(siblings[index].getText());
            if (value > 0L) return value;
        }
        return 0L;
    }

    private static int indexOf(Widget[] widgets, Widget widget) {
        for (int index = 0; index < widgets.length; index++) {
            if (widgets[index] == widget) return index;
        }
        return -1;
    }

    /** The first widget at or under this scope whose own text names the boat, else null. */
    private static Widget findLabel(Widget scope, String target, int depth) {
        if (scope == null || depth < 0) return null;
        if (cleanText(scope.getText()).toLowerCase(Locale.ROOT).contains(target)) return scope;
        for (Widget child : children(scope)) {
            Widget found = findLabel(child, target, depth - 1);
            if (found != null) return found;
        }
        return null;
    }

    /** The one pure-number text in a row's subtree, else 0 when none or several. */
    private static long rowFeeFrom(Widget row) {
        long fee = 0L;
        for (String part : textsIn(row, 4)) {
            long value = pureNumber(part);
            if (value <= 0L) continue;
            if (fee > 0L && fee != value) return 0L;
            fee = value;
        }
        return fee;
    }

    /** A widget subtree's texts in document order, bounded by depth and count. */
    private static List<String> textsIn(Widget root, int depth) {
        var texts = new ArrayList<String>();
        collectTexts(root, texts, depth);
        return texts;
    }

    private static void collectTexts(Widget widget, List<String> texts, int depth) {
        if (widget == null || depth < 0 || texts.size() >= 256) return;
        String text = cleanText(widget.getText());
        if (!text.isEmpty()) texts.add(text);
        for (Widget child : children(widget)) collectTexts(child, texts, depth - 1);
    }

    private static Widget[] children(Widget widget) {
        if (widget == null) return new Widget[0];
        Widget[] stat = widget.getChildren();
        Widget[] dynamic = widget.getDynamicChildren();
        if (stat == null || stat.length == 0) return compact(dynamic);
        if (dynamic == null || dynamic.length == 0) return compact(stat);
        Widget[] all = Arrays.copyOf(stat, stat.length + dynamic.length);
        System.arraycopy(dynamic, 0, all, stat.length, dynamic.length);
        return compact(all);
    }

    /** The array without null entries; the client's child arrays may carry them. */
    private static Widget[] compact(Widget[] widgets) {
        if (widgets == null || widgets.length == 0) return new Widget[0];
        int kept = 0;
        for (Widget widget : widgets) {
            if (widget != null) kept++;
        }
        if (kept == widgets.length) return widgets;
        Widget[] compacted = new Widget[kept];
        int index = 0;
        for (Widget widget : widgets) {
            if (widget != null) compacted[index++] = widget;
        }
        return compacted;
    }

    private static String cleanText(String text) {
        return text == null ? "" : Text.removeTags(text).trim();
    }

    /**
     * The one number an open chat dialog shows (a recovery confirm's fee inside its sentence),
     * else 0 when none or several appear.
     */
    static long dialogFee(Client client) {
        Widget dialog = client == null ? null : client.getWidget(InterfaceID.Chatmenu.UNIVERSE);
        if (dialog == null) return 0L;
        String bag = String.join("\n", textsIn(dialog, 3));
        long fee = 0L;
        var matcher = Pattern.compile("(\\d{1,3}(?:,\\d{3})+|\\d{1,7})").matcher(bag);
        while (matcher.find()) {
            long value = ChargeCheck.parseCount(matcher.group(1));
            if (value <= 0L) continue;
            if (fee > 0L && fee != value) return 0L;
            fee = value;
        }
        return fee;
    }

    /**
     * A bounded description of a clicked widget, its ancestors and their children, for a live
     * capture when a fee read misses (owner 2026-10-14).
     */
    static String describe(Widget clicked) {
        if (clicked == null) return "none";
        StringBuilder out = new StringBuilder();
        Widget scope = clicked;
        for (int level = 0; level < 4 && scope != null; level++, scope = scope.getParent()) {
            out.append(level == 0 ? "" : " < ")
                    .append(scope.getId())
                    .append(':')
                    .append(brief(scope.getText()));
            int shown = 0;
            for (Widget child : children(scope)) {
                if (shown++ >= 12) {
                    out.append(" | ...");
                    break;
                }
                out.append(" | ").append(child.getId()).append(':').append(brief(child.getText()));
            }
        }
        return out.toString();
    }

    private static String brief(String text) {
        String clean = cleanText(text);
        return clean.length() <= 48 ? clean : clean.substring(0, 48) + "\u2026";
    }
}
