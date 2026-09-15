package com.gpmanager.engine;

import com.gpmanager.engine.evidence.UtilityContainerCatalogue;
import java.util.Locale;
import javax.annotation.Nullable;

/**
 * Live producer helpers — menu/region/chat → engine APIs without double-booking.
 * Call sites live in {@code GpManagerPlugin}; unit-testable offline.
 */
public final class LiveProducerBridge
{
    private LiveProducerBridge()
    {
    }

    /** Death reclaim / Office / IRS fee chat or observed coins. */
    @Nullable
    public static com.gpmanager.model.ProfitTransaction tryBookDeathFee(
        GpManagerEngine engine,
        String message,
        long observedFeeCoins,
        boolean ironman,
        long now)
    {
        if (engine == null)
        {
            return null;
        }
        String lower = norm(message);
        if (lower == null && observedFeeCoins <= 0L)
        {
            return null;
        }
        long fee = 0L;
        String why = null;
        if (observedFeeCoins > 0L)
        {
            fee = DeathReclaimFees.bossIrsFee(observedFeeCoins);
            why = DeathReclaimFees.irsWhy();
        }
        else if (lower != null && (lower.contains("death's office") || lower.contains("deaths office")))
        {
            // Fee amount must come from invent evidence; chat alone is not enough for %.
            return null;
        }
        else if (lower != null && (lower.contains("reclaim") || lower.contains("gravestone")
            || lower.contains("pay a fee")))
        {
            // Without item-value evidence keep fee at observed 0 — caller should pass coins.
            return null;
        }
        if (fee <= 0L)
        {
            return null;
        }
        return engine.bookDeathReclaimFee(fee, why, now);
    }

    public static void observeWealthFromMenu(
        @Nullable java.util.function.BiConsumer<String, String> observe,
        String option,
        String target)
    {
        if (observe == null)
        {
            return;
        }
        String joined = ((option == null ? "" : option) + " " + (target == null ? "" : target))
            .toLowerCase(Locale.ROOT);
        if (joined.contains("stash") || joined.contains("clue stash"))
        {
            observe.accept("stash", "STASH interaction");
        }
        else if (joined.contains("costume room") || joined.contains("fancy dress box")
            || joined.contains("armour case") || joined.contains("cape rack"))
        {
            observe.accept("poh", "PoH costume storage");
        }
        else if (joined.contains("group storage") || joined.contains("shared storage")
            || joined.contains("gim"))
        {
            observe.accept("gim", "GIM shared storage");
        }
        else if (joined.contains("raid") && (joined.contains("storage") || joined.contains("private storage")
            || joined.contains("shared chest")))
        {
            observe.accept("raid_bags", "Raid storage");
        }
        else if (joined.contains("coffer") || joined.contains("nightmare zone")
            || joined.contains("blast furnace"))
        {
            observe.accept("coffers", "Coffer / minigame storage");
        }
        else if (joined.contains("seed vault") || joined.contains("tool leprechaun")
            || joined.contains("leprechaun"))
        {
            observe.accept("coffers", "Seed vault / leprechaun");
        }
    }

    public static boolean looksLikeLootKeyGain(String itemName)
    {
        String n = norm(itemName);
        return n != null && (n.equals("loot key") || n.contains("loot key"));
    }

    public static boolean looksLikePlankSackAmbiguous(String option, String target)
    {
        String joined = ((option == null ? "" : option) + " " + (target == null ? "" : target))
            .toLowerCase(Locale.ROOT);
        return joined.contains("plank sack");
    }

    public static boolean isPendingRewardUiOpen(@Nullable String interfaceTitleOrSource)
    {
        String n = norm(interfaceTitleOrSource);
        if (n == null)
        {
            return false;
        }
        return RewardChestCatalogue.isPendingRewardName(n)
            || n.contains("reward")
            || n.contains("chest")
            || LootKeyLifecycle.looksLikeLootChestUi(n);
    }

    @Nullable
    private static String norm(String value)
    {
        if (value == null)
        {
            return null;
        }
        String t = value.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() ? null : t;
    }
}
