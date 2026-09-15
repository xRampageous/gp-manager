package com.gpmanager.ui.bento;

import javax.annotation.Nullable;

/** Plugin-side facts for the Live read model (§13.3): thresholds, the live encounter, boundaries. */
public final class LiveContext
{
    public static final LiveContext NONE = new LiveContext(1_000_000L, null, null);

    public final long notableDropGp;
    @Nullable
    public final com.gpmanager.reward.RewardPresentationModel.EncounterSummary encounter;
    @Nullable
    public final BoundaryLog boundaries;

    public LiveContext(long notableDropGp,
        @Nullable com.gpmanager.reward.RewardPresentationModel.EncounterSummary encounter, @Nullable BoundaryLog boundaries)
    {
        this.notableDropGp = notableDropGp;
        this.encounter = encounter;
        this.boundaries = boundaries;
    }
}
