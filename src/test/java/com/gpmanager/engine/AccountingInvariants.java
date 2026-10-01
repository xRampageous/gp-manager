package com.gpmanager;


final class AccountingInvariants
{
    private AccountingInvariants()
    {
    }

    static boolean transferNeverCounts(Transaction tx)
    {
        if (tx == null)
        {
            return true;
        }
        return tx.getType() != TransactionType.TRANSFER || !tx.isCounted();
    }
}
