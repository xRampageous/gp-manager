package com.gpmanager;

import static com.gpmanager.ActionKind.*;
import static com.gpmanager.FlowFilters.*;
import static com.gpmanager.GameData.msg;
import static com.gpmanager.TransactionType.*;

import lombok.*;

import java.util.*;
import java.util.function.*;
import java.util.regex.*;

/**
 * Derives the observed {@link ActionKind} for a settled inventory change from positive evidence
 * only: a matched menu verb plus the settled flow shape. Never upgrades weak evidence into a
 * confident verb — a dose leftover without a matched Drink click is {@link ActionKind#SUPPLIES},
 * not {@link ActionKind#DRINK}.
 *
 * <p>Presentation evidence only. Accounting type, valuation and counted state are decided elsewhere
 * and never read this class.
 */
class ActionReader {
    static final Set<String> HERBLORE_INGREDIENTS = new HashSet<>(GameData.names("empty_vessels"));
    static final Pattern HERBLORE_INGREDIENT_SUFFIX = GameData.pattern("herblore-ingredient");
    static final Pattern DOSE_SUFFIX = GameData.pattern("dose-suffix");

    /** Ammunition names; tips, heads and shafts are fletching supplies, not ammunition. */
    static final Pattern AMMUNITION = GameData.pattern("ammunition");

    static final Pattern FLETCHING_PART = GameData.pattern("fletching-part");

    /** Menu verb prefixes (a leading "~" means "contains") and the action each evidences. */
    static final Object[] VERBS =
            GameData.rows("action_rules").stream()
                    .filter(a -> !a[2].isEmpty())
                    .flatMap(a -> java.util.stream.Stream.of(a[0], ActionKind.valueOf(a[2])))
                    .toArray();

    /**
     * Final stamp for a booked change.
     *
     * @param matchedIntent the menu verb whose intent matched these flows, or null when the intent
     *     was absent, stale or matched nothing
     * @param flows settled flows of the transaction
     * @param type booked accounting type (unchanged by this call)
     * @param activityName booked activity name, used only to recognise Herblore production
     */
    static ActionKind resolve(
            ActionKind matchedIntent, List<Flow> flows, TransactionType type, String activityName) {
        if (Phrases.empty(flows)) return null;
        // A decant shape can also appear in a bank transfer or trade. Only the
        // otherwise-unclassified mixed inventory row is evidence of a player action.
        if (type == UNCERTAIN && isDecant(flows)) return DECANT;
        String activity = Phrases.lower(activityName);
        if (type == PROCESSING
                && activity.equals("herblore")
                && all(flows, -1, ActionReader::isIngredient)
                && all(flows, 1, name -> trailingDose(name) > 0)) {
            return MIX;
        }
        if (type == PROCESSING && activity.equals("cooking") && isCooking(flows)) {
            // Use the actual outcome: every output burnt → Burnt, otherwise Cooked.
            return all(flows, 1, name -> name.startsWith("burnt")) ? BURN : COOK;
        }
        // Rune/ammo spends are only recognised on cost-shaped rows: a bank deposit or
        // death loss of runes is a transfer/death, not casting.
        boolean spendShaped = type == CONSUMPTION || type == PK_SUPPLY_COST;
        boolean hasLoss = hasLoss(flows);
        boolean hasGain = !gains(flows, ANY).isEmpty();
        boolean lossOnly = hasLoss && !hasGain;
        if (spendShaped && lossOnly && all(flows, 0, ActionReader::isRune)) return ActionKind.CAST;
        if (spendShaped && lossOnly && all(flows, 0, ActionReader::isAmmo)) return FIRE;
        boolean doseStep = isDoseStep(flows);
        // Every loss must be one potion's doses (Drink) or one item (Eat/Bury/...): a coalesced
        // shark or bones under the same click is not "Drank", so it falls back to Supplies.
        boolean onePotion =
                oneLossFamily(flows, name -> trailingDose(name) > 0 ? doseBaseName(name) : null);
        boolean oneItem =
                oneLossFamily(flows, name -> name.isEmpty() ? null : name)
                        && all(flows, -1, name -> trailingDose(name) <= 0);
        if (matchedIntent != null && hasLoss) {
            switch (matchedIntent) {
                case DRINK:
                    return onePotion ? ActionKind.DRINK : ActionKind.SUPPLIES;
                case EAT:
                case BURY:
                case SCATTER:
                case OFFER:
                    return oneItem ? matchedIntent : ActionKind.SUPPLIES;
                case CAST:
                    // A Cast click without a rune-only loss is not evidence runes were used.
                    return doseStep ? ActionKind.SUPPLIES : null;
                case SUPPLIES:
                    // This explicit intent currently comes from Light/Tend-to and the
                    // Tinderbox→fuel pair. Require the matching loss to be actual fuel.
                    return lossOnly && all(flows, 0, name -> name.matches(msg("logs-kindling")))
                            ? ActionKind.SUPPLIES
                            : null;
                default:
                    return matchedIntent;
            }
        }
        // Consumption is known from the leftover shape; the verb is not.
        return type == CONSUMPTION && doseStep ? ActionKind.SUPPLIES : null;
    }

