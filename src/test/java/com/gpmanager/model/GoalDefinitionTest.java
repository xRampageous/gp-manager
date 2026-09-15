package com.gpmanager.model;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class GoalDefinitionTest
{
    @Test
    public void legacyMissingFieldsResolveToAnInactiveSessionNetGoal()
    {
        GoalDefinition absent = new Gson().fromJson("{}", GoalDefinition.class);

        assertEquals(GoalDefinition.Kind.NET, absent.getKind());
        assertEquals(GoalDefinition.Scope.SESSION, absent.getScope());
        assertEquals(0L, absent.getTargetValue());
        assertEquals(-1, absent.getItemId());
        assertTrue(absent.getMilestonePercentages().isEmpty());
        assertFalse(absent.isConfigured());
        assertFalse(absent.getId().isEmpty());
    }

    @Test
    public void milestonesAreSortedUniqueBoundedAndCopySafe()
    {
        List<Integer> source = new ArrayList<>(Arrays.asList(50, 25, 50, 0, 101, null, 100));
        GoalDefinition goal = new GoalDefinition(
            GoalDefinition.Kind.NET, GoalDefinition.Scope.TODAY, 200_000L, source);
        source.add(75);

        assertEquals(Arrays.asList(25, 50, 100), goal.getMilestonePercentages());
        try
        {
            goal.getMilestonePercentages().add(75);
            fail("milestone view should be immutable");
        }
        catch (UnsupportedOperationException expected)
        {
            // Expected: callers cannot mutate persisted definition state.
        }
    }

    @Test
    public void itemCountGoalRequiresASelectedItem()
    {
        GoalDefinition draft = new GoalDefinition(
            GoalDefinition.Kind.ITEM_COUNT, GoalDefinition.Scope.RUN, 20L, -1, null);
        assertFalse(draft.isConfigured());
        draft.setItemId(4151);
        assertTrue(draft.isConfigured());
    }

    @Test
    public void copiedGoalKeepsStableProfileIdentity()
    {
        GoalDefinition goal = new GoalDefinition(
            GoalDefinition.Kind.KILLS, GoalDefinition.Scope.RUN, 10L, Arrays.asList(50, 100));
        GoalDefinition copy = goal.copy();

        assertEquals(goal.getId(), copy.getId());
        assertFalse(copy.getId().isEmpty());
    }
}
