package com.gpmanager;
import static com.gpmanager.GameData.msg;
import java.util.*;
import java.util.regex.*;
import lombok.*;
import net.runelite.api.gameval.ItemID;
import static java.util.Locale.*;
import static java.util.Collections.*;
/**
* One explicit weapon Check response, expressed as its measured component stock.
* It does not infer depletion from combat events, menu intent, or probabilities.
*/
@AllArgsConstructor
class ChargeRead {
static final int SCALES = ItemID.SNAKEBOSS_SCALE;
static final Pattern TRIDENT_CHECK = GameData.pattern("ayz");
static final Pattern BLOWPIPE_CHECK = GameData.pattern("ays");
static final Pattern TYPED_DARTS = GameData.pattern("aza");
static final Pattern RUNE_CHARGES = GameData.pattern("ayx");
static final Pattern ATES_CHECK = GameData.pattern("ayr");
static final Pattern TOME_CHECK = GameData.pattern("ayy");
static final Pattern CRYSTAL_CHECK = GameData.pattern("ayw");
static final Pattern BLOOD_FURY_CHECK = GameData.pattern("aya");
static final Pattern EYE_OF_AYAK_CHECK = GameData.pattern("ayc");
static final Pattern EYE_RUNE_CHECK = GameData.pattern("ayd");
static final Map<String, Integer> DARTS = new LinkedHashMap<>();
static {
 for (String[] row : GameData.rows("d15"))
 DARTS.put(row[0], Integer.parseInt(row[1]));
}

@Getter
@AllArgsConstructor
enum Variant {
 TRIDENT_SEAS("Trident of the Seas", true, new int[]{ItemID.TOTS, ItemID.TOTS_CHARGED, ItemID.TOTS_UNCHARGED,
  ItemID.TOTS_CHARGED_ORN, ItemID.TOTS_ORN, ItemID.TOTS_UNCHARGED_ORN}), TRIDENT_SWAMP(msg("cv"), true,
 new int[]{ItemID.TOXIC_TOTS_CHARGED, ItemID.TOXIC_TOTS_UNCHARGED,
  ItemID.TOXIC_TOTS_CHARGED_ORN, ItemID.TOXIC_TOTS_UNCHARGED_ORN}), TRIDENT_SWAMP_ENHANCED(msg("cw"), true,
 new int[]{ItemID.TOXIC_TOTS_I_CHARGED, ItemID.TOXIC_TOTS_I_UNCHARGED,
  ItemID.TOXIC_TOTS_I_CHARGED_ORN, ItemID.TOXIC_TOTS_I_UNCHARGED_ORN}), TRIDENT_SEAS_ENHANCED(msg("cx"), true,
 new int[]{ItemID.TOTS_I_CHARGED, ItemID.TOTS_I_UNCHARGED, ItemID.TOTS_I_CHARGED_ORN, ItemID.TOTS_I_UNCHARGED_ORN}),
 V1b("Toxic blowpipe", true, new int[]{ItemID.TOXIC_BLOWPIPE, ItemID.TOXIC_BLOWPIPE_LOADED,
  ItemID.TOXIC_BLOWPIPE_ORNAMENT, ItemID.TOXIC_BLOWPIPE_LOADED_ORNAMENT}),
 /** Rune-fed built-in casts: the Check reads charges; each cast consumes the pinned runes. */
 SHADOW("Tumeken's shadow", true, new int[]{ItemID.TUMEKENS_SHADOW, ItemID.TUMEKENS_SHADOW_UNCHARGED}),
 SANGUINESTI("Sanguinesti staff", true, new int[]{ItemID.SANGUINESTI_STAFF, ItemID.SANGUINESTI_STAFF_UNCHARGED}),
 SANGUINESTI_HOLY(msg("m1"), true, new int[]{ItemID.SANGUINESTI_STAFF_OR, ItemID.SANGUINESTI_STAFF_UNCHARGED_OR}),
 WARPED("Warped sceptre", true, new int[]{ItemID.WARPED_SCEPTRE, ItemID.WARPED_SCEPTRE_UNCHARGED}),
 VENATOR("Venator bow", true, new int[]{ItemID.VENATOR_BOW, ItemID.VENATOR_BOW_UNCHARGED}),
 /** The ornament-kit variant reads as its own bow. */
 VENATOR_ECHO("Echo venator bow", true, new int[]{ItemID.VENATOR_BOW_ORNAMENT, ItemID.VENATOR_BOW_ORNAMENT_UNCHARGED}),
 /** Tear-fed: one demon tear is one charge; its charge/Check line reports the balance. */
 EYE_OF_AYAK("Eye of Ayak", true, new int[]{ItemID.EYE_OF_AYAK, ItemID.EYE_OF_AYAK_UNCHARGED}),
 /** Estimate-only until its Check mapping lands. */
 SCYTHE("Scythe of vitur", false, new int[]{ItemID.SCYTHE_OF_VITUR, ItemID.SCYTHE_OF_VITUR_UNCHARGED,
  ItemID.SCYTHE_OF_VITUR_OR, ItemID.SCYTHE_OF_VITUR_UNCHARGED_OR,
  ItemID.SCYTHE_OF_VITUR_BL, ItemID.SCYTHE_OF_VITUR_UNCHARGED_BL}),
 /** Untradeable, tear-fed teleport charges. */
 ATES("Pendant of Ates", true, new int[]{ItemID.PENDANT_OF_ATES, ItemID.PENDANT_OF_ATES_EMPTY}),
 /** Page-fed spell shields: one page gives 20 charges. */
 V3("Tome of fire", true, new int[]{ItemID.TOME_OF_FIRE, ItemID.TOME_OF_FIRE_UNCHARGED}),
 /** Shard-fed crystal weapons: one shard gives 100 charges. */
 CRYSTAL_BOW("Crystal bow", true, new int[]{ItemID.CRYSTAL_BOW, ItemID.CRYSTAL_BOW_INACTIVE, ItemID.CRYSTAL_BOW_2500}),
 CRYSTAL_HALBERD("Crystal halberd", true,
 new int[]{ItemID.CRYSTAL_HALBERD, ItemID.CRYSTAL_HALBERD_INACTIVE, ItemID.CRYSTAL_HALBERD_2500}),
 BOWFA("Bow of Faerdhinen", true, new int[]{ItemID.BOW_OF_FAERDHINEN, ItemID.BOW_OF_FAERDHINEN_INACTIVE}),
 /** Shard-fed blade: one charge per attack, hit or miss; the Check text is not pinned yet. */
 SAELDOR("Blade of Saeldor", false, new int[]{ItemID.BLADE_OF_SAELDOR, ItemID.BLADE_OF_SAELDOR_INACTIVE}),
 /** Blood-shard amulet: one charge per successful melee hit, whether it heals or not. */
 BLOOD_FURY("Amulet of blood fury", true, new int[]{ItemID.BLOOD_AMULET});
 final String displayName;
 final boolean implemented;
 @Getter(AccessLevel.NONE)
 final int[] itemIds;
 boolean trident() {
  return name().startsWith("TRIDENT");
 }
 /** A rune-fed built-in cast whose Check reads charges. */
 boolean chargeFed() {
  return this == SHADOW || this == WARPED || this == VENATOR || this == VENATOR_ECHO || name().startsWith("SANGUINESTI");
 }
 boolean crystal() {
  return name().startsWith("CRYSTAL_") || this == BOWFA || this == SAELDOR;
 }
}

final Variant variant;
final Map<Integer, Long> componentCounts;
/** Returns -1 when a Check explicitly reported no darts or had an unknown type. */
final int dartItemId;
final boolean bookable;
/**
* Parse a pinned Check-message shape. Returns {@code null} for unrelated chat.
* Recognized but unsupported/unknown variants return a non-bookable read so a
* tracker can clear an older baseline safely.
*/
static ChargeRead parseCheckMessage(String rawMessage) {
 String message = stripTags(rawMessage);
 Variant surface = message == null ? null : surface(message.toLowerCase(ROOT));
 if (surface == null) return null;
 // A recognizably malformed Check surface resets the caller's old baseline
 // rather than carrying stale evidence forward.
 var components = new LinkedHashMap<Integer, Long>();
 int dartId = -1;
 try {
  Matcher trident = TRIDENT_CHECK.matcher(message);
  Matcher blowpipe = BLOWPIPE_CHECK.matcher(message);
  if (trident.matches() && surface.implemented) {
   long charges = trident.group(3) != null ? parseCount(trident.group(3)) : trident.group(4) != null ? 1L : 0L;
   components.put(ItemID.DEATHRUNE, charges);
   components.put(ItemID.CHAOSRUNE, charges);
   components.put(ItemID.FIRERUNE, Math.multiplyExact(charges, 5L));
   if (surface == Variant.TRIDENT_SEAS || surface == Variant.TRIDENT_SEAS_ENHANCED) {
    components.put(ItemID.COINS, Math.multiplyExact(charges, 10L));
   } else {
    components.put(SCALES, charges);
   }
  } else if (blowpipe.matches()) {
   components.put(SCALES, parseCount(blowpipe.group(2)));
   String dartPart = blowpipe.group(1).trim();
   Matcher darts = TYPED_DARTS.matcher(dartPart);
   if (!"none".equalsIgnoreCase(dartPart)) {
    dartId = darts.matches() ? DARTS.getOrDefault(normalizeItemName(darts.group(1)), -1) : -1;
    if (dartId < 0) return unsupported(surface);
    components.put(dartId, parseCount(darts.group(2)));
   }
  } else if (surface != null && surface.chargeFed()) {
   Matcher charges = RUNE_CHARGES.matcher(message);
   if (charges.matches()) {
    long count = charges.group(2) != null ? parseCount(charges.group(2)) : charges.group(3) != null ? 1L : 0L;
    if (surface == Variant.SHADOW) {
     components.put(ItemID.SOULRUNE, Math.multiplyExact(count, 2L));
     components.put(ItemID.CHAOSRUNE, Math.multiplyExact(count, 5L));
    } else if (surface == Variant.WARPED) {
     components.put(ItemID.CHAOSRUNE, Math.multiplyExact(count, 2L));
     components.put(ItemID.EARTHRUNE, Math.multiplyExact(count, 5L));
    } else if (surface == Variant.VENATOR || surface == Variant.VENATOR_ECHO) {
     components.put(ItemID.ANCIENT_ESSENCE, count);
    } else {
     components.put(ItemID.BLOODRUNE, Math.multiplyExact(count, 2L));
    }
   }
  } else if (surface == Variant.ATES) {
   Matcher ates = ATES_CHECK.matcher(message);
   if (ates.matches()) {
    components.put(ItemID.FROZEN_TEAR, ates.group(1) != null ? parseCount(ates.group(1)) : ates.group(2) != null ? 1L : 0L);
   }
  } else if (surface == Variant.V3) {
   Matcher tome = TOME_CHECK.matcher(message);
   if (tome.matches()) {
    components.put(ItemID.WINT_BURNT_PAGE, tome.group(1) != null ? parseCount(tome.group(1))
    : tome.group(2) != null ? 1L : 0L);
   }
  } else if (surface == Variant.BLOOD_FURY) {
   Matcher fury = BLOOD_FURY_CHECK.matcher(message);
   if (fury.matches()) components.put(ItemID.BLOOD_SHARD, parseCount(fury.group(1)));
  } else if (surface == Variant.EYE_OF_AYAK) {
   Matcher eye = EYE_OF_AYAK_CHECK.matcher(message);
   Matcher runes = EYE_RUNE_CHECK.matcher(message);
   if (eye.matches()) {
    components.put(ItemID.DEMON_TEAR, parseCount(eye.group(1)));
   } else if (runes.matches()) {
    long charges = parseCount(runes.group(1));
    components.put(ItemID.DEATHRUNE, Math.multiplyExact(charges, 2L));
    components.put(ItemID.CHAOSRUNE, charges);
   }
  } else if (surface != null && surface.crystal()) {
   Matcher crystal = CRYSTAL_CHECK.matcher(message);
   if (crystal.matches()) {
    components.put(ItemID.PRIF_CRYSTAL_SHARD, crystal.group(2) != null ? parseCount(crystal.group(2))
    : crystal.group(3) != null ? 1L : 0L);
   }
  }
  if (!components.isEmpty()) return new ChargeRead(surface, unmodifiableMap(components), dartId, true);
 } catch (NumberFormatException | ArithmeticException malformed) {
  // The count is syntax-checked; this is a value outside the supported range.
 }
 return unsupported(surface);
}

/** The Check surface a lowercase chat line belongs to, whether or not it parses. */
static Variant surface(String lower) {
 if (lower.startsWith("darts:") && lower.contains("scales:")) return Variant.V1b;
 if (lower.startsWith("the pendant has ")) return Variant.ATES;
 if (lower.startsWith("your tome has been charged with ")) return Variant.V3;
 if (lower.startsWith("your amulet of blood fury will work for ")) return Variant.BLOOD_FURY;
 if (lower.startsWith("the eye of ayak has been charged with demon tears.")
 || lower.startsWith("the eye of ayak has been charged with runes.")) {
  return Variant.EYE_OF_AYAK;
 }
 for (Variant variant : Variant.values()) {
  if (variant == Variant.V1b) continue;
  String name = variant.displayName.toLowerCase(ROOT);
  if (lower.startsWith("your " + name + " has ") || lower.startsWith("your " + name + " only has ")
  || lower.startsWith(name + " has ") || lower.startsWith(name + " only has ")) {
   return variant;
  }
 }
 return null;
}

/** Resolve only exact supported menu-target names; menu evidence is identity only. */
static Variant supportedVariantForItemName(String itemName) {
 String name = normalizeItemName(itemName);
 for (Variant variant : Variant.values()) {
  if (variant.implemented && variant.displayName.toLowerCase(ROOT).equals(name)) return variant;
 }
 if (msg("cb").equals(name) || "uncharged trident".equals(name)) return Variant.TRIDENT_SEAS;
 if (msg("cz").equals(name)) return Variant.TRIDENT_SWAMP;
 if (msg("da").equals(name)) return Variant.TRIDENT_SEAS_ENHANCED;
 if (msg("bu").equals(name)) return Variant.TRIDENT_SWAMP_ENHANCED;
 return null;
}

/** Any catalogue variant by exact display name for presentation; estimate-only variants included. */
static Variant variantNamed(String itemName) {
 String name = normalizeItemName(itemName);
 for (Variant variant : Variant.values()) {
  if (variant.displayName.toLowerCase(ROOT).equals(name)) return variant;
 }
 return null;
}

/** Resolve charged and uncharged item ids so a Check receipt can be tied to its menu target. */
static Variant supportedVariantForItemId(int itemId) {
 for (Variant variant : Variant.values()) {
  for (int id : variant.itemIds) {
   if (id == itemId) return variant;
  }
 }
 return null;
}

/**
* Validate a candidate item-on-weapon load component. Both the canonical id
* and displayed name must agree, so unknown dart types fail closed.
*/
static boolean isSupportedLoadComponent(Variant variant, int itemId, String itemName) {
 String expectedName = variant == null || !variant.implemented ? null : expectedLoadName(variant, itemId);
 String actualName = normalizeItemName(itemName);
 return expectedName != null && actualName != null
 && (expectedName.equals(actualName) || actualName.equals(expectedName + "s") && !expectedName.endsWith("s"));
}

boolean hasDarts() {
 return dartItemId >= 0;
}

/** Component units per priced item: a page feeds 20 charges, a shard 100. */
static int unitsPerPricedItem(Variant variant, int componentItemId) {
 if (variant == Variant.V3) return 20;
 if (variant == Variant.BLOOD_FURY) return 10_000;
 if (variant.crystal()) return 100;
 return 1;
}

/** Returns only a positive same-variant load increase; decreases are charge spend evidence. */
static Map<Integer, Long> exactLoadQuantities(ChargeRead previous, ChargeRead current) {
 if (previous == null || current == null || !previous.bookable || !current.bookable
 || previous.variant != current.variant || current.variant != Variant.V1b && !current.variant.trident()) {
  return emptyMap();
 }
 var result = new LinkedHashMap<Integer, Long>();
 for (Map.Entry<Integer, Long> entry : current.componentCounts.entrySet()) {
  if (current.variant == Variant.V1b && entry.getKey() != SCALES) continue;
  Long before = previous.componentCounts.get(entry.getKey());
  if (before == null || entry.getValue() == null) return emptyMap();
  if (entry.getValue() > before) result.put(entry.getKey(), entry.getValue() - before);
 }
 return unmodifiableMap(result);
}

static ChargeRead unsupported(Variant variant) {
 return new ChargeRead(variant, emptyMap(), -1, false);
}

static String expectedLoadName(Variant variant, int itemId) {
 if (variant == Variant.V1b) {
  for (Map.Entry<String, Integer> dart : DARTS.entrySet()) {
   if (dart.getValue() == itemId) return dart.getKey();
  }
  return itemId == SCALES ? "zulrah's scale" : null;
 }
 if (variant == Variant.BLOOD_FURY) return itemId == ItemID.BLOOD_SHARD ? "blood shard" : null;
 if (variant == Variant.EYE_OF_AYAK) {
  // Both charging modes validate, so a recharge books as a neutral transfer (owner 2026-09-30).
  return itemId == ItemID.DEMON_TEAR ? "demon tear" : itemId == ItemID.DEATHRUNE ? "death rune"
  : itemId == ItemID.CHAOSRUNE ? "chaos rune" : null;
 }
 boolean seas = variant == Variant.TRIDENT_SEAS || variant == Variant.TRIDENT_SEAS_ENHANCED;
 return !variant.trident() ? null : itemId == ItemID.DEATHRUNE ? "death rune" : itemId == ItemID.CHAOSRUNE ? "chaos rune"
 : itemId == ItemID.FIRERUNE ? "fire rune" : seas && itemId == ItemID.COINS ? "coin"
 : !seas && itemId == SCALES ? "zulrah's scale" : null;
}

static long parseCount(String raw) {
 return Long.parseLong(raw.replace(",", ""));
}

static String stripTags(String value) {
 return value == null ? null : value.replaceAll("<[^>]*>", "").trim();
}

static String normalizeItemName(String value) {
 String cleaned = stripTags(value);
 return ModelText.empty(cleaned) ? null : cleaned.toLowerCase(ROOT).replaceAll("\\s+", " ");
}
}
