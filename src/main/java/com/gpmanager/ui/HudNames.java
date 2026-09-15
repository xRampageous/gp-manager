package com.gpmanager.ui;

import java.awt.FontMetrics;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Presentation-only short names for HUD and InfoBox activity labels. */
public final class HudNames
{
    private static final Map<String, String> EXACT = exactNames();
    private static final Pattern FOSSIL_WYVERN = Pattern.compile(
        "(?i)^Fossil Island Wyvern \\((Ancient|Long-tailed|Spitting|Taloned) Wyvern\\)$");
    private static final Pattern SUFFIX = Pattern.compile(
        "(?i)(?:\\s×\\d+(?:\\s+rewards?)?|\\s·\\s\\d+\\s+(?:kills?|rewards?)"
            + "|\\s·\\sStreak|\\s\\(selected\\))$");
    private static final String[] STATUS_PREFIXES = {
        "PAUSED", "AFK", "REC", "HOLD", "STOP", "IDLE"
    };
    /** Trailing "(qualifier)" the game appends to shop and disambiguated NPC names. */
    private static final Pattern TRAILING_QUALIFIER = Pattern.compile("\\s*\\(([^()]*)\\)\\s*$");
    private static final String ELLIPSIS = "\u2026";

    private HudNames()
    {
    }

    /** Apply the canonical HUD abbreviation without changing transaction/activity data. */
    public static String compact(String name)
    {
        String value = safeTrim(name);
        if (value.isEmpty())
        {
            return value;
        }
        NameParts parts = splitSuffixes(value);
        return compactCore(parts.name) + parts.suffix;
    }

    /**
     * Apply name compaction within the actual painted pixel budget. Prefixes,
     * batch counts, selected markers and streak suffixes keep their own identity.
     */
    public static String compact(String title, int availableWidth, FontMetrics metrics)
    {
        if (metrics == null)
        {
            return compact(title);
        }
        String value = safeTrim(title);
        if (value.isEmpty())
        {
            return value;
        }

        StringBuilder prefix = new StringBuilder();
        String remaining = value;
        boolean foundPrefix;
        do
        {
            foundPrefix = false;
            if (startsWithIgnoreCase(remaining, "Session · "))
            {
                prefix.append(remaining, 0, "Session · ".length());
                remaining = remaining.substring("Session · ".length());
                foundPrefix = true;
            }
            else
            {
                for (String status : STATUS_PREFIXES)
                {
                    String token = status + " · ";
                    if (startsWithIgnoreCase(remaining, token))
                    {
                        prefix.append(remaining, 0, token.length());
                        remaining = remaining.substring(token.length());
                        foundPrefix = true;
                        break;
                    }
                }
            }
        }
        while (foundPrefix);

        NameParts parts = splitSuffixes(remaining);
        int fullBudget = availableWidth - metrics.stringWidth(prefix.toString() + parts.suffix);
        if (fullBudget <= 0)
        {
            return HudDropRowComponent.truncateToWidth(value, metrics, availableWidth);
        }

        Matcher fossil = FOSSIL_WYVERN.matcher(parts.name);
        String displayName;
        if (fossil.matches())
        {
            String full = parts.name;
            String variant = fossil.group(1) + " Wyvern";
            if (metrics.stringWidth(full) <= fullBudget)
            {
                displayName = full;
            }
            else if (metrics.stringWidth(variant) <= fullBudget)
            {
                displayName = variant;
            }
            else
            {
                displayName = "Wyvern";
            }
        }
        else
        {
            displayName = compactCore(parts.name);
            if (metrics.stringWidth(displayName) > fullBudget)
            {
                displayName = trimWords(displayName, metrics, fullBudget);
            }
        }

        String result = prefix + displayName + parts.suffix;
        return metrics.stringWidth(result) <= availableWidth
            ? result
            : HudDropRowComponent.truncateToWidth(result, metrics, availableWidth);
    }