    /**
     * Doses of one potion redistributed with no dose lost: e.g. −(3) −(1) +(4), or −(4) +(2) +(2).
     * Requires every flow to be a dosed form of the same base name.
     */
    static boolean isDecant(List<Flow> flows) {
        if (flows == null || flows.size() < 2) return false;
        String base = null;
        long doseBalance = 0L;
        boolean anyLoss = false;
        boolean anyGain = false;
        for (Flow flow : flows) {
            if (flow == null || flow.quantityDelta == 0L) continue;
            String name = Phrases.lower(flow.itemName);
            int dose = trailingDose(name);
            // Combining doses frees a vessel; an emptied vial/jug gain is part of the shape.
            if (dose <= 0 && flow.quantityDelta > 0L && isVessel(name)) continue;
            String stem = doseBaseName(name);
            if (dose <= 0 || stem.isEmpty() || base != null && !base.equals(stem)) return false;
            base = stem;
            doseBalance += (long) dose * flow.quantityDelta;
            anyLoss |= flow.quantityDelta < 0L;
            anyGain |= flow.quantityDelta > 0L;
        }
        return anyLoss && anyGain && doseBalance == 0L;
    }

    /** A dose/food step confirms a consume even when its menu intent has expired. */
    static boolean isDoseStep(List<Flow> flows) {
        return !partialUses(flows).isEmpty();
    }

    /** Exact matched loss/remainder pairs for a recognized dose or partial-food consume. */
    static List<PartialUse> partialUses(List<Flow> flows) {
        if (flows == null || flows.size() < 2) return Collections.emptyList();
        var pairs = new ArrayList<PartialUse>();
        for (int lossIndex = 0; lossIndex < flows.size(); lossIndex++) {
            // A loss pairs only with the one remainder it could have become, and that remainder
            // only with this loss.
            List<Integer> gains = stepPartners(flows, flows.get(lossIndex), true);
            if (gains.size() == 1
                    && stepPartners(flows, flows.get(gains.get(0)), false).size() == 1) {
                pairs.add(new PartialUse(lossIndex, gains.get(0)));
            }
        }
        return Collections.unmodifiableList(pairs);
    }

    /**
     * Indexes of the flows a loss could step to ({@code toGains}), or the losses a gain could come
     * from.
     */
    static List<Integer> stepPartners(List<Flow> flows, Flow flow, boolean toGains) {
        var partners = new ArrayList<Integer>();
        Flow loss = toGains ? flow : null;
        Flow gain = toGains ? null : flow;
        if (flow == null
                || (toGains
                        ? flow.quantityDelta >= 0L || flow.quantityDelta == Long.MIN_VALUE
                        : flow.quantityDelta <= 0L)) {
            return partners;
        }
        for (int index = 0; index < flows.size(); index++) {
            Flow other = flows.get(index);
            Flow lost = toGains ? loss : other;
            Flow gained = toGains ? other : gain;
            if (lost != null
                    && gained != null
                    && lost.quantityDelta < 0L
                    && lost.quantityDelta != Long.MIN_VALUE
                    && gained.quantityDelta > 0L
                    && -lost.quantityDelta == gained.quantityDelta
                    && isConsumeStep(lost.itemName, gained.itemName)) {
                partners.add(index);
            }
        }
        return partners;
    }

    @AllArgsConstructor
    static class PartialUse {
        int lossIndex;
        int remainderIndex;
    }

    static boolean addDoseLosses(Map<Integer, Long> target, List<Flow> flows) {
        boolean matched = false;
        for (Flow loss : flows) {
            if (!stepPartners(flows, loss, true).isEmpty()) {
                target.put(loss.itemId, -loss.quantityDelta);
                matched = true;
            }
        }
        return matched;
    }

    /**
     * Potion base name without its dose suffix, for receipts: "Prayer potion(4)" → "Prayer potion".
     */
    static String doseBaseName(String itemName) {
        return itemName == null ? "" : DOSE_SUFFIX.matcher(itemName.trim()).replaceFirst("").trim();
    }

