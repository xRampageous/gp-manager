package com.gpmanager.model;

import java.util.Locale;

/**
 * Presentation-only provenance for a charge-load review row.
 *
 * <p>This records the observed variant, selected component, matched target and
 * observation time that explain an ambiguous load. It does not establish an
 * item flow, valuation, transaction type or counted state.</p>
 */
public final class ChargeLoadReviewProvenance
{
    private String variantWireName;
    private int selectedComponentId;
    private String matchedTargetIdentity;
    private long observedAtEpochMillis;

    public ChargeLoadReviewProvenance()
    {
        // Gson
    }

    public ChargeLoadReviewProvenance(
        String variantWireName,
        int selectedComponentId,
        String matchedTargetIdentity,
        long observedAtEpochMillis)
    {
        this.variantWireName = normalizeVariant(variantWireName);
        this.selectedComponentId = Math.max(0, selectedComponentId);
        this.matchedTargetIdentity = normalizeIdentity(matchedTargetIdentity);
        this.observedAtEpochMillis = Math.max(0L, observedAtEpochMillis);
    }

    public String getVariantWireName()
    {
        return normalizeVariant(variantWireName);
    }

    public int getSelectedComponentId()
    {
        return Math.max(0, selectedComponentId);
    }

    public String getMatchedTargetIdentity()
    {
        return normalizeIdentity(matchedTargetIdentity);
    }

    public long getObservedAtEpochMillis()
    {
        return Math.max(0L, observedAtEpochMillis);
    }

    public ChargeLoadReviewProvenance copy()
    {
        return new ChargeLoadReviewProvenance(
            getVariantWireName(),
            getSelectedComponentId(),
            getMatchedTargetIdentity(),
            getObservedAtEpochMillis());
    }

    private static String normalizeVariant(String value)
    {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeIdentity(String value)
    {
        return value == null ? "" : value.trim();
    }
}
