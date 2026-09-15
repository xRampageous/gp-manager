package com.gpmanager.ui;

import com.gpmanager.model.ActionKind;
import com.gpmanager.model.KeyChestCatalogue;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.reward.CollectionStatus;
import com.gpmanager.reward.RewardItem;
import com.gpmanager.reward.RewardObservation;
import com.gpmanager.reward.RewardSourceKind;
import com.gpmanager.util.QuantityFormatter;
import java.util.Locale;
import javax.annotation.Nullable;

/**
 * Presentation-only HUD+ tray lifecycle/state tags. Never affects accounting.
 *
 * <p>States: Ground Loot, Received, skilling verbs (Chopped/…/Gathered/Made),
 * Deposit/Withdrew (bank), Used, Dropped/Destroyed (Used sourceName overrides), Lost, Mixed,
 * Charged, Stored, Pending Rewards, Claimed, Traded, Recovered.
 *
 * <p>Not tray states (use other surfaces): Equipped, Uncertain (Review),
 * Unpriced (item/hover), untradeable value config, GE tax, Ignored/filtered,
 * Paused/Idle/Waiting/Streak, Player Loot (same as Ground Loot→Received), Adjustments.
 */
public enum HudTrayState
{
    GROUND_LOOT("Ground Loot"),
    RECEIVED("Received"),
    CHEST_LOOT("Chest loot"),
    /** Bank ownership move — {@link #tagLine} paints Deposit or Withdrew. */
    BANKED("Deposit"),
    USED("Used"),
    LOST("Lost"),
    MIXED("Mixed"),
    CHARGED("Charged"),
    STORED("Stored"),
    PENDING_REWARDS("Pending Rewards"),
    CLAIMED("Claimed"),
    TRADED("Traded"),
    RECOVERED("Recovered"),
    /** Fallback when skilling activity is unknown — {@link #tagLine} may use a verb instead. */
    GATHERED("Gathered");

    private final String label;

    HudTrayState(String label)
    {
        this.label = label;
    }

    public String getLabel()
    {
        return label;
    }

    /**
     * Resolve priority (high → low): Claimed → Charged → Stored → Pending Rewards → Recovered
     * → Traded → Banked → Mixed → Lost/Used → skilling verb/Gathered → Ground Loot → Received.
     */
    public static HudTrayState resolve(@Nullable RewardObservation reward)
    {
        if (reward == null)
        {
            return MIXED;
        }
        if (KeyChestCatalogue.entryForChestMention(reward.getSourceName()) != null)
        {
            return CHEST_LOOT;
        }
        RewardSourceKind kind = reward.getSourceKind();
        if (kind == RewardSourceKind.CLAIMED)
        {
            return CLAIMED;
        }
        if (kind == RewardSourceKind.CHARGED)
        {
            return CHARGED;
        }
        if (kind == RewardSourceKind.STORED)
        {
            return STORED;
        }
        if (kind == RewardSourceKind.PENDING_REWARDS)
        {
            return PENDING_REWARDS;
        }
        if (kind == RewardSourceKind.RECOVERED)
        {
            return RECOVERED;
        }
        if (kind == RewardSourceKind.TRADED)
        {
            return TRADED;
        }
        if (kind == RewardSourceKind.BANKED)
        {
            return BANKED;
        }
        // B10: a card whose source carries an evidenced action verb (Drank/Ate/Decanted/
        // Supplies used) is a consume receipt, even when leftover stacks make it look Mixed.
        ActionKind action = actionFromSource(reward.getSourceName());
        if (action != null)
        {
            return action == ActionKind.DECANT ? MIXED : USED;
        }

        boolean anyLoss = false;
        boolean anyGain = false;
        for (RewardItem item : reward.getItems())
        {
            if (item == null)
            {
                continue;
            }
            if (item.isLoss())
            {
                anyLoss = true;
            }
            else
            {
                anyGain = true;
            }
        }
        if (anyLoss && anyGain)
        {
            return MIXED;
        }
        // Process spends (Burned/Offered/…) are SKILLING with loss stacks — paint
        // the skilling verb, not Used. Check before the generic loss → Used path.
        if (kind.isSkilling() && isInventoryConsumeActivity(reward.getSourceName()))
        {
            if (anyLoss && anyGain)
            {
                return MIXED;
            }
            return USED;
        }
        if (kind.isSkilling() && isProcessSpendActivity(reward.getSourceName()))
        {
            return GATHERED;
        }
        if (anyLoss || kind == RewardSourceKind.USED || kind == RewardSourceKind.LOST)
        {
            if (kind == RewardSourceKind.LOST || isLostSourceName(reward.getSourceName()))
            {
                return LOST;
            }
            if (isProcessSpendActivity(reward.getSourceName()))
            {
                return GATHERED;
            }
            return USED;
        }
        if (kind.isSkilling())
        {
            return GATHERED;
        }
        if (kind.isObservedLoot())
        {
            CollectionStatus status = reward.collectionStatus();
            // Unconfirmed NPC/chest drops stay Ground Loot. Any floor pickup
            // evidence (PARTIAL/COLLECTED) presents as Received.
            if (status == CollectionStatus.UNCONFIRMED)
            {
                return GROUND_LOOT;
            }
            return RECEIVED;
        }
        return RECEIVED;
    }

