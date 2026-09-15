package com.gpmanager.ui;

import java.util.Locale;

/**
 * Presentation-only HUD+ process / gather title helpers. Never mutates accounting.
 */
public final class HudPlusProcessLabels
{
    private static final String[] TRAVERSAL_OBJECT_NAMES = {
        "ditch", "door", "gate", "large door", "ladder", "stairs", "staircase",
        "trapdoor", "rope", "tunnel", "crevice", "hole", "cave entrance",
        "cave exit", "stepping stone", "log balance", "portal", "fairy ring",
        "spirit tree", "obelisk", "lever", "gangplank", "boat", "canoe",
        "magic carpet", "charter ship", "mushtree", "portal nexus", "jewellery box",
        "mounted glory", "wilderness ditch", "fence", "stile"
    };

    private HudPlusProcessLabels()
    {
    }

    /** Gather skills never become HUD+ titles — prefer a target or an empty live header. */
    public static boolean isGatherSkillTitle(String activity)
    {
        if (activity == null || activity.trim().isEmpty())
        {
            return false;
        }
        String lower = activity.trim().toLowerCase(Locale.ROOT);
        // Agility is deliberately absent: a course (or the skill) is a real activity title.
        return "woodcutting".equals(lower)
            || "mining".equals(lower)
            || "fishing".equals(lower)
            || "farming".equals(lower)
            || "hunter".equals(lower);
    }

    /**
     * True when a held process title must drop the moment the player engages an NPC.
     * Bury/Offer/Firemaking/Cooking/… cannot involve an NPC, so a fresh target is
     * stronger evidence than the stale spend; Thieving and Magic are themselves
     * NPC-targeted (pickpocket, cast) and keep their title.
     */
    public static boolean yieldsToNpcEngagement(String activity)
    {
        if (!isProcessTitle(activity))
        {
            return false;
        }
        String lower = activity.trim().toLowerCase(Locale.ROOT);
        return !"thieving".equals(lower) && !"magic".equals(lower);
    }

    /**
     * True when a late chat/XP signal should be ignored while an NPC is the active
     * target. Magic and Thieving remain eligible because they are NPC-targeted actions.
     */
    public static boolean shouldSuppressProcessSignalDuringNpcEngagement(
        String activity, boolean engagedWithNpc)
    {
        return engagedWithNpc && yieldsToNpcEngagement(activity);
    }

    /** Process-like titles allowed on the HUD when no interaction target. */
    public static boolean isProcessTitle(String activity)
    {
        if (activity == null || activity.trim().isEmpty())
        {
            return false;
        }
        String lower = activity.trim().toLowerCase(Locale.ROOT);
        return "crafting".equals(lower)
            || "smithing".equals(lower)
            || "smelting".equals(lower)
            || "fletching".equals(lower)
            || "cooking".equals(lower)
            || "herblore".equals(lower)
            || "firemaking".equals(lower)
            || "runecraft".equals(lower)
            || "runecrafting".equals(lower)
            || "construction".equals(lower)
            || "spinning".equals(lower)
            || "stringing".equals(lower)
            || "chiselling".equals(lower)
            || "chiseling".equals(lower)
            || "cutting".equals(lower)
            || "cleaning".equals(lower)
            || "enchanting".equals(lower)
            || "glassblowing".equals(lower)
            || "grinding".equals(lower)
            || "weaving".equals(lower)
            || "casting".equals(lower)
            || "planking".equals(lower)
            || "prayer".equals(lower)
            || "thieving".equals(lower)
            || "magic".equals(lower);
    }

