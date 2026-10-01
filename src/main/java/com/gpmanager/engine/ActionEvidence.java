package com.gpmanager;
import static com.gpmanager.GameData.msg;
import java.util.*;
import java.util.function.*;
import java.util.regex.*;
import lombok.*;
import static com.gpmanager.FlowFilters.*;
import static com.gpmanager.ActionKind.*;
import static com.gpmanager.TransactionType.*;
/**
* Derives the observed {@link ActionKind} for a settled inventory change from positive
* evidence only: a matched menu verb plus the settled flow shape. Never upgrades weak
* evidence into a confident verb — a dose leftover without a matched Drink click is
* {@link ActionKind#SUPPLIES}, not {@link ActionKind#DRINK}.
*
* <p>Presentation evidence only. Accounting type, valuation and counted state are decided
* elsewhere and never read this class.
*/
class ActionEvidence {
static final Set<String> HERBLORE_INGREDIENTS = new HashSet<>(GameData.names("d9"));
static final Pattern HERBLORE_INGREDIENT_SUFFIX = GameData.pattern("ayq");
static final Pattern DOSE_SUFFIX = GameData.pattern("ayo");
/** Ammunition names; tips, heads and shafts are fletching supplies, not ammunition. */
static final Pattern AMMUNITION = GameData.pattern("ayn");
static final Pattern FLETCHING_PART = GameData.pattern("ayp");
/** Menu verb prefixes (a leading "~" means "contains") and the action each evidences. */
static final Object[] VERBS = {
"drink", ActionKind.DRINK, "eat", ActionKind.EAT, "~bury", ActionKind.BURY, "~scatter", ActionKind.SCATTER,
"offer", ActionKind.OFFER, "cast", ActionKind.CAST};
/**
* Menu verb → intent kind. {@code option} is the normalized (lower-case) menu option.
* Returns null for options that do not evidence a specific action (Empty, Break, Release).
*/
static ActionKind fromMenuOption(String option) {
String lower = lower(option);
for (int index = 0; index < VERBS.length && !lower.isEmpty(); index += 2) {
String verb = (String) VERBS[index];
if (verb.startsWith("~") ? lower.contains(verb.substring(1)) : lower.startsWith(verb)) {
return (ActionKind) VERBS[index + 1];
}
}
return lower.matches(msg("ct")) ? ActionKind.SUPPLIES : null;
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
static ActionKind resolve(ActionKind matchedIntent, List<Flow> flows, TransactionType type, String activityName) {
if (ModelText.empty(flows)) return null;
// A decant shape can also appear in a bank transfer or trade. Only the
// otherwise-unclassified mixed inventory row is evidence of a player action.
if (type == UNCERTAIN && isDecant(flows)) return DECANT;
String activity = lower(activityName);
if (type == PROCESSING && activity.equals("herblore")
&& all(flows, -1, ActionEvidence::isHerbloreIngredient) && all(flows, 1, name -> trailingDose(name) > 0)) {
return MIX;
}
if (type == PROCESSING && activity.equals("cooking") && isCookingFlow(flows)) {
// Use the actual outcome: every output burnt → Burnt, otherwise Cooked.
return all(flows, 1, name -> name.startsWith("burnt")) ? BURN : COOK;
}
// Rune/ammo spends are only recognised on cost-shaped rows: a bank deposit or
// death loss of runes is a transfer/death, not casting.
boolean spendShaped = type == CONSUMPTION || type == PK_SUPPLY_COST;
boolean hasLoss = hasLoss(flows);
boolean hasGain = !gains(flows, ANY).isEmpty();
boolean lossOnly = hasLoss && !hasGain;
if (spendShaped && lossOnly && all(flows, 0, ActionEvidence::isRuneName)) return ActionKind.CAST;
if (spendShaped && lossOnly && all(flows, 0, ActionEvidence::isAmmunitionName)) return FIRE;
// Recovery is passive and cannot be inferred from an ammo-only gain: that may
// be a ground pickup. Keep it routine only when upstream supplied explicit
// recovery evidence as the matched intent.
if (matchedIntent == RECOVER_AMMO && hasGain && !hasLoss && all(flows, 0, ActionEvidence::isAmmunitionName)
&& type != TransactionType.LOOT && type != TransactionType.PK_LOOT
&& type != TRADE && type != TransactionType.TRANSFER) {
return RECOVER_AMMO;
}
boolean doseStep = isDoseOrPartialConsumeDelta(flows);
// Every loss must be one potion's doses (Drink) or one item (Eat/Bury/...): a coalesced
// shark or bones under the same click is not "Drank", so it falls back to Supplies.
boolean onePotion = oneLossFamily(flows, name -> trailingDose(name) > 0 ? doseBaseName(name) : null);
boolean oneItem = oneLossFamily(flows, name -> name.isEmpty() ? null : name)
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
return lossOnly && all(flows, 0, name -> name.matches(msg("s"))) ? ActionKind.SUPPLIES : null;
default:
return matchedIntent;
}
}
// Consumption is known from the leftover shape; the verb is not.
return type == CONSUMPTION && doseStep ? ActionKind.SUPPLIES : null;
}

