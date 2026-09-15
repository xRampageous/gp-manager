package com.gpmanager.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Calculates a measured carried-value risk estimate for HUD+.
 *
 * <p>This calculator does not infer which items the game will keep. The caller
 * supplies the keep count; we conservatively subtract the most valuable
 * measured item stacks up to that count. Any missing price or malformed stack
 * makes the result incomplete instead of silently understating risk.</p>
 */
public final class WildernessRiskCalculator
{
    private WildernessRiskCalculator()
    {
    }

    public static Result calculate(
        @Nullable List<ItemStack> inventory,
        @Nullable List<ItemStack> equipment,
        int keepCount)
    {
        if (inventory == null || equipment == null || keepCount < 0)
        {
            return Result.incomplete();
        }

        List<Long> stackValues = new ArrayList<>();
        long carriedValue = 0L;
        if (!appendValues(inventory, stackValues))
        {
            return Result.incomplete();
        }
        if (!appendValues(equipment, stackValues))
        {
            return Result.incomplete();
        }

        try
        {
            for (Long stackValue : stackValues)
            {
                carriedValue = Math.addExact(carriedValue, stackValue);
            }
        }
        catch (ArithmeticException overflow)
        {
            return Result.incomplete();
        }

        Collections.sort(stackValues, Comparator.reverseOrder());
        long keptValue = 0L;
        int keptStacks = Math.min(keepCount, stackValues.size());
        try
        {
            for (int i = 0; i < keptStacks; i++)
            {
                keptValue = Math.addExact(keptValue, stackValues.get(i));
            }
            return Result.complete(carriedValue, keptValue, Math.subtractExact(carriedValue, keptValue));
        }
        catch (ArithmeticException overflow)
        {
            return Result.incomplete();
        }
    }

    private static boolean appendValues(List<ItemStack> stacks, List<Long> values)
    {
        for (ItemStack stack : stacks)
        {
            if (stack == null || stack.getItemId() < 0 || stack.getQuantity() <= 0L
                || stack.getGeUnitPrice() == null || stack.getGeUnitPrice() < 0L)
            {
                return false;
            }
            try
            {
                values.add(Math.multiplyExact(stack.getQuantity(), stack.getGeUnitPrice()));
            }
            catch (ArithmeticException overflow)
            {
                return false;
            }
        }
        return true;
    }

    /** A single inventory/equipment slot with caller-supplied GE pricing evidence. */
    public static final class ItemStack
    {
        private final int itemId;
        private final long quantity;
        @Nullable
        private final Long geUnitPrice;

        public ItemStack(int itemId, long quantity, @Nullable Long geUnitPrice)
        {
            this.itemId = itemId;
            this.quantity = quantity;
            this.geUnitPrice = geUnitPrice;
        }

        public int getItemId()
        {
            return itemId;
        }

        public long getQuantity()
        {
            return quantity;
        }

        @Nullable
        public Long getGeUnitPrice()
        {
            return geUnitPrice;
        }
    }

    public static final class Result
    {
        private static final Result INCOMPLETE = new Result(false, 0L, 0L, 0L);

        private final boolean complete;
        private final long carriedValue;
        private final long keptValue;
        private final long riskValue;

        private Result(boolean complete, long carriedValue, long keptValue, long riskValue)
        {
            this.complete = complete;
            this.carriedValue = carriedValue;
            this.keptValue = keptValue;
            this.riskValue = riskValue;
        }

        private static Result incomplete()
        {
            return INCOMPLETE;
        }

        private static Result complete(long carriedValue, long keptValue, long riskValue)
        {
            return new Result(true, carriedValue, keptValue, riskValue);
        }

        public boolean isComplete()
        {
            return complete;
        }

        public long getCarriedValue()
        {
            requireComplete();
            return carriedValue;
        }

        public long getKeptValue()
        {
            requireComplete();
            return keptValue;
        }

        public long getRiskValue()
        {
            requireComplete();
            return riskValue;
        }

        private void requireComplete()
        {
            if (!complete)
            {
                throw new IllegalStateException("Wilderness risk is incomplete");
            }
        }
    }
}
