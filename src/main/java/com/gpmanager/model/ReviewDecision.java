package com.gpmanager.model;

/** A deliberate correction choice for an unresolved transaction. */
public enum ReviewDecision
{
    GAIN(TransactionCorrection.REVENUE),
    COST(TransactionCorrection.COST),
    TRANSFER(TransactionCorrection.TRANSFER),
    IGNORE(TransactionCorrection.IGNORE);

    private final TransactionCorrection correction;

    ReviewDecision(TransactionCorrection correction)
    {
        this.correction = correction;
    }

    public TransactionCorrection toCorrection()
    {
        return correction;
    }
}
