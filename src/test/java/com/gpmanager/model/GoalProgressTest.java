package com.gpmanager.model;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class GoalProgressTest
{
    @Test
    public void calculatesNetGoalAndReportsReachedMilestones()
    {
        GoalDefinition goal = new GoalDefinition(
            GoalDefinition.Kind.NET, GoalDefinition.Scope.SESSION, 200_000L,
            Arrays.asList(25, 50, 75, 100));

        GoalProgress progress = GoalProgress.calculate(goal, 125_000L, 3_600_000L, 0L, 0L);

        assertTrue(progress.isAvailable());
        assertEquals(125_000L, progress.getCurrentValue());
        assertEquals(62.5d, progress.getPercentComplete(), 0.0001d);
        assertFalse(progress.isReached());
        assertEquals(75_000L, progress.getRemainingValue());
        assertEquals(Arrays.asList(25, 50), progress.getReachedMilestones());
    }

    @Test
    public void derivesGpPerHourFromScopedNetAndActiveElapsedTime()
    {
        GoalDefinition goal = new GoalDefinition(
            GoalDefinition.Kind.GP_PER_HOUR, GoalDefinition.Scope.RUN, 300_000L, null);

        GoalProgress progress = GoalProgress.calculate(goal, 75_000L, 900_000L, 0L, 0L);

        assertTrue(progress.isAvailable());
        assertEquals(300_000L, progress.getCurrentValue());
        assertTrue(progress.isReached());
        assertEquals(100.0d, progress.getPercentComplete(), 0.0d);
        assertEquals(0L, progress.getRemainingValue());
    }

    @Test
    public void selectsKillsAndItemCountInputsByGoalKind()
    {
        GoalDefinition kills = new GoalDefinition(
            GoalDefinition.Kind.KILLS, GoalDefinition.Scope.TODAY, 10L, null);
        GoalDefinition items = new GoalDefinition(
            GoalDefinition.Kind.ITEM_COUNT, GoalDefinition.Scope.SESSION, 50L, 995, null);

        assertEquals(7L, GoalProgress.calculate(kills, 999L, 1L, 7L, 90L).getCurrentValue());
        assertEquals(90L, GoalProgress.calculate(items, 999L, 1L, 7L, 90L).getCurrentValue());
    }

    @Test
    public void clampsDisplayPercentAndAvoidsOverflowingGpPerHour()
    {
        GoalDefinition net = new GoalDefinition(GoalDefinition.Kind.NET,
            GoalDefinition.Scope.SESSION, 100L, null);
        GoalProgress negative = GoalProgress.calculate(net, -50L, 10L, 0L, 0L);
        assertEquals(0.0d, negative.getPercentComplete(), 0.0d);
        assertFalse(negative.isReached());
        assertEquals(150L, negative.getRemainingValue());

        GoalDefinition rate = new GoalDefinition(GoalDefinition.Kind.GP_PER_HOUR,
            GoalDefinition.Scope.SESSION, 100L, null);
        GoalProgress overflow = GoalProgress.calculate(rate, Long.MAX_VALUE, 1L, 0L, 0L);
        assertFalse(overflow.isAvailable());
        assertEquals("RATE_OVERFLOW", overflow.getUnavailableReason());
    }

    @Test
    public void unconfiguredGoalAndRateWithoutElapsedTimeAreUnavailable()
    {
        GoalProgress unconfigured = GoalProgress.calculate(new GoalDefinition(), 100L, 1L, 1L, 1L);
        assertFalse(unconfigured.isAvailable());
        assertEquals("GOAL_NOT_CONFIGURED", unconfigured.getUnavailableReason());

        GoalDefinition rate = new GoalDefinition(GoalDefinition.Kind.GP_PER_HOUR,
            GoalDefinition.Scope.SESSION, 100L, null);
        GoalProgress noTime = GoalProgress.calculate(rate, 100L, 0L, 0L, 0L);
        assertFalse(noTime.isAvailable());
        assertEquals("ELAPSED_TIME_UNAVAILABLE", noTime.getUnavailableReason());
    }
}
