package com.gpmanager.ui;

import java.util.Locale;

/**
 * Single table for HUD+ process skill transitions: Make-X questions, chat
 * failsafes, loss-only menus, and open-intent spend families.
 *
 * <p>Make-X question literals inspired by the Action Progress plugin
 * (BSD-2-Clause) — {@code guillaume009/runelite-plugin-action-progress} /
 * {@code CalebWhiting/runelite-plugin-action-progress}. No runtime dependency.
 */
public final class ProcessSkillSignals
{
    /** Verb fragment → skill for "how/what would you like to &lt;verb&gt;". */
    private static final String[][] MAKE_X_VERBS = {
        {"cook", "Cooking"},
        {"burn", "Firemaking"},
        {"smelt", "Smelting"},
        {"smith", "Smithing"},
        {"fletch", "Fletching"},
        {"mix", "Herblore"},
        {"clean", "Herblore"},
        {"build", "Construction"},
        {"enchant", "Magic"},
        {"charge", "Magic"},
        {"blow", "Crafting"},
        {"weave", "Crafting"},
        {"grind", "Crafting"},
        {"string", "Crafting"},
        {"craft", "Crafting"},
    };

    private static final String[] FLETCH_PRODUCT_HINTS = {
        "how many bows would you like",
        "how many crossbows would you like",
        "how many stocks would you like",
        "how many shieldbows would you like",
        "how many sets of",
    };

    private static final String[] CRAFT_PRODUCT_HINTS = {
        "leather", "glass", "pottery", "jewellery", "jewelry", "battlestaff", "gem", "amulet",
    };

    private ProcessSkillSignals()
    {
    }

    /**
     * Make-X / chatbox questions → canonical process-skill hint, or empty.
     */
    public static String skillFromMakeXQuestion(String text)
    {
        if (text == null || text.isEmpty())
        {
            return "";
        }
        String m = text.toLowerCase(Locale.ROOT);
        if (!(m.contains("how many") || m.contains("what would you like")))
        {
            return "";
        }
        // Product-specific before generic "craft" (rune vs leather).
        if (m.contains("rune"))
        {
            return "Runecraft";
        }
        if (m.contains("plank"))
        {
            return "Construction";
        }
        if (m.contains("potion") || m.contains("herb"))
        {
            return "Herblore";
        }
        if (m.contains("cake") || m.contains("pie"))
        {
            return "Cooking";
        }
        if (m.contains("tablet") || m.contains("orb") || m.contains("bolt") && m.contains("enchant"))
        {
            return "Magic";
        }
        for (String hint : FLETCH_PRODUCT_HINTS)
        {
            if (m.contains(hint))
            {
                if (hint.equals("how many sets of")
                    && !(m.contains("arrow") || m.contains("bolt") || m.contains("javelin")))
                {
                    continue;
                }
                return "Fletching";
            }
        }
        if (m.contains("how many would you like to make") || m.contains("what would you like to make"))
        {
            for (String hint : CRAFT_PRODUCT_HINTS)
            {
                if (m.contains(hint))
                {
                    return "Crafting";
                }
            }
            if (m.contains("fletch"))
            {
                return "Fletching";
            }
        }
        for (String[] row : MAKE_X_VERBS)
        {
            String verb = row[0];
            if (m.contains("to " + verb) || m.contains("like to " + verb)
                || m.contains("sets would you like to " + verb))
            {
                return row[1];
            }
        }
        return "";
    }

    /**
     * Game/spam chat that names a process skill, for receipt-intent routing only.
     * Chat text is never sufficient evidence to promote the live HUD title.
     */
    public static String skillFromChatMessage(String message)
    {
        if (message == null || message.isEmpty())
        {
            return "";
        }
        String m = message.toLowerCase(Locale.ROOT);
        String makeX = skillFromMakeXQuestion(m);
        if (!makeX.isEmpty())
        {
            return makeX;
        }
        if (containsAny(m, "you light the", "you light a fire", "the fire catches", "logs begin to burn"))
        {
            return "Firemaking";
        }
        if (containsAny(m, "you successfully cook", "you cook a ", "you cook the ",
            "you manage to cook", "you accidentally burn the", "you burn the "))
        {
            return "Cooking";
        }
        if (m.contains("you smelt"))
        {
            return "Smelting";
        }
        if (m.contains("you smith ") || m.contains("you hammer the ")
            || (m.contains("you make a ") && m.contains(" bar")))
        {
            return "Smithing";
        }
        if (containsAny(m, "you fletch", "you carefully cut", "you string the"))
        {
            return "Fletching";
        }
        if (containsAny(m, "you craft ", "you cut the gem", "you blow the",
            "you weave ", "you grind the", "you tan the"))
        {
            return "Crafting";
        }
        if (containsAny(m, "you mix the", "you combine the", "you clean the "))
        {
            return "Herblore";
        }
        if (m.contains("you build ") || (m.contains("you create a ") && m.contains("furniture")))
        {
            return "Construction";
        }
        if ((m.contains("you cast ") && (m.contains("alchemy") || m.contains("alch")))
            || (m.contains("you convert ") && m.contains("coins"))
            || m.contains("high level alchemy") || m.contains("low level alchemy"))
        {
            return "Magic";
        }
        if (containsAny(m, "you bury", "you scatter",
            "gods are pleased with your offering",
            "gods are very pleased with your offering",
            "you offer ",
            "dark lord spares",
            "sinister offering"))
        {
            return "Prayer";
        }
        if (m.contains("you bind the") || (m.contains("you craft some") && m.contains("rune")))
        {
            return "Runecraft";
        }
        return "";
    }

