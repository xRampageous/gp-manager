package com.gpmanager;

import lombok.*;

import net.runelite.api.gameval.ItemID;

import java.util.*;

/**
 * Compares explicit charge reads. One read establishes a baseline; only measured component
 * decreases can produce a booking-ready delta.
 */
class MeasuredChargeReadTracker {
    /**
     * Bounded number of same-variant weapons tracked side by side (two blowpipes, a spare trident).
     */
    static final int MAX_TARGETS = 8;

    /**
     * One baseline per stable target identity, most recently read last: each weapon's last read,
     * which its next Check is measured against.
     */
    LinkedHashMap<String, Baseline> baselines = new LinkedHashMap<>();

    /** One weapon's last exact read and its local observation time. */
    @Getter
    @AllArgsConstructor
    static class Baseline {
        ChargeCheck read;
        long atEpochMillis;

        /**
         * Component units loaded into the weapon since this read, which its next Check adds back.
         */
        final Map<Integer, Long> loaded = new HashMap<>();
    }

    /**
     * A load into one weapon, clicked at {@code clickedAt}: its next Check counts it as loaded, not
     * as unused. A load clicked before the last read is already in that read. A load whose weapon
     * is unknown goes to the only weapon of that kind with a read, or else makes that kind's reads
     * untrustworthy.
     */
    synchronized void noteLoad(
            ChargeCheck.ChargedItem variant,
            String targetIdentity,
            long clickedAt,
            int itemId,
            long units) {
        Baseline last =
                targetIdentity == null ? only(variant) : baselines.get(targetIdentity.trim());
        if (targetIdentity == null && last == null) {
            resetVariant(variant);
            return;
        }
        if (last == null || clickedAt < last.atEpochMillis || units <= 0L) return;
        last.loaded.merge(itemId, units, SafeMath::safeAdd);
    }

    /** Observe one exact Check read and retain its local observation time. */
    synchronized ChargeDelta observe(ChargeCheck read, String targetIdentity, long now) {
        if (read == null) return new ChargeDelta(null, ChargeDelta.CheckResult.MISMATCH);
        if (!read.bookable || Phrases.blank(targetIdentity)) {
            reset();
            return new ChargeDelta(read.variant, ChargeDelta.CheckResult.MISMATCH);
        }
        String safeIdentity = targetIdentity.trim();
        // Each weapon keeps its own baseline: a Check of the other blowpipe is neither a
        // decrease on this one nor a reason to forget what this one last read.
        Baseline last = baselines.remove(safeIdentity);
        baselines.put(safeIdentity, new Baseline(read, SafeMath.nonNeg(now)));
        while (baselines.size() > MAX_TARGETS)
            baselines.remove(baselines.keySet().iterator().next());
        ChargeCheck previous = last == null ? null : last.read;
        if (previous == null) return new ChargeDelta(read.variant, ChargeDelta.CheckResult.SEED);
        if (previous.variant != read.variant)
            return new ChargeDelta(read.variant, ChargeDelta.CheckResult.MISMATCH);
        boolean blowpipe = read.variant == ChargeCheck.ChargedItem.BLOWPIPE;
        // Dart type is an independent component identity. A type switch can return old darts
        // and load new ones, so do not infer dart spend from the change; scale measurements
        // remain valid across that switch.
        boolean dartTypeSwitch =
                blowpipe
                        && read.hasDarts()
                        && (!previous.hasDarts() || previous.dartItemId != read.dartItemId);
        Map<Integer, Long> previousCounts = previous.componentCounts;
        Map<Integer, Long> loaded = last.loaded;
        var nextCounts = new LinkedHashMap<Integer, Long>(read.componentCounts);
        if (blowpipe && previous.hasDarts() && !read.hasDarts())
            nextCounts.put(previous.dartItemId, 0L);
        var itemIds = new LinkedHashSet<Integer>(previousCounts.keySet());
        itemIds.addAll(nextCounts.keySet());
        var decreases = new ArrayList<ChargeDelta.ComponentDelta>();
        var unchanged = new ArrayList<Integer>();
        boolean same = true;
        for (int itemId : itemIds) {
            long before = previousCounts.getOrDefault(itemId, 0L);
            // What was loaded since is not use: count it as if it had been there all along.
            long after = nextCounts.getOrDefault(itemId, 0L) - loaded.getOrDefault(itemId, 0L);
            same &= after == before;
            if (after == before) unchanged.add(itemId);
            if (after < before && (!dartTypeSwitch || itemId == ItemID.SNAKEBOSS_SCALE)) {
                decreases.add(new ChargeDelta.ComponentDelta(itemId, after - before));
            }
        }
        // Independent decreases still prove spend when a different component was loaded.
        var delta =
                new ChargeDelta(
                        read.variant,
                        decreases,
                        !decreases.isEmpty()
                                ? ChargeDelta.CheckResult.SPEND
                                : same
                                        ? ChargeDelta.CheckResult.SAME
                                        : ChargeDelta.CheckResult.LOAD);
        delta.unchanged.addAll(unchanged);
        return delta;
    }

    synchronized void reset() {
        baselines.clear();
    }

    /** The one baseline of this kind, or null when there are none or several. */
    Baseline only(ChargeCheck.ChargedItem variant) {
        Baseline found = null;
        for (Baseline last : baselines.values()) {
            if (last.read.variant != variant) continue;
            if (found != null) return null;
            found = last;
        }
        return found;
    }

    /**
     * Forget one variant's baselines after a custody change: a new physical instance of the weapon
     * entered the inventory, so a stored baseline can no longer be trusted to belong to the item
     * now occupying the slot. The next Check seeds a fresh baseline.
     */
    synchronized void resetVariant(ChargeCheck.ChargedItem variant) {
        if (variant == null) return;
        baselines.values().removeIf(last -> last.read.variant == variant);
    }
}
