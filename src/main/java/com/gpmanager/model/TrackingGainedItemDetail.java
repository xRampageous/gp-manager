package com.gpmanager.model;

/**
 * Filterable daily insight detail for one item id/name/quantity/unit-price
 * combination. Older saves keep their original name-only item totals and are
 * exposed as undetailed gains by {@link TrackingDaySummary}.
 */
public final class TrackingGainedItemDetail
{
    private int itemId;
    private String itemName;
    private long quantity;
    private int unitPrice;
    private long value;
    private long observations;
    private ItemPriceSource priceSource;
    private long priceCapturedAtEpochMillis;

    public TrackingGainedItemDetail()
    {
        // Gson
    }

    TrackingGainedItemDetail(ItemFlow flow)
    {
        this.itemId = flow == null ? 0 : flow.getItemId();
        this.itemName = normalize(flow == null ? null : flow.getItemName());
        this.quantity = flow == null ? 0L : Math.max(0L, flow.getQuantityDelta());
        this.unitPrice = flow == null ? 0 : Math.max(0, flow.getUnitPrice());
        this.value = flow == null ? 0L : Math.max(0L, flow.getValueDelta());
        this.observations = flow == null ? 0L : 1L;
        this.priceSource = flow == null ? ItemPriceSource.UNKNOWN : flow.getPriceSource();
        this.priceCapturedAtEpochMillis = flow == null ? 0L : flow.getPriceCapturedAtEpochMillis();
    }

    /** Sums quantity, value and observations; the newer price capture wins. */
    public void absorb(TrackingGainedItemDetail other)
    {
        if (other == null || other == this) return;
        quantity = Math.max(0L, quantity) + Math.max(0L, other.quantity);
        value += other.value;
        observations = Math.max(0L, observations) + Math.max(0L, other.observations);
        if (other.priceCapturedAtEpochMillis > priceCapturedAtEpochMillis)
        {
            unitPrice = other.unitPrice;
            priceSource = other.priceSource;
            priceCapturedAtEpochMillis = other.priceCapturedAtEpochMillis;
        }
    }

    public int getItemId() { return itemId; }
    public String getItemName() { return normalize(itemName); }
    public long getQuantity() { return Math.max(0L, quantity); }
    public int getUnitPrice() { return Math.max(0, unitPrice); }
    public long getValue() { return Math.max(0L, value); }
    public long getObservations() { return Math.max(0L, observations); }
    public ItemPriceSource getPriceSource()
    {
        return priceSource == null ? ItemPriceSource.UNKNOWN : priceSource;
    }
    public long getPriceCapturedAtEpochMillis() { return Math.max(0L, priceCapturedAtEpochMillis); }

    public ItemFlow toItemFlow()
    {
        if (itemId <= 0 || getValue() <= 0L)
        {
            return null;
        }
        return new ItemFlow(itemId, getItemName(), getQuantity(), getUnitPrice(), getValue(),
            getPriceSource(), getPriceCapturedAtEpochMillis());
    }

    void add(ItemFlow flow)
    {
        if (flow == null) return;
        value = safeAdd(value, Math.max(0L, flow.getValueDelta()));
        observations = safeAdd(observations, 1L);
    }

    void remove(ItemFlow flow)
    {
        if (flow == null) return;
        value = safeAdd(value, -Math.max(0L, flow.getValueDelta()));
        observations = Math.max(0L, observations - 1L);
    }

    public void merge(TrackingGainedItemDetail other)
    {
        if (other == null) return;
        value = safeAdd(value, other.getValue());
        observations = safeAdd(observations, other.getObservations());
    }

    boolean isEmpty()
    {
        return getValue() <= 0L || getObservations() <= 0L;
    }

    public TrackingGainedItemDetail copy()
    {
        TrackingGainedItemDetail copy = new TrackingGainedItemDetail();
        copy.itemId = itemId;
        copy.itemName = getItemName();
        copy.quantity = getQuantity();
        copy.unitPrice = getUnitPrice();
        copy.value = getValue();
        copy.observations = getObservations();
        copy.priceSource = getPriceSource();
        copy.priceCapturedAtEpochMillis = getPriceCapturedAtEpochMillis();
        return copy;
    }

    public String aggregationKey()
    {
        return itemId + ":" + quantity + ":" + unitPrice + ":" + getItemName()
            + ":" + getPriceSource().name() + ":" + getPriceCapturedAtEpochMillis();
    }

    static String key(ItemFlow flow)
    {
        if (flow == null) return "";
        return Math.max(0, flow.getItemId()) + ":"
            + Math.max(0L, flow.getQuantityDelta()) + ":"
            + Math.max(0, flow.getUnitPrice()) + ":"
            + normalize(flow.getItemName()) + ":"
            + flow.getPriceSource().name() + ":"
            + flow.getPriceCapturedAtEpochMillis();
    }

    private static String normalize(String value)
    {
        return value == null || value.trim().isEmpty() ? "General" : value.trim();
    }

    private static long safeAdd(long left, long right)
    {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException ex) { return right >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE; }
    }
}
