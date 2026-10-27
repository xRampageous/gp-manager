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
        SavedState.SavedGrind grind = source.saveGrind("Vorkath", 5_000_000L, null, true);
        assertNotNull(grind);
        SavedState alice = source.savedState();

        Engine live = PresentationLifecycleTest.engine();
        live.restoreProfile("alice", alice, now);
        assertEquals("a loaded profile shows its Grinds", 1, live.plans(true).size());
        assertEquals("and the next save keeps them", 1, live.savedState().savedGrinds().size());

        live.restoreProfile("bob", new SavedState(), now + 1_000L);
        assertEquals("another profile never inherits them", 0, live.plans(true).size());
        assertEquals(0, live.savedState().savedGrinds().size());
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
        source.session().addTransaction(booked(now + 1_000L, "Dragon bones", 1, 2, 3_200, 6_400), 2_000, false);
        source.finishCustom(now + 2_000L);
        SavedState legacy = source.savedState();
        legacy.setSchemaVersion(102);

        Engine upgraded = PresentationLifecycleTest.engine();
        upgraded.restore(legacy, now + 3_000L);
        assertEquals("no inference during upgrade", 0, upgraded.plans(true).size());
        assertEquals("history survives", 1, upgraded.history().size());
        assertEquals("financial totals are untouched", 6_400L,
            upgraded.historyMetrics(upgraded.history().get(0).id(), now + 3_000L).net);
        assertFalse("old sessions are not auto-linked",
            upgraded.history().get(0).isLinked());
    }

    @Test
    public void noRetroactiveNameMatchingDuringRestore() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);
        engine.session().addTransaction(booked(now + 1_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000, false);
        engine.finishCustom(now + 2_000L);
        SavedState state = engine.savedState();
        SavedState.SavedGrind grind = engine.saveGrind("Vorkath", null, null, false);
        assertNotNull(grind);
        state.setSavedGrinds(engine.plans(true));

        Engine restored = PresentationLifecycleTest.engine();
        restored.restore(state, now + 3_000L);
        assertEquals(1, restored.plans(true).size());
        assertEquals("display names never link history", 0,
            restored.linkedSessions(grind.id()).size());
    }

    @Test
    public void nativeRoundTripKeepsGrindsTargetsAndLineage() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now - 60_000L);
        engine.session().setNetTarget(5_000_000L, 0, 0);
        engine.session().setTimeTarget(3L * 3_600_000L, now);
        long setAt = engine.session().timeSetMillis();
        engine.session().pauseTimeTarget(now + 500L);
        engine.session().profitTargetSetNet = 12_345L;
        String sessionId = engine.session().id();
        SavedState.SavedGrind grind = engine.saveGrind("Vorkath", 5_000_000L, 3L * 3_600_000L, true);
        engine.session().setGrindId(grind.id());
        assertNotNull(grind);
        engine.finishCustom(now + 1_000L);
        SavedState state = engine.savedState();

        Engine restored = PresentationLifecycleTest.engine();
        restored.restore(state, now + 2_000L);
        SavedState.SavedGrind reloaded = restored.plan(grind.id());
        assertNotNull("the definition round-trips", reloaded);
        assertEquals("Vorkath", reloaded.name());
        assertEquals(Long.valueOf(5_000_000L), reloaded.targetGp());
        assertEquals(Long.valueOf(3L * 3_600_000L), reloaded.timeTargetMillis());
        assertTrue(reloaded.favorite);
        assertEquals("lineage round-trips by stable id", 1,
            restored.linkedSessions(grind.id()).size());
        assertEquals("the actual target snapshot round-trips", Long.valueOf(5_000_000L),
            restored.historySession(sessionId).targetGp());
        assertEquals(Long.valueOf(3L * 3_600_000L),
            restored.historySession(sessionId).timeTargetMillis());
        assertEquals("the count baseline round-trips", setAt,
            restored.historySession(sessionId).timeSetMillis());
        assertTrue("the timer pause round-trips",
            restored.historySession(sessionId).timeTargetPaused());
        assertEquals("the Net baseline round-trips", 12_345L,
            restored.historySession(sessionId).profitTargetSetNet());
    }

    @Test
    public void savedGrindsAreProfileScoped() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        SavedState profileA = engine.savedState();
        engine.saveGrind("Vorkath", 5_000_000L, null, false);
        profileA = engine.savedState();

        SavedState profileB = new SavedState();
        profileB.setOwnerKey("profile-b");

        engine.restore(profileA, System.currentTimeMillis());
        assertEquals("profile A sees exactly its own state", 1, engine.plans(true).size());
        engine.restore(profileB, System.currentTimeMillis());
        assertEquals("profile B never sees A's My Grinds", 0, engine.plans(true).size());
        engine.restore(profileA, System.currentTimeMillis());
        assertEquals(1, engine.plans(true).size());
    }

    @Test
    public void newFieldsDefaultSafelyOnLegacyShapes() throws Exception
    {
        SavedState legacy = new SavedState();
        legacy.setSchemaVersion(102);
        assertTrue("no NPE on a legacy state without the list", legacy.savedGrinds().isEmpty());

        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);
        assertEquals("no inferred lineage for a new instance", "", engine.session().grindId());
        assertNull(engine.session().timeTargetMillis());
        engine.session().setGrindId(null);
        assertEquals("", engine.session().grindId());
        engine.session().setTimeTarget(null, now);
        assertNull(engine.session().timeTargetMillis());
        assertEquals("clearing forgets the count", 0L,
            engine.session().timeSetMillis());
    }

    @Test
    public void clearingTargetsWorksWithoutTouchingFinancialState() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);
        engine.session().addTransaction(booked(now + 1_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000, false);
        engine.setTargets(5_000_000L, 3L * 3_600_000L, now + 2_000L);
        long net = engine.metrics(now + 2_000L).net;
        engine.setTargets(null, null, now + 3_000L);
        assertNull(engine.session().targetGp());
        assertNull(engine.session().timeTargetMillis());
        assertEquals("clearing a target changes no money", net, engine.metrics(now + 3_000L).net);
    }

    private static Transaction booked(long at, String name, int itemId, long quantity, int unitPrice, long value)
    {
        return new Transaction(at, TransactionType.GAIN, Context.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Flow(itemId, name, quantity, unitPrice, value)),
            Confidence.LIKELY, "Test sample.", null);
    }
}
