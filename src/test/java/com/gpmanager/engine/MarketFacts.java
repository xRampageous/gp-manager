package com.gpmanager;

import java.util.Collections;

/**
 * Settlement facts the Market read model no longer carries because no page shows them, derived
 * from the custody record with the formulas the read model used. Tests keep asserting the ledger
 * truth behind every Market row.
 */
public final class MarketFacts
{
    private MarketFacts()
    {
    }

    /** The read-model row for one record (the projection's own constructor is private). */
    public static Bi.Row row(Aa record)
    {
        return Bi.rows(Collections.singletonList(record), null, null).get(0);
    }

    public static Aa record(Am engine, Bi.Row row)
    {
        for (Aa record : engine.geCustody.aji())
        {
            if (record.getOfferId().equals(row.presentationId))
            {
                return record;
            }
        }
        throw new AssertionError("no custody record for " + row.presentationId);
    }

    private static boolean sell(Aa r)
    {
        return r.getSide() == Aa.Side.SELL;
    }

    private static boolean basisKnown(Aa r)
    {
        return r.vf() && !r.wx();
    }

    public static long basisValueGp(Aa r)
    {
        return !basisKnown(r) ? -1L : !sell(r) && r.collectedValueGp != 0L
            ? Ae.abs(r.collectedValueGp) : r.lo(r.getFilledQty());
    }

    public static long realizedGrossGp(Aa r)
    {
        long settled = r.getSettledQty();
        long adjustment = sell(r) && settled > 0L ? r.settledCashGp - r.getSettledExecutionGp() : 0L;
        return settled <= 0L ? 0L : sell(r) ? r.settledCashGp - adjustment : Ae.abs(r.settledCashGp);
    }

    public static long trackedQtyConsumed(Aa r)
    {
        long settled = r.getSettledQty();
        return settled <= 0L ? 0L : sell(r) ? Math.min(r.getConsumedTrackedQty(), settled) : settled;
    }

    public static long unknownQtyRealized(Aa r)
    {
        long settled = r.getSettledQty();
        return settled <= 0L ? 0L : Math.max(0L, settled - trackedQtyConsumed(r));
    }

    public static long knownProceedsGp(Aa r)
    {
        long settled = r.getSettledQty();
        long observed = Math.abs(r.settledCashGp);
        return settled <= 0L ? 0L : sell(r)
            ? Df.yl(observed, settled, trackedQtyConsumed(r)) : observed;
    }

    public static long unknownLiquidationGp(Aa r)
    {
        return r.getSettledQty() <= 0L ? 0L : Math.max(0L, Math.abs(r.settledCashGp) - knownProceedsGp(r));
    }

    public static long grossGeReferenceGp(Aa r)
    {
        long settled = r.getSettledQty();
        return settled <= 0L || !basisKnown(r) ? -1L : r.lo(settled);
    }
}