    /** Prayer tray verb modes — bury vs altar/spell offer vs ash scatter. */
    public enum PrayerSpendMode
    {
        BURY,
        SCATTER,
        OFFER;

        /** Source name for HUD tray skillingVerb (Buried / Scattered / Offered). */
        public String traySourceName()
        {
            switch (this)
            {
                case BURY:
                    return "Buried";
                case SCATTER:
                    return "Scattered";
                case OFFER:
                default:
                    return "Offered";
            }
        }

        public String wireName()
        {
            return name().toLowerCase(Locale.ROOT);
        }

        public static PrayerSpendMode fromWire(String wire)
        {
            if (wire == null || wire.trim().isEmpty())
            {
                return OFFER;
            }
            switch (wire.trim().toLowerCase(Locale.ROOT))
            {
                case "bury":
                    return BURY;
                case "scatter":
                    return SCATTER;
                case "offer":
                default:
                    return OFFER;
            }
        }
    }

    /** Loss-only process menus (plus Tend-to campfire). */
    public static boolean isLossOnlyProcessSpendOption(String option)
    {
        if (option == null || option.isEmpty())
        {
            return false;
        }
        String lower = option.trim().toLowerCase(Locale.ROOT);
        return "light".equals(lower)
            || "tend-to".equals(lower)
            || "tend to".equals(lower)
            || "offer".equals(lower)
            || "pray".equals(lower)
            || "pray-at".equals(lower)
            || lower.contains("bury")
            || lower.contains("scatter");
    }

    public static String processSpendSkillForOption(String option)
    {
        if (option == null || option.isEmpty())
        {
            return "";
        }
        String lower = option.trim().toLowerCase(Locale.ROOT);
        if ("light".equals(lower) || "tend-to".equals(lower) || "tend to".equals(lower))
        {
            return "Firemaking";
        }
        if ("offer".equals(lower) || "pray".equals(lower) || "pray-at".equals(lower)
            || lower.contains("bury") || lower.contains("scatter"))
        {
            return "Prayer";
        }
        return "";
    }

    /**
     * Prayer spend mode from menu option. Empty when not a Prayer loss-only option.
     */
    public static PrayerSpendMode prayerModeFromOption(String option)
    {
        if (option == null || option.isEmpty())
        {
            return null;
        }
        String lower = option.trim().toLowerCase(Locale.ROOT);
        if (lower.contains("scatter"))
        {
            return PrayerSpendMode.SCATTER;
        }
        if (lower.contains("bury"))
        {
            return PrayerSpendMode.BURY;
        }
        if ("offer".equals(lower) || "pray".equals(lower) || "pray-at".equals(lower))
        {
            return PrayerSpendMode.OFFER;
        }
        return null;
    }

    /**
     * Use Tinderbox ↔ fuel. Requires fuel in the target and either tinderbox or a
     * Use-on arrow so plain "Use logs" without a light pair does not arm Firemaking.
     */
    public static boolean isFiremakingUsePair(String option, String target)
    {
        if (option == null || !"use".equals(option.trim().toLowerCase(Locale.ROOT)))
        {
            return false;
        }
        String t = target == null ? "" : target.toLowerCase(Locale.ROOT);
        boolean fuel = isFiremakingFuelName(t);
        boolean tinder = t.contains("tinderbox");
        boolean useOn = t.contains("->") || t.contains("→");
        return fuel && (tinder || useOn);
    }

