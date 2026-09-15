package com.gpmanager.model;

public enum SessionCategory
{
    ALL("All", null),
    GENERAL("General", null),
    PVM("PvM", "pvm"),
    SKILLING("Skilling", "skilling"),
    TRADING("Trading", "trading"),
    PKING("PKing", "pvp"),
    MIXED("Mixed", null),
    BOSSING("Bossing", "bossing"),
    RAIDS("Raids", "raids"),
    SLAYER("Slayer", "slayer"),
    OTHER("Other", null);

    private final String displayName;
    private final String sidebarTag;

    SessionCategory(String displayName, String sidebarTag)
    {
        this.displayName = displayName;
        this.sidebarTag = sidebarTag;
    }

    public String getDisplayName()
    {
        return displayName;
    }

    /** Tag used by the sidebar's pre-override category representation. */
    public String getSidebarTag()
    {
        return sidebarTag;
    }

    /** Returns the exact recognized category tag, or null for an ordinary tag. */
    public static SessionCategory fromSidebarTag(String tag)
    {
        if (tag == null) return null;
        String normalized = tag.trim();
        for (SessionCategory category : values())
        {
            if (category.sidebarTag != null && category.sidebarTag.equalsIgnoreCase(normalized))
            {
                return category;
            }
        }
        return null;
    }

    @Override
    public String toString()
    {
        return displayName;
    }
}
