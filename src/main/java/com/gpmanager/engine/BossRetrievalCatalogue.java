package com.gpmanager;

import static com.gpmanager.GameData.msg;

import static java.util.Locale.*;

import lombok.*;

import java.util.*;

/**
 * Item Retrieval Services and their published flat fees. Source: OSRS Wiki "Item Retrieval Service"
 * (checked 2026-10-11). The fee here is an <em>expectation used for labelling</em>; the booked cost
 * is always the coins actually observed leaving the inventory in the reclaim window. Fees paid from
 * the bank or Death's Coffer are not observable and are never invented.
 */
class BossRetrievalCatalogue {
    /** Marker for services whose fee depends on the run (ToA scales with raid level). */
    static final long VARIABLE_FEE = -1L;

    /** Marker for tiered / percentage fees (gravestone, Death's Office) — see DeathReclaimFees. */
    static final long TIERED_FEE = -2L;

    @AllArgsConstructor
    static class Service {
        String key;
        String activity;
        String interactable;
        long expectedFee;
        String note;

        /**
         * Target name shared with unrelated scenery (a bare "Chest", a gravestone): only evidence
         * when a local PvM death is still awaiting reclaim.
         */
        boolean ambiguousTarget;

        /** Ledger explanation for an observed coin payment at this service. */
        String why(long observedCoins) {
            var why =
                    new StringBuilder(msg("item-retrieval"))
                            .append(activity)
                            .append(" (")
                            .append(interactable)
                            .append(")");
            if (expectedFee > 0L) {
                why.append(", published ").append(String.format(ROOT, "%,d", expectedFee));
                if (observedCoins > 0L && observedCoins != expectedFee) {
                    why.append(", observed ").append(String.format(ROOT, "%,d", observedCoins));
                }
            } else if (expectedFee == VARIABLE_FEE) {
                why.append(", fee varies");
            }
            if (!note.isEmpty()) why.append(". ").append(note);
            return why.toString();
        }
    }

    static final Map<String, Service> BY_INTERACTABLE = new LinkedHashMap<>();

    static {
        for (String[] row : GameData.rows("retrieval_services")) {
            BY_INTERACTABLE.put(
                    row[2].toLowerCase(ROOT),
                    new Service(
                            row[0],
                            row[1],
                            row[2],
                            Long.parseLong(row[3]),
                            row[4],
                            Boolean.parseBoolean(row[5])));
        }
    }

    /**
     * Service for a menu target (NPC or object name) when the option is a retrieval verb (see
     * {@link #isRetrievalOption}). Ambiguous targets ("Chest", "Gravestone", "Death") are returned
     * too; the engine only acts on them while a local PvM death awaits reclaim.
     */

    /** Menu verbs that open or complete a reclaim. */
    /** Existing reclaim vocabulary and grave prefix compiled once from d18. */
    static final java.util.regex.Pattern RECLAIM = GameData.pattern("reclaim");

    static final java.util.regex.Pattern GRAVE = GameData.pattern("grave");

    static Service serviceFor(String option, String target) {
        if (target == null || option == null || !RECLAIM.matcher(Phrases.lower(option)).matches()) {
            return null;
        }
        String lower = Phrases.lower(target);
        if (lower.isEmpty()) return null;
        Service exact = BY_INTERACTABLE.get(lower);
        if (exact != null) return exact;
        for (Map.Entry<String, Service> entry : BY_INTERACTABLE.entrySet()) {
            if (!entry.getValue().ambiguousTarget && lower.contains(entry.getKey()))
                return entry.getValue();
        }
        if (GRAVE.matcher(lower).matches()) return BY_INTERACTABLE.get("gravestone");
        return null;
    }
}
