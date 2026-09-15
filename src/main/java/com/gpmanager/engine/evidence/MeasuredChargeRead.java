package com.gpmanager.engine.evidence;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import net.runelite.api.gameval.ItemID;

/**
 * One explicit weapon Check response, expressed as its measured component stock.
 * It does not infer depletion from combat events, menu intent, or probabilities.
 */
public final class MeasuredChargeRead
{
    private static final int DEATH_RUNE = 560;
    private static final int CHAOS_RUNE = 562;
    private static final int FIRE_RUNE = 554;
    private static final int ZULRAH_SCALES = 12934;
    private static final int COINS = 995;
    private static final int TRIDENT_SEAS_CHARGED = 11907;
    private static final int TRIDENT_SEAS_UNCHARGED = 11908;
    private static final int TRIDENT_SWAMP_CHARGED = 12899;
    private static final int TRIDENT_SWAMP_UNCHARGED = 12900;
    private static final int TRIDENT_SEAS_ENHANCED_CHARGED = 22288;
    private static final int TRIDENT_SEAS_ENHANCED_UNCHARGED = 22290;
    private static final int TRIDENT_SWAMP_ENHANCED_CHARGED = 22292;
    private static final int TRIDENT_SWAMP_ENHANCED_UNCHARGED = 22294;
    private static final int TOXIC_BLOWPIPE_ID = 12926;

    private static final String COUNT = "(?:\\d{1,3}(?:,\\d{3})+|\\d+)";
    private static final Pattern TRIDENT_CHECK = Pattern.compile(
        "^Your Trident of the (seas|swamp)( \\(e\\))? has (?:(" + COUNT
            + ") charges|(one) charge|(no) charges)\\.$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern BLOWPIPE_CHECK = Pattern.compile(
        "^Darts: (.+?)\\. Scales: (" + COUNT + ") \\((?:\\d+(?:\\.\\d+)?)%\\)\\.$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern TYPED_DARTS = Pattern.compile(
        "^(.+?) x (" + COUNT + ")$",
        Pattern.CASE_INSENSITIVE);

    public enum Variant
    {
        TRIDENT_SEAS(ChargeFamilyIds.TRIDENT, "Trident of the seas", true),
        TRIDENT_SWAMP(ChargeFamilyIds.TRIDENT, "Trident of the swamp", true),
        TRIDENT_SWAMP_ENHANCED(ChargeFamilyIds.TRIDENT, "Trident of the swamp (e)", true),
        TRIDENT_SEAS_ENHANCED(ChargeFamilyIds.TRIDENT, "Trident of the seas (e)", false),
        TOXIC_BLOWPIPE(ChargeFamilyIds.BLOWPIPE, "Toxic blowpipe", true);

        private final String familyId;
        private final String displayName;
        private final boolean implemented;

        Variant(String familyId, String displayName, boolean implemented)
        {
            this.familyId = familyId;
            this.displayName = displayName;
            this.implemented = implemented;
        }

        public String getFamilyId()
        {
            return familyId;
        }

        public boolean isImplemented()
        {
            return implemented;
        }

        public String getDisplayName()
        {
            return displayName;
        }
    }

    private final Variant variant;
    private final Map<Integer, Long> componentCounts;
    private final long chargeCount;
    private final int dartItemId;
    private final long dartCount;
    private final boolean bookable;

    private MeasuredChargeRead(
        Variant variant,
        Map<Integer, Long> componentCounts,
        long chargeCount,
        int dartItemId,
        long dartCount,
        boolean bookable)
    {
        this.variant = variant;
        this.componentCounts = Collections.unmodifiableMap(new LinkedHashMap<>(componentCounts));
        this.chargeCount = chargeCount;
        this.dartItemId = dartItemId;
        this.dartCount = dartCount;
        this.bookable = bookable && variant.isImplemented();
    }

