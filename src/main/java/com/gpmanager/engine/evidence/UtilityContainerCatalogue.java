package com.gpmanager;

import static com.gpmanager.GameData.msg;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Special bags/sacks/barrels/kits/pouches. Store/retrieve is ownership-neutral ({@link Kind});
 * contents valuation follows snapshots — never invent gain/loss.
 */
class UtilityContainerCatalogue {
    enum Kind {
        STORE,
        WITHDRAW,
        EMPTY_TO_BANK,
        EMPTY_TO_INVENTORY,
        CALIBRATE_CHECK
    }

    /** Special container names, most specific first: the first one a name contains wins. */
    static final List<String> NAMES = GameData.names("utility_containers");

    static final Pattern ESSENCE_POUCH = GameData.pattern("essence-pouch");

    /**
     * The container family used in the custody note ({@code herb_sack}), or null for other items.
     */
    static String familyFor(String itemName) {
        String lower = Phrases.norm(itemName);
        if (lower != null && ESSENCE_POUCH.matcher(lower).find()) return "essence_pouch";
        for (String name : NAMES) {
            if (lower != null && lower.contains(name))
                return name.replace("'", "").replace(' ', '_').replace('-', '_');
        }
        return null;
    }

    /**
     * Classify a menu option on a known container. Deposit-box Empty → bank is {@link
     * Kind#EMPTY_TO_BANK} (ownership-neutral).
     */
    static Kind classify(String option, boolean depositBoxOpen) {
        String normalized = Phrases.lower(option);
        return "check".equals(normalized)
                ? Kind.CALIBRATE_CHECK
                : normalized.startsWith("empty")
                        ? depositBoxOpen ? Kind.EMPTY_TO_BANK : Kind.EMPTY_TO_INVENTORY
                        : normalized.startsWith("withdraw") || normalized.startsWith("remove")
                                ? Kind.WITHDRAW
                                : Kind.STORE;
    }

    /** True when evidence must stay ownership-neutral (never invent Net). */
    static boolean isNeutral(Kind kind) {
        return kind != null && kind != Kind.CALIBRATE_CHECK;
    }

    /**
     * True when the menu option is a container custody action this catalogue can classify.
     * Store/withdraw/empty/fill are reversible custody movement, never revenue or cost.
     */
    static boolean isCustodyOption(String option) {
        String normalized = Phrases.norm(option);
        return normalized != null
                && (normalized.matches(msg("fill-check"))
                        || normalized.startsWith("empty")
                        || normalized.startsWith("withdraw")
                        || normalized.startsWith("remove"));
    }
}
