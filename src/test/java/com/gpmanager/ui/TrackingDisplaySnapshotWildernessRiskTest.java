package com.gpmanager.ui;

import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TrackingDisplaySnapshotWildernessRiskTest
{
    @Test
    public void copyMethodsPreservePvPAndMeasuredRiskFields()
    {
        WildernessRiskCalculator.Result risk = WildernessRiskCalculator.calculate(
            Collections.singletonList(new WildernessRiskCalculator.ItemStack(995, 100L, 1L)),
            Collections.emptyList(), 0);
        TrackingDisplaySnapshot snapshot = TrackingDisplaySnapshot.from(
            null, "", "Live", null, 1_000L, 3_000L, 0L, null, null)
            .withWildernessRisk(true, risk)
            .withInteraction(InteractionContextModel.View.EMPTY)
            .withMinimumDisplayedLootValue(1L)
            .withRevealTiming(2_000L, 1_000L)
            .withCharacterIdle(true)
            .withBankingUiOpen(true)
            .withLootCoalescing(true)
            .withLootBatchLocked(true)
            .withSessionChrome(true, "Session", true, 3L, 4L, true)
            .withNeutralZoneActive(true)
            .withConfirmedProcessTitle("Fishing");

        assertTrue(snapshot.isPvpPossible());
        assertTrue(snapshot.hasKnownWildernessRisk());
        assertSame(risk, snapshot.getWildernessRisk());
    }

    @Test
    public void incompleteRiskIsRetainedAsExplicitUnknownWhilePvPStateIsKept()
    {
        TrackingDisplaySnapshot snapshot = TrackingDisplaySnapshot.from(
            null, "", "Live", null, 1_000L, 3_000L, 0L, null, null)
            .withWildernessRisk(true, WildernessRiskCalculator.calculate(
                Collections.singletonList(new WildernessRiskCalculator.ItemStack(995, 1L, null)),
                Collections.emptyList(), 0));

        assertTrue(snapshot.isPvpPossible());
        assertTrue(!snapshot.hasKnownWildernessRisk());
    }
}
