package com.gpmanager.engine;

/**
 * Grave reclaim / Death's Office / boss IRS fee math.
 * Fees book as Lost; items returned as Recovered — never invent Recovered for
 * permanently deleted / timer-expired items.
 */
public final class DeathReclaimFees
{
    public static final long GRAVE_FREE_BELOW = 100_000L;
    public static final long GRAVE_FEE_1K = 1_000L;
    public static final long GRAVE_FEE_10K = 10_000L;
    public static final long GRAVE_FEE_100K = 100_000L;
    public static final long GRAVE_FEE_CAP = 500_000L;
    public static final long OFFICE_THRESHOLD = 100_000L;

    private DeathReclaimFees()
    {
    }

    /**
     * Tiered gravestone reclaim fee for items being reclaimed.
     * Bands: &lt;100k free; then 1k / 10k / 100k style bands capped at 500k.
     * Ironman pays 50% (rounded down).
     */
    public static long graveReclaimFee(long reclaimedItemValue, boolean ironman)
    {
        if (reclaimedItemValue <= 0L)
        {
            return 0L;
        }
        long fee;
        if (reclaimedItemValue < GRAVE_FREE_BELOW)
        {
            fee = 0L;
        }
        else if (reclaimedItemValue < 1_000_000L)
        {
            fee = GRAVE_FEE_1K;
        }
        else if (reclaimedItemValue < 10_000_000L)
        {
            fee = GRAVE_FEE_10K;
        }
        else
        {
            fee = GRAVE_FEE_100K;
        }
        fee = Math.min(GRAVE_FEE_CAP, fee);
        if (ironman)
        {
            fee = fee / 2L;
        }
        return fee;
    }

    /**
     * Death's Office: 5% (2.5% iron) for items ≥100k each — pass the sum of
     * eligible item values. Fee may come from Death's Coffer or bank.
     */
    public static long deathsOfficeFee(long eligibleItemValueSum, boolean ironman)
    {
        if (eligibleItemValueSum < OFFICE_THRESHOLD)
        {
            return 0L;
        }
        long bps = ironman ? 250L : 500L; // 2.5% / 5%
        return (eligibleItemValueSum * bps) / 10_000L;
    }

    /** Boss Item Retrieval Service fee when known; 0 when unknown (honest). */
    public static long bossIrsFee(long feeObservedCoins)
    {
        return Math.max(0L, feeObservedCoins);
    }

    public static String graveFeeWhy(boolean ironman)
    {
        return ironman
            ? "Grave reclaim fee (ironman 50% off)"
            : "Grave reclaim fee (tiered)";
    }

    public static String deathsOfficeWhy(boolean ironman)
    {
        return ironman
            ? "Death's Office fee 2.5%"
            : "Death's Office fee 5%";
    }

    public static String irsWhy()
    {
        return "Boss Item Retrieval Service fee";
    }
}
