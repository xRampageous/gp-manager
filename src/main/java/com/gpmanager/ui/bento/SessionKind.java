package com.gpmanager.ui.bento;

import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.SessionCategory;
import com.gpmanager.model.SessionMode;
import java.util.Locale;
import javax.annotation.Nullable;

/**
 * The category a session is started with (SIDEBAR_BENTO.md §13.2). Stored as the engine's
 * {@link SessionCategory} where one exists (PvP also turns PK accounting on) and as a tag
 * otherwise; read back from either. Presentation and naming only.
 */
public enum SessionKind
{
    PVM("PvM", "pvm", SessionMode.AUTO, SessionCategory.PVM),
    BOSSING("Bossing", "bossing", SessionMode.AUTO, SessionCategory.BOSSING),
    RAIDS("Raids", "raids", SessionMode.AUTO, SessionCategory.RAIDS),
    SLAYER("Slayer", "slayer", SessionMode.AUTO, SessionCategory.SLAYER),
    SKILLING("Skilling", "skilling", SessionMode.AUTO, SessionCategory.SKILLING),
    PVP("PvP", "pvp", SessionMode.PK, SessionCategory.PKING),
    TRADING("Trading", "trading", SessionMode.AUTO, SessionCategory.TRADING),
    OTHER("Other", "", SessionMode.AUTO, SessionCategory.OTHER);

    public final String label;
    public final String tag;
    public final SessionMode mode;
    /** The engine's persisted category (pass 8 step 28); the tag is only the pre-pass-8 form. */
    public final SessionCategory category;

    SessionKind(String label, String tag, SessionMode mode, SessionCategory category)
    {
        this.label = label;
        this.tag = tag;
        this.mode = mode;
        this.category = category;
    }

    @Nullable
    public static SessionKind ofCategory(@Nullable SessionCategory c)
    {
        if (c == null)
        {
            return null;
        }
        for (SessionKind k : values())
        {
            if (k.category == c)
            {
                return k;
            }
        }
        return null;
    }

    @Override
    public String toString()
    {
        return label;
    }

    /** The persisted category first, then a pre-pass-8 tag, then the engine's derived category. */
    @Nullable
    public static SessionKind of(ProfitSession s)
    {
        SessionKind explicit = ofCategory(s.getCategoryOverride());
        if (explicit != null)
        {
            return explicit;
        }
        String tags = s.getTagsDisplay();
        if (tags != null && !tags.isEmpty())
        {
            for (String raw : tags.split(","))
            {
                String t = raw.trim().toLowerCase(Locale.ROOT);
                for (SessionKind k : values())
                {
                    if (!k.tag.isEmpty() && k.tag.equals(t))
                    {
                        return k;
                    }
                }
            }
        }
        SessionCategory c = s.getCategory();
        return c == SessionCategory.GENERAL || c == SessionCategory.MIXED || c == SessionCategory.ALL ? null : ofCategory(c);
    }

    /** What the detector's category and the name suggest, for the picker's default. */
    public static SessionKind suggest(@Nullable String activity, @Nullable String detectorCategory, boolean pvpPossible)
    {
        String a = (activity == null ? "" : activity).toLowerCase(Locale.ROOT);
        String d = (detectorCategory == null ? "" : detectorCategory).toLowerCase(Locale.ROOT);
        if (pvpPossible || d.contains("pvp") || a.contains("pk"))
        {
            return PVP;
        }
        if (a.startsWith("slayer") || a.contains("slayer"))
        {
            return SLAYER;
        }
        if (d.contains("raid") || a.contains("chambers") || a.contains("theatre") || a.contains("tombs"))
        {
            return RAIDS;
        }
        if (d.contains("skill") || a.contains("agility") || a.contains("woodcut") || a.contains("mining") || a.contains("fishing")
            || a.contains("runecraft") || a.contains("crafting") || a.contains("herblore") || a.contains("smithing")
            || a.contains("cooking") || a.contains("fletching") || a.contains("farming") || a.contains("hunter")
            || a.contains("thieving") || a.contains("firemaking") || a.contains("construction") || a.contains("prayer"))
        {
            return SKILLING;
        }
        if (d.contains("trad"))
        {
            return TRADING;
        }
        if (d.contains("pvm") || !a.isEmpty())
        {
            return BOSSING;
        }
        return OTHER;
    }

    /** The tag list with any category tag swapped for {@code kind}'s (Other clears it). */
    public static String retag(@Nullable String tagsDisplay, SessionKind kind)
    {
        java.util.List<String> kept = new java.util.ArrayList<>();
        if (tagsDisplay != null)
        {
            for (String raw : tagsDisplay.split(","))
            {
                String t = raw.trim();
                if (t.isEmpty())
                {
                    continue;
                }
                boolean isKind = false;
                for (SessionKind k : values())
                {
                    isKind |= !k.tag.isEmpty() && k.tag.equalsIgnoreCase(t);
                }
                if (!isKind)
                {
                    kept.add(t);
                }
            }
        }
        if (kind != null && !kind.tag.isEmpty())
        {
            kept.add(0, kind.tag);
        }
        return String.join(", ", kept);
    }

    /** The label for a session row; empty when nothing is known. */
    public static String labelOf(ProfitSession s)
    {
        SessionKind k = of(s);
        return k == null ? "" : k.label;
    }
}
