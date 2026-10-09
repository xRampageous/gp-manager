package com.gpmanager;
import static com.gpmanager.Eb.*;
import static com.gpmanager.Ar.*;
import com.gpmanager.Ar.V;
import lombok.RequiredArgsConstructor;
import java.util.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.widgets.Widget;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.InterfaceID;
/**
* Client-thread intake for the exact Check-to-Check charge contract: a Check click binds a
* short-lived identity, the numeric chat read is the only source of counts, and a Use-on-item
* click arms one narrow load. Local graphics, hitsplats and location clicks book LIKELY estimates.
*/
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ChargeIntake {
final Client client;
final Ix ix;
final GpManagerConfig config;
final Am engine;
/** The single GP Manager valuation authority; null only in narrow synthetic tests. */
final Bl valuation;
/** Fractional component carry per variant+item so whole units book when they accrue. */
final Map<String, Long> chargeCarry = new HashMap<>();
/** Exact cumulative value per fractional component: units consumed and value already booked. */
final Map<String, long[]> valueCarry = new HashMap<>();
int lastCastTick = -1;
/** The blowpipe's loaded dart from its last Check or load; -1 until one says. */
int blowpipeDart = -1;
int lastCastGraphic;
/** The player's spot anims at the last change, by id and start cycle. */
Set<Long> playingAnims = new HashSet<>();
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
/** How long an Uncharge click waits for its returned components: 50 ticks, 30 seconds. */
static final int RETURN_TICKS = 50;
/** A Check command has one physical target and a short inclusive client-tick window. */
Ev ck() { return engine.eb.peek(CK); }
boolean ck(V /* variant */a, String /* id */b, int /* tick */c, int /* ticks */d) {
 engine.eb.drop(CK);
 if (a == null || !a.implemented || Ag.blank(b) || d <= 0) return false;
 Ev e = engine.eb.add(CK, -1, 1L, d, null);
 e.v = a;
 e.id = b;
 e.at = c + d;
 return true;
}
String ck(Ar /* read */a, int /* tick */b) {
 Ev e = ck();
 String /* id */c = e != null && b <= e.at && a != null && a.bookable && a.variant == e.v ? e.id : null;
 engine.eb.drop(CK);
 return c;
}

/** Any unrelated chat/menu between click and numeric response drops the target link. */
void oi() {
 engine.eb.drop(CK);
}

/** Login/hop/profile change: transient baselines and load intents never survive. */
void ags() {
 engine.eb.drop(CK);
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
 blowpipeDart = -1;
 engine.eb.drop(RV);
 lastAttackTick = -1;
 engine.afu();
 engine.oh();
}

/**
* Feed only exact numeric Check messages into the measured charge baseline. Chat is
* deliberately not activity/title evidence. Booking rejects the complete delta on a missing
* supported component valuation or any arithmetic overflow.
*
* @return true when the message was a charge Check read (consumed, even if unbooked)
*/
boolean abt(String /* message */b, long /* now */c) {
 Ar /* read */d = acs(b);
 if (d == null) return false;
 if (d.variant == V.V1b) blowpipeDart = d.dartItemId;
 V /* clicked */e = ck() == null ? null : ck().v;
 // A revenant Check says only "bow", "sceptre" or "chainmace": the clicked weapon names the row.
 if (e != null && e.check != null && e != d.variant && e.check == d.variant.check) d.variant = e;
 String /* targetIdentity */f = ck(d, client.getTickCount());
 if (f == null && d.variant == V.BLOOD_FURY) {
  // The amulet's count arrives on its own, without a Check click: bind it to the
  // worn amulet's neck slot (owner 2026-09-29).
  f = wornBloodFury();
 }
 if (d.variant == V.EYE_OF_AYAK) {
  // No Check click: the line can only mean the worn staff or the pack copy.
  if (f == null) f = eyeIdentity();
  // The line names the mode ("demon tears" or "runes"), stored for that staff (review 2026-10-09).
  eyeRunes.put(f, d.componentCounts.containsKey(ItemID.DEATHRUNE));
 }
 if (f == null) {
  // A value without its fresh Check target cannot distinguish another
  // same-variant weapon; discard any older comparison baseline.
  engine.afu();
  return true;
 }
 if (b.toLowerCase(Locale.ROOT).contains("charged with")) {
  // Any charge wording is a load: the components moved into the weapon, never spend.
  // Armed even on the first read, where no delta exists yet (owner 2026-09-30).
  xfer("m2");
 }
 Cm /* delta */g = engine.abu(d, f, c);
 // Every exact read names its result; quantities still come only from measured decreases.
 switch (g.k) {
  case SAME:
   engine.closeChargeWindow(f);
   return true;
  case LOAD:
   xfer("m2");
   return true;
  case SEED:
  case MISMATCH:
   return true;
  case SPEND:
   break;
 }
 if (ix == null) return true;
 if (d.variant == V.EYE_OF_AYAK) {
  boolean /* rune */i = d.componentCounts.containsKey(ItemID.DEATHRUNE) || d.componentCounts.containsKey(ItemID.CHAOSRUNE);
  if (g.componentDeltas.stream().anyMatch(/* component */j -> (j.getItemId() == ItemID.DEMON_TEAR) == i)) {
   // A mode switch's vanished component is a load or unload, never spend.
   return true;
  }
 }
 var /* losses */k = new ArrayList<Ab>();
 for (Cm.ComponentDelta /* component */l : g.componentDeltas) {
  int /* itemId */m = l.itemId;
  int /* canonicalId */n = ix.canon(m);
  ItemComposition /* composition */o = m <= 0 || n <= 0 ? null : ix.comp(n);
  if (o == null || Ag.blank(o.getName())) return true;
  // Every component's base unit valuation comes from the one shared GP Manager
  // policy: face value, explicit manual override, the RuneLite mapping guard,
  // the world/economy gate and the explicit active market route. Booking rejects
  // the whole measured delta when any component lacks a supported valuation.
  Ab /* valued */p = valuation == null ? null : valuation.akj(n, l.quantityDelta, c);
  int /* unitPrice */q = p == null ? 0 : p.unitPrice;
  // A component measured in fractions of the priced item (a page feeds 20 charges).
  int /* perItem */r = akr(d.variant, m);
  long /* units */t = l.quantityDelta;
  long /* value */u = Ae.agz(t, q);
  if (r > 1 && q > 0) {
   u = -carriedValue(d.variant, m, Math.abs(t), q, r);
   q = Math.max(1, q / r);
  }
  k.add(new Ab(m, o.getName(), t, q, u,
  p == null ? Av.UNPRICED : p.getPriceSource(), c));
 }
 engine.mj(g, d.variant.displayName, k, c, f);
 // The measured read reconciles only this weapon's estimates; another of the same kind keeps
 // its own until its own Check (Codex research 1.2).
 engine.clearPendingEstimates(f);
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
void aks(Skill /* skill */a, int /* tick */b, long /* now */c) {
 if (a == Skill.MAGIC && eyeCandidateTick >= 0 && b - eyeCandidateTick <= 1) {
  if (eyeCandidateAnimation > 0) eyeAnimations.add(eyeCandidateAnimation);
  if (eyeCandidateGraphic > 0) eyeGraphics.add(eyeCandidateGraphic);
  if (eyeCandidateIdentity != null && b != lastAttackTick) {
   // The cast that proved the trigger books once, through the same tick dedupe.
   lastAttackTick = b;
   mt(V.EYE_OF_AYAK, eyeCandidateWeaponId, eyeCandidateIdentity, Au.CAST, c);
  }
  eyeCandidateAnimation = 0;
  eyeCandidateGraphic = 0;
  eyeCandidateTick = -1;
  eyeCandidateWeaponId = 0;
  eyeCandidateIdentity = null;
 }
 boolean /* melee */d = a == Skill.ATTACK || a == Skill.STRENGTH || a == Skill.DEFENCE;
 boolean /* other */e = a == Skill.RANGED || a == Skill.MAGIC;
 if (!d && !e) return;
 if (b != stanceXpTick) {
  stanceXpTick = b;
  meleeXpThisTick = false;
  otherXpThisTick = false;
 }
 meleeXpThisTick |= d;
 otherXpThisTick |= e;
 meleeStanceUntil = otherXpThisTick ? -1 : meleeXpThisTick ? b + 8 : meleeStanceUntil;
 if (d && pendingFuryHitTick == b) {
  // The hit that opened this fight lands before its XP in the same tick.
  pendingFuryHitTick = -1;
  ItemContainer /* worn */f = cont(InventoryID.WORN);
  Item /* amulet */g = f == null ? null : f.getItem(EquipmentInventorySlot.AMULET.getSlotIdx());
  if (g != null && g.getId() == ItemID.BLOOD_AMULET) {
   mt(V.BLOOD_FURY, g.getId(), wornBloodFury(), rw(V.BLOOD_FURY), c);
  }
 }
}

/** Whether the recent XP pattern proves the player is meleeing right now. */
boolean furyMelee(int /* tick */a) {
 return a <= meleeStanceUntil;
}

/**
* Uncharging Bryophyta's staff destroys the nature runes it holds (OSRS Wiki; "You remove 1000
* charges from your staff, leaving it empty."): those charges are spent, one rune each.
*/
void emptied(long /* charges */a, long /* now */b) {
 engine.mn("bryophyta's staff", V.BRYOPHYTA.displayName, Au.CAST, List.of(valuation.akj(ItemID.NATURERUNE, -a, b)), b);
}

/** The doses a chug drank this tick, booked as one row on the next game tick. */
final List<Ab> chugged = new ArrayList<>();

/**
* A chug drinks one dose of every potion in the chugging barrel, never touching the inventory.
* The game names each one, as its one-dose potion: "You have 3 doses of Saradomin brew(1) left in
* your barrel." or "You have finished all doses of ..." (OSRS Wiki; Dude Where's My Stuff).
*/
void chug(String /* potion */a, long /* now */b) {
 for (var /* match */c : ix.find(a)) {
  if (c.getName().equalsIgnoreCase(a)) {
   chugged.add(valuation.akj(c.getId(), -1L, b));
   return;
  }
 }
}

/** Each tick, the doses drunk from the barrel are one Drank row (owner 2026-10-09). */
void drank(long /* now */a) {
 if (chugged.isEmpty()) return;
 engine.chug(new ArrayList<>(chugged), a);
 chugged.clear();
}

/** A client item container, or null without a client. */
ItemContainer cont(int /* containerId */a) {
 return client == null ? null : client.getItemContainer(a);
}

/** Marks the next item move as a neutral charge transfer with this message. */
void xfer(String /* messageKey */a) {
 engine.markContext(Aj.TRANSFER, Math.max(4, config.stabilizationTicks() + 2), Ak.msg(a));
}

/** That staff's charging mode; unknown means tears (the default charge type). */
boolean eyeRune(String /* identity */a) {
 return Boolean.TRUE.equals(eyeRunes.get(a));
}

/** The eye's identity for lines that arrive with no Check click: the worn staff, else the pack. */
String eyeIdentity() {
 ItemContainer /* worn */a = cont(InventoryID.WORN);
 if (a != null) {
  Item /* weapon */b = a.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
  if (b != null && aja(b.getId()) == V.EYE_OF_AYAK) {
   return akz(V.EYE_OF_AYAK, b.getId());
  }
 }
 ItemContainer /* inventory */c = cont(InventoryID.INV);
 if (c == null) return null;
 for (int /* slot */d = 0; d < c.size(); d++) {
  Item /* item */e = c.getItem(d);
  if (e != null && aja(e.getId()) == V.EYE_OF_AYAK) {
   return (InterfaceID.Inventory.ITEMS >>> 16) + ":" + d + ":" + e.getId() + ":" + V.EYE_OF_AYAK.name();
  }
 }
 return null;
}

/** The worn amulet's identity for its click-free count messages; null when not worn. */
String wornBloodFury() {
 ItemContainer /* worn */a = cont(InventoryID.WORN);
 if (a == null) return null;
 int /* slot */b = EquipmentInventorySlot.AMULET.getSlotIdx();
 Item /* amulet */c = a.getItem(b);
 if (c == null || c.getId() != ItemID.BLOOD_AMULET) return null;
 return (InterfaceID.Wornitems.UNIVERSE >>> 16) + ":" + b + ":" + c.getId() + ":" + V.BLOOD_FURY.name();
}

/** Every menu click: supersede a pending Check link, then arm Check / unload / load intents. */
void onMenuOptionClicked(MenuOptionClicked /* event */b, String /* option */c, String /* target */d) {
 // Answering the Uncharge prompt keeps the return watch; any other action ends it, so a
 // declined prompt never turns later loot of the same runes or scales into a transfer.
 if (!isDialogAnswer(b, c)) engine.eb.drop(RV);
 ld(b, c, d);
 lc(b, c);
 // The memoirs are charged at the Old Memorial: the runes move into the book (owner 1.2).
 if ("inspect".equals(c) && d.contains("old memorial")) engine.yx(V.MEMOIRS, ItemID.LAWRUNE, "law rune", null, LOAD_TICKS);
 if (!lh(c)) return;
 int /* itemId */e = ns(b);
 V v = aja(e);
 if (v != V.ATES && v != V.MEMOIRS) return;
 String /* identity */f = nt(b, v, e);
 if (f != null) mt(v, e, f, Au.SUPPLIES, Ae.now());
}

/** A pendant or memoirs teleport click spends one charge; bank, inventory and interface options are plumbing. */
static boolean lh(String /* option */a) {
 String /* name */b = a == null ? "" : a.replace('\u2019', '\'');
 if (b.startsWith("the ")) b = b.substring(4);
 return LOCATIONS.contains(b);
}

/** The pendant's six teleport locations, normalized like menu options. */
static final Set<String> LOCATIONS = Set.of("darkfrost", "twilight temple", "ralos' rise",
"north aldarin", "kastori", "nemus retreat", "lunch by the lancalliums", "fisher's flute", "history and hearsay",
"jewellery of jubilation", "a dark disposition");
/**
* A local cast or shot graphic books this use's pinned recipe as a LIKELY estimate; the next
* measured Check reconciles it at the captured prices. Any unpriced component fails the whole
* estimate closed. A repeated graphic in one tick is one use.
*/
/**
* Spot anims play on for several ticks and the player can hold several, so only one that was not
* playing at the last change is a new cast.
*/
void spotAnims(Iterable<ActorSpotAnim> /* anims */a, long /* now */b) {
 var /* playing */c = new HashSet<Long>();
 for (ActorSpotAnim /* anim */d : a) {
  if (d == null) continue;
  long /* key */e = (long) d.getId() << 32 | d.getStartCycle() & 0xFFFFFFFFL;
  if (c.add(e) && !playingAnims.contains(e)) ach(d.getId(), b);
 }
 playingAnims = c;
}

void ach(int /* graphicId */b, long /* now */c) {
 Item /* weapon */d = ala();
 V /* variant */e = d == null ? null : aja(d.getId());
 if (e == null || b <= 0) return;
 boolean /* eye */f = e == V.EYE_OF_AYAK;
 if (!wa(e, b) && !(f && eyeGraphics.contains(b))) {
  if (f) {
   // The eye's cast trigger is pinned in no table: learn it from the magic XP every
   // cast drops (owner 2026-09-29), then each cast books one tear like a spell.
   eyeCandidateGraphic = b;
   eyeCandidateTick = client.getTickCount();
   eyeCandidateWeaponId = d.getId();
   eyeCandidateIdentity = akz(V.EYE_OF_AYAK, d.getId());
  }
  return;
 }
 int /* tick */g = client.getTickCount();
 if (g == lastCastTick && b == lastCastGraphic || f && g == lastAttackTick) return;
 lastCastTick = g;
 lastCastGraphic = b;
 if (f) lastAttackTick = g;
 mt(e, d.getId(), akz(e, d.getId()), rw(e), c);
}

/**
* A landed hit on the target. The scythe books one charge per damaging hit; the blowpipe books
* any shot while its attack animation is live, because a 2-tick attack restarts the same
* animation and never fires a second AnimationChanged. At most once per tick.
*/
void aci(int /* damage */b, long /* now */c) {
 Item /* weapon */d = ala();
 V /* variant */e = d == null ? null : aja(d.getId());
 // Blood fury: one charge per melee hit. Combat XP proves melee for every weapon
 // (owner 2026-09-29); a hit whose XP has not landed yet this tick stays pending
 // until that same tick's XP confirms the stance (owner 2026-10-01).
 if (b > 0) {
  int /* tick */f = client.getTickCount();
  ItemContainer /* worn */g = client.getItemContainer(InventoryID.WORN);
  Item /* amulet */h = g == null ? null : g.getItem(EquipmentInventorySlot.AMULET.getSlotIdx());
  if (h != null && h.getId() == ItemID.BLOOD_AMULET) {
   if (furyMelee(f)) mt(V.BLOOD_FURY, h.getId(), wornBloodFury(), rw(V.BLOOD_FURY), c);
   else pendingFuryHitTick = f;
  }
 }
 if (e == null) return;
 // Each landed splat books: a multi-target swing is several hits, not one attack.
 boolean /* scythe */i = e == V.SCYTHE && b > 0;
 Actor /* local */j = client.getLocalPlayer();
 boolean /* shot */k = e == V.V1b && j != null && j.getAnimation() == lm(e);
 if (!i && !k) return;
 if (k) {
  int /* tick */l = client.getTickCount();
  if (l == lastAttackTick) return;
  lastAttackTick = l;
 }
 mt(e, d.getId(), akz(e, d.getId()), rw(e), c);
}

/** A local attack animation books the pinned per-attack charge for crystal weapons. */
void acj(int /* animationId */a, long /* now */b) {
 Item /* weapon */c = ala();
 V /* variant */d = c == null ? null : aja(c.getId());
 if (d == null) return;
 if (d == V.EYE_OF_AYAK) {
  if (!eyeAnimations.contains(a)) {
   // Candidate cast animation; the same tick's magic XP proves it, like the graphic.
   eyeCandidateAnimation = a;
   eyeCandidateTick = client.getTickCount();
   eyeCandidateWeaponId = c.getId();
   eyeCandidateIdentity = akz(V.EYE_OF_AYAK, c.getId());
   return;
  }
 } else if (!lg(d, a)) {
  return;
 }
 int /* tick */e = client.getTickCount();
 if (e == lastAttackTick) return;
 lastAttackTick = e;
 mt(d, c.getId(), akz(d, c.getId()), rw(d), b);
}

Item ala() {
 ItemContainer /* worn */a = client.getItemContainer(InventoryID.WORN);
 return a == null ? null : a.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
}

void mt(V /* variant */a, int /* weaponId */b, String /* identity */c, Au /* kind */d, long /* now */e) {
 // A hit is gameplay: resume the pauses gameplay ends (logout/idle/recovery) and start
 // tracking when automatic startup is on, before booking — so the very first attack after
 // logging in counts instead of arriving while the session is still catching up.
 engine.resume(e, Ed.LIFECYCLE, Ed.IDLE, Ed.RECOVERY);
 if (!engine.live()) engine.ait(e);
 List<Ab> /* recipe */f = rx(a, c, e);
 if (f == null || f.isEmpty()) return;
 Ac /* receipt */g = engine.mn(c, a.displayName, d, f, e);
 if (g != null) g.aym = b;
}

/** A family may cast with more than one local graphic (Sanguinesti's Justiciar variant). */
static boolean wa(V /* variant */a, int /* graphicId */b) {
 return b > 0 && Arrays.stream(a.graphics).anyMatch(/* id */c -> c == b);
}

/** The attack animation pinned for per-shot estimates; 0 means none. */
static int lm(V /* variant */a) {
 return a == V.V1b ? 5061 : 0;
}

/** The attack animations pinned for per-attack estimates (d20); false means none. */
static boolean lg(V /* variant */a, int /* animationId */b) {
 return Arrays.stream(a.animations).anyMatch(/* id */c -> c == b);
}

/** The action a variant's estimate represents: a built-in cast, a bow shot or a melee use. */
static Au rw(V /* variant */a) {
 return a.action;
}

/** The equipment-side identity a worn Check uses: container group, weapon slot, item, variant. */
static String akz(V /* variant */a, int /* weaponItemId */b) {
 int /* widgetId */c = InterfaceID.Wornitems.UNIVERSE;
 return (c >>> 16) + ":" + EquipmentInventorySlot.WEAPON.getSlotIdx() + ":" + b + ":" + a.name();
}

/** The eye's mode-aware per-cast recipe, keyed off its own charge line's wording (owner 2026-09-30). */
static Map<Integer, Long> eyeRecipe(boolean /* runeCharged */a) {
 var /* recipe */b = new LinkedHashMap<Integer, Long>();
 if (a) {
  b.put(ItemID.DEATHRUNE, 2L);
  b.put(ItemID.CHAOSRUNE, 1L);
 } else {
  b.put(ItemID.DEMON_TEAR, 1L);
 }
 return b;
}

/** One cast's pinned component quantities per family (d20), as a copy the caller may extend. */
static Map<Integer, Long> recipeOf(V /* variant */a) {
 return new LinkedHashMap<>(a.recipe);
}

/** Fractional per-use components: [itemId, numerator, denominator] accruing toward one item. */
static long[] tw(V /* variant */a) {
 return a.fraction;
}

/**
* Darts a blowpipe shot spends, as {numerator, denominator}, by the cape slot (OSRS wiki): an
* assembler or Dizana's quiver saves 80%, the accumulator 72%, the attractor 60%; none saves none.
*/
static long[] dartLoss(String /* capeName */a) {
 String /* cape */b = a == null ? "" : a.toLowerCase(Locale.ROOT);
 if (b.contains("assembler") || b.contains("dizana")) return new long[] {1L, 5L};
 if (b.contains("accumulator")) return new long[] {7L, 25L};
 if (b.contains("attractor")) return new long[] {2L, 5L};
 return new long[] {1L, 1L};
}

String wornCapeName() {
 ItemContainer /* worn */a = cont(InventoryID.WORN);
 Item /* cape */b = a == null ? null : a.getItem(EquipmentInventorySlot.CAPE.getSlotIdx());
 return b == null || b.getId() <= 0 ? "" : itemName(b.getId());
}

/** Whole units to book now and the remainder to keep, for a fractional component. */
static long[] accrue(long /* carry */a, long /* numerator */b, long /* denominator */c) {
 long /* total */d = a + b;
 return new long[]{d / c, d % c};
}

/**
* Exact cumulative value for a fractional priced item: charges are booked in units of
* {@code 1/perItem} of the item, so each whole item's price is divided without losing its
* remainder to truncation. Returns the value this booking adds.
*/
long carriedValue(V /* variant */a, int /* itemId */b, long /* units */c, int /* price */d, int /* perItem */e) {
 if (c <= 0L || d <= 0 || e <= 1) return Math.max(0L, c * Math.max(0, d));
 // Only the new units are priced, at today's quote; earlier use keeps the price it was booked
 // at (Codex review 1.2). The sub-gp remainder carries, and so does any 1 gp floor overpaid.
 long[] /* carry */f = valueCarry.computeIfAbsent(a.name() + ":" + b, /* ignored */g -> new long[1]);
 long /* total */h = f[0] + c * d;
 long /* booked */i = Math.max(1L, h / e);
 f[0] = h - i * e;
 return i;
}

/** One cast's priced recipe, or null when a component lacks a supported valuation. */
List<Ab> rx(V /* variant */b, String /* identity */c, long /* now */d) {
 var /* recipe */e = recipeOf(b);
 if (b == V.EYE_OF_AYAK) e = eyeRecipe(eyeRune(c));
 long[] /* fraction */f = tw(b);
 if (f != null) {
  String /* key */g = b.name() + ":" + f[0];
  long[] /* arf */h = accrue(chargeCarry.getOrDefault(g, 0L), f[1], f[2]);
  chargeCarry.put(g, h[1]);
  if (h[0] > 0L) e.put((int) f[0], h[0]);
 }
 if (b == V.V1b && blowpipeDart > 0) {
  // Owner 1.1: each shot spends its dart unless an Ava's device saves it; the expected share
  // accrues like the scales, and a Check reconciles it.
  long[] /* loss */i = dartLoss(wornCapeName());
  String /* key */j = "V1b:" + blowpipeDart;
  long[] /* darts */k = accrue(chargeCarry.getOrDefault(j, 0L), i[0], i[1]);
  chargeCarry.put(j, k[1]);
  if (k[0] > 0L) e.put(blowpipeDart, k[0]);
 }
 var /* flows */l = new ArrayList<Ab>();
 for (Map.Entry<Integer, Long> /* entry */m : e.entrySet()) {
  int /* canonicalId */n = ix == null ? -1 : ix.canon(m.getKey());
  ItemComposition /* composition */o = n <= 0 ? null : ix.comp(n);
  if (o == null || Ag.blank(o.getName())) return null;
  Ab /* valued */p = valuation == null ? null : valuation.akj(n, -m.getValue(), d);
  int /* unitPrice */q = p == null ? 0 : p.unitPrice;
  if (q <= 0) return null;
  // Charge-fed components book in charge units; the priced item covers several, and the
  // value carry keeps every whole item's price exact across those fractional bookings.
  int /* perItem */r = akr(b, m.getKey());
  long /* units */t = m.getValue();
  long /* value */u = Ae.agz(-t, q);
  if (r > 1) {
   u = -carriedValue(b, m.getKey(), t, q, r);
   q = Math.max(1, q / r);
  }
  l.add(new Ab(n, o.getName(), -t, q, u, p.getPriceSource(), d));
 }
 return l;
}

/**
* Link one numeric Check response to the item slot that was actually checked. The
* click contains identity only; any other menu action supersedes the pending link.
*/
void ld(MenuOptionClicked /* event */b, String /* option */c, String /* target */d) {
 engine.eb.drop(CK);
 int /* aps */f = ns(b);
 V /* amb */g = aja(f);
 if (g == null) g = ajb(d);
 if (("uncharge".equals(c) || "unload".equals(c) || "empty".equals(c)) && g != null) {
  // These actions can return loaded components. A later lower Check value
  // cannot be treated as consumed charges across that unmeasured return.
  engine.afu();
  // The return is the player's own charge investment coming back: an
  // ownership-neutral transfer, never income (owner 2026-09-29).
  markReturnTransfer();
  // Most weapons ask to confirm first, so the components can land long after this short
  // context: keep watching for them (watchReturn) instead of booking them as a gain.
  if (client != null) {
   Ev e = engine.eb.put(RV, -1, 1L, RETURN_TICKS, null);
   e.v = g;
   e.at = client.getTickCount() + RETURN_TICKS;
   e.data = inventoryCounts();
  }
  return;
 }
 if (!"check".equals(c) || g == null || !g.implemented) return;
 String /* targetIdentity */h = nt(b, g, f);
 if (h != null) ck(g, h, client.getTickCount(), 2);
}

/** Each tick before booking: an Uncharge's own components landing make that change a transfer. */
void watchReturn() {
 Ev e = engine.eb.peek(RV);
 if (e == null || client == null) return;
 if (client.getTickCount() > e.at) {
  engine.eb.drop(RV);
  return;
 }
 if (!returnLanded(e.v, (Map<Integer, Long>) e.data, inventoryCounts(), this::itemName)) return;
 markReturnTransfer();
 engine.eb.drop(RV);
}

/** The returned charge is the player's own investment coming back: ownership-neutral. */
void markReturnTransfer() {
 xfer("jv");
}

/** A click that answers a chat dialog (the "Really uncharge?" Yes/No), not a new action. */
static boolean isDialogAnswer(MenuOptionClicked /* event */a, String /* option */b) {
 return a.getMenuAction() == MenuAction.WIDGET_CONTINUE || "yes".equals(b) || "continue".equals(b);
}

/** True when an item that loads this weapon (its scales, darts or runes) increased. */
static boolean returnLanded(V /* variant */a, Map<Integer, Long> /* before */b, Map<Integer, Long> /* after */c,
java.util.function.IntFunction<String> /* names */d) {
 for (Map.Entry<Integer, Long> /* item */e : c.entrySet()) {
  if (e.getValue() > b.getOrDefault(e.getKey(), 0L)
  && xq(a, e.getKey(), d.apply(e.getKey()))) return true;
 }
 return false;
}

String itemName(int /* itemId */a) {
 ItemComposition /* composition */b = ix == null ? null : ix.comp(a);
 return b == null ? null : b.getName();
}

/** Canonical inventory quantities, so noted or placeholder ids compare as the item itself. */
Map<Integer, Long> inventoryCounts() {
 ItemContainer /* inventory */a = cont(InventoryID.INV);
 var /* counts */b = new HashMap<Integer, Long>();
 if (a == null) return b;
 for (Item /* item */c : a.getItems()) {
  if (c == null || c.getId() <= 0 || c.getQuantity() <= 0) continue;
  int /* id */d = ix == null ? c.getId() : ix.canon(c.getId());
  b.merge(d, (long) c.getQuantity(), Long::sum);
 }
 return b;
}

/** Room to type a charge amount; any other click still ends the load. */
static final int LOAD_TICKS = 25;
/**
* Arm one narrowly-scoped, ownership-neutral load only for a real Use-on-item action
* against an exact supported weapon and a source component whose canonical id and
* item name both match that weapon's recipe.
*/
void lc(MenuOptionClicked /* event */b, String /* option */c) {
 // Answering the "How many charges?" prompt is part of the same load (owner 1.2).
 if (isDialogAnswer(b, c)) return;
 // On a widget-target click the clicked widget is the target; the item being used is the
 // client's selected widget. Either order loads: scales on the blowpipe, or the blowpipe on them.
 Widget /* clicked */d = b.getWidget();
 Widget /* selected */e = client == null ? null : client.getSelectedWidget();
 V /* used */f = e == null ? null : aja(e.getItemId());
 if (b.getMenuAction() == MenuAction.WIDGET_TARGET_ON_GAME_OBJECT && f != null) {
  // A weapon used on an object (the scythe on the Vyre well) loads its recipe from the pack.
  int /* part */g = f.recipe.isEmpty() ? (int) f.fraction[0] : f.recipe.keySet().iterator().next();
  engine.yx(f, g, String.valueOf(g), weaponIdentity(e.getId(), e.getIndex(),
  e.getItemId(), f), LOAD_TICKS);
  return;
 }
 if (b.getMenuAction() != MenuAction.WIDGET_TARGET_ON_WIDGET || d == null || e == null) {
  engine.oh();
  return;
 }
 boolean /* weaponClicked */h = aja(d.getItemId()) != null;
 Widget /* weapon */i = h ? d : e;
 V /* variant */j = aja(i.getItemId());
 int /* aqo */k = canonical((h ? e : d).getItemId());
 String /* name */l = k <= 0 ? null : itemName(k);
 if (j == null || l == null || !xq(j, k, l)) {
  engine.oh();
  return;
 }
 if (j == V.V1b && Ar.DARTS.containsValue(k)) blowpipeDart = k;
 String /* targetIdentity */m = weaponIdentity(i.getId(), i.getIndex(), i.getItemId(), j);
 engine.yx(j, k, l, m, LOAD_TICKS);
}

int canonical(int /* itemId */a) {
 return ix == null || a <= 0 ? a : ix.canon(a);
}

/** Resolve the weapon targeted by the menu action, distinct from an item-on-item source. */
static int ns(MenuOptionClicked /* event */a) {
 if (a == null) return -1;
 int /* id */b = a.getId();
 if (aja(b) != null) return b;
 int /* itemId */c = a.getItemId();
 if (aja(c) != null) return c;
 if (a.getWidget() != null) {
  int /* widgetItemId */d = a.getWidget().getItemId();
  if (aja(d) != null) return d;
 }
 return -1;
}

/**
* Same item/container/slot identity for the clicked weapon across Use-on-item and
* Check. Without all location fields, a charge load remains Review only.
*/
static String nt(MenuOptionClicked /* event */a, V /* variant */b, int /* weaponItemId */c) {
 if (a == null) return null;
 int /* widgetId */d = a.getParam1();
 if (d <= 0 && a.getWidget() != null) d = a.getWidget().getId();
 return weaponIdentity(d, a.getParam0(), c, b);
}

/**
* Container group, slot, item and variant uniquely identify one weapon: the same item in
* inventory and equipment (or two slots in either container) cannot share a baseline, and a
* worn estimate builds the identical identity from the equipment slot.
*/
static String weaponIdentity(int /* widgetId */a, int /* slot */b, int /* weaponItemId */c, V /* variant */d) {
 int /* arb */e = a >>> 16;
 if (d == null || c <= 0 || b < 0 || a <= 0 || e <= 0) return null;
 return e + ":" + b + ":" + c + ":" + d.name();
}
}
