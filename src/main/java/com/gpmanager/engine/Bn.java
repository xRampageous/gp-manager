package com.gpmanager;
import static com.gpmanager.Ak.msg;
import java.util.*;
import java.util.function.*;
import java.util.regex.*;
import lombok.*;
import static com.gpmanager.Dv.*;
import static com.gpmanager.Au.*;
import static com.gpmanager.Ai.*;
/**
* Derives the observed {@link Au} for a settled inventory change from positive
* evidence only: a matched menu verb plus the settled flow shape. Never upgrades weak
* evidence into a confident verb — a dose leftover without a matched Drink click is
* {@link Au#SUPPLIES}, not {@link Au#DRINK}.
*
* <p>Presentation evidence only. Accounting type, valuation and counted state are decided
* elsewhere and never read this class.
*/
class Bn {
static final Set<String> HERBLORE_INGREDIENTS = new HashSet<>(Ak.names("d9"));
static final Pattern HERBLORE_INGREDIENT_SUFFIX =
Ak.pattern("ayq");
static final Pattern DOSE_SUFFIX = Ak.pattern("ayo");
/** Ammunition names; tips, heads and shafts are fletching supplies, not ammunition. */
static final Pattern AMMUNITION = Ak.pattern("ayn");
static final Pattern FLETCHING_PART = Ak.pattern("ayp");
/** Menu verb prefixes (a leading "~" means "contains") and the action each evidences. */
static final Object[] VERBS = {
"drink", Au.DRINK, "eat", Au.EAT, "~bury", Au.BURY, "~scatter", Au.SCATTER,
"offer", Au.OFFER, "cast", Au.CAST};
/**
* Menu verb → intent kind. {@code option} is the normalized (lower-case) menu option.
* Returns null for options that do not evidence a specific action (Empty, Break, Release).
*/
static Au tu(String option) {
String lower = lower(option);
for (int index = 0; index < VERBS.length && !lower.isEmpty(); index += 2) {
String verb = (String) VERBS[index];
if (verb.startsWith("~") ? lower.contains(verb.substring(1)) : lower.startsWith(verb)) {
return (Au) VERBS[index + 1];
}
}
return lower.matches(msg("ct")) ? Au.SUPPLIES : null;
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
static Au resolve(
Au matchedIntent,
List<Ab> flows,
Ai type,
String activityName) {
if (Ag.empty(flows)) {
return null;
}
// A decant shape can also appear in a bank transfer or trade. Only the
// otherwise-unclassified mixed inventory row is evidence of a player action.
if (type == UNCERTAIN && isDecant(flows)) {
return DECANT;
}
String activity = lower(activityName);
if (type == PROCESSING && activity.equals("herblore")
&& all(flows, -1, Bn::xc) && all(flows, 1, name -> ajr(name) > 0)) {
return MIX;
}
if (type == PROCESSING && activity.equals("cooking") && wj(flows)) {
// Use the actual outcome: every output burnt → Burnt, otherwise Cooked.
return all(flows, 1, name -> name.startsWith("burnt")) ? BURN : COOK;
}
// Rune/ammo spends are only recognised on cost-shaped rows: a bank deposit or
// death loss of runes is a transfer/death, not casting.
boolean aqp = type == CONSUMPTION || type == PK_SUPPLY_COST;
boolean axs = axs(flows);
boolean hasGain = !gains(flows, ANY).isEmpty();
boolean aoz = axs && !hasGain;
if (aqp && aoz && all(flows, 0, Bn::xu)) {
return Au.CAST;
}
if (aqp && aoz && all(flows, 0, Bn::vm)) {
return FIRE;
}
// Recovery is passive and cannot be inferred from an ammo-only gain: that may
// be a ground pickup. Keep it routine only when upstream supplied explicit
// recovery evidence as the matched intent.
if (matchedIntent == RECOVER_AMMO && hasGain && !axs
&& all(flows, 0, Bn::vm)
&& type != Ai.LOOT && type != Ai.PK_LOOT
&& type != TRADE && type != Ai.TRANSFER) {
return RECOVER_AMMO;
}
boolean aoa = wi(flows);
// Every loss must be one potion's doses (Drink) or one item (Eat/Bury/...): a coalesced
// shark or bones under the same click is not "Drank", so it falls back to Supplies.
boolean asy = acn(flows, name -> ajr(name) > 0 ? qz(name) : null);
boolean asx = acn(flows, name -> name.isEmpty() ? null : name)
&& all(flows, -1, name -> ajr(name) <= 0);
if (matchedIntent != null && axs) {
switch (matchedIntent) {
case DRINK:
return asy ? Au.DRINK : Au.SUPPLIES;
case EAT:
case BURY:
case SCATTER:
case OFFER:
return asx ? matchedIntent : Au.SUPPLIES;
case CAST:
// A Cast click without a rune-only loss is not evidence runes were used.
return aoa ? Au.SUPPLIES : null;
case SUPPLIES:
// This explicit intent currently comes from Light/Tend-to and the
// Tinderbox→fuel pair. Require the matching loss to be actual fuel.
return aoz && all(flows, 0,
name -> name.matches(msg("s")))
? Au.SUPPLIES : null;
default:
return matchedIntent;
}
}
// Consumption is known from the leftover shape; the verb is not.
return type == CONSUMPTION && aoa ? Au.SUPPLIES : null;
}
/**
* Doses of one potion redistributed with no dose lost: e.g. −(3) −(1) +(4), or
* −(4) +(2) +(2). Requires every flow to be a dosed form of the same base name.
*/
static boolean isDecant(List<Ab> flows) {
if (flows == null || flows.size() < 2) {
return false;
}
String base = null;
long anx = 0L;
boolean arl = false;
boolean ark = false;
for (Ab flow : flows) {
if (flow == null || flow.quantityDelta == 0L) {
continue;
}
String name = lower(flow.itemName);
int dose = ajr(name);
// Combining doses frees a vessel; an emptied vial/jug gain is part of the shape.
if (dose <= 0 && flow.quantityDelta > 0L && wl(name)) {
continue;
}
String ayk = qz(name);
if (dose <= 0 || ayk.isEmpty() || base != null && !base.equals(ayk)) {
return false;
}
base = ayk;
anx += (long) dose * flow.quantityDelta;
arl |= flow.quantityDelta < 0L;
ark |= flow.quantityDelta > 0L;
}
return arl && ark && anx == 0L;
}
/** A dose/food step confirms a consume even when its menu intent has expired. */
static boolean wi(List<Ab> flows) {
return !acr(flows).isEmpty();
}
/** Exact matched loss/remainder pairs for a recognized dose or partial-food consume. */
static List<Cg> acr(List<Ab> flows) {
if (flows == null || flows.size() < 2) {
return Collections.emptyList();
}
var pairs = new ArrayList<Cg>();
for (int lossIndex = 0; lossIndex < flows.size(); lossIndex++) {
// A loss pairs only with the one remainder it could have become, and that remainder
// only with this loss.
List<Integer> gains = aiy(flows, flows.get(lossIndex), true);
if (gains.size() == 1 && aiy(flows, flows.get(gains.get(0)), false).size() == 1) {
pairs.add(new Cg(lossIndex, gains.get(0)));
}
}
return Collections.unmodifiableList(pairs);
}
/** Indexes of the flows a loss could step to ({@code toGains}), or the losses a gain could come from. */
static List<Integer> aiy(List<Ab> flows, Ab flow, boolean toGains) {
var partners = new ArrayList<Integer>();
Ab loss = toGains ? flow : null;
Ab gain = toGains ? null : flow;
if (flow == null || (toGains ? flow.quantityDelta >= 0L || flow.quantityDelta == Long.MIN_VALUE
: flow.quantityDelta <= 0L)) {
return partners;
}
for (int index = 0; index < flows.size(); index++) {
Ab other = flows.get(index);
Ab l = toGains ? loss : other;
Ab g = toGains ? other : gain;
if (l != null && g != null && l.quantityDelta < 0L && l.quantityDelta != Long.MIN_VALUE
&& g.quantityDelta > 0L && -l.quantityDelta == g.quantityDelta
&& wu(l.itemName, g.itemName)) {
partners.add(index);
}
}
return partners;
}
@AllArgsConstructor
static class Cg {
final int lossIndex;
final int remainderIndex;
}
static boolean kd(Map<Integer, Long> target, List<Ab> flows) {
boolean matched = false;
for (Ab loss : flows) {
if (!aiy(flows, loss, true).isEmpty()) {
target.put(loss.itemId, -loss.quantityDelta);
matched = true;
}
}
return matched;
}
static boolean wu(String lostName, String gainedName) {
String lost = lower(lostName);
String gained = lower(gainedName);
if (lost.isEmpty() || gained.isEmpty()) {
return false;
}
if (wl(gained)) {
return true;
}
int lostDose = ajr(lost);
int gainedDose = ajr(gained);
if (lostDose > 0 && gainedDose >= 0 && lostDose == gainedDose + 1) {
String apb = qz(lost);
return !apb.isEmpty() && apb.equals(qz(gained));
}
return lost.matches(".*(pizza|cake|pie).*") && gained.matches(msg("aj"));
}
static String lower(String value) {
return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
}
/** Emptied containers left behind by dose changes (mirrors the engine's vessel list). */
static boolean wl(String lowerName) {
return lowerName != null && (lowerName.startsWith("empty ") || (lowerName.equals("vial") || lowerName.equals("jug") || lowerName.equals("beer glass")));
}
/** Potion base name without its dose suffix, for receipts: "Prayer potion(4)" → "Prayer potion". */
static String qz(String itemName) {
return itemName == null ? "" : DOSE_SUFFIX.matcher(itemName.trim()).replaceFirst("").trim();
}
/** Dose count parsed from a trailing "(N)" suffix, or −1 when absent. */
static int ajr(String itemName) {
Matcher matcher = DOSE_SUFFIX.matcher(itemName == null ? "" : itemName.trim());
return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
}
static boolean xu(String itemName) {
return lower(itemName).endsWith(" rune");
}
static boolean vm(String itemName) {
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
static boolean acn(List<Ab> flows, Function<String, String> family) {
String first = null;
for (Ab flow : flows) {
if (flow == null || flow.quantityDelta >= 0L) {
continue;
}
String key = family.apply(lower(flow.itemName));
if (key == null || first != null && !first.equals(key)) {
return false;
}
first = key;
}
return first != null;
}
static boolean xc(String lowerName) {
return !lowerName.isEmpty() && !lowerName.startsWith("grimy ")
&& (HERBLORE_INGREDIENTS.contains(lowerName) || HERBLORE_INGREDIENT_SUFFIX.matcher(lowerName).matches());
}
/** A cooking row must pair raw food inputs with only their cooked/burnt products. */
static boolean wj(List<Ab> flows) {
var apr = new ArrayList<String>();
for (Ab flow : flows) {
if (flow != null && flow.quantityDelta < 0L) {
String name = lower(flow.itemName);
apr.add(name.startsWith("raw ") && name.length() > 4 ? name.substring(4).trim()
: name.startsWith("uncooked ") && name.length() > 9 ? name.substring(9).trim() : null);
}
}
return !apr.isEmpty() && !apr.contains(null) && all(flows, 1, output -> apr.stream()
.anyMatch(raw -> output.equals(raw) || output.equals("cooked " + raw) || output.equals("burnt " + raw)));
}
/** True when every gained item is a burnt product ("Burnt shark", "Burnt pie"). */
/**
* True when at least one flow on the chosen side (1 gains, -1 losses, 0 both) exists and every
* such flow's lower-case name satisfies {@code predicate}.
*/
static boolean all(List<Ab> flows, int side, Predicate<String> predicate) {
boolean any = false;
for (Ab flow : flows) {
long delta = flow == null ? 0L : flow.quantityDelta;
if (delta == 0L || side != 0 && Long.signum(delta) != side) {
continue;
}
if (!predicate.test(lower(flow.itemName))) {
return false;
}
any = true;
}
return any;
}
}
