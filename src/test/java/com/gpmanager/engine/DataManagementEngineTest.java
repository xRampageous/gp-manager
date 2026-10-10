package com.gpmanager;

import org.junit.Test;
import static org.junit.Assert.*;

public class DataManagementEngineTest
{
    private Engine engine()
    {
        return new Engine(deltas -> java.util.Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
    }

    @Test public void clearHistoryPreservesOwnersAndTargets()
    {
        Engine engine = engine();
        engine.ensureSession(1L);
        engine.getGeneralSession().setProfitTargetGp(100L);
        engine.startCustom("Custom", 2L);
        engine.finishCustom(3L);
        assertTrue(engine.deleteHistory(engine.getHistory().get(0).getId()));
        assertEquals(0, engine.getHistory().size());
        assertEquals(Long.valueOf(100L), engine.getGeneralSession().getProfitTargetGp());
    }

    @Test public void resetTrackingClearsOwnersHistoryAndTargetsLikeANewInstall()
    {
        Engine engine = engine();
        engine.ensureSession(1L);
        engine.getGeneralSession().setProfitTargetGp(100L);
        engine.startCustom("Custom", 2L);
        engine.resetData(3L);
        assertEquals(0, engine.getHistory().size());
        assertFalse(engine.isCustomActive());
        // A reset is a new install: no owner (and so no target) until play or Start.
        assertNull(engine.getActiveSession());
        assertNull(engine.getGeneralSession());
    }
}
