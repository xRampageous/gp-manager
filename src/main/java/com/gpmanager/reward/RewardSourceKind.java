package com.gpmanager.reward;

/**
 * How a reward observation was obtained. Display-only — never mutates the ledger.
 */
public enum RewardSourceKind
{
    NPC_LOOT("NPC loot"),
    SERVER_NPC_LOOT("NPC loot"),
    LOOT_TRACKER("Loot Tracker"),
    PLAYER_LOOT("Player loot"),
    RECENT_PICKUPS("Recent pickups"),
    SKILLING("Skilling"),
    INVENTORY_CONFIRMED("Collected"),
    /** Presentation-only bank deposit/withdraw flash — never counted as profit. */
    BANKED("Deposit"),
    /** Presentation-only charge flash — never invents Net until calibrated. */
    CHARGED("Charged"),
    /** Presentation-only container flash — bag fill is not Lost. */
    STORED("Stored"),
    /** Presentation-only reward-chest / clue / raid UI — waiting to claim (not Ground Loot). */
    PENDING_REWARDS("Pending Rewards"),
    /** Presentation-only reward-chest claim — not the same as Deposit/Withdrew. */
    CLAIMED("Claimed"),
    /** Presentation-only GE/shop/player trade flash — ownership-neutral. */
    TRADED("Traded"),
    /** Presentation-only death-storage / reclaim flash. */
    RECOVERED("Recovered"),
    /** Presentation-only supply consumption. */
    USED("Used"),
    /** Presentation-only death / fee loss. */
    LOST("Lost"),
    UNKNOWN("Reward");

    private final String label;

    RewardSourceKind(String label)
    {
        this.label = label;
    }

    public String getLabel()
    {
        return label;
    }

    public boolean isSkilling()
    {
        return this == SKILLING;
    }

    /** Bounded inventory receipts without a matched encounter — expandable tray. */
    public boolean isRecentPickup()
    {
        return this == RECENT_PICKUPS;
    }

    public boolean isObservedLoot()
    {
        return this == NPC_LOOT
            || this == SERVER_NPC_LOOT
            || this == LOOT_TRACKER
            || this == PLAYER_LOOT;
    }

    /** Explicit peer/transfer presentation flashes preserved on Always Expanded bank open. */
    public boolean isPeerPresentationFlash()
    {
        return this == BANKED
            || this == CHARGED
            || this == STORED
            || this == PENDING_REWARDS
            || this == CLAIMED
            || this == TRADED
            || this == RECOVERED
            || this == USED
            || this == LOST;
    }

    /** Reward-interface / chest / clue loot held before invent or bank claim. */
    public boolean isPendingRewards()
    {
        return this == PENDING_REWARDS;
    }

    /** Sources that may show the multi-item Keep-expanded tray. */
    public boolean supportsExpandedTray()
    {
        return isObservedLoot()
            || isRecentPickup()
            || this == INVENTORY_CONFIRMED
            || this == SKILLING
            || isPeerPresentationFlash();
    }
}
