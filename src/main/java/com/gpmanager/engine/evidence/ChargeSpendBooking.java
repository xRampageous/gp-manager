package com.gpmanager.engine.evidence;

import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Books only component losses that exactly match a compatible measured-read
 * difference. Generic inventory losses and family calibration cannot authorize
 * charge spend.
 */
public final class ChargeSpendBooking
{
    public static final class Result
    {
        public final boolean booked;
        public final long costGp;
        public final String warning;
        public final String explanation;
        public final List<ItemFlow> flows;
        /** Set when {@link com.gpmanager.engine.GpManagerEngine#bookChargeSpend} added a session row. */
        public final com.gpmanager.model.ProfitTransaction transaction;

        public Result(
            boolean booked,
            long costGp,
            String warning,
            String explanation,
            List<ItemFlow> flows,
            com.gpmanager.model.ProfitTransaction transaction)
        {
            this.booked = booked;
            this.costGp = costGp;
            this.warning = warning == null ? "" : warning;
            this.explanation = explanation == null ? "" : explanation;
            this.flows = flows == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(flows));
            this.transaction = transaction;
        }
    }

    private ChargeSpendBooking()
    {
    }

    /**
     * Book the supplied prices only when they exactly match every measured
     * negative component delta. Missing/partial/extra/unknown-priced flows fail
     * closed as a whole; there is no persisted calibration bypass.
     */
    public static Result tryBook(
        @Nullable MeasuredChargeDelta delta,
        @Nullable String itemName,
        @Nullable List<ItemFlow> pricedComponentLosses)
    {
        return tryBook(delta, itemName, pricedComponentLosses, Collections.emptySet());
    }

    /**
     * As above, but fail the complete measured difference when any component is
     * still represented by an unresolved same-variant charge-load Review row.
     */
    public static Result tryBook(
        @Nullable MeasuredChargeDelta delta,
        @Nullable String itemName,
        @Nullable List<ItemFlow> pricedComponentLosses,
        @Nullable Set<Integer> pendingLoadComponentIds)
    {
        if (delta == null
            || delta.getVariant() == null
            || !delta.getVariant().isImplemented()
            || delta.getComponentDeltas().isEmpty()
            || pricedComponentLosses == null
            || pricedComponentLosses.size() != delta.getComponentDeltas().size())
        {
            return notBooked("No complete measured charge difference; zero cost booked.");
        }

        if (pendingLoadComponentIds != null && !pendingLoadComponentIds.isEmpty())
        {
            for (MeasuredChargeDelta.ComponentDelta component : delta.getComponentDeltas())
            {
                if (component != null && pendingLoadComponentIds.contains(component.getItemId()))
                {
                    return notBooked(
                        "A charge component is still awaiting load confirmation; zero charge cost booked.");
                }
            }
        }

        Map<Integer, Long> expected = new HashMap<>();
        for (MeasuredChargeDelta.ComponentDelta component : delta.getComponentDeltas())
        {
            if (component == null || component.getItemId() <= 0 || component.getQuantityDelta() >= 0L)
            {
                return notBooked("Invalid measured charge difference; zero cost booked.");
            }
            try
            {
                expected.merge(component.getItemId(), component.getQuantityDelta(), Math::addExact);
            }
            catch (ArithmeticException ex)
            {
                return notBooked("Measured charge quantity overflow; zero cost booked.");
            }
        }

        Map<Integer, Long> actual = new HashMap<>();
        long cost = 0L;
        for (ItemFlow flow : pricedComponentLosses)
        {
            boolean validPriceSource = flow != null
                && (flow.getItemId() == 995
                    ? flow.getUnitPrice() == 1
                        && flow.getPriceSource() == ItemPriceSource.FACE_VALUE
                    : flow.getPriceSource() == ItemPriceSource.GRAND_EXCHANGE);
            if (flow == null
                || flow.getQuantityDelta() >= 0L
                || flow.getItemId() <= 0
                || flow.getUnitPrice() <= 0
                || !validPriceSource
                || flow.getValueDelta() >= 0L
                || flow.getValueDelta() == Long.MIN_VALUE)
            {
                return notBooked("Charge components need complete RuneLite GE prices; zero cost booked.");
            }
            long expectedValue;
            try
            {
                expectedValue = Math.multiplyExact(flow.getQuantityDelta(), (long) flow.getUnitPrice());
            }
            catch (ArithmeticException ex)
            {
                return notBooked("Charge component value overflow; zero cost booked.");
            }
            if (expectedValue != flow.getValueDelta())
            {
                return notBooked("Charge component value does not match its GE price; zero cost booked.");
            }
            try
            {
                actual.merge(flow.getItemId(), flow.getQuantityDelta(), Math::addExact);
                cost = Math.addExact(cost, Math.abs(flow.getValueDelta()));
            }
            catch (ArithmeticException ex)
            {
                return notBooked("Charge component aggregate overflow; zero cost booked.");
            }
        }

        if (!expected.equals(actual) || cost <= 0L)
        {
            return notBooked("Priced flows do not exactly match the measured charge difference; zero cost booked.");
        }

        ChargeRecipeCatalogue.Recipe recipe = ChargeRecipeCatalogue.recipeFor(delta.getFamilyId());
        String display = itemName == null || itemName.trim().isEmpty()
            ? recipe == null ? delta.getFamilyId() : recipe.displayName
            : itemName.trim();
        String variant = delta.getVariant() == null
            ? display
            : delta.getVariant().getDisplayName();
        return new Result(
            true,
            cost,
            "",
            "Measured Check difference for " + variant + ".",
            pricedComponentLosses,
            null);
    }

    private static Result notBooked(String reason)
    {
        return new Result(false, 0L, reason, "No measured charge cost was booked.",
            Collections.emptyList(), null);
    }

}