    /** Tray state label only — kill/reward batch counts belong on the HUD+ header (e.g. Goblin ×17). */
    public static String tagLine(@Nullable RewardObservation reward)
    {
        HudTrayState state = resolve(reward);
        ActionKind action = reward == null ? null : actionFromSource(reward.getSourceName());
        if (action != null)
        {
            return action.completedVerb();
        }
        String tag = state.getLabel();
        if (state == MIXED && reward != null && reward.getSourceKind() != null
            && reward.getSourceKind().isSkilling())
        {
            // A paired process card is positive evidence of the skill's own verb
            // (Cooked / Fletched / Smithed). "Mixed" is the honest verb only for
            // Herblore or an unknown process. Cooking reports the actual outcome.
            String verb = pairedProcessVerb(reward);
            if (!verb.isEmpty())
            {
                return verb;
            }
        }
        if (state == BANKED && reward != null)
        {
            tag = bankTransferTag(reward.getSourceName());
        }
        else if (state == GATHERED && reward != null)
        {
            String source = reward.getSourceName();
            if (source != null && "Session".equalsIgnoreCase(source.trim()))
            {
                return "Session";
            }
            // Drink/Eat/Sip are inventory consumes — never invent Sipped:/Drinked: verbs.
            if (isInventoryConsumeActivity(source))
            {
                return USED.getLabel();
            }
            tag = skillingVerb(source);
        }
        else if (state == USED && reward != null)
        {
            String source = reward.getSourceName();
            if (isDroppedSourceName(source))
            {
                tag = "Dropped";
            }
            else if (isDestroyedSourceName(source))
            {
                tag = "Destroyed";
            }
            else if (isInventoryConsumeActivity(source))
            {
                tag = USED.getLabel();
            }
        }
        return tag;
    }

    /**
     * B10 tray sources that carry an evidenced completed verb. Bury/Offer/Scatter keep
     * their XP-confirmed skilling wording (Buried/Offered) and "Mixed" stays the
     * paired-process state, so those are not treated as action sources here.
     */
    @Nullable
    public static ActionKind actionFromSource(@Nullable String source)
    {
        ActionKind kind = ActionKind.fromCompletedVerb(source);
        if (kind == null)
        {
            return null;
        }
        switch (kind)
        {
            case BURY:
            case OFFER:
            case SCATTER:
            case MIX:
            case COOK:
            case BURN:
                return null;
            default:
                return kind;
        }
    }

    /** Past-tense verb for a paired (−in +out) process card, or "" to keep Mixed. */
    static String pairedProcessVerb(RewardObservation reward)
    {
        String source = reward.getSourceName() == null ? "" : reward.getSourceName().trim();
        String lower = source.toLowerCase(Locale.ROOT);
        if (lower.isEmpty() || "herblore".equals(lower) || "processing".equals(lower)
            || "skilling".equals(lower) || "made".equals(lower))
        {
            return "";
        }
        if ("cooking".equals(lower))
        {
            boolean anyGain = false;
            boolean allBurnt = true;
            for (RewardItem item : reward.getItems())
            {
                if (item == null || item.isLoss())
                {
                    continue;
                }
                anyGain = true;
                String name = item.getItemName() == null ? "" : item.getItemName().trim().toLowerCase(Locale.ROOT);
                allBurnt &= name.startsWith("burnt");
            }
            return anyGain && allBurnt ? "Burnt" : "Cooked";
        }
        String verb = skillingVerb(source);
        return GATHERED.getLabel().equals(verb) ? "" : verb;
    }

