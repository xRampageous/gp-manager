package com.gpmanager.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Item Retrieval Services and their published flat fees. Source: OSRS Wiki
 * "Item Retrieval Service" (checked 2026-09-13). The fee here is an <em>expectation used
 * for labelling</em>; the booked cost is always the coins actually observed leaving the
 * inventory in the reclaim window. Fees paid from the bank or Death's Coffer are not
 * observable and are never invented.
 */
public final class BossRetrievalCatalogue
{
    /** Marker for services whose fee depends on the run (ToA scales with raid level). */
    public static final long VARIABLE_FEE = -1L;
    /** Marker for tiered / percentage fees (gravestone, Death's Office) — see DeathReclaimFees. */
    public static final long TIERED_FEE = -2L;

    public static final class Service
    {
        public final String key;
        public final String activity;
        public final String interactable;
        public final long expectedFee;
        public final String note;
        /**
         * Target name shared with unrelated scenery (a bare "Chest", a gravestone): only
         * evidence when a local PvM death is still awaiting reclaim.
         */
        public final boolean ambiguousTarget;

        Service(String key, String activity, String interactable, long expectedFee, String note)
        {
            this(key, activity, interactable, expectedFee, note, false);
        }

        Service(String key, String activity, String interactable, long expectedFee, String note,
            boolean ambiguousTarget)
        {
            this.key = key;
            this.activity = activity;
            this.interactable = interactable;
            this.expectedFee = expectedFee;
            this.note = note;
            this.ambiguousTarget = ambiguousTarget;
        }

        /** Ledger explanation for an observed coin payment at this service. */
        public String why(long observedCoins)
        {
            StringBuilder why = new StringBuilder("Item retrieval fee — ").append(activity)
                .append(" (").append(interactable).append(")");
            if (expectedFee > 0L)
            {
                why.append(", published ").append(String.format(Locale.ROOT, "%,d", expectedFee));
                if (observedCoins > 0L && observedCoins != expectedFee)
                {
                    why.append(", observed ").append(String.format(Locale.ROOT, "%,d", observedCoins));
                }
            }
            else if (expectedFee == VARIABLE_FEE)
            {
                why.append(", fee varies");
            }
            if (!note.isEmpty())
            {
                why.append(". ").append(note);
            }
            return why.toString();
        }
    }

    private static final Map<String, Service> BY_INTERACTABLE;

    static
    {
        Map<String, Service> map = new LinkedHashMap<>();
        add(map, new Service("zulrah", "Zulrah", "Priestess Zul-Gwenwynig", 100_000L,
            "Free below 50 kills and for Ultimate Ironmen"));
        add(map, new Service("vorkath", "Vorkath", "Torfinn", 100_000L, ""));
        add(map, new Service("hydra", "Alchemical Hydra", "Orrvor quo Maten", 100_000L, ""));
        add(map, new Service("grotesque_guardians", "Grotesque Guardians", "Magical chest", 50_000L, ""));
        add(map, new Service("sepulchre", "Hallowed Sepulchre", "Mysterious Stranger", 25_000L, ""));
        add(map, new Service("hespori", "Hespori", "Arno", 25_000L, ""));
        add(map, new Service("nightmare", "The Nightmare", "Shura", 60_000L, ""));
        add(map, new Service("phosani", "Phosani's Nightmare", "Sister Senga", 60_000L, ""));
        // Nex (100,000), Theatre of Blood (100,000) and Tombs of Amascut (up to 500,000)
        // all use a bare "Chest": indistinguishable by name, so one variable-fee entry.
        add(map, new Service("retrieval_chest", "Raid / boss retrieval chest", "Chest", VARIABLE_FEE,
            "Nex and Theatre of Blood 100,000; Tombs of Amascut up to 500,000 by raid level", true));
        // Generic death services — fee is tiered / percentage, see DeathReclaimFees.
        add(map, new Service("gravestone", "Gravestone", "Gravestone", TIERED_FEE,
            "Tiered per item, 500,000 cap; paid from Death's Coffer or bank when carried coins are absent", true));
        add(map, new Service("deaths_office", "Death's Office", "Death", TIERED_FEE,
            "5% of items worth 100,000 or more (2.5% ironman)", true));
        BY_INTERACTABLE = Collections.unmodifiableMap(map);
    }

    private static void add(Map<String, Service> map, Service service)
    {
        map.put(service.interactable.toLowerCase(Locale.ROOT), service);
    }

    private BossRetrievalCatalogue()
    {
    }

    /**
     * Service for a menu target (NPC or object name) when the option is a retrieval verb
     * (see {@link #isRetrievalOption}). Ambiguous targets ("Chest", "Gravestone", "Death")
     * are returned too; the engine only acts on them while a local PvM death awaits reclaim.
     */
    @Nullable
    public static Service forMenu(@Nullable String option, @Nullable String target)
    {
        if (target == null || !isRetrievalOption(option))
        {
            return null;
        }
        String lower = target.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty())
        {
            return null;
        }
        Service exact = BY_INTERACTABLE.get(lower);
        if (exact != null)
        {
            return exact;
        }
        for (Map.Entry<String, Service> entry : BY_INTERACTABLE.entrySet())
        {
            if (!entry.getValue().ambiguousTarget && lower.contains(entry.getKey()))
            {
                return entry.getValue();
            }
        }
        if (lower.startsWith("gravestone") || lower.startsWith("grave"))
        {
            return BY_INTERACTABLE.get("gravestone");
        }
        return null;
    }

    /** Menu verbs that open or complete a reclaim. */
    public static boolean isRetrievalOption(@Nullable String option)
    {
        if (option == null)
        {
            return false;
        }
        String lower = option.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("claim") || lower.startsWith("reclaim") || lower.startsWith("retrieve")
            || lower.startsWith("loot") || lower.startsWith("collect") || lower.startsWith("talk")
            || lower.startsWith("check") || lower.startsWith("search") || lower.startsWith("open");
    }

    public static Map<String, Service> services()
    {
        return BY_INTERACTABLE;
    }
}