    /**
     * Menu option → process gerund for HUD header, or null if not a process op.
     * Bare {@code make} is intentionally omitted (Make-X) — wait for XP skill.
     */
    public static String gerundFromMenuOption(String option)
    {
        if (option == null || option.isEmpty())
        {
            return null;
        }
        String lower = option.trim().toLowerCase(Locale.ROOT);
        switch (lower)
        {
            case "smelt":
                return "Smelting";
            case "smith":
                return "Smithing";
            case "spin":
                return "Spinning";
            case "chisel":
                return "Chiselling";
            case "craft":
                return "Crafting";
            case "string":
                return "Stringing";
            case "fletch":
                return "Fletching";
            case "cut":
                return "Cutting";
            case "blow":
                return "Glassblowing";
            case "weave":
                return "Weaving";
            case "grind":
                return "Grinding";
            case "cast":
                // Object cast (mould) only — inventory spell cast handled elsewhere.
                return "Casting";
            case "cook":
                return "Cooking";
            case "light":
                return "Firemaking";
            case "tend-to":
            case "tend to":
                return "Firemaking";
            case "build":
            case "remove":
                return "Construction";
            case "mix":
            case "combine":
                return "Herblore";
            case "steal":
            case "pickpocket":
                return "Thieving";
            case "check":
            case "dismantle":
            case "lay":
            case "catch":
                return "Hunter";
            case "offer":
            case "pray":
            case "pray-at":
                return "Prayer";
            case "plant":
                return "Planted";
            case "pick":
            case "pick fruit":
                return "Picking";
            case "harvest":
                return "Harvest";
            case "rake":
                return "Rake";
            case "clean":
                return "Cleaning";
            case "enchant":
                return "Enchanting";
            case "tip":
            case "feather":
            case "attach":
                return "Fletching";
            case "plank":
                return "Planking";
            case "tan":
                return "Crafting";
            case "mould":
                return "Crafting";
            default:
                return null;
        }
    }

    /** True when the menu option should update interaction context as processing/gather. */
    public static boolean isTrackedObjectOption(String option)
    {
        if (option == null || option.isEmpty())
        {
            return false;
        }
        String lower = option.trim().toLowerCase(Locale.ROOT);
        if ("make".equals(lower))
        {
            // Make-X alone does not set a title — XP / known process options do.
            return false;
        }
        return "chop down".equals(lower)
            || "chop".equals(lower)
            || "mine".equals(lower)
            || "fish".equals(lower)
            || "net".equals(lower)
            || "bait".equals(lower)
            || "lure".equals(lower)
            || "harpoon".equals(lower)
            || "cage".equals(lower)
            || "pick".equals(lower)
            || "pick fruit".equals(lower)
            || "harvest".equals(lower)
            || "rake".equals(lower)
            || "plant".equals(lower)
            || "search".equals(lower)
            || "climb".equals(lower)
            || "climb-up".equals(lower)
            || "climb-down".equals(lower)
            || "jump".equals(lower)
            || "squeeze-past".equals(lower)
            || "cross".equals(lower)
            || "walk-across".equals(lower)
            || gerundFromMenuOption(lower) != null;
    }

    /**
     * True only when an object action names the activity being performed. Keep
     * {@link #isTrackedObjectOption(String)} for gameplay-activity and idle-reset
     * semantics; movement options may be meaningful without being HUD titles.
     */
    public static boolean isTitledObjectOption(String option, String objectName)
    {
        if (option == null || option.trim().isEmpty()
            || objectName == null || objectName.trim().isEmpty())
        {
            return false;
        }
        String action = normalizeWords(option);
        String name = normalizeWords(objectName);
        if (action.isEmpty() || name.isEmpty()
            || hasPhrase(name, "bank")
            || isTraversalOption(action) || isTraversalObjectName(name))
        {
            return false;
        }

        if (isTrackedObjectOption(option))
        {
            return true;
        }

        if (isRewardContainerAction(action) && hasAnyWord(name,
            "chest", "coffer", "casket", "reward", "dispenser"))
        {
            return true;
        }

        return isMinigameEntryAction(action) && isMinigameName(name);
    }

    private static boolean isTraversalOption(String action)
    {
        return action.startsWith("climb")
            || action.startsWith("jump")
            || action.equals("cross") || action.startsWith("cross ")
            || action.startsWith("walk across")
            || action.startsWith("squeeze through")
            || action.startsWith("squeeze past")
            || action.equals("enter") || action.startsWith("enter ")
            || action.equals("exit") || action.startsWith("exit ")
            || action.equals("pass") || action.startsWith("pass ")
            || action.startsWith("go through")
            || action.equals("travel") || action.startsWith("travel ")
            || action.equals("board") || action.startsWith("board ")
            || action.equals("ride") || action.startsWith("ride ")
            || action.equals("teleport") || action.startsWith("teleport ");
    }

