package com.gpmanager.grounditems;

import java.awt.Color;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * One evaluated item against Ground Items highlight/hide rules.
 */
public final class GroundItemsItemDecision
{
    public enum ListMatch
    {
        NONE,
        HIGHLIGHTED,
        HIDDEN
    }

    private final boolean visibleUnderFollow;
    private final boolean onHighlightedList;
    private final boolean hiddenByRules;
    private final boolean valueTierHighlighted;
    private final ListMatch listMatch;
    @Nullable
    private final Color highlightColor;

    public GroundItemsItemDecision(
        boolean visibleUnderFollow,
        boolean onHighlightedList,
        boolean hiddenByRules,
        boolean valueTierHighlighted,
        ListMatch listMatch,
        @Nullable Color highlightColor)
    {
        this.visibleUnderFollow = visibleUnderFollow;
        this.onHighlightedList = onHighlightedList;
        this.hiddenByRules = hiddenByRules;
        this.valueTierHighlighted = valueTierHighlighted;
        this.listMatch = listMatch == null ? ListMatch.NONE : listMatch;
        this.highlightColor = highlightColor;
    }

    public boolean isVisibleUnderFollow()
    {
        return visibleUnderFollow;
    }

    public boolean isOnHighlightedList()
    {
        return onHighlightedList;
    }

    public boolean isHiddenByRules()
    {
        return hiddenByRules;
    }

    public boolean isValueTierHighlighted()
    {
        return valueTierHighlighted;
    }

    /** True when Ground Items would treat the item as highlighted (list, colour, or tier). */
    public boolean isHighlighted()
    {
        return highlightColor != null;
    }

    public ListMatch getListMatch()
    {
        return listMatch;
    }

    @Nullable
    public Color getHighlightColor()
    {
        return highlightColor;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (!(o instanceof GroundItemsItemDecision))
        {
            return false;
        }
        GroundItemsItemDecision that = (GroundItemsItemDecision) o;
        return visibleUnderFollow == that.visibleUnderFollow
            && onHighlightedList == that.onHighlightedList
            && hiddenByRules == that.hiddenByRules
            && valueTierHighlighted == that.valueTierHighlighted
            && listMatch == that.listMatch
            && Objects.equals(highlightColor, that.highlightColor);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(
            visibleUnderFollow,
            onHighlightedList,
            hiddenByRules,
            valueTierHighlighted,
            listMatch,
            highlightColor);
    }
}
