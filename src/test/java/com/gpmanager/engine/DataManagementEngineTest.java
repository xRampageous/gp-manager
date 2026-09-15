package com.gpmanager.engine;

import com.gpmanager.GpManagerConfig;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.SessionMode;
import org.junit.Test;
import static org.junit.Assert.*;

public class DataManagementEngineTest
{
    private GpManagerEngine engine()
    {
        return new GpManagerEngine(deltas -> java.util.Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
    }

    @Test public void clearHistoryPreservesOwnersAndTargets()
    {
        GpManagerEngine engine = engine();
        engine.ensureSession(1L);
        engine.getGeneralSession().setProfitTargetGp(100L);
        engine.startCustomSession("Custom", SessionMode.AUTO, 2L);
        engine.finishCustomSession(3L);
        assertEquals(1, engine.clearCompletedHistory());
        assertEquals(0, engine.getHistory().size());
        assertEquals(Long.valueOf(100L), engine.getGeneralSession().getProfitTargetGp());
    }

    @Test public void restartGeneralRefusesCustomAndCreatesStoppedFreshOwner()
    {
        GpManagerEngine engine = engine();
        engine.ensureSession(1L);
        engine.getGeneralSession().setProfitTargetGp(100L);
        engine.startCustomSession("Custom", SessionMode.AUTO, 2L);
        assertFalse(engine.restartGeneral(3L, true, true));
        engine.finishCustomSession(4L);
        assertTrue(engine.restartGeneral(5L, true, true));
        assertTrue(engine.getActiveSession().isStopped());
        assertEquals("Overall", engine.getActiveSession().getName());
        assertEquals(Long.valueOf(100L), engine.getActiveSession().getProfitTargetGp());
    }

    @Test public void resetTrackingClearsOwnersHistoryTargetsAndArmsFreshGeneralWhenAutoStartOn()
    {
        GpManagerEngine engine = engine();
        engine.ensureSession(1L);
        engine.getGeneralSession().setProfitTargetGp(100L);
        engine.startCustomSession("Custom", SessionMode.AUTO, 2L);
        engine.resetTrackingData(3L);
        assertEquals(0, engine.getHistory().size());
        assertFalse(engine.isCustomSessionActive());
        assertFalse(engine.getActiveSession().isStopped());
        assertFalse(engine.getActiveSession().isPaused());
        assertTrue(engine.isBaselinePriming());
        assertNull(engine.getGeneralSession().getProfitTargetGp());
    }

    @Test public void resetTrackingStopsWhenAutoStartDisabledButRemainsWakeEligible()
    {
        GpManagerEngine engine = new GpManagerEngine(
            deltas -> java.util.Collections.emptyList(),
            new TransactionClassifier(),
            new GpManagerConfig()
            {
                @Override
                public boolean autoStartSession()
                {
                    return false;
                }
            });
        engine.ensureSession(1L);
        engine.resetTrackingData(2L);
        assertTrue(engine.getActiveSession().isStopped());
        assertTrue(engine.isAutoStartEligibleAfterReset());
    }
}
