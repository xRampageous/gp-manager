package com.gpmanager.engine.evidence;

import com.gpmanager.model.ItemFlow;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Bounded evidence that a component loss may be loading an observed charged weapon.
 * Item-on-item intent alone has no quantity and therefore cannot partition a loss.
 * Only a single exact measured-quantity match is eligible for an ownership-neutral
 * transfer; later measured Check differences reconcile the deferred component
 * flows and separately own charge-use costs.
 */
public final class ChargeLoadTransferEvidence
{
    public static final String AMBIGUOUS_QUANTITY_NOTE =
        "Charge load: quantity unconfirmed — confirmed by the next Check, or decide";

    private MeasuredChargeRead.Variant variant;
    private int selectedItemId = -1;
    private String targetIdentity;
    private int ticksRemaining;
    private long expectedQuantity;

    /**
     * Arm only for a supported item-on-item target and a measured charge component.
     */
    public synchronized boolean arm(
        MeasuredChargeRead.Variant variant,
        int selectedItemId,
        @Nullable String selectedItemName,
        int ticks)
    {
        return arm(variant, selectedItemId, selectedItemName, null, ticks);
    }

    /** Arm with the stable target identity used by the corresponding Check menu action. */
    public synchronized boolean arm(
        MeasuredChargeRead.Variant variant,
        int selectedItemId,
        @Nullable String selectedItemName,
        @Nullable String targetIdentity,
        int ticks)
    {
        return armInternal(variant, selectedItemId, selectedItemName, targetIdentity, -1L, ticks);
    }

    /**
     * Arm with an exact quantity established by a future measured Check/read or
     * a quantity-bearing dialog. This overload is deliberately not wired to the
     * live item-on-item menu path: no such trustworthy quantity source is
     * currently available there.
     */
    public synchronized boolean armWithExactQuantity(
        MeasuredChargeRead.Variant variant,
        int selectedItemId,
        @Nullable String selectedItemName,
        long expectedQuantity,
        int ticks)
    {
        if (expectedQuantity <= 0L)
        {
            return false;
        }
        return armInternal(variant, selectedItemId, selectedItemName, null, expectedQuantity, ticks);
    }

    private boolean armInternal(
        MeasuredChargeRead.Variant variant,
        int selectedItemId,
        @Nullable String selectedItemName,
        @Nullable String targetIdentity,
        long expectedQuantity,
        int ticks)
    {
        if (variant == null
            || ticks <= 0
            || !MeasuredChargeRead.isSupportedLoadComponent(variant, selectedItemId, selectedItemName))
        {
            return false;
        }
        this.variant = variant;
        this.selectedItemId = selectedItemId;
        this.targetIdentity = normalizeIdentity(targetIdentity);
        this.expectedQuantity = expectedQuantity;
        this.ticksRemaining = ticks;
        return true;
    }

    /**
     * Partition recipe component losses after the selected component is observed.
     * An item-on-item menu action has no quantity, so candidate losses are held
     * separately for later measured Check reconciliation.
     */
    public synchronized Partition partition(@Nullable List<ItemFlow> flows)
    {
        if (variant == null || ticksRemaining <= 0 || flows == null || flows.isEmpty())
        {
            return Partition.none(flows);
        }

        List<ItemFlow> supportedLosses = new ArrayList<>();
        boolean selectedComponentLost = false;
        List<ItemFlow> remaining = new ArrayList<>();
        for (ItemFlow flow : flows)
        {
            if (flow != null
                && flow.getQuantityDelta() < 0L
                && MeasuredChargeRead.isSupportedLoadComponent(
                    variant, flow.getItemId(), flow.getItemName()))
            {
                supportedLosses.add(flow);
                selectedComponentLost |= flow.getItemId() == selectedItemId;
            }
            else if (flow != null)
            {
                remaining.add(flow);
            }
        }

        if (supportedLosses.isEmpty() || !selectedComponentLost)
        {
            return Partition.none(flows);
        }

        List<ItemFlow> selectedCandidates = new ArrayList<>();
        for (ItemFlow flow : supportedLosses)
        {
            if (flow.getItemId() == selectedItemId)
            {
                selectedCandidates.add(flow);
            }
        }
        if (expectedQuantity <= 0L || selectedCandidates.size() != 1
            || absoluteQuantity(selectedCandidates.get(0).getQuantityDelta()) != expectedQuantity)
        {
            // Hold every supported component lost with the selected component.
            // The next same-target Check may measure a recipe-level load; for a
            // blowpipe it can confirm scales only and leaves darts in Review.
            return new Partition(variant, selectedItemId, targetIdentity,
                Collections.emptyList(), remaining, supportedLosses, true);
        }

        MeasuredChargeRead.Variant matchedVariant = variant;
        List<ItemFlow> exactRemaining = new ArrayList<>(remaining);
        for (ItemFlow flow : supportedLosses)
        {
            if (!selectedCandidates.contains(flow))
            {
                exactRemaining.add(flow);
            }
        }
        clear();
        return new Partition(matchedVariant, selectedItemId, targetIdentity,
            selectedCandidates, exactRemaining, Collections.emptyList(), false);
    }

    public synchronized void tick()
    {
        if (ticksRemaining > 0 && --ticksRemaining <= 0)
        {
            clear();
        }
    }

    public synchronized void clear()
    {
        variant = null;
        selectedItemId = -1;
        targetIdentity = null;
        ticksRemaining = 0;
        expectedQuantity = -1L;
    }

    @Nullable
    public synchronized MeasuredChargeRead.Variant getVariant()
    {
        return variant;
    }

    public synchronized int getSelectedItemId()
    {
        return selectedItemId;
    }

    @Nullable
    public synchronized String getTargetIdentity()
    {
        return targetIdentity;
    }

    public synchronized boolean isArmed()
    {
        return variant != null && ticksRemaining > 0;
    }

    public static final class Partition
    {
        @Nullable
        public final MeasuredChargeRead.Variant variant;
        public final int selectedItemId;
        @Nullable
        public final String targetIdentity;
        public final List<ItemFlow> transferred;
        public final List<ItemFlow> remaining;
        public final List<ItemFlow> ambiguousCandidates;
        public final boolean ambiguousQuantity;

        private Partition(
            @Nullable MeasuredChargeRead.Variant variant,
            int selectedItemId,
            @Nullable String targetIdentity,
            List<ItemFlow> transferred,
            List<ItemFlow> remaining,
            List<ItemFlow> ambiguousCandidates,
            boolean ambiguousQuantity)
        {
            this.variant = variant;
            this.selectedItemId = selectedItemId;
            this.targetIdentity = targetIdentity;
            this.transferred = Collections.unmodifiableList(new ArrayList<>(transferred));
            this.remaining = Collections.unmodifiableList(new ArrayList<>(remaining));
            this.ambiguousCandidates = Collections.unmodifiableList(new ArrayList<>(ambiguousCandidates));
            this.ambiguousQuantity = ambiguousQuantity;
        }

        private static Partition none(@Nullable List<ItemFlow> flows)
        {
            List<ItemFlow> copied = flows == null ? Collections.emptyList() : flows;
            return new Partition(null, -1, null, Collections.emptyList(), copied,
                Collections.emptyList(), false);
        }

        public boolean hasTransfer()
        {
            return !transferred.isEmpty();
        }

    }

    private static long absoluteQuantity(long quantity)
    {
        return quantity == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(quantity);
    }

    @Nullable
    private static String normalizeIdentity(@Nullable String value)
    {
        if (value == null || value.trim().isEmpty())
        {
            return null;
        }
        return value.trim();
    }
}