    /** Deposit vs Withdrew from bank flash sourceName (legacy "Banked" → Deposit). */
    static String bankTransferTag(@Nullable String sourceName)
    {
        if (sourceName == null || sourceName.trim().isEmpty())
        {
            return "Deposit";
        }
        String lower = sourceName.trim().toLowerCase(Locale.ROOT);
        if (lower.contains("withdrew") || lower.contains("withdraw"))
        {
            return "Withdrew";
        }
        return "Deposit";
    }

    /** Menu/consume labels that must not become skilling past-tense tray verbs. */
    public static boolean isInventoryConsumeActivity(@Nullable String activityName)
    {
        if (activityName == null || activityName.trim().isEmpty())
        {
            return false;
        }
        String lower = activityName.trim().toLowerCase(Locale.ROOT);
        return "drink".equals(lower)
            || "drinked".equals(lower)
            || "sip".equals(lower)
            || "sipped".equals(lower)
            || "eat".equals(lower)
            || "eaten".equals(lower)
            || "empty".equals(lower)
            || "break".equals(lower)
            || "release".equals(lower);
    }

    /** Activities whose inventory spends paint Burned/Cooked/Offered (not Used). */
    public static boolean isProcessSpendActivity(@Nullable String activityName)
    {
        if (activityName == null || activityName.trim().isEmpty())
        {
            return false;
        }
        String lower = activityName.trim().toLowerCase(Locale.ROOT);
        return "firemaking".equals(lower)
            || "cooking".equals(lower)
            || "prayer".equals(lower)
            || "buried".equals(lower)
            || "scattered".equals(lower)
            || "offered".equals(lower)
            || "magic".equals(lower)
            || "alchemy".equals(lower)
            || "alched".equals(lower)
            || "construction".equals(lower)
            || "herblore".equals(lower)
            || "smithing".equals(lower)
            || "smelting".equals(lower)
            || "fletching".equals(lower)
            || "crafting".equals(lower);
    }

    /**
     * Safe to paint process verbs from activity alone (no menu intent). Excludes
     * Prayer/Magic so drinking a prayer potion under a Prayer hint stays Used.
     */
    public static boolean isImmediateProcessSpendActivity(@Nullable String activityName)
    {
        if (activityName == null || activityName.trim().isEmpty())
        {
            return false;
        }
        String lower = activityName.trim().toLowerCase(Locale.ROOT);
        return "firemaking".equals(lower)
            || "cooking".equals(lower)
            || "construction".equals(lower)
            || "herblore".equals(lower)
            || "smithing".equals(lower)
            || "smelting".equals(lower)
            || "fletching".equals(lower)
            || "crafting".equals(lower);
    }