    private static String compactCore(String name)
    {
        String value = safeTrim(name);
        if (value.isEmpty())
        {
            return value;
        }
        if (FOSSIL_WYVERN.matcher(value).matches())
        {
            return "Wyvern";
        }
        String exact = EXACT.get(normalize(value));
        if (exact != null)
        {
            return exact;
        }

        value = replaceIgnoreCase(value, "Grand Exchange", "GE");
        value = replaceIgnoreCase(value, "Theatre of Blood", "ToB");
        value = replaceIgnoreCase(value, "Chambers of Xeric", "CoX");
        value = replaceIgnoreCase(value, "Tombs of Amascut", "ToA");
        value = replaceIgnoreCase(value, "Guardians of the Rift", "GotR");
        value = replaceIgnoreCase(value, "Hallowed Sepulchre", "Sepulchre");
        value = replaceIgnoreCase(value, "Fortis Colosseum", "Colosseum");

        if (equalsIgnoreCase(value, "The Nightmare")) return "Nightmare";
        if (equalsIgnoreCase(value, "The Leviathan")) return "Leviathan";
        if (equalsIgnoreCase(value, "The Whisperer")) return "Whisperer";

        return compactQualifier(value);
    }

    /**
     * Generic rule for names the table cannot enumerate: a trailing parenthetical
     * qualifier ("Hofuthand (weapons and armor)", "Shade chest (Shades of Mort'ton)")
     * is dropped on the HUD. Raid mode qualifiers are the exception — the mode is
     * the thing being tracked — so they are shortened instead (Hard Mode → HM).
     */
    private static String compactQualifier(String value)
    {
        String current = value;
        while (true)
        {
            Matcher matcher = TRAILING_QUALIFIER.matcher(current);
            if (!matcher.find() || matcher.start() == 0)
            {
                return current;
            }
            String base = current.substring(0, matcher.start()).trim();
            String qualifier = matcher.group(1).trim();
            if (isRaidBase(base))
            {
                String mode = compactMode(qualifier);
                return mode.isEmpty() ? base : base + " (" + mode + ")";
            }
            current = base;
        }
    }

    private static boolean isRaidBase(String base)
    {
        return base.equalsIgnoreCase("ToB") || base.equalsIgnoreCase("CoX") || base.equalsIgnoreCase("ToA");
    }

    private static String compactMode(String qualifier)
    {
        String mode = qualifier;
        mode = replaceIgnoreCase(mode, "Hard Mode", "HM");
        mode = replaceIgnoreCase(mode, "Challenge Mode", "CM");
        mode = removeTrailingIgnoreCase(mode, " Mode");
        return mode.trim();
    }

    /**
     * Last resort under a pixel budget: drop whole trailing words before cutting
     * inside one ("Elite Void…" rather than "Elite Void Kni…"); the first word always survives.
     */
    private static String trimWords(String name, FontMetrics metrics, int budget)
    {
        String[] words = name.split(" ");
        for (int keep = words.length - 1; keep >= 1; keep--)
        {
            String candidate = String.join(" ", java.util.Arrays.copyOf(words, keep)) + ELLIPSIS;
            if (metrics.stringWidth(candidate) <= budget)
            {
                return candidate;
            }
        }
        return HudDropRowComponent.truncateToWidth(name, metrics, budget);
    }

    private static NameParts splitSuffixes(String value)
    {
        String name = value;
        String suffix = "";
        Matcher matcher = SUFFIX.matcher(name);
        while (matcher.find() && matcher.start() > 0)
        {
            suffix = matcher.group() + suffix;
            name = name.substring(0, matcher.start());
            matcher = SUFFIX.matcher(name);
        }
        return new NameParts(name, suffix);
    }

