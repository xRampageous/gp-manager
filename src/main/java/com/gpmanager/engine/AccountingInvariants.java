package com.gpmanager.engine;

import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TransactionType;
import java.util.List;

/**
 * Audit helpers for plan §12 invariants. Used by tests and defensive call sites —
 * never invents accounting on its own.
 */
public final class AccountingInvariants
{
    private AccountingInvariants()
    {
    }

    /** TRANSFER never counts toward Net. */
    public static boolean transferNeverCounts(ProfitTransaction tx)
    {
        if (tx == null)
        {
            return true;
        }
        if (tx.getType() == TransactionType.TRANSFER)
        {
            return !tx.isCounted();
        }
        return true;
    }

    /**
     * GE tax XOR invent shortfall: at most one of explicit tax row vs invent-embedded
     * shortfall should add an extra tax cost on the same settle.
     */
    public static boolean geTaxXorHolds(GeSellTaxBooking.Result result)
    {
        if (result == null || result.taxBooked <= 0L)
        {
            return true;
        }
        int taxRows = 0;
        List<ItemFlow> flows = result.flows;
        if (flows != null)
        {
            for (ItemFlow flow : flows)
            {
                if (GeSellTaxBooking.isTaxFlow(flow))
                {
                    taxRows++;
                }
            }
        }
        if (result.explicitRow)
        {
            return taxRows == 1;
        }
        return taxRows == 0;
    }

    public static boolean noTaxOnPlayerTradeOrBuy(String note, long taxBooked)
    {
        if (taxBooked <= 0L)
        {
            return true;
        }
        // Tax only allowed on GE Collect — buys / player trades / shops stay 0.
        return GeSellTaxBooking.looksLikeGeCollect(note);
    }
}