    /**
     * Use bones/ashes ↔ altar (PoH gilded / Wilderness chaos).
     * OSRS: Use bone on altar or Offer/Pray; Chaos Altar does not accept demonic ashes
     * ([Chaos Temple](https://oldschool.runescape.wiki/w/Chaos_Temple_(church))).
     */
    public static boolean isPrayerAltarUsePair(String option, String target)
    {
        if (option == null || !"use".equals(option.trim().toLowerCase(Locale.ROOT)))
        {
            return false;
        }
        String t = target == null ? "" : target.toLowerCase(Locale.ROOT);
        if (!t.contains("altar"))
        {
            return false;
        }
        // Demonic ashes work on PoH altars / scatter, not Chaos Altar — still arm
        // Prayer; XP absence → Used timeout (no fake cost without invent loss).
        return isPrayerRemainsName(t);
    }

    /** Cast Sinister Offering (Arceuus spellbook, OSRS). */
    public static boolean isSinisterOfferingCast(String option, String target)
    {
        if (option == null || !option.trim().toLowerCase(Locale.ROOT).startsWith("cast"))
        {
            return false;
        }
        String t = target == null ? "" : target.toLowerCase(Locale.ROOT);
        return t.contains("sinister offering");
    }

    public static boolean isPrayerRemainsName(String lowerName)
    {
        if (lowerName == null || lowerName.isEmpty())
        {
            return false;
        }
        return lowerName.contains("bones") || lowerName.contains("ashes")
            || lowerName.contains("ensouled") || lowerName.contains("blessed bone");
    }

    /** PoH gilded-altar success chat — reinforce consume intent (OSRS wiki). */
    public static boolean isAltarOfferingChat(String message)
    {
        if (message == null || message.isEmpty())
        {
            return false;
        }
        String m = message.toLowerCase(Locale.ROOT);
        // Exact wiki forms: "The gods are pleased with your offering"
        // and "The gods are very pleased with your offering" (both burners lit).
        // "gods are pleased" is NOT a substring of the "very" form — match both.
        return m.contains("gods are pleased with your offering")
            || m.contains("gods are very pleased with your offering")
            || m.contains("you offer ");
    }

    /**
     * Chaos Altar 50% bone-save (Wilderness) — Prayer title only.
     * OSRS: "The Dark Lord spares your sacrifice but still rewards you for your efforts."
     * Invent may not lose a bone; must not reinforce consume intent.
     */
    public static boolean isChaosAltarBoneSaveChat(String message)
    {
        if (message == null || message.isEmpty())
        {
            return false;
        }
        String m = message.toLowerCase(Locale.ROOT);
        return m.contains("dark lord spares your sacrifice")
            || m.contains("dark lord spares");
    }

    /**
     * Arceuus spell Sinister Offering ([wiki](https://oldschool.runescape.wiki/w/Sinister_Offering))
     * — up to 3 bones for 300% bury XP.
     */
    public static boolean isSinisterOfferingChat(String message)
    {
        if (message == null || message.isEmpty())
        {
            return false;
        }
        return message.toLowerCase(Locale.ROOT).contains("sinister offering");
    }

    /**
     * Open process-spend intent: loss stacks must look like fuel for the armed skill.
     */
    public static boolean matchesOpenSpendFamily(String skillName, String itemName, int itemId)
    {
        if (skillName == null || skillName.trim().isEmpty())
        {
            return false;
        }
        String skill = skillName.trim().toLowerCase(Locale.ROOT);
        String name = itemName == null ? "" : itemName.toLowerCase(Locale.ROOT);
        if (name.isEmpty() && itemId < 0)
        {
            return false;
        }
        switch (skill)
        {
            case "firemaking":
                return isFiremakingFuelName(name);
            case "cooking":
                return isRawFoodName(name);
            case "prayer":
            case "buried":
            case "scattered":
            case "offered":
                return isPrayerRemainsName(name);
            case "magic":
            case "alchemy":
                return false;
            default:
                return false;
        }
    }

    private static boolean isRawFoodName(String lowerName)
    {
        return lowerName != null
            && (lowerName.startsWith("raw ") || lowerName.startsWith("uncooked "));
    }

    public static boolean isFiremakingFuelName(String lowerName)
    {
        if (lowerName == null || lowerName.isEmpty())
        {
            return false;
        }
        return lowerName.contains("logs")
            || lowerName.contains("juniper")
            || lowerName.contains("redwood")
            || lowerName.contains("blisterwood")
            || lowerName.contains("pyre")
            || "kindling".equals(lowerName)
            || lowerName.endsWith(" kindling");
    }

    /** Skills whose chat/Make-X hint may arm a loss-only receipt intent. */
    public static boolean armsOpenProcessSpendFromChat(String skill)
    {
        return skill != null && "firemaking".equalsIgnoreCase(skill.trim());
    }

    private static boolean containsAny(String haystack, String... needles)
    {
        for (String n : needles)
        {
            if (haystack.contains(n))
            {
                return true;
            }
        }
        return false;
    }
}
