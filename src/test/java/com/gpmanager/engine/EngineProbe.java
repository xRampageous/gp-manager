package com.gpmanager;

import java.util.List;

/** Test-side reads of engine state; moved out of production to keep the plugin small. */
public final class EngineProbe
{
    private EngineProbe()
    {
    }

    private static Az pool(Am engine, int itemId) {
        synchronized (engine.trackedBasis) {
            return engine.trackedBasis.pools.get(itemId);
        }
    }

    public static long availableQty(Am engine, int itemId) {
        Az pool = pool(engine, itemId);
        return pool == null ? 0L : pool.getAvailableQty();
    }

    public static long availableBasisGp(Am engine, int itemId) {
        Az pool = pool(engine, itemId);
        return pool == null ? 0L : pool.getAvailableBasisGp();
    }

    public static long reservedQty(Am engine, int itemId) {
        Az pool = pool(engine, itemId);
        return pool == null ? 0L : pool.getReservedQty();
    }

    public static long reservedBasisGp(Am engine, int itemId) {
        Az pool = pool(engine, itemId);
        return pool == null ? 0L : pool.getReservedBasisGp();
    }

    /** Exact known coverage for one canonical item. */
    public static long knownCoverageQty(Am engine, int itemId) {
        return availableQty(engine, itemId) + reservedQty(engine, itemId);
    }

    /** Exact known coverage basis for one canonical item. */
    public static long knownCoverageBasisGp(Am engine, int itemId) {
        return availableBasisGp(engine, itemId) + reservedBasisGp(engine, itemId);
    }

    public static boolean isBaselinePriming(Am engine) {
        synchronized (engine) {
            return engine.baselinePriming;
        }
    }

    public static boolean isAwaitingDeathReclaim(Am engine)
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

    /** Profile PvP facts over the retained Sessions' projections and anchors. */
    public static PkProfileBase profileFacts(PkHistoryArchive archive, List<Ad> sessions)
    {
        PkProfileBase facts = new PkProfileBase();
        if (sessions != null)
        {
            for (Ad session : sessions)
            {
                if (session != null && session.pkProjection != null)
                {
                    facts.merge(session.pkProjection, session.getPkAttributions());
                }
            }
        }
        return facts;
    }
}
