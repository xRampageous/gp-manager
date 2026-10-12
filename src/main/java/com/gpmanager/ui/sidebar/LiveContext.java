package com.gpmanager;

import lombok.AllArgsConstructor;
import java.util.*;

/**
 * Plugin-side facts the Live read model cannot read from the engine: the current owner's idle-badge
 * state, the presentation marks noted for that owner, the loot visibility preferences, the cached
 * client-thread PvP sample and the activity context label.
 *
 * <p>Forward-port note: the historical context also carried the notable-drop threshold, the live
 * encounter summary and the sidebar net-graph toggle. Those producers no longer exist in schema
 * 102, so nothing is fabricated here; the fields that remain are the ones the current plugin can
 * state truthfully.
 */
class LiveContext {
    static final LiveContext NONE =
            new LiveContext(false, null, 0L, PvpState.NONE, "", false, "", false);
    boolean idle;

    /** Presentation-only item filter; null means every booked row stays visible. */
    RecentFilter filter;

    /** Hide gained rows whose unit price is below this value; 0 shows all. Never hides costs. */
    long minimumDisplayedLootValue;

    /** Cached client-thread PvP sample; presentation only. */
    PvpState pvp;

    /** Activity context label for the status line; empty means "use the owner name". */
    String activity;

    /** Freshest client-thread interaction target for the shared activity label; empty when none. */
    String target;

    /** The client is at the login screen; only read when no Grind is active. */
    boolean offline;

    /** A world hop is in flight: presentation says Reconnecting, never Logged out. */
    boolean hopping;

    LiveContext(
            boolean idle,
            RecentFilter filter,
            long minimumDisplayedLootValue,
            PvpState pvp,
            String activity,
            boolean offline,
            String target) {
        this(idle, filter, minimumDisplayedLootValue, pvp, activity, offline, target, false);
    }

    LiveContext(
            boolean idle,
            RecentFilter filter,
            long minimumDisplayedLootValue,
            PvpState pvp,
            String activity,
            boolean offline,
            String target,
            boolean hopping) {
        this.idle = idle;
        this.filter = filter;
        this.minimumDisplayedLootValue = SafeMath.nonNeg(minimumDisplayedLootValue);
        this.pvp = pvp == null ? PvpState.NONE : pvp;
        this.activity = Phrases.orEmpty(activity);
        this.offline = offline;
        this.target = Phrases.orEmpty(target);
        this.hopping = hopping;
    }

    /** Whether one flow stays on a presentation row under the current visibility settings. */
    boolean flowVisible(Flow flow) {
        if (flow == null) return false;
        // Costs, fees and deaths are never hidden by the display loot filter.
        if (flow.quantityDelta <= 0L) return true;
        // Unknown / unpriced gains remain discoverable for Review; the minimum is
        // compared against the unit price so quantity cannot defeat it.
        if (flow.unitPrice > 0 && flow.unitPrice < minimumDisplayedLootValue) return false;
        return filter == null || filter.isFlowIncluded(flow);
    }
}

/**
 * Client-side PvP facts the plugin observes on its tick (Wilderness / PvP world, skull, Protect
 * Item, what a death would lose). Presentation only; nothing here touches accounting.
 */
@AllArgsConstructor
class PvpState {
    static final PvpState NONE = new PvpState(false, false, false, 0L);
    boolean pvpPossible;
    boolean skulled;
    boolean protectItem;

    /** GP a death here would lose; 0 where no death can be lost. */
    long risk;

    /**
     * Every held unit but the most valuable 3 (none when skulled), one more with Protect Item, at
     * today's prices; an unpriced unit counts as 0. A stack keeps units, not the whole stack.
     */
    static long risk(List<Flow> held, boolean skulled, boolean protectItem) {
        var stacks = new ArrayList<Flow>();
        for (Flow flow : held) {
            if (flow != null && flow.quantityDelta > 0L) stacks.add(flow);
        }
        stacks.sort((a, b) -> Long.compare(deathValue(b), deathValue(a)));
        long keep = (skulled ? 0 : 3) + (protectItem ? 1 : 0);
        long risk = 0L;
        for (Flow stack : stacks) {
            long kept = Math.min(keep, stack.quantityDelta);
            keep -= kept;
            risk =
                    SafeMath.safeAdd(
                            risk, Math.max(0L, deathValue(stack)) * (stack.quantityDelta - kept));
        }
        return risk;
    }

    /**
     * What a death weighs the item at. Since 2026-07-29 the game ranks every Avernic defender at
     * 600,000, its repair cost when it breaks unprotected (OSRS Wiki).
     */
    static long deathValue(Flow stack) {
        return GameData.pattern("avernic").matcher(Phrases.lower(stack.itemName)).matches()
                ? 600_000L
                : stack.unitPrice;
    }
}

/**
 * Presentation-only item eligibility for the Live Recent rows. The panel binds the current {@code
 * LootPresentationFilterService} decision behind this seam so the kit stays free of plugin
 * dependencies and the existing filter authority stays the only policy.
 */
@FunctionalInterface
interface RecentFilter {
    /**
     * @return true when presentation may show a row carrying this flow
     */
    boolean isFlowIncluded(Flow flow);

    /**
     * A charge spend row also answers to the separate charge list, so its family's spends can be
     * hidden while the item's other rows (a gain) still show.
     */
    default boolean showRecent(String name, long quantity, boolean charge) {
        return true;
    }

    default void hideRecent(String name, boolean charge) {}

    /** Changes whenever the decisions may change, so the Live list is rebuilt only then. */
    default String version() {
        return "";
    }
}
