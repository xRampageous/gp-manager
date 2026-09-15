package com.gpmanager.engine;

import com.gpmanager.model.ActionKind;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.TransactionType;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Derives the observed {@link ActionKind} for a settled inventory change from positive
 * evidence only: a matched menu verb plus the settled flow shape. Never upgrades weak
 * evidence into a confident verb — a dose leftover without a matched Drink click is
 * {@link ActionKind#SUPPLIES}, not {@link ActionKind#DRINK}.
 *
 * <p>Presentation evidence only. Accounting type, valuation and counted state are decided
 * elsewhere and never read this class.
 */
public final class ActionEvidence
{
    private static final Pattern DOSE_SUFFIX = Pattern.compile("\\((\\d)\\)\\s*$");

    private ActionEvidence()
    {
    }

    /**
     * Menu verb → intent kind. {@code option} is the normalized (lower-case) menu option.
     * Returns null for options that do not evidence a specific action (Empty, Break, Release).
     */
    @Nullable
    public static ActionKind fromMenuOption(@Nullable String option)
    {
        if (option == null || option.isEmpty())
        {
            return null;
        }
        String lower = option.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("drink"))
        {
            return ActionKind.DRINK;
        }
        if (lower.startsWith("eat"))
        {
            return ActionKind.EAT;
        }
        if (lower.contains("bury"))
        {
            return ActionKind.BURY;
        }
        if (lower.contains("scatter"))
        {
            return ActionKind.SCATTER;
        }
        if (lower.startsWith("offer"))
        {
            return ActionKind.OFFER;
        }
        if (lower.startsWith("cast"))
        {
            return ActionKind.CAST;
        }
        return null;
    }

    /**
     * Final stamp for a booked change.
     *
     * @param matchedIntent the menu verb whose intent matched these flows, or null when the
     *                      intent was absent, stale or matched nothing
     * @param flows         settled flows of the transaction
     * @param type          booked accounting type (unchanged by this call)
     * @param activityName  booked activity name, used only to recognise Herblore production
     */
    @Nullable
    public static ActionKind resolve(
        @Nullable ActionKind matchedIntent,
        @Nullable List<ItemFlow> flows,
        @Nullable TransactionType type,
        @Nullable String activityName)
    {
        if (flows == null || flows.isEmpty())
        {
            return null;
        }
        // A decant shape can also appear in a bank transfer or trade. Only the
        // otherwise-unclassified mixed inventory row is evidence of a player action.
        if (type == TransactionType.UNCERTAIN && isDecant(flows))
        {
            return ActionKind.DECANT;
        }
        if (type == TransactionType.PROCESSING
            && isHerbloreActivity(activityName)
            && isHerbloreMix(flows))
        {
            return ActionKind.MIX;
        }
        if (type == TransactionType.PROCESSING
            && isCookingActivity(activityName)
            && isCookingFlow(flows))
        {
            // Use the actual outcome: every output burnt → Burnt, otherwise Cooked.
            return allGainsBurnt(flows) ? ActionKind.BURN : ActionKind.COOK;
        }
        // Rune/ammo spends are only recognised on cost-shaped rows: a bank deposit or
        // death loss of runes is a transfer/death, not casting.
        boolean spendShaped = type == TransactionType.CONSUMPTION
            || type == TransactionType.PK_SUPPLY_COST;
        boolean lossOnly = hasLoss(flows) && !hasGain(flows);
        boolean gainOnly = hasGain(flows) && !hasLoss(flows);
        if (spendShaped && lossOnly && allMatch(flows, ActionEvidence::isRuneName))
        {
            return ActionKind.CAST;
        }
        if (spendShaped && lossOnly && allMatch(flows, ActionEvidence::isAmmunitionName))
        {
            return ActionKind.FIRE;
        }
        // Recovery is passive and cannot be inferred from an ammo-only gain: that may
        // be a ground pickup. Keep it routine only when upstream supplied explicit
        // recovery evidence as the matched intent.
        if (matchedIntent == ActionKind.RECOVER_AMMO
            && gainOnly
            && allMatch(flows, ActionEvidence::isAmmunitionName)
            && type != TransactionType.LOOT
            && type != TransactionType.PK_LOOT
            && type != TransactionType.TRADE
            && type != TransactionType.TRANSFER)
        {
            return ActionKind.RECOVER_AMMO;
        }
        boolean doseStep = GpManagerEngine.isDoseOrPartialConsumeDelta(flows);
        if (matchedIntent != null && hasLoss(flows))
        {
            switch (matchedIntent)
            {
                case DRINK:
                    // Every loss must be a dose/vessel of one potion; a coalesced shark or
                    // bones under the same click is not "Drank" — fall through to Supplies.
                    return lossesAreSinglePotionFamily(flows) ? ActionKind.DRINK : ActionKind.SUPPLIES;
                case EAT:
                    return lossesAreOneItemFamily(flows) && !lossesContainDosedPotion(flows)
                        ? ActionKind.EAT
                        : ActionKind.SUPPLIES;
                case BURY:
                case SCATTER:
                case OFFER:
                    return lossesAreOneItemFamily(flows) && !lossesContainDosedPotion(flows)
                        ? matchedIntent
                        : ActionKind.SUPPLIES;
                case CAST:
                    // A Cast click without a rune-only loss is not evidence runes were used.
                    return doseStep ? ActionKind.SUPPLIES : null;
                default:
                    return matchedIntent;
            }
        }
        if (type == TransactionType.CONSUMPTION && doseStep)
        {
            // Consumption is known from the leftover shape; the verb is not.
            return ActionKind.SUPPLIES;
        }
        return null;
    }

    /**
     * Doses of one potion redistributed with no dose lost: e.g. −(3) −(1) +(4), or
     * −(4) +(2) +(2). Requires every flow to be a dosed form of the same base name.
     */
    static boolean isDecant(List<ItemFlow> flows)
    {
        if (flows == null || flows.size() < 2)
        {
            return false;
        }
        String base = null;
        long doseBalance = 0L;
        boolean anyLoss = false;
        boolean anyGain = false;
        for (ItemFlow flow : flows)
        {
            if (flow == null || flow.getQuantityDelta() == 0L)
            {
                continue;
            }
            String name = flow.getItemName() == null ? "" : flow.getItemName().trim().toLowerCase(Locale.ROOT);
            int dose = trailingDose(name);
            if (dose <= 0)
            {
                // Combining doses frees a vessel; an emptied vial/jug gain is part of the shape.
                if (flow.getQuantityDelta() > 0L && isEmptyVesselName(name))
                {
                    continue;
                }
                return false;
            }
            String stem = DOSE_SUFFIX.matcher(name).replaceFirst("").trim();
            if (stem.isEmpty())
            {
                return false;
            }
            if (base == null)
            {
                base = stem;
            }
            else if (!base.equals(stem))
            {
                return false;
            }
            doseBalance += (long) dose * flow.getQuantityDelta();
            anyLoss |= flow.getQuantityDelta() < 0L;
            anyGain |= flow.getQuantityDelta() > 0L;
        }
        return anyLoss && anyGain && doseBalance == 0L;
    }

    /** Emptied containers left behind by dose changes (mirrors the engine's vessel list). */
    static boolean isEmptyVesselName(@Nullable String lowerName)
    {
        if (lowerName == null)
        {
            return false;
        }
        return lowerName.equals("vial")
            || lowerName.equals("empty vial")
            || lowerName.startsWith("empty ")
            || lowerName.equals("jug")
            || lowerName.equals("empty jug")
            || lowerName.equals("beer glass")
            || lowerName.equals("empty cup");
    }

    /** Potion base name without its dose suffix, for receipts: "Prayer potion(4)" → "Prayer potion". */
    public static String doseBaseName(@Nullable String itemName)
    {
        if (itemName == null)
        {
            return "";
        }
        return DOSE_SUFFIX.matcher(itemName.trim()).replaceFirst("").trim();
    }

    /** Dose count parsed from a trailing "(N)" suffix, or −1 when absent. */
    public static int trailingDose(@Nullable String itemName)
    {
        if (itemName == null)
        {
            return -1;
        }
        Matcher matcher = DOSE_SUFFIX.matcher(itemName.trim());
        if (!matcher.find())
        {
            return -1;
        }
        try
        {
            return Integer.parseInt(matcher.group(1));
        }
        catch (NumberFormatException ignored)
        {
            return -1;
        }
    }

    /**
     * Doses consumed by a dose-step change (4→3 = 1, 1→vial = 1), or 0 when the flows are
     * not a single-potion dose step. Uses the net dose balance across the same base name.
     */
    public static int dosesConsumed(@Nullable List<ItemFlow> flows)
    {
        if (flows == null || flows.isEmpty())
        {
            return 0;
        }
        Map<String, Long> balance = new HashMap<>();
        for (ItemFlow flow : flows)
        {
            if (flow == null)
            {
                continue;
            }
            String name = flow.getItemName() == null ? "" : flow.getItemName().trim().toLowerCase(Locale.ROOT);
            int dose = trailingDose(name);
            if (dose <= 0)
            {
                continue;
            }
            String stem = DOSE_SUFFIX.matcher(name).replaceFirst("").trim();
            balance.merge(stem, (long) dose * flow.getQuantityDelta(), Long::sum);
        }
        if (balance.size() != 1)
        {
            return 0;
        }
        long net = balance.values().iterator().next();
        return net < 0L && net >= -Integer.MAX_VALUE ? (int) -net : 0;
    }

    static boolean isRuneName(@Nullable String itemName)
    {
        if (itemName == null)
        {
            return false;
        }
        String lower = itemName.trim().toLowerCase(Locale.ROOT);
        return lower.endsWith(" rune");
    }

    static boolean isAmmunitionName(@Nullable String itemName)
    {
        if (itemName == null)
        {
            return false;
        }
        String lower = itemName.trim().toLowerCase(Locale.ROOT);
        if (lower.endsWith(" arrow") || lower.endsWith(" arrows")
            || lower.endsWith(" bolt") || lower.endsWith(" bolts")
            || lower.endsWith(" bolts (e)") || lower.endsWith(" bolt (e)")
            || lower.endsWith(" dart") || lower.endsWith(" darts")
            || lower.endsWith(" javelin") || lower.endsWith(" javelins")
            || lower.endsWith(" knife") || lower.endsWith(" knives")
            || lower.endsWith(" thrownaxe") || lower.endsWith(" throwing axe")
            || lower.endsWith(" chinchompa") || lower.equals("chinchompa")
            || lower.endsWith("chinchompa"))
        {
            // Tips/heads/shafts are fletching supplies, not ammunition.
            return !lower.contains("arrowtip") && !lower.contains("bolt tip")
                && !lower.contains("dart tip") && !lower.contains("javelin head")
                && !lower.contains("arrow shaft");
        }
        return false;
    }

    /** All losses are dosed forms (or the emptied vessel) of one potion base name. */
    private static boolean lossesAreSinglePotionFamily(List<ItemFlow> flows)
    {
        String base = null;
        boolean any = false;
        for (ItemFlow flow : flows)
        {
            if (flow == null || flow.getQuantityDelta() >= 0L)
            {
                continue;
            }
            String name = flow.getItemName() == null ? "" : flow.getItemName().trim().toLowerCase(Locale.ROOT);
            if (trailingDose(name) <= 0)
            {
                return false;
            }
            String stem = DOSE_SUFFIX.matcher(name).replaceFirst("").trim();
            if (base == null)
            {
                base = stem;
            }
            else if (!base.equals(stem))
            {
                return false;
            }
            any = true;
        }
        return any;
    }

    /** All losses belong to one exact item family; a matched click cannot name mixed costs. */
    private static boolean lossesAreOneItemFamily(List<ItemFlow> flows)
    {
        String family = null;
        boolean any = false;
        for (ItemFlow flow : flows)
        {
            if (flow == null || flow.getQuantityDelta() >= 0L)
            {
                continue;
            }
            String name = flow.getItemName() == null
                ? ""
                : flow.getItemName().trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty())
            {
                return false;
            }
            if (family == null)
            {
                family = name;
            }
            else if (!family.equals(name))
            {
                return false;
            }
            any = true;
        }
        return any;
    }

    private static boolean lossesContainDosedPotion(List<ItemFlow> flows)
    {
        for (ItemFlow flow : flows)
        {
            if (flow != null && flow.getQuantityDelta() < 0L && trailingDose(flow.getItemName()) > 0)
            {
                return true;
            }
        }
        return false;
    }

    /** Every Herblore input is ingredient-shaped and every output is a dose potion. */
    private static boolean isHerbloreMix(List<ItemFlow> flows)
    {
        boolean hasInput = false;
        boolean hasOutput = false;
        for (ItemFlow flow : flows)
        {
            if (flow == null || flow.getQuantityDelta() == 0L)
            {
                continue;
            }
            String name = flow.getItemName() == null
                ? ""
                : flow.getItemName().trim().toLowerCase(Locale.ROOT);
            if (flow.getQuantityDelta() < 0L)
            {
                if (!isHerbloreIngredient(name))
                {
                    return false;
                }
                hasInput = true;
            }
            else
            {
                if (trailingDose(name) <= 0)
                {
                    return false;
                }
                hasOutput = true;
            }
        }
        return hasInput && hasOutput;
    }

    private static boolean isHerbloreIngredient(String lowerName)
    {
        if (lowerName.isEmpty() || lowerName.startsWith("grimy "))
        {
            return false;
        }
        if ("vial".equals(lowerName)
            || "vial of water".equals(lowerName)
            || "coconut milk".equals(lowerName)
            || "marrentill".equals(lowerName)
            || "tarromin".equals(lowerName)
            || "harralander".equals(lowerName)
            || "toadflax".equals(lowerName)
            || "kwuarm".equals(lowerName)
            || "snapdragon".equals(lowerName)
            || "cadantine".equals(lowerName)
            || "lantadyme".equals(lowerName)
            || "torstol".equals(lowerName)
            || "snape grass".equals(lowerName)
            || "eye of newt".equals(lowerName)
            || "wine of zamorak".equals(lowerName)
            || "potato cactus".equals(lowerName)
            || "red spiders' eggs".equals(lowerName)
            || "red spider's eggs".equals(lowerName)
            || "ground mud rune".equals(lowerName)
            || "blue dragon scale".equals(lowerName))
        {
            return true;
        }
        if (lowerName.endsWith(" weed")
            || lowerName.endsWith(" leaf")
            || lowerName.endsWith(" grass")
            || lowerName.endsWith(" root")
            || lowerName.endsWith(" dust")
            || lowerName.endsWith(" berries")
            || lowerName.endsWith(" berry")
            || lowerName.endsWith(" horn")
            || lowerName.endsWith(" fungus")
            || lowerName.endsWith(" nest")
            || lowerName.endsWith(" eggs"))
        {
            return true;
        }
        return false;
    }

    /** A cooking row must pair raw food inputs with only their cooked/burnt products. */
    private static boolean isCookingFlow(List<ItemFlow> flows)
    {
        List<String> rawBases = new java.util.ArrayList<>();
        List<String> outputs = new java.util.ArrayList<>();
        for (ItemFlow flow : flows)
        {
            if (flow == null || flow.getQuantityDelta() == 0L)
            {
                continue;
            }
            String name = flow.getItemName() == null
                ? ""
                : flow.getItemName().trim().toLowerCase(Locale.ROOT);
            if (flow.getQuantityDelta() < 0L)
            {
                String rawBase = rawFoodBase(name);
                if (rawBase == null)
                {
                    return false;
                }
                rawBases.add(rawBase);
            }
            else
            {
                outputs.add(name);
            }
        }
        if (rawBases.isEmpty() || outputs.isEmpty())
        {
            return false;
        }
        for (String output : outputs)
        {
            boolean matchesInput = false;
            for (String rawBase : rawBases)
            {
                if (output.equals(rawBase)
                    || output.equals("cooked " + rawBase)
                    || output.equals("burnt " + rawBase))
                {
                    matchesInput = true;
                    break;
                }
            }
            if (!matchesInput)
            {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private static String rawFoodBase(String lowerName)
    {
        if (lowerName.startsWith("raw ") && lowerName.length() > 4)
        {
            return lowerName.substring(4).trim();
        }
        if (lowerName.startsWith("uncooked ") && lowerName.length() > 9)
        {
            return lowerName.substring(9).trim();
        }
        return null;
    }

    private static boolean isCookingActivity(@Nullable String activityName)
    {
        return activityName != null && "cooking".equalsIgnoreCase(activityName.trim());
    }

    /** True when every gained item is a burnt product ("Burnt shark", "Burnt pie"). */
    public static boolean allGainsBurnt(@Nullable List<ItemFlow> flows)
    {
        if (flows == null)
        {
            return false;
        }
        boolean any = false;
        for (ItemFlow flow : flows)
        {
            if (flow == null || flow.getQuantityDelta() <= 0L)
            {
                continue;
            }
            String name = flow.getItemName() == null ? "" : flow.getItemName().trim().toLowerCase(Locale.ROOT);
            if (!name.startsWith("burnt"))
            {
                return false;
            }
            any = true;
        }
        return any;
    }

    private static boolean isHerbloreActivity(@Nullable String activityName)
    {
        return activityName != null && "herblore".equalsIgnoreCase(activityName.trim());
    }

    private static boolean hasLoss(List<ItemFlow> flows)
    {
        for (ItemFlow flow : flows)
        {
            if (flow != null && flow.getQuantityDelta() < 0L)
            {
                return true;
            }
        }
        return false;
    }

    private static boolean hasGain(List<ItemFlow> flows)
    {
        for (ItemFlow flow : flows)
        {
            if (flow != null && flow.getQuantityDelta() > 0L)
            {
                return true;
            }
        }
        return false;
    }

    private static boolean allMatch(List<ItemFlow> flows, java.util.function.Predicate<String> predicate)
    {
        boolean any = false;
        for (ItemFlow flow : flows)
        {
            if (flow == null || flow.getQuantityDelta() == 0L)
            {
                continue;
            }
            if (!predicate.test(flow.getItemName()))
            {
                return false;
            }
            any = true;
        }
        return any;
    }
}