    /** Past-tense activity verb, or Gathered/Made fallbacks. One style for every tray tag: no colon. */
    public static String skillingVerb(@Nullable String activityName)
    {
        if (activityName == null || activityName.trim().isEmpty())
        {
            return GATHERED.getLabel();
        }
        String name = activityName.trim();
        String lower = name.toLowerCase(Locale.ROOT);
        if ("made".equals(lower) || "processing".equals(lower))
        {
            return "Made";
        }
        if ("woodcutting".equals(lower))
        {
            return "Chopped";
        }
        if ("mining".equals(lower))
        {
            return "Mined";
        }
        if ("fishing".equals(lower))
        {
            return "Fished";
        }
        if ("farming".equals(lower))
        {
            return "Harvested";
        }
        if ("hunter".equals(lower))
        {
            return "Caught";
        }
        if ("cooking".equals(lower))
        {
            return "Cooked";
        }
        if ("smithing".equals(lower))
        {
            return "Smithed";
        }
        if ("smelting".equals(lower))
        {
            return "Smelted";
        }
        if ("spinning".equals(lower))
        {
            return "Spun";
        }
        if ("stringing".equals(lower))
        {
            return "Strung";
        }
        if ("chiselling".equals(lower) || "chiseling".equals(lower))
        {
            return "Chiseled";
        }
        if ("cutting".equals(lower))
        {
            return "Cut";
        }
        if ("clean".equals(lower) || "cleaning".equals(lower))
        {
            return "Cleaned";
        }
        if ("enchant".equals(lower) || "enchanting".equals(lower))
        {
            return "Enchanted";
        }
        if ("glassblowing".equals(lower))
        {
            return "Blown";
        }
        if ("grinding".equals(lower))
        {
            return "Ground";
        }
        if ("weaving".equals(lower))
        {
            return "Woven";
        }
        if ("casting".equals(lower))
        {
            return "Cast";
        }
        if ("plank".equals(lower) || "planking".equals(lower))
        {
            return "Planked";
        }
        if ("crafting".equals(lower) || "runecraft".equals(lower) || "runecrafting".equals(lower))
        {
            return "Crafted";
        }
        if ("fletching".equals(lower))
        {
            return "Fletched";
        }
        if ("herblore".equals(lower))
        {
            return "Brewed";
        }
        if ("construction".equals(lower))
        {
            return "Built";
        }
        if ("firemaking".equals(lower))
        {
            return "Burned";
        }
        if ("prayer".equals(lower) || "offered".equals(lower))
        {
            return "Offered";
        }
        if ("buried".equals(lower))
        {
            return "Buried";
        }
        if ("scattered".equals(lower))
        {
            return "Scattered";
        }
        if ("thieving".equals(lower))
        {
            return "Stolen";
        }
        if ("magic".equals(lower) || "alched".equals(lower) || "alchemy".equals(lower))
        {
            return "Alched";
        }
        if ("gathered".equals(lower) || "skilling".equals(lower) || "agility".equals(lower))
        {
            return GATHERED.getLabel();
        }
        return GATHERED.getLabel();
    }

    public static String itemDetailLine(@Nullable RewardItem item)
    {
        if (item == null)
        {
            return "";
        }
        String name = item.compactLabel();
        if (!item.isValueKnown())
        {
            return name + " · unpriced";
        }
        long qty = Math.abs(item.getQuantity());
        long total = item.getRecordedValue();
        long unit = qty <= 0L ? 0L : Math.abs(total) / qty;
        String provenance = item.getPriceSource() == ItemPriceSource.MANUAL_OVERRIDE
            ? "manual override"
            : QuantityFormatter.compactGp(unit) + " ea";
        String totalText = (total > 0L ? "+" : "") + QuantityFormatter.compactGp(total);
        return name + " · " + provenance + " · " + totalText;
    }

    public static boolean isGroundUnconfirmed(@Nullable RewardObservation reward)
    {
        return reward != null
            && reward.getSourceKind().isObservedLoot()
            && reward.collectionStatus() == CollectionStatus.UNCONFIRMED;
    }

    /** Settled trays Always Expanded keeps when banking (not Ground Loot). */
    public static boolean isPreservedOnAlwaysExpandedBank(@Nullable RewardObservation reward)
    {
        if (reward == null || isGroundUnconfirmed(reward))
        {
            return false;
        }
        RewardSourceKind kind = reward.getSourceKind();
        if (kind.isSkilling() || kind.isPeerPresentationFlash())
        {
            return true;
        }
        HudTrayState state = resolve(reward);
        return state == RECEIVED
            || state == USED
            || state == LOST
            || state == MIXED
            || state == GATHERED;
    }

    private static boolean isLostSourceName(@Nullable String source)
    {
        if (source == null || source.isEmpty())
        {
            return false;
        }
        String lower = source.toLowerCase(Locale.ROOT);
        return lower.contains("death")
            || "lost".equals(lower)
            || lower.contains("pk fee")
            || "pk_fee".equals(lower)
            || "pk_death_loss".equals(lower);
    }

    private static boolean isDroppedSourceName(@Nullable String source)
    {
        return source != null && "dropped".equalsIgnoreCase(source.trim());
    }

    private static boolean isDestroyedSourceName(@Nullable String source)
    {
        return source != null && "destroyed".equalsIgnoreCase(source.trim());
    }
}
