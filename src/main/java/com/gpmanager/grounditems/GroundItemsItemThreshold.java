package com.gpmanager.grounditems;

/**
 * Parses one Ground Items list entry ({@code name}, {@code name>5}, {@code name<5},
 * wildcards with {@code *}). Mirrors RuneLite {@code ItemThreshold} semantics
 * without referencing package-private Ground Items types.
 */
final class GroundItemsItemThreshold
{
    enum Inequality
    {
        LESS_THAN,
        MORE_THAN
    }

    private final String name;
    private final int quantity;
    private final Inequality inequality;
    private final boolean wildcard;

    private GroundItemsItemThreshold(String name, int quantity, Inequality inequality, boolean wildcard)
    {
        this.name = name;
        this.quantity = quantity;
        this.inequality = inequality;
        this.wildcard = wildcard;
    }

    static GroundItemsItemThreshold fromName(String entry)
    {
        if (entry == null || entry.trim().isEmpty())
        {
            return null;
        }

        Inequality operator = Inequality.MORE_THAN;
        int qty = 0;
        boolean wildcard = entry.contains("*");
        String working = entry;

        for (int i = working.length() - 1; i >= 0; i--)
        {
            char c = working.charAt(i);
            if (c >= '0' && c <= '9' || Character.isWhitespace(c))
            {
                continue;
            }
            switch (c)
            {
                case '<':
                    operator = Inequality.LESS_THAN;
                    // fallthrough
                case '>':
                    if (i + 1 < working.length())
                    {
                        try
                        {
                            qty = Integer.parseInt(working.substring(i + 1).trim());
                        }
                        catch (NumberFormatException e)
                        {
                            qty = 0;
                            operator = Inequality.MORE_THAN;
                        }
                        working = working.substring(0, i);
                    }
                    break;
                default:
                    break;
            }
            break;
        }

        return new GroundItemsItemThreshold(working.trim(), qty, operator, wildcard);
    }

    String getName()
    {
        return name;
    }

    boolean isWildcard()
    {
        return wildcard;
    }

    boolean quantityHolds(int itemCount)
    {
        if (inequality == Inequality.LESS_THAN)
        {
            return itemCount < quantity;
        }
        return itemCount > quantity;
    }

    boolean isUnconditional()
    {
        return quantity == 0 && inequality == Inequality.MORE_THAN;
    }
}