    private static Map<String, String> exactNames()
    {
        Map<String, String> names = new LinkedHashMap<>();
        add(names, "Grand Exchange Clerk", "GE Clerk");
        add(names, "Grand Exchange booth", "GE booth");
        add(names, "Bank deposit box", "Deposit box");
        add(names, "Priestess Zul-Gwenwynig", "Zul-Gwenwynig");
        add(names, "Orrvor quo Maten", "Orrvor");
        add(names, "Mysterious Stranger", "Stranger");
        add(names, "Alchemical Hydra", "Alch. Hydra");
        add(names, "Grotesque Guardians", "Grotesques");
        add(names, "Phosani's Nightmare", "Phosani");
        add(names, "Corrupted Gauntlet", "CG");
        add(names, "The Gauntlet", "Gauntlet");
        add(names, "Crystalline Hunllef", "Hunllef");
        add(names, "Corrupted Hunllef", "C. Hunllef");
        add(names, "King Black Dragon", "KBD");
        add(names, "Kalphite Queen", "KQ");
        add(names, "Corporeal Beast", "Corp");
        add(names, "Abyssal Sire", "Sire");
        add(names, "Thermonuclear smoke devil", "Thermy");
        add(names, "Commander Zilyana", "Zilyana");
        add(names, "General Graardor", "Graardor");
        add(names, "K'ril Tsutsaroth", "K'ril");
        add(names, "Kree'arra", "Kree'arra");
        add(names, "Dagannoth Prime", "Dag Prime");
        add(names, "Dagannoth Rex", "Dag Rex");
        add(names, "Dagannoth Supreme", "Dag Supreme");
        add(names, "Phantom Muspah", "Muspah");
        add(names, "Duke Sucellus", "Duke");
        add(names, "Crazy archaeologist", "Crazy arch");
        add(names, "Chaos Elemental", "Chaos Ele");
        add(names, "Brutal black dragon", "Brutal black");
        add(names, "Greater Nechryael", "G. Nechryael");
        add(names, "Mutated Bloodveld", "M. Bloodveld");
        add(names, "Lizardman shaman", "Shaman");
        add(names, "Aberrant spectre", "Ab. spectre");
        add(names, "Deviant spectre", "Dev. spectre");
        add(names, "Basilisk Knight", "Bas. Knight");
        add(names, "Amethyst crystals", "Amethyst");
        add(names, "Blisterwood tree", "Blisterwood");
        add(names, "Motherlode Mine", "Motherlode");
        add(names, "Volcanic Mine", "Volcanic");
        add(names, "Elven Crystal Chest", "Elven chest");
        add(names, "Larran's big chest", "Larran's chest");
        add(names, "Larran's small chest", "Larran's chest");
        add(names, "Raid / boss retrieval chest", "Retrieval chest");
        add(names, "Item Retrieval Service", "Retrieval");
        add(names, "Tool Leprechaun store", "Leprechaun");
        add(names, "GIM shared storage deposit", "Group storage");
        add(names, "GIM shared storage withdraw", "Group storage");
        add(names, "Coffer / minigame storage", "Coffer");
        add(names, "Sorceress's Garden", "Sorc. Garden");
        add(names, "Pyramid Plunder", "Plunder");
        add(names, "Rogues' Den", "Rogues' Den");
        add(names, "Hallowed Sepulchre", "Sepulchre");
        add(names, "Fortis Colosseum", "Colosseum");
        add(names, "Guardians of the Rift", "GotR");
        add(names, "Theatre of Blood (Entry)", "ToB (Entry)");
        add(names, "Theatre of Blood (Entry Mode)", "ToB (Entry)");
        add(names, "Theatre of Blood (Hard Mode)", "ToB (HM)");
        add(names, "Chambers of Xeric (Challenge Mode)", "CoX (CM)");
        add(names, "Tombs of Amascut (Expert)", "ToA (Expert)");
        return Collections.unmodifiableMap(names);
    }

    private static void add(Map<String, String> names, String full, String compact)
    {
        names.put(normalize(full), compact);
    }

    private static String replaceIgnoreCase(String value, String target, String replacement)
    {
        int start = value.toLowerCase(Locale.ROOT).indexOf(target.toLowerCase(Locale.ROOT));
        if (start < 0)
        {
            return value;
        }
        return value.substring(0, start) + replacement
            + value.substring(start + target.length());
    }

    private static String removeTrailingIgnoreCase(String value, String suffix)
    {
        return value.toLowerCase(Locale.ROOT).endsWith(suffix.toLowerCase(Locale.ROOT))
            ? value.substring(0, value.length() - suffix.length()).trim()
            : value;
    }

    private static boolean startsWithIgnoreCase(String value, String prefix)
    {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static boolean equalsIgnoreCase(String left, String right)
    {
        return left.equalsIgnoreCase(right);
    }

    private static String normalize(String value)
    {
        return value.trim().replace('\u2019', '\'').toLowerCase(Locale.ROOT);
    }

    private static String safeTrim(String value)
    {
        return value == null ? "" : value.trim();
    }

    private static final class NameParts
    {
        private final String name;
        private final String suffix;

        private NameParts(String name, String suffix)
        {
            this.name = name;
            this.suffix = suffix;
        }
    }
}
