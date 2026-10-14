package com.gpmanager;

import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** My Grinds persistence: one additive schema step inside the canonical state, truthful lineage. */
public class GrindsPersistenceTest
{
    /** The plugin loads every profile through agl, never through restore. */
    @Test
    public void profileLoadKeepsThatProfilesGrindsAndNoOtherProfiles() throws Exception
    {
        JsonCodec.bind(new com.google.gson.Gson());
        Engine source = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        SavedState.SavedGrind grind = source.saveGrind("Vorkath", 5_000_000L, null, true, null);
        assertNotNull(grind);
        SavedState alice = source.savedState();

        Engine live = PresentationLifecycleTest.engine();
        live.restoreProfile("alice", alice, now);
        assertEquals("a loaded profile shows its Grinds", 1, live.getSavedGrinds(true).size());
        assertEquals("and the next save keeps them", 1, live.savedState().getSavedGrinds().size());

        live.restoreProfile("bob", new SavedState(), now + 1_000L);
        assertEquals("another profile never inherits them", 0, live.getSavedGrinds(true).size());
        assertEquals(0, live.savedState().getSavedGrinds().size());
    }

    @Test
    public void schemaIsAdditiveAndFutureVersionsStayReadOnly() throws Exception
    {
        assertEquals(110, SavedState.CURRENT_SCHEMA_VERSION);
        SavedState old = new SavedState();
        old.setSchemaVersion(103);
        assertFalse("pre-1.0 states are never read", old.isSupportedSchema());
        assertFalse((old.schemaVersion > SavedState.CURRENT_SCHEMA_VERSION));
        SavedState future = new SavedState();
        future.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION + 1);
        assertTrue((future.schemaVersion > SavedState.CURRENT_SCHEMA_VERSION));
        assertFalse("a newer state is never written back", future.isSupportedSchema());
    }

    @Test
    public void schema102UpgradeLeavesFinancialTotalsUntouchedAndAddsEmptyGrindState() throws Exception
    {
        Engine source = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        source.startCustom("Legacy grind", now);
        source.getActiveSession().addTransaction(booked(now + 1_000L, "Dragon bones", 1, 2, 3_200, 6_400), 2_000);
        source.finishCustom(now + 2_000L);
        SavedState legacy = source.savedState();
        legacy.setSchemaVersion(102);

        Engine upgraded = PresentationLifecycleTest.engine();
        upgraded.restore(legacy, now + 3_000L);
        assertEquals("no inference during upgrade", 0, upgraded.getSavedGrinds(true).size());
        assertEquals("history survives", 1, upgraded.getHistory().size());
        assertEquals("financial totals are untouched", 6_400L,
            upgraded.historyMetrics(upgraded.getHistory().get(0).getId(), now + 3_000L).net);
        assertFalse("old sessions are not auto-linked",
            upgraded.getHistory().get(0).isLinked());
    }

    @Test
    public void noRetroactiveNameMatchingDuringRestore() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);
        engine.getActiveSession().addTransaction(booked(now + 1_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000);
        engine.finishCustom(now + 2_000L);
        SavedState state = engine.savedState();
        SavedState.SavedGrind grind = engine.saveGrind("Vorkath", null, null, false, null);
        assertNotNull(grind);
        state.setSavedGrinds(engine.getSavedGrinds(true));

        Engine restored = PresentationLifecycleTest.engine();
        restored.restore(state, now + 3_000L);
        assertEquals(1, restored.getSavedGrinds(true).size());
        assertEquals("display names never link history", 0,
            restored.linkedSessions(grind.getGrindId()).size());
    }

    @Test
    public void nativeRoundTripKeepsGrindsTargetsAndLineage() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now - 60_000L);
        engine.getActiveSession().setProfitTargetGp(5_000_000L);
        engine.getActiveSession().setActiveTimeTargetMillis(3L * 3_600_000L, now);
        long setAt = engine.getActiveSession().getActiveTimeTargetSetMillis();
        engine.getActiveSession().pauseTimeTarget(now + 500L);
        engine.getActiveSession().profitTargetSetNet = 12_345L;
        String sessionId = engine.getActiveSession().getId();
        SavedState.SavedGrind grind = engine.saveGrind("Vorkath", 5_000_000L, 3L * 3_600_000L, true, sessionId);
        assertNotNull(grind);
        engine.finishCustom(now + 1_000L);
        SavedState state = engine.savedState();

        Engine restored = PresentationLifecycleTest.engine();
        restored.restore(state, now + 2_000L);
        SavedState.SavedGrind reloaded = restored.savedGrind(grind.getGrindId());
        assertNotNull("the definition round-trips", reloaded);
        assertEquals("Vorkath", reloaded.getName());
        assertEquals(Long.valueOf(5_000_000L), reloaded.getNetTargetGp());
        assertEquals(Long.valueOf(3L * 3_600_000L), reloaded.getActiveTimeTargetMillis());
        assertTrue(reloaded.favorite);
        assertEquals("lineage round-trips by stable id", 1,
            restored.linkedSessions(grind.getGrindId()).size());
        assertEquals("the actual target snapshot round-trips", Long.valueOf(5_000_000L),
            restored.historySession(sessionId).getProfitTargetGp());
        assertEquals(Long.valueOf(3L * 3_600_000L),
            restored.historySession(sessionId).getActiveTimeTargetMillis());
        assertEquals("the count baseline round-trips", setAt,
            restored.historySession(sessionId).getActiveTimeTargetSetMillis());
        assertTrue("the timer pause round-trips",
            restored.historySession(sessionId).isTimeTargetPaused());
        assertEquals("the Net baseline round-trips", 12_345L,
            restored.historySession(sessionId).getProfitTargetSetNet());
    }

    @Test
    public void savedGrindsAreProfileScoped() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        SavedState profileA = engine.savedState();
        engine.saveGrind("Vorkath", 5_000_000L, null, false, null);
        profileA = engine.savedState();

        SavedState profileB = new SavedState();
        profileB.setOwnerKey("profile-b");

        engine.restore(profileA, System.currentTimeMillis());
        assertEquals("profile A sees exactly its own state", 1, engine.getSavedGrinds(true).size());
        engine.restore(profileB, System.currentTimeMillis());
        assertEquals("profile B never sees A's My Grinds", 0, engine.getSavedGrinds(true).size());
        engine.restore(profileA, System.currentTimeMillis());
        assertEquals(1, engine.getSavedGrinds(true).size());
    }

    @Test
    public void newFieldsDefaultSafelyOnLegacyShapes() throws Exception
    {
        SavedState legacy = new SavedState();
        legacy.setSchemaVersion(102);
        assertTrue("no NPE on a legacy state without the list", legacy.getSavedGrinds().isEmpty());

        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);
        assertEquals("no inferred lineage for a new instance", "", engine.getActiveSession().getGrindId());
        assertNull(engine.getActiveSession().getActiveTimeTargetMillis());
        engine.getActiveSession().setGrindId(null);
        assertEquals("", engine.getActiveSession().getGrindId());
        engine.getActiveSession().setActiveTimeTargetMillis(null, now);
        assertNull(engine.getActiveSession().getActiveTimeTargetMillis());
        assertEquals("clearing forgets the count", 0L,
            engine.getActiveSession().getActiveTimeTargetSetMillis());
    }

    @Test
    public void clearingTargetsWorksWithoutTouchingFinancialState() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);
        engine.getActiveSession().addTransaction(booked(now + 1_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000);
        engine.setTargets(5_000_000L, 3L * 3_600_000L, now + 2_000L);
        long net = engine.getMetrics(now + 2_000L).net;
        engine.setTargets(null, null, now + 3_000L);
        assertNull(engine.getActiveSession().getProfitTargetGp());
        assertNull(engine.getActiveSession().getActiveTimeTargetMillis());
        assertEquals("clearing a target changes no money", net, engine.getMetrics(now + 3_000L).net);
    }

    private static Transaction booked(long at, String name, int itemId, long quantity, int unitPrice, long value)
    {
        return new Transaction(at, TransactionType.GAIN, Context.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Flow(itemId, name, quantity, unitPrice, value)),
            Confidence.LIKELY, "Test sample.", null);
    }
}
