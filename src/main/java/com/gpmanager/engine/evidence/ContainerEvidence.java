package com.gpmanager.engine.evidence;

/**
 * Container store/withdraw evidence markers. Ownership-neutral parks must not
 * invent Net; utility-container families use {@link ChargeAccountingGate}.
 */
public final class ContainerEvidence
{
    public enum Kind
    {
        STORE,
        WITHDRAW,
        EMPTY_TO_BANK,
        EMPTY_TO_INVENTORY,
        CALIBRATE_CHECK
    }

    private final String familyId;
    private final Kind kind;
    private final int itemId;
    private final long quantity;
    private final String note;

    public ContainerEvidence(String familyId, Kind kind, int itemId, long quantity, String note)
    {
        this.familyId = familyId == null ? "" : familyId;
        this.kind = kind == null ? Kind.STORE : kind;
        this.itemId = itemId;
        this.quantity = quantity;
        this.note = note == null ? "" : note;
    }

    public String getFamilyId()
    {
        return familyId;
    }

    public Kind getKind()
    {
        return kind;
    }

    public int getItemId()
    {
        return itemId;
    }

    public long getQuantity()
    {
        return quantity;
    }

    public String getNote()
    {
        return note;
    }

    /** True when this evidence may book component GP into Net. */
    public boolean mayBookCosts()
    {
        return kind == Kind.CALIBRATE_CHECK
            || ChargeAccountingGate.isCalibrated(familyId);
    }
}