    /**
     * Parse a pinned Check-message shape. Returns {@code null} for unrelated chat.
     * Recognized but unsupported/unknown variants return a non-bookable read so a
     * tracker can clear an older baseline safely.
     */
    @Nullable
    public static MeasuredChargeRead parseCheckMessage(@Nullable String rawMessage)
    {
        String message = stripTags(rawMessage);
        if (message == null)
        {
            return null;
        }

        Matcher trident = TRIDENT_CHECK.matcher(message);
        if (trident.matches())
        {
            boolean enhanced = trident.group(2) != null;
            boolean seas = "seas".equalsIgnoreCase(trident.group(1));
            Variant variant = seas
                ? (enhanced ? Variant.TRIDENT_SEAS_ENHANCED : Variant.TRIDENT_SEAS)
                : (enhanced ? Variant.TRIDENT_SWAMP_ENHANCED : Variant.TRIDENT_SWAMP);
            long charges;
            try
            {
                // The numeric token is syntax-checked before conversion; this
                // catch handles values outside the supported long range.
                charges = trident.group(3) != null
                    ? parseCount(trident.group(3))
                    : trident.group(4) != null ? 1L : 0L;
            }
            catch (NumberFormatException ex)
            {
                return unsupported(variant);
            }
            if (!variant.isImplemented())
            {
                return new MeasuredChargeRead(variant, Collections.emptyMap(), charges, -1, 0L, false);
            }

            try
            {
                Map<Integer, Long> components = tridentComponents(variant, charges);
                return new MeasuredChargeRead(variant, components, charges, -1, 0L, true);
            }
            catch (ArithmeticException ex)
            {
                return unsupported(variant);
            }
        }

        // If a line is recognizably a charge Check surface but malformed, reset
        // the caller's old baseline rather than carrying stale evidence forward.
        Matcher blowpipe = BLOWPIPE_CHECK.matcher(message);
        if (blowpipe.matches())
        {
            long scales;
            try
            {
                scales = parseCount(blowpipe.group(2));
            }
            catch (NumberFormatException ex)
            {
                return unsupported(Variant.TOXIC_BLOWPIPE);
            }

            String dartPart = blowpipe.group(1).trim();
            if ("none".equalsIgnoreCase(dartPart))
            {
                Map<Integer, Long> components = new LinkedHashMap<>();
                components.put(ZULRAH_SCALES, scales);
                return new MeasuredChargeRead(
                    Variant.TOXIC_BLOWPIPE, components, 0L, -1, 0L, true);
            }

            Matcher darts = TYPED_DARTS.matcher(dartPart);
            if (!darts.matches())
            {
                return unsupported(Variant.TOXIC_BLOWPIPE);
            }

            long dartCount;
            try
            {
                dartCount = parseCount(darts.group(2));
            }
            catch (NumberFormatException ex)
            {
                return unsupported(Variant.TOXIC_BLOWPIPE);
            }
            int dartId = dartItemId(darts.group(1));
            if (dartId < 0)
            {
                return new MeasuredChargeRead(
                    Variant.TOXIC_BLOWPIPE,
                    Collections.emptyMap(),
                    0L,
                    -1,
                    dartCount,
                    false);
            }

            Map<Integer, Long> components = new LinkedHashMap<>();
            components.put(ZULRAH_SCALES, scales);
            components.put(dartId, dartCount);
            return new MeasuredChargeRead(
                Variant.TOXIC_BLOWPIPE, components, 0L, dartId, dartCount, true);
        }

        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.startsWith("darts:") && lower.contains("scales:"))
        {
            return unsupported(Variant.TOXIC_BLOWPIPE);
        }
        if (lower.startsWith("your trident of the seas has "))
        {
            return unsupported(Variant.TRIDENT_SEAS);
        }
        if (lower.startsWith("your trident of the swamp has "))
        {
            return unsupported(Variant.TRIDENT_SWAMP);
        }
        if (lower.startsWith("your trident of the seas (e) has "))
        {
            return unsupported(Variant.TRIDENT_SEAS_ENHANCED);
        }
        if (lower.startsWith("your trident of the swamp (e) has "))
        {
            return unsupported(Variant.TRIDENT_SWAMP_ENHANCED);
        }
        return null;
    }

    /** Resolve only exact supported menu-target names; menu evidence is identity only. */
    @Nullable
    public static Variant supportedVariantForItemName(@Nullable String itemName)
    {
        String name = normalizeItemName(itemName);
        if ("trident of the seas".equals(name))
        {
            return Variant.TRIDENT_SEAS;
        }
        if ("trident of the swamp".equals(name))
        {
            return Variant.TRIDENT_SWAMP;
        }
        if ("trident of the swamp (e)".equals(name))
        {
            return Variant.TRIDENT_SWAMP_ENHANCED;
        }
        if ("toxic blowpipe".equals(name))
        {
            return Variant.TOXIC_BLOWPIPE;
        }
        return null;
    }

    /** Resolve charged and uncharged item ids so a Check receipt can be tied to its menu target. */
    @Nullable
    public static Variant supportedVariantForItemId(int itemId)
    {
        if (itemId == TRIDENT_SEAS_CHARGED || itemId == TRIDENT_SEAS_UNCHARGED)
        {
            return Variant.TRIDENT_SEAS;
        }
        if (itemId == TRIDENT_SWAMP_CHARGED || itemId == TRIDENT_SWAMP_UNCHARGED)
        {
            return Variant.TRIDENT_SWAMP;
        }
        if (itemId == TRIDENT_SWAMP_ENHANCED_CHARGED || itemId == TRIDENT_SWAMP_ENHANCED_UNCHARGED)
        {
            return Variant.TRIDENT_SWAMP_ENHANCED;
        }
        if (itemId == TRIDENT_SEAS_ENHANCED_CHARGED || itemId == TRIDENT_SEAS_ENHANCED_UNCHARGED)
        {
            return Variant.TRIDENT_SEAS_ENHANCED;
        }
        if (itemId == TOXIC_BLOWPIPE_ID)
        {
            return Variant.TOXIC_BLOWPIPE;
        }
        return null;
    }

    /**
     * Validate a candidate item-on-weapon load component. Both the canonical id
     * and displayed name must agree, so unknown dart types fail closed.
     */
    public static boolean isSupportedLoadComponent(
        @Nullable Variant variant,
        int itemId,
        @Nullable String itemName)
    {
        if (variant == null || !variant.isImplemented())
        {
            return false;
        }
        String expectedName = expectedLoadName(variant, itemId);
        String actualName = normalizeItemName(itemName);
        return expectedName != null
            && actualName != null
            && (expectedName.equals(actualName) || expectedName.equals(singularizeLastWord(actualName)));
    }

    public Variant getVariant()
    {
        return variant;
    }

    public String getFamilyId()
    {
        return variant.getFamilyId();
    }

    public Map<Integer, Long> getComponentCounts()
    {
        return componentCounts;
    }

    public long getChargeCount()
    {
        return chargeCount;
    }

    /** Returns -1 when a Check explicitly reported no darts or had an unknown type. */
    public int getDartItemId()
    {
        return dartItemId;
    }

    public long getDartCount()
    {
        return dartCount;
    }

    public boolean hasDarts()
    {
        return dartItemId >= 0;
    }

    public boolean isBookable()
    {
        return bookable;
    }

    private static MeasuredChargeRead unsupported(Variant variant)
    {
        return new MeasuredChargeRead(variant, Collections.emptyMap(), 0L, -1, 0L, false);
    }

    private static Map<Integer, Long> tridentComponents(Variant variant, long charges)
    {
        Map<Integer, Long> components = new LinkedHashMap<>();
        components.put(DEATH_RUNE, charges);
        components.put(CHAOS_RUNE, charges);
        components.put(FIRE_RUNE, Math.multiplyExact(charges, 5L));
        if (variant == Variant.TRIDENT_SEAS)
        {
            components.put(COINS, Math.multiplyExact(charges, 10L));
        }
        else
        {
            components.put(ZULRAH_SCALES, charges);
        }
        return components;
    }

    private static int dartItemId(String name)
    {
        String normalized = normalizeItemName(name);
        if ("bronze dart".equals(normalized)) return ItemID.BRONZE_DART;
        if ("iron dart".equals(normalized)) return ItemID.IRON_DART;
        if ("steel dart".equals(normalized)) return ItemID.STEEL_DART;
        if ("mithril dart".equals(normalized)) return ItemID.MITHRIL_DART;
        if ("adamant dart".equals(normalized)) return ItemID.ADAMANT_DART;
        if ("rune dart".equals(normalized)) return ItemID.RUNE_DART;
        if ("amethyst dart".equals(normalized)) return ItemID.AMETHYST_DART;
        if ("dragon dart".equals(normalized)) return ItemID.DRAGON_DART;
        return -1;
    }

    @Nullable
    private static String expectedLoadName(Variant variant, int itemId)
    {
        if (variant == Variant.TRIDENT_SEAS)
        {
            if (itemId == DEATH_RUNE) return "death rune";
            if (itemId == CHAOS_RUNE) return "chaos rune";
            if (itemId == FIRE_RUNE) return "fire rune";
            if (itemId == COINS) return "coin";
            return null;
        }
        if (variant == Variant.TRIDENT_SWAMP || variant == Variant.TRIDENT_SWAMP_ENHANCED)
        {
            if (itemId == DEATH_RUNE) return "death rune";
            if (itemId == CHAOS_RUNE) return "chaos rune";
            if (itemId == FIRE_RUNE) return "fire rune";
            if (itemId == ZULRAH_SCALES) return "zulrah's scale";
            return null;
        }
        if (variant == Variant.TOXIC_BLOWPIPE)
        {
            if (itemId == ZULRAH_SCALES) return "zulrah's scale";
            int dartId = dartItemIdForLoad(itemId);
            return dartId == itemId ? dartName(itemId) : null;
        }
        return null;
    }

    private static int dartItemIdForLoad(int itemId)
    {
        return itemId == ItemID.BRONZE_DART
            || itemId == ItemID.IRON_DART
            || itemId == ItemID.STEEL_DART
            || itemId == ItemID.MITHRIL_DART
            || itemId == ItemID.ADAMANT_DART
            || itemId == ItemID.RUNE_DART
            || itemId == ItemID.AMETHYST_DART
            || itemId == ItemID.DRAGON_DART
            ? itemId
            : -1;
    }

    @Nullable
    private static String dartName(int itemId)
    {
        if (itemId == ItemID.BRONZE_DART) return "bronze dart";
        if (itemId == ItemID.IRON_DART) return "iron dart";
        if (itemId == ItemID.STEEL_DART) return "steel dart";
        if (itemId == ItemID.MITHRIL_DART) return "mithril dart";
        if (itemId == ItemID.ADAMANT_DART) return "adamant dart";
        if (itemId == ItemID.RUNE_DART) return "rune dart";
        if (itemId == ItemID.AMETHYST_DART) return "amethyst dart";
        if (itemId == ItemID.DRAGON_DART) return "dragon dart";
        return null;
    }

    private static long parseCount(String raw)
    {
        return Long.parseLong(raw.replace(",", ""));
    }

    @Nullable
    private static String stripTags(@Nullable String value)
    {
        if (value == null)
        {
            return null;
        }
        return value.replaceAll("<[^>]*>", "").trim();
    }

    @Nullable
    private static String normalizeItemName(@Nullable String value)
    {
        String cleaned = stripTags(value);
        if (cleaned == null || cleaned.isEmpty())
        {
            return null;
        }
        return cleaned.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String singularizeLastWord(String value)
    {
        if (value.endsWith("s") && !value.endsWith("ss"))
        {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
