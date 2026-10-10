package com.gpmanager;

import lombok.AllArgsConstructor;

import java.util.*;

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
