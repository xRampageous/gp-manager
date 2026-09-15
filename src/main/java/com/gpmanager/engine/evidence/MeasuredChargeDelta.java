package com.gpmanager.engine.evidence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Measured negative component-count differences; valuation is applied by the caller. */
public final class MeasuredChargeDelta
{
    public static final class ComponentDelta
    {
        private final int itemId;
        private final long quantityDelta;

        ComponentDelta(int itemId, long quantityDelta)
        {
            if (quantityDelta >= 0L)
            {
                throw new IllegalArgumentException("Charge component deltas must be negative");
            }
            this.itemId = itemId;
            this.quantityDelta = quantityDelta;
        }

        public int getItemId()
        {
            return itemId;
        }

        public long getQuantityDelta()
        {
            return quantityDelta;
        }
    }

    private final MeasuredChargeRead.Variant variant;
    private final String familyId;
    private final List<ComponentDelta> components;

    MeasuredChargeDelta(MeasuredChargeRead.Variant variant, List<ComponentDelta> components)
    {
        this.variant = variant;
        this.familyId = variant.getFamilyId();
        this.components = Collections.unmodifiableList(new ArrayList<>(components));
    }

    public MeasuredChargeRead.Variant getVariant()
    {
        return variant;
    }

    public String getFamilyId()
    {
        return familyId;
    }

    public List<ComponentDelta> getComponentDeltas()
    {
        return components;
    }
}
