package com.gpmanager;
import static com.gpmanager.ChargeRead.*;
import static net.runelite.api.gameval.AnimationID.*;
import com.gpmanager.ChargeRead.Variant;
import lombok.RequiredArgsConstructor;
import java.util.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.widgets.Widget;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.game.ItemManager;
/**
* Client-thread intake for the exact Check-to-Check charge contract: a Check click binds a
* short-lived identity, the numeric chat read is the only source of counts, and a Use-on-item
* click arms one narrow load. Local graphics, hitsplats and location clicks book LIKELY estimates.
*/
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ChargeIntake {
final Client client;
final ItemManager itemManager;
final GpManagerConfig config;
final Engine engine;
/** The single GP Manager valuation authority; null only in narrow synthetic tests. */
final ItemValuationService valuation;
final MeasuredChargeCheckIntent checkIntent = new MeasuredChargeCheckIntent();
/** Fractional component carry per variant+item so whole units book when they accrue. */
final Map<String, Long> chargeCarry = new HashMap<>();
/** Exact cumulative value per fractional component: units consumed and value already booked. */
final Map<String, long[]> valueCarry = new HashMap<>();
int lastCastTick = -1;
int lastCastGraphic;
int lastAttackTick = -1;
/** Learnable eye cast triggers: local graphics or animations proven by same-tick magic XP. */
final Set<Integer> eyeGraphics = new HashSet<>();
final Set<Integer> eyeAnimations = new HashSet<>();
int eyeCandidateGraphic;
int eyeCandidateAnimation;
int eyeCandidateTick = -1;
int eyeCandidateWeaponId;
String eyeCandidateIdentity;
/** A melee hit awaiting its same-tick XP confirmation, so a login's first hit counts. */
int pendingFuryHitTick = -1;
/** True when the staff's own line said runes: each cast then spends 2 death and 1 chaos. */
/** Eye charging mode per target identity: true when that staff's own line said runes. */
final Map<String, Boolean> eyeRunes = new HashMap<>();
/** An Uncharge/Unload waiting for its components to land (a confirm prompt can take a while). */
Variant returnVariant;
int returnUntilTick = -1;
Map<Integer, Long> returnBase = Collections.emptyMap();
/** How long an Uncharge click waits for its returned components: 50 ticks, 30 seconds. */
static final int RETURN_TICKS = 50;
/** Any unrelated chat/menu between click and numeric response drops the target link. */
void clearCheckIntent() {
 checkIntent.clear();
}

/** Login/hop/profile change: transient baselines and load intents never survive. */
void resetTransient() {
 checkIntent.clear();
 chargeCarry.clear();
 valueCarry.clear();
 eyeGraphics.clear();
 eyeAnimations.clear();
 eyeCandidateGraphic = 0;
 eyeCandidateAnimation = 0;
 eyeCandidateTick = -1;
 eyeCandidateWeaponId = 0;
 eyeCandidateIdentity = null;
 pendingFuryHitTick = -1;
 eyeRunes.clear();
 returnVariant = null;
 lastAttackTick = -1;
 engine.resetMeasuredChargeReads();
 engine.clearChargeLoadTransfer();
}

/**
* Feed only exact numeric Check messages into the measured charge baseline. Chat is
* deliberately not activity/title evidence. Booking rejects the complete delta on a missing
* supported component valuation or any arithmetic overflow.
*
* @return true when the message was a charge Check read (consumed, even if unbooked)
*/
boolean observeMeasuredChargeCheck(String message, long now) {
 ChargeRead read = parseCheckMessage(message);
 if (read == null) return false;
 String targetIdentity = checkIntent.consume(read, client.getTickCount());
 if (targetIdentity == null && read.variant == Variant.BLOOD_FURY) {
  // The amulet's count arrives on its own, without a Check click: bind it to the
  // worn amulet's neck slot (owner 2026-09-29).
  targetIdentity = wornBloodFury();
 }
 if (read.variant == Variant.EYE_OF_AYAK) {
  // The line names the mode ("demon tears" or "runes").
  eyeRunes.put(targetIdentity, read.componentCounts.containsKey(ItemID.DEATHRUNE));
  if (targetIdentity == null) {
   // No Check click: the line can only mean the worn staff or the pack copy.
   targetIdentity = eyeIdentity();
  }
 }
 if (targetIdentity == null) {
  // A value without its fresh Check target cannot distinguish another
  // same-variant weapon; discard any older comparison baseline.
  engine.resetMeasuredChargeReads();
  return true;
 }
 if (message.toLowerCase(Locale.ROOT).contains("charged with")) {
  // Any charge wording is a load: the components moved into the weapon, never spend.
  // Armed even on the first read, where no delta exists yet (owner 2026-09-30).
  engine.markContext(Context.TRANSFER, Math.max(4, config.stabilizationTicks() + 2), GameData.msg("m2"));
 }
 ChargeDelta delta = engine.observeMeasuredChargeRead(read, targetIdentity, now);
 if (read.bookable && delta != null && delta.getComponentDeltas().isEmpty()) {
  // A compatible Check that moved nothing closes the window; automatic overestimates must go.
  engine.closeChargeWindow(targetIdentity);
  return true;
 }
 if (!read.bookable || delta == null || delta.getComponentDeltas().isEmpty() || itemManager == null) {
  return true;
 }
 if (delta.getComponentDeltas().stream().anyMatch(component -> component.getQuantityDelta() >= 0)) {
  // A rise is a recharge: the component moved into the weapon's stored charges,
  // never spend. Arm the ownership-neutral context so the matching inventory loss
  // (any charging route: use-on-item, the Eye's charge dialogue, a load) is not a cost.
  engine.markContext(Context.TRANSFER, Math.max(4, config.stabilizationTicks() + 2), GameData.msg("m2"));
  return true;
 }
 if (read.variant == Variant.EYE_OF_AYAK) {
  boolean rune = read.componentCounts.containsKey(ItemID.DEATHRUNE) || read.componentCounts.containsKey(ItemID.CHAOSRUNE);
  if (delta.getComponentDeltas().stream().anyMatch(component -> (component.getItemId() == ItemID.DEMON_TEAR) == rune)) {
   // A mode switch's vanished component is a load or unload, never spend.
   return true;
  }
 }
 var losses = new ArrayList<Flow>();
 for (ChargeDelta.ComponentDelta component : delta.getComponentDeltas()) {
  int itemId = component.getItemId();
  int canonicalId = itemManager.canonicalize(itemId);
  ItemComposition composition = itemId <= 0 || canonicalId <= 0 ? null : itemManager.getItemComposition(canonicalId);
  if (composition == null || ModelText.blank(composition.getName())) return true;
  // Every component's base unit valuation comes from the one shared GP Manager
  // policy: face value, explicit manual override, the RuneLite mapping guard,
  // the world/economy gate and the explicit active market route. Booking rejects
  // the whole measured delta when any component lacks a supported valuation.
  Flow valued = valuation == null ? null : valuation.valueChargeComponent(canonicalId, component.getQuantityDelta(), now);
  int unitPrice = valued == null ? 0 : valued.unitPrice;
  // A component measured in fractions of the priced item (a page feeds 20 charges).
  int perItem = unitsPerPricedItem(read.variant, itemId);
  long units = component.getQuantityDelta();
  long value = SafeMath.safeMultiply(units, unitPrice);
  if (perItem > 1 && unitPrice > 0) {
   value = -carriedValue(read.variant, itemId, Math.abs(units), unitPrice, perItem);
   unitPrice = Math.max(1, unitPrice / perItem);
  }
  losses.add(new Flow(itemId, composition.getName(), units, unitPrice, value,
  valued == null ? PriceSource.UNPRICED : valued.getPriceSource(), now));
 }
 engine.bookChargeSpend(delta, read.variant.getDisplayName(), losses, now, targetIdentity);
 // The measured read reconciles this family's estimates: the Live strip drops it.
 engine.clearPendingEstimates(read.variant.getDisplayName());
 return true;
}

/** The tick through which recent combat XP proves a melee stance; -1 when it does not. */
int meleeStanceUntil = -1;
int stanceXpTick = -1;
boolean meleeXpThisTick;
boolean otherXpThisTick;
/**
* Combat XP reveals the attack style with no per-weapon animation table: Attack/Strength/
* Defence confirm melee, Ranged/Magic deny it, so every melee weapon qualifies (owner 2026-09-29).
*/
void learnEyeCast(Skill skill, int tick, long now) {
 if (skill == Skill.MAGIC && eyeCandidateTick >= 0 && tick - eyeCandidateTick <= 1) {
  if (eyeCandidateAnimation > 0) eyeAnimations.add(eyeCandidateAnimation);
  if (eyeCandidateGraphic > 0) eyeGraphics.add(eyeCandidateGraphic);
  if (eyeCandidateIdentity != null && tick != lastAttackTick) {
   // The cast that proved the trigger books once, through the same tick dedupe.
   lastAttackTick = tick;
   bookAttackEstimate(Variant.EYE_OF_AYAK, eyeCandidateWeaponId, eyeCandidateIdentity, ActionKind.CAST, now);
  }
  eyeCandidateAnimation = 0;
  eyeCandidateGraphic = 0;
  eyeCandidateTick = -1;
  eyeCandidateWeaponId = 0;
  eyeCandidateIdentity = null;
 }
 boolean melee = skill == Skill.ATTACK || skill == Skill.STRENGTH || skill == Skill.DEFENCE;
 boolean other = skill == Skill.RANGED || skill == Skill.MAGIC;
 if (!melee && !other) return;
 if (tick != stanceXpTick) {
  stanceXpTick = tick;
  meleeXpThisTick = false;
  otherXpThisTick = false;
 }
 meleeXpThisTick |= melee;
 otherXpThisTick |= other;
 meleeStanceUntil = otherXpThisTick ? -1 : meleeXpThisTick ? tick + 8 : meleeStanceUntil;
 if (melee && pendingFuryHitTick == tick) {
  // The hit that opened this fight lands before its XP in the same tick.
  pendingFuryHitTick = -1;
  ItemContainer worn = client == null ? null : client.getItemContainer(InventoryID.WORN);
  Item amulet = worn == null ? null : worn.getItem(EquipmentInventorySlot.AMULET.getSlotIdx());
  if (amulet != null && amulet.getId() == ItemID.BLOOD_AMULET) {
   bookAttackEstimate(Variant.BLOOD_FURY, amulet.getId(), wornBloodFury(), kindFor(Variant.BLOOD_FURY), now);
  }
 }
}

/** Whether the recent XP pattern proves the player is meleeing right now. */
boolean furyMelee(int tick) {
 return tick <= meleeStanceUntil;
}

/** That staff's charging mode; unknown means tears (the default charge type). */
boolean eyeRune(String identity) {
 return Boolean.TRUE.equals(eyeRunes.get(identity));
}

/** The eye's identity for lines that arrive with no Check click: the worn staff, else the pack. */
String eyeIdentity() {
 ItemContainer worn = client == null ? null : client.getItemContainer(InventoryID.WORN);
 if (worn != null) {
  Item weapon = worn.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
  if (weapon != null && supportedVariantForItemId(weapon.getId()) == Variant.EYE_OF_AYAK) {
   return wornTargetIdentity(Variant.EYE_OF_AYAK, weapon.getId());
  }
 }
 ItemContainer inventory = client == null ? null : client.getItemContainer(InventoryID.INV);
 if (inventory == null) return null;
 for (int slot = 0; slot < inventory.size(); slot++) {
  Item item = inventory.getItem(slot);
  if (item != null && supportedVariantForItemId(item.getId()) == Variant.EYE_OF_AYAK) {
   return (InterfaceID.Inventory.ITEMS >>> 16) + ":" + slot + ":" + item.getId() + ":" + Variant.EYE_OF_AYAK.name();
  }
 }
 return null;
}

/** The worn amulet's identity for its click-free count messages; null when not worn. */
String wornBloodFury() {
 ItemContainer worn = client == null ? null : client.getItemContainer(InventoryID.WORN);
 if (worn == null) return null;
 int slot = EquipmentInventorySlot.AMULET.getSlotIdx();
 Item amulet = worn.getItem(slot);
 if (amulet == null || amulet.getId() != ItemID.BLOOD_AMULET) return null;
 return (InterfaceID.Wornitems.UNIVERSE >>> 16) + ":" + slot + ":" + amulet.getId() + ":" + Variant.BLOOD_FURY.name();
}

/** Every menu click: supersede a pending Check link, then arm Check / unload / load intents. */
void onMenuOptionClicked(MenuOptionClicked event, String option, String target) {
 // Answering the Uncharge prompt keeps the return watch; any other action ends it, so a
 // declined prompt never turns later loot of the same runes or scales into a transfer.
 if (!isDialogAnswer(event, option)) returnVariant = null;
 armMeasuredChargeCheck(event, option, target);
 armChargeLoadTransfer(event, target);
 if (!isAtesLocation(option)) return;
 int itemId = chargeWeaponItemId(event);
 if (supportedVariantForItemId(itemId) != Variant.ATES) return;
 String identity = chargeWeaponTargetIdentity(event, Variant.ATES, itemId);
 if (identity != null) bookAttackEstimate(Variant.ATES, itemId, identity, ActionKind.SUPPLIES, System.currentTimeMillis());
}

/** A pendant teleport click spends one tear; bank, inventory and interface options are plumbing. */
static boolean isAtesLocation(String option) {
 String name = option == null ? "" : option.replace('\u2019', '\'');
 if (name.startsWith("the ")) name = name.substring(4);
 return LOCATIONS.contains(name);
}

/** The pendant's six teleport locations, normalized like menu options. */
static final Set<String> LOCATIONS = Set.of("darkfrost", "twilight temple", "ralos' rise",
"north aldarin", "kastori", "nemus retreat");
/**
* A local cast or shot graphic books this use's pinned recipe as a LIKELY estimate; the next
* measured Check reconciles it at the captured prices. Any unpriced component fails the whole
* estimate closed. A repeated graphic in one tick is one use.
*/
void graphicChanged(int graphicId, long now) {
 Item weapon = wornWeapon();
 Variant variant = weapon == null ? null : supportedVariantForItemId(weapon.getId());
 if (variant == null || graphicId <= 0) return;
 boolean eye = variant == Variant.EYE_OF_AYAK;
 if (!isCastGraphic(variant, graphicId) && !(eye && eyeGraphics.contains(graphicId))) {
  if (eye) {
   // The eye's cast trigger is pinned in no table: learn it from the magic XP every
   // cast drops (owner 2026-09-29), then each cast books one tear like a spell.
   eyeCandidateGraphic = graphicId;
   eyeCandidateTick = client.getTickCount();
   eyeCandidateWeaponId = weapon.getId();
   eyeCandidateIdentity = wornTargetIdentity(Variant.EYE_OF_AYAK, weapon.getId());
  }
  return;
 }
 int tick = client.getTickCount();
 if (tick == lastCastTick && graphicId == lastCastGraphic || eye && tick == lastAttackTick) return;
 lastCastTick = tick;
 lastCastGraphic = graphicId;
 if (eye) lastAttackTick = tick;
 bookAttackEstimate(variant, weapon.getId(), wornTargetIdentity(variant, weapon.getId()), kindFor(variant), now);
}

/**
* A landed hit on the target. The scythe books one charge per damaging hit; the blowpipe books
* any shot while its attack animation is live, because a 2-tick attack restarts the same
* animation and never fires a second AnimationChanged. At most once per tick.
*/
void hitApplied(int damage, long now) {
 Item weapon = wornWeapon();
 Variant variant = weapon == null ? null : supportedVariantForItemId(weapon.getId());
 // Blood fury: one charge per melee hit. Combat XP proves melee for every weapon
 // (owner 2026-09-29); a hit whose XP has not landed yet this tick stays pending
 // until that same tick's XP confirms the stance (owner 2026-10-01).
 if (damage > 0) {
  int tick = client.getTickCount();
  ItemContainer worn = client.getItemContainer(InventoryID.WORN);
  Item amulet = worn == null ? null : worn.getItem(EquipmentInventorySlot.AMULET.getSlotIdx());
  if (amulet != null && amulet.getId() == ItemID.BLOOD_AMULET) {
   if (furyMelee(tick)) bookAttackEstimate(Variant.BLOOD_FURY, amulet.getId(), wornBloodFury(), kindFor(Variant.BLOOD_FURY), now);
   else pendingFuryHitTick = tick;
  }
 }
 if (variant == null) return;
 // Each landed splat books: a multi-target swing is several hits, not one attack.
 boolean scythe = variant == Variant.SCYTHE && damage > 0;
 Actor local = client.getLocalPlayer();
 boolean shot = variant == Variant.V1b && local != null && local.getAnimation() == attackAnimation(variant);
 if (!scythe && !shot) return;
 if (shot) {
  int tick = client.getTickCount();
  if (tick == lastAttackTick) return;
  lastAttackTick = tick;
 }
 bookAttackEstimate(variant, weapon.getId(), wornTargetIdentity(variant, weapon.getId()), kindFor(variant), now);
}

/** A local attack animation books the pinned per-attack charge for crystal weapons. */
void observeSkillXp(int animationId, long now) {
 Item weapon = wornWeapon();
 Variant variant = weapon == null ? null : supportedVariantForItemId(weapon.getId());
 if (variant == null) return;
 if (variant == Variant.EYE_OF_AYAK) {
  if (!eyeAnimations.contains(animationId)) {
   // Candidate cast animation; the same tick's magic XP proves it, like the graphic.
   eyeCandidateAnimation = animationId;
   eyeCandidateTick = client.getTickCount();
   eyeCandidateWeaponId = weapon.getId();
   eyeCandidateIdentity = wornTargetIdentity(Variant.EYE_OF_AYAK, weapon.getId());
   return;
  }
 } else if (!isPinnedAttack(variant, animationId)) {
  return;
 }
 int tick = client.getTickCount();
 if (tick == lastAttackTick) return;
 lastAttackTick = tick;
 bookAttackEstimate(variant, weapon.getId(), wornTargetIdentity(variant, weapon.getId()), kindFor(variant), now);
}

Item wornWeapon() {
 ItemContainer worn = client.getItemContainer(InventoryID.WORN);
 return worn == null ? null : worn.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
}

void bookAttackEstimate(Variant variant, int weaponId, String identity, ActionKind kind, long now) {
 // A hit is gameplay: resume the pauses gameplay ends (logout/idle/recovery) and start
 // tracking when automatic startup is on, before booking — so the very first attack after
 // logging in counts instead of arriving while the session is still catching up.
 engine.resume(now, PauseReason.LIFECYCLE, PauseReason.IDLE, PauseReason.RECOVERY);
 if (!engine.live()) engine.startGeneralFromActivityIfNeeded(now);
 List<Flow> recipe = estimateRecipe(variant, identity, now);
 if (recipe == null || recipe.isEmpty()) return;
 Transaction receipt = engine.bookEstimatedChargeUsage(identity, variant.getDisplayName(), kind, recipe, now);
 if (receipt != null) receipt.chargeCastItemId = weaponId;
}

/** The local cast graphic pinned for each supported family; 0 means no automatic estimate. */
static int castGraphic(Variant variant) {
 switch (variant) {
  case TRIDENT_SEAS:
  case TRIDENT_SEAS_ENHANCED:
  return 1251;
  case TRIDENT_SWAMP:
  case TRIDENT_SWAMP_ENHANCED:
  return 665;
  case SHADOW:
  return 2125;
  case WARPED:
  return 2567;
  case SANGUINESTI:
  case SANGUINESTI_HOLY:
  return 1540;
  case VENATOR:
  case VENATOR_ECHO:
  return 2289;
  case BOWFA:
  return 1888;
  default:
  return 0;
 }
}

/** A family may cast with more than one local graphic (Sanguinesti's Justiciar variant). */
static boolean isCastGraphic(Variant variant, int graphicId) {
 return graphicId == castGraphic(variant) || graphicId == 1900 && (variant == Variant.SANGUINESTI
 || variant == Variant.SANGUINESTI_HOLY);
}

/** The attack animation pinned for per-shot estimates; 0 means none. */
static int attackAnimation(Variant variant) {
 return variant == Variant.V1b ? 5061 : 0;
}

/** The attack animations pinned for per-attack estimates; false means none. */
static boolean isPinnedAttack(Variant variant, int animationId) {
 if (variant == Variant.CRYSTAL_BOW) return animationId == HUMAN_BOW;
 if (variant == Variant.SAELDOR) return animationId == HUMAN_SWORD_SLASH || animationId == HUMAN_SWORD_LUNGE;
 return variant == Variant.CRYSTAL_HALBERD && (animationId == HUMAN_SPEAR_SPIKE || animationId == HUMAN_SCYTHE_SWEEP
 || animationId == DRAGON_HALBERD_SPECIAL_ATTACK);
}

/** The action a variant's estimate represents: a built-in cast, a bow shot or a melee use. */
static ActionKind kindFor(Variant variant) {
 return variant == Variant.VENATOR || variant == Variant.VENATOR_ECHO || variant == Variant.CRYSTAL_BOW
 || variant == Variant.BOWFA ? ActionKind.FIRE
 : variant == Variant.SCYTHE || variant == Variant.CRYSTAL_HALBERD || variant == Variant.SAELDOR
 || variant == Variant.BLOOD_FURY ? ActionKind.SUPPLIES : ActionKind.CAST;
}

/** The equipment-side identity a worn Check uses: container group, weapon slot, item, variant. */
static String wornTargetIdentity(Variant variant, int weaponItemId) {
 int widgetId = InterfaceID.Wornitems.UNIVERSE;
 return (widgetId >>> 16) + ":" + EquipmentInventorySlot.WEAPON.getSlotIdx() + ":" + weaponItemId + ":" + variant.name();
}

/** The eye's mode-aware per-cast recipe, keyed off its own charge line's wording (owner 2026-09-30). */
static Map<Integer, Long> eyeRecipe(boolean runeCharged) {
 var recipe = new LinkedHashMap<Integer, Long>();
 if (runeCharged) {
  recipe.put(ItemID.DEATHRUNE, 2L);
  recipe.put(ItemID.CHAOSRUNE, 1L);
 } else {
  recipe.put(ItemID.DEMON_TEAR, 1L);
 }
 return recipe;
}

/** One cast's pinned component quantities per family. */
static Map<Integer, Long> recipeOf(Variant variant) {
 var recipe = new LinkedHashMap<Integer, Long>();
 if (variant == Variant.SHADOW) {
  recipe.put(ItemID.SOULRUNE, 2L);
  recipe.put(ItemID.CHAOSRUNE, 5L);
 } else if (variant == Variant.WARPED) {
  recipe.put(ItemID.CHAOSRUNE, 2L);
  recipe.put(ItemID.EARTHRUNE, 5L);
 } else if (variant == Variant.SANGUINESTI || variant == Variant.SANGUINESTI_HOLY) {
  recipe.put(ItemID.BLOODRUNE, 2L);
 } else if (variant == Variant.VENATOR || variant == Variant.VENATOR_ECHO) {
  recipe.put(ItemID.ANCIENT_ESSENCE, 1L);
 } else if (variant == Variant.SCYTHE) {
  recipe.put(ItemID.BLOODRUNE, 2L);
 } else if (variant == Variant.ATES) {
  recipe.put(ItemID.FROZEN_TEAR, 1L);
 } else if (variant.crystal()) {
  recipe.put(ItemID.PRIF_CRYSTAL_SHARD, 1L);
 } else if (variant == Variant.V1b) {
  // Scales accrue fractionally; nothing whole per shot.
 } else if (variant == Variant.EYE_OF_AYAK) {
  // One cast spends one demon tear; a rune-charged eye reconciles at its own Check.
  recipe.put(ItemID.DEMON_TEAR, 1L);
 } else if (variant == Variant.BLOOD_FURY) {
  // One shard covers 10,000 hits (unitsPerPricedItem); each counted melee hit books its 1/10,000flushPending.
  recipe.put(ItemID.BLOOD_SHARD, 1L);
 } else {
  recipe.put(ItemID.DEATHRUNE, 1L);
  recipe.put(ItemID.CHAOSRUNE, 1L);
  recipe.put(ItemID.FIRERUNE, 5L);
  boolean seas = variant == Variant.TRIDENT_SEAS || variant == Variant.TRIDENT_SEAS_ENHANCED;
  recipe.put(seas ? ItemID.COINS : SCALES, seas ? 10L : 1L);
 }
 return recipe;
}

/** Fractional per-use components: [itemId, numerator, denominator] accruing toward one item. */
static long[] fractionOf(Variant variant) {
 if (variant == Variant.V1b) {
  return new long[]{SCALES, 2L, 3L};
 }
 if (variant == Variant.SCYTHE) {
  return new long[]{ItemID.VIAL_BLOOD, 1L, 100L};
 }
 return null;
}

/** Whole units to book now and the remainder to keep, for a fractional component. */
static long[] accrue(long carry, long numerator, long denominator) {
 long total = carry + numerator;
 return new long[]{total / denominator, total % denominator};
}

/**
* Exact cumulative value for a fractional priced item: charges are booked in units of
* {@code 1/perItem} of the item, so each whole item's price is divided without losing its
* remainder to truncation. Returns the value this booking adds.
*/
long carriedValue(Variant variant, int itemId, long units, int price, int perItem) {
 if (units <= 0L || price <= 0 || perItem <= 1) return Math.max(0L, units * Math.max(0, price));
 String key = variant.name() + ":" + itemId;
 long[] carry = valueCarry.computeIfAbsent(key, ignored -> new long[2]);
 long totalUnits = carry[0] + units;
 long ideal = totalUnits * price / perItem;
 long booked = ideal - carry[1];
 if (booked < 1L) booked = 1L;
 carry[0] = totalUnits;
 carry[1] = ideal;
 return booked;
}

/** One cast's priced recipe, or null when a component lacks a supported valuation. */
List<Flow> estimateRecipe(Variant variant, String identity, long now) {
 var recipe = recipeOf(variant);
 if (variant == Variant.EYE_OF_AYAK) recipe = eyeRecipe(eyeRune(identity));
 long[] fraction = fractionOf(variant);
 if (fraction != null) {
  String key = variant.name() + ":" + fraction[0];
  long[] accrued = accrue(chargeCarry.getOrDefault(key, 0L), fraction[1], fraction[2]);
  chargeCarry.put(key, accrued[1]);
  if (accrued[0] > 0L) recipe.put((int) fraction[0], accrued[0]);
 }
 var flows = new ArrayList<Flow>();
 for (Map.Entry<Integer, Long> entry : recipe.entrySet()) {
  int canonicalId = itemManager == null ? -1 : itemManager.canonicalize(entry.getKey());
  ItemComposition composition = canonicalId <= 0 ? null : itemManager.getItemComposition(canonicalId);
  if (composition == null || ModelText.blank(composition.getName())) return null;
  Flow valued = valuation == null ? null : valuation.valueChargeComponent(canonicalId, -entry.getValue(), now);
  int unitPrice = valued == null ? 0 : valued.unitPrice;
  if (unitPrice <= 0) return null;
  // Charge-fed components book in charge units; the priced item covers several, and the
  // value carry keeps every whole item's price exact across those fractional bookings.
  int perItem = unitsPerPricedItem(variant, entry.getKey());
  long units = entry.getValue();
  long value = SafeMath.safeMultiply(-units, unitPrice);
  if (perItem > 1) {
   value = -carriedValue(variant, entry.getKey(), units, unitPrice, perItem);
   unitPrice = Math.max(1, unitPrice / perItem);
  }
  flows.add(new Flow(canonicalId, composition.getName(), -units, unitPrice, value, valued.getPriceSource(), now));
 }
 return flows;
}

/**
* Link one numeric Check response to the item slot that was actually checked. The
* click contains identity only; any other menu action supersedes the pending link.
*/
void armMeasuredChargeCheck(MenuOptionClicked event, String option, String target) {
 checkIntent.clear();
 int rawItemId = chargeWeaponItemId(event);
 Variant itemVariant = supportedVariantForItemId(rawItemId);
 if (itemVariant == null) itemVariant = supportedVariantForItemName(target);
 if (("uncharge".equals(option) || "unload".equals(option) || "empty".equals(option)) && itemVariant != null) {
  // These actions can return loaded components. A later lower Check value
  // cannot be treated as consumed charges across that unmeasured return.
  engine.resetMeasuredChargeReads();
  // The return is the player's own charge investment coming back: an
  // ownership-neutral transfer, never income (owner 2026-09-29).
  markReturnTransfer();
  // Most weapons ask to confirm first, so the components can land long after this short
  // context: keep watching for them (watchReturn) instead of booking them as a gain.
  if (client != null) {
   returnVariant = itemVariant;
   returnUntilTick = client.getTickCount() + RETURN_TICKS;
   returnBase = inventoryCounts();
  }
  return;
 }
 if (!"check".equals(option) || itemVariant == null || !itemVariant.isImplemented()) return;
 String targetIdentity = chargeWeaponTargetIdentity(event, itemVariant, rawItemId);
 if (targetIdentity != null) checkIntent.arm(itemVariant, targetIdentity, client.getTickCount(), 2);
}

/** Each tick before booking: an Uncharge's own components landing make that change a transfer. */
void watchReturn() {
 if (returnVariant == null || client == null) return;
 if (client.getTickCount() > returnUntilTick) {
  returnVariant = null;
  return;
 }
 if (!returnLanded(returnVariant, returnBase, inventoryCounts(), this::itemName)) return;
 markReturnTransfer();
 returnVariant = null;
}

/** The returned charge is the player's own investment coming back: ownership-neutral. */
void markReturnTransfer() {
 engine.markContext(Context.TRANSFER, Math.max(4, config.stabilizationTicks() + 2), GameData.msg("jv"));
}

/** A click that answers a chat dialog (the "Really uncharge?" Yes/No), not a new action. */
static boolean isDialogAnswer(MenuOptionClicked event, String option) {
 return event.getMenuAction() == MenuAction.WIDGET_CONTINUE || "yes".equals(option) || "continue".equals(option);
}

/** True when an item that loads this weapon (its scales, darts or runes) increased. */
static boolean returnLanded(Variant variant, Map<Integer, Long> before, Map<Integer, Long> after,
java.util.function.IntFunction<String> names) {
 for (Map.Entry<Integer, Long> item : after.entrySet()) {
  if (item.getValue() > before.getOrDefault(item.getKey(), 0L)
  && isSupportedLoadComponent(variant, item.getKey(), names.apply(item.getKey()))) return true;
 }
 return false;
}

String itemName(int itemId) {
 ItemComposition composition = itemManager == null ? null : itemManager.getItemComposition(itemId);
 return composition == null ? null : composition.getName();
}

/** Canonical inventory quantities, so noted or placeholder ids compare as the item itself. */
Map<Integer, Long> inventoryCounts() {
 ItemContainer inventory = client == null ? null : client.getItemContainer(InventoryID.INV);
 var counts = new HashMap<Integer, Long>();
 if (inventory == null) return counts;
 for (Item item : inventory.getItems()) {
  if (item == null || item.getId() <= 0 || item.getQuantity() <= 0) continue;
  int id = itemManager == null ? item.getId() : itemManager.canonicalize(item.getId());
  counts.merge(id, (long) item.getQuantity(), Long::sum);
 }
 return counts;
}

/**
* Arm one narrowly-scoped, ownership-neutral load only for a real Use-on-item action
* against an exact supported weapon and a source component whose canonical id and
* item name both match that weapon's recipe.
*/
void armChargeLoadTransfer(MenuOptionClicked event, String target) {
 // On a widget-target click the clicked widget is the target; the item being used is the
 // client's selected widget. Either order loads: scales on the blowpipe, or the blowpipe on them.
 Widget clicked = event.getWidget();
 Widget selected = client == null ? null : client.getSelectedWidget();
 if (event.getMenuAction() != MenuAction.WIDGET_TARGET_ON_WIDGET || clicked == null || selected == null) {
  engine.clearChargeLoadTransfer();
  return;
 }
 boolean weaponClicked = supportedVariantForItemId(clicked.getItemId()) != null;
 Widget weapon = weaponClicked ? clicked : selected;
 Variant variant = supportedVariantForItemId(weapon.getItemId());
 int sourceItemId = canonical((weaponClicked ? selected : clicked).getItemId());
 String name = sourceItemId <= 0 ? null : itemName(sourceItemId);
 if (variant == null || name == null || !isSupportedLoadComponent(variant, sourceItemId, name)) {
  engine.clearChargeLoadTransfer();
  return;
 }
 int ticks = Math.max(4, config.stabilizationTicks() + 2);
 String targetIdentity = weaponIdentity(weapon.getId(), weapon.getIndex(), weapon.getItemId(), variant);
 engine.markChargeLoadTransfer(variant, sourceItemId, name, targetIdentity, ticks);
}

int canonical(int itemId) {
 return itemManager == null || itemId <= 0 ? itemId : itemManager.canonicalize(itemId);
}

/** Resolve the weapon targeted by the menu action, distinct from an item-on-item source. */
static int chargeWeaponItemId(MenuOptionClicked event) {
 if (event == null) return -1;
 int id = event.getId();
 if (supportedVariantForItemId(id) != null) return id;
 int itemId = event.getItemId();
 if (supportedVariantForItemId(itemId) != null) return itemId;
 if (event.getWidget() != null) {
  int widgetItemId = event.getWidget().getItemId();
  if (supportedVariantForItemId(widgetItemId) != null) return widgetItemId;
 }
 return -1;
}

/**
* Same item/container/slot identity for the clicked weapon across Use-on-item and
* Check. Without all location fields, a charge load remains Review only.
*/
static String chargeWeaponTargetIdentity(MenuOptionClicked event, Variant variant, int weaponItemId) {
 if (event == null) return null;
 int widgetId = event.getParam1();
 if (widgetId <= 0 && event.getWidget() != null) widgetId = event.getWidget().getId();
 return weaponIdentity(widgetId, event.getParam0(), weaponItemId, variant);
}

/**
* Container group, slot, item and variant uniquely identify one weapon: the same item in
* inventory and equipment (or two slots in either container) cannot share a baseline, and a
* worn estimate builds the identical identity from the equipment slot.
*/
static String weaponIdentity(int widgetId, int slot, int weaponItemId, Variant variant) {
 int widgetGroup = widgetId >>> 16;
 if (variant == null || weaponItemId <= 0 || slot < 0 || widgetId <= 0 || widgetGroup <= 0) return null;
 return widgetGroup + ":" + slot + ":" + weaponItemId + ":" + variant.name();
}
}