    /** Dose count parsed from a trailing "(N)" suffix, or −1 when absent. */
    static int trailingDose(String itemName) {
        Matcher matcher = DOSE_SUFFIX.matcher(itemName == null ? "" : itemName.trim());
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    static boolean isAmmo(String itemName) {
        String lower = Phrases.lower(itemName);
        return AMMUNITION.matcher(lower).matches() && !FLETCHING_PART.matcher(lower).matches();
    }

    /**
     * True when every loss has the same non-null family key (a matched click cannot name mixed
     * costs); false when there is no loss.
     */
    static boolean oneLossFamily(List<Flow> flows, Function<String, String> family) {
        String first = null;
        for (Flow flow : flows) {
            if (flow == null || flow.quantityDelta >= 0L) continue;
            String key = family.apply(Phrases.lower(flow.itemName));
            if (key == null || first != null && !first.equals(key)) return false;
            first = key;
        }
        return first != null;
    }

    /** A cooking row must pair raw food inputs with only their cooked/burnt products. */
    static boolean isCooking(List<Flow> flows) {
        var rawBases = new ArrayList<String>();
        for (Flow flow : flows) {
            if (flow != null && flow.quantityDelta < 0L) {
                String name = Phrases.lower(flow.itemName);
                rawBases.add(
                        name.startsWith("raw ") && name.length() > 4
                                ? name.substring(4).trim()
                                : name.startsWith("uncooked ") && name.length() > 9
                                        ? name.substring(9).trim()
                                        : null);
            }
        }
        return !rawBases.isEmpty()
                && !rawBases.contains(null)
                && all(
                        flows,
                        1,
                        output ->
                                rawBases.stream()
                                        .anyMatch(
                                                raw ->
                                                        output.equals(raw)
                                                                || output.equals("cooked " + raw)
                                                                || output.equals("burnt " + raw)));
    }

    /** True when every gained item is a burnt product ("Burnt shark", "Burnt pie"). */
    /**
     * True when at least one flow on the chosen side (1 gains, -1 losses, 0 both) exists and every
     * such flow's lower-case name satisfies {@code predicate}.
     */
    static boolean all(List<Flow> flows, int side, Predicate<String> predicate) {
        boolean any = false;
        for (Flow flow : flows) {
            long delta = flow == null ? 0L : flow.quantityDelta;
            if (delta == 0L || side != 0 && Long.signum(delta) != side) continue;
            if (!predicate.test(Phrases.lower(flow.itemName))) return false;
            any = true;
        }
        return any;
    }

    /** Ordered intent and item-name rules, compiled beside the old implementation for parity. */
    static final Map<String, Phrases.Clause[][]> RULES = GameData.rules("action_rules");

    static boolean rule(String group, String text) {
        return Phrases.has(text, RULES.get(group));
    }

    /** First matching verb wins, before the explicit fuel fallback. */
    static ActionKind verbOf(String raw) {
        String name = Phrases.lower(raw);
        for (int index = 0; index < VERBS.length; index += 2) {
            if (rule((String) VERBS[index], name)) return (ActionKind) VERBS[index + 1];
        }
        return rule("supplies", name) ? ActionKind.SUPPLIES : null;
    }

    /** Empty vessels use the already-normalized name, exactly as before. */
    static boolean isVessel(String name) {
        return rule("vessel", name);
    }

    /** Rune and unopened-claim names use the shared trimmed normalization. */
    static boolean isRune(String name) {
        return rule("rune", Phrases.lower(name));
    }

    static boolean isClaim(String name) {
        return rule("claim", Phrases.lower(name));
    }

    /** Ingredient names retain their existing non-null precondition and named ingredient set. */
    static boolean isIngredient(String name) {
        return !name.isEmpty()
                && rule("ingredient", name)
                && (HERBLORE_INGREDIENTS.contains(name)
                        || HERBLORE_INGREDIENT_SUFFIX.matcher(name).matches());
    }

    /** Dose arithmetic is unchanged; only food/vessel vocabulary is table-driven. */
    static boolean isConsumeStep(String lostName, String gainedName) {
        String lost = Phrases.lower(lostName);
        String gained = Phrases.lower(gainedName);
        if (lost.isEmpty() || gained.isEmpty()) return false;
        if (isVessel(gained)) return true;
        int lostDose = trailingDose(lost);
        int gainedDose = trailingDose(gained);
        if (lostDose > 0 && gainedDose >= 0 && lostDose == gainedDose + 1) {
            String lostBase = doseBaseName(lost);
            return !lostBase.isEmpty() && lostBase.equals(doseBaseName(gained));
        }
        return rule("food", lost) && rule("partial", gained);
    }
}
