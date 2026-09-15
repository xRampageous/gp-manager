package com.gpmanager.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Presentation rollup: specific activity / NPC / skill nets → Insights categories
 * with per-source drill-down. Does not mutate accounting.
 */
public final class InsightsActivityBreakdown
{
    private final List<CategoryRow> categories;

    private InsightsActivityBreakdown(List<CategoryRow> categories)
    {
        this.categories = Collections.unmodifiableList(categories);
    }

    public List<CategoryRow> getCategories()
    {
        return categories;
    }

    public CategoryRow find(String displayName)
    {
        if (displayName == null || displayName.trim().isEmpty())
        {
            return null;
        }
        // Preserve callers and historical terminology while presenting consistent labels.
        if ("Raids".equalsIgnoreCase(displayName)) displayName = "Raid";
        if ("PKing".equalsIgnoreCase(displayName)) displayName = "PvP";
        if ("Other".equalsIgnoreCase(displayName)) displayName = "Misc";
        for (CategoryRow row : categories)
        {
            if (displayName.equalsIgnoreCase(row.getName()))
            {
                return row;
            }
        }
        return null;
    }

    public static InsightsActivityBreakdown from(Map<String, Long> activityNet)
    {
        EnumMap<InsightsActivityCategory, MutableCategory> grouped =
            new EnumMap<>(InsightsActivityCategory.class);
        if (activityNet != null)
        {
            for (Map.Entry<String, Long> entry : activityNet.entrySet())
            {
                if (entry == null || entry.getValue() == null || entry.getValue() == 0L)
                {
                    continue;
                }
                String source = normalize(entry.getKey());
                InsightsActivityCategory category = categorize(source);
                MutableCategory bucket = grouped.computeIfAbsent(category, MutableCategory::new);
                bucket.net = saturatingAdd(bucket.net, entry.getValue());
                bucket.sources.merge(source, entry.getValue(), InsightsActivityBreakdown::saturatingAdd);
            }
        }

        List<CategoryRow> rows = new ArrayList<>();
        for (InsightsActivityCategory category : InsightsActivityCategory.values())
        {
            MutableCategory bucket = grouped.get(category);
            if (bucket == null || bucket.net == 0L)
            {
                continue;
            }
            List<Map.Entry<String, Long>> sources = new ArrayList<>(bucket.sources.entrySet());
            sources.sort(Comparator
                .<Map.Entry<String, Long>>comparingLong(e -> Math.abs(e.getValue()))
                .reversed()
                .thenComparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER));
            Map<String, Long> ordered = new LinkedHashMap<>();
            for (Map.Entry<String, Long> source : sources)
            {
                ordered.put(source.getKey(), source.getValue());
            }
            rows.add(new CategoryRow(category.getDisplayName(), bucket.net, ordered));
        }
        rows.sort(Comparator
            .comparingLong(CategoryRow::getNet)
            .reversed()
            .thenComparing(CategoryRow::getName, String.CASE_INSENSITIVE_ORDER));
        return new InsightsActivityBreakdown(rows);
    }

    /** True when the label is a broad Insights bucket, not a current-activity title. */
    public static boolean isGeneralizedCategory(String activity)
    {
        if (activity == null)
        {
            return false;
        }
        String trimmed = activity.trim();
        if (trimmed.isEmpty())
        {
            return false;
        }
        for (InsightsActivityCategory category : InsightsActivityCategory.values())
        {
            if (category != InsightsActivityCategory.OTHER
                && category.getDisplayName().equalsIgnoreCase(trimmed))
            {
                return true;
            }
        }
        return "PK".equalsIgnoreCase(trimmed) || "PKing".equalsIgnoreCase(trimmed)
            || "Raids".equalsIgnoreCase(trimmed) || "Pvm".equalsIgnoreCase(trimmed);
    }

    /**
     * Specific NPC / encounter names (Chicken, Dark wizard) that must not soft-stick
     * the HUD+ header via detected-activity fallback after Idle / bank / death.
     * Live interaction still titles them. Combat skills and named content (Barrows,
     * Gauntlet) are left alone.
     */
    public static boolean isSpecificNpcActivityTitle(String activity)
    {
        String name = normalize(activity);
        if (name.isEmpty() || isGeneralizedCategory(name) || isCombatSkill(name))
        {
            return false;
        }
        if (containsAny(name, "barrows", "gauntlet"))
        {
            return false;
        }
        return categorize(name) == InsightsActivityCategory.PVM;
    }

    public static InsightsActivityCategory categorize(String activity)
    {
        String name = normalize(activity);
        if (KeyChestCatalogue.entryForChestMention(name) != null)
        {
            return InsightsActivityCategory.OTHER;
        }
        if (name.isEmpty()
            || equalsIgnore(name, "General", "Tracking", "Waiting", "Idle",
                "Transfer", "Drop recovery", "Other", "Misc")
            || containsAny(name, "larren", "brimstone chest", "crystal chest", "clue scroll", "reward casket"))
        {
            return InsightsActivityCategory.OTHER;
        }
        // Explicit AE/AC source → bucket map (tax / loot-key / Foundry / split).
        if (containsAny(name, "ge tax", "ge sell tax", "grand exchange tax", "tax 2%"))
        {
            return InsightsActivityCategory.TRADING;
        }
        if (containsAny(name, "loot key", "loot chest", "wilderness loot"))
        {
            return InsightsActivityCategory.PK;
        }
        if (containsAny(name, "giants' foundry", "giants foundry", "foundry")
            || equalsIgnore(name, "Birdhouse", "Herbiboar"))
        {
            return InsightsActivityCategory.SKILLING;
        }
        if (containsAny(name, "split share", "split keep", "item split"))
        {
            // Splits keep the original activity name when present; fall through otherwise.
            if (isRaid(name))
            {
                return InsightsActivityCategory.RAIDS;
            }
            if (isSkillingSkill(name) || containsAny(name, "foundry", "birdhouse", "herbiboar"))
            {
                return InsightsActivityCategory.SKILLING;
            }
        }
        if (containsAny(name, "barrows", "gauntlet", "tempoross", "wintertodt", "guardians of the rift", "gotr"))
        {
            if (containsAny(name, "tempoross", "wintertodt", "guardians of the rift", "gotr"))
            {
                return InsightsActivityCategory.SKILLING;
            }
            if (containsAny(name, "gauntlet"))
            {
                return InsightsActivityCategory.PVM;
            }
            return InsightsActivityCategory.PVM;
        }
        if (isRaid(name))
        {
            return InsightsActivityCategory.RAIDS;
        }
        if (equalsIgnore(name, "PKing", "Pking", "PK", "PvP")
            || containsAny(name, "wilderness", "bounty hunter", "last man standing"))
        {
            return InsightsActivityCategory.PK;
        }
        if (equalsIgnore(name, "Trading", "Market", "Charges")
            || containsAny(name, "grand exchange", "flipping", "charge spend", "charge component"))
        {
            return InsightsActivityCategory.TRADING;
        }
        if (equalsIgnore(name, "Skilling") || isSkillingSkill(name))
        {
            return InsightsActivityCategory.SKILLING;
        }
        if (isCombatSkill(name) || equalsIgnore(name, "PvM", "Pvm"))
        {
            return InsightsActivityCategory.PVM;
        }
        // Specific NPC / boss / encounter names roll into PvM unless classified above.
        return InsightsActivityCategory.PVM;
    }

    private static boolean isRaid(String name)
    {
        return equalsIgnore(name, "Raids", "Raid")
            || containsAny(name,
                "chambers of xeric", "theatre of blood", "tombs of amascut");
    }

    private static boolean isSkillingSkill(String name)
    {
        return equalsIgnore(name,
            "Mining", "Smithing", "Fishing", "Cooking", "Woodcutting", "Fletching",
            "Crafting", "Runecraft", "Runecrafting", "Herblore", "Agility", "Thieving",
            "Hunter", "Farming", "Construction", "Firemaking", "Slayer", "Sailing",
            "Prayer");
    }

    private static boolean isCombatSkill(String name)
    {
        return equalsIgnore(name,
            "Attack", "Strength", "Defence", "Defense", "Ranged", "Magic",
            "Hitpoints", "Combat");
    }

    private static boolean equalsIgnore(String value, String... options)
    {
        for (String option : options)
        {
            if (value.equalsIgnoreCase(option))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String value, String... needles)
    {
        String lower = value.toLowerCase(Locale.ROOT);
        for (String needle : needles)
        {
            if (lower.contains(needle))
            {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value)
    {
        return value == null || value.trim().isEmpty() ? "General" : value.trim();
    }

    private static long saturatingAdd(long left, long right)
    {
        try
        {
            return Math.addExact(left, right);
        }
        catch (ArithmeticException ex)
        {
            return right >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    public static final class CategoryRow
    {
        private final String name;
        private final long net;
        private final Map<String, Long> sources;

        private CategoryRow(String name, long net, Map<String, Long> sources)
        {
            this.name = name;
            this.net = net;
            this.sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
        }

        public String getName()
        {
            return name;
        }

        public long getNet()
        {
            return net;
        }

        public Map<String, Long> getSources()
        {
            return sources;
        }
    }

    private static final class MutableCategory
    {
        private long net;
        private final Map<String, Long> sources = new LinkedHashMap<>();

        private MutableCategory(InsightsActivityCategory ignored)
        {
        }
    }
}
