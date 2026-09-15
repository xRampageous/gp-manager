package com.gpmanager.engine;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Agility courses by map region, so Agility XP earns a named activity ("Wilderness Agility
 * Course") instead of the bare skill. Presentation / activity naming only — accounting never
 * reads this. Region ids follow the wiki course maps (and RuneLite's agility plugin).
 */
public final class AgilityCourses
{
    private static final Map<Integer, String> BY_REGION;

    static
    {
        Map<Integer, String> map = new HashMap<>();
        map.put(9781, "Gnome Stronghold Course");
        map.put(6200, "Shayzien Basic Course");
        map.put(5944, "Shayzien Advanced Course");
        map.put(12338, "Draynor Rooftop");
        map.put(13105, "Al Kharid Rooftop");
        map.put(13356, "Agility Pyramid");
        map.put(12853, "Varrock Rooftop");
        map.put(10559, "Penguin Agility Course");
        map.put(10039, "Barbarian Outpost Course");
        map.put(13878, "Canifis Rooftop");
        map.put(11050, "Ape Atoll Course");
        map.put(12084, "Falador Rooftop");
        map.put(11837, "Wilderness Agility Course");
        map.put(14234, "Werewolf Agility Course");
        map.put(10806, "Seers' Village Rooftop");
        map.put(13358, "Pollnivneach Rooftop");
        map.put(10553, "Rellekka Rooftop");
        map.put(12895, "Prifddinas Course");
        map.put(10547, "Ardougne Rooftop");
        map.put(11157, "Brimhaven Agility Arena");
        BY_REGION = Collections.unmodifiableMap(map);
    }

    private AgilityCourses()
    {
    }

    /** Course name for a region, or null when the region is not a known course. */
    @Nullable
    public static String courseName(int regionId)
    {
        return BY_REGION.get(regionId);
    }

    /** The Wilderness course's reward dispenser and the Brimhaven ticket dispenser. */
    public static boolean isDispenser(@Nullable String target)
    {
        if (target == null)
        {
            return false;
        }
        String lower = target.trim().toLowerCase(Locale.ROOT);
        return lower.contains("agility dispenser") || lower.contains("ticket dispenser");
    }
}
