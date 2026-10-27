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
    public static MarketRows.Row row(GeOffer record)
    {
        return MarketRows.rows(Collections.singletonList(record), null, null).get(0);
    }

    public static GeOffer record(Engine engine, MarketRows.Row row)
    {
        for (GeOffer record : engine.exchange.records())
        {
            if (record.getOfferId().equals(row.presentationId))
            {
                return record;
            }
        }
        throw new AssertionError("no custody record for " + row.presentationId);
    }

    private static boolean sell(GeOffer r)
    {
        return r.getSide() == GeOffer.Side.SELL;
    }

    private static boolean basisKnown(GeOffer r)
    {
        return r.hasFrozenBasis() && !r.unbased();
    }

    public static long basisValueGp(GeOffer r)
    {
        return !basisKnown(r) ? -1L : !sell(r) && r.collectedValueGp != 0L
            ? SafeMath.abs(r.collectedValueGp) : r.basisFor(r.getFilledQty());
    }

    public static long realizedGrossGp(GeOffer r)
    {
        long settled = r.getSettledQty();
        long adjustment = sell(r) && settled > 0L ? r.settledCashGp - r.getSettledExecutionGp() : 0L;
        return settled <= 0L ? 0L : sell(r) ? r.settledCashGp - adjustment : SafeMath.abs(r.settledCashGp);
    }

    public static long trackedQtyConsumed(GeOffer r)
    {
        long settled = r.getSettledQty();
        return settled <= 0L ? 0L : sell(r) ? Math.min(r.getConsumedTrackedQty(), settled) : settled;
    }

    public static long unknownQtyRealized(GeOffer r)
    {
        long settled = r.getSettledQty();
        return settled <= 0L ? 0L : Math.max(0L, settled - trackedQtyConsumed(r));
    }

    public static long knownProceedsGp(GeOffer r)
    {
        long settled = r.getSettledQty();
        long observed = Math.abs(r.settledCashGp);
        return settled <= 0L ? 0L : sell(r)
            ? BasisMath.shareOf(settled, observed, trackedQtyConsumed(r)) : observed;
    }

    public static long unknownLiquidationGp(GeOffer r)
    {
        return r.getSettledQty() <= 0L ? 0L : Math.max(0L, Math.abs(r.settledCashGp) - knownProceedsGp(r));
    }

    public static long grossGeReferenceGp(GeOffer r)
    {
        long settled = r.getSettledQty();
        return settled <= 0L || !basisKnown(r) ? -1L : r.basisFor(settled);
    }
}
