package com.gpmanager.engine.evidence;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Legacy per-profile calibration gate used by utility-container evidence. Weapon
 * charge costs use {@link MeasuredChargeReadTracker}; a menu Check never
 * authorizes a weapon cost.
 *
 * <p>Container callers that want to surface calibration UX may show
 * {@link #calibrationWarning(String)} and keep uncalibrated container costs out of Net until
 * {@link #isCalibrated(String)} is true for that family.</p>
 *
 * <p>In-memory set is restored from ConfigManager per RS profile via
 * {@link #restore(Set)} / {@link #snapshot()} so calibration survives plugin
 * reload and does not leak across accounts.</p>
 */
public final class ChargeAccountingGate
{
    private static final Set<String> CALIBRATED =
        Collections.newSetFromMap(new ConcurrentHashMap<>());

    private ChargeAccountingGate()
    {
    }

    /**
     * Legacy global switch — true only when at least one family is calibrated.
     * Prefer {@link #isCalibrated(String)} for new call sites.
     */
    public static boolean isEnabled()
    {
        return !CALIBRATED.isEmpty();
    }

    /** True when a utility-container family has recorded Check evidence. */
    public static boolean isCalibrated(String familyId)
    {
        String key = normalize(familyId);
        return key != null && CALIBRATED.contains(key);
    }

    /** Mark a utility-container family calibrated after its Check evidence. */
    public static void markCalibrated(String familyId)
    {
        String key = normalize(familyId);
        if (key != null)
        {
            CALIBRATED.add(key);
        }
    }

    /** Clear one family (tests / uncharge-to-empty). */
    public static void clearCalibrated(String familyId)
    {
        String key = normalize(familyId);
        if (key != null)
        {
            CALIBRATED.remove(key);
        }
    }

    /** Test / logout / profile-switch reset. */
    public static void clearAllCalibrated()
    {
        CALIBRATED.clear();
    }

    /** Stable snapshot for ConfigManager persistence (sorted insertion order). */
    public static Set<String> snapshot()
    {
        return Collections.unmodifiableSet(new LinkedHashSet<>(CALIBRATED));
    }

    /** Replace in-memory calibration from persisted profile data. */
    public static void restore(Set<String> familyIds)
    {
        CALIBRATED.clear();
        if (familyIds == null)
        {
            return;
        }
        for (String id : familyIds)
        {
            markCalibrated(id);
        }
    }

    /** Encode for ConfigManager (comma-separated family ids). */
    public static String encode(Set<String> familyIds)
    {
        if (familyIds == null || familyIds.isEmpty())
        {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String id : familyIds)
        {
            String key = normalize(id);
            if (key == null)
            {
                continue;
            }
            if (out.length() > 0)
            {
                out.append(',');
            }
            out.append(key);
        }
        return out.toString();
    }

    /** Decode ConfigManager value into family ids. */
    public static Set<String> decode(String raw)
    {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null || raw.trim().isEmpty())
        {
            return out;
        }
        for (String part : raw.split(","))
        {
            String key = normalize(part);
            if (key != null)
            {
                out.add(key);
            }
        }
        return out;
    }

    /**
     * User-visible warning for a utility-container surface that is not yet
     * calibrated. Example: {@code Check forestry kit to calibrate}.
     */
    public static String calibrationWarning(String itemName)
    {
        String name = itemName == null || itemName.trim().isEmpty()
            ? "this charged item"
            : itemName.trim();
        return "Check " + name + " to calibrate before inferred container costs are counted.";
    }

    private static String normalize(String familyId)
    {
        if (familyId == null)
        {
            return null;
        }
        String key = familyId.trim().toLowerCase(Locale.ROOT);
        return key.isEmpty() ? null : key;
    }
}
