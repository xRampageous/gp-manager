package com.gpmanager;

import org.junit.Test;
import static org.junit.Assert.*;

public class DataManagementEngineTest
{
    private Am engine()
    {
        return new Am(deltas -> java.util.Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
    }

    @Test public void clearHistoryPreservesOwnersAndTargets()
    {
        Am engine = engine();
        engine.rm(1L);
        engine.getGeneralSession().setProfitTargetGp(100L);
        engine.ajl("Custom", Cx.AUTO, 2L);
        engine.sx(3L);
        assertTrue(engine.qu(engine.getHistory().get(0).getId()));
        assertEquals(0, engine.getHistory().size());
        assertEquals(Long.valueOf(100L), engine.getGeneralSession().getProfitTargetGp());
    }

    @Test public void resetTrackingClearsOwnersHistoryAndTargetsLikeANewInstall()
    {
        Am engine = engine();
        engine.rm(1L);
        engine.getGeneralSession().setProfitTargetGp(100L);
        engine.ajl("Custom", Cx.AUTO, 2L);
        engine.agr(3L);
        assertEquals(0, engine.getHistory().size());
        assertFalse(engine.wb());
        // A reset is a new install: no owner (and so no target) until play or Start.
        assertNull(engine.getActiveSession());
        assertNull(engine.getGeneralSession());
    }
}
