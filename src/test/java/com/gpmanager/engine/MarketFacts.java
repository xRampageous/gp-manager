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
    public static MarketSettlementProjection.Row row(GeRecord record)
    {
        return MarketSettlementProjection.rows(Collections.singletonList(record), null, null).get(0);
    }

    public static GeRecord record(Engine engine, MarketSettlementProjection.Row row)
    {
        for (GeRecord record : engine.geCustody.snapshotRecords())
        {
            if (record.getOfferId().equals(row.presentationId))
            {
                return record;
            }
        }
        throw new AssertionError("no custody record for " + row.presentationId);
    }

    private static boolean sell(GeRecord r)
    {
        return r.getSide() == GeRecord.Side.SELL;
    }

    private static boolean basisKnown(GeRecord r)
    {
        return r.hasFrozenBasis() && !r.isLegacyUnbased();
    }

    public static long basisValueGp(GeRecord r)
    {
        return !basisKnown(r) ? -1L : !sell(r) && r.collectedValueGp != 0L
            ? SafeMath.abs(r.collectedValueGp) : r.lo(r.getFilledQty());
    }

    public static long realizedGrossGp(GeRecord r)
    {
        long settled = r.getSettledQty();
        long adjustment = sell(r) && settled > 0L ? r.settledCashGp - r.getSettledExecutionGp() : 0L;
        return settled <= 0L ? 0L : sell(r) ? r.settledCashGp - adjustment : SafeMath.abs(r.settledCashGp);
    }

    public static long trackedQtyConsumed(GeRecord r)
    {
        long settled = r.getSettledQty();
        return settled <= 0L ? 0L : sell(r) ? Math.min(r.getConsumedTrackedQty(), settled) : settled;
    }

    public static long unknownQtyRealized(GeRecord r)
    {
        long settled = r.getSettledQty();
        return settled <= 0L ? 0L : Math.max(0L, settled - trackedQtyConsumed(r));
    }

    public static long knownProceedsGp(GeRecord r)
    {
        long settled = r.getSettledQty();
        long observed = Math.abs(r.settledCashGp);
        return settled <= 0L ? 0L : sell(r)
            ? TrackedBasisMath.knownProceedsOf(observed, settled, trackedQtyConsumed(r)) : observed;
    }

    public static long unknownLiquidationGp(GeRecord r)
    {
        return r.getSettledQty() <= 0L ? 0L : Math.max(0L, Math.abs(r.settledCashGp) - knownProceedsGp(r));
    }

    public static long grossGeReferenceGp(GeRecord r)
    {
        long settled = r.getSettledQty();
        return settled <= 0L || !basisKnown(r) ? -1L : r.lo(settled);
    }
}
