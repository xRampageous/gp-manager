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
        Am source = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        SavedState.Ap grind = source.avg("Vorkath", 5_000_000L, null, true, null);
        assertNotNull(grind);
        SavedState alice = source.qm();

        Am live = PresentationLifecycleTest.engine();
        live.agl("alice", alice, now);
        assertEquals("a loaded profile shows its Grinds", 1, live.getSavedGrinds(true).size());
        assertEquals("and the next save keeps them", 1, live.qm().getSavedGrinds().size());

        live.agl("bob", new SavedState(), now + 1_000L);
        assertEquals("another profile never inherits them", 0, live.getSavedGrinds(true).size());
        assertEquals(0, live.qm().getSavedGrinds().size());
    }

    @Test
    public void schemaIsAdditiveAndFutureVersionsStayReadOnly() throws Exception
    {
        assertEquals(108, SavedState.CURRENT_SCHEMA_VERSION);
        SavedState old = new SavedState();
        old.setSchemaVersion(103);
        assertFalse("pre-1.0 states are never read", old.ye());
        assertFalse((old.schemaVersion > SavedState.CURRENT_SCHEMA_VERSION));
        SavedState future = new SavedState();
        future.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION + 1);
        assertTrue((future.schemaVersion > SavedState.CURRENT_SCHEMA_VERSION));
        assertFalse("a newer state is never written back", future.ye());
    }

    @Test
    public void schema102UpgradeLeavesFinancialTotalsUntouchedAndAddsEmptyGrindState() throws Exception
    {
        Am source = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        source.ajl("Legacy grind", Cx.GENERAL, now);
        source.getActiveSession().kf(booked(now + 1_000L, "Dragon bones", 1, 2, 3_200, 6_400), 2_000);
        source.sx(now + 2_000L);
        SavedState legacy = source.qm();
        legacy.setSchemaVersion(102);

        Am upgraded = PresentationLifecycleTest.engine();
        upgraded.restore(legacy, now + 3_000L);
        assertEquals("no inference during upgrade", 0, upgraded.getSavedGrinds(true).size());
        assertEquals("history survives", 1, upgraded.getHistory().size());
        assertEquals("financial totals are untouched", 6_400L,
            upgraded.tz(upgraded.getHistory().get(0).getId(), now + 3_000L).net);
        assertFalse("old sessions are not auto-linked",
            upgraded.getHistory().get(0).xf());
    }

    @Test
    public void noRetroactiveNameMatchingDuringRestore() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        engine.getActiveSession().kf(booked(now + 1_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000);
        engine.sx(now + 2_000L);
        SavedState state = engine.qm();
        SavedState.Ap grind = engine.avg("Vorkath", null, null, false, null);
        assertNotNull(grind);
        state.setSavedGrinds(engine.getSavedGrinds(true));

        Am restored = PresentationLifecycleTest.engine();
        restored.restore(state, now + 3_000L);
        assertEquals(1, restored.getSavedGrinds(true).size());
        assertEquals("display names never link history", 0,
            restored.yk(grind.getGrindId()).size());
    }

    @Test
    public void nativeRoundTripKeepsGrindsTargetsAndLineage() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        engine.getActiveSession().setProfitTargetGp(5_000_000L);
        engine.getActiveSession().setActiveTimeTargetMillis(3L * 3_600_000L);
        String sessionId = engine.getActiveSession().getId();
        SavedState.Ap grind = engine.avg("Vorkath", 5_000_000L, 3L * 3_600_000L, true, sessionId);
        assertNotNull(grind);
        engine.sx(now + 1_000L);
        SavedState state = engine.qm();

        Am restored = PresentationLifecycleTest.engine();
        restored.restore(state, now + 2_000L);
        SavedState.Ap reloaded = restored.um(grind.getGrindId());
        assertNotNull("the definition round-trips", reloaded);
        assertEquals("Vorkath", reloaded.getName());
        assertEquals(Long.valueOf(5_000_000L), reloaded.getNetTargetGp());
        assertEquals(Long.valueOf(3L * 3_600_000L), reloaded.getActiveTimeTargetMillis());
        assertTrue(reloaded.favorite);
        assertEquals("lineage round-trips by stable id", 1,
            restored.yk(grind.getGrindId()).size());
        assertEquals("the actual target snapshot round-trips", Long.valueOf(5_000_000L),
            restored.ua(sessionId).getProfitTargetGp());
        assertEquals(Long.valueOf(3L * 3_600_000L),
            restored.ua(sessionId).getActiveTimeTargetMillis());
    }

    @Test
    public void savedGrindsAreProfileScoped() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        SavedState profileA = engine.qm();
        engine.avg("Vorkath", 5_000_000L, null, false, null);
        profileA = engine.qm();

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

        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        assertEquals("no inferred lineage for a new instance", "", engine.getActiveSession().getGrindId());
        assertNull(engine.getActiveSession().getActiveTimeTargetMillis());
        engine.getActiveSession().setGrindId(null);
        assertEquals("", engine.getActiveSession().getGrindId());
        engine.getActiveSession().setActiveTimeTargetMillis(null);
        assertNull(engine.getActiveSession().getActiveTimeTargetMillis());
    }

    @Test
    public void clearingTargetsWorksWithoutTouchingFinancialState() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        engine.getActiveSession().kf(booked(now + 1_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000);
        engine.ahq(5_000_000L, 3L * 3_600_000L, now + 2_000L);
        long net = engine.getMetrics(now + 2_000L).net;
        engine.ahq(null, null, now + 3_000L);
        assertNull(engine.getActiveSession().getProfitTargetGp());
        assertNull(engine.getActiveSession().getActiveTimeTargetMillis());
        assertEquals("clearing a target changes no money", net, engine.getMetrics(now + 3_000L).net);
    }

    private static Ac booked(long at, String name, int itemId, long quantity, int unitPrice, long value)
    {
        return new Ac(at, null, Ai.GAIN, Aj.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Ab(itemId, name, quantity, unitPrice, value)),
            Bd.LIKELY, "Test sample.", null);
    }
}
