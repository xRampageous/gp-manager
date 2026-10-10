package com.gpmanager;

import static com.gpmanager.PriceSource.*;

import static java.lang.Math.*;

import lombok.*;

import net.runelite.api.gameval.ItemID;

import java.util.*;

/** Measured negative component-count differences; valuation is applied by the caller. */
@Getter
class ChargeDelta {
    ChargeCheck.ChargedItem variant;
    List<ComponentDelta> componentDeltas;

    /** Components the Check read at the same count as before: their estimates were overcounts. */
    final Set<Integer> unchanged = new HashSet<>();

    /** Explicit Check outcome; this enum is transient accounting evidence, never save data. */
    enum CheckResult {
        SEED,
        SAME,
        SPEND,
        LOAD,
        MISMATCH
    }

    CheckResult result;

    ChargeDelta(ChargeCheck.ChargedItem variant, List<ComponentDelta> deltas, CheckResult result) {
        this.variant = variant;
        this.componentDeltas = deltas;
        this.result = result;
    }

    /** A caller supplying measured losses explicitly constructs SPEND, as before. */
    ChargeDelta(ChargeCheck.ChargedItem variant, List<ComponentDelta> deltas) {
        this(variant, deltas, CheckResult.SPEND);
    }

    /** An outcome without a measured decrease carries no bookable component quantity. */
    ChargeDelta(ChargeCheck.ChargedItem variant, CheckResult result) {
        this(variant, Collections.emptyList(), result);
    }

    /** One component's measured decrease (always negative). */
    @Getter
    @AllArgsConstructor
    static class ComponentDelta {
        int itemId;
        long quantityDelta;
    }

    /**
     * The GP cost of these priced component losses when they exactly match every measured decrease;
     * 0 otherwise. Missing, partial, extra, unsupported-priced or overflowing flows fail closed as
     * a whole. Generic inventory losses can never authorize charge spend.
     */
    long exactCost(List<Flow> pricedLosses) {
        if (result != CheckResult.SPEND
                || variant == null
                || !variant.implemented
                || componentDeltas.isEmpty()
                || pricedLosses == null
                || pricedLosses.size() != componentDeltas.size()) {
            return 0L;
        }
        var expected = new HashMap<Integer, Long>();
        var actual = new HashMap<Integer, Long>();
        long cost = 0L;
        try {
            for (ComponentDelta component : componentDeltas) {
                if (component.itemId <= 0) return 0L;
                expected.merge(component.itemId, component.quantityDelta, Math::addExact);
            }
            for (Flow flow : pricedLosses) {
                boolean validPriceSource =
                        flow != null
                                && (flow.itemId == ItemID.COINS
                                        ? flow.unitPrice == 1 && flow.getPriceSource() == FACE_VALUE
                                        : flow.getPriceSource() == GRAND_EXCHANGE
                                                || flow.getPriceSource() == MANUAL_OVERRIDE);
                if (!validPriceSource
                        || flow.quantityDelta >= 0L
                        || flow.itemId <= 0
                        || flow.unitPrice <= 0
                        || flow.valueDelta >= 0L
                        || multiplyExact(flow.quantityDelta, (long) flow.unitPrice)
                                != flow.valueDelta) {
                    return 0L;
                }
                actual.merge(flow.itemId, flow.quantityDelta, Math::addExact);
                cost = addExact(cost, negateExact(flow.valueDelta));
            }
        } catch (ArithmeticException ex) {
            return 0L;
        }
        return expected.equals(actual) ? cost : 0L;
    }
}
