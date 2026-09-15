package com.gpmanager.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Profile-owned goal settings. This is presentation metadata only; it never
 * creates, changes, or revalues an accounting transaction.
 */
public class GoalDefinition
{
    private String id = UUID.randomUUID().toString();
    private Kind kind = Kind.NET;
    private Scope scope = Scope.SESSION;
    private long targetValue;
    /** Item id for ITEM_COUNT goals; -1 means no item has been selected yet. */
    private int itemId = -1;
    private List<Integer> milestonePercentages = new ArrayList<>();

    /** Gson and legacy profile default: an unconfigured session net goal. */
    public GoalDefinition()
    {
    }

    public GoalDefinition(Kind kind, Scope scope, long targetValue, List<Integer> milestonePercentages)
    {
        this(kind, scope, targetValue, -1, milestonePercentages);
    }

    public GoalDefinition(
        Kind kind,
        Scope scope,
        long targetValue,
        int itemId,
        List<Integer> milestonePercentages)
    {
        this.kind = kind == null ? Kind.NET : kind;
        this.scope = scope == null ? Scope.SESSION : scope;
        this.targetValue = targetValue;
        this.itemId = itemId;
        this.milestonePercentages = normalizeMilestones(milestonePercentages);
    }

    private GoalDefinition(String id, Kind kind, Scope scope, long targetValue, int itemId,
        List<Integer> milestonePercentages)
    {
        this.id = id;
        this.kind = kind == null ? Kind.NET : kind;
        this.scope = scope == null ? Scope.SESSION : scope;
        this.targetValue = targetValue;
        this.itemId = itemId;
        this.milestonePercentages = normalizeMilestones(milestonePercentages);
    }

    public String getId()
    {
        if (id == null || id.trim().isEmpty())
        {
            id = UUID.randomUUID().toString();
        }
        return id;
    }

    public void setId(String id)
    {
        this.id = id == null || id.trim().isEmpty() ? UUID.randomUUID().toString() : id.trim();
    }

    public GoalDefinition copy()
    {
        return new GoalDefinition(getId(), getKind(), getScope(), targetValue, itemId, milestonePercentages);
    }

    public Kind getKind()
    {
        return kind == null ? Kind.NET : kind;
    }

    public void setKind(Kind kind)
    {
        this.kind = kind == null ? Kind.NET : kind;
    }

    public Scope getScope()
    {
        return scope == null ? Scope.SESSION : scope;
    }

    public void setScope(Scope scope)
    {
        this.scope = scope == null ? Scope.SESSION : scope;
    }

    /** Target in GP, GP/hour, kills, or item count according to {@link #getKind()}. */
    public long getTargetValue()
    {
        return targetValue;
    }

    public void setTargetValue(long targetValue)
    {
        this.targetValue = targetValue;
    }

    public int getItemId()
    {
        return itemId;
    }

    public void setItemId(int itemId)
    {
        this.itemId = itemId;
    }

    /** Sorted, unique milestone thresholds in the inclusive range 1..100. */
    public List<Integer> getMilestonePercentages()
    {
        return Collections.unmodifiableList(normalizeMilestones(milestonePercentages));
    }

    public void setMilestonePercentages(List<Integer> milestonePercentages)
    {
        this.milestonePercentages = normalizeMilestones(milestonePercentages);
    }

    /**
     * An absent legacy goal is intentionally inactive. Item goals additionally
     * require an item selection; invalid editor drafts remain representable.
     */
    public boolean isConfigured()
    {
        return getTargetValue() > 0L && (getKind() != Kind.ITEM_COUNT || getItemId() >= 0);
    }

    private static List<Integer> normalizeMilestones(List<Integer> source)
    {
        TreeSet<Integer> sorted = new TreeSet<>();
        if (source != null)
        {
            for (Integer value : source)
            {
                if (value != null && value >= 1 && value <= 100)
                {
                    sorted.add(value);
                }
            }
        }
        return new ArrayList<>(sorted);
    }

    public enum Kind
    {
        NET,
        GP_PER_HOUR,
        KILLS,
        ITEM_COUNT
    }

    public enum Scope
    {
        SESSION,
        TODAY,
        RUN
    }
}