    private static boolean isTraversalObjectName(String name)
    {
        for (String traversalName : TRAVERSAL_OBJECT_NAMES)
        {
            if (hasPhrase(name, traversalName))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean isRewardContainerAction(String action)
    {
        return action.equals("open") || action.startsWith("open ")
            || action.equals("take") || action.startsWith("take ")
            || action.equals("unlock") || action.startsWith("unlock ")
            || action.equals("search") || action.startsWith("search ")
            || action.equals("claim") || action.startsWith("claim ");
    }

    private static boolean isMinigameEntryAction(String action)
    {
        return action.equals("start") || action.startsWith("start ")
            || action.equals("play") || action.startsWith("play ")
            || action.equals("join") || action.startsWith("join ");
    }

    private static boolean isMinigameName(String name)
    {
        return hasAnyPhrase(name, "minigame", "gauntlet", "barrows", "tempoross",
            "wintertodt", "pest control", "soul wars", "guardians of the rift",
            "hallowed sepulchre", "chambers of xeric", "theatre of blood",
            "tombs of amascut", "fight caves", "inferno", "fishing trawler");
    }

    private static boolean hasAnyWord(String value, String... words)
    {
        for (String word : words)
        {
            if (hasPhrase(value, word))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnyPhrase(String value, String... phrases)
    {
        for (String phrase : phrases)
        {
            if (hasPhrase(value, phrase))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPhrase(String normalizedText, String phrase)
    {
        String paddedText = " " + normalizedText + " ";
        return paddedText.contains(" " + normalizeWords(phrase) + " ");
    }

    private static String normalizeWords(String value)
    {
        return value.trim().toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
    }

    /** Scenery name → process gerund when the station itself is the activity. */
    public static String gerundFromScenery(String objectName)
    {
        if (objectName == null || objectName.trim().isEmpty())
        {
            return null;
        }
        String lower = objectName.trim().toLowerCase(Locale.ROOT);
        if (lower.contains("bank"))
        {
            return null;
        }
        if (lower.contains("blast furnace") || lower.contains("furnace"))
        {
            return "Smelting";
        }
        if (lower.contains("anvil"))
        {
            return "Smithing";
        }
        if (lower.contains("spinning wheel"))
        {
            return "Spinning";
        }
        if (lower.contains("pottery oven") || lower.contains("potter's wheel")
            || lower.contains("potters wheel"))
        {
            return "Crafting";
        }
        if (lower.contains("loom"))
        {
            return "Weaving";
        }
        // Ranges/ovens are Cooking. Bare Fire / campfire are ambiguous (cook vs
        // firemake) — leave null so the menu action gerund decides.
        if (lower.contains("range") || lower.contains("stove") || lower.contains("oven")
            || lower.contains("cooking"))
        {
            return "Cooking";
        }
        if (lower.contains("altar"))
        {
            return "Prayer";
        }
        if (lower.contains("workbench") || lower.contains("crafting table")
            || lower.contains("craft table"))
        {
            return "Crafting";
        }
        if (lower.contains("sawmill"))
        {
            return "Planking";
        }
        if (lower.contains("lectern"))
        {
            return "Magic";
        }
        return null;
    }

    /**
     * Prefer process gerund over generic station names; keep concrete gather targets
     * (Oak tree, Copper rocks). Agility obstacles keep the object name.
     */
    public static String resolveHudPlusObjectName(String objectName, String actionGerund)
    {
        // Menu action wins over scenery (Cook vs Light on the same Fire).
        if (actionGerund != null && !actionGerund.isEmpty() && isProcessGerund(actionGerund)
            && !"Hunter".equals(actionGerund))
        {
            return actionGerund;
        }
        String scenery = gerundFromScenery(objectName);
        if (scenery != null)
        {
            return scenery;
        }
        // Farm verbs without a strong object name — rare; prefer object when present.
        if (actionGerund != null
            && ("Planted".equals(actionGerund)
                || "Picking".equals(actionGerund)
                || "Harvest".equals(actionGerund)
                || "Rake".equals(actionGerund))
            && (objectName == null || objectName.trim().isEmpty()
                || isGenericFarmObject(objectName)))
        {
            return actionGerund;
        }
        return objectName == null ? "" : objectName.trim();
    }

    public static boolean isProcessGerund(String label)
    {
        if (label == null || label.isEmpty())
        {
            return false;
        }
        return isProcessTitle(label)
            || "planted".equalsIgnoreCase(label)
            || "picking".equalsIgnoreCase(label)
            || "harvest".equalsIgnoreCase(label)
            || "rake".equalsIgnoreCase(label)
            || "cleaning".equalsIgnoreCase(label)
            || "hunter".equalsIgnoreCase(label);
    }

    private static boolean isGenericFarmObject(String objectName)
    {
        String lower = objectName.trim().toLowerCase(Locale.ROOT);
        return "farming patch".equals(lower)
            || lower.endsWith(" patch")
            || "herb patch".equals(lower);
    }

    /**
     * Activity hint for observeActivity from a process/gather menu option.
     * Farming verbs map to Farming for Insights consistency.
     */
    public static String activityHintFromOption(String option)
    {
        if (option == null || option.isEmpty())
        {
            return "General";
        }
        String lower = option.trim().toLowerCase(Locale.ROOT);
        if ("plant".equals(lower) || "rake".equals(lower) || "pick".equals(lower)
            || "pick fruit".equals(lower) || "harvest".equals(lower))
        {
            return "Farming";
        }
        if ("climb".equals(lower) || "climb-up".equals(lower) || "climb-down".equals(lower)
            || "jump".equals(lower) || "squeeze-past".equals(lower) || "cross".equals(lower)
            || "walk-across".equals(lower))
        {
            return "Agility";
        }
        if ("check".equals(lower) || "dismantle".equals(lower) || "lay".equals(lower)
            || "catch".equals(lower))
        {
            return "Hunter";
        }
        String gerund = gerundFromMenuOption(option);
        return gerund == null ? "General" : gerund;
    }

    /**
     * Inventory/object transforms that expect −input +output and arm PRODUCTION.
     * Excludes loss-only spends (light / bury / scatter / offer) and gather verbs.
     */
    public static boolean isTransformProductionOption(String option)
    {
        if (option == null || option.isEmpty())
        {
            return false;
        }
        String lower = option.trim().toLowerCase(Locale.ROOT);
        switch (lower)
        {
            case "fletch":
            case "craft":
            case "smith":
            case "smelt":
            case "spin":
            case "string":
            case "chisel":
            case "cut":
            case "blow":
            case "weave":
            case "grind":
            case "mix":
            case "combine":
            case "cook":
            case "build":
            case "remove":
            case "clean":
            case "enchant":
            case "tip":
            case "feather":
            case "attach":
            case "plank":
            case "tan":
            case "mould":
                return true;
            default:
                return false;
        }
    }

    /**
     * Canonical skill / activity name for PRODUCTION arming and HUD process cards.
     * Maps gerunds (Spinning) onto ledger skill names (Crafting) where needed.
     */
    public static String transformSkillForOption(String option)
    {
        if (option == null || option.isEmpty())
        {
            return "";
        }
        String lower = option.trim().toLowerCase(Locale.ROOT);
        switch (lower)
        {
            case "fletch":
            case "tip":
            case "feather":
            case "attach":
                return "Fletching";
            case "craft":
            case "tan":
            case "mould":
                return "Crafting";
            case "smith":
                return "Smithing";
            case "smelt":
                return "Smelting";
            case "spin":
            case "string":
            case "chisel":
            case "cut":
            case "blow":
            case "weave":
            case "grind":
            case "enchant":
                return "Crafting";
            case "mix":
            case "combine":
            case "clean":
                return "Herblore";
            case "cook":
                return "Cooking";
            case "build":
            case "remove":
                return "Construction";
            case "plank":
                return "Construction";
            default:
                return "";
        }
    }

    /** True when XP skill name should reinforce an open PRODUCTION window. */
    public static boolean isTransformProcessSkill(String skillName)
    {
        if (skillName == null || skillName.trim().isEmpty())
        {
            return false;
        }
        String lower = skillName.trim().toLowerCase(Locale.ROOT);
        return "fletching".equals(lower)
            || "crafting".equals(lower)
            || "smithing".equals(lower)
            || "smelting".equals(lower)
            || "cooking".equals(lower)
            || "herblore".equals(lower)
            || "construction".equals(lower)
            || "runecraft".equals(lower)
            || "runecrafting".equals(lower);
    }
}
