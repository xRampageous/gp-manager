package com.gpmanager;


/** Test-side reads of engine state; moved out of production to keep the plugin small. */
public final class EngineProbe
{
    private EngineProbe()
    {
    }

    private static BasisPool pool(Engine engine, int itemId) {
        synchronized (engine.trackedBasis) {
            return engine.trackedBasis.pools.get(itemId);
        }
    }

    public static long availableQty(Engine engine, int itemId) {
        BasisPool pool = pool(engine, itemId);
        return pool == null ? 0L : pool.getAvailableQty();
    }

    public static long availableBasisGp(Engine engine, int itemId) {
        BasisPool pool = pool(engine, itemId);
        return pool == null ? 0L : pool.getAvailableBasisGp();
    }

    /** Known coverage held out by the item's open SELL custody records. */
    public static long reservedQty(Engine engine, int itemId) {
        long total = 0L;
        for (GeOffer record : engine.geCustody.records()) {
            if (record.itemId == itemId && record.getSide() == GeOffer.Side.SELL) total += record.reservedQty();
        }
        return total;
    }

    public static long reservedBasisGp(Engine engine, int itemId) {
        long total = 0L;
        for (GeOffer record : engine.geCustody.records()) {
            if (record.itemId == itemId && record.getSide() == GeOffer.Side.SELL) total += record.reservedBasis();
        }
        return total;
    }

    /** Exact known coverage for one canonical item. */
    public static long knownCoverageQty(Engine engine, int itemId) {
        return availableQty(engine, itemId) + reservedQty(engine, itemId);
    }

    /** Exact known coverage basis for one canonical item. */
    public static long knownCoverageBasisGp(Engine engine, int itemId) {
        return availableBasisGp(engine, itemId) + reservedBasisGp(engine, itemId);
    }

    public static boolean isBaselinePriming(Engine engine) {
        synchronized (engine) {
            return engine.baselinePriming;
        }
    }

    public static boolean isAwaitingDeathReclaim(Engine engine)
    {
        synchronized (engine)
        {
            return engine.deathReclaim.isAwaitingReclaim();
        }
    }

    /** The cash retained internally pending exact attribution. */
    public static long pendingSettlementCash(GeCustodyLedger ledger)
    {
        synchronized (ledger)
        {
            return ledger.pendingSettlementCashGp;
        }
    }
}