/**
* Doses of one potion redistributed with no dose lost: e.g. −(3) −(1) +(4), or
* −(4) +(2) +(2). Requires every flow to be a dosed form of the same base name.
*/
static boolean isDecant(List<Flow> flows) {
if (flows == null || flows.size() < 2) return false;
String base = null;
long doseBalance = 0L;
boolean anyLoss = false;
boolean anyGain = false;
for (Flow flow : flows) {
if (flow == null || flow.quantityDelta == 0L) continue;
String name = lower(flow.itemName);
int dose = trailingDose(name);
// Combining doses frees a vessel; an emptied vial/jug gain is part of the shape.
if (dose <= 0 && flow.quantityDelta > 0L && isEmptyVesselName(name)) continue;
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
static boolean isDoseOrPartialConsumeDelta(List<Flow> flows) {
return !partialConsumePairs(flows).isEmpty();
}

/** Exact matched loss/remainder pairs for a recognized dose or partial-food consume. */
static List<PartialConsumePair> partialConsumePairs(List<Flow> flows) {
if (flows == null || flows.size() < 2) return Collections.emptyList();
var pairs = new ArrayList<PartialConsumePair>();
for (int lossIndex = 0; lossIndex < flows.size(); lossIndex++) {
// A loss pairs only with the one remainder it could have become, and that remainder
// only with this loss.
List<Integer> gains = stepPartners(flows, flows.get(lossIndex), true);
if (gains.size() == 1 && stepPartners(flows, flows.get(gains.get(0)), false).size() == 1) {
pairs.add(new PartialConsumePair(lossIndex, gains.get(0)));
}
}
return Collections.unmodifiableList(pairs);
}

/** Indexes of the flows a loss could step to ({@code toGains}), or the losses a gain could come from. */
static List<Integer> stepPartners(List<Flow> flows, Flow flow, boolean toGains) {
var partners = new ArrayList<Integer>();
Flow loss = toGains ? flow : null;
Flow gain = toGains ? null : flow;
if (flow == null || (toGains ? flow.quantityDelta >= 0L || flow.quantityDelta == Long.MIN_VALUE
: flow.quantityDelta <= 0L)) {
return partners;
}
for (int index = 0; index < flows.size(); index++) {
Flow other = flows.get(index);
Flow l = toGains ? loss : other;
Flow g = toGains ? other : gain;
if (l != null && g != null && l.quantityDelta < 0L && l.quantityDelta != Long.MIN_VALUE
&& g.quantityDelta > 0L && -l.quantityDelta == g.quantityDelta && isDoseOrVesselStep(l.itemName, g.itemName)) {
partners.add(index);
}
}
return partners;
}

@AllArgsConstructor
static class PartialConsumePair {
final int lossIndex;
final int remainderIndex;
}

static boolean addDoseStepLossQuantities(Map<Integer, Long> target, List<Flow> flows) {
boolean matched = false;
for (Flow loss : flows) {
if (!stepPartners(flows, loss, true).isEmpty()) {
target.put(loss.itemId, -loss.quantityDelta);
matched = true;
}
}
return matched;
}

static boolean isDoseOrVesselStep(String lostName, String gainedName) {
String lost = lower(lostName);
String gained = lower(gainedName);
if (lost.isEmpty() || gained.isEmpty()) return false;
if (isEmptyVesselName(gained)) return true;
int lostDose = trailingDose(lost);
int gainedDose = trailingDose(gained);
if (lostDose > 0 && gainedDose >= 0 && lostDose == gainedDose + 1) {
String lostBase = doseBaseName(lost);
return !lostBase.isEmpty() && lostBase.equals(doseBaseName(gained));
}
return lost.matches(".*(pizza|cake|pie).*") && gained.matches(msg("aj"));
}

static String lower(String value) {
return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
}

/** Emptied containers left behind by dose changes (mirrors the engine's vessel list). */
static boolean isEmptyVesselName(String lowerName) {
return lowerName != null && (lowerName.startsWith("empty ") || (lowerName.equals("vial") || lowerName.equals("jug") || lowerName.equals("beer glass")));
}

/** Potion base name without its dose suffix, for receipts: "Prayer potion(4)" → "Prayer potion". */
static String doseBaseName(String itemName) {
return itemName == null ? "" : DOSE_SUFFIX.matcher(itemName.trim()).replaceFirst("").trim();
}

/** Dose count parsed from a trailing "(N)" suffix, or −1 when absent. */
static int trailingDose(String itemName) {
Matcher matcher = DOSE_SUFFIX.matcher(itemName == null ? "" : itemName.trim());
return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
}

static boolean isRuneName(String itemName) {
return lower(itemName).endsWith(" rune");
}

static boolean isAmmunitionName(String itemName) {
String lower = lower(itemName);
return AMMUNITION.matcher(lower).matches() && !FLETCHING_PART.matcher(lower).matches();
}

/** A value-less unopened claim (a coin pouch) never turns a pickup into a mixed cost. */
static boolean claim(String itemName) {
return lower(itemName).equals("coin pouch");
}

/**
* True when every loss has the same non-null family key (a matched click cannot name mixed
* costs); false when there is no loss.
*/
static boolean oneLossFamily(List<Flow> flows, Function<String, String> family) {
String first = null;
for (Flow flow : flows) {
if (flow == null || flow.quantityDelta >= 0L) continue;
String key = family.apply(lower(flow.itemName));
if (key == null || first != null && !first.equals(key)) return false;
first = key;
}
return first != null;
}

static boolean isHerbloreIngredient(String lowerName) {
return !lowerName.isEmpty() && !lowerName.startsWith("grimy ")
&& (HERBLORE_INGREDIENTS.contains(lowerName) || HERBLORE_INGREDIENT_SUFFIX.matcher(lowerName).matches());
}

/** A cooking row must pair raw food inputs with only their cooked/burnt products. */
static boolean isCookingFlow(List<Flow> flows) {
var rawBases = new ArrayList<String>();
for (Flow flow : flows) {
if (flow != null && flow.quantityDelta < 0L) {
String name = lower(flow.itemName);
rawBases.add(name.startsWith("raw ") && name.length() > 4 ? name.substring(4).trim()
: name.startsWith("uncooked ") && name.length() > 9 ? name.substring(9).trim() : null);
}
}
return !rawBases.isEmpty() && !rawBases.contains(null) && all(flows, 1, output -> rawBases.stream()
.anyMatch(raw -> output.equals(raw) || output.equals("cooked " + raw) || output.equals("burnt " + raw)));
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
if (!predicate.test(lower(flow.itemName))) return false;
any = true;
}
return any;
}
}
