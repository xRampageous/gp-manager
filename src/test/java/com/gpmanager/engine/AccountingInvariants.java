package com.gpmanager;


final class AccountingInvariants
{
    private AccountingInvariants()
    {
    }

    static boolean transferNeverCounts(Ac tx)
    {
        if (tx == null)
        {
            return true;
        }
        return tx.getType() != Ai.TRANSFER || !tx.isCounted();
    }
}
