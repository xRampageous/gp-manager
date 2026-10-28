package com.gpmanager;

import java.util.ArrayList;
import java.util.List;

/** Test-side reads of model state; moved out of production to keep the plugin small. */
public final class ModelProbe
{
    private ModelProbe()
    {
    }

    /** A valuation from a plain price per item id: NORMAL economy, no guard (moved out of the plugin). */
    public static Valuation valuation(GpManagerConfig config, java.util.function.IntFunction<net.runelite.api.ItemComposition> compositions,
        java.util.function.IntUnaryOperator prices)
    {
        return new Valuation(config, compositions, (id, active) -> prices.applyAsInt(id), id -> false, Valuation.Economy.NORMAL);
    }

    public static Valuation valuation(GpManagerConfig config, java.util.function.IntFunction<net.runelite.api.ItemComposition> compositions,
        Valuation.MarketQuote quotes)
    {
        return new Valuation(config, compositions, quotes, id -> false, Valuation.Economy.NORMAL);
    }

    /** The session's corrections that have not been undone, oldest first. */
    public static List<CorrectionRecord> activeCorrections(Session session)
    {
        List<CorrectionRecord> active = new ArrayList<>();
        for (CorrectionRecord record : session.correctionHistory)
        {
            if (record != null && !record.undone)
            {
                active.add(record);
            }
        }
        return active;
    }
}
